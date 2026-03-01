package io.aaps.copilot.ui

import android.Manifest
import android.app.Application
import android.content.ContentValues
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.Settings
import android.security.KeyChain
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.aaps.copilot.CopilotApp
import io.aaps.copilot.config.AppSettings
import io.aaps.copilot.config.resolvedNightscoutUrl
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
import io.aaps.copilot.data.local.entity.TherapyEventEntity
import io.aaps.copilot.data.repository.CloudAnalysisHistoryUiModel
import io.aaps.copilot.data.repository.CloudAnalysisTrendUiModel
import io.aaps.copilot.data.repository.CloudJobsUiModel
import io.aaps.copilot.data.repository.CloudReplayUiModel
import io.aaps.copilot.data.repository.GlucoseSanitizer
import io.aaps.copilot.data.repository.TherapySanitizer
import io.aaps.copilot.data.repository.toDomain
import io.aaps.copilot.data.remote.nightscout.NightscoutTreatmentRequest
import io.aaps.copilot.domain.model.ActionCommand
import io.aaps.copilot.domain.model.DayType
import io.aaps.copilot.domain.model.PatternWindow
import io.aaps.copilot.domain.model.SafetySnapshot
import io.aaps.copilot.domain.predict.BaselineComparator
import io.aaps.copilot.domain.predict.ForecastQualityEvaluator
import io.aaps.copilot.domain.predict.InsulinActionProfileId
import io.aaps.copilot.domain.predict.ProfileEstimator
import io.aaps.copilot.domain.predict.ProfileEstimatorConfig
import io.aaps.copilot.domain.predict.TelemetrySignal
import io.aaps.copilot.domain.rules.AdaptiveTargetControllerRule
import io.aaps.copilot.scheduler.WorkScheduler
import io.aaps.copilot.service.LocalNightscoutServiceController
import io.aaps.copilot.service.LocalNightscoutTls
import java.net.URI
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
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
        db.glucoseDao().observeLatest(limit = 3_000),
        db.therapyDao().observeLatest(limit = 1_800),
        db.forecastDao().observeLatest(limit = 7000),
        db.forecastDao().observeLatestByHorizon(5),
        db.forecastDao().observeLatestByHorizon(30),
        db.forecastDao().observeLatestByHorizon(60),
        db.baselineDao().observeLatest(limit = 600),
        db.ruleExecutionDao().observeLatest(limit = 20),
        db.actionCommandDao().observeLatest(limit = 40),
        db.auditLogDao().observeLatest(limit = 50),
        db.telemetryDao().observeLatest(limit = 12_000),
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
        val glucose = GlucoseSanitizer.filterEntities(values[0] as List<GlucoseSampleEntity>)
        @Suppress("UNCHECKED_CAST")
        val therapy = TherapySanitizer.filterEntities(values[1] as List<TherapyEventEntity>)
        @Suppress("UNCHECKED_CAST")
        val forecasts = values[2] as List<ForecastEntity>
        val latestForecast5Row = values[3] as ForecastEntity?
        val latestForecast30Row = values[4] as ForecastEntity?
        val latestForecast60Row = values[5] as ForecastEntity?
        @Suppress("UNCHECKED_CAST")
        val baseline = values[6] as List<BaselinePointEntity>
        @Suppress("UNCHECKED_CAST")
        val ruleExec = values[7] as List<RuleExecutionEntity>
        @Suppress("UNCHECKED_CAST")
        val actionCommands = values[8] as List<ActionCommandEntity>
        @Suppress("UNCHECKED_CAST")
        val audits = values[9] as List<AuditLogEntity>
        @Suppress("UNCHECKED_CAST")
        val telemetry = values[10] as List<TelemetrySampleEntity>
        @Suppress("UNCHECKED_CAST")
        val patterns = values[11] as List<PatternWindowEntity>
        val profile = values[12] as ProfileEstimateEntity?
        @Suppress("UNCHECKED_CAST")
        val profileSegments = values[13] as List<ProfileSegmentEstimateEntity>
        @Suppress("UNCHECKED_CAST")
        val syncStates = values[14] as List<SyncStateEntity>
        val settings = values[15] as AppSettings
        val autoConnect = values[16] as AutoConnectUi?
        val message = values[17] as String?
        val dryRun = values[18] as DryRunUi?
        val cloudReplay = values[19] as CloudReplayUiModel?
        val cloudJobs = values[20] as CloudJobsUiModel?
        val analysisHistory = values[21] as CloudAnalysisHistoryUiModel?
        val analysisTrend = values[22] as CloudAnalysisTrendUiModel?
        val insightsFilter = values[23] as InsightsFilterUi

        val sortedGlucose = glucose.sortedBy { it.timestamp }
        val latest = sortedGlucose.lastOrNull()
        val prev = sortedGlucose.dropLast(1).lastOrNull()
        val quality = qualityEvaluator.evaluate(forecasts, glucose)
        val baselineDeltas = baselineComparator.compare(
            forecasts = forecasts.map { it.toDomainForecast() },
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
        val lastLocalNsEntries = audits.firstOrNull { it.message == "local_nightscout_entries_post" }
        val lastLocalNsTreatments = audits.firstOrNull { it.message == "local_nightscout_treatments_post" }
        val lastLocalNsDeviceStatus = audits.firstOrNull { it.message == "local_nightscout_devicestatus_post" }
        val lastLocalNsReactive = audits.firstOrNull { it.message == "local_nightscout_reactive_automation_enqueued" }
        val tlsDiagnosticLines = buildAapsTlsDiagnosticLines(
            settings = settings,
            audits = audits,
            nowTs = now
        )
        val transportStatusLines = buildList {
            val effectiveNightscoutUrl = settings.resolvedNightscoutUrl()
            add(
                "Local Nightscout emulator: " + if (settings.localNightscoutEnabled) {
                    "enabled (https://127.0.0.1:${settings.localNightscoutPort})"
                } else {
                    "disabled"
                }
            )
            add(
                "Effective Nightscout URL: " + if (effectiveNightscoutUrl.isNotBlank()) {
                    effectiveNightscoutUrl
                } else {
                    "not configured"
                }
            )
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
            add("Outbound temp target/carbs: Nightscout API -> LOCAL_TREATMENTS -> NS_EMULATOR -> custom relay")
            add(
                "Local fallback relay: " + if (settings.localCommandFallbackEnabled) {
                    "enabled (${settings.localCommandPackage} / ${settings.localCommandAction})"
                } else {
                    "disabled"
                }
            )
            add("Direct AAPS broadcasts: build-dependent, use Action delivery/Audit log for per-channel validation")
            lastBroadcastIngest?.let {
                add("Last broadcast ingest: ${formatTs(it.timestamp)}")
            }
            lastBroadcastSkip?.let {
                add("Last broadcast skip: ${formatTs(it.timestamp)} (${it.message})")
            }
            lastLocalNsEntries?.let {
                add(
                    "Local NS entries POST: ${formatTs(it.timestamp)} " +
                        "(received=${auditMetaField(it, "received") ?: "?"}, inserted=${auditMetaField(it, "inserted") ?: "?"})"
                )
            }
            lastLocalNsTreatments?.let {
                add(
                    "Local NS treatments POST: ${formatTs(it.timestamp)} " +
                        "(received=${auditMetaField(it, "received") ?: "?"}, inserted=${auditMetaField(it, "inserted") ?: "?"}, telemetry=${auditMetaField(it, "telemetry") ?: "?"})"
                )
            }
            lastLocalNsDeviceStatus?.let {
                add(
                    "Local NS devicestatus POST: ${formatTs(it.timestamp)} " +
                        "(received=${auditMetaField(it, "received") ?: "?"}, telemetry=${auditMetaField(it, "telemetry") ?: "?"})"
                )
            }
            lastLocalNsReactive?.let {
                add(
                    "Local NS reactive automation: ${formatTs(it.timestamp)} " +
                        "(channel=${auditMetaField(it, "channel") ?: "?"}, inserted=${auditMetaField(it, "inserted") ?: "?"}, telemetry=${auditMetaField(it, "telemetry") ?: "?"})"
                )
            }
            addAll(tlsDiagnosticLines)
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
        val yesterdayProfileLines = buildYesterdayProfileLines(
            glucose = sortedGlucose,
            therapy = therapy,
            telemetry = telemetry,
            nowTs = now
        )
        val activityLines = buildActivitySummaryLines(
            telemetry = telemetry,
            nowTs = now,
            activityPermissionGranted = isActivityRecognitionGranted(),
            audits = audits
        )
        val telemetryCoverageLines = buildTelemetryCoverageLines(telemetry, therapy, profile, now)
        val telemetryLines = buildTelemetryLines(telemetry)
        val telemetryByKey = latestTelemetryByKey(telemetry)
        val actionLines = buildActionLines(actionCommands)
        val forecast5Latest = latestForecast5Row?.valueMmol ?: latestForecastValue(forecasts, 5)
        val forecast30Latest = latestForecast30Row?.valueMmol ?: latestForecastValue(forecasts, 30)
        val forecast60Latest = latestForecast60Row?.valueMmol ?: latestForecastValue(forecasts, 60)
        val calculatedUamFlag = telemetryByKey["uam_calculated_flag"].toNumericValue()
        val calculatedUamConfidence = telemetryByKey["uam_calculated_confidence"].toNumericValue()
        val calculatedUamCarbs = telemetryByKey["uam_calculated_carbs_grams"].toNumericValue()
        val calculatedUamDelta5 = telemetryByKey["uam_calculated_delta5_mmol"].toNumericValue()
        val calculatedUamRise15 = telemetryByKey["uam_calculated_rise15_mmol"].toNumericValue()
        val calculatedUamRise30 = telemetryByKey["uam_calculated_rise30_mmol"].toNumericValue()
        val controllerWeightedError = if (forecast30Latest != null && forecast60Latest != null) {
            val e30 = forecast30Latest - settings.baseTargetMmol
            val e60 = forecast60Latest - settings.baseTargetMmol
            0.65 * e30 + 0.35 * e60
        } else {
            null
        }
        val latestAdaptiveExecution = ruleExec.firstOrNull { it.ruleId == AdaptiveTargetControllerRule.RULE_ID }
        val controllerAction = latestAdaptiveExecution?.actionJson?.let { parseRuleActionJson(it) }
        val controllerReasons = latestAdaptiveExecution?.reasonsJson?.let { parseRuleReasonsJson(it) }.orEmpty()
        val controllerConfidence = parseConfidenceFromReasons(controllerReasons)
        val controllerReason = controllerReasons.firstOrNull()
        val adaptiveAuditLines = audits
            .filter { it.message.startsWith("adaptive_controller_") }
            .take(10)
            .map { "${formatTs(it.timestamp)} ${it.level} ${it.message}" }
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
            localNightscoutEnabled = settings.localNightscoutEnabled,
            localNightscoutPort = settings.localNightscoutPort,
            resolvedNightscoutUrl = settings.resolvedNightscoutUrl(),
            localBroadcastIngestEnabled = settings.localBroadcastIngestEnabled,
            strictBroadcastSenderValidation = settings.strictBroadcastSenderValidation,
            localCommandFallbackEnabled = settings.localCommandFallbackEnabled,
            localCommandPackage = settings.localCommandPackage,
            localCommandAction = settings.localCommandAction,
            insulinProfileId = settings.insulinProfileId,
            baseTargetMmol = settings.baseTargetMmol,
            postHypoThresholdMmol = settings.postHypoThresholdMmol,
            postHypoDeltaThresholdMmol5m = settings.postHypoDeltaThresholdMmol5m,
            postHypoTargetMmol = settings.postHypoTargetMmol,
            postHypoDurationMinutes = settings.postHypoDurationMinutes,
            postHypoLookbackMinutes = settings.postHypoLookbackMinutes,
            adaptiveControllerEnabled = settings.adaptiveControllerEnabled,
            adaptiveControllerPriority = settings.adaptiveControllerPriority,
            adaptiveControllerRetargetMinutes = settings.adaptiveControllerRetargetMinutes,
            adaptiveControllerSafetyProfile = settings.adaptiveControllerSafetyProfile,
            adaptiveControllerStaleMaxMinutes = settings.adaptiveControllerStaleMaxMinutes,
            adaptiveControllerMaxActions6h = settings.adaptiveControllerMaxActions6h,
            adaptiveControllerMaxStepMmol = settings.adaptiveControllerMaxStepMmol,
            latestGlucoseMmol = latest?.mmol,
            glucoseDelta = if (latest != null && prev != null) latest.mmol - prev.mmol else null,
            forecast5m = forecast5Latest,
            forecast30m = forecast30Latest,
            forecast60m = forecast60Latest,
            calculatedUamActive = calculatedUamFlag?.let { it >= 0.5 },
            calculatedUamConfidence = calculatedUamConfidence,
            calculatedUamCarbsGrams = calculatedUamCarbs,
            calculatedUamDelta5Mmol = calculatedUamDelta5,
            calculatedUamRise15Mmol = calculatedUamRise15,
            calculatedUamRise30Mmol = calculatedUamRise30,
            lastRuleState = ruleExec.firstOrNull()?.state,
            lastRuleId = ruleExec.firstOrNull()?.ruleId,
            controllerState = latestAdaptiveExecution?.state,
            controllerReason = controllerReason,
            controllerConfidence = controllerConfidence,
            controllerNextTarget = controllerAction?.targetMmol,
            controllerDurationMinutes = controllerAction?.durationMinutes,
            controllerForecast30 = forecast30Latest,
            controllerForecast60 = forecast60Latest,
            controllerWeightedError = controllerWeightedError,
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
            yesterdayProfileLines = yesterdayProfileLines,
            activityLines = activityLines,
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
            adaptiveAuditLines = adaptiveAuditLines,
            auditLines = audits.map { "${it.level}: ${it.message}" },
            message = message
        )
    }
        .conflate()
        .flowOn(Dispatchers.Default)
        .stateIn(
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

    fun setExportFolderUri(exportUri: String?) {
        viewModelScope.launch {
            container.settingsStore.update {
                it.copy(exportFolderUri = exportUri)
            }
            messageState.value = "Export folder saved"
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

    fun setInsulinProfile(profileRaw: String) {
        viewModelScope.launch {
            val normalized = InsulinActionProfileId.fromRaw(profileRaw).name
            container.settingsStore.update { it.copy(insulinProfileId = normalized) }
            WorkScheduler.triggerReactiveAutomation(getApplication())
            messageState.value = "Insulin profile set to $normalized"
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
            LocalNightscoutServiceController.start(getApplication())
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

    fun setLocalNightscoutConfig(enabled: Boolean, port: Int) {
        viewModelScope.launch {
            val safePort = port.coerceIn(1_024, 65_535)
            container.settingsStore.update {
                it.copy(
                    localNightscoutEnabled = enabled,
                    localNightscoutPort = safePort
                )
            }
            LocalNightscoutServiceController.reconcile(getApplication(), enabled)
            messageState.value = if (enabled) {
                "Local Nightscout enabled at https://127.0.0.1:$safePort"
            } else {
                "Local Nightscout disabled"
            }
        }
    }

    fun exportLocalNightscoutCertificate() {
        viewModelScope.launch {
            runCatching {
                val context = getApplication<Application>().applicationContext
                val caCertificate = LocalNightscoutTls.loadCaCertificate(context)
                val serverCertificate = LocalNightscoutTls.loadServerCertificate(context)
                val caDerBytes = caCertificate.encoded
                val caPemBytes = LocalNightscoutTls.toPem(caCertificate).toByteArray()
                val serverPemBytes = LocalNightscoutTls.toPem(serverCertificate).toByteArray()
                val resolver = context.contentResolver

                fun exportDownload(fileName: String, mimeType: String, payload: ByteArray) {
                    val values = ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                        put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                        put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            put(MediaStore.MediaColumns.IS_PENDING, 1)
                        }
                    }
                    val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                        ?: error("failed_to_create_download_entry:$fileName")
                    resolver.openOutputStream(uri)?.use { output ->
                        output.write(payload)
                    } ?: error("failed_to_open_output_stream:$fileName")
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        val finalizeValues = ContentValues().apply {
                            put(MediaStore.MediaColumns.IS_PENDING, 0)
                        }
                        resolver.update(uri, finalizeValues, null, null)
                    }
                }

                exportDownload(
                    fileName = "copilot-local-nightscout-root-ca.cer",
                    mimeType = "application/pkix-cert",
                    payload = caDerBytes
                )
                exportDownload(
                    fileName = "copilot-local-nightscout-root-ca.crt",
                    mimeType = "application/x-x509-ca-cert",
                    payload = caPemBytes
                )
                exportDownload(
                    fileName = "copilot-local-nightscout-server.crt",
                    mimeType = "application/x-x509-ca-cert",
                    payload = serverPemBytes
                )
            }.onSuccess {
                messageState.value =
                    "TLS certificates exported (root CA + server). Install root CA in Android, then reconnect AAPS NSClient."
            }.onFailure {
                messageState.value = "Certificate export failed: ${it.message}"
            }
        }
    }

    fun installLocalNightscoutCertificate() {
        viewModelScope.launch {
            runCatching {
                val context = getApplication<Application>().applicationContext
                val certificate = LocalNightscoutTls.loadCaCertificate(context)
                val installIntent = KeyChain.createInstallIntent().apply {
                    putExtra(KeyChain.EXTRA_CERTIFICATE, certificate.encoded)
                    putExtra(KeyChain.EXTRA_NAME, "AAPS Copilot Loopback Root CA")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(installIntent)
            }.onSuccess {
                messageState.value =
                    "System certificate installer opened for Root CA. If CA install is not offered, export certs and install root CA manually from Android settings."
            }.onFailure {
                messageState.value = "Certificate install launch failed: ${it.message}"
            }
        }
    }

    fun openCertificateSettings() {
        viewModelScope.launch {
            runCatching {
                val context = getApplication<Application>().applicationContext
                val intent = Intent(Settings.ACTION_SECURITY_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            }.onSuccess {
                messageState.value = "Android security settings opened. Install CA certificate from Downloads."
            }.onFailure {
                messageState.value = "Failed to open security settings: ${it.message}"
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

    fun setAdaptiveControllerConfig(
        enabled: Boolean,
        priority: Int,
        retargetMinutes: Int,
        safetyProfile: String,
        staleMaxMinutes: Int,
        maxActions6h: Int,
        maxStepMmol: Double
    ) {
        viewModelScope.launch {
            val normalizedProfile = when (safetyProfile.trim().uppercase(Locale.US)) {
                "STRICT" -> "STRICT"
                "AGGRESSIVE" -> "AGGRESSIVE"
                else -> "BALANCED"
            }
            val normalizedRetarget = when (retargetMinutes) {
                5,
                15,
                30 -> retargetMinutes
                else -> 5
            }
            container.settingsStore.update {
                it.copy(
                    adaptiveControllerEnabled = true,
                    adaptiveControllerPriority = priority.coerceIn(0, 200),
                    adaptiveControllerRetargetMinutes = normalizedRetarget,
                    adaptiveControllerSafetyProfile = normalizedProfile,
                    adaptiveControllerStaleMaxMinutes = staleMaxMinutes.coerceIn(5, 60),
                    adaptiveControllerMaxActions6h = maxActions6h.coerceIn(1, 10),
                    adaptiveControllerMaxStepMmol = maxStepMmol.coerceIn(0.05, 1.00)
                )
            }
            if (!enabled) {
                messageState.value = "Adaptive controller is always ON"
                return@launch
            }
            messageState.value = "Adaptive controller settings updated"
        }
    }

    fun setAdaptiveControllerEnabled(enabled: Boolean) {
        viewModelScope.launch {
            container.settingsStore.update { it.copy(adaptiveControllerEnabled = true) }
            WorkScheduler.triggerReactiveAutomation(getApplication())
            messageState.value = if (enabled) {
                "Adaptive controller enabled"
            } else {
                "Adaptive controller is always ON"
            }
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

    fun runNightscoutSelfTest() {
        viewModelScope.launch {
            val settings = container.settingsStore.settings.first()
            val url = settings.resolvedNightscoutUrl()
            if (url.isBlank()) {
                messageState.value = "Nightscout self-test failed: URL is not configured"
                return@launch
            }
            val nsApi = container.apiFactory.nightscoutApi(url, settings.apiSecret)
            val loopback = isLoopbackUrl(url)

            runCatching {
                val status = nsApi.getStatus()
                val statusText = status.status ?: "unknown"
                if (!loopback) {
                    nsApi.getTreatments(mapOf("count" to "1"))
                    return@runCatching "Nightscout reachable ($statusText). Read-only check on external URL."
                }

                val nowIso = Instant.now().toString()
                val nowMs = System.currentTimeMillis()
                nsApi.postTreatment(
                    NightscoutTreatmentRequest(
                        createdAt = nowIso,
                        eventType = "Temporary Target",
                        duration = 30,
                        targetTop = 100.0,
                        targetBottom = 100.0,
                        reason = "copilot_self_test_temp",
                        notes = "copilot:self_test"
                    )
                )
                nsApi.postTreatment(
                    NightscoutTreatmentRequest(
                        createdAt = nowIso,
                        eventType = "Carb Correction",
                        carbs = 1.0,
                        reason = "copilot_self_test_carbs",
                        notes = "copilot:self_test"
                    )
                )
                nsApi.postSgvEntries(
                    listOf(
                        mapOf(
                            "date" to nowMs,
                            "sgv" to 108,
                            "type" to "sgv",
                            "device" to "copilot_self_test"
                        )
                    )
                )
                nsApi.postDeviceStatus(
                    listOf(
                        mapOf(
                            "created_at" to nowIso,
                            "openaps" to mapOf(
                                "iob" to mapOf("iob" to 1.1),
                                "suggested" to mapOf(
                                    "COB" to 12,
                                    "insulinReq" to 0.4,
                                    "predBGs" to mapOf("UAM" to listOf(118, 125))
                                ),
                                "profile" to mapOf(
                                    "dia" to 5,
                                    "sens" to 45,
                                    "carb_ratio" to 10
                                )
                            ),
                            "uploader" to mapOf("battery" to 90)
                        )
                    )
                )
                val latest = nsApi.getTreatments(mapOf("count" to "6"))
                val sgvEcho = nsApi.getSgvEntries(mapOf("count" to "3"))
                val echoed = latest.count {
                    it.reason?.contains("copilot_self_test", ignoreCase = true) == true ||
                        it.notes?.contains("copilot:self_test", ignoreCase = true) == true
                }
                val telemetryEcho = db.telemetryDao()
                    .since(nowMs - 5 * 60_000L)
                    .count { it.source == "local_nightscout_devicestatus" }
                "Local Nightscout self-test OK ($statusText). Echoed treatments: $echoed, sgv points: ${sgvEcho.size}, devicestatus telemetry: $telemetryEcho"
            }.onSuccess { message ->
                messageState.value = message
            }.onFailure { error ->
                messageState.value = "Nightscout self-test failed: ${error.message}"
            }
        }
    }

    fun runAapsTlsDiagnostic() {
        viewModelScope.launch {
            val nowTs = System.currentTimeMillis()
            val settings = container.settingsStore.settings.first()
            val audits = db.auditLogDao().observeLatest(limit = 500).first()
            val lines = buildAapsTlsDiagnosticLines(settings, audits, nowTs)
            messageState.value = if (lines.isEmpty()) {
                "AAPS TLS diagnostic: no data"
            } else {
                lines.take(3).joinToString(" | ")
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

    private fun isLoopbackUrl(url: String): Boolean {
        val host = runCatching { URI(url.trim()).host.orEmpty() }.getOrDefault("")
        return host.equals("127.0.0.1") || host.equals("localhost", ignoreCase = true)
    }

    private fun buildAapsTlsDiagnosticLines(
        settings: AppSettings,
        audits: List<AuditLogEntity>,
        nowTs: Long
    ): List<String> {
        val aapsTlsCompatibility = inspectAapsTlsCompatibility()
        fun withCompatibilityHints(lines: List<String>): List<String> {
            val extras = mutableListOf<String>()
            if (!aapsTlsCompatibility.installed) {
                extras.add("AAPS package not detected on this phone")
            } else {
                aapsTlsCompatibility.targetSdk?.let { sdk ->
                    extras.add("AAPS targetSdk: $sdk")
                }
                if (aapsTlsCompatibility.likelyRejectsUserCa) {
                    extras.add("Compatibility warning: this AAPS build likely rejects user-installed CA certs")
                    extras.add("Hint: use public Nightscout HTTPS (public CA) or patched AAPS build with user-CA trust")
                }
            }
            return lines + extras
        }

        val loopbackUrl = "https://127.0.0.1:${settings.localNightscoutPort}"
        if (!settings.localNightscoutEnabled) {
            return withCompatibilityHints(
                listOf(
                "AAPS transport: OFF (reason=NS_DISABLED)",
                "Source error: local Nightscout emulator is disabled",
                "Hint 1: enable local Nightscout and save settings"
                )
            )
        }

        val recentStartFailure = audits.firstOrNull {
            it.message == "local_nightscout_start_failed" &&
                nowTs - it.timestamp <= LOCAL_NS_START_FAILURE_WINDOW_MS
        }
        if (recentStartFailure != null) {
            val failureReason = auditMetaField(recentStartFailure, "error") ?: "unknown"
            return withCompatibilityHints(
                listOf(
                "AAPS transport: FAIL (reason=NS_START_FAILED)",
                "Source error: local Nightscout start failed ($failureReason)",
                "Hint 1: free/change local port and tap Save local Nightscout",
                "Hint 2: confirm loopback URL in AAPS is $loopbackUrl"
                )
            )
        }

        val recentExternal = audits
            .asSequence()
            .filter { it.message == "local_nightscout_external_request" }
            .filter { nowTs - it.timestamp <= TLS_DIAGNOSTIC_WINDOW_MS }
            .toList()
            .sortedByDescending { it.timestamp }
        val recentExternalAapsLike = recentExternal.filter(::isLikelyAapsClientAudit)
        val recentSocketSessions = audits
            .asSequence()
            .filter { it.message == "local_nightscout_socket_session_created" }
            .filter { nowTs - it.timestamp <= TLS_DIAGNOSTIC_WINDOW_MS }
            .toList()
            .sortedByDescending { it.timestamp }
        val recentSocketAapsLike = recentSocketSessions.filter(::isLikelyAapsClientAudit)
        val recentSocketAuth = audits
            .asSequence()
            .filter { it.message == "local_nightscout_socket_authorize" }
            .filter { nowTs - it.timestamp <= TLS_DIAGNOSTIC_WINDOW_MS }
            .toList()
            .sortedByDescending { it.timestamp }
        val recentSocketAuthAapsLike = recentSocketAuth.filter(::isLikelyAapsClientAudit)

        val latestAuth = recentSocketAuthAapsLike.firstOrNull()
        if (latestAuth != null) {
            val source = auditMetaField(latestAuth, "source") ?: "unknown"
            return withCompatibilityHints(
                listOf(
                "AAPS transport: OK (reason=NS_SOCKET_AUTH_OK)",
                "Last AUTH: ${formatTs(latestAuth.timestamp)} (source=$source)",
                "Socket sessions ${TLS_DIAGNOSTIC_WINDOW_MINUTES}m: total=${recentSocketSessions.size}, app-like=${recentSocketAapsLike.size}",
                "Loopback endpoint: $loopbackUrl"
                )
            )
        }

        val latestSocket = recentSocketAapsLike.firstOrNull()
        if (latestSocket != null) {
            val source = auditMetaField(latestSocket, "source") ?: "unknown"
            return withCompatibilityHints(
                listOf(
                "AAPS transport: FAIL (reason=NS_SOCKET_NO_AUTH)",
                "Source error: Socket session opened (${formatTs(latestSocket.timestamp)} source=$source), but no authorize event",
                "Hint 1: verify API Secret in AAPS equals Copilot value",
                "Hint 2: restart NSClientV1 and trigger sync in AAPS"
                )
            )
        }

        val latestExternalAaps = recentExternalAapsLike.firstOrNull()
        if (latestExternalAaps != null) {
            val method = auditMetaField(latestExternalAaps, "method") ?: "GET"
            val path = auditMetaField(latestExternalAaps, "path") ?: "/api/v1/*"
            return withCompatibilityHints(
                listOf(
                "AAPS transport: FAIL (reason=NS_HTTP_NO_SOCKET)",
                "Source error: HTTPS request seen (${formatTs(latestExternalAaps.timestamp)} $method $path), but no socket session",
                "Hint 1: in AAPS use NSClientV1 and URL exactly $loopbackUrl",
                "Hint 2: install loopback certificate as CA and restart NSClient"
                )
            )
        }

        val recentBrowser = recentExternal.filter {
            auditMetaField(it, "source").equals("browser", ignoreCase = true)
        }
        val sourceError = if (recentBrowser.isNotEmpty()) {
            "browser HTTPS traffic exists, but no AAPS-like client traffic in last ${TLS_DIAGNOSTIC_WINDOW_MINUTES}m"
        } else {
            "no successful external HTTPS requests to local NS in last ${TLS_DIAGNOSTIC_WINDOW_MINUTES}m"
        }
        val reasonCode = if (recentBrowser.isNotEmpty()) "NS_BROWSER_ONLY" else "NS_NO_TRAFFIC"
        val secretHint = if (settings.apiSecret.isBlank()) {
            "Hint 4: set non-empty API Secret in Copilot and AAPS"
        } else {
            "Hint 4: verify API Secret in AAPS equals Copilot value"
        }
        return withCompatibilityHints(
            listOf(
            "AAPS transport: FAIL (reason=$reasonCode)",
            "Source error: $sourceError",
            "Hint 1: in AAPS NSClient set URL exactly $loopbackUrl",
            "Hint 2: install loopback certificate as CA in Android",
            "Hint 3: restart AAPS NSClient and trigger sync",
            secretHint
            )
        )
    }

    private fun inspectAapsTlsCompatibility(): AapsTlsCompatibility {
        val context = getApplication<Application>().applicationContext
        val pm = context.packageManager
        val packageName = "info.nightscout.androidaps"
        val appInfo = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getApplicationInfo(packageName, PackageManager.ApplicationInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.getApplicationInfo(packageName, 0)
            }
        }.getOrNull() ?: return AapsTlsCompatibility(
            installed = false,
            targetSdk = null,
            networkSecurityConfigRes = null,
            likelyRejectsUserCa = false
        )

        // Android 14+ blocks reflective access to hidden ApplicationInfo fields,
        // so we avoid probing networkSecurityConfigRes via reflection.
        val networkSecurityConfigRes: Int? = null
        val likelyRejectsUserCa = appInfo.targetSdkVersion >= Build.VERSION_CODES.N
        return AapsTlsCompatibility(
            installed = true,
            targetSdk = appInfo.targetSdkVersion,
            networkSecurityConfigRes = networkSecurityConfigRes,
            likelyRejectsUserCa = likelyRejectsUserCa
        )
    }

    private fun isLikelyAapsClientAudit(entry: AuditLogEntity): Boolean {
        val source = auditMetaField(entry, "source")?.lowercase(Locale.US).orEmpty()
        if (source == "browser") return false
        val userAgent = auditMetaField(entry, "userAgent")?.lowercase(Locale.US).orEmpty()
        if (
            userAgent.contains("mozilla") ||
            userAgent.contains("chrome") ||
            userAgent.contains("safari") ||
            userAgent.contains("curl") ||
            userAgent.contains("postman") ||
            userAgent.contains("insomnia")
        ) {
            return false
        }
        return source == "okhttp" || source == "dalvik" || source == "unknown"
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

    private fun payloadDouble(payloadJson: String, vararg keys: String): Double? {
        return runCatching {
            val obj = JSONObject(payloadJson)
            keys.firstNotNullOfOrNull { key ->
                obj.opt(key)?.toString()?.replace(",", ".")?.toDoubleOrNull()
            }
        }.getOrNull()
    }

    private data class ParsedRuleAction(
        val targetMmol: Double?,
        val durationMinutes: Int?
    )

    private fun parseRuleActionJson(actionJson: String): ParsedRuleAction? {
        return runCatching {
            val obj = JSONObject(actionJson)
            ParsedRuleAction(
                targetMmol = obj.optDouble("targetMmol").takeIf { !it.isNaN() },
                durationMinutes = obj.optInt("durationMinutes").takeIf { it > 0 }
            )
        }.getOrNull()
    }

    private fun parseRuleReasonsJson(reasonsJson: String): List<String> {
        return runCatching {
            val array = JSONArray(reasonsJson)
            buildList {
                for (i in 0 until array.length()) {
                    val raw = array.optString(i).trim()
                    if (raw.isNotBlank()) add(raw)
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun parseConfidenceFromReasons(reasons: List<String>): Double? {
        val marker = reasons.firstOrNull { it.startsWith("confidence=") } ?: return null
        return marker.substringAfter("=", "").replace(",", ".").toDoubleOrNull()
    }

    private fun auditMetaField(entry: AuditLogEntity, key: String): String? {
        return runCatching {
            val value = JSONObject(entry.metadataJson).opt(key)
            when (value) {
                null,
                JSONObject.NULL -> null
                is String -> value.takeIf { it.isNotBlank() }
                else -> value.toString()
            }
        }.getOrNull()
    }

    private fun buildRuleCooldownLines(
        executions: List<RuleExecutionEntity>,
        settings: AppSettings,
        nowTs: Long
    ): List<String> {
        val rules = listOf(
            Triple(AdaptiveTargetControllerRule.RULE_ID, "Adaptive", settings.adaptiveControllerRetargetMinutes),
            Triple("PostHypoReboundGuard.v1", "PostHypo", settings.rulePostHypoCooldownMinutes),
            Triple("PatternAdaptiveTarget.v1", "Pattern", settings.rulePatternCooldownMinutes),
            Triple("SegmentProfileGuard.v1", "Segment", settings.ruleSegmentCooldownMinutes)
        )
        return rules.map { (ruleId, title, cooldownMinutes) ->
            if (ruleId == AdaptiveTargetControllerRule.RULE_ID && !settings.adaptiveControllerEnabled) {
                return@map "$title: disabled"
            }
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

    private fun telemetryCoverageSpecs(): List<TelemetryCoverageSpec> = listOf(
        TelemetryCoverageSpec(
            primaryKey = "iob_units",
            label = "IOB",
            staleThresholdMin = 30L,
            exactAliases = listOf("raw_iob"),
            tokenAliases = listOf("iob", "insulinonboard")
        ),
        TelemetryCoverageSpec(
            primaryKey = "cob_grams",
            label = "COB",
            staleThresholdMin = 30L,
            exactAliases = listOf("raw_cob"),
            tokenAliases = listOf("cob", "carbsonboard")
        ),
        TelemetryCoverageSpec(
            primaryKey = "carbs_grams",
            label = "Carbs",
            staleThresholdMin = 240L,
            exactAliases = listOf("status_carbs_grams", "raw_carbs"),
            tokenAliases = listOf("carbs", "enteredcarbs", "mealcarbs")
        ),
        TelemetryCoverageSpec(
            primaryKey = "insulin_units",
            label = "Insulin",
            staleThresholdMin = 240L,
            exactAliases = listOf("status_insulin_units", "raw_insulin", "raw_insulin_units"),
            tokenAliases = listOf("insulin_units", "bolusunits", "enteredinsulin")
        ),
        TelemetryCoverageSpec(
            primaryKey = "dia_hours",
            label = "DIA",
            staleThresholdMin = 24 * 60L,
            tokenAliases = listOf("dia", "insulinactiontime")
        ),
        TelemetryCoverageSpec(
            primaryKey = "steps_count",
            label = "Steps",
            staleThresholdMin = 24 * 60L,
            tokenAliases = listOf("steps", "stepcount")
        ),
        TelemetryCoverageSpec(
            primaryKey = "activity_ratio",
            label = "Activity ratio",
            staleThresholdMin = 180L,
            tokenAliases = listOf("activity", "activityratio", "sensitivityratio")
        ),
        TelemetryCoverageSpec(
            primaryKey = "distance_km",
            label = "Distance",
            staleThresholdMin = 24 * 60L,
            tokenAliases = listOf("distance", "distancekm")
        ),
        TelemetryCoverageSpec(
            primaryKey = "active_minutes",
            label = "Active minutes",
            staleThresholdMin = 24 * 60L,
            tokenAliases = listOf("activeminutes", "exerciseminutes")
        ),
        TelemetryCoverageSpec(
            primaryKey = "calories_active_kcal",
            label = "Active calories",
            staleThresholdMin = 24 * 60L,
            tokenAliases = listOf("activecalories", "caloriesactive")
        ),
        TelemetryCoverageSpec(
            primaryKey = "heart_rate_bpm",
            label = "Heart rate",
            staleThresholdMin = 180L,
            tokenAliases = listOf("heart", "heartrate")
        ),
        TelemetryCoverageSpec(
            primaryKey = "uam_calculated_flag",
            label = "UAM",
            staleThresholdMin = 180L,
            exactAliases = listOf("uam_value", "uam_detected", "unannounced_meal", "has_uam", "is_uam")
        ),
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

    private fun buildTelemetryCoverageLines(
        samples: List<TelemetrySampleEntity>,
        therapyEvents: List<TherapyEventEntity>,
        profile: ProfileEstimateEntity?,
        nowTs: Long
    ): List<String> {
        val latestByKey = latestTelemetryByKey(samples)
        val therapyFallback = resolveTherapyFallback(therapyEvents)
        val lines = telemetryCoverageSpecs().map { spec ->
            val sample = resolveTelemetrySample(spec, latestByKey)
            val fallback = resolveCoverageFallback(spec, therapyFallback, profile, nowTs)
            if (sample == null) {
                fallback ?: "${spec.label}: MISSING"
            } else if (fallback != null && shouldPreferFallback(spec, sample)) {
                "$fallback [override]"
            } else {
                val ageMin = ((nowTs - sample.timestamp).coerceAtLeast(0L)) / 60_000L
                val freshness = if (ageMin <= spec.staleThresholdMin) "fresh" else "stale ${ageMin}m"
                "${spec.label}: ${formatTelemetryLine(sample)} [$freshness]"
            }
        }
        return if (samples.isEmpty()) {
            listOf("Telemetry stream is empty; fallback/profile values are shown when available.") + lines
        } else {
            lines
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
            "distance_km",
            "active_minutes",
            "calories_active_kcal",
            "heart_rate_bpm",
            "uam_calculated_flag",
            "uam_calculated_confidence",
            "uam_calculated_carbs_grams",
            "uam_calculated_delta5_mmol",
            "uam_calculated_rise15_mmol",
            "uam_calculated_rise30_mmol",
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
        val syntheticPrimary = telemetryCoverageSpecs()
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

    private fun latestTelemetryByKey(
        samples: List<TelemetrySampleEntity>,
        nowTs: Long = System.currentTimeMillis()
    ): Map<String, TelemetrySampleEntity> {
        val todayStart = Instant.ofEpochMilli(nowTs)
            .atZone(ZoneId.systemDefault())
            .toLocalDate()
            .atStartOfDay(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
        return samples
            .filter { isTelemetrySampleUsable(it) }
            .groupBy { it.key }
            .mapValues { (key, values) ->
                if (key in CUMULATIVE_ACTIVITY_KEYS) {
                    val dayValues = values.filter { it.timestamp >= todayStart }
                    val sourceValues = if (dayValues.isNotEmpty()) dayValues else values
                    sourceValues.maxWithOrNull(
                        compareBy<TelemetrySampleEntity> { it.valueDouble ?: Double.NEGATIVE_INFINITY }
                            .thenBy { it.timestamp }
                    )
                } else {
                    values.maxByOrNull { it.timestamp }
                }
            }
            .filterValues { it != null }
            .mapValues { it.value!! }
    }

    private fun latestForecastValue(forecasts: List<ForecastEntity>, horizonMinutes: Int): Double? {
        return forecasts
            .asSequence()
            .filter { it.horizonMinutes == horizonMinutes }
            .maxWithOrNull(compareBy<ForecastEntity> { it.timestamp }.thenBy { it.id })
            ?.valueMmol
    }

    private fun isTelemetrySampleUsable(sample: TelemetrySampleEntity): Boolean {
        if (sample.key != "uam_calculated_flag") return true
        val numeric = sample.valueDouble ?: sample.valueText?.replace(",", ".")?.toDoubleOrNull()
        return numeric == null || numeric in 0.0..1.5
    }

    private fun TelemetrySampleEntity?.toNumericValue(): Double? {
        if (this == null) return null
        return valueDouble ?: valueText?.replace(",", ".")?.toDoubleOrNull()
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

    private data class TherapyCoverageFallback(
        val carbsGrams: Double?,
        val carbsTs: Long?,
        val insulinUnits: Double?,
        val insulinTs: Long?
    )

    private fun resolveTherapyFallback(events: List<TherapyEventEntity>): TherapyCoverageFallback {
        var carbs: Double? = null
        var carbsTs: Long? = null
        var insulin: Double? = null
        var insulinTs: Long? = null

        events.sortedByDescending { it.timestamp }.forEach { event ->
            if (carbs == null && (event.type.equals("carbs", true) || event.type.equals("meal_bolus", true))) {
                val grams = payloadDouble(event.payloadJson, "grams", "carbs", "enteredCarbs", "mealCarbs")
                if (grams != null && grams in 1.0..300.0) {
                    carbs = grams
                    carbsTs = event.timestamp
                }
            }
            if (insulin == null && (event.type.equals("meal_bolus", true) || event.type.equals("correction_bolus", true))) {
                val units = payloadDouble(event.payloadJson, "units", "bolusUnits", "insulin", "enteredInsulin")
                if (units != null && units in 0.05..25.0) {
                    insulin = units
                    insulinTs = event.timestamp
                }
            }
            if (carbs != null && insulin != null) return@forEach
        }

        return TherapyCoverageFallback(
            carbsGrams = carbs,
            carbsTs = carbsTs,
            insulinUnits = insulin,
            insulinTs = insulinTs
        )
    }

    private fun resolveCoverageFallback(
        spec: TelemetryCoverageSpec,
        therapyFallback: TherapyCoverageFallback,
        profile: ProfileEstimateEntity?,
        nowTs: Long
    ): String? {
        return when (spec.primaryKey) {
            "carbs_grams" -> therapyFallback.carbsGrams?.let {
                val ageMin = minutesSince(nowTs, therapyFallback.carbsTs) ?: 0L
                val freshness = if (ageMin <= spec.staleThresholdMin) "fresh" else "stale ${ageMin}m"
                "Carbs: ${String.format(Locale.US, "%.1f", it)} g (therapy fallback, ${formatTs(therapyFallback.carbsTs)}) [$freshness]"
            }
            "insulin_units" -> therapyFallback.insulinUnits?.let {
                val ageMin = minutesSince(nowTs, therapyFallback.insulinTs) ?: 0L
                val freshness = if (ageMin <= spec.staleThresholdMin) "fresh" else "stale ${ageMin}m"
                "Insulin: ${String.format(Locale.US, "%.2f", it)} U (therapy fallback, ${formatTs(therapyFallback.insulinTs)}) [$freshness]"
            }
            "isf_value" -> profile?.isfMmolPerUnit?.let {
                "ISF: ${String.format(Locale.US, "%.2f", it)} mmol/L/U (profile estimate)"
            }
            "cr_value" -> profile?.crGramPerUnit?.let {
                "CR: ${String.format(Locale.US, "%.2f", it)} g/U (profile estimate)"
            }
            "uam_value", "uam_calculated_flag" -> profile?.uamObservedCount?.let {
                val carbs = profile.uamEstimatedRecentCarbsGrams
                "UAM: observed=$it, recent=${String.format(Locale.US, "%.1f", carbs)} g (profile analyzer)"
            }
            else -> null
        }
    }

    private fun shouldPreferFallback(
        spec: TelemetryCoverageSpec,
        sample: TelemetrySampleEntity
    ): Boolean {
        return when (spec.primaryKey) {
            "carbs_grams" -> {
                sample.key == "carbs_grams" &&
                    sample.source == "aaps_broadcast" &&
                    (sample.valueDouble ?: 0.0) <= 0.0
            }
            "insulin_units" -> {
                sample.key == "insulin_units" && sample.source == "aaps_broadcast"
            }
            else -> false
        }
    }

    private fun formatTelemetryLine(sample: TelemetrySampleEntity): String {
        val title = sample.key.replace('_', ' ')
        val value = sample.valueDouble?.let {
            if (sample.unit == "steps") String.format("%.0f", it) else String.format("%.2f", it)
        } ?: sample.valueText.orEmpty()
        val unit = sample.unit?.takeIf { it.isNotBlank() }?.let { " $it" }.orEmpty()
        return "$title: $value$unit (${sample.source}, ${formatTs(sample.timestamp)})"
    }

    private fun buildYesterdayProfileLines(
        glucose: List<GlucoseSampleEntity>,
        therapy: List<TherapyEventEntity>,
        telemetry: List<TelemetrySampleEntity>,
        nowTs: Long
    ): List<String> {
        val dayWindow = resolveDayWindow(nowTs)
        val glucoseYesterday = glucose.filter { it.timestamp in dayWindow.startYesterdayTs until dayWindow.startTodayTs }
        val therapyYesterday = therapy.filter { it.timestamp in dayWindow.startYesterdayTs until dayWindow.startTodayTs }
        val telemetryYesterday = telemetry.filter { it.timestamp in dayWindow.startYesterdayTs until dayWindow.startTodayTs }
        val dateLabel = dayWindow.yesterdayDate.toString()

        if (glucoseYesterday.size < 18) {
            return listOf("Yesterday ($dateLabel): insufficient glucose points (${glucoseYesterday.size})")
        }

        val estimator = ProfileEstimator(ProfileEstimatorConfig(lookbackDays = 1))
        val glucoseDomain = glucoseYesterday.map { it.toDomain() }
        val therapyDomain = therapyYesterday.mapNotNull { event ->
            runCatching { event.toDomain(container.gson) }.getOrNull()
        }
        val telemetrySignals = telemetryYesterday.map { sample ->
            TelemetrySignal(
                ts = sample.timestamp,
                key = sample.key,
                valueDouble = sample.valueDouble,
                valueText = sample.valueText
            )
        }
        val calculated = estimator.estimate(
            glucoseHistory = glucoseDomain,
            therapyEvents = therapyDomain,
            telemetrySignals = emptyList()
        )
        val hourlyCalculated = estimator.estimateHourly(
            glucoseHistory = glucoseDomain,
            therapyEvents = therapyDomain,
            telemetrySignals = emptyList()
        )
        val merged = estimator.estimate(
            glucoseHistory = glucoseDomain,
            therapyEvents = therapyDomain,
            telemetrySignals = telemetrySignals
        )
        val hourlyMerged = estimator.estimateHourly(
            glucoseHistory = glucoseDomain,
            therapyEvents = therapyDomain,
            telemetrySignals = telemetrySignals
        )

        return buildList {
            add("Yesterday ($dateLabel): CGM=${glucoseYesterday.size}, therapy=${therapyYesterday.size}, telemetry=${telemetryYesterday.size}")
            if (calculated == null) {
                add("History-only: insufficient correction/meal samples")
            } else {
                add(
                    "History-only (real): ISF=${String.format(Locale.US, "%.2f", calculated.isfMmolPerUnit)} mmol/L/U, " +
                        "CR=${String.format(Locale.US, "%.2f", calculated.crGramPerUnit)} g/U, " +
                        "conf=${String.format(Locale.US, "%.0f%%", calculated.confidence * 100)}, " +
                        "n=${calculated.sampleCount} (ISF=${calculated.isfSampleCount}, CR=${calculated.crSampleCount})"
                )
            }
            if (merged != null) {
                add(
                    "Merged with telemetry: ISF=${String.format(Locale.US, "%.2f", merged.isfMmolPerUnit)} mmol/L/U, " +
                    "CR=${String.format(Locale.US, "%.2f", merged.crGramPerUnit)} g/U, " +
                    "conf=${String.format(Locale.US, "%.0f%%", merged.confidence * 100)}"
                )
            }
            if (hourlyCalculated.isEmpty()) {
                add("Hourly history-only (real): insufficient hourly samples")
            } else {
                add("Hourly history-only (real): ${hourlyCalculated.size}/24 hours")
                hourlyCalculated.forEach { hour ->
                    val isfText = hour.isfMmolPerUnit?.let { String.format(Locale.US, "%.2f", it) } ?: "-"
                    val crText = hour.crGramPerUnit?.let { String.format(Locale.US, "%.2f", it) } ?: "-"
                    add(
                        "${String.format(Locale.US, "%02d:00", hour.hour)} " +
                            "ISF=$isfText, CR=$crText, " +
                            "conf=${String.format(Locale.US, "%.0f%%", hour.confidence * 100)}, " +
                            "n(ISF/CR)=${hour.isfSampleCount}/${hour.crSampleCount}"
                    )
                }
            }
            if (hourlyMerged.isNotEmpty()) {
                add("Hourly merged (OpenAPS+history): ${hourlyMerged.size}/24 hours")
                hourlyMerged.forEach { hour ->
                    val isfText = hour.isfMmolPerUnit?.let { String.format(Locale.US, "%.2f", it) } ?: "-"
                    val crText = hour.crGramPerUnit?.let { String.format(Locale.US, "%.2f", it) } ?: "-"
                    add(
                        "${String.format(Locale.US, "%02d:00", hour.hour)} " +
                            "ISF=$isfText, CR=$crText, " +
                            "conf=${String.format(Locale.US, "%.0f%%", hour.confidence * 100)}, " +
                            "n(ISF/CR)=${hour.isfSampleCount}/${hour.crSampleCount}"
                    )
                }
            }
        }
    }

    private fun buildActivitySummaryLines(
        telemetry: List<TelemetrySampleEntity>,
        nowTs: Long,
        activityPermissionGranted: Boolean,
        audits: List<AuditLogEntity>
    ): List<String> {
        val dayWindow = resolveDayWindow(nowTs)
        val todayStart = dayWindow.startTodayTs
        val yesterdayStart = dayWindow.startYesterdayTs
        val byKey = latestTelemetryByKey(telemetry, nowTs = nowTs)

        val stepsToday = maxMetricInRange(telemetry, "steps_count", todayStart, nowTs + 1)
        val stepsYesterday = maxMetricInRange(telemetry, "steps_count", yesterdayStart, todayStart)
        val distanceTodayKm = maxMetricInRange(telemetry, "distance_km", todayStart, nowTs + 1)
        val activeMinutesToday = maxMetricInRange(telemetry, "active_minutes", todayStart, nowTs + 1)
        val activeCaloriesToday = maxMetricInRange(telemetry, "calories_active_kcal", todayStart, nowTs + 1)
        val activityRatioCurrent = byKey["activity_ratio"].toNumericValue()
        val activityRatioAvg6h = averageMetricInRange(
            telemetry = telemetry,
            key = "activity_ratio",
            startTs = (nowTs - 6 * 60 * 60_000L).coerceAtLeast(0L),
            endTs = nowTs + 1
        )
        val latestLocalSensorTs = telemetry
            .asSequence()
            .filter { it.source == "local_sensor" }
            .maxOfOrNull { it.timestamp }
        val localSensorAgeMin = minutesSince(nowTs, latestLocalSensorTs)
        val latestHealthConnectTs = telemetry
            .asSequence()
            .filter { it.source == "health_connect" }
            .maxOfOrNull { it.timestamp }
        val healthConnectAgeMin = minutesSince(nowTs, latestHealthConnectTs)
        val latestHealthConnectAudit = audits
            .asSequence()
            .filter { it.message == "health_connect_activity_status" }
            .maxByOrNull { it.timestamp }
        val healthConnectState = latestHealthConnectAudit?.let { auditMetaField(it, "state") }
        val healthConnectMissingPermissions = latestHealthConnectAudit?.let { auditMetaField(it, "missingPermissions") }
        val activityLabel = byKey["activity_label"]?.valueText?.trim().takeIf { !it.isNullOrBlank() }

        val hasAnyMetric = listOfNotNull(
            stepsToday,
            stepsYesterday,
            distanceTodayKm,
            activeMinutesToday,
            activeCaloriesToday,
            activityRatioCurrent,
            activityRatioAvg6h
        ).isNotEmpty() || activityLabel != null || latestLocalSensorTs != null

        return buildList {
            add(
                "Activity permission: " + if (activityPermissionGranted) {
                    "granted"
                } else {
                    "missing (grant ACTIVITY_RECOGNITION)"
                }
            )
            add(
                "Local sensor stream: " + if (latestLocalSensorTs == null) {
                    "no data yet"
                } else {
                    "${formatTs(latestLocalSensorTs)} (age ${minutesLabel(localSensorAgeMin)})"
                }
            )
            add(
                "Health Connect stream: " + if (latestHealthConnectTs == null) {
                    formatHealthConnectStatus(healthConnectState, healthConnectMissingPermissions)
                } else {
                    "${formatTs(latestHealthConnectTs)} (age ${minutesLabel(healthConnectAgeMin)})"
                }
            )
            if (!hasAnyMetric) {
                add("No steps/activity telemetry yet")
                return@buildList
            }
            add(
                "Steps today: ${stepsToday?.let { String.format(Locale.US, "%.0f", it) } ?: "-"} | " +
                    "yesterday: ${stepsYesterday?.let { String.format(Locale.US, "%.0f", it) } ?: "-"}"
            )
            add(
                "Activity ratio: current=${activityRatioCurrent?.let { String.format(Locale.US, "%.2f", it) } ?: "-"}, " +
                    "6h avg=${activityRatioAvg6h?.let { String.format(Locale.US, "%.2f", it) } ?: "-"}"
            )
            add(
                "Active minutes today: ${activeMinutesToday?.let { String.format(Locale.US, "%.0f min", it) } ?: "-"}"
            )
            add(
                "Distance today: ${distanceTodayKm?.let { String.format(Locale.US, "%.2f km", it) } ?: "-"}"
            )
            add(
                "Active calories today: ${activeCaloriesToday?.let { String.format(Locale.US, "%.0f kcal", it) } ?: "-"}"
            )
            activityLabel?.let { add("Activity label: $it") }
        }
    }

    private fun formatHealthConnectStatus(state: String?, missingPermissions: String?): String {
        return when {
            state == null -> "no data yet (grant Health Connect read permissions)"
            state == "ok" -> "available"
            state == "permission_missing" -> {
                if (missingPermissions.isNullOrBlank()) {
                    "permission missing"
                } else {
                    "permission missing: $missingPermissions"
                }
            }
            state.startsWith("sdk_unavailable") -> "sdk unavailable"
            state == "client_unavailable" -> "client unavailable"
            state == "permission_check_failed" -> "permission check failed"
            state == "sync_failed" -> "sync failed"
            else -> state
        }
    }

    private fun isActivityRecognitionGranted(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return true
        return ContextCompat.checkSelfPermission(
            getApplication(),
            Manifest.permission.ACTIVITY_RECOGNITION
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun maxMetricInRange(
        telemetry: List<TelemetrySampleEntity>,
        key: String,
        startTs: Long,
        endTs: Long
    ): Double? {
        return telemetry
            .asSequence()
            .filter { it.key == key && it.timestamp in startTs until endTs }
            .mapNotNull { it.toNumericValue() }
            .maxOrNull()
    }

    private fun averageMetricInRange(
        telemetry: List<TelemetrySampleEntity>,
        key: String,
        startTs: Long,
        endTs: Long
    ): Double? {
        val values = telemetry
            .asSequence()
            .filter { it.key == key && it.timestamp in startTs until endTs }
            .mapNotNull { it.toNumericValue() }
            .toList()
        if (values.isEmpty()) return null
        return values.average()
    }

    private data class DayWindow(
        val yesterdayDate: LocalDate,
        val startYesterdayTs: Long,
        val startTodayTs: Long
    )

    private fun resolveDayWindow(nowTs: Long): DayWindow {
        val zone = ZoneId.systemDefault()
        val today = Instant.ofEpochMilli(nowTs).atZone(zone).toLocalDate()
        val yesterday = today.minusDays(1)
        return DayWindow(
            yesterdayDate = yesterday,
            startYesterdayTs = yesterday.atStartOfDay(zone).toInstant().toEpochMilli(),
            startTodayTs = today.atStartOfDay(zone).toInstant().toEpochMilli()
        )
    }

    private companion object {
        private const val TLS_DIAGNOSTIC_WINDOW_MINUTES = 15
        private const val TLS_DIAGNOSTIC_WINDOW_MS = TLS_DIAGNOSTIC_WINDOW_MINUTES * 60_000L
        private const val LOCAL_NS_START_FAILURE_WINDOW_MS = 60 * 60_000L
        private val CUMULATIVE_ACTIVITY_KEYS = setOf(
            "steps_count",
            "distance_km",
            "active_minutes",
            "calories_active_kcal"
        )
    }

}

private fun ForecastEntity.toDomainForecast(): io.aaps.copilot.domain.model.Forecast =
    io.aaps.copilot.domain.model.Forecast(
        ts = timestamp,
        horizonMinutes = horizonMinutes,
        valueMmol = valueMmol,
        ciLow = ciLow,
        ciHigh = ciHigh,
        modelVersion = modelVersion
    )

private data class AapsTlsCompatibility(
    val installed: Boolean,
    val targetSdk: Int?,
    val networkSecurityConfigRes: Int?,
    val likelyRejectsUserCa: Boolean
)

data class MainUiState(
    val nightscoutUrl: String = "",
    val cloudUrl: String = "",
    val exportUri: String? = null,
    val killSwitch: Boolean = false,
    val localNightscoutEnabled: Boolean = false,
    val localNightscoutPort: Int = 17580,
    val resolvedNightscoutUrl: String = "",
    val localBroadcastIngestEnabled: Boolean = true,
    val strictBroadcastSenderValidation: Boolean = false,
    val localCommandFallbackEnabled: Boolean = false,
    val localCommandPackage: String = "info.nightscout.androidaps",
    val localCommandAction: String = "info.nightscout.client.NEW_TREATMENT",
    val insulinProfileId: String = "NOVORAPID",
    val baseTargetMmol: Double = 5.5,
    val postHypoThresholdMmol: Double = 3.0,
    val postHypoDeltaThresholdMmol5m: Double = 0.20,
    val postHypoTargetMmol: Double = 4.4,
    val postHypoDurationMinutes: Int = 60,
    val postHypoLookbackMinutes: Int = 90,
    val adaptiveControllerEnabled: Boolean = true,
    val adaptiveControllerPriority: Int = 120,
    val adaptiveControllerRetargetMinutes: Int = 5,
    val adaptiveControllerSafetyProfile: String = "BALANCED",
    val adaptiveControllerStaleMaxMinutes: Int = 15,
    val adaptiveControllerMaxActions6h: Int = 4,
    val adaptiveControllerMaxStepMmol: Double = 0.25,
    val latestGlucoseMmol: Double? = null,
    val glucoseDelta: Double? = null,
    val forecast5m: Double? = null,
    val forecast30m: Double? = null,
    val forecast60m: Double? = null,
    val calculatedUamActive: Boolean? = null,
    val calculatedUamConfidence: Double? = null,
    val calculatedUamCarbsGrams: Double? = null,
    val calculatedUamDelta5Mmol: Double? = null,
    val calculatedUamRise15Mmol: Double? = null,
    val calculatedUamRise30Mmol: Double? = null,
    val lastRuleState: String? = null,
    val lastRuleId: String? = null,
    val controllerState: String? = null,
    val controllerReason: String? = null,
    val controllerConfidence: Double? = null,
    val controllerNextTarget: Double? = null,
    val controllerDurationMinutes: Int? = null,
    val controllerForecast30: Double? = null,
    val controllerForecast60: Double? = null,
    val controllerWeightedError: Double? = null,
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
    val yesterdayProfileLines: List<String> = emptyList(),
    val activityLines: List<String> = emptyList(),
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
    val adaptiveAuditLines: List<String> = emptyList(),
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
