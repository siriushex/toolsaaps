package io.aaps.copilot.data.repository

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.test.core.app.ApplicationProvider
import io.aaps.copilot.data.local.CopilotDatabase
import io.aaps.copilot.domain.alerts.*
import kotlinx.coroutines.*
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executor
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
class LocalAlarmSharedCooldownTest {
    private lateinit var db: CopilotDatabase
    private val sharedReads = CopyOnWriteArrayList<String>()
    private var environment = LocalAlarmEnvironment(true, true, 1_000, 10_000, 2)
    private val timing = LocalAlarmTiming()
    private fun store() = RoomLocalAlarmStore(db) { environment }
    private fun signal(id: String = "source-a", level: LocalAlarmLevel = LocalAlarmLevel.WARNING_30) =
        LocalAlarmEvidence(LocalAlarmKey(LocalAlarmSourceKind.GLUCOSE, id), 1, level,
            environment.bootCount, environment.nowElapsedMs, environment.nowElapsedMs + 9_000_000, true)
    private fun advance(time: Long) { environment = environment.copy(nowElapsedMs = time, nowWallMs = time + 9_000) }
    private suspend fun delivered(audio: LocalAlarmAudioResult = LocalAlarmAudioResult.STARTED): LocalAlarmCycle {
        val owner = store(); val input = signal()
        val cycle = owner.evaluate(input, timing)!!.startCycle!!
        assertTrue(owner.recordStep(input, cycle, LocalAlarmStepResult(0, environment.nowElapsedMs,
            LocalAlarmNotificationResult.POSTED, audio, LocalAlarmVibrationResult.NOT_REQUESTED, 25), timing))
        assertTrue(owner.finish(cycle, cancelled = audio != LocalAlarmAudioResult.STARTED))
        return cycle
    }

    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), CopilotDatabase::class.java)
            .setQueryCallback(object : RoomDatabase.QueryCallback {
                override fun onQuery(sqlQuery: String, bindArgs: List<Any?>) {
                    if (sqlQuery.contains("AND level IN")) sharedReads += sqlQuery
                }
            }, Executor { it.run() })
            .allowMainThreadQueries().build()
    }
    @After fun close() { db.close() }

    @Test fun finishedAbsentSourceStillDefersDifferentSourceAcrossStoreInstances() = runBlocking {
        delivered()
        advance(3_000)
        val next = store().evaluate(signal("source-b"), timing)!!
        assertEquals("COOLDOWN", next.admission.name)
        assertNull(next.startCycle)
        assertEquals(0L, next.state!!.ordinal)
        val choice = store().previewArbitration(listOf(LocalAlarmRequest(signal("source-b"), 3_000)), null, timing)!!
        assertNull(choice.selected)
        assertEquals(301_000L, choice.nextWakeElapsedMs)
        advance(301_000)
        assertEquals(LocalAlarmAdmission.START, store().evaluate(signal("source-b"), timing)!!.admission)
    }

    @Test fun sourceRecoveryOrGenerationChangeCannotResetSharedInterval() = runBlocking {
        delivered(); advance(3_000)
        store().evaluate(signal().copy(level = LocalAlarmLevel.NONE), timing)
        val replacement = signal().copy(generation = 2)
        assertEquals("COOLDOWN", store().evaluate(replacement, timing)!!.admission.name)
        advance(300_999)
        assertNull(store().evaluate(replacement.copy(observedElapsedMs = environment.nowElapsedMs), timing)!!.startCycle)
        advance(301_000)
        assertEquals(2L, store().evaluate(signal().copy(generation = 2), timing)!!.startCycle!!.ordinal)
    }

    @Test fun notificationOrFailedAudioIsNotRecordedAsHeard() = runBlocking {
        delivered(LocalAlarmAudioResult.FAILED); advance(3_000)
        assertEquals(LocalAlarmAdmission.START, store().evaluate(signal("source-b"), timing)!!.admission)
    }

    @Test fun liveClaimReservesOnlyOneSoftSlotWithoutClaimingDelivery() = runBlocking {
        val owner = store(); val cycle = owner.evaluate(signal(), timing)!!.startCycle!!
        val blocked = store().evaluate(signal("source-b"), timing)!!
        assertEquals("COOLDOWN", blocked.admission.name)
        val request = LocalAlarmRequest(signal("source-b"), 1_000)
        assertEquals(56_000L, owner.previewArbitration(listOf(request), null, timing)!!.nextWakeElapsedMs)
        assertTrue(owner.finish(cycle, cancelled = true))
        assertEquals(LocalAlarmAdmission.START, store().evaluate(signal("source-b"), timing)!!.admission)
    }

    @Test fun differentStoresCannotClaimTwoSoftSourcesConcurrently() = runBlocking {
        val inputs = listOf(signal(), signal("source-b"))
        val results = coroutineScope { inputs.map { async(Dispatchers.Default) { store().evaluate(it, timing)!! } }.awaitAll() }
        assertEquals(1, results.count { it.admission == LocalAlarmAdmission.START })
        assertEquals(1, results.count { it.admission.name == "COOLDOWN" })
    }

    @Test fun criticalSourceBypassesBothLiveSoftReservationAndConfirmedCooldown() = runBlocking {
        delivered(); advance(3_000)
        val low = signal("low", LocalAlarmLevel.LOW_NOW)
        assertEquals(LocalAlarmAdmission.START, store().evaluate(low, timing)!!.admission)
        val decision = store().previewArbitration(listOf(LocalAlarmRequest(signal("critical", LocalAlarmLevel.CRITICAL_5), 3_000),
            LocalAlarmRequest(signal("source-b"), 3_000)), null, timing)!!
        assertEquals(LocalAlarmLevel.CRITICAL_5, decision.selected!!.level)
    }

    @Test fun freshBootAndExpiredConfirmedCooldownDoNotSuppressCurrentRisk() = runBlocking {
        delivered()
        environment = environment.copy(bootCount = 3, nowElapsedMs = 500)
        assertEquals(LocalAlarmAdmission.START, store().evaluate(signal("source-b"), timing)!!.admission)
    }

    @Test fun actualConfiguredSoftIntervalGovernsSharedDeadline() = runBlocking {
        delivered(); advance(3_000)
        val request = LocalAlarmRequest(signal("source-b"), 3_000)
        val choice = store().previewArbitration(listOf(request), null, timing.copy(softRepeatMinutes = 30))!!
        assertNull(choice.selected)
        assertEquals(1_801_000L, choice.nextWakeElapsedMs)
        val expiring = request.copy(evidence = request.evidence.copy(validUntilElapsedMs = 5_000))
        assertEquals(5_000L, store().previewArbitration(listOf(expiring), null, timing)!!.nextWakeElapsedMs)
    }

    @Test fun corruptNoncriticalJournalCannotAuthorizeSoftButNeverBlocksCritical() = runBlocking {
        delivered(); advance(3_000)
        db.openHelper.writableDatabase.execSQL("UPDATE alert_local_cycles SET resultJson='{}'")
        val soft = store().evaluate(signal("source-b"), timing)!!
        assertEquals(LocalAlarmAdmission.UNAVAILABLE, soft.admission)
        val low = signal("low", LocalAlarmLevel.LOW_NOW)
        assertEquals(low, store().previewArbitration(listOf(LocalAlarmRequest(low, 3_000),
            LocalAlarmRequest(signal("source-b"), 3_000)), null, timing)!!.selected)
        assertEquals(LocalAlarmAdmission.START, store().evaluate(low, timing)!!.admission)
    }

    @Test fun criticalSelectionDoesNotWaitForUnrelatedSoftHistoryQuery() = runBlocking {
        delivered(); advance(3_000); sharedReads.clear()
        val low = signal("low", LocalAlarmLevel.LOW_NOW)
        val choice = store().previewArbitration(listOf(LocalAlarmRequest(low, 3_000),
            LocalAlarmRequest(signal("source-b"), 3_000)), null, timing)!!
        assertEquals(low, choice.selected)
        assertTrue("Critical selection must not read unrelated soft history", sharedReads.isEmpty())
        assertEquals(LocalAlarmAdmission.START, store().evaluate(low, timing)!!.admission)
        assertTrue(sharedReads.isEmpty())
    }

    @Test fun criticalCanPreemptLiveSoftReservationBeforeAnyResult() = runBlocking {
        val cycle = store().evaluate(signal(), timing)!!.startCycle!!
        val low = signal("low", LocalAlarmLevel.LOW_NOW)
        val decision = store().previewArbitration(listOf(LocalAlarmRequest(signal(), 1_000), LocalAlarmRequest(low, 1_000)),
            cycle, timing)!!
        assertEquals(low, decision.selected)
        assertEquals(cycle, decision.cancelCycle)
    }

    @Test fun historyReadIsBoundedAndAnOverfullWindowIsNotMistakenForSilence() = runBlocking {
        val cycle = delivered(); advance(3_000)
        val original = db.alertLocalDao().cycle(cycle.key.kind.name, cycle.key.id, cycle.generation, cycle.ordinal)!!
        for (index in 2L..70L) db.alertLocalDao().insertCycle(original.copy(ordinal = index,
            resultJson = LocalAlarmPersistenceCodec.encodeResult(LocalAlarmCycleResult())!!))
        assertEquals(65, db.alertLocalDao().recentNoncriticalCycles(2, 0).size)
        assertEquals(LocalAlarmAdmission.UNAVAILABLE, store().evaluate(signal("source-b"), timing)!!.admission)
        assertEquals(LocalAlarmAdmission.START, store().evaluate(signal("low", LocalAlarmLevel.LOW_NOW), timing)!!.admission)
    }

    @Test fun futureAudioReceiptIsNotCurrentConfirmation() = runBlocking {
        val cycle = delivered()
        val row = db.alertLocalDao().cycle(cycle.key.kind.name, cycle.key.id, cycle.generation, cycle.ordinal)!!
        val result = LocalAlarmPersistenceCodec.decodeResult(row.resultJson)!!
        db.alertLocalDao().updateCycle(row.copy(resultJson = LocalAlarmPersistenceCodec.encodeResult(
            result.copy(steps = listOf(result.steps.single().copy(atElapsedMs = 2_000))))!!))
        advance(1_500)
        assertEquals(LocalAlarmAdmission.UNAVAILABLE, store().evaluate(signal("source-b"), timing)!!.admission)
    }

    @Test fun notificationOnlyReceiptDoesNotDelayAnotherAudibleSource() = runBlocking {
        delivered(LocalAlarmAudioResult.NOT_REQUESTED); advance(3_000)
        assertEquals(LocalAlarmAdmission.START, store().evaluate(signal("source-b"), timing)!!.admission)
    }
}
