import hashlib
import importlib.util
import copy
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch


HERE = Path(__file__).resolve().parent


def load_helper(name):
    path = HERE / f"{name}.py"
    if not path.is_file():
        raise AssertionError(f"missing build helper: {name}")
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class CheckoutTests(unittest.TestCase):
    def setUp(self):
        self.module = load_helper("native_build")
        self.directory = tempfile.TemporaryDirectory(prefix="native-source-")
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        self.git("init", "-q")
        self.source = self.root / "source.rs"
        self.source.write_bytes(b"synthetic-public-source\n")
        self.git("add", "source.rs")
        self.git("-c", "user.name=Source Test", "-c", "user.email=source@example.invalid",
                 "commit", "-qm", "Synthetic source")
        self.commit = self.git("rev-parse", "HEAD").strip()
        self.git("-c", "user.name=Source Test", "-c", "user.email=source@example.invalid",
                 "tag", "-am", "Synthetic release", "release-test")
        self.ref = self.git("rev-parse", "release-test").strip()

    def git(self, *args):
        result = subprocess.run(["git", "-c", "core.hooksPath=/dev/null", *args],
                                cwd=self.root, check=True, capture_output=True,
                                env={"PATH": os.environ["PATH"], "HOME": str(self.root)})
        return result.stdout.decode()

    def validate(self):
        with patch.object(self.module.bundle, "UPSTREAM_REF", self.ref):
            with patch.object(self.module, "UPSTREAM_COMMIT", self.commit):
                self.module.verify_checkout(self.root)

    def test_exact_tag_and_clean_commit_admitted(self):
        self.validate()

    def test_modified_tracked_file_refused(self):
        self.source.write_bytes(b"changed\n")
        with self.assertRaises(ValueError):
            self.validate()

    def test_untracked_file_refused(self):
        (self.root / "unexpected").touch()
        with self.assertRaises(ValueError):
            self.validate()

    def test_ignored_file_refused_before_build(self):
        (self.root / ".git/info/exclude").write_text("unexpected\n")
        (self.root / "unexpected").touch()
        with self.assertRaises(ValueError):
            self.validate()

    def test_wrong_commit_refused(self):
        self.source.write_bytes(b"new-version\n")
        self.git("add", "source.rs")
        self.git("-c", "user.name=Source Test", "-c", "user.email=source@example.invalid",
                 "commit", "-qm", "Changed source")
        with self.assertRaises(ValueError):
            self.validate()

    def test_commit_instead_of_annotated_tag_refused(self):
        with patch.object(self.module.bundle, "UPSTREAM_REF", self.commit):
            with patch.object(self.module, "UPSTREAM_COMMIT", self.commit):
                with self.assertRaises(ValueError):
                    self.module.verify_checkout(self.root)

    def test_pin_file_content_and_symlink(self):
        digest = hashlib.sha256(self.source.read_bytes()).hexdigest()
        self.module.verify_pinned_file(self.source, digest)
        link = self.root / "link"
        link.symlink_to(self.source)
        with self.assertRaises(ValueError):
            self.module.verify_pinned_file(link, digest)
        with self.assertRaises(ValueError):
            self.module.verify_pinned_file(self.source, "0" * 64)


class OfflineSmokeTests(unittest.TestCase):
    def setUp(self):
        self.module = load_helper("offline_smoke")
        self.good = dict(uid=65534, status="CapEff:\t0\nCapPrm:\t0\nCapBnd:\t0\nCapInh:\t0\nCapAmb:\t0\nNoNewPrivs:\t1\n",
                         interfaces={"lo"}, host_ns="net:[10]", current_ns="net:[11]")

    def test_minimal_nonroot_offline_context_admitted(self):
        self.module.verify_isolation(**self.good)

    def test_root_capabilities_or_privileges_refused(self):
        for change in ({"uid": 0}, {"status": "CapEff:\t1\nNoNewPrivs:\t1\n"},
                       {"status": "CapEff:\t0\nNoNewPrivs:\t0\n"}, {"status": ""}):
            with self.subTest(change=change), self.assertRaises(ValueError):
                self.module.verify_isolation(**(self.good | change))

    def test_host_namespace_or_external_interface_refused(self):
        for change in ({"current_ns": "net:[10]"}, {"interfaces": {"lo", "eth0"}}):
            with self.subTest(change=change), self.assertRaises(ValueError):
                self.module.verify_isolation(**(self.good | change))

    def test_other_capability_sets_must_also_be_empty(self):
        for field in ("CapPrm", "CapBnd", "CapInh", "CapAmb"):
            status = self.good["status"].replace(f"{field}:\t0", f"{field}:\t1")
            with self.subTest(field=field), self.assertRaises(ValueError):
                self.module.verify_isolation(**(self.good | {"status": status}))

    def test_only_exact_version_success_admitted(self):
        self.module.verify_version(0, b"codex-cli 0.153.4\n", b"")
        for result in ((1, b"codex-cli 0.153.4\n", b""), (0, b"wrong\n", b""),
                       (0, b"codex-cli 0.153.4\n", b"warning")):
            with self.subTest(result=result), self.assertRaises(ValueError):
                self.module.verify_version(*result)

    def test_preflight_help_does_not_touch_runtime_state(self):
        result = subprocess.run([sys.executable, str(HERE / "offline_smoke.py"), "--help"],
                                capture_output=True, timeout=5)
        self.assertEqual(result.returncode, 0, result.stderr.decode())
        self.assertIn(b"--preflight", result.stdout)


class LockMetadataTests(unittest.TestCase):
    def setUp(self):
        self.module = load_helper("native_build")
        self.original = {"version": 4, "package": [
            {"name": "local-component", "version": "0.0.0", "dependencies": ["remote-library"]},
            {"name": "remote-library", "version": "1.2.3", "source": "registry+https://example.invalid/index",
             "checksum": "1" * 64},
        ]}
        self.updated = copy.deepcopy(self.original)
        self.updated["package"][0]["version"] = "0.153.4"

    def validate(self):
        self.assertTrue(hasattr(self.module, "verify_lock_metadata"), "metadata-only lock guard missing")
        return self.module.verify_lock_metadata(self.original, self.updated)

    def test_only_local_version_metadata_admitted(self):
        self.assertEqual(self.validate(), 1)

    def test_dependency_version_source_checksum_and_fields_refused(self):
        for field, value in (("version", "1.2.4"), ("source", "git+https://example.invalid/source#" + "2" * 40),
                             ("checksum", "2" * 64), ("dependencies", ["unexpected"])):
            with self.subTest(field=field):
                updated = copy.deepcopy(self.updated)
                updated["package"][1][field] = value
                self.assertTrue(hasattr(self.module, "verify_lock_metadata"), "metadata-only lock guard missing")
                with self.assertRaises(ValueError):
                    self.module.verify_lock_metadata(self.original, updated)

    def test_added_or_removed_package_refused(self):
        self.updated["package"].pop()
        with self.assertRaises(ValueError):
            self.validate()

    def test_wrong_local_version_refused(self):
        self.updated["package"][0]["version"] = "0.154.0"
        with self.assertRaises(ValueError):
            self.validate()

    def test_lock_metadata_changes_refused(self):
        self.updated["version"] = 3
        with self.assertRaises(ValueError):
            self.validate()


class ScratchStateTests(unittest.TestCase):
    def setUp(self):
        self.module = load_helper("offline_smoke")
        self.directory = tempfile.TemporaryDirectory(prefix="version-home-")
        self.addCleanup(self.directory.cleanup)
        self.home = Path(self.directory.name)
        self.aliases = {"helper-a", "helper-b", "helper-c", "helper-d"}
        for name, value in (("STATE_ROOT", "state"), ("ALIAS_PREFIX", "helper-"),
                            ("ALIASES", self.aliases)):
            if hasattr(self.module, name):
                manager = patch.object(self.module, name, value)
                manager.start()
                self.addCleanup(manager.stop)

    def validate(self):
        self.assertTrue(hasattr(self.module, "verify_scratch_state"), "bounded scratch-state guard missing")
        return self.module.verify_scratch_state(self.home)

    def populate(self):
        self.group = self.home / "state/tmp/arg0/helper-ABC123"
        self.group.mkdir(parents=True, mode=0o700)
        self.group.parent.chmod(0o700)
        (self.group / ".lock").touch()
        (self.group / ".lock").chmod(0o600)
        for name in self.aliases:
            (self.group / name).symlink_to("/work/client")

    def test_empty_home_admitted(self):
        self.assertEqual(self.validate(), {"directories": 0, "files": 0, "links": 0})

    def test_only_bounded_native_alias_scratch_admitted(self):
        self.populate()
        self.assertEqual(self.validate(), {"directories": 4, "files": 1, "links": 4})

    def test_unexpected_state_refused(self):
        self.populate()
        (self.home / "state/unexpected-state").write_text("synthetic-state")
        with self.assertRaises(ValueError):
            self.validate()

    def test_nonempty_lock_refused(self):
        self.populate()
        (self.group / ".lock").write_text("synthetic-state")
        with self.assertRaises(ValueError):
            self.validate()

    def test_nonprivate_lock_refused(self):
        self.populate()
        (self.group / ".lock").chmod(0o644)
        with self.assertRaises(ValueError):
            self.validate()

    def test_private_umask_is_inherited_by_native_style_writers(self):
        self.assertTrue(hasattr(self.module, "prepare_private_home"), "private umask setup missing")
        previous = os.umask(0o022)
        self.addCleanup(os.umask, previous)
        home = self.home / "fresh"
        self.module.prepare_private_home(home)
        group = home / "native-directory"
        group.mkdir(mode=0o777)
        lock = group / "lock"
        lock.touch(mode=0o666)
        self.assertEqual(group.stat().st_mode & 0o777, 0o700)
        self.assertEqual(lock.stat().st_mode & 0o777, 0o600)

    def test_external_alias_target_refused(self):
        self.populate()
        path = self.group / "helper-a"
        path.unlink()
        path.symlink_to("/unexpected")
        with self.assertRaises(ValueError):
            self.validate()

    def test_nonprivate_or_multiple_alias_groups_refused(self):
        self.populate()
        self.group.chmod(0o755)
        with self.assertRaises(ValueError):
            self.validate()
        self.group.chmod(0o700)
        (self.group.parent / "helper-DEF456").mkdir(mode=0o700)
        with self.assertRaises(ValueError):
            self.validate()


if __name__ == "__main__":
    unittest.main()
