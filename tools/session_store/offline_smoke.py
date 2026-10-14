"""Version-only check inside a pre-created minimal Linux mount/network namespace."""

import argparse
import hashlib
import json
import os
from pathlib import Path
import resource
import subprocess


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
    if list(home.iterdir()):
        raise ValueError("unexpected profile write")
    print(json.dumps({"version": "0.153.4", "offline": True, "nonroot": True,
                      "capabilities": 0, "profile_entries": 0,
                      "version_output_sha256": hashlib.sha256(result.stdout).hexdigest()}))


if __name__ == "__main__":
    main()
