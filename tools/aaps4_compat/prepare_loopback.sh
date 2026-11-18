#!/usr/bin/env bash
set -euo pipefail

root="$(cd "$(dirname "$0")/../.." && pwd)"
: "${AAPS_FINAL_DIR:?Set AAPS_FINAL_DIR to the pristine 4.0.0 checkout}"
: "${JAVA_HOME:?Use Java 21 for the upstream build}"
expected=f77abd9b82b308e8fc330da0d7a31370fb4a0f49
test "$(git -C "$AAPS_FINAL_DIR" rev-parse HEAD)" = "$expected"
candidate="${AAPS_LOOPBACK_BUILD_DIR:-$root/android-app/build/aaps-4-loopback}"
test ! -e "$candidate"
git -C "$AAPS_FINAL_DIR" worktree add --detach "$candidate" "$expected"
cd "$candidate"
patch="$root/tools/aaps4_compat/final-loopback-upload.patch"
provider="$root/tools/aaps4_compat/final-android-local-upload.patch"
git apply --check "$patch" "$provider"
git apply --index "$patch" "$provider"
polling="$root/tools/aaps4_compat/final-settings-polling.patch"
git apply --check "$polling"
git apply --index "$polling"
enrollment="$root/tools/aaps4_compat/final-pin-enrollment.patch"
git apply --check "$enrollment"
git apply --index "$enrollment"
reply="$root/tools/aaps4_compat/final-local-key-reply.patch"
git apply --check "$reply"
git apply --index "$reply"
probe="$root/tools/aaps4_compat/final-local-probe.patch"
git apply --check "$probe"
git apply --index "$probe"
picker="$root/tools/aaps4_compat/final-local-picker.patch"
git apply --check "$picker"
git apply --index "$picker"
# A JVM peer can pass even if Android cannot discover the provider or call it.
"${PYTHON:-python3}" - <<'PY'
import xml.etree.ElementTree as ET
manifest = ET.parse("app/src/main/AndroidManifest.xml")
name = "{http://schemas.android.com/apk/res/android}name"
peer = "io.aaps.predictivecopilot"
assert any(p.get(name) == peer for p in manifest.findall("./queries/package")), "Missing local app visibility"
assert any(p.get(name) == peer + ".permission.ISSUE_LOCAL_CLIENT_TOKEN"
           for p in manifest.findall("./uses-permission")), "Missing local enrollment permission"
PY
# The final AAPS build refuses tracked changes. Commit only in this detached fixture.
git -c user.name="Local fixture" -c user.email="fixture@example.invalid" \
    -c commit.gpgsign=false -c core.hooksPath=/dev/null commit -m "Apply local upload transport for verification"
export AAPS_INTEROP_SOURCE="$root/tools/aaps4_compat/final_src"
export AAPS_INTEROP_QUERY_SOURCE="$root/tools/aaps4_compat/loopback_src"
export AAPS_INTEROP_CLASSPATH="$root/android-app/build/aaps4-loopback-interop/classpath.txt"
./gradlew --no-daemon --max-workers=2 --console=plain \
    -I "$root/tools/aaps4_compat/interop.init.gradle" :core:nssdk:jvmTest :core:nssdk:exportInteropClasspath \
    :plugins:sync:testAndroidHostTest --tests '*AuthorizedClientsRepositoryTest' --tests '*AuthorizedClientsViewModelTokensTest' \
    :app:compileFullReleaseKotlin
test -s "$AAPS_INTEROP_CLASSPATH"
