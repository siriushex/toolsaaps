"""Synthetic JPEG decoder and contained host records, no live systemd operation."""
import asyncio
import hashlib
import importlib
import json
import struct
import sys
from pathlib import Path
from types import SimpleNamespace
from uuid import UUID, uuid4

import pytest
from app.ai import runtime_admission
from app.ai.runtime_admission import ImageEntry
from test_meal_vision import jpeg


def component():
    return importlib.import_module("app.ai.meal_decoder")


def test_decoder_properties_have_no_profile_network_or_shared_identity():
    module = component()
    from pathlib import Path
    from types import SimpleNamespace
    image = SimpleNamespace(root=Path("/opt/personal-image/root"), binary=Path("/opt/personal-image/python"))
    values = module.properties(image)
    assert "User=copilot-image" in values and "PrivateNetwork=yes" in values
    assert "RestrictAddressFamilies=AF_UNIX" in values
    assert "MemoryMax=256M" in values and "MemorySwapMax=0" in values
    assert "CPUQuota=25%" in values and "RuntimeMaxSec=12" in values
    assert all("profile" not in value.lower() for value in values)
    assert all("BindPaths=" not in value for value in values)


def test_fixed_decoder_entry_normalizes_once_and_waits_for_independent_stop():
    async def scenario():
        # Real unprivileged child tests only the byte protocol and decoder, not OS containment.
        source = "import json,sys;sys.path[:0]=json.loads(sys.argv[1]);from app.ai.meal_decode_entry import main;main()"
        process = await asyncio.create_subprocess_exec(sys.executable, "-u", "-c", source, json.dumps(sys.path),
            stdin=asyncio.subprocess.PIPE, stdout=asyncio.subprocess.PIPE, stderr=asyncio.subprocess.PIPE)
        try:
            raw = jpeg(metadata=True)
            process.stdin.write(struct.pack("!I", len(raw)) + raw)
            await process.stdin.drain()
            header = await process.stdout.readexactly(12)
            count, width, height = struct.unpack("!III", header)
            clean = await process.stdout.readexactly(count)
            assert (width, height) == (32, 24) and b"image-metadata-sentinel" not in clean
            assert process.returncode is None
            process.stdin.close()
            await asyncio.wait_for(process.wait(), 2)
            assert await process.stdout.read() == await process.stderr.read() == b""
            assert process.returncode == 0
        finally:
            if process.returncode is None:
                process.kill()
                await process.wait()
    asyncio.run(scenario())


@pytest.mark.parametrize("body", [b"", struct.pack("!I", 0), struct.pack("!I", 1_048_577),
                                  struct.pack("!I", 6) + b"secret"])
def test_decoder_entry_rejects_bad_frames_without_payload_diagnostics(body):
    async def scenario():
        source = "import json,sys;sys.path[:0]=json.loads(sys.argv[1]);from app.ai.meal_decode_entry import main;main()"
        process = await asyncio.create_subprocess_exec(sys.executable, "-u", "-c", source, json.dumps(sys.path),
            stdin=asyncio.subprocess.PIPE, stdout=asyncio.subprocess.PIPE, stderr=asyncio.subprocess.PIPE)
        out, err = await process.communicate(body)
        assert out == err == b"" and process.returncode == 70
    asyncio.run(scenario())


@pytest.fixture
def kernel(tmp_path, monkeypatch):
    module = component()
    proc = tmp_path / "proc"
    process = proc / "123"
    (process / "ns").mkdir(parents=True)
    (proc / "self/ns").mkdir(parents=True)
    (process / "ns/net").write_bytes(b"")
    (proc / "self/ns/net").write_bytes(b"")
    root = tmp_path / "image"
    (root / "work").mkdir(parents=True)
    binary = root / "work/client"
    binary.write_bytes(b"python")
    (process / "root").symlink_to(root, target_is_directory=True)
    verifier = module.DecoderStopVerifier(str(uuid4()), "a" * 32, cgroup_root=tmp_path / "cgroup")
    group = verifier.root / verifier.group_path.lstrip("/")
    group.mkdir(parents=True)
    for name, value in (("memory.max", "268435456"), ("memory.swap.max", "0"),
                        ("cpu.max", "25000 100000"), ("pids.max", "8")):
        (group / name).write_text(value)
    (process / "status").write_text("Uid:\t994 994 994 994\nGid:\t986 986 986 986\nGroups:\t986\n"
        "CapEff:\t0\nCapPrm:\t0\nCapBnd:\t0\nCapAmb:\t0\nCapInh:\t0\nNoNewPrivs:\t1\nSeccomp:\t2\n")
    (process / "cgroup").write_text("0::" + verifier.group_path)
    (process / "environ").write_bytes(b"\0".join((key + "=" + value).encode() for key, value in module.DECODER_ENV.items()))
    (process / "mountinfo").write_text("1 0 0:0 / / ro - ext4 root ro\n"
        "2 1 0:0 / /work/client ro - ext4 root ro\n3 1 0:0 / /tmp rw - tmpfs tmpfs rw\n")
    state = dict(Id=verifier.unit, LoadState="loaded", InvocationID="a" * 32, User="copilot-image",
        KillMode="control-group", ActiveState="active", SubState="running", ControlGroup=verifier.group_path,
        RootDirectory=str(root), PrivateNetwork="yes", RestrictAddressFamilies="AF_UNIX", IPAddressAllow="", MainPID="123")
    monkeypatch.setattr(module, "PROC_ROOT", proc)
    return module, SimpleNamespace(root=root, binary=binary), state, verifier, process, group


def test_decoder_kernel_records_require_exact_offline_separate_user(kernel):
    module, image, state, verifier, _, _ = kernel
    module.verify_kernel(image, 994, 986, state, verifier)


@pytest.mark.parametrize("change", ["network", "uid", "groups", "swap", "cpu", "caps", "env", "mount", "namespace"])
def test_decoder_kernel_rejects_weakened_or_leaking_configuration(kernel, change):
    module, image, state, verifier, process, group = kernel
    if change == "network":
        state["PrivateNetwork"] = "no"
    elif change in ("uid", "groups", "caps"):
        path = process / "status"
        text = path.read_text()
        text = text.replace("994 994 994 994", "993 993 993 993") if change == "uid" else (
            text.replace("Groups:\t986", "Groups:\t985 986") if change == "groups" else text.replace("CapEff:\t0", "CapEff:\t1"))
        path.write_text(text)
    elif change in ("swap", "cpu"):
        (group / ("memory.swap.max" if change == "swap" else "cpu.max")).write_text("max")
    elif change == "env":
        with (process / "environ").open("ab") as target:
            target.write(b"\0HOME=/private-sentinel")
    elif change == "mount":
        path = process / "mountinfo"
        path.write_text(path.read_text().replace("/ / ro", "/ / rw"))
    else:
        (process / "ns/net").unlink()
        (process / "ns/net").symlink_to(process.parent / "self/ns/net")
    with pytest.raises(module.DecoderError):
        module.verify_kernel(image, 994, 986, state, verifier)


@pytest.mark.parametrize("mode", ["valid", "kernel-refused", "cancelled-start"])
def test_actual_child_pipe_normalizes_only_after_admission_and_is_reaped(tmp_path, monkeypatch, mode):
    module = component()
    image = module.DecoderRuntimeImage(tmp_path.resolve() / "root", 1, 2,
        (ImageEntry("tmp", 0o755),), tmp_path.resolve() / "python", ImageEntry("python", 0o555, 0, "a" * 64))
    decoder = module.SystemdMealDecoder(image, 994, 986)
    events, children, calls = [], [], []
    original = asyncio.create_subprocess_exec
    started, release = asyncio.Event(), asyncio.Event()
    async def spawn(*argv, **kwargs):
        calls.append(argv)
        code = ("import json,sys;sys.path[:0]=json.loads(sys.argv[1]);"
            "print('READY',flush=True);"
            "gate=sys.stdin.buffer.readline();"
            "assert gate==b'START\\n';"
            "from app.ai.meal_decode_entry import main;main()")
        child = await original(sys.executable, "-u", "-c", code, json.dumps(sys.path), **kwargs)
        children.append(child)
        started.set()
        if mode == "cancelled-start":
            await release.wait()
        return child
    def show(unit):
        return dict(Id=unit, LoadState="loaded" if children else "not-found", User="copilot-image",
            KillMode="control-group", InvocationID="a" * 32, ControlGroup="/system.slice/" + unit)
    async def stop(verifier):
        events.append("stopped")
        for child in children:
            if child.returncode is None:
                child.terminate()
            await child.wait()
        return True
    monkeypatch.setattr(module, "_require_host", lambda: None)
    monkeypatch.setattr(module.DecoderRuntimeImage, "verify", lambda self: events.append("image"))
    def kernel(*args):
        events.append("kernel")
        if mode == "kernel-refused":
            raise ValueError("private-sentinel")
    monkeypatch.setattr(module, "verify_kernel", kernel)
    monkeypatch.setattr(module, "_show", show)
    monkeypatch.setattr(module.DecoderStopVerifier, "stop_and_confirm", stop)
    monkeypatch.setattr(module.asyncio, "create_subprocess_exec", spawn)
    async def scenario():
        task = asyncio.create_task(decoder.normalize(jpeg(metadata=True)))
        if mode == "cancelled-start":
            await started.wait()
            task.cancel()
            release.set()
            with pytest.raises(asyncio.CancelledError):
                await task
        elif mode == "kernel-refused":
            with pytest.raises(module.DecoderError) as error:
                await task
            assert "private-sentinel" not in str(error.value)
        else:
            normalized = await task
            assert normalized.width == 32 and b"image-metadata-sentinel" not in normalized.jpeg
        assert events == ["image", "kernel", "stopped"]
        assert await decoder.stop_and_confirm()
        assert all(child.returncode is not None for child in children)
    asyncio.run(scenario())
    assert len(calls) == 1 and "--property=PrivateNetwork=yes" in calls[0]


def test_decoder_image_pins_entry_and_module_sources_not_just_operator_hashes(tmp_path, monkeypatch):
    module = component()
    root = tmp_path.resolve() / "root"
    root.mkdir(mode=0o755)
    sources = {"work/decoder.py": "meal_decode_entry.py", "work/lib/app/ai/meal_photo.py": "meal_photo.py",
               "work/lib/app/ai/meal_vision_image.py": "meal_vision_image.py"}
    bodies = {name: Path(module.__file__).with_name(source).read_bytes() for name, source in sources.items()}
    bodies.update({"work/client": b"", "bin/env": b"env", "bin/sh": b"sh",
        "work/lib/app/__init__.py": b"", "work/lib/app/ai/__init__.py": b"",
        "work/lib/PIL/__init__.py": b"public-fixture", "work/lib/PIL/Image.py": b"public-fixture"})
    for name in ("tmp", "work/empty"):
        (root / name).mkdir(mode=0o755, parents=True)
    for name, raw in bodies.items():
        target = root / name
        target.parent.mkdir(mode=0o755, parents=True, exist_ok=True)
        target.write_bytes(raw)
        target.chmod(0o444)
    binary = tmp_path.resolve() / "python"
    binary.write_bytes(b"public-python-fixture")
    binary.chmod(0o555)
    def pinned():
        entries = tuple(ImageEntry(str(path.relative_to(root)), 0o755) if path.is_dir() else
            ImageEntry(str(path.relative_to(root)), 0o444, path.stat().st_size, hashlib.sha256(path.read_bytes()).hexdigest())
            for path in sorted(root.rglob("*")))
        return module.DecoderRuntimeImage(root, root.stat().st_dev, root.stat().st_ino, entries,
            binary, ImageEntry("python", 0o555, binary.stat().st_size, hashlib.sha256(binary.read_bytes()).hexdigest()))
    monkeypatch.setattr(runtime_admission, "_parents", lambda path: None)
    monkeypatch.setattr(runtime_admission, "_owner", lambda info: None)
    pinned().verify()
    target = root / "work/decoder.py"
    target.chmod(0o644)
    target.write_bytes(b"changed-public-script")
    target.chmod(0o444)
    with pytest.raises(runtime_admission.AdmissionError, match="source_rejected"):
        pinned().verify()


def test_decoder_stop_does_not_accept_the_native_session_user(tmp_path, monkeypatch):
    from test_ai_systemd_stop import install_commands, setup, show
    original, _ = setup(tmp_path)
    verifier = component().DecoderStopVerifier(str(UUID(hex=original.unit.removeprefix("copilot-ai-job-").removesuffix(".service"))), "a" * 32,
                                              cgroup_root=original.root)
    install_commands(monkeypatch, verifier, show(verifier), show(verifier))
    assert asyncio.run(verifier.stop_and_confirm()) is False
    install_commands(monkeypatch, verifier, show(verifier, User="copilot-image"), show(verifier, User="copilot-image"))
    assert asyncio.run(verifier.stop_and_confirm()) is True
