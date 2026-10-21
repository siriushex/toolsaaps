"""Exercise the external Android JSON isolation recipe on synthetic Gradle graphs."""

import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
import xml.etree.ElementTree as ET
from zipfile import ZipFile


RECIPE = Path(__file__).with_name("android-platform-json.init.gradle")
PREFIX = "JSON_ISOLATION_EVIDENCE="
RUNTIME = (
    "fullReleaseRuntimeClasspath",
    "fullDebugRuntimeClasspath",
    "fullDebugUnitTestRuntimeClasspath",
    "fullDebugAndroidTestRuntimeClasspath",
)


class AndroidPlatformJsonTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        source = os.environ.get("AAPS_RC1_DIR")
        if not source:
            raise RuntimeError("AAPS_RC1_DIR is required; these checks must not skip")
        cls.wrapper = Path(source).resolve() / "gradlew"
        if not cls.wrapper.is_file() or not RECIPE.is_file():
            raise RuntimeError("upstream wrapper and JSON recipe are required")
        cls.temp = tempfile.TemporaryDirectory(prefix="platform-json-")
        cls.addClassCleanup(cls.temp.cleanup)
        cls.root = Path(cls.temp.name)
        for group, artifact, version in (
            ("org.json", "json", "20090211"),
            ("com.vaadin.external.google", "android-json", "0.0.20131108.vaadin1"),
        ):
            folder = cls.root / "repo" / group.replace(".", "/") / artifact / version
            folder.mkdir(parents=True)
            pom = ET.Element("project")
            for key, value in (
                ("modelVersion", "4.0.0"), ("groupId", group),
                ("artifactId", artifact), ("version", version),
            ):
                ET.SubElement(pom, key).text = value
            ET.ElementTree(pom).write(folder / f"{artifact}-{version}.pom")
            with ZipFile(folder / f"{artifact}-{version}.jar", "w"):
                pass
        (cls.root / "settings.gradle").write_text(
            "rootProject.name = 'platform-check'\ninclude ':app', ':unrelated'\n"
        )
        (cls.root / "build.gradle").write_text("// Synthetic dependency graphs only.\n")
        compile_names = [name.replace("Runtime", "Compile") for name in RUNTIME]
        cls.names = list(RUNTIME) + compile_names + [
            "otherRuntimeClasspath", "aapsclient3ReleaseRuntimeClasspath",
            "fullReleaseAnnotationProcessorClasspath",
        ]
        fixture = """
import groovy.json.JsonOutput
repositories {
    maven {
        url = rootProject.file(providers.gradleProperty('brokenRepository').isPresent()
            ? 'absent' : 'repo').toURI()
    }
}
def names = NAMES
names.each { name ->
    configurations.create(name)
    dependencies.add(name, 'org.json:json:20090211')
    if (name.contains('Test') && !providers.gradleProperty('omitHostJson').isPresent()) {
        dependencies.add(name, 'com.vaadin.external.google:android-json:0.0.20131108.vaadin1')
    }
}
// Android test's library implementation is distinct from the main Socket.IO graph.
names.findAll { it.contains('AndroidTest') }.each { name ->
    configurations.getByName(name).dependencies.removeAll { it.group == 'org.json' }
}
tasks.register('fixtureExclusions') {
    doLast {
        println('FIXTURE_EXCLUSIONS=' + JsonOutput.toJson(names.collectEntries { name ->
            [(name): configurations.getByName(name).excludeRules.collect {
                it.group + ':' + it.module
            }.sort()]
        }))
    }
}
""".replace("NAMES", json.dumps(cls.names))
        for module in ("app", "unrelated"):
            folder = cls.root / module
            folder.mkdir()
            (folder / "build.gradle").write_text(fixture)
        cls.baseline = cls.run_gradle()
        cls.fixed = cls.run_gradle("-PandroidPlatformJsonIsolation=true")

    @classmethod
    def run_gradle(cls, *properties, success=True):
        result = subprocess.run(
            [str(cls.wrapper), "--no-daemon", "--max-workers=2", "--console=plain",
             "--offline", "-I", str(RECIPE), *properties,
             ":app:checkAndroidPlatformJsonIsolation", ":app:fixtureExclusions",
             ":unrelated:fixtureExclusions"],
            cwd=cls.root, text=True, capture_output=True, timeout=120,
        )
        if success and result.returncode:
            raise AssertionError("\n".join((result.stdout + result.stderr).splitlines()[-25:]))
        if not success:
            return result
        lines = result.stdout.splitlines()
        evidence = [json.loads(line[len(PREFIX):]) for line in lines if line.startswith(PREFIX)]
        exclusions = [json.loads(line.split("=", 1)[1]) for line in lines
                      if line.startswith("FIXTURE_EXCLUSIONS=")]
        if len(evidence) != 1 or len(exclusions) != 2:
            raise AssertionError("missing or duplicate dependency evidence")
        return evidence[0], exclusions

    def test_default_does_not_change_dependency_graph(self):
        evidence, exclusions = self.baseline
        self.assertFalse(evidence["fixed"])
        self.assertTrue(all(not rules for module in exclusions for rules in module.values()))

    def test_only_expected_modules_are_removed(self):
        before = self.baseline[0]["configurations"]
        after = self.fixed[0]["configurations"]
        self.assertTrue(self.fixed[0]["fixed"])
        for name in RUNTIME:
            with self.subTest(name=name):
                self.assertEqual(before[name]["platformExpectedSha256"], after[name]["sha256"])
                self.assertEqual(before[name]["modules"] - 1, after[name]["modules"])
        self.assertTrue(after["fullDebugUnitTestRuntimeClasspath"]["androidJson"])
        self.assertFalse(after["fullDebugAndroidTestRuntimeClasspath"]["androidJson"])

    def test_compile_and_runtime_scope_excludes_no_other_consumers(self):
        app, unrelated = self.fixed[1]
        for name, rules in app.items():
            consumer = name.startswith("full") and name.endswith(
                ("CompileClasspath", "RuntimeClasspath")
            )
            expected = ["org.json:json"] if consumer else []
            if "AndroidTest" in name:
                expected.insert(0, "com.vaadin.external.google:android-json")
            self.assertEqual(expected, rules, name)
        self.assertTrue(all(not rules for rules in unrelated.values()))

    def test_false_property_does_not_opt_in(self):
        self.assertEqual(self.baseline, self.run_gradle("-PandroidPlatformJsonIsolation=false"))

    def test_missing_host_json_fails_instead_of_disabling_tests(self):
        result = self.run_gradle(
            "-PandroidPlatformJsonIsolation=true", "-PomitHostJson=true", success=False,
        )
        self.assertNotEqual(0, result.returncode)
        self.assertIn("checkAndroidPlatformJsonIsolation", result.stdout + result.stderr)

    def test_unresolved_dependencies_fail_closed(self):
        result = self.run_gradle(
            "-PandroidPlatformJsonIsolation=true", "-PbrokenRepository=true", success=False,
        )
        self.assertNotEqual(0, result.returncode)
        self.assertIn("checkAndroidPlatformJsonIsolation", result.stdout + result.stderr)


if __name__ == "__main__":
    unittest.main()
