package io.aaps.copilot.domain.predict

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ResidualArModelTest {
    private val start = 1_800_000_000_000L
    private val step = 300_000L

    private fun trained() = ResidualArModel().also { model ->
        repeat(12) { index -> model.appendOrUpdate(start + index * step, 0.20 + index * 0.005) }
    }

    @Test fun skippedBucketsAreNotConsecutiveFiveMinuteObservations() {
        val model = ResidualArModel()
        repeat(8) { index -> model.appendOrUpdate(start + index * 2 * step, 0.3) }
        val params = model.fit(false, 20.0)
        assertTrue(params.usedFallback)
        assertEquals(1, params.sampleCount)
        assertEquals(0.0, params.mu, 0.0)
    }

    @Test fun gapDiscardsPreGapMeanUntilContinuousHistoryIsAvailable() {
        val model = trained()
        assertFalse(model.fit(false, 20.0).usedFallback)
        model.appendOrUpdate(start + 13 * step, -0.2)
        val params = model.fit(false, 20.0)
        assertTrue(params.usedFallback)
        assertEquals(1, params.sampleCount)
        assertEquals(0.0, params.mu, 0.0)
        assertTrue(model.forecastSteps(-0.2, params, 12).drop(1).all { it < 0.0 })
    }

    @Test fun learningResumesAfterEightContinuousPostGapBuckets() {
        val model = trained()
        repeat(7) { index -> model.appendOrUpdate(start + (13 + index) * step, -0.2) }
        assertTrue(model.fit(false, 20.0).usedFallback)
        model.appendOrUpdate(start + 20 * step, -0.2)
        val params = model.fit(false, 20.0)
        assertFalse(params.usedFallback)
        assertEquals(8, params.sampleCount)
        assertEquals(-0.2, params.mu, 1e-9)
    }

    @Test fun minuteUpdatesAndDuplicateCyclesDoNotInflateSampleCount() {
        val model = ResidualArModel()
        repeat(40) { index ->
            model.appendOrUpdate(start + index * 60_000L, 0.1)
            model.appendOrUpdate(start + index * 60_000L, 0.1)
        }
        assertEquals(8, model.fit(false, 20.0).sampleCount)
        assertFalse(model.fit(false, 20.0).usedFallback)
    }

    @Test fun copyRetainsGapBoundaryWithoutResettingLiveHistory() {
        val live = trained()
        val expected = live.fit(false, 20.0)
        val copy = live.copyForSimulation()
        copy.appendOrUpdate(start + 13 * step, -0.2)
        assertTrue(copy.fit(false, 20.0).usedFallback)
        assertEquals(expected, live.fit(false, 20.0))
    }

    @Test fun obsoleteAndNonFiniteSamplesDoNotCorruptCurrentHistory() {
        val model = trained()
        val expected = model.fit(false, 20.0)
        model.appendOrUpdate(start + 5 * step, -1.0)
        model.appendOrUpdate(start + 12 * step, Double.NaN)
        model.appendOrUpdate(start + 12 * step, Double.POSITIVE_INFINITY)
        assertEquals(expected, model.fit(false, 20.0))
    }
}
