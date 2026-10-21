"""Encrypted automatic daily reservations and one-shot handoff, not a model worker."""

from __future__ import annotations

import hashlib
import secrets
import shutil
import time
from contextlib import contextmanager
from dataclasses import dataclass
from datetime import date, datetime, timedelta
from typing import Annotated, Literal
from uuid import uuid4
from zoneinfo import ZoneInfo

from pydantic import Field

from . import checked_daily_review, daily_reports, daily_review, daily_source
from .daily_glucose import Digest, civil_day
from .history_contract import (
    Closed,
    HistoryError,
    RETENTION_MS,
    Time,
    canonical_uuid,
    encoded,
    valid_time,
)

MAX_ENTRY_BYTES = 131_072
MAX_ENTRIES = 512
META = (
    "sequence",
    "queue_id",
    "logical_key",
    "state",
    "created_ms",
    "updated_ms",
    "dispatch_before_ms",
    "expires_ms",
)
SCHEMA = (
    "CREATE TABLE daily_review_queue(sequence INTEGER PRIMARY KEY AUTOINCREMENT,"
    "queue_id TEXT NOT NULL UNIQUE,logical_key TEXT NOT NULL UNIQUE,state TEXT NOT NULL,"
    "created_ms INTEGER NOT NULL,updated_ms INTEGER NOT NULL,dispatch_before_ms INTEGER NOT NULL,"
    "expires_ms INTEGER NOT NULL,nonce BLOB NOT NULL,ciphertext BLOB NOT NULL)",
    "CREATE INDEX daily_queue_expiry ON daily_review_queue(expires_ms)",
)


class FrozenDailyWork(Closed):
    inputJson: Annotated[
        str, Field(min_length=1, max_length=daily_review.MAX_INPUT_BYTES)
    ]
    inputDigest: Digest
    sourceDigest: Digest
    sourceBinding: Digest
    factsDigest: Digest
    promptRevision: Annotated[str, Field(min_length=1, max_length=64)]
    promptDigest: Digest
    expiresAtMs: Time

    @classmethod
    def from_work(cls, work):
        daily_review._packet(work)
        return cls(
            inputJson=work.input_body.decode("utf-8"),
            inputDigest=work.input_digest,
            sourceDigest=work.source_digest,
            sourceBinding=work.source_binding,
            factsDigest=work.facts_digest,
            promptRevision=work.prompt_revision,
            promptDigest=work.prompt_digest,
            expiresAtMs=work.expires_ms,
        )

    def to_work(self):
        return daily_review.DailyReviewWork(
            self.inputJson.encode("utf-8"),
            self.inputDigest,
            self.sourceDigest,
            self.sourceBinding,
            self.factsDigest,
            self.promptRevision,
            self.promptDigest,
            self.expiresAtMs,
        )


class DailyQueueItem(Closed):
    revision: Literal["daily-review-queue-v1"] = "daily-review-queue-v1"
    executionMeaning: Literal["NO_PROVIDER_OR_SLOT_ADMISSION"] = (
        "NO_PROVIDER_OR_SLOT_ADMISSION"
    )
    sequence: daily_reports.Index
    queueId: str
    logicalKey: Digest
    reportDate: str
    zoneId: str
    state: Literal["QUEUED", "HANDOFF_RECORDED", "CANCELLED"]
    createdAtMs: Time
    updatedAtMs: Time
    dispatchBeforeMs: Time
    expiresAtMs: Time
    handoffAtMs: Time | None
    frozenWork: FrozenDailyWork


@dataclass(frozen=True)
class DailyQueueHandoff:
    request_id: str
    work: daily_review.DailyReviewWork
    source_check: daily_source.DailySourceCheck


def _deadline(end):
    if time.monotonic() >= end:
        raise HistoryError(503, "daily_queue_unavailable")


@contextmanager
def _transaction(store, owner, device, *, write=False, migrate=False):
    with store._locked():
        if write and shutil.disk_usage(store.root).free < store.min_free_bytes:
            raise HistoryError(503, "history_storage_unavailable")
        db = store._open(owner, device, create=False)
        try:
            if db is None:
                yield None, None
                return
            if migrate:
                store._enable_daily_queue(db)
            db.execute("BEGIN IMMEDIATE" if write else "BEGIN")
            end = store._daily_budget(db)
            yield db, end
            _deadline(end)
            if write:
                store._check_quota(db)
                _deadline(end)
                db.commit()
                _deadline(end)
        finally:
            if db is not None:
                db.close()


def _bounds(day, zone):
    window = civil_day(day, zone)
    following = (date.fromisoformat(day) + timedelta(days=1)).isoformat()
    return window, civil_day(following, zone).endMs


def _validate_entry(store, owner, device, item):
    canonical_uuid(item.queueId)
    window, dispatch = _bounds(item.reportDate, item.zoneId)
    frozen = item.frozenWork
    envelope = daily_review._decode(
        frozen.to_work().input_body,
        daily_review.DailyReviewEnvelope,
        daily_review.MAX_INPUT_BYTES,
    )
    packet = envelope.input
    if (
        item.logicalKey
        != daily_reports._key(store, owner, device, item.reportDate, item.zoneId)
        or item.logicalKey != packet.reportKey
        or packet.day.window != window
        or packet.sourceBinding != store._identity(owner, device)
        or item.dispatchBeforeMs != dispatch
        or item.expiresAtMs != window.endMs + RETENTION_MS
        or not packet.generatedAtMs
        <= item.createdAtMs
        <= item.updatedAtMs
        < item.expiresAtMs
        or not window.endMs <= item.createdAtMs < dispatch
        or item.expiresAtMs != frozen.expiresAtMs
        or item.expiresAtMs != packet.expiresAtMs
        or (
            frozen.inputDigest,
            frozen.sourceDigest,
            frozen.sourceBinding,
            frozen.factsDigest,
            frozen.promptRevision,
        )
        != (
            envelope.packetDigest,
            packet.sourceDigest,
            packet.sourceBinding,
            packet.factsDigest,
            packet.promptRevision,
        )
        or frozen.inputDigest
        != hashlib.sha256(encoded(packet.model_dump(mode="json"))).hexdigest()
        or packet.resultSchemaDigest
        != hashlib.sha256(encoded(envelope.resultSchema)).hexdigest()
        or item.state == "QUEUED"
        and (item.handoffAtMs is not None or item.updatedAtMs != item.createdAtMs)
        or item.state == "CANCELLED"
        and item.handoffAtMs is not None
        or item.state == "HANDOFF_RECORDED"
        and (
            item.handoffAtMs != item.updatedAtMs
            or not item.createdAtMs <= item.handoffAtMs < dispatch
        )
    ):
        raise ValueError("invalid_daily_queue_entry")


def _metadata(item):
    return dict(
        sequence=item.sequence,
        queue_id=item.queueId,
        logical_key=item.logicalKey,
        state=item.state,
        created_ms=item.createdAtMs,
        updated_ms=item.updatedAtMs,
        dispatch_before_ms=item.dispatchBeforeMs,
        expires_ms=item.expiresAtMs,
    )


def _decrypt(store, owner, device, row):
    if (
        type(row["nonce"]) is not bytes
        or len(row["nonce"]) != 12
        or type(row["ciphertext"]) is not bytes
        or not 16 < len(row["ciphertext"]) <= MAX_ENTRY_BYTES + 16
    ):
        raise ValueError("invalid_daily_queue_ciphertext")
    plain = store._cipher(owner, device).decrypt(
        row["nonce"],
        row["ciphertext"],
        store._aad(owner, device, "daily-review-queue-v1", [row[k] for k in META]),
    )
    item = DailyQueueItem.model_validate_json(plain)
    if any(row[key] != val for key, val in _metadata(item).items()):
        raise ValueError("invalid_daily_queue_metadata")
    _validate_entry(store, owner, device, item)
    return item


def _load(store, owner, device, db, value, *, now_ms, logical=False):
    if db is None or db.execute("PRAGMA user_version").fetchone()[0] < 4:
        return None
    column = "logical_key" if logical else "queue_id"
    row = db.execute(
        "SELECT * FROM daily_review_queue WHERE " + column + "=?", (value,)
    ).fetchone()
    if row is None:
        return None
    item = _decrypt(store, owner, device, row)
    if now_ms < item.updatedAtMs:
        raise HistoryError(409, "daily_queue_clock_regression")
    return None if now_ms >= item.expiresAtMs else item


def _write(store, owner, device, db, item, *, insert=False):
    plain = encoded(item.model_dump(mode="json"))
    if len(plain) > MAX_ENTRY_BYTES:
        raise HistoryError(413, "daily_queue_too_large")
    values = _metadata(item)
    nonce = secrets.token_bytes(12)
    values.update(
        nonce=nonce,
        ciphertext=store._cipher(owner, device).encrypt(
            nonce,
            plain,
            store._aad(
                owner, device, "daily-review-queue-v1", [values[k] for k in META]
            ),
        ),
    )
    if insert:
        db.execute(
            "INSERT INTO daily_review_queue("
            + ",".join(values)
            + ") VALUES("
            + ",".join("?" for _ in values)
            + ")",
            tuple(values.values()),
        )
    else:
        db.execute(
            "UPDATE daily_review_queue SET "
            + ",".join(k + "=?" for k in values)
            + " WHERE queue_id=?",
            (*values.values(), item.queueId),
        )


def get_entry(store, owner, device, queue_id, *, now_ms):
    canonical_uuid(queue_id)
    valid_time(now_ms)
    with _transaction(store, owner, device) as (db, _):
        return _load(store, owner, device, db, queue_id, now_ms=now_ms)


def enqueue(store, owner, device, *, report_date, zone_id, now_ms, language="ru-RU"):
    key = daily_reports._query_key(
        store, owner, device, report_date=report_date, zone_id=zone_id, now_ms=now_ms
    )
    if type(language) is not str or language not in ("ru-RU", "en-US"):
        raise ValueError("invalid_daily_review_language")
    latest = (
        datetime.fromtimestamp(now_ms // 1000, ZoneInfo(zone_id)).date()
        - timedelta(days=1)
    ).isoformat()
    if report_date != latest:
        raise ValueError("daily_queue_latest_completed_day_required")
    with _transaction(store, owner, device) as (db, _):
        old = _load(store, owner, device, db, key, now_ms=now_ms, logical=True)
        if old is not None:
            return old
    prepared = checked_daily_review.prepare_checked_review(
        store,
        owner,
        device,
        report_date=report_date,
        zone_id=zone_id,
        now_ms=now_ms,
        language=language,
    )
    window, dispatch = _bounds(report_date, zone_id)
    with _transaction(store, owner, device, write=True, migrate=True) as (db, _):
        if db is None:
            raise HistoryError(404, "daily_review_report_unavailable")
        store._purge(db, now_ms)
        old = _load(store, owner, device, db, key, now_ms=now_ms, logical=True)
        if old is not None:
            return old
        if (
            db.execute("SELECT count(*) FROM daily_review_queue").fetchone()[0]
            >= MAX_ENTRIES
        ):
            raise HistoryError(429, "daily_queue_capacity_reached")
        row = db.execute(
            "SELECT seq FROM sqlite_sequence WHERE name='daily_review_queue'"
        ).fetchone()
        item = DailyQueueItem(
            sequence=(row[0] if row else 0) + 1,
            queueId=str(uuid4()),
            logicalKey=key,
            reportDate=report_date,
            zoneId=zone_id,
            state="QUEUED",
            createdAtMs=now_ms,
            updatedAtMs=now_ms,
            dispatchBeforeMs=dispatch,
            expiresAtMs=window.endMs + RETENTION_MS,
            handoffAtMs=None,
            frozenWork=FrozenDailyWork.from_work(prepared.work),
        )
        _validate_entry(store, owner, device, item)
        _write(store, owner, device, db, item, insert=True)
        return item


def cancel(store, owner, device, queue_id, *, now_ms):
    canonical_uuid(queue_id)
    valid_time(now_ms)
    with _transaction(store, owner, device, write=True) as (db, _):
        item = _load(store, owner, device, db, queue_id, now_ms=now_ms)
        if item is None or item.state == "CANCELLED":
            return item
        if item.state == "HANDOFF_RECORDED":
            raise HistoryError(409, "daily_queue_handoff_recorded")
        item = item.model_copy(update=dict(state="CANCELLED", updatedAtMs=now_ms))
        _write(store, owner, device, db, item)
        return item


def handoff(store, owner, device, queue_id, *, now_ms):
    canonical_uuid(queue_id)
    valid_time(now_ms)
    with _transaction(store, owner, device, write=True) as (db, end):
        item = _load(store, owner, device, db, queue_id, now_ms=now_ms)
        if item is None or item.state != "QUEUED":
            return None
        if now_ms >= item.dispatchBeforeMs:
            raise HistoryError(409, "daily_queue_dispatch_expired")
        work = item.frozenWork.to_work()
        try:
            packet = daily_review._packet(work)
        except ValueError:
            raise HistoryError(409, "daily_queue_work_policy_changed") from None
        key = daily_reports._key(store, owner, device, item.reportDate, item.zoneId)
        report = daily_reports._read_report(
            store, owner, device, db, key=key, now_ms=now_ms, report_revision=None
        )
        source = (
            None
            if report is None
            else daily_source._check_selected_source(
                store,
                owner,
                device,
                db,
                report,
                now_ms=now_ms,
                snapshot=store._highwater(db),
                stop_at=end,
            )
        )
        checked_daily_review._bind_work_to_report(
            report, source, work, packet, now_ms=now_ms
        )
        _deadline(end)
        updated = item.model_copy(
            update=dict(
                state="HANDOFF_RECORDED", updatedAtMs=now_ms, handoffAtMs=now_ms
            )
        )
        _write(store, owner, device, db, updated)
        return DailyQueueHandoff(item.queueId, work, source)
