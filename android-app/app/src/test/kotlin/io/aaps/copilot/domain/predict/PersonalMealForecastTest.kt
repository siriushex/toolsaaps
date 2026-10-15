package io.aaps.copilot.domain.predict

import io.aaps.copilot.domain.model.*
import io.aaps.copilot.domain.nutrition.*
import io.aaps.copilot.domain.profile.*
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class PersonalMealForecastTest {
    private val now = 1_700_000_000_000L
    private val mealTs = now - 45 * 60_000L
    private val glucose = (0..24).map { GlucosePoint(now - (24 - it) * 300_000L, 6.0 + it * 0.02, "sensor") }
    private val event = TherapyEvent(mealTs, "carbs", mapOf("carbs" to "30"),
        componentTrust = TherapyEventComponentTrust(12, "r1"))
    private val meal = MealPortionObservation("12", Instant.ofEpochMilli(mealTs), 30.0, MealAbsorptionProfile.MIXED,
        MealPortionProvenance.USER_ENTERED, MealPortion.MEDIUM, therapyRevision = "r1")
    private fun engine(uam: Boolean = false) = HybridPredictionEngine(enableEnhancedPredictionV3 = true, enableUam = uam).apply {
        setMealAbsorptionContext(MealAbsorptionContext(enabled = true,
            manual = MealAbsorptionSelection(MealAbsorptionProfile.MIXED, 120)))
    }
    private fun shape(duration: Int = 150, exponent: Double = 1.15) =
        PersonalAbsorptionShape(MealAbsorptionProfile.MIXED, duration, exponent)

    @Test fun identicalCurvePreservesFullBaselineAndSourceState() = runBlocking {
        val engine = engine()
        val baseline = engine.predict(glucose, listOf(event))
        val before = engine.diagnosticsSnapshot()!!
        val result = engine.forecastPersonalMeal(glucose, listOf(event), meal, shape(120, 1.0))!!
        assertEquals(baseline, result.forecasts)
        assertEquals(before.glucosePath, result.diagnostics.glucosePath)
        assertEquals(before.trendStep, result.diagnostics.trendStep)
        assertEquals(before.residualCarbsNowGrams, result.diagnostics.residualCarbsNowGrams, 0.0)
        assertFalse(result.diagnostics.kfHistoryRevised)
        assertSame(before, engine.diagnosticsSnapshot())
        assertFalse(result.clinicalInfluenceAllowed)
        assertThrows(UnsupportedOperationException::class.java) { (result.forecasts as MutableList).clear() }
        Unit
    }

    @Test fun curveRevisesHistoryResidualTrendAndFutureFoodTogether() = runBlocking {
        val engine = engine()
        for (last in 8..24) engine.predict(glucose.take(last + 1), listOf(event).filter { it.ts <= glucose[last].ts })
        val before = engine.diagnosticsSnapshot()!!
        assertFalse(before.arUsedFallback)
        val result = engine.forecastPersonalMeal(glucose, listOf(event), meal, shape())!!
        val after = result.diagnostics
        assertTrue(after.kfHistoryRevised)
        assertTrue(after.arUsedFallback)
        assertNotEquals(before.knownInputAnnouncedCarbStep1, after.knownInputAnnouncedCarbStep1)
        assertNotEquals(before.trendStep, after.trendStep)
        val csf = engine.projectMealTimingInsulin(glucose, listOf(event), 60).csfMmolPerGram
        assertEquals(30 * csf * (shape().cumulative(45.0) - shape().cumulative(40.0)),
            after.knownInputAnnouncedCarbStep1, 1e-9)
        assertEquals(30 * (1 - shape().cumulative(45.0)), after.residualCarbsNowGrams, 1e-9)
        assertEquals(30 * (1 - shape().cumulative(75.0)), after.residualCarbs30mGrams, 1e-9)
        assertEquals(30 * (1 - shape().cumulative(105.0)), after.residualCarbs60mGrams, 1e-9)
        for (step in 1..12) assertEquals(30 * csf *
            (shape().cumulative(45.0 + step * 5) - shape().cumulative(45.0 + (step - 1) * 5)),
            after.announcedCarbStep[step], 1e-9)
        assertSame(before, engine.diagnosticsSnapshot())
        assertEquals(result.forecasts, engine.forecastPersonalMeal(glucose, listOf(event), meal, shape())!!.forecasts)
        assertEquals("30", event.payload["carbs"])
    }

    @Test fun otherMealsInsulinAndDisplayGiAreNotReplaced() = runBlocking {
        val other = event.copy(ts = now - 20 * 60_000L, componentTrust = TherapyEventComponentTrust(13, "r2"))
        val insulin = TherapyEvent(now - 30 * 60_000L, "bolus", mapOf("units" to "1.0"))
        val engine = engine()
        val all = listOf(event, other, insulin)
        engine.predict(glucose, all)
        val before = engine.diagnosticsSnapshot()!!
        val after = engine.forecastPersonalMeal(glucose, all, meal, shape())!!.diagnostics
        assertEquals(before.insulinStep, after.insulinStep)
        assertEquals(before.knownInputInsulinStep1, after.knownInputInsulinStep1, 0.0)
        assertEquals(before.foodDisplayProjection, after.foodDisplayProjection)
        val isolated = engine.forecastPersonalMeal(glucose, listOf(event), meal, shape())!!.diagnostics
        val type = MealAbsorptionCurve(MealAbsorptionProfile.MIXED, 120)
        assertEquals(30 * (1 - type.absorbedFraction(20.0)),
            after.residualCarbsNowGrams - isolated.residualCarbsNowGrams, 1e-9)
    }

    @Test fun badIdentityMassProfileProvenanceAvailabilityAndDuplicatesReject() {
        val engine = engine()
        for (changed in listOf(meal.copy(canonicalId = "13"), meal.copy(therapyRevision = "r2"),
            meal.copy(timestamp = meal.timestamp.minusSeconds(1)), meal.copy(grams = 60.0),
            meal.copy(profile = MealAbsorptionProfile.FAST), meal.copy(deleted = true),
            meal.copy(provenance = MealPortionProvenance.SYNTHETIC_UAM),
            meal.copy(availableAt = Instant.ofEpochMilli(now + 1)))) {
            assertNull(engine.forecastPersonalMeal(glucose, listOf(event), changed, shape()))
        }
        assertNull(engine.forecastPersonalMeal(glucose, listOf(event, event), meal, shape()))
        assertNull(engine.forecastPersonalMeal(glucose, listOf(event.copy(componentTrust = TherapyEventComponentTrust.NONE)), meal, shape()))
        assertNull(engine.forecastPersonalMeal(glucose, listOf(event.copy(payload = mapOf("carbs" to "30", "carbType" to "rescue"))), meal, shape()))
        assertNull(HybridPredictionEngine().forecastPersonalMeal(glucose, listOf(event), meal, shape()))
        assertNull(engine.forecastPersonalMeal(glucose.map { it.copy(quality = DataQuality.SENSOR_ERROR) }, listOf(event), meal, shape()))
        assertNull(engine.diagnosticsSnapshot())
    }

    @Test fun unifiedUamReconcilesPersonalFoodInsteadOfAddingASecondFoodTerm() = runBlocking {
        val rising = glucose.takeLast(8).mapIndexed { i, point -> point.copy(valueMmol = 6.0 + i * 0.25) }
        val engine = engine(true).apply {
            setUamSensitivityRuntimeContext(io.aaps.copilot.testSensitivityRuntimeContext(consumer = SensitivityRuntimeConsumer.UAM))
            setUamRuntimeQualityContext(0.92, 0.88, 1.0, false)
        }
        val small = event.copy(payload = mapOf("carbs" to "2"))
        engine.predict(rising, listOf(small))
        val after = engine.forecastPersonalMeal(rising, listOf(small), meal.copy(grams = 2.0), shape())!!.diagnostics
        assertTrue(after.rawUnifiedUamStep.any { it > 0 })
        for (i in 1..12) assertEquals(after.insulinStep[i] + after.resolvedMealPressureStep[i],
            after.resolvedTherapyStep[i], 1e-9)
        assertEquals(2 * (1 - shape().cumulative(45.0)), after.residualCarbsNowGrams, 1e-9)
    }

    @Test fun effectiveInsulinKernelChangesWithDurationAndOnsetButNotMealCurve() {
        val engine = engine()
        val before = engine.mealInsulinKernelIdentity()
        assertEquals(before, engine.forkForMealSimulation().mealInsulinKernelIdentity())
        engine.setInsulinDurationHours(4.5)
        assertNotEquals(before, engine.mealInsulinKernelIdentity())
        val duration = engine.mealInsulinKernelIdentity()
        engine.setInsulinOnsetMinutes(10.0, 20.0)
        assertNotEquals(duration, engine.mealInsulinKernelIdentity())
    }
}
