package io.aaps.copilot.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.aaps.copilot.config.ClinicalAiProviderConfig
import io.aaps.copilot.config.ClinicalAiProviderId
import io.aaps.copilot.data.local.CopilotDatabase
import io.aaps.copilot.domain.alerts.AlertCauseAnalysis
import io.aaps.copilot.domain.alerts.AlertCauseCode
import io.aaps.copilot.domain.alerts.AlertCauseSnapshotCodec
import io.aaps.copilot.domain.alerts.CauseConfidence
import java.util.Collections
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
class EpisodeAlertDeliveryRoomTest {

    private lateinit var db: CopilotDatabase

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, CopilotDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun technicalMonitorUsesAuthoritativeRoomMuteAndExactExpiry() = runBlocking {
        var now = 10_000L
        val owner = EpisodeAlertDeliveryStateMachine(RoomEpisodeAlertReceiptStore(db), clock = { now })
        val second = EpisodeAlertDeliveryStateMachine(RoomEpisodeAlertReceiptStore(db), clock = { now })
        owner.muteFor(now, GlucoseAlertMuteOption.MINUTES_30.durationMs)
        second.coordinateMutedSideEffect { assertThat(it).isTrue() }
        now += GlucoseAlertMuteOption.MINUTES_30.durationMs
        second.coordinateMutedSideEffect { assertThat(it).isFalse() }
    }

    @Test
    fun technicalCallbackHoldsTheActualSharedRoomOperationMutex() = runBlocking {
        machine().coordinateMutedSideEffect {
            val shared = EpisodeAlertOperationLocks.forIdentity(db)
            val acquired = shared.tryLock()
            if (acquired) shared.unlock()
            assertThat(acquired).isFalse()
        }
    }

    @Test
    fun technicalSideEffectAndMuteShareTheSameOperationLock() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val muteStarted = CompletableDeferred<Unit>()
        val first = machine()
        val second = machine()
        val delivery = async(Dispatchers.Default) {
            first.coordinateMutedSideEffect { entered.complete(Unit); release.await() }
        }
        entered.await()
        val mute = async(Dispatchers.Default) {
            muteStarted.complete(Unit)
            second.muteFor(10_000L, GlucoseAlertMuteOption.MINUTES_30.durationMs)
        }
        muteStarted.await()
        delay(100L)
        val completedBeforeRelease = mute.isCompleted
        release.complete(Unit)
        delivery.await()
        mute.await()
        assertThat(completedBeforeRelease).isFalse()
    }

    @Test
    fun twoCoordinatorsClaimOneInitialReceiptInRealRoom() = runBlocking {
        val attempts = AtomicInteger()
        val first = machine()
        val second = machine()
        val signal = low(nowTs = 10_000L)

        coroutineScope {
            listOf(first, second).map { coordinator ->
                async(Dispatchers.Default) {
                    coordinator.coordinate(signal) {
                        attempts.incrementAndGet()
                        AlertSideEffectResult.delivered()
                    }
                }
            }.awaitAll()
        }

        val episode = db.alertEventDao().latestUnresolvedGlucoseEpisode()!!
        val receipts = db.alertDeliveryReceiptDao().byEpisodeId(episode.episodeId)
        assertThat(attempts.get()).isEqualTo(1)
        assertThat(receipts.map { it.kind }).containsExactly(AlertDeliveryKind.INITIAL.name)
        assertThat(receipts.single().result).isEqualTo(AlertReceiptResult.DELIVERED.name)
    }

    @Test
    fun initialDeliveryDispatchesThroughProductionBoundaryToOneRealRoomAiClaim() = runBlocking {
        val config = ClinicalAiProviderConfig.defaultOpenAi()
        val requests = AtomicInteger()
        val gatewayCompleted = CompletableDeferred<Unit>()
        val dispatcher = AlertAiProductionFactory.create(
            scope = this,
            settingsSource = AlertAiAnalysisSettingsSource {
                AlertAiAnalysisSettings(enabled = true, config = config)
            },
            credentialSource = AlertAiCredentialSource { "synthetic-credential" },
            networkGate = AlertAiNetworkGate { alertAiTestNetworkLease(101L) },
            datasetSource = AlertAiReportDatasetSource(::emptyAlertDataset),
            dao = db.alertAiAnalysisDao(),
            gatewayFactory = ClinicalAiGatewayFactory(
                mapOf(
                    ClinicalAiProviderId.OPENAI to ClinicalAiGatewayBuilder { selected ->
                        object : ClinicalAiGateway {
                            override val providerId = selected.providerId
                            override val modelId = selected.modelId

                            override fun capabilities() =
                                ClinicalAiCapabilities(262_144, 65_536, 1_024, true)

                            override suspend fun testConnection(credential: String) =
                                error("connection test is not used")

                            override suspend fun analyze(
                                payload: ClinicalReportPayload,
                                credential: suspend () -> String,
                                progress: ClinicalOpenAiProgressCallback
                            ): ClinicalOpenAiResult = error("manual report is not used")

                            override suspend fun analyzeAlert(
                                context: AlertAiCanonicalContext,
                                credential: suspend () -> String,
                                networkLease: AlertAiValidatedNetworkLease
                            ): AlertAiGatewayResult {
                                credential()
                                requests.incrementAndGet()
                                gatewayCompleted.complete(Unit)
                                return AlertAiGatewayResult(
                                    providerId,
                                    modelId,
                                    VALID_ALERT_RESULT_JSON
                                )
                            }
                        }
                    }
                )
            ),
            clock = { ALERT_AI_CONTEXT_FIXTURE_NOW + 1L }
        )
        val machine = EpisodeAlertDeliveryStateMachine(
            store = RoomEpisodeAlertReceiptStore(db),
            clearRiskSideEffects = {},
            postCommitObserver = dispatcher
        )
        val signal = low(ALERT_AI_CONTEXT_FIXTURE_NOW).copy(
            causeAnalysis = AlertCauseAnalysis(
                primary = AlertCauseCode.DATA_INCOMPLETE,
                factors = listOf(AlertCauseCode.DATA_INCOMPLETE),
                confidence = CauseConfidence.LOW,
                evidenceCodes = listOf("CURRENT_EVIDENCE_MISSING"),
                shortAdvice = "untrusted test advice"
            ),
            causeSnapshot = AlertCauseSnapshotCodec.failureSnapshot()
        )

        val delivered = machine.coordinate(signal) { AlertSideEffectResult.delivered() }
        withTimeout(5_000L) { gatewayCompleted.await() }
        val duplicate = machine.coordinate(signal) { AlertSideEffectResult.delivered() }

        val episodeId = requireNotNull(delivered.episodeId)
        val receipt = db.alertDeliveryReceiptDao().byEpisodeId(episodeId).single()
        val analysis = db.alertAiAnalysisDao().byEpisodeId(episodeId)
        assertThat(receipt.result).isEqualTo(AlertReceiptResult.DELIVERED.name)
        assertThat(analysis?.status).isEqualTo(AlertAiAnalysisStatus.COMPLETED.name)
        assertThat(requests.get()).isEqualTo(1)
        assertThat(duplicate.sideEffectAttempted).isFalse()
    }

    @Test
    fun concurrentCoordinateMuteAndResumeStaySerialized() = runBlocking {
        val deliveryEntered = CompletableDeferred<Unit>()
        val releaseDelivery = CompletableDeferred<Unit>()
        val muteStarted = CompletableDeferred<Unit>()
        val resumeStarted = CompletableDeferred<Unit>()
        val completionOrder = Collections.synchronizedList(mutableListOf<String>())
        val first = machine()
        val second = machine()
        val third = machine()
        val delivery = async(Dispatchers.Default) {
            first.coordinate(low(nowTs = 20_000L)) {
                deliveryEntered.complete(Unit)
                releaseDelivery.await()
                AlertSideEffectResult.delivered()
            }
        }
        deliveryEntered.await()

        val mute = async(Dispatchers.Default) {
            muteStarted.complete(Unit)
            second.muteFor(20_001L, GlucoseAlertMuteOption.MINUTES_30.durationMs).also {
                completionOrder += "mute"
            }
        }
        val resume = async(Dispatchers.Default) {
            resumeStarted.complete(Unit)
            third.resume(20_002L)
            completionOrder += "resume"
        }
        muteStarted.await()
        resumeStarted.await()
        delay(100L)
        assertThat(mute.isCompleted).isFalse()
        assertThat(resume.isCompleted).isFalse()

        releaseDelivery.complete(Unit)
        delivery.await()
        val mutedUntil = mute.await()
        resume.await()
        val expectedFinalMute = if (completionOrder.last() == "resume") 0L else mutedUntil
        assertThat(mutedUntil).isEqualTo(20_001L + GlucoseAlertMuteOption.MINUTES_30.durationMs)
        assertThat(first.currentMutedUntil()).isEqualTo(expectedFinalMute)
        assertThat(db.alertDeliveryReceiptDao().byEpisodeId(
            db.alertEventDao().latestUnresolvedGlucoseEpisode()!!.episodeId
        )).hasSize(1)
    }

    @Test
    fun concurrentMuteUsesExactLongestWinnerThenResumeClears() = runBlocking {
        val first = machine()
        val second = machine()
        val nowTs = 30_000L

        coroutineScope {
            listOf(
                async(Dispatchers.Default) {
                    first.muteFor(nowTs, GlucoseAlertMuteOption.MINUTES_30.durationMs)
                },
                async(Dispatchers.Default) {
                    second.muteFor(nowTs, GlucoseAlertMuteOption.MINUTES_60.durationMs)
                }
            ).awaitAll()
        }

        assertThat(first.currentMutedUntil())
            .isEqualTo(nowTs + GlucoseAlertMuteOption.MINUTES_60.durationMs)
        second.resume(nowTs + 1L)
        assertThat(first.currentMutedUntil()).isEqualTo(0L)
    }

    @Test
    fun roomTransactionRollbackDoesNotPersistMute() = runBlocking {
        val store = RoomEpisodeAlertReceiptStore(db)
        var failed = false

        try {
            store.transaction {
                writeMuteUntil(99_000L, 40_000L)
                error("force rollback")
            }
        } catch (_: IllegalStateException) {
            failed = true
        }

        assertThat(failed).isTrue()
        assertThat(store.muteUntil()).isEqualTo(0L)
    }

    @Test
    fun restartRecoversStaleClaimWithoutSecondReceipt() = runBlocking {
        val first = machine()
        var crashed = false
        try {
            first.coordinate(low(nowTs = 50_000L)) { throw SimulatedRoomProcessDeath() }
        } catch (_: SimulatedRoomProcessDeath) {
            crashed = true
        }
        assertThat(crashed).isTrue()
        val episode = db.alertEventDao().latestUnresolvedGlucoseEpisode()!!
        val claimed = db.alertDeliveryReceiptDao().byEpisodeId(episode.episodeId).single()
        assertThat(claimed.result).isEqualTo(AlertReceiptResult.CLAIMED.name)

        var recoveredClaim: EpisodeDeliveryClaim? = null
        val restarted = machine().coordinate(
            low(nowTs = 50_000L + EpisodeAlertDeliveryStateMachine.CLAIM_LEASE_MS)
        ) { claim ->
            recoveredClaim = claim
            AlertSideEffectResult.delivered()
        }

        val receipts = db.alertDeliveryReceiptDao().byEpisodeId(episode.episodeId)
        assertThat(restarted.receiptResult).isEqualTo(AlertReceiptResult.DELIVERED)
        assertThat(receipts).hasSize(1)
        assertThat(receipts.single().result).isEqualTo(AlertReceiptResult.DELIVERED.name)
        assertThat(recoveredClaim?.notificationTag)
            .isEqualTo(EpisodeAlertDeliveryStateMachine.GLUCOSE_NOTIFICATION_TAG)
    }

    @Test
    fun fileBackedRoomCloseAndReopenRecoversOneReceiptAndGlobalSlot() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val databaseName = "episode-alert-restart-${UUID.randomUUID()}.db"
        var fileDb = Room.databaseBuilder(context, CopilotDatabase::class.java, databaseName)
            .allowMainThreadQueries()
            .build()
        try {
            val first = machine(fileDb)
            var processDied = false
            try {
                first.coordinate(low(nowTs = 60_000L)) { throw SimulatedRoomProcessDeath() }
            } catch (_: SimulatedRoomProcessDeath) {
                processDied = true
            }
            assertThat(processDied).isTrue()
            val episodeId = fileDb.alertEventDao().latestUnresolvedGlucoseEpisode()!!.episodeId
            assertThat(fileDb.alertDeliveryReceiptDao().byEpisodeId(episodeId).single().result)
                .isEqualTo(AlertReceiptResult.CLAIMED.name)

            fileDb.close()
            fileDb = Room.databaseBuilder(context, CopilotDatabase::class.java, databaseName)
                .allowMainThreadQueries()
                .build()
            var recoveredClaim: EpisodeDeliveryClaim? = null
            val recovered = machine(fileDb).coordinate(
                low(nowTs = 60_000L + EpisodeAlertDeliveryStateMachine.CLAIM_LEASE_MS)
            ) { claim ->
                recoveredClaim = claim
                AlertSideEffectResult.delivered()
            }

            val receipts = fileDb.alertDeliveryReceiptDao().byEpisodeId(episodeId)
            assertThat(recovered.receiptResult).isEqualTo(AlertReceiptResult.DELIVERED)
            assertThat(receipts).hasSize(1)
            assertThat(receipts.single().result).isEqualTo(AlertReceiptResult.DELIVERED.name)
            assertThat(recoveredClaim?.notificationTag)
                .isEqualTo(EpisodeAlertDeliveryStateMachine.GLUCOSE_NOTIFICATION_TAG)
            assertThat(recoveredClaim?.notificationId)
                .isEqualTo(EpisodeAlertDeliveryStateMachine.GLUCOSE_NOTIFICATION_ID)
        } finally {
            if (fileDb.isOpen) fileDb.close()
            context.deleteDatabase(databaseName)
        }
    }

    private fun machine(database: CopilotDatabase = db) = EpisodeAlertDeliveryStateMachine(
        store = RoomEpisodeAlertReceiptStore(database),
        clearRiskSideEffects = {}
    )

    private fun low(nowTs: Long) = EpisodeAlertSignal(
        stage = GlucoseAlertState.WARNING_30,
        direction = GlucoseAlertDirection.LOW,
        nowTs = nowTs
    )

    private fun emptyAlertDataset(nowTs: Long): AlertAiContextDataset {
        val budget = AlertAiRetainedDerivedBudget()
        budget.reserve(AlertAiDatasetDerivedSourceName.ALERT_CONTEXT_DATASET, 1)
        return AlertAiContextDataset(
            generatedAt = nowTs,
            detail24h = ClinicalDetailWindow(
                fromTs = nowTs - DAY_MS,
                throughTs = nowTs,
                glucose = emptyList(),
                calibratedGlucose = emptyList(),
                therapy = emptyList(),
                targets = emptyList(),
                forecasts = emptyList(),
                telemetry = emptyList()
            ),
            glucose14d = emptyList(),
            therapy14d = emptyList(),
            events14d = emptyList(),
            retainedWorkBudget = budget
        )
    }

    private companion object {
        const val DAY_MS = 24L * 60L * 60L * 1_000L
    }
}

private class SimulatedRoomProcessDeath : Error("simulated process death")
