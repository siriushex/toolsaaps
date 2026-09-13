import asyncio
import base64
import hashlib
import json
import time
from dataclasses import asdict, dataclass, replace
from threading import Event
from types import SimpleNamespace
from uuid import uuid4

import pytest
from cryptography.hazmat.primitives import serialization
from fastapi.testclient import TestClient

from app.ai_server import create_bound_ai_app
from test_ai_activation import NOW, device, service, signed


class MutableClock:
    def __init__(self, value=NOW + 2):
        self.value = value

    def __call__(self):
        return self.value


class AdvancingClock:
    def __init__(self, value=NOW + 2):
        self.value = value
        self.started = time.monotonic()

    def __call__(self):
        return self.value + int((time.monotonic() - self.started) * 1000)


@dataclass
class SyntheticWorker:
    work: object
    factory: object
    stop_receipt: object = True
    fail: bool = False

    @property
    def route_revision(self):
        return self.work.route_revision

    @property
    def request_digest(self):
        return self.work.request_digest

    async def run(self):
        self.factory.runs.append(self.work.job_id)
        self.factory.run_texts.append(self.work.request.text)
        behavior = self.factory.behaviors.get(self.work.request.text, {})
        started = behavior.get("started")
        if started is not None:
            started.set()
        if behavior.get("block"):
            await asyncio.Future()
        if self.fail or behavior.get("fail"):
            raise RuntimeError("private synthetic provider detail")
        if behavior.get("malformed"):
            return {"text": "synthetic response", "tool": "forbidden"}
        if behavior.get("oversized"):
            return {"text": "x" * 8193}
        return {"text": "synthetic response"}

    async def stop_and_confirm(self):
        self.factory.stops.append(self.work.job_id)
        return self.factory.behaviors.get(self.work.request.text, {}).get(
            "stop_receipt", self.stop_receipt)


class SyntheticFactory:
    def __init__(self):
        self.works = []
        self.runs = []
        self.run_texts = []
        self.stops = []
        self.behaviors = {}

    def configure(self, text, **behavior):
        self.behaviors[text] = behavior

    def __call__(self, work):
        self.works.append(work)
        return SyntheticWorker(work, self)


def key_fingerprint(key):
    return hashlib.sha256(key.public_key().public_bytes(
        serialization.Encoding.DER,
        serialization.PublicFormat.SubjectPublicKeyInfo,
    )).hexdigest()


def proof_headers(proof, key, *, access_token=None, request_id=None, deadline_ms=None):
    headers = {
        "X-Copilot-Signature": proof["signature"],
        "X-Copilot-Issued-Ms": str(proof["issued_ms"]),
        "X-Copilot-Nonce": proof["nonce"],
        "X-Copilot-Key": key_fingerprint(key),
    }
    if access_token is not None:
        headers["Authorization"] = "Bearer " + access_token
    if request_id is not None:
        headers["X-Copilot-Request-Id"] = request_id
    if deadline_ms is not None:
        headers["X-Copilot-Deadline-Ms"] = str(deadline_ms)
    return headers


def activate(client, service, *, owner_id=None):
    impl, auth, *_ = service
    owner_id = owner_id or str(uuid4())
    code = auth.issue_subscription(owner_id=owner_id, duration_days=30, now_ms=NOW)
    request_id = str(uuid4())
    started = client.post("/api/ai/v1/activation/start",
                          json={"code": code, "request_id": request_id})
    assert started.status_code == 200
    key, chain = device(service, SimpleNamespace(**started.json()))
    body = json.dumps({
        "request_id": request_id,
        "certificate_chain": [base64.b64encode(item).decode() for item in chain],
    }, separators=(",", ":")).encode()
    path = "/api/ai/v1/activation/complete"
    response = client.post(path, content=body, headers={"Content-Type": "application/json"} |
        proof_headers(signed(key, path=path, body=body, credential=request_id), key))
    assert response.status_code == 200
    return owner_id, key, response.json()


def signed_get(client, key, tokens, path, *, now=NOW + 2):
    proof = signed(key, method="GET", path=path, body=b"",
                   credential=tokens["access_token"], now=now)
    return client.get(path, headers=proof_headers(proof, key,
                      access_token=tokens["access_token"]))


def signed_delete(client, key, tokens, path, *, now=NOW + 2):
    proof = signed(key, method="DELETE", path=path, body=b"",
                   credential=tokens["access_token"], now=now)
    return client.delete(path, headers=proof_headers(proof, key,
                         access_token=tokens["access_token"]))


def submit(client, key, tokens, body, *, request_id, deadline_ms, proof_body=None,
           proof_key=None, proof_path="/api/ai/v1/jobs", now=NOW + 2):
    from app.ai.job_policy import submit_proof_credential

    credential = submit_proof_credential(tokens["access_token"], request_id=request_id,
                                         deadline_ms=deadline_ms)
    proof = signed(proof_key or key, path=proof_path, body=proof_body or body,
                   credential=credential, now=now)
    return client.post("/api/ai/v1/jobs", content=body,
        headers={"Content-Type": "application/json"} | proof_headers(
            proof, key, access_token=tokens["access_token"], request_id=request_id,
            deadline_ms=deadline_ms))


def refresh_tokens(service, key, tokens, *, now=NOW + 3):
    request_id = str(uuid4())
    body = json.dumps({"request_id": request_id,
                       "refresh_token": tokens["refresh_token"]},
                      separators=(",", ":")).encode()
    fingerprint = hashlib.sha256(key.public_key().public_bytes(
        serialization.Encoding.DER,
        serialization.PublicFormat.SubjectPublicKeyInfo,
    )).hexdigest()
    refreshed = service[0].refresh(body=body, key_fingerprint=fingerprint,
        now_ms=now, **signed(key, path="/api/ai/v1/session/refresh", body=body,
                            credential=tokens["refresh_token"], now=now))
    return asdict(refreshed)


def wait_for_terminal(client, key, tokens, job_id, *, now=NOW + 2):
    path = f"/api/ai/v1/jobs/{job_id}"
    for _ in range(100):
        response = signed_get(client, key, tokens, path, now=now)
        assert response.status_code == 200
        if response.json()["state"] not in {"QUEUED", "RUNNING", "CANCEL_REQUESTED"}:
            return response
        time.sleep(0.01)
    raise AssertionError("synthetic job did not reach terminal state")


@pytest.fixture
def transport(service, tmp_path):
    from app.ai.job_ledger import JobLedger
    from app.ai.job_service import AiJobService

    clock = MutableClock()
    factory = SyntheticFactory()
    ledger = JobLedger(tmp_path / "jobs.sqlite")
    jobs = AiJobService(service[1], service[0].proofs, ledger, factory, clock_ms=clock)
    with TestClient(create_bound_ai_app(service[0], jobs=jobs, clock_ms=clock)) as client:
        yield client, jobs, ledger, factory, clock
    ledger.engine.dispose()


def test_signed_chat_submit_retry_get_and_capabilities(transport, service):
    client, _, _, factory, _ = transport
    _, key, tokens = activate(client, service)
    capability_path = "/api/ai/v1/capabilities"
    capabilities = signed_get(client, key, tokens, capability_path)
    assert capabilities.status_code == 200
    assert capabilities.json() == {
        "revision": "server-codex-chat-r1a.1",
        "inference_enabled": True,
        "task_kinds": ["CHAT"],
        "input_modalities": ["TEXT"],
        "output_modalities": ["TEXT"],
        "max_text_chars": 4096,
        "max_text_bytes": 8192,
        "max_result_chars": 8192,
        "max_result_bytes": 16384,
        "max_response_bytes": 65536,
        "max_deadline_ms": 900000,
    }

    body = b'{"text":"synthetic hello"}'
    request_id = str(uuid4())
    deadline_ms = NOW + 120_000
    accepted = submit(client, key, tokens, body, request_id=request_id,
                      deadline_ms=deadline_ms)
    assert accepted.status_code == 202
    receipt = accepted.json()
    assert receipt["request_id"] == request_id
    assert receipt["kind"] == "CHAT"
    assert receipt["deadline_ms"] == deadline_ms

    recovered = submit(client, key, tokens, body, request_id=request_id,
                       deadline_ms=deadline_ms)
    assert recovered.status_code == 202
    assert recovered.json()["job_id"] == receipt["job_id"]
    finished = wait_for_terminal(client, key, tokens, receipt["job_id"])
    assert finished.json()["state"] == "SUCCEEDED"
    assert finished.json()["result"] == {"text": "synthetic response"}
    assert factory.runs == factory.stops == [receipt["job_id"]]
    assert len(factory.works) == 1
    work = factory.works[0]
    assert work.request.text == "synthetic hello"
    assert work.policy.model == "trusted-server-model-v1"
    assert work.policy.allow_tools is work.policy.allow_actions is False


def test_job_proof_tampering_replay_and_idempotency_conflict_are_closed(transport, service):
    client, _, _, _, _ = transport
    _, key, tokens = activate(client, service)
    request_id = str(uuid4())
    deadline_ms = NOW + 120_000
    body = b'{"text":"synthetic original"}'

    wrong = submit(client, key, tokens, body, request_id=request_id,
                   deadline_ms=deadline_ms, proof_body=b'{"text":"changed"}')
    assert wrong.status_code == 401
    other_key = device(service, SimpleNamespace(challenge=base64.b64encode(b"x" * 32).decode()))[0]
    wrong_signer = submit(client, key, tokens, body, request_id=request_id,
                          deadline_ms=deadline_ms, proof_key=other_key)
    assert wrong_signer.status_code == 401
    wrong_path = submit(client, key, tokens, body, request_id=request_id,
                        deadline_ms=deadline_ms, proof_path="/api/ai/v1/session/status")
    assert wrong_path.status_code == 401

    accepted = submit(client, key, tokens, body, request_id=request_id,
                      deadline_ms=deadline_ms)
    assert accepted.status_code == 202
    changed = submit(client, key, tokens, b'{"text":"synthetic changed"}',
                     request_id=request_id, deadline_ms=deadline_ms)
    assert changed.status_code == 409
    changed_deadline = submit(client, key, tokens, body, request_id=request_id,
                              deadline_ms=deadline_ms + 1)
    assert changed_deadline.status_code == 409

    fresh_id = str(uuid4())
    from app.ai.job_policy import submit_proof_credential
    credential = submit_proof_credential(tokens["access_token"], request_id=fresh_id,
                                         deadline_ms=deadline_ms)
    proof = signed(key, path="/api/ai/v1/jobs", body=body, credential=credential)
    headers = {"Content-Type": "application/json"} | proof_headers(
        proof, key, access_token=tokens["access_token"], request_id=fresh_id,
        deadline_ms=deadline_ms)
    assert client.post("/api/ai/v1/jobs", content=body, headers=headers).status_code == 202
    assert client.post("/api/ai/v1/jobs", content=body, headers=headers).status_code == 401


@pytest.mark.parametrize("body", [
    b'{"text":"a","text":"b"}',
    b'{"text":"ok","model":"client"}',
    b'{"text":NaN}',
    b'{"text":{"nested":true}}',
    b'{"text":',
])
def test_job_body_validation_is_generic(transport, service, body):
    client, _, _, _, _ = transport
    _, key, tokens = activate(client, service)
    response = submit(client, key, tokens, body, request_id=str(uuid4()),
                      deadline_ms=NOW + 120_000)
    assert response.status_code == 400
    assert response.json() == {"error": "invalid_request"}
    assert response.headers["cache-control"] == "no-store"


def test_default_factory_remains_closed_without_jobs(service):
    with TestClient(create_bound_ai_app(service[0], clock_ms=lambda: NOW + 2)) as client:
        _, key, tokens = activate(client, service)
        assert signed_get(client, key, tokens, "/api/ai/v1/capabilities").status_code == 404
        status = signed_get(client, key, tokens, "/api/ai/v1/session/status")
        assert status.status_code == 200
        assert status.json()["inference_enabled"] is False


def test_cancel_and_worker_failures_are_bounded_and_generic(transport, service):
    client, _, _, factory, _ = transport
    _, key, tokens = activate(client, service)
    started = Event()
    factory.configure("synthetic blocking", block=True, started=started)
    body = b'{"text":"synthetic blocking"}'
    accepted = submit(client, key, tokens, body, request_id=str(uuid4()),
                      deadline_ms=NOW + 120_000)
    assert accepted.status_code == 202
    assert started.wait(2)
    job_id = accepted.json()["job_id"]
    cancelled = signed_delete(client, key, tokens, f"/api/ai/v1/jobs/{job_id}")
    assert cancelled.status_code == 200
    assert cancelled.json()["state"] == "CANCELLED"
    assert factory.stops == [job_id]

    for text in ("synthetic failure", "synthetic malformed output",
                 "synthetic oversized output"):
        factory.configure(text, fail=text.endswith("failure"),
                          malformed=text.endswith("malformed output"),
                          oversized=text.endswith("oversized output"))
        body = json.dumps({"text": text}, separators=(",", ":")).encode()
        response = submit(client, key, tokens, body, request_id=str(uuid4()),
                          deadline_ms=NOW + 120_000)
        assert response.status_code == 202
        finished = wait_for_terminal(client, key, tokens, response.json()["job_id"])
        assert finished.json()["state"] == "FAILED"
        assert finished.json()["result_available"] is False
        assert "private" not in finished.text


def test_unconfirmed_stop_stays_unknown_and_blocks_global_capacity(transport, service):
    client, _, ledger, factory, _ = transport
    _, key, tokens = activate(client, service)
    bound = service[1].authenticate_attested(tokens["access_token"],
        key_fingerprint=key_fingerprint(key), now_ms=NOW + 2)
    started = Event()
    factory.configure("synthetic unknown stop", block=True, started=started,
                      stop_receipt=False)
    first = submit(client, key, tokens, b'{"text":"synthetic unknown stop"}',
                   request_id=str(uuid4()), deadline_ms=NOW + 120_000)
    assert first.status_code == 202 and started.wait(2)
    job_id = first.json()["job_id"]
    cancelled = signed_delete(client, key, tokens, f"/api/ai/v1/jobs/{job_id}")
    assert cancelled.status_code == 200
    assert cancelled.json()["state"] == "UNKNOWN"
    assert ledger.get(bound.owner_id, job_id).state == "CANCEL_REQUESTED"

    _, next_key, next_tokens = activate(client, service)
    queued = submit(client, next_key, next_tokens, b'{"text":"synthetic queued"}',
                    request_id=str(uuid4()), deadline_ms=NOW + 120_000)
    assert queued.status_code == 202
    time.sleep(0.05)
    assert "synthetic queued" not in factory.run_texts
    assert ledger.get(service[1].authenticate_attested(
        next_tokens["access_token"],
        key_fingerprint=key_fingerprint(next_key),
        now_ms=NOW + 2).owner_id, queued.json()["job_id"]).state == "QUEUED"


def test_cross_owner_key_scope_and_revoked_result_read_are_closed(transport, service):
    client, _, _, _, _ = transport
    owner, key, tokens = activate(client, service)
    response = submit(client, key, tokens, b'{"text":"synthetic scoped"}',
                      request_id=str(uuid4()), deadline_ms=NOW + 120_000)
    finished = wait_for_terminal(client, key, tokens, response.json()["job_id"])
    path = f"/api/ai/v1/jobs/{response.json()['job_id']}"
    other_owner, other_key, other_tokens = activate(client, service)
    assert other_owner != owner
    assert signed_get(client, other_key, other_tokens, path).status_code == 404
    assert signed_delete(client, other_key, other_tokens, path).status_code == 404

    wrong_key_proof = signed(other_key, method="GET", path=path, body=b"",
                             credential=tokens["access_token"])
    wrong_key = client.get(path, headers=proof_headers(
        wrong_key_proof, other_key, access_token=tokens["access_token"]))
    assert wrong_key.status_code == 401

    service[1].revoke_owner(owner_id=owner, now_ms=NOW + 3)
    assert signed_get(client, key, tokens, path, now=NOW + 3).status_code == 401
    assert finished.json()["result"] == {"text": "synthetic response"}


def test_owner_quota_and_idempotency_span_sessions_but_results_remain_key_scoped(
        transport, service):
    client, _, _, factory, _ = transport
    owner, first_key, first_tokens = activate(client, service)
    started = Event()
    factory.configure("synthetic owner hold", block=True, started=started)
    request_id = str(uuid4())
    body = b'{"text":"synthetic owner hold"}'
    first = submit(client, first_key, first_tokens, body, request_id=request_id,
                   deadline_ms=NOW + 120_000)
    assert first.status_code == 202 and started.wait(2)
    path = f"/api/ai/v1/jobs/{first.json()['job_id']}"

    _, second_key, second_tokens = activate(client, service, owner_id=owner)
    assert signed_get(client, second_key, second_tokens, path).status_code == 404
    assert signed_delete(client, second_key, second_tokens, path).status_code == 404
    busy = submit(client, second_key, second_tokens,
                  b'{"text":"synthetic same owner other session"}',
                  request_id=str(uuid4()), deadline_ms=NOW + 120_000)
    assert busy.status_code == 429
    assert busy.json() == {"error": "owner_busy"}

    assert signed_delete(client, first_key, first_tokens, path).json()["state"] == "CANCELLED"
    rebound = submit(client, second_key, second_tokens, body, request_id=request_id,
                     deadline_ms=NOW + 120_000)
    assert rebound.status_code == 409
    assert rebound.json() == {"error": "request_conflict"}


def test_refresh_does_not_cancel_same_grant_before_dispatch(transport, service):
    client, _, _, factory, clock = transport
    _, first_key, first_tokens = activate(client, service)
    started = Event()
    factory.configure("synthetic hold queue", block=True, started=started)
    first = submit(client, first_key, first_tokens, b'{"text":"synthetic hold queue"}',
                   request_id=str(uuid4()), deadline_ms=NOW + 120_000)
    assert first.status_code == 202 and started.wait(2)

    _, key, old_tokens = activate(client, service)
    queued = submit(client, key, old_tokens, b'{"text":"synthetic after refresh"}',
                    request_id=str(uuid4()), deadline_ms=NOW + 120_000)
    assert queued.status_code == 202
    fresh_tokens = refresh_tokens(service, key, old_tokens)
    clock.value = NOW + 3
    assert signed_delete(client, first_key, first_tokens,
                         f"/api/ai/v1/jobs/{first.json()['job_id']}").status_code == 200
    finished = wait_for_terminal(client, key, fresh_tokens, queued.json()["job_id"],
                                 now=clock.value)
    assert finished.json()["state"] == "SUCCEEDED"
    assert "synthetic after refresh" in factory.run_texts


def test_revocation_and_deadline_are_rechecked_before_dispatch(transport, service):
    client, _, ledger, factory, clock = transport
    _, first_key, first_tokens = activate(client, service)
    started = Event()
    factory.configure("synthetic dispatch hold", block=True, started=started)
    first = submit(client, first_key, first_tokens,
                   b'{"text":"synthetic dispatch hold"}', request_id=str(uuid4()),
                   deadline_ms=NOW + 120_000)
    assert first.status_code == 202 and started.wait(2)

    revoked_owner, revoked_key, revoked_tokens = activate(client, service)
    revoked_bound = service[1].authenticate_attested(
        revoked_tokens["access_token"],
        key_fingerprint=key_fingerprint(revoked_key), now_ms=NOW + 2)
    revoked = submit(client, revoked_key, revoked_tokens,
                     b'{"text":"synthetic revoked queued"}', request_id=str(uuid4()),
                     deadline_ms=NOW + 120_000)
    assert revoked.status_code == 202
    service[1].revoke_owner(owner_id=revoked_owner, now_ms=NOW + 3)

    _, deadline_key, deadline_tokens = activate(client, service)
    expired = submit(client, deadline_key, deadline_tokens,
                     b'{"text":"synthetic deadline queued"}', request_id=str(uuid4()),
                     deadline_ms=NOW + 3)
    assert expired.status_code == 202
    deadline_bound = service[1].authenticate_attested(
        deadline_tokens["access_token"],
        key_fingerprint=key_fingerprint(deadline_key), now_ms=NOW + 2)
    clock.value = NOW + 4
    assert signed_delete(client, first_key, first_tokens,
        f"/api/ai/v1/jobs/{first.json()['job_id']}", now=clock.value).status_code == 200

    for _ in range(100):
        if (ledger.get(revoked_bound.owner_id, revoked.json()["job_id"]).state == "CANCELLED"
                and ledger.get(deadline_bound.owner_id, expired.json()["job_id"]).state == "EXPIRED"):
            break
        time.sleep(0.01)
    assert ledger.get(revoked_bound.owner_id, revoked.json()["job_id"]).state == "CANCELLED"
    assert ledger.get(deadline_bound.owner_id, expired.json()["job_id"]).state == "EXPIRED"
    assert "synthetic revoked queued" not in factory.run_texts
    assert "synthetic deadline queued" not in factory.run_texts


def test_fifo_waiting_and_owner_queue_bounds(transport, service):
    client, _, _, factory, _ = transport
    _, key, tokens = activate(client, service)
    started = Event()
    factory.configure("synthetic fifo hold", block=True, started=started)
    first = submit(client, key, tokens, b'{"text":"synthetic fifo hold"}',
                   request_id=str(uuid4()), deadline_ms=NOW + 120_000)
    assert first.status_code == 202 and started.wait(2)
    same_owner = submit(client, key, tokens, b'{"text":"synthetic same owner"}',
                        request_id=str(uuid4()), deadline_ms=NOW + 120_000)
    assert same_owner.status_code == 429
    assert same_owner.json() == {"error": "owner_busy"}

    waiting = []
    for index in range(5):
        _, item_key, item_tokens = activate(client, service)
        text = f"synthetic fifo {index}"
        body = json.dumps({"text": text}, separators=(",", ":")).encode()
        accepted = submit(client, item_key, item_tokens, body,
                          request_id=str(uuid4()), deadline_ms=NOW + 120_000)
        assert accepted.status_code == 202
        waiting.append((text, item_key, item_tokens, accepted.json()["job_id"]))
    _, overflow_key, overflow_tokens = activate(client, service)
    overflow = submit(client, overflow_key, overflow_tokens,
                      b'{"text":"synthetic overflow queue"}', request_id=str(uuid4()),
                      deadline_ms=NOW + 120_000)
    assert overflow.status_code == 429
    assert overflow.json() == {"error": "queue_full"}

    assert signed_delete(client, key, tokens,
        f"/api/ai/v1/jobs/{first.json()['job_id']}").status_code == 200
    for _, item_key, item_tokens, job_id in waiting:
        assert wait_for_terminal(client, item_key, item_tokens, job_id).json()["state"] == "SUCCEEDED"
    assert factory.run_texts == ["synthetic fifo hold"] + [item[0] for item in waiting]


def test_result_ttl_removes_content_without_reexecution(transport, service):
    client, _, _, factory, clock = transport
    _, key, tokens = activate(client, service)
    body = b'{"text":"synthetic expiring result"}'
    request_id = str(uuid4())
    deadline_ms = NOW + 120_000
    response = submit(client, key, tokens, body, request_id=request_id,
                      deadline_ms=deadline_ms)
    finished = wait_for_terminal(client, key, tokens, response.json()["job_id"])
    expires_ms = finished.json()["result_expires_ms"]
    fresh = refresh_tokens(service, key, tokens, now=expires_ms)
    clock.value = expires_ms
    expired = signed_get(client, key, fresh,
        f"/api/ai/v1/jobs/{response.json()['job_id']}", now=clock.value)
    assert expired.status_code == 200
    assert expired.json()["state"] == "SUCCEEDED"
    assert expired.json()["result_available"] is False
    assert "result" not in expired.json()
    retry = submit(client, key, fresh, body, request_id=request_id,
                   deadline_ms=deadline_ms, now=clock.value)
    assert retry.status_code == 202
    assert retry.json()["job_id"] == response.json()["job_id"]
    assert factory.run_texts.count("synthetic expiring result") == 1


def test_idle_drain_purges_result_without_status_read(service, tmp_path):
    from app.ai.job_ledger import JobLedger
    from app.ai.job_policy import CHAT_POLICY
    from app.ai.job_service import AiJobService

    clock = AdvancingClock()
    factory = SyntheticFactory()
    ledger = JobLedger(tmp_path / "idle-jobs.sqlite")
    jobs = AiJobService(service[1], service[0].proofs, ledger, factory,
        policy=replace(CHAT_POLICY, result_ttl_ms=30), clock_ms=clock)
    try:
        with TestClient(create_bound_ai_app(service[0], jobs=jobs, clock_ms=clock)) as client:
            _, key, tokens = activate(client, service)
            body = b'{"text":"synthetic idle expiry"}'
            request_id = str(uuid4())
            now = clock()
            accepted = submit(client, key, tokens, body, request_id=request_id,
                              deadline_ms=now + 120_000, now=now)
            assert accepted.status_code == 202
            job_id = accepted.json()["job_id"]
            for _ in range(100):
                if job_id in jobs._results:
                    break
                time.sleep(0.01)
            assert job_id in jobs._results
            time.sleep(0.1)
            assert job_id not in jobs._results
    finally:
        ledger.engine.dispose()


def test_single_drain_expires_content_while_worker_is_active(service, tmp_path):
    from app.ai.job_ledger import JobLedger
    from app.ai.job_policy import CHAT_POLICY
    from app.ai.job_service import AiJobService

    clock = AdvancingClock()
    factory = SyntheticFactory()
    ledger = JobLedger(tmp_path / "active-expiry-jobs.sqlite")
    jobs = AiJobService(service[1], service[0].proofs, ledger, factory,
        policy=replace(CHAT_POLICY, result_ttl_ms=50), clock_ms=clock)
    try:
        with TestClient(create_bound_ai_app(service[0], jobs=jobs, clock_ms=clock)) as client:
            _, result_key, result_tokens = activate(client, service)
            result = submit(client, result_key, result_tokens,
                b'{"text":"synthetic expires during active"}', request_id=str(uuid4()),
                deadline_ms=clock() + 120_000, now=clock())
            result_id = result.json()["job_id"]
            wait_for_terminal(client, result_key, result_tokens, result_id, now=clock())
            assert result_id in jobs._results

            started = Event()
            factory.configure("synthetic active expiry hold", block=True, started=started)
            _, active_key, active_tokens = activate(client, service)
            active = submit(client, active_key, active_tokens,
                b'{"text":"synthetic active expiry hold"}', request_id=str(uuid4()),
                deadline_ms=clock() + 120_000, now=clock())
            assert active.status_code == 202 and started.wait(2)

            _, queued_key, queued_tokens = activate(client, service)
            queued_owner = service[1].authenticate_attested(
                queued_tokens["access_token"], key_fingerprint=key_fingerprint(queued_key),
                now_ms=clock()).owner_id
            queued = submit(client, queued_key, queued_tokens,
                b'{"text":"synthetic queued active expiry"}', request_id=str(uuid4()),
                deadline_ms=clock() + 50, now=clock())
            assert queued.status_code == 202

            time.sleep(0.15)
            assert result_id not in jobs._results
            assert queued.json()["job_id"] not in jobs._payloads
            assert ledger.get(queued_owner, queued.json()["job_id"]).state == "EXPIRED"
            assert signed_delete(client, active_key, active_tokens,
                f"/api/ai/v1/jobs/{active.json()['job_id']}", now=clock()).status_code == 200
    finally:
        ledger.engine.dispose()


def test_idle_drain_expires_queued_payload_behind_unknown_stop(transport, service):
    client, jobs, ledger, factory, clock = transport
    _, first_key, first_tokens = activate(client, service)
    started = Event()
    factory.configure("synthetic expiry blocker", block=True, started=started,
                      stop_receipt=False)
    first = submit(client, first_key, first_tokens,
                   b'{"text":"synthetic expiry blocker"}', request_id=str(uuid4()),
                   deadline_ms=NOW + 120_000)
    assert first.status_code == 202 and started.wait(2)

    _, key, tokens = activate(client, service)
    bound = service[1].authenticate_attested(tokens["access_token"],
        key_fingerprint=key_fingerprint(key), now_ms=NOW + 2)
    queued = submit(client, key, tokens, b'{"text":"synthetic expires waiting"}',
                    request_id=str(uuid4()), deadline_ms=NOW + 10)
    assert queued.status_code == 202
    assert signed_delete(client, first_key, first_tokens,
        f"/api/ai/v1/jobs/{first.json()['job_id']}").json()["state"] == "UNKNOWN"
    clock.value = NOW + 20
    for _ in range(100):
        if ledger.get(bound.owner_id, queued.json()["job_id"]).state == "EXPIRED":
            break
        time.sleep(0.01)
    assert ledger.get(bound.owner_id, queued.json()["job_id"]).state == "EXPIRED"
    assert queued.json()["job_id"] not in jobs._payloads
    assert "synthetic expires waiting" not in factory.run_texts


def test_http_envelope_limits_duplicate_headers_and_canonical_paths(transport, service):
    client, _, _, _, clock = transport
    _, key, tokens = activate(client, service)
    too_large = b"x" * 16_385
    assert submit(client, key, tokens, too_large, request_id=str(uuid4()),
                  deadline_ms=NOW + 120_000).status_code == 413
    assert submit(client, key, tokens, b'{"text":"ok"}', request_id=str(uuid4()),
                  deadline_ms=clock.value + 900_001).status_code == 400

    body = b'{"text":"synthetic headers"}'
    request_id = str(uuid4())
    deadline_ms = NOW + 120_000
    from app.ai.job_policy import submit_proof_credential
    credential = submit_proof_credential(tokens["access_token"], request_id=request_id,
                                         deadline_ms=deadline_ms)
    proof = signed(key, path="/api/ai/v1/jobs", body=body, credential=credential)
    base = list(({"Content-Type": "application/json"} | proof_headers(
        proof, key, access_token=tokens["access_token"], request_id=request_id,
        deadline_ms=deadline_ms)).items())
    assert client.post("/api/ai/v1/jobs", content=body,
                       headers=base + [("X-Copilot-Nonce", str(uuid4()))]).status_code == 401
    assert client.post("/api/ai/v1/jobs", content=body,
                       headers=base + [("Authorization", "Bearer duplicate")]).status_code == 401

    headers = dict(base)
    headers["X-Copilot-Nonce"] = str(uuid4())
    assert client.post("/api/ai/v1/jobs", content=body, headers=headers).status_code == 401
    accepted = submit(client, key, tokens, body, request_id=request_id,
                      deadline_ms=deadline_ms)
    job_id = accepted.json()["job_id"]
    upper_path = f"/api/ai/v1/jobs/{job_id.upper()}"
    assert signed_get(client, key, tokens, upper_path).status_code == 404
    path = f"/api/ai/v1/jobs/{job_id}"
    proof = signed(key, method="GET", path=path, body=b"",
                   credential=tokens["access_token"])
    body_headers = proof_headers(proof, key, access_token=tokens["access_token"])
    assert client.request("GET", path, content=b"{}", headers=body_headers).status_code == 400
    delete_proof = signed(key, method="DELETE", path=path, body=b"",
                          credential=tokens["access_token"])
    delete_headers = proof_headers(delete_proof, key,
                                   access_token=tokens["access_token"])
    assert client.request("DELETE", path, content=b"{}",
                          headers=delete_headers).status_code == 400
    query_path = path + "?view=result"
    assert client.get(query_path, headers=body_headers).status_code == 400

    clock.value = tokens["access_expires_ms"]
    assert signed_get(client, key, tokens, path, now=clock.value).status_code == 401


def test_graceful_close_stops_active_and_restart_never_replays(service, tmp_path):
    from app.ai.job_ledger import JobLedger
    from app.ai.job_service import AiJobService

    clock = MutableClock()
    ledger = JobLedger(tmp_path / "restart-jobs.sqlite")
    first_factory = SyntheticFactory()
    started = Event()
    first_factory.configure("synthetic shutdown", block=True, started=started)
    first_jobs = AiJobService(service[1], service[0].proofs, ledger,
                              first_factory, clock_ms=clock)
    body = b'{"text":"synthetic shutdown"}'
    request_id = str(uuid4())
    deadline_ms = NOW + 120_000
    with TestClient(create_bound_ai_app(service[0], jobs=first_jobs, clock_ms=clock)) as client:
        owner, key, tokens = activate(client, service)
        accepted = submit(client, key, tokens, body, request_id=request_id,
                          deadline_ms=deadline_ms)
        assert accepted.status_code == 202 and started.wait(2)
        job_id = accepted.json()["job_id"]
        bound = service[1].authenticate_attested(tokens["access_token"],
            key_fingerprint=key_fingerprint(key), now_ms=clock())
    assert first_factory.stops == [job_id]
    assert ledger.get(owner, job_id).state == "CANCELLED"

    second_factory = SyntheticFactory()
    second_jobs = AiJobService(service[1], service[0].proofs, ledger,
                               second_factory, clock_ms=clock)
    try:
        with TestClient(create_bound_ai_app(service[0], jobs=second_jobs, clock_ms=clock)) as client:
            path = f"/api/ai/v1/jobs/{job_id}"
            status = signed_get(client, key, tokens, path, now=clock())
            assert status.status_code == 200 and status.json()["state"] == "CANCELLED"
            retry = submit(client, key, tokens, body, request_id=request_id,
                           deadline_ms=deadline_ms, now=clock())
            assert retry.status_code == 202
            assert retry.json()["job_id"] == job_id
            assert second_factory.runs == []
            assert bound.session_id == ledger.get(owner, job_id).session_id
    finally:
        ledger.engine.dispose()


def test_restart_marks_payloadless_queue_unknown_and_keeps_running_slot_occupied(
        service, tmp_path):
    from app.ai.job_ledger import JobLedger
    from app.ai.job_policy import CHAT_POLICY, request_digest
    from app.ai.job_service import AiJobService
    from app.ai.schemas import ChatRequest

    clock = MutableClock()
    with TestClient(create_bound_ai_app(service[0], clock_ms=clock)) as identity_client:
        owner, key, tokens = activate(identity_client, service)
    bound = service[1].authenticate_attested(tokens["access_token"],
        key_fingerprint=key_fingerprint(key), now_ms=clock())
    ledger = JobLedger(tmp_path / "lost-payload.sqlite")
    body = b'{"text":"synthetic lost payload"}'
    request_id = str(uuid4())
    deadline_ms = NOW + 120_000
    digest = request_digest(CHAT_POLICY, request_id=request_id,
        deadline_ms=deadline_ms, request=ChatRequest.decode(body), body=body)
    queued = ledger.reserve_once(owner_id=owner, session_id=bound.session_id,
        key_hash=bound.key_fingerprint, request_id=request_id, kind="CHAT",
        digest=digest, route_revision=CHAT_POLICY.route_revision,
        deadline_ms=deadline_ms, now_ms=clock())[0]
    factory = SyntheticFactory()
    jobs = AiJobService(service[1], service[0].proofs, ledger, factory, clock_ms=clock)
    try:
        with TestClient(create_bound_ai_app(service[0], jobs=jobs, clock_ms=clock)) as client:
            path = f"/api/ai/v1/jobs/{queued.id}"
            status = signed_get(client, key, tokens, path, now=clock())
            assert status.status_code == 200 and status.json()["state"] == "UNKNOWN"
            retry = submit(client, key, tokens, body, request_id=request_id,
                           deadline_ms=deadline_ms, now=clock())
            assert retry.status_code == 202
            assert retry.json()["job_id"] == queued.id
            assert retry.json()["state"] == "UNKNOWN"
            assert factory.runs == []
    finally:
        ledger.engine.dispose()

    running_ledger = JobLedger(tmp_path / "lost-running.sqlite")
    running_request_id = str(uuid4())
    running_body = b'{"text":"synthetic lost running"}'
    running_digest = request_digest(CHAT_POLICY, request_id=running_request_id,
        deadline_ms=deadline_ms, request=ChatRequest.decode(running_body),
        body=running_body)
    running = running_ledger.reserve_once(owner_id=owner, session_id=bound.session_id,
        key_hash=bound.key_fingerprint, request_id=running_request_id, kind="CHAT",
        digest=running_digest, route_revision=CHAT_POLICY.route_revision,
        deadline_ms=deadline_ms, now_ms=clock())[0]
    assert running_ledger.claim(owner, running.id, now_ms=clock()) is not None
    blocked_factory = SyntheticFactory()
    blocked_jobs = AiJobService(service[1], service[0].proofs, running_ledger,
                                 blocked_factory, clock_ms=clock)
    try:
        with TestClient(create_bound_ai_app(service[0], jobs=blocked_jobs,
                                            clock_ms=clock)) as client:
            status = signed_get(client, key, tokens,
                f"/api/ai/v1/jobs/{running.id}", now=clock())
            assert status.status_code == 200 and status.json()["state"] == "UNKNOWN"
            blocked = submit(client, key, tokens, b'{"text":"synthetic blocked"}',
                request_id=str(uuid4()), deadline_ms=deadline_ms, now=clock())
            assert blocked.status_code == 429
            assert blocked.json() == {"error": "owner_busy"}
            assert blocked_factory.runs == []
            assert running_ledger.get(owner, running.id).state == "RUNNING"
    finally:
        running_ledger.engine.dispose()


def test_job_metadata_store_contains_no_request_result_or_credential(transport, service):
    client, _, ledger, _, _ = transport
    _, key, tokens = activate(client, service)
    private_text = "synthetic-private-content-marker"
    body = json.dumps({"text": private_text}, separators=(",", ":")).encode()
    response = submit(client, key, tokens, body, request_id=str(uuid4()),
                      deadline_ms=NOW + 120_000)
    wait_for_terminal(client, key, tokens, response.json()["job_id"])
    with ledger.engine.connect() as db:
        columns = {row[1] for row in db.exec_driver_sql("PRAGMA table_info(ai_jobs)")}
        row = db.exec_driver_sql("SELECT * FROM ai_jobs").mappings().one()
    assert not columns & {"payload", "prompt", "text", "result", "error", "credential"}
    serialized = json.dumps(dict(row), sort_keys=True)
    for private in (private_text, "synthetic response", tokens["access_token"]):
        assert private not in serialized
