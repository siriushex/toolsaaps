package io.aaps.copilot.data.repository

import io.aaps.copilot.config.AppSettings
import io.aaps.copilot.config.ClinicalAiConfigState
import io.aaps.copilot.data.local.dao.AlertAiAnalysisDao
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

fun resolveAlertAiAnalysisSettings(settings: AppSettings): AlertAiAnalysisSettings? {
    val config = when (val state = settings.clinicalAiConfigState) {
        is ClinicalAiConfigState.Valid -> state.config
        is ClinicalAiConfigState.UnconfiguredDefault -> state.config
        is ClinicalAiConfigState.Invalid -> return null
    }
    return AlertAiAnalysisSettings(
        enabled = settings.automaticEventAiAnalysisEnabled,
        config = config
    )
}

fun interface AlertAiReportDatasetSource {
    suspend fun build(requestedAt: Long): AlertAiContextDataset
}

class AlertAiProductionContextSource(
    private val datasetSource: AlertAiReportDatasetSource,
    private val contextBuilder: AlertAiContextBuilder = AlertAiContextBuilder()
) : AlertAiAnalysisContextSource {
    override suspend fun build(trigger: AlertAiAnalysisTrigger): AlertAiCanonicalContext {
        val dataset = datasetSource.build(trigger.requestedAt)
        if (dataset.retainedWorkBudget == null) throw AlertAiContextException.LimitExceeded()
        return contextBuilder.build(
            AlertAiContextRequest(
                nowTs = trigger.requestedAt,
                dataset = dataset,
                localCauseSnapshot = trigger.localCauseSnapshot,
                stage = trigger.stage,
                direction = trigger.direction
            )
        )
    }
}

fun interface AlertAiAnalysisRunner {
    suspend fun run(trigger: AlertAiAnalysisTrigger)
}

class AlertAiProductionDispatcher(
    private val scope: CoroutineScope,
    private val runner: AlertAiAnalysisRunner
) : EpisodeAlertPostCommitObserver {
    override fun onInitialDelivered(delivery: InitialAlertDelivery) {
        scope.launch {
            runner.run(
                AlertAiAnalysisTrigger(
                    episodeId = delivery.episodeId,
                    requestedAt = delivery.requestedAt,
                    stage = delivery.stage,
                    direction = delivery.direction,
                    localCauseSnapshot = delivery.localCauseSnapshot
                )
            )
        }
    }
}

object AlertAiProductionFactory {
    fun create(
        scope: CoroutineScope,
        settingsSource: AlertAiAnalysisSettingsSource,
        credentialSource: AlertAiCredentialSource,
        networkGate: AlertAiNetworkGate,
        datasetSource: AlertAiReportDatasetSource,
        dao: AlertAiAnalysisDao,
        gatewayFactory: ClinicalAiGatewayFactory,
        clock: () -> Long = System::currentTimeMillis
    ): AlertAiProductionDispatcher {
        val coordinator = AlertAiAnalysisCoordinator(
            settingsSource = settingsSource,
            credentialSource = credentialSource,
            networkGate = networkGate,
            contextSource = AlertAiProductionContextSource(datasetSource),
            dao = dao,
            gatewayFactory = gatewayFactory,
            analysisIdFactory = { episodeId -> "alert-ai-$episodeId" },
            clock = clock
        )
        return AlertAiProductionDispatcher(
            scope = scope,
            runner = AlertAiAnalysisRunner { trigger ->
                coordinator.run(trigger)
                Unit
            }
        )
    }
}
