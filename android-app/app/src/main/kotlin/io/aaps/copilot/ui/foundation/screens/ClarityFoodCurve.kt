package io.aaps.copilot.ui.foundation.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FirstPage
import androidx.compose.material.icons.filled.LastPage
import androidx.compose.material.icons.filled.ZoomOutMap
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.aaps.copilot.R
import io.aaps.copilot.ui.foundation.components.ClinicalChartViewportMode
import io.aaps.copilot.ui.foundation.components.ClinicalChartViewportSnapshot
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

@Composable
internal fun ClarityFoodCurve(state: OverviewUiState) {
    val chart = state.chart.copy(historyPoints = emptyList(), futurePath = emptyList(), futureCi = emptyList(),
        events = emptyList(), showEvents = false)
    val interaction = rememberInteractiveClinicalChartState(chart)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FoodDisplayStatusLabel(state)
        val display = state.acceptedFoodDisplay
        display?.let {
            Text(stringResource(R.string.clarity_food_origin, UiFormatters.formatTimestamp(it.generatedAt)),
                style = MaterialTheme.typography.bodySmall)
        }
        val endKnown = display != null && display.complete == true &&
            state.foodDisplayStatus in setOf(FoodDisplayStatus.CURRENT, FoodDisplayStatus.UPDATING) &&
            display.points.last().value > display.points.first().value
        Text(if (endKnown) stringResource(R.string.clarity_food_period, UiFormatters.formatTimestamp(display!!.points.last().ts))
            else stringResource(R.string.clarity_food_unknown), style = MaterialTheme.typography.bodySmall)
        if (interaction != null) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                ClarityToolButton(Icons.Default.FirstPage, stringResource(R.string.clarity_food_start), "foodCurveStart") {
                    interaction.restore(ClinicalChartViewportSnapshot(90 * 60_000L,
                        interaction.domain.startTs + 90 * 60_000L - interaction.domain.nowTs, ClinicalChartViewportMode.EXPLORE))
                }
                ClarityToolButton(Icons.Default.ZoomOutMap, stringResource(R.string.clarity_food_fit), "foodCurveFit") {
                    interaction.restore(ClinicalChartViewportSnapshot(interaction.domain.durationMs,
                        interaction.domain.endTs - interaction.domain.nowTs, ClinicalChartViewportMode.EXPLORE))
                }
                ClarityToolButton(Icons.Default.LastPage, stringResource(R.string.clarity_food_end), "foodCurveEnd") {
                    interaction.restore(ClinicalChartViewportSnapshot(90 * 60_000L,
                        interaction.domain.endTs - interaction.domain.nowTs, ClinicalChartViewportMode.EXPLORE))
                }
            }
            InteractiveClinicalForecastChart(chart, interaction,
                contentDescription = stringResource(R.string.clarity_food_details),
                viewportStateDescription = "${UiFormatters.formatTimestamp(interaction.viewport.startTs)} - ${UiFormatters.formatTimestamp(interaction.viewport.endTs)}",
                emptyText = stringResource(R.string.meal_impact_unavailable),
                nowLabel = stringResource(R.string.overview_chart_now), plus30Label = stringResource(R.string.overview_chart_plus_30),
                zoomInLabel = stringResource(R.string.overview_chart_zoom_in), zoomOutLabel = stringResource(R.string.overview_chart_zoom_out),
                olderLabel = stringResource(R.string.overview_chart_older), newerLabel = stringResource(R.string.overview_chart_newer),
                resetLabel = stringResource(R.string.clarity_food_fit), modifier = Modifier.testTag("foodFullCurve"))
            Text(stringResource(if (chart.mealImpactGiAdjusted) R.string.meal_impact_gi_legend else R.string.meal_impact_legend),
                style = MaterialTheme.typography.bodySmall)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ClarityToolButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, tag: String, onClick: () -> Unit) {
    TooltipBox(positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = { PlainTooltip { Text(label) } }, state = rememberTooltipState()) {
        IconButton(modifier = Modifier.testTag(tag), onClick = onClick) { Icon(icon, label) }
    }
}
