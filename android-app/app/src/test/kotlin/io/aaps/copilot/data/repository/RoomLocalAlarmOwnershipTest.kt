package io.aaps.copilot.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.aaps.copilot.data.local.CopilotDatabase
import io.aaps.copilot.domain.alerts.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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
class RoomLocalAlarmOwnershipTest {
    private lateinit var db: CopilotDatabase
    private var environment = LocalAlarmEnvironment(true, true, 1_000, 10_000, 2)
    private val signal = LocalAlarmEvidence(LocalAlarmKey(LocalAlarmSourceKind.GLUCOSE, "source-a"),
        1, LocalAlarmLevel.WARNING_30, 2, 1_000, 900_000, true)
    private val timing = LocalAlarmTiming()
    private fun store() = RoomLocalAlarmStore(db) { environment }
    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), CopilotDatabase::class.java)
            .allowMainThreadQueries().build()
    }
    @After fun close() { db.close() }

    @Test fun interruptedCleanupNeverCreatesReplacementAndPreservesDueOrdinalAndProgress() = runBlocking {
        val owner = store(); val cycle = owner.evaluate(signal, timing)!!.startCycle!!
        assertTrue(owner.recordStep(signal, cycle, LocalAlarmStepResult(0, 1_000,
            LocalAlarmNotificationResult.NOT_REQUESTED, LocalAlarmAudioResult.STARTED,
            LocalAlarmVibrationResult.NOT_REQUESTED, 25), timing))
        val before = db.alertLocalDao().state(signal.key.kind.name, signal.key.id)!!
        assertTrue(owner.markInterrupted(cycle))
        val after = db.alertLocalDao().state(signal.key.kind.name, signal.key.id)!!
        assertEquals(before.ordinal, after.ordinal); assertEquals(before.nextDueElapsedMs, after.nextDueElapsedMs)
        assertEquals(25, LocalAlarmPersistenceCodec.decodeState(after.stateJson)!!.reachedPercent)
        assertEquals("UNCERTAIN", db.alertLocalDao().cycle(signal.key.kind.name, signal.key.id, 1, 1)!!.status)
        assertNull(db.alertLocalDao().cycle(signal.key.kind.name, signal.key.id, 1, 2))
        assertEquals(LocalAlarmAdmission.WAITING, owner.evaluate(signal, timing)!!.admission)
    }

    @Test fun mismatchedAndRepeatedInterruptionCannotChangeAnotherClaim() = runBlocking {
        val owner = store(); val cycle = owner.evaluate(signal, timing)!!.startCycle!!
        assertFalse(owner.markInterrupted(cycle.copy(ordinal = 2)))
        assertTrue(owner.markInterrupted(cycle)); assertFalse(owner.markInterrupted(cycle))
        environment = environment.copy(nowElapsedMs = 301_000)
        val next = owner.evaluate(signal, timing)!!.startCycle!!
        assertFalse(owner.markInterrupted(cycle))
        assertEquals("CLAIMED", db.alertLocalDao().cycle(signal.key.kind.name, signal.key.id, 1, next.ordinal)!!.status)
    }

    @Test fun previousBootInterruptionIsMetadataCleanupNotFreshAuthority() = runBlocking {
        val owner = store(); val cycle = owner.evaluate(signal, timing)!!.startCycle!!
        environment = environment.copy(bootCount = 3, mutedUntilWallMs = 90_000)
        assertTrue(owner.markInterrupted(cycle))
        assertEquals("UNCERTAIN", db.alertLocalDao().cycle(signal.key.kind.name, signal.key.id, 1, 1)!!.status)
        assertEquals(LocalAlarmAdmission.INVALID_EVIDENCE, owner.evaluate(signal, timing)!!.admission)
    }

    @Test fun runtimeOwnershipIsSharedPerDatabaseButNeverHoldsGlobalMuteLock() = runBlocking {
        val first = store(); val second = store()
        assertSame(first.runtimeMutex, second.runtimeMutex)
        assertNotSame(first.runtimeMutex, EpisodeAlertOperationLocks.forIdentity(db))
        first.runtimeMutex.lock()
        try { assertNotNull(withTimeout(2_000) { second.evaluate(signal, timing) }) }
        finally { first.runtimeMutex.unlock() }
    }
}
