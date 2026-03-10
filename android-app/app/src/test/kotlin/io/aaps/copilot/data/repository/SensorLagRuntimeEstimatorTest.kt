package io.aaps.copilot.data.repository

import com.google.common.truth.Truth.assertThat
import io.aaps.copilot.config.SensorLagCorrectionMode
import io.aaps.copilot.domain.model.DataQuality
import io.aaps.copilot.domain.model.GlucosePoint
import io.aaps.copilot.domain.model.SensorLagAgeSource
import io.aaps.copilot.domain.model.TherapyEvent
import org.junit.Test

class SensorLagRuntimeEstimatorTest {

    @Test
    fun explicitSensorAge_twoDays_risingTrendProducesSmallPositiveCorrection() {
        val now = 1_710_000_000_000L
        val glucose = recentRisingSeries(nowTs = now, rawCurrent = 7.2, risePer5m = 0.18)

        val estimate = SensorLagRuntimeEstimator.estimate(
            SensorLagRuntimeEstimator.Input(
                nowTs = now,
                glucose = glucose,
                therapy = listOf(TherapyEvent(ts = now - 48L * HOUR_MS, type = "sensor_change", payload = emptyMap())),
                latestGlucose = glucose.last(),
                requestedMode = SensorLagCorrectionMode.ACTIVE,
                staleMaxMinutes = 15,
                sensorQualityScore = 0.84,
                sensorBlocked = false,
                sensorSuspectFalseLow = false,
                latestInput = GlucoseInputMetadata(key = "sgv", kind = "sgv")
            )
        )

        assertThat(estimate.ageSource).isEqualTo(SensorLagAgeSource.EXPLICIT)
        assertThat(estimate.mode).isEqualTo(SensorLagCorrectionMode.ACTIVE)
        assertThat(estimate.lagMinutes).isAtLeast(8.0)
        assertThat(estimate.lagMinutes).isLessThan(9.0)
        assertThat(estimate.correctedGlucoseMmol).isGreaterThan(estimate.rawGlucoseMmol)
        assertThat(estimate.correctionMmol).isGreaterThan(0.0)
    }

    @Test
    fun explicitSensorAge_thirteenDays_capsCorrectionWithinSafeBounds() {
        val now = 1_710_000_000_000L
        val glucose = recentRisingSeries(nowTs = now, rawCurrent = 8.4, risePer5m = 0.40)

        val estimate = SensorLagRuntimeEstimator.estimate(
            SensorLagRuntimeEstimator.Input(
                nowTs = now,
                glucose = glucose,
                therapy = listOf(TherapyEvent(ts = now - 13L * DAY_MS, type = "sensor_change", payload = emptyMap())),
                latestGlucose = glucose.last(),
                requestedMode = SensorLagCorrectionMode.ACTIVE,
                staleMaxMinutes = 15,
                sensorQualityScore = 0.88,
                sensorBlocked = false,
                sensorSuspectFalseLow = false,
                latestInput = GlucoseInputMetadata(key = "sgv", kind = "sgv")
            )
        )

        assertThat(estimate.lagMinutes).isGreaterThan(17.0)
        assertThat(estimate.lagMinutes).isAtMost(20.0)
        assertThat(kotlin.math.abs(estimate.correctionMmol)).isAtMost(1.5)
        assertThat(estimate.correctedGlucoseMmol).isGreaterThan(estimate.rawGlucoseMmol)
    }

    @Test
    fun inferredSensorAge_activeModeCapsLagAtTwelveMinutes() {
        val now = 1_710_000_000_000L
        val boundaryTs = now - 13L * DAY_MS
        val glucose = buildList {
            add(GlucosePoint(ts = boundaryTs - FIVE_MIN_MS, valueMmol = 6.0, source = "source_old", quality = DataQuality.OK))
            var ts = boundaryTs
            var value = 6.0
            while (ts <= now) {
                if (ts >= now - 30L * 60L * 1000L) {
                    value += 0.12
                }
                add(GlucosePoint(ts = ts, valueMmol = value, source = "source_new", quality = DataQuality.OK))
                ts += FIVE_MIN_MS
            }
        }

        val estimate = SensorLagRuntimeEstimator.estimate(
            SensorLagRuntimeEstimator.Input(
                nowTs = now,
                glucose = glucose,
                therapy = emptyList(),
                latestGlucose = glucose.last(),
                requestedMode = SensorLagCorrectionMode.ACTIVE,
                staleMaxMinutes = 15,
                sensorQualityScore = 0.80,
                sensorBlocked = false,
                sensorSuspectFalseLow = false,
                latestInput = GlucoseInputMetadata(key = "sgv", kind = "sgv")
            )
        )

        assertThat(estimate.ageSource).isEqualTo(SensorLagAgeSource.INFERRED)
        assertThat(estimate.mode).isEqualTo(SensorLagCorrectionMode.ACTIVE)
        assertThat(estimate.lagMinutes).isWithin(0.001).of(12.0)
        assertThat(estimate.correctionMmol).isGreaterThan(0.0)
        assertThat(estimate.disableReason).isNull()
    }

    @Test
    fun missingSensorAge_activeFallsBackToShadowWithReason() {
        val now = 1_710_000_000_000L
        val glucose = recentRisingSeries(nowTs = now, rawCurrent = 6.9, risePer5m = 0.10)

        val estimate = SensorLagRuntimeEstimator.estimate(
            SensorLagRuntimeEstimator.Input(
                nowTs = now,
                glucose = glucose,
                therapy = emptyList(),
                latestGlucose = glucose.last(),
                requestedMode = SensorLagCorrectionMode.ACTIVE,
                staleMaxMinutes = 15,
                sensorQualityScore = 0.85,
                sensorBlocked = false,
                sensorSuspectFalseLow = false,
                latestInput = GlucoseInputMetadata(key = "sgv", kind = "sgv")
            )
        )

        assertThat(estimate.ageSource).isEqualTo(SensorLagAgeSource.MISSING)
        assertThat(estimate.mode).isEqualTo(SensorLagCorrectionMode.SHADOW)
        assertThat(estimate.disableReason).isEqualTo("sensor_age_unresolved")
    }

    @Test
    fun rawInputBlocksActiveAndKeepsShadowOnly() {
        val now = 1_710_000_000_000L
        val glucose = recentRisingSeries(nowTs = now, rawCurrent = 7.4, risePer5m = 0.18)

        val estimate = SensorLagRuntimeEstimator.estimate(
            SensorLagRuntimeEstimator.Input(
                nowTs = now,
                glucose = glucose,
                therapy = listOf(TherapyEvent(ts = now - 72L * HOUR_MS, type = "sensor_change", payload = emptyMap())),
                latestGlucose = glucose.last(),
                requestedMode = SensorLagCorrectionMode.ACTIVE,
                staleMaxMinutes = 15,
                sensorQualityScore = 0.84,
                sensorBlocked = false,
                sensorSuspectFalseLow = false,
                latestInput = GlucoseInputMetadata(key = "raw_sgv", kind = "raw")
            )
        )

        assertThat(estimate.mode).isEqualTo(SensorLagCorrectionMode.SHADOW)
        assertThat(estimate.disableReason).isEqualTo("raw_glucose_input")
        assertThat(estimate.correctedGlucoseMmol).isGreaterThan(estimate.rawGlucoseMmol)
    }

    @Test
    fun fallingTrendProducesNegativeCorrection() {
        val now = 1_710_000_000_000L
        val glucose = recentSeries(nowTs = now, rawCurrent = 6.8) { stepsFromLatest ->
            6.8 + 0.16 * stepsFromLatest
        }

        val estimate = SensorLagRuntimeEstimator.estimate(
            SensorLagRuntimeEstimator.Input(
                nowTs = now,
                glucose = glucose,
                therapy = listOf(TherapyEvent(ts = now - 72L * HOUR_MS, type = "sensor_change", payload = emptyMap())),
                latestGlucose = glucose.last(),
                requestedMode = SensorLagCorrectionMode.ACTIVE,
                staleMaxMinutes = 15,
                sensorQualityScore = 0.86,
                sensorBlocked = false,
                sensorSuspectFalseLow = false,
                latestInput = GlucoseInputMetadata(key = "sgv", kind = "sgv")
            )
        )

        assertThat(estimate.mode).isEqualTo(SensorLagCorrectionMode.ACTIVE)
        assertThat(estimate.correctionMmol).isLessThan(0.0)
        assertThat(estimate.correctedGlucoseMmol).isLessThan(estimate.rawGlucoseMmol)
    }

    @Test
    fun flatTrendProducesNearZeroCorrection() {
        val now = 1_710_000_000_000L
        val glucose = recentSeries(nowTs = now, rawCurrent = 7.0) { 7.0 }

        val estimate = SensorLagRuntimeEstimator.estimate(
            SensorLagRuntimeEstimator.Input(
                nowTs = now,
                glucose = glucose,
                therapy = listOf(TherapyEvent(ts = now - 11L * DAY_MS, type = "sensor_change", payload = emptyMap())),
                latestGlucose = glucose.last(),
                requestedMode = SensorLagCorrectionMode.ACTIVE,
                staleMaxMinutes = 15,
                sensorQualityScore = 0.90,
                sensorBlocked = false,
                sensorSuspectFalseLow = false,
                latestInput = GlucoseInputMetadata(key = "sgv", kind = "sgv")
            )
        )

        assertThat(estimate.mode).isEqualTo(SensorLagCorrectionMode.ACTIVE)
        assertThat(kotlin.math.abs(estimate.correctionMmol)).isLessThan(0.001)
        assertThat(estimate.correctedGlucoseMmol).isWithin(0.001).of(estimate.rawGlucoseMmol)
    }

    @Test
    fun staleDataBlocksActiveAndFallsBackToShadow() {
        val now = 1_710_000_000_000L
        val glucose = recentRisingSeries(nowTs = now - 20L * 60L * 1000L, rawCurrent = 7.3, risePer5m = 0.15)

        val estimate = SensorLagRuntimeEstimator.estimate(
            SensorLagRuntimeEstimator.Input(
                nowTs = now,
                glucose = glucose,
                therapy = listOf(TherapyEvent(ts = now - 3L * DAY_MS, type = "sensor_change", payload = emptyMap())),
                latestGlucose = glucose.last(),
                requestedMode = SensorLagCorrectionMode.ACTIVE,
                staleMaxMinutes = 15,
                sensorQualityScore = 0.82,
                sensorBlocked = false,
                sensorSuspectFalseLow = false,
                latestInput = GlucoseInputMetadata(key = "sgv", kind = "sgv")
            )
        )

        assertThat(estimate.mode).isEqualTo(SensorLagCorrectionMode.SHADOW)
        assertThat(estimate.disableReason).isEqualTo("stale_glucose")
    }

    @Test
    fun insufficientRecentPointsBlockActiveAndFallBackToShadow() {
        val now = 1_710_000_000_000L
        val glucose = listOf(
            GlucosePoint(ts = now - 10L * FIVE_MIN_MS, valueMmol = 6.6, source = "nightscout", quality = DataQuality.OK),
            GlucosePoint(ts = now - 5L * FIVE_MIN_MS, valueMmol = 6.9, source = "nightscout", quality = DataQuality.OK),
            GlucosePoint(ts = now, valueMmol = 7.1, source = "nightscout", quality = DataQuality.OK)
        )

        val estimate = SensorLagRuntimeEstimator.estimate(
            SensorLagRuntimeEstimator.Input(
                nowTs = now,
                glucose = glucose,
                therapy = listOf(TherapyEvent(ts = now - 3L * DAY_MS, type = "sensor_change", payload = emptyMap())),
                latestGlucose = glucose.last(),
                requestedMode = SensorLagCorrectionMode.ACTIVE,
                staleMaxMinutes = 15,
                sensorQualityScore = 0.82,
                sensorBlocked = false,
                sensorSuspectFalseLow = false,
                latestInput = GlucoseInputMetadata(key = "sgv", kind = "sgv")
            )
        )

        assertThat(estimate.mode).isEqualTo(SensorLagCorrectionMode.SHADOW)
        assertThat(estimate.disableReason).isEqualTo("insufficient_recent_points")
    }

    @Test
    fun sensorBlockedAndFalseLowFlagsBlockActive() {
        val now = 1_710_000_000_000L
        val glucose = recentRisingSeries(nowTs = now, rawCurrent = 7.1, risePer5m = 0.12)

        val blockedEstimate = SensorLagRuntimeEstimator.estimate(
            SensorLagRuntimeEstimator.Input(
                nowTs = now,
                glucose = glucose,
                therapy = listOf(TherapyEvent(ts = now - 4L * DAY_MS, type = "sensor_change", payload = emptyMap())),
                latestGlucose = glucose.last(),
                requestedMode = SensorLagCorrectionMode.ACTIVE,
                staleMaxMinutes = 15,
                sensorQualityScore = 0.80,
                sensorBlocked = true,
                sensorSuspectFalseLow = false,
                latestInput = GlucoseInputMetadata(key = "sgv", kind = "sgv")
            )
        )
        val falseLowEstimate = SensorLagRuntimeEstimator.estimate(
            SensorLagRuntimeEstimator.Input(
                nowTs = now,
                glucose = glucose,
                therapy = listOf(TherapyEvent(ts = now - 4L * DAY_MS, type = "sensor_change", payload = emptyMap())),
                latestGlucose = glucose.last(),
                requestedMode = SensorLagCorrectionMode.ACTIVE,
                staleMaxMinutes = 15,
                sensorQualityScore = 0.80,
                sensorBlocked = false,
                sensorSuspectFalseLow = true,
                latestInput = GlucoseInputMetadata(key = "sgv", kind = "sgv")
            )
        )

        assertThat(blockedEstimate.mode).isEqualTo(SensorLagCorrectionMode.SHADOW)
        assertThat(blockedEstimate.disableReason).isEqualTo("sensor_quality_blocked")
        assertThat(falseLowEstimate.mode).isEqualTo(SensorLagCorrectionMode.SHADOW)
        assertThat(falseLowEstimate.disableReason).isEqualTo("sensor_suspect_false_low")
    }

    @Test
    fun lowSensorQualityScoreBlocksActive() {
        val now = 1_710_000_000_000L
        val glucose = recentRisingSeries(nowTs = now, rawCurrent = 7.0, risePer5m = 0.10)

        val estimate = SensorLagRuntimeEstimator.estimate(
            SensorLagRuntimeEstimator.Input(
                nowTs = now,
                glucose = glucose,
                therapy = listOf(TherapyEvent(ts = now - 4L * DAY_MS, type = "sensor_change", payload = emptyMap())),
                latestGlucose = glucose.last(),
                requestedMode = SensorLagCorrectionMode.ACTIVE,
                staleMaxMinutes = 15,
                sensorQualityScore = 0.42,
                sensorBlocked = false,
                sensorSuspectFalseLow = false,
                latestInput = GlucoseInputMetadata(key = "sgv", kind = "sgv")
            )
        )

        assertThat(estimate.mode).isEqualTo(SensorLagCorrectionMode.SHADOW)
        assertThat(estimate.disableReason).isEqualTo("sensor_quality_low")
    }

    @Test
    fun shadowModeWithMissingAgeKeepsAdvisoryEstimateAndDisableReason() {
        val now = 1_710_000_000_000L
        val glucose = recentRisingSeries(nowTs = now, rawCurrent = 6.9, risePer5m = 0.10)

        val estimate = SensorLagRuntimeEstimator.estimate(
            SensorLagRuntimeEstimator.Input(
                nowTs = now,
                glucose = glucose,
                therapy = emptyList(),
                latestGlucose = glucose.last(),
                requestedMode = SensorLagCorrectionMode.SHADOW,
                staleMaxMinutes = 15,
                sensorQualityScore = 0.85,
                sensorBlocked = false,
                sensorSuspectFalseLow = false,
                latestInput = GlucoseInputMetadata(key = "sgv", kind = "sgv")
            )
        )

        assertThat(estimate.ageSource).isEqualTo(SensorLagAgeSource.MISSING)
        assertThat(estimate.mode).isEqualTo(SensorLagCorrectionMode.SHADOW)
        assertThat(estimate.disableReason).isEqualTo("sensor_age_unresolved")
        assertThat(estimate.correctionMmol).isGreaterThan(0.0)
    }

    @Test
    fun forecastBias_scalesCorrectionByHorizon() {
        val estimate = io.aaps.copilot.domain.model.SensorLagEstimate(
            rawGlucoseMmol = 7.0,
            correctedGlucoseMmol = 7.6,
            lagMinutes = 10.0,
            correctionMmol = 0.6,
            ageHours = 48.0,
            ageSource = SensorLagAgeSource.EXPLICIT,
            confidence = 0.9,
            mode = SensorLagCorrectionMode.ACTIVE,
            disableReason = null
        )
        val forecasts = listOf(
            io.aaps.copilot.domain.model.Forecast(nowTs(5), 5, 7.1, 6.5, 7.7, "test"),
            io.aaps.copilot.domain.model.Forecast(nowTs(30), 30, 7.4, 6.8, 8.0, "test"),
            io.aaps.copilot.domain.model.Forecast(nowTs(60), 60, 7.8, 7.0, 8.6, "test")
        )

        val adjusted = SensorLagRuntimeEstimator.applyForecastBias(forecasts, estimate)

        assertThat(adjusted[0].valueMmol - forecasts[0].valueMmol).isWithin(0.001).of(0.6)
        assertThat(adjusted[1].valueMmol - forecasts[1].valueMmol).isWithin(0.001).of(0.36)
        assertThat(adjusted[2].valueMmol - forecasts[2].valueMmol).isWithin(0.001).of(0.18)
    }

    private fun recentRisingSeries(
        nowTs: Long,
        rawCurrent: Double,
        risePer5m: Double
    ): List<GlucosePoint> {
        return recentSeries(nowTs = nowTs, rawCurrent = rawCurrent) { stepsFromLatest ->
            rawCurrent - risePer5m * stepsFromLatest
        }
    }

    private fun recentSeries(
        nowTs: Long,
        rawCurrent: Double,
        valueAtStep: (stepsFromLatest: Int) -> Double
    ): List<GlucosePoint> {
        val points = mutableListOf<GlucosePoint>()
        for (index in 0..6) {
            val stepsFromLatest = 6 - index
            points += GlucosePoint(
                ts = nowTs - stepsFromLatest * FIVE_MIN_MS,
                valueMmol = if (stepsFromLatest == 0) rawCurrent else valueAtStep(stepsFromLatest),
                source = "nightscout",
                quality = DataQuality.OK
            )
        }
        return points
    }

    companion object {
        private const val FIVE_MIN_MS = 5L * 60L * 1000L
        private const val HOUR_MS = 60L * 60L * 1000L
        private const val DAY_MS = 24L * HOUR_MS

        private fun nowTs(minutes: Int): Long = 1_710_000_000_000L + minutes * 60L * 1000L
    }
}
