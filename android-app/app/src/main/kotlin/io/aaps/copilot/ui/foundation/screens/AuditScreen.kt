package io.aaps.copilot.ui.foundation.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.aaps.copilot.R
import io.aaps.copilot.ui.foundation.design.AppElevation
import io.aaps.copilot.ui.foundation.design.Spacing
import io.aaps.copilot.ui.foundation.format.UiFormatters
import io.aaps.copilot.ui.foundation.theme.AapsCopilotTheme
import java.util.Locale

private val AuditSectionShape = RoundedCornerShape(18.dp)
private val AuditInfoShape = RoundedCornerShape(12.dp)
private val AuditPillShape = RoundedCornerShape(999.dp)
private enum class AuditContentTab { LOG, UAM }

@Composable
fun AuditScreen(
    state: AuditUiState,
    uamState: UamUiState,
    onSelectWindow: (AuditWindowUi) -> Unit,
    onOnlyErrorsChange: (Boolean) -> Unit,
    onMarkUamCorrect: (String) -> Unit,
    onMarkUamWrong: (String) -> Unit,
    onMergeUamWithManual: (String) -> Unit,
    onExportUamToAaps: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val expanded = remember { mutableStateListOf<String>() }
    var contentTab by rememberSaveable { mutableStateOf(AuditContentTab.LOG) }
    val successCount = state.rows.count { it.level.equals("INFO", ignoreCase = true) || it.level.equals("OK", ignoreCase = true) }
    val warningCount = state.rows.count { it.level.equals("WARN", ignoreCase = true) || it.summary.contains("warning", ignoreCase = true) }
    val errorCount = state.rows.count { it.level.equals("ERROR", ignoreCase = true) || it.summary.contains("failed", ignoreCase = true) }

    ScreenStateLayout(
        loadState = state.loadState,
        isStale = state.isStale,
        errorText = state.errorText,
        emptyText = stringResource(id = R.string.audit_empty)
    ) {
        LazyColumn(
            modifier = modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            item {
                FiltersCard(
                    state = state,
                    contentTab = contentTab,
                    onContentTabChange = { contentTab = it },
                    onSelectWindow = onSelectWindow,
                    onOnlyErrorsChange = onOnlyErrorsChange
                )
            }
            if (contentTab == AuditContentTab.LOG) {
                item { AuditSectionLabel(text = stringResource(id = R.string.section_audit_recent)) }
                item {
                    AuditSummaryCard(
                        total = state.rows.size,
                        successCount = successCount,
                        warningCount = warningCount,
                        errorCount = errorCount
                    )
                }
                if (state.rows.isEmpty()) {
                    item {
                        AuditSectionCard {
                            Text(
                                text = stringResource(id = R.string.audit_empty),
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                } else {
                    items(state.rows) { row ->
                        val isExpanded = expanded.contains(row.id)
                        AuditRowCard(
                            row = row,
                            expanded = isExpanded,
                            onToggle = {
                                if (isExpanded) expanded.remove(row.id) else expanded.add(row.id)
                            }
                        )
                    }
                }
            } else {
                item {
                    AuditUamPanel(
                        state = uamState,
                        onMarkCorrect = onMarkUamCorrect,
                        onMarkWrong = onMarkUamWrong,
                        onMergeWithManual = onMergeUamWithManual,
                        onExportToAaps = onExportUamToAaps
                    )
                }
            }
        }
    }
}

@Composable
private fun FiltersCard(
    state: AuditUiState,
    contentTab: AuditContentTab,
    onContentTabChange: (AuditContentTab) -> Unit,
    onSelectWindow: (AuditWindowUi) -> Unit,
    onOnlyErrorsChange: (Boolean) -> Unit
) {
    AuditSectionCard {
        AuditSectionLabel(text = stringResource(id = R.string.section_audit_filters))

        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            FilterChip(
                selected = contentTab == AuditContentTab.LOG,
                onClick = { onContentTabChange(AuditContentTab.LOG) },
                label = { Text(stringResource(id = R.string.audit_tab_log)) }
            )
            FilterChip(
                selected = contentTab == AuditContentTab.UAM,
                onClick = { onContentTabChange(AuditContentTab.UAM) },
                label = { Text(stringResource(id = R.string.audit_tab_uam)) }
            )
        }

        if (contentTab == AuditContentTab.LOG) {
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                listOf(AuditWindowUi.H6, AuditWindowUi.H24, AuditWindowUi.D7).forEach { window ->
                    FilterChip(
                        selected = state.window == window,
                        onClick = { onSelectWindow(window) },
                        label = { Text(window.label) }
                    )
                }
                FilterChip(
                    selected = state.onlyErrors,
                    onClick = { onOnlyErrorsChange(!state.onlyErrors) },
                    label = { Text(stringResource(id = R.string.audit_only_errors)) }
                )
            }
        }
    }
}

@Composable
private fun AuditRowCard(
    row: AuditItemUi,
    expanded: Boolean,
    onToggle: () -> Unit
) {
    val expandRotation = if (expanded) 180f else 0f
    AuditSectionCard(
        modifier = Modifier.clickable(onClick = onToggle)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = UiFormatters.formatTimestamp(row.ts),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
                verticalAlignment = Alignment.CenterVertically
            ) {
                LevelPill(level = row.level)
                Icon(
                    imageVector = Icons.Default.ExpandMore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.graphicsLayer(rotationZ = expandRotation)
                )
            }
        }

        Text(
            text = row.summary,
            style = MaterialTheme.typography.bodyMedium
        )

        Row(
            horizontalArrangement = Arrangement.spacedBy(Spacing.xs)
        ) {
            InfoPill(text = row.source)
            if (!row.idempotencyKey.isNullOrBlank()) {
                InfoPill(text = stringResource(id = R.string.label_idempotency_key))
            }
            if (!row.payloadSummary.isNullOrBlank()) {
                InfoPill(text = stringResource(id = R.string.label_payload))
            }
        }

        if (expanded) {
            Surface(
                shape = AuditInfoShape,
                color = MaterialTheme.colorScheme.surfaceVariant,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(Spacing.xxs)
                ) {
                    Text(text = "${stringResource(id = R.string.label_context)}: ${row.context}", style = MaterialTheme.typography.bodySmall)
                    row.idempotencyKey?.let {
                        Text(text = "${stringResource(id = R.string.label_idempotency_key)}: $it", style = MaterialTheme.typography.bodySmall)
                    }
                    row.payloadSummary?.let {
                        Text(text = "${stringResource(id = R.string.label_payload_summary)}: $it", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

@Composable
private fun LevelPill(level: String) {
    val normalized = level.uppercase(Locale.US)
    val visual = when {
        normalized == "ERROR" -> AuditLevelVisual(
            icon = Icons.Default.Error,
            text = stringResource(id = R.string.status_alert),
            background = MaterialTheme.colorScheme.errorContainer,
            border = MaterialTheme.colorScheme.outlineVariant,
            foreground = MaterialTheme.colorScheme.onErrorContainer
        )
        normalized == "WARN" || normalized == "WARNING" -> AuditLevelVisual(
            icon = Icons.Default.Warning,
            text = stringResource(id = R.string.status_warn),
            background = MaterialTheme.colorScheme.tertiaryContainer,
            border = MaterialTheme.colorScheme.outlineVariant,
            foreground = MaterialTheme.colorScheme.onTertiaryContainer
        )
        normalized == "INFO" -> AuditLevelVisual(
            icon = Icons.Default.Info,
            text = stringResource(id = R.string.status_info),
            background = MaterialTheme.colorScheme.primaryContainer,
            border = MaterialTheme.colorScheme.outlineVariant,
            foreground = MaterialTheme.colorScheme.onPrimaryContainer
        )
        else -> AuditLevelVisual(
            icon = Icons.Default.CheckCircle,
            text = stringResource(id = R.string.status_ok),
            background = MaterialTheme.colorScheme.secondaryContainer,
            border = MaterialTheme.colorScheme.outlineVariant,
            foreground = MaterialTheme.colorScheme.onSecondaryContainer
        )
    }
    Surface(
        shape = AuditPillShape,
        color = visual.background,
        border = BorderStroke(1.dp, visual.border)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
            horizontalArrangement = Arrangement.spacedBy(Spacing.xxs),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = visual.icon,
                contentDescription = null,
                tint = visual.foreground
            )
            Text(
                text = visual.text,
                style = MaterialTheme.typography.labelSmall,
                color = visual.foreground
            )
        }
    }
}

@Composable
private fun AuditSummaryCard(
    total: Int,
    successCount: Int,
    warningCount: Int,
    errorCount: Int
) {
    AuditSectionCard {
        AuditSectionLabel(text = stringResource(id = R.string.audit_summary_title))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Spacing.xs)
        ) {
            AuditSummaryTile(
                title = stringResource(id = R.string.audit_summary_total),
                value = total.toString(),
                icon = Icons.Default.Info,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.weight(1f)
            )
            AuditSummaryTile(
                title = stringResource(id = R.string.audit_summary_warn),
                value = warningCount.toString(),
                icon = Icons.Default.Warning,
                tint = MaterialTheme.colorScheme.onTertiaryContainer,
                modifier = Modifier.weight(1f)
            )
            AuditSummaryTile(
                title = stringResource(id = R.string.audit_summary_error),
                value = errorCount.toString(),
                icon = Icons.Default.Error,
                tint = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.weight(1f)
            )
        }
        Text(
            text = stringResource(id = R.string.audit_summary_info_template, successCount),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun AuditSummaryTile(
    title: String,
    value: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    tint: Color,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = AuditInfoShape,
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 9.dp, vertical = 7.dp),
            verticalArrangement = Arrangement.spacedBy(Spacing.xxs)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.xxs)
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = tint
                )
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                text = value,
                style = MaterialTheme.typography.titleMedium,
                color = tint
            )
        }
    }
}

private data class AuditLevelVisual(
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val text: String,
    val background: Color,
    val border: Color,
    val foreground: Color
)

@Composable
private fun InfoPill(text: String) {
    Surface(
        shape = AuditPillShape,
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp)
        )
    }
}

@Composable
private fun AuditSectionCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = AuditSectionShape,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
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
private fun AuditSectionLabel(text: String) {
    var showInfo by rememberSaveable(text) { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = text.uppercase(),
            style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.7.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        IconButton(onClick = { showInfo = true }) {
            Icon(
                imageVector = Icons.Default.Info,
                contentDescription = stringResource(id = R.string.settings_info_button_cd, text),
                tint = MaterialTheme.colorScheme.primary
            )
        }
    }
    if (showInfo) {
        AlertDialog(
            onDismissRequest = { showInfo = false },
            title = { Text(text = text) },
            text = {
                Text(
                    text = stringResource(
                        id = R.string.audit_info_section_generic,
                        text
                    )
                )
            },
            confirmButton = {
                TextButton(onClick = { showInfo = false }) {
                    Text(text = stringResource(id = R.string.action_close))
                }
            }
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun AuditScreenPreview() {
    AapsCopilotTheme {
        AuditScreen(
            state = AuditUiState(
                loadState = ScreenLoadState.READY,
                isStale = false,
                window = AuditWindowUi.H24,
                onlyErrors = false,
                rows = listOf(
                    AuditItemUi(
                        id = "audit:1",
                        ts = System.currentTimeMillis(),
                        source = "action",
                        level = "INFO",
                        summary = "temp_target 5.5 mmol",
                        context = "deliveryChannel=nightscout",
                        idempotencyKey = "idp:123",
                        payloadSummary = "{...}"
                    )
                )
            ),
            uamState = UamUiState(loadState = ScreenLoadState.READY, isStale = false),
            onSelectWindow = {},
            onOnlyErrorsChange = {},
            onMarkUamCorrect = {},
            onMarkUamWrong = {},
            onMergeUamWithManual = {},
            onExportUamToAaps = {}
        )
    }
}
