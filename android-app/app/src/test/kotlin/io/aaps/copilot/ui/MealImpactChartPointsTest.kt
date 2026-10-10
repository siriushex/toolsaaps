package io.aaps.copilot.ui

import io.aaps.copilot.ui.foundation.screens.mealImpactChartPoints
import io.aaps.copilot.ui.foundation.screens.mealImpactChartData
import io.aaps.copilot.ui.foundation.screens.ChartPointUi
import org.junit.Assert.*
import org.junit.Test

class MealImpactChartPointsTest {
    private fun versioned(steps: List<Double>, complete: Boolean = true) =
        """{"schemaVersion":2,"cycle":"cycle-1","predictionAtMs":1000,"complete":$complete,"giAdjustedMeals":1,"modelVersion":"food_gi_shape_v1","steps":$steps}"""

    @Test fun completionAndGiFlagsAreNotInventedForLegacyData() {
        val legacy = mealImpactChartData(payload, "cycle-1", 2000L, anchor)!!
        assertNull(legacy.complete)
        assertFalse(legacy.giAdjusted)
        val complete = mealImpactChartData(versioned(listOf(0.0, 0.1)), "cycle-1", 2000L, anchor)!!
        assertEquals(true, complete.complete)
        assertTrue(complete.giAdjusted)
        val incomplete = mealImpactChartData(versioned(listOf(0.0, 0.1), false)
            .replace("\"giAdjustedMeals\":1", "\"giAdjustedMeals\":0"), "cycle-1", 2000L, anchor)!!
        assertEquals(false, incomplete.complete)
        assertFalse(incomplete.giAdjusted)
    }

    @Test fun anAcceptedTailCannotBeReanchoredToAnotherGlucoseTimestamp() {
        assertTrue(mealImpactChartPoints(versioned(listOf(0.0, 0.1)), "cycle-1", 2000L,
            anchor.copy(ts = 1500L)).isEmpty())
    }

    @Test fun maximumTailIsVisibleButOversizedTailIsUnavailable() {
        val steps = List(145) { if (it == 0) 0.0 else 0.01 }
        val points = mealImpactChartPoints(versioned(steps, false), "cycle-1", 2000L, anchor)
        assertEquals(145, points.size)
        assertEquals(anchor.ts + 720 * 60_000L, points.last().ts)
        assertTrue(mealImpactChartPoints(versioned(steps + 0.01), "cycle-1", 2000L, anchor).isEmpty())
    }

    @Test fun invalidVersionClockFlagsModelAndStepsAreUnavailable() {
        val valid = versioned(listOf(0.0, 0.1))
        for (invalid in listOf(valid.replace("\"schemaVersion\":2", "\"schemaVersion\":3"),
            valid.replace("\"predictionAtMs\":1000", "\"predictionAtMs\":0"),
            valid.replace("\"predictionAtMs\":1000", "\"predictionAtMs\":1001"),
            valid.replace("\"complete\":true", "\"complete\":\"true\""),
            valid.replace("food_gi_shape_v1", "unknown_shape"),
            valid.replace("[0.0, 0.1]", "[0,-1]"), valid.replace("[0.0, 0.1]", "[0,NaN]"),
            valid.replace("[0.0, 0.1]", "[0,\"0.1\"]"), valid.replace("[0.0, 0.1]", "[1,0.1]"),
            valid.replace("\"giAdjustedMeals\":1", "\"giAdjustedMeals\":-1"))) {
            assertTrue(invalid, mealImpactChartPoints(invalid, "cycle-1", 2000L, anchor).isEmpty())
        }
    }

    @Test fun timestampOverflowAndOversizedPayloadCannotFabricatePoints() {
        val hugeClock = versioned(listOf(0.0, 0.1)).replace("\"predictionAtMs\":1000",
            "\"predictionAtMs\":${Long.MAX_VALUE}")
        assertTrue(mealImpactChartPoints(hugeClock, "cycle-1", Long.MAX_VALUE,
            anchor.copy(ts = Long.MAX_VALUE)).isEmpty())
        assertTrue(mealImpactChartPoints(" ".repeat(16_385) + payload, "cycle-1", 2000L, anchor).isEmpty())
    }

    @Test fun fullVersionedFoodTailIsNotCutAtThirtyMinutes() {
        val steps = List(25) { if (it == 0) 0.0 else 0.1 }
        val json = """{"schemaVersion":2,"cycle":"cycle-1","predictionAtMs":1000,"complete":true,"giAdjustedMeals":0,"modelVersion":"food_gi_shape_v1","steps":${steps}}"""
        val points = mealImpactChartPoints(json, "cycle-1", 2000L, anchor)
        assertEquals(25, points.size)
        assertEquals(anchor.ts + 120 * 60_000L, points.last().ts)
        assertEquals(anchor.value + 2.4, points.last().value, 1e-9)
    }

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
