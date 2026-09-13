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

        assertThat(estimate.ageSource).isEqualTo(SensorLagAgeSource.EXPLICIT_EVENT)
        assertThat(estimate.mode).isEqualTo(SensorLagCorrectionMode.ACTIVE)
        assertThat(estimate.lagMinutes).isAtLeast(9.0)
        assertThat(estimate.lagMinutes).isLessThan(9.5)
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

        assertThat(estimate.lagMinutes).isGreaterThan(15.5)
        assertThat(estimate.lagMinutes).isAtMost(18.0)
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

        assertThat(estimate.ageSource).isEqualTo(SensorLagAgeSource.INFERRED_BOUNDARY)
        assertThat(estimate.mode).isEqualTo(SensorLagCorrectionMode.ACTIVE)
        assertThat(estimate.lagMinutes).isGreaterThan(15.5)
        assertThat(estimate.lagMinutes).isAtMost(18.0)
        assertThat(estimate.effectiveCorrectionCap).isWithin(0.001).of(1.0)
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
    fun devicestatusAgeWinsOverExplicitSensorChange() {
        val now = 1_710_000_000_000L
        val glucose = recentRisingSeries(nowTs = now, rawCurrent = 7.4, risePer5m = 0.16)

        val estimate = SensorLagRuntimeEstimator.estimate(
            SensorLagRuntimeEstimator.Input(
                nowTs = now,
                glucose = glucose,
                therapy = listOf(TherapyEvent(ts = now - 72L * HOUR_MS, type = "sensor_change", payload = emptyMap())),
                latestGlucose = glucose.last(),
                requestedMode = SensorLagCorrectionMode.ACTIVE,
                staleMaxMinutes = 15,
                sensorQualityScore = 0.88,
                sensorBlocked = false,
                sensorSuspectFalseLow = false,
                latestInput = GlucoseInputMetadata(key = "sgv", kind = "sgv"),
                devicestatusAgeHours = 36.0,
                devicestatusAgeTs = now - HOUR_MS
            )
        )

        assertThat(estimate.ageSource).isEqualTo(SensorLagAgeSource.DEVICESTATUS)
        assertThat(estimate.ageHours).isWithin(0.001).of(36.0)
    }

    @Test
    fun devicestatusConflictReducesSourceConfidence() {
        val now = 1_710_000_000_000L
        val glucose = recentRisingSeries(nowTs = now, rawCurrent = 7.4, risePer5m = 0.16)

        val estimate = SensorLagRuntimeEstimator.estimate(
            SensorLagRuntimeEstimator.Input(
                nowTs = now,
                glucose = glucose,
                therapy = listOf(TherapyEvent(ts = now - 10L * DAY_MS, type = "sensor_change", payload = emptyMap())),
                latestGlucose = glucose.last(),
                requestedMode = SensorLagCorrectionMode.ACTIVE,
                staleMaxMinutes = 15,
                sensorQualityScore = 0.88,
                sensorBlocked = false,
                sensorSuspectFalseLow = false,
                latestInput = GlucoseInputMetadata(key = "sgv", kind = "sgv"),
                devicestatusAgeHours = 48.0,
                devicestatusAgeTs = now - HOUR_MS
            )
        )

        assertThat(estimate.ageSource).isEqualTo(SensorLagAgeSource.DEVICESTATUS)
        assertThat(estimate.sourceConfidence).isLessThan(0.9)
        assertThat(estimate.ageConflictHours).isGreaterThan(24.0)
    }

    @Test
    fun noisyTrendReducesTrendConsistency() {
        val now = 1_710_000_000_000L
        val glucose = listOf(
            6.6, 7.1, 6.7, 7.4, 6.9, 7.7, 7.0, 7.8
        ).mapIndexed { index, value ->
            GlucosePoint(
                ts = now - (7 - index) * FIVE_MIN_MS,
                valueMmol = value,
                source = "nightscout",
                quality = DataQuality.OK
            )
        }

        val estimate = SensorLagRuntimeEstimator.estimate(
            SensorLagRuntimeEstimator.Input(
                nowTs = now,
                glucose = glucose,
                therapy = listOf(TherapyEvent(ts = now - 72L * HOUR_MS, type = "sensor_change", payload = emptyMap())),
                latestGlucose = glucose.last(),
                requestedMode = SensorLagCorrectionMode.ACTIVE,
                staleMaxMinutes = 15,
                sensorQualityScore = 0.88,
                sensorBlocked = false,
                sensorSuspectFalseLow = false,
                latestInput = GlucoseInputMetadata(key = "sgv", kind = "sgv")
            )
        )

        assertThat(estimate.trendConsistency).isLessThan(0.8)
    }

    @Test
    fun replayMultiplierIsBoundedForHelpfulAndHarmfulBuckets() {
        val now = 1_710_000_000_000L
        val glucose = recentRisingSeries(nowTs = now, rawCurrent = 7.4, risePer5m = 0.16)

        val helpfulEstimate = SensorLagRuntimeEstimator.estimate(
            SensorLagRuntimeEstimator.Input(
                nowTs = now,
                glucose = glucose,
                therapy = listOf(TherapyEvent(ts = now - 72L * HOUR_MS, type = "sensor_change", payload = emptyMap())),
                latestGlucose = glucose.last(),
                requestedMode = SensorLagCorrectionMode.ACTIVE,
                staleMaxMinutes = 15,
                sensorQualityScore = 0.88,
                sensorBlocked = false,
                sensorSuspectFalseLow = false,
                latestInput = GlucoseInputMetadata(key = "sgv", kind = "sgv"),
                replayBucketStats = listOf(
                    SensorLagReplayBucketStats(30, "1-10d", 8, 1.0, 0.8, 0.2, 0.0, 0.0),
                    SensorLagReplayBucketStats(60, "1-10d", 8, 1.2, 0.9, 0.3, 0.0, 0.0)
                )
            )
        )
        val harmfulEstimate = SensorLagRuntimeEstimator.estimate(
            SensorLagRuntimeEstimator.Input(
                nowTs = now,
                glucose = glucose,
                therapy = listOf(TherapyEvent(ts = now - 72L * HOUR_MS, type = "sensor_change", payload = emptyMap())),
                latestGlucose = glucose.last(),
                requestedMode = SensorLagCorrectionMode.ACTIVE,
                staleMaxMinutes = 15,
                sensorQualityScore = 0.88,
                sensorBlocked = false,
                sensorSuspectFalseLow = false,
                latestInput = GlucoseInputMetadata(key = "sgv", kind = "sgv"),
                replayBucketStats = listOf(
                    SensorLagReplayBucketStats(30, "1-10d", 8, 0.8, 1.0, -0.2, 0.0, 0.0),
                    SensorLagReplayBucketStats(60, "1-10d", 8, 0.9, 1.2, -0.3, 0.0, 0.0)
                )
            )
        )

        assertThat(helpfulEstimate.replayMultiplier).isAtMost(1.12)
        assertThat(helpfulEstimate.replayMultiplier).isAtLeast(1.0)
        assertThat(harmfulEstimate.replayMultiplier).isAtLeast(0.88)
        assertThat(harmfulEstimate.replayMultiplier).isAtMost(1.0)
    }

    @Test
    fun replayMultiplierFallsBackToHelpfulNeighborBucketWhenExactBucketMissing() {
        val now = 1_710_000_000_000L
        val glucose = recentRisingSeries(nowTs = now, rawCurrent = 7.4, risePer5m = 0.16)

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
                latestInput = GlucoseInputMetadata(key = "sgv", kind = "sgv"),
                replayBucketStats = listOf(
                    SensorLagReplayBucketStats(30, "10-12d", 9, 1.0, 0.82, 0.18, 0.0, 0.0),
                    SensorLagReplayBucketStats(60, "10-12d", 10, 1.2, 0.92, 0.28, 0.0, 0.0)
                )
            )
        )

        assertThat(estimate.wearBucket).isEqualTo("12-14d")
        assertThat(estimate.replayMultiplier).isGreaterThan(1.0)
        assertThat(estimate.replayMultiplier).isAtMost(1.12)
    }

    @Test
    fun replayMultiplierSmoothsHarmfulExactBucketWithHelpfulNeighbors() {
        val now = 1_710_000_000_000L
        val glucose = recentRisingSeries(nowTs = now, rawCurrent = 7.4, risePer5m = 0.16)

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
                latestInput = GlucoseInputMetadata(key = "sgv", kind = "sgv"),
                replayBucketStats = listOf(
                    SensorLagReplayBucketStats(30, "12-14d", 8, 0.80, 1.05, -0.25, 0.05, 0.12),
                    SensorLagReplayBucketStats(60, "12-14d", 8, 0.95, 1.25, -0.30, 0.08, 0.18),
                    SensorLagReplayBucketStats(30, "10-12d", 12, 1.05, 0.88, 0.17, 0.04, 0.01),
                    SensorLagReplayBucketStats(60, "10-12d", 12, 1.20, 0.96, 0.24, 0.06, 0.02)
                )
            )
        )

        assertThat(estimate.wearBucket).isEqualTo("12-14d")
        assertThat(estimate.replayMultiplier).isGreaterThan(0.88)
        assertThat(estimate.replayMultiplier).isLessThan(1.0)
    }

    @Test
    fun lateWearMildlyHarmfulReplayStillReducesMultiplier() {
        val now = 1_710_000_000_000L
        val glucose = recentRisingSeries(nowTs = now, rawCurrent = 7.4, risePer5m = 0.16)

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
                latestInput = GlucoseInputMetadata(key = "sgv", kind = "sgv"),
                replayBucketStats = listOf(
                    SensorLagReplayBucketStats(30, "12-14d", 30, 1.02, 1.06, -0.036, 0.40, 0.63),
                    SensorLagReplayBucketStats(60, "12-14d", 10, 0.81, 0.95, -0.142, 0.81, 0.95)
                )
            )
        )

        assertThat(estimate.wearBucket).isEqualTo("12-14d")
        assertThat(estimate.replayMultiplier).isLessThan(0.91)
        assertThat(estimate.replayMultiplier).isAtLeast(0.88)
    }

    @Test
    fun lateWearExactHarmfulBucketCapsReplayMultiplierAtLateWearGuard() {
        val now = 1_710_000_000_000L
        val glucose = recentRisingSeries(nowTs = now, rawCurrent = 7.4, risePer5m = 0.16)

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
                latestInput = GlucoseInputMetadata(key = "sgv", kind = "sgv"),
                replayBucketStats = listOf(
                    SensorLagReplayBucketStats(5, "12-14d", 45, 0.24, 0.38, -0.134, -0.01, 0.10),
                    SensorLagReplayBucketStats(30, "12-14d", 30, 1.02, 1.06, -0.036, 0.40, 0.63),
                    SensorLagReplayBucketStats(60, "12-14d", 10, 0.81, 0.95, -0.142, 0.81, 0.95),
                    SensorLagReplayBucketStats(30, "10-12d", 20, 0.95, 0.88, 0.07, 0.10, 0.02),
                    SensorLagReplayBucketStats(60, "10-12d", 18, 1.10, 0.97, 0.13, 0.15, 0.03)
                )
            )
        )

        assertThat(estimate.wearBucket).isEqualTo("12-14d")
        assertThat(estimate.replayMultiplier).isWithin(0.001).of(0.90)
    }

    @Test
    fun forecastBias_scalesCorrectionByHorizon() {
        val estimate = io.aaps.copilot.domain.model.SensorLagEstimate(
            rawGlucoseMmol = 7.0,
            correctedGlucoseMmol = 7.6,
            lagMinutes = 10.0,
            correctionMmol = 0.6,
            ageHours = 48.0,
            ageSource = SensorLagAgeSource.EXPLICIT_EVENT,
            wearBucket = "1-10d",
            sourceConfidence = 0.82,
            trendConsistency = 0.90,
            replayMultiplier = 1.0,
            effectiveLagMinutes = 10.0,
            effectiveCorrectionCap = 1.5,
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

    @Test
    fun forecastBias_lateWearBucketsApplyAdditionalHorizonAttenuation() {
        val estimate = io.aaps.copilot.domain.model.SensorLagEstimate(
            rawGlucoseMmol = 7.0,
            correctedGlucoseMmol = 7.6,
            lagMinutes = 13.5,
            correctionMmol = 0.6,
            ageHours = 300.0,
            ageSource = SensorLagAgeSource.DEVICESTATUS,
            wearBucket = "12-14d",
            sourceConfidence = 0.96,
            trendConsistency = 0.90,
            replayMultiplier = 0.90,
            effectiveLagMinutes = 13.5,
            effectiveCorrectionCap = 1.5,
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

        assertThat(adjusted[0].valueMmol - forecasts[0].valueMmol).isWithin(0.001).of(0.564)
        assertThat(adjusted[1].valueMmol - forecasts[1].valueMmol).isWithin(0.001).of(0.204672)
        assertThat(adjusted[2].valueMmol - forecasts[2].valueMmol).isWithin(0.001).of(0.068952)
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
