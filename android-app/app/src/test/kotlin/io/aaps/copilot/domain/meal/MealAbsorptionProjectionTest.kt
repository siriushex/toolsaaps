package io.aaps.copilot.domain.meal

import io.aaps.copilot.domain.profile.MealAbsorptionProfile
import org.junit.Assert.*
import org.junit.Test

class MealAbsorptionProjectionTest {
    private val now = 1_700_000_000_000L

    @Test fun futureFoodStartsAtItsHypotheticalTimeNotAtDeclaration() {
        val result = MealAbsorptionProjection.project(now, now + 15 * 60_000L, 30.0,
            MealAbsorptionProfile.FAST, 45, 120)
        assertTrue(result.points.filter { it.offsetMinutes <= 15 }.all { it.absorbedStepGrams == 0.0 })
        assertTrue(result.points.first { it.offsetMinutes == 20 }.absorbedStepGrams > 0)
        assertEquals(30.0, result.points.sumOf { it.absorbedStepGrams }, 1e-9)
        assertEquals(0.0, result.remainingAtHorizonGrams, 1e-9)
        assertTrue(result.completeByHorizon)
    }

    @Test fun previouslyAbsorbedCarbsAreNotCountedAgain() {
        val result = MealAbsorptionProjection.project(now, now - 30 * 60_000L, 40.0,
            MealAbsorptionProfile.FAST, 60, 120)
        assertEquals(30.0, result.alreadyAbsorbedGrams, 1e-9)
        assertEquals(10.0, result.points.sumOf { it.absorbedStepGrams }, 1e-9)
        assertEquals(0.0, result.points.first().cumulativeFutureGrams, 0.0)
    }

    @Test fun slowMealTailIsExplicitlyIncompleteAtSixtyMinutes() {
        val short = MealAbsorptionProjection.project(now, now, 60.0,
            MealAbsorptionProfile.FAT_PROTEIN, 360, 60)
        assertFalse(short.completeByHorizon)
        assertTrue(short.remainingAtHorizonGrams > 50.0)
        val full = MealAbsorptionProjection.project(now, now, 60.0,
            MealAbsorptionProfile.FAT_PROTEIN, 360, 360)
        assertTrue(full.completeByHorizon)
        assertEquals(60.0, full.points.sumOf { it.absorbedStepGrams }, 1e-9)
    }

    @Test fun reactionDelayIsPartOfStartAndDoesNotTruncateTail() {
        val delayedStart = now + 50 * 60_000L
        val short = MealAbsorptionProjection.project(now, delayedStart, 60.0,
            MealAbsorptionProfile.FAT_PROTEIN, 360, 360)
        assertFalse(short.completeByHorizon)
        val full = MealAbsorptionProjection.project(now, delayedStart, 60.0,
            MealAbsorptionProfile.FAT_PROTEIN, 360, 410)
        assertTrue(full.completeByHorizon)
    }

    @Test fun zeroAndCompletedMealsHaveNoFutureContribution() {
        val zero = MealAbsorptionProjection.project(now, now, 0.0, MealAbsorptionProfile.MIXED, 120, 60)
        val completed = MealAbsorptionProjection.project(now, now - 180 * 60_000L, 40.0,
            MealAbsorptionProfile.MIXED, 120, 60)
        for (result in listOf(zero, completed)) {
            assertTrue(result.completeByHorizon)
            assertTrue(result.points.all { it.absorbedStepGrams == 0.0 })
        }
    }

    @Test fun invalidDurationOrNumericInputCannotSilentlySelectDefaultProfile() {
        assertThrows(IllegalArgumentException::class.java) {
            MealAbsorptionProjection.project(now, now, 40.0, MealAbsorptionProfile.FAST, 360, 60)
        }
        for (grams in listOf(-1.0, Double.NaN, Double.POSITIVE_INFINITY)) {
            assertThrows(IllegalArgumentException::class.java) {
                MealAbsorptionProjection.project(now, now, grams, MealAbsorptionProfile.FAST, 60, 60)
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            MealAbsorptionProjection.project(now, now, 40.0, MealAbsorptionProfile.FAST, 60, 721)
        }
    }

    @Test fun everyStepPreservesCarbMassAndOutputIsImmutable() {
        val result = MealAbsorptionProjection.project(now, now - 10 * 60_000L, 80.0,
            MealAbsorptionProfile.MIXED, 180, 240)
        for (point in result.points) {
            assertEquals(80.0, result.alreadyAbsorbedGrams + point.cumulativeFutureGrams + point.remainingGrams, 1e-9)
            assertTrue(point.absorbedStepGrams >= 0.0)
        }
        assertThrows(UnsupportedOperationException::class.java) { (result.points as MutableList).clear() }
    }
}
