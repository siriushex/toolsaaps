"""Descriptive glucose facts, not a clinical detector or treatment policy."""

from __future__ import annotations

import hashlib
import math
import re
from bisect import bisect_right
from dataclasses import dataclass, field
from datetime import UTC, date, datetime, time, timedelta
from typing import Annotated, Literal
from zoneinfo import ZoneInfo, ZoneInfoNotFoundError

from pydantic import Field, ValidationError, model_validator

from .history_contract import (
    Closed,
    Code,
    HistoricalContext,
    HistoryRecord,
    Positive,
    Time,
    encoded,
    valid_time,
)

MINUTE_MS = 60_000
HOUR_MS = 60 * MINUTE_MS
MAX_GAP_MS = 15 * MINUTE_MS
MAX_INPUT_RECORDS = 50_000
MAX_COHORTS = 128
MAX_RANGES = 256
MAX_EPISODES = 32
MAX_EPISODE_REFERENCES = 16
REVISION = "daily-glucose-linear-v2"
NonnegativeFloat = Annotated[float, Field(ge=0, allow_inf_nan=False)]
Count = Annotated[int, Field(ge=0, le=2**63 - 1)]
Digest = Annotated[str, Field(pattern=r"^[0-9a-f]{64}$")]


class DayWindow(Closed):
    reportDate: str
    zoneId: str
    startMs: Time
    endMs: Time


class AnalysisRange(Closed):
    startMs: Time
    endMs: Time
    lowMmol: Positive
    highMmol: Positive
    version: Code

    @model_validator(mode="after")
    def ordered(self):
        if self.startMs >= self.endMs or self.lowMmol >= self.highMmol:
            raise ValueError("invalid_analysis_range")
        return self


class EvidenceReference(Closed):
    recordId: str
    revision: int
    eventAtMs: Time


class GlucoseMetrics(Closed):
    coveredMinutes: NonnegativeFloat
    meanMmol: Positive | None
    sdMmol: NonnegativeFloat | None
    cvPct: NonnegativeFloat | None
    rangeCoveredMinutes: NonnegativeFloat
    rangeMissingMinutes: NonnegativeFloat
    belowMinutes: NonnegativeFloat | None
    inRangeMinutes: NonnegativeFloat | None
    aboveMinutes: NonnegativeFloat | None
    belowPct: NonnegativeFloat | None
    inRangePct: NonnegativeFloat | None
    abovePct: NonnegativeFloat | None


class HourGlucose(Closed):
    hour: Annotated[int, Field(ge=0, le=23)]
    startMs: Time
    endMs: Time
    metrics: GlucoseMetrics


class GlucoseCohort(Closed):
    cohortId: str
    source: str
    quality: Literal["OK", "UNKNOWN"]
    context: HistoricalContext | None
    sensorChangeEpoch: Count
    inDayRecordCount: Count
    metrics: GlucoseMetrics
    hours: tuple[HourGlucose, ...]


class GlucoseEpisode(Closed):
    episodeId: str
    cohortId: str
    band: Literal["BELOW_RANGE", "ABOVE_RANGE"]
    startMs: NonnegativeFloat
    endMs: NonnegativeFloat
    minutes: NonnegativeFloat
    rangeVersion: Code
    lowMmol: Positive
    highMmol: Positive
    estimatedBoundaries: Literal[True] = True
    interpretation: Literal["OBSERVATION_ONLY"] = "OBSERVATION_ONLY"
    evidence: tuple[EvidenceReference, ...]
    evidenceCount: Count
    omittedEvidenceCount: Count


class QualityCounts(Closed):
    OK: Count
    UNKNOWN: Count
    SUSPECT: Count


class DailyGlucoseFacts(Closed):
    revision: Literal["daily-glucose-linear-v2"] = REVISION
    provenance: Literal["DEVICE_DECLARED"] = "DEVICE_DECLARED"
    dataCompleteness: Literal["UNKNOWN"] = "UNKNOWN"
    scope: Literal["GLUCOSE_ONLY"] = "GLUCOSE_ONLY"
    timezoneMeaning: Literal["CONFIGURED_REPORTING_PROJECTION"] = (
        "CONFIGURED_REPORTING_PROJECTION"
    )
    window: DayWindow
    asOfMs: Time
    snapshotSequence: Count
    sourceDigest: Digest
    sourceBinding: Digest | None
    dayMinutes: NonnegativeFloat
    hourlyStatus: Literal["AVAILABLE", "UNAVAILABLE_CLOCK_DISCONTINUITY"]
    inputRecordCount: Count
    inDayRecordCount: Count
    qualityCounts: QualityCounts
    sensorChanges: tuple[EvidenceReference, ...]
    conflictingTimestamps: Count
    coveredMinutes: NonnegativeFloat
    qualityOkCoveredMinutes: NonnegativeFloat
    unverifiedCoveredMinutes: NonnegativeFloat
    missingMinutes: NonnegativeFloat
    coveragePct: NonnegativeFloat
    maxGapMinutes: NonnegativeFloat | None
    maxUncoveredMinutes: NonnegativeFloat
    ranges: tuple[AnalysisRange, ...]
    cohorts: tuple[GlucoseCohort, ...]
    episodes: tuple[GlucoseEpisode, ...]
    omittedEpisodes: Count


def civil_day(report_date: str, zone_id: str) -> DayWindow:
    try:
        if type(report_date) is not str or type(zone_id) is not str:
            raise ValueError()
        day = date.fromisoformat(report_date)
        if day.isoformat() != report_date:
            raise ValueError()
        zone = ZoneInfo(zone_id)
        start = datetime.combine(day, time.min, zone).astimezone(UTC)
        end = datetime.combine(day + timedelta(days=1), time.min, zone).astimezone(UTC)
        if start.astimezone(zone).date() != day or start >= end:
            raise ValueError()
        return DayWindow(
            reportDate=report_date,
            zoneId=zone_id,
            startMs=int(start.timestamp() * 1000),
            endMs=int(end.timestamp() * 1000),
        )
    except (ValueError, TypeError, OverflowError, ZoneInfoNotFoundError):
        raise ValueError("invalid_daily_window") from None


@dataclass
class _Accumulator:
    milliseconds: int = 0
    knownMilliseconds: int = 0
    minutes: float = 0.0
    mean: float = 0.0
    m2: float = 0.0
    below: float = 0.0
    inside: float = 0.0
    above: float = 0.0

    def add(self, milliseconds, left, right):
        # Merge a uniform linear segment's moments without subtracting large squares.
        segment_mean = (left + right) / 2
        delta = segment_mean - self.mean
        minutes = milliseconds / MINUTE_MS
        total = (self.milliseconds + milliseconds) / MINUTE_MS
        self.m2 += (
            minutes * (right - left) ** 2 / 12
            + delta**2 * self.minutes * minutes / total
        )
        self.mean += delta * minutes / total
        self.milliseconds += milliseconds
        self.minutes = total

    def finish(self):
        known = self.knownMilliseconds / MINUTE_MS
        below = min(known, self.below)
        above = min(max(0.0, known - below), self.above)
        inside = max(0.0, known - below - above)
        sd = math.sqrt(max(0.0, self.m2 / self.minutes)) if self.minutes else None
        return GlucoseMetrics(
            coveredMinutes=self.minutes,
            meanMmol=self.mean if self.minutes else None,
            sdMmol=sd,
            cvPct=100 * sd / self.mean if sd is not None else None,
            rangeCoveredMinutes=known,
            rangeMissingMinutes=max(0.0, self.minutes - known),
            belowMinutes=below if known else None,
            inRangeMinutes=inside if known else None,
            aboveMinutes=above if known else None,
            belowPct=100 * below / known if known else None,
            inRangePct=100 * inside / known if known else None,
            abovePct=100 * above / known if known else None,
        )


@dataclass
class _Episode:
    cohort: str
    band: str
    start: float
    end: float
    target: AnalysisRange
    references: dict = field(default_factory=dict)

    def finish(self, digest):
        refs = sorted(self.references.values(), key=lambda r: (r.eventAtMs, r.recordId))
        kept = (
            refs
            if len(refs) <= MAX_EPISODE_REFERENCES
            else (
                refs[: MAX_EPISODE_REFERENCES // 2]
                + refs[-MAX_EPISODE_REFERENCES // 2 :]
            )
        )
        identity = [
            REVISION,
            digest,
            self.cohort,
            self.band,
            self.start,
            self.end,
            self.target.model_dump(),
        ]
        return GlucoseEpisode(
            episodeId=hashlib.sha256(encoded(identity)).hexdigest(),
            cohortId=self.cohort,
            band=self.band,
            startMs=self.start,
            endMs=self.end,
            minutes=(self.end - self.start) / MINUTE_MS,
            rangeVersion=self.target.version,
            lowMmol=self.target.lowMmol,
            highMmol=self.target.highMmol,
            evidence=tuple(kept),
            evidenceCount=len(refs),
            omittedEvidenceCount=len(refs) - len(kept),
        )


def _range_parts(start, end, ranges, starts):
    index = max(0, bisect_right(starts, start) - 1)
    while index < len(ranges) and ranges[index].startMs < end:
        target = ranges[index]
        left, right = max(start, target.startMs), min(end, target.endMs)
        if left < right:
            yield left, right, target
        index += 1


def _band_parts(start, end, left, right, target):
    cuts = [0.0, 1.0]
    if left != right:
        cuts.extend(
            f
            for threshold in (target.lowMmol, target.highMmol)
            if 0 < (f := (threshold - left) / (right - left)) < 1
        )
    cuts.sort()
    for a, b in zip(cuts, cuts[1:]):
        midpoint = left + (right - left) * (a + b) / 2
        band = (
            "below"
            if midpoint < target.lowMmol
            else "above"
            if midpoint > target.highMmol
            else "inside"
        )
        yield start + (end - start) * a, start + (end - start) * b, band


def _regular_clock(window):
    if window.endMs - window.startMs != 24 * HOUR_MS:
        return False
    zone = ZoneInfo(window.zoneId)
    offset = datetime.fromtimestamp(window.startMs / 1000, zone).utcoffset()
    for hour in range(24):
        first = datetime.fromtimestamp((window.startMs + hour * HOUR_MS) / 1000, zone)
        last = datetime.fromtimestamp(
            (window.startMs + (hour + 1) * HOUR_MS - 1) / 1000, zone
        )
        if (
            first.hour != hour
            or first.minute != 0
            or first.second != 0
            or last.hour != hour
            or last.minute != 59
            or first.fold
            or last.fold
            or first.utcoffset() != offset
            or last.utcoffset() != offset
        ):
            return False
    return True


def _add_segment(accumulator, start, end, value_at, ranges, range_starts):
    accumulator.add(end - start, value_at(start), value_at(end))
    parts = []
    for left, right, target in _range_parts(start, end, ranges, range_starts):
        accumulator.knownMilliseconds += right - left
        for a, b, band in _band_parts(
            left, right, value_at(left), value_at(right), target
        ):
            setattr(accumulator, band, getattr(accumulator, band) + (b - a) / MINUTE_MS)
            parts.append((a, b, band, target))
    return parts


@dataclass
class _PreparedSeries:
    counts: dict
    groups: dict
    points: list
    conflicts: int
    unusable: list


def _prepare_series(rows, changes, start_ms, end_ms, *, max_unusable=0):
    """Shared original-observation continuity; callers validate scope/identity."""
    change_times = sorted({r.eventAtMs for r in changes if not r.deleted})
    change_boundaries = set(change_times)
    rows = sorted(
        (r for r in rows if not r.deleted), key=lambda r: (r.eventAtMs, r.recordId)
    )
    counts = dict(OK=0, UNKNOWN=0, SUSPECT=0)
    groups, buckets = {}, {}
    for row in rows:
        in_window = start_ms <= row.eventAtMs < end_ms
        if in_window:
            counts[row.quality] += 1
        context = row.context.model_dump(exclude_none=True) if row.context else None
        epoch = bisect_right(change_times, row.eventAtMs)
        key = hashlib.sha256(
            encoded([row.source, row.quality, context, epoch])
        ).hexdigest()
        buckets.setdefault(row.eventAtMs, []).append((key, row))
        if row.quality != "SUSPECT":
            if key not in groups:
                groups[key] = [row, 0, _Accumulator(), epoch]
            groups[key][1] += int(in_window)
    if len(groups) > MAX_COHORTS:
        raise ValueError("daily_glucose_capacity")
    points, conflicts, unusable = [], 0, []
    for timestamp, bucket in sorted(buckets.items()):
        conflict = len({(key, row.data["glucoseMmol"]) for key, row in bucket}) != 1
        if conflict and start_ms <= timestamp < end_ms:
            conflicts += 1
        usable = (
            not conflict
            and bucket[0][1].quality != "SUSPECT"
            and timestamp not in change_boundaries
        )
        refs = (
            tuple(
                EvidenceReference(
                    recordId=r.recordId, revision=r.revision, eventAtMs=r.eventAtMs
                )
                for _, r in bucket
            )
            if usable
            else ()
        )
        if max_unusable and not usable and start_ms <= timestamp < end_ms:
            reason = (
                "CONFLICT"
                if conflict
                else "SUSPECT"
                if bucket[0][1].quality == "SUSPECT"
                else "SENSOR_BOUNDARY"
            )
            unusable.append((timestamp, reason, tuple(r for _, r in bucket)))
            if len(unusable) > max_unusable:
                raise ValueError("daily_glucose_capacity")
        points.append(
            (
                timestamp,
                bucket[0][0] if usable else None,
                bucket[0][1].data["glucoseMmol"] if usable else None,
                refs,
            )
        )
    return _PreparedSeries(counts, groups, points, conflicts, unusable)


def build_daily_glucose(
    records,
    *,
    report_date,
    zone_id,
    ranges,
    now_ms,
    snapshot_sequence,
    source_binding=None,
    sensor_changes=(),
) -> DailyGlucoseFacts:
    """Calculate decoded latest records; only HistoryStore provides authentication."""
    window = civil_day(report_date, zone_id)
    try:
        valid_time(now_ms)
        if (
            now_ms < window.endMs
            or type(snapshot_sequence) is not int
            or not 0 <= snapshot_sequence < 2**63
            or source_binding is not None
            and (
                type(source_binding) is not str
                or re.fullmatch(r"[0-9a-f]{64}", source_binding) is None
            )
            or type(records) not in (tuple, list)
            or type(ranges) not in (tuple, list)
            or type(sensor_changes) not in (tuple, list)
        ):
            raise ValueError()
        if (
            len(records) + len(sensor_changes) > MAX_INPUT_RECORDS
            or len(ranges) > MAX_RANGES
            or len(sensor_changes) > MAX_RANGES
        ):
            raise ValueError("daily_glucose_capacity")
        settings = tuple(
            sorted(
                (AnalysisRange.model_validate(r.model_dump()) for r in ranges),
                key=lambda r: r.startMs,
            )
        )
        if any(a.endMs > b.startMs for a, b in zip(settings, settings[1:])):
            raise ValueError()
        rows = [HistoryRecord.model_validate(r.model_dump()) for r in records]
        changes = [HistoryRecord.model_validate(r.model_dump()) for r in sensor_changes]
        all_rows = rows + changes
        if (
            any(r.kind != "GLUCOSE" for r in rows)
            or any(
                r.kind != "THERAPY"
                or not r.deleted
                and r.data != {"eventType": "SENSOR_CHANGE"}
                for r in changes
            )
            or len({r.recordId for r in all_rows}) != len(all_rows)
            or any(
                r.observedAtMs > now_ms
                or not window.startMs - MAX_GAP_MS
                <= r.eventAtMs
                <= min(now_ms, window.endMs + MAX_GAP_MS)
                for r in all_rows
            )
        ):
            raise ValueError()
    except (ValueError, TypeError, AttributeError, ValidationError) as exc:
        if type(exc) is ValueError and exc.args == ("daily_glucose_capacity",):
            raise ValueError("daily_glucose_capacity") from None
        raise ValueError("invalid_daily_glucose_input") from None

    digest = hashlib.sha256(
        encoded(
            [
                REVISION,
                source_binding,
                window.model_dump(),
                now_ms,
                snapshot_sequence,
                [r.model_dump() for r in settings],
            ]
        )
    )
    for row in sorted(all_rows, key=lambda r: r.recordId):
        body = encoded(row.model_dump())
        digest.update(len(body).to_bytes(4, "big"))
        digest.update(body)
    source_digest = digest.hexdigest()
    changes = sorted(
        (r for r in changes if not r.deleted), key=lambda r: (r.eventAtMs, r.recordId)
    )
    prepared = _prepare_series(rows, changes, window.startMs, window.endMs)
    counts, groups, points, conflicts = (
        prepared.counts,
        prepared.groups,
        prepared.points,
        prepared.conflicts,
    )

    starts = [r.startMs for r in settings]
    hourly_enabled = _regular_clock(window)
    hourly_accumulators = {}
    episodes, covered, gaps = [], [], []
    for first, second in zip(points, points[1:]):
        t0, key, y0, refs0 = first
        t1, other, y1, refs1 = second
        a, b = max(t0, window.startMs), min(t1, window.endMs)
        if a >= b:
            continue
        gaps.append((t1 - t0) / MINUTE_MS)
        if key is None or key != other or t1 - t0 > MAX_GAP_MS:
            continue

        def value_at(t):
            return y0 + (y1 - y0) * (t - t0) / (t1 - t0)

        accumulator = groups[key][2]
        covered.append((a, b, key))
        for p, q, band, target in _add_segment(
            accumulator, a, b, value_at, settings, starts
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
            prior.end = float(q)
            prior.references.update(
                ((r.recordId, r.revision), r) for r in refs0 + refs1
            )
        if hourly_enabled:
            for hour in range(
                (a - window.startMs) // HOUR_MS, (b - 1 - window.startMs) // HOUR_MS + 1
            ):
                hour_start = window.startMs + hour * HOUR_MS
                hour_acc = hourly_accumulators.setdefault((key, hour), _Accumulator())
                _add_segment(
                    hour_acc,
                    max(a, hour_start),
                    min(b, hour_start + HOUR_MS),
                    value_at,
                    settings,
                    starts,
                )

    cohort_facts = tuple(
        GlucoseCohort(
            cohortId=key,
            source=row.source,
            quality=row.quality,
            context=row.context,
            sensorChangeEpoch=epoch,
            inDayRecordCount=count,
            metrics=acc.finish(),
            hours=tuple(
                HourGlucose(
                    hour=hour,
                    startMs=window.startMs + hour * HOUR_MS,
                    endMs=window.startMs + (hour + 1) * HOUR_MS,
                    metrics=hour_acc.finish(),
                )
                for (cohort_key, hour), hour_acc in sorted(hourly_accumulators.items())
                if cohort_key == key
            ),
        )
        for key, (row, count, acc, epoch) in sorted(groups.items())
    )
    ok_ms = sum(
        acc.milliseconds for row, _, acc, _ in groups.values() if row.quality == "OK"
    )
    unknown_ms = sum(
        acc.milliseconds
        for row, _, acc, _ in groups.values()
        if row.quality == "UNKNOWN"
    )
    ok, unknown = ok_ms / MINUTE_MS, unknown_ms / MINUTE_MS
    total = (ok_ms + unknown_ms) / MINUTE_MS
    day_minutes = (window.endMs - window.startMs) / MINUTE_MS
    previous, max_missing = window.startMs, 0.0
    for a, b, _ in covered:
        max_missing = max(max_missing, (a - previous) / MINUTE_MS)
        previous = b
    max_missing = max(max_missing, (window.endMs - previous) / MINUTE_MS)
    selected = sorted(episodes, key=lambda e: (-(e.end - e.start), e.start, e.cohort))[
        :MAX_EPISODES
    ]
    return DailyGlucoseFacts(
        window=window,
        asOfMs=now_ms,
        snapshotSequence=snapshot_sequence,
        sourceDigest=source_digest,
        sourceBinding=source_binding,
        dayMinutes=day_minutes,
        hourlyStatus="AVAILABLE"
        if hourly_enabled
        else "UNAVAILABLE_CLOCK_DISCONTINUITY",
        inputRecordCount=len(records),
        inDayRecordCount=sum(counts.values()),
        qualityCounts=QualityCounts(**counts),
        sensorChanges=tuple(
            EvidenceReference(
                recordId=r.recordId, revision=r.revision, eventAtMs=r.eventAtMs
            )
            for r in changes
        ),
        conflictingTimestamps=conflicts,
        coveredMinutes=total,
        qualityOkCoveredMinutes=ok,
        unverifiedCoveredMinutes=unknown,
        missingMinutes=max(0.0, day_minutes - total),
        coveragePct=100 * total / day_minutes,
        maxGapMinutes=max(gaps) if gaps else None,
        maxUncoveredMinutes=max_missing,
        ranges=settings,
        cohorts=cohort_facts,
        episodes=tuple(
            e.finish(source_digest)
            for e in sorted(selected, key=lambda e: (e.start, e.cohort))
        ),
        omittedEpisodes=len(episodes) - len(selected),
    )
