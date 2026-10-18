import hashlib
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import release


class ReleaseTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.staged = self.root / "staged"
        self.staged.mkdir()
        self.public = self.root / "public"
        self.bytes = b"synthetic artifact"
        digest = hashlib.sha256(self.bytes).hexdigest()
        self.value = dict(schema=1, packageName=release.PACKAGE, versionCode=2, versionName="0.1.1", minSdk=31,
                          sizeBytes=len(self.bytes), sha256=digest, apkUrl=f"{release.ORIGIN}/app-updates/{digest}.apk", notes="sample")
        self.write_stage()

    def write_stage(self):
        (self.staged / "latest.json").write_text(json.dumps(self.value))
        (self.staged / f'{self.value["sha256"]}.apk').write_bytes(self.bytes)

    def test_publish_and_identical_retry(self):
        for _ in range(2):
            self.assertEqual(self.value, release.publish(self.staged, self.public))
            self.assertEqual(self.bytes, (self.public / f'{self.value["sha256"]}.apk').read_bytes())
            self.assertEqual(self.value, release.read_manifest(self.public / "latest.json"))

    def test_reject_downgrade_or_mutated_same_version(self):
        release.publish(self.staged, self.public)
        for change in ({"versionCode": 1}, {"notes": "changed"}):
            original = self.value.copy()
            self.value.update(change)
            self.write_stage()
            with self.assertRaises(ValueError):
                release.publish(self.staged, self.public)
            self.assertEqual(original, release.read_manifest(self.public / "latest.json"))
            self.value = original

    def test_reject_corruption_without_publishing(self):
        (self.staged / f'{self.value["sha256"]}.apk').write_bytes(b"tampered")
        with self.assertRaises(ValueError):
            release.publish(self.staged, self.public)
        self.assertFalse((self.public / "latest.json").exists())

    def test_invalid_fields(self):
        for change in ({"versionCode": True}, {"apkUrl": "http://example.test/app.apk"}, {"sha256": "../file"},
                       {"sizeBytes": release.MAX_BYTES + 1}, {"notes": "bad\x00"}, {"extra": "value"}):
            with self.assertRaises(ValueError):
                release.validate(dict(self.value, **change))

    def test_manifest_is_not_replaced_if_artifact_copy_fails(self):
        release.publish(self.staged, self.public)
        old = (self.public / "latest.json").read_bytes()
        self.bytes = b"another artifact"
        digest = hashlib.sha256(self.bytes).hexdigest()
        self.value.update(versionCode=3, sha256=digest, sizeBytes=len(self.bytes), apkUrl=f"{release.ORIGIN}/app-updates/{digest}.apk")
        self.write_stage()
        with patch.object(release.shutil, "copyfileobj", side_effect=OSError("disk full")):
            with self.assertRaises(OSError):
                release.publish(self.staged, self.public)
        self.assertEqual(old, (self.public / "latest.json").read_bytes())
        self.assertEqual([], list(self.public.glob(".release-*")))

    def test_landing_escapes_server_notes(self):
        page = release.landing(dict(self.value, notes="<script>sample</script>"))
        self.assertNotIn("<script>", page)
        self.assertIn("&lt;script&gt;", page)

    def test_prepare_requires_verified_expected_signer(self):
        apk = self.root / "sample.apk"
        apk.write_bytes(self.bytes)
        with patch.object(release.subprocess, "run") as run:
            run.return_value.stdout = "Signer #1 certificate SHA-256 digest: " + "b" * 64 + "\n"
            with self.assertRaises(ValueError):
                release.prepare(apk, self.root / "prepared", Path("aapt"), Path("apksigner"), "a" * 64, "")
        self.assertFalse((self.root / "prepared").exists())


if __name__ == "__main__":
    unittest.main()
