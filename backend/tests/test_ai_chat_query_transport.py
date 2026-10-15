from uuid import uuid4

from fastapi.testclient import TestClient

from app.ai.history_contract import encoded
from app.ai.server_chat_query import MAX_RESPONSE_BYTES
from app.ai_server import create_bound_ai_app
from test_ai_activation import NOW, service, signed  # noqa: F401 - shared fixture
from test_ai_history_transport import history_transport, signed_post  # noqa: F401 - shared fixture
from test_ai_job_transport import activate, key_fingerprint, proof_headers, refresh_tokens
from test_ai_server_chat_query import save

PATH = "/api/ai/v1/history/conversations"


def query(**changes):
    return dict(fromMs=NOW - 1000, untilMs=NOW + 2, cursor=None, limit=20) | changes


def test_archive_read_route_is_not_mounted_by_default(service):
    with TestClient(create_bound_ai_app(service[0], clock_ms=lambda: NOW + 2)) as client:
        assert client.post(PATH, json=query()).status_code == 404


def test_signed_full_text_read_is_no_store_without_enabling_inference(history_transport):
    client, history, owner, key, tokens = history_transport
    entry = save(history, owner=owner, key=key_fingerprint(key), now=NOW)
    response = signed_post(client, key, tokens, "conversations", query())
    assert response.status_code == 200 and response.headers["cache-control"] == "no-store"
    result = response.json()
    assert result["origin"] == "SERVER_ARCHIVE" and result["revision"] == "server-chat-archive-v1"
    assert result["records"][0]["jobId"] == entry.id
    assert result["records"][0]["answer"] == "Synthetic answer"
    assert client.post("/api/ai/v1/jobs", json={"text": "Synthetic question"}).status_code == 404


def test_signed_pagination_does_not_include_later_server_insert(history_transport):
    client, history, owner, key, tokens = history_transport
    entries = [save(history, owner=owner, key=key_fingerprint(key), now=NOW) for _ in range(2)]
    first = signed_post(client, key, tokens, "conversations", query(limit=1)).json()
    save(history, owner=owner, key=key_fingerprint(key), now=NOW)
    second = signed_post(client, key, tokens, "conversations", query(cursor=first["nextCursor"], limit=1)).json()
    assert [first["records"][0]["jobId"], second["records"][0]["jobId"]] == [entry.id for entry in entries]
    assert not second["hasMore"]


def test_same_owner_device_isolation_and_cursor_scope(history_transport, service):
    client, history, owner, key, tokens = history_transport
    for _ in range(2):
        save(history, owner=owner, key=key_fingerprint(key), now=NOW)
    cursor = signed_post(client, key, tokens, "conversations", query(limit=1)).json()["nextCursor"]
    _, other_key, other_tokens = activate(client, service, owner_id=owner)
    empty = signed_post(client, other_key, other_tokens, "conversations", query())
    assert empty.status_code == 200 and empty.json()["records"] == []
    denied = signed_post(client, other_key, other_tokens, "conversations", query(cursor=cursor))
    assert denied.status_code == 400 and cursor not in denied.text
    _, third_key, third_tokens = activate(client, service)
    assert signed_post(client, third_key, third_tokens, "conversations", query()).status_code == 401


def test_proof_is_bound_to_path_body_and_nonce_not_bearer_alone(history_transport):
    client, _, _, key, tokens = history_transport
    raw = encoded(query())
    headers = {"Content-Type": "application/json"} | proof_headers(
        signed(key, path=PATH, body=raw, credential=tokens["access_token"]),
        key, access_token=tokens["access_token"])
    assert client.post(PATH, content=raw, headers=headers).status_code == 200
    assert client.post(PATH, content=raw, headers=headers).status_code == 401
    assert signed_post(client, key, tokens, "conversations", query(limit=1), proof_body=raw).status_code == 401
    foreign_headers = {"Content-Type": "application/json"} | proof_headers(
        signed(key, path="/api/ai/v1/history/query", body=raw, credential=tokens["access_token"]),
        key, access_token=tokens["access_token"])
    assert client.post(PATH, content=raw, headers=foreign_headers).status_code == 401
    assert client.post(PATH, json=query(), headers={"Authorization": "Bearer " + tokens["access_token"]}).status_code == 401


def test_refresh_retains_archive_access_but_revocation_blocks_storage(history_transport, service, monkeypatch):
    client, history, owner, key, tokens = history_transport
    save(history, owner=owner, key=key_fingerprint(key), now=NOW)
    refreshed = refresh_tokens(service, key, tokens)
    client.audit_clock.value = NOW + 3
    assert signed_post(client, key, refreshed, "conversations", query()).json()["records"]
    calls = []
    original = history._open
    def observed(*args, **kwargs):
        calls.append(True)
        return original(*args, **kwargs)
    monkeypatch.setattr(history, "_open", observed)
    service[1].revoke_owner(owner_id=owner, now_ms=NOW + 3)
    assert signed_post(client, key, refreshed, "conversations", query()).status_code == 401
    assert calls == []


def test_revocation_during_proof_verification_prevents_archive_read(history_transport, service, monkeypatch):
    client, history, owner, key, tokens = history_transport
    save(history, owner=owner, key=key_fingerprint(key), now=NOW)
    original = service[0].proofs.verify
    def revoke(**values):
        result = original(**values)
        service[1].revoke_owner(owner_id=owner, now_ms=NOW + 2)
        return result
    monkeypatch.setattr(service[0].proofs, "verify", revoke)
    def forbidden(*args, **kwargs):
        raise AssertionError("storage must not be accessed")
    monkeypatch.setattr(history, "_open", forbidden)
    assert signed_post(client, key, tokens, "conversations", query()).status_code == 401


def test_query_size_and_unknown_identity_fields_are_not_echoed(history_transport):
    client, _, _, key, tokens = history_transport
    oversized = signed_post(client, key, tokens, "conversations", {}, raw=b"x" * 4097)
    assert oversized.status_code == 413
    identity = str(uuid4())
    invalid = signed_post(client, key, tokens, "conversations", query(ownerId=identity))
    assert invalid.status_code == 400 and identity not in invalid.text
    assert signed_post(client, key, tokens, "conversations", query(limit=True)).status_code == 400
    assert client.get(PATH).status_code == 405


def test_largest_full_message_fits_actual_http_response_budget(history_transport):
    client, history, owner, key, tokens = history_transport
    for _ in range(2):
        save(history, owner=owner, key=key_fingerprint(key), now=NOW,
             question="\u0001" * 4096, answer="\u0002" * 8192)
    response = signed_post(client, key, tokens, "conversations", query())
    assert response.status_code == 200 and len(response.content) <= MAX_RESPONSE_BYTES
    assert response.json()["hasMore"]
    assert response.json()["records"][0]["answer"] == "\u0002" * 8192


def test_storage_failure_never_echoes_payload_or_path(history_transport, monkeypatch):
    client, history, _, key, tokens = history_transport
    def fail(*args, **kwargs):
        raise OSError("Synthetic private archive payload and path")
    monkeypatch.setattr(history, "_open", fail)
    response = signed_post(client, key, tokens, "conversations", query())
    assert response.status_code == 503 and "Synthetic private" not in response.text
