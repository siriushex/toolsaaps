package io.aaps.copilot.data.repository

import com.google.common.truth.Truth.assertThat
import io.aaps.copilot.data.remote.cloud.AnalysisHistoryItem
import io.aaps.copilot.data.remote.cloud.AnalysisTrendItem
import org.junit.Test

class InsightsExportFormatterTest {

    private val history = CloudAnalysisHistoryUiModel(
        items = listOf(
            AnalysisHistoryItem(
                runTs = 1_700_000_000_000,
                date = "2026-02-27",
                locale = "ru-RU",
                source = "manual",
                status = "SUCCESS",
                summary = "stable day",
                anomalies = listOf("mild post-breakfast spike"),
                recommendations = listOf("review CR at breakfast"),
                errorMessage = null
            )
        )
    )

    private val trend = CloudAnalysisTrendUiModel(
        items = listOf(
            AnalysisTrendItem(
                weekStart = "2026-02-23",
                totalRuns = 4,
                successRuns = 4,
                failedRuns = 0,
                manualRuns = 2,
                schedulerRuns = 2,
                anomaliesCount = 3,
                recommendationsCount = 4
            )
        )
    )

    @Test
    fun buildCsv_containsHistoryAndTrend() {
        val csv = InsightsExportFormatter.buildCsv(history, trend, "Filters: source=manual, status=SUCCESS")

        assertThat(csv).contains("meta,")
        assertThat(csv).contains("Filters: source=manual, status=SUCCESS")
        assertThat(csv).contains("history,2026-02-27,manual,SUCCESS")
        assertThat(csv).contains("trend,2026-02-23,4,4,0,2,2,3,4")
    }

    @Test
    fun buildPdfLines_containsSections() {
        val lines = InsightsExportFormatter.buildPdfLines(history, trend, "Filters: source=manual, status=SUCCESS")

        assertThat(lines.any { it == "History" }).isTrue()
        assertThat(lines.any { it == "Weekly trend" }).isTrue()
        assertThat(lines.any { it.contains("2026-02-27 manual SUCCESS") }).isTrue()
        assertThat(lines.any { it.contains("2026-02-23: runs=4") }).isTrue()
    }
}
