package io.aaps.copilot.ui.foundation.screens

import io.aaps.copilot.domain.events.CompensationEvent
import io.aaps.copilot.domain.events.CompensationEventType
import io.aaps.copilot.domain.events.EventSource
import org.junit.Assert.*
import org.junit.Test

class ClarityFoodTimelineTest {
    private val origin = 1_800_000_000_000L
    private fun meal(id: String = "meal-a", ts: Long = origin - 60_000L,
        grams: String = "22", source: EventSource = EventSource.USER) =
        CompensationEvent(id, ts, ts + 180 * 60_000L, CompensationEventType.MEAL,
            source = source, attributes = mapOf("carbs" to grams))
    private fun state(events: List<CompensationEvent>) = OverviewUiState(ScreenLoadState.READY, false,
        foodDisplayNowTs = origin, events = events)

    @Test fun retainsRealRecordsInChronologicalOrderWithoutResidualInference() {
        val early = meal("early", origin - 11 * 60 * 60_000L, "40", EventSource.AAPS)
        val later = meal()
        assertEquals(listOf(early, later), clarityFoodTimelineEvents(state(listOf(later, early))))
        assertEquals(40.0, clarityMealEnteredGrams(early)!!, 0.0)
    }

    @Test fun excludesFutureOldUnknownAndSyntheticEntries() {
        val records = listOf(meal("future", origin + 1), meal("old", origin - 13 * 60 * 60_000L),
            meal("unknown", grams = "NaN"), meal("automatic", source = EventSource.AUTOMATIC),
            meal("uam").copy(subtype = "UAM"), meal("marker").copy(attributes = mapOf("carbs" to "20", "source" to "uam_engine")))
        assertTrue(clarityFoodTimelineEvents(state(records)).isEmpty())
    }

    @Test fun rejectsConflictingAliasesAndNonfiniteGrams() {
        assertNull(clarityMealEnteredGrams(meal().copy(attributes = mapOf("carbs" to "22", "carbsGrams" to "30"))))
        for (raw in listOf("Infinity", "-1", "0", "unknown")) assertNull(clarityMealEnteredGrams(meal(grams = raw)))
        assertEquals(22.0, clarityMealEnteredGrams(meal().copy(attributes = mapOf("carbsGrams" to "22")))!!, 0.0)
    }

    @Test fun latestRevisionWinsEvenWhenNewRevisionIsNoLongerMeal() {
        val older = meal()
        val newer = older.copy(revision = 2, type = CompensationEventType.CUSTOM)
        assertTrue(clarityFoodTimelineEvents(state(listOf(older, newer))).isEmpty())
        val latest = older.copy(revision = 3, attributes = mapOf("carbs" to "28"))
        assertEquals(listOf(latest), clarityFoodTimelineEvents(state(listOf(older, latest))))
    }

    @Test fun retainedCurveUsesItsOriginalOriginNotFutureExternalCobTime() {
        val accepted = AcceptedFoodDisplaySnapshot(FoodDisplayScope(1, "EVIDENCE", "EVIDENCE", "session-a"),
            "cycle-a", origin, listOf(ChartPointUi(origin, 6.0), ChartPointUi(origin + 12 * 60 * 60_000L, 7.0)), true, false)
        val current = state(listOf(meal(), meal("later", origin + 60_000L))).copy(
            foodDisplayNowTs = origin + 10 * 60_000L, acceptedFoodDisplay = accepted, foodDisplayStatus = FoodDisplayStatus.UPDATING)
        assertEquals(listOf("meal-a"), clarityFoodTimelineEvents(current).map { it.localId })
        assertEquals(origin, current.acceptedFoodDisplay!!.generatedAt)
    }
}
