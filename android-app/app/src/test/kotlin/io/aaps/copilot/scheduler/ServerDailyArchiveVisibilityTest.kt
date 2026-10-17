package io.aaps.copilot.scheduler

import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ServerDailyArchiveVisibilityTest {
    @Test fun failedReadDoesNotEndFollowingDailyCutoffsAndCancellationStopsWakes() = runTest {
        val base = Instant.parse("2026-10-14T04:59:59Z").toEpochMilli()
        var calls = 0
        val work = backgroundScope.launch {
            observeDailyArchiveCutoffs({ base + testScheduler.currentTime }, { null }, { ZoneId.of("UTC") }) { calls++ }
        }
        runCurrent()
        advanceTimeBy(1001); runCurrent()
        assertEquals(1, calls)
        advanceTimeBy(86_400_000); runCurrent()
        assertEquals(2, calls)
        work.cancelAndJoin()
        advanceTimeBy(86_400_000); runCurrent()
        assertEquals(2, calls)
    }

    @Test fun retainedExpiryWakesOnceWithoutTightLoopAndDstUsesCivilCutoff() = runTest {
        val base = Instant.parse("2026-03-28T12:00:00Z").toEpochMilli()
        var calls = 0
        val work = backgroundScope.launch {
            observeDailyArchiveCutoffs({ base + testScheduler.currentTime }, { base + 2000 }, { ZoneId.of("Europe/Berlin") }) { calls++ }
        }
        runCurrent()
        advanceTimeBy(2001); runCurrent()
        assertEquals(1, calls)
        advanceTimeBy(100_000); runCurrent()
        assertEquals(1, calls)
        // DST shifts the next local05:00 to03:00 UTC, not a fixed24-hour delay.
        advanceTimeBy(15 * 3_600_000L - testScheduler.currentTime + 1); runCurrent()
        assertEquals(2, calls)
        work.cancelAndJoin()
    }
}
