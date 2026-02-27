from __future__ import annotations

import math
from collections import defaultdict
from datetime import UTC, datetime
from typing import Dict, List, Tuple

from ..models import (
    GlucosePoint,
    ReplayDayTypeStats,
    ReplayDriftStats,
    ReplayForecastStats,
    ReplayHourStats,
    ReplayReportResponse,
    ReplayRuleStats,
    TherapyEvent,
)
from .predict_service import predict
from .rule_service import evaluate_post_hypo_rebound


def _closest_glucose(points: List[GlucosePoint], target_ts: int, tolerance_ms: int) -> GlucosePoint | None:
    if not points:
        return None
    best = min(points, key=lambda p: abs(p.ts - target_ts))
    if abs(best.ts - target_ts) > tolerance_ms:
        return None
    return best


def build_replay_report(
    glucose: List[GlucosePoint],
    therapy_events: List[TherapyEvent],
    since: int,
    until: int,
    step_minutes: int = 5,
) -> ReplayReportResponse:
    sorted_glucose = sorted([g for g in glucose if since <= g.ts <= until], key=lambda x: x.ts)
    sorted_therapy = sorted([t for t in therapy_events if since <= t.ts <= until], key=lambda x: x.ts)

    if len(sorted_glucose) < 30:
        return ReplayReportResponse(
            since=since,
            until=until,
            points=len(sorted_glucose),
            forecastStats=[],
            ruleStats=[ReplayRuleStats(ruleId="PostHypoReboundGuard.v1", triggered=0, blocked=0, noMatch=0)],
            dayTypeStats=[],
            hourlyStats=[],
            driftStats=[],
        )

    horizon_errors: Dict[int, List[Tuple[float, float, float, int]]] = defaultdict(list)
    rule_counter = {"TRIGGERED": 0, "BLOCKED": 0, "NO_MATCH": 0}

    step = max(1, step_minutes)
    stride = max(1, step // 5)

    for i in range(24, len(sorted_glucose), stride):
        current = sorted_glucose[i]
        window_glucose = sorted_glucose[max(0, i - 72): i + 1]
        window_start = current.ts - 24 * 60 * 60 * 1000
        window_therapy = [t for t in sorted_therapy if window_start <= t.ts <= current.ts]

        forecasts = predict(window_glucose)
        for fc in forecasts:
            tolerance_ms = 5 * 60 * 1000 if fc.horizon <= 10 else 15 * 60 * 1000
            actual = _closest_glucose(sorted_glucose, fc.ts, tolerance_ms)
            if actual is None:
                continue

            abs_error = abs(actual.valueMmol - fc.valueMmol)
            sq_error = abs_error * abs_error
            ard = abs_error / actual.valueMmol if actual.valueMmol > 0 else 0.0
            horizon_errors[fc.horizon].append((abs_error, sq_error, ard, actual.ts))

        decision = evaluate_post_hypo_rebound(window_glucose)
        rule_counter[decision.state] += 1

    forecast_stats: List[ReplayForecastStats] = []
    for horizon, errors in sorted(horizon_errors.items(), key=lambda x: x[0]):
        if not errors:
            continue
        n = float(len(errors))
        mae = sum(e[0] for e in errors) / n
        rmse = math.sqrt(sum(e[1] for e in errors) / n)
        mard = (sum(e[2] for e in errors) / n) * 100.0
        forecast_stats.append(
            ReplayForecastStats(
                horizon=horizon,
                sampleCount=len(errors),
                mae=mae,
                rmse=rmse,
                mardPct=mard,
            )
        )

    day_type_stats = _build_day_type_stats(horizon_errors)
    hourly_stats = _build_hourly_stats(horizon_errors)
    drift_stats = _build_drift_stats(horizon_errors)

    rule_stats = [
        ReplayRuleStats(
            ruleId="PostHypoReboundGuard.v1",
            triggered=rule_counter["TRIGGERED"],
            blocked=rule_counter["BLOCKED"],
            noMatch=rule_counter["NO_MATCH"],
        )
    ]

    return ReplayReportResponse(
        since=since,
        until=until,
        points=len(sorted_glucose),
        forecastStats=forecast_stats,
        ruleStats=rule_stats,
        dayTypeStats=day_type_stats,
        hourlyStats=hourly_stats,
        driftStats=drift_stats,
    )


def _build_day_type_stats(horizon_errors: Dict[int, List[Tuple[float, float, float, int]]]) -> List[ReplayDayTypeStats]:
    grouped: Dict[str, Dict[int, List[Tuple[float, float, float]]]] = {
        "WEEKDAY": defaultdict(list),
        "WEEKEND": defaultdict(list),
    }
    for horizon, errors in horizon_errors.items():
        for abs_error, sq_error, ard, ts in errors:
            day_type = "WEEKEND" if datetime.fromtimestamp(ts / 1000, tz=UTC).weekday() >= 5 else "WEEKDAY"
            grouped[day_type][horizon].append((abs_error, sq_error, ard))

    result: List[ReplayDayTypeStats] = []
    for day_type in ("WEEKDAY", "WEEKEND"):
        forecast_stats: List[ReplayForecastStats] = []
        for horizon in sorted(grouped[day_type].keys()):
            rows = grouped[day_type][horizon]
            if not rows:
                continue
            n = float(len(rows))
            forecast_stats.append(
                ReplayForecastStats(
                    horizon=horizon,
                    sampleCount=len(rows),
                    mae=sum(r[0] for r in rows) / n,
                    rmse=math.sqrt(sum(r[1] for r in rows) / n),
                    mardPct=(sum(r[2] for r in rows) / n) * 100.0,
                )
            )
        result.append(ReplayDayTypeStats(dayType=day_type, forecastStats=forecast_stats))
    return result


def _build_hourly_stats(horizon_errors: Dict[int, List[Tuple[float, float, float, int]]]) -> List[ReplayHourStats]:
    selected_horizon = 5 if 5 in horizon_errors else (min(horizon_errors.keys()) if horizon_errors else None)
    if selected_horizon is None:
        return []
    hourly: Dict[int, List[Tuple[float, float, float]]] = defaultdict(list)
    for abs_error, sq_error, ard, ts in horizon_errors[selected_horizon]:
        hour = datetime.fromtimestamp(ts / 1000, tz=UTC).hour
        hourly[hour].append((abs_error, sq_error, ard))

    result: List[ReplayHourStats] = []
    for hour in range(24):
        rows = hourly.get(hour, [])
        if not rows:
            continue
        n = float(len(rows))
        result.append(
            ReplayHourStats(
                hour=hour,
                sampleCount=len(rows),
                mae=sum(r[0] for r in rows) / n,
                rmse=math.sqrt(sum(r[1] for r in rows) / n),
                mardPct=(sum(r[2] for r in rows) / n) * 100.0,
            )
        )
    return result


def _build_drift_stats(horizon_errors: Dict[int, List[Tuple[float, float, float, int]]]) -> List[ReplayDriftStats]:
    result: List[ReplayDriftStats] = []
    for horizon in sorted(horizon_errors.keys()):
        rows = sorted(horizon_errors[horizon], key=lambda r: r[3])
        if len(rows) < 10:
            continue
        split = len(rows) // 2
        prev = rows[:split]
        recent = rows[split:]
        prev_mae = sum(r[0] for r in prev) / float(len(prev))
        recent_mae = sum(r[0] for r in recent) / float(len(recent))
        result.append(
            ReplayDriftStats(
                horizon=horizon,
                previousMae=prev_mae,
                recentMae=recent_mae,
                deltaMae=recent_mae - prev_mae,
            )
        )
    return result
