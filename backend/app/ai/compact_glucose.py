"""Pure statistical view using the original daily-glucose continuity and moments."""

from bisect import bisect_right
from dataclasses import dataclass, field

from .daily_glucose import (
    MAX_GAP_MS,
    MINUTE_MS,
    _Accumulator,
    _Episode,
    _add_segment,
    _prepare_series,
)

COLUMNS = ["n", "covered_s", "first_q", "last_q", "mean_q", "min_q", "max_q"]
MAX_DETAILS = 256
MAX_CELLS = 8192


@dataclass
class _Bin:
    accumulator: _Accumulator = field(default_factory=_Accumulator)
    count: int = 0
    first: float | None = None
    last: float | None = None
    minimum: float | None = None
    maximum: float | None = None
    min_at: int | None = None
    max_at: int | None = None

    def observe(self, at, value):
        self.count += 1
        if self.first is None:
            self.first = value
        self.last = value
        if self.minimum is None or value < self.minimum:
            self.minimum, self.min_at = value, at
        if self.maximum is None or value > self.maximum:
            self.maximum, self.max_at = value, at

    def row(self):
        def q(value):
            return None if value is None else round(value * 100)

        acc = self.accumulator
        return [
            self.count,
            acc.milliseconds / 1000,
            q(self.first),
            q(self.last),
            q(acc.mean) if acc.milliseconds else None,
            q(self.minimum),
            q(self.maximum),
        ]


def grids(start, end, background, focus):
    recent = max(start, end - 72 * 60 * MINUTE_MS)
    cuts = sorted(
        {start, end, recent, *(f.start_ms for f in focus), *(f.end_ms for f in focus)}
    )
    result = []
    for left, right in zip(cuts, cuts[1:]):
        step = background if right <= recent else 10
        step = min(
            [step]
            + [
                f.step_minutes
                for f in focus
                if f.start_ms <= left and right <= f.end_ms
            ]
        )
        if result and result[-1][1] == left and result[-1][2] == step:
            result[-1] = (result[-1][0], right, step)
        else:
            result.append((left, right, step))
    return result


def project_glucose(rows, changes, ranges, start, end, background, focus, digest):
    series = _prepare_series(rows, changes, start, end, max_unusable=MAX_DETAILS)
    layout = grids(start, end, background, focus)
    cell_count = sum(
        (b - a + step * MINUTE_MS - 1) // (step * MINUTE_MS) for a, b, step in layout
    ) * len(series.groups)
    if cell_count > MAX_CELLS:
        raise ValueError("compact_context_projection_capacity")
    cells, ordered, starts = {}, [], []
    for a, b, step in layout:
        for at in range(a, b, step * MINUTE_MS):
            starts.append(at)
            ordered.append((at, min(b, at + step * MINUTE_MS)))

    def cell(key, index):
        return cells.setdefault((key, index), _Bin())

    for at, key, value, _ in series.points:
        if key is not None and start <= at < end:
            cell(key, bisect_right(starts, at) - 1).observe(at, value)

    exceptions = [
        dict(
            atMs=at,
            reason=reason,
            evidence=[
                dict(
                    recordId=r.recordId,
                    revision=r.revision,
                    source=r.source,
                    quality=r.quality,
                    glucoseMmol=r.data["glucoseMmol"],
                )
                for r in evidence
            ],
        )
        for at, reason, evidence in series.unusable
    ]
    exceptions += [
        dict(
            atMs=r.eventAtMs,
            reason="SENSOR_CHANGE",
            evidence=[dict(recordId=r.recordId, revision=r.revision)],
        )
        for r in changes
        if not r.deleted and start <= r.eventAtMs < end
    ]
    if len(exceptions) > MAX_DETAILS:
        raise ValueError("compact_context_projection_capacity")
    range_starts = [r.startMs for r in ranges]
    episodes, covered = [], []
    for first, second in zip(series.points, series.points[1:]):
        t0, key, y0, refs0 = first
        t1, other, y1, refs1 = second
        a, b = max(t0, start), min(t1, end)
        if a >= b:
            continue
        if key is None or key != other or t1 - t0 > MAX_GAP_MS:
            reason = "GAP" if t1 - t0 > MAX_GAP_MS else "CONTINUITY_BREAK"
            exceptions.append(dict(startMs=a, endMs=b, reason=reason))
            if len(exceptions) > MAX_DETAILS:
                raise ValueError("compact_context_projection_capacity")
            continue

        def value_at(t):
            return y0 + (y1 - y0) * (t - t0) / (t1 - t0)

        covered.append((a, b))
        for p, q, band, target in _add_segment(
            series.groups[key][2], a, b, value_at, ranges, range_starts
        ):
            if band == "inside":
                continue
            label = "BELOW_RANGE" if band == "below" else "ABOVE_RANGE"
            prior = episodes[-1] if episodes else None
            if (
                prior is None
                or prior.cohort != key
                or prior.band != label
                or prior.end != p
                or prior.target != target
            ):
                prior = _Episode(key, label, float(p), float(q), target)
                episodes.append(prior)
                if len(episodes) > MAX_DETAILS:
                    raise ValueError("compact_context_projection_capacity")
            prior.end = float(q)
            prior.references.update(
                ((r.recordId, r.revision), r) for r in refs0 + refs1
            )
        index = bisect_right(starts, a) - 1
        while index < len(ordered) and ordered[index][0] < b:
            left, right = max(a, ordered[index][0]), min(b, ordered[index][1])
            cell(key, index).accumulator.add(
                right - left, value_at(left), value_at(right)
            )
            index += 1

    cohort_ids = sorted(series.groups)
    cohorts = []
    for key in cohort_ids:
        row, count, acc, epoch = series.groups[key]
        cohorts.append(
            dict(
                cohortId=key,
                source=row.source,
                quality=row.quality,
                context=row.context.model_dump() if row.context else None,
                sensorChangeEpoch=epoch,
                inWindowRecordCount=count,
                metrics=acc.finish().model_dump(),
            )
        )
    output = []
    for cohort_index, key in enumerate(cohort_ids):
        index = 0
        for a, b, step in layout:
            data, extrema = [], []
            for at in range(a, b, step * MINUTE_MS):
                item = cells.get((key, index), _Bin())
                data.append(item.row())
                extrema.append(
                    [None if v is None else v - at for v in (item.min_at, item.max_at)]
                )
                index += 1
            output.append(
                dict(
                    cohort=cohort_index,
                    startMs=a,
                    endMs=b,
                    stepMinutes=step,
                    rows=data,
                    extremaOffsetsMs=extrema,
                )
            )
    total_ms = sum(group[2].milliseconds for group in series.groups.values())
    previous, max_missing = start, 0
    for a, b in covered:
        max_missing = max(max_missing, a - previous)
        previous = b
    max_missing = max(max_missing, end - previous)
    return dict(
        columns=COLUMNS,
        valueScaleMmol=0.01,
        extremaOffsetUnit="ms",
        cohorts=cohorts,
        series=output,
        exceptions=exceptions,
        episodes=[e.finish(digest).model_dump() for e in episodes],
        omittedEpisodes=0,
        coveredMinutes=total_ms / MINUTE_MS,
        missingMinutes=max(0, end - start - total_ms) / MINUTE_MS,
        maxUncoveredMinutes=max_missing / MINUTE_MS,
        qualityCounts=series.counts,
        conflictingTimestamps=series.conflicts,
        inputRecordCount=len(rows),
    )
