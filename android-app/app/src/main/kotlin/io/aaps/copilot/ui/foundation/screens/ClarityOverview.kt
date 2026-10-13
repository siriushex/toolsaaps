package io.aaps.copilot.ui.foundation.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.TrendingDown
import androidx.compose.material.icons.filled.TrendingFlat
import androidx.compose.material.icons.filled.TrendingUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.aaps.copilot.R
import io.aaps.copilot.domain.target.EatingSoonPolicy
import io.aaps.copilot.ui.foundation.format.UiFormatters
import java.util.UUID

@Composable
internal fun ClarityOverviewContent(
    state: OverviewUiState,
    onEatingSoon: (String) -> Unit,
    onMeal: () -> Unit,
    onIob: () -> Unit,
    onIsf: () -> Unit,
    onCr: () -> Unit,
    onUam: () -> Unit,
    onBaseTarget: () -> Unit,
    onOpenClinicalReport: () -> Unit,
    warningContent: @Composable () -> Unit,
    profileContent: @Composable () -> Unit,
    pumpStatusContent: @Composable () -> Unit,
    onLoadMealEstimateHistory: suspend (Long, String) -> io.aaps.copilot.domain.nutrition.LentaMealHistory = { cutoff, zone ->
        io.aaps.copilot.domain.nutrition.LentaMealHistory(cutoff, zone,
            status = io.aaps.copilot.domain.nutrition.LentaMealHistoryStatus.UNAVAILABLE)
    },
    modifier: Modifier = Modifier,
    onLoadEventContext: io.aaps.copilot.domain.events.EventContextLoader = { io.aaps.copilot.domain.events.EventCompensationContext() }
) {
    var targetDetails by rememberSaveable { mutableStateOf(false) }
    var foodDetails by rememberSaveable { mutableStateOf(false) }
    var eatingDetails by rememberSaveable { mutableStateOf(false) }
    var confirmEating by rememberSaveable { mutableStateOf(false) }
    var requestId by rememberSaveable { mutableStateOf("") }
    var horizon by rememberSaveable { mutableStateOf<Int?>(null) }
    var chartExpanded by rememberSaveable { mutableStateOf(false) }
    val openConfirmation = {
        requestId = UUID.randomUUID().toString()
        confirmEating = true
    }
    val displayedManager = state.targetManagerLiveStatus.copy(
        currentTargetMmol = state.observedAapsTargetMmol ?: state.targetManagerLiveStatus.currentTargetMmol
    )

    if (targetDetails) ClarityDetailsDialog(
        stringResource(R.string.clarity_target_details), "clarityTargetDetails", { targetDetails = false }
    ) {
        Text(clarityManagerModeLabel(state.targetManagerLiveStatus.mode), style = MaterialTheme.typography.labelLarge)
        TargetManagerLiveStatusSection(displayedManager, false, framed = false)
        state.targetManagerLiveStatus.timestamp?.let {
            Text(stringResource(R.string.clarity_decision_at, UiFormatters.formatTimestamp(it)),
                style = MaterialTheme.typography.bodySmall)
        }
        TextButton(onClick = { targetDetails = false; onBaseTarget() }) {
            Text(stringResource(R.string.overview_target_edit))
        }
    }
    if (foodDetails) ClarityFoodDetailsDialog(state, { foodDetails = false },
        onMeal = { foodDetails = false; onMeal() },
        onEatingSoon = {
            foodDetails = false
            if (state.eatingSoon.status == EatingSoonUiStatus.IDLE) openConfirmation() else eatingDetails = true
        }, onLoadHistory = onLoadMealEstimateHistory)
    if (chartExpanded) ClarityDetailsDialog(
        stringResource(R.string.overview_chart_title), "clarityChartDetails", { chartExpanded = false }
    ) {
        ClarityGlucoseChart(state, true, {}, { horizon = it }, onLoadEventContext = onLoadEventContext)
    }
    horizon?.let { minutes ->
        ClarityDetailsDialog(stringResource(R.string.clarity_horizon_title, minutes), "clarityForecastDetails", { horizon = null }) {
            val point = state.horizons.firstOrNull { it.horizonMinutes == minutes }?.takeIf { state.forecastsCurrent && !state.isStale }
            Text("${UiFormatters.formatMmol(point?.pred, 1)} ${stringResource(R.string.unit_mmol_l)}",
                style = MaterialTheme.typography.headlineMedium)
            Text(stringResource(R.string.clarity_forecast_interval,
                UiFormatters.formatMmol(point?.ciLow, 1), UiFormatters.formatMmol(point?.ciHigh, 1)))
            Text(stringResource(R.string.clarity_cgm_age, clarityAge(state.sampleAgeMinutes)))
            if (point == null) Text(stringResource(R.string.clarity_forecast_unavailable))
        }
    }
    if (confirmEating) AlertDialog(
        modifier = Modifier.testTag("clarityEatingConfirm"),
        onDismissRequest = { confirmEating = false },
        title = { Text(stringResource(R.string.clarity_eating_soon)) },
        text = { Text(stringResource(R.string.clarity_eating_confirm,
            UiFormatters.formatMmol(EatingSoonPolicy.TARGET_MMOL, 1), EatingSoonPolicy.DURATION_MINUTES)) },
        confirmButton = {
            TextButton(modifier = Modifier.testTag("clarityEatingSubmit"), onClick = {
                confirmEating = false
                onEatingSoon(requestId)
            }) { Text(stringResource(R.string.clarity_eating_send)) }
        },
        dismissButton = { TextButton(onClick = { confirmEating = false }) { Text(stringResource(R.string.action_cancel)) } }
    )
    if (eatingDetails) ClarityDetailsDialog(
        stringResource(R.string.clarity_eating_status), "clarityEatingDetails", { eatingDetails = false }
    ) {
        Text(clarityEatingStatusLabel(state.eatingSoon.status), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(when (state.eatingSoon.status) {
            EatingSoonUiStatus.QUEUED -> R.string.clarity_eating_queued_detail
            EatingSoonUiStatus.AWAITING_AAPS -> R.string.clarity_eating_waiting_detail
            EatingSoonUiStatus.CONFIRMED -> R.string.clarity_eating_confirmed_detail
            EatingSoonUiStatus.UNKNOWN -> R.string.clarity_eating_unknown_detail
            else -> R.string.clarity_eating_blocked
        }))
        if (state.eatingSoon.status == EatingSoonUiStatus.BLOCKED) state.eatingSoon.reason?.let {
            Text(io.aaps.copilot.ui.manualEatingSoonBlockedMessage(LocalContext.current, it),
                style = MaterialTheme.typography.bodySmall)
        }
        Text("${stringResource(R.string.clarity_aaps_target)} · ${UiFormatters.formatMmol(displayedManager.currentTargetMmol, 1)} ${stringResource(R.string.unit_mmol_l)}",
            style = MaterialTheme.typography.bodyMedium)
        TextButton(onClick = { eatingDetails = false; targetDetails = true }) {
            Text(stringResource(R.string.clarity_target_details))
        }
        val navigate = LocalIssueNavigator.current
        if (navigate != null) TextButton(onClick = { eatingDetails = false; navigate(IssueDestination.AUDIT) }) {
            Text(stringResource(IssueDestination.AUDIT.labelRes))
        }
        if (state.eatingSoon.status in setOf(EatingSoonUiStatus.CONFIRMED, EatingSoonUiStatus.BLOCKED)) {
            TextButton(modifier = Modifier.testTag("clarityEatingNewRequest"), onClick = {
                eatingDetails = false; openConfirmation()
            }) { Text(stringResource(R.string.clarity_new_request)) }
        }
    }

    BoxWithConstraints(modifier.fillMaxSize()) {
    val compactChartHeight = (maxHeight - 320.dp).coerceIn(140.dp, 190.dp)
    Column(Modifier.fillMaxSize().testTag("clarityOverview")) {
        warningContent()
        LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("clarityContent"), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            item {
                BoxWithConstraints(Modifier.fillMaxWidth().testTag("clarityPrimaryPanel")) {
                    if (maxWidth < 280.dp || LocalDensity.current.fontScale >= 1.3f) {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Column(Modifier.testTag("clarityClinicalReport")
                                .clickable(role = Role.Button, onClickLabel = stringResource(R.string.clarity_details),
                                    onClick = onOpenClinicalReport)) { ClarityGlucose(state) }
                            HorizontalDivider()
                            ClarityTarget(displayedManager, { targetDetails = true }, Modifier.fillMaxWidth())
                        }
                    } else Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Column(Modifier.weight(1.45f).testTag("clarityClinicalReport")
                            .clickable(role = Role.Button, onClickLabel = stringResource(R.string.clarity_details),
                                onClick = onOpenClinicalReport)) { ClarityGlucose(state) }
                        ClarityTarget(displayedManager, { targetDetails = true }, Modifier.weight(1f))
                    }
                }
            }
            item {
                HorizontalDivider()
                ClarityMetrics(state, onIob, { foodDetails = true }, onIsf, onCr, onUam, onBaseTarget)
                OverviewSensitivitySourceStatus(state)
                HorizontalDivider()
            }
            item { ClarityGlucoseChart(state, false, { chartExpanded = true }, { horizon = it },
                compactChartHeight, pumpStatusContent, onLoadEventContext) }
            item { profileContent() }
        }
        HorizontalDivider()
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(
                modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("clarityEatingSoon"),
                shape = RoundedCornerShape(6.dp),
                onClick = { if (state.eatingSoon.status == EatingSoonUiStatus.IDLE) openConfirmation() else eatingDetails = true }
            ) {
                if (LocalDensity.current.fontScale < 1.3f) Icon(Icons.Default.Restaurant, null)
                Text(if (state.eatingSoon.status == EatingSoonUiStatus.UNKNOWN) stringResource(R.string.clarity_eating_review)
                    else clarityEatingStatusLabel(state.eatingSoon.status),
                    Modifier.padding(start = if (LocalDensity.current.fontScale < 1.3f) 8.dp else 0.dp))
            }
            Surface(shape = RoundedCornerShape(6.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                IconButton(modifier = Modifier.size(48.dp).testTag("clarityAddMeal"), onClick = onMeal) {
                    Icon(Icons.Default.Add, stringResource(R.string.clarity_add_meal))
                }
            }
        }
    }
    }
}

@Composable
private fun ClarityMetrics(state: OverviewUiState, onIob: () -> Unit, onCob: () -> Unit,
    onIsf: () -> Unit, onCr: () -> Unit, onUam: () -> Unit, onBase: () -> Unit) {
    val iob = overviewIobPresentation(state.currentIobUnits, state.iobDetails.signedNetIobUnits)
    val metrics = listOf(
        ClarityMetricSpec("clarityIob", stringResource(R.string.metric_iob), iob.value, stringResource(R.string.unit_u), onIob,
            stringResource(R.string.overview_iob_pair_description, iob.current, iob.signedNet, stringResource(R.string.unit_u)), iob),
        ClarityMetricSpec("clarityCob", stringResource(R.string.metric_cob_copilot), UiFormatters.formatGrams(state.currentCobGrams, 1), stringResource(R.string.unit_g), onCob),
        ClarityMetricSpec("clarityIsf", "ISF · ${metricActualSourceLabel(state.isfRuntime)}", UiFormatters.formatMmol(state.currentIsfMmolPerUnit, 1), stringResource(R.string.unit_mmol_u), onIsf),
        ClarityMetricSpec("clarityCr", "CR · ${metricActualSourceLabel(state.crRuntime)}", UiFormatters.formatGrams(state.currentCrGramsPerUnit, 1), stringResource(R.string.unit_g_u), onCr),
        ClarityMetricSpec("clarityUam", stringResource(R.string.overview_model_uam), UiFormatters.formatGrams(state.calculatedUamCarbsGrams, 1), stringResource(R.string.unit_g), onUam),
        ClarityMetricSpec("clarityBaseTarget", stringResource(R.string.metric_base_target), UiFormatters.formatMmol(state.effectiveBaseTargetMmol, 1), stringResource(R.string.unit_mmol_l), onBase)
    )
    BoxWithConstraints(Modifier.fillMaxWidth().testTag("clarityMetrics")) {
        val columns = when {
            LocalDensity.current.fontScale >= 1.8f -> 1
            maxWidth < 280.dp || LocalDensity.current.fontScale >= 1.3f -> 2
            else -> 3
        }
        Column {
            metrics.chunked(columns).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { metric ->
                        ClarityMetric(metric.label, metric.value, metric.unit, metric.tag, metric.onClick, Modifier.weight(1f),
                            metric.description, metric.iobPair)
                    }
                }
            }
        }
    }
}

private data class ClarityMetricSpec(val tag: String, val label: String, val value: String,
    val unit: String, val onClick: () -> Unit, val description: String? = null, val iobPair: OverviewIobPresentation? = null)

@Composable
private fun ClarityGlucose(state: OverviewUiState) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(UiFormatters.formatMmol(state.glucose, 1), modifier = Modifier.testTag("clarityGlucose"),
            style = MaterialTheme.typography.displayMedium.copy(fontSize = 40.sp, lineHeight = 44.sp),
            fontWeight = FontWeight.SemiBold)
        if (state.delta != null && state.delta.isFinite()) Icon(when {
            state.delta > 0.0 -> Icons.Default.TrendingUp
            state.delta < 0.0 -> Icons.Default.TrendingDown
            else -> Icons.Default.TrendingFlat
        }, UiFormatters.formatSignedDelta(state.delta, 1), tint = MaterialTheme.colorScheme.primary)
    }
    Text("${stringResource(R.string.unit_mmol_l)} · ${UiFormatters.formatSignedDelta(state.delta, 1)}",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (shouldShowRawGlucoseCompanion(state.glucose, state.rawGlucose)) {
        Text(stringResource(R.string.overview_raw_glucose_compact, UiFormatters.formatMmol(state.rawGlucose, 1)),
            modifier = Modifier.testTag("overviewRawGlucoseCompanion"), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun ClarityTarget(status: TargetManagerLiveStatusUi, onClick: () -> Unit, modifier: Modifier) {
    Column(modifier.heightIn(min = 68.dp).testTag("clarityTargetSummary")
        .clickable(role = Role.Button, onClickLabel = stringResource(R.string.clarity_target_details), onClick = onClick)
        .padding(4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(stringResource(R.string.clarity_aaps_target), style = MaterialTheme.typography.labelMedium)
        Text(UiFormatters.formatMmol(status.currentTargetMmol, 1), modifier = Modifier.testTag("clarityObservedTarget"),
            style = MaterialTheme.typography.headlineMedium.copy(fontSize = 24.sp, lineHeight = 28.sp), fontWeight = FontWeight.SemiBold)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(clarityManagerModeLabel(status.mode), modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
            Icon(Icons.Default.ChevronRight, null, Modifier.size(16.dp))
        }
        if (status.availability == TargetManagerLiveStatusAvailabilityUi.CURRENT && status.proposedTargetMmol != null) {
            Text(stringResource(R.string.clarity_target_proposal, UiFormatters.formatMmol(status.proposedTargetMmol, 1)),
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else if (status.availability == TargetManagerLiveStatusAvailabilityUi.STALE) {
            Text(stringResource(R.string.overview_target_manager_status_stale), style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun ClarityMetric(label: String, value: String, unit: String, tag: String, onClick: () -> Unit, modifier: Modifier,
    description: String? = null, iobPair: OverviewIobPresentation? = null) {
    Column(modifier.heightIn(min = 48.dp).testTag(tag).clickable(role = Role.Button, onClick = onClick)
        .semantics { description?.let { contentDescription = it } }.padding(vertical = 4.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (iobPair != null) OverviewIobValue(iobPair, unit,
            MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
            MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant),
            Modifier.fillMaxWidth()) else Text(buildAnnotatedString {
            withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(value) }
            withStyle(SpanStyle(fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)) { append(" "); append(unit) }
        }, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
internal fun ClarityDetailsDialog(title: String, tag: String, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    AlertDialog(modifier = Modifier.testTag(tag), onDismissRequest = onDismiss,
        shape = RoundedCornerShape(8.dp),
        title = { Text(title) },
        text = { Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp), content = content) },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) } })
}

@Composable
internal fun clarityManagerModeLabel(mode: String?): String = stringResource(when (mode?.uppercase()) {
    "ACTIVE" -> R.string.clarity_mode_active
    "SHADOW" -> R.string.clarity_mode_shadow
    "OFF" -> R.string.clarity_mode_off
    else -> R.string.clarity_mode_unknown
})

@Composable
internal fun clarityEatingStatusLabel(status: EatingSoonUiStatus): String = stringResource(when (status) {
    EatingSoonUiStatus.IDLE -> R.string.clarity_eating_soon
    EatingSoonUiStatus.QUEUED -> R.string.clarity_eating_queued
    EatingSoonUiStatus.AWAITING_AAPS -> R.string.clarity_eating_waiting
    EatingSoonUiStatus.CONFIRMED -> R.string.clarity_eating_confirmed
    EatingSoonUiStatus.BLOCKED -> R.string.clarity_eating_blocked
    EatingSoonUiStatus.UNKNOWN -> R.string.clarity_eating_unknown
})

@Composable
internal fun clarityAge(minutes: Long?): String = minutes?.let {
    stringResource(R.string.clarity_age_minutes, it)
} ?: stringResource(R.string.placeholder_missing)
