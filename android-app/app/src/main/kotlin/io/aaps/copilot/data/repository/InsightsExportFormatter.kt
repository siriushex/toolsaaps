package io.aaps.copilot.data.repository

object InsightsExportFormatter {

    fun buildCsv(
        history: CloudAnalysisHistoryUiModel,
        trend: CloudAnalysisTrendUiModel,
        filterLabel: String
    ): String {
        val rows = mutableListOf<String>()
        rows += "section,key1,key2,key3,key4,key5,key6,key7,key8,key9"
        rows += "meta,${escape(filterLabel)},historyCount,${history.items.size},trendCount,${trend.items.size},,,,"

        history.items.forEach { item ->
            rows += "history,${item.date},${escape(item.source)},${escape(item.status)},${item.runTs},${item.anomalies.size},${item.recommendations.size},${escape(item.locale)},${escape(item.errorMessage.orEmpty())},${escape(item.summary.take(160))}"
        }

        trend.items.forEach { item ->
            rows += "trend,${item.weekStart},${item.totalRuns},${item.successRuns},${item.failedRuns},${item.manualRuns},${item.schedulerRuns},${item.anomaliesCount},${item.recommendationsCount},"
        }

        return rows.joinToString(separator = "\n")
    }

    fun buildPdfLines(
        history: CloudAnalysisHistoryUiModel,
        trend: CloudAnalysisTrendUiModel,
        filterLabel: String
    ): List<String> {
        val lines = mutableListOf<String>()
        lines += "Insights report"
        lines += filterLabel
        lines += "history=${history.items.size}, trendWeeks=${trend.items.size}"
        lines += ""
        lines += "History"

        history.items.forEach { item ->
            val counts = "an=${item.anomalies.size}, rec=${item.recommendations.size}"
            lines += "${item.date} ${item.source} ${item.status} | run=${item.runTs} | $counts"
            item.errorMessage?.takeIf { it.isNotBlank() }?.let { lines += "  error=$it" }
            item.summary.takeIf { it.isNotBlank() }?.let { lines += "  summary=${it.take(180)}" }
        }

        lines += ""
        lines += "Weekly trend"
        trend.items.forEach { item ->
            lines += "${item.weekStart}: runs=${item.totalRuns}, ok=${item.successRuns}, fail=${item.failedRuns}, manual=${item.manualRuns}, sched=${item.schedulerRuns}, an=${item.anomaliesCount}, rec=${item.recommendationsCount}"
        }

        return lines
    }

    private fun escape(value: String): String {
        if (!value.contains(",") && !value.contains('"') && !value.contains('\n')) return value
        return '"' + value.replace("\"", "\"\"") + '"'
    }
}
