import hashlib
import importlib.util
from pathlib import Path
import tempfile
import unittest
from unittest.mock import MagicMock, patch

import verify_upstream


HERE = Path(__file__).resolve().parent


class SourcePinTests(unittest.TestCase):
    def setUp(self):
        path = HERE / "session_patch_bundle.py"
        self.assertTrue(path.is_file(), "source-bound patch verifier is missing")
        spec = importlib.util.spec_from_file_location("session_patch_bundle", path)
        self.module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(self.module)
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        self.payload = b"synthetic-public-source\n"
        self.target = self.root / "module.rs"
        self.target.write_bytes(self.payload)
        self.inputs = {"module.rs": hashlib.sha256(self.payload).hexdigest()}

    def test_exact_source_is_admitted(self):
        with patch.object(self.module, "PINNED_FILES", self.inputs):
            self.module.verify_source(self.root)

    def test_changed_source_is_refused(self):
        self.target.write_bytes(b"synthetic-changed-source")
        with patch.object(self.module, "PINNED_FILES", self.inputs):
            with self.assertRaises(ValueError):
                self.module.verify_source(self.root)

    def test_missing_source_is_refused(self):
        self.target.unlink()
        with patch.object(self.module, "PINNED_FILES", self.inputs):
            with self.assertRaises(ValueError):
                self.module.verify_source(self.root)

    def test_symlink_source_is_refused(self):
        self.target.unlink()
        actual = self.root / "synthetic-source"
        actual.write_bytes(self.payload)
        self.target.symlink_to(actual)
        with patch.object(self.module, "PINNED_FILES", self.inputs):
            with self.assertRaises(ValueError):
                self.module.verify_source(self.root)

    def test_oversized_source_is_refused(self):
        self.target.write_bytes(b"x" * (128 * 1024 + 1))
        self.inputs["module.rs"] = hashlib.sha256(self.target.read_bytes()).hexdigest()
        with patch.object(self.module, "PINNED_FILES", self.inputs):
            with self.assertRaises(ValueError):
                self.module.verify_source(self.root)

    def test_rendered_new_module_is_exact_tested_source(self):
        text = self.module.render_patch()
        module = (HERE / "src/lib.rs").read_text()
        marker = "+++ b/codex-rs/login/src/atomic_session_file.rs\n"
        added = text.split(marker, 1)[1].splitlines()[1:]
        reconstructed = "".join(line[1:] + "\n" for line in added if line.startswith("+"))
        self.assertEqual(reconstructed, module)


class PublicFetchTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        self.inputs = {"module.rs": hashlib.sha256(b"synthetic-source").hexdigest()}

    def response(self, request, *, redirect=False, oversized=False):
        self.assertEqual(request.get_method(), "GET")
        self.assertFalse(request.has_header("Authorization"))
        self.assertEqual(request.get_header("Accept"), "application/vnd.github.raw+json")
        self.assertTrue(request.full_url.startswith("https://api.github.com/repos/openai/codex/contents/"))
        self.assertTrue(request.full_url.endswith("?ref=" + verify_upstream.bundle.UPSTREAM_REF))
        manager = MagicMock()
        result = manager.__enter__.return_value
        result.geturl.return_value = "https://example.invalid/changed" if redirect else request.full_url
        result.read.return_value = b"x" * (128 * 1024 + 1) if oversized else b"synthetic-source"
        return manager

    def test_fixed_public_api_fetch_without_authorization(self):
        with patch.object(verify_upstream.bundle, "PINNED_FILES", self.inputs):
            with patch.object(verify_upstream, "urlopen", side_effect=lambda request, timeout: self.response(request)):
                verify_upstream.fetch_source(self.root)
            verify_upstream.bundle.verify_source(self.root)

    def test_redirect_is_refused(self):
        with patch.object(verify_upstream.bundle, "PINNED_FILES", self.inputs):
            with patch.object(verify_upstream, "urlopen", side_effect=lambda request, timeout: self.response(request, redirect=True)):
                with self.assertRaises(ValueError):
                    verify_upstream.fetch_source(self.root)
        self.assertFalse((self.root / "module.rs").exists())

    def test_oversized_response_is_refused(self):
        with patch.object(verify_upstream.bundle, "PINNED_FILES", self.inputs):
            with patch.object(verify_upstream, "urlopen", side_effect=lambda request, timeout: self.response(request, oversized=True)):
                with self.assertRaises(ValueError):
                    verify_upstream.fetch_source(self.root)
        self.assertFalse((self.root / "module.rs").exists())


if __name__ == "__main__":
    unittest.main()
