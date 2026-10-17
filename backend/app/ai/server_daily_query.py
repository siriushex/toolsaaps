"""Bounded same-device projections of retained, unreviewed daily results."""

from __future__ import annotations

from typing import Annotated

from pydantic import Field, model_validator

from . import daily_results
from .history_contract import Closed, HistoryError, _decode, canonical_uuid, encoded, valid_time

REVISION = "daily-review-read-v1"
MAX_QUERY_BYTES = 1024
MAX_RESPONSE_BYTES = 131_072
LIST_FIELDS = {"afterSequence", "snapshotSequence"}
DETAIL_FIELDS = {"jobId"}
Cursor = Annotated[int, Field(ge=0, lt=2**63 - 1)]


class DailyResultsQuery(Closed):
    afterSequence: Cursor
    snapshotSequence: Cursor | None

    @model_validator(mode="after")
    def bounds(self):
        if self.snapshotSequence is not None and self.afterSequence > self.snapshotSequence:
            raise ValueError("invalid_cursor")
        return self


class DailyResultQuery(Closed):
    jobId: Annotated[str, Field(min_length=36, max_length=36)]

    @model_validator(mode="after")
    def identity(self):
        canonical_uuid(self.jobId)
        return self


def _query(body, model, now_ms):
    try:
        valid_time(now_ms)
        if type(body) is not bytes or not 0 < len(body) <= MAX_QUERY_BYTES:
            raise ValueError("body_limit")
        return _decode(body, model)
    except (ValueError, TypeError, UnicodeError, RecursionError):
        raise HistoryError(400, "invalid_daily_read_request") from None


def _bounded(value):
    if len(encoded(value)) > MAX_RESPONSE_BYTES:
        raise HistoryError(503, "daily_review_read_unavailable")
    return value


def read_results(store, owner, device, body, *, now_ms):
    query = _query(body, DailyResultsQuery, now_ms)
    page = daily_results.list_results(store, owner, device, now_ms=now_ms,
        after_sequence=query.afterSequence, snapshot_sequence=query.snapshotSequence)
    return _bounded({"revision": REVISION} | page.model_dump(mode="json"))


def read_result(store, owner, device, body, *, now_ms):
    query = _query(body, DailyResultQuery, now_ms)
    selected = daily_results.get_result(store, owner, device, query.jobId, now_ms=now_ms)
    if selected is None:
        raise HistoryError(404, "daily_review_not_found")
    record = selected.record
    # Deliberately exclude frozen input, execution claims and request metadata.
    return _bounded(dict(revision=REVISION, jobId=record.jobId,
        reportDate=record.queue.reportDate, zoneId=record.queue.zoneId,
        reportRevision=record.sourceAtReply.reportRevision, model=record.model,
        storedAtMs=record.storedAtMs, expiresAtMs=record.expiresAtMs,
        currentState=selected.currentState,
        sourceCheck=selected.sourceCheck.model_dump(mode="json") if selected.sourceCheck else None,
        sourceAtReply=record.sourceAtReply.model_dump(mode="json"),
        review=record.review.model_dump(mode="json")))
