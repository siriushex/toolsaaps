from __future__ import annotations

import statistics
from typing import List

from ..models import ForecastOut, GlucosePoint


def _slope_per_5m(glucose: List[GlucosePoint]) -> float:
    if len(glucose) < 2:
        return 0.0
    first = glucose[-6] if len(glucose) >= 6 else glucose[0]
    last = glucose[-1]
    dt = max(1, last.ts - first.ts)
    return ((last.valueMmol - first.valueMmol) / dt) * (5 * 60 * 1000)


def predict(glucose: List[GlucosePoint]) -> List[ForecastOut]:
    if not glucose:
        return []

    sorted_points = sorted(glucose, key=lambda p: p.ts)
    latest = sorted_points[-1]
    slope = _slope_per_5m(sorted_points)

    value_5 = latest.valueMmol + slope

    window = [p.valueMmol for p in sorted_points[-24:]]
    base = statistics.fmean(window) if window else latest.valueMmol
    value_60 = base + slope * 12

    return [
        ForecastOut(
            ts=latest.ts + 5 * 60 * 1000,
            horizon=5,
            valueMmol=value_5,
            ciLow=max(2.2, value_5 - 0.45),
            ciHigh=value_5 + 0.45,
            modelVersion="cloud-hf-v1",
        ),
        ForecastOut(
            ts=latest.ts + 60 * 60 * 1000,
            horizon=60,
            valueMmol=value_60,
            ciLow=max(2.2, value_60 - 1.2),
            ciHigh=value_60 + 1.2,
            modelVersion="cloud-ensemble-v1",
        ),
    ]
