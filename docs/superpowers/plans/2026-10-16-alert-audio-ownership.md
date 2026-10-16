# Alert Audio Request Ownership Implementation Plan

> **For agentic workers:** Use superpowers:executing-plans inline. Native subagents are unavailable. This is the transport-ownership part of the approved reliable-alert scheme, not platform activation or a second player.

**Goal:** Make legacy, preview and admitted local playback share exact request ownership so a stale cancellation/queued stop cannot kill a replacement, and current OFF prevents a preparing request from starting.

**Architecture:** Keep the existing MediaPlayer/focus/volume paths. A small internal synchronous ownership gate tracks at most the requested and active tickets plus an opaque revision stamp. New legacy/preview calls reserve before URI work/Main dispatch; local calls capture a stamp before dispatch and reserve only after admission/window/duplicate checks. Source authority remains outside this transport. Physical cleanup stays on Main and always checks exact request identity.

**Tech Stack:** Kotlin, Handler, synchronized bounded metadata, existing JUnit/Robolectric fake media and coroutine dispatchers. No dependency/schema/default/manifest/service/phone/therapy change.

## Task 1: Behavioral Reproduction

Modify `android-app/app/src/test/kotlin/io/aaps/copilot/data/repository/GlucoseAlertLocalPlaybackTest.kt`.

- [x] Add actual-controller tests before implementation: queued global Stop followed by a new urgent legacy clip; queued preview Stop followed by a new preview; cancelled old legacy preparation after a new local/legacy clip starts; OFF during preparation before Handler cleanup.
- [x] Reuse the existing fake media setup, `prepare()` and settings. Use StandardTestDispatcher for the old suspended continuation and UnconfinedTestDispatcher for its replacement; cancel the old job before its queued resume runs.
- [x] Run `./gradlew :app:testDebugUnitTest --tests '*GlucoseAlertLocalPlaybackTest' --max-workers=2 --console=plain` from `android-app`, capture RED and inspect exact assertions. No device sound.

## Task 2: Complete Bounded Ownership Gate

Create `android-app/app/src/main/kotlin/io/aaps/copilot/data/repository/AlertAudioOwnership.kt`
and matching `src/test/kotlin/io/aaps/copilot/data/repository/AlertAudioOwnershipTest.kt`.

- [x] Test-first API: `begin(kind)`, `stamp()`, `beginLocalIfCurrent(stamp, cycle)`, `activate(request)`, `isCurrent(request)`, `release(request)`, `revoke(request)`, `revokeAll()`, `revokePreview()`, `revokeCycle(cycle)`.
- [x] Tickets are identity objects with kind LEGACY/PREVIEW/LOCAL and optional exact cycle. Store only requested/active and an opaque revision, never an unbounded generation map or numeric rollover.
- [x] Local reservation after preflight requires the captured revision to match. Denied/duplicate local preflight must not replace an urgent legacy owner. Global revoke changes revision even when empty, fencing work already dispatched but not reserved.
- [x] Exact release/revoke of an old ticket cannot clear a newer requested/active ticket. Matching preview/cycle revocation returns only known exact tickets; stopping the same exact cycle remains valid, while an old cycle cannot stop a newer ordinal.
- [x] All gate operations are non-suspending, synchronized, metadata-only; no platform, Room, callbacks, logging or clinical authority inside the lock.

## Task 3: Actual Player Binding

Modify `android-app/app/src/main/kotlin/io/aaps/copilot/data/repository/GlucoseAlertAudioController.kt`.

- [x] Legacy/preview reserve once before source resolution/Main dispatch. Local captures a stamp before dispatch, then validates admission/spec/window/duplicates before reserving. Never invalidate a valid owner for a denied local attempt.
- [x] Pass the exact ticket into `startPlaybackOnMain`. Check current ownership before focus/player creation, preparation, seek/start and playback confirmation, including after callbacks. Global OFF revokes synchronously; queued physical cleanup targets only revoked tickets, not later accepted requests.
- [x] Use `stopOwnedPlayback(request, reason)` for cancellation/old callbacks and release only that ticket. New startup may stop the previous active player, but may not release its own new pending ticket. No unconditional legacy cancellation cleanup of a replacement.
- [x] Preview Stop is request-scoped. Exact cycle Stop revokes now and rechecks the same cycle on Main, preserving existing same-cycle semantics and excluding replacement ordinals. Any pending local session is cleared only when it belongs to the revoked ticket/cycle.
- [x] All prepared/seek callbacks start a request at most once. Preserve URI fallback, legacy urgent floor/gain, focus handling, local absolute timing/duplicate-step guards, receipt meanings and cancellation propagation.
- [x] Run new gate/controller tests plus existing source/episode/notifier/engine/local-alarm suites, inspect JUnit and add adversarial controls for denied preflight and current cancellation. Final focused585 cases pass, including39 controller and16 gate cases.

## Task 4: Review And Verification

- [x] Review lock/thread ownership, pending-dispatch revocation, callback reentrancy and old/new cleanup order. Three observer REDs reproduced replacement cleanup/false confirmation and pass after the fix. This fixes actual transport ownership, not the complete foreground owner/native INITIAL handoff.
- [x] Update AI_NOTES and governing docs. Record prior52b5e03 exact CI receipts without creating a CI-only commit loop.
- [x] Full quality from `android-app`: `./gradlew :app:testDebugUnitTest :app:lintDebug :app:compileDebugKotlin :app:assembleDebug --max-workers=2 --console=plain`. Final5857 cases/5851 pass/6 existing skips,0 failures/errors; unchanged379 lint notice keys/0 errors, debug v2 signature verified. Extra slow-observer RED fixed timer installation before observable active state; all40 controller cases pass. Failed evidence retained.
- [ ] Fetch/check divergence, stage explicit reviewed source/tests/docs, scan for private data/credentials/restricted operational identifiers, commit/push existing feature branch and inspect exact-head Verify plus downloaded synthetic reports.

## Remaining Runtime Boundary

No production LocalAlarmCoordinator is constructed or armed here. Complete current typed source/settings/Room OFF/capability composition, explicit single-owner INITIAL handoff, foreground/genuine AlarmClock scheduling, scoped visible ACK and OFF-expiry reassessment remain separate integration work. Physical phone acceptance remains separately authorized; no100-percent delivery/hearing claim.
