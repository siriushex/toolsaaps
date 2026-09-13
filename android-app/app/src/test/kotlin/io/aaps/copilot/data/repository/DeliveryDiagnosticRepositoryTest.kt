package io.aaps.copilot.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.aaps.copilot.data.local.CopilotDatabase
import io.aaps.copilot.domain.alerts.DeliveryDiagnosticObservation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
class DeliveryDiagnosticRepositoryTest {
    private lateinit var db: CopilotDatabase
    private var now = BASE
    private var posts = 0
    private var clears = 0

    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), CopilotDatabase::class.java)
            .allowMainThreadQueries().build()
    }
    @After fun tearDown() { db.close() }

    private fun mute() = EpisodeAlertDeliveryStateMachine(RoomEpisodeAlertReceiptStore(db), clock = { now })
    private fun repository(post: suspend () -> AlertReceiptResult = { posts++; AlertReceiptResult.DELIVERED }) =
        DeliveryDiagnosticRepository(db, mute(), { post() }, { clears++ }, { now })

    private fun sample(minute: Int, value: Double = 9.0 + minute * 0.1, basis: String = "basis") = DeliveryDiagnosticObservation(
        observedAt = BASE + minute * MINUTE, glucoseTs = BASE + minute * MINUTE,
        glucoseMmol = value, positiveIobUnits = 3.0, activityUnitsPerMinute = 0.02,
        isfMmolPerUnit = 2.0, crGramsPerUnit = 10.0, cobGrams = 40.0, uamActive = true,
        forecast30Mmol = value, forecast30CiLow = value - 0.5, forecast30CiHigh = value + 0.5,
        basisKey = basis, forecast30TargetTs = BASE + (minute + 30) * MINUTE
    )
    private suspend fun feed(minute: Int, repo: DeliveryDiagnosticRepository = repository(), value: Double = 9.0 + minute * 0.1) {
        now = BASE + minute * MINUTE
        repo.accept(now, sample(minute, value), enabled = true, lowRisk = false)
    }
    private suspend fun episodes() = db.alertEventDao().between(BASE - MINUTE, now)
        .filter { it.eventType == DeliveryDiagnosticRepository.EVENT_TYPE }

    @Test fun foodAndUamCanOpenOneIndependentEpisodeAcrossRecreation() = runBlocking {
        (0..45 step 5).forEach { feed(it) }
        assertThat(posts).isEqualTo(1)
        assertThat(episodes()).hasSize(1)
        assertThat(db.alertEventDao().latestUnresolvedGlucoseEpisode()).isNull()
        val episode = episodes().single()
        assertThat(episode.causeCode).isEqualTo("DELIVERY_NONRESPONSE")
        assertThat(db.alertDeliveryReceiptDao().byEpisodeAndKind(episode.episodeId, "INITIAL")?.result).isEqualTo("DELIVERED")
        val repeated = sample(45).copy(observedAt = now + MINUTE)
        now += MINUTE
        repository().accept(now, repeated, true, false)
        assertThat(posts).isEqualTo(1)
    }

    @Test fun offThirtyAndSixtyNeverDeliverAnEpisodeSeenDuringMuteAfterExpiry() = runBlocking {
        listOf(30L, 60L).forEach { duration ->
            db.clearAllTables(); now = BASE; posts = 0
            (0..10 step 5).forEach { feed(it) }
            mute().muteFor(now, duration * MINUTE)
            (15..100 step 5).forEach { feed(it) }
            assertThat(posts).isEqualTo(0)
            val episode = episodes().single()
            assertThat(db.alertDeliveryReceiptDao().byEpisodeAndKind(episode.episodeId, "SUPPRESSED_SNOOZE")?.result)
                .isEqualTo("SUPPRESSED")
        }
    }

    @Test fun pendingEvidenceDuringOffIsNotDelayedUntilMuteExpiry() = runBlocking {
        now = BASE + 5 * MINUTE
        mute().muteFor(now, 30 * MINUTE)
        (0..45 step 5).forEach { feed(it) }
        assertThat(posts).isEqualTo(0)
        assertThat(episodes()).hasSize(1)
    }

    @Test fun firstCandidateAtExactMuteExpiryMayNotifyAfterConfirmation() = runBlocking {
        mute().muteFor(now, 30 * MINUTE)
        (0..45 step 5).forEach { feed(it) }
        assertThat(posts).isEqualTo(1)
    }

    @Test fun missingDataOrDisabledAlertsDoNotResolveOrRenotifyExistingEpisode() = runBlocking {
        (0..35 step 5).forEach { feed(it) }
        now += 5 * MINUTE
        repository().accept(now, null, true, false)
        now += 5 * MINUTE
        repository().accept(now, sample(45), false, false)
        (50..95 step 5).forEach { feed(it) }
        assertThat(posts).isEqualTo(1)
        assertThat(episodes().single().status).isEqualTo("OPEN")
    }

    @Test fun sustainedRecoveryClosesEpisodeAndAllowsANewIndependentEpisode() = runBlocking {
        (0..35 step 5).forEach { feed(it) }
        (40..75 step 5).forEach { feed(it, value = 7.0) }
        assertThat(episodes().single().status).isEqualTo("RESOLVED")
        assertThat(clears).isGreaterThan(0)
        (80..125 step 5).forEach { feed(it, value = 9.0 + (it - 80) * 0.1) }
        assertThat(posts).isEqualTo(2)
        assertThat(episodes()).hasSize(2)
    }

    @Test fun failedNotificationIsTerminalWithoutRetry() = runBlocking {
        val failed = repository { posts++; throw IllegalStateException("synthetic failure") }
        (0..45 step 5).forEach { feed(it, failed) }
        assertThat(posts).isEqualTo(1)
        val episode = episodes().single()
        assertThat(db.alertDeliveryReceiptDao().byEpisodeAndKind(episode.episodeId, "INITIAL")?.result).isEqualTo("FAILED")
    }

    @Test fun cancelledUnknownNotificationIsNeverRetriedAfterRecreation() = runBlocking {
        (0..30 step 5).forEach { feed(it) }
        var cancelled = false
        try { feed(35, repository { posts++; throw CancellationException("synthetic cancellation") }) }
        catch (_: CancellationException) { cancelled = true }
        assertThat(cancelled).isTrue()
        feed(40)
        assertThat(posts).isEqualTo(1)
        val episode = episodes().single()
        assertThat(db.alertDeliveryReceiptDao().byEpisodeAndKind(episode.episodeId, "INITIAL")?.result).isEqualTo("CLAIMED")
    }

    @Test fun corruptStateFailsClosedInsteadOfResettingAnOpenEpisode() = runBlocking {
        (0..35 step 5).forEach { feed(it) }
        val state = requireNotNull(db.alertEventDao().byEpisodeId(DeliveryDiagnosticRepository.STATE_ID))
        db.alertEventDao().upsert(state.copy(localSnapshotJson = "{}"))
        var rejected = false
        try { feed(40) } catch (_: IllegalArgumentException) { rejected = true }
        assertThat(rejected).isTrue()
        assertThat(posts).isEqualTo(1)
    }

    @Test fun lowRiskCannotCreateADiagnosticNotification() = runBlocking {
        (0..45 step 5).forEach { minute ->
            now = BASE + minute * MINUTE
            repository().accept(now, sample(minute), true, true)
        }
        assertThat(posts).isEqualTo(0)
        assertThat(episodes()).isEmpty()
    }

    @Test fun roomFailureRollsBackEvidenceEpisodeAndReceiptBeforePosting() = runBlocking {
        (0..30 step 5).forEach { feed(it) }
        val before = db.alertEventDao().byEpisodeId(DeliveryDiagnosticRepository.STATE_ID)
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_diagnostic BEFORE INSERT ON alert_delivery_receipts " +
            "WHEN NEW.episodeId LIKE 'delivery-diagnostic-%' BEGIN SELECT RAISE(ABORT, 'synthetic'); END")
        var failed = false
        try { feed(35) } catch (_: android.database.SQLException) { failed = true }
        assertThat(failed).isTrue()
        assertThat(posts).isEqualTo(0)
        assertThat(episodes()).isEmpty()
        assertThat(db.alertEventDao().byEpisodeId(DeliveryDiagnosticRepository.STATE_ID)).isEqualTo(before)
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_diagnostic")
        feed(35)
        assertThat(posts).isEqualTo(1)
    }

    @Test fun globalOffWaitsForInFlightPostThenClearsItWithoutLateRepost() = runBlocking {
        (0..30 step 5).forEach { feed(it) }
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var visible = false
        val diagnostic = repository { entered.complete(Unit); release.await(); visible = true; posts++; AlertReceiptResult.DELIVERED }
        val posting = launch { feed(35, diagnostic) }
        entered.await()
        val off = launch {
            EpisodeAlertDeliveryStateMachine(RoomEpisodeAlertReceiptStore(db),
                clearRiskSideEffects = { visible = false }, clock = { now }).muteFor(now, 30 * MINUTE)
        }
        yield()
        assertThat(off.isCompleted).isFalse()
        release.complete(Unit)
        posting.join(); off.join()
        assertThat(visible).isFalse()
        feed(40)
        assertThat(posts).isEqualTo(1)
    }

    @Test fun lostStateCannotCreateASecondEpisodeWhileTheFirstRemainsOpen() = runBlocking {
        (0..35 step 5).forEach { feed(it) }
        db.openHelper.writableDatabase.execSQL("DELETE FROM alert_events WHERE episodeId = ?",
            arrayOf(DeliveryDiagnosticRepository.STATE_ID))
        var rejected = false
        try { feed(40) } catch (_: IllegalArgumentException) { rejected = true }
        assertThat(rejected).isTrue()
        assertThat(posts).isEqualTo(1)
    }

    @Test fun corruptStateCannotPreventMandatoryClearWhenDisabledOrLowRisk() = runBlocking {
        (0..35 step 5).forEach { feed(it) }
        val original = requireNotNull(db.alertEventDao().byEpisodeId(DeliveryDiagnosticRepository.STATE_ID))
        val corrupt = original.copy(localSnapshotJson = "{}")
        db.alertEventDao().upsert(corrupt)
        for ((enabled, lowRisk) in listOf(false to false, true to true)) {
            val beforeClear = clears
            now += MINUTE
            var rejected = false
            try { repository().accept(now, null, enabled, lowRisk) } catch (_: IllegalArgumentException) { rejected = true }
            assertThat(rejected).isTrue()
            assertThat(clears).isEqualTo(beforeClear + 1)
            assertThat(db.alertEventDao().byEpisodeId(DeliveryDiagnosticRepository.STATE_ID)).isEqualTo(corrupt)
        }
        assertThat(posts).isEqualTo(1)
    }

    @Test fun reusedForecastResetsEvidenceInsteadOfPoisoningFollowingValidSamples() = runBlocking {
        feed(0)
        now = BASE + 5 * MINUTE
        repository().accept(now, sample(5).copy(forecast30TargetTs = sample(0).forecast30TargetTs), true, false)
        (10..45 step 5).forEach { feed(it) }
        assertThat(posts).isEqualTo(1)
        assertThat(episodes()).hasSize(1)
    }

    @Test fun acceptedNormalFanOutPublishesButReadOnlyAndSourceChangesCannotWriteDiagnosticState() = runBlocking {
        for (intent in AutomationRepository.AutomationCycleIntent.entries) {
            db.clearAllTables(); posts = 0
            val policy = AutomationRepository.resolveCyclePolicyStatic(intent, true, false, false)
            (0..45 step 5).forEach { minute ->
                now = BASE + minute * MINUTE
                val sample = sample(minute)
                val sensitivity = io.aaps.copilot.testSensitivityRuntimeSnapshot(timestamp = now)
                val authority = AutomationRepository.AcceptedClinicalForecasts(
                    listOf(5, 30, 60).map { horizon -> io.aaps.copilot.domain.model.Forecast(
                        now + horizon * MINUTE, horizon, sample.forecast30Mmol,
                        sample.forecast30CiLow, sample.forecast30CiHigh, "diagnostic-test") }, now, "a".repeat(64))
                val uam = AutomationRepository.bindUnifiedUamToAcceptedForecastsStatic(
                    AutomationRepository.projectUnifiedUamRuntimeStatic(null, 40.0, false,
                        io.aaps.copilot.testSensitivityRuntimeContext(
                            consumer = io.aaps.copilot.domain.predict.SensitivityRuntimeConsumer.UAM,
                            snapshot = sensitivity)), authority)
                AutomationRepository.runAcceptedClinicalCalculationFanOutStatic<Unit>(
                    intent = intent, policy = policy, acceptedSnapshot = sensitivity,
                    acceptedClinicalForecastAuthority = authority, unifiedUam = uam,
                    writeUamCarbs = {}, evaluateRulesAndTargetManager = { _, _ -> emptyList() },
                    assessAlertCause = { _, _ -> }, publishAcceptedForecast = {},
                    publishAlerts = { _, _ -> repository().accept(now, sample, true, false) })
            }
            if (intent == AutomationRepository.AutomationCycleIntent.NORMAL) {
                assertThat(posts).isEqualTo(1)
                assertThat(episodes()).hasSize(1)
            } else {
                assertThat(posts).isEqualTo(0)
                assertThat(episodes()).isEmpty()
                assertThat(db.alertEventDao().byEpisodeId(DeliveryDiagnosticRepository.STATE_ID)).isNull()
            }
        }
    }

    companion object { const val BASE = 1_800_000_000_000L; const val MINUTE = 60_000L }
}
