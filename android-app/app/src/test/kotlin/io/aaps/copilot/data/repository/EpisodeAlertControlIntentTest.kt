package io.aaps.copilot.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.aaps.copilot.data.local.CopilotDatabase
import io.aaps.copilot.domain.alerts.LocalAlarmAdmission
import io.aaps.copilot.domain.alerts.LocalAlarmEnvironment
import io.aaps.copilot.domain.alerts.LocalAlarmEvidence
import io.aaps.copilot.domain.alerts.LocalAlarmKey
import io.aaps.copilot.domain.alerts.LocalAlarmLevel
import io.aaps.copilot.domain.alerts.LocalAlarmSourceKind
import io.aaps.copilot.domain.alerts.LocalAlarmTiming
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
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
class EpisodeAlertControlIntentTest {
    private lateinit var db: CopilotDatabase
    private val now = 1_000_000L
    private fun signal() = EpisodeAlertSignal(GlucoseAlertState.LOW_NOW, GlucoseAlertDirection.LOW, now)
    private fun machine() = EpisodeAlertDeliveryStateMachine(RoomEpisodeAlertReceiptStore(db), clock = { now })

    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), CopilotDatabase::class.java)
            .allowMainThreadQueries().build()
    }
    @After fun close() { db.close() }

    private suspend fun claimLocal(id: String) {
        val local = RoomLocalAlarmStore(db) { LocalAlarmEnvironment(enabled = true, armed = true,
            nowElapsedMs = 10_000, nowWallMs = now, bootCount = 3) }
        val evidence = LocalAlarmEvidence(LocalAlarmKey(LocalAlarmSourceKind.GLUCOSE, id), 1,
            LocalAlarmLevel.LOW_NOW, 3, 10_000, 70_000, true)
        val result = withTimeout(1_500L) { local.evaluate(evidence, LocalAlarmTiming()) }
        assertEquals(LocalAlarmAdmission.START, result?.admission)
        assertTrue(local.finish(requireNotNull(result?.startCycle)))
    }

    @Test fun nativeDeliveryCanClaimAndFinishLocalAudioWithoutHoldingItsPersistenceLock() = runBlocking {
        val result = machine().coordinate(signal()) {
            claimLocal(it.episodeId)
            AlertSideEffectResult.delivered()
        }
        assertEquals(AlertReceiptResult.DELIVERED, result.receiptResult)
    }

    @Test fun technicalDeliveryDoesNotHoldTheLocalAudioPersistenceLock() = runBlocking {
        machine().coordinateMutedSideEffect {
            assertFalse(it)
            claimLocal("source-technical-handoff")
        }
    }

    @Test fun pendingOffStillDeniesNewLocalClaimsWhileTheDeliveryFinishesCleanup() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val cleaning = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val delivery = async(start = CoroutineStart.UNDISPATCHED) {
            machine().coordinate(signal()) {
                entered.complete(Unit)
                try { awaitCancellation() } finally {
                    withContext(NonCancellable) { cleaning.complete(Unit); finish.await() }
                }
            }
        }
        entered.await()
        val off = async(start = CoroutineStart.UNDISPATCHED) { machine().muteFor(now, 60_000L) }
        try {
            withTimeout(1_500L) { cleaning.await() }
            val local = RoomLocalAlarmStore(db) { LocalAlarmEnvironment(true, true, 10_000, now, 3) }
            val evidence = LocalAlarmEvidence(LocalAlarmKey(LocalAlarmSourceKind.GLUCOSE, "pending-control"),
                1, LocalAlarmLevel.LOW_NOW, 3, 10_000, 70_000, true)
            val result = withTimeout(1_500L) { local.evaluate(evidence, LocalAlarmTiming()) }
            assertEquals(LocalAlarmAdmission.UNAVAILABLE, result?.admission)
            assertNull(result?.startCycle)
        } finally { finish.complete(Unit); delivery.await(); off.await() }
    }

    @Test fun offWithdrawsSourcesBeforeWaitingForBusyRoom() = runBlocking {
        var revoked = false
        var stopped = false
        val owner = EpisodeAlertDeliveryStateMachine(RoomEpisodeAlertReceiptStore(db), revokeAlarmSources = { revoked = true },
            revokeRiskPlayback = { stopped = true })
        val lock = EpisodeAlertOperationLocks.forIdentity(db)
        lock.lock()
        val off = async(start = CoroutineStart.UNDISPATCHED) { owner.muteFor(now, 30 * 60_000L) }
        try {
            assertTrue("OFF must revoke before waiting for Room", revoked)
            assertTrue("Already-playing sound must stop before waiting for Room", stopped)
            assertFalse(off.isCompleted)
        } finally { lock.unlock(); off.await() }
    }

    @Test fun queuedDeliveryCannotStartAfterOffIntentEvenWhenItGetsRoomFirst() = runBlocking {
        val lock = EpisodeAlertOperationLocks.forIdentity(db)
        var attempts = 0
        lock.lock()
        val delivery = async(start = CoroutineStart.UNDISPATCHED) {
            machine().coordinate(signal()) { attempts++; AlertSideEffectResult.delivered() }
        }
        val off = async(start = CoroutineStart.UNDISPATCHED) { machine().muteFor(now, 30 * 60_000L) }
        lock.unlock()
        delivery.await(); off.await()
        assertEquals("The queued pre-OFF signal must not make a new sound", 0, attempts)
    }

    @Test fun offCancelsPreparingDeliveryWithoutCancellingItsCaller() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        var cleaned = false
        var callerContinued = false
        val delivery = async(start = CoroutineStart.UNDISPATCHED) {
            val result = machine().coordinate(signal()) {
                entered.complete(Unit)
                try { awaitCancellation() } finally { cleaned = true }
            }
            callerContinued = true
            result
        }
        entered.await()
        val off = async(start = CoroutineStart.UNDISPATCHED) { machine().muteFor(now, 30 * 60_000L) }
        try {
            withTimeout(1_500L) { off.await() }
            val result = delivery.await()
            assertTrue(cleaned)
            assertTrue("Muting alerts must not cancel the enclosing forecast/therapy caller", callerContinued)
            assertEquals(AlertReceiptResult.FAILED, result.receiptResult)
            assertEquals("alert_control_changed", db.alertDeliveryReceiptDao().byEpisodeId(result.episodeId!!).single().sanitizedError)
        } finally { delivery.cancelAndJoin(); off.cancelAndJoin() }
    }

    @Test fun offAlsoCancelsSuspendedTechnicalDeliveryUsingTheSharedIdentity() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        var cleaned = false
        val delivery = async(start = CoroutineStart.UNDISPATCHED) {
            machine().coordinateMutedSideEffect { muted ->
                assertFalse(muted)
                entered.complete(Unit)
                try { awaitCancellation() } finally { cleaned = true }
            }
        }
        entered.await()
        val off = async(start = CoroutineStart.UNDISPATCHED) { machine().muteFor(now, 30 * 60_000L) }
        try {
            withTimeout(1_500L) { off.await(); delivery.await() }
            assertTrue(cleaned)
        } finally { delivery.cancelAndJoin(); off.cancelAndJoin() }
    }

    @Test fun invalidMuteCannotCancelOrRevokeAnActiveDelivery() = runBlocking {
        var revoked = false
        val owner = EpisodeAlertDeliveryStateMachine(RoomEpisodeAlertReceiptStore(db), revokeRiskPlayback = { revoked = true })
        try { owner.muteFor(now, 0); fail("Invalid duration") } catch (_: IllegalArgumentException) {}
        assertFalse(revoked)
        assertEquals(0, owner.currentMutedUntil())
        assertEquals(AlertReceiptResult.DELIVERED, owner.coordinate(signal()) { AlertSideEffectResult.delivered() }.receiptResult)
    }

    @Test fun failedImmediateCleanupDoesNotPreventPersistentOff() = runBlocking {
        val owner = EpisodeAlertDeliveryStateMachine(RoomEpisodeAlertReceiptStore(db),
            revokeRiskPlayback = { error("synthetic_cleanup_failure") })
        val until = owner.muteFor(now, 30 * 60_000L)
        assertEquals(until, machine().currentMutedUntil())
        assertEquals(AlertDeliveryKind.SUPPRESSED_SNOOZE, machine().coordinate(signal()) {
            fail("Muted delivery"); AlertSideEffectResult.delivered()
        }.deliveryKind)
    }

    @Test fun callerCancellationStillPropagatesAndReleasesTheSharedGate() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val caller = async(start = CoroutineStart.UNDISPATCHED) {
            machine().coordinate(signal()) { entered.complete(Unit); awaitCancellation() }
        }
        entered.await()
        caller.cancel(CancellationException("synthetic_caller_cancel"))
        caller.join()
        assertTrue(caller.isCancelled)
        machine().muteFor(now, 60_000L)
        assertEquals(now + 60_000L, machine().currentMutedUntil())
    }

    @Test fun muteWaitsForPhysicalCleanupButStopsPlaybackBeforeThatCleanupFinishes() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val cleanupEntered = CompletableDeferred<Unit>()
        val finishCleanup = CompletableDeferred<Unit>()
        var immediateStop = false
        val delivery = async(start = CoroutineStart.UNDISPATCHED) {
            machine().coordinate(signal()) {
                entered.complete(Unit)
                try { awaitCancellation() } finally {
                    withContext(NonCancellable) { cleanupEntered.complete(Unit); finishCleanup.await() }
                }
            }
        }
        entered.await()
        val control = EpisodeAlertDeliveryStateMachine(RoomEpisodeAlertReceiptStore(db),
            revokeRiskPlayback = { immediateStop = true })
        val off = async(start = CoroutineStart.UNDISPATCHED) { control.muteFor(now, 60_000L) }
        try {
            withTimeout(1_500L) { cleanupEntered.await() }
            assertTrue(immediateStop)
            assertFalse("Do not commit or replace until exact delivery cleanup completes", off.isCompleted)
        } finally { finishCleanup.complete(Unit); delivery.await(); off.await() }
    }
}
