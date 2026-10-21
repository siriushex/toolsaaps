"""Read-only, trusted-host admission for the contained text runtime.

Pins are supplied by the operator, never captured automatically or accepted from
an API request. The image contains only public executables/configuration; the
owner profile is checked separately without reading authorization bodies.
"""
from __future__ import annotations

from dataclasses import dataclass
import hashlib
import ipaddress
import json
import os
from pathlib import Path
import re
import stat
import time

from .native_text import TextSessionPolicy, restricted_catalog
from .session_profile import DIRECTORY_FLAGS, FILE_FLAGS, _directory


class AdmissionError(Exception):
    """Fixed codes only, with no environment, file content or native output."""


def _path(path):
    if (not isinstance(path, Path) or not path.is_absolute() or ".." in path.parts
            or not re.fullmatch(r"/[A-Za-z0-9_./-]{1,1023}", str(path))):
        raise AdmissionError("invalid_runtime_path")


def _owner(info):
    if info.st_uid != 0 or info.st_gid != 0:
        raise AdmissionError("root_owned_runtime_required")


def _parents(path):
    _path(path)
    for parent in (path, *path.parents):
        fd = _directory(parent)
        try:
            info = os.fstat(fd)
            _owner(info)
            if stat.S_IMODE(info.st_mode) & 0o022:
                raise AdmissionError("writable_runtime_parent")
        finally:
            os.close(fd)


@dataclass(frozen=True)
class ImageEntry:
    relative: str
    mode: int
    size: int | None = None
    sha256: str | None = None

    def __post_init__(self):
        if type(self.relative) is not str:
            raise AdmissionError("invalid_image_entry")
        path = Path(self.relative)
        if (path.is_absolute() or str(path) != self.relative
                or self.relative == "." or ".." in path.parts
                or not re.fullmatch(r"[A-Za-z0-9_./+-]{1,256}", self.relative)
                or type(self.mode) is not int):
            raise AdmissionError("invalid_image_entry")
        if self.size is None:
            if self.sha256 is not None or self.mode != 0o755:
                raise AdmissionError("invalid_image_directory")
        elif (type(self.size) is not int or not 0 <= self.size <= 512 * 1024 * 1024
                or self.mode not in (0o444, 0o555, 0o644, 0o755)
                or type(self.sha256) is not str or not re.fullmatch(r"[a-f0-9]{64}", self.sha256)):
            raise AdmissionError("invalid_image_file")

    def check(self, parent_fd, name):
        flags = DIRECTORY_FLAGS if self.size is None else os.O_RDONLY | FILE_FLAGS
        fd = os.open(name, flags, dir_fd=parent_fd)
        try:
            before = os.fstat(fd)
            _owner(before)
            if stat.S_IMODE(before.st_mode) != self.mode:
                raise AdmissionError("image_mode_changed")
            if self.size is None:
                return
            if not stat.S_ISREG(before.st_mode) or before.st_nlink != 1 or before.st_size != self.size:
                raise AdmissionError("image_file_changed")
            digest, count = hashlib.sha256(), 0
            while block := os.read(fd, 1_048_576):
                count += len(block)
                if count > self.size:
                    raise AdmissionError("image_size_changed")
                digest.update(block)
            after = os.fstat(fd)
            if (count != self.size or digest.hexdigest() != self.sha256
                    or (before.st_dev, before.st_ino, before.st_mtime_ns, before.st_ctime_ns)
                    != (after.st_dev, after.st_ino, after.st_mtime_ns, after.st_ctime_ns)):
                raise AdmissionError("image_digest_changed")
        finally:
            os.close(fd)


@dataclass(frozen=True)
class RuntimeImage:
    root: Path
    device: int
    inode: int
    entries: tuple[ImageEntry, ...]
    binary: Path
    binary_pin: ImageEntry

    def __post_init__(self):
        _path(self.root)
        _path(self.binary)
        if (type(self.device) is not int or self.device < 0 or type(self.inode) is not int or self.inode < 1
                or type(self.entries) is not tuple or not 1 <= len(self.entries) <= 256
                or any(not isinstance(entry, ImageEntry) for entry in self.entries)
                or len({entry.relative for entry in self.entries}) != len(self.entries)
                or sum(entry.size or 0 for entry in self.entries) > 64 * 1024 * 1024
                or not isinstance(self.binary_pin, ImageEntry)
                or self.binary_pin.relative != self.binary.name or self.binary_pin.size is None
                or not self.binary_pin.mode & 0o111):
            raise AdmissionError("invalid_runtime_image")

    def _verify_tree(self):
        _parents(self.root)
        _parents(self.binary.parent)
        info = self.root.stat()
        if (info.st_dev, info.st_ino, stat.S_IMODE(info.st_mode)) != (self.device, self.inode, 0o755):
            raise AdmissionError("runtime_root_changed")
        binary_fd = _directory(self.binary.parent)
        try:
            self.binary_pin.check(binary_fd, self.binary.name)
        finally:
            os.close(binary_fd)
        expected = {entry.relative: entry for entry in self.entries}
        seen = set()

        def walk(fd, prefix=""):
            with os.scandir(fd) as contents:
                for item in contents:
                    relative = prefix + item.name
                    entry = expected.get(relative)
                    if entry is None:
                        raise AdmissionError("unexpected_image_entry")
                    entry.check(fd, item.name)
                    seen.add(relative)
                    if entry.size is None:
                        child = os.open(item.name, DIRECTORY_FLAGS, dir_fd=fd)
                        try:
                            walk(child, relative + "/")
                        finally:
                            os.close(child)

        root_fd = _directory(self.root)
        try:
            walk(root_fd)
        finally:
            os.close(root_fd)
        if seen != set(expected):
            raise AdmissionError("missing_image_entry")
        return expected

    def verify(self, policy: TextSessionPolicy, hosts: dict[str, tuple[str, ...]]):
        expected = self._verify_tree()
        for name in ("work/client", "work/profile", "work/home", "work/empty", "tmp",
                     "bin/env", "bin/sh", "work/ca.pem", "etc/hosts", "etc/nsswitch.conf", "work/text-model.json"):
            if name not in expected:
                raise AdmissionError("incomplete_runtime_image")
        directories = {"work/profile", "work/home", "work/empty", "tmp"}
        if any((expected[name].size is None) != (name in directories)
               for name in directories | {"work/client", "bin/env", "bin/sh", "work/ca.pem",
                                          "etc/hosts", "etc/nsswitch.conf", "work/text-model.json"}):
            raise AdmissionError("invalid_runtime_layout")
        if any(name.startswith(prefix + "/") for name in expected for prefix in directories):
            raise AdmissionError("nonempty_runtime_mountpoint")
        catalog = json.loads(self._small_file("work/text-model.json", 262144))
        if catalog != restricted_catalog(catalog, policy.model):
            raise AdmissionError("unrestricted_model_catalog")
        # No resolver fallback, aliases, proxies, extra hosts or loopback bypass.
        nss = self._small_file("etc/nsswitch.conf", 4096).decode("ascii").splitlines()
        if nss != ["passwd: files", "group: files", "hosts: files"]:
            raise AdmissionError("runtime_resolver_rejected")
        lines = self._small_file("etc/hosts", 4096).decode("ascii").splitlines()
        actual = set()
        for line in lines:
            fields = line.split()
            if len(fields) != 2 or tuple(fields) in actual:
                raise AdmissionError("runtime_hosts_rejected")
            actual.add(tuple(fields))
        if actual != {(address, host) for host, addresses in hosts.items() for address in addresses}:
            raise AdmissionError("runtime_hosts_rejected")

    def _small_file(self, name, limit):
        with (self.root / name).open("rb") as source:
            value = source.read(limit + 1)
        if len(value) > limit:
            raise AdmissionError("image_config_limit")
        return value


@dataclass(frozen=True)
class OfficialEgress:
    chat: tuple[str, ...]
    auth: tuple[str, ...]
    admitted_at: int
    expires_at: int

    def __post_init__(self):
        if (type(self.admitted_at) is not int or type(self.expires_at) is not int
                or not 0 < self.expires_at - self.admitted_at <= 900):
            raise AdmissionError("invalid_egress_lifetime")
        for values in (self.chat, self.auth):
            if type(values) is not tuple or not 1 <= len(values) <= 4 or len(set(values)) != len(values):
                raise AdmissionError("invalid_official_addresses")
            for value in values:
                address = ipaddress.IPv4Address(value)
                if str(address) != value or not address.is_global:
                    raise AdmissionError("public_ipv4_required")

    @property
    def hosts(self):
        return {"chatgpt.com": self.chat, "auth.openai.com": self.auth}

    @property
    def addresses(self):
        return tuple(sorted(set(self.chat + self.auth)))

    def check_time(self):
        if not self.admitted_at <= time.time() < self.expires_at:
            raise AdmissionError("egress_admission_expired")
