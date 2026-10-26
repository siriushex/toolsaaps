#!/usr/bin/env bash
set -euo pipefail

root="$(cd "$(dirname "$0")/../.." && pwd)"
: "${AAPS_RC1_DIR:?Set AAPS_RC1_DIR to the pristine upstream checkout}"
: "${JAVA_HOME:?Use Java 21 for the upstream build}"
expected=d5e7f5bdbb4aaefa4eaaa89a46fba257ef396fa0
test "$(git -C "$AAPS_RC1_DIR" rev-parse HEAD)" = "$expected"
git -C "$AAPS_RC1_DIR" diff --quiet HEAD --
test -z "$(git -C "$AAPS_RC1_DIR" ls-files --others --exclude-standard)"
temp="$(mktemp -d "${TMPDIR:-/tmp}/query-interop.XXXXXX")"
trap 'rm -rf -- "$temp"' EXIT
query_source=core/nssdk/src/commonMain/kotlin/app/aaps/core/nssdk/localmodel/clientcontrol/ComboHistoryReadRequest.kt
cp "$root/tools/aaps4_compat/combo-history-query.patch" "$temp/query.patch"
# Compile the exact candidate decoder beside the external driver, not in upstream.
(cd "$temp" && git apply --include="$query_source" "$temp/query.patch")
test -s "$temp/$query_source"
export AAPS_INTEROP_QUERY_SOURCE="$temp/core/nssdk/src/commonMain/kotlin"
export AAPS_INTEROP_SOURCE="$root/tools/aaps4_compat/src"
export AAPS_INTEROP_CLASSPATH="$root/android-app/build/aaps4-interop/classpath.txt"
rm -f -- "$AAPS_INTEROP_CLASSPATH"
cd "$AAPS_RC1_DIR"
./gradlew --no-daemon --max-workers=2 --console=plain -Pkotlin.incremental=false \
    -I "$root/tools/aaps4_compat/interop.init.gradle" :core:nssdk:exportInteropClasspath
git diff --quiet HEAD --
test -z "$(git ls-files --others --exclude-standard)"
test -s "$AAPS_INTEROP_CLASSPATH"
