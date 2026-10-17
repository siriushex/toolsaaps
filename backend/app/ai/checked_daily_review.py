"""Trusted source-checked review boundaries; no inference or admission authority."""

from __future__ import annotations

from dataclasses import dataclass

from . import daily_review
from .daily_source import DailySourceCheck, _report_source_snapshot
from .history_contract import HistoryError, valid_time


@dataclass(frozen=True)
class SourceCheckedReviewWork:
    work: daily_review.DailyReviewWork
    source_check: DailySourceCheck


@dataclass(frozen=True)
class SourceCheckedDailyReview:
    review: daily_review.GroundedDailyReview
    source_check: DailySourceCheck


def _matched_report(report, source):
    if report is None:
        raise HistoryError(404, "daily_review_report_unavailable")
    if source.status == "CHANGED":
        raise HistoryError(409, "daily_review_source_changed")
    if source.status != "MATCHED":
        raise HistoryError(409, "daily_review_source_unavailable")
    return report.model_copy(update=dict(sourceCheck="MATCHED"))


def _prepare_input(report, *, now_ms, language):
    try:
        return daily_review.prepare_review(report, now_ms=now_ms, language=language)
    except ValueError:
        raise HistoryError(422, "daily_review_input_unavailable") from None


def prepare_checked_review(
    store, owner, device, *, report_date, zone_id, now_ms, language="ru-RU"
):
    if type(language) is not str or language not in ("ru-RU", "en-US"):
        raise ValueError("invalid_daily_review_language")
    with _report_source_snapshot(
        store, owner, device, report_date=report_date, zone_id=zone_id, now_ms=now_ms
    ) as (report, source):
        matched = _matched_report(report, source)
        work = _prepare_input(matched, now_ms=now_ms, language=language)
        return SourceCheckedReviewWork(work, source)


def _bind_work_to_report(report, source, work, packet, *, now_ms):
    if report is not None and (
        report.reportKey,
        report.reportRevision,
        report.factsDigest,
    ) != (packet.reportKey, packet.reportRevision, packet.factsDigest):
        raise HistoryError(409, "daily_review_report_superseded")
    matched = _matched_report(report, source)
    expected = _prepare_input(matched, now_ms=now_ms, language=packet.language)
    if expected != work:
        raise HistoryError(409, "daily_review_work_archive_mismatch")
    return expected


def validate_checked_review(store, owner, device, work, body, *, now_ms):
    valid_time(now_ms)
    packet = daily_review._packet(work)
    if work.source_binding != store._identity(owner, device):
        raise ValueError("invalid_daily_review_owner_device_binding")
    if not packet.generatedAtMs <= now_ms < packet.expiresAtMs:
        raise ValueError("daily_review_source_changed_or_expired")
    # Reject malformed/oversized replies before an expensive retained-source read.
    daily_review._decode(
        body, daily_review.DailyReviewResult, daily_review.MAX_RESULT_BYTES
    )
    window = packet.day.window
    with _report_source_snapshot(
        store,
        owner,
        device,
        report_date=window.reportDate,
        zone_id=window.zoneId,
        now_ms=now_ms,
    ) as (report, source):
        expected = _bind_work_to_report(report, source, work, packet, now_ms=now_ms)
        try:
            result = daily_review.validate_review(
                expected, body, current_source_digest=source.sourceDigest, now_ms=now_ms
            )
        except ValueError:
            raise HistoryError(422, "invalid_daily_review_result") from None
        return SourceCheckedDailyReview(result, source)
