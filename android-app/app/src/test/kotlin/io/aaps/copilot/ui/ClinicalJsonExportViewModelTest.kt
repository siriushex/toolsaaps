package io.aaps.copilot.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import io.aaps.copilot.historyexport.ClinicalJsonExporter
import io.aaps.copilot.historyexport.ClinicalJsonPeriod
import io.aaps.copilot.ui.foundation.screens.ClinicalJsonExportViewModel
import io.aaps.copilot.ui.foundation.screens.JsonExportPhase
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.RuntimeEnvironment

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class ClinicalJsonExportViewModelTest {
    private val uri = Uri.parse("content://local.documents/report-one")
    private class Store : ClinicalJsonExporter {
        val completion = CompletableDeferred<Long>()
        var calls = 0; var deletes = 0; var cleanup = true
        override suspend fun export(uri: Uri, period: ClinicalJsonPeriod, progress: (Long) -> Unit): Long {
            calls++; progress(100); return completion.await()
        }
        override suspend fun removeIncomplete(uri: Uri): Boolean { deletes++; return cleanup }
    }
    @Test fun repeatedClickCannotDuplicateExportAndSuccessDoesNotDeleteDocument() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val store = Store(); val saved = SavedStateHandle()
            val model = ClinicalJsonExportViewModel(RuntimeEnvironment.getApplication(), saved, store)
            model.export(uri, ClinicalJsonPeriod.DAYS_30); model.export(uri, ClinicalJsonPeriod.FULL)
            runCurrent()
            assertEquals(1, store.calls); assertEquals(100, model.state.value.rows)
            store.completion.complete(1000); advanceUntilIdle()
            assertEquals(JsonExportPhase.SAVED, model.state.value.phase)
            assertEquals(0, store.deletes); assertFalse(saved.contains("jsonActiveUri"))
        } finally { Dispatchers.resetMain() }
    }
    @Test fun cancelledOrFailedCopyCleansOnlyItsDocumentAndReportsCleanupFailure() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            for (cancel in listOf(true, false)) {
                val store = Store().apply { cleanup = cancel }
                val model = ClinicalJsonExportViewModel(RuntimeEnvironment.getApplication(), SavedStateHandle(), store)
                model.export(uri, ClinicalJsonPeriod.DAYS_7); runCurrent()
                if (cancel) model.cancel() else store.completion.completeExceptionally(java.io.IOException())
                advanceUntilIdle()
                assertEquals(1, store.deletes)
                assertEquals(if (cancel) JsonExportPhase.CANCELLED else JsonExportPhase.CLEANUP_FAILED, model.state.value.phase)
            }
        } finally { Dispatchers.resetMain() }
    }
    @Test fun restoredInterruptedExportIsCleanedWithoutStartingAnotherExport() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val store = Store(); val saved = SavedStateHandle(mapOf("jsonActiveUri" to uri.toString()))
            val model = ClinicalJsonExportViewModel(RuntimeEnvironment.getApplication(), saved, store)
            model.cancel(); advanceUntilIdle()
            assertEquals(JsonExportPhase.FAILED, model.state.value.phase)
            assertEquals(0, store.calls); assertEquals(1, store.deletes)
            assertFalse(saved.contains("jsonActiveUri"))
        } finally { Dispatchers.resetMain() }
    }
    @Test fun immediateCancellationStillCleansTheCreatedDocument() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val store = Store(); val saved = SavedStateHandle()
            val model = ClinicalJsonExportViewModel(RuntimeEnvironment.getApplication(), saved, store)
            model.export(uri, ClinicalJsonPeriod.FULL); model.cancel(); advanceUntilIdle()
            assertEquals(JsonExportPhase.CANCELLED, model.state.value.phase)
            assertEquals(1, store.deletes); assertFalse(saved.contains("jsonActiveUri"))
        } finally { Dispatchers.resetMain() }
    }
    @Test fun nonDocumentUriCannotStartOrDeleteAnExport() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val store = Store(); val model = ClinicalJsonExportViewModel(RuntimeEnvironment.getApplication(), SavedStateHandle(), store)
            model.export(Uri.parse("file:///tmp/unrelated"), ClinicalJsonPeriod.FULL); advanceUntilIdle()
            assertEquals(JsonExportPhase.FAILED, model.state.value.phase)
            assertEquals(0, store.calls); assertEquals(0, store.deletes)
        } finally { Dispatchers.resetMain() }
    }
}
