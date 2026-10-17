package io.aaps.copilot.data.repository

import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class RoomAiHistoryMaintenanceTest {
    @Test fun disabledStateDoesNotAccessSourceAndDisablingCancelsInFlightSync() = runTest {
        val enabled = MutableStateFlow(false)
        var calls = 0
        var cancelled = false
        val job = launch {
            maintainRoomAiHistory(enabled, {
                calls++
                try { awaitCancellation() } finally { cancelled = true }
            }, { fail("cancellation must not be reported as failure") })
        }
        runCurrent()
        assertEquals(0, calls)
        enabled.value = true
        runCurrent()
        assertEquals(1, calls)
        enabled.value = false
        runCurrent()
        assertTrue(cancelled)
        job.cancel()
    }

    @Test fun partialAndInitialBackfillHaveShortPauseButCompletedBackfillWaits() = runTest {
        for ((finished, full, expected) in listOf(Triple(false, 0L, 5000L), Triple(true, 0L, 5000L), Triple(true, 100L, 900000L))) {
            var wait = 0L
            val job = launch {
                maintainRoomAiHistory(MutableStateFlow(true), { RoomAiExportStatus(finished, 1, 100, 0, 0, full) },
                    { fail("unexpected failure") }, { wait = it; awaitCancellation() })
            }
            runCurrent()
            assertEquals(expected, wait)
            job.cancel()
            runCurrent()
        }
    }

    @Test fun operationalFailureBacksOffWithoutExposingItsBody() = runTest {
        var failures = 0
        var wait = 0L
        val job = launch {
            maintainRoomAiHistory(MutableStateFlow(true), { throw java.io.IOException("synthetic failure") },
                { failures++ }, { wait = it; awaitCancellation() })
        }
        runCurrent()
        assertEquals(1, failures)
        assertEquals(60000L, wait)
        job.cancel()
    }
}
