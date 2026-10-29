# DEVOPS

## Client History Transfer Verification

Run the transfer, paired read-journal, catalog retention and native channel suites
before full Android quality and APK checks. The stale-reply regression reproduces
the former failure between consecutive requests. Exercise real private SQLite,
2MiB bounded transfer, slow storage after cancellation and after reply expiry,
target priority and unsupported peers. Preserve existing native interop CI checks.
No AAPS patch changes, schema migration or source deletion. Rollback keeps the
private no-backup history database; no phone/server/release operation is implied.
See AAPS4_COMBO_CLIENT_TRANSFER.md for limits and release acceptance gaps.

## Native Read Owner Verification

The eleventh frozen patch, `combo-history-owner.patch`, connects the bounded
authenticated history handler. Run the combined recipe, including the new
`ComboHistoryReadOwnerTest` filter, and verify restoration of the pinned checkout.
Then prepare the pristine SDK oracle and run full Android test/lint/compile/build
and APK guards. Use only synthetic history and credentials in source/CI.
Missing owner patch must fail before applying any source edits. Rollback is source
reversion with private journals preserved; no device or therapy action is included.
Capability/client background lifecycle and real source I/O acceptance are separate.

## Android Test Runtime Resolution

Resolve `:app:prepareRobolectricSdks` before tests. Robolectric4.16.1 uses pinned
instrumented Android runtimes for API31,34,35 and the target/default API36.
Each version has its own non-transitive Gradle configuration; a shared
configuration would silently select only the newest version of this module.
The Sync task writes to `app/build/robolectric-sdks`. Unit tests use that directory
with `robolectric.offline=true`, including local runs. These configurations are
test infrastructure only and must not join the application runtime classpath.
When adding an SDK annotation or updating Robolectric, verify every selected SDK
is resolved. A missing jar must fail, not trigger a download inside a testcase.

PR69 push CI40825424192 failed while lazily downloading the API35 runtime with
a connection reset. Resolve/download failure is now a separate CI preparation
step, without test retries, skips or a changed Robolectric version.
PR CI40825434795 instead exposed an alert test oracle race: caller continuation
order after releasing a mutex is not the database commit order. Observe the
existing callback under the shared delivery lock and exercise deliberately
reversed caller completion. Production alert behavior is unchanged.

## Native CI Failure Evidence

Always preserve `native-unit-test-reports` for 3 days, restricted to
`android-app/build/aaps-rc1/**/build/test-results/**/TEST-*.xml`. These are synthetic
tests in the pinned checkout, not device records. Keep the original failing run
and inspect its testcase/stdout before diagnosing a timeout. Do not upload the
whole checkout, credentials, raw device logs or databases. The Copilot XML
artifact is separate and cannot explain a failure before its tests start.

Main CI 40787591139 hit the 60s watchdog in unchanged long-button-release behavior;
one pristine isolated run and 30 two-processor repetitions passed locally. Cause
remains unproven. Artifact retention improves diagnosis; it does not fix that
timeout or validate pump behavior. No blind retry-to-green or timeout extension.

## Native Reply Verification

The native recipe now freezes eleven compatibility patches, including the reply
codec, structured-child watchdog fix and read owner. SDK preparation extracts the exact decoder and encoder
beside the external driver without patching upstream. Run recipe tests, native
verification/restoration, sequential pristine SDK preparation, then Copilot's
AapsV3ComboNativeReplyTest and full Android quality/APK guard.

Verify metadata digest and adverse flags as well as decryption: encryption alone
does not establish protocol equivalence or complete history. Use only synthetic
fixtures. This source stage has no runtime/phone/server/APK deployment or migration;
rollback removes the unused codec while preserving all retained evidence.

CI 40781360339 exposed an upstream Combo error-lockout timer race. The ninth
patch, `combo-error-timeout.patch`, retires timer ownership atomically before
unlock/callbacks and prevents a cancelled timer from clearing a newer error.
Its controlled-time regression fails without the fix; no CI skip or retry-to-green
is used. The original failed run is retained. The patch is applied only in the
reversible verification checkout, not deployed to AAPS. Lockout duration, pump
delivery commands and clinical activation are unchanged.

## Catalog Retention Verification

Run `AapsV3ComboCatalogRetentionTest` with the read-journal, inventory and private
store suites, then full Android test/lint/compile/build with the pinned SDK driver.
Exercise real paired ACK/AEAD receipt and take, fresh-pong session replacement,
roster OFF/ON, close/cancellation, storage failure, delayed audits and target
preparation while the private database is blocked. The 4096-entry maximum catalog
must assemble without partial persistence or a delivery-completeness claim.

Session/admission observations are in-memory handoff fields only; no wire, Room
or encrypted-record schema migration is introduced. Source rollback preserves
retained bytes. Native dispatch, device I/O acceptance and clinical activation
remain separate, with no new phone/server/APK deployment in this source stage.

## Combo Handoff Verification

Run AapsV3ComboReadJournalTest with protocol/private-store cases, then full Android
quality and the actual pristine SDK/TLS fixture. Native SQLite tests cover consume
rollback, external/inherited transactions, concurrent take, post-commit expiry,
scope changes, private-write cancellation/close and 64 authenticated fragments for a
2 MiB retained read. No main transaction may span private database I/O; test target
preparation while the private database is blocked, without issuing real therapy.

CONSUMED is a new terminal encrypted journal enum, not a Room schema migration.
An older binary may reject that disconnected read record; it must not be repaired
or deleted to force recovery. Ordinary target operations do not parse it. No live
owner is connected in this stage. Rollback is source revert with retained evidence
preserved; no phone/server deployment or clinical activation is implied.

## Native Query Verification

`combo-history-query.patch` adds a pure shared SDK decoder and common tests. The
reversible native recipe now owns eight frozen patches. `prepare.sh` extracts
the decoder and reply codec into an owned temporary directory and compiles those exact sources beside
the external SDK driver; the upstream checkout remains pristine. Missing patch or
source fails before Gradle. Temporary extraction is removed on completion/failure.
The driver checks actual Copilot inventory/cursor/chunk requests using native MAC
verification and the candidate decoder, while confirming stock action dispatch
still rejects the extension. Run recipe tests, native suites, then sequential SDK
preparation and Copilot quality. Do not concurrently mutate native build outputs.
This SDK-only preparation disables Kotlin incremental compilation: after native
patch restoration the pinned compiler can fail computing changed symbols across
main/test source sets. It still compiles the full SDK/driver and runs the same
interoperability checks; app and native-test compilation settings are unchanged.

## Combo Read Outbox Verification

Run native-SQLite `AapsV3ComboReadJournalTest` with pairing/target journal and
channel regressions. Cover file reopen, exact recovery, priority, concurrent reads,
row/wire headroom, late clock/cancellation, write failure and external transactions.
The external `ComboReadRequestInteropKt` verifies the candidate MAC using actual
pristine RC1 crypto and confirms that its stock closed message schema refuses the
unknown extension. It is not an extended master endpoint or phone acceptance test.
Run `prepare.sh`, focused/full Android tests with required native driver variables,
lint/build and APK guard, then exact-head CI. No AAPS source modification or schema
migration is introduced. Rollback removes the disconnected code; preserve journals.

Local incremental packaging retained 7.65 MB of unreferenced ZIP gaps. Moving only
the generated APK aside and running assemble again rebuilt the archive from the
same checked inputs; compare every member's SHA-256 and rerun the retention/size
guard. Archive size alone must not be mistaken for a DEX/code-size regression.

## Combo Chunk Envelope Verification

Run `tools/aaps4_compat/prepare.sh` against the clean pinned RC1 checkout with
Java21. It compiles the external synthetic `ComboChunkCryptoInteropKt` driver
without modifying native sources. Run `AapsV3ComboChunkEnvelopeTest` with the
same `AAPS_INTEROP_CLASSPATH` and `AAPS_INTEROP_JAVA_HOME` as the existing TLS
suite; CI requires the native crypto test rather than accepting a missing fixture.
The driver uses RC1's real cryptography-kotlin HKDF and AES-GCM in both directions,
only synthetic fixed-key bytes, bounded stdin and a fixed error code. It opens
no network connection or pump/application credential and creates no AAPS extension.

Then run full Android test/lint/compile/build, actual SDK TLS and APK retention
checks. No schema, deployment or configuration migration is required; rollback
is source revert. A green run does not authorize transport or meal-time activation.

## Paired Journal Verification

Run AapsV3TargetJournalTest and AapsV3PairingJournalTest on native SQLite with
AapsV3TargetChannelTest and AapsV3ComboJournalStoreTest. Preserve external/inherited
transaction rejection, the nested read-only pairing assertion, shared probe/target
counters and pending ACK settlement. Then run full Android tests/lint/compile/build,
actual pristine SDK TLS and APK retention/size checks before exact-head CI.
The compatibility check is pinned to Room2.8.4, including its RestrictedApi guard.
No schema, phone, server or AAPS migration; rollback is reverting this source stage.
Do not connect raw Combo transfer or admit meal-time advice based on these tests.

## Forecast Test Verification

Run ForecastTest session/arbiter/policy/notification/UI tests, the reactive lease
wiring regression, full Android tests/lint/compile/build, pristine SDK TLS and
APK retention/size checks. Only source, synthetic tests and documentation belong
in the PR. Do not activate the high-compute session on a phone as a build check.
Rollback is stopping the explicit session and reverting this source stage; no
schema, credential, AAPS or server migration is involved. Device thermal/CPU and
prospective accuracy validation remain separate from JVM/CI evidence.

## Combo Consumer Verification

Private retention also runs AapsV3ComboJournalStoreTest against native SQLite,
including same-file reopen, multi-chunk abort and explicit journal/sync checks.
Five nested-transaction regressions also cover new/replayed receipts, reads,
inventory and a dispatcher switch. Keep them when upgrading Room: the narrowly
suppressed compatibility-mode guard is pinned to Room2.8.4. It must not become a
no-op after a SQLiteDriver migration. Independent concurrent retains must pass.
No production instance is created by this stage. Rollback removes the unused
source integration only; preserve any retained private database. There is no
destructive migration or clinical database schema change. See
AAPS4_COMBO_PRIVATE_RETENTION.md for ownership, bounds and device-validation gates.

Run AapsV3ComboJournalReadTest and existing Combo evidence/owner tests, then full
Android test/lint/compile/assemble and pristine SDK TLS. The native compatibility
recipe pins the same synthetic writer digest as Copilot; it changes no production
native behavior. Never generate golden fixtures from a phone. A successful
in-memory assembly is not a persistent receipt or deployment. See
AAPS4_COMBO_JOURNAL_INGESTION.md; paired encrypted transport remains disconnected.

## Combo Readout Verification

The existing native compatibility recipe runs synthetic readout tests alongside
writer, parser and plugin regressions. Use only temporary synthetic journals;
never copy actual raw history into source, CI or logs. The feature has no runtime
caller or automatic upload. Readout rollback removes the source API only; preserve
private journal files. Device latency, paired transport and clinical admission
remain independent. See AAPS4_COMBO_HISTORY_READOUT.md.

## Retained Combo Evidence Verification

Run the new typed-evidence, clinical-source and target-owner tests before the
full Android suite, lint, build, pristine SDK TLS and APK class-retention guard.
The native patch is unchanged. No production migration, server deployment or
phone update is part of this source stage; all diagnostic inputs are synthetic.
See AAPS4_COMBO_HISTORY_EVIDENCE.md for capacity and unsupported-identity limits.

## Combo Journal Verification

The reversible native recipe includes actual temporary-file journal tests and
plugin callback/status tests. No real pump or medical data is used. The private
no-backup journal is bounded and has no automatic pruning or settings migration.
Do not delete retained evidence to hide capacity/corruption failures. Source
rollback removes the added capture code only; preserve existing journal files.
Run native faults, recipe cleanup, pristine SDK TLS, Room retention and full
Android quality before exact-head CI/review. Device fsync latency and failure
behavior are not established by JVM tests. No deployment or timer admission is
implied. See AAPS4_COMBO_HISTORY_JOURNAL.md.

## Combo Observation Verification

The same reversible compatibility recipe now runs both ComboCtl and the Combo
plugin unit suites. No separate pump reader or transport is deployed. Rebuild
the pristine SDK driver to exercise opaque ComboHistoryRead status over TLS;
Copilot's Room import test verifies immutable history without therapy writes.
See AAPS4_COMBO_HISTORY_OBSERVATION.md. Source integration does not install AAPS,
publish an APK or enable a meal-time recommendation.

## Native Profile Catalog Stage

LOCAL_NS_V3_PROFILE.md adds an immutable catalog route to the existing listener
without a Room migration, background worker, AAPS patch or clinical writer.
The pinned pristine SDK driver exercises native profile upload/read/history and
the hour-label/exact-seconds distinction. Rebuild the driver with prepare.sh
before its integration test; unchanged upstream source remains mandatory.
Run focused profiles, full Android test/lint/build and exact-head Verify before
main integration. This source stage is not AAPS4 migration admission and does
not alter the published APK or phone. Production clinical dispatch stays closed.

## Controlled Main Integration

Insulin-window regressions exercise malformed future records through both the
pure index and accepted v3 wire data in native Room, with cache/reopen/correction
and valid-future controls. They do not require a real pump, payload or server.
Run the full Android quality and pristine SDK TLS suite before integration.

WORKSTREAMS.md tracks isolated assignments and coordinator-owned integration.
The October21 reconciliation combines independently tested main/development
features; neither prior suite proves the merged tree. Run full merged Android
tests/lint/build, real stock-SDK fixture, backend and APK guards, inspect
exact-head Verify, then explicitly merge after a fresh main check. Verify the
resulting main SHA and CI separately. No auto-merge, force push or implied server,
phone, clinical activation or public APK publication.

## RC1 Receiver Patch Verification

The combined recipe also applies combo-history-integrity.patch,
combo-history-source.patch and combo-history-control.patch and runs the full
native Combo module suite. Synthetic packets exercise malformed history flags
and acknowledgement order without accessing a real pump. The same exact-diff
cleanup owns all seven patches; prepare.sh still requires pristine upstream.
Patch inputs are copied before applying. Rollback reverses these immutable copies
in reverse dependency order: reversing one concatenated stream can recreate a
new file modified by a later patch. Synthetic recipe tests cover that case,
missing inputs and concurrent edits without overwriting them.
The control patch adds isolated history refusal ACKs and native receiver tests
against disguised stock payloads; it does not enable source reads or advertise
a history capability. Existing target/executor tests still run in the same suite.
See AAPS4_COMBO_HISTORY_INTEGRITY.md. This is not an AAPS phone deployment.

The upload patch also includes SDK clock-refusal regressions. These use the
native JVM HTTP client with a local synthetic server, not a real pump or service.
The backend owner composition regression now runs20isolated cases and includes
fixed state, rejected-storage stack locations and synthetic transport counts on
unexpected terminal states. No request text, token or database body is printed.
This instruments the intermittent PR32 UNKNOWN; it does not establish its cause.

The existing upload patch also requires valid insulin CREATE/UPDATE receipts;
repeated refusals and uploader recreation cannot silently advance those cursors.
Native test doubles exercise retained preference state, not an installed phone
or full process-persistence attestation. Existing failure cleanup and source pin
checks remain mandatory before the pristine SDK interoperability run.

See AAPS4_MASTER_ADMISSION.md. `tools/aaps4_compat/verify_master.sh` temporarily
applies the reviewed receiver/target-transaction/test patch to a clean pinned RC1
checkout, runs native/SMS/executor and real Room rollback regressions, and reverses
its own exact diff and added test file even after a test
failure. Unknown concurrent edits are preserved and fail the check. The existing
recipe now includes upload-profile-retry.patch and both complete native uploader
suites; see AAPS4_UPLOAD_PROFILE_RETRY.md. A missing historical profile holds the
corresponding upload queue, not a successful acknowledgement. This verification
does not install the patch on a phone or attest full delivery history. The
pristine SDK preparation gate stays unchanged; Verify separately installs the
upstream-required Android37 platform (`platforms;android-37.0`, the exact Google
package identifier) for native module tests. This is not a signed
AAPS APK publication, full upstream APK CI or USB installation permission.

Match the CI toolchain split locally: native patch/SDK preparation uses Java21;
Copilot quality runs use Java17 with `AAPS_INTEROP_JAVA_HOME` pointing to Java21.
A JVM fatal error is an incomplete test run, not an application assertion result.
Keep crash/replay reports private and outside staged source paths.

## Treatment Wire Stage

LOCAL_NS_V3_TREATMENTS.md reuses the existing authenticated listener/Room37 store.
No schema, credential, background worker, clinical writer or AAPS source change.
The real pinned SDK fixture now also uploads/reads/patches/deletes treatments and
preserves record ICfg and canonical native units. Keep full test/lint/build,
stock SDK and APK guards enabled. Profile/food and clinical/master admission stay
closed; never remove the native publication lock to install this partial stage.

## Native Pairing Stage

See AAPS4_PAIRING.md. The existing SDK fixture now starts with real native PIN
wrapping and verifies Hello/Ping/target counters1/2/3/4 over TLS. It still simulates
the master, not actual application. Runtime/key/roster failures cannot select a
legacy fallback. No extra worker, AAPS patch or phone migration is part of this
source stage. Never drop the production clinical-admission lock to deploy it.
Private sealed journal JSON now includes pairing/probe/retired-owner fields;
old-APK rollback is not admitted merely because the Room version remains37.
Preserve originals and verify compatibility rather than restoring old counters.

## Native RC1 Master Regression

With Java21 and the pinned unchanged RC1 checkout, these local mock-only tasks
passed173 tests on2026-10-19. No device or therapy command is involved:

```sh
./gradlew :plugins:sync:testAndroidHostTest \
  --tests '*.clientcontrol.ClientControlReceiverTest' \
  --tests '*.clientcontrol.ClientControlRoundTripTest' \
  --tests '*.clientcontrol.BatchActionMappingTest' \
  --tests '*.clientcontrol.ClientControlUplinkIntegrationTest' \
  :implementation:testAndroidHostTest --tests '*.WizardBolusExecutorImplTest' \
  --no-daemon --max-workers=2 --console=plain
```

This complements, not replaces, the real SDK TLS fixture and Copilot's full
test/lint/build/size assessment. See AAPS4_TARGET_JOURNAL.md for live admission.

## Stock SDK Interoperability

The existing real-SDK fixture now also exercises settings and native target-only
envelope/ACK codecs. A bounded child/parent handshake verifies native preview
before commit publication, then receives the commit through authenticated
`/storage` polling before native DTO/HMAC verification. The same peer subscribes
with the six unchanged stock collection names and verifies a settings update.
This is a synthetic wire peer, not the actual Android socket/master executor
or an AAPS device rollout. No clinical rows or READY transition follow. See
LOCAL_NS_V3_SETTINGS.md, LOCAL_NS_V3_STORAGE.md and AAPS4_TARGET_PROTOCOL.md;
durable pairing/live admission remains.

Verify now pins a pristine AAPS RC1 checkout and compiles its JVM SDK with an
external test driver using Java21, then runs Copilot tests/lint/build on Java17.
The driver uses the real local TLS listener and synthetic Room data. CI fails
if the required SDK fixture is absent. No APK/signing files or credentials from
the user's device enter this check. See LOCAL_NS_V3_ENTRIES.md for local commands.
Entry and immutable device-status routes remain partial wire integration, not
whole-app or device admission. Device-status SDK checks use the same isolated
driver; no extra AAPS source patch or credential is introduced.

The external Android JSON packaging recipe and its synthetic Gradle regression
checks are documented in AAPS4_BUILD.md. Recipe CI is not a full upstream APK
build: the latter currently requires local Android platform 37. It must not be
reported as stock-client/device acceptance or permission to install unsigned APKs.

## Local V3 Store Migration

Room 35->36 creates only `local_ns_v3_documents` and `local_ns_v3_clock` plus their
indexes. It neither rewrites clinical tables nor imports data into the new store.
The production builder already registers the complete migration chain; no new
destructive fallback is added. Tests cover a synthetic 35 database and existing
migration paths; no real phone database is opened or copied for this stage.

The persistence stage alone does not authorize a device update or v3 routes.
Before a later device rollout, account for the irreversible forward schema step:
an APK expecting 35 cannot open a migrated 36 database without an explicit
compatible migration. Preserve medical history; never uninstall, reset or use
destructive fallback as rollback. No pruning/reset API is available in the store.
Runtime bounds and remaining retention/admission gates: LOCAL_NS_V3_STORE.md.

## Local AAPS4 Authentication Stage

This is Copilot source only. The local TLS listener accepts RC1-style token
refresh; entries and immutable device status have typed adapters, but other collections explicitly
return501 and cannot set runtime READY.
Do not migrate AAPS settings, enroll its client or publish a compatible-device
claim yet. No new remote service, database schema, secret file or dependency is
introduced. Restart/rollback discards ephemeral bearers; it must preserve the
existing private TLS identity and local/remote settings. Full stock-client trust,
revisioned collections and acknowledged commands remain acceptance gates in
AAPS4_COMPATIBILITY.md and LOCAL_NS_V3_AUTH.md.

## Matched Photo Decoder Updates

Photo source and decoder must be released together. DecoderRuntimeImage verifies
the exact decoder entry, JPEG normalizer and meal schema hashes, not just Python
and Pillow. A complete backend source update with an older decoder correctly
fails with decoder_source_rejected. Never bypass or replace that check with a
generic runtime-health assertion.

Prepare a new root-owned immutable decoder directory from the verified public
inventory, retaining binary/library pins and changing only the reviewed source
files. Validate both old and new inventories. Use a new protected vision
descriptor, preserving native model/profile/control/API identities and the
existing text-admission source. Test a synthetic JPEG in the actual offline
contained decoder and require independently confirmed stop before activation.

For switching, close only the dedicated AI proxy routes, drain workers and
require idle jobs plus expired in-memory result TTL. Keep text running. Stop API
before image; start image and wait for its PID-bound listener before API. Listener
readiness must not acquire the native profile guard concurrently with startup.
On failure restore only the owned image override and original proxy after the
previous image/API pair is healthy. Retain all uncertain claims and private data.

On 2026-10-18 this flow passed for source f814e0f6: 15 operator tests, a real
offline synthetic decoder check, exact live source/descriptor verification,
unchanged model/login/schema checks and external HTTPS authentication refusal.
No provider turn or medical image was submitted. This is decoder/host readiness,
not food-recognition accuracy or a signed-device end-to-end inference test.

## Provider Usage Source Integration

The new local text client requests provider-token-usage-v1 receipts. Upgrade the
text supervisor before the API in a separately approved idle-window rollout;
old clients remain compatible with the new supervisor. Reverse that order for
rollback. No automatic request replay or token/history restoration is permitted.
No services are changed by source publication. See PROVIDER_TOKEN_USAGE.md for
binding, optional encrypted archival and the unresolved preflight-count boundary.

## Compact Chat Source Integration

The compact adapter is source-only and opt-in. No installed descriptor, service,
model, login, phone or HTTP route changes. Trusted factory must select the separate
compact prompt, attach the existing HistoryStore as chat_archive, and provide an
explicit window/focus plus independently admitted model token accounting. Do not
use synthetic test counters for production admission. Default factories remain
legacy. Existing encrypted chat schema/budgets retain an optional bounded receipt;
there is no migration or archive replacement. Verify all writer compatibility and
exact-head backend/native/source CI before any separately reviewed host opt-in.
Disable by restoring trusted factory/policy composition, preserving receipts and
unknown outcomes; do not replay jobs, reset guards or restore old medical history.
See COMPACT_CHAT_ADAPTER.md for remaining coverage, dense-load and rollout gates.

## Internet APK Distribution

The dedicated static HTTPS update route, verified release preparation, atomic
publication and affected-nginx-only rollback are documented in APP_UPDATES.md.
This route does not restart or replace the AI API or its authenticated profile.

## Authenticated Daily Facts Host

Source adds optional actual-bool owner `daily_facts`, defaultfalse. No installed
descriptor/unit, timer, key or database is changed by this stage. One nonroot
API-lifetime task prepares only explicitly opted-in device facts after local05:00,
with current grant checks, completion deduplication and bounded failure retry.
No model/profile admission, daily_reads enablement or public settings route.
Run new service/schedule/owner/auth/subscription/lifecycle tests and full backend,
then inspect exact-head Verify. Before actual opt-in, every history writer must
support schema6. Do not copy tokens, reset claims or roll back to an incompatible
old API. A disable/restart must drain in-flight facts before resources close.
See DAILY_FACTS_HOST.md for limits, revocation race and separate live acceptance.

## Daily Facts Opt-In

Source adds internal schedule read/CAS-write/one-shot producer operations only.
No existing descriptor, host, cron, systemd timer or phone setting is changed.
Opt-in explicitly migrates the dedicated synthetic/test store to schema6; reads
do not migrate. Run schedule/report/source/history/queue/result and full backend
tests plus scoped lint/compile before source publication. Actual host composition
must verify current device/owner consent and run bounded synchronous work off the
event loop; no timer or live model call is permitted merely by this source stage.
All writers must understand schema6 before an actual opt-in. Binary-only rollback
to code supporting only1-5 will refuse the database; preserve data and restore
compatible source, never reset schema/drop tables/restore old medical history.
Details and the separate operational gates are in DAILY_FACTS_SCHEDULE.md.

## Android Server Archive

Source adds a separate encrypted DAILY_ARCHIVE slot and native Reports reader;
no clinical database migration or server descriptor/unit change. Existing owner
daily_reads admission remains false unless separately reviewed and enabled.
Run focused client/cache/security/Focus/native tests, then full Android quality
and actual native raster inspection. Verify the exact pushed SHA, not only local
success. Do not use a real model request, patient upload or database copy for QA.
No APK installation is implied; existing alerts/services must not be disabled.
Cutoff refresh is visible read-only work, not actual server05:00 dispatch.
See ANDROID_SERVER_DAILY_ARCHIVE.md for cache/retention/rollback limitations.

## Daily Result Read Admission (2026-10-17)

Source adds optional protected owner descriptor `daily_reads` (actual bool,
defaultfalse). No installed descriptor, unit, proxy or server is changed here.
Before a separately approved rollout, run daily-read/owner/history/archive/source
tests and the full backend suite; verify exact-head CI. Reuse existing protected
owner roots and signed routes. No key, profile, new root or migration is needed.
Enabling reads does not enable05:00 facts/dispatch or model/publication admission.
An approved rollout must check actual signed same-device responses and denial
after revocation, preserving current/historical source labels. Returning the
flag to false removes the routes; do not downgrade or restore history schemas.
Never use a real model request or copy a phone database to test this read stage.

## Meal Preparation And Automatic GI

Android-only source, no Room schema, backend, provider or device settings change.
MODEL_ESTIMATE uses existing nullable meal metadata with an explicit reference;
older applications ignore the unsupported source instead of treating it as a
known GI. Run source/persistence/history/unchanged-clinical-output regressions,
native compact/large-font and finite-frame checks, then full unit/lint/build.
No test food, bolus, target or sound is needed. A known future meal timestamp is
not clinical readiness; do not enable the isolated timing research via this UI.

## Food Photo Saved-Session Repair (2026-10-17)

Run ServerMealPhotoSessionTest with existing photo gateway/protocol/repository,
connection, preparation and native review tests, then full Android test/lint/build.
Check saved/unloaded/expired access, 401, text-only capability, missing session,
pending activation and credentials removed between readiness and submission.
Phone installation is a separate `adb install -r` on the intended existing device;
do not disable, uninstall, clear data or send test food/targets.

Read-only host projection on 2026-10-17 confirms owner descriptor v2 has no vision
endpoint while API/text are active. This APK repair cannot enable image inference.
No fake capabilities, text tunnel, copied login profile or replay is permitted.
Vision rollout requires independently admitted decoder/native/channel and an
explicit bounded synthetic provider test; preserve owned-config rollback.
For an existing trusted text refresher, --text-admission may point to its root-
owned protected descriptor. Only verified public image/egress follows renewal;
model/private identity remains pinned. Keep the photo POST body allowance local
to its exact route (3 MiB); existing text body limits must not be widened.

Owner rollout was explicitly authorized and passed one synthetic provider test
and independent API/proxy checks. Preserve owned config backups; never copy login
tokens, reset a guard or replay that test. Graceful nginx reload is asynchronous:
wait for observed drain before checking sustained idle and restarting API, then
verify effective photo/text size limits. Anonymous probes must remain refused.

## Local Nightly Advice

No backend deployment/schema or Room migration. A fixed local aggregate key
and unique network-free advice worker are added; startup requests catch-up.
05:00 uses the WorkManager next override, not a critical/system alarm. Power-save
defers work. Test source limits, duplicate/restart/cancellation, local clock/DST,
stale display and existing Focus/clinical report behavior. See NIGHTLY_ADVICE.md.
Do not claim actual overnight delivery from a local synthetic test or green CI.

## Chat Motion And Attachments

Android-only source, no Room/server schema or service change. Existing server
TEXT limits remain4096codepoints/8192bytes; no image/PDF activation. Run focused
attachment/route/Focus/motion tests and full Android test/lint/build, then inspect
exact-head Verify. Native raster/video evidence stays private. A built APK is
not USB/provider acceptance. See AI_CHAT_ATTACHMENTS.md for exact route limits.

## Owned Daily Dispatch (2026-10-17)

Source-only execution/transport integration. Default host CLI remains chat-only;
--daily-socket explicitly binds the dedicated channel under the same protected
parent and shares one capacity/unknown-stop gate with chat. No unit/config/timer,
new dependency, public daily route, phone or therapy setting changes here.

Before activation run daily execution/dispatcher/supervisor, legacy executor,
ledger/admission/results, text/vision and host suites plus full backend. Preserve
unknown ledger/profile state; do not recreate old claims after startup. A
handoff-only crash can obtain a first-ever ledger admission only after authentic
retained work and fresh source verification. Result-loss after success is not a
reason to repeat inference. See DAILY_DISPATCH.md for exact remaining gates.

An actual deployment needs both socket/PID readiness checks, exact runtime/model/
egress and stop verification, opted-in device/zone schedule, protected result
reads and explicit source/schema rollback assessment. Existing365-day expiry
and encrypted result schema are retained; no live migration is performed here.

## Native Moderation Compatibility (2026-10-17)

The first reviewed frame-only rollout composed live base3b81c2a5 with two modules
from59bba270. Full compatibility suite:1160 passed/27 opt-in skips; both exact-head
Verify runs passed. Two source-path drop-ins preserve the old interpreter,
dependencies, descriptors and private storage. Public unit rollback copies only;
no credentials/history copied and no database/schema migration.

Readiness and both standalone/parent-contained no-turn handshakes passed. One
large synthetic supervisor request failed with positive stop/idle. A separately
reviewed diagnostic identified an unhandled turn/moderationMetadata event. The
method fingerprint matches the pinned generated ServerNotification schema. This
is a protocol defect, not proof of the cause of the earlier three user failures.
The additional source fix needs its own tests, rollout and real answer receipt.
Do not replay prior jobs or reset uncertain profile claims. Nightly05:00 remains
unconnected; this compatibility stage grants no clinical/therapy authority.

## Full-Context Native Frame Correction (2026-10-17)

Source-only correction: the shared bounded native pipe/parser line cap covers
the existing 128 KiB input with worst-case JSON escaping. No runtime binary,
network admission, service, schema, model or profile change is performed here.
The original contained-text design's 64 KiB line assumption is superseded by
6*131072+1024 bytes; its 1 MiB session and other independent limits remain.

Before any rollout, run the full backend suite and the native text/process,
supervisor/host, contextual-chat, vision and daily-worker checks. Publish only
reviewed source/tests/docs. Stage a new immutable server image under an explicit
operational plan; do not edit an active pinned image, copy login credentials,
reset uncertain claims or automatically replay failed/unknown model jobs.
An actual bounded provider test and intended-device result remain separate
acceptance gates. This repair does not enable the 05:00 daily review pipeline.

## Foreground Glucose Alarms

The new local runtime is off by default; source publication does not enable it
on any phone. Opt-in is in Alerts. It uses a private specialUse foreground
service, SCHEDULE_EXACT_ALARM access and a bounded partial wake lock. Android can
show each current critical repeat as its next system alarm. Denied access or
foreground-start refusal is visible; no permission is auto-granted. Play Console
special-use declaration/review remains a separate distribution requirement.

Disable the new toggle to withdraw its owner and cancel its pending clock;
existing native initial alerts remain available. Global OFF 30/60 still pauses
all existing risk deliveries. No schema migration, therapy preference change or
new server operation is part of this stage. Phone installation and deliberate
audio/vibration/locked-screen/Doze acceptance require separate authorization.

Platform references checked 2026-10-16:
- https://developer.android.com/develop/background-work/services/fgs/service-types
- https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start
- https://developer.android.com/about/versions/14/changes/schedule-exact-alarms
- https://developer.android.com/reference/android/app/AlarmManager

## Local Observation Runtime

Additive install-bound preference, default off. No Room schema, dependency,
manifest permission or external server change. Existing source/TLS settings and
therapy permissions are retained. Separate local periodic work runs without a
network constraint and is cancelled on disable/power-save or therapy runtime
handoff. Turning this option off is not a global source or therapy stop.

Run LocalObservationSettingsTest, LocalObservationRuntimeTest, SetupScreenTest,
bootstrap and reactive worker policy tests, then full Android quality. Retain
native small-screen/large-font evidence. Phone source reception, reboot, screen
lock and power-save return still require separate acceptance; Android restrictions
can deny foreground start. No claim of independent alarms or guaranteed delivery.

## Local Diary / Room35

MIGRATION_34_35 adds one independent journal table and its occurrence-time index.
There is no backfill, clinical mutation, changed dependency or destructive
fallback. Verify the file-backed34->35 path, older supported tails and restart.
The previous schema34 APK cannot open35: use a reviewed forward fix, not an
APK-only downgrade or stale therapy restore. Source publication is not approval
to install, stop the app or copy medical data from the phone.

Run LocalDiaryRepositoryTest, LocalDiaryMigrationTest, LocalDiaryViewModelTest,
LocalDiaryScreenTest, SetupScreenTest and supported migration tests, then full
Android unit/lint/Kotlin/debug-build. Physical-device acceptance remains separate.

## First-Start Checklist (2026-10-16)

Additive setup-only preferences and native UI; no Room schema, dependency,
manifest, service or remote deployment changes. Older application versions ignore
the new checklist keys. Equipment choices preserve all existing clinical and
connection settings; this stage does not enable no-AAPS background forecasting.
Complete local quality and exact-head Verify precede source-stage publication.
APK installation and actual device permission/navigation acceptance are separate.

## Immediate OFF Source Repair (2026-10-16)

Existing control entry points now revoke the admitted delivery child and shared
audio/vibration before waiting for Room. Control revision is process metadata,
not a database migration or substitute for persisted OFF. Parent cancellation,
failed write and exact cleanup remain visible. No permission, service, scheduler,
dependency/default, host/model or phone operation is included. Full Android
quality passed5868 cases/6 existing skips/0 failures or errors in20m24s; lint
has0 errors and unchanged379 notices. Debug v2 signature verified. Exact-head
CI remains required before source-stage completion.
The native INITIAL lock/volume handoff remains a prerequisite to real repeated
runtime activation; do not infer it from the current pre-lock cancellation fix.

## Audio Ownership And Focus Follow-through (2026-10-16)

Source/controller/UI changes only. No schema, dependency, manifest, service,
permission/default, model request, host or phone operation. Exact request fencing
is not the complete source/foreground/native INITIAL handoff and does not arm
AlarmClock or enable acknowledgement. Saved legacy cloud history is not the new
per-device daily results service; rendering it cannot activate a nightly model.
Full Android quality and exact-head CI precede source-stage completion. Phone
installation, locked-screen/sound/volume acceptance and daily server activation
remain distinct authorized operations. See the feature-completion track plan.

## Accepted Alarm Source Stage (2026-10-16)

Glucose and delivery-diagnostic accepted paths now expose checked source feeds;
no consumer arms a second sound owner. No schema/migration, manifest, service,
permission, setting default, phone, model/server or therapy operation. Keep
existing native initial delivery until an explicitly admitted single-owner
handoff is verified. Source publication/flow emission is not physical delivery.
Clock failure gives no source, never a fabricated current timestamp. Boot is read
once per process; after reboot/startup fresh accepted evidence is still required.
Targeted555 synthetic cases and final full tests/lint/compile/build passed:
5820 cases,5814 pass/6 existing skips,0 failures/errors; lint0 errors and the
unchanged371-warning/8-hint baseline. One first-run TLS startup failure did not
recur in isolated or full retry; retain its original report. Exact-head source
publication/CI is a separate gate, never phone acceptance.
Plan: superpowers/plans/2026-10-16-accepted-alarm-sources.md. Do not disable or
install on a phone, exercise sound/volume or infer clinical validation from CI.

## Alert Fatigue Source Stage (2026-10-16)

No schema/migration, service/permission/default, source adapter, phone or therapy
operation. Inactive policy/executor support now uses one soft clip, noncritical
backoff and journal-derived shared cooldown. The lookback query materializes at
most65 rows; over64 or invalid history prevents new soft admission, not critical
selection. Critical selection/retention skips that query. Total SQL scan cost
requires retained-history profiling before runtime activation; no CPU claim.
Continue the approved source/foreground/wake/ACK/OFF integration before claiming
live repeat delivery. Preserve urgent native fallback and single audio ownership.
Build/CI does not install an APK, arm alarms or prove physical/human delivery.

## Initial Alert Delivery Source Stage (2026-10-16)

No schema, permission, service/alarm, setting default, server or phone operation.
Normal accepted alerts now precede the existing therapy stage, with independent
ordinary failure handling and truthful selected-channel/partial-channel reports.
Source-change and writer authorization remain unchanged. Source/build/CI is not
APK installation, runtime arming, physical audibility or clinical validation.
No disable/force-stop, backup, test sound or therapy command is authorized here.
The repeated runtime, source bridge, ACK/OFF wake ownership and intended-phone
acceptance are separate remaining stages of the approved reliable-alert design.

## Daily Capacity Source Stage (2026-10-16)

No operational caller, deployment, migration, timer or profile change. The new
ledger method issues fresh daily capacity only inside an idle transaction;
legacy daily claims now yield to queued non-daily work. Existing metadata schema,
normal reservation/settlement and no-replay uncertain-stop rules are retained.
Do not feed a returned RUNNING claim to the old QUEUED-only executor or build an
API accepting arbitrary claim receipts. A dedicated owned lifecycle/provider/
supervisor, positive stop, source/queue/result coordination and opt-in schedule
must be integrated and separately admitted. No preemption/host latency guarantee.
See DAILY_IDLE_CAPACITY.md; no host/model/phone/therapy operation in this stage.

## Daily Result Source Stage (2026-10-16)

Only an explicit stopped-successful internal result write adds schema 5 and its
result table/index after preserving schemas 1-4. Read paths never migrate. Shared
quotas/purge and fixed report-day+365d expiry include results. Failed payload
writes roll back, but committed additive migration can remain. Source supporting
only schemas 1-4 cannot open 5; do not downgrade, drop tables or reset versions.
No host/database/backup/restore operation is performed here.

The ledger and device store do not commit atomically. Preserve a successful job
with missing result as RESULT_UNAVAILABLE; never auto-infer again. Lost write
acknowledgement is recovered by original job identity, not a new request.
Unknown stop still holds shared capacity. Provider/supervisor/priority/schedule
and public advisory-quality admission remain separate. No server/profile/API/
medical-data/model/phone/therapy activation. See DAILY_REVIEW_RESULTS.md.

## Focus UI Source Stage (2026-10-16)

Focus+ (2026-10-17) adds two fixed Room telemetry projections and an independent
local 05:00 WorkManager task, with no schema migration or server activation.
Old successful display survives an unsuccessful refresh; no remote request is
replayed. Keep package/signing identity, do not disable/clear Copilot for backup.
See AI_FOCUS_PLUS.md; build/CI and intended-phone acceptance remain separate.

Native navigation/presentation only; no database/config/provider migration.
Source approval/build and synthetic native rasters are not intended-phone
installation or real provider acceptance. Keep existing package/signing identity
and never clear/uninstall/disable Copilot for backup. No test therapy/alert/audio
commands are part of this UI check. Actual phone/version/permissions must be
verified separately before any explicitly authorized USB update. Server daily
result/schedule/photo admission is unaffected. See AI_FOCUS_UI.md.

## Daily Worker Source Stage (2026-10-16)

daily_worker is not wired into the public API or existing text supervisor. Do not
route daily envelopes through CHAT, reserve owner capacity while scanning, or
launch an uncontained native runner. No daemon, schema, profile, config, host,
model session or APK changes accompany this stage. Shared JobExecutor/ledger
settlement is reused; uncertain stop/clock or queue handoff outcomes must not
be replayed. A separate daily supervisor/atomic priority dispatcher and encrypted
result composition need explicit admission before opt-in scheduling/deployment.
See DAILY_REVIEW_WORKER.md for operational boundaries and remaining gates.

## Durable Daily Queue Source Stage (2026-10-16)

Only explicit enqueue adds daily_review_queue/index and commits additive schema 4
after preserving schemas 1-3. Reads/get/handoff/cancel never migrate. Failed payload
writes roll back rows; an already committed additive migration may remain. Queue
payloads share quotas/purge, distinct AEAD purpose and fixed report-day+365d expiry.

Old source supporting only schemas 1-3 cannot reopen schema 4. No reverse SQL,
table drop or version reset is a rollback. No host/database/backup operation is
performed here. Future rollout requires compatible source and coordinated protected
restore/ledger reconciliation; do not replay reservations from an older snapshot.

Handoff is one-shot even after commit succeeded but acknowledgement/deadline
failed. Preserve the marker/unknown outcome rather than automatically resubmitting.
No model, worker, slot reservation, schedule, capability or public route is enabled;
actual dispatch arbitration/containment/result settlement remain separate.
See DAILY_REVIEW_QUEUE.md.

## Checked Daily Review Boundary Stage (2026-10-16)

Only trusted synchronous read helpers consume the source check; future async
orchestration must offload them using the existing worker-thread convention.
They perform no network/model work and hold no read lock across inference.
No schema, service, descriptor, route, provider policy, scheduler, runtime/profile,
phone or therapy setting is changed. No public capability is advertised.

Checked work must be prepared again immediately before future dispatch if it
waited in a queue. Checked replies still need independently admitted quality and
a coordinated fresh check/durable publication boundary; they are not stored here.
See CHECKED_DAILY_REVIEW.md. Legacy unverified preparation remains unchanged and
must not be used to bypass the new trusted boundaries in a future daily worker.

## Frozen Source Check Stage (2026-10-16)

Only internal read helpers and synthetic checks change. No migration, service,
descriptor, profile, scheduler, public API, model, phone or therapy operation.
The existing protected file/lock/transaction/deadline bounds are shared by report
selection and source replay; reads never enable archive schema 3. Source evidence
at retention is unavailable, never repaired by extending expiry or restoring old
records. No worker consumes the check; source deployment remains separate.
See DAILY_SOURCE_VERIFICATION.md for the actual versus replayed cuts and limits.

## Daily Review Contract Source Stage (2026-10-16)

Only internal preparation and reference checking are implemented. The exact
source result schema and hash accompany canonical work input; result text stays
NOT_ADMITTED. No server descriptor, capability, public enum, native policy,
supervisor, source root, service, profile, timer or database schema is changed.
No provider or real-device test is included. Pure helpers are not deployment.

A future worker needs to consume independent frozen-source verification, a
separate explicitly admitted DAILY_REVIEW policy, shared profile/ledger ownership,
encrypted work/results and no-replay uncertain-state handling before opt-in
scheduling. Matching supplied hashes, valid JSON or REFERENCES_ONLY cannot
substitute for those gates. Keep the trusted model/reasoning choices unchanged.

## Daily Facts Archive Source Stage (2026-10-16)

No deployment or operator invocation accompanies these internal methods. Only
save_daily_glucose_report explicitly enables archive schema 3; it first preserves
the additive chat schema 2 and adds the report table/index. Failed report writes
roll back payloads; a successfully committed additive migration may remain.
Old source accepting only schema 1/2 cannot reopen schema 3. Do not downgrade an
enabled store, delete the report table or reset its version. A future operational
rollout needs a protected consistent copy/restore gate and compatible source;
retain original stores and uncertain work rather than attempting reverse SQL.

Existing purge and shared row/byte quotas include reports. Reports expire at
their original civil-day end plus 365 days, not the time of a refresh/retry.
Logical date/zone has at most 32 revisions; maximum encoded report is 512 KiB;
list reads return at most 20 authenticated summaries under SQL/wall-time bounds.
Reads never migrate, dispatch a model or rescan source history. An explicit
source check is a point-in-time comparison, not continuously monitored freshness.
No public/signed/admin route or background caller is enabled by this stage.

## Owner Text API Rollout (2026-10-16)

Source3b81c2a5 has passed both exact-head Verify runs (all six jobs). The
owner-authorized Linux rollout now runs a separate nonroot HTTP API behind TLS
and the existing proof/attestation boundary. The root Unix text controller alone
launches the distinct contained native identity. Private keys/stores remain
separate from the preserved owner profile; neither the API nor operator copied
login credentials. No medical payload was used for deployment tests.

Observed gates: differential permitted/denied TLS/BPF probes, actual empty-profile
kernel/start/stop/IO checks, protected nonroot API startup, and exactly one real
synthetic text turn (OK,9.085s,no retry). Independent checks found an idle original
profile and no running native job. The target-host synthetic store/context/API/
attestation/year suite passed170 tests. Its210241 encrypted records read in3.664s
cold,1.1319s verified-cache,3.4781s after restart and3.6287s after another writer's
revision, within unchanged budgets;239.7MiB peak,zero swap. These are synthetic
capacity results, not actual phone archive completeness or clinical accuracy.

The admitted deployment has PID-bound supervisor readiness, actual HTTP readiness,
socket activation and a singleton public-only admission renewal every5minutes.
Each renewal rechecks DNS/TLS and differential network denial before publishing a
12-minute descriptor; it cannot change the model, profile, peer or private binding.
Failure closes only the owned API/socket. Recovery of an unknown profile/job claim
is still manual, never a reset or replay. Known public image inventory is bounded;
reaching its maintenance limit also fails closed. Trust snapshots remain separately
operator-pinned: renew them under controlled drain before their seven-day freshness
limit. A successful network renewal does not renew Android trust.

Only the owned nginx site changed, after an exact private rollback copy and syntax
checks. It streams128KiB-bounded API bodies without disk body/response spooling or
request logging, preserves signed headers/raw request paths and strips forwarded
identity. Eleven public TLS negative checks passed, including401/400/404/405/413/
415 behavior. Owned API closure yielded public503; restart restored401. A401 is
access rejection, not a successful signed model exchange or device activation.

No enrollment credential has been issued. Real hardware attestation, signed
phone history/chat and app UI acceptance remain pending because ADB is absent.
Public routing is the authenticated enrollment surface, not a bypass of that
device gate. Do not replace hardware proof with a synthetic root or bearer-only
fallback. The image supervisor/photo capability, daily model worker/schedule and
private admin are not enabled by this text deployment. Do not claim full AI
feature completion from the synthetic text receipt.

Rollback: close the owned API/socket, drain its exact controller/native invocation,
retain encrypted stores and any uncertain claims, verify the current site matches
the installed digest and restore only its saved original site before nginx test/
reload. Preserve original profile tokens and metadata. Operational configs,
private receipts and logs are not source-publication artifacts.

## Photo Supervisor And Decoder

The source CLI is app.ai.vision_supervisor_host, with the closed protected
configuration and separate public-image/user requirements documented in
[VISION_API_DECODER.md](VISION_API_DECODER.md). The optional owner vision socket
must not be configured until that host is independently admitted. No installer,
root HTTP process, automatic credential copying or nginx change is included.
Run the new decoder/supervisor/host and owner API suites, then the full backend
gate with pinned Pillow. Synthetic privileged records are not production proof.

## Native Meal Vision Verification

Focused gates: test_ai_native_vision, test_ai_contained_vision and the existing
native-text/process/profile suites; then full backend/tests with pinned Pillow.
See [CONTAINED_MEAL_VISION.md](CONTAINED_MEAL_VISION.md) for limits and pending
rollout gates. The native transport does not activate owner_api or replace the
public nginx503. Reuse the original guard binding, never initialize over it.
No image through the text supervisor, root HTTP API or capability-only bypass.
Rollback of a future admitted service must drain its exact invocations, retain
uncertain claims and private storage, and restore only its owned proxy mapping.

## Combined UI And Photo Gate

See [COMBINED_UI_PHOTO.md](COMBINED_UI_PHOTO.md) for exact source scope,
local synthetic capacity evidence, per-100g schema and inactive server boundary.
Install pinned backend requirements (now including Pillow 12.3.0) for image
decoder tests; missing optional decoder skips do not establish image readiness.
Run full Android unit tests, lint and assemble before the in-place device gate.
No service/profile activation or provider request is implied by source CI.

## Core Room Export

The independent server_ai_export_index_v1.db is created lazily in Android's
noBackupFilesDir; it contains hashed keys, fingerprints, times and progress, not
medical payloads. server_ai_room_export_pending_v1.bin holds an AES-GCM atomic
page with a separate Keystore alias/AAD from the existing signed transport plan.
Never clear either pending journal to unblock an uncertain send, regenerate a
decryption key, or move an index between device bindings. Clinical copilot.db
schema/version remain unchanged. Downgrading the app does not delete journals.

Local source checks: ServerAiRoomHistoryTest and RoomAiHistoryMaintenanceTest,
then existing ServerAi* tests plus the common testDebugUnitTest/lintDebug/
assembleDebug gate. Synthetic native SQLite checks are not actual Keystore,
power-loss or phone/server evidence. Inspect coverage and fresh signed requests
on the admitted device before considering the path operational.

For a fixture-data-only correction after a full run, keep the original suite
report and unchanged production lint/APK evidence; rerun the affected tests:
`./gradlew :app:testDebugUnitTest --tests '*ServerAi*Test' --tests '*RoomAiHistoryMaintenanceTest' --tests '*AiChatRepository*Test' --tests '*AiChatContextSummaryTest'`.
The core-history stage's full 5625-test execution found one invalid classification
fixture; after AAPS_REAL correction, 150 affected tests passed. Do not describe
that scoped rerun as a second complete-suite execution.

## Owner API Source Entry

The optional standalone `app.ai.owner_readiness` pre-start check is frame-free:
it checks both existing text/image sockets against their systemd MainPID and
kernel peer credentials, ownership/mode/link count, then rechecks path/PID across
connect. Run as the existing API user. Both service dependencies/orderings must
be explicit; metadata presence or Type=simple alone does not prove readiness.
Missing peer credentials or a timeout must reject startup, never bypass the
worker. It sends no request bytes, opens no profile/database and changes no
model. Focused tests: test_ai_owner_readiness.py and test_ai_owner_api.py.
Operational admission remains separate: install only a reviewed immutable helper,
preserve any touched descriptor/unit, drain the API before trust renewal, verify
actual readiness and public denial, and retain prior data/uncertain job claims.
For the official CRL, Google documents Cache-Control as its refresh interval:
https://developer.android.com/privacy-and-security/security-key-attestation .
An old Date alone is not a stale response when a consistent Age remains within
that lifetime. Bound accepted cache age to24hours, reject missing/inconsistent
freshness, future dates and duplicate fields, and record the actual checked time
only after a verified HTTPS response. Never extend the existing seven-day local
admission limit or renew it from an old local file. Public401 demonstrates access
control/reachability, not a successful authenticated model turn.

Before provisioning, use protected owner configuration version2. Its attestation
object requires revocation_snapshot with exactly path and sha256, pointing to a
separate single-link0400/0600 regular file readable by the API. Pin the complete
official JSON response from https://android.googleapis.com/attestation/status;
do not translate serial keys into imaginary certificate hashes or replace a
failed fetch with an empty list. Set revocation_checked_ms from verified operator
intake, keep the existing freshness bound, and renew/restart under controlled
drain before that trust expires. The API never downloads trust from request data.
Retain the prior descriptor and snapshot when replacing them; no v1 fallback.

Storage validation runs concurrently with signed requests and worker admission.
Inventory must use independent checked descriptors, not a shared lifetime cursor.
The owner-api overlap regression checks ownership and descriptor cleanup; the
journal fault test must assert its injection actually ran after authentication
gates. Do not replace a503 assertion with retries that hide storage drift.

`COPILOT_AI_OWNER_CONFIG` selects the mandatory protected descriptor documented
in `backend/app/ai/owner_api.py`. Run `uvicorn app.ai.owner_api:create_app --factory`
as the dedicated nonroot API user, one worker, ASGI lifespan enabled, behind the
separately admitted TLS/proof boundary. Provision private storage and independent
keys; never copy login credentials into API configuration. No startup is performed
by imports or the normal backend app. Unresolved executing ledger claims require
trusted reconciliation, not database deletion or automatic replay.

Focused source gates: `backend/tests/test_ai_owner_api.py`, Android
ServerAiChatGateway/ServerAiHistoryClient/repository tests and AiChatContextSummary
tests, followed by the full backend suite and one shared Android unit/lint/build.
Worker source tests are not deployment or actual device evidence. Rollback of an
admitted deployment must drain only its owned service before releasing storage;
retain encrypted state, uncertain claims and the independent owner profile.

For narrow client corrections after a complete suite has identified an issue,
retain the unchanged suite evidence and run the affected client/legacy tests:
`./gradlew :app:testDebugUnitTest --tests '*ServerAi*Test' --tests '*AiChatRepository*Test' --tests '*AiChatContextSummaryTest' :app:lintDebug :app:assembleDebug`.
Record the full-run and focused-retest results separately, including skips;
do not label a scoped rerun as a zero-failure complete-suite run. Published CI
still runs the full suite on the exact new commit.
## Narrow Periodic Client Upload

Main integration imports only client transport/export/configuration/scheduler
dependencies. No server unit, credentials, API routes or medical schema change.
Full Android test/lint/assemble and exact-head Verify are required on the narrow
tree; the development-branch APK is not its verification. Do not install over a
newer-schema device as part of a source merge. Preserve pending encrypted plans
and export metadata across rollback; never restore an old medical database.
This does not activate server model jobs or publish an APK update.

## Trusted Supervisor Entry Point

PYTHONPATH=backend python -m app.ai.text_supervisor_host --help describes the
explicit root host entry point. The operator configuration schema, protected
socket directory, reload and rollback rules are in
superpowers/specs/2026-10-15-text-supervisor-host.md. No root service is installed
automatically; do not run the HTTP API under this identity. Never point it at
an unverified image/descriptor or initialize over the existing owner profile.
Focused suite: test_ai_text_supervisor_host, then full backend gate. All lifecycle
tests are synthetic; active BPF/TLS and actual root/systemd evidence remain required.

## Concrete Runtime Verification

Focused suites: test_ai_runtime_admission and test_ai_contained_text_process,
followed by the full backend/tests gate. Synthetic tests use temporary public
images, proc records and actual local child pipes; privileged systemd/guard
operations are substituted. No provider, login, medical payload or host mutation.

Instantiate SystemdTextProcess only in the trusted supervisor with operator-pinned
RuntimeImage, initialized SessionProfileGuard, OfficialEgress and fixed policy.
The image layout/ownership, exact argv/resources, expiry and rollback rules are
in superpowers/specs/2026-10-15-contained-text-process.md. The source has no
automatic installer, root socket binding or default API activation. Never reuse
the old offline auth mount or silently capture current metadata as new authority.
Expired address admission requires fresh host validation and an admitted image;
it cannot keep serving indefinitely from the one-time model-test receipt.

Production still needs active deny/TLS/BPF controls, separate API UID/socket
permissions and startup reconciliation. Verify stop under disconnect/restart,
protected storage/key/purge/restore/load and Android before claiming activation.
Rollback disables admission, stops positively bound own invocations and preserves
profile/control/database state. Never clear an unbound/unknown marker to resume.

## Local Supervisor Channel

Focused suites: test_ai_text_supervisor and test_ai_supervised_chat_transport.
Tests create private temporary Unix sockets and synthetic native transports only.
Full backend/Verify gates remain required. Use uv --offline when dependencies
are already cached; no provider/model request is part of these tests.

The host must supply the listener and independently admitted process factory.
Keep its socket under a supervisor-owned, non-writable-by-API directory, with
only the intended API group allowed to connect. Pin distinct numeric identities;
do not grant the API profile access, root, sudo or systemctl. Startup/recovery must
reconcile the existing durable ledger and SessionProfileGuard, not recreate them.
No root launcher/systemd template is installed by this source change. An ambiguous
stop disables new admission without deleting a claim, socket, profile or database.
See the2026-10-15 text-supervisor-channel spec for protocol and operational gates.

## Conversation Query Verification

Focused synthetic suites: test_ai_server_chat_query and test_ai_chat_query_transport.
Full gate: PYTHONPATH=backend uv run --no-project --with-requirements
backend/requirements.txt python -m pytest -q -rs backend/tests. The manifest now
pins pytest9.0.3 to address GHSA-6w46-j5rx-g56g; refresh the test environment from
that manifest. This does not deploy a runtime or close the default-branch alert.

The optional owner-scoped history injection mounts history/conversations with
its own4KiB/128KiB budgets. Preserve them in any future reverse proxy/client;
do not widen jobs or observational-history limits. Signed-body query only, no
URL credentials/filters or access logging. Cursor409 requires a fresh first
page; fixed membership is not a historical outcome snapshot. No migration or
database rewrite in this stage. Operational purge/key/load tests remain separate.

## Server Conversation Storage

The optional CHAT service accepts chat_archive=HistoryStore. No default route,
process or deployment is enabled. Focused synthetic suites: test_ai_server_chat
and test_ai_chat_archive_transport, plus existing contextual/job/history tests.
Run the full backend gate before publishing. No real payloads enter tests or Git.

First archive acceptance verifies the private key manifest and separately commits
additive schema1-to2 before storing data. Reads and observation writes do not
migrate schema1. Schema2 requires a compatible binary: disable archive admission
for rollback, retain the database/current key, do not restore an old snapshot or
drop the new table. The spec documents crash gaps and UNKNOWN completion.

Existing purge covers expired archive rows; production must schedule it for idle
devices and enforce backup retention too. Pending entries reserve128KiB in shared
quotas. Synthetic tests are not volume/load, power-loss or key-custody evidence.
Protected master-key provisioning, volume/backups encryption, restore and real
storage failure checks remain required before medical ingestion.

## Contextual Text Source Stage

Run PYTHONPATH=backend uv run --no-project --with-requirements
backend/requirements.txt python -m pytest -q backend/tests. Focused suites are
test_ai_native_text, test_ai_history_context and test_ai_contextual_chat. All
fixtures are synthetic; they do not launch systemd or call an upstream model.
No migration, server/profile replacement or APK is included in this change.

Before installing a production factory, independently pin native/catalog hashes,
the new owner profile/control identities, official egress and kernel limits. The
public API must not run as root. The narrow supervisor must serialize the profile,
verify before releasing input and positively stop the original invocation before
job settlement. Measure history scan/lock contention at the365-day volume; failures
must stay explicit. Configure archive/key/purge/restore and then exercise signed
device upload/chat; a synthetic adapter response is not deployment readiness.

## Inactive Profile Guard

backend/app/ai/session_profile.py is not installed or injected anywhere. Local
synthetic validation: PYTHONPATH=backend uv run --no-project --with-requirements
backend/requirements.txt python -m pytest -q backend/tests/test_ai_session_profile.py,
then the full backend/tests gate. Test children contain no credentials/medical
data and never call host systemctl; trusted stop behavior is injected on temporary
verifier objects. Existing stop-engine tests remain separate.

Production admission must freshly pin one canonical profile/control mapping and
immutable cgroup/slice scope. Never call capture to silently adopt drift. Keep
control state outside worker mounts. Initialize only an empty profile/control
root. Do not unlink lock/state to recover capacity. A reserved unbound crash,
orphan or uncertain stop is a refusal requiring separate trusted reconciliation;
only matching bound recovery uses the original stop verifier. No stale session
backup or orphan promotion. Atomic file/directory sync is not provider refresh
transaction safety. Actual runtime/credential presentation/adoption is not implied.

## Pinned Native Build Validation

The small compatibility fetch uses the public raw host at native_build's pinned
peeled commit, avoiding the unauthenticated API's shared runner rate limit. The
annotated-tag identity and original file hashes remain pinned. It adds no token
or custom User-Agent and does not change the native checkout/build workflow.

Native Client Candidate is a disposable build workflow, separate from Verify.
See tools/session_store/README.md. It fetches the fixed annotated tag and checks
the peeled commit, all clean checkout state, exact native lock/toolchain and the
three-path patch receipt before/after compilation. The upstream dev-small
profile and Rust1.95.0 are used without dependency/version substitution.
The official tag's lockfile has149 stale local package versions0.0.0 versus
manifest0.153.4. A separate Cargo workspace-only metadata update is admitted by
strict parsed equality except those fields; every external package/version/
source/checksum/dependency remains unchanged. Both lock digests are retained.
Tests and builds still require --locked. Additional deltas fail before compile.
Two compile jobs and a90-minute timeout bound the disposable Ubuntu runner;
production hosts and existing login roots are not build workers.

Isolation preflight executes no client. Native login library tests and the actual CLI build precede the no-network,
minimal-root, nonroot version smoke. A success is build/provenance evidence only,
not authenticated model access or production containment. Push builds upload no
binary. Owner-approved manual dispatch with retain_candidate=true retains only
the verified runtime candidate for seven days; see the tooling README for the
exact archive/digest contract. No release, deployment, keyring/profile mount or
real login is part of this workflow.
The same workflow now includes a separate synthetic native protocol harness;
see tools/session_store/README.md. It uses only a disposable private CA/loopback
peer, the development build's supported issuer override, six bounded negative/
cancel/termination cases and an independent namespace teardown check. Exact-source
execution is a gate, not inferred from unit tests. No real provider login,
token exchange, inference or production profile is involved. Explicit runtime-
change admission and actual production containment remain separate gates.
Keep existing runtime and owner profile intact.

The separate --exchange mode adds four synthetic-only cases after the original
six. Only this mode admits the exact OAuth form route and, on success, one fixed
synthetic0600 auth.json with strict schema/content/time/metadata checks. It verifies
account completion/readback and a separate native login status process with no
connection attempts. This is not authenticated app-server restart: pinned native
startup automatically fetches model metadata when already authenticated. Both
auth issuer and ChatGPT base URL stay local; no real provider/credential/signature
validation or model availability is claimed. CI retains no session/test-state
artifact; explicitly retained binaries contain only the bounded public build
candidate, provenance and notices. Never install from an archive's own digest:
independently pin the exact successful CI run/source and job-summary manifest
digest, verify the downloaded ZIP without extraction, then obtain separate
host/runtime-change admission. Production hosts are not build workers.

## Atomic Session Candidate

tools/session_store is an offline source candidate, not an installed runtime or
credential manager. Its README contains the locked Rust/Python checks and exact
upstream source provenance. The patch builder admits only the two pinned source
hashes and refuses an existing helper; it never reads an authorization profile.
The compatibility check applies the patch only to its own fresh public-source
copy and verifies dependency metadata, exact helper bytes and three changed paths.

The helper syncs a private sibling file before atomic replacement and the parent
directory afterward on Unix. Error after replacement is not rollback authority.
Killed writers may leave private orphans; no auto-promotion or stale-token restore.
File atomicity does not solve the provider-rotation/local-commit gap or certify
hardware power-loss behavior. Serialize profile runs and refuse ambiguous state.

Before server adoption: full pinned-source client build/provenance, explicit
runtime-change admission, bounded profile/orphan policy and contained actual
client TLS/headers/protocol/cancel tests. Never replace the installed binary,
mount a host keyring, copy old auth or start a real login from this candidate.

## Independent Login Profile Boundary

The owner selected a separate ChatGPT login and approved a new private profile.
Only an empty service-owned0700 directory has been created and independently
verified. It is persistent state, not one of the temporary transport-test roots.
Existing authorization files and profiles remain unread and unchanged. The
directory itself proves neither authentication nor successful model access.

Before first login, capture the pinned client's supported device-code protocol
with synthetic state in containment. Verify private-CA support, effective neutral
headers, exact methods/routes, no extra egress, cancellation and bounded private
state writes. Keep one serialized owner profile and preserve atomic refreshes.
Present a real one-time code only directly to its initiating owner, never to
application logs, this project's artifacts, model context or source control.
Login/provider refusal must not broaden routes or switch credential/billing mode.

Rollback can remove only the original newly created, still-empty directory after
identity verification. If state appears or identity changes, preserve it and
inspect; do not recursively delete it or overwrite it with an old snapshot.
No login worker, authentication gateway, model request or API is enabled here.

## Public Transport Validation Boundary

The owner-approved anonymous check recorded in AI_NOTES passed actual public
transport to both fixed names with matching local/upstream403 and verified TLS.
Before public HEAD, the trusted controller pinned fresh fixed-name DNS/public CA
inputs and proved same-address host-network deny/allow with a private test CA.
The latter cannot send HTTP to a public origin. Gateway upstream trust then used
only the pinned public bundle, fixed IPv4 addresses and fixed provider Host/SNI.

Only two public HEAD requests were made; no redirects, credentials, prompts or
model/auth routes. All13 controls and16 bound stops passed. Independent checks
confirmed own-file/process/cgroup absence and unchanged sources/public CA.
Operational roots/addresses/keys/manifests remain private, not installed services.

ObservedProbeProfile outputs only HTTP status, local TLS result and its own
upstream status. It uses curl's header write-out support, introduced in7.84.0;
see https://curl.se/docs/manpage.html#-w. Optional real-engine coverage is
backend/tests/test_ai_upstream_probe_nginx.py with NGINX_TEST_BINARY. Raw headers
or provider bodies are never receipts. Original profile semantics are unchanged.

A403 may be an anonymous-access or edge-policy refusal; it is not authenticated
model access or permission to bypass challenges. Login needs a separately reviewed
minimal profile, fixed-version protocol capture and owner participation. Do not
copy root/old authorization, broaden paths after refusal, expose privileged HTTP
installation, upload device data or claim personal AI readiness from this check.

## Synthetic Host Validation Boundary

The owner-approved bounded synthetic operation recorded in AI_NOTES passed13
cases using private TLS peers, isolated roots/namespaces and an aggregate
512MiB/swap0/25%-of-one-core/64-task slice. Each of17 original service invocations
was positively bound before stop; an independent read-only check confirmed own
file/process/cgroup absence and preserved source identities. Exact operational
manifests, certificates and evidence remain private, not a production launcher.

SystemdStopVerifier defaults to system.slice; slice_id admits only a canonical
v4 identity mapped to the fixed generated slice name. A loaded static unit may
clear InvocationID only after successful original-invocation binding and stop,
in the exact inactive/dead/blank-ID/empty-group state. Entry without the original
ID, a different ID/user/unit/group or populated descendant still fails closed.
Root validation compares device/inode, not proc symlink spelling. All Nginx HTTP
temporary paths must use the bounded /work mount, not distribution defaults.

Inactive unit definitions can remain in systemd's cache after own files are
deleted; do not claim they were collected or globally reload unrelated units
just to clear them. Unknown start/stop identity retains own files for inspection.
After an interrupted operation, inspect actual state before retrying; never
replace a missing receipt with a blanket stop/delete or reuse a job identity.

This synthetic result does not establish provider connectivity, independent
minimal login, authenticated neutral inference, malicious-context containment,
real workload cancellation, retained history or device chat. Those are separate
reviewed gates; no existing credentials, model profiles, phone or therapy are
implicitly authorized by these results.

## Transport Profile Source Stage

app.ai.egress_probe only generates profiles and validates status receipts; it
does not construct roots, install units or perform networking. Synthetic mode
requires two TLS Unix peers and an isolated test upstream CA; public mode requires
one independently verified public IPv4 per fixed provider and the public CA bundle.
The source has no mixed mode, runtime DNS, authorization or model request path.

Optional real-engine suite: set NGINX_TEST_BINARY to a trusted Nginx1.24+ binary
and run backend/tests/test_ai_egress_probe_nginx.py. It uses generated short-lived
test certificates and filesystem sockets only. Tests rebase filesystem references
for an unprivileged local directory, not the HTTP/TLS policy. Without the explicit
binary these checks skip honestly; ordinary unit tests do not prove live egress.

Before remote use, separately review exact root/library/config/certificate/socket
and unit manifest. Worker and gateway properties alone omit relay/namespace setup,
shared512MiB/25%-of-one-core/64-task budget, fixed launch environment, source/CA
provenance, active cgroup filtering and independent stop receipts for every role.
Do not compensate with host networking, broad mounts, credential reuse or a
privileged public API. Existing services/firewall/profile remain untouched.

## Personal History Storage Stage

This optional local library/API stage is not a server deployment. Default app
factories expose no history route. Compose HistoryStore and HistoryService with
trusted activation/proof services, one explicit owner and an externally supplied
32-byte master key; never place keys in history directories, URLs or logs.
The POSIX private directory must be owned by the runtime account with0700 mode.
Files use0600, local SQLite and per-device payload encryption; metadata remain
plaintext and production still requires an encrypted local volume and backups.

Records expire365 days after original event time; requests use original request
time. Successful writes purge expired own-file data and queries filter it.
HistoryStore.purge provides bounded maintenance for idle files; deployment must
schedule it and enforce backup/key expiry. No automatic maintenance service is
installed here. Key changes must fail closed, not initialize over existing files.

Before enabling real ingestion: capacity/OS quotas, private master-key custody,
audited restore and key rotation, current enrollment policy, monitoring without
payload logging and storage-failure tests. Preserve current deployment before any
separate rollout; never restore stale medical history to roll back application
code. Existing per-device files are never deleted or rewritten as a fallback.

## Environments

### Offline Runtime Validation Boundary

The owner-approved version-only operational check recorded in AI_NOTES passed
inside the existing offline root with the separate nonroot identity. Actual
properties and an invocation-bound stop receipt were inspected, then disposable
unit/cgroup/directory absence was independently checked. Private operational
evidence is retained outside Git; no credentials or medical payloads were used.

This is not an online worker or deployment acceptance. A version string does not
prove model compatibility, authenticated inference, provider-only network egress,
hostile-context containment, cancellation under load or contextual device chat.
The default API readiness remains unchanged. Any later online validation needs
its own reviewed exact-target plan, rollback and explicit authorization; do not
reuse the offline result as permission to mount credentials or upload history.

### Development

- Local Android development: `android-app` via Gradle.
- Local backend development: `backend` via Python venv + uvicorn.

## Current quality commands
- Android build: `cd android-app && ./gradlew :app:assembleDebug`
- Android lint: `cd android-app && ./gradlew :app:lintDebug`
- Android tests: `cd android-app && ./gradlew :app:testDebugUnitTest`
- Android typecheck proxy: `cd android-app && ./gradlew :app:compileDebugKotlin`
- Backend tests: `cd backend && .venv/bin/python -m pytest -q`

## Gaps to close
- Backend lint is not standardized (recommended: `ruff check` + `ruff format --check`).
- Backend typecheck is not standardized (recommended: `mypy`).

## GitHub Workflow

Repository: https://github.com/siriushex/toolsaaps (public).

After each locally verified stage: fetch origin, inspect divergence and the
complete diff, scan for secrets/private data, explicitly stage reviewed paths,
commit and push the feature branch to its upstream. Use a PR against main;
merging remains a separate decision. Do not use force-push, background auto-commit
or automatic pull over a dirty worktree. If new upstream changes overlap with
local edits, stop publication and reconcile them explicitly without losing work.

`.github/workflows/verify.yml` runs on push, PR and manual dispatch:
- Python3.12: install pinned backend requirements and run the backend suite.
- Java17 / Android SDK36: unit tests, lint and debug APK build.
- Read-only repository permissions, pinned action SHAs, bounded job durations.
- No provider keys, production connections, phone databases, signing keys or
  deployment steps. CI debug APKs are not uploaded and are not phone releases.

Confirm the remote branch SHA matches the local commit, then inspect the Verify
result. A successful push with queued/failed CI is not a passed verification.
CI does not prove device migration, delivery or clinical correctness. Retain
private device evidence locally and link only non-sensitive summaries in PRs.
Required-check branch protection is not enabled by this file; it must be
configured separately before claiming main is protected.

## Rollback basics
- Android: reinstall last known-good apk artifact on device.
- Backend: redeploy previous image/version and run smoke endpoint checks.

## Migration basics
- Any DB schema change must include migration strategy and rollback note in PR + docs.

### Room 33 To 34

Migration34 adds the independent meal-input observation table and two indexes.
No historical backfill, clinical/forecast/settings rewrite or model activation.
Native migration/restart/integrity and supported older tails must pass. A schema33
APK cannot open a migrated34 database; use a reviewed forward repair and fresh
coherent backup, never APK-only downgrade, stale therapy restore or destructive
fallback. This source stage does not authorize phone installation/disable/stop.

### Room 32 To 33

Migration33 creates the independent prospective forecast journal and two indexes
only. No backfill or clinical/alarm/meal mutation. File-backed migration/restart
and supported migration tails must pass. A schema32 APK cannot open migrated33;
use a reviewed forward fix and fresh coherent current-data backup. Do not restore
stale therapy or use APK-only downgrade. No destructive fallback. Publication
does not authorize phone installation.

### Room 31 To 32

Migration32 only creates the local alarm state/cycle journal and its scheduling
indexes. Existing clinical/history/settings/receipts are not rewritten. Before
any separately authorized phone update, retain a fresh coherent private backup,
test ALL migrations on a disposable current-data copy and verify on-device
opening without destructive fallback. The storage-only source stage does not
enable new audio and does not prove device delivery or CPU/RAM savings.

An old schema31 APK cannot reopen a migrated32 database. Do not apply the generic
APK rollback to this transition or restore stale therapy data. Use a reviewed
forward fix or a separately validated non-destructive downgrade after a fresh
current-data backup. No destructive fallback is added for31/32.

### Room 30 To 31

Migration31 adds three nullable GI metadata columns to meal profile overrides
and pending meal intents. Existing therapy/history/settings and profile timing
are not rewritten; old GI remains unknown. Synthetic migration/restart tests
cover retention, foreign keys and integrity. Verify a fresh disposable working
database copy before the authorized in-place phone update, never the live DB
through a test harness. Retain a coherent private backup and signed old APK.

The old schema30 APK does not support a database already migrated to31. Do not
blindly reinstall it or restore an old backup over later therapy. Recovery
requires a reviewed forward fix or separately reviewed non-destructive downgrade
with a fresh current-data backup. The generic APK rollback above is insufficient
for this schema transition. No destructive fallback is added for30/31.
