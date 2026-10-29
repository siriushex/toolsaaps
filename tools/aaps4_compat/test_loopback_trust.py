"""Verify the exact scope of the pinned RC1 Android trust resource patch."""

import os
from pathlib import Path
import subprocess
import tempfile
import unittest
import xml.etree.ElementTree as ET


PATCH = Path(__file__).with_name("loopback-trust.patch")
RESOURCE = Path("app/src/main/res/xml/network_security_config.xml")


class LoopbackTrustTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        source = os.environ.get("AAPS_RC1_DIR")
        if not source:
            raise RuntimeError("AAPS_RC1_DIR is required; trust checks must not skip")
        cls.original = (Path(source) / RESOURCE).read_bytes()
        cls.temporary = tempfile.TemporaryDirectory(prefix="loopback-trust-")
        cls.addClassCleanup(cls.temporary.cleanup)
        cls.root = Path(cls.temporary.name)
        target = cls.root / RESOURCE
        target.parent.mkdir(parents=True)
        target.write_bytes(cls.original)
        result = subprocess.run(
            ["git", "apply", "--verbose", str(PATCH)], cwd=cls.root,
            capture_output=True, text=True, timeout=15,
        )
        if result.returncode:
            raise AssertionError(result.stderr)
        cls.patched = target.read_bytes()
        cls.policy = ET.fromstring(cls.patched)

    def test_only_one_resource_is_changed(self):
        touched = {path.relative_to(self.root) for path in self.root.rglob("*") if path.is_file()}
        self.assertEqual({RESOURCE}, touched)
        patch = PATCH.read_text()
        self.assertEqual(1, patch.count("diff --git "))
        self.assertIn(f"diff --git a/{RESOURCE} b/{RESOURCE}\n", patch)

    def test_trust_is_confined_to_literal_loopback_domains(self):
        self.assertEqual("network-security-config", self.policy.tag)
        self.assertEqual({}, self.policy.attrib)
        self.assertEqual(["domain-config"], [child.tag for child in self.policy])
        domain = self.policy.find("domain-config")
        self.assertEqual(
            [("localhost", {"includeSubdomains": "false"}),
             ("127.0.0.1", {"includeSubdomains": "false"})],
            [(element.text, element.attrib) for element in domain.findall("domain")],
        )
        self.assertEqual(["domain", "domain", "trust-anchors"], [child.tag for child in domain])

    def test_only_system_and_user_certificate_stores_are_trusted(self):
        anchors = self.policy.find("domain-config/trust-anchors")
        self.assertIsNotNone(anchors)
        self.assertEqual({}, anchors.attrib)
        self.assertEqual(["certificates", "certificates"], [child.tag for child in anchors])
        self.assertEqual([{"src": "system"}, {"src": "user"}],
                         [child.attrib for child in anchors])
        self.assertTrue(all(len(child) == 0 for child in anchors))

    def test_no_global_debug_or_pinning_override(self):
        self.assertEqual([], self.policy.findall(".//base-config"))
        self.assertEqual([], self.policy.findall(".//debug-overrides"))
        self.assertEqual([], self.policy.findall(".//pin-set"))
        self.assertTrue(all("overridePins" not in element.attrib for element in self.policy.iter()))

    def test_existing_cleartext_and_domain_policy_are_unchanged(self):
        policy = ET.fromstring(self.patched)
        domain = policy.find("domain-config")
        domain.remove(domain.find("trust-anchors"))
        self.assertEqual(ET.canonicalize(self.original, strip_text=True),
                         ET.canonicalize(ET.tostring(policy), strip_text=True))

    def test_patch_reversal_restores_exact_original_resource(self):
        with tempfile.TemporaryDirectory(prefix="loopback-reverse-") as directory:
            root = Path(directory)
            target = root / RESOURCE
            target.parent.mkdir(parents=True)
            target.write_bytes(self.patched)
            result = subprocess.run(["git", "apply", "--reverse", str(PATCH)],
                                    cwd=root, capture_output=True, text=True, timeout=15)
            self.assertEqual(0, result.returncode, result.stderr)
            self.assertEqual(self.original, target.read_bytes())

    def test_unknown_domain_resource_is_rejected_without_overwriting(self):
        with tempfile.TemporaryDirectory(prefix="loopback-conflict-") as directory:
            root = Path(directory)
            target = root / RESOURCE
            target.parent.mkdir(parents=True)
            changed = self.original.replace(b">localhost<", b">owner.example.invalid<")
            self.assertNotEqual(self.original, changed)
            target.write_bytes(changed)
            result = subprocess.run(["git", "apply", str(PATCH)], cwd=root,
                                    capture_output=True, text=True, timeout=15)
            self.assertNotEqual(0, result.returncode)
            self.assertEqual(changed, target.read_bytes())
