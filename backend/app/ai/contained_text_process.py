"""Trusted Linux host implementation of ContainedTextProcess.

Construct only in the privileged text supervisor. There is no HTTP command
runner, automatic enrollment, installation, credential reader or provider retry.
"""
from __future__ import annotations

import asyncio
import ipaddress
import json
import os
from pathlib import Path
import re
import subprocess
import sys
from uuid import UUID

from .native_text import NATIVE_SETTINGS, TextSessionPolicy
from .runtime_admission import OfficialEgress, RuntimeImage, _parents, _path
from .session_profile import ProfileError, SessionProfileGuard
from .systemd_stop import FIELDS, SystemdStopVerifier

START_SECONDS = 12
PIPE_SECONDS = 2
LINE_LIMIT = 65536
STDERR_LIMIT = 32768
SEND_LIMIT = 6 * 131072 + 1024
GATE = 'printf "READY\\n"; IFS= read -r gate; [ "$gate" = START ] || exit 70; exec "$@"'
# These native compatibility keys are fixed upstream API names, not identifiers.
NATIVE_ENV = {
    "HOME": "/work/home", "TMPDIR": "/tmp", "PATH": "/work/empty", "LANG": "C.UTF-8",
    "MALLOC_ARENA_MAX": "2", "RUST_LOG": "off", "SSL_CERT_FILE": "/work/ca.pem",
    "CODEX_HOME": "/work/profile", "CODEX_APP_SERVER_LOGIN_ISSUER": "https://auth.openai.com",
    "CODEX_INTERNAL_ORIGINATOR_OVERRIDE": "personal-ai",
}
HOST_ENV = {"PATH": "/usr/bin:/bin", "LC_ALL": "C", "SYSTEMD_PAGER": "cat"}
PROC_ROOT = Path("/proc")
SHOW_FIELDS = (*FIELDS, "MainPID", "PrivateNetwork", "IPAddressAllow", "IPAddressDeny",
               "RestrictAddressFamilies", "RootDirectory")


class ProcessError(Exception):
    """Fixed error codes; never includes native stderr or host configuration."""


def _require_host():
    if sys.platform != "linux" or os.geteuid() != 0:
        raise ProcessError("trusted_linux_host_required")


def _show(unit):
    result = subprocess.run(("/usr/bin/systemctl", "show", "--no-pager",
        "--property=" + ",".join(SHOW_FIELDS), unit), stdin=subprocess.DEVNULL,
        stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, timeout=5, check=False, env=HOST_ENV)
    if result.returncode != 0 or len(result.stdout) > 8192:
        raise ProcessError("unit_snapshot_unavailable")
    rows = result.stdout.decode("ascii").splitlines()
    values = dict(row.split("=", 1) for row in rows)
    if len(values) != len(rows) or set(values) != set(SHOW_FIELDS):
        raise ProcessError("unit_snapshot_rejected")
    return values


def properties(image: RuntimeImage, guard: SessionProfileGuard, egress: OfficialEgress):
    _path(guard.profile.path)
    private = f"rw,size=16M,mode=0700,uid={guard.profile.uid},gid={guard.profile.gid}"
    values = (
        "User=copilot-ai", "Group=copilot-ai", "RootDirectory=" + str(image.root),
        "WorkingDirectory=/work", "MountAPIVFS=yes", "ProtectSystem=strict", "ProtectHome=yes",
        "PrivateDevices=yes", "PrivateNetwork=no", "RestrictAddressFamilies=AF_INET AF_UNIX",
        "IPAddressDeny=any", *("IPAddressAllow=" + address + "/32" for address in egress.addresses),
        "SocketBindDeny=any", "NoNewPrivileges=yes", "CapabilityBoundingSet=",
        "ProtectKernelTunables=yes", "ProtectKernelModules=yes", "ProtectControlGroups=yes",
        "ProtectProc=invisible", "ProcSubset=pid", "RestrictSUIDSGID=yes", "RestrictNamespaces=yes",
        "RestrictRealtime=yes", "LockPersonality=yes", "SystemCallFilter=@system-service",
        "KillMode=control-group", "TimeoutStopSec=3", "RuntimeMaxSec=840",
        "MemoryMax=512M", "MemorySwapMax=0", "CPUQuota=25%", "TasksMax=32",
        "LimitCORE=0", "LimitFSIZE=32M", "LimitNOFILE=128", "UMask=0077",
        "TemporaryFileSystem=/work/home:" + private + " /tmp:rw,size=16M,mode=1777",
        "BindReadOnlyPaths=" + str(image.binary) + ":/work/client",
        "BindPaths=" + str(guard.profile.path) + ":/work/profile", "ReadWritePaths=/work/profile",
    )
    if guard.slice_id is not None:
        values += ("Slice=personalai" + UUID(guard.slice_id).hex + ".slice",)
    return values


def _bounded(path, limit=16384):
    with path.open("rb") as source:
        value = source.read(limit + 1)
    if len(value) > limit:
        raise ProcessError("kernel_metadata_limit")
    return value


def verify_kernel(image, guard, egress, state, verifier):
    """Check the blocked gate process before START or any context is supplied."""
    if (state["Id"] != verifier.unit or state["LoadState"] != "loaded"
            or state["InvocationID"] != verifier.invocation_id
            or state["User"] != "copilot-ai" or state["KillMode"] != "control-group"
            or state["ActiveState"] != "active" or state["SubState"] != "running"
            or state["ControlGroup"] != verifier.group_path or state["RootDirectory"] != str(image.root)):
        raise ProcessError("runtime_unit_changed")
    addresses = {str(ipaddress.ip_network(value, strict=False)) for value in state["IPAddressAllow"].split()}
    if (addresses != {value + "/32" for value in egress.addresses}
            or "0.0.0.0/0" not in state["IPAddressDeny"].split()
            or state["PrivateNetwork"] != "no"
            or set(state["RestrictAddressFamilies"].split()) != {"AF_INET", "AF_UNIX"}):
        raise ProcessError("runtime_network_changed")
    if not re.fullmatch(r"[1-9][0-9]{0,9}", state["MainPID"]):
        raise ProcessError("runtime_pid_rejected")
    proc = PROC_ROOT / state["MainPID"]
    status = dict(line.split(":", 1) for line in _bounded(proc / "status").decode("ascii").splitlines()
                  if ":" in line)
    if (status["Uid"].split() != [str(guard.profile.uid)] * 4
            or status["Gid"].split() != [str(guard.profile.gid)] * 4
            or status["Groups"].split() != [str(guard.profile.gid)]
            or any(int(status[key], 16) for key in ("CapEff", "CapPrm", "CapBnd", "CapAmb", "CapInh"))
            or status["NoNewPrivs"].strip() != "1" or status["Seccomp"].strip() != "2"):
        raise ProcessError("runtime_privileges_rejected")
    if _bounded(proc / "cgroup", 4096).decode("ascii").strip() != "0::" + verifier.group_path:
        raise ProcessError("runtime_cgroup_changed")
    group = guard.cgroup_root / verifier.group_path.lstrip("/")
    for name, expected in (("memory.max", "536870912"), ("memory.swap.max", "0"),
                           ("cpu.max", "25000 100000"), ("pids.max", "32")):
        if _bounded(group / name, 1024).decode("ascii").strip() != expected:
            raise ProcessError("runtime_limits_rejected")
    for actual, expected in ((proc / "root", image.root),
                             (proc / "root/work/profile", guard.profile.path),
                             (proc / "root/work/client", image.binary)):
        first, second = actual.stat(), expected.stat()
        if (first.st_dev, first.st_ino) != (second.st_dev, second.st_ino):
            raise ProcessError("runtime_mount_changed")
    pairs = [entry.split(b"=", 1) for entry in _bounded(proc / "environ").split(b"\0") if entry]
    env = dict(pairs)
    expected = {key.encode(): value.encode() for key, value in NATIVE_ENV.items()}
    if env.get(b"PWD") == b"/work":
        expected[b"PWD"] = b"/work"
    if len(env) != len(pairs) or env != expected:
        raise ProcessError("runtime_environment_rejected")
    mounts = {}
    for line in _bounded(proc / "mountinfo", 65536).decode("ascii").splitlines():
        fields = line.split()
        separator = fields.index("-")
        # The admitted paths contain no mountinfo escaping. Refuse ambiguous overlays.
        if fields[4] not in {"/", "/work/client", "/work/profile", "/work/home", "/tmp"}:
            continue
        if fields[4] in mounts:
            raise ProcessError("runtime_mount_ambiguous")
        mounts[fields[4]] = (set(fields[5].split(",")), fields[separator + 1])
    for name, access, filesystem in (("/", "ro", None), ("/work/client", "ro", None),
                                    ("/work/profile", "rw", None), ("/work/home", "rw", "tmpfs"),
                                    ("/tmp", "rw", "tmpfs")):
        options, actual_fs = mounts[name]
        if access not in options or filesystem is not None and actual_fs != filesystem:
            raise ProcessError("runtime_mount_access_rejected")


class PipeTextTransport:
    def __init__(self, process):
        self.process = process
        self.error_bytes = 0
        self.error_failed = False
        self.error_reader = asyncio.create_task(self._discard_errors())
        self.closed = False
        self._reading = None

    async def _discard_errors(self):
        count = 0
        try:
            while block := await self.process.stderr.read(4096):
                count += len(block)
                self.error_bytes = count
                if count > STDERR_LIMIT:
                    self.error_failed = True
                    return False
            return True
        except Exception:
            self.error_failed = True
            return False

    def _check(self):
        if self.closed or self.error_reader.done() and self.error_reader.result() is not True:
            raise ProcessError("runtime_pipe_rejected")

    async def send(self, value):
        self._check()
        raw = json.dumps(value, ensure_ascii=False, separators=(",", ":"), allow_nan=False).encode() + b"\n"
        if len(raw) > SEND_LIMIT:
            raise ProcessError("runtime_input_limit")
        self.process.stdin.write(raw)
        await self.process.stdin.drain()
        self._check()

    async def receive(self):
        self._check()
        if self._reading is not None:
            raise ProcessError("runtime_concurrent_read")
        reading = asyncio.create_task(self.process.stdout.readline())
        self._reading = reading
        try:
            await asyncio.wait({reading, self.error_reader}, return_when=asyncio.FIRST_COMPLETED)
            self._check()
            raw = await reading
            if not raw or len(raw) > LINE_LIMIT or not raw.endswith(b"\n"):
                raise ProcessError("runtime_output_limit")
            return raw
        except (ValueError, OSError):
            raise ProcessError("runtime_pipe_rejected") from None
        finally:
            if not reading.done():
                reading.cancel()
            await asyncio.gather(reading, return_exceptions=True)
            self._reading = None

    async def close(self):
        self.closed = True
        self.process.stdin.close()
        if self._reading is not None:
            self._reading.cancel()
            await asyncio.gather(self._reading, return_exceptions=True)
        if not self.error_reader.done():
            self.error_reader.cancel()
        await asyncio.gather(self.error_reader, return_exceptions=True)


async def _drain(reader, limit):
    count = 0
    while block := await reader.read(4096):
        count += len(block)
        if count > limit:
            raise ProcessError("runtime_drain_limit")


class SystemdTextProcess:
    """One fixed native session with a durable profile lease and exact stop.

    The host supplies an admitted immutable image, expiring official addresses
    and an initialized guard. Construction has no filesystem/process effects.
    """
    def __init__(self, image: RuntimeImage, guard: SessionProfileGuard,
                 egress: OfficialEgress, policy: TextSessionPolicy):
        if (not isinstance(image, RuntimeImage) or not isinstance(guard, SessionProfileGuard)
                or not isinstance(egress, OfficialEgress) or not isinstance(policy, TextSessionPolicy)
                or guard.control.uid != 0 or guard.control.gid != 0
                or guard.cgroup_root != Path("/sys/fs/cgroup")):
            raise ValueError("trusted_runtime_configuration_required")
        for private in (guard.profile.path, guard.control.path):
            if any(private == path or private in path.parents or path in private.parents
                   for path in (image.root, image.binary)):
                raise ValueError("runtime_and_private_state_must_be_disjoint")
        properties(image, guard, egress)
        self.image, self.guard, self.egress, self.policy = image, guard, egress, policy
        self._starting = self._stopping = self._spawn = None
        self._context = self._lease = self._verifier = self._transport = self._process = None
        self._closing = self._used = False

    async def start(self, policy):
        if self._used or self._closing or policy != self.policy:
            raise ProcessError("runtime_start_rejected")
        self._used = True
        self._starting = asyncio.create_task(self._start())
        # Preserve an in-flight OS creation after caller cancellation for teardown.
        return await asyncio.shield(self._starting)

    async def _start(self):
        try:
            async with asyncio.timeout(START_SECONDS):
                _require_host()
                self.egress.check_time()
                await asyncio.to_thread(self.image.verify, self.policy, self.egress.hosts)
                for private in (self.guard.profile.path, self.guard.control.path):
                    await asyncio.to_thread(_parents, private.parent)
                if self._closing:
                    raise ProcessError("runtime_closing")
                self._context = self.guard.reserve()
                self._lease = self._context.__enter__()
                # Auth/cache files may change; local configuration is never an
                # alternate source for provider settings or model instructions.
                if any((self.guard.profile.path / name).exists()
                       or (self.guard.profile.path / name).is_symlink()
                       for name in ("config.toml", "AGENTS.md", "instructions.md")):
                    raise ProcessError("runtime_profile_configuration_rejected")
                unit = "copilot-ai-job-" + self._lease.run_id.replace("-", "") + ".service"
                before = await asyncio.to_thread(_show, unit)
                if before["LoadState"] != "not-found" or self._closing:
                    raise ProcessError("runtime_unit_present")
                native = ["/work/client"]
                for setting in NATIVE_SETTINGS:
                    native += ["-c", setting]
                native += ["app-server"]
                argv = ["/usr/bin/systemd-run", "--unit=" + unit, "--pipe", "--quiet", "--wait", "--collect"]
                argv += ["--property=" + value for value in properties(self.image, self.guard, self.egress)]
                argv += ["/bin/env", "-i", *(key + "=" + value for key, value in NATIVE_ENV.items()),
                         "/bin/sh", "-c", GATE, "personal-ai", *native]
                self._spawn = asyncio.create_task(asyncio.create_subprocess_exec(*argv,
                    stdin=asyncio.subprocess.PIPE, stdout=asyncio.subprocess.PIPE,
                    stderr=asyncio.subprocess.PIPE, env=HOST_ENV, limit=LINE_LIMIT))
                self._process = await asyncio.shield(self._spawn)
                self._transport = PipeTextTransport(self._process)
                if await self._transport.receive() != b"READY\n":
                    raise ProcessError("runtime_gate_rejected")
                state = await self._bind()
                await asyncio.to_thread(verify_kernel, self.image, self.guard, self.egress, state, self._verifier)
                self.egress.check_time()
                if self._closing:
                    raise ProcessError("runtime_closing")
                self._process.stdin.write(b"START\n")
                await self._process.stdin.drain()
                return self._transport
        except BaseException as error:
            if isinstance(error, (KeyboardInterrupt, SystemExit)):
                raise
            raise ProcessError("runtime_start_failed") from None

    async def _bind(self):
        unit = "copilot-ai-job-" + self._lease.run_id.replace("-", "") + ".service"
        state = await asyncio.to_thread(_show, unit)
        if (state["Id"] != unit or state["LoadState"] != "loaded"
                or state["User"] != "copilot-ai" or state["KillMode"] != "control-group"
                or not re.fullmatch(r"[a-f0-9]{32}", state["InvocationID"])):
            raise ProcessError("runtime_invocation_unavailable")
        verifier = SystemdStopVerifier(self._lease.run_id, state["InvocationID"],
            cgroup_root=self.guard.cgroup_root, slice_id=self.guard.slice_id)
        if state["ControlGroup"] != verifier.group_path:
            raise ProcessError("runtime_invocation_changed")
        self._lease.bind(verifier.invocation_id)
        self._verifier = verifier
        return state

    async def stop_and_confirm(self):
        self._closing = True
        if self._stopping is None:
            self._stopping = asyncio.create_task(self._stop())
        return await asyncio.shield(self._stopping)

    async def _stop(self):
        try:
            if self._starting is not None:
                await asyncio.gather(self._starting, return_exceptions=True)
            if self._spawn is not None and self._process is None:
                try:
                    async with asyncio.timeout(PIPE_SECONDS):
                        self._process = await asyncio.shield(self._spawn)
                except Exception:
                    pass
            if self._lease is None:
                return self._spawn is None
            if self._verifier is None:
                if self._spawn is None:
                    # Reserved-but-unbound state is deliberately retained.
                    return False
                await self._bind()
            await self._lease.finish(self._verifier)
            if self._process is not None:
                if self._transport is not None:
                    await self._transport.close()
                try:
                    async with asyncio.timeout(PIPE_SECONDS):
                        async with asyncio.TaskGroup() as tasks:
                            tasks.create_task(self._process.wait())
                            tasks.create_task(_drain(self._process.stdout, self.policy.max_stream_bytes))
                            consumed = self._transport.error_bytes if self._transport is not None else 0
                            tasks.create_task(_drain(self._process.stderr, max(0, STDERR_LIMIT - consumed)))
                except Exception:
                    # The native cgroup is proven empty; terminate only our pipe helper.
                    if self._process.returncode is None:
                        self._process.kill()
                    # Both producers are now stopped; only finite buffered data
                    # remains. Join all pipe readers, even after a drain failure.
                    async with asyncio.timeout(PIPE_SECONDS):
                        await self._process.communicate()
                    return False
            return self._transport is None or not self._transport.error_failed
        except (Exception, asyncio.CancelledError):
            return False
        finally:
            if self._transport is not None:
                try:
                    await self._transport.close()
                except Exception:
                    pass
            if self._context is not None:
                try:
                    self._context.__exit__(None, None, None)
                except ProfileError:
                    pass
