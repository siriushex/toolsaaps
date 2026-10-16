"""Dedicated bounded daily JSON channel. No caller-supplied execution settings."""
from __future__ import annotations

import asyncio
from dataclasses import dataclass
import hashlib
import json

from . import daily_review
from .daily_queue import FrozenDailyWork
from .daily_worker import MAX_DEADLINE_MS, NativeDailyReviewRunner, daily_session_policy
from .history_contract import valid_time
from .text_supervisor import SupervisorError, SupervisorTextRunner, TextSupervisor, _encode, policy_digest

MAX_REQUEST = 6 * daily_review.MAX_INPUT_BYTES + 8192
MAX_RESPONSE = 6 * daily_review.MAX_RESULT_BYTES + 1024


def daily_policy_digest(policy):
    if policy != daily_session_policy(policy.model):
        raise ValueError("fixed_daily_policy_required")
    return hashlib.sha256(_encode(dict(task="DAILY_REVIEW", revision=1,
        policy=policy_digest(policy), request=MAX_REQUEST, response=MAX_RESPONSE))).hexdigest()


def _remaining(work, deadline, clock):
    now = clock()
    valid_time(now)
    valid_time(deadline)
    packet = daily_review._packet(work)
    if not packet.generatedAtMs <= now < deadline <= min(work.expires_ms, now + MAX_DEADLINE_MS):
        raise SupervisorError("daily_deadline_rejected")
    return (deadline - now) / 1000


def _decode(value, policy, clock):
    try:
        if type(value) is not dict or set(value) != {"frozenWork", "deadlineMs"}:
            raise ValueError
        work = FrozenDailyWork.model_validate(value["frozenWork"]).to_work()
        _remaining(work, value["deadlineMs"], clock)
        if len(work.input_body) > policy.max_input_bytes:
            raise ValueError
        return work, value["deadlineMs"]
    except (ValueError, TypeError, AttributeError, KeyError):
        raise SupervisorError("daily_work_rejected") from None


def _result_dict(value):
    return daily_review._decode(value, daily_review.DailyReviewResult,
                               daily_review.MAX_RESULT_BYTES).model_dump(mode="json")


@dataclass(frozen=True)
class _Payload:
    work: daily_review.DailyReviewWork
    deadline: int
    digest: str


class SupervisorDailyReviewRunner(SupervisorTextRunner):
    max_request, max_response = MAX_REQUEST, MAX_RESPONSE
    response_fields = SupervisorTextRunner.response_fields | {"bindingSha256"}
    _policy_digest = staticmethod(daily_policy_digest)

    def __init__(self, path, peer, policy, *, deadline_ms, clock_ms):
        if not callable(clock_ms):
            raise ValueError("daily_clock_required")
        valid_time(deadline_ms)
        self.deadline_ms, self.clock_ms = deadline_ms, clock_ms
        self.binding_digest = None
        super().__init__(path, peer, policy)

    def _payload(self, work):
        _remaining(work, self.deadline_ms, self.clock_ms)
        return _encode(dict(frozenWork=FrozenDailyWork.from_work(work).model_dump(mode="json"),
                            deadlineMs=self.deadline_ms))

    async def run(self, work, policy):
        if self._task is not None or self._cancel:
            raise SupervisorError("daily_runner_already_used")
        if policy != self.policy:
            raise SupervisorError("daily_policy_rejected")
        payload = self._payload(work)
        self.binding_digest = hashlib.sha256(payload).hexdigest()
        self._task = asyncio.create_task(self._exchange(payload))
        result = await asyncio.shield(self._task)
        _remaining(work, self.deadline_ms, self.clock_ms)
        return result

    def _request(self, payload):
        return dict(version=1, runId=self.run_id, policySha256=self.digest, work=json.loads(payload))

    def _validate_extra(self, value):
        if value["bindingSha256"] != self.binding_digest:
            raise SupervisorError("daily_receipt_binding_rejected")

    @staticmethod
    def _result(value):
        raw = _encode(value)
        return _encode(_result_dict(raw))


class DailySupervisor(TextSupervisor):
    max_request, max_response = MAX_REQUEST, MAX_RESPONSE
    _policy_digest = staticmethod(daily_policy_digest)
    _result = staticmethod(_result_dict)

    def __init__(self, policy, peer, process_factory, *, clock_ms, capacity=None, run_seconds=840):
        if not callable(clock_ms):
            raise ValueError("daily_clock_required")
        self.clock_ms = clock_ms
        super().__init__(policy, peer, process_factory, capacity=capacity, run_seconds=run_seconds)

    def _parse_request(self, value):
        self._header(value, {"version", "runId", "policySha256", "work"})
        work, deadline = _decode(value["work"], self.policy, self.clock_ms)
        return value["runId"], _Payload(work, deadline, hashlib.sha256(_encode(value["work"])).hexdigest())

    def _runner(self, payload):
        return NativeDailyReviewRunner(self.factory())

    async def _run(self, runner, payload):
        async with asyncio.timeout(_remaining(payload.work, payload.deadline, self.clock_ms)):
            result = await runner.run(payload.work, self.policy)
        _remaining(payload.work, payload.deadline, self.clock_ms)
        return result

    def _receipt_extra(self, payload):
        return dict(bindingSha256=payload.digest)
