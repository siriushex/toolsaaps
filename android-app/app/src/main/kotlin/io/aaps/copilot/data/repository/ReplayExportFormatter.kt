package io.aaps.copilot.data.repository

object ReplayExportFormatter {

    fun buildCsv(report: CloudReplayUiModel): String {
        val rows = mutableListOf<String>()
        rows += "section,key1,key2,key3,key4,key5,key6"
        rows += "meta,days,${report.days},points,${report.points},stepMinutes,${report.stepMinutes}"

        report.forecastStats.forEach {
            rows += "forecast,${it.horizon},${it.sampleCount},${fmt(it.mae)},${fmt(it.rmse)},${fmt(it.mardPct)},"
        }

        report.dayTypeStats.forEach { day ->
            day.forecastStats.forEach {
                rows += "dayType,${day.dayType},${it.horizon},${it.sampleCount},${fmt(it.mae)},${fmt(it.rmse)},${fmt(it.mardPct)}"
            }
        }

        report.hourlyStats.forEach {
            rows += "hourly,${it.hour},${it.sampleCount},${fmt(it.mae)},${fmt(it.rmse)},${fmt(it.mardPct)},"
        }

        report.driftStats.forEach {
            rows += "drift,${it.horizon},${fmt(it.previousMae)},${fmt(it.recentMae)},${fmt(it.deltaMae)},,"
        }

        report.ruleStats.forEach {
            rows += "rule,${escape(it.ruleId)},${it.triggered},${it.blocked},${it.noMatch},,"
        }

        return rows.joinToString(separator = "\n")
    }

    fun buildPdfLines(report: CloudReplayUiModel, horizonFilter: Int?): List<String> {
        val lines = mutableListOf<String>()
        lines += "Replay report"
        lines += "days=${report.days}, points=${report.points}, step=${report.stepMinutes}m"
        lines += ""
        lines += "Forecast"
        report.forecastStats
            .filter { horizonFilter == null || it.horizon == horizonFilter }
            .forEach {
                lines += "${it.horizon}m n=${it.sampleCount}, MAE=${fmt(it.mae)}, RMSE=${fmt(it.rmse)}, MARD=${fmt(it.mardPct)}%"
            }

        lines += ""
        lines += "Weekday/Weekend"
        report.dayTypeStats.forEach { day ->
            lines += day.dayType
            day.forecastStats
                .filter { horizonFilter == null || it.horizon == horizonFilter }
                .forEach {
                    lines += "  ${it.horizon}m n=${it.sampleCount}, MAE=${fmt(it.mae)}"
                }
        }

        lines += ""
        lines += "Hourly"
        report.hourlyStats.forEach {
            lines += "h${it.hour.toString().padStart(2, '0')} n=${it.sampleCount}, MAE=${fmt(it.mae)}, RMSE=${fmt(it.rmse)}"
        }

        lines += ""
        lines += "Drift"
        report.driftStats
            .filter { horizonFilter == null || it.horizon == horizonFilter }
            .forEach {
                lines += "${it.horizon}m prev=${fmt(it.previousMae)} recent=${fmt(it.recentMae)} delta=${fmt(it.deltaMae)}"
            }

        lines += ""
        lines += "Rules"
        report.ruleStats.forEach {
            lines += "${it.ruleId}: TRG=${it.triggered}, BLK=${it.blocked}, NO=${it.noMatch}"
        }

        return lines
    }

    private fun fmt(value: Double): String = String.format("%.4f", value)

    private fun escape(value: String): String {
        if (!value.contains(",") && !value.contains('"')) return value
        return '"' + value.replace("\"", "\"\"") + '"'
    }
}
