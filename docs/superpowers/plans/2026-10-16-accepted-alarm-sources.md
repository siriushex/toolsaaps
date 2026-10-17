# Accepted Alarm Sources Implementation Plan

> **For agentic workers:** Use superpowers:executing-plans inline. The owner has approved the reliable-alert scheme and requested continuation; no new design question is needed. No native subagent tool is available.

**Goal:** Publish checked, short-lived glucose and delivery-diagnostic risk sources from their actual accepted event paths, without activating a second sound owner.

**Architecture:** Reuse existing clinical decisions, episode transactions and diagnostic confirmation. A shared internal feed handles clock/boot expiry, duplicate measurement windows, stable generation/queue age and revocation tickets, not clinical detection. Actual producers alone bind those windows to committed episodes; cached display/history is never sufficient. Existing native sound, global OFF, clinical thresholds and therapy stay unchanged.

**Tech Stack:** Kotlin, StateFlow, injected clocks, Room, JUnit/Robolectric. No dependency, schema, service, permission, preference or hardware change.

## Task 1: Checked Source Feed

Create `android-app/app/src/main/kotlin/io/aaps/copilot/data/repository/LocalAlarmSourceFeed.kt`
and `android-app/app/src/test/kotlin/io/aaps/copilot/data/repository/LocalAlarmSourceFeedTest.kt`.

- [x] RED: missing feed API, then verify actual expiry/duplicate/generation/revocation behavior.
  The core contract is `begin(observation) -> ticket`, then `publish(ticket, episodeId, revision)`
  after the producer's committed validation; `current()` rechecks the exact publication and clock.
- [x] GREEN: a null/malformed observation, failed operation or explicit invalidation revokes
  source/cohort. New CGM data revokes old publication before writes but retains cohort identity.
  Duplicate CGM retains its original clock window; it cannot renew expiry or queue age.
- [x] Bind both wall and elapsed deadlines to the accepted measurement, same boot, no backward
  clocks and <=5s clock disagreement. Clock uncertainty produces no source, not a fresh timestamp.
  New boot requires fresh producer acceptance. Reject out-of-order measurement/evaluation.
- [x] Stable episode+boot keeps generation despite event revision/level changes. A fresh cohort
  uses the committed event revision; higher/lower-level ACK policy remains the existing controller's.
  No START, Room alarm claim, Android sound or user acknowledgement is created here.

## Task 2: Glucose Producer

Create `GlucoseAlarmObservation.kt` and `GlucoseAlarmSourceTest.kt` under the corresponding
repository main/test roots. Modify `GlucoseAlertEngine.kt`, `GlucoseAlertNotifier.kt`,
`EpisodeAlertDeliveryStateMachine.kt`, `AutomationRepository.kt` and `service/AppContainer.kt`.

- [x] RED: accepted current decisions bind to the committed matching OPEN episode; missing/future/
  stale CGM, mismatched stage/direction, safe/pending-only state, stale operation and restart readback
  cannot publish. Test duplicate CGM, fresh renewal, escalation, reversal, OFF/resume and cancellation.
- [x] Derive `currentAlarmStage` from existing confirmed current candidate, not the latched
  episode/UI stage. A recovered current low may retain legacy LOW_NOW during hysteresis;
  it cannot authorize repeating that old urgent level. Opposite-direction forecast risk
  still waits for the existing accepted reversal. Legacy state/notify/thresholds stay unchanged.
- [x] Expose a checked source getter and read-only StateFlow using the complete feed. Observe the
  behavior RED before connecting acceptance; no placeholder producer is left at completion.
- [x] Pass the real accepted CGM timestamp from AutomationRepository to the notifier. A private-
  constructor observation factory binds the actual decision/stage/direction/current value/freshness.
  Derive the exclusive expiry from the existing <=5-minute integer-age bucket, not a new threshold.
- [x] Revoke on replacement before episode work. After normal episode coordination, perform a
  bounded best-effort readback of exact matching event/status/time and Room OFF. Native initial
  delivery/receipt takes place first; optional source read failure cannot suppress that sound.
  `withTimeoutOrNull` bounds only the optional read, parent cancellation still revokes and propagates.
- [x] Every global OFF/resume/toggle attempt revokes sources before persistence, independently of
  DataStore mirror success. Preserve legacy receipt/suppression/AI behavior: no INITIAL replay.

## Task 3: Diagnostic Producer And Composition

Modify `DeliveryDiagnosticRepository.kt`, add `DeliveryDiagnosticAlarmSourceTest.kt`, and
wire platform clock/revocation in `service/AppContainer.kt`.

- [x] RED: only a fresh accepted, confirmed diagnostic episode publishes; duplicate samples do not
  renew its window. Invalid/basis-changed input, low-risk suppression, OFF, recovery, cancellation,
  storage failure, stale data, old boot and retained startup display cannot authorize a source.
- [x] Keep source creation alongside the existing validated history/event projection, not downstream
  of UI `acceptedAt`. Return a private source candidate from the committed transaction. No extra
  storage read is inserted before existing diagnostic notification delivery.
- [x] Publish after successful accepted work, including an ordinary failed notification receipt;
  failure to deliver does not resolve clinical risk. Failure/cancellation of accepted work revokes.
- [x] Share an actual wall/elapsed/boot clock in AppContainer. Its existing OFF cleanup revokes
  glucose/diagnostic/pump sources without acquiring their Room/monitor locks. Cached flow values
  remain wake/provenance only; future hardware still must check OFF, settings and capabilities.

## Task 4: Review, Verification And Publication

- [x] Run focused new source tests plus existing episode/diagnostic/notifier/engine/runtime tests.
  Inspect RED and GREEN JUnit, including production pipeline timestamp forwarding coverage.
- [x] Review no extra first-sound gate, no implicit restore/expiry rearming, lock order, cancellation,
  finite/bounded source identities and absence of raw medical-data logging.
- [x] Update `AI_NOTES.md`, `docs/ARCHITECTURE.md`, `docs/INVARIANTS.md`, `docs/PLAN.md`,
  `docs/DEVOPS.md` and `docs/SECURITY_REVIEW.md` with actual current scope/results.
- [x] Run from `android-app`:
  `./gradlew :app:testDebugUnitTest :app:lintDebug :app:compileDebugKotlin :app:assembleDebug --max-workers=2 --console=plain`.
  Inspect fresh JUnit/lint/build reports, fix failures, then review explicit staged source paths.
  Final run:5820 cases/510 suites,5814 pass/6 existing skips,0 failures/errors;
  lint0 errors,371 warnings/8 hints unchanged. First-run unrelated TLS startup
  failure did not recur in its isolated suite or complete retry; original reports retained.
- [ ] Fetch/check divergence, commit and push existing branch/PR only after local quality; inspect
  exact-head Verify and downloaded synthetic reports. Record native checkpoint and remaining work.

## Remaining Boundary

The complete foreground/AlarmClock owner, safe native-audio handoff, visible scoped ACK,
OFF-expiry fresh reassessment and separately authorized phone acceptance are not activated
by this source stage. No phone installation, disable/backup, test sound, volume/DND/battery,
remote service, clinical threshold, dose or target operation is authorized here.
