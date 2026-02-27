package io.aaps.copilot.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.aaps.copilot.CopilotApp
import io.aaps.copilot.config.AppSettings
import io.aaps.copilot.data.local.entity.AuditLogEntity
import io.aaps.copilot.data.local.entity.BaselinePointEntity
import io.aaps.copilot.data.local.entity.ForecastEntity
import io.aaps.copilot.data.local.entity.GlucoseSampleEntity
import io.aaps.copilot.data.local.entity.PatternWindowEntity
import io.aaps.copilot.data.local.entity.ProfileEstimateEntity
import io.aaps.copilot.data.local.entity.RuleExecutionEntity
import io.aaps.copilot.data.local.entity.SyncStateEntity
import io.aaps.copilot.data.repository.CloudAnalysisHistoryUiModel
import io.aaps.copilot.data.repository.CloudAnalysisTrendUiModel
import io.aaps.copilot.data.repository.CloudJobsUiModel
import io.aaps.copilot.data.repository.CloudReplayUiModel
import io.aaps.copilot.data.repository.toDomain
import io.aaps.copilot.domain.model.DayType
import io.aaps.copilot.domain.model.PatternWindow
import io.aaps.copilot.domain.predict.BaselineComparator
import io.aaps.copilot.domain.predict.ForecastQualityEvaluator
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val container = (application as CopilotApp).container
    private val db = container.db

    private val messageState = MutableStateFlow<String?>(null)
    private val dryRunState = MutableStateFlow<DryRunUi?>(null)
    private val cloudReplayState = MutableStateFlow<CloudReplayUiModel?>(null)
    private val cloudJobsState = MutableStateFlow<CloudJobsUiModel?>(null)
    private val analysisHistoryState = MutableStateFlow<CloudAnalysisHistoryUiModel?>(null)
    private val analysisTrendState = MutableStateFlow<CloudAnalysisTrendUiModel?>(null)
    private val insightsFilterState = MutableStateFlow(InsightsFilterUi())
    private val qualityEvaluator = ForecastQualityEvaluator()
    private val baselineComparator = BaselineComparator()

    init {
        refreshCloudJobs(silent = true)
        refreshAnalysisInsights(silent = true)
    }

    val uiState: StateFlow<MainUiState> = combine(
        db.glucoseDao().observeLatest(limit = 720),
        db.forecastDao().observeLatest(limit = 600),
        db.baselineDao().observeLatest(limit = 600),
        db.ruleExecutionDao().observeLatest(limit = 20),
        db.auditLogDao().observeLatest(limit = 50),
        container.analyticsRepository.observePatterns(),
        container.analyticsRepository.observeProfileEstimate(),
        db.syncStateDao().observeAll(),
        container.settingsStore.settings,
        messageState,
        dryRunState,
        cloudReplayState,
        cloudJobsState,
        analysisHistoryState,
        analysisTrendState,
        insightsFilterState
    ) { values ->
        @Suppress("UNCHECKED_CAST")
        val glucose = values[0] as List<GlucoseSampleEntity>
        @Suppress("UNCHECKED_CAST")
        val forecasts = values[1] as List<ForecastEntity>
        @Suppress("UNCHECKED_CAST")
        val baseline = values[2] as List<BaselinePointEntity>
        @Suppress("UNCHECKED_CAST")
        val ruleExec = values[3] as List<RuleExecutionEntity>
        @Suppress("UNCHECKED_CAST")
        val audits = values[4] as List<AuditLogEntity>
        @Suppress("UNCHECKED_CAST")
        val patterns = values[5] as List<PatternWindowEntity>
        val profile = values[6] as ProfileEstimateEntity?
        @Suppress("UNCHECKED_CAST")
        val syncStates = values[7] as List<SyncStateEntity>
        val settings = values[8] as AppSettings
        val message = values[9] as String?
        val dryRun = values[10] as DryRunUi?
        val cloudReplay = values[11] as CloudReplayUiModel?
        val cloudJobs = values[12] as CloudJobsUiModel?
        val analysisHistory = values[13] as CloudAnalysisHistoryUiModel?
        val analysisTrend = values[14] as CloudAnalysisTrendUiModel?
        val insightsFilter = values[15] as InsightsFilterUi

        val sortedGlucose = glucose.sortedBy { it.timestamp }
        val latest = sortedGlucose.lastOrNull()
        val prev = sortedGlucose.dropLast(1).lastOrNull()
        val quality = qualityEvaluator.evaluate(forecasts, glucose)
        val baselineDeltas = baselineComparator.compare(
            forecasts = forecasts.map { it.toDomain() },
            baseline = baseline
        )
        val now = System.currentTimeMillis()
        val nightscoutSyncTs = syncStates.firstOrNull { it.source == "nightscout" }?.lastSyncedTimestamp
        val cloudPushSyncTs = syncStates.firstOrNull { it.source == "cloud_push" }?.lastSyncedTimestamp
        val latestGlucoseTs = latest?.timestamp
        val latestDataAgeMinutes = minutesSince(now, latestGlucoseTs)
        val nightscoutAgeMinutes = minutesSince(now, nightscoutSyncTs)
        val cloudPushBacklogMinutes = if (latestGlucoseTs != null && cloudPushSyncTs != null) {
            ((latestGlucoseTs - cloudPushSyncTs).coerceAtLeast(0L)) / 60_000L
        } else {
            null
        }
        val staleTag = if (latestDataAgeMinutes != null && latestDataAgeMinutes > settings.staleDataMaxMinutes) "STALE" else "OK"
        val lastSyncIssue = audits.firstOrNull {
            it.level != "INFO" && (
                it.message.contains("sync", ignoreCase = true) ||
                    it.message.contains("cloud_push", ignoreCase = true)
                )
        }?.let { "${it.level}: ${it.message}" }

        val syncStatusLines = listOf(
            "Data age: ${minutesLabel(latestDataAgeMinutes)} ($staleTag)",
            "Nightscout last sync: ${formatTs(nightscoutSyncTs)} (age ${minutesLabel(nightscoutAgeMinutes)})",
            "Cloud push last sync: ${formatTs(cloudPushSyncTs)} (backlog ${minutesLabel(cloudPushBacklogMinutes)})",
        ) + listOfNotNull(lastSyncIssue?.let { "Last sync issue: $it" })

        val jobStatusLines = if (cloudJobs == null) {
            emptyList()
        } else {
            val header = "Scheduler TZ: ${cloudJobs.timezone}"
            val items = cloudJobs.jobs.map { job ->
                "${job.jobId}: ${job.lastStatus ?: "NEVER"} | last=${formatTs(job.lastRunTs)} | next=${formatTs(job.nextRunTs)}" +
                    (job.lastMessage?.takeIf { it.isNotBlank() }?.let { " | msg=$it" } ?: "")
            }
            listOf(header) + items
        }
        val analysisHistoryLines = analysisHistory?.items?.map { item ->
            val counts = "an=${item.anomalies.size}, rec=${item.recommendations.size}"
            "${item.date} ${item.source} ${item.status} | ${formatTs(item.runTs)} | $counts"
        } ?: emptyList()
        val analysisTrendLines = analysisTrend?.items?.map { item ->
            "${item.weekStart}: runs=${item.totalRuns}, ok=${item.successRuns}, fail=${item.failedRuns}, an=${item.anomaliesCount}, rec=${item.recommendationsCount}"
        } ?: emptyList()
        val insightsFilterLabel = buildInsightsFilterLabel(insightsFilter)

        MainUiState(
            nightscoutUrl = settings.nightscoutUrl,
            cloudUrl = settings.cloudBaseUrl,
            exportUri = settings.exportFolderUri,
            killSwitch = settings.killSwitch,
            baseTargetMmol = settings.baseTargetMmol,
            latestGlucoseMmol = latest?.mmol,
            glucoseDelta = if (latest != null && prev != null) latest.mmol - prev.mmol else null,
            forecast5m = forecasts.firstOrNull { it.horizonMinutes == 5 }?.valueMmol,
            forecast60m = forecasts.firstOrNull { it.horizonMinutes == 60 }?.valueMmol,
            lastRuleState = ruleExec.firstOrNull()?.state,
            lastRuleId = ruleExec.firstOrNull()?.ruleId,
            profileIsf = profile?.isfMmolPerUnit,
            profileCr = profile?.crGramPerUnit,
            profileConfidence = profile?.confidence,
            rulePostHypoEnabled = settings.rulePostHypoEnabled,
            rulePatternEnabled = settings.rulePatternEnabled,
            rulePostHypoPriority = settings.rulePostHypoPriority,
            rulePatternPriority = settings.rulePatternPriority,
            maxActionsIn6Hours = settings.maxActionsIn6Hours,
            staleDataMaxMinutes = settings.staleDataMaxMinutes,
            weekdayHotHours = patterns
                .filter { it.dayType == DayType.WEEKDAY.name && (it.lowRate >= 0.12 || it.highRate >= 0.18) }
                .map {
                    PatternWindow(
                        dayType = DayType.WEEKDAY,
                        hour = it.hour,
                        lowRate = it.lowRate,
                        highRate = it.highRate,
                        recommendedTargetMmol = it.recommendedTargetMmol
                    )
                },
            weekendHotHours = patterns
                .filter { it.dayType == DayType.WEEKEND.name && (it.lowRate >= 0.12 || it.highRate >= 0.18) }
                .map {
                    PatternWindow(
                        dayType = DayType.WEEKEND,
                        hour = it.hour,
                        lowRate = it.lowRate,
                        highRate = it.highRate,
                        recommendedTargetMmol = it.recommendedTargetMmol
                    )
                },
            qualityMetrics = quality.map {
                QualityMetricUi(
                    horizonMinutes = it.horizonMinutes,
                    sampleCount = it.sampleCount,
                    mae = it.mae,
                    rmse = it.rmse,
                    mardPct = it.mardPct
                )
            },
            baselineDeltaLines = baselineDeltas.map {
                "${it.horizonMinutes}m ${it.algorithm}: ${if (it.deltaMmol >= 0) "+" else ""}${"%.2f".format(it.deltaMmol)} mmol/L"
            },
            syncStatusLines = syncStatusLines,
            jobStatusLines = jobStatusLines,
            insightsFilterLabel = insightsFilterLabel,
            analysisHistoryLines = analysisHistoryLines,
            analysisTrendLines = analysisTrendLines,
            dryRun = dryRun,
            cloudReplay = cloudReplay,
            auditLines = audits.map { "${it.level}: ${it.message}" },
            message = message
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = MainUiState()
    )

    fun saveConnections(nightscoutUrl: String, apiSecret: String, cloudUrl: String, exportUri: String?) {
        viewModelScope.launch {
            container.settingsStore.update {
                it.copy(
                    nightscoutUrl = nightscoutUrl,
                    apiSecret = apiSecret,
                    cloudBaseUrl = cloudUrl,
                    exportFolderUri = exportUri
                )
            }
            messageState.value = "Connection settings saved"
        }
    }

    fun setBaseTarget(mmol: Double) {
        viewModelScope.launch {
            container.settingsStore.update { it.copy(baseTargetMmol = mmol.coerceIn(4.4, 8.0)) }
            container.analyticsRepository.recalculate(mmol.coerceIn(4.4, 8.0))
            messageState.value = "Base target updated"
        }
    }

    fun setKillSwitch(enabled: Boolean) {
        viewModelScope.launch {
            container.settingsStore.update { it.copy(killSwitch = enabled) }
            messageState.value = if (enabled) "Kill switch enabled" else "Kill switch disabled"
        }
    }

    fun setRuleConfig(
        postHypoEnabled: Boolean,
        patternEnabled: Boolean,
        postHypoPriority: Int,
        patternPriority: Int
    ) {
        viewModelScope.launch {
            container.settingsStore.update {
                it.copy(
                    rulePostHypoEnabled = postHypoEnabled,
                    rulePatternEnabled = patternEnabled,
                    rulePostHypoPriority = postHypoPriority.coerceIn(0, 200),
                    rulePatternPriority = patternPriority.coerceIn(0, 200)
                )
            }
            messageState.value = "Rule config updated"
        }
    }

    fun setSafetyLimits(maxActionsIn6h: Int, staleDataMaxMinutes: Int) {
        viewModelScope.launch {
            container.settingsStore.update {
                it.copy(
                    maxActionsIn6Hours = maxActionsIn6h.coerceIn(1, 10),
                    staleDataMaxMinutes = staleDataMaxMinutes.coerceIn(5, 60)
                )
            }
            messageState.value = "Safety limits updated"
        }
    }

    fun runDryRun(days: Int = 14) {
        viewModelScope.launch {
            runCatching {
                container.automationRepository.runDryRunSimulation(days)
            }.onSuccess { report ->
                dryRunState.value = DryRunUi(
                    periodDays = report.periodDays,
                    samplePoints = report.samplePoints,
                    lines = report.rules.map {
                        "${it.ruleId}: TRG=${it.triggered}, BLK=${it.blocked}, NO=${it.noMatch}"
                    }
                )
                messageState.value = "Dry-run completed"
            }.onFailure {
                messageState.value = "Dry-run failed: ${it.message}"
            }
        }
    }

    fun runAutomationNow() {
        viewModelScope.launch {
            runCatching {
                container.automationRepository.runAutomationCycle()
                "Automation cycle completed"
            }.onFailure {
                messageState.value = "Automation failed: ${it.message}"
            }.onSuccess {
                messageState.value = it
                refreshCloudJobs(silent = true)
            }
        }
    }

    fun runDailyAnalysisNow() {
        viewModelScope.launch {
            val result = container.insightsRepository.runDailyAnalysis()
            messageState.value = result
            refreshCloudJobs(silent = true)
            refreshAnalysisInsights(silent = true)
        }
    }

    fun refreshCloudJobs(silent: Boolean = false) {
        viewModelScope.launch {
            runCatching {
                container.insightsRepository.fetchJobsStatus()
            }.onSuccess { jobs ->
                cloudJobsState.value = jobs
                if (!silent) {
                    messageState.value = "Cloud jobs status refreshed"
                }
            }.onFailure {
                if (!silent) {
                    messageState.value = "Cloud jobs status failed: ${it.message}"
                }
            }
        }
    }

    fun applyInsightsFilters(sourceRaw: String, statusRaw: String) {
        val source = normalizeFilter(sourceRaw)
        val status = normalizeFilter(statusRaw)?.uppercase()
        insightsFilterState.value = InsightsFilterUi(source = source, status = status)
        refreshAnalysisInsights(silent = false)
    }

    fun refreshAnalysisHistory(silent: Boolean = false) {
        refreshAnalysisInsights(silent)
    }

    private fun refreshAnalysisInsights(silent: Boolean) {
        viewModelScope.launch {
            val filter = insightsFilterState.value
            runCatching {
                container.insightsRepository.fetchAnalysisHistory(
                    limit = 30,
                    source = filter.source,
                    status = filter.status,
                    days = 60
                )
            }.onSuccess { history ->
                analysisHistoryState.value = history
                if (!silent) {
                    messageState.value = "Analysis history refreshed"
                }
            }.onFailure {
                if (!silent) {
                    messageState.value = "Analysis history failed: ${it.message}"
                }
            }

            runCatching {
                container.insightsRepository.fetchAnalysisTrend(
                    weeks = 8,
                    source = filter.source,
                    status = filter.status
                )
            }.onSuccess { trend ->
                analysisTrendState.value = trend
            }.onFailure {
                if (!silent) {
                    messageState.value = "Analysis trend failed: ${it.message}"
                }
            }
        }
    }

    fun runCloudReplayNow(days: Int = 14, stepMinutes: Int = 5) {
        viewModelScope.launch {
            runCatching {
                container.insightsRepository.runReplayReport(days, stepMinutes)
            }.onSuccess { report ->
                cloudReplayState.value = report
                messageState.value = "Cloud replay completed (${report.points} points)"
            }.onFailure {
                messageState.value = "Cloud replay failed: ${it.message}"
            }
        }
    }

    fun exportInsightsCsv() {
        viewModelScope.launch {
            val history = analysisHistoryState.value
            val trend = analysisTrendState.value
            if (history == null || trend == null) {
                messageState.value = "Refresh history first"
                return@launch
            }

            val filterLabel = buildInsightsFilterLabel(insightsFilterState.value)
            runCatching {
                container.insightsRepository.exportAnalysisCsv(history, trend, filterLabel)
            }.onSuccess { path ->
                messageState.value = "Insights CSV exported: $path"
            }.onFailure {
                messageState.value = "Insights CSV export failed: ${it.message}"
            }
        }
    }

    fun exportInsightsPdf() {
        viewModelScope.launch {
            val history = analysisHistoryState.value
            val trend = analysisTrendState.value
            if (history == null || trend == null) {
                messageState.value = "Refresh history first"
                return@launch
            }

            val filterLabel = buildInsightsFilterLabel(insightsFilterState.value)
            runCatching {
                container.insightsRepository.exportAnalysisPdf(history, trend, filterLabel)
            }.onSuccess { path ->
                messageState.value = "Insights PDF exported: $path"
            }.onFailure {
                messageState.value = "Insights PDF export failed: ${it.message}"
            }
        }
    }

    fun exportReplayCsv() {
        viewModelScope.launch {
            val report = cloudReplayState.value
            if (report == null) {
                messageState.value = "Run replay first"
                return@launch
            }
            runCatching {
                container.insightsRepository.exportReplayCsv(report)
            }.onSuccess { path ->
                messageState.value = "CSV exported: $path"
            }.onFailure {
                messageState.value = "CSV export failed: ${it.message}"
            }
        }
    }

    fun exportReplayPdf(horizonFilter: Int?) {
        viewModelScope.launch {
            val report = cloudReplayState.value
            if (report == null) {
                messageState.value = "Run replay first"
                return@launch
            }
            runCatching {
                container.insightsRepository.exportReplayPdf(report, horizonFilter)
            }.onSuccess { path ->
                messageState.value = "PDF exported: $path"
            }.onFailure {
                messageState.value = "PDF export failed: ${it.message}"
            }
        }
    }

    private fun minutesSince(now: Long, ts: Long?): Long? {
        if (ts == null) return null
        return ((now - ts).coerceAtLeast(0L)) / 60_000L
    }

    private fun minutesLabel(value: Long?): String = value?.let { "${it}m" } ?: "-"

    private fun formatTs(ts: Long?): String {
        if (ts == null) return "-"
        return Instant.ofEpochMilli(ts)
            .atZone(ZoneId.systemDefault())
            .format(DateTimeFormatter.ofPattern("MM-dd HH:mm"))
    }

    private fun normalizeFilter(value: String): String? {
        val normalized = value.trim().lowercase()
        if (normalized.isBlank() || normalized == "all") return null
        return normalized
    }

    private fun buildInsightsFilterLabel(filter: InsightsFilterUi): String =
        "Filters: source=${filter.source ?: "all"}, status=${filter.status ?: "all"}"
}

data class MainUiState(
    val nightscoutUrl: String = "",
    val cloudUrl: String = "",
    val exportUri: String? = null,
    val killSwitch: Boolean = false,
    val baseTargetMmol: Double = 5.5,
    val latestGlucoseMmol: Double? = null,
    val glucoseDelta: Double? = null,
    val forecast5m: Double? = null,
    val forecast60m: Double? = null,
    val lastRuleState: String? = null,
    val lastRuleId: String? = null,
    val profileIsf: Double? = null,
    val profileCr: Double? = null,
    val profileConfidence: Double? = null,
    val rulePostHypoEnabled: Boolean = true,
    val rulePatternEnabled: Boolean = true,
    val rulePostHypoPriority: Int = 100,
    val rulePatternPriority: Int = 50,
    val maxActionsIn6Hours: Int = 3,
    val staleDataMaxMinutes: Int = 10,
    val weekdayHotHours: List<PatternWindow> = emptyList(),
    val weekendHotHours: List<PatternWindow> = emptyList(),
    val qualityMetrics: List<QualityMetricUi> = emptyList(),
    val baselineDeltaLines: List<String> = emptyList(),
    val syncStatusLines: List<String> = emptyList(),
    val jobStatusLines: List<String> = emptyList(),
    val insightsFilterLabel: String = "Filters: source=all, status=all",
    val analysisHistoryLines: List<String> = emptyList(),
    val analysisTrendLines: List<String> = emptyList(),
    val dryRun: DryRunUi? = null,
    val cloudReplay: CloudReplayUiModel? = null,
    val auditLines: List<String> = emptyList(),
    val message: String? = null
)

data class QualityMetricUi(
    val horizonMinutes: Int,
    val sampleCount: Int,
    val mae: Double,
    val rmse: Double,
    val mardPct: Double
)

data class DryRunUi(
    val periodDays: Int,
    val samplePoints: Int,
    val lines: List<String>
)

data class InsightsFilterUi(
    val source: String? = null,
    val status: String? = null
)
