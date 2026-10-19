package io.aaps.copilot.scheduler

import io.aaps.copilot.data.repository.RoomAiExportStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ServerAiHistoryUploadTest {
    @Test fun batchIsBoundedAndStopsWhenHistoryIsComplete() = runTest {
        var calls = 0
        val partial = RoomAiExportStatus(false, 1, 100, 64, 0, 100)
        assertEquals(partial, uploadHistorySlices { calls++; partial })
        assertEquals(4, calls)
        calls = 0
        val complete = partial.copy(scanFinished = true)
        assertEquals(complete, uploadHistorySlices { calls++; if (calls == 2) complete else partial })
        assertEquals(2, calls)
        calls = 0
        assertEquals(complete, uploadHistorySlices { calls++; complete })
        assertEquals(1, calls)
    }

    @Test fun onlyRecoverableTransportFailuresAreRetried() {
        for (reason in io.aaps.copilot.data.repository.ServerAiHistoryError.entries) {
            val retryable = reason in setOf(io.aaps.copilot.data.repository.ServerAiHistoryError.NETWORK,
                io.aaps.copilot.data.repository.ServerAiHistoryError.UNAVAILABLE,
                io.aaps.copilot.data.repository.ServerAiHistoryError.RATE_LIMITED)
            assertEquals(retryable, isRetryableHistoryUploadFailure(io.aaps.copilot.data.repository.ServerAiHistoryFailure(reason)))
        }
        for (reason in io.aaps.copilot.security.ServerAiConnectionError.entries) {
            assertEquals(reason == io.aaps.copilot.security.ServerAiConnectionError.NETWORK,
                isRetryableHistoryUploadFailure(io.aaps.copilot.security.ServerAiConnectionFailure(reason)))
        }
        assertTrue(isRetryableHistoryUploadFailure(java.io.IOException("offline")))
        assertFalse(isRetryableHistoryUploadFailure(IllegalStateException("invalid checkpoint")))
    }

    @Test fun missingSessionDisabledBackgroundAndPowerSaveNeverAccessData() = runTest {
        for ((enabled, manual, powerSave, hasSession) in listOf(
            listOf(false, false, false, true), listOf(true, false, false, false),
            listOf(true, false, true, true), listOf(false, true, true, true),
            listOf(false, true, false, false)
        )) {
            assertEquals(HistoryUploadOutcome.SKIPPED,
                runHistoryUpload(MutableStateFlow(historyUploadAllowed(enabled, manual, powerSave, hasSession))) {
                    error("source must not be read")
                })
        }
    }

    @Test fun explicitManualUploadCanRunWithBackgroundOff() = runTest {
        var calls = 0
        assertEquals(HistoryUploadOutcome.COMPLETE, runHistoryUpload(MutableStateFlow(historyUploadAllowed(false, true, false, true))) {
            calls++
            RoomAiExportStatus(true, 1, 100, 4, 0, 100)
        })
        assertEquals(1, calls)
    }

    @Test fun partialPagesAndInitialBackfillContinueWithoutInventingCompletion() = runTest {
        for (status in listOf(RoomAiExportStatus(false, 1, 100, 64, 0, 50),
            RoomAiExportStatus(true, 1, 100, 64, 0, 0))) {
            assertEquals(HistoryUploadOutcome.MORE, runHistoryUpload(MutableStateFlow(true)) { status })
        }
    }

    @Test fun cancellationAndTransportFailureDoNotBecomeSuccess() = runTest {
        for (failure in listOf(CancellationException("cancelled"), java.io.IOException("offline"))) {
            val actual = runCatching {
                runHistoryUpload(MutableStateFlow(true)) { throw failure }
            }.exceptionOrNull()
            // Coroutine stack-trace recovery may copy an exception across an async boundary.
            assertEquals(failure.javaClass, actual?.javaClass)
            assertEquals(failure.message, actual?.message)
        }
    }

    @Test fun powerSaveOrRevocationCancelsAnInFlightUpload() = runTest {
        val admitted = MutableStateFlow(true)
        var cancelled = false
        val result = async {
            runHistoryUpload(admitted) {
                try { awaitCancellation() } finally { cancelled = true }
            }
        }
        runCurrent()
        admitted.value = false
        assertEquals(HistoryUploadOutcome.SKIPPED, result.await())
        assertTrue(cancelled)
    }

    @Test fun expiredPowerPauseCanRunWithoutMonitoringOrTherapyArmed() = runTest {
        assertFalse(historyUploadAllowed(true, false, true, true))
        assertTrue(historyUploadAllowed(true, false, false, true))
        assertEquals(HistoryUploadOutcome.COMPLETE, runHistoryUpload(MutableStateFlow(true)) {
            RoomAiExportStatus(true, 1, 100, 0, 0, 100)
        })
    }
}
