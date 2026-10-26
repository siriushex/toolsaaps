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
patches=(master-admission transport-privacy upload-profile-retry combo-history-integrity combo-history-source combo-history-control combo-history-query)
cleanup() {
    status=$?
    trap - EXIT INT TERM
    if "$applied"; then
        git -C "$AAPS_RC1_DIR" diff --binary HEAD -- > "$temp/current.diff"
        git -C "$AAPS_RC1_DIR" ls-files --others --exclude-standard > "$temp/current.untracked"
        if cmp -s "$temp/applied.diff" "$temp/current.diff" &&
            cmp -s "$temp/applied.untracked" "$temp/current.untracked" &&
            git -C "$AAPS_RC1_DIR" apply --reverse --check "$temp/change.patch"; then
            # Later patches may modify files introduced by an earlier patch.
            # A concatenated reverse can recreate those files after removing them.
            for ((i=${#patches[@]}-1; i>=0; i--)); do
                if ! git -C "$AAPS_RC1_DIR" apply --reverse "$temp/${patches[i]}.patch"; then
                    status=1
                    break
                fi
            done
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

for patch in "${patches[@]}"; do
    cp "$root/tools/aaps4_compat/$patch.patch" "$temp/$patch.patch"
done
for patch in "${patches[@]}"; do
    cat "$temp/$patch.patch"
done > "$temp/change.patch"
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
    --tests '*.NSClientV3PluginTest' \
    --tests '*.DataSyncSelectorV3Test' \
    :implementation:testAndroidHostTest --tests '*.WizardBolusExecutorImplTest' \
    :database:impl:testAndroidHostTest \
    --tests '*.InsertAndCancelCurrentTemporaryTargetTransactionTest' \
    --tests '*.CancelCurrentTemporaryTargetIfAnyTransactionTest' \
    :database:impl:jvmTest --tests '*.TargetAdmissionTransactionTest' \
    :core:nssdk:jvmTest \
    :pump:combov2:comboctl:testDebugUnitTest \
    :pump:combov2:testDebugUnitTest
