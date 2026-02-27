package io.aaps.copilot.data.repository

import android.content.Context
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.os.Environment
import io.aaps.copilot.config.AppSettingsStore
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

class InsightsRepository(
    private val context: Context,
    private val settingsStore: AppSettingsStore,
    private val apiFactory: ApiFactory,
    private val auditLogger: AuditLogger
) {

    suspend fun runDailyAnalysis(): String {
        val settings = settingsStore.settings.first()
        if (settings.cloudBaseUrl.isBlank()) {
            val message = "Cloud API not configured"
            auditLogger.warn("daily_analysis_skipped", mapOf("reason" to "missing_cloud_url"))
            return message
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
            }
            auditLogger.info("daily_analysis_completed")
            summary
        }.getOrElse { error ->
            val message = error.message ?: "daily analysis failed"
            auditLogger.error("daily_analysis_failed", mapOf("error" to message))
            message
        }
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
