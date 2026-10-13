package io.aaps.copilot.data.repository

import io.aaps.copilot.domain.pump.InfusionSetEvidence
import io.aaps.copilot.domain.pump.InfusionSetPolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test

class InfusionSetReminderRepositoryTest {
    private val start = 1_800_000_000_000L
    private val first = InfusionSetEvidence(start, "infusion-$start", "nightscout_treatment")
    private class MemoryStore : InfusionReminderStore {
        var saved = InfusionReminderRecord()
        override suspend fun load() = saved
        override suspend fun save(record: InfusionReminderRecord) { saved = record }
    }
    private class Fixture(val first: InfusionSetEvidence) {
        val store = MemoryStore()
        var source: InfusionSetEvidence? = first
        var now = first.occurrenceTs
        var muted = false
        var enabled = true
        var capable = true
        var posts = 0
        var clears = 0
        var cancellations = 0
        var result = InfusionPostResult.POSTED
        var postAction: suspend () -> InfusionPostResult = { posts++; result }
        val scheduled = mutableListOf<Triple<String, Long, Boolean>>()
        fun repo() = InfusionSetReminderRepository(store, { source }, { now },
            { enabled }, { capable }, { _, _ -> postAction() }, { clears++ },
            { generation, due, replace ->
                assertEquals(generation, store.saved.generation)
                assertEquals(due, store.saved.dueTs)
                scheduled.add(Triple(generation, due, replace))
            }, { cancellations++ }, { action -> action(muted) })
        fun due() { now = first.occurrenceTs + InfusionSetPolicy.INTERVAL_MS }
    }

    @Test fun sourceIsSavedBeforeIndependentSchedulingAndRestartRestoresIt() = runBlocking {
        val f = Fixture(first)
        f.repo().refresh()
        assertEquals(first.generation, f.scheduled.single().first)
        assertTrue(f.scheduled.single().third)
        assertEquals(start + InfusionSetPolicy.INTERVAL_MS, f.store.saved.dueTs)
        f.repo().refresh()
        assertFalse(f.scheduled.last().third)
        assertEquals(0, f.posts)
    }

    @Test fun exactDuePostsOnceAndRestartCannotRenotify() = runBlocking {
        val f = Fixture(first); val repo = f.repo()
        repo.refresh(); f.due()
        repo.runDue(first.generation)
        assertEquals(1, f.posts)
        assertEquals(InfusionNotice.POSTED, f.store.saved.notice)
        f.repo().runDue(first.generation)
        assertEquals(1, f.posts)
    }

    @Test fun staleWorkerRereadsChangedOrDeletedSource() = runBlocking {
        val f = Fixture(first); val repo = f.repo(); repo.refresh(); f.due()
        val changed = f.now - 1000
        f.source = InfusionSetEvidence(changed, "infusion-$changed", first.source)
        repo.runDue(first.generation)
        assertEquals(0, f.posts)
        assertEquals(f.source!!.generation, f.store.saved.generation)
        f.source = null
        repo.runDue(f.store.saved.generation)
        assertEquals("", f.store.saved.generation)
        assertTrue(f.cancellations > 0)
    }

    @Test fun unknownPostingAfterCancellationIsTerminalAcrossRestart() = runBlocking {
        val f = Fixture(first); f.repo().refresh(); f.due()
        f.postAction = { f.posts++; throw CancellationException("interrupted post") }
        assertTrue(runCatching { f.repo().runDue(first.generation) }.exceptionOrNull() is CancellationException)
        assertEquals(InfusionNotice.CLAIMED, f.store.saved.notice)
        f.postAction = { f.posts++; InfusionPostResult.POSTED }
        f.repo().runDue(first.generation)
        assertEquals(1, f.posts)
    }

    @Test fun knownPermissionBlockRetriesOnceAfterCapabilityRestoration() = runBlocking {
        val f = Fixture(first); f.repo().refresh(); f.due(); f.capable = false
        f.repo().runDue(first.generation)
        assertEquals(InfusionNotice.BLOCKED, f.store.saved.notice)
        assertEquals(0, f.posts)
        f.repo().runDue(first.generation)
        assertEquals(1, f.store.saved.attempts)
        f.capable = true; f.result = InfusionPostResult.BLOCKED
        f.repo().refresh()
        assertTrue("restored capability must replace a still-finishing blocked worker", f.scheduled.last().third)
        f.repo().runDue(first.generation)
        assertEquals(2, f.store.saved.attempts)
        assertEquals(1, f.posts)
        f.repo().runDue(first.generation)
        assertEquals(1, f.posts)
    }

    @Test fun offAtDeadlineSuppressesRatherThanDelaysReminder() = runBlocking {
        listOf(true, false).forEach { useMute ->
            val f = Fixture(first); f.repo().refresh(); f.due()
            f.muted = useMute; f.enabled = useMute
            f.repo().runDue(first.generation)
            assertEquals(InfusionNotice.SUPPRESSED, f.store.saved.notice)
            f.muted = false; f.enabled = true; f.repo().runDue(first.generation)
            assertEquals(0, f.posts)
            assertEquals(start + InfusionSetPolicy.INTERVAL_MS, f.store.saved.dueTs)
        }
    }

    @Test fun deletedAndReimportedOldOccurrenceDoesNotForgetAttemptWatermark() = runBlocking {
        val f = Fixture(first); f.repo().refresh(); f.due(); f.repo().runDue(first.generation)
        f.source = null; f.repo().refresh()
        assertEquals(start, f.store.saved.attemptedOccurrenceWatermark)
        f.source = first; f.repo().refresh(); f.repo().runDue(first.generation)
        assertEquals(1, f.posts)
        assertEquals(InfusionNotice.PREVIOUSLY_ATTEMPTED, f.store.saved.notice)
    }

    @Test fun durableClaimPrecedesUnknownPostAndOlderCorrectionCannotRenotify() = runBlocking {
        val f = Fixture(first); val repo = f.repo(); repo.refresh(); f.due()
        f.postAction = {
            assertEquals(InfusionNotice.CLAIMED, f.store.saved.notice)
            f.posts++; InfusionPostResult.UNKNOWN
        }
        repo.runDue(first.generation)
        assertEquals(InfusionNotice.CLAIMED, f.store.saved.notice)
        assertEquals(1, f.posts)
        f.source = first.copy(occurrenceTs = start - 1000, generation = "infusion-${start - 1000}")
        repo.refresh(); repo.runDue(f.source!!.generation)
        assertEquals(1, f.posts)
    }

    @Test fun replacementRefreshCannotBeOverwrittenByInFlightOldReceipt() = runBlocking {
        val f = Fixture(first); val repo = f.repo(); repo.refresh(); f.due()
        val entered = CompletableDeferred<Unit>(); val finish = CompletableDeferred<Unit>()
        f.postAction = { entered.complete(Unit); finish.await(); f.posts++; InfusionPostResult.POSTED }
        val posting = launch { repo.runDue(first.generation) }
        entered.await()
        val next = f.now - 1000
        f.source = InfusionSetEvidence(next, "infusion-$next", first.source)
        val refresh = async { repo.refresh() }
        yield()
        assertFalse(refresh.isCompleted)
        assertEquals(first.generation, f.store.saved.generation)
        finish.complete(Unit); posting.join(); refresh.await()
        assertEquals(f.source!!.generation, f.store.saved.generation)
        assertEquals(InfusionNotice.NONE, f.store.saved.notice)
        assertEquals(1, f.posts)
    }
}
