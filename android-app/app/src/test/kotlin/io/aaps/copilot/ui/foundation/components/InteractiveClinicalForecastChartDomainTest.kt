package io.aaps.copilot.ui.foundation.components

import com.google.common.truth.Truth.assertThat
import io.aaps.copilot.ui.foundation.screens.ChartPointUi
import io.aaps.copilot.ui.foundation.screens.ClinicalForecastChartUiState
import org.junit.Test

class InteractiveClinicalForecastChartDomainTest {
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
