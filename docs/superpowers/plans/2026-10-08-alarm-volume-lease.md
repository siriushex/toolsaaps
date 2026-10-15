# Guarded Alarm Volume Lease Implementation Plan

> **For agentic workers:** Use superpowers:executing-plans inline, with TDD and native checkpoints. No subagent tool is available in this task.

**Goal:** Implement single-cycle alarm-stream volume ownership without activating the proposed escalation mode.

**Architecture:** A synchronous volume-only lease uses the existing LocalAlarmCycle and LocalAlarmProfiles.targetVolume. An injected current admission callback and elapsed clock guard acquisition and each raise. An Android adapter only reads/sets STREAM_ALARM with flags0. No service, player, vibrator, timer, Room, clinical source, settings or runtime consumer is added.

**Tech Stack:** Existing Kotlin, Android AudioManager, JUnit/Truth and Robolectric; no dependencies.

## Scope And Constraints

Continue the Oct7 proposal after verified policy/storage stages. Feature work
is authorized by the human's request to continue, not consent for installation
or activation. Fresh ADB is empty; original incident and phone CPU/RAM remain
unverified. Keep default legacy sound and urgent-low floor unchanged.

The admission callback is supplied by the future serialized coordinator and
must validate committed ownership, source authority/freshness, global OFF and
Android capabilities. This helper cannot authorize a clinical alarm itself.
Reads are observations, not atomic compare-and-set against the Android system.
A change-and-return gesture or a route change with identical indices may not
be detectable; never promise stronger protection or perceived loudness.

## Task 1: Regression-First Volume Ownership

Files:
- Create `android-app/app/src/test/kotlin/io/aaps/copilot/data/repository/LocalAlarmVolumeLeaseTest.kt`.
- Create `android-app/app/src/main/kotlin/io/aaps/copilot/data/repository/LocalAlarmVolumeLease.kt`.

- [x] Write fake hardware tests first. Minimal desired API:

```kotlin
val lease = LocalAlarmVolumeLease(port, { elapsedMs }, { admitted })
assertThat(lease.acquire(cycle).status).isEqualTo(AlarmVolumeStatus.ACQUIRED)
assertThat(lease.raise(cycle, 70).confirmedIndex).isEqualTo(5)
assertThat(lease.release(cycle).status).isEqualTo(AlarmVolumeStatus.RESTORED)
```

- [x] Run `:app:testDebugUnitTest --tests '*LocalAlarmVolumeLeaseTest'` and inspect absent-API RED.
- [x] Implement exact cycle identity and one owner, idempotent acquisition, no
  lower-than-current raise, hardware round-up, post-read admission and absolute
  deadline checks. Re-read after every set; request success is not confirmation.
- [x] A differing current index permanently stops raises/restoration for this
  lease. Fixed/invalid/changed maximum, denied admission, unknown write outcome
  and read/write failure stop raises. Unknown ownership must not restore.
- [x] Release only the exact owner. Restore baseline only after a confirmed
  change and still-matching owned index; OFF/expiry cleanup must remain possible.
  Propagate cancellation and never retain a lease after release failure.
- [x] Cover urgent70%, high initial index, repeated and stale calls, manual
  override/return, fixed volume, unavailable reads/writes/readback, no-op setters,
  OFF between read/write, deadline, capability denial and cancellation.

## Task 2: Android Alarm-Only Adapter

Files:
- Create `android-app/app/src/main/kotlin/io/aaps/copilot/data/repository/AndroidAlarmVolumePort.kt`.
- Create `android-app/app/src/test/kotlin/io/aaps/copilot/data/repository/AndroidAlarmVolumePortTest.kt`.

- [x] Write adapter RED for snapshot and setting only STREAM_ALARM; retain media,
  ring and notification indices, with no sound/UI/vibration flags.
- [x] Implement read of getStreamVolume/getStreamMaxVolume/isVolumeFixed and
  `audioManager.setStreamVolume(AudioManager.STREAM_ALARM, index, 0)` only.
- [x] Run new tests plus existing LocalAlarm and legacy audio volume/profile suites.

## Task 3: Review And Publication

- [x] Self-review admission boundaries and exception/override ownership. New
  failures get actual RED/GREEN regressions, not speculative edits.
- [x] Update ARCHITECTURE, INVARIANTS, PLAN, SECURITY_REVIEW and AI_NOTES; no
  claimed phone fix, active ramp, reduced CPU/RAM or automatic volume change.
- [x] Run full Android unit/lint/compile/assemble with Java17/SDK36 and inspect
  terminal reports. Keep private logs outside Git.
- [ ] Fetch/check divergence, stage explicit reviewed paths, commit and push
  existing feature branch; inspect exact-SHA Verify. Do not merge or deploy.

## Inspected Local Results

Both absent-API REDs were inspected. Initial lease24 and adapter4 cases passed.
Three callback/reentrancy cases then failed on the unchanged implementation;
after the guarded transition fix, focused91 across7 suites passed. A late
LOW_NOW floor regression then failed expected5/actual2 for a quiet caller step;
the helper now clamps to the canonical first-target minimum. Final focused92
across7 suites pass with0 failures/errors/skips. New tests total32. Full Android
quality after this change passed in9m41s:4932
cases across424 suites,0 failures/errors and3 optional real-phone-copy skips.
Fresh lint XML:0 errors,305 existing warnings,4 hints and no new volume issues.
The candidate APK contains the two new classes and is not installed.
Publication/Verify remains an external gate; its actual terminal outcome is
recorded in the matching native checkpoint after the source commit exists.

## Primary Reference

[AudioManager](https://developer.android.com/reference/android/media/AudioManager)
documents index-based volume, fixed-volume no-op and possible SecurityException.
Do not toggle DND/ringer, force a route, or treat setter return as delivery.
