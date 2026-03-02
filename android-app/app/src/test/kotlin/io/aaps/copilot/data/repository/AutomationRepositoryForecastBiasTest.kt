package io.aaps.copilot.data.repository

import com.google.common.truth.Truth.assertThat
import io.aaps.copilot.domain.model.DataQuality
import io.aaps.copilot.domain.model.Forecast
import io.aaps.copilot.domain.model.GlucosePoint
import org.junit.Test

class AutomationRepositoryForecastBiasTest {

    @Test
    fun cobBias_raisesForecasts() {
        val source = sampleForecasts()
        val adjusted = AutomationRepository.applyCobIobForecastBiasStatic(
            forecasts = source,
            cobGrams = 40.0,
            iobUnits = 0.0
        )

        assertThat(adjusted[0].valueMmol).isGreaterThan(source[0].valueMmol)
        assertThat(adjusted[1].valueMmol).isGreaterThan(source[1].valueMmol)
        assertThat(adjusted[2].valueMmol).isGreaterThan(source[2].valueMmol)
        assertThat(adjusted.all { it.modelVersion.contains("cob_iob_bias_v1") }).isTrue()
    }

    @Test
    fun iobBias_lowersForecasts() {
        val source = sampleForecasts()
        val adjusted = AutomationRepository.applyCobIobForecastBiasStatic(
            forecasts = source,
            cobGrams = 0.0,
            iobUnits = 3.0
        )

        assertThat(adjusted[0].valueMmol).isLessThan(source[0].valueMmol)
        assertThat(adjusted[1].valueMmol).isLessThan(source[1].valueMmol)
        assertThat(adjusted[2].valueMmol).isLessThan(source[2].valueMmol)
    }

    @Test
    fun ciAndValueStayWithinPhysiologicBounds() {
        val source = listOf(
            Forecast(
                ts = System.currentTimeMillis() + 60 * 60_000L,
                horizonMinutes = 60,
                valueMmol = 21.8,
                ciLow = 20.8,
                ciHigh = 22.0,
                modelVersion = "test"
            )
        )

        val adjusted = AutomationRepository.applyCobIobForecastBiasStatic(
            forecasts = source,
            cobGrams = 200.0,
            iobUnits = 0.0
        )

        assertThat(adjusted.single().valueMmol).isAtMost(22.0)
        assertThat(adjusted.single().ciLow).isAtLeast(2.2)
        assertThat(adjusted.single().ciHigh).isAtMost(22.0)
        assertThat(adjusted.single().ciLow).isAtMost(adjusted.single().valueMmol)
        assertThat(adjusted.single().ciHigh).isAtLeast(adjusted.single().valueMmol)
    }

    @Test
    fun baseAlignment_isSkippedForAdaptiveRule() {
        val skipped = AutomationRepository.shouldSkipBaseAlignmentStatic(
            sourceRuleId = "AdaptiveTargetController.v1",
            actionReason = "adaptive_pi_ci_v2|mode=control_pi"
        )

        assertThat(skipped).isTrue()
    }

    @Test
    fun baseAlignment_isAllowedForNonAdaptiveRules() {
        val skipped = AutomationRepository.shouldSkipBaseAlignmentStatic(
            sourceRuleId = "PatternAdaptiveTargetRule.v1",
            actionReason = "pattern_weekday_high"
        )

        assertThat(skipped).isFalse()
    }

    @Test
    fun calibrationBias_raisesWhenHistoryUnderpredicts() {
        val source = sampleForecasts()
        val history = buildList {
            repeat(40) { idx ->
                add(
                    AutomationRepository.ForecastCalibrationPoint(
                        horizonMinutes = 60,
                        errorMmol = 1.0, // actual > pred
                        ageMs = (idx + 1) * 5 * 60_000L
                    )
                )
            }
        }

        val adjusted = AutomationRepository.applyRecentForecastCalibrationBiasStatic(
            forecasts = source,
            history = history
        )

        val src60 = source.first { it.horizonMinutes == 60 }
        val adj60 = adjusted.first { it.horizonMinutes == 60 }
        assertThat(adj60.valueMmol).isGreaterThan(src60.valueMmol)
        assertThat(adj60.modelVersion).contains("calib_v1")
    }

    @Test
    fun calibrationBias_lowersWhenHistoryOverpredicts_withClamp() {
        val source = sampleForecasts()
        val history = buildList {
            repeat(40) { idx ->
                add(
                    AutomationRepository.ForecastCalibrationPoint(
                        horizonMinutes = 30,
                        errorMmol = -2.0, // actual < pred
                        ageMs = (idx + 1) * 5 * 60_000L
                    )
                )
            }
        }

        val adjusted = AutomationRepository.applyRecentForecastCalibrationBiasStatic(
            forecasts = source,
            history = history
        )

        val src30 = source.first { it.horizonMinutes == 30 }
        val adj30 = adjusted.first { it.horizonMinutes == 30 }
        assertThat(adj30.valueMmol).isLessThan(src30.valueMmol)
        assertThat(src30.valueMmol - adj30.valueMmol).isAtMost(0.46)
    }

    @Test
    fun calibrationBias_notAppliedWhenSamplesInsufficient() {
        val source = sampleForecasts()
        val history = listOf(
            AutomationRepository.ForecastCalibrationPoint(
                horizonMinutes = 60,
                errorMmol = 1.5,
                ageMs = 10 * 60_000L
            )
        )

        val adjusted = AutomationRepository.applyRecentForecastCalibrationBiasStatic(
            forecasts = source,
            history = history
        )

        assertThat(adjusted).isEqualTo(source)
    }

    @Test
    fun sensorQuality_detectsSuspectFalseLow() {
        val now = System.currentTimeMillis()
        val glucose = listOf(
            GlucosePoint(now - 10 * 60_000L, 8.8, "test", DataQuality.OK),
            GlucosePoint(now - 5 * 60_000L, 8.6, "test", DataQuality.OK),
            GlucosePoint(now, 3.3, "test", DataQuality.OK)
        )

        val assessment = AutomationRepository.evaluateSensorQualityStatic(
            glucose = glucose,
            nowTs = now,
            staleMaxMinutes = 15
        )

        assertThat(assessment.blocked).isTrue()
        assertThat(assessment.suspectFalseLow).isTrue()
        assertThat(assessment.reason).isEqualTo("suspect_false_low")
    }

    @Test
    fun sensorQuality_stableSeriesRemainsOk() {
        val now = System.currentTimeMillis()
        val glucose = listOf(
            GlucosePoint(now - 20 * 60_000L, 6.1, "test"),
            GlucosePoint(now - 15 * 60_000L, 6.0, "test"),
            GlucosePoint(now - 10 * 60_000L, 6.1, "test"),
            GlucosePoint(now - 5 * 60_000L, 6.0, "test"),
            GlucosePoint(now, 6.1, "test")
        )

        val assessment = AutomationRepository.evaluateSensorQualityStatic(
            glucose = glucose,
            nowTs = now,
            staleMaxMinutes = 15
        )

        assertThat(assessment.blocked).isFalse()
        assertThat(assessment.score).isGreaterThan(0.70)
    }

    @Test
    fun sensorQualityRollback_sentWhenBlockedAndTargetDrifted() {
        val assessment = AutomationRepository.SensorQualityAssessment(
            score = 0.2,
            blocked = true,
            reason = "suspect_false_low",
            suspectFalseLow = true,
            delta5Mmol = -1.8,
            noiseStd5Mmol = 0.9,
            gapMinutes = 1.0
        )

        val shouldRollback = AutomationRepository.shouldSendSensorQualityRollbackStatic(
            activeTempTarget = 8.0,
            baseTargetMmol = 5.5,
            assessment = assessment
        )

        assertThat(shouldRollback).isTrue()
    }

    @Test
    fun sensorQualityRollback_notSentWhenNearBase() {
        val assessment = AutomationRepository.SensorQualityAssessment(
            score = 0.4,
            blocked = true,
            reason = "rapid_delta",
            suspectFalseLow = false,
            delta5Mmol = 1.7,
            noiseStd5Mmol = 0.2,
            gapMinutes = 1.0
        )

        val shouldRollback = AutomationRepository.shouldSendSensorQualityRollbackStatic(
            activeTempTarget = 5.6,
            baseTargetMmol = 5.5,
            assessment = assessment
        )

        assertThat(shouldRollback).isFalse()
    }

    @Test
    fun normalizeForecastSet_keepsSingleRowPerHorizon() {
        val now = System.currentTimeMillis()
        val source = listOf(
            Forecast(now + 5 * 60_000L, 5, 6.0, 5.0, 7.0, "local-hybrid-v3"),
            Forecast(now + 5 * 60_000L, 5, 6.2, 5.6, 6.8, "local-hybrid-v3"),
            Forecast(now + 30 * 60_000L, 30, 6.6, 5.5, 7.7, "local-hybrid-v3"),
            Forecast(now + 30 * 60_000L, 30, 6.5, 5.9, 7.1, "cloud-v1"),
            Forecast(now + 60 * 60_000L, 60, 7.0, 5.8, 8.2, "local-hybrid-v3")
        )

        val normalized = AutomationRepository.normalizeForecastSetStatic(source)

        assertThat(normalized.map { it.horizonMinutes }).containsExactly(5, 30, 60)
        assertThat(normalized.first { it.horizonMinutes == 5 }.valueMmol).isEqualTo(6.2)
        assertThat(normalized.first { it.horizonMinutes == 30 }.modelVersion).contains("cloud")
    }

    private fun sampleForecasts(): List<Forecast> {
        val now = System.currentTimeMillis()
        return listOf(
            Forecast(now + 5 * 60_000L, 5, 6.0, 5.5, 6.5, "test"),
            Forecast(now + 30 * 60_000L, 30, 6.4, 5.7, 7.1, "test"),
            Forecast(now + 60 * 60_000L, 60, 6.8, 5.8, 7.8, "test")
        )
    }
}
