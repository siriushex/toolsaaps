package io.aaps.copilot.ui.foundation.screens

import android.app.Application
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import io.aaps.copilot.CopilotApp
import io.aaps.copilot.R
import io.aaps.copilot.historyexport.ClinicalJsonExportStore
import io.aaps.copilot.historyexport.ClinicalJsonExporter
import io.aaps.copilot.historyexport.ClinicalJsonPeriod
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

internal enum class JsonExportPhase { IDLE, RUNNING, SAVED, FAILED, CANCELLED, CLEANUP_FAILED }
internal data class JsonExportState(val phase: JsonExportPhase = JsonExportPhase.IDLE, val rows: Long = 0, val bytes: Long = 0)

internal class ClinicalJsonExportViewModel(app: Application, private val saved: SavedStateHandle,
    private val store: ClinicalJsonExporter) : AndroidViewModel(app) {
    constructor(app: Application, saved: SavedStateHandle) : this(app, saved,
        (app as CopilotApp).container.let { ClinicalJsonExportStore(app, it.db, it.settingsStore) })
    private val mutable = MutableStateFlow(JsonExportState())
    val state = mutable.asStateFlow()
    private var job: Job? = null
    init {
        saved.get<String>("jsonActiveUri")?.let { interrupted ->
            mutable.value = JsonExportState(JsonExportPhase.RUNNING)
            job = viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
                withContext(NonCancellable) {
                    val cleaned = store.removeIncomplete(Uri.parse(interrupted))
                    saved.remove<String>("jsonActiveUri")
                    mutable.value = JsonExportState(if (cleaned) JsonExportPhase.FAILED else JsonExportPhase.CLEANUP_FAILED)
                }
            }
        }
    }
    fun export(uri: Uri, period: ClinicalJsonPeriod) {
        if (mutable.value.phase == JsonExportPhase.RUNNING) return
        if (uri.scheme != "content") { pickerFailed(); return }
        saved["jsonActiveUri"] = uri.toString()
        mutable.value = JsonExportState(JsonExportPhase.RUNNING)
        job = viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            var complete = false
            var cancelled = false
            try {
                val bytes = store.export(uri, period) { rows -> mutable.value = mutable.value.copy(rows = rows) }
                complete = true
                mutable.value = JsonExportState(JsonExportPhase.SAVED, bytes = bytes)
            } catch (_: CancellationException) { cancelled = true }
            catch (_: Exception) { /* Only fixed UI errors; no clinical contents or provider paths in logs. */ }
            finally {
                if (!complete) withContext(NonCancellable) {
                    val cleaned = store.removeIncomplete(uri)
                    mutable.value = JsonExportState(if (!cleaned) JsonExportPhase.CLEANUP_FAILED else
                        if (cancelled) JsonExportPhase.CANCELLED else JsonExportPhase.FAILED)
                }
                saved.remove<String>("jsonActiveUri")
            }
        }
    }
    fun cancel() { job?.cancel() }
    fun pickerFailed() { mutable.value = JsonExportState(JsonExportPhase.FAILED) }
}

@Composable
internal fun ClinicalJsonExport(model: ClinicalJsonExportViewModel = viewModel(
    viewModelStoreOwner = checkNotNull(LocalActivity.current as? ComponentActivity)
)) {
    val state by model.state.collectAsStateWithLifecycle()
    var pendingPeriod by rememberSaveable { mutableStateOf<String?>(null) }
    val currentModel by rememberUpdatedState(model)
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val period = pendingPeriod?.let { name -> ClinicalJsonPeriod.entries.find { it.name == name } }
        pendingPeriod = null
        if (uri != null && period != null) currentModel.export(uri, period)
    }
    ClinicalJsonExportContent(state, pendingPeriod != null, onExport = { period ->
        pendingPeriod = period.name
        try { picker.launch("copilot-history-${period.days ?: "full"}-${LocalDate.now()}.json") }
        catch (_: Exception) { pendingPeriod = null; model.pickerFailed() }
    }, onCancel = model::cancel)
}

@Composable
internal fun ClinicalJsonExportContent(state: JsonExportState, picking: Boolean = false,
    onExport: (ClinicalJsonPeriod) -> Unit, onCancel: () -> Unit) {
    var show by rememberSaveable { mutableStateOf(false) }
    var selected by rememberSaveable { mutableStateOf(ClinicalJsonPeriod.DAYS_30.name) }
    val running = state.phase == JsonExportPhase.RUNNING
    Column(Modifier.fillMaxWidth()) {
        TextButton({ show = true }, enabled = !running && !picking, modifier = Modifier.testTag("jsonReport")) {
            Icon(Icons.Default.FileDownload, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.json_report_title))
        }
        if (running) {
            LinearProgressIndicator(Modifier.fillMaxWidth().testTag("jsonReportProgress"))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.json_report_progress, state.rows), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                TextButton(onCancel) { Text(stringResource(android.R.string.cancel)) }
            }
        } else if (state.phase != JsonExportPhase.IDLE) {
            Text(when (state.phase) {
                JsonExportPhase.SAVED -> stringResource(R.string.json_report_saved, state.bytes / 1024)
                JsonExportPhase.CANCELLED -> stringResource(R.string.json_report_cancelled)
                JsonExportPhase.CLEANUP_FAILED -> stringResource(R.string.json_report_partial)
                else -> stringResource(R.string.json_report_failed)
            }, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("jsonReportResult"))
        }
    }
    if (show) AlertDialog(onDismissRequest = { show = false }, title = { Text(stringResource(R.string.json_report_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(stringResource(R.string.json_report_disclosure), style = MaterialTheme.typography.bodySmall)
                ClinicalJsonPeriod.entries.forEach { period ->
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("jsonPeriod${period.name}")
                        .selectable(selected == period.name, role = Role.RadioButton, onClick = { selected = period.name }),
                        verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected == period.name, onClick = null)
                        Spacer(Modifier.width(8.dp))
                        Text(if (period.days == null) stringResource(R.string.json_report_full) else stringResource(R.string.json_report_days, period.days))
                    }
                }
            }
        },
        confirmButton = { TextButton({ show = false; onExport(ClinicalJsonPeriod.valueOf(selected)) }, Modifier.testTag("jsonReportSave")) {
            Text(stringResource(R.string.json_report_save))
        } },
        dismissButton = { TextButton({ show = false }) { Text(stringResource(android.R.string.cancel)) } })
}
