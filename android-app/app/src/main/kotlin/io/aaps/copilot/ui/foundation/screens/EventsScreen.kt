package io.aaps.copilot.ui.foundation.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import io.aaps.copilot.domain.events.CompensationEvent
import io.aaps.copilot.domain.events.CompensationEventLifecycle
import io.aaps.copilot.domain.events.CompensationEventType
import io.aaps.copilot.domain.events.EventSource
import io.aaps.copilot.domain.events.FemaleCyclePhase
import io.aaps.copilot.domain.profile.PhysiologicalSex
import io.aaps.copilot.R
import io.aaps.copilot.ui.EventIconResources
import io.aaps.copilot.ui.ContextEventCommandUiDisposition
import io.aaps.copilot.ui.ManualCompensationEventRoute

@Composable
internal fun EventsDialog(
    events: List<CompensationEvent>,
    nowTs: Long,
    sex: PhysiologicalSex,
    showOnGraph: Boolean,
    onShowOnGraphChange: (Boolean) -> Unit,
    onDismiss: () -> Unit,
    onSave: (CompensationEvent, Long?, (ContextEventCommandUiDisposition) -> Unit) -> Unit,
    onClose: (CompensationEvent) -> Unit,
    onDelete: (CompensationEvent) -> Unit,
    onOpenMealAction: () -> Unit,
    onOpenPlannedActivity: () -> Unit,
    onOpenBloodCheck: () -> Unit,
    onOpenReadOnlyDiagnostic: () -> Unit
) {
    var editing by remember { mutableStateOf<CompensationEvent?>(null) }
    var draftType by remember { mutableStateOf<CompensationEventType?>(null) }
    var editorOpen by remember { mutableStateOf(false) }
    var editorSubmitting by remember { mutableStateOf(false) }
    var editorFeedbackRes by remember { mutableStateOf<Int?>(null) }
    var sourceFilter by rememberSaveable { mutableStateOf(EventSourceFilter.ALL) }
    val sections = eventTimelineSections(events, nowTs, sourceFilter)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.events_title)) },
        text = {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 560.dp)
                    .testTag("eventsDialogContent"),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                item(key = "filters") {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        EventSourceFilter.entries.forEach { option ->
                            val label = when (option) {
                                EventSourceFilter.ALL -> R.string.events_filter_all
                                EventSourceFilter.MANUAL -> R.string.events_filter_manual
                                EventSourceFilter.AAPS -> R.string.events_filter_aaps
                                EventSourceFilter.AUTO -> R.string.events_filter_auto
                            }
                            FilterChip(
                                selected = sourceFilter == option,
                                onClick = { sourceFilter = option },
                                label = { Text(stringResource(label)) }
                            )
                        }
                    }
                }
                item(key = "graph-toggle") {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(stringResource(R.string.events_show_on_graph), modifier = Modifier.weight(1f))
                        Switch(
                            checked = showOnGraph,
                            onCheckedChange = onShowOnGraphChange,
                            modifier = Modifier.testTag("eventsShowOnGraph")
                        )
                    }
                }
                item(key = "actions") {
                    EventActionGrid(
                        items = eventActionItems(sex),
                        onSelect = { item ->
                            when (item.route) {
                                ManualCompensationEventRoute.MEAL_ACTION -> onOpenMealAction()
                                ManualCompensationEventRoute.PLANNED_ACTIVITY -> onOpenPlannedActivity()
                                ManualCompensationEventRoute.BLOOD_CHECK -> onOpenBloodCheck()
                                ManualCompensationEventRoute.READ_ONLY_DIAGNOSTIC -> onOpenReadOnlyDiagnostic()
                                ManualCompensationEventRoute.CONTEXT -> {
                                    editing = null
                                    draftType = item.type
                                    editorFeedbackRes = null
                                    editorOpen = true
                                }
                            }
                        }
                    )
                }
                item(key = "active-header") {
                    Text(
                        stringResource(R.string.events_active_section),
                        modifier = Modifier.testTag("eventsActiveSection")
                    )
                }
                items(sections.active, key = { "active:${it.localId}" }) { event ->
                    EventRow(
                        event = event,
                        nowTs = nowTs,
                        canManage = canManageExistingContextEvent(event),
                        canEdit = canEditExistingContextEvent(event, sex),
                        onEdit = {
                            editing = event
                            draftType = event.type
                            editorFeedbackRes = null
                            editorOpen = true
                        },
                        onClose = { onClose(event) },
                        onDelete = { onDelete(event) }
                    )
                }
                item(key = "recent-header") {
                    Text(
                        stringResource(R.string.events_recent_24h_section),
                        modifier = Modifier.testTag("eventsRecentSection")
                    )
                }
                items(sections.recent24h, key = { "recent:${it.localId}" }) { event ->
                    EventRow(
                        event = event,
                        nowTs = nowTs,
                        canManage = canManageExistingContextEvent(event),
                        canEdit = canEditExistingContextEvent(event, sex),
                        onEdit = {
                            editing = event
                            draftType = event.type
                            editorFeedbackRes = null
                            editorOpen = true
                        },
                        onClose = { onClose(event) },
                        onDelete = { onDelete(event) }
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.events_close)) } }
    )
    EventEditorDialog(
        initial = editing,
        preselectedType = draftType,
        sex = sex,
        visible = editorOpen,
        submitting = editorSubmitting,
        feedbackMessageRes = editorFeedbackRes,
        onDismiss = {
            if (!editorSubmitting) {
                editorOpen = false
                editing = null
                draftType = null
                editorFeedbackRes = null
            }
        },
        onSave = { event, expectedRevision ->
            editorSubmitting = true
            onSave(event, expectedRevision) { disposition ->
                editorSubmitting = false
                editorFeedbackRes = disposition.messageRes
                if (disposition.closeEditor) {
                    editorOpen = false
                    editing = null
                    draftType = null
                    editorFeedbackRes = null
                }
            }
        }
    )
}

@Composable
private fun EventActionGrid(
    items: List<EventActionItem>,
    onSelect: (EventActionItem) -> Unit
) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        maxItemsInEachRow = 3
    ) {
        items.forEach { item ->
            val label = stringResource(eventTypeLabelRes(item.type))
            val description = stringResource(eventActionDescriptionRes(item.route), label)
            OutlinedButton(
                onClick = { onSelect(item) },
                enabled = item.available,
                modifier = Modifier
                    .widthIn(min = 88.dp, max = 104.dp)
                    .heightIn(min = 64.dp)
                    .semantics { contentDescription = description }
                    .testTag("eventAction:${item.type.name}"),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp, vertical = 4.dp)
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        painter = painterResource(EventIconResources.byType.getValue(item.type).resourceId),
                        contentDescription = null,
                        tint = Color.Unspecified,
                        modifier = Modifier.size(20.dp)
                    )
                    Text(
                        text = label,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
        }
    }
}

internal fun eventTypeLabelRes(type: CompensationEventType): Int = when (type) {
    CompensationEventType.MEAL -> R.string.events_type_meal
    CompensationEventType.ACTIVITY -> R.string.events_type_activity
    CompensationEventType.STRESS -> R.string.events_type_stress
    CompensationEventType.ILLNESS -> R.string.events_type_illness
    CompensationEventType.SLEEP -> R.string.events_type_sleep
    CompensationEventType.HORMONAL -> R.string.events_type_hormonal
    CompensationEventType.MEDICATION_STEROID -> R.string.events_type_medication
    CompensationEventType.ALCOHOL -> R.string.events_type_alcohol
    CompensationEventType.SENSOR_CALIBRATION -> R.string.events_type_sensor
    CompensationEventType.INFUSION_PUMP_INSULIN -> R.string.events_type_infusion
    CompensationEventType.CUSTOM -> R.string.events_type_custom
    CompensationEventType.MENSTRUAL_CYCLE -> R.string.events_type_cycle
}

private fun eventActionDescriptionRes(route: ManualCompensationEventRoute): Int = when (route) {
    ManualCompensationEventRoute.CONTEXT -> R.string.events_action_context_description
    ManualCompensationEventRoute.MEAL_ACTION -> R.string.events_action_meal_description
    ManualCompensationEventRoute.PLANNED_ACTIVITY -> R.string.events_action_activity_description
    ManualCompensationEventRoute.BLOOD_CHECK -> R.string.events_action_blood_check_description
    ManualCompensationEventRoute.READ_ONLY_DIAGNOSTIC -> R.string.events_action_diagnostic_description
}

internal fun eventSourceLabelRes(source: EventSource): Int = when (source) {
    EventSource.USER -> R.string.events_source_manual
    EventSource.AAPS -> R.string.events_source_aaps
    EventSource.AUTOMATIC -> R.string.events_source_automatic
}

private fun femaleCyclePhaseLabelRes(phase: FemaleCyclePhase): Int = when (phase) {
    FemaleCyclePhase.MENSTRUATION -> R.string.events_cycle_phase_menstruation
    FemaleCyclePhase.FOLLICULAR -> R.string.events_cycle_phase_follicular
    FemaleCyclePhase.OVULATION -> R.string.events_cycle_phase_ovulation
    FemaleCyclePhase.LUTEAL -> R.string.events_cycle_phase_luteal
}

@Composable
private fun EventRow(
    event: CompensationEvent,
    nowTs: Long,
    canManage: Boolean,
    canEdit: Boolean,
    onEdit: () -> Unit,
    onClose: () -> Unit,
    onDelete: () -> Unit
) {
    val icon = EventIconResources.byType[event.type]
    val eventTitle = event.title.ifBlank { stringResource(eventTypeLabelRes(event.type)) }
    val lifecycleText = stringResource(
        when (event.lifecycleAt(nowTs)) {
            CompensationEventLifecycle.UPCOMING -> R.string.events_upcoming
            CompensationEventLifecycle.ACTIVE -> R.string.events_active
            CompensationEventLifecycle.ELAPSED -> R.string.events_ended
            CompensationEventLifecycle.CLOSED -> R.string.events_closed
        }
    )
    Column(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (icon != null) {
                Icon(
                    painter = painterResource(icon.resourceId),
                    contentDescription = eventTitle,
                    tint = Color.Unspecified
                )
            }
            Column(Modifier.weight(1f)) {
                Text(eventTitle)
                Text(lifecycleText)
            }
        }
        if (canManage) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (canEdit) {
                    TextButton(onClick = onEdit, modifier = Modifier.testTag("eventEdit:${event.localId}")) {
                        Text(stringResource(R.string.events_edit))
                    }
                }
                if (event.isActiveAt(nowTs)) {
                    TextButton(onClick = onClose, modifier = Modifier.testTag("eventFinish:${event.localId}")) {
                        Text(stringResource(R.string.events_finish))
                    }
                }
                val deleteDescription = stringResource(R.string.events_delete_description)
                IconButton(
                    onClick = onDelete,
                    modifier = Modifier
                        .testTag("eventDelete:${event.localId}")
                        .semantics { contentDescription = deleteDescription }
                ) {
                    Icon(Icons.Default.Delete, contentDescription = null)
                }
            }
        }
    }
}

@Composable
private fun EventEditorDialog(
    initial: CompensationEvent?,
    preselectedType: CompensationEventType?,
    sex: PhysiologicalSex,
    visible: Boolean,
    submitting: Boolean,
    feedbackMessageRes: Int?,
    onDismiss: () -> Unit,
    onSave: (CompensationEvent, Long?) -> Unit
) {
    if (!visible) return
    val creatableTypes = creatableContextEventTypes(sex)
    val type = initial?.type ?: preselectedType?.takeIf { it in creatableTypes } ?: creatableTypes.first()
    val draftStartTs = remember(initial) { initial?.startTs ?: System.currentTimeMillis() }
    var title by remember(initial) { mutableStateOf(initial?.title.orEmpty()) }
    var note by remember(initial) { mutableStateOf(initial?.note.orEmpty()) }
    var cyclePhase by remember(initial) {
        mutableStateOf(initial?.femaleCyclePhase() ?: FemaleCyclePhase.MENSTRUATION)
    }
    val canSave = !submitting && type in creatableTypes && note.length <= 500
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (initial == null) R.string.events_add else R.string.events_edit_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(eventTypeLabelRes(type)))
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it.take(60) },
                    modifier = Modifier.testTag("eventEditorTitle"),
                    label = { Text(stringResource(R.string.events_title_field)) },
                    singleLine = true
                )
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it.take(500) },
                    modifier = Modifier.testTag("eventEditorNote"),
                    label = { Text(stringResource(R.string.events_note_field)) }
                )
                if (type == CompensationEventType.MENSTRUAL_CYCLE) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        menstrualCyclePhaseOptions().forEach { phase ->
                            FilterChip(
                                selected = cyclePhase == phase,
                                onClick = { cyclePhase = phase },
                                label = { Text(stringResource(femaleCyclePhaseLabelRes(phase))) }
                            )
                        }
                    }
                }
                feedbackMessageRes?.let { messageRes ->
                    Text(
                        text = stringResource(messageRes),
                        modifier = Modifier.testTag("eventEditorFeedback")
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = canSave,
                onClick = {
                    val event = initial?.copy(
                        title = title,
                        note = note.ifBlank { null },
                        revision = initial.revision
                    ) ?: CompensationEvent(
                        localId = "manual:$draftStartTs",
                        startTs = draftStartTs,
                        endTs = draftStartTs,
                        type = type,
                        title = title,
                        note = note.ifBlank { null }
                    )
                    onSave(
                        if (event.type == CompensationEventType.MENSTRUAL_CYCLE) {
                            event.withFemaleCyclePhase(cyclePhase)
                        } else {
                            event
                        },
                        initial?.revision
                    )
                }
            ) { Text(stringResource(R.string.events_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.events_cancel)) } }
    )
}
