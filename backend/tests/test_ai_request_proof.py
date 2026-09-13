import base64
from concurrent.futures import ThreadPoolExecutor
from uuid import uuid4

import pytest
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec

from app.ai.client_auth import AuthError
from app.ai.request_proof import RequestProofVerifier, signing_bytes

NOW = 1_800_000_000_000


@pytest.fixture
def setup(tmp_path):
    key = ec.generate_private_key(ec.SECP256R1())
    public = key.public_key().public_bytes(serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo)
    guard = RequestProofVerifier(tmp_path / "proofs.sqlite")
    args = dict(method="POST", path="/api/ai/v1/jobs", body=b'{"synthetic":true}',
                credential="synthetic-token", issued_ms=NOW, nonce=str(uuid4()))
    yield guard, key, public, args
    guard.engine.dispose()


def sign(key, args):
    return base64.urlsafe_b64encode(key.sign(signing_bytes(**args), ec.ECDSA(hashes.SHA256()))).decode().rstrip("=")


def test_signature_valid_once_and_duplicate_blocked(setup):
    guard, key, public, args = setup
    signature = sign(key, args)
    fingerprint = guard.verify(public_key_der=public, signature=signature, now_ms=NOW, **args)
    assert len(fingerprint) == 64
    with pytest.raises(AuthError):
        guard.verify(public_key_der=public, signature=signature, now_ms=NOW + 1, **args)


@pytest.mark.parametrize("change", [{"body": b"changed"}, {"method": "DELETE"},
    {"path": "/api/ai/v1/auth/refresh"}, {"credential": "stolen-token"},
    {"issued_ms": NOW + 1}, {"nonce": str(uuid4())}])
def test_tampering_rejected_without_consuming_real_nonce(setup, change):
    guard, key, public, args = setup
    signature = sign(key, args)
    with pytest.raises(AuthError):
        guard.verify(public_key_der=public, signature=signature, now_ms=NOW, **(args | change))
    assert guard.verify(public_key_der=public, signature=signature, now_ms=NOW, **args)


@pytest.mark.parametrize("clock", [NOW - 60_001, NOW + 60_000])
def test_stale_or_far_future_signature_rejected(setup, clock):
    guard, key, public, args = setup
    with pytest.raises(AuthError):
        guard.verify(public_key_der=public, signature=sign(key, args), now_ms=clock, **args)


def test_wrong_key_rejected(setup):
    guard, _, public, args = setup
    other = ec.generate_private_key(ec.SECP256R1())
    with pytest.raises(AuthError):
        guard.verify(public_key_der=public, signature=sign(other, args), now_ms=NOW, **args)


def test_cross_instance_concurrent_replay_is_atomic(setup, tmp_path):
    guard, key, public, args = setup
    other = RequestProofVerifier(tmp_path / "proofs.sqlite")
    signature = sign(key, args)
    def attempt(index):
        try:
            (guard if index % 2 else other).verify(public_key_der=public, signature=signature, now_ms=NOW, **args)
            return True
        except AuthError:
            return False
    with ThreadPoolExecutor(max_workers=8) as pool:
        assert sum(pool.map(attempt, range(16))) == 1
    other.engine.dispose()


@pytest.mark.parametrize("signature", ["", "%%", "a" * 1024, None])
def test_bad_encoding_has_generic_error(setup, signature):
    guard, _, public, args = setup
    with pytest.raises(AuthError, match="^unauthorized$"):
        guard.verify(public_key_der=public, signature=signature, now_ms=NOW, **args)


def test_storage_contains_no_body_or_token(setup, tmp_path):
    guard, key, public, args = setup
    signature = sign(key, args)
    guard.verify(public_key_der=public, signature=signature, now_ms=NOW, **args)
    guard.engine.dispose()
    data = (tmp_path / "proofs.sqlite").read_bytes()
    for private in (args["credential"].encode(), args["body"], signature.encode()):
        assert private not in data


def test_canonical_contract_does_not_include_source_ip(setup):
    _, _, _, args = setup
    message = signing_bytes(**args)
    assert b"https://diai.centv.ru" in message
    assert b"source_ip" not in message
    assert args["credential"].encode() not in message


def test_restart_retains_replay_receipt(setup, tmp_path):
    guard, key, public, args = setup
    signature = sign(key, args)
    guard.verify(public_key_der=public, signature=signature, now_ms=NOW, **args)
    other = RequestProofVerifier(tmp_path / "proofs.sqlite")
    with pytest.raises(AuthError):
        other.verify(public_key_der=public, signature=signature, now_ms=NOW + 1, **args)
    other.engine.dispose()


def test_unsupported_curve_rejected(setup):
    guard, _, _, args = setup
    key = ec.generate_private_key(ec.SECP384R1())
    public = key.public_key().public_bytes(serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo)
    with pytest.raises(AuthError):
        guard.verify(public_key_der=public, signature=sign(key, args), now_ms=NOW, **args)


def test_expired_receipts_pruned_without_reenabling_stale_signature(setup):
    guard, key, public, args = setup
    signature = sign(key, args)
    guard.verify(public_key_der=public, signature=signature, now_ms=NOW, **args)
    later = args | {"issued_ms": NOW + 60_000, "nonce": str(uuid4())}
    guard.verify(public_key_der=public, signature=sign(key, later), now_ms=NOW + 60_000, **later)
    with guard.engine.connect() as db:
        assert db.exec_driver_sql("SELECT count(*) FROM ai_request_proofs").scalar() == 1
    with pytest.raises(AuthError):
        guard.verify(public_key_der=public, signature=signature, now_ms=NOW + 60_000, **args)
