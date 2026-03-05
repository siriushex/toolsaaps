package io.aaps.copilot.ui.foundation.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.aaps.copilot.R
import io.aaps.copilot.ui.foundation.design.AppElevation
import io.aaps.copilot.ui.foundation.design.Spacing
import io.aaps.copilot.ui.foundation.format.UiFormatters
import io.aaps.copilot.ui.foundation.theme.AapsCopilotTheme
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.max

private val AiSectionShape = RoundedCornerShape(18.dp)

@Composable
fun AiAnalysisScreen(
    state: AiAnalysisUiState,
    onRunDailyAnalysis: () -> Unit = {},
    onRefreshCloudJobs: () -> Unit = {},
    onRefreshInsights: () -> Unit = {},
    onApplyFilters: (source: String, status: String, days: String, weeks: String) -> Unit = { _, _, _, _ -> },
    onRunReplay: (days: Int, stepMinutes: Int) -> Unit = { _, _ -> },
    onExportInsightsCsv: () -> Unit = {},
    onExportInsightsPdf: () -> Unit = {},
    onExportReplayCsv: () -> Unit = {},
    onExportReplayPdf: (horizonFilter: Int?) -> Unit = {},
    onSendChatPrompt: (String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    var selectedSource by rememberSaveable { mutableStateOf("all") }
    var selectedStatus by rememberSaveable { mutableStateOf("all") }
    var selectedDays by rememberSaveable { mutableIntStateOf(60) }
    var selectedWeeks by rememberSaveable { mutableIntStateOf(8) }
    var selectedHorizonFilter by rememberSaveable { mutableIntStateOf(0) }
    var selectedFactorFilter by rememberSaveable { mutableStateOf("ALL") }
    var chatInput by rememberSaveable { mutableStateOf("") }

    val sourceOptions = listOf("all", "manual", "scheduler")
    val statusOptions = listOf("all", "success", "failed")
    val daysOptions = listOf(30, 60, 90)
    val weeksOptions = listOf(4, 8, 12)
    val horizonOptions = listOf(0, 5, 30, 60)
    val factorOptions = buildList {
        add("ALL")
        addAll(state.localTopFactors.map { it.factor }.distinct())
        addAll(state.localDayTypeGaps.mapNotNull { it.dominantFactor }.distinct())
    }.distinct()
    val dayTypeMetricPattern = stringResource(id = R.string.ai_analysis_replay_daytype_metric_item)
    val filteredHorizonScores = state.localHorizonScores.filter {
        selectedHorizonFilter == 0 || it.horizonMinutes == selectedHorizonFilter
    }
    val filteredTopFactors = state.localTopFactors.filter {
        (selectedHorizonFilter == 0 || it.horizonMinutes == selectedHorizonFilter) &&
            (selectedFactorFilter == "ALL" || it.factor.equals(selectedFactorFilter, ignoreCase = true))
    }
    val filteredHotspots = state.localHotspots.filter {
        selectedHorizonFilter == 0 || it.horizonMinutes == selectedHorizonFilter
    }
    val filteredTopMisses = state.localTopMisses.filter {
        selectedHorizonFilter == 0 || it.horizonMinutes == selectedHorizonFilter
    }
    val filteredDayTypeGaps = state.localDayTypeGaps.filter {
        (selectedHorizonFilter == 0 || it.horizonMinutes == selectedHorizonFilter) &&
            (selectedFactorFilter == "ALL" || it.dominantFactor.equals(selectedFactorFilter, ignoreCase = true))
    }

    ScreenStateLayout(
        loadState = state.loadState,
        isStale = state.isStale,
        errorText = state.errorText,
        emptyText = if (state.analysisReady) {
            stringResource(id = R.string.ai_analysis_empty)
        } else {
            stringResource(
                id = R.string.ai_analysis_collecting_data,
                state.dataCoverageHours,
                state.minDataHours
            )
        }
    ) {
        LazyColumn(
            modifier = modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            item {
                AiSectionCard {
                    AiSectionLabel(
                        text = stringResource(id = R.string.section_ai_data_window),
                        infoText = stringResource(id = R.string.ai_analysis_data_window_info)
                    )
                    val readinessText = if (state.analysisReady) {
                        stringResource(
                            id = R.string.ai_analysis_ready,
                            state.dataCoverageHours,
                            state.minDataHours
                        )
                    } else {
                        stringResource(
                            id = R.string.ai_analysis_collecting_data,
                            state.dataCoverageHours,
                            state.minDataHours
                        )
                    }
                    Text(
                        text = readinessText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            state.aiTuningStatus?.let { tuning ->
                item {
                    AiTuningStatusCard(status = tuning)
                }
            }

            item {
                AiSectionCard {
                    AiSectionLabel(
                        text = stringResource(id = R.string.section_ai_controls),
                        infoText = stringResource(
                            id = R.string.analytics_info_section_generic,
                            stringResource(id = R.string.section_ai_controls)
                        )
                    )
                    Text(
                        text = stringResource(id = R.string.ai_analysis_filter_label, state.filterLabel),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = if (state.cloudConfigured) {
                            stringResource(id = R.string.ai_analysis_cloud_configured)
                        } else {
                            stringResource(id = R.string.ai_analysis_cloud_openai_mode)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = stringResource(id = R.string.ai_analysis_filters_title),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    FilterChipGroup(
                        selected = selectedSource,
                        options = sourceOptions
                    ) { selectedSource = it }
                    FilterChipGroup(
                        selected = selectedStatus,
                        options = statusOptions
                    ) { selectedStatus = it }
                    IntFilterChipGroup(
                        selected = selectedDays,
                        options = daysOptions,
                        formatter = { value ->
                            stringResource(id = R.string.ai_analysis_filter_days_label, value)
                        }
                    ) { selectedDays = it }
                    IntFilterChipGroup(
                        selected = selectedWeeks,
                        options = weeksOptions,
                        formatter = { value ->
                            stringResource(id = R.string.ai_analysis_filter_weeks_label, value)
                        }
                    ) { selectedWeeks = it }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(Spacing.xs)
                    ) {
                        OutlinedButton(
                            onClick = {
                                onApplyFilters(
                                    selectedSource,
                                    selectedStatus,
                                    selectedDays.toString(),
                                    selectedWeeks.toString()
                                )
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(text = stringResource(id = R.string.ai_analysis_apply_filters))
                        }
                        OutlinedButton(
                            onClick = onRefreshCloudJobs,
                            modifier = Modifier.weight(1f),
                            enabled = state.cloudConfigured
                        ) {
                            Text(text = stringResource(id = R.string.ai_analysis_refresh_jobs))
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(Spacing.xs)
                    ) {
                        OutlinedButton(
                            onClick = onRefreshInsights,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(text = stringResource(id = R.string.ai_analysis_refresh_insights))
                        }
                        OutlinedButton(
                            onClick = onRunDailyAnalysis,
                            modifier = Modifier.weight(1f),
                            enabled = state.analysisReady
                        ) {
                            Text(text = stringResource(id = R.string.ai_analysis_run_daily))
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(Spacing.xs)
                    ) {
                        OutlinedButton(
                            onClick = { onRunReplay(1, 5) },
                            modifier = Modifier.weight(1f),
                            enabled = state.cloudConfigured
                        ) {
                            Text(text = stringResource(id = R.string.ai_analysis_run_replay))
                        }
                        OutlinedButton(
                            onClick = onExportInsightsCsv,
                            modifier = Modifier.weight(1f),
                            enabled = state.cloudConfigured && state.historyItems.isNotEmpty() && state.trendItems.isNotEmpty()
                        ) {
                            Text(text = stringResource(id = R.string.ai_analysis_export_insights_csv))
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(Spacing.xs)
                    ) {
                        OutlinedButton(
                            onClick = onExportInsightsPdf,
                            modifier = Modifier.weight(1f),
                            enabled = state.cloudConfigured && state.historyItems.isNotEmpty() && state.trendItems.isNotEmpty()
                        ) {
                            Text(text = stringResource(id = R.string.ai_analysis_export_insights_pdf))
                        }
                        OutlinedButton(
                            onClick = onExportReplayCsv,
                            modifier = Modifier.weight(1f),
                            enabled = state.cloudConfigured && state.replay != null
                        ) {
                            Text(text = stringResource(id = R.string.ai_analysis_export_replay_csv))
                        }
                    }
                    OutlinedButton(
                        onClick = { onExportReplayPdf(null) },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = state.cloudConfigured && state.replay != null
                    ) {
                        Text(text = stringResource(id = R.string.ai_analysis_export_replay_pdf))
                    }
                }
            }

            item {
                AiSectionCard {
                    AiSectionLabel(
                        text = stringResource(id = R.string.section_ai_cloud_status),
                        infoText = stringResource(
                            id = R.string.analytics_info_section_generic,
                            stringResource(id = R.string.section_ai_cloud_status)
                        )
                    )
                    if (state.jobs.isEmpty()) {
                        Text(
                            text = stringResource(id = R.string.ai_analysis_no_jobs),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        state.jobs.forEach { job ->
                            Text(
                                text = stringResource(
                                    id = R.string.ai_analysis_job_line,
                                    job.jobId,
                                    job.lastStatus ?: "--",
                                    formatTs(job.lastRunTs),
                                    formatTs(job.nextRunTs),
                                    job.lastMessage?.takeIf { it.isNotBlank() }?.take(80) ?: "--"
                                ),
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            }

            item {
                AiSectionCard {
                    AiSectionLabel(
                        text = stringResource(id = R.string.section_ai_latest_report),
                        infoText = stringResource(
                            id = R.string.analytics_info_section_generic,
                            stringResource(id = R.string.section_ai_latest_report)
                        )
                    )
                    val latest = state.historyItems.firstOrNull()
                    if (latest == null) {
                        Text(
                            text = stringResource(id = R.string.ai_analysis_no_history),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        Text(
                            text = stringResource(
                                id = R.string.ai_analysis_history_item_header,
                                latest.date,
                                latest.source,
                                latest.status
                            ),
                            style = MaterialTheme.typography.labelMedium
                        )
                        Text(
                            text = formatTs(latest.runTs),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = latest.summary.ifBlank { "--" },
                            style = MaterialTheme.typography.bodySmall
                        )
                        latest.anomalies.take(3).forEach { line ->
                            Text(text = "- $line", style = MaterialTheme.typography.bodySmall)
                        }
                        latest.recommendations.take(3).forEach { line ->
                            Text(text = "> $line", style = MaterialTheme.typography.bodySmall)
                        }
                        latest.errorMessage
                            ?.takeIf { it.isNotBlank() }
                            ?.let { message ->
                                Text(
                                    text = stringResource(id = R.string.ai_analysis_history_error, message),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                    }
                }
            }

            item {
                AiSectionCard {
                    AiSectionLabel(
                        text = stringResource(id = R.string.section_ai_history),
                        infoText = stringResource(
                            id = R.string.analytics_info_section_generic,
                            stringResource(id = R.string.section_ai_history)
                        )
                    )
                    if (state.historyItems.isEmpty()) {
                        Text(
                            text = stringResource(id = R.string.ai_analysis_no_history),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        state.historyItems.take(8).forEach { item ->
                            Column(verticalArrangement = Arrangement.spacedBy(Spacing.xxs)) {
                                Text(
                                    text = stringResource(
                                        id = R.string.ai_analysis_history_item_header,
                                        item.date,
                                        item.source,
                                        item.status
                                    ),
                                    style = MaterialTheme.typography.labelMedium
                                )
                                Text(
                                    text = item.summary.ifBlank { "--" },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }

            item {
                AiSectionCard {
                    AiSectionLabel(
                        text = stringResource(id = R.string.section_ai_trend),
                        infoText = stringResource(
                            id = R.string.analytics_info_section_generic,
                            stringResource(id = R.string.section_ai_trend)
                        )
                    )
                    if (state.trendItems.isEmpty()) {
                        Text(
                            text = stringResource(id = R.string.ai_analysis_no_trend),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        state.trendItems.take(12).forEach { item ->
                            Text(
                                text = stringResource(
                                    id = R.string.ai_analysis_trend_line,
                                    item.weekStart,
                                    item.totalRuns,
                                    item.successRuns,
                                    item.failedRuns,
                                    item.anomaliesCount,
                                    item.recommendationsCount
                                ),
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            }

            state.replay?.let { replay ->
                item {
                    AiSectionCard {
                        AiSectionLabel(
                            text = stringResource(id = R.string.section_ai_replay),
                            infoText = stringResource(
                                id = R.string.analytics_info_section_generic,
                                stringResource(id = R.string.section_ai_replay)
                            )
                        )
                        Text(
                            text = stringResource(
                                id = R.string.ai_analysis_replay_meta,
                                replay.days,
                                replay.points,
                                replay.stepMinutes
                            ),
                            style = MaterialTheme.typography.labelMedium
                        )
                        replay.forecastStats
                            .sortedBy { it.horizonMinutes }
                            .forEach { stat ->
                                Text(
                                    text = stringResource(
                                    id = R.string.ai_analysis_replay_forecast_line,
                                    stat.horizonMinutes,
                                    UiFormatters.formatMmol(stat.mae),
                                    UiFormatters.formatMmol(stat.rmse),
                                    formatPercentFromPct(stat.mardPct, 1),
                                    stat.sampleCount
                                ),
                                style = MaterialTheme.typography.bodySmall
                            )
                            }
                        replay.ruleStats.take(6).forEach { stat ->
                            Text(
                                text = stringResource(
                                    id = R.string.ai_analysis_replay_rule_line,
                                    stat.ruleId,
                                    stat.triggered,
                                    stat.blocked,
                                    stat.noMatch
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        replay.dayTypeStats.take(4).forEach { stat ->
                            Text(
                                text = stringResource(
                                    id = R.string.ai_analysis_replay_daytype_line,
                                    stat.dayType,
                                    stat.metrics.joinToString { metric ->
                                        String.format(
                                            Locale.US,
                                            dayTypeMetricPattern,
                                            metric.horizonMinutes,
                                            UiFormatters.formatMmol(metric.mae)
                                        )
                                    }
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        replay.hourlyTop.take(6).forEach { stat ->
                            Text(
                                text = stringResource(
                                    id = R.string.ai_analysis_replay_hourly_line,
                                    stat.hour,
                                    UiFormatters.formatMmol(stat.mae),
                                    formatPercentFromPct(stat.mardPct, 1),
                                    stat.sampleCount
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        replay.driftStats.take(3).forEach { stat ->
                            Text(
                                text = stringResource(
                                    id = R.string.ai_analysis_replay_drift_line,
                                    stat.horizonMinutes,
                                    UiFormatters.formatSignedDelta(stat.deltaMae),
                                    UiFormatters.formatMmol(stat.recentMae),
                                    UiFormatters.formatMmol(stat.previousMae)
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            item {
                AiSectionCard {
                    AiSectionLabel(
                        text = stringResource(id = R.string.section_ai_focus_filters),
                        infoText = stringResource(
                            id = R.string.analytics_info_section_generic,
                            stringResource(id = R.string.section_ai_focus_filters)
                        )
                    )
                    Text(
                        text = stringResource(id = R.string.ai_analysis_focus_horizon),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    HorizonFilterChipGroup(
                        selected = selectedHorizonFilter,
                        options = horizonOptions,
                        onSelected = { selectedHorizonFilter = it }
                    )
                    Text(
                        text = stringResource(id = R.string.ai_analysis_focus_factor),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    FactorFilterChipGroup(
                        selected = selectedFactorFilter,
                        options = factorOptions,
                        onSelected = { selectedFactorFilter = it }
                    )
                }
            }

            item {
                AiSectionCard {
                    AiSectionLabel(
                        text = stringResource(id = R.string.section_ai_local_daily),
                        infoText = stringResource(
                            id = R.string.analytics_info_section_generic,
                            stringResource(id = R.string.section_ai_local_daily)
                        )
                    )
                    state.localDailyGeneratedAtTs?.let { ts ->
                        Text(
                            text = stringResource(id = R.string.ai_analysis_local_generated, formatTs(ts)),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    if (!state.localDailyPeriodStartUtc.isNullOrBlank() || !state.localDailyPeriodEndUtc.isNullOrBlank()) {
                        Text(
                            text = stringResource(
                                id = R.string.ai_analysis_local_period,
                                state.localDailyPeriodStartUtc ?: "--",
                                state.localDailyPeriodEndUtc ?: "--"
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (state.localDailyMetrics.isEmpty()) {
                        Text(
                            text = stringResource(id = R.string.analytics_empty),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        state.localDailyMetrics.forEach { metric ->
                            Text(
                                text = stringResource(
                                    id = R.string.ai_analysis_local_metric_line,
                                    metric.horizonMinutes,
                                    UiFormatters.formatMmol(metric.mae),
                                    UiFormatters.formatMmol(metric.rmse),
                                    formatPercentFromPct(metric.mardPct, 1),
                                    UiFormatters.formatSignedDelta(metric.bias),
                                    metric.sampleCount?.toString() ?: "--"
                                ),
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            }

            item {
                AiSectionCard {
                    AiSectionLabel(
                        text = stringResource(id = R.string.section_ai_quality_scorecards),
                        infoText = stringResource(
                            id = R.string.analytics_info_section_generic,
                            stringResource(id = R.string.section_ai_quality_scorecards)
                        )
                    )
                    if (filteredHorizonScores.isEmpty()) {
                        Text(
                            text = stringResource(id = R.string.analytics_empty),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        filteredHorizonScores.forEach { score ->
                            val bandLabel = when (score.scoreBand) {
                                "EXCELLENT" -> stringResource(id = R.string.ai_analysis_score_excellent)
                                "GOOD" -> stringResource(id = R.string.ai_analysis_score_good)
                                "WARNING" -> stringResource(id = R.string.ai_analysis_score_warning)
                                "CRITICAL" -> stringResource(id = R.string.ai_analysis_score_critical)
                                else -> stringResource(id = R.string.ai_analysis_score_no_data)
                            }
                            Text(
                                text = stringResource(
                                    id = R.string.ai_analysis_scorecard_line,
                                    score.horizonMinutes,
                                    UiFormatters.formatMmol(score.mae),
                                    formatPercentFromPct(score.mardPct, 1),
                                    score.sampleCount?.toString() ?: "--",
                                    bandLabel
                                ),
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            }

            item {
                AiSectionCard {
                    AiSectionLabel(
                        text = stringResource(id = R.string.section_ai_top_factors),
                        infoText = stringResource(
                            id = R.string.analytics_info_section_generic,
                            stringResource(id = R.string.section_ai_top_factors)
                        )
                    )
                    state.localTopFactorsOverall?.takeIf { it.isNotBlank() }?.let { top ->
                        Text(
                            text = stringResource(id = R.string.ai_analysis_top_factors_overall, top),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (filteredTopFactors.isEmpty()) {
                        Text(
                            text = stringResource(id = R.string.analytics_empty),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        filteredTopFactors.forEach { factor ->
                            Text(
                                text = stringResource(
                                    id = R.string.ai_analysis_top_factor_line,
                                    factor.horizonMinutes,
                                    replayFactorDisplayName(factor.factor),
                                    UiFormatters.formatDecimalOrPlaceholder(factor.contributionScore, 3),
                                    formatPercentFromPct(factor.upliftPct, 1),
                                    factor.sampleCount
                                ),
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            }

            item {
                AiSectionCard {
                    AiSectionLabel(
                        text = stringResource(id = R.string.section_ai_hotspots),
                        infoText = stringResource(
                            id = R.string.analytics_info_section_generic,
                            stringResource(id = R.string.section_ai_hotspots)
                        )
                    )
                    if (filteredHotspots.isEmpty()) {
                        Text(
                            text = stringResource(id = R.string.analytics_empty),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        val chartHorizons = if (selectedHorizonFilter == 0) {
                            listOf(5, 30, 60)
                        } else {
                            listOf(selectedHorizonFilter)
                        }
                        chartHorizons.forEach { horizon ->
                            val points = filteredHotspots
                                .filter { it.horizonMinutes == horizon }
                                .sortedBy { it.hour }
                            if (points.isNotEmpty()) {
                                MiniErrorSparkline(
                                    horizonMinutes = horizon,
                                    points = points
                                )
                            }
                        }
                        filteredHotspots.forEach { hotspot ->
                            Text(
                                text = stringResource(
                                    id = R.string.ai_analysis_hotspot_line,
                                    hotspot.horizonMinutes,
                                    hotspot.hour,
                                    UiFormatters.formatMmol(hotspot.mae),
                                    formatPercentFromPct(hotspot.mardPct, 1),
                                    UiFormatters.formatSignedDelta(hotspot.bias),
                                    hotspot.sampleCount
                                ),
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            }

            item {
                AiSectionCard {
                    AiSectionLabel(
                        text = stringResource(id = R.string.section_ai_top_misses),
                        infoText = stringResource(
                            id = R.string.analytics_info_section_generic,
                            stringResource(id = R.string.section_ai_top_misses)
                        )
                    )
                    if (filteredTopMisses.isEmpty()) {
                        Text(
                            text = stringResource(id = R.string.analytics_empty),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        filteredTopMisses.take(8).forEach { miss ->
                            Text(
                                text = stringResource(
                                    id = R.string.ai_analysis_top_miss_line,
                                    miss.horizonMinutes,
                                    formatTs(miss.ts),
                                    UiFormatters.formatMmol(miss.absError),
                                    UiFormatters.formatMmol(miss.pred),
                                    UiFormatters.formatMmol(miss.actual),
                                    UiFormatters.formatGrams(miss.cob),
                                    UiFormatters.formatUnits(miss.iob),
                                    UiFormatters.formatMmol(miss.uam),
                                    UiFormatters.formatMmol(miss.ciWidth),
                                    UiFormatters.formatDecimalOrPlaceholder(miss.activity, 2)
                                ),
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            }

            item {
                AiSectionCard {
                    AiSectionLabel(
                        text = stringResource(id = R.string.section_ai_daytype_gaps),
                        infoText = stringResource(
                            id = R.string.analytics_info_section_generic,
                            stringResource(id = R.string.section_ai_daytype_gaps)
                        )
                    )
                    if (filteredDayTypeGaps.isEmpty()) {
                        Text(
                            text = stringResource(id = R.string.analytics_empty),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        filteredDayTypeGaps.forEach { gap ->
                            Text(
                                text = stringResource(
                                    id = R.string.ai_analysis_daytype_gap_line,
                                    gap.horizonMinutes,
                                    gap.hour,
                                    replayDayTypeDisplayName(gap.worseDayType),
                                    UiFormatters.formatMmol(gap.maeGapMmol),
                                    formatPercentFromPct(gap.mardGapPct, 1),
                                    gap.dominantFactor?.let { replayFactorDisplayName(it) } ?: "--",
                                    gap.sampleCount
                                ),
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            }

            item {
                AiSectionCard {
                    AiSectionLabel(
                        text = stringResource(id = R.string.section_ai_recommendations),
                        infoText = stringResource(
                            id = R.string.analytics_info_section_generic,
                            stringResource(id = R.string.section_ai_recommendations)
                        )
                    )
                    if (state.localRecommendations.isEmpty()) {
                        Text(
                            text = stringResource(id = R.string.ai_analysis_no_recommendations),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        state.localRecommendations.forEach { line ->
                            Text(text = "- $line", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }

            item {
                AiSectionCard {
                    AiSectionLabel(
                        text = stringResource(id = R.string.section_ai_chat),
                        infoText = stringResource(id = R.string.ai_analysis_chat_info)
                    )
                    if (!state.analysisReady) {
                        Text(
                            text = stringResource(
                                id = R.string.ai_analysis_chat_requires_data,
                                state.dataCoverageHours,
                                state.minDataHours
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (state.chatMessages.isEmpty()) {
                        Text(
                            text = stringResource(id = R.string.ai_analysis_chat_empty),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        state.chatMessages.takeLast(8).forEach { message ->
                            val prefix = if (message.role.equals("user", ignoreCase = true)) {
                                stringResource(id = R.string.ai_analysis_chat_role_user)
                            } else {
                                stringResource(id = R.string.ai_analysis_chat_role_ai)
                            }
                            Text(
                                text = "$prefix: ${message.text}",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                    TextField(
                        value = chatInput,
                        onValueChange = { chatInput = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(text = stringResource(id = R.string.ai_analysis_chat_input_label)) },
                        placeholder = { Text(text = stringResource(id = R.string.ai_analysis_chat_input_placeholder)) },
                        enabled = !state.chatInProgress
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedButton(
                            onClick = {
                                val payload = chatInput.trim()
                                if (payload.isNotBlank()) {
                                    onSendChatPrompt(payload)
                                    chatInput = ""
                                }
                            },
                            enabled = !state.chatInProgress && chatInput.trim().isNotBlank(),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(text = stringResource(id = R.string.ai_analysis_chat_send))
                        }
                        if (state.chatInProgress) {
                            CircularProgressIndicator(modifier = Modifier.height(18.dp), strokeWidth = 2.dp)
                        } else {
                            Spacer(modifier = Modifier.height(18.dp))
                        }
                    }
                }
            }

            item {
                AiSectionCard {
                    AiSectionLabel(
                        text = stringResource(id = R.string.ai_analysis_rolling_title),
                        infoText = stringResource(
                            id = R.string.analytics_info_section_generic,
                            stringResource(id = R.string.ai_analysis_rolling_title)
                        )
                    )
                    if (state.rollingLines.isEmpty()) {
                        Text(
                            text = stringResource(id = R.string.analytics_empty),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        state.rollingLines.take(12).forEach { line ->
                            Text(
                                text = line,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FilterChipGroup(
    selected: String,
    options: List<String>,
    onSelected: (String) -> Unit
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
        verticalArrangement = Arrangement.spacedBy(Spacing.xs)
    ) {
        options.forEach { option ->
            FilterChip(
                selected = selected == option,
                onClick = { onSelected(option) },
                label = {
                    Text(
                        text = when (option) {
                            "manual" -> stringResource(id = R.string.ai_analysis_filter_source_manual)
                            "scheduler" -> stringResource(id = R.string.ai_analysis_filter_source_scheduler)
                            "success" -> stringResource(id = R.string.ai_analysis_filter_status_success)
                            "failed" -> stringResource(id = R.string.ai_analysis_filter_status_failed)
                            else -> stringResource(id = R.string.ai_analysis_filter_source_all)
                        }
                    )
                }
            )
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
    AiSectionCard {
        AiSectionLabel(
            text = stringResource(id = R.string.section_ai_tuning_status),
            infoText = stringResource(id = R.string.ai_tuning_status_info)
        )
        Surface(
            shape = RoundedCornerShape(12.dp),
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
        Text(
            text = stringResource(id = R.string.ai_tuning_reason_line, status.reason),
            style = MaterialTheme.typography.bodySmall
        )
        status.generatedTs?.let { ts ->
            Text(
                text = stringResource(id = R.string.ai_tuning_generated_line, formatTs(ts)),
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

@Composable
private fun IntFilterChipGroup(
    selected: Int,
    options: List<Int>,
    formatter: @Composable (Int) -> String,
    onSelected: (Int) -> Unit
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
        verticalArrangement = Arrangement.spacedBy(Spacing.xs)
    ) {
        options.forEach { value ->
            FilterChip(
                selected = selected == value,
                onClick = { onSelected(value) },
                label = { Text(text = formatter(value)) }
            )
        }
    }
}

@Composable
private fun HorizonFilterChipGroup(
    selected: Int,
    options: List<Int>,
    onSelected: (Int) -> Unit
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
        verticalArrangement = Arrangement.spacedBy(Spacing.xs)
    ) {
        options.forEach { value ->
            val label = when (value) {
                5 -> "5m"
                30 -> "30m"
                60 -> "60m"
                else -> stringResource(id = R.string.ai_analysis_filter_horizon_all)
            }
            FilterChip(
                selected = selected == value,
                onClick = { onSelected(value) },
                label = { Text(text = label) }
            )
        }
    }
}

@Composable
private fun FactorFilterChipGroup(
    selected: String,
    options: List<String>,
    onSelected: (String) -> Unit
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
        verticalArrangement = Arrangement.spacedBy(Spacing.xs)
    ) {
        options.forEach { value ->
            FilterChip(
                selected = selected.equals(value, ignoreCase = true),
                onClick = { onSelected(value) },
                label = {
                    Text(
                        text = if (value.equals("ALL", ignoreCase = true)) {
                            stringResource(id = R.string.ai_analysis_filter_factor_all)
                        } else {
                            replayFactorDisplayName(value)
                        }
                    )
                }
            )
        }
    }
}

@Composable
private fun MiniErrorSparkline(
    horizonMinutes: Int,
    points: List<AiHotspotUi>
) {
    if (points.size < 2) {
        Text(
            text = stringResource(id = R.string.ai_analysis_sparkline_empty, horizonMinutes),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        return
    }
    val maeValues = points.map { it.mae.toFloat() }
    val mardValues = points.map { it.mardPct.toFloat() }
    val minHour = points.minOf { it.hour }
    val maxHour = points.maxOf { it.hour }
    val maeColor = MaterialTheme.colorScheme.primary
    val mardColor = MaterialTheme.colorScheme.tertiary
    val gridColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.18f)
    val chartDescription = stringResource(
        id = R.string.ai_analysis_sparkline_accessibility,
        horizonMinutes,
        points.size,
        UiFormatters.formatMmol(points.lastOrNull()?.mae),
        formatPercentFromPct(points.lastOrNull()?.mardPct, 1)
    )
    Text(
        text = stringResource(id = R.string.ai_analysis_sparkline_title, horizonMinutes),
        style = MaterialTheme.typography.labelMedium
    )
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(92.dp)
            .semantics { contentDescription = chartDescription }
    ) {
        val chartWidth = size.width
        val chartHeight = size.height
        repeat(3) { index ->
            val y = chartHeight * index / 2f
            drawLine(
                color = gridColor,
                start = Offset(0f, y),
                end = Offset(chartWidth, y),
                strokeWidth = 1f
            )
        }
        fun xFor(hour: Int, idx: Int): Float {
            val span = max(1, maxHour - minHour)
            return if (maxHour == minHour) {
                if (points.size <= 1) 0f else idx.toFloat() / (points.size - 1).toFloat() * chartWidth
            } else {
                ((hour - minHour).toFloat() / span.toFloat()) * chartWidth
            }
        }
        fun yFor(value: Float, series: List<Float>): Float {
            val minV = series.minOrNull() ?: value
            val maxV = series.maxOrNull() ?: value
            val span = max(0.0001f, maxV - minV)
            val ratio = (value - minV) / span
            return chartHeight - (ratio * chartHeight)
        }
        val maePath = Path()
        val mardPath = Path()
        points.forEachIndexed { index, row ->
            val x = xFor(row.hour, index)
            val yMae = yFor(maeValues[index], maeValues)
            val yMard = yFor(mardValues[index], mardValues)
            if (index == 0) {
                maePath.moveTo(x, yMae)
                mardPath.moveTo(x, yMard)
            } else {
                maePath.lineTo(x, yMae)
                mardPath.lineTo(x, yMard)
            }
        }
        drawPath(
            path = maePath,
            color = maeColor,
            style = Stroke(width = 3f)
        )
        drawPath(
            path = mardPath,
            color = mardColor,
            style = Stroke(width = 2f)
        )
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
    ) {
        Text(
            text = stringResource(id = R.string.ai_analysis_sparkline_legend_mae),
            style = MaterialTheme.typography.bodySmall,
            color = maeColor
        )
        Text(
            text = stringResource(id = R.string.ai_analysis_sparkline_legend_mard),
            style = MaterialTheme.typography.bodySmall,
            color = mardColor
        )
    }
}

@Composable
private fun AiSectionCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = AiSectionShape,
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
private fun AiSectionLabel(
    text: String,
    infoText: String
) {
    var showInfo by rememberSaveable(text) { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = text.uppercase(Locale.US),
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
            text = { Text(text = infoText) },
            confirmButton = {
                TextButton(onClick = { showInfo = false }) {
                    Text(text = stringResource(id = R.string.action_close))
                }
            }
        )
    }
}

private fun formatTs(ts: Long?): String {
    if (ts == null) return "--"
    return Instant.ofEpochMilli(ts)
        .atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern("MM-dd HH:mm"))
}

private fun formatPercentFromPct(value: Double?, decimals: Int): String {
    if (value == null) return "--"
    return String.format(Locale.US, "%.${decimals}f%%", value)
}

@Composable
private fun replayFactorDisplayName(raw: String): String {
    return when (raw.uppercase(Locale.US)) {
        "COB" -> stringResource(id = R.string.analytics_replay_factor_cob)
        "IOB" -> stringResource(id = R.string.analytics_replay_factor_iob)
        "UAM" -> stringResource(id = R.string.analytics_replay_factor_uam)
        "CI" -> stringResource(id = R.string.analytics_replay_factor_ci)
        "DIA_H" -> stringResource(id = R.string.analytics_replay_factor_dia)
        "ACTIVITY" -> stringResource(id = R.string.analytics_replay_factor_activity)
        "SENSOR_Q" -> stringResource(id = R.string.analytics_replay_factor_sensor_q)
        "SENSOR_AGE_H" -> stringResource(id = R.string.analytics_replay_factor_sensor_age)
        "ISF_CONF" -> stringResource(id = R.string.analytics_replay_factor_isf_conf)
        "ISF_Q" -> stringResource(id = R.string.analytics_replay_factor_isf_q)
        "SET_AGE_H" -> stringResource(id = R.string.analytics_replay_factor_set_age)
        "CTX_AMBIG" -> stringResource(id = R.string.analytics_replay_factor_context_ambiguity)
        "DAWN" -> stringResource(id = R.string.analytics_replay_factor_dawn)
        "STRESS" -> stringResource(id = R.string.analytics_replay_factor_stress)
        "STEROID" -> stringResource(id = R.string.analytics_replay_factor_steroid)
        "HORMONE" -> stringResource(id = R.string.analytics_replay_factor_hormone)
        else -> raw
    }
}

@Composable
private fun replayDayTypeDisplayName(raw: String): String {
    return when (raw.uppercase(Locale.US)) {
        "WEEKDAY" -> stringResource(id = R.string.analytics_replay_day_type_weekday)
        "WEEKEND" -> stringResource(id = R.string.analytics_replay_day_type_weekend)
        else -> raw
    }
}

@Preview(showBackground = true)
@Composable
private fun AiAnalysisScreenPreview() {
    AapsCopilotTheme {
        AiAnalysisScreen(
            state = AiAnalysisUiState(
                loadState = ScreenLoadState.READY,
                isStale = false,
                cloudConfigured = true,
                filterLabel = "Filters: source=all, status=all, days=60, weeks=8",
                aiTuningStatus = AiTuningStatusUi(
                    state = "ACTIVE",
                    reason = "latest optimizer report applied",
                    generatedTs = 1_800_000_000_000L,
                    confidence = 0.71,
                    statusRaw = "active"
                ),
                jobs = listOf(
                    AiCloudJobUi(
                        jobId = "daily_analysis",
                        lastStatus = "OK",
                        lastRunTs = 1_800_000_000_000L,
                        nextRunTs = 1_800_000_360_000L,
                        lastMessage = "completed"
                    )
                ),
                historyItems = listOf(
                    AiAnalysisHistoryItemUi(
                        runTs = 1_800_000_000_000L,
                        date = "2026-03-04",
                        source = "scheduler",
                        status = "SUCCESS",
                        summary = "Morning hypo-risk remains elevated near 06:00.",
                        anomalies = listOf("Low-risk window 05:30-06:30"),
                        recommendations = listOf("Raise pre-dawn target by +0.2 mmol/L"),
                        errorMessage = null
                    )
                ),
                localRecommendations = listOf(
                    "60m horizon has highest MARD; tune residual trend decay.",
                    "COB contribution dominates top misses after dinner."
                ),
                localDailyMetrics = listOf(
                    DailyReportHorizonUi(horizonMinutes = 5, sampleCount = 120, mae = 0.24, rmse = 0.35, mardPct = 4.9, bias = 0.03),
                    DailyReportHorizonUi(horizonMinutes = 60, sampleCount = 120, mae = 0.81, rmse = 1.02, mardPct = 11.8, bias = -0.11)
                ),
                localHorizonScores = listOf(
                    AiHorizonScoreUi(horizonMinutes = 5, sampleCount = 120, mae = 0.24, mardPct = 4.9, scoreBand = "EXCELLENT"),
                    AiHorizonScoreUi(horizonMinutes = 30, sampleCount = 120, mae = 0.53, mardPct = 7.1, scoreBand = "EXCELLENT"),
                    AiHorizonScoreUi(horizonMinutes = 60, sampleCount = 120, mae = 0.81, mardPct = 11.8, scoreBand = "GOOD")
                ),
                localTopFactorsOverall = "COB=0.61;CI=0.52;IOB=0.34",
                localTopFactors = listOf(
                    AiTopFactorUi(horizonMinutes = 60, factor = "COB", contributionScore = 0.61, upliftPct = 73.1, sampleCount = 120),
                    AiTopFactorUi(horizonMinutes = 30, factor = "CI", contributionScore = 0.52, upliftPct = 62.9, sampleCount = 120)
                ),
                localHotspots = listOf(
                    AiHotspotUi(horizonMinutes = 60, hour = 19, sampleCount = 22, mae = 1.14, mardPct = 13.2, bias = -0.28)
                ),
                localTopMisses = listOf(
                    AiTopMissUi(
                        horizonMinutes = 60,
                        ts = 1_800_000_000_000L - 40 * 60_000L,
                        absError = 1.32,
                        pred = 10.4,
                        actual = 9.1,
                        cob = 32.0,
                        iob = 1.4,
                        uam = 0.28,
                        ciWidth = 1.6,
                        activity = 0.98
                    )
                ),
                localDayTypeGaps = listOf(
                    AiDayTypeGapUi(
                        horizonMinutes = 60,
                        hour = 19,
                        worseDayType = "WEEKEND",
                        maeGapMmol = 0.38,
                        mardGapPct = 3.1,
                        dominantFactor = "COB",
                        sampleCount = 22
                    )
                )
            )
        )
    }
}
