# Stock AAPS 4.0 Compatibility

## Decision

The owner requested on 2026-10-18 that compatibility be implemented primarily in
Copilot, with minimal AAPS changes. Do not transplant the old custom AAPS bridge
or modify dosing algorithms. The target under inspection is official 4.0.0-rc1,
commit d5e7f5bdbb4aaefa4eaaa89a46fba257ef396fa0. It is a prerelease.

The external receiver-admission patch and its explicit limits are described in
AAPS4_MASTER_ADMISSION.md. It is not a dose-algorithm change or permission to
replace the active phone application before the remaining migration gates pass.

## Confirmed Protocol Differences

- Stock `DataReceiver` handles `NS_EMULATOR` as glucose data through
  `MM640gPlugin`. Presence of that receiver is NOT treatment support.
- `LOCAL_TREATMENTS`, protected Copilot history receivers and bolus-entry activity
  belong to the custom 3.x build, not stock RC1.
- RC1 uses `NSClientV3Plugin`; the old `NSClientAddUpdateWorker` is absent.
- Its `core/nssdk/.../networking/NightscoutApi.kt` uses `/api/v3/status`,
  `/lastModified`, entries, treatments, devicestatus, food and profile. Authentication
  refresh uses `/api/v2/authorization/request/<refresh-token>` and subsequent
  requests use a bearer credential. This credential-bearing path must never enter
  ordinary access/audit logs.
- Collection history has modification cursors, distinct from clinical event time.
  First-load reads use `date$gt` or `created_at$gt`; edits and invalidations use
  `/history/<modified-since>`. Ignoring these filters silently loses or replays data.
- Current insulin settings are carried by `ICfg`: label, end/peak times in
  milliseconds and concentration. They must not relabel historical insulin.
- The optional xDrip export exposes profile, SGV, device status and treatments as
  broadcasts. It has no sender permission. Do not promote this to authenticated
  treatment confirmation or command support merely from an action name.
- RC1 also has native Client Control in
  `plugins/sync/.../nsclientV3/clientcontrol`: owner pairing, HMAC envelopes,
  persistent counters, expiry and signed ACKs through the v3 `settings` collection.
  `BatchPrepare` supports a `temp_target` action, returning a preview before a
  separate commit. Preparation or HTTP success is not application. This is the
  preferred candidate for acknowledged commands, subject to interoperability and
  safety verification; do not implement new AAPS command receivers prematurely.
- v3 notifications use the Socket.IO `/storage` namespace with authenticated
  subscriptions and collection create/update/delete events. With sockets off,
  the ordinary sync loop normally polls every five minutes; HTTP-only support
  must not be advertised as prompt Target Manager delivery.
- Storage `subscribe` uses the configured access token, while HTTP uses the
  refreshed bearer token. Test both credential lifetimes and revocation; do not
  assume the existing v1 Socket.IO authorization message is compatible.
- Stock `app/src/main/res/xml/network_security_config.xml` permits cleartext only
  on localhost/127.0.0.1 but does not opt in to user CA trust. Copilot's private-CA
  loopback TLS identity therefore needs an explicit trust compatibility check.
  Do not silently downgrade to HTTP or disable certificate/hostname validation.
  If stock configuration cannot trust that identity, limit any AAPS patch to a
  narrowly scoped loopback trust configuration and verify it on device; do not
  modify AAPS algorithms or export credentials to solve this transport problem.
- The old custom manifest includes `glucodata.Minute`; stock RC1's manifest and
  DataReceiver do not. Confirm the actual phone CGM source and a supported input
  route before migration. Do not remove an active custom CGM route merely because
  the new APK compiles.
  For JugglucoNG compatibility, stock DataReceiver accepts
  `com.eveningoutpost.dexdrip.BgEstimate` through XdripInbox/XdripSourceWorker and
  its source resolver includes JugglucoNG labels. This is a stock candidate, not
  proof that the phone currently emits that action or has selected that plugin.
  Verify the actual enabled sender/output and fresh AAPS reading before migration;
  do not silently replace the CGM route or infer it from stale preference keys.

## Implementation Sequence

1. Correct both existing legacy broadcast send paths in Copilot: require an
   explicit destination and actual treatment-extension evidence before using
   the generic glucose receiver. Preserve existing command ownership and unknown
   outcome reconciliation; failed relay must not create local clinical evidence.
2. Add authenticated v3 transport to the existing Copilot loopback TLS service.
   Reuse its private identity and local-only binding. Session expiry/restart,
   request budgets, credential redaction and v1 regression tests are prerequisites.
   Resolve the stock-client TLS trust boundary before claiming connectivity.
3. Add durable v3 collection revisions, stable record identity, tombstones,
   bounded history paging and exact filters. Preserve current source provenance,
   idempotency and invalidation. Do not implement history by returning current
   rows under arbitrary timestamps or claim unimplemented collections are synced.
4. Ingest AAPS uploads and export only permitted records. Implement authenticated
   storage notifications and `settings` semantics before evaluating paired native
   Client Control. Preserve the prepare/confirm/commit boundary, signed ACK
   correlation, durable counters and expiry. A target-only operation must never
   acquire a bolus, profile or running-mode side effect. Keep downloaded treatment
   evidence separate from applied-command evidence; uncertain delivery cannot
   trigger an alternate-channel retry.
5. Verify a stock RC1 client against synthetic TLS fixtures, including reload,
   revisions, clock skew, disconnection, denied credentials and profile/insulin
   context. Include revoked pairing, stale/replayed ACKs, expired previews,
   disabled client control, concurrent commands and restart during confirmation.
   Expose unsupported features and required user settings explicitly.
6. Only then update the connected device after signer, settings export and
   CGM/pump compatibility checks. No automatic therapy test commands.

## Acceptance Boundary

On2026-10-20 two external mock regressions against the unchanged pinned master
confirmed stale command time across settings retrieval and the Executing ACK
write. In both cases the mock executor was called after the signed validity
deadline. This is not a live pump test or a fix. Keep production native admission
closed; a minimal end-to-end deadline fix and corresponding master/executor tests
are required before enabling targets or replacing the phone's therapy app.

Persistent document storage is now separate from the route adapter; see
LOCAL_NS_V3_STORE.md. Room 36 contains revisioned latest-state documents, not
backfilled clinical records or immutable snapshots of every intermediate update.
The typed entries adapter now exposes wire storage over authenticated TLS; see
LOCAL_NS_V3_ENTRIES.md. Immutable device-status create/read is described in
LOCAL_NS_V3_DEVICE_STATUS.md. Settings wire CRUD is now available; see
LOCAL_NS_V3_SETTINGS.md. The native target codec has a real SDK DTO/crypto and
TLS/settings fixture in AAPS4_TARGET_PROTOCOL.md. That fixture is not execution
of the master handler. Treatment wire CRUD/history is now implemented separately
(LOCAL_NS_V3_TREATMENTS.md), including exact native units and record ICfg.
Immutable profile catalog create/latest/history/read is described in
LOCAL_NS_V3_PROFILE.md. Food still fails501. Effective-profile/clinical ingestion
and live native control remain unconnected. Authenticated storage notifications
now read committed revisions, including direct journal writes; see
LOCAL_NS_V3_STORAGE.md. Pairing UI/runtime and repository native routing are now
registered (AAPS4_PAIRING.md); production target publication stays explicitly
blocked until clinical projection and master-handler admission are verified.

Legacy capability checks and the bounded refresh/bearer exchange do not establish
complete stock-AAPS compatibility. The auth-only stage is specified separately in
LOCAL_NS_V3_AUTH.md. RC1 requires permissions across all collections for its
whole-app uploader; partial entry permissions intentionally do not claim readiness.
An APK build or an SDK-level TLS test is not device acceptance.
For this stage AAPS source, phone settings and therapy remain unchanged. Existing
custom 3.x exchange must keep working. Baseline RC1 compilation is a separate
check from Copilot's regression suite and from the eventual on-device migration.
