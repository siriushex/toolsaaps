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
import androidx.compose.material3.SwitchDefaults
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
import io.aaps.copilot.config.UiStyle
import io.aaps.copilot.R
import io.aaps.copilot.ui.foundation.design.AppElevation
import io.aaps.copilot.ui.foundation.design.Spacing
import io.aaps.copilot.ui.foundation.theme.AapsCopilotTheme
import io.aaps.copilot.ui.foundation.theme.LocalUiStyle
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
                SafetyHeroHeader(state = state)
            }
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
            item {
                GlucoseAlertsDiagnosticsCard(state = state)
            }
            item {
                TherapyHistoryDiagnosticsCard(state = state)
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
private fun SafetyHeroHeader(state: SafetyUiState) {
    val midnightGlass = LocalUiStyle.current == UiStyle.MIDNIGHT_GLASS
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = stringResource(id = R.string.safety_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = if (midnightGlass) Color(0xFF44536B) else MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (state.killSwitchEnabled) {
            Surface(
                shape = SafetyPillShape,
                color = if (midnightGlass) Color(0x55312735) else MaterialTheme.colorScheme.errorContainer,
                border = BorderStroke(1.dp, if (midnightGlass) Color(0x1FFFFFFF) else MaterialTheme.colorScheme.outlineVariant)
            ) {
                Text(
                    text = stringResource(id = R.string.safety_kill_switch_on),
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (midnightGlass) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onErrorContainer
                )
            }
        }
    }
}

@Composable
private fun AiTuningStatusCard(status: AiTuningStatusUi) {
    val midnightGlass = LocalUiStyle.current == UiStyle.MIDNIGHT_GLASS
    val normalizedState = status.state.trim().uppercase(Locale.US)
    val (statusIcon, statusColor) = when (normalizedState) {
        "ACTIVE" -> Icons.Default.CheckCircle to if (midnightGlass) Color(0x2200E676) else MaterialTheme.colorScheme.secondaryContainer
        "STALE" -> Icons.Default.Warning to if (midnightGlass) Color(0x664F2D00) else MaterialTheme.colorScheme.tertiaryContainer
        else -> Icons.Default.Error to if (midnightGlass) Color(0x665A1E25) else MaterialTheme.colorScheme.errorContainer
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
            border = BorderStroke(1.dp, if (midnightGlass) Color(0x1FFFFFFF) else MaterialTheme.colorScheme.outlineVariant)
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
            color = if (midnightGlass) Color(0xAA101D38) else MaterialTheme.colorScheme.surfaceVariant,
            border = BorderStroke(1.dp, if (midnightGlass) Color(0x1FFFFFFF) else MaterialTheme.colorScheme.outlineVariant)
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
                        color = if (midnightGlass) Color(0xFFB5C0D8) else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                status.confidence?.let { confidence ->
                    Text(
                        text = stringResource(
                            id = R.string.ai_tuning_confidence_line,
                            String.format(Locale.US, "%.2f", confidence)
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (midnightGlass) Color(0xFFB5C0D8) else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                status.statusRaw
                    ?.takeIf { it.isNotBlank() }
                    ?.let { raw ->
                        Text(
                            text = stringResource(id = R.string.ai_tuning_raw_line, raw),
                            style = MaterialTheme.typography.bodySmall,
                            color = if (midnightGlass) Color(0xFFB5C0D8) else MaterialTheme.colorScheme.onSurfaceVariant
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
    val midnightGlass = LocalUiStyle.current == UiStyle.MIDNIGHT_GLASS
    var localEnabled by rememberSaveable { mutableStateOf(enabled) }
    LaunchedEffect(enabled) {
        localEnabled = enabled
    }
    SafetySectionCard {
        Surface(
            shape = SafetyInfoShape,
            color = if (midnightGlass) {
                if (localEnabled) Color(0x55312735) else Color(0x221D4ED8)
            } else {
                if (localEnabled) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.secondaryContainer
            },
            border = BorderStroke(1.dp, if (midnightGlass) Color(0x1FFFFFFF) else MaterialTheme.colorScheme.outlineVariant)
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
                    tint = if (midnightGlass) {
                        if (localEnabled) Color(0xFFFFD180) else Color(0xFF8DB6FF)
                    } else {
                        if (localEnabled) MaterialTheme.colorScheme.onTertiaryContainer else MaterialTheme.colorScheme.onSecondaryContainer
                    }
                )
                Text(
                    text = if (localEnabled) {
                        stringResource(id = R.string.safety_kill_switch_on)
                    } else {
                        stringResource(id = R.string.safety_kill_switch_off)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (midnightGlass) {
                        if (localEnabled) Color(0xFFFFD180) else Color(0xFF8DB6FF)
                    } else {
                        if (localEnabled) MaterialTheme.colorScheme.onTertiaryContainer else MaterialTheme.colorScheme.onSecondaryContainer
                    }
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
                style = MaterialTheme.typography.titleMedium,
                color = if (midnightGlass) Color(0xFFF8FAFC) else MaterialTheme.colorScheme.onSurface
            )
            Switch(
                checked = localEnabled,
                onCheckedChange = { next ->
                    localEnabled = next
                    onToggle(next)
                },
                colors = if (midnightGlass) {
                    SwitchDefaults.colors(
                        checkedThumbColor = Color(0xFFF8FAFC),
                        checkedTrackColor = Color(0xFF1D4ED8),
                        checkedBorderColor = Color.Transparent,
                        uncheckedThumbColor = Color(0xFFDCEBFF),
                        uncheckedTrackColor = Color(0xFF334155),
                        uncheckedBorderColor = Color.Transparent
                    )
                } else {
                    SwitchDefaults.colors()
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
    val midnightGlass = LocalUiStyle.current == UiStyle.MIDNIGHT_GLASS
    SafetySectionCard {
        SafetySectionLabel(
            text = stringResource(id = R.string.section_safety_limits),
            infoText = stringResource(id = R.string.safety_info_limits_section)
        )
        val unitMinutes = stringResource(id = R.string.unit_minutes)

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

        StatCell(
            modifier = Modifier.fillMaxWidth(),
            title = stringResource(id = R.string.safety_hard_bounds),
            value = state.hardBounds
        )

        Surface(
            shape = SafetyInfoShape,
            color = if (midnightGlass) Color(0xAA101D38) else MaterialTheme.colorScheme.surfaceVariant,
            border = BorderStroke(1.dp, if (midnightGlass) Color(0x1FFFFFFF) else MaterialTheme.colorScheme.outlineVariant)
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(Spacing.xxs)
            ) {
                Text(
                    text = "${stringResource(id = R.string.safety_adaptive_bounds)}: ${state.adaptiveBounds}",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (midnightGlass) Color(0xFFDCEBFF) else MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "${stringResource(id = R.string.safety_local_ns_status)}: ${
                        if (state.localNightscoutEnabled) {
                            "${state.localNightscoutRuntimeStatus}:${state.localNightscoutPort}" +
                                state.localNightscoutRuntimeReason?.let { " (reason=$it)" }.orEmpty()
                        } else {
                            stringResource(id = R.string.status_off_short)
                        }
                    }",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (midnightGlass) Color(0xFFDCEBFF) else MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "${stringResource(id = R.string.safety_local_ns_tls)}: ${state.localNightscoutTlsStatusText}",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (midnightGlass) Color(0xFFDCEBFF) else MaterialTheme.colorScheme.onSurface
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
private fun GlucoseAlertsDiagnosticsCard(state: SafetyUiState) {
    SafetySectionCard {
        SafetySectionLabel(
            text = stringResource(id = R.string.section_safety_glucose_alerts),
            infoText = stringResource(id = R.string.safety_info_glucose_alerts)
        )
        val statusText = when (state.glucoseAlertState?.uppercase(Locale.US)) {
            "LOW_NOW" -> stringResource(id = R.string.glucose_alert_state_low_now)
            "CRITICAL_5" -> stringResource(id = R.string.glucose_alert_state_critical_5)
            "WARNING_30" -> stringResource(id = R.string.glucose_alert_state_warning_30)
            "WATCH_60" -> stringResource(id = R.string.glucose_alert_state_watch_60)
            "SOFT_HIGH_RISK" -> stringResource(id = R.string.glucose_alert_state_soft_high)
            else -> stringResource(id = R.string.glucose_alert_state_none)
        }
        val directionText = when (state.glucoseAlertDirection?.uppercase(Locale.US)) {
            "LOW" -> stringResource(id = R.string.glucose_alert_direction_low)
            "HIGH" -> stringResource(id = R.string.glucose_alert_direction_high)
            else -> stringResource(id = R.string.placeholder_missing)
        }
        val disableReasonText = state.glucoseAlertDisableReason?.let { reason ->
            when (reason.lowercase(Locale.US)) {
                "stale_data" -> stringResource(id = R.string.glucose_alert_disable_stale)
                "sensor_blocked" -> stringResource(id = R.string.glucose_alert_disable_sensor_blocked)
                "suspect_false_low" -> stringResource(id = R.string.glucose_alert_disable_false_low)
                "missing_forecast30" -> stringResource(id = R.string.glucose_alert_disable_missing_forecast)
                "soft_alerts_disabled" -> stringResource(id = R.string.glucose_alert_disable_disabled)
                "glucose_alert_muted" -> stringResource(id = R.string.glucose_alert_disable_muted)
                else -> reason.replace('_', ' ')
            }
        } ?: stringResource(id = R.string.placeholder_missing)

        SafetyInfoCard {
            SafetyInfoLine(stringResource(id = R.string.glucose_alert_summary_state), statusText)
            SafetyInfoLine(stringResource(id = R.string.glucose_alert_summary_direction), directionText)
            SafetyInfoLine(
                stringResource(id = R.string.glucose_alert_summary_low),
                state.glucoseAlertLowThreshold?.let { "${String.format(Locale.US, "%.1f", it)} mmol/L" }
                    ?: stringResource(id = R.string.placeholder_missing)
            )
            SafetyInfoLine(
                stringResource(id = R.string.glucose_alert_summary_high),
                state.glucoseAlertHighThreshold?.let { "${String.format(Locale.US, "%.1f", it)} mmol/L" }
                    ?: stringResource(id = R.string.placeholder_missing)
            )
            SafetyInfoLine(
                stringResource(id = R.string.glucose_alert_summary_urgent_low),
                state.glucoseAlertUrgentLowThreshold?.let { "${String.format(Locale.US, "%.1f", it)} mmol/L" }
                    ?: stringResource(id = R.string.placeholder_missing)
            )
            SafetyInfoLine(
                stringResource(id = R.string.glucose_alert_summary_last_soft),
                state.glucoseAlertSoftLastTs?.let(::formatSafetyTs) ?: stringResource(id = R.string.placeholder_missing)
            )
            SafetyInfoLine(
                stringResource(id = R.string.glucose_alert_summary_last_strong),
                state.glucoseAlertStrongLastTs?.let(::formatSafetyTs) ?: stringResource(id = R.string.placeholder_missing)
            )
            SafetyInfoLine(
                stringResource(id = R.string.glucose_alert_summary_soft_clip),
                "${state.glucoseAlertSoftClipLabel} · " + if (state.glucoseAlertSoftClipValid) {
                    stringResource(id = R.string.settings_glucose_alerts_audio_valid)
                } else {
                    stringResource(id = R.string.settings_glucose_alerts_audio_fallback)
                }
            )
            SafetyInfoLine(
                stringResource(id = R.string.glucose_alert_summary_critical_clip_1),
                "${state.glucoseAlertCriticalClip1Label} · " + if (state.glucoseAlertCriticalClip1Valid) {
                    stringResource(id = R.string.settings_glucose_alerts_audio_valid)
                } else {
                    stringResource(id = R.string.settings_glucose_alerts_audio_fallback)
                }
            )
            SafetyInfoLine(
                stringResource(id = R.string.glucose_alert_summary_critical_clip_2),
                "${state.glucoseAlertCriticalClip2Label} · " + if (state.glucoseAlertCriticalClip2Valid) {
                    stringResource(id = R.string.settings_glucose_alerts_audio_valid)
                } else {
                    stringResource(id = R.string.settings_glucose_alerts_audio_fallback)
                }
            )
            SafetyInfoLine(stringResource(id = R.string.glucose_alert_summary_disable_reason), disableReasonText)
        }
    }
}

@Composable
private fun TherapyHistoryDiagnosticsCard(state: SafetyUiState) {
    SafetySectionCard {
        SafetySectionLabel(
            text = stringResource(id = R.string.section_safety_therapy_history),
            infoText = stringResource(id = R.string.safety_info_therapy_history)
        )
        val modeText = when (state.therapyHistorySourceMode?.uppercase(Locale.US)) {
            "SPARSE_REAL_FETCHED" -> stringResource(id = R.string.therapy_history_mode_sparse_real_fetched)
            "REAL_FETCHED" -> stringResource(id = R.string.therapy_history_mode_real_fetched)
            "RECOVERED_PLATEAU_ONLY" -> stringResource(id = R.string.therapy_history_mode_recovered_plateau)
            "SYNTHETIC_ONLY" -> stringResource(id = R.string.therapy_history_mode_synthetic_only)
            "MIXED_NO_REAL" -> stringResource(id = R.string.therapy_history_mode_mixed_no_real)
            "EMPTY" -> stringResource(id = R.string.therapy_history_mode_empty)
            else -> stringResource(id = R.string.placeholder_missing)
        }
        SafetyInfoCard {
            SafetyInfoLine(stringResource(id = R.string.therapy_history_summary_mode), modeText)
            SafetyInfoLine(
                stringResource(id = R.string.therapy_history_summary_real),
                state.therapyHistoryRealFetchedInsulin30d?.toString() ?: stringResource(id = R.string.placeholder_missing)
            )
            SafetyInfoLine(
                stringResource(id = R.string.therapy_history_summary_recovered),
                state.therapyHistoryRecoveredInsulin30d?.toString() ?: stringResource(id = R.string.placeholder_missing)
            )
            SafetyInfoLine(
                stringResource(id = R.string.therapy_history_summary_inferred),
                state.therapyHistoryInferredInsulin30d?.toString() ?: stringResource(id = R.string.placeholder_missing)
            )
            SafetyInfoLine(
                stringResource(id = R.string.therapy_history_summary_usable),
                state.therapyHistoryUsableInsulin30d?.toString() ?: stringResource(id = R.string.placeholder_missing)
            )
            SafetyInfoLine(
                stringResource(id = R.string.therapy_history_summary_raw),
                state.therapyHistoryRawInsulin30d?.toString() ?: stringResource(id = R.string.placeholder_missing)
            )
            SafetyInfoLine(
                stringResource(id = R.string.therapy_history_summary_synthetic_ratio),
                state.therapyHistorySyntheticRatioPct?.let { "${String.format(Locale.US, "%.0f", it)}%" }
                    ?: stringResource(id = R.string.placeholder_missing)
            )
            SafetyInfoLine(
                stringResource(id = R.string.therapy_history_summary_last_sync_treatments),
                state.therapyHistoryLastSyncTreatmentCount?.toString() ?: stringResource(id = R.string.placeholder_missing)
            )
            SafetyInfoLine(
                stringResource(id = R.string.therapy_history_summary_last_sync_insulin_like),
                state.therapyHistoryLastSyncInsulinLikeCount?.toString() ?: stringResource(id = R.string.placeholder_missing)
            )
            SafetyInfoLine(
                stringResource(id = R.string.therapy_history_summary_last_sync_carb_like),
                state.therapyHistoryLastSyncCarbLikeCount?.toString() ?: stringResource(id = R.string.placeholder_missing)
            )
            SafetyInfoLine(
                stringResource(id = R.string.therapy_history_summary_last_sync_local_actions),
                state.therapyHistoryLastSyncLocalActionCount?.toString() ?: stringResource(id = R.string.placeholder_missing)
            )
            SafetyInfoLine(
                stringResource(id = R.string.therapy_history_summary_upstream_temp_target_only),
                if (state.therapyHistoryUpstreamTempTargetOnly == true) {
                    stringResource(id = R.string.yes)
                } else {
                    stringResource(id = R.string.no)
                }
            )
            SafetyInfoLine(
                stringResource(id = R.string.therapy_history_summary_bootstrap),
                if (state.therapyHistoryBootstrapNeeded == true) {
                    stringResource(id = R.string.therapy_history_bootstrap_needed_yes)
                } else {
                    stringResource(id = R.string.therapy_history_bootstrap_needed_no)
                }
            )
            SafetyInfoLine(
                stringResource(id = R.string.therapy_history_summary_plateau_only),
                if (state.therapyHistoryPlateauOnly == true) {
                    stringResource(id = R.string.yes)
                } else {
                    stringResource(id = R.string.no)
                }
            )
        }
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
    val midnightGlass = LocalUiStyle.current == UiStyle.MIDNIGHT_GLASS
    val primaryTextColor = if (midnightGlass) Color(0xFFF8FAFC) else MaterialTheme.colorScheme.onSurface
    val controlColor = if (midnightGlass) Color(0xFFDCEBFF) else MaterialTheme.colorScheme.onSurface
    val disabledControlColor = if (midnightGlass) Color(0x665D6B84) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
    Surface(
        shape = SafetyInfoShape,
        color = if (midnightGlass) Color(0xAA101D38) else MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(1.dp, if (midnightGlass) Color(0x1FFFFFFF) else MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(Spacing.xxs)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium,
                color = primaryTextColor
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = if (midnightGlass) Color(0xFFB5C0D8) else MaterialTheme.colorScheme.onSurfaceVariant
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
                    val enabled = value > min + 0.0001
                    Icon(
                        imageVector = Icons.Default.Remove,
                        contentDescription = null,
                        tint = if (enabled) controlColor else disabledControlColor
                    )
                }
                Text(
                    text = "${"%.1f".format(value)} ${stringResource(id = R.string.unit_mmol_l)}",
                    style = MaterialTheme.typography.titleSmall,
                    color = primaryTextColor
                )
                IconButton(
                    enabled = value < max - 0.0001,
                    onClick = { onChange((value + 0.1).coerceAtMost(max)) }
                ) {
                    val enabled = value < max - 0.0001
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = null,
                        tint = if (enabled) controlColor else disabledControlColor
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
    val midnightGlass = LocalUiStyle.current == UiStyle.MIDNIGHT_GLASS
    Surface(
        modifier = modifier,
        shape = SafetyInfoShape,
        color = if (midnightGlass) Color(0xAA101D38) else MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(1.dp, if (midnightGlass) Color(0x1FFFFFFF) else MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelSmall,
                color = if (midnightGlass) Color(0xFF93A5C3) else MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = value,
                style = MaterialTheme.typography.titleSmall,
                color = if (midnightGlass) Color(0xFFF8FAFC) else MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

@Composable
private fun SafetyInfoCard(
    content: @Composable ColumnScope.() -> Unit
) {
    val midnightGlass = LocalUiStyle.current == UiStyle.MIDNIGHT_GLASS
    Surface(
        shape = SafetyInfoShape,
        color = if (midnightGlass) Color(0xAA101D38) else MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(1.dp, if (midnightGlass) Color(0x1FFFFFFF) else MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(Spacing.xxs),
            content = content
        )
    }
}

@Composable
private fun SafetyInfoLine(
    label: String,
    value: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = if (LocalUiStyle.current == UiStyle.MIDNIGHT_GLASS) Color(0xFFB5C0D8) else MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.labelMedium,
            color = if (LocalUiStyle.current == UiStyle.MIDNIGHT_GLASS) Color(0xFFF8FAFC) else MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
private fun CooldownCard(lines: List<String>) {
    val midnightGlass = LocalUiStyle.current == UiStyle.MIDNIGHT_GLASS
    SafetySectionCard {
        SafetySectionLabel(
            text = stringResource(id = R.string.section_safety_cooldown),
            infoText = stringResource(id = R.string.safety_info_cooldown_section)
        )
        lines.forEach { line ->
            Surface(
                shape = SafetyInfoShape,
                color = if (midnightGlass) Color(0xAA101D38) else MaterialTheme.colorScheme.surfaceVariant,
                border = BorderStroke(1.dp, if (midnightGlass) Color(0x1FFFFFFF) else MaterialTheme.colorScheme.outlineVariant)
            ) {
                Text(
                    text = line,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (midnightGlass) Color(0xFFDCEBFF) else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)
                )
            }
        }
    }
}

@Composable
private fun ChecklistSection(items: List<SafetyChecklistItemUi>) {
    val midnightGlass = LocalUiStyle.current == UiStyle.MIDNIGHT_GLASS
    SafetySectionCard {
        SafetySectionLabel(
            text = stringResource(id = R.string.section_safety_checklist),
            infoText = stringResource(id = R.string.safety_info_checklist_section)
        )
        items.forEach { item ->
            Surface(
                shape = SafetyInfoShape,
                color = if (midnightGlass) {
                    if (item.ok) Color(0x55103A35) else Color(0x665A1E25)
                } else {
                    if (item.ok) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.errorContainer
                },
                border = BorderStroke(1.dp, if (midnightGlass) {
                    if (item.ok) Color(0x3325C685) else Color(0x33FF6B74)
                } else {
                    MaterialTheme.colorScheme.outlineVariant
                })
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
                        tint = if (midnightGlass) {
                            if (item.ok) Color(0xFF9FFFB0) else Color(0xFFFFA7AE)
                        } else {
                            if (item.ok) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onErrorContainer
                        }
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = item.title,
                            style = MaterialTheme.typography.labelLarge,
                            color = if (midnightGlass) Color(0xFFF8FAFC) else MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = item.details,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (midnightGlass) Color(0xFFB5C0D8) else MaterialTheme.colorScheme.onSurfaceVariant
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
    val midnightGlass = LocalUiStyle.current == UiStyle.MIDNIGHT_GLASS
    SafetySectionCard {
        Surface(
            shape = SafetyInfoShape,
            color = if (midnightGlass) Color(0xFF10275A) else MaterialTheme.colorScheme.primaryContainer,
            border = BorderStroke(1.dp, if (midnightGlass) Color(0x334A82BF) else MaterialTheme.colorScheme.outlineVariant)
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
                    color = if (midnightGlass) Color(0xFFDCEBFF) else MaterialTheme.colorScheme.onPrimaryContainer
                )
                Text(
                    text = if (killSwitchEnabled) {
                        stringResource(id = R.string.safety_mode_manual)
                    } else {
                        stringResource(id = R.string.safety_mode_automated)
                    },
                    style = MaterialTheme.typography.titleSmall,
                    color = if (midnightGlass) Color(0xFFF8FAFC) else MaterialTheme.colorScheme.onPrimaryContainer
                )
                Text(
                    text = stringResource(id = R.string.safety_checks_passed, checksPassed, checksTotal),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (midnightGlass) Color(0xFFB5C0D8) else MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }
    }
}

@Composable
private fun SafetySectionCard(
    content: @Composable ColumnScope.() -> Unit
) {
    val midnightGlass = LocalUiStyle.current == UiStyle.MIDNIGHT_GLASS
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = SafetySectionShape,
        border = BorderStroke(1.dp, if (midnightGlass) Color(0x263A4A66) else MaterialTheme.colorScheme.outlineVariant),
        colors = CardDefaults.cardColors(containerColor = if (midnightGlass) Color(0xF51D2D49) else MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = AppElevation.level1)
    ) {
        Column(
            modifier = Modifier.padding(Spacing.sm),
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
    val midnightGlass = LocalUiStyle.current == UiStyle.MIDNIGHT_GLASS
    var showInfo by rememberSaveable(text, infoText) { mutableStateOf(false) }
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
                shape = SafetyPillShape,
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
