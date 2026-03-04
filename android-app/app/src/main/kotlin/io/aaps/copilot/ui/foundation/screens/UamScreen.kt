package io.aaps.copilot.ui.foundation.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.aaps.copilot.R
import io.aaps.copilot.ui.foundation.design.AppElevation
import io.aaps.copilot.ui.foundation.design.Spacing
import io.aaps.copilot.ui.foundation.format.UiFormatters
import io.aaps.copilot.ui.foundation.theme.AapsCopilotTheme

private val UamSectionShape = RoundedCornerShape(18.dp)
private val UamInfoShape = RoundedCornerShape(12.dp)
private val UamPillShape = RoundedCornerShape(999.dp)

@Composable
fun UamScreen(
    state: UamUiState,
    onMarkCorrect: (String) -> Unit,
    onMarkWrong: (String) -> Unit,
    onMergeWithManual: (String) -> Unit,
    onExportToAaps: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var selectedEvent by remember { mutableStateOf<UamEventUi?>(null) }
    val pendingCount = state.events.count { isPendingState(it.state) }
    ScreenStateLayout(
        loadState = state.loadState,
        isStale = state.isStale,
        errorText = state.errorText,
        emptyText = stringResource(id = R.string.uam_empty)
    ) {
        LazyColumn(
            modifier = modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            item {
                UamSummaryCard(state = state)
            }
            if (pendingCount > 0) {
                item {
                    Surface(
                        shape = UamInfoShape,
                        color = MaterialTheme.colorScheme.tertiaryContainer,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                    ) {
                        Text(
                            text = stringResource(id = R.string.uam_pending_attention_template, pendingCount),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)
                        )
                    }
                }
            }
            if (state.events.isEmpty()) {
                item {
                    UamSectionCard {
                        UamSectionLabel(text = stringResource(id = R.string.section_uam_events))
                        Text(
                            text = stringResource(id = R.string.uam_events_empty),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            } else {
                item {
                    UamSectionLabel(text = stringResource(id = R.string.section_uam_events))
                }
                items(state.events) { event ->
                    UamEventCard(
                        event = event,
                        exportEnabled = state.enableUamExportToAaps,
                        dryRun = state.dryRunExport,
                        onOpenDetails = { selectedEvent = event },
                        onMarkCorrect = { onMarkCorrect(event.id) },
                        onMarkWrong = { onMarkWrong(event.id) },
                        onMergeWithManual = { onMergeWithManual(event.id) },
                        onExportToAaps = { onExportToAaps(event.id) }
                    )
                }
            }
        }
    }
    selectedEvent?.let { selected ->
        val eventId = selected.id
        UamEventBottomSheet(
            event = selected,
            exportEnabled = state.enableUamExportToAaps,
            dryRun = state.dryRunExport,
            onDismiss = { selectedEvent = null },
            onMarkCorrect = {
                onMarkCorrect(eventId)
                selectedEvent = null
            },
            onMarkWrong = {
                onMarkWrong(eventId)
                selectedEvent = null
            },
            onMergeWithManual = {
                onMergeWithManual(eventId)
                selectedEvent = null
            },
            onExportToAaps = {
                onExportToAaps(eventId)
                selectedEvent = null
            }
        )
    }
}

@Composable
private fun UamSummaryCard(state: UamUiState) {
    UamSectionCard {
        UamSectionLabel(text = stringResource(id = R.string.section_uam_inferred))
        val unitG = stringResource(id = R.string.unit_g)

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Spacing.xs)
        ) {
            UamInfoCell(
                modifier = Modifier.weight(1f),
                title = stringResource(id = R.string.label_uam_inferred),
                lines = listOf(
                    "${stringResource(id = R.string.label_active_state)}: ${boolText(state.inferredActive)}",
                    "${stringResource(id = R.string.label_carbs)}: ${UiFormatters.formatGrams(state.inferredCarbsGrams, 1)} $unitG",
                    "${stringResource(id = R.string.label_confidence)}: ${UiFormatters.formatPercent(state.inferredConfidence, 0)}"
                )
            )
            UamInfoCell(
                modifier = Modifier.weight(1f),
                title = stringResource(id = R.string.section_uam_calculated),
                lines = listOf(
                    "${stringResource(id = R.string.label_active_state)}: ${boolText(state.calculatedActive)}",
                    "${stringResource(id = R.string.label_carbs)}: ${UiFormatters.formatGrams(state.calculatedCarbsGrams, 1)} $unitG",
                    "${stringResource(id = R.string.label_confidence)}: ${UiFormatters.formatPercent(state.calculatedConfidence, 0)}"
                )
            )
        }

        Surface(
            shape = UamPillShape,
            color = if (state.enableUamExportToAaps && !state.dryRunExport) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.tertiaryContainer
        ) {
            val text = if (state.enableUamExportToAaps) {
                if (state.dryRunExport) {
                    stringResource(id = R.string.uam_export_enabled_dry_run)
                } else {
                    stringResource(id = R.string.uam_export_enabled_live)
                }
            } else {
                stringResource(id = R.string.uam_export_disabled)
            }
            Text(
                text = text,
                style = MaterialTheme.typography.labelMedium,
                color = if (state.enableUamExportToAaps && !state.dryRunExport) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onTertiaryContainer,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
            )
        }
    }
}

@Composable
private fun UamInfoCell(
    title: String,
    lines: List<String>,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = UamInfoShape,
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 9.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            lines.forEach { line ->
                Text(
                    text = line,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun UamEventCard(
    event: UamEventUi,
    exportEnabled: Boolean,
    dryRun: Boolean,
    onOpenDetails: () -> Unit,
    onMarkCorrect: () -> Unit,
    onMarkWrong: () -> Unit,
    onMergeWithManual: () -> Unit,
    onExportToAaps: () -> Unit
) {
    UamSectionCard(
        modifier = Modifier.clickable(onClick = onOpenDetails),
        borderColor = if (isPendingState(event.state)) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.outlineVariant
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = event.id,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1
            )
            Surface(
                shape = UamPillShape,
                color = stateColorForEvent(event.state)
            ) {
                Text(
                    text = "${event.state} · ${event.mode}",
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp)
                )
            }
        }

        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
            verticalArrangement = Arrangement.spacedBy(Spacing.xs)
        ) {
            val unitG = stringResource(id = R.string.unit_g)
            ConfidencePill(confidence = event.confidence)
            InfoPill("${stringResource(id = R.string.label_mode)}: ${event.mode}")
            InfoPill("${stringResource(id = R.string.label_start_time)}: ${UiFormatters.formatTimestamp(event.ingestionTs)}")
            InfoPill("${stringResource(id = R.string.label_carbs)}: ${UiFormatters.formatGrams(event.carbsDisplayG, 1)} $unitG")
            InfoPill("${stringResource(id = R.string.label_seq)}: ${event.exportSeq}")
        }

        Text(
            text = "${stringResource(id = R.string.label_tag)}: ${event.tag}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        val antiDup = buildAntiDuplicateStatus(event = event, exportEnabled = exportEnabled, dryRun = dryRun)
        Surface(
            shape = UamInfoShape,
            color = MaterialTheme.colorScheme.tertiaryContainer,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        ) {
            Text(
                text = "${stringResource(id = R.string.uam_antidup_status)}: $antiDup",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp)
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Spacing.xs)
        ) {
            OutlinedButton(onClick = onMarkCorrect, modifier = Modifier.weight(1f)) {
                Text(stringResource(id = R.string.uam_mark_correct), fontSize = 12.sp)
            }
            OutlinedButton(onClick = onMarkWrong, modifier = Modifier.weight(1f)) {
                Text(stringResource(id = R.string.uam_mark_wrong), fontSize = 12.sp)
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Spacing.xs)
        ) {
            OutlinedButton(onClick = onMergeWithManual, modifier = Modifier.weight(1f)) {
                Text(stringResource(id = R.string.uam_merge_manual), fontSize = 12.sp)
            }
            OutlinedButton(
                onClick = onExportToAaps,
                enabled = event.exportBlockedReason.isNullOrBlank() && exportEnabled && !dryRun,
                modifier = Modifier.weight(1f)
            ) {
                Text(stringResource(id = R.string.uam_export_to_aaps), fontSize = 12.sp)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun UamEventBottomSheet(
    event: UamEventUi,
    exportEnabled: Boolean,
    dryRun: Boolean,
    onDismiss: () -> Unit,
    onMarkCorrect: () -> Unit,
    onMarkWrong: () -> Unit,
    onMergeWithManual: () -> Unit,
    onExportToAaps: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.md, vertical = Spacing.sm),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            Text(
                text = stringResource(id = R.string.uam_event_details_title),
                style = MaterialTheme.typography.titleLarge
            )
            InfoPill("${stringResource(id = R.string.label_start_time)}: ${UiFormatters.formatTimestamp(event.ingestionTs)}")
            InfoPill("${stringResource(id = R.string.label_carbs)}: ${UiFormatters.formatGrams(event.carbsDisplayG, 1)} ${stringResource(id = R.string.unit_g)}")
            InfoPill("${stringResource(id = R.string.label_confidence)}: ${UiFormatters.formatPercent(event.confidence, 0)}")
            InfoPill("${stringResource(id = R.string.label_mode)}: ${event.mode}")
            InfoPill("${stringResource(id = R.string.label_status)}: ${event.state}")
            if (!event.exportBlockedReason.isNullOrBlank()) {
                InfoPill("${stringResource(id = R.string.uam_antidup_status)}: ${event.exportBlockedReason}")
            }
            val antiDup = buildAntiDuplicateStatus(event = event, exportEnabled = exportEnabled, dryRun = dryRun)
            Surface(
                shape = UamInfoShape,
                color = MaterialTheme.colorScheme.tertiaryContainer,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                Text(
                    text = "${stringResource(id = R.string.uam_antidup_status)}: $antiDup",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Spacing.xs)
            ) {
                OutlinedButton(onClick = onMarkCorrect, modifier = Modifier.weight(1f)) {
                    Text(stringResource(id = R.string.uam_mark_correct), fontSize = 12.sp)
                }
                OutlinedButton(onClick = onMarkWrong, modifier = Modifier.weight(1f)) {
                    Text(stringResource(id = R.string.uam_mark_wrong), fontSize = 12.sp)
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Spacing.xs)
            ) {
                OutlinedButton(onClick = onMergeWithManual, modifier = Modifier.weight(1f)) {
                    Text(stringResource(id = R.string.uam_merge_manual), fontSize = 12.sp)
                }
                OutlinedButton(
                    onClick = onExportToAaps,
                    enabled = event.exportBlockedReason.isNullOrBlank() && exportEnabled && !dryRun,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(stringResource(id = R.string.uam_export_to_aaps), fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
private fun InfoPill(text: String) {
    Surface(
        shape = UamPillShape,
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 6.dp)
        )
    }
}

@Composable
private fun buildAntiDuplicateStatus(
    event: UamEventUi,
    exportEnabled: Boolean,
    dryRun: Boolean
): String {
    return when {
        !exportEnabled -> stringResource(id = R.string.uam_antidup_export_disabled)
        dryRun -> stringResource(id = R.string.uam_antidup_dry_run)
        event.manualCarbsNearby -> stringResource(id = R.string.uam_antidup_manual_carbs_nearby)
        event.manualCobActive -> stringResource(id = R.string.uam_antidup_manual_cob_active)
        !event.exportBlockedReason.isNullOrBlank() -> event.exportBlockedReason
        else -> stringResource(id = R.string.uam_antidup_ready)
    }
}

@Composable
private fun UamSectionCard(
    modifier: Modifier = Modifier,
    borderColor: Color? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val resolvedBorderColor = borderColor ?: MaterialTheme.colorScheme.outlineVariant
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = UamSectionShape,
        border = BorderStroke(1.dp, resolvedBorderColor),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = AppElevation.level1)
    ) {
        Column(
            modifier = Modifier.padding(Spacing.md),
            verticalArrangement = Arrangement.spacedBy(Spacing.xs),
            content = content
        )
    }
}

@Composable
private fun UamSectionLabel(text: String) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.7.sp),
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun boolText(active: Boolean?): String {
    return when (active) {
        true -> stringResource(id = R.string.status_on_short)
        false -> stringResource(id = R.string.status_off_short)
        null -> "--"
    }
}

@Composable
private fun ConfidencePill(confidence: Double?) {
    val level = when {
        (confidence ?: 0.0) >= 0.80 -> 2
        (confidence ?: 0.0) >= 0.60 -> 1
        else -> 0
    }
    val label = when (level) {
        2 -> stringResource(id = R.string.uam_confidence_high)
        1 -> stringResource(id = R.string.uam_confidence_medium)
        else -> stringResource(id = R.string.uam_confidence_low)
    }
    val (bg, fg) = when (level) {
        2 -> MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.onSecondaryContainer
        1 -> MaterialTheme.colorScheme.tertiaryContainer to MaterialTheme.colorScheme.onTertiaryContainer
        else -> MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
    }
    Surface(
        shape = UamPillShape,
        color = bg,
        border = BorderStroke(1.dp, fg.copy(alpha = 0.35f))
    ) {
        Text(
            text = "$label (${UiFormatters.formatPercent(confidence, 0)})",
            style = MaterialTheme.typography.labelSmall,
            color = fg,
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 6.dp)
        )
    }
}

private fun isPendingState(state: String): Boolean {
    val normalized = state.uppercase()
    return normalized.contains("SUSPECTED") || normalized.contains("PENDING")
}

@Composable
private fun stateColorForEvent(state: String): Color {
    return when {
        state.contains("CONFIRMED", ignoreCase = true) -> MaterialTheme.colorScheme.secondaryContainer
        state.contains("FINAL", ignoreCase = true) -> MaterialTheme.colorScheme.primaryContainer
        state.contains("MERGED", ignoreCase = true) -> MaterialTheme.colorScheme.surfaceVariant
        else -> MaterialTheme.colorScheme.tertiaryContainer
    }
}

@Preview(showBackground = true)
@Composable
private fun UamScreenPreview() {
    AapsCopilotTheme {
        UamScreen(
            state = UamUiState(
                loadState = ScreenLoadState.READY,
                isStale = false,
                inferredActive = true,
                inferredCarbsGrams = 24.0,
                inferredConfidence = 0.72,
                calculatedActive = true,
                calculatedCarbsGrams = 19.0,
                calculatedConfidence = 0.66,
                enableUamExportToAaps = true,
                dryRunExport = false,
                events = listOf(
                    UamEventUi(
                        id = "evt-1",
                        state = "CONFIRMED",
                        mode = "BOOST",
                        createdAt = System.currentTimeMillis() - 30 * 60_000,
                        updatedAt = System.currentTimeMillis(),
                        ingestionTs = System.currentTimeMillis() - 25 * 60_000,
                        carbsDisplayG = 25.0,
                        confidence = 0.71,
                        exportSeq = 1,
                        exportedGrams = 15.0,
                        tag = "UAM_ENGINE|id=evt-1|seq=1|ver=1|mode=BOOST|",
                        manualCarbsNearby = false,
                        manualCobActive = false,
                        exportBlockedReason = null
                    )
                )
            ),
            onMarkCorrect = {},
            onMarkWrong = {},
            onMergeWithManual = {},
            onExportToAaps = {}
        )
    }
}
