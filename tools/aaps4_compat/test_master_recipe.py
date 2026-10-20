"""Check patch cleanup/failure ownership on local synthetic Git checkouts."""

import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest


RECIPE = Path(__file__).with_name("verify_master.sh")
PIN = "d5e7f5bdbb4aaefa4eaaa89a46fba257ef396fa0"


class MasterRecipeTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory(prefix="master-recipe-")
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.source = self.root / "upstream"
        self.source.mkdir()
        self.git = shutil.which("git")
        self.assertIsNotNone(self.git)
        self.run_git("init", "-q")
        self.run_git("config", "user.name", "Validation Fixture")
        self.run_git("config", "user.email", "validation@example.invalid")
        (self.source / "receiver.txt").write_text("original\n")
        (self.source / ".gitignore").write_text(".gradle-invoked\n")
        wrapper = self.source / "gradlew"
        wrapper.write_text(
            "#!/usr/bin/env bash\nset -eu\n"
            "printf '%s\\n' \"$@\" > .gradle-invoked\n"
            "if [ \"${FIXTURE_MODE:-}\" = concurrent ]; then\n"
            "  printf '%s\\n' 'other edit' >> receiver.txt\nfi\n"
            "if [ \"${FIXTURE_MODE:-}\" = failure ]; then exit 9; fi\n"
        )
        wrapper.chmod(0o755)
        self.run_git("add", "receiver.txt", "gradlew", ".gitignore")
        self.run_git("commit", "-qm", "Synthetic initial state")
        tools = self.root / "project" / "tools" / "aaps4_compat"
        tools.mkdir(parents=True)
        self.recipe = tools / RECIPE.name
        shutil.copyfile(RECIPE, self.recipe)
        (self.source / "receiver.txt").write_text("patched\n")
        patch = self.run_git("diff", "--binary", "HEAD", "--").stdout
        (tools / "master-admission.patch").write_text(patch)
        (self.source / "receiver.txt").write_text("original\n")
        binaries = self.root / "bin"
        binaries.mkdir()
        # Only the pin lookup is synthetic. Diff/apply/cleanup use real Git;
        # independent integration runs validate the actual pinned AAPS checkout.
        shim = binaries / "git"
        shim.write_text(
            "#!/usr/bin/env bash\nset -eu\n"
            "if [ \"${3:-}\" = rev-parse ]; then\n"
            "  printf '%s\\n' \"$FIXTURE_PIN\"\nelse\n"
            "  exec \"$REAL_GIT\" \"$@\"\nfi\n"
        )
        shim.chmod(0o755)
        self.env = dict(os.environ, AAPS_RC1_DIR=str(self.source),
                        JAVA_HOME=os.environ.get("JAVA_HOME", "/unused-java"),
                        PATH=str(binaries) + os.pathsep + os.environ["PATH"],
                        REAL_GIT=self.git, FIXTURE_PIN=PIN)

    def run_git(self, *args):
        return subprocess.run([self.git, "-C", str(self.source), *args],
                              text=True, capture_output=True, check=True)

    def run_recipe(self, mode="", pin=PIN):
        return subprocess.run(["bash", str(self.recipe)],
                              env=dict(self.env, FIXTURE_MODE=mode, FIXTURE_PIN=pin),
                              text=True, capture_output=True, timeout=30)

    def test_success_reverses_only_its_patch(self):
        result = self.run_recipe()
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual("", self.run_git("diff", "HEAD", "--").stdout)
        self.assertTrue((self.source / ".gradle-invoked").exists())
        arguments = (self.source / ".gradle-invoked").read_text().splitlines()
        self.assertIn(":plugins:sync:testAndroidHostTest", arguments)
        self.assertIn(":implementation:testAndroidHostTest", arguments)
        self.assertIn("*.WizardBolusExecutorImplTest", arguments)

    def test_failed_gradle_restores_source_and_preserves_exit_code(self):
        result = self.run_recipe("failure")
        self.assertEqual(9, result.returncode, result.stderr)
        self.assertEqual("", self.run_git("diff", "HEAD", "--").stdout)

    def test_concurrent_edit_is_not_reversed_or_overwritten(self):
        result = self.run_recipe("concurrent")
        self.assertNotEqual(0, result.returncode)
        self.assertIn("preserving the checkout", result.stderr)
        self.assertEqual("patched\nother edit\n", (self.source / "receiver.txt").read_text())

    def test_wrong_pin_refuses_before_application(self):
        result = self.run_recipe(pin="0" * 40)
        self.assertNotEqual(0, result.returncode)
        self.assertFalse((self.source / ".gradle-invoked").exists())
        self.assertEqual("original\n", (self.source / "receiver.txt").read_text())

    def test_existing_tracked_edits_are_preserved(self):
        (self.source / "receiver.txt").write_text("owner edit\n")
        result = self.run_recipe()
        self.assertNotEqual(0, result.returncode)
        self.assertFalse((self.source / ".gradle-invoked").exists())
        self.assertEqual("owner edit\n", (self.source / "receiver.txt").read_text())

    def test_untracked_source_is_not_adopted(self):
        (self.source / "unknown.txt").write_text("owner content\n")
        result = self.run_recipe()
        self.assertNotEqual(0, result.returncode)
        self.assertFalse((self.source / ".gradle-invoked").exists())
        self.assertEqual("owner content\n", (self.source / "unknown.txt").read_text())
