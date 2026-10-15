package io.aaps.copilot.domain.predict

import org.junit.Assert.*
import org.junit.Test

class MealRollingImpactTest {
    @Test fun accumulatesFromZeroToThirtyMinutes() {
        val points = MealRollingImpact.nextThirtyMinutes(listOf(0.0) + (1..12).map(Int::toDouble))
        assertEquals(7, points.size)
        assertEquals(listOf(0.0, 1.0, 3.0, 6.0, 10.0, 15.0, 21.0), points)
    }
    @Test fun missingOrInvalidStepsAreUnavailableNotZero() {
        assertTrue(MealRollingImpact.nextThirtyMinutes(emptyList()).isEmpty())
        assertTrue(MealRollingImpact.nextThirtyMinutes(List(13) { Double.NaN }).isEmpty())
        assertTrue(MealRollingImpact.nextThirtyMinutes(List(13) { -1.0 }).isEmpty())
        assertEquals(List(7) { 0.0 }, MealRollingImpact.nextThirtyMinutes(List(13) { 0.0 }))
    }
    @Test fun stopsRisingWhenAbsorptionStopsWithoutSubtractingPastContribution() {
        val steps = listOf(0.0, 0.2, 0.3) + List(10) { 0.0 }
        assertEquals(listOf(0.0, 0.2, 0.5, 0.5, 0.5, 0.5, 0.5), MealRollingImpact.nextThirtyMinutes(steps))
    }
    @Test fun rejectsOverflow() {
        assertTrue(MealRollingImpact.nextThirtyMinutes(List(13) { Double.MAX_VALUE }).isEmpty())
    }
}
