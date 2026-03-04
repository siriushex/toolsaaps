package io.aaps.copilot.data.repository

import android.content.Context
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.os.Environment
import io.aaps.copilot.config.AppSettingsStore
import io.aaps.copilot.data.local.CopilotDatabase
import io.aaps.copilot.data.local.entity.ForecastEntity
import io.aaps.copilot.data.local.entity.GlucoseSampleEntity
import io.aaps.copilot.data.local.entity.TelemetrySampleEntity
import io.aaps.copilot.data.remote.cloud.AnalysisHistoryItem
import io.aaps.copilot.data.remote.cloud.AnalysisTrendItem
import io.aaps.copilot.data.remote.cloud.CloudJobStatus
import io.aaps.copilot.data.remote.cloud.DailyAnalysisRequest
import io.aaps.copilot.data.remote.cloud.ReplayDayTypeStats
import io.aaps.copilot.data.remote.cloud.ReplayDriftStats
import io.aaps.copilot.data.remote.cloud.ReplayForecastStats
import io.aaps.copilot.data.remote.cloud.ReplayHourStats
import io.aaps.copilot.data.remote.cloud.ReplayReportRequest
import io.aaps.copilot.data.remote.cloud.ReplayRuleStats
import io.aaps.copilot.service.ApiFactory
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.sqrt
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

class InsightsRepository(
    private val context: Context,
    private val db: CopilotDatabase,
    private val settingsStore: AppSettingsStore,
    private val apiFactory: ApiFactory,
    private val auditLogger: AuditLogger
) {

    private data class IsfCrDroppedReasonSummary(
        val eventCount: Int,
        val droppedTotal: Int,
        val reasonCounts: Map<String, Int>,
        val sourceMessage: String
    )

    suspend fun runDailyAnalysis(): String {
        val localReportStatus = runCatching {
            generateDailyForecastReport(hours = DAILY_FORECAST_REPORT_HOURS)
        }.getOrElse { error ->
            val message = error.message ?: "local forecast report failed"
            auditLogger.error("daily_forecast_report_failed", mapOf("error" to message))
            DailyForecastReportResult(
                statusLine = "Local forecast report failed: $message",
                markdownPath = null,
                csvPath = null,
                matchedSamples = 0,
                topMardHorizon = null,
                topMardPct = null
            )
        }

        val settings = settingsStore.settings.first()
        if (settings.cloudBaseUrl.isBlank()) {
            val message = "Cloud API not configured"
            auditLogger.warn("daily_analysis_skipped", mapOf("reason" to "missing_cloud_url"))
            return buildString {
                append(localReportStatus.statusLine)
                append('\n')
                append(message)
            }
        }

        return runCatching {
            val api = apiFactory.cloudApi(settings)
            val response = api.dailyAnalysis(
                DailyAnalysisRequest(
                    date = LocalDate.now().toString(),
                    locale = Locale.getDefault().toLanguageTag()
                )
            )
            val summary = buildString {
                append(response.summary)
                if (response.anomalies.isNotEmpty()) {
                    append("\nAnomalies: ")
                    append(response.anomalies.joinToString("; "))
                }
                if (response.recommendations.isNotEmpty()) {
                    append("\nRecommendations: ")
                    append(response.recommendations.joinToString("; "))
                }
                append("\n")
                append(localReportStatus.statusLine)
            }
            auditLogger.info("daily_analysis_completed")
            summary
        }.getOrElse { error ->
            val message = error.message ?: "daily analysis failed"
            auditLogger.error("daily_analysis_failed", mapOf("error" to message))
            buildString {
                append("Cloud daily analysis failed: ")
                append(message)
                append('\n')
                append(localReportStatus.statusLine)
            }
        }
    }

    suspend fun generateDailyForecastReport(hours: Int = 24): DailyForecastReportResult = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val safeHours = hours.coerceIn(6, 48)
        val reportSince = now - safeHours * 60L * 60L * 1000L
        val maxRollingDays = ROLLING_FORECAST_WINDOWS_DAYS.maxOrNull() ?: 1
        val rollingSince = now - maxRollingDays.toLong() * MILLIS_PER_DAY
        val dataSince = minOf(reportSince, rollingSince)
        val glucoseSince = dataSince - 2L * 60L * 60L * 1000L

        val allForecasts = db.forecastDao().since(dataSince)
            .asSequence()
            .filter { it.horizonMinutes in DAILY_FORECAST_HORIZONS }
            .toList()
        val glucose = db.glucoseDao().since(glucoseSince)
        val telemetry = db.telemetryDao().since(reportSince - 60L * 60L * 1000L)

        val forecasts = allForecasts
            .asSequence()
            .filter { it.timestamp in reportSince..now }
            .toList()

        val payload = buildDailyForecastReportPayloadStatic(
            forecasts = forecasts,
            glucose = glucose,
            telemetrySamples = telemetry,
            sinceTs = reportSince,
            untilTs = now
        )
        val isfCrDroppedSummary = collectIsfCrDroppedReasonSummary(sinceTs = reportSince)
        val isfCrQualityRecommendations = buildIsfCrDataQualityRecommendations(
            droppedTotal = isfCrDroppedSummary?.droppedTotal ?: 0,
            eventCount = isfCrDroppedSummary?.eventCount ?: 0,
            reasonCounts = isfCrDroppedSummary?.reasonCounts ?: emptyMap()
        )
        val enrichedPayload = payload.copy(
            recommendations = (payload.recommendations + isfCrQualityRecommendations).distinct()
        )
        val rollingPayloads = buildRollingForecastPayloadsStatic(
            forecasts = allForecasts,
            glucose = glucose,
            untilTs = now
        )

        val dir = outputDir("forecast-reports")
        val dateSuffix = LocalDate.now().toString()
        val markdownFile = File(dir, "forecast-report-$dateSuffix.md")
        val csvFile = File(dir, "forecast-report-$dateSuffix.csv")
        markdownFile.writeText(buildDailyForecastMarkdown(enrichedPayload, isfCrDroppedSummary))
        csvFile.writeText(buildDailyForecastCsv(enrichedPayload, isfCrDroppedSummary))

        persistDailyForecastReportTelemetry(
            nowTs = now,
            payload = enrichedPayload,
            markdownPath = markdownFile.absolutePath,
            isfCrDroppedSummary = isfCrDroppedSummary,
            rollingPayloads = rollingPayloads
        )

        val topHorizon = enrichedPayload.horizonStats.maxByOrNull { it.mardPct }
        auditLogger.info(
            "daily_forecast_report_generated",
            mapOf(
                "periodHours" to safeHours,
                "forecastRows" to enrichedPayload.forecastRows,
                "matchedSamples" to enrichedPayload.matchedSamples,
                "markdownPath" to markdownFile.absolutePath,
                "csvPath" to csvFile.absolutePath,
                "topMardHorizon" to topHorizon?.horizonMinutes,
                "topMardPct" to topHorizon?.mardPct,
                "isfCrDroppedEventCount" to isfCrDroppedSummary?.eventCount,
                "isfCrDroppedTotal" to isfCrDroppedSummary?.droppedTotal,
                "isfCrDroppedSource" to isfCrDroppedSummary?.sourceMessage,
                "rolling14dMatched" to rollingPayloads[14]?.matchedSamples,
                "rolling30dMatched" to rollingPayloads[30]?.matchedSamples,
                "rolling90dMatched" to rollingPayloads[90]?.matchedSamples
            )
        )

        DailyForecastReportResult(
            statusLine = buildString {
                append("Local forecast report 24h: matched=")
                append(enrichedPayload.matchedSamples)
                topHorizon?.let {
                    append(", top MARD ")
                    append(it.horizonMinutes)
                    append("m=")
                    append(String.format(Locale.US, "%.2f", it.mardPct))
                    append("%")
                }
            },
            markdownPath = markdownFile.absolutePath,
            csvPath = csvFile.absolutePath,
            matchedSamples = enrichedPayload.matchedSamples,
            topMardHorizon = topHorizon?.horizonMinutes,
            topMardPct = topHorizon?.mardPct
        )
    }

    suspend fun fetchAnalysisHistory(
        limit: Int = 14,
        source: String? = null,
        status: String? = null,
        days: Int = 60
    ): CloudAnalysisHistoryUiModel {
        val settings = settingsStore.settings.first()
        if (settings.cloudBaseUrl.isBlank()) {
            return CloudAnalysisHistoryUiModel(items = emptyList())
        }

        val safeLimit = limit.coerceIn(1, 60)
        val safeDays = days.coerceIn(1, 365)
        return runCatching {
            val api = apiFactory.cloudApi(settings)
            val response = api.analysisHistory(
                limit = safeLimit,
                source = source,
                status = status,
                days = safeDays
            )
            auditLogger.info("analysis_history_completed", mapOf("items" to response.items.size))
            CloudAnalysisHistoryUiModel(items = response.items)
        }.getOrElse { error ->
            val message = error.message ?: "analysis history failed"
            auditLogger.warn("analysis_history_failed", mapOf("error" to message))
            CloudAnalysisHistoryUiModel(items = emptyList())
        }
    }

    suspend fun fetchAnalysisTrend(
        weeks: Int = 8,
        source: String? = null,
        status: String? = null
    ): CloudAnalysisTrendUiModel {
        val settings = settingsStore.settings.first()
        if (settings.cloudBaseUrl.isBlank()) {
            return CloudAnalysisTrendUiModel(items = emptyList())
        }

        val safeWeeks = weeks.coerceIn(1, 52)
        return runCatching {
            val api = apiFactory.cloudApi(settings)
            val response = api.analysisTrend(
                weeks = safeWeeks,
                source = source,
                status = status
            )
            auditLogger.info("analysis_trend_completed", mapOf("items" to response.items.size))
            CloudAnalysisTrendUiModel(items = response.items)
        }.getOrElse { error ->
            val message = error.message ?: "analysis trend failed"
            auditLogger.warn("analysis_trend_failed", mapOf("error" to message))
            CloudAnalysisTrendUiModel(items = emptyList())
        }
    }

    suspend fun fetchJobsStatus(): CloudJobsUiModel {
        val settings = settingsStore.settings.first()
        if (settings.cloudBaseUrl.isBlank()) {
            return CloudJobsUiModel(timezone = "local", jobs = emptyList())
        }

        return runCatching {
            val api = apiFactory.cloudApi(settings)
            val response = api.jobsStatus()
            auditLogger.info("cloud_jobs_status_completed", mapOf("jobs" to response.jobs.size))
            CloudJobsUiModel(
                timezone = response.timezone,
                jobs = response.jobs
            )
        }.getOrElse { error ->
            val message = error.message ?: "jobs status failed"
            auditLogger.warn("cloud_jobs_status_failed", mapOf("error" to message))
            CloudJobsUiModel(timezone = "unknown", jobs = emptyList())
        }
    }

    suspend fun runReplayReport(days: Int, stepMinutes: Int = 5): CloudReplayUiModel {
        val settings = settingsStore.settings.first()
        if (settings.cloudBaseUrl.isBlank()) {
            error("Cloud API not configured")
        }

        val clampedDays = days.coerceIn(1, 60)
        val clampedStep = stepMinutes.coerceIn(5, 60)
        val until = System.currentTimeMillis()
        val since = until - clampedDays * 24L * 60 * 60 * 1000

        val api = apiFactory.cloudApi(settings)
        return try {
            val response = api.replayReport(ReplayReportRequest(since = since, until = until, stepMinutes = clampedStep))
            auditLogger.info("cloud_replay_completed", mapOf("days" to clampedDays, "points" to response.points))

            CloudReplayUiModel(
                days = clampedDays,
                points = response.points,
                stepMinutes = clampedStep,
                forecastStats = response.forecastStats,
                ruleStats = response.ruleStats,
                dayTypeStats = response.dayTypeStats,
                hourlyStats = response.hourlyStats,
                driftStats = response.driftStats
            )
        } catch (error: Exception) {
            val message = error.message ?: "cloud replay failed"
            auditLogger.error("cloud_replay_failed", mapOf("error" to message, "days" to clampedDays))
            throw error
        }
    }

    suspend fun exportReplayCsv(report: CloudReplayUiModel): String = withContext(Dispatchers.IO) {
        val dir = outputDir("replay-exports")
        val ts = timestampSuffix()
        val file = File(dir, "replay-$ts.csv")
        file.writeText(ReplayExportFormatter.buildCsv(report))
        auditLogger.info("replay_csv_exported", mapOf("path" to file.absolutePath))
        file.absolutePath
    }

    suspend fun exportReplayPdf(report: CloudReplayUiModel, horizonFilter: Int?): String = withContext(Dispatchers.IO) {
        val dir = outputDir("replay-exports")
        val ts = timestampSuffix()
        val file = File(dir, "replay-$ts.pdf")

        val pdf = PdfDocument()
        val pageInfo = PdfDocument.PageInfo.Builder(1080, 1920, 1).create()
        var page = pdf.startPage(pageInfo)
        var canvas = page.canvas
        val paint = Paint().apply { textSize = 26f }
        var y = 60f
        var pageNumber = 1

        val lines = ReplayExportFormatter.buildPdfLines(report, horizonFilter)
        for (line in lines) {
            if (y > 1860f) {
                pdf.finishPage(page)
                pageNumber += 1
                val nextInfo = PdfDocument.PageInfo.Builder(1080, 1920, pageNumber).create()
                page = pdf.startPage(nextInfo)
                canvas = page.canvas
                y = 60f
            }
            canvas.drawText(line, 40f, y, paint)
            y += 34f
        }
        pdf.finishPage(page)
        file.outputStream().use { pdf.writeTo(it) }
        pdf.close()
        auditLogger.info("replay_pdf_exported", mapOf("path" to file.absolutePath))
        file.absolutePath
    }

    suspend fun exportAnalysisCsv(
        history: CloudAnalysisHistoryUiModel,
        trend: CloudAnalysisTrendUiModel,
        filterLabel: String
    ): String = withContext(Dispatchers.IO) {
        val dir = outputDir("insights-exports")
        val ts = timestampSuffix()
        val file = File(dir, "insights-$ts.csv")
        file.writeText(InsightsExportFormatter.buildCsv(history, trend, filterLabel))
        auditLogger.info("insights_csv_exported", mapOf("path" to file.absolutePath))
        file.absolutePath
    }

    suspend fun exportAnalysisPdf(
        history: CloudAnalysisHistoryUiModel,
        trend: CloudAnalysisTrendUiModel,
        filterLabel: String
    ): String = withContext(Dispatchers.IO) {
        val dir = outputDir("insights-exports")
        val ts = timestampSuffix()
        val file = File(dir, "insights-$ts.pdf")

        val pdf = PdfDocument()
        val pageInfo = PdfDocument.PageInfo.Builder(1080, 1920, 1).create()
        var page = pdf.startPage(pageInfo)
        var canvas = page.canvas
        val paint = Paint().apply { textSize = 26f }
        var y = 60f
        var pageNumber = 1

        val lines = InsightsExportFormatter.buildPdfLines(history, trend, filterLabel)
        for (line in lines) {
            if (y > 1860f) {
                pdf.finishPage(page)
                pageNumber += 1
                val nextInfo = PdfDocument.PageInfo.Builder(1080, 1920, pageNumber).create()
                page = pdf.startPage(nextInfo)
                canvas = page.canvas
                y = 60f
            }
            canvas.drawText(line, 40f, y, paint)
            y += 34f
        }
        pdf.finishPage(page)
        file.outputStream().use { pdf.writeTo(it) }
        pdf.close()
        auditLogger.info("insights_pdf_exported", mapOf("path" to file.absolutePath))
        file.absolutePath
    }

    private suspend fun persistDailyForecastReportTelemetry(
        nowTs: Long,
        payload: DailyForecastReportPayload,
        markdownPath: String,
        isfCrDroppedSummary: IsfCrDroppedReasonSummary?,
        rollingPayloads: Map<Int, DailyForecastReportPayload>
    ) {
        val source = "forecast_daily_report"
        val rows = mutableListOf<io.aaps.copilot.data.local.entity.TelemetrySampleEntity>()

        fun addNumeric(key: String, value: Double?, unit: String? = null) {
            rows += io.aaps.copilot.data.local.entity.TelemetrySampleEntity(
                id = "tm-$source-$key-$nowTs",
                timestamp = nowTs,
                source = source,
                key = key,
                valueDouble = value,
                valueText = null,
                unit = unit,
                quality = if (value == null) "STALE" else "OK"
            )
        }

        fun addText(key: String, value: String?) {
            val text = value?.trim().orEmpty()
            rows += io.aaps.copilot.data.local.entity.TelemetrySampleEntity(
                id = "tm-$source-$key-$nowTs",
                timestamp = nowTs,
                source = source,
                key = key,
                valueDouble = null,
                valueText = text.ifBlank { null },
                unit = null,
                quality = if (text.isBlank()) "STALE" else "OK"
            )
        }

        addNumeric("daily_report_matched_samples", payload.matchedSamples.toDouble())
        addNumeric("daily_report_forecast_rows", payload.forecastRows.toDouble())
        addText("daily_report_markdown_path", markdownPath)
        addText("daily_report_period_start", formatUtc(payload.sinceTs))
        addText("daily_report_period_end", formatUtc(payload.untilTs))
        addNumeric("daily_report_isfcr_dropped_event_count", isfCrDroppedSummary?.eventCount?.toDouble())
        addNumeric("daily_report_isfcr_dropped_total", isfCrDroppedSummary?.droppedTotal?.toDouble())
        addText("daily_report_isfcr_dropped_source", isfCrDroppedSummary?.sourceMessage)
        val isfCrDroppedTopReasons = isfCrDroppedSummary
            ?.reasonCounts
            ?.entries
            ?.sortedByDescending { it.value }
            ?.take(6)
            ?.joinToString(";") { "${it.key}=${it.value}" }
        addText("daily_report_isfcr_dropped_top_reasons", isfCrDroppedTopReasons)
        val isfCrQualityRisk = buildIsfCrDataQualityRiskLabel(
            eventCount = isfCrDroppedSummary?.eventCount,
            droppedTotal = isfCrDroppedSummary?.droppedTotal,
            reasonCounts = isfCrDroppedSummary?.reasonCounts ?: emptyMap()
        )
        val isfCrQualityRiskLevel = buildIsfCrDataQualityRiskLevel(
            eventCount = isfCrDroppedSummary?.eventCount,
            droppedTotal = isfCrDroppedSummary?.droppedTotal,
            reasonCounts = isfCrDroppedSummary?.reasonCounts ?: emptyMap()
        )
        addText("daily_report_isfcr_quality_risk", isfCrQualityRisk)
        addNumeric("daily_report_isfcr_quality_risk_level", isfCrQualityRiskLevel.toDouble())
        if (isfCrDroppedSummary != null && isfCrDroppedSummary.droppedTotal > 0) {
            val gap = isfCrDroppedSummary.reasonCounts["cr_gross_gap"] ?: 0
            val sensor = isfCrDroppedSummary.reasonCounts["cr_sensor_blocked"] ?: 0
            val uam = isfCrDroppedSummary.reasonCounts["cr_uam_ambiguity"] ?: 0
            val droppedTotal = isfCrDroppedSummary.droppedTotal.toDouble()
            addNumeric("daily_report_isfcr_cr_gap_drop_rate_pct", gap * 100.0 / droppedTotal, "%")
            addNumeric("daily_report_isfcr_cr_sensor_drop_rate_pct", sensor * 100.0 / droppedTotal, "%")
            addNumeric("daily_report_isfcr_cr_uam_drop_rate_pct", uam * 100.0 / droppedTotal, "%")
        }

        payload.horizonStats.forEach { stat ->
            addNumeric("daily_report_mae_${stat.horizonMinutes}m", stat.mae, "mmol/L")
            addNumeric("daily_report_rmse_${stat.horizonMinutes}m", stat.rmse, "mmol/L")
            addNumeric("daily_report_mard_${stat.horizonMinutes}m_pct", stat.mardPct, "%")
            addNumeric("daily_report_bias_${stat.horizonMinutes}m", stat.bias, "mmol/L")
            addNumeric("daily_report_n_${stat.horizonMinutes}m", stat.sampleCount.toDouble())
            addNumeric("daily_report_ci_coverage_${stat.horizonMinutes}m_pct", stat.ciCoveragePct, "%")
            addNumeric("daily_report_ci_width_${stat.horizonMinutes}m", stat.ciMeanWidth, "mmol/L")
        }
        (1..3).forEach { index ->
            addText("daily_report_recommendation_$index", payload.recommendations.getOrNull(index - 1))
        }
        data class PairRatioSummary(
            val factorA: String,
            val factorB: String,
            val ratio: Double,
            val highHighMae: Double,
            val lowLowMae: Double
        )
        fun topPairRatioForHorizon(horizon: Int): PairRatioSummary? {
            return payload.replayFactorPairs
                .asSequence()
                .filter { it.horizonMinutes == horizon }
                .groupBy { it.factorA to it.factorB }
                .mapNotNull { (pair, rows) ->
                    val lowLow = rows.firstOrNull { it.bucketA == REGIME_LOW && it.bucketB == REGIME_LOW }
                    val highHigh = rows.firstOrNull { it.bucketA == REGIME_HIGH && it.bucketB == REGIME_HIGH }
                    if (
                        lowLow == null ||
                        highHigh == null ||
                        lowLow.sampleCount < 4 ||
                        highHigh.sampleCount < 4 ||
                        lowLow.mae <= 1e-9
                    ) {
                        return@mapNotNull null
                    }
                    PairRatioSummary(
                        factorA = pair.first,
                        factorB = pair.second,
                        ratio = highHigh.mae / lowLow.mae,
                        highHighMae = highHigh.mae,
                        lowLowMae = lowLow.mae
                    )
                }
                .maxByOrNull { it.ratio }
        }
        DAILY_FORECAST_HORIZONS.forEach { horizon ->
            val topFactor = payload.factorContributions
                .asSequence()
                .filter { it.horizonMinutes == horizon }
                .maxByOrNull { it.contributionScore }
            val topHotspot = payload.replayHotspots
                .asSequence()
                .filter { it.horizonMinutes == horizon }
                .maxByOrNull { it.mae }
            val topMiss = payload.replayTopMisses
                .asSequence()
                .firstOrNull { it.horizonMinutes == horizon }
            val topPair = topPairRatioForHorizon(horizon)
            val topCluster = payload.replayErrorClusters
                .asSequence()
                .filter { it.horizonMinutes == horizon }
                .maxByOrNull { it.mae }
            val topDayTypeGap = payload.replayDayTypeGaps
                .asSequence()
                .filter { it.horizonMinutes == horizon }
                .maxByOrNull { abs(it.maeGapMmol) }
            addText(
                "daily_report_replay_top_factor_${horizon}m",
                topFactor?.let {
                    "${it.factor};score=${fmt(it.contributionScore)};corr=${fmt(it.corrAbsError)};upliftPct=${fmt(it.upliftPct)};n=${it.sampleCount}"
                }
            )
            addText(
                "daily_report_replay_top_factor_hint_${horizon}m",
                topFactor?.let {
                    factorRecommendationHint(
                        factor = it.factor,
                        horizonMinutes = horizon,
                        corrAbsError = it.corrAbsError,
                        upliftPct = it.upliftPct
                    )
                }
            )
            addText(
                "daily_report_replay_hotspot_${horizon}m",
                topHotspot?.let {
                    "hour=${it.hour.toString().padStart(2, '0')};mae=${fmt(it.mae)};mardPct=${fmt(it.mardPct)};n=${it.sampleCount}"
                }
            )
            addText(
                "daily_report_replay_top_miss_${horizon}m",
                topMiss?.let {
                    "ts=${formatUtc(it.ts)};absErr=${fmt(it.absError)};pred=${fmt(it.pred)};actual=${fmt(it.actual)};cob=${fmt(it.cob)};iob=${fmt(it.iob)};uam=${fmt(it.uam)};ciw=${fmt(it.ciWidth)};dia=${fmt(it.diaHours)};activity=${fmt(it.activity)};sensorQ=${fmt(it.sensorQuality)}"
                }
            )
            addText(
                "daily_report_replay_top_pair_${horizon}m",
                topPair?.let {
                    "${it.factorA}x${it.factorB};hhllRatio=${fmt(it.ratio)};hhMae=${fmt(it.highHighMae)};llMae=${fmt(it.lowLowMae)}"
                }
            )
            addText(
                "daily_report_replay_top_pair_hint_${horizon}m",
                topPair?.let {
                    if (it.ratio >= 1.35) {
                        "${horizon}m pair ${it.factorA}×${it.factorB} is unstable in high/high regime (ratio=${fmt(it.ratio)})."
                    } else if (it.ratio <= 0.75) {
                        "${horizon}m pair ${it.factorA}×${it.factorB} appears protective in high/high regime (ratio=${fmt(it.ratio)})."
                    } else {
                        null
                    }
                }
            )
            addText(
                "daily_report_replay_error_cluster_${horizon}m",
                topCluster?.let {
                    "dayType=${it.dayType};hour=${it.hour.toString().padStart(2, '0')};mae=${fmt(it.mae)};mardPct=${fmt(it.mardPct)};cob=${fmt(it.meanCob)};iob=${fmt(it.meanIob)};uam=${fmt(it.meanUam)};ci=${fmt(it.meanCiWidth)};n=${it.sampleCount}"
                }
            )
            addText(
                "daily_report_replay_error_cluster_hint_${horizon}m",
                topCluster?.let { cluster ->
                    cluster.dominantFactor()?.let { dominant ->
                        "${horizon}m ${cluster.dayType.lowercase(Locale.US)} cluster driver=${dominant.first} (norm=${fmt(dominant.second)}) around ${cluster.hour.toString().padStart(2, '0')}:00."
                    }
                }
            )
            addText(
                "daily_report_replay_daytype_gap_${horizon}m",
                topDayTypeGap?.let {
                    "hour=${it.hour.toString().padStart(2, '0')};worse=${it.worseDayType};maeGap=${fmt(it.maeGapMmol)};weekdayMae=${fmt(it.weekdayMae)};weekendMae=${fmt(it.weekendMae)};weekdayN=${it.weekdaySampleCount};weekendN=${it.weekendSampleCount}"
                }
            )
            addText(
                "daily_report_replay_daytype_gap_hint_${horizon}m",
                topDayTypeGap?.takeIf { abs(it.maeGapMmol) >= 0.25 }?.let {
                    "${horizon}m ${it.worseDayType.lowercase(Locale.US)} is worse at ${it.hour.toString().padStart(2, '0')}:00 (ΔMAE=${fmt(it.maeGapMmol)})."
                }
            )
        }
        val overallTopFactors = payload.factorContributions
            .groupBy { it.factor }
            .mapValues { (_, rows) ->
                rows.map { it.contributionScore }.average()
            }
            .entries
            .sortedByDescending { it.value }
            .take(4)
            .joinToString(";") { "${it.key}=${fmt(it.value)}" }
            .ifBlank { null }
        addText("daily_report_replay_top_factors_overall", overallTopFactors)
        addText("daily_report_replay_hotspots_json", serializeReplayHotspots(payload.replayHotspots))
        addText("daily_report_replay_factors_json", serializeReplayFactorContributions(payload.factorContributions))
        addText("daily_report_replay_factor_coverage_json", serializeReplayFactorCoverage(payload.replayFactorCoverage))
        addText("daily_report_replay_factor_regime_json", serializeReplayFactorRegimes(payload.replayFactorRegimes))
        addText("daily_report_replay_factor_pair_json", serializeReplayFactorPairs(payload.replayFactorPairs))
        addText("daily_report_replay_top_miss_json", serializeReplayTopMisses(payload.replayTopMisses))
        addText("daily_report_replay_error_cluster_json", serializeReplayErrorClusters(payload.replayErrorClusters))
        addText("daily_report_replay_daytype_gap_json", serializeReplayDayTypeGaps(payload.replayDayTypeGaps))
        rollingPayloads.forEach { (days, rollingPayload) ->
            val prefix = "rolling_report_${days}d"
            addNumeric("${prefix}_matched_samples", rollingPayload.matchedSamples.toDouble())
            addNumeric("${prefix}_forecast_rows", rollingPayload.forecastRows.toDouble())
            addText("${prefix}_period_start", formatUtc(rollingPayload.sinceTs))
            addText("${prefix}_period_end", formatUtc(rollingPayload.untilTs))
            rollingPayload.horizonStats.forEach { stat ->
                addNumeric("${prefix}_mae_${stat.horizonMinutes}m", stat.mae, "mmol/L")
                addNumeric("${prefix}_rmse_${stat.horizonMinutes}m", stat.rmse, "mmol/L")
                addNumeric("${prefix}_mard_${stat.horizonMinutes}m_pct", stat.mardPct, "%")
                addNumeric("${prefix}_bias_${stat.horizonMinutes}m", stat.bias, "mmol/L")
                addNumeric("${prefix}_n_${stat.horizonMinutes}m", stat.sampleCount.toDouble())
                addNumeric("${prefix}_ci_coverage_${stat.horizonMinutes}m_pct", stat.ciCoveragePct, "%")
                addNumeric("${prefix}_ci_width_${stat.horizonMinutes}m", stat.ciMeanWidth, "mmol/L")
            }
        }

        db.telemetryDao().upsertAll(rows)
    }

    private suspend fun collectIsfCrDroppedReasonSummary(sinceTs: Long): IsfCrDroppedReasonSummary? {
        val primary = db.auditLogDao().recentByMessage(
            message = "isfcr_evidence_extracted",
            sinceTs = sinceTs,
            limit = 2000
        )
        val selected = if (primary.isNotEmpty()) {
            primary to "isfcr_evidence_extracted"
        } else {
            val fallback = db.auditLogDao().recentByMessage(
                message = "isfcr_realtime_computed",
                sinceTs = sinceTs,
                limit = 2000
            )
            if (fallback.isEmpty()) return null
            fallback to "isfcr_realtime_computed"
        }
        val events = selected.first
        var droppedTotal = 0
        val reasonCounts = linkedMapOf<String, Int>()
        events.forEach { entry ->
            val meta = runCatching { JSONObject(entry.metadataJson) }.getOrNull() ?: return@forEach
            droppedTotal += meta.optInt("droppedEvidence", 0).coerceAtLeast(0)
            val droppedReasonsRaw = if (meta.has("droppedReasons") && !meta.isNull("droppedReasons")) {
                meta.optString("droppedReasons")
            } else {
                null
            }
            parseDroppedReasonCounters(droppedReasonsRaw).forEach { (reason, count) ->
                reasonCounts[reason] = (reasonCounts[reason] ?: 0) + count
            }
        }
        return IsfCrDroppedReasonSummary(
            eventCount = events.size,
            droppedTotal = droppedTotal,
            reasonCounts = reasonCounts,
            sourceMessage = selected.second
        )
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
                val count = parts.getOrNull(1)?.trim()?.toIntOrNull()?.coerceAtLeast(0) ?: 1
                counters[key] = (counters[key] ?: 0) + count
            }
        return counters
    }

    private fun buildDailyForecastMarkdown(
        payload: DailyForecastReportPayload,
        isfCrDroppedSummary: IsfCrDroppedReasonSummary?
    ): String {
        val lines = mutableListOf<String>()
        lines += "# Forecast Daily Report"
        lines += ""
        lines += "- Period: ${formatUtc(payload.sinceTs)} -> ${formatUtc(payload.untilTs)}"
        lines += "- Forecast rows: ${payload.forecastRows}"
        lines += "- Matched samples: ${payload.matchedSamples}"
        lines += ""
        lines += "## Horizon Metrics"
        if (payload.horizonStats.isEmpty()) {
            lines += "- No matched forecast samples for 5/30/60m."
        } else {
            lines += "| Horizon | n | MAE (mmol/L) | RMSE (mmol/L) | MARD (%) | Bias (mmol/L) | CI cover (%) | CI width (mmol/L) |"
            lines += "|---|---:|---:|---:|---:|---:|---:|---:|"
            payload.horizonStats
                .sortedBy { it.horizonMinutes }
                .forEach { stat ->
                    lines += "| ${stat.horizonMinutes}m | ${stat.sampleCount} | ${fmt(stat.mae)} | ${fmt(stat.rmse)} | ${fmt(stat.mardPct)} | ${fmt(stat.bias)} | ${fmt(stat.ciCoveragePct)} | ${fmt(stat.ciMeanWidth)} |"
                }
        }
        lines += ""
        lines += "## Hourly Hotspots (worst MARD)"
        if (payload.hourlyStats.isEmpty()) {
            lines += "- Not enough hourly matched samples."
        } else {
            lines += "| Hour | Horizon | n | MAE | MARD (%) | Bias |"
            lines += "|---:|---:|---:|---:|---:|---:|"
            payload.hourlyStats
                .sortedByDescending { it.mardPct }
                .take(12)
                .forEach { hour ->
                    lines += "| ${hour.hour.toString().padStart(2, '0')} | ${hour.horizonMinutes}m | ${hour.sampleCount} | ${fmt(hour.mae)} | ${fmt(hour.mardPct)} | ${fmt(hour.bias)} |"
                }
        }
        lines += ""
        lines += "## Glucose Bands"
        if (payload.bandStats.isEmpty()) {
            lines += "- No band-level metrics."
        } else {
            lines += "| Band | Horizon | n | MAE | MARD (%) |"
            lines += "|---|---:|---:|---:|---:|"
            payload.bandStats.forEach { band ->
                lines += "| ${band.bandLabel} | ${band.horizonMinutes}m | ${band.sampleCount} | ${fmt(band.mae)} | ${fmt(band.mardPct)} |"
            }
        }
        lines += ""
        lines += "## Worst Errors"
        if (payload.worstSamples.isEmpty()) {
            lines += "- No matched samples."
        } else {
            lines += "| Timestamp (UTC) | Horizon | Pred | Actual | Abs Err | ARD (%) | Model |"
            lines += "|---|---:|---:|---:|---:|---:|---|"
            payload.worstSamples.forEach { row ->
                lines += "| ${formatUtc(row.ts)} | ${row.horizonMinutes}m | ${fmt(row.pred)} | ${fmt(row.actual)} | ${fmt(row.absError)} | ${fmt(row.ardPct)} | ${row.modelFamily} |"
            }
        }
        lines += ""
        lines += "## Targeted Replay 24h: Error Hotspots"
        if (payload.replayHotspots.isEmpty()) {
            lines += "- Not enough samples for hourly hotspots."
        } else {
            lines += "| Horizon | Hour | n | MAE | MARD (%) | Bias |"
            lines += "|---:|---:|---:|---:|---:|---:|"
            payload.replayHotspots.forEach { row ->
                lines += "| ${row.horizonMinutes}m | ${row.hour.toString().padStart(2, '0')} | ${row.sampleCount} | ${fmt(row.mae)} | ${fmt(row.mardPct)} | ${fmt(row.bias)} |"
            }
        }
        lines += ""
        lines += "## Targeted Replay 24h: Factor Contributions"
        if (payload.factorContributions.isEmpty()) {
            lines += "- Factor attribution unavailable (insufficient matched points or missing factor telemetry)."
        } else {
            lines += "| Horizon | Factor | n | Corr(absErr) | MAE high quartile | MAE low quartile | Uplift (%) | Score |"
            lines += "|---:|---|---:|---:|---:|---:|---:|---:|"
            payload.factorContributions.forEach { row ->
                lines += "| ${row.horizonMinutes}m | ${row.factor} | ${row.sampleCount} | ${fmt(row.corrAbsError)} | ${fmt(row.maeHigh)} | ${fmt(row.maeLow)} | ${fmt(row.upliftPct)} | ${fmt(row.contributionScore)} |"
            }
        }
        lines += ""
        lines += "## Targeted Replay 24h: Factor Coverage"
        if (payload.replayFactorCoverage.isEmpty()) {
            lines += "- Factor coverage unavailable."
        } else {
            lines += "| Horizon | Factor | matched n | Coverage (%) |"
            lines += "|---:|---|---:|---:|"
            payload.replayFactorCoverage.forEach { row ->
                lines += "| ${row.horizonMinutes}m | ${row.factor} | ${row.sampleCount} | ${fmt(row.coveragePct)} |"
            }
        }
        lines += ""
        lines += "## Targeted Replay 24h: Core Factor Regimes (Low/Mid/High)"
        if (payload.replayFactorRegimes.isEmpty()) {
            lines += "- Core factor regimes unavailable."
        } else {
            lines += "| Horizon | Factor | Regime | n | Mean factor | MAE | MARD (%) | Bias |"
            lines += "|---:|---|---|---:|---:|---:|---:|---:|"
            payload.replayFactorRegimes.forEach { row ->
                lines += "| ${row.horizonMinutes}m | ${row.factor} | ${row.bucket} | ${row.sampleCount} | ${fmt(row.meanFactorValue)} | ${fmt(row.mae)} | ${fmt(row.mardPct)} | ${fmt(row.bias)} |"
            }
        }
        lines += ""
        lines += "## Targeted Replay 24h: Core Factor Pair Regimes"
        if (payload.replayFactorPairs.isEmpty()) {
            lines += "- Core factor pair regimes unavailable."
        } else {
            lines += "| Horizon | Pair | Regime | n | Mean A | Mean B | MAE | MARD (%) | Bias |"
            lines += "|---:|---|---|---:|---:|---:|---:|---:|---:|"
            payload.replayFactorPairs.forEach { row ->
                lines += "| ${row.horizonMinutes}m | ${row.factorA}×${row.factorB} | ${row.bucketA}×${row.bucketB} | ${row.sampleCount} | ${fmt(row.meanFactorA)} | ${fmt(row.meanFactorB)} | ${fmt(row.mae)} | ${fmt(row.mardPct)} | ${fmt(row.bias)} |"
            }
        }
        lines += ""
        lines += "## Targeted Replay 24h: Top Miss Context"
        if (payload.replayTopMisses.isEmpty()) {
            lines += "- Top miss context unavailable."
        } else {
            lines += "| Horizon | Timestamp (UTC) | Abs err | Pred | Actual | COB | IOB | UAM | CI width | DIA | Activity | Sensor Q |"
            lines += "|---:|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|"
            payload.replayTopMisses.forEach { row ->
                lines += "| ${row.horizonMinutes}m | ${formatUtc(row.ts)} | ${fmt(row.absError)} | ${fmt(row.pred)} | ${fmt(row.actual)} | ${fmt(row.cob)} | ${fmt(row.iob)} | ${fmt(row.uam)} | ${fmt(row.ciWidth)} | ${fmt(row.diaHours)} | ${fmt(row.activity)} | ${fmt(row.sensorQuality)} |"
            }
        }
        lines += ""
        lines += "## Targeted Replay 24h: Error Clusters (DayType + Hour + Mean Factors)"
        if (payload.replayErrorClusters.isEmpty()) {
            lines += "- Error clusters unavailable."
        } else {
            lines += "| Horizon | DayType | Hour | n | MAE | MARD (%) | Bias | Mean COB | Mean IOB | Mean UAM | Mean CI width | Dominant factor |"
            lines += "|---:|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---|"
            payload.replayErrorClusters.forEach { row ->
                val dominant = row.dominantFactor()?.let { "${it.first} (${fmt(it.second)})" } ?: "-"
                lines += "| ${row.horizonMinutes}m | ${row.dayType} | ${row.hour.toString().padStart(2, '0')} | ${row.sampleCount} | ${fmt(row.mae)} | ${fmt(row.mardPct)} | ${fmt(row.bias)} | ${fmt(row.meanCob)} | ${fmt(row.meanIob)} | ${fmt(row.meanUam)} | ${fmt(row.meanCiWidth)} | $dominant |"
            }
        }
        lines += ""
        lines += "## Targeted Replay 24h: Weekday vs Weekend Gap"
        if (payload.replayDayTypeGaps.isEmpty()) {
            lines += "- Day-type gap diagnostics unavailable."
        } else {
            lines += "| Horizon | Hour | Worse day-type | ΔMAE (weekend-weekday) | Weekday MAE (n) | Weekend MAE (n) | ΔMARD (%) | Worse COB/IOB/UAM/CI | Driver |"
            lines += "|---:|---:|---|---:|---|---|---:|---|---|"
            payload.replayDayTypeGaps.forEach { row ->
                val driver = row.dominantFactor?.let { factor ->
                    val score = row.dominantScore?.let { " (${fmt(it)})" }.orEmpty()
                    "$factor$score"
                } ?: "-"
                val worseFactors = "COB=${fmt(row.worseMeanCob)}, IOB=${fmt(row.worseMeanIob)}, UAM=${fmt(row.worseMeanUam)}, CI=${fmt(row.worseMeanCiWidth)}"
                lines += "| ${row.horizonMinutes}m | ${row.hour.toString().padStart(2, '0')} | ${row.worseDayType} | ${fmt(row.maeGapMmol)} | ${fmt(row.weekdayMae)} (${row.weekdaySampleCount}) | ${fmt(row.weekendMae)} (${row.weekendSampleCount}) | ${fmt(row.mardGapPct)} | $worseFactors | $driver |"
            }
        }
        lines += ""
        lines += "## ISF/CR Data Quality"
        val qualityLines = buildIsfCrDroppedQualityLines(
            sourceMessage = isfCrDroppedSummary?.sourceMessage,
            eventCount = isfCrDroppedSummary?.eventCount,
            droppedTotal = isfCrDroppedSummary?.droppedTotal,
            reasonCounts = isfCrDroppedSummary?.reasonCounts ?: emptyMap()
        )
        if (qualityLines.isEmpty()) {
            lines += "- No ISF/CR dropped-evidence summary in this window."
        } else {
            qualityLines.forEach { line -> lines += "- $line" }
        }
        lines += ""
        lines += "## Recommendations To Lower MARD"
        if (payload.recommendations.isEmpty()) {
            lines += "- Keep current settings; no strong local degradation signal in last 24h."
        } else {
            payload.recommendations.forEach { lines += "- $it" }
        }
        return lines.joinToString(separator = "\n")
    }

    private fun buildDailyForecastCsv(
        payload: DailyForecastReportPayload,
        isfCrDroppedSummary: IsfCrDroppedReasonSummary?
    ): String {
        val rows = mutableListOf<String>()
        rows += "section,key,horizon,band,hour,sampleCount,mae,rmse,mardPct,bias,ciCoveragePct,ciMeanWidth,timestamp,pred,actual,absError,ardPct,model"
        buildIsfCrDroppedQualityLines(
            sourceMessage = isfCrDroppedSummary?.sourceMessage,
            eventCount = isfCrDroppedSummary?.eventCount,
            droppedTotal = isfCrDroppedSummary?.droppedTotal,
            reasonCounts = isfCrDroppedSummary?.reasonCounts ?: emptyMap()
        ).forEach { line ->
            rows += "quality,isfcr_dropped_summary,,,,,,,,,,,\"${line.replace("\"", "\"\"")}\",,,,,"
        }
        payload.horizonStats.forEach { stat ->
            rows += "horizon,metrics,${stat.horizonMinutes},,,${stat.sampleCount},${fmt(stat.mae)},${fmt(stat.rmse)},${fmt(stat.mardPct)},${fmt(stat.bias)},${fmt(stat.ciCoveragePct)},${fmt(stat.ciMeanWidth)},,,,,,"
        }
        payload.hourlyStats.forEach { stat ->
            rows += "hourly,metrics,${stat.horizonMinutes},,${stat.hour},${stat.sampleCount},${fmt(stat.mae)},,${fmt(stat.mardPct)},${fmt(stat.bias)},,,,,,,"
        }
        payload.bandStats.forEach { stat ->
            rows += "band,metrics,${stat.horizonMinutes},${stat.bandLabel},,${stat.sampleCount},${fmt(stat.mae)},,${fmt(stat.mardPct)},,,,,,,,"
        }
        payload.worstSamples.forEach { row ->
            rows += "worst,sample,${row.horizonMinutes},,,,,,,,,,${row.ts},${fmt(row.pred)},${fmt(row.actual)},${fmt(row.absError)},${fmt(row.ardPct)},${row.modelFamily}"
        }
        payload.replayHotspots.forEach { row ->
            rows += "replay_hotspot,hourly,${row.horizonMinutes},,${row.hour},${row.sampleCount},${fmt(row.mae)},,${fmt(row.mardPct)},${fmt(row.bias)},,,,,,,"
        }
        payload.factorContributions.forEach { row ->
            rows += "factor_contribution,${row.factor},${row.horizonMinutes},,,${row.sampleCount},${fmt(row.maeHigh)},,${fmt(row.upliftPct)},${fmt(row.corrAbsError)},,,${fmt(row.maeLow)},,,${fmt(row.contributionScore)},"
        }
        payload.replayFactorCoverage.forEach { row ->
            rows += "factor_coverage,${row.factor},${row.horizonMinutes},,,${row.sampleCount},,,,,,,,,,${fmt(row.coveragePct)},"
        }
        payload.replayFactorRegimes.forEach { row ->
            rows += "factor_regime,${row.factor},${row.horizonMinutes},${row.bucket},,${row.sampleCount},${fmt(row.mae)},,${fmt(row.mardPct)},${fmt(row.bias)},,,,,${fmt(row.meanFactorValue)},,"
        }
        payload.replayFactorPairs.forEach { row ->
            rows += "factor_pair_regime,${row.factorA}x${row.factorB},${row.horizonMinutes},${row.bucketA}x${row.bucketB},,${row.sampleCount},${fmt(row.mae)},,${fmt(row.mardPct)},${fmt(row.bias)},,,,,${fmt(row.meanFactorA)},${fmt(row.meanFactorB)},"
        }
        payload.replayTopMisses.forEach { row ->
            rows += "replay_top_miss,context,${row.horizonMinutes},,,1,${fmt(row.absError)},,${fmt(row.uam)},,${fmt(row.sensorQuality)},${fmt(row.ciWidth)},${row.ts},${fmt(row.pred)},${fmt(row.actual)},,,,,"
            rows += "replay_top_miss_ctx,COB,${row.horizonMinutes},,,1,${fmt(row.cob)},,,${fmt(row.iob)},,,,,,,,,"
            rows += "replay_top_miss_ctx,DIA_H,${row.horizonMinutes},,,1,${fmt(row.diaHours)},,,${fmt(row.activity)},,,,,,,,,"
        }
        payload.replayErrorClusters.forEach { row ->
            rows += "replay_error_cluster,hourly,${row.horizonMinutes},${row.dayType},${row.hour},${row.sampleCount},${fmt(row.mae)},,${fmt(row.mardPct)},${fmt(row.bias)},,,,${fmt(row.meanCob)},${fmt(row.meanIob)},${fmt(row.meanUam)},${fmt(row.meanCiWidth)}"
        }
        payload.replayDayTypeGaps.forEach { row ->
            val driver = row.dominantFactor?.let { "${it}${row.dominantScore?.let { s -> "(${fmt(s)})" } ?: ""}" } ?: ""
            rows += "replay_daytype_gap,${row.worseDayType},${row.horizonMinutes},,${row.hour},${row.weekdaySampleCount + row.weekendSampleCount},${fmt(row.maeGapMmol)},,${fmt(row.mardGapPct)},,,,,${fmt(row.weekdayMae)},${fmt(row.weekendMae)},${fmt(row.worseMeanCob)},${driver}"
        }
        return rows.joinToString(separator = "\n")
    }

    private fun formatUtc(ts: Long): String {
        return DateTimeFormatter.ISO_INSTANT.format(java.time.Instant.ofEpochMilli(ts))
    }

    private fun fmt(value: Double): String = String.format(Locale.US, "%.3f", value)

    companion object {
        private const val DAILY_FORECAST_REPORT_HOURS = 24
        private const val MILLIS_PER_DAY = 24L * 60L * 60L * 1000L
        private val ROLLING_FORECAST_WINDOWS_DAYS = listOf(14, 30, 90)
        private val DAILY_FORECAST_HORIZONS = setOf(5, 30, 60)
        private const val FACTOR_TELEMETRY_MAX_DISTANCE_MS = 10L * 60L * 1000L
        private const val FACTOR_TELEMETRY_FUTURE_TOLERANCE_MS = 2L * 60L * 1000L
        private val FACTOR_SPECS = listOf(
            FactorSpec.COB,
            FactorSpec.IOB,
            FactorSpec.UAM,
            FactorSpec.CI_WIDTH,
            FactorSpec.DIA_HOURS,
            FactorSpec.ACTIVITY_RATIO,
            FactorSpec.SENSOR_QUALITY,
            FactorSpec.SENSOR_AGE_HOURS,
            FactorSpec.ISF_CONFIDENCE,
            FactorSpec.ISF_QUALITY,
            FactorSpec.SET_AGE_HOURS,
            FactorSpec.CONTEXT_AMBIGUITY,
            FactorSpec.DAWN_FACTOR,
            FactorSpec.STRESS_FACTOR,
            FactorSpec.STEROID_FACTOR,
            FactorSpec.HORMONE_FACTOR
        )
        private val CORE_REPLAY_FACTORS = setOf("COB", "IOB", "UAM", "CI")
        private const val REGIME_LOW = "LOW"
        private const val REGIME_MID = "MID"
        private const val REGIME_HIGH = "HIGH"
        private val CORE_FACTOR_PAIRS = listOf(
            FactorSpec.COB to FactorSpec.IOB,
            FactorSpec.COB to FactorSpec.UAM,
            FactorSpec.COB to FactorSpec.CI_WIDTH,
            FactorSpec.IOB to FactorSpec.CI_WIDTH,
            FactorSpec.UAM to FactorSpec.CI_WIDTH
        )

        internal fun buildDailyForecastReportPayloadStatic(
            forecasts: List<ForecastEntity>,
            glucose: List<GlucoseSampleEntity>,
            telemetrySamples: List<TelemetrySampleEntity> = emptyList(),
            sinceTs: Long,
            untilTs: Long
        ): DailyForecastReportPayload {
            if (forecasts.isEmpty() || glucose.isEmpty()) {
                return DailyForecastReportPayload(
                    sinceTs = sinceTs,
                    untilTs = untilTs,
                    forecastRows = forecasts.size,
                    matchedSamples = 0,
                    horizonStats = emptyList(),
                    hourlyStats = emptyList(),
                    bandStats = emptyList(),
                    worstSamples = emptyList(),
                    replayHotspots = emptyList(),
                    factorContributions = emptyList(),
                    recommendations = emptyList()
                )
            }

            val sortedGlucose = glucose.sortedBy { it.timestamp }
            val horizonAcc = mutableMapOf<Int, ErrorAccumulator>()
            val hourlyAcc = mutableMapOf<Pair<Int, Int>, ErrorAccumulator>()
            val bandAcc = mutableMapOf<Triple<String, Int, Int>, ErrorAccumulator>()
            val matched = mutableListOf<MatchedErrorSample>()

            forecasts.forEach { forecast ->
                val toleranceMs = if (forecast.horizonMinutes <= 10) 5 * 60 * 1000L else 15 * 60 * 1000L
                val actual = closestGlucose(sortedGlucose, forecast.timestamp, toleranceMs) ?: return@forEach
                val absError = abs(actual.mmol - forecast.valueMmol)
                val sqError = absError * absError
                val ard = if (actual.mmol > 0.0) absError / actual.mmol else 0.0
                val signed = forecast.valueMmol - actual.mmol
                val ciLow = minOf(forecast.ciLow, forecast.ciHigh)
                val ciHigh = maxOf(forecast.ciLow, forecast.ciHigh)
                val ciWidth = (ciHigh - ciLow).coerceAtLeast(0.0)
                val insideCi = actual.mmol in ciLow..ciHigh

                horizonAcc.getOrPut(forecast.horizonMinutes) { ErrorAccumulator() }
                    .add(
                        absError = absError,
                        sqError = sqError,
                        ard = ard,
                        signedError = signed,
                        ciWidth = ciWidth,
                        insideCi = insideCi
                    )

                val hour = java.time.Instant.ofEpochMilli(forecast.timestamp)
                    .atZone(java.time.ZoneId.systemDefault())
                    .hour
                hourlyAcc.getOrPut(hour to forecast.horizonMinutes) { ErrorAccumulator() }
                    .add(
                        absError = absError,
                        sqError = sqError,
                        ard = ard,
                        signedError = signed,
                        ciWidth = ciWidth,
                        insideCi = insideCi
                    )

                val bandLabel = when {
                    actual.mmol < 3.9 -> "HYPO(<3.9)"
                    actual.mmol <= 10.0 -> "TARGET(3.9-10.0)"
                    else -> "HIGH(>10.0)"
                }
                bandAcc.getOrPut(Triple(bandLabel, forecast.horizonMinutes, hour)) { ErrorAccumulator() }
                    .add(
                        absError = absError,
                        sqError = sqError,
                        ard = ard,
                        signedError = signed,
                        ciWidth = ciWidth,
                        insideCi = insideCi
                    )

                matched += MatchedErrorSample(
                    ts = forecast.timestamp,
                    generationTs = forecast.timestamp - forecast.horizonMinutes * 60_000L,
                    horizonMinutes = forecast.horizonMinutes,
                    pred = forecast.valueMmol,
                    actual = actual.mmol,
                    absError = absError,
                    ardPct = ard * 100.0,
                    ciWidth = ciWidth,
                    modelFamily = forecast.modelVersion.substringBefore('|').ifBlank { forecast.modelVersion }
                )
            }

            val horizonStats = horizonAcc
                .map { (h, acc) -> acc.toHorizonStats(h) }
                .sortedBy { it.horizonMinutes }

            val hourlyStats = hourlyAcc
                .mapNotNull { (key, acc) ->
                    if (acc.n < 4) return@mapNotNull null
                    HourlyForecastStats(
                        hour = key.first,
                        horizonMinutes = key.second,
                        sampleCount = acc.n,
                        mae = acc.mae(),
                        mardPct = acc.mardPct(),
                        bias = acc.bias()
                    )
                }
                .sortedWith(compareByDescending<HourlyForecastStats> { it.mardPct }.thenByDescending { it.sampleCount })

            val bandStats = bandAcc
                .entries
                .groupBy { it.key.first to it.key.second }
                .map { (bandHorizon, entries) ->
                    val merged = ErrorAccumulator()
                    entries.forEach { merged.merge(it.value) }
                    BandForecastStats(
                        bandLabel = bandHorizon.first,
                        horizonMinutes = bandHorizon.second,
                        sampleCount = merged.n,
                        mae = merged.mae(),
                        mardPct = merged.mardPct()
                    )
                }
                .sortedWith(compareBy<BandForecastStats> { it.horizonMinutes }.thenByDescending { it.sampleCount })

            val worstSamples = matched
                .sortedByDescending { it.absError }
                .take(15)
                .map {
                    DailyForecastWorstSample(
                        ts = it.ts,
                        horizonMinutes = it.horizonMinutes,
                        pred = it.pred,
                        actual = it.actual,
                        absError = it.absError,
                        ardPct = it.ardPct,
                        modelFamily = it.modelFamily
                    )
                }

            val replayHotspots = buildReplayHotspots(hourlyStats)
            val factorAttribution = buildFactorContributions(
                matched = matched,
                telemetrySamples = telemetrySamples
            )
            val factorContributions = factorAttribution.contributions
            val factorCoverage = factorAttribution.coverage
            val factorRegimes = buildReplayFactorRegimes(
                matched = matched,
                telemetrySamples = telemetrySamples
            )
            val factorPairs = buildReplayFactorPairRegimes(
                matched = matched,
                telemetrySamples = telemetrySamples
            )
            val replayTopMisses = buildReplayTopMissContexts(
                matched = matched,
                telemetrySamples = telemetrySamples
            )
            val replayErrorClusters = buildReplayErrorClusters(
                matched = matched,
                telemetrySamples = telemetrySamples
            )
            val replayDayTypeGaps = buildReplayDayTypeGaps(
                replayErrorClusters = replayErrorClusters
            )

            val recommendations = buildRecommendations(
                horizonStats = horizonStats,
                hourlyStats = hourlyStats,
                worstSamples = worstSamples,
                replayHotspots = replayHotspots,
                factorContributions = factorContributions,
                factorCoverage = factorCoverage,
                factorRegimes = factorRegimes,
                factorPairs = factorPairs,
                replayErrorClusters = replayErrorClusters,
                replayDayTypeGaps = replayDayTypeGaps
            ).distinct()

            return DailyForecastReportPayload(
                sinceTs = sinceTs,
                untilTs = untilTs,
                forecastRows = forecasts.size,
                matchedSamples = matched.size,
                horizonStats = horizonStats,
                hourlyStats = hourlyStats,
                bandStats = bandStats,
                worstSamples = worstSamples,
                replayHotspots = replayHotspots,
                factorContributions = factorContributions,
                replayFactorCoverage = factorCoverage,
                replayFactorRegimes = factorRegimes,
                replayFactorPairs = factorPairs,
                replayTopMisses = replayTopMisses,
                replayErrorClusters = replayErrorClusters,
                replayDayTypeGaps = replayDayTypeGaps,
                recommendations = recommendations
            )
        }

        internal fun buildRollingForecastPayloadsStatic(
            forecasts: List<ForecastEntity>,
            glucose: List<GlucoseSampleEntity>,
            untilTs: Long,
            windowsDays: List<Int> = ROLLING_FORECAST_WINDOWS_DAYS
        ): Map<Int, DailyForecastReportPayload> {
            return windowsDays.associateWith { days ->
                val rollingWindowSince = untilTs - days.toLong() * MILLIS_PER_DAY
                buildDailyForecastReportPayloadStatic(
                    forecasts = forecasts.filter { it.timestamp in rollingWindowSince..untilTs },
                    glucose = glucose,
                    telemetrySamples = emptyList(),
                    sinceTs = rollingWindowSince,
                    untilTs = untilTs
                )
            }
        }

        private fun buildRecommendations(
            horizonStats: List<HorizonForecastStats>,
            hourlyStats: List<HourlyForecastStats>,
            worstSamples: List<DailyForecastWorstSample>,
            replayHotspots: List<ReplayHotspotStats>,
            factorContributions: List<ReplayFactorContributionStats>,
            factorCoverage: List<ReplayFactorCoverageStats>,
            factorRegimes: List<ReplayFactorRegimeStats>,
            factorPairs: List<ReplayFactorPairRegimeStats>,
            replayErrorClusters: List<ReplayErrorClusterStats>,
            replayDayTypeGaps: List<ReplayDayTypeGapStats>
        ): List<String> {
            val recommendations = mutableListOf<String>()
            val byHorizon = horizonStats.associateBy { it.horizonMinutes }
            val h5 = byHorizon[5]
            val h30 = byHorizon[30]
            val h60 = byHorizon[60]

            if (h60 != null && h60.mardPct >= 15.0) {
                recommendations += "60m MARD is high (${fmtStatic(h60.mardPct)}%). Re-check UAM sensitivity and carb absorption profile for long horizon."
            }
            if (h5 != null && h5.mardPct >= 12.0) {
                recommendations += "5m MARD is elevated (${fmtStatic(h5.mardPct)}%). Verify CGM quality/stale windows and short-term noise filtering."
            }
            if (h30 != null && h60 != null && h60.mardPct - h30.mardPct >= 3.0) {
                recommendations += "Error grows strongly from 30m to 60m. Tune residual trend decay and insulin profile selection."
            }
            if (h30 != null && h30.ciCoveragePct < 55.0) {
                recommendations += "30m CI coverage is low (${fmtStatic(h30.ciCoveragePct)}%). Increase uncertainty calibration for medium horizon."
            }
            if (h60 != null && h60.ciCoveragePct < 55.0) {
                recommendations += "60m CI coverage is low (${fmtStatic(h60.ciCoveragePct)}%). Increase uncertainty calibration for long horizon and residual/UAM regimes."
            }
            if (h60 != null && h60.bias > 0.6) {
                recommendations += "60m bias is positive (${fmtStatic(h60.bias)} mmol/L): model tends to overpredict. Consider reducing carb/UAM aggressiveness."
            }
            if (h60 != null && h60.bias < -0.6) {
                recommendations += "60m bias is negative (${fmtStatic(h60.bias)} mmol/L): model tends to underpredict highs. Consider stronger post-meal/UAM compensation."
            }

            hourlyStats.firstOrNull()?.let { hotspot ->
                recommendations += "Worst hourly hotspot: ${hotspot.hour.toString().padStart(2, '0')}:00 at ${hotspot.horizonMinutes}m (MARD ${fmtStatic(hotspot.mardPct)}%, n=${hotspot.sampleCount}). Review this time-slot profile."
            }

            val underPredHigh = worstSamples.count { it.actual >= 10.0 && (it.pred - it.actual) <= -1.0 }
            if (underPredHigh >= 4) {
                recommendations += "Frequent underprediction during high glucose episodes detected. Review carb class mapping and UAM boost settings."
            }

            val topHotspotByHorizon = replayHotspots
                .groupBy { it.horizonMinutes }
                .mapValues { (_, rows) -> rows.maxByOrNull { it.mae } }
            listOf(5, 30, 60).forEach { horizon ->
                topHotspotByHorizon[horizon]?.let { hotspot ->
                    recommendations += "${horizon}m hotspot: ${hotspot.hour.toString().padStart(2, '0')}:00 has MAE ${fmtStatic(hotspot.mae)} (n=${hotspot.sampleCount})."
                }
            }

            val topFactorByHorizon = factorContributions
                .groupBy { it.horizonMinutes }
                .mapValues { (_, rows) -> rows.maxByOrNull { it.contributionScore } }
            listOf(5, 30, 60).forEach { horizon ->
                topFactorByHorizon[horizon]?.let { factor ->
                    recommendations += "${horizon}m top factor=${factor.factor} (corr=${fmtStatic(factor.corrAbsError)}, uplift=${fmtStatic(factor.upliftPct)}%)."
                    factorRecommendationHint(
                        factor = factor.factor,
                        horizonMinutes = horizon,
                        corrAbsError = factor.corrAbsError,
                        upliftPct = factor.upliftPct
                    )?.let { hint ->
                        recommendations += hint
                    }
                }
            }

            val lowCoverageByHorizon = factorCoverage
                .filter { it.factor in CORE_REPLAY_FACTORS && it.coveragePct < 60.0 }
                .groupBy { it.horizonMinutes }
            listOf(5, 30, 60).forEach { horizon ->
                lowCoverageByHorizon[horizon]
                    ?.sortedBy { it.coveragePct }
                    ?.firstOrNull()
                    ?.let { weak ->
                        recommendations += "${horizon}m factor coverage is weak (${weak.factor}=${fmtStatic(weak.coveragePct)}%, n=${weak.sampleCount}). Improve telemetry continuity before tuning this factor."
                    }
            }
            val lowCoverageExtendedByHorizon = factorCoverage
                .filter {
                    it.factor !in CORE_REPLAY_FACTORS &&
                        it.coveragePct < 45.0 &&
                        it.sampleCount >= 8
                }
                .groupBy { it.horizonMinutes }
            listOf(5, 30, 60).forEach { horizon ->
                lowCoverageExtendedByHorizon[horizon]
                    ?.sortedBy { it.coveragePct }
                    ?.firstOrNull()
                    ?.let { weak ->
                        recommendations += "${horizon}m extended factor coverage is low (${weak.factor}=${fmtStatic(weak.coveragePct)}%, n=${weak.sampleCount}). Treat this factor as low-confidence until telemetry improves."
                    }
            }
            val regimeByFactorHorizon = factorRegimes
                .filter { it.factor in CORE_REPLAY_FACTORS }
                .groupBy { it.horizonMinutes to it.factor }
            regimeByFactorHorizon.forEach { (key, rows) ->
                val low = rows.firstOrNull { it.bucket == REGIME_LOW }
                val high = rows.firstOrNull { it.bucket == REGIME_HIGH }
                if (low == null || high == null || low.sampleCount < 4 || high.sampleCount < 4 || low.mae <= 1e-9) {
                    return@forEach
                }
                val ratio = high.mae / low.mae
                if (ratio >= 1.35) {
                    recommendations += "${key.first}m ${key.second} high-regime MAE is ${fmtStatic(ratio)}x vs low-regime (high=${fmtStatic(high.mae)}, low=${fmtStatic(low.mae)}). Prioritize tuning this factor under high-load windows."
                } else if (ratio <= 0.75) {
                    recommendations += "${key.first}m ${key.second} appears protective in high regime (MAE ratio=${fmtStatic(ratio)}). Keep current handling and avoid over-tightening."
                }
            }
            data class PairRatioCandidate(
                val horizonMinutes: Int,
                val factorA: String,
                val factorB: String,
                val ratio: Double,
                val highHighMae: Double,
                val lowLowMae: Double
            )
            val pairCandidates = factorPairs
                .groupBy { Triple(it.horizonMinutes, it.factorA, it.factorB) }
                .mapNotNull { (key, rows) ->
                    val lowLow = rows.firstOrNull { it.bucketA == REGIME_LOW && it.bucketB == REGIME_LOW }
                    val highHigh = rows.firstOrNull { it.bucketA == REGIME_HIGH && it.bucketB == REGIME_HIGH }
                    if (
                        lowLow == null ||
                        highHigh == null ||
                        lowLow.sampleCount < 4 ||
                        highHigh.sampleCount < 4 ||
                        lowLow.mae <= 1e-9
                    ) {
                        return@mapNotNull null
                    }
                    PairRatioCandidate(
                        horizonMinutes = key.first,
                        factorA = key.second,
                        factorB = key.third,
                        ratio = highHigh.mae / lowLow.mae,
                        highHighMae = highHigh.mae,
                        lowLowMae = lowLow.mae
                    )
                }
            listOf(5, 30, 60).forEach { horizon ->
                val horizonPairs = pairCandidates.filter { it.horizonMinutes == horizon }
                val worst = horizonPairs.maxByOrNull { it.ratio }
                val protective = horizonPairs.minByOrNull { it.ratio }
                if (worst != null && worst.ratio >= 1.35) {
                    recommendations += "${horizon}m top pair=${worst.factorA}×${worst.factorB} (HH/LL MAE ratio=${fmtStatic(worst.ratio)}, HH=${fmtStatic(worst.highHighMae)}, LL=${fmtStatic(worst.lowLowMae)}). Prioritize combined-factor tuning."
                }
                if (
                    protective != null &&
                    protective.ratio <= 0.75 &&
                    (worst == null || worst.factorA != protective.factorA || worst.factorB != protective.factorB)
                ) {
                    recommendations += "${horizon}m protective pair=${protective.factorA}×${protective.factorB} (HH/LL MAE ratio=${fmtStatic(protective.ratio)})."
                }
            }
            replayErrorClusters
                .groupBy { it.horizonMinutes }
                .forEach { (horizon, rows) ->
                    val top = rows.maxByOrNull { it.mae } ?: return@forEach
                    val dominant = top.dominantFactor()
                    recommendations += buildString {
                        append("${horizon}m top ${top.dayType.lowercase(Locale.US)} error cluster at ${top.hour.toString().padStart(2, '0')}:00 ")
                        append("(MAE=${fmtStatic(top.mae)}, MARD=${fmtStatic(top.mardPct)}%, n=${top.sampleCount}). ")
                        append("Mean factors: COB=${fmtStatic(top.meanCob)}, IOB=${fmtStatic(top.meanIob)}, UAM=${fmtStatic(top.meanUam)}, CI=${fmtStatic(top.meanCiWidth)}.")
                        dominant?.let {
                            append(" Dominant load driver: ${it.first} (norm=${fmtStatic(it.second)}).")
                        }
                    }
                }
            replayDayTypeGaps
                .groupBy { it.horizonMinutes }
                .forEach { (horizon, rows) ->
                    val top = rows.maxByOrNull { abs(it.maeGapMmol) } ?: return@forEach
                    if (abs(top.maeGapMmol) < 0.25) return@forEach
                    recommendations += buildString {
                        append("${horizon}m weekday/weekend gap at ${top.hour.toString().padStart(2, '0')}:00 ")
                        append("(worse=${top.worseDayType.lowercase(Locale.US)}, ")
                        append("ΔMAE=${fmtStatic(top.maeGapMmol)}, ")
                        append("weekday=${fmtStatic(top.weekdayMae)}, weekend=${fmtStatic(top.weekendMae)}).")
                    }
                }
            return recommendations
        }

        private fun buildReplayTopMissContexts(
            matched: List<MatchedErrorSample>,
            telemetrySamples: List<TelemetrySampleEntity>
        ): List<ReplayTopMissContextStats> {
            if (matched.isEmpty()) return emptyList()
            val telemetryByKey = telemetrySamples
                .asSequence()
                .filter { it.valueDouble != null }
                .groupBy { it.key.lowercase(Locale.US) }
            val factorCache = mutableMapOf<Pair<FactorSpec, Long>, Double>()

            fun factor(
                spec: FactorSpec,
                sample: MatchedErrorSample
            ): Double {
                val key = spec to sample.generationTs
                val cached = factorCache[key]
                if (cached != null) return if (cached.isNaN()) 0.0 else cached
                val resolved = resolveFactorValue(
                    spec = spec,
                    sample = sample,
                    telemetryByKey = telemetryByKey
                )
                val normalized = resolved?.takeUnless { it.isNaN() } ?: Double.NaN
                factorCache[key] = normalized
                return if (normalized.isNaN()) 0.0 else normalized
            }

            val rows = mutableListOf<ReplayTopMissContextStats>()
            DAILY_FORECAST_HORIZONS.sorted().forEach { horizon ->
                val topMiss = matched
                    .asSequence()
                    .filter { it.horizonMinutes == horizon }
                    .maxByOrNull { it.absError }
                    ?: return@forEach
                rows += ReplayTopMissContextStats(
                    horizonMinutes = horizon,
                    ts = topMiss.ts,
                    absError = topMiss.absError,
                    pred = topMiss.pred,
                    actual = topMiss.actual,
                    cob = factor(FactorSpec.COB, topMiss),
                    iob = factor(FactorSpec.IOB, topMiss),
                    uam = factor(FactorSpec.UAM, topMiss),
                    ciWidth = topMiss.ciWidth,
                    diaHours = factor(FactorSpec.DIA_HOURS, topMiss),
                    activity = factor(FactorSpec.ACTIVITY_RATIO, topMiss),
                    sensorQuality = factor(FactorSpec.SENSOR_QUALITY, topMiss)
                )
            }
            return rows.sortedBy { it.horizonMinutes }
        }

        private fun buildReplayErrorClusters(
            matched: List<MatchedErrorSample>,
            telemetrySamples: List<TelemetrySampleEntity>
        ): List<ReplayErrorClusterStats> {
            if (matched.isEmpty()) return emptyList()
            val telemetryByKey = telemetrySamples
                .asSequence()
                .filter { it.valueDouble != null }
                .groupBy { it.key.lowercase(Locale.US) }
            val factorCache = mutableMapOf<Pair<FactorSpec, Long>, Double>()

            fun factorValue(spec: FactorSpec, sample: MatchedErrorSample): Double? {
                val cacheKey = spec to sample.generationTs
                val cached = factorCache[cacheKey]
                if (cached != null) return cached.takeUnless { it.isNaN() }
                val resolved = resolveFactorValue(
                    spec = spec,
                    sample = sample,
                    telemetryByKey = telemetryByKey
                )
                val normalized = resolved?.takeUnless { it.isNaN() } ?: Double.NaN
                factorCache[cacheKey] = normalized
                return normalized.takeUnless { it.isNaN() }
            }

            data class ClusterAccumulator(
                var sampleCount: Int = 0,
                var absErrorSum: Double = 0.0,
                var ardPctSum: Double = 0.0,
                var biasSum: Double = 0.0,
                var cobSum: Double = 0.0,
                var cobN: Int = 0,
                var iobSum: Double = 0.0,
                var iobN: Int = 0,
                var uamSum: Double = 0.0,
                var uamN: Int = 0,
                var ciSum: Double = 0.0,
                var ciN: Int = 0
            ) {
                fun addFactor(value: Double?, onAdd: (Double) -> Unit, onCount: () -> Unit) {
                    if (value == null || !value.isFinite()) return
                    onAdd(value)
                    onCount()
                }
            }

            val byCluster = mutableMapOf<Triple<Int, Int, String>, ClusterAccumulator>()
            matched.forEach { sample ->
                val dateTime = java.time.Instant.ofEpochMilli(sample.ts)
                    .atZone(java.time.ZoneId.systemDefault())
                val hour = dateTime.hour
                val dayType = when (dateTime.dayOfWeek) {
                    java.time.DayOfWeek.SATURDAY,
                    java.time.DayOfWeek.SUNDAY -> "WEEKEND"
                    else -> "WEEKDAY"
                }
                val key = Triple(sample.horizonMinutes, hour, dayType)
                val acc = byCluster.getOrPut(key) { ClusterAccumulator() }
                acc.sampleCount += 1
                acc.absErrorSum += sample.absError
                acc.ardPctSum += sample.ardPct
                acc.biasSum += sample.pred - sample.actual
                acc.addFactor(
                    value = factorValue(FactorSpec.COB, sample),
                    onAdd = { acc.cobSum += it },
                    onCount = { acc.cobN += 1 }
                )
                acc.addFactor(
                    value = factorValue(FactorSpec.IOB, sample),
                    onAdd = { acc.iobSum += it },
                    onCount = { acc.iobN += 1 }
                )
                acc.addFactor(
                    value = factorValue(FactorSpec.UAM, sample),
                    onAdd = { acc.uamSum += it },
                    onCount = { acc.uamN += 1 }
                )
                acc.addFactor(
                    value = sample.ciWidth,
                    onAdd = { acc.ciSum += it },
                    onCount = { acc.ciN += 1 }
                )
            }

            return byCluster
                .asSequence()
                .mapNotNull { (key, acc) ->
                    if (acc.sampleCount < 4) return@mapNotNull null
                    ReplayErrorClusterStats(
                        horizonMinutes = key.first,
                        hour = key.second,
                        dayType = key.third,
                        sampleCount = acc.sampleCount,
                        mae = acc.absErrorSum / acc.sampleCount,
                        mardPct = acc.ardPctSum / acc.sampleCount,
                        bias = acc.biasSum / acc.sampleCount,
                        meanCob = if (acc.cobN <= 0) 0.0 else acc.cobSum / acc.cobN,
                        meanIob = if (acc.iobN <= 0) 0.0 else acc.iobSum / acc.iobN,
                        meanUam = if (acc.uamN <= 0) 0.0 else acc.uamSum / acc.uamN,
                        meanCiWidth = if (acc.ciN <= 0) 0.0 else acc.ciSum / acc.ciN
                    )
                }
                .sortedWith(
                    compareBy<ReplayErrorClusterStats> { it.horizonMinutes }
                        .thenByDescending { it.mae }
                        .thenByDescending { it.sampleCount }
                        .thenBy { it.dayType }
                        .thenBy { it.hour }
                )
                .toList()
        }

        private fun buildReplayDayTypeGaps(
            replayErrorClusters: List<ReplayErrorClusterStats>
        ): List<ReplayDayTypeGapStats> {
            if (replayErrorClusters.isEmpty()) return emptyList()
            return replayErrorClusters
                .groupBy { Triple(it.horizonMinutes, it.hour, it.dayType) }
                .mapNotNull { (key, rows) ->
                    rows.maxByOrNull { it.sampleCount }?.let { key to it }
                }
                .groupBy { it.first.first to it.first.second }
                .values
                .mapNotNull { bucket ->
                    val weekday = bucket.firstOrNull { it.first.third == "WEEKDAY" }?.second
                    val weekend = bucket.firstOrNull { it.first.third == "WEEKEND" }?.second
                    if (weekday == null || weekend == null) return@mapNotNull null
                    if (weekday.sampleCount < 4 || weekend.sampleCount < 4) return@mapNotNull null
                    val worse = if (weekend.mae >= weekday.mae) weekend else weekday
                    ReplayDayTypeGapStats(
                        horizonMinutes = weekday.horizonMinutes,
                        hour = weekday.hour,
                        weekdaySampleCount = weekday.sampleCount,
                        weekendSampleCount = weekend.sampleCount,
                        weekdayMae = weekday.mae,
                        weekendMae = weekend.mae,
                        weekdayMardPct = weekday.mardPct,
                        weekendMardPct = weekend.mardPct,
                        maeGapMmol = weekend.mae - weekday.mae,
                        mardGapPct = weekend.mardPct - weekday.mardPct,
                        worseDayType = worse.dayType,
                        worseMeanCob = worse.meanCob,
                        worseMeanIob = worse.meanIob,
                        worseMeanUam = worse.meanUam,
                        worseMeanCiWidth = worse.meanCiWidth,
                        dominantFactor = worse.dominantFactor()?.first,
                        dominantScore = worse.dominantFactor()?.second
                    )
                }
                .groupBy { it.horizonMinutes }
                .values
                .flatMap { rows ->
                    rows.sortedByDescending { abs(it.maeGapMmol) }.take(3)
                }
                .sortedWith(
                    compareBy<ReplayDayTypeGapStats> { it.horizonMinutes }
                        .thenByDescending { abs(it.maeGapMmol) }
                        .thenBy { it.hour }
                )
        }

        private fun buildReplayFactorRegimes(
            matched: List<MatchedErrorSample>,
            telemetrySamples: List<TelemetrySampleEntity>
        ): List<ReplayFactorRegimeStats> {
            if (matched.isEmpty()) return emptyList()
            val telemetryByKey = telemetrySamples
                .asSequence()
                .filter { it.valueDouble != null }
                .groupBy { it.key.lowercase(Locale.US) }
            val factorCache = mutableMapOf<Pair<FactorSpec, Long>, Double>()

            fun factorValue(spec: FactorSpec, sample: MatchedErrorSample): Double? {
                val cacheKey = spec to sample.generationTs
                val cached = factorCache[cacheKey]
                if (cached != null) return cached.takeUnless { it.isNaN() }
                val resolved = resolveFactorValue(
                    spec = spec,
                    sample = sample,
                    telemetryByKey = telemetryByKey
                )
                val normalized = resolved?.takeUnless { it.isNaN() } ?: Double.NaN
                factorCache[cacheKey] = normalized
                return normalized.takeUnless { it.isNaN() }
            }

            val rows = mutableListOf<ReplayFactorRegimeStats>()
            DAILY_FORECAST_HORIZONS.sorted().forEach { horizon ->
                val horizonSamples = matched.filter { it.horizonMinutes == horizon }
                if (horizonSamples.size < 12) return@forEach
                FACTOR_SPECS
                    .filter { it.label in CORE_REPLAY_FACTORS }
                    .forEach { spec ->
                        val pairs = horizonSamples.mapNotNull { sample ->
                            factorValue(spec, sample)?.let { factorValue ->
                                factorValue to sample
                            }
                        }
                        if (pairs.size < 12) return@forEach
                        val values = pairs.map { it.first }
                        val qLow = percentile(values, 0.33)
                        val qHigh = percentile(values, 0.67)
                        val buckets = listOf(
                            REGIME_LOW to pairs.filter { it.first <= qLow },
                            REGIME_MID to pairs.filter { it.first > qLow && it.first <= qHigh },
                            REGIME_HIGH to pairs.filter { it.first > qHigh }
                        )
                        buckets.forEach { (bucket, bucketRows) ->
                            if (bucketRows.size < 4) return@forEach
                            rows += ReplayFactorRegimeStats(
                                horizonMinutes = horizon,
                                factor = spec.label,
                                bucket = bucket,
                                sampleCount = bucketRows.size,
                                meanFactorValue = bucketRows.map { it.first }.average(),
                                mae = bucketRows.map { it.second.absError }.average(),
                                mardPct = bucketRows.map { it.second.ardPct }.average(),
                                bias = bucketRows.map { it.second.pred - it.second.actual }.average()
                            )
                        }
                    }
            }
            return rows.sortedWith(
                compareBy<ReplayFactorRegimeStats> { it.horizonMinutes }
                    .thenBy { it.factor }
                    .thenBy { replayBucketOrder(it.bucket) }
            )
        }

        private fun buildReplayFactorPairRegimes(
            matched: List<MatchedErrorSample>,
            telemetrySamples: List<TelemetrySampleEntity>
        ): List<ReplayFactorPairRegimeStats> {
            if (matched.isEmpty()) return emptyList()
            val telemetryByKey = telemetrySamples
                .asSequence()
                .filter { it.valueDouble != null }
                .groupBy { it.key.lowercase(Locale.US) }
            val factorCache = mutableMapOf<Pair<FactorSpec, Long>, Double>()

            fun factorValue(spec: FactorSpec, sample: MatchedErrorSample): Double? {
                val cacheKey = spec to sample.generationTs
                val cached = factorCache[cacheKey]
                if (cached != null) return cached.takeUnless { it.isNaN() }
                val resolved = resolveFactorValue(
                    spec = spec,
                    sample = sample,
                    telemetryByKey = telemetryByKey
                )
                val normalized = resolved?.takeUnless { it.isNaN() } ?: Double.NaN
                factorCache[cacheKey] = normalized
                return normalized.takeUnless { it.isNaN() }
            }

            val rows = mutableListOf<ReplayFactorPairRegimeStats>()
            DAILY_FORECAST_HORIZONS.sorted().forEach { horizon ->
                val horizonSamples = matched.filter { it.horizonMinutes == horizon }
                if (horizonSamples.size < 18) return@forEach
                CORE_FACTOR_PAIRS.forEach { (factorA, factorB) ->
                    val pairs = horizonSamples.mapNotNull { sample ->
                        val a = factorValue(factorA, sample) ?: return@mapNotNull null
                        val b = factorValue(factorB, sample) ?: return@mapNotNull null
                        Triple(a, b, sample)
                    }
                    if (pairs.size < 16) return@forEach
                    val medianA = percentile(pairs.map { it.first }, 0.50)
                    val medianB = percentile(pairs.map { it.second }, 0.50)
                    val grouped = listOf(
                        REGIME_LOW to REGIME_LOW to pairs.filter { it.first <= medianA && it.second <= medianB },
                        REGIME_LOW to REGIME_HIGH to pairs.filter { it.first <= medianA && it.second > medianB },
                        REGIME_HIGH to REGIME_LOW to pairs.filter { it.first > medianA && it.second <= medianB },
                        REGIME_HIGH to REGIME_HIGH to pairs.filter { it.first > medianA && it.second > medianB }
                    )
                    grouped.forEach { group ->
                        val bucketA = group.first.first
                        val bucketB = group.first.second
                        val bucketRows = group.second
                        if (bucketRows.size < 4) return@forEach
                        rows += ReplayFactorPairRegimeStats(
                            horizonMinutes = horizon,
                            factorA = factorA.label,
                            factorB = factorB.label,
                            bucketA = bucketA,
                            bucketB = bucketB,
                            sampleCount = bucketRows.size,
                            meanFactorA = bucketRows.map { it.first }.average(),
                            meanFactorB = bucketRows.map { it.second }.average(),
                            mae = bucketRows.map { it.third.absError }.average(),
                            mardPct = bucketRows.map { it.third.ardPct }.average(),
                            bias = bucketRows.map { it.third.pred - it.third.actual }.average()
                        )
                    }
                }
            }
            return rows.sortedWith(
                compareBy<ReplayFactorPairRegimeStats> { it.horizonMinutes }
                    .thenBy { it.factorA }
                    .thenBy { it.factorB }
                    .thenBy { replayBucketOrder(it.bucketA) }
                    .thenBy { replayBucketOrder(it.bucketB) }
            )
        }

        private fun factorRecommendationHint(
            factor: String,
            horizonMinutes: Int,
            corrAbsError: Double,
            upliftPct: Double
        ): String? {
            val highFactorWorse = upliftPct >= 0.0
            return when (factor.uppercase(Locale.US)) {
                "COB" -> if (highFactorWorse) {
                    "${horizonMinutes}m COB-driven error: review carb class mapping/absorption caps and late-meal handling windows."
                } else {
                    "${horizonMinutes}m COB effect looks protective (higher COB -> lower error). Keep current carb mapping and monitor post-meal tails."
                }

                "IOB" -> if (highFactorWorse) {
                    "${horizonMinutes}m IOB-linked error is elevated: verify DIA profile and insulin timing alignment."
                } else {
                    "${horizonMinutes}m higher IOB aligns with lower error; keep insulin profile but monitor lows to avoid over-correction."
                }

                "UAM" -> if (highFactorWorse) {
                    "${horizonMinutes}m UAM dominates error: tune UAM sensitivity/boost and improve announced-carb capture."
                } else {
                    "${horizonMinutes}m UAM signal appears stabilizing; keep current UAM settings and watch for false-positive episodes."
                }

                "CI" -> if (highFactorWorse) {
                    "${horizonMinutes}m wide CI coincides with larger errors: increase uncertainty-aware safeguards and inspect noisy windows."
                } else {
                    "${horizonMinutes}m CI expansion tracks risk correctly; keep calibration and verify CI coverage targets."
                }

                "DIA_H" -> if (highFactorWorse) {
                    "${horizonMinutes}m DIA-linked error is elevated: verify insulin duration profile and insulin type alignment."
                } else {
                    "${horizonMinutes}m DIA behavior looks stable; keep current insulin-duration profile and monitor long-tail lows."
                }

                "ACTIVITY" -> if (highFactorWorse) {
                    "${horizonMinutes}m activity context is a major driver: strengthen activity-aware ISF/CR adaptation around exercise windows."
                } else {
                    "${horizonMinutes}m activity-aware adaptation helps; preserve activity weighting and keep steps/activity telemetry stable."
                }

                "SENSOR_Q" -> if (highFactorWorse) {
                    "${horizonMinutes}m sensor-quality relation is unstable (corr=${fmtStatic(corrAbsError)}). Re-check sensor gate thresholds and false-low filters."
                } else {
                    "${horizonMinutes}m lower sensor quality worsens errors: tighten sensor-quality gate and fallback behavior."
                }

                "ISF_CONF" -> if (highFactorWorse) {
                    "${horizonMinutes}m high ISF confidence still shows larger error: inspect confidence calibration against replay residuals."
                } else {
                    "${horizonMinutes}m low ISF confidence aligns with higher error: keep confidence-gated fallback chain strict."
                }

                "ISF_Q" -> if (highFactorWorse) {
                    "${horizonMinutes}m ISF quality score does not suppress error enough; review quality penalties and evidence filters."
                } else {
                    "${horizonMinutes}m low ISF quality drives error: prioritize evidence quality and sensor integrity windows."
                }

                "SET_AGE_H" -> if (highFactorWorse) {
                    "${horizonMinutes}m infusion-set age strongly impacts error: consider earlier set changes in high-age windows."
                } else {
                    "${horizonMinutes}m set-age impact is controlled; keep current set-age penalties and monitor drift."
                }

                "SENSOR_AGE_H" -> if (highFactorWorse) {
                    "${horizonMinutes}m sensor-age impact is high: watch late-life sensor drift and tighten stale/quality fallback."
                } else {
                    "${horizonMinutes}m sensor-age contribution is controlled; current sensor-aging penalties look adequate."
                }

                "CTX_AMBIG" -> if (highFactorWorse) {
                    "${horizonMinutes}m context ambiguity (stress/hormone/illness) is dominant: use manual context tags and conservative fallback in ambiguous periods."
                } else {
                    "${horizonMinutes}m context ambiguity handling appears protective; keep latent/manual context model enabled."
                }

                "DAWN" -> if (highFactorWorse) {
                    "${horizonMinutes}m dawn factor contributes to error: refine dawn window multipliers and early-morning profile segments."
                } else {
                    "${horizonMinutes}m dawn handling improves fit; keep dawn factor active and monitor morning bias."
                }

                "STRESS" -> if (highFactorWorse) {
                    "${horizonMinutes}m stress factor contributes to error: strengthen stress-tag workflow and stress-aware ISF dampening."
                } else {
                    "${horizonMinutes}m stress adaptation helps stabilize forecasts; maintain stress context integration."
                }

                "STEROID" -> if (highFactorWorse) {
                    "${horizonMinutes}m steroid context contributes to error: verify steroid tags/duration and resistance multipliers."
                } else {
                    "${horizonMinutes}m steroid-aware adaptation appears beneficial; keep steroid context integration active."
                }

                "HORMONE" -> if (highFactorWorse) {
                    "${horizonMinutes}m hormone context drives residual error: improve hormone-phase tagging and multiplier calibration."
                } else {
                    "${horizonMinutes}m hormone-aware adjustment appears beneficial; keep hormone context enabled."
                }

                else -> null
            }
        }

        private fun buildReplayHotspots(hourlyStats: List<HourlyForecastStats>): List<ReplayHotspotStats> {
            if (hourlyStats.isEmpty()) return emptyList()
            return DAILY_FORECAST_HORIZONS
                .flatMap { horizon ->
                    hourlyStats
                        .asSequence()
                        .filter { it.horizonMinutes == horizon }
                        .sortedByDescending { it.mae }
                        .take(3)
                        .map { stat ->
                            ReplayHotspotStats(
                                horizonMinutes = stat.horizonMinutes,
                                hour = stat.hour,
                                sampleCount = stat.sampleCount,
                                mae = stat.mae,
                                mardPct = stat.mardPct,
                                bias = stat.bias
                            )
                        }
                        .toList()
                }
        }

        private fun buildFactorContributions(
            matched: List<MatchedErrorSample>,
            telemetrySamples: List<TelemetrySampleEntity>
        ): FactorAttributionResult {
            if (matched.isEmpty()) return FactorAttributionResult(
                contributions = emptyList(),
                coverage = emptyList()
            )
            val telemetryByKey = telemetrySamples
                .asSequence()
                .filter { it.valueDouble != null }
                .groupBy { it.key.lowercase(Locale.US) }
            val factorCache = mutableMapOf<Pair<FactorSpec, Long>, Double>()

            fun factorValue(
                spec: FactorSpec,
                sample: MatchedErrorSample
            ): Double? {
                val key = spec to sample.generationTs
                val cached = factorCache[key]
                if (cached != null) {
                    return cached.takeUnless { it.isNaN() }
                }
                val resolved = resolveFactorValue(
                    spec = spec,
                    sample = sample,
                    telemetryByKey = telemetryByKey
                )
                val normalized = resolved?.takeUnless { it.isNaN() } ?: Double.NaN
                factorCache[key] = normalized
                return normalized.takeUnless { it.isNaN() }
            }

            val rows = mutableListOf<ReplayFactorContributionStats>()
            val coverageRows = mutableListOf<ReplayFactorCoverageStats>()
            DAILY_FORECAST_HORIZONS.forEach { horizon ->
                val horizonSamples = matched.filter { it.horizonMinutes == horizon }
                if (horizonSamples.size < 12) return@forEach

                FACTOR_SPECS.forEach { spec ->
                    val pairs = horizonSamples.mapNotNull { sample ->
                        factorValue(spec = spec, sample = sample)?.let { value ->
                            value to sample.absError
                        }
                    }
                    coverageRows += ReplayFactorCoverageStats(
                        horizonMinutes = horizon,
                        factor = spec.label,
                        sampleCount = pairs.size,
                        coveragePct = (pairs.size * 100.0 / horizonSamples.size).coerceIn(0.0, 100.0)
                    )
                    if (pairs.size < 12) return@forEach

                    val factorValues = pairs.map { it.first }
                    val absErrors = pairs.map { it.second }
                    val corrPearson = pearsonCorrelation(factorValues, absErrors)
                    val corrSpearman = spearmanCorrelation(factorValues, absErrors)
                    val corr = when {
                        corrPearson.isNaN() && corrSpearman.isNaN() -> 0.0
                        corrPearson.isNaN() -> corrSpearman
                        corrSpearman.isNaN() -> corrPearson
                        else -> ((corrPearson + corrSpearman) / 2.0).coerceIn(-1.0, 1.0)
                    }
                    val threshold = percentile(factorValues, 0.75)
                    val high = pairs.filter { it.first >= threshold }.map { it.second }
                    val low = pairs.filter { it.first < threshold }.map { it.second }
                    if (high.size < 4 || low.size < 4) return@forEach

                    val maeHigh = high.average()
                    val maeLow = low.average()
                    val upliftPct = if (maeLow <= 1e-9) 0.0 else ((maeHigh / maeLow) - 1.0) * 100.0
                    val upliftMagnitudePct = if (maeLow <= 1e-9) {
                        abs(maeHigh - maeLow) * 100.0
                    } else {
                        (abs(maeHigh - maeLow) / maeLow) * 100.0
                    }
                    val contributionScore = (
                        abs(corr).coerceIn(0.0, 1.0) * 0.60 +
                            (upliftMagnitudePct / 100.0).coerceIn(0.0, 1.0) * 0.40
                        ).coerceIn(0.0, 1.0)

                    rows += ReplayFactorContributionStats(
                        horizonMinutes = horizon,
                        factor = spec.label,
                        sampleCount = pairs.size,
                        corrAbsError = corr,
                        maeHigh = maeHigh,
                        maeLow = maeLow,
                        upliftPct = upliftPct,
                        contributionScore = contributionScore
                    )
                }
            }
            return FactorAttributionResult(
                contributions = rows.sortedWith(
                    compareBy<ReplayFactorContributionStats> { it.horizonMinutes }
                        .thenByDescending { it.contributionScore }
                ),
                coverage = coverageRows.sortedWith(
                    compareBy<ReplayFactorCoverageStats> { it.horizonMinutes }
                        .thenBy { it.factor }
                )
            )
        }

        private fun nearestTelemetryValue(
            telemetryByKey: Map<String, List<TelemetrySampleEntity>>,
            key: String,
            ts: Long,
            maxDistanceMs: Long
        ): Double? {
            val rows = telemetryByKey[key] ?: return null
            val bounded = rows.asSequence()
                .filter { sample ->
                    sample.timestamp >= ts - maxDistanceMs &&
                        sample.timestamp <= ts + FACTOR_TELEMETRY_FUTURE_TOLERANCE_MS
                }
                .toList()
            val causal = bounded
                .asSequence()
                .filter { it.timestamp <= ts + FACTOR_TELEMETRY_FUTURE_TOLERANCE_MS }
                .maxByOrNull { it.timestamp }
            if (causal != null) {
                return causal.valueDouble
            }
            return rows.minByOrNull { abs(it.timestamp - ts) }
                ?.takeIf { abs(it.timestamp - ts) <= maxDistanceMs }
                ?.valueDouble
        }

        private fun nearestTelemetryValueMulti(
            telemetryByKey: Map<String, List<TelemetrySampleEntity>>,
            keys: List<String>,
            ts: Long,
            maxDistanceMs: Long
        ): Double? {
            return keys.asSequence()
                .mapNotNull { key ->
                    nearestTelemetryValue(
                        telemetryByKey = telemetryByKey,
                        key = key,
                        ts = ts,
                        maxDistanceMs = maxDistanceMs
                    )
                }
                .firstOrNull()
        }

        private fun uamFactorValue(
            telemetryByKey: Map<String, List<TelemetrySampleEntity>>,
            ts: Long
        ): Double? {
            val flag = listOf(
                nearestTelemetryValue(telemetryByKey, "uam_value", ts, FACTOR_TELEMETRY_MAX_DISTANCE_MS),
                nearestTelemetryValue(telemetryByKey, "uam_calculated_flag", ts, FACTOR_TELEMETRY_MAX_DISTANCE_MS),
                nearestTelemetryValue(telemetryByKey, "uam_inferred_flag", ts, FACTOR_TELEMETRY_MAX_DISTANCE_MS)
            ).mapNotNull { it }.maxOrNull() ?: 0.0
            val delta = listOf(
                nearestTelemetryValue(telemetryByKey, "uam_uci0_mmol5", ts, FACTOR_TELEMETRY_MAX_DISTANCE_MS),
                nearestTelemetryValue(telemetryByKey, "uam_calculated_delta5_mmol", ts, FACTOR_TELEMETRY_MAX_DISTANCE_MS)
            ).mapNotNull { it }.maxOrNull() ?: 0.0
            val score = maxOf(flag, delta)
            return if (score <= 0.0) null else score
        }

        private fun resolveFactorValue(
            spec: FactorSpec,
            sample: MatchedErrorSample,
            telemetryByKey: Map<String, List<TelemetrySampleEntity>>
        ): Double? {
            return when (spec) {
                FactorSpec.CI_WIDTH -> sample.ciWidth
                FactorSpec.COB -> nearestTelemetryValue(
                    telemetryByKey = telemetryByKey,
                    key = "cob_grams",
                    ts = sample.generationTs,
                    maxDistanceMs = FACTOR_TELEMETRY_MAX_DISTANCE_MS
                )
                FactorSpec.IOB -> nearestTelemetryValue(
                    telemetryByKey = telemetryByKey,
                    key = "iob_units",
                    ts = sample.generationTs,
                    maxDistanceMs = FACTOR_TELEMETRY_MAX_DISTANCE_MS
                )
                FactorSpec.UAM -> uamFactorValue(
                    telemetryByKey = telemetryByKey,
                    ts = sample.generationTs
                )
                FactorSpec.DIA_HOURS -> nearestTelemetryValueMulti(
                    telemetryByKey = telemetryByKey,
                    keys = listOf("dia_hours", "dia"),
                    ts = sample.generationTs,
                    maxDistanceMs = FACTOR_TELEMETRY_MAX_DISTANCE_MS
                )
                FactorSpec.ACTIVITY_RATIO -> nearestTelemetryValueMulti(
                    telemetryByKey = telemetryByKey,
                    keys = listOf("activity_ratio", "activity", "sensitivity_ratio"),
                    ts = sample.generationTs,
                    maxDistanceMs = FACTOR_TELEMETRY_MAX_DISTANCE_MS
                )
                FactorSpec.SENSOR_QUALITY -> nearestTelemetryValueMulti(
                    telemetryByKey = telemetryByKey,
                    keys = listOf("sensor_quality_score", "sensor_quality"),
                    ts = sample.generationTs,
                    maxDistanceMs = FACTOR_TELEMETRY_MAX_DISTANCE_MS
                )
                FactorSpec.ISF_CONFIDENCE -> nearestTelemetryValue(
                    telemetryByKey = telemetryByKey,
                    key = "isf_realtime_confidence",
                    ts = sample.generationTs,
                    maxDistanceMs = FACTOR_TELEMETRY_MAX_DISTANCE_MS
                )
                FactorSpec.ISF_QUALITY -> nearestTelemetryValue(
                    telemetryByKey = telemetryByKey,
                    key = "isf_realtime_quality_score",
                    ts = sample.generationTs,
                    maxDistanceMs = FACTOR_TELEMETRY_MAX_DISTANCE_MS
                )
                FactorSpec.SET_AGE_HOURS -> nearestTelemetryValue(
                    telemetryByKey = telemetryByKey,
                    key = "isf_factor_set_age_hours",
                    ts = sample.generationTs,
                    maxDistanceMs = FACTOR_TELEMETRY_MAX_DISTANCE_MS
                )
                FactorSpec.SENSOR_AGE_HOURS -> nearestTelemetryValue(
                    telemetryByKey = telemetryByKey,
                    key = "isf_factor_sensor_age_hours",
                    ts = sample.generationTs,
                    maxDistanceMs = FACTOR_TELEMETRY_MAX_DISTANCE_MS
                )
                FactorSpec.CONTEXT_AMBIGUITY -> nearestTelemetryValue(
                    telemetryByKey = telemetryByKey,
                    key = "isf_factor_context_ambiguity",
                    ts = sample.generationTs,
                    maxDistanceMs = FACTOR_TELEMETRY_MAX_DISTANCE_MS
                )
                FactorSpec.DAWN_FACTOR -> nearestTelemetryValue(
                    telemetryByKey = telemetryByKey,
                    key = "isf_factor_dawn_factor",
                    ts = sample.generationTs,
                    maxDistanceMs = FACTOR_TELEMETRY_MAX_DISTANCE_MS
                )
                FactorSpec.STRESS_FACTOR -> nearestTelemetryValue(
                    telemetryByKey = telemetryByKey,
                    key = "isf_factor_stress_factor",
                    ts = sample.generationTs,
                    maxDistanceMs = FACTOR_TELEMETRY_MAX_DISTANCE_MS
                )
                FactorSpec.STEROID_FACTOR -> nearestTelemetryValue(
                    telemetryByKey = telemetryByKey,
                    key = "isf_factor_steroid_factor",
                    ts = sample.generationTs,
                    maxDistanceMs = FACTOR_TELEMETRY_MAX_DISTANCE_MS
                )
                FactorSpec.HORMONE_FACTOR -> nearestTelemetryValue(
                    telemetryByKey = telemetryByKey,
                    key = "isf_factor_hormone_factor",
                    ts = sample.generationTs,
                    maxDistanceMs = FACTOR_TELEMETRY_MAX_DISTANCE_MS
                )
            }
        }

        private fun percentile(values: List<Double>, quantile: Double): Double {
            if (values.isEmpty()) return 0.0
            val sorted = values.sorted()
            val q = quantile.coerceIn(0.0, 1.0)
            val index = ((sorted.size - 1) * q).toInt().coerceIn(0, sorted.lastIndex)
            return sorted[index]
        }

        private fun pearsonCorrelation(xs: List<Double>, ys: List<Double>): Double {
            if (xs.size != ys.size || xs.size < 3) return 0.0
            val meanX = xs.average()
            val meanY = ys.average()
            var cov = 0.0
            var varX = 0.0
            var varY = 0.0
            xs.indices.forEach { i ->
                val dx = xs[i] - meanX
                val dy = ys[i] - meanY
                cov += dx * dy
                varX += dx * dx
                varY += dy * dy
            }
            if (varX <= 1e-9 || varY <= 1e-9) return 0.0
            return (cov / sqrt(varX * varY)).coerceIn(-1.0, 1.0)
        }

        private fun spearmanCorrelation(xs: List<Double>, ys: List<Double>): Double {
            if (xs.size != ys.size || xs.size < 3) return 0.0
            val rankedX = rankWithTies(xs)
            val rankedY = rankWithTies(ys)
            return pearsonCorrelation(rankedX, rankedY)
        }

        private fun rankWithTies(values: List<Double>): List<Double> {
            if (values.isEmpty()) return emptyList()
            val indexed = values.mapIndexed { index, value -> index to value }
                .sortedBy { it.second }
            val ranks = DoubleArray(values.size)
            var i = 0
            while (i < indexed.size) {
                var j = i
                while (j + 1 < indexed.size && indexed[j + 1].second == indexed[i].second) {
                    j += 1
                }
                val avgRank = ((i + 1) + (j + 1)) / 2.0
                var k = i
                while (k <= j) {
                    ranks[indexed[k].first] = avgRank
                    k += 1
                }
                i = j + 1
            }
            return ranks.toList()
        }

        private fun serializeReplayHotspots(rows: List<ReplayHotspotStats>): String? {
            if (rows.isEmpty()) return null
            val array = JSONArray()
            rows.forEach { row ->
                array.put(
                    JSONObject()
                        .put("horizonMinutes", row.horizonMinutes)
                        .put("hour", row.hour)
                        .put("sampleCount", row.sampleCount)
                        .put("mae", row.mae)
                        .put("mardPct", row.mardPct)
                        .put("bias", row.bias)
                )
            }
            return array.toString()
        }

        private fun serializeReplayFactorContributions(rows: List<ReplayFactorContributionStats>): String? {
            if (rows.isEmpty()) return null
            val array = JSONArray()
            rows.forEach { row ->
                array.put(
                    JSONObject()
                        .put("horizonMinutes", row.horizonMinutes)
                        .put("factor", row.factor)
                        .put("sampleCount", row.sampleCount)
                        .put("corrAbsError", row.corrAbsError)
                        .put("maeHigh", row.maeHigh)
                        .put("maeLow", row.maeLow)
                        .put("upliftPct", row.upliftPct)
                        .put("contributionScore", row.contributionScore)
                )
            }
            return array.toString()
        }

        private fun serializeReplayFactorCoverage(rows: List<ReplayFactorCoverageStats>): String? {
            if (rows.isEmpty()) return null
            val array = JSONArray()
            rows.forEach { row ->
                array.put(
                    JSONObject()
                        .put("horizonMinutes", row.horizonMinutes)
                        .put("factor", row.factor)
                        .put("sampleCount", row.sampleCount)
                        .put("coveragePct", row.coveragePct)
                )
            }
            return array.toString()
        }

        private fun serializeReplayFactorRegimes(rows: List<ReplayFactorRegimeStats>): String? {
            if (rows.isEmpty()) return null
            val array = JSONArray()
            rows.forEach { row ->
                array.put(
                    JSONObject()
                        .put("horizonMinutes", row.horizonMinutes)
                        .put("factor", row.factor)
                        .put("bucket", row.bucket)
                        .put("sampleCount", row.sampleCount)
                        .put("meanFactorValue", row.meanFactorValue)
                        .put("mae", row.mae)
                        .put("mardPct", row.mardPct)
                        .put("bias", row.bias)
                )
            }
            return array.toString()
        }

        private fun serializeReplayFactorPairs(rows: List<ReplayFactorPairRegimeStats>): String? {
            if (rows.isEmpty()) return null
            val array = JSONArray()
            rows.forEach { row ->
                array.put(
                    JSONObject()
                        .put("horizonMinutes", row.horizonMinutes)
                        .put("factorA", row.factorA)
                        .put("factorB", row.factorB)
                        .put("bucketA", row.bucketA)
                        .put("bucketB", row.bucketB)
                        .put("sampleCount", row.sampleCount)
                        .put("meanFactorA", row.meanFactorA)
                        .put("meanFactorB", row.meanFactorB)
                        .put("mae", row.mae)
                        .put("mardPct", row.mardPct)
                        .put("bias", row.bias)
                )
            }
            return array.toString()
        }

        private fun serializeReplayTopMisses(rows: List<ReplayTopMissContextStats>): String? {
            if (rows.isEmpty()) return null
            val array = JSONArray()
            rows.forEach { row ->
                array.put(
                    JSONObject()
                        .put("horizonMinutes", row.horizonMinutes)
                        .put("ts", row.ts)
                        .put("absError", row.absError)
                        .put("pred", row.pred)
                        .put("actual", row.actual)
                        .put("cob", row.cob)
                        .put("iob", row.iob)
                        .put("uam", row.uam)
                        .put("ciWidth", row.ciWidth)
                        .put("diaHours", row.diaHours)
                        .put("activity", row.activity)
                        .put("sensorQuality", row.sensorQuality)
                )
            }
            return array.toString()
        }

        private fun serializeReplayErrorClusters(rows: List<ReplayErrorClusterStats>): String? {
            if (rows.isEmpty()) return null
            val array = JSONArray()
            rows.forEach { row ->
                val dominant = row.dominantFactor()
                array.put(
                    JSONObject()
                        .put("horizonMinutes", row.horizonMinutes)
                        .put("hour", row.hour)
                        .put("dayType", row.dayType)
                        .put("sampleCount", row.sampleCount)
                        .put("mae", row.mae)
                        .put("mardPct", row.mardPct)
                        .put("bias", row.bias)
                        .put("meanCob", row.meanCob)
                        .put("meanIob", row.meanIob)
                        .put("meanUam", row.meanUam)
                        .put("meanCiWidth", row.meanCiWidth)
                        .put("dominantFactor", dominant?.first)
                        .put("dominantScore", dominant?.second)
                )
            }
            return array.toString()
        }

        private fun serializeReplayDayTypeGaps(rows: List<ReplayDayTypeGapStats>): String? {
            if (rows.isEmpty()) return null
            val array = JSONArray()
            rows.forEach { row ->
                array.put(
                    JSONObject()
                        .put("horizonMinutes", row.horizonMinutes)
                        .put("hour", row.hour)
                        .put("worseDayType", row.worseDayType)
                        .put("weekdaySampleCount", row.weekdaySampleCount)
                        .put("weekendSampleCount", row.weekendSampleCount)
                        .put("weekdayMae", row.weekdayMae)
                        .put("weekendMae", row.weekendMae)
                        .put("weekdayMardPct", row.weekdayMardPct)
                        .put("weekendMardPct", row.weekendMardPct)
                        .put("maeGapMmol", row.maeGapMmol)
                        .put("mardGapPct", row.mardGapPct)
                        .put("worseMeanCob", row.worseMeanCob)
                        .put("worseMeanIob", row.worseMeanIob)
                        .put("worseMeanUam", row.worseMeanUam)
                        .put("worseMeanCiWidth", row.worseMeanCiWidth)
                        .put("dominantFactor", row.dominantFactor)
                        .put("dominantScore", row.dominantScore)
                )
            }
            return array.toString()
        }

        internal fun buildIsfCrDataQualityRecommendations(
            droppedTotal: Int,
            eventCount: Int,
            reasonCounts: Map<String, Int>
        ): List<String> {
            if (droppedTotal <= 0 || eventCount <= 0) return emptyList()
            val recommendations = mutableListOf<String>()
            val gapRate = reasonRate(reasonCounts, "cr_gross_gap", droppedTotal)
            val sensorRate = reasonRate(reasonCounts, "cr_sensor_blocked", droppedTotal)
            val uamRate = reasonRate(reasonCounts, "cr_uam_ambiguity", droppedTotal)

            if (sensorRate >= 20.0) {
                recommendations += "ISF/CR extraction is often blocked by sensor quality (${fmtStatic(sensorRate)}% of dropped windows). Improve CGM signal quality and verify sensor age/change timing."
            }
            if (gapRate >= 20.0) {
                recommendations += "CR extraction has many gross CGM gaps (${fmtStatic(gapRate)}% of dropped windows). Keep background data flow stable and reduce CGM interruptions."
            }
            if (uamRate >= 20.0) {
                recommendations += "CR windows are frequently dropped due to UAM ambiguity (${fmtStatic(uamRate)}%). Tune UAM sensitivity/boost and log announced carbs promptly."
            }
            if (droppedTotal >= maxOf(60, eventCount * 3)) {
                recommendations += "High dropped-evidence load detected (dropped=$droppedTotal over events=$eventCount). Relax CR integrity thresholds only after sensor/data stability improves."
            }
            return recommendations
        }

        internal fun buildIsfCrDroppedQualityLines(
            sourceMessage: String?,
            eventCount: Int?,
            droppedTotal: Int?,
            reasonCounts: Map<String, Int>
        ): List<String> {
            val source = sourceMessage?.ifBlank { "n/a" } ?: "n/a"
            val safeEventCount = eventCount?.coerceAtLeast(0) ?: 0
            val safeDroppedTotal = droppedTotal?.coerceAtLeast(0) ?: 0
            if (safeEventCount <= 0 || safeDroppedTotal <= 0) {
                return listOf(
                    "source=$source, events=$safeEventCount, dropped=$safeDroppedTotal"
                )
            }
            val gapRate = reasonRate(reasonCounts, "cr_gross_gap", safeDroppedTotal)
            val sensorRate = reasonRate(reasonCounts, "cr_sensor_blocked", safeDroppedTotal)
            val uamRate = reasonRate(reasonCounts, "cr_uam_ambiguity", safeDroppedTotal)
            val topReasons = reasonCounts.entries
                .sortedByDescending { it.value }
                .take(4)
                .joinToString("; ") { "${it.key}=${it.value}" }
                .ifBlank { "n/a" }
            val risk = buildIsfCrDataQualityRiskLabel(
                eventCount = safeEventCount,
                droppedTotal = safeDroppedTotal,
                reasonCounts = reasonCounts
            )
            return listOf(
                "source=$source, events=$safeEventCount, dropped=$safeDroppedTotal",
                "Quality risk: $risk",
                "CR integrity drop-rate: gap=${fmtStatic(gapRate)}%, sensorBlocked=${fmtStatic(sensorRate)}%, uamAmbiguity=${fmtStatic(uamRate)}%",
                "Top dropped reasons: $topReasons"
            )
        }

        internal fun buildIsfCrDataQualityRiskLabel(
            eventCount: Int?,
            droppedTotal: Int?,
            reasonCounts: Map<String, Int>
        ): String {
            val events = eventCount?.coerceAtLeast(0) ?: 0
            val dropped = droppedTotal?.coerceAtLeast(0) ?: 0
            if (events <= 0 || dropped <= 0) return "UNKNOWN"

            val rates = linkedMapOf(
                "gap" to reasonRate(reasonCounts, "cr_gross_gap", dropped),
                "sensorBlocked" to reasonRate(reasonCounts, "cr_sensor_blocked", dropped),
                "uamAmbiguity" to reasonRate(reasonCounts, "cr_uam_ambiguity", dropped)
            )
            val dominant = rates.entries
                .maxByOrNull { it.value }
                ?.let { it.key to it.value }
                ?: ("gap" to 0.0)
            val riskLevel = buildIsfCrDataQualityRiskLevel(
                eventCount = events,
                droppedTotal = dropped,
                reasonCounts = reasonCounts
            )
            val maxRate = dominant.second

            return when (riskLevel) {
                3 -> "HIGH (${dominant.first} ${fmtStatic(maxRate)}%)"
                2 -> "MEDIUM (${dominant.first} ${fmtStatic(maxRate)}%)"
                1 -> "LOW (${dominant.first} ${fmtStatic(maxRate)}%)"
                else ->
                    "UNKNOWN"
            }
        }

        internal fun buildIsfCrDataQualityRiskLevel(
            eventCount: Int?,
            droppedTotal: Int?,
            reasonCounts: Map<String, Int>
        ): Int {
            val events = eventCount?.coerceAtLeast(0) ?: 0
            val dropped = droppedTotal?.coerceAtLeast(0) ?: 0
            if (events <= 0 || dropped <= 0) return 0

            val gapRate = reasonRate(reasonCounts, "cr_gross_gap", dropped)
            val sensorRate = reasonRate(reasonCounts, "cr_sensor_blocked", dropped)
            val uamRate = reasonRate(reasonCounts, "cr_uam_ambiguity", dropped)
            val maxRate = maxOf(gapRate, sensorRate, uamRate)
            val overloadHard = dropped >= maxOf(100, events * 4)
            val overloadMedium = dropped >= maxOf(60, events * 3)

            return when {
                overloadHard || maxRate >= 35.0 -> 3
                overloadMedium || maxRate >= 20.0 -> 2
                else -> 1
            }
        }

        private fun reasonRate(reasonCounts: Map<String, Int>, key: String, droppedTotal: Int): Double {
            if (droppedTotal <= 0) return 0.0
            return ((reasonCounts[key] ?: 0).coerceAtLeast(0) * 100.0) / droppedTotal
        }

        private fun fmtStatic(value: Double): String = String.format(Locale.US, "%.3f", value)

        private fun closestGlucose(
            samples: List<GlucoseSampleEntity>,
            targetTs: Long,
            toleranceMs: Long
        ): GlucoseSampleEntity? {
            val candidate = samples.minByOrNull { abs(it.timestamp - targetTs) } ?: return null
            return candidate.takeIf { abs(candidate.timestamp - targetTs) <= toleranceMs }
        }

        private class ErrorAccumulator {
            var n: Int = 0
                private set
            private var absSum: Double = 0.0
            private var sqSum: Double = 0.0
            private var ardSum: Double = 0.0
            private var signedSum: Double = 0.0
            private var ciWidthSum: Double = 0.0
            private var ciInsideCount: Int = 0

            fun add(
                absError: Double,
                sqError: Double,
                ard: Double,
                signedError: Double,
                ciWidth: Double,
                insideCi: Boolean
            ) {
                n += 1
                absSum += absError
                sqSum += sqError
                ardSum += ard
                signedSum += signedError
                ciWidthSum += ciWidth
                if (insideCi) {
                    ciInsideCount += 1
                }
            }

            fun merge(other: ErrorAccumulator) {
                n += other.n
                absSum += other.absSum
                sqSum += other.sqSum
                ardSum += other.ardSum
                signedSum += other.signedSum
                ciWidthSum += other.ciWidthSum
                ciInsideCount += other.ciInsideCount
            }

            fun mae(): Double = if (n <= 0) 0.0 else absSum / n
            fun rmse(): Double = if (n <= 0) 0.0 else sqrt(sqSum / n)
            fun mardPct(): Double = if (n <= 0) 0.0 else ardSum / n * 100.0
            fun bias(): Double = if (n <= 0) 0.0 else signedSum / n
            fun ciCoveragePct(): Double = if (n <= 0) 0.0 else ciInsideCount.toDouble() * 100.0 / n
            fun ciMeanWidth(): Double = if (n <= 0) 0.0 else ciWidthSum / n

            fun toHorizonStats(horizon: Int): HorizonForecastStats {
                return HorizonForecastStats(
                    horizonMinutes = horizon,
                    sampleCount = n,
                    mae = mae(),
                    rmse = rmse(),
                    mardPct = mardPct(),
                    bias = bias(),
                    ciCoveragePct = ciCoveragePct(),
                    ciMeanWidth = ciMeanWidth()
                )
            }
        }

        private data class FactorAttributionResult(
            val contributions: List<ReplayFactorContributionStats>,
            val coverage: List<ReplayFactorCoverageStats>
        )

        private fun replayBucketOrder(bucket: String): Int = when (bucket.uppercase(Locale.US)) {
            REGIME_LOW -> 0
            REGIME_MID -> 1
            REGIME_HIGH -> 2
            else -> 3
        }

        private enum class FactorSpec(val label: String) {
            COB("COB"),
            IOB("IOB"),
            UAM("UAM"),
            CI_WIDTH("CI"),
            DIA_HOURS("DIA_H"),
            ACTIVITY_RATIO("ACTIVITY"),
            SENSOR_QUALITY("SENSOR_Q"),
            SENSOR_AGE_HOURS("SENSOR_AGE_H"),
            ISF_CONFIDENCE("ISF_CONF"),
            ISF_QUALITY("ISF_Q"),
            SET_AGE_HOURS("SET_AGE_H"),
            CONTEXT_AMBIGUITY("CTX_AMBIG"),
            DAWN_FACTOR("DAWN"),
            STRESS_FACTOR("STRESS"),
            STEROID_FACTOR("STEROID"),
            HORMONE_FACTOR("HORMONE")
        }
    }

    private fun outputDir(folderName: String): File {
        val base = context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)
            ?: context.filesDir
        val dir = File(base, folderName)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private fun timestampSuffix(): String =
        DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.US).format(LocalDateTime.now()) + "-${System.currentTimeMillis() % 100000}"
}

data class CloudReplayUiModel(
    val days: Int,
    val points: Int,
    val stepMinutes: Int,
    val forecastStats: List<ReplayForecastStats>,
    val ruleStats: List<ReplayRuleStats>,
    val dayTypeStats: List<ReplayDayTypeStats>,
    val hourlyStats: List<ReplayHourStats>,
    val driftStats: List<ReplayDriftStats>
)

data class CloudJobsUiModel(
    val timezone: String,
    val jobs: List<CloudJobStatus>
)

data class CloudAnalysisHistoryUiModel(
    val items: List<AnalysisHistoryItem>
)

data class CloudAnalysisTrendUiModel(
    val items: List<AnalysisTrendItem>
)

data class DailyForecastReportResult(
    val statusLine: String,
    val markdownPath: String?,
    val csvPath: String?,
    val matchedSamples: Int,
    val topMardHorizon: Int?,
    val topMardPct: Double?
)

data class DailyForecastReportPayload(
    val sinceTs: Long,
    val untilTs: Long,
    val forecastRows: Int,
    val matchedSamples: Int,
    val horizonStats: List<HorizonForecastStats>,
    val hourlyStats: List<HourlyForecastStats>,
    val bandStats: List<BandForecastStats>,
    val worstSamples: List<DailyForecastWorstSample>,
    val replayHotspots: List<ReplayHotspotStats> = emptyList(),
    val factorContributions: List<ReplayFactorContributionStats> = emptyList(),
    val replayFactorCoverage: List<ReplayFactorCoverageStats> = emptyList(),
    val replayFactorRegimes: List<ReplayFactorRegimeStats> = emptyList(),
    val replayFactorPairs: List<ReplayFactorPairRegimeStats> = emptyList(),
    val replayTopMisses: List<ReplayTopMissContextStats> = emptyList(),
    val replayErrorClusters: List<ReplayErrorClusterStats> = emptyList(),
    val replayDayTypeGaps: List<ReplayDayTypeGapStats> = emptyList(),
    val recommendations: List<String>
)

data class HorizonForecastStats(
    val horizonMinutes: Int,
    val sampleCount: Int,
    val mae: Double,
    val rmse: Double,
    val mardPct: Double,
    val bias: Double,
    val ciCoveragePct: Double,
    val ciMeanWidth: Double
)

data class HourlyForecastStats(
    val hour: Int,
    val horizonMinutes: Int,
    val sampleCount: Int,
    val mae: Double,
    val mardPct: Double,
    val bias: Double
)

data class BandForecastStats(
    val bandLabel: String,
    val horizonMinutes: Int,
    val sampleCount: Int,
    val mae: Double,
    val mardPct: Double
)

data class DailyForecastWorstSample(
    val ts: Long,
    val horizonMinutes: Int,
    val pred: Double,
    val actual: Double,
    val absError: Double,
    val ardPct: Double,
    val modelFamily: String
)

data class ReplayHotspotStats(
    val horizonMinutes: Int,
    val hour: Int,
    val sampleCount: Int,
    val mae: Double,
    val mardPct: Double,
    val bias: Double
)

data class ReplayFactorContributionStats(
    val horizonMinutes: Int,
    val factor: String,
    val sampleCount: Int,
    val corrAbsError: Double,
    val maeHigh: Double,
    val maeLow: Double,
    val upliftPct: Double,
    val contributionScore: Double
)

data class ReplayFactorCoverageStats(
    val horizonMinutes: Int,
    val factor: String,
    val sampleCount: Int,
    val coveragePct: Double
)

data class ReplayFactorRegimeStats(
    val horizonMinutes: Int,
    val factor: String,
    val bucket: String,
    val sampleCount: Int,
    val meanFactorValue: Double,
    val mae: Double,
    val mardPct: Double,
    val bias: Double
)

data class ReplayFactorPairRegimeStats(
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

data class ReplayTopMissContextStats(
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

data class ReplayErrorClusterStats(
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
    val meanCiWidth: Double
) {
    fun dominantFactor(): Pair<String, Double>? {
        val scores = listOf(
            "COB" to (meanCob / 20.0).coerceAtLeast(0.0),
            "IOB" to (meanIob / 2.0).coerceAtLeast(0.0),
            "UAM" to (meanUam / 0.2).coerceAtLeast(0.0),
            "CI" to (meanCiWidth / 1.5).coerceAtLeast(0.0)
        )
        return scores.maxByOrNull { it.second }?.takeIf { it.second > 0.0 }
    }
}

data class ReplayDayTypeGapStats(
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

private data class MatchedErrorSample(
    val ts: Long,
    val generationTs: Long,
    val horizonMinutes: Int,
    val pred: Double,
    val actual: Double,
    val absError: Double,
    val ardPct: Double,
    val ciWidth: Double,
    val modelFamily: String
)
