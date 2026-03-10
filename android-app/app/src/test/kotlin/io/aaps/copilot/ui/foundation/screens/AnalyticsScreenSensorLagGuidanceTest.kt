package io.aaps.copilot.ui.foundation.screens

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AnalyticsScreenSensorLagGuidanceTest {

    @Test
    fun buildSensorLagRolloutGuidance_marksActiveCandidateWhenGainAndShadowPass() {
        val guidance = buildSensorLagRolloutGuidance(
            replayBuckets = listOf(
                replay(bucket = "10-12d", horizonMinutes = 30, sampleCount = 5, gain = 0.14),
                replay(bucket = "10-12d", horizonMinutes = 60, sampleCount = 5, gain = 0.12)
            ),
            shadowBuckets = listOf(
                shadow(bucket = "10-12d", sampleCount = 10, ruleChangedRatePct = 12.0)
            )
        ).single()

        assertThat(guidance.bucket).isEqualTo("10-12d")
        assertThat(guidance.status).isEqualTo(SensorLagRolloutStatus.ACTIVE_CANDIDATE)
        assertThat(guidance.weightedGainMmol).isWithin(0.001).of(0.13)
        assertThat(guidance.replaySamples).isEqualTo(10)
    }

    @Test
    fun buildSensorLagRolloutGuidance_marksShadowFirstForModestPositiveGain() {
        val guidance = buildSensorLagRolloutGuidance(
            replayBuckets = listOf(
                replay(bucket = "12-14d", horizonMinutes = 30, sampleCount = 4, gain = 0.05),
                replay(bucket = "12-14d", horizonMinutes = 60, sampleCount = 4, gain = 0.04)
            ),
            shadowBuckets = listOf(
                shadow(bucket = "12-14d", sampleCount = 8, ruleChangedRatePct = 6.0)
            )
        ).single()

        assertThat(guidance.status).isEqualTo(SensorLagRolloutStatus.SHADOW_FIRST)
        assertThat(guidance.weightedGainMmol).isWithin(0.001).of(0.045)
    }

    @Test
    fun buildSensorLagRolloutGuidance_marksHoldWhenGainIsWeakAndShadowIsLow() {
        val guidance = buildSensorLagRolloutGuidance(
            replayBuckets = listOf(
                replay(bucket = "1-10d", horizonMinutes = 30, sampleCount = 4, gain = 0.01),
                replay(bucket = "1-10d", horizonMinutes = 60, sampleCount = 4, gain = 0.0)
            ),
            shadowBuckets = listOf(
                shadow(bucket = "1-10d", sampleCount = 8, ruleChangedRatePct = 4.0)
            )
        ).single()

        assertThat(guidance.status).isEqualTo(SensorLagRolloutStatus.HOLD)
        assertThat(guidance.weightedGainMmol).isWithin(0.001).of(0.005)
    }

    @Test
    fun buildSensorLagRolloutGuidance_marksInsufficientWhenReplaySamplesBelowThreshold() {
        val guidance = buildSensorLagRolloutGuidance(
            replayBuckets = listOf(
                replay(bucket = "<24h", horizonMinutes = 30, sampleCount = 3, gain = 0.20),
                replay(bucket = "<24h", horizonMinutes = 60, sampleCount = 3, gain = 0.18)
            ),
            shadowBuckets = listOf(
                shadow(bucket = "<24h", sampleCount = 6, ruleChangedRatePct = 15.0)
            )
        ).single()

        assertThat(guidance.status).isEqualTo(SensorLagRolloutStatus.INSUFFICIENT_DATA)
        assertThat(guidance.replaySamples).isEqualTo(6)
    }

    @Test
    fun buildSensorLagRolloutGuidance_prefers30And60MinuteReplayOver5MinuteRows() {
        val guidance = buildSensorLagRolloutGuidance(
            replayBuckets = listOf(
                replay(bucket = ">14d", horizonMinutes = 5, sampleCount = 20, gain = -0.40),
                replay(bucket = ">14d", horizonMinutes = 30, sampleCount = 5, gain = 0.12),
                replay(bucket = ">14d", horizonMinutes = 60, sampleCount = 5, gain = 0.10)
            ),
            shadowBuckets = listOf(
                shadow(bucket = ">14d", sampleCount = 10, ruleChangedRatePct = 10.0)
            )
        ).single()

        assertThat(guidance.status).isEqualTo(SensorLagRolloutStatus.ACTIVE_CANDIDATE)
        assertThat(guidance.weightedGainMmol).isWithin(0.001).of(0.11)
        assertThat(guidance.replaySamples).isEqualTo(10)
    }

    @Test
    fun sensorLagAgeBucket_mapsBoundariesAsExpected() {
        assertThat(sensorLagAgeBucket(23.9)).isEqualTo("<24h")
        assertThat(sensorLagAgeBucket(24.0)).isEqualTo("1-10d")
        assertThat(sensorLagAgeBucket(239.9)).isEqualTo("1-10d")
        assertThat(sensorLagAgeBucket(240.0)).isEqualTo("10-12d")
        assertThat(sensorLagAgeBucket(287.9)).isEqualTo("10-12d")
        assertThat(sensorLagAgeBucket(288.0)).isEqualTo("12-14d")
        assertThat(sensorLagAgeBucket(335.9)).isEqualTo("12-14d")
        assertThat(sensorLagAgeBucket(336.0)).isEqualTo(">14d")
    }

    private fun replay(
        bucket: String,
        horizonMinutes: Int,
        sampleCount: Int,
        gain: Double
    ): DailyReportSensorLagReplayUi {
        return DailyReportSensorLagReplayUi(
            horizonMinutes = horizonMinutes,
            bucket = bucket,
            sampleCount = sampleCount,
            rawMae = 0.50,
            lagMae = (0.50 - gain).coerceAtLeast(0.01),
            maeImprovementMmol = gain,
            rawBias = 0.10,
            lagBias = 0.04
        )
    }

    private fun shadow(
        bucket: String,
        sampleCount: Int,
        ruleChangedRatePct: Double
    ): DailyReportSensorLagShadowUi {
        return DailyReportSensorLagShadowUi(
            bucket = bucket,
            sampleCount = sampleCount,
            ruleChangedRatePct = ruleChangedRatePct,
            meanAbsTargetDeltaMmol = 0.18
        )
    }
}
