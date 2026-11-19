#!/usr/bin/env bash
set -euo pipefail

root="$(cd "$(dirname "$0")/../.." && pwd)"
: "${AAPS_FINAL_DIR:?Set AAPS_FINAL_DIR to the pristine 4.0.0 checkout}"
: "${JAVA_HOME:?Use Java 21 for the upstream build}"
test "$(git -C "$AAPS_FINAL_DIR" rev-parse HEAD)" = f77abd9b82b308e8fc330da0d7a31370fb4a0f49
git -C "$AAPS_FINAL_DIR" diff --quiet HEAD --
test -z "$(git -C "$AAPS_FINAL_DIR" ls-files --others --exclude-standard)"

export AAPS_INTEROP_SOURCE="$root/tools/aaps4_compat/final_src"
export AAPS_INTEROP_CLASSPATH="$root/android-app/build/aaps4-final-interop/classpath.txt"
unset AAPS_INTEROP_QUERY_SOURCE
rm -f -- "$AAPS_INTEROP_CLASSPATH"
cd "$AAPS_FINAL_DIR"
./gradlew --no-daemon --max-workers=2 --console=plain \
    -I "$root/tools/aaps4_compat/interop.init.gradle" :core:nssdk:exportInteropClasspath
git diff --quiet HEAD --
test -z "$(git ls-files --others --exclude-standard)"
test -s "$AAPS_INTEROP_CLASSPATH"
