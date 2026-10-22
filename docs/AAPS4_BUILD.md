# Pristine RC1 Android Packaging

The separate combined-patch packaging command is now
`bash tools/aaps4_compat/verify_master.sh --package`. It verifies the receiver,
target transaction, log privacy and scoped loopback trust together before
restoring the original source. See AAPS4_LOOPBACK_TRUST.md. Its unsigned output
is not an admission to install or enable native Target Manager.

Target: official AAPS 4.0.0-rc1, commit
`d5e7f5bdbb4aaefa4eaaa89a46fba257ef396fa0`. This is a build check, not admission
to replace the phone's active therapy application. See AAPS4_COMPATIBILITY.md.

## Duplicate JSON Dependency

FullRelease lint rejects `org.json:json:20090211`, inherited through Socket.IO,
because Android already provides those classes. Android instrumented tests also
inherit Vaadin's Android JSON implementation. Removing only the instrumented-test
copy did not fix the main release failure; that failed attempt is not a pass.

`tools/aaps4_compat/android-platform-json.init.gradle` is an opt-in external build
recipe, leaving upstream tracked files untouched. With
`-PandroidPlatformJsonIsolation=true` it removes `org.json:json` only from the
app's `full*CompileClasspath` and `full*RuntimeClasspath` consumers, plus Vaadin
JSON from Android instrumented tests. JVM tests retain the Vaadin implementation.
Other projects, other variants and tooling are unaffected. No lint suppression,
test exclusion, signing configuration or dosing source change is introduced.

The diagnostic task refuses unresolved dependencies and missing host JSON. Run
it once without the property and once with it; each baseline
`platformExpectedSha256` must equal the corresponding fixed `sha256`. Only the
duplicate module is removed from each of the four runtime graphs.

## Local Verification

Use Java 21 and an Android SDK containing the upstream-required platform 37. The
existing JVM SDK CI job does not need that platform; it is not a full AAPS APK CI
build. Substitute absolute paths for the variables below.

```sh
cd "$AAPS_RC1_DIR"
git diff --quiet HEAD --
git rev-parse HEAD
./gradlew --no-daemon --max-workers=2 --console=plain \
  -I "$COPILOT_DIR/tools/aaps4_compat/android-platform-json.init.gradle" \
  :app:checkAndroidPlatformJsonIsolation
./gradlew --no-daemon --max-workers=2 --console=plain \
  -I "$COPILOT_DIR/tools/aaps4_compat/android-platform-json.init.gradle" \
  -PandroidPlatformJsonIsolation=true \
  :app:checkAndroidPlatformJsonIsolation :app:testFullDebugUnitTest \
  :app:compileFullDebugAndroidTestKotlin :app:lintFullRelease :app:assembleFullRelease
git diff --quiet HEAD --
```

This pinned upstream has no actual `testFullReleaseUnitTest` task. The command
above uses its real FullDebug unit tests and also compiles instrumented tests;
it does not claim device execution of instrumented tests.

On 2026-10-19 the identical recipe passed this real build in 6m01s: 128 tests,
zero failures/errors/skips; FullRelease lint zero errors and 29 existing warnings.
The unsigned APK is 140452156 bytes, SHA-256
`81b3a9b6e8a4b38574af6bf176fb12e3e244857108329371ea1354a7173a19de`.
Its 86789 defined classes contain no `org/json` implementation; all 321 compiled
classes of the app module are retained. The four dependency
graphs lose exactly their intended duplicate; upstream tracked source is clean.
These are local build results, not a signed release or USB installation.

## Recipe Regression Checks

```sh
AAPS_RC1_DIR="$AAPS_RC1_DIR" python3 -m unittest discover \
  -s "$COPILOT_DIR/tools/aaps4_compat" -p 'test_*.py'
```

The tests run real Gradle offline against synthetic local Maven dependencies.
They compare complete module-identity graph hashes before/after exclusion, check
compile/runtime and project/variant boundaries, preserve host JSON and require
failure when it is missing. Missing wrapper or recipe is an error, not a skip.
Verify runs these checks with the existing pinned upstream Java 21 wrapper.
All six local recipe checks passed in 13.2s, including unresolved dependencies
and explicit opt-out. The recipe is byte-identical to the real-build input.
No phone database, settings, signing key or medical fixture participates.
