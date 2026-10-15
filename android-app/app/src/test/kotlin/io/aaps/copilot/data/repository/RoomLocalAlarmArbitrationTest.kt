package io.aaps.copilot.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.aaps.copilot.data.local.CopilotDatabase
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
class RoomLocalAlarmArbitrationTest {
    private lateinit var db: CopilotDatabase
    private var environment = LocalAlarmEnvironment(true, true, 100_000, 200_000, 2)
    private val timing = LocalAlarmTiming()
    private fun request(id: String = "source-a", level: LocalAlarmLevel = LocalAlarmLevel.WARNING_30) =
        LocalAlarmRequest(LocalAlarmEvidence(LocalAlarmKey(LocalAlarmSourceKind.GLUCOSE, id), 1,
            level, 2, 90_000, 900_000, true), 1_000)
    private fun store() = RoomLocalAlarmStore(db) { environment }
    private suspend fun state(source: LocalAlarmRequest) = db.alertLocalDao().state(source.evidence.key.kind.name, source.evidence.key.id)
    private suspend fun row(cycle: LocalAlarmCycle) = db.alertLocalDao().cycle(cycle.key.kind.name, cycle.key.id, cycle.generation, cycle.ordinal)

    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), CopilotDatabase::class.java)
            .allowMainThreadQueries().build()
    }
    @After fun close() { db.close() }

    @Test fun repeatedPreviewSelectsOneSourceButCreatesNoClaimsOrStates() = runBlocking {
        val lower = request(); val higher = request("source-b", LocalAlarmLevel.LOW_NOW)
        repeat(3) {
            val decision = store().previewArbitration(listOf(lower, higher), null, timing)!!
            assertEquals(higher.evidence, decision.selected)
            assertNull(state(lower)); assertNull(state(higher))
            assertNull(db.alertLocalDao().cycle(higher.evidence.key.kind.name, higher.evidence.key.id, 1, 1))
        }
    }

    @Test fun previewPreemptionDoesNotCancelJournalOrAdvanceOrdinalDueAndProgress() = runBlocking {
        val source = request(); val owner = store()
        val cycle = owner.evaluate(source.evidence, timing)!!.startCycle!!
        val before = state(source); val claim = row(cycle)
        val higher = request("source-b", LocalAlarmLevel.LOW_NOW)
        val decision = owner.previewArbitration(listOf(source, higher), cycle, timing)!!
        assertEquals(cycle, decision.cancelCycle); assertEquals(higher.evidence, decision.selected)
        assertEquals(before, state(source)); assertEquals(claim, row(cycle)); assertNull(state(higher))
    }

    @Test fun previewUsesActualRoomOffAndDoesNotSlideItOrResumeJournal() = runBlocking {
        val source = request(); val owner = store()
        val cycle = owner.evaluate(source.evidence, timing)!!.startCycle!!
        val before = state(source)
        RoomEpisodeAlertReceiptStore(db).writeMuteUntil(220_000, 200_000)
        val muted = owner.previewArbitration(listOf(source), cycle, timing)!!
        assertEquals(cycle, muted.cancelCycle); assertNull(muted.selected)
        assertEquals(120_000L, muted.nextWakeElapsedMs); assertEquals(before, state(source))
        environment = environment.copy(nowWallMs = 220_000, mutedUntilWallMs = 800_000)
        assertEquals(cycle, owner.previewArbitration(listOf(source), cycle, timing)!!.retainedCycle)
        assertEquals(220_000L, db.alertEventDao().byEpisodeId(EpisodeAlertDeliveryStateMachine.MUTE_STATE_ID)!!.suppressionUntil)
        assertEquals(before, state(source))
    }

    @Test fun environmentCaptureFollowsSharedMuteLock() = runBlocking {
        val lock = EpisodeAlertOperationLocks.forIdentity(db); lock.lock()
        val started = CompletableDeferred<Unit>(); val source = request()
        val pending = async(Dispatchers.Default) { started.complete(Unit); store().previewArbitration(listOf(source), null, timing) }
        started.await(); environment = environment.copy(armed = false); lock.unlock()
        assertNull(pending.await()!!.selected); assertNull(state(source))
    }

    @Test fun corruptStoredStateFailsClosedForAllCandidates() = runBlocking {
        val source = request(); store().evaluate(source.evidence, timing)
        val before = state(source)!!
        db.openHelper.writableDatabase.execSQL("UPDATE alert_local_state SET stateJson=? WHERE sourceId=?",
            arrayOf("{}", source.evidence.key.id))
        assertNull(store().previewArbitration(listOf(source, request("fresh", LocalAlarmLevel.LOW_NOW)), null, timing))
        assertEquals(before.ordinal, state(source)!!.ordinal)
        assertNull(state(request("fresh")))
    }

    @Test fun omittedOwnerStateIsStillValidatedAndNeverAdopted() = runBlocking {
        val source = request(); val owner = store()
        val cycle = owner.evaluate(source.evidence, timing)!!.startCycle!!
        val before = row(cycle)
        val decision = owner.previewArbitration(emptyList(), cycle, timing)!!
        assertEquals(cycle, decision.cancelCycle); assertNull(decision.selected)
        assertEquals(before, row(cycle))
        db.openHelper.writableDatabase.execSQL("UPDATE alert_local_cycles SET resultJson=? WHERE sourceId=?",
            arrayOf("{}", source.evidence.key.id))
        assertNull(owner.previewArbitration(emptyList(), cycle, timing))
    }

    @Test fun abandonedClaimRequiresExplicitRecoveryAndPreviewCannotMarkItTerminal() = runBlocking {
        val source = request(); val cycle = store().evaluate(source.evidence, timing)!!.startCycle!!
        val decision = store().previewArbitration(listOf(source), null, timing)!!
        assertEquals(LocalAlarmArbitrationStatus.RECOVERY_REQUIRED, decision.status)
        assertEquals(listOf(cycle), decision.interruptedCycles)
        assertEquals("CLAIMED", row(cycle)!!.status); assertNull(decision.selected)
    }

    @Test fun ackExpiryPreviewNeverClearsPauseOrCreatesNextClaim() = runBlocking {
        val source = request(); val owner = store()
        val cycle = owner.evaluate(source.evidence, timing)!!.startCycle!!
        assertTrue(owner.acknowledge(source.evidence, timing, LocalAlarmAcknowledgement(cycle.key, 1, 1, cycle.level)))
        val before = state(source)
        assertEquals(400_000L, owner.previewArbitration(listOf(source), null, timing)!!.nextWakeElapsedMs)
        environment = environment.copy(nowElapsedMs = 400_000, nowWallMs = 500_000)
        assertEquals(source.evidence, owner.previewArbitration(listOf(source), null, timing)!!.selected)
        assertEquals(before, state(source)); assertNull(row(cycle.copy(ordinal = 2)))
    }

    @Test fun cancellationWhileWaitingForMuteLockPropagatesWithoutMutation() = runBlocking {
        val lock = EpisodeAlertOperationLocks.forIdentity(db); lock.lock()
        val started = CompletableDeferred<Unit>(); val source = request()
        val pending = async(Dispatchers.Default) { started.complete(Unit); store().previewArbitration(listOf(source), null, timing) }
        started.await(); pending.cancel(); pending.join(); lock.unlock()
        assertTrue(pending.isCancelled); assertNull(state(source))
    }

    @Test fun duplicateAndOverBoundRequestsAreRejectedBeforeClaimReads() = runBlocking {
        val source = request()
        val owner = store()
        assertEquals(LocalAlarmArbitrationStatus.INVALID_SNAPSHOT,
            owner.previewArbitration(listOf(source, source), null, timing)!!.status)
        val requests = (1..64).map { request("source-$it") }
        val cycle = owner.evaluate(source.evidence, timing)!!.startCycle!!
        assertEquals(LocalAlarmArbitrationStatus.INVALID_SNAPSHOT,
            owner.previewArbitration(requests, cycle, timing)!!.status)
        assertEquals(1L, state(source)!!.ordinal)
    }

    @Test fun requestMutationDuringIndexedReadCannotSwitchToAnUncheckedSource() = runBlocking {
        db.close()
        val lower = request(); val higher = request("source-b", LocalAlarmLevel.LOW_NOW)
        val requests = mutableListOf(lower)
        var changeDuringRead = false
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), CopilotDatabase::class.java)
            .allowMainThreadQueries()
            .setQueryCallback({ sql, _ ->
                if (changeDuringRead && sql.contains("SELECT * FROM alert_local_state")) {
                    changeDuringRead = false
                    requests[0] = higher
                }
            }, java.util.concurrent.Executor { it.run() }).build()
        store().evaluate(higher.evidence, timing)
        db.openHelper.writableDatabase.execSQL("UPDATE alert_local_state SET stateJson=? WHERE sourceId=?",
            arrayOf("{}", higher.evidence.key.id))
        changeDuringRead = true
        val decision = store().previewArbitration(requests, null, timing)!!
        assertEquals(higher, requests.single())
        assertEquals(lower.evidence, decision.selected)
    }
}
