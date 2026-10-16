"""Internal stopped-result archive, not publication or provider admission."""

from __future__ import annotations

import asyncio
import secrets
import shutil
import time
from dataclasses import dataclass, replace
from typing import Annotated, Literal

from pydantic import Field

from . import (
    checked_daily_review,
    daily_queue,
    daily_reports,
    daily_review,
    daily_source,
)
from .daily_glucose import Count, Digest
from .daily_worker import DailyReviewWorker, ROUTE_REVISION, daily_request_digest
from .history_contract import (
    Closed,
    HistoryError,
    Time,
    canonical_uuid,
    encoded,
    valid_time,
)
from .job_executor import JobExecutor, _complete_critical
from .job_ledger import Job

MAX_ENTRY_BYTES = 131_072
MAX_ENTRIES = 512
MAX_PAGE_ENTRIES = 20
PURPOSE = "daily-review-result-v1"
META = (
    "sequence",
    "job_id",
    "queue_id",
    "report_key",
    "stored_ms",
    "expires_ms",
    "request_digest",
)
SCHEMA = (
    "CREATE TABLE daily_review_results(sequence INTEGER PRIMARY KEY AUTOINCREMENT,"
    "job_id TEXT NOT NULL UNIQUE,queue_id TEXT NOT NULL UNIQUE,report_key TEXT NOT NULL,"
    "stored_ms INTEGER NOT NULL,expires_ms INTEGER NOT NULL,request_digest TEXT NOT NULL,"
    "nonce BLOB NOT NULL,ciphertext BLOB NOT NULL)",
    "CREATE INDEX daily_result_expiry ON daily_review_results(expires_ms)",
)


class DailyResultRecord(Closed):
    revision: Literal["daily-review-result-v1"] = PURPOSE
    currentSourceCheck: Literal["NOT_CHECKED"] = "NOT_CHECKED"
    executionState: Literal["SUCCEEDED_AFTER_CONFIRMED_STOP"] = (
        "SUCCEEDED_AFTER_CONFIRMED_STOP"
    )
    sequence: daily_reports.Index
    jobId: str
    requestDigest: Digest
    routeRevision: Literal["advisory-daily-worker-v1"] = ROUTE_REVISION
    model: Annotated[str, Field(min_length=1, max_length=128)]
    jobCreatedAtMs: Time
    startedAtMs: Time
    finishedAtMs: Time
    deadlineMs: Time
    storedAtMs: Time
    expiresAtMs: Time
    queue: daily_queue.DailyQueueItem
    sourceAtReply: daily_source.DailySourceCheck
    review: daily_review.GroundedDailyReview


class DailyResultView(Closed):
    record: DailyResultRecord
    currentState: Literal[
        "SOURCE_MATCHED", "SOURCE_CHANGED", "SOURCE_UNAVAILABLE", "SUPERSEDED"
    ]
    sourceCheck: daily_source.DailySourceCheck | None


class DailyResultSummary(Closed):
    sequence: daily_reports.Index
    jobId: str
    requestId: str
    reportDate: str
    zoneId: str
    model: str
    storedAtMs: Time
    expiresAtMs: Time
    summary: str
    sourceCheck: Literal["NOT_CHECKED"] = "NOT_CHECKED"
    publicationState: Literal["NOT_ADMITTED"] = "NOT_ADMITTED"


class DailyResultPage(Closed):
    membershipMeaning: Literal["RETAINED_ARCHIVE_ONLY"] = "RETAINED_ARCHIVE_ONLY"
    snapshotSequence: Count
    records: tuple[DailyResultSummary, ...]
    hasMore: bool
    nextAfterSequence: Count | None


@dataclass(frozen=True)
class DailyArchiveExecution:
    job: Job | None
    archive_state: Literal["STORED", "RESULT_UNAVAILABLE", "NO_SUCCESS"]
    record: DailyResultRecord | None = None


def _packet(record):
    envelope = daily_review._decode(
        record.queue.frozenWork.to_work().input_body,
        daily_review.DailyReviewEnvelope,
        daily_review.MAX_INPUT_BYTES,
    )
    return envelope.input


def _reply(review):
    return daily_review.DailyReviewResult.model_validate_json(
        encoded(
            dict(
                revision="daily-review-v1",
                factsDigest=review.factsDigest,
                packetDigest=review.packetDigest,
                status=review.status,
                findings=[
                    dict(
                        candidateId=f.pattern.candidateId,
                        action=f.action,
                        alternativeCodes=f.alternativeCodes,
                        interpretation=f.interpretation,
                    )
                    for f in review.findings
                ],
                missingInputCodes=review.missingInputCodes,
                summary=review.summary,
            )
        )
    )


def _validate_record(store, owner, device, record):
    canonical_uuid(record.jobId)
    daily_queue._validate_entry(store, owner, device, record.queue)
    packet, source, queue = _packet(record), record.sourceAtReply, record.queue
    if (
        queue.state != "HANDOFF_RECORDED"
        or queue.handoffAtMs is None
        or not queue.handoffAtMs
        <= record.jobCreatedAtMs
        <= record.startedAtMs
        <= source.checkedAtMs
        <= record.finishedAtMs
        <= record.storedAtMs
        < record.expiresAtMs
        or not record.finishedAtMs < record.deadlineMs <= queue.dispatchBeforeMs
        or record.deadlineMs - record.jobCreatedAtMs > 900_000
        or record.expiresAtMs != queue.expiresAtMs
        or source.status != "MATCHED"
        or source.reason != "SOURCE_MATCHED"
        or (
            source.reportKey,
            source.reportRevision,
            source.factsDigest,
            source.sourceBinding,
            source.sourceDigest,
            source.originalAsOfMs,
            source.originalSnapshotSequence,
        )
        != (
            packet.reportKey,
            packet.reportRevision,
            packet.factsDigest,
            packet.sourceBinding,
            packet.sourceDigest,
            packet.day.asOfMs,
            packet.day.snapshotSequence,
        )
        or source.replayedSourceDigest != packet.sourceDigest
        or source.replayedFactsDigest != packet.factsDigest
        or daily_review._ground_result(
            packet, queue.frozenWork.inputDigest, _reply(record.review)
        )
        != record.review
    ):
        raise ValueError("invalid_daily_result_entry")


def _metadata(record):
    return dict(
        sequence=record.sequence,
        job_id=record.jobId,
        queue_id=record.queue.queueId,
        report_key=record.queue.logicalKey,
        stored_ms=record.storedAtMs,
        expires_ms=record.expiresAtMs,
        request_digest=record.requestDigest,
    )


def _decrypt(store, owner, device, row):
    if (
        type(row["nonce"]) is not bytes
        or len(row["nonce"]) != 12
        or type(row["ciphertext"]) is not bytes
        or not 16 < len(row["ciphertext"]) <= MAX_ENTRY_BYTES + 16
    ):
        raise ValueError("invalid_daily_result_ciphertext")
    plain = store._cipher(owner, device).decrypt(
        row["nonce"],
        row["ciphertext"],
        store._aad(owner, device, PURPOSE, [row[key] for key in META]),
    )
    record = DailyResultRecord.model_validate_json(plain)
    if any(row[key] != val for key, val in _metadata(record).items()):
        raise ValueError("invalid_daily_result_metadata")
    _validate_record(store, owner, device, record)
    return record


def _highwater(db):
    row = db.execute(
        "SELECT seq FROM sqlite_sequence WHERE name='daily_review_results'"
    ).fetchone()
    high = row[0] if row else 0
    maximum = db.execute(
        "SELECT coalesce(max(sequence),0) FROM daily_review_results"
    ).fetchone()[0]
    if type(high) is not int or not 0 <= maximum <= high < 2**63 - 1:
        raise ValueError("invalid_daily_result_sequence")
    return high


def _deadline(end):
    if time.monotonic() >= end:
        raise HistoryError(503, "daily_result_unavailable")


def _load(store, owner, device, db, job_id, now_ms):
    if db is None or db.execute("PRAGMA user_version").fetchone()[0] < 5:
        return None
    _highwater(db)
    row = db.execute(
        "SELECT * FROM daily_review_results WHERE job_id=?", (job_id,)
    ).fetchone()
    if row is None:
        return None
    record = _decrypt(store, owner, device, row)
    if now_ms < record.storedAtMs:
        raise HistoryError(409, "daily_result_clock_regression")
    return record if now_ms < record.expiresAtMs else None


def _get_archived(store, owner, device, job_id, *, now_ms):
    canonical_uuid(job_id)
    valid_time(now_ms)
    store._identity(owner, device)
    with store._daily_read_snapshot(owner, device) as (db, _, end):
        record = _load(store, owner, device, db, job_id, now_ms)
        _deadline(end)
        return record


def get_result(store, owner, device, job_id, *, now_ms):
    """Selected audit read: source proof is point-in-time, never medical approval."""
    canonical_uuid(job_id)
    valid_time(now_ms)
    store._identity(owner, device)
    with store._daily_read_snapshot(owner, device) as (db, snapshot, end):
        record = _load(store, owner, device, db, job_id, now_ms)
        if record is None:
            return None
        packet = _packet(record)
        report = daily_reports._read_report(
            store,
            owner,
            device,
            db,
            key=packet.reportKey,
            now_ms=now_ms,
            report_revision=None,
        )
        source, state = None, "SOURCE_UNAVAILABLE"
        if report is not None:
            if (
                report.reportRevision != packet.reportRevision
                or report.factsDigest != packet.factsDigest
            ):
                state = "SUPERSEDED"
            else:
                source = daily_source._check_selected_source(
                    store,
                    owner,
                    device,
                    db,
                    report,
                    now_ms=now_ms,
                    snapshot=snapshot,
                    stop_at=end,
                )
                state = {
                    "MATCHED": "SOURCE_MATCHED",
                    "CHANGED": "SOURCE_CHANGED",
                    "UNAVAILABLE": "SOURCE_UNAVAILABLE",
                }[source.status]
        _deadline(end)
        return DailyResultView(record=record, currentState=state, sourceCheck=source)


def list_results(
    store, owner, device, *, now_ms, after_sequence=0, snapshot_sequence=None
):
    valid_time(now_ms)
    store._identity(owner, device)
    if (
        type(after_sequence) is not int
        or not 0 <= after_sequence < 2**63 - 1
        or snapshot_sequence is not None
        and (
            type(snapshot_sequence) is not int
            or not after_sequence <= snapshot_sequence < 2**63 - 1
        )
    ):
        raise ValueError("invalid_daily_result_cursor")
    with store._daily_read_snapshot(owner, device) as (db, _, end):
        high = (
            0
            if db is None or db.execute("PRAGMA user_version").fetchone()[0] < 5
            else _highwater(db)
        )
        bound = high if snapshot_sequence is None else snapshot_sequence
        if not after_sequence <= bound <= high:
            raise HistoryError(409, "daily_result_snapshot_unavailable")
        rows = (
            []
            if not high
            else db.execute(
                "SELECT * FROM daily_review_results WHERE sequence>? AND sequence<=? "
                "AND expires_ms>? ORDER BY sequence LIMIT ?",
                (after_sequence, bound, now_ms, MAX_PAGE_ENTRIES + 1),
            ).fetchall()
        )
        records = []
        for row in rows:
            record = _decrypt(store, owner, device, row)
            if now_ms < record.storedAtMs:
                raise HistoryError(409, "daily_result_clock_regression")
            records.append(
                DailyResultSummary(
                    sequence=record.sequence,
                    jobId=record.jobId,
                    requestId=record.queue.queueId,
                    reportDate=record.queue.reportDate,
                    zoneId=record.queue.zoneId,
                    model=record.model,
                    storedAtMs=record.storedAtMs,
                    expiresAtMs=record.expiresAtMs,
                    summary=record.review.summary,
                )
            )
        more = len(records) > MAX_PAGE_ENTRIES
        selected = tuple(records[:MAX_PAGE_ENTRIES])
        _deadline(end)
        return DailyResultPage(
            snapshotSequence=bound,
            records=selected,
            hasMore=more,
            nextAfterSequence=selected[-1].sequence if more else None,
        )


def _save_success(executor, worker, job, checked, *, now_ms):
    """Private sink used only by execute_and_archive, never a caller receipt API."""
    valid_time(now_ms)
    if (
        type(job) is not Job
        or type(checked) is not checked_daily_review.SourceCheckedDailyReview
        or job.state != "SUCCEEDED"
        or job.kind != "DAILY_REVIEW"
        or job.route_revision != ROUTE_REVISION
        or replace(job, state="QUEUED", started_ms=None, finished_ms=None)
        != replace(worker.job, state="QUEUED", started_ms=None, finished_ms=None)
        or executor.ledger.get(job.owner_id, job.id) != job
        or job.digest
        != daily_request_digest(
            worker.work,
            request_id=job.request_id,
            model=worker.policy.model,
            deadline_ms=job.deadline_ms,
        )
    ):
        raise ValueError("invalid_daily_result_execution")
    store, owner, device = worker.history, job.owner_id, worker.device
    with store._locked():
        if shutil.disk_usage(store.root).free < store.min_free_bytes:
            raise HistoryError(503, "history_storage_unavailable")
        db = store._open(owner, device, create=False)
        if db is None:
            raise HistoryError(409, "daily_result_handoff_unavailable")
        try:
            store._enable_daily_results(db)
            db.execute("BEGIN IMMEDIATE")
            end = store._daily_budget(db)
            existing = _load(store, owner, device, db, job.id, now_ms)
            if existing is not None:
                if (
                    existing.requestDigest != job.digest
                    or existing.queue.frozenWork.to_work() != worker.work
                    or existing.review != checked.review
                    or existing.sourceAtReply != checked.source_check
                ):
                    raise HistoryError(409, "daily_result_conflict")
                _deadline(end)
                return existing
            queue = daily_queue._load(
                store, owner, device, db, job.request_id, now_ms=now_ms
            )
            if (
                queue is None
                or queue.state != "HANDOFF_RECORDED"
                or queue.frozenWork.to_work() != worker.work
            ):
                raise HistoryError(409, "daily_result_handoff_unavailable")
            packet = daily_review._packet(worker.work)
            report = daily_reports._read_report(
                store,
                owner,
                device,
                db,
                key=packet.reportKey,
                now_ms=now_ms,
                report_revision=packet.reportRevision,
            )
            if (
                report is None
                or checked_daily_review._prepare_input(
                    report.model_copy(update=dict(sourceCheck="MATCHED")),
                    now_ms=now_ms,
                    language=packet.language,
                )
                != worker.work
            ):
                raise HistoryError(409, "daily_result_archive_mismatch")
            store._purge(db, now_ms)
            if (
                db.execute("SELECT count(*) FROM daily_review_results").fetchone()[0]
                >= MAX_ENTRIES
            ):
                raise HistoryError(429, "daily_result_capacity_reached")
            record = DailyResultRecord(
                sequence=_highwater(db) + 1,
                jobId=job.id,
                requestDigest=job.digest,
                model=worker.policy.model,
                jobCreatedAtMs=job.created_ms,
                startedAtMs=job.started_ms,
                finishedAtMs=job.finished_ms,
                deadlineMs=job.deadline_ms,
                storedAtMs=now_ms,
                expiresAtMs=queue.expiresAtMs,
                queue=queue,
                sourceAtReply=checked.source_check,
                review=checked.review,
            )
            try:
                _validate_record(store, owner, device, record)
            except ValueError:
                raise HistoryError(422, "invalid_daily_result_entry") from None
            plain = encoded(record.model_dump(mode="json"))
            if len(plain) > MAX_ENTRY_BYTES:
                raise HistoryError(413, "daily_result_too_large")
            values, nonce = _metadata(record), secrets.token_bytes(12)
            values.update(
                nonce=nonce,
                ciphertext=store._cipher(owner, device).encrypt(
                    nonce,
                    plain,
                    store._aad(owner, device, PURPOSE, [values[key] for key in META]),
                ),
            )
            db.execute(
                "INSERT INTO daily_review_results("
                + ",".join(values)
                + ") VALUES("
                + ",".join("?" for _ in values)
                + ")",
                tuple(values.values()),
            )
            store._check_quota(db)
            _deadline(end)
            db.commit()
            _deadline(end)
            return record
        finally:
            db.close()


async def execute_and_archive(executor, worker):
    """One trusted composition; success and archival are separate durable facts."""
    if type(executor) is not JobExecutor or type(worker) is not DailyReviewWorker:
        raise ValueError("daily_archive_executor_required")
    outcome = await executor.execute(worker.job.owner_id, worker.job.id, worker)
    return await _archive_execution(executor, worker, outcome)


async def _archive_execution(executor, worker, outcome):
    owner, device, job_id = worker.job.owner_id, worker.device, worker.job.id
    job = None if outcome is None else outcome.job
    if job is None or job.state != "SUCCEEDED":
        return DailyArchiveExecution(job, "NO_SUCCESS")
    if outcome.result is None:
        archived, cancelled = await _complete_critical(
            asyncio.to_thread(
                _get_archived,
                worker.history,
                owner,
                device,
                job_id,
                now_ms=executor.clock_ms(),
            )
        )
        if cancelled:
            raise asyncio.CancelledError
        return DailyArchiveExecution(
            job, "STORED" if archived else "RESULT_UNAVAILABLE", archived
        )
    record, cancelled = await _complete_critical(
        asyncio.to_thread(
            _save_success,
            executor,
            worker,
            job,
            outcome.result,
            now_ms=executor.clock_ms(),
        )
    )
    if cancelled:
        raise asyncio.CancelledError
    return DailyArchiveExecution(job, "STORED", record)
