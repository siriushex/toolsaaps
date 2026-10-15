package io.aaps.copilot.ui.foundation.components

import io.aaps.copilot.ui.foundation.screens.ChartPointUi
import io.aaps.copilot.ui.foundation.screens.ClinicalForecastChartUiState
import io.aaps.copilot.ui.foundation.screens.HorizonPredictionUi
import io.aaps.copilot.ui.foundation.screens.OverviewUiState
import io.aaps.copilot.ui.foundation.screens.ScreenLoadState
import io.aaps.copilot.ui.foundation.screens.clarityForecastPoints
import org.junit.Assert.*
import org.junit.Test

class ClinicalForecastMarkersTest {
    private val markers = listOf(5, 30, 60).map { ClinicalForecastMarker(it, ChartPointUi(600L + it, 7.2), "forecast") }

    @Test fun clusteredPointsRetainDisjointFortyEightDpHitAreas() {
        val positions = forecastMarkerPlacements(markers, 288f, 190f, 0, 700, ClinicalChartBounds(2.0, 12.0))
        assertEquals(3, positions.size)
        positions.zipWithNext().forEach { (a, b) -> assertTrue(b.centerX - a.centerX >= 48f) }
        positions.forEach { assertTrue(it.centerX in 24f..264f); assertTrue(it.top in 44f..122f) }
    }

    @Test fun invalidAndOffViewportPointsCannotCreateHitTargets() {
        assertTrue(forecastMarkerPlacements(markers, 288f, 190f, 0, 600, ClinicalChartBounds(2.0, 12.0)).isEmpty())
        val invalid = markers.map { it.copy(point = it.point.copy(value = Double.NaN)) }
        assertTrue(forecastMarkerPlacements(invalid, 288f, 190f, 0, 700, ClinicalChartBounds(2.0, 12.0)).isEmpty())
        assertTrue(forecastMarkerPlacements(markers, 120f, 190f, 0, 700, ClinicalChartBounds(2.0, 12.0)).isEmpty())
    }

    @Test fun staleAndMissingForecastsNeverCreateMarkers() {
        val state = OverviewUiState(ScreenLoadState.READY, false, forecastsCurrent = true,
            chart = ClinicalForecastChartUiState(historyPoints = listOf(ChartPointUi(1000, 7.0)),
                futurePath = listOf(ChartPointUi(1000 + 30 * 60_000L, 7.5))),
            horizons = listOf(HorizonPredictionUi(30, 7.5, 6.0, 9.0)))
        assertEquals(1, clarityForecastPoints(state).size)
        assertTrue(clarityForecastPoints(state.copy(isStale = true)).isEmpty())
        assertTrue(clarityForecastPoints(state.copy(forecastsCurrent = false)).isEmpty())
        assertTrue(clarityForecastPoints(state.copy(chart = ClinicalForecastChartUiState())).isEmpty())
        assertTrue(clarityForecastPoints(state.copy(chart = state.chart.copy(futurePath = emptyList()))).isEmpty())
        assertTrue(clarityForecastPoints(state.copy(chart = state.chart.copy(futurePath = listOf(ChartPointUi(1000 + 30 * 60_000L, 8.0))))).isEmpty())
    }
}
