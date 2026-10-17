package io.aaps.copilot.ui.foundation.screens

import io.aaps.copilot.domain.events.*
import io.aaps.copilot.ui.motion.mealPreparationPose
import org.junit.Assert.*
import org.junit.Test

class MealPreparationPresentationTest {
    private val now = 1_800_000_000_000L
    private fun ready() = OverviewUiState(ScreenLoadState.READY, false, glucose = 6.0, sampleAgeMinutes = 0,
        foodDisplayNowTs = now, currentIobUnits = 2.2,
        iobDetails = IobRuntimeDetailsUi(sampleTimestamp = now),
        eatingSoon = EatingSoonUiState(EatingSoonUiStatus.CONFIRMED, "meal-intent", now - 60_000L))
    private fun plan(id: String = "meal-a") = CompensationEvent(id, now + 10 * 60_000L, now + 120 * 60_000L,
        CompensationEventType.MEAL, source = EventSource.AAPS, displayCarbsGrams = 30.0,
        displayMealIdentity = id, displayMealRevision = "r1")

    @Test fun totalIobWithoutMealIntentNeverClaimsPreparationOrStartTime() {
        val result = mealPreparationPresentation(ready().copy(eatingSoon = EatingSoonUiState()))
        assertEquals(MealPreparationStatus.IDLE, result.status)
        assertNull(result.plannedMealAtMs)
    }

    @Test fun explicitIntentAndFreshIobShowContextButNotAReadyToEatClock() {
        val result = mealPreparationPresentation(ready())
        assertEquals(MealPreparationStatus.IOB_PRESENT, result.status)
        assertNull(result.plannedMealAtMs)
        assertNull(result.minutesUntilPlan)
    }

    @Test fun zeroIobWaitsAndFreshnessCannotBeManufacturedByNegativeOrFutureAge() {
        assertEquals(MealPreparationStatus.WAITING_FOR_IOB,
            mealPreparationPresentation(ready().copy(currentIobUnits = 0.0)).status)
        for (time in listOf(null, now + 1, now - 300_001)) {
            assertEquals(MealPreparationStatus.CHECK_DATA, mealPreparationPresentation(ready().copy(
                iobDetails = IobRuntimeDetailsUi(sampleTimestamp = time))).status)
        }
        for (iob in listOf(null, Double.NaN, Double.POSITIVE_INFINITY, -1.0)) {
            assertEquals(MealPreparationStatus.CHECK_DATA,
                mealPreparationPresentation(ready().copy(currentIobUnits = iob)).status)
        }
    }

    @Test fun missingStaleFutureOrLowGlucoseSuppressesDecorativeInsulinReadiness() {
        for (age in listOf(null, -1L, 6L)) {
            assertEquals(MealPreparationStatus.CHECK_DATA,
                mealPreparationPresentation(ready().copy(sampleAgeMinutes = age)).status)
        }
        assertEquals(MealPreparationStatus.CHECK_DATA, mealPreparationPresentation(ready().copy(isStale = true)).status)
        assertEquals(MealPreparationStatus.CHECK_DATA, mealPreparationPresentation(ready().copy(killSwitchEnabled = true)).status)
        for (glucose in listOf(3.0, 4.0)) {
            assertEquals(MealPreparationStatus.CHECK_GLUCOSE,
                mealPreparationPresentation(ready().copy(glucose = glucose)).status)
        }
        assertEquals(MealPreparationStatus.CHECK_GLUCOSE,
            mealPreparationPresentation(ready().copy(glucoseAlertStrongActive = true)).status)
    }

    @Test fun blockedUnknownExpiredFutureAndUnidentifiedIntentDoNotAnimate() {
        for (intent in listOf(
            ready().eatingSoon.copy(status = EatingSoonUiStatus.BLOCKED),
            ready().eatingSoon.copy(status = EatingSoonUiStatus.UNKNOWN),
            ready().eatingSoon.copy(timestamp = now - 30 * 60_000L),
            ready().eatingSoon.copy(timestamp = now + 1),
            ready().eatingSoon.copy(requestKey = null))) {
            assertEquals(MealPreparationStatus.IDLE, mealPreparationPresentation(ready().copy(eatingSoon = intent)).status)
        }
    }

    @Test fun onlySingleFutureCanonicalMealSuppliesARecordedPlanAndNotAnInferredDelay() {
        val result = mealPreparationPresentation(ready().copy(events = listOf(plan())))
        assertEquals(plan().startTs, result.plannedMealAtMs)
        assertEquals(10, result.minutesUntilPlan)
        val noIntent = mealPreparationPresentation(ready().copy(events = listOf(plan()), eatingSoon = EatingSoonUiState()))
        assertEquals(MealPreparationStatus.IOB_PRESENT, noIntent.status)
        for (events in listOf(listOf(plan(), plan("meal-b")),
            listOf(plan().copy(source = EventSource.USER)),
            listOf(plan().copy(displayMealIdentity = null)),
            listOf(plan().copy(displayMealRevision = null)),
            listOf(plan().copy(displayCarbsGrams = Double.NaN)),
            listOf(plan().copy(startTs = now - 1)))) {
            assertNull(mealPreparationPresentation(ready().copy(events = events)).plannedMealAtMs)
        }
    }

    @Test fun latestClosedOrContradictoryRevisionCannotResurrectAnOldMealPlan() {
        val original = plan()
        for (events in listOf(listOf(original, original.copy(revision = 2, status = CompensationEventStatus.CLOSED)),
            listOf(original, original.copy(startTs = original.startTs + 1)))) {
            assertNull(mealPreparationPresentation(ready().copy(events = events)).plannedMealAtMs)
        }
    }

    @Test fun finiteFoodGestureMovesOnlyDuringItsBoundedActiveWindow() {
        val rest = mealPreparationPose(0, true)
        assertNotEquals(rest, mealPreparationPose(250, true))
        for (time in listOf(-1L, 0L, 2_400L, 10_000L)) assertEquals(rest, mealPreparationPose(time, true))
        assertEquals(rest, mealPreparationPose(250, false))
        for (time in 0L..2_400L step 20) {
            val pose = mealPreparationPose(time, true)
            assertTrue(pose.x in 0f..8f && pose.y in -3f..0f && pose.angle in 0f..22f)
        }
    }
}
