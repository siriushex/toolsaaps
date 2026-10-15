package io.aaps.copilot.domain.events

import org.junit.Assert.*
import org.junit.Test

class EventCompensationReviewTest {
    private val start = 1_800_000_000_000L
    private val minute = 60_000L
    private val samples = (0..12).map { EventContextSample(start + it * 5 * minute, 5.0 + it * 0.25, "aaps_broadcast") }

    @Test fun describesObservedRangeWithoutDoseOrTimingRecommendation() {
        val result = EventCompensationReview.glucose(start, start + 60 * minute, samples)!!
        assertEquals(5.0, result.baseline, 0.0)
        assertEquals(8.0, result.peak, 0.0)
        assertEquals(60, result.peakAfterMinutes)
        assertEquals(12, result.sampleCount)
    }

    @Test fun futurePointsDoNotLeakIntoCurrentObservation() {
        val result = EventCompensationReview.glucose(start, start + 30 * minute, samples)!!
        assertEquals(6.5, result.peak, 0.0)
        assertEquals(start + 30 * minute, result.throughTs)
    }

    @Test fun earlyMealAndMissingBaselineStayUnknown() {
        assertNull(EventCompensationReview.glucose(start, start + 29 * minute, samples))
        assertNull(EventCompensationReview.glucose(start, start + 60 * minute, samples.drop(1)))
    }

    @Test fun gapsAndStaleTailAreNotContinuousCoverage() {
        assertNull(EventCompensationReview.glucose(start, start + 60 * minute, samples.filterIndexed { i, _ -> i !in 4..6 }))
        assertNull(EventCompensationReview.glucose(start, start + 90 * minute, samples))
    }

    @Test fun mixedStreamsAndConflictingDuplicatesFailClosed() {
        assertNull(EventCompensationReview.glucose(start, start + 60 * minute,
            samples + samples[4].copy(value = 15.0)))
        assertNull(EventCompensationReview.glucose(start, start + 60 * minute,
            samples + samples[4].copy(source = "nightscout")))
        assertEquals(12, EventCompensationReview.glucose(start, start + 60 * minute, samples + samples)!!.sampleCount)
    }

    @Test fun invalidValuesAndInputOverflowCannotProduceAdvice() {
        listOf(Double.NaN, Double.POSITIVE_INFINITY, -1.0, 41.0).forEach { invalid ->
            assertNull(EventCompensationReview.glucose(start, start + 60 * minute,
                samples.mapIndexed { i, row -> if (i == 5) row.copy(value = invalid) else row }))
        }
        assertNull(EventCompensationReview.glucose(start, start + 60 * minute, List(601) { samples[0] }))
    }

    @Test fun comparesHistoricalIsfWithinSameStreamNotCurrentIsf() {
        val values = listOf(EventContextSample(start - 60 * minute, 2.0, "aaps_broadcast"),
            EventContextSample(start - 5 * minute, 2.5, "aaps_broadcast"),
            EventContextSample(start + minute, 9.0, "aaps_broadcast"))
        val result = EventCompensationReview.isf(start, values)!!
        assertEquals(25.0, result.changePercent, 0.0)
        assertEquals(2.5, result.atEvent, 0.0)
        assertNull(EventCompensationReview.isf(start, values.map { it.copy(ts = it.ts - 120 * minute) }))
    }

    @Test fun isfSourceSwitchConflictAndZeroDenominatorFailClosed() {
        val before = EventContextSample(start - 60 * minute, 2.0, "aaps_broadcast")
        val current = EventContextSample(start, 1.5, "aaps_broadcast")
        assertEquals(-25.0, EventCompensationReview.isf(start, listOf(before, current))!!.changePercent, 0.0)
        assertNull(EventCompensationReview.isf(start, listOf(before.copy(value = 0.0), current)))
        assertNull(EventCompensationReview.isf(start, listOf(before, current.copy(source = "nightscout"))))
        assertNull(EventCompensationReview.isf(start, listOf(before, current, current.copy(value = 1.0))))
    }
}
