import asyncio
from contextlib import contextmanager
from dataclasses import replace
import json
from pathlib import Path
import sys
from types import SimpleNamespace
from uuid import uuid4

import pytest

from app.ai import contained_text_process as native
from app.ai.contextual_chat import NativeTextRunner
from app.ai.runtime_admission import RuntimeImage
from app.ai.session_profile import DirectoryBinding, SessionProfileGuard
from app.ai.systemd_stop import SystemdStopVerifier
from test_ai_native_text import POLICY, transcript
from test_ai_runtime_admission import egress, image  # noqa: F401
from test_ai_text_supervisor import PEER, serving
from app.ai.text_supervisor import SupervisorError, SupervisorTextRunner


class Lease:
    def __init__(self, guard):
        self.guard = guard
        self.run_id = str(uuid4())
        self.invocation = None

    def bind(self, invocation):
        assert self.invocation is None
        self.invocation = invocation
        self.guard.events.append("bound")

    async def finish(self, verifier, *, io_stopped=None):
        assert verifier.invocation_id == self.invocation
        if not await verifier.stop_and_confirm():
            raise ValueError("stop_unconfirmed")
        if io_stopped is not None and await io_stopped() is not True:
            raise ValueError("stop_unconfirmed")
        self.guard.events.append("idle")


class Guard(SessionProfileGuard):
    @contextmanager
    def reserve(self):
        self.events.append("reserved")
        lease = Lease(self)
        self.leases.append(lease)
        try:
            yield lease
        finally:
            self.events.append("unlocked")


@pytest.fixture
def host(image, monkeypatch, tmp_path, request):
    bind = DirectoryBinding
    guard = Guard(profile=bind(tmp_path / "profile", 1, 2, 993, 985),
        control=bind(tmp_path / "control", 1, 3, 0, 0), cgroup_root=Path("/sys/fs/cgroup"))
    object.__setattr__(guard, "events", [])
    object.__setattr__(guard, "leases", [])
    events, children, calls = guard.events, [], []
    frames = getattr(request, "param", None) or transcript()
    original_spawn = asyncio.create_subprocess_exec
    source = (
        "import json,sys\n"
        "print('READY',flush=True)\n"
        "if sys.stdin.readline()!='START\\n':sys.exit(70)\n"
        "frames=json.loads(sys.argv[1])\n"
        "for line in sys.stdin:\n"
        " value=json.loads(line)\n"
        " if value.get('id')==1: print(json.dumps(frames[0]),flush=True)\n"
        " if value.get('id')==2:\n"
        "  for frame in frames[1:4]: print(json.dumps(frame),flush=True)\n"
        " if value.get('id')==3:\n"
        "  for frame in frames[4:]: print(json.dumps(frame),flush=True)\n"
    )
    async def spawn(*args, **kwargs):
        calls.append((args, kwargs))
        child = await original_spawn(sys.executable, "-u", "-c", source, json.dumps(frames), **kwargs)
        children.append(child)
        events.append("spawned")
        return child
    def show(unit):
        if "spawned" not in events:
            return {"LoadState": "not-found"}
        return dict(Id=unit, LoadState="loaded", User="copilot-ai", KillMode="control-group",
            InvocationID="a" * 32, ControlGroup="/system.slice/" + unit)
    def verify(*args):
        events.append("verified")
    async def stop(verifier):
        events.append("stopped")
        for child in children:
            if child.returncode is None:
                child.terminate()
            await child.wait()
        return True
    monkeypatch.setattr(native, "_require_host", lambda: None)
    monkeypatch.setattr(native, "_parents", lambda path: None)
    monkeypatch.setattr(native, "_show", show)
    monkeypatch.setattr(native, "verify_kernel", verify)
    monkeypatch.setattr(RuntimeImage, "verify", lambda *args: None)
    monkeypatch.setattr(native.OfficialEgress, "check_time", lambda self: None)
    monkeypatch.setattr(native.asyncio, "create_subprocess_exec", spawn)
    monkeypatch.setattr(SystemdStopVerifier, "stop_and_confirm", stop)
    process = native.SystemdTextProcess(image, guard, egress(), POLICY)
    return process, events, children, calls, original_spawn


def test_construction_has_no_process_effect_and_policy_cannot_be_changed(host):
    process, events, _, calls, _ = host
    assert events == calls == []
    async def scenario():
        with pytest.raises(native.ProcessError, match="start_rejected"):
            await process.start(replace(POLICY, model="other-model"))
        assert await process.stop_and_confirm()
    asyncio.run(scenario())
    assert events == calls == []


def test_real_pipes_fixed_launch_gate_and_native_protocol_are_single_use(host):
    process, events, children, calls, _ = host
    async def scenario():
        runner = NativeTextRunner(process)
        assert await runner.run("Synthetic question", POLICY) == {"text": "Synthetic answer"}
        assert events == ["reserved", "spawned", "bound", "verified"]
        assert await runner.stop_and_confirm()
        assert await runner.stop_and_confirm()
        assert events[-3:] == ["stopped", "idle", "unlocked"]
        assert all(child.returncode is not None for child in children)
        with pytest.raises(native.ProcessError, match="start_rejected"):
            await process.start(POLICY)
    asyncio.run(scenario())
    assert len(calls) == 1
    argv, kwargs = calls[0]
    assert argv[0] == "/usr/bin/systemd-run" and "--pipe" in argv
    assert "--property=MemoryMax=512M" in argv and "--property=MemorySwapMax=0" in argv
    assert "--property=CPUQuota=25%" in argv and "--property=RuntimeMaxSec=840" in argv
    assert argv[argv.index("/bin/sh") + 2] == native.GATE
    assert "Synthetic question" not in " ".join(argv)
    assert kwargs["env"] == native.HOST_ENV
    assert argv[-1] == "app-server" and "/work/client" in argv


def test_supervisor_channel_with_actual_child_pipes_completes_after_stop(host):
    process, events, _, _, _ = host
    async def scenario():
        async with serving(lambda: process) as (_, path):
            runner = SupervisorTextRunner(path, PEER, POLICY)
            assert await runner.run("Synthetic question", POLICY) == {"text": "Synthetic answer"}
            assert "idle" in events and await runner.stop_and_confirm()
    asyncio.run(scenario())


@pytest.mark.parametrize("text", ["x" * 131_072, "\u0430" * 65_536, "\0" * 32_768],
                         ids=["max-ascii", "max-utf8", "json-controls"])
def test_full_context_crosses_supervisor_and_real_child_pipes(host, monkeypatch, text):
    process, events, children, calls, original_spawn = host
    source = (
        "import json,sys\n"
        "print('READY',flush=True)\n"
        "if sys.stdin.readline()!='START\\n':sys.exit(70)\n"
        "frames=json.loads(sys.argv[1])\n"
        "for line in sys.stdin:\n"
        " value=json.loads(line)\n"
        " if value.get('id')==1: print(json.dumps(frames[0]),flush=True)\n"
        " if value.get('id')==2:\n"
        "  for frame in frames[1:4]: print(json.dumps(frame),flush=True)\n"
        " if value.get('id')==3:\n"
        "  for frame in frames[4:6]: print(json.dumps(frame),flush=True)\n"
        "  for method in ('item/started','item/completed'):\n"
        "   print(json.dumps({'method':method,'params':{'threadId':'sample-thread',"
        "'turnId':'sample-turn','item':{'id':'sample-user','type':'userMessage',"
        "'content':value['params']['input']}}}),flush=True)\n"
        "  for frame in frames[6:]: print(json.dumps(frame),flush=True)\n"
    )
    async def spawn(*args, **kwargs):
        calls.append((args, kwargs))
        child = await original_spawn(sys.executable, "-u", "-c", source,
                                     json.dumps(transcript()), **kwargs)
        children.append(child)
        events.append("spawned")
        return child
    monkeypatch.setattr(native.asyncio, "create_subprocess_exec", spawn)
    async def scenario():
        async with serving(lambda: process) as (_, path):
            runner = SupervisorTextRunner(path, PEER, POLICY)
            assert await runner.run(text, POLICY) == {"text": "Synthetic answer"}
            assert await runner.stop_and_confirm()
            assert events[-3:] == ["stopped", "idle", "unlocked"]
            assert all(child.returncode is not None for child in children)
    asyncio.run(scenario())
    assert len(calls) == 1


def test_kernel_failure_keeps_gate_closed_and_stops_exact_invocation(host, monkeypatch):
    process, events, _, _, _ = host
    def refuse(*args):
        raise ValueError("sample-private-host-value")
    monkeypatch.setattr(native, "verify_kernel", refuse)
    async def scenario():
        with pytest.raises(native.ProcessError) as error:
            await process.start(POLICY)
        assert "sample-private" not in str(error.value)
        assert "verified" not in events
        assert await process.stop_and_confirm()
    asyncio.run(scenario())


def test_cancelled_start_preserves_spawn_then_blocks_gate_and_cleans_up(host, monkeypatch):
    process, events, _, _, _ = host
    original_spawn = native.asyncio.create_subprocess_exec
    async def scenario():
        created, release = asyncio.Event(), asyncio.Event()
        async def delayed(*args, **kwargs):
            child = await original_spawn(*args, **kwargs)
            created.set()
            await release.wait()
            return child
        monkeypatch.setattr(native.asyncio, "create_subprocess_exec", delayed)
        task = asyncio.create_task(process.start(POLICY))
        await created.wait()
        task.cancel()
        with pytest.raises(asyncio.CancelledError):
            await task
        stopping = asyncio.create_task(process.stop_and_confirm())
        await asyncio.sleep(0)
        release.set()
        assert await stopping
        assert events[-3:] == ["stopped", "idle", "unlocked"]
        assert process._process.stdin.is_closing()
    asyncio.run(scenario())


def test_unknown_stop_withholds_result_and_supervisor_capacity(host, monkeypatch):
    process, events, children, _, _ = host
    async def unknown(verifier):
        for child in children:
            child.terminate()
            await child.wait()
        return False
    monkeypatch.setattr(SystemdStopVerifier, "stop_and_confirm", unknown)
    async def scenario():
        async with serving(lambda: process) as (supervisor, path):
            runner = SupervisorTextRunner(path, PEER, POLICY)
            with pytest.raises(SupervisorError):
                await runner.run("Synthetic question", POLICY)
            assert not await runner.stop_and_confirm()
            assert supervisor._poisoned
            assert "idle" not in events
    asyncio.run(scenario())


@pytest.mark.parametrize("raw", [b"unterminated", b"x" * (native.LINE_LIMIT + 1) + b"\n", b""],
                         ids=["unterminated", "over-limit", "eof"])
def test_pipe_transport_rejects_eof_and_oversized_or_unterminated_lines(raw):
    async def scenario():
        process = type("Pipes", (), {"stdout": asyncio.StreamReader(limit=native.LINE_LIMIT),
            "stderr": asyncio.StreamReader()})()
        process.stdout.feed_data(raw)
        process.stdout.feed_eof()
        process.stderr.feed_eof()
        transport = native.PipeTextTransport(process)
        with pytest.raises(native.ProcessError):
            await transport.receive()
        await transport.error_reader
    asyncio.run(scenario())


def test_pipe_budget_carries_full_admitted_context_echo():
    async def scenario():
        raw = json.dumps({"method": "item/completed", "params": {
            "threadId": "sample-thread", "turnId": "sample-turn",
            "item": {"type": "userMessage", "content": [
                {"type": "text", "text": "x" * 98_304, "textElements": []}]}}}).encode() + b"\n"
        assert len(raw) > 65_536
        process = SimpleNamespace(stdout=asyncio.StreamReader(limit=native.PipeTextTransport.line_limit),
                                  stderr=asyncio.StreamReader())
        process.stdout.feed_data(raw)
        process.stdout.feed_eof()
        process.stderr.feed_eof()
        transport = native.PipeTextTransport(process)
        try:
            assert await transport.receive() == raw
        finally:
            await transport.error_reader
    asyncio.run(scenario())


def test_excess_stderr_unblocks_receive_without_exposing_output():
    async def scenario():
        process = type("Pipes", (), {"stdout": asyncio.StreamReader(), "stderr": asyncio.StreamReader()})()
        transport = native.PipeTextTransport(process)
        receiving = asyncio.create_task(transport.receive())
        process.stderr.feed_data(b"sample-private" * 4096)
        with pytest.raises(native.ProcessError) as error:
            await asyncio.wait_for(receiving, 1)
        assert "sample-private" not in str(error.value)
    asyncio.run(scenario())


@pytest.fixture
def kernel(image, tmp_path, monkeypatch):
    proc_root = tmp_path / "proc"
    proc = proc_root / "321"
    proc.mkdir(parents=True)
    monkeypatch.setattr(native, "PROC_ROOT", proc_root)
    profile = tmp_path / "profile"
    profile.mkdir()
    (image.root / "work/profile").rmdir()
    (image.root / "work/profile").symlink_to(profile, target_is_directory=True)
    (image.root / "work/client").unlink()
    (image.root / "work/client").symlink_to(image.binary)
    (proc / "root").symlink_to(image.root, target_is_directory=True)
    guard = SessionProfileGuard(profile=DirectoryBinding(profile, 1, 2, 993, 985),
        control=DirectoryBinding(tmp_path / "control", 1, 3, 0, 0), cgroup_root=tmp_path / "cgroup")
    verifier = SystemdStopVerifier(str(uuid4()), "a" * 32, cgroup_root=guard.cgroup_root)
    group = guard.cgroup_root / verifier.group_path.lstrip("/")
    group.mkdir(parents=True)
    for name, value in (("memory.max", "536870912"), ("memory.swap.max", "0"),
                        ("cpu.max", "25000 100000"), ("pids.max", "32")):
        (group / name).write_text(value)
    (proc / "status").write_text("Uid:\t993 993 993 993\nGid:\t985 985 985 985\nGroups:\t985\n"
        "CapEff:\t0000\nCapPrm:\t0000\nCapBnd:\t0000\nCapAmb:\t0000\nCapInh:\t0000\n"
        "NoNewPrivs:\t1\nSeccomp:\t2\n")
    (proc / "environ").write_bytes(b"\0".join((key + "=" + value).encode()
        for key, value in native.NATIVE_ENV.items()) + b"\0")
    (proc / "cgroup").write_text("0::" + verifier.group_path + "\n")
    (proc / "mountinfo").write_text("\n".join(
        f"{index} 1 0:1 / {name} {access} - {filesystem} source rw"
        for index, (name, access, filesystem) in enumerate([
            ("/", "ro", "ext4"), ("/work/client", "ro", "ext4"),
            ("/work/profile", "rw", "ext4"), ("/work/home", "rw", "tmpfs"), ("/tmp", "rw", "tmpfs")], 2)))
    state = dict(Id=verifier.unit, LoadState="loaded", InvocationID="a" * 32, User="copilot-ai",
        KillMode="control-group", ActiveState="active", SubState="running", ControlGroup=verifier.group_path,
        RootDirectory=str(image.root), IPAddressAllow="1.1.1.1/32 8.8.8.8/32",
        IPAddressDeny="0.0.0.0/0 ::/0", PrivateNetwork="no", RestrictAddressFamilies="AF_INET AF_UNIX",
        MainPID="321")
    return image, guard, egress(), state, verifier, proc, group


def test_kernel_gate_checks_real_file_metadata_and_bounded_proc_records(kernel):
    native.verify_kernel(*kernel[:5])


@pytest.mark.parametrize("change", ["uid", "capabilities", "seccomp", "environment", "memory",
    "swap", "cpu", "tasks", "cgroup", "root", "profile", "readwrite", "network", "invocation"])
def test_kernel_gate_refuses_one_changed_boundary(kernel, change, tmp_path):
    image, guard, addresses, state, verifier, proc, group = kernel
    if change in ("uid", "capabilities", "seccomp"):
        path = proc / "status"
        raw = path.read_text()
        before, after = {"uid": ("993 993 993 993", "0 0 0 0"),
            "capabilities": ("CapEff:\t0000", "CapEff:\t0001"), "seccomp": ("Seccomp:\t2", "Seccomp:\t0")}[change]
        path.write_text(raw.replace(before, after))
    elif change == "environment":
        with (proc / "environ").open("ab") as output:
            output.write(b"HTTPS_PROXY=sample-private\0")
    elif change in ("memory", "swap", "cpu", "tasks"):
        path = {"memory": "memory.max", "swap": "memory.swap.max", "cpu": "cpu.max", "tasks": "pids.max"}[change]
        (group / path).write_text("max")
    elif change == "cgroup":
        (proc / "cgroup").write_text("0::/other\n")
    elif change in ("root", "profile"):
        path = proc / "root" if change == "root" else image.root / "work/profile"
        path.unlink()
        path.symlink_to(tmp_path, target_is_directory=True)
    elif change == "readwrite":
        path = proc / "mountinfo"
        path.write_text(path.read_text().replace("/work/client ro", "/work/client rw"))
    elif change == "network":
        state["IPAddressAllow"] += " 127.0.0.1/32"
    else:
        state["InvocationID"] = "b" * 32
    with pytest.raises((native.ProcessError, KeyError, OSError)):
        native.verify_kernel(image, guard, addresses, state, verifier)


def test_failed_preflight_never_reserves_or_spawns(host, monkeypatch):
    process, events, _, calls, _ = host
    def refuse(*args):
        raise ValueError("sample-private-pin")
    monkeypatch.setattr(RuntimeImage, "verify", refuse)
    async def scenario():
        with pytest.raises(native.ProcessError):
            await process.start(POLICY)
        assert await process.stop_and_confirm()
    asyncio.run(scenario())
    assert events == calls == []


def test_spawn_failure_retains_reserved_lease_without_false_stop_receipt(host, monkeypatch):
    process, events, _, _, _ = host
    async def refuse(*args, **kwargs):
        raise OSError("sample-private-process-value")
    monkeypatch.setattr(native.asyncio, "create_subprocess_exec", refuse)
    async def scenario():
        with pytest.raises(native.ProcessError):
            await process.start(POLICY)
        assert not await process.stop_and_confirm()
    asyncio.run(scenario())
    assert events == ["reserved", "unlocked"]


def test_cancelling_stop_caller_does_not_cancel_independent_cleanup(host, monkeypatch):
    process, events, _, _, _ = host
    original = SystemdStopVerifier.stop_and_confirm
    async def scenario():
        entered, release = asyncio.Event(), asyncio.Event()
        async def slow(verifier):
            entered.set()
            await release.wait()
            return await original(verifier)
        monkeypatch.setattr(SystemdStopVerifier, "stop_and_confirm", slow)
        await process.start(POLICY)
        stopping = asyncio.create_task(process.stop_and_confirm())
        await entered.wait()
        stopping.cancel()
        with pytest.raises(asyncio.CancelledError):
            await stopping
        assert "idle" not in events
        release.set()
        assert await process.stop_and_confirm()
    asyncio.run(scenario())
    assert events[-3:] == ["stopped", "idle", "unlocked"]


def test_gate_timeout_is_reconciled_without_releasing_native_input(host, monkeypatch):
    process, events, children, _, original_spawn = host
    async def silent(*args, **kwargs):
        child = await original_spawn(sys.executable, "-c", "import sys; sys.stdin.read()", **kwargs)
        children.append(child)
        events.append("spawned")
        return child
    monkeypatch.setattr(native.asyncio, "create_subprocess_exec", silent)
    monkeypatch.setattr(native, "START_SECONDS", 0.1)
    async def scenario():
        with pytest.raises(native.ProcessError):
            await process.start(POLICY)
        assert await process.stop_and_confirm()
    asyncio.run(scenario())
    assert "verified" not in events and events[-3:] == ["stopped", "idle", "unlocked"]


def test_large_native_line_is_discarded_after_stop_without_hanging_pipe_wait(host, monkeypatch):
    process, events, children, _, original_spawn = host
    async def flooding(*args, **kwargs):
        source = ("import sys; print('READY',flush=True); sys.stdin.readline(); "
                  f"print('x'*{native.LINE_LIMIT + 1},flush=True); sys.stdin.read()")
        child = await original_spawn(sys.executable, "-u", "-c", source, **kwargs)
        children.append(child)
        events.append("spawned")
        return child
    monkeypatch.setattr(native.asyncio, "create_subprocess_exec", flooding)
    async def scenario():
        transport = await process.start(POLICY)
        with pytest.raises(native.ProcessError):
            await transport.receive()
        assert await asyncio.wait_for(process.stop_and_confirm(), 3)
    asyncio.run(scenario())
    assert "idle" in events


def test_native_payload_budget_accounts_for_json_control_character_escaping():
    class Input:
        def write(self, raw):
            self.raw = raw
        async def drain(self):
            pass
    async def scenario():
        writer = Input()
        pipes = SimpleNamespace(stdin=writer, stderr=asyncio.StreamReader())
        pipes.stderr.feed_eof()
        transport = native.PipeTextTransport(pipes)
        await transport.send({"text": "\0" * 131072})
        assert json.loads(writer.raw)["text"] == "\0" * 131072
        assert len(writer.raw) > 262144
        await transport.error_reader
    asyncio.run(scenario())


def test_default_process_refuses_non_linux_host_before_profile_or_process_access(monkeypatch):
    monkeypatch.setattr(native, "sys", SimpleNamespace(platform="darwin"))
    with pytest.raises(native.ProcessError, match="trusted_linux_host"):
        native._require_host()


def test_custom_profile_configuration_is_not_read_or_passed_to_runtime(host):
    process, events, _, calls, _ = host
    profile = process.guard.profile.path
    profile.mkdir()
    (profile / "config.toml").write_text("sample-private")
    async def scenario():
        with pytest.raises(native.ProcessError) as error:
            await process.start(POLICY)
        assert "sample-private" not in str(error.value)
        assert not await process.stop_and_confirm()
    asyncio.run(scenario())
    assert calls == [] and events == ["reserved", "unlocked"]


def test_pipe_drain_failure_still_reaps_owned_helper_and_refuses_receipt(host, monkeypatch):
    process, events, children, _, _ = host
    async def refuse(*args):
        raise native.ProcessError("runtime_drain_limit")
    monkeypatch.setattr(native, "_drain", refuse)
    async def scenario():
        await process.start(POLICY)
        assert not await process.stop_and_confirm()
        assert all(child.returncode is not None for child in children)
    asyncio.run(scenario())
    assert "stopped" in events
    assert "idle" not in events


def test_stderr_budget_violation_discovered_after_answer_still_refuses_receipt(host):
    process, events, _, _, _ = host
    async def scenario():
        runner = NativeTextRunner(process)
        assert await runner.run("Synthetic question", POLICY)
        process._transport.error_failed = True
        assert not await runner.stop_and_confirm()
    asyncio.run(scenario())
    assert "idle" not in events
