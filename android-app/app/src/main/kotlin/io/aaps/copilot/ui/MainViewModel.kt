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
import io.aaps.copilot.data.local.entity.UamInferenceEventEntity
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
import io.aaps.copilot.domain.isfcr.IsfCrRealtimeSnapshot
import io.aaps.copilot.domain.isfcr.IsfCrRuntimeMode
import io.aaps.copilot.domain.isfcr.PhysioContextTag
import io.aaps.copilot.domain.predict.UamInferenceState
import io.aaps.copilot.domain.predict.BaselineComparator
import io.aaps.copilot.domain.predict.ForecastQualityEvaluator
import io.aaps.copilot.domain.predict.InsulinActionProfileId
import io.aaps.copilot.domain.predict.ProfileEstimator
import io.aaps.copilot.domain.predict.ProfileEstimatorConfig
import io.aaps.copilot.domain.predict.TelemetrySignal
import io.aaps.copilot.domain.predict.UamTagCodec
import io.aaps.copilot.domain.predict.UamExportMode
import io.aaps.copilot.domain.rules.AdaptiveTargetControllerRule
import io.aaps.copilot.scheduler.WorkScheduler
import io.aaps.copilot.service.LocalNightscoutServiceController
import io.aaps.copilot.service.LocalNightscoutTls
import io.aaps.copilot.ui.foundation.screens.AnalyticsUiState
import io.aaps.copilot.ui.foundation.screens.AppHealthUiState
import io.aaps.copilot.ui.foundation.screens.AuditItemUi
import io.aaps.copilot.ui.foundation.screens.AuditWindowUi
import io.aaps.copilot.ui.foundation.screens.AuditUiState
import io.aaps.copilot.ui.foundation.screens.ForecastLayerState
import io.aaps.copilot.ui.foundation.screens.ForecastRangeUi
import io.aaps.copilot.ui.foundation.screens.ForecastUiState
import io.aaps.copilot.ui.foundation.screens.OverviewUiState
import io.aaps.copilot.ui.foundation.screens.PhysioTagJournalItemUi
import io.aaps.copilot.ui.foundation.screens.SafetyUiState
import io.aaps.copilot.ui.foundation.screens.SettingsUiState
import io.aaps.copilot.ui.foundation.screens.UamUiState
import io.aaps.copilot.ui.foundation.screens.toAnalyticsUiState
import io.aaps.copilot.ui.foundation.screens.toAppHealthUiState
import io.aaps.copilot.ui.foundation.screens.toAuditUiState
import io.aaps.copilot.ui.foundation.screens.toForecastUiState
import io.aaps.copilot.ui.foundation.screens.toOverviewUiState
import io.aaps.copilot.ui.foundation.screens.toSafetyUiState
import io.aaps.copilot.ui.foundation.screens.toSettingsUiState
import io.aaps.copilot.ui.foundation.screens.toUamUiState
import java.net.URI
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID
import kotlin.math.roundToInt
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
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
    private val forecastRangeState = MutableStateFlow(ForecastRangeUi.H3)
    private val forecastLayersState = MutableStateFlow(ForecastLayerState())
    private val auditWindowState = MutableStateFlow(AuditWindowUi.H24)
    private val auditOnlyErrorsState = MutableStateFlow(false)
    private val proModeState = MutableStateFlow(false)
    private val verboseLogsState = MutableStateFlow(false)
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
        db.baselineDao().observeLatest(limit = 600),
        db.ruleExecutionDao().observeLatest(limit = 20),
        db.actionCommandDao().observeLatest(limit = 40),
        db.auditLogDao().observeLatest(limit = 50),
        db.telemetryDao().observeLatest(limit = 12_000),
        container.analyticsRepository.observePatterns(),
        container.analyticsRepository.observeProfileEstimate(),
        db.profileEstimateDao().observeHistory(limit = 20_000),
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
        insightsFilterState,
        db.uamInferenceEventDao().observeLatest(limit = 300),
        container.analyticsRepository.observeIsfCrSnapshot(),
        container.analyticsRepository.observeIsfCrHistory(limit = 20_000),
        container.isfCrRepository.observeRecentTags(sinceTs = 0L)
    ) { values ->
        @Suppress("UNCHECKED_CAST")
        val glucose = GlucoseSanitizer.filterEntities(values[0] as List<GlucoseSampleEntity>)
        @Suppress("UNCHECKED_CAST")
        val therapy = TherapySanitizer.filterEntities(values[1] as List<TherapyEventEntity>)
        @Suppress("UNCHECKED_CAST")
        val forecasts = values[2] as List<ForecastEntity>
        val latestForecastByHorizon = ForecastSnapshotResolver.resolveLatestByHorizon(forecasts)
        val latestForecast5Row = latestForecastByHorizon[5]
        val latestForecast30Row = latestForecastByHorizon[30]
        val latestForecast60Row = latestForecastByHorizon[60]
        @Suppress("UNCHECKED_CAST")
        val baseline = values[3] as List<BaselinePointEntity>
        @Suppress("UNCHECKED_CAST")
        val ruleExec = values[4] as List<RuleExecutionEntity>
        @Suppress("UNCHECKED_CAST")
        val actionCommands = values[5] as List<ActionCommandEntity>
        @Suppress("UNCHECKED_CAST")
        val audits = values[6] as List<AuditLogEntity>
        @Suppress("UNCHECKED_CAST")
        val telemetry = values[7] as List<TelemetrySampleEntity>
        @Suppress("UNCHECKED_CAST")
        val patterns = values[8] as List<PatternWindowEntity>
        val profile = values[9] as ProfileEstimateEntity?
        @Suppress("UNCHECKED_CAST")
        val profileHistory = values[10] as List<ProfileEstimateEntity>
        @Suppress("UNCHECKED_CAST")
        val profileSegments = values[11] as List<ProfileSegmentEstimateEntity>
        @Suppress("UNCHECKED_CAST")
        val syncStates = values[12] as List<SyncStateEntity>
        val settings = values[13] as AppSettings
        val autoConnect = values[14] as AutoConnectUi?
        val message = values[15] as String?
        val dryRun = values[16] as DryRunUi?
        val cloudReplay = values[17] as CloudReplayUiModel?
        val cloudJobs = values[18] as CloudJobsUiModel?
        val analysisHistory = values[19] as CloudAnalysisHistoryUiModel?
        val analysisTrend = values[20] as CloudAnalysisTrendUiModel?
        val insightsFilter = values[21] as InsightsFilterUi
        @Suppress("UNCHECKED_CAST")
        val uamEvents = values[22] as List<UamInferenceEventEntity>
        val isfCrSnapshot = values[23] as IsfCrRealtimeSnapshot?
        @Suppress("UNCHECKED_CAST")
        val isfCrHistory = values[24] as List<IsfCrRealtimeSnapshot>
        @Suppress("UNCHECKED_CAST")
        val physioTags = values[25] as List<PhysioContextTag>

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
        val lastInfusionSetChangeTs = therapy
            .filter { it.type == "infusion_set_change" || it.type == "site_change" || it.type == "cannula_change" }
            .maxOfOrNull { it.timestamp }
        val lastSensorChangeTs = therapy
            .filter { it.type == "sensor_change" }
            .maxOfOrNull { it.timestamp }
        val lastInsulinRefillTs = therapy
            .filter { it.type == "insulin_refill" || it.type == "insulin_change" || it.type == "reservoir_change" }
            .maxOfOrNull { it.timestamp }
        val lastPumpBatteryChangeTs = therapy
            .filter { it.type == "pump_battery_change" || it.type == "battery_change" || it.type == "battery_replacement" }
            .maxOfOrNull { it.timestamp }
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
            add("Infusion set change: ${formatTs(lastInfusionSetChangeTs)}")
            add("Sensor change: ${formatTs(lastSensorChangeTs)}")
            add("Insulin refill: ${formatTs(lastInsulinRefillTs)}")
            add("Pump battery change: ${formatTs(lastPumpBatteryChangeTs)}")
            addAll(tlsDiagnosticLines)
        }
        val replacementHistoryLines = buildReplacementHistoryLines(
            therapy = therapy,
            nowTs = now
        )

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
        val isfCrDeepLines = buildIsfCrDeepLines(
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
        val glucoseHistoryPoints = sortedGlucose
            .filter { it.timestamp >= now - 24 * 60 * 60_000L }
            .map { GlucoseHistoryRowUi(timestamp = it.timestamp, valueMmol = it.mmol) }
        val forecast5Latest = latestForecast5Row?.valueMmol ?: latestForecastValue(forecasts, 5)
        val forecast30Latest = latestForecast30Row?.valueMmol ?: latestForecastValue(forecasts, 30)
        val forecast60Latest = latestForecast60Row?.valueMmol ?: latestForecastValue(forecasts, 60)
        val forecast5CiLow = latestForecast5Row?.ciLow
        val forecast5CiHigh = latestForecast5Row?.ciHigh
        val forecast30CiLow = latestForecast30Row?.ciLow
        val forecast30CiHigh = latestForecast30Row?.ciHigh
        val forecast60CiLow = latestForecast60Row?.ciLow
        val forecast60CiHigh = latestForecast60Row?.ciHigh
        val latestIobUnits = (
            telemetryByKey["iob_effective_units"].toNumericValue()
                ?: telemetryByKey["iob_units"].toNumericValue()
            )
        val latestIobRealUnits = telemetryByKey["iob_real_units"].toNumericValue()
        val latestCobGrams = (
            telemetryByKey["cob_effective_grams"].toNumericValue()
                ?: telemetryByKey["cob_grams"].toNumericValue()
            )
            ?.coerceIn(0.0, settings.carbComputationMaxGrams.coerceIn(20.0, 60.0))
        val insulinRealOnsetMinutes = telemetryByKey["insulin_real_onset_min"].toNumericValue()
        val insulinRealProfileCurveCompact = telemetryByKey["insulin_profile_real_curve_compact"]
            ?.valueText
            ?.trim()
            ?.takeIf { it.isNotBlank() }
        val insulinRealProfileUpdatedTs = telemetryByKey["insulin_profile_real_updated_ts"]
            .toNumericValue()
            ?.toLong()
        val insulinRealProfileConfidence = telemetryByKey["insulin_profile_real_confidence"]
            .toNumericValue()
            ?.coerceIn(0.0, 1.0)
        val insulinRealProfileSamples = telemetryByKey["insulin_profile_real_samples"]
            .toNumericValue()
            ?.toInt()
            ?.coerceAtLeast(0)
        val insulinRealProfileOnsetMinutes = telemetryByKey["insulin_profile_real_onset_min"]
            .toNumericValue()
        val insulinRealProfilePeakMinutes = telemetryByKey["insulin_profile_real_peak_min"]
            .toNumericValue()
        val insulinRealProfileScale = telemetryByKey["insulin_profile_real_scale"]
            .toNumericValue()
        val insulinRealProfileStatus = telemetryByKey["insulin_profile_real_status"]
            ?.valueText
            ?.trim()
            ?.takeIf { it.isNotBlank() }
        val latestActivityRatio = telemetryByKey["activity_ratio"].toNumericValue()
        val latestStepsCount = telemetryByKey["steps_count"].toNumericValue()
        val calculatedUamFlag = telemetryByKey["uam_calculated_flag"].toNumericValue()
        val calculatedUamConfidence = telemetryByKey["uam_calculated_confidence"].toNumericValue()
        val calculatedUamCarbs = telemetryByKey["uam_calculated_carbs_grams"].toNumericValue()
        val calculatedUamDelta5 = telemetryByKey["uam_calculated_delta5_mmol"].toNumericValue()
        val calculatedUamRise15 = telemetryByKey["uam_calculated_rise15_mmol"].toNumericValue()
        val calculatedUamRise30 = telemetryByKey["uam_calculated_rise30_mmol"].toNumericValue()
        val uci0Mmol5m = telemetryByKey["uam_uci0_mmol5"].toNumericValue() ?: calculatedUamDelta5
        val inferredUamFlag = telemetryByKey["uam_inferred_flag"].toNumericValue()
        val inferredUamConfidence = telemetryByKey["uam_inferred_confidence"].toNumericValue()
        val inferredUamCarbs = telemetryByKey["uam_inferred_carbs_grams"].toNumericValue()
        val inferredUamIngestionTs = telemetryByKey["uam_inferred_ingestion_ts"].toNumericValue()?.toLong()
        val inferredUamBoostMode = telemetryByKey["uam_inferred_boost_mode"].toNumericValue()
        val inferredUamManualCob = telemetryByKey["uam_manual_cob_grams"].toNumericValue()
        val inferredUamLastGAbs = telemetryByKey["uam_inferred_gabs_last5_g"].toNumericValue()
        val dailyReportGeneratedAtTs = listOf(
            telemetryByKey["daily_report_matched_samples"],
            telemetryByKey["daily_report_forecast_rows"],
            telemetryByKey["daily_report_mae_5m"],
            telemetryByKey["daily_report_mae_30m"],
            telemetryByKey["daily_report_mae_60m"]
        ).firstNotNullOfOrNull { it?.timestamp }
        val dailyReportMatchedSamples = telemetryByKey["daily_report_matched_samples"].toNumericValue()?.roundToInt()
        val dailyReportForecastRows = telemetryByKey["daily_report_forecast_rows"].toNumericValue()?.roundToInt()
        val dailyReportMarkdownPath = telemetryByKey["daily_report_markdown_path"]?.valueText
        val dailyReportPeriodStartUtc = telemetryByKey["daily_report_period_start"]?.valueText
        val dailyReportPeriodEndUtc = telemetryByKey["daily_report_period_end"]?.valueText
        val dailyReportMetrics = listOf(5, 30, 60).mapNotNull { horizon ->
            val mae = telemetryByKey["daily_report_mae_${horizon}m"].toNumericValue()
            val rmse = telemetryByKey["daily_report_rmse_${horizon}m"].toNumericValue()
            val mardPct = telemetryByKey["daily_report_mard_${horizon}m_pct"].toNumericValue()
            val bias = telemetryByKey["daily_report_bias_${horizon}m"].toNumericValue()
            val sampleCount = telemetryByKey["daily_report_n_${horizon}m"].toNumericValue()?.roundToInt()
            val ciCoveragePct = telemetryByKey["daily_report_ci_coverage_${horizon}m_pct"].toNumericValue()
            val ciMeanWidth = telemetryByKey["daily_report_ci_width_${horizon}m"].toNumericValue()
            if (
                mae == null &&
                rmse == null &&
                mardPct == null &&
                bias == null &&
                sampleCount == null &&
                ciCoveragePct == null &&
                ciMeanWidth == null
            ) {
                null
            } else {
                DailyReportMetricUi(
                    horizonMinutes = horizon,
                    sampleCount = sampleCount,
                    mae = mae,
                    rmse = rmse,
                    mardPct = mardPct,
                    bias = bias,
                    ciCoveragePct = ciCoveragePct,
                    ciMeanWidth = ciMeanWidth
                )
            }
        }
        val dailyReportRecommendations = (1..3).mapNotNull { index ->
            telemetryByKey["daily_report_recommendation_$index"]
                ?.valueText
                ?.trim()
                ?.takeIf { it.isNotBlank() }
        }
        val dailyReportIsfCrDroppedEvents = telemetryByKey["daily_report_isfcr_dropped_event_count"]
            .toNumericValue()
            ?.roundToInt()
        val dailyReportIsfCrDroppedTotal = telemetryByKey["daily_report_isfcr_dropped_total"]
            .toNumericValue()
            ?.roundToInt()
        val dailyReportIsfCrDroppedSource = telemetryByKey["daily_report_isfcr_dropped_source"]
            ?.valueText
            ?.trim()
            ?.takeIf { it.isNotBlank() }
        val dailyReportIsfCrTopReasons = telemetryByKey["daily_report_isfcr_dropped_top_reasons"]
            ?.valueText
            ?.trim()
            ?.takeIf { it.isNotBlank() }
        val dailyReportIsfCrQualityRisk = telemetryByKey["daily_report_isfcr_quality_risk"]
            ?.valueText
            ?.trim()
            ?.takeIf { it.isNotBlank() }
        val dailyReportIsfCrGapDropRatePct = telemetryByKey["daily_report_isfcr_cr_gap_drop_rate_pct"]
            .toNumericValue()
        val dailyReportIsfCrSensorDropRatePct = telemetryByKey["daily_report_isfcr_cr_sensor_drop_rate_pct"]
            .toNumericValue()
        val dailyReportIsfCrUamDropRatePct = telemetryByKey["daily_report_isfcr_cr_uam_drop_rate_pct"]
            .toNumericValue()
        val dailyReportIsfCrQualityLines = buildList {
            if (
                dailyReportIsfCrDroppedEvents != null ||
                dailyReportIsfCrDroppedTotal != null ||
                dailyReportIsfCrDroppedSource != null
            ) {
                add(
                    "source=${dailyReportIsfCrDroppedSource ?: "--"}, " +
                        "events=${dailyReportIsfCrDroppedEvents ?: 0}, " +
                        "dropped=${dailyReportIsfCrDroppedTotal ?: 0}"
                )
            }
            dailyReportIsfCrQualityRisk?.let { risk ->
                add("Quality risk: $risk")
            }
            if (
                dailyReportIsfCrGapDropRatePct != null ||
                dailyReportIsfCrSensorDropRatePct != null ||
                dailyReportIsfCrUamDropRatePct != null
            ) {
                add(
                    "CR integrity drop-rate: gap=${String.format(Locale.US, "%.1f", dailyReportIsfCrGapDropRatePct ?: 0.0)}%, " +
                        "sensorBlocked=${String.format(Locale.US, "%.1f", dailyReportIsfCrSensorDropRatePct ?: 0.0)}%, " +
                        "uamAmbiguity=${String.format(Locale.US, "%.1f", dailyReportIsfCrUamDropRatePct ?: 0.0)}%"
                )
            }
            dailyReportIsfCrTopReasons?.let { topReasons ->
                add("Top dropped reasons: $topReasons")
            }
        }
        val rollingReportLines = listOf(14, 30, 90).mapNotNull { days ->
            val prefix = "rolling_report_${days}d"
            val matched = telemetryByKey["${prefix}_matched_samples"].toNumericValue()?.roundToInt()
            val mae30 = telemetryByKey["${prefix}_mae_30m"].toNumericValue()
            val mae60 = telemetryByKey["${prefix}_mae_60m"].toNumericValue()
            val mard30 = telemetryByKey["${prefix}_mard_30m_pct"].toNumericValue()
            val mard60 = telemetryByKey["${prefix}_mard_60m_pct"].toNumericValue()
            val cov60 = telemetryByKey["${prefix}_ci_coverage_60m_pct"].toNumericValue()
            val width60 = telemetryByKey["${prefix}_ci_width_60m"].toNumericValue()
            val hasAny = listOf(
                matched?.toDouble(),
                mae30,
                mae60,
                mard30,
                mard60,
                cov60,
                width60
            ).any { it != null }
            if (!hasAny) {
                null
            } else {
                buildString {
                    append("${days}d: n=${matched ?: 0}")
                    mae30?.let { append(", MAE30=${String.format(Locale.US, "%.2f", it)}") }
                    mae60?.let { append(", MAE60=${String.format(Locale.US, "%.2f", it)}") }
                    mard30?.let { append(", MARD30=${String.format(Locale.US, "%.1f", it)}%") }
                    mard60?.let { append(", MARD60=${String.format(Locale.US, "%.1f", it)}%") }
                    cov60?.let { append(", CI60=${String.format(Locale.US, "%.1f", it)}%") }
                    width60?.let { append(", W60=${String.format(Locale.US, "%.2f", it)}") }
                }
            }
        }
        val replayFactor5 = telemetryByKey["daily_report_replay_top_factor_5m"]?.valueText?.trim()?.takeIf { it.isNotBlank() }
        val replayFactor30 = telemetryByKey["daily_report_replay_top_factor_30m"]?.valueText?.trim()?.takeIf { it.isNotBlank() }
        val replayFactor60 = telemetryByKey["daily_report_replay_top_factor_60m"]?.valueText?.trim()?.takeIf { it.isNotBlank() }
        val replayFactorHint5 = telemetryByKey["daily_report_replay_top_factor_hint_5m"]?.valueText?.trim()?.takeIf { it.isNotBlank() }
        val replayFactorHint30 = telemetryByKey["daily_report_replay_top_factor_hint_30m"]?.valueText?.trim()?.takeIf { it.isNotBlank() }
        val replayFactorHint60 = telemetryByKey["daily_report_replay_top_factor_hint_60m"]?.valueText?.trim()?.takeIf { it.isNotBlank() }
        val replayHotspot5 = telemetryByKey["daily_report_replay_hotspot_5m"]?.valueText?.trim()?.takeIf { it.isNotBlank() }
        val replayHotspot30 = telemetryByKey["daily_report_replay_hotspot_30m"]?.valueText?.trim()?.takeIf { it.isNotBlank() }
        val replayHotspot60 = telemetryByKey["daily_report_replay_hotspot_60m"]?.valueText?.trim()?.takeIf { it.isNotBlank() }
        val replayTopMiss5 = telemetryByKey["daily_report_replay_top_miss_5m"]?.valueText?.trim()?.takeIf { it.isNotBlank() }
        val replayTopMiss30 = telemetryByKey["daily_report_replay_top_miss_30m"]?.valueText?.trim()?.takeIf { it.isNotBlank() }
        val replayTopMiss60 = telemetryByKey["daily_report_replay_top_miss_60m"]?.valueText?.trim()?.takeIf { it.isNotBlank() }
        val replayTopPair5 = telemetryByKey["daily_report_replay_top_pair_5m"]?.valueText?.trim()?.takeIf { it.isNotBlank() }
        val replayTopPair30 = telemetryByKey["daily_report_replay_top_pair_30m"]?.valueText?.trim()?.takeIf { it.isNotBlank() }
        val replayTopPair60 = telemetryByKey["daily_report_replay_top_pair_60m"]?.valueText?.trim()?.takeIf { it.isNotBlank() }
        val replayTopPairHint5 = telemetryByKey["daily_report_replay_top_pair_hint_5m"]?.valueText?.trim()?.takeIf { it.isNotBlank() }
        val replayTopPairHint30 = telemetryByKey["daily_report_replay_top_pair_hint_30m"]?.valueText?.trim()?.takeIf { it.isNotBlank() }
        val replayTopPairHint60 = telemetryByKey["daily_report_replay_top_pair_hint_60m"]?.valueText?.trim()?.takeIf { it.isNotBlank() }
        val replayErrorCluster5 = telemetryByKey["daily_report_replay_error_cluster_5m"]?.valueText?.trim()?.takeIf { it.isNotBlank() }
        val replayErrorCluster30 = telemetryByKey["daily_report_replay_error_cluster_30m"]?.valueText?.trim()?.takeIf { it.isNotBlank() }
        val replayErrorCluster60 = telemetryByKey["daily_report_replay_error_cluster_60m"]?.valueText?.trim()?.takeIf { it.isNotBlank() }
        val replayErrorClusterHint5 = telemetryByKey["daily_report_replay_error_cluster_hint_5m"]?.valueText?.trim()?.takeIf { it.isNotBlank() }
        val replayErrorClusterHint30 = telemetryByKey["daily_report_replay_error_cluster_hint_30m"]?.valueText?.trim()?.takeIf { it.isNotBlank() }
        val replayErrorClusterHint60 = telemetryByKey["daily_report_replay_error_cluster_hint_60m"]?.valueText?.trim()?.takeIf { it.isNotBlank() }
        val replayDayTypeGap5 = telemetryByKey["daily_report_replay_daytype_gap_5m"]?.valueText?.trim()?.takeIf { it.isNotBlank() }
        val replayDayTypeGap30 = telemetryByKey["daily_report_replay_daytype_gap_30m"]?.valueText?.trim()?.takeIf { it.isNotBlank() }
        val replayDayTypeGap60 = telemetryByKey["daily_report_replay_daytype_gap_60m"]?.valueText?.trim()?.takeIf { it.isNotBlank() }
        val replayDayTypeGapHint5 = telemetryByKey["daily_report_replay_daytype_gap_hint_5m"]?.valueText?.trim()?.takeIf { it.isNotBlank() }
        val replayDayTypeGapHint30 = telemetryByKey["daily_report_replay_daytype_gap_hint_30m"]?.valueText?.trim()?.takeIf { it.isNotBlank() }
        val replayDayTypeGapHint60 = telemetryByKey["daily_report_replay_daytype_gap_hint_60m"]?.valueText?.trim()?.takeIf { it.isNotBlank() }
        val replayOverallFactors = telemetryByKey["daily_report_replay_top_factors_overall"]?.valueText?.trim()?.takeIf { it.isNotBlank() }
        val replayHotspots = parseReplayHotspotsJson(
            telemetryByKey["daily_report_replay_hotspots_json"]?.valueText
        )
        val replayFactorContributions = parseReplayFactorsJson(
            telemetryByKey["daily_report_replay_factors_json"]?.valueText
        )
        val replayFactorCoverage = parseReplayCoverageJson(
            telemetryByKey["daily_report_replay_factor_coverage_json"]?.valueText
        )
        val replayFactorRegimes = parseReplayRegimesJson(
            telemetryByKey["daily_report_replay_factor_regime_json"]?.valueText
        )
        val replayFactorPairs = parseReplayPairsJson(
            telemetryByKey["daily_report_replay_factor_pair_json"]?.valueText
        )
        val replayTopMisses = parseReplayTopMissJson(
            telemetryByKey["daily_report_replay_top_miss_json"]?.valueText
        )
        val replayErrorClusters = parseReplayErrorClustersJson(
            telemetryByKey["daily_report_replay_error_cluster_json"]?.valueText
        )
        val replayDayTypeGaps = parseReplayDayTypeGapsJson(
            telemetryByKey["daily_report_replay_daytype_gap_json"]?.valueText
        )
        val replayReportLines = buildList {
            replayOverallFactors?.let { add("Replay 24h top factors: $it") }
            replayFactor5?.let { add("Replay 24h 5m factor: $it") }
            replayFactor30?.let { add("Replay 24h 30m factor: $it") }
            replayFactor60?.let { add("Replay 24h 60m factor: $it") }
            replayFactorHint5?.let { add("Replay 24h 5m hint: $it") }
            replayFactorHint30?.let { add("Replay 24h 30m hint: $it") }
            replayFactorHint60?.let { add("Replay 24h 60m hint: $it") }
            replayHotspot5?.let { add("Replay 24h 5m hotspot: $it") }
            replayHotspot30?.let { add("Replay 24h 30m hotspot: $it") }
            replayHotspot60?.let { add("Replay 24h 60m hotspot: $it") }
            replayTopMiss5?.let { add("Replay 24h 5m top miss: $it") }
            replayTopMiss30?.let { add("Replay 24h 30m top miss: $it") }
            replayTopMiss60?.let { add("Replay 24h 60m top miss: $it") }
            replayTopPair5?.let { add("Replay 24h 5m top pair: $it") }
            replayTopPair30?.let { add("Replay 24h 30m top pair: $it") }
            replayTopPair60?.let { add("Replay 24h 60m top pair: $it") }
            replayTopPairHint5?.let { add("Replay 24h 5m pair hint: $it") }
            replayTopPairHint30?.let { add("Replay 24h 30m pair hint: $it") }
            replayTopPairHint60?.let { add("Replay 24h 60m pair hint: $it") }
            replayErrorCluster5?.let { add("Replay 24h 5m error cluster: $it") }
            replayErrorCluster30?.let { add("Replay 24h 30m error cluster: $it") }
            replayErrorCluster60?.let { add("Replay 24h 60m error cluster: $it") }
            replayErrorClusterHint5?.let { add("Replay 24h 5m cluster hint: $it") }
            replayErrorClusterHint30?.let { add("Replay 24h 30m cluster hint: $it") }
            replayErrorClusterHint60?.let { add("Replay 24h 60m cluster hint: $it") }
            replayDayTypeGap5?.let { add("Replay 24h 5m day-type gap: $it") }
            replayDayTypeGap30?.let { add("Replay 24h 30m day-type gap: $it") }
            replayDayTypeGap60?.let { add("Replay 24h 60m day-type gap: $it") }
            replayDayTypeGapHint5?.let { add("Replay 24h 5m day-type hint: $it") }
            replayDayTypeGapHint30?.let { add("Replay 24h 30m day-type hint: $it") }
            replayDayTypeGapHint60?.let { add("Replay 24h 60m day-type hint: $it") }
            replayFactorCoverage
                .filter { it.factor in setOf("COB", "IOB", "UAM", "CI") }
                .sortedWith(compareBy<DailyReportReplayCoverageUi> { it.horizonMinutes }.thenBy { it.factor })
                .forEach { row ->
                    add(
                        "Replay 24h ${row.horizonMinutes}m coverage ${row.factor}: " +
                            "${String.format(Locale.US, "%.1f", row.coveragePct)}% (n=${row.sampleCount})"
                    )
                }
            replayFactorRegimes
                .filter { it.factor in setOf("COB", "IOB", "UAM", "CI") }
                .sortedWith(
                    compareBy<DailyReportReplayRegimeUi> { it.horizonMinutes }
                        .thenBy { it.factor }
                        .thenBy {
                            when (it.bucket) {
                                "LOW" -> 0
                                "MID" -> 1
                                "HIGH" -> 2
                                else -> 3
                            }
                        }
                )
                .forEach { row ->
                    add(
                        "Replay 24h ${row.horizonMinutes}m ${row.factor} ${row.bucket}: " +
                            "mean=${String.format(Locale.US, "%.2f", row.meanFactorValue)}, " +
                            "MAE=${String.format(Locale.US, "%.2f", row.mae)}, " +
                            "MARD=${String.format(Locale.US, "%.1f", row.mardPct)}%, " +
                        "bias=${String.format(Locale.US, "%.2f", row.bias)} (n=${row.sampleCount})"
                    )
                }
            replayFactorPairs
                .filter { it.factorA in setOf("COB", "IOB", "UAM", "CI") && it.factorB in setOf("COB", "IOB", "UAM", "CI") }
                .sortedWith(
                    compareBy<DailyReportReplayPairUi> { it.horizonMinutes }
                        .thenBy { it.factorA }
                        .thenBy { it.factorB }
                        .thenBy {
                            when (it.bucketA) {
                                "LOW" -> 0
                                "HIGH" -> 1
                                else -> 2
                            }
                        }
                        .thenBy {
                            when (it.bucketB) {
                                "LOW" -> 0
                                "HIGH" -> 1
                                else -> 2
                            }
                        }
                )
                .forEach { row ->
                    add(
                        "Replay 24h ${row.horizonMinutes}m ${row.factorA}x${row.factorB} ${row.bucketA}x${row.bucketB}: " +
                            "meanA=${String.format(Locale.US, "%.2f", row.meanFactorA)}, " +
                            "meanB=${String.format(Locale.US, "%.2f", row.meanFactorB)}, " +
                            "MAE=${String.format(Locale.US, "%.2f", row.mae)}, " +
                            "MARD=${String.format(Locale.US, "%.1f", row.mardPct)}%, " +
                            "bias=${String.format(Locale.US, "%.2f", row.bias)} (n=${row.sampleCount})"
                    )
                }
        }
        val sensorQualityScore = telemetryByKey["sensor_quality_score"].toNumericValue()
        val sensorQualityBlocked = telemetryByKey["sensor_quality_blocked"].toNumericValue()?.let { it >= 0.5 }
        val sensorQualityReason = telemetryByKey["sensor_quality_reason"]?.valueText
        val sensorQualitySuspectFalseLow = telemetryByKey["sensor_quality_suspect_false_low"].toNumericValue()
            ?.let { it >= 0.5 }
        val smbContextSummary = buildSmbContextSummary(
            telemetryByKey = telemetryByKey,
            baseTargetMmol = settings.baseTargetMmol
        )
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
        val lastAction = actionCommands.firstOrNull()?.let { command ->
            val target = payloadDouble(command.payloadJson, "targetMmol")
            val duration = payloadDouble(command.payloadJson, "durationMinutes")?.toInt()
            val carbs = payloadDouble(command.payloadJson, "carbsGrams", "carbs", "grams")
            val summary = when {
                target != null && duration != null -> "target=${String.format(Locale.US, "%.2f", target)} mmol/L for ${duration}m"
                carbs != null -> "carbs=${String.format(Locale.US, "%.1f", carbs)} g"
                else -> command.payloadJson.take(100)
            }
            LastActionRowUi(
                type = command.type,
                status = command.status,
                timestamp = command.timestamp,
                tempTargetMmol = target,
                durationMinutes = duration,
                carbsGrams = carbs,
                idempotencyKey = command.idempotencyKey,
                payloadSummary = summary
            )
        }
        val manualCobActive = settings.uamDisableWhenManualCobActive &&
            ((inferredUamManualCob ?: 0.0) > settings.uamManualCobThresholdG)
        val uamEventRows = uamEvents
            .sortedByDescending { it.updatedAt }
            .map { event ->
                val manualNearby = hasManualCarbsNearby(
                    therapy = therapy,
                    centerTs = event.ingestionTs,
                    mergeWindowMinutes = settings.uamManualMergeWindowMinutes
                )
                val exportBlockedReason = when {
                    !settings.enableUamExportToAaps -> "export_disabled"
                    settings.dryRunExport -> "dry_run"
                    settings.uamExportMode == UamExportMode.OFF -> "mode_off"
                    settings.uamDisableIfManualCarbsNearby && manualNearby -> "manual_carbs_nearby"
                    manualCobActive -> "manual_cob_active"
                    event.state != UamInferenceState.CONFIRMED.name -> "event_not_confirmed"
                    else -> null
                }
                UamEventRowUi(
                    id = event.id,
                    state = event.state,
                    mode = event.mode,
                    createdAt = event.createdAt,
                    updatedAt = event.updatedAt,
                    ingestionTs = event.ingestionTs,
                    carbsDisplayG = event.carbsDisplayG,
                    confidence = event.confidence,
                    exportSeq = event.exportSeq,
                    exportedGrams = event.exportedGrams,
                    tag = UamTagCodec.buildTag(
                        eventId = event.id,
                        seq = event.exportSeq.coerceAtLeast(1),
                        mode = runCatching { io.aaps.copilot.domain.predict.UamMode.valueOf(event.mode) }
                            .getOrDefault(io.aaps.copilot.domain.predict.UamMode.NORMAL)
                    ),
                    manualCarbsNearby = manualNearby,
                    manualCobActive = manualCobActive,
                    exportBlockedReason = exportBlockedReason
                )
            }
        val auditRecords = buildAuditRecords(
            audits = audits,
            ruleExecutions = ruleExec,
            actions = actionCommands
        )
        val profileSegmentLines = profileSegments
            .sortedWith(compareBy<ProfileSegmentEstimateEntity> { it.dayType }.thenBy { it.timeSlot })
            .map { segment ->
                val isfText = segment.isfMmolPerUnit?.let { String.format("%.2f", it) } ?: "-"
                val crText = segment.crGramPerUnit?.let { String.format("%.2f", it) } ?: "-"
                "${segment.dayType} ${segment.timeSlot}: ISF=$isfText, CR=$crText, conf=${String.format("%.0f", segment.confidence * 100)}%, n(ISF/CR)=${segment.isfSampleCount}/${segment.crSampleCount}"
            }
        val profileHistoryPoints = if (isfCrHistory.isNotEmpty()) {
            isfCrHistory
                .asSequence()
                .map { row ->
                    IsfCrHistoryPointUi(
                        timestamp = row.ts,
                        isfMerged = row.isfBase,
                        crMerged = row.crBase,
                        isfCalculated = row.displayIsfEffForAnalytics(),
                        crCalculated = row.displayCrEffForAnalytics()
                    )
                }
                .sortedBy { it.timestamp }
                .toList()
        } else {
            profileHistory
                .asSequence()
                .map { row ->
                    IsfCrHistoryPointUi(
                        timestamp = row.timestamp,
                        isfMerged = row.isfMmolPerUnit,
                        crMerged = row.crGramPerUnit,
                        isfCalculated = row.calculatedIsfMmolPerUnit,
                        crCalculated = row.calculatedCrGramPerUnit
                    )
                }
                .sortedBy { it.timestamp }
                .toList()
        }
        val snapshotFactorLines = isfCrSnapshot?.factors
            ?.entries
            ?.sortedBy { it.key }
            ?.map { entry ->
                "${entry.key.replace('_', ' ')}=${String.format(Locale.US, "%.3f", entry.value)}"
            }
            .orEmpty()
        val runtimeDiagnosticsSnapshot = buildIsfCrRuntimeDiagnosticsSnapshot(audits)
        val runtimeDiagnosticsLines = buildIsfCrRuntimeDiagnosticsLines(runtimeDiagnosticsSnapshot)
        val activationGateLines = buildIsfCrActivationGateLines(audits, telemetryByKey)
        val isfCrDroppedReasons24h = buildIsfCrDroppedReasonSummaryLines(
            audits = audits,
            nowTs = now,
            windowMs = 24L * 60L * 60L * 1000L
        )
        val isfCrDroppedReasons7d = buildIsfCrDroppedReasonSummaryLines(
            audits = audits,
            nowTs = now,
            windowMs = 7L * 24L * 60L * 60L * 1000L
        )
        val isfCrWearImpact24h = buildIsfCrWearImpactSummaryLines(
            audits = audits,
            nowTs = now,
            windowMs = 24L * 60L * 60L * 1000L
        )
        val isfCrWearImpact7d = buildIsfCrWearImpactSummaryLines(
            audits = audits,
            nowTs = now,
            windowMs = 7L * 24L * 60L * 60L * 1000L
        )
        val physioTagLines = physioTags
            .sortedByDescending { it.tsStart }
            .take(12)
            .map { tag ->
                val severity = String.format(Locale.US, "%.2f", tag.severity.coerceIn(0.0, 1.0))
                "${tag.tagType} (sev=$severity, ${formatTs(tag.tsStart)}..${formatTs(tag.tsEnd)})"
            }
        val insightsFilterLabel = buildInsightsFilterLabel(insightsFilter)
        val deepIsfCrLinesCombined = buildList {
            isfCrSnapshot?.let { snapshot ->
                add(
                    "Realtime snapshot: mode=${snapshot.mode.name}, " +
                        "ISF=${String.format(Locale.US, "%.2f", snapshot.isfEff)}, " +
                        "CR=${String.format(Locale.US, "%.2f", snapshot.crEff)}, " +
                        "conf=${String.format(Locale.US, "%.0f%%", snapshot.confidence * 100)}, " +
                        "q=${String.format(Locale.US, "%.0f%%", snapshot.qualityScore * 100)}"
                )
                add(
                    "Realtime CI: ISF[${String.format(Locale.US, "%.2f", snapshot.ciIsfLow)}..${String.format(Locale.US, "%.2f", snapshot.ciIsfHigh)}], " +
                        "CR[${String.format(Locale.US, "%.2f", snapshot.ciCrLow)}..${String.format(Locale.US, "%.2f", snapshot.ciCrHigh)}]"
                )
            }
            addAll(runtimeDiagnosticsLines)
            addAll(snapshotFactorLines.take(6))
            addAll(isfCrDeepLines)
        }

        MainUiState().apply {
            this.nightscoutUrl = settings.nightscoutUrl
            this.cloudUrl = settings.cloudBaseUrl
            this.exportUri = settings.exportFolderUri
            this.killSwitch = settings.killSwitch
            this.localNightscoutEnabled = settings.localNightscoutEnabled
            this.localNightscoutPort = settings.localNightscoutPort
            this.resolvedNightscoutUrl = settings.resolvedNightscoutUrl()
            this.localBroadcastIngestEnabled = settings.localBroadcastIngestEnabled
            this.strictBroadcastSenderValidation = settings.strictBroadcastSenderValidation
            this.localCommandFallbackEnabled = settings.localCommandFallbackEnabled
            this.localCommandPackage = settings.localCommandPackage
            this.localCommandAction = settings.localCommandAction
            this.insulinProfileId = settings.insulinProfileId
            this.enableUamInference = settings.enableUamInference
            this.enableUamBoost = settings.enableUamBoost
            this.enableUamExportToAaps = settings.enableUamExportToAaps
            this.uamExportMode = settings.uamExportMode.name
            this.dryRunExport = settings.dryRunExport
            this.uamLearnedMultiplier = settings.uamLearnedMultiplier
            this.uamMinSnackG = settings.uamMinSnackG
            this.uamMaxSnackG = settings.uamMaxSnackG
            this.uamSnackStepG = settings.uamSnackStepG
            this.uamBackdateMinutesDefault = settings.uamBackdateMinutesDefault
            this.uamExportMinIntervalMin = settings.uamExportMinIntervalMin
            this.uamExportMaxBackdateMin = settings.uamExportMaxBackdateMin
            this.isfCrShadowMode = settings.isfCrShadowMode
            this.isfCrConfidenceThreshold = settings.isfCrConfidenceThreshold
            this.isfCrUseActivity = settings.isfCrUseActivity
            this.isfCrUseManualTags = settings.isfCrUseManualTags
            this.isfCrMinIsfEvidencePerHour = settings.isfCrMinIsfEvidencePerHour
            this.isfCrMinCrEvidencePerHour = settings.isfCrMinCrEvidencePerHour
            this.isfCrCrMaxGapMinutes = settings.isfCrCrMaxGapMinutes
            this.isfCrCrMaxSensorBlockedRatePct = settings.isfCrCrMaxSensorBlockedRatePct
            this.isfCrCrMaxUamAmbiguityRatePct = settings.isfCrCrMaxUamAmbiguityRatePct
            this.isfCrSnapshotRetentionDays = settings.isfCrSnapshotRetentionDays
            this.isfCrEvidenceRetentionDays = settings.isfCrEvidenceRetentionDays
            this.isfCrAutoActivationEnabled = settings.isfCrAutoActivationEnabled
            this.isfCrAutoActivationLookbackHours = settings.isfCrAutoActivationLookbackHours
            this.isfCrAutoActivationMinSamples = settings.isfCrAutoActivationMinSamples
            this.isfCrAutoActivationMinMeanConfidence = settings.isfCrAutoActivationMinMeanConfidence
            this.isfCrAutoActivationMaxMeanAbsIsfDeltaPct = settings.isfCrAutoActivationMaxMeanAbsIsfDeltaPct
            this.isfCrAutoActivationMaxMeanAbsCrDeltaPct = settings.isfCrAutoActivationMaxMeanAbsCrDeltaPct
            this.isfCrAutoActivationMinSensorQualityScore = settings.isfCrAutoActivationMinSensorQualityScore
            this.isfCrAutoActivationMinSensorFactor = settings.isfCrAutoActivationMinSensorFactor
            this.isfCrAutoActivationMaxWearConfidencePenalty = settings.isfCrAutoActivationMaxWearConfidencePenalty
            this.isfCrAutoActivationMaxSensorAgeHighRatePct = settings.isfCrAutoActivationMaxSensorAgeHighRatePct
            this.isfCrAutoActivationMaxSuspectFalseLowRatePct = settings.isfCrAutoActivationMaxSuspectFalseLowRatePct
            this.isfCrAutoActivationMinDayTypeRatio = settings.isfCrAutoActivationMinDayTypeRatio
            this.isfCrAutoActivationMaxDayTypeSparseRatePct = settings.isfCrAutoActivationMaxDayTypeSparseRatePct
            this.isfCrAutoActivationRequireDailyQualityGate = settings.isfCrAutoActivationRequireDailyQualityGate
            this.isfCrAutoActivationDailyRiskBlockLevel = settings.isfCrAutoActivationDailyRiskBlockLevel
            this.isfCrAutoActivationMinDailyMatchedSamples = settings.isfCrAutoActivationMinDailyMatchedSamples
            this.isfCrAutoActivationMaxDailyMae30Mmol = settings.isfCrAutoActivationMaxDailyMae30Mmol
            this.isfCrAutoActivationMaxDailyMae60Mmol = settings.isfCrAutoActivationMaxDailyMae60Mmol
            this.isfCrAutoActivationMaxHypoRatePct = settings.isfCrAutoActivationMaxHypoRatePct
            this.isfCrAutoActivationMinDailyCiCoverage30Pct = settings.isfCrAutoActivationMinDailyCiCoverage30Pct
            this.isfCrAutoActivationMinDailyCiCoverage60Pct = settings.isfCrAutoActivationMinDailyCiCoverage60Pct
            this.isfCrAutoActivationMaxDailyCiWidth30Mmol = settings.isfCrAutoActivationMaxDailyCiWidth30Mmol
            this.isfCrAutoActivationMaxDailyCiWidth60Mmol = settings.isfCrAutoActivationMaxDailyCiWidth60Mmol
            this.isfCrAutoActivationRollingMinRequiredWindows = settings.isfCrAutoActivationRollingMinRequiredWindows
            this.isfCrAutoActivationRollingMaeRelaxFactor = settings.isfCrAutoActivationRollingMaeRelaxFactor
            this.isfCrAutoActivationRollingCiCoverageRelaxFactor = settings.isfCrAutoActivationRollingCiCoverageRelaxFactor
            this.isfCrAutoActivationRollingCiWidthRelaxFactor = settings.isfCrAutoActivationRollingCiWidthRelaxFactor
            this.baseTargetMmol = settings.baseTargetMmol
            this.postHypoThresholdMmol = settings.postHypoThresholdMmol
            this.postHypoDeltaThresholdMmol5m = settings.postHypoDeltaThresholdMmol5m
            this.postHypoTargetMmol = settings.postHypoTargetMmol
            this.postHypoDurationMinutes = settings.postHypoDurationMinutes
            this.postHypoLookbackMinutes = settings.postHypoLookbackMinutes
            this.adaptiveControllerEnabled = settings.adaptiveControllerEnabled
            this.adaptiveControllerPriority = settings.adaptiveControllerPriority
            this.adaptiveControllerRetargetMinutes = settings.adaptiveControllerRetargetMinutes
            this.adaptiveControllerSafetyProfile = settings.adaptiveControllerSafetyProfile
            this.adaptiveControllerStaleMaxMinutes = settings.adaptiveControllerStaleMaxMinutes
            this.adaptiveControllerMaxActions6h = settings.adaptiveControllerMaxActions6h
            this.adaptiveControllerMaxStepMmol = settings.adaptiveControllerMaxStepMmol
            this.latestDataAgeMinutes = latestDataAgeMinutes
            this.nightscoutSyncAgeMinutes = nightscoutAgeMinutes
            this.cloudPushBacklogMinutes = cloudPushBacklogMinutes
            this.latestGlucoseMmol = latest?.mmol
            this.glucoseDelta = if (latest != null && prev != null) latest.mmol - prev.mmol else null
            this.latestIobUnits = latestIobUnits
            this.latestIobRealUnits = latestIobRealUnits
            this.latestCobGrams = latestCobGrams
            this.insulinRealOnsetMinutes = insulinRealOnsetMinutes
            this.insulinRealProfileCurveCompact = insulinRealProfileCurveCompact
            this.insulinRealProfileUpdatedTs = insulinRealProfileUpdatedTs
            this.insulinRealProfileConfidence = insulinRealProfileConfidence
            this.insulinRealProfileSamples = insulinRealProfileSamples
            this.insulinRealProfileOnsetMinutes = insulinRealProfileOnsetMinutes
            this.insulinRealProfilePeakMinutes = insulinRealProfilePeakMinutes
            this.insulinRealProfileScale = insulinRealProfileScale
            this.insulinRealProfileStatus = insulinRealProfileStatus
            this.latestActivityRatio = latestActivityRatio
            this.latestStepsCount = latestStepsCount
            this.forecast5m = forecast5Latest
            this.forecast5mCiLow = forecast5CiLow
            this.forecast5mCiHigh = forecast5CiHigh
            this.forecast30m = forecast30Latest
            this.forecast30mCiLow = forecast30CiLow
            this.forecast30mCiHigh = forecast30CiHigh
            this.forecast60m = forecast60Latest
            this.forecast60mCiLow = forecast60CiLow
            this.forecast60mCiHigh = forecast60CiHigh
            this.calculatedUamActive = calculatedUamFlag?.let { it >= 0.5 }
            this.calculatedUamConfidence = calculatedUamConfidence
            this.calculatedUamCarbsGrams = calculatedUamCarbs
            this.calculatedUci0Mmol5m = uci0Mmol5m
            this.calculatedUamDelta5Mmol = calculatedUamDelta5
            this.calculatedUamRise15Mmol = calculatedUamRise15
            this.calculatedUamRise30Mmol = calculatedUamRise30
            this.inferredUamActive = inferredUamFlag?.let { it >= 0.5 }
            this.inferredUamConfidence = inferredUamConfidence
            this.inferredUamCarbsGrams = inferredUamCarbs
            this.inferredUamIngestionTs = inferredUamIngestionTs
            this.inferredUamBoostMode = inferredUamBoostMode?.let { it >= 0.5 }
            this.inferredUamManualCobGrams = inferredUamManualCob
            this.inferredUamLastGAbsGrams = inferredUamLastGAbs
            this.uamEventRows = uamEventRows
            this.sensorQualityScore = sensorQualityScore
            this.sensorQualityBlocked = sensorQualityBlocked
            this.sensorQualityReason = sensorQualityReason
            this.sensorQualitySuspectFalseLow = sensorQualitySuspectFalseLow
            this.smbContextSummary = smbContextSummary
            this.lastRuleState = ruleExec.firstOrNull()?.state
            this.lastRuleId = ruleExec.firstOrNull()?.ruleId
            this.controllerState = latestAdaptiveExecution?.state
            this.controllerReason = controllerReason
            this.controllerConfidence = controllerConfidence
            this.controllerNextTarget = controllerAction?.targetMmol
            this.controllerDurationMinutes = controllerAction?.durationMinutes
            this.controllerForecast30 = forecast30Latest
            this.controllerForecast60 = forecast60Latest
            this.controllerWeightedError = controllerWeightedError
            this.profileIsf = profile?.calculatedIsfMmolPerUnit ?: profile?.isfMmolPerUnit
            this.profileCr = profile?.calculatedCrGramPerUnit ?: profile?.crGramPerUnit
            this.profileConfidence = profile?.calculatedConfidence ?: profile?.confidence
            this.profileSamples = if ((profile?.calculatedSampleCount ?: 0) > 0) profile?.calculatedSampleCount else profile?.sampleCount
            this.profileIsfSamples = if ((profile?.calculatedIsfSampleCount ?: 0) > 0) profile?.calculatedIsfSampleCount else profile?.isfSampleCount
            this.profileCrSamples = if ((profile?.calculatedCrSampleCount ?: 0) > 0) profile?.calculatedCrSampleCount else profile?.crSampleCount
            this.profileTelemetryIsfSamples = profile?.telemetryIsfSampleCount
            this.profileTelemetryCrSamples = profile?.telemetryCrSampleCount
            this.profileUamObservedCount = profile?.uamObservedCount
            this.profileUamFilteredIsfSamples = profile?.uamFilteredIsfSamples
            this.profileUamEpisodes = profile?.uamEpisodeCount
            this.profileUamCarbsGrams = profile?.uamEstimatedCarbsGrams
            this.profileUamRecentCarbsGrams = profile?.uamEstimatedRecentCarbsGrams
            this.profileCalculatedIsf = profile?.calculatedIsfMmolPerUnit
            this.profileCalculatedCr = profile?.calculatedCrGramPerUnit
            this.profileCalculatedConfidence = profile?.calculatedConfidence
            this.profileCalculatedSamples = profile?.calculatedSampleCount
            this.profileCalculatedIsfSamples = profile?.calculatedIsfSampleCount
            this.profileCalculatedCrSamples = profile?.calculatedCrSampleCount
            this.profileLookbackDays = profile?.lookbackDays
            this.isfCrRealtimeMode = isfCrSnapshot?.mode?.name
            this.isfCrRealtimeConfidence = isfCrSnapshot?.confidence
            this.isfCrRealtimeQualityScore = isfCrSnapshot?.qualityScore
            this.isfCrRealtimeIsfEff = isfCrSnapshot?.displayIsfEffForAnalytics()
            this.isfCrRealtimeCrEff = isfCrSnapshot?.displayCrEffForAnalytics()
            this.isfCrRealtimeIsfBase = isfCrSnapshot?.isfBase
            this.isfCrRealtimeCrBase = isfCrSnapshot?.crBase
            this.isfCrRealtimeCiIsfLow = isfCrSnapshot?.ciIsfLow
            this.isfCrRealtimeCiIsfHigh = isfCrSnapshot?.ciIsfHigh
            this.isfCrRealtimeCiCrLow = isfCrSnapshot?.ciCrLow
            this.isfCrRealtimeCiCrHigh = isfCrSnapshot?.ciCrHigh
            this.isfCrRealtimeFactors = snapshotFactorLines
            this.isfCrRuntimeDiagTs = runtimeDiagnosticsSnapshot?.realtimeTs
            this.isfCrRuntimeDiagMode = runtimeDiagnosticsSnapshot?.mode
            this.isfCrRuntimeDiagConfidence = runtimeDiagnosticsSnapshot?.confidence
            this.isfCrRuntimeDiagConfidenceThreshold = runtimeDiagnosticsSnapshot?.confidenceThreshold
            this.isfCrRuntimeDiagQualityScore = runtimeDiagnosticsSnapshot?.qualityScore
            this.isfCrRuntimeDiagUsedEvidence = runtimeDiagnosticsSnapshot?.usedEvidence
            this.isfCrRuntimeDiagDroppedEvidence = runtimeDiagnosticsSnapshot?.droppedEvidence
            this.isfCrRuntimeDiagDroppedReasons = runtimeDiagnosticsSnapshot?.droppedReasons
            this.isfCrRuntimeDiagCurrentDayType = runtimeDiagnosticsSnapshot?.currentDayType
            this.isfCrRuntimeDiagIsfBaseSource = runtimeDiagnosticsSnapshot?.isfBaseSource
            this.isfCrRuntimeDiagCrBaseSource = runtimeDiagnosticsSnapshot?.crBaseSource
            this.isfCrRuntimeDiagIsfDayTypeBaseAvailable = runtimeDiagnosticsSnapshot?.isfDayTypeBaseAvailable
            this.isfCrRuntimeDiagCrDayTypeBaseAvailable = runtimeDiagnosticsSnapshot?.crDayTypeBaseAvailable
            this.isfCrRuntimeDiagHourWindowIsfEvidence = runtimeDiagnosticsSnapshot?.hourWindowIsfEvidence
            this.isfCrRuntimeDiagHourWindowCrEvidence = runtimeDiagnosticsSnapshot?.hourWindowCrEvidence
            this.isfCrRuntimeDiagHourWindowIsfSameDayType = runtimeDiagnosticsSnapshot?.hourWindowIsfSameDayType
            this.isfCrRuntimeDiagHourWindowCrSameDayType = runtimeDiagnosticsSnapshot?.hourWindowCrSameDayType
            this.isfCrRuntimeDiagMinIsfEvidencePerHour = runtimeDiagnosticsSnapshot?.minIsfEvidencePerHour
            this.isfCrRuntimeDiagMinCrEvidencePerHour = runtimeDiagnosticsSnapshot?.minCrEvidencePerHour
            this.isfCrRuntimeDiagCrMaxGapMinutes = runtimeDiagnosticsSnapshot?.crMaxGapMinutes
            this.isfCrRuntimeDiagCrMaxSensorBlockedRatePct = runtimeDiagnosticsSnapshot?.crMaxSensorBlockedRatePct
            this.isfCrRuntimeDiagCrMaxUamAmbiguityRatePct = runtimeDiagnosticsSnapshot?.crMaxUamAmbiguityRatePct
            this.isfCrRuntimeDiagCoverageHoursIsf = runtimeDiagnosticsSnapshot?.coverageHoursIsf
            this.isfCrRuntimeDiagCoverageHoursCr = runtimeDiagnosticsSnapshot?.coverageHoursCr
            this.isfCrRuntimeDiagReasons = runtimeDiagnosticsSnapshot?.realtimeReasons
            this.isfCrRuntimeDiagLowConfidenceTs = runtimeDiagnosticsSnapshot?.lowConfidenceTs
            this.isfCrRuntimeDiagLowConfidenceReasons = runtimeDiagnosticsSnapshot?.lowConfidenceReasons
            this.isfCrRuntimeDiagFallbackTs = runtimeDiagnosticsSnapshot?.fallbackTs
            this.isfCrRuntimeDiagFallbackReasons = runtimeDiagnosticsSnapshot?.fallbackReasons
            this.isfCrActivationGateLines = activationGateLines
            this.isfCrDroppedReasons24hLines = isfCrDroppedReasons24h
            this.isfCrDroppedReasons7dLines = isfCrDroppedReasons7d
            this.isfCrWearImpact24hLines = isfCrWearImpact24h
            this.isfCrWearImpact7dLines = isfCrWearImpact7d
            this.isfCrActiveTags = physioTagLines
            this.isfCrHistoryPoints = profileHistoryPoints
            this.isfCrHistoryLastUpdatedTs = profileHistoryPoints.lastOrNull()?.timestamp
            this.profileSegmentLines = profileSegmentLines
            this.yesterdayProfileLines = yesterdayProfileLines
            this.isfCrDeepLines = deepIsfCrLinesCombined
            this.activityLines = activityLines
            this.rulePostHypoEnabled = settings.rulePostHypoEnabled
            this.rulePatternEnabled = settings.rulePatternEnabled
            this.ruleSegmentEnabled = settings.ruleSegmentEnabled
            this.rulePostHypoPriority = settings.rulePostHypoPriority
            this.rulePatternPriority = settings.rulePatternPriority
            this.ruleSegmentPriority = settings.ruleSegmentPriority
            this.rulePostHypoCooldownMinutes = settings.rulePostHypoCooldownMinutes
            this.rulePatternCooldownMinutes = settings.rulePatternCooldownMinutes
            this.ruleSegmentCooldownMinutes = settings.ruleSegmentCooldownMinutes
            this.patternMinSamplesPerWindow = settings.patternMinSamplesPerWindow
            this.patternMinActiveDaysPerWindow = settings.patternMinActiveDaysPerWindow
            this.patternLowRateTrigger = settings.patternLowRateTrigger
            this.patternHighRateTrigger = settings.patternHighRateTrigger
            this.analyticsLookbackDays = settings.analyticsLookbackDays
            this.maxActionsIn6Hours = settings.maxActionsIn6Hours
            this.staleDataMaxMinutes = settings.staleDataMaxMinutes
            this.safetyMinTargetMmol = settings.safetyMinTargetMmol
            this.safetyMaxTargetMmol = settings.safetyMaxTargetMmol
            this.carbAbsorptionMaxAgeMinutes = settings.carbAbsorptionMaxAgeMinutes
            this.carbComputationMaxGrams = settings.carbComputationMaxGrams
            this.weekdayHotHours = patterns
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
                }
            this.weekendHotHours = patterns
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
                }
            this.qualityMetrics = quality.map {
                QualityMetricUi(
                    horizonMinutes = it.horizonMinutes,
                    sampleCount = it.sampleCount,
                    mae = it.mae,
                    rmse = it.rmse,
                    mardPct = it.mardPct
                )
            }
            this.dailyReportGeneratedAtTs = dailyReportGeneratedAtTs
            this.dailyReportMatchedSamples = dailyReportMatchedSamples
            this.dailyReportForecastRows = dailyReportForecastRows
            this.dailyReportPeriodStartUtc = dailyReportPeriodStartUtc
            this.dailyReportPeriodEndUtc = dailyReportPeriodEndUtc
            this.dailyReportMarkdownPath = dailyReportMarkdownPath
            this.dailyReportMetrics = dailyReportMetrics
            this.dailyReportRecommendations = dailyReportRecommendations
            this.dailyReportIsfCrQualityLines = dailyReportIsfCrQualityLines
            this.dailyReportReplayHotspots = replayHotspots
            this.dailyReportReplayFactors = replayFactorContributions
            this.dailyReportReplayCoverage = replayFactorCoverage
            this.dailyReportReplayRegimes = replayFactorRegimes
            this.dailyReportReplayPairs = replayFactorPairs
            this.dailyReportReplayTopMisses = replayTopMisses
            this.dailyReportReplayErrorClusters = replayErrorClusters
            this.dailyReportReplayDayTypeGaps = replayDayTypeGaps
            this.dailyReportReplayTopFactorsOverall = replayOverallFactors
            this.rollingReportLines = rollingReportLines + replayReportLines
            this.baselineDeltaLines = baselineDeltas.map {
                "${it.horizonMinutes}m ${it.algorithm}: ${if (it.deltaMmol >= 0) "+" else ""}${"%.2f".format(it.deltaMmol)} mmol/L"
            }
            this.telemetryCoverageLines = telemetryCoverageLines
            this.telemetryLines = telemetryLines
            this.actionLines = actionLines
            this.autoConnectLines = autoConnect?.lines.orEmpty()
            this.transportStatusLines = transportStatusLines
            this.replacementHistoryLines = replacementHistoryLines
            this.syncStatusLines = syncStatusLines
            this.jobStatusLines = jobStatusLines
            this.insightsFilterLabel = insightsFilterLabel
            this.analysisHistoryLines = analysisHistoryLines
            this.analysisTrendLines = analysisTrendLines
            this.ruleCooldownLines = ruleCooldownLines
            this.dryRun = dryRun
            this.cloudReplay = cloudReplay
            this.adaptiveAuditLines = adaptiveAuditLines
            this.glucoseHistoryPoints = glucoseHistoryPoints
            this.lastAction = lastAction
            this.trend60ComponentMmol = telemetryByKey["forecast_trend_60_mmol"].toNumericValue()
            this.therapy60ComponentMmol = telemetryByKey["forecast_therapy_60_mmol"].toNumericValue()
            this.uam60ComponentMmol = telemetryByKey["forecast_uam_60_mmol"].toNumericValue()
            this.residualRoc0Mmol5m = telemetryByKey["forecast_residual_roc0_mmol5"].toNumericValue()
            this.sigmaEMmol5m = telemetryByKey["forecast_sigmae_mmol5"].toNumericValue()
            this.kfSigmaGMmol = telemetryByKey["forecast_kf_sigma_g_mmol"].toNumericValue()
            this.auditRecords = auditRecords
            this.auditLines = audits.map { "${it.level}: ${it.message}" }
            this.message = message
        }
    }
        .conflate()
        .flowOn(Dispatchers.Default)
        .stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = MainUiState()
    )

    val messageUiState: StateFlow<String?> = messageState.asStateFlow()

    val appHealthUiState: StateFlow<AppHealthUiState> = uiState
        .map { it.toAppHealthUiState() }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = MainUiState().toAppHealthUiState()
        )

    val overviewUiState: StateFlow<OverviewUiState> = combine(
        uiState,
        proModeState
    ) { state, isProMode ->
        state.toOverviewUiState(isProMode = isProMode)
    }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = OverviewUiState(loadState = io.aaps.copilot.ui.foundation.screens.ScreenLoadState.LOADING, isStale = true)
        )

    val forecastUiState: StateFlow<ForecastUiState> = combine(
        uiState,
        forecastRangeState,
        forecastLayersState,
        proModeState
    ) { state, range, layers, isProMode ->
        state.toForecastUiState(range = range, layers = layers, isProMode = isProMode)
    }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = ForecastUiState(loadState = io.aaps.copilot.ui.foundation.screens.ScreenLoadState.LOADING, isStale = true)
        )

    val uamUiState: StateFlow<UamUiState> = uiState
        .map { it.toUamUiState() }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = UamUiState(loadState = io.aaps.copilot.ui.foundation.screens.ScreenLoadState.LOADING, isStale = true)
        )

    val safetyUiState: StateFlow<SafetyUiState> = uiState
        .map { it.toSafetyUiState() }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = SafetyUiState(
                loadState = io.aaps.copilot.ui.foundation.screens.ScreenLoadState.LOADING,
                isStale = true,
                killSwitchEnabled = false,
                staleMinutesLimit = 10,
                hardBounds = "4.0..10.0",
                hardMinTargetMmol = 4.0,
                hardMaxTargetMmol = 10.0,
                adaptiveBounds = "4.0..9.0",
                baseTarget = 5.5,
                maxActionsIn6h = 3,
                localNightscoutEnabled = false,
                localNightscoutPort = 17580
            )
        )

    val auditUiState: StateFlow<AuditUiState> = combine(
        uiState,
        auditWindowState,
        auditOnlyErrorsState
    ) { state, window, onlyErrors ->
        state.toAuditUiState(
            window = window,
            onlyErrors = onlyErrors
        )
    }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = AuditUiState(loadState = io.aaps.copilot.ui.foundation.screens.ScreenLoadState.LOADING, isStale = true)
        )

    val analyticsUiState: StateFlow<AnalyticsUiState> = uiState
        .map { it.toAnalyticsUiState() }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = AnalyticsUiState(loadState = io.aaps.copilot.ui.foundation.screens.ScreenLoadState.LOADING, isStale = true)
        )

    val settingsUiState: StateFlow<SettingsUiState> = combine(
        container.settingsStore.settings,
        verboseLogsState,
        proModeState,
        container.isfCrRepository.observeRecentTags(
            sinceTs = System.currentTimeMillis() - PHYSIO_TAG_JOURNAL_LOOKBACK_MS
        )
    ) { settings, verbose, proMode, tags ->
        val nowTs = System.currentTimeMillis()
        val activeTags = tags
            .asSequence()
            .filter { it.tsEnd >= nowTs }
            .sortedBy { it.tsEnd }
            .map { tag ->
                val pct = (tag.severity * 100.0).coerceIn(0.0, 100.0)
                String.format(Locale.US, "%s %.0f%%", tag.tagType, pct)
            }
            .toList()
        val tagJournal = tags
            .sortedByDescending { it.tsStart }
            .take(PHYSIO_TAG_JOURNAL_MAX_ROWS)
            .map { tag ->
                PhysioTagJournalItemUi(
                    id = tag.id,
                    tagType = tag.tagType,
                    severity = tag.severity.coerceIn(0.0, 1.0),
                    tsStart = tag.tsStart,
                    tsEnd = tag.tsEnd,
                    isActive = tag.tsEnd >= nowTs,
                    source = tag.source,
                    note = tag.note
                )
            }
        SettingsUiState(
            loadState = io.aaps.copilot.ui.foundation.screens.ScreenLoadState.READY,
            isStale = false,
            errorText = null,
            proModeEnabled = proMode,
            baseTarget = settings.baseTargetMmol,
            nightscoutUrl = settings.nightscoutUrl,
            resolvedNightscoutUrl = settings.resolvedNightscoutUrl(),
            insulinProfileId = settings.insulinProfileId,
            localNightscoutEnabled = settings.localNightscoutEnabled,
            localBroadcastIngestEnabled = settings.localBroadcastIngestEnabled,
            strictBroadcastSenderValidation = settings.strictBroadcastSenderValidation,
            enableUamInference = settings.enableUamInference,
            enableUamBoost = settings.enableUamBoost,
            enableUamExportToAaps = settings.enableUamExportToAaps,
            uamExportMode = settings.uamExportMode.name,
            dryRunExport = settings.dryRunExport,
            uamMinSnackG = settings.uamMinSnackG,
            uamMaxSnackG = settings.uamMaxSnackG,
            uamSnackStepG = settings.uamSnackStepG,
            isfCrShadowMode = settings.isfCrShadowMode,
            isfCrConfidenceThreshold = settings.isfCrConfidenceThreshold,
            isfCrUseActivity = settings.isfCrUseActivity,
            isfCrUseManualTags = settings.isfCrUseManualTags,
            isfCrMinIsfEvidencePerHour = settings.isfCrMinIsfEvidencePerHour,
            isfCrMinCrEvidencePerHour = settings.isfCrMinCrEvidencePerHour,
            isfCrCrMaxGapMinutes = settings.isfCrCrMaxGapMinutes,
            isfCrCrMaxSensorBlockedRatePct = settings.isfCrCrMaxSensorBlockedRatePct,
            isfCrCrMaxUamAmbiguityRatePct = settings.isfCrCrMaxUamAmbiguityRatePct,
            isfCrSnapshotRetentionDays = settings.isfCrSnapshotRetentionDays,
            isfCrEvidenceRetentionDays = settings.isfCrEvidenceRetentionDays,
            isfCrAutoActivationEnabled = settings.isfCrAutoActivationEnabled,
            isfCrAutoActivationLookbackHours = settings.isfCrAutoActivationLookbackHours,
            isfCrAutoActivationMinSamples = settings.isfCrAutoActivationMinSamples,
            isfCrAutoActivationMinMeanConfidence = settings.isfCrAutoActivationMinMeanConfidence,
            isfCrAutoActivationMaxMeanAbsIsfDeltaPct = settings.isfCrAutoActivationMaxMeanAbsIsfDeltaPct,
            isfCrAutoActivationMaxMeanAbsCrDeltaPct = settings.isfCrAutoActivationMaxMeanAbsCrDeltaPct,
            isfCrAutoActivationMinSensorQualityScore = settings.isfCrAutoActivationMinSensorQualityScore,
            isfCrAutoActivationMinSensorFactor = settings.isfCrAutoActivationMinSensorFactor,
            isfCrAutoActivationMaxWearConfidencePenalty = settings.isfCrAutoActivationMaxWearConfidencePenalty,
            isfCrAutoActivationMaxSensorAgeHighRatePct = settings.isfCrAutoActivationMaxSensorAgeHighRatePct,
            isfCrAutoActivationMaxSuspectFalseLowRatePct = settings.isfCrAutoActivationMaxSuspectFalseLowRatePct,
            isfCrAutoActivationMinDayTypeRatio = settings.isfCrAutoActivationMinDayTypeRatio,
            isfCrAutoActivationMaxDayTypeSparseRatePct = settings.isfCrAutoActivationMaxDayTypeSparseRatePct,
            isfCrAutoActivationRequireDailyQualityGate = settings.isfCrAutoActivationRequireDailyQualityGate,
            isfCrAutoActivationDailyRiskBlockLevel = settings.isfCrAutoActivationDailyRiskBlockLevel,
            isfCrAutoActivationMinDailyMatchedSamples = settings.isfCrAutoActivationMinDailyMatchedSamples,
            isfCrAutoActivationMaxDailyMae30Mmol = settings.isfCrAutoActivationMaxDailyMae30Mmol,
            isfCrAutoActivationMaxDailyMae60Mmol = settings.isfCrAutoActivationMaxDailyMae60Mmol,
            isfCrAutoActivationMaxHypoRatePct = settings.isfCrAutoActivationMaxHypoRatePct,
            isfCrAutoActivationMinDailyCiCoverage30Pct = settings.isfCrAutoActivationMinDailyCiCoverage30Pct,
            isfCrAutoActivationMinDailyCiCoverage60Pct = settings.isfCrAutoActivationMinDailyCiCoverage60Pct,
            isfCrAutoActivationMaxDailyCiWidth30Mmol = settings.isfCrAutoActivationMaxDailyCiWidth30Mmol,
            isfCrAutoActivationMaxDailyCiWidth60Mmol = settings.isfCrAutoActivationMaxDailyCiWidth60Mmol,
            isfCrAutoActivationRollingMinRequiredWindows = settings.isfCrAutoActivationRollingMinRequiredWindows,
            isfCrAutoActivationRollingMaeRelaxFactor = settings.isfCrAutoActivationRollingMaeRelaxFactor,
            isfCrAutoActivationRollingCiCoverageRelaxFactor = settings.isfCrAutoActivationRollingCiCoverageRelaxFactor,
            isfCrAutoActivationRollingCiWidthRelaxFactor = settings.isfCrAutoActivationRollingCiWidthRelaxFactor,
            isfCrActiveTags = activeTags,
            isfCrTagJournal = tagJournal,
            adaptiveControllerEnabled = settings.adaptiveControllerEnabled,
            safetyMinTargetMmol = settings.safetyMinTargetMmol,
            safetyMaxTargetMmol = settings.safetyMaxTargetMmol,
            postHypoThresholdMmol = settings.postHypoThresholdMmol,
            postHypoTargetMmol = settings.postHypoTargetMmol,
            verboseLogsEnabled = verbose,
            retentionDays = settings.analyticsLookbackDays,
            warningText = "Not a medical device. Verify all therapy decisions manually."
        )
    }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = SettingsUiState(
                loadState = io.aaps.copilot.ui.foundation.screens.ScreenLoadState.LOADING,
                isStale = true,
                proModeEnabled = false,
                baseTarget = 5.5,
                nightscoutUrl = "",
                resolvedNightscoutUrl = "",
                insulinProfileId = "NOVORAPID",
                localNightscoutEnabled = false,
                localBroadcastIngestEnabled = true,
                strictBroadcastSenderValidation = false,
                enableUamInference = true,
                enableUamBoost = true,
                enableUamExportToAaps = false,
                uamExportMode = UamExportMode.OFF.name,
                dryRunExport = true,
                uamMinSnackG = 15,
                uamMaxSnackG = 60,
                uamSnackStepG = 5,
                isfCrShadowMode = true,
                isfCrConfidenceThreshold = 0.45,
                isfCrUseActivity = true,
                isfCrUseManualTags = true,
                isfCrMinIsfEvidencePerHour = 2,
                isfCrMinCrEvidencePerHour = 2,
                isfCrCrMaxGapMinutes = 30,
                isfCrCrMaxSensorBlockedRatePct = 25.0,
                isfCrCrMaxUamAmbiguityRatePct = 60.0,
                isfCrSnapshotRetentionDays = 365,
                isfCrEvidenceRetentionDays = 730,
                isfCrAutoActivationEnabled = false,
                isfCrAutoActivationLookbackHours = 24,
                isfCrAutoActivationMinSamples = 72,
                isfCrAutoActivationMinMeanConfidence = 0.65,
                isfCrAutoActivationMaxMeanAbsIsfDeltaPct = 25.0,
                isfCrAutoActivationMaxMeanAbsCrDeltaPct = 25.0,
                isfCrAutoActivationMinSensorQualityScore = 0.46,
                isfCrAutoActivationMinSensorFactor = 0.90,
                isfCrAutoActivationMaxWearConfidencePenalty = 0.12,
                isfCrAutoActivationMaxSensorAgeHighRatePct = 70.0,
                isfCrAutoActivationMaxSuspectFalseLowRatePct = 35.0,
                isfCrAutoActivationMinDayTypeRatio = 0.30,
                isfCrAutoActivationMaxDayTypeSparseRatePct = 75.0,
                isfCrAutoActivationRequireDailyQualityGate = true,
                isfCrAutoActivationDailyRiskBlockLevel = 3,
                isfCrAutoActivationMinDailyMatchedSamples = 120,
                isfCrAutoActivationMaxDailyMae30Mmol = 0.90,
                isfCrAutoActivationMaxDailyMae60Mmol = 1.40,
                isfCrAutoActivationMaxHypoRatePct = 6.0,
                isfCrAutoActivationMinDailyCiCoverage30Pct = 55.0,
                isfCrAutoActivationMinDailyCiCoverage60Pct = 55.0,
                isfCrAutoActivationMaxDailyCiWidth30Mmol = 1.80,
                isfCrAutoActivationMaxDailyCiWidth60Mmol = 2.60,
                isfCrAutoActivationRollingMinRequiredWindows = 2,
                isfCrAutoActivationRollingMaeRelaxFactor = 1.15,
                isfCrAutoActivationRollingCiCoverageRelaxFactor = 0.90,
                isfCrAutoActivationRollingCiWidthRelaxFactor = 1.25,
                isfCrActiveTags = emptyList(),
                adaptiveControllerEnabled = true,
                safetyMinTargetMmol = 4.0,
                safetyMaxTargetMmol = 10.0,
                postHypoThresholdMmol = 4.0,
                postHypoTargetMmol = 4.4,
                verboseLogsEnabled = false,
                retentionDays = 365,
                warningText = "Not a medical device. Verify all decisions."
            )
        )

    fun clearMessage() {
        messageState.value = null
    }

    fun runCycleNow() {
        runAutomationNow()
    }

    fun setForecastRange(range: ForecastRangeUi) {
        forecastRangeState.value = range
    }

    fun setForecastLayers(
        showTrend: Boolean? = null,
        showTherapy: Boolean? = null,
        showUam: Boolean? = null,
        showCi: Boolean? = null
    ) {
        val current = forecastLayersState.value
        forecastLayersState.value = current.copy(
            showTrend = showTrend ?: current.showTrend,
            showTherapy = showTherapy ?: current.showTherapy,
            showUam = showUam ?: current.showUam,
            showCi = showCi ?: current.showCi
        )
    }

    fun setAuditWindow(window: AuditWindowUi) {
        auditWindowState.value = window
    }

    fun setAuditOnlyErrors(enabled: Boolean) {
        auditOnlyErrorsState.value = enabled
    }

    fun setVerboseLogsEnabled(enabled: Boolean) {
        verboseLogsState.value = enabled
        messageState.value = if (enabled) {
            "Verbose UI logs enabled"
        } else {
            "Verbose UI logs disabled"
        }
    }

    fun setProModeEnabled(enabled: Boolean) {
        proModeState.value = enabled
        messageState.value = if (enabled) {
            "Pro mode enabled"
        } else {
            "Pro mode disabled"
        }
    }

    fun markUamEventCorrect(eventId: String) {
        updateUamEvent(eventId) { event ->
            event.copy(
                state = UamInferenceState.FINAL.name,
                learnedEligible = true,
                updatedAt = System.currentTimeMillis()
            )
        }
    }

    fun markUamEventWrong(eventId: String) {
        updateUamEvent(eventId) { event ->
            event.copy(
                state = UamInferenceState.MERGED.name,
                learnedEligible = false,
                updatedAt = System.currentTimeMillis()
            )
        }
    }

    fun mergeUamEventWithManualCarbs(eventId: String) {
        updateUamEvent(eventId) { event ->
            event.copy(
                state = UamInferenceState.MERGED.name,
                updatedAt = System.currentTimeMillis()
            )
        }
    }

    fun exportUamEventToAaps(eventId: String) {
        viewModelScope.launch {
            val dao = db.uamInferenceEventDao()
            val event = dao.byId(eventId)
            if (event == null) {
                messageState.value = "UAM event not found"
                return@launch
            }
            if (!uiState.value.enableUamExportToAaps) {
                messageState.value = "UAM export is disabled in settings"
                return@launch
            }
            if (uiState.value.dryRunExport) {
                messageState.value = "UAM export is in dry-run mode"
                return@launch
            }
            val updated = if (event.state == UamInferenceState.SUSPECTED.name) {
                event.copy(
                    state = UamInferenceState.CONFIRMED.name,
                    updatedAt = System.currentTimeMillis()
                )
            } else {
                event
            }
            dao.upsert(updated)
            runAutomationNow()
        }
    }

    private fun updateUamEvent(
        eventId: String,
        updater: (UamInferenceEventEntity) -> UamInferenceEventEntity
    ) {
        viewModelScope.launch {
            val dao = db.uamInferenceEventDao()
            val current = dao.byId(eventId)
            if (current == null) {
                messageState.value = "UAM event not found"
                return@launch
            }
            dao.upsert(updater(current))
            messageState.value = "UAM event updated"
        }
    }

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
            container.settingsStore.update {
                it.copy(
                    baseTargetMmol = mmol.coerceIn(
                        it.safetyMinTargetMmol,
                        it.safetyMaxTargetMmol
                    )
                )
            }
            recalculateAnalyticsAsync()
            messageState.value = "Base target updated"
        }
    }

    fun setNightscoutUrl(url: String) {
        viewModelScope.launch {
            container.settingsStore.update { it.copy(nightscoutUrl = url.trim()) }
            messageState.value = "Nightscout URL updated"
        }
    }

    fun setLocalNightscoutEnabled(enabled: Boolean) {
        viewModelScope.launch {
            val current = container.settingsStore.settings.first()
            val safePort = current.localNightscoutPort.coerceIn(1_024, 65_535)
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

    fun setInsulinProfile(profileRaw: String) {
        viewModelScope.launch {
            val normalized = InsulinActionProfileId.fromRaw(profileRaw).name
            container.settingsStore.update { it.copy(insulinProfileId = normalized) }
            triggerReactiveAutomationAsync()
            messageState.value = "Insulin profile set to $normalized"
        }
    }

    fun setUamExportMode(modeRaw: String) {
        viewModelScope.launch {
            val mode = runCatching { UamExportMode.valueOf(modeRaw.trim().uppercase(Locale.US)) }
                .getOrDefault(UamExportMode.CONFIRMED_ONLY)
            container.settingsStore.update { it.copy(uamExportMode = mode) }
            messageState.value = "UAM export mode updated: ${mode.name}"
        }
    }

    fun setUamRuntimeConfig(
        enableInference: Boolean,
        enableBoost: Boolean,
        enableExport: Boolean,
        exportModeRaw: String,
        dryRunExport: Boolean
    ) {
        viewModelScope.launch {
            val mode = runCatching { UamExportMode.valueOf(exportModeRaw.trim().uppercase(Locale.US)) }
                .getOrDefault(UamExportMode.OFF)
            container.settingsStore.update {
                it.copy(
                    enableUamInference = enableInference,
                    enableUamBoost = enableBoost,
                    enableUamExportToAaps = enableExport,
                    uamExportMode = mode,
                    dryRunExport = dryRunExport
                )
            }
            triggerReactiveAutomationAsync()
            messageState.value = "UAM runtime config updated"
        }
    }

    fun setUamInferenceTuning(
        minSnackG: Int,
        maxSnackG: Int,
        snackStepG: Int,
        backdateMinutes: Int,
        exportMinIntervalMin: Int,
        exportMaxBackdateMin: Int
    ) {
        viewModelScope.launch {
            val safeMin = minSnackG.coerceIn(5, 80)
            val safeMax = maxSnackG.coerceIn(safeMin, 120)
            val safeStep = snackStepG.coerceIn(1, 20)
            container.settingsStore.update {
                it.copy(
                    uamMinSnackG = safeMin,
                    uamMaxSnackG = safeMax,
                    uamSnackStepG = safeStep,
                    uamBackdateMinutesDefault = backdateMinutes.coerceIn(5, 120),
                    uamExportMinIntervalMin = exportMinIntervalMin.coerceIn(5, 60),
                    uamExportMaxBackdateMin = exportMaxBackdateMin.coerceIn(30, 360)
                )
            }
            triggerReactiveAutomationAsync()
            messageState.value = "UAM tuning updated"
        }
    }

    fun setUamSnackConfig(
        minSnackG: Int,
        maxSnackG: Int,
        snackStepG: Int
    ) {
        viewModelScope.launch {
            val current = container.settingsStore.settings.first()
            setUamInferenceTuning(
                minSnackG = minSnackG,
                maxSnackG = maxSnackG,
                snackStepG = snackStepG,
                backdateMinutes = current.uamBackdateMinutesDefault,
                exportMinIntervalMin = current.uamExportMinIntervalMin,
                exportMaxBackdateMin = current.uamExportMaxBackdateMin
            )
        }
    }

    fun setPostHypoThreshold(mmol: Double) {
        viewModelScope.launch {
            container.settingsStore.update {
                it.copy(postHypoThresholdMmol = mmol.coerceIn(4.0, 10.0))
            }
            messageState.value = "Post-hypo threshold updated"
        }
    }

    fun setPostHypoTarget(mmol: Double) {
        viewModelScope.launch {
            container.settingsStore.update {
                it.copy(postHypoTargetMmol = mmol.coerceIn(4.0, 10.0))
            }
            messageState.value = "Post-hypo target updated"
        }
    }

    fun setRetentionDays(days: Int) {
        viewModelScope.launch {
            val safeDays = days.coerceIn(30, 730)
            container.settingsStore.update { it.copy(analyticsLookbackDays = safeDays) }
            recalculateAnalyticsAsync()
            messageState.value = "Retention days updated"
        }
    }

    fun setIsfCrShadowMode(enabled: Boolean) {
        viewModelScope.launch {
            container.settingsStore.update { it.copy(isfCrShadowMode = enabled) }
            triggerReactiveAutomationAsync()
            messageState.value = if (enabled) {
                "ISF/CR engine set to SHADOW mode"
            } else {
                "ISF/CR engine set to ACTIVE mode"
            }
        }
    }

    fun setIsfCrConfidenceThreshold(value: Double) {
        viewModelScope.launch {
            container.settingsStore.update { it.copy(isfCrConfidenceThreshold = value.coerceIn(0.2, 0.95)) }
            triggerReactiveAutomationAsync()
            messageState.value = "ISF/CR confidence threshold updated"
        }
    }

    fun setIsfCrUseActivity(enabled: Boolean) {
        viewModelScope.launch {
            container.settingsStore.update { it.copy(isfCrUseActivity = enabled) }
            triggerReactiveAutomationAsync()
            messageState.value = if (enabled) {
                "ISF/CR activity factor enabled"
            } else {
                "ISF/CR activity factor disabled"
            }
        }
    }

    fun setIsfCrUseManualTags(enabled: Boolean) {
        viewModelScope.launch {
            container.settingsStore.update { it.copy(isfCrUseManualTags = enabled) }
            triggerReactiveAutomationAsync()
            messageState.value = if (enabled) {
                "ISF/CR manual tags enabled"
            } else {
                "ISF/CR manual tags disabled"
            }
        }
    }

    fun setIsfCrMinEvidencePerHour(minIsfEvidence: Int, minCrEvidence: Int) {
        viewModelScope.launch {
            container.settingsStore.update {
                it.copy(
                    isfCrMinIsfEvidencePerHour = minIsfEvidence.coerceIn(0, 12),
                    isfCrMinCrEvidencePerHour = minCrEvidence.coerceIn(0, 12)
                )
            }
            triggerReactiveAutomationAsync()
            messageState.value = "ISF/CR hourly evidence minimums updated"
        }
    }

    fun setIsfCrCrIntegrityGateSettings(
        maxGapMinutes: Int,
        maxSensorBlockedRatePct: Double,
        maxUamAmbiguityRatePct: Double
    ) {
        viewModelScope.launch {
            container.settingsStore.update {
                it.copy(
                    isfCrCrMaxGapMinutes = maxGapMinutes.coerceIn(10, 60),
                    isfCrCrMaxSensorBlockedRatePct = maxSensorBlockedRatePct.coerceIn(0.0, 100.0),
                    isfCrCrMaxUamAmbiguityRatePct = maxUamAmbiguityRatePct.coerceIn(0.0, 100.0)
                )
            }
            triggerReactiveAutomationAsync()
            messageState.value = "ISF/CR CR-window integrity thresholds updated"
        }
    }

    fun setIsfCrAutoActivationEnabled(enabled: Boolean) {
        viewModelScope.launch {
            container.settingsStore.update { it.copy(isfCrAutoActivationEnabled = enabled) }
            triggerReactiveAutomationAsync()
            messageState.value = if (enabled) {
                "ISF/CR shadow auto-activation enabled"
            } else {
                "ISF/CR shadow auto-activation disabled"
            }
        }
    }

    fun setIsfCrAutoActivationLookbackHours(hours: Int) {
        viewModelScope.launch {
            container.settingsStore.update { it.copy(isfCrAutoActivationLookbackHours = hours.coerceIn(6, 72)) }
            triggerReactiveAutomationAsync()
            messageState.value = "ISF/CR auto-activation lookback updated"
        }
    }

    fun setIsfCrAutoActivationMinSamples(samples: Int) {
        viewModelScope.launch {
            container.settingsStore.update { it.copy(isfCrAutoActivationMinSamples = samples.coerceIn(12, 288)) }
            triggerReactiveAutomationAsync()
            messageState.value = "ISF/CR auto-activation min samples updated"
        }
    }

    fun setIsfCrAutoActivationMinMeanConfidence(value: Double) {
        viewModelScope.launch {
            container.settingsStore.update {
                it.copy(isfCrAutoActivationMinMeanConfidence = value.coerceIn(0.2, 0.95))
            }
            triggerReactiveAutomationAsync()
            messageState.value = "ISF/CR auto-activation confidence target updated"
        }
    }

    fun setIsfCrAutoActivationMaxMeanAbsDeltaPct(
        isfPct: Double,
        crPct: Double
    ) {
        viewModelScope.launch {
            container.settingsStore.update {
                it.copy(
                    isfCrAutoActivationMaxMeanAbsIsfDeltaPct = isfPct.coerceIn(5.0, 100.0),
                    isfCrAutoActivationMaxMeanAbsCrDeltaPct = crPct.coerceIn(5.0, 100.0)
                )
            }
            triggerReactiveAutomationAsync()
            messageState.value = "ISF/CR auto-activation delta bounds updated"
        }
    }

    fun setIsfCrAutoActivationSensorThresholds(
        minSensorQualityScore: Double,
        minSensorFactor: Double,
        maxWearConfidencePenalty: Double,
        maxSensorAgeHighRatePct: Double,
        maxSuspectFalseLowRatePct: Double
    ) {
        viewModelScope.launch {
            container.settingsStore.update {
                it.copy(
                    isfCrAutoActivationMinSensorQualityScore = minSensorQualityScore.coerceIn(0.0, 1.0),
                    isfCrAutoActivationMinSensorFactor = minSensorFactor.coerceIn(0.0, 1.0),
                    isfCrAutoActivationMaxWearConfidencePenalty = maxWearConfidencePenalty.coerceIn(0.0, 1.0),
                    isfCrAutoActivationMaxSensorAgeHighRatePct = maxSensorAgeHighRatePct.coerceIn(0.0, 100.0),
                    isfCrAutoActivationMaxSuspectFalseLowRatePct = maxSuspectFalseLowRatePct.coerceIn(0.0, 100.0)
                )
            }
            triggerReactiveAutomationAsync()
            messageState.value = "ISF/CR auto-activation sensor gate updated"
        }
    }

    fun setIsfCrAutoActivationDayTypeThresholds(
        minDayTypeRatio: Double,
        maxDayTypeSparseRatePct: Double
    ) {
        viewModelScope.launch {
            container.settingsStore.update {
                it.copy(
                    isfCrAutoActivationMinDayTypeRatio = minDayTypeRatio.coerceIn(0.0, 1.0),
                    isfCrAutoActivationMaxDayTypeSparseRatePct = maxDayTypeSparseRatePct.coerceIn(0.0, 100.0)
                )
            }
            triggerReactiveAutomationAsync()
            messageState.value = "ISF/CR auto-activation day-type gate updated"
        }
    }

    fun setIsfCrAutoActivationRequireDailyQualityGate(enabled: Boolean) {
        viewModelScope.launch {
            container.settingsStore.update {
                it.copy(isfCrAutoActivationRequireDailyQualityGate = enabled)
            }
            triggerReactiveAutomationAsync()
            messageState.value = if (enabled) {
                "ISF/CR daily quality gate enabled"
            } else {
                "ISF/CR daily quality gate disabled"
            }
        }
    }

    fun setIsfCrAutoActivationDailyRiskBlockLevel(level: Int) {
        viewModelScope.launch {
            container.settingsStore.update {
                it.copy(isfCrAutoActivationDailyRiskBlockLevel = level.coerceIn(2, 3))
            }
            triggerReactiveAutomationAsync()
            messageState.value = "ISF/CR daily risk gate threshold updated"
        }
    }

    fun setIsfCrAutoActivationDailyQualityThresholds(
        minDailyMatchedSamples: Int,
        maxDailyMae30Mmol: Double,
        maxDailyMae60Mmol: Double,
        maxHypoRatePct: Double,
        minDailyCiCoverage30Pct: Double,
        minDailyCiCoverage60Pct: Double,
        maxDailyCiWidth30Mmol: Double,
        maxDailyCiWidth60Mmol: Double
    ) {
        viewModelScope.launch {
            container.settingsStore.update {
                it.copy(
                    isfCrAutoActivationMinDailyMatchedSamples = minDailyMatchedSamples.coerceIn(24, 720),
                    isfCrAutoActivationMaxDailyMae30Mmol = maxDailyMae30Mmol.coerceIn(0.3, 4.0),
                    isfCrAutoActivationMaxDailyMae60Mmol = maxDailyMae60Mmol.coerceIn(0.5, 6.0),
                    isfCrAutoActivationMaxHypoRatePct = maxHypoRatePct.coerceIn(0.5, 30.0),
                    isfCrAutoActivationMinDailyCiCoverage30Pct = minDailyCiCoverage30Pct.coerceIn(20.0, 99.0),
                    isfCrAutoActivationMinDailyCiCoverage60Pct = minDailyCiCoverage60Pct.coerceIn(20.0, 99.0),
                    isfCrAutoActivationMaxDailyCiWidth30Mmol = maxDailyCiWidth30Mmol.coerceIn(0.3, 6.0),
                    isfCrAutoActivationMaxDailyCiWidth60Mmol = maxDailyCiWidth60Mmol.coerceIn(0.5, 8.0)
                )
            }
            triggerReactiveAutomationAsync()
            messageState.value = "ISF/CR daily quality thresholds updated"
        }
    }

    fun setIsfCrAutoActivationRollingGateSettings(
        minRequiredWindows: Int,
        maeRelaxFactor: Double,
        ciCoverageRelaxFactor: Double,
        ciWidthRelaxFactor: Double
    ) {
        viewModelScope.launch {
            container.settingsStore.update {
                it.copy(
                    isfCrAutoActivationRollingMinRequiredWindows = minRequiredWindows.coerceIn(1, 3),
                    isfCrAutoActivationRollingMaeRelaxFactor = maeRelaxFactor.coerceIn(1.0, 1.5),
                    isfCrAutoActivationRollingCiCoverageRelaxFactor = ciCoverageRelaxFactor.coerceIn(0.70, 1.0),
                    isfCrAutoActivationRollingCiWidthRelaxFactor = ciWidthRelaxFactor.coerceIn(1.0, 1.5)
                )
            }
            triggerReactiveAutomationAsync()
            messageState.value = "ISF/CR rolling quality gate thresholds updated"
        }
    }

    fun setIsfCrRetention(snapshotDays: Int, evidenceDays: Int) {
        viewModelScope.launch {
            container.settingsStore.update {
                it.copy(
                    isfCrSnapshotRetentionDays = snapshotDays.coerceIn(30, 730),
                    isfCrEvidenceRetentionDays = evidenceDays.coerceIn(30, 1095)
                )
            }
            triggerReactiveAutomationAsync()
            messageState.value = "ISF/CR retention updated"
        }
    }

    fun addPhysioTag(tagType: String, severity: Double = 0.7, durationHours: Int = 6) {
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            val safeType = normalizePhysioTagType(tagType).ifBlank { return@launch }
            container.isfCrRepository.addOrUpdateTag(
                tag = PhysioContextTag(
                    id = "tag-${UUID.randomUUID()}",
                    tsStart = now,
                    tsEnd = now + durationHours.coerceIn(1, 48) * 60L * 60L * 1000L,
                    tagType = safeType,
                    severity = severity.coerceIn(0.1, 1.0),
                    source = "user",
                    note = "quick_tag:$safeType"
                )
            )
            triggerReactiveAutomationAsync()
            messageState.value = "Tag added: $safeType"
        }
    }

    fun closePhysioTag(tagId: String) {
        val safeId = tagId.trim()
        if (safeId.isEmpty()) return
        viewModelScope.launch {
            val closed = container.isfCrRepository.closeTag(tagId = safeId, nowTs = System.currentTimeMillis())
            if (closed) {
                triggerReactiveAutomationAsync()
                messageState.value = "Tag closed"
            } else {
                messageState.value = "Tag not found"
            }
        }
    }

    fun clearActivePhysioTags() {
        viewModelScope.launch {
            container.isfCrRepository.closeActiveTags(System.currentTimeMillis())
            triggerReactiveAutomationAsync()
            messageState.value = "Active physiology tags cleared"
        }
    }

    fun setKillSwitch(enabled: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
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

    fun setSafetyLimits(
        maxActionsIn6h: Int,
        staleDataMaxMinutes: Int,
        safetyMinTargetMmol: Double? = null,
        safetyMaxTargetMmol: Double? = null,
        carbAbsorptionMaxAgeMinutes: Int? = null,
        carbComputationMaxGrams: Double? = null
    ) {
        viewModelScope.launch {
            container.settingsStore.update {
                val nextSafetyMax = (safetyMaxTargetMmol ?: it.safetyMaxTargetMmol).coerceIn(4.2, 10.0)
                val nextSafetyMin = (safetyMinTargetMmol ?: it.safetyMinTargetMmol)
                    .coerceIn(4.0, 9.8)
                    .coerceAtMost(nextSafetyMax - 0.2)
                it.copy(
                    maxActionsIn6Hours = maxActionsIn6h.coerceIn(1, 10),
                    staleDataMaxMinutes = staleDataMaxMinutes.coerceIn(5, 60),
                    safetyMinTargetMmol = nextSafetyMin,
                    safetyMaxTargetMmol = nextSafetyMax,
                    baseTargetMmol = it.baseTargetMmol.coerceIn(nextSafetyMin, nextSafetyMax),
                    postHypoThresholdMmol = it.postHypoThresholdMmol.coerceIn(nextSafetyMin, nextSafetyMax),
                    postHypoTargetMmol = it.postHypoTargetMmol.coerceIn(nextSafetyMin, nextSafetyMax),
                    carbAbsorptionMaxAgeMinutes = (carbAbsorptionMaxAgeMinutes
                        ?: it.carbAbsorptionMaxAgeMinutes).coerceIn(60, 180),
                    carbComputationMaxGrams = (carbComputationMaxGrams
                        ?: it.carbComputationMaxGrams).coerceIn(20.0, 60.0)
                )
            }
            messageState.value = "Safety limits updated"
        }
    }

    fun setSafetyTargetBounds(minTargetMmol: Double, maxTargetMmol: Double) {
        viewModelScope.launch {
            container.settingsStore.update {
                val normalizedMax = maxTargetMmol.coerceIn(4.2, 10.0)
                val normalizedMin = minTargetMmol
                    .coerceIn(4.0, 9.8)
                    .coerceAtMost(normalizedMax - 0.2)
                it.copy(
                    safetyMinTargetMmol = normalizedMin,
                    safetyMaxTargetMmol = normalizedMax,
                    baseTargetMmol = it.baseTargetMmol.coerceIn(normalizedMin, normalizedMax),
                    postHypoThresholdMmol = it.postHypoThresholdMmol.coerceIn(normalizedMin, normalizedMax),
                    postHypoTargetMmol = it.postHypoTargetMmol.coerceIn(normalizedMin, normalizedMax)
                )
            }
            messageState.value = "Safety target bounds updated"
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
                    postHypoThresholdMmol = thresholdMmol.coerceIn(4.0, 10.0),
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
            recalculateAnalyticsAsync()
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
                    adaptiveControllerEnabled = enabled,
                    adaptiveControllerPriority = priority.coerceIn(0, 200),
                    adaptiveControllerRetargetMinutes = normalizedRetarget,
                    adaptiveControllerSafetyProfile = normalizedProfile,
                    adaptiveControllerStaleMaxMinutes = staleMaxMinutes.coerceIn(5, 60),
                    adaptiveControllerMaxActions6h = maxActions6h.coerceIn(1, 10),
                    adaptiveControllerMaxStepMmol = maxStepMmol.coerceIn(0.05, 1.00)
                )
            }
            messageState.value = "Adaptive controller settings updated"
        }
    }

    fun setAdaptiveControllerEnabled(enabled: Boolean) {
        viewModelScope.launch {
            container.settingsStore.update { it.copy(adaptiveControllerEnabled = enabled) }
            triggerReactiveAutomationAsync()
            messageState.value = if (enabled) {
                "Adaptive controller enabled"
            } else {
                "Adaptive controller disabled"
            }
        }
    }

    private fun triggerReactiveAutomationAsync() {
        viewModelScope.launch(Dispatchers.Default) {
            WorkScheduler.triggerReactiveAutomation(getApplication())
        }
    }

    private fun recalculateAnalyticsAsync() {
        viewModelScope.launch(Dispatchers.Default) {
            val settings = container.settingsStore.settings.first()
            container.analyticsRepository.recalculate(settings)
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
            val settings = container.settingsStore.settings.first()
            val safetyCapGrams = settings.carbComputationMaxGrams.coerceIn(20.0, 60.0)
            val carbsGrams = parseFlexibleDouble(carbsRaw)?.takeIf { it in 1.0..safetyCapGrams }
            if (carbsGrams == null) {
                messageState.value = "Manual carbs failed: invalid grams value (allowed 1..${String.format(Locale.US, "%.0f", safetyCapGrams)} g)"
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

    private fun hasManualCarbsNearby(
        therapy: List<TherapyEventEntity>,
        centerTs: Long,
        mergeWindowMinutes: Int
    ): Boolean {
        val windowMs = mergeWindowMinutes.coerceIn(5, 240) * 60_000L
        return therapy.any { event ->
            if (!eventTypeLooksLikeCarbs(event.type)) return@any false
            val carbs = payloadDouble(event.payloadJson, "grams", "carbs", "enteredCarbs", "mealCarbs")
                ?: return@any false
            carbs > 0.0 && kotlin.math.abs(event.timestamp - centerTs) <= windowMs
        }
    }

    private fun eventTypeLooksLikeCarbs(type: String): Boolean {
        val normalized = type.trim().lowercase(Locale.US)
        return normalized.contains("carb") || normalized.contains("meal")
    }

    private fun buildAuditRecords(
        audits: List<AuditLogEntity>,
        ruleExecutions: List<RuleExecutionEntity>,
        actions: List<ActionCommandEntity>
    ): List<AuditRecordRowUi> {
        val auditRows = audits.map { row ->
            AuditRecordRowUi(
                id = "audit:${row.id}",
                ts = row.timestamp,
                source = "audit",
                level = row.level,
                summary = row.message,
                context = row.metadataJson.take(220)
            )
        }
        val ruleRows = ruleExecutions.map { row ->
            val reason = parseRuleReasonsJson(row.reasonsJson).joinToString("; ").ifBlank { "-" }
            AuditRecordRowUi(
                id = "rule:${row.id}",
                ts = row.timestamp,
                source = "rule",
                level = if (row.state == "TRIGGERED") "INFO" else if (row.state == "BLOCKED") "WARN" else "INFO",
                summary = "${row.ruleId} ${row.state}",
                context = reason,
                payloadSummary = row.actionJson?.take(220)
            )
        }
        val actionRows = actions.map { row ->
            val summary = when (row.type.lowercase(Locale.US)) {
                "temp_target" -> {
                    val target = payloadDouble(row.payloadJson, "targetMmol")
                    val duration = payloadDouble(row.payloadJson, "durationMinutes")?.toInt()
                    "temp_target ${target?.let { String.format(Locale.US, "%.2f", it) } ?: "-"} mmol/L ${duration ?: "-"}m"
                }
                "carbs" -> {
                    val grams = payloadDouble(row.payloadJson, "carbsGrams", "carbs", "grams")
                    "carbs ${grams?.let { String.format(Locale.US, "%.1f", it) } ?: "-"} g"
                }
                else -> row.type
            }
            AuditRecordRowUi(
                id = "action:${row.id}",
                ts = row.timestamp,
                source = "action",
                level = when (row.status.uppercase(Locale.US)) {
                    "FAILED" -> "ERROR"
                    "PENDING" -> "WARN"
                    else -> "INFO"
                },
                summary = summary,
                context = row.payloadJson.take(220),
                idempotencyKey = row.idempotencyKey,
                payloadSummary = row.payloadJson.take(220)
            )
        }
        return (auditRows + ruleRows + actionRows).sortedByDescending { it.ts }
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

    private fun parseReplayHotspotsJson(raw: String?): List<DailyReportReplayHotspotUi> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val horizonMinutes = item.optInt("horizonMinutes", 0)
                    val dayType = item.optString("dayType", "").trim().uppercase(Locale.US)
                    val hour = item.optInt("hour", -1)
                    val sampleCount = item.optInt("sampleCount", 0)
                    val mae = item.optDouble("mae", Double.NaN)
                    val mardPct = item.optDouble("mardPct", Double.NaN)
                    val bias = item.optDouble("bias", Double.NaN)
                    if (
                        horizonMinutes <= 0 ||
                        dayType !in setOf("WEEKDAY", "WEEKEND") ||
                        hour !in 0..23 ||
                        sampleCount <= 0 ||
                        !mae.isFinite() ||
                        !mardPct.isFinite() ||
                        !bias.isFinite()
                    ) {
                        continue
                    }
                    add(
                        DailyReportReplayHotspotUi(
                            horizonMinutes = horizonMinutes,
                            hour = hour,
                            sampleCount = sampleCount,
                            mae = mae,
                            mardPct = mardPct,
                            bias = bias
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
            .sortedWith(
                compareBy<DailyReportReplayHotspotUi> { it.horizonMinutes }
                    .thenByDescending { it.mae }
            )
    }

    private fun parseReplayFactorsJson(raw: String?): List<DailyReportReplayFactorUi> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val horizonMinutes = item.optInt("horizonMinutes", 0)
                    val factor = item.optString("factor", "").trim()
                    val sampleCount = item.optInt("sampleCount", 0)
                    val corrAbsError = item.optDouble("corrAbsError", Double.NaN)
                    val maeHigh = item.optDouble("maeHigh", Double.NaN)
                    val maeLow = item.optDouble("maeLow", Double.NaN)
                    val upliftPct = item.optDouble("upliftPct", Double.NaN)
                    val contributionScore = item.optDouble("contributionScore", Double.NaN)
                    if (
                        horizonMinutes <= 0 ||
                        factor.isBlank() ||
                        sampleCount <= 0 ||
                        !corrAbsError.isFinite() ||
                        !maeHigh.isFinite() ||
                        !maeLow.isFinite() ||
                        !upliftPct.isFinite() ||
                        !contributionScore.isFinite()
                    ) {
                        continue
                    }
                    add(
                        DailyReportReplayFactorUi(
                            horizonMinutes = horizonMinutes,
                            factor = factor,
                            sampleCount = sampleCount,
                            corrAbsError = corrAbsError,
                            maeHigh = maeHigh,
                            maeLow = maeLow,
                            upliftPct = upliftPct,
                            contributionScore = contributionScore
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
            .sortedWith(
                compareBy<DailyReportReplayFactorUi> { it.horizonMinutes }
                    .thenByDescending { it.contributionScore }
            )
    }

    private fun parseReplayCoverageJson(raw: String?): List<DailyReportReplayCoverageUi> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val horizonMinutes = item.optInt("horizonMinutes", 0)
                    val factor = item.optString("factor", "").trim()
                    val sampleCount = item.optInt("sampleCount", 0)
                    val coveragePct = item.optDouble("coveragePct", Double.NaN)
                    if (
                        horizonMinutes <= 0 ||
                        factor.isBlank() ||
                        sampleCount < 0 ||
                        !coveragePct.isFinite()
                    ) {
                        continue
                    }
                    add(
                        DailyReportReplayCoverageUi(
                            horizonMinutes = horizonMinutes,
                            factor = factor,
                            sampleCount = sampleCount,
                            coveragePct = coveragePct
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
            .sortedWith(
                compareBy<DailyReportReplayCoverageUi> { it.horizonMinutes }
                    .thenBy { it.factor }
            )
    }

    private fun parseReplayRegimesJson(raw: String?): List<DailyReportReplayRegimeUi> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val horizonMinutes = item.optInt("horizonMinutes", 0)
                    val factor = item.optString("factor", "").trim()
                    val bucket = item.optString("bucket", "").trim().uppercase(Locale.US)
                    val sampleCount = item.optInt("sampleCount", 0)
                    val meanFactorValue = item.optDouble("meanFactorValue", Double.NaN)
                    val mae = item.optDouble("mae", Double.NaN)
                    val mardPct = item.optDouble("mardPct", Double.NaN)
                    val bias = item.optDouble("bias", Double.NaN)
                    if (
                        horizonMinutes <= 0 ||
                        factor.isBlank() ||
                        bucket !in setOf("LOW", "MID", "HIGH") ||
                        sampleCount <= 0 ||
                        !meanFactorValue.isFinite() ||
                        !mae.isFinite() ||
                        !mardPct.isFinite() ||
                        !bias.isFinite()
                    ) {
                        continue
                    }
                    add(
                        DailyReportReplayRegimeUi(
                            horizonMinutes = horizonMinutes,
                            factor = factor,
                            bucket = bucket,
                            sampleCount = sampleCount,
                            meanFactorValue = meanFactorValue,
                            mae = mae,
                            mardPct = mardPct,
                            bias = bias
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
            .sortedWith(
                compareBy<DailyReportReplayRegimeUi> { it.horizonMinutes }
                    .thenBy { it.factor }
                    .thenBy {
                        when (it.bucket) {
                            "LOW" -> 0
                            "MID" -> 1
                            "HIGH" -> 2
                            else -> 3
                        }
                    }
            )
    }

    private fun parseReplayPairsJson(raw: String?): List<DailyReportReplayPairUi> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val horizonMinutes = item.optInt("horizonMinutes", 0)
                    val factorA = item.optString("factorA", "").trim()
                    val factorB = item.optString("factorB", "").trim()
                    val bucketA = item.optString("bucketA", "").trim().uppercase(Locale.US)
                    val bucketB = item.optString("bucketB", "").trim().uppercase(Locale.US)
                    val sampleCount = item.optInt("sampleCount", 0)
                    val meanFactorA = item.optDouble("meanFactorA", Double.NaN)
                    val meanFactorB = item.optDouble("meanFactorB", Double.NaN)
                    val mae = item.optDouble("mae", Double.NaN)
                    val mardPct = item.optDouble("mardPct", Double.NaN)
                    val bias = item.optDouble("bias", Double.NaN)
                    if (
                        horizonMinutes <= 0 ||
                        factorA.isBlank() ||
                        factorB.isBlank() ||
                        bucketA !in setOf("LOW", "HIGH") ||
                        bucketB !in setOf("LOW", "HIGH") ||
                        sampleCount <= 0 ||
                        !meanFactorA.isFinite() ||
                        !meanFactorB.isFinite() ||
                        !mae.isFinite() ||
                        !mardPct.isFinite() ||
                        !bias.isFinite()
                    ) {
                        continue
                    }
                    add(
                        DailyReportReplayPairUi(
                            horizonMinutes = horizonMinutes,
                            factorA = factorA,
                            factorB = factorB,
                            bucketA = bucketA,
                            bucketB = bucketB,
                            sampleCount = sampleCount,
                            meanFactorA = meanFactorA,
                            meanFactorB = meanFactorB,
                            mae = mae,
                            mardPct = mardPct,
                            bias = bias
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
            .sortedWith(
                compareBy<DailyReportReplayPairUi> { it.horizonMinutes }
                    .thenBy { it.factorA }
                    .thenBy { it.factorB }
                    .thenBy {
                        when (it.bucketA) {
                            "LOW" -> 0
                            "HIGH" -> 1
                            else -> 2
                        }
                    }
                    .thenBy {
                        when (it.bucketB) {
                            "LOW" -> 0
                            "HIGH" -> 1
                            else -> 2
                        }
                    }
            )
    }

    private fun parseReplayTopMissJson(raw: String?): List<DailyReportReplayTopMissUi> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val horizonMinutes = item.optInt("horizonMinutes", 0)
                    val ts = item.optLong("ts", 0L)
                    val absError = item.optDouble("absError", Double.NaN)
                    val pred = item.optDouble("pred", Double.NaN)
                    val actual = item.optDouble("actual", Double.NaN)
                    val cob = item.optDouble("cob", Double.NaN)
                    val iob = item.optDouble("iob", Double.NaN)
                    val uam = item.optDouble("uam", Double.NaN)
                    val ciWidth = item.optDouble("ciWidth", Double.NaN)
                    val diaHours = item.optDouble("diaHours", Double.NaN)
                    val activity = item.optDouble("activity", Double.NaN)
                    val sensorQuality = item.optDouble("sensorQuality", Double.NaN)
                    if (
                        horizonMinutes <= 0 ||
                        ts <= 0L ||
                        !absError.isFinite() ||
                        !pred.isFinite() ||
                        !actual.isFinite() ||
                        !cob.isFinite() ||
                        !iob.isFinite() ||
                        !uam.isFinite() ||
                        !ciWidth.isFinite() ||
                        !diaHours.isFinite() ||
                        !activity.isFinite() ||
                        !sensorQuality.isFinite()
                    ) {
                        continue
                    }
                    add(
                        DailyReportReplayTopMissUi(
                            horizonMinutes = horizonMinutes,
                            ts = ts,
                            absError = absError,
                            pred = pred,
                            actual = actual,
                            cob = cob,
                            iob = iob,
                            uam = uam,
                            ciWidth = ciWidth,
                            diaHours = diaHours,
                            activity = activity,
                            sensorQuality = sensorQuality
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
            .sortedBy { it.horizonMinutes }
    }

    private fun parseReplayErrorClustersJson(raw: String?): List<DailyReportReplayErrorClusterUi> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val horizonMinutes = item.optInt("horizonMinutes", 0)
                    val hour = item.optInt("hour", -1)
                    val dayType = item.optString("dayType", "").trim().uppercase(Locale.US)
                    val sampleCount = item.optInt("sampleCount", 0)
                    val mae = item.optDouble("mae", Double.NaN)
                    val mardPct = item.optDouble("mardPct", Double.NaN)
                    val bias = item.optDouble("bias", Double.NaN)
                    val meanCob = item.optDouble("meanCob", Double.NaN)
                    val meanIob = item.optDouble("meanIob", Double.NaN)
                    val meanUam = item.optDouble("meanUam", Double.NaN)
                    val meanCiWidth = item.optDouble("meanCiWidth", Double.NaN)
                    val dominantFactor = item.optString("dominantFactor", "").trim()
                        .takeIf { it.isNotBlank() }
                    val dominantScore = item.optDouble("dominantScore", Double.NaN)
                        .takeIf { it.isFinite() }
                    if (
                        horizonMinutes <= 0 ||
                        hour !in 0..23 ||
                        dayType !in setOf("WEEKDAY", "WEEKEND") ||
                        sampleCount <= 0 ||
                        !mae.isFinite() ||
                        !mardPct.isFinite() ||
                        !bias.isFinite() ||
                        !meanCob.isFinite() ||
                        !meanIob.isFinite() ||
                        !meanUam.isFinite() ||
                        !meanCiWidth.isFinite()
                    ) {
                        continue
                    }
                    add(
                        DailyReportReplayErrorClusterUi(
                            horizonMinutes = horizonMinutes,
                            hour = hour,
                            dayType = dayType,
                            sampleCount = sampleCount,
                            mae = mae,
                            mardPct = mardPct,
                            bias = bias,
                            meanCob = meanCob,
                            meanIob = meanIob,
                            meanUam = meanUam,
                            meanCiWidth = meanCiWidth,
                            dominantFactor = dominantFactor,
                            dominantScore = dominantScore
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
            .sortedWith(
                compareBy<DailyReportReplayErrorClusterUi> { it.horizonMinutes }
                    .thenBy { it.dayType }
                    .thenByDescending { it.mae }
                    .thenByDescending { it.sampleCount }
            )
    }

    private fun parseReplayDayTypeGapsJson(raw: String?): List<DailyReportReplayDayTypeGapUi> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val horizonMinutes = item.optInt("horizonMinutes", 0)
                    val hour = item.optInt("hour", -1)
                    val worseDayType = item.optString("worseDayType", "").trim().uppercase(Locale.US)
                    val weekdaySampleCount = item.optInt("weekdaySampleCount", 0)
                    val weekendSampleCount = item.optInt("weekendSampleCount", 0)
                    val weekdayMae = item.optDouble("weekdayMae", Double.NaN)
                    val weekendMae = item.optDouble("weekendMae", Double.NaN)
                    val weekdayMardPct = item.optDouble("weekdayMardPct", Double.NaN)
                    val weekendMardPct = item.optDouble("weekendMardPct", Double.NaN)
                    val maeGapMmol = item.optDouble("maeGapMmol", Double.NaN)
                    val mardGapPct = item.optDouble("mardGapPct", Double.NaN)
                    val worseMeanCob = item.optDouble("worseMeanCob", Double.NaN)
                    val worseMeanIob = item.optDouble("worseMeanIob", Double.NaN)
                    val worseMeanUam = item.optDouble("worseMeanUam", Double.NaN)
                    val worseMeanCiWidth = item.optDouble("worseMeanCiWidth", Double.NaN)
                    val dominantFactor = item.optString("dominantFactor", "").trim()
                        .takeIf { it.isNotBlank() }
                    val dominantScore = item.optDouble("dominantScore", Double.NaN)
                        .takeIf { it.isFinite() }
                    if (
                        horizonMinutes <= 0 ||
                        hour !in 0..23 ||
                        worseDayType !in setOf("WEEKDAY", "WEEKEND") ||
                        weekdaySampleCount <= 0 ||
                        weekendSampleCount <= 0 ||
                        !weekdayMae.isFinite() ||
                        !weekendMae.isFinite() ||
                        !weekdayMardPct.isFinite() ||
                        !weekendMardPct.isFinite() ||
                        !maeGapMmol.isFinite() ||
                        !mardGapPct.isFinite() ||
                        !worseMeanCob.isFinite() ||
                        !worseMeanIob.isFinite() ||
                        !worseMeanUam.isFinite() ||
                        !worseMeanCiWidth.isFinite()
                    ) {
                        continue
                    }
                    add(
                        DailyReportReplayDayTypeGapUi(
                            horizonMinutes = horizonMinutes,
                            hour = hour,
                            worseDayType = worseDayType,
                            weekdaySampleCount = weekdaySampleCount,
                            weekendSampleCount = weekendSampleCount,
                            weekdayMae = weekdayMae,
                            weekendMae = weekendMae,
                            weekdayMardPct = weekdayMardPct,
                            weekendMardPct = weekendMardPct,
                            maeGapMmol = maeGapMmol,
                            mardGapPct = mardGapPct,
                            worseMeanCob = worseMeanCob,
                            worseMeanIob = worseMeanIob,
                            worseMeanUam = worseMeanUam,
                            worseMeanCiWidth = worseMeanCiWidth,
                            dominantFactor = dominantFactor,
                            dominantScore = dominantScore
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
            .sortedWith(
                compareBy<DailyReportReplayDayTypeGapUi> { it.horizonMinutes }
                    .thenByDescending { kotlin.math.abs(it.maeGapMmol) }
                    .thenBy { it.hour }
            )
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

    private fun buildIsfCrActivationGateLines(
        audits: List<AuditLogEntity>,
        telemetryByKey: Map<String, TelemetrySampleEntity>
    ): List<String> {
        val latestKpi = audits
            .asSequence()
            .filter { it.message == "isfcr_shadow_activation_evaluated" }
            .maxByOrNull { it.timestamp }
        val latestQuality = audits
            .asSequence()
            .filter { it.message == "isfcr_shadow_quality_gate_evaluated" }
            .maxByOrNull { it.timestamp }
        val latestDayType = audits
            .asSequence()
            .filter { it.message == "isfcr_shadow_day_type_gate_evaluated" }
            .maxByOrNull { it.timestamp }
        val latestSensor = audits
            .asSequence()
            .filter { it.message == "isfcr_shadow_sensor_gate_evaluated" }
            .maxByOrNull { it.timestamp }
        val latestDataQualityRisk = audits
            .asSequence()
            .filter { it.message == "isfcr_shadow_data_quality_risk_gate_evaluated" }
            .maxByOrNull { it.timestamp }
        val latestRolling = audits
            .asSequence()
            .filter { it.message == "isfcr_shadow_rolling_gate_evaluated" }
            .maxByOrNull { it.timestamp }
        val latestPromoted = audits
            .asSequence()
            .filter { it.message == "isfcr_shadow_auto_promoted" }
            .maxByOrNull { it.timestamp }

        fun boolField(entry: AuditLogEntity?, key: String): Boolean? {
            val safeEntry = entry ?: return null
            return auditMetaField(safeEntry, key)?.toBooleanStrictOrNull()
        }

        fun intField(entry: AuditLogEntity?, key: String): Int? {
            val safeEntry = entry ?: return null
            return auditMetaField(safeEntry, key)?.toIntOrNull()
        }

        fun doubleField(entry: AuditLogEntity?, key: String): Double? {
            val safeEntry = entry ?: return null
            return auditMetaField(safeEntry, key)
                ?.replace(",", ".")
                ?.toDoubleOrNull()
        }

        fun fmt(value: Double?, decimals: Int = 2): String {
            return value?.let { String.format(Locale.US, "%.${decimals}f", it) } ?: "--"
        }

        val lines = mutableListOf<String>()
        latestKpi?.let { entry ->
            val eligible = boolField(entry, "eligible")
            val reason = auditMetaField(entry, "reason") ?: "n/a"
            val sampleCount = intField(entry, "sampleCount")
            val meanConfidence = doubleField(entry, "meanConfidence")
            val meanIsfDelta = doubleField(entry, "meanAbsIsfDeltaPct")
            val meanCrDelta = doubleField(entry, "meanAbsCrDeltaPct")
            lines += buildString {
                append("KPI gate (${formatTs(entry.timestamp)}): eligible=${eligible ?: false}, reason=$reason")
                append(", n=${sampleCount ?: 0}, conf=${fmt(meanConfidence?.times(100.0), 0)}%")
                append(", |ΔISF|=${fmt(meanIsfDelta, 1)}%, |ΔCR|=${fmt(meanCrDelta, 1)}%")
            }
        }

        latestQuality?.let { entry ->
            val eligible = boolField(entry, "eligible")
            val reason = auditMetaField(entry, "reason") ?: "n/a"
            val matched = intField(entry, "matchedSamples")
            val mae30 = doubleField(entry, "mae30Mmol")
            val mae60 = doubleField(entry, "mae60Mmol")
            val hypo = doubleField(entry, "hypoRatePct24h")
            val cov30 = doubleField(entry, "ciCoverage30Pct")
            val cov60 = doubleField(entry, "ciCoverage60Pct")
            val width30 = doubleField(entry, "ciWidth30Mmol")
            val width60 = doubleField(entry, "ciWidth60Mmol")
            lines += buildString {
                append("Daily gate (${formatTs(entry.timestamp)}): eligible=${eligible ?: false}, reason=$reason")
                append(", n=${matched ?: 0}, MAE30=${fmt(mae30)}, MAE60=${fmt(mae60)}, hypo=${fmt(hypo, 1)}%")
            }
            lines += buildString {
                append("CI calib: cov30=${fmt(cov30, 1)}%, cov60=${fmt(cov60, 1)}%, width30=${fmt(width30)}, width60=${fmt(width60)}")
            }
        }

        latestDayType?.let { entry ->
            val eligible = boolField(entry, "eligible")
            val reason = auditMetaField(entry, "reason") ?: "n/a"
            val sampleCount = intField(entry, "sampleCount")
            val isfRatio = doubleField(entry, "meanIsfSameDayTypeRatio")
            val crRatio = doubleField(entry, "meanCrSameDayTypeRatio")
            val isfSparse = doubleField(entry, "isfSparseRatePct")
            val crSparse = doubleField(entry, "crSparseRatePct")
            lines += buildString {
                append("Day-type gate (${formatTs(entry.timestamp)}): eligible=${eligible ?: false}, reason=$reason")
                append(", n=${sampleCount ?: 0}, isfRatio=${fmt(isfRatio?.times(100.0), 0)}%, crRatio=${fmt(crRatio?.times(100.0), 0)}%")
                append(", isfSparse=${fmt(isfSparse, 1)}%, crSparse=${fmt(crSparse, 1)}%")
            }
        }

        latestSensor?.let { entry ->
            val eligible = boolField(entry, "eligible")
            val reason = auditMetaField(entry, "reason") ?: "n/a"
            val sampleCount = intField(entry, "sampleCount")
            val quality = doubleField(entry, "meanQualityScore")
            val sensorFactor = doubleField(entry, "meanSensorFactor")
            val wearPenalty = doubleField(entry, "meanWearPenalty")
            val sensorAgeHighRate = doubleField(entry, "sensorAgeHighRatePct")
            val suspectFalseLowRate = doubleField(entry, "suspectFalseLowRatePct")
            lines += buildString {
                append("Sensor gate (${formatTs(entry.timestamp)}): eligible=${eligible ?: false}, reason=$reason")
                append(", n=${sampleCount ?: 0}, quality=${fmt(quality?.times(100.0), 0)}%, factor=${fmt(sensorFactor?.times(100.0), 0)}%")
                append(", wearPenalty=${fmt(wearPenalty?.times(100.0), 1)}%, ageHigh=${fmt(sensorAgeHighRate, 1)}%")
                append(", falseLow=${fmt(suspectFalseLowRate, 1)}%")
            }
        }

        latestDataQualityRisk?.let { entry ->
            val eligible = boolField(entry, "eligible")
            val reason = auditMetaField(entry, "reason") ?: "n/a"
            val riskLevel = intField(entry, "riskLevel")
            val blockedRiskLevel = intField(entry, "blockedRiskLevel")
            val source = auditMetaField(entry, "riskLevelSource") ?: "unknown"
            lines += buildString {
                append("Data-quality risk gate (${formatTs(entry.timestamp)}): eligible=${eligible ?: false}, reason=$reason")
                append(", riskLevel=${riskLevel ?: 0}, blockAt=${blockedRiskLevel ?: 3}, source=$source")
            }
        }

        latestRolling?.let { entry ->
            val eligible = boolField(entry, "eligible")
            val reason = auditMetaField(entry, "reason") ?: "n/a"
            val requiredConfigured = intField(entry, "requiredWindowCountConfigured")
            val requiredEvaluated = intField(entry, "requiredWindowCount")
            val evaluated = intField(entry, "evaluatedWindowCount")
            val passed = intField(entry, "passedWindowCount")
            val maeRelax = doubleField(entry, "maeRelaxFactor")
            val ciCoverageRelax = doubleField(entry, "ciCoverageRelaxFactor")
            val ciWidthRelax = doubleField(entry, "ciWidthRelaxFactor")
            lines += buildString {
                append("Rolling gate (${formatTs(entry.timestamp)}): eligible=${eligible ?: false}, reason=$reason")
                append(", cfg=${requiredConfigured ?: requiredEvaluated ?: 0}, eval=${evaluated ?: 0}, pass=${passed ?: 0}")
            }
            lines += buildString {
                append("Rolling relax: MAE×${fmt(maeRelax)}, CIcov×${fmt(ciCoverageRelax)}, CIwidth×${fmt(ciWidthRelax)}")
            }
            lines += parseRollingGateWindows(auditMetaField(entry, "windows"))
                .map { window -> formatRollingGateWindowLine(window) { value -> fmt(value) } }
        }

        latestPromoted?.let { entry ->
            val reason = auditMetaField(entry, "reason") ?: "n/a"
            val qualityReason = auditMetaField(entry, "qualityReason") ?: "n/a"
            lines += "Last promotion (${formatTs(entry.timestamp)}): reason=$reason, qualityReason=$qualityReason"
        }

        val rollingKpiLines = listOf(14, 30, 90).mapNotNull { days ->
            val prefix = "rolling_report_${days}d"
            val matched = telemetryByKey["${prefix}_matched_samples"].toNumericValue()?.roundToInt()
            val mard30 = telemetryByKey["${prefix}_mard_30m_pct"].toNumericValue()
            val mard60 = telemetryByKey["${prefix}_mard_60m_pct"].toNumericValue()
            val hasAny = matched != null || mard30 != null || mard60 != null
            if (!hasAny) {
                null
            } else {
                buildString {
                    append("Rolling ${days}d: n=${matched ?: 0}")
                    mard30?.let { append(", MARD30=${fmt(it, 1)}%") }
                    mard60?.let { append(", MARD60=${fmt(it, 1)}%") }
                }
            }
        }
        lines += rollingKpiLines
        return lines
    }

    private data class IsfCrRuntimeDiagnosticsSnapshot(
        val realtimeTs: Long?,
        val mode: String?,
        val confidence: Double?,
        val confidenceThreshold: Double?,
        val qualityScore: Double?,
        val usedEvidence: Int?,
        val droppedEvidence: Int?,
        val droppedReasons: String?,
        val currentDayType: String?,
        val isfBaseSource: String?,
        val crBaseSource: String?,
        val isfDayTypeBaseAvailable: Boolean?,
        val crDayTypeBaseAvailable: Boolean?,
        val hourWindowIsfEvidence: Int?,
        val hourWindowCrEvidence: Int?,
        val hourWindowIsfSameDayType: Int?,
        val hourWindowCrSameDayType: Int?,
        val minIsfEvidencePerHour: Int?,
        val minCrEvidencePerHour: Int?,
        val crMaxGapMinutes: Double?,
        val crMaxSensorBlockedRatePct: Double?,
        val crMaxUamAmbiguityRatePct: Double?,
        val coverageHoursIsf: Int?,
        val coverageHoursCr: Int?,
        val realtimeReasons: String?,
        val lowConfidenceTs: Long?,
        val lowConfidenceReasons: String?,
        val fallbackTs: Long?,
        val fallbackReasons: String?
    )

    private fun buildIsfCrRuntimeDiagnosticsSnapshot(
        audits: List<AuditLogEntity>
    ): IsfCrRuntimeDiagnosticsSnapshot? {
        val latestRealtime = audits
            .asSequence()
            .filter { it.message == "isfcr_realtime_computed" }
            .maxByOrNull { it.timestamp }
        val latestLowConfidence = audits
            .asSequence()
            .filter { it.message == "isfcr_low_confidence" }
            .maxByOrNull { it.timestamp }
        val latestFallback = audits
            .asSequence()
            .filter { it.message == "isfcr_fallback_applied" }
            .maxByOrNull { it.timestamp }

        if (latestRealtime == null && latestLowConfidence == null && latestFallback == null) return null

        fun doubleField(entry: AuditLogEntity?, key: String): Double? {
            val safe = entry ?: return null
            return auditMetaField(safe, key)
                ?.replace(",", ".")
                ?.toDoubleOrNull()
        }

        fun intField(entry: AuditLogEntity?, key: String): Int? {
            val safe = entry ?: return null
            return auditMetaField(safe, key)?.toIntOrNull()
        }

        return IsfCrRuntimeDiagnosticsSnapshot(
            realtimeTs = latestRealtime?.timestamp,
            mode = latestRealtime?.let { auditMetaField(it, "mode") },
            confidence = doubleField(latestRealtime, "confidence"),
            confidenceThreshold = doubleField(latestRealtime, "confidenceThreshold"),
            qualityScore = doubleField(latestRealtime, "qualityScore"),
            usedEvidence = intField(latestRealtime, "usedEvidence"),
            droppedEvidence = intField(latestRealtime, "droppedEvidence"),
            droppedReasons = latestRealtime?.let { auditMetaField(it, "droppedReasons") },
            currentDayType = latestRealtime?.let { auditMetaField(it, "currentDayType") },
            isfBaseSource = latestRealtime?.let { auditMetaField(it, "isfBaseSource") },
            crBaseSource = latestRealtime?.let { auditMetaField(it, "crBaseSource") },
            isfDayTypeBaseAvailable = latestRealtime?.let {
                doubleField(it, "isfDayTypeBaseAvailable")?.let { value -> value >= 0.5 }
            },
            crDayTypeBaseAvailable = latestRealtime?.let {
                doubleField(it, "crDayTypeBaseAvailable")?.let { value -> value >= 0.5 }
            },
            hourWindowIsfEvidence = intField(latestRealtime, "hourWindowIsfEvidence"),
            hourWindowCrEvidence = intField(latestRealtime, "hourWindowCrEvidence"),
            hourWindowIsfSameDayType = intField(latestRealtime, "hourWindowIsfSameDayType"),
            hourWindowCrSameDayType = intField(latestRealtime, "hourWindowCrSameDayType"),
            minIsfEvidencePerHour = intField(latestRealtime, "minIsfEvidencePerHour"),
            minCrEvidencePerHour = intField(latestRealtime, "minCrEvidencePerHour"),
            crMaxGapMinutes = doubleField(latestRealtime, "crMaxGapMinutes"),
            crMaxSensorBlockedRatePct = doubleField(latestRealtime, "crMaxSensorBlockedRatePct"),
            crMaxUamAmbiguityRatePct = doubleField(latestRealtime, "crMaxUamAmbiguityRatePct"),
            coverageHoursIsf = intField(latestRealtime, "coverageHoursIsf"),
            coverageHoursCr = intField(latestRealtime, "coverageHoursCr"),
            realtimeReasons = latestRealtime?.let { auditMetaField(it, "reasons") },
            lowConfidenceTs = latestLowConfidence?.timestamp,
            lowConfidenceReasons = latestLowConfidence?.let { auditMetaField(it, "reasons") },
            fallbackTs = latestFallback?.timestamp,
            fallbackReasons = latestFallback?.let { auditMetaField(it, "reasons") }
        )
    }

    private fun buildIsfCrRuntimeDiagnosticsLines(
        snapshot: IsfCrRuntimeDiagnosticsSnapshot?
    ): List<String> {
        snapshot ?: return emptyList()

        fun fmt(value: Double?, decimals: Int = 2): String {
            return value?.let { String.format(Locale.US, "%.${decimals}f", it) } ?: "--"
        }

        return buildList {
            if (snapshot.realtimeTs != null) {
                val mode = snapshot.mode ?: "n/a"
                add(
                    "Runtime (${formatTs(snapshot.realtimeTs)}): mode=$mode, " +
                        "conf=${fmt(snapshot.confidence?.times(100.0), 0)}% " +
                        "(thr=${fmt(snapshot.confidenceThreshold?.times(100.0), 0)}%), " +
                        "quality=${fmt(snapshot.qualityScore?.times(100.0), 0)}%"
                )
                add(
                    "Evidence: used=${snapshot.usedEvidence ?: 0}, dropped=${snapshot.droppedEvidence ?: 0}, " +
                        "coverage ISF/CR=${snapshot.coverageHoursIsf ?: 0}/${snapshot.coverageHoursCr ?: 0} hours"
                )
                if (
                    snapshot.isfBaseSource != null ||
                    snapshot.crBaseSource != null ||
                    snapshot.isfDayTypeBaseAvailable != null ||
                    snapshot.crDayTypeBaseAvailable != null
                ) {
                    add(
                        "Base source ISF/CR=${snapshot.isfBaseSource ?: "--"}/${snapshot.crBaseSource ?: "--"}, " +
                            "day-type available=${snapshot.isfDayTypeBaseAvailable ?: false}/${snapshot.crDayTypeBaseAvailable ?: false}"
                    )
                }
                if (
                    snapshot.hourWindowIsfEvidence != null ||
                    snapshot.hourWindowCrEvidence != null ||
                    snapshot.minIsfEvidencePerHour != null ||
                    snapshot.minCrEvidencePerHour != null
                ) {
                    add(
                        "Hour-window evidence ISF/CR=${snapshot.hourWindowIsfEvidence ?: 0}/${snapshot.hourWindowCrEvidence ?: 0} " +
                            "(min ${snapshot.minIsfEvidencePerHour ?: 0}/${snapshot.minCrEvidencePerHour ?: 0})"
                    )
                }
                if (
                    snapshot.crMaxGapMinutes != null ||
                    snapshot.crMaxSensorBlockedRatePct != null ||
                    snapshot.crMaxUamAmbiguityRatePct != null
                ) {
                    add(
                        "CR gate: gap<=${fmt(snapshot.crMaxGapMinutes, 0)}m, " +
                            "sensorBlocked<=${fmt(snapshot.crMaxSensorBlockedRatePct, 0)}%, " +
                            "uamAmbiguity<=${fmt(snapshot.crMaxUamAmbiguityRatePct, 0)}%"
                    )
                }
                if (
                    snapshot.currentDayType != null ||
                    snapshot.hourWindowIsfSameDayType != null ||
                    snapshot.hourWindowCrSameDayType != null
                ) {
                    add(
                        "Day-type evidence (${snapshot.currentDayType ?: "n/a"}) ISF/CR=" +
                            "${snapshot.hourWindowIsfSameDayType ?: 0}/${snapshot.hourWindowCrSameDayType ?: 0}"
                    )
                }
                snapshot.droppedReasons
                    ?.takeIf { it.isNotBlank() }
                    ?.let { add("Dropped reasons: $it") }
                snapshot.realtimeReasons
                    ?.takeIf { it.isNotBlank() }
                    ?.let { add("Runtime reasons: $it") }
            }
            if (snapshot.lowConfidenceTs != null) {
                add("Low confidence (${formatTs(snapshot.lowConfidenceTs)}): reasons=${snapshot.lowConfidenceReasons ?: "n/a"}")
            }
            if (snapshot.fallbackTs != null) {
                add("Fallback (${formatTs(snapshot.fallbackTs)}): reasons=${snapshot.fallbackReasons ?: "n/a"}")
            }
        }
    }

    private fun buildIsfCrDroppedReasonSummaryLines(
        audits: List<AuditLogEntity>,
        nowTs: Long,
        windowMs: Long,
        topLimit: Int = 8
    ): List<String> {
        val cutoffTs = nowTs - windowMs
        val primaryEvents = audits.filter {
            it.timestamp >= cutoffTs && it.message == "isfcr_evidence_extracted"
        }
        val selected = if (primaryEvents.isNotEmpty()) {
            primaryEvents
        } else {
            audits.filter {
                it.timestamp >= cutoffTs && it.message == "isfcr_realtime_computed"
            }
        }
        if (selected.isEmpty()) return emptyList()

        val reasonCounts = linkedMapOf<String, Int>()
        var droppedTotal = 0
        selected.forEach { entry ->
            droppedTotal += auditMetaField(entry, "droppedEvidence")?.toIntOrNull() ?: 0
            parseDroppedReasonCounters(auditMetaField(entry, "droppedReasons")).forEach { (reason, count) ->
                reasonCounts[reason] = (reasonCounts[reason] ?: 0) + count
            }
        }
        return formatIsfCrDroppedReasonSummaryLines(
            eventCount = selected.size,
            droppedTotal = droppedTotal,
            reasonCounts = reasonCounts,
            topLimit = topLimit
        )
    }

    private fun buildIsfCrWearImpactSummaryLines(
        audits: List<AuditLogEntity>,
        nowTs: Long,
        windowMs: Long
    ): List<String> {
        val cutoffTs = nowTs - windowMs
        val events = audits.filter {
            it.timestamp >= cutoffTs && it.message == "isfcr_realtime_computed"
        }
        if (events.isEmpty()) return emptyList()

        val setAgeValues = events.mapNotNull { auditMetaDouble(it, "setAgeHours") }
        val sensorAgeValues = events.mapNotNull { auditMetaDouble(it, "sensorAgeHours") }
        val setFactorValues = events.mapNotNull { auditMetaDouble(it, "setFactor") }
        val sensorFactorValues = events.mapNotNull { auditMetaDouble(it, "sensorFactor") }
        val ambiguityValues = events.mapNotNull { auditMetaDouble(it, "contextAmbiguity") }
        val wearPenaltyValues = events.mapNotNull { auditMetaDouble(it, "wearConfidencePenalty") }
        val confidenceValues = events.mapNotNull { auditMetaDouble(it, "confidence") }
        val dayTypeValues = events.mapNotNull { auditMetaField(it, "currentDayType")?.uppercase(Locale.US) }
        val weekdayCount = dayTypeValues.count { it == "WEEKDAY" }
        val weekendCount = dayTypeValues.count { it == "WEEKEND" }
        val isfSameDayTypeRatios = events.mapNotNull { entry ->
            val total = auditMetaDouble(entry, "hourWindowIsfEvidence") ?: return@mapNotNull null
            val same = auditMetaDouble(entry, "hourWindowIsfSameDayType") ?: return@mapNotNull null
            if (total <= 0.0) return@mapNotNull null
            (same / total).coerceIn(0.0, 1.0)
        }
        val crSameDayTypeRatios = events.mapNotNull { entry ->
            val total = auditMetaDouble(entry, "hourWindowCrEvidence") ?: return@mapNotNull null
            val same = auditMetaDouble(entry, "hourWindowCrSameDayType") ?: return@mapNotNull null
            if (total <= 0.0) return@mapNotNull null
            (same / total).coerceIn(0.0, 1.0)
        }
        val isfDayTypeSparseCount = events.count { auditReasonSet(it).contains("isf_day_type_evidence_sparse") }
        val crDayTypeSparseCount = events.count { auditReasonSet(it).contains("cr_day_type_evidence_sparse") }

        val setHigh = setAgeValues.count { it > 72.0 }
        val sensorHigh = sensorAgeValues.count { it > 120.0 }
        val setCount = setAgeValues.size.coerceAtLeast(1)
        val sensorCount = sensorAgeValues.size.coerceAtLeast(1)
        val meanSetAge = setAgeValues.average().takeIf { !it.isNaN() }
        val meanSensorAge = sensorAgeValues.average().takeIf { !it.isNaN() }
        val meanSetFactor = setFactorValues.average().takeIf { !it.isNaN() }
        val meanSensorFactor = sensorFactorValues.average().takeIf { !it.isNaN() }
        val meanAmbiguity = ambiguityValues.average().takeIf { !it.isNaN() }
        val meanWearPenalty = wearPenaltyValues.average().takeIf { !it.isNaN() }
        val meanConfidence = confidenceValues.average().takeIf { !it.isNaN() }
        val meanIsfSameDayTypeRatio = isfSameDayTypeRatios.average().takeIf { !it.isNaN() }
        val meanCrSameDayTypeRatio = crSameDayTypeRatios.average().takeIf { !it.isNaN() }

        fun fmt(value: Double?, digits: Int = 2): String {
            return value?.let { String.format(Locale.US, "%.${digits}f", it) } ?: "--"
        }

        return buildList {
            add(
                "Events=${events.size}, mean set/sensor age=${fmt(meanSetAge, 1)}h/${fmt(meanSensorAge, 1)}h"
            )
            add(
                "High wear set>72h=${(setHigh * 100.0 / setCount).toInt()}%, sensor>120h=${(sensorHigh * 100.0 / sensorCount).toInt()}%"
            )
            if (meanSetFactor != null || meanSensorFactor != null) {
                add("Mean factors set/sensor=${fmt(meanSetFactor)}/${fmt(meanSensorFactor)}")
            }
            if (dayTypeValues.isNotEmpty()) {
                add(
                    "Day type distribution: weekday=${(weekdayCount * 100.0 / dayTypeValues.size).toInt()}%, " +
                        "weekend=${(weekendCount * 100.0 / dayTypeValues.size).toInt()}%"
                )
            }
            if (meanIsfSameDayTypeRatio != null || meanCrSameDayTypeRatio != null) {
                add(
                    "Mean same-day-type ratio ISF/CR=" +
                        "${fmt(meanIsfSameDayTypeRatio?.times(100.0), 0)}%/${fmt(meanCrSameDayTypeRatio?.times(100.0), 0)}%"
                )
            }
            add(
                "Day-type sparse flags ISF/CR=" +
                    "${(isfDayTypeSparseCount * 100.0 / events.size).toInt()}%/" +
                    "${(crDayTypeSparseCount * 100.0 / events.size).toInt()}%"
            )
            if (meanAmbiguity != null || meanWearPenalty != null) {
                add(
                    "Ambiguity=${fmt(meanAmbiguity?.times(100.0), 0)}%, wear penalty=${fmt(meanWearPenalty?.times(100.0), 0)}%"
                )
            }
            if (meanConfidence != null) {
                add("Mean confidence=${fmt(meanConfidence * 100.0, 0)}%")
            }
        }
    }

    private fun parseDroppedReasonCounters(raw: String?): Map<String, Int> {
        if (raw.isNullOrBlank()) return emptyMap()
        val counters = linkedMapOf<String, Int>()
        raw.split(';', ',', '\n')
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .forEach { token ->
                val parts = token.split("=", limit = 2)
                val key = parts[0].trim()
                if (key.isBlank()) return@forEach
                val count = parts.getOrNull(1)?.trim()?.toIntOrNull() ?: 1
                counters[key] = (counters[key] ?: 0) + count.coerceAtLeast(0)
            }
        return counters
    }

    private fun auditMetaDouble(entry: AuditLogEntity, key: String): Double? {
        return auditMetaField(entry, key)
            ?.replace(",", ".")
            ?.toDoubleOrNull()
    }

    private fun auditReasonSet(entry: AuditLogEntity): Set<String> {
        val raw = auditMetaField(entry, "reasons").orEmpty()
        if (raw.isBlank()) return emptySet()
        return raw
            .split(',', ';', '\n')
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .toSet()
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
            primaryKey = "iob_effective_units",
            label = "IOB",
            staleThresholdMin = 30L,
            exactAliases = listOf("iob_real_units", "iob_units", "raw_iob"),
            tokenAliases = listOf("iob", "insulinonboard")
        ),
        TelemetryCoverageSpec(
            primaryKey = "insulin_real_onset_min",
            label = "Insulin onset real",
            staleThresholdMin = 6 * 60L,
            tokenAliases = listOf("insulin_real_onset")
        ),
        TelemetryCoverageSpec(
            primaryKey = "cob_effective_grams",
            label = "COB",
            staleThresholdMin = 30L,
            exactAliases = listOf("cob_grams", "raw_cob"),
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
            primaryKey = "uam_inferred_flag",
            label = "UAM inferred",
            staleThresholdMin = 180L,
            exactAliases = listOf(
                "uam_calculated_flag",
                "uam_value",
                "uam_detected",
                "unannounced_meal",
                "has_uam",
                "is_uam"
            )
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
            "iob_effective_units",
            "iob_real_units",
            "cob_effective_grams",
            "iob_units",
            "cob_grams",
            "insulin_real_onset_min",
            "insulin_profile_base_onset_min",
            "insulin_real_onset_samples",
            "insulin_profile_real_confidence",
            "insulin_profile_real_samples",
            "insulin_profile_real_onset_min",
            "insulin_profile_real_peak_min",
            "insulin_profile_real_scale",
            "insulin_profile_real_status",
            "insulin_profile_real_source_profile",
            "insulin_profile_real_updated_ts",
            "insulin_profile_real_published_ts",
            "iob_external_raw_units",
            "cob_external_raw_grams",
            "iob_local_fallback_units",
            "cob_local_fallback_grams",
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
            "uam_inferred_flag",
            "uam_inferred_confidence",
            "uam_inferred_carbs_grams",
            "uam_inferred_ingestion_ts",
            "uam_inferred_mode",
            "uam_inferred_boost_mode",
            "uam_manual_cob_grams",
            "uam_inferred_gabs_last5_g",
            "uam_calculated_flag",
            "uam_calculated_confidence",
            "uam_calculated_carbs_grams",
            "uam_calculated_delta5_mmol",
            "uam_calculated_rise15_mmol",
            "uam_calculated_rise30_mmol",
            "uam_value",
            "sensor_quality_score",
            "sensor_quality_blocked",
            "sensor_quality_reason",
            "sensor_quality_suspect_false_low",
            "sensor_quality_delta5_mmol",
            "sensor_quality_noise_std5",
            "sensor_quality_gap_min",
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
        if (sample.key != "uam_calculated_flag" && sample.key != "uam_inferred_flag") return true
        val numeric = sample.valueDouble ?: sample.valueText?.replace(",", ".")?.toDoubleOrNull()
        return numeric == null || numeric in 0.0..1.5
    }

    private fun TelemetrySampleEntity?.toNumericValue(): Double? {
        if (this == null) return null
        return valueDouble ?: valueText?.replace(",", ".")?.toDoubleOrNull()
    }

    private fun buildSmbContextSummary(
        telemetryByKey: Map<String, TelemetrySampleEntity>,
        baseTargetMmol: Double
    ): String {
        val highTempTarget = telemetryByKey["temp_target_high_mmol"].toNumericValue()
        val highTempActive = highTempTarget != null && highTempTarget >= baseTargetMmol + 0.10

        val smbSignals = telemetryByKey.values
            .asSequence()
            .filter { sample ->
                val key = sample.key.lowercase(Locale.US)
                key.contains("smb") || key.contains("microbolus")
            }
            .sortedByDescending { it.timestamp }
            .take(3)
            .map { sample ->
                val value = sample.valueDouble?.let { String.format("%.2f", it) }
                    ?: sample.valueText
                    ?: "-"
                "${sample.key}=$value"
            }
            .toList()

        val chunks = mutableListOf<String>()
        if (highTempActive) {
            chunks += "High temp target active (${String.format("%.2f", highTempTarget)} mmol/L): SMB may be reduced by AAPS high-temp-target settings."
        }
        if (smbSignals.isNotEmpty()) {
            chunks += "SMB telemetry: ${smbSignals.joinToString("; ")}"
        }
        if (chunks.isEmpty()) {
            return "No explicit SMB telemetry flags detected."
        }
        return chunks.joinToString(" | ")
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

    private data class ReplacementSpec(
        val label: String,
        val types: Set<String>
    )

    private fun buildReplacementHistoryLines(
        therapy: List<TherapyEventEntity>,
        nowTs: Long
    ): List<String> {
        val specs = listOf(
            ReplacementSpec(
                label = "Infusion set",
                types = setOf("infusion_set_change", "site_change", "cannula_change", "set_change")
            ),
            ReplacementSpec(
                label = "Sensor",
                types = setOf("sensor_change", "sensor_start")
            ),
            ReplacementSpec(
                label = "Insulin refill",
                types = setOf("insulin_refill", "insulin_change", "reservoir_change", "cartridge_change", "pump_refill")
            ),
            ReplacementSpec(
                label = "Pump battery",
                types = setOf("pump_battery_change", "battery_change", "battery_replacement")
            )
        )
        return specs.map { spec ->
            val events = therapy
                .filter { spec.types.contains(it.type.lowercase(Locale.US)) }
                .sortedByDescending { it.timestamp }
            val lastTs = events.firstOrNull()?.timestamp
            val count7d = events.count { it.timestamp >= nowTs - 7L * 24 * 60 * 60 * 1000 }
            val count30d = events.count { it.timestamp >= nowTs - 30L * 24 * 60 * 60 * 1000 }
            val avgInterval30d = averageIntervalDays(
                events = events.filter { it.timestamp >= nowTs - 30L * 24 * 60 * 60 * 1000 }
            )
            val avgText = avgInterval30d?.let { String.format(Locale.US, "%.1f d", it) } ?: "-"
            "${spec.label}: last=${formatTs(lastTs)}, 7d=$count7d, 30d=$count30d, avg interval(30d)=$avgText"
        }
    }

    private fun averageIntervalDays(events: List<TherapyEventEntity>): Double? {
        if (events.size < 2) return null
        val sorted = events.sortedBy { it.timestamp }
        val deltas = sorted.zipWithNext().map { (a, b) ->
            (b.timestamp - a.timestamp).coerceAtLeast(0L).toDouble()
        }
        if (deltas.isEmpty()) return null
        return deltas.average() / (24.0 * 60 * 60 * 1000)
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
                val realFirst = profile.calculatedIsfMmolPerUnit ?: it
                "ISF: ${String.format(Locale.US, "%.2f", realFirst)} mmol/L/U (real profile estimate)"
            }
            "cr_value" -> profile?.crGramPerUnit?.let {
                val realFirst = profile.calculatedCrGramPerUnit ?: it
                "CR: ${String.format(Locale.US, "%.2f", realFirst)} g/U (real profile estimate)"
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
                    "History + telemetry fallback: ISF=${String.format(Locale.US, "%.2f", merged.isfMmolPerUnit)} mmol/L/U, " +
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
                add("Hourly history + telemetry fallback: ${hourlyMerged.size}/24 hours")
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

    private fun buildIsfCrDeepLines(
        glucose: List<GlucoseSampleEntity>,
        therapy: List<TherapyEventEntity>,
        telemetry: List<TelemetrySampleEntity>,
        nowTs: Long
    ): List<String> {
        if (glucose.isEmpty()) return listOf("ISF/CR deep analysis: no glucose data")

        val windowsDays = listOf(1, 3, 7, 14, 30)
        val historyWindowMs = 24L * 60L * 60L * 1000L

        return buildList {
            add("ISF/CR deep analysis (real + telemetry fallback)")
            windowsDays.forEach { days ->
                val startTs = nowTs - days * historyWindowMs
                val glucoseWindow = glucose.filter { it.timestamp in startTs..nowTs }
                val therapyWindow = therapy.filter { it.timestamp in startTs..nowTs }
                val telemetryWindow = telemetry.filter { it.timestamp in startTs..nowTs }

                if (glucoseWindow.size < 18) {
                    add("Last ${days}d: insufficient glucose points (${glucoseWindow.size})")
                    return@forEach
                }

                val estimator = ProfileEstimator(ProfileEstimatorConfig(lookbackDays = days))
                val glucoseDomain = glucoseWindow.map { it.toDomain() }
                val therapyDomain = therapyWindow.mapNotNull { event ->
                    runCatching { event.toDomain(container.gson) }.getOrNull()
                }
                val telemetrySignals = telemetryWindow.map { sample ->
                    TelemetrySignal(
                        ts = sample.timestamp,
                        key = sample.key,
                        valueDouble = sample.valueDouble,
                        valueText = sample.valueText
                    )
                }

                val historyOnly = estimator.estimate(
                    glucoseHistory = glucoseDomain,
                    therapyEvents = therapyDomain,
                    telemetrySignals = emptyList()
                )
                val merged = estimator.estimate(
                    glucoseHistory = glucoseDomain,
                    therapyEvents = therapyDomain,
                    telemetrySignals = telemetrySignals
                )

                add(
                    "Last ${days}d history-only: " + if (historyOnly == null) {
                        "insufficient correction/meal samples"
                    } else {
                        "ISF=${String.format(Locale.US, "%.2f", historyOnly.isfMmolPerUnit)} mmol/L/U, " +
                            "CR=${String.format(Locale.US, "%.2f", historyOnly.crGramPerUnit)} g/U, " +
                            "conf=${String.format(Locale.US, "%.0f%%", historyOnly.confidence * 100)}, " +
                            "n=${historyOnly.sampleCount}"
                    }
                )
                add(
                    "Last ${days}d telemetry fallback: " + if (merged == null) {
                        "insufficient samples"
                    } else {
                        "ISF=${String.format(Locale.US, "%.2f", merged.isfMmolPerUnit)} mmol/L/U, " +
                            "CR=${String.format(Locale.US, "%.2f", merged.crGramPerUnit)} g/U, " +
                            "conf=${String.format(Locale.US, "%.0f%%", merged.confidence * 100)}, " +
                            "n=${merged.sampleCount}"
                    }
                )

                if (days != 7) return@forEach

                val hourlyHistory = estimator.estimateHourly(
                    glucoseHistory = glucoseDomain,
                    therapyEvents = therapyDomain,
                    telemetrySignals = emptyList()
                )
                val hourlyMerged = estimator.estimateHourly(
                    glucoseHistory = glucoseDomain,
                    therapyEvents = therapyDomain,
                    telemetrySignals = telemetrySignals
                )
                val hourlyByDayTypeHistory = estimator.estimateHourlyByDayType(
                    glucoseHistory = glucoseDomain,
                    therapyEvents = therapyDomain,
                    telemetrySignals = emptyList()
                )

                add("Last 7d hourly history-only (${hourlyHistory.size}/24 hours):")
                if (hourlyHistory.isEmpty()) {
                    add("hourly history-only: insufficient hourly samples")
                } else {
                    hourlyHistory.forEach { hour ->
                        val isfText = hour.isfMmolPerUnit?.let { String.format(Locale.US, "%.2f", it) } ?: "-"
                        val crText = hour.crGramPerUnit?.let { String.format(Locale.US, "%.2f", it) } ?: "-"
                        add(
                            "${String.format(Locale.US, "%02d:00", hour.hour)} ISF=$isfText, CR=$crText, " +
                                "conf=${String.format(Locale.US, "%.0f%%", hour.confidence * 100)}, " +
                                "n(ISF/CR)=${hour.isfSampleCount}/${hour.crSampleCount}"
                        )
                    }
                }

                add("Last 7d hourly telemetry fallback (${hourlyMerged.size}/24 hours):")
                if (hourlyMerged.isEmpty()) {
                    add("hourly merged: insufficient hourly samples")
                } else {
                    hourlyMerged.forEach { hour ->
                        val isfText = hour.isfMmolPerUnit?.let { String.format(Locale.US, "%.2f", it) } ?: "-"
                        val crText = hour.crGramPerUnit?.let { String.format(Locale.US, "%.2f", it) } ?: "-"
                        add(
                            "${String.format(Locale.US, "%02d:00", hour.hour)} ISF=$isfText, CR=$crText, " +
                                "conf=${String.format(Locale.US, "%.0f%%", hour.confidence * 100)}, " +
                                "n(ISF/CR)=${hour.isfSampleCount}/${hour.crSampleCount}"
                        )
                    }
                }

                add("Last 7d hourly history-only by day type:")
                DayType.entries.forEach { dayType ->
                    val dayRows = hourlyByDayTypeHistory.filter { it.dayType == dayType }
                    if (dayRows.isEmpty()) {
                        add("${dayType.name}: no samples")
                    } else {
                        add("${dayType.name}:")
                        dayRows.forEach { row ->
                            val isfText = row.isfMmolPerUnit?.let { String.format(Locale.US, "%.2f", it) } ?: "-"
                            val crText = row.crGramPerUnit?.let { String.format(Locale.US, "%.2f", it) } ?: "-"
                            add(
                                "  ${String.format(Locale.US, "%02d:00", row.hour)} " +
                                    "ISF=$isfText, CR=$crText, conf=${String.format(Locale.US, "%.0f%%", row.confidence * 100)}, " +
                                    "n(ISF/CR)=${row.isfSampleCount}/${row.crSampleCount}"
                            )
                        }
                    }
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
            if (HEALTH_CONNECT_ENABLED) {
                add(
                    "Health Connect stream: " + if (latestHealthConnectTs == null) {
                        formatHealthConnectStatus(healthConnectState, healthConnectMissingPermissions)
                    } else {
                        "${formatTs(latestHealthConnectTs)} (age ${minutesLabel(healthConnectAgeMin)})"
                    }
                )
            } else {
                add("Health Connect stream: paused")
            }
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

    private fun normalizePhysioTagType(raw: String): String {
        return when (raw.trim().lowercase(Locale.US)) {
            "hormonal_phase" -> "hormonal"
            "steroid" -> "steroids"
            else -> raw.trim().lowercase(Locale.US)
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
        private const val HEALTH_CONNECT_ENABLED = false
        private const val PHYSIO_TAG_JOURNAL_MAX_ROWS = 40
        private const val PHYSIO_TAG_JOURNAL_LOOKBACK_MS = 180L * 24 * 60 * 60 * 1000
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

private fun IsfCrRealtimeSnapshot.displayIsfEffForAnalytics(): Double {
    if (mode != IsfCrRuntimeMode.FALLBACK) return isfEff
    val raw = factors["raw_isf_eff"]
    return if (raw != null && raw.isFinite() && raw > 0.0) raw else isfEff
}

private fun IsfCrRealtimeSnapshot.displayCrEffForAnalytics(): Double {
    if (mode != IsfCrRuntimeMode.FALLBACK) return crEff
    val raw = factors["raw_cr_eff"]
    return if (raw != null && raw.isFinite() && raw > 0.0) raw else crEff
}

private data class AapsTlsCompatibility(
    val installed: Boolean,
    val targetSdk: Int?,
    val networkSecurityConfigRes: Int?,
    val likelyRejectsUserCa: Boolean
)

class MainUiState {
    var nightscoutUrl: String = ""
    var cloudUrl: String = ""
    var exportUri: String? = null
    var killSwitch: Boolean = false
    var localNightscoutEnabled: Boolean = false
    var localNightscoutPort: Int = 17580
    var resolvedNightscoutUrl: String = ""
    var localBroadcastIngestEnabled: Boolean = true
    var strictBroadcastSenderValidation: Boolean = false
    var localCommandFallbackEnabled: Boolean = false
    var localCommandPackage: String = "info.nightscout.androidaps"
    var localCommandAction: String = "info.nightscout.client.NEW_TREATMENT"
    var insulinProfileId: String = "NOVORAPID"
    var enableUamInference: Boolean = true
    var enableUamBoost: Boolean = true
    var enableUamExportToAaps: Boolean = true
    var uamExportMode: String = "CONFIRMED_ONLY"
    var dryRunExport: Boolean = false
    var uamLearnedMultiplier: Double = 1.0
    var uamMinSnackG: Int = 15
    var uamMaxSnackG: Int = 60
    var uamSnackStepG: Int = 5
    var uamBackdateMinutesDefault: Int = 25
    var uamExportMinIntervalMin: Int = 10
    var uamExportMaxBackdateMin: Int = 180
    var isfCrShadowMode: Boolean = true
    var isfCrConfidenceThreshold: Double = 0.55
    var isfCrUseActivity: Boolean = true
    var isfCrUseManualTags: Boolean = true
    var isfCrMinIsfEvidencePerHour: Int = 2
    var isfCrMinCrEvidencePerHour: Int = 2
    var isfCrCrMaxGapMinutes: Int = 30
    var isfCrCrMaxSensorBlockedRatePct: Double = 25.0
    var isfCrCrMaxUamAmbiguityRatePct: Double = 60.0
    var isfCrSnapshotRetentionDays: Int = 365
    var isfCrEvidenceRetentionDays: Int = 730
    var isfCrAutoActivationEnabled: Boolean = false
    var isfCrAutoActivationLookbackHours: Int = 24
    var isfCrAutoActivationMinSamples: Int = 72
    var isfCrAutoActivationMinMeanConfidence: Double = 0.65
    var isfCrAutoActivationMaxMeanAbsIsfDeltaPct: Double = 25.0
    var isfCrAutoActivationMaxMeanAbsCrDeltaPct: Double = 25.0
    var isfCrAutoActivationMinSensorQualityScore: Double = 0.46
    var isfCrAutoActivationMinSensorFactor: Double = 0.90
    var isfCrAutoActivationMaxWearConfidencePenalty: Double = 0.12
    var isfCrAutoActivationMaxSensorAgeHighRatePct: Double = 70.0
    var isfCrAutoActivationMaxSuspectFalseLowRatePct: Double = 35.0
    var isfCrAutoActivationMinDayTypeRatio: Double = 0.30
    var isfCrAutoActivationMaxDayTypeSparseRatePct: Double = 75.0
    var isfCrAutoActivationRequireDailyQualityGate: Boolean = true
    var isfCrAutoActivationDailyRiskBlockLevel: Int = 3
    var isfCrAutoActivationMinDailyMatchedSamples: Int = 120
    var isfCrAutoActivationMaxDailyMae30Mmol: Double = 0.90
    var isfCrAutoActivationMaxDailyMae60Mmol: Double = 1.40
    var isfCrAutoActivationMaxHypoRatePct: Double = 6.0
    var isfCrAutoActivationMinDailyCiCoverage30Pct: Double = 55.0
    var isfCrAutoActivationMinDailyCiCoverage60Pct: Double = 55.0
    var isfCrAutoActivationMaxDailyCiWidth30Mmol: Double = 1.80
    var isfCrAutoActivationMaxDailyCiWidth60Mmol: Double = 2.60
    var isfCrAutoActivationRollingMinRequiredWindows: Int = 2
    var isfCrAutoActivationRollingMaeRelaxFactor: Double = 1.15
    var isfCrAutoActivationRollingCiCoverageRelaxFactor: Double = 0.90
    var isfCrAutoActivationRollingCiWidthRelaxFactor: Double = 1.25
    var baseTargetMmol: Double = 5.5
    var postHypoThresholdMmol: Double = 4.0
    var postHypoDeltaThresholdMmol5m: Double = 0.20
    var postHypoTargetMmol: Double = 4.4
    var postHypoDurationMinutes: Int = 60
    var postHypoLookbackMinutes: Int = 90
    var adaptiveControllerEnabled: Boolean = true
    var adaptiveControllerPriority: Int = 120
    var adaptiveControllerRetargetMinutes: Int = 5
    var adaptiveControllerSafetyProfile: String = "BALANCED"
    var adaptiveControllerStaleMaxMinutes: Int = 15
    var adaptiveControllerMaxActions6h: Int = 4
    var adaptiveControllerMaxStepMmol: Double = 0.25
    var latestDataAgeMinutes: Long? = null
    var nightscoutSyncAgeMinutes: Long? = null
    var cloudPushBacklogMinutes: Long? = null
    var latestGlucoseMmol: Double? = null
    var glucoseDelta: Double? = null
    var latestIobUnits: Double? = null
    var latestIobRealUnits: Double? = null
    var latestCobGrams: Double? = null
    var insulinRealOnsetMinutes: Double? = null
    var insulinRealProfileCurveCompact: String? = null
    var insulinRealProfileUpdatedTs: Long? = null
    var insulinRealProfileConfidence: Double? = null
    var insulinRealProfileSamples: Int? = null
    var insulinRealProfileOnsetMinutes: Double? = null
    var insulinRealProfilePeakMinutes: Double? = null
    var insulinRealProfileScale: Double? = null
    var insulinRealProfileStatus: String? = null
    var latestActivityRatio: Double? = null
    var latestStepsCount: Double? = null
    var forecast5m: Double? = null
    var forecast5mCiLow: Double? = null
    var forecast5mCiHigh: Double? = null
    var forecast30m: Double? = null
    var forecast30mCiLow: Double? = null
    var forecast30mCiHigh: Double? = null
    var forecast60m: Double? = null
    var forecast60mCiLow: Double? = null
    var forecast60mCiHigh: Double? = null
    var calculatedUamActive: Boolean? = null
    var calculatedUamConfidence: Double? = null
    var calculatedUamCarbsGrams: Double? = null
    var calculatedUci0Mmol5m: Double? = null
    var calculatedUamDelta5Mmol: Double? = null
    var calculatedUamRise15Mmol: Double? = null
    var calculatedUamRise30Mmol: Double? = null
    var inferredUamActive: Boolean? = null
    var inferredUamConfidence: Double? = null
    var inferredUamCarbsGrams: Double? = null
    var inferredUamIngestionTs: Long? = null
    var inferredUamBoostMode: Boolean? = null
    var inferredUamManualCobGrams: Double? = null
    var inferredUamLastGAbsGrams: Double? = null
    var uamEventRows: List<UamEventRowUi> = emptyList()
    var sensorQualityScore: Double? = null
    var sensorQualityBlocked: Boolean? = null
    var sensorQualityReason: String? = null
    var sensorQualitySuspectFalseLow: Boolean? = null
    var smbContextSummary: String = "No explicit SMB telemetry flags detected."
    var lastRuleState: String? = null
    var lastRuleId: String? = null
    var controllerState: String? = null
    var controllerReason: String? = null
    var controllerConfidence: Double? = null
    var controllerNextTarget: Double? = null
    var controllerDurationMinutes: Int? = null
    var controllerForecast30: Double? = null
    var controllerForecast60: Double? = null
    var controllerWeightedError: Double? = null
    var profileIsf: Double? = null
    var profileCr: Double? = null
    var profileConfidence: Double? = null
    var profileSamples: Int? = null
    var profileIsfSamples: Int? = null
    var profileCrSamples: Int? = null
    var profileTelemetryIsfSamples: Int? = null
    var profileTelemetryCrSamples: Int? = null
    var profileUamObservedCount: Int? = null
    var profileUamFilteredIsfSamples: Int? = null
    var profileUamEpisodes: Int? = null
    var profileUamCarbsGrams: Double? = null
    var profileUamRecentCarbsGrams: Double? = null
    var profileCalculatedIsf: Double? = null
    var profileCalculatedCr: Double? = null
    var profileCalculatedConfidence: Double? = null
    var profileCalculatedSamples: Int? = null
    var profileCalculatedIsfSamples: Int? = null
    var profileCalculatedCrSamples: Int? = null
    var profileLookbackDays: Int? = null
    var isfCrRealtimeMode: String? = null
    var isfCrRealtimeConfidence: Double? = null
    var isfCrRealtimeQualityScore: Double? = null
    var isfCrRealtimeIsfEff: Double? = null
    var isfCrRealtimeCrEff: Double? = null
    var isfCrRealtimeIsfBase: Double? = null
    var isfCrRealtimeCrBase: Double? = null
    var isfCrRealtimeCiIsfLow: Double? = null
    var isfCrRealtimeCiIsfHigh: Double? = null
    var isfCrRealtimeCiCrLow: Double? = null
    var isfCrRealtimeCiCrHigh: Double? = null
    var isfCrRealtimeFactors: List<String> = emptyList()
    var isfCrRuntimeDiagTs: Long? = null
    var isfCrRuntimeDiagMode: String? = null
    var isfCrRuntimeDiagConfidence: Double? = null
    var isfCrRuntimeDiagConfidenceThreshold: Double? = null
    var isfCrRuntimeDiagQualityScore: Double? = null
    var isfCrRuntimeDiagUsedEvidence: Int? = null
    var isfCrRuntimeDiagDroppedEvidence: Int? = null
    var isfCrRuntimeDiagDroppedReasons: String? = null
    var isfCrRuntimeDiagCurrentDayType: String? = null
    var isfCrRuntimeDiagIsfBaseSource: String? = null
    var isfCrRuntimeDiagCrBaseSource: String? = null
    var isfCrRuntimeDiagIsfDayTypeBaseAvailable: Boolean? = null
    var isfCrRuntimeDiagCrDayTypeBaseAvailable: Boolean? = null
    var isfCrRuntimeDiagHourWindowIsfEvidence: Int? = null
    var isfCrRuntimeDiagHourWindowCrEvidence: Int? = null
    var isfCrRuntimeDiagHourWindowIsfSameDayType: Int? = null
    var isfCrRuntimeDiagHourWindowCrSameDayType: Int? = null
    var isfCrRuntimeDiagMinIsfEvidencePerHour: Int? = null
    var isfCrRuntimeDiagMinCrEvidencePerHour: Int? = null
    var isfCrRuntimeDiagCrMaxGapMinutes: Double? = null
    var isfCrRuntimeDiagCrMaxSensorBlockedRatePct: Double? = null
    var isfCrRuntimeDiagCrMaxUamAmbiguityRatePct: Double? = null
    var isfCrRuntimeDiagCoverageHoursIsf: Int? = null
    var isfCrRuntimeDiagCoverageHoursCr: Int? = null
    var isfCrRuntimeDiagReasons: String? = null
    var isfCrRuntimeDiagLowConfidenceTs: Long? = null
    var isfCrRuntimeDiagLowConfidenceReasons: String? = null
    var isfCrRuntimeDiagFallbackTs: Long? = null
    var isfCrRuntimeDiagFallbackReasons: String? = null
    var isfCrActivationGateLines: List<String> = emptyList()
    var isfCrDroppedReasons24hLines: List<String> = emptyList()
    var isfCrDroppedReasons7dLines: List<String> = emptyList()
    var isfCrWearImpact24hLines: List<String> = emptyList()
    var isfCrWearImpact7dLines: List<String> = emptyList()
    var isfCrActiveTags: List<String> = emptyList()
    var isfCrHistoryPoints: List<IsfCrHistoryPointUi> = emptyList()
    var isfCrHistoryLastUpdatedTs: Long? = null
    var profileSegmentLines: List<String> = emptyList()
    var yesterdayProfileLines: List<String> = emptyList()
    var isfCrDeepLines: List<String> = emptyList()
    var activityLines: List<String> = emptyList()
    var rulePostHypoEnabled: Boolean = true
    var rulePatternEnabled: Boolean = true
    var ruleSegmentEnabled: Boolean = true
    var rulePostHypoPriority: Int = 100
    var rulePatternPriority: Int = 50
    var ruleSegmentPriority: Int = 40
    var rulePostHypoCooldownMinutes: Int = 30
    var rulePatternCooldownMinutes: Int = 60
    var ruleSegmentCooldownMinutes: Int = 60
    var patternMinSamplesPerWindow: Int = 40
    var patternMinActiveDaysPerWindow: Int = 7
    var patternLowRateTrigger: Double = 0.12
    var patternHighRateTrigger: Double = 0.18
    var analyticsLookbackDays: Int = 365
    var maxActionsIn6Hours: Int = 3
    var staleDataMaxMinutes: Int = 10
    var safetyMinTargetMmol: Double = 4.0
    var safetyMaxTargetMmol: Double = 10.0
    var carbAbsorptionMaxAgeMinutes: Int = 180
    var carbComputationMaxGrams: Double = 60.0
    var weekdayHotHours: List<PatternWindow> = emptyList()
    var weekendHotHours: List<PatternWindow> = emptyList()
    var qualityMetrics: List<QualityMetricUi> = emptyList()
    var dailyReportGeneratedAtTs: Long? = null
    var dailyReportMatchedSamples: Int? = null
    var dailyReportForecastRows: Int? = null
    var dailyReportPeriodStartUtc: String? = null
    var dailyReportPeriodEndUtc: String? = null
    var dailyReportMarkdownPath: String? = null
    var dailyReportMetrics: List<DailyReportMetricUi> = emptyList()
    var dailyReportRecommendations: List<String> = emptyList()
    var dailyReportIsfCrQualityLines: List<String> = emptyList()
    var dailyReportReplayHotspots: List<DailyReportReplayHotspotUi> = emptyList()
    var dailyReportReplayFactors: List<DailyReportReplayFactorUi> = emptyList()
    var dailyReportReplayCoverage: List<DailyReportReplayCoverageUi> = emptyList()
    var dailyReportReplayRegimes: List<DailyReportReplayRegimeUi> = emptyList()
    var dailyReportReplayPairs: List<DailyReportReplayPairUi> = emptyList()
    var dailyReportReplayTopMisses: List<DailyReportReplayTopMissUi> = emptyList()
    var dailyReportReplayErrorClusters: List<DailyReportReplayErrorClusterUi> = emptyList()
    var dailyReportReplayDayTypeGaps: List<DailyReportReplayDayTypeGapUi> = emptyList()
    var dailyReportReplayTopFactorsOverall: String? = null
    var rollingReportLines: List<String> = emptyList()
    var baselineDeltaLines: List<String> = emptyList()
    var telemetryCoverageLines: List<String> = emptyList()
    var telemetryLines: List<String> = emptyList()
    var actionLines: List<String> = emptyList()
    var autoConnectLines: List<String> = emptyList()
    var transportStatusLines: List<String> = emptyList()
    var replacementHistoryLines: List<String> = emptyList()
    var syncStatusLines: List<String> = emptyList()
    var jobStatusLines: List<String> = emptyList()
    var insightsFilterLabel: String = "Filters: source=all, status=all, days=60, weeks=8"
    var analysisHistoryLines: List<String> = emptyList()
    var analysisTrendLines: List<String> = emptyList()
    var ruleCooldownLines: List<String> = emptyList()
    var dryRun: DryRunUi? = null
    var cloudReplay: CloudReplayUiModel? = null
    var adaptiveAuditLines: List<String> = emptyList()
    var glucoseHistoryPoints: List<GlucoseHistoryRowUi> = emptyList()
    var lastAction: LastActionRowUi? = null
    var trend60ComponentMmol: Double? = null
    var therapy60ComponentMmol: Double? = null
    var uam60ComponentMmol: Double? = null
    var residualRoc0Mmol5m: Double? = null
    var sigmaEMmol5m: Double? = null
    var kfSigmaGMmol: Double? = null
    var auditRecords: List<AuditRecordRowUi> = emptyList()
    var auditLines: List<String> = emptyList()
    var message: String? = null
}

data class QualityMetricUi(
    val horizonMinutes: Int,
    val sampleCount: Int,
    val mae: Double,
    val rmse: Double,
    val mardPct: Double
)

data class DailyReportMetricUi(
    val horizonMinutes: Int,
    val sampleCount: Int?,
    val mae: Double?,
    val rmse: Double?,
    val mardPct: Double?,
    val bias: Double?,
    val ciCoveragePct: Double? = null,
    val ciMeanWidth: Double? = null
)

data class DailyReportReplayHotspotUi(
    val horizonMinutes: Int,
    val hour: Int,
    val sampleCount: Int,
    val mae: Double,
    val mardPct: Double,
    val bias: Double
)

data class DailyReportReplayFactorUi(
    val horizonMinutes: Int,
    val factor: String,
    val sampleCount: Int,
    val corrAbsError: Double,
    val maeHigh: Double,
    val maeLow: Double,
    val upliftPct: Double,
    val contributionScore: Double
)

data class DailyReportReplayCoverageUi(
    val horizonMinutes: Int,
    val factor: String,
    val sampleCount: Int,
    val coveragePct: Double
)

data class DailyReportReplayRegimeUi(
    val horizonMinutes: Int,
    val factor: String,
    val bucket: String,
    val sampleCount: Int,
    val meanFactorValue: Double,
    val mae: Double,
    val mardPct: Double,
    val bias: Double
)

data class DailyReportReplayPairUi(
    val horizonMinutes: Int,
    val factorA: String,
    val factorB: String,
    val bucketA: String,
    val bucketB: String,
    val sampleCount: Int,
    val meanFactorA: Double,
    val meanFactorB: Double,
    val mae: Double,
    val mardPct: Double,
    val bias: Double
)

data class DailyReportReplayTopMissUi(
    val horizonMinutes: Int,
    val ts: Long,
    val absError: Double,
    val pred: Double,
    val actual: Double,
    val cob: Double,
    val iob: Double,
    val uam: Double,
    val ciWidth: Double,
    val diaHours: Double,
    val activity: Double,
    val sensorQuality: Double
)

data class DailyReportReplayErrorClusterUi(
    val horizonMinutes: Int,
    val hour: Int,
    val dayType: String,
    val sampleCount: Int,
    val mae: Double,
    val mardPct: Double,
    val bias: Double,
    val meanCob: Double,
    val meanIob: Double,
    val meanUam: Double,
    val meanCiWidth: Double,
    val dominantFactor: String? = null,
    val dominantScore: Double? = null
)

data class DailyReportReplayDayTypeGapUi(
    val horizonMinutes: Int,
    val hour: Int,
    val worseDayType: String,
    val weekdaySampleCount: Int,
    val weekendSampleCount: Int,
    val weekdayMae: Double,
    val weekendMae: Double,
    val weekdayMardPct: Double,
    val weekendMardPct: Double,
    val maeGapMmol: Double,
    val mardGapPct: Double,
    val worseMeanCob: Double,
    val worseMeanIob: Double,
    val worseMeanUam: Double,
    val worseMeanCiWidth: Double,
    val dominantFactor: String? = null,
    val dominantScore: Double? = null
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

data class GlucoseHistoryRowUi(
    val timestamp: Long,
    val valueMmol: Double
)

data class LastActionRowUi(
    val type: String,
    val status: String,
    val timestamp: Long,
    val tempTargetMmol: Double? = null,
    val durationMinutes: Int? = null,
    val carbsGrams: Double? = null,
    val idempotencyKey: String? = null,
    val payloadSummary: String? = null
)

data class UamEventRowUi(
    val id: String,
    val state: String,
    val mode: String,
    val createdAt: Long,
    val updatedAt: Long,
    val ingestionTs: Long,
    val carbsDisplayG: Double,
    val confidence: Double,
    val exportSeq: Int,
    val exportedGrams: Double,
    val tag: String,
    val manualCarbsNearby: Boolean,
    val manualCobActive: Boolean,
    val exportBlockedReason: String?
)

data class AuditRecordRowUi(
    val id: String,
    val ts: Long,
    val source: String,
    val level: String,
    val summary: String,
    val context: String,
    val idempotencyKey: String? = null,
    val payloadSummary: String? = null
)
