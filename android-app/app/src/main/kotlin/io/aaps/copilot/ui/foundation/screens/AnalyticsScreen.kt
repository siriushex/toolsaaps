package io.aaps.copilot.ui.foundation.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.aaps.copilot.config.UiStyle
import io.aaps.copilot.R
import io.aaps.copilot.ui.IsfCrHistoryPointUi
import io.aaps.copilot.ui.IsfCrOverlayPointUi
import io.aaps.copilot.ui.IsfCrHistoryResolver
import io.aaps.copilot.ui.IsfCrHistoryWindow
import io.aaps.copilot.ui.foundation.design.AppElevation
import io.aaps.copilot.ui.foundation.design.Spacing
import io.aaps.copilot.ui.foundation.format.UiFormatters
import io.aaps.copilot.ui.foundation.theme.AapsCopilotTheme
import io.aaps.copilot.ui.foundation.theme.LocalUiStyle
import io.aaps.copilot.ui.foundation.theme.LocalNumericTypography
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private enum class AnalyticsTab {
    QUALITY,
    ISF_CR
}

private enum class IsfCrChartScale(val rangeMultiplier: Double) {
    AUTO(1.0),
    ZOOM_IN(0.7),
    ZOOM_OUT(1.6)
}

private enum class SensorLagTimelineKind {
    MODE,
    BUCKET
}

private data class IsfCrSeriesPoint(
    val ts: Long,
    val evidence: Double?,
    val fallback: Double?,
    val aaps: Double?
)

private data class IsfCrOverlaySeriesPoint(
    val ts: Long,
    val cob: Double?,
    val uam: Double?,
    val activity: Double?
)

private data class AnalyticsOverlayScale(
    val min: Double,
    val max: Double,
    val latest: Double?
)

private data class InsulinProfileActivityPoint(
    val minute: Double,
    val per5mShare: Double,
    val level: Double
)

private val AnalyticsSectionShape = RoundedCornerShape(18.dp)
private val AnalyticsInfoShape = RoundedCornerShape(12.dp)

@Composable
fun AnalyticsScreen(
    state: AnalyticsUiState,
    onRunDailyAnalysis: () -> Unit = {},
    onInsulinProfileActivate: (String) -> Unit = {},
    onSensorLagCorrectionModeChange: (String) -> Unit = {},
    openSensorLagTrendDetailRequest: Boolean = false,
    onSensorLagTrendDetailHandled: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    var selectedTabRaw by rememberSaveable { mutableStateOf(AnalyticsTab.ISF_CR.name) }
    var selectedWindowRaw by rememberSaveable { mutableStateOf(IsfCrHistoryWindow.LAST_24H.name) }
    var selectedCircadianWindowRaw by rememberSaveable { mutableStateOf(CircadianPatternWindowUi.DAYS_14.name) }
    var showSensorLagTrendDialog by rememberSaveable { mutableStateOf(false) }
    val selectedTab = remember(selectedTabRaw) {
        runCatching { AnalyticsTab.valueOf(selectedTabRaw) }.getOrDefault(AnalyticsTab.ISF_CR)
    }
    val selectedWindow = remember(selectedWindowRaw) {
        runCatching { IsfCrHistoryWindow.valueOf(selectedWindowRaw) }.getOrDefault(IsfCrHistoryWindow.LAST_24H)
    }
    val selectedCircadianWindow = remember(selectedCircadianWindowRaw) {
        runCatching { CircadianPatternWindowUi.valueOf(selectedCircadianWindowRaw) }
            .getOrDefault(CircadianPatternWindowUi.DAYS_14)
    }
    val anchorTs = state.historyLastUpdatedTs ?: System.currentTimeMillis()
    val historyPoints = remember(
        state.historyPoints,
        state.historyLastUpdatedTs,
        selectedWindow
    ) {
        IsfCrHistoryResolver.resolve(
            points = state.historyPoints,
            nowTs = anchorTs,
            window = selectedWindow,
            maxPoints = 420
        )
    }
    val historyOverlayPoints = remember(
        state.historyOverlayPoints,
        historyPoints
    ) {
        alignOverlayPoints(
            source = state.historyOverlayPoints,
            visiblePoints = historyPoints
        )
    }

    LaunchedEffect(openSensorLagTrendDetailRequest) {
        if (openSensorLagTrendDetailRequest) {
            selectedTabRaw = AnalyticsTab.QUALITY.name
            showSensorLagTrendDialog = true
            onSensorLagTrendDetailHandled()
        }
    }

    ScreenStateLayout(
        loadState = state.loadState,
        isStale = state.isStale,
        errorText = state.errorText,
        emptyText = stringResource(id = R.string.analytics_empty)
    ) {
        LazyColumn(
            modifier = modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            item {
                AnalyticsTabCard(
                    selectedTab = selectedTab,
                    onTabSelected = { selectedTabRaw = it.name }
                )
            }

            when (selectedTab) {
                AnalyticsTab.QUALITY -> {
                    item {
                        AnalyticsSectionCard {
                            OutlinedButton(onClick = onRunDailyAnalysis) {
                                Text(text = stringResource(id = R.string.analytics_run_daily_analysis))
                            }
                        }
                    }
                    state.sensorLagDiagnostics?.let { diagnostics ->
                        item {
                            SensorLagDiagnosticsCard(
                                diagnostics = diagnostics,
                                replayBuckets = state.dailyReportSensorLagReplayBuckets,
                                shadowBuckets = state.dailyReportSensorLagShadowBuckets,
                                showTrendDialog = showSensorLagTrendDialog,
                                onShowTrendDialogChange = { showSensorLagTrendDialog = it },
                                onSensorLagCorrectionModeChange = onSensorLagCorrectionModeChange
                            )
                        }
                    }
                    if (
                        state.dailyReportGeneratedAtTs != null ||
                        state.dailyReportHorizonStats.isNotEmpty() ||
                        state.dailyReportMatchedSamples != null
                    ) {
                        item {
                            DailyForecastReportCard(state = state)
                        }
                    }
                    state.circadianReplaySummary?.let { summary ->
                        item {
                            AnalyticsSectionCard {
                                AnalyticsSectionLabel(text = stringResource(id = R.string.analytics_circadian_replay_title))
                                Text(
                                    text = stringResource(id = R.string.analytics_circadian_replay_info),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                CircadianReplaySummaryContent(
                                    summary = summary,
                                    emptyText = stringResource(id = R.string.analytics_empty)
                                )
                            }
                        }
                    }
                    item {
                        RollingKpiCard(lines = state.rollingReportLines)
                    }
                    item {
                        ActivationGateCard(lines = state.activationGateLines)
                    }
                    item {
                        DroppedReasonsSummaryCard(
                            lines24h = state.droppedReasons24hLines,
                            lines7d = state.droppedReasons7dLines
                        )
                    }
                    item {
                        WearImpactSummaryCard(
                            lines24h = state.wearImpact24hLines,
                            lines7d = state.wearImpact7dLines
                        )
                    }
                    item {
                        AnalyticsSectionCard {
                            AnalyticsSectionLabel(text = stringResource(id = R.string.section_analytics_quality))
                            if (state.qualityLines.isEmpty()) {
                                Text(
                                    text = stringResource(id = R.string.analytics_empty),
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                        }
                    }
                    items(state.qualityLines) { line ->
                        AnalyticsLineCard(line = line)
                    }
                    item {
                        AnalyticsSectionCard {
                            AnalyticsSectionLabel(text = stringResource(id = R.string.section_analytics_baseline))
                            if (state.baselineDeltaLines.isEmpty()) {
                                Text(
                                    text = stringResource(id = R.string.analytics_empty),
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                        }
                    }
                    items(state.baselineDeltaLines) { line ->
                        AnalyticsLineCard(line = line)
                    }
                }

                AnalyticsTab.ISF_CR -> {
                    item {
                        IsfCrOverviewCard(
                            state = state,
                            selectedWindow = selectedWindow,
                            shownPoints = historyPoints.size,
                            totalPoints = state.historyPoints.size,
                            visibleStartTs = historyPoints.firstOrNull()?.timestamp,
                            visibleEndTs = historyPoints.lastOrNull()?.timestamp,
                            onWindowSelected = { selectedWindowRaw = it.name }
                        )
                    }
                    item {
                        IsfCrRealtimeCard(state = state)
                    }
                    item {
                        IsfCrRuntimeDiagnosticsCard(diagnostics = state.runtimeDiagnostics)
                    }
                    item {
                        InsulinProfilesCard(
                            state = state,
                            onActivateProfile = onInsulinProfileActivate
                        )
                    }
                    item {
                        IsfCrSeriesCard(
                            chartKey = "isf",
                            title = stringResource(id = R.string.analytics_chart_isf),
                            unit = stringResource(id = R.string.unit_mmol_l) + "/U",
                            points = historyPoints,
                            overlayPoints = historyOverlayPoints,
                            evidenceColor = Color(0xFF0A5FBF),
                            fallbackColor = Color(0xFF8B5CF6),
                            aapsColor = Color(0xFFCC6A00),
                            cobColor = Color(0xFF0F766E),
                            uamColor = Color(0xFFD946EF),
                            activityColor = Color(0xFF2563EB),
                            evidenceSelector = { it.isfEvidenceStrict },
                            fallbackSelector = { it.isfFallbackRuntimeStrict },
                            aapsSelector = { it.isfAapsStrict }
                        )
                    }
                    item {
                        IsfCrSeriesCard(
                            chartKey = "cr",
                            title = stringResource(id = R.string.analytics_chart_cr),
                            unit = stringResource(id = R.string.unit_g) + "/U",
                            points = historyPoints,
                            overlayPoints = historyOverlayPoints,
                            evidenceColor = Color(0xFF00796B),
                            fallbackColor = Color(0xFF8B5CF6),
                            aapsColor = Color(0xFFCC6A00),
                            cobColor = Color(0xFF0F766E),
                            uamColor = Color(0xFFD946EF),
                            activityColor = Color(0xFF2563EB),
                            evidenceSelector = { it.crEvidenceStrict },
                            fallbackSelector = { it.crFallbackRuntimeStrict },
                            aapsSelector = { it.crAapsStrict }
                        )
                    }
                    item {
                        CircadianPatternsCard(
                            state = state,
                            selectedWindow = selectedCircadianWindow,
                            onWindowSelected = { selectedCircadianWindowRaw = it.name }
                        )
                    }
                    if (state.deepLines.isNotEmpty()) {
                        item {
                            AnalyticsSectionCard {
                                AnalyticsSectionLabel(text = stringResource(id = R.string.analytics_deep_diagnostics))
                                state.deepLines.take(10).forEach { line ->
                                    Surface(
                                        shape = AnalyticsInfoShape,
                                        color = MaterialTheme.colorScheme.surfaceVariant,
                                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                                    ) {
                                        Text(
                                            text = line,
                                            style = MaterialTheme.typography.bodySmall,
                                            modifier = Modifier.padding(Spacing.xs)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DroppedReasonsSummaryCard(
    lines24h: List<String>,
    lines7d: List<String>
) {
    AnalyticsSectionCard {
        AnalyticsSectionLabel(text = stringResource(id = R.string.section_analytics_dropped_reasons))
        if (lines24h.isEmpty() && lines7d.isEmpty()) {
            Text(
                text = stringResource(id = R.string.analytics_dropped_reasons_empty),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            return@AnalyticsSectionCard
        }
        if (lines24h.isNotEmpty()) {
            AnalyticsSectionLabel(text = stringResource(id = R.string.analytics_dropped_reasons_24h))
            lines24h.forEach { line ->
                Text(
                    text = line,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        if (lines7d.isNotEmpty()) {
            AnalyticsSectionLabel(text = stringResource(id = R.string.analytics_dropped_reasons_7d))
            lines7d.forEach { line ->
                Text(
                    text = line,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun RollingKpiCard(lines: List<String>) {
    AnalyticsSectionCard {
        AnalyticsSectionLabel(text = stringResource(id = R.string.section_analytics_rolling_kpi))
        if (lines.isEmpty()) {
            Text(
                text = stringResource(id = R.string.analytics_rolling_kpi_empty),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            return@AnalyticsSectionCard
        }
        lines.forEach { line ->
            Surface(
                shape = AnalyticsInfoShape,
                color = MaterialTheme.colorScheme.surfaceVariant,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                Text(
                    text = line,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(Spacing.xs)
                )
            }
        }
    }
}

@Composable
private fun IsfCrRealtimeCard(
    state: AnalyticsUiState
) {
    AnalyticsSectionCard {
        AnalyticsSectionLabel(text = stringResource(id = R.string.section_analytics_isfcr_realtime))
        val mode = state.realtimeMode ?: "N/A"
        Text(
            text = stringResource(
                id = R.string.analytics_isfcr_realtime_status_template,
                mode,
                UiFormatters.formatPercent(state.realtimeConfidence),
                UiFormatters.formatPercent(state.realtimeQualityScore)
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Spacing.xs)
        ) {
            AnalyticsMetricTile(
                title = stringResource(id = R.string.analytics_isf_eff),
                value = state.realtimeIsfEff,
                unit = stringResource(id = R.string.unit_mmol_l) + "/U",
                modifier = Modifier.weight(1f)
            )
            AnalyticsMetricTile(
                title = stringResource(id = R.string.analytics_cr_eff),
                value = state.realtimeCrEff,
                unit = stringResource(id = R.string.unit_g) + "/U",
                modifier = Modifier.weight(1f)
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Spacing.xs)
        ) {
            AnalyticsMetricTile(
                title = stringResource(id = R.string.analytics_isf_base),
                value = state.realtimeIsfBase,
                unit = stringResource(id = R.string.unit_mmol_l) + "/U",
                iconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            AnalyticsMetricTile(
                title = stringResource(id = R.string.analytics_cr_base),
                value = state.realtimeCrBase,
                unit = stringResource(id = R.string.unit_g) + "/U",
                iconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
        }
        Text(
            text = stringResource(
                id = R.string.analytics_isfcr_realtime_ci_template,
                UiFormatters.formatMmol(state.realtimeCiIsfLow, decimals = 2),
                UiFormatters.formatMmol(state.realtimeCiIsfHigh, decimals = 2),
                UiFormatters.formatDecimalOrPlaceholder(state.realtimeCiCrLow, decimals = 2),
                UiFormatters.formatDecimalOrPlaceholder(state.realtimeCiCrHigh, decimals = 2)
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (state.activeTagLines.isNotEmpty()) {
            AnalyticsSectionLabel(text = stringResource(id = R.string.analytics_isfcr_active_tags))
            state.activeTagLines.take(4).forEach { line ->
                Text(
                    text = line,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        if (state.realtimeFactorLines.isNotEmpty()) {
            AnalyticsSectionLabel(text = stringResource(id = R.string.analytics_isfcr_factors))
            state.realtimeFactorLines.take(8).forEach { line ->
                Text(
                    text = line,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun WearImpactSummaryCard(
    lines24h: List<String>,
    lines7d: List<String>
) {
    AnalyticsSectionCard {
        AnalyticsSectionLabel(text = stringResource(id = R.string.section_analytics_wear_impact))
        if (lines24h.isEmpty() && lines7d.isEmpty()) {
            Text(
                text = stringResource(id = R.string.analytics_wear_impact_empty),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            return@AnalyticsSectionCard
        }
        if (lines24h.isNotEmpty()) {
            AnalyticsSectionLabel(text = stringResource(id = R.string.analytics_wear_impact_24h))
            lines24h.forEach { line ->
                Text(
                    text = line,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        if (lines7d.isNotEmpty()) {
            AnalyticsSectionLabel(text = stringResource(id = R.string.analytics_wear_impact_7d))
            lines7d.forEach { line ->
                Text(
                    text = line,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun IsfCrRuntimeDiagnosticsCard(
    diagnostics: IsfCrRuntimeDiagnosticsUi?
) {
    AnalyticsSectionCard {
        AnalyticsSectionLabel(text = stringResource(id = R.string.section_analytics_runtime_diagnostics))
        if (diagnostics == null) {
            Text(
                text = stringResource(id = R.string.analytics_runtime_diag_empty),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            return@AnalyticsSectionCard
        }

        val mode = diagnostics.mode ?: "--"
        Text(
            text = stringResource(
                id = R.string.analytics_runtime_diag_realtime_line,
                formatHistoryTs(diagnostics.ts),
                mode
            ),
            style = MaterialTheme.typography.bodySmall
        )
        Text(
            text = stringResource(
                id = R.string.analytics_runtime_diag_confidence_line,
                UiFormatters.formatPercent(diagnostics.confidence),
                UiFormatters.formatPercent(diagnostics.confidenceThreshold),
                UiFormatters.formatPercent(diagnostics.qualityScore)
            ),
            style = MaterialTheme.typography.bodySmall
        )
        Text(
            text = stringResource(
                id = R.string.analytics_runtime_diag_evidence_line,
                diagnostics.usedEvidence?.toString() ?: "--",
                diagnostics.droppedEvidence?.toString() ?: "--",
                diagnostics.coverageHoursIsf?.toString() ?: "--",
                diagnostics.coverageHoursCr?.toString() ?: "--"
            ),
            style = MaterialTheme.typography.bodySmall
        )
        if (
            diagnostics.isfBaseSource != null ||
            diagnostics.crBaseSource != null ||
            diagnostics.isfDayTypeBaseAvailable != null ||
            diagnostics.crDayTypeBaseAvailable != null
        ) {
            Text(
                text = stringResource(
                    id = R.string.analytics_runtime_diag_base_source_line,
                    diagnostics.isfBaseSource ?: "--",
                    diagnostics.crBaseSource ?: "--",
                    diagnostics.isfDayTypeBaseAvailable?.toString() ?: "--",
                    diagnostics.crDayTypeBaseAvailable?.toString() ?: "--"
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (
            diagnostics.currentDayType != null ||
            diagnostics.hourWindowIsfSameDayType != null ||
            diagnostics.hourWindowCrSameDayType != null
        ) {
            Text(
                text = stringResource(
                    id = R.string.analytics_runtime_diag_day_type_line,
                    diagnostics.currentDayType ?: "--",
                    diagnostics.hourWindowIsfSameDayType?.toString() ?: "--",
                    diagnostics.hourWindowCrSameDayType?.toString() ?: "--"
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (
            diagnostics.hourWindowIsfEvidence != null ||
            diagnostics.hourWindowCrEvidence != null ||
            diagnostics.minIsfEvidencePerHour != null ||
            diagnostics.minCrEvidencePerHour != null
        ) {
            Text(
                text = stringResource(
                    id = R.string.analytics_runtime_diag_hour_window_line,
                    diagnostics.hourWindowIsfEvidence?.toString() ?: "--",
                    diagnostics.hourWindowCrEvidence?.toString() ?: "--",
                    diagnostics.minIsfEvidencePerHour?.toString() ?: "--",
                    diagnostics.minCrEvidencePerHour?.toString() ?: "--"
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (
            diagnostics.crMaxGapMinutes != null ||
            diagnostics.crMaxSensorBlockedRatePct != null ||
            diagnostics.crMaxUamAmbiguityRatePct != null
        ) {
            Text(
                text = stringResource(
                    id = R.string.analytics_runtime_diag_cr_gate_line,
                    diagnostics.crMaxGapMinutes?.roundToInt()?.toString() ?: "--",
                    diagnostics.crMaxSensorBlockedRatePct?.roundToInt()?.toString() ?: "--",
                    diagnostics.crMaxUamAmbiguityRatePct?.roundToInt()?.toString() ?: "--"
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        diagnostics.droppedReasons
            ?.takeIf { it.isNotBlank() }
            ?.let { droppedReasons ->
                Text(
                    text = stringResource(
                        id = R.string.analytics_runtime_diag_dropped_reasons_line,
                        droppedReasons
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        if (diagnostics.droppedReasonCodes.isNotEmpty()) {
            ReasonCodesRow(
                title = stringResource(id = R.string.analytics_runtime_diag_dropped_codes),
                reasons = diagnostics.droppedReasonCodes
            )
        }
        diagnostics.reasons
            ?.takeIf { it.isNotBlank() }
            ?.let { reasons ->
                Text(
                    text = stringResource(
                        id = R.string.analytics_runtime_diag_reasons_line,
                        reasons
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        if (diagnostics.reasonCodes.isNotEmpty()) {
            ReasonCodesRow(
                title = stringResource(id = R.string.analytics_runtime_diag_runtime_codes),
                reasons = diagnostics.reasonCodes
            )
        }
        if (diagnostics.lowConfidenceTs != null || !diagnostics.lowConfidenceReasons.isNullOrBlank()) {
            Text(
                text = stringResource(
                    id = R.string.analytics_runtime_diag_low_conf_line,
                    formatHistoryTs(diagnostics.lowConfidenceTs),
                    diagnostics.lowConfidenceReasons ?: "--"
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (diagnostics.lowConfidenceReasonCodes.isNotEmpty()) {
            ReasonCodesRow(
                title = stringResource(id = R.string.analytics_runtime_diag_low_conf_codes),
                reasons = diagnostics.lowConfidenceReasonCodes
            )
        }
        if (diagnostics.fallbackTs != null || !diagnostics.fallbackReasons.isNullOrBlank()) {
            Text(
                text = stringResource(
                    id = R.string.analytics_runtime_diag_fallback_line,
                    formatHistoryTs(diagnostics.fallbackTs),
                    diagnostics.fallbackReasons ?: "--"
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (diagnostics.fallbackReasonCodes.isNotEmpty()) {
            ReasonCodesRow(
                title = stringResource(id = R.string.analytics_runtime_diag_fallback_codes),
                reasons = diagnostics.fallbackReasonCodes
            )
        }
    }
}

@Composable
private fun ReasonCodesRow(
    title: String,
    reasons: List<String>
) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(Spacing.xxs),
        verticalArrangement = Arrangement.spacedBy(Spacing.xxs)
    ) {
        reasons.forEach { reason ->
            val reasonLabel = when (reason.lowercase(Locale.US)) {
                "model_state_missing" -> stringResource(id = R.string.analytics_reason_model_state_missing)
                "isf_evidence_sparse" -> stringResource(id = R.string.analytics_reason_isf_evidence_sparse)
                "cr_evidence_sparse" -> stringResource(id = R.string.analytics_reason_cr_evidence_sparse)
                "isf_hourly_evidence_below_min" -> stringResource(id = R.string.analytics_reason_isf_hourly_evidence_below_min)
                "cr_hourly_evidence_below_min" -> stringResource(id = R.string.analytics_reason_cr_hourly_evidence_below_min)
                "isf_day_type_evidence_sparse" -> stringResource(id = R.string.analytics_reason_isf_day_type_evidence_sparse)
                "cr_day_type_evidence_sparse" -> stringResource(id = R.string.analytics_reason_cr_day_type_evidence_sparse)
                "isf_day_type_base_missing" -> stringResource(id = R.string.analytics_reason_isf_day_type_base_missing)
                "cr_day_type_base_missing" -> stringResource(id = R.string.analytics_reason_cr_day_type_base_missing)
                "set_age_high" -> stringResource(id = R.string.analytics_reason_set_age_high)
                "sensor_age_high" -> stringResource(id = R.string.analytics_reason_sensor_age_high)
                "context_ambiguity_high" -> stringResource(id = R.string.analytics_reason_context_ambiguity_high)
                "low_confidence_fallback" -> stringResource(id = R.string.analytics_reason_low_confidence_fallback)
                "isf_missing_units" -> stringResource(id = R.string.analytics_reason_isf_missing_units)
                "isf_small_units" -> stringResource(id = R.string.analytics_reason_isf_small_units)
                "isf_carbs_around" -> stringResource(id = R.string.analytics_reason_isf_carbs_around)
                "isf_missing_baseline" -> stringResource(id = R.string.analytics_reason_isf_missing_baseline)
                "isf_missing_future" -> stringResource(id = R.string.analytics_reason_isf_missing_future)
                "isf_non_positive_drop" -> stringResource(id = R.string.analytics_reason_isf_non_positive_drop)
                "isf_out_of_range" -> stringResource(id = R.string.analytics_reason_isf_out_of_range)
                "isf_low_quality" -> stringResource(id = R.string.analytics_reason_isf_low_quality)
                "cr_missing_carbs" -> stringResource(id = R.string.analytics_reason_cr_missing_carbs)
                "cr_small_carbs" -> stringResource(id = R.string.analytics_reason_cr_small_carbs)
                "cr_no_bolus_nearby" -> stringResource(id = R.string.analytics_reason_cr_no_bolus_nearby)
                "cr_sparse_points" -> stringResource(id = R.string.analytics_reason_cr_sparse_points)
                "cr_low_quality" -> stringResource(id = R.string.analytics_reason_cr_low_quality)
                "cr_sparse_intervals" -> stringResource(id = R.string.analytics_reason_cr_sparse_intervals)
                "cr_fit_invalid" -> stringResource(id = R.string.analytics_reason_cr_fit_invalid)
                else -> reason
            }
            Surface(
                shape = AnalyticsInfoShape,
                color = MaterialTheme.colorScheme.surfaceVariant,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                Text(
                    text = reasonLabel,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(horizontal = Spacing.xs, vertical = Spacing.xxs)
                )
            }
        }
    }
}

@Composable
private fun InsulinProfilesCard(
    state: AnalyticsUiState,
    onActivateProfile: (String) -> Unit = {}
) {
    val curves = remember(state.insulinProfileCurves) {
        state.insulinProfileCurves
            .map { profile ->
                profile.copy(points = profile.points.sortedBy { it.minute })
            }
            .filter { it.points.size >= 2 }
    }
    if (curves.isEmpty()) return
    val activeProfile = curves.firstOrNull { it.isSelected } ?: curves.first()
    val curvesKey = remember(curves) { curves.joinToString(separator = "|") { it.id } }
    var previewProfileId by rememberSaveable(activeProfile.id, curvesKey) { mutableStateOf(activeProfile.id) }
    val previewProfile = curves.firstOrNull { it.id == previewProfileId } ?: activeProfile
    val chartProfiles = if (previewProfile.id == activeProfile.id) {
        listOf(previewProfile)
    } else {
        listOf(previewProfile, activeProfile)
    }
    val rawActivitySeries = remember(chartProfiles) {
        chartProfiles.associate { profile ->
            profile.id to buildInsulinActivityPoints(profile.points)
        }
    }
    val globalPeakPer5m = remember(rawActivitySeries) {
        max(
            1e-6,
            rawActivitySeries.values
                .flatMap { it }
                .maxOfOrNull { it.per5mShare } ?: 1e-6
        )
    }
    val activitySeries = remember(rawActivitySeries, globalPeakPer5m) {
        rawActivitySeries.mapValues { (_, points) ->
            points.map { point ->
                point.copy(level = (point.per5mShare / globalPeakPer5m).coerceIn(0.0, 1.0))
            }
        }
    }
    val realCurveSorted = remember(state.insulinRealProfileCurvePoints) {
        state.insulinRealProfileCurvePoints.sortedBy { it.minute }
    }
    val realActivityRaw = remember(realCurveSorted) {
        buildInsulinActivityPoints(realCurveSorted)
    }
    val realActivitySeries = remember(realActivityRaw, globalPeakPer5m) {
        realActivityRaw.map { point ->
            point.copy(level = (point.per5mShare / globalPeakPer5m).coerceIn(0.0, 1.0))
        }
    }
    val showRealOverlay = state.insulinRealProfileAvailable &&
        state.selectedInsulinProfileId == activeProfile.id &&
        realActivitySeries.size >= 2
    val previewActivity = activitySeries[previewProfile.id].orEmpty()
    val previewPeak = previewActivity.maxByOrNull { it.per5mShare }
    val previewPeakPercentPer5m = previewPeak?.per5mShare?.times(100.0)
    val level30 = activityPercentAtMinute(previewActivity, minute = 30.0)
    val level60 = activityPercentAtMinute(previewActivity, minute = 60.0)
    val level120 = activityPercentAtMinute(previewActivity, minute = 120.0)

    val palette = listOf(
        Color(0xFF0A5FBF),
        Color(0xFF00796B),
        Color(0xFFB23A48),
        Color(0xFF7B1FA2),
        Color(0xFFED6C02)
    )
    val colorByProfile = remember(curves) {
        curves.mapIndexed { index, curve ->
            curve.id to palette[index % palette.size]
        }.toMap()
    }
    val gridMajor = MaterialTheme.colorScheme.outline.copy(alpha = 0.16f)
    val gridMinor = MaterialTheme.colorScheme.outline.copy(alpha = 0.10f)
    val axisColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
    val maxMinute = max(
        1.0,
        curves.maxOfOrNull { profile -> profile.points.maxOfOrNull { it.minute } ?: 0.0 } ?: 1.0
    )
    val chartDescription = stringResource(
        id = R.string.analytics_insulin_profiles_accessibility,
        previewProfile.id,
        UiFormatters.formatDecimalOrPlaceholder(maxMinute, decimals = 0)
    )

    AnalyticsSectionCard {
        AnalyticsSectionLabel(text = stringResource(id = R.string.section_analytics_insulin_profiles))
        Text(
            text = stringResource(
                id = R.string.analytics_insulin_profiles_active_template,
                activeProfile.id,
                activeProfile.label
            ),
            style = MaterialTheme.typography.titleSmall
        )
        Text(
            text = stringResource(
                id = R.string.analytics_insulin_profiles_preview_template,
                previewProfile.id,
                previewProfile.label
            ),
            style = MaterialTheme.typography.titleSmall
        )
        Text(
            text = stringResource(id = R.string.analytics_insulin_profiles_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (state.insulinRealProfileAvailable) {
            Text(
                text = stringResource(
                    id = R.string.analytics_insulin_profiles_real_daily_line,
                    formatHistoryTs(state.insulinRealProfileUpdatedTs),
                    UiFormatters.formatPercent(state.insulinRealProfileConfidence),
                    state.insulinRealProfileSamples ?: 0,
                    state.insulinRealProfileStatus ?: "estimated_daily"
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                text = stringResource(
                    id = R.string.analytics_insulin_profiles_real_daily_shape,
                    UiFormatters.formatDecimalOrPlaceholder(state.insulinRealProfileOnsetMinutes, decimals = 0),
                    UiFormatters.formatDecimalOrPlaceholder(state.insulinRealProfilePeakMinutes, decimals = 0),
                    UiFormatters.formatDecimalOrPlaceholder(state.insulinRealProfileScale, decimals = 2)
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = stringResource(id = R.string.analytics_insulin_profiles_real_overlay_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            Text(
                text = stringResource(id = R.string.analytics_insulin_profiles_real_daily_unavailable),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(220.dp)
                .semantics {
                    contentDescription = chartDescription
                }
        ) {
            val left = 36.dp.toPx()
            val right = 12.dp.toPx()
            val top = 10.dp.toPx()
            val bottom = 22.dp.toPx()
            val chartWidth = (size.width - left - right).coerceAtLeast(1f)
            val chartHeight = (size.height - top - bottom).coerceAtLeast(1f)

            fun xOf(minute: Double): Float {
                val ratio = (minute / maxMinute).coerceIn(0.0, 1.0)
                return left + (ratio * chartWidth).toFloat()
            }

            fun yOf(level: Double): Float {
                val ratio = level.coerceIn(0.0, 1.0)
                return (top + chartHeight - ratio * chartHeight).toFloat()
            }

            repeat(5) { index ->
                val y = top + chartHeight * index / 4f
                drawLine(
                    color = gridMajor,
                    start = Offset(left, y),
                    end = Offset(left + chartWidth, y),
                    strokeWidth = 1f
                )
            }
            repeat(7) { index ->
                val x = left + chartWidth * index / 6f
                drawLine(
                    color = gridMinor,
                    start = Offset(x, top),
                    end = Offset(x, top + chartHeight),
                    strokeWidth = 1f
                )
            }

            drawLine(
                color = axisColor,
                start = Offset(left, top),
                end = Offset(left, top + chartHeight),
                strokeWidth = 1.2f
            )
            drawLine(
                color = axisColor,
                start = Offset(left, top + chartHeight),
                end = Offset(left + chartWidth, top + chartHeight),
                strokeWidth = 1.2f
            )

            chartProfiles.forEachIndexed { idx, profile ->
                val color = colorByProfile[profile.id] ?: palette[idx % palette.size]
                val points = activitySeries[profile.id].orEmpty()
                if (points.size < 2) return@forEachIndexed
                val path = Path()
                points.forEachIndexed { pointIdx, point ->
                    val x = xOf(point.minute)
                    val y = yOf(point.level)
                    if (pointIdx == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                val isPreview = profile.id == previewProfile.id
                val selectedWidth = if (isPreview) 4.2f else 2.2f
                val selectedAlpha = if (isPreview) 1.0f else 0.58f
                drawPath(
                    path = path,
                    color = color.copy(alpha = selectedAlpha),
                    style = Stroke(
                        width = selectedWidth,
                        cap = StrokeCap.Round,
                        pathEffect = if (isPreview) null else PathEffect.dashPathEffect(floatArrayOf(12f, 8f), 0f)
                    )
                )
                val last = points.last()
                drawCircle(
                    color = color.copy(alpha = selectedAlpha),
                    radius = if (isPreview) 5.0f else 3.5f,
                    center = Offset(xOf(last.minute), yOf(last.level))
                )
            }

            if (showRealOverlay) {
                val overlayColor = colorByProfile[activeProfile.id] ?: palette.first()
                val overlayPath = Path()
                realActivitySeries.forEachIndexed { index, point ->
                    val x = xOf(point.minute)
                    val y = yOf(point.level)
                    if (index == 0) overlayPath.moveTo(x, y) else overlayPath.lineTo(x, y)
                }
                drawPath(
                    path = overlayPath,
                    color = overlayColor.copy(alpha = 0.95f),
                    style = Stroke(
                        width = 3.0f,
                        cap = StrokeCap.Round,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 7f), 0f)
                    )
                )
                val tail = realActivitySeries.last()
                drawCircle(
                    color = overlayColor.copy(alpha = 0.95f),
                    radius = 4.2f,
                    center = Offset(xOf(tail.minute), yOf(tail.level))
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            listOf(0.0, maxMinute * 0.25, maxMinute * 0.5, maxMinute * 0.75, maxMinute).forEach { tick ->
                Text(
                    text = formatDurationTick(tick),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Text(
            text = stringResource(id = R.string.analytics_insulin_profiles_axis_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Spacing.xs)
        ) {
            AnalyticsMetricTile(
                title = stringResource(id = R.string.analytics_insulin_profiles_level_30m),
                value = level30,
                unit = "%/5m",
                iconColor = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f)
            )
            AnalyticsMetricTile(
                title = stringResource(id = R.string.analytics_insulin_profiles_level_60m),
                value = level60,
                unit = "%/5m",
                iconColor = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f)
            )
            AnalyticsMetricTile(
                title = stringResource(id = R.string.analytics_insulin_profiles_level_120m),
                value = level120,
                unit = "%/5m",
                iconColor = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f)
            )
        }
        Text(
            text = stringResource(
                id = R.string.analytics_insulin_profiles_peak_template,
                UiFormatters.formatPercent(previewPeakPercentPer5m?.div(100.0)),
                UiFormatters.formatDecimalOrPlaceholder(previewPeak?.minute, decimals = 0),
                UiFormatters.formatDecimalOrPlaceholder(maxMinute, decimals = 0)
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
            verticalArrangement = Arrangement.spacedBy(Spacing.xxs)
        ) {
            curves.forEach { profile ->
                val isPreview = profile.id == previewProfile.id
                val isActive = profile.id == activeProfile.id
                FilterChip(
                    selected = isPreview,
                    onClick = { previewProfileId = profile.id },
                    label = {
                        Text(
                            text = buildString {
                                append(profile.id)
                                if (profile.isUltraRapid) {
                                    append(" • ")
                                    append(stringResource(id = R.string.analytics_insulin_ultrarapid))
                                }
                                if (isActive) {
                                    append(" • ")
                                    append(stringResource(id = R.string.analytics_insulin_active))
                                }
                            }
                        )
                    },
                    leadingIcon = {
                        Icon(
                            imageVector = if (isActive) Icons.Default.CheckCircle else Icons.Default.ShowChart,
                            contentDescription = null
                        )
                    }
                )
            }
        }
        if (previewProfile.id != activeProfile.id) {
            OutlinedButton(onClick = { onActivateProfile(previewProfile.id) }) {
                Text(text = stringResource(id = R.string.analytics_insulin_profiles_activate_button))
            }
        } else {
            Surface(
                shape = AnalyticsInfoShape,
                color = MaterialTheme.colorScheme.surfaceVariant,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                Text(
                    text = stringResource(id = R.string.analytics_insulin_profiles_active_selected),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = Spacing.sm, vertical = Spacing.xxs)
                )
            }
        }

        Column(
            verticalArrangement = Arrangement.spacedBy(Spacing.xxs)
        ) {
            chartProfiles.forEachIndexed { idx, profile ->
                val color = colorByProfile[profile.id] ?: palette[idx % palette.size]
                val isPreview = profile.id == previewProfile.id
                val isActive = profile.id == activeProfile.id
                AnalyticsLegendItem(
                    text = buildString {
                        append(profile.id)
                        if (isPreview) {
                            append(" · ")
                            append(stringResource(id = R.string.analytics_insulin_preview))
                        }
                        if (isActive) {
                            append(" · ")
                            append(stringResource(id = R.string.analytics_insulin_active))
                        }
                    },
                    color = color,
                    icon = if (isActive) Icons.Default.CheckCircle else Icons.Default.ShowChart
                )
            }
            if (showRealOverlay) {
                val overlayColor = colorByProfile[activeProfile.id] ?: palette.first()
                AnalyticsLegendItem(
                    text = stringResource(
                        id = R.string.analytics_insulin_profiles_real_overlay_legend,
                        activeProfile.id
                    ),
                    color = overlayColor,
                    icon = Icons.Default.ShowChart
                )
            }
        }
    }
}

private fun buildInsulinActivityPoints(points: List<InsulinProfilePointUi>): List<InsulinProfileActivityPoint> {
    if (points.size < 2) return emptyList()
    val sorted = points.sortedBy { it.minute }
    val raw = mutableListOf<InsulinProfileActivityPoint>()
    raw += InsulinProfileActivityPoint(
        minute = sorted.first().minute,
        per5mShare = 0.0,
        level = 0.0
    )
    for (index in 1 until sorted.size) {
        val prev = sorted[index - 1]
        val current = sorted[index]
        val deltaMinute = (current.minute - prev.minute).coerceAtLeast(1e-6)
        val deltaCumulative = (current.cumulative - prev.cumulative).coerceAtLeast(0.0)
        val per5mShare = (deltaCumulative / deltaMinute) * 5.0
        raw += InsulinProfileActivityPoint(
            minute = current.minute,
            per5mShare = per5mShare.coerceAtLeast(0.0),
            level = 0.0
        )
    }
    return raw
}

private fun formatDurationTick(minute: Double): String {
    val rounded = minute.roundToInt().coerceAtLeast(0)
    return if (rounded >= 60) {
        val hours = rounded / 60.0
        String.format(Locale.getDefault(), "%.1fh", hours)
    } else {
        "${rounded}m"
    }
}

private fun activityPercentAtMinute(
    points: List<InsulinProfileActivityPoint>,
    minute: Double
): Double? {
    if (points.isEmpty()) return null
    val boundedMinute = minute.coerceAtLeast(0.0)
    val exact = points.firstOrNull { kotlin.math.abs(it.minute - boundedMinute) < 1e-6 }
    if (exact != null) return exact.per5mShare * 100.0
    val sorted = points.sortedBy { it.minute }
    val before = sorted.lastOrNull { it.minute <= boundedMinute }
    val after = sorted.firstOrNull { it.minute >= boundedMinute }
    return when {
        before == null && after == null -> null
        before == null -> after?.per5mShare?.times(100.0)
        after == null -> before.per5mShare * 100.0
        before.minute == after.minute -> before.per5mShare * 100.0
        else -> {
            val ratio = ((boundedMinute - before.minute) / (after.minute - before.minute)).coerceIn(0.0, 1.0)
            (before.per5mShare + (after.per5mShare - before.per5mShare) * ratio) * 100.0
        }
    }
}

@Composable
private fun AnalyticsTabCard(
    selectedTab: AnalyticsTab,
    onTabSelected: (AnalyticsTab) -> Unit
) {
    val midnightGlass = LocalUiStyle.current == UiStyle.MIDNIGHT_GLASS
    AnalyticsSectionCard {
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            val tabs = listOf(AnalyticsTab.ISF_CR, AnalyticsTab.QUALITY)
            tabs.forEachIndexed { index, tab ->
                SegmentedButton(
                    selected = selectedTab == tab,
                    onClick = { onTabSelected(tab) },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = tabs.size),
                    colors = if (midnightGlass) {
                        SegmentedButtonDefaults.colors(
                            activeContainerColor = Color(0xFF1D4ED8),
                            activeContentColor = Color(0xFFF8FAFC),
                            inactiveContainerColor = Color(0xAA101D38),
                            inactiveContentColor = Color(0xFFB5C0D8),
                            activeBorderColor = Color(0x332563EB),
                            inactiveBorderColor = Color(0x1FFFFFFF)
                        )
                    } else {
                        SegmentedButtonDefaults.colors()
                    },
                    label = {
                        Text(
                            text = when (tab) {
                                AnalyticsTab.QUALITY -> stringResource(id = R.string.analytics_tab_quality)
                                AnalyticsTab.ISF_CR -> stringResource(id = R.string.analytics_tab_isf_cr)
                            }
                        )
                    }
                )
            }
        }
    }
}

@Composable
private fun IsfCrOverviewCard(
    state: AnalyticsUiState,
    selectedWindow: IsfCrHistoryWindow,
    shownPoints: Int,
    totalPoints: Int,
    visibleStartTs: Long?,
    visibleEndTs: Long?,
    onWindowSelected: (IsfCrHistoryWindow) -> Unit
) {
    val midnightGlass = LocalUiStyle.current == UiStyle.MIDNIGHT_GLASS
    val selectedWindowHours = selectedWindow.durationMs?.div(60L * 60L * 1000L)?.toInt()
    val visibleCoverageHours = if (visibleStartTs != null && visibleEndTs != null && visibleEndTs >= visibleStartTs) {
        ((visibleEndTs - visibleStartTs).toDouble() / (60.0 * 60.0 * 1000.0))
    } else {
        null
    }
    val hasPartialCoverage = selectedWindowHours != null &&
        visibleCoverageHours != null &&
        visibleCoverageHours + 0.01 < selectedWindowHours * 0.9

    AnalyticsSectionCard {
        AnalyticsSectionLabel(text = stringResource(id = R.string.section_analytics_isfcr_overview))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Spacing.xs)
        ) {
            AnalyticsMetricTile(
                title = stringResource(id = R.string.analytics_isf_real),
                value = state.currentIsfReal,
                unit = stringResource(id = R.string.unit_mmol_l) + "/U",
                modifier = Modifier.weight(1f)
            )
            AnalyticsMetricTile(
                title = stringResource(id = R.string.analytics_cr_real),
                value = state.currentCrReal,
                unit = stringResource(id = R.string.unit_g) + "/U",
                modifier = Modifier.weight(1f)
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Spacing.xs)
        ) {
            AnalyticsMetricTile(
                title = stringResource(id = R.string.analytics_isf_merged),
                value = state.currentIsfMerged,
                unit = stringResource(id = R.string.unit_mmol_l) + "/U",
                iconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            AnalyticsMetricTile(
                title = stringResource(id = R.string.analytics_cr_merged),
                value = state.currentCrMerged,
                unit = stringResource(id = R.string.unit_g) + "/U",
                iconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Spacing.xs)
        ) {
            AnalyticsMetricTile(
                title = stringResource(id = R.string.analytics_isf_aaps_raw),
                value = state.currentIsfAapsRaw,
                unit = stringResource(id = R.string.unit_mmol_l) + "/U",
                iconColor = Color(0xFFCC6A00),
                modifier = Modifier.weight(1f)
            )
            AnalyticsMetricTile(
                title = stringResource(id = R.string.analytics_cr_aaps_raw),
                value = state.currentCrAapsRaw,
                unit = stringResource(id = R.string.unit_g) + "/U",
                iconColor = Color(0xFFCC6A00),
                modifier = Modifier.weight(1f)
            )
        }
        Text(
            text = stringResource(id = R.string.analytics_series_priority_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        LazyRow(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            items(IsfCrHistoryWindow.entries.toList()) { candidate ->
                FilterChip(
                    selected = candidate == selectedWindow,
                    onClick = { onWindowSelected(candidate) },
                    colors = if (midnightGlass) analyticsFilterChipColors() else FilterChipDefaults.filterChipColors(),
                    label = { Text(text = candidate.label) }
                )
            }
        }
        Text(
            text = stringResource(id = R.string.analytics_points_template, shownPoints, totalPoints),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (selectedWindowHours != null && visibleCoverageHours != null) {
            Text(
                text = stringResource(
                    id = R.string.analytics_window_coverage_template,
                    selectedWindow.label,
                    selectedWindowHours,
                    UiFormatters.formatDecimalOrPlaceholder(visibleCoverageHours, 1)
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (hasPartialCoverage) {
            Text(
                text = stringResource(id = R.string.analytics_window_coverage_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Text(
            text = stringResource(
                id = R.string.analytics_updated_template,
                formatHistoryTs(state.historyLastUpdatedTs)
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun AnalyticsMetricTile(
    title: String,
    value: Double?,
    unit: String,
    iconColor: Color? = null,
    modifier: Modifier = Modifier
) {
    val midnightGlass = LocalUiStyle.current == UiStyle.MIDNIGHT_GLASS
    val resolvedIconColor = iconColor ?: MaterialTheme.colorScheme.primary
    val valueText = UiFormatters.formatMmol(value, decimals = 2)
    Surface(
        modifier = modifier.semantics {
            contentDescription = "$title $valueText $unit"
        },
        shape = AnalyticsInfoShape,
        color = if (midnightGlass) Color(0xAA101D38) else MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(1.dp, if (midnightGlass) Color(0x1FFFFFFF) else MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.sm),
            verticalArrangement = Arrangement.spacedBy(Spacing.xxs)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.xxs)
            ) {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = resolvedIconColor
                )
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (midnightGlass) Color(0xFFF8FAFC) else MaterialTheme.colorScheme.onSurface
                )
            }
            Text(
                text = valueText,
                style = LocalNumericTypography.current.valueMedium,
                color = if (midnightGlass) Color(0xFFF8FAFC) else MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = unit,
                style = MaterialTheme.typography.labelSmall,
                color = if (midnightGlass) Color(0xFFB5C0D8) else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun IsfCrSeriesCard(
    chartKey: String,
    title: String,
    unit: String,
    points: List<IsfCrHistoryPointUi>,
    overlayPoints: List<IsfCrOverlayPointUi>,
    evidenceColor: Color,
    fallbackColor: Color,
    aapsColor: Color,
    cobColor: Color,
    uamColor: Color,
    activityColor: Color,
    evidenceSelector: (IsfCrHistoryPointUi) -> Double?,
    fallbackSelector: (IsfCrHistoryPointUi) -> Double?,
    aapsSelector: (IsfCrHistoryPointUi) -> Double?
) {
    var selectedScaleRaw by rememberSaveable(chartKey) { mutableStateOf(IsfCrChartScale.AUTO.name) }
    var showEvidence by rememberSaveable("${chartKey}_evidence") { mutableStateOf(true) }
    var showFallback by rememberSaveable("${chartKey}_fallback") { mutableStateOf(true) }
    var showAaps by rememberSaveable("${chartKey}_aaps") { mutableStateOf(true) }
    var showCob by rememberSaveable("${chartKey}_cob") { mutableStateOf(false) }
    var showUam by rememberSaveable("${chartKey}_uam") { mutableStateOf(false) }
    var showActivity by rememberSaveable("${chartKey}_activity") { mutableStateOf(false) }
    val selectedScale = remember(selectedScaleRaw) {
        runCatching { IsfCrChartScale.valueOf(selectedScaleRaw) }.getOrDefault(IsfCrChartScale.AUTO)
    }
    val series = remember(points) {
        points
            .map {
                IsfCrSeriesPoint(
                    ts = it.timestamp,
                    evidence = evidenceSelector(it),
                    fallback = fallbackSelector(it),
                    aaps = aapsSelector(it)
                )
            }
            .sortedBy { it.ts }
    }
    val overlaySeries = remember(overlayPoints) {
        overlayPoints
            .map {
                IsfCrOverlaySeriesPoint(
                    ts = it.timestamp,
                    cob = it.cobGrams,
                    uam = it.uamGrams,
                    activity = it.activityRatio
                )
            }
            .sortedBy { it.ts }
    }
    val evidenceValues = series.mapNotNull { it.evidence?.takeIf { value -> value.isFinite() } }
    val fallbackValues = series.mapNotNull { it.fallback?.takeIf { value -> value.isFinite() } }
    val aapsValues = series.mapNotNull { it.aaps?.takeIf { value -> value.isFinite() } }
    val selectedPrimaryValues = buildList {
        if (showEvidence) addAll(evidenceValues)
        if (showFallback) addAll(fallbackValues)
        if (showAaps) addAll(aapsValues)
    }
    val statsValues = when {
        selectedPrimaryValues.isNotEmpty() -> selectedPrimaryValues
        evidenceValues.isNotEmpty() -> evidenceValues
        fallbackValues.isNotEmpty() -> fallbackValues
        else -> aapsValues
    }
    val min = statsValues.minOrNull()
    val max = statsValues.maxOrNull()
    val lastEvidence = evidenceValues.lastOrNull()
    val lastFallback = fallbackValues.lastOrNull()
    val lastAaps = aapsValues.lastOrNull()
    val overlayCobScale = overlayScaleOrNull(
        overlaySeries.mapNotNull { it.cob?.takeIf { value -> value.isFinite() } }.takeIf { showCob }.orEmpty()
    )
    val overlayUamScale = overlayScaleOrNull(
        overlaySeries.mapNotNull { it.uam?.takeIf { value -> value.isFinite() } }.takeIf { showUam }.orEmpty()
    )
    val overlayActivityScale = overlayScaleOrNull(
        overlaySeries.mapNotNull { it.activity?.takeIf { value -> value.isFinite() } }.takeIf { showActivity }.orEmpty()
    )
    val lastEvidenceFallbackPair = series.lastOrNull { it.evidence != null && it.fallback != null }
    val lastDelta = if (lastEvidenceFallbackPair?.evidence != null && lastEvidenceFallbackPair.fallback != null) {
        lastEvidenceFallbackPair.evidence - lastEvidenceFallbackPair.fallback
    } else {
        null
    }
    val startTs = series.firstOrNull()?.ts
    val endTs = series.lastOrNull()?.ts
    val hasEvidenceSeparation = series.any { point ->
        val evidence = point.evidence ?: return@any false
        val fallback = point.fallback ?: return@any false
        abs(evidence - fallback) >= 0.02
    }
    val fallbackCoveragePct = if (series.isNotEmpty()) {
        (series.count { it.fallback != null }.toDouble() / series.size.toDouble() * 100.0).coerceIn(0.0, 100.0)
    } else {
        0.0
    }
    val aapsCoveragePct = if (series.isNotEmpty()) {
        (series.count { it.aaps != null }.toDouble() / series.size.toDouble() * 100.0).coerceIn(0.0, 100.0)
    } else {
        0.0
    }
    val evidenceCoveragePct = if (series.isNotEmpty()) {
        (series.count { it.evidence != null }.toDouble() / series.size.toDouble() * 100.0).coerceIn(0.0, 100.0)
    } else {
        0.0
    }

    AnalyticsSectionCard {
        AnalyticsSectionLabel(text = stringResource(id = R.string.section_analytics_isfcr_history))
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall
        )
        Text(
            text = stringResource(id = R.string.analytics_series_line_mapping),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        if (series.size < 2) {
            Text(
                text = stringResource(id = R.string.analytics_chart_not_enough_points),
                style = MaterialTheme.typography.bodyMedium
            )
            return@AnalyticsSectionCard
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Spacing.xs)
        ) {
            listOf(IsfCrChartScale.ZOOM_IN, IsfCrChartScale.AUTO, IsfCrChartScale.ZOOM_OUT).forEach { candidate ->
                FilterChip(
                    selected = selectedScale == candidate,
                    onClick = { selectedScaleRaw = candidate.name },
                    colors = if (LocalUiStyle.current == UiStyle.MIDNIGHT_GLASS) analyticsFilterChipColors() else FilterChipDefaults.filterChipColors(),
                    label = {
                        Text(
                            text = when (candidate) {
                                IsfCrChartScale.ZOOM_IN -> stringResource(id = R.string.analytics_scale_zoom_in)
                                IsfCrChartScale.AUTO -> stringResource(id = R.string.analytics_scale_auto)
                                IsfCrChartScale.ZOOM_OUT -> stringResource(id = R.string.analytics_scale_zoom_out)
                            }
                        )
                    }
                )
            }
        }

        val allValues = series.flatMap { point ->
            buildList {
                if (showEvidence) point.evidence?.let { add(it) }
                if (showFallback) point.fallback?.let { add(it) }
                if (showAaps) point.aaps?.let { add(it) }
            }
        }.filter { it.isFinite() }.sorted()
        val ySource = if (allValues.isNotEmpty()) allValues else statsValues.sorted()
        val trimmedCount = (ySource.size * 0.02).toInt().coerceAtMost((ySource.size - 1).coerceAtLeast(0) / 2)
        val yMinRaw = ySource.getOrNull(trimmedCount) ?: 0.0
        val yMaxRaw = ySource.getOrNull(ySource.lastIndex - trimmedCount) ?: 1.0
        val baseRange = (yMaxRaw - yMinRaw).coerceAtLeast(0.05)
        val yCenter = yMinRaw + baseRange * 0.5
        val yPadding = max(0.1, baseRange * 0.15)
        val scaledRange = baseRange * selectedScale.rangeMultiplier
        val yMin = yCenter - scaledRange * 0.5 - yPadding
        val yMax = yCenter + scaledRange * 0.5 + yPadding
        val yMid = yMin + (yMax - yMin) * 0.5
        val minTs = series.first().ts
        val maxTs = max(minTs + 1L, series.last().ts)
        val midTs = minTs + (maxTs - minTs) / 2L

        val chartDescription = stringResource(
            id = R.string.analytics_chart_accessibility_template,
            title,
            UiFormatters.formatMmol(lastEvidence, 2),
            unit,
            UiFormatters.formatMmol(lastFallback, 2),
            UiFormatters.formatMmol(lastAaps, 2)
        )
        val gridMajor = MaterialTheme.colorScheme.outline.copy(alpha = 0.16f)
        val gridMinor = MaterialTheme.colorScheme.outline.copy(alpha = 0.10f)
        val axisColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
        val chartBackground = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.28f)

        Text(
            text = stringResource(id = R.string.analytics_axis_units_template, unit),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier
                    .width(56.dp)
                    .height(240.dp),
                verticalArrangement = Arrangement.SpaceBetween,
                horizontalAlignment = Alignment.End
            ) {
                Text(
                    text = UiFormatters.formatDecimalOrPlaceholder(yMax, decimals = 2),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = UiFormatters.formatDecimalOrPlaceholder(yMid, decimals = 2),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = UiFormatters.formatDecimalOrPlaceholder(yMin, decimals = 2),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Canvas(
                modifier = Modifier
                    .weight(1f)
                    .height(240.dp)
                    .semantics { contentDescription = chartDescription }
            ) {
                val left = 20.dp.toPx()
                val right = 8.dp.toPx()
                val top = 10.dp.toPx()
                val bottom = 22.dp.toPx()
                val chartWidth = (size.width - left - right).coerceAtLeast(1f)
                val chartHeight = (size.height - top - bottom).coerceAtLeast(1f)
                drawRect(
                    color = chartBackground,
                    topLeft = Offset(left, top),
                    size = Size(chartWidth, chartHeight)
                )

                fun xOf(ts: Long): Float {
                    return left + ((ts - minTs).toDouble() / (maxTs - minTs).toDouble() * chartWidth).toFloat()
                }

                fun yOf(value: Double): Float {
                    val ratio = ((value - yMin) / (yMax - yMin)).coerceIn(0.0, 1.0)
                    return (top + chartHeight - ratio * chartHeight).toFloat()
                }

                fun yOverlayOf(value: Double, scale: AnalyticsOverlayScale): Float {
                    val ratio = ((value - scale.min) / (scale.max - scale.min)).coerceIn(0.0, 1.0)
                    return (top + chartHeight - ratio * chartHeight).toFloat()
                }

                repeat(5) { index ->
                    val y = top + chartHeight * index / 4f
                    drawLine(
                        color = gridMajor,
                        start = Offset(left, y),
                        end = Offset(left + chartWidth, y),
                        strokeWidth = 1f
                    )
                }
                repeat(7) { index ->
                    val x = left + chartWidth * index / 6f
                    drawLine(
                        color = gridMinor,
                        start = Offset(x, top),
                        end = Offset(x, top + chartHeight),
                        strokeWidth = 1f
                    )
                }

                drawLine(
                    color = axisColor,
                    start = Offset(left, top),
                    end = Offset(left, top + chartHeight),
                    strokeWidth = 1.2f
                )
                drawLine(
                    color = axisColor,
                    start = Offset(left, top + chartHeight),
                    end = Offset(left + chartWidth, top + chartHeight),
                    strokeWidth = 1.2f
                )

                val evidencePath = Path()
                val fallbackPath = Path()
                val aapsPath = Path()
                val cobPath = Path()
                val uamPath = Path()
                val activityPath = Path()

                var hasEvidencePath = false
                var pendingMoveEvidence = true
                series.forEach { point ->
                    if (!showEvidence) return@forEach
                    val evidenceValue = point.evidence ?: run {
                        pendingMoveEvidence = true
                        return@forEach
                    }
                    val x = xOf(point.ts)
                    val yEvidence = yOf(evidenceValue)
                    if (pendingMoveEvidence) {
                        evidencePath.moveTo(x, yEvidence)
                        pendingMoveEvidence = false
                    } else {
                        evidencePath.lineTo(x, yEvidence)
                    }
                    hasEvidencePath = true
                }

                var hasFallbackPath = false
                var pendingMoveFallback = true
                series.forEach { point ->
                    if (!showFallback) return@forEach
                    val fallbackValue = point.fallback ?: run {
                        pendingMoveFallback = true
                        return@forEach
                    }
                    val x = xOf(point.ts)
                    val y = yOf(fallbackValue)
                    if (pendingMoveFallback) {
                        fallbackPath.moveTo(x, y)
                        pendingMoveFallback = false
                    } else {
                        fallbackPath.lineTo(x, y)
                    }
                    hasFallbackPath = true
                }

                var hasAapsPath = false
                var pendingMoveAaps = true
                series.forEach { point ->
                    if (!showAaps) return@forEach
                    val aapsValue = point.aaps ?: run {
                        pendingMoveAaps = true
                        return@forEach
                    }
                    val x = xOf(point.ts)
                    val y = yOf(aapsValue)
                    if (pendingMoveAaps) {
                        aapsPath.moveTo(x, y)
                        pendingMoveAaps = false
                    } else {
                        aapsPath.lineTo(x, y)
                    }
                    hasAapsPath = true
                }

                var hasCobPath = false
                var pendingMoveCob = true
                overlaySeries.forEach { point ->
                    if (!showCob || overlayCobScale == null) return@forEach
                    val value = point.cob ?: run {
                        pendingMoveCob = true
                        return@forEach
                    }
                    val x = xOf(point.ts)
                    val y = yOverlayOf(value, overlayCobScale)
                    if (pendingMoveCob) {
                        cobPath.moveTo(x, y)
                        pendingMoveCob = false
                    } else {
                        cobPath.lineTo(x, y)
                    }
                    hasCobPath = true
                }

                var hasUamPath = false
                var pendingMoveUam = true
                overlaySeries.forEach { point ->
                    if (!showUam || overlayUamScale == null) return@forEach
                    val value = point.uam ?: run {
                        pendingMoveUam = true
                        return@forEach
                    }
                    val x = xOf(point.ts)
                    val y = yOverlayOf(value, overlayUamScale)
                    if (pendingMoveUam) {
                        uamPath.moveTo(x, y)
                        pendingMoveUam = false
                    } else {
                        uamPath.lineTo(x, y)
                    }
                    hasUamPath = true
                }

                var hasActivityPath = false
                var pendingMoveActivity = true
                overlaySeries.forEach { point ->
                    if (!showActivity || overlayActivityScale == null) return@forEach
                    val value = point.activity ?: run {
                        pendingMoveActivity = true
                        return@forEach
                    }
                    val x = xOf(point.ts)
                    val y = yOverlayOf(value, overlayActivityScale)
                    if (pendingMoveActivity) {
                        activityPath.moveTo(x, y)
                        pendingMoveActivity = false
                    } else {
                        activityPath.lineTo(x, y)
                    }
                    hasActivityPath = true
                }

                if (hasAapsPath) {
                    drawPath(
                        path = aapsPath,
                        color = aapsColor,
                        style = Stroke(
                            width = 2.2f,
                            cap = StrokeCap.Round,
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 9f), 0f)
                        )
                    )
                }
                if (hasFallbackPath) {
                    drawPath(
                        path = fallbackPath,
                        color = fallbackColor,
                        style = Stroke(
                            width = 2.4f,
                            cap = StrokeCap.Round,
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(16f, 10f), 0f)
                        )
                    )
                }
                if (hasEvidencePath) {
                    drawPath(
                        path = evidencePath,
                        color = evidenceColor,
                        style = Stroke(width = 3.5f, cap = StrokeCap.Round)
                    )
                }
                if (hasCobPath) {
                    drawPath(
                        path = cobPath,
                        color = cobColor,
                        style = Stroke(
                            width = 2.0f,
                            cap = StrokeCap.Round,
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f), 0f)
                        )
                    )
                }
                if (hasUamPath) {
                    drawPath(
                        path = uamPath,
                        color = uamColor,
                        style = Stroke(
                            width = 2.0f,
                            cap = StrokeCap.Round,
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(2f, 8f), 0f)
                        )
                    )
                }
                if (hasActivityPath) {
                    drawPath(
                        path = activityPath,
                        color = activityColor,
                        style = Stroke(
                            width = 2.0f,
                            cap = StrokeCap.Round,
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(14f, 6f), 0f)
                        )
                    )
                }

                if (showAaps) {
                    lastAaps?.let {
                        val lastAapsPoint = series.lastOrNull { seriesPoint -> seriesPoint.aaps != null }
                        if (lastAapsPoint != null) {
                            drawCircle(
                                color = aapsColor,
                                radius = 4.5f,
                                center = Offset(xOf(lastAapsPoint.ts), yOf(lastAapsPoint.aaps ?: it))
                            )
                        }
                    }
                }
                val lastFallbackPoint = series.lastOrNull { it.fallback != null }
                if (showFallback && lastFallbackPoint?.fallback != null) {
                    drawCircle(
                        color = fallbackColor,
                        radius = 4.8f,
                        center = Offset(xOf(lastFallbackPoint.ts), yOf(lastFallbackPoint.fallback))
                    )
                }
                val lastEvidencePoint = series.lastOrNull { it.evidence != null }
                if (showEvidence && lastEvidencePoint?.evidence != null) {
                    drawCircle(
                        color = evidenceColor,
                        radius = 5.2f,
                        center = Offset(xOf(lastEvidencePoint.ts), yOf(lastEvidencePoint.evidence))
                    )
                }
            }
        }
        Text(
            text = stringResource(id = R.string.analytics_axis_time),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = formatHistoryAxisTick(minTs, maxTs - minTs),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = formatHistoryAxisTick(midTs, maxTs - minTs),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = formatHistoryAxisTick(maxTs, maxTs - minTs),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Text(
            text = stringResource(id = R.string.analytics_lines_visible),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
            verticalArrangement = Arrangement.spacedBy(Spacing.xs)
        ) {
            AnalyticsLineToggle(
                text = stringResource(id = R.string.analytics_toggle_evidence),
                color = evidenceColor,
                checked = showEvidence,
                onCheckedChange = { showEvidence = it }
            )
            AnalyticsLineToggle(
                text = stringResource(id = R.string.analytics_toggle_fallback),
                color = fallbackColor,
                checked = showFallback,
                onCheckedChange = { showFallback = it }
            )
            AnalyticsLineToggle(
                text = stringResource(id = R.string.analytics_toggle_aaps_raw),
                color = aapsColor,
                checked = showAaps,
                onCheckedChange = { showAaps = it }
            )
        }
        Text(
            text = stringResource(id = R.string.analytics_overlay_visible),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
            verticalArrangement = Arrangement.spacedBy(Spacing.xs)
        ) {
            AnalyticsLineToggle(
                text = stringResource(id = R.string.analytics_toggle_cob),
                color = cobColor,
                checked = showCob,
                onCheckedChange = { showCob = it }
            )
            AnalyticsLineToggle(
                text = stringResource(id = R.string.analytics_toggle_uam),
                color = uamColor,
                checked = showUam,
                onCheckedChange = { showUam = it }
            )
            AnalyticsLineToggle(
                text = stringResource(id = R.string.analytics_toggle_activity),
                color = activityColor,
                checked = showActivity,
                onCheckedChange = { showActivity = it }
            )
        }
        if (showCob || showUam || showActivity) {
            Text(
                text = stringResource(id = R.string.analytics_overlay_scale_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
            verticalArrangement = Arrangement.spacedBy(Spacing.xs)
        ) {
            if (showEvidence) {
                AnalyticsLegendItem(
                    text = stringResource(id = R.string.analytics_legend_real),
                    color = evidenceColor,
                    icon = Icons.Default.ShowChart
                )
            }
            if (showFallback) {
                AnalyticsLegendItem(
                    text = stringResource(id = R.string.analytics_legend_merged),
                    color = fallbackColor,
                    icon = Icons.Default.Info
                )
            }
            if (showAaps) {
                AnalyticsLegendItem(
                    text = stringResource(id = R.string.analytics_legend_aaps_raw),
                    color = aapsColor,
                    icon = Icons.Default.Info
                )
            }
            if (showCob && overlayCobScale != null) {
                AnalyticsLegendItem(
                    text = stringResource(
                        id = R.string.analytics_overlay_legend_template,
                        stringResource(id = R.string.analytics_toggle_cob),
                        UiFormatters.formatDecimalOrPlaceholder(overlayCobScale.latest, 1),
                        stringResource(id = R.string.unit_g),
                        UiFormatters.formatDecimalOrPlaceholder(overlayCobScale.min, 1),
                        UiFormatters.formatDecimalOrPlaceholder(overlayCobScale.max, 1)
                    ),
                    color = cobColor,
                    icon = Icons.Default.ShowChart
                )
            }
            if (showUam && overlayUamScale != null) {
                AnalyticsLegendItem(
                    text = stringResource(
                        id = R.string.analytics_overlay_legend_template,
                        stringResource(id = R.string.analytics_toggle_uam),
                        UiFormatters.formatDecimalOrPlaceholder(overlayUamScale.latest, 1),
                        stringResource(id = R.string.unit_g),
                        UiFormatters.formatDecimalOrPlaceholder(overlayUamScale.min, 1),
                        UiFormatters.formatDecimalOrPlaceholder(overlayUamScale.max, 1)
                    ),
                    color = uamColor,
                    icon = Icons.Default.ShowChart
                )
            }
            if (showActivity && overlayActivityScale != null) {
                AnalyticsLegendItem(
                    text = stringResource(
                        id = R.string.analytics_overlay_legend_template,
                        stringResource(id = R.string.analytics_toggle_activity),
                        UiFormatters.formatDecimalOrPlaceholder(overlayActivityScale.latest, 2),
                        stringResource(id = R.string.analytics_overlay_activity_unit),
                        UiFormatters.formatDecimalOrPlaceholder(overlayActivityScale.min, 2),
                        UiFormatters.formatDecimalOrPlaceholder(overlayActivityScale.max, 2)
                    ),
                    color = activityColor,
                    icon = Icons.Default.ShowChart
                )
            }
        }
        Text(
            text = stringResource(
                id = R.string.analytics_real_coverage_template,
                UiFormatters.formatDecimalOrPlaceholder(evidenceCoveragePct, 0)
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = stringResource(
                id = R.string.analytics_fallback_coverage_template,
                UiFormatters.formatDecimalOrPlaceholder(fallbackCoveragePct, 0)
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = stringResource(
                id = R.string.analytics_aaps_coverage_template,
                UiFormatters.formatDecimalOrPlaceholder(aapsCoveragePct, 0)
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (evidenceCoveragePct <= 0.0) {
            Text(
                text = stringResource(id = R.string.analytics_real_series_empty_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (fallbackCoveragePct <= 0.0) {
            Text(
                text = stringResource(id = R.string.analytics_fallback_series_empty_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (aapsCoveragePct <= 0.0) {
            Text(
                text = stringResource(id = R.string.analytics_aaps_series_empty_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else if (!hasEvidenceSeparation) {
            Text(
                text = stringResource(id = R.string.analytics_series_realtime_matches_base_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Text(
            text = stringResource(
                id = R.string.analytics_stats_template,
                UiFormatters.formatDecimalOrPlaceholder(min, 2),
                UiFormatters.formatDecimalOrPlaceholder(max, 2),
                UiFormatters.formatDecimalOrPlaceholder(lastEvidence ?: lastFallback ?: lastAaps, 2),
                unit
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = stringResource(
                id = R.string.analytics_last_delta_template,
                UiFormatters.formatSignedDelta(lastDelta),
                unit
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (startTs != null && endTs != null) {
            Text(
                text = stringResource(
                    id = R.string.analytics_range_template,
                    formatHistoryTs(startTs),
                    formatHistoryTs(endTs)
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun AnalyticsLineToggle(
    text: String,
    color: Color,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Surface(
        shape = AnalyticsInfoShape,
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = Spacing.sm, vertical = Spacing.xxs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.xs)
        ) {
            Icon(
                imageVector = Icons.Default.ShowChart,
                contentDescription = null,
                tint = color
            )
            Text(
                text = text,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange
            )
        }
    }
}

private fun overlayScaleOrNull(values: List<Double>): AnalyticsOverlayScale? {
    if (values.isEmpty()) return null
    val sorted = values.filter { it.isFinite() }.sorted()
    if (sorted.isEmpty()) return null
    val trimmedCount = (sorted.size * 0.05).toInt().coerceAtMost((sorted.size - 1).coerceAtLeast(0) / 2)
    val minRaw = sorted.getOrNull(trimmedCount) ?: sorted.first()
    val maxRaw = sorted.getOrNull(sorted.lastIndex - trimmedCount) ?: sorted.last()
    val range = (maxRaw - minRaw).coerceAtLeast(0.01)
    val padding = max(0.02, range * 0.12)
    return AnalyticsOverlayScale(
        min = minRaw - padding,
        max = maxRaw + padding,
        latest = sorted.lastOrNull()
    )
}

@Composable
private fun AnalyticsLegendItem(
    text: String,
    color: Color,
    icon: androidx.compose.ui.graphics.vector.ImageVector
) {
    Surface(
        shape = AnalyticsInfoShape,
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = Spacing.sm, vertical = Spacing.xxs),
            horizontalArrangement = Arrangement.spacedBy(Spacing.xxs),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = color
            )
            Text(
                text = text,
                style = MaterialTheme.typography.labelMedium
            )
        }
    }
}

@Composable
private fun AnalyticsLineCard(line: String) {
    AnalyticsSectionCard {
        Surface(
            shape = AnalyticsInfoShape,
            color = MaterialTheme.colorScheme.surfaceVariant,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        ) {
            Text(
                text = line,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(Spacing.sm)
            )
        }
    }
}

@Composable
private fun SensorLagDiagnosticsCard(
    diagnostics: SensorLagDiagnosticsUi,
    replayBuckets: List<DailyReportSensorLagReplayUi>,
    shadowBuckets: List<DailyReportSensorLagShadowUi>,
    showTrendDialog: Boolean,
    onShowTrendDialogChange: (Boolean) -> Unit,
    onSensorLagCorrectionModeChange: (String) -> Unit
) {
    val rolloutGuidance = buildSensorLagRolloutGuidance(
        replayBuckets = replayBuckets,
        shadowBuckets = shadowBuckets
    )
    val currentBucket = diagnostics.ageHours?.let(::sensorLagAgeBucket)
    val currentGuidance = currentBucket
        ?.let { bucket -> rolloutGuidance.firstOrNull { guidance -> guidance.bucket == bucket } }
    val stateSummary = when {
        diagnostics.configuredMode.equals("OFF", ignoreCase = true) ->
            stringResource(id = R.string.analytics_sensor_lag_state_disabled)
        diagnostics.runtimeMode.equals("ACTIVE", ignoreCase = true) ->
            stringResource(id = R.string.analytics_sensor_lag_state_active)
        diagnostics.runtimeMode.equals("SHADOW", ignoreCase = true) ->
            stringResource(id = R.string.analytics_sensor_lag_state_shadow)
        !diagnostics.disableReason.isNullOrBlank() ->
            stringResource(
                id = R.string.analytics_sensor_lag_state_blocked,
                diagnostics.disableReason.replace('_', ' ')
            )
        else -> stringResource(id = R.string.analytics_sensor_lag_state_waiting)
    }
    val runtimeModeValue = diagnostics.runtimeMode ?: diagnostics.configuredMode
    val runtimeTone = when {
        diagnostics.runtimeMode.equals("ACTIVE", ignoreCase = true) -> AnalyticsMetricTone.POSITIVE
        diagnostics.runtimeMode.equals("SHADOW", ignoreCase = true) -> AnalyticsMetricTone.DEFAULT
        diagnostics.configuredMode.equals("OFF", ignoreCase = true) -> AnalyticsMetricTone.DEFAULT
        !diagnostics.disableReason.isNullOrBlank() -> AnalyticsMetricTone.NEGATIVE
        else -> AnalyticsMetricTone.DEFAULT
    }

    AnalyticsSectionCard {
        AnalyticsSectionLabel(text = stringResource(id = R.string.section_analytics_sensor_lag))
        Text(
            text = stringResource(id = R.string.analytics_sensor_lag_subtitle),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        SensorLagSummaryCard(text = stateSummary)
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(Spacing.xxs),
            verticalArrangement = Arrangement.spacedBy(Spacing.xxs)
        ) {
            AnalyticsMetricChip(
                label = stringResource(id = R.string.analytics_sensor_lag_metric_config_mode),
                value = diagnostics.configuredMode.uppercase(Locale.US)
            )
            AnalyticsMetricChip(
                label = stringResource(id = R.string.analytics_sensor_lag_metric_runtime_mode),
                value = runtimeModeValue.uppercase(Locale.US),
                tone = runtimeTone
            )
            AnalyticsMetricChip(
                label = stringResource(id = R.string.analytics_sensor_lag_metric_bucket),
                value = currentBucket?.let { sensorLagBucketDisplayName(it) } ?: "--"
            )
            AnalyticsMetricChip(
                label = stringResource(id = R.string.analytics_sensor_lag_metric_lag),
                value = diagnostics.lagMinutes?.let { "${UiFormatters.formatDecimalOrPlaceholder(it, 0)}m" } ?: "--"
            )
            AnalyticsMetricChip(
                label = stringResource(id = R.string.analytics_sensor_lag_metric_age),
                value = diagnostics.ageHours?.let { "${UiFormatters.formatDecimalOrPlaceholder(it, 0)}h" } ?: "--"
            )
            AnalyticsMetricChip(
                label = stringResource(id = R.string.analytics_sensor_lag_metric_confidence),
                value = UiFormatters.formatPercent(diagnostics.confidence, decimals = 0)
            )
            AnalyticsMetricChip(
                label = stringResource(id = R.string.analytics_sensor_lag_metric_raw_glucose),
                value = UiFormatters.formatMmol(diagnostics.rawGlucoseMmol, decimals = 2)
            )
            AnalyticsMetricChip(
                label = stringResource(id = R.string.analytics_sensor_lag_metric_corrected_glucose),
                value = UiFormatters.formatMmol(diagnostics.correctedGlucoseMmol, decimals = 2)
            )
            AnalyticsMetricChip(
                label = stringResource(id = R.string.analytics_sensor_lag_metric_correction),
                value = UiFormatters.formatSignedDelta(diagnostics.correctionMmol),
                tone = when {
                    (diagnostics.correctionMmol ?: 0.0) > 0.0 -> AnalyticsMetricTone.POSITIVE
                    (diagnostics.correctionMmol ?: 0.0) < 0.0 -> AnalyticsMetricTone.NEGATIVE
                    else -> AnalyticsMetricTone.DEFAULT
                }
            )
            AnalyticsMetricChip(
                label = stringResource(id = R.string.analytics_sensor_lag_metric_sensor_quality),
                value = UiFormatters.formatDecimalOrPlaceholder(diagnostics.sensorQualityScore, 2),
                tone = when {
                    diagnostics.sensorQualityBlocked == true || diagnostics.sensorQualitySuspectFalseLow == true ->
                        AnalyticsMetricTone.NEGATIVE
                    else -> AnalyticsMetricTone.DEFAULT
                }
            )
        }
        diagnostics.ageSource?.let { source ->
            Text(
                text = stringResource(
                    id = R.string.analytics_sensor_lag_source_line,
                    sensorLagAgeSourceDisplayName(source)
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (diagnostics.lagTrendPoints.size >= 2 || diagnostics.correctionTrendPoints.size >= 2) {
            val trendWindowLine = if (
                diagnostics.trendStartAgeHours != null &&
                diagnostics.trendEndAgeHours != null
            ) {
                stringResource(
                    id = R.string.analytics_sensor_lag_trend_window_line,
                    "${UiFormatters.formatDecimalOrPlaceholder(diagnostics.trendStartAgeHours, 0)}h",
                    "${UiFormatters.formatDecimalOrPlaceholder(diagnostics.trendEndAgeHours, 0)}h"
                )
            } else {
                stringResource(id = R.string.analytics_sensor_lag_trend_window_line_fallback)
            }
            Text(
                text = trendWindowLine,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (diagnostics.lagTrendPoints.size >= 2) {
                SensorLagTrendSparkline(
                    title = stringResource(id = R.string.analytics_sensor_lag_trend_lag_title),
                    points = diagnostics.lagTrendPoints,
                    lineColor = MaterialTheme.colorScheme.primary,
                    valueFormatter = { value ->
                        "${UiFormatters.formatDecimalOrPlaceholder(value, 0)}m"
                    }
                )
            }
            if (diagnostics.correctionTrendPoints.size >= 2) {
                SensorLagTrendSparkline(
                    title = stringResource(id = R.string.analytics_sensor_lag_trend_correction_title),
                    points = diagnostics.correctionTrendPoints,
                    lineColor = MaterialTheme.colorScheme.tertiary,
                    includeZeroBaseline = true,
                    valueFormatter = { value ->
                        UiFormatters.formatSignedDelta(value)
                    }
                )
            }
            OutlinedButton(onClick = { onShowTrendDialogChange(true) }) {
                Text(text = stringResource(id = R.string.analytics_sensor_lag_trend_open_button))
            }
        }
        if (!diagnostics.disableReason.isNullOrBlank()) {
            Text(
                text = stringResource(
                    id = R.string.analytics_sensor_lag_gate_line,
                    diagnostics.disableReason.replace('_', ' ')
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.tertiary
            )
        }
        if (
            diagnostics.sensorQualityBlocked == true ||
            diagnostics.sensorQualitySuspectFalseLow == true ||
            !diagnostics.sensorQualityReason.isNullOrBlank()
        ) {
            Text(
                text = buildString {
                    append(stringResource(id = R.string.analytics_sensor_lag_quality_prefix))
                    append(": ")
                    append(
                        diagnostics.sensorQualityReason
                            ?.replace('_', ' ')
                            ?.takeIf { it.isNotBlank() }
                            ?: stringResource(id = R.string.analytics_sensor_lag_quality_ok)
                    )
                    if (diagnostics.sensorQualityBlocked == true) {
                        append(" • ")
                        append(stringResource(id = R.string.analytics_sensor_lag_quality_blocked))
                    }
                    if (diagnostics.sensorQualitySuspectFalseLow == true) {
                        append(" • ")
                        append(stringResource(id = R.string.analytics_sensor_lag_quality_false_low))
                    }
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Text(
            text = stringResource(id = R.string.analytics_sensor_lag_replay_guidance_title),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (currentGuidance != null) {
            SensorLagRolloutGuidanceCard(guidance = currentGuidance)
        } else {
            SensorLagSummaryCard(
                text = if (currentBucket != null) {
                    stringResource(
                        id = R.string.analytics_sensor_lag_replay_guidance_missing_bucket,
                        sensorLagBucketDisplayName(currentBucket)
                    )
                } else {
                    stringResource(id = R.string.analytics_sensor_lag_replay_guidance_missing)
                }
            )
        }
    }
    if (showTrendDialog) {
        SensorLagTrendDetailDialog(
            diagnostics = diagnostics,
            currentBucket = currentBucket,
            currentGuidance = currentGuidance,
            rolloutGuidance = rolloutGuidance,
            onSensorLagCorrectionModeChange = onSensorLagCorrectionModeChange,
            onDismiss = { onShowTrendDialogChange(false) }
        )
    }
}

@Composable
private fun SensorLagRelativeSummaryCard(
    currentBucket: String?,
    currentGuidance: SensorLagRolloutGuidance?,
    adjacentGuidance: List<SensorLagRolloutGuidance>
) {
    val comparableAdjacent = adjacentGuidance.filter { it.weightedGainMmol != null || it.shadowRatePct != null }
    val summaryText = if (
        currentBucket == null ||
        currentGuidance == null ||
        comparableAdjacent.isEmpty()
    ) {
        stringResource(id = R.string.analytics_sensor_lag_comparison_missing)
    } else {
        val currentRank = sensorLagRolloutStatusRank(currentGuidance.status)
        val adjacentRanks = comparableAdjacent.map { sensorLagRolloutStatusRank(it.status) }
        val currentGain = currentGuidance.weightedGainMmol
        val adjacentGains = comparableAdjacent.mapNotNull { it.weightedGainMmol }
        val adjacentGainAvg = adjacentGains.takeIf { it.isNotEmpty() }?.average()
        val currentShadow = currentGuidance.shadowRatePct
        val adjacentShadows = comparableAdjacent.mapNotNull { it.shadowRatePct }
        val adjacentShadowAvg = adjacentShadows.takeIf { it.isNotEmpty() }?.average()
        val gainDelta = if (currentGain != null && adjacentGainAvg != null) currentGain - adjacentGainAvg else null
        val shadowDelta = if (currentShadow != null && adjacentShadowAvg != null) currentShadow - adjacentShadowAvg else null
        val relationTextId = when {
            currentRank > (adjacentRanks.maxOrNull() ?: currentRank) ||
                (gainDelta != null && gainDelta >= 0.05 && (shadowDelta == null || shadowDelta >= -2.0)) ->
                R.string.analytics_sensor_lag_comparison_stronger
            currentRank < (adjacentRanks.minOrNull() ?: currentRank) ||
                (gainDelta != null && gainDelta <= -0.05 && (shadowDelta == null || shadowDelta <= 2.0)) ->
                R.string.analytics_sensor_lag_comparison_weaker
            else -> R.string.analytics_sensor_lag_comparison_similar
        }
        stringResource(
            id = relationTextId,
            sensorLagBucketDisplayName(currentBucket),
            UiFormatters.formatSignedDelta(currentGain),
            adjacentGainAvg?.let { UiFormatters.formatSignedDelta(it) } ?: "--",
            currentShadow?.let { String.format(Locale.US, "%.1f%%", it) } ?: "--",
            adjacentShadowAvg?.let { String.format(Locale.US, "%.1f%%", it) } ?: "--"
        )
    }
    SensorLagSummaryCard(text = summaryText)
}

@Composable
private fun SensorLagTrendSparkline(
    title: String,
    points: List<ChartPointUi>,
    lineColor: Color,
    valueFormatter: (Double) -> String,
    includeZeroBaseline: Boolean = false,
    chartHeightDp: Dp = 88.dp
) {
    AnalyticsSectionCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium
            )
            val startValue = points.firstOrNull()?.value
            val latestValue = points.lastOrNull()?.value
            Text(
                text = if (startValue != null && latestValue != null) {
                    stringResource(
                        id = R.string.analytics_sensor_lag_trend_range_line,
                        valueFormatter(startValue),
                        valueFormatter(latestValue)
                    )
                } else {
                    stringResource(id = R.string.analytics_sensor_lag_trend_empty)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (points.size < 2) {
            Text(
                text = stringResource(id = R.string.analytics_sensor_lag_trend_empty),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            return@AnalyticsSectionCard
        }

        val values = points.map { it.value.toFloat() }
        val minTs = points.first().ts
        val maxTs = max(minTs + 1L, points.last().ts)
        val rawMin = values.minOrNull() ?: 0f
        val rawMax = values.maxOrNull() ?: 0f
        val anchoredMin = if (includeZeroBaseline) min(rawMin, 0f) else rawMin
        val anchoredMax = if (includeZeroBaseline) max(rawMax, 0f) else rawMax
        val rawSpan = max(0.0001f, anchoredMax - anchoredMin)
        val minValue = anchoredMin - rawSpan * 0.1f
        val maxValue = anchoredMax + rawSpan * 0.1f
        val span = max(0.0001f, maxValue - minValue)
        val gridColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.18f)
        val zeroLineColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
        val latestFormatted = points.lastOrNull()?.value?.let(valueFormatter) ?: "--"
        val contentDescription = stringResource(
            id = R.string.analytics_sensor_lag_trend_accessibility,
            title,
            points.size,
            latestFormatted
        )

        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(chartHeightDp)
                .semantics { this.contentDescription = contentDescription }
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
            if (includeZeroBaseline && minValue <= 0f && maxValue >= 0f) {
                val zeroRatio = (0f - minValue) / span
                val zeroY = chartHeight - zeroRatio * chartHeight
                drawLine(
                    color = zeroLineColor,
                    start = Offset(0f, zeroY),
                    end = Offset(chartWidth, zeroY),
                    strokeWidth = 1f,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f))
                )
            }
            fun xFor(ts: Long): Float {
                val ratio = (ts - minTs).toFloat() / (maxTs - minTs).toFloat()
                return ratio.coerceIn(0f, 1f) * chartWidth
            }
            fun yFor(value: Float): Float {
                val ratio = (value - minValue) / span
                return chartHeight - ratio * chartHeight
            }
            val path = Path()
            points.forEachIndexed { index, point ->
                val x = xFor(point.ts)
                val y = yFor(point.value.toFloat())
                if (index == 0) {
                    path.moveTo(x, y)
                } else {
                    path.lineTo(x, y)
                }
            }
            drawPath(
                path = path,
                color = lineColor,
                style = Stroke(width = 4f, cap = StrokeCap.Round)
            )
            points.lastOrNull()?.let { point ->
                drawCircle(
                    color = lineColor,
                    radius = 5f,
                    center = Offset(xFor(point.ts), yFor(point.value.toFloat()))
                )
            }
        }
    }
}

@Composable
private fun SensorLagTrendDetailDialog(
    diagnostics: SensorLagDiagnosticsUi,
    currentBucket: String?,
    currentGuidance: SensorLagRolloutGuidance?,
    rolloutGuidance: List<SensorLagRolloutGuidance>,
    onSensorLagCorrectionModeChange: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val adjacentGuidance = remember(currentBucket, rolloutGuidance) {
        if (currentBucket == null) {
            emptyList()
        } else {
            val currentOrder = sensorLagBucketOrder(currentBucket)
            rolloutGuidance
                .filter { guidance ->
                    guidance.bucket != currentBucket &&
                        kotlin.math.abs(sensorLagBucketOrder(guidance.bucket) - currentOrder) == 1
                }
                .sortedBy { sensorLagBucketOrder(it.bucket) }
        }
    }
    val trendWindowLine = if (
        diagnostics.trendStartAgeHours != null &&
        diagnostics.trendEndAgeHours != null
    ) {
        stringResource(
            id = R.string.analytics_sensor_lag_trend_window_line,
            "${UiFormatters.formatDecimalOrPlaceholder(diagnostics.trendStartAgeHours, 0)}h",
            "${UiFormatters.formatDecimalOrPlaceholder(diagnostics.trendEndAgeHours, 0)}h"
        )
    } else {
        stringResource(id = R.string.analytics_sensor_lag_trend_window_line_fallback)
    }
    val shadowActionEnabled = diagnostics.configuredMode.equals("SHADOW", ignoreCase = true).not()
    val activeActionEnabled = diagnostics.configuredMode.equals("ACTIVE", ignoreCase = true).not() &&
        currentGuidance?.status == SensorLagRolloutStatus.ACTIVE_CANDIDATE &&
        diagnostics.disableReason.isNullOrBlank()
    val rolloutActionHint = when {
        diagnostics.configuredMode.equals("ACTIVE", ignoreCase = true) ->
            stringResource(id = R.string.analytics_sensor_lag_action_active_already)
        activeActionEnabled ->
            stringResource(id = R.string.analytics_sensor_lag_action_active_ready)
        !diagnostics.disableReason.isNullOrBlank() ->
            stringResource(
                id = R.string.analytics_sensor_lag_action_active_blocked_gate,
                diagnostics.disableReason.replace('_', ' ')
            )
        currentGuidance?.status != SensorLagRolloutStatus.ACTIVE_CANDIDATE ->
            stringResource(id = R.string.analytics_sensor_lag_action_active_blocked_guidance)
        else ->
            stringResource(id = R.string.analytics_sensor_lag_action_shadow_hint)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(id = R.string.analytics_sensor_lag_detail_title)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm)
            ) {
                Text(
                    text = stringResource(id = R.string.analytics_sensor_lag_action_title),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
                    verticalArrangement = Arrangement.spacedBy(Spacing.xs)
                ) {
                    OutlinedButton(
                        enabled = shadowActionEnabled,
                        onClick = {
                            onSensorLagCorrectionModeChange("SHADOW")
                            onDismiss()
                        }
                    ) {
                        Text(text = stringResource(id = R.string.analytics_sensor_lag_action_shadow))
                    }
                    FilledTonalButton(
                        enabled = activeActionEnabled,
                        onClick = {
                            onSensorLagCorrectionModeChange("ACTIVE")
                            onDismiss()
                        }
                    ) {
                        Text(text = stringResource(id = R.string.analytics_sensor_lag_action_active))
                    }
                }
                SensorLagSummaryCard(text = rolloutActionHint)
                Text(
                    text = stringResource(id = R.string.analytics_sensor_lag_replay_guidance_title),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (currentGuidance != null) {
                    SensorLagRolloutGuidanceCard(guidance = currentGuidance)
                } else {
                    SensorLagSummaryCard(
                        text = if (currentBucket != null) {
                            stringResource(
                                id = R.string.analytics_sensor_lag_replay_guidance_missing_bucket,
                                sensorLagBucketDisplayName(currentBucket)
                            )
                        } else {
                            stringResource(id = R.string.analytics_sensor_lag_replay_guidance_missing)
                        }
                    )
                }
                SensorLagRelativeSummaryCard(
                    currentBucket = currentBucket,
                    currentGuidance = currentGuidance,
                    adjacentGuidance = adjacentGuidance
                )
                Text(
                    text = stringResource(id = R.string.analytics_sensor_lag_adjacent_guidance_title),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (adjacentGuidance.isNotEmpty()) {
                    adjacentGuidance.forEach { guidance ->
                        SensorLagRolloutGuidanceCard(guidance = guidance)
                    }
                } else {
                    SensorLagSummaryCard(
                        text = if (currentBucket != null) {
                            stringResource(
                                id = R.string.analytics_sensor_lag_adjacent_guidance_missing_bucket,
                                sensorLagBucketDisplayName(currentBucket)
                            )
                        } else {
                            stringResource(id = R.string.analytics_sensor_lag_adjacent_guidance_missing)
                        }
                    )
                }
                Text(
                    text = trendWindowLine,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (diagnostics.lagTrendPoints.size >= 2) {
                    SensorLagTrendSparkline(
                        title = stringResource(id = R.string.analytics_sensor_lag_trend_lag_title),
                        points = diagnostics.lagTrendPoints,
                        lineColor = MaterialTheme.colorScheme.primary,
                        valueFormatter = { value ->
                            "${UiFormatters.formatDecimalOrPlaceholder(value, 0)}m"
                        },
                        chartHeightDp = 184.dp
                    )
                }
                if (diagnostics.correctionTrendPoints.size >= 2) {
                    SensorLagTrendSparkline(
                        title = stringResource(id = R.string.analytics_sensor_lag_trend_correction_title),
                        points = diagnostics.correctionTrendPoints,
                        lineColor = MaterialTheme.colorScheme.tertiary,
                        valueFormatter = { value ->
                            UiFormatters.formatSignedDelta(value)
                        },
                        includeZeroBaseline = true,
                        chartHeightDp = 184.dp
                    )
                }
                if (diagnostics.modeSegments.isNotEmpty()) {
                    SensorLagTimelineBand(
                        title = stringResource(id = R.string.analytics_sensor_lag_timeline_mode_title),
                        segments = diagnostics.modeSegments,
                        kind = SensorLagTimelineKind.MODE
                    )
                }
                if (diagnostics.bucketSegments.isNotEmpty()) {
                    SensorLagTimelineBand(
                        title = stringResource(id = R.string.analytics_sensor_lag_timeline_bucket_title),
                        segments = diagnostics.bucketSegments,
                        kind = SensorLagTimelineKind.BUCKET
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(id = R.string.action_close))
            }
        }
    )
}

@Composable
private fun SensorLagTimelineBand(
    title: String,
    segments: List<SensorLagTimelineSegmentUi>,
    kind: SensorLagTimelineKind
) {
    if (segments.isEmpty()) return
    val sortedSegments = segments
        .filter { it.endTs > it.startTs }
        .sortedBy { it.startTs }
    if (sortedSegments.isEmpty()) return
    val bandStartTs = sortedSegments.minOf { it.startTs }
    val bandEndTs = max(bandStartTs + 1L, sortedSegments.maxOf { it.endTs })
    val totalDuration = sortedSegments.sumOf { (it.endTs - it.startTs).coerceAtLeast(0L) }.coerceAtLeast(1L)
    val outlineTone = MaterialTheme.colorScheme.outline.copy(alpha = 0.55f)
    val fallbackTone = MaterialTheme.colorScheme.tertiary
    val secondaryTone = MaterialTheme.colorScheme.secondary
    fun colorForLabel(raw: String): Color {
        return when (kind) {
            SensorLagTimelineKind.MODE -> when (raw.uppercase(Locale.US)) {
                "ACTIVE" -> Color(0xFF2E7D32)
                "SHADOW" -> Color(0xFF1565C0)
                "OFF" -> outlineTone
                else -> fallbackTone
            }
            SensorLagTimelineKind.BUCKET -> when (raw) {
                "<24h" -> Color(0xFF4C78A8)
                "1-10d" -> Color(0xFF72B7B2)
                "10-12d" -> Color(0xFFF2CF5B)
                "12-14d" -> Color(0xFFF58518)
                ">14d" -> Color(0xFFE45756)
                else -> secondaryTone
            }
        }
    }
    val aggregatedDurations = linkedMapOf<String, Long>()
    sortedSegments.forEach { segment ->
        aggregatedDurations[segment.label] =
            aggregatedDurations.getOrDefault(segment.label, 0L) + (segment.endTs - segment.startTs).coerceAtLeast(0L)
    }
    AnalyticsSectionCard {
        Text(
            text = title,
            style = MaterialTheme.typography.labelMedium
        )
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(30.dp)
        ) {
            val chartWidth = size.width.coerceAtLeast(1f)
            val chartHeight = size.height.coerceAtLeast(1f)
            sortedSegments.forEach { segment ->
                val startRatio = ((segment.startTs - bandStartTs).toDouble() / (bandEndTs - bandStartTs).toDouble())
                    .coerceIn(0.0, 1.0)
                val endRatio = ((segment.endTs - bandStartTs).toDouble() / (bandEndTs - bandStartTs).toDouble())
                    .coerceIn(0.0, 1.0)
                val left = (chartWidth * startRatio).toFloat()
                val right = (chartWidth * endRatio).toFloat().coerceAtLeast(left + 2f)
                drawRect(
                    color = colorForLabel(segment.label),
                    topLeft = Offset(left, 0f),
                    size = Size(right - left, chartHeight)
                )
            }
        }
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(Spacing.xxs),
            verticalArrangement = Arrangement.spacedBy(Spacing.xxs)
        ) {
            aggregatedDurations.forEach { (label, duration) ->
                val sharePct = duration.toDouble() / totalDuration.toDouble() * 100.0
                SensorLagTimelineLegendChip(
                    text = stringResource(
                        id = R.string.analytics_sensor_lag_timeline_legend_share,
                        sensorLagTimelineDisplayName(label, kind),
                        String.format(Locale.US, "%.0f%%", sharePct)
                    ),
                    color = colorForLabel(label)
                )
            }
        }
    }
}

@Composable
private fun SensorLagTimelineLegendChip(
    text: String,
    color: Color
) {
    Surface(
        shape = AnalyticsInfoShape,
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = Spacing.sm, vertical = Spacing.xxs),
            horizontalArrangement = Arrangement.spacedBy(Spacing.xxs),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                modifier = Modifier
                    .width(10.dp)
                    .height(10.dp),
                shape = RoundedCornerShape(999.dp),
                color = color,
                content = {}
            )
            Text(
                text = text,
                style = MaterialTheme.typography.labelMedium
            )
        }
    }
}

@Composable
private fun sensorLagTimelineDisplayName(
    raw: String,
    kind: SensorLagTimelineKind
): String {
    return when (kind) {
        SensorLagTimelineKind.MODE -> raw.uppercase(Locale.US)
        SensorLagTimelineKind.BUCKET -> sensorLagBucketDisplayName(raw)
    }
}

@Composable
private fun DailyForecastReportCard(
    state: AnalyticsUiState
) {
    val matched = state.dailyReportMatchedSamples?.toString() ?: "--"
    val rows = state.dailyReportForecastRows?.toString() ?: "--"
    AnalyticsSectionCard {
        AnalyticsSectionLabel(text = stringResource(id = R.string.section_analytics_daily_report))
        state.dailyReportGeneratedAtTs?.let { generatedTs ->
            Text(
                text = stringResource(
                    id = R.string.analytics_daily_report_generated,
                    formatHistoryTs(generatedTs)
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Text(
            text = stringResource(id = R.string.analytics_daily_report_counts, matched, rows),
            style = MaterialTheme.typography.bodyMedium
        )
        val periodStart = state.dailyReportPeriodStartUtc ?: "--"
        val periodEnd = state.dailyReportPeriodEndUtc ?: "--"
        Text(
            text = stringResource(id = R.string.analytics_daily_report_period, periodStart, periodEnd),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (state.dailyReportHorizonStats.isNotEmpty()) {
            state.dailyReportHorizonStats
                .sortedBy { it.horizonMinutes }
                .forEach { stat ->
                    val mae = UiFormatters.formatMmol(stat.mae, decimals = 2)
                    val rmse = UiFormatters.formatMmol(stat.rmse, decimals = 2)
                    val mard = stat.mardPct?.let { String.format(Locale.US, "%.1f%%", it) } ?: "--"
                    val bias = UiFormatters.formatSignedDelta(stat.bias)
                    val sampleCount = stat.sampleCount?.toString() ?: "--"
                    val ciCoverage = stat.ciCoveragePct?.let { String.format(Locale.US, "%.1f%%", it) } ?: "--"
                    val ciWidth = UiFormatters.formatMmol(stat.ciMeanWidth, decimals = 2)
                    Surface(
                        shape = AnalyticsInfoShape,
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                    ) {
                        Column(
                            modifier = Modifier.padding(Spacing.sm),
                            verticalArrangement = Arrangement.spacedBy(Spacing.xxs)
                        ) {
                            Text(
                                text = stringResource(
                                    id = R.string.analytics_daily_report_metric_line,
                                    stat.horizonMinutes,
                                    mae,
                                    rmse,
                                    mard,
                                    bias,
                                    sampleCount
                                ),
                                style = MaterialTheme.typography.bodySmall
                            )
                            Text(
                                text = stringResource(
                                    id = R.string.analytics_daily_report_ci_line,
                                    ciCoverage,
                                    ciWidth
                                ),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
        }
        if (
            state.dailyReportReplayTopFactorsOverall != null ||
            state.dailyReportReplayHotspots.isNotEmpty() ||
            state.dailyReportReplayFactorContributions.isNotEmpty() ||
            state.dailyReportReplayFactorRegimes.isNotEmpty() ||
            state.dailyReportReplayFactorPairs.isNotEmpty() ||
            state.dailyReportReplayTopMisses.isNotEmpty() ||
            state.dailyReportReplayErrorClusters.isNotEmpty() ||
            state.dailyReportReplayDayTypeGaps.isNotEmpty()
        ) {
            Text(
                text = stringResource(id = R.string.analytics_daily_report_replay_title),
                style = MaterialTheme.typography.labelMedium
            )
            state.dailyReportReplayTopFactorsOverall
                ?.takeIf { it.isNotBlank() }
                ?.let { overall ->
                    Surface(
                        shape = AnalyticsInfoShape,
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                    ) {
                        Text(
                            text = stringResource(id = R.string.analytics_daily_report_replay_overall, overall),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(Spacing.sm)
                        )
                    }
                }
            state.dailyReportReplayHotspots
                .sortedWith(compareBy<DailyReportReplayHotspotUi> { it.horizonMinutes }.thenByDescending { it.mae })
                .take(9)
                .forEach { hotspot ->
                    Surface(
                        shape = AnalyticsInfoShape,
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                    ) {
                        Text(
                            text = stringResource(
                                id = R.string.analytics_daily_report_replay_hotspot_line,
                                hotspot.horizonMinutes,
                                hotspot.hour.toString().padStart(2, '0'),
                                UiFormatters.formatMmol(hotspot.mae, decimals = 2),
                                String.format(Locale.US, "%.1f%%", hotspot.mardPct),
                                UiFormatters.formatSignedDelta(hotspot.bias),
                                hotspot.sampleCount.toString()
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(Spacing.sm)
                        )
                    }
                }
            listOf(5, 30, 60).forEach { horizon ->
                state.dailyReportReplayFactorContributions
                    .asSequence()
                    .filter { it.horizonMinutes == horizon }
                    .sortedByDescending { it.contributionScore }
                    .take(4)
                    .forEach { factor ->
                        Surface(
                            shape = AnalyticsInfoShape,
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                        ) {
                            Text(
                                text = stringResource(
                                    id = R.string.analytics_daily_report_replay_factor_line,
                                    factor.horizonMinutes,
                                    replayFactorDisplayName(factor.factor),
                                    String.format(Locale.US, "%.3f", factor.contributionScore),
                                    String.format(Locale.US, "%.3f", factor.corrAbsError),
                                    String.format(Locale.US, "%.1f%%", factor.upliftPct),
                                    factor.sampleCount.toString()
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(Spacing.sm)
                            )
                        }
                    }
            }
            if (state.dailyReportReplayFactorCoverage.isNotEmpty()) {
                state.dailyReportReplayFactorCoverage
                    .asSequence()
                    .filter { it.factor in setOf("COB", "IOB", "UAM", "CI") }
                    .sortedWith(
                        compareBy<DailyReportReplayCoverageUi> { it.horizonMinutes }
                            .thenBy { it.factor }
                    )
                    .forEach { coverage ->
                        Surface(
                            shape = AnalyticsInfoShape,
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                        ) {
                            Text(
                                text = stringResource(
                                    id = R.string.analytics_daily_report_replay_coverage_line,
                                    coverage.horizonMinutes,
                                    replayFactorDisplayName(coverage.factor),
                                    String.format(Locale.US, "%.1f%%", coverage.coveragePct),
                                    coverage.sampleCount.toString()
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(Spacing.sm)
                            )
                        }
                    }
            }
            if (state.dailyReportReplayFactorRegimes.isNotEmpty()) {
                state.dailyReportReplayFactorRegimes
                    .asSequence()
                    .filter { it.factor in setOf("COB", "IOB", "UAM", "CI") }
                    .sortedWith(
                        compareBy<DailyReportReplayRegimeUi> { it.horizonMinutes }
                            .thenBy { it.factor }
                            .thenBy {
                                when (it.bucket) {
                                    "LOW" -> 0
                                    "MID" -> 1
                                    "HIGH" -> 2
                                    else -> 3
                                }
                            }
                    )
                    .forEach { regime ->
                        Surface(
                            shape = AnalyticsInfoShape,
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                        ) {
                            Text(
                                text = stringResource(
                                    id = R.string.analytics_daily_report_replay_regime_line,
                                    regime.horizonMinutes,
                                    replayFactorDisplayName(regime.factor),
                                    replayRegimeDisplayName(regime.bucket),
                                    UiFormatters.formatDecimalOrPlaceholder(regime.meanFactorValue, decimals = 2),
                                    UiFormatters.formatMmol(regime.mae, decimals = 2),
                                    String.format(Locale.US, "%.1f%%", regime.mardPct),
                                    UiFormatters.formatSignedDelta(regime.bias),
                                    regime.sampleCount.toString()
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(Spacing.sm)
                            )
                        }
                    }
            }
            if (state.dailyReportReplayFactorPairs.isNotEmpty()) {
                state.dailyReportReplayFactorPairs
                    .asSequence()
                    .filter { it.factorA in setOf("COB", "IOB", "UAM", "CI") && it.factorB in setOf("COB", "IOB", "UAM", "CI") }
                    .sortedWith(
                        compareBy<DailyReportReplayPairUi> { it.horizonMinutes }
                            .thenBy { it.factorA }
                            .thenBy { it.factorB }
                            .thenBy {
                                when (it.bucketA) {
                                    "LOW" -> 0
                                    "HIGH" -> 1
                                    else -> 2
                                }
                            }
                            .thenBy {
                                when (it.bucketB) {
                                    "LOW" -> 0
                                    "HIGH" -> 1
                                    else -> 2
                                }
                            }
                    )
                    .forEach { pair ->
                        Surface(
                            shape = AnalyticsInfoShape,
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                        ) {
                            Text(
                                text = stringResource(
                                    id = R.string.analytics_daily_report_replay_pair_line,
                                    pair.horizonMinutes,
                                    replayFactorDisplayName(pair.factorA),
                                    replayFactorDisplayName(pair.factorB),
                                    replayRegimeDisplayName(pair.bucketA),
                                    replayRegimeDisplayName(pair.bucketB),
                                    UiFormatters.formatDecimalOrPlaceholder(pair.meanFactorA, decimals = 2),
                                    UiFormatters.formatDecimalOrPlaceholder(pair.meanFactorB, decimals = 2),
                                    UiFormatters.formatMmol(pair.mae, decimals = 2),
                                    String.format(Locale.US, "%.1f%%", pair.mardPct),
                                    UiFormatters.formatSignedDelta(pair.bias),
                                    pair.sampleCount.toString()
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(Spacing.sm)
                            )
                        }
                    }
            }
            if (state.dailyReportReplayTopMisses.isNotEmpty()) {
                state.dailyReportReplayTopMisses
                    .sortedBy { it.horizonMinutes }
                    .forEach { miss ->
                        Surface(
                            shape = AnalyticsInfoShape,
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                        ) {
                            Text(
                                text = stringResource(
                                    id = R.string.analytics_daily_report_replay_top_miss_line,
                                    miss.horizonMinutes,
                                    formatHistoryTs(miss.ts),
                                    UiFormatters.formatMmol(miss.absError, decimals = 2),
                                    UiFormatters.formatMmol(miss.pred, decimals = 2),
                                    UiFormatters.formatMmol(miss.actual, decimals = 2),
                                    String.format(Locale.US, "%.1f", miss.cob),
                                    String.format(Locale.US, "%.1f", miss.iob),
                                    String.format(Locale.US, "%.2f", miss.uam),
                                    UiFormatters.formatMmol(miss.ciWidth, decimals = 2),
                                    String.format(Locale.US, "%.1f", miss.diaHours),
                                    String.format(Locale.US, "%.2f", miss.activity),
                                    String.format(Locale.US, "%.2f", miss.sensorQuality)
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(Spacing.sm)
                            )
                        }
                    }
            }
            if (state.dailyReportReplayErrorClusters.isNotEmpty()) {
                state.dailyReportReplayErrorClusters
                    .sortedWith(
                        compareBy<DailyReportReplayErrorClusterUi> { it.horizonMinutes }
                            .thenByDescending { it.mae }
                    )
                    .forEach { cluster ->
                        Surface(
                            shape = AnalyticsInfoShape,
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                        ) {
                            val dominant = cluster.dominantFactor
                                ?.takeIf { it.isNotBlank() }
                                ?.let { replayFactorDisplayName(it) }
                                ?: "--"
                            Text(
                                text = stringResource(
                                    id = R.string.analytics_daily_report_replay_error_cluster_line,
                                    cluster.horizonMinutes,
                                    replayDayTypeDisplayName(cluster.dayType),
                                    cluster.hour.toString().padStart(2, '0'),
                                    UiFormatters.formatMmol(cluster.mae, decimals = 2),
                                    String.format(Locale.US, "%.1f%%", cluster.mardPct),
                                    UiFormatters.formatSignedDelta(cluster.bias),
                                    UiFormatters.formatGrams(cluster.meanCob, decimals = 1),
                                    UiFormatters.formatUnits(cluster.meanIob, decimals = 1),
                                    UiFormatters.formatDecimalOrPlaceholder(cluster.meanUam, decimals = 2),
                                    UiFormatters.formatMmol(cluster.meanCiWidth, decimals = 2),
                                    dominant,
                                    cluster.sampleCount.toString()
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(Spacing.sm)
                            )
                        }
                    }
            }
            if (state.dailyReportReplayDayTypeGaps.isNotEmpty()) {
                state.dailyReportReplayDayTypeGaps
                    .sortedWith(
                        compareBy<DailyReportReplayDayTypeGapUi> { it.horizonMinutes }
                            .thenByDescending { kotlin.math.abs(it.maeGapMmol) }
                    )
                    .forEach { gap ->
                        Surface(
                            shape = AnalyticsInfoShape,
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                        ) {
                            val dominant = gap.dominantFactor
                                ?.takeIf { it.isNotBlank() }
                                ?.let { replayFactorDisplayName(it) }
                                ?: "--"
                            Text(
                                text = stringResource(
                                    id = R.string.analytics_daily_report_replay_daytype_gap_line,
                                    gap.horizonMinutes,
                                    replayDayTypeDisplayName(gap.worseDayType),
                                    gap.hour.toString().padStart(2, '0'),
                                    UiFormatters.formatMmol(gap.maeGapMmol, decimals = 2),
                                    UiFormatters.formatMmol(gap.weekdayMae, decimals = 2),
                                    UiFormatters.formatMmol(gap.weekendMae, decimals = 2),
                                    String.format(Locale.US, "%.1f%%", gap.mardGapPct),
                                    UiFormatters.formatGrams(gap.worseMeanCob, decimals = 1),
                                    UiFormatters.formatUnits(gap.worseMeanIob, decimals = 1),
                                    UiFormatters.formatDecimalOrPlaceholder(gap.worseMeanUam, decimals = 2),
                                    UiFormatters.formatMmol(gap.worseMeanCiWidth, decimals = 2),
                                    dominant,
                                    (gap.weekdaySampleCount + gap.weekendSampleCount).toString()
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(Spacing.sm)
                            )
                    }
                }
            }
        }
        if (
            state.dailyReportSensorLagReplayBuckets.isNotEmpty() ||
            state.dailyReportSensorLagShadowBuckets.isNotEmpty()
        ) {
            Text(
                text = stringResource(id = R.string.analytics_daily_report_sensor_lag_title),
                style = MaterialTheme.typography.labelMedium
            )
            val bestReplayBucket = state.dailyReportSensorLagReplayBuckets
                .filter { it.sampleCount > 0 && it.maeImprovementMmol > 0.0 }
                .maxWithOrNull(
                    compareBy<DailyReportSensorLagReplayUi> { it.maeImprovementMmol }
                        .thenBy { it.sampleCount }
                )
            if (state.dailyReportSensorLagReplayBuckets.isNotEmpty()) {
                SensorLagSummaryCard(
                    text = if (bestReplayBucket != null) {
                        stringResource(
                            id = R.string.analytics_daily_report_sensor_lag_best_gain,
                            bestReplayBucket.horizonMinutes,
                            sensorLagBucketDisplayName(bestReplayBucket.bucket),
                            UiFormatters.formatMmol(bestReplayBucket.rawMae, decimals = 2),
                            UiFormatters.formatMmol(bestReplayBucket.lagMae, decimals = 2),
                            UiFormatters.formatSignedDelta(bestReplayBucket.maeImprovementMmol),
                            bestReplayBucket.sampleCount.toString()
                        )
                    } else {
                        stringResource(id = R.string.analytics_daily_report_sensor_lag_no_gain)
                    }
                )
            }
            val topShadowBucket = state.dailyReportSensorLagShadowBuckets
                .filter { it.sampleCount > 0 }
                .maxWithOrNull(
                    compareBy<DailyReportSensorLagShadowUi> { it.ruleChangedRatePct }
                        .thenBy { it.meanAbsTargetDeltaMmol ?: 0.0 }
                        .thenBy { it.sampleCount }
                )
            if (topShadowBucket != null) {
                SensorLagSummaryCard(
                    text = stringResource(
                        id = R.string.analytics_daily_report_sensor_lag_top_shadow,
                        sensorLagBucketDisplayName(topShadowBucket.bucket),
                        String.format(Locale.US, "%.1f%%", topShadowBucket.ruleChangedRatePct),
                        UiFormatters.formatMmol(topShadowBucket.meanAbsTargetDeltaMmol, decimals = 2),
                        topShadowBucket.sampleCount.toString()
                    )
                )
            }
            if (state.dailyReportSensorLagReplayBuckets.isNotEmpty()) {
                Text(
                    text = stringResource(id = R.string.analytics_daily_report_sensor_lag_replay_subtitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                state.dailyReportSensorLagReplayBuckets
                    .sortedWith(
                        compareBy<DailyReportSensorLagReplayUi> { it.horizonMinutes }
                            .thenBy { sensorLagBucketOrder(it.bucket) }
                    )
                    .forEach { bucket ->
                        SensorLagReplayBucketCard(bucket = bucket)
                    }
            }
            if (state.dailyReportSensorLagShadowBuckets.isNotEmpty()) {
                Text(
                    text = stringResource(id = R.string.analytics_daily_report_sensor_lag_shadow_subtitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                state.dailyReportSensorLagShadowBuckets
                    .sortedBy { sensorLagBucketOrder(it.bucket) }
                    .forEach { bucket ->
                        SensorLagShadowBucketCard(bucket = bucket)
                    }
            }
            val rolloutGuidance = buildSensorLagRolloutGuidance(
                replayBuckets = state.dailyReportSensorLagReplayBuckets,
                shadowBuckets = state.dailyReportSensorLagShadowBuckets
            )
            if (rolloutGuidance.isNotEmpty()) {
                Text(
                    text = stringResource(id = R.string.analytics_daily_report_sensor_lag_rollout_title),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                rolloutGuidance.forEach { guidance ->
                    SensorLagRolloutGuidanceCard(guidance = guidance)
                }
            }
        }
        state.dailyReportMarkdownPath
            ?.takeIf { it.isNotBlank() }
            ?.let { path ->
                Text(
                    text = stringResource(id = R.string.analytics_daily_report_path, path),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        if (state.dailyReportRecommendations.isNotEmpty()) {
            Text(
                text = stringResource(id = R.string.analytics_daily_report_recommendations),
                style = MaterialTheme.typography.labelMedium
            )
            state.dailyReportRecommendations.forEach { recommendation ->
                Surface(
                    shape = AnalyticsInfoShape,
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                ) {
                    Text(
                        text = recommendation,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(Spacing.sm)
                    )
                }
            }
        }
        if (state.dailyReportIsfCrQualityLines.isNotEmpty()) {
            Text(
                text = stringResource(id = R.string.analytics_daily_report_isfcr_quality),
                style = MaterialTheme.typography.labelMedium
            )
            state.dailyReportIsfCrQualityLines.forEach { line ->
                Surface(
                    shape = AnalyticsInfoShape,
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                ) {
                    Text(
                        text = line,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(Spacing.sm)
                    )
                }
            }
        }
    }
}

private enum class AnalyticsMetricTone {
    DEFAULT,
    POSITIVE,
    NEGATIVE
}

internal enum class SensorLagRolloutStatus {
    ACTIVE_CANDIDATE,
    SHADOW_FIRST,
    HOLD,
    INSUFFICIENT_DATA
}

internal data class SensorLagRolloutGuidance(
    val bucket: String,
    val status: SensorLagRolloutStatus,
    val weightedGainMmol: Double?,
    val replaySamples: Int,
    val shadowRatePct: Double?,
    val meanAbsTargetDeltaMmol: Double?
)

@Composable
private fun SensorLagSummaryCard(
    text: String
) {
    Surface(
        shape = AnalyticsInfoShape,
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(Spacing.sm)
        )
    }
}

@Composable
private fun SensorLagReplayBucketCard(
    bucket: DailyReportSensorLagReplayUi
) {
    val gainTone = when {
        bucket.maeImprovementMmol > 0.03 -> AnalyticsMetricTone.POSITIVE
        bucket.maeImprovementMmol < -0.03 -> AnalyticsMetricTone.NEGATIVE
        else -> AnalyticsMetricTone.DEFAULT
    }
    Surface(
        shape = AnalyticsInfoShape,
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(
            modifier = Modifier.padding(Spacing.sm),
            verticalArrangement = Arrangement.spacedBy(Spacing.xxs)
        ) {
            Text(
                text = stringResource(
                    id = R.string.analytics_daily_report_sensor_lag_bucket_header,
                    bucket.horizonMinutes,
                    sensorLagBucketDisplayName(bucket.bucket)
                ),
                style = MaterialTheme.typography.labelMedium
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(Spacing.xxs),
                verticalArrangement = Arrangement.spacedBy(Spacing.xxs)
            ) {
                AnalyticsMetricChip(
                    label = stringResource(id = R.string.analytics_daily_report_sensor_lag_metric_raw_mae),
                    value = UiFormatters.formatMmol(bucket.rawMae, decimals = 2)
                )
                AnalyticsMetricChip(
                    label = stringResource(id = R.string.analytics_daily_report_sensor_lag_metric_lag_mae),
                    value = UiFormatters.formatMmol(bucket.lagMae, decimals = 2)
                )
                AnalyticsMetricChip(
                    label = stringResource(id = R.string.analytics_daily_report_sensor_lag_metric_gain),
                    value = UiFormatters.formatSignedDelta(bucket.maeImprovementMmol),
                    tone = gainTone
                )
                AnalyticsMetricChip(
                    label = stringResource(id = R.string.analytics_daily_report_sensor_lag_metric_raw_bias),
                    value = UiFormatters.formatSignedDelta(bucket.rawBias)
                )
                AnalyticsMetricChip(
                    label = stringResource(id = R.string.analytics_daily_report_sensor_lag_metric_lag_bias),
                    value = UiFormatters.formatSignedDelta(bucket.lagBias)
                )
                AnalyticsMetricChip(
                    label = stringResource(id = R.string.analytics_daily_report_sensor_lag_metric_samples),
                    value = bucket.sampleCount.toString()
                )
            }
        }
    }
}

@Composable
private fun SensorLagShadowBucketCard(
    bucket: DailyReportSensorLagShadowUi
) {
    val shadowTone = when {
        bucket.ruleChangedRatePct >= 20.0 -> AnalyticsMetricTone.POSITIVE
        bucket.ruleChangedRatePct <= 5.0 -> AnalyticsMetricTone.DEFAULT
        else -> AnalyticsMetricTone.POSITIVE
    }
    Surface(
        shape = AnalyticsInfoShape,
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(
            modifier = Modifier.padding(Spacing.sm),
            verticalArrangement = Arrangement.spacedBy(Spacing.xxs)
        ) {
            Text(
                text = sensorLagBucketDisplayName(bucket.bucket),
                style = MaterialTheme.typography.labelMedium
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(Spacing.xxs),
                verticalArrangement = Arrangement.spacedBy(Spacing.xxs)
            ) {
                AnalyticsMetricChip(
                    label = stringResource(id = R.string.analytics_daily_report_sensor_lag_metric_rule_change),
                    value = String.format(Locale.US, "%.1f%%", bucket.ruleChangedRatePct),
                    tone = shadowTone
                )
                AnalyticsMetricChip(
                    label = stringResource(id = R.string.analytics_daily_report_sensor_lag_metric_target_delta),
                    value = UiFormatters.formatMmol(bucket.meanAbsTargetDeltaMmol, decimals = 2)
                )
                AnalyticsMetricChip(
                    label = stringResource(id = R.string.analytics_daily_report_sensor_lag_metric_samples),
                    value = bucket.sampleCount.toString()
                )
            }
        }
    }
}

@Composable
private fun AnalyticsMetricChip(
    label: String,
    value: String,
    tone: AnalyticsMetricTone = AnalyticsMetricTone.DEFAULT
) {
    val background = when (tone) {
        AnalyticsMetricTone.DEFAULT -> MaterialTheme.colorScheme.surface
        AnalyticsMetricTone.POSITIVE -> MaterialTheme.colorScheme.tertiaryContainer
        AnalyticsMetricTone.NEGATIVE -> MaterialTheme.colorScheme.errorContainer
    }
    val contentColor = when (tone) {
        AnalyticsMetricTone.DEFAULT -> MaterialTheme.colorScheme.onSurface
        AnalyticsMetricTone.POSITIVE -> MaterialTheme.colorScheme.onTertiaryContainer
        AnalyticsMetricTone.NEGATIVE -> MaterialTheme.colorScheme.onErrorContainer
    }
    val borderColor = when (tone) {
        AnalyticsMetricTone.DEFAULT -> MaterialTheme.colorScheme.outlineVariant
        AnalyticsMetricTone.POSITIVE -> MaterialTheme.colorScheme.tertiary
        AnalyticsMetricTone.NEGATIVE -> MaterialTheme.colorScheme.error
    }
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = background,
        border = BorderStroke(1.dp, borderColor)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = Spacing.sm, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(Spacing.xxs),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = contentColor.copy(alpha = 0.78f)
            )
            Text(
                text = value,
                style = MaterialTheme.typography.labelSmall,
                color = contentColor
            )
        }
    }
}

internal fun buildSensorLagRolloutGuidance(
    replayBuckets: List<DailyReportSensorLagReplayUi>,
    shadowBuckets: List<DailyReportSensorLagShadowUi>
): List<SensorLagRolloutGuidance> {
    val buckets = (replayBuckets.map { it.bucket } + shadowBuckets.map { it.bucket })
        .distinct()
        .sortedBy(::sensorLagBucketOrder)
    return buckets.mapNotNull { bucket ->
        val prioritizedReplay = replayBuckets.filter {
            it.bucket == bucket && it.horizonMinutes in setOf(30, 60)
        }
        val relevantReplay = if (prioritizedReplay.isNotEmpty()) {
            prioritizedReplay
        } else {
            replayBuckets.filter { it.bucket == bucket }
        }
        val replaySamples = relevantReplay.sumOf { it.sampleCount }
        val weightedGain = if (replaySamples > 0) {
            relevantReplay.sumOf { it.maeImprovementMmol * it.sampleCount } / replaySamples.toDouble()
        } else {
            null
        }
        val positiveReplayCount = relevantReplay.count { it.maeImprovementMmol >= 0.05 }
        val negativeReplayCount = relevantReplay.count { it.maeImprovementMmol <= -0.05 }
        val shadow = shadowBuckets.firstOrNull { it.bucket == bucket }
        val shadowRate = shadow?.ruleChangedRatePct
        val status = when {
            replaySamples < 8 -> SensorLagRolloutStatus.INSUFFICIENT_DATA
            weightedGain == null -> SensorLagRolloutStatus.INSUFFICIENT_DATA
            weightedGain >= 0.10 &&
                positiveReplayCount >= max(1, relevantReplay.size - negativeReplayCount) &&
                (shadowRate ?: 0.0) >= 8.0 -> SensorLagRolloutStatus.ACTIVE_CANDIDATE
            weightedGain > 0.03 ||
                ((shadowRate ?: 0.0) >= 8.0 && (weightedGain ?: 0.0) > -0.02) -> SensorLagRolloutStatus.SHADOW_FIRST
            else -> SensorLagRolloutStatus.HOLD
        }
        SensorLagRolloutGuidance(
            bucket = bucket,
            status = status,
            weightedGainMmol = weightedGain,
            replaySamples = replaySamples,
            shadowRatePct = shadowRate,
            meanAbsTargetDeltaMmol = shadow?.meanAbsTargetDeltaMmol
        )
    }
}

internal fun sensorLagAgeBucket(ageHours: Double): String = when {
    ageHours < 24.0 -> "<24h"
    ageHours < 240.0 -> "1-10d"
    ageHours < 288.0 -> "10-12d"
    ageHours < 336.0 -> "12-14d"
    else -> ">14d"
}

private fun sensorLagRolloutStatusRank(status: SensorLagRolloutStatus): Int {
    return when (status) {
        SensorLagRolloutStatus.ACTIVE_CANDIDATE -> 3
        SensorLagRolloutStatus.SHADOW_FIRST -> 2
        SensorLagRolloutStatus.HOLD -> 1
        SensorLagRolloutStatus.INSUFFICIENT_DATA -> 0
    }
}

@Composable
private fun SensorLagRolloutGuidanceCard(
    guidance: SensorLagRolloutGuidance
) {
    val statusTone = when (guidance.status) {
        SensorLagRolloutStatus.ACTIVE_CANDIDATE -> AnalyticsMetricTone.POSITIVE
        SensorLagRolloutStatus.SHADOW_FIRST -> AnalyticsMetricTone.DEFAULT
        SensorLagRolloutStatus.HOLD -> AnalyticsMetricTone.NEGATIVE
        SensorLagRolloutStatus.INSUFFICIENT_DATA -> AnalyticsMetricTone.DEFAULT
    }
    Surface(
        shape = AnalyticsInfoShape,
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(
            modifier = Modifier.padding(Spacing.sm),
            verticalArrangement = Arrangement.spacedBy(Spacing.xxs)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = sensorLagBucketDisplayName(guidance.bucket),
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.weight(1f)
                )
                AnalyticsMetricChip(
                    label = stringResource(id = R.string.analytics_daily_report_sensor_lag_metric_rollout),
                    value = sensorLagRolloutStatusLabel(guidance.status),
                    tone = statusTone
                )
            }
            Text(
                text = sensorLagRolloutReasonText(guidance),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(Spacing.xxs),
                verticalArrangement = Arrangement.spacedBy(Spacing.xxs)
            ) {
                AnalyticsMetricChip(
                    label = stringResource(id = R.string.analytics_daily_report_sensor_lag_metric_weighted_gain),
                    value = UiFormatters.formatSignedDelta(guidance.weightedGainMmol),
                    tone = when {
                        (guidance.weightedGainMmol ?: 0.0) >= 0.10 -> AnalyticsMetricTone.POSITIVE
                        (guidance.weightedGainMmol ?: 0.0) <= 0.0 -> AnalyticsMetricTone.NEGATIVE
                        else -> AnalyticsMetricTone.DEFAULT
                    }
                )
                AnalyticsMetricChip(
                    label = stringResource(id = R.string.analytics_daily_report_sensor_lag_metric_rule_change),
                    value = guidance.shadowRatePct?.let { String.format(Locale.US, "%.1f%%", it) } ?: "--"
                )
                AnalyticsMetricChip(
                    label = stringResource(id = R.string.analytics_daily_report_sensor_lag_metric_target_delta),
                    value = UiFormatters.formatMmol(guidance.meanAbsTargetDeltaMmol, decimals = 2)
                )
                AnalyticsMetricChip(
                    label = stringResource(id = R.string.analytics_daily_report_sensor_lag_metric_samples),
                    value = guidance.replaySamples.toString()
                )
            }
        }
    }
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
private fun replayRegimeDisplayName(raw: String): String {
    return when (raw.uppercase(Locale.US)) {
        "LOW" -> stringResource(id = R.string.analytics_replay_regime_low)
        "MID" -> stringResource(id = R.string.analytics_replay_regime_mid)
        "HIGH" -> stringResource(id = R.string.analytics_replay_regime_high)
        else -> raw
    }
}

private fun sensorLagBucketOrder(raw: String): Int {
    return when (raw) {
        "<24h" -> 0
        "1-10d" -> 1
        "10-12d" -> 2
        "12-14d" -> 3
        ">14d" -> 4
        else -> 5
    }
}

@Composable
private fun sensorLagBucketDisplayName(raw: String): String {
    return when (raw) {
        "<24h" -> stringResource(id = R.string.analytics_daily_report_sensor_lag_bucket_lt24h)
        "1-10d" -> stringResource(id = R.string.analytics_daily_report_sensor_lag_bucket_1_10d)
        "10-12d" -> stringResource(id = R.string.analytics_daily_report_sensor_lag_bucket_10_12d)
        "12-14d" -> stringResource(id = R.string.analytics_daily_report_sensor_lag_bucket_12_14d)
        ">14d" -> stringResource(id = R.string.analytics_daily_report_sensor_lag_bucket_gt14d)
        else -> raw
    }
}

@Composable
private fun sensorLagAgeSourceDisplayName(raw: String): String {
    return when (raw.lowercase(Locale.US)) {
        "explicit" -> stringResource(id = R.string.analytics_sensor_lag_age_source_explicit)
        "inferred" -> stringResource(id = R.string.analytics_sensor_lag_age_source_inferred)
        "unknown" -> stringResource(id = R.string.analytics_sensor_lag_age_source_unknown)
        else -> raw
    }
}

@Composable
private fun sensorLagRolloutStatusLabel(status: SensorLagRolloutStatus): String {
    return when (status) {
        SensorLagRolloutStatus.ACTIVE_CANDIDATE -> stringResource(id = R.string.analytics_daily_report_sensor_lag_rollout_active)
        SensorLagRolloutStatus.SHADOW_FIRST -> stringResource(id = R.string.analytics_daily_report_sensor_lag_rollout_shadow)
        SensorLagRolloutStatus.HOLD -> stringResource(id = R.string.analytics_daily_report_sensor_lag_rollout_hold)
        SensorLagRolloutStatus.INSUFFICIENT_DATA -> stringResource(id = R.string.analytics_daily_report_sensor_lag_rollout_insufficient)
    }
}

@Composable
private fun sensorLagRolloutReasonText(guidance: SensorLagRolloutGuidance): String {
    val gain = UiFormatters.formatSignedDelta(guidance.weightedGainMmol)
    val shadow = guidance.shadowRatePct?.let { String.format(Locale.US, "%.1f%%", it) } ?: "--"
    return when (guidance.status) {
        SensorLagRolloutStatus.ACTIVE_CANDIDATE -> stringResource(
            id = R.string.analytics_daily_report_sensor_lag_rollout_reason_active,
            gain,
            shadow,
            guidance.replaySamples.toString()
        )
        SensorLagRolloutStatus.SHADOW_FIRST -> stringResource(
            id = R.string.analytics_daily_report_sensor_lag_rollout_reason_shadow,
            gain,
            shadow,
            guidance.replaySamples.toString()
        )
        SensorLagRolloutStatus.HOLD -> stringResource(
            id = R.string.analytics_daily_report_sensor_lag_rollout_reason_hold,
            gain,
            shadow,
            guidance.replaySamples.toString()
        )
        SensorLagRolloutStatus.INSUFFICIENT_DATA -> stringResource(
            id = R.string.analytics_daily_report_sensor_lag_rollout_reason_insufficient,
            guidance.replaySamples.toString()
        )
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

@Composable
private fun ActivationGateCard(
    lines: List<String>
) {
    AnalyticsSectionCard {
        AnalyticsSectionLabel(text = stringResource(id = R.string.section_analytics_activation_gate))
        if (lines.isEmpty()) {
            Text(
                text = stringResource(id = R.string.analytics_activation_gate_empty),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            return@AnalyticsSectionCard
        }
        lines.forEach { line ->
            Surface(
                shape = AnalyticsInfoShape,
                color = MaterialTheme.colorScheme.surfaceVariant,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                Text(
                    text = line,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(Spacing.sm)
                )
            }
        }
    }
}

@Composable
private fun AnalyticsSectionCard(
    content: @Composable ColumnScope.() -> Unit
) {
    val midnightGlass = LocalUiStyle.current == UiStyle.MIDNIGHT_GLASS
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = if (midnightGlass) RoundedCornerShape(28.dp) else AnalyticsSectionShape,
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
private fun AnalyticsSectionLabel(text: String) {
    val midnightGlass = LocalUiStyle.current == UiStyle.MIDNIGHT_GLASS
    var showInfo by rememberSaveable(text) { mutableStateOf(false) }
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
            text = {
                Text(
                    text = stringResource(
                        id = R.string.analytics_info_section_generic,
                        text
                    )
                )
            },
            confirmButton = {
                TextButton(onClick = { showInfo = false }) {
                    Text(text = stringResource(id = R.string.action_close))
                }
            }
        )
    }
}

@Composable
private fun CircadianPatternsCard(
    state: AnalyticsUiState,
    selectedWindow: CircadianPatternWindowUi,
    onWindowSelected: (CircadianPatternWindowUi) -> Unit
) {
    AnalyticsSectionCard {
        AnalyticsSectionLabel(text = stringResource(id = R.string.section_analytics_circadian_patterns))
        state.circadianStateStatus?.let { status ->
            CircadianStateStatusCard(status = status)
        }
        if (state.circadianSections.isEmpty()) {
            Text(
                text = stringResource(id = R.string.analytics_circadian_empty),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            return@AnalyticsSectionCard
        }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            items(CircadianPatternWindowUi.entries.toList()) { candidate ->
                FilterChip(
                    selected = candidate == selectedWindow,
                    onClick = { onWindowSelected(candidate) },
                    colors = if (LocalUiStyle.current == UiStyle.MIDNIGHT_GLASS) analyticsFilterChipColors() else FilterChipDefaults.filterChipColors(),
                    label = { Text(text = candidate.label) }
                )
            }
        }
        Text(
            text = stringResource(id = R.string.analytics_circadian_window_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            state.circadianSections.forEach { section ->
                CircadianPatternSectionCard(
                    section = section,
                    selectedWindow = selectedWindow
                )
            }
        }
    }
}

@Composable
private fun CircadianStateStatusCard(
    status: CircadianStateStatusUi
) {
    val statusColor = when (status.state) {
        "READY" -> MaterialTheme.colorScheme.primary
        "STALE" -> MaterialTheme.colorScheme.tertiary
        "PARTIAL" -> MaterialTheme.colorScheme.secondary
        else -> MaterialTheme.colorScheme.error
    }
    Surface(
        shape = AnalyticsInfoShape,
        color = if (LocalUiStyle.current == UiStyle.MIDNIGHT_GLASS) Color(0xAA101D38) else MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(1.dp, statusColor.copy(alpha = 0.35f))
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
                AnalyticsSectionLabel(text = stringResource(id = R.string.analytics_circadian_state_title))
                Text(
                    text = status.state,
                    style = MaterialTheme.typography.labelLarge,
                    color = statusColor
                )
            }
            Text(
                text = status.reason,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
                verticalArrangement = Arrangement.spacedBy(Spacing.xs)
            ) {
                CircadianStateMetricChip(
                    label = stringResource(id = R.string.analytics_circadian_state_slots),
                    value = status.slotCount.toString()
                )
                CircadianStateMetricChip(
                    label = stringResource(id = R.string.analytics_circadian_state_transitions),
                    value = status.transitionCount.toString()
                )
                CircadianStateMetricChip(
                    label = stringResource(id = R.string.analytics_circadian_state_snapshots),
                    value = status.snapshotCount.toString()
                )
                CircadianStateMetricChip(
                    label = stringResource(id = R.string.analytics_circadian_state_replay),
                    value = status.replayCount.toString()
                )
                CircadianStateMetricChip(
                    label = stringResource(id = R.string.analytics_circadian_state_sections),
                    value = status.sectionCount.toString()
                )
            }
            Text(
                text = stringResource(
                    id = R.string.analytics_circadian_state_updated_template,
                    formatHistoryTs(status.latestSnapshotTs),
                    formatHistoryTs(status.latestReplayTs)
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            status.sourceSummary?.let { sourceSummary ->
                Text(
                    text = stringResource(
                        id = R.string.analytics_circadian_state_sources_template,
                        sourceSummary
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun CircadianStateMetricChip(
    label: String,
    value: String
) {
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = if (LocalUiStyle.current == UiStyle.MIDNIGHT_GLASS) 0.35f else 1f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = Spacing.sm, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = value,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

@Composable
private fun CircadianPatternSectionCard(
    section: CircadianPatternSectionUi,
    selectedWindow: CircadianPatternWindowUi
) {
    val windowData = section.windows.firstOrNull { it.windowDays == selectedWindow.days }
        ?: section.windows.maxByOrNull { it.windowDays }
    Surface(
        shape = AnalyticsInfoShape,
        color = if (LocalUiStyle.current == UiStyle.MIDNIGHT_GLASS) Color(0xAA101D38) else MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(1.dp, if (LocalUiStyle.current == UiStyle.MIDNIGHT_GLASS) Color(0x1FFFFFFF) else MaterialTheme.colorScheme.outlineVariant)
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
                    text = circadianDayTypeLabel(section.requestedDayType),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = stringResource(
                        id = R.string.analytics_circadian_source_template,
                        circadianDayTypeLabel(section.segmentSource)
                    ),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                text = stringResource(
                    id = if (section.segmentFallback) {
                        R.string.analytics_circadian_fallback_template
                    } else {
                        R.string.analytics_circadian_quality_template
                    },
                    section.stableWindowDays,
                    section.coverageDays,
                    UiFormatters.formatPercent(section.confidence),
                    UiFormatters.formatPercent(section.qualityScore)
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (windowData == null) {
                Text(
                    text = stringResource(id = R.string.analytics_circadian_window_empty, selectedWindow.label),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Text(
                    text = stringResource(
                        id = R.string.analytics_circadian_window_stats,
                        selectedWindow.label,
                        windowData.coverageDays,
                        windowData.sampleCount
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (windowData.replayDiagnostics.isNotEmpty()) {
                    AnalyticsSectionLabel(text = stringResource(id = R.string.analytics_circadian_replay_diagnostics))
                    windowData.replayDiagnostics.forEach { diag ->
                        Text(
                            text = stringResource(
                                id = R.string.analytics_circadian_replay_diag_line,
                                diag.horizonMinutes,
                                diag.bucketStatus,
                                UiFormatters.formatPercent(diag.winRate),
                                UiFormatters.formatMmol(diag.maeBaseline),
                                UiFormatters.formatMmol(diag.maeCircadian),
                                diag.sampleCount,
                                if (diag.fallbackToAll) {
                                    stringResource(id = R.string.analytics_circadian_replay_diag_fallback)
                                } else {
                                    ""
                                }
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                CircadianMedianBandChart(points = windowData.points)
                CircadianDeltaChart(points = windowData.deltaPoints)
                AnalyticsSectionLabel(text = stringResource(id = R.string.analytics_circadian_top_risky_windows))
                if (windowData.topRiskWindows.isEmpty()) {
                    Text(
                        text = stringResource(id = R.string.analytics_circadian_risky_empty),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    windowData.topRiskWindows.forEach { risk ->
                        Text(
                            text = stringResource(
                                id = R.string.analytics_circadian_risky_window_line,
                                formatHourRange(risk.hour),
                                UiFormatters.formatPercent(risk.lowRate),
                                UiFormatters.formatPercent(risk.highRate),
                                UiFormatters.formatMmol(risk.recommendedTargetMmol, 2)
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CircadianMedianBandChart(
    points: List<CircadianCurvePointUi>
) {
    if (points.size < 4) {
        Text(
            text = stringResource(id = R.string.analytics_chart_not_enough_points),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        return
    }
    val sorted = points.sortedBy { it.slotIndex }
    val minY = (sorted.minOf { it.p10 } - 0.6).coerceAtLeast(2.2)
    val maxY = (sorted.maxOf { it.p90 } + 0.6).coerceAtMost(22.0)
    val range = (maxY - minY).coerceAtLeast(0.5)
    val accessibilityLabel = stringResource(
        id = R.string.analytics_circadian_curve_accessibility,
        UiFormatters.formatMmol(sorted.lastOrNull()?.medianBg, 2)
    )
    val gridColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)
    AnalyticsSectionLabel(text = stringResource(id = R.string.analytics_circadian_median_curve))
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(180.dp)
            .semantics {
                contentDescription = accessibilityLabel
            }
    ) {
        val width = size.width
        val height = size.height
        val leftPad = 28f
        val topPad = 10f
        val bottomPad = 22f
        val plotWidth = (width - leftPad).coerceAtLeast(1f)
        val plotHeight = (height - topPad - bottomPad).coerceAtLeast(1f)
        fun xOf(slotIndex: Int): Float = leftPad + (slotIndex / 95f) * plotWidth
        fun yOf(value: Double): Float = topPad + ((maxY - value) / range).toFloat() * plotHeight

        repeat(4) { idx ->
            val y = topPad + plotHeight * (idx / 3f)
            drawLine(
                color = gridColor,
                start = Offset(leftPad, y),
                end = Offset(width, y),
                strokeWidth = 1f
            )
        }

        val bandPath = Path().apply {
            sorted.forEachIndexed { index, point ->
                val x = xOf(point.slotIndex)
                val y = yOf(point.p25)
                if (index == 0) moveTo(x, y) else lineTo(x, y)
            }
            sorted.asReversed().forEach { point ->
                lineTo(xOf(point.slotIndex), yOf(point.p75))
            }
            close()
        }
        drawPath(path = bandPath, color = Color(0xFF2563EB).copy(alpha = 0.16f))

        val p10Path = Path()
        val p90Path = Path()
        val medianPath = Path()
        sorted.forEachIndexed { index, point ->
            val x = xOf(point.slotIndex)
            val p10y = yOf(point.p10)
            val p90y = yOf(point.p90)
            val medianY = yOf(point.medianBg)
            if (index == 0) {
                p10Path.moveTo(x, p10y)
                p90Path.moveTo(x, p90y)
                medianPath.moveTo(x, medianY)
            } else {
                p10Path.lineTo(x, p10y)
                p90Path.lineTo(x, p90y)
                medianPath.lineTo(x, medianY)
            }
        }
        drawPath(
            path = p10Path,
            color = Color(0xFF93C5FD),
            style = Stroke(width = 2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f), 0f))
        )
        drawPath(
            path = p90Path,
            color = Color(0xFF93C5FD),
            style = Stroke(width = 2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f), 0f))
        )
        drawPath(
            path = medianPath,
            color = Color(0xFF1D4ED8),
            style = Stroke(width = 3f, cap = StrokeCap.Round)
        )
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        listOf("00:00", "06:00", "12:00", "18:00", "24:00").forEach { tick ->
            Text(
                text = tick,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun CircadianDeltaChart(
    points: List<CircadianDeltaPointUi>
) {
    if (points.size < 4) return
    val sorted = points.sortedBy { it.slotIndex }
    val values = sorted.flatMap { listOfNotNull(it.delta30, it.delta60) }
    if (values.isEmpty()) return
    val maxAbs = max(0.4, values.maxOf { abs(it) } + 0.2)
    val zeroLineColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.7f)
    AnalyticsSectionLabel(text = stringResource(id = R.string.analytics_circadian_expected_delta))
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(140.dp)
    ) {
        val width = size.width
        val height = size.height
        val leftPad = 28f
        val topPad = 10f
        val bottomPad = 22f
        val plotWidth = (width - leftPad).coerceAtLeast(1f)
        val plotHeight = (height - topPad - bottomPad).coerceAtLeast(1f)
        fun xOf(slotIndex: Int): Float = leftPad + (slotIndex / 95f) * plotWidth
        fun yOf(value: Double): Float = topPad + (((maxAbs - value) / (maxAbs * 2.0)).toFloat() * plotHeight)

        val zeroY = yOf(0.0)
        drawLine(
            color = zeroLineColor,
            start = Offset(leftPad, zeroY),
            end = Offset(width, zeroY),
            strokeWidth = 1.5f
        )
        val delta30Path = Path()
        val delta60Path = Path()
        var has30 = false
        var has60 = false
        sorted.forEach { point ->
            point.delta30?.let { value ->
                val x = xOf(point.slotIndex)
                val y = yOf(value)
                if (!has30) {
                    delta30Path.moveTo(x, y)
                    has30 = true
                } else {
                    delta30Path.lineTo(x, y)
                }
            }
            point.delta60?.let { value ->
                val x = xOf(point.slotIndex)
                val y = yOf(value)
                if (!has60) {
                    delta60Path.moveTo(x, y)
                    has60 = true
                } else {
                    delta60Path.lineTo(x, y)
                }
            }
        }
        if (has30) {
            drawPath(
                path = delta30Path,
                color = Color(0xFF9333EA),
                style = Stroke(width = 2.5f, cap = StrokeCap.Round)
            )
        }
        if (has60) {
            drawPath(
                path = delta60Path,
                color = Color(0xFF0F766E),
                style = Stroke(width = 2.5f, cap = StrokeCap.Round, pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 7f), 0f))
            )
        }
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
    ) {
        AnalyticsLegendItem(
            text = stringResource(id = R.string.analytics_circadian_delta_30),
            color = Color(0xFF9333EA),
            icon = Icons.Default.ShowChart
        )
        AnalyticsLegendItem(
            text = stringResource(id = R.string.analytics_circadian_delta_60),
            color = Color(0xFF0F766E),
            icon = Icons.Default.ShowChart
        )
    }
}

@Composable
private fun circadianDayTypeLabel(raw: String): String = when (raw.uppercase(Locale.US)) {
    "WEEKDAY" -> stringResource(id = R.string.analytics_replay_day_type_weekday)
    "WEEKEND" -> stringResource(id = R.string.analytics_replay_day_type_weekend)
    else -> stringResource(id = R.string.analytics_circadian_day_type_all)
}

private fun formatHourRange(hour: Int): String {
    val start = hour.coerceIn(0, 23)
    val end = (start + 1) % 24
    return String.format(Locale.US, "%02d:00-%02d:00", start, end)
}

@Composable
private fun analyticsFilterChipColors() = FilterChipDefaults.filterChipColors(
    selectedContainerColor = Color(0x221D4ED8),
    selectedLabelColor = Color(0xFF8DB6FF),
    selectedLeadingIconColor = Color(0xFF8DB6FF),
    containerColor = Color(0xAA101D38),
    labelColor = Color(0xFFB5C0D8),
    iconColor = Color(0xFFB5C0D8)
)

private fun alignOverlayPoints(
    source: List<IsfCrOverlayPointUi>,
    visiblePoints: List<IsfCrHistoryPointUi>
): List<IsfCrOverlayPointUi> {
    if (source.isEmpty() || visiblePoints.isEmpty()) return emptyList()
    val byTimestamp = source.associateBy { it.timestamp }
    return visiblePoints.mapNotNull { point -> byTimestamp[point.timestamp] }
}

private fun formatHistoryTs(ts: Long?): String {
    if (ts == null || ts <= 0L) return "--"
    return Instant.ofEpochMilli(ts)
        .atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern("dd MMM HH:mm", Locale.getDefault()))
}

private fun formatHistoryAxisTick(ts: Long, spanMs: Long): String {
    if (ts <= 0L) return "--"
    val pattern = when {
        spanMs <= 36L * 60L * 60L * 1000L -> "HH:mm"
        spanMs <= 4L * 24L * 60L * 60L * 1000L -> "dd MMM HH:mm"
        spanMs <= 8L * 24L * 60L * 60L * 1000L -> "dd MMM"
        else -> "dd MMM"
    }
    return Instant.ofEpochMilli(ts)
        .atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern(pattern, Locale.getDefault()))
}

@Preview(showBackground = true)
@Composable
private fun AnalyticsScreenPreview() {
    val now = System.currentTimeMillis()
    AapsCopilotTheme {
        AnalyticsScreen(
            state = AnalyticsUiState(
                loadState = ScreenLoadState.READY,
                isStale = false,
                qualityLines = listOf(
                    "5m MAE 0.24 | RMSE 0.36 | MARD 4.9%",
                    "30m MAE 0.44 | RMSE 0.69 | MARD 7.0%"
                ),
                baselineDeltaLines = listOf(
                    "30m COB: -0.11 mmol/L",
                    "60m UAM: +0.09 mmol/L"
                ),
                currentIsfReal = 2.46,
                currentCrReal = 10.8,
                currentIsfMerged = 2.31,
                currentCrMerged = 11.2,
                currentIsfAapsRaw = 2.18,
                currentCrAapsRaw = 9.7,
                historyPoints = listOf(
                    IsfCrHistoryPointUi(
                        timestamp = now - 7 * 60 * 60_000L,
                        isfMerged = 2.2,
                        crMerged = 11.5,
                        isfCalculated = 2.3,
                        crCalculated = 11.0,
                        isfAaps = 2.1,
                        crAaps = 9.8
                    ),
                    IsfCrHistoryPointUi(
                        timestamp = now - 6 * 60 * 60_000L,
                        isfMerged = 2.25,
                        crMerged = 11.4,
                        isfCalculated = 2.35,
                        crCalculated = 10.9,
                        isfAaps = 2.12,
                        crAaps = 9.7
                    ),
                    IsfCrHistoryPointUi(
                        timestamp = now - 5 * 60 * 60_000L,
                        isfMerged = 2.3,
                        crMerged = 11.2,
                        isfCalculated = 2.4,
                        crCalculated = 10.8,
                        isfAaps = 2.15,
                        crAaps = 9.6
                    ),
                    IsfCrHistoryPointUi(
                        timestamp = now - 4 * 60 * 60_000L,
                        isfMerged = 2.32,
                        crMerged = 11.1,
                        isfCalculated = 2.45,
                        crCalculated = 10.7,
                        isfAaps = 2.18,
                        crAaps = 9.5
                    ),
                    IsfCrHistoryPointUi(
                        timestamp = now - 3 * 60 * 60_000L,
                        isfMerged = 2.28,
                        crMerged = 11.3,
                        isfCalculated = 2.42,
                        crCalculated = 10.9,
                        isfAaps = 2.17,
                        crAaps = 9.6
                    ),
                    IsfCrHistoryPointUi(
                        timestamp = now - 2 * 60 * 60_000L,
                        isfMerged = 2.27,
                        crMerged = 11.4,
                        isfCalculated = 2.4,
                        crCalculated = 11.0,
                        isfAaps = 2.16,
                        crAaps = 9.7
                    ),
                    IsfCrHistoryPointUi(
                        timestamp = now - 60 * 60_000L,
                        isfMerged = 2.3,
                        crMerged = 11.2,
                        isfCalculated = 2.46,
                        crCalculated = 10.8,
                        isfAaps = 2.18,
                        crAaps = 9.7
                    )
                ),
                historyOverlayPoints = listOf(
                    IsfCrOverlayPointUi(
                        timestamp = now - 7 * 60 * 60_000L,
                        cobGrams = 28.0,
                        uamGrams = 8.0,
                        activityRatio = 1.02
                    ),
                    IsfCrOverlayPointUi(
                        timestamp = now - 5 * 60 * 60_000L,
                        cobGrams = 22.0,
                        uamGrams = 12.0,
                        activityRatio = 1.08
                    ),
                    IsfCrOverlayPointUi(
                        timestamp = now - 3 * 60 * 60_000L,
                        cobGrams = 14.0,
                        uamGrams = 9.0,
                        activityRatio = 1.26
                    ),
                    IsfCrOverlayPointUi(
                        timestamp = now - 60 * 60_000L,
                        cobGrams = 6.0,
                        uamGrams = 2.0,
                        activityRatio = 0.98
                    )
                ),
                historyLastUpdatedTs = now,
                deepLines = listOf(
                    "day/06:00-09:00: ISF 2.41, CR 10.9, conf=74%",
                    "day/09:00-12:00: ISF 2.32, CR 11.2, conf=71%"
                )
            )
        )
    }
}
