import json
import subprocess
import sys
import time
from uuid import uuid4

import pytest

from app.ai.client_auth import AuthError, ClientAuth


def test_admin_revocation_invalidates_sessions_and_unused_codes(tmp_path):
    auth = ClientAuth(tmp_path / "identity.sqlite")
    now = time.time_ns() // 1_000_000
    owner, other = str(uuid4()), str(uuid4())
    code = auth.issue_enrollment(owner_id=owner, now_ms=now)
    tokens = auth.enroll(code, now_ms=now)
    unused = auth.issue_enrollment(owner_id=owner, now_ms=now)
    other_code = auth.issue_enrollment(owner_id=other, now_ms=now)
    auth.revoke_owner(owner_id=owner, now_ms=now + 1)
    auth.revoke_owner(owner_id=owner, now_ms=now + 2)
    with pytest.raises(AuthError):
        auth.authenticate(tokens.access_token, now_ms=now + 3)
    with pytest.raises(AuthError):
        auth.enroll(unused, now_ms=now + 3)
    other_tokens = auth.enroll(other_code, now_ms=now + 3)
    assert auth.authenticate(other_tokens.access_token, now_ms=now + 4) == other
    auth.engine.dispose()


def cli(*args):
    return subprocess.run([sys.executable, "-m", "app.ai.enrollment_admin", *map(str, args)],
                          capture_output=True, text=True, timeout=10)


@pytest.fixture
def private_store(tmp_path):
    folder = tmp_path / "private"
    folder.mkdir(mode=0o700)
    db = folder / "identity.sqlite"
    auth = ClientAuth(db)
    auth.engine.dispose()
    db.chmod(0o600)
    return folder, db


def test_cli_issues_private_file_and_revokes_owner(private_store):
    folder, db = private_store
    output, owner = folder / "enrollment.json", str(uuid4())
    result = cli("issue", "--database", db, "--owner", owner, "--output", output)
    assert result.returncode == 0, result.stderr
    payload = json.loads(output.read_text())
    assert set(payload) == {"version", "server_url", "code", "expires_ms"}
    assert payload["server_url"] == "https://diai.centv.ru"
    assert payload["version"] == 1
    assert output.stat().st_mode & 0o777 == 0o600
    assert payload["code"] not in result.stdout + result.stderr
    auth = ClientAuth(db)
    now = time.time_ns() // 1_000_000
    tokens = auth.enroll(payload["code"], now_ms=now)
    assert auth.authenticate(tokens.access_token, now_ms=now) == owner
    result = cli("revoke-owner", "--database", db, "--owner", owner)
    assert result.returncode == 0, result.stderr
    with pytest.raises(AuthError):
        auth.authenticate(tokens.access_token, now_ms=time.time_ns() // 1_000_000)
    auth.engine.dispose()


def test_cli_issues_one_field_subscription_code_with_explicit_duration(private_store):
    from app.ai.client_auth import SUBSCRIPTION_CODE_MS
    folder, db = private_store
    output = folder / "subscription.json"
    before = time.time_ns() // 1_000_000
    result = cli("issue-subscription", "--database", db, "--owner", uuid4(),
                 "--days", "90", "--output", output)
    assert result.returncode == 0, result.stderr
    after = time.time_ns() // 1_000_000
    payload = json.loads(output.read_text())
    assert set(payload) == {"version", "server_url", "code", "expires_ms", "duration_days"}
    assert payload["duration_days"] == 90
    assert payload["version"] == 2
    assert before + SUBSCRIPTION_CODE_MS <= payload["expires_ms"] <= after + SUBSCRIPTION_CODE_MS
    assert len(payload["code"]) == 19
    assert output.stat().st_mode & 0o777 == 0o600
    assert payload["code"] not in result.stdout + result.stderr


@pytest.mark.parametrize("command,days", [
    ("issue-subscription", None), ("issue-subscription", "0"),
    ("issue-subscription", "367"), ("issue-subscription", "invalid"),
    ("issue", "30"), ("revoke-owner", "30"),
])
def test_cli_rejects_missing_invalid_or_irrelevant_subscription_duration(private_store, command, days):
    folder, db = private_store
    output = folder / "subscription.json"
    args = [command, "--database", db, "--owner", uuid4()]
    if command != "revoke-owner":
        args += ["--output", output]
    if days is not None:
        args += ["--days", days]
    result = cli(*args)
    assert result.returncode == 2
    assert result.stderr.strip() == "enrollment_admin_failed"
    assert not output.exists()


def test_refuses_existing_output_without_overwriting(private_store):
    folder, db = private_store
    output = folder / "enrollment.json"
    output.write_text("preserve")
    result = cli("issue", "--database", db, "--owner", uuid4(), "--output", output)
    assert result.returncode == 2
    assert result.stderr.strip() == "enrollment_admin_failed"
    assert output.read_text() == "preserve"


@pytest.mark.parametrize("unsafe", ["db_permissions", "dir_permissions", "db_symlink", "output_symlink", "missing_db"])
def test_refuses_unsafe_paths(private_store, unsafe):
    folder, db = private_store
    output = folder / "enrollment.json"
    if unsafe == "db_permissions":
        db.chmod(0o644)
    elif unsafe == "dir_permissions":
        folder.chmod(0o755)
    elif unsafe == "db_symlink":
        link = folder / "link.sqlite"
        link.symlink_to(db)
        db = link
    elif unsafe == "output_symlink":
        output.symlink_to(db)
    else:
        db = folder / "missing.sqlite"
    result = cli("issue", "--database", db, "--owner", uuid4(), "--output", output)
    assert result.returncode == 2
    assert result.stderr.strip() == "enrollment_admin_failed"
    assert "Traceback" not in result.stderr
    assert not output.exists() or output.is_symlink()


def test_invalid_owner_not_echoed(private_store):
    folder, db = private_store
    result = cli("issue", "--database", db, "--owner", "sensitive-invalid-value", "--output", folder / "out")
    assert result.returncode == 2
    assert result.stderr.strip() == "enrollment_admin_failed"
    assert "sensitive-invalid-value" not in result.stderr + result.stdout


def test_partial_write_failure_removes_only_created_file(private_store, monkeypatch):
    from app.ai import enrollment_admin
    folder, db = private_store
    output = folder / "enrollment.json"
    original_write = enrollment_admin.os.write
    calls = [0]
    def fail_after_partial(fd, payload):
        calls[0] += 1
        if calls[0] == 1:
            return original_write(fd, payload[:10])
        raise OSError("synthetic_write_failure")
    monkeypatch.setattr(enrollment_admin.os, "write", fail_after_partial)
    assert enrollment_admin.main(["issue", "--database", str(db), "--owner", str(uuid4()),
                                  "--output", str(output)]) == 2
    assert not output.exists()


def test_admin_regrant_after_revocation_requires_new_code(tmp_path):
    auth = ClientAuth(tmp_path / "identity.sqlite")
    now, owner = time.time_ns() // 1_000_000, str(uuid4())
    old = auth.issue_enrollment(owner_id=owner, now_ms=now)
    auth.revoke_owner(owner_id=owner, now_ms=now + 1)
    new = auth.issue_enrollment(owner_id=owner, now_ms=now + 2)
    with pytest.raises(AuthError):
        auth.enroll(old, now_ms=now + 3)
    tokens = auth.enroll(new, now_ms=now + 3)
    assert auth.authenticate(tokens.access_token, now_ms=now + 4) == owner
    auth.engine.dispose()
