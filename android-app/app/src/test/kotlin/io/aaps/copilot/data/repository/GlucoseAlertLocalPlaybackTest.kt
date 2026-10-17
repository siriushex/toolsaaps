package io.aaps.copilot.data.repository

import android.app.Application
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Looper
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.aaps.copilot.config.AppSettings
import io.aaps.copilot.data.local.CopilotDatabase
import io.aaps.copilot.domain.alerts.*
import io.aaps.copilot.domain.predict.InsulinActionProfileId
import java.io.File
import java.time.Duration
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.flow.collect
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

    @Test fun actualOffCancelsNativePreparationAndLateCallbackCannotRestartIt() = runTest {
        val database = Room.inMemoryDatabaseBuilder(context, CopilotDatabase::class.java)
            .allowMainThreadQueries().build()
        val created = CompletableDeferred<MediaPlayer>()
        ShadowMediaPlayer.setCreateListener { player, _ -> players += player; created.complete(player) }
        val machine = EpisodeAlertDeliveryStateMachine(RoomEpisodeAlertReceiptStore(database),
            revokeRiskPlayback = controller::stop)
        try {
            val delivery = async {
                machine.coordinate(EpisodeAlertSignal(GlucoseAlertState.LOW_NOW, GlucoseAlertDirection.LOW, 1_000_000)) {
                    val result = controller.playForDecision(urgentDecision(), settings)
                    GlucoseAlertDeliveryResult(audioPlayed = result.played).toAlertSideEffectResult()
                }
            }
            val player = created.await()
            machine.muteFor(1_000_000, 30 * 60_000L)
            shadowOf(Looper.getMainLooper()).idle()
            assertThat(shadowOf(player).state).isEqualTo(ShadowMediaPlayer.State.END)
            // The shadow resets its own state to PREPARED even after release.
            shadowOf(player).invokePreparedListener()
            assertThat(delivery.await().receiptResult).isEqualTo(AlertReceiptResult.FAILED)
            assertThat(shadowOf(player).state).isNotEqualTo(ShadowMediaPlayer.State.STARTED)
            assertThat(controller.playbackState.value.activeSlot).isNull()
            assertThat(shadowOf(audio).lastAbandonedAudioFocusRequest).isNotNull()
        } finally { database.close() }
    }

    @Test fun actualOffStopsAlreadyPlayingNativeAudioBeforeRoomLockIsAvailable() = runTest {
        val database = Room.inMemoryDatabaseBuilder(context, CopilotDatabase::class.java)
            .allowMainThreadQueries().build()
        val lock = EpisodeAlertOperationLocks.forIdentity(database)
        val playback = async { controller.playForDecision(urgentDecision(), settings) }
        runCurrent(); prepare()
        assertThat(playback.await().played).isTrue()
        val player = players.last()
        val machine = EpisodeAlertDeliveryStateMachine(RoomEpisodeAlertReceiptStore(database),
            revokeRiskPlayback = controller::stop)
        lock.lock()
        val off = async(start = CoroutineStart.UNDISPATCHED) { machine.muteFor(1_000_000, 30 * 60_000L) }
        try {
            shadowOf(Looper.getMainLooper()).idle()
            assertThat(off.isCompleted).isFalse()
            assertThat(shadowOf(player).state).isEqualTo(ShadowMediaPlayer.State.END)
            assertThat(controller.playbackState.value.activeSlot).isNull()
        } finally { lock.unlock(); off.await(); database.close() }
    }

    @Test fun deniedSignalDoesNotCreatePlayerOrRequestFocus() = runTest {
        allowed = false
        val result = controller.playLocalAlarm(cycle, 0, settings) { allowed }
        assertThat(result.played).isFalse()
        assertThat(players).isEmpty()
        assertThat(shadowOf(audio).lastAudioFocusRequest).isNull()
    }

    @Test fun preparationRestoresLegacyBoostBeforeTheNewVolumeLeaseReadsItsBaseline() = runTest {
        audio.setStreamVolume(AudioManager.STREAM_ALARM, 1, 0)
        val legacy = async { controller.playForDecision(urgentDecision(), settings) }
        runCurrent(); prepare()
        assertThat(legacy.await().played).isTrue()
        assertThat(audio.getStreamVolume(AudioManager.STREAM_ALARM)).isGreaterThan(1)
        val owner = LocalAlarmAudioOwner(cycle, { true }, {}, { true })
        assertThat(controller.prepareLocalAlarm(owner)).isTrue()
        assertThat(AndroidAlarmVolumePort(audio).read().index).isEqualTo(1)
        assertThat(shadowOf(players.single()).state).isEqualTo(ShadowMediaPlayer.State.END)
        assertThat(controller.ownsLocalAlarm(cycle)).isTrue()
        controller.stopLocalAlarm(cycle)
    }

    @Test fun deniedPreparationDoesNotInterruptTheExistingUrgentClip() = runTest {
        val legacy = async { controller.playForDecision(urgentDecision(), settings) }
        runCurrent(); prepare(); legacy.await()
        assertThat(controller.prepareLocalAlarm(LocalAlarmAudioOwner(cycle, { false }, {}, { true }))).isFalse()
        assertThat(shadowOf(players.single()).isReallyPlaying).isTrue()
    }

    @Test fun legacyReplacementWaitsForTheExactOwnersVolumeCleanup() = runTest {
        val released = CompletableDeferred<Boolean>()
        var revoked = 0
        val owner = LocalAlarmAudioOwner(cycle, { true }, { revoked++ }, { released.await() })
        assertThat(controller.prepareLocalAlarm(owner)).isTrue()
        audio.setStreamVolume(AudioManager.STREAM_ALARM, 5, 0)
        val legacy = async { controller.playForDecision(urgentDecision(), settings) }
        runCurrent()
        assertThat(revoked).isEqualTo(1)
        assertThat(players).isEmpty()
        assertThat(legacy.isCompleted).isFalse()
        controller.stopLocalAlarm(cycle)
        audio.setStreamVolume(AudioManager.STREAM_ALARM, 1, 0)
        released.complete(true)
        runCurrent(); prepare()
        assertThat(legacy.await().played).isTrue()
        controller.stop(); shadowOf(Looper.getMainLooper()).idle()
        assertThat(audio.getStreamVolume(AudioManager.STREAM_ALARM)).isEqualTo(1)
    }

    @Test fun retiredOwnerStillBlocksPreviewUntilVolumeRestorationCompletes() = runTest {
        val released = CompletableDeferred<Boolean>()
        val owner = LocalAlarmAudioOwner(cycle, { true }, {}, { released.await() })
        assertThat(controller.prepareLocalAlarm(owner)).isTrue()
        controller.stopLocalAlarm(cycle)
        val preview = async { controller.preview(GlucoseAlertAudioSlot.SOFT, settings) }
        runCurrent()
        assertThat(players).isEmpty()
        assertThat(preview.isCompleted).isFalse()
        released.complete(true)
        runCurrent(); prepare()
        assertThat(preview.await().played).isTrue()
    }

    @Test fun unknownCleanupBlocksReplacementWithoutStartingAnotherPlayer() = runTest {
        assertThat(controller.prepareLocalAlarm(LocalAlarmAudioOwner(cycle, { true }, {}, { false }))).isTrue()
        val result = controller.playForDecision(urgentDecision(), settings)
        assertThat(result.played).isFalse()
        assertThat(result.failureReason).isEqualTo("local_audio_cleanup_unconfirmed")
        assertThat(controller.prepareLocalAlarm(LocalAlarmAudioOwner(cycle.copy(ordinal = 2), { true }, {}, { true }))).isFalse()
        assertThat(players).isEmpty()
    }

    @Test fun offWhileWaitingForCleanupCannotStartTheReplacementLater() = runTest {
        val released = CompletableDeferred<Boolean>()
        assertThat(controller.prepareLocalAlarm(LocalAlarmAudioOwner(cycle, { true }, {}, { released.await() }))).isTrue()
        val legacy = async { controller.playForDecision(urgentDecision(), settings) }
        runCurrent()
        controller.stop()
        released.complete(true)
        runCurrent()
        assertThat(legacy.await().played).isFalse()
        assertThat(players).isEmpty()
    }

    @Test fun completedLeaseCanBeReplacedButActiveLeaseCannotBeStolen() = runTest {
        val first = LocalAlarmAudioOwner(cycle, { true }, {}, { true })
        val second = LocalAlarmAudioOwner(cycle.copy(ordinal = 2), { true }, {}, { true })
        assertThat(controller.prepareLocalAlarm(first)).isTrue()
        assertThat(controller.prepareLocalAlarm(second)).isFalse()
        controller.stopLocalAlarm(cycle)
        assertThat(controller.prepareLocalAlarm(second)).isTrue()
        controller.stopLocalAlarm(cycle)
        assertThat(controller.ownsLocalAlarm(second.cycle)).isTrue()
        controller.stopLocalAlarm(second.cycle)
    }

    @Test fun preparationCancellationReleasesTheReservedRequest() = runTest {
        var checks = 0
        val first = LocalAlarmAudioOwner(cycle, { if (++checks == 2) throw CancellationException("prepare_cancel"); true }, {}, { true })
        val preparation = async { controller.prepareLocalAlarm(first) }
        try { preparation.await(); error("Expected cancellation") } catch (_: CancellationException) { }
        assertThat(controller.ownsLocalAlarm(cycle)).isFalse()
        val next = LocalAlarmAudioOwner(cycle.copy(ordinal = 2), { true }, {}, { true })
        assertThat(controller.prepareLocalAlarm(next)).isTrue()
        controller.stopLocalAlarm(next.cycle)
    }

    @Test fun reentrantLegacyReplacementWinsOverAnUnfinishedPreparation() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val initial = async { controller.playForDecision(urgentDecision(), settings) }
        runCurrent(); prepare(); initial.await()
        var replaced = false
        var replacement: Deferred<GlucoseAlertAudioPlaybackResult>? = null
        val observer = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            controller.playbackState.collect {
                if (it.activeSlot == null && !replaced) {
                    replaced = true
                    replacement = async(start = CoroutineStart.UNDISPATCHED) {
                        controller.preview(GlucoseAlertAudioSlot.SOFT, settings)
                    }
                }
            }
        }
        val owner = LocalAlarmAudioOwner(cycle, { true }, {}, { true })
        assertThat(controller.prepareLocalAlarm(owner)).isFalse()
        runCurrent(); prepare()
        assertThat(replacement!!.await().played).isTrue()
        assertThat(controller.ownsLocalAlarm(cycle)).isFalse()
        observer.cancel()
    }

    @Test fun actualExecutorCleansItsVolumeBeforeNativeUrgentPlaybackStarts() = actualExecutorHandoff(false)

    @Test fun actualExecutorReleasesAudioBeforeBusyJournalWrite() = actualExecutorHandoff(true)

    private fun actualExecutorHandoff(blockJournal: Boolean) = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val database = Room.inMemoryDatabaseBuilder(context, CopilotDatabase::class.java)
            .allowMainThreadQueries().build()
        val environment = LocalAlarmEnvironment(true, true, now, 10_000, 1)
        val evidence = LocalAlarmEvidence(cycle.key, 1, cycle.level, 1, now, 900_000, true)
        val started = CompletableDeferred<Unit>()
        val created = Channel<MediaPlayer>(Channel.UNLIMITED)
        ShadowMediaPlayer.setCreateListener { player, _ -> players += player; created.trySend(player) }
        val store = RoomLocalAlarmStore(database) { environment }
        val executor = LocalAlarmCycleExecutor(store, AndroidAlarmVolumePort(audio), controller,
            current = { LocalAlarmExecutionContext(evidence, environment, true, true) },
            waitMs = { started.complete(Unit); awaitCancellation() })
        audio.setStreamVolume(AudioManager.STREAM_ALARM, 1, 0)
        val persistence = EpisodeAlertOperationLocks.forIdentity(database)
        var locked = false
        try {
            val local = async { executor.execute(settings) }
            select<Unit> {
                created.onReceive { }
                local.onAwait { error("Local execution ended before audio: ${it.status}") }
            }
            runCurrent(); prepare()
            select<Unit> {
                started.onAwait { }
                local.onAwait { error("Local execution ended before wait: ${it.status}") }
            }
            if (blockJournal) { persistence.lock(); locked = true }
            val legacy = async { controller.playForDecision(urgentDecision(), settings) }
            select<Unit> {
                created.onReceive { }
                legacy.onAwait { error("Native playback ended before preparation: ${it.failureReason}") }
            }
            runCurrent(); prepare()
            assertThat(legacy.await().played).isTrue()
            if (locked) {
                assertThat(local.isCompleted).isFalse()
                persistence.unlock(); locked = false
            }
            local.join()
            assertThat(local.isCancelled).isTrue()
            assertThat(currentCoroutineContext().isActive).isTrue()
            assertThat(shadowOf(players.first()).state).isEqualTo(ShadowMediaPlayer.State.END)
            controller.stop(); shadowOf(Looper.getMainLooper()).idle()
            assertThat(audio.getStreamVolume(AudioManager.STREAM_ALARM)).isEqualTo(1)
        } finally {
            if (locked) persistence.unlock()
            created.close(); database.close()
        }
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

    @Test fun removedSoftStepsCannotCreateAPlayerNearTheCycleDeadline() = runTest {
        now = 54_000
        val result = async { controller.playLocalAlarm(cycle, 3, settings.copy(softAlertAudioDurationMs = 5_000)) { allowed } }
        runCurrent()
        assertThat(result.await().played).isFalse()
        assertThat(players).isEmpty()
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

    @Test fun localEntryPointOnlyHasScopedOwnerAndNoTherapyNetworkWriter() {
        val root = File("src/main/kotlin/io/aaps/copilot")
        val consumers = root.walkTopDown().filter { it.extension == "kt" && it.name != "GlucoseAlertAudioController.kt" }
            .filter { it.readText().contains(Regex("playLocalAlarm\\s*\\(|stopLocalAlarm\\s*\\(")) }
            .map { it.relativeTo(root).path }.toList()
        assertThat(consumers).containsExactly("data/repository/LocalAlarmCycleExecutor.kt", "data/repository/GlucoseAlarmOwner.kt")
        val executor = File(root, "data/repository/LocalAlarmCycleExecutor.kt").readText()
        listOf("AutomationRepository", "ForecastRepository", "Telegram", "MediaPlayer(")
            .forEach { assertThat(executor).doesNotContain(it) }
        val runners = root.walkTopDown().filter { it.extension == "kt" && it.name != "LocalAlarmCycleExecutor.kt" }
            .filter { it.readText().contains(Regex("LocalAlarmCycleExecutor\\s*\\(")) }.map { it.relativeTo(root).path }.toList()
        assertThat(runners).containsExactly("data/repository/LocalAlarmCoordinator.kt")
        val coordinators = root.walkTopDown().filter { it.extension == "kt" && it.name != "LocalAlarmCoordinator.kt" }
            .filter { it.readText().contains(Regex("LocalAlarmCoordinator\\s*\\(")) }.map { it.relativeTo(root).path }.toList()
        assertThat(coordinators).containsExactly("data/repository/GlucoseAlarmOwner.kt")
        val owner = File(root, "data/repository/GlucoseAlarmOwner.kt").readText()
        listOf("AutomationRepository", "ForecastRepository", "Telegram", "MediaPlayer(", "TargetManager")
            .forEach { assertThat(owner).doesNotContain(it) }
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
        now = 14_000
        val result = async {
            controller.playLocalAlarm(cycle, 0, settings.copy(softAlertAudioDurationMs = 5_000)) {
                if (players.lastOrNull()?.let { shadowOf(it).isReallyPlaying } == true) now = 15_000
                true
            }
        }
        runCurrent()
        prepare()
        assertThat(result.await().played).isTrue()
        now = 19_000
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(4))
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

    @Test fun queuedGlobalStopCannotKillANewerUrgentLegacyRequest() = runTest {
        val first = async { controller.preview(GlucoseAlertAudioSlot.SOFT, settings) }
        runCurrent(); prepare(); first.await()
        controller.stop()
        val next = async { controller.playForDecision(urgentDecision(), settings) }
        runCurrent(); prepare()
        assertThat(next.await().played).isTrue()
        shadowOf(Looper.getMainLooper()).idle()
        assertThat(shadowOf(players.last()).isReallyPlaying).isTrue()
        assertThat(controller.playbackState.value.activeSlot).isEqualTo(GlucoseAlertAudioSlot.CRITICAL_PRIMARY)
    }

    @Test fun queuedPreviewStopCannotKillANewerPreviewRequest() = runTest {
        val first = async { controller.preview(GlucoseAlertAudioSlot.SOFT, settings) }
        runCurrent(); prepare(); first.await()
        controller.stopPreview()
        val next = async { controller.preview(GlucoseAlertAudioSlot.CRITICAL_PRIMARY, settings) }
        runCurrent(); prepare()
        assertThat(next.await().played).isTrue()
        shadowOf(Looper.getMainLooper()).idle()
        assertThat(shadowOf(players.last()).isReallyPlaying).isTrue()
    }

    @Test fun lateLegacyCancellationCannotKillANewerLocalRequest() = lateLegacyCancellation(local = true)
    @Test fun lateLegacyCancellationCannotKillANewerLegacyRequest() = lateLegacyCancellation(local = false)

    private fun lateLegacyCancellation(local: Boolean) = runTest {
        val first = async(StandardTestDispatcher(testScheduler)) { controller.playForDecision(urgentDecision(), settings) }
        runCurrent()
        val next = async(UnconfinedTestDispatcher(testScheduler)) {
            if (local) controller.playLocalAlarm(cycle, 0, settings) { allowed }
            else controller.playForDecision(urgentDecision(), settings)
        }
        prepare()
        assertThat(next.await().played).isTrue()
        first.cancel(); runCurrent(); first.join()
        shadowOf(Looper.getMainLooper()).idle()
        assertThat(shadowOf(players.last()).isReallyPlaying).isTrue()
    }

    @Test fun currentOffRevokesPreparingRequestBeforeQueuedCleanupRuns() = runTest {
        val result = async { controller.playLocalAlarm(cycle, 0, settings) { allowed } }
        runCurrent()
        controller.stop()
        prepare()
        assertThat(result.await().played).isFalse()
        assertThat(shadowOf(players.last()).state).isEqualTo(ShadowMediaPlayer.State.END)
    }

    @Test fun duplicateLegacyPreparedCallbackCannotExtendTheClip() = runTest {
        val result = async { controller.preview(GlucoseAlertAudioSlot.SOFT, settings.copy(softAlertAudioDurationMs = 2_000)) }
        runCurrent(); prepare(); assertThat(result.await().played).isTrue()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
        shadowOf(players.last()).invokePreparedListener()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
        assertThat(shadowOf(players.last()).state).isEqualTo(ShadowMediaPlayer.State.END)
    }

    @Test fun offInsidePostStartClockReadCannotConfirmTheRevokedRequest() = runTest {
        controller = GlucoseAlertAudioController(context) {
            if (players.lastOrNull()?.let { shadowOf(it).isReallyPlaying } == true) controller.stop()
            now
        }
        val result = async { controller.playLocalAlarm(cycle, 0, settings) { allowed } }
        runCurrent(); prepare()
        assertThat(result.await().played).isFalse()
        assertThat(shadowOf(players.last()).state).isEqualTo(ShadowMediaPlayer.State.END)
    }

    @Test fun offInsideStopTimerCaptureCannotConfirmTheRevokedRequest() = runTest {
        var playingReads = 0
        controller = GlucoseAlertAudioController(context) {
            if (players.lastOrNull()?.let { shadowOf(it).isReallyPlaying } == true && ++playingReads == 2) controller.stop()
            now
        }
        val result = async { controller.playLocalAlarm(cycle, 0, settings) { allowed } }
        runCurrent(); prepare()
        assertThat(result.await().played).isFalse()
        assertThat(shadowOf(players.last()).state).isEqualTo(ShadowMediaPlayer.State.END)
    }

    @Test fun globalOffInsideLocalPreflightCannotCreateAPlayer() = runTest {
        val result = controller.playLocalAlarm(cycle, 0, settings) {
            controller.stop()
            true
        }
        assertThat(result.played).isFalse(); assertThat(players).isEmpty()
    }

    @Test fun legacyCancellationBeforeMainDispatchCannotStopANewerRequest() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val old = async(start = CoroutineStart.UNDISPATCHED) { controller.playForDecision(urgentDecision(), settings) }
        controller.stop()
        old.cancel()
        val next = async { controller.playForDecision(urgentDecision(), settings) }
        runCurrent(); prepare()
        assertThat(next.await().played).isTrue()
        shadowOf(Looper.getMainLooper()).idle()
        assertThat(shadowOf(players.last()).isReallyPlaying).isTrue()
        old.join()
    }

    @Test fun stopFromPlaybackObserverCannotConfirmRevokedPlayback() = runTest {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            controller.playbackState.collect { state ->
                if (state.activeSlot != null) controller.stop()
            }
        }
        val result = async { controller.preview(GlucoseAlertAudioSlot.SOFT, settings) }
        runCurrent()
        prepare()
        assertThat(result.await().played).isFalse()
        shadowOf(Looper.getMainLooper()).idle()
        assertThat(shadowOf(players.last()).state).isEqualTo(ShadowMediaPlayer.State.END)
    }

    @Test fun slowPlaybackObserverCannotMoveTheAbsoluteStopDeadline() = runTest {
        now = 14_000
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            controller.playbackState.collect { state ->
                if (state.activeSlot != null) {
                    now = 15_000
                    shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
                }
            }
        }
        val result = async {
            controller.playLocalAlarm(cycle, 0, settings.copy(softAlertAudioDurationMs = 5_000)) { allowed }
        }
        runCurrent()
        prepare()
        assertThat(result.await().played).isTrue()
        now = 19_000
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(4))
        assertThat(shadowOf(players.last()).state).isEqualTo(ShadowMediaPlayer.State.END)
    }

    @Test fun replacementFromPlaybackObserverKeepsItsPlayerAndDoesNotConfirmOldPlayback() = runTest {
        var replacement: Deferred<GlucoseAlertAudioPlaybackResult>? = null
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            controller.playbackState.collect { state ->
                if (state.activeSlot != null && replacement == null) {
                    replacement = backgroundScope.async(start = CoroutineStart.UNDISPATCHED) {
                        controller.preview(GlucoseAlertAudioSlot.CRITICAL_PRIMARY, settings)
                    }
                }
            }
        }
        val first = async { controller.preview(GlucoseAlertAudioSlot.SOFT, settings) }
        runCurrent()
        prepare()
        assertThat(first.await().played).isFalse()
        assertThat(players).hasSize(2)
        assertThat(shadowOf(players.last()).state).isNotEqualTo(ShadowMediaPlayer.State.END)
        prepare()
        assertThat(requireNotNull(replacement).await().played).isTrue()
    }

    @Test fun replacementFromIdleObserverSurvivesTheOldPhysicalCleanup() = runTest {
        val first = async { controller.preview(GlucoseAlertAudioSlot.SOFT, settings) }
        runCurrent()
        prepare()
        assertThat(first.await().played).isTrue()
        var replacement: Deferred<GlucoseAlertAudioPlaybackResult>? = null
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            controller.playbackState.collect { state ->
                if (state.activeSlot == null && replacement == null) {
                    replacement = backgroundScope.async(start = CoroutineStart.UNDISPATCHED) {
                        controller.preview(GlucoseAlertAudioSlot.CRITICAL_PRIMARY, settings)
                    }
                }
            }
        }
        controller.stop()
        shadowOf(Looper.getMainLooper()).idle()
        assertThat(players).hasSize(2)
        assertThat(shadowOf(players.last()).state).isNotEqualTo(ShadowMediaPlayer.State.END)
        prepare()
        assertThat(requireNotNull(replacement).await().played).isTrue()
    }

    private fun urgentDecision() = GlucoseAlertDecision(
        state = GlucoseAlertState.LOW_NOW, direction = GlucoseAlertDirection.LOW,
        notifyKind = GlucoseAlertNotifyKind.LOW_NOW, nextState = GlucoseAlertRuntimeState(),
        lowThreshold = 4.0, highThreshold = 10.0, urgentLowThreshold = 4.0,
        pred5 = null, pred30 = null, pred60 = null, ciLow30 = null, ciHigh30 = null,
        currentGlucoseMmol = 3.8, currentGlucoseFresh = true, predictedMinutesToLow = null,
        trendDelta5Mmol = null, softActive = false, strongActive = true,
        repeatSuppressedByTrend = false, disableReason = null
    )

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
