"""Bounded lossless event tables; authenticated version selection stays external."""

from copy import deepcopy

from pydantic import ValidationError

from .compact_context import MAX_SECTION_BYTES
from .history_contract import HistoryRecord, encoded, valid_time

MAX_EVENT_BYTES = MAX_SECTION_BYTES
COLUMNS = [
    "recordId",
    "revision",
    "eventOffsetMs",
    "observedLagMs",
    "receivedLagMs",
    "sequence",
    "cohort",
]
LIMITATIONS = [
    "SELECTED_UPLOADED_EVENTS_ONLY",
    "RECORDED_NOT_CONFIRMED_DELIVERY",
    "MEAL_AND_CARBS_MAY_OVERLAP_DO_NOT_SUM",
    "ESTIMATED_GI_AND_APPROXIMATE_GRAMS_REMAIN_ESTIMATES",
    "FOCUS_RECORDS_ARE_OVERLAPPING_VIEWS_NOT_ADDITIONAL_EVENTS",
]


class MealTherapyEvents:
    def __init__(self, from_ms, until_ms):
        valid_time(from_ms)
        valid_time(until_ms)
        if from_ms >= until_ms:
            raise ValueError("compact_context_invalid_input")
        self.from_ms, self.until_ms = from_ms, until_ms
        self._cohorts, self._tables, self._ids = {}, {}, set()
        self._count = 0
        self._failed = None
        self._bytes = len(encoded(self._envelope([], [])))

    def _envelope(self, cohorts, tables):
        return dict(
            revision="compact-meal-therapy-v1",
            fromMs=self.from_ms,
            untilMs=self.until_ms,
            timeUnit="ms",
            columns=list(COLUMNS),
            cohorts=cohorts,
            tables=tables,
            recordCount=self._count,
            omittedRecords=0,
            limitations=list(LIMITATIONS),
        )

    def _check(self):
        if self._failed:
            raise ValueError(self._failed)

    def _charge(self, value):
        self._bytes += len(encoded(value)) + 1
        if self._bytes > MAX_EVENT_BYTES:
            self._failed = "compact_context_budget_exceeded"
            self._check()

    def add(self, record, *, sequence, received_ms):
        self._check()
        try:
            valid_time(received_ms)
            if type(record) is not HistoryRecord:
                raise ValueError()
            row = HistoryRecord.model_validate(record.model_dump())
            if (
                row.kind not in ("MEAL", "THERAPY")
                or row.deleted
                or not self.from_ms <= row.eventAtMs < self.until_ms
                or received_ms < row.observedAtMs
                or type(sequence) is not int
                or not 0 < sequence < 2**63
                or row.recordId in self._ids
            ):
                raise ValueError()
        except (ValueError, TypeError, AttributeError, ValidationError):
            self._failed = "compact_context_invalid_input"
            self._check()
        cohort = dict(
            source=row.source,
            quality=row.quality,
            context=row.context.model_dump() if row.context else None,
        )
        key = encoded(cohort)
        if key not in self._cohorts:
            self._charge(cohort)
            self._cohorts[key] = (len(self._cohorts), cohort)
        fields = tuple(sorted(row.data))
        table_key = row.kind, fields
        if table_key not in self._tables:
            self._charge(dict(kind=row.kind, fields=list(fields), rows=[]))
            self._tables[table_key] = []
        values = [
            row.recordId,
            row.revision,
            row.eventAtMs - self.from_ms,
            row.observedAtMs - row.eventAtMs,
            received_ms - row.eventAtMs,
            sequence,
            self._cohorts[key][0],
            *(row.data[f] for f in fields),
        ]
        self._charge(values)
        self._tables[table_key].append(values)
        self._ids.add(row.recordId)
        self._count += 1

    def finish(self):
        self._check()
        ordered = sorted(self._cohorts)
        remap = {self._cohorts[key][0]: i for i, key in enumerate(ordered)}
        tables = []
        for (kind, fields), rows in sorted(self._tables.items()):
            frozen = []
            for row in sorted(rows, key=lambda r: (r[2], r[0])):
                copy = list(row)
                copy[6] = remap[copy[6]]
                frozen.append(copy)
            tables.append(dict(kind=kind, fields=list(fields), rows=frozen))
        result = self._envelope(
            deepcopy([self._cohorts[key][1] for key in ordered]), tables
        )
        if len(encoded(result)) > MAX_EVENT_BYTES:
            self._failed = "compact_context_budget_exceeded"
            self._check()
        return result
