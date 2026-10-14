"""Prepare and verify one clean, pinned upstream checkout; no client execution."""

import argparse
import hashlib
import json
import os
from pathlib import Path
import stat
import subprocess

import session_patch_bundle as bundle


UPSTREAM_COMMIT = "3d2ee51ca2d5db578f328aa75e20aa22c0197c9a"
LOCK = "codex-rs/Cargo.lock"
TOOLCHAIN = "codex-rs/rust-toolchain.toml"
LOCK_SHA256 = "3494b8a78d0f643556a83a9cc184e912bcab9f4c5640288952f4223452ba5dc8"
TOOLCHAIN_SHA256 = "570656042681cfd8795403a455baf9a33035331a07db0645e866bbcea89a3d64"
MODULE = "codex-rs/login/src/atomic_session_file.rs"
CHANGED = set(bundle.PINNED_FILES) | {MODULE}


def git(root: Path, *arguments: str, data: bytes | None = None) -> bytes:
    result = subprocess.run(
        ["git", "-c", "core.hooksPath=/dev/null", *arguments], cwd=root,
        input=data, capture_output=True, timeout=30,
        env={"PATH": os.environ["PATH"], "GIT_CONFIG_NOSYSTEM": "1",
             "GIT_CONFIG_GLOBAL": "/dev/null", "GIT_CEILING_DIRECTORIES": str(root.parent)},
    )
    if result.returncode:
        raise ValueError("source command rejected")
    return result.stdout


def file_digest(path: Path) -> str:
    fd = os.open(path, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK)
    with os.fdopen(fd, "rb") as source:
        metadata = os.fstat(source.fileno())
        if not stat.S_ISREG(metadata.st_mode) or metadata.st_size > 16 * 1024 * 1024:
            raise ValueError("source file type or size rejected")
        return hashlib.file_digest(source, "sha256").hexdigest()


def verify_pinned_file(path: Path, expected: str) -> None:
    try:
        actual = file_digest(path)
    except OSError:
        raise ValueError("source file unavailable") from None
    if actual != expected:
        raise ValueError("source file identity rejected")


def verify_checkout(root: Path) -> None:
    root = root.resolve(strict=True)
    if Path(git(root, "rev-parse", "--show-toplevel").decode().strip()) != root:
        raise ValueError("checkout root rejected")
    if git(root, "cat-file", "-t", bundle.UPSTREAM_REF).strip() != b"tag":
        raise ValueError("annotated tag required")
    if git(root, "rev-parse", f"{bundle.UPSTREAM_REF}^{{commit}}").decode().strip() != UPSTREAM_COMMIT:
        raise ValueError("tag commit rejected")
    if git(root, "rev-parse", "HEAD").decode().strip() != UPSTREAM_COMMIT:
        raise ValueError("checkout commit rejected")
    if git(root, "status", "--porcelain", "--untracked-files=all", "--ignored"):
        raise ValueError("clean checkout required")


def verify_inputs(root: Path) -> None:
    verify_pinned_file(root / LOCK, LOCK_SHA256)
    verify_pinned_file(root / TOOLCHAIN, TOOLCHAIN_SHA256)


def verify_outputs(root: Path, receipt: dict) -> None:
    expected = {
        "schema": 1, "source_ref": bundle.UPSTREAM_REF, "source_commit": UPSTREAM_COMMIT,
        "lock_sha256": LOCK_SHA256, "toolchain_sha256": TOOLCHAIN_SHA256,
        "patch_sha256": hashlib.sha256(bundle.render_patch().encode()).hexdigest(),
        "profile": "dev-small",
    }
    if {key: receipt.get(key) for key in expected} != expected:
        raise ValueError("build receipt identity rejected")
    if git(root, "rev-parse", "HEAD").decode().strip() != UPSTREAM_COMMIT:
        raise ValueError("source commit changed")
    verify_inputs(root)
    tracked = git(root, "diff", "HEAD", "--name-only", "-z").decode().split("\0")
    untracked = git(root, "ls-files", "--others", "--exclude-standard", "-z").decode().split("\0")
    if (set(tracked) | set(untracked)) - {""} != CHANGED:
        raise ValueError("unexpected source changes")
    digests = {relative: file_digest(root / relative) for relative in sorted(CHANGED)}
    if digests != receipt.get("files"):
        raise ValueError("patched source changed")
    if (root / MODULE).read_bytes() != (bundle.HERE / "src/lib.rs").read_bytes():
        raise ValueError("tested helper mismatch")
    git(root, "diff", "--check")


def prepare(root: Path) -> dict:
    verify_checkout(root)
    verify_inputs(root)
    bundle.verify_source(root)
    text = bundle.render_patch()
    bundle.check_patch(root, text)
    git(root, "apply", "-", data=text.encode())
    receipt = {
        "schema": 1, "source_ref": bundle.UPSTREAM_REF, "source_commit": UPSTREAM_COMMIT,
        "lock_sha256": LOCK_SHA256, "toolchain_sha256": TOOLCHAIN_SHA256,
        "patch_sha256": hashlib.sha256(text.encode()).hexdigest(), "profile": "dev-small",
        "files": {relative: file_digest(root / relative) for relative in sorted(CHANGED)},
    }
    verify_outputs(root, receipt)
    return receipt


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("operation", choices=("prepare", "verify"))
    parser.add_argument("--source-root", type=Path, required=True)
    parser.add_argument("--receipt", type=Path, required=True)
    args = parser.parse_args()
    root = args.source_root.resolve(strict=True)
    if args.operation == "prepare":
        if args.receipt.exists() or args.receipt.is_symlink() or args.receipt.resolve().is_relative_to(root):
            raise ValueError("new external receipt required")
        receipt = prepare(root)
        with args.receipt.open("x", encoding="utf-8") as target:
            json.dump(receipt, target, sort_keys=True)
            target.write("\n")
    else:
        receipt = json.loads(args.receipt.read_text(encoding="utf-8"))
        verify_outputs(root, receipt)
    print(json.dumps(receipt, sort_keys=True))


if __name__ == "__main__":
    main()
