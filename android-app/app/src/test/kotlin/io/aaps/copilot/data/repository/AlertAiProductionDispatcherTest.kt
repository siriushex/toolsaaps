package io.aaps.copilot.data.repository

import com.google.common.truth.Truth.assertThat
import io.aaps.copilot.config.ClinicalAiProviderConfig
import io.aaps.copilot.config.ClinicalAiProviderId
import io.aaps.copilot.config.OpenAiCompatibleProtocol
import io.aaps.copilot.data.local.dao.AlertAiAnalysisDao
import io.aaps.copilot.data.local.entity.AlertAiAnalysisEntity
import io.aaps.copilot.domain.alerts.AlertCauseDirection
import io.aaps.copilot.domain.alerts.AlertCauseSnapshotCodec
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AlertAiProductionDispatcherTest {
    @Test
    fun productionContextSourceChargesDailyEventsListsAndFinalDtoFromOneLedger() = runTest {
        val exactObservations = mutableListOf<AlertAiDatasetDerivedObservation>()
        val exactDataset = budgetedDataset(
            maxWorkItems = 28,
            observations = exactObservations,
            events = listOf(eventSummary("exact"))
        )

        val context = AlertAiProductionContextSource(
            AlertAiReportDatasetSource { exactDataset }
        ).build(trigger("exact-ledger"))

        assertThat(context.rowCount).isEqualTo(15)
        assertThat(exactObservations.count {
            it.source == AlertAiDatasetDerivedSourceName.ALERT_CONTEXT_DAILY_AGGREGATE
        }).isEqualTo(14)
        assertThat(exactObservations.count {
            it.source == AlertAiDatasetDerivedSourceName.ALERT_CONTEXT_CANONICAL_EVENT
        }).isEqualTo(1)
        assertThat(exactObservations.count {
            it.source == AlertAiDatasetDerivedSourceName.ALERT_CONTEXT_LIST
        }).isEqualTo(10)
        assertThat(exactObservations.last().source)
            .isEqualTo(AlertAiDatasetDerivedSourceName.ALERT_CANONICAL_CONTEXT)
        assertThat(exactObservations.last().totalAfterReservation).isEqualTo(28L)

        assertWorkOverflow(
            maxWorkItems = 11,
            events = emptyList(),
            expectedSource = AlertAiDatasetDerivedSourceName.ALERT_CONTEXT_DAILY_AGGREGATE,
            expectedAttemptedTotal = 12L
        )
        assertWorkOverflow(
            maxWorkItems = 26,
            events = listOf(eventSummary("event-limit")),
            expectedSource = AlertAiDatasetDerivedSourceName.ALERT_CONTEXT_CANONICAL_EVENT,
            expectedAttemptedTotal = 27L
        )
        assertWorkOverflow(
            maxWorkItems = 27,
            events = listOf(eventSummary("dto-limit")),
            expectedSource = AlertAiDatasetDerivedSourceName.ALERT_CANONICAL_CONTEXT,
            expectedAttemptedTotal = 28L
        )
    }

    @Test
    fun productionContextSourceUsesExactBoundedCanonicalByteSink() = runTest {
        val baseline = AlertAiProductionContextSource(
            datasetSource = AlertAiReportDatasetSource {
                budgetedDataset(maxWorkItems = 100, events = listOf(eventSummary("bytes")))
            }
        ).build(trigger("bytes-baseline"))
        val exactSize = baseline.canonicalBytes.size

        val exact = AlertAiProductionContextSource(
            datasetSource = AlertAiReportDatasetSource {
                budgetedDataset(maxWorkItems = 100, events = listOf(eventSummary("bytes")))
            },
            contextBuilder = AlertAiContextBuilder(maxBytes = exactSize)
        ).build(trigger("bytes-exact"))
        assertThat(exact.canonicalJson).isEqualTo(baseline.canonicalJson)
        assertThat(exact.sha256).isEqualTo(baseline.sha256)

        val writes = mutableListOf<AlertAiContextByteObservation>()
        val overflow = runCatching {
            AlertAiProductionContextSource(
                datasetSource = AlertAiReportDatasetSource {
                    budgetedDataset(maxWorkItems = 100, events = listOf(eventSummary("bytes")))
                },
                contextBuilder = AlertAiContextBuilder(
                    maxBytes = exactSize - 1,
                    byteProbe = AlertAiContextByteProbe(writes::add)
                )
            ).build(trigger("bytes-overflow"))
        }.exceptionOrNull()

        assertThat(overflow).isInstanceOf(AlertAiContextException.LimitExceeded::class.java)
        assertThat(writes).isNotEmpty()
        assertThat(writes.maxOf { it.retainedBytes }).isAtMost(exactSize - 1)
        assertThat(writes.last().attemptedBytes).isGreaterThan(exactSize - 1)
        assertThat(writes.last().retainedBytes).isAtMost(exactSize - 1)
    }

    @Test
    fun dispatchLaunchesExactlyOneCoroutineAndNeverBlocksAlertDelivery() = runTest {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var calls = 0
        val dispatcher = AlertAiProductionDispatcher(
            scope = this,
            runner = AlertAiAnalysisRunner {
                calls += 1
                started.complete(Unit)
                release.await()
            }
        )

        dispatcher.onInitialDelivered(delivery("non-blocking"))

        assertThat(started.isCompleted).isFalse()
        runCurrent()
        assertThat(started.isCompleted).isTrue()
        assertThat(calls).isEqualTo(1)
        release.complete(Unit)
    }

    @Test
    fun preflightFailuresSkipClaimAndNetworkSwitchTerminalizesClaimBeforeRequest() = runTest {
        val cases = listOf(
            GateCase(enabled = false, credential = "synthetic-key", networks = listOf(11L)),
            GateCase(enabled = true, credential = null, networks = listOf(11L)),
            GateCase(enabled = true, credential = "synthetic-key", networks = emptyList()),
            GateCase(enabled = true, credential = "synthetic-key", networks = listOf(11L, 22L))
        )

        cases.forEachIndexed { index, case ->
            val dao = FakeAlertAiDao()
            val datasetBuilds = AtomicInteger()
            val requests = AtomicInteger()
            val networks = ArrayDeque(
                case.networks.map { alertAiTestNetworkLease(it) }
            )
            val config = ClinicalAiProviderConfig.defaultOpenAi()
            val dispatcher = AlertAiProductionFactory.create(
                scope = this,
                settingsSource = AlertAiAnalysisSettingsSource {
                    AlertAiAnalysisSettings(case.enabled, config)
                },
                credentialSource = AlertAiCredentialSource { case.credential },
                networkGate = AlertAiNetworkGate {
                    networks.removeFirstOrNull()
                },
                datasetSource = AlertAiReportDatasetSource { nowTs ->
                    datasetBuilds.incrementAndGet()
                    emptyDataset(nowTs)
                },
                dao = dao,
                gatewayFactory = gatewayFactory(config, requests),
                clock = { REQUESTED_AT }
            )

            dispatcher.onInitialDelivered(delivery("gate-$index"))
            advanceUntilIdle()

            if (case.networks.size == 2) {
                assertThat(dao.rows.single().status)
                    .isEqualTo(AlertAiAnalysisStatus.FAILED.name)
                assertThat(dao.rows.single().sanitizedError)
                    .isEqualTo("EXECUTION_IDENTITY_CHANGED")
            } else {
                assertThat(dao.rows).isEmpty()
            }
            assertThat(requests.get()).isEqualTo(0)
            assertThat(datasetBuilds.get()).isEqualTo(if (case.networks.size == 2) 1 else 0)
        }
    }

    @Test
    fun configuredProvidersRouteExactSelectedProviderAndModelWithOneDatasetBuild() = runTest {
        val configs = listOf(
            ClinicalAiProviderConfig(ClinicalAiProviderId.OPENAI, "gpt-5.6-terra"),
            ClinicalAiProviderConfig(ClinicalAiProviderId.GEMINI, "gemini-3.6-flash"),
            ClinicalAiProviderConfig(ClinicalAiProviderId.ANTHROPIC, "claude-test-model"),
            ClinicalAiProviderConfig.normalized(
                providerId = ClinicalAiProviderId.OPENAI_COMPATIBLE,
                modelId = "compatible-test-model",
                endpoint = "http://127.0.0.1:18080/v1",
                compatibleProtocol = OpenAiCompatibleProtocol.RESPONSES
            )
        )

        configs.forEachIndexed { index, config ->
            val dao = FakeAlertAiDao()
            val datasetBuilds = AtomicInteger()
            val requestedIdentities = mutableListOf<Pair<ClinicalAiProviderId, String>>()
            val dispatcher = AlertAiProductionFactory.create(
                scope = this,
                settingsSource = AlertAiAnalysisSettingsSource {
                    AlertAiAnalysisSettings(true, config)
                },
                credentialSource = AlertAiCredentialSource { "synthetic-key" },
                networkGate = AlertAiNetworkGate {
                    alertAiTestNetworkLease(101L)
                },
                datasetSource = AlertAiReportDatasetSource { nowTs ->
                    datasetBuilds.incrementAndGet()
                    emptyDataset(nowTs)
                },
                dao = dao,
                gatewayFactory = ClinicalAiGatewayFactory(
                    mapOf(
                        config.providerId to ClinicalAiGatewayBuilder { selected ->
                            RecordingGateway(selected, requestedIdentities)
                        }
                    )
                ),
                clock = { REQUESTED_AT }
            )

            dispatcher.onInitialDelivered(delivery("provider-$index"))
            advanceUntilIdle()

            assertThat(datasetBuilds.get()).isEqualTo(1)
            assertThat(requestedIdentities)
                .containsExactly(config.providerId to config.modelId)
            assertThat(dao.rows.single().provider).isEqualTo(config.providerId.name)
            assertThat(dao.rows.single().model).isEqualTo(config.modelId)
            assertThat(dao.rows.single().status)
                .isEqualTo(AlertAiAnalysisStatus.COMPLETED.name)
        }
    }

    @Test
    fun productionFilesAddNoRetryWorkerOrTherapyRepositoryAccess() {
        val dispatcher = File(
            "src/main/kotlin/io/aaps/copilot/data/repository/AlertAiProductionDispatcher.kt"
        ).readText()
        assertThat(dispatcher.windowed("scope.launch".length).count { it == "scope.launch" })
            .isEqualTo(1)
        listOf("WorkManager", "Worker", "JobService", "PeriodicWorkRequest", "retry(")
            .forEach { forbidden -> assertThat(dispatcher).doesNotContain(forbidden) }
        listOf(
            "AutomationRepository",
            "TargetManagerRepository",
            "GlucoseCalibrationRepository",
            "NightscoutActionRepository"
        ).forEach { forbidden -> assertThat(dispatcher).doesNotContain(forbidden) }
    }

    private fun gatewayFactory(
        config: ClinicalAiProviderConfig,
        requests: AtomicInteger
    ) = ClinicalAiGatewayFactory(
        mapOf(
            config.providerId to ClinicalAiGatewayBuilder { selected ->
                object : ClinicalAiGateway {
                    override val providerId = selected.providerId
                    override val modelId = selected.modelId
                    override fun capabilities() = ClinicalAiCapabilities(1, 1, 1, true)
                    override suspend fun testConnection(credential: String) =
                        ClinicalAiConnectionTest(
                            providerId,
                            modelId,
                            ClinicalAiConnectionStatus.SUCCESS
                        )
                    override suspend fun analyze(
                        payload: ClinicalReportPayload,
                        credential: suspend () -> String,
                        progress: ClinicalOpenAiProgressCallback
                    ): ClinicalOpenAiResult = error("manual reports are not used")
                    override suspend fun analyzeAlert(
                        context: AlertAiCanonicalContext,
                        credential: suspend () -> String,
                        networkLease: AlertAiValidatedNetworkLease
                    ): AlertAiGatewayResult {
                        requests.incrementAndGet()
                        credential()
                        return AlertAiGatewayResult(providerId, modelId, VALID_RESULT)
                    }
                }
            }
        )
    )

    private class RecordingGateway(
        config: ClinicalAiProviderConfig,
        private val identities: MutableList<Pair<ClinicalAiProviderId, String>>
    ) : ClinicalAiGateway {
        override val providerId = config.providerId
        override val modelId = config.modelId
        override fun capabilities() = ClinicalAiCapabilities(1, 1, 1, true)
        override suspend fun testConnection(credential: String) = ClinicalAiConnectionTest(
            providerId,
            modelId,
            ClinicalAiConnectionStatus.SUCCESS
        )
        override suspend fun analyze(
            payload: ClinicalReportPayload,
            credential: suspend () -> String,
            progress: ClinicalOpenAiProgressCallback
        ): ClinicalOpenAiResult = error("manual reports are not used")
        override suspend fun analyzeAlert(
            context: AlertAiCanonicalContext,
            credential: suspend () -> String,
            networkLease: AlertAiValidatedNetworkLease
        ): AlertAiGatewayResult {
            identities += providerId to modelId
            credential()
            return AlertAiGatewayResult(providerId, modelId, VALID_RESULT)
        }
    }

    private class FakeAlertAiDao : AlertAiAnalysisDao {
        val rows = mutableListOf<AlertAiAnalysisEntity>()
        private val observed = MutableStateFlow<AlertAiAnalysisEntity?>(null)

        override suspend fun insert(entity: AlertAiAnalysisEntity): Long {
            if (rows.any { it.episodeId == entity.episodeId }) return -1L
            rows += entity
            observed.value = entity
            return 1L
        }

        override suspend fun update(entity: AlertAiAnalysisEntity): Int {
            val index = rows.indexOfFirst { it.analysisId == entity.analysisId }
            if (index < 0) return 0
            rows[index] = entity
            observed.value = entity
            return 1
        }

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
        ): Int {
            val current = rows.firstOrNull {
                it.analysisId == analysisId &&
                    it.episodeId == episodeId &&
                    it.provider == provider &&
                    it.model == model &&
                    it.requestHash == expectedRequestHash &&
                    it.status == AlertAiAnalysisStatus.RUNNING.name &&
                    it.resultJson == null &&
                    it.completedAt == null &&
                    it.sanitizedError == null
            } ?: return 0
            return update(
                current.copy(
                    requestHash = newRequestHash,
                    status = newStatus,
                    resultJson = newResultJson,
                    completedAt = newCompletedAt,
                    sanitizedError = newSanitizedError
                )
            )
        }

        override suspend fun byEpisodeId(episodeId: String) =
            rows.firstOrNull { it.episodeId == episodeId }

        override fun observeByEpisodeId(episodeId: String): Flow<AlertAiAnalysisEntity?> = observed

        override suspend fun byStatus(status: String) = rows.filter { it.status == status }
    }

    private fun delivery(episodeId: String) = InitialAlertDelivery(
        episodeId = episodeId,
        requestedAt = REQUESTED_AT,
        stage = "WARNING_30",
        direction = AlertCauseDirection.LOW,
        localCauseSnapshot = AlertCauseSnapshotCodec.failureSnapshot()
    )

    private fun trigger(episodeId: String) = AlertAiAnalysisTrigger(
        episodeId = episodeId,
        requestedAt = REQUESTED_AT,
        stage = "WARNING_30",
        direction = AlertCauseDirection.LOW,
        localCauseSnapshot = AlertCauseSnapshotCodec.failureSnapshot()
    )

    private fun emptyDataset(nowTs: Long): AlertAiContextDataset {
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

    private fun budgetedDataset(
        maxWorkItems: Int,
        observations: MutableList<AlertAiDatasetDerivedObservation> = mutableListOf(),
        events: List<ClinicalEventSummary> = emptyList()
    ): AlertAiContextDataset {
        val budget = AlertAiRetainedDerivedBudget(
            maxRows = maxWorkItems,
            probe = AlertAiDatasetDerivedProbe(observations::add)
        )
        budget.reserve(AlertAiDatasetDerivedSourceName.ALERT_CONTEXT_DATASET, 1)
        return AlertAiContextDataset(
            generatedAt = REQUESTED_AT,
            detail24h = ClinicalDetailWindow(
                fromTs = REQUESTED_AT - DAY_MS,
                throughTs = REQUESTED_AT,
                glucose = emptyList(),
                calibratedGlucose = emptyList(),
                therapy = emptyList(),
                targets = emptyList(),
                forecasts = emptyList(),
                telemetry = emptyList()
            ),
            glucose14d = emptyList(),
            therapy14d = emptyList(),
            events14d = events,
            retainedWorkBudget = budget
        )
    }

    private suspend fun assertWorkOverflow(
        maxWorkItems: Int,
        events: List<ClinicalEventSummary>,
        expectedSource: AlertAiDatasetDerivedSourceName,
        expectedAttemptedTotal: Long
    ) {
        val observations = mutableListOf<AlertAiDatasetDerivedObservation>()
        val dataset = budgetedDataset(maxWorkItems, observations, events)

        val overflow = runCatching {
            AlertAiProductionContextSource(
                AlertAiReportDatasetSource { dataset }
            ).build(trigger("overflow-$maxWorkItems"))
        }.exceptionOrNull()

        assertThat(overflow).isInstanceOf(AlertAiContextException.LimitExceeded::class.java)
        assertThat(observations.last().source).isEqualTo(expectedSource)
        assertThat(observations.last().totalAfterReservation).isEqualTo(expectedAttemptedTotal)
    }

    private fun eventSummary(id: String) = ClinicalEventSummary(
        localId = id,
        type = "ACTIVITY",
        subtype = "WALK",
        startTs = REQUESTED_AT - 60_000L,
        endTs = REQUESTED_AT - 1L,
        severity = "INFO",
        source = "USER",
        title = "Walk",
        note = "N".repeat(500),
        status = "COMPLETED",
        provenance = "LOCAL"
    )

    private data class GateCase(
        val enabled: Boolean,
        val credential: String?,
        val networks: List<Long>
    )

    private companion object {
        const val REQUESTED_AT = 20L * 24L * 60L * 60L * 1_000L
        const val DAY_MS = 24L * 60L * 60L * 1_000L
        const val VALID_RESULT =
            "{\"schemaVersion\":1,\"primaryCauseCode\":\"DATA_INCOMPLETE\",\"confidence\":\"LOW\",\"evidenceCodes\":[],\"adviceCode\":\"DATA_INCOMPLETE\"}"
    }
}
