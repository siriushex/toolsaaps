"""Install only the dedicated update include in the pre-existing HTTPS host."""
import argparse
import hashlib
from pathlib import Path
import shutil
import subprocess
from release import atomic_write

SITE = Path("/etc/nginx/sites-available/diai.centv.ru")
SNIPPET = Path("/etc/nginx/snippets/copilot-app-updates.conf")
INCLUDE = "    include /etc/nginx/snippets/copilot-app-updates.conf;\n\n"
ANCHOR = "    location = /api/ai/v1/meal-photo/jobs {"


def patch_site(content):
    if content.count(ANCHOR) != 1 or "copilot-app-updates.conf" in content or "/app-updates/" in content:
        raise ValueError("unexpected host configuration")
    prefix = content.split(ANCHOR)[0]
    if "listen 443 ssl;" not in prefix or "server_name diai.centv.ru;" not in prefix:
        raise ValueError("missing existing HTTPS host")
    return content.replace(ANCHOR, INCLUDE + ANCHOR, 1)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--expected-site-sha256", required=True)
    parser.add_argument("--snippet", type=Path, required=True)
    parser.add_argument("--backup", type=Path, required=True)
    args = parser.parse_args()
    original = SITE.read_bytes()
    if hashlib.sha256(original).hexdigest() != args.expected_site_sha256 or SNIPPET.exists():
        raise ValueError("host changed or update include already exists; inspect before retry")
    updated = patch_site(original.decode("utf-8")).encode("utf-8")
    snippet = args.snippet.read_bytes()
    if any(value in snippet.lower() for value in (b"codex", b"skylink", b"argos")):
        raise ValueError("non-neutral configuration")
    args.backup.mkdir(mode=0o700, parents=True, exist_ok=False)
    shutil.copy2(SITE, args.backup / "diai.centv.ru")
    atomic_write(SNIPPET, snippet)
    try:
        if SITE.read_bytes() != original:
            raise ValueError("host changed during preparation")
        atomic_write(SITE, updated)
        subprocess.run(["nginx", "-t"], check=True, capture_output=True)
        subprocess.run(["systemctl", "reload", "nginx"], check=True, capture_output=True)
        subprocess.run(["systemctl", "is-active", "--quiet", "nginx"], check=True)
    except Exception:
        if SITE.read_bytes() == updated:
            atomic_write(SITE, original)
        if SNIPPET.read_bytes() == snippet:
            SNIPPET.unlink()
        subprocess.run(["nginx", "-t"], check=True, capture_output=True)
        subprocess.run(["systemctl", "reload", "nginx"], check=True, capture_output=True)
        raise
    print("route_installed_nginx_active")


if __name__ == "__main__":
    main()
