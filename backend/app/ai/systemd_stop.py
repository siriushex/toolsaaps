"""Narrow trusted-host stop verifier, not a public privileged command runner.

The trusted launcher must pin the original InvocationID,
use system.slice and never reuse job UUIDs. Deploy outside the unprivileged HTTP
process; do not grant that process unrestricted systemctl or sudo permissions.
"""
from __future__ import annotations

import asyncio
import re
import subprocess
from pathlib import Path
from uuid import UUID


FIELDS = ("Id", "LoadState", "ActiveState", "SubState", "InvocationID", "User", "KillMode", "ControlGroup")


class SystemdStopVerifier:
    def __init__(self, job_id: str, invocation_id: str, *, cgroup_root: Path = Path("/sys/fs/cgroup")):
        if not isinstance(job_id, str) or str(UUID(job_id)) != job_id:
            raise ValueError("canonical_uuid_required")
        if not isinstance(invocation_id, str) or not re.fullmatch(r"[a-f0-9]{32}", invocation_id):
            raise ValueError("invalid_invocation_id")
        self.unit = "copilot-ai-job-" + UUID(job_id).hex + ".service"
        self.invocation_id = invocation_id
        self.root = cgroup_root
        self.group_path = "/system.slice/" + self.unit

    def _command(self, args):
        try:
            result = subprocess.run(("/usr/bin/systemctl", *args), stdin=subprocess.DEVNULL,
                stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, timeout=5, check=False,
                env={"PATH": "/usr/bin:/bin", "LC_ALL": "C", "SYSTEMD_PAGER": "cat"})
            if result.returncode != 0 or len(result.stdout) > 4096:
                return None
            return result.stdout.decode("ascii")
        except (OSError, UnicodeError, subprocess.TimeoutExpired):
            return None

    def _snapshot(self, *, allow_collected=False):
        raw = self._command(("show", "--no-pager", "--property=" + ",".join(FIELDS), self.unit))
        if raw is None:
            return None
        result = {}
        for line in raw.splitlines():
            key, sep, value = line.partition("=")
            if not sep or key not in FIELDS or key in result:
                return None
            result[key] = value
        if set(result) != set(FIELDS):
            return None
        if allow_collected and result == dict(Id=self.unit, LoadState="not-found",
                ActiveState="inactive", SubState="dead", InvocationID="", User="",
                KillMode="control-group", ControlGroup=""):
            return result
        expected = {"Id": self.unit, "LoadState": "loaded", "InvocationID": self.invocation_id,
                    "User": "copilot-ai", "KillMode": "control-group"}
        if any(result[key] != value for key, value in expected.items()):
            return None
        if result["ControlGroup"] not in ("", self.group_path):
            return None
        return result

    def _empty_cgroup(self):
        try:
            if not (self.root / "cgroup.controllers").is_file():
                return False
            parent = self.root / "system.slice"
            if not parent.is_dir() or parent.is_symlink():
                return False
            group = parent / self.unit
            if group.is_symlink():
                return False
            if not group.exists():
                return True
            events = group / "cgroup.events"
            if events.is_symlink():
                return False
            with events.open("r", encoding="ascii") as source:
                content = source.read(1025)
            if len(content) > 1024:
                return False
            values = {}
            for line in content.splitlines():
                key, value = line.split()
                if key in values:
                    return False
                values[key] = value
            # populated covers descendants, unlike the direct cgroup.procs list.
            return values.get("populated") == "0"
        except (OSError, ValueError, UnicodeError):
            return False

    def _stop_and_confirm(self):
        if self._snapshot() is None:
            return False
        if self._command(("stop", self.unit)) is None:
            return False
        after = self._snapshot(allow_collected=True)
        if after is not None and after["LoadState"] == "not-found":
            # Only accept GC after positively identifying and stopping this
            # invocation; an absent unit on entry is never a stop receipt.
            group = self.root / "system.slice" / self.unit
            return self._empty_cgroup() and not group.exists() and not group.is_symlink()
        return (after is not None
                and (after["ActiveState"], after["SubState"]) in (("inactive", "dead"), ("failed", "failed"))
                and self._empty_cgroup())

    async def stop_and_confirm(self) -> bool:
        return await asyncio.to_thread(self._stop_and_confirm)
