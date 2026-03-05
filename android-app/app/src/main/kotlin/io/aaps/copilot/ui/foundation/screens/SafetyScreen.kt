package io.aaps.copilot.ui.foundation.screens

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
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
import io.aaps.copilot.ui.foundation.theme.AapsCopilotTheme
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val SafetySectionShape = RoundedCornerShape(18.dp)
private val SafetyInfoShape = RoundedCornerShape(12.dp)
private val SafetyPillShape = RoundedCornerShape(999.dp)

@Composable
fun SafetyScreen(
    state: SafetyUiState,
    onKillSwitchToggle: (Boolean) -> Unit,
    onSafetyBoundsChange: (Double, Double) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier
) {
    ScreenStateLayout(
        loadState = state.loadState,
        isStale = state.isStale,
        errorText = state.errorText,
        emptyText = stringResource(id = R.string.safety_empty)
    ) {
        LazyColumn(
            modifier = modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            item {
                KillSwitchCard(
                    enabled = state.killSwitchEnabled,
                    onToggle = onKillSwitchToggle
                )
            }
            item {
                LimitsCard(
                    state = state,
                    onSafetyBoundsChange = onSafetyBoundsChange
                )
            }
            state.aiTuningStatus?.let { tuning ->
                item {
                    AiTuningStatusCard(status = tuning)
                }
            }
            if (state.cooldownStatusLines.isNotEmpty()) {
                item {
                    CooldownCard(lines = state.cooldownStatusLines)
                }
            }
            item {
                ChecklistSection(items = state.checklist)
            }
            item {
                SafetySummaryCard(
                    killSwitchEnabled = state.killSwitchEnabled,
                    checksPassed = state.checklist.count { it.ok },
                    checksTotal = state.checklist.size
                )
            }
        }
    }
}

@Composable
private fun AiTuningStatusCard(status: AiTuningStatusUi) {
    val normalizedState = status.state.trim().uppercase(Locale.US)
    val (statusIcon, statusColor) = when (normalizedState) {
        "ACTIVE" -> Icons.Default.CheckCircle to MaterialTheme.colorScheme.secondaryContainer
        "STALE" -> Icons.Default.Warning to MaterialTheme.colorScheme.tertiaryContainer
        else -> Icons.Default.Error to MaterialTheme.colorScheme.errorContainer
    }
    val stateLabel = when (normalizedState) {
        "ACTIVE" -> stringResource(id = R.string.ai_tuning_state_active)
        "STALE" -> stringResource(id = R.string.ai_tuning_state_stale)
        else -> stringResource(id = R.string.ai_tuning_state_blocked)
    }

    SafetySectionCard {
        SafetySectionLabel(
            text = stringResource(id = R.string.section_ai_tuning_status),
            infoText = stringResource(id = R.string.ai_tuning_status_info)
        )
        Surface(
            shape = SafetyInfoShape,
            color = statusColor,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = statusIcon,
                    contentDescription = null
                )
                Text(
                    text = stateLabel,
                    style = MaterialTheme.typography.labelLarge
                )
            }
        }
        Surface(
            shape = SafetyInfoShape,
            color = MaterialTheme.colorScheme.surfaceVariant,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(Spacing.xxs)
            ) {
                Text(
                    text = stringResource(id = R.string.ai_tuning_reason_line, status.reason),
                    style = MaterialTheme.typography.bodySmall
                )
                status.generatedTs?.let { ts ->
                    Text(
                        text = stringResource(
                            id = R.string.ai_tuning_generated_line,
                            formatSafetyTs(ts)
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                status.confidence?.let { confidence ->
                    Text(
                        text = stringResource(
                            id = R.string.ai_tuning_confidence_line,
                            String.format(Locale.US, "%.2f", confidence)
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                status.statusRaw
                    ?.takeIf { it.isNotBlank() }
                    ?.let { raw ->
                        Text(
                            text = stringResource(id = R.string.ai_tuning_raw_line, raw),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
            }
        }
    }
}

@Composable
private fun KillSwitchCard(
    enabled: Boolean,
    onToggle: (Boolean) -> Unit
) {
    var localEnabled by rememberSaveable { mutableStateOf(enabled) }
    LaunchedEffect(enabled) {
        localEnabled = enabled
    }
    SafetySectionCard {
        Surface(
            shape = SafetyInfoShape,
            color = if (localEnabled) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.secondaryContainer,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = if (localEnabled) Icons.Default.Warning else Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = if (localEnabled) MaterialTheme.colorScheme.onTertiaryContainer else MaterialTheme.colorScheme.onSecondaryContainer
                )
                Text(
                    text = if (localEnabled) {
                        stringResource(id = R.string.safety_kill_switch_on)
                    } else {
                        stringResource(id = R.string.safety_kill_switch_off)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (localEnabled) MaterialTheme.colorScheme.onTertiaryContainer else MaterialTheme.colorScheme.onSecondaryContainer
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(id = R.string.label_kill_switch),
                style = MaterialTheme.typography.titleMedium
            )
            Switch(
                checked = localEnabled,
                onCheckedChange = { next ->
                    localEnabled = next
                    onToggle(next)
                }
            )
        }
    }
}

@Composable
private fun LimitsCard(
    state: SafetyUiState,
    onSafetyBoundsChange: (Double, Double) -> Unit
) {
    SafetySectionCard {
        SafetySectionLabel(
            text = stringResource(id = R.string.section_safety_limits),
            infoText = stringResource(id = R.string.safety_info_limits_section)
        )
        val unitMinutes = stringResource(id = R.string.unit_minutes)
        val unitMmol = stringResource(id = R.string.unit_mmol_l)

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Spacing.xs)
        ) {
            StatCell(
                modifier = Modifier.weight(1f),
                title = stringResource(id = R.string.metric_stale_limit),
                value = "${state.staleMinutesLimit} $unitMinutes"
            )
            StatCell(
                modifier = Modifier.weight(1f),
                title = stringResource(id = R.string.metric_max_actions),
                value = "${state.maxActionsIn6h} / 6h"
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Spacing.xs)
        ) {
            StatCell(
                modifier = Modifier.weight(1f),
                title = stringResource(id = R.string.metric_base_target),
                value = "${"%.1f".format(state.baseTarget)} $unitMmol"
            )
            StatCell(
                modifier = Modifier.weight(1f),
                title = stringResource(id = R.string.safety_hard_bounds),
                value = state.hardBounds
            )
        }

        Surface(
            shape = SafetyInfoShape,
            color = MaterialTheme.colorScheme.surfaceVariant,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(Spacing.xxs)
            ) {
                Text(
                    text = "${stringResource(id = R.string.safety_adaptive_bounds)}: ${state.adaptiveBounds}",
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    text = "${stringResource(id = R.string.safety_local_ns_status)}: ${
                        if (state.localNightscoutEnabled) {
                            "${stringResource(id = R.string.status_on_short)}:${state.localNightscoutPort}"
                        } else {
                            stringResource(id = R.string.status_off_short)
                        }
                    }",
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    text = "${stringResource(id = R.string.safety_local_ns_tls)}: ${state.localNightscoutTlsStatusText}",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }

        BoundAdjustRow(
            title = stringResource(id = R.string.settings_adaptive_low_alert),
            subtitle = stringResource(id = R.string.settings_adaptive_low_alert_subtitle),
            value = state.hardMinTargetMmol,
            min = 4.0,
            max = (state.hardMaxTargetMmol - 0.2).coerceAtLeast(4.0),
            onChange = { next -> onSafetyBoundsChange(next, state.hardMaxTargetMmol) }
        )
        BoundAdjustRow(
            title = stringResource(id = R.string.settings_adaptive_high_alert),
            subtitle = stringResource(id = R.string.settings_adaptive_high_alert_subtitle),
            value = state.hardMaxTargetMmol,
            min = (state.hardMinTargetMmol + 0.2).coerceAtMost(10.0),
            max = 10.0,
            onChange = { next -> onSafetyBoundsChange(state.hardMinTargetMmol, next) }
        )
    }
}

@Composable
private fun BoundAdjustRow(
    title: String,
    subtitle: String,
    value: Double,
    min: Double,
    max: Double,
    onChange: (Double) -> Unit
) {
    Surface(
        shape = SafetyInfoShape,
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(Spacing.xxs)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    enabled = value > min + 0.0001,
                    onClick = { onChange((value - 0.1).coerceAtLeast(min)) }
                ) {
                    Icon(
                        imageVector = Icons.Default.Remove,
                        contentDescription = null
                    )
                }
                Text(
                    text = "${"%.1f".format(value)} ${stringResource(id = R.string.unit_mmol_l)}",
                    style = MaterialTheme.typography.titleSmall
                )
                IconButton(
                    enabled = value < max - 0.0001,
                    onClick = { onChange((value + 0.1).coerceAtMost(max)) }
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = null
                    )
                }
            }
        }
    }
}

@Composable
private fun StatCell(
    title: String,
    value: String,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = SafetyInfoShape,
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = value,
                style = MaterialTheme.typography.titleSmall
            )
        }
    }
}

@Composable
private fun CooldownCard(lines: List<String>) {
    SafetySectionCard {
        SafetySectionLabel(
            text = stringResource(id = R.string.section_safety_cooldown),
            infoText = stringResource(id = R.string.safety_info_cooldown_section)
        )
        lines.forEach { line ->
            Surface(
                shape = SafetyInfoShape,
                color = MaterialTheme.colorScheme.surfaceVariant,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                Text(
                    text = line,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)
                )
            }
        }
    }
}

@Composable
private fun ChecklistSection(items: List<SafetyChecklistItemUi>) {
    SafetySectionCard {
        SafetySectionLabel(
            text = stringResource(id = R.string.section_safety_checklist),
            infoText = stringResource(id = R.string.safety_info_checklist_section)
        )
        items.forEach { item ->
            Surface(
                shape = SafetyInfoShape,
                color = if (item.ok) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.errorContainer,
                border = BorderStroke(1.dp, if (item.ok) MaterialTheme.colorScheme.outlineVariant else MaterialTheme.colorScheme.outlineVariant)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = if (item.ok) Icons.Default.CheckCircle else Icons.Default.Error,
                        contentDescription = null,
                        tint = if (item.ok) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onErrorContainer
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = item.title,
                            style = MaterialTheme.typography.labelLarge
                        )
                        Text(
                            text = item.details,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SafetySummaryCard(
    killSwitchEnabled: Boolean,
    checksPassed: Int,
    checksTotal: Int
) {
    SafetySectionCard {
        Surface(
            shape = SafetyInfoShape,
            color = MaterialTheme.colorScheme.primaryContainer,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(Spacing.xxs)
            ) {
                Text(
                    text = stringResource(id = R.string.safety_system_status),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Text(
                    text = if (killSwitchEnabled) {
                        stringResource(id = R.string.safety_mode_manual)
                    } else {
                        stringResource(id = R.string.safety_mode_automated)
                    },
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Text(
                    text = stringResource(id = R.string.safety_checks_passed, checksPassed, checksTotal),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }
    }
}

@Composable
private fun SafetySectionCard(
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = SafetySectionShape,
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
private fun SafetySectionLabel(
    text: String,
    infoText: String? = null
) {
    var showInfo by rememberSaveable(text, infoText) { mutableStateOf(false) }
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
        if (!infoText.isNullOrBlank()) {
            IconButton(onClick = { showInfo = true }) {
                Icon(
                    imageVector = Icons.Default.Info,
                    contentDescription = stringResource(id = R.string.settings_info_button_cd, text),
                    tint = MaterialTheme.colorScheme.primary
                )
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

private fun formatSafetyTs(ts: Long): String {
    return Instant.ofEpochMilli(ts)
        .atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern("MM-dd HH:mm"))
}

@Preview(showBackground = true)
@Composable
private fun SafetyScreenPreview() {
    AapsCopilotTheme {
        SafetyScreen(
            state = SafetyUiState(
                loadState = ScreenLoadState.READY,
                isStale = false,
                killSwitchEnabled = false,
                staleMinutesLimit = 10,
                hardBounds = "4.0..10.0",
                hardMinTargetMmol = 4.0,
                hardMaxTargetMmol = 10.0,
                adaptiveBounds = "4.0..9.0",
                baseTarget = 5.5,
                maxActionsIn6h = 4,
                cooldownStatusLines = listOf("Adaptive: ready", "PostHypo: 12m left"),
                localNightscoutEnabled = true,
                localNightscoutPort = 17582,
                localNightscoutTlsOk = true,
                localNightscoutTlsStatusText = "TLS OK",
                aiTuningStatus = AiTuningStatusUi(
                    state = "BLOCKED",
                    reason = "confidence below threshold",
                    generatedTs = 1_800_000_000_000L,
                    confidence = 0.38,
                    statusRaw = "blocked:low_confidence"
                ),
                checklist = listOf(
                    SafetyChecklistItemUi("Data freshness", true, "age=1 min"),
                    SafetyChecklistItemUi("Sensor quality", false, "suspect false low")
                )
            ),
            onKillSwitchToggle = {}
        )
    }
}
