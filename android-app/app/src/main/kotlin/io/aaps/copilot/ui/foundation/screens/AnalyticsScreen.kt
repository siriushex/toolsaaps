package io.aaps.copilot.ui.foundation.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.aaps.copilot.R
import io.aaps.copilot.ui.IsfCrHistoryPointUi
import io.aaps.copilot.ui.IsfCrHistoryResolver
import io.aaps.copilot.ui.IsfCrHistoryWindow
import io.aaps.copilot.ui.foundation.design.AppElevation
import io.aaps.copilot.ui.foundation.design.Spacing
import io.aaps.copilot.ui.foundation.format.UiFormatters
import io.aaps.copilot.ui.foundation.theme.AapsCopilotTheme
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

private data class IsfCrSeriesPoint(
    val ts: Long,
    val real: Double,
    val merged: Double
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
    modifier: Modifier = Modifier
) {
    var selectedTabRaw by rememberSaveable { mutableStateOf(AnalyticsTab.ISF_CR.name) }
    var selectedWindowRaw by rememberSaveable { mutableStateOf(IsfCrHistoryWindow.DAY.name) }
    val selectedTab = remember(selectedTabRaw) {
        runCatching { AnalyticsTab.valueOf(selectedTabRaw) }.getOrDefault(AnalyticsTab.ISF_CR)
    }
    val selectedWindow = remember(selectedWindowRaw) {
        runCatching { IsfCrHistoryWindow.valueOf(selectedWindowRaw) }.getOrDefault(IsfCrHistoryWindow.DAY)
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
                    if (
                        state.dailyReportGeneratedAtTs != null ||
                        state.dailyReportHorizonStats.isNotEmpty() ||
                        state.dailyReportMatchedSamples != null
                    ) {
                        item {
                            DailyForecastReportCard(state = state)
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
                            title = stringResource(id = R.string.analytics_chart_isf),
                            unit = stringResource(id = R.string.unit_mmol_l) + "/U",
                            points = historyPoints,
                            realColor = Color(0xFF0A5FBF),
                            mergedColor = Color(0xFF6C7E95),
                            realSelector = { it.isfReal },
                            mergedSelector = { it.isfMerged }
                        )
                    }
                    item {
                        IsfCrSeriesCard(
                            title = stringResource(id = R.string.analytics_chart_cr),
                            unit = stringResource(id = R.string.unit_g) + "/U",
                            points = historyPoints,
                            realColor = Color(0xFF00796B),
                            mergedColor = Color(0xFF6C7E95),
                            realSelector = { it.crReal },
                            mergedSelector = { it.crMerged }
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
    AnalyticsSectionCard {
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            val tabs = listOf(AnalyticsTab.ISF_CR, AnalyticsTab.QUALITY)
            tabs.forEachIndexed { index, tab ->
                SegmentedButton(
                    selected = selectedTab == tab,
                    onClick = { onTabSelected(tab) },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = tabs.size),
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
    onWindowSelected: (IsfCrHistoryWindow) -> Unit
) {
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
        LazyRow(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            items(IsfCrHistoryWindow.entries.toList()) { candidate ->
                FilterChip(
                    selected = candidate == selectedWindow,
                    onClick = { onWindowSelected(candidate) },
                    label = { Text(text = candidate.label) }
                )
            }
        }
        Text(
            text = stringResource(id = R.string.analytics_points_template, shownPoints, totalPoints),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
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
    val resolvedIconColor = iconColor ?: MaterialTheme.colorScheme.primary
    val valueText = UiFormatters.formatMmol(value, decimals = 2)
    Surface(
        modifier = modifier.semantics {
            contentDescription = "$title $valueText $unit"
        },
        shape = AnalyticsInfoShape,
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
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
                    style = MaterialTheme.typography.labelMedium
                )
            }
            Text(
                text = valueText,
                style = LocalNumericTypography.current.valueMedium
            )
            Text(
                text = unit,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun IsfCrSeriesCard(
    title: String,
    unit: String,
    points: List<IsfCrHistoryPointUi>,
    realColor: Color,
    mergedColor: Color,
    realSelector: (IsfCrHistoryPointUi) -> Double,
    mergedSelector: (IsfCrHistoryPointUi) -> Double
) {
    val series = remember(points) {
        points
            .map {
                IsfCrSeriesPoint(
                    ts = it.timestamp,
                    real = realSelector(it),
                    merged = mergedSelector(it)
                )
            }
            .sortedBy { it.ts }
    }
    val min = series.minOfOrNull { it.real }
    val max = series.maxOfOrNull { it.real }
    val lastReal = series.lastOrNull()?.real
    val lastMerged = series.lastOrNull()?.merged
    val startTs = series.firstOrNull()?.ts
    val endTs = series.lastOrNull()?.ts
    val hasRealSeparation = series.any { abs(it.real - it.merged) >= 0.02 }

    AnalyticsSectionCard {
        AnalyticsSectionLabel(text = stringResource(id = R.string.section_analytics_isfcr_history))
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall
        )

        if (series.size < 2) {
            Text(
                text = stringResource(id = R.string.analytics_chart_not_enough_points),
                style = MaterialTheme.typography.bodyMedium
            )
            return@AnalyticsSectionCard
        }

        val allValues = series.flatMap { listOf(it.real, it.merged) }
        val yMinRaw = allValues.minOrNull() ?: 0.0
        val yMaxRaw = allValues.maxOrNull() ?: 1.0
        val yPadding = max(0.1, (yMaxRaw - yMinRaw) * 0.15)
        val yMin = yMinRaw - yPadding
        val yMax = yMaxRaw + yPadding
        val minTs = series.first().ts
        val maxTs = max(minTs + 1L, series.last().ts)

        val chartDescription = stringResource(
            id = R.string.analytics_chart_accessibility_template,
            title,
            UiFormatters.formatMmol(lastReal, 2),
            unit,
            UiFormatters.formatMmol(lastMerged, 2)
        )
        val gridMajor = MaterialTheme.colorScheme.outline.copy(alpha = 0.16f)
        val gridMinor = MaterialTheme.colorScheme.outline.copy(alpha = 0.10f)
        val axisColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)

        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(220.dp)
                .semantics { contentDescription = chartDescription }
        ) {
            val left = 36.dp.toPx()
            val right = 12.dp.toPx()
            val top = 10.dp.toPx()
            val bottom = 22.dp.toPx()
            val chartWidth = (size.width - left - right).coerceAtLeast(1f)
            val chartHeight = (size.height - top - bottom).coerceAtLeast(1f)

            fun xOf(ts: Long): Float {
                return left + ((ts - minTs).toDouble() / (maxTs - minTs).toDouble() * chartWidth).toFloat()
            }

            fun yOf(value: Double): Float {
                val ratio = ((value - yMin) / (yMax - yMin)).coerceIn(0.0, 1.0)
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

            val mergedPath = Path()
            val realPath = Path()
            series.forEachIndexed { index, point ->
                val x = xOf(point.ts)
                val yReal = yOf(point.real)
                val yMerged = yOf(point.merged)
                if (index == 0) {
                    realPath.moveTo(x, yReal)
                    mergedPath.moveTo(x, yMerged)
                } else {
                    realPath.lineTo(x, yReal)
                    mergedPath.lineTo(x, yMerged)
                }
            }
            drawPath(
                path = mergedPath,
                color = mergedColor,
                style = Stroke(
                    width = 2.5f,
                    cap = StrokeCap.Round,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(14f, 10f), 0f)
                )
            )
            drawPath(
                path = realPath,
                color = realColor,
                style = Stroke(width = 3.5f, cap = StrokeCap.Round)
            )

            val last = series.last()
            drawCircle(color = mergedColor, radius = 4.5f, center = Offset(xOf(last.ts), yOf(last.merged)))
            drawCircle(color = realColor, radius = 5.2f, center = Offset(xOf(last.ts), yOf(last.real)))
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Spacing.md)
        ) {
            AnalyticsLegendItem(
                text = stringResource(id = R.string.analytics_legend_real),
                color = realColor,
                icon = Icons.Default.ShowChart
            )
            AnalyticsLegendItem(
                text = stringResource(id = R.string.analytics_legend_merged),
                color = mergedColor,
                icon = Icons.Default.Info
            )
        }
        if (!hasRealSeparation) {
            Text(
                text = stringResource(id = R.string.analytics_series_realtime_matches_base_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Text(
            text = stringResource(
                id = R.string.analytics_stats_template,
                UiFormatters.formatMmol(min, 2),
                UiFormatters.formatMmol(max, 2),
                UiFormatters.formatMmol(lastReal, 2),
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
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = AnalyticsSectionShape,
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
private fun AnalyticsSectionLabel(text: String) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.7.sp),
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

private fun formatHistoryTs(ts: Long?): String {
    if (ts == null || ts <= 0L) return "--"
    return Instant.ofEpochMilli(ts)
        .atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern("dd MMM HH:mm", Locale.getDefault()))
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
                historyPoints = listOf(
                    IsfCrHistoryPointUi(
                        timestamp = now - 7 * 60 * 60_000L,
                        isfMerged = 2.2,
                        crMerged = 11.5,
                        isfCalculated = 2.3,
                        crCalculated = 11.0
                    ),
                    IsfCrHistoryPointUi(
                        timestamp = now - 6 * 60 * 60_000L,
                        isfMerged = 2.25,
                        crMerged = 11.4,
                        isfCalculated = 2.35,
                        crCalculated = 10.9
                    ),
                    IsfCrHistoryPointUi(
                        timestamp = now - 5 * 60 * 60_000L,
                        isfMerged = 2.3,
                        crMerged = 11.2,
                        isfCalculated = 2.4,
                        crCalculated = 10.8
                    ),
                    IsfCrHistoryPointUi(
                        timestamp = now - 4 * 60 * 60_000L,
                        isfMerged = 2.32,
                        crMerged = 11.1,
                        isfCalculated = 2.45,
                        crCalculated = 10.7
                    ),
                    IsfCrHistoryPointUi(
                        timestamp = now - 3 * 60 * 60_000L,
                        isfMerged = 2.28,
                        crMerged = 11.3,
                        isfCalculated = 2.42,
                        crCalculated = 10.9
                    ),
                    IsfCrHistoryPointUi(
                        timestamp = now - 2 * 60 * 60_000L,
                        isfMerged = 2.27,
                        crMerged = 11.4,
                        isfCalculated = 2.4,
                        crCalculated = 11.0
                    ),
                    IsfCrHistoryPointUi(
                        timestamp = now - 60 * 60_000L,
                        isfMerged = 2.3,
                        crMerged = 11.2,
                        isfCalculated = 2.46,
                        crCalculated = 10.8
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
