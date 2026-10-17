package io.aaps.copilot.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.aaps.copilot.data.local.CopilotDatabase
import io.aaps.copilot.domain.alerts.DeliveryDiagnosticObservation
import io.aaps.copilot.domain.alerts.LocalAlarmLevel
import kotlinx.coroutines.CancellationException
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
class DeliveryDiagnosticAlarmSourceTest {
    private lateinit var db: CopilotDatabase
    private lateinit var alerts: EpisodeAlertDeliveryStateMachine
    private var revocable: DeliveryDiagnosticRepository? = null
    private var now = BASE
    private var elapsed = 1_000_000L
    private var boot = 3
    private var posts = 0
    private var postResult = AlertReceiptResult.DELIVERED
    private var cancelPost = false
    private var postAdvanceMs = 0L
    private fun clock() = LocalAlarmSourceClock(now, elapsed, boot)
    private fun at(minute: Int) { now = BASE + minute * MINUTE; elapsed = 1_000_000L + minute * MINUTE }
    private fun repository() = DeliveryDiagnosticRepository(db, alerts, {
        posts++
        assertNull("Initial notice remains independent and first", revocable?.currentAlarmSource())
        if (cancelPost) throw CancellationException("cancel_notice")
        now += postAdvanceMs; elapsed += postAdvanceMs
        postResult
    }, {}, { now }, alarmClock = ::clock).also { revocable = it }
    private fun sample(minute: Int, value: Double = 9.0 + minute * 0.1, basis: String = "basis") =
        DeliveryDiagnosticObservation(BASE + minute * MINUTE, BASE + minute * MINUTE, value,
            3.0, 0.02, 2.0, 10.0, 40.0, true, value, value - 0.5, value + 0.5,
            BASE + (minute + 30) * MINUTE, basis)
    private suspend fun feed(repo: DeliveryDiagnosticRepository, minute: Int, value: Double = 9.0 + minute * 0.1) {
        at(minute); repo.accept(now, sample(minute, value), true, false)
    }
    private suspend fun confirmed(repo: DeliveryDiagnosticRepository) { (0..45 step 5).forEach { feed(repo, it) } }

    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), CopilotDatabase::class.java)
            .allowMainThreadQueries().build()
        alerts = EpisodeAlertDeliveryStateMachine(RoomEpisodeAlertReceiptStore(db), clock = { now },
            alarmClock = ::clock, revokeAlarmSources = { revocable?.invalidateAlarmSource() })
    }
    @After fun close() { db.close() }

    @Test fun onlyConfirmedAcceptedCurrentRiskPublishesSource() = runBlocking {
        val repo = repository()
        (0..30 step 5).forEach { feed(repo, it) }
        assertNull(repo.currentAlarmSource())
        (35..45 step 5).forEach { feed(repo, it) }
        val source = repo.currentAlarmSource()!!
        assertEquals(LocalAlarmLevel.DELIVERY_DIAGNOSTIC, source.evidence.level)
        assertEquals(repo.display.value.episodeId, source.evidence.key.id)
        assertEquals(1, posts)
    }

    @Test fun duplicateDoesNotRenewWindowGenerationOrQueueAge() = runBlocking {
        val repo = repository(); confirmed(repo)
        val first = repo.currentAlarmSource()!!
        at(46); repo.accept(now, sample(45).copy(observedAt = now), true, false)
        val next = repo.currentAlarmSource()!!
        assertEquals(first.evidence.generation, next.evidence.generation)
        assertEquals(first.evidence.validUntilElapsedMs, next.evidence.validUntilElapsedMs)
        assertEquals(first.pendingSinceElapsedMs, next.pendingSinceElapsedMs)
        assertEquals(1, posts)
    }

    @Test fun freshCgmRenewsEvidenceWithoutRepeatingInitialNotice() = runBlocking {
        val repo = repository(); confirmed(repo)
        val first = repo.currentAlarmSource()!!
        feed(repo, 50)
        val next = repo.currentAlarmSource()!!
        assertEquals(first.evidence.generation, next.evidence.generation)
        assertTrue(next.evidence.validUntilElapsedMs > first.evidence.validUntilElapsedMs)
        assertEquals(1, posts)
    }

    @Test fun retainedStartupProjectionIsNotSourceAuthority() = runBlocking {
        val repo = repository(); confirmed(repo)
        val restart = repository(); restart.refreshRetainedDisplay(now)
        assertFalse(restart.display.value.episodeId.isBlank())
        assertNull(restart.currentAlarmSource()); assertNull(restart.alarmSource.value)
        assertEquals(1, posts)
    }

    @Test fun ordinaryDisplayRefreshDoesNotReplaceCurrentAcceptedSource() = runBlocking {
        val repo = repository(); confirmed(repo)
        val first = repo.currentAlarmSource()!!
        repo.refreshRetainedDisplay(now)
        assertSame(first, repo.currentAlarmSource())
    }

    @Test fun changedBasisOrInvalidObservationRevokesWithoutResolvingHistory() = runBlocking {
        val repo = repository(); confirmed(repo)
        at(50); repo.accept(now, sample(50, basis = "different"), true, false)
        assertNull(repo.currentAlarmSource())
        assertEquals("OPEN", db.alertEventDao().byEpisodeId(repo.display.value.episodeId)!!.status)
        at(55); repo.accept(now, null, true, false)
        assertNull(repo.currentAlarmSource()); assertEquals(1, posts)
    }

    @Test fun currentLowRiskAndDisabledDiagnosticsCannotAuthorizeSource() = runBlocking {
        val repo = repository(); confirmed(repo)
        at(50); repo.accept(now, sample(50), true, true)
        assertNull(repo.currentAlarmSource())
        at(55); repo.accept(now, sample(55), false, false)
        assertNull(repo.currentAlarmSource()); assertEquals(1, posts)
    }

    @Test fun globalOffRevokesImmediatelyAndExpiryAloneDoesNotRepublish() = runBlocking {
        val repo = repository(); confirmed(repo)
        alerts.muteFor(now, 30 * MINUTE)
        assertNull(repo.currentAlarmSource()); assertNull(repo.alarmSource.value)
        at(75); assertNull(repo.currentAlarmSource())
        feed(repo, 75)
        assertNull("A gap requires reconfirmation", repo.currentAlarmSource())
        assertEquals(1, posts)
    }

    @Test fun mutedFreshEvidenceRemainsUnavailableUntilNewUnmutedAcceptance() = runBlocking {
        val repo = repository(); confirmed(repo)
        alerts.muteFor(now, 30 * MINUTE)
        (50..70 step 5).forEach { feed(repo, it); assertNull(repo.currentAlarmSource()) }
        at(75); assertNull(repo.currentAlarmSource())
        feed(repo, 75)
        assertNotNull(repo.currentAlarmSource())
        assertEquals(1, posts)
    }

    @Test fun failedNoticeDoesNotResolveFreshAcceptedRiskOrRetryInitial() = runBlocking {
        postResult = AlertReceiptResult.FAILED
        val repo = repository(); confirmed(repo)
        assertNotNull(repo.currentAlarmSource()); assertEquals(1, posts)
        feed(repo, 50)
        assertNotNull(repo.currentAlarmSource()); assertEquals(1, posts)
    }

    @Test fun cancelledNoticeCannotLeaveSourceAuthorityOrReplayAnInitial() = runBlocking {
        val repo = repository(); (0..30 step 5).forEach { feed(repo, it) }
        cancelPost = true
        try { feed(repo, 35); fail("Expected cancellation") } catch (_: CancellationException) {}
        assertNull(repo.currentAlarmSource())
        cancelPost = false; feed(repo, 40)
        assertNotNull(repo.currentAlarmSource()); assertEquals(1, posts)
    }

    @Test fun storageFailureRevokesBeforeReplacementWrite() = runBlocking {
        val repo = repository(); confirmed(repo)
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_diagnostic BEFORE INSERT ON alert_events BEGIN SELECT RAISE(ABORT,'test_rejection'); END")
        try { feed(repo, 50); fail("Expected failure") } catch (_: Exception) {}
        assertNull(repo.currentAlarmSource())
    }

    @Test fun corruptRetainedReadRevokesCachedSourceRatherThanRestoringAuthority() = runBlocking {
        val repo = repository(); confirmed(repo)
        val state = db.alertEventDao().byEpisodeId(DeliveryDiagnosticRepository.STATE_ID)!!
        db.alertEventDao().upsert(state.copy(localSnapshotJson = "{}"))
        try { repo.refreshRetainedDisplay(now); fail("Expected failure") } catch (_: Exception) {}
        assertNull(repo.currentAlarmSource())
    }

    @Test fun failureReadingGlobalOffBeforeAcceptanceRevokesTheOldSource() = runBlocking {
        val repo = repository(); confirmed(repo)
        db.close()
        try { feed(repo, 50); fail("Expected failure") } catch (_: Exception) {}
        assertNull(repo.currentAlarmSource())
    }

    @Test fun staleForecastOriginTightensEvenAFreshCgmWindow() = runBlocking {
        val repo = repository(); confirmed(repo)
        at(50)
        repo.accept(now, sample(50).copy(forecast30TargetTs = BASE + 76 * MINUTE), true, false)
        val source = repo.currentAlarmSource()!!
        assertEquals(1_000_000L + 52 * MINUTE + 1, source.evidence.validUntilElapsedMs)
        at(52); assertNotNull(repo.currentAlarmSource())
        now++; elapsed++; assertNull(repo.currentAlarmSource())
    }

    @Test fun slowInitialNoticeCannotPublishAlreadyExpiredRisk() = runBlocking {
        val repo = repository(); (0..30 step 5).forEach { feed(repo, it) }
        postAdvanceMs = 7 * MINUTE
        feed(repo, 35)
        assertNull(repo.currentAlarmSource()); assertEquals(1, posts)
        assertEquals("DELIVERED", db.alertDeliveryReceiptDao().byEpisodeAndKind(repo.display.value.episodeId, "INITIAL")!!.result)
    }

    @Test fun invalidEvaluationTimeRevokesInsteadOfLeavingOldAuthority() = runBlocking {
        val repo = repository(); confirmed(repo)
        try { repo.accept(0, sample(50), true, false); fail("Expected rejection") } catch (_: IllegalArgumentException) {}
        assertNull(repo.currentAlarmSource())
    }

    @Test fun bootAndExpiryAreCheckedWithoutNewPackets() = runBlocking {
        val repo = repository(); confirmed(repo)
        at(52); assertNull(repo.currentAlarmSource())
        feed(repo, 55); assertNull(repo.currentAlarmSource())
        (60..100 step 5).forEach { feed(repo, it) }
        assertNotNull(repo.currentAlarmSource())
        boot++; assertNull(repo.currentAlarmSource())
    }

    @Test fun sustainedRecoveryRevokesButDoesNotAddRecoverySound() = runBlocking {
        val repo = repository(); confirmed(repo)
        (50..85 step 5).forEach { feed(repo, it, 7.0) }
        assertNull(repo.currentAlarmSource()); assertEquals(1, posts)
        assertEquals("RESOLVED", db.alertEventDao().between(BASE, now)
            .single { it.eventType == DeliveryDiagnosticRepository.EVENT_TYPE }.status)
    }

    private companion object { const val BASE = 10_000_000L; const val MINUTE = 60_000L }
}
