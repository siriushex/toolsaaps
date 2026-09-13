import asyncio
import subprocess
import sys
from uuid import uuid4

import pytest
from fastapi.testclient import TestClient

from app.ai.client_auth import ClientAuth
from app.ai_server import create_ai_app

BASE = "/api/ai/v1"


@pytest.fixture
def setup(tmp_path):
    auth = ClientAuth(tmp_path / "identity.sqlite")
    app = create_ai_app(auth)
    with TestClient(app) as client:
        yield auth, app, client
    auth.engine.dispose()


def enroll(auth, client):
    import time
    code = auth.issue_enrollment(owner_id=str(uuid4()), now_ms=time.time_ns() // 1_000_000)
    response = client.post(BASE + "/auth/enroll", json={"code": code})
    assert response.status_code == 200
    assert response.headers["cache-control"] == "no-store"
    return response.json()


def test_enroll_refresh_revoke_and_capabilities(setup):
    auth, _, client = setup
    tokens = enroll(auth, client)
    def caps(token):
        return client.get(BASE + "/capabilities", headers={"Authorization": "Bearer " + token})
    assert caps(tokens["access_token"]).json() == {
        "revision": "auth-pilot-v1", "inference_enabled": False, "models": [], "task_kinds": []}
    response = client.post(BASE + "/auth/refresh", json={"refresh_token": tokens["refresh_token"]})
    assert response.status_code == 200
    new = response.json()
    assert caps(tokens["access_token"]).status_code == 401
    assert caps(new["access_token"]).status_code == 200
    assert client.post(BASE + "/auth/revoke", json={"refresh_token": new["refresh_token"]}).status_code == 204
    assert caps(new["access_token"]).status_code == 401


def test_short_subscription_code_cannot_activate_through_bearer_only_pilot(setup):
    import time
    auth, _, client = setup
    code = auth.issue_subscription(owner_id=str(uuid4()), duration_days=30,
                                   now_ms=time.time_ns() // 1_000_000)
    response = client.post(BASE + "/auth/enroll", json={"code": code})
    assert response.status_code == 401
    assert response.json() == {"error": "unauthorized"}
    assert response.headers["cache-control"] == "no-store"
    assert code not in response.text
    with auth.engine.connect() as db:
        assert db.exec_driver_sql("SELECT consumed_ms FROM ai_client_credentials").scalar_one() is None


@pytest.mark.parametrize("path", ["/v1/sync/pull", "/v1/actions/temp-target", "/docs", "/openapi.json", BASE + "/jobs"])
def test_no_therapy_docs_or_inference_routes(setup, path):
    _, _, client = setup
    assert client.get(path).status_code == 404
    assert client.post(path, json={}).status_code == 404


def test_no_auth_in_query_or_ambiguous_headers(setup):
    auth, _, client = setup
    token = enroll(auth, client)["access_token"]
    assert client.get(BASE + "/capabilities", params={"token": token}).status_code == 400
    assert client.get(BASE + "/capabilities").status_code == 401
    assert client.get(BASE + "/capabilities", headers=[("Authorization", "Bearer " + token)] * 2).status_code == 401


@pytest.mark.parametrize("body", [
    '{"code":"secret", "owner_id":"forged"}',
    '{"code":"secret", "code":"other"}',
    '{"code":42}', '[]', '{', '{"code":NaN}',
])
def test_invalid_body_never_echoes_input(setup, body):
    _, _, client = setup
    result = client.post(BASE + "/auth/enroll", content=body, headers={"Content-Type": "application/json"})
    assert result.status_code == 400
    assert result.json() == {"error": "invalid_request"}
    assert result.headers["cache-control"] == "no-store"


def test_size_encoding_and_declared_length_limits(setup):
    _, _, client = setup
    assert client.post(BASE + "/auth/enroll", content=b"a" * 2049,
                       headers={"Content-Type": "application/json"}).status_code == 413
    assert client.post(BASE + "/auth/enroll", content=b"{}",
                       headers={"Content-Encoding": "gzip", "Content-Type": "application/json"}).status_code == 415
    assert client.post(BASE + "/auth/enroll", content="{}").status_code == 415


def test_global_rate_limit_precedes_body_consumption(tmp_path):
    auth = ClientAuth(tmp_path / "identity.sqlite")
    app = create_ai_app(auth, requests_per_minute=2)
    with TestClient(app) as client:
        for _ in range(2):
            assert client.get(BASE + "/capabilities").status_code == 401
        response = client.post(BASE + "/auth/enroll", content=b"a" * 5000)
        assert response.status_code == 429
        assert response.headers["retry-after"] == "60"
    auth.engine.dispose()


def test_chunk_limit_and_disconnect(setup):
    _, app, _ = setup
    async def request(messages, delay=0):
        sent = []
        async def receive():
            if delay:
                await asyncio.sleep(delay)
            return messages.pop(0)
        async def send(message):
            sent.append(message)
        scope = {"type": "http", "method": "POST", "path": BASE + "/auth/enroll",
                 "query_string": b"", "headers": [(b"content-type", b"application/json")]}
        await app(scope, receive, send)
        return sent
    async def run():
        messages = [{"type": "http.request", "body": b"a" * 1500, "more_body": True}] * 2
        assert (await request(messages))[0]["status"] == 413
        assert await request([{"type": "http.disconnect"}]) == []
    asyncio.run(run())


def test_slow_body_times_out_without_entering_auth(tmp_path):
    auth = ClientAuth(tmp_path / "identity.sqlite")
    app = create_ai_app(auth, body_timeout_seconds=0.01)
    async def run():
        sent = []
        async def receive():
            await asyncio.sleep(1)
            raise AssertionError("deadline_not_enforced")
        async def send(message):
            sent.append(message)
        scope = {"type": "http", "method": "POST", "path": BASE + "/auth/enroll",
                 "query_string": b"", "headers": [(b"content-type", b"application/json")]}
        await app(scope, receive, send)
        assert sent[0]["status"] == 408
    asyncio.run(run())
    auth.engine.dispose()


def test_storage_failure_returns_sanitized_unavailable(setup, monkeypatch):
    auth, _, client = setup
    def broken(*args, **kwargs):
        raise RuntimeError("credential-secret-and-private-sql")
    monkeypatch.setattr(auth, "enroll", broken)
    response = client.post(BASE + "/auth/enroll", json={"code": "secret"})
    assert response.status_code == 503
    assert response.json() == {"error": "service_unavailable"}
    assert "secret" not in response.text


def test_rate_limit_recovers_at_window_boundary(tmp_path, monkeypatch):
    auth = ClientAuth(tmp_path / "identity.sqlite")
    app = create_ai_app(auth, requests_per_minute=1)
    clock = [100.0]
    monkeypatch.setattr("app.ai_server.time.monotonic", lambda: clock[0])
    assert app._admit()
    assert not app._admit()
    clock[0] = 160.0
    assert app._admit()
    auth.engine.dispose()


def test_isolated_import_does_not_load_therapy_modules():
    script = "import sys; import app.ai_server; assert not any(x in sys.modules for x in ('app.main','app.repository','app.db','apscheduler'))"
    result = subprocess.run([sys.executable, "-c", script], capture_output=True, timeout=10)
    assert result.returncode == 0, result.stderr.decode()
