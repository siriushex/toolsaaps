package io.aaps.copilot.ui.foundation.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.aaps.copilot.R
import io.aaps.copilot.ui.foundation.design.Spacing
import io.aaps.copilot.ui.foundation.format.UiFormatters
import java.util.Locale

@Composable
internal fun ColumnScope.CircadianReplaySummaryContent(
    summary: CircadianReplaySummaryUi?,
    emptyText: String
) {
    if (summary == null || summary.windows.isEmpty()) {
        Text(
            text = emptyText,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        return
    }
    Text(
        text = stringResource(
            id = R.string.circadian_replay_generated_at,
            formatTsShort(summary.generatedAtTs)
        ),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    summary.windows.forEach { window ->
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.40f),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(Spacing.sm),
                verticalArrangement = Arrangement.spacedBy(Spacing.xs)
            ) {
                Text(
                    text = stringResource(
                        id = R.string.circadian_replay_window_summary,
                        circadianWindowLabel(window.days),
                        window.appliedRows,
                        formatPercent(window.appliedPct)
                    ),
                    style = MaterialTheme.typography.labelLarge
                )
                Text(
                    text = stringResource(
                        id = R.string.circadian_replay_shift_summary,
                        UiFormatters.formatSignedDelta(window.meanShift30),
                        UiFormatters.formatSignedDelta(window.meanShift60)
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                window.buckets.forEach { bucket ->
                    Text(
                        text = circadianBucketLabel(bucket.bucket),
                        style = MaterialTheme.typography.labelMedium
                    )
                    bucket.metrics.sortedBy { it.horizonMinutes }.forEach { metric ->
                        Text(
                            text = stringResource(
                                id = R.string.circadian_replay_metric_line,
                                metric.horizonMinutes,
                                UiFormatters.formatMmol(metric.maeBaseline),
                                UiFormatters.formatMmol(metric.maeCircadian),
                                UiFormatters.formatSignedDelta(metric.deltaMmol),
                                formatPercent(metric.deltaPct),
                                metric.sampleCount,
                                formatPercent(metric.winRate * 100.0),
                                metric.bucketStatus
                            ),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun circadianBucketLabel(bucket: String): String {
    return when (bucket.uppercase(Locale.US)) {
        "LOW_ACUTE" -> stringResource(id = R.string.circadian_replay_bucket_low_acute)
        "WEEKDAY" -> stringResource(id = R.string.circadian_replay_bucket_weekday)
        "WEEKEND" -> stringResource(id = R.string.circadian_replay_bucket_weekend)
        else -> stringResource(id = R.string.circadian_replay_bucket_all)
    }
}

@Composable
private fun circadianWindowLabel(days: Int): String {
    return when (days) {
        1 -> stringResource(id = R.string.circadian_replay_window_24h)
        7 -> stringResource(id = R.string.circadian_replay_window_7d)
        else -> stringResource(id = R.string.circadian_replay_window_days, days)
    }
}

private fun formatPercent(value: Double): String {
    return String.format(Locale.US, "%.1f%%", value)
}

private fun formatTsShort(ts: Long): String {
    val instant = java.time.Instant.ofEpochMilli(ts)
    val formatter = java.time.format.DateTimeFormatter.ofPattern("dd.MM HH:mm")
        .withZone(java.time.ZoneId.systemDefault())
    return formatter.format(instant)
}
