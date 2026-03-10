package io.aaps.copilot.ui.foundation.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.TrendingDown
import androidx.compose.material.icons.filled.TrendingUp
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.lazy.LazyColumn
import io.aaps.copilot.R
import io.aaps.copilot.config.UiStyle
import io.aaps.copilot.ui.foundation.design.AppElevation
import io.aaps.copilot.ui.foundation.design.Spacing
import io.aaps.copilot.ui.foundation.format.UiFormatters
import io.aaps.copilot.ui.foundation.theme.AapsCopilotTheme
import io.aaps.copilot.ui.foundation.theme.LocalUiStyle
import io.aaps.copilot.ui.foundation.theme.LocalNumericTypography
import java.util.Locale
import kotlin.math.abs

private val SectionShape = RoundedCornerShape(18.dp)
private val InfoShape = RoundedCornerShape(12.dp)
private val PillShape = RoundedCornerShape(999.dp)

@Composable
fun OverviewScreen(
    state: OverviewUiState,
    onRunCycleNow: () -> Unit,
    onSetKillSwitch: (Boolean) -> Unit,
    onOpenSensorLagAnalytics: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    var showKillSwitchConfirm by remember { mutableStateOf(false) }
    var deferredSectionsReady by remember { mutableStateOf(false) }
    var valueAnimationsReady by remember { mutableStateOf(false) }
    val midnightGlass = LocalUiStyle.current == UiStyle.MIDNIGHT_GLASS

    LaunchedEffect(Unit) {
        withFrameNanos { }
        deferredSectionsReady = true
        withFrameNanos { }
        valueAnimationsReady = true
    }

    if (showKillSwitchConfirm) {
        AlertDialog(
            onDismissRequest = { showKillSwitchConfirm = false },
            title = { Text(text = stringResource(id = R.string.overview_kill_switch_title)) },
            text = {
                Text(
                    text = if (state.killSwitchEnabled) {
                        stringResource(id = R.string.overview_kill_switch_disable_confirm)
                    } else {
                        stringResource(id = R.string.overview_kill_switch_enable_confirm)
                    }
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showKillSwitchConfirm = false
                        onSetKillSwitch(!state.killSwitchEnabled)
                    }
                ) {
                    Text(stringResource(id = R.string.action_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showKillSwitchConfirm = false }) {
                    Text(stringResource(id = R.string.action_cancel))
                }
            }
        )
    }

    ScreenStateLayout(
        loadState = state.loadState,
        isStale = state.isStale,
        errorText = state.errorText,
        emptyText = stringResource(id = R.string.overview_empty)
    ) {
        LazyColumn(
            modifier = modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            item {
                OverviewHeader(state = state, midnightGlass = midnightGlass)
            }
            item {
                CurrentGlucoseSection(
                    state = state,
                    animationsEnabled = valueAnimationsReady,
                    midnightGlass = midnightGlass,
                    onOpenSensorLagAnalytics = onOpenSensorLagAnalytics
                )
            }
            item {
                PredictionsSection(horizons = state.horizons, midnightGlass = midnightGlass)
            }
            if (deferredSectionsReady) {
                item {
                    UamSection(
                        active = state.uamActive,
                        uci0Mmol5m = state.uci0Mmol5m,
                        inferredCarbsLast60g = state.inferredCarbsLast60g,
                        mode = state.uamModeLabel,
                        midnightGlass = midnightGlass
                    )
                }
                item {
                    TelemetrySection(chips = state.telemetryChips, midnightGlass = midnightGlass)
                }
                item {
                    LastActionSection(action = state.lastAction, midnightGlass = midnightGlass)
                }
            }
            item {
                ActionButtonsRow(
                    canRunCycleNow = state.canRunCycleNow,
                    onRunCycleNow = onRunCycleNow,
                    onKillSwitch = { showKillSwitchConfirm = true },
                    midnightGlass = midnightGlass
                )
            }
        }
    }
}

@Composable
private fun OverviewHeader(state: OverviewUiState, midnightGlass: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = stringResource(id = R.string.app_name),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
        val ageText = compactAgeLabel(state.sampleAgeMinutes)
        val staleChipText = if (state.isStale) {
            stringResource(id = R.string.overview_stale_badge, ageText)
        } else {
            stringResource(id = R.string.overview_live_badge, ageText)
        }
        Surface(
            shape = PillShape,
            color = if (midnightGlass) {
                if (state.isStale) Color(0x33FFB020) else Color(0x221D4ED8)
            } else {
                if (state.isStale) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.secondaryContainer
            },
            modifier = Modifier.semantics {
                contentDescription = staleChipText
            }
        ) {
            Text(
                text = staleChipText,
                style = MaterialTheme.typography.labelSmall,
                color = if (midnightGlass) {
                    if (state.isStale) Color(0xFFFFD180) else Color(0xFF8DB6FF)
                } else {
                    if (state.isStale) MaterialTheme.colorScheme.onTertiaryContainer else MaterialTheme.colorScheme.onSecondaryContainer
                },
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
            )
        }
    }
}

@Composable
private fun CurrentGlucoseSection(
    state: OverviewUiState,
    animationsEnabled: Boolean,
    midnightGlass: Boolean,
    onOpenSensorLagAnalytics: (() -> Unit)?
) {
    val risk = riskBadge(state.glucose, state.isStale)
    val warningHorizon = state.horizons
        .filter { it.warningWideCi }
        .maxByOrNull { it.horizonMinutes }

    SectionCard {
        SectionLabel(
            text = stringResource(id = R.string.metric_current_glucose),
            infoText = stringResource(id = R.string.overview_info_current_glucose_section)
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    color = if (midnightGlass) Color(0xAA101D38) else Color.Transparent,
                    shape = RoundedCornerShape(22.dp)
                )
                .padding(horizontal = if (midnightGlass) 18.dp else 0.dp, vertical = if (midnightGlass) 18.dp else 0.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.xxs)) {
                if (animationsEnabled) {
                    AnimatedContent(
                        targetState = UiFormatters.formatMmol(state.glucose, 2),
                        transitionSpec = {
                            fadeIn(animationSpec = tween(220)) togetherWith
                                fadeOut(animationSpec = tween(180))
                        },
                        label = "overviewGlucoseValue"
                    ) { glucoseValue ->
                        Text(
                            text = glucoseValue,
                            style = LocalNumericTypography.current.valueLarge.copy(
                                fontSize = 56.sp,
                                lineHeight = 56.sp,
                                letterSpacing = (-1.1).sp
                            )
                        )
                    }
                } else {
                    Text(
                        text = UiFormatters.formatMmol(state.glucose, 2),
                        style = LocalNumericTypography.current.valueLarge.copy(
                            fontSize = 56.sp,
                            lineHeight = 56.sp,
                            letterSpacing = (-1.1).sp
                        )
                    )
                }
                Text(
                    text = stringResource(id = R.string.unit_mmol_l),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (midnightGlass) Color(0xFF93A5C3) else MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (state.correctedGlucose != null && abs((state.correctedGlucose ?: 0.0) - (state.glucose ?: 0.0)) >= 0.01) {
                    Text(
                        text = buildString {
                            append(
                                if (state.sensorLagMode?.equals("ACTIVE", ignoreCase = true) == true) {
                                    "Control glucose "
                                } else {
                                    "Estimated now "
                                }
                            )
                            append(UiFormatters.formatMmol(state.correctedGlucose, 2))
                            append(" mmol/L")
                            state.sensorLagMinutes?.let {
                                append(" · lag ")
                                append(UiFormatters.formatDecimalOrPlaceholder(it, decimals = 0))
                                append("m")
                            }
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (midnightGlass) Color(0xFF8DB6FF) else MaterialTheme.colorScheme.primary
                    )
                }
                state.sensorLagRolloutVerdict?.let { verdict ->
                    val verdictText = stringResource(
                        id = R.string.overview_sensor_lag_rollout_template,
                        stringResource(id = overviewSensorLagRolloutStatusLabelResId(verdict.status)),
                        stringResource(id = overviewSensorLagBucketLabelResId(verdict.bucket))
                    )
                    val verdictTone = overviewSensorLagRolloutTone(
                        status = verdict.status,
                        midnightGlass = midnightGlass
                    )
                    if (onOpenSensorLagAnalytics != null) {
                        Surface(
                            shape = InfoShape,
                            color = if (midnightGlass) {
                                Color(0x1A7FB3FF)
                            } else {
                                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
                            },
                            border = BorderStroke(1.dp, verdictTone.copy(alpha = if (midnightGlass) 0.45f else 0.35f)),
                            modifier = Modifier
                                .clickable(onClick = onOpenSensorLagAnalytics)
                                .semantics { contentDescription = verdictText }
                        ) {
                            Text(
                                text = verdictText,
                                style = MaterialTheme.typography.bodySmall,
                                color = verdictTone,
                                modifier = Modifier.padding(horizontal = Spacing.xs, vertical = Spacing.xxs)
                            )
                        }
                    } else {
                        Text(
                            text = verdictText,
                            style = MaterialTheme.typography.bodySmall,
                            color = verdictTone
                        )
                    }
                }
            }
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(Spacing.xxs)
            ) {
                if (animationsEnabled) {
                    AnimatedContent(
                        targetState = UiFormatters.formatSignedDelta(state.delta, 2),
                        transitionSpec = {
                            fadeIn(animationSpec = tween(220)) togetherWith
                                fadeOut(animationSpec = tween(180))
                        },
                        label = "overviewDeltaValue"
                    ) { deltaValue ->
                        Text(
                            text = deltaValue,
                            style = LocalNumericTypography.current.valueMedium.copy(fontSize = 28.sp),
                            color = deltaTone(state.delta)
                        )
                    }
                } else {
                    Text(
                        text = UiFormatters.formatSignedDelta(state.delta, 2),
                        style = LocalNumericTypography.current.valueMedium.copy(fontSize = 28.sp),
                        color = deltaTone(state.delta)
                    )
                }
                Text(
                    text = stringResource(id = R.string.overview_roc_label),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (midnightGlass) Color(0xFF93A5C3) else MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (!state.sensorLagDisableReason.isNullOrBlank()) {
                    Text(
                        text = state.sensorLagDisableReason.replace('_', ' '),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (midnightGlass) Color(0xFFFFD180) else MaterialTheme.colorScheme.tertiary
                    )
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(
                    id = R.string.overview_sample_age,
                    UiFormatters.formatMinutes(state.sampleAgeMinutes)
                ),
                style = MaterialTheme.typography.bodySmall,
                color = if (midnightGlass) Color(0xFFB5C0D8) else MaterialTheme.colorScheme.onSurfaceVariant
            )
            Surface(
                shape = PillShape,
                color = risk.bgColor,
                border = BorderStroke(1.dp, risk.tone.copy(alpha = if (midnightGlass) 0.25f else 0.35f))
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.xxs)
                ) {
                    Icon(
                        imageVector = risk.icon,
                        contentDescription = stringResource(id = risk.labelRes),
                        tint = risk.tone,
                        modifier = Modifier.width(14.dp)
                    )
                    Text(
                        text = stringResource(id = risk.labelRes),
                        style = MaterialTheme.typography.labelSmall,
                        color = risk.tone
                    )
                }
            }
        }

        if (warningHorizon != null) {
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = if (midnightGlass) Color(0x33FFB020) else MaterialTheme.colorScheme.tertiaryContainer,
                border = BorderStroke(1.dp, if (midnightGlass) Color(0x33FFB020) else MaterialTheme.colorScheme.outlineVariant)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.xxs),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = stringResource(id = R.string.status_hypo_risk),
                        tint = MaterialTheme.colorScheme.onTertiaryContainer
                    )
                    Text(
                        text = stringResource(
                            id = R.string.overview_hypo_risk_window_detected,
                            warningHorizon.horizonMinutes
                        ),
                        style = MaterialTheme.typography.labelMedium,
                        color = if (midnightGlass) Color(0xFFFFD180) else MaterialTheme.colorScheme.onTertiaryContainer
                    )
                }
            }
        }
    }
}

@Composable
private fun PredictionsSection(
    horizons: List<HorizonPredictionUi>,
    midnightGlass: Boolean
) {
    val sorted = horizons.sortedBy { it.horizonMinutes }
    SectionCard {
        SectionLabel(
            text = stringResource(id = R.string.section_overview_predictions),
            infoText = stringResource(id = R.string.overview_info_predictions_section)
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Spacing.xs)
        ) {
            sorted.forEach { horizon ->
                PredictionCell(
                    horizon = horizon,
                    modifier = Modifier.weight(1f),
                    midnightGlass = midnightGlass
                )
            }
        }
    }
}

@Composable
private fun PredictionCell(
    horizon: HorizonPredictionUi,
    modifier: Modifier = Modifier,
    midnightGlass: Boolean
) {
    val (container, content, ciTone) = when {
        !midnightGlass -> Triple(
            MaterialTheme.colorScheme.primaryContainer,
            MaterialTheme.colorScheme.onSurface,
            MaterialTheme.colorScheme.onSurfaceVariant
        )
        horizon.pred != null && horizon.pred < 4.4 -> Triple(
            Color(0x55312735),
            Color(0xFFF8FAFC),
            Color(0xFFFFD180)
        )
        else -> Triple(
            Color(0xFF10275A),
            Color(0xFFF8FAFC),
            Color(0xFFB5C0D8)
        )
    }
    Surface(
        modifier = modifier,
        shape = InfoShape,
        color = container,
        border = BorderStroke(1.dp, if (midnightGlass) Color(0x1FFFFFFF) else MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = "${horizon.horizonMinutes}m",
                style = MaterialTheme.typography.labelSmall,
                color = ciTone
            )
            Text(
                text = UiFormatters.formatMmol(horizon.pred, 2),
                style = LocalNumericTypography.current.valueMedium.copy(fontSize = 29.sp),
                color = content
            )
            Text(
                text = stringResource(
                    id = R.string.overview_ci_template,
                    UiFormatters.formatMmol(horizon.ciLow, 2),
                    UiFormatters.formatMmol(horizon.ciHigh, 2)
                ),
                style = MaterialTheme.typography.labelSmall,
                color = ciTone
            )
        }
    }
}

@Composable
private fun UamSection(
    active: Boolean?,
    uci0Mmol5m: Double?,
    inferredCarbsLast60g: Double?,
    mode: String?,
    midnightGlass: Boolean
) {
    SectionCard {
        SectionLabel(
            text = stringResource(id = R.string.section_overview_uam_status),
            infoText = stringResource(id = R.string.overview_info_uam_section)
        )
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Spacing.xs)
            ) {
                UamInfoCard(
                    modifier = Modifier.weight(1f),
                    title = stringResource(id = R.string.label_active_state),
                    value = when (active) {
                        true -> stringResource(id = R.string.status_on_short)
                        false -> stringResource(id = R.string.status_off_short)
                        null -> stringResource(id = R.string.placeholder_missing)
                    },
                    midnightGlass = midnightGlass
                )
                UamInfoCard(
                    modifier = Modifier.weight(1f),
                    title = stringResource(id = R.string.overview_uam_uci0),
                    value = uci0Mmol5m?.let { "${UiFormatters.formatMmol(it, 2)} mmol/5m" }
                        ?: stringResource(id = R.string.placeholder_missing),
                    midnightGlass = midnightGlass
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Spacing.xs)
            ) {
                UamInfoCard(
                    modifier = Modifier.weight(1f),
                    title = stringResource(id = R.string.overview_label_inferred_60m),
                    value = inferredCarbsLast60g?.let { UiFormatters.formatGrams(it, 0) + " g" }
                        ?: stringResource(id = R.string.placeholder_missing),
                    midnightGlass = midnightGlass
                )
                UamInfoCard(
                    modifier = Modifier.weight(1f),
                    title = stringResource(id = R.string.overview_label_mode),
                    value = mode ?: stringResource(id = R.string.placeholder_missing),
                    midnightGlass = midnightGlass
                )
            }
        }
    }
}

@Composable
private fun UamInfoCard(
    title: String,
    value: String,
    modifier: Modifier = Modifier,
    midnightGlass: Boolean
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(10.dp),
        color = if (midnightGlass) Color(0xAA101D38) else MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(1.dp, if (midnightGlass) Color(0x1FFFFFFF) else MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium,
                color = if (midnightGlass) Color(0xFFF8FAFC) else MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = value,
                style = MaterialTheme.typography.bodySmall,
                color = if (midnightGlass) Color(0xFFB5C0D8) else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun TelemetrySection(chips: List<TelemetryChipUi>, midnightGlass: Boolean) {
    SectionCard {
        SectionLabel(
            text = stringResource(id = R.string.section_overview_telemetry),
            infoText = stringResource(id = R.string.overview_info_telemetry_section)
        )
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
            verticalArrangement = Arrangement.spacedBy(Spacing.xs)
        ) {
            chips.forEach { chip ->
                val line = buildString {
                    append(chip.label)
                    append(' ')
                    append(chip.value)
                    chip.unit?.takeIf { it.isNotBlank() }?.let {
                        append(' ')
                        append(it)
                    }
                }
                Surface(
                    shape = PillShape,
                    color = if (midnightGlass) Color(0xFF162544) else MaterialTheme.colorScheme.surfaceVariant,
                    border = BorderStroke(1.dp, if (midnightGlass) Color(0x1FFFFFFF) else MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier.semantics {
                        contentDescription = line
                    }
                ) {
                    Text(
                        text = line,
                        style = MaterialTheme.typography.labelMedium,
                        color = if (midnightGlass) Color(0xFFDCEBFF) else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(horizontal = 11.dp, vertical = 7.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun LastActionSection(action: LastActionUi?, midnightGlass: Boolean) {
    SectionCard {
        SectionLabel(
            text = stringResource(id = R.string.section_overview_last_action),
            infoText = stringResource(id = R.string.overview_info_last_action_section)
        )
        if (action == null) {
            Text(
                text = stringResource(id = R.string.overview_no_actions),
                style = MaterialTheme.typography.bodyMedium
            )
            return@SectionCard
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = actionSummary(action = action),
                style = MaterialTheme.typography.bodySmall,
                color = if (midnightGlass) Color(0xFFF8FAFC) else MaterialTheme.colorScheme.onSurface
            )
            StatusPill(status = action.status, midnightGlass = midnightGlass)
        }

        val idempotency = action.idempotencyKey ?: stringResource(id = R.string.placeholder_missing)
        Text(
            text = "${UiFormatters.formatTimestamp(action.timestamp)} · ${stringResource(id = R.string.overview_action_meta, idempotency)}",
            style = MaterialTheme.typography.labelSmall,
            color = if (midnightGlass) Color(0xFFB5C0D8) else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun StatusPill(status: String, midnightGlass: Boolean) {
    val normalized = status.uppercase(Locale.US)
    val isSent = normalized.contains("SENT")
    val isBlocked = normalized.contains("BLOCKED")
    val tone = if (midnightGlass) {
        when {
            isSent -> Color(0xFF9FFFB0)
            isBlocked -> Color(0xFFFFD37A)
            else -> Color(0xFFFF8A80)
        }
    } else {
        when {
            isSent -> MaterialTheme.colorScheme.onSecondaryContainer
            isBlocked -> MaterialTheme.colorScheme.onTertiaryContainer
            else -> MaterialTheme.colorScheme.error
        }
    }
    val bg = if (midnightGlass) {
        when {
            isSent -> Color(0x2200E676)
            isBlocked -> Color(0x33F59E0B)
            else -> Color(0x66B42318)
        }
    } else {
        when {
            isSent -> MaterialTheme.colorScheme.secondaryContainer
            isBlocked -> MaterialTheme.colorScheme.tertiaryContainer
            else -> MaterialTheme.colorScheme.errorContainer
        }
    }

    Surface(
        shape = PillShape,
        color = bg
    ) {
        Text(
            text = "● ${status.uppercase()}",
            style = MaterialTheme.typography.labelMedium,
            color = tone,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
        )
    }
}

@Composable
private fun ActionButtonsRow(
    canRunCycleNow: Boolean,
    onRunCycleNow: () -> Unit,
    onKillSwitch: () -> Unit,
    midnightGlass: Boolean
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Spacing.xs)
    ) {
        FilledTonalButton(
            onClick = onRunCycleNow,
            enabled = canRunCycleNow,
            modifier = Modifier.weight(1f),
            shape = RoundedCornerShape(16.dp)
        ) {
            Icon(Icons.Default.Bolt, contentDescription = stringResource(id = R.string.overview_run_cycle_now))
            Text(
                text = stringResource(id = R.string.overview_run_cycle_now),
                modifier = Modifier.padding(start = Spacing.xxs)
            )
        }

        OutlinedButton(
            onClick = onKillSwitch,
            modifier = Modifier.weight(1f),
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(1.dp, if (midnightGlass) Color(0x33FFB020) else MaterialTheme.colorScheme.outlineVariant)
        ) {
            Icon(
                Icons.Default.Warning,
                contentDescription = stringResource(id = R.string.overview_kill_switch_shortcut),
                tint = if (midnightGlass) Color(0xFFFFD180) else MaterialTheme.colorScheme.onErrorContainer
            )
            Text(
                text = stringResource(id = R.string.overview_kill_switch_shortcut),
                modifier = Modifier.padding(start = Spacing.xxs),
                color = if (midnightGlass) Color(0xFFFFD180) else MaterialTheme.colorScheme.onErrorContainer
            )
        }
    }
}

@Composable
private fun SectionCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    val midnightGlass = LocalUiStyle.current == UiStyle.MIDNIGHT_GLASS
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = if (midnightGlass) RoundedCornerShape(28.dp) else SectionShape,
        border = BorderStroke(1.dp, if (midnightGlass) Color(0x1FFFFFFF) else MaterialTheme.colorScheme.outlineVariant),
        colors = CardDefaults.cardColors(containerColor = if (midnightGlass) Color(0xCC0E1C36) else MaterialTheme.colorScheme.surface),
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
private fun SectionLabel(
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
                shape = RoundedCornerShape(12.dp),
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

private fun compactAgeLabel(minutes: Long?): String {
    val value = minutes ?: return "--"
    return if (value < 1L) "<1m" else "${value}m"
}

@Composable
private fun actionSummary(action: LastActionUi): String {
    return when {
        action.tempTargetMmol != null && action.durationMinutes != null -> {
            val value = UiFormatters.formatMmol(action.tempTargetMmol, 1)
            stringResource(
                id = R.string.overview_action_temp_target_summary,
                value,
                action.durationMinutes
            )
        }
        action.carbsGrams != null -> {
            stringResource(
                id = R.string.overview_action_carbs_summary,
                UiFormatters.formatGrams(action.carbsGrams, 1)
            )
        }
        !action.payloadSummary.isNullOrBlank() -> action.payloadSummary
        else -> stringResource(id = R.string.overview_action_fallback_summary, action.type)
    }
}

private data class RiskBadge(
    val labelRes: Int,
    val icon: ImageVector,
    val tone: Color,
    val bgColor: Color
)

@Composable
private fun riskBadge(glucose: Double?, stale: Boolean): RiskBadge {
    return when {
        stale -> RiskBadge(
            labelRes = R.string.status_stale_data,
            icon = Icons.Default.Warning,
            tone = MaterialTheme.colorScheme.onTertiaryContainer,
            bgColor = MaterialTheme.colorScheme.tertiaryContainer
        )
        glucose == null -> RiskBadge(
            labelRes = R.string.status_info,
            icon = Icons.Default.CheckCircle,
            tone = Color(0xFF5F6B7A),
            bgColor = MaterialTheme.colorScheme.surfaceVariant
        )
        glucose < 3.9 -> RiskBadge(
            labelRes = R.string.overview_glucose_low,
            icon = Icons.Default.Error,
            tone = MaterialTheme.colorScheme.onErrorContainer,
            bgColor = MaterialTheme.colorScheme.errorContainer
        )
        glucose > 13.9 -> RiskBadge(
            labelRes = R.string.overview_glucose_very_high,
            icon = Icons.Default.TrendingUp,
            tone = MaterialTheme.colorScheme.onErrorContainer,
            bgColor = MaterialTheme.colorScheme.errorContainer
        )
        glucose > 10.0 -> RiskBadge(
            labelRes = R.string.overview_glucose_high,
            icon = Icons.Default.TrendingUp,
            tone = MaterialTheme.colorScheme.onTertiaryContainer,
            bgColor = MaterialTheme.colorScheme.tertiaryContainer
        )
        else -> RiskBadge(
            labelRes = R.string.overview_glucose_target,
            icon = Icons.Default.TrendingDown,
            tone = MaterialTheme.colorScheme.onSecondaryContainer,
            bgColor = MaterialTheme.colorScheme.secondaryContainer
        )
    }
}

@Composable
private fun deltaTone(delta: Double?): Color {
    return when {
        delta == null -> MaterialTheme.colorScheme.onSurfaceVariant
        delta > 0.0 -> MaterialTheme.colorScheme.onTertiaryContainer
        delta < 0.0 -> MaterialTheme.colorScheme.onSecondaryContainer
        else -> MaterialTheme.colorScheme.onSurface
    }
}

@Composable
private fun overviewSensorLagRolloutTone(status: String, midnightGlass: Boolean): Color {
    return when (status.uppercase(Locale.US)) {
        "ACTIVE_CANDIDATE" -> if (midnightGlass) Color(0xFF9ED9B0) else MaterialTheme.colorScheme.secondary
        "HOLD" -> if (midnightGlass) Color(0xFFFFD180) else MaterialTheme.colorScheme.tertiary
        else -> if (midnightGlass) Color(0xFFB5C0D8) else MaterialTheme.colorScheme.onSurfaceVariant
    }
}

private fun overviewSensorLagRolloutStatusLabelResId(status: String): Int {
    return when (status.uppercase(Locale.US)) {
        "ACTIVE_CANDIDATE" -> R.string.analytics_daily_report_sensor_lag_rollout_active
        "SHADOW_FIRST" -> R.string.analytics_daily_report_sensor_lag_rollout_shadow
        "HOLD" -> R.string.analytics_daily_report_sensor_lag_rollout_hold
        else -> R.string.analytics_daily_report_sensor_lag_rollout_insufficient
    }
}

private fun overviewSensorLagBucketLabelResId(bucket: String): Int {
    return when (bucket) {
        "<24h" -> R.string.analytics_daily_report_sensor_lag_bucket_lt24h
        "1-10d" -> R.string.analytics_daily_report_sensor_lag_bucket_1_10d
        "10-12d" -> R.string.analytics_daily_report_sensor_lag_bucket_10_12d
        "12-14d" -> R.string.analytics_daily_report_sensor_lag_bucket_12_14d
        else -> R.string.analytics_daily_report_sensor_lag_bucket_gt14d
    }
}

@Preview(showBackground = true)
@Composable
private fun OverviewScreenPreview() {
    AapsCopilotTheme {
        OverviewScreen(
            state = OverviewUiState(
                loadState = ScreenLoadState.READY,
                isStale = true,
                glucose = 8.7,
                correctedGlucose = 9.0,
                delta = 0.24,
                sampleAgeMinutes = 7,
                isProMode = true,
                sensorLagRolloutVerdict = SensorLagRolloutVerdictUi(
                    status = "SHADOW_FIRST",
                    bucket = "10-12d"
                ),
                horizons = listOf(
                    HorizonPredictionUi(5, 8.92, 8.60, 9.22),
                    HorizonPredictionUi(30, 9.78, 8.10, 10.95),
                    HorizonPredictionUi(60, 10.26, 8.05, 12.12, warningWideCi = true)
                ),
                uamActive = true,
                uci0Mmol5m = 0.18,
                inferredCarbsLast60g = 24.0,
                uamModeLabel = "BOOST",
                telemetryChips = listOf(
                    TelemetryChipUi("IOB", "1.8", "U"),
                    TelemetryChipUi("COB", "22", "g"),
                    TelemetryChipUi("Activity", "1.16", null),
                    TelemetryChipUi("Steps", "1234", null)
                ),
                lastAction = LastActionUi(
                    type = "temp_target",
                    status = "SENT",
                    timestamp = System.currentTimeMillis(),
                    tempTargetMmol = 5.2,
                    durationMinutes = 30,
                    idempotencyKey = "tt:bucket:2026-03-02T12:40"
                )
            ),
            onRunCycleNow = {},
            onSetKillSwitch = {}
        )
    }
}
