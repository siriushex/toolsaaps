import asyncio
import base64
from contextlib import ExitStack, contextmanager
from dataclasses import replace
import hashlib
import json
import os
from pathlib import Path
import sqlite3
import stat
import struct
from threading import Event
import traceback
from types import SimpleNamespace
from uuid import uuid4

import pytest
from fastapi.testclient import TestClient
from cryptography.hazmat.primitives import serialization
from sqlalchemy import update

from app.ai import owner_api as owner
from app.ai.activation import ActivationService
from app.ai.android_attestation import AndroidAttestationVerifier
from app.ai.client_auth import ClientAuth, attested_device_keys, sessions
from app.ai.history_contract import RETENTION_MS, encoded
from app.ai.job_ledger import JobLedger
from app.ai.schemas import ChatRequest
from app.ai.text_supervisor import policy_digest
from test_ai_activation import NOW, complete, device, service, signed  # noqa: F401
from test_ai_history_transport import batch, query, signed_post
from test_ai_job_transport import (MutableClock, activate, key_fingerprint, proof_headers,
    signed_get, submit, wait_for_background_state, wait_for_terminal)

CHECK_SUPERVISOR = owner._check_supervisor


@pytest.fixture(autouse=True)
def protected_test_anchor(tmp_path, monkeypatch):
    # Development ancestors may be writable. Keep production's full refusal;
    # synthetic tests start their no-follow walk at their private fixture only.
    # Our focused run places these fixtures inside the assigned worktree.
    anchor = tmp_path.resolve()
    monkeypatch.setattr(owner, "_directory", lambda path: owner._walk_directory(path, anchor))


@pytest.fixture
def provisioned(tmp_path, monkeypatch):
    root = tmp_path.resolve()
    roots = {}
    for name in ("identity", "proofs", "jobs", "history"):
        path = root / name
        path.mkdir(mode=0o700)
        roots[name] = str(path)
    for name, value in (("receipt.key", b"r" * 32), ("history.key", b"h" * 32)):
        path = root / name
        path.write_bytes(value)
        path.chmod(0o600)
    snapshot = root / "revocation.json"
    snapshot.write_bytes(b'{"entries":{}}')
    snapshot.chmod(0o600)
    value = dict(version=2, owner_id=str(uuid4()), model="sample-model",
        roots=roots, receipt_key=str(root / "receipt.key"), history_key=str(root / "history.key"),
        supervisor=dict(socket="/run/personal-ai/text.sock", uid=os.geteuid(), gid=os.getegid()),
        attestation=dict(application_id="io.aaps.predictivecopilot",
            allowed_signer_sha256=["73" * 32], trusted_root_sha256=["ab" * 32],
            revoked_certificate_sha256=[], revocation_checked_ms=NOW,
            revocation_snapshot=dict(path=str(snapshot), sha256=hashlib.sha256(snapshot.read_bytes()).hexdigest())))
    path = root / "owner.json"
    path.write_text(json.dumps(value))
    path.chmod(0o600)
    # The managed sandbox prohibits bind(2). Socket metadata and typed transport
    # are tested separately with synthetic OS/stream records, never a live host.
    monkeypatch.setattr(owner, "_check_supervisor", lambda config: None)
    return path, value


def save(provisioned):
    path, value = provisioned
    path.write_text(json.dumps(value))
    return path


def test_configuration_derives_policy_without_opening_stores(provisioned):
    path, value = provisioned
    config = owner.read_configuration(path)
    assert config.owner_id == value["owner_id"]
    assert config.policy.model == value["model"]
    assert config.policy.allow_tools is config.policy.allow_actions is False
    assert "serverConversation" in config.policy.system_prompt
    assert not list(Path(value["roots"]["jobs"]).iterdir())


def test_daily_reads_are_explicit_and_off_by_default(provisioned):
    path, value = provisioned
    assert getattr(owner.read_configuration(path), "daily_reads", None) is False
    for enabled in (True, False):
        value["daily_reads"] = enabled
        config = owner.read_configuration(save(provisioned))
        assert config.daily_reads is enabled
        assert config.policy.allow_tools is config.policy.allow_actions is False


def test_daily_facts_are_explicit_independent_and_off_by_default(provisioned):
    path, value = provisioned
    assert getattr(owner.read_configuration(path), "daily_facts", None) is False
    value["daily_facts"] = True
    config = owner.read_configuration(save(provisioned))
    assert config.daily_facts is True and config.daily_reads is False
    assert config.policy.allow_actions is False


@pytest.mark.parametrize("invalid", [1, "true", None, {}, []])
def test_daily_facts_reject_non_boolean_configuration(provisioned, invalid):
    _, value = provisioned
    value["daily_facts"] = invalid
    with pytest.raises(owner.OwnerApiError):
        owner.read_configuration(save(provisioned))


def test_daily_facts_task_belongs_to_api_lifespan(provisioned, monkeypatch):
    from app.ai.daily_facts_service import DailyFactsMaintenance

    _, value = provisioned
    value["daily_facts"] = True
    seen = []
    async def run(self, stop):
        seen.append("started")
        await stop.wait()
        seen.append("drained")
    monkeypatch.setattr(DailyFactsMaintenance, "run", run)
    app = owner.create_owner_api(save(provisioned), clock_ms=lambda: NOW + 2)
    with TestClient(app) as client:
        client.portal.call(asyncio.sleep, 0)
        assert seen == ["started"]
        resources = app.resources
        assert resources.daily_facts is not None and resources.jobs.inference_ready
    assert seen == ["started", "drained"]
    assert app.resources is None and not resources.jobs.inference_ready


def test_daily_facts_default_does_not_construct_background_owner(provisioned, monkeypatch):
    from app.ai.daily_facts_service import DailyFactsMaintenance

    monkeypatch.setattr(DailyFactsMaintenance, "__init__",
                        lambda *a, **kw: pytest.fail("default background owner"))
    with TestClient(owner.create_owner_api(provisioned[0], clock_ms=lambda: NOW + 2)) as client:
        assert client.app.resources.daily_facts is None


def test_api_shutdown_drains_owned_facts_thread_before_closing_roots(provisioned, monkeypatch):
    from app.ai.daily_facts_service import DailyFactsMaintenance

    _, value = provisioned
    value["daily_facts"] = True
    entered, release = Event(), Event()
    checked = []

    async def run(self, stop):
        def read():
            entered.set()
            assert release.wait(3)
            self.storage_check()
            checked.append(True)
        await self._call(read)

    monkeypatch.setattr(DailyFactsMaintenance, "run", run)
    app = owner.create_owner_api(save(provisioned), clock_ms=lambda: NOW + 2)

    async def scenario():
        context = app._lifespan(app)
        await context.__aenter__()
        assert await asyncio.to_thread(entered.wait, 1)
        resources = app.resources
        closing = asyncio.create_task(context.__aexit__(None, None, None))
        await asyncio.sleep(0)
        assert not closing.done() and resources.jobs.inference_ready
        release.set()
        await closing
        assert checked == [True] and not resources.jobs.inference_ready

    try:
        asyncio.run(scenario())
    finally:
        release.set()


@pytest.mark.parametrize("invalid", [1, "true", None, {}, []])
def test_daily_reads_reject_non_boolean_owner_configuration(provisioned, invalid):
    _, value = provisioned
    value["daily_reads"] = invalid
    with pytest.raises(owner.OwnerApiError):
        owner.read_configuration(save(provisioned))


def test_owner_configuration_loads_pinned_serial_snapshot(provisioned):
    _, value = provisioned
    snapshot = Path(value["attestation"]["revocation_snapshot"]["path"])
    snapshot.write_bytes(b'{"entries":{"abc":{"status":"REVOKED"}}}')
    value["attestation"]["revocation_snapshot"]["sha256"] = hashlib.sha256(snapshot.read_bytes()).hexdigest()
    config = owner.read_configuration(save(provisioned))
    assert config.attestation.revoked_certificate_serials == frozenset({"abc"})


@pytest.mark.parametrize("change", ["missing", "legacy", "digest", "mode", "link", "private-key-path", "in-database"])
def test_owner_refuses_unpinned_or_unsafe_revocation_snapshot(provisioned, change):
    _, value = provisioned
    descriptor = value["attestation"]["revocation_snapshot"]
    path = Path(descriptor["path"])
    if change == "missing":
        del value["attestation"]["revocation_snapshot"]
    elif change == "legacy":
        value["version"] = 1
    elif change == "digest":
        path.write_bytes(b'{"entries":{"abc":{"status":"REVOKED"}}}')
    elif change == "mode":
        path.chmod(0o644)
    elif change == "link":
        target = path.with_suffix(".original")
        path.rename(target)
        path.symlink_to(target)
    elif change == "private-key-path":
        descriptor["path"] = value["history_key"]
    else:
        descriptor["path"] = value["roots"]["history"] + "/snapshot.json"
    with pytest.raises(owner.OwnerApiError):
        owner.read_configuration(save(provisioned))


def test_optional_vision_descriptor_requires_separate_closed_socket(provisioned):
    _, value = provisioned
    value["vision"] = dict(socket="/run/personal-ai/image.sock", uid=0, gid=0)
    value["daily_reads"] = True
    config = owner.read_configuration(save(provisioned))
    assert str(config.vision_socket_path) == value["vision"]["socket"]
    assert config.vision_peer.uid == 0
    assert config.daily_reads is True
    value["vision"]["socket"] = value["supervisor"]["socket"]
    with pytest.raises(owner.OwnerApiError):
        owner.read_configuration(save(provisioned))


def test_all_services_are_drained_even_if_one_stop_is_cancelled():
    closed = []
    class Service:
        def __init__(self, name):
            self.name = name
        async def close(self):
            closed.append(self.name)
            if self.name == "photo":
                raise asyncio.CancelledError()
    resources = owner._Resources.__new__(owner._Resources)
    resources.services = (Service("text"), Service("photo"))
    with pytest.raises(owner.OwnerApiError):
        asyncio.run(resources.close_services())
    assert closed == ["photo", "text"]


def test_opted_in_signed_photo_uses_supervisor_without_api_decoder(provisioned, service, monkeypatch):  # noqa: F811
    from test_ai_meal_photo_transport import submit_photo, wait_for_photo
    from test_meal_vision import estimate_bytes
    path, value = provisioned
    value["vision"] = dict(socket="/run/personal-ai/image.sock", uid=0, gid=0)
    root = service[2].public_bytes(serialization.Encoding.DER)
    value["attestation"]["trusted_root_sha256"] = [hashlib.sha256(root).hexdigest()]
    save(provisioned)
    created = []
    class SyntheticWorker:
        def __init__(self, work, endpoint, peer, *, clock_ms):
            self.route_revision, self.request_digest = work.route_revision, work.request_digest
            assert endpoint == value["vision"]["socket"] and peer.uid == 0
            assert work.request.image_bytes == b"\xff\xd8\xffsample\xff\xd9"
            created.append(self)
        async def run(self):
            return json.loads(estimate_bytes())
        async def stop_and_confirm(self):
            return True
    monkeypatch.setattr(owner, "SupervisorMealVisionWorker", SyntheticWorker)
    app = owner.create_owner_api(path, clock_ms=MutableClock())
    with TestClient(app) as client:
        resources = app.resources
        synthetic = (resources.activation, resources.auth, service[2], service[3], None)
        _, key, tokens = activate(client, synthetic, owner_id=value["owner_id"])
        raw = b"\xff\xd8\xffsample\xff\xd9"
        body = encoded(dict(schemaVersion=1, mimeType="image/jpeg", imageBase64=base64.b64encode(raw).decode()))
        accepted = submit_photo(client, key, tokens, body, request_id=str(uuid4()), deadline_ms=NOW + 60_000)
        assert accepted.status_code == 202
        finished = wait_for_photo(client, key, tokens, accepted.json()["job_id"]).json()
        assert finished["state"] == "SUCCEEDED" and len(created) == 1
        assert finished["result"] == json.loads(estimate_bytes())
        caps = signed_get(client, key, tokens, "/api/ai/v1/capabilities").json()
        assert set(caps["task_kinds"]) == {"CHAT", "MEAL_PHOTO"}
        assert resources.photo_jobs.inference_ready
    assert not resources.photo_jobs.inference_ready


@pytest.mark.parametrize("change", ["unknown", "version", "owner", "model", "relative",
    "traversal", "shared", "nested", "key-in-db", "peer", "socket", "attestation", "stale-shape"])
def test_closed_configuration_rejects_unsafe_values(provisioned, change):
    _, value = provisioned
    if change == "unknown":
        value["instructions"] = "private operator text"
    elif change == "version":
        value["version"] = True
    elif change == "owner":
        value["owner_id"] = "not-a-uuid"
    elif change == "model":
        value["model"] = "sample/model"
    elif change == "relative":
        value["receipt_key"] = "receipt.key"
    elif change == "traversal":
        value["roots"]["jobs"] += "/../jobs"
    elif change == "shared":
        value["roots"]["jobs"] = value["roots"]["identity"]
    elif change == "nested":
        value["roots"]["jobs"] = value["roots"]["identity"] + "/nested"
    elif change == "key-in-db":
        value["history_key"] = value["roots"]["history"] + "/key"
    elif change == "peer":
        value["supervisor"]["uid"] = True
    elif change == "socket":
        value["supervisor"]["socket"] = "/" + "x" * 104
    elif change == "attestation":
        value["attestation"]["trusted_root_sha256"] = []
    else:
        value["attestation"]["max_revocation_age_ms"] = 999999999
    with pytest.raises(owner.OwnerApiError):
        owner.read_configuration(save(provisioned))


@pytest.mark.parametrize("raw", [b'[]', b'{"version":1,"version":1}', b'{"v":NaN}',
    b'\xff', b' ' * 32769])
def test_configuration_is_bounded_unique_json(provisioned, raw):
    path, _ = provisioned
    path.write_bytes(raw)
    with pytest.raises(owner.OwnerApiError):
        owner.read_configuration(path)


@pytest.mark.parametrize("kind", ["mode", "symlink", "hardlink", "parent-link", "parent-mode"])
def test_protected_configuration_rejects_links_and_permissions(provisioned, kind):
    path, _ = provisioned
    if kind == "mode":
        path.chmod(0o644)
    elif kind == "hardlink":
        os.link(path, path.with_suffix(".linked"))
    elif kind == "symlink":
        linked = path.with_suffix(".linked")
        linked.symlink_to(path)
        path = linked
    elif kind == "parent-link":
        linked = path.parent / "linked"
        linked.symlink_to(path.parent, target_is_directory=True)
        path = linked / path.name
    else:
        path.parent.chmod(0o770)
    with pytest.raises(owner.OwnerApiError):
        owner.read_configuration(path)


def test_factory_refuses_root_before_reading_configuration(monkeypatch):
    monkeypatch.setattr(owner.os, "geteuid", lambda: 0)
    with pytest.raises(owner.OwnerApiError, match="nonroot"):
        owner.create_owner_api(Path("/absent"))


def test_explicit_entry_requires_configuration_pointer(monkeypatch):
    monkeypatch.delenv("COPILOT_AI_OWNER_CONFIG", raising=False)
    with pytest.raises(owner.OwnerApiError):
        owner.create_app()


def test_startup_and_shutdown_close_resources_and_hold_exclusive_lock(provisioned):
    path, value = provisioned
    app = owner.create_owner_api(path, clock_ms=lambda: NOW + 2)
    assert app.resources is None
    with TestClient(app) as client:
        resources = app.resources
        assert resources.jobs.inference_ready
        assert client.get("/health").status_code == 404
        assert client.get("/api/ai/v1/capabilities").status_code == 401
        with pytest.raises(owner.OwnerApiError):
            with TestClient(owner.create_owner_api(path, clock_ms=lambda: NOW + 2)):
                pass
        for name in ("identity", "proofs", "jobs"):
            for entry in Path(value["roots"][name]).iterdir():
                assert entry.stat().st_mode & 0o777 == 0o600
    assert app.resources is None
    assert not resources.jobs.inference_ready
    with TestClient(owner.create_owner_api(path, clock_ms=lambda: NOW + 3)):
        pass


@pytest.mark.parametrize("kind", ["missing-key", "key-mode", "key-link", "key-size", "same-key",
    "unknown-file", "database-link", "database-mode", "root-mode"])
def test_startup_refuses_unprovisioned_keys_and_unsafe_storage(provisioned, kind):
    path, value = provisioned
    key = Path(value["history_key"])
    root = Path(value["roots"]["identity"])
    if kind == "missing-key":
        key.unlink()
    elif kind == "key-mode":
        key.chmod(0o644)
    elif kind == "key-link":
        os.link(key, key.with_suffix(".linked"))
    elif kind == "key-size":
        key.write_bytes(b"h" * 31)
    elif kind == "same-key":
        key.write_bytes(b"r" * 32)
    elif kind == "unknown-file":
        (root / "medical.sqlite").write_bytes(b"")
    elif kind == "database-link":
        (root / "identity.sqlite").symlink_to(path)
    elif kind == "database-mode":
        (root / "identity.sqlite").touch(mode=0o644)
    else:
        root.chmod(0o750)
    with pytest.raises(owner.OwnerApiError):
        with TestClient(owner.create_owner_api(path, clock_ms=lambda: NOW + 2)):
            pass


@pytest.fixture
def synthetic_transport(monkeypatch):
    class Writer:
        def __init__(self, reader, channel):
            self.reader, self.channel = reader, channel
            self.closed = False

        def write(self, data):
            size = struct.unpack("!I", data[:4])[0]
            request = json.loads(data[4:])
            assert size == len(data) - 4
            if "cancel" in request:
                self.reply(self.channel.requests[-1], "CANCELLED", True)
                return
            self.channel.requests.append(request)
            self.channel.entered.set()
            if not self.channel.block:
                self.reply(request, "SUCCEEDED", self.channel.stopped)

        def reply(self, request, state, stopped):
            if self.channel.ambiguous:
                self.reader.feed_eof()
                return
            value = dict(version=1, runId=request["runId"], state=state, stopped=stopped,
                result={"text": "Synthetic answer"} if state == "SUCCEEDED" else None)
            raw = encoded(value)
            self.reader.feed_data(struct.pack("!I", len(raw)) + raw)

        async def drain(self):
            pass

        def close(self):
            self.closed = True

        async def wait_closed(self):
            pass

    channel = SimpleNamespace(requests=[], connections=[], peers=[], stopped=True,
        ambiguous=False, block=False, entered=Event())

    async def connect(path, *, limit):
        assert path == "/run/personal-ai/text.sock"
        reader = asyncio.StreamReader(limit=limit)
        writer = Writer(reader, channel)
        channel.connections.append(writer)
        return reader, writer

    def peer_check(peer, writer):
        assert peer == owner.PeerIdentity(os.geteuid(), os.getegid())
        channel.peers.append(peer)

    monkeypatch.setattr(owner.asyncio, "open_unix_connection", connect)
    monkeypatch.setattr(owner.PeerIdentity, "check", peer_check)
    return channel


@pytest.fixture
def running(provisioned, service, synthetic_transport):  # noqa: F811
    path, value = provisioned
    root = service[2].public_bytes(serialization.Encoding.DER)
    value["attestation"]["trusted_root_sha256"] = [hashlib.sha256(root).hexdigest()]
    save(provisioned)
    clock = MutableClock()
    app = owner.create_owner_api(path, clock_ms=clock)
    with TestClient(app) as client:
        resources = app.resources
        synthetic = (resources.activation, resources.auth, service[2], service[3], None)
        _, key, tokens = activate(client, synthetic, owner_id=value["owner_id"])
        yield SimpleNamespace(client=client, app=app, resources=resources, synthetic=synthetic,
            key=key, tokens=tokens, clock=clock, config=value, path=path, channel=synthetic_transport)


def refresh_http(run, key, tokens):
    body = encoded(dict(request_id=str(uuid4()), refresh_token=tokens["refresh_token"]))
    path = "/api/ai/v1/session/refresh"
    return run.client.post(path, content=body, headers={"Content-Type": "application/json"} |
        proof_headers(signed(key, path=path, body=body, credential=tokens["refresh_token"],
                             now=run.clock()), key))


def test_owner_composition_passes_explicit_daily_read_admission(provisioned, service, synthetic_transport):  # noqa: F811
    path, value = provisioned
    value["daily_reads"] = True
    root = service[2].public_bytes(serialization.Encoding.DER)
    value["attestation"]["trusted_root_sha256"] = [hashlib.sha256(root).hexdigest()]
    save(provisioned)
    app = owner.create_owner_api(path, clock_ms=MutableClock())
    with TestClient(app) as client:
        resources = app.resources
        synthetic = (resources.activation, resources.auth, service[2], service[3], None)
        _, key, tokens = activate(client, synthetic, owner_id=value["owner_id"])
        result = signed_post(client, key, tokens, "daily-results",
            dict(afterSequence=0, snapshotSequence=None))
        assert result.status_code == 200 and result.json()["records"] == []
        assert not list(resources.store.root.glob("*.sqlite"))
        assert not synthetic_transport.requests


@pytest.mark.parametrize("_attempt", range(20))
def test_signed_history_context_job_and_archive_keep_existing_contracts(running, monkeypatch, _attempt):
    run = running
    rejected_checks = []
    original_check = run.resources.check

    def checked_storage():
        try:
            return original_check()
        except Exception as error:
            frames = traceback.extract_tb(error.__traceback__)
            rejected_checks.append((type(error).__name__, [
                f"{frame.name}:{frame.lineno}" for frame in frames if frame.filename == owner.__file__
            ]))
            raise

    monkeypatch.setattr(run.resources, "check", checked_storage)
    assert signed_post(run.client, run.key, run.tokens, "batches", batch()).status_code == 200
    path = "/api/ai/v1/capabilities"
    capabilities = signed_get(run.client, run.key, run.tokens, path).json()
    assert capabilities["task_kinds"] == ["CHAT"] and capabilities["inference_enabled"] is True
    assert run.client.post("/api/ai/v1/meal-photo/jobs", json={}).status_code == 404
    body = b'{"text":"Synthetic private question"}'
    request_id, deadline = str(uuid4()), NOW + 60_000
    response = submit(run.client, run.key, run.tokens, body, request_id=request_id, deadline_ms=deadline)
    assert response.status_code == 202
    job_id = response.json()["job_id"]
    try:
        final = wait_for_terminal(run.client, run.key, run.tokens, job_id).json()
    except AssertionError:
        if rejected_checks:
            pytest.fail(f"protected storage rejected: {rejected_checks}", pytrace=False)
        raise
    assert final["state"] == "SUCCEEDED", (
        f"terminal state: {final['state']}; protected storage rejected: {rejected_checks}; "
        f"synthetic requests: {len(run.channel.requests)}; "
        f"writers closed: {[writer.closed for writer in run.channel.connections]}"
    )
    assert final["result"] == {"text": "Synthetic answer"}
    assert submit(run.client, run.key, run.tokens, body, request_id=request_id,
                  deadline_ms=deadline).json()["job_id"] == job_id
    assert len(run.channel.requests) == len(run.channel.peers) == 1
    assert all(writer.closed for writer in run.channel.connections)
    request = run.channel.requests[0]
    assert set(request) == {"version", "runId", "policySha256", "text", "usageReceiptRevision"}
    assert request["usageReceiptRevision"] == "provider-token-usage-v1"
    config = owner.read_configuration(run.path)
    policy = owner.TextSessionPolicy(config.policy.model, config.policy.system_prompt)
    assert request["policySha256"] == policy_digest(policy)
    envelope = json.loads(request["text"])
    assert envelope["question"] == "Synthetic private question"
    assert envelope["deviceHistory"]["coverage"]["eligibleRecords"] == 1
    assert envelope["serverConversation"]["exchanges"] == []
    run.clock.value = NOW + 3
    conversation = dict(fromMs=NOW - 1000, untilMs=NOW + 3, cursor=None, limit=20)
    response = signed_post(run.client, run.key, run.tokens, "conversations", conversation)
    assert response.status_code == 200 and response.headers["cache-control"] == "no-store"
    record = response.json()["records"][0]
    assert record["question"] == "Synthetic private question" and record["answer"] == "Synthetic answer"
    assert response.json()["origin"] == "SERVER_ARCHIVE"
    _, other_key, other_tokens = activate(run.client, run.synthetic, owner_id=run.config["owner_id"])
    assert signed_post(run.client, other_key, other_tokens, "conversations", conversation).json()["records"] == []
    stored = next(run.resources.store.root.glob("*.sqlite"))
    assert b"Synthetic private question" not in stored.read_bytes()
    assert b"Synthetic answer" not in stored.read_bytes()
    with sqlite3.connect(stored) as db:
        assert db.execute("SELECT expires_ms FROM server_chat").fetchone()[0] == NOW + 2 + RETENTION_MS
    with run.resources.ledger.engine.connect() as db:
        assert "text" not in db.exec_driver_sql("SELECT * FROM ai_jobs").keys()


def test_other_subscribed_owner_cannot_activate_recover_refresh_or_use_any_route(running):
    run = running
    foreign = str(uuid4())
    code = run.resources.auth.issue_subscription(owner_id=foreign, duration_days=30, now_ms=NOW)
    request_id = str(uuid4())
    response = run.client.post("/api/ai/v1/activation/start", json=dict(code=code, request_id=request_id))
    assert response.status_code == 401
    # A pre-existing independently valid foreign session is still refused on
    # every owner API route, including completed-activation recovery and refresh.
    unscoped_auth = ClientAuth(Path(run.config["roots"]["identity"]) / "identity.sqlite")
    try:
        activation = ActivationService(unscoped_auth, run.resources.activation.attestation,
            run.resources.proofs, receipt_key=b"r" * 32)
        challenge = activation.start(code=code, request_id=request_id, now_ms=NOW + 1)
        synthetic = (activation, unscoped_auth, run.synthetic[2], run.synthetic[3], None)
        key, chain = device(synthetic, challenge)
        tokens = complete(synthetic, request_id, key, chain)
        tokens = dict(access_token=tokens.access_token, refresh_token=tokens.refresh_token)
        activation_body = encoded(dict(request_id=request_id,
            certificate_chain=[base64.b64encode(item).decode() for item in chain]))
        path = "/api/ai/v1/activation/complete"
        assert run.client.post(path, content=activation_body,
            headers={"Content-Type": "application/json"} | proof_headers(signed(key,
                path=path, body=activation_body, credential=request_id), key)).status_code == 401
        assert refresh_http(run, key, tokens).status_code == 401
        for route in ("session/status", "capabilities"):
            assert signed_get(run.client, key, tokens, "/api/ai/v1/" + route).status_code == 401
        for route, payload in (("batches", batch()), ("query", query()), ("conversations",
                dict(fromMs=NOW - 1000, untilMs=NOW + 2, cursor=None, limit=20))):
            assert signed_post(run.client, key, tokens, route, payload).status_code == 401
        assert submit(run.client, key, tokens, b'{"text":"Foreign question"}',
            request_id=str(uuid4()), deadline_ms=NOW + 60_000).status_code == 401
        assert run.channel.requests == []
    finally:
        unscoped_auth.engine.dispose()


@pytest.mark.parametrize("change", ["revoked-session", "revoked-device", "expired-access", "expired-grant"])
def test_current_access_and_revocation_are_required_for_status_jobs_history_and_refresh(running, change):
    run = running
    if change == "expired-access":
        run.clock.value += 600_001
    else:
        with run.resources.auth.engine.begin() as db:
            if change == "revoked-session":
                db.execute(update(sessions).values(revoked_ms=NOW + 2))
            elif change == "revoked-device":
                db.execute(update(attested_device_keys).values(revoked_ms=NOW + 2))
            else:
                db.execute(update(sessions).values(expires_ms=NOW + 2))
    for route in ("session/status", "capabilities"):
        assert signed_get(run.client, run.key, run.tokens, "/api/ai/v1/" + route,
                          now=run.clock()).status_code == 401
    assert signed_post(run.client, run.key, run.tokens, "query", query()).status_code == 401
    assert submit(run.client, run.key, run.tokens, b'{"text":"Sample"}', request_id=str(uuid4()),
        deadline_ms=run.clock() + 60_000, now=run.clock()).status_code == 401
    if change == "expired-access":
        assert refresh_http(run, run.key, run.tokens).status_code == 200
    else:
        assert refresh_http(run, run.key, run.tokens).status_code == 401
    assert run.channel.requests == []


def test_exact_body_nonce_and_metadata_proofs_still_apply(running):
    run = running
    path = "/api/ai/v1/history/query"
    body = encoded(query())
    headers = {"Content-Type": "application/json"} | proof_headers(signed(run.key,
        path=path, body=body, credential=run.tokens["access_token"]), run.key,
        access_token=run.tokens["access_token"])
    assert run.client.post(path, content=body, headers=headers).status_code == 200
    assert run.client.post(path, content=body, headers=headers).status_code == 401
    assert signed_post(run.client, run.key, run.tokens, "query", query(), proof_body=b"{}").status_code == 401
    assert submit(run.client, run.key, run.tokens, b'{"text":"Sample"}', proof_body=b'{}',
        request_id=str(uuid4()), deadline_ms=NOW + 60_000).status_code == 401
    assert run.channel.requests == []


def test_ambiguous_supervisor_execution_is_never_reconnected_or_retried(running):
    run = running
    run.channel.ambiguous = True
    body, request_id, deadline = b'{"text":"Sample"}', str(uuid4()), NOW + 60_000
    response = submit(run.client, run.key, run.tokens, body, request_id=request_id, deadline_ms=deadline)
    assert response.status_code == 202
    wait_for_background_state(lambda: not run.resources.jobs.inference_ready)
    job = run.resources.ledger.get(run.config["owner_id"], response.json()["job_id"])
    assert job.state == "RUNNING"
    assert submit(run.client, run.key, run.tokens, body, request_id=request_id,
                  deadline_ms=deadline).status_code == 503
    assert len(run.channel.requests) == len(run.channel.connections) == 1


@pytest.mark.parametrize("state", ["QUEUED", "RUNNING", "CANCEL_REQUESTED"])
def test_startup_reconciles_without_replaying_and_preserves_ambiguous_claims(provisioned, state):
    path, value = provisioned
    db_path = Path(value["roots"]["jobs"]) / "jobs.sqlite"
    db_path.touch(mode=0o600)
    ledger = JobLedger(db_path)
    try:
        job = ledger.reserve(owner_id=value["owner_id"], request_id=str(uuid4()), kind="CHAT",
            digest="ab" * 32, route_revision="sample-r1", now_ms=NOW, deadline_ms=NOW + 60_000)
        claim = ledger.claim(job.owner_id, job.id, now_ms=NOW + 1) if state != "QUEUED" else None
        if state == "CANCEL_REQUESTED":
            ledger.cancel(job.owner_id, job.id, now_ms=NOW + 1)
        if state == "QUEUED":
            with TestClient(owner.create_owner_api(path, clock_ms=lambda: NOW + 2)):
                assert ledger.get(job.owner_id, job.id).state == "UNKNOWN"
        else:
            with pytest.raises(owner.OwnerApiError):
                with TestClient(owner.create_owner_api(path, clock_ms=lambda: NOW + 2)):
                    pass
            assert ledger.get(job.owner_id, job.id).state == state
            ledger.settle_stopped(job.owner_id, job.id, claim.token, "UNKNOWN", now_ms=NOW + 2)
            with TestClient(owner.create_owner_api(path, clock_ms=lambda: NOW + 3)):
                pass
    finally:
        ledger.engine.dispose()


def test_startup_failure_after_service_start_drains_and_releases_all_resources(provisioned, monkeypatch):
    path, _ = provisioned
    original = owner.AiJobService.start
    captured = []
    async def fail_after_start(self):
        captured.append(self)
        await original(self)
        raise ValueError("private synthetic startup error")
    monkeypatch.setattr(owner.AiJobService, "start", fail_after_start)
    with pytest.raises(owner.OwnerApiError) as error:
        with TestClient(owner.create_owner_api(path, clock_ms=lambda: NOW + 2)):
            pass
    assert "private synthetic" not in str(error.value)
    assert captured[0]._drain_task is None and not captured[0].inference_ready
    monkeypatch.setattr(owner.AiJobService, "start", original)
    with TestClient(owner.create_owner_api(path, clock_ms=lambda: NOW + 3)):
        pass


def test_partial_database_constructor_failure_disposes_its_engine(provisioned, monkeypatch):
    path, _ = provisioned
    original = owner.RequestProofVerifier.__init__
    disposed = []
    def fail(self, *args, **kwargs):
        original(self, *args, **kwargs)
        dispose = self.engine.dispose
        def record():
            disposed.append(True)
            dispose()
        self.engine.dispose = record
        raise ValueError("private synthetic database error")
    monkeypatch.setattr(owner.RequestProofVerifier, "__init__", fail)
    with pytest.raises(owner.OwnerApiError):
        with TestClient(owner.create_owner_api(path, clock_ms=lambda: NOW + 2)):
            pass
    assert disposed == [True]
    monkeypatch.setattr(owner.RequestProofVerifier, "__init__", original)
    with TestClient(owner.create_owner_api(path, clock_ms=lambda: NOW + 3)):
        pass


@pytest.mark.parametrize("target", ["identity", "proofs", "jobs", "lock", "directory"])
def test_replaced_storage_identity_fails_closed_without_unlinking_replacement(running, target):
    run = running
    value = run.config
    if target == "directory":
        entry = Path(value["roots"]["proofs"])
        entry.rename(entry.with_name("old-proofs"))
        entry.mkdir(mode=0o700)
    else:
        kind = "jobs" if target == "lock" else target
        filename = "owner-api.lock" if target == "lock" else owner.DATABASES[kind]
        entry = Path(value["roots"][kind]) / filename
        entry.rename(entry.with_suffix(".old"))
        entry.touch(mode=0o600)
    assert signed_get(run.client, run.key, run.tokens, "/api/ai/v1/session/status").status_code == 503
    assert entry.exists()


@pytest.mark.parametrize("change", ["valid", "regular", "symlink", "mode", "owner", "group", "hardlink"])
def test_supervisor_socket_metadata_is_checked_without_following_links(provisioned, monkeypatch, change):
    path, _ = provisioned
    config = replace(owner.read_configuration(path), socket_path=path.parent / "socket-record")
    info = SimpleNamespace(st_mode=stat.S_IFSOCK | 0o660, st_uid=os.geteuid(),
        st_gid=os.getegid(), st_nlink=1)
    if change == "regular":
        info.st_mode = stat.S_IFREG | 0o660
    elif change == "symlink":
        info.st_mode = stat.S_IFLNK | 0o660
    elif change == "mode":
        info.st_mode = stat.S_IFSOCK | 0o666
    elif change == "owner":
        info.st_uid += 1
    elif change == "group":
        info.st_gid += 1
    elif change == "hardlink":
        info.st_nlink = 2
    original = os.stat
    calls = []
    def synthetic_stat(value, *args, **kwargs):
        if value == "socket-record":
            calls.append(kwargs)
            return info
        return original(value, *args, **kwargs)
    monkeypatch.setattr(owner.os, "stat", synthetic_stat)
    if change == "valid":
        CHECK_SUPERVISOR(config)
    else:
        with pytest.raises(ValueError):
            CHECK_SUPERVISOR(config)
    assert len(calls) == 1 and calls[0]["follow_symlinks"] is False
    assert type(calls[0]["dir_fd"]) is int


@pytest.mark.parametrize("change", ["signer", "revoked-root", "stale-snapshot", "future-snapshot"])
def test_composed_activation_keeps_strict_attestation_checks(running, change):
    run = running
    code = run.resources.auth.issue_subscription(owner_id=run.config["owner_id"], duration_days=30, now_ms=NOW)
    request_id = str(uuid4())
    started = run.client.post("/api/ai/v1/activation/start", json=dict(code=code, request_id=request_id))
    assert started.status_code == 200
    key, chain = device(run.synthetic, SimpleNamespace(**started.json()))
    policy = owner.read_configuration(run.path).attestation
    if change == "signer":
        policy = replace(policy, allowed_signer_sha256=frozenset({"ff" * 32}))
    elif change == "revoked-root":
        policy = replace(policy, revoked_certificate_sha256=policy.trusted_root_sha256)
    elif change == "stale-snapshot":
        policy = replace(policy, revocation_checked_ms=NOW - 7 * 86_400_000)
    else:
        policy = replace(policy, revocation_checked_ms=NOW + 60_000)
    run.resources.activation.attestation = AndroidAttestationVerifier(policy)
    body = encoded(dict(request_id=request_id,
        certificate_chain=[base64.b64encode(item).decode() for item in chain]))
    path = "/api/ai/v1/activation/complete"
    response = run.client.post(path, content=body, headers={"Content-Type": "application/json"} |
        proof_headers(signed(key, path=path, body=body, credential=request_id), key))
    assert response.status_code == 401
    assert run.channel.requests == []


def test_revoke_between_signature_and_storage_is_rechecked(running, monkeypatch):
    run = running
    original = run.resources.proofs.verify
    def revoke_after_proof(**kwargs):
        value = original(**kwargs)
        run.resources.auth.revoke_attested_device(owner_id=run.config["owner_id"],
            key_fingerprint=key_fingerprint(run.key), now_ms=NOW + 2)
        return value
    monkeypatch.setattr(run.resources.proofs, "verify", revoke_after_proof)
    assert signed_post(run.client, run.key, run.tokens, "batches", batch()).status_code == 401
    assert not list(run.resources.store.root.glob("*.sqlite"))


def test_revocation_before_dispatch_prevents_supervisor_input(running, monkeypatch):
    run = running
    original = run.resources.auth.authorize_attested_session
    checked = []
    def revoke_before_dispatch(**kwargs):
        checked.append(True)
        if len(checked) >= 3:  # pre-body, post-proof, then stable dispatch binding
            run.resources.auth.revoke_attested_device(owner_id=run.config["owner_id"],
                key_fingerprint=key_fingerprint(run.key), now_ms=NOW + 2)
        return original(**kwargs)
    monkeypatch.setattr(run.resources.auth, "authorize_attested_session", revoke_before_dispatch)
    response = submit(run.client, run.key, run.tokens, b'{"text":"Sample"}',
        request_id=str(uuid4()), deadline_ms=NOW + 60_000)
    assert response.status_code == 202
    wait_for_background_state(lambda: run.resources.ledger.get(
        run.config["owner_id"], response.json()["job_id"]).state == "CANCELLED")
    assert run.channel.requests == []


def test_shutdown_cancels_active_turn_and_joins_socket_before_disposal(running):
    run = running
    run.channel.block = True
    response = submit(run.client, run.key, run.tokens, b'{"text":"Sample"}',
        request_id=str(uuid4()), deadline_ms=NOW + 60_000)
    assert response.status_code == 202 and run.channel.entered.wait(5)
    # TestClient's real lifespan exit occurs in the fixture after this assertion;
    # drive the same owned close first to assert the cancellation receipt/order.
    run.client.portal.call(run.resources.jobs.close)
    assert all(writer.closed for writer in run.channel.connections)
    assert run.resources.ledger.get(run.config["owner_id"], response.json()["job_id"]).state == "CANCELLED"
    assert not run.resources.jobs.inference_ready


def test_cancelled_startup_waits_for_service_then_closes_it(provisioned, monkeypatch):
    path, _ = provisioned
    original = owner.AiJobService.start
    captured = []
    async def scenario():
        entered, release = asyncio.Event(), asyncio.Event()
        async def delayed(self):
            captured.append(self)
            entered.set()
            await release.wait()
            await original(self)
        monkeypatch.setattr(owner.AiJobService, "start", delayed)
        app = owner.create_owner_api(path, clock_ms=lambda: NOW + 2)
        context = app._lifespan(app)
        task = asyncio.create_task(context.__aenter__())
        await entered.wait()
        task.cancel()
        await asyncio.sleep(0)
        assert not task.done()
        release.set()
        with pytest.raises(asyncio.CancelledError):
            await task
        assert app.resources is None and captured[0]._drain_task is None
        monkeypatch.setattr(owner.AiJobService, "start", original)
        async with owner.create_owner_api(path, clock_ms=lambda: NOW + 3)._lifespan(app):
            pass
    asyncio.run(scenario())


def test_cancelled_shutdown_drains_service_before_database_disposal(provisioned, monkeypatch):
    path, _ = provisioned
    original = owner.AiJobService.close
    async def scenario():
        entered, release = asyncio.Event(), asyncio.Event()
        async def delayed(self):
            entered.set()
            await release.wait()
            await original(self)
        monkeypatch.setattr(owner.AiJobService, "close", delayed)
        app = owner.create_owner_api(path, clock_ms=lambda: NOW + 2)
        context = app._lifespan(app)
        await context.__aenter__()
        resources = app.resources
        task = asyncio.create_task(context.__aexit__(None, None, None))
        await entered.wait()
        task.cancel()
        await asyncio.sleep(0)
        assert not task.done() and resources.jobs.inference_ready
        release.set()
        with pytest.raises(asyncio.CancelledError):
            await task
        assert app.resources is None and resources.jobs._drain_task is None
        async with owner.create_owner_api(path, clock_ms=lambda: NOW + 3)._lifespan(app):
            pass
    asyncio.run(scenario())


def test_idle_periodic_purge_keeps_original_365_day_expiry(running, monkeypatch):
    run = running
    policy = owner.read_configuration(run.path).policy
    job = run.resources.ledger.reserve(owner_id=run.config["owner_id"], request_id=str(uuid4()),
        kind="CHAT", digest="ab" * 32, route_revision=policy.route_revision,
        now_ms=NOW + 2, deadline_ms=NOW + 60_000)
    job = replace(job, session_id=str(uuid4()), key_hash=key_fingerprint(run.key))
    run.resources.store.accept_chat(job, ChatRequest("Synthetic retained question"), policy, now_ms=NOW + 2)
    stored = next(run.resources.store.root.glob("*.sqlite"))
    run.clock.value = NOW + 2 + RETENTION_MS
    async def scenario():
        monkeypatch.setattr(owner, "PURGE_SECONDS", 0.001)
        stop = asyncio.Event()
        original = run.resources.store.purge
        def purge(**kwargs):
            original(**kwargs)
        monkeypatch.setattr(run.resources.store, "purge", purge)
        task = asyncio.create_task(run.app._maintain(run.resources, stop))
        try:
            for _ in range(100):
                with sqlite3.connect(stored) as db:
                    if db.execute("SELECT count(*) FROM server_chat").fetchone()[0] == 0:
                        break
                await asyncio.sleep(0.005)
            else:
                pytest.fail("idle archive purge did not finish")
        finally:
            stop.set()
            await task
    asyncio.run(scenario())
    assert not run.app._unavailable


def test_configuration_read_detects_in_place_racing_change(provisioned, monkeypatch):
    path, _ = provisioned
    original = owner.os.read
    changed = False
    def mutate(fd, size):
        nonlocal changed
        data = original(fd, size)
        if data and not changed:
            changed = True
            with path.open("ab") as stream:
                stream.write(b" ")
        return data
    monkeypatch.setattr(owner.os, "read", mutate)
    with pytest.raises(owner.OwnerApiError):
        owner.read_configuration(path)


def test_resources_are_not_used_without_lifespan(provisioned):
    path, value = provisioned
    client = TestClient(owner.create_owner_api(path, clock_ms=lambda: NOW + 2))
    assert client.get("/api/ai/v1/session/status").status_code == 503
    assert not list(Path(value["roots"]["identity"]).iterdir())


def test_sqlite_journal_removal_during_inventory_is_normal_not_storage_drift(provisioned, monkeypatch):
    path, _ = provisioned
    app = owner.create_owner_api(path, clock_ms=lambda: NOW + 2)
    with TestClient(app):
        root = app.resources.roots["proofs"]
        original = owner.os.scandir
        inspected = []
        class RemovedJournal:
            name = "proofs.sqlite-journal"
            def stat(self, *, follow_symlinks):
                assert follow_symlinks is False
                inspected.append(True)
                raise FileNotFoundError()
        class Scan:
            def __enter__(self):
                return iter([RemovedJournal()])
            def __exit__(self, *args):
                pass
        def racing_scan(path):
            target = type(path) is int and owner._identity(os.fstat(path))[:2] == root.identity[:2]
            return Scan() if target else original(path)
        monkeypatch.setattr(owner.os, "scandir", racing_scan)
        root.inventory({"proofs.sqlite", "proofs.sqlite-journal"})
        assert inspected == [True]
        assert not app._unavailable


def test_overlapping_inventories_own_independent_directory_descriptions(provisioned, monkeypatch):
    _, value = provisioned
    with ExitStack() as stack:
        root = owner._Root(Path(value["roots"]["proofs"]), stack)
        root.database("proofs.sqlite")
        original = owner.os.scandir
        active, scanned = set(), []
        nested = False

        @contextmanager
        def overlapping_scan(fd):
            nonlocal nested
            assert fd != root.fd
            assert fd not in active
            assert owner._identity(os.fstat(fd))[:5] == root.identity
            scanned.append(fd)
            active.add(fd)
            try:
                with original(fd) as entries:
                    if not nested:
                        nested = True
                        root.inventory({"proofs.sqlite"})
                    yield entries
            finally:
                active.remove(fd)

        monkeypatch.setattr(owner.os, "scandir", overlapping_scan)
        root.inventory({"proofs.sqlite"})
        assert len(scanned) == 2 and not active
        assert os.fstat(root.fd).st_ino == root.identity[1]
        for fd in scanned:
            with pytest.raises(OSError):
                os.fstat(fd)


def test_startup_purges_expired_archive_without_reconstructing_outcomes(provisioned):
    path, value = provisioned
    config = owner.read_configuration(path)
    with TestClient(owner.create_owner_api(path, clock_ms=lambda: NOW + 2)) as client:
        resources = client.app.resources
        job = resources.ledger.reserve(owner_id=config.owner_id, request_id=str(uuid4()), kind="CHAT",
            digest="ab" * 32, route_revision=config.policy.route_revision,
            now_ms=NOW + 2, deadline_ms=NOW + 60_000)
        job = replace(job, session_id=str(uuid4()), key_hash="ab" * 32)
        resources.store.accept_chat(job, ChatRequest("Synthetic expired question"), config.policy, now_ms=NOW + 2)
        stored = next(resources.store.root.glob("*.sqlite"))
    now = NOW + 2 + RETENTION_MS
    value["attestation"]["revocation_checked_ms"] = now
    save(provisioned)
    with TestClient(owner.create_owner_api(path, clock_ms=lambda: now)) as client:
        with sqlite3.connect(stored) as db:
            assert db.execute("SELECT count(*) FROM server_chat").fetchone()[0] == 0
        assert client.app.resources.ledger.get(config.owner_id, job.id).state == "UNKNOWN"


def test_periodic_storage_failure_disables_admission_and_closes_jobs(provisioned, monkeypatch):
    path, _ = provisioned
    async def scenario():
        app = owner.create_owner_api(path, clock_ms=lambda: NOW + 2)
        async with app._lifespan(app):
            resources = app.resources
            monkeypatch.setattr(owner, "PURGE_SECONDS", 0.001)
            def fail(**kwargs):
                raise ValueError("private synthetic storage detail")
            monkeypatch.setattr(resources.store, "purge", fail)
            stop = asyncio.Event()
            await asyncio.wait_for(app._maintain(resources, stop), 2)
            assert app._unavailable and not resources.jobs.inference_ready
    asyncio.run(scenario())


def test_configuration_parent_replacement_during_read_is_rejected(provisioned, monkeypatch):
    path, _ = provisioned
    original = owner.os.read
    changed = False
    def replace_parent(fd, size):
        nonlocal changed
        data = original(fd, size)
        if data and not changed:
            changed = True
            path.parent.rename(path.parent.with_name(path.parent.name + "-old"))
            path.parent.mkdir(mode=0o700)
        return data
    monkeypatch.setattr(owner.os, "read", replace_parent)
    with pytest.raises(owner.OwnerApiError):
        owner.read_configuration(path)


def test_protected_key_fifo_is_rejected_without_blocking(provisioned):
    path, value = provisioned
    key = Path(value["history_key"])
    key.unlink()
    os.mkfifo(key, mode=0o600)
    with pytest.raises(owner.OwnerApiError):
        with TestClient(owner.create_owner_api(path, clock_ms=lambda: NOW + 2)):
            pass


def test_http_admission_rejects_unauthenticated_requests_before_storage_work(provisioned, monkeypatch):
    path, _ = provisioned
    app = owner.create_owner_api(path, clock_ms=lambda: NOW + 2)
    with TestClient(app) as client:
        checks = []
        original = app.resources.check
        def count():
            checks.append(True)
            original()
        monkeypatch.setattr(app.resources, "check", count)
        assert client.get("/api/ai/v1/capabilities").status_code == 401
        assert checks == []
