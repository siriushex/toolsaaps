package io.aaps.copilot.domain.predict

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MealEffectTimelineTest {
    @Test
    fun buildsCumulativeFoodContributionFromAcceptedFiveMinuteSteps() {
        val timeline = MealEffectTimelineBuilder.build(
            snapshot = snapshot(listOf(0.0, 0.2, 0.3, 0.1, 0.0, 0.4, 0.2)),
            horizonMinutes = 30
        )

        requireNotNull(timeline)
        assertEquals(7, timeline.points.size)
        assertEquals(0.0, timeline.points.first().cumulativeDeltaMmol, 0.0)
        assertEquals(0.2, timeline.points[1].cumulativeDeltaMmol, 0.0)
        assertEquals(1.2, timeline.points.last().cumulativeDeltaMmol, 0.0)
        assertEquals(30 * 60_000L + 1_700_000_000_000L, timeline.points.last().timestamp)
        assertEquals(0.8, timeline.points.last().coverage, 0.0)
    }

    @Test
    fun refusesUnknownCoverageAndInvalidAcceptedStepsInsteadOfDrawingZero() {
        assertNull(MealEffectTimelineBuilder.build(snapshot(listOf(0.0, 0.2), coverage = null)))
        assertNull(MealEffectTimelineBuilder.build(snapshot(listOf(0.0, -0.1, 0.2))))
        assertNull(MealEffectTimelineBuilder.build(snapshot(listOf(0.0, 0.2, Double.NaN))))
    }

    @Test
    fun refusesIncompleteHorizonAndRejectsNonFiveMinuteHorizon() {
        assertNull(MealEffectTimelineBuilder.build(snapshot(listOf(0.0, 0.2, 0.3)), horizonMinutes = 30))
        assertNull(MealEffectTimelineBuilder.build(snapshot(List(13) { 0.1 }), horizonMinutes = 22))
    }

    private fun snapshot(steps: List<Double>, coverage: Double? = 0.8): AcceptedMealEffectSnapshot =
        AcceptedMealEffectSnapshot(
            asOfTs = 1_700_000_000_000L,
            runtimeGeneration = "runtime-1",
            isfRevision = "isf-1",
            crRevision = "cr-1",
            curveRevision = "curve-1",
            announcedCarbCoverage = coverage,
            announcedCarbStep = steps
        )
}
