package io.aaps.copilot.ui.foundation.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Description
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.aaps.copilot.R
import io.aaps.copilot.ui.foundation.design.Spacing
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private data class DisplayObservation(val preview: String, val details: String)

@Composable
internal fun AiFocusDisplayReport(display: ClinicalDisplayUi, refreshing: Boolean, failed: Boolean,
    onRetry: () -> Unit, onDetails: () -> Unit, discuss: (String) -> Unit) {
    var days by rememberSaveable { mutableIntStateOf(1) }
    var selected by remember { mutableStateOf<String?>(null) }
    var periodsOpen by remember { mutableStateOf(false) }
    val summary = display.summaries.firstOrNull { it.days == days } ?: display.summaries.firstOrNull() ?: return
    val locale = LocalConfiguration.current.locales[0]
    val date = remember(display.generatedAtTs, display.zoneId, locale) {
        DateTimeFormatter.ofPattern("dd MMM yyyy, HH:mm", locale).withZone(ZoneId.of(display.zoneId))
            .format(Instant.ofEpochMilli(display.generatedAtTs))
    }
    val source = display.complete?.metadata?.let {
        listOfNotNull(clinicalAiProviderLabel(it.providerId), it.model).joinToString(" / ")
    } ?: stringResource(R.string.ai_focus_local_summary)
    val period = if (summary.days == 1) stringResource(R.string.ai_focus_period_day)
        else stringResource(R.string.ai_focus_period_days, summary.days)
    val sourceLine = "$period · $date · ${display.zoneId}\n$source"
    val observations = if (display.complete != null) display.complete.patterns.take(3).map { finding ->
        val evidence = stringResource(R.string.clinical_report_evidence_line,
            stringResource(clinicalTextString(finding.evidence.metric)),
            clinicalNumericValue(finding.evidence.value, finding.evidence.decimals, finding.evidence.unit).orEmpty())
        val details = stringResource(R.string.clinical_report_finding_line,
            stringResource(clinicalTextString(finding.topic)), stringResource(clinicalTextString(finding.period)),
            stringResource(clinicalTextString(finding.direction)), stringResource(clinicalTextString(finding.timeBand)),
            stringResource(clinicalTextString(finding.confidence))) + "\n" + evidence
        DisplayObservation(stringResource(clinicalTextString(finding.topic)) + "\n" + evidence, details)
    } else listOf(
        stringResource(R.string.clinical_report_below_4) + ": " +
            (summary.belowRange?.let { ClinicalReportFormatter.formatNumber(it, 1, locale) + " %" } ?: "--"),
        stringResource(R.string.clinical_report_above_range) + ": " +
            (summary.aboveRange?.let { ClinicalReportFormatter.formatNumber(it, 1, locale) + " %" } ?: "--"),
        stringResource(clinicalLocalQualityString(summary.quality))
    ).map { DisplayObservation(it, it) }
    Column(Modifier.fillMaxWidth().padding(Spacing.md).testTag("ai_focus_saved_report"),
        verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(date, style = MaterialTheme.typography.titleSmall, modifier = Modifier.testTag("ai_focus_saved_date"))
                Text("$period · $source", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Box {
                AiFocusAction(Icons.Default.DateRange, if (summary.days == 1) stringResource(R.string.ai_focus_period_day)
                    else stringResource(R.string.ai_focus_period_days, summary.days), { periodsOpen = true },
                    Modifier.testTag("ai_focus_saved_period_menu"))
                DropdownMenu(periodsOpen, { periodsOpen = false }) {
                    display.summaries.forEach { item ->
                        DropdownMenuItem(text = { Text(if (item.days == 1) stringResource(R.string.ai_focus_period_day)
                            else stringResource(R.string.ai_focus_period_days, item.days)) },
                            onClick = { days = item.days; periodsOpen = false },
                            modifier = Modifier.testTag("ai_focus_saved_period_${item.days}"))
                    }
                }
            }
            AiFocusAction(Icons.Default.Description, stringResource(R.string.ai_focus_report_actions), onDetails,
                Modifier.testTag("ai_focus_report_actions"))
        }
        if (refreshing) Text(stringResource(R.string.ai_focus_updating), style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.testTag("ai_focus_display_refreshing"))
        else if (failed) Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.ai_focus_update_failed), Modifier.weight(1f), style = MaterialTheme.typography.labelSmall)
            TextButton(onRetry, Modifier.testTag("ai_focus_display_retry")) { Text(stringResource(R.string.ai_focus_retry)) }
        }
        AiFocusMetricSummary(summary, showDetailsHint = false, compact = true, onDetails = onDetails)
        observations.forEachIndexed { index, observation ->
            HorizontalDivider()
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { selected = observation.details }
                .testTag("ai_focus_observation_$index").padding(vertical = Spacing.xs),
                verticalAlignment = Alignment.CenterVertically) {
                Text(observation.preview, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null)
            }
        }
    }
    selected?.let { line ->
        val limitations = stringResource(R.string.ai_focus_display_limitations)
        AlertDialog(onDismissRequest = { selected = null },
            title = { Text(stringResource(R.string.clinical_report_observations)) },
            text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Text(line)
                Text(sourceLine, style = MaterialTheme.typography.labelSmall)
                Text(limitations, style = MaterialTheme.typography.bodySmall)
            } },
            confirmButton = { TextButton({ discuss("$line\n$sourceLine\n$limitations"); selected = null },
                Modifier.testTag("ai_focus_observation_discuss")) { Text(stringResource(R.string.ai_focus_discuss)) } },
            dismissButton = { TextButton({ selected = null }) { Text(stringResource(R.string.action_close)) } })
    }
}
