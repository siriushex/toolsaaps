import base64
import hashlib
import json
from contextlib import contextmanager
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
from threading import Event, local
from uuid import uuid4

import pytest
from cryptography import x509
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.hazmat.primitives.ciphers.aead import AESGCM
from sqlalchemy import create_engine
from cryptography.x509.oid import NameOID

from app.ai.client_auth import AuthError, ClientAuth
from app.ai.request_proof import RequestProofVerifier, signing_bytes
from app.ai.android_attestation import AndroidAttestationVerifier
from test_android_attestation import NOW, _certificate, _key_description, _policy


@pytest.fixture
def service(tmp_path):
    from app.ai.activation import ActivationService
    auth = ClientAuth(tmp_path / "identity.sqlite")
    proofs = RequestProofVerifier(tmp_path / "proof.sqlite")
    root_key = ec.generate_private_key(ec.SECP256R1())
    root_name = x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, "Synthetic activation root")])
    root = _certificate(root_name, root_name, root_key.public_key(), root_key, ca=True)
    root_der = root.public_bytes(serialization.Encoding.DER)
    verifier = AndroidAttestationVerifier(_policy([root_der]))
    receipt_key = AESGCM.generate_key(bit_length=256)
    service = ActivationService(auth, verifier, proofs, receipt_key=receipt_key)
    yield service, auth, root, root_key, receipt_key
    auth.engine.dispose()
    proofs.engine.dispose()


def start(service, request_id=None):
    impl, auth, *_ = service
    code = auth.issue_subscription(owner_id=str(uuid4()), duration_days=30, now_ms=NOW)
    identity = request_id or str(uuid4())
    challenge = impl.start(code=code, request_id=identity, now_ms=NOW + 1)
    return code, identity, challenge


def device(service, challenge):
    _, _, root, root_key, _ = service
    key = ec.generate_private_key(ec.SECP256R1())
    leaf = _certificate(x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, "Device")]),
                        root.subject, key.public_key(), root_key, ca=False,
                        extension=_key_description(challenge=base64.b64decode(challenge.challenge)))
    chain = [leaf.public_bytes(serialization.Encoding.DER), root.public_bytes(serialization.Encoding.DER)]
    return key, chain


def signed(key, *, path, body, credential, method="POST", now=NOW + 2):
    nonce = str(uuid4())
    message = signing_bytes(method=method, path=path, body=body, credential=credential,
                            issued_ms=now, nonce=nonce)
    return dict(signature=base64.urlsafe_b64encode(key.sign(message, ec.ECDSA(hashes.SHA256()))).decode().rstrip("="),
                issued_ms=now, nonce=nonce)


def complete(service, request_id, key, chain, *, now=NOW + 2):
    body = json.dumps({"request_id": request_id,
                       "certificate_chain": [base64.b64encode(c).decode() for c in chain]},
                      separators=(",", ":")).encode()
    return service[0].complete(body=body, now_ms=now, **signed(
        key, path="/api/ai/v1/activation/complete", body=body, credential=request_id, now=now))


def test_start_is_idempotent_without_consuming_code(service):
    impl, auth, *_ = service
    code, identity, challenge = start(service)
    assert impl.start(code=code, request_id=identity, now_ms=NOW + 2).challenge == challenge.challenge
    assert len(base64.b64decode(challenge.challenge)) == 32
    with auth.engine.connect() as db:
        assert db.exec_driver_sql("SELECT consumed_ms FROM ai_client_credentials").scalar_one() is None
    with pytest.raises(AuthError):
        impl.start(code=code, request_id=str(uuid4()), now_ms=NOW + 3)


def test_signed_activation_and_lost_response_recovery(service):
    _, auth, *_ = service
    _, identity, challenge = start(service)
    key, chain = device(service, challenge)
    tokens = complete(service, identity, key, chain)
    recovered = complete(service, identity, key, chain, now=NOW + 3)
    assert recovered == tokens
    assert auth.authenticate(tokens.access_token, now_ms=NOW + 4)
    with auth.engine.connect() as db:
        assert db.exec_driver_sql("SELECT COUNT(*) FROM ai_client_sessions").scalar_one() == 1


def test_activation_recovery_keeps_expired_access_and_usable_refresh(service):
    impl, auth, *_ = service
    _, identity, challenge = start(service)
    key, chain = device(service, challenge)
    tokens = complete(service, identity, key, chain)
    recovery_ms = tokens.access_expires_ms + 1

    recovered = complete(service, identity, key, chain, now=recovery_ms)
    assert recovered == tokens
    with pytest.raises(AuthError):
        auth.authenticate(recovered.access_token, now_ms=recovery_ms)

    fingerprint = hashlib.sha256(key.public_key().public_bytes(
        serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo)).hexdigest()
    body = json.dumps({"request_id": str(uuid4()), "refresh_token": recovered.refresh_token},
                      separators=(",", ":")).encode()
    refreshed = impl.refresh(body=body, key_fingerprint=fingerprint, now_ms=recovery_ms,
        **signed(key, path="/api/ai/v1/session/refresh", body=body,
                 credential=recovered.refresh_token, now=recovery_ms))
    assert auth.authenticate(refreshed.access_token, now_ms=recovery_ms + 1)


def test_challenge_cannot_be_swapped_between_codes(service):
    _, first, challenge1 = start(service)
    _, second, challenge2 = start(service)
    key, chain = device(service, challenge1)
    with pytest.raises(AuthError):
        complete(service, second, key, chain)
    assert complete(service, first, key, chain).subscription_expires_ms


def test_certificate_without_private_key_cannot_activate(service):
    _, identity, challenge = start(service)
    key, chain = device(service, challenge)
    wrong = ec.generate_private_key(ec.SECP256R1())
    with pytest.raises(AuthError):
        complete(service, identity, wrong, chain)
    assert complete(service, identity, key, chain).subscription_expires_ms


def test_recovery_after_revocation_is_rejected(service):
    _, auth, *_ = service
    _, identity, challenge = start(service)
    key, chain = device(service, challenge)
    tokens = complete(service, identity, key, chain)
    auth.revoke(tokens.refresh_token, now_ms=NOW + 3)
    with pytest.raises(AuthError):
        complete(service, identity, key, chain, now=NOW + 4)


def test_expired_challenge_cannot_activate(service):
    _, identity, challenge = start(service)
    key, chain = device(service, challenge)
    with pytest.raises(AuthError):
        complete(service, identity, key, chain, now=challenge.expires_ms)


def test_activation_body_rejects_duplicate_or_unknown_fields(service):
    impl, *_ = service
    _, identity, challenge = start(service)
    key, _ = device(service, challenge)
    for body in (b'{"request_id":"x","request_id":"y","certificate_chain":[]}',
                 b'{"request_id":"x","certificate_chain":[],"owner_id":"z"}'):
        with pytest.raises(AuthError):
            impl.complete(body=body, now_ms=NOW + 2, **signed(key,
                path="/api/ai/v1/activation/complete", body=body, credential=identity))


def test_bound_refresh_can_recover_lost_response_without_extending_subscription(service):
    impl, auth, *_ = service
    _, identity, challenge = start(service)
    key, chain = device(service, challenge)
    old = complete(service, identity, key, chain)
    fingerprint = hashlib.sha256(key.public_key().public_bytes(
        serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo)).hexdigest()
    body = json.dumps({"request_id": str(uuid4()), "refresh_token": old.refresh_token},
                      separators=(",", ":")).encode()
    def refresh(now):
        return impl.refresh(body=body, key_fingerprint=fingerprint, now_ms=now,
            **signed(key, path="/api/ai/v1/session/refresh", body=body, credential=old.refresh_token, now=now))
    first = refresh(NOW + 3)
    recovered = refresh(NOW + 4)
    assert recovered == first
    assert recovered.subscription_expires_ms == old.subscription_expires_ms
    assert auth.authenticate(first.access_token, now_ms=NOW + 5)


def test_concurrent_refresh_recovery_returns_one_stable_response(service):
    impl, auth, *_ = service
    _, identity, challenge = start(service)
    key, chain = device(service, challenge)
    old = complete(service, identity, key, chain)
    fingerprint = hashlib.sha256(key.public_key().public_bytes(
        serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo)).hexdigest()
    body = json.dumps({"request_id": str(uuid4()), "refresh_token": old.refresh_token},
                      separators=(",", ":")).encode()

    def refresh(now):
        return impl.refresh(body=body, key_fingerprint=fingerprint, now_ms=now,
            **signed(key, path="/api/ai/v1/session/refresh", body=body,
                     credential=old.refresh_token, now=now))

    first = refresh(NOW + 3)
    with ThreadPoolExecutor(max_workers=8) as pool:
        recovered = list(pool.map(refresh, range(NOW + 4, NOW + 12)))
    assert all(tokens == first for tokens in recovered)
    assert auth.authenticate(first.access_token, now_ms=NOW + 12)


def test_recovery_racing_later_refresh_cannot_revoke_new_family(service, monkeypatch):
    impl, auth, *_ = service
    _, identity, challenge = start(service)
    key, chain = device(service, challenge)
    old = complete(service, identity, key, chain)
    fingerprint = hashlib.sha256(key.public_key().public_bytes(
        serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo)).hexdigest()
    first_body = json.dumps({"request_id": str(uuid4()), "refresh_token": old.refresh_token},
                            separators=(",", ":")).encode()

    def refresh(body, credential, now):
        return impl.refresh(body=body, key_fingerprint=fingerprint, now_ms=now,
            **signed(key, path="/api/ai/v1/session/refresh", body=body,
                     credential=credential, now=now))

    first = refresh(first_body, old.refresh_token, NOW + 3)
    later_body = json.dumps({"request_id": str(uuid4()), "refresh_token": first.refresh_token},
                            separators=(",", ":")).encode()
    original_write = auth._write
    role = local()
    recovery_locked = Event()
    later_waiting = Event()
    release_recovery = Event()

    @contextmanager
    def ordered_write():
        if getattr(role, "value", None) == "later":
            later_waiting.set()
        with original_write() as db:
            if getattr(role, "value", None) == "recovery":
                recovery_locked.set()
                assert release_recovery.wait(5)
            yield db

    monkeypatch.setattr(auth, "_write", ordered_write)

    def recover():
        role.value = "recovery"
        return refresh(first_body, old.refresh_token, NOW + 4)

    def advance():
        role.value = "later"
        return refresh(later_body, first.refresh_token, NOW + 5)

    with ThreadPoolExecutor(max_workers=2) as pool:
        recovery = pool.submit(recover)
        assert recovery_locked.wait(5)
        later = pool.submit(advance)
        assert later_waiting.wait(5)
        release_recovery.set()
        recovered = recovery.result()
        advanced = later.result()

    assert recovered == first
    assert auth.authenticate(advanced.access_token, now_ms=NOW + 6)


def test_refresh_receipt_survives_original_expiry_but_not_its_own_boundary(service):
    impl, auth, *_ = service
    code = auth.issue_subscription(owner_id=str(uuid4()), duration_days=60, now_ms=NOW)
    identity = str(uuid4())
    challenge = impl.start(code=code, request_id=identity, now_ms=NOW + 1)
    key, chain = device(service, challenge)
    old = complete(service, identity, key, chain)
    fingerprint = hashlib.sha256(key.public_key().public_bytes(
        serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo)).hexdigest()
    request_id = str(uuid4())
    body = json.dumps({"request_id": request_id, "refresh_token": old.refresh_token},
                      separators=(",", ":")).encode()

    def refresh(body_value, credential, now):
        return impl.refresh(body=body_value, key_fingerprint=fingerprint, now_ms=now,
            **signed(key, path="/api/ai/v1/session/refresh", body=body_value,
                     credential=credential, now=now))

    accepted_ms = old.refresh_expires_ms - 1
    first = refresh(body, old.refresh_token, accepted_ms)
    recovered = refresh(body, old.refresh_token, old.refresh_expires_ms + 1)
    assert recovered == first
    assert recovered.subscription_expires_ms == old.subscription_expires_ms

    new_body = json.dumps({"request_id": str(uuid4()), "refresh_token": old.refresh_token},
                          separators=(",", ":")).encode()
    with pytest.raises(AuthError):
        refresh(new_body, old.refresh_token, old.refresh_expires_ms + 2)
    with pytest.raises(AuthError):
        refresh(body, old.refresh_token, accepted_ms + 600_000)


def test_refresh_receipt_retention_prunes_expired_operations(service):
    impl, auth, *_ = service
    _, identity, challenge = start(service)
    key, chain = device(service, challenge)
    old = complete(service, identity, key, chain)
    fingerprint = hashlib.sha256(key.public_key().public_bytes(
        serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo)).hexdigest()

    def refresh(credential, request_id, now):
        body = json.dumps({"request_id": request_id, "refresh_token": credential},
                          separators=(",", ":")).encode()
        return impl.refresh(body=body, key_fingerprint=fingerprint, now_ms=now,
            **signed(key, path="/api/ai/v1/session/refresh", body=body,
                     credential=credential, now=now))

    first = refresh(old.refresh_token, str(uuid4()), NOW + 3)
    refresh(first.refresh_token, str(uuid4()), NOW + 3 + 600_000)
    with auth.engine.connect() as db:
        assert db.exec_driver_sql("SELECT COUNT(*) FROM ai_refresh_requests").scalar_one() == 1
        assert db.exec_driver_sql(
            "SELECT COUNT(*) FROM ai_response_receipts WHERE operation = 'refresh'"
        ).scalar_one() == 1


def test_activation_respects_shared_receipt_cap_and_recovers_capacity(service, monkeypatch):
    monkeypatch.setattr("app.ai.activation.MAX_RECEIPTS", 1)
    _, auth, *_ = service
    _, first_id, first_challenge = start(service)
    _, second_id, second_challenge = start(service)
    first_key, first_chain = device(service, first_challenge)
    second_key, second_chain = device(service, second_challenge)
    first = complete(service, first_id, first_key, first_chain)

    with pytest.raises(AuthError):
        complete(service, second_id, second_key, second_chain, now=NOW + 3)
    with auth.engine.connect() as db:
        assert db.exec_driver_sql("SELECT COUNT(*) FROM ai_client_sessions").scalar_one() == 1
        assert db.exec_driver_sql("SELECT COUNT(*) FROM ai_response_receipts").scalar_one() == 1
    assert complete(service, first_id, first_key, first_chain, now=NOW + 4) == first

    with auth._write() as db:
        db.exec_driver_sql("UPDATE ai_response_receipts SET retain_until_ms = ?", (NOW + 5,))

    second = complete(service, second_id, second_key, second_chain, now=NOW + 6)
    assert auth.authenticate(second.access_token, now_ms=NOW + 7)
    with auth.engine.connect() as db:
        assert db.exec_driver_sql("SELECT COUNT(*) FROM ai_client_sessions").scalar_one() == 2
        assert db.exec_driver_sql("SELECT COUNT(*) FROM ai_response_receipts").scalar_one() == 1


def test_response_receipt_is_encrypted_and_requires_the_injected_key(service):
    from app.ai.activation import ActivationService

    impl, auth, root, root_key, receipt_key = service
    _, identity, challenge = start(service)
    key, chain = device(service, challenge)
    tokens = complete(service, identity, key, chain)
    with auth.engine.connect() as db:
        nonce, ciphertext = db.exec_driver_sql(
            "SELECT nonce, ciphertext FROM ai_response_receipts WHERE operation = 'activation'"
        ).one()
    for secret in (tokens.access_token, tokens.refresh_token):
        assert secret.encode() not in bytes(nonce) + bytes(ciphertext)
        assert secret.encode() not in Path(auth.engine.url.database).read_bytes()

    restarted = ActivationService(auth, impl.attestation, impl.proofs, receipt_key=receipt_key)
    restarted_service = restarted, auth, root, root_key, receipt_key
    assert complete(restarted_service, identity, key, chain, now=NOW + 3) == tokens

    wrong_key = AESGCM.generate_key(bit_length=256)
    unavailable = ActivationService(auth, impl.attestation, impl.proofs, receipt_key=wrong_key)
    unavailable_service = unavailable, auth, root, root_key, wrong_key
    with pytest.raises(AuthError):
        complete(unavailable_service, identity, key, chain, now=NOW + 4)


def test_activation_service_requires_explicit_receipt_key(service):
    from app.ai.activation import ActivationService

    impl, auth, *_ = service
    with pytest.raises(ValueError, match="receipt_key_required"):
        ActivationService(auth, impl.attestation, impl.proofs, receipt_key=None)


def test_client_auth_adds_receipt_table_without_rewriting_identity_rows(tmp_path):
    from app.ai.client_auth import metadata, response_receipts

    path = tmp_path / "legacy-identity.sqlite"
    engine = create_engine(f"sqlite:///{path}")
    legacy_tables = [table for table in metadata.sorted_tables
                     if table.name != response_receipts.name]
    metadata.create_all(engine, tables=legacy_tables)
    owner_id, session_id = str(uuid4()), str(uuid4())
    with engine.begin() as db:
        db.exec_driver_sql(
            "INSERT INTO ai_client_sessions (id,owner_id,expires_ms) VALUES (?,?,?)",
            (session_id, owner_id, NOW + 600_000))
        before = db.exec_driver_sql("SELECT * FROM ai_client_sessions").fetchall()
    engine.dispose()

    auth = ClientAuth(path)
    try:
        with auth.engine.connect() as db:
            assert db.exec_driver_sql(
                "SELECT * FROM ai_client_sessions").fetchall() == before
            assert db.exec_driver_sql(
                "SELECT name FROM sqlite_master WHERE type='table' AND name='ai_response_receipts'"
            ).scalar_one() == "ai_response_receipts"
            assert db.exec_driver_sql("PRAGMA integrity_check").scalar_one() == "ok"
    finally:
        auth.engine.dispose()


def test_concurrent_complete_has_one_grant_and_session(service):
    _, auth, *_ = service
    _, identity, challenge = start(service)
    key, chain = device(service, challenge)
    def attempt(_):
        try:
            return complete(service, identity, key, chain)
        except AuthError:
            return None
    with ThreadPoolExecutor(max_workers=4) as pool:
        results = list(pool.map(attempt, range(8)))
    assert any(results)
    with auth.engine.connect() as db:
        assert db.exec_driver_sql("SELECT COUNT(*) FROM ai_client_sessions").scalar_one() == 1
        assert db.exec_driver_sql("SELECT COUNT(*) FROM ai_subscription_grants WHERE session_id IS NOT NULL").scalar_one() == 1


def test_start_request_id_cannot_select_another_owners_grant(service):
    impl, auth, *_ = service
    _, identity, _ = start(service)
    different = auth.issue_subscription(owner_id=str(uuid4()), duration_days=7, now_ms=NOW)
    with pytest.raises(AuthError):
        impl.start(code=different, request_id=identity, now_ms=NOW + 2)


def test_attestation_failure_leaves_code_and_challenge_recoverable(service):
    _, identity, challenge = start(service)
    key, chain = device(service, challenge)
    with pytest.raises(AuthError):
        complete(service, identity, key, [b"invalid", chain[-1]])
    assert complete(service, identity, key, chain).subscription_expires_ms


def test_complete_recovery_does_not_roll_back_a_later_refresh(service):
    impl, auth, *_ = service
    _, identity, challenge = start(service)
    key, chain = device(service, challenge)
    tokens = complete(service, identity, key, chain)
    new = auth.refresh(tokens.refresh_token, now_ms=NOW + 3)
    with pytest.raises(AuthError):
        complete(service, identity, key, chain, now=NOW + 4)
    assert auth.authenticate(new.access_token, now_ms=NOW + 5)
