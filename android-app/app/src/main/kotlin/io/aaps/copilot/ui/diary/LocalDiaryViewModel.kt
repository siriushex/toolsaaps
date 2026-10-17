package io.aaps.copilot.ui.diary

import android.app.Application
import android.os.Bundle
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import io.aaps.copilot.CopilotApp
import io.aaps.copilot.data.local.entity.LocalDiaryEntryEntity
import io.aaps.copilot.data.repository.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.ZoneId
import java.util.UUID

internal data class DiaryUiState(
    val entries: List<LocalDiaryEntryEntity> = emptyList(),
    val loading: Boolean = true,
    val loadFailed: Boolean = false,
    val deleted: Boolean = false,
    val limit: Int = 100,
    val draft: DiaryDraft? = null,
    val saving: Boolean = false,
    val error: DiaryError? = null,
    val historyId: String? = null,
    val history: List<LocalDiaryEntryEntity>? = null,
    val historyFailed: Boolean = false
)

internal class LocalDiaryViewModel(
    application: Application,
    private val store: LocalDiaryStore,
    private val savedState: SavedStateHandle,
    private val now: () -> Long = System::currentTimeMillis
) : AndroidViewModel(application) {
    constructor(application: Application, savedState: SavedStateHandle) :
        this(application, (application as CopilotApp).container.localDiary, savedState)

    private val mutableState = MutableStateFlow(DiaryUiState(draft = decodeDraft(savedState["diaryDraft"])))
    val state = mutableState.asStateFlow()
    private var observation: Job? = null
    private var historyJob: Job? = null

    init { reload() }

    fun reload() {
        observation?.cancel()
        val snapshot = mutableState.value
        mutableState.value = snapshot.copy(loading = true, loadFailed = false, entries = emptyList())
        observation = viewModelScope.launch {
            try {
                store.observe(snapshot.deleted, snapshot.limit).collect {
                    mutableState.value = mutableState.value.copy(entries = it, loading = false, loadFailed = false)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutableState.value = mutableState.value.copy(entries = emptyList(), loading = false, loadFailed = true) }
        }
    }

    fun showDeleted(value: Boolean) {
        mutableState.value = mutableState.value.copy(deleted = value, limit = 100)
        reload()
    }

    fun loadMore() {
        mutableState.value = mutableState.value.copy(limit = (mutableState.value.limit + 100).coerceAtMost(1000))
        reload()
    }

    fun start(kind: DiaryKind) {
        if (mutableState.value.saving || mutableState.value.draft != null) return
        setDraft(DiaryDraft(UUID.randomUUID().toString(), kind = kind,
            occurredAtMs = now() / 60_000 * 60_000, zoneId = ZoneId.systemDefault().id))
    }

    fun edit(entry: LocalDiaryEntryEntity) {
        if (!mutableState.value.saving && mutableState.value.draft == null && !entry.deleted) setDraft(entry.toDiaryDraft())
    }

    fun changeDraft(draft: DiaryDraft?) {
        if (mutableState.value.saving) return
        val current = mutableState.value.draft ?: return
        if (draft != null && (draft.entryId != current.entryId || draft.expectedRevision != current.expectedRevision ||
                draft.kind != current.kind || draft.zoneId != current.zoneId || draft.deleted != current.deleted)) return
        setDraft(draft)
    }

    private fun setDraft(draft: DiaryDraft?) {
        savedState["diaryDraft"] = draft?.let(::encodeDraft)
        mutableState.value = mutableState.value.copy(draft = draft, error = null)
    }

    fun save() { mutableState.value.draft?.let { submit(it, closeEditor = true) } }
    fun setDeleted(entry: LocalDiaryEntryEntity, deleted: Boolean) {
        if (mutableState.value.draft != null) return
        submit(entry.toDiaryDraft().copy(deleted = deleted), closeEditor = false)
    }

    private fun submit(draft: DiaryDraft, closeEditor: Boolean) {
        if (mutableState.value.saving) return
        mutableState.value = mutableState.value.copy(saving = true, error = null)
        viewModelScope.launch {
            try {
                when (val result = store.save(draft)) {
                    is DiarySaveResult.Saved -> {
                        if (closeEditor) {
                            savedState["diaryDraft"] = null
                            mutableState.value = mutableState.value.copy(draft = null)
                        }
                    }
                    is DiarySaveResult.Rejected -> mutableState.value = mutableState.value.copy(error = result.reason)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutableState.value = mutableState.value.copy(error = DiaryError.STORAGE) }
            finally { mutableState.value = mutableState.value.copy(saving = false) }
        }
    }

    fun history(entry: LocalDiaryEntryEntity?) {
        historyJob?.cancel()
        mutableState.value = mutableState.value.copy(historyId = entry?.entryId, history = null, historyFailed = false)
        if (entry == null) return
        historyJob = viewModelScope.launch {
            try {
                val revisions = store.history(entry.entryId)
                mutableState.value = mutableState.value.copy(history = revisions)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutableState.value = mutableState.value.copy(historyFailed = true) }
        }
    }
}

private fun encodeDraft(draft: DiaryDraft) = Bundle().apply {
    putString("id", draft.entryId); putInt("revision", draft.expectedRevision)
    putString("kind", draft.kind.name); putLong("at", draft.occurredAtMs); putString("zone", draft.zoneId)
    putString("amount", draft.amount); putString("insulin", draft.insulinName)
    putString("insulinKind", draft.insulinKind?.name); putString("note", draft.note)
    putBoolean("deleted", draft.deleted)
}

private fun decodeDraft(bundle: Bundle?): DiaryDraft? = bundle?.let {
    runCatching {
        DiaryDraft(requireNotNull(it.getString("id")), it.getInt("revision"),
            DiaryKind.valueOf(requireNotNull(it.getString("kind"))), it.getLong("at"),
            requireNotNull(it.getString("zone")), it.getString("amount").orEmpty(), it.getString("insulin").orEmpty(),
            it.getString("insulinKind")?.let(DiaryInsulinKind::valueOf), it.getString("note").orEmpty(), it.getBoolean("deleted"))
    }.getOrNull()
}
