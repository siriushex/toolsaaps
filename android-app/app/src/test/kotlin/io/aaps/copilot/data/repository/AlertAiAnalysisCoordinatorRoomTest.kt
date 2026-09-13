package io.aaps.copilot.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.aaps.copilot.config.ClinicalAiProviderConfig
import io.aaps.copilot.config.ClinicalAiProviderId
import io.aaps.copilot.data.local.CopilotDatabase
import io.aaps.copilot.data.local.dao.AlertAiAnalysisDao
import io.aaps.copilot.data.local.entity.AlertAiAnalysisEntity
import io.aaps.copilot.data.local.entity.AlertEventEntity
import io.aaps.copilot.domain.alerts.AlertCauseDirection
import io.aaps.copilot.domain.alerts.AlertCauseSnapshotCodec
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
class AlertAiAnalysisCoordinatorRoomTest {
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
    fun concurrentCoordinatorsUseRoomUniqueClaimForExactlyOneRequest() = runBlocking {
        val episodeId = "room-concurrent"
        insertAlertEvent(episodeId)
        val calls = AtomicInteger()
        val coordinator = coordinator(db.alertAiAnalysisDao(), calls)
        val trigger = trigger(episodeId)

        val outcomes = listOf(
            async(Dispatchers.Default) { coordinator.run(trigger) },
            async(Dispatchers.Default) { coordinator.run(trigger) }
        ).awaitAll()

        assertThat(outcomes).containsExactly(
            AlertAiAnalysisOutcome.COMPLETED,
            AlertAiAnalysisOutcome.ALREADY_CLAIMED
        )
        assertThat(calls.get()).isEqualTo(1)
        val stored = db.alertAiAnalysisDao().byEpisodeId(episodeId)
        assertThat(stored?.status).isEqualTo(AlertAiAnalysisStatus.COMPLETED.name)
        assertThat(stored?.resultJson).isEqualTo(VALID_ALERT_RESULT_JSON)
    }

    @Test
    fun cancelledRoomConflictCannotTerminalizeWinnerClaim() = runBlocking {
        val episodeId = "room-cancelled-conflict"
        insertAlertEvent(episodeId)
        val calls = AtomicInteger()
        val winnerEnteredGateway = CompletableDeferred<Unit>()
        val releaseWinner = CompletableDeferred<Unit>()
        val winner = coordinator(db.alertAiAnalysisDao(), calls) {
            winnerEnteredGateway.complete(Unit)
            releaseWinner.await()
        }
        val cancellingConflictDao = CancellingConflictDao(db.alertAiAnalysisDao())
        val loser = coordinator(cancellingConflictDao, calls)
        val trigger = trigger(episodeId)
        val winnerResult = async(Dispatchers.Default) { winner.run(trigger) }
        winnerEnteredGateway.await()

        val loserFailure = supervisorScope {
            val loserResult = async(Dispatchers.Default) { loser.run(trigger) }
            runCatching { loserResult.await() }.exceptionOrNull()
        }

        try {
            assertThat(loserFailure).isInstanceOf(CancellationException::class.java)
            val storedWinner = db.alertAiAnalysisDao().byEpisodeId(episodeId)
            assertThat(storedWinner?.status).isEqualTo(AlertAiAnalysisStatus.RUNNING.name)
            assertThat(cancellingConflictDao.conflictingAnalysisId)
                .isNotEqualTo(storedWinner?.analysisId)
            assertThat(storedWinner?.analysisId?.length).isAtMost(128)
            assertThat(storedWinner?.analysisId?.all(::safeIdChar)).isTrue()
            assertThat(cancellingConflictDao.conflictingAnalysisId?.all(::safeIdChar)).isTrue()
            assertThat(calls.get()).isEqualTo(1)
        } finally {
            releaseWinner.complete(Unit)
        }

        assertThat(winnerResult.await()).isEqualTo(AlertAiAnalysisOutcome.COMPLETED)
        assertThat(db.alertAiAnalysisDao().byEpisodeId(episodeId)?.status)
            .isEqualTo(AlertAiAnalysisStatus.COMPLETED.name)
        assertThat(calls.get()).isEqualTo(1)
    }

    private suspend fun insertAlertEvent(episodeId: String) {
        db.alertEventDao().insert(
            AlertEventEntity(
                episodeId = episodeId,
                eventType = "GLUCOSE_ALERT_LOW",
                stage = "LOW_PREDICTED_30",
                status = "OPEN",
                severity = "WARNING_30",
                createdAt = REQUESTED_AT,
                updatedAt = REQUESTED_AT,
                resolvedAt = null,
                localSnapshotJson = AlertCauseSnapshotCodec.failureSnapshot().canonicalJson,
                causeCode = "DATA_INCOMPLETE",
                causeSummary = null,
                suppressionUntil = null,
                lastNotificationAt = null,
                revision = 1L
            )
        )
    }

    private fun coordinator(
        dao: AlertAiAnalysisDao,
        calls: AtomicInteger,
        beforeResult: suspend () -> Unit = {}
    ): AlertAiAnalysisCoordinator {
        val config = ClinicalAiProviderConfig.defaultOpenAi()
        return AlertAiAnalysisCoordinator(
            settingsSource = AlertAiAnalysisSettingsSource {
                AlertAiAnalysisSettings(enabled = true, config = config)
            },
            credentialSource = AlertAiCredentialSource { "synthetic-credential" },
            networkGate = AlertAiNetworkGate { alertAiTestNetworkLease(101L) },
            contextSource = AlertAiAnalysisContextSource { alertContextFixture() },
            dao = dao,
            gatewayFactory = ClinicalAiGatewayFactory(
                mapOf(
                    ClinicalAiProviderId.OPENAI to ClinicalAiGatewayBuilder { selected ->
                        CountingGateway(selected, calls, beforeResult)
                    }
                )
            ),
            analysisIdFactory = { "analysis-$it" },
            clock = { REQUESTED_AT + 1_000L }
        )
    }

    private fun trigger(episodeId: String) = AlertAiAnalysisTrigger(
        episodeId = episodeId,
        requestedAt = REQUESTED_AT,
        stage = "LOW_PREDICTED_30",
        direction = AlertCauseDirection.LOW,
        localCauseSnapshot = AlertCauseSnapshotCodec.failureSnapshot()
    )

    private class CountingGateway(
        config: ClinicalAiProviderConfig,
        private val calls: AtomicInteger,
        private val beforeResult: suspend () -> Unit = {}
    ) : ClinicalAiGateway {
        override val providerId = config.providerId
        override val modelId = config.modelId

        override fun capabilities() = ClinicalAiCapabilities(262_144, 65_536, 1_024, true)

        override suspend fun testConnection(credential: String) = ClinicalAiConnectionTest(
            providerId,
            modelId,
            ClinicalAiConnectionStatus.SUCCESS
        )

        override suspend fun analyze(
            payload: ClinicalReportPayload,
            credential: suspend () -> String,
            progress: ClinicalOpenAiProgressCallback
        ): ClinicalOpenAiResult = throw UnsupportedOperationException()

        override suspend fun analyzeAlert(
            context: AlertAiCanonicalContext,
            credential: suspend () -> String,
            networkLease: AlertAiValidatedNetworkLease
        ): AlertAiGatewayResult {
            credential()
            calls.incrementAndGet()
            beforeResult()
            return AlertAiGatewayResult(providerId, modelId, VALID_ALERT_RESULT_JSON)
        }
    }

    private class CancellingConflictDao(
        private val delegate: AlertAiAnalysisDao
    ) : AlertAiAnalysisDao {
        var conflictingAnalysisId: String? = null
            private set

        override suspend fun insert(entity: AlertAiAnalysisEntity): Long {
            val result = delegate.insert(entity)
            if (result == -1L) {
                conflictingAnalysisId = entity.analysisId
                currentCoroutineContext().cancel(
                    CancellationException("cancelled after Room conflict")
                )
            }
            return result
        }

        override suspend fun update(entity: AlertAiAnalysisEntity) = delegate.update(entity)

        override suspend fun transitionOwnedRunning(
            analysisId: String,
            episodeId: String,
            provider: String,
            model: String,
            expectedRequestHash: String,
            newRequestHash: String,
            newStatus: String,
            newResultJson: String?,
            newCompletedAt: Long?,
            newSanitizedError: String?
        ) = delegate.transitionOwnedRunning(
            analysisId,
            episodeId,
            provider,
            model,
            expectedRequestHash,
            newRequestHash,
            newStatus,
            newResultJson,
            newCompletedAt,
            newSanitizedError
        )

        override suspend fun byEpisodeId(episodeId: String) = delegate.byEpisodeId(episodeId)

        override fun observeByEpisodeId(episodeId: String): Flow<AlertAiAnalysisEntity?> =
            delegate.observeByEpisodeId(episodeId)

        override suspend fun byStatus(status: String) = delegate.byStatus(status)
    }

    private fun safeIdChar(char: Char): Boolean =
        char == '_' || char == '-' || char.isLetterOrDigit()

    private companion object {
        const val REQUESTED_AT = ALERT_AI_CONTEXT_FIXTURE_NOW
    }
}
