package io.aaps.copilot.ui.foundation.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.aaps.copilot.R
import io.aaps.copilot.config.UiStyle
import io.aaps.copilot.ui.foundation.format.UiFormatters
import io.aaps.copilot.ui.foundation.design.AppElevation
import io.aaps.copilot.ui.foundation.design.Spacing
import io.aaps.copilot.ui.foundation.theme.AapsCopilotTheme
import io.aaps.copilot.ui.foundation.theme.LocalNumericTypography
import io.aaps.copilot.ui.foundation.theme.LocalUiStyle
import java.util.Locale

enum class MetricStatus {
    NORMAL,
    GOOD,
    WARNING,
    CRITICAL
}

enum class SafetyBannerType {
    INFO,
    WARNING,
    ERROR,
    SUCCESS
}

@Composable
fun SectionHeader(
    text: String,
    modifier: Modifier = Modifier
) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        modifier = modifier.padding(top = Spacing.xs, bottom = Spacing.xxs)
    )
}

@Composable
fun MetricCard(
    title: String,
    value: Double?,
    unit: String,
    subtitle: String? = null,
    status: MetricStatus = MetricStatus.NORMAL,
    decimals: Int = 2,
    emphasized: Boolean = false,
    modifier: Modifier = Modifier
) {
    val visual = metricStatusVisual(status)
    val valueText = value?.let { formatDecimal(it, decimals) } ?: stringResource(id = R.string.placeholder_missing)
    val semanticsLabel = "$title $valueText $unit ${visual.label}"
    Card(
        modifier = modifier.semantics { contentDescription = semanticsLabel },
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = AppElevation.level2)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.md),
            verticalArrangement = Arrangement.spacedBy(Spacing.xxs)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.xs)
            ) {
                Icon(imageVector = visual.icon, contentDescription = visual.label, tint = visual.tone)
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.weight(1f, fill = false)
                )
                AnimatedContent(
                    targetState = visual.label,
                    transitionSpec = { fadeIn(animationSpec = tween(220)) togetherWith fadeOut(animationSpec = tween(180)) },
                    label = "metricStatus"
                ) { targetStatus ->
                    Text(
                        text = targetStatus,
                        style = MaterialTheme.typography.labelSmall,
                        color = visual.tone
                    )
                }
            }
            AnimatedContent(
                targetState = valueText,
                transitionSpec = { fadeIn(animationSpec = tween(220)) togetherWith fadeOut(animationSpec = tween(180)) },
                label = "metricValue"
            ) { target ->
                Text(
                    text = target,
                    style = if (emphasized) LocalNumericTypography.current.valueLarge else LocalNumericTypography.current.valueMedium
                )
            }
            Text(
                text = unit,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
fun PredictionHorizonChip(
    horizonMinutes: Int,
    pred: Double?,
    ciLow: Double?,
    ciHigh: Double?,
    onClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val predText = pred?.let { formatDecimal(it, 2) } ?: stringResource(R.string.placeholder_missing)
    val lowText = ciLow?.let { formatDecimal(it, 2) } ?: stringResource(R.string.placeholder_missing)
    val highText = ciHigh?.let { formatDecimal(it, 2) } ?: stringResource(R.string.placeholder_missing)
    val label = stringResource(
        id = R.string.prediction_chip_template,
        horizonMinutes,
        predText,
        lowText,
        highText
    )
    AssistChip(
        onClick = { onClick?.invoke() },
        enabled = onClick != null,
        label = {
            Text(text = label, style = LocalNumericTypography.current.valueSmall)
        },
        shape = RoundedCornerShape(12.dp),
        modifier = modifier
    )
}

@Composable
fun SafetyBanner(
    type: SafetyBannerType,
    text: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val (icon, tone) = when (type) {
        SafetyBannerType.INFO -> Icons.Default.Info to MaterialTheme.colorScheme.primary
        SafetyBannerType.WARNING -> Icons.Default.Warning to MaterialTheme.colorScheme.tertiary
        SafetyBannerType.ERROR -> Icons.Default.Error to MaterialTheme.colorScheme.error
        SafetyBannerType.SUCCESS -> Icons.Default.CheckCircle to MaterialTheme.colorScheme.secondary
    }
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = tone.copy(alpha = 0.12f)),
        elevation = CardDefaults.cardElevation(defaultElevation = AppElevation.level1)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.md),
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(imageVector = icon, contentDescription = null, tint = tone)
            Text(text = text, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            if (!actionLabel.isNullOrBlank() && onAction != null) {
                TextButton(onClick = onAction) {
                    Text(actionLabel)
                }
            }
        }
    }
}

@Composable
fun DebugRow(
    key: String,
    value: String,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = Spacing.xxs),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = key, style = MaterialTheme.typography.bodyMedium)
        Text(text = value, style = LocalNumericTypography.current.valueSmall)
    }
}

@Composable
fun AppHealthBanner(
    staleData: Boolean,
    killSwitchEnabled: Boolean,
    lastSyncText: String,
    modifier: Modifier = Modifier
) {
    val midnightGlass = LocalUiStyle.current == UiStyle.MIDNIGHT_GLASS
    Card(
        modifier = modifier,
        border = if (midnightGlass) BorderStroke(1.dp, androidx.compose.ui.graphics.Color(0x1FFFFFFF)) else null,
        colors = CardDefaults.cardColors(
            containerColor = if (midnightGlass) androidx.compose.ui.graphics.Color(0xCC0E1C36) else MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = AppElevation.level1)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.md),
            verticalArrangement = Arrangement.spacedBy(Spacing.xs)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(id = R.string.app_health_title),
                    style = MaterialTheme.typography.titleSmall,
                    color = if (midnightGlass) androidx.compose.ui.graphics.Color(0xFFF8FAFC) else MaterialTheme.colorScheme.onSurface
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    BadgedBox(
                        badge = {
                            Badge {
                                Text(
                                    text = if (staleData) {
                                        stringResource(R.string.status_stale_short)
                                    } else {
                                        stringResource(R.string.status_ok_short)
                                    }
                                )
                            }
                        }
                    ) {
                        Icon(
                            imageVector = if (staleData) Icons.Default.Warning else Icons.Default.CheckCircle,
                            contentDescription = if (staleData) {
                                stringResource(R.string.status_stale_short)
                            } else {
                                stringResource(R.string.status_ok_short)
                            }
                        )
                    }
                    BadgedBox(
                        badge = {
                            Badge {
                                Text(
                                    text = if (killSwitchEnabled) {
                                        stringResource(R.string.status_on_short)
                                    } else {
                                        stringResource(R.string.status_off_short)
                                    }
                                )
                            }
                        }
                    ) {
                        Icon(
                            imageVector = if (killSwitchEnabled) Icons.Default.Error else Icons.Default.CheckCircle,
                            contentDescription = if (killSwitchEnabled) {
                                stringResource(R.string.status_on_short)
                            } else {
                                stringResource(R.string.status_off_short)
                            }
                        )
                    }
                }
            }
            Text(
                text = stringResource(R.string.app_health_last_sync, lastSyncText),
                style = MaterialTheme.typography.bodySmall,
                color = if (midnightGlass) androidx.compose.ui.graphics.Color(0xFFB5C0D8) else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun OverviewBaseTargetBanner(
    baseTargetMmol: Double,
    minTargetMmol: Double,
    maxTargetMmol: Double,
    staleData: Boolean,
    killSwitchEnabled: Boolean,
    lastSyncText: String,
    onBaseTargetChange: (Double) -> Unit,
    modifier: Modifier = Modifier
) {
    val uiStyle = LocalUiStyle.current
    val midnightGlass = uiStyle == UiStyle.MIDNIGHT_GLASS
    val cardColor = when (uiStyle) {
        UiStyle.MIDNIGHT_GLASS -> androidx.compose.ui.graphics.Color(0xCC0C1830)
        UiStyle.DYNAMIC_GRADIENT -> MaterialTheme.colorScheme.surface.copy(alpha = 0.94f)
        UiStyle.CLASSIC -> MaterialTheme.colorScheme.surface
    }
    val titleTone = if (midnightGlass) androidx.compose.ui.graphics.Color(0xFFF6FAFF) else MaterialTheme.colorScheme.onSurface
    val secondaryTone = if (midnightGlass) androidx.compose.ui.graphics.Color(0xFFB5C0D8) else MaterialTheme.colorScheme.onSurfaceVariant
    val heroColor = when (uiStyle) {
        UiStyle.MIDNIGHT_GLASS -> androidx.compose.ui.graphics.Color(0xE0122545)
        UiStyle.DYNAMIC_GRADIENT -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.72f)
        UiStyle.CLASSIC -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.9f)
    }
    val heroBorder = when (uiStyle) {
        UiStyle.MIDNIGHT_GLASS -> androidx.compose.ui.graphics.Color(0x24FFFFFF)
        UiStyle.DYNAMIC_GRADIENT -> MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
        UiStyle.CLASSIC -> MaterialTheme.colorScheme.outlineVariant
    }
    val buttonContainer = when (uiStyle) {
        UiStyle.MIDNIGHT_GLASS -> androidx.compose.ui.graphics.Color(0xFF17335F)
        UiStyle.DYNAMIC_GRADIENT -> MaterialTheme.colorScheme.secondaryContainer
        UiStyle.CLASSIC -> MaterialTheme.colorScheme.secondaryContainer
    }
    val buttonContent = if (midnightGlass) androidx.compose.ui.graphics.Color(0xFFF6FAFF) else MaterialTheme.colorScheme.onSecondaryContainer
    val heroValue = UiFormatters.formatMmol(baseTargetMmol, 1)
    Card(
        modifier = modifier,
        border = if (midnightGlass) BorderStroke(1.dp, androidx.compose.ui.graphics.Color(0x1FFFFFFF)) else null,
        colors = CardDefaults.cardColors(containerColor = cardColor),
        shape = RoundedCornerShape(20.dp),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = AppElevation.level2)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.md),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = stringResource(id = R.string.metric_base_target),
                        style = MaterialTheme.typography.titleMedium,
                        color = titleTone
                    )
                    Text(
                        text = stringResource(
                            id = R.string.overview_base_target_range,
                            UiFormatters.formatMmol(minTargetMmol, 1),
                            UiFormatters.formatMmol(maxTargetMmol, 1)
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = secondaryTone
                    )
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (killSwitchEnabled) {
                        OverviewInlineBadge(
                            label = stringResource(id = R.string.status_kill_switch),
                            icon = Icons.Default.Error,
                            containerColor = if (midnightGlass) androidx.compose.ui.graphics.Color(0xFF5A1E25) else MaterialTheme.colorScheme.errorContainer,
                            contentColor = if (midnightGlass) androidx.compose.ui.graphics.Color(0xFFFFD0D3) else MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                    OverviewInlineBadge(
                        label = if (staleData) {
                            stringResource(id = R.string.status_stale_data)
                        } else {
                            stringResource(id = R.string.status_live_data)
                        },
                        icon = if (staleData) Icons.Default.Warning else Icons.Default.CheckCircle,
                        containerColor = when {
                            staleData && midnightGlass -> androidx.compose.ui.graphics.Color(0xFF46330E)
                            staleData -> MaterialTheme.colorScheme.tertiaryContainer
                            midnightGlass -> androidx.compose.ui.graphics.Color(0xFF17335F)
                            else -> MaterialTheme.colorScheme.secondaryContainer
                        },
                        contentColor = when {
                            staleData && midnightGlass -> androidx.compose.ui.graphics.Color(0xFFFFDE9B)
                            staleData -> MaterialTheme.colorScheme.onTertiaryContainer
                            midnightGlass -> androidx.compose.ui.graphics.Color(0xFFDCEBFF)
                            else -> MaterialTheme.colorScheme.onSecondaryContainer
                        }
                    )
                }
            }
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = heroColor,
                shape = RoundedCornerShape(24.dp),
                border = BorderStroke(1.dp, heroBorder)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Spacing.sm, vertical = Spacing.sm),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OverviewTargetStepperButton(
                        enabled = baseTargetMmol > minTargetMmol + 0.0001,
                        icon = Icons.Default.Remove,
                        contentDescription = stringResource(id = R.string.overview_base_target_decrease),
                        containerColor = buttonContainer,
                        contentColor = buttonContent,
                        onClick = {
                            onBaseTargetChange((baseTargetMmol - 0.1).coerceAtLeast(minTargetMmol))
                        }
                    )
                    Column(
                        modifier = Modifier.weight(1f),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        AnimatedContent(
                            targetState = heroValue,
                            transitionSpec = { fadeIn(animationSpec = tween(220)) togetherWith fadeOut(animationSpec = tween(180)) },
                            label = "baseTargetValue"
                        ) { target ->
                            Text(
                                text = target,
                                style = LocalNumericTypography.current.valueLarge,
                                color = titleTone
                            )
                        }
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = stringResource(id = R.string.unit_mmol_l),
                                style = MaterialTheme.typography.bodyMedium,
                                color = secondaryTone
                            )
                            Box(
                                modifier = Modifier
                                    .size(4.dp)
                                    .background(secondaryTone.copy(alpha = 0.7f), RoundedCornerShape(999.dp))
                            )
                            Text(
                                text = "±0.1",
                                style = MaterialTheme.typography.labelMedium,
                                color = secondaryTone
                            )
                        }
                    }
                    OverviewTargetStepperButton(
                        enabled = baseTargetMmol < maxTargetMmol - 0.0001,
                        icon = Icons.Default.Add,
                        contentDescription = stringResource(id = R.string.overview_base_target_increase),
                        containerColor = buttonContainer,
                        contentColor = buttonContent,
                        onClick = {
                            onBaseTargetChange((baseTargetMmol + 0.1).coerceAtMost(maxTargetMmol))
                        }
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        color = if (midnightGlass) androidx.compose.ui.graphics.Color(0x141AFFFFFF) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f),
                        shape = RoundedCornerShape(999.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.spacedBy(Spacing.xxs),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Sync,
                                contentDescription = null,
                                tint = secondaryTone,
                                modifier = Modifier.size(14.dp)
                            )
                            Text(
                                text = stringResource(id = R.string.app_health_last_sync, lastSyncText),
                                style = MaterialTheme.typography.labelMedium,
                                color = secondaryTone
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.width(Spacing.xs))
                Text(
                    text = "${UiFormatters.formatMmol(minTargetMmol, 1)}-${UiFormatters.formatMmol(maxTargetMmol, 1)}",
                    style = MaterialTheme.typography.labelMedium,
                    color = secondaryTone
                )
            }
        }
    }
}

@Composable
private fun OverviewTargetStepperButton(
    enabled: Boolean,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    containerColor: androidx.compose.ui.graphics.Color,
    contentColor: androidx.compose.ui.graphics.Color,
    onClick: () -> Unit
) {
    FilledTonalIconButton(
        onClick = onClick,
        enabled = enabled,
        colors = androidx.compose.material3.IconButtonDefaults.filledTonalIconButtonColors(
            containerColor = containerColor,
            contentColor = contentColor,
            disabledContainerColor = containerColor.copy(alpha = 0.35f),
            disabledContentColor = contentColor.copy(alpha = 0.45f)
        )
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription
        )
    }
}

@Composable
private fun OverviewInlineBadge(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    containerColor: androidx.compose.ui.graphics.Color,
    contentColor: androidx.compose.ui.graphics.Color
) {
    Surface(
        color = containerColor,
        shape = RoundedCornerShape(999.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(Spacing.xxs),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = contentColor,
                modifier = Modifier.size(14.dp)
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = contentColor
            )
        }
    }
}

@Composable
private fun StatusBadge(
    staleData: Boolean,
    killSwitchEnabled: Boolean
) {
    val label = when {
        killSwitchEnabled -> stringResource(id = R.string.status_kill_switch)
        staleData -> stringResource(id = R.string.status_stale_data)
        else -> stringResource(id = R.string.status_live_data)
    }
    val icon = when {
        killSwitchEnabled -> Icons.Default.Error
        staleData -> Icons.Default.Warning
        else -> Icons.Default.CheckCircle
    }
    val tone = when {
        killSwitchEnabled -> MaterialTheme.colorScheme.errorContainer
        staleData -> MaterialTheme.colorScheme.tertiaryContainer
        else -> MaterialTheme.colorScheme.secondaryContainer
    }
    val fg = when {
        killSwitchEnabled -> MaterialTheme.colorScheme.onErrorContainer
        staleData -> MaterialTheme.colorScheme.onTertiaryContainer
        else -> MaterialTheme.colorScheme.onSecondaryContainer
    }
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = tone
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(Spacing.xxs),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(imageVector = icon, contentDescription = null, tint = fg)
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = fg
            )
        }
    }
}

@Composable
private fun metricStatusVisual(status: MetricStatus): MetricVisual = when (status) {
    MetricStatus.NORMAL -> MetricVisual(
        icon = Icons.Default.Info,
        label = stringResource(R.string.status_low_risk),
        tone = MaterialTheme.colorScheme.onSurfaceVariant
    )
    MetricStatus.GOOD -> MetricVisual(
        icon = Icons.Default.CheckCircle,
        label = stringResource(R.string.status_low_risk),
        tone = MaterialTheme.colorScheme.secondary
    )
    MetricStatus.WARNING -> MetricVisual(
        icon = Icons.Default.Warning,
        label = stringResource(R.string.status_warning_risk),
        tone = MaterialTheme.colorScheme.tertiary
    )
    MetricStatus.CRITICAL -> MetricVisual(
        icon = Icons.Default.Error,
        label = stringResource(R.string.status_hypo_risk),
        tone = MaterialTheme.colorScheme.error
    )
}

private data class MetricVisual(
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val label: String,
    val tone: androidx.compose.ui.graphics.Color
)

private fun formatDecimal(value: Double, decimals: Int): String {
    return String.format(Locale.US, "%.${decimals}f", value)
}

@Preview(showBackground = true)
@Composable
private fun MetricCardPreview() {
    AapsCopilotTheme {
        MetricCard(
            title = stringResource(id = R.string.metric_current_glucose),
            value = 5.87,
            unit = stringResource(id = R.string.unit_mmol_l),
            subtitle = stringResource(id = R.string.status_ok),
            status = MetricStatus.GOOD
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun PredictionChipPreview() {
    AapsCopilotTheme {
        PredictionHorizonChip(
            horizonMinutes = 30,
            pred = 6.2,
            ciLow = 5.9,
            ciHigh = 6.9
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun SafetyBannerPreview() {
    AapsCopilotTheme {
        SafetyBanner(
            type = SafetyBannerType.WARNING,
            text = stringResource(id = R.string.state_stale)
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun AppHealthBannerPreview() {
    AapsCopilotTheme {
        AppHealthBanner(
            staleData = true,
            killSwitchEnabled = false,
            lastSyncText = "2 min"
        )
    }
}
