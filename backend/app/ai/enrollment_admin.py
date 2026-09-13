"""Local trusted administration only. Never serve this module through HTTP.

Use a private service-owned database directory and private output directory.
Enrollment files contain a bearer secret and must be delivered out of band,
never checked in, logged, or placed under a web root. No secrets on stdout.
"""
from __future__ import annotations

import argparse
import json
import os
import stat
import sys
import time
from pathlib import Path
from uuid import UUID

from .client_auth import ClientAuth, ENROLLMENT_MS, MAX_SUBSCRIPTION_DAYS, SUBSCRIPTION_CODE_MS


class _Parser(argparse.ArgumentParser):
    def error(self, message):
        raise ValueError("invalid_arguments")


def _private_directory(path):
    info = path.lstat()
    if not stat.S_ISDIR(info.st_mode) or info.st_uid != os.geteuid() or info.st_mode & 0o077:
        raise ValueError("private_directory_required")


def _database(path):
    _private_directory(path.parent)
    info = path.lstat()
    if (not stat.S_ISREG(info.st_mode) or info.st_uid != os.geteuid()
            or info.st_mode & 0o077 or info.st_nlink != 1):
        raise ValueError("private_database_required")


def _issue(auth, owner, output, duration_days=None):
    _private_directory(output.parent)
    fd = os.open(output, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600)
    identity = os.fstat(fd)
    success = False
    try:
        now = time.time_ns() // 1_000_000
        if duration_days is None:
            code = auth.issue_enrollment(owner_id=owner, now_ms=now)
            result = {"version": 1, "server_url": "https://diai.centv.ru",
                      "code": code, "expires_ms": now + ENROLLMENT_MS}
        else:
            code = auth.issue_subscription(owner_id=owner, duration_days=duration_days, now_ms=now)
            result = {"version": 2, "server_url": "https://diai.centv.ru", "code": code,
                      "expires_ms": now + SUBSCRIPTION_CODE_MS, "duration_days": duration_days}
        payload = json.dumps(result, separators=(",", ":")).encode("ascii")
        remaining = memoryview(payload)
        while remaining:
            count = os.write(fd, remaining)
            if count <= 0:
                raise OSError("write_failed")
            remaining = remaining[count:]
        os.fsync(fd)
        success = True
    finally:
        os.close(fd)
        if not success:
            # Never remove a replacement created by another administrator.
            try:
                current = output.lstat()
                if (current.st_dev, current.st_ino) == (identity.st_dev, identity.st_ino):
                    output.unlink()
            except FileNotFoundError:
                pass


def main(argv=None):
    parser = _Parser(description="Private AI client enrollment administration")
    parser.add_argument("command", choices=("issue", "issue-subscription", "revoke-owner"))
    parser.add_argument("--database", type=Path, required=True)
    parser.add_argument("--owner", required=True)
    parser.add_argument("--output", type=Path)
    parser.add_argument("--days", type=int)
    auth = None
    try:
        args = parser.parse_args(argv)
        if str(UUID(args.owner)) != args.owner:
            raise ValueError("canonical_uuid_required")
        issuing = args.command in ("issue", "issue-subscription")
        if issuing != (args.output is not None):
            raise ValueError("invalid_output_option")
        if (args.command == "issue-subscription") != (args.days is not None):
            raise ValueError("invalid_days_option")
        if args.days is not None and not 1 <= args.days <= MAX_SUBSCRIPTION_DAYS:
            raise ValueError("invalid_subscription_duration")
        _database(args.database)
        auth = ClientAuth(args.database)
        if issuing:
            _issue(auth, args.owner, args.output, args.days)
        else:
            auth.revoke_owner(owner_id=args.owner, now_ms=time.time_ns() // 1_000_000)
        print("completed")
        return 0
    except Exception:
        print("enrollment_admin_failed", file=sys.stderr)
        return 2
    finally:
        if auth is not None:
            auth.engine.dispose()


if __name__ == "__main__":
    raise SystemExit(main())
