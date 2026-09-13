package io.aaps.copilot.ui.foundation.screens

import android.Manifest
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.aaps.copilot.config.UiStyle
import io.aaps.copilot.config.ClinicalAiProviderId
import io.aaps.copilot.R
import io.aaps.copilot.ui.foundation.design.AppElevation
import io.aaps.copilot.ui.foundation.design.Spacing
import io.aaps.copilot.ui.foundation.format.UiFormatters
import io.aaps.copilot.ui.foundation.theme.AapsCopilotTheme
import io.aaps.copilot.ui.foundation.theme.LocalUiStyle
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.max

private val AiSectionShape = RoundedCornerShape(18.dp)

@Composable
fun AiAnalysisScreen(
    state: AiAnalysisUiState,
    onPrepareClinicalReport: () -> Unit = {},
    onRetryLocalClinicalReportPreparation: () -> Unit = {},
    onReprepareClinicalReportPdf: () -> Unit = {},
    onSendClinicalReport: (ClinicalReportDisclosureUi) -> Unit = {},
    onCancelClinicalReport: () -> Unit = {},
    onRequestUnknownClinicalReportRetry: () -> Unit = {},
    onDismissUnknownClinicalReportRetry: () -> Unit = {},
    onConfirmUnknownClinicalReportRetry: () -> Unit = {},
    onOpenClinicalReportSettings: () -> Unit = {},
    onBeginClinicalReportPdfExport: () -> Unit = {},
    onClaimClinicalReportPdfExportTicket: (Long) -> Boolean = { false },
    onResolveClinicalReportPdfExport: (Long, Uri?) -> Unit = { _, _ -> },
    onShareClinicalReportPdf: () -> Unit = {},
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
    onChatDraftChange: (String) -> Unit = {},
    onAttachImage: (Uri) -> Unit = {},
    onAttachFile: (Uri) -> Unit = {},
    onRemoveChatAttachment: (String) -> Unit = {},
    onVoiceRepliesToggle: (Boolean) -> Unit = {},
    onStartVoiceRecording: () -> Unit = {},
    onStopVoiceRecording: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val midnightGlass = LocalUiStyle.current == UiStyle.MIDNIGHT_GLASS
    val context = LocalContext.current
    var selectedDays by rememberSaveable { mutableIntStateOf(state.windowDays) }
    var pendingPdfTicketId by rememberSaveable { mutableStateOf<Long?>(null) }
    val currentResolvePdfExport by rememberUpdatedState(onResolveClinicalReportPdfExport)
    val resolvePdfPickerResult: (Uri?) -> Unit = { uri ->
        val ticketId = pendingPdfTicketId
        if (ticketId != null) {
            pendingPdfTicketId = null
            currentResolvePdfExport(ticketId, uri)
        }
    }
    val createClinicalReportPdf = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/pdf")
    ) { uri ->
        resolvePdfPickerResult(uri)
    }
    LaunchedEffect(state.windowDays) {
        selectedDays = state.windowDays
    }
    val pdfTicket = state.clinicalReport.pdfExportTicket
    LaunchedEffect(pdfTicket?.id) {
        val ticket = pdfTicket ?: return@LaunchedEffect
        if (!onClaimClinicalReportPdfExportTicket(ticket.id)) return@LaunchedEffect
        pendingPdfTicketId = ticket.id
        try {
            createClinicalReportPdf.launch(ticket.suggestedFilename)
        } catch (_: Exception) {
            pendingPdfTicketId = null
            currentResolvePdfExport(ticket.id, null)
        }
    }
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
            runCatching {
                context.contentResolver.takePersistableUriPermission(it, flags)
            }
            onAttachImage(it)
        }
    }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
            runCatching {
                context.contentResolver.takePersistableUriPermission(it, flags)
            }
            onAttachFile(it)
        }
    }
    val audioPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) onStartVoiceRecording()
    }

    val daysOptions = listOf(3, 5, 7, 30)
    val dayTypeMetricPattern = stringResource(id = R.string.ai_analysis_replay_daytype_metric_item)
    val filteredHorizonScores = state.localHorizonScores
    val filteredTopFactors = state.localTopFactors
    val filteredHotspots = state.localHotspots
    val filteredTopMisses = state.localTopMisses
    val filteredDayTypeGaps = state.localDayTypeGaps

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
                ClinicalReportSection(
                    state = state.clinicalReport,
                    onPrepare = onPrepareClinicalReport,
                    onRetryLocalPreparation = onRetryLocalClinicalReportPreparation,
                    onRepreparePdf = onReprepareClinicalReportPdf,
                    onSend = onSendClinicalReport,
                    onCancel = onCancelClinicalReport,
                    onRequestUnknownRetry = onRequestUnknownClinicalReportRetry,
                    onDismissUnknownRetry = onDismissUnknownClinicalReportRetry,
                    onConfirmUnknownRetry = onConfirmUnknownClinicalReportRetry,
                    onOpenSettings = onOpenClinicalReportSettings,
                    onSavePdf = onBeginClinicalReportPdfExport,
                    onSharePdf = onShareClinicalReportPdf
                )
            }
            item {
                AiChatHeroCard(
                    state = state,
                    onDraftChange = onChatDraftChange,
                    onSend = { onSendChatPrompt(state.chatDraft) },
                    onAttachImage = { imagePicker.launch(arrayOf("image/*")) },
                    onAttachFile = { filePicker.launch(arrayOf("*/*")) },
                    onRemoveAttachment = onRemoveChatAttachment,
                    onVoiceRepliesToggle = onVoiceRepliesToggle,
                    onStartVoiceRecording = {
                        audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    },
                    onStopVoiceRecording = onStopVoiceRecording
                )
            }
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
                        text = if (state.cloudConfigured) {
                            stringResource(id = R.string.ai_analysis_cloud_configured)
                        } else {
                            stringResource(id = R.string.ai_analysis_cloud_openai_mode)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    IntFilterChipGroup(
                        selected = selectedDays,
                        options = daysOptions,
                        formatter = { value ->
                            stringResource(id = R.string.ai_analysis_filter_days_label, value)
                        }
                    ) {
                        selectedDays = it
                        onApplyFilters(
                            "all",
                            "all",
                            it.toString(),
                            if (it >= 30) "4" else "1"
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(Spacing.xs)
                    ) {
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
                            onClick = { onRunReplay(selectedDays, 5) },
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

            state.circadianReplaySummary?.let { summary ->
                item {
                    AiSectionCard {
                        AiSectionLabel(
                            text = stringResource(id = R.string.analytics_circadian_replay_title),
                            infoText = stringResource(id = R.string.analytics_circadian_replay_info)
                        )
                        CircadianReplaySummaryContent(
                            summary = summary,
                            emptyText = stringResource(id = R.string.analytics_empty)
                        )
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
                        val chartHorizons = listOf(5, 30, 60)
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
private fun ClinicalReportSection(
    state: ClinicalReportUiState,
    onPrepare: () -> Unit,
    onRetryLocalPreparation: () -> Unit,
    onRepreparePdf: () -> Unit,
    onSend: (ClinicalReportDisclosureUi) -> Unit,
    onCancel: () -> Unit,
    onRequestUnknownRetry: () -> Unit,
    onDismissUnknownRetry: () -> Unit,
    onConfirmUnknownRetry: () -> Unit,
    onOpenSettings: () -> Unit,
    onSavePdf: () -> Unit,
    onSharePdf: () -> Unit
) {
    val title = stringResource(id = R.string.clinical_report_title)
    var disclosureSnapshot by remember { mutableStateOf<ClinicalReportDisclosureUi?>(null) }
    var eventPreviewSnapshot by remember { mutableStateOf<String?>(null) }
    var disclosureConfirming by remember { mutableStateOf(false) }
    var submittedDisclosure by remember { mutableStateOf<ClinicalReportDisclosureUi?>(null) }
    LaunchedEffect(state.disclosure, state.remoteEventPreviewText, state.canSend) {
        val snapshot = disclosureSnapshot
        if (
            snapshot != null && (
                !state.canSend ||
                    state.disclosure != snapshot ||
                    state.remoteEventPreviewText != eventPreviewSnapshot
                )
        ) {
            disclosureSnapshot = null
            eventPreviewSnapshot = null
            disclosureConfirming = false
        }
        val submitted = submittedDisclosure
        if (submitted != null && (!state.canSend || state.disclosure != submitted)) {
            submittedDisclosure = null
        }
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("clinical_report_section")
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(horizontal = Spacing.md, vertical = Spacing.sm),
        verticalArrangement = Arrangement.spacedBy(Spacing.xs)
    ) {
        Text(
            text = title,
            modifier = Modifier.semantics { heading() },
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = stringResource(id = R.string.clinical_report_advisory_only),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        if (state.summary24h != null) {
            ClinicalPeriodSummaryRow(
                summary = state.summary24h,
                modifier = Modifier.testTag("clinical_report_summary_1")
            )
        }
        if (state.summary24h != null && (state.summary7d != null || state.summary30d != null)) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
        if (state.summary7d != null) {
            ClinicalPeriodSummaryRow(
                summary = state.summary7d,
                modifier = Modifier.testTag("clinical_report_summary_7")
            )
        }
        if (state.summary7d != null && state.summary30d != null) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
        if (state.summary30d != null) {
            ClinicalPeriodSummaryRow(
                summary = state.summary30d,
                modifier = Modifier.testTag("clinical_report_summary_30")
            )
            if (
                state.summary30d.probableMealWindows.isNotEmpty() ||
                state.summary30d.recentProbableMealWindows.isNotEmpty()
            ) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                ClinicalProbableMealWindows(
                    recentWindows = state.summary30d.recentProbableMealWindows,
                    stableWindows = state.summary30d.probableMealWindows
                )
            }
        }

        when (state.phase) {
            ClinicalReportPhaseUi.IDLE -> {
                Text(
                    text = stringResource(id = R.string.clinical_report_idle),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                ClinicalPrimaryButton(
                    text = stringResource(id = R.string.clinical_report_prepare),
                    tag = "clinical_report_prepare",
                    onClick = onPrepare
                )
            }
            ClinicalReportPhaseUi.BUILDING -> ClinicalReportBuilding()
            ClinicalReportPhaseUi.UPLOADING -> {
                val progressDescription = when (state.progressStage) {
                    ClinicalReportProgressStageUi.PREPARING ->
                        stringResource(id = R.string.clinical_report_progress_preparing)
                    ClinicalReportProgressStageUi.ANALYZING ->
                        stringResource(
                            id = R.string.clinical_report_progress_analyzing,
                            state.completedChunks,
                            state.totalChunks
                        )
                    ClinicalReportProgressStageUi.REDUCING ->
                        stringResource(
                            id = R.string.clinical_report_progress_reducing,
                            state.progressLevel,
                            state.completedChunks,
                            state.totalChunks
                        )
                    ClinicalReportProgressStageUi.SYNTHESIZING ->
                        stringResource(id = R.string.clinical_report_progress_synthesizing)
                    ClinicalReportProgressStageUi.VALIDATING ->
                        stringResource(id = R.string.clinical_report_progress_validating)
                    ClinicalReportProgressStageUi.COMPLETED ->
                        stringResource(id = R.string.clinical_report_progress_completed)
                }
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clearAndSetSemantics {
                            contentDescription = progressDescription
                            text = AnnotatedString(progressDescription)
                            progressBarRangeInfo = ProgressBarRangeInfo(
                                current = state.progress,
                                range = 0f..1f
                            )
                        }
                        .testTag("clinical_report_progress_stage")
                ) {
                    LinearProgressIndicator(
                        progress = { state.progress },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        text = progressDescription,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                OutlinedButton(
                    onClick = onCancel,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .testTag("clinical_report_cancel"),
                    enabled = state.canCancel
                ) {
                    Text(text = stringResource(id = R.string.clinical_report_cancel))
                }
            }
            ClinicalReportPhaseUi.COMPLETE -> {
                state.complete?.let { ClinicalCompleteReport(report = it) }
            }
            ClinicalReportPhaseUi.CANCELLED -> {
                ClinicalSecondaryStatus(
                    text = stringResource(id = R.string.clinical_report_cancelled),
                    warning = false
                )
            }
            ClinicalReportPhaseUi.FAILED,
            ClinicalReportPhaseUi.UNKNOWN_OUTCOME -> {
                state.failure?.let {
                    ClinicalSecondaryStatus(
                        text = stringResource(id = clinicalFailureString(it)),
                        warning = true
                    )
                }
            }
            ClinicalReportPhaseUi.LOCAL_READY -> Unit
        }

        if (state.canSavePdf) {
            OutlinedButton(
                onClick = onSavePdf,
                enabled = state.pdfExportState != ClinicalPdfExportUiState.PREPARING &&
                    state.pdfExportState != ClinicalPdfExportUiState.WRITING,
                modifier = Modifier
                    .heightIn(min = 40.dp)
                    .testTag("clinical_report_save_pdf")
            ) {
                Icon(
                    imageVector = Icons.Default.PictureAsPdf,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.size(Spacing.xs))
                Text(text = stringResource(id = R.string.clinical_report_save_pdf))
            }
        }
        if (state.canSharePdf) {
            OutlinedButton(
                onClick = onSharePdf,
                modifier = Modifier
                    .heightIn(min = 40.dp)
                    .testTag("clinical_report_share_pdf")
            ) {
                Icon(
                    imageVector = Icons.Default.Share,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.size(Spacing.xs))
                Text(text = stringResource(id = R.string.clinical_report_share_pdf))
            }
        }
        if (
            (state.canSavePdf || state.pdfRequiresReprepare) &&
            state.pdfExportState != ClinicalPdfExportUiState.IDLE
        ) {
            Text(
                text = stringResource(
                    id = when (state.pdfExportState) {
                        ClinicalPdfExportUiState.IDLE ->
                            R.string.clinical_report_pdf_preparing
                        ClinicalPdfExportUiState.PREPARING ->
                            R.string.clinical_report_pdf_preparing
                        ClinicalPdfExportUiState.READY ->
                            R.string.clinical_report_pdf_ready
                        ClinicalPdfExportUiState.WRITING ->
                            R.string.clinical_report_pdf_saving
                        ClinicalPdfExportUiState.COMPLETE ->
                            R.string.clinical_report_pdf_saved
                        ClinicalPdfExportUiState.FAILED ->
                            R.string.clinical_report_pdf_failed
                        ClinicalPdfExportUiState.TOO_LARGE ->
                            R.string.clinical_report_pdf_too_large
                        ClinicalPdfExportUiState.CANCELLED ->
                            R.string.clinical_report_pdf_cancelled
                    }
                ),
                modifier = Modifier.testTag("clinical_report_pdf_status"),
                style = MaterialTheme.typography.bodySmall,
                color = when (state.pdfExportState) {
                    ClinicalPdfExportUiState.FAILED,
                    ClinicalPdfExportUiState.TOO_LARGE -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
        }
        if (state.pdfRequiresReprepare) {
            OutlinedButton(
                onClick = onRepreparePdf,
                modifier = Modifier
                    .heightIn(min = 40.dp)
                    .testTag("clinical_report_pdf_reprepare")
            ) {
                Text(text = stringResource(id = R.string.clinical_report_pdf_reprepare))
            }
        }

        if (state.pointsToSettings) {
            OutlinedButton(
                onClick = onOpenSettings,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .testTag("clinical_report_settings")
            ) {
                Text(text = stringResource(id = R.string.clinical_report_open_settings))
            }
        }
        if (state.canRequestGuardedRetry) {
            OutlinedButton(
                onClick = onRequestUnknownRetry,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .testTag("clinical_report_review_retry")
            ) {
                Text(text = stringResource(id = R.string.clinical_report_review_retry))
            }
        } else if (state.canRetryLocalPreparation) {
            ClinicalPrimaryButton(
                text = stringResource(id = R.string.clinical_report_retry_local),
                tag = "clinical_report_retry_local",
                onClick = onRetryLocalPreparation
            )
        } else if (state.canSend) {
            ClinicalPrimaryButton(
                text = stringResource(id = R.string.clinical_report_send),
                tag = "clinical_report_send",
                enabled =
                    state.disclosure != null &&
                        submittedDisclosure != state.disclosure,
                onClick = {
                    state.disclosure?.let { disclosure ->
                        if (submittedDisclosure == disclosure) return@let
                        disclosureConfirming = false
                        disclosureSnapshot = disclosure
                        eventPreviewSnapshot = state.remoteEventPreviewText
                    }
                }
            )
        }
    }

    disclosureSnapshot?.let { snapshot ->
        val disclosureBodyMaxHeight = (
            LocalConfiguration.current.screenHeightDp.dp * 0.4f
            ).coerceIn(96.dp, 320.dp)
        AlertDialog(
            onDismissRequest = {
                if (!disclosureConfirming) {
                    disclosureSnapshot = null
                    eventPreviewSnapshot = null
                }
            },
            modifier = Modifier.testTag("clinical_report_send_disclosure"),
            shape = RoundedCornerShape(8.dp),
            title = {
                Text(text = stringResource(id = R.string.clinical_report_disclosure_title))
            },
            text = {
                Column(
                    modifier = Modifier
                        .heightIn(max = disclosureBodyMaxHeight)
                        .verticalScroll(rememberScrollState())
                        .testTag("clinical_report_disclosure_scroll"),
                    verticalArrangement = Arrangement.spacedBy(Spacing.xs)
                ) {
                    Text(
                        text = stringResource(id = R.string.clinical_report_disclosure_data),
                        modifier = Modifier.testTag("clinical_report_disclosure_data"),
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        text = stringResource(
                            id = R.string.clinical_report_disclosure_provider,
                            clinicalAiProviderLabel(snapshot.providerId)
                        ),
                        modifier = Modifier.testTag("clinical_report_disclosure_provider"),
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        text = stringResource(
                            id = R.string.clinical_report_disclosure_model,
                            snapshot.model
                        ),
                        modifier = Modifier.testTag("clinical_report_disclosure_model"),
                        style = MaterialTheme.typography.bodySmall
                    )
                    snapshot.endpointHost?.let { host ->
                        Text(
                            text = stringResource(
                                id = R.string.clinical_report_disclosure_endpoint_host,
                                host
                            ),
                            modifier = Modifier.testTag("clinical_report_disclosure_endpoint"),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    Text(
                        text = stringResource(id = R.string.clinical_report_remote_event_preview),
                        style = MaterialTheme.typography.labelMedium
                    )
                    Text(
                        text = eventPreviewSnapshot.orEmpty(),
                        modifier = Modifier.testTag("clinical_report_remote_event_preview"),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        if (!disclosureConfirming) {
                            disclosureSnapshot = null
                            eventPreviewSnapshot = null
                        }
                    },
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .testTag("clinical_report_disclosure_cancel"),
                    enabled = !disclosureConfirming
                ) {
                    Text(text = stringResource(id = R.string.action_cancel))
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (disclosureConfirming) return@TextButton
                        if (
                            !state.canSend ||
                            state.disclosure != snapshot ||
                            state.remoteEventPreviewText != eventPreviewSnapshot
                        ) {
                            disclosureSnapshot = null
                            eventPreviewSnapshot = null
                            return@TextButton
                        }
                        disclosureConfirming = true
                        submittedDisclosure = snapshot
                        disclosureSnapshot = null
                        eventPreviewSnapshot = null
                        onSend(snapshot)
                    },
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .testTag("clinical_report_disclosure_confirm"),
                    enabled =
                        !disclosureConfirming &&
                            state.canSend &&
                            state.disclosure == snapshot
                ) {
                    Text(
                        text = stringResource(
                            id = R.string.clinical_report_disclosure_confirm
                        )
                    )
                }
            }
        )
    }

    if (state.showRetryConfirmation) {
        AlertDialog(
            onDismissRequest = onDismissUnknownRetry,
            shape = RoundedCornerShape(8.dp),
            title = {
                Text(text = stringResource(id = R.string.clinical_report_retry_title))
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    Text(text = stringResource(id = R.string.clinical_report_retry_body))
                    state.retryFeedback?.let { feedback ->
                        Text(
                            text = stringResource(
                                id = when (feedback) {
                                    ClinicalReportRetryFeedbackUi.PENDING ->
                                        R.string.clinical_report_retry_pending
                                    ClinicalReportRetryFeedbackUi.IN_FLIGHT ->
                                        R.string.clinical_report_retry_in_flight
                                    ClinicalReportRetryFeedbackUi.FAILED ->
                                        R.string.clinical_report_retry_failed
                                }
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            },
            dismissButton = {
                TextButton(
                    onClick = onDismissUnknownRetry,
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .testTag("clinical_report_retry_cancel")
                ) {
                    Text(text = stringResource(id = R.string.action_cancel))
                }
            },
            confirmButton = {
                TextButton(
                    onClick = onConfirmUnknownRetry,
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .testTag("clinical_report_retry_confirm"),
                    enabled = state.retryFeedback != ClinicalReportRetryFeedbackUi.PENDING
                ) {
                    Text(text = stringResource(id = R.string.clinical_report_retry_confirm))
                }
            }
        )
    }
}

@Composable
private fun ClinicalProbableMealWindows(
    recentWindows: List<ProbableMealWindowUi>,
    stableWindows: List<ProbableMealWindowUi>
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("clinical_report_probable_meal_windows"),
        verticalArrangement = Arrangement.spacedBy(Spacing.xxs)
    ) {
        Text(
            text = stringResource(id = R.string.analytics_probable_meal_windows),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = stringResource(id = R.string.analytics_probable_meal_windows_info),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        ClinicalMealWindowHorizon(
            label = stringResource(id = R.string.analytics_probable_meal_windows_recent),
            windows = recentWindows
        )
        ClinicalMealWindowHorizon(
            label = stringResource(id = R.string.analytics_probable_meal_windows_stable),
            windows = stableWindows
        )
    }
}

@Composable
private fun ClinicalMealWindowHorizon(
    label: String,
    windows: List<ProbableMealWindowUi>
) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary
    )
    if (windows.isEmpty()) {
        Text(
            text = stringResource(id = R.string.analytics_probable_meal_windows_horizon_empty),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    } else {
        windows.sortedBy(ProbableMealWindowUi::medianMinuteOfDay).forEach { window ->
            Text(
                text = stringResource(
                    id = R.string.clinical_report_probable_meal_window_row,
                    clinicalMealWindowClock(window.medianMinuteOfDay),
                    clinicalMealWindowClock(window.startMinuteOfDay),
                    clinicalMealWindowClock(window.endMinuteOfDay),
                    window.supportDays,
                    window.lookbackDays,
                    window.enteredEpisodeCount,
                    window.uamEpisodeCount
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

private fun clinicalMealWindowClock(minuteOfDay: Int): String {
    val bounded = minuteOfDay.coerceIn(0, 24 * 60 - 1)
    return String.format(Locale.US, "%02d:%02d", bounded / 60, bounded % 60)
}

@Composable
private fun ClinicalPeriodSummaryRow(
    summary: ClinicalPeriodSummaryUi,
    modifier: Modifier = Modifier
) {
    val periodLabel = if (summary.days == 1) {
        stringResource(id = R.string.clinical_text_period_last_24_hours)
    } else {
        stringResource(id = R.string.clinical_report_period_days, summary.days)
    }
    val coverageLabel = stringResource(id = R.string.clinical_report_coverage)
    val coverageValue = clinicalNumericValue(
        value = summary.coveragePct,
        decimals = 1,
        unit = ClinicalNumericUnitUi.PERCENT
    )
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Spacing.xxs)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = periodLabel,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = stringResource(id = clinicalLocalQualityString(summary.quality)),
                style = MaterialTheme.typography.labelMedium,
                color = when (summary.quality) {
                    ClinicalLocalDataQualityUi.GOOD -> MaterialTheme.colorScheme.primary
                    ClinicalLocalDataQualityUi.LIMITED -> MaterialTheme.colorScheme.tertiary
                    ClinicalLocalDataQualityUi.INSUFFICIENT -> MaterialTheme.colorScheme.error
                }
            )
        }
        LinearProgressIndicator(
            progress = { summary.coverageProgress },
            modifier = Modifier
                .fillMaxWidth()
                .height(2.dp)
                .semantics {
                    contentDescription = listOfNotNull(
                        periodLabel,
                        coverageLabel,
                        coverageValue
                    ).joinToString(", ")
                    progressBarRangeInfo = ProgressBarRangeInfo(
                        current = summary.coverageProgress,
                        range = 0f..1f
                    )
                }
        )
        ClinicalMetricPair(
            firstLabel = stringResource(id = R.string.clinical_report_mean),
            firstValue = clinicalNumericValue(
                summary.meanGlucose,
                1,
                ClinicalNumericUnitUi.MMOL_L
            ),
            secondLabel = stringResource(id = R.string.clinical_report_median),
            secondValue = clinicalNumericValue(
                summary.medianGlucose,
                1,
                ClinicalNumericUnitUi.MMOL_L
            )
        )
        ClinicalMetricPair(
            firstLabel = stringResource(id = R.string.clinical_report_variability),
            firstValue = clinicalNumericValue(
                summary.variability,
                1,
                ClinicalNumericUnitUi.PERCENT
            ),
            secondLabel = stringResource(id = R.string.clinical_report_below_4),
            secondValue = clinicalNumericValue(
                summary.belowRange,
                1,
                ClinicalNumericUnitUi.PERCENT
            )
        )
        ClinicalMetricPair(
            firstLabel = stringResource(id = R.string.clinical_report_in_range),
            firstValue = clinicalNumericValue(
                summary.inRange,
                1,
                ClinicalNumericUnitUi.PERCENT
            ),
            secondLabel = stringResource(id = R.string.clinical_report_above_range),
            secondValue = clinicalNumericValue(
                summary.aboveRange,
                1,
                ClinicalNumericUnitUi.PERCENT
            )
        )
        ClinicalMetricPair(
            firstLabel = stringResource(
                id = if (summary.therapyTotalsAuthoritative) {
                    R.string.clinical_report_total_insulin
                } else {
                    R.string.clinical_report_copilot_events_insulin
                }
            ),
            firstValue = clinicalNumericValue(
                summary.recordedInsulin,
                1,
                ClinicalNumericUnitUi.INSULIN_UNITS
            ),
            secondLabel = stringResource(
                id = if (summary.therapyTotalsAuthoritative) {
                    R.string.clinical_report_total_carbs
                } else {
                    R.string.clinical_report_copilot_events_carbs
                }
            ),
            secondValue = clinicalNumericValue(
                summary.realCarbs,
                1,
                ClinicalNumericUnitUi.GRAMS
            )
        )
        if (summary.deliveredBasalInsulin != null || summary.deliveredBolusInsulin != null) {
            ClinicalMetricPair(
                firstLabel = stringResource(id = R.string.clinical_report_basal_insulin),
                firstValue = clinicalNumericValue(
                    summary.deliveredBasalInsulin,
                    1,
                    ClinicalNumericUnitUi.INSULIN_UNITS
                ),
                secondLabel = stringResource(id = R.string.clinical_report_bolus_insulin),
                secondValue = clinicalNumericValue(
                    summary.deliveredBolusInsulin,
                    1,
                    ClinicalNumericUnitUi.INSULIN_UNITS
                )
            )
        }
        ClinicalMetricPair(
            firstLabel = stringResource(id = R.string.clinical_report_entered_carbs),
            firstValue = clinicalNumericValue(
                summary.enteredCarbs,
                1,
                ClinicalNumericUnitUi.GRAMS
            ),
            secondLabel = stringResource(id = R.string.clinical_report_uam_carbs),
            secondValue = clinicalNumericValue(
                summary.uamCarbs,
                1,
                ClinicalNumericUnitUi.GRAMS
            )
        )
        ClinicalMetric(
            label = stringResource(id = R.string.clinical_report_aaps_carbs_diagnostic),
            value = clinicalNumericValue(
                summary.aapsCarbs,
                1,
                ClinicalNumericUnitUi.GRAMS
            ),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("clinical_report_aaps_carbs_${summary.days}")
        )
        ClinicalMetricPair(
            firstLabel = stringResource(id = R.string.clinical_report_iob_derived_insulin),
            firstValue = clinicalNumericValue(
                summary.iobDerivedInsulin,
                1,
                ClinicalNumericUnitUi.INSULIN_UNITS
            ),
            secondLabel = stringResource(id = R.string.clinical_report_steps),
            secondValue = clinicalNumericValue(
                summary.steps,
                0,
                ClinicalNumericUnitUi.COUNT
            )
        )
        ClinicalMetricPair(
            firstLabel = stringResource(id = R.string.clinical_report_active_minutes),
            firstValue = clinicalNumericValue(
                summary.activeMinutes,
                0,
                ClinicalNumericUnitUi.MINUTES
            ),
            secondLabel = stringResource(id = R.string.clinical_report_activity_coverage),
            secondValue = clinicalNumericValue(
                summary.activityCoveragePct,
                1,
                ClinicalNumericUnitUi.PERCENT
            )
        )
        ClinicalMetricPair(
            firstLabel = stringResource(id = R.string.clinical_report_carbohydrate_energy),
            firstValue = clinicalNumericValue(
                summary.carbohydrateEnergyKcal,
                0,
                ClinicalNumericUnitUi.KILOCALORIES
            ),
            secondLabel = stringResource(id = R.string.clinical_report_active_calories),
            secondValue = clinicalNumericValue(
                summary.activeCaloriesKcal,
                0,
                ClinicalNumericUnitUi.KILOCALORIES
            )
        )
        ClinicalMetricPair(
            firstLabel = stringResource(id = R.string.clinical_report_coverage),
            firstValue = coverageValue,
            secondLabel = stringResource(id = R.string.clinical_report_max_gap),
            secondValue = clinicalNumericValue(
                summary.maxGapMinutes?.toDouble(),
                0,
                ClinicalNumericUnitUi.MINUTES
            )
        )
    }
}

@Composable
private fun ClinicalMetricPair(
    firstLabel: String,
    firstValue: String?,
    secondLabel: String,
    secondValue: String?
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Spacing.md)
    ) {
        ClinicalMetric(
            label = firstLabel,
            value = firstValue,
            modifier = Modifier.weight(1f)
        )
        ClinicalMetric(
            label = secondLabel,
            value = secondValue,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun ClinicalMetric(
    label: String,
    value: String?,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(Spacing.xxs)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value ?: stringResource(id = R.string.clinical_report_unavailable),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
private fun ClinicalReportBuilding() {
    val progressDescription = stringResource(id = R.string.clinical_report_building)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clearAndSetSemantics {
                contentDescription = progressDescription
                progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate
            },
        horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
        verticalAlignment = Alignment.CenterVertically
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(20.dp),
            strokeWidth = 2.dp
        )
        Text(
            text = progressDescription,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xxs)) {
        repeat(2) {
            Spacer(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            )
        }
    }
}

@Composable
private fun ClinicalPrimaryButton(
    text: String,
    tag: String,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .testTag(tag),
        enabled = enabled,
        shape = RoundedCornerShape(8.dp)
    ) {
        Text(text = text)
    }
}

@Composable
private fun ClinicalSecondaryStatus(
    text: String,
    warning: Boolean
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = if (warning) Icons.Default.Warning else Icons.Default.Info,
            contentDescription = null,
            tint = if (warning) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun ClinicalCompleteReport(report: ClinicalCompleteReportUi) {
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    ClinicalReportSubsection(
        title = stringResource(id = R.string.clinical_report_summary_status),
        modifier = Modifier.testTag("clinical_report_complete_status"),
        rows = listOf(
            stringResource(
                id = R.string.clinical_report_status_line,
                7,
                stringResource(id = clinicalTextString(report.summary7dStatus))
            ),
            stringResource(
                id = R.string.clinical_report_status_line,
                30,
                stringResource(id = clinicalTextString(report.summary30dStatus))
            )
        )
    )
    ClinicalReportSubsection(
        title = stringResource(id = R.string.clinical_report_data_quality),
        modifier = Modifier.testTag("clinical_report_complete_quality"),
        rows = report.dataQuality.map { stringResource(id = clinicalTextString(it)) }
    )
    if (report.patterns.isNotEmpty()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("clinical_report_complete_observations"),
            verticalArrangement = Arrangement.spacedBy(Spacing.xxs)
        ) {
            Text(
                text = stringResource(id = R.string.clinical_report_observations),
                style = MaterialTheme.typography.titleSmall
            )
            report.patterns.forEach { finding ->
                Text(
                    text = stringResource(
                        id = R.string.clinical_report_finding_line,
                        stringResource(id = clinicalTextString(finding.topic)),
                        stringResource(id = clinicalTextString(finding.period)),
                        stringResource(id = clinicalTextString(finding.direction)),
                        stringResource(id = clinicalTextString(finding.timeBand)),
                        stringResource(id = clinicalTextString(finding.confidence))
                    ),
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    text = stringResource(
                        id = R.string.clinical_report_evidence_line,
                        stringResource(id = clinicalTextString(finding.evidence.metric)),
                        clinicalNumericValue(
                            finding.evidence.value,
                            finding.evidence.decimals,
                            finding.evidence.unit
                        ).orEmpty()
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
    ClinicalReportSubsection(
        title = stringResource(id = R.string.clinical_report_safety_observations),
        modifier = Modifier.testTag("clinical_report_complete_safety"),
        rows = report.safetyObservations.map { stringResource(id = clinicalTextString(it)) }
    )
    if (report.recommendations.isNotEmpty()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("clinical_report_complete_recommendations"),
            verticalArrangement = Arrangement.spacedBy(Spacing.xxs)
        ) {
            Text(
                text = stringResource(id = R.string.clinical_report_topics_care_team),
                style = MaterialTheme.typography.titleSmall
            )
            report.recommendations.forEach { recommendation ->
                Text(
                    text = stringResource(
                        id = R.string.clinical_report_recommendation_line,
                        stringResource(id = clinicalTextString(recommendation.topic)),
                        stringResource(id = clinicalTextString(recommendation.priority)),
                        stringResource(id = clinicalTextString(recommendation.period))
                    ),
                    style = MaterialTheme.typography.bodySmall
                )
                recommendation.linkedEvidence.forEach { finding ->
                    Text(
                        text = stringResource(
                            id = R.string.clinical_report_recommendation_evidence_line,
                            stringResource(id = clinicalTextString(finding.topic)),
                            stringResource(id = clinicalTextString(finding.evidence.metric)),
                            clinicalNumericValue(
                                finding.evidence.value,
                                finding.evidence.decimals,
                                finding.evidence.unit
                            ).orEmpty()
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
    ClinicalReportSubsection(
        title = stringResource(id = R.string.clinical_report_questions_care_team),
        modifier = Modifier.testTag("clinical_report_complete_questions"),
        rows = report.careTeamQuestions.map { stringResource(id = clinicalTextString(it)) }
    )
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("clinical_report_complete_metadata"),
        verticalArrangement = Arrangement.spacedBy(Spacing.xxs)
    ) {
        Text(
            text = stringResource(
                id = R.string.clinical_report_generated,
                clinicalTimestamp(report.metadata.generatedAtTs)
                    ?: stringResource(id = R.string.clinical_report_unavailable)
            ),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        report.metadata.model?.let {
            Text(
                text = stringResource(id = R.string.clinical_report_model, it),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Text(
            text = stringResource(
                id = R.string.clinical_report_provider,
                clinicalAiProviderLabel(report.metadata.providerId)
            ),
            modifier = Modifier.testTag("clinical_report_complete_provider"),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (
            report.metadata.schemaName != null &&
            report.metadata.schemaVersion != null
        ) {
            Text(
                text = stringResource(
                    id = R.string.clinical_report_schema,
                    report.metadata.schemaName,
                    report.metadata.schemaVersion
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun clinicalNumericValue(
    value: Double?,
    decimals: Int,
    unit: ClinicalNumericUnitUi
): String? {
    val locale = LocalConfiguration.current.locales[0] ?: Locale.getDefault()
    if (unit == ClinicalNumericUnitUi.PERCENT) {
        return value?.let {
            ClinicalReportFormatter.formatPercent(it / 100.0, decimals, locale)
        }
    }
    val number = value?.let {
        ClinicalReportFormatter.formatNumber(it, decimals, locale)
    } ?: return null
    if (unit == ClinicalNumericUnitUi.COUNT) return number
    val unitText = stringResource(
        id = when (unit) {
            ClinicalNumericUnitUi.MMOL_L -> R.string.unit_mmol_l
            ClinicalNumericUnitUi.PERCENT -> error("Percent uses locale-native formatting")
            ClinicalNumericUnitUi.INSULIN_UNITS -> R.string.unit_u
            ClinicalNumericUnitUi.GRAMS -> R.string.unit_g
            ClinicalNumericUnitUi.MINUTES -> R.string.unit_minutes
            ClinicalNumericUnitUi.KILOCALORIES -> R.string.unit_kcal
            ClinicalNumericUnitUi.COUNT -> error("Count has no unit")
        }
    )
    return stringResource(
        id = R.string.clinical_report_value_with_unit,
        number,
        unitText
    )
}

@Composable
private fun clinicalTimestamp(timestamp: Long?): String? {
    val value = timestamp ?: return null
    val locale = LocalConfiguration.current.locales[0] ?: Locale.getDefault()
    return ClinicalReportFormatter.formatTimestamp(
        timestamp = value,
        locale = locale,
        zoneId = ZoneId.systemDefault()
    )
}

@Composable
private fun ClinicalReportSubsection(
    title: String,
    rows: List<String>,
    modifier: Modifier = Modifier
) {
    if (rows.isEmpty()) return
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Spacing.xxs)
    ) {
        Text(text = title, style = MaterialTheme.typography.titleSmall)
        rows.forEach { row ->
            Text(
                text = row,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private fun clinicalLocalQualityString(value: ClinicalLocalDataQualityUi): Int = when (value) {
    ClinicalLocalDataQualityUi.GOOD -> R.string.clinical_report_quality_good
    ClinicalLocalDataQualityUi.LIMITED -> R.string.clinical_report_quality_limited
    ClinicalLocalDataQualityUi.INSUFFICIENT -> R.string.clinical_report_quality_insufficient
}

@Composable
private fun clinicalAiProviderLabel(providerId: ClinicalAiProviderId): String =
    stringResource(
        id = when (providerId) {
            ClinicalAiProviderId.OPENAI -> R.string.settings_clinical_ai_provider_openai
            ClinicalAiProviderId.ANTHROPIC -> R.string.settings_clinical_ai_provider_anthropic
            ClinicalAiProviderId.GEMINI -> R.string.settings_clinical_ai_provider_gemini
            ClinicalAiProviderId.OPENAI_COMPATIBLE ->
                R.string.settings_clinical_ai_provider_compatible
        }
    )

private fun clinicalFailureString(value: ClinicalReportFailureUi): Int = when (value) {
    ClinicalReportFailureUi.LOCAL_BUILD -> R.string.clinical_report_failure_local_build
    ClinicalReportFailureUi.CREDENTIAL_UNAVAILABLE ->
        R.string.clinical_report_failure_credential
    ClinicalReportFailureUi.UNAUTHORIZED -> R.string.clinical_report_failure_unauthorized
    ClinicalReportFailureUi.RATE_LIMITED -> R.string.clinical_report_failure_rate_limited
    ClinicalReportFailureUi.SERVER -> R.string.clinical_report_failure_server
    ClinicalReportFailureUi.HTTP -> R.string.clinical_report_failure_http
    ClinicalReportFailureUi.TIMEOUT -> R.string.clinical_report_failure_timeout
    ClinicalReportFailureUi.NETWORK -> R.string.clinical_report_failure_network
    ClinicalReportFailureUi.REFUSAL -> R.string.clinical_report_failure_refusal
    ClinicalReportFailureUi.INCOMPLETE -> R.string.clinical_report_failure_incomplete
    ClinicalReportFailureUi.INVALID_RESPONSE -> R.string.clinical_report_failure_invalid_response
    ClinicalReportFailureUi.OVERSIZED_RESPONSE ->
        R.string.clinical_report_failure_oversized_response
    ClinicalReportFailureUi.REQUEST_TOO_LARGE ->
        R.string.clinical_report_failure_request_too_large
    ClinicalReportFailureUi.DATASET_TOO_LARGE ->
        R.string.clinical_report_failure_dataset_too_large
    ClinicalReportFailureUi.INVALID_INPUT -> R.string.clinical_report_failure_invalid_input
    ClinicalReportFailureUi.PARTIAL_CHUNK -> R.string.clinical_report_failure_partial_chunk
    ClinicalReportFailureUi.UNKNOWN_REMOTE_OUTCOME ->
        R.string.clinical_report_failure_unknown_outcome
    ClinicalReportFailureUi.PROCESS_INTERRUPTED_PRE_REQUEST ->
        R.string.clinical_report_failure_interrupted
    ClinicalReportFailureUi.CANCELLED -> R.string.clinical_report_cancelled
    ClinicalReportFailureUi.OTHER -> R.string.clinical_report_failure_other
}

private fun clinicalTextString(value: ClinicalReportTextKey): Int = when (value) {
    ClinicalReportTextKey.SUMMARY_STABLE -> R.string.clinical_text_summary_stable
    ClinicalReportTextKey.SUMMARY_HIGH_VARIABILITY ->
        R.string.clinical_text_summary_high_variability
    ClinicalReportTextKey.SUMMARY_LOW_EXPOSURE -> R.string.clinical_text_summary_low_exposure
    ClinicalReportTextKey.SUMMARY_HIGH_EXPOSURE -> R.string.clinical_text_summary_high_exposure
    ClinicalReportTextKey.SUMMARY_MIXED -> R.string.clinical_text_summary_mixed
    ClinicalReportTextKey.SUMMARY_INSUFFICIENT_DATA ->
        R.string.clinical_text_summary_insufficient_data
    ClinicalReportTextKey.QUALITY_COMPLETE -> R.string.clinical_text_quality_complete
    ClinicalReportTextKey.QUALITY_PARTIAL_COVERAGE ->
        R.string.clinical_text_quality_partial_coverage
    ClinicalReportTextKey.QUALITY_MISSING_INTERVALS ->
        R.string.clinical_text_quality_missing_intervals
    ClinicalReportTextKey.QUALITY_SENSOR_GAPS -> R.string.clinical_text_quality_sensor_gaps
    ClinicalReportTextKey.QUALITY_THERAPY_GAPS -> R.string.clinical_text_quality_therapy_gaps
    ClinicalReportTextKey.QUALITY_TARGET_GAPS -> R.string.clinical_text_quality_target_gaps
    ClinicalReportTextKey.QUALITY_FORECAST_GAPS -> R.string.clinical_text_quality_forecast_gaps
    ClinicalReportTextKey.QUALITY_TELEMETRY_GAPS ->
        R.string.clinical_text_quality_telemetry_gaps
    ClinicalReportTextKey.QUALITY_INSUFFICIENT_DATA ->
        R.string.clinical_text_quality_insufficient_data
    ClinicalReportTextKey.OBSERVATION_GLUCOSE_STABILITY ->
        R.string.clinical_text_observation_glucose_stability
    ClinicalReportTextKey.OBSERVATION_GLUCOSE_VARIABILITY ->
        R.string.clinical_text_observation_glucose_variability
    ClinicalReportTextKey.OBSERVATION_LOW_EXPOSURE ->
        R.string.clinical_text_observation_low_exposure
    ClinicalReportTextKey.OBSERVATION_HIGH_EXPOSURE ->
        R.string.clinical_text_observation_high_exposure
    ClinicalReportTextKey.OBSERVATION_MEAL_ASSOCIATION ->
        R.string.clinical_text_observation_meal_association
    ClinicalReportTextKey.OBSERVATION_OVERNIGHT_PATTERN ->
        R.string.clinical_text_observation_overnight_pattern
    ClinicalReportTextKey.OBSERVATION_TARGET_ALIGNMENT ->
        R.string.clinical_text_observation_target_alignment
    ClinicalReportTextKey.OBSERVATION_SENSOR_RELIABILITY ->
        R.string.clinical_text_observation_sensor_reliability
    ClinicalReportTextKey.OBSERVATION_INFUSION_SET_SIGNAL ->
        R.string.clinical_text_observation_infusion_set_signal
    ClinicalReportTextKey.OBSERVATION_DATA_COVERAGE ->
        R.string.clinical_text_observation_data_coverage
    ClinicalReportTextKey.DIRECTION_STABLE -> R.string.clinical_text_direction_stable
    ClinicalReportTextKey.DIRECTION_INCREASING -> R.string.clinical_text_direction_increasing
    ClinicalReportTextKey.DIRECTION_DECREASING -> R.string.clinical_text_direction_decreasing
    ClinicalReportTextKey.DIRECTION_INTERMITTENT ->
        R.string.clinical_text_direction_intermittent
    ClinicalReportTextKey.DIRECTION_MIXED -> R.string.clinical_text_direction_mixed
    ClinicalReportTextKey.DIRECTION_NOT_APPLICABLE ->
        R.string.clinical_text_direction_not_applicable
    ClinicalReportTextKey.TIME_ALL_DAY -> R.string.clinical_text_time_all_day
    ClinicalReportTextKey.TIME_OVERNIGHT -> R.string.clinical_text_time_overnight
    ClinicalReportTextKey.TIME_MORNING -> R.string.clinical_text_time_morning
    ClinicalReportTextKey.TIME_AFTERNOON -> R.string.clinical_text_time_afternoon
    ClinicalReportTextKey.TIME_EVENING -> R.string.clinical_text_time_evening
    ClinicalReportTextKey.CONFIDENCE_LOW -> R.string.clinical_text_confidence_low
    ClinicalReportTextKey.CONFIDENCE_MEDIUM -> R.string.clinical_text_confidence_medium
    ClinicalReportTextKey.CONFIDENCE_HIGH -> R.string.clinical_text_confidence_high
    ClinicalReportTextKey.EVIDENCE_MEAN_GLUCOSE -> R.string.clinical_report_mean
    ClinicalReportTextKey.EVIDENCE_MEDIAN_GLUCOSE -> R.string.clinical_report_median
    ClinicalReportTextKey.EVIDENCE_MEAN_TARGET -> R.string.clinical_text_evidence_mean_target
    ClinicalReportTextKey.EVIDENCE_VARIABILITY -> R.string.clinical_report_variability
    ClinicalReportTextKey.EVIDENCE_BELOW_RANGE -> R.string.clinical_report_below_4
    ClinicalReportTextKey.EVIDENCE_IN_RANGE -> R.string.clinical_report_in_range
    ClinicalReportTextKey.EVIDENCE_ABOVE_RANGE -> R.string.clinical_report_above_range
    ClinicalReportTextKey.EVIDENCE_COVERAGE -> R.string.clinical_report_coverage
    ClinicalReportTextKey.EVIDENCE_MAX_GAP -> R.string.clinical_report_max_gap
    ClinicalReportTextKey.EVIDENCE_DURATION -> R.string.clinical_text_evidence_duration
    ClinicalReportTextKey.EVIDENCE_RECORDED_INSULIN ->
        R.string.clinical_report_recorded_insulin
    ClinicalReportTextKey.EVIDENCE_RECORDED_CARBS -> R.string.clinical_report_real_carbs
    ClinicalReportTextKey.EVIDENCE_SAMPLE_COUNT ->
        R.string.clinical_text_evidence_sample_count
    ClinicalReportTextKey.DISCUSSION_SENSOR_RELIABILITY ->
        R.string.clinical_text_discussion_sensor_reliability
    ClinicalReportTextKey.DISCUSSION_INFUSION_SET_REVIEW ->
        R.string.clinical_text_discussion_infusion_set_review
    ClinicalReportTextKey.DISCUSSION_MEAL_TIMING_REVIEW ->
        R.string.clinical_text_discussion_meal_timing_review
    ClinicalReportTextKey.DISCUSSION_ISF_CR_REVIEW ->
        R.string.clinical_text_discussion_isf_cr_review
    ClinicalReportTextKey.DISCUSSION_TARGET_PATTERN_REVIEW ->
        R.string.clinical_text_discussion_target_pattern_review
    ClinicalReportTextKey.DISCUSSION_DATA_QUALITY_REVIEW ->
        R.string.clinical_text_discussion_data_quality_review
    ClinicalReportTextKey.DISCUSSION_LOW_RISK_REVIEW ->
        R.string.clinical_text_discussion_low_risk_review
    ClinicalReportTextKey.DISCUSSION_OTHER_CLINICAL_REVIEW ->
        R.string.clinical_text_discussion_other_clinical_review
    ClinicalReportTextKey.PRIORITY_LOW -> R.string.clinical_text_priority_low
    ClinicalReportTextKey.PRIORITY_MEDIUM -> R.string.clinical_text_priority_medium
    ClinicalReportTextKey.PRIORITY_HIGH -> R.string.clinical_text_priority_high
    ClinicalReportTextKey.PERIOD_LAST_24_HOURS -> R.string.clinical_text_period_last_24_hours
    ClinicalReportTextKey.PERIOD_LAST_7_DAYS -> R.string.clinical_text_period_last_7_days
    ClinicalReportTextKey.PERIOD_LAST_30_DAYS -> R.string.clinical_text_period_last_30_days
    ClinicalReportTextKey.PERIOD_COMPARATIVE_7D_30D ->
        R.string.clinical_text_period_comparative
    ClinicalReportTextKey.SAFETY_RECURRENT_LOW_PATTERN ->
        R.string.clinical_text_safety_recurrent_low_pattern
    ClinicalReportTextKey.SAFETY_PROLONGED_LOW_PATTERN ->
        R.string.clinical_text_safety_prolonged_low_pattern
    ClinicalReportTextKey.SAFETY_HIGH_EXPOSURE_PATTERN ->
        R.string.clinical_text_safety_high_exposure_pattern
    ClinicalReportTextKey.SAFETY_HIGH_VARIABILITY_PATTERN ->
        R.string.clinical_text_safety_high_variability_pattern
    ClinicalReportTextKey.SAFETY_SENSOR_RELIABILITY_CONCERN ->
        R.string.clinical_text_safety_sensor_reliability_concern
    ClinicalReportTextKey.SAFETY_INFUSION_SET_REVIEW_SIGNAL ->
        R.string.clinical_text_safety_infusion_set_review_signal
    ClinicalReportTextKey.SAFETY_INSUFFICIENT_DATA ->
        R.string.clinical_text_safety_insufficient_data
    ClinicalReportTextKey.SAFETY_NONE_IDENTIFIED ->
        R.string.clinical_text_safety_none_identified
    ClinicalReportTextKey.QUESTION_SENSOR_RELIABILITY_CONTEXT ->
        R.string.clinical_text_question_sensor_reliability_context
    ClinicalReportTextKey.QUESTION_INFUSION_SET_CONTEXT ->
        R.string.clinical_text_question_infusion_set_context
    ClinicalReportTextKey.QUESTION_MEAL_TIMING_CONTEXT ->
        R.string.clinical_text_question_meal_timing_context
    ClinicalReportTextKey.QUESTION_ISF_CR_CONTEXT -> R.string.clinical_text_question_isf_cr_context
    ClinicalReportTextKey.QUESTION_TARGET_PATTERN_CONTEXT ->
        R.string.clinical_text_question_target_pattern_context
    ClinicalReportTextKey.QUESTION_LOW_PATTERN_CONTEXT ->
        R.string.clinical_text_question_low_pattern_context
    ClinicalReportTextKey.QUESTION_HIGH_PATTERN_CONTEXT ->
        R.string.clinical_text_question_high_pattern_context
    ClinicalReportTextKey.QUESTION_DATA_COMPLETENESS_CONTEXT ->
        R.string.clinical_text_question_data_completeness_context
}

@Composable
private fun AiChatHeroCard(
    state: AiAnalysisUiState,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit,
    onAttachImage: () -> Unit,
    onAttachFile: () -> Unit,
    onRemoveAttachment: (String) -> Unit,
    onVoiceRepliesToggle: (Boolean) -> Unit,
    onStartVoiceRecording: () -> Unit,
    onStopVoiceRecording: () -> Unit
) {
    val midnightGlass = LocalUiStyle.current == UiStyle.MIDNIGHT_GLASS
    val quickPrompts = listOf(
        stringResource(id = R.string.ai_analysis_chat_quick_trend),
        stringResource(id = R.string.ai_analysis_chat_quick_errors),
        stringResource(id = R.string.ai_analysis_chat_quick_isfcr),
        stringResource(id = R.string.ai_analysis_chat_quick_uam)
    )
    AiSectionCard(
        modifier = Modifier.animateContentSize()
    ) {
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
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(Spacing.xxs)
            ) {
                Text(
                    text = stringResource(id = R.string.ai_analysis_chat_voice_mode_title),
                    style = MaterialTheme.typography.titleSmall
                )
                Text(
                    text = when {
                        state.chatRecording -> stringResource(id = R.string.ai_analysis_chat_recording_active)
                        state.chatVoiceBusy -> stringResource(id = R.string.ai_analysis_chat_voice_busy)
                        state.chatSpeaking -> stringResource(id = R.string.ai_analysis_chat_speaking)
                        else -> stringResource(id = R.string.ai_analysis_chat_voice_mode_body)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = state.chatVoiceRepliesEnabled,
                onCheckedChange = onVoiceRepliesToggle
            )
        }
        if (state.analysisReady) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
                verticalArrangement = Arrangement.spacedBy(Spacing.xs)
            ) {
                quickPrompts.forEach { prompt ->
                    FilterChip(
                        selected = false,
                        onClick = { onDraftChange(prompt) },
                        label = {
                            Text(
                                text = prompt,
                                style = MaterialTheme.typography.labelMedium
                            )
                        },
                        colors = if (midnightGlass) aiFilterChipColors() else FilterChipDefaults.filterChipColors()
                    )
                }
            }
        }
        if (state.chatMessages.isEmpty()) {
            Text(
                text = stringResource(id = R.string.ai_analysis_chat_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                state.chatMessages.takeLast(10).forEach { message ->
                    AiChatMessageBubble(message = message)
                }
            }
        }
        if (state.chatPendingAttachments.isNotEmpty()) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
                verticalArrangement = Arrangement.spacedBy(Spacing.xs)
            ) {
                state.chatPendingAttachments.forEach { attachment ->
                    AiAttachmentChip(
                        attachment = attachment,
                        removable = true,
                        onRemove = { onRemoveAttachment(attachment.id) }
                    )
                }
            }
        }
        TextField(
            value = state.chatDraft,
            onValueChange = onDraftChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text(text = stringResource(id = R.string.ai_analysis_chat_input_label)) },
            placeholder = { Text(text = stringResource(id = R.string.ai_analysis_chat_input_placeholder)) },
            textStyle = MaterialTheme.typography.bodyLarge.copy(fontSize = 18.sp),
            enabled = !state.chatInProgress && !state.chatVoiceBusy,
            colors = if (midnightGlass) {
                TextFieldDefaults.colors(
                    focusedContainerColor = Color(0x33182947),
                    unfocusedContainerColor = Color(0x33182947),
                    focusedTextColor = Color(0xFFF8FAFC),
                    unfocusedTextColor = Color(0xFFF8FAFC),
                    focusedLabelColor = Color(0xFF8DB6FF),
                    unfocusedLabelColor = Color(0xFFB5C0D8),
                    focusedPlaceholderColor = Color(0x8093A5C3),
                    unfocusedPlaceholderColor = Color(0x8093A5C3),
                    focusedIndicatorColor = Color(0xFF4A82BF),
                    unfocusedIndicatorColor = Color(0x33FFFFFF),
                    cursorColor = Color(0xFF8DB6FF)
                )
            } else {
                TextFieldDefaults.colors()
            }
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedButton(
                onClick = onAttachImage,
                enabled = !state.chatInProgress && !state.chatVoiceBusy
            ) {
                Icon(
                    imageVector = Icons.Default.PhotoCamera,
                    contentDescription = null,
                    modifier = Modifier.padding(end = 4.dp)
                )
                Text(text = stringResource(id = R.string.ai_analysis_chat_attach_photo_short))
            }
            OutlinedButton(
                onClick = onAttachFile,
                enabled = !state.chatInProgress && !state.chatVoiceBusy
            ) {
                Icon(
                    imageVector = Icons.Default.AttachFile,
                    contentDescription = null,
                    modifier = Modifier.padding(end = 4.dp)
                )
                Text(text = stringResource(id = R.string.ai_analysis_chat_attach_file_short))
            }
            OutlinedButton(
                onClick = if (state.chatRecording) onStopVoiceRecording else onStartVoiceRecording,
                enabled = !state.chatInProgress && !state.chatVoiceBusy && state.analysisReady
            ) {
                Icon(
                    imageVector = if (state.chatRecording) Icons.Default.Stop else Icons.Default.Mic,
                    contentDescription = null,
                    modifier = Modifier.padding(end = 4.dp)
                )
                Text(
                    text = if (state.chatRecording) {
                        stringResource(id = R.string.ai_analysis_chat_stop_recording_short)
                    } else {
                        stringResource(id = R.string.ai_analysis_chat_start_recording_short)
                    }
                )
            }
            Spacer(modifier = Modifier.weight(1f))
            if (state.chatInProgress || state.chatVoiceBusy) {
                CircularProgressIndicator(
                    modifier = Modifier.height(18.dp),
                    strokeWidth = 2.dp
                )
            }
            OutlinedButton(
                onClick = onSend,
                enabled = state.analysisReady &&
                    !state.chatInProgress &&
                    !state.chatVoiceBusy &&
                    (state.chatDraft.isNotBlank() || state.chatPendingAttachments.isNotEmpty())
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Send,
                    contentDescription = null,
                    modifier = Modifier.padding(end = 4.dp)
                )
                Text(text = stringResource(id = R.string.ai_analysis_chat_send))
            }
        }
    }
}

@Composable
private fun AiChatMessageBubble(
    message: AiChatMessageUi
) {
    val isUser = message.role.equals("user", ignoreCase = true)
    val containerColor = if (isUser) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    val contentColor = if (isUser) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    val roleLabel = if (isUser) {
        stringResource(id = R.string.ai_analysis_chat_role_user)
    } else {
        stringResource(id = R.string.ai_analysis_chat_role_ai)
    }
    Card(
        colors = CardDefaults.cardColors(
            containerColor = containerColor,
            contentColor = contentColor
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.sm),
            verticalArrangement = Arrangement.spacedBy(Spacing.xs)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = roleLabel,
                    style = MaterialTheme.typography.labelLarge
                )
                if (message.voiceTranscript) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Mic,
                            contentDescription = null,
                            modifier = Modifier.padding(end = 4.dp)
                        )
                        Text(
                            text = stringResource(id = R.string.ai_analysis_chat_voice_transcript),
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
            }
            Text(
                text = message.text,
                style = MaterialTheme.typography.bodyLarge.copy(fontSize = 18.sp)
            )
            if (message.attachments.isNotEmpty()) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
                    verticalArrangement = Arrangement.spacedBy(Spacing.xs)
                ) {
                    message.attachments.forEach { attachment ->
                        AiAttachmentChip(
                            attachment = attachment,
                            removable = false,
                            onRemove = {}
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AiAttachmentChip(
    attachment: AiChatAttachmentUi,
    removable: Boolean,
    onRemove: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant
    ) {
        Row(
            modifier = Modifier.padding(horizontal = Spacing.sm, vertical = Spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.xs)
        ) {
            Icon(
                imageVector = when (attachment.kind.lowercase(Locale.US)) {
                    "image" -> Icons.Default.PhotoCamera
                    else -> Icons.Default.AttachFile
                },
                contentDescription = null
            )
            Column {
                Text(
                    text = attachment.name,
                    style = MaterialTheme.typography.labelMedium
                )
                val meta = listOfNotNull(attachment.previewLabel, attachment.sizeLabel).joinToString(" • ")
                if (meta.isNotBlank()) {
                    Text(
                        text = meta,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (removable) {
                IconButton(onClick = onRemove) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = stringResource(id = R.string.ai_analysis_chat_remove_attachment)
                    )
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
    val midnightGlass = LocalUiStyle.current == UiStyle.MIDNIGHT_GLASS
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
        verticalArrangement = Arrangement.spacedBy(Spacing.xs)
    ) {
        options.forEach { option ->
            FilterChip(
                selected = selected == option,
                onClick = { onSelected(option) },
                colors = if (midnightGlass) aiFilterChipColors() else FilterChipDefaults.filterChipColors(),
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
    AiSectionCard {
        AiSectionLabel(
            text = stringResource(id = R.string.section_ai_tuning_status),
            infoText = stringResource(id = R.string.ai_tuning_status_info)
        )
        Surface(
            shape = RoundedCornerShape(12.dp),
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
        Text(
            text = stringResource(id = R.string.ai_tuning_reason_line, status.reason),
            style = MaterialTheme.typography.bodySmall
        )
        status.generatedTs?.let { ts ->
            Text(
                text = stringResource(id = R.string.ai_tuning_generated_line, formatTs(ts)),
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

@Composable
private fun IntFilterChipGroup(
    selected: Int,
    options: List<Int>,
    formatter: @Composable (Int) -> String,
    onSelected: (Int) -> Unit
) {
    val midnightGlass = LocalUiStyle.current == UiStyle.MIDNIGHT_GLASS
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
        verticalArrangement = Arrangement.spacedBy(Spacing.xs)
    ) {
        options.forEach { value ->
            FilterChip(
                selected = selected == value,
                onClick = { onSelected(value) },
                colors = if (midnightGlass) aiFilterChipColors() else FilterChipDefaults.filterChipColors(),
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
    val midnightGlass = LocalUiStyle.current == UiStyle.MIDNIGHT_GLASS
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
                colors = if (midnightGlass) aiFilterChipColors() else FilterChipDefaults.filterChipColors(),
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
    val midnightGlass = LocalUiStyle.current == UiStyle.MIDNIGHT_GLASS
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
        verticalArrangement = Arrangement.spacedBy(Spacing.xs)
    ) {
        options.forEach { value ->
            FilterChip(
                selected = selected.equals(value, ignoreCase = true),
                onClick = { onSelected(value) },
                colors = if (midnightGlass) aiFilterChipColors() else FilterChipDefaults.filterChipColors(),
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
    val midnightGlass = LocalUiStyle.current == UiStyle.MIDNIGHT_GLASS
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = if (midnightGlass) RoundedCornerShape(28.dp) else AiSectionShape,
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
private fun AiSectionLabel(
    text: String,
    infoText: String
) {
    val midnightGlass = LocalUiStyle.current == UiStyle.MIDNIGHT_GLASS
    var showInfo by rememberSaveable(text) { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = text.uppercase(Locale.US),
            style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.7.sp),
            color = if (midnightGlass) Color(0xFFD0D7E8) else MaterialTheme.colorScheme.onSurfaceVariant
        )
        Surface(
            shape = RoundedCornerShape(999.dp),
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

@Composable
private fun aiFilterChipColors() = FilterChipDefaults.filterChipColors(
    selectedContainerColor = Color(0x221D4ED8),
    selectedLabelColor = Color(0xFF8DB6FF),
    selectedLeadingIconColor = Color(0xFF8DB6FF),
    containerColor = Color(0xAA101D38),
    labelColor = Color(0xFFB5C0D8),
    iconColor = Color(0xFFB5C0D8)
)

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
