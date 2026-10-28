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
        (self.source / "transport.txt").write_text("original transport\n")
        (self.source / "upload.txt").write_text("original upload\n")
        (self.source / "combo.txt").write_text("original combo\n")
        (self.source / ".gitignore").write_text(".gradle-invoked\n.transport-invoked\n.upload-invoked\n.combo-invoked\n.reader-invoked\n")
        wrapper = self.source / "gradlew"
        wrapper.write_text(
            "#!/usr/bin/env bash\nset -eu\n"
            "printf '%s\\n' \"$@\" > .gradle-invoked\n"
            "cp transport.txt .transport-invoked\n"
            "cp upload.txt .upload-invoked\n"
            "cp combo.txt .combo-invoked\n"
            "if [ -f added.txt ]; then cp added.txt .reader-invoked; fi\n"
            "if [ -n \"${AAPS_INTEROP_CLASSPATH:-}\" ]; then\n"
            "  mkdir -p \"$(dirname \"$AAPS_INTEROP_CLASSPATH\")\"\n"
            "  printf '%s\\n' synthetic > \"$AAPS_INTEROP_CLASSPATH\"\nfi\n"
            "if [ \"${FIXTURE_MODE:-}\" = concurrent ]; then\n"
            "  printf '%s\\n' 'other edit' >> receiver.txt\nfi\n"
            "if [ \"${FIXTURE_MODE:-}\" = untracked ]; then\n"
            "  printf '%s\\n' 'other source' > concurrent.txt\nfi\n"
            "if [ \"${FIXTURE_MODE:-}\" = added-edit ]; then\n"
            "  printf '%s\\n' 'other edit' >> added.txt\nfi\n"
            "if [ \"${FIXTURE_MODE:-}\" = transport-edit ]; then\n"
            "  printf '%s\\n' 'other edit' >> transport.txt\nfi\n"
            "if [ \"${FIXTURE_MODE:-}\" = upload-edit ]; then\n"
            "  printf '%s\\n' 'other edit' >> upload.txt\nfi\n"
            "if [ \"${FIXTURE_MODE:-}\" = combo-edit ]; then\n"
            "  printf '%s\\n' 'other edit' >> combo.txt\nfi\n"
            "if [ \"${FIXTURE_MODE:-}\" = reader-edit ]; then\n"
            "  printf '%s\\n' 'other edit' >> added.txt\nfi\n"
            "if [ \"${FIXTURE_MODE:-}\" = failure ]; then exit 9; fi\n"
        )
        wrapper.chmod(0o755)
        self.run_git("add", "receiver.txt", "transport.txt", "upload.txt", "combo.txt", "gradlew", ".gitignore")
        self.run_git("commit", "-qm", "Synthetic initial state")
        tools = self.root / "project" / "tools" / "aaps4_compat"
        tools.mkdir(parents=True)
        self.recipe = tools / RECIPE.name
        shutil.copyfile(RECIPE, self.recipe)
        self.prepare = tools / "prepare.sh"
        shutil.copyfile(RECIPE.with_name("prepare.sh"), self.prepare)
        (self.source / "receiver.txt").write_text("patched\n")
        patch = self.run_git("diff", "--binary", "HEAD", "--").stdout
        patch += ("diff --git a/added.txt b/added.txt\nnew file mode 100644\n"
                  "--- /dev/null\n+++ b/added.txt\n@@ -0,0 +1 @@\n+new test\n")
        (tools / "master-admission.patch").write_text(patch)
        (self.source / "receiver.txt").write_text("original\n")
        (self.source / "transport.txt").write_text("patched transport\n")
        self.transport_patch = tools / "transport-privacy.patch"
        self.transport_patch.write_text(self.run_git("diff", "--binary", "HEAD", "--", "transport.txt").stdout)
        (self.source / "transport.txt").write_text("original transport\n")
        (self.source / "upload.txt").write_text("patched upload\n")
        self.upload_patch = tools / "upload-profile-retry.patch"
        self.upload_patch.write_text(self.run_git("diff", "--binary", "HEAD", "--", "upload.txt").stdout)
        (self.source / "upload.txt").write_text("original upload\n")
        (self.source / "combo.txt").write_text("patched combo\n")
        self.combo_patch = tools / "combo-history-integrity.patch"
        self.combo_patch.write_text(self.run_git("diff", "--binary", "HEAD", "--", "combo.txt").stdout)
        (self.source / "combo.txt").write_text("original combo\n")
        self.reader_patch = tools / "combo-history-source.patch"
        self.reader_patch.write_text(
            "diff --git a/added.txt b/added.txt\n--- a/added.txt\n+++ b/added.txt\n"
            "@@ -1 +1 @@\n-new test\n+new test with reader\n"
        )
        self.control_patch = tools / "combo-history-control.patch"
        self.control_patch.write_text(
            "diff --git a/control.txt b/control.txt\nnew file mode 100644\n"
            "--- /dev/null\n+++ b/control.txt\n@@ -0,0 +1 @@\n+isolated response\n"
        )
        self.query_source = "core/nssdk/src/commonMain/kotlin/app/aaps/core/nssdk/localmodel/clientcontrol/ComboHistoryReadRequest.kt"
        self.query_patch = tools / "combo-history-query.patch"
        self.query_patch.write_text(
            f"diff --git a/{self.query_source} b/{self.query_source}\nnew file mode 100644\n"
            f"--- /dev/null\n+++ b/{self.query_source}\n@@ -0,0 +1 @@\n+synthetic decoder\n"
        )
        self.reply_source = self.query_source.replace("ReadRequest", "ReadReply")
        self.reply_patch = tools / "combo-history-reply.patch"
        self.reply_patch.write_text(
            f"diff --git a/{self.reply_source} b/{self.reply_source}\nnew file mode 100644\n"
            f"--- /dev/null\n+++ b/{self.reply_source}\n@@ -0,0 +1 @@\n+synthetic reply codec\n"
        )
        binaries = self.root / "bin"
        binaries.mkdir()
        self.scratch = self.root / "scratch"
        self.scratch.mkdir()
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
                        TMPDIR=str(self.scratch),
                        PATH=str(binaries) + os.pathsep + os.environ["PATH"],
                        REAL_GIT=self.git, FIXTURE_PIN=PIN)
        self.env.pop("AAPS_INTEROP_CLASSPATH", None)

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
        self.assertEqual([], list(self.scratch.glob("master-admission.*")))
        self.assertTrue((self.source / ".gradle-invoked").exists())
        self.assertFalse((self.source / "added.txt").exists())
        self.assertFalse((self.source / "control.txt").exists())
        self.assertFalse((self.source / self.query_source).exists())
        self.assertFalse((self.source / self.reply_source).exists())
        self.assertEqual("patched transport\n", (self.source / ".transport-invoked").read_text())
        self.assertEqual("patched upload\n", (self.source / ".upload-invoked").read_text())
        self.assertEqual("patched combo\n", (self.source / ".combo-invoked").read_text())
        self.assertEqual("new test with reader\n", (self.source / ".reader-invoked").read_text())
        arguments = (self.source / ".gradle-invoked").read_text().splitlines()
        self.assertIn(":plugins:sync:testAndroidHostTest", arguments)
        self.assertIn(":implementation:testAndroidHostTest", arguments)
        self.assertIn("*.WizardBolusExecutorImplTest", arguments)
        self.assertIn(":database:impl:jvmTest", arguments)
        self.assertIn("*.TargetAdmissionTransactionTest", arguments)
        self.assertIn("*.SmsCommunicatorPluginTest", arguments)
        self.assertIn("*.NSClientV3PluginTest", arguments)
        self.assertIn("*.DataSyncSelectorV3Test", arguments)
        self.assertIn(":core:nssdk:jvmTest", arguments)
        self.assertIn(":pump:combov2:comboctl:testDebugUnitTest", arguments)
        self.assertIn(":pump:combov2:testDebugUnitTest", arguments)

    def test_missing_combo_patch_refuses_before_changing_source(self):
        self.combo_patch.unlink()
        result = self.run_recipe()
        self.assertNotEqual(0, result.returncode)
        self.assertFalse((self.source / ".gradle-invoked").exists())
        self.assertEqual("", self.run_git("diff", "HEAD", "--").stdout)

    def test_missing_reader_patch_refuses_before_changing_source(self):
        self.reader_patch.unlink()
        result = self.run_recipe()
        self.assertNotEqual(0, result.returncode)
        self.assertFalse((self.source / ".gradle-invoked").exists())
        self.assertEqual("", self.run_git("diff", "HEAD", "--").stdout)

    def test_missing_control_patch_refuses_before_changing_source(self):
        self.control_patch.unlink()
        result = self.run_recipe()
        self.assertNotEqual(0, result.returncode)
        self.assertFalse((self.source / ".gradle-invoked").exists())
        self.assertEqual("", self.run_git("diff", "HEAD", "--").stdout)

    def test_missing_query_patch_refuses_before_changing_source(self):
        self.query_patch.unlink()
        result = self.run_recipe()
        self.assertNotEqual(0, result.returncode)
        self.assertFalse((self.source / ".gradle-invoked").exists())
        self.assertEqual("", self.run_git("diff", "HEAD", "--").stdout)

    def test_prepare_missing_query_patch_refuses_without_upstream_changes(self):
        self.query_patch.unlink()
        result = subprocess.run(["bash", str(self.prepare)], env=self.env, capture_output=True, text=True)
        self.assertNotEqual(0, result.returncode)
        self.assertFalse((self.source / ".gradle-invoked").exists())
        self.assertEqual("", self.run_git("status", "--porcelain").stdout)
        self.assertEqual([], list(self.scratch.glob("query-interop.*")))

    def test_missing_reply_patch_refuses_before_changing_source(self):
        self.reply_patch.unlink()
        result = self.run_recipe()
        self.assertNotEqual(0, result.returncode)
        self.assertFalse((self.source / ".gradle-invoked").exists())
        self.assertEqual("", self.run_git("status", "--porcelain").stdout)

    def test_prepare_missing_reply_patch_refuses_without_upstream_changes(self):
        self.reply_patch.unlink()
        result = subprocess.run(["bash", str(self.prepare)], env=self.env, capture_output=True, text=True)
        self.assertNotEqual(0, result.returncode)
        self.assertFalse((self.source / ".gradle-invoked").exists())
        self.assertEqual("", self.run_git("status", "--porcelain").stdout)
        self.assertEqual([], list(self.scratch.glob("query-interop.*")))

    def test_prepare_requires_exact_reply_source(self):
        self.reply_patch.write_text(self.control_patch.read_text())
        result = subprocess.run(["bash", str(self.prepare)], env=self.env, capture_output=True, text=True)
        self.assertNotEqual(0, result.returncode)
        self.assertFalse((self.source / ".gradle-invoked").exists())
        self.assertEqual("", self.run_git("status", "--porcelain").stdout)
        self.assertEqual([], list(self.scratch.glob("query-interop.*")))

    def test_prepare_does_not_adopt_a_patch_without_the_expected_source(self):
        self.query_patch.write_text(self.control_patch.read_text())
        result = subprocess.run(["bash", str(self.prepare)], env=self.env, capture_output=True, text=True)
        self.assertNotEqual(0, result.returncode)
        self.assertFalse((self.source / ".gradle-invoked").exists())
        self.assertEqual("", self.run_git("status", "--porcelain").stdout)
        self.assertEqual([], list(self.scratch.glob("query-interop.*")))

    def test_concurrent_reader_edit_is_not_reversed(self):
        result = self.run_recipe("reader-edit")
        self.assertNotEqual(0, result.returncode)
        self.assertIn("preserving the checkout", result.stderr)
        self.assertEqual("new test with reader\nother edit\n", (self.source / "added.txt").read_text())

    def test_concurrent_combo_edit_is_not_reversed(self):
        result = self.run_recipe("combo-edit")
        self.assertNotEqual(0, result.returncode)
        self.assertIn("preserving the checkout", result.stderr)
        self.assertEqual("patched combo\nother edit\n", (self.source / "combo.txt").read_text())

    def test_missing_transport_patch_refuses_before_changing_source(self):
        self.transport_patch.unlink()
        result = self.run_recipe()
        self.assertNotEqual(0, result.returncode)
        self.assertFalse((self.source / ".gradle-invoked").exists())
        self.assertEqual("", self.run_git("diff", "HEAD", "--").stdout)
        self.assertEqual([], list(self.scratch.glob("master-admission.*")))

    def test_concurrent_transport_edit_is_not_reversed(self):
        result = self.run_recipe("transport-edit")
        self.assertNotEqual(0, result.returncode)
        self.assertIn("preserving the checkout", result.stderr)
        self.assertEqual("patched transport\nother edit\n", (self.source / "transport.txt").read_text())

    def test_missing_upload_patch_refuses_before_changing_source(self):
        self.upload_patch.unlink()
        result = self.run_recipe()
        self.assertNotEqual(0, result.returncode)
        self.assertFalse((self.source / ".gradle-invoked").exists())
        self.assertEqual("", self.run_git("diff", "HEAD", "--").stdout)

    def test_concurrent_upload_edit_is_not_reversed(self):
        result = self.run_recipe("upload-edit")
        self.assertNotEqual(0, result.returncode)
        self.assertIn("preserving the checkout", result.stderr)
        self.assertEqual("patched upload\nother edit\n", (self.source / "upload.txt").read_text())

    def test_prepare_rejects_untracked_sdk_source(self):
        (self.source / "unknown.txt").write_text("owner content\n")
        result = subprocess.run(["bash", str(self.prepare)], env=self.env,
                                text=True, capture_output=True, timeout=30)
        self.assertNotEqual(0, result.returncode)
        self.assertFalse((self.source / ".gradle-invoked").exists())
        self.assertEqual("owner content\n", (self.source / "unknown.txt").read_text())

    def test_prepare_rejects_concurrent_source_without_overwriting_it(self):
        result = subprocess.run(["bash", str(self.prepare)],
                                env=dict(self.env, FIXTURE_MODE="untracked"),
                                text=True, capture_output=True, timeout=30)
        self.assertNotEqual(0, result.returncode)
        self.assertEqual("other source\n", (self.source / "concurrent.txt").read_text())

    def test_clean_prepare_exports_classpath(self):
        result = subprocess.run(["bash", str(self.prepare)], env=self.env,
                                text=True, capture_output=True, timeout=30)
        self.assertEqual(0, result.returncode, result.stderr)
        exported = self.root / "project/android-app/build/aaps4-interop/classpath.txt"
        self.assertEqual("synthetic\n", exported.read_text())
        self.assertEqual("", self.run_git("diff", "HEAD", "--").stdout)
        self.assertIn("-Pkotlin.incremental=false", (self.source / ".gradle-invoked").read_text().splitlines())
        self.assertFalse((self.source / self.query_source).exists())
        self.assertFalse((self.source / self.reply_source).exists())
        self.assertEqual([], list(self.scratch.glob("query-interop.*")))

    def test_ci_prepares_stock_sdk_after_reversing_temporary_patches(self):
        workflow = (RECIPE.parents[2] / ".github/workflows/verify.yml").read_text()
        patch_step = "        run: bash tools/aaps4_compat/verify_master.sh\n"
        prepare_step = "        run: bash tools/aaps4_compat/prepare.sh\n"
        test_step = "        run: ./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug --no-daemon --max-workers=2 --console=plain\n"
        for step in (patch_step, prepare_step, test_step):
            self.assertEqual(1, workflow.count(step))
        self.assertLess(workflow.index(patch_step), workflow.index(prepare_step))
        self.assertLess(workflow.index(prepare_step), workflow.index(test_step))

    def test_failed_gradle_restores_source_and_preserves_exit_code(self):
        result = self.run_recipe("failure")
        self.assertEqual(9, result.returncode, result.stderr)
        self.assertEqual("", self.run_git("diff", "HEAD", "--").stdout)
        self.assertFalse((self.source / "added.txt").exists())

    def test_new_concurrent_source_is_preserved_and_fails_cleanup(self):
        result = self.run_recipe("untracked")
        self.assertNotEqual(0, result.returncode)
        self.assertIn("preserving the checkout", result.stderr)
        self.assertEqual("other source\n", (self.source / "concurrent.txt").read_text())
        self.assertEqual("patched\n", (self.source / "receiver.txt").read_text())

    def test_edit_to_added_patch_file_is_not_overwritten(self):
        result = self.run_recipe("added-edit")
        self.assertNotEqual(0, result.returncode)
        self.assertIn("preserving the checkout", result.stderr)
        self.assertEqual("new test with reader\nother edit\n", (self.source / "added.txt").read_text())

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
