package io.aaps.copilot.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.aaps.copilot.data.local.CopilotDatabase
import io.aaps.copilot.data.local.entity.AlertAiAnalysisEntity
import io.aaps.copilot.data.local.entity.AlertDeliveryReceiptEntity
import io.aaps.copilot.data.local.entity.AlertEventEntity
import io.aaps.copilot.domain.alerts.AlertCauseCode
import io.aaps.copilot.domain.alerts.CauseConfidence
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
class AlertsRepositoryRoomTest {
    private lateinit var db: CopilotDatabase

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            CopilotDatabase::class.java
        ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun historyUsesHalfOpenThirtyDayWindowAndStableNewestFirstGlucoseOnlyOrder() = runBlocking {
        val through = 40L * DAY_MS
        val from = through - 30L * DAY_MS
        insert("lower", "GLUCOSE_ALERT_LOW", from)
        insert("same-a", "GLUCOSE_ALERT_HIGH", through - 1L)
        insert("same-b", "GLUCOSE_ALERT_LOW", through - 1L)
        insert("upper", "GLUCOSE_ALERT_LOW", through)
        insert("mute", "GLUCOSE_ALERT_MUTE_STATE", through - 2L)
        insert("sequence", "GLUCOSE_ALERT_SEQUENCE_STATE", through - 3L)

        val history = AlertsRepository(db.alertEventDao(), db.alertAiAnalysisDao())
            .observeHistory(fromTs = from, throughTs = through)
            .first()

        assertThat(history.map { it.episodeId }).containsExactly("same-b", "same-a", "lower").inOrder()
    }

    @Test
    fun historyUsesCreatedAtAndExcludesOldCreatedEpisodeUpdatedInsideWindow() = runBlocking {
        val through = 40L * DAY_MS
        val from = through - 30L * DAY_MS
        insert("old-updated", "GLUCOSE_ALERT_LOW", updatedAt = through - 1L, createdAt = from - 1L)
        insert("inside", "GLUCOSE_ALERT_HIGH", updatedAt = from - 10L, createdAt = from)

        val history = AlertsRepository(db.alertEventDao(), db.alertAiAnalysisDao())
            .observeHistory(fromTs = from, throughTs = through)
            .first()

        assertThat(history.map { it.episodeId }).containsExactly("inside")
        Unit
    }

    @Test
    fun reopeningCapturesANewExactWindowAndNeverAdmitsFutureRows() {
        val firstNow = 40L * DAY_MS
        val secondNow = firstNow + DAY_MS

        val first = AlertsRepository.historyWindowAt(firstNow)
        val second = AlertsRepository.historyWindowAt(secondNow)

        assertThat(first.throughTsExclusive).isEqualTo(firstNow)
        assertThat(first.fromTsInclusive).isEqualTo(firstNow - 30L * DAY_MS)
        assertThat(second.fromTsInclusive - first.fromTsInclusive).isEqualTo(DAY_MS)
        assertThat(second.throughTsExclusive - first.throughTsExclusive).isEqualTo(DAY_MS)
    }

    @Test
    fun activeEpisodeIsSeparateAndSuppressedStateIsExplicit() = runBlocking {
        val now = 50L * DAY_MS
        insert(
            id = "active",
            type = "GLUCOSE_ALERT_LOW",
            updatedAt = now - 1L,
            status = "OPEN",
            suppressionUntil = null,
            lastNotificationAt = now - 10_000L
        )
        db.alertDeliveryReceiptDao().insert(
            AlertDeliveryReceiptEntity(
                receiptId = "active|SUPPRESSED_SNOOZE",
                episodeId = "active",
                kind = "SUPPRESSED_SNOOZE",
                attemptedAt = now - 1L,
                result = "SUPPRESSED",
                suppressionUntil = now + 60_000L,
                deliveredAt = null,
                sanitizedError = null
            )
        )
        insert("resolved", "GLUCOSE_ALERT_HIGH", now - 2L, status = "RESOLVED")
        val repository = AlertsRepository(db.alertEventDao(), db.alertAiAnalysisDao())

        val active = repository.observeActive().first()

        assertThat(active?.episodeId).isEqualTo("active")
        assertThat(active?.notShownOff).isTrue()
        assertThat(active?.suppressionUntil).isEqualTo(now + 60_000L)
    }

    @Test
    fun deliveredEpisodeWithLaterSuppressedReceiptIsNotGloballyMarkedNotShown() = runBlocking {
        val now = 55L * DAY_MS
        insert("mixed", "GLUCOSE_ALERT_LOW", now - 1L, status = "OPEN")
        db.alertDeliveryReceiptDao().insert(
            receipt("mixed", "INITIAL", "DELIVERED", now - 2_000L, deliveredAt = now - 2_000L)
        )
        db.alertDeliveryReceiptDao().insert(
            receipt(
                "mixed",
                "SUPPRESSED_SNOOZE",
                "SUPPRESSED",
                now - 1_000L,
                suppressionUntil = now + 60_000L
            )
        )

        val active = AlertsRepository(db.alertEventDao(), db.alertAiAnalysisDao())
            .observeActive().first()

        assertThat(active?.suppressionUntil).isEqualTo(now + 60_000L)
        assertThat(active?.notShownOff).isFalse()
    }

    @Test
    fun activeSelectionKeepsPreTaskCreatedAtSemantics() = runBlocking {
        insert("newer-created", "GLUCOSE_ALERT_LOW", updatedAt = 1_100L, createdAt = 1_000L, status = "OPEN")
        insert("older-recent-update", "GLUCOSE_ALERT_HIGH", updatedAt = 2_000L, createdAt = 900L, status = "OPEN")

        assertThat(db.alertEventDao().latestUnresolvedGlucoseEpisode()?.episodeId)
            .isEqualTo("newer-created")
        assertThat(AlertsRepository(db.alertEventDao(), db.alertAiAnalysisDao()).observeActive().first()?.episodeId)
            .isEqualTo("newer-created")
    }

    @Test
    fun corruptSnapshotAndAiAreBoundedAndLocalCauseComesFirst() = runBlocking {
        val now = 60L * DAY_MS
        insert(
            id = "episode",
            type = "GLUCOSE_ALERT_LOW",
            updatedAt = now,
            snapshot = "{broken",
            causeCode = "SENSOR_QUALITY",
            causeSummary = "arbitrary persisted free text must not be rendered"
        )
        db.alertAiAnalysisDao().insert(
            AlertAiAnalysisEntity(
                analysisId = "analysis",
                episodeId = "episode",
                provider = "provider",
                model = "model",
                requestHash = "hash",
                status = "COMPLETED",
                resultJson = "{also-broken",
                requestedAt = now,
                completedAt = now,
                sanitizedError = null
            )
        )
        val detail = AlertsRepository(db.alertEventDao(), db.alertAiAnalysisDao())
            .observeDetail("episode")
            .first()

        assertThat(detail?.localAdviceCode).isEqualTo("SENSOR_QUALITY")
        assertThat(detail.toString()).doesNotContain("arbitrary")
        assertThat(detail?.evidence).isEmpty()
        assertThat(detail?.aiPresentation).isNull()
    }

    @Test
    fun selectionAcceptsOnlyExistingPersistedGlucoseEpisodes() = runBlocking {
        insert("glucose-alert-valid", "GLUCOSE_ALERT_LOW", 1_000L)
        insert("glucose-alert-marker", "GLUCOSE_ALERT_SEQUENCE_STATE", 1_001L)
        val repository = AlertsRepository(db.alertEventDao(), db.alertAiAnalysisDao())

        assertThat(repository.validatedGlucoseEpisodeId("glucose-alert-valid"))
            .isEqualTo("glucose-alert-valid")
        assertThat(repository.validatedGlucoseEpisodeId("glucose-alert-marker")).isNull()
        assertThat(repository.validatedGlucoseEpisodeId("glucose-alert-missing")).isNull()
        assertThat(repository.validatedGlucoseEpisodeId("../unsafe")).isNull()
    }

    @Test
    fun completedPersistedAiWithUnknownCommandKeyIsRejectedFailClosed() = runBlocking {
        val now = 70L * DAY_MS
        insert(
            id = "glucose-alert-ai",
            type = "GLUCOSE_ALERT_HIGH",
            updatedAt = now,
            causeCode = "CIRCADIAN_PATTERN"
        )
        db.alertAiAnalysisDao().insert(
            AlertAiAnalysisEntity(
                analysisId = "saved-analysis",
                episodeId = "glucose-alert-ai",
                provider = "saved-provider",
                model = "saved-model",
                requestHash = "saved-hash",
                status = "COMPLETED",
                resultJson = "{\"summary\":\"Saved observation\",\"command\":\"must not render\"}",
                requestedAt = now,
                completedAt = now,
                sanitizedError = null
            )
        )

        val detail = AlertsRepository(db.alertEventDao(), db.alertAiAnalysisDao())
            .observeDetail("glucose-alert-ai")
            .first()

        assertThat(detail?.aiPresentation).isNull()
        assertThat(detail?.localAdviceCode).isEqualTo("CIRCADIAN_PATTERN")
    }

    @Test
    fun completedPersistedLegacyFreeFormAiIsPresentationIneligible() = runBlocking {
        val now = 71L * DAY_MS
        insert(
            id = "glucose-alert-benign-ai",
            type = "GLUCOSE_ALERT_HIGH",
            updatedAt = now,
            causeCode = "CIRCADIAN_PATTERN"
        )
        db.alertAiAnalysisDao().insert(
            AlertAiAnalysisEntity(
                analysisId = "benign-analysis",
                episodeId = "glucose-alert-benign-ai",
                provider = "saved-provider",
                model = "saved-model",
                requestHash = "saved-hash",
                status = "COMPLETED",
                resultJson = "{\"summary\":\"Observed a recurring morning rise.\"}",
                requestedAt = now,
                completedAt = now,
                sanitizedError = null
            )
        )

        val detail = AlertsRepository(db.alertEventDao(), db.alertAiAnalysisDao())
            .observeDetail("glucose-alert-benign-ai")
            .first()

        assertThat(detail?.aiPresentation).isNull()
    }

    @Test
    fun completedPersistedTypedAiMapsOnlyToTypedPresentation() = runBlocking {
        val now = 72L * DAY_MS
        insert(
            id = "glucose-alert-typed-ai",
            type = "GLUCOSE_ALERT_HIGH",
            updatedAt = now,
            causeCode = "CIRCADIAN_PATTERN"
        )
        db.alertAiAnalysisDao().insert(
            AlertAiAnalysisEntity(
                analysisId = "typed-analysis",
                episodeId = "glucose-alert-typed-ai",
                provider = "saved-provider",
                model = "saved-model",
                requestHash = "saved-hash",
                status = "COMPLETED",
                resultJson = typedAiJson(),
                requestedAt = now,
                completedAt = now,
                sanitizedError = null
            )
        )

        val detail = AlertsRepository(db.alertEventDao(), db.alertAiAnalysisDao())
            .observeDetail("glucose-alert-typed-ai")
            .first()

        assertThat(detail?.aiPresentation).isEqualTo(
            AlertAiPresentation(
                primaryCause = AlertCauseCode.SENSOR_QUALITY,
                confidence = CauseConfidence.MEDIUM,
                evidence = listOf(AlertEvidenceKind.SENSOR_QUALITY, AlertEvidenceKind.DATA_QUALITY),
                advice = AlertCauseCode.DATA_INCOMPLETE,
                canonicalEvidenceCodes = listOf("SENSOR_BLOCKED", "CURRENT_EVIDENCE_STALE")
            )
        )
        assertThat(detail.toString()).doesNotContain("SENSOR_BLOCKED")
        assertThat(detail.toString()).doesNotContain("CURRENT_EVIDENCE_STALE")
    }

    @Test
    fun typedAiRequiresCompletedPersistedStatus() = runBlocking {
        val now = 73L * DAY_MS
        insert("glucose-alert-pending-ai", "GLUCOSE_ALERT_LOW", now)
        db.alertAiAnalysisDao().insert(
            AlertAiAnalysisEntity(
                analysisId = "pending-analysis",
                episodeId = "glucose-alert-pending-ai",
                provider = "saved-provider",
                model = "saved-model",
                requestHash = "saved-hash",
                status = "PENDING",
                resultJson = typedAiJson(),
                requestedAt = now,
                completedAt = null,
                sanitizedError = null
            )
        )

        val detail = AlertsRepository(db.alertEventDao(), db.alertAiAnalysisDao())
            .observeDetail("glucose-alert-pending-ai")
            .first()

        assertThat(detail?.aiPresentation).isNull()
    }

    @Test
    fun snapshotMapsToTypedBoundedClinicalEvidenceWithoutRawTokens() = runBlocking {
        val now = 75L * DAY_MS
        val snapshot = """{
            "version":1,"nowTs":$now,"cycleTimestamp":${now - 500L},
            "cause":"SENSOR_QUALITY","confidence":"HIGH","factors":["SENSOR_QUALITY"],
            "evidence":["SENSOR_BLOCKED","CURRENT_EVIDENCE_STALE"],"identityStatus":"MATCHED",
            "glucose":{"currentMmol":6.6,"currentTimestamp":${now - 1_000L},"forecast5Mmol":6.2,
            "forecast30Mmol":5.4,"forecast60Mmol":4.8,"dataFresh":true}
        }""".trimIndent()
        insert("typed", "GLUCOSE_ALERT_LOW", now, snapshot = snapshot, causeCode = "SENSOR_QUALITY")

        val detail = AlertsRepository(db.alertEventDao(), db.alertAiAnalysisDao())
            .observeDetail("typed").first()!!

        assertThat(detail.evidence.map { it.kind }).containsExactly(
            AlertEvidenceKind.CURRENT_GLUCOSE,
            AlertEvidenceKind.FORECAST_5,
            AlertEvidenceKind.FORECAST_30,
            AlertEvidenceKind.FORECAST_60,
            AlertEvidenceKind.SENSOR_QUALITY,
            AlertEvidenceKind.DATA_QUALITY
        ).inOrder()
        assertThat(detail.evidence.first().glucoseMmol).isEqualTo(6.6)
        assertThat(detail.evidence.first().timestamp).isEqualTo(now - 1_000L)
        assertThat(detail.toString()).doesNotContain("SENSOR_BLOCKED")
        assertThat(detail.toString()).doesNotContain("currentMmol")
    }

    private suspend fun insert(
        id: String,
        type: String,
        updatedAt: Long,
        createdAt: Long = updatedAt,
        status: String = "RESOLVED",
        suppressionUntil: Long? = null,
        snapshot: String = "{}",
        causeCode: String? = null,
        causeSummary: String? = null,
        lastNotificationAt: Long? = null
    ) {
        db.alertEventDao().insert(
            AlertEventEntity(
                episodeId = id,
                eventType = type,
                stage = "WARNING_30",
                status = status,
                severity = "WARNING_30",
                createdAt = createdAt,
                updatedAt = updatedAt,
                resolvedAt = updatedAt.takeIf { status == "RESOLVED" },
                localSnapshotJson = snapshot,
                causeCode = causeCode,
                causeSummary = causeSummary,
                suppressionUntil = suppressionUntil,
                lastNotificationAt = lastNotificationAt,
                revision = 1L
            )
        )
    }

    private fun receipt(
        episodeId: String,
        kind: String,
        result: String,
        attemptedAt: Long,
        suppressionUntil: Long? = null,
        deliveredAt: Long? = null
    ) = AlertDeliveryReceiptEntity(
        receiptId = "$episodeId|$kind",
        episodeId = episodeId,
        kind = kind,
        attemptedAt = attemptedAt,
        result = result,
        suppressionUntil = suppressionUntil,
        deliveredAt = deliveredAt,
        sanitizedError = null
    )

    private fun typedAiJson(): String =
        """{"schemaVersion":1,"primaryCauseCode":"SENSOR_QUALITY","confidence":"MEDIUM","evidenceCodes":["SENSOR_BLOCKED","CURRENT_EVIDENCE_STALE"],"adviceCode":"DATA_INCOMPLETE"}"""

    private companion object {
        const val DAY_MS = 24L * 60L * 60L * 1_000L
    }
}
