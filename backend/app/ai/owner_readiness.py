"""Bounded, frame-free readiness of the two existing owner API dependencies."""
import os
from pathlib import Path
import pwd
import socket
import stat
import struct
import subprocess
import time


ENDPOINTS = (
    ("personal-ai-text.service", Path("/run/personal-ai-text/text.sock")),
    ("personal-ai-image.service", Path("/run/personal-ai-image/image.sock")),
)


def main_pid(unit):
    result = subprocess.run(
        ["/usr/bin/systemctl", "show", unit, "--property=MainPID", "--value"],
        stdin=subprocess.DEVNULL, capture_output=True, timeout=2, check=False,
        env={"PATH": "/usr/bin:/bin", "LC_ALL": "C"},
    )
    if result.returncode != 0 or len(result.stdout) > 32:
        return 0
    try:
        return max(0, int(result.stdout))
    except ValueError:
        return 0


def socket_identity(info):
    return info.st_dev, info.st_ino, info.st_mode, info.st_uid, info.st_gid, info.st_nlink


def valid_listener(info, peer, pid, api_gid):
    return (stat.S_ISSOCK(info.st_mode) and stat.S_IMODE(info.st_mode) == 0o660
            and info.st_nlink == 1 and info.st_uid == 0 and info.st_gid == api_gid
            and pid > 0 and peer == (pid, 0, 0))


def listener_ready(unit, path, api_gid):
    if not hasattr(socket, "SO_PEERCRED"):
        return False
    try:
        pid = main_pid(unit)
        before = path.lstat()
        with socket.socket(socket.AF_UNIX, socket.SOCK_STREAM) as client:
            client.settimeout(0.5)
            client.connect(str(path))
            peer = struct.unpack("3i", client.getsockopt(socket.SOL_SOCKET, socket.SO_PEERCRED, 12))
        # Recheck the path and service PID across connect; never accept a stale socket.
        return (valid_listener(before, peer, pid, api_gid)
                and socket_identity(before) == socket_identity(path.lstat())
                and main_pid(unit) == pid)
    except (OSError, ValueError, struct.error, subprocess.SubprocessError):
        return False


def wait_for_dependencies(api_gid, *, check=listener_ready, clock=time.monotonic, sleep=time.sleep):
    deadline = clock() + 25
    while clock() < deadline:
        ready = all(check(unit, path, api_gid) for unit, path in ENDPOINTS)
        if ready and clock() < deadline:
            return True
        sleep(0.2)
    return False


def main():
    account = pwd.getpwnam("copilot-api")
    if (os.getuid(), os.geteuid(), os.getgid(), os.getegid()) != (
            account.pw_uid, account.pw_uid, account.pw_gid, account.pw_gid):
        raise SystemExit("owner_readiness_identity_rejected")
    if not wait_for_dependencies(account.pw_gid):
        raise SystemExit("owner_dependencies_not_ready")


if __name__ == "__main__":
    main()
