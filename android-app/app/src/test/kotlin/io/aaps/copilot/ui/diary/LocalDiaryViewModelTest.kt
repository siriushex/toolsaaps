package io.aaps.copilot.ui.diary

import android.app.Application
import android.os.Bundle
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import io.aaps.copilot.data.local.entity.LocalDiaryEntryEntity
import io.aaps.copilot.data.repository.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
class LocalDiaryViewModelTest {
    private class Store : LocalDiaryStore {
        val rows = MutableStateFlow<List<LocalDiaryEntryEntity>>(emptyList())
        var failure = false
        var saves = 0
        var draft: DiaryDraft? = null
        var gate: CompletableDeferred<Unit>? = null
        var saveFailure = false
        override fun observe(deleted: Boolean, limit: Int): Flow<List<LocalDiaryEntryEntity>> = flow {
            if (failure) error("Storage unavailable")
            emitAll(rows)
        }
        override suspend fun history(id: String) = rows.value.filter { it.entryId == id }
        override suspend fun save(draft: DiaryDraft): DiarySaveResult {
            saves++; this.draft = draft; gate?.await()
            if (saveFailure) error("Lost acknowledgement")
            return DiarySaveResult.Saved(LocalDiaryEntryEntity(draft.entryId, draft.expectedRevision + 1,
                draft.kind.name, draft.occurredAtMs, draft.zoneId, 10_000, draft.insulinName,
                draft.insulinKind?.name.orEmpty(), draft.note, draft.deleted, 2000))
        }
    }

    @Test fun draftIdentityAndFieldsSurviveSavedStateRecreation() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val handle = SavedStateHandle()
        val first = LocalDiaryViewModel(Application(), Store(), handle) { 1_800_000_000_000 }
        var second: LocalDiaryViewModel? = null
        try {
            first.start(DiaryKind.INJECTION)
            val draft = first.state.value.draft!!.copy(amount = "2,25", insulinName = "Example", insulinKind = DiaryInsulinKind.RAPID)
            first.changeDraft(draft)
            val restored = SavedStateHandle(mapOf("diaryDraft" to Bundle(handle.get<Bundle>("diaryDraft")!!)))
            second = LocalDiaryViewModel(Application(), Store(), restored)
            assertEquals(draft, second.state.value.draft)
        } finally { first.viewModelScope.cancel(); second?.viewModelScope?.cancel(); Dispatchers.resetMain() }
    }

    @Test fun duplicateSaveAndDismissDuringWriteAreBlockedAndSuccessClosesEditor() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = Store().apply { gate = CompletableDeferred() }
        val model = LocalDiaryViewModel(Application(), store, SavedStateHandle()) { 1_800_000_000_000 }
        try {
            model.start(DiaryKind.MEAL)
            model.changeDraft(model.state.value.draft!!.copy(amount = "10"))
            model.save(); model.save(); model.changeDraft(null)
            runCurrent()
            assertEquals(1, store.saves)
            assertTrue(model.state.value.saving)
            assertNotNull(model.state.value.draft)
            store.gate!!.complete(Unit); runCurrent()
            assertFalse(model.state.value.saving)
            assertNull(model.state.value.draft)
        } finally { model.viewModelScope.cancel(); Dispatchers.resetMain() }
    }

    @Test fun uncertainSaveRetainsSameIdentityForRetry() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = Store().apply { saveFailure = true }
        val model = LocalDiaryViewModel(Application(), store, SavedStateHandle()) { 1_800_000_000_000 }
        try {
            model.start(DiaryKind.MEAL)
            model.changeDraft(model.state.value.draft!!.copy(amount = "10"))
            val before = model.state.value.draft
            model.save(); runCurrent()
            assertEquals(DiaryError.STORAGE, model.state.value.error)
            assertEquals(before, model.state.value.draft)
            assertFalse(model.state.value.saving)
            store.saveFailure = false
            model.save(); runCurrent()
            assertEquals(before, store.draft)
            assertNull(model.state.value.draft)
        } finally { model.viewModelScope.cancel(); Dispatchers.resetMain() }
    }

    @Test fun lateFieldCallbackCannotReplaceDifferentDraftOrReopenClosedEditor() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val model = LocalDiaryViewModel(Application(), Store(), SavedStateHandle()) { 1_800_000_000_000 }
        try {
            model.start(DiaryKind.MEAL)
            val old = model.state.value.draft!!
            model.changeDraft(null)
            model.changeDraft(old.copy(amount = "50"))
            assertNull(model.state.value.draft)
            model.start(DiaryKind.INJECTION)
            val current = model.state.value.draft
            model.changeDraft(old.copy(occurredAtMs = old.occurredAtMs - 60_000))
            assertEquals(current, model.state.value.draft)
        } finally { model.viewModelScope.cancel(); Dispatchers.resetMain() }
    }

    @Test fun failedReadClearsStaleRowsAndReloadRecovers() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = Store()
        val model = LocalDiaryViewModel(Application(), store, SavedStateHandle())
        try {
            runCurrent(); assertFalse(model.state.value.loadFailed)
            store.failure = true; model.reload(); runCurrent()
            assertTrue(model.state.value.loadFailed)
            assertTrue(model.state.value.entries.isEmpty())
            store.failure = false; model.reload(); runCurrent()
            assertFalse(model.state.value.loadFailed)
        } finally { model.viewModelScope.cancel(); Dispatchers.resetMain() }
    }
}
