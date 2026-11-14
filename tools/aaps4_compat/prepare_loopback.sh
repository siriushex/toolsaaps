#!/usr/bin/env bash
set -euo pipefail

root="$(cd "$(dirname "$0")/../.." && pwd)"
: "${AAPS_FINAL_DIR:?Set AAPS_FINAL_DIR to the pristine 4.0.0 checkout}"
: "${JAVA_HOME:?Use Java 21 for the upstream build}"
expected=f77abd9b82b308e8fc330da0d7a31370fb4a0f49
test "$(git -C "$AAPS_FINAL_DIR" rev-parse HEAD)" = "$expected"
candidate="$root/android-app/build/aaps-4-loopback"
test ! -e "$candidate"
git -C "$AAPS_FINAL_DIR" worktree add --detach "$candidate" "$expected"
cd "$candidate"
patch="$root/tools/aaps4_compat/final-loopback-upload.patch"
provider="$root/tools/aaps4_compat/final-android-local-upload.patch"
git apply --check "$patch" "$provider"
git apply --index "$patch" "$provider"
# The final AAPS build refuses tracked changes. Commit only in this detached fixture.
git -c user.name="Local fixture" -c user.email="fixture@example.invalid" \
    -c commit.gpgsign=false -c core.hooksPath=/dev/null commit -m "Apply local upload transport for verification"
export AAPS_INTEROP_SOURCE="$root/tools/aaps4_compat/final_src"
export AAPS_INTEROP_QUERY_SOURCE="$root/tools/aaps4_compat/loopback_src"
export AAPS_INTEROP_CLASSPATH="$root/android-app/build/aaps4-loopback-interop/classpath.txt"
./gradlew --no-daemon --max-workers=2 --console=plain \
    -I "$root/tools/aaps4_compat/interop.init.gradle" :core:nssdk:jvmTest :core:nssdk:exportInteropClasspath
test -s "$AAPS_INTEROP_CLASSPATH"
