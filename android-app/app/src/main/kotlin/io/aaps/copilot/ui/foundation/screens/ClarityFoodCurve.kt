package io.aaps.copilot.ui.foundation.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.aaps.copilot.R
import io.aaps.copilot.domain.nutrition.*
import io.aaps.copilot.domain.profile.MealAbsorptionProfile
import io.aaps.copilot.ui.foundation.components.InteractiveClinicalForecastChart
import io.aaps.copilot.ui.foundation.components.rememberInteractiveClinicalChartState
import io.aaps.copilot.ui.foundation.format.UiFormatters

@Composable
internal fun FoodDisplayStatusLabel(state: OverviewUiState) {
    Text(stringResource(when (state.foodDisplayStatus) {
        FoodDisplayStatus.CURRENT -> R.string.clarity_food_current
        FoodDisplayStatus.UPDATING -> R.string.clarity_food_updating
        FoodDisplayStatus.OUTDATED -> R.string.clarity_food_outdated
        FoodDisplayStatus.UNAVAILABLE -> R.string.clarity_food_unknown
        FoodDisplayStatus.INACTIVE -> R.string.clarity_food_none
    }), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.testTag("foodDisplayStatus"))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ClarityFoodDetailsDialog(state: OverviewUiState, onDismiss: () -> Unit,
    onMeal: () -> Unit, onEatingSoon: () -> Unit,
    onLoadHistory: suspend (Long, String) -> LentaMealHistory = { cutoff, zone ->
        LentaMealHistory(cutoff, zone, status = LentaMealHistoryStatus.UNAVAILABLE)
    }) {
    var showInfo by rememberSaveable { mutableStateOf(false) }
    var estimateMode by rememberSaveable { mutableStateOf(false) }
    var typeName by rememberSaveable { mutableStateOf(MealAbsorptionProfile.MIXED.name) }
    var portionName by rememberSaveable { mutableStateOf(MealPortion.MEDIUM.name) }
    val cutoff by rememberSaveable { mutableStateOf(System.currentTimeMillis()) }
    val zoneId by rememberSaveable { mutableStateOf(java.time.ZoneId.systemDefault().id) }
    val minute by rememberSaveable { mutableStateOf(java.time.Instant.ofEpochMilli(cutoff)
        .atZone(java.time.ZoneId.of(zoneId)).toLocalTime().toSecondOfDay() / 60) }
    var history by remember { mutableStateOf<LentaMealHistory?>(null) }
    var loading by remember { mutableStateOf(false) }
    val modes = rememberSaveableStateHolder()
    LaunchedEffect(estimateMode, cutoff, zoneId) {
        if (estimateMode && history == null) {
            loading = true
            try { history = onLoadHistory(cutoff, zoneId) } finally { loading = false }
        }
    }
    val type = MealAbsorptionProfile.valueOf(typeName)
    val portion = MealPortion.valueOf(portionName)
    val request = remember(type, portion, state.mealPortions, cutoff, zoneId, minute) {
        HybridMealResponseRequest(cutoff, type, portion, zoneId, minute, portionSettings = state.mealPortions)
    }
    val estimate = remember(request, history) {
        if (!state.mealPortions.isValid()) null else LentaMealEstimator().estimate(request,
            history ?: LentaMealHistory(cutoff, zoneId, status = LentaMealHistoryStatus.UNAVAILABLE))
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize().testTag("clarityFoodDetails"), color = MaterialTheme.colorScheme.surface) {
            BoxWithConstraints(Modifier.fillMaxSize().padding(12.dp)) {
                val fontReserve = 320.dp * (LocalDensity.current.fontScale - 1f).coerceAtLeast(0f)
                val curveHeight = (maxHeight - if (estimateMode) 420.dp + fontReserve else 360.dp)
                    .coerceIn(120.dp, 240.dp)
                Column(Modifier.fillMaxSize()) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        ClarityToolButton(Icons.Default.Close, stringResource(R.string.action_close), "clarityFoodClose", onDismiss)
                        Text(stringResource(R.string.clarity_food_details), Modifier.weight(1f),
                            style = MaterialTheme.typography.titleMedium)
                        ClarityToolButton(Icons.Default.Info, stringResource(R.string.clarity_details), "clarityFoodInfo") { showInfo = true }
                    }
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        listOf(false, true).forEachIndexed { index, selectedEstimate ->
                            SegmentedButton(selected = estimateMode == selectedEstimate,
                                onClick = { estimateMode = selectedEstimate },
                                shape = SegmentedButtonDefaults.itemShape(index, 2),
                                modifier = Modifier.heightIn(min = 48.dp).testTag(if (selectedEstimate) "lentaModeEstimate" else "lentaModeCurrent")) {
                                Text(stringResource(if (selectedEstimate) R.string.lenta_mode_estimate else R.string.lenta_mode_current))
                            }
                        }
                    }
                    modes.SaveableStateProvider(if (estimateMode) "estimate" else "current") {
                    Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (estimateMode) {
                            LentaMealEstimateView(estimate, type, portion, { typeName = it.name },
                                { portionName = it.name }, loading, curveHeight, request)
                        } else {
                        Text("${stringResource(R.string.metric_cob)} ${UiFormatters.formatGrams(state.currentCobGrams, 1)} ${stringResource(R.string.unit_g)}",
                            style = MaterialTheme.typography.headlineSmall, modifier = Modifier.testTag("clarityFoodCob"))
                        ClarityFoodCurve(state, curveHeight)
                        HorizontalDivider()
                        Text(stringResource(R.string.clarity_food_entered), style = MaterialTheme.typography.labelMedium)
                        val meals = clarityFoodTimelineEvents(state)
                        if (meals.isEmpty()) Text(stringResource(R.string.clarity_food_no_entries),
                            style = MaterialTheme.typography.bodySmall)
                        meals.forEach { meal ->
                            Row(Modifier.fillMaxWidth().testTag("clarityFoodMeal_${meal.localId}"),
                                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text(UiFormatters.formatTimestamp(meal.startTs), Modifier.weight(1f),
                                    style = MaterialTheme.typography.bodySmall)
                                Column(Modifier.weight(1f)) {
                                    Text("${UiFormatters.formatGrams(clarityMealEnteredGrams(meal), 1)} ${stringResource(R.string.unit_g)}",
                                        style = MaterialTheme.typography.titleSmall)
                                    val profile = when (meal.attributes["profile"]?.uppercase(java.util.Locale.US)) {
                                        "FAST" -> R.string.food_profile_fast_label
                                        "MIXED" -> R.string.food_profile_mixed_label
                                        "FATTY_PROTEIN" -> R.string.food_profile_slow_label
                                        else -> null
                                    }
                                    profile?.let { Text(stringResource(it), style = MaterialTheme.typography.bodySmall) }
                                }
                            }
                        }
                        }
                    }
                    }
                    HorizontalDivider()
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = onEatingSoon,
                            modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("clarityFoodEatingSoon")) {
                            Icon(Icons.Default.Restaurant, null)
                            Text(clarityEatingStatusLabel(state.eatingSoon.status), Modifier.padding(start = 8.dp))
                        }
                        ClarityToolButton(Icons.Default.Add, stringResource(R.string.clarity_add_meal), "clarityFoodAddMeal", onMeal)
                    }
                }
            }
        }
    }
    if (showInfo) ClarityDetailsDialog(stringResource(R.string.clarity_food_details), "clarityFoodModelDetails", { showInfo = false }) {
        if (estimateMode) {
            Text(stringResource(R.string.lenta_estimate_info))
            Text(stringResource(R.string.lenta_estimate_history_info))
        } else {
            Text(stringResource(if (state.chart.mealImpactGiAdjusted) R.string.meal_impact_gi_legend else R.string.meal_impact_legend))
            Text(stringResource(R.string.clarity_food_entries_context))
        }
    }
}

@Composable
internal fun ClarityFoodCurve(state: OverviewUiState, chartHeight: Dp = 240.dp) {
    val chart = state.chart.copy(historyPoints = emptyList(), futurePath = emptyList(), futureCi = emptyList(),
        events = emptyList(), showEvents = false)
    val interaction = rememberInteractiveClinicalChartState(chart)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FoodDisplayStatusLabel(state)
        Text(stringResource(R.string.clarity_food_axis), style = MaterialTheme.typography.labelSmall)
        val display = state.acceptedFoodDisplay
        display?.let {
            Text(stringResource(R.string.clarity_food_origin, UiFormatters.formatTimestamp(it.generatedAt)),
                style = MaterialTheme.typography.bodySmall)
        }
        val endKnown = display != null && display.complete == true &&
            state.foodDisplayStatus in setOf(FoodDisplayStatus.CURRENT, FoodDisplayStatus.UPDATING) &&
            display.points.size >= 2 && display.points.last().value > display.points.first().value
        Text(if (endKnown) stringResource(R.string.clarity_food_period, UiFormatters.formatTimestamp(display!!.points.last().ts))
            else stringResource(R.string.clarity_food_unknown), style = MaterialTheme.typography.bodySmall)
        if (interaction != null) {
            val description = "${UiFormatters.formatTimestamp(interaction.viewport.startTs)} - ${UiFormatters.formatTimestamp(interaction.viewport.endTs)}"
            Box(Modifier.fillMaxWidth().testTag("foodFullCurve").semantics { stateDescription = description }) {
            InteractiveClinicalForecastChart(chart, interaction,
                contentDescription = stringResource(R.string.clarity_food_details),
                viewportStateDescription = description,
                emptyText = stringResource(R.string.meal_impact_unavailable),
                nowLabel = stringResource(R.string.clarity_food_calculation), plus30Label = stringResource(R.string.overview_chart_plus_30),
                zoomInLabel = stringResource(R.string.overview_chart_zoom_in), zoomOutLabel = stringResource(R.string.overview_chart_zoom_out),
                olderLabel = stringResource(R.string.overview_chart_older), newerLabel = stringResource(R.string.overview_chart_newer),
                resetLabel = stringResource(R.string.clarity_food_fit), chartHeight = chartHeight, showDisplayRange = false)
            }
        } else {
            Text(stringResource(R.string.meal_impact_unavailable), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ClarityToolButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, tag: String, onClick: () -> Unit) {
    ClarityToolButton(icon, label, tag, true, onClick)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ClarityToolButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, tag: String,
    enabled: Boolean, onClick: () -> Unit) {
    TooltipBox(positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = { PlainTooltip { Text(label) } }, state = rememberTooltipState()) {
        IconButton(modifier = Modifier.size(48.dp).testTag(tag), enabled = enabled, onClick = onClick) { Icon(icon, label) }
    }
}
