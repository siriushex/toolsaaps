"""Version-only check inside a pre-created minimal Linux mount/network namespace."""

import argparse
import hashlib
import json
import os
from pathlib import Path
import resource
import re
import stat
import subprocess


# Exact native filesystem compatibility names, never probe or session labels.
STATE_ROOT = ".codex"
ALIAS_PREFIX = "codex-arg0"
ALIASES = {"apply_patch", "applypatch", "codex-linux-sandbox", "codex-execve-wrapper"}


def verify_isolation(*, uid: int, status: str, interfaces: set[str],
                     host_ns: str, current_ns: str) -> None:
    fields = dict(line.split(":", 1) for line in status.splitlines() if ":" in line)
    if uid != 65534 or fields.get("NoNewPrivs", "").strip() != "1":
        raise ValueError("nonroot no-new-privileges context required")
    for capability in ("CapEff", "CapPrm", "CapBnd", "CapInh", "CapAmb"):
        if capability not in fields or int(fields[capability].strip(), 16) != 0:
            raise ValueError("capabilities not cleared")
    if not host_ns or host_ns == current_ns or interfaces - {"lo"}:
        raise ValueError("private network namespace required")


def verify_version(code: int, output: bytes, error: bytes) -> None:
    if code != 0 or output != b"codex-cli 0.153.4\n" or error:
        raise ValueError("exact quiet version check failed")


def verify_scratch_state(home: Path) -> dict:
    def directory(path: Path, *, private: bool = False) -> None:
        metadata = path.lstat()
        mode = stat.S_IMODE(metadata.st_mode)
        if (not stat.S_ISDIR(metadata.st_mode) or metadata.st_uid != os.geteuid()
                or mode & 0o022 or (private and mode != 0o700)):
            raise ValueError("unexpected scratch directory")

    directory(home, private=True)
    if not list(home.iterdir()):
        return {"directories": 0, "files": 0, "links": 0}
    state = home / STATE_ROOT
    temporary = state / "tmp"
    aliases = temporary / "arg0"
    for parent, child in ((home, state), (state, temporary), (temporary, aliases)):
        if set(parent.iterdir()) != {child}:
            raise ValueError("unexpected profile state")
        directory(child, private=child == aliases)
    groups = list(aliases.iterdir())
    if len(groups) != 1 or not re.fullmatch(re.escape(ALIAS_PREFIX) + r"[A-Za-z0-9]{6}", groups[0].name):
        raise ValueError("unexpected alias group")
    group = groups[0]
    directory(group, private=True)
    if {path.name for path in group.iterdir()} != ALIASES | {".lock"}:
        raise ValueError("unexpected scratch files")
    lock = (group / ".lock").lstat()
    if not stat.S_ISREG(lock.st_mode) or lock.st_size != 0 or lock.st_uid != os.geteuid():
        raise ValueError("unexpected scratch lock")
    for name in ALIASES:
        path = group / name
        metadata = path.lstat()
        if (not stat.S_ISLNK(metadata.st_mode) or metadata.st_uid != os.geteuid()
                or os.readlink(path) != "/work/client"):
            raise ValueError("unexpected alias link")
    return {"directories": 4, "files": 1, "links": len(ALIASES)}


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("host_namespace")
    parser.add_argument("--preflight", action="store_true")
    args = parser.parse_args()
    if (os.getuid(), os.getgid(), os.getegid(), os.getgroups()) != (65534, 65534, 65534, []):
        raise ValueError("real identity or supplementary groups not cleared")
    resource.setrlimit(resource.RLIMIT_CPU, (10, 10))
    resource.setrlimit(resource.RLIMIT_AS, (1024 * 1024 * 1024, 1024 * 1024 * 1024))
    resource.setrlimit(resource.RLIMIT_NPROC, (32, 32))
    interfaces = {line.split(":", 1)[0].strip() for line in Path("/proc/net/dev").read_text().splitlines()
                  if ":" in line}
    verify_isolation(uid=os.geteuid(), status=Path("/proc/self/status").read_text(),
                     interfaces=interfaces, host_ns=args.host_namespace,
                     current_ns=os.readlink("/proc/self/ns/net"))
    if any(Path(path).exists() for path in ("/root", "/home", "/run", "/var")):
        raise ValueError("unexpected host state mount")
    if args.preflight:
        print(json.dumps({"isolation_preflight": True, "client_executed": False}))
        return
    home = Path("/tmp/session")
    home.mkdir(mode=0o700)
    result = subprocess.run(["/work/client", "--version"],
                            env={"HOME": str(home), "TMPDIR": "/tmp", "PATH": "/usr/bin:/bin"},
                            capture_output=True, timeout=12)
    verify_version(result.returncode, result.stdout, result.stderr)
    scratch = verify_scratch_state(home)
    print(json.dumps({"version": "0.153.4", "offline": True, "nonroot": True,
                      "capabilities": 0, "scratch": scratch,
                      "version_output_sha256": hashlib.sha256(result.stdout).hexdigest()}))


if __name__ == "__main__":
    main()
