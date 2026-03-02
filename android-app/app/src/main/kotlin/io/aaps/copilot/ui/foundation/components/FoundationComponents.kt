package io.aaps.copilot.ui.foundation.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.aaps.copilot.R
import io.aaps.copilot.ui.foundation.design.AppElevation
import io.aaps.copilot.ui.foundation.design.Spacing
import io.aaps.copilot.ui.foundation.theme.AapsCopilotTheme
import io.aaps.copilot.ui.foundation.theme.LocalNumericTypography
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
    modifier: Modifier = Modifier
) {
    val (icon, statusText) = metricStatusVisual(status)
    Card(
        modifier = modifier,
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
                Icon(imageVector = icon, contentDescription = statusText)
                Text(text = title, style = MaterialTheme.typography.labelLarge)
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                text = value?.let { formatDecimal(it, decimals) } ?: stringResource(id = R.string.placeholder_missing),
                style = LocalNumericTypography.current.valueLarge
            )
            Text(
                text = unit,
                style = MaterialTheme.typography.bodyMedium,
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
        onClick = {},
        enabled = false,
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
    Card(
        modifier = modifier,
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
                    style = MaterialTheme.typography.titleSmall
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
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
private fun metricStatusVisual(status: MetricStatus): Pair<androidx.compose.ui.graphics.vector.ImageVector, String> = when (status) {
    MetricStatus.NORMAL -> Icons.Default.Info to stringResource(R.string.status_info)
    MetricStatus.GOOD -> Icons.Default.CheckCircle to stringResource(R.string.status_ok)
    MetricStatus.WARNING -> Icons.Default.Warning to stringResource(R.string.status_warn)
    MetricStatus.CRITICAL -> Icons.Default.Error to stringResource(R.string.status_alert)
}

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
