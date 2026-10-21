import asyncio
from dataclasses import FrozenInstanceError
import importlib
import json
import os
from pathlib import Path
import subprocess
import select
import signal
import stat
import sys
from uuid import uuid4

import pytest

from app.ai.systemd_stop import SystemdStopVerifier


def component():
    try:
        return importlib.import_module("app.ai.session_profile")
    except ModuleNotFoundError:
        pytest.fail("session profile controller is missing")


def setup(tmp_path):
    module = component()
    profile, control = tmp_path / "profile", tmp_path / "control"
    profile.mkdir(mode=0o700)
    control.mkdir(mode=0o700)
    bind = module.DirectoryBinding.capture
    guard = module.SessionProfileGuard(
        profile=bind(profile.resolve(), os.geteuid(), os.getegid()),
        control=bind(control.resolve(), os.geteuid(), os.getegid()),
        cgroup_root=tmp_path / "cgroup", slice_id=str(uuid4()))
    return module, guard, profile, control


def verifier(guard, run_id, invocation="a" * 32, *, outcome=True, calls=None):
    value = SystemdStopVerifier(run_id, invocation, cgroup_root=guard.cgroup_root,
                               slice_id=guard.slice_id)
    async def confirm():
        if calls is not None:
            calls.append((run_id, invocation))
        return outcome
    value.stop_and_confirm = confirm
    return value


def state(control):
    return json.loads((control / "state.json").read_text())


def test_fresh_init_and_successful_exact_bound_lifecycle(tmp_path):
    module, guard, profile, control = setup(tmp_path)
    guard.initialize()
    assert (control / "session.lock").stat().st_mode & 0o777 == 0o600
    with guard.reserve() as lease:
        assert state(control)["phase"] == "reserved"
        lease.bind("a" * 32)
        calls = []
        asyncio.run(lease.finish(verifier(guard, lease.run_id, calls=calls)))
        assert calls == [(lease.run_id, "a" * 32)]
        assert state(control)["phase"] == "idle"
        with pytest.raises(module.ProfileError):
            lease.bind("a" * 32)
    assert list(profile.iterdir()) == []


def test_lock_contention_never_resets_or_unlinks_lock(tmp_path):
    module, guard, _, control = setup(tmp_path)
    guard.initialize()
    inode = (control / "session.lock").stat().st_ino
    with guard.reserve():
        before = (control / "state.json").read_bytes()
        with pytest.raises(module.ProfileError, match="profile_busy"):
            with guard.reserve():
                pytest.fail("concurrent reservation admitted")
        assert (control / "state.json").read_bytes() == before
    assert (control / "session.lock").stat().st_ino == inode


def test_context_exit_retains_marker_and_unbound_crash_is_not_recoverable(tmp_path):
    module, guard, _, control = setup(tmp_path)
    guard.initialize()
    with guard.reserve() as lease:
        run_id = lease.run_id
    with pytest.raises(module.ProfileError, match="unfinished_session"):
        with guard.reserve():
            pass
    with pytest.raises(module.ProfileError):
        guard.initialize()
    with pytest.raises(module.ProfileError):
        asyncio.run(guard.recover(run_id, "a" * 32, verifier(guard, run_id)))
    assert state(control)["phase"] == "reserved"


@pytest.mark.parametrize("outcome", [False, None, 1])
def test_stop_must_be_exact_true_before_capacity_is_released(tmp_path, outcome):
    module, guard, _, control = setup(tmp_path)
    guard.initialize()
    with guard.reserve() as lease:
        lease.bind("a" * 32)
        with pytest.raises(module.ProfileError, match="stop_unconfirmed"):
            asyncio.run(lease.finish(verifier(guard, lease.run_id, outcome=outcome)))
    assert state(control)["phase"] == "bound"


@pytest.mark.parametrize("outcome", [False, None, 1, True])
def test_io_confirmation_precedes_idle_marker(tmp_path, outcome):
    module, guard, _, control = setup(tmp_path)
    guard.initialize()
    calls = []
    with guard.reserve() as lease:
        lease.bind("a" * 32)
        async def io_stopped():
            assert state(control)["phase"] == "bound"
            assert len(calls) == 1
            return outcome
        async def scenario():
            await lease.finish(verifier(guard, lease.run_id, calls=calls), io_stopped=io_stopped)
        if outcome is True:
            asyncio.run(scenario())
        else:
            with pytest.raises(module.ProfileError, match="stop_unconfirmed"):
                asyncio.run(scenario())
    assert state(control)["phase"] == ("idle" if outcome is True else "bound")


def test_cancellation_joins_io_and_retains_bound_marker(tmp_path):
    _, guard, _, control = setup(tmp_path)
    guard.initialize()
    with guard.reserve() as lease:
        lease.bind("a" * 32)
        async def scenario():
            entered, release = asyncio.Event(), asyncio.Event()
            async def io_stopped():
                entered.set()
                await release.wait()
                assert state(control)["phase"] == "bound"
                return True
            task = asyncio.create_task(lease.finish(verifier(guard, lease.run_id), io_stopped=io_stopped))
            await entered.wait()
            task.cancel()
            await asyncio.sleep(0)
            assert not task.done()
            release.set()
            with pytest.raises(asyncio.CancelledError):
                await task
        asyncio.run(scenario())
    assert state(control)["phase"] == "bound"


def test_other_invocation_or_scope_never_reaches_stop(tmp_path):
    module, guard, _, _ = setup(tmp_path)
    guard.initialize()
    with guard.reserve() as lease:
        lease.bind("a" * 32)
        calls = []
        for other in (verifier(guard, str(uuid4()), calls=calls),
                      verifier(guard, lease.run_id, "b" * 32, calls=calls)):
            with pytest.raises(module.ProfileError):
                asyncio.run(lease.finish(other))
        other = verifier(guard, lease.run_id, calls=calls)
        other.worker_user = "copilot-image"
        with pytest.raises(module.ProfileError):
            asyncio.run(lease.finish(other))
        other = verifier(guard, lease.run_id, calls=calls)
        other.root = guard.cgroup_root / "other"
        with pytest.raises(module.ProfileError):
            asyncio.run(lease.finish(other))
        assert calls == []


def test_explicit_bound_recovery_rechecks_original_run_and_stop(tmp_path):
    module, guard, _, control = setup(tmp_path)
    guard.initialize()
    with guard.reserve() as lease:
        lease.bind("a" * 32)
        run_id = lease.run_id
    with pytest.raises(module.ProfileError):
        asyncio.run(guard.recover(str(uuid4()), "a" * 32, verifier(guard, run_id)))
    asyncio.run(guard.recover(run_id, "a" * 32, verifier(guard, run_id)))
    assert state(control)["phase"] == "idle"


@pytest.mark.parametrize("root_name,orphan", [("profile", ".tmpSample"), ("control", ".state-Sample")])
def test_writer_orphans_are_refused_not_deleted_or_promoted(tmp_path, root_name, orphan):
    module, guard, profile, control = setup(tmp_path)
    guard.initialize()
    path = {"profile": profile, "control": control}[root_name] / orphan
    path.write_bytes(b"sample")
    path.chmod(0o600)
    with pytest.raises(module.ProfileError):
        with guard.reserve():
            pass
    assert path.read_bytes() == b"sample"
    assert state(control)["phase"] == "idle"


def test_existing_auth_cannot_be_implicitly_initialized(tmp_path):
    module, guard, profile, _ = setup(tmp_path)
    (profile / "auth.json").write_text("sample")
    with pytest.raises(module.ProfileError):
        guard.initialize()


def test_profile_credentials_are_never_opened_or_parsed(tmp_path, monkeypatch):
    _, guard, profile, _ = setup(tmp_path)
    guard.initialize()
    auth = profile / "auth.json"
    auth.write_bytes(b"sample private content")
    auth.chmod(0o600)
    original = os.open
    def checked(path, *args, **kwargs):
        assert str(path) not in ("auth.json", str(auth)), "credential body was opened"
        return original(path, *args, **kwargs)
    monkeypatch.setattr(os, "open", checked)
    with guard.reserve() as lease:
        lease.bind("a" * 32)
        asyncio.run(lease.finish(verifier(guard, lease.run_id)))


@pytest.mark.parametrize("name", ["profile", "control"])
def test_replaced_directory_identity_is_refused(tmp_path, name):
    module, guard, profile, control = setup(tmp_path)
    guard.initialize()
    target = {"profile": profile, "control": control}[name]
    target.rename(tmp_path / (name + "-old"))
    target.mkdir(mode=0o700)
    with pytest.raises(module.ProfileError):
        with guard.reserve():
            pass


@pytest.mark.parametrize("name", ["session.lock", "state.json"])
def test_control_links_and_nonprivate_modes_are_rejected(tmp_path, name):
    module, guard, _, control = setup(tmp_path)
    guard.initialize()
    path = control / name
    path.chmod(0o644)
    with pytest.raises(module.ProfileError):
        with guard.reserve():
            pass
    path.chmod(0o600)
    sibling = tmp_path / "sample"
    path.rename(sibling)
    path.symlink_to(sibling)
    with pytest.raises(module.ProfileError):
        with guard.reserve():
            pass


def test_duplicate_or_extra_journal_fields_are_rejected_without_reset(tmp_path):
    module, guard, _, control = setup(tmp_path)
    guard.initialize()
    path = control / "state.json"
    content = path.read_text()
    for altered in (content[:-1] + ',"phase":"idle"}', content[:-1] + ',"extra":true}'):
        path.write_text(altered)
        with pytest.raises(module.ProfileError):
            with guard.reserve():
                pass
        assert path.read_text() == altered


def test_stop_cancellation_is_drained_and_retains_active_marker(tmp_path):
    _, guard, _, control = setup(tmp_path)
    guard.initialize()
    async def exercise():
        started, release = asyncio.Event(), asyncio.Event()
        with guard.reserve() as lease:
            lease.bind("a" * 32)
            stop = verifier(guard, lease.run_id)
            async def confirm():
                started.set()
                await release.wait()
                return True
            stop.stop_and_confirm = confirm
            task = asyncio.create_task(lease.finish(stop))
            await started.wait()
            task.cancel()
            await asyncio.sleep(0)
            assert not task.done()
            release.set()
            with pytest.raises(asyncio.CancelledError):
                await task
    asyncio.run(exercise())
    assert state(control)["phase"] == "bound"


def test_other_process_cannot_reserve_held_profile(tmp_path, monkeypatch):
    module, guard, _, _ = setup(tmp_path)
    guard.initialize()
    monkeypatch.delenv("PYTHONPATH", raising=False)
    with guard.reserve():
        result = subprocess.run([sys.executable, str(Path(__file__)), "contend", str(tmp_path)],
                                capture_output=True, timeout=10, close_fds=True,
                                env={**os.environ, "PYTHONPATH": str(Path(__file__).resolve().parents[1])})
    assert result.returncode == 0, result.stderr.decode()
    assert result.stdout == b"profile_busy\n"


def test_guard_scope_and_lease_run_identity_cannot_be_changed(tmp_path):
    _, guard, _, _ = setup(tmp_path)
    guard.initialize()
    with pytest.raises(FrozenInstanceError):
        guard.slice_id = str(uuid4())
    with guard.reserve() as lease:
        with pytest.raises(AttributeError):
            lease.run_id = str(uuid4())


def test_restart_cannot_change_saved_stop_scope(tmp_path):
    module, guard, _, control = setup(tmp_path)
    guard.initialize()
    with guard.reserve() as lease:
        lease.bind("a" * 32)
        run_id = lease.run_id
    different = module.SessionProfileGuard(profile=guard.profile, control=guard.control,
        cgroup_root=guard.cgroup_root / "other", slice_id=guard.slice_id)
    calls = []
    with pytest.raises(module.ProfileError):
        asyncio.run(different.recover(run_id, "a" * 32, verifier(different, run_id, calls=calls)))
    assert calls == []
    assert state(control)["phase"] == "bound"


def test_identical_journal_replacement_during_lease_is_not_adopted(tmp_path):
    module, guard, _, control = setup(tmp_path)
    guard.initialize()
    with guard.reserve() as lease:
        path = control / "state.json"
        replacement = control / "replacement"
        replacement.write_bytes(path.read_bytes())
        replacement.chmod(0o600)
        replacement.replace(path)
        with pytest.raises(module.ProfileError):
            lease.bind("a" * 32)


def test_raw_stop_exception_is_hidden_and_marker_remains(tmp_path):
    module, guard, _, control = setup(tmp_path)
    guard.initialize()
    with guard.reserve() as lease:
        lease.bind("a" * 32)
        stop = verifier(guard, lease.run_id)
        async def fail():
            raise RuntimeError("sample private diagnostic")
        stop.stop_and_confirm = fail
        with pytest.raises(module.ProfileError, match="stop_unconfirmed") as error:
            asyncio.run(lease.finish(stop))
        assert "private" not in str(error.value)
    assert state(control)["phase"] == "bound"


def test_forced_controller_death_leaves_bound_marker_for_explicit_recovery(tmp_path, monkeypatch):
    module, guard, _, control = setup(tmp_path)
    guard.initialize()
    monkeypatch.delenv("PYTHONPATH", raising=False)
    process = subprocess.Popen([sys.executable, str(Path(__file__)), "hold", str(tmp_path), guard.slice_id],
                               stdout=subprocess.PIPE, stderr=subprocess.PIPE, close_fds=True,
                               env={**os.environ, "PYTHONPATH": str(Path(__file__).resolve().parents[1])})
    try:
        assert select.select([process.stdout], [], [], 10)[0], "child did not reserve profile"
        assert process.stdout.readline() == b"ready\n"
        with pytest.raises(module.ProfileError, match="profile_busy"):
            with guard.reserve():
                pass
        process.kill()
        process.communicate(timeout=10)
        with pytest.raises(module.ProfileError, match="unfinished_session"):
            with guard.reserve():
                pass
        record = state(control)
        asyncio.run(guard.recover(record["run_id"], record["invocation_id"], verifier(guard, record["run_id"])))
        assert state(control)["phase"] == "idle"
    finally:
        if process.poll() is None:
            process.kill()
        process.communicate(timeout=10)


def test_forked_lease_cannot_transition_or_release_parent_lock(tmp_path):
    _, guard, _, _ = setup(tmp_path)
    guard.initialize()
    with guard.reserve() as lease:
        pid = os.fork()
        if pid == 0:
            try:
                lease.bind("a" * 32)
                os._exit(2)
            except component().ProfileError:
                os._exit(0)
        assert os.waitpid(pid, 0)[1] == 0
        with pytest.raises(component().ProfileError, match="profile_busy"):
            with guard.reserve():
                pass


def test_journal_directory_metadata_requires_integer_types(tmp_path):
    module, guard, _, control = setup(tmp_path)
    guard.initialize()
    value = state(control)
    value["profile"]["device"] = float(value["profile"]["device"])
    (control / "state.json").write_text(json.dumps(value))
    with pytest.raises(module.ProfileError, match="invalid_session_journal"):
        with guard.reserve():
            pass


def test_inventory_does_not_allocate_an_unbounded_directory_listing(tmp_path, monkeypatch):
    _, guard, _, _ = setup(tmp_path)
    def reject(*_args):
        pytest.fail("unbounded directory list requested")
    monkeypatch.setattr(os, "listdir", reject)
    guard.initialize()
    with guard.reserve() as lease:
        lease.bind("a" * 32)
        asyncio.run(lease.finish(verifier(guard, lease.run_id)))


def test_pre_replace_error_preserves_idle_and_removes_only_own_temporary(tmp_path, monkeypatch):
    module, guard, _, control = setup(tmp_path)
    guard.initialize()
    before = (control / "state.json").read_bytes()
    original = os.fsync
    def fail(fd):
        if stat.S_ISREG(os.fstat(fd).st_mode):
            raise OSError("sample file sync failure")
        return original(fd)
    monkeypatch.setattr(os, "fsync", fail)
    with pytest.raises(module.ProfileError):
        with guard.reserve():
            pass
    assert (control / "state.json").read_bytes() == before
    assert {path.name for path in control.iterdir()} == {"state.json", "session.lock"}


def test_post_replace_bind_error_never_restores_previous_journal(tmp_path, monkeypatch):
    module, guard, _, control = setup(tmp_path)
    guard.initialize()
    with guard.reserve() as lease:
        original = os.fsync
        def fail(fd):
            if stat.S_ISDIR(os.fstat(fd).st_mode):
                raise OSError("sample directory sync failure")
            return original(fd)
        monkeypatch.setattr(os, "fsync", fail)
        with pytest.raises(module.ProfileError):
            lease.bind("a" * 32)
    assert state(control)["phase"] == "bound"
    with pytest.raises(module.ProfileError, match="unfinished_session"):
        with guard.reserve():
            pass


def test_lock_replacement_while_held_is_rejected(tmp_path):
    module, guard, _, control = setup(tmp_path)
    guard.initialize()
    with guard.reserve() as lease:
        (control / "session.lock").rename(tmp_path / "previous-lock")
        (control / "session.lock").touch(mode=0o600)
        with pytest.raises(module.ProfileError, match="session_lock_changed"):
            lease.bind("a" * 32)


@pytest.mark.parametrize("kind", ["mode", "symlink", "hardlink", "size"])
def test_authorization_metadata_is_private_bounded_and_single_link(tmp_path, kind):
    module, guard, profile, _ = setup(tmp_path)
    guard.initialize()
    auth = profile / "auth.json"
    auth.write_bytes(b"sample")
    auth.chmod(0o600)
    if kind == "mode":
        auth.chmod(0o644)
    elif kind == "symlink":
        auth.rename(tmp_path / "outside")
        auth.symlink_to(tmp_path / "outside")
    elif kind == "hardlink":
        os.link(auth, tmp_path / "outside")
    else:
        with auth.open("r+b") as stream:
            stream.truncate(262145)
    with pytest.raises(module.ProfileError):
        with guard.reserve():
            pass


def test_public_installation_metadata_is_the_only_file_mode_exception(tmp_path):
    module, guard, profile, _ = setup(tmp_path)
    guard.initialize()
    identity = profile / "installation_id"
    identity.write_text(str(uuid4()))
    identity.chmod(0o644)
    with guard.reserve() as lease:
        lease.bind("a" * 32)
        asyncio.run(lease.finish(verifier(guard, lease.run_id)))
    identity.write_text("x" * 36)
    with pytest.raises(module.ProfileError, match="invalid_installation_metadata"):
        with guard.reserve():
            pass


def test_symlink_path_component_never_becomes_an_admitted_root(tmp_path):
    module, _, profile, _ = setup(tmp_path)
    alias = tmp_path / "alias"
    alias.symlink_to(profile, target_is_directory=True)
    with pytest.raises(module.ProfileError):
        module.DirectoryBinding.capture(alias, os.geteuid(), os.getegid())


def test_orphan_created_during_stop_blocks_journal_release(tmp_path):
    module, guard, profile, control = setup(tmp_path)
    guard.initialize()
    with guard.reserve() as lease:
        lease.bind("a" * 32)
        stop = verifier(guard, lease.run_id)
        async def confirm():
            orphan = profile / ".tmpSample"
            orphan.write_bytes(b"sample")
            orphan.chmod(0o600)
            return True
        stop.stop_and_confirm = confirm
        with pytest.raises(module.ProfileError, match="profile_writer_orphan"):
            asyncio.run(lease.finish(stop))
    assert state(control)["phase"] == "bound"


def test_repeated_cancellation_does_not_abandon_stop_task(tmp_path):
    module, guard, _, control = setup(tmp_path)
    guard.initialize()
    async def exercise():
        started, release, done = asyncio.Event(), asyncio.Event(), asyncio.Event()
        with guard.reserve() as lease:
            lease.bind("a" * 32)
            stop = verifier(guard, lease.run_id)
            async def confirm():
                started.set()
                await release.wait()
                done.set()
                return True
            stop.stop_and_confirm = confirm
            task = asyncio.create_task(lease.finish(stop))
            await started.wait()
            for _ in range(2):
                task.cancel()
                await asyncio.sleep(0)
            assert not task.done() and not done.is_set()
            with pytest.raises(module.ProfileError, match="inactive_session_lease"):
                await lease.finish(stop)
            release.set()
            with pytest.raises(asyncio.CancelledError):
                await task
            assert done.is_set()
    asyncio.run(exercise())
    assert state(control)["phase"] == "bound"


def child():
    module = component()
    base = Path(sys.argv[2])
    bind = module.DirectoryBinding.capture
    guard = module.SessionProfileGuard(
        profile=bind((base / "profile").resolve(), os.geteuid(), os.getegid()),
        control=bind((base / "control").resolve(), os.geteuid(), os.getegid()),
        cgroup_root=base / "cgroup", slice_id=sys.argv[3] if len(sys.argv) == 4 else None)
    try:
        with guard.reserve() as lease:
            if sys.argv[1] == "hold":
                lease.bind("a" * 32)
                print("ready", flush=True)
                signal.pause()
            else:
                raise SystemExit(2)
    except module.ProfileError as error:
        print(str(error))


if __name__ == "__main__":
    child()
