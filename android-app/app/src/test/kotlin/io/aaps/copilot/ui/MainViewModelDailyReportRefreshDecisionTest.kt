package io.aaps.copilot.ui

import com.google.common.truth.Truth.assertThat
import io.aaps.copilot.data.local.entity.TelemetrySampleEntity
import org.junit.Test

class MainViewModelDailyReportRefreshDecisionTest {

    @Test
    fun evaluateLocalDailyReportRefreshDecision_requestsGenerationWhenReportMissing() {
        val decision = MainViewModel.evaluateLocalDailyReportRefreshDecision(emptyList())

        assertThat(decision.shouldGenerate).isTrue()
        assertThat(decision.reason).isEqualTo("missing_daily_report")
    }

    @Test
    fun evaluateLocalDailyReportRefreshDecision_requestsGenerationWhenSensorLagReplayJsonMissing() {
        val ts = 1_800_000_000_000L
        val rows = listOf(
            reportNumeric(ts, "daily_report_matched_samples", 48.0),
            runtimeNumeric(ts, "sensor_age_hours", 288.0),
            runtimeText(ts, "sensor_lag_mode", "ACTIVE"),
            runtimeNumeric(ts, "sensor_lag_candidate_forecast_30m", 6.4)
        )

        val decision = MainViewModel.evaluateLocalDailyReportRefreshDecision(rows)

        assertThat(decision.shouldGenerate).isTrue()
        assertThat(decision.reason).isEqualTo("sensor_lag_replay_missing")
    }

    @Test
    fun evaluateLocalDailyReportRefreshDecision_requestsGenerationWhenShadowJsonMissing() {
        val ts = 1_800_000_000_000L
        val rows = listOf(
            reportNumeric(ts, "daily_report_matched_samples", 48.0),
            reportText(ts, "daily_report_sensor_lag_bucket_json", """[{"bucket":"12-14d"}]"""),
            runtimeNumeric(ts, "sensor_age_hours", 288.0),
            runtimeText(ts, "sensor_lag_mode", "SHADOW"),
            runtimeNumeric(ts, "sensor_lag_candidate_forecast_60m", 6.9)
        )

        val decision = MainViewModel.evaluateLocalDailyReportRefreshDecision(rows)

        assertThat(decision.shouldGenerate).isTrue()
        assertThat(decision.reason).isEqualTo("sensor_lag_shadow_missing")
    }

    @Test
    fun evaluateLocalDailyReportRefreshDecision_skipsWhenRecentReportAlreadyHasSensorLagJson() {
        val ts = 1_800_000_000_000L
        val rows = listOf(
            reportNumeric(ts, "daily_report_matched_samples", 48.0),
            reportText(ts, "daily_report_sensor_lag_bucket_json", """[{"bucket":"12-14d","n":8}]"""),
            reportText(ts, "daily_report_sensor_lag_shadow_json", """[{"bucket":"12-14d","n":4}]"""),
            runtimeNumeric(ts, "sensor_age_hours", 288.0),
            runtimeText(ts, "sensor_lag_mode", "SHADOW"),
            runtimeNumeric(ts, "sensor_lag_candidate_forecast_60m", 6.9)
        )

        val decision = MainViewModel.evaluateLocalDailyReportRefreshDecision(rows)

        assertThat(decision.shouldGenerate).isFalse()
        assertThat(decision.reason).isNull()
    }

    @Test
    fun evaluateLocalDailyReportRefreshDecision_skipsWhenSensorLagInputsAreInsufficient() {
        val ts = 1_800_000_000_000L
        val rows = listOf(
            reportNumeric(ts, "daily_report_matched_samples", 48.0),
            runtimeText(ts, "sensor_lag_mode", "ACTIVE")
        )

        val decision = MainViewModel.evaluateLocalDailyReportRefreshDecision(rows)

        assertThat(decision.shouldGenerate).isFalse()
        assertThat(decision.reason).isNull()
    }

    private fun reportNumeric(ts: Long, key: String, value: Double) =
        TelemetrySampleEntity(
            id = "report-$key-$ts",
            timestamp = ts,
            source = "forecast_daily_report",
            key = key,
            valueDouble = value,
            valueText = null,
            unit = null,
            quality = "OK"
        )

    private fun reportText(ts: Long, key: String, value: String) =
        TelemetrySampleEntity(
            id = "report-$key-$ts",
            timestamp = ts,
            source = "forecast_daily_report",
            key = key,
            valueDouble = null,
            valueText = value,
            unit = null,
            quality = "OK"
        )

    private fun runtimeNumeric(ts: Long, key: String, value: Double) =
        TelemetrySampleEntity(
            id = "runtime-$key-$ts",
            timestamp = ts,
            source = "sensor_lag_test",
            key = key,
            valueDouble = value,
            valueText = null,
            unit = null,
            quality = "OK"
        )

    private fun runtimeText(ts: Long, key: String, value: String) =
        TelemetrySampleEntity(
            id = "runtime-$key-$ts",
            timestamp = ts,
            source = "sensor_lag_test",
            key = key,
            valueDouble = null,
            valueText = value,
            unit = null,
            quality = "OK"
        )
}
