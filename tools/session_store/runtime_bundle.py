"""Build/inspect a bounded CI candidate; never install, extract or authorize it."""

import argparse
from contextlib import contextmanager
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import re
import stat
import struct
import zipfile

import native_build as native


HERE = Path(__file__).resolve().parent
UPSTREAM_LICENSES = {
    "LICENSE": "d17f227e4df5da1600391338865ce0f3055211760a36688f816941d58232d8dc",
    "NOTICE": "9d71575ecfd9a843fc1677b0efb08053c6ba9fd686a0de1a6f5382fd3c220915",
}
MAX_BINARY = 512 * 1024 * 1024
MAX_JSON = 2 * 1024 * 1024
MAX_NOTICE = 1024 * 1024
MAX_NOTICES = 32 * 1024 * 1024
MAX_MEMBERS = 5000
MAX_ARCHIVE = MAX_BINARY + MAX_NOTICES + 8 * MAX_JSON
DIGEST = re.compile(r"[0-9a-f]{64}\Z")
FIXED_FILES = {"bin/client", "source.json", "changes/session.patch", "licenses/index.json",
               "licenses/upstream/LICENSE", "licenses/upstream/NOTICE", "licenses/patch/LICENSE"}
NOTICE_PATH = re.compile(r"licenses/dependencies/[0-9a-f]{64}/notice-[0-9]{3}\.txt\Z")
RECEIPT_FIELDS = {"schema", "source_ref", "source_commit", "lock_sha256", "toolchain_sha256",
                  "patch_sha256", "profile", "files", "normalized_lock_sha256", "workspace_metadata_updates"}


def admit_receipt(receipt):
    if not isinstance(receipt, dict) or set(receipt) != RECEIPT_FIELDS:
        raise ValueError("closed build receipt required")
    if (receipt["schema"] != 1 or type(receipt["schema"]) is not int or
            receipt["source_ref"] != native.bundle.UPSTREAM_REF or receipt["source_commit"] != native.UPSTREAM_COMMIT or
            receipt["profile"] != "dev-small" or type(receipt["workspace_metadata_updates"]) is not int or
            receipt["workspace_metadata_updates"] != 149 or not isinstance(receipt["files"], dict) or
            set(receipt["files"]) != native.CHANGED | {native.LOCK}):
        raise ValueError("source receipt identity rejected")
    for value in [receipt[key] for key in ("lock_sha256", "toolchain_sha256", "patch_sha256", "normalized_lock_sha256")] + list(receipt["files"].values()):
        if not isinstance(value, str) or not DIGEST.fullmatch(value):
            raise ValueError("source digest rejected")


def encoded(value):
    return (json.dumps(value, sort_keys=True, ensure_ascii=True, allow_nan=False) + "\n").encode()


def unique_object(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError("duplicate JSON field")
        result[key] = value
    return result


def decoded(data, limit=MAX_JSON):
    if len(data) > limit:
        raise ValueError("JSON size limit")
    try:
        return json.loads(data, object_pairs_hook=unique_object,
                          parse_constant=lambda _: (_ for _ in ()).throw(ValueError("nonfinite JSON")))
    except (UnicodeError, json.JSONDecodeError, RecursionError) as error:
        raise ValueError("invalid JSON") from error


def safe_name(name):
    if (not isinstance(name, str) or not 0 < len(name) <= 240 or "\\" in name
            or any(ord(char) < 32 or ord(char) == 127 for char in name)):
        raise ValueError("invalid member name")
    path = PurePosixPath(name)
    if path.is_absolute() or str(path) != name or any(p in ("", ".", "..") for p in name.split("/")):
        raise ValueError("noncanonical member path")
    return name


def elf_info(stream):
    stream.seek(0, 2)
    size = stream.tell()
    stream.seek(0)
    header = stream.read(64)
    if len(header) != 64 or header[:7] != b"\x7fELF\x02\x01\x01" or header[7] not in (0, 3):
        raise ValueError("Linux ELF64 little-endian required")
    fields = struct.unpack_from("<HHIQQQIHHHHHH", header, 16)
    kind, machine, version, _, table, _, _, header_size, entry_size, count, _, _, _ = fields
    if (kind not in (2, 3) or machine != 62 or version != 1 or header_size != 64 or
            entry_size != 56 or not 1 <= count <= 128 or table < 64 or table + count * 56 > size):
        raise ValueError("ELF program table rejected")
    interpreters = []
    for index in range(count):
        stream.seek(table + index * 56)
        entry = struct.unpack("<IIQQQQQQ", stream.read(56))
        if entry[0] == 3:
            offset, length = entry[2], entry[5]
            if not 1 <= length <= 128 or offset + length > size:
                raise ValueError("ELF interpreter bounds rejected")
            stream.seek(offset)
            interpreters.append(stream.read(length))
    if interpreters != [b"/lib64/ld-linux-x86-64.so.2\0"]:
        raise ValueError("ELF interpreter rejected")
    return {"architecture": "x86_64", "format": "ELF64-LE", "interpreter": "/lib64/ld-linux-x86-64.so.2"}


def consume(stream, limit, destination=None):
    digest, size = hashlib.sha256(), 0
    while block := stream.read(min(1024 * 1024, limit - size + 1)):
        size += len(block)
        if size > limit:
            raise ValueError("file size limit")
        digest.update(block)
        if destination is not None:
            destination.write(block)
    return {"sha256": digest.hexdigest(), "size": size}


@contextmanager
def regular(path):
    fd = os.open(path, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK)
    try:
        info = os.fstat(fd)
        if not stat.S_ISREG(info.st_mode) or info.st_nlink != 1:
            raise ValueError("regular single-link file required")
        with os.fdopen(fd, "rb", closefd=False) as stream:
            yield stream
    finally:
        os.close(fd)


def public_path(path, roots, follow_notice=False):
    path = Path(os.path.abspath(path))
    root = next((root for root in roots if path.is_relative_to(root)), None)
    if root is None:
        raise ValueError("notice outside public source roots")
    if follow_notice:
        return public_path(path.resolve(strict=True), roots)
    current = root
    for part in path.relative_to(root).parts:
        current = current / part
        if current.is_symlink():
            raise ValueError("notice source link refused")
    return path


def notice_name(name):
    return re.match(r"^(LICENSE|LICENCE|COPYING|NOTICE)([._-].*)?$", name, re.IGNORECASE) is not None


def inventory(source, cargo, metadata, put):
    packages = metadata.get("packages") if isinstance(metadata, dict) else None
    if not isinstance(packages, list) or not 1 <= len(packages) <= 2000 or not isinstance(metadata.get("resolve"), dict):
        raise ValueError("resolved Cargo metadata required")
    roots = [source, cargo / "registry/src", cargo / "git/checkouts"]
    result, identities, notice_bytes = [], set(), 0
    for package in packages:
        identity = {key: package.get(key) for key in ("name", "version", "source", "license")}
        for key, value in identity.items():
            if (value is None and key in ("source", "license")):
                continue
            if not isinstance(value, str) or not 0 < len(value) <= 1024 or any(ord(c) < 32 for c in value):
                raise ValueError("invalid dependency identity")
        key = hashlib.sha256(encoded(identity)).hexdigest()
        if key in identities:
            raise ValueError("duplicate dependency identity")
        identities.add(key)
        manifest = public_path(Path(package["manifest_path"]), roots)
        directory = manifest.parent
        notices = set()
        with os.scandir(directory) as entries:
            for count, entry in enumerate(entries, 1):
                if count > 2048:
                    raise ValueError("dependency directory limit")
                if notice_name(entry.name) and (entry.is_file(follow_symlinks=False) or entry.is_symlink()):
                    notices.add(public_path(Path(entry.path), roots, follow_notice=True))
        explicit = package.get("license_file")
        if explicit:
            path = Path(explicit)
            path = path if path.is_absolute() else directory / path
            path = public_path(path, roots, follow_notice=True)
            notices.add(path)
        # Workspace crates inherit the pinned repository license.
        if identity["source"] is None:
            notices.add(source / "LICENSE")
        names = []
        if len(notices) > 32:
            raise ValueError("dependency notice count limit")
        for index, path in enumerate(sorted(notices)):
            with regular(path) as stream:
                data = stream.read(MAX_NOTICE + 1)
            notice_bytes += len(data)
            if len(data) > MAX_NOTICE or notice_bytes > MAX_NOTICES:
                raise ValueError("notice budget exceeded")
            target = f"licenses/dependencies/{key}/notice-{index:03d}.txt"
            put(target, data)
            names.append(target)
        result.append({**identity, "id": key, "notices": names,
                       "notice_status": "collected" if names else "declared-only"})
    return sorted(result, key=lambda entry: entry["id"])


def build(source, receipt, metadata, cargo, output, project_commit):
    if output.exists() or output.is_symlink() or not re.fullmatch(r"[0-9a-f]{40}", project_commit):
        raise ValueError("new destination and exact project commit required")
    admit_receipt(receipt)
    native.verify_outputs(source, receipt)
    source, cargo = source.resolve(strict=True), cargo.resolve(strict=True)
    output.mkdir(mode=0o700)
    files = {}

    def put(name, data):
        safe_name(name)
        if name in files or len(files) >= MAX_MEMBERS - 1:
            raise ValueError("candidate member count/identity rejected")
        target = output / name
        target.parent.mkdir(parents=True, exist_ok=True)
        with target.open("xb") as stream:
            stream.write(data)
        files[name] = {"size": len(data), "sha256": hashlib.sha256(data).hexdigest()}

    for name, digest in UPSTREAM_LICENSES.items():
        with regular(source / name) as stream:
            data = stream.read(MAX_NOTICE + 1)
        if len(data) > MAX_NOTICE or hashlib.sha256(data).hexdigest() != digest:
            raise ValueError("upstream notice pin rejected")
        put(f"licenses/upstream/{name}", data)
    put("licenses/patch/LICENSE", (HERE / "LICENSE").read_bytes())
    put("changes/session.patch", native.bundle.render_patch().encode())
    put("source.json", encoded(receipt))
    index = inventory(source, cargo, metadata, put)
    put("licenses/index.json", encoded(index))
    binary = output / "bin/client"
    binary.parent.mkdir()
    with regular(public_path(source / "codex-rs/target/dev-small/codex", [source])) as stream:
        binary_info = elf_info(stream)
        stream.seek(0)
        with binary.open("xb") as target:
            files["bin/client"] = consume(stream, MAX_BINARY, target)
    binary.chmod(0o755)
    manifest = {"schema": 1, "project_commit": project_commit, "candidate_only": True,
                "real_authorization": False, "deployment": False, "elf": binary_info, "files": files}
    (output / "manifest.json").write_bytes(encoded(manifest))
    digest = hashlib.sha256(encoded(manifest)).hexdigest()
    result = verify_directory(output, digest)
    return {"manifest_sha256": digest, **result}


def verify_members(names, sizes, opener, expected_digest):
    if not isinstance(expected_digest, str) or not DIGEST.fullmatch(expected_digest):
        raise ValueError("external manifest digest required")
    if not 1 <= len(names) <= MAX_MEMBERS or len(set(names)) != len(names):
        raise ValueError("member count/duplicates rejected")
    for name in names:
        safe_name(name)
        limit = MAX_BINARY if name == "bin/client" else MAX_JSON if name in ("manifest.json", "source.json", "licenses/index.json") else MAX_NOTICE
        if not 0 <= sizes[name] <= limit:
            raise ValueError("member size limit")
    if "manifest.json" not in names or sum(sizes.values()) > MAX_ARCHIVE:
        raise ValueError("candidate size/manifest rejected")
    with opener("manifest.json") as stream:
        raw = stream.read(MAX_JSON + 1)
    if hashlib.sha256(raw).hexdigest() != expected_digest:
        raise ValueError("external manifest identity mismatch")
    manifest = decoded(raw)
    fields = {"schema", "project_commit", "candidate_only", "real_authorization", "deployment", "elf", "files"}
    if not isinstance(manifest, dict) or set(manifest) != fields or type(manifest["schema"]) is not int or manifest["schema"] != 1:
        raise ValueError("manifest schema rejected")
    if (manifest["candidate_only"] is not True or manifest["real_authorization"] is not False or
            manifest["deployment"] is not False or not isinstance(manifest["project_commit"], str) or
            not re.fullmatch(r"[0-9a-f]{40}", manifest["project_commit"])):
        raise ValueError("candidate identity rejected")
    files = manifest["files"]
    if not isinstance(files, dict) or set(files) != set(names) - {"manifest.json"} or not FIXED_FILES <= set(files):
        raise ValueError("candidate member set mismatch")
    if any(name not in FIXED_FILES and not NOTICE_PATH.fullmatch(name) for name in files):
        raise ValueError("unknown candidate member")
    if sum(sizes[name] for name in files if NOTICE_PATH.fullmatch(name)) > MAX_NOTICES:
        raise ValueError("notice budget exceeded")
    for name, expected in files.items():
        if (not isinstance(expected, dict) or set(expected) != {"size", "sha256"} or
                type(expected["size"]) is not int or expected["size"] != sizes[name] or
                not isinstance(expected["sha256"], str) or not DIGEST.fullmatch(expected["sha256"])):
            raise ValueError("file manifest rejected")
        with opener(name) as stream:
            actual = consume(stream, sizes[name])
        if actual != expected:
            raise ValueError("candidate content mismatch")
    with opener("bin/client") as stream:
        if elf_info(stream) != manifest["elf"]:
            raise ValueError("binary format mismatch")
    with opener("source.json") as stream:
        source = decoded(stream.read(MAX_JSON + 1))
    admit_receipt(source)
    with opener("licenses/index.json") as stream:
        index = decoded(stream.read(MAX_JSON + 1))
    if not isinstance(index, list) or not 1 <= len(index) <= 2000:
        raise ValueError("notice index rejected")
    notice_paths = []
    identities = set()
    for item in index:
        if (not isinstance(item, dict) or set(item) != {"name", "version", "source", "license", "id", "notices", "notice_status"}
                or not isinstance(item["id"], str) or not DIGEST.fullmatch(item["id"]) or item["id"] in identities
                or not isinstance(item["notices"], list) or len(item["notices"]) > 32
                or item["notice_status"] != ("collected" if item["notices"] else "declared-only")):
            raise ValueError("dependency notice entry rejected")
        identities.add(item["id"])
        identity = {key: item[key] for key in ("name", "version", "source", "license")}
        if hashlib.sha256(encoded(identity)).hexdigest() != item["id"]:
            raise ValueError("dependency identity hash mismatch")
        for name in item["notices"]:
            if not isinstance(name, str) or not NOTICE_PATH.fullmatch(name) or name.split("/")[2] != item["id"]:
                raise ValueError("dependency notice identity mismatch")
            notice_paths.append(name)
    if len(set(notice_paths)) != len(notice_paths) or set(notice_paths) != {name for name in files if NOTICE_PATH.fullmatch(name)}:
        raise ValueError("dependency notice coverage mismatch")
    return {"binary_sha256": files["bin/client"]["sha256"], "project_commit": manifest["project_commit"],
            "files": len(names), "bytes": sum(sizes.values()), "dependencies": len(index),
            "declared_only": sum(not item["notices"] for item in index)}


def verify_directory(root, expected_digest):
    fd = os.open(root, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW)
    sizes, directories = {}, set()

    def walk(parent, prefix="", depth=0):
        if depth > 6:
            raise ValueError("directory depth limit")
        with os.scandir(parent) as entries:
            for entry in entries:
                name = safe_name(prefix + entry.name)
                if len(sizes) + len(directories) >= MAX_MEMBERS * 2:
                    raise ValueError("directory entry limit")
                info = entry.stat(follow_symlinks=False)
                if stat.S_ISDIR(info.st_mode):
                    directories.add(name)
                    child = os.open(entry.name, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW, dir_fd=parent)
                    try:
                        walk(child, name + "/", depth + 1)
                    finally:
                        os.close(child)
                elif stat.S_ISREG(info.st_mode) and info.st_nlink == 1:
                    sizes[name] = info.st_size
                else:
                    raise ValueError("candidate links/special files refused")

    @contextmanager
    def opener(name):
        current = os.dup(fd)
        try:
            parts = name.split("/")
            for part in parts[:-1]:
                child = os.open(part, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW, dir_fd=current)
                os.close(current)
                current = child
            child = os.open(parts[-1], os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK, dir_fd=current)
            with os.fdopen(child, "rb") as stream:
                info = os.fstat(stream.fileno())
                if not stat.S_ISREG(info.st_mode) or info.st_nlink != 1 or info.st_size != sizes[name]:
                    raise ValueError("candidate file identity changed")
                yield stream
        finally:
            os.close(current)
    try:
        walk(fd)
        expected_dirs = {str(parent) for name in sizes for parent in PurePosixPath(name).parents if str(parent) != "."}
        if directories != expected_dirs:
            raise ValueError("unexpected candidate directory")
        return verify_members(list(sizes), sizes, opener, expected_digest)
    finally:
        os.close(fd)


def verify_zip(path, expected_digest):
    with regular(path) as raw:
        raw.seek(0, 2)
        size = raw.tell()
        if size > MAX_ARCHIVE:
            raise ValueError("archive size limit")
        raw.seek(max(0, size - 65557))
        tail = raw.read(65557)
        end = tail.rfind(b"PK\x05\x06")
        if end < 0 or len(tail) - end < 22:
            raise ValueError("archive directory missing")
        _, disk, directory_disk, disk_count, count, directory_size, offset, comment = struct.unpack_from("<4s4H2IH", tail, end)
        if (disk != 0 or directory_disk != 0 or disk_count != count or not 1 <= count <= MAX_MEMBERS or
                directory_size > 4 * MAX_JSON or offset + directory_size > size or len(tail) - end != 22 + comment):
            raise ValueError("archive directory bounds rejected")
        raw.seek(0)
        with zipfile.ZipFile(raw) as archive:
            members, sizes = archive.infolist(), {}
            if len(members) != count:
                raise ValueError("archive count mismatch")
            for member in members:
                name = safe_name(member.filename)
                mode = member.external_attr >> 16
                if (member.orig_filename != name or name in sizes or member.is_dir() or member.flag_bits & 1 or
                        (stat.S_IFMT(mode) not in (0, stat.S_IFREG))):
                    raise ValueError("archive links/duplicates/type rejected")
                sizes[name] = member.file_size
            return verify_members(list(sizes), sizes, archive.open, expected_digest)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    operations = parser.add_subparsers(dest="operation", required=True)
    create = operations.add_parser("create")
    for name in ("source-root", "build-receipt", "metadata", "cargo-home", "output", "receipt"):
        create.add_argument("--" + name, type=Path, required=True)
    create.add_argument("--project-commit", required=True)
    verify = operations.add_parser("verify")
    target = verify.add_mutually_exclusive_group(required=True)
    target.add_argument("--root", type=Path)
    target.add_argument("--zip", type=Path)
    verify.add_argument("--manifest-sha256", required=True)
    args = parser.parse_args()
    if args.operation == "create":
        if args.receipt.exists() or args.receipt.is_symlink() or args.receipt.resolve().is_relative_to(args.output.resolve()):
            raise ValueError("new external receipt required")
        with regular(args.build_receipt) as stream:
            receipt = decoded(stream.read(MAX_JSON + 1))
        with regular(args.metadata) as stream:
            metadata = decoded(stream.read(32 * MAX_JSON + 1), 32 * MAX_JSON)
        result = build(args.source_root, receipt, metadata, args.cargo_home, args.output, args.project_commit)
        with args.receipt.open("x") as stream:
            stream.write(encoded(result).decode())
    else:
        result = verify_zip(args.zip, args.manifest_sha256) if args.zip else verify_directory(args.root, args.manifest_sha256)
    print(json.dumps(result, sort_keys=True))


if __name__ == "__main__":
    main()
