package io.aaps.copilot.ui.foundation.screens

import io.aaps.copilot.ui.foundation.components.clinicalChartDomain
import org.junit.Assert.*
import org.junit.Test

class AcceptedFoodDisplayTest {
    private val origin = 1_800_000_000_000L
    private val scope = FoodDisplayScope(2L, "EVIDENCE", "EVIDENCE", "session-a")
    private val points = List(145) { ChartPointUi(origin + it * 300_000L, 6.0 + it * 0.01) }
    private val display = AcceptedFoodDisplaySnapshot(scope, "meal-cycle", origin, points, true, true)
    private fun state() = OverviewUiState(ScreenLoadState.READY, false, glucose = 6.0, currentCobGrams = 20.0,
        foodDisplayScope = scope, acceptedFoodDisplay = display, foodDisplayNowTs = origin,
        chart = ClinicalForecastChartUiState(historyPoints = listOf(points.first())))

    @Test fun acceptedOriginSurvivesNewCgmAndCobWithoutRescalingOrMoving() {
        val current = state().copy(acceptedFoodDisplay = null, foodDisplayNowTs = origin + 300_000L,
            currentCobGrams = 15.0, foodCobTimestamp = origin + 300_000L,
            chart = state().chart.copy(historyPoints = listOf(points.first(), ChartPointUi(origin + 300_000L, 6.8))))
        val result = retainAcceptedFoodDisplay(display, current)
        assertEquals(points, result.chart.mealImpactPoints)
        assertEquals(FoodDisplayStatus.UPDATING, result.foodDisplayStatus)
        assertFalse(result.forecastsCurrent)
        assertTrue(result.horizons.isEmpty())
        assertEquals(points.last().ts, clinicalChartDomain(result.chart)!!.endTs)
    }

    @Test fun acceptedReplacementIsAtomicAndOlderPublicationCannotRollItBack() {
        val replacement = display.copy(cycle = "new-cycle", generatedAt = origin + 300_000L,
            points = points.take(20))
        assertEquals(replacement, retainAcceptedFoodDisplay(display, state().copy(acceptedFoodDisplay = replacement)).acceptedFoodDisplay)
        assertEquals(replacement, retainAcceptedFoodDisplay(replacement, state()).acceptedFoodDisplay)
    }

    @Test fun revisionSourcesCalibrationScopeResetOrMissingOriginInvalidateRetainedTail() {
        val changes = listOf(state().copy(foodDisplayScope = scope.copy(settingsRevision = 3)),
            state().copy(foodDisplayScope = scope.copy(isfSource = "AAPS")),
            state().copy(foodDisplayScope = scope.copy(crSource = "AAPS")),
            state().copy(foodDisplayScope = scope.copy(calibrationIdentity = "session-b")),
            state().copy(foodDisplayScope = null), state().copy(foodDisplayScopeReset = true),
            state().copy(chart = state().chart.copy(historyPoints = listOf(ChartPointUi(origin + 1, 6.0)))))
        changes.forEach { changed ->
            val result = retainAcceptedFoodDisplay(display, changed.copy(acceptedFoodDisplay = null))
            assertNull(result.acceptedFoodDisplay)
            assertTrue(result.chart.mealImpactPoints.isEmpty())
            assertEquals(FoodDisplayStatus.UNAVAILABLE, result.foodDisplayStatus)
        }
    }

    @Test fun positiveCobAfterEndpointIsUnknownAndNeverFabricatesAContinuation() {
        val result = retainAcceptedFoodDisplay(display, state().copy(acceptedFoodDisplay = null,
            foodDisplayNowTs = points.last().ts + 1))
        assertEquals(points, result.chart.mealImpactPoints)
        assertEquals(FoodDisplayStatus.OUTDATED, result.foodDisplayStatus)
    }

    @Test fun zeroCobAloneDoesNotEraseActiveFoodInfluence() {
        assertEquals(display, retainAcceptedFoodDisplay(display, state().copy(currentCobGrams = 0.0)).acceptedFoodDisplay)
        val flat = display.copy(points = points.map { it.copy(value = 6.0) })
        val result = retainAcceptedFoodDisplay(null, state().copy(currentCobGrams = 0.0, acceptedFoodDisplay = flat))
        assertNull(result.acceptedFoodDisplay)
        assertEquals(FoodDisplayStatus.INACTIVE, result.foodDisplayStatus)
    }

    @Test fun onlyFoodSeriesStillHasAFullTwelveHourDomain() {
        val domain = clinicalChartDomain(ClinicalForecastChartUiState(mealImpactPoints = points))!!
        assertEquals(origin, domain.startTs)
        assertEquals(origin + 720 * 60_000L, domain.endTs)
    }

    @Test fun originalPointMustBeUniqueAndPayloadClockRemainsStrict() {
        val payload = """{"schemaVersion":2,"predictionAtMs":$origin}"""
        val newer = ChartPointUi(origin + 300_000L, 6.8)
        assertEquals(points.first(), acceptedMealImpactOrigin(payload, listOf(points.first(), newer)))
        assertNull(acceptedMealImpactOrigin(payload, listOf(newer)))
        assertNull(acceptedMealImpactOrigin(payload, listOf(points.first(), points.first())))
        assertNull(acceptedMealImpactOrigin(payload.replace("$origin", "${origin}.1"), points))
        assertNull(acceptedMealImpactOrigin(" ".repeat(16385), points))
    }
}
