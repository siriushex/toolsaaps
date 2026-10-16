"""Trusted one-shot daily composition, not a schedule or public admission route."""
from __future__ import annotations

import asyncio
from dataclasses import dataclass
from typing import Literal

from . import daily_results, daily_review
from .daily_queue import DailyQueueHandoff
from .daily_worker import DailyReviewWorker, MAX_DEADLINE_MS, ROUTE_REVISION, daily_request_digest, daily_session_policy
from .history_contract import valid_time
from .history_store import HistoryStore
from .job_executor import JobExecutor, _complete_critical
from .job_ledger import LedgerError


@dataclass(frozen=True)
class DailyDispatchResult:
    disposition: Literal["ADMITTED", "DEFERRED", "RECORDED", "UNAVAILABLE"]
    archive: daily_results.DailyArchiveExecution | None = None


async def _io(operation, *args, **kwargs):
    result, cancelled = await _complete_critical(asyncio.to_thread(operation, *args, **kwargs))
    if cancelled:
        raise asyncio.CancelledError
    return result


class DailyDispatcher:
    def __init__(self, history, executor, *, model, runner_factory, clock_ms=None):
        if (not isinstance(history, HistoryStore) or type(executor) is not JobExecutor
                or not callable(runner_factory) or clock_ms is not None and not callable(clock_ms)):
            raise ValueError("invalid_daily_dispatcher")
        daily_session_policy(model)
        self.history, self.executor, self.model = history, executor, model
        self.runner_factory = runner_factory
        self.clock_ms = clock_ms or executor.clock_ms

    def _handoff(self, owner, device, entry, now):
        if entry.state == "QUEUED":
            given = self.history.handoff_daily_glucose_review(owner, device, entry.queueId, now_ms=now)
            if given is not None:
                return given
        entry = self.history.get_queued_daily_glucose_review(owner, device, entry.queueId, now_ms=now)
        if entry is None or entry.state != "HANDOFF_RECORDED" or now >= entry.dispatchBeforeMs:
            return None
        work = entry.frozenWork.to_work()
        packet = daily_review._packet(work)
        prepared = self.history.prepare_checked_daily_glucose_review(owner, device,
            report_date=entry.reportDate, zone_id=entry.zoneId, language=packet.language, now_ms=now)
        if prepared.work != work:
            raise ValueError("daily_dispatch_source_changed")
        # A handoff is not inference admission. Only a new ledger claim may run it.
        return DailyQueueHandoff(entry.queueId, work, prepared.source_check)

    async def _recorded(self, owner, device, entry, job, now):
        expected = daily_request_digest(entry.frozenWork.to_work(), request_id=entry.queueId,
                                       model=self.model, deadline_ms=job.deadline_ms)
        if (job.digest != expected or job.route_revision != ROUTE_REVISION
                or job.key_hash not in (None, device)
                or now < max(job.created_ms, job.started_ms or 0, job.finished_ms or 0)):
            raise ValueError("daily_dispatch_job_binding_mismatch")
        archived = None
        state = "NO_SUCCESS"
        if job.state == "SUCCEEDED":
            archived = await _io(daily_results._get_archived, self.history,
                                 owner, device, job.id, now_ms=now)
            state = "STORED" if archived else "RESULT_UNAVAILABLE"
        return DailyDispatchResult("RECORDED", daily_results.DailyArchiveExecution(job, state, archived))

    async def dispatch(self, owner, device, queue_id):
        last = None
        def now():
            nonlocal last
            value = self.clock_ms()
            valid_time(value)
            if last is not None and value < last:
                raise ValueError("daily_dispatch_clock_regression")
            last = value
            return value
        entry = await _io(self.history.get_queued_daily_glucose_review,
                          owner, device, queue_id, now_ms=now())
        if entry is None or entry.state == "CANCELLED":
            return DailyDispatchResult("UNAVAILABLE")
        original = await _io(self.executor.ledger.get_request, owner, "DAILY_REVIEW", queue_id)
        if original is not None:
            return await self._recorded(owner, device, entry, original, now())
        if now() >= entry.dispatchBeforeMs:
            return DailyDispatchResult("UNAVAILABLE")
        given = await _io(self._handoff, owner, device, entry, now())
        if given is None:
            return DailyDispatchResult("UNAVAILABLE")
        deadline = min(entry.dispatchBeforeMs, now() + MAX_DEADLINE_MS)
        digest = daily_request_digest(given.work, request_id=queue_id, model=self.model, deadline_ms=deadline)
        workers = []
        def factory(job):
            worker = DailyReviewWorker(job, given, self.history, device=device, model=self.model,
                clock_ms=self.clock_ms, runner=self.runner_factory(job, given))
            workers.append(worker)
            return worker
        try:
            execution = await self.executor.execute_daily(owner_id=owner, request_id=queue_id,
                digest=digest, route_revision=ROUTE_REVISION, deadline_ms=deadline, worker_factory=factory)
        except LedgerError as error:
            if str(error) != "request_conflict":
                raise
            original = await _io(self.executor.ledger.get_request, owner, "DAILY_REVIEW", queue_id)
            if original is None:
                raise
            return await self._recorded(owner, device, entry, original, now())
        if execution.disposition == "DEFERRED":
            return DailyDispatchResult("DEFERRED")
        if execution.disposition == "RECORDED":
            return await self._recorded(owner, device, entry, execution.outcome.job, now())
        archive = await daily_results._archive_execution(self.executor, workers[0], execution.outcome)
        return DailyDispatchResult("ADMITTED", archive)
