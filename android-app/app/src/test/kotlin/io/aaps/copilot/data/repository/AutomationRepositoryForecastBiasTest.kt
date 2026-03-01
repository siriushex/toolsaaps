package io.aaps.copilot.data.repository

import com.google.common.truth.Truth.assertThat
import io.aaps.copilot.domain.model.Forecast
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

    private fun sampleForecasts(): List<Forecast> {
        val now = System.currentTimeMillis()
        return listOf(
            Forecast(now + 5 * 60_000L, 5, 6.0, 5.5, 6.5, "test"),
            Forecast(now + 30 * 60_000L, 30, 6.4, 5.7, 7.1, "test"),
            Forecast(now + 60 * 60_000L, 60, 6.8, 5.8, 7.8, "test")
        )
    }
}
