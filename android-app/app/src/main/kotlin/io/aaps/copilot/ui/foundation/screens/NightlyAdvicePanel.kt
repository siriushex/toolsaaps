package io.aaps.copilot.ui.foundation.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.aaps.copilot.R
import io.aaps.copilot.data.repository.NightlyAdviceSnapshot
import io.aaps.copilot.scheduler.NightlyAdviceSchedule
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NightlyAdvicePanel(report: NightlyAdviceSnapshot, discuss: (String) -> Unit) {
    var days by rememberSaveable { mutableIntStateOf(report.windows.lastOrNull { it.usable }?.days ?: 3) }
    val selected = report.windows.firstOrNull { it.days == days } ?: report.windows.first()
    val locale = LocalConfiguration.current.locales[0] ?: Locale.getDefault()
    val zone = ZoneId.of(report.zoneId)
    val date = remember(locale, zone) { DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm", locale).withZone(zone) }
    fun timestamp(ts: Long) = date.format(Instant.ofEpochMilli(ts))
    fun number(value: Double?) = value?.let { ClinicalReportFormatter.formatNumber(it, 1, locale) } ?: "--"
    fun percent(value: Double?) = value?.let {
        val decimals = if (abs(it - kotlin.math.round(it)) < 0.05) 0 else 1
        ClinicalReportFormatter.formatNumber(it, decimals, locale) + "%"
    } ?: "--"
    val period = stringResource(R.string.ai_advice_period, timestamp(selected.fromTs), timestamp(report.throughTs))
    val baseSource = stringResource(R.string.ai_advice_source, period, timestamp(report.generatedAt), report.zoneId)
    val now = Instant.ofEpochMilli(System.currentTimeMillis()).atZone(ZoneId.systemDefault())
    val currentEnd = NightlyAdviceSchedule.windowEnd(now).toInstant().toEpochMilli()
    val stale = report.throughTs != currentEnd || report.generatedAt > now.toInstant().toEpochMilli()
    val recentIncomplete = days > 3 && !report.windows.first().usable
    val staleText = if (stale) stringResource(R.string.ai_advice_stale) else null
    val recentText = if (recentIncomplete) stringResource(R.string.ai_advice_recent_incomplete) else null
    val source = listOfNotNull(baseSource, staleText, recentText).joinToString("\n")
    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.ai_advice_history), Modifier.semantics { heading() }, style = MaterialTheme.typography.titleSmall)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            listOf(3, 7, 14).forEachIndexed { index, count ->
                SegmentedButton(selected = days == count, onClick = { days = count },
                    shape = SegmentedButtonDefaults.itemShape(index, 3),
                    modifier = Modifier.testTag("ai_advice_window_$count")) {
                    Text(stringResource(R.string.ai_focus_period_days, count))
                }
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(period, Modifier.testTag("ai_advice_period"), style = MaterialTheme.typography.bodySmall)
            Text(stringResource(R.string.ai_advice_updated, timestamp(report.generatedAt)) + " · " + report.zoneId,
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(pluralStringResource(R.plurals.ai_advice_coverage, days, number(selected.coveragePct), selected.qualityDays, days),
                style = MaterialTheme.typography.bodySmall)
            Text(stringResource(R.string.ai_advice_nightly), style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (staleText != null) Text(staleText, Modifier.testTag("ai_advice_stale"),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        if (recentText != null) Text(recentText, Modifier.testTag("ai_advice_recent_incomplete"),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (selected.inRangePct != null) {
            Column(Modifier.fillMaxWidth().testTag("ai_advice_metrics"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(R.string.ai_advice_below, R.string.ai_advice_range, R.string.ai_advice_above).forEach { label ->
                        Text(stringResource(label), Modifier.weight(1f), style = MaterialTheme.typography.labelSmall)
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(selected.belowPct, selected.inRangePct, selected.abovePct).forEachIndexed { index, value ->
                        Text(percent(value), Modifier.weight(1f).testTag("ai_advice_metric_$index"),
                            style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
            Text(stringResource(R.string.ai_advice_reference_range), style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            val parts = listOf((selected.belowPct ?: 0.0) to MaterialTheme.colorScheme.error,
                selected.inRangePct to Color(0xFF258575), (selected.abovePct ?: 0.0) to Color(0xFFA36B13))
            Canvas(Modifier.fillMaxWidth().height(6.dp)) {
                var start = 0f
                parts.forEach { (pct, color) ->
                    val width = (size.width * pct / 100.0).toFloat()
                    drawRect(color, androidx.compose.ui.geometry.Offset(start, 0f), androidx.compose.ui.geometry.Size(width, size.height))
                    start += width
                }
            }
        }
        if (!selected.usable) {
            Text(stringResource(R.string.ai_advice_insufficient), Modifier.testTag("ai_advice_insufficient"),
                style = MaterialTheme.typography.bodyMedium)
        } else {
            val quality = pluralStringResource(R.plurals.ai_advice_coverage, days, number(selected.coveragePct), selected.qualityDays, days)
            var count = 0
            if (selected.lowDays >= 2) {
                count++
                AdviceObservation(stringResource(R.string.ai_advice_low_title),
                    pluralStringResource(R.plurals.ai_advice_low_evidence, days, selected.lowDays, days, number(selected.belowPct)),
                    stringResource(R.string.ai_advice_low_action), source, quality, "low", discuss)
            }
            selected.recentDeltaPp?.takeIf { abs(it) >= 5.0 }?.let { delta ->
                count++
                AdviceObservation(stringResource(R.string.ai_advice_trend_title),
                    pluralStringResource(R.plurals.ai_advice_trend_evidence, days - 3, String.format(locale, "%+.1f", delta), days - 3),
                    stringResource(R.string.ai_advice_trend_action), source, quality, "trend", discuss)
            }
            if (selected.highDays >= 2) {
                count++
                AdviceObservation(stringResource(R.string.ai_advice_high_title),
                    pluralStringResource(R.plurals.ai_advice_high_evidence, days, selected.highDays, days, number(selected.abovePct)),
                    stringResource(R.string.ai_advice_high_action), source, quality, "high", discuss)
            }
            if (count == 0) {
                Text(stringResource(R.string.ai_advice_no_pattern), style = MaterialTheme.typography.bodyMedium)
            }
        }
        Text(stringResource(R.string.ai_advice_scope), style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun AdviceObservation(title: String, evidence: String, action: String, source: String,
    quality: String, key: String, discuss: (String) -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        HorizontalDivider()
        Text(title, Modifier.semantics { heading() }, style = MaterialTheme.typography.titleSmall)
        Text(evidence, style = MaterialTheme.typography.bodyMedium)
        Text(action, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        TextButton({ discuss(listOf(source, quality, title, evidence, action).joinToString("\n")) },
            Modifier.testTag("ai_advice_discuss_$key")) { Text(stringResource(R.string.ai_focus_discuss)) }
    }
}
