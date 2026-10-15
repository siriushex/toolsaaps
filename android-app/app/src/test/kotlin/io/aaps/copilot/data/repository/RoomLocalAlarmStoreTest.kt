package io.aaps.copilot.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.aaps.copilot.data.local.CopilotDatabase
import io.aaps.copilot.data.local.entity.AlertLocalCycleEntity
import io.aaps.copilot.domain.alerts.*
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
class RoomLocalAlarmStoreTest {
    private lateinit var db: CopilotDatabase
    private var environment = LocalAlarmEnvironment(true, true, 1_000, 10_000, 2)
    private val key = LocalAlarmKey(LocalAlarmSourceKind.GLUCOSE, "episode-a")
    private val timing = LocalAlarmTiming()
    private fun evidence(level: LocalAlarmLevel = LocalAlarmLevel.WARNING_30, generation: Long = 1) =
        LocalAlarmEvidence(key, generation, level, environment.bootCount, environment.nowElapsedMs, environment.nowElapsedMs + 900_000, true)
    private fun store() = RoomLocalAlarmStore(db) { environment }
    private suspend fun state() = db.alertLocalDao().state(key.kind.name, key.id)!!
    private suspend fun row(cycle: LocalAlarmCycle) = db.alertLocalDao().cycle(key.kind.name, key.id, cycle.generation, cycle.ordinal)!!
    private fun step(index: Int = 0, percent: Int? = 25) = LocalAlarmStepResult(index, environment.nowElapsedMs,
        LocalAlarmNotificationResult.POSTED, LocalAlarmAudioResult.STARTED, LocalAlarmVibrationResult.REQUESTED, percent)

    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), CopilotDatabase::class.java)
            .allowMainThreadQueries().build()
    }
    @After fun close() { db.close() }

    @Test fun concurrentStoresCommitOneClaimAndDuplicateDoesNotSlideDue() = runBlocking {
        val first = store(); val second = store(); val signal = evidence()
        val results = coroutineScope { listOf(first, second).map { async(Dispatchers.Default) { it.evaluate(signal, timing)!! } }.awaitAll() }
        assertEquals(1, results.count { it.admission == LocalAlarmAdmission.START })
        assertEquals(1, results.count { it.admission == LocalAlarmAdmission.ACTIVE })
        assertEquals(1L, state().ordinal)
        val due = state().nextDueElapsedMs
        environment = environment.copy(nowElapsedMs = 20_000)
        assertEquals(LocalAlarmAdmission.ACTIVE, second.evaluate(evidence(), timing)!!.admission)
        assertEquals(due, state().nextDueElapsedMs)
    }

    @Test fun roomMuteOverridesBothStaleCallerHintsAndExpiresExactly() = runBlocking {
        RoomEpisodeAlertReceiptStore(db).writeMuteUntil(20_000, 10_000)
        val owner = store()
        assertEquals(LocalAlarmAdmission.MUTED, owner.evaluate(evidence(), timing)!!.admission)
        assertEquals(0L, state().ordinal)
        environment = environment.copy(nowWallMs = 20_000, mutedUntilWallMs = 90_000)
        assertEquals(LocalAlarmAdmission.START, owner.evaluate(evidence(), timing)!!.admission)
    }

    @Test fun environmentIsReadOnlyAfterSharedOperationLock() = runBlocking {
        val lock = EpisodeAlertOperationLocks.forIdentity(db)
        lock.lock()
        val entered = CompletableDeferred<Unit>()
        val owner = store(); val signal = evidence()
        val pending = async(Dispatchers.Default) { entered.complete(Unit); owner.evaluate(signal, timing) }
        entered.await()
        environment = environment.copy(enabled = false)
        lock.unlock()
        assertEquals(LocalAlarmAdmission.DISABLED, pending.await()!!.admission)
        assertEquals(0L, state().ordinal)
    }

    @Test fun finishPreservesRepeatAndOldResultCannotMutateNewClaim() = runBlocking {
        val owner = store(); val first = owner.evaluate(evidence(), timing)!!.startCycle!!
        assertTrue(owner.finish(first))
        assertEquals("FINISHED", row(first).status)
        environment = environment.copy(nowElapsedMs = 100_000)
        assertEquals(LocalAlarmAdmission.WAITING, owner.evaluate(evidence(), timing)!!.admission)
        environment = environment.copy(nowElapsedMs = 301_000)
        val second = owner.evaluate(evidence(), timing)!!.startCycle!!
        assertEquals(2L, second.ordinal)
        assertFalse(owner.recordStep(evidence(), first, step(), timing))
        assertFalse(owner.finish(first))
        assertEquals("CLAIMED", row(second).status)
    }

    @Test fun stepsAreIdempotentBoundedAndOnlyConfirmedProgressPersists() = runBlocking {
        val owner = store(); val cycle = owner.evaluate(evidence(), timing)!!.startCycle!!
        assertTrue(owner.recordStep(evidence(), cycle, step(percent = null), timing))
        assertEquals(0, LocalAlarmPersistenceCodec.decodeState(state().stateJson)!!.reachedPercent)
        assertTrue(owner.recordStep(evidence(), cycle, step(percent = null), timing))
        assertFalse(owner.recordStep(evidence(), cycle, step(), timing))
        assertFalse(owner.recordStep(evidence(), cycle, step(1, 50), timing))
        environment = environment.copy(nowElapsedMs = 16_000)
        assertTrue(owner.recordStep(evidence(), cycle, step(1, 50), timing))
        assertEquals(50, LocalAlarmPersistenceCodec.decodeState(state().stateJson)!!.reachedPercent)
        assertEquals(2, LocalAlarmPersistenceCodec.decodeResult(row(cycle).resultJson)!!.steps.size)
        environment = environment.copy(nowElapsedMs = cycle.deadlineElapsedMs)
        assertFalse(owner.recordStep(evidence(), cycle, step(2, 75), timing))
    }

    @Test fun rejectedAuthorityMuteAndChangedLevelCannotWriteResults() = runBlocking {
        val owner = store(); val cycle = owner.evaluate(evidence(), timing)!!.startCycle!!
        assertFalse(owner.recordStep(evidence().copy(authorized = false), cycle, step(), timing))
        assertFalse(owner.recordStep(evidence(LocalAlarmLevel.CRITICAL_5), cycle, step(), timing))
        RoomEpisodeAlertReceiptStore(db).writeMuteUntil(50_000, 10_000)
        assertFalse(owner.recordStep(evidence(), cycle, step(), timing))
        assertEquals(0, LocalAlarmPersistenceCodec.decodeResult(row(cycle).resultJson)!!.steps.size)
        assertEquals(LocalAlarmAdmission.MUTED, owner.evaluate(evidence(), timing)!!.admission)
        assertEquals("CANCELLED", row(cycle).status)
    }

    @Test fun identicalEarlierStepRemainsIdempotentAfterLaterConfirmedProgress() = runBlocking {
        val owner = store(); val cycle = owner.evaluate(evidence(), timing)!!.startCycle!!
        val first = step()
        assertTrue(owner.recordStep(evidence(), cycle, first, timing))
        environment = environment.copy(nowElapsedMs = 16_000)
        assertTrue(owner.recordStep(evidence(), cycle, step(1, 50), timing))
        val before = state()
        assertTrue(owner.recordStep(evidence(), cycle, first, timing))
        assertEquals(before, state())
        assertEquals(2, LocalAlarmPersistenceCodec.decodeResult(row(cycle).resultJson)!!.steps.size)
    }

    @Test fun futureJournalProgressIsNotCurrentConfirmation() = runBlocking {
        val cycle = store().evaluate(evidence(), timing)!!.startCycle!!
        assertTrue(store().recordStep(evidence(), cycle, step(), timing))
        val row = row(cycle)
        val result = LocalAlarmPersistenceCodec.decodeResult(row.resultJson)!!
        val future = result.copy(steps = listOf(result.steps.single().copy(atElapsedMs = 20_000)))
        db.alertLocalDao().updateCycle(row.copy(resultJson = LocalAlarmPersistenceCodec.encodeResult(future)!!))
        assertNull(store().evaluate(evidence(), timing))
        assertFalse(store().finish(cycle))
        assertEquals("CLAIMED", row(cycle).status)
    }

    @Test fun identicalStrongStepDoesNotDependOnLaterTimingSettings() = runBlocking {
        val owner = store(); val signal = evidence(LocalAlarmLevel.CRITICAL_5)
        val cycle = owner.evaluate(signal, timing)!!.startCycle!!
        environment = environment.copy(nowElapsedMs = 6_000)
        val confirmed = step(1, 50)
        assertTrue(owner.recordStep(evidence(cycle.level), cycle, confirmed, timing))
        assertTrue(owner.recordStep(evidence(cycle.level), cycle, confirmed, timing.copy(strongClipMs = 30_000)))
        assertEquals(1, LocalAlarmPersistenceCodec.decodeResult(row(cycle).resultJson)!!.steps.size)
    }

    @Test fun acknowledgementIsExactAndHigherPriorityCancelsPause() = runBlocking {
        val owner = store(); val cycle = owner.evaluate(evidence(), timing)!!.startCycle!!
        val ack = LocalAlarmAcknowledgement(key, 1, cycle.ordinal, cycle.level)
        assertFalse(owner.acknowledge(evidence(), timing, ack.copy(ordinal = 9)))
        assertTrue(owner.acknowledge(evidence(), timing, ack))
        assertFalse(owner.acknowledge(evidence(), timing, ack))
        assertEquals("CANCELLED", row(cycle).status)
        assertEquals(LocalAlarmAdmission.ACKNOWLEDGED, owner.evaluate(evidence(), timing)!!.admission)
        val high = owner.evaluate(evidence(LocalAlarmLevel.CRITICAL_5), timing)!!
        assertEquals(LocalAlarmAdmission.START, high.admission)
        assertEquals(2L, high.startCycle!!.ordinal)
    }

    @Test fun recoverMarksInterruptedAttemptUncertainWithoutReplayOrResettingDue() = runBlocking {
        val owner = store(); val cycle = owner.evaluate(evidence(), timing)!!.startCycle!!
        assertTrue(owner.recordStep(evidence(), cycle, step(), timing))
        environment = environment.copy(nowElapsedMs = 20_000)
        val result = store().recover(evidence(), timing)!!
        assertEquals(LocalAlarmAdmission.WAITING, result.admission)
        assertEquals("UNCERTAIN", row(cycle).status)
        assertEquals(1L, state().ordinal)
        assertEquals(301_000L, state().nextDueElapsedMs)
        assertFalse(owner.recordStep(evidence(), cycle, step(), timing))
        environment = environment.copy(nowElapsedMs = 301_000)
        assertEquals(2L, store().recover(evidence(), timing)!!.startCycle!!.ordinal)
    }

    @Test fun rebootRecoveryUsesFreshAuthorityAndNeverResetsOrdinal() = runBlocking {
        val cycle = store().evaluate(evidence(), timing)!!.startCycle!!
        environment = environment.copy(bootCount = 3, nowElapsedMs = 500)
        val result = store().recover(evidence(), timing)!!
        assertEquals(2L, result.startCycle!!.ordinal)
        assertEquals("UNCERTAIN", row(cycle).status)
        assertEquals(0, result.state!!.reachedPercent)
    }

    @Test fun corruptPayloadOrMirrorsNeverInventNewOrdinal() = runBlocking {
        val cycle = store().evaluate(evidence(), timing)!!.startCycle!!
        val sql = db.openHelper.writableDatabase
        sql.execSQL("UPDATE alert_local_state SET ordinal=99")
        assertNull(store().evaluate(evidence(), timing))
        assertEquals(99L, state().ordinal)
        sql.execSQL("UPDATE alert_local_state SET ordinal=1, stateJson='{}'")
        assertNull(store().recover(evidence(), timing))
        assertEquals("CLAIMED", row(cycle).status)
    }

    @Test fun corruptClaimMetadataOrResultsFailClosed() = runBlocking {
        val cycle = store().evaluate(evidence(), timing)!!.startCycle!!
        val sql = db.openHelper.writableDatabase
        sql.execSQL("UPDATE alert_local_cycles SET status='FINISHED'")
        assertNull(store().evaluate(evidence(), timing))
        sql.execSQL("UPDATE alert_local_cycles SET status='CLAIMED', resultJson='{}'")
        assertNull(store().recover(evidence(), timing))
        sql.execSQL("UPDATE alert_local_cycles SET resultJson='{}', level='LOW_NOW'")
        assertFalse(store().finish(cycle))
        assertEquals(1L, state().ordinal)
    }

    @Test fun mutedAcknowledgementCannotCreatePause() = runBlocking {
        val owner = store(); val cycle = owner.evaluate(evidence(), timing)!!.startCycle!!
        RoomEpisodeAlertReceiptStore(db).writeMuteUntil(50_000, 10_000)
        assertFalse(owner.acknowledge(evidence(), timing, LocalAlarmAcknowledgement(key, 1, 1, cycle.level)))
        assertNull(state().pauseUntilWallMs)
    }

    @Test fun cycleCollisionAndStateWriteFailureRollbackEntireClaim() = runBlocking {
        val signal = evidence(); val cycle = LocalAlarmPolicy.evaluate(signal, null, environment, timing).startCycle!!
        db.alertLocalDao().insertCycle(AlertLocalCycleEntity(key.kind.name, key.id, 1, 1, 2, cycle.level.name,
            cycle.startedElapsedMs, cycle.deadlineElapsedMs, 10_000, null, "CLAIMED", LocalAlarmPersistenceCodec.encodeResult(LocalAlarmCycleResult())!!))
        assertNull(store().evaluate(signal, timing))
        assertNull(db.alertLocalDao().state(key.kind.name, key.id))
        db.openHelper.writableDatabase.execSQL("DELETE FROM alert_local_cycles")
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_local_state BEFORE INSERT ON alert_local_state BEGIN SELECT RAISE(ABORT,'synthetic failure'); END")
        assertNull(store().evaluate(signal, timing))
        assertNull(db.alertLocalDao().cycle(key.kind.name, key.id, 1, 1))
    }

    @Test fun revisionOverflowCannotCommitAnotherClaim() = runBlocking {
        val cycle = store().evaluate(evidence(), timing)!!.startCycle!!
        assertTrue(store().finish(cycle))
        db.openHelper.writableDatabase.execSQL("UPDATE alert_local_state SET revision=9223372036854775807")
        environment = environment.copy(nowElapsedMs = 301_000)
        assertNull(store().evaluate(evidence(), timing))
        assertEquals(1L, state().ordinal)
        assertNull(db.alertLocalDao().cycle(key.kind.name, key.id, 1, 2))
    }

    @Test fun retentionIsBoundedDoesNotDeleteClaimsOrOrdinalState() = runBlocking {
        val cycle = store().evaluate(evidence(), timing)!!.startCycle!!
        for (index in 2L..106L) db.alertLocalDao().insertCycle(AlertLocalCycleEntity(key.kind.name, key.id, 1, index, 2,
            cycle.level.name, 1_000, 56_000, 1, 2, "FINISHED", LocalAlarmPersistenceCodec.encodeResult(LocalAlarmCycleResult())!!))
        assertEquals(100, store().prune(31L * 86_400_000, Int.MAX_VALUE))
        assertEquals(5, store().prune(31L * 86_400_000))
        assertEquals("CLAIMED", row(cycle).status)
        assertEquals(1L, state().ordinal)
    }

    @Test fun schedulingQueriesUseBoundedLimitsAndCurrentBoot() = runBlocking {
        store().evaluate(evidence(), timing)
        val original = state()
        for (index in 1..70) db.alertLocalDao().insertState(original.copy(sourceId = "source-$index"))
        assertEquals(1, db.alertLocalDao().dueRepeats(2, Long.MAX_VALUE, -1).size)
        assertEquals(64, db.alertLocalDao().dueRepeats(2, Long.MAX_VALUE, Int.MAX_VALUE).size)
        assertEquals(0, db.alertLocalDao().dueRepeats(3, Long.MAX_VALUE).size)
        assertEquals(1, db.alertLocalDao().expiredCycles(2, Long.MAX_VALUE, -1).size)
        assertEquals(64, db.alertLocalDao().expiredCycles(2, Long.MAX_VALUE, Int.MAX_VALUE).size)
        db.openHelper.writableDatabase.execSQL("UPDATE alert_local_state SET pauseUntilWallMs=20000")
        assertEquals(1, db.alertLocalDao().expiredPauses(2, Long.MAX_VALUE, -1).size)
        assertEquals(64, db.alertLocalDao().expiredPauses(2, Long.MAX_VALUE, Int.MAX_VALUE).size)
        for ((column, index) in listOf("nextDueElapsedMs" to "index_alert_local_state_bootCount_nextDueElapsedMs",
            "pauseUntilWallMs" to "index_alert_local_state_bootCount_pauseUntilWallMs",
            "cycleDeadlineElapsedMs" to "index_alert_local_state_bootCount_cycleDeadlineElapsedMs")) {
            db.openHelper.writableDatabase.query("EXPLAIN QUERY PLAN SELECT * FROM alert_local_state WHERE bootCount=2 AND $column <= 900000 ORDER BY $column LIMIT 64").use {
                assertTrue(it.moveToFirst()); assertTrue(it.getString(3).contains(index))
            }
        }
    }

    @Test fun cancellationIsPropagatedNotReportedAsStorageFailure() = runBlocking {
        val owner = RoomLocalAlarmStore(db) { throw CancellationException("cancelled") }
        try { owner.evaluate(evidence(), timing); fail("cancellation swallowed") } catch (_: CancellationException) { }
    }
}
