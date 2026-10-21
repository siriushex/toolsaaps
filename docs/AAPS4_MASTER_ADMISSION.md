# RC1 Master Admission Patch

Target: official 4.0.0-rc1, commit
`d5e7f5bdbb4aaefa4eaaa89a46fba257ef396fa0`. The patch is limited to command
admission and the existing temporary-target transaction path, with synthetic
regressions. It changes no dosing calculation, SDK wire format, preferences,
pump driver or clinical database schema.

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

## Target Transaction Admission

An optional callback accompanies the native commit into the executor. Only a
batch prepared from exactly one TempTarget action uses it. The consume-once
pending slot is removed first, then admission is checked before persistence,
inside the acquired Room transaction, after its read and after its writes.
An expiry, revoked pairing, changed counter/identity or disabled control throws
inside the transaction and rolls back both cancellation and insertion. No target
change notification or user-entry success can follow the rejected transaction.
The receiver returns the correlated signed refusal without replaying the slot.

The wall/monotonic deadline is read after suspending preference/identity reads.
The final check precedes SQLite commit; this is not an atomic transaction with
the separate preferences store and cannot guarantee revocation occurring after
that check. A completed transaction is never retroactively reported as expired.

Local target calls retain their original overload. Mixed batches, insulin,
carbohydrates and pump queues retain upstream semantics and are not covered by
the target-only guarantee. Copilot's codec must continue to admit only target-only
actions. The source patch alone does not enable production Target Manager.

## Verification

```sh
JAVA_HOME="$JAVA_21_HOME" AAPS_RC1_DIR="$PRISTINE_RC1_DIR" \
  bash tools/aaps4_compat/verify_master.sh
```

The helper requires the exact commit and no tracked or nonignored untracked
changes. It snapshots and applies the admission and transport-privacy patches,
runs the real receiver, round-trip,
mapping, uplink, SMS and executor tests, plus target transaction host tests and
real Room/bundled-SQLite JVM rollback checks and the full NSSDK JVM suite, then
reverses only its own exact diff.
If concurrent source edits occur it preserves the checkout and fails instead of overwriting
them. Gradle needs the upstream-required Android platform. The stock SDK fixture
remains a separate pristine-source check; this patched stage is not called stock.
CI runs `prepare.sh` after this helper has restored the source, so the exported
stock classpath cannot accidentally reuse the patched SDK build output. When
running the helper locally after preparation, run preparation again before
claiming a pristine SDK interoperability result.

On 2026-10-20 the frozen patch passed 103 receiver/round-trip/mapping/uplink
tests, 80 existing executor tests and all 128 app tests, with zero failures,
errors or skips. FullDebug instrumented-test Kotlin compilation, FullRelease
lint and unsigned APK build passed in 9m55s. Lint has zero errors and 29 existing
warnings. The 140452156-byte unsigned APK has SHA-256
`3463efa2ac97a7ebe0858e7259a0db8383ba34d1018a2880def2ac16a77e0533`.
Both master classes are defined among its 86791 DEX classes. It is a **patched**
build, not the previous pristine artifact, and is not installed. Six synthetic
real-Git recipe tests also pass, including concurrent-edit preservation.

The October21 target-transaction extension passed224 native tests (receiver,
round-trip, mapping, uplink, SMS, executor and database), plus128 app tests, with
zero failures/errors/skips. Actual SQLite rollback checks cover all three
transaction boundaries, suspended revocation, cancelled caller, unchanged prior
target and absent change notifications. FullDebug instrumented-test compilation,
FullRelease lint and unsigned build passed in10m04s; lint retains29 warnings and
zero errors. Unsigned APK:140468540bytes, SHA-256
`2aac7abc68f9d86ccf1a562f3387c17783f596b5cd58f411caee03e1378ee952`.
Two failing-first executor regressions are retained in local test evidence.
The Git recipe suite now has eight cases including added/untracked edit preservation.
The actual helper applied and reversed the frozen patch on the pinned source,
leaving it clean. All14 tooling checks passed. Full Copilot verification passed
6571 tests with six pre-existing private-input skips, zero errors, and all19
target-journal checks. Late signed transaction refusals cannot mark application
or replay a command; forged refusals retain unknown ownership. Lint/build and
APK size/class-retention guards pass. Exact-head CI/main receipts are recorded
separately in the PR/checkpoint after publication, not inferred from local results.

## Remaining Admission Gates

The October21 transport stage adds a separate two-line URL logging repair with
four synthetic native tests. Three tests failed against the unmodified logger;
all174 SDK tests passed after the repair. The combined admission/transport helper
passed398 tests with zero failures/errors/skips and restored the pristine tree.
It preserves HTTP method/status diagnostics but never logs token-bearing paths,
query strings, headers or bodies. This is not a TLS trust change or a phone build.

The target-only callback extends admission into the temporary-target transaction.
Mixed command batches and pump queues remain outside that boundary. A receiver test returning
`Delivered` from a mock proves neither application nor pump delivery. Do not
translate expiry after a real effect into an assertion that nothing happened.

Production native publication remains closed in Copilot. Typed clinical/profile
exchange, loopback CA trust on Android and actual JugglucoNG continuity on the
upgraded phone remain separate gates; see AAPS4_CLINICAL_PROFILE_CONTRACT.md.
The log repair still requires exact-head CI and later artifact/device acceptance.
This helper
does not sign/install an APK, pair a device or issue a therapy command.
