package io.aaps.copilot.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.aaps.copilot.config.AppSettings
import io.aaps.copilot.data.local.CopilotDatabase
import io.aaps.copilot.domain.alerts.*
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class LocalAlarmCoordinatorTest {
    private lateinit var db: CopilotDatabase
    private val clock = Clock()
    private val volume = Volume()
    private val audio = Audio()
    @Volatile private var missing = false
    @Volatile private var template = LocalAlarmCoordinatorContext(settings(), listOf(request()),
        LocalAlarmEnvironment(true, true, 1_000, 10_000, 2), true, true)
    private fun live(): LocalAlarmCoordinatorContext? = if (missing) null else template.let {
        val now = clock.now.get()
        it.copy(environment = it.environment.copy(nowElapsedMs = now, nowWallMs = 9_000 + now))
    }
    private fun store() = RoomLocalAlarmStore(db) {
        template.environment.copy(nowElapsedMs = clock.now.get(), nowWallMs = 9_000 + clock.now.get())
    }
    private fun coordinator() = LocalAlarmCoordinator(store(), volume, audio, ::live,
        dispatcher = Dispatchers.Unconfined, waitMs = clock::sleep)
    private fun request(id: String = "source-a", level: LocalAlarmLevel = LocalAlarmLevel.WARNING_30, pending: Long = 1_000) =
        LocalAlarmRequest(LocalAlarmEvidence(LocalAlarmKey(LocalAlarmSourceKind.GLUCOSE, id), 1, level,
            2, 1_000, 900_000, true), pending)
    private suspend fun until(condition: () -> Boolean) = withTimeout(5_000) { while (!condition()) delay(5) }
    private suspend fun running(owner: LocalAlarmCoordinator, block: suspend CoroutineScope.(Deferred<LocalAlarmCoordinatorExit>) -> Unit) =
        coroutineScope { val job = async { owner.run() }; try { block(job) } finally { job.cancelAndJoin() } }
    private suspend fun row(cycle: LocalAlarmCycle) = db.alertLocalDao().cycle(cycle.key.kind.name, cycle.key.id, cycle.generation, cycle.ordinal)!!
    private suspend fun terminal(cycle: LocalAlarmCycle, status: String) = withTimeout(5_000) {
        while (row(cycle).status != status) delay(5)
    }

    private class Clock {
        val now = AtomicLong(1_000)
        var beforeRegister: suspend (Long) -> Unit = {}
        private val waits = mutableListOf<Pair<Long, CompletableDeferred<Unit>>>()
        suspend fun sleep(ms: Long) {
            beforeRegister(ms)
            val item = now.get() + ms to CompletableDeferred<Unit>()
            synchronized(waits) { waits += item }
            try { item.second.await() } finally { synchronized(waits) { waits.remove(item) } }
        }
        fun advance(ms: Long) {
            val at = now.addAndGet(ms)
            val due = synchronized(waits) { waits.filter { it.first <= at }.toList() }
            due.forEach { it.second.complete(Unit) }
        }
        fun count() = synchronized(waits) { waits.size }
        fun hasDeadline(at: Long) = synchronized(waits) { waits.any { it.first == at } }
    }
    private class Volume : AlarmVolumePort {
        @Volatile var index = 1
        var onRead: () -> Unit = {}
        val writes = CopyOnWriteArrayList<Int>()
        override fun read(): AlarmVolumeSnapshot { onRead(); return AlarmVolumeSnapshot(index, 10, false) }
        override fun set(index: Int) { this.index = index; writes += index }
    }
    private inner class Audio : LocalAlarmAudioPort {
        val starts = CopyOnWriteArrayList<Pair<LocalAlarmCycle, Int>>()
        val stops = CopyOnWriteArrayList<LocalAlarmCycle>()
        @Volatile var playing: LocalAlarmCycle? = null
        @Volatile var stopFails = false
        @Volatile var lastGuard: ((LocalAlarmCycle) -> Boolean)? = null
        override suspend fun playLocalAlarm(cycle: LocalAlarmCycle, stepIndex: Int, settings: AppSettings,
            admitted: (LocalAlarmCycle) -> Boolean): GlucoseAlertAudioPlaybackResult {
            assertTrue(admitted(cycle)); assertTrue(playing == null || playing == cycle)
            playing = cycle; lastGuard = admitted; starts += cycle to stepIndex
            return GlucoseAlertAudioPlaybackResult(played = true, durationMs = if (cycle.level.strong) 20_000 else 2_000)
        }
        override fun stopLocalAlarm(cycle: LocalAlarmCycle) {
            stops += cycle
            if (stopFails) error("stop_unconfirmed")
            if (playing == cycle) playing = null
        }
    }
    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), CopilotDatabase::class.java)
            .allowMainThreadQueries().build()
    }
    @After fun close() { db.close() }

    @Test fun constructionAndSignalsNeverAutomaticallyRunOrClaim() = runBlocking {
        coordinator().signal(); delay(20)
        assertTrue(audio.starts.isEmpty()); assertTrue(volume.writes.isEmpty())
        assertNull(db.alertLocalDao().state("GLUCOSE", "source-a"))
    }

    @Test fun missingOrUnarmedContextCannotStartAndIdleHasNoTimer() = runBlocking {
        missing = true
        val owner = coordinator()
        running(owner) {
            delay(20); assertTrue(audio.starts.isEmpty()); assertEquals(0, clock.count())
            missing = false; template = template.copy(environment = template.environment.copy(armed = false))
            owner.signal(); delay(20); assertTrue(audio.starts.isEmpty())
        }
        assertEquals(LocalAlarmCoordinatorExit.STOPPED, owner.lastExit)
    }

    @Test fun higherEligibleSourcePreemptsOnlyAfterOldAudioAndVolumeCleanup() = runBlocking {
        val owner = coordinator()
        running(owner) {
            until { audio.starts.size == 1 }; val first = audio.starts.single().first
            template = template.copy(requests = template.requests + request("source-b", LocalAlarmLevel.LOW_NOW))
            owner.signal(); until { audio.starts.size == 2 }
            val next = audio.starts.last().first
            assertEquals(LocalAlarmLevel.LOW_NOW, next.level); assertTrue(audio.stops.contains(first))
            assertEquals("CANCELLED", row(first).status); assertEquals(7, volume.index)
            assertEquals(next, audio.playing)
        }
    }

    @Test fun equalPriorityKeepsOwnerUntilCompletionThenOldestPendingWins() = runBlocking {
        val owner = coordinator()
        running(owner) {
            until { audio.starts.size == 1 }; val first = audio.starts.single().first
            template = template.copy(requests = template.requests + request("source-b", pending = 0))
            owner.signal(); delay(20); assertEquals(first, audio.playing)
            repeat(3) { index ->
                until { clock.hasDeadline(16_000L + index * 15_000L) }
                clock.advance(15_000); until { audio.starts.size >= index + 2 }
            }
            until { clock.hasDeadline(48_000) }
            clock.advance(2_000); until { audio.starts.any { it.first.key.id == "source-b" } }
            assertEquals("FINISHED", row(first).status); assertTrue(audio.stops.contains(first))
        }
    }

    @Test fun duplicateUpdatesNeverRestartCycleSlideDueOrAccumulateTimers() = runBlocking {
        val registering = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        clock.beforeRegister = { ms -> if (ms == 15_000L && clock.now.get() == 1_000L) {
            registering.complete(Unit); release.await()
        } }
        val owner = coordinator()
        running(owner) {
            until { audio.starts.size == 1 }; val cycle = audio.starts.single().first
            withTimeout(5_000) { registering.await() }
            // Audio start precedes asynchronous Room work and step-wait registration.
            release.complete(Unit)
            until { clock.count() == 2 }
            assertTrue("Step wait must be registered before advancing its clock", clock.hasDeadline(16_000))
            clock.advance(10_000)
            template = template.copy(requests = template.requests.map { it.copy(evidence = it.evidence.copy(observedElapsedMs = clock.now.get())) })
            repeat(20) { owner.signal() }
            until { clock.count() == 2 }
            assertEquals(1, audio.starts.size); assertEquals(301_000L, db.alertLocalDao().state("GLUCOSE", "source-a")!!.nextDueElapsedMs)
            assertTrue(clock.count() <= 2)
            clock.advance(5_000); until { audio.starts.size == 2 }
            assertTrue(audio.starts.all { it.first == cycle })
        }
    }

    @Test fun sourceLossRevokesGuardSynchronouslyAndCancelsExactOwner() = runBlocking {
        val owner = coordinator()
        running(owner) {
            until { audio.starts.size == 1 }; val cycle = audio.starts.single().first
            template = template.copy(requests = emptyList()); owner.signal()
            assertFalse(audio.lastGuard!!(cycle)); until { audio.stops.contains(cycle) }
            until { clock.count() == 0 }
            terminal(cycle, "CANCELLED")
            assertEquals("CANCELLED", row(cycle).status); assertEquals(1, audio.starts.size)
        }
    }

    @Test fun actualOffAndPublishedMuteCancelWithoutAudioOrClaimBacklog() = runBlocking {
        val owner = coordinator()
        running(owner) {
            until { audio.starts.size == 1 }; val cycle = audio.starts.single().first
            RoomEpisodeAlertReceiptStore(db).writeMuteUntil(70_000, 10_000)
            template = template.copy(environment = template.environment.copy(mutedUntilWallMs = 70_000))
            owner.signal(); assertFalse(audio.lastGuard!!(cycle)); until { audio.stops.contains(cycle) }
            terminal(cycle, "CANCELLED")
            clock.advance(60_000); delay(30)
            assertEquals(1, audio.starts.size); assertEquals("CANCELLED", row(cycle).status)
            assertEquals(70_000L, db.alertEventDao().byEpisodeId(EpisodeAlertDeliveryStateMachine.MUTE_STATE_ID)!!.suppressionUntil)
        }
    }

    @Test fun capabilityLossStopsAndCannotInventRuntimeArming() = runBlocking {
        val owner = coordinator()
        running(owner) {
            until { audio.starts.size == 1 }; val cycle = audio.starts.single().first
            template = template.copy(audioAvailable = false); owner.signal()
            assertFalse(audio.lastGuard!!(cycle)); until { audio.stops.contains(cycle) }
            clock.advance(400_000); delay(30); assertEquals(1, audio.starts.size)
        }
    }

    @Test fun nearestSourceExpiryStopsWithoutAnyNewSignalOrPolling() = runBlocking {
        template = template.copy(requests = listOf(request().copy(evidence = request().evidence.copy(validUntilElapsedMs = 5_000))))
        val owner = coordinator()
        running(owner) {
            until { audio.starts.size == 1 }; val cycle = audio.starts.single().first
            until { clock.count() == 2 }; clock.advance(4_000)
            until { audio.stops.contains(cycle) }; until { clock.count() == 0 }
            assertEquals(1, audio.starts.size)
        }
    }

    @Test fun sourceLossInsideHardwareReadCannotRaiseVolumeOrStartAudio() = runBlocking {
        val owner = coordinator(); var first = true
        volume.onRead = { if (first) { first = false; missing = true; owner.signal() } }
        running(owner) {
            until { !first }; until { clock.count() == 0 }; delay(30)
            assertTrue(volume.writes.isEmpty()); assertTrue(audio.starts.isEmpty())
        }
    }

    @Test fun abandonedClaimBecomesUncertainWithoutImmediateReplayAndRetainsDue() = runBlocking {
        val first = store().evaluate(request().evidence, LocalAlarmTiming())!!.startCycle!!
        val owner = coordinator()
        running(owner) {
            until { clock.count() == 1 }
            assertEquals("UNCERTAIN", row(first).status); assertTrue(audio.starts.isEmpty())
            clock.advance(300_000); until { audio.starts.size == 1 }
            assertEquals(2L, audio.starts.single().first.ordinal); assertEquals(0, audio.starts.single().second)
        }
    }

    @Test fun failedCleanupBlocksReplacementAndDoesNotClaimSuccess() = runBlocking {
        val owner = coordinator()
        running(owner) { job ->
            until { audio.starts.size == 1 }; audio.stopFails = true
            template = template.copy(requests = template.requests + request("source-b", LocalAlarmLevel.LOW_NOW))
            owner.signal()
            assertEquals(LocalAlarmCoordinatorExit.UNAVAILABLE, withTimeout(5_000) { job.await() })
            assertEquals(1, audio.starts.size); assertNull(db.alertLocalDao().state("GLUCOSE", "source-b"))
            assertEquals(LocalAlarmCoordinatorExit.UNAVAILABLE, owner.lastExit)
            assertEquals(LocalAlarmCoordinatorExit.UNAVAILABLE, coordinator().run())
            assertEquals(1, audio.starts.size)
        }
    }

    @Test fun corruptJournalFailsClosedInsteadOfResettingOrdinal() = runBlocking {
        store().evaluate(request().evidence, LocalAlarmTiming())
        db.openHelper.writableDatabase.execSQL("UPDATE alert_local_state SET stateJson=? WHERE sourceId=?", arrayOf("{}", "source-a"))
        val owner = coordinator()
        assertEquals(LocalAlarmCoordinatorExit.UNAVAILABLE, withTimeout(5_000) { owner.run() })
        assertTrue(audio.starts.isEmpty()); assertEquals(1L, db.alertLocalDao().state("GLUCOSE", "source-a")!!.ordinal)
    }

    @Test fun twoRuntimeInstancesCannotClaimDifferentSourcesOnSameDatabase() = runBlocking {
        val first = coordinator(); val second = coordinator()
        running(first) {
            until { audio.starts.size == 1 }
            assertEquals(LocalAlarmCoordinatorExit.BUSY, withTimeout(2_000) { second.run() })
            assertEquals(1, audio.starts.size)
        }
    }

    @Test fun corruptionWhilePlayingStopsBeforeUnavailableRuntimeReturns() = runBlocking {
        val owner = coordinator()
        running(owner) { job ->
            until { audio.starts.size == 1 }; val cycle = audio.starts.single().first
            db.openHelper.writableDatabase.execSQL("UPDATE alert_local_state SET stateJson=? WHERE sourceId=?", arrayOf("{}", "source-a"))
            owner.signal()
            assertEquals(LocalAlarmCoordinatorExit.UNAVAILABLE, withTimeout(5_000) { job.await() })
            assertTrue(audio.stops.contains(cycle)); assertNull(audio.playing)
            assertEquals(1, volume.index); assertEquals(0, clock.count())
            assertEquals(LocalAlarmCoordinatorExit.UNAVAILABLE, coordinator().run())
        }
    }

    @Test fun revocationDoesNotWaitForBlockedGlobalMuteOrJournalLock() = runBlocking {
        val owner = coordinator(); val lock = EpisodeAlertOperationLocks.forIdentity(db)
        running(owner) {
            until { audio.starts.size == 1 }; val cycle = audio.starts.single().first
            lock.lock()
            try {
                missing = true; owner.signal()
                assertFalse(audio.lastGuard!!(cycle)); until { audio.stops.contains(cycle) }
                assertNull(audio.playing)
                assertEquals(LocalAlarmCoordinatorExit.BUSY, coordinator().run())
            } finally { lock.unlock() }
        }
    }

    @Test fun changedSnapshotDuringPreviewCannotLaunchItsOldSelection() = runBlocking {
        db.close(); var switch = true
        lateinit var owner: LocalAlarmCoordinator
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), CopilotDatabase::class.java)
            .allowMainThreadQueries().setQueryCallback({ sql, _ ->
                if (switch && sql.contains("SELECT * FROM alert_local_state")) {
                    switch = false
                    template = template.copy(requests = template.requests + request("source-b", LocalAlarmLevel.LOW_NOW))
                    owner.signal()
                }
            }, java.util.concurrent.Executor { it.run() }).build()
        owner = coordinator()
        running(owner) {
            until { audio.starts.size == 1 }
            assertEquals(LocalAlarmLevel.LOW_NOW, audio.starts.single().first.level)
            assertNull(db.alertLocalDao().state("GLUCOSE", "source-a"))
        }
    }

    private fun settings() = AppSettings(
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
