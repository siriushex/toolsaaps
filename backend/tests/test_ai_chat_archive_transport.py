import asyncio
import json
from threading import Event
from types import SimpleNamespace
from uuid import uuid4

import pytest
from fastapi.testclient import TestClient

from app.ai.contextual_chat import ContextualChatWorker, contextual_chat_policy
from app.ai.history_contract import HistoryError
from app.ai.job_ledger import JobLedger
from app.ai.job_service import AiJobService
from app.ai.job_policy import CHAT_POLICY
from app.ai.schemas import ChatRequest
from app.ai_server import create_bound_ai_app
from test_ai_activation import service  # noqa: F401 - shared attestation fixture
from test_ai_contextual_chat import Process
from test_ai_history_store import KEY, OWNER, store
from test_ai_job_transport import (NOW, MutableClock, SyntheticFactory, activate,
                                   key_fingerprint, signed_delete, submit, wait_for_terminal)
from test_ai_native_text import MODEL


@pytest.fixture
def archived(service, tmp_path):
    history, clock, factory = store(tmp_path), MutableClock(), SyntheticFactory()
    ledger = JobLedger(tmp_path / "jobs.sqlite")
    jobs = AiJobService(service[1], service[0].proofs, ledger, factory,
                        clock_ms=clock, chat_archive=history)
    try:
        with TestClient(create_bound_ai_app(service[0], jobs=jobs, clock_ms=clock)) as client:
            yield client, jobs, history, clock, factory
    finally:
        ledger.engine.dispose()


def request(client, key, tokens, text):
    return submit(client, key, tokens, json.dumps(dict(text=text)).encode(),
                  request_id=str(uuid4()), deadline_ms=NOW + 60_000)


def exchanges(history, owner, key, clock):
    return history.chat_context(owner, key_fingerprint(key), now_ms=clock())["exchanges"]


def test_signed_request_and_settled_answer_survive_restart_and_device_isolation(archived, service, tmp_path):
    client, jobs, history, clock, factory = archived
    owner, key, tokens = activate(client, service)
    _, other_key, _ = activate(client, service, owner_id=owner)
    reply = request(client, key, tokens, "Synthetic question")
    assert reply.status_code == 202
    done = wait_for_terminal(client, key, tokens, reply.json()["job_id"])
    assert done.json()["result_available"] is True
    assert factory.stops == factory.runs
    saved = exchanges(store(tmp_path), owner, key, clock)
    assert saved[0]["question"] == "Synthetic question"
    assert saved[0]["answer"] == "synthetic response"
    assert saved[0]["state"] == "SUCCEEDED"
    assert exchanges(history, owner, other_key, clock) == []
    client.portal.call(jobs.close)
    restarted_factory = SyntheticFactory()
    restarted = AiJobService(service[1], service[0].proofs, jobs.ledger, restarted_factory,
                            clock_ms=clock, chat_archive=store(tmp_path))
    client.portal.call(restarted.start)
    try:
        assert exchanges(store(tmp_path), owner, key, clock) == saved
        assert restarted_factory.runs == []
    finally:
        client.portal.call(restarted.close)


@pytest.mark.parametrize("behavior,state", [({"fail": True}, "FAILED"),
                                            ({"malformed": True}, "FAILED"),
                                            ({"stop_receipt": False}, "OUTCOME_NOT_RECORDED")])
def test_failed_or_unconfirmed_execution_never_archives_answer(archived, service, behavior, state):
    client, _, history, clock, factory = archived
    owner, key, tokens = activate(client, service)
    factory.configure("Synthetic failure", **behavior)
    reply = request(client, key, tokens, "Synthetic failure")
    wait_for_terminal(client, key, tokens, reply.json()["job_id"])
    saved = exchanges(history, owner, key, clock)
    assert saved[0]["state"] == state and saved[0]["answer"] is None


def test_cancelled_running_request_preserves_question_but_not_answer(archived, service):
    client, _, history, clock, factory = archived
    owner, key, tokens = activate(client, service)
    started = Event()
    factory.configure("Synthetic pending", block=True, started=started)
    reply = request(client, key, tokens, "Synthetic pending")
    assert started.wait(2)
    cancelled = signed_delete(client, key, tokens, "/api/ai/v1/jobs/" + reply.json()["job_id"])
    assert cancelled.json()["state"] == "CANCELLED"
    saved = exchanges(history, owner, key, clock)
    assert saved[0]["state"] == "CANCELLED" and saved[0]["answer"] is None
    assert len(factory.stops) == 1


def test_queued_cancel_and_expiry_are_archived_without_running(archived, service):
    client, jobs, history, clock, factory = archived
    first_owner, first_key, first_tokens = activate(client, service)
    owner, key, tokens = activate(client, service)
    started = Event()
    factory.configure("Hold global slot", block=True, started=started)
    first = request(client, first_key, first_tokens, "Hold global slot")
    assert started.wait(2)
    second = request(client, key, tokens, "Queued question")
    cancelled = signed_delete(client, key, tokens, "/api/ai/v1/jobs/" + second.json()["job_id"])
    assert cancelled.json()["state"] == "CANCELLED"
    third = request(client, key, tokens, "Expiring question")
    assert third.status_code == 202
    clock.value = NOW + 60_000
    client.portal.call(jobs._expire_volatile)
    states = {row["question"]: row["state"] for row in exchanges(history, owner, key, clock)}
    assert states == {"Queued question": "CANCELLED", "Expiring question": "EXPIRED"}
    assert factory.runs == [first.json()["job_id"]]


def test_acceptance_storage_failure_prevents_dispatch_and_returns_safe_error(archived, service, monkeypatch):
    client, jobs, history, _, factory = archived
    _, key, tokens = activate(client, service)
    def fail(*args, **kwargs):
        raise OSError("Synthetic private storage detail")
    monkeypatch.setattr(history, "accept_chat", fail)
    reply = request(client, key, tokens, "Synthetic question")
    assert reply.status_code == 503
    assert "Synthetic private" not in reply.text
    assert jobs.inference_ready is False and factory.runs == []


def test_result_storage_failure_never_publishes_answer_or_retries(archived, service, monkeypatch):
    client, jobs, history, clock, factory = archived
    owner, key, tokens = activate(client, service)
    def fail(*args, **kwargs):
        raise HistoryError(503, "history_storage_unavailable")
    monkeypatch.setattr(history, "finish_chat", fail)
    reply = request(client, key, tokens, "Synthetic result failure")
    done = wait_for_terminal(client, key, tokens, reply.json()["job_id"])
    assert done.json()["result_available"] is False
    assert "result" not in done.json()
    assert jobs.inference_ready is False
    assert len(factory.runs) == len(factory.stops) == 1
    assert exchanges(history, owner, key, clock)[0]["state"] == "OUTCOME_NOT_RECORDED"


def test_shutdown_still_stops_active_process_when_archive_is_unavailable(archived, service, monkeypatch):
    client, jobs, history, _, factory = archived
    _, key, tokens = activate(client, service)
    started = Event()
    factory.configure("Synthetic shutdown", block=True, started=started)
    request(client, key, tokens, "Synthetic shutdown")
    assert started.wait(2)
    def fail(*args, **kwargs):
        raise OSError("Synthetic failure")
    monkeypatch.setattr(history, "finish_chat", fail)
    client.portal.call(jobs.close)
    assert len(factory.stops) == 1
    assert jobs._active_task is None and jobs._drain_task is None


def test_next_contextual_turn_includes_previous_server_exchange_not_current_question(service, tmp_path):
    history, clock, processes = store(tmp_path), MutableClock(), []
    ledger = JobLedger(tmp_path / "jobs.sqlite")
    def factory(work):
        process = Process()
        processes.append(process)
        return ContextualChatWorker(work, history, process, clock_ms=clock)
    jobs = AiJobService(service[1], service[0].proofs, ledger, factory, clock_ms=clock,
                        policy=contextual_chat_policy(MODEL), chat_archive=history)
    try:
        with TestClient(create_bound_ai_app(service[0], jobs=jobs, clock_ms=clock)) as client:
            _, key, tokens = activate(client, service)
            first = request(client, key, tokens, "Synthetic first question")
            wait_for_terminal(client, key, tokens, first.json()["job_id"])
            second = request(client, key, tokens, "Synthetic follow-up")
            done = wait_for_terminal(client, key, tokens, second.json()["job_id"])
            assert done.json()["result_available"] is True
            prompt = json.loads(processes[-1].transport.sent[-1]["params"]["input"][0]["text"])
            assert prompt["question"] == "Synthetic follow-up"
            previous = prompt["serverConversation"]["exchanges"]
            assert len(previous) == 1
            assert previous[0]["question"] == "Synthetic first question"
            assert previous[0]["answer"] == "Synthetic answer"
            assert prompt["serverConversation"]["origin"] == "SERVER_ARCHIVE"
    finally:
        ledger.engine.dispose()


def test_disconnected_submit_drains_archive_write_without_duplicate_dispatch(service, tmp_path, monkeypatch):
    history, clock, factory = store(tmp_path), MutableClock(), SyntheticFactory()
    ledger = JobLedger(tmp_path / "jobs.sqlite")
    entered, release = Event(), Event()
    original = history.accept_chat
    def slow(*args, **kwargs):
        entered.set()
        assert release.wait(3)
        return original(*args, **kwargs)
    monkeypatch.setattr(history, "accept_chat", slow)
    monkeypatch.setattr(service[1], "authorize_attested_session", lambda **kwargs: None)
    jobs = AiJobService(service[1], service[0].proofs, ledger, factory, clock_ms=clock, chat_archive=history)
    device = SimpleNamespace(owner_id=OWNER, key_fingerprint=KEY, session_id=str(uuid4()))
    async def scenario():
        await jobs.start()
        try:
            args = dict(request_id=str(uuid4()), deadline_ms=NOW + 60_000,
                        body=b'{"text":"Synthetic disconnect"}', now_ms=clock())
            task = asyncio.create_task(jobs.submit(device, **args))
            assert await asyncio.to_thread(entered.wait, 1)
            task.cancel()
            await asyncio.sleep(0)
            task.cancel()
            assert not task.done()
            release.set()
            with pytest.raises(asyncio.CancelledError):
                await task
            receipt = await jobs.submit(device, **args)
            for _ in range(200):
                current = await jobs.get(device, receipt["job_id"], now_ms=clock())
                if current["result_available"]:
                    break
                await asyncio.sleep(0.005)
            assert current["result_available"]
            assert len(factory.runs) == 1
            assert len(history.chat_context(OWNER, KEY, now_ms=clock())["exchanges"]) == 1
        finally:
            release.set()
            await jobs.close()
    try:
        asyncio.run(scenario())
    finally:
        release.set()
        ledger.engine.dispose()


def test_restart_keeps_unresolved_request_without_inventing_answer_or_replaying(service, tmp_path):
    history, clock, factory = store(tmp_path), MutableClock(), SyntheticFactory()
    ledger = JobLedger(tmp_path / "jobs.sqlite")
    entry = ledger.reserve_once(owner_id=OWNER, request_id=str(uuid4()), kind="CHAT", digest="d4" * 32,
        route_revision=CHAT_POLICY.route_revision, deadline_ms=NOW + 60_000, now_ms=clock(),
        session_id=str(uuid4()), key_hash=KEY)[0]
    history.accept_chat(entry, ChatRequest("Synthetic interrupted question"), CHAT_POLICY, now_ms=clock())
    jobs = AiJobService(service[1], service[0].proofs, ledger, factory, clock_ms=clock, chat_archive=history)
    async def scenario():
        await jobs.start()
        try:
            assert ledger.get(OWNER, entry.id).state == "UNKNOWN"
            saved = history.chat_context(OWNER, KEY, now_ms=clock())["exchanges"][0]
            assert saved["state"] == "OUTCOME_NOT_RECORDED" and saved["answer"] is None
            assert factory.runs == []
        finally:
            await jobs.close()
    try:
        asyncio.run(scenario())
    finally:
        ledger.engine.dispose()


def test_result_cache_expiry_does_not_delete_long_term_archive(archived, service):
    client, jobs, history, clock, _ = archived
    owner, key, tokens = activate(client, service)
    reply = request(client, key, tokens, "Synthetic retained question")
    wait_for_terminal(client, key, tokens, reply.json()["job_id"])
    clock.value += CHAT_POLICY.result_ttl_ms + 1
    client.portal.call(jobs._expire_volatile)
    assert not jobs._results
    assert exchanges(history, owner, key, clock)[0]["answer"] == "synthetic response"


def test_revoked_queued_request_is_archived_without_dispatch(archived, service):
    client, jobs, history, clock, factory = archived
    _, key, tokens = activate(client, service)
    owner, other_key, other_tokens = activate(client, service)
    started = Event()
    factory.configure("Synthetic blocker", block=True, started=started)
    first = request(client, key, tokens, "Synthetic blocker")
    assert started.wait(2)
    request(client, other_key, other_tokens, "Synthetic revoked")
    service[1].revoke_owner(owner_id=owner, now_ms=clock())
    signed_delete(client, key, tokens, "/api/ai/v1/jobs/" + first.json()["job_id"])
    async def wait():
        for _ in range(200):
            await jobs._collect_active()
            await jobs._dispatch_next()
            if exchanges(history, owner, other_key, clock)[0]["state"] == "CANCELLED":
                return
            await asyncio.sleep(0.005)
        raise AssertionError("archive not settled")
    client.portal.call(wait)
    assert factory.run_texts == ["Synthetic blocker"]


def test_success_not_published_before_archive_commit_and_late_cancel_cannot_erase_it(archived, service, monkeypatch):
    client, jobs, history, clock, factory = archived
    owner, key, tokens = activate(client, service)
    bound = service[1].authenticate_attested(tokens["access_token"],
                                            key_fingerprint=key_fingerprint(key), now_ms=clock())
    entered, release = Event(), Event()
    original = history.finish_chat
    def slow(*args, **kwargs):
        entered.set()
        assert release.wait(3)
        return original(*args, **kwargs)
    monkeypatch.setattr(history, "finish_chat", slow)
    try:
        reply = request(client, key, tokens, "Synthetic saving result")
        assert entered.wait(2)
        job_id = reply.json()["job_id"]
        assert factory.stops == [job_id]
        pending = client.portal.call(lambda: jobs.get(bound, job_id, now_ms=clock()))
        assert pending["state"] == "RUNNING" and pending["result_available"] is False
        assert exchanges(history, owner, key, clock)[0]["answer"] is None
        late_cancel = signed_delete(client, key, tokens, "/api/ai/v1/jobs/" + job_id)
        assert late_cancel.json()["state"] == "SUCCEEDED"
    finally:
        release.set()
    done = wait_for_terminal(client, key, tokens, job_id)
    assert done.json()["result_available"] is True
    assert exchanges(history, owner, key, clock)[0]["answer"] == "synthetic response"


def test_cancelled_shutdown_drains_archive_and_clears_owned_tasks(service, tmp_path, monkeypatch):
    history, clock, factory = store(tmp_path), MutableClock(), SyntheticFactory()
    ledger = JobLedger(tmp_path / "jobs.sqlite")
    entered, release, started = Event(), Event(), Event()
    original = history.finish_chat
    def slow(*args, **kwargs):
        entered.set()
        assert release.wait(3)
        return original(*args, **kwargs)
    monkeypatch.setattr(history, "finish_chat", slow)
    monkeypatch.setattr(service[1], "authorize_attested_session", lambda **kwargs: None)
    factory.configure("Synthetic closing", block=True, started=started)
    jobs = AiJobService(service[1], service[0].proofs, ledger, factory, clock_ms=clock, chat_archive=history)
    device = SimpleNamespace(owner_id=OWNER, key_fingerprint=KEY, session_id=str(uuid4()))
    async def scenario():
        await jobs.start()
        try:
            await jobs.submit(device, request_id=str(uuid4()), deadline_ms=NOW + 60_000,
                              body=b'{"text":"Synthetic closing"}', now_ms=clock())
            assert await asyncio.to_thread(started.wait, 1)
            closing = asyncio.create_task(jobs.close())
            assert await asyncio.to_thread(entered.wait, 1)
            closing.cancel()
            await asyncio.sleep(0)
            assert not closing.done()
            assert len(factory.stops) == 1
            release.set()
            with pytest.raises(asyncio.CancelledError):
                await closing
            assert jobs._drain_task is None and jobs._active_task is None
            assert history.chat_context(OWNER, KEY, now_ms=clock())["exchanges"][0]["state"] == "CANCELLED"
        finally:
            release.set()
            await jobs.close()
    try:
        asyncio.run(scenario())
    finally:
        release.set()
        ledger.engine.dispose()
