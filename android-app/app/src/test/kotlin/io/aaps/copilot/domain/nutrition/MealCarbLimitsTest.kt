package io.aaps.copilot.domain.nutrition

import io.aaps.copilot.domain.model.TherapyEvent
import io.aaps.copilot.domain.model.TherapyEventComponentTrust
import org.junit.Assert.*
import org.junit.Test

class MealCarbLimitsTest {
    private val now = 1_700_000_000_000L
    private fun meal() = TherapyEvent(now, "carbs", mapOf("carbs" to "80",
        "aapsCarbAmount" to "80", "aapsCarbIsValid" to "true",
        "aapsCarbClassification" to "AAPS_REAL", "aapsCarbSynthetic" to "false",
        "aapsCarbSuperseded" to "false"),
        componentTrust = TherapyEventComponentTrust(12, "revision-12"))

    @Test fun manualMealMaximumDoesNotRaiseAutomaticOrOtherManualLimits() {
        assertEquals(80.0, MealCarbLimits.submissionMaximum("manual:meal:entry", 20.0), 0.0)
        assertEquals(20.0, MealCarbLimits.submissionMaximum("automatic:entry", 20.0), 0.0)
        assertEquals(20.0, MealCarbLimits.submissionMaximum("manual:other", 20.0), 0.0)
        assertEquals(60.0, MealCarbLimits.submissionMaximum("automatic:entry", 80.0), 0.0)
    }

    @Test fun trustedRealFoodUsesCanonicalGramsAndSeparateMaximum() {
        assertEquals(80.0, MealCarbLimits.announcedGrams(meal(), 20.0)!!, 0.0)
        assertEquals(80.0, MealCarbLimits.announcedGrams(meal().copy(
            payload = meal().payload + ("carbs" to "10")), 20.0)!!, 0.0)
        assertEquals(80.0, MealCarbLimits.effectiveCobMaximum(listOf(meal()), now, 180, 20.0), 0.0)
    }

    @Test fun missingOrConflictingCanonicalTrustCannotIncreaseComputationLimit() {
        for (trust in listOf(TherapyEventComponentTrust.NONE,
            TherapyEventComponentTrust(12, ""),
            TherapyEventComponentTrust(12, "revision-12", canonicalReferenceConflict = true))) {
            val event = meal().copy(componentTrust = trust)
            assertEquals(20.0, MealCarbLimits.announcedGrams(event, 20.0)!!, 0.0)
            assertEquals(20.0, MealCarbLimits.effectiveCobMaximum(listOf(event), now, 180, 20.0), 0.0)
        }
    }

    @Test fun syntheticInvalidAndSupersededFoodCannotRaiseCobOrBecomeAnnounced() {
        for (change in listOf("aapsCarbSynthetic" to "true", "aapsCarbIsValid" to "false",
            "aapsCarbSuperseded" to "true", "aapsCarbClassification" to "AAPS_CORRECTION",
            "aapsCarbAmount" to "NaN")) {
            val event = meal().copy(payload = meal().payload + change)
            assertNull(MealCarbLimits.announcedGrams(event, 20.0))
            assertEquals(20.0, MealCarbLimits.effectiveCobMaximum(listOf(event), now, 180, 20.0), 0.0)
        }
    }

    @Test fun futureAndExpiredRealFoodCannotRaiseCurrentCobMaximum() {
        for (ts in listOf(now + 1, now - 181 * 60_000L)) {
            assertEquals(20.0, MealCarbLimits.effectiveCobMaximum(
                listOf(meal().copy(ts = ts)), now, 180, 20.0), 0.0)
        }
        assertEquals(80.0, MealCarbLimits.effectiveCobMaximum(
            listOf(meal().copy(ts = now - 180 * 60_000L)), now, 180, 20.0), 0.0)
    }

    @Test fun untrustedLegacyMealRetainsExistingCap() {
        val event = TherapyEvent(now, "carbs", mapOf("carbs" to "80"))
        assertEquals(20.0, MealCarbLimits.announcedGrams(event, 20.0)!!, 0.0)
        assertEquals(20.0, MealCarbLimits.effectiveCobMaximum(listOf(event), now, 180, 20.0), 0.0)
    }
}
