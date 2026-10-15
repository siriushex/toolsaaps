"""Inactive trusted-host session guard; no launcher, route or credential reader.

One admitted local control directory must be mapped to each profile. The native
worker must not mount the control directory. Stop verifiers are trusted host
dependencies, never deserialized receipts from an HTTP client.
"""
from __future__ import annotations

import asyncio
from contextlib import contextmanager
from dataclasses import asdict, dataclass
import fcntl
from functools import wraps
import json
import os
from pathlib import Path
import re
import stat
from uuid import UUID, uuid4

from .systemd_stop import SystemdStopVerifier


DIRECTORY_FLAGS = os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW | os.O_CLOEXEC
FILE_FLAGS = os.O_NOFOLLOW | os.O_NONBLOCK | os.O_CLOEXEC
STATE_LIMIT = 8192


class ProfileError(Exception):
    """Fixed codes only; never expose profile paths, bodies or raw OS errors."""


def _directory(path):
    if (not isinstance(path, Path) or not path.is_absolute() or ".." in path.parts
            or len(str(path)) > 2048):
        raise ProfileError("invalid_directory")
    fd = os.open("/", DIRECTORY_FLAGS)
    try:
        for part in path.parts[1:]:
            following = os.open(part, DIRECTORY_FLAGS, dir_fd=fd)
            os.close(fd)
            fd = following
        return fd
    except BaseException:
        os.close(fd)
        raise


@dataclass(frozen=True)
class DirectoryBinding:
    path: Path
    device: int
    inode: int
    uid: int
    gid: int

    @classmethod
    def capture(cls, path: Path, uid: int, gid: int):
        """Metadata-only administrative admission; not automatic re-enrollment."""
        fd = None
        try:
            fd = _directory(path)
            info = os.fstat(fd)
            value = cls(path, info.st_dev, info.st_ino, uid, gid)
            value.check(fd)
            return value
        except OSError:
            raise ProfileError("directory_unavailable") from None
        finally:
            if fd is not None:
                os.close(fd)

    def check(self, fd):
        if (any(type(number) is not int or number < 0
                for number in (self.device, self.inode, self.uid, self.gid)) or self.inode == 0):
            raise ProfileError("invalid_directory_identity")
        info = os.fstat(fd)
        if (not stat.S_ISDIR(info.st_mode) or stat.S_IMODE(info.st_mode) != 0o700
                or (info.st_dev, info.st_ino, info.st_uid, info.st_gid)
                != (self.device, self.inode, self.uid, self.gid)):
            raise ProfileError("directory_identity_changed")

    def record(self):
        return asdict(self) | {"path": str(self.path)}


def _private_file(info, binding, *, empty=False):
    if (not stat.S_ISREG(info.st_mode) or stat.S_IMODE(info.st_mode) != 0o600
            or (info.st_uid, info.st_gid) != (binding.uid, binding.gid)
            or info.st_nlink != 1 or (empty and info.st_size != 0)):
        raise ProfileError("private_control_file_required")


def _unique(pairs):
    result = dict(pairs)
    if len(result) != len(pairs):
        raise ProfileError("invalid_session_journal")
    return result


def _storage_errors(method):
    @wraps(method)
    def checked(*args, **kwargs):
        try:
            return method(*args, **kwargs)
        except OSError:
            raise ProfileError("session_storage_unavailable") from None
    return checked


def _names(fd, maximum):
    result = set()
    with os.scandir(fd) as entries:
        for index, entry in enumerate(entries):
            if index >= maximum:
                raise ProfileError("profile_inventory_limit")
            result.add(entry.name)
    return result


@dataclass(frozen=True, kw_only=True)
class SessionProfileGuard:
    profile: DirectoryBinding
    control: DirectoryBinding
    cgroup_root: Path
    slice_id: str | None = None

    def __post_init__(self):
        profile, control, cgroup_root, slice_id = self.profile, self.control, self.cgroup_root, self.slice_id
        if (not isinstance(profile, DirectoryBinding) or not isinstance(control, DirectoryBinding)
                or profile.uid == 0 or not isinstance(cgroup_root, Path) or not cgroup_root.is_absolute()
                or ".." in cgroup_root.parts
                or profile.path == control.path
                or profile.path in control.path.parents or control.path in profile.path.parents):
            raise ProfileError("invalid_guard_configuration")
        if slice_id is not None:
            try:
                identity = UUID(slice_id)
                if str(identity) != slice_id or identity.version != 4:
                    raise ValueError
            except (ValueError, AttributeError, TypeError):
                raise ProfileError("invalid_guard_configuration") from None

    def _record(self, phase="idle", run_id=None, invocation_id=None):
        return {"schema": 1, "profile": self.profile.record(), "control": self.control.record(),
                "scope": {"cgroup_root": str(self.cgroup_root), "slice_id": self.slice_id},
                "phase": phase, "run_id": run_id, "invocation_id": invocation_id}

    @contextmanager
    def _locked(self, *, initialize=False):
        descriptors, files = [], None
        try:
            for binding in (self.profile, self.control):
                fd = _directory(binding.path)
                descriptors.append(fd)
                binding.check(fd)
            profile_fd, control_fd = descriptors
            flags = os.O_RDWR | FILE_FLAGS | (os.O_CREAT if initialize else 0)
            lock_fd = os.open("session.lock", flags, 0o600, dir_fd=control_fd)
            descriptors.append(lock_fd)
            _private_file(os.fstat(lock_fd), self.control, empty=True)
            try:
                fcntl.flock(lock_fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
            except BlockingIOError:
                raise ProfileError("profile_busy") from None
            files = _SessionFiles(self, profile_fd, control_fd, lock_fd)
            files.check()
            if _names(control_fd, 2) - {"session.lock", "state.json"}:
                raise ProfileError("unexpected_controller_state")
            yield files
        except OSError:
            raise ProfileError("session_storage_unavailable") from None
        finally:
            if files is not None:
                files.closed = True
            # Closing the last descriptor releases flock. An inherited child
            # must never issue LOCK_UN on the parent's shared open description.
            for fd in reversed(descriptors):
                os.close(fd)

    def initialize(self):
        with self._locked(initialize=True) as files:
            if _names(files.control_fd, 2) != {"session.lock"} or _names(files.profile_fd, 1):
                raise ProfileError("fresh_profile_required")
            files.write(self._record())

    @contextmanager
    def reserve(self):
        with self._locked() as files:
            if files.read()["phase"] != "idle":
                raise ProfileError("unfinished_session")
            files.inventory()
            record = self._record("reserved", str(uuid4()))
            files.write(record)
            yield ProfileLease(files, record)

    async def recover(self, run_id, invocation_id, verifier):
        with self._locked() as files:
            record = files.read()
            if (record["phase"] != "bound" or record["run_id"] != run_id
                    or record["invocation_id"] != invocation_id):
                raise ProfileError("recovery_identity_mismatch")
            await ProfileLease(files, record).finish(verifier)


class _SessionFiles:
    def __init__(self, guard, profile_fd, control_fd, lock_fd):
        self.guard, self.profile_fd, self.control_fd, self.lock_fd = guard, profile_fd, control_fd, lock_fd
        self.pid, self.closed = os.getpid(), False
        self.journal_identity = None

    @_storage_errors
    def check(self):
        if self.closed or self.pid != os.getpid():
            raise ProfileError("inactive_session_lease")
        for binding, held in ((self.guard.profile, self.profile_fd), (self.guard.control, self.control_fd)):
            binding.check(held)
            fd = _directory(binding.path)
            try:
                binding.check(fd)
            finally:
                os.close(fd)
        held = os.fstat(self.lock_fd)
        current = os.stat("session.lock", dir_fd=self.control_fd, follow_symlinks=False)
        _private_file(current, self.guard.control, empty=True)
        if (held.st_dev, held.st_ino) != (current.st_dev, current.st_ino):
            raise ProfileError("session_lock_changed")
        if _names(self.control_fd, 2) - {"session.lock", "state.json"}:
            raise ProfileError("unexpected_controller_state")
        if self.journal_identity is not None:
            info = os.stat("state.json", dir_fd=self.control_fd, follow_symlinks=False)
            _private_file(info, self.guard.control)
            if (info.st_dev, info.st_ino) != self.journal_identity:
                raise ProfileError("session_journal_changed")

    @_storage_errors
    def read(self):
        self.check()
        fd = os.open("state.json", os.O_RDONLY | FILE_FLAGS, dir_fd=self.control_fd)
        try:
            info = os.fstat(fd)
            _private_file(info, self.guard.control)
            current = os.stat("state.json", dir_fd=self.control_fd, follow_symlinks=False)
            if (info.st_dev, info.st_ino) != (current.st_dev, current.st_ino):
                raise ProfileError("session_journal_changed")
            if not 2 <= info.st_size <= STATE_LIMIT:
                raise ProfileError("invalid_session_journal")
            raw = os.read(fd, STATE_LIMIT + 1)
            self.journal_identity = (info.st_dev, info.st_ino)
        finally:
            os.close(fd)
        try:
            value = json.loads(raw, object_pairs_hook=_unique)
            if (not isinstance(value, dict) or type(value.get("schema")) is not int
                    or value != self.guard._record(value.get("phase"), value.get("run_id"),
                                                   value.get("invocation_id"))):
                raise ValueError
            for key in ("profile", "control"):
                if any(type(value[key][field]) is not int for field in ("device", "inode", "uid", "gid")):
                    raise ValueError
            phase, run_id, invocation = value["phase"], value["run_id"], value["invocation_id"]
            if phase == "idle":
                if run_id is not None or invocation is not None:
                    raise ValueError
            elif phase in ("reserved", "bound"):
                identity = UUID(run_id)
                if str(identity) != run_id or identity.version != 4:
                    raise ValueError
                if (phase == "reserved" and invocation is not None
                        or phase == "bound" and (type(invocation) is not str
                                                  or not re.fullmatch(r"[a-f0-9]{32}", invocation))):
                    raise ValueError
            else:
                raise ValueError
            return value
        except (ValueError, TypeError, AttributeError, UnicodeError, RecursionError):
            raise ProfileError("invalid_session_journal") from None

    @_storage_errors
    def write(self, value):
        self.check()
        raw = json.dumps(value, sort_keys=True, separators=(",", ":"), allow_nan=False).encode("ascii")
        if len(raw) > STATE_LIMIT:
            raise ProfileError("invalid_session_journal")
        name, fd, identity = ".state-" + uuid4().hex, None, None
        try:
            fd = os.open(name, os.O_WRONLY | os.O_CREAT | os.O_EXCL | FILE_FLAGS, 0o600,
                         dir_fd=self.control_fd)
            identity = os.fstat(fd)
            while raw:
                written = os.write(fd, raw)
                if written <= 0:
                    raise OSError("short_write")
                raw = raw[written:]
            os.fsync(fd)
            os.replace(name, "state.json", src_dir_fd=self.control_fd, dst_dir_fd=self.control_fd)
            self.journal_identity = (identity.st_dev, identity.st_ino)
            os.fsync(self.control_fd)
        finally:
            if fd is not None:
                os.close(fd)
            if identity is not None:
                try:
                    current = os.stat(name, dir_fd=self.control_fd, follow_symlinks=False)
                    if (current.st_dev, current.st_ino) == (identity.st_dev, identity.st_ino):
                        os.unlink(name, dir_fd=self.control_fd)
                except FileNotFoundError:
                    pass
        self.check()

    @_storage_errors
    def inventory(self):
        self.check()
        names = _names(self.profile_fd, 128)
        total = 0
        for name in names:
            info = os.stat(name, dir_fd=self.profile_fd, follow_symlinks=False)
            if name.startswith(".tmp"):
                raise ProfileError("profile_writer_orphan")
            if (info.st_uid, info.st_gid) != (self.guard.profile.uid, self.guard.profile.gid):
                raise ProfileError("profile_owner_changed")
            if stat.S_ISDIR(info.st_mode) and stat.S_IMODE(info.st_mode) == 0o700:
                continue
            if not stat.S_ISREG(info.st_mode) or info.st_nlink != 1:
                raise ProfileError("private_profile_state_required")
            if name == "installation_id" and stat.S_IMODE(info.st_mode) == 0o644 and info.st_size == 36:
                fd = os.open(name, os.O_RDONLY | FILE_FLAGS, dir_fd=self.profile_fd)
                try:
                    opened = os.fstat(fd)
                    if (not stat.S_ISREG(opened.st_mode) or stat.S_IMODE(opened.st_mode) != 0o644
                            or opened.st_nlink != 1 or opened.st_size != 36
                            or (opened.st_dev, opened.st_ino, opened.st_uid, opened.st_gid)
                            != (info.st_dev, info.st_ino, info.st_uid, info.st_gid)):
                        raise ValueError
                    identity = os.read(fd, 37).decode("ascii")
                    parsed = UUID(identity)
                    if str(parsed) != identity or parsed.version != 4:
                        raise ValueError
                except (ValueError, UnicodeError):
                    raise ProfileError("invalid_installation_metadata") from None
                finally:
                    os.close(fd)
            elif stat.S_IMODE(info.st_mode) != 0o600:
                raise ProfileError("private_profile_state_required")
            if name == "auth.json" and not 2 <= info.st_size <= 262144:
                raise ProfileError("invalid_authorization_metadata")
            total += info.st_size
            if total > 32 * 1024 * 1024:
                raise ProfileError("profile_inventory_limit")


class ProfileLease:
    def __init__(self, files, record):
        self._files, self._record = files, record
        self._finished, self._stopping = False, False

    @property
    def run_id(self):
        return self._record["run_id"]

    def _check(self):
        if self._finished or self._stopping or self._files.read() != self._record:
            raise ProfileError("inactive_session_lease")

    def bind(self, invocation_id):
        self._check()
        if (self._record["phase"] != "reserved" or type(invocation_id) is not str
                or not re.fullmatch(r"[a-f0-9]{32}", invocation_id)):
            raise ProfileError("invalid_invocation_identity")
        record = self._record | {"phase": "bound", "invocation_id": invocation_id}
        self._files.write(record)
        self._record = record

    def _verifier(self, verifier):
        guard = self._files.guard
        expected = SystemdStopVerifier(self.run_id, self._record["invocation_id"],
                                      cgroup_root=guard.cgroup_root, slice_id=guard.slice_id)
        if (type(verifier) is not SystemdStopVerifier
                or any(getattr(verifier, field, None) != getattr(expected, field)
                       for field in ("unit", "invocation_id", "root", "parent_name", "group_path", "worker_user"))):
            raise ProfileError("stop_identity_mismatch")

    async def finish(self, verifier, *, io_stopped=None):
        self._check()
        if self._record["phase"] != "bound":
            raise ProfileError("unbound_session")
        self._verifier(verifier)
        self._stopping = True
        async def confirm():
            if await verifier.stop_and_confirm() is not True:
                return False
            return io_stopped is None or await io_stopped() is True
        task = asyncio.create_task(confirm())
        cancelled = False
        try:
            while True:
                try:
                    confirmed = await asyncio.shield(task)
                    break
                except asyncio.CancelledError:
                    cancelled = True
                    if task.done():
                        raise
            if cancelled:
                raise asyncio.CancelledError
        except Exception:
            if cancelled:
                raise asyncio.CancelledError from None
            raise ProfileError("stop_unconfirmed") from None
        finally:
            self._stopping = False
        if confirmed is not True:
            raise ProfileError("stop_unconfirmed")
        self._check()
        self._verifier(verifier)
        self._files.inventory()
        self._files.write(self._files.guard._record())
        self._finished = True
