import asyncio
from uuid import uuid4

import pytest

from app.ai.systemd_stop import SystemdStopVerifier


def setup(tmp_path, *, populated="0"):
    job_id = str(uuid4())
    unit = "copilot-ai-job-" + job_id.replace("-", "") + ".service"
    root = tmp_path / "cgroup"
    group = root / "system.slice" / unit
    group.mkdir(parents=True)
    (root / "cgroup.controllers").write_text("cpu memory pids")
    (group / "cgroup.events").write_text(f"populated {populated}\nfrozen 0\n")
    verifier = SystemdStopVerifier(job_id, "a" * 32, cgroup_root=root)
    return verifier, group


def show(verifier, **changes):
    fields = dict(Id=verifier.unit, LoadState="loaded", ActiveState="inactive", SubState="dead",
                  InvocationID="a" * 32, User="copilot-ai", KillMode="control-group", ControlGroup="")
    fields.update(changes)
    return "\n".join(f"{key}={value}" for key, value in fields.items())


def install_commands(monkeypatch, verifier, before, after, stop_ok=True):
    commands = []
    def command(args):
        commands.append(args)
        if args[0] == "stop":
            return "" if stop_ok else None
        return before if len(commands) == 1 else after
    monkeypatch.setattr(verifier, "_command", command)
    return commands


def test_stop_confirms_identity_and_empty_descendant_cgroup(tmp_path, monkeypatch):
    verifier, _ = setup(tmp_path)
    commands = install_commands(monkeypatch, verifier, show(verifier, ActiveState="active", SubState="running"), show(verifier))
    assert asyncio.run(verifier.stop_and_confirm()) is True
    assert [command[0] for command in commands] == ["show", "stop", "show"]


@pytest.mark.parametrize("changes", [{"InvocationID": "b" * 32}, {"LoadState": "not-found"},
                                    {"User": "root"}, {"KillMode": "process"}, {"Id": "nginx.service"}])
def test_untrusted_identity_never_stopped(tmp_path, monkeypatch, changes):
    verifier, _ = setup(tmp_path)
    commands = install_commands(monkeypatch, verifier, show(verifier, **changes), show(verifier))
    assert asyncio.run(verifier.stop_and_confirm()) is False
    assert len(commands) == 1


@pytest.mark.parametrize("changes", [{"InvocationID": "b" * 32}, {"ActiveState": "active"},
                                    {"SubState": "stop-sigterm"}, {"ControlGroup": "/other"}])
def test_changed_or_active_unit_cannot_release_slot(tmp_path, monkeypatch, changes):
    verifier, _ = setup(tmp_path)
    install_commands(monkeypatch, verifier, show(verifier), show(verifier, **changes))
    assert asyncio.run(verifier.stop_and_confirm()) is False


def test_populated_cgroup_blocks_receipt(tmp_path, monkeypatch):
    verifier, _ = setup(tmp_path, populated="1")
    install_commands(monkeypatch, verifier, show(verifier), show(verifier))
    assert asyncio.run(verifier.stop_and_confirm()) is False


def test_removed_cgroup_after_same_invocation_stopped(tmp_path, monkeypatch):
    verifier, group = setup(tmp_path)
    (group / "cgroup.events").unlink()
    group.rmdir()
    install_commands(monkeypatch, verifier, show(verifier), show(verifier))
    assert asyncio.run(verifier.stop_and_confirm()) is True


def test_stop_failure_and_malformed_snapshot_fail_closed(tmp_path, monkeypatch):
    verifier, _ = setup(tmp_path)
    install_commands(monkeypatch, verifier, show(verifier), show(verifier), stop_ok=False)
    assert asyncio.run(verifier.stop_and_confirm()) is False
    install_commands(monkeypatch, verifier, show(verifier) + "\nUser=root", show(verifier))
    assert asyncio.run(verifier.stop_and_confirm()) is False


def test_symlink_cgroup_not_trusted(tmp_path, monkeypatch):
    verifier, group = setup(tmp_path)
    (group / "cgroup.events").unlink()
    group.rmdir()
    group.symlink_to(tmp_path)
    install_commands(monkeypatch, verifier, show(verifier), show(verifier))
    assert asyncio.run(verifier.stop_and_confirm()) is False


def test_arbitrary_units_rejected(tmp_path):
    with pytest.raises(ValueError):
        SystemdStopVerifier("nginx.service", "a" * 32, cgroup_root=tmp_path)


def test_collected_transient_unit_requires_absent_not_only_empty_cgroup(tmp_path, monkeypatch):
    verifier, group = setup(tmp_path)
    collected = show(verifier, LoadState="not-found", InvocationID="", User="")
    install_commands(monkeypatch, verifier, show(verifier), collected)
    assert asyncio.run(verifier.stop_and_confirm()) is False
    (group / "cgroup.events").unlink()
    group.rmdir()
    install_commands(monkeypatch, verifier, show(verifier), collected)
    assert asyncio.run(verifier.stop_and_confirm()) is True


def bundle_setup(tmp_path, *, populated="0"):
    job_id, slice_id = str(uuid4()), str(uuid4())
    root = tmp_path / "cgroup"
    parent = root / ("personalai" + slice_id.replace("-", "") + ".slice")
    unit = "copilot-ai-job-" + job_id.replace("-", "") + ".service"
    group = parent / unit
    group.mkdir(parents=True)
    (root / "cgroup.controllers").write_text("cpu memory pids")
    (group / "cgroup.events").write_text(f"populated {populated}\n")
    verifier = SystemdStopVerifier(job_id, "a" * 32, cgroup_root=root, slice_id=slice_id)
    return verifier, group


def test_shared_slice_receipt_requires_exact_parent(tmp_path, monkeypatch):
    verifier, group = bundle_setup(tmp_path)
    expected = "/" + group.parent.name + "/" + verifier.unit
    assert verifier.group_path == expected
    install_commands(monkeypatch, verifier, show(verifier, ControlGroup=expected), show(verifier))
    assert asyncio.run(verifier.stop_and_confirm()) is True
    commands = install_commands(monkeypatch, verifier,
        show(verifier, ControlGroup="/system.slice/" + verifier.unit), show(verifier))
    assert asyncio.run(verifier.stop_and_confirm()) is False
    assert len(commands) == 1


@pytest.mark.parametrize("slice_id", ["system.slice", "../other", True, "",
    "00000000-0000-0000-0000-000000000000", "00000000-0000-1000-8000-000000000000"])
def test_untrusted_slice_identity_rejected(slice_id):
    with pytest.raises(ValueError, match="invalid_slice_identity"):
        SystemdStopVerifier(str(uuid4()), "a" * 32, slice_id=slice_id)


def test_shared_slice_populated_descendants_block_receipt(tmp_path, monkeypatch):
    verifier, _ = bundle_setup(tmp_path, populated="1")
    install_commands(monkeypatch, verifier, show(verifier), show(verifier))
    assert asyncio.run(verifier.stop_and_confirm()) is False


def test_shared_slice_parent_symlink_blocks_receipt(tmp_path, monkeypatch):
    verifier, group = bundle_setup(tmp_path)
    (group / "cgroup.events").unlink()
    group.rmdir()
    parent = group.parent
    parent.rmdir()
    parent.symlink_to(tmp_path)
    install_commands(monkeypatch, verifier, show(verifier), show(verifier))
    assert asyncio.run(verifier.stop_and_confirm()) is False


def test_collected_shared_slice_may_disappear_only_after_bound_stop(tmp_path, monkeypatch):
    verifier, group = bundle_setup(tmp_path)
    (group / "cgroup.events").unlink()
    group.rmdir()
    group.parent.rmdir()
    collected = show(verifier, LoadState="not-found", InvocationID="", User="")
    commands = install_commands(monkeypatch, verifier, show(verifier), collected)
    assert asyncio.run(verifier.stop_and_confirm()) is True
    assert [command[0] for command in commands] == ["show", "stop", "show"]
    commands = install_commands(monkeypatch, verifier, collected, collected)
    assert asyncio.run(verifier.stop_and_confirm()) is False
    assert len(commands) == 1


@pytest.mark.parametrize("factory", [setup, bundle_setup])
def test_loaded_inactive_unit_may_clear_invocation_after_bound_stop(tmp_path, monkeypatch, factory):
    verifier, _ = factory(tmp_path)
    cleared = show(verifier, InvocationID="")
    install_commands(monkeypatch, verifier, show(verifier, ActiveState="active", SubState="running"), cleared)
    assert asyncio.run(verifier.stop_and_confirm()) is True
    commands = install_commands(monkeypatch, verifier, cleared, cleared)
    assert asyncio.run(verifier.stop_and_confirm()) is False
    assert len(commands) == 1


@pytest.mark.parametrize("changes", [{"User": "root"}, {"Id": "other.service"},
    {"KillMode": "process"}, {"ControlGroup": "/other"}, {"ActiveState": "active"},
    {"SubState": "failed"}, {"LoadState": "masked"}])
def test_cleared_invocation_still_requires_exact_inactive_identity(tmp_path, monkeypatch, changes):
    verifier, _ = setup(tmp_path)
    install_commands(monkeypatch, verifier, show(verifier), show(verifier, InvocationID="", **changes))
    assert asyncio.run(verifier.stop_and_confirm()) is False


def test_cleared_invocation_cannot_hide_populated_descendants(tmp_path, monkeypatch):
    verifier, _ = setup(tmp_path, populated="1")
    install_commands(monkeypatch, verifier, show(verifier), show(verifier, InvocationID=""))
    assert asyncio.run(verifier.stop_and_confirm()) is False
