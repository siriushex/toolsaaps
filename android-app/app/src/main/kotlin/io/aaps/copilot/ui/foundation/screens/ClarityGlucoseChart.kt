package io.aaps.copilot.ui.foundation.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import io.aaps.copilot.R
import io.aaps.copilot.ui.foundation.components.ClinicalChartViewportMode
import io.aaps.copilot.ui.foundation.components.ClinicalChartViewportSnapshot
import io.aaps.copilot.ui.foundation.components.ClinicalForecastChart
import io.aaps.copilot.ui.foundation.components.ClinicalForecastMarker
import io.aaps.copilot.ui.foundation.components.InteractiveClinicalForecastChart
import io.aaps.copilot.ui.foundation.components.rememberInteractiveClinicalChartState
import io.aaps.copilot.ui.foundation.format.UiFormatters

internal fun clarityForecastPoints(state: OverviewUiState): List<Pair<Int, ChartPointUi>> {
    if (!state.forecastsCurrent || state.isStale) return emptyList()
    val origin = state.chart.historyPoints.lastOrNull { it.value.isFinite() }?.ts ?: return emptyList()
    if (origin <= 0 || origin > Long.MAX_VALUE - 60 * 60_000L) return emptyList()
    return state.horizons.filter { it.horizonMinutes in listOf(5, 30, 60) && it.pred?.isFinite() == true }
        .distinctBy { it.horizonMinutes }.mapNotNull { horizon ->
            val point = state.chart.futurePath.firstOrNull { it.ts == origin + horizon.horizonMinutes * 60_000L &&
                it.value.isFinite() && kotlin.math.abs(it.value - horizon.pred!!) < 0.000001 } ?: return@mapNotNull null
            horizon.horizonMinutes to point
        }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ClarityGlucoseChart(state: OverviewUiState, expanded: Boolean, onExpand: () -> Unit,
    onHorizon: (Int) -> Unit, compactHeight: Dp = 190.dp, statusContent: @Composable () -> Unit = {}) {
    // The full food tail belongs to its own view, not the one-hour glucose forecast.
    val chart = state.chart.copy(mealImpactPoints = emptyList(), mealImpactComplete = null, mealImpactGiAdjusted = false)
    val interaction = rememberInteractiveClinicalChartState(chart)
    var hours by rememberSaveable { mutableIntStateOf(3) }
    LaunchedEffect(interaction, hours) {
        interaction?.restore(ClinicalChartViewportSnapshot((hours + 1) * 60 * 60_000L,
            60 * 60_000L, ClinicalChartViewportMode.FOLLOW_LIVE))
    }
    val description = stringResource(R.string.overview_chart_title)
    val markers = clarityForecastPoints(state).map { (minutes, point) ->
        val horizon = state.horizons.first { it.horizonMinutes == minutes }
        ClinicalForecastMarker(minutes, point, stringResource(R.string.balance_forecast_marker, minutes,
            UiFormatters.formatMmol(point.value, 1), UiFormatters.formatMmol(horizon.ciLow, 1), UiFormatters.formatMmol(horizon.ciHigh, 1)))
    }
    BoxWithConstraints(Modifier.fillMaxWidth().testTag("clarityGlucoseChart")) {
    val compactToolbar = expanded && maxWidth >= 320.dp && LocalDensity.current.fontScale < 1.3f
    val ranges: @Composable () -> Unit = {
        listOf(1, 3, 6).forEach { value ->
            TextButton(onClick = { hours = value }, contentPadding = PaddingValues(horizontal = 4.dp),
                modifier = Modifier.width(48.dp).testTag("clarityChartRange$value")
                    .semantics { selected = hours == value }) {
                Text(stringResource(R.string.balance_chart_hours, value),
                    color = if (hours == value) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    Column(Modifier.fillMaxWidth()) {
        FlowRow(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.Center) {
            if (expanded) androidx.compose.foundation.layout.Spacer(Modifier.weight(1f)) else {
                Text(stringResource(R.string.clarity_cgm_age_compact, clarityAge(state.sampleAgeMinutes)),
                    Modifier.widthIn(min = 44.dp * LocalDensity.current.fontScale).weight(1f).align(Alignment.CenterVertically), style = MaterialTheme.typography.bodySmall)
                Text(stringResource(R.string.clarity_sync_age_compact, clarityAge(state.syncAgeMinutes)),
                    Modifier.widthIn(min = 44.dp * LocalDensity.current.fontScale).weight(1f).align(Alignment.CenterVertically), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (compactToolbar) ranges()
            if (!expanded) statusContent()
            if (expanded || markers.none { interaction != null && it.point.ts in interaction.viewport.startTs..interaction.viewport.endTs }) {
                ClarityToolButton(Icons.Default.Info, stringResource(R.string.balance_forecast_details), "clarityForecastInfo") { onHorizon(30) }
            }
            if (!expanded) ClarityToolButton(Icons.Default.Fullscreen, stringResource(R.string.balance_chart_expand), "clarityChartExpand", onExpand)
            else ClarityToolButton(Icons.Default.Refresh, stringResource(R.string.overview_chart_reset_view), "clarityChartReset") { interaction?.reset() }
        }
        if (expanded && !compactToolbar) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { ranges() }
        val height = if (expanded) 300.dp else compactHeight
        if (interaction == null) {
            ClinicalForecastChart(chart, description, stringResource(R.string.overview_chart_empty),
                stringResource(R.string.overview_chart_now), stringResource(R.string.overview_chart_plus_30), chartHeight = height)
        } else {
            InteractiveClinicalForecastChart(chart, interaction, description,
                "${UiFormatters.formatTimestamp(interaction.viewport.startTs)} - ${UiFormatters.formatTimestamp(interaction.viewport.endTs)}",
                stringResource(R.string.overview_chart_empty), stringResource(R.string.overview_chart_now),
                stringResource(R.string.overview_chart_plus_30), stringResource(R.string.overview_chart_zoom_in),
                stringResource(R.string.overview_chart_zoom_out), stringResource(R.string.overview_chart_older),
                stringResource(R.string.overview_chart_newer), stringResource(R.string.overview_chart_reset_view),
                chartHeight = height, forecastMarkers = markers, onForecastMarkerClick = onHorizon)
        }
    }
    }
}
