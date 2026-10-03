package io.aaps.copilot.domain.meal

import io.aaps.copilot.domain.model.GlucosePoint
import io.aaps.copilot.domain.model.TherapyEvent
import io.aaps.copilot.domain.predict.HybridPredictionEngine
import io.aaps.copilot.domain.predict.SensitivityMetricOverride
import io.aaps.copilot.domain.profile.MealAbsorptionProfile
import org.junit.Assert.*
import org.junit.Test

class MealComponentProjectionTest {
    private val now = 1_700_000_000_000L
    private fun insulin(horizon: Int) = HybridPredictionEngine(enableEnhancedPredictionV3 = true).apply {
        setSensitivityOverrides(
            SensitivityMetricOverride(3.0, 1.0, 0.0, 1.0, "test", true),
            SensitivityMetricOverride(10.0, 1.0, 0.0, 1.0, "test", true))
    }.projectMealTimingInsulin(
        (0..20).map { GlucosePoint(now - (20 - it) * 300_000L, 6.0, "sensor") },
        listOf(TherapyEvent(now - 30 * 60_000L, "bolus", mapOf("units" to "2.0"))), horizon)

    @Test fun overlayUsesSameSensitivityAndPreservesBothComponents() {
        val insulin = insulin(360)
        val food = MealAbsorptionProjection.project(now, now + 15 * 60_000L, 30.0,
            MealAbsorptionProfile.FAST, 45, 360)
        val result = MealComponentProjection.combine(food, insulin)
        assertEquals(9.0, result.points.sumOf { it.foodStepMmol }, 1e-9)
        assertEquals(insulin.stepEffectsMmol, result.points.map { it.insulinStepMmol })
        assertEquals(9.0 + insulin.stepEffectsMmol.sum(), result.points.last().cumulativeModeledEffectMmol, 1e-9)
        assertTrue(result.points.filter { it.offsetMinutes <= 15 }.all { it.foodStepMmol == 0.0 })
        assertTrue(result.modeledTailsComplete)
    }

    @Test fun sixtyMinuteOverlayReportsUnfinishedTails() {
        val food = MealAbsorptionProjection.project(now, now, 30.0, MealAbsorptionProfile.MIXED, 180, 60)
        assertFalse(MealComponentProjection.combine(food, insulin(60)).modeledTailsComplete)
    }

    @Test fun noFoodDoesNotEraseInsulinRiskComponent() {
        val food = MealAbsorptionProjection.project(now, now, 0.0, MealAbsorptionProfile.FAST, 45, 60)
        val result = MealComponentProjection.combine(food, insulin(60))
        assertTrue(result.points.all { it.foodStepMmol == 0.0 })
        assertTrue(result.points.last().cumulativeModeledEffectMmol < 0.0)
    }

    @Test fun reconciledScenarioDoesNotDoubleCountFoodOrChangeInsulin() {
        val replacement = MealAbsorptionProjection.project(now, now, 20.0,
            MealAbsorptionProfile.FAST, 60, 360)
        val other = MealAbsorptionProjection.project(now, now, 10.0,
            MealAbsorptionProfile.FAST, 60, 360)
        val baseline = listOf(MealFoodComponent("declared", "selected", MealFoodOrigin.ANNOUNCED, other),
            MealFoodComponent("inferred", "selected", MealFoodOrigin.UAM, other),
            MealFoodComponent("other", "previous", MealFoodOrigin.ANNOUNCED, other))
        val scenario = MealScenarioFoodProjection.replace(baseline, "selected", replacement)
        val insulin = insulin(360)
        val result = MealComponentProjection.combine(scenario, insulin)
        assertEquals(30.0 * insulin.csfMmolPerGram, result.points.sumOf { it.foodStepMmol }, 1e-9)
        assertEquals(insulin.stepEffectsMmol, result.points.map { it.insulinStepMmol })
    }

    @Test fun differentAnchorsOrHorizonsCannotBeCombined() {
        for (food in listOf(
            MealAbsorptionProjection.project(now - 300_000, now, 30.0, MealAbsorptionProfile.FAST, 45, 60),
            MealAbsorptionProjection.project(now, now, 30.0, MealAbsorptionProfile.FAST, 45, 120)
        )) assertThrows(IllegalArgumentException::class.java) { MealComponentProjection.combine(food, insulin(60)) }
    }

    @Test fun timelineIsImmutable() {
        val food = MealAbsorptionProjection.project(now, now, 30.0, MealAbsorptionProfile.FAST, 45, 60)
        val result = MealComponentProjection.combine(food, insulin(60))
        assertThrows(UnsupportedOperationException::class.java) { (result.points as MutableList).clear() }
    }
}
