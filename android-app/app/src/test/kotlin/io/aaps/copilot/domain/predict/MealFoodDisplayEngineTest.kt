package io.aaps.copilot.domain.predict

import io.aaps.copilot.domain.model.Forecast
import io.aaps.copilot.domain.model.GlucosePoint
import io.aaps.copilot.domain.model.TherapyEvent
import io.aaps.copilot.domain.model.TherapyEventComponentTrust
import io.aaps.copilot.domain.profile.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class MealFoodDisplayEngineTest {
    @Test fun frozenEngineCopyKeepsGiDisplayContextWithoutChangingLiveState() = runBlocking {
        val engine = HybridPredictionEngine(enableEnhancedPredictionV3 = true)
        engine.setMealGlycemicIndexContext(MealGlycemicIndexContext(mapOf("12" to
            MealGlycemicIndexOverride("r1", MealGlycemicIndex.fromManualInput("100")!!))))
        val expected = engine.predict(glucose, listOf(meal))
        val original = engine.diagnosticsSnapshot()!!
        val frozen = engine.forkForMealSimulation()
        engine.setMealGlycemicIndexContext(MealGlycemicIndexContext.EMPTY)
        assertEquals(expected, frozen.predict(glucose, listOf(meal)))
        assertEquals(1, frozen.diagnosticsSnapshot()!!.foodDisplayProjection!!.giAdjustedMeals)
        assertSame(original, engine.diagnosticsSnapshot())
    }

    private val now = 1_700_000_000_000L
    private val glucose = (0..20).map { GlucosePoint(now - (20 - it) * 300_000L, 6.0, "sensor") }
    private val meal = TherapyEvent(now - 15 * 60_000L, "carbs", mapOf("carbs" to "30"),
        componentTrust = TherapyEventComponentTrust(12L, "r1"))
    private val insulin = TherapyEvent(now - 10 * 60_000L, "bolus", mapOf("units" to "0.8"))

    private suspend fun snapshot(
        gi: String? = null, revision: String = "r1", events: List<TherapyEvent> = listOf(meal, insulin),
        context: MealAbsorptionContext = MealAbsorptionContext(enabled = true,
            manual = MealAbsorptionSelection(MealAbsorptionProfile.FAT_PROTEIN, 360))
    ): Pair<List<Forecast>, HybridPredictionEngine.V3Diagnostics> {
        val engine = HybridPredictionEngine(enableEnhancedPredictionV3 = true, enableUam = true)
        engine.setMealAbsorptionContext(context)
        engine.setMealGlycemicIndexContext(gi?.let { MealGlycemicIndexContext(mapOf("12" to
            MealGlycemicIndexOverride(revision, MealGlycemicIndex.fromManualInput(it)!!))) }
            ?: MealGlycemicIndexContext.EMPTY)
        val forecasts = engine.predict(glucose, events)
        return forecasts to engine.diagnosticsSnapshot()!!
    }

    @Test fun giChangesOnlyDisplayNotClinicalForecastPressureInsulinUamOrRemainder() = runBlocking {
        val baseline = snapshot()
        val low = snapshot("20")
        val high = snapshot("100")
        for (candidate in listOf(low, high)) {
            assertEquals(baseline.first, candidate.first)
            assertEquals(baseline.second.copy(foodDisplayProjection = null),
                candidate.second.copy(foodDisplayProjection = null))
        }
        val plain = baseline.second.foodDisplayProjection!!
        val slow = low.second.foodDisplayProjection!!
        val fast = high.second.foodDisplayProjection!!
        assertTrue(slow.stepsMmol.take(7).sum() < plain.stepsMmol.take(7).sum())
        assertTrue(fast.stepsMmol.take(7).sum() > plain.stepsMmol.take(7).sum())
        assertEquals(plain.stepsMmol.sum(), slow.stepsMmol.sum(), 1e-9)
        assertEquals(plain.stepsMmol.sum(), fast.stepsMmol.sum(), 1e-9)
        assertEquals(plain.stepsMmol.size, slow.stepsMmol.size)
        assertEquals(plain.stepsMmol.size, fast.stepsMmol.size)
        assertEquals(1, fast.giAdjustedMeals)
        assertTrue(plain.completeByHorizon)
        assertEquals(now, plain.predictionAtMs)
        assertTrue(plain.stepsMmol.size > 13)
    }

    @Test fun baselinePrefixMatchesLiveAnnouncedStepsForModernAndLegacyProfiles() = runBlocking {
        for (context in listOf(MealAbsorptionContext.DISABLED, MealAbsorptionContext(enabled = true,
            manual = MealAbsorptionSelection(MealAbsorptionProfile.FAT_PROTEIN, 360)))) {
            val diagnostics = snapshot(context = context).second
            diagnostics.announcedCarbStep.zip(diagnostics.foodDisplayProjection!!.stepsMmol)
                .forEach { (clinical, display) -> assertEquals(clinical, display, 1e-9) }
            assertEquals(diagnostics.foodDisplayProjection!!.stepsMmol,
                snapshot("60", context = context).second.foodDisplayProjection!!.stepsMmol)
        }
    }

    @Test fun wrongRevisionAndUntrustedReferencesCannotApplyGi() = runBlocking {
        val baseline = snapshot().second.foodDisplayProjection!!
        val wrongRevision = snapshot("100", revision = "r2").second.foodDisplayProjection!!
        assertEquals(baseline.stepsMmol, wrongRevision.stepsMmol)
        for (trust in listOf(TherapyEventComponentTrust.NONE,
            TherapyEventComponentTrust(12L, "r1", canonicalReferenceConflict = true),
            TherapyEventComponentTrust(12L, "r1", legacyValidityConflict = true))) {
            val events = listOf(meal.copy(componentTrust = trust), insulin)
            val unknown = snapshot(events = events).second.foodDisplayProjection!!
            val adjusted = snapshot("100", events = events).second.foodDisplayProjection!!
            assertEquals(unknown.stepsMmol, adjusted.stepsMmol)
            assertEquals(0, adjusted.giAdjustedMeals)
        }
    }

    @Test fun oversizedDisplayEvidenceDoesNotBlockClinicalForecast() = runBlocking {
        val result = snapshot(events = List(5_001) { meal })
        assertEquals(setOf(5, 30, 60), result.first.map { it.horizonMinutes }.toSet())
        assertNull(result.second.foodDisplayProjection)
    }

    @Test fun syntheticUamIsNotIncludedInFoodDisplay() = runBlocking {
        val baseline = snapshot()
        val synthetic = TherapyEvent(now, "carbs", mapOf("carbs" to "100", "synthetic" to "true"))
        val candidate = snapshot(events = listOf(meal, insulin, synthetic))
        assertEquals(baseline.second.foodDisplayProjection!!.stepsMmol,
            candidate.second.foodDisplayProjection!!.stepsMmol)
    }
}
