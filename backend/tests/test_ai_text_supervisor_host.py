import asyncio
from dataclasses import asdict, replace
import json
import os
from pathlib import Path
import socket
import tempfile

import pytest

from app.ai import text_supervisor_host as host
from app.ai.runtime_admission import AdmissionError
from app.ai.text_supervisor import PeerIdentity, SupervisorError, SupervisorTextRunner
from test_ai_contextual_chat import Process
from test_ai_runtime_admission import egress, image  # noqa: F401
from test_ai_text_supervisor import Pending


def configuration(image):
    return dict(version=1, socket="/run/personal-ai/text.sock", api=dict(uid=1001, gid=1001),
        model="sample-model", image=dict(root=str(image.root), device=image.device, inode=image.inode,
            entries=[asdict(entry) for entry in image.entries], binary=str(image.binary), binary_pin=asdict(image.binary_pin)),
        profile=dict(path="/var/lib/personal-ai/profile", device=1, inode=2, uid=993, gid=985),
        control=dict(path="/var/lib/personal-ai/control", device=1, inode=3, uid=0, gid=0),
        scope=dict(cgroup_root="/sys/fs/cgroup", slice_id=None), egress=asdict(egress()))


def decode(value):
    return host.HostConfiguration.decode(json.dumps(value).encode())


@pytest.fixture
def socket_path():
    with tempfile.TemporaryDirectory(prefix="personal-ai-", dir="/tmp") as directory:
        yield Path(directory) / "text.sock"


def test_configuration_derives_fixed_context_policy_and_constructs_without_effects(image):
    value = decode(configuration(image))
    assert value.policy.model == "sample-model"
    assert "serverConversation" in value.policy.instructions
    assert value.api_peer == PeerIdentity(1001, 1001)
    assert value.egress.addresses == ("1.1.1.1", "8.8.8.8")


@pytest.mark.parametrize("change", ["extra", "root-api", "worker-api", "zero-group", "bool-id",
    "instructions", "socket", "version", "scope", "entry", "control-owner"])
def test_operator_configuration_refuses_unknown_fields_or_privilege_confusion(image, change):
    value = configuration(image)
    if change == "extra":
        value["command"] = "sample-private-command"
    elif change == "root-api":
        value["api"]["uid"] = 0
    elif change == "worker-api":
        value["api"]["uid"] = 993
    elif change == "zero-group":
        value["api"]["gid"] = 0
    elif change == "bool-id":
        value["api"]["uid"] = True
    elif change == "instructions":
        value["instructions"] = "sample-private-instructions"
    elif change == "socket":
        value["socket"] = "/run/../tmp/socket"
    elif change == "version":
        value["version"] = True
    elif change == "scope":
        value["scope"]["cgroup_root"] = "/tmp/cgroup"
    elif change == "entry":
        value["image"]["entries"][0]["command"] = "sample-private-command"
    else:
        value["control"]["uid"] = 1001
    with pytest.raises(host.HostError) as error:
        decode(value)
    assert "sample-private" not in str(error.value)


@pytest.mark.parametrize("raw", [b'{"version":1,"version":1}', b'{"version":NaN}', b'[]', b'\xff'])
def test_duplicate_non_json_and_invalid_utf8_configuration_is_refused(raw):
    with pytest.raises(host.HostError):
        host.HostConfiguration.decode(raw)


def test_protected_configuration_read_checks_real_mode_links_and_no_follow(image, tmp_path, monkeypatch):
    path = tmp_path.resolve() / "settings.json"
    path.write_text(json.dumps(configuration(image)))
    path.chmod(0o600)
    monkeypatch.setattr(host, "_parents", lambda path: None)
    monkeypatch.setattr(host, "_owner", lambda info: None)
    assert host.read_configuration(path).policy.model == "sample-model"
    path.chmod(0o644)
    with pytest.raises(host.HostError):
        host.read_configuration(path)
    path.chmod(0o600)
    linked = path.with_name("linked.json")
    os.link(path, linked)
    with pytest.raises(host.HostError):
        host.read_configuration(path)
    linked.unlink()
    linked.symlink_to(path)
    with pytest.raises(host.HostError):
        host.read_configuration(linked)


def test_listener_owns_only_its_new_socket_and_removes_it_on_normal_close(socket_path, monkeypatch):
    monkeypatch.setattr(host, "_parents", lambda path: None)
    original = os.chmod
    def linux_chmod(*args, **kwargs):
        if kwargs.get("follow_symlinks") is False:
            raise NotImplementedError("synthetic Linux chmod capability")
        return original(*args, **kwargs)
    monkeypatch.setattr(host.os, "chmod", linux_chmod)
    path = socket_path
    with host.owned_listener(path, os.getgid()) as listener:
        assert listener.family == socket.AF_UNIX
        assert path.stat().st_mode & 0o777 == 0o660
    assert not path.exists()


@pytest.mark.parametrize("kind", ["file", "symlink", "socket"])
def test_listener_refuses_preexisting_paths_without_unlinking_them(socket_path, monkeypatch, kind):
    monkeypatch.setattr(host, "_parents", lambda path: None)
    path, listener = socket_path, None
    if kind == "file":
        path.write_bytes(b"sample-private")
    elif kind == "symlink":
        path.symlink_to(path.parent / "absent")
    else:
        listener = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
        listener.bind(str(path))
    try:
        inode = path.lstat().st_ino
        with pytest.raises(host.HostError, match="already_present"):
            with host.owned_listener(path, os.getgid()):
                pytest.fail("existing path admitted")
        assert path.lstat().st_ino == inode
    finally:
        if listener is not None:
            listener.close()


def test_replaced_socket_path_is_preserved_during_cleanup(socket_path, monkeypatch):
    monkeypatch.setattr(host, "_parents", lambda path: None)
    path = socket_path
    with pytest.raises(host.HostError, match="socket_changed"):
        with host.owned_listener(path, os.getgid()):
            path.unlink()
            path.write_bytes(b"sample-private")
    assert path.read_bytes() == b"sample-private"


@pytest.mark.parametrize("mode", ["complete", "active-shutdown", "changed-identity"])
def test_host_runs_real_local_channel_and_cleans_socket_on_shutdown(image, monkeypatch, mode):
    # Same-UID peers only for this local transport test; production requires root
    # and a distinct nonroot API identity before any listener is created.
    with tempfile.TemporaryDirectory(prefix="personal-ai-", dir="/tmp") as directory:
        value = configuration(image)
        value["socket"] = directory + "/text.sock"
        value["api"] = dict(uid=os.getuid(), gid=os.getgid())
        config = decode(value)
        monkeypatch.setattr(host, "_require_host", lambda: None)
        monkeypatch.setattr(host, "_parents", lambda path: None)
        current = [config]
        monkeypatch.setattr(host, "read_configuration", lambda path: current[0])
        monkeypatch.setattr(host.HostConfiguration, "check_idle", lambda self: None)
        monkeypatch.setattr(host.RuntimeImage, "verify", lambda *args: None)
        monkeypatch.setattr(host.OfficialEgress, "check_time", lambda self: None)
        processes, created = [], asyncio.Event()
        def process(*args):
            value = Pending() if mode == "active-shutdown" else Process()
            processes.append(value)
            created.set()
            return value
        monkeypatch.setattr(host, "SystemdTextProcess", process)
        async def scenario():
            stop, ready = asyncio.Event(), asyncio.Event()
            task = asyncio.create_task(host.serve(Path("/etc/personal-ai/settings.json"), stop, ready=ready))
            try:
                await asyncio.wait_for(ready.wait(), 1)
                if mode == "changed-identity":
                    current[0] = replace(config, policy=replace(config.policy, model="other-model"))
                client = SupervisorTextRunner(str(config.socket_path), PeerIdentity(os.getuid(), os.getgid()), config.policy)
                running = asyncio.create_task(client.run("Synthetic question", config.policy))
                if mode == "active-shutdown":
                    await asyncio.wait_for(created.wait(), 1)
                    await processes[0].entered.wait()
                    stop.set()
                if mode == "complete":
                    assert await running == {"text": "Synthetic answer"}
                else:
                    with pytest.raises(SupervisorError):
                        await running
                assert await client.stop_and_confirm() is (mode != "changed-identity")
            finally:
                stop.set()
                assert await task is (mode != "changed-identity")
            assert not config.socket_path.exists()
        asyncio.run(scenario())
        if mode == "changed-identity":
            assert processes == []
        else:
            assert len(processes) == 1 and processes[0].stops == 1


def test_host_configuration_replacement_during_read_is_refused(image, tmp_path, monkeypatch):
    path = tmp_path.resolve() / "settings.json"
    content = json.dumps(configuration(image))
    path.write_text(content)
    path.chmod(0o600)
    monkeypatch.setattr(host, "_parents", lambda path: None)
    monkeypatch.setattr(host, "_owner", lambda info: None)
    original = os.read
    changed = []
    def racing(fd, size):
        raw = original(fd, size)
        if raw and not changed:
            changed.append(True)
            path.unlink()
            path.write_text(content)
            path.chmod(0o600)
        return raw
    monkeypatch.setattr(host.os, "read", racing)
    with pytest.raises(host.HostError, match="unavailable"):
        host.read_configuration(path)


def test_identity_policy_and_guard_cannot_change_when_only_image_admission_is_reloaded(image):
    original = decode(configuration(image))
    refreshed = replace(original, egress=replace(original.egress, admitted_at=200, expires_at=1100))
    assert refreshed.fixed_identity() == original.fixed_identity()
    assert replace(original, policy=replace(original.policy, model="other-model")).fixed_identity() != original.fixed_identity()


def test_cli_failure_prints_fixed_code_without_configuration_values(monkeypatch, capsys):
    monkeypatch.setattr(host.sys, "argv", ["personal-ai", "--config", "/etc/personal-ai/settings.json"])
    async def fail(path):
        raise AdmissionError("sample-private-host-detail")
    monkeypatch.setattr(host, "_main", fail)
    assert host.main() == 1
    output = capsys.readouterr()
    assert output.out == "" and output.err == "host_start_or_stop_unconfirmed\n"
