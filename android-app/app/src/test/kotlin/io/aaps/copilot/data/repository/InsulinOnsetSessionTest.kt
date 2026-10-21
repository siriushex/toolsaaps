package io.aaps.copilot.data.repository

import io.aaps.copilot.data.local.entity.TelemetrySampleEntity
import io.aaps.copilot.data.local.entity.TherapyEventEntity
import java.io.IOException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class InsulinOnsetSessionTest {
    private val now = 10_000_000L
    private val model = AiInsulinContext("FIASP", 27.5, now, fittedProfileId = "FIASP")
    private val row = TherapyEventEntity("meal-one", now, "meal_bolus", "{\"insulin\":2.2,\"carbs\":40}")
    private val samples = listOf(
        TelemetrySampleEntity("onset", now, "copilot_runtime_cob_iob", "insulin_real_onset_min", 27.5, null, "min", "OK"),
        TelemetrySampleEntity("profile", now, "copilot_real_insulin_profile", "insulin_profile_real_source_profile", null, "FIASP", null, "OK"))
    private fun start() = retainInsulinOnsetSession(null, listOf(row), model, now)!!

    @Test fun completedTimerRemainsVisibleUntilExplicitlyDismissed() {
        val started = start()
        val later = now + 24 * 60 * 60_000L
        val restored = retainInsulinOnsetSession(started, listOf(row), model, later)!!
        assertTrue(restored.timer.visibleAt(later))
        assertFalse(restored.timer.requiresReview)
        assertEquals(started.timer.estimatedAtMs, restored.timer.estimatedAtMs)
        assertEquals(0L, restored.timer.remainingSeconds(later))
        assertFalse("old completion cannot repeat a meal gesture", restored.timer.showsMealCompletionAt(later))
        assertNull("historical rows alone must not invent a new timer", retainInsulinOnsetSession(null, listOf(row), model, later))
    }

    @Test fun mealGestureIsLimitedToRecentValidMealCompletionNotRetentionLifetime() {
        val timer = start().timer
        val end = timer.estimatedAtMs
        assertFalse(timer.showsMealCompletionAt(end - 1))
        assertTrue(timer.showsMealCompletionAt(end))
        assertTrue(timer.showsMealCompletionAt(end + 900_000))
        assertFalse(timer.showsMealCompletionAt(end + 900_001))
        assertTrue(timer.visibleAt(end + 900_001))
        assertFalse(timer.copy(requiresReview = true).showsMealCompletionAt(end))
        assertFalse(timer.copy(mealAssociated = false).showsMealCompletionAt(end))
    }

    @Test fun missingCancelledOrConflictingInputsKeepReviewTileNotAnEatingSignal() {
        val started = start()
        val variants = listOf(
            emptyList<TherapyEventEntity>() to model,
            listOf(row.copy(payloadJson = "{\"insulin\":2.2,\"cancelled\":true}")) to model,
            listOf(row) to model.copy(onsetMinutes = null),
            listOf(row) to model.copy(fittedProfileId = "HUMALOG"),
            listOf(row, row.copy(id = "same-time")) to model,
            listOf(row) to model.copy(configuredProfileId = "HUMALOG", fittedProfileId = "HUMALOG")
        )
        variants.forEach { (events, estimate) ->
            val retained = retainInsulinOnsetSession(started, events, estimate, now + 60_000)!!
            assertTrue(retained.timer.visibleAt(now + 60_000))
            assertTrue(retained.timer.needsReviewAt(now + 60_000))
            assertEquals(started.timer.estimatedAtMs, retained.timer.estimatedAtMs)
        }
    }

    @Test fun validRefreshRecoversWithoutRestartingFromRefreshTime() {
        val invalid = retainInsulinOnsetSession(start(), listOf(row), model.copy(onsetMinutes = null), now + 60_000)!!
        val resumed = retainInsulinOnsetSession(invalid, listOf(row), model.copy(onsetMinutes = 30.0,
            onsetEvidenceTs = now + 120_000), now + 120_000)!!
        assertFalse(resumed.timer.requiresReview)
        assertEquals(now + 30 * 60_000L, resumed.timer.estimatedAtMs)
    }

    @Test fun correctionsAndHistoricalRowsCannotMoveTheAnchorBackward() {
        val started = start()
        val older = row.copy(id = "older", timestamp = now - 120_000)
        assertTrue(retainInsulinOnsetSession(started, listOf(older), model, now)!!.timer.requiresReview)
        assertTrue(retainInsulinOnsetSession(started, listOf(row.copy(timestamp = now - 1)), model, now)!!.timer.requiresReview)
        assertEquals(row.id, retainInsulinOnsetSession(started, listOf(older), model, now)!!.timer.recordKey)
    }

    @Test fun dismissSurvivesNewModelAndOnlyANewerDifferentRecordOpensAnotherTimer() {
        val dismissed = start().copy(dismissed = true)
        assertTrue(retainInsulinOnsetSession(dismissed, listOf(row), model.copy(onsetMinutes = 32.0), now)!!.dismissed)
        assertTrue(retainInsulinOnsetSession(dismissed, listOf(row.copy(timestamp = now + 1)), model, now + 1)!!.dismissed)
        val newer = row.copy(id = "meal-two", timestamp = now + 60_000)
        val next = retainInsulinOnsetSession(dismissed, listOf(row, newer), model, now + 60_000)!!
        assertFalse(next.dismissed)
        assertEquals(newer.id, next.timer.recordKey)
    }

    @Test fun wallClockRollbackKeepsReviewInsteadOfDisappearingOrCountingFromFuture() {
        val prior = start()
        assertTrue(prior.timer.visibleAt(now - 60_000))
        assertTrue(prior.timer.needsReviewAt(now - 60_000))
        assertTrue(retainInsulinOnsetSession(prior, listOf(row), model, now - 60_000)!!.timer.requiresReview)
    }

    @Test fun microbolusesCannotReplaceRetainedTimerAndIobCannotCreateOne() {
        val prior = start()
        val micro = row.copy(id = "automatic-dose", timestamp = now + 60_000,
            payloadJson = "{\"insulin\":0.1,\"isSMB\":true}")
        assertEquals(row.id, retainInsulinOnsetSession(prior, listOf(row, micro), model, now + 60_000)!!.timer.recordKey)
        assertNull(retainInsulinOnsetSession(null, listOf(micro), model, now + 60_000))
        assertNull(retainInsulinOnsetSession(null, emptyList(), model, now))
    }

    @Test fun repositoryRetainsAcrossRefreshRecreationAndDurableDismiss() = runTest {
        val rows = MutableStateFlow(listOf(row))
        val telemetry = MutableStateFlow(samples)
        val profiles = MutableStateFlow("FIASP")
        val store = MemoryStore()
        var time = now
        fun repository() = InsulinOnsetSessionRepository(backgroundScope, rows, telemetry, profiles, store) { time }
        val first = repository()
        runCurrent()
        val deadline = first.countdown.value!!.estimatedAtMs
        telemetry.value = emptyList()
        runCurrent()
        assertTrue(first.countdown.value!!.requiresReview)
        time += 20 * 60_000L
        telemetry.value = samples
        runCurrent()
        assertEquals(deadline, first.countdown.value!!.estimatedAtMs)
        assertEquals(450L, first.countdown.value!!.remainingSeconds(time))
        time += 24 * 60 * 60_000L
        val reopened = repository()
        runCurrent()
        assertNotNull(reopened.countdown.value)
        assertEquals(deadline, reopened.countdown.value!!.estimatedAtMs)
        reopened.dismiss("different-record")
        assertNotNull(reopened.countdown.value)
        reopened.dismiss(row.id)
        assertNull(reopened.countdown.value)
        val dismissedRestart = repository()
        runCurrent()
        assertNull(dismissedRestart.countdown.value)
    }

    @Test fun failedPersistenceKeepsReviewAndFailedDismissDoesNotPretendToSave() = runTest {
        val store = MemoryStore().apply { fail = true }
        val telemetry = MutableStateFlow(samples)
        val repo = InsulinOnsetSessionRepository(backgroundScope, MutableStateFlow(listOf(row)), telemetry,
            MutableStateFlow("FIASP"), store) { now }
        runCurrent()
        assertTrue(repo.countdown.value!!.requiresReview)
        repo.dismiss(row.id)
        assertNotNull(repo.countdown.value)
        store.fail = false
        telemetry.value = samples + samples.last().copy(id = "profile-duplicate")
        runCurrent()
        assertFalse(repo.countdown.value!!.requiresReview)
        repo.dismiss(row.id)
        assertNull(repo.countdown.value)
    }

    @Test fun loadingIsNotAnEmptySessionAndMustNotClearAnExistingNotification() = runTest {
        val rows = MutableSharedFlow<List<TherapyEventEntity>>(replay = 1)
        val store = MemoryStore().apply { state = start() }
        val repo = InsulinOnsetSessionRepository(backgroundScope, rows, MutableStateFlow(samples),
            MutableStateFlow("FIASP"), store) { now }
        val ready = backgroundScope.async { repo.awaitInitialEvaluation() }
        runCurrent()
        assertFalse(ready.isCompleted)
        rows.emit(listOf(row))
        runCurrent()
        assertTrue(ready.isCompleted)
        assertNotNull(repo.countdown.value)
    }

    @Test fun genuinelyEmptyStartupCompletesInitializationAndAllowsNoticeCleanup() = runTest {
        val repo = InsulinOnsetSessionRepository(backgroundScope, MutableStateFlow(emptyList()),
            MutableStateFlow(emptyList()), MutableStateFlow("FIASP"), MemoryStore()) { now }
        val ready = backgroundScope.async { repo.awaitInitialEvaluation() }
        runCurrent()
        assertTrue(ready.isCompleted)
        assertNull(repo.countdown.value)
    }

    private class MemoryStore : InsulinOnsetSessionStore {
        var state: InsulinOnsetSession? = null
        var fail = false
        override suspend fun load(): InsulinOnsetSession? = state
        override suspend fun save(session: InsulinOnsetSession) {
            if (fail) throw IOException("Storage unavailable")
            state = session
        }
    }
}
