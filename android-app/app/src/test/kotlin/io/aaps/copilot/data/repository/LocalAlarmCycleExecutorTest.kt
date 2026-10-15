package io.aaps.copilot.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.aaps.copilot.config.AppSettings
import io.aaps.copilot.data.local.CopilotDatabase
import io.aaps.copilot.domain.alerts.*
import kotlinx.coroutines.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class LocalAlarmCycleExecutorTest {
    private lateinit var db: CopilotDatabase
    private lateinit var store: RoomLocalAlarmStore
    private var environment = LocalAlarmEnvironment(true, true, 1_000, 10_000, 2)
    private var signal = LocalAlarmEvidence(LocalAlarmKey(LocalAlarmSourceKind.GLUCOSE, "source-a"),
        1, LocalAlarmLevel.WARNING_30, 2, 1_000, 900_000, true)
    private var capability = true
    private val timing = LocalAlarmTiming()
    private val settings = testSettings()
    private val volume = Volume()
    private val audio = Audio()
    private var onWait: suspend (Long) -> Unit = {}

    private inner class Volume : AlarmVolumePort {
        var index = 1
        var available = true
        var cancelReads = false
        var onRead: () -> Unit = {}
        val writes = mutableListOf<Int>()
        override fun read(): AlarmVolumeSnapshot {
            onRead()
            if (cancelReads) throw CancellationException("cleanup_cancel")
            check(available)
            return AlarmVolumeSnapshot(index, 10, false)
        }
        override fun set(index: Int) { this.index = index; writes += index }
    }

    private inner class Audio : LocalAlarmAudioPort {
        val steps = mutableListOf<Int>()
        val levels = mutableListOf<Int>()
        val times = mutableListOf<Long>()
        val stopped = mutableListOf<LocalAlarmCycle>()
        var fail = false
        var stopFails = false
        var onStart: suspend (LocalAlarmCycle) -> Unit = {}
        override suspend fun playLocalAlarm(cycle: LocalAlarmCycle, stepIndex: Int,
            settings: AppSettings, admitted: (LocalAlarmCycle) -> Boolean): GlucoseAlertAudioPlaybackResult {
            assertTrue(admitted(cycle))
            steps += stepIndex; levels += volume.index; times += environment.nowElapsedMs
            onStart(cycle)
            return GlucoseAlertAudioPlaybackResult(played = !fail, failureReason = if (fail) "test_failure" else null,
                durationMs = if (!cycle.level.strong) settings.softAlertAudioDurationMs
                    else if (cycle.ordinal % 2L == 1L) settings.criticalAlertAudio1DurationMs
                    else settings.criticalAlertAudio2DurationMs)
        }
        override fun stopLocalAlarm(cycle: LocalAlarmCycle) {
            stopped += cycle
            if (stopFails) error("stop_unconfirmed")
        }
    }

    private fun executor(onClaimed: (LocalAlarmCycle) -> Unit = {},
        onFinished: (LocalAlarmExecutionResult) -> Unit = {}) = LocalAlarmCycleExecutor(store, volume, audio,
        current = { LocalAlarmExecutionContext(signal, environment, capability, capability) },
        dispatcher = Dispatchers.Unconfined,
        waitMs = { ms -> onWait(ms); environment = environment.copy(
            nowElapsedMs = environment.nowElapsedMs + ms, nowWallMs = environment.nowWallMs + ms) },
        onClaimed = onClaimed, onFinished = onFinished)

    private suspend fun result(cycle: LocalAlarmCycle) = LocalAlarmPersistenceCodec.decodeResult(
        db.alertLocalDao().cycle(cycle.key.kind.name, cycle.key.id, cycle.generation, cycle.ordinal)!!.resultJson)!!
    private suspend fun row(cycle: LocalAlarmCycle) =
        db.alertLocalDao().cycle(cycle.key.kind.name, cycle.key.id, cycle.generation, cycle.ordinal)!!

    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), CopilotDatabase::class.java)
            .allowMainThreadQueries().build()
        store = RoomLocalAlarmStore(db) { environment }
    }
    @After fun close() { db.close() }

    @Test fun journalAdmissionNeverClaimsOrMutatesAndReadsActualOff() = runBlocking {
        val cycle = store.evaluate(signal, timing)!!.startCycle!!
        val before = db.alertLocalDao().state(signal.key.kind.name, signal.key.id)
        assertTrue(store.admits(signal, cycle, timing))
        assertEquals(before, db.alertLocalDao().state(signal.key.kind.name, signal.key.id))
        RoomEpisodeAlertReceiptStore(db).writeMuteUntil(90_000, 10_000)
        assertFalse(store.admits(signal, cycle, timing))
        assertEquals(before, db.alertLocalDao().state(signal.key.kind.name, signal.key.id))
        assertEquals("CLAIMED", row(cycle).status)
        environment = environment.copy(nowElapsedMs = 301_000, nowWallMs = 100_000)
        assertFalse(store.admits(signal, cycle, timing))
        assertEquals(1L, db.alertLocalDao().state(signal.key.kind.name, signal.key.id)!!.ordinal)
    }

    @Test fun softCycleRaisesBeforeEachClipAndRecordsOnlyConfirmedWork() = runBlocking {
        val outcome = executor().execute(settings)
        assertEquals(LocalAlarmExecutionStatus.FINISHED, outcome.status)
        assertEquals(listOf(0, 1, 2, 3), audio.steps)
        assertEquals(listOf(3, 5, 8, 10), audio.levels)
        assertEquals(listOf(1_000L, 16_000L, 31_000L, 46_000L), audio.times)
        assertEquals(listOf(3, 5, 8, 10, 1), volume.writes)
        val cycle = outcome.cycle!!
        assertEquals("FINISHED", row(cycle).status)
        assertEquals(listOf(25, 50, 75, 100), result(cycle).steps.map { it.reachedPercent })
        assertTrue(result(cycle).steps.all { it.notification == LocalAlarmNotificationResult.NOT_REQUESTED &&
            it.vibration == LocalAlarmVibrationResult.NOT_REQUESTED })
        assertTrue(environment.nowElapsedMs <= cycle.deadlineElapsedMs)
    }

    @Test fun lowNowConfirmsImmediateFloorAndStartsOneClipOnly() = runBlocking {
        signal = signal.copy(level = LocalAlarmLevel.LOW_NOW)
        val outcome = executor().execute(settings)
        assertEquals(LocalAlarmExecutionStatus.FINISHED, outcome.status)
        assertEquals(listOf(0), audio.steps)
        assertEquals(listOf(7), audio.levels)
        assertEquals(listOf(7, 9, 10, 1), volume.writes)
        assertEquals(listOf(70, 85, 100, 100), result(outcome.cycle!!).steps.map { it.reachedPercent })
        assertEquals(listOf(LocalAlarmAudioResult.STARTED, LocalAlarmAudioResult.NOT_REQUESTED,
            LocalAlarmAudioResult.NOT_REQUESTED, LocalAlarmAudioResult.NOT_REQUESTED), result(outcome.cycle!!).steps.map { it.audio })
    }

    @Test fun actualRoomOffDuringWaitStopsAndRestoresWithoutAnotherStep() = runBlocking {
        onWait = { RoomEpisodeAlertReceiptStore(db).writeMuteUntil(90_000, environment.nowWallMs) }
        val outcome = executor().execute(settings)
        assertEquals(LocalAlarmExecutionStatus.DENIED, outcome.status)
        assertEquals(listOf(0), audio.steps)
        assertEquals(listOf(3, 1), volume.writes)
        assertEquals("CANCELLED", row(outcome.cycle!!).status)
    }

    @Test fun sourceGenerationChangeDoesNotReuseClaim() = runBlocking {
        onWait = { signal = signal.copy(generation = 2) }
        val outcome = executor().execute(settings)
        assertEquals(LocalAlarmExecutionStatus.DENIED, outcome.status)
        assertEquals(listOf(0), audio.steps)
        assertEquals("CANCELLED", row(outcome.cycle!!).status)
    }

    @Test fun alreadyClaimedCycleIsNotReplayed() = runBlocking {
        val claim = store.evaluate(signal, timing)!!.startCycle!!
        val outcome = executor().execute(settings)
        assertEquals(LocalAlarmExecutionStatus.NOT_STARTED, outcome.status)
        assertTrue(audio.steps.isEmpty()); assertTrue(volume.writes.isEmpty())
        assertEquals("CLAIMED", row(claim).status)
    }

    @Test fun unavailableCapabilityDoesNotCreateAClaim() = runBlocking {
        capability = false
        assertEquals(LocalAlarmExecutionStatus.DENIED, executor().execute(settings).status)
        assertNull(db.alertLocalDao().state(signal.key.kind.name, signal.key.id))
        assertTrue(volume.writes.isEmpty())
    }

    @Test fun audioFailureStopsRampAndRecordsFailureSeparately() = runBlocking {
        audio.fail = true
        val outcome = executor().execute(settings)
        assertEquals(LocalAlarmExecutionStatus.AUDIO_FAILED, outcome.status)
        assertEquals(listOf(0), audio.steps)
        assertEquals(listOf(3, 1), volume.writes)
        assertEquals(LocalAlarmAudioResult.FAILED, result(outcome.cycle!!).steps.single().audio)
    }

    @Test fun observedManualVolumeOverrideStopsWithoutRestoration() = runBlocking {
        onWait = { volume.index = 2 }
        val outcome = executor().execute(settings)
        assertEquals(LocalAlarmExecutionStatus.VOLUME_UNAVAILABLE, outcome.status)
        assertEquals(listOf(0), audio.steps)
        assertEquals(listOf(3), volume.writes)
        assertEquals(2, volume.index)
    }

    @Test fun missedIntermediateWindowIsSkippedNotReplayed() = runBlocking {
        var first = true
        onWait = { if (first) { first = false; environment = environment.copy(nowElapsedMs = 16_000) } }
        val outcome = executor().execute(settings)
        assertEquals(LocalAlarmExecutionStatus.FINISHED, outcome.status)
        assertEquals(listOf(0, 2, 3), audio.steps)
        assertEquals(listOf(0, 2, 3), result(outcome.cycle!!).steps.map { it.index })
    }

    @Test fun cancellationPropagatesAndCleansUpOnce() = runBlocking {
        onWait = { throw CancellationException("local_cancel") }
        try { executor().execute(settings); fail("Expected cancellation") } catch (_: CancellationException) { }
        assertEquals(listOf(0), audio.steps)
        assertEquals(listOf(3, 1), volume.writes)
        assertEquals(1, audio.stopped.size)
        assertEquals("CANCELLED", row(audio.stopped.single()).status)
    }

    @Test fun concurrentCallerIsBusyWithoutClaimingAnotherSource() = runBlocking {
        val started = CompletableDeferred<Unit>(); val proceed = CompletableDeferred<Unit>()
        audio.onStart = { started.complete(Unit); proceed.await() }
        val owner = executor()
        val first = async { owner.execute(settings) }
        started.await()
        signal = signal.copy(key = LocalAlarmKey(LocalAlarmSourceKind.GLUCOSE, "source-b"))
        assertEquals(LocalAlarmExecutionStatus.BUSY, owner.execute(settings).status)
        assertNull(db.alertLocalDao().state(signal.key.kind.name, signal.key.id))
        proceed.complete(Unit)
        assertEquals(LocalAlarmExecutionStatus.DENIED, first.await().status)
    }

    @Test fun cleanupCancellationCannotLeaveExecutorPermanentlyBusy() = runBlocking {
        val owner = executor()
        onWait = { if (audio.steps.size == 4) volume.cancelReads = true }
        try { owner.execute(settings); fail("Expected cancellation") } catch (_: CancellationException) { }
        volume.cancelReads = false
        onWait = {}
        assertEquals("CANCELLED", row(audio.stopped.first()).status)
        environment = environment.copy(nowElapsedMs = 301_000, nowWallMs = 310_000)
        assertEquals(LocalAlarmExecutionStatus.FINISHED, owner.execute(settings).status)
    }

    @Test fun unconfirmedAudioStopCannotBeReportedAsFinished() = runBlocking {
        audio.stopFails = true
        val outcome = executor().execute(settings)
        assertNotEquals(LocalAlarmExecutionStatus.FINISHED, outcome.status)
        assertEquals(listOf(3, 5, 8, 10, 1), volume.writes)
    }

    @Test fun evidenceExpiryEndsWaitBeforeNextStep() = runBlocking {
        signal = signal.copy(validUntilElapsedMs = 6_000)
        val outcome = executor().execute(settings)
        assertEquals(LocalAlarmExecutionStatus.DENIED, outcome.status)
        assertEquals(6_000L, environment.nowElapsedMs)
        assertEquals(listOf(0), audio.steps)
        assertEquals(listOf(3, 1), volume.writes)
    }

    @Test fun bootChangeCancelsOldCycle() = runBlocking {
        onWait = { environment = environment.copy(bootCount = 3) }
        val outcome = executor().execute(settings)
        assertNotEquals(LocalAlarmExecutionStatus.FINISHED, outcome.status)
        assertEquals(listOf(0), audio.steps)
        assertEquals(listOf(3, 1), volume.writes)
    }

    @Test fun capabilityLossCancelsBeforeAnotherRaise() = runBlocking {
        onWait = { capability = false }
        val outcome = executor().execute(settings)
        assertEquals(LocalAlarmExecutionStatus.DENIED, outcome.status)
        assertEquals(listOf(3, 1), volume.writes)
    }

    @Test fun unconfirmedInitialVolumeNeverStartsAudio() = runBlocking {
        volume.available = false
        val outcome = executor().execute(settings)
        assertEquals(LocalAlarmExecutionStatus.VOLUME_UNAVAILABLE, outcome.status)
        assertTrue(audio.steps.isEmpty()); assertTrue(volume.writes.isEmpty())
        assertEquals("CANCELLED", row(outcome.cycle!!).status)
    }

    @Test fun explicitCancellationBindsTheExactCycleAndDoesNotReplay() = runBlocking {
        val entered = CompletableDeferred<LocalAlarmCycle>(); val proceed = CompletableDeferred<Unit>()
        audio.onStart = { entered.complete(it); proceed.await() }
        val owner = executor()
        val task = async { owner.execute(settings) }
        val cycle = entered.await()
        assertFalse(owner.cancel(cycle.copy(ordinal = cycle.ordinal + 1)))
        assertTrue(owner.cancel(cycle))
        try { task.await(); fail("Expected cancellation") } catch (_: CancellationException) { }
        assertFalse(owner.cancel(cycle))
        assertEquals(listOf(cycle), audio.stopped)
        assertEquals(listOf(3, 1), volume.writes)
        assertEquals("CANCELLED", row(cycle).status)
    }

    @Test fun journalWriteFailureCancelsWithoutRetryingHardware() = runBlocking {
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_alarm_result BEFORE UPDATE OF resultJson " +
            "ON alert_local_cycles WHEN OLD.resultJson != NEW.resultJson BEGIN SELECT RAISE(ABORT, 'test_rejection'); END")
        val outcome = executor().execute(settings)
        assertEquals(LocalAlarmExecutionStatus.JOURNAL_UNAVAILABLE, outcome.status)
        assertEquals(listOf(0), audio.steps)
        assertEquals(listOf(3, 1), volume.writes)
        assertTrue(result(outcome.cycle!!).steps.isEmpty())
    }

    @Test fun laterCycleRetainsConfirmedMaximumRatherThanRestartingQuietly() = runBlocking {
        val owner = executor()
        assertEquals(LocalAlarmExecutionStatus.FINISHED, owner.execute(settings).status)
        val due = db.alertLocalDao().state(signal.key.kind.name, signal.key.id)!!.nextDueElapsedMs!!
        environment = environment.copy(nowElapsedMs = due, nowWallMs = due + 9_000)
        audio.steps.clear(); audio.levels.clear(); volume.writes.clear()
        val second = owner.execute(settings)
        assertEquals(2L, second.cycle!!.ordinal)
        assertEquals(listOf(10, 10, 10, 10), audio.levels)
        assertEquals(listOf(10, 1), volume.writes)
        assertTrue(result(second.cycle!!).steps.all { it.reachedPercent == 100 })
    }

    @Test fun strongProfilesUseSelectedSlotDurationForEveryOrdinal() = runBlocking {
        signal = signal.copy(level = LocalAlarmLevel.CRITICAL_5)
        val first = executor().execute(settings.copy(criticalAlertAudio1DurationMs = 15_000))
        assertEquals(LocalAlarmExecutionStatus.FINISHED, first.status)
        assertEquals(listOf(1_000L, 4_750L, 8_500L, 12_250L), result(first.cycle!!).steps.map { it.atElapsedMs })
        environment = environment.copy(nowElapsedMs = 121_000, nowWallMs = 130_000)
        val second = executor().execute(settings.copy(criticalAlertAudio2DurationMs = 30_000))
        assertEquals(LocalAlarmExecutionStatus.FINISHED, second.status)
        assertEquals(listOf(121_000L, 128_500L, 136_000L, 143_500L), result(second.cycle!!).steps.map { it.atElapsedMs })
    }

    @Test fun slowVolumeReadCannotRaiseOrStartAMissedStep() = runBlocking {
        var reads = 0
        volume.onRead = { if (++reads == 2) environment = environment.copy(nowElapsedMs = 16_000) }
        val outcome = executor().execute(settings)
        assertNotEquals(LocalAlarmExecutionStatus.FINISHED, outcome.status)
        assertTrue(volume.writes.isEmpty())
        assertTrue(audio.steps.isEmpty())
        assertEquals("CANCELLED", row(outcome.cycle!!).status)
    }

    @Test fun reportsCommittedOwnerBeforeAudioAndOutcomeAfterCleanup() = runBlocking {
        var committed: LocalAlarmCycle? = null
        val reports = mutableListOf<LocalAlarmExecutionResult>()
        audio.onStart = { assertEquals(committed, it) }
        val outcome = executor(onClaimed = { committed = it }, onFinished = {
            assertTrue(audio.stopped.contains(it.cycle)); reports += it
        }).execute(settings)
        assertEquals(outcome.cycle, committed); assertEquals(listOf(outcome), reports)
        assertEquals("FINISHED", row(outcome.cycle!!).status)
    }

    @Test fun cancellationReportsCleanupThenPropagatesOriginalException() = runBlocking {
        val failure = CancellationException("source_lost")
        onWait = { throw failure }
        val reports = mutableListOf<LocalAlarmExecutionResult>()
        try { executor(onFinished = { reports += it }).execute(settings); fail() }
        catch (cancelled: CancellationException) {
            assertTrue(generateSequence<Throwable>(cancelled) { it.cause }.any { it === failure })
        }
        assertEquals(LocalAlarmExecutionStatus.CANCELLED, reports.single().status)
        assertEquals("CANCELLED", row(reports.single().cycle!!).status)
        assertEquals(1, volume.index)
    }

    @Test fun cancelledCleanupFailureIsReportedAsUnavailableNotSafeCancellation() = runBlocking {
        audio.stopFails = true
        onWait = { throw CancellationException("source_lost") }
        val reports = mutableListOf<LocalAlarmExecutionResult>()
        try { executor(onFinished = { reports += it }).execute(settings); fail() }
        catch (_: CancellationException) {}
        assertEquals(LocalAlarmExecutionStatus.CLEANUP_UNAVAILABLE, reports.single().status)
    }

    @Test fun deniedPreflightReportsNoOwnership() = runBlocking {
        capability = false
        val reports = mutableListOf<LocalAlarmExecutionResult>()
        val outcome = executor(onFinished = { reports += it }).execute(settings)
        assertEquals(listOf(outcome), reports); assertNull(outcome.cycle)
        assertTrue(audio.steps.isEmpty()); assertTrue(volume.writes.isEmpty())
    }

    @Test fun failedClaimObserverCannotStartHardwareAndStillFinishesClaim() = runBlocking {
        val reports = mutableListOf<LocalAlarmExecutionResult>()
        try { executor(onClaimed = { error("observer_unavailable") }, onFinished = { reports += it }).execute(settings); fail() }
        catch (_: IllegalStateException) {}
        assertTrue(audio.steps.isEmpty()); assertTrue(volume.writes.isEmpty())
        assertEquals("CANCELLED", row(reports.single().cycle!!).status)
    }

    @Test fun failedFinishObserverNeverRetainsExecutorLock() = runBlocking {
        var failOnce = true
        val runner = executor(onFinished = { if (failOnce) { failOnce = false; error("observer_unavailable") } })
        try { runner.execute(settings); fail() } catch (_: IllegalStateException) {}
        signal = signal.copy(generation = 2)
        assertEquals(LocalAlarmExecutionStatus.FINISHED, runner.execute(settings).status)
    }

    private fun testSettings() = AppSettings(
        nightscoutUrl = "", apiSecret = "", cloudBaseUrl = "", killSwitch = false,
        rootExperimentalEnabled = false, localBroadcastIngestEnabled = true,
        strictBroadcastSenderValidation = false, localNightscoutEnabled = true,
        localNightscoutPort = 17582, localCommandFallbackEnabled = true,
        localCommandPackage = "info.nightscout.androidaps", localCommandAction = "io.aaps.copilot.ACTION_COMMAND",
        insulinProfileId = "FIASP", baseTargetMmol = 5.5,
        postHypoThresholdMmol = 3.0, postHypoDeltaThresholdMmol5m = 0.2,
        postHypoTargetMmol = 4.4, postHypoDurationMinutes = 90, postHypoLookbackMinutes = 240,
        rulePostHypoEnabled = true, rulePatternEnabled = true, ruleSegmentEnabled = true,
        adaptiveControllerEnabled = true, rulePostHypoPriority = 50, rulePatternPriority = 40,
        ruleSegmentPriority = 30, adaptiveControllerPriority = 60, rulePostHypoCooldownMinutes = 30,
        rulePatternCooldownMinutes = 60, ruleSegmentCooldownMinutes = 60,
        adaptiveControllerRetargetMinutes = 5, adaptiveControllerSafetyProfile = "default",
        adaptiveControllerStaleMaxMinutes = 20, adaptiveControllerMaxActions6h = 24,
        adaptiveControllerMaxStepMmol = 0.6, patternMinSamplesPerWindow = 12,
        patternMinActiveDaysPerWindow = 3, patternLowRateTrigger = 0.25, patternHighRateTrigger = 0.25,
        analyticsLookbackDays = 30, maxActionsIn6Hours = 24, staleDataMaxMinutes = 20, exportFolderUri = null
    )
}
