"""Trusted Linux launcher for a pinned, offline, credential-free JPEG decoder.

No arbitrary command/path/environment interface or profile mount. Construction
starts nothing. The operator must provision a separate public image and UID.
"""
from __future__ import annotations

import asyncio
import hashlib
import re
import struct
from pathlib import Path
from uuid import uuid4

from .contained_text_process import (
    GATE,
    HOST_ENV,
    _bounded,
    _drain,
    _require_host,
    _show,
)
from .job_executor import _complete_critical
from .meal_photo import MAX_IMAGE_BYTES
from .meal_vision_image import MAX_IMAGE_EDGE, MAX_IMAGE_PIXELS, NormalizedMealImage
from .runtime_admission import AdmissionError, RuntimeImage
from .systemd_stop import SystemdStopVerifier

PROC_ROOT = Path("/proc")
SECONDS = 12
DECODER_ENV = {"HOME": "/tmp", "TMPDIR": "/tmp", "PATH": "/work/empty", "LANG": "C.UTF-8"}


class DecoderError(Exception):
    """Fixed codes only, without decoder, path, kernel or image diagnostics."""


class DecoderRuntimeImage(RuntimeImage):
    def verify(self):
        expected = self._verify_tree()
        required = {"work/client", "work/empty", "tmp", "bin/env", "bin/sh",
                    "work/decoder.py", "work/lib/PIL/__init__.py", "work/lib/PIL/Image.py"}
        if (not required <= expected.keys()
                or any(name == "work/profile" or name.startswith("work/profile/") for name in expected)
                or any(expected[name].size is not None for name in ("tmp", "work/empty"))
                or any(name.startswith("tmp/") or name.startswith("work/empty/") for name in expected)):
            raise AdmissionError("decoder_layout_rejected")
        sources = {"work/decoder.py": "meal_decode_entry.py",
            "work/lib/app/ai/meal_vision_image.py": "meal_vision_image.py",
            "work/lib/app/ai/meal_photo.py": "meal_photo.py"}
        pins = {name: hashlib.sha256(Path(__file__).with_name(source).read_bytes()).hexdigest()
                for name, source in sources.items()}
        for name in ("work/lib/app/__init__.py", "work/lib/app/ai/__init__.py"):
            pins[name] = hashlib.sha256(b"").hexdigest()
        if any(name not in expected or expected[name].sha256 != digest for name, digest in pins.items()):
            raise AdmissionError("decoder_source_rejected")


def properties(image):
    return (
        "User=copilot-image", "Group=copilot-image", "RootDirectory=" + str(image.root),
        "WorkingDirectory=/work", "MountAPIVFS=yes", "ProtectSystem=strict", "ProtectHome=yes",
        "PrivateDevices=yes", "PrivateNetwork=yes", "RestrictAddressFamilies=AF_UNIX",
        "IPAddressDeny=any", "SocketBindDeny=any", "NoNewPrivileges=yes", "CapabilityBoundingSet=",
        "ProtectKernelTunables=yes", "ProtectKernelModules=yes", "ProtectControlGroups=yes",
        "ProtectProc=invisible", "ProcSubset=pid", "RestrictSUIDSGID=yes", "RestrictNamespaces=yes",
        "RestrictRealtime=yes", "LockPersonality=yes", "SystemCallFilter=@system-service",
        "KillMode=control-group", "TimeoutStopSec=3", "RuntimeMaxSec=12",
        "MemoryMax=256M", "MemorySwapMax=0", "CPUQuota=25%", "TasksMax=8",
        "LimitCORE=0", "LimitFSIZE=0", "LimitNOFILE=32", "UMask=0077",
        "TemporaryFileSystem=/tmp:rw,size=8M,mode=1777",
        "BindReadOnlyPaths=" + str(image.binary) + ":/work/client",
    )


class DecoderStopVerifier(SystemdStopVerifier):
    worker_user = "copilot-image"


def verify_kernel(image, uid, gid, state, verifier):
    if (state["Id"] != verifier.unit or state["LoadState"] != "loaded"
            or state["InvocationID"] != verifier.invocation_id or state["User"] != "copilot-image"
            or state["KillMode"] != "control-group" or state["ActiveState"] != "active"
            or state["SubState"] != "running" or state["ControlGroup"] != verifier.group_path
            or state["RootDirectory"] != str(image.root) or state["PrivateNetwork"] != "yes"
            or state["RestrictAddressFamilies"].split() != ["AF_UNIX"]
            or state["IPAddressAllow"] or not re.fullmatch(r"[1-9][0-9]{0,9}", state["MainPID"])):
        raise DecoderError("decoder_unit_rejected")
    proc = PROC_ROOT / state["MainPID"]
    status = dict(line.split(":", 1) for line in _bounded(proc / "status").decode("ascii").splitlines() if ":" in line)
    if (status["Uid"].split() != [str(uid)] * 4 or status["Gid"].split() != [str(gid)] * 4
            or status["Groups"].split() != [str(gid)]
            or any(int(status[name], 16) for name in ("CapEff", "CapPrm", "CapBnd", "CapAmb", "CapInh"))
            or status["NoNewPrivs"].strip() != "1" or status["Seccomp"].strip() != "2"):
        raise DecoderError("decoder_privileges_rejected")
    if ((proc / "ns/net").stat().st_ino == (PROC_ROOT / "self/ns/net").stat().st_ino
            or _bounded(proc / "cgroup", 4096).decode("ascii").strip() != "0::" + verifier.group_path):
        raise DecoderError("decoder_namespace_rejected")
    group = verifier.root / verifier.group_path.lstrip("/")
    for name, value in (("memory.max", "268435456"), ("memory.swap.max", "0"),
                        ("cpu.max", "25000 100000"), ("pids.max", "8")):
        if _bounded(group / name, 1024).decode("ascii").strip() != value:
            raise DecoderError("decoder_limits_rejected")
    for actual, expected in ((proc / "root", image.root), (proc / "root/work/client", image.binary)):
        one, two = actual.stat(), expected.stat()
        if (one.st_dev, one.st_ino) != (two.st_dev, two.st_ino):
            raise DecoderError("decoder_mount_rejected")
    pairs = [pair.split(b"=", 1) for pair in _bounded(proc / "environ").split(b"\0") if pair]
    environment = dict(pairs)
    expected = {key.encode(): value.encode() for key, value in DECODER_ENV.items()}
    if environment.get(b"PWD") == b"/work":
        expected[b"PWD"] = b"/work"
    if len(environment) != len(pairs) or environment != expected:
        raise DecoderError("decoder_environment_rejected")
    mounts = {}
    for line in _bounded(proc / "mountinfo", 65536).decode("ascii").splitlines():
        fields = line.split()
        if fields[4] in {"/", "/work/client", "/tmp"}:
            if fields[4] in mounts:
                raise DecoderError("decoder_mount_ambiguous")
            mounts[fields[4]] = (set(fields[5].split(",")), fields[fields.index("-") + 1])
    if ("ro" not in mounts["/"][0] or "ro" not in mounts["/work/client"][0]
            or "rw" not in mounts["/tmp"][0] or mounts["/tmp"][1] != "tmpfs"):
        raise DecoderError("decoder_mount_access_rejected")


class SystemdMealDecoder:
    def __init__(self, image: DecoderRuntimeImage, uid: int, gid: int):
        if (type(image) is not DecoderRuntimeImage
                or any(type(value) is not int or not 0 < value < 2**31 for value in (uid, gid))):
            raise ValueError("decoder_configuration_rejected")
        self.image, self.uid, self.gid = image, uid, gid
        self.run_id = str(uuid4())
        self.unit = "copilot-ai-job-" + self.run_id.replace("-", "") + ".service"
        self._used = self._closing = False
        self._starting = self._spawn = self._process = self._verifier = self._reading = self._stopping = None

    async def normalize(self, raw):
        if self._used or self._closing:
            raise DecoderError("decoder_already_used")
        self._used = True
        try:
            if type(raw) is not bytes or not 1 <= len(raw) <= MAX_IMAGE_BYTES:
                raise DecoderError("decoder_input_rejected")
            async with asyncio.timeout(SECONDS):
                self._starting = asyncio.create_task(self._start())
                await asyncio.shield(self._starting)
                if self._closing:
                    raise DecoderError("decoder_stopping")
                self._process.stdin.write(struct.pack("!I", len(raw)) + raw)
                await self._process.stdin.drain()
                self._reading = asyncio.create_task(self._read_image())
                result = await asyncio.shield(self._reading)
        except asyncio.CancelledError:
            raise
        except Exception:
            raise DecoderError("decoder_failed") from None
        finally:
            stopped, cancelled = await _complete_critical(self.stop_and_confirm())
            if cancelled:
                raise asyncio.CancelledError
        if stopped is not True:
            raise DecoderError("decoder_stop_unconfirmed")
        return result

    async def _start(self):
        async with asyncio.timeout(SECONDS):
            _require_host()
            await asyncio.to_thread(self.image.verify)
            if self._closing or (await asyncio.to_thread(_show, self.unit))["LoadState"] != "not-found":
                raise DecoderError("decoder_start_rejected")
            argv = ["/usr/bin/systemd-run", "--unit=" + self.unit, "--pipe", "--quiet", "--wait", "--collect"]
            argv += ["--property=" + value for value in properties(self.image)]
            argv += ["/bin/env", "-i", *(key + "=" + value for key, value in DECODER_ENV.items()),
                "/bin/sh", "-c", GATE, "personal-image", "/work/client", "-I", "-S", "-B", "/work/decoder.py"]
            self._spawn = asyncio.create_task(asyncio.create_subprocess_exec(*argv, stdin=asyncio.subprocess.PIPE,
                stdout=asyncio.subprocess.PIPE, stderr=asyncio.subprocess.PIPE, env=HOST_ENV, limit=65536))
            self._process = await asyncio.shield(self._spawn)
            if await self._process.stdout.readline() != b"READY\n":
                raise DecoderError("decoder_gate_rejected")
            state = await self._bind()
            await asyncio.to_thread(verify_kernel, self.image, self.uid, self.gid, state, self._verifier)
            if self._closing:
                raise DecoderError("decoder_stopping")
            self._process.stdin.write(b"START\n")
            await self._process.stdin.drain()

    async def _bind(self):
        state = await asyncio.to_thread(_show, self.unit)
        if (state["Id"] != self.unit or state["LoadState"] != "loaded" or state["User"] != "copilot-image"
                or state["KillMode"] != "control-group" or not re.fullmatch(r"[a-f0-9]{32}", state["InvocationID"])):
            raise DecoderError("decoder_invocation_rejected")
        verifier = DecoderStopVerifier(self.run_id, state["InvocationID"])
        if state["ControlGroup"] != verifier.group_path:
            raise DecoderError("decoder_cgroup_rejected")
        self._verifier = verifier
        return state

    async def _read_image(self):
        count, width, height = struct.unpack("!III", await self._process.stdout.readexactly(12))
        if (not 1 <= count <= MAX_IMAGE_BYTES or not 1 <= width <= MAX_IMAGE_EDGE
                or not 1 <= height <= MAX_IMAGE_EDGE or width * height > MAX_IMAGE_PIXELS):
            raise DecoderError("decoder_output_rejected")
        raw = await self._process.stdout.readexactly(count)
        if not raw.startswith(b"\xff\xd8\xff") or not raw.endswith(b"\xff\xd9"):
            raise DecoderError("decoder_output_rejected")
        return NormalizedMealImage(raw, width, height)

    async def stop_and_confirm(self):
        self._closing = True
        if self._stopping is None:
            self._stopping = asyncio.create_task(self._stop())
        return await asyncio.shield(self._stopping)

    async def _stop(self):
        try:
            if self._starting is not None:
                await asyncio.gather(self._starting, return_exceptions=True)
            if self._spawn is None:
                return True
            if self._process is None:
                async with asyncio.timeout(2):
                    self._process = await asyncio.shield(self._spawn)
            if self._verifier is None:
                await self._bind()
            if await self._verifier.stop_and_confirm() is not True:
                return False
            if self._reading is not None:
                if not self._reading.done():
                    self._reading.cancel()
                await asyncio.gather(self._reading, return_exceptions=True)
            self._process.stdin.close()
            async with asyncio.timeout(2):
                async with asyncio.TaskGroup() as tasks:
                    tasks.create_task(self._process.wait())
                    tasks.create_task(_drain(self._process.stdout, 0))
                    tasks.create_task(_drain(self._process.stderr, 0))
            return True
        except (Exception, asyncio.CancelledError):
            if self._verifier is not None and self._process is not None and self._process.returncode is None:
                # Kill only our pipe helper; this never supplies a stop receipt.
                self._process.kill()
                try:
                    async with asyncio.timeout(2):
                        await self._process.communicate()
                except Exception:
                    pass
            return False
