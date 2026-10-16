"""Explicit trusted-host entry point: python -m app.ai.text_supervisor_host.

Only operator-owned configuration is accepted. No HTTP interface, automatic
profile enrollment, image provisioning, address lookup or credential parsing.
"""
from __future__ import annotations

import argparse
import asyncio
from contextlib import ExitStack, contextmanager
from dataclasses import dataclass
import json
import os
from pathlib import Path
import signal
import socket
import stat
import sys
import time

from .contained_text_process import SystemdTextProcess, _require_host
from .contextual_chat import contextual_chat_policy
from .daily_supervisor import DailySupervisor
from .daily_worker import daily_session_policy
from .job_executor import _complete_critical
from .native_text import TextSessionPolicy
from .runtime_admission import ImageEntry, OfficialEgress, RuntimeImage, _owner, _parents, _path
from .session_profile import FILE_FLAGS, DirectoryBinding, SessionProfileGuard, _directory
from .text_supervisor import PeerIdentity, SupervisorCapacity, TextSupervisor

CONFIG_LIMIT = 131072


class HostError(Exception):
    """Fixed diagnostic codes only; configuration values are never printed."""


def _fields(value, names):
    if not isinstance(value, dict) or set(value) != set(names.split()):
        raise HostError("host_configuration_rejected")
    return value


def _unique(pairs):
    result = dict(pairs)
    if len(result) != len(pairs):
        raise HostError("host_configuration_rejected")
    return result


def _binding(value):
    _fields(value, "path device inode uid gid")
    _path(Path(value["path"]))
    return DirectoryBinding(Path(value["path"]), value["device"], value["inode"], value["uid"], value["gid"])


@dataclass(frozen=True)
class HostConfiguration:
    socket_path: Path
    api_peer: PeerIdentity
    policy: TextSessionPolicy
    image: RuntimeImage
    guard: SessionProfileGuard
    egress: OfficialEgress

    @classmethod
    def decode(cls, raw):
        try:
            value = json.loads(raw.decode("utf-8"), object_pairs_hook=_unique,
                parse_constant=lambda _: (_ for _ in ()).throw(ValueError()))
            _fields(value, "version socket api model image profile control scope egress")
            if type(value["version"]) is not int or value["version"] != 1:
                raise ValueError
            api = _fields(value["api"], "uid gid")
            peer = PeerIdentity(api["uid"], api["gid"])
            socket_path = Path(value["socket"])
            _path(socket_path)
            if len(str(socket_path).encode()) > 103 or peer.uid == 0 or peer.gid == 0:
                raise ValueError
            image = _fields(value["image"], "root device inode entries binary binary_pin")
            if not isinstance(image["entries"], list) or not 1 <= len(image["entries"]) <= 256:
                raise ValueError
            entries = tuple(ImageEntry(**_fields(entry, "relative mode size sha256")) for entry in image["entries"])
            binary_pin = ImageEntry(**_fields(image["binary_pin"], "relative mode size sha256"))
            runtime = RuntimeImage(Path(image["root"]), image["device"], image["inode"], entries,
                                   Path(image["binary"]), binary_pin)
            scope = _fields(value["scope"], "cgroup_root slice_id")
            guard = SessionProfileGuard(profile=_binding(value["profile"]), control=_binding(value["control"]),
                cgroup_root=Path(scope["cgroup_root"]), slice_id=scope["slice_id"])
            if peer.uid == guard.profile.uid or peer.gid == guard.profile.gid:
                raise ValueError
            egress = _fields(value["egress"], "chat auth admitted_at expires_at")
            if not isinstance(egress["chat"], list) or not isinstance(egress["auth"], list):
                raise ValueError
            network = OfficialEgress(tuple(egress["chat"]), tuple(egress["auth"]),
                egress["admitted_at"], egress["expires_at"])
            job_policy = contextual_chat_policy(value["model"])
            policy = TextSessionPolicy(job_policy.model, job_policy.system_prompt)
            # This validates all remaining cross-component constraints without starting anything.
            SystemdTextProcess(runtime, guard, network, policy)
            return cls(socket_path, peer, policy, runtime, guard, network)
        except Exception:
            raise HostError("host_configuration_rejected") from None

    def fixed_identity(self):
        return self.socket_path, self.api_peer, self.policy, self.guard

    def check_idle(self):
        with self.guard._locked() as files:
            if files.read()["phase"] != "idle":
                raise HostError("host_profile_reconciliation_required")
            files.inventory()


def read_configuration(path: Path, *, decode=HostConfiguration.decode):
    fd = parent = None
    try:
        _path(path)
        _parents(path.parent)
        parent = _directory(path.parent)
        fd = os.open(path.name, os.O_RDONLY | FILE_FLAGS, dir_fd=parent)
        before = os.fstat(fd)
        _owner(before)
        if (not stat.S_ISREG(before.st_mode) or stat.S_IMODE(before.st_mode) != 0o600
                or before.st_nlink != 1
                or not 2 <= before.st_size <= CONFIG_LIMIT):
            raise HostError("private_host_configuration_required")
        raw = bytearray()
        while chunk := os.read(fd, min(4096, CONFIG_LIMIT + 1 - len(raw))):
            raw.extend(chunk)
            if len(raw) > CONFIG_LIMIT:
                raise HostError("host_configuration_limit")
        after = os.fstat(fd)
        current = os.stat(path.name, dir_fd=parent, follow_symlinks=False)
        def identity(info):
            return info.st_dev, info.st_ino, info.st_size, info.st_mtime_ns, info.st_ctime_ns
        if identity(before) != identity(after) or identity(after) != identity(current):
            raise HostError("host_configuration_changed")
        return decode(bytes(raw))
    except Exception:
        raise HostError("host_configuration_unavailable") from None
    finally:
        if fd is not None:
            os.close(fd)
        if parent is not None:
            os.close(parent)


def _socket_identity(path):
    info = path.lstat()
    if not stat.S_ISSOCK(info.st_mode):
        raise HostError("host_socket_changed")
    return info.st_dev, info.st_ino, info.st_uid, info.st_gid, stat.S_IMODE(info.st_mode)


@contextmanager
def owned_listener(path: Path, api_gid: int):
    """Never unlink a pre-existing or replaced path, including a stale socket."""
    _parents(path.parent)
    try:
        path.lstat()
    except FileNotFoundError:
        pass
    else:
        raise HostError("host_socket_already_present")
    listener, identity = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM), None
    try:
        listener.bind(str(path))
        identity = _socket_identity(path)
        os.chown(path, -1, api_gid, follow_symlinks=False)
        identity = _socket_identity(path)
        # The protected root parent is not writable by API clients. Linux Python
        # does not support chmod's no-follow option on all admitted hosts.
        os.chmod(path, 0o660)
        identity = _socket_identity(path)
        if identity[2:] != (os.geteuid(), api_gid, 0o660):
            raise HostError("host_socket_permissions_rejected")
        listener.listen(2)
        yield listener
    finally:
        listener.close()
        if identity is not None:
            try:
                current = _socket_identity(path)
            except FileNotFoundError:
                pass
            else:
                if current != identity:
                    raise HostError("host_socket_changed")
                path.unlink()


async def serve(config_path: Path, shutdown: asyncio.Event, *, ready=None,
                daily_socket: Path | None = None, clock_ms=None):
    _require_host()
    initial = await asyncio.to_thread(read_configuration, config_path)
    initial.egress.check_time()
    await asyncio.to_thread(initial.image.verify, initial.policy, initial.egress.hosts)
    await asyncio.to_thread(initial.check_idle)
    clock = clock_ms if clock_ms is not None else lambda: time.time_ns() // 1_000_000
    if not callable(clock):
        raise HostError("host_clock_rejected")
    if daily_socket is not None:
        _path(daily_socket)
        if (daily_socket.parent != initial.socket_path.parent or daily_socket == initial.socket_path
                or len(str(daily_socket).encode()) > 103):
            raise HostError("host_daily_socket_rejected")
        await asyncio.to_thread(initial.image.verify, daily_session_policy(initial.policy.model), initial.egress.hosts)
    def factory(*, daily=False):
        # Only a fresh explicit root-owned admission may change public image/egress.
        current = read_configuration(config_path)
        if current.fixed_identity() != initial.fixed_identity():
            raise HostError("host_identity_changed")
        policy = daily_session_policy(current.policy.model) if daily else current.policy
        return SystemdTextProcess(current.image, current.guard, current.egress, policy)
    capacity = SupervisorCapacity()
    supervisors = []
    with ExitStack() as listeners:
        try:
            listener = listeners.enter_context(owned_listener(initial.socket_path, initial.api_peer.gid))
            text = TextSupervisor(initial.policy, initial.api_peer, factory, capacity=capacity)
            pairs = [(text, listener)]
            if daily_socket is not None:
                listener = listeners.enter_context(owned_listener(daily_socket, initial.api_peer.gid))
                daily = DailySupervisor(daily_session_policy(initial.policy.model), initial.api_peer,
                    lambda: factory(daily=True), clock_ms=clock, capacity=capacity)
                pairs.append((daily, listener))
            for supervisor, listener in pairs:
                supervisors.append(supervisor)
                await supervisor.start(listener)
            if ready is not None:
                ready.set()
            await shutdown.wait()
        finally:
            async def close_all():
                stopped = True
                for supervisor in reversed(supervisors):
                    try:
                        confirmed = await supervisor.close()
                        stopped &= confirmed is True
                    except (Exception, asyncio.CancelledError):
                        stopped = False
                return stopped
            stopped, cancelled = await _complete_critical(close_all())
            if cancelled:
                raise asyncio.CancelledError
    return stopped


async def _main(config_path, *, daily_socket=None):
    shutdown = asyncio.Event()
    loop = asyncio.get_running_loop()
    for name in (signal.SIGTERM, signal.SIGINT):
        loop.add_signal_handler(name, shutdown.set)
    try:
        return await serve(config_path, shutdown, daily_socket=daily_socket)
    finally:
        for name in (signal.SIGTERM, signal.SIGINT):
            loop.remove_signal_handler(name)


def main():
    parser = argparse.ArgumentParser(description="Run the trusted local text supervisor.")
    parser.add_argument("--config", type=Path, required=True)
    parser.add_argument("--daily-socket", type=Path)
    args = parser.parse_args()
    try:
        operation = _main(args.config) if args.daily_socket is None else _main(args.config, daily_socket=args.daily_socket)
        return 0 if asyncio.run(operation) else 1
    except Exception:
        print("host_start_or_stop_unconfirmed", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
