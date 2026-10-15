import asyncio
import json
import os
import socket
import tempfile
from pathlib import Path
from threading import Event
from uuid import uuid4

import pytest
from fastapi.testclient import TestClient

from app.ai.contextual_chat import ContextualChatWorker, contextual_chat_policy
from app.ai.history_service import HistoryService
from app.ai.job_ledger import JobLedger
from app.ai.job_service import AiJobService
from app.ai.native_text import TextSessionPolicy
from app.ai.text_supervisor import PeerIdentity, SupervisorTextRunner, TextSupervisor
from app.ai_server import create_bound_ai_app
from test_ai_activation import service  # noqa: F401 - shared synthetic attestation
from test_ai_contextual_chat import Process
from test_ai_history_contract import body, record
from test_ai_history_store import store
from test_ai_history_transport import signed_post
from test_ai_job_transport import (
    NOW, MutableClock, activate, key_fingerprint, signed_delete, signed_get,
    submit, wait_for_terminal,
)
from test_ai_native_text import MODEL


@pytest.fixture
def supervised(service, tmp_path):
    clock, history = MutableClock(), store(tmp_path)
    ledger = JobLedger(tmp_path / "jobs.sqlite")
    policy = contextual_chat_policy(MODEL)
    text_policy = TextSessionPolicy(policy.model, policy.system_prompt)
    peer = PeerIdentity(os.getuid(), os.getgid())
    processes, configuration = [], {}
    def process_factory():
        if configuration.get("pending"):
            class Waiting(Process):
                async def start(self, policy):
                    self.started.append(policy)
                    configuration["started"].set()
                    await asyncio.Event().wait()
            process = Waiting()
        else:
            process = Process(stop_receipt=configuration.get("stop_receipt", True))
        processes.append(process)
        return process
    with tempfile.TemporaryDirectory(prefix="personal-ai-", dir="/tmp") as directory:
        path = str(Path(directory) / "text.sock")
        listener = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
        listener.bind(path)
        listener.listen(2)
        supervisor = TextSupervisor(text_policy, peer, process_factory)
        def factory(job):
            return ContextualChatWorker(job, history, clock_ms=clock,
                runner=SupervisorTextRunner(path, peer, text_policy))
        jobs = AiJobService(service[1], service[0].proofs, ledger, factory,
                            policy=policy, clock_ms=clock, chat_archive=history)
        try:
            with TestClient(create_bound_ai_app(service[0], jobs=jobs, clock_ms=clock)) as client:
                client.portal.call(supervisor.start, listener)
                try:
                    yield client, jobs, history, clock, processes, configuration, supervisor
                finally:
                    client.portal.call(jobs.close)
                    client.portal.call(supervisor.close)
        finally:
            ledger.engine.dispose()


def send(client, key, tokens, text="Sample question", request_id=None):
    return submit(client, key, tokens, json.dumps({"text": text}).encode(),
                  request_id=request_id or str(uuid4()), deadline_ms=NOW + 60_000)


def test_signed_api_to_socket_to_archive_followup_and_device_isolation(supervised, service):
    client, _, history, clock, processes, _, _ = supervised
    owner, key, tokens = activate(client, service)
    _, other_key, other_tokens = activate(client, service, owner_id=owner)
    history.append(owner, key_fingerprint(key), body([record(eventAtMs=NOW - 1000,
        observedAtMs=NOW)]), now_ms=NOW)
    history.append(owner, key_fingerprint(other_key), body([record(eventAtMs=NOW - 1000,
        observedAtMs=NOW, data={"glucoseMmol": 99.0})]), now_ms=NOW)
    request_id = str(uuid4())
    first = send(client, key, tokens, request_id=request_id)
    assert first.status_code == 202
    job_id = first.json()["job_id"]
    assert wait_for_terminal(client, key, tokens, job_id).json()["result"] == {"text": "Synthetic answer"}
    assert processes[0].stops == 1
    assert send(client, key, tokens, request_id=request_id).json()["job_id"] == job_id
    assert len(processes) == 1
    assert signed_get(client, other_key, other_tokens, "/api/ai/v1/jobs/" + job_id).status_code == 404
    saved = history.chat_context(owner, key_fingerprint(key), now_ms=clock())["exchanges"]
    assert saved[0]["state"] == "SUCCEEDED" and saved[0]["answer"] == "Synthetic answer"
    assert history.chat_context(owner, key_fingerprint(other_key), now_ms=clock())["exchanges"] == []
    second = send(client, key, tokens, "Follow up")
    assert wait_for_terminal(client, key, tokens, second.json()["job_id"]).json()["state"] == "SUCCEEDED"
    context = json.loads(processes[1].transport.sent[-1]["params"]["input"][0]["text"])
    assert context["deviceHistory"]["glucoseCohorts"][0]["meanMmol"] == 6.4
    assert context["serverConversation"]["exchanges"][0]["answer"] == "Synthetic answer"
    # The existing signed history route reads the exact stored answer, independently of the job cache.
    service_history = HistoryService(service[1], service[0].proofs, history, owner_id=owner)
    with TestClient(create_bound_ai_app(service[0], history=service_history, clock_ms=clock)) as reader:
        response = signed_post(reader, key, tokens, "conversations",
            dict(fromMs=NOW - 1, untilMs=clock() + 1, cursor=None, limit=20))
        assert response.status_code == 200
        assert len(response.json()["records"]) == 2
        assert response.json()["records"][0]["answer"] == "Synthetic answer"


def test_signed_cancel_reaches_supervisor_and_archives_cancelled(supervised, service):
    client, _, history, clock, processes, configuration, _ = supervised
    owner, key, tokens = activate(client, service)
    started = Event()
    configuration.update(pending=True, started=started)
    reply = send(client, key, tokens)
    assert started.wait(2)
    cancelled = signed_delete(client, key, tokens, "/api/ai/v1/jobs/" + reply.json()["job_id"])
    assert cancelled.json()["state"] == "CANCELLED"
    saved = history.chat_context(owner, key_fingerprint(key), now_ms=clock())["exchanges"]
    assert saved[0]["state"] == "CANCELLED" and saved[0]["answer"] is None
    assert len(processes) == processes[0].stops == 1


def test_unconfirmed_stop_retains_ledger_slot_and_no_archived_success(supervised, service):
    client, jobs, history, clock, processes, configuration, _ = supervised
    configuration["stop_receipt"] = False
    owner, key, tokens = activate(client, service)
    reply = send(client, key, tokens)
    result = wait_for_terminal(client, key, tokens, reply.json()["job_id"]).json()
    assert result["state"] == "UNKNOWN" and result["result_available"] is False
    assert jobs.ledger.get(owner, reply.json()["job_id"]).state == "RUNNING"
    assert jobs.inference_ready is False
    saved = history.chat_context(owner, key_fingerprint(key), now_ms=clock())["exchanges"]
    assert saved[0]["state"] == "OUTCOME_NOT_RECORDED" and saved[0]["answer"] is None
    assert len(processes) == processes[0].stops == 1
