import inspect
import json
from uuid import uuid4

import pytest
from fastapi.testclient import TestClient

from app.ai_server import create_bound_ai_app
from test_ai_activation import NOW, service, signed
from test_ai_job_transport import activate, proof_headers, refresh_tokens, MutableClock


def test_history_is_independently_injected_not_enabled_by_default(service):
    assert "history" in inspect.signature(create_bound_ai_app).parameters, (
        "signed history injection is absent"
    )
    with TestClient(
        create_bound_ai_app(service[0], clock_ms=lambda: NOW + 2)
    ) as client:
        assert client.post("/api/ai/v1/history/batches", json={}).status_code == 404
        assert client.post("/api/ai/v1/history/query", json={}).status_code == 404


@pytest.fixture
def history_transport(service, tmp_path):
    from app.ai.history_service import HistoryService
    from app.ai.history_store import HistoryStore

    owner = str(uuid4())
    store = HistoryStore(
        tmp_path / "history", master_key=bytes(range(32)), min_free_bytes=0
    )
    history = HistoryService(service[1], service[0].proofs, store, owner_id=owner)
    clock = MutableClock()
    with TestClient(
        create_bound_ai_app(service[0], history=history, clock_ms=clock)
    ) as client:
        client.audit_clock = clock
        _, key, tokens = activate(client, service, owner_id=owner)
        yield client, store, owner, key, tokens


def signed_post(client, key, tokens, route, value, *, raw=None, proof_body=None):
    body = raw if raw is not None else json.dumps(value, separators=(",", ":")).encode()
    path = "/api/ai/v1/history/" + route
    proof = signed(
        key,
        path=path,
        body=body if proof_body is None else proof_body,
        credential=tokens["access_token"],
    )
    headers = {"Content-Type": "application/json"} | proof_headers(
        proof, key, access_token=tokens["access_token"]
    )
    return client.post(path, content=body, headers=headers)


def batch():
    return {
        "batchId": str(uuid4()),
        "records": [
            {
                "recordId": str(uuid4()),
                "revision": 1,
                "eventAtMs": NOW,
                "observedAtMs": NOW + 1,
                "kind": "GLUCOSE",
                "source": "CGM",
                "quality": "UNKNOWN",
                "deleted": False,
                "data": {"glucoseMmol": 6.4},
            }
        ],
    }


def query():
    return dict(
        fromMs=NOW - 1000,
        untilMs=NOW + 2,
        kinds=[],
        afterSequence=0,
        snapshotSequence=None,
        limit=32,
    )


def test_signed_history_roundtrip_retry_and_no_inference(history_transport):
    client, _, _, key, tokens = history_transport
    value = batch()
    first = signed_post(client, key, tokens, "batches", value)
    assert first.status_code == 200 and first.headers["cache-control"] == "no-store"
    assert signed_post(client, key, tokens, "batches", value).json() == first.json()
    result = signed_post(client, key, tokens, "query", query())
    assert result.status_code == 200
    assert result.json()["records"][0]["record"] == value["records"][0]
    path = "/api/ai/v1/session/status"
    headers = proof_headers(
        signed(
            key, method="GET", path=path, body=b"", credential=tokens["access_token"]
        ),
        key,
        access_token=tokens["access_token"],
    )
    assert client.get(path, headers=headers).json()["inference_enabled"] is False
    assert (
        client.post("/api/ai/v1/jobs", json={"text": "Synthetic question"}).status_code
        == 404
    )


def test_exact_body_proof_replay_and_unknown_identity_are_rejected(history_transport):
    client, _, _, key, tokens = history_transport
    value = batch()
    body = json.dumps(value).encode()
    path = "/api/ai/v1/history/batches"
    headers = {"Content-Type": "application/json"} | proof_headers(
        signed(key, path=path, body=body, credential=tokens["access_token"]),
        key,
        access_token=tokens["access_token"],
    )
    assert client.post(path, content=body, headers=headers).status_code == 200
    assert client.post(path, content=body, headers=headers).status_code == 401
    assert (
        signed_post(
            client, key, tokens, "batches", batch(), proof_body=body
        ).status_code
        == 401
    )
    assert (
        client.post(
            path,
            content=body,
            headers={
                "Content-Type": "application/json",
                "Authorization": "Bearer " + tokens["access_token"],
            },
        ).status_code
        == 401
    )
    response = signed_post(
        client, key, tokens, "batches", value | {"deviceId": "other-device"}
    )
    assert response.status_code == 400 and "other-device" not in response.text


def test_same_owner_other_device_and_other_owner_are_isolated(
    history_transport, service
):
    client, _, owner, key, tokens = history_transport
    assert signed_post(client, key, tokens, "batches", batch()).status_code == 200
    _, second_key, second_tokens = activate(client, service, owner_id=owner)
    assert (
        signed_post(client, second_key, second_tokens, "query", query()).json()[
            "records"
        ]
        == []
    )
    _, third_key, third_tokens = activate(client, service)
    assert (
        signed_post(client, third_key, third_tokens, "query", query()).status_code
        == 401
    )


def test_refresh_preserves_device_history_and_revocation_blocks_it(
    history_transport, service
):
    client, _, _, key, tokens = history_transport
    assert signed_post(client, key, tokens, "batches", batch()).status_code == 200
    refreshed = refresh_tokens(service, key, tokens)
    client.audit_clock.value = NOW + 3
    assert signed_post(client, key, refreshed, "query", query()).status_code == 200
    service[1].revoke(refreshed["refresh_token"], now_ms=NOW + 4)
    client.audit_clock.value = NOW + 4
    assert signed_post(client, key, refreshed, "query", query()).status_code == 401


def test_oversized_and_bad_nested_inputs_have_no_echo(history_transport):
    client, _, _, key, tokens = history_transport
    result = signed_post(client, key, tokens, "batches", {}, raw=b"x" * 131073)
    assert result.status_code == 413
    bad = batch()
    bad["records"][0]["data"]["apiKey"] = "synthetic-sensitive-value"
    result = signed_post(client, key, tokens, "batches", bad)
    assert result.status_code == 400 and "synthetic-sensitive-value" not in result.text


def test_storage_failure_does_not_leak_paths_or_payload(history_transport, monkeypatch):
    client, store, _, key, tokens = history_transport

    def fail(*args, **kwargs):
        raise OSError("synthetic private path and value")

    monkeypatch.setattr(store, "_open", fail)
    result = signed_post(client, key, tokens, "batches", batch())
    assert result.status_code == 503
    assert "synthetic private" not in result.text


def test_revocation_between_admission_and_proof_blocks_database_write(
    history_transport, service, monkeypatch
):
    client, store, owner, key, tokens = history_transport
    verify = service[0].proofs.verify

    def revoke_then_return(**values):
        result = verify(**values)
        service[1].revoke_owner(owner_id=owner, now_ms=NOW + 2)
        return result

    monkeypatch.setattr(service[0].proofs, "verify", revoke_then_return)
    assert signed_post(client, key, tokens, "batches", batch()).status_code == 401
    assert not list(store.root.glob("*.sqlite"))


def test_history_owner_scope_does_not_restrict_other_job_owners(service, tmp_path):
    from app.ai.history_service import HistoryService
    from app.ai.history_store import HistoryStore
    from app.ai.job_ledger import JobLedger
    from app.ai.job_service import AiJobService
    from test_ai_job_transport import SyntheticFactory, submit, wait_for_terminal

    owner = str(uuid4())
    history = HistoryService(
        service[1],
        service[0].proofs,
        HistoryStore(
            tmp_path / "history", master_key=bytes(range(32)), min_free_bytes=0
        ),
        owner_id=owner,
    )
    ledger = JobLedger(tmp_path / "jobs.sqlite")
    jobs = AiJobService(
        service[1],
        service[0].proofs,
        ledger,
        SyntheticFactory(),
        clock_ms=lambda: NOW + 2,
    )
    try:
        with TestClient(
            create_bound_ai_app(
                service[0], jobs=jobs, history=history, clock_ms=lambda: NOW + 2
            )
        ) as client:
            _, key, tokens = activate(client, service)
            assert signed_post(client, key, tokens, "query", query()).status_code == 401
            response = submit(
                client,
                key,
                tokens,
                b'{"text":"Synthetic independent request"}',
                request_id=str(uuid4()),
                deadline_ms=NOW + 60_000,
            )
            assert response.status_code == 202
            assert (
                wait_for_terminal(
                    client, key, tokens, response.json()["job_id"]
                ).json()["state"]
                == "SUCCEEDED"
            )
    finally:
        ledger.engine.dispose()
