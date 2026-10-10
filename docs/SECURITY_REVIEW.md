# SECURITY REVIEW

## Eating Soon Target Context (2026-10-10)

- Explicit intent is derived from current resolved AAPS evidence plus one
  matching SENT command, not a note, UI selection, pending request or old
  Target Manager decision. Bounded payload/key parsing and duplicate rejection
  prevent ambiguous records from acquiring confirmed context.
- Context is part of dispatch identity and confirmed semantic fingerprints;
  absent/false context keeps the prior fingerprint encoding. Protective intent
  alone cannot bypass current forecast, freshness, delivery, arming, bounds or
  opt-in priority. Other manual writer-conflict behavior is unchanged.
- Supersession requires a privately constructed checked current target proof:
  either a newer manager target or independently confirmed Eating Soon, each
  matched to one unique SENT record. Its own current command is never discarded.
  No broad pending-command exemption, resend or new therapy writer is added.
- Private phone-history evidence is kept outside Git. Real-phone clinical
  delivery cannot be validated by synthetic unit tests or an APK installation.

## Pump-Link Source Publication (2026-10-10)

- Immutable exact accepted snapshot/episode, after durable transition/receipt
  writes. UI and receipt outcomes are not sound authority. Publication is
  withdrawn before superseding writes; ambiguous cancellation/failure stays null.
- Current read independently checks boot, elapsed/condition/deadline and exact
  publication after external clock callbacks; stale flow observations cannot
  authorize hardware. No fabricated clock/generation/TTL or reset of history.
  Previous-boot episode timing is not compared with the current elapsed epoch;
  only current accepted packet provenance establishes a post-reboot fault.
- Committed global OFF cleanup invalidates before legacy cleanup, without
  Room/monitor reentry. Delayed old coordination failure cannot revoke newer
  publication. OFF expiry alone does not rebuild a source or replay delivery.
- No consumer, arming, extra timer, network, permission/schema, volume/audio,
  risk detector or clinical writer. No raw sensitive payload or exception logs.
  Complete source/context mapping, OFF intent, channels/lifecycle/opt-in/UI and
  actual phone/night delivery remain release gates. Link status is not worn-pump
  or insulin-delivery proof. Synthetic checks do not close those device gates.

## Inactive Serialized Runtime (2026-10-10)

- Mandatory current source/OFF/capability context, no default-true authority or
  production constructor. Signals revoke admission/cancel synchronously without
  Room locking; hardware cleanup remains Main-owned and precedes journal finish.
- Lifetime ownership is separate from OFF transactions. Starting claims report
  exact tokens; read-only preview never adopts an execution owner's pending claim.
  Replacement claims wait for cancel/join and confirmed terminal cleanup.
- Abandoned exact claims get metadata-only UNCERTAIN with no new claim/replay.
  Corrupt journal and unknown cleanup stop/latch the runtime closed in process;
  constructing another actor cannot retry uncertain hardware automatically.
- Bounded snapshots and one conflated wake/nearest timer; no raw exception,
  clinical data/network logging, source detector/therapy writer, setting/schema/
  permission or device action. Current-context changes discard old selections.
- Synthetic ports test orchestration, not Android audio completion/hearing or
  locked-screen delivery. Source/service/channel/UI and real-device gates remain.
  Legacy initial delivery and urgent-low protection remain unchanged.

## Read-Only Multi-Source Selection (2026-10-09)

- Accepted evidence is separate from queue age/journal. Existing policy still
  gates freshness, boot/generation, OFF, enabled/armed state, ACK and timing.
  Selection cannot grant fresh authority or commit hypothetical claims.
- Inputs/reads cap at64 unique keys including owner. Duplicate/bad inputs or
  corrupt state fail closed; conflicting/unowned claims require recovery without
  adoption. Higher priority cannot overlap old execution: future runtime must
  cancel/join before a fresh committed START and side-effect admission.
- Real RED reproduced borrowed-input replacement during a Room read selecting
  a source whose corrupt saved state was never loaded. Freezing the request batch
  before reads binds subsequent selection to exactly the validated source keys.
- Actual Room OFF is read within existing shared lock/transaction. Preview never
  mutates claims/ordinals/progress/ACK/repeat and preserves cancellation errors.
  Checked elapsed scheduling is not a timer/Doze or notification guarantee.
- No production caller, network, raw clinical log, schema, setting, permission,
  detector/therapy writer or phone action added. Source/lifecycle/channel/UI and
  real-device gates remain; default legacy initial-delivery behavior is intact.

## Inactive Cycle Executor (2026-10-09)

- Room exact-claim admission is read-only, transaction/mute ordered and bounded.
  It cannot grant fresh accepted source authority or make hardware writes atomic
  with global OFF. Current source publication and exact cancellation remain
  required integration gates; no runtime constructs the executor in this stage.
- One new claim is executed at most once by the runner. Concurrency does not
  claim another source, adopt interrupted ownership or replay expired steps.
- Confirmed volume precedes sound; canonical urgent floor and exact step windows
  are retained. Real REDs reproduced cleanup cancellation, false FINISHED after
  stop failure and a missed step admitted during a slow hardware read.
- Cleanup revokes ownership, attempts independent releases/finish and always
  unlocks. Unknown cleanup is observable, not success; cancellation propagates.
  API start/stop requests are not proof of completion, hearing or human action.
- No new clinical detector/writer, network, permission, settings/schema, arming,
  service or device mutation. Existing single-player transport is reused.
  Missing notification/vibration/native fallback/async media failure/source
  coordination/foreground/wake/UI/device checks prevent live feature activation.

## Guarded Playback Transport (2026-10-09)

- New explicit cycle/step API has no production callers and cannot arm itself.
  Fresh committed claim/source/OFF/capability and urgent-floor authority belong
  to the future serialized coordinator, not caller-independent saved JSON.
- Canonical shape, absolute start/deadline bounds, exact-session cancellation
  and player/focus tokens prevent missed/stale callback ownership. Synthetic
  RED regressions covered clock-jump resource leakage and duplicate callbacks.
  A late RED also reproduced post-start admission extending the stop timer;
  the captured absolute end now bounds scheduling after that callback.
- New mode changes only audio attributes/focus for its admitted clips; no
  stream volume, route, ringer/DND, permission, network or therapy writer added.
- Clip resolver/gains and one existing player are shared. Failure vocabulary
  does not include raw exception payloads or clinical snapshots. Start API
  success is not hearing, human acknowledgement or reliable night delivery.
- Synchronous URI access and delayed platform timers are not hard real-time
  guarantees. OFF while playing needs exact owner cancellation. Complete
  foreground/wake/vibration/notification/source/UI integration and separately
  authorized real-device tests remain mandatory before activation.

## Alarm Volume Ownership (2026-10-08)

- Scope: volume-only helper plus STREAM_ALARM flags0 adapter, no runtime
  consumers, activation, permissions, settings, network or clinical change.
- Exact cycle binding, bounded elapsed admission and readback prevent stale
  callbacks or unconfirmed sets from granting progress. Callback cancellation
  and reentrancy were demonstrated RED then fixed. Cancellation propagates.
- The canonical first-target floor is enforced in this helper as well as the
  profile producer; a caller's quiet step cannot weaken admitted LOW_NOW70%.
- Observed override/unknown hardware ownership prevents restoration; cleanup
  compares current index/maximum and never retries a released lease. No route,
  DND/ringer or other-stream setters and no raw exception/clinical logs.
- This helper is not a clinical/capability authority. Its future serialized
  coordinator must supply fresh committed source/OFF/platform admission.
- Public volume observations cannot distinguish all gestures/route changes or
  atomically exclude system writes. No stronger ownership, human response,
  locked-screen delivery or resource benefit is claimed.
- Legacy urgent-low handling is untouched. Service/player integration and
  separately authorized real-device acceptance remain release requirements.

## Local Alarm Journal (2026-10-08)

- Scope: two additive Room32 tables and guarded local persistence. No network,
  backend, source-risk detector, service, permission, sound or therapy change.
- State/results use strict versioned bounded JSON with exact types, duplicate/
  unknown-field rejection and checked integers. Mirrored identities/revisions
  must agree; bad state fails closed rather than inventing an ordinal.
- New claims/results require fresh accepted policy evidence and current Room
  mute ordering. Persisted payloads do not grant source authorization.
- Outcome vocabulary records API attempts only, not heard/acknowledged,
  physical pump attachment or confirmed insulin delivery. No raw clinical
  snapshots, credentials or exception payloads are added to logs.
- Synthetic rollback/race/recovery/migration tests and full quality are required;
  source/CI success does not establish Android sound/Doze/device behavior.
  Private phone evidence and build artifacts stay outside publication.

## Food Display And GI (2026-10-04)

- Scope is display metadata, nullable Room31 storage, accepted telemetry and UI.
  No backend route, provider call, therapy command parameter or safety gate added.
- GI is finite0..200 with explicit user/catalog provenance and exact revision;
  optional invalid/unavailable data cannot block valid manual carbohydrates.
- Display work is bounded to5000 meals,145 points and16 KiB. Parser rejects
  unsupported versions, clocks, nonfinite/negative steps and timestamp overflow.
  Old/unmatched data cannot be relabeled as a complete accepted projection.
- Clinical invariance is covered by real-engine low/high/unknown GI tests.
  Coefficients are explicitly uncalibrated. No clinical benefit claim is made.
- Private phone databases, logs, images, APKs and backups must not be published.
  Full quality/diff/secret checks and device acceptance remain separate gates.

## Source Release Check (2026-09-13)

- The full candidate test suite detected an obsolete shared TLS `.p12` asset
  inherited from the old public branch. Removed that file from the release;
  current runtime identity storage is per installation. No old key was printed
  or reused. Historical revisions and older APKs are not erased by this change.
- Packaging verification scans main assets for credential containers and checks
  three legacy asset-loading references in `LocalNightscoutTls.kt`. It now also
  works in a fresh checkout with no assets directory.
- The server identity implementation is not a deployed inference service.
  Production trust roots, signer policy, fresh revocations, private receipt key
  provisioning and live job transport remain unverified release gates.

## Scope
- Android app ingestion, sync, automation, outbound action delivery.
- Backend API endpoints and scheduler jobs.

## Current checklist status

### MUST
- Verify secrets are never logged (API keys, auth headers, secrets).
- Validate all inbound payload parsing paths for numeric bounds and null safety.
- Ensure outbound treatment/temp-target writes use idempotency and source tagging.

### SHOULD
- Add backend input validation tests for edge payloads and malformed JSON.
- Add rate limiting strategy for externally exposed backend endpoints.
- Add explicit PII redaction policy in logs and docs.

### NICE
- Threat model diagram for Android local transport + local Nightscout emulator.
- Security CI job (dependency audit + static checks) with baseline allowlist.

## Findings log
- No structured findings recorded yet in this document.
- Next security thread must convert checklist into concrete findings with owner and due date.
