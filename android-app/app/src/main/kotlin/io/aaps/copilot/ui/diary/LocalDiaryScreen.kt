package io.aaps.copilot.ui.diary

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.text.format.DateFormat
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.aaps.copilot.R
import io.aaps.copilot.data.local.entity.LocalDiaryEntryEntity
import io.aaps.copilot.data.repository.*
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
internal fun LocalDiaryRoute(model: LocalDiaryViewModel = viewModel()) {
    val state by model.state.collectAsStateWithLifecycle()
    LocalDiaryScreen(state, model::start, model::edit, model::changeDraft, model::save,
        model::setDeleted, model::showDeleted, model::reload, model::loadMore, model::history)
}

@Composable
internal fun LocalDiaryScreen(
    state: DiaryUiState,
    onStart: (DiaryKind) -> Unit,
    onEdit: (LocalDiaryEntryEntity) -> Unit,
    onDraft: (DiaryDraft?) -> Unit,
    onSave: () -> Unit,
    onDeleted: (LocalDiaryEntryEntity, Boolean) -> Unit,
    onFilter: (Boolean) -> Unit,
    onReload: () -> Unit,
    onMore: () -> Unit,
    onHistory: (LocalDiaryEntryEntity?) -> Unit
) {
    var deleteRequest by remember { mutableStateOf<LocalDiaryEntryEntity?>(null) }
    if (state.draft != null) {
        DiaryEditor(state.draft, state.saving, state.error, onDraft, onSave)
    } else {
        Column(Modifier.fillMaxSize().testTag("diary_screen"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.diary_local_only), style = MaterialTheme.typography.bodySmall)
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val minimumButtonWidth = (80 + 52 * LocalDensity.current.fontScale).dp
                if (maxWidth < minimumButtonWidth * 2 + 8.dp) {
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        DiaryAddButton(DiaryKind.MEAL, !state.saving, onStart, Modifier.fillMaxWidth())
                        DiaryAddButton(DiaryKind.INJECTION, !state.saving, onStart, Modifier.fillMaxWidth())
                    }
                } else {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        DiaryAddButton(DiaryKind.MEAL, !state.saving, onStart, Modifier.weight(1f))
                        DiaryAddButton(DiaryKind.INJECTION, !state.saving, onStart, Modifier.weight(1f))
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(state.deleted, onFilter, enabled = !state.saving, modifier = Modifier.testTag("diary_deleted_filter"))
                Text(stringResource(R.string.diary_deleted))
            }
            state.error?.let { Text(stringResource(errorText(it)), color = MaterialTheme.colorScheme.error) }
            when {
                state.loading -> LinearProgressIndicator(Modifier.fillMaxWidth())
                state.loadFailed -> {
                    Text(stringResource(R.string.diary_load_failed))
                    TextButton(onReload) { Text(stringResource(R.string.setup_retry)) }
                }
                else -> LazyColumn(Modifier.weight(1f).testTag("diary_list"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (state.entries.isEmpty()) item { Text(stringResource(R.string.diary_empty)) }
                    items(state.entries, key = { it.entryId }) { entry ->
                        Column(Modifier.fillMaxWidth().testTag("diary_row_${entry.entryId}")) {
                            Text(entrySummary(entry), style = MaterialTheme.typography.titleMedium)
                            Text(formatDiaryTime(entry.occurredAtMs, entry.recordedZoneId), style = MaterialTheme.typography.bodySmall)
                            if (entry.note.isNotBlank()) Text(entry.note)
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                IconButton({ onHistory(entry) }, Modifier.testTag("diary_history_${entry.entryId}")) {
                                    Icon(Icons.Default.History, stringResource(R.string.diary_history))
                                }
                                if (entry.deleted) {
                                    TextButton({ onDeleted(entry, false) }, enabled = !state.saving) { Text(stringResource(R.string.diary_restore)) }
                                } else {
                                    IconButton({ onEdit(entry) }, enabled = !state.saving, modifier = Modifier.testTag("diary_edit_${entry.entryId}")) {
                                        Icon(Icons.Default.Edit, stringResource(R.string.diary_edit))
                                    }
                                    IconButton({ deleteRequest = entry }, enabled = !state.saving, modifier = Modifier.testTag("diary_delete_${entry.entryId}")) {
                                        Icon(Icons.Default.Delete, stringResource(R.string.diary_delete))
                                    }
                                }
                            }
                            HorizontalDivider()
                        }
                    }
                    if (state.entries.size == state.limit) item {
                        Text(stringResource(R.string.diary_recent_limit, state.limit))
                        if (state.limit < 1000) TextButton(onMore) { Text(stringResource(R.string.diary_more)) }
                    }
                }
            }
        }
    }
    deleteRequest?.let { entry ->
        AlertDialog(onDismissRequest = { deleteRequest = null }, title = { Text(stringResource(R.string.diary_delete)) },
            text = { Text(stringResource(R.string.diary_delete_detail)) },
            confirmButton = { TextButton({ deleteRequest = null; onDeleted(entry, true) }, Modifier.testTag("diary_confirm_delete"), enabled = !state.saving) { Text(stringResource(R.string.diary_delete)) } },
            dismissButton = { TextButton({ deleteRequest = null }) { Text(stringResource(R.string.action_cancel)) } })
    }
    if (state.historyId != null) {
        AlertDialog(onDismissRequest = { onHistory(null) }, title = { Text(stringResource(R.string.diary_history)) },
            text = {
                Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    when {
                        state.historyFailed -> Text(stringResource(R.string.diary_load_failed))
                        state.history == null -> CircularProgressIndicator()
                        else -> {
                            if (state.history.size == 100) Text(stringResource(R.string.diary_history_limit))
                            state.history.forEach { row ->
                                Text(stringResource(R.string.diary_revision, row.revision,
                                    formatDiaryTime(row.recordedAtMs, row.recordedZoneId)))
                                Text(entrySummary(row))
                                Text(formatDiaryTime(row.occurredAtMs, row.recordedZoneId))
                                if (row.note.isNotBlank()) Text(row.note)
                                if (row.deleted) Text(stringResource(R.string.diary_deleted))
                                HorizontalDivider()
                            }
                        }
                    }
                }
            }, confirmButton = { TextButton({ onHistory(null) }) { Text(stringResource(R.string.diary_close)) } })
    }
}

@Composable
private fun DiaryAddButton(kind: DiaryKind, enabled: Boolean, onStart: (DiaryKind) -> Unit, modifier: Modifier) {
    val meal = kind == DiaryKind.MEAL
    OutlinedButton({ onStart(kind) }, modifier.testTag(if (meal) "diary_add_meal" else "diary_add_injection"), enabled = enabled) {
        Icon(if (meal) Icons.Default.Restaurant else Icons.Default.Medication, null)
        Spacer(Modifier.width(6.dp))
        Text(stringResource(if (meal) R.string.diary_meal else R.string.diary_insulin))
    }
}

@Composable
private fun DiaryEditor(draft: DiaryDraft, saving: Boolean, error: DiaryError?, onDraft: (DiaryDraft?) -> Unit, onSave: () -> Unit) {
    val context = LocalContext.current
    var confirmDiscard by remember { mutableStateOf(false) }
    BackHandler { if (!saving) confirmDiscard = true }
    Column(Modifier.fillMaxSize().imePadding().testTag("diary_editor"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(if (draft.kind == DiaryKind.MEAL) R.string.diary_meal_entry else R.string.diary_injection_entry),
            style = MaterialTheme.typography.titleLarge)
        Text(stringResource(R.string.diary_local_only), style = MaterialTheme.typography.bodySmall)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            val date = Instant.ofEpochMilli(draft.occurredAtMs).atZone(ZoneId.of(draft.zoneId))
            OutlinedButton(onClick = {
                DatePickerDialog(context, { _, year, month, day ->
                    TimePickerDialog(context, { _, hour, minute ->
                        val selected = java.time.LocalDate.of(year, month + 1, day).atTime(hour, minute).atZone(ZoneId.of(draft.zoneId))
                        onDraft(draft.copy(occurredAtMs = selected.toInstant().toEpochMilli()))
                    }, date.hour, date.minute, DateFormat.is24HourFormat(context)).show()
                }, date.year, date.monthValue - 1, date.dayOfMonth).apply {
                    datePicker.maxDate = System.currentTimeMillis()
                }.show()
            }, enabled = !saving, modifier = Modifier.fillMaxWidth().testTag("diary_time")) {
                Icon(Icons.Default.Schedule, null); Spacer(Modifier.width(6.dp))
                Text(formatDiaryTime(draft.occurredAtMs, draft.zoneId))
            }
            OutlinedTextField(draft.amount, { if (it.length <= 12) onDraft(draft.copy(amount = it)) },
                label = { Text(stringResource(if (draft.kind == DiaryKind.MEAL) R.string.diary_carbs_label else R.string.diary_dose_label)) },
                enabled = !saving, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth().testTag("diary_amount"), isError = error == DiaryError.AMOUNT)
            if (draft.kind == DiaryKind.INJECTION) {
                OutlinedTextField(draft.insulinName, { if (it.length <= 100) onDraft(draft.copy(insulinName = it)) },
                    label = { Text(stringResource(R.string.diary_insulin_name)) }, singleLine = true, enabled = !saving,
                    modifier = Modifier.fillMaxWidth().testTag("diary_insulin_name"), isError = error == DiaryError.INSULIN)
                DiaryInsulinKind.entries.forEach { kind ->
                    FilterChip(draft.insulinKind == kind, { onDraft(draft.copy(insulinKind = kind)) },
                        label = { Text(stringResource(insulinKindText(kind))) }, enabled = !saving,
                        modifier = Modifier.testTag("diary_kind_${kind.name}"))
                }
            }
            OutlinedTextField(draft.note, { if (it.length <= 500) onDraft(draft.copy(note = it)) },
                label = { Text(stringResource(R.string.diary_note)) }, enabled = !saving, minLines = 2, maxLines = 5,
                modifier = Modifier.fillMaxWidth().testTag("diary_note"))
        }
        error?.let { Text(stringResource(errorText(it)), color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("diary_error")) }
        Button(onSave, Modifier.fillMaxWidth().testTag("diary_save"), enabled = !saving) {
            Icon(Icons.Default.Save, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.diary_save))
        }
        TextButton({ confirmDiscard = true }, enabled = !saving, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.action_cancel)) }
    }
    if (confirmDiscard) AlertDialog(onDismissRequest = { confirmDiscard = false },
        title = { Text(stringResource(R.string.diary_discard)) }, text = { Text(stringResource(R.string.diary_discard_detail)) },
        confirmButton = { TextButton({ confirmDiscard = false; onDraft(null) }) { Text(stringResource(R.string.diary_discard)) } },
        dismissButton = { TextButton({ confirmDiscard = false }) { Text(stringResource(R.string.diary_continue)) } })
}

@Composable
private fun entrySummary(entry: LocalDiaryEntryEntity): String {
    val amount = diaryAmountText(entry.amountMilli).replace('.', java.text.DecimalFormatSymbols.getInstance().decimalSeparator)
    return if (entry.kind == DiaryKind.MEAL.name) stringResource(R.string.diary_meal_value, amount)
    else stringResource(R.string.diary_insulin_value, entry.insulinName, amount,
        stringResource(insulinKindText(DiaryInsulinKind.valueOf(entry.insulinKind))))
}

private fun formatDiaryTime(at: Long, zone: String): String = DateTimeFormatter
    .ofPattern("d MMM yyyy, HH:mm z", Locale.getDefault()).format(Instant.ofEpochMilli(at).atZone(ZoneId.of(zone)))

private fun insulinKindText(kind: DiaryInsulinKind) = when (kind) {
    DiaryInsulinKind.RAPID -> R.string.diary_rapid
    DiaryInsulinKind.LONG -> R.string.diary_long
    DiaryInsulinKind.OTHER -> R.string.diary_other
}

private fun errorText(error: DiaryError) = when (error) {
    DiaryError.AMOUNT -> R.string.diary_invalid_amount
    DiaryError.TIME -> R.string.diary_invalid_time
    DiaryError.INSULIN -> R.string.diary_invalid_insulin
    DiaryError.NOTE -> R.string.diary_invalid_note
    DiaryError.CONFLICT -> R.string.diary_conflict
    DiaryError.INVALID -> R.string.diary_invalid
    DiaryError.STORAGE -> R.string.diary_storage_failed
}
