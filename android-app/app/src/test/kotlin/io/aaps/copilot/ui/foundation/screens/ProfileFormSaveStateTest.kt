package io.aaps.copilot.ui.foundation.screens

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ProfileFormSaveStateTest {
    @Test fun pendingSaveBlocksDuplicatesAndClosesOnlyAfterSuccess() = runTest {
        var closed = 0
        var requests = 0
        lateinit var complete: EnergyProfileSaveCompletion
        val form = ProfileFormSaveState(this) { closed++ }
        form.submit { requests++; complete = it }
        form.submit { requests++ }
        assertTrue(form.busy)
        assertEquals(1, requests)
        assertEquals(0, closed)
        complete(EnergyProfileSaveResult.Saved)
        runCurrent()
        assertFalse(form.busy)
        assertEquals(1, closed)
    }

    @Test fun failedSaveCanRetryAndOldCompletionCannotDismissIt() = runTest {
        var closed = 0
        lateinit var first: EnergyProfileSaveCompletion
        lateinit var second: EnergyProfileSaveCompletion
        val form = ProfileFormSaveState(this) { closed++ }
        form.submit { first = it }
        first(EnergyProfileSaveResult.Failed("Fixture error"))
        runCurrent()
        assertFalse(form.busy)
        assertEquals("Fixture error", form.error?.message)
        form.submit { second = it }
        first(EnergyProfileSaveResult.Saved)
        runCurrent()
        assertTrue(form.busy)
        assertNull(form.error)
        assertEquals(0, closed)
        second(EnergyProfileSaveResult.Saved)
        second(EnergyProfileSaveResult.Saved)
        runCurrent()
        assertEquals(1, closed)
    }

    @Test fun disposedFormCannotCloseAnotherEditor() = runTest {
        var closed = 0
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        lateinit var complete: EnergyProfileSaveCompletion
        val form = ProfileFormSaveState(scope) { closed++ }
        form.submit { complete = it }
        scope.cancel()
        complete(EnergyProfileSaveResult.Saved)
        runCurrent()
        assertEquals(0, closed)
    }

    @Test fun unexpectedWriterExceptionKeepsFormEditable() = runTest {
        val form = ProfileFormSaveState(this) { fail("Must not close") }
        form.submit { error("Fixture write failed") }
        runCurrent()
        assertFalse(form.busy)
        assertNotNull(form.error)
    }

    @Test fun cancellationIsNotConvertedIntoAnErrorOrSuccess() = runTest {
        val form = ProfileFormSaveState(this) { fail("Must not close") }
        lateinit var canceled: EnergyProfileSaveCompletion
        val failure = runCatching {
            form.submit { canceled = it; throw CancellationException("Fixture canceled") }
        }.exceptionOrNull()
        assertTrue(failure is CancellationException)
        assertFalse(form.busy)
        assertNull(form.error)
        form.submit { }
        canceled(EnergyProfileSaveResult.Saved)
        runCurrent()
        assertTrue(form.busy)
    }
}
