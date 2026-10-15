"""Check the candidate against exact public sources, never an installed runtime."""

import argparse
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import tomllib
from urllib.request import Request, urlopen

import session_patch_bundle as bundle
from native_build import UPSTREAM_COMMIT


def verify(root: Path) -> dict:
    bundle.verify_source(root)
    patch = bundle.render_patch()
    bundle.check_patch(root, patch)
    with tempfile.TemporaryDirectory(prefix="session-source-") as temporary:
        target = Path(temporary)
        for relative in bundle.PINNED_FILES:
            destination = target / relative
            destination.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(root / relative, destination)
        bundle.verify_source(target)
        before_path = target / "codex-rs/login/Cargo.toml"
        before = tomllib.loads(before_path.read_text())
        result = subprocess.run(
            ["git", "apply", "-"], cwd=target,
            env={"PATH": os.environ["PATH"], "GIT_CEILING_DIRECTORIES": str(target.parent)},
            input=patch.encode(), stdout=subprocess.PIPE,
            stderr=subprocess.PIPE, timeout=15,
        )
        if result.returncode:
            raise ValueError("candidate application failed")
        before["dependencies"]["tempfile"] = before["dev-dependencies"].pop("tempfile")
        if tomllib.loads(before_path.read_text()) != before:
            raise ValueError("unexpected dependency change")
        added = "codex-rs/login/src/atomic_session_file.rs"
        if (target / added).read_bytes() != (bundle.HERE / "src/lib.rs").read_bytes():
            raise ValueError("candidate differs from tested module")
        files = {str(path.relative_to(target)) for path in target.rglob("*") if path.is_file()}
        if files != set(bundle.PINNED_FILES) | {added}:
            raise ValueError("unexpected patch paths")
    return {"checked": True, "applied": True, "files": 3, "source_ref": bundle.UPSTREAM_REF}


def fetch_source(root: Path) -> None:
    for relative in bundle.PINNED_FILES:
        url = f"https://raw.githubusercontent.com/openai/codex/{UPSTREAM_COMMIT}/{relative}"
        request = Request(url)
        with urlopen(request, timeout=20) as response:
            if response.geturl() != url:
                raise ValueError("source redirect refused")
            data = response.read(128 * 1024 + 1)
        if len(data) > 128 * 1024:
            raise ValueError("source too large")
        destination = root / relative
        destination.parent.mkdir(parents=True, exist_ok=True)
        destination.write_bytes(data)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source-root", type=Path)
    args = parser.parse_args()
    if args.source_root:
        print(json.dumps(verify(args.source_root.resolve(strict=True))))
        return
    with tempfile.TemporaryDirectory(prefix="session-input-") as temporary:
        root = Path(temporary)
        fetch_source(root)
        print(json.dumps(verify(root)))


if __name__ == "__main__":
    main()
