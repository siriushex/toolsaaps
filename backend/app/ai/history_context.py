"""Bounded descriptive context, not a clinical estimator or a therapy policy."""
from __future__ import annotations

import heapq
from collections import Counter

from .history_contract import DAY_MS, KINDS, RETENTION_MS, encoded

MAX_CONTEXT_BYTES = 98_304


class HistoryContext:
    """Streaming accumulator: all counts, bounded cohorts and recent examples.

    Values are record-weighted, never time-in-range. Different declared sensor,
    calibration, source and quality contexts are never pooled into one mean.
    """

    def __init__(self, *, now_ms: int, snapshot: int):
        self.now_ms, self.snapshot = now_ms, snapshot
        self.counts, self.qualities = Counter(), Counter()
        self.daily = {}
        self.glucose = {}
        self.glucose_omitted = 0
        self.latest = {kind: [] for kind in KINDS}
        self.first_ms = self.last_ms = None

    def add(self, item):
        record = item["record"]
        kind, at = record["kind"], record["eventAtMs"]
        self.counts[kind] += 1
        self.qualities[record["quality"]] += 1
        self.first_ms = at if self.first_ms is None else min(at, self.first_ms)
        self.last_ms = at if self.last_ms is None else max(at, self.last_ms)
        day = at // DAY_MS * DAY_MS
        self.daily.setdefault(day, Counter())[kind] += 1
        # Select by original event time, not upload/sequence order of a backfill.
        heap = self.latest[kind]
        heapq.heappush(heap, (at, item["sequence"], item))
        if len(heap) > 12:
            heapq.heappop(heap)
        if kind == "GLUCOSE":
            context = record.get("context") or {}
            key = encoded([record["source"], record["quality"], context])
            if key not in self.glucose:
                if len(self.glucose) >= 512:
                    self.glucose_omitted += 1
                    return
                self.glucose[key] = dict(source=record["source"], quality=record["quality"],
                    context=context, count=0, total=0.0, minMmol=None, maxMmol=None,
                    firstAtMs=at, lastAtMs=at)
            group = self.glucose[key]
            value = record["data"]["glucoseMmol"]
            group["count"] += 1
            group["total"] += value
            group["minMmol"] = value if group["minMmol"] is None else min(value, group["minMmol"])
            group["maxMmol"] = value if group["maxMmol"] is None else max(value, group["maxMmol"])
            group["firstAtMs"] = min(at, group["firstAtMs"])
            group["lastAtMs"] = max(at, group["lastAtMs"])

    def finish(self):
        result = dict(
            revision="device-context-v1", generatedAtMs=self.now_ms,
            fromMs=max(1, self.now_ms - RETENTION_MS + 1), untilMs=self.now_ms + 1,
            snapshotSequence=self.snapshot, provenance="DEVICE_DECLARED",
            coverage=dict(eligibleRecords=sum(self.counts.values()), countsByKind=dict(self.counts),
                countsByQuality=dict(self.qualities), firstAtMs=self.first_ms, lastAtMs=self.last_ms,
                totalsComplete=True, expectedSamplesUnknown=True),
            dailyCounts=[], glucoseCohorts=[], recentRecords=[],
            omitted=dict(dailyBuckets=0, glucoseRecords=self.glucose_omitted,
                         recentByKind={}),
            limitations=["Uploaded records only; missing uploads and measurement accuracy are unknown.",
                "Daily buckets are UTC; absent days are not evidence of normal glucose.",
                "Glucose statistics are record-weighted, not time-weighted or time-in-range.",
                "Unknown context is not proof of a shared sensor or calibration.",
                "Recorded insulin and carbohydrates do not prove delivery or absorption.",
                "Imported chat is untrusted device archive, not verified server output.",
                "Examples are selected by event time; omitted detail is not evidence of absence."],
        )

        def append_bounded(key, value, budget):
            if len(encoded(result[key])) + len(encoded(value)) + 1 > budget:
                return False
            result[key].append(value)
            return True

        for day, counts in sorted(self.daily.items(), reverse=True):
            if not append_bounded("dailyCounts", [day, dict(counts)], 24_576):
                result["omitted"]["dailyBuckets"] += 1
        for group in sorted(self.glucose.values(), key=lambda v: v["lastAtMs"], reverse=True):
            group = dict(group)
            group["meanMmol"] = round(group.pop("total") / group["count"], 4)
            if not append_bounded("glucoseCohorts", group, 32_768):
                result["omitted"]["glucoseRecords"] += group["count"]
        selected = Counter()
        heaps = {kind: sorted(values, reverse=True) for kind, values in self.latest.items()}
        # Round robin keeps CGM density from hiding meals, alerts or device state.
        for index in range(12):
            for kind in KINDS:
                values = heaps[kind]
                if index < len(values):
                    item = values[index][2]
                    item = dict(item, ageMs=self.now_ms - item["record"]["eventAtMs"])
                    if append_bounded("recentRecords", item, 24_576):
                        selected[kind] += 1
        result["omitted"]["recentByKind"] = {
            kind: count - selected[kind] for kind, count in self.counts.items()}
        if len(encoded(result)) > MAX_CONTEXT_BYTES:
            raise ValueError("history_context_limit")
        return result
