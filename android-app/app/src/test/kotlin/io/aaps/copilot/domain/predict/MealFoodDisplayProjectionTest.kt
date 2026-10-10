package io.aaps.copilot.domain.predict

import io.aaps.copilot.domain.profile.MealGlycemicIndex
import org.junit.Assert.*
import org.junit.Test

class MealFoodDisplayProjectionTest {
    @Test fun equivalentProjectionsHaveValueEqualityAndStableHashCodes() {
        val first = project(90.0)
        val second = project(90.0)
        assertNotSame(first, second)
        assertEquals(first, second)
        assertEquals(first.hashCode(), second.hashCode())
        assertNotEquals(first, project(30.0))
    }

    private val now = 1_700_000_000_000L
    private fun meal(gi: Double? = null, already: Double = 0.0) = MealFoodDisplayInput(
        grams = 20.0,
        cumulativeAtOffset = { already + (1.0 - already) * (it / 120.0).coerceIn(0.0, 1.0) },
        glycemicIndex = gi?.let { MealGlycemicIndex.fromManualInput(it.toString()) }
    )
    private fun project(gi: Double? = null, already: Double = 0.0) =
        MealFoodDisplayProjection.build(now, 0.5, listOf(meal(gi, already)))!!

    @Test fun coversWholeTailAndPreservesMassAndCompletion() {
        for (gi in listOf(null, 0.0, 30.0, 60.0, 90.0, 200.0)) {
            val result = project(gi, already = 0.25)
            assertEquals(25, result.stepsMmol.size)
            assertEquals(0.0, result.stepsMmol.first(), 0.0)
            assertEquals(7.5, result.stepsMmol.sum(), 1e-12)
            assertEquals(0.0, result.remainingAtHorizonGrams, 0.0)
            assertTrue(result.completeByHorizon)
            assertTrue(result.stepsMmol.all { it.isFinite() && it >= 0.0 })
            assertEquals(if (gi == null || gi == 60.0) 0 else 1, result.giAdjustedMeals)
        }
    }

    @Test fun higherGiShiftsOnlyRemainingInfluenceEarlier() {
        for (already in listOf(0.0, 0.25, 0.95)) {
            val low = project(30.0, already)
            val baseline = project(null, already)
            val high = project(90.0, already)
            for (index in 1..23) {
                assertTrue(high.stepsMmol.take(index + 1).sum() > baseline.stepsMmol.take(index + 1).sum())
                assertTrue(baseline.stepsMmol.take(index + 1).sum() > low.stepsMmol.take(index + 1).sum())
            }
            assertEquals(low.stepsMmol.sum(), high.stepsMmol.sum(), 1e-12)
        }
    }

    @Test fun unknownAndNeutralGiKeepBaselineExactly() {
        assertEquals(project(null).stepsMmol, project(60.0).stepsMmol)
    }

    @Test fun overlappingMealsAreShapedIndependentlyNotByAnAverageGi() {
        val first = project(30.0)
        val second = project(90.0)
        val sum = MealFoodDisplayProjection.build(now, 0.5, listOf(meal(30.0), meal(90.0)))!!
        first.stepsMmol.indices.forEach { index ->
            assertEquals(first.stepsMmol[index] + second.stepsMmol[index], sum.stepsMmol[index], 1e-12)
        }
        assertEquals(2, sum.giAdjustedMeals)
    }

    @Test fun noFoodAndAlreadyAbsorbedFoodKeepVerifiedFlatThirtyMinutes() {
        for (meals in listOf(emptyList(), listOf(meal(90.0, 1.0)))) {
            val result = MealFoodDisplayProjection.build(now, 0.5, meals)!!
            assertEquals(List(7) { 0.0 }, result.stepsMmol)
            assertTrue(result.completeByHorizon)
            assertEquals(0, result.giAdjustedMeals)
        }
    }

    @Test fun nonzeroTailAtCapIsExplicitlyIncomplete() {
        val longMeal = MealFoodDisplayInput(20.0, { it / 1440.0 })
        val result = MealFoodDisplayProjection.build(now, 0.5, listOf(longMeal))!!
        assertEquals(145, result.stepsMmol.size)
        assertFalse(result.completeByHorizon)
        assertEquals(10.0, result.remainingAtHorizonGrams, 1e-12)
    }

    @Test fun rejectsInvalidFractionsClocksSensitivityAndBudgets() {
        for (input in listOf(
            MealFoodDisplayInput(Double.NaN, { 0.0 }),
            MealFoodDisplayInput(20.0, { Double.NaN }),
            MealFoodDisplayInput(20.0, { -0.1 }),
            MealFoodDisplayInput(20.0, { 1.1 }),
            MealFoodDisplayInput(20.0, { if (it == 5) 0.5 else 0.1 }),
            MealFoodDisplayInput(20.0, { error("display input failed") })
        )) assertNull(MealFoodDisplayProjection.build(now, 0.5, listOf(input)))
        assertNull(MealFoodDisplayProjection.build(0, 0.5, emptyList()))
        assertNull(MealFoodDisplayProjection.build(now, Double.NaN, emptyList()))
        assertNull(MealFoodDisplayProjection.build(now, 0.0, emptyList()))
        assertNull(MealFoodDisplayProjection.build(now, 0.5, List(5001) { meal() }))
        assertNull(MealFoodDisplayProjection.build(now, 0.5, listOf(meal()), 725))
    }

    @Test fun outputDoesNotRetainMutableInputOrExposeMutableSteps() {
        val inputs = mutableListOf(meal())
        val result = MealFoodDisplayProjection.build(now, 0.5, inputs)!!
        inputs.clear()
        assertEquals(10.0, result.stepsMmol.sum(), 1e-12)
        assertThrows(UnsupportedOperationException::class.java) { (result.stepsMmol as MutableList).clear() }
    }
}
