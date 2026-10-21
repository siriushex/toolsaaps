#!/usr/bin/env bash
set -euo pipefail

root="$(cd "$(dirname "$0")/../.." && pwd)"
: "${AAPS_RC1_DIR:?Set AAPS_RC1_DIR to the pristine upstream checkout}"
: "${JAVA_HOME:?Use Java 21 for the upstream build}"
expected=d5e7f5bdbb4aaefa4eaaa89a46fba257ef396fa0
test "$(git -C "$AAPS_RC1_DIR" rev-parse HEAD)" = "$expected"
git -C "$AAPS_RC1_DIR" diff --quiet HEAD --
test -z "$(git -C "$AAPS_RC1_DIR" ls-files --others --exclude-standard)"

temp="$(mktemp -d "${TMPDIR:-/tmp}/master-admission.XXXXXX")"
applied=false
cleanup() {
    status=$?
    trap - EXIT INT TERM
    if "$applied"; then
        git -C "$AAPS_RC1_DIR" diff --binary HEAD -- > "$temp/current.diff"
        git -C "$AAPS_RC1_DIR" ls-files --others --exclude-standard > "$temp/current.untracked"
        if cmp -s "$temp/applied.diff" "$temp/current.diff" &&
            cmp -s "$temp/applied.untracked" "$temp/current.untracked" &&
            git -C "$AAPS_RC1_DIR" apply --reverse --check "$temp/change.patch"; then
            git -C "$AAPS_RC1_DIR" apply --reverse "$temp/change.patch" || status=1
            git -C "$AAPS_RC1_DIR" diff --quiet HEAD -- || status=1
            test -z "$(git -C "$AAPS_RC1_DIR" ls-files --others --exclude-standard)" || status=1
        else
            printf '%s\n' 'Source changed during verification; preserving the checkout without reversal.' >&2
            status=1
        fi
    fi
    rm -rf -- "$temp"
    exit "$status"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

cat "$root/tools/aaps4_compat/master-admission.patch" \
    "$root/tools/aaps4_compat/transport-privacy.patch" > "$temp/change.patch"
git -C "$AAPS_RC1_DIR" apply --check "$temp/change.patch"
git -C "$AAPS_RC1_DIR" apply "$temp/change.patch"
applied=true
git -C "$AAPS_RC1_DIR" diff --binary HEAD -- > "$temp/applied.diff"
git -C "$AAPS_RC1_DIR" ls-files --others --exclude-standard > "$temp/applied.untracked"
cd "$AAPS_RC1_DIR"
./gradlew --no-daemon --max-workers=2 --console=plain \
    :plugins:sync:testAndroidHostTest \
    --tests '*.clientcontrol.ClientControlReceiverTest' \
    --tests '*.clientcontrol.ClientControlRoundTripTest' \
    --tests '*.clientcontrol.BatchActionMappingTest' \
    --tests '*.clientcontrol.ClientControlUplinkIntegrationTest' \
    --tests '*.SmsCommunicatorPluginTest' \
    :implementation:testAndroidHostTest --tests '*.WizardBolusExecutorImplTest' \
    :database:impl:testAndroidHostTest \
    --tests '*.InsertAndCancelCurrentTemporaryTargetTransactionTest' \
    --tests '*.CancelCurrentTemporaryTargetIfAnyTransactionTest' \
    :database:impl:jvmTest --tests '*.TargetAdmissionTransactionTest' \
    :core:nssdk:jvmTest
