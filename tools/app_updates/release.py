"""Prepare a signed APK release locally; publish verified bytes atomically on the host."""
import argparse
import fcntl
import hashlib
import html
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile

ORIGIN = "https://diai.centv.ru"
PACKAGE = "io.aaps.predictivecopilot"
MAX_BYTES = 200 * 1024 * 1024
FIELDS = {"schema", "packageName", "versionCode", "versionName", "minSdk", "sizeBytes", "sha256", "apkUrl", "notes"}


def file_hash(path):
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def validate(value):
    if not isinstance(value, dict) or set(value) != FIELDS:
        raise ValueError("invalid manifest fields")
    for key in ("schema", "versionCode", "minSdk", "sizeBytes"):
        if type(value[key]) is not int:
            raise ValueError("invalid numeric field")
    if value["schema"] != 1 or value["packageName"] != PACKAGE:
        raise ValueError("invalid package or schema")
    if not 1 <= value["versionCode"] <= 2_100_000_000 or not 31 <= value["minSdk"] <= 100:
        raise ValueError("invalid version")
    if not 1 <= value["sizeBytes"] <= MAX_BYTES:
        raise ValueError("invalid size")
    for key, pattern in (("versionName", r"[A-Za-z0-9][A-Za-z0-9.+_-]{0,39}"), ("sha256", r"[a-f0-9]{64}")):
        if not isinstance(value[key], str) or not re.fullmatch(pattern, value[key]):
            raise ValueError("invalid release identity")
    if value["apkUrl"] != f'{ORIGIN}/app-updates/{value["sha256"]}.apk':
        raise ValueError("invalid download URL")
    notes = value["notes"]
    if not isinstance(notes, str) or len(notes) > 2000 or any((ord(c) < 32 or 127 <= ord(c) <= 159) and c != "\n" for c in notes):
        raise ValueError("invalid notes")
    return value


def read_manifest(path):
    if path.stat().st_size > 16 * 1024:
        raise ValueError("oversized manifest")
    return validate(json.loads(path.read_text(encoding="utf-8")))


def landing(value):
    return f'''<!doctype html><html lang="ru"><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>Copilot: обновление</title><style>
body{{font:16px system-ui;margin:40px auto;padding:0 24px;max-width:560px;color:#20302e;background:#fff}}
h1{{font-size:28px}}a{{display:inline-block;padding:14px 20px;background:#006b5f;color:white;border-radius:6px}}
p{{line-height:1.5}}small{{overflow-wrap:anywhere;color:#526461}}</style>
<h1>Copilot {html.escape(value['versionName'])}</h1>
<p>Сборка {value['versionCode']} · {value['sizeBytes'] / 1024 / 1024:.1f} МБ</p>
<p>{html.escape(value['notes']).replace(chr(10), '<br>')}</p>
<p><a href="{value['apkUrl']}">Скачать APK</a></p>
<p>Откройте файл на телефоне и подтвердите обновление Android. Не удаляйте прежнее приложение.
На время установки оповещения Copilot могут прерваться. AAPS не меняется.</p>
<p>После первой установки: меню → О приложении → Проверить обновление.</p>
<small>SHA-256: {value['sha256']}</small></html>'''


def prepare(apk, output, aapt, apksigner, expected_signer, notes):
    if not re.fullmatch(r"[a-f0-9]{64}", expected_signer):
        raise ValueError("invalid expected signer")
    signature = subprocess.run([str(apksigner), "verify", "--print-certs", str(apk)],
                               check=True, capture_output=True, text=True).stdout
    signers = re.findall(r"^Signer #\d+ certificate SHA-256 digest: ([a-f0-9]+)$", signature, re.M)
    if signers != [expected_signer]:
        raise ValueError("unexpected APK signer")
    badging = subprocess.run([str(aapt), "dump", "badging", str(apk)],
                             check=True, capture_output=True, text=True).stdout
    package = re.search(r"^package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'", badging, re.M)
    sdk = re.search(r"^sdkVersion:'(\d+)'$", badging, re.M)
    if not package or not sdk:
        raise ValueError("missing APK metadata")
    digest = file_hash(apk)
    value = validate(dict(schema=1, packageName=package[1], versionCode=int(package[2]), versionName=package[3],
                          minSdk=int(sdk[1]), sizeBytes=apk.stat().st_size, sha256=digest,
                          apkUrl=f"{ORIGIN}/app-updates/{digest}.apk", notes=notes))
    output.mkdir(mode=0o700, parents=True, exist_ok=False)
    shutil.copyfile(apk, output / f"{digest}.apk")
    (output / "latest.json").write_text(json.dumps(value, ensure_ascii=False) + "\n", encoding="utf-8")
    (output / "index.html").write_text(landing(value), encoding="utf-8")
    return value


def atomic_write(path, content):
    fd, temporary = tempfile.mkstemp(prefix=".release-", dir=path.parent)
    try:
        with os.fdopen(fd, "wb") as stream:
            stream.write(content)
            stream.flush()
            os.fsync(stream.fileno())
        os.chmod(temporary, 0o644)
        os.replace(temporary, path)
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)


def publish(staged, public):
    public.mkdir(mode=0o755, parents=True, exist_ok=True)
    if public.is_symlink():
        raise ValueError("public directory must not be a link")
    with (public / ".publish.lock").open("a") as lock:
        fcntl.flock(lock, fcntl.LOCK_EX)
        return publish_locked(staged, public)


def publish_locked(staged, public):
    value = read_manifest(staged / "latest.json")
    source = staged / f'{value["sha256"]}.apk'
    if source.is_symlink() or source.stat().st_size != value["sizeBytes"] or file_hash(source) != value["sha256"]:
        raise ValueError("APK integrity failure")
    previous = public / "latest.json"
    if previous.exists():
        old = read_manifest(previous)
        if value["versionCode"] < old["versionCode"] or (value["versionCode"] == old["versionCode"] and value != old):
            raise ValueError("version must increase; existing releases are immutable")
    target = public / source.name
    if target.exists():
        if target.is_symlink() or file_hash(target) != value["sha256"]:
            raise ValueError("existing artifact mismatch")
    else:
        fd, temporary = tempfile.mkstemp(prefix=".release-", dir=public)
        try:
            with os.fdopen(fd, "wb") as output, source.open("rb") as input_file:
                shutil.copyfileobj(input_file, output)
                output.flush()
                os.fsync(output.fileno())
            if file_hash(Path(temporary)) != value["sha256"]:
                raise ValueError("copied artifact mismatch")
            os.chmod(temporary, 0o644)
            os.replace(temporary, target)
        finally:
            if os.path.exists(temporary):
                os.unlink(temporary)
    atomic_write(public / "index.html", landing(value).encode("utf-8"))
    # Pointer changes last: clients never see a manifest before its immutable APK.
    atomic_write(previous, (json.dumps(value, ensure_ascii=False) + "\n").encode("utf-8"))
    directory = os.open(public, os.O_RDONLY)
    try:
        os.fsync(directory)
    finally:
        os.close(directory)
    return value


def main():
    parser = argparse.ArgumentParser()
    sub = parser.add_subparsers(dest="command", required=True)
    build = sub.add_parser("prepare")
    for name in ("apk", "output", "aapt", "apksigner"):
        build.add_argument(f"--{name}", type=Path, required=True)
    build.add_argument("--expected-signer", required=True)
    build.add_argument("--notes", default="")
    deploy = sub.add_parser("publish")
    deploy.add_argument("--staged", type=Path, required=True)
    deploy.add_argument("--public", type=Path, required=True)
    args = vars(parser.parse_args())
    command = args.pop("command")
    value = prepare(**args) if command == "prepare" else publish(**args)
    print(json.dumps({key: value[key] for key in ("versionCode", "versionName", "sizeBytes", "sha256")}))


if __name__ == "__main__":
    main()
