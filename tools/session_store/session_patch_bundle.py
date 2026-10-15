"""Offline source-bound candidate patch; never runs a client or reads a profile."""

import argparse
import difflib
import hashlib
import json
import os
from pathlib import Path
import stat
import subprocess
import sys


UPSTREAM_REF = "042fb41b7c813ac7999105e886b2b7aa715b5081"
PINNED_FILES = {
    "codex-rs/login/src/auth/storage.rs": "2e95a8998687e926675d41b5d941c3c3546a652c03d9680e6c7385e59bf459af",
    "codex-rs/login/Cargo.toml": "0c762d109c77508556e0d61badaba96a9e9a43ddd5dc1273113d2a8684e76593",
}
HERE = Path(__file__).resolve().parent


def verify_source(root: Path) -> None:
    for relative, digest in PINNED_FILES.items():
        path = root / relative
        try:
            fd = os.open(path, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK)
            with os.fdopen(fd, "rb") as source:
                metadata = os.fstat(source.fileno())
                if not stat.S_ISREG(metadata.st_mode) or metadata.st_size > 128 * 1024:
                    raise ValueError("source type or size rejected")
                data = source.read(128 * 1024 + 1)
        except OSError:
            raise ValueError("source unavailable") from None
        if len(data) > 128 * 1024 or hashlib.sha256(data).hexdigest() != digest:
            raise ValueError("source identity rejected")


def render_patch() -> str:
    integration = (HERE / "integration.patch").read_text(encoding="utf-8")
    module = (HERE / "src/lib.rs").read_text(encoding="utf-8")
    addition = difflib.unified_diff(
        [], module.splitlines(keepends=True), fromfile="/dev/null",
        tofile="b/codex-rs/login/src/atomic_session_file.rs",
    )
    return integration + "".join(addition)


def check_patch(root: Path, patch_text: str) -> None:
    if (root / "codex-rs/login/src/atomic_session_file.rs").exists():
        raise ValueError("candidate module already exists")
    environment = {key: value for key, value in os.environ.items() if not key.startswith("GIT_")}
    environment["GIT_CEILING_DIRECTORIES"] = str(root.parent)
    result = subprocess.run(
        ["git", "apply", "--check", "-"], cwd=root, env=environment,
        input=patch_text.encode("utf-8"), stdout=subprocess.PIPE,
        stderr=subprocess.PIPE, timeout=15,
    )
    if result.returncode != 0:
        raise ValueError("patch applicability rejected")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source-root", required=True, type=Path)
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    try:
        root = args.source_root.resolve(strict=True)
        verify_source(root)
        text = render_patch()
        check_patch(root, text)
    except (OSError, ValueError, subprocess.TimeoutExpired):
        print("source-bound patch check failed", file=sys.stderr)
        return 1
    if args.check:
        print(json.dumps({"checked": True, "source_ref": UPSTREAM_REF, "files": 3}))
    else:
        sys.stdout.write(text)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
