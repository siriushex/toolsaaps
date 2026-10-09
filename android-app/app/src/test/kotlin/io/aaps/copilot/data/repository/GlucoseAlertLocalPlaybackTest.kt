package io.aaps.copilot.data.repository

import android.app.Application
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.aaps.copilot.config.AppSettings
import io.aaps.copilot.domain.alerts.*
import io.aaps.copilot.domain.predict.InsulinActionProfileId
import java.io.File
import java.time.Duration
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowMediaPlayer

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
@LooperMode(LooperMode.Mode.PAUSED)
class GlucoseAlertLocalPlaybackTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val cycle = LocalAlarmCycle(LocalAlarmKey(LocalAlarmSourceKind.GLUCOSE, "source-a"),
        1, 1, LocalAlarmLevel.WARNING_30, 1, 1_000, 56_000)
    private var now = 1_000L
    private var allowed = true
    private lateinit var controller: GlucoseAlertAudioController
    private val players = mutableListOf<MediaPlayer>()
    private val settings = testSettings()

    @Before fun setup() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        ShadowMediaPlayer.setMediaInfoProvider { ShadowMediaPlayer.MediaInfo(180_000, -1) }
        ShadowMediaPlayer.setCreateListener { player, _ -> players += player }
        controller = GlucoseAlertAudioController(context) { now }
    }

    @After fun cleanup() {
        controller.stop()
        shadowOf(Looper.getMainLooper()).idle()
        Dispatchers.resetMain()
    }

    @Test fun deniedSignalDoesNotCreatePlayerOrRequestFocus() = runTest {
        allowed = false
        val result = controller.playLocalAlarm(cycle, 0, settings) { allowed }
        assertThat(result.played).isFalse()
        assertThat(players).isEmpty()
        assertThat(shadowOf(audio).lastAudioFocusRequest).isNull()
    }

    @Test fun admittedSoftClipUsesAlarmAttributesButDoesNotWriteStreamVolume() = runTest {
        audio.setStreamVolume(AudioManager.STREAM_ALARM, 1, 0)
        val result = async { controller.playLocalAlarm(cycle, 0, settings) { allowed } }
        runCurrent()
        prepare()
        assertThat(result.await().played).isTrue()
        assertThat(shadowOf(players.last()).audioAttributes.usage).isEqualTo(AudioAttributes.USAGE_ALARM)
        assertThat(shadowOf(players.last()).leftVolume).isEqualTo(0.58f)
        assertThat(audio.getStreamVolume(AudioManager.STREAM_ALARM)).isEqualTo(1)
        assertThat(controller.playbackState.value.activeSlot).isEqualTo(GlucoseAlertAudioSlot.SOFT)
    }

    @Test fun offDuringPreparationPreventsTheStartAndReleasesFocus() = runTest {
        val result = async { controller.playLocalAlarm(cycle, 0, settings) { allowed } }
        runCurrent()
        allowed = false
        prepare()
        assertThat(result.await().played).isFalse()
        assertThat(shadowOf(players.last()).state).isEqualTo(ShadowMediaPlayer.State.END)
        assertThat(shadowOf(audio).lastAbandonedAudioFocusRequest).isNotNull()
    }

    @Test fun latePreparedCallbackCannotReplayAMissedStep() = runTest {
        val result = async { controller.playLocalAlarm(cycle, 0, settings) { allowed } }
        runCurrent()
        now = 16_000
        prepare()
        assertThat(result.await().played).isFalse()
        assertThat(shadowOf(players.last()).state).isEqualTo(ShadowMediaPlayer.State.END)
    }

    @Test fun preparationTimeoutFinishesWithoutAnyMediaCallback() = runTest {
        val result = async { controller.playLocalAlarm(cycle, 0, settings) { allowed } }
        runCurrent()
        now = 16_000
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(15))
        assertThat(result.await().played).isFalse()
        assertThat(shadowOf(players.last()).state).isEqualTo(ShadowMediaPlayer.State.END)
    }

    @Test fun lastSoftClipIsTruncatedAtTheAbsoluteCycleDeadline() = runTest {
        now = 54_000
        val result = async { controller.playLocalAlarm(cycle, 3, settings.copy(softAlertAudioDurationMs = 5_000)) { allowed } }
        runCurrent()
        prepare()
        assertThat(result.await().durationMs).isEqualTo(2_000)
        now = 56_000
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
        assertThat(shadowOf(players.last()).state).isEqualTo(ShadowMediaPlayer.State.END)
    }

    @Test fun exactCycleStopCancelsPreparationButStaleStopCannotCancelANewOwner() = runTest {
        val first = async { controller.playLocalAlarm(cycle, 0, settings) { allowed } }
        runCurrent()
        controller.stopLocalAlarm(cycle)
        shadowOf(Looper.getMainLooper()).idle()
        assertThat(first.await().played).isFalse()
        val next = cycle.copy(ordinal = 2)
        val second = async { controller.playLocalAlarm(next, 0, settings) { allowed } }
        runCurrent()
        controller.stopLocalAlarm(cycle)
        shadowOf(Looper.getMainLooper()).idle()
        prepare()
        assertThat(second.await().played).isTrue()
    }

    @Test fun duplicateAndEarlierStepDoNotRestartTheSameCycle() = runTest {
        val result = async { controller.playLocalAlarm(cycle, 0, settings) { allowed } }
        runCurrent()
        assertThat(controller.playLocalAlarm(cycle, 0, settings) { allowed }.played).isFalse()
        assertThat(players).hasSize(1)
        prepare()
        result.await()
        controller.stopLocalAlarm(cycle)
        shadowOf(Looper.getMainLooper()).idle()
        assertThat(controller.playLocalAlarm(cycle, 0, settings) { allowed }.played).isFalse()
        assertThat(players).hasSize(1)
    }

    @Test fun cancellationReleasesThePreparingPlayerBeforeReturning() = runTest {
        val result = async { controller.playLocalAlarm(cycle, 0, settings) { allowed } }
        runCurrent()
        result.cancelAndJoin()
        assertThat(shadowOf(players.last()).state).isEqualTo(ShadowMediaPlayer.State.END)
    }

    @Test fun deniedFocusDoesNotCreatePlayerAndAbandonsTheRequest() = runTest {
        shadowOf(audio).setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_FAILED)
        assertThat(controller.playLocalAlarm(cycle, 0, settings) { allowed }.played).isFalse()
        assertThat(players).isEmpty()
        assertThat(shadowOf(audio).lastAbandonedAudioFocusRequest).isNotNull()
    }

    @Test fun oldPreparedAndErrorCallbacksCannotStopTheReplacement() = runTest {
        val first = async { controller.playLocalAlarm(cycle, 0, settings) { allowed } }
        runCurrent()
        val old = shadowOf(players.last())
        val second = async { controller.playLocalAlarm(cycle.copy(ordinal = 2), 0, settings) { allowed } }
        runCurrent()
        assertThat(first.await().played).isFalse()
        old.invokePreparedListener()
        old.invokeErrorListener(1, 0)
        prepare()
        assertThat(second.await().played).isTrue()
        assertThat(controller.playbackState.value.activeSlot).isEqualTo(GlucoseAlertAudioSlot.SOFT)
    }

    @Test fun legacyPreviewRetainsNotificationAttributesAndGain() = runTest {
        val result = async { controller.preview(GlucoseAlertAudioSlot.SOFT, settings) }
        runCurrent()
        prepare()
        assertThat(result.await().played).isTrue()
        assertThat(shadowOf(players.last()).audioAttributes.usage).isEqualTo(AudioAttributes.USAGE_NOTIFICATION_EVENT)
        assertThat(shadowOf(players.last()).leftVolume).isEqualTo(0.58f)
        assertThat(controller.playbackState.value.isPreview).isTrue()
    }

    @Test fun newEntryPointHasNoProductionCallerOrTherapyNetworkWriter() {
        val root = File("src/main/kotlin/io/aaps/copilot")
        val consumers = root.walkTopDown().filter { it.extension == "kt" && it.name != "GlucoseAlertAudioController.kt" }
            .filter { it.readText().contains(Regex("playLocalAlarm\\s*\\(|stopLocalAlarm\\s*\\(")) }
            .map { it.relativeTo(root).path }.toList()
        assertThat(consumers).isEmpty()
        val source = File(root, "data/repository/GlucoseAlertAudioController.kt").readText()
        listOf("AutomationRepository", "ForecastRepository", "Telegram", "setCommunicationDevice", "setRingerMode")
            .forEach { assertThat(source).doesNotContain(it) }
    }

    @Test fun clockJumpBetweenAdmissionAndTimerCaptureStillCleansUp() = runTest {
        var reads = 0
        controller = GlucoseAlertAudioController(context) { if (++reads >= 6) 16_000 else 1_000 }
        val result = controller.playLocalAlarm(cycle, 0, settings) { allowed }
        assertThat(result.played).isFalse()
        assertThat(shadowOf(players.last()).state).isEqualTo(ShadowMediaPlayer.State.END)
        assertThat(shadowOf(audio).lastAbandonedAudioFocusRequest).isNotNull()
    }

    @Test fun duplicatePreparedCallbackCannotExtendTheClip() = runTest {
        val result = async { controller.playLocalAlarm(cycle, 0, settings) { allowed } }
        runCurrent()
        prepare()
        assertThat(result.await().played).isTrue()
        now = 2_000
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
        shadowOf(players.last()).invokePreparedListener()
        now = 3_000
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
        assertThat(shadowOf(players.last()).state).isEqualTo(ShadowMediaPlayer.State.END)
    }

    @Test fun slowPostStartAdmissionCannotMoveTheAbsoluteStopDeadline() = runTest {
        now = 54_000
        val result = async {
            controller.playLocalAlarm(cycle, 3, settings.copy(softAlertAudioDurationMs = 5_000)) {
                if (players.lastOrNull()?.let { shadowOf(it).isReallyPlaying } == true) now = 55_000
                true
            }
        }
        runCurrent()
        prepare()
        assertThat(result.await().played).isTrue()
        now = 56_000
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
        assertThat(shadowOf(players.last()).state).isEqualTo(ShadowMediaPlayer.State.END)
    }

    @Test fun staleFocusLossCannotStopANewerPlayer() = runTest {
        val first = async { controller.playLocalAlarm(cycle, 0, settings) { allowed } }
        runCurrent()
        val oldListener = shadowOf(audio).lastAudioFocusRequest.listener
        val second = async { controller.playLocalAlarm(cycle.copy(ordinal = 2), 0, settings) { allowed } }
        runCurrent()
        first.await()
        prepare()
        second.await()
        oldListener.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS)
        shadowOf(Looper.getMainLooper()).idle()
        assertThat(shadowOf(players.last()).isReallyPlaying).isTrue()
    }

    @Test fun currentFocusLossReleasesTheCurrentPlayer() = runTest {
        val result = async { controller.playLocalAlarm(cycle, 0, settings) { allowed } }
        runCurrent()
        prepare()
        result.await()
        shadowOf(audio).lastAudioFocusRequest.listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS)
        shadowOf(Looper.getMainLooper()).idle()
        assertThat(shadowOf(players.last()).state).isEqualTo(ShadowMediaPlayer.State.END)
    }

    @Test fun cancellationInsidePendingAdmissionCannotCreateAPlayer() = runTest {
        var calls = 0
        val result = controller.playLocalAlarm(cycle, 0, settings) {
            if (++calls == 2) controller.stopLocalAlarm(cycle)
            true
        }
        assertThat(result.played).isFalse()
        assertThat(players).isEmpty()
    }

    @Test fun deniedSeekCompletionNeverStartsThePreparedPlayer() = runTest {
        val result = async { controller.playLocalAlarm(cycle, 0, settings.copy(softAlertAudioStartMs = 1_000)) { allowed } }
        runCurrent()
        shadowOf(players.last()).setSeekDelay(-1)
        prepare()
        allowed = false
        shadowOf(players.last()).invokeSeekCompleteListener()
        assertThat(result.await().played).isFalse()
        assertThat(shadowOf(players.last()).state).isEqualTo(ShadowMediaPlayer.State.END)
    }

    @Test fun strongCycleStartsOnlyOneCriticalClipWithExistingGainAndNoVolumeWrite() = runTest {
        audio.setStreamVolume(AudioManager.STREAM_ALARM, 1, 0)
        val strong = cycle.copy(level = LocalAlarmLevel.LOW_NOW)
        val result = async { controller.playLocalAlarm(strong, 0, settings) { allowed } }
        runCurrent()
        prepare()
        assertThat(result.await().durationMs).isEqualTo(20_000)
        assertThat(shadowOf(players.last()).leftVolume).isEqualTo(0.92f)
        assertThat(audio.getStreamVolume(AudioManager.STREAM_ALARM)).isEqualTo(1)
        assertThat(controller.playLocalAlarm(strong, 1, settings) { allowed }.played).isFalse()
        assertThat(players).hasSize(1)
    }

    @Test fun mediaErrorIsFailureNotPlaybackConfirmation() = runTest {
        val result = async { controller.playLocalAlarm(cycle, 0, settings) { allowed } }
        runCurrent()
        shadowOf(players.last()).invokeErrorListener(1, 0)
        assertThat(result.await().played).isFalse()
        assertThat(shadowOf(players.last()).state).isEqualTo(ShadowMediaPlayer.State.END)
    }

    private fun prepare() {
        shadowOf(players.last()).apply {
            setState(ShadowMediaPlayer.State.PREPARED)
            invokePreparedListener()
        }
    }

    @Test fun admissionCancellationPropagatesAndReleasesThePreparingOwner() = runTest {
        var cancelled = false
        val result = async {
            controller.playLocalAlarm(cycle, 0, settings) {
                if (cancelled) throw CancellationException("local_cancel")
                true
            }
        }
        runCurrent()
        cancelled = true
        prepare()
        try {
            result.await()
            error("Expected cancellation")
        } catch (_: CancellationException) {
            assertThat(shadowOf(players.last()).state).isEqualTo(ShadowMediaPlayer.State.END)
        }
    }

    @Test fun denialOfAnotherCycleDoesNotInterruptTheCurrentPlayer() = runTest {
        val result = async { controller.playLocalAlarm(cycle, 0, settings) { allowed } }
        runCurrent()
        prepare()
        result.await()
        assertThat(controller.playLocalAlarm(cycle.copy(ordinal = 2), 0, settings) { false }.played).isFalse()
        assertThat(shadowOf(players.last()).isReallyPlaying).isTrue()
        assertThat(players).hasSize(1)
    }

    @Test fun unreadableCustomUriRetainsTheExistingPreflightBuiltInFallback() = runTest {
        val result = async {
            controller.playLocalAlarm(cycle, 0, settings.copy(softAlertAudioUri = "file:///missing-local-alert.mp3")) { allowed }
        }
        runCurrent()
        prepare()
        shadowOf(Looper.getMainLooper()).idle()
        assertThat(result.await().fallbackUsed).isTrue()
        assertThat(result.await().played).isTrue()
        assertThat(shadowOf(players.last()).sourceUri).isNull()
    }

    @Test fun legacyUrgentLowStillRaisesItsImmediateFloorAndRestoresItsBaseline() = runTest {
        audio.setStreamVolume(AudioManager.STREAM_ALARM, 1, 0)
        val decision = GlucoseAlertDecision(
            state = GlucoseAlertState.LOW_NOW, direction = GlucoseAlertDirection.LOW,
            notifyKind = GlucoseAlertNotifyKind.LOW_NOW, nextState = GlucoseAlertRuntimeState(),
            lowThreshold = 4.0, highThreshold = 10.0, urgentLowThreshold = 4.0,
            pred5 = null, pred30 = null, pred60 = null, ciLow30 = null, ciHigh30 = null,
            currentGlucoseMmol = 3.8, currentGlucoseFresh = true, predictedMinutesToLow = null,
            trendDelta5Mmol = null, softActive = false, strongActive = true,
            repeatSuppressedByTrend = false, disableReason = null
        )
        val result = async { controller.playForDecision(decision, settings) }
        runCurrent()
        prepare()
        assertThat(result.await().played).isTrue()
        assertThat(audio.getStreamVolume(AudioManager.STREAM_ALARM))
            .isEqualTo(GlucoseAlertAudioVolumePolicy.minimumAudibleVolume(audio.getStreamMaxVolume(AudioManager.STREAM_ALARM)))
        assertThat(shadowOf(players.last()).audioAttributes.usage).isEqualTo(AudioAttributes.USAGE_ALARM)
        assertThat(shadowOf(players.last()).leftVolume).isEqualTo(0.92f)
        controller.stop()
        shadowOf(Looper.getMainLooper()).idle()
        assertThat(audio.getStreamVolume(AudioManager.STREAM_ALARM)).isEqualTo(1)
    }

    private fun testSettings() = AppSettings(
        nightscoutUrl = "", apiSecret = "", cloudBaseUrl = "", killSwitch = false,
        rootExperimentalEnabled = false, localBroadcastIngestEnabled = true,
        strictBroadcastSenderValidation = false, localNightscoutEnabled = true,
        localNightscoutPort = 17582, localCommandFallbackEnabled = true,
        localCommandPackage = "info.nightscout.androidaps", localCommandAction = "io.aaps.copilot.ACTION_COMMAND",
        insulinProfileId = InsulinActionProfileId.FIASP.name,
        baseTargetMmol = 5.5, postHypoThresholdMmol = 3.0, postHypoDeltaThresholdMmol5m = 0.2,
        postHypoTargetMmol = 4.4, postHypoDurationMinutes = 90, postHypoLookbackMinutes = 240,
        rulePostHypoEnabled = true, rulePatternEnabled = true, ruleSegmentEnabled = true,
        adaptiveControllerEnabled = true, rulePostHypoPriority = 50, rulePatternPriority = 40,
        ruleSegmentPriority = 30, adaptiveControllerPriority = 60, rulePostHypoCooldownMinutes = 30,
        rulePatternCooldownMinutes = 60, ruleSegmentCooldownMinutes = 60,
        adaptiveControllerRetargetMinutes = 5, adaptiveControllerSafetyProfile = "default",
        adaptiveControllerStaleMaxMinutes = 20, adaptiveControllerMaxActions6h = 24,
        adaptiveControllerMaxStepMmol = 0.6, patternMinSamplesPerWindow = 12,
        patternMinActiveDaysPerWindow = 3, patternLowRateTrigger = 0.25, patternHighRateTrigger = 0.25,
        analyticsLookbackDays = 30, maxActionsIn6Hours = 24, staleDataMaxMinutes = 20, exportFolderUri = null,
        softAlertAudioStartMs = 0, criticalAlertAudio1StartMs = 0, criticalAlertAudio2StartMs = 0
    )
}
