package io.aaps.copilot.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.aaps.copilot.data.local.CopilotDatabase
import io.aaps.copilot.config.AppSettingsStore
import io.aaps.copilot.data.local.entity.AlertEventEntity
import io.aaps.copilot.domain.alerts.LocalAlarmLevel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
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
class GlucoseAlarmSourceTest {
    private lateinit var db: CopilotDatabase
    private var now = 1_000_000L
    private var elapsed = 10_000L
    private var boot = 3
    private var nativeCalls = 0
    private fun clock() = LocalAlarmSourceClock(now, elapsed, boot)
    private fun advance(ms: Long) { now += ms; elapsed += ms }
    private fun machine(store: EpisodeAlertReceiptStore = RoomEpisodeAlertReceiptStore(db),
        revoke: () -> Unit = {}) = EpisodeAlertDeliveryStateMachine(store, clock = { now },
        alarmClock = ::clock, revokeAlarmSources = revoke)
    private fun decision(stage: GlucoseAlertState = GlucoseAlertState.WARNING_30) = GlucoseAlertDecision(
        state = stage, direction = if (stage == GlucoseAlertState.SOFT_HIGH_RISK) GlucoseAlertDirection.HIGH else GlucoseAlertDirection.LOW,
        notifyKind = GlucoseAlertNotifyKind.NONE, nextState = GlucoseAlertRuntimeState(),
        lowThreshold = 4.4, highThreshold = 9.0, urgentLowThreshold = 3.5,
        pred5 = 5.0, pred30 = 4.0, pred60 = 3.0, ciLow30 = 3.5, ciHigh30 = 4.5,
        currentGlucoseMmol = if (stage == GlucoseAlertState.LOW_NOW) 3.3 else 5.5,
        currentGlucoseFresh = true, predictedMinutesToLow = 20, trendDelta5Mmol = -0.1,
        softActive = true, strongActive = false, repeatSuppressedByTrend = false, disableReason = null,
        currentAlarmStage = stage)
    private fun signal(stage: GlucoseAlertState = GlucoseAlertState.WARNING_30, measuredAt: Long = now): EpisodeAlertSignal {
        val decision = decision(stage)
        return EpisodeAlertSignal(stage, decision.direction, now,
            alarmObservation = GlucoseAlarmObservation.fromDecision(decision, now, measuredAt))
    }
    private suspend fun accept(machine: EpisodeAlertDeliveryStateMachine, signal: EpisodeAlertSignal = signal()): EpisodeCoordinationResult =
        machine.coordinate(signal) {
            nativeCalls++
            assertNull("Source must not gate initial native delivery", machine.currentAlarmSource())
            AlertSideEffectResult.delivered()
        }

    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), CopilotDatabase::class.java)
            .allowMainThreadQueries().build()
    }
    @After fun close() { db.close() }

    @Test fun sourceIsPublishedOnlyAfterAcceptedCommittedEpisode() = runBlocking {
        val machine = machine()
        assertNull(machine.currentAlarmSource())
        val result = accept(machine)
        val source = machine.currentAlarmSource()!!
        assertEquals(result.episodeId, source.evidence.key.id)
        assertEquals(LocalAlarmLevel.WARNING_30, source.evidence.level)
        assertEquals("OPEN", db.alertEventDao().byEpisodeId(result.episodeId!!)!!.status)
        assertEquals("DELIVERED", db.alertDeliveryReceiptDao().byEpisodeAndKind(result.episodeId!!, "INITIAL")!!.result)
        assertEquals(1, nativeCalls)
    }

    @Test fun duplicateDoesNotReplayInitialOrResetGenerationQueueAndExpiry() = runBlocking {
        val machine = machine(); val measuredAt = now
        accept(machine); val first = machine.currentAlarmSource()!!
        advance(60_000); accept(machine, signal(measuredAt = measuredAt))
        val second = machine.currentAlarmSource()!!
        assertEquals(first.evidence.generation, second.evidence.generation)
        assertEquals(first.evidence.validUntilElapsedMs, second.evidence.validUntilElapsedMs)
        assertEquals(first.pendingSinceElapsedMs, second.pendingSinceElapsedMs)
        assertEquals(1, nativeCalls)
    }

    @Test fun freshCgmRenewsWindowButKeepsEpisodeGeneration() = runBlocking {
        val machine = machine(); accept(machine)
        val first = machine.currentAlarmSource()!!
        advance(300_000); accept(machine)
        val next = machine.currentAlarmSource()!!
        assertEquals(first.evidence.generation, next.evidence.generation)
        assertTrue(next.evidence.validUntilElapsedMs > first.evidence.validUntilElapsedMs)
        assertEquals(1, nativeCalls)
    }

    @Test fun escalationUsesExistingEpisodeGenerationAndCurrentLevel() = runBlocking {
        val machine = machine(); accept(machine)
        val first = machine.currentAlarmSource()!!
        advance(1_000); accept(machine, signal(GlucoseAlertState.LOW_NOW))
        val next = machine.currentAlarmSource()!!
        assertEquals(first.evidence.key, next.evidence.key)
        assertEquals(first.evidence.generation, next.evidence.generation)
        assertEquals(LocalAlarmLevel.LOW_NOW, next.evidence.level)
        assertEquals(2, nativeCalls)
    }

    @Test fun oppositeRiskRequiresNewCommittedEpisode() = runBlocking {
        val machine = machine(); accept(machine)
        val first = machine.currentAlarmSource()!!
        advance(1_000); accept(machine, signal(GlucoseAlertState.SOFT_HIGH_RISK))
        assertNotEquals(first.evidence.key, machine.currentAlarmSource()!!.evidence.key)
        assertEquals(LocalAlarmLevel.SOFT_HIGH_RISK, machine.currentAlarmSource()!!.evidence.level)
    }

    @Test fun safeSignalRevokesEvenWhileLegacyEpisodeIsSafePending() = runBlocking {
        val machine = machine(); accept(machine); advance(1_000)
        machine.coordinate(EpisodeAlertSignal(GlucoseAlertState.NONE, null, now)) { fail("No clear delivery"); AlertSideEffectResult.failed("unexpected") }
        assertNull(machine.currentAlarmSource()); assertNull(machine.alarmSource.value)
        assertEquals("SAFE_PENDING", db.alertEventDao().latestUnresolvedGlucoseEpisode()!!.status)
    }

    @Test fun missingObservationAndHistoricalStartupCannotMintAuthority() = runBlocking {
        val machine = machine(); accept(machine)
        val restart = machine()
        assertNull(restart.currentAlarmSource()); assertNull(restart.alarmSource.value)
        advance(1_000)
        restart.coordinate(EpisodeAlertSignal(GlucoseAlertState.WARNING_30, GlucoseAlertDirection.LOW, now)) { fail("No replay"); AlertSideEffectResult.failed("unexpected") }
        assertNull(restart.currentAlarmSource())
    }

    @Test fun staleOrFutureMeasurementAndMalformedCurrentValueAreRejected() {
        assertNull(GlucoseAlarmObservation.fromDecision(decision(), now, now + 1))
        assertNull(GlucoseAlarmObservation.fromDecision(decision(), now, now - 360_000))
        assertNull(GlucoseAlarmObservation.fromDecision(decision().copy(currentGlucoseFresh = false), now, now))
        for (value in listOf(Double.NaN, Double.POSITIVE_INFINITY, -1.0, 0.0)) {
            assertNull(GlucoseAlarmObservation.fromDecision(decision().copy(currentGlucoseMmol = value), now, now))
        }
    }

    @Test fun expiryMatchesExistingIntegerFreshnessBucket() = runBlocking {
        val machine = machine(); val measuredAt = now
        advance(359_999); accept(machine, signal(measuredAt = measuredAt))
        assertNotNull(machine.currentAlarmSource())
        advance(1); assertNull(machine.currentAlarmSource())
    }

    @Test fun mismatchedSignalCannotReuseAValidObservation() = runBlocking {
        val machine = machine()
        val mismatched = signal().copy(stage = GlucoseAlertState.LOW_NOW)
        accept(machine, mismatched)
        assertNull(machine.currentAlarmSource())
    }

    @Test fun offAndResumeRevokeImmediatelyAndExpiryAloneDoesNotRestore() = runBlocking {
        val machine = machine(); accept(machine)
        machine.muteFor(now, 30 * 60_000L)
        assertNull(machine.currentAlarmSource())
        advance(30 * 60_000L)
        assertNull(machine.currentAlarmSource())
        accept(machine)
        assertNotNull(machine.currentAlarmSource())
        assertEquals(1, nativeCalls)
        machine.resume(now); assertNull(machine.currentAlarmSource())
    }

    @Test fun offBeforeAnyDeliveryDoesNotAuthorizeUntilFreshUnmutedAcceptance() = runBlocking {
        val machine = machine(); machine.muteFor(now, 30 * 60_000L)
        accept(machine)
        assertNull(machine.currentAlarmSource()); assertEquals(0, nativeCalls)
        advance(30 * 60_000L); accept(machine)
        assertNotNull(machine.currentAlarmSource()); assertEquals(0, nativeCalls)
    }

    @Test fun failedMutePersistenceStillRevokesAllSourcesBeforeWrite() = runBlocking {
        var revocations = 0
        val machine = machine(revoke = { revocations++ }); accept(machine)
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_mute BEFORE INSERT ON alert_events " +
            "WHEN NEW.episodeId='glucose-alert-mute-state' BEGIN SELECT RAISE(ABORT,'test_rejection'); END")
        try { machine.muteFor(now, 30 * 60_000L); fail("Expected write failure") } catch (_: Exception) {}
        assertNull(machine.currentAlarmSource()); assertEquals(1, revocations)
    }

    @Test fun processingFailureRevokesOldSourceBeforePersistingReplacement() = runBlocking {
        val machine = machine(); accept(machine); advance(300_000)
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_event BEFORE INSERT ON alert_events BEGIN SELECT RAISE(ABORT,'test_rejection'); END")
        try { accept(machine); fail("Expected write failure") } catch (_: Exception) {}
        assertNull(machine.currentAlarmSource())
    }

    @Test fun cancellationDuringNativeDeliveryRevokesAndPropagates() = runBlocking {
        val machine = machine(); accept(machine); advance(1_000)
        try {
            machine.coordinate(signal(GlucoseAlertState.SOFT_HIGH_RISK)) { throw CancellationException("cancel_delivery") }
            fail("Expected cancellation")
        } catch (_: CancellationException) {}
        assertNull(machine.currentAlarmSource())
    }

    @Test fun optionalReadFailureDoesNotUndoNativeReceiptOrReplayInitial() = runBlocking {
        val store = SourceReadStore(RoomEpisodeAlertReceiptStore(db))
        store.failOnRead = 3
        val machine = machine(store)
        val result = accept(machine)
        assertEquals(AlertReceiptResult.DELIVERED, result.receiptResult)
        assertNull(machine.currentAlarmSource()); assertEquals(1, nativeCalls)
        store.failOnRead = 0; advance(300_000); accept(machine)
        assertNotNull(machine.currentAlarmSource()); assertEquals(1, nativeCalls)
    }

    @Test fun optionalReadCannotTrustAChangedCommittedEpisodeBinding() = runBlocking {
        val store = SourceReadStore(RoomEpisodeAlertReceiptStore(db))
        store.transformOnRead = { event -> event.copy(status = "SAFE_PENDING") }
        val machine = machine(store)
        accept(machine)
        assertNull(machine.currentAlarmSource()); assertEquals(1, nativeCalls)
    }

    @Test fun optionalReadTimeoutCannotUndoDeliveredInitialReceipt() = runBlocking {
        val store = SourceReadStore(RoomEpisodeAlertReceiptStore(db)).also { it.sourceReadDelay = 5_000 }
        val machine = machine(store)
        val result = accept(machine)
        assertEquals(AlertReceiptResult.DELIVERED, result.receiptResult)
        assertNull(machine.currentAlarmSource()); assertEquals(1, nativeCalls)
        assertEquals("DELIVERED", db.alertDeliveryReceiptDao().byEpisodeAndKind(result.episodeId!!, "INITIAL")!!.result)
    }

    @Test fun parentCancellationDuringOptionalReadRevokesButDoesNotReplayInitial() = runBlocking {
        val store = SourceReadStore(RoomEpisodeAlertReceiptStore(db)).also { it.cancelSourceRead = true }
        val machine = machine(store)
        try { accept(machine); fail("Expected cancellation") } catch (_: CancellationException) {}
        assertNull(machine.currentAlarmSource()); assertEquals(1, nativeCalls)
        store.cancelSourceRead = false; advance(300_000); accept(machine)
        assertNotNull(machine.currentAlarmSource()); assertEquals(1, nativeCalls)
    }

    @Test fun ordinaryRevocationCallbackFailureCannotPreventRoomOff() = runBlocking {
        val machine = machine(revoke = { error("source_callback_unavailable") }); accept(machine)
        val until = machine.muteFor(now, 30 * 60_000L)
        assertEquals(now + 30 * 60_000L, until)
        assertEquals(until, RoomEpisodeAlertReceiptStore(db).muteUntil())
        assertNull(machine.currentAlarmSource())
    }

    @Test fun publicNotifierForwardsActualMeasurementTimestampWithoutReplayingInitial() = runBlocking {
        val machine = machine(); accept(machine)
        val first = machine.currentAlarmSource()!!
        advance(300_000)
        val context = ApplicationProvider.getApplicationContext<Context>()
        val defaults = AppSettingsStore(object : DataStore<Preferences> {
            override val data = flowOf(emptyPreferences())
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences = error("read-only defaults")
        }).settings.first()
        val notifier = GlucoseAlertNotifier(context, GlucoseAlertAudioController(context), machine)
        val result = notifier.deliver(decision(), 5.5, defaults, nowTs = now, currentGlucoseTimestamp = now)
        val next = machine.currentAlarmSource()!!
        assertTrue(next.evidence.validUntilElapsedMs > first.evidence.validUntilElapsedMs)
        assertEquals(first.evidence.generation, next.evidence.generation)
        assertFalse(result.audioPlayed); assertFalse(result.notificationPosted); assertEquals(1, nativeCalls)
        advance(1_000)
        notifier.deliver(decision(), 5.5, defaults, nowTs = now)
        assertNull("A missing production timestamp never falls back to now", machine.currentAlarmSource())
    }

    @Test fun latchedLegacyStageCannotAuthorizeRepeatingWhenCurrentCandidateIsSafe() {
        assertNull(GlucoseAlarmObservation.fromDecision(decision(GlucoseAlertState.LOW_NOW)
            .copy(currentAlarmStage = GlucoseAlertState.NONE), now, now))
    }

    @Test fun unavailableSourceClockCannotSuppressExistingNativeDelivery() = runBlocking {
        val machine = EpisodeAlertDeliveryStateMachine(RoomEpisodeAlertReceiptStore(db), clock = { now },
            alarmClock = { error("clock_unavailable") })
        val result = accept(machine)
        assertEquals(AlertReceiptResult.DELIVERED, result.receiptResult)
        assertEquals(1, nativeCalls); assertNull(machine.currentAlarmSource())
    }

    @Test fun oldBootAndExpiredDataRevokeWithoutAProducerCall() = runBlocking {
        val machine = machine(); accept(machine)
        boot++
        assertNull(machine.currentAlarmSource())
        assertNull(machine.alarmSource.value)
    }

    private class SourceReadStore(private val delegate: EpisodeAlertReceiptStore) : EpisodeAlertReceiptStore by delegate {
        var failOnRead = 0
        var sourceReadDelay = 0L
        var cancelSourceRead = false
        var transformOnRead: ((AlertEventEntity) -> AlertEventEntity)? = null
        private var reads = 0
        override suspend fun <T> transaction(block: suspend EpisodeAlertReceiptStore.() -> T): T =
            delegate.transaction { block(this@SourceReadStore) }
        override suspend fun eventById(episodeId: String): AlertEventEntity? {
            val event = delegate.eventById(episodeId)
            if (event?.eventType !in listOf("GLUCOSE_ALERT_LOW", "GLUCOSE_ALERT_HIGH")) return event
            reads++
            if (reads == failOnRead) error("optional_source_read_unavailable")
            if (reads >= 3) {
                if (cancelSourceRead) throw CancellationException("source_read_cancelled")
                if (sourceReadDelay > 0) delay(sourceReadDelay)
            }
            return if (reads >= 3 && event != null) transformOnRead?.invoke(event) ?: event else event
        }
    }
}
