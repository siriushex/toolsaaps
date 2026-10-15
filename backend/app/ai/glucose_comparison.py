"""Bounded descriptive comparisons. No learning, diagnosis or therapy authority."""

from __future__ import annotations

import hashlib
import math
from bisect import bisect_left, bisect_right
from datetime import date, timedelta
from typing import Literal

from .daily_glucose import (
    Count,
    DailyGlucoseFacts,
    Digest,
    GlucoseCohort,
    HOUR_MS,
    MAX_GAP_MS,
    NonnegativeFloat,
    build_daily_glucose,
    civil_day,
)
from .history_contract import (
    Closed,
    Code,
    HistoryRecord,
    Positive,
    RETENTION_MS,
    Time,
    encoded,
    valid_time,
)

REVISION = "glucose-comparison-v1"
MAX_PATTERNS = 24
MAX_HISTORY_DAYS = 21
MAX_REVIEW_INPUT_RECORDS = 100_000
MIN_COVERAGE_PCT = 80.0
MIN_EXPOSURE_MINUTES = 5.0
MIN_RECURRENCE_DAYS = 3


class DayEligibility(Closed):
    reportDate: str
    sourceDigest: Digest | None
    glucoseReason: Code | None
    rangeReason: Code | None


class AggregateGlucose(Closed):
    coveredMinutes: NonnegativeFloat
    rangeComparableMinutes: NonnegativeFloat
    meanMmol: Positive | None
    sdMmol: NonnegativeFloat | None
    cvPct: NonnegativeFloat | None
    belowPct: NonnegativeFloat | None
    inRangePct: NonnegativeFloat | None
    abovePct: NonnegativeFloat | None


class GlucoseBaseline(Closed):
    lookbackDays: Literal[7, 21]
    glucoseDays: Count
    rangeDays: Count
    missingDates: tuple[str, ...]
    days: tuple[DayEligibility, ...]
    metrics: AggregateGlucose
    deltaMeanMmol: float | None
    deltaInRangePercentagePoints: float | None


class CohortComparison(Closed):
    cohortId: Digest
    limitations: tuple[Code, ...]
    baselines: tuple[GlucoseBaseline, ...]


class ExcludedDay(Closed):
    reportDate: str
    reason: Code


class WindowEvidence(Closed):
    reportDate: str
    sourceDigest: Digest
    cohortId: Digest
    startMs: Time
    endMs: Time
    coveredMinutes: NonnegativeFloat
    missingMinutes: NonnegativeFloat
    exposureMinutes: NonnegativeFloat
    episodeIds: tuple[Digest, ...]
    omittedEpisodes: Count
    omittedEndpointReferences: Count


class ExposurePattern(Closed):
    candidateId: Digest
    cohortId: Digest
    band: Literal["BELOW_RANGE", "ABOVE_RANGE"]
    startHour: int
    endHour: int
    lookbackDays: Literal[21] = 21
    status: Literal["OBSERVATION", "PROVISIONAL_RECURRENCE"]
    qualifyingDays: Count
    eligibleWindows: Count
    lowMmol: Positive
    highMmol: Positive
    rangeVersion: Code
    evidence: tuple[WindowEvidence, ...]
    excludedDays: tuple[ExcludedDay, ...]
    allowedAction: Literal["REVIEW_MEASUREMENT_AND_RECORDED_CONTEXT"] = (
        "REVIEW_MEASUREMENT_AND_RECORDED_CONTEXT"
    )


class GlucoseComparison(Closed):
    revision: Literal["glucose-comparison-v1"] = REVISION
    provenance: Literal["DEVICE_DECLARED"] = "DEVICE_DECLARED"
    interpretation: Literal["DESCRIPTIVE_ONLY"] = "DESCRIPTIVE_ONLY"
    status: Literal["LIMITED_DATA", "DESCRIPTIVE"]
    reportDate: str
    sourceBinding: Digest
    currentDigest: Digest
    sourceDigest: Digest
    snapshotSequence: Count
    asOfMs: Time
    minimumCoveragePct: NonnegativeFloat = MIN_COVERAGE_PCT
    minimumExposureMinutes: NonnegativeFloat = MIN_EXPOSURE_MINUTES
    minimumRecurrenceDays: Count = MIN_RECURRENCE_DAYS
    analyses: tuple[CohortComparison, ...]
    patterns: tuple[ExposurePattern, ...]
    omittedPatterns: Count


class DailyGlucoseReview(Closed):
    current: DailyGlucoseFacts
    comparison: GlucoseComparison


def _context_signature(cohort: GlucoseCohort):
    return encoded(
        [
            cohort.source,
            cohort.context.model_dump(exclude_none=True)
            if cohort.context is not None
            else None,
        ]
    )


def _context_limit(facts, cohort):
    if cohort.quality != "OK":
        return "CURRENT_QUALITY_NOT_OK"
    context = cohort.context
    if (
        context is None
        or not all(
            (
                context.zoneId,
                context.sensorSessionId,
                context.calibrationModelId,
                context.inputModelVersion,
            )
        )
        or context.glucoseBasis in (None, "UNKNOWN")
    ):
        return "CURRENT_CONTEXT_UNAVAILABLE"
    if context.zoneId != facts.window.zoneId:
        return "CURRENT_HISTORICAL_ZONE_MISMATCH"
    if any(
        facts.window.startMs <= r.eventAtMs < facts.window.endMs
        for r in facts.sensorChanges
    ):
        return "CURRENT_SENSOR_CHANGE_DAY"
    return None


def _match(facts, reference):
    if facts.inDayRecordCount == 0:
        return None, "NO_UPLOADED_GLUCOSE"
    if any(
        facts.window.startMs <= r.eventAtMs < facts.window.endMs
        for r in facts.sensorChanges
    ):
        return None, "SENSOR_CHANGE_DAY"
    matches = [
        c
        for c in facts.cohorts
        if _context_signature(c) == _context_signature(reference)
    ]
    if not matches:
        return None, "CONTEXT_MISMATCH"
    if len(matches) != 1:
        return None, "AMBIGUOUS_CONTEXT"
    cohort = matches[0]
    if cohort.quality != "OK":
        return None, "QUALITY_NOT_OK"
    return cohort, None


def _range_signature(facts, start, end):
    cursor, signature = start, None
    for target in facts.ranges:
        a, b = max(start, target.startMs), min(end, target.endMs)
        if a >= b:
            continue
        item = (target.version, target.lowMmol, target.highMmol)
        if a != cursor or signature is not None and signature != item:
            return None
        cursor, signature = b, item
    return signature if cursor == end else None


def _covered(cohort, day):
    return cohort.metrics.coveredMinutes * 100 >= MIN_COVERAGE_PCT * day.dayMinutes


def _aggregate(glucose, exposure):
    total = math.fsum(c.metrics.coveredMinutes for c in glucose)
    mean = (
        math.fsum(c.metrics.coveredMinutes * c.metrics.meanMmol for c in glucose)
        / total
        if total
        else None
    )
    variance = (
        math.fsum(
            c.metrics.coveredMinutes
            * (c.metrics.sdMmol**2 + (c.metrics.meanMmol - mean) ** 2)
            for c in glucose
        )
        / total
        if total
        else None
    )
    sd = math.sqrt(max(0.0, variance)) if variance is not None else None
    known = math.fsum(c.metrics.rangeCoveredMinutes for c in exposure)

    def percentage(field):
        return (
            100 * math.fsum(getattr(c.metrics, field) for c in exposure) / known
            if known
            else None
        )

    return AggregateGlucose(
        coveredMinutes=total,
        rangeComparableMinutes=known,
        meanMmol=mean,
        sdMmol=sd,
        cvPct=100 * sd / mean if total else None,
        belowPct=percentage("belowMinutes"),
        inRangePct=percentage("inRangeMinutes"),
        abovePct=percentage("aboveMinutes"),
    )


def _baseline(current, reference, history, lookback):
    current_limit = _context_limit(current, reference)
    current_range = _range_signature(
        current, current.window.startMs, current.window.endMs
    )
    today = date.fromisoformat(current.window.reportDate)
    days, glucose, exposure = [], [], []
    for offset in range(lookback, 0, -1):
        day = (today - timedelta(days=offset)).isoformat()
        previous = history.get(day)
        reason, matched = "MISSING_DAY", None
        if previous is not None:
            if previous.inDayRecordCount == 0:
                reason = "NO_UPLOADED_GLUCOSE"
            elif current_limit:
                reason = current_limit
            else:
                matched, reason = _match(previous, reference)
                if matched is not None and not _covered(matched, previous):
                    matched, reason = None, "LOW_DAY_COVERAGE"
        range_reason = reason
        if matched is not None:
            glucose.append(matched)
            previous_range = _range_signature(
                previous, previous.window.startMs, previous.window.endMs
            )
            if current_range is None:
                range_reason = "CURRENT_RANGE_UNAVAILABLE"
            elif previous_range is None or matched.metrics.rangeMissingMinutes > 1e-6:
                range_reason = "RANGE_UNAVAILABLE"
            elif previous_range != current_range:
                range_reason = "RANGE_MISMATCH"
            else:
                range_reason = None
                exposure.append(matched)
        days.append(
            DayEligibility(
                reportDate=day,
                sourceDigest=previous.sourceDigest if previous else None,
                glucoseReason=reason,
                rangeReason=range_reason,
            )
        )
    metrics = _aggregate(glucose, exposure)
    valid_current = current_limit is None and _covered(reference, current)
    delta_mean = (
        reference.metrics.meanMmol - metrics.meanMmol
        if valid_current and metrics.meanMmol is not None
        else None
    )
    delta_range = (
        reference.metrics.inRangePct - metrics.inRangePct
        if valid_current
        and current_range is not None
        and reference.metrics.rangeMissingMinutes <= 1e-6
        and reference.metrics.inRangePct is not None
        and metrics.inRangePct is not None
        else None
    )
    return GlucoseBaseline(
        lookbackDays=lookback,
        glucoseDays=len(glucose),
        rangeDays=len(exposure),
        missingDates=tuple(
            d.reportDate
            for d in days
            if d.glucoseReason in ("MISSING_DAY", "NO_UPLOADED_GLUCOSE")
        ),
        days=tuple(days),
        metrics=metrics,
        deltaMeanMmol=delta_mean,
        deltaInRangePercentagePoints=delta_range,
    )


def _window(facts, cohort, hour, band, signature):
    if facts.hourlyStatus != "AVAILABLE":
        return None, "CLOCK_DISCONTINUITY"
    start = facts.window.startMs + hour * HOUR_MS
    end = start + 2 * HOUR_MS
    ranges = _range_signature(facts, start, end)
    if ranges is None:
        return None, "RANGE_UNAVAILABLE"
    if ranges != signature:
        return None, "RANGE_MISMATCH"
    hours = [h for h in cohort.hours if hour <= h.hour < hour + 2]
    covered = math.fsum(h.metrics.coveredMinutes for h in hours)
    if covered * 100 < 120 * MIN_COVERAGE_PCT:
        return None, "LOW_WINDOW_COVERAGE"
    if math.fsum(h.metrics.rangeMissingMinutes for h in hours) > 1e-6:
        return None, "RANGE_UNAVAILABLE"
    field = "belowMinutes" if band == "BELOW_RANGE" else "aboveMinutes"
    exposure = math.fsum(getattr(h.metrics, field) or 0.0 for h in hours)
    episodes = [
        e
        for e in facts.episodes
        if e.cohortId == cohort.cohortId
        and e.band == band
        and e.startMs < end
        and e.endMs > start
    ]
    return WindowEvidence(
        reportDate=facts.window.reportDate,
        sourceDigest=facts.sourceDigest,
        cohortId=cohort.cohortId,
        startMs=start,
        endMs=end,
        coveredMinutes=covered,
        missingMinutes=max(0.0, 120 - covered),
        exposureMinutes=exposure,
        episodeIds=tuple(e.episodeId for e in episodes),
        omittedEpisodes=facts.omittedEpisodes,
        omittedEndpointReferences=sum(e.omittedEvidenceCount for e in episodes),
    ), None


def _patterns(current, reference, history, digest):
    if _context_limit(current, reference) or current.hourlyStatus != "AVAILABLE":
        return []
    patterns, today = [], date.fromisoformat(current.window.reportDate)
    for hour in range(0, 24, 2):
        signature = _range_signature(
            current,
            current.window.startMs + hour * HOUR_MS,
            current.window.startMs + (hour + 2) * HOUR_MS,
        )
        if signature is None:
            continue
        for band in ("BELOW_RANGE", "ABOVE_RANGE"):
            present, _ = _window(current, reference, hour, band, signature)
            if present is None or present.exposureMinutes < MIN_EXPOSURE_MINUTES:
                continue
            evidence, excluded = [], []
            for offset in range(MAX_HISTORY_DAYS, 0, -1):
                day = (today - timedelta(days=offset)).isoformat()
                previous = history.get(day)
                item, reason = None, "MISSING_DAY"
                if previous is not None:
                    matched, reason = _match(previous, reference)
                    if matched is not None:
                        item, reason = _window(previous, matched, hour, band, signature)
                if item is None:
                    excluded.append(ExcludedDay(reportDate=day, reason=reason))
                else:
                    evidence.append(item)
            evidence.append(present)
            count = sum(e.exposureMinutes >= MIN_EXPOSURE_MINUTES for e in evidence)
            patterns.append(
                ExposurePattern(
                    candidateId=hashlib.sha256(
                        encoded([digest, reference.cohortId, hour, band])
                    ).hexdigest(),
                    cohortId=reference.cohortId,
                    band=band,
                    startHour=hour,
                    endHour=hour + 2,
                    status="PROVISIONAL_RECURRENCE"
                    if count >= MIN_RECURRENCE_DAYS
                    else "OBSERVATION",
                    qualifyingDays=count,
                    eligibleWindows=len(evidence),
                    rangeVersion=signature[0],
                    lowMmol=signature[1],
                    highMmol=signature[2],
                    evidence=tuple(evidence),
                    excludedDays=tuple(excluded),
                )
            )
    return patterns


def build_glucose_comparison(current, history) -> GlucoseComparison:
    """Consume trusted computed facts, not HTTP/client-supplied metric authority."""
    try:
        if type(history) not in (tuple, list) or len(history) > MAX_HISTORY_DAYS:
            raise ValueError()
        current = DailyGlucoseFacts.model_validate(current.model_dump())
        previous = [DailyGlucoseFacts.model_validate(f.model_dump()) for f in history]
        if current.sourceBinding is None:
            raise ValueError()
        today = date.fromisoformat(current.window.reportDate)
        dates = set()
        for item in [current] + previous:
            day = date.fromisoformat(item.window.reportDate)
            if (
                item.window != civil_day(item.window.reportDate, item.window.zoneId)
                or item.window.endMs > item.asOfMs
                or item.window.zoneId != current.window.zoneId
                or item.sourceBinding != current.sourceBinding
                or item.asOfMs != current.asOfMs
                or item.snapshotSequence != current.snapshotSequence
                or item.window.reportDate in dates
                or item is not current
                and not 1 <= (today - day).days <= MAX_HISTORY_DAYS
            ):
                raise ValueError()
            dates.add(item.window.reportDate)
    except (ValueError, TypeError, AttributeError):
        raise ValueError("invalid_glucose_comparison") from None
    history_by_date = {f.window.reportDate: f for f in previous}
    digest = hashlib.sha256(
        encoded(
            [
                REVISION,
                current.sourceDigest,
                sorted((f.window.reportDate, f.sourceDigest) for f in previous),
                MIN_COVERAGE_PCT,
                MIN_EXPOSURE_MINUTES,
                MIN_RECURRENCE_DAYS,
            ]
        )
    ).hexdigest()
    analyses, patterns = [], []
    for cohort in current.cohorts:
        limitations = []
        limit = _context_limit(current, cohort)
        if limit:
            limitations.append(limit)
        if not _covered(cohort, current):
            limitations.append("LOW_CURRENT_DAY_COVERAGE")
        if current.hourlyStatus != "AVAILABLE":
            limitations.append("CURRENT_CLOCK_DISCONTINUITY")
        if (
            _range_signature(current, current.window.startMs, current.window.endMs)
            is None
        ):
            limitations.append("CURRENT_RANGE_UNAVAILABLE")
        analyses.append(
            CohortComparison(
                cohortId=cohort.cohortId,
                limitations=tuple(limitations),
                baselines=tuple(
                    _baseline(current, cohort, history_by_date, days)
                    for days in (7, 21)
                ),
            )
        )
        patterns.extend(_patterns(current, cohort, history_by_date, digest))
    patterns.sort(
        key=lambda p: (
            p.band != "BELOW_RANGE",
            -p.qualifyingDays,
            -p.evidence[-1].exposureMinutes,
            p.startHour,
            p.cohortId,
        )
    )
    selected = tuple(patterns[:MAX_PATTERNS])
    return GlucoseComparison(
        status="DESCRIPTIVE"
        if selected or any(b.glucoseDays for a in analyses for b in a.baselines)
        else "LIMITED_DATA",
        reportDate=current.window.reportDate,
        sourceBinding=current.sourceBinding,
        currentDigest=current.sourceDigest,
        sourceDigest=digest,
        snapshotSequence=current.snapshotSequence,
        asOfMs=current.asOfMs,
        analyses=tuple(analyses),
        patterns=selected,
        omittedPatterns=len(patterns) - len(selected),
    )


def review_windows(report_date, zone_id, *, now_ms):
    valid_time(now_ms)
    window = civil_day(report_date, zone_id)
    if window.endMs > now_ms or window.startMs <= now_ms - RETENTION_MS:
        raise ValueError("invalid_glucose_review")
    today = date.fromisoformat(report_date)
    windows = tuple(
        civil_day((today - timedelta(days=offset)).isoformat(), zone_id)
        for offset in range(MAX_HISTORY_DAYS, -1, -1)
    )
    return tuple(w for w in windows if w.startMs > now_ms - RETENTION_MS)


def build_glucose_review(
    records,
    *,
    sensor_changes=(),
    report_date,
    zone_id,
    ranges,
    now_ms,
    snapshot_sequence,
    source_binding,
):
    """Partition one trusted snapshot; no database, model or clinical writer."""
    try:
        windows = review_windows(report_date, zone_id, now_ms=now_ms)
        if (
            type(records) not in (tuple, list)
            or type(sensor_changes) not in (tuple, list)
            or len(records) + len(sensor_changes) > MAX_REVIEW_INPUT_RECORDS
        ):
            raise ValueError()
        all_rows = list(records) + list(sensor_changes)
        if (
            any(not isinstance(r, HistoryRecord) for r in all_rows)
            or len({r.recordId for r in all_rows}) != len(all_rows)
            or any(r.kind != "GLUCOSE" for r in records)
            or any(r.kind != "THERAPY" for r in sensor_changes)
            or any(
                not max(1, windows[0].startMs - MAX_GAP_MS)
                <= r.eventAtMs
                <= min(now_ms, windows[-1].endMs + MAX_GAP_MS)
                for r in all_rows
            )
        ):
            raise ValueError()
        glucose = sorted(records, key=lambda r: (r.eventAtMs, r.recordId))
        changes = sorted(sensor_changes, key=lambda r: (r.eventAtMs, r.recordId))
        glucose_times = [r.eventAtMs for r in glucose]
        change_times = [r.eventAtMs for r in changes]
        days = []
        for window in windows:
            start, end = window.startMs - MAX_GAP_MS, window.endMs + MAX_GAP_MS
            selected = glucose[
                bisect_left(glucose_times, start) : bisect_right(glucose_times, end)
            ]
            context = changes[
                bisect_left(change_times, start) : bisect_right(change_times, end)
            ]
            days.append(
                build_daily_glucose(
                    selected,
                    sensor_changes=context,
                    report_date=window.reportDate,
                    zone_id=zone_id,
                    ranges=ranges,
                    now_ms=now_ms,
                    snapshot_sequence=snapshot_sequence,
                    source_binding=source_binding,
                )
            )
        current = days[-1]
        return DailyGlucoseReview(
            current=current, comparison=build_glucose_comparison(current, days[:-1])
        )
    except (ValueError, TypeError, AttributeError):
        raise ValueError("invalid_glucose_review") from None
