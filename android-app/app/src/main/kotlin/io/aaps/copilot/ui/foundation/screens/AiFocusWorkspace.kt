package io.aaps.copilot.ui.foundation.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.aaps.copilot.R
import io.aaps.copilot.ui.foundation.design.Spacing
import io.aaps.copilot.ui.motion.CopilotOwl
import io.aaps.copilot.ui.motion.NativeIconButton
import io.aaps.copilot.ui.motion.OwlGesture
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private enum class FocusTab(val key: String, val label: Int, val title: Int) {
    REPORT("report", R.string.ai_focus_tab_report, R.string.ai_focus_title_report),
    CHAT("chat", R.string.ai_focus_tab_chat, R.string.ai_focus_tab_chat),
    RECOMMENDATIONS("recommendations", R.string.ai_focus_tab_recommendations, R.string.ai_focus_title_recommendations),
    ARCHIVE("archive", R.string.ai_focus_tab_archive, R.string.ai_focus_title_archive)
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
internal fun AiFocusWorkspace(
    state: AiAnalysisUiState,
    onDraftChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    report: @Composable () -> Unit,
    chat: @Composable () -> Unit,
    diagnostics: @Composable () -> Unit
) {
    var tabIndex by rememberSaveable { mutableIntStateOf(0) }
    var details by rememberSaveable { mutableStateOf(false) }
    val holder = rememberSaveableStateHolder()
    val tab = FocusTab.entries.getOrElse(tabIndex) { FocusTab.REPORT }
    val busy = when (tab) {
        FocusTab.REPORT -> state.clinicalReport.phase == ClinicalReportPhaseUi.BUILDING ||
            state.clinicalReport.phase == ClinicalReportPhaseUi.UPLOADING
        FocusTab.CHAT -> state.chatInProgress || state.chatVoiceBusy
        else -> state.loadState == ScreenLoadState.LOADING
    }
    BackHandler(details) { details = false }
    val discuss: (String) -> Unit = { question ->
        onDraftChange(if (state.chatDraft.isBlank()) question else state.chatDraft + "\n\n" + question)
        tabIndex = FocusTab.CHAT.ordinal
        details = false
    }
    Column(modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(if (details) R.string.ai_focus_diagnostics else tab.title),
                Modifier.weight(1f).semantics { heading() },
                style = MaterialTheme.typography.titleMedium
            )
            if (!details && !(tab == FocusTab.CHAT && state.chatInProgress)) {
                CopilotOwl(modifier = Modifier.testTag("ai_focus_owl"), size = 28.dp,
                    gesture = if (busy) OwlGesture.WAITING else OwlGesture.LISTEN,
                    description = if (busy) stringResource(R.string.owl_ai_waiting) else null,
                    motionKey = "focus-${tab.key}-$busy")
            }
            AiFocusAction(Icons.Default.Info, stringResource(R.string.ai_focus_diagnostics),
                { details = !details }, modifier = Modifier.testTag("ai_focus_diagnostics"))
        }
        val tabs: @Composable () -> Unit = {
            FocusTab.entries.forEach { item ->
                Tab(
                    selected = !details && tab == item,
                    onClick = { tabIndex = item.ordinal; details = false },
                    modifier = Modifier.heightIn(min = 48.dp).testTag("ai_focus_tab_${item.key}"),
                ) { Text(stringResource(item.label), Modifier.padding(horizontal = 2.dp, vertical = 12.dp),
                    style = MaterialTheme.typography.labelSmall, maxLines = 1, softWrap = false) }
            }
        }
        if (LocalDensity.current.fontScale > 1.6f) {
            PrimaryScrollableTabRow(selectedTabIndex = tab.ordinal, edgePadding = 0.dp, tabs = tabs)
        } else PrimaryTabRow(selectedTabIndex = tab.ordinal, tabs = tabs)
        Box(Modifier.weight(1f).fillMaxWidth()) {
            holder.SaveableStateProvider(if (details) "diagnostics" else tab.key) {
                if (details) diagnostics() else when (tab) {
                    FocusTab.REPORT -> LazyColumn(
                        state = rememberLazyListState(), modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = Spacing.sm)
                    ) { item(key = "clinical-report") { report() } }
                    FocusTab.CHAT -> chat()
                    FocusTab.RECOMMENDATIONS -> AiFocusRecommendations(state, discuss)
                    FocusTab.ARCHIVE -> AiFocusArchive(state, discuss)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AiFocusAction(
    icon: ImageVector, label: String, onClick: () -> Unit,
    modifier: Modifier = Modifier, enabled: Boolean = true
) {
    TooltipBox(positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = { PlainTooltip { Text(label) } }, state = rememberTooltipState()) {
        NativeIconButton(onClick, modifier.size(48.dp), enabled) { Icon(icon, label) }
    }
}

@Composable
internal fun AiFocusMetricSummary(summary: ClinicalPeriodSummaryUi, onDetails: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClickLabel = stringResource(R.string.ai_focus_report_details), onClick = onDetails)
        .testTag("clinical_report_summary_${summary.days}").padding(vertical = Spacing.sm),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            FocusMetric(stringResource(R.string.clinical_report_in_range), summary.inRange, "%", Modifier.weight(1f))
            FocusMetric(stringResource(R.string.clinical_report_mean), summary.meanGlucose,
                stringResource(R.string.ai_focus_mmol), Modifier.weight(1f))
            FocusMetric(stringResource(R.string.clinical_report_variability), summary.variability, "%", Modifier.weight(1f))
        }
        val values = listOf(summary.belowRange, summary.inRange, summary.aboveRange)
        if (values.all { it != null && it.isFinite() && it in 0.0..100.0 } &&
            kotlin.math.abs(values.sumOf { it!! } - 100.0) <= 1.0) {
            val colors = listOf(MaterialTheme.colorScheme.error, MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.tertiary)
            Canvas(Modifier.fillMaxWidth().height(6.dp)) {
                var start = 0f
                values.forEachIndexed { index, value ->
                    val width = size.width * value!!.toFloat() / 100f
                    drawRect(colors[index], androidx.compose.ui.geometry.Offset(start, 0f),
                        androidx.compose.ui.geometry.Size(width, size.height))
                    start += width
                }
            }
        }
        val locale = LocalConfiguration.current.locales[0] ?: Locale.getDefault()
        Text(stringResource(R.string.ai_focus_coverage,
            summary.coveragePct?.let { ClinicalReportFormatter.formatNumber(it, 1, locale) } ?: "--"),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(stringResource(R.string.ai_focus_report_details), style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun RowScope.FocusMetric(label: String, value: Double?, unit: String, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        val locale = LocalConfiguration.current.locales[0] ?: Locale.getDefault()
        Text(value?.let { ClinicalReportFormatter.formatNumber(it, 1, locale) } ?: "--",
            style = MaterialTheme.typography.headlineSmall)
        Text(unit, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun AiFocusRecommendations(state: AiAnalysisUiState, discuss: (String) -> Unit) {
    val latest = state.historyItems.filter { it.isUsableSavedReview() }.maxByOrNull { it.runTs }
    val clinical = state.clinicalReport.complete
    LazyColumn(Modifier.fillMaxSize(), state = rememberLazyListState(),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        if (state.localRecommendations.isEmpty() && latest?.recommendations.isNullOrEmpty() && clinical?.recommendations.isNullOrEmpty()) {
            item { Text(stringResource(R.string.ai_analysis_no_recommendations), Modifier.padding(Spacing.md)) }
        }
        clinical?.let { complete ->
            itemsIndexed(complete.recommendations) { index, recommendation ->
                val topic = stringResource(R.string.clinical_report_recommendation_line,
                    stringResource(clinicalTextString(recommendation.topic)),
                    stringResource(clinicalTextString(recommendation.priority)),
                    stringResource(clinicalTextString(recommendation.period)))
                val evidence = recommendation.linkedEvidence.map { finding ->
                    stringResource(R.string.clinical_report_recommendation_evidence_line,
                        stringResource(clinicalTextString(finding.topic)),
                        stringResource(clinicalTextString(finding.evidence.metric)),
                        clinicalNumericValue(finding.evidence.value, finding.evidence.decimals, finding.evidence.unit).orEmpty())
                }
                FocusRecommendation((listOf(topic) + evidence).joinToString("\n"),
                    listOf(clinicalAiProviderLabel(complete.metadata.providerId), complete.metadata.model ?: "--",
                        complete.metadata.generatedAtTs?.let(::focusTimestamp) ?: "--").joinToString(" / "),
                    "ai_focus_clinical_discuss_$index", discuss)
            }
        }
        itemsIndexed(state.localRecommendations) { index, line ->
            FocusRecommendation(line, stringResource(R.string.section_ai_local_daily),
                "ai_focus_discuss_$index", discuss)
        }
        latest?.let { record ->
            itemsIndexed(record.recommendations) { index, line ->
                FocusRecommendation(line, "${record.date} / ${record.source} / ${record.status}",
                    "ai_focus_history_discuss_$index", discuss)
            }
        }
    }
}

@Composable
private fun FocusRecommendation(line: String, source: String, tag: String, discuss: (String) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = Spacing.md, vertical = Spacing.sm),
        verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        Text(source, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(line, style = MaterialTheme.typography.bodyMedium)
        TextButton({ discuss("$source\n$line") }, Modifier.testTag(tag)) { Text(stringResource(R.string.ai_focus_discuss)) }
        HorizontalDivider()
    }
}

@Composable
private fun AiFocusArchive(state: AiAnalysisUiState, discuss: (String) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var opened by remember { mutableStateOf<AiAnalysisHistoryItemUi?>(null) }
    val records = remember(state.historyItems) { state.historyItems.sortedByDescending { it.runTs } }
    val latest = records.firstOrNull { it.isUsableSavedReview() }?.takeIf { query.isBlank() }
    val matches = remember(records, query, latest) {
        records.filter {
            query.isBlank() || (listOf(it.date, it.source, it.status, it.summary) + it.anomalies + it.recommendations)
                .any { field -> field.contains(query, ignoreCase = true) }
        }.filterNot { it == latest }
    }
    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth().testTag("ai_focus_archive_search"),
            singleLine = true, label = { Text(stringResource(R.string.ai_focus_search)) })
        if (state.loadState == ScreenLoadState.LOADING) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        if (state.isStale || state.loadState == ScreenLoadState.ERROR) {
            Text(state.errorText ?: stringResource(R.string.status_stale_data),
                Modifier.padding(Spacing.sm), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error)
        }
        LazyColumn(state = rememberLazyListState(), modifier = Modifier.weight(1f)) {
            latest?.let { record ->
                item(key = "latest-saved-review") {
                    Column(Modifier.fillMaxWidth().testTag("ai_focus_latest_report").padding(Spacing.md),
                        verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(R.string.ai_focus_latest_saved), Modifier.weight(1f).semantics { heading() },
                                style = MaterialTheme.typography.titleSmall)
                            AiFocusAction(Icons.AutoMirrored.Filled.ArrowForward,
                                stringResource(R.string.ai_focus_report_details), { opened = record },
                                Modifier.testTag("ai_focus_latest_open"))
                        }
                        Text("${record.date} / ${record.source}", style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(record.summary, style = MaterialTheme.typography.bodyMedium,
                            maxLines = 4, overflow = TextOverflow.Ellipsis)
                        HorizontalDivider()
                    }
                }
            }
            if (matches.isEmpty() && latest == null) item {
                Text(stringResource(R.string.ai_analysis_no_history), Modifier.padding(Spacing.md))
            }
            itemsIndexed(matches) { index, record ->
                Column(Modifier.fillMaxWidth().clickable { opened = record }
                    .testTag("ai_focus_archive_item_$index").padding(Spacing.md),
                    verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    Text("${record.date} / ${record.source}", style = MaterialTheme.typography.labelMedium)
                    Text(record.summary, style = MaterialTheme.typography.bodyMedium)
                    Text(record.status, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    HorizontalDivider()
                }
            }
        }
    }
    opened?.let { record ->
        AlertDialog(onDismissRequest = { opened = null },
            modifier = Modifier.testTag("ai_focus_archive_detail"),
            title = { Text(record.date) },
            text = {
                Column(Modifier.heightIn(max = LocalConfiguration.current.screenHeightDp.dp * .6f)
                    .verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    Text("${record.source} / ${record.status}")
                    Text(focusTimestamp(record.runTs))
                    Text(record.summary)
                    record.anomalies.forEach { Text(it) }
                    record.recommendations.forEach { Text(it) }
                    record.errorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = { TextButton({ opened = null }) { Text(stringResource(R.string.action_close)) } },
            dismissButton = { TextButton({
                opened = null
                discuss((listOf("${record.date} / ${record.source} / ${record.status}", record.summary) +
                    record.anomalies + record.recommendations).joinToString("\n"))
            }) { Text(stringResource(R.string.ai_focus_discuss)) } })
    }
}

private fun AiAnalysisHistoryItemUi.isUsableSavedReview() =
    status == "SUCCESS" && errorMessage.isNullOrBlank() && summary.isNotBlank()

private fun focusTimestamp(timestamp: Long) = runCatching {
    DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm").withZone(ZoneId.systemDefault())
        .format(Instant.ofEpochMilli(timestamp))
}.getOrDefault("--")
