# Guarded Local Alarm Playback Implementation Plan

> **For agentic workers:** Use superpowers:executing-plans task-by-task in the existing feature worktree.

**Goal:** Reuse the existing player for bounded, freshly admitted local-cycle clips without adding a runtime caller.

**Architecture:** Add an explicit cycle/step playback entry point to GlucoseAlertAudioController and a small immutable playback window. Keep one player/focus owner and the existing clip resolver/gain. The future serialized coordinator supplies committed authority, current OFF and platform admission; this transport cannot arm itself.

**Tech Stack:** Kotlin, Android MediaPlayer/Handler, coroutines, JUnit/Truth and Robolectric.

## Scope And Boundaries

No service, source adapter, setting, permission, volume lease consumer, vibration, wake lock, notification, Room, therapy/target/forecast or backend change. Legacy playback remains the default unchanged path. No device update or live sound. The phone is absent in fresh ADB inventory.

A clip start belongs to one exact cycle and one of its canonical start steps. Preparation/seek must finish before the next profile step (or cycle deadline for the final soft step); expired starts are skipped, not replayed. Playback duration is clipped at the absolute55s cycle deadline. Timer delivery is not a real-time/Doze guarantee; wake/service integration and real-device acceptance remain separate.

## Task 1: Playback Window And Controller Admission

Files:
- Create android-app/app/src/main/kotlin/io/aaps/copilot/data/repository/LocalAlarmPlaybackWindow.kt
- Modify android-app/app/src/main/kotlin/io/aaps/copilot/data/repository/GlucoseAlertAudioController.kt
- Create matching LocalAlarmPlaybackWindowTest.kt and GlucoseAlertLocalPlaybackTest.kt

- [x] Add failing synthetic tests for exact cycle shape, start windows, late preparation, denial, cancellation and USAGE_ALARM.
- [x] Inspect RED for the missing API before implementation.
- [x] Implement checked canonical windows using existing policy/profile validation.
- [x] Add explicit guarded playback, exact-cycle stop, shared single player and bounded preparation/playing timers.
- [x] Preserve legacy slot selection, gains, urgent floor and audio attributes. New path never writes volume.
- [x] Run focused tests; inspect callback/focus replacement, stale stop and duplicate/no-replay behavior. Final80 cases pass.

## Task 2: Verification And Publication

Files:
- docs/ARCHITECTURE.md, INVARIANTS.md, PLAN.md, SECURITY_REVIEW.md, AI_NOTES.md

- [x] Record actual contracts, restrictions and test evidence.
- [x] Self-review the complete diff; add actual RED regressions for any found defect. Three actual callback/time regressions fixed.
- [x] Run full Android testDebugUnitTest/lintDebug/compileDebugKotlin/assembleDebug and refresh lintReportDebug. Final gate passed in9m15s.
- [x] Inspect structured results and candidate APK;4962 cases,0 failures/errors,3 optional phone-copy skips. Lint0 errors/305 existing warnings/4 hints. No install/activation claim.
- [x] Review explicit publication paths, fetch and check divergence. Preserve the existing draft PR against main.

Publication/exact-source Verify are the external source gate: commit/push only
after local verification, then verify remote SHA and both actual runs. Record
their outcome in the private native checkpoint only after real completion;
these local checkboxes do not preclaim the later CI outcome. No main merge.
