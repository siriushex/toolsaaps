package io.aaps.copilot.data.repository

import com.google.common.truth.Truth.assertThat
import io.aaps.copilot.data.remote.cloud.ReplayDayTypeStats
import io.aaps.copilot.data.remote.cloud.ReplayDriftStats
import io.aaps.copilot.data.remote.cloud.ReplayForecastStats
import io.aaps.copilot.data.remote.cloud.ReplayHourStats
import io.aaps.copilot.data.remote.cloud.ReplayRuleStats
import org.junit.Test

class ReplayExportFormatterTest {

    private val report = CloudReplayUiModel(
        days = 14,
        points = 200,
        stepMinutes = 5,
        forecastStats = listOf(
            ReplayForecastStats(horizon = 5, sampleCount = 100, mae = 0.2, rmse = 0.3, mardPct = 4.1),
            ReplayForecastStats(horizon = 60, sampleCount = 90, mae = 0.8, rmse = 1.1, mardPct = 9.0)
        ),
        ruleStats = listOf(
            ReplayRuleStats(ruleId = "PostHypoReboundGuard.v1", triggered = 3, blocked = 1, noMatch = 50)
        ),
        dayTypeStats = listOf(
            ReplayDayTypeStats(dayType = "WEEKDAY", forecastStats = listOf(ReplayForecastStats(5, 70, 0.22, 0.33, 4.3))),
            ReplayDayTypeStats(dayType = "WEEKEND", forecastStats = listOf(ReplayForecastStats(5, 30, 0.18, 0.28, 3.8)))
        ),
        hourlyStats = listOf(
            ReplayHourStats(hour = 7, sampleCount = 12, mae = 0.25, rmse = 0.34, mardPct = 4.4)
        ),
        driftStats = listOf(
            ReplayDriftStats(horizon = 5, previousMae = 0.19, recentMae = 0.22, deltaMae = 0.03)
        )
    )

    @Test
    fun buildCsv_containsSections() {
        val csv = ReplayExportFormatter.buildCsv(report)

        assertThat(csv).contains("section,key1,key2,key3,key4,key5,key6")
        assertThat(csv).contains("forecast,5,100")
        assertThat(csv).contains("dayType,WEEKDAY,5")
        assertThat(csv).contains("hourly,7,12")
        assertThat(csv).contains("drift,5")
        assertThat(csv).contains("rule,PostHypoReboundGuard.v1,3,1,50")
    }

    @Test
    fun buildPdfLines_respectsHorizonFilter() {
        val lines = ReplayExportFormatter.buildPdfLines(report, horizonFilter = 5)

        assertThat(lines.any { it.startsWith("5m") }).isTrue()
        assertThat(lines.any { it.startsWith("60m") }).isFalse()
    }
}
