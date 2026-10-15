package io.aaps.copilot.domain.nutrition

import io.aaps.copilot.domain.profile.MealAbsorptionProfile
import io.aaps.copilot.domain.profile.MealGlycemicIndex
import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

class LentaMealEstimateTest {
    private val now = Instant.parse("2026-10-13T12:00:00Z").toEpochMilli()
    private val request = HybridMealResponseRequest(now, MealAbsorptionProfile.MIXED,
        MealPortion.MEDIUM, "UTC", 720)
    private fun meal(id: Int, grams: Double = 35.0, gi: Double? = null) = LentaMealHistoryEntry(
        MealPortionObservation("meal-$id", Instant.ofEpochMilli(now - id * 86400000L), grams,
            MealAbsorptionProfile.MIXED, MealPortionProvenance.USER_ENTERED, MealPortion.MEDIUM,
            availableAt = Instant.ofEpochMilli(now - id * 86400000L + 1000), therapyRevision = "r1"),
        gi?.let { MealGlycemicIndex.fromStored(it, "USER") })
    private fun history(vararg entries: LentaMealHistoryEntry) = LentaMealHistory(now, "UTC", entries.toList())

    @Test fun everyTypeAndPortionWorksWithoutHistoryAndConservesMass() {
        for (type in MealAbsorptionProfile.entries) for (portion in MealPortion.entries) {
            val result = LentaMealEstimator().estimate(request.copy(selectedProfile = type, portion = portion), history())
            assertEquals(0.0, result.curve.first().modeledAbsorbedGrams, 0.0)
            assertEquals(0.0, result.curve.last().modeledRemainingGrams, 0.0)
            assertEquals(result.durationMinutes, result.curve.last().ageMinutes)
            result.curve.forEach { assertEquals(result.modeledGrams,
                it.modeledRemainingGrams + it.modeledAbsorbedGrams, 1e-9) }
            assertNull(result.historyGi)
            assertFalse(result.clinicalInfluenceAllowed)
        }
    }

    @Test fun oneIndependentMealHasSmallBoundedInfluenceWithoutInventingGi() {
        val base = LentaMealEstimator().estimate(request, history())
        val personal = LentaMealEstimator().estimate(request, history(meal(1)))
        assertEquals(1, personal.supportMeals)
        assertTrue(personal.modeledGrams > base.modeledGrams)
        assertTrue(personal.modeledGrams < 35.0)
        assertNull(personal.historyGi)
        assertEquals(base.durationMinutes, personal.durationMinutes)
    }

    @Test fun knownHighAndLowGiShapeThePreviewButKeepMassAndEndpoint() {
        val high = LentaMealEstimator().estimate(request, history(meal(1, gi = 90.0)))
        val low = LentaMealEstimator().estimate(request, history(meal(1, gi = 30.0)))
        assertEquals(90.0, high.historyGi!!, 0.0)
        assertEquals(1, high.giSupportMeals)
        assertTrue(high.curve[10].modeledAbsorbedGrams > low.curve[10].modeledAbsorbedGrams)
        assertEquals(high.modeledGrams, low.modeledGrams, 0.0)
        assertEquals(high.durationMinutes, low.durationMinutes)
        assertEquals(0.0, high.curve.last().modeledRemainingGrams, 0.0)
    }

    @Test fun unsuitableUnfinishedAndConflictingEntriesDoNotInfluenceEstimate() {
        val good = meal(1)
        val bad = listOf(good.copy(meal = good.meal.copy(profile = MealAbsorptionProfile.FAST)),
            meal(2).copy(meal = meal(2).meal.copy(provenance = MealPortionProvenance.ACCEPTED_SUGGESTION)),
            meal(3).copy(meal = meal(3).meal.copy(availableAt = Instant.ofEpochMilli(now + 1))),
            good.copy(meal = good.meal.copy(timestamp = Instant.ofEpochMilli(now - 60000))),
            meal(4), meal(4).copy(meal = meal(4).meal.copy(deleted = true)))
        assertEquals(0, LentaMealEstimator().estimate(request, history(*bad.toTypedArray())).supportMeals)
    }

    @Test fun timeNeighborhoodWrapsAtMidnightAndCutoffIsNotMoved() {
        val entry = meal(1).let { it.copy(meal = it.meal.copy(
            timestamp = Instant.parse("2026-10-12T23:50:00Z"),
            availableAt = Instant.parse("2026-10-12T23:50:01Z"))) }
        val result = LentaMealEstimator().estimate(request.copy(minuteOfDay = 10), history(entry))
        assertEquals(1, result.supportMeals)
        assertEquals(0, LentaMealEstimator().estimate(request.copy(minuteOfDay = 720), history(entry)).supportMeals)
    }

    @Test fun overflowAndForeignSnapshotFallBackWithoutPartialHistoryClaims() {
        assertEquals(0, LentaMealEstimator().estimate(request,
            history(meal(1)).copy(status = LentaMealHistoryStatus.OVERFLOW)).supportMeals)
        assertEquals(0, LentaMealEstimator().estimate(request,
            history(meal(1)).copy(cutoffMs = now + 1)).supportMeals)
        assertEquals(0, LentaMealEstimator().estimate(request,
            history(meal(1)).copy(zoneId = "Europe/London")).supportMeals)
    }
}
