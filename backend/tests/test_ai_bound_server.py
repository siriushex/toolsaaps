import base64
import hashlib
import json
from uuid import uuid4

from fastapi.testclient import TestClient
from cryptography.hazmat.primitives import serialization

from app.ai_server import create_bound_ai_app
from test_ai_activation import NOW, device, service, signed


def headers(proof, key=None):
    result = {"Content-Type": "application/json", "X-Copilot-Signature": proof["signature"],
              "X-Copilot-Issued-Ms": str(proof["issued_ms"]), "X-Copilot-Nonce": proof["nonce"]}
    if key is not None:
        result["X-Copilot-Key"] = hashlib.sha256(key.public_key().public_bytes(
            serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo)).hexdigest()
    return result


def test_one_code_http_handshake_requires_app_proof_and_has_no_bearer_bypass(service):
    impl, auth, *_ = service
    code = auth.issue_subscription(owner_id=str(uuid4()), duration_days=30, now_ms=NOW)
    identity = str(uuid4())
    with TestClient(create_bound_ai_app(impl, clock_ms=lambda: NOW + 2)) as client:
        first = client.post("/api/ai/v1/activation/start", json={"code": code, "request_id": identity})
        assert first.status_code == 200
        assert first.headers["cache-control"] == "no-store"
        from types import SimpleNamespace
        key, chain = device(service, SimpleNamespace(**first.json()))
        body = json.dumps({"request_id": identity, "certificate_chain": [base64.b64encode(c).decode() for c in chain]}).encode()
        path = "/api/ai/v1/activation/complete"
        assert client.post(path, content=body, headers={"Content-Type": "application/json"}).status_code == 401
        response = client.post(path, content=body, headers=headers(signed(key, path=path, body=body, credential=identity)))
        assert response.status_code == 200
        tokens = response.json()
        recovered = client.post(path, content=body, headers=headers(
            signed(key, path=path, body=body, credential=identity)))
        assert recovered.status_code == 200
        assert recovered.json() == tokens
        assert tokens["subscription_expires_ms"] == NOW + 2 + 30 * 86_400_000
        status_path = "/api/ai/v1/session/status"
        bearer = {"Authorization": "Bearer " + tokens["access_token"]}
        assert client.get(status_path, headers=bearer).status_code == 401
        signed_headers = headers(signed(key, method="GET", path=status_path, body=b"", credential=tokens["access_token"]), key)
        result = client.get(status_path, headers=bearer | signed_headers)
        assert result.status_code == 200
        assert result.json()["inference_enabled"] is False
        assert client.get(status_path, headers=bearer | signed_headers).status_code == 401
        for bypass in ("auth/enroll", "auth/refresh", "capabilities", "jobs"):
            assert client.post("/api/ai/v1/" + bypass, json={"code": code}).status_code == 404


def test_bound_admission_rejects_excess_body_duplicates_and_queries(service):
    with TestClient(create_bound_ai_app(service[0], clock_ms=lambda: NOW + 2)) as client:
        assert client.post("/api/ai/v1/activation/start", content=b"x" * 2049,
                           headers={"Content-Type": "application/json"}).status_code == 413
        assert client.post("/api/ai/v1/activation/complete", content=b"x" * 96_001,
                           headers={"Content-Type": "application/json"}).status_code == 413
        assert client.post("/api/ai/v1/activation/start?code=secret", json={}).status_code == 400
        result = client.post("/api/ai/v1/activation/start", content=b'{"code":"secret","code":"x","request_id":"x"}',
                             headers={"Content-Type": "application/json"})
        assert result.status_code == 400
        assert "secret" not in result.text
