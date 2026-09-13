package io.aaps.copilot.domain.predict

import io.aaps.copilot.domain.model.DataQuality
import io.aaps.copilot.domain.model.GlucosePoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RevisionAwareKalmanFilterTest {
    private fun history(start: Int = 0, size: Int = 73) = (start until start + size).map {
        KalmanHistoryInput(1_700_000_000_000L + it * 300_000L, 7.0 + it * .01,
            if (it == start) 0.0 else -.01)
    }

    @Test fun duplicateHistoryAppliesNoUpdates() {
        val filter = RevisionAwareKalmanFilter()
        val first = filter.update(history(), .1)
        val repeated = filter.update(history(), .1)
        assertEquals(73, first.appliedUpdates)
        assertEquals(0, repeated.appliedUpdates)
        assertFalse(repeated.rebuilt)
        assertEquals(first.snapshot, repeated.snapshot)
    }

    @Test fun rollingWindowsProcessOnlyNewPointIncludingTrimmedFirstInterval() {
        val filter = RevisionAwareKalmanFilter()
        filter.update(history(), .1)
        for (start in 1..100) {
            val next = filter.update(history(start), .1)
            assertFalse("window $start rebuilt", next.rebuilt)
            assertEquals(1, next.appliedUpdates)
            assertEquals(73 + start, next.snapshot!!.updatesCount)
        }
    }

    @Test fun trimWithoutAppendKeepsState() {
        val filter = RevisionAwareKalmanFilter()
        val first = filter.update(history(), .1)
        val trimmed = filter.update(history(start = 1, size = 72), .1)
        assertFalse(trimmed.rebuilt)
        assertEquals(0, trimmed.appliedUpdates)
        assertEquals(first.snapshot, trimmed.snapshot)
    }

    @Test fun glucoseAndKnownInputCorrectionsReplayWithoutMutatingCaller() {
        for (therapy in listOf(false, true)) {
            val filter = RevisionAwareKalmanFilter()
            val old = history()
            filter.update(old, .1)
            val revised = old.mapIndexed { index, point ->
                if (index != 65) point else if (therapy) point.copy(knownRocPerMin = -.04)
                else point.copy(glucose = 8.5)
            }
            val updated = filter.update(revised, .1)
            assertTrue(updated.rebuilt)
            assertEquals(73, updated.appliedUpdates)
            assertEquals(RevisionAwareKalmanFilter().update(revised, .1).snapshot, updated.snapshot)
            assertEquals(history(), old)
            assertFalse(filter.update(revised, .1).rebuilt)
        }
    }

    @Test fun historicalDeletionInsertionAndPrependingReplay() {
        val full = history()
        val deleted = full.filterIndexed { index, _ -> index != 65 }
        for ((before, after) in listOf(full to deleted, deleted to full, full.drop(1) to full)) {
            val filter = RevisionAwareKalmanFilter()
            filter.update(before, .1)
            val updated = filter.update(after, .1)
            assertTrue(updated.rebuilt)
            assertEquals(RevisionAwareKalmanFilter().update(after, .1).snapshot, updated.snapshot)
        }
    }

    @Test fun rollbackAndReanchoredGridReplay() {
        val full = history()
        val shifted = full.map { it.copy(ts = it.ts + 60_000L) }
        for (after in listOf(full.dropLast(1), history(100), shifted)) {
            val filter = RevisionAwareKalmanFilter()
            filter.update(full, .1)
            val updated = filter.update(after, .1)
            assertTrue(updated.rebuilt)
            assertEquals(RevisionAwareKalmanFilter().update(after, .1).snapshot, updated.snapshot)
        }
    }

    @Test fun callerListMutationCannotSilentlyChangeCachedHistory() {
        val filter = RevisionAwareKalmanFilter()
        val points = history().toMutableList()
        filter.update(points, .1)
        points[65] = points[65].copy(glucose = 8.5)
        assertTrue(filter.update(points, .1).rebuilt)
    }

    @Test fun emptyInputDoesNotCorruptState() {
        val filter = RevisionAwareKalmanFilter()
        assertEquals(null, filter.update(emptyList(), .1).snapshot)
        val first = filter.update(history(), .1)
        assertEquals(first.snapshot, filter.update(emptyList(), .1).snapshot)
        assertEquals(first.snapshot, filter.update(history(), .1).snapshot)
    }

    @Test fun reanchoredGridReplaysActualTherapyIntervals() {
        val old = history()
        val shifted = old.mapIndexed { index, point ->
            point.copy(ts = point.ts + 60_000L, knownRocPerMin = if (index == 0) 0.0 else -.1)
        }
        val source = (0..360).map {
            GlucosePoint(old.first().ts + it * 60_000L, 8.0, "test", DataQuality.OK)
        }
        val filter = RevisionAwareKalmanFilter()
        filter.update(old, .1, source)
        val nextSource = source + source.last().copy(ts = source.last().ts + 60_000L)
        val actual = filter.update(shifted, .1, nextSource) { previous ->
            previous.map { it.knownRocPerMin }.toDoubleArray()
        }
        assertEquals(RevisionAwareKalmanFilter().update(shifted, .1).snapshot, actual.snapshot)
        assertTrue(actual.rebuilt)
        assertFalse(actual.causalHistoryRevised)
    }

    @Test fun trimmingDoesNotHideAChangedHistoricalBoundaryInterval() {
        val old = history()
        val filter = RevisionAwareKalmanFilter()
        filter.update(old, .1)
        val trimmed = history(1)
        val actual = filter.update(trimmed, .1, revalidateKnownInputs = { previous ->
            previous.mapIndexed { index, point -> if (index == 1) -.2 else point.knownRocPerMin }.toDoubleArray()
        })
        assertTrue(actual.rebuilt)
        assertTrue(actual.causalHistoryRevised)
        assertEquals(RevisionAwareKalmanFilter().update(trimmed, .1).snapshot, actual.snapshot)
    }
}
