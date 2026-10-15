"""Closed observational history contracts; no command or inference authority."""

from __future__ import annotations

import json
from typing import Annotated, Literal
from uuid import UUID
from zoneinfo import ZoneInfo, ZoneInfoNotFoundError

from pydantic import BaseModel, ConfigDict, Field, ValidationError, model_validator

DAY_MS = 86_400_000
RETENTION_MS = 365 * DAY_MS
MAX_BODY_BYTES = 131_072
MAX_RECORD_BYTES = 16_384
MAX_RESPONSE_BYTES = 65_536
MAX_TIME = 2**63 - RETENTION_MS - 1
KINDS = (
    "GLUCOSE",
    "THERAPY",
    "MEAL",
    "FORECAST",
    "SENSITIVITY",
    "ACTIVITY",
    "DEVICE",
    "ALERT",
    "DIAGNOSTIC",
    "CHAT",
)
Time = Annotated[int, Field(gt=0, le=MAX_TIME)]
Positive = Annotated[float, Field(gt=0, le=1_000_000, allow_inf_nan=False)]
Nonnegative = Annotated[float, Field(ge=0, le=1_000_000, allow_inf_nan=False)]
Signed = Annotated[float, Field(ge=-1_000_000, le=1_000_000, allow_inf_nan=False)]
Code = Annotated[str, Field(pattern=r"^[A-Za-z0-9][A-Za-z0-9_.:-]{0,63}$")]


class HistoryError(Exception):
    def __init__(self, status: int, code: str):
        self.status, self.code = status, code
        super().__init__(code)


def canonical_uuid(value):
    if type(value) is not str or str(UUID(value)) != value:
        raise ValueError("canonical_uuid_required")
    return value


def valid_time(value):
    if type(value) is not int or not 0 < value <= MAX_TIME:
        raise ValueError("invalid_time")


class Closed(BaseModel):
    model_config = ConfigDict(
        extra="forbid", strict=True, frozen=True, allow_inf_nan=False
    )


class Glucose(Closed):
    glucoseMmol: Positive


class Therapy(Closed):
    eventType: Literal[
        "CARBS",
        "BOLUS",
        "BASAL",
        "TEMP_TARGET",
        "CANCEL_TARGET",
        "SENSOR_CHANGE",
        "SET_CHANGE",
        "DELIVERY_STATE",
    ]
    carbsGrams: Nonnegative | None = None
    insulinUnits: Nonnegative | None = None
    basalUnitsPerHour: Nonnegative | None = None
    targetMmol: Positive | None = None
    durationMinutes: Nonnegative | None = None
    deliveryState: Literal["CONFIRMED", "UNKNOWN", "SUSPECT", "STOPPED"] | None = None

    @model_validator(mode="after")
    def matching_fields(self):
        allowed = {
            "CARBS": {"carbsGrams"},
            "BOLUS": {"insulinUnits"},
            "BASAL": {"basalUnitsPerHour", "durationMinutes"},
            "TEMP_TARGET": {"targetMmol", "durationMinutes"},
            "DELIVERY_STATE": {"deliveryState"},
            "CANCEL_TARGET": set(),
            "SENSOR_CHANGE": set(),
            "SET_CHANGE": set(),
        }[self.eventType]
        present = self.model_fields_set - {"eventType"}
        if present != allowed or any(getattr(self, field) is None for field in present):
            raise ValueError("incomplete_or_mixed_therapy")
        return self


class Meal(Closed):
    carbsGrams: Nonnegative
    foodType: Literal["FAST", "MIXED", "FAT_PROTEIN"]
    quantitySource: Literal["WEIGHED", "LABEL", "RECIPE", "APPROXIMATE", "UNKNOWN"]
    glycemicIndex: Annotated[float, Field(ge=0, le=200, allow_inf_nan=False)] | None = (
        None
    )
    giSource: Literal["USER", "CATALOG", "MODEL_ESTIMATE"] | None = None

    @model_validator(mode="after")
    def gi_provenance(self):
        if (self.glycemicIndex is None) != (self.giSource is None):
            raise ValueError("gi_provenance_required")
        return self


class Forecast(Closed):
    horizonMinutes: Literal[5, 30, 60]
    valueMmol: Positive
    ciLowMmol: Positive | None = None
    ciHighMmol: Positive | None = None
    modelVersion: Code

    @model_validator(mode="after")
    def interval(self):
        if (self.ciLowMmol is None) != (self.ciHighMmol is None):
            raise ValueError("incomplete_interval")
        if (
            self.ciLowMmol is not None
            and not self.ciLowMmol <= self.valueMmol <= self.ciHighMmol
        ):
            raise ValueError("invalid_interval")
        return self


class Nonempty(Closed):
    @model_validator(mode="after")
    def nonempty(self):
        if not any(value is not None for value in self.model_dump().values()):
            raise ValueError("empty_context")
        return self


class Sensitivity(Nonempty):
    isfMmolPerUnit: Positive | None = None
    crGramsPerUnit: Positive | None = None
    positiveIobUnits: Nonnegative | None = None
    netIobUnits: Signed | None = None
    cobGrams: Nonnegative | None = None


class Activity(Nonempty):
    steps: Annotated[int, Field(ge=0, le=1_000_000)] | None = None
    activeMinutes: Nonnegative | None = None
    activeKcal: Nonnegative | None = None


class Device(Nonempty):
    sensorAgeHours: Nonnegative | None = None
    setAgeHours: Nonnegative | None = None
    deliveryState: Literal["CONFIRMED", "UNKNOWN", "SUSPECT", "STOPPED"] | None = None


class Alert(Closed):
    code: Code
    severity: Literal["INFO", "WARNING", "CRITICAL"]
    deliveryStatus: Literal["CONFIRMED", "UNKNOWN", "FAILED", "SUPPRESSED"]


class Diagnostic(Closed):
    code: Code
    status: Literal["OK", "UNKNOWN", "FAILED", "BLOCKED"]


class Chat(Closed):
    conversationId: str
    role: Literal["USER", "ASSISTANT"]
    text: Annotated[str, Field(min_length=1, max_length=8000)]
    origin: Literal["DEVICE_ARCHIVE"]

    @model_validator(mode="after")
    def identity(self):
        canonical_uuid(self.conversationId)
        return self


PAYLOADS = dict(
    zip(
        KINDS,
        (
            Glucose,
            Therapy,
            Meal,
            Forecast,
            Sensitivity,
            Activity,
            Device,
            Alert,
            Diagnostic,
            Chat,
        ),
    )
)


def encoded(value):
    return json.dumps(
        value,
        ensure_ascii=False,
        allow_nan=False,
        sort_keys=True,
        separators=(",", ":"),
    ).encode("utf-8")


class HistoricalContext(Nonempty):
    zoneId: Annotated[str, Field(min_length=1, max_length=64)] | None = None
    sensorSessionId: Code | None = None
    calibrationModelId: Code | None = None
    glucoseBasis: Literal["RAW", "RESOLVED", "UNKNOWN"] | None = None
    inputModelVersion: Code | None = None

    @model_validator(mode="after")
    def known_zone(self):
        if self.zoneId is not None:
            try:
                ZoneInfo(self.zoneId)
            except (ValueError, ZoneInfoNotFoundError):
                raise ValueError("invalid_historical_zone") from None
        return self


class HistoryRecord(Closed):
    recordId: str
    revision: Annotated[int, Field(ge=1, le=2**31 - 1)]
    eventAtMs: Time
    observedAtMs: Time
    kind: Literal[
        "GLUCOSE",
        "THERAPY",
        "MEAL",
        "FORECAST",
        "SENSITIVITY",
        "ACTIVITY",
        "DEVICE",
        "ALERT",
        "DIAGNOSTIC",
        "CHAT",
    ]
    source: Literal["CGM", "AAPS", "COPILOT", "USER", "SERVER"]
    quality: Literal["OK", "UNKNOWN", "SUSPECT"]
    deleted: bool
    data: dict | None
    context: HistoricalContext | None = None

    @model_validator(mode="after")
    def payload(self):
        canonical_uuid(self.recordId)
        if self.eventAtMs > self.observedAtMs:
            raise ValueError("invalid_observation_time")
        if self.deleted:
            if self.data is not None:
                raise ValueError("tombstone_payload")
        else:
            validated = PAYLOADS[self.kind].model_validate(self.data)
            object.__setattr__(self, "data", validated.model_dump(exclude_unset=True))
        if len(encoded(self.model_dump())) > MAX_RECORD_BYTES:
            raise ValueError("record_limit")
        return self


class HistoryBatch(Closed):
    batchId: str
    records: Annotated[list[HistoryRecord], Field(min_length=1, max_length=32)]

    @model_validator(mode="after")
    def identity(self):
        canonical_uuid(self.batchId)
        ids = [item.recordId for item in self.records]
        if len(ids) != len(set(ids)):
            raise ValueError("duplicate_record_in_batch")
        return self


class HistoryQuery(Closed):
    fromMs: Time
    untilMs: Time
    kinds: Annotated[list[str], Field(max_length=len(KINDS))]
    afterSequence: Annotated[int, Field(ge=0, le=2**63 - 1)]
    snapshotSequence: Annotated[int, Field(ge=0, le=2**63 - 1)] | None
    limit: Annotated[int, Field(ge=1, le=32)]

    @model_validator(mode="after")
    def window(self):
        if (
            self.fromMs >= self.untilMs
            or len(set(self.kinds)) != len(self.kinds)
            or set(self.kinds) - set(KINDS)
            or self.snapshotSequence is not None
            and self.afterSequence > self.snapshotSequence
        ):
            raise ValueError("invalid_window")
        return self


def _unique(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError("duplicate_key")
        result[key] = value
    return result


def _decode(body, model):
    if type(body) is not bytes or not 1 <= len(body) <= MAX_BODY_BYTES:
        raise ValueError("body_limit")
    value = json.loads(
        body.decode("utf-8"),
        object_pairs_hook=_unique,
        parse_constant=lambda _: (_ for _ in ()).throw(ValueError()),
    )
    return model.model_validate(value)


def decode_batch(body: bytes, *, now_ms: int) -> HistoryBatch:
    try:
        valid_time(now_ms)
        batch = _decode(body, HistoryBatch)
        if any(
            not now_ms - RETENTION_MS < row.eventAtMs <= row.observedAtMs <= now_ms
            for row in batch.records
        ):
            raise ValueError("outside_retention")
        return batch
    except (ValueError, TypeError, UnicodeError, RecursionError, ValidationError):
        raise HistoryError(400, "invalid_history_request") from None


def decode_query(body: bytes, *, now_ms: int) -> HistoryQuery:
    try:
        valid_time(now_ms)
        query = _decode(body, HistoryQuery)
        if query.fromMs < max(1, now_ms - RETENTION_MS) or query.untilMs > now_ms + 1:
            raise ValueError("outside_retention")
        return query
    except (ValueError, TypeError, UnicodeError, RecursionError, ValidationError):
        raise HistoryError(400, "invalid_history_request") from None
