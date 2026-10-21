# Alert Fatigue Policy Implementation Plan

> **For agentic workers:** Use superpowers:executing-plans task by task in this task. Native subagents are unavailable; no external runner is started.

**Goal:** Implement the owner's explicitly approved noncritical sound/backoff/coalescing rules in the existing inactive single-owner runtime, preserving critical cadence, scoped acknowledgement and global OFF.

**Architecture:** Reuse the existing policy, cycle executor and transactional Room journal. Derive backoff from the previous cycle's persisted interval, not the lifetime ordinal. Derive shared cooldown from confirmed noncritical audio-start receipts across all sources, including sources no longer in the current batch. A live claim reserves the single noncritical slot until cleanup/deadline but does not count as delivered. Unknown cooldown history prevents only new noncritical audio, never critical selection.

**Tech Stack:** Kotlin, coroutines, Room, JUnit, Robolectric. No dependency or database schema change.

## Authorization And Boundary

The owner explicitly approved critical repeats at the existing configured interval,
exact-source temporary acknowledgement, noncritical backoff up to 30 minutes,
global OFF and a genuine user-visible critical alarm clock. This stage implements
the fatigue policy only. Source adapters, foreground/alarm-clock activation, UI
and physical phone acceptance remain separate uncompleted stages. No phone
operation, alarm arming, test sound, volume/DND change or therapy change.

## Task 1: One Soft Clip And Bounded Backoff

Files: `LocalAlarmProfiles.kt`, `LocalAlarmPolicy.kt`, `LocalAlarmCycleExecutor.kt`,
`LocalAlarmPlaybackWindow.kt`, their four focused test files under
`android-app/app/src/{main,test}/kotlin/io/aaps/copilot/`.
Real player callbacks are covered by `GlucoseAlertLocalPlaybackTest.kt` as well.

- [x] RED: assert one soft step at offset 0 and 25%, including old reached=100;
  assert successive intervals `300000, 600000, 1200000, 1800000, 1800000`.
  Preserve urgent floor and strong quarter-duration ramp tests. Update obsolete
  multi-step soft fixtures to strong levels when they test ramp/cancellation.
- [x] GREEN: soft targets become `listOf(25)` independent of retained progression;
  strong targets stay unchanged. For unchanged soft identities use
  `maxOf(base, previousInterval * 2).coerceAtMost(1800000)`; new identities or
  levels start at base. Existing due times never slide on evidence/settings updates.
  ACK expiry and OFF resume explicitly restart at base after fresh admission.
- [x] Use one profile-owned step deadline helper in both executor and playback
  windows. The single soft clip keeps the old 15-second initial admission window;
  no step index 1 access and no delayed replay at the 55-second cleanup deadline.
- [x] Verify focused tests, including persistence round trips, duplicate evidence,
  reboot, new generation, changed severity, overflow and no overdue catch-up burst.

## Task 2: Transactional Shared Noncritical Cooldown

Files: `LocalAlarmModels.kt`, `LocalAlarmPolicy.kt`,
`LocalAlarmArbitrationPolicy.kt`, `AlertLocalDao.kt`, `RoomLocalAlarmStore.kt`;
tests: `LocalAlarmArbitrationPolicyTest.kt`, `RoomLocalAlarmStoreTest.kt` and
existing coordinator/arbitration integration tests.

- [x] RED: after source A records STARTED and finishes, new source B is deferred
  until the configured soft interval even if A is absent from the batch/store
  instance. Notification-only/failed audio does not start that interval. An active
  uncompleted claim blocks another soft claim but not a critical claim.
- [x] RED: exact expiry admits one source; arbitration wakes at cooldown/earlier
  source expiry, duplicate refresh does not slide cooldown, concurrent stores
  cannot claim two different noncritical sources, old boot does not suppress.
- [x] GREEN: add a bounded DAO query of current-boot noncritical cycles since
  `now - softInterval - 55000`, newest first, limit 65. Reject over 64 or malformed
  rows as unavailable, validate result timestamps and typed cycle shape, derive
  max confirmed STARTED time plus soft interval and unexpired claim deadline.
  Cancellation propagates. Query/payload failure never suppresses critical risk.
- [x] Feed the calculated deadline into both read-only arbitration and committed
  START admission under the existing shared operation lock/transaction. An active
  exact owner remains admitted; deferral creates no ordinal, progress or result.
  Preserve each source's state and displayed evidence independently.
- [x] GREEN: add `COOLDOWN` admission with a future deadline in arbitration;
  unknown shared history yields noncritical UNAVAILABLE. Strong levels bypass
  both. No new sound or delivery claim is inferred from a scheduling reservation.

Additional test files: `domain/alerts/LocalAlarmFatiguePolicyTest.kt` and
`data/repository/LocalAlarmSharedCooldownTest.kt` under the existing test root.
Focused result:210 cases across14 suites passed, no failures/errors/skips.
RED evidence:92 cases/10 expected failures for clip/backoff behavior;9 cases/6
expected failures for shared cooldown; separate11-case RED with1 critical-query
dependency failure. Fake clocks/audio/volume and in-memory Room only.

## Task 3: Review, Quality And Publication

Files: `AI_NOTES.md`, relevant current sections in `docs/ARCHITECTURE.md`,
`docs/INVARIANTS.md`, `docs/PLAN.md`, `docs/DEVOPS.md`, `docs/SECURITY_REVIEW.md`.

- [x] Inspect source diff, authority/cancellation/cleanup behavior and secret/neutral
  identifier checks. Review source-stage completeness without claiming activation.
- [x] Run focused alarm tests first, then from `android-app`:
  `./gradlew :app:testDebugUnitTest :app:lintDebug :app:compileDebugKotlin :app:assembleDebug --max-workers=2 --console=plain`.
  Inspect actual JUnit and lint reports. Fix failures before publication.
- Full source quality passed in9m56s:5751 cases/5745 passed/6 existing skips,
  no errors/failures; lint0 errors and unchanged379 baseline notices. The first
  complete run caught2 obsolete fourth-soft-clip player fixtures, then26 updated
  callback cases and the complete rerun passed. Source/security/diff review clean.
- [ ] Update current docs and this plan with inspected evidence and remaining
  runtime work. Fetch origin, verify no divergence, stage only reviewed paths,
  commit/push the existing feature branch. Inspect exact-head Verify results.
- [ ] Record the checkpoint. No installation, clinical validation or guaranteed
  physical/human delivery claim follows from source/CI success.
