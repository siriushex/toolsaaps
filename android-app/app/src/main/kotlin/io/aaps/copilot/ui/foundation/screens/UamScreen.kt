package io.aaps.copilot.ui.foundation.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.aaps.copilot.config.UiStyle
import io.aaps.copilot.R
import io.aaps.copilot.ui.foundation.design.AppElevation
import io.aaps.copilot.ui.foundation.design.Spacing
import io.aaps.copilot.ui.foundation.format.UiFormatters
import io.aaps.copilot.ui.foundation.theme.AapsCopilotTheme
import io.aaps.copilot.ui.foundation.theme.LocalUiStyle

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
    val midnightGlass = LocalUiStyle.current == UiStyle.MIDNIGHT_GLASS
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
                UamSummaryCard(state = state, midnightGlass = midnightGlass)
            }
            if (pendingCount > 0) {
                item {
                    Surface(
                        shape = UamInfoShape,
                        color = if (midnightGlass) Color(0x1FFFB020) else MaterialTheme.colorScheme.tertiaryContainer,
                        border = BorderStroke(1.dp, if (midnightGlass) Color(0x33FFB020) else MaterialTheme.colorScheme.outlineVariant)
                    ) {
                        Text(
                            text = stringResource(id = R.string.uam_pending_attention_template, pendingCount),
                            style = MaterialTheme.typography.labelMedium,
                            color = if (midnightGlass) Color(0xFFFFD180) else MaterialTheme.colorScheme.onTertiaryContainer,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)
                        )
                    }
                }
            }
            if (state.events.isEmpty()) {
                item {
                    UamSectionCard {
                        UamSectionLabel(
                            text = stringResource(id = R.string.section_uam_events),
                            infoText = stringResource(id = R.string.uam_info_events_section)
                        )
                        Text(
                            text = stringResource(id = R.string.uam_events_empty),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            } else {
                item {
                    UamSectionLabel(
                        text = stringResource(id = R.string.section_uam_events),
                        infoText = stringResource(id = R.string.uam_info_events_section)
                    )
                }
                items(state.events) { event ->
                    UamEventCard(
                        event = event,
                        midnightGlass = midnightGlass,
                        exportEnabled = state.enableUamExportToAaps,
                        dryRun = state.dryRunExport,
                        onOpenDetails = { selectedEvent = event },
                        onMarkCorrect = { onMarkCorrect(event.id) },
                        onMarkWrong = { onMarkWrong(event.id) },
                        onMergeWithManual = { onMergeWithManual(event.id) },
                        onExportToAaps = { onExportToAaps(event.id) }
                    )
                }
                item {
                    UamStatsRow(state = state, midnightGlass = midnightGlass)
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
fun AuditUamPanel(
    state: UamUiState,
    onMarkCorrect: (String) -> Unit,
    onMarkWrong: (String) -> Unit,
    onMergeWithManual: (String) -> Unit,
    onExportToAaps: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var selectedEvent by remember { mutableStateOf<UamEventUi?>(null) }
    val pendingCount = state.events.count { isPendingState(it.state) }
    val midnightGlass = LocalUiStyle.current == UiStyle.MIDNIGHT_GLASS

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm)
    ) {
        UamSummaryCard(state = state, midnightGlass = midnightGlass)
        if (pendingCount > 0) {
            Surface(
                shape = UamInfoShape,
                color = if (midnightGlass) Color(0x1FFFB020) else MaterialTheme.colorScheme.tertiaryContainer,
                border = BorderStroke(1.dp, if (midnightGlass) Color(0x33FFB020) else MaterialTheme.colorScheme.outlineVariant)
            ) {
                Text(
                    text = stringResource(id = R.string.uam_pending_attention_template, pendingCount),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (midnightGlass) Color(0xFFFFD180) else MaterialTheme.colorScheme.onTertiaryContainer,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)
                )
            }
        }
        if (state.events.isEmpty()) {
            UamSectionCard {
                UamSectionLabel(
                    text = stringResource(id = R.string.section_uam_events),
                    infoText = stringResource(id = R.string.uam_info_events_section)
                )
                Text(
                    text = stringResource(id = R.string.uam_events_empty),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        } else {
            UamSectionLabel(
                text = stringResource(id = R.string.section_uam_events),
                infoText = stringResource(id = R.string.uam_info_events_section)
            )
            state.events.forEach { event ->
                UamEventCard(
                    event = event,
                    midnightGlass = midnightGlass,
                    exportEnabled = state.enableUamExportToAaps,
                    dryRun = state.dryRunExport,
                    onOpenDetails = { selectedEvent = event },
                    onMarkCorrect = { onMarkCorrect(event.id) },
                    onMarkWrong = { onMarkWrong(event.id) },
                    onMergeWithManual = { onMergeWithManual(event.id) },
                    onExportToAaps = { onExportToAaps(event.id) }
                )
            }
            UamStatsRow(state = state, midnightGlass = midnightGlass)
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
private fun UamSummaryCard(
    state: UamUiState,
    midnightGlass: Boolean
) {
    UamSectionCard {
        UamSectionLabel(
            text = stringResource(id = R.string.section_uam_inferred),
            infoText = stringResource(id = R.string.uam_info_inferred_section)
        )
        val unitG = stringResource(id = R.string.unit_g)

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Spacing.xs)
        ) {
            UamInfoCell(
                midnightGlass = midnightGlass,
                modifier = Modifier.weight(1f),
                title = stringResource(id = R.string.label_uam_inferred),
                lines = listOf(
                    "${stringResource(id = R.string.label_active_state)}: ${boolText(state.inferredActive)}",
                    "${stringResource(id = R.string.label_carbs)}: ${UiFormatters.formatGrams(state.inferredCarbsGrams, 1)} $unitG",
                    "${stringResource(id = R.string.label_confidence)}: ${UiFormatters.formatPercent(state.inferredConfidence, 0)}"
                )
            )
            UamInfoCell(
                midnightGlass = midnightGlass,
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
            color = when {
                midnightGlass && state.enableUamExportToAaps && !state.dryRunExport -> Color(0x2200E676)
                midnightGlass -> Color(0x221D4ED8)
                state.enableUamExportToAaps && !state.dryRunExport -> MaterialTheme.colorScheme.secondaryContainer
                else -> MaterialTheme.colorScheme.tertiaryContainer
            }
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
                color = when {
                    midnightGlass && state.enableUamExportToAaps && !state.dryRunExport -> Color(0xFF9FFFB0)
                    midnightGlass -> Color(0xFF8DB6FF)
                    state.enableUamExportToAaps && !state.dryRunExport -> MaterialTheme.colorScheme.onSecondaryContainer
                    else -> MaterialTheme.colorScheme.onTertiaryContainer
                },
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
            )
        }
    }
}

@Composable
private fun UamInfoCell(
    midnightGlass: Boolean,
    title: String,
    lines: List<String>,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = UamInfoShape,
        color = if (midnightGlass) Color(0xAA101D38) else MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(1.dp, if (midnightGlass) Color(0x1FFFFFFF) else MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 9.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium,
                color = if (midnightGlass) Color(0xFFF8FAFC) else MaterialTheme.colorScheme.onSurface
            )
            lines.forEach { line ->
                Text(
                    text = line,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (midnightGlass) Color(0xFFB5C0D8) else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun UamEventCard(
    event: UamEventUi,
    midnightGlass: Boolean,
    exportEnabled: Boolean,
    dryRun: Boolean,
    onOpenDetails: () -> Unit,
    onMarkCorrect: () -> Unit,
    onMarkWrong: () -> Unit,
    onMergeWithManual: () -> Unit,
    onExportToAaps: () -> Unit
) {
    val visual = eventVisuals(event = event, midnightGlass = midnightGlass)
    UamSectionCard(
        modifier = Modifier.clickable(onClick = onOpenDetails),
        borderColor = visual.borderColor,
        containerColor = visual.containerColor
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top
        ) {
            Row(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                verticalAlignment = Alignment.Top
            ) {
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(UamPillShape)
                        .background(visual.iconBg)
                        .padding(4.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = visual.icon,
                        contentDescription = null,
                        tint = visual.iconTint
                    )
                }
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(Spacing.xxs)
                ) {
                    Text(
                        text = visual.title,
                        style = MaterialTheme.typography.titleMedium,
                        color = if (midnightGlass) Color(0xFFF8FAFC) else MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = event.id,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (midnightGlass) Color(0xFFB5C0D8) else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                }
            }
            Icon(
                imageVector = Icons.Default.ExpandMore,
                contentDescription = null,
                tint = if (midnightGlass) Color(0xFFE5EEFF) else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
            verticalArrangement = Arrangement.spacedBy(Spacing.xs)
        ) {
            val unitG = stringResource(id = R.string.unit_g)
            StatusChip(
                text = if (event.mode.equals("BOOST", ignoreCase = true)) {
                    "${event.state} • BOOST"
                } else {
                    event.state
                },
                container = visual.primaryChipBg,
                content = visual.primaryChipFg
            )
            ConfidencePill(confidence = event.confidence, midnightGlass = midnightGlass)
            if (event.mode.isNotBlank()) {
                StatusChip(
                    text = event.mode,
                    container = visual.modeChipBg,
                    content = visual.modeChipFg
                )
            }
            StatusChip(
                text = "${stringResource(id = R.string.label_start_time)}: ${UiFormatters.formatTimestamp(event.ingestionTs)}",
                container = if (midnightGlass) Color(0xFF374151) else MaterialTheme.colorScheme.surfaceVariant,
                content = if (midnightGlass) Color(0xFFF8FAFC) else MaterialTheme.colorScheme.onSurfaceVariant
            )
            StatusChip(
                text = "${UiFormatters.formatGrams(event.carbsDisplayG, 1)} $unitG",
                container = if (midnightGlass) Color(0xFF334155) else MaterialTheme.colorScheme.surfaceVariant,
                content = if (midnightGlass) Color(0xFFF8FAFC) else MaterialTheme.colorScheme.onSurfaceVariant
            )
            StatusChip(
                text = "${stringResource(id = R.string.label_seq)}: ${event.exportSeq}",
                container = if (midnightGlass) Color(0xFF334155) else MaterialTheme.colorScheme.surfaceVariant,
                content = if (midnightGlass) Color(0xFFD7E3F8) else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Text(
            text = "${stringResource(id = R.string.label_tag)}: ${event.tag}",
            style = MaterialTheme.typography.bodySmall,
            color = if (midnightGlass) Color(0xFF93A5C3) else MaterialTheme.colorScheme.onSurfaceVariant
        )

        val antiDup = buildAntiDuplicateStatus(event = event, exportEnabled = exportEnabled, dryRun = dryRun)
        Surface(
            shape = UamInfoShape,
            color = if (midnightGlass) Color(0x221D4ED8) else MaterialTheme.colorScheme.tertiaryContainer,
            border = BorderStroke(1.dp, if (midnightGlass) Color(0x332563EB) else MaterialTheme.colorScheme.outlineVariant)
        ) {
            Text(
                text = "${stringResource(id = R.string.uam_antidup_status)}: $antiDup",
                style = MaterialTheme.typography.labelMedium,
                color = if (midnightGlass) Color(0xFFDCEBFF) else MaterialTheme.colorScheme.onTertiaryContainer,
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
private fun StatusChip(
    text: String,
    container: Color,
    content: Color
) {
    Surface(
        shape = UamPillShape,
        color = container,
        border = BorderStroke(1.dp, content.copy(alpha = 0.18f))
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = content,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
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
    containerColor: Color? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val midnightGlass = LocalUiStyle.current == UiStyle.MIDNIGHT_GLASS
    val resolvedBorderColor = borderColor ?: if (midnightGlass) Color(0x1FFFFFFF) else MaterialTheme.colorScheme.outlineVariant
    val resolvedContainerColor = containerColor ?: if (midnightGlass) Color(0xCC0E1C36) else MaterialTheme.colorScheme.surface
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = if (midnightGlass) RoundedCornerShape(28.dp) else UamSectionShape,
        border = BorderStroke(1.dp, resolvedBorderColor),
        colors = CardDefaults.cardColors(containerColor = resolvedContainerColor),
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
private fun UamSectionLabel(
    text: String,
    infoText: String? = null
) {
    var showInfo by rememberSaveable(text, infoText) { mutableStateOf(false) }
    val midnightGlass = LocalUiStyle.current == UiStyle.MIDNIGHT_GLASS
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = text.uppercase(),
            style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.7.sp),
            color = if (midnightGlass) Color(0xFFD0D7E8) else MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (!infoText.isNullOrBlank()) {
            Surface(
                shape = UamPillShape,
                color = if (midnightGlass) Color(0x221D4ED8) else Color.Transparent
            ) {
                IconButton(onClick = { showInfo = true }) {
                    Icon(
                        imageVector = Icons.Default.Info,
                        contentDescription = stringResource(id = R.string.settings_info_button_cd, text),
                        tint = if (midnightGlass) Color(0xFF5CA9FF) else MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
    if (showInfo && !infoText.isNullOrBlank()) {
        AlertDialog(
            onDismissRequest = { showInfo = false },
            title = { Text(text = text) },
            text = { Text(text = infoText) },
            confirmButton = {
                TextButton(onClick = { showInfo = false }) {
                    Text(text = stringResource(id = R.string.action_close))
                }
            }
        )
    }
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
private fun ConfidencePill(confidence: Double?, midnightGlass: Boolean = false) {
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
        2 -> if (midnightGlass) Color(0x221D4ED8) to Color(0xFF7FB3FF) else MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.onSecondaryContainer
        1 -> if (midnightGlass) Color(0x664F2D00) to Color(0xFFFFC94A) else MaterialTheme.colorScheme.tertiaryContainer to MaterialTheme.colorScheme.onTertiaryContainer
        else -> if (midnightGlass) Color(0x66B42318) to Color(0xFFFF8A80) else MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
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

private data class UamEventVisuals(
    val title: String,
    val icon: ImageVector,
    val iconTint: Color,
    val iconBg: Color,
    val containerColor: Color,
    val borderColor: Color,
    val primaryChipBg: Color,
    val primaryChipFg: Color,
    val modeChipBg: Color,
    val modeChipFg: Color
)

@Composable
private fun eventVisuals(event: UamEventUi, midnightGlass: Boolean): UamEventVisuals {
    val isConfirmed = event.state.contains("CONFIRMED", ignoreCase = true) || event.state.contains("FINAL", ignoreCase = true)
    val isPending = isPendingState(event.state)
    return when {
        isConfirmed -> UamEventVisuals(
            title = stringResource(id = R.string.uam_event_confirmed_title),
            icon = Icons.Default.CheckCircle,
            iconTint = if (midnightGlass) Color(0xFF00E676) else MaterialTheme.colorScheme.secondary,
            iconBg = if (midnightGlass) Color(0x2200E676) else MaterialTheme.colorScheme.secondaryContainer,
            containerColor = if (midnightGlass) Color(0x55103A35) else MaterialTheme.colorScheme.surface,
            borderColor = if (midnightGlass) Color(0x3325C685) else MaterialTheme.colorScheme.outlineVariant,
            primaryChipBg = if (midnightGlass) Color(0x221D4ED8) else MaterialTheme.colorScheme.secondaryContainer,
            primaryChipFg = if (midnightGlass) Color(0xFF8DB6FF) else MaterialTheme.colorScheme.onSecondaryContainer,
            modeChipBg = if (midnightGlass) Color(0xFF334155) else MaterialTheme.colorScheme.surfaceVariant,
            modeChipFg = if (midnightGlass) Color(0xFFF8FAFC) else MaterialTheme.colorScheme.onSurfaceVariant
        )
        isPending -> UamEventVisuals(
            title = stringResource(id = R.string.uam_event_detected_title),
            icon = Icons.Default.WarningAmber,
            iconTint = if (midnightGlass) Color(0xFFFFC107) else MaterialTheme.colorScheme.tertiary,
            iconBg = if (midnightGlass) Color(0x22FFC107) else MaterialTheme.colorScheme.tertiaryContainer,
            containerColor = if (midnightGlass) Color(0x55312735) else MaterialTheme.colorScheme.surface,
            borderColor = if (midnightGlass) Color(0x33FFB020) else MaterialTheme.colorScheme.tertiary,
            primaryChipBg = if (midnightGlass) Color(0x221D4ED8) else MaterialTheme.colorScheme.tertiaryContainer,
            primaryChipFg = if (midnightGlass) Color(0xFF8DB6FF) else MaterialTheme.colorScheme.onTertiaryContainer,
            modeChipBg = if (midnightGlass) Color(0x664F2D00) else MaterialTheme.colorScheme.surfaceVariant,
            modeChipFg = if (midnightGlass) Color(0xFFFFC94A) else MaterialTheme.colorScheme.onSurfaceVariant
        )
        else -> UamEventVisuals(
            title = stringResource(id = R.string.uam_event_detected_title),
            icon = Icons.Default.Info,
            iconTint = if (midnightGlass) Color(0xFF4DA3FF) else MaterialTheme.colorScheme.primary,
            iconBg = if (midnightGlass) Color(0x221D4ED8) else MaterialTheme.colorScheme.primaryContainer,
            containerColor = if (midnightGlass) Color(0x5512275A) else MaterialTheme.colorScheme.surface,
            borderColor = if (midnightGlass) Color(0x332563EB) else MaterialTheme.colorScheme.outlineVariant,
            primaryChipBg = if (midnightGlass) Color(0x664F2D00) else MaterialTheme.colorScheme.tertiaryContainer,
            primaryChipFg = if (midnightGlass) Color(0xFFFFC94A) else MaterialTheme.colorScheme.onTertiaryContainer,
            modeChipBg = if (midnightGlass) Color(0xFF334155) else MaterialTheme.colorScheme.surfaceVariant,
            modeChipFg = if (midnightGlass) Color(0xFFF8FAFC) else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun UamStatsRow(
    state: UamUiState,
    midnightGlass: Boolean
) {
    val now = System.currentTimeMillis()
    val todayCount = state.events.count { now - it.createdAt <= 24L * 60L * 60L * 1000L }
    val weekCount = state.events.count { now - it.createdAt <= 7L * 24L * 60L * 60L * 1000L }
    val accuracyPct = ((state.events.mapNotNull { it.confidence }.average()).takeIf { !it.isNaN() } ?: 0.0) * 100.0

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
    ) {
        UamStatTile(
            modifier = Modifier.weight(1f),
            title = stringResource(id = R.string.label_today),
            value = todayCount.toString(),
            container = if (midnightGlass) Color(0xFF10275A) else MaterialTheme.colorScheme.primaryContainer
        )
        UamStatTile(
            modifier = Modifier.weight(1f),
            title = stringResource(id = R.string.label_this_week),
            value = weekCount.toString(),
            container = if (midnightGlass) Color(0xFF162544) else MaterialTheme.colorScheme.surfaceVariant
        )
        UamStatTile(
            modifier = Modifier.weight(1f),
            title = stringResource(id = R.string.label_accuracy),
            value = "${accuracyPct.toInt()}%",
            container = if (midnightGlass) Color(0xFF083B38) else MaterialTheme.colorScheme.secondaryContainer
        )
    }
}

@Composable
private fun UamStatTile(
    title: String,
    value: String,
    container: Color,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(22.dp),
        color = container,
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f))
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(Spacing.xs)
        ) {
            Text(
                text = title.uppercase(),
                style = MaterialTheme.typography.labelMedium,
                color = Color(0xFFD7E3F8)
            )
            Text(
                text = value,
                style = MaterialTheme.typography.headlineMedium,
                color = Color.White
            )
        }
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
