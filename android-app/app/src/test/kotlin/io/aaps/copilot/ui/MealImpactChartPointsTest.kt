package io.aaps.copilot.ui

import io.aaps.copilot.ui.foundation.screens.mealImpactChartPoints
import io.aaps.copilot.ui.foundation.screens.ChartPointUi
import org.junit.Assert.*
import org.junit.Test

class MealImpactChartPointsTest {
    @Test fun primaryUiSubscriptionIncludesFoodSteps() {
        assertTrue(buildPrimaryTelemetryKeysForUi().contains("forecast_meal_steps"))
    }
    private val payload = """{"cycle":"cycle-1","steps":[0,1,1,1,1,1,1,0,0,0,0,0,0]}"""
    private val anchor = ChartPointUi(ts = 1000L, value = 6.2)
    @Test fun onlyMatchingAcceptedCycleCanDisplay() {
        assertTrue(mealImpactChartPoints(payload, "other", 1000L, anchor).isEmpty())
        assertTrue(mealImpactChartPoints("{}", "cycle-1", 1000L, anchor).isEmpty())
        assertTrue(mealImpactChartPoints(payload, "cycle-1", null, anchor).isEmpty())
        val points = mealImpactChartPoints(payload, "cycle-1", 2000L, anchor)
        assertEquals(anchor, points.first())
        assertEquals(7.2, points[1].value, 1e-9)
        assertEquals(12.2, points.last().value, 1e-9)
        assertEquals(1_801_000L, points.last().ts)
    }
    @Test fun requiresValidCurrentGlucoseAndDoesNotFabricateAnAnchor() {
        for (invalid in listOf(null, anchor.copy(value = Double.NaN), anchor.copy(value = 0.0))) {
            assertTrue(mealImpactChartPoints(payload, "cycle-1", 1000L, invalid).isEmpty())
        }
    }
    @Test fun noFoodProducesFlatLineAtCurrentGlucose() {
        val zero = """{"cycle":"cycle-1","steps":[0,0,0,0,0,0,0,0,0,0,0,0,0]}"""
        assertEquals(List(7) { anchor.value }, mealImpactChartPoints(zero, "cycle-1", 1000L, anchor).map { it.value })
    }
}
