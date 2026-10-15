import asyncio
import json
from dataclasses import replace
from threading import Event
from uuid import uuid4

import pytest
from fastapi.testclient import TestClient

from app.ai.contextual_chat import ContextualChatWorker, contextual_chat_policy
from app.ai.job_service import JobIdentity, JobWork
from app.ai.native_text import NativeTextError
from app.ai.schemas import ChatRequest
from test_ai_history_contract import NOW, body, record
from test_ai_history_store import KEY, OTHER, OWNER, store
from test_ai_native_text import MODEL, Transport, transcript
from test_ai_activation import service  # noqa: F401 - shared synthetic attestation fixture


def work():
    policy = contextual_chat_policy(MODEL)
    return JobWork(str(uuid4()), ChatRequest.decode(b'{"text":"Sample question"}'),
                   "c3" * 32, policy.route_revision, NOW + 60_000, policy,
                   JobIdentity(OWNER, KEY), NOW)


class Process:
    def __init__(self, frames=None, *, stop_receipt=True):
        self.transport = Transport(transcript() if frames is None else frames)
        self.started = []
        self.stops = 0
        self.stop_receipt = stop_receipt

    async def start(self, policy):
        self.started.append(policy)
        return self.transport

    async def stop_and_confirm(self):
        self.stops += 1
        return self.stop_receipt


def test_verified_device_context_is_in_one_text_turn_not_system_instructions(tmp_path):
    history = store(tmp_path)
    history.append(OWNER, KEY, body([record()]), now_ms=NOW)
    history.append(OWNER, OTHER, body([record(data={"glucoseMmol": 12.0})]), now_ms=NOW)
    process = Process()
    job = work()
    worker = ContextualChatWorker(job, history, process, clock_ms=lambda: NOW)

    async def scenario():
        assert await worker.run() == {"text": "Synthetic answer"}
        assert await worker.stop_and_confirm() is True
        with pytest.raises(ValueError, match="already_used"):
            await worker.run()
    asyncio.run(scenario())
    assert len(process.started) == process.stops == 1
    question = json.loads(process.transport.sent[-1]["params"]["input"][0]["text"])
    assert question["question"] == job.request.text
    context = question["deviceHistory"]
    assert context["coverage"]["eligibleRecords"] == 1
    assert context["glucoseCohorts"][0]["meanMmol"] == 6.4
    assert OWNER not in json.dumps(question) and KEY not in json.dumps(question)
    assert process.started[0].instructions == job.policy.system_prompt
    assert "untrusted data" in job.policy.system_prompt


def test_failed_context_does_not_start_a_model(tmp_path, monkeypatch):
    history = store(tmp_path)
    def unavailable(*args, **kwargs):
        raise ValueError("synthetic storage error")
    monkeypatch.setattr(history, "context_snapshot", unavailable)
    process = Process()
    worker = ContextualChatWorker(work(), history, process, clock_ms=lambda: NOW)
    async def scenario():
        with pytest.raises(ValueError):
            await worker.run()
        assert await worker.stop_and_confirm() is True
    asyncio.run(scenario())
    assert process.started == [] and process.stops == 0


@pytest.mark.parametrize("now", [NOW - 1, NOW + 60_000])
def test_invalid_clock_or_deadline_prevents_start(tmp_path, now):
    process = Process()
    worker = ContextualChatWorker(work(), store(tmp_path), process, clock_ms=lambda: now)
    with pytest.raises(ValueError, match="deadline"):
        asyncio.run(worker.run())
    assert process.started == []


def test_expired_after_context_does_not_start_model(tmp_path):
    times = iter([NOW, NOW + 60_000])
    process = Process()
    worker = ContextualChatWorker(work(), store(tmp_path), process, clock_ms=lambda: next(times))
    with pytest.raises(ValueError, match="deadline"):
        asyncio.run(worker.run())
    assert process.started == []


def test_oversized_combined_context_is_refused_before_process_start(tmp_path, monkeypatch):
    history, process = store(tmp_path), Process()
    monkeypatch.setattr(history, "context_snapshot", lambda *args, **kwargs: {"data": "x" * 131_072})
    worker = ContextualChatWorker(work(), history, process, clock_ms=lambda: NOW)
    with pytest.raises(ValueError, match="input_limit"):
        asyncio.run(worker.run())
    assert process.started == []


def test_cancellation_joins_context_reader_without_inference(tmp_path, monkeypatch):
    history = store(tmp_path)
    entered, release = Event(), Event()
    original = history.context_snapshot
    def slow(*args, **kwargs):
        entered.set()
        assert release.wait(2)
        return original(*args, **kwargs)
    monkeypatch.setattr(history, "context_snapshot", slow)
    process = Process()
    worker = ContextualChatWorker(work(), history, process, clock_ms=lambda: NOW)
    async def scenario():
        task = asyncio.create_task(worker.run())
        assert await asyncio.to_thread(entered.wait, 1)
        task.cancel()
        with pytest.raises(asyncio.CancelledError):
            await task
        stopping = asyncio.create_task(worker.stop_and_confirm())
        await asyncio.sleep(0)
        assert not stopping.done()
        release.set()
        assert await stopping is True
    try:
        asyncio.run(scenario())
    finally:
        release.set()
    assert process.started == []


def test_cancelled_start_still_requires_independent_process_stop(tmp_path):
    class Pending(Process):
        async def start(self, policy):
            self.started.append(policy)
            raise asyncio.CancelledError()
    process = Pending(stop_receipt=False)
    worker = ContextualChatWorker(work(), store(tmp_path), process, clock_ms=lambda: NOW)
    async def scenario():
        with pytest.raises(asyncio.CancelledError):
            await worker.run()
        assert await worker.stop_and_confirm() is False
    asyncio.run(scenario())
    assert process.stops == 1


def test_native_failure_never_retries_and_stop_failure_is_retained(tmp_path):
    process = Process([{"method": "warning", "params": {}}], stop_receipt="true")
    worker = ContextualChatWorker(work(), store(tmp_path), process, clock_ms=lambda: NOW)
    async def scenario():
        with pytest.raises(NativeTextError):
            await worker.run()
        assert await worker.stop_and_confirm() is False
    asyncio.run(scenario())
    assert len(process.started) == process.stops == 1


def test_unsupported_policy_and_invalid_device_identity_are_rejected(tmp_path):
    job = work()
    with pytest.raises(ValueError, match="invalid_contextual"):
        ContextualChatWorker(replace(job, policy=replace(job.policy, allow_tools=True)),
                             store(tmp_path), Process(), clock_ms=lambda: NOW)
    with pytest.raises(ValueError):
        JobIdentity(OWNER, "../foreign")


def test_signed_job_retrieves_only_authenticated_device_context_and_settles_after_stop(service, tmp_path):
    from app.ai.job_ledger import JobLedger
    from app.ai.job_service import AiJobService
    from app.ai_server import create_bound_ai_app
    from test_ai_job_transport import (NOW as API_NOW, MutableClock, activate, key_fingerprint,
                                      signed_get, submit, wait_for_terminal)

    history = store(tmp_path)
    clock = MutableClock()
    ledger = JobLedger(tmp_path / "jobs.sqlite")
    processes = []
    def factory(job):
        process = Process()
        processes.append(process)
        return ContextualChatWorker(job, history, process, clock_ms=clock)
    jobs = AiJobService(service[1], service[0].proofs, ledger, factory,
                        policy=contextual_chat_policy(MODEL), clock_ms=clock)
    try:
        with TestClient(create_bound_ai_app(service[0], jobs=jobs, clock_ms=clock)) as client:
            owner, key, tokens = activate(client, service)
            _, other_key, other_tokens = activate(client, service, owner_id=owner)
            row = record(eventAtMs=API_NOW - 1000, observedAtMs=API_NOW)
            history.append(owner, key_fingerprint(key), body([row]), now_ms=API_NOW)
            history.append(owner, key_fingerprint(other_key), body([
                row | {"data": {"glucoseMmol": 99.0}}]), now_ms=API_NOW)
            accepted = submit(client, key, tokens, b'{"text":"Sample question"}',
                              request_id=str(uuid4()), deadline_ms=API_NOW + 60_000)
            assert accepted.status_code == 202
            job_id = accepted.json()["job_id"]
            result = wait_for_terminal(client, key, tokens, job_id)
            assert result.json()["state"] == "SUCCEEDED"
            assert result.json()["result"] == {"text": "Synthetic answer"}
            assert processes[0].stops == 1
            context = json.loads(processes[0].transport.sent[-1]["params"]["input"][0]["text"])["deviceHistory"]
            assert context["coverage"]["eligibleRecords"] == 1
            assert context["glucoseCohorts"][0]["meanMmol"] == 6.4
            assert signed_get(client, other_key, other_tokens,
                              "/api/ai/v1/jobs/" + job_id).status_code == 404
    finally:
        ledger.engine.dispose()
