package io.aaps.copilot.ui.foundation.components

import com.google.common.truth.Truth.assertThat
import io.aaps.copilot.ui.foundation.screens.ChartPointUi
import io.aaps.copilot.ui.foundation.screens.ClinicalForecastChartUiState
import org.junit.Test

class InteractiveClinicalForecastChartDomainTest {
    @Test fun foodAloneCannotInventHistoryAndInvalidFoodCannotExpandTheDomain() {
        assertThat(clinicalChartDomain(ClinicalForecastChartUiState(
            mealImpactPoints = listOf(ChartPointUi(20, 5.0), ChartPointUi(100, 6.0))))).isNull()
        val chart = ClinicalForecastChartUiState(
            historyPoints = listOf(ChartPointUi(10, 5.0), ChartPointUi(20, 5.0)),
            mealImpactPoints = listOf(ChartPointUi(-1, 6.0), ChartPointUi(100, Double.NaN)))
        assertThat(clinicalChartDomain(chart)).isEqualTo(ClinicalChartDomain(10, 20, 20))
    }

    @Test fun followLiveAndResetShowTheWholeTailWithoutChangingNow() {
        val hour = 3_600_000L
        val chart = ClinicalForecastChartUiState(
            historyPoints = listOf(ChartPointUi(10 * hour, 5.0), ChartPointUi(24 * hour, 5.0)),
            mealImpactPoints = listOf(ChartPointUi(24 * hour, 5.0), ChartPointUi(30 * hour, 8.0)))
        val domain = clinicalChartDomain(chart)!!
        val viewport = defaultClinicalChartViewport(domain)
        assertThat(domain.nowTs).isEqualTo(24 * hour)
        assertThat(viewport.startTs).isEqualTo(21 * hour)
        assertThat(viewport.endTs).isEqualTo(30 * hour)
    }

    @Test
    fun domainIncludesFullFoodTailWithoutChangingCurrentGlucoseClock() {
        val chart = ClinicalForecastChartUiState(
            historyPoints = listOf(ChartPointUi(10L, 5.1), ChartPointUi(20L, 5.3)),
            mealImpactPoints = listOf(ChartPointUi(20L, 5.3), ChartPointUi(100L, 8.0))
        )
        assertThat(clinicalChartDomain(chart)).isEqualTo(ClinicalChartDomain(10L, 100L, 20L))
    }

    @Test
    fun clinicalChartDomain_historyOnlyValidPointsReturnsHistoryDomain() {
        val chart = ClinicalForecastChartUiState(
            historyPoints = listOf(
                ChartPointUi(ts = 10L, value = 5.1),
                ChartPointUi(ts = 20L, value = 5.3)
            )
        )

        assertThat(clinicalChartDomain(chart)).isEqualTo(ClinicalChartDomain(10L, 20L, 20L))
    }

    @Test
    fun clinicalChartDomain_fewerThanTwoValidSeriesPointsReturnsNull() {
        val chart = ClinicalForecastChartUiState(
            historyPoints = listOf(ChartPointUi(ts = 10L, value = 5.1)),
            futurePath = listOf(ChartPointUi(ts = 20L, value = Double.NaN))
        )

        assertThat(clinicalChartDomain(chart)).isNull()
    }
}
