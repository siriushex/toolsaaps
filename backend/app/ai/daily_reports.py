"""Internal encrypted facts archive. No model dispatch or public read authority."""

from __future__ import annotations

import hashlib
import secrets
import shutil
import time
from typing import Annotated, Literal

from pydantic import Field

from .daily_glucose import Count, Digest, NonnegativeFloat, civil_day
from .glucose_comparison import DailyGlucoseReview
from .history_contract import (
    Closed,
    HistoryError,
    RETENTION_MS,
    Time,
    encoded,
    valid_time,
)

MAX_REPORT_BYTES = 524_288
MAX_REPORT_REVISIONS = 32
MAX_PAGE_ENTRIES = 20
REVISION = "daily-facts-report-v1"
Index = Annotated[int, Field(ge=1, le=2**63 - 1)]
META_FIELDS = (
    "sequence",
    "report_key",
    "report_revision",
    "created_ms",
    "expires_ms",
    "facts_digest",
)
SCHEMA = (
    "CREATE TABLE daily_reports(sequence INTEGER PRIMARY KEY AUTOINCREMENT,"
    "report_key TEXT NOT NULL,report_revision INTEGER NOT NULL,created_ms INTEGER NOT NULL,"
    "expires_ms INTEGER NOT NULL,facts_digest TEXT NOT NULL,nonce BLOB NOT NULL,"
    "ciphertext BLOB NOT NULL,UNIQUE(report_key,report_revision),UNIQUE(report_key,facts_digest))",
    "CREATE INDEX daily_report_expiry ON daily_reports(expires_ms)",
)


class DailyFactsReport(Closed):
    revision: Literal["daily-facts-report-v1"] = REVISION
    origin: Literal["SERVER_FACTS_ARCHIVE"] = "SERVER_FACTS_ARCHIVE"
    archiveSequence: Index
    reportKey: Digest
    reportRevision: Index
    createdAtMs: Time
    expiresAtMs: Time
    factsDigest: Digest
    factsState: Literal["FACTS_ONLY", "STALE_SOURCE"] = "FACTS_ONLY"
    sourceCheck: Literal["NOT_CHECKED", "MATCHED", "CHANGED"] = "NOT_CHECKED"
    modelState: Literal["NOT_REQUESTED"] = "NOT_REQUESTED"
    facts: DailyGlucoseReview


class DailyReportSummary(Closed):
    provenance: Literal["DEVICE_DECLARED"] = "DEVICE_DECLARED"
    dataCompleteness: Literal["UNKNOWN"] = "UNKNOWN"
    archiveSequence: Index
    reportKey: Digest
    reportRevision: Index
    reportDate: str
    zoneId: str
    createdAtMs: Time
    expiresAtMs: Time
    factsDigest: Digest
    sourceCheck: Literal["NOT_CHECKED"] = "NOT_CHECKED"
    modelState: Literal["NOT_REQUESTED"] = "NOT_REQUESTED"
    coveragePct: NonnegativeFloat
    qualityOkCoveredMinutes: NonnegativeFloat
    unverifiedCoveredMinutes: NonnegativeFloat
    missingMinutes: NonnegativeFloat
    comparisonStatus: Literal["LIMITED_DATA", "DESCRIPTIVE"]


class DailyReportPage(Closed):
    revision: Literal["daily-facts-report-v1"] = REVISION
    membershipMeaning: Literal["RETAINED_ARCHIVE_ONLY"] = "RETAINED_ARCHIVE_ONLY"
    snapshotSequence: Count
    records: tuple[DailyReportSummary, ...]
    hasMore: bool
    nextAfterSequence: Count | None


def _key(store, owner, device, day, zone):
    return hashlib.sha256(
        encoded([store._identity(owner, device), day, zone])
    ).hexdigest()


def _checked_facts(store, owner, device, facts, now):
    valid_time(now)
    if type(facts) is not DailyGlucoseReview:
        raise ValueError("invalid_daily_report_facts")
    # Even frozen Pydantic containers can be altered through model_copy or nested data.
    facts = DailyGlucoseReview.model_validate_json(
        encoded(facts.model_dump(mode="json"))
    )
    current, comparison = facts.current, facts.comparison
    binding = store._identity(owner, device)
    window = civil_day(current.window.reportDate, current.window.zoneId)
    if (
        current.window != window
        or not window.endMs <= current.asOfMs <= now < window.endMs + RETENTION_MS
        or current.sourceBinding != binding
        or comparison.sourceBinding != binding
        or comparison.currentDigest != current.sourceDigest
        or comparison.reportDate != window.reportDate
        or comparison.snapshotSequence != current.snapshotSequence
        or comparison.asOfMs != current.asOfMs
    ):
        raise ValueError("invalid_daily_report_binding")
    return facts


def _highwater(db):
    value = db.execute(
        "SELECT seq FROM sqlite_sequence WHERE name='daily_reports'"
    ).fetchone()
    high = value[0] if value is not None else 0
    maximum = db.execute(
        "SELECT coalesce(max(sequence),0) FROM daily_reports"
    ).fetchone()[0]
    if type(high) is not int or not 0 <= maximum <= high < 2**63 - 1:
        raise ValueError("invalid_daily_report_sequence")
    return high


def _decrypt(store, owner, device, row):
    if (
        type(row["nonce"]) is not bytes
        or len(row["nonce"]) != 12
        or type(row["ciphertext"]) is not bytes
        or not 16 < len(row["ciphertext"]) <= MAX_REPORT_BYTES + 16
    ):
        raise ValueError("invalid_daily_report_ciphertext")
    plain = store._cipher(owner, device).decrypt(
        row["nonce"],
        row["ciphertext"],
        store._aad(
            owner, device, "daily-facts-report-v1", [row[k] for k in META_FIELDS]
        ),
    )
    report = DailyFactsReport.model_validate_json(plain)
    facts = _checked_facts(store, owner, device, report.facts, report.createdAtMs)
    current = facts.current
    expected = dict(
        sequence=report.archiveSequence,
        report_key=report.reportKey,
        report_revision=report.reportRevision,
        created_ms=report.createdAtMs,
        expires_ms=report.expiresAtMs,
        facts_digest=report.factsDigest,
    )
    if (
        any(row[k] != v for k, v in expected.items())
        or report.reportKey
        != _key(store, owner, device, current.window.reportDate, current.window.zoneId)
        or report.factsDigest
        != hashlib.sha256(encoded(facts.model_dump(mode="json"))).hexdigest()
        or report.expiresAtMs != current.window.endMs + RETENTION_MS
        or report.sourceCheck != "NOT_CHECKED"
        or report.factsState != "FACTS_ONLY"
    ):
        raise ValueError("invalid_daily_report_entry")
    return report


def _budget(db):
    end, instructions = time.monotonic() + 5, 0

    def progress():
        nonlocal instructions
        instructions += 1000
        return int(instructions > 2_000_000 or time.monotonic() >= end)

    db.set_progress_handler(progress, 1000)
    return end


def _deadline(end):
    if time.monotonic() >= end:
        raise HistoryError(503, "daily_report_unavailable")


def save_report(store, owner, device, facts, *, now_ms):
    facts = _checked_facts(store, owner, device, facts, now_ms)
    if len(encoded(facts.model_dump(mode="json"))) > MAX_REPORT_BYTES:
        raise ValueError("daily_report_too_large")
    with store._locked():
        if shutil.disk_usage(store.root).free < store.min_free_bytes:
            raise HistoryError(503, "history_storage_unavailable")
        db = store._open(owner, device, create=True)
        try:
            store._enable_daily_reports(db)
            db.execute("BEGIN IMMEDIATE")
            end = _budget(db)
            result = _save_report_in_transaction(
                store, owner, device, db, facts, now_ms=now_ms, end=end,
            )
            _deadline(end)
            db.commit()
            return result
        finally:
            db.close()


def _save_report_in_transaction(store, owner, device, db, facts, *, now_ms, end):
    """Caller owns the protected transaction, budget and commit."""
    facts = _checked_facts(store, owner, device, facts, now_ms)
    if not db.in_transaction:
        raise ValueError("daily_report_transaction_required")
    window = facts.current.window
    key = _key(store, owner, device, window.reportDate, window.zoneId)
    facts_plain = encoded(facts.model_dump(mode="json"))
    if len(facts_plain) > MAX_REPORT_BYTES:
        raise ValueError("daily_report_too_large")
    digest = hashlib.sha256(facts_plain).hexdigest()
    store._purge(db, now_ms)
    row = db.execute(
        "SELECT * FROM daily_reports WHERE report_key=? AND facts_digest=?",
        (key, digest),
    ).fetchone()
    if row is not None:
        result = _decrypt(store, owner, device, row)
        if now_ms < result.createdAtMs:
            raise HistoryError(409, "daily_report_clock_regression")
        _deadline(end)
        return result
    old = db.execute(
        "SELECT * FROM daily_reports WHERE report_key=? ORDER BY report_revision DESC LIMIT 1",
        (key,),
    ).fetchone()
    revision = 1
    if old is not None:
        previous = _decrypt(store, owner, device, old)
        if (
            now_ms < previous.createdAtMs
            or facts.current.asOfMs < previous.facts.current.asOfMs
            or facts.current.snapshotSequence < previous.facts.current.snapshotSequence
        ):
            raise HistoryError(409, "daily_report_source_regression")
        revision = previous.reportRevision + 1
    if revision > MAX_REPORT_REVISIONS:
        raise HistoryError(429, "daily_report_revision_limit")
    result = DailyFactsReport(
        archiveSequence=_highwater(db) + 1,
        reportKey=key,
        reportRevision=revision,
        createdAtMs=now_ms,
        expiresAtMs=window.endMs + RETENTION_MS,
        factsDigest=digest,
        facts=facts,
    )
    plain = encoded(result.model_dump(mode="json"))
    if len(plain) > MAX_REPORT_BYTES:
        raise HistoryError(413, "daily_report_too_large")
    values = dict(
        sequence=result.archiveSequence,
        report_key=key,
        report_revision=revision,
        created_ms=now_ms,
        expires_ms=result.expiresAtMs,
        facts_digest=digest,
    )
    nonce = secrets.token_bytes(12)
    values.update(
        nonce=nonce,
        ciphertext=store._cipher(owner, device).encrypt(
            nonce, plain,
            store._aad(owner, device, "daily-facts-report-v1", [values[k] for k in META_FIELDS]),
        ),
    )
    db.execute(
        "INSERT INTO daily_reports(" + ",".join(values) + ") VALUES("
        + ",".join("?" for _ in values) + ")", tuple(values.values()),
    )
    store._check_quota(db)
    _deadline(end)
    return result


def _query_key(
    store,
    owner,
    device,
    *,
    report_date,
    zone_id,
    now_ms,
    report_revision=None,
    source_digest=None,
):
    valid_time(now_ms)
    window = civil_day(report_date, zone_id)
    if (
        report_revision is not None
        and (
            type(report_revision) is not int
            or not 1 <= report_revision <= MAX_REPORT_REVISIONS
        )
        or source_digest is not None
        and (
            type(source_digest) is not str
            or len(source_digest) != 64
            or any(c not in "0123456789abcdef" for c in source_digest)
        )
    ):
        raise ValueError("invalid_daily_report_query")
    return _key(store, owner, device, window.reportDate, window.zoneId)


def _read_report(store, owner, device, db, *, key, now_ms, report_revision):
    """Read inside the caller's protected transaction and deadline."""
    if db.execute("PRAGMA user_version").fetchone()[0] < 3:
        return None
    _highwater(db)
    clause, args = (
        ("", ())
        if report_revision is None
        else (" AND report_revision=?", (report_revision,))
    )
    row = db.execute(
        "SELECT * FROM daily_reports WHERE report_key=? AND created_ms<=? AND expires_ms>?"
        + clause
        + " ORDER BY report_revision DESC LIMIT 1",
        (key, now_ms, now_ms, *args),
    ).fetchone()
    return None if row is None else _decrypt(store, owner, device, row)


def get_report(
    store,
    owner,
    device,
    *,
    report_date,
    zone_id,
    now_ms,
    report_revision=None,
    source_digest=None,
):
    key = _query_key(
        store,
        owner,
        device,
        report_date=report_date,
        zone_id=zone_id,
        now_ms=now_ms,
        report_revision=report_revision,
        source_digest=source_digest,
    )
    with store._locked():
        db = store._open(owner, device, create=False)
        if db is None:
            return None
        try:
            db.execute("BEGIN")
            end = _budget(db)
            result = _read_report(
                store,
                owner,
                device,
                db,
                key=key,
                now_ms=now_ms,
                report_revision=report_revision,
            )
            _deadline(end)
            if result is not None and source_digest is not None:
                changed = source_digest != result.facts.comparison.sourceDigest
                result = result.model_copy(
                    update=dict(
                        sourceCheck="CHANGED" if changed else "MATCHED",
                        factsState="STALE_SOURCE" if changed else "FACTS_ONLY",
                    )
                )
            return result
        finally:
            db.close()


def list_reports(
    store, owner, device, *, now_ms, after_sequence=0, snapshot_sequence=None, limit=10
):
    valid_time(now_ms)
    store._identity(owner, device)
    for value in (
        after_sequence,
        snapshot_sequence if snapshot_sequence is not None else 0,
    ):
        if type(value) is not int or not 0 <= value < 2**63 - 1:
            raise ValueError("invalid_daily_report_query")
    if type(limit) is not int or not 1 <= limit <= MAX_PAGE_ENTRIES:
        raise ValueError("invalid_daily_report_query")
    with store._locked():
        db = store._open(owner, device, create=False)
        try:
            records, high, has_more = [], 0, False
            if db is not None:
                db.execute("BEGIN")
                end = _budget(db)
                if db.execute("PRAGMA user_version").fetchone()[0] >= 3:
                    high = _highwater(db)
            upper = high if snapshot_sequence is None else snapshot_sequence
            if not after_sequence <= upper <= high:
                raise HistoryError(409, "daily_report_snapshot_unavailable")
            if high:
                rows = db.execute(
                    "SELECT * FROM daily_reports WHERE sequence>? AND sequence<=? "
                    "AND created_ms<=? AND expires_ms>? ORDER BY sequence LIMIT ?",
                    (after_sequence, upper, now_ms, now_ms, limit + 1),
                )
                for row in rows:
                    report = _decrypt(store, owner, device, row)
                    _deadline(end)
                    if len(records) == limit:
                        has_more = True
                        break
                    records.append(
                        DailyReportSummary(
                            **{
                                key: getattr(report, key)
                                for key in (
                                    "archiveSequence",
                                    "reportKey",
                                    "reportRevision",
                                    "createdAtMs",
                                    "expiresAtMs",
                                    "factsDigest",
                                )
                            },
                            reportDate=report.facts.current.window.reportDate,
                            zoneId=report.facts.current.window.zoneId,
                            coveragePct=report.facts.current.coveragePct,
                            qualityOkCoveredMinutes=report.facts.current.qualityOkCoveredMinutes,
                            unverifiedCoveredMinutes=report.facts.current.unverifiedCoveredMinutes,
                            missingMinutes=report.facts.current.missingMinutes,
                            comparisonStatus=report.facts.comparison.status,
                        )
                    )
            if db is not None:
                _deadline(end)
            return DailyReportPage(
                snapshotSequence=upper,
                records=tuple(records),
                hasMore=has_more,
                nextAfterSequence=records[-1].archiveSequence if has_more else None,
            )
        finally:
            if db is not None:
                db.close()
