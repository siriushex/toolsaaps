"""Dedicated daily JSON worker, not a dispatcher or provider admission gate.

Use only through JobExecutor with an independently admitted contained process.
Construction has no storage/process effects. No HTTP policy is registered here.
"""

from __future__ import annotations

import asyncio
import hashlib
from typing import Protocol

from . import daily_review
from .contextual_chat import ContainedTextProcess
from .daily_queue import DailyQueueHandoff
from .history_contract import canonical_uuid, encoded, valid_time
from .history_store import HistoryStore
from .job_ledger import Job
from .native_text import NativeTextError, TextSessionPolicy, _NativeSession

ROUTE_REVISION = "advisory-daily-worker-v1"
MAX_DEADLINE_MS = 900_000


def daily_session_policy(model: str) -> TextSessionPolicy:
    return TextSessionPolicy(
        model, daily_review.SYSTEM_PROMPT, max_input_bytes=daily_review.MAX_INPUT_BYTES
    )


def daily_request_digest(work, *, request_id, model, deadline_ms):
    """Separate daily binding; never adapt a daily packet into a CHAT request."""
    canonical_uuid(request_id)
    valid_time(deadline_ms)
    daily_review._packet(work)
    policy = daily_session_policy(model)
    if deadline_ms > work.expires_ms:
        raise ValueError("invalid_daily_worker_deadline")
    return hashlib.sha256(
        encoded(
            dict(
                kind="DAILY_REVIEW",
                routeRevision=ROUTE_REVISION,
                requestId=request_id,
                deadlineMs=deadline_ms,
                model=policy.model,
                inputDigest=work.input_digest,
                sourceBinding=work.source_binding,
                promptRevision=work.prompt_revision,
                promptDigest=work.prompt_digest,
                maxInputBytes=policy.max_input_bytes,
                maxResultBytes=daily_review.MAX_RESULT_BYTES,
                maxStreamBytes=policy.max_stream_bytes,
                maxEvents=policy.max_events,
                maxDeadlineMs=MAX_DEADLINE_MS,
                allowTools=False,
                allowActions=False,
            )
        )
    ).hexdigest()


class NativeDailyReviewSession(_NativeSession):
    """One tool-free ephemeral text turn returning only a closed daily JSON reply."""

    def __init__(self, transport, policy, *, containment_verified: bool):
        if (
            containment_verified is not True
            or type(policy) is not TextSessionPolicy
            or policy != daily_session_policy(policy.model)
        ):
            raise ValueError("contained_daily_transport_required")
        super().__init__(transport, policy)

    @staticmethod
    def _decode_answer(value):
        try:
            if type(value) is not str:
                raise ValueError
            raw = value.encode("utf-8")
            daily_review._decode(
                raw, daily_review.DailyReviewResult, daily_review.MAX_RESULT_BYTES
            )
            return raw
        except (ValueError, UnicodeError, RecursionError):
            raise NativeTextError("daily_reply_rejected") from None

    async def run(self, work):
        if self._used:
            raise NativeTextError("daily_session_already_used")
        try:
            daily_review._packet(work)
        except (ValueError, TypeError, AttributeError):
            self._used = True
            raise NativeTextError("daily_input_rejected") from None
        return await self._run(
            [{"type": "text", "text": work.input_body.decode(), "textElements": []}]
        )


class DailyTurnRunner(Protocol):
    async def run(
        self, work: daily_review.DailyReviewWork, policy: TextSessionPolicy
    ) -> bytes: ...

    async def stop_and_confirm(self) -> bool: ...


class NativeDailyReviewRunner:
    def __init__(self, process: ContainedTextProcess):
        self.process = process
        self._attempted = False

    async def run(self, work, policy):
        if self._attempted:
            raise ValueError("daily_runner_already_used")
        # A failed or cancelled start may already own an OS process/profile lease.
        self._attempted = True
        transport = await self.process.start(policy)
        return await NativeDailyReviewSession(
            transport, policy, containment_verified=True
        ).run(work)

    async def stop_and_confirm(self):
        return not self._attempted or await self.process.stop_and_confirm() is True


class DailyReviewWorker:
    def __init__(
        self,
        job: Job,
        handoff: DailyQueueHandoff,
        history: HistoryStore,
        *,
        device: str,
        model: str,
        clock_ms,
        process: ContainedTextProcess | None = None,
        runner: DailyTurnRunner | None = None,
    ):
        if (
            type(job) is not Job
            or type(handoff) is not DailyQueueHandoff
            or not isinstance(history, HistoryStore)
            or not callable(clock_ms)
            or (process is None) == (runner is None)
        ):
            raise ValueError("invalid_daily_worker")
        packet = daily_review._packet(handoff.work)
        canonical_uuid(job.id)
        valid_time(job.created_ms)
        digest = daily_request_digest(
            handoff.work,
            request_id=handoff.request_id,
            model=model,
            deadline_ms=job.deadline_ms,
        )
        if (
            job.kind != "DAILY_REVIEW"
            or job.route_revision != ROUTE_REVISION
            or job.request_id != handoff.request_id
            or job.digest != digest
            or job.state != "QUEUED"
            or job.started_ms is not None
            or job.finished_ms is not None
            or job.key_hash is not None
            and job.key_hash != device
            or history._identity(job.owner_id, device) != handoff.work.source_binding
            or not packet.generatedAtMs <= job.created_ms < job.deadline_ms
            or job.deadline_ms - job.created_ms > MAX_DEADLINE_MS
        ):
            raise ValueError("invalid_daily_worker_binding")
        self.job, self.work, self.history, self.device = (
            job,
            handoff.work,
            history,
            device,
        )
        self.policy, self.clock_ms = daily_session_policy(model), clock_ms
        self.runner = runner if runner is not None else NativeDailyReviewRunner(process)
        self.route_revision, self.request_digest = job.route_revision, digest
        self._used = self._runner_attempted = False
        self._source_task = None
        self._last_ms = job.created_ms

    def _now(self):
        now = self.clock_ms()
        valid_time(now)
        if not self._last_ms <= now < self.job.deadline_ms:
            raise ValueError("daily_worker_deadline")
        self._last_ms = now
        return now

    def _prepare(self, now):
        owner, request = self.job.owner_id, self.job.request_id
        entry = self.history.get_queued_daily_glucose_review(
            owner, self.device, request, now_ms=now
        )
        if (
            entry is None
            or entry.state != "HANDOFF_RECORDED"
            or entry.frozenWork.to_work() != self.work
            or entry.handoffAtMs is None
            or entry.handoffAtMs > self.job.created_ms
            or self.job.deadline_ms > entry.dispatchBeforeMs
        ):
            raise ValueError("daily_worker_handoff_unavailable")
        prepared = self.history.prepare_checked_daily_glucose_review(
            owner,
            self.device,
            report_date=entry.reportDate,
            zone_id=entry.zoneId,
            now_ms=now,
            language=daily_review._packet(self.work).language,
        )
        if prepared.work != self.work:
            raise ValueError("daily_worker_archive_mismatch")

    async def _read(self, operation, *args, **kwargs):
        self._source_task = asyncio.create_task(
            asyncio.to_thread(operation, *args, **kwargs)
        )
        return await asyncio.shield(self._source_task)

    async def run(self):
        if self._used:
            raise ValueError("daily_worker_already_used")
        self._used = True
        await self._read(self._prepare, self._now())
        self._now()
        self._runner_attempted = True
        raw = await self.runner.run(self.work, self.policy)
        checked = await self._read(
            self.history.validate_checked_daily_glucose_review,
            self.job.owner_id,
            self.device,
            self.work,
            raw,
            now_ms=self._now(),
        )
        self._now()
        return checked

    async def stop_and_confirm(self):
        # asyncio cancellation cannot stop a SQLite reader thread. Join that owned
        # bounded operation before claiming cleanup, then verify the native stop.
        if self._source_task is not None:
            try:
                await asyncio.shield(self._source_task)
            except asyncio.CancelledError:
                raise
            except Exception:
                pass
        return (
            not self._runner_attempted or await self.runner.stop_and_confirm() is True
        )
