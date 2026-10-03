package io.aaps.copilot.domain.meal

import io.aaps.copilot.domain.profile.MealAbsorptionProfile
import org.junit.Assert.*
import org.junit.Test

class MealScenarioFoodProjectionTest {
    private val now = 1_700_000_000_000L
    private fun food(grams: Double, start: Long = now, horizon: Int = 360) =
        MealAbsorptionProjection.project(now, start, grams, MealAbsorptionProfile.FAST, 60, horizon)
    private fun component(id: String, meal: String?, grams: Double, uam: Boolean = false) =
        MealFoodComponent(id, meal, if (uam) MealFoodOrigin.UAM else MealFoodOrigin.ANNOUNCED, food(grams))

    @Test fun replacementRemovesAnnouncedAndExplicitlyLinkedUamOnly() {
        val baseline = listOf(component("announced", "selected", 30.0),
            component("inferred", "selected", 12.0, true), component("other", "other-meal", 10.0))
        val result = MealScenarioFoodProjection.replace(baseline, "selected", food(20.0))
        assertEquals(30.0, result.points.sumOf { it.absorbedStepGrams }, 1e-9)
        assertEquals(setOf("announced", "inferred"), result.replacedComponentIds.toSet())
        assertEquals(listOf("other"), result.retainedComponentIds)
        assertEquals(30.0, baseline.first().projection.points.last().cumulativeFutureGrams, 1e-9)
        assertTrue(result.completeByHorizon)
    }

    @Test fun absentMealRetainsOtherFoodAndDoesNotInventNegativeCarbs() {
        val result = MealScenarioFoodProjection.replace(listOf(component("old", "selected", 30.0),
            component("other", "other-meal", 10.0)), "selected", food(0.0))
        assertEquals(10.0, result.points.last().cumulativeFutureGrams, 1e-9)
        assertTrue(result.points.all { it.absorbedStepGrams >= 0.0 })
    }

    @Test fun unknownUamOrAmbiguousOtherMealCannotBeSilentlyAdded() {
        val cases = listOf(listOf(component("unknown", null, 10.0, true)),
            listOf(component("unknown", null, 10.0)),
            listOf(component("a", "other", 20.0), component("b", "other", 10.0, true)))
        for (baseline in cases) assertThrows(IllegalArgumentException::class.java) {
            MealScenarioFoodProjection.replace(baseline, "selected", food(30.0))
        }
    }

    @Test fun duplicateComponentIdentityAndMismatchedTimelineAreRejected() {
        val a = component("same", "selected", 20.0)
        for (baseline in listOf(listOf(a, a), listOf(a.copy(projection = food(20.0, horizon = 60))),
            listOf(a.copy(projection = MealAbsorptionProjection.project(now - 1, now, 20.0,
                MealAbsorptionProfile.FAST, 60, 360))))) {
            assertThrows(IllegalArgumentException::class.java) {
                MealScenarioFoodProjection.replace(baseline, "selected", food(30.0))
            }
        }
    }

    @Test fun lateEntrySeparatesPastAbsorptionFromFutureEffect() {
        val replacement = food(30.0, now - 30 * 60_000L)
        val result = MealScenarioFoodProjection.replace(emptyList(), "selected", replacement)
        assertEquals(replacement.alreadyAbsorbedGrams, result.alreadyAbsorbedGrams, 1e-9)
        assertEquals(30.0, result.alreadyAbsorbedGrams + result.points.last().cumulativeFutureGrams, 1e-9)
        assertTrue(result.points.last().cumulativeFutureGrams < 30.0)
    }

    @Test fun preservesUnfinishedOtherMealTailAndImmutableInputs() {
        val baseline = mutableListOf(component("other", "other-meal", 20.0).copy(
            projection = food(20.0, now + 350 * 60_000L)))
        val result = MealScenarioFoodProjection.replace(baseline, "selected", food(30.0))
        baseline.clear()
        assertFalse(result.completeByHorizon)
        assertTrue(result.points.last().remainingGrams > 0.0)
        assertEquals(listOf("other"), result.retainedComponentIds)
        assertThrows(UnsupportedOperationException::class.java) { (result.points as MutableList).clear() }
        assertThrows(UnsupportedOperationException::class.java) { (result.retainedComponentIds as MutableList).clear() }
    }

    @Test fun permutationDoesNotChangeNumericalProjection() {
        val baseline = listOf(component("b", "meal-b", 20.0), component("a", "meal-a", 10.0))
        val first = MealScenarioFoodProjection.replace(baseline, "selected", food(30.0))
        val second = MealScenarioFoodProjection.replace(baseline.reversed(), "selected", food(30.0))
        assertEquals(first.points, second.points)
        assertEquals(first.retainedComponentIds, second.retainedComponentIds)
    }

    @Test fun workBudgetRejectsBeforeProcessingOversizedBaseline() {
        val repeated = component("repeated", "selected", 10.0)
        val error = assertThrows(IllegalArgumentException::class.java) {
            MealScenarioFoodProjection.replace(List(5_001) { repeated }, "selected", food(20.0))
        }
        assertEquals("Meal food projection budget exceeded", error.message)
    }
}
