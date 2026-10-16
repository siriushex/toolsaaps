"""Point-in-time frozen-source verification; no model or publication authority."""

from __future__ import annotations

import hashlib
from contextlib import contextmanager
from typing import Literal

from . import daily_glucose, daily_reports, glucose_comparison
from .daily_glucose import Count, Digest
from .history_contract import Closed, RETENTION_MS, Time, encoded


class DailySourceCheck(Closed):
    revision: Literal["daily-source-check-v1"] = "daily-source-check-v1"
    scope: Literal["GLUCOSE_ONLY"] = "GLUCOSE_ONLY"
    meaning: Literal["RETAINED_HISTORY_AT_CHECK"] = "RETAINED_HISTORY_AT_CHECK"
    provenance: Literal["DEVICE_DECLARED"] = "DEVICE_DECLARED"
    dataCompleteness: Literal["UNKNOWN"] = "UNKNOWN"
    modelState: Literal["NOT_REQUESTED"] = "NOT_REQUESTED"
    reportKey: Digest
    reportRevision: daily_reports.Index
    factsDigest: Digest
    sourceBinding: Digest
    sourceDigest: Digest
    replayedSourceDigest: Digest | None
    replayedFactsDigest: Digest | None
    originalAsOfMs: Time
    originalSnapshotSequence: Count
    checkedAtMs: Time
    checkedSnapshotSequence: Count
    windowStartMs: Time
    originalWindowEndMs: Time
    windowEndMs: Time
    status: Literal["MATCHED", "CHANGED", "UNAVAILABLE"]
    reason: Literal[
        "SOURCE_MATCHED",
        "SOURCE_CHANGED",
        "NEWER_OBSERVATION",
        "RETENTION_WINDOW",
        "HISTORY_ROLLBACK",
        "FACTS_MISMATCH",
    ]


def check_report_source(
    store, owner, device, *, report_date, zone_id, now_ms, report_revision=None
):
    with _report_source_snapshot(
        store,
        owner,
        device,
        report_date=report_date,
        zone_id=zone_id,
        now_ms=now_ms,
        report_revision=report_revision,
    ) as (_, source):
        return source


@contextmanager
def _report_source_snapshot(
    store, owner, device, *, report_date, zone_id, now_ms, report_revision=None
):
    key = daily_reports._query_key(
        store,
        owner,
        device,
        report_date=report_date,
        zone_id=zone_id,
        now_ms=now_ms,
        report_revision=report_revision,
    )
    with store._daily_read_snapshot(owner, device) as (db, snapshot, stop_at):
        if db is None:
            yield None, None
            return
        report = daily_reports._read_report(
            store,
            owner,
            device,
            db,
            key=key,
            now_ms=now_ms,
            report_revision=report_revision,
        )
        if report is None:
            yield None, None
            return
        source = _check_selected_source(
            store,
            owner,
            device,
            db,
            report,
            now_ms=now_ms,
            snapshot=snapshot,
            stop_at=stop_at,
        )
        yield report, source


def _check_selected_source(
    store, owner, device, db, report, *, now_ms, snapshot, stop_at
):
    current = report.facts.current
    report_date, zone_id = current.window.reportDate, current.window.zoneId
    windows = glucose_comparison.review_windows(
        report_date, zone_id, now_ms=current.asOfMs
    )
    start = max(1, windows[0].startMs - daily_glucose.MAX_GAP_MS)
    guard_end = windows[-1].endMs + daily_glucose.MAX_GAP_MS
    end = min(now_ms, guard_end)
    binding = dict(
        reportKey=report.reportKey,
        reportRevision=report.reportRevision,
        factsDigest=report.factsDigest,
        sourceBinding=current.sourceBinding,
        sourceDigest=report.facts.comparison.sourceDigest,
        originalAsOfMs=current.asOfMs,
        originalSnapshotSequence=current.snapshotSequence,
        checkedAtMs=now_ms,
        checkedSnapshotSequence=snapshot,
        windowStartMs=start,
        originalWindowEndMs=min(current.asOfMs, guard_end),
        windowEndMs=end,
    )

    def result(status, reason, source=None, facts=None):
        return DailySourceCheck(
            **binding,
            status=status,
            reason=reason,
            replayedSourceDigest=source,
            replayedFactsDigest=facts,
        )

    if start <= now_ms - RETENTION_MS:
        return result("UNAVAILABLE", "RETENTION_WINDOW")
    if snapshot < current.snapshotSequence:
        return result("UNAVAILABLE", "HISTORY_ROLLBACK")
    rows, changes = store._read_glucose_records(
        owner,
        device,
        db,
        from_ms=start,
        until_ms=end,
        now_ms=now_ms,
        row_limit=glucose_comparison.MAX_REVIEW_INPUT_RECORDS,
        snapshot=snapshot,
        stop_at=stop_at,
        include_deleted=True,
    )
    # Authenticate tombstones before treating their former evidence as absent.
    rows = [r for r in rows if not r.deleted]
    if any(r.observedAtMs > current.asOfMs for r in rows + changes):
        return result("CHANGED", "NEWER_OBSERVATION")
    # Original cuts/ranges reproduce the digest; actual check cuts stay separate.
    replayed = glucose_comparison.build_glucose_review(
        rows,
        sensor_changes=changes,
        report_date=report_date,
        zone_id=zone_id,
        ranges=current.ranges,
        now_ms=current.asOfMs,
        snapshot_sequence=current.snapshotSequence,
        source_binding=current.sourceBinding,
    )
    source = replayed.comparison.sourceDigest
    facts = hashlib.sha256(encoded(replayed.model_dump(mode="json"))).hexdigest()
    if source != binding["sourceDigest"]:
        return result("CHANGED", "SOURCE_CHANGED", source, facts)
    if facts != report.factsDigest:
        return result("UNAVAILABLE", "FACTS_MISMATCH", source, facts)
    return result("MATCHED", "SOURCE_MATCHED", source, facts)
