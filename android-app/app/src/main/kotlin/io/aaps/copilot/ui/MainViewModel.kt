package io.aaps.copilot.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.aaps.copilot.CopilotApp
import io.aaps.copilot.config.AppSettings
import io.aaps.copilot.data.local.entity.ActionCommandEntity
import io.aaps.copilot.data.repository.AapsAutoConnectRepository
import io.aaps.copilot.data.local.entity.AuditLogEntity
import io.aaps.copilot.data.local.entity.BaselinePointEntity
import io.aaps.copilot.data.local.entity.ForecastEntity
import io.aaps.copilot.data.local.entity.GlucoseSampleEntity
import io.aaps.copilot.data.local.entity.PatternWindowEntity
import io.aaps.copilot.data.local.entity.ProfileEstimateEntity
import io.aaps.copilot.data.local.entity.ProfileSegmentEstimateEntity
import io.aaps.copilot.data.local.entity.RuleExecutionEntity
import io.aaps.copilot.data.local.entity.SyncStateEntity
import io.aaps.copilot.data.local.entity.TelemetrySampleEntity
import io.aaps.copilot.data.repository.CloudAnalysisHistoryUiModel
import io.aaps.copilot.data.repository.CloudAnalysisTrendUiModel
import io.aaps.copilot.data.repository.CloudJobsUiModel
import io.aaps.copilot.data.repository.CloudReplayUiModel
import io.aaps.copilot.data.repository.toDomain
import io.aaps.copilot.domain.model.ActionCommand
import io.aaps.copilot.domain.model.DayType
import io.aaps.copilot.domain.model.PatternWindow
import io.aaps.copilot.domain.model.SafetySnapshot
import io.aaps.copilot.domain.predict.BaselineComparator
import io.aaps.copilot.domain.predict.ForecastQualityEvaluator
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID
import org.json.JSONObject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
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
    private val autoConnectState = MutableStateFlow<AutoConnectUi?>(null)
    private val qualityEvaluator = ForecastQualityEvaluator()
    private val baselineComparator = BaselineComparator()

    init {
        runAutoConnectNow(silent = true)
        refreshCloudJobs(silent = true)
        refreshAnalysisInsights(silent = true)
    }

    val uiState: StateFlow<MainUiState> = combine(
        db.glucoseDao().observeLatest(limit = 720),
        db.forecastDao().observeLatest(limit = 600),
        db.baselineDao().observeLatest(limit = 600),
        db.ruleExecutionDao().observeLatest(limit = 20),
        db.actionCommandDao().observeLatest(limit = 40),
        db.auditLogDao().observeLatest(limit = 50),
        db.telemetryDao().observeLatest(limit = 1500),
        container.analyticsRepository.observePatterns(),
        container.analyticsRepository.observeProfileEstimate(),
        container.analyticsRepository.observeProfileSegments(),
        db.syncStateDao().observeAll(),
        container.settingsStore.settings,
        autoConnectState,
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
        val actionCommands = values[4] as List<ActionCommandEntity>
        @Suppress("UNCHECKED_CAST")
        val audits = values[5] as List<AuditLogEntity>
        @Suppress("UNCHECKED_CAST")
        val telemetry = values[6] as List<TelemetrySampleEntity>
        @Suppress("UNCHECKED_CAST")
        val patterns = values[7] as List<PatternWindowEntity>
        val profile = values[8] as ProfileEstimateEntity?
        @Suppress("UNCHECKED_CAST")
        val profileSegments = values[9] as List<ProfileSegmentEstimateEntity>
        @Suppress("UNCHECKED_CAST")
        val syncStates = values[10] as List<SyncStateEntity>
        val settings = values[11] as AppSettings
        val autoConnect = values[12] as AutoConnectUi?
        val message = values[13] as String?
        val dryRun = values[14] as DryRunUi?
        val cloudReplay = values[15] as CloudReplayUiModel?
        val cloudJobs = values[16] as CloudJobsUiModel?
        val analysisHistory = values[17] as CloudAnalysisHistoryUiModel?
        val analysisTrend = values[18] as CloudAnalysisTrendUiModel?
        val insightsFilter = values[19] as InsightsFilterUi

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

        val lastBroadcastIngest = audits.firstOrNull { it.message == "broadcast_ingest_completed" }
        val lastBroadcastSkip = audits.firstOrNull { it.message == "broadcast_ingest_skipped" }
        val transportStatusLines = buildList {
            add(
                "Inbound local broadcast: " + if (settings.localBroadcastIngestEnabled) {
                    "enabled"
                } else {
                    "disabled"
                }
            )
            add(
                "Strict sender validation: " + if (settings.strictBroadcastSenderValidation) {
                    "enabled"
                } else {
                    "disabled"
                }
            )
            add("Outbound temp target/carbs: Nightscout API")
            add(
                "Local fallback relay: " + if (settings.localCommandFallbackEnabled) {
                    "enabled (${settings.localCommandPackage} / ${settings.localCommandAction})"
                } else {
                    "disabled"
                }
            )
            add("Direct AAPS treatment broadcast: experimental fallback only (build-dependent)")
            lastBroadcastIngest?.let {
                add("Last broadcast ingest: ${formatTs(it.timestamp)}")
            }
            lastBroadcastSkip?.let {
                add("Last broadcast skip: ${formatTs(it.timestamp)} (${it.message})")
            }
        }

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
            val summaryPreview = item.summary.replace('\n', ' ').trim().take(120)
            val anomalyPreview = item.anomalies.firstOrNull()?.replace('\n', ' ')?.take(80)
            val recommendationPreview = item.recommendations.firstOrNull()?.replace('\n', ' ')?.take(80)
            buildList {
                add("${item.date} ${item.source} ${item.status} | ${formatTs(item.runTs)} | $counts")
                if (summaryPreview.isNotBlank()) add("  summary: $summaryPreview")
                if (!anomalyPreview.isNullOrBlank()) add("  top anomaly: $anomalyPreview")
                if (!recommendationPreview.isNullOrBlank()) add("  top rec: $recommendationPreview")
                if (!item.errorMessage.isNullOrBlank()) add("  error: ${item.errorMessage.take(120)}")
            }.joinToString(separator = "\n")
        } ?: emptyList()
        val analysisTrendLines = analysisTrend?.items?.map { item ->
            "${item.weekStart}: runs=${item.totalRuns}, ok=${item.successRuns}, fail=${item.failedRuns}, an=${item.anomaliesCount}, rec=${item.recommendationsCount}"
        } ?: emptyList()
        val ruleCooldownLines = buildRuleCooldownLines(ruleExec, settings, now)
        val telemetryCoverageLines = buildTelemetryCoverageLines(telemetry, now)
        val telemetryLines = buildTelemetryLines(telemetry)
        val actionLines = buildActionLines(actionCommands)
        val profileSegmentLines = profileSegments
            .sortedWith(compareBy<ProfileSegmentEstimateEntity> { it.dayType }.thenBy { it.timeSlot })
            .map { segment ->
                val isfText = segment.isfMmolPerUnit?.let { String.format("%.2f", it) } ?: "-"
                val crText = segment.crGramPerUnit?.let { String.format("%.2f", it) } ?: "-"
                "${segment.dayType} ${segment.timeSlot}: ISF=$isfText, CR=$crText, conf=${String.format("%.0f", segment.confidence * 100)}%, n(ISF/CR)=${segment.isfSampleCount}/${segment.crSampleCount}"
            }
        val insightsFilterLabel = buildInsightsFilterLabel(insightsFilter)

        MainUiState(
            nightscoutUrl = settings.nightscoutUrl,
            cloudUrl = settings.cloudBaseUrl,
            exportUri = settings.exportFolderUri,
            killSwitch = settings.killSwitch,
            localBroadcastIngestEnabled = settings.localBroadcastIngestEnabled,
            strictBroadcastSenderValidation = settings.strictBroadcastSenderValidation,
            localCommandFallbackEnabled = settings.localCommandFallbackEnabled,
            localCommandPackage = settings.localCommandPackage,
            localCommandAction = settings.localCommandAction,
            baseTargetMmol = settings.baseTargetMmol,
            postHypoThresholdMmol = settings.postHypoThresholdMmol,
            postHypoDeltaThresholdMmol5m = settings.postHypoDeltaThresholdMmol5m,
            postHypoTargetMmol = settings.postHypoTargetMmol,
            postHypoDurationMinutes = settings.postHypoDurationMinutes,
            postHypoLookbackMinutes = settings.postHypoLookbackMinutes,
            latestGlucoseMmol = latest?.mmol,
            glucoseDelta = if (latest != null && prev != null) latest.mmol - prev.mmol else null,
            forecast5m = forecasts.firstOrNull { it.horizonMinutes == 5 }?.valueMmol,
            forecast60m = forecasts.firstOrNull { it.horizonMinutes == 60 }?.valueMmol,
            lastRuleState = ruleExec.firstOrNull()?.state,
            lastRuleId = ruleExec.firstOrNull()?.ruleId,
            profileIsf = profile?.isfMmolPerUnit,
            profileCr = profile?.crGramPerUnit,
            profileConfidence = profile?.confidence,
            profileSamples = profile?.sampleCount,
            profileIsfSamples = profile?.isfSampleCount,
            profileCrSamples = profile?.crSampleCount,
            profileTelemetryIsfSamples = profile?.telemetryIsfSampleCount,
            profileTelemetryCrSamples = profile?.telemetryCrSampleCount,
            profileUamObservedCount = profile?.uamObservedCount,
            profileUamFilteredIsfSamples = profile?.uamFilteredIsfSamples,
            profileUamEpisodes = profile?.uamEpisodeCount,
            profileUamCarbsGrams = profile?.uamEstimatedCarbsGrams,
            profileUamRecentCarbsGrams = profile?.uamEstimatedRecentCarbsGrams,
            profileCalculatedIsf = profile?.calculatedIsfMmolPerUnit,
            profileCalculatedCr = profile?.calculatedCrGramPerUnit,
            profileCalculatedConfidence = profile?.calculatedConfidence,
            profileCalculatedSamples = profile?.calculatedSampleCount,
            profileCalculatedIsfSamples = profile?.calculatedIsfSampleCount,
            profileCalculatedCrSamples = profile?.calculatedCrSampleCount,
            profileLookbackDays = profile?.lookbackDays,
            profileSegmentLines = profileSegmentLines,
            rulePostHypoEnabled = settings.rulePostHypoEnabled,
            rulePatternEnabled = settings.rulePatternEnabled,
            ruleSegmentEnabled = settings.ruleSegmentEnabled,
            rulePostHypoPriority = settings.rulePostHypoPriority,
            rulePatternPriority = settings.rulePatternPriority,
            ruleSegmentPriority = settings.ruleSegmentPriority,
            rulePostHypoCooldownMinutes = settings.rulePostHypoCooldownMinutes,
            rulePatternCooldownMinutes = settings.rulePatternCooldownMinutes,
            ruleSegmentCooldownMinutes = settings.ruleSegmentCooldownMinutes,
            patternMinSamplesPerWindow = settings.patternMinSamplesPerWindow,
            patternMinActiveDaysPerWindow = settings.patternMinActiveDaysPerWindow,
            patternLowRateTrigger = settings.patternLowRateTrigger,
            patternHighRateTrigger = settings.patternHighRateTrigger,
            analyticsLookbackDays = settings.analyticsLookbackDays,
            maxActionsIn6Hours = settings.maxActionsIn6Hours,
            staleDataMaxMinutes = settings.staleDataMaxMinutes,
            weekdayHotHours = patterns
                .filter { it.dayType == DayType.WEEKDAY.name && it.isRiskWindow }
                .sortedBy { it.hour }
                .map {
                    PatternWindow(
                        dayType = DayType.WEEKDAY,
                        hour = it.hour,
                        sampleCount = it.sampleCount,
                        activeDays = it.activeDays,
                        lowRate = it.lowRate,
                        highRate = it.highRate,
                        recommendedTargetMmol = it.recommendedTargetMmol,
                        isRiskWindow = it.isRiskWindow
                    )
                },
            weekendHotHours = patterns
                .filter { it.dayType == DayType.WEEKEND.name && it.isRiskWindow }
                .sortedBy { it.hour }
                .map {
                    PatternWindow(
                        dayType = DayType.WEEKEND,
                        hour = it.hour,
                        sampleCount = it.sampleCount,
                        activeDays = it.activeDays,
                        lowRate = it.lowRate,
                        highRate = it.highRate,
                        recommendedTargetMmol = it.recommendedTargetMmol,
                        isRiskWindow = it.isRiskWindow
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
            telemetryCoverageLines = telemetryCoverageLines,
            telemetryLines = telemetryLines,
            actionLines = actionLines,
            autoConnectLines = autoConnect?.lines.orEmpty(),
            transportStatusLines = transportStatusLines,
            syncStatusLines = syncStatusLines,
            jobStatusLines = jobStatusLines,
            insightsFilterLabel = insightsFilterLabel,
            analysisHistoryLines = analysisHistoryLines,
            analysisTrendLines = analysisTrendLines,
            ruleCooldownLines = ruleCooldownLines,
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
            container.settingsStore.update { it.copy(baseTargetMmol = mmol.coerceIn(4.0, 10.0)) }
            val settings = container.settingsStore.settings.first()
            container.analyticsRepository.recalculate(settings)
            messageState.value = "Base target updated"
        }
    }

    fun setKillSwitch(enabled: Boolean) {
        viewModelScope.launch {
            container.settingsStore.update { it.copy(killSwitch = enabled) }
            messageState.value = if (enabled) "Kill switch enabled" else "Kill switch disabled"
        }
    }

    fun setLocalBroadcastIngestEnabled(enabled: Boolean) {
        viewModelScope.launch {
            container.settingsStore.update { it.copy(localBroadcastIngestEnabled = enabled) }
            messageState.value = if (enabled) {
                "Local broadcast ingest enabled"
            } else {
                "Local broadcast ingest disabled"
            }
        }
    }

    fun setStrictBroadcastValidation(enabled: Boolean) {
        viewModelScope.launch {
            container.settingsStore.update { it.copy(strictBroadcastSenderValidation = enabled) }
            messageState.value = if (enabled) {
                "Strict sender validation enabled"
            } else {
                "Strict sender validation disabled"
            }
        }
    }

    fun setLocalCommandFallbackConfig(enabled: Boolean, packageName: String, action: String) {
        viewModelScope.launch {
            val normalizedPackage = packageName.trim()
            val normalizedAction = action.trim()
            container.settingsStore.update {
                it.copy(
                    localCommandFallbackEnabled = enabled,
                    localCommandPackage = normalizedPackage.ifBlank { "info.nightscout.androidaps" },
                    localCommandAction = normalizedAction.ifBlank { "info.nightscout.client.NEW_TREATMENT" }
                )
            }
            messageState.value = if (enabled) {
                "Local command fallback enabled"
            } else {
                "Local command fallback disabled"
            }
        }
    }

    fun setRuleConfig(
        postHypoEnabled: Boolean,
        patternEnabled: Boolean,
        segmentEnabled: Boolean,
        postHypoPriority: Int,
        patternPriority: Int,
        segmentPriority: Int,
        postHypoCooldownMinutes: Int,
        patternCooldownMinutes: Int,
        segmentCooldownMinutes: Int
    ) {
        viewModelScope.launch {
            container.settingsStore.update {
                it.copy(
                    rulePostHypoEnabled = postHypoEnabled,
                    rulePatternEnabled = patternEnabled,
                    ruleSegmentEnabled = segmentEnabled,
                    rulePostHypoPriority = postHypoPriority.coerceIn(0, 200),
                    rulePatternPriority = patternPriority.coerceIn(0, 200),
                    ruleSegmentPriority = segmentPriority.coerceIn(0, 200),
                    rulePostHypoCooldownMinutes = postHypoCooldownMinutes.coerceIn(0, 240),
                    rulePatternCooldownMinutes = patternCooldownMinutes.coerceIn(0, 240),
                    ruleSegmentCooldownMinutes = segmentCooldownMinutes.coerceIn(0, 240)
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

    fun setPostHypoTuning(
        thresholdMmol: Double,
        deltaThresholdMmol5m: Double,
        targetMmol: Double,
        durationMinutes: Int,
        lookbackMinutes: Int
    ) {
        viewModelScope.launch {
            container.settingsStore.update {
                it.copy(
                    postHypoThresholdMmol = thresholdMmol.coerceIn(2.2, 4.8),
                    postHypoDeltaThresholdMmol5m = deltaThresholdMmol5m.coerceIn(0.05, 1.0),
                    postHypoTargetMmol = targetMmol.coerceIn(4.0, 10.0),
                    postHypoDurationMinutes = durationMinutes.coerceIn(15, 180),
                    postHypoLookbackMinutes = lookbackMinutes.coerceIn(30, 240)
                )
            }
            messageState.value = "Post-hypo rule tuning updated"
        }
    }

    fun setPatternTuning(
        minSamplesPerWindow: Int,
        minActiveDaysPerWindow: Int,
        lowRateTrigger: Double,
        highRateTrigger: Double,
        lookbackDays: Int
    ) {
        viewModelScope.launch {
            container.settingsStore.update {
                it.copy(
                    patternMinSamplesPerWindow = minSamplesPerWindow.coerceIn(10, 300),
                    patternMinActiveDaysPerWindow = minActiveDaysPerWindow.coerceIn(3, 60),
                    patternLowRateTrigger = lowRateTrigger.coerceIn(0.03, 0.60),
                    patternHighRateTrigger = highRateTrigger.coerceIn(0.05, 0.80),
                    analyticsLookbackDays = lookbackDays.coerceIn(30, 730)
                )
            }
            val settings = container.settingsStore.settings.first()
            container.analyticsRepository.recalculate(settings)
            messageState.value = "Pattern tuning updated"
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

    fun sendManualTempTarget(targetRaw: String, durationRaw: String, reasonRaw: String) {
        viewModelScope.launch {
            val targetMmol = parseFlexibleDouble(targetRaw)?.takeIf { it in 4.0..10.0 }
            val durationMinutes = durationRaw.trim().toIntOrNull()?.takeIf { it in 5..720 }
            if (targetMmol == null || durationMinutes == null) {
                messageState.value = "Manual temp target failed: invalid target/duration"
                return@launch
            }

            val reason = reasonRaw.trim().ifBlank { "manual_ui_temp_target" }
            val sent = runCatching {
                val command = buildManualCommand(
                    type = "temp_target",
                    params = mapOf(
                        "targetMmol" to String.format(Locale.US, "%.2f", targetMmol),
                        "durationMinutes" to durationMinutes.toString(),
                        "reason" to reason
                    )
                )
                container.actionRepository.submitTempTarget(command)
            }.getOrDefault(false)

            messageState.value = if (sent) {
                "Manual temp target sent (${String.format(Locale.US, "%.2f", targetMmol)} mmol/L, ${durationMinutes}m)"
            } else {
                "Manual temp target failed (check Action delivery / Audit Log)"
            }
        }
    }

    fun sendManualCarbs(carbsRaw: String, reasonRaw: String) {
        viewModelScope.launch {
            val carbsGrams = parseFlexibleDouble(carbsRaw)?.takeIf { it in 1.0..300.0 }
            if (carbsGrams == null) {
                messageState.value = "Manual carbs failed: invalid grams value"
                return@launch
            }

            val reason = reasonRaw.trim().ifBlank { "manual_ui_carbs" }
            val sent = runCatching {
                val command = buildManualCommand(
                    type = "carbs",
                    params = mapOf(
                        "carbsGrams" to String.format(Locale.US, "%.1f", carbsGrams),
                        "reason" to reason
                    )
                )
                container.actionRepository.submitCarbs(command)
            }.getOrDefault(false)

            messageState.value = if (sent) {
                "Manual carbs sent (${String.format(Locale.US, "%.1f", carbsGrams)} g)"
            } else {
                "Manual carbs failed (check Action delivery / Audit Log)"
            }
        }
    }

    fun runAutoConnectNow(silent: Boolean = false) {
        viewModelScope.launch {
            runCatching {
                val result = container.autoConnectRepository.bootstrap()
                if (result.rootEnabled) {
                    container.rootDbRepository.syncIfEnabled()
                }
                container.exportRepository.importBaselineFromExports()
                result
            }.onSuccess { result ->
                autoConnectState.value = AutoConnectUi(lines = buildAutoConnectLines(result))
                if (!silent) {
                    messageState.value = "Auto-connect scan complete"
                }
            }.onFailure {
                if (!silent) {
                    messageState.value = "Auto-connect failed: ${it.message}"
                }
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

    fun applyInsightsFilters(sourceRaw: String, statusRaw: String, daysRaw: String, weeksRaw: String) {
        val source = normalizeFilter(sourceRaw)
        val status = normalizeFilter(statusRaw)?.uppercase()
        val days = daysRaw.trim().toIntOrNull()?.coerceIn(1, 365) ?: 60
        val weeks = weeksRaw.trim().toIntOrNull()?.coerceIn(1, 52) ?: 8
        insightsFilterState.value = InsightsFilterUi(
            source = source,
            status = status,
            days = days,
            weeks = weeks
        )
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
                    days = filter.days
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
                    weeks = filter.weeks,
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
        "Filters: source=${filter.source ?: "all"}, status=${filter.status ?: "all"}, days=${filter.days}, weeks=${filter.weeks}"

    private fun buildAutoConnectLines(result: AapsAutoConnectRepository.BootstrapResult): List<String> {
        return buildList {
            add("Export path: ${result.exportPath ?: "-"}")
            add("Export connected: ${if (result.exportConnected) "yes" else "no"}")
            add("Nightscout configured: ${if (result.nightscoutConfigured) "yes" else "no"}")
            add("Nightscout source: ${result.nightscoutSource ?: "not detected"}")
            add("Root available: ${if (result.rootAvailable) "yes" else "no"}")
            add("Root DB detected: ${result.rootDbPath ?: "not detected"}")
            add("Root import enabled: ${if (result.rootEnabled) "yes" else "no"}")
            add("AAPS package: ${result.aapsPackage ?: "not installed"}")
            add("xDrip package: ${result.xdripPackage ?: "not installed"}")
            add("All-files access: ${if (result.hasAllFilesAccess) "granted" else "missing"}")
        }
    }

    private suspend fun buildManualCommand(
        type: String,
        params: Map<String, String>
    ): ActionCommand {
        val settings = container.settingsStore.settings.first()
        val nowTs = System.currentTimeMillis()
        val latestGlucoseTs = db.glucoseDao().maxTimestamp()
        val dataFresh = if (latestGlucoseTs == null) {
            false
        } else {
            nowTs - latestGlucoseTs <= settings.staleDataMaxMinutes * 60_000L
        }
        val actionsLast6h = container.actionRepository.countSentActionsLast6h()
        return ActionCommand(
            id = UUID.randomUUID().toString(),
            type = type,
            params = params,
            safetySnapshot = SafetySnapshot(
                killSwitch = settings.killSwitch,
                dataFresh = dataFresh,
                activeTempTargetMmol = null,
                actionsLast6h = actionsLast6h
            ),
            idempotencyKey = "manual:$type:$nowTs:${UUID.randomUUID()}"
        )
    }

    private fun parseFlexibleDouble(raw: String): Double? {
        val normalized = raw.trim().replace(',', '.')
        return normalized.toDoubleOrNull()
    }

    private fun buildActionLines(commands: List<ActionCommandEntity>): List<String> {
        if (commands.isEmpty()) return listOf("No auto-actions yet")
        return commands.take(12).map { command ->
            val channel = payloadField(command.payloadJson, "deliveryChannel") ?: "nightscout"
            val target = payloadField(command.payloadJson, "targetMmol")
            val duration = payloadField(command.payloadJson, "durationMinutes")
            val carbs = payloadField(command.payloadJson, "carbsGrams")
                ?: payloadField(command.payloadJson, "carbs")
                ?: payloadField(command.payloadJson, "grams")
            val details = when {
                target != null && duration != null -> "target=$target mmol/L, duration=${duration}m"
                carbs != null -> "carbs=${carbs}g"
                target != null -> "target=$target mmol/L"
                else -> command.payloadJson.take(80)
            }
            "${command.status} ${command.type}: $details, channel=$channel (${formatTs(command.timestamp)})"
        }
    }

    private fun payloadField(payloadJson: String, key: String): String? {
        return runCatching {
            JSONObject(payloadJson).optString(key).takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    private fun buildRuleCooldownLines(
        executions: List<RuleExecutionEntity>,
        settings: AppSettings,
        nowTs: Long
    ): List<String> {
        val rules = listOf(
            Triple("PostHypoReboundGuard.v1", "PostHypo", settings.rulePostHypoCooldownMinutes),
            Triple("PatternAdaptiveTarget.v1", "Pattern", settings.rulePatternCooldownMinutes),
            Triple("SegmentProfileGuard.v1", "Segment", settings.ruleSegmentCooldownMinutes)
        )
        return rules.map { (ruleId, title, cooldownMinutes) ->
            if (cooldownMinutes <= 0) {
                return@map "$title: cooldown off"
            }
            val lastTriggeredTs = executions.firstOrNull {
                it.ruleId == ruleId && it.state == "TRIGGERED"
            }?.timestamp
            if (lastTriggeredTs == null) {
                return@map "$title: ready"
            }
            val remainingMs = cooldownMinutes * 60_000L - (nowTs - lastTriggeredTs)
            if (remainingMs <= 0) {
                "$title: ready"
            } else {
                val remainingMin = ((remainingMs + 59_999L) / 60_000L).coerceAtLeast(1L)
                "$title: ${remainingMin}m left (last ${formatTs(lastTriggeredTs)})"
            }
        }
    }

    private data class TelemetryCoverageSpec(
        val primaryKey: String,
        val label: String,
        val staleThresholdMin: Long,
        val exactAliases: List<String> = emptyList(),
        val tokenAliases: List<String> = emptyList()
    )

    private fun buildTelemetryCoverageLines(
        samples: List<TelemetrySampleEntity>,
        nowTs: Long
    ): List<String> {
        if (samples.isEmpty()) {
            return listOf("No telemetry received yet")
        }
        val latestByKey = latestTelemetryByKey(samples)
        val required = listOf(
            TelemetryCoverageSpec(primaryKey = "iob_units", label = "IOB", staleThresholdMin = 30L),
            TelemetryCoverageSpec(primaryKey = "cob_grams", label = "COB", staleThresholdMin = 30L),
            TelemetryCoverageSpec(primaryKey = "carbs_grams", label = "Carbs", staleThresholdMin = 240L),
            TelemetryCoverageSpec(primaryKey = "insulin_units", label = "Insulin", staleThresholdMin = 240L),
            TelemetryCoverageSpec(primaryKey = "dia_hours", label = "DIA", staleThresholdMin = 24 * 60L),
            TelemetryCoverageSpec(primaryKey = "steps_count", label = "Steps", staleThresholdMin = 24 * 60L),
            TelemetryCoverageSpec(primaryKey = "activity_ratio", label = "Activity ratio", staleThresholdMin = 180L),
            TelemetryCoverageSpec(primaryKey = "heart_rate_bpm", label = "Heart rate", staleThresholdMin = 180L),
            TelemetryCoverageSpec(primaryKey = "uam_value", label = "UAM", staleThresholdMin = 180L, tokenAliases = listOf("uam")),
            TelemetryCoverageSpec(
                primaryKey = "isf_value",
                label = "ISF",
                staleThresholdMin = 7 * 24 * 60L,
                tokenAliases = listOf("isf", "sens", "sensitivity")
            ),
            TelemetryCoverageSpec(
                primaryKey = "cr_value",
                label = "CR",
                staleThresholdMin = 7 * 24 * 60L,
                tokenAliases = listOf("cr", "carb_ratio", "carbratio", "icratio")
            )
        )
        return required.map { spec ->
            val sample = resolveTelemetrySample(spec, latestByKey)
            if (sample == null) {
                "${spec.label}: MISSING"
            } else {
                val ageMin = ((nowTs - sample.timestamp).coerceAtLeast(0L)) / 60_000L
                val freshness = if (ageMin <= spec.staleThresholdMin) "fresh" else "stale ${ageMin}m"
                "${spec.label}: ${formatTelemetryLine(sample)} [$freshness]"
            }
        }
    }

    private fun buildTelemetryLines(samples: List<TelemetrySampleEntity>): List<String> {
        if (samples.isEmpty()) return emptyList()
        val latestByKey = latestTelemetryByKey(samples)
        val primaryKeys = listOf(
            "iob_units",
            "cob_grams",
            "carbs_grams",
            "insulin_units",
            "dia_hours",
            "steps_count",
            "activity_ratio",
            "activity_label",
            "heart_rate_bpm",
            "uam_value",
            "isf_value",
            "cr_value",
            "basal_rate_u_h",
            "insulin_req_units",
            "temp_target_low_mmol",
            "temp_target_high_mmol",
            "temp_target_duration_min",
            "profile_percent"
        )
        val syntheticPrimary = listOf(
            TelemetryCoverageSpec(
                primaryKey = "isf_value",
                label = "ISF",
                staleThresholdMin = 7 * 24 * 60L,
                tokenAliases = listOf("isf", "sens", "sensitivity")
            ),
            TelemetryCoverageSpec(
                primaryKey = "cr_value",
                label = "CR",
                staleThresholdMin = 7 * 24 * 60L,
                tokenAliases = listOf("cr", "carb_ratio", "carbratio", "icratio")
            )
        )
        val resolvedSyntheticKeys = mutableSetOf<String>()
        val primaryLines = primaryKeys.mapNotNull { key ->
            latestByKey[key]?.let { sample -> formatTelemetryLine(sample) }
        } + syntheticPrimary.mapNotNull { spec ->
            val sample = resolveTelemetrySample(spec, latestByKey) ?: return@mapNotNull null
            resolvedSyntheticKeys += sample.key
            if (sample.key in primaryKeys) return@mapNotNull null
            formatTelemetryLine(sample)
        }
        val otherLines = latestByKey
            .filterKeys { it !in primaryKeys && it !in resolvedSyntheticKeys }
            .values
            .sortedBy { it.key }
            .map { sample -> formatTelemetryLine(sample) }
        return primaryLines + otherLines
    }

    private fun latestTelemetryByKey(samples: List<TelemetrySampleEntity>): Map<String, TelemetrySampleEntity> {
        return samples
            .groupBy { it.key }
            .mapValues { (_, values) -> values.maxByOrNull { it.timestamp } }
            .filterValues { it != null }
            .mapValues { it.value!! }
    }

    private fun resolveTelemetrySample(
        spec: TelemetryCoverageSpec,
        latestByKey: Map<String, TelemetrySampleEntity>
    ): TelemetrySampleEntity? {
        val exactKeys = listOf(spec.primaryKey) + spec.exactAliases
        exactKeys.forEach { key ->
            latestByKey[key]?.let { return it }
        }
        if (spec.tokenAliases.isEmpty()) return null
        return latestByKey.values
            .asSequence()
            .filter { sample ->
                spec.tokenAliases.any { alias -> keyContainsAliasToken(sample.key, alias) }
            }
            .maxByOrNull { it.timestamp }
    }

    private fun keyContainsAliasToken(key: String, alias: String): Boolean {
        val normalizedAlias = normalizeTelemetryKey(alias)
        if (normalizedAlias.isBlank()) return false
        val normalizedKey = normalizeTelemetryKey(key)
        if (normalizedKey == normalizedAlias || normalizedKey.endsWith("_$normalizedAlias")) return true
        val parts = normalizedKey.split('_').filter { it.isNotBlank() }
        return parts.contains(normalizedAlias)
    }

    private fun normalizeTelemetryKey(value: String): String {
        return value
            .replace(Regex("([a-z0-9])([A-Z])"), "$1_$2")
            .lowercase(Locale.US)
            .replace(Regex("[^a-z0-9]+"), "_")
            .trim('_')
    }

    private fun formatTelemetryLine(sample: TelemetrySampleEntity): String {
        val title = sample.key.replace('_', ' ')
        val value = sample.valueDouble?.let {
            if (sample.unit == "steps") String.format("%.0f", it) else String.format("%.2f", it)
        } ?: sample.valueText.orEmpty()
        val unit = sample.unit?.takeIf { it.isNotBlank() }?.let { " $it" }.orEmpty()
        return "$title: $value$unit (${sample.source}, ${formatTs(sample.timestamp)})"
    }

}

data class MainUiState(
    val nightscoutUrl: String = "",
    val cloudUrl: String = "",
    val exportUri: String? = null,
    val killSwitch: Boolean = false,
    val localBroadcastIngestEnabled: Boolean = true,
    val strictBroadcastSenderValidation: Boolean = false,
    val localCommandFallbackEnabled: Boolean = false,
    val localCommandPackage: String = "info.nightscout.androidaps",
    val localCommandAction: String = "info.nightscout.client.NEW_TREATMENT",
    val baseTargetMmol: Double = 5.5,
    val postHypoThresholdMmol: Double = 3.0,
    val postHypoDeltaThresholdMmol5m: Double = 0.20,
    val postHypoTargetMmol: Double = 4.4,
    val postHypoDurationMinutes: Int = 60,
    val postHypoLookbackMinutes: Int = 90,
    val latestGlucoseMmol: Double? = null,
    val glucoseDelta: Double? = null,
    val forecast5m: Double? = null,
    val forecast60m: Double? = null,
    val lastRuleState: String? = null,
    val lastRuleId: String? = null,
    val profileIsf: Double? = null,
    val profileCr: Double? = null,
    val profileConfidence: Double? = null,
    val profileSamples: Int? = null,
    val profileIsfSamples: Int? = null,
    val profileCrSamples: Int? = null,
    val profileTelemetryIsfSamples: Int? = null,
    val profileTelemetryCrSamples: Int? = null,
    val profileUamObservedCount: Int? = null,
    val profileUamFilteredIsfSamples: Int? = null,
    val profileUamEpisodes: Int? = null,
    val profileUamCarbsGrams: Double? = null,
    val profileUamRecentCarbsGrams: Double? = null,
    val profileCalculatedIsf: Double? = null,
    val profileCalculatedCr: Double? = null,
    val profileCalculatedConfidence: Double? = null,
    val profileCalculatedSamples: Int? = null,
    val profileCalculatedIsfSamples: Int? = null,
    val profileCalculatedCrSamples: Int? = null,
    val profileLookbackDays: Int? = null,
    val profileSegmentLines: List<String> = emptyList(),
    val rulePostHypoEnabled: Boolean = true,
    val rulePatternEnabled: Boolean = true,
    val ruleSegmentEnabled: Boolean = true,
    val rulePostHypoPriority: Int = 100,
    val rulePatternPriority: Int = 50,
    val ruleSegmentPriority: Int = 40,
    val rulePostHypoCooldownMinutes: Int = 30,
    val rulePatternCooldownMinutes: Int = 60,
    val ruleSegmentCooldownMinutes: Int = 60,
    val patternMinSamplesPerWindow: Int = 40,
    val patternMinActiveDaysPerWindow: Int = 7,
    val patternLowRateTrigger: Double = 0.12,
    val patternHighRateTrigger: Double = 0.18,
    val analyticsLookbackDays: Int = 365,
    val maxActionsIn6Hours: Int = 3,
    val staleDataMaxMinutes: Int = 10,
    val weekdayHotHours: List<PatternWindow> = emptyList(),
    val weekendHotHours: List<PatternWindow> = emptyList(),
    val qualityMetrics: List<QualityMetricUi> = emptyList(),
    val baselineDeltaLines: List<String> = emptyList(),
    val telemetryCoverageLines: List<String> = emptyList(),
    val telemetryLines: List<String> = emptyList(),
    val actionLines: List<String> = emptyList(),
    val autoConnectLines: List<String> = emptyList(),
    val transportStatusLines: List<String> = emptyList(),
    val syncStatusLines: List<String> = emptyList(),
    val jobStatusLines: List<String> = emptyList(),
    val insightsFilterLabel: String = "Filters: source=all, status=all, days=60, weeks=8",
    val analysisHistoryLines: List<String> = emptyList(),
    val analysisTrendLines: List<String> = emptyList(),
    val ruleCooldownLines: List<String> = emptyList(),
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
    val status: String? = null,
    val days: Int = 60,
    val weeks: Int = 8
)

data class AutoConnectUi(
    val lines: List<String>
)
