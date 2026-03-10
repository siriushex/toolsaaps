#!/usr/bin/env python3
from __future__ import annotations

import argparse
import bisect
import csv
import json
import math
import sqlite3
from collections import defaultdict
from dataclasses import dataclass
from datetime import datetime, timedelta
from pathlib import Path
from statistics import mean
from zoneinfo import ZoneInfo


DAY_MS = 24 * 60 * 60 * 1000
MINUTE_MS = 60 * 1000
FIVE_MINUTES_MS = 5 * MINUTE_MS
EXPECTED_5M_PER_DAY = 288
MIN_GLUCOSE_MMOL = 2.2
MAX_GLUCOSE_MMOL = 22.0
MIN_SLOT_SAMPLES = 6
MIN_ACTIVE_DAYS = {
    "WEEKDAY": 3,
    "WEEKEND": 2,
    "ALL": 4,
}
MIN_REPLAY_SAMPLES = {
    "WEEKDAY": 5,
    "WEEKEND": 4,
    "ALL": 6,
}
MIN_COVERAGE_RATIO = {
    "WEEKDAY": 0.45,
    "WEEKEND": 0.40,
    "ALL": 0.40,
}
RECENCY_MAX_WEIGHT = 0.30
RECENCY_BG_CLAMP = 1.5
RECENCY_DELTA_CLAMP = 1.2
MIN_DAY_COVERAGE_QUALITY = 0.35
MIN_DAY_OBSERVED_SHARE = 0.35
MAX_GAP_MINUTES_FOR_GOOD_WINDOW = 60
HORIZONS = (15, 30, 60)
WEIGHT5 = 0.10
WEIGHT30 = 0.25
WEIGHT60 = 0.35
EVAL_HORIZONS = (30, 60)
TELEMETRY_KEYS = (
    "sensor_quality_delta5_mmol",
    "delta5_mmol",
    "glucose_delta5_mmol",
    "cob_grams",
    "cob_effective_grams",
    "cob_external_adjusted_grams",
    "uam_value",
    "iob_units",
    "iob_effective_units",
    "iob_real_units",
    "sensor_quality_blocked",
    "sensor_quality_suspect_false_low",
)


def round_to_five_minute_bucket(ts: int) -> int:
    return ((ts + FIVE_MINUTES_MS // 2) // FIVE_MINUTES_MS) * FIVE_MINUTES_MS


def round_to_step(value: float, step: float) -> float:
    return math.floor(value / step + 0.5) * step


def percentile(values: list[float], q: float) -> float:
    xs = sorted(v for v in values if math.isfinite(v))
    if not xs:
        return 0.0
    if len(xs) == 1:
        return xs[0]
    pos = max(0.0, min(1.0, q)) * (len(xs) - 1)
    lo = math.floor(pos)
    hi = math.ceil(pos)
    if lo == hi:
        return xs[int(lo)]
    ratio = pos - lo
    return xs[int(lo)] + (xs[int(hi)] - xs[int(lo)]) * ratio


def avg_or_none(values: list[float]) -> float | None:
    xs = [v for v in values if math.isfinite(v)]
    return mean(xs) if xs else None


def same_bias_direction(a: float, b: float) -> bool:
    return a == 0.0 or b == 0.0 or (a > 0.0 and b > 0.0) or (a < 0.0 and b < 0.0)


def source_priority(source: str) -> int:
    source = (source or "").lower()
    if source == "aaps_broadcast":
        return 60
    if source == "nightscout":
        return 50
    if source == "xdrip_broadcast":
        return 45
    if source == "local_nightscout_entry":
        return 42
    if source.startswith("local_nightscout"):
        return 40
    if source == "local_broadcast":
        return 10
    return 20


def quality_priority(quality: str) -> int:
    quality = (quality or "").upper()
    if quality == "OK":
        return 3
    if quality == "STALE":
        return 2
    if quality == "SENSOR_ERROR":
        return 1
    return 0


@dataclass(frozen=True)
class GlucosePoint:
    ts: int
    mmol: float
    source: str
    quality: str


@dataclass(frozen=True)
class ForecastRow:
    ts: int
    horizon: int
    value: float
    ci_low: float
    ci_high: float
    model_version: str


@dataclass(frozen=True)
class DayQuality:
    score: float
    usable: bool


@dataclass(frozen=True)
class PriorState:
    confidence: float
    quality_score: float
    stability_score: float
    horizon_quality30: float
    horizon_quality60: float
    acute_attenuation: float
    stale_blocked: bool
    bg_median: float
    slot_p10: float
    slot_p25: float
    slot_p75: float
    slot_p90: float
    delta15: float
    delta30: float
    delta60: float
    residual_bias30: float
    residual_bias60: float
    median_reversion30: float
    median_reversion60: float
    replay_bias30: float
    replay_bias60: float
    replay_sample_count30: int
    replay_sample_count60: int
    replay_win_rate30: float
    replay_win_rate60: float
    replay_mae_improvement30: float
    replay_mae_improvement60: float
    replay_status30: str
    replay_status60: str
    segment_source: str


@dataclass(frozen=True)
class ReplaySlotRow:
    day_type: str
    window_days: int
    slot_index: int
    horizon: int
    sample_count: int
    coverage_days: int
    mae_baseline: float
    mae_circadian: float
    mae_improvement_mmol: float
    median_signed_error_baseline: float
    median_signed_error_circadian: float
    win_rate: float
    quality_score: float
    updated_at: int


@dataclass(frozen=True)
class SnapshotRow:
    day_type: str
    segment_source: str
    stable_window_days: int
    recency_window_days: int
    recency_weight: float
    confidence: float
    quality_score: float


@dataclass(frozen=True)
class SlotStatRow:
    day_type: str
    window_days: int
    slot_index: int
    sample_count: int
    active_days: int
    median_bg: float
    p10: float
    p25: float
    p75: float
    p90: float
    mean_cob: float | None
    mean_iob: float | None
    mean_uam: float | None
    mean_activity: float | None
    fast_rise_rate: float
    fast_drop_rate: float
    confidence: float
    quality_score: float


@dataclass(frozen=True)
class TransitionRow:
    day_type: str
    window_days: int
    slot_index: int
    horizon: int
    sample_count: int
    delta_median: float
    delta_p25: float
    delta_p75: float
    residual_bias_mmol: float
    confidence: float


@dataclass(frozen=True)
class ResolvedReplayMetric:
    bias: float
    sample_count: int
    win_rate: float
    mae_improvement_mmol: float
    status: str


class TelemetryLookup:
    def __init__(self, rows: list[tuple[int, str, float | None, str | None]]):
        self.by_key: dict[str, tuple[list[int], list[float]]] = {}
        grouped: dict[str, list[tuple[int, float]]] = defaultdict(list)
        for ts, key, value_double, value_text in rows:
            if key not in TELEMETRY_KEYS:
                continue
            value = value_double
            if value is None and value_text not in (None, ""):
                try:
                    value = float(value_text.replace(",", "."))
                except ValueError:
                    value = None
            if value is None or not math.isfinite(value):
                continue
            grouped[key].append((ts, value))
        for key, values in grouped.items():
            values.sort(key=lambda x: x[0])
            self.by_key[key] = ([v[0] for v in values], [v[1] for v in values])

    def latest(self, key: str, ts: int) -> float | None:
        item = self.by_key.get(key)
        if item is None:
            return None
        xs, ys = item
        idx = bisect.bisect_right(xs, ts) - 1
        if idx < 0:
            return None
        return ys[idx]


class GlucoseSeries:
    def __init__(self, points: list[GlucosePoint]):
        dedup: dict[int, GlucosePoint] = {}
        for point in points:
            if point.source == "local_broadcast" and point.mmol >= 30.0:
                continue
            existing = dedup.get(point.ts)
            if existing is None:
                dedup[point.ts] = point
                continue
            existing_score = source_priority(existing.source) * 10 + quality_priority(existing.quality)
            point_score = source_priority(point.source) * 10 + quality_priority(point.quality)
            if point_score > existing_score:
                dedup[point.ts] = point
        self.points = sorted(dedup.values(), key=lambda x: x.ts)
        self.ts = [p.ts for p in self.points]
        self.values = [p.mmol for p in self.points]

        bucketed: dict[int, list[float]] = defaultdict(list)
        for point in self.points:
            bucketed[round_to_five_minute_bucket(point.ts)].append(point.mmol)
        self.bucket_points = sorted(
            ((bucket, percentile(values, 0.5)) for bucket, values in bucketed.items()),
            key=lambda x: x[0],
        )
        self.bucket_by_ts = {ts: value for ts, value in self.bucket_points}

    def interpolate(self, ts: int, max_gap_min: int = 15) -> float | None:
        if not self.points:
            return None
        idx = bisect.bisect_left(self.ts, ts)
        if idx < len(self.ts) and self.ts[idx] == ts:
            return self.values[idx]
        prev_idx = idx - 1
        next_idx = idx
        if prev_idx < 0 or next_idx >= len(self.ts):
            return None
        prev_ts = self.ts[prev_idx]
        next_ts = self.ts[next_idx]
        if next_ts - prev_ts > max_gap_min * MINUTE_MS:
            return None
        ratio = (ts - prev_ts) / (next_ts - prev_ts)
        return self.values[prev_idx] + (self.values[next_idx] - self.values[prev_idx]) * ratio


def local_dt(ts: int, tz: ZoneInfo) -> datetime:
    return datetime.fromtimestamp(ts / 1000, tz)


def current_day_type(ts: int, tz: ZoneInfo) -> str:
    dow = local_dt(ts, tz).isoweekday()
    return "WEEKEND" if dow in (6, 7) else "WEEKDAY"


def matches_day_type(ts: int, day_type: str, tz: ZoneInfo) -> bool:
    return day_type == "ALL" or current_day_type(ts, tz) == day_type


def slot_index(ts: int, tz: ZoneInfo) -> int:
    dt = local_dt(ts, tz)
    return min(95, (dt.hour * 60 + dt.minute) // 15)


def minute_of_slot(ts: int, tz: ZoneInfo) -> int:
    return local_dt(ts, tz).minute


def possible_days_for_window(now_ts: int, day_type: str, window_days: int, tz: ZoneInfo) -> int:
    end = local_dt(now_ts, tz).date()
    start = end - timedelta(days=window_days - 1)
    count = 0
    cur = start
    while cur <= end:
        dow = cur.isoweekday()
        matches = day_type == "ALL" or (day_type == "WEEKDAY" and dow not in (6, 7)) or (day_type == "WEEKEND" and dow in (6, 7))
        if matches:
            count += 1
        cur += timedelta(days=1)
    return count


def has_enough_coverage(now_ts: int, day_type: str, window_days: int, coverage_days: int, tz: ZoneInfo) -> bool:
    possible = max(1, possible_days_for_window(now_ts, day_type, window_days, tz))
    ratio = coverage_days / possible
    return coverage_days >= min(MIN_ACTIVE_DAYS[day_type], possible) and ratio >= MIN_COVERAGE_RATIO[day_type]


def build_day_quality_map(points: list[tuple[int, float]], tz: ZoneInfo) -> dict[datetime.date, DayQuality]:
    grouped: dict[datetime.date, list[int]] = defaultdict(list)
    for ts, _ in points:
        grouped[local_dt(ts, tz).date()].append(slot_index(ts, tz) * 3 + (minute_of_slot(ts, tz) // 5))
    result: dict[datetime.date, DayQuality] = {}
    for day, slots in grouped.items():
        slots.sort()
        coverage = len(slots) / EXPECTED_5M_PER_DAY
        max_gap = 0
        for a, b in zip(slots, slots[1:]):
            max_gap = max(max_gap, max(0, (b - a) - 1))
        if max_gap <= 6:
            gap_penalty = 1.0
        else:
            threshold = (MAX_GAP_MINUTES_FOR_GOOD_WINDOW // 5) * 2
            if max_gap >= threshold:
                gap_penalty = 0.0
            else:
                gap_penalty = 1.0 - max(0.0, min(1.0, (max_gap - 6) / (threshold - 6)))
        score = max(0.0, min(1.0, coverage * 0.75 + gap_penalty * 0.25))
        result[day] = DayQuality(score=score, usable=score >= MIN_DAY_COVERAGE_QUALITY and coverage >= MIN_DAY_OBSERVED_SHARE)
    return result


def blend_bounded(stable: float, recency: float | None, weight: float, clamp_abs: float) -> float:
    if recency is None or not math.isfinite(recency) or weight <= 0.0:
        return stable
    correction = max(-clamp_abs, min(clamp_abs, recency - stable))
    return stable + correction * max(0.0, min(1.0, weight))


def replay_bucket_status(day_type: str, sample_count: int, coverage_days: int, required_coverage_days: int, mae_improvement_mmol: float, win_rate: float) -> str:
    if sample_count < MIN_REPLAY_SAMPLES[day_type] or coverage_days < required_coverage_days:
        return "INSUFFICIENT"
    if mae_improvement_mmol <= -0.05 and win_rate >= 0.55:
        return "HELPFUL"
    if mae_improvement_mmol >= 0.05:
        return "HARMFUL"
    if mae_improvement_mmol > 0.02 and win_rate < 0.40:
        return "HARMFUL"
    return "NEUTRAL"


def transition_quality(sample_count: int, confidence: float, delta_p25: float, delta_p75: float, default_quality: float, spread_reference: float) -> float:
    if sample_count <= 0:
        return max(0.25, min(0.80, default_quality))
    spread = max(0.0, delta_p75 - delta_p25)
    spread_quality = max(0.25, min(1.0, 1.0 - spread / spread_reference))
    return max(0.0, min(1.0, max(0.0, min(1.0, confidence)) * 0.65 + spread_quality * 0.35))


def slot_stability_score(p10: float, p25: float, p75: float, p90: float, fast_rise_rate: float, fast_drop_rate: float, mean_cob: float | None, mean_uam: float | None) -> float:
    iqr_penalty = max(0.0, min(0.35, max(0.0, p75 - p25) / 4.5))
    dynamic_penalty = max(
        0.0,
        min(
            0.45,
            max(0.0, min(1.0, max(fast_rise_rate, fast_drop_rate))) * 0.35
            + max(0.0, min(0.20, (mean_cob or 0.0) / 20.0)) * 0.20
            + max(0.0, min(0.20, (mean_uam or 0.0) / 1.5)) * 0.20,
        ),
    )
    return max(0.35, min(1.0, 1.0 - iqr_penalty - dynamic_penalty))


def horizon_quality_for_horizon(prior: PriorState, horizon: int) -> float:
    if horizon <= 15:
        value = 1.0
    elif horizon <= 30:
        ratio = max(0.0, min(1.0, (horizon - 15) / 15.0))
        value = 1.0 + (prior.horizon_quality30 - 1.0) * ratio
    elif horizon <= 60:
        ratio = max(0.0, min(1.0, (horizon - 30) / 30.0))
        value = prior.horizon_quality30 + (prior.horizon_quality60 - prior.horizon_quality30) * ratio
    else:
        value = prior.horizon_quality60
    return max(0.25, min(1.0, value))


def replay_multiplier_for_prior(prior: PriorState, horizon: int) -> float:
    if horizon < 30:
        return 1.0
    if horizon < 60:
        base = {
            "HELPFUL": 1.0,
            "NEUTRAL": 0.70,
            "HARMFUL": 0.35,
            "INSUFFICIENT": 0.0,
        }.get(prior.replay_status30, 0.0)
        return max(
            0.0,
            min(
                1.25,
                base
                * replay_helpful_boost(
                    horizon=horizon,
                    sample_count=prior.replay_sample_count30,
                    mae_improvement_mmol=prior.replay_mae_improvement30,
                    win_rate=prior.replay_win_rate30,
                    acute_attenuation=prior.acute_attenuation,
                ),
            ),
        )
    base = {
        "HELPFUL": 1.0,
        "NEUTRAL": 0.70,
        "HARMFUL": 0.35,
        "INSUFFICIENT": 0.0,
    }.get(prior.replay_status60, 0.0)
    return max(
        0.0,
        min(
            1.25,
            base
            * replay_helpful_boost(
                horizon=horizon,
                sample_count=prior.replay_sample_count60,
                mae_improvement_mmol=prior.replay_mae_improvement60,
                win_rate=prior.replay_win_rate60,
                acute_attenuation=prior.acute_attenuation,
            ),
        ),
    )


def replay_helpful_boost(horizon: int, sample_count: int, mae_improvement_mmol: float, win_rate: float, acute_attenuation: float) -> float:
    if acute_attenuation < 0.70:
        return 1.0
    if sample_count < 8:
        return 1.0
    if horizon < 60:
        if win_rate >= 0.60 and mae_improvement_mmol <= -0.10:
            return 1.15
        if win_rate >= 0.55 and mae_improvement_mmol <= -0.05:
            return 1.08
        return 1.0
    if win_rate >= 0.60 and mae_improvement_mmol <= -0.15:
        return 1.20
    if win_rate >= 0.55 and mae_improvement_mmol <= -0.08:
        return 1.10
    return 1.0


def replay_bias_strength_for_horizon(prior: PriorState, horizon: int) -> float:
    if horizon < 60:
        replay_multiplier = {
            "HELPFUL": 1.0,
            "NEUTRAL": 0.70,
            "HARMFUL": 0.0,
            "INSUFFICIENT": 0.45,
        }.get(prior.replay_status30, 0.0)
    else:
        replay_multiplier = {
            "HELPFUL": 1.0,
            "NEUTRAL": 0.70,
            "HARMFUL": 0.0,
            "INSUFFICIENT": 0.45,
        }.get(prior.replay_status60, 0.0)
    return max(0.0, min(1.0, replay_multiplier * prior.acute_attenuation * horizon_quality_for_horizon(prior, horizon)))


def prior_delta_for_horizon(prior: PriorState, horizon: int) -> float:
    if horizon <= 5:
        base = prior.delta15 * 0.33
    elif horizon <= 15:
        base = prior.delta15
    elif horizon <= 30:
        ratio = max(0.0, min(1.0, (horizon - 15) / 15.0))
        base = prior.delta15 + (prior.delta30 - prior.delta15) * ratio
    elif horizon <= 60:
        ratio = max(0.0, min(1.0, (horizon - 30) / 30.0))
        base = prior.delta30 + (prior.delta60 - prior.delta30) * ratio
    else:
        base = prior.delta60
    if horizon <= 15:
        reversion = 0.0
    elif horizon <= 30:
        ratio = max(0.0, min(1.0, (horizon - 15) / 15.0))
        reversion = prior.median_reversion30 * ratio
    elif horizon <= 60:
        ratio = max(0.0, min(1.0, (horizon - 30) / 30.0))
        reversion = prior.median_reversion30 + (prior.median_reversion60 - prior.median_reversion30) * ratio
    else:
        reversion = prior.median_reversion60
    return base + reversion


def replay_bias_for_horizon(prior: PriorState, horizon: int) -> float:
    if horizon < 30:
        return 0.0
    if horizon < 60:
        if prior.replay_status30 == "HARMFUL":
            return 0.0
        return max(-0.20, min(0.20, prior.replay_bias30 * replay_bias_strength_for_horizon(prior, horizon)))
    if prior.replay_status60 == "HARMFUL":
        return 0.0
    return max(-0.35, min(0.35, prior.replay_bias60 * replay_bias_strength_for_horizon(prior, horizon)))


def weight_for_horizon(horizon: int, confidence: float, quality_score: float, stability_score: float, horizon_quality: float, acute: float, replay_multiplier: float, weight30: float, weight60: float) -> float:
    pattern_multiplier = max(
        0.0,
        min(
            1.0,
            math.sqrt(
                max(0.0, min(1.0, confidence))
                * max(0.0, min(1.0, quality_score))
                * max(0.0, min(1.0, stability_score))
                * max(0.0, min(1.0, horizon_quality))
            ),
        ),
    )
    acute_multiplier = max(0.0, min(1.0, acute))
    replay = max(0.0, min(1.0, replay_multiplier))
    w5 = max(0.0, min(WEIGHT5, WEIGHT5 * pattern_multiplier * acute_multiplier))
    w30 = max(0.0, min(0.45, weight30 * pattern_multiplier * acute_multiplier * replay))
    w60 = max(0.0, min(0.55, weight60 * pattern_multiplier * acute_multiplier * replay))
    if horizon <= 5:
        return w5
    if horizon <= 30:
        ratio = max(0.0, min(1.0, (horizon - 5) / 25.0))
        return w5 + (w30 - w5) * ratio
    if horizon <= 60:
        ratio = max(0.0, min(1.0, (horizon - 30) / 30.0))
        return w30 + (w60 - w30) * ratio
    return w60


def median_reversion_bias(current_glucose: float, bg_median: float, p10: float, p25: float, p75: float, p90: float, horizon: int, horizon_quality: float, stability_score: float, replay_status: str, acute_attenuation: float, stale: bool) -> float:
    if stale or horizon < 30:
        return 0.0
    if p25 <= current_glucose <= p75:
        return 0.0
    replay_multiplier = {
        "HELPFUL": 1.0,
        "NEUTRAL": 0.75,
        "INSUFFICIENT": 0.55,
        "HARMFUL": 0.0,
    }.get(replay_status, 0.0)
    if replay_multiplier <= 0.0:
        return 0.0
    band_edge = p75 if current_glucose > p75 else p25
    band_excess = abs(current_glucose - band_edge)
    if band_excess <= 1e-6:
        return 0.0
    spread = max(p75 - p25, 0.35, p90 - p10)
    deviation_strength = max(0.20, min(1.0, (band_excess / spread) + 0.20))
    pull = bg_median - current_glucose
    base_factor = 0.18 if horizon < 60 else 0.30
    raw = pull * base_factor * deviation_strength * max(0.0, min(1.0, horizon_quality)) * max(0.0, min(1.0, stability_score)) * replay_multiplier * max(0.0, min(1.0, acute_attenuation))
    if horizon < 60:
        return max(-0.18, min(0.18, raw))
    return max(-0.32, min(0.32, raw))


def telemetry_snapshot(lookup: TelemetryLookup, ts: int) -> dict[str, float | None]:
    return {key: lookup.latest(key, ts) for key in TELEMETRY_KEYS}


def resolve_replay_metric(stable: ReplaySlotRow | None, recency: ReplaySlotRow | None, requested_day_type: str, correction_clamp: float) -> ResolvedReplayMetric:
    if stable is None:
        return ResolvedReplayMetric(
            bias=0.0,
            sample_count=0,
            win_rate=0.0,
            mae_improvement_mmol=0.0,
            status="INSUFFICIENT",
        )
    recency_correction = 0.0
    if recency is not None:
        if same_bias_direction(stable.median_signed_error_circadian, recency.median_signed_error_circadian) or abs(recency.median_signed_error_circadian) <= correction_clamp * 0.5:
            recency_correction = max(
                -correction_clamp,
                min(
                    correction_clamp,
                    recency.median_signed_error_circadian - stable.median_signed_error_circadian,
                ),
            )
    return ResolvedReplayMetric(
        bias=stable.median_signed_error_circadian + recency_correction,
        sample_count=stable.sample_count,
        win_rate=stable.win_rate,
        mae_improvement_mmol=stable.mae_improvement_mmol,
        status=replay_bucket_status(
            day_type=requested_day_type,
            sample_count=stable.sample_count,
            coverage_days=stable.coverage_days,
            required_coverage_days=MIN_ACTIVE_DAYS[requested_day_type],
            mae_improvement_mmol=stable.mae_improvement_mmol,
            win_rate=stable.win_rate,
        ),
    )


def circular_slot_distance(a: int, b: int) -> int:
    diff = abs(a - b)
    return min(diff, 96 - diff)


def aggregate_replay_neighborhood(
    rows: list[ReplaySlotRow],
    slot_idx: int,
    horizon: int,
    radius: int,
    include_center: bool,
) -> ReplaySlotRow | None:
    candidates = [
        row for row in rows
        if row.horizon == horizon
        and circular_slot_distance(row.slot_index, slot_idx) <= radius
        and (include_center or row.slot_index != slot_idx)
    ]
    if not candidates:
        return None
    weighted = []
    for row in candidates:
        distance = circular_slot_distance(row.slot_index, slot_idx)
        distance_weight = {0: 1.0, 1: 0.65, 2: 0.40}.get(distance, 0.0)
        weight = max(0.0, distance_weight * row.sample_count)
        if weight > 0.0:
            weighted.append((row, weight))
    if not weighted:
        return None
    total_weight = max(1.0, sum(weight for _, weight in weighted))
    template = max(weighted, key=lambda item: item[1])[0]

    def wavg(selector):
        return sum(selector(row) * weight for row, weight in weighted) / total_weight

    return ReplaySlotRow(
        day_type=template.day_type,
        window_days=template.window_days,
        slot_index=slot_idx,
        horizon=template.horizon,
        sample_count=sum(row.sample_count for row, _ in weighted),
        coverage_days=max(row.coverage_days for row, _ in weighted),
        mae_baseline=wavg(lambda row: row.mae_baseline),
        mae_circadian=wavg(lambda row: row.mae_circadian),
        mae_improvement_mmol=wavg(lambda row: row.mae_improvement_mmol),
        median_signed_error_baseline=wavg(lambda row: row.median_signed_error_baseline),
        median_signed_error_circadian=wavg(lambda row: row.median_signed_error_circadian),
        win_rate=max(0.0, min(1.0, wavg(lambda row: row.win_rate))),
        quality_score=max(0.0, min(1.0, wavg(lambda row: row.quality_score))),
        updated_at=max(row.updated_at for row, _ in weighted),
    )


def resolve_replay_bucket(rows: list[ReplaySlotRow], slot_idx: int, horizon: int, requested_day_type: str) -> ReplaySlotRow | None:
    exact = aggregate_replay_neighborhood(rows, slot_idx, horizon, 0, True)
    exact_status = None
    if exact is not None:
        exact_status = replay_bucket_status(
            day_type=requested_day_type,
            sample_count=exact.sample_count,
            coverage_days=exact.coverage_days,
            required_coverage_days=MIN_ACTIVE_DAYS[requested_day_type],
            mae_improvement_mmol=exact.mae_improvement_mmol,
            win_rate=exact.win_rate,
        )
    neighborhood = aggregate_replay_neighborhood(rows, slot_idx, horizon, 2, False)
    neighborhood_status = None
    if neighborhood is not None:
        neighborhood_status = replay_bucket_status(
            day_type=requested_day_type,
            sample_count=neighborhood.sample_count,
            coverage_days=neighborhood.coverage_days,
            required_coverage_days=MIN_ACTIVE_DAYS[requested_day_type],
            mae_improvement_mmol=neighborhood.mae_improvement_mmol,
            win_rate=neighborhood.win_rate,
        )
    if exact is None:
        return neighborhood
    if exact_status == "INSUFFICIENT":
        return neighborhood or exact
    if neighborhood is None:
        return exact
    if should_prefer_replay_neighborhood(exact, exact_status, neighborhood, neighborhood_status):
        return neighborhood
    return exact


def should_prefer_replay_neighborhood(
    exact: ReplaySlotRow,
    exact_status: str | None,
    neighborhood: ReplaySlotRow,
    neighborhood_status: str | None,
) -> bool:
    if exact_status is None or neighborhood_status is None:
        return False
    exact_rank = replay_bucket_rank(exact_status)
    neighborhood_rank = replay_bucket_rank(neighborhood_status)
    if neighborhood_rank <= exact_rank:
        return False
    if exact_status == "HARMFUL":
        return True
    if (
        exact_status == "NEUTRAL"
        and neighborhood_status == "HELPFUL"
        and neighborhood.sample_count >= exact.sample_count
        and neighborhood.coverage_days >= exact.coverage_days
    ):
        return True
    return False


def replay_bucket_rank(status: str) -> int:
    if status == "HELPFUL":
        return 3
    if status == "NEUTRAL":
        return 2
    if status == "HARMFUL":
        return 1
    return 0


def transition_quality_for_row(stat: TransitionRow | None, default_quality: float, spread_reference: float) -> float:
    if stat is None:
        return max(0.25, min(0.80, default_quality))
    return transition_quality(
        sample_count=stat.sample_count,
        confidence=stat.confidence,
        delta_p25=stat.delta_p25,
        delta_p75=stat.delta_p75,
        default_quality=default_quality,
        spread_reference=spread_reference,
    )


def compute_prior(
    now_ts: int,
    current_glucose: float,
    telemetry: dict[str, float],
    snapshots: list[SnapshotRow],
    slot_stats: list[SlotStatRow],
    transition_stats: list[TransitionRow],
    replay_stats: list[ReplaySlotRow],
    tz: ZoneInfo,
) -> PriorState | None:
    requested = current_day_type(now_ts, tz)
    snapshot = next((item for item in snapshots if item.day_type == requested), None)
    if snapshot is None:
        return None
    source = snapshot.segment_source
    slot = slot_index(now_ts, tz)
    stable_slot = next(
        (
            item
            for item in slot_stats
            if item.day_type == source
            and item.window_days == snapshot.stable_window_days
            and item.slot_index == slot
        ),
        None,
    )
    if stable_slot is None:
        return None
    recency_slot = next(
        (
            item
            for item in slot_stats
            if item.day_type == source
            and item.window_days == snapshot.recency_window_days
            and item.slot_index == slot
        ),
        None,
    )
    transitions_by_horizon = {
        item.horizon: item
        for item in transition_stats
        if item.day_type == source
        and item.window_days == snapshot.stable_window_days
        and item.slot_index == slot
        and item.horizon in HORIZONS
    }
    recency_transitions = {
        item.horizon: item
        for item in transition_stats
        if item.day_type == source
        and item.window_days == snapshot.recency_window_days
        and item.slot_index == slot
        and item.horizon in HORIZONS
    }
    recency_weight = max(0.0, min(RECENCY_MAX_WEIGHT, snapshot.recency_weight))
    blended_bg = blend_bounded(stable_slot.median_bg, recency_slot.median_bg if recency_slot else None, recency_weight, RECENCY_BG_CLAMP)
    delta15 = blend_bounded(
        transitions_by_horizon.get(15).delta_median if transitions_by_horizon.get(15) else 0.0,
        recency_transitions.get(15).delta_median if recency_transitions.get(15) else None,
        recency_weight,
        RECENCY_DELTA_CLAMP,
    )
    delta30 = blend_bounded(
        transitions_by_horizon.get(30).delta_median if transitions_by_horizon.get(30) else 0.0,
        recency_transitions.get(30).delta_median if recency_transitions.get(30) else None,
        recency_weight,
        RECENCY_DELTA_CLAMP,
    )
    delta60 = blend_bounded(
        transitions_by_horizon.get(60).delta_median if transitions_by_horizon.get(60) else 0.0,
        recency_transitions.get(60).delta_median if recency_transitions.get(60) else None,
        recency_weight,
        RECENCY_DELTA_CLAMP,
    )
    residual_bias30 = max(-0.25, min(0.25, transitions_by_horizon.get(30).residual_bias_mmol if transitions_by_horizon.get(30) else 0.0))
    residual_bias60 = max(-0.40, min(0.40, transitions_by_horizon.get(60).residual_bias_mmol if transitions_by_horizon.get(60) else 0.0))
    horizon_quality30 = transition_quality_for_row(
        transitions_by_horizon.get(30),
        default_quality=stable_slot.quality_score,
        spread_reference=2.4,
    )
    horizon_quality60 = transition_quality_for_row(
        transitions_by_horizon.get(60),
        default_quality=stable_slot.quality_score,
        spread_reference=3.2,
    )
    stability_score = slot_stability_score(
        p10=stable_slot.p10,
        p25=stable_slot.p25,
        p75=stable_slot.p75,
        p90=stable_slot.p90,
        fast_rise_rate=stable_slot.fast_rise_rate,
        fast_drop_rate=stable_slot.fast_drop_rate,
        mean_cob=stable_slot.mean_cob,
        mean_uam=stable_slot.mean_uam,
    )
    stable_replay_rows = [
        item for item in replay_stats
        if item.day_type == requested
        and item.window_days == snapshot.stable_window_days
        and item.horizon in EVAL_HORIZONS
    ]
    recency_replay_rows = [
        item for item in replay_stats
        if item.day_type == requested
        and item.window_days == snapshot.recency_window_days
        and item.horizon in EVAL_HORIZONS
    ]
    fallback_replay_rows = [
        item for item in replay_stats
        if item.day_type == "ALL"
        and item.window_days == snapshot.stable_window_days
        and item.horizon in EVAL_HORIZONS
    ]
    replay30 = resolve_replay_metric(
        stable=resolve_replay_bucket(stable_replay_rows, slot, 30, requested) or resolve_replay_bucket(fallback_replay_rows, slot, 30, "ALL"),
        recency=resolve_replay_bucket(recency_replay_rows, slot, 30, requested),
        requested_day_type=requested,
        correction_clamp=0.12,
    )
    replay60 = resolve_replay_metric(
        stable=resolve_replay_bucket(stable_replay_rows, slot, 60, requested) or resolve_replay_bucket(fallback_replay_rows, slot, 60, "ALL"),
        recency=resolve_replay_bucket(recency_replay_rows, slot, 60, requested),
        requested_day_type=requested,
        correction_clamp=0.20,
    )
    delta5 = telemetry.get("sensor_quality_delta5_mmol") or telemetry.get("delta5_mmol") or telemetry.get("glucose_delta5_mmol") or 0.0
    cob = telemetry.get("cob_effective_grams") or telemetry.get("cob_external_adjusted_grams") or telemetry.get("cob_grams") or 0.0
    iob = telemetry.get("iob_effective_units") or telemetry.get("iob_real_units") or telemetry.get("iob_units") or 0.0
    acute = abs(delta5) > 0.30 or cob >= 15.0 or (telemetry.get("uam_value") or 0.0) >= 0.5 or iob >= 3.0
    stale = (telemetry.get("sensor_quality_blocked") or 0.0) >= 0.5 or (telemetry.get("sensor_quality_suspect_false_low") or 0.0) >= 0.5
    confidence = max(
        0.0,
        min(
            1.0,
            min(
                [snapshot.confidence, stable_slot.confidence]
                + [item.confidence for item in transitions_by_horizon.values()]
            ),
        ),
    )
    median_reversion30 = median_reversion_bias(
        current_glucose=current_glucose,
        bg_median=stable_slot.median_bg,
        p10=stable_slot.p10,
        p25=stable_slot.p25,
        p75=stable_slot.p75,
        p90=stable_slot.p90,
        horizon=30,
        horizon_quality=horizon_quality30,
        stability_score=stability_score,
        replay_status=replay30.status,
        acute_attenuation=0.4 if acute else 1.0,
        stale=stale,
    )
    median_reversion60 = median_reversion_bias(
        current_glucose=current_glucose,
        bg_median=stable_slot.median_bg,
        p10=stable_slot.p10,
        p25=stable_slot.p25,
        p75=stable_slot.p75,
        p90=stable_slot.p90,
        horizon=60,
        horizon_quality=horizon_quality60,
        stability_score=stability_score,
        replay_status=replay60.status,
        acute_attenuation=0.4 if acute else 1.0,
        stale=stale,
    )
    return PriorState(
        confidence=confidence,
        quality_score=stable_slot.quality_score,
        stability_score=stability_score,
        horizon_quality30=horizon_quality30,
        horizon_quality60=horizon_quality60,
        acute_attenuation=0.4 if acute else 1.0,
        stale_blocked=stale,
        bg_median=max(MIN_GLUCOSE_MMOL, min(MAX_GLUCOSE_MMOL, blended_bg)),
        slot_p10=stable_slot.p10,
        slot_p25=stable_slot.p25,
        slot_p75=stable_slot.p75,
        slot_p90=stable_slot.p90,
        delta15=delta15,
        delta30=delta30 + residual_bias30,
        delta60=delta60 + residual_bias60,
        residual_bias30=residual_bias30,
        residual_bias60=residual_bias60,
        median_reversion30=median_reversion30,
        median_reversion60=median_reversion60,
        replay_bias30=max(-0.20, min(0.20, replay30.bias)),
        replay_bias60=max(-0.35, min(0.35, replay60.bias)),
        replay_sample_count30=replay30.sample_count,
        replay_sample_count60=replay60.sample_count,
        replay_win_rate30=replay30.win_rate,
        replay_win_rate60=replay60.win_rate,
        replay_mae_improvement30=replay30.mae_improvement_mmol,
        replay_mae_improvement60=replay60.mae_improvement_mmol,
        replay_status30=replay30.status,
        replay_status60=replay60.status,
        segment_source=source,
    )


def prior_delta_for_horizon(prior: PriorState, horizon: int) -> float:
    base = 0.0
    if horizon <= 5:
        base = prior.delta15 * 0.33
    elif horizon <= 15:
        base = prior.delta15
    elif horizon <= 30:
        ratio = max(0.0, min(1.0, (horizon - 15) / 15.0))
        base = prior.delta15 + (prior.delta30 - prior.delta15) * ratio
    elif horizon <= 60:
        ratio = max(0.0, min(1.0, (horizon - 30) / 30.0))
        base = prior.delta30 + (prior.delta60 - prior.delta30) * ratio
    else:
        base = prior.delta60
    if horizon <= 15:
        reversion = 0.0
    elif horizon <= 30:
        ratio = max(0.0, min(1.0, (horizon - 15) / 15.0))
        reversion = prior.median_reversion30 * ratio
    elif horizon <= 60:
        ratio = max(0.0, min(1.0, (horizon - 30) / 30.0))
        reversion = prior.median_reversion30 + (prior.median_reversion60 - prior.median_reversion30) * ratio
    else:
        reversion = prior.median_reversion60
    return base + reversion


def reconstruct_baseline_value(row: ForecastRow, prior: PriorState, current_glucose: float) -> float:
    if "|circadian_v1" not in row.model_version and "|circadian_v2" not in row.model_version:
        return max(MIN_GLUCOSE_MMOL, min(MAX_GLUCOSE_MMOL, row.value))
    replay_multiplier = replay_multiplier_for_prior(prior, row.horizon) if "|circadian_v2" in row.model_version else 1.0
    weight = weight_for_horizon(
        horizon=row.horizon,
        confidence=prior.confidence,
        quality_score=prior.quality_score,
        stability_score=prior.stability_score,
        horizon_quality=horizon_quality_for_horizon(prior, row.horizon),
        acute=prior.acute_attenuation,
        replay_multiplier=replay_multiplier,
        weight30=WEIGHT30,
        weight60=WEIGHT60,
    )
    if weight <= 1e-6 or weight >= 0.999:
        return max(MIN_GLUCOSE_MMOL, min(MAX_GLUCOSE_MMOL, row.value))
    prior_target = max(MIN_GLUCOSE_MMOL, min(MAX_GLUCOSE_MMOL, current_glucose + prior_delta_for_horizon(prior, row.horizon)))
    replay_bias = replay_bias_for_horizon(prior, row.horizon)
    return max(
        MIN_GLUCOSE_MMOL,
        min(MAX_GLUCOSE_MMOL, (row.value - replay_bias - prior_target * weight) / (1.0 - weight)),
    )


def apply_prior_value(baseline_value: float, horizon: int, prior: PriorState, current_glucose: float) -> float:
    if prior.stale_blocked:
        return baseline_value
    weight = weight_for_horizon(
        horizon=horizon,
        confidence=prior.confidence,
        quality_score=prior.quality_score,
        stability_score=prior.stability_score,
        horizon_quality=horizon_quality_for_horizon(prior, horizon),
        acute=prior.acute_attenuation,
        replay_multiplier=replay_multiplier_for_prior(prior, horizon),
        weight30=WEIGHT30,
        weight60=WEIGHT60,
    )
    if weight <= 1e-6:
        return baseline_value
    prior_target = max(MIN_GLUCOSE_MMOL, min(MAX_GLUCOSE_MMOL, current_glucose + prior_delta_for_horizon(prior, horizon)))
    replay_bias = replay_bias_for_horizon(prior, horizon)
    blended_raw = baseline_value * (1.0 - weight) + prior_target * weight + replay_bias
    shift_cap = 0.12 if horizon <= 5 else 0.45 if horizon <= 30 else 0.75
    shift = max(-shift_cap, min(shift_cap, blended_raw - baseline_value))
    return max(MIN_GLUCOSE_MMOL, min(MAX_GLUCOSE_MMOL, baseline_value + shift))


def is_replay_low_acute(telemetry: dict[str, float | None], stale_blocked: bool) -> bool:
    if stale_blocked:
        return False
    delta5_value = telemetry.get("sensor_quality_delta5_mmol")
    if delta5_value is None:
        delta5_value = telemetry.get("delta5_mmol")
    if delta5_value is None:
        delta5_value = telemetry.get("glucose_delta5_mmol")
    cob_value = telemetry.get("cob_effective_grams")
    if cob_value is None:
        cob_value = telemetry.get("cob_external_adjusted_grams")
    if cob_value is None:
        cob_value = telemetry.get("cob_grams")
    iob_value = telemetry.get("iob_effective_units")
    if iob_value is None:
        iob_value = telemetry.get("iob_real_units")
    if iob_value is None:
        iob_value = telemetry.get("iob_units")
    uam_value = telemetry.get("uam_value")
    has_dynamics = delta5_value is not None
    has_inputs = cob_value is not None or iob_value is not None or uam_value is not None
    if not has_dynamics or not has_inputs:
        return False
    delta5 = delta5_value or 0.0
    cob = cob_value or 0.0
    iob = iob_value or 0.0
    uam = uam_value or 0.0
    blocked = (telemetry.get("sensor_quality_blocked") or 0.0) >= 0.5
    suspect_false_low = (telemetry.get("sensor_quality_suspect_false_low") or 0.0) >= 0.5
    return (not blocked and not suspect_false_low and abs(delta5) <= 0.25 and cob < 10.0 and iob < 2.5 and uam < 0.5)


def evaluate_window(
    forecasts: list[ForecastRow],
    glucose_series: GlucoseSeries,
    telemetry_lookup: TelemetryLookup,
    snapshots: list[SnapshotRow],
    slot_stats: list[SlotStatRow],
    transition_stats: list[TransitionRow],
    replay_stats: list[ReplaySlotRow],
    now_ts: int,
    days: int,
    tz: ZoneInfo,
) -> dict:
    since_ts = now_ts - days * DAY_MS
    baseline_forecasts = [row for row in forecasts if row.ts >= since_ts and row.horizon in EVAL_HORIZONS]
    per_bucket: dict[tuple[int, int], ForecastRow] = {}
    for row in baseline_forecasts:
        key = (round_to_five_minute_bucket(row.ts), row.horizon)
        existing = per_bucket.get(key)
        if existing is None or row.ts > existing.ts:
            per_bucket[key] = row
    eval_rows = sorted(per_bucket.values(), key=lambda x: (x.ts, x.horizon))

    summary = {
        "days": days,
        "rows": len(eval_rows),
        "all": {},
        "low_acute": {},
        "weekday": {},
        "weekend": {},
    }
    buckets: dict[str, dict[int, list[tuple[float, float]]]] = {
        "all": defaultdict(list),
        "low_acute": defaultdict(list),
        "weekday": defaultdict(list),
        "weekend": defaultdict(list),
    }
    applied_count = 0
    total_shift = defaultdict(list)

    for row in eval_rows:
        current_glucose = glucose_series.interpolate(row.ts, max_gap_min=20)
        actual = glucose_series.interpolate(round_to_five_minute_bucket(row.ts + row.horizon * MINUTE_MS), max_gap_min=20)
        if current_glucose is None or actual is None:
            continue
        telemetry = telemetry_snapshot(telemetry_lookup, row.ts)
        prior = compute_prior(
            now_ts=row.ts,
            current_glucose=current_glucose,
            telemetry=telemetry,
            snapshots=snapshots,
            slot_stats=slot_stats,
            transition_stats=transition_stats,
            replay_stats=replay_stats,
            tz=tz,
        )
        if prior is None:
            continue
        baseline_pred = reconstruct_baseline_value(row, prior, current_glucose)
        enabled_pred = baseline_pred
        low_acute = is_replay_low_acute(telemetry, prior.stale_blocked)
        if not prior.stale_blocked:
            enabled_pred = apply_prior_value(baseline_pred, row.horizon, prior, current_glucose)
            if abs(enabled_pred - baseline_pred) > 1e-6:
                applied_count += 1
                total_shift[row.horizon].append(enabled_pred - baseline_pred)
        day_bucket = "weekend" if current_day_type(row.ts, tz) == "WEEKEND" else "weekday"
        buckets["all"][row.horizon].append((abs(actual - baseline_pred), abs(actual - enabled_pred)))
        buckets[day_bucket][row.horizon].append((abs(actual - baseline_pred), abs(actual - enabled_pred)))
        if low_acute:
            buckets["low_acute"][row.horizon].append((abs(actual - baseline_pred), abs(actual - enabled_pred)))

    summary["appliedRows"] = applied_count
    summary["appliedPct"] = (applied_count * 100.0 / len(eval_rows)) if eval_rows else 0.0
    summary["meanShift"] = {str(h): (avg_or_none(total_shift[h]) or 0.0) for h in EVAL_HORIZONS}

    for group, grouped in buckets.items():
        for horizon in EVAL_HORIZONS:
            rows = grouped[horizon]
            if not rows:
                continue
            mae_base = sum(x[0] for x in rows) / len(rows)
            mae_enabled = sum(x[1] for x in rows) / len(rows)
            summary[group][str(horizon)] = {
                "n": len(rows),
                "mae_baseline": mae_base,
                "mae_circadian": mae_enabled,
                "delta_mmol": mae_enabled - mae_base,
                "delta_pct": ((mae_enabled - mae_base) / mae_base * 100.0) if mae_base > 1e-6 else 0.0,
                "win_rate": sum(1 for base_err, circ_err in rows if circ_err + 1e-9 < base_err) / len(rows),
            }
    return summary


def load_db(db_path: Path) -> tuple[
    list[GlucosePoint],
    list[ForecastRow],
    list[tuple[int, str, float | None, str | None]],
    list[SnapshotRow],
    list[SlotStatRow],
    list[TransitionRow],
    list[ReplaySlotRow],
]:
    try:
        conn = sqlite3.connect(f"file:{db_path}?mode=ro", uri=True)
        conn.row_factory = sqlite3.Row
        try:
            def fetch_all(sql: str, params: tuple | list = (), required: bool = False):
                try:
                    return conn.execute(sql, params).fetchall()
                except sqlite3.DatabaseError:
                    if required:
                        raise
                    return []

            glucose_rows = fetch_all(
                "select timestamp, mmol, source, quality from glucose_samples order by timestamp",
                required=True,
            )
            forecast_rows = fetch_all(
                "select timestamp, horizonMinutes, valueMmol, ciLow, ciHigh, modelVersion from forecasts order by timestamp",
                required=True,
            )
            telemetry_rows = fetch_all(
                "select timestamp, key, valueDouble, valueText from telemetry_samples where key in (%s) order by timestamp"
                % ",".join("?" for _ in TELEMETRY_KEYS),
                TELEMETRY_KEYS,
            )
            snapshot_rows = fetch_all(
                "select dayType, segmentSource, stableWindowDays, recencyWindowDays, recencyWeight, confidence, qualityScore from circadian_pattern_snapshots"
            )
            slot_rows = fetch_all(
                "select dayType, windowDays, slotIndex, sampleCount, activeDays, medianBg, p10, p25, p75, p90, meanCob, meanIob, meanUam, meanActivity, fastRiseRate, fastDropRate, confidence, qualityScore from circadian_slot_stats"
            )
            transition_rows = fetch_all(
                "select dayType, windowDays, slotIndex, horizonMinutes, sampleCount, deltaMedian, deltaP25, deltaP75, residualBiasMmol, confidence from circadian_transition_stats"
            )
            replay_rows = fetch_all(
                "select dayType, windowDays, slotIndex, horizonMinutes, sampleCount, coverageDays, maeBaseline, maeCircadian, maeImprovementMmol, medianSignedErrorBaseline, medianSignedErrorCircadian, winRate, qualityScore, updatedAt from circadian_replay_slot_stats"
            )
        finally:
            conn.close()

        glucose = [GlucosePoint(ts=row["timestamp"], mmol=row["mmol"], source=row["source"], quality=row["quality"]) for row in glucose_rows]
        forecasts = [
            ForecastRow(
                ts=row["timestamp"],
                horizon=row["horizonMinutes"],
                value=row["valueMmol"],
                ci_low=row["ciLow"],
                ci_high=row["ciHigh"],
                model_version=row["modelVersion"],
            )
            for row in forecast_rows
        ]
        telemetry = [(row["timestamp"], row["key"], row["valueDouble"], row["valueText"]) for row in telemetry_rows]
        snapshots = [
            SnapshotRow(
                day_type=row["dayType"],
                segment_source=row["segmentSource"],
                stable_window_days=row["stableWindowDays"],
                recency_window_days=row["recencyWindowDays"],
                recency_weight=row["recencyWeight"],
                confidence=row["confidence"],
                quality_score=row["qualityScore"],
            )
            for row in snapshot_rows
        ]
        slot_stats = [
            SlotStatRow(
                day_type=row["dayType"],
                window_days=row["windowDays"],
                slot_index=row["slotIndex"],
                sample_count=row["sampleCount"],
                active_days=row["activeDays"],
                median_bg=row["medianBg"],
                p10=row["p10"],
                p25=row["p25"],
                p75=row["p75"],
                p90=row["p90"],
                mean_cob=row["meanCob"],
                mean_iob=row["meanIob"],
                mean_uam=row["meanUam"],
                mean_activity=row["meanActivity"],
                fast_rise_rate=row["fastRiseRate"],
                fast_drop_rate=row["fastDropRate"],
                confidence=row["confidence"],
                quality_score=row["qualityScore"],
            )
            for row in slot_rows
        ]
        transition_stats = [
            TransitionRow(
                day_type=row["dayType"],
                window_days=row["windowDays"],
                slot_index=row["slotIndex"],
                horizon=row["horizonMinutes"],
                sample_count=row["sampleCount"],
                delta_median=row["deltaMedian"],
                delta_p25=row["deltaP25"],
                delta_p75=row["deltaP75"],
                residual_bias_mmol=row["residualBiasMmol"],
                confidence=row["confidence"],
            )
            for row in transition_rows
        ]
        replay_stats = [
            ReplaySlotRow(
                day_type=row["dayType"],
                window_days=row["windowDays"],
                slot_index=row["slotIndex"],
                horizon=row["horizonMinutes"],
                sample_count=row["sampleCount"],
                coverage_days=row["coverageDays"],
                mae_baseline=row["maeBaseline"],
                mae_circadian=row["maeCircadian"],
                mae_improvement_mmol=row["maeImprovementMmol"],
                median_signed_error_baseline=row["medianSignedErrorBaseline"],
                median_signed_error_circadian=row["medianSignedErrorCircadian"],
                win_rate=row["winRate"],
                quality_score=row["qualityScore"],
                updated_at=row["updatedAt"],
            )
            for row in replay_rows
        ]
        return glucose, forecasts, telemetry, snapshots, slot_stats, transition_stats, replay_stats
    except sqlite3.DatabaseError:
        return load_from_csv_exports(db_path.parent)


def load_from_csv_exports(base_dir: Path) -> tuple[
    list[GlucosePoint],
    list[ForecastRow],
    list[tuple[int, str, float | None, str | None]],
    list[SnapshotRow],
    list[SlotStatRow],
    list[TransitionRow],
    list[ReplaySlotRow],
]:
    glucose_csv = base_dir / "csv_glucose.csv"
    forecasts_csv = base_dir / "csv_forecasts.csv"
    telemetry_csv = base_dir / "csv_telemetry.csv"
    telemetry_extra_csv = base_dir / "csv_telemetry_extra.csv"
    if not glucose_csv.exists() or not forecasts_csv.exists():
        raise FileNotFoundError("SQLite database is unreadable and fallback CSV exports are missing")

    glucose: list[GlucosePoint] = []
    with glucose_csv.open() as fh:
        for row in csv.DictReader(fh):
            glucose.append(
                GlucosePoint(
                    ts=int(row["timestamp"]),
                    mmol=float(row["mmol"]),
                    source=row["source"],
                    quality=row["quality"],
                )
            )

    forecasts: list[ForecastRow] = []
    with forecasts_csv.open() as fh:
        for row in csv.DictReader(fh):
            forecasts.append(
                ForecastRow(
                    ts=int(row["timestamp"]),
                    horizon=int(row["horizonMinutes"]),
                    value=float(row["valueMmol"]),
                    ci_low=float(row["ciLow"]),
                    ci_high=float(row["ciHigh"]),
                    model_version=row["modelVersion"],
                )
            )

    telemetry: list[tuple[int, str, float | None, str | None]] = []
    for path in (telemetry_csv, telemetry_extra_csv):
        if not path.exists():
            continue
        with path.open() as fh:
            for row in csv.DictReader(fh):
                raw_double = row.get("valueDouble")
                value_double = float(raw_double) if raw_double not in (None, "") else None
                telemetry.append((int(row["timestamp"]), row["key"], value_double, row.get("valueText")))
    telemetry.sort(key=lambda item: item[0])
    return glucose, forecasts, telemetry, [], [], [], []


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("db", type=Path)
    parser.add_argument("--timezone", default="Europe/Moscow")
    parser.add_argument("--out", type=Path, default=None)
    args = parser.parse_args()

    tz = ZoneInfo(args.timezone)
    glucose, forecasts, telemetry, snapshots, slot_stats, transition_stats, replay_stats = load_db(args.db)
    glucose_series = GlucoseSeries(glucose)
    telemetry_lookup = TelemetryLookup(telemetry)
    now_ts = max((row.ts for row in forecasts), default=int(datetime.now(tz).timestamp() * 1000))

    results = {
        "generatedAt": datetime.now(tz).isoformat(),
        "db": str(args.db),
        "timezone": args.timezone,
        "windows": [
            evaluate_window(forecasts, glucose_series, telemetry_lookup, snapshots, slot_stats, transition_stats, replay_stats, now_ts, 1, tz),
            evaluate_window(forecasts, glucose_series, telemetry_lookup, snapshots, slot_stats, transition_stats, replay_stats, now_ts, 7, tz),
        ],
    }

    text = json.dumps(results, ensure_ascii=False, indent=2)
    if args.out:
        args.out.write_text(text)
    print(text)


if __name__ == "__main__":
    main()
