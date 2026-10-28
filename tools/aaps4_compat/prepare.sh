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
# Compile the exact candidate protocol beside the driver, not in upstream.
for part in query reply; do
    cp "$root/tools/aaps4_compat/combo-history-$part.patch" "$temp/$part.patch"
done
for part in query reply; do
    if [ "$part" = query ]; then name=ComboHistoryReadRequest; else name=ComboHistoryReadReply; fi
    source=core/nssdk/src/commonMain/kotlin/app/aaps/core/nssdk/localmodel/clientcontrol/$name.kt
    (cd "$temp" && git apply --include="$source" "$temp/$part.patch")
    test -s "$temp/$source"
done
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
