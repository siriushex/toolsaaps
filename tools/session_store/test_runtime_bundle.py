import hashlib
import io
import json
import os
from pathlib import Path
import struct
import tempfile
import unittest
from unittest.mock import patch
import warnings
import zipfile

from test_native_build import load_helper


def sample_elf():
    header = bytearray(256)
    header[:16] = b"\x7fELF\x02\x01\x01" + b"\0" * 9
    struct.pack_into("<HHIQQQIHHHHHH", header, 16, 3, 62, 1, 0, 64, 0, 0, 64, 56, 1, 0, 0, 0)
    interpreter = b"/lib64/ld-linux-x86-64.so.2\0"
    struct.pack_into("<IIQQQQQQ", header, 64, 3, 4, 128, 0, 0, len(interpreter), len(interpreter), 1)
    header[128:128 + len(interpreter)] = interpreter
    return bytes(header)


class RuntimeBundleTests(unittest.TestCase):
    def setUp(self):
        try:
            self.module = load_helper("runtime_bundle")
        except FileNotFoundError:
            self.fail("runtime candidate packager missing")
        self.temporary = tempfile.TemporaryDirectory(prefix="runtime-package-")
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name).resolve()

    def fixture(self):
        source, cargo = self.root / "source", self.root / "cargo"
        package = cargo / "registry/src/sample/meal-parser-1.0.0"
        package.mkdir(parents=True)
        (package / "LICENSE-MIT").write_text("sample dependency notice")
        source.mkdir()
        (source / "LICENSE").write_text("sample upstream license")
        (source / "NOTICE").write_text("sample upstream notice")
        binary = source / "codex-rs/target/dev-small/codex"
        binary.parent.mkdir(parents=True)
        binary.write_bytes(sample_elf())
        binary.chmod(0o755)
        metadata = {"packages": [{"name": "meal-parser", "version": "1.0.0", "source": "registry+https://example.invalid/index",
                                  "license": "MIT", "license_file": None,
                                  "manifest_path": str(package / "Cargo.toml")}],
                    "resolve": {"nodes": []}}
        receipt = {"schema": 1, "source_ref": self.module.native.bundle.UPSTREAM_REF,
                   "source_commit": self.module.native.UPSTREAM_COMMIT, "profile": "dev-small",
                   "lock_sha256": "c" * 64, "toolchain_sha256": "d" * 64, "patch_sha256": "e" * 64,
                   "files": {key: "f" * 64 for key in self.module.native.CHANGED | {self.module.native.LOCK}},
                   "normalized_lock_sha256": "c" * 64, "workspace_metadata_updates": 149}
        return source, cargo, metadata, receipt

    def build(self, linked_input=False):
        source, cargo, metadata, receipt = self.fixture()
        if linked_input:
            binary = source / "codex-rs/target/dev-small/codex"
            os.link(binary, binary.with_name("compiled-alias"))
        with patch.object(self.module.native, "verify_outputs"), patch.object(self.module, "UPSTREAM_LICENSES", {
                "LICENSE": hashlib.sha256((source / "LICENSE").read_bytes()).hexdigest(),
                "NOTICE": hashlib.sha256((source / "NOTICE").read_bytes()).hexdigest()}):
            result = self.module.build(source, receipt, metadata, cargo, self.root / "candidate", "a" * 40)
        return self.root / "candidate", result

    def test_build_and_verify_exact_candidate(self):
        candidate, receipt = self.build()
        result = self.module.verify_directory(candidate, receipt["manifest_sha256"])
        self.assertEqual(result["binary_sha256"], hashlib.sha256(sample_elf()).hexdigest())
        self.assertEqual(result["dependencies"], 1)
        self.assertFalse((candidate / "auth.json").exists())

    def test_cargo_two_link_input_is_copied_to_single_link_candidate(self):
        candidate, receipt = self.build(linked_input=True)
        self.assertEqual(receipt["build_input_links"], 2)
        self.assertEqual((candidate / "bin/client").stat().st_nlink, 1)
        self.module.verify_directory(candidate, receipt["manifest_sha256"])

    def test_notice_hardlinks_still_refused(self):
        source, cargo, metadata, _ = self.fixture()
        notice = next((cargo / "registry/src").glob("*/*/LICENSE-MIT"))
        os.link(notice, self.root / "notice-alias")
        with self.assertRaises(ValueError):
            self.module.inventory(source, cargo, metadata, lambda *args: None)

    def test_elf_requires_linux_x64_dynamic_interpreter(self):
        self.module.elf_info(io.BytesIO(sample_elf()))
        for offset, value in ((0, 0), (4, 1), (5, 2), (18, 183), (128, 0)):
            data = bytearray(sample_elf())
            data[offset] = value
            with self.subTest(offset=offset), self.assertRaises(ValueError):
                self.module.elf_info(io.BytesIO(data))
        with self.assertRaises(ValueError):
            self.module.elf_info(io.BytesIO(b"short"))

    def test_no_unknown_files_links_or_changed_content(self):
        candidate, receipt = self.build()
        extra = candidate / "auth.json"
        extra.write_text("sample")
        with self.assertRaises(ValueError):
            self.module.verify_directory(candidate, receipt["manifest_sha256"])
        extra.unlink()
        binary = candidate / "bin/client"
        binary.chmod(0o600)
        binary.write_bytes(b"changed")
        with self.assertRaises(ValueError):
            self.module.verify_directory(candidate, receipt["manifest_sha256"])
        binary.unlink()
        binary.symlink_to("/usr/bin/true")
        with self.assertRaises(ValueError):
            self.module.verify_directory(candidate, receipt["manifest_sha256"])

    def test_expected_manifest_digest_is_external_not_self_asserted(self):
        candidate, _ = self.build()
        with self.assertRaises(ValueError):
            self.module.verify_directory(candidate, "b" * 64)
        manifest = json.loads((candidate / "manifest.json").read_text())
        self.assertEqual(manifest["project_commit"], "a" * 40)

    def test_zip_verification_never_extracts_and_refuses_traversal_or_duplicates(self):
        candidate, receipt = self.build()
        archive = self.root / "candidate.zip"
        with zipfile.ZipFile(archive, "w") as out:
            for path in sorted(candidate.rglob("*")):
                if path.is_file():
                    out.write(path, path.relative_to(candidate).as_posix())
        self.module.verify_zip(archive, receipt["manifest_sha256"])
        with zipfile.ZipFile(archive, "a") as out:
            out.writestr("../escape", "sample")
        with self.assertRaises(ValueError):
            self.module.verify_zip(archive, receipt["manifest_sha256"])
        self.assertFalse((self.root.parent / "escape").exists())

    def test_notice_paths_cannot_import_private_or_missing_data(self):
        source, cargo, metadata, receipt = self.fixture()
        metadata["packages"][0]["license_file"] = str(self.root / "private")
        (self.root / "private").write_text("sample outside allowed sources")
        with self.assertRaisesRegex(ValueError, "outside public"):
            self.module.inventory(source, cargo, metadata, lambda *args: None)
        metadata["packages"][0]["license_file"] = str(cargo / "registry/src/missing/LICENSE")
        with self.assertRaises(FileNotFoundError):
            self.module.inventory(source, cargo, metadata, lambda *args: None)

    def test_build_refuses_existing_destination_or_unverified_source(self):
        source, cargo, metadata, receipt = self.fixture()
        output = self.root / "candidate"
        output.mkdir()
        with self.assertRaises(ValueError):
            self.module.build(source, receipt, metadata, cargo, output, "a" * 40)
        output.rmdir()
        with patch.object(self.module.native, "verify_outputs", side_effect=ValueError("source rejected")):
            with self.assertRaisesRegex(ValueError, "source rejected"):
                self.module.build(source, receipt, metadata, cargo, output, "a" * 40)
        self.assertFalse(output.exists())

    def archive(self, candidate, name="candidate.zip"):
        path = self.root / name
        with zipfile.ZipFile(path, "w", compression=zipfile.ZIP_DEFLATED) as out:
            for item in sorted(candidate.rglob("*")):
                if item.is_file():
                    out.write(item, item.relative_to(candidate).as_posix())
        return path

    def test_zip_duplicate_symlink_and_absolute_paths_refused(self):
        candidate, receipt = self.build()
        for number, name in enumerate(("bin/client", "/absolute", "x\\y", "link")):
            archive = self.archive(candidate, f"sample-{number}.zip")
            with zipfile.ZipFile(archive, "a") as out, warnings.catch_warnings():
                warnings.simplefilter("ignore", UserWarning)
                if name == "link":
                    info = zipfile.ZipInfo(name)
                    info.external_attr = 0o120777 << 16
                    out.writestr(info, "/outside")
                else:
                    out.writestr(name, "sample")
            with self.subTest(name=name), self.assertRaises(ValueError):
                self.module.verify_zip(archive, receipt["manifest_sha256"])

    def test_directory_hardlink_and_empty_extra_directory_refused(self):
        candidate, receipt = self.build()
        empty = candidate / "unexpected"
        empty.mkdir()
        with self.assertRaises(ValueError):
            self.module.verify_directory(candidate, receipt["manifest_sha256"])
        empty.rmdir()
        os.link(candidate / "bin/client", self.root / "second-link")
        with self.assertRaises(ValueError):
            self.module.verify_directory(candidate, receipt["manifest_sha256"])

    def test_member_count_and_expansion_limits(self):
        candidate, receipt = self.build()
        archive = self.archive(candidate)
        with patch.object(self.module, "MAX_BINARY", 10), self.assertRaises(ValueError):
            self.module.verify_zip(archive, receipt["manifest_sha256"])
        with patch.object(self.module, "MAX_MEMBERS", 1), self.assertRaises(ValueError):
            self.module.verify_zip(archive, receipt["manifest_sha256"])
        with patch.object(self.module, "MAX_NOTICE", 10), self.assertRaises(ValueError):
            self.module.verify_directory(candidate, receipt["manifest_sha256"])

    def test_json_duplicates_unknown_fields_and_budgets(self):
        with self.assertRaises(ValueError):
            self.module.decoded(b'{"schema":1,"schema":1}')
        with self.assertRaises(ValueError):
            self.module.decoded(b'NaN')
        with self.assertRaises(ValueError):
            self.module.decoded(b'{}', 1)
        candidate, receipt = self.build()
        manifest = candidate / "manifest.json"
        content = json.loads(manifest.read_bytes())
        content["unexpected"] = True
        manifest.write_bytes(self.module.encoded(content))
        digest = hashlib.sha256(manifest.read_bytes()).hexdigest()
        with self.assertRaises(ValueError):
            self.module.verify_directory(candidate, digest)

    def test_member_names_reject_control_characters_and_noncanonical_paths(self):
        for name in ("bin/client\0extra", "bin/./client", "bin//client", "bin/client\n", "../x", "x/", ""):
            with self.subTest(name=name), self.assertRaises(ValueError):
                self.module.safe_name(name)

    def test_receipt_cannot_smuggle_unexpected_payload(self):
        source, cargo, metadata, receipt = self.fixture()
        receipt["unexpected"] = "sample"
        with patch.object(self.module.native, "verify_outputs"), self.assertRaisesRegex(ValueError, "closed build receipt"):
            self.module.build(source, receipt, metadata, cargo, self.root / "candidate", "a" * 40)
        self.assertFalse((self.root / "candidate").exists())

    def test_unresolved_metadata_and_escaping_notice_links_refused(self):
        source, cargo, metadata, _ = self.fixture()
        metadata["resolve"] = None
        with self.assertRaises(ValueError):
            self.module.inventory(source, cargo, metadata, lambda *args: None)
        metadata["resolve"] = {}
        notice = next((cargo / "registry/src").glob("*/*/LICENSE-MIT"))
        notice.unlink()
        outside = self.root / "private"
        outside.write_text("sample outside public roots")
        notice.symlink_to(outside)
        with self.assertRaisesRegex(ValueError, "outside public"):
            self.module.inventory(source, cargo, metadata, lambda *args: None)

    def test_public_notice_link_is_copied_as_regular_content(self):
        source, cargo, metadata, _ = self.fixture()
        notice = next((cargo / "registry/src").glob("*/*/LICENSE-MIT"))
        notice.unlink()
        notice.symlink_to(source / "LICENSE")
        copied = []
        index = self.module.inventory(source, cargo, metadata, lambda name,data: copied.append(data))
        self.assertEqual(copied, [b"sample upstream license"])
        self.assertEqual(index[0]["notice_status"], "collected")

    def test_declared_only_notices_are_reported_not_certified(self):
        source, cargo, metadata, _ = self.fixture()
        next((cargo / "registry/src").glob("*/*/LICENSE-MIT")).unlink()
        index = self.module.inventory(source, cargo, metadata, lambda *args: None)
        self.assertEqual(index[0]["notice_status"], "declared-only")
        self.assertEqual(index[0]["notices"], [])


if __name__ == "__main__":
    unittest.main()
