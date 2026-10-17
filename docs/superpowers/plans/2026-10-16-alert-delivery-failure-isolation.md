# Accepted Alert Delivery Failure Isolation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox syntax for tracking.

**Goal:** Publish accepted alerts before potentially slow/failing writers, and stop reporting blocked or partially failed delivery as fully healthy.

**Architecture:** Keep the accepted forecast/UAM/sensitivity gate and clinical engine. Isolate two downstream consumers after that gate, without retrying either or swallowing cancellation. Notification posting checks actual user-controlled channel state; delivery records retain separate channel failures while preserving legacy any-channel receipt semantics.

**Tech Stack:** Kotlin, coroutines, Android NotificationManager, Room, JUnit, Robolectric, Gradle.

---

## Boundaries

This is the first independently useful production stage of
`2026-10-16-reliable-alert-delivery-design.md`. Do not activate LocalAlarmCoordinator,
create a service/alarm/permission, change clinical thresholds, OFF semantics,
therapy decisions or AI dispatch. No phone/remote operation. The full repeated
runtime, coalescing/backoff, source bridge, OFF expiry reassessment, ACK UI and
physical acceptance remain open requirements, not completed work.

## Task 1: Selected Channel Admission

**Files:**
- Modify: `android-app/app/src/main/kotlin/io/aaps/copilot/data/repository/GlucoseAlertNotifier.kt`
- Create: `android-app/app/src/test/kotlin/io/aaps/copilot/data/repository/GlucoseAlertNotificationAdmissionTest.kt`

- [x] Add public-notifier/Room integration tests for a blocked watch channel,
  denied notification permission, an allowed channel, unchanged blocked channel
  and duplicate accepted evidence. Use in-memory Room, an Application test
  context and read-only default settings. Never play audio; use WATCH only.

```kotlin
manager.createNotificationChannel(NotificationChannel(
    "copilot_glucose_alert_watch", "Test watch", NotificationManager.IMPORTANCE_NONE))
val result = notifier.deliver(watchDecision(), 5.0, defaults(), nowTs = 10_000L)
assertThat(result.notificationPosted).isFalse()
assertThat(manager.activeNotifications).isEmpty()
```

- [x] Run the new suite against current source and inspect the expected
  false-positive-post assertion failure, not only a compiler error.
- [x] In `postNotification`, retain the permission check and check global enable,
  channel existence/importance and an explicitly blocked Android28+ channel
  group before `manager.notify`. Treat unreadable platform state as unavailable.
  Do not recreate, change importance or unmute a user channel.

```kotlin
if (!notificationsAllowed()) return false
val platform = context.getSystemService(NotificationManager::class.java) ?: return false
val channel = platform.getNotificationChannel(notification.channelId) ?: return false
if (channel.importance == NotificationManager.IMPORTANCE_NONE) return false
if (channel.group?.let { platform.getNotificationChannelGroup(it)?.isBlocked } == true) return false
```

- [x] Run channel admission and existing presentation suites; inspect posted
  notification counts and Room receipt status separately from physical hearing.

## Task 2: Preserve Partial Channel Failures

**Files:**
- Modify: `android-app/app/src/main/kotlin/io/aaps/copilot/data/repository/ConfirmedGlucoseAlertDelivery.kt`
- Modify: `android-app/app/src/main/kotlin/io/aaps/copilot/data/repository/GlucoseAlertNotifier.kt`
- Modify: `android-app/app/src/main/kotlin/io/aaps/copilot/data/repository/AutomationRepository.kt`
- Modify: `android-app/app/src/test/kotlin/io/aaps/copilot/data/repository/GlucoseAlertConfirmedDeliveryTest.kt`

- [x] Add failing tests for notification success plus required-audio failure,
  audio success plus notification failure, unavailable required vibration,
  independent failures, required audio returning no result, and cancellation.
  Existing WATCH/unrequested-channel tests must retain silent semantics.

```kotlin
val result = deliverConfirmedGlucoseAlertChannels(
    notificationPermissionGranted = true, postNotification = { true },
    invokeVibration = { true }, audioRequired = true, vibrationRequired = true,
    startAudio = { GlucoseAlertAudioPlaybackResult(failureReason = "audio_start_failed") })
assertThat(result.audioFailureReason).isEqualTo("audio_start_failed")
assertThat(result.degradedReason).isEqualTo("audio_start_failed")
assertThat(result.toAlertSideEffectResult().receiptResult).isEqualTo(AlertReceiptResult.DELIVERED)
```

- [x] Run RED, then add optional channel-required arguments and three separate
  nullable failure fields on GlucoseAlertDeliveryResult. A positive receipt still
  means one adapter accepted an effect, not full delivery or acknowledgement.
  Preserve audio timeout cleanup and propagate cancellation.
- [x] Set required sound/vibration only for existing soft/critical playback, not
  WATCH. Persist separate notification/audio/vibration issue telemetry alongside
  the existing summary. No private payload or raw exception message is added.
- [x] Run confirmed delivery, channel admission and episode receipt suites.

## Task 3: Accepted Consumer Failure Isolation

**Files:**
- Modify: `android-app/app/src/main/kotlin/io/aaps/copilot/data/repository/AutomationRepository.kt`
- Modify: `android-app/app/src/test/kotlin/io/aaps/copilot/data/repository/IntegratedClinicalRuntimeRevisionTest.kt`

- [x] Add failing tests for ordinary UAM writer failure and target calculation
  failure after valid forecast/UAM binding: publish one alert, preserve the
  original exception, never retry a writer or publish a successful widget.
- [x] Test both consumers failing (primary therapy error, suppressed alert error),
  cancellation (no new effect after cancellation), alert-only failure (no therapy
  retry), and writer-disabled source-change policy (no notification side effect).

```kotlin
assertThat(failure).isSameInstanceAs(writerFailure)
assertThat(alertPublications).isEqualTo(1)
assertThat(writerAttempts).isEqualTo(1)
assertThat(widgetPublications).isEqualTo(0)
```

- [x] Observe RED, including the explicit alert-before-writer ordering RED. Invoke
  the already permitted alert consumer before the existing therapy call, only
  after all authority checks. Capture only ordinary alert `Exception`, preserve
  a primary therapy failure with the alert failure suppressed, or rethrow the
  alert failure after successful therapy. A cancellation remains cancellation. Errors
  before acceptance must still produce zero consumer effects; never use finally
  to publish after invalid authority or process-fatal errors.
- [x] Run integrated accepted-runtime and delivery diagnostic focused regressions;
  automation policy is additionally covered by the pending complete suite.
  No current-low/forecast-unavailable independence is claimed.

## Task 4: Review, Quality and Source Publication

**Files:** `AI_NOTES.md`, `docs/ARCHITECTURE.md`, `docs/INVARIANTS.md`,
`docs/PLAN.md`, `docs/SECURITY_REVIEW.md`, this plan and approved design.

- [x] Record exact changes/results and remaining full-runtime gates. Review
  security, no therapy decision/clinical threshold/default/permission changes,
  and no secret/private artifacts in the explicit source diff.
- [x] Run scoped suites then full `:app:testDebugUnitTest :app:lintDebug
  :app:compileDebugKotlin :app:assembleDebug --max-workers=2 --console=plain` with
  existing Java17/Android SDK. Expected: terminal BUILD SUCCESSFUL and inspected
  JUnit/lint reports. Do not claim Android service/phone delivery from these tests.
  Final126 focused/24 new cases pass. Complete5727 cases:5721 passed,6 existing
  opt-in skips,0 failures/errors; lint0 errors/379 existing notices. Terminal
  BUILD SUCCESSFUL in9m39s after the explicit SecurityException lint repair.
- [ ] Fetch origin, inspect divergence/diff, stage explicit reviewed source paths,
  commit/push the existing feature branch, inspect exact-head Verify on PR7.
  No merge, deployment, phone update or arming is part of publication.
- [ ] Save a matching task checkpoint with evidence and next source-owner stage.

Execution: inline in the existing task. Native subagent tools are unavailable;
do not substitute external agent processes or create user-owned chats.
