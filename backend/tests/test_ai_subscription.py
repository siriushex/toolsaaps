import hashlib
import re
from concurrent.futures import ThreadPoolExecutor
from uuid import uuid4

import pytest
from sqlalchemy import create_engine

from app.ai.client_auth import AuthError, ClientAuth
from app.ai.android_attestation import AndroidAttestationVerifier
from test_android_attestation import CHALLENGE, NOW, _chain, _policy


DAY = 86_400_000


@pytest.fixture
def subscription(tmp_path):
    auth = ClientAuth(tmp_path / "identity.sqlite")
    chain, public_key = _chain()
    verifier = AndroidAttestationVerifier(_policy(chain))
    owner = str(uuid4())
    yield auth, owner, chain, verifier, hashlib.sha256(public_key).hexdigest()
    auth.engine.dispose()


def activate(subject, code, now=NOW + 1):
    auth, _, chain, verifier, _ = subject
    return auth.enroll_attested(code, certificate_chain_der=chain,
                               expected_challenge=CHALLENGE, verifier=verifier, now_ms=now)


def issue(subject, days=30):
    auth, owner, *_ = subject
    return auth.issue_subscription(owner_id=owner, duration_days=days, now_ms=NOW)


def test_short_code_activates_one_device_for_duration_from_activation(subscription):
    auth, owner, _, _, fingerprint = subscription
    code = issue(subscription, days=7)
    assert re.fullmatch(r"[0-9A-HJKMNP-TV-Z]{4}(?:-[0-9A-HJKMNP-TV-Z]{4}){3}", code)
    tokens = activate(subscription, "  " + code.lower().replace("-", " ") + "  ", NOW + 500)
    assert tokens.subscription_expires_ms == NOW + 500 + 7 * DAY
    assert tokens.refresh_expires_ms == tokens.subscription_expires_ms
    assert tokens.access_expires_ms == NOW + 500 + 600_000
    bound = auth.authenticate_attested(tokens.access_token, key_fingerprint=fingerprint,
                                      now_ms=NOW + 501)
    assert bound.owner_id == owner
    assert bound.subscription_expires_ms == tokens.subscription_expires_ms
    with pytest.raises(AuthError):
        activate(subscription, code, NOW + 501)


def test_subscription_code_cannot_use_unbound_bearer_pilot(subscription):
    auth, *_ = subscription
    code = issue(subscription)
    with pytest.raises(AuthError):
        auth.enroll(code, now_ms=NOW + 1)
    assert activate(subscription, code).subscription_expires_ms


def test_failed_attestation_does_not_consume_subscription(subscription):
    auth, _, chain, verifier, _ = subscription
    code = issue(subscription)
    with pytest.raises(AuthError):
        auth.enroll_attested(code, certificate_chain_der=chain,
                            expected_challenge=b"x" * 32, verifier=verifier, now_ms=NOW + 1)
    assert activate(subscription, code).subscription_expires_ms


def test_code_expires_separately_from_subscription(subscription):
    code = issue(subscription)
    with pytest.raises(AuthError):
        activate(subscription, code, NOW + DAY)
    assert activate(subscription, code, NOW + DAY - 1).subscription_expires_ms == NOW + 31 * DAY - 1


@pytest.mark.parametrize("days", [0, -1, 367, True, 1.5, "30", None])
def test_duration_must_be_explicit_and_bounded(subscription, days):
    with pytest.raises(ValueError):
        issue(subscription, days=days)


def test_refresh_renews_session_window_not_subscription(subscription):
    auth, _, _, _, fingerprint = subscription
    old = activate(subscription, issue(subscription, days=90))
    end = old.subscription_expires_ms
    assert old.refresh_expires_ms == NOW + 1 + 30 * DAY
    fresh = auth.refresh(old.refresh_token, now_ms=NOW + 29 * DAY)
    assert fresh.subscription_expires_ms == end
    assert fresh.refresh_expires_ms == NOW + 59 * DAY
    assert auth.authenticate_attested(fresh.access_token, key_fingerprint=fingerprint,
                                     now_ms=NOW + 29 * DAY + 1).subscription_expires_ms == end


def test_subscription_expiry_caps_both_credentials(subscription):
    auth, _, _, _, fingerprint = subscription
    tokens = activate(subscription, issue(subscription, days=1))
    end = tokens.subscription_expires_ms
    final = auth.refresh(tokens.refresh_token, now_ms=end - 1)
    assert final.access_expires_ms == final.refresh_expires_ms == end
    for call in (
        lambda: auth.authenticate(final.access_token, now_ms=end),
        lambda: auth.authenticate_attested(final.access_token, key_fingerprint=fingerprint, now_ms=end),
        lambda: auth.refresh(final.refresh_token, now_ms=end),
    ):
        with pytest.raises(AuthError):
            call()


def test_concurrent_activation_creates_one_subscription(subscription, tmp_path):
    auth, owner, chain, verifier, fingerprint = subscription
    other = ClientAuth(tmp_path / "identity.sqlite")
    code = issue(subscription)
    def attempt(i):
        try:
            return activate((auth if i % 2 else other, owner, chain, verifier, fingerprint), code)
        except AuthError:
            return None
    try:
        with ThreadPoolExecutor(max_workers=4) as pool:
            results = list(pool.map(attempt, range(8)))
        assert sum(result is not None for result in results) == 1
        with auth.engine.connect() as db:
            assert db.exec_driver_sql("SELECT COUNT(*) FROM ai_client_sessions").scalar_one() == 1
            assert db.exec_driver_sql("SELECT COUNT(*) FROM ai_subscription_grants WHERE session_id IS NOT NULL").scalar_one() == 1
    finally:
        other.engine.dispose()


def test_revoke_subscription_and_pending_code_survives_restart(subscription, tmp_path):
    auth, owner, _, _, fingerprint = subscription
    used_code = issue(subscription)
    tokens = activate(subscription, used_code)
    pending_code = issue(subscription)
    auth.revoke_owner(owner_id=owner, now_ms=NOW + 2)
    reopened = ClientAuth(tmp_path / "identity.sqlite")
    try:
        with pytest.raises(AuthError):
            reopened.authenticate_attested(tokens.access_token, key_fingerprint=fingerprint, now_ms=NOW + 3)
        with pytest.raises(AuthError):
            activate((reopened, *subscription[1:]), pending_code, NOW + 3)
    finally:
        reopened.engine.dispose()
    contents = (tmp_path / "identity.sqlite").read_bytes()
    for secret in (used_code, used_code.replace("-", ""), pending_code, tokens.access_token, tokens.refresh_token):
        assert secret.encode("ascii") not in contents
        assert secret not in repr(tokens)


def test_failed_token_write_rolls_back_grant_activation(subscription, monkeypatch):
    auth, *_ = subscription
    code = issue(subscription)
    mint = ClientAuth._mint
    def fail_refresh(db, kind, *args):
        if kind == "refresh":
            raise RuntimeError("synthetic_write_failure")
        return mint(db, kind, *args)
    with monkeypatch.context() as patch:
        patch.setattr(ClientAuth, "_mint", staticmethod(fail_refresh))
        with pytest.raises(RuntimeError, match="synthetic_write_failure"):
            activate(subscription, code)
    tokens = activate(subscription, code, NOW + 2)
    assert tokens.subscription_expires_ms == NOW + 2 + 30 * DAY


@pytest.mark.parametrize("code", [
    "A" * 15, "A" * 17, "A" * 10000, "I" * 16, "O" * 16, "U" * 16,
    "А" * 16, "A" * 15 + "\n", "A" * 15 + "\x00", None, 123,
])
def test_malformed_codes_rejected_before_activation(subscription, code):
    with pytest.raises(AuthError):
        activate(subscription, code)


def test_subscription_code_not_accepted_as_access_refresh_or_revoke(subscription):
    auth, *_ = subscription
    code = issue(subscription)
    for method in (auth.authenticate, auth.refresh, auth.revoke):
        with pytest.raises(AuthError):
            method(code, now_ms=NOW + 1)
    assert activate(subscription, code).subscription_expires_ms


def test_second_device_cannot_reuse_consumed_code(subscription):
    auth, owner, *_ = subscription
    code = issue(subscription)
    first = activate(subscription, code)
    other_chain, other_key = _chain()
    other = (auth, owner, other_chain, AndroidAttestationVerifier(_policy(other_chain)),
             hashlib.sha256(other_key).hexdigest())
    with pytest.raises(AuthError):
        activate(other, code)
    assert auth.authenticate(first.access_token, now_ms=NOW + 2) == owner


def test_unknown_code_does_not_run_expensive_certificate_verification(subscription, monkeypatch):
    def must_not_verify(*args, **kwargs):
        pytest.fail("invalid activation code reached certificate verification")
    monkeypatch.setattr(AndroidAttestationVerifier, "verify", must_not_verify)
    with pytest.raises(AuthError):
        activate(subscription, "0000-0000-0000-0000")


def test_revoked_device_cannot_refresh_subscription(subscription):
    auth, owner, _, _, fingerprint = subscription
    tokens = activate(subscription, issue(subscription))
    auth.revoke_attested_device(owner_id=owner, key_fingerprint=fingerprint, now_ms=NOW + 2)
    with pytest.raises(AuthError):
        auth.refresh(tokens.refresh_token, now_ms=NOW + 3)


def test_upgrade_adds_grants_without_rewriting_existing_identity_tables(tmp_path):
    from app.ai.client_auth import metadata
    path = tmp_path / "legacy.sqlite"
    engine = create_engine(f"sqlite:///{path}")
    tables = [table for table in metadata.sorted_tables if table.name != "ai_subscription_grants"]
    metadata.create_all(engine, tables=tables)
    owner, session_id = str(uuid4()), str(uuid4())
    secret = "access." + "a" * 43
    digest = hashlib.sha256(secret.encode("ascii")).hexdigest()
    with engine.begin() as db:
        db.exec_driver_sql("INSERT INTO ai_client_sessions (id,owner_id,expires_ms) VALUES (?,?,?)",
                           (session_id, owner, NOW + DAY))
        db.exec_driver_sql("INSERT INTO ai_client_credentials (digest,kind,owner_id,session_id,issued_ms,expires_ms) VALUES (?,?,?,?,?,?)",
                           (digest, "access", owner, session_id, NOW, NOW + 600_000))
        before = {table.name: db.exec_driver_sql(f'SELECT * FROM "{table.name}"').fetchall() for table in tables}
    auth = ClientAuth(path)
    try:
        with engine.connect() as db:
            for table in tables:
                assert db.exec_driver_sql(f'SELECT * FROM "{table.name}"').fetchall() == before[table.name]
            assert db.exec_driver_sql("PRAGMA integrity_check").scalar_one() == "ok"
        assert auth.authenticate(secret, now_ms=NOW + 1) == owner
        assert auth.issue_subscription(owner_id=owner, duration_days=30, now_ms=NOW)
    finally:
        auth.engine.dispose()
        engine.dispose()
