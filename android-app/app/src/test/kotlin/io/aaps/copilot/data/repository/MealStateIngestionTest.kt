package io.aaps.copilot.data.repository

import io.aaps.copilot.domain.meal.*
import io.aaps.copilot.domain.model.ActionCommand
import io.aaps.copilot.domain.model.SafetySnapshot
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class MealStateIngestionTest {
    @Test fun alreadyCancelledOwnerStillPersistsForNextStartup() = runTest {
        val owner = CoroutineScope(coroutineContext + Job().also { it.cancel() })
        val saved = mutableListOf<MealIngestionEvent>()
        val queue = MealStateIngestionQueue(owner, { saved += it }, { 1000L })
        runCurrent()
        assertTrue(queue.offerInput(command))
        assertEquals(1, saved.size)
        assertEquals(1L, queue.health.value.deferredSignals)
    }

    @Test fun ownerCancellationStopsWorkerWithoutClassifyingItAsStorageFailure() = runTest {
        val owner = CoroutineScope(coroutineContext + Job())
        val queue = MealStateIngestionQueue(owner, {}, { 1000L }, drain = { CompletableDeferred<MealInboxDrainResult>().await() })
        assertTrue(queue.offerInput(command))
        runCurrent()
        owner.cancel()
        runCurrent()
        assertTrue(queue.offerInput(command))
        assertEquals(0L, queue.health.value.failedBatches)
    }
    private val command = ActionCommand("input", "carbs", mapOf("carbsGrams" to "20"),
        SafetySnapshot(false, true, null, 0), "manual:meal:input")

    @Test fun preservesInputBeforeAapsAndDoesNotRetainFreeNotes() = runTest {
        val events = mutableListOf<MealIngestionEvent>()
        val queue = MealStateIngestionQueue(backgroundScope, { events += it }, { 1000L })
        assertTrue(queue.offerInput(command))
        assertTrue(queue.offerPage(page(row())))
        runCurrent()
        assertEquals(2, events.size)
        val input = (events.first() as MealIngestionEvent.Input).value
        assertEquals(command.idempotencyKey, input.id)
        assertEquals(MealCarbRange(20.0, 20.0), input.carbs)
        val record = (events.last() as MealIngestionEvent.Confirmed).rows.single()
        assertEquals(command.idempotencyKey, record.inputId)
        assertEquals(2L, record.record.revision)
        assertFalse(record.record.deleted)
    }

    @Test fun coalescedSignalsLoseNoPersistedInputsAndWorkerRecoversOnNextEvent() = runTest {
        var saved = 0
        var drains = 0
        val queue = MealStateIngestionQueue(backgroundScope, { saved++ }, { 1000L }, drain = {
            drains++
            if (drains == 1) error("storage temporarily unavailable")
            MealInboxDrainResult()
        })
        repeat(100) { assertTrue(queue.offerInput(command)) }
        runCurrent()
        assertEquals(100, saved)
        assertEquals(1L, queue.health.value.failedBatches)
        assertTrue(queue.offerInput(command))
        runCurrent()
        assertEquals(2, drains)
        assertEquals(101, saved)
    }

    @Test fun startupDrainsWithoutNewInputAndStorageFailureIsNotSuccess() = runTest {
        var drains = 0
        val queue = MealStateIngestionQueue(backgroundScope, { error("persist failed") }, { 1000L }, drain = {
            drains++; MealInboxDrainResult()
        })
        runCurrent()
        assertEquals(1, drains)
        try { queue.offerInput(command); fail("Must not acknowledge failed persistence") }
        catch (_: IllegalStateException) { }
        assertEquals(1L, queue.health.value.failedBatches)
    }

    @Test fun automaticCarbsInvalidNumbersAndHistoricalReferenceRowsAreNotInputs() = runTest {
        val events = mutableListOf<MealIngestionEvent>()
        val queue = MealStateIngestionQueue(backgroundScope, { events += it }, { 1000L })
        assertFalse(queue.offerInput(command.copy(idempotencyKey = "automatic:meal")))
        assertFalse(queue.offerInput(command.copy(params = mapOf("carbsGrams" to "NaN"))))
        assertFalse(queue.offerInput(command.copy(type = "temp_target")))
        assertTrue(queue.offerPage(page(row().copy(referenceId = 4))))
        runCurrent()
        assertTrue(events.isEmpty())
    }

    @Test fun truncatedNotesDoNotEstablishLinkAndDeletionUsesCanonicalIdentity() = runTest {
        val events = mutableListOf<MealIngestionEvent>()
        val queue = MealStateIngestionQueue(backgroundScope, { events += it }, { 1000L })
        queue.offerPage(page(row().copy(isValid = false, notesTruncated = true)))
        runCurrent()
        val record = (events.single() as MealIngestionEvent.Confirmed).rows.single()
        assertNull(record.inputId)
        assertEquals("42", record.record.canonicalId)
        assertTrue(record.record.deleted)
    }

    private fun row() = AapsCarbHistoryRow(42, 2, 1000, true, null, 1100, 0, 20.0,
        "copilot:manual:meal:input", null, false, null, null, false)
    private fun page(vararg rows: AapsCarbHistoryRow) = AapsCarbHistoryPage(rows.toList(), 0, 0, false, 2000, 2)
}
