from dataclasses import replace
import hashlib
import json
import os
from types import SimpleNamespace

import pytest

from app.ai import runtime_admission as admission
from app.ai.native_text import restricted_catalog
from test_ai_native_text import POLICY


@pytest.fixture
def image(tmp_path, monkeypatch):
    root = tmp_path.resolve() / "image"
    root.mkdir(mode=0o755)
    files = {"work/client": b"", "bin/env": b"public-env", "bin/sh": b"public-shell",
        "work/ca.pem": b"public-ca", "etc/hosts": b"1.1.1.1 chatgpt.com\n8.8.8.8 auth.openai.com\n",
        "etc/nsswitch.conf": b"passwd: files\ngroup: files\nhosts: files\n",
        "work/text-model.json": json.dumps(restricted_catalog({"models": [{"slug": POLICY.model,
            "default_reasoning_level": "medium"}]}, POLICY.model)).encode()}
    for name in ("work/profile", "work/home", "work/empty", "tmp", "bin", "etc"):
        (root / name).mkdir(mode=0o755, parents=True, exist_ok=True)
    for name, body in files.items():
        (root / name).write_bytes(body)
        (root / name).chmod(0o444)
    binary = tmp_path.resolve() / "client"
    binary.write_bytes(b"public-runtime")
    binary.chmod(0o555)
    entries = tuple(admission.ImageEntry(str(path.relative_to(root)), 0o755) if path.is_dir()
        else admission.ImageEntry(str(path.relative_to(root)), 0o444, path.stat().st_size,
                                 hashlib.sha256(path.read_bytes()).hexdigest())
        for path in sorted(root.rglob("*")))
    info = root.stat()
    result = admission.RuntimeImage(root, info.st_dev, info.st_ino, entries, binary,
        admission.ImageEntry("client", 0o555, binary.stat().st_size, hashlib.sha256(binary.read_bytes()).hexdigest()))
    # Only host ownership/parent admission is substituted. Real file traversal,
    # hashes, modes, layout and configuration validation run on temporary files.
    monkeypatch.setattr(admission, "_parents", lambda path: None)
    monkeypatch.setattr(admission, "_owner", lambda info: None)
    return result


def egress():
    return admission.OfficialEgress(("1.1.1.1",), ("8.8.8.8",), 100, 1000)


def test_complete_pinned_public_image_and_exact_tool_free_catalog(image):
    image.verify(POLICY, egress().hosts)


@pytest.mark.parametrize("change", ["binary", "file", "mode", "extra", "missing", "symlink", "directory"])
def test_replaced_modified_or_unexpected_image_state_is_refused(image, change):
    target = image.root / "work/ca.pem"
    if change == "binary":
        image.binary.chmod(0o755)
        image.binary.write_bytes(b"changed-runtime")
        image.binary.chmod(0o555)
    elif change == "file":
        target.chmod(0o644)
        target.write_bytes(b"wrong--ca")
        target.chmod(0o444)
    elif change == "mode":
        target.chmod(0o666)
    elif change == "extra":
        (image.root / "work/extra").write_bytes(b"unexpected")
    elif change == "missing":
        target.unlink()
    elif change == "symlink":
        target.unlink()
        target.symlink_to(image.binary)
    else:
        (image.root / "work/profile").rmdir()
        (image.root / "work/profile").write_bytes(b"not-directory")
    with pytest.raises((admission.AdmissionError, OSError)):
        image.verify(POLICY, egress().hosts)


def test_pin_does_not_accept_newly_added_image_content_by_omission(image):
    reduced = replace(image, entries=tuple(entry for entry in image.entries if entry.relative != "etc/hosts"))
    with pytest.raises(admission.AdmissionError):
        reduced.verify(POLICY, egress().hosts)


def test_total_public_image_budget_is_bounded_before_hashing(image):
    large = admission.ImageEntry("large", 0o444, 64 * 1024 * 1024 + 1, "a" * 64)
    with pytest.raises(admission.AdmissionError, match="invalid_runtime_image"):
        replace(image, entries=image.entries + (large,))


@pytest.mark.parametrize("field,value", [("mode", 0o777), ("relative", "../escape"),
    ("relative", "/absolute"), ("relative", "a//b"), ("relative", None), ("size", True),
    ("sha256", "A" * 64)])
def test_manifest_rejects_unsafe_or_ambiguous_entries(field, value):
    fields = dict(relative="bin/client", mode=0o555, size=1, sha256="a" * 64)
    fields[field] = value
    with pytest.raises(admission.AdmissionError):
        admission.ImageEntry(**fields)


@pytest.mark.parametrize("addresses", [("127.0.0.1",), ("10.1.1.1",), ("::1",),
    ("169.254.169.254",), ("1.1.1.1", "1.1.1.1"), ()])
def test_official_host_admission_rejects_private_non_ipv4_and_duplicates(addresses):
    with pytest.raises((admission.AdmissionError, ValueError)):
        admission.OfficialEgress(addresses, ("8.8.8.8",), 100, 1000)


def test_official_host_pins_expire_and_never_resolve_or_extend_themselves(monkeypatch):
    value = egress()
    monkeypatch.setattr(admission.time, "time", lambda: 101)
    value.check_time()
    for now in (99, 1000, 1001):
        monkeypatch.setattr(admission.time, "time", lambda: now)
        with pytest.raises(admission.AdmissionError, match="expired"):
            value.check_time()
    with pytest.raises(admission.AdmissionError):
        admission.OfficialEgress(value.chat, value.auth, 100, 1001)


def test_runtime_file_owner_must_be_root():
    admission._owner(SimpleNamespace(st_uid=0, st_gid=0))
    with pytest.raises(admission.AdmissionError, match="root_owned"):
        admission._owner(SimpleNamespace(st_uid=os.getuid() or 1, st_gid=0))


def repin(image, name, content):
    path = image.root / name
    path.chmod(0o644)
    path.write_bytes(content)
    path.chmod(0o444)
    entry = admission.ImageEntry(name, 0o444, len(content), hashlib.sha256(content).hexdigest())
    return replace(image, entries=tuple(entry if old.relative == name else old for old in image.entries))


@pytest.mark.parametrize("name,body", [
    ("etc/hosts", b"1.1.1.1 chatgpt.com\n8.8.8.8 auth.openai.com\n127.0.0.1 localhost\n"),
    ("etc/nsswitch.conf", b"passwd: files\ngroup: files\nhosts: files dns\n"),
    ("work/text-model.json", b'{"models":[{"slug":"sample-model","tool_mode":"code_mode_only"}]}'),
])
def test_even_pinned_configuration_must_enforce_fixed_hosts_and_no_tools(image, name, body):
    image = repin(image, name, body)
    with pytest.raises(admission.AdmissionError):
        image.verify(POLICY, egress().hosts)
