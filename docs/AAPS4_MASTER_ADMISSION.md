# RC1 Master Admission Patch

Target: official 4.0.0-rc1, commit
`d5e7f5bdbb4aaefa4eaaa89a46fba257ef396fa0`. The patch is deliberately limited
to command reception and its tests. It changes no dosing calculation, SDK wire
format, preferences, pump driver or clinical database schema.

## Confirmed Defect

Upstream captures the wall clock before a settings read or before waiting for the
command mutex. Authorization, Executing ACK and counter persistence can suspend
after expiry was checked. Six actual source regressions reproduced an invocation
of the mocked executor despite expired/revoked/disabled admission. These are
synthetic master tests, not pump or phone commands.

`master-admission.patch` captures a monotonic receipt before those waits, checks
fresh wall time at admission and after the ACK/counter writes, and rechecks the
stored pairing identity/state/counter and control setting. An exact deadline or
wall-clock rollback rejects the command. Monotonic elapsed time cannot renew its
budget when the wall clock stalls. Rejected counters are consumed, not replayed;
revocation cannot recreate the deleted pairing. A final ACK uses fresh time.

## Verification

```sh
JAVA_HOME="$JAVA_21_HOME" AAPS_RC1_DIR="$PRISTINE_RC1_DIR" \
  bash tools/aaps4_compat/verify_master.sh
```

The helper requires the exact commit and no tracked or nonignored untracked
changes. It snapshots and applies the patch, runs the real receiver, round-trip,
mapping, uplink and existing executor tests, then reverses only its own exact diff.
If concurrent source edits occur it preserves the checkout and fails instead of overwriting
them. Gradle needs the upstream-required Android platform. The stock SDK fixture
remains a separate pristine-source check; this patched stage is not called stock.

On 2026-10-20 the frozen patch passed 103 receiver/round-trip/mapping/uplink
tests, 80 existing executor tests and all 128 app tests, with zero failures,
errors or skips. FullDebug instrumented-test Kotlin compilation, FullRelease
lint and unsigned APK build passed in 9m55s. Lint has zero errors and 29 existing
warnings. The 140452156-byte unsigned APK has SHA-256
`3463efa2ac97a7ebe0858e7259a0db8383ba34d1018a2880def2ac16a77e0533`.
Both master classes are defined among its 86791 DEX classes. It is a **patched**
build, not the previous pristine artifact, and is not installed. Six synthetic
real-Git recipe tests also pass, including concurrent-edit preservation.

## Remaining Admission Gates

This patch guards **dispatch to the executor**, not the later transaction or pump
queue. The executor still needs an independently checked target-only validity
boundary at the actual temporary-target mutation. A receiver test returning
`Delivered` from a mock proves neither application nor pump delivery. Do not
translate expiry after a real effect into an assertion that nothing happened.

Production native publication remains closed in Copilot. Typed clinical/profile
exchange, loopback CA trust on Android, refresh-path log redaction and actual
JugglucoNG continuity on the upgraded phone remain separate gates. This helper
does not sign/install an APK, pair a device or issue a therapy command.
