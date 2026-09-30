package io.aaps.copilot.domain.predict

import io.aaps.copilot.domain.model.*
import io.aaps.copilot.domain.profile.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class MealAnnouncedFoodProjectionTest {
    private val now = 1_700_000_000_000L
    private val glucose = (0..20).map { GlucosePoint(now - (20 - it) * 300_000L, 6.0, "sensor") }
    private fun meal(id: Long = 12) = TherapyEvent(now - 15 * 60_000L, "carbs", mapOf("carbs" to "30"),
        componentTrust = TherapyEventComponentTrust(canonicalCarbId = id, canonicalCarbRevision = "r1"))
    private fun engine() = HybridPredictionEngine(enableEnhancedPredictionV3 = true, enableUam = false)

    @Test fun modernAndLegacyCurvesMatchLiveAnnouncedComponent() = runBlocking {
        for (context in listOf(MealAbsorptionContext.DISABLED, MealAbsorptionContext(enabled = true,
            manual = MealAbsorptionSelection(MealAbsorptionProfile.FAT_PROTEIN, 360)))) {
            val engine = engine().apply { setMealAbsorptionContext(context) }
            val events = listOf(meal())
            engine.predict(glucose, events)
            val diagnostics = engine.diagnosticsSnapshot()!!
            val food = engine.projectMealTimingAnnouncedFood(glucose, events, 360).single()
            val insulin = engine.projectMealTimingInsulin(glucose, events, 360)
            diagnostics.announcedCarbStep.zip(food.projection.points.take(13)).forEach { (expected, p) ->
                assertEquals(expected, p.absorbedStepGrams * insulin.csfMmolPerGram, 1e-9)
            }
            assertEquals("12", food.canonicalMealId)
            assertSame(diagnostics, engine.diagnosticsSnapshot())
        }
    }

    @Test fun canonicalRevisionIsRequiredAndPayloadCannotForgeIt() {
        val variants = listOf(meal().copy(componentTrust = TherapyEventComponentTrust.NONE,
                payload = meal().payload + mapOf("canonicalCarbId" to "12", "canonicalCarbRevision" to "r1")),
            meal().copy(componentTrust = TherapyEventComponentTrust(12, "r1", canonicalReferenceConflict = true)),
            meal().copy(componentTrust = TherapyEventComponentTrust(12, "")),
            meal().copy(componentTrust = TherapyEventComponentTrust(12, "r1", legacyValidityConflict = true)))
        for (event in variants) assertThrows(IllegalArgumentException::class.java) {
            engine().projectMealTimingAnnouncedFood(glucose, listOf(event), 360)
        }
    }

    @Test fun duplicateCanonicalRecordsAreNotAddedTwice() {
        assertThrows(IllegalArgumentException::class.java) {
            engine().projectMealTimingAnnouncedFood(glucose, listOf(meal(), meal().copy(ts = now)), 360)
        }
    }

    @Test fun perMealRevisionOverrideIsReusedAndResultIsImmutable() {
        val engine = engine().apply { setMealAbsorptionContext(MealAbsorptionContext(enabled = true,
            perMealOverrides = mapOf("12" to MealAbsorptionSelection(MealAbsorptionProfile.FAT_PROTEIN, 360, "r1")))) }
        val result = engine.projectMealTimingAnnouncedFood(glucose, listOf(meal()), 60)
        assertFalse(result.single().projection.completeByHorizon)
        assertThrows(UnsupportedOperationException::class.java) { (result as MutableList).clear() }
    }

    @Test fun futureEvidenceAndOversizedHorizonAreRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            engine().projectMealTimingAnnouncedFood(glucose, listOf(meal().copy(ts = now + 1)), 60)
        }
        assertThrows(IllegalArgumentException::class.java) {
            engine().projectMealTimingAnnouncedFood(glucose, listOf(meal()), 725)
        }
    }

    @Test fun syntheticUamIsNotReintroducedAsAnnouncedFood() {
        val synthetic = TherapyEvent(now, "carbs", mapOf("carbs" to "10", "synthetic" to "true"))
        val engine = engine()
        val baseline = engine.projectMealTimingAnnouncedFood(glucose, listOf(meal()), 360)
        val withUam = engine.projectMealTimingAnnouncedFood(glucose, listOf(meal(), synthetic), 360)
        assertEquals(1, withUam.size)
        assertEquals(baseline.single().projection.points, withUam.single().projection.points)
    }

    @Test fun evidenceBudgetCannotSilentlyTruncateHistory() {
        assertThrows(IllegalArgumentException::class.java) {
            engine().projectMealTimingAnnouncedFood(glucose, List(5_001) { meal() }, 360)
        }
    }
}
