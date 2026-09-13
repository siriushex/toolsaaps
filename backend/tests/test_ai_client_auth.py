from concurrent.futures import ThreadPoolExecutor
from uuid import uuid4

import pytest
from sqlalchemy import create_engine

from app.ai.client_auth import AuthError, ClientAuth


NOW = 1_800_000_000_000


@pytest.fixture
def auth(tmp_path):
    store = ClientAuth(tmp_path / "identity.sqlite")
    yield store
    store.engine.dispose()


def connect(auth, owner=None):
    owner = owner or str(uuid4())
    code = auth.issue_enrollment(owner_id=owner, now_ms=NOW)
    return owner, code, auth.enroll(code, now_ms=NOW + 1)


def test_enrollment_access_and_expiry(auth):
    owner, code, tokens = connect(auth)
    assert auth.authenticate(tokens.access_token, now_ms=NOW + 2) == owner
    with pytest.raises(AuthError, match="unauthorized"):
        auth.authenticate(tokens.access_token, now_ms=tokens.access_expires_ms)
    with pytest.raises(AuthError, match="unauthorized"):
        auth.enroll(code, now_ms=NOW + 2)


def test_rotation_invalidates_old_access_and_refresh_replay_revokes_family(auth):
    _, _, old = connect(auth)
    new = auth.refresh(old.refresh_token, now_ms=NOW + 2)
    assert new.refresh_token != old.refresh_token
    with pytest.raises(AuthError):
        auth.authenticate(old.access_token, now_ms=NOW + 3)
    assert auth.authenticate(new.access_token, now_ms=NOW + 3)
    with pytest.raises(AuthError):
        auth.refresh(old.refresh_token, now_ms=NOW + 4)
    with pytest.raises(AuthError):
        auth.authenticate(new.access_token, now_ms=NOW + 5)
    with pytest.raises(AuthError):
        auth.refresh(new.refresh_token, now_ms=NOW + 5)


def test_revoke_is_isolated_and_idempotent(auth):
    _, _, first = connect(auth)
    second_owner, _, second = connect(auth)
    auth.revoke(first.refresh_token, now_ms=NOW + 2)
    auth.revoke(first.refresh_token, now_ms=NOW + 3)
    with pytest.raises(AuthError):
        auth.authenticate(first.access_token, now_ms=NOW + 4)
    assert auth.authenticate(second.access_token, now_ms=NOW + 4) == second_owner


def test_expired_enrollment_rejected(auth):
    code = auth.issue_enrollment(owner_id=str(uuid4()), now_ms=NOW)
    with pytest.raises(AuthError):
        auth.enroll(code, now_ms=NOW + 600_000)


def test_refresh_does_not_extend_absolute_session_lifetime(auth):
    _, _, first = connect(auth)
    new = auth.refresh(first.refresh_token, now_ms=first.refresh_expires_ms - 1)
    assert new.refresh_expires_ms == first.refresh_expires_ms
    assert new.access_expires_ms == first.refresh_expires_ms
    with pytest.raises(AuthError):
        auth.refresh(new.refresh_token, now_ms=new.refresh_expires_ms)


def test_concurrent_enrollment_is_once_across_instances(auth, tmp_path):
    code = auth.issue_enrollment(owner_id=str(uuid4()), now_ms=NOW)
    other = ClientAuth(tmp_path / "identity.sqlite")
    def attempt(index):
        try:
            return (auth if index % 2 else other).enroll(code, now_ms=NOW + 1)
        except AuthError:
            return None
    try:
        with ThreadPoolExecutor(max_workers=8) as pool:
            results = list(pool.map(attempt, range(16)))
        assert sum(result is not None for result in results) == 1
    finally:
        other.engine.dispose()


def test_concurrent_refresh_cannot_create_two_valid_successors(auth):
    _, _, old = connect(auth)
    def attempt(_):
        try:
            return auth.refresh(old.refresh_token, now_ms=NOW + 2)
        except AuthError:
            return None
    with ThreadPoolExecutor(max_workers=2) as pool:
        results = list(pool.map(attempt, range(2)))
    successful = [result for result in results if result is not None]
    assert len(successful) == 1
    with pytest.raises(AuthError):
        auth.authenticate(successful[0].access_token, now_ms=NOW + 3)


def test_credentials_are_not_stored_or_repr_exposed(auth, tmp_path):
    _, code, tokens = connect(auth)
    new = auth.refresh(tokens.refresh_token, now_ms=NOW + 2)
    auth.engine.dispose()
    content = (tmp_path / "identity.sqlite").read_bytes()
    for secret in (code, tokens.access_token, tokens.refresh_token,
                   new.access_token, new.refresh_token):
        assert secret.encode() not in content
        assert secret not in repr(tokens)
        assert secret not in repr(new)


@pytest.mark.parametrize("bad", [None, "", "invalid-secret", "a" * 5000, 42])
def test_bad_tokens_have_generic_errors(auth, bad):
    for method in (auth.enroll, auth.refresh, auth.authenticate, auth.revoke):
        with pytest.raises(AuthError) as error:
            method(bad, now_ms=NOW)
        assert str(error.value) == "unauthorized"


def test_tokens_cannot_be_used_for_other_purposes(auth):
    _, code, tokens = connect(auth)
    for method, token in ((auth.refresh, tokens.access_token),
                          (auth.authenticate, tokens.refresh_token),
                          (auth.enroll, tokens.refresh_token),
                          (auth.revoke, tokens.access_token),
                          (auth.authenticate, code)):
        with pytest.raises(AuthError):
            method(token, now_ms=NOW + 2)


def test_persistence_and_revocation_survive_reopen(auth, tmp_path):
    owner, _, tokens = connect(auth)
    other = ClientAuth(tmp_path / "identity.sqlite")
    try:
        assert other.authenticate(tokens.access_token, now_ms=NOW + 2) == owner
        other.revoke(tokens.refresh_token, now_ms=NOW + 3)
        with pytest.raises(AuthError):
            auth.authenticate(tokens.access_token, now_ms=NOW + 4)
    finally:
        other.engine.dispose()


def test_clock_before_issue_cannot_authenticate_or_rotate(auth):
    _, _, tokens = connect(auth)
    for method, token in ((auth.authenticate, tokens.access_token),
                          (auth.refresh, tokens.refresh_token)):
        with pytest.raises(AuthError):
            method(token, now_ms=NOW)


def test_rejects_other_database_without_modification(tmp_path):
    path = tmp_path / "therapy.sqlite"
    engine = create_engine(f"sqlite:///{path}")
    with engine.begin() as db:
        db.exec_driver_sql("CREATE TABLE therapy (value TEXT)")
    engine.dispose()
    before = path.read_bytes()
    with pytest.raises(ValueError, match="dedicated_database_required"):
        ClientAuth(path)
    assert path.read_bytes() == before


def test_failed_issuance_rolls_back_enrollment_and_session(auth, monkeypatch):
    code = auth.issue_enrollment(owner_id=str(uuid4()), now_ms=NOW)
    mint = auth._mint
    def failing_mint(db, kind, *args):
        if kind == "refresh":
            raise RuntimeError("synthetic_storage_failure")
        return mint(db, kind, *args)
    with monkeypatch.context() as patch:
        patch.setattr(ClientAuth, "_mint", staticmethod(failing_mint))
        with pytest.raises(RuntimeError, match="synthetic_storage_failure"):
            auth.enroll(code, now_ms=NOW + 1)
    tokens = auth.enroll(code, now_ms=NOW + 2)
    assert auth.authenticate(tokens.access_token, now_ms=NOW + 3)
    with auth.engine.connect() as db:
        assert db.exec_driver_sql("SELECT COUNT(*) FROM ai_client_sessions").scalar() == 1


def test_failed_refresh_does_not_consume_previous_credentials(auth, monkeypatch):
    owner, _, old = connect(auth)
    mint = auth._mint
    def failing_mint(db, kind, *args):
        if kind == "refresh":
            raise RuntimeError("synthetic_storage_failure")
        return mint(db, kind, *args)
    with monkeypatch.context() as patch:
        patch.setattr(ClientAuth, "_mint", staticmethod(failing_mint))
        with pytest.raises(RuntimeError):
            auth.refresh(old.refresh_token, now_ms=NOW + 2)
    assert auth.authenticate(old.access_token, now_ms=NOW + 3) == owner
    new = auth.refresh(old.refresh_token, now_ms=NOW + 4)
    assert auth.authenticate(new.access_token, now_ms=NOW + 5) == owner
