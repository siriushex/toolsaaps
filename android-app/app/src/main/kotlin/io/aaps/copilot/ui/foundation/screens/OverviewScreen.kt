package io.aaps.copilot.ui.foundation.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import io.aaps.copilot.R
import io.aaps.copilot.ui.foundation.components.MetricCard
import io.aaps.copilot.ui.foundation.components.MetricStatus
import io.aaps.copilot.ui.foundation.components.PredictionHorizonChip
import io.aaps.copilot.ui.foundation.components.SectionHeader
import io.aaps.copilot.ui.foundation.design.Spacing
import io.aaps.copilot.ui.foundation.theme.AapsCopilotTheme
import java.util.Locale

@Composable
fun OverviewScreen(
    state: OverviewUiState,
    modifier: Modifier = Modifier
) {
    ScreenStateLayout(
        loadState = state.loadState,
        isStale = state.isStale,
        errorText = state.errorText,
        emptyText = stringResource(id = R.string.overview_empty)
    ) {
        LazyColumn(
            modifier = modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            item {
                SectionHeader(text = stringResource(id = R.string.section_overview_metrics))
            }
            item {
                MetricCard(
                    title = stringResource(id = R.string.metric_current_glucose),
                    value = state.glucose,
                    unit = stringResource(id = R.string.unit_mmol_l),
                    subtitle = state.glucose?.let { glucoseStatusSubtitle(it) },
                    status = glucoseStatus(state.glucose),
                    modifier = Modifier.fillMaxWidth()
                )
            }
            item {
                MetricCard(
                    title = stringResource(id = R.string.metric_delta),
                    value = state.delta,
                    unit = stringResource(id = R.string.unit_mmol_l_5m),
                    subtitle = state.delta?.let { deltaSubtitle(it) },
                    status = deltaStatus(state.delta),
                    modifier = Modifier.fillMaxWidth()
                )
            }
            item {
                MetricCard(
                    title = stringResource(id = R.string.metric_iob),
                    value = state.iobUnits,
                    unit = stringResource(id = R.string.unit_u),
                    status = MetricStatus.NORMAL,
                    modifier = Modifier.fillMaxWidth(),
                    decimals = 2
                )
            }
            item {
                MetricCard(
                    title = stringResource(id = R.string.metric_cob),
                    value = state.cobGrams,
                    unit = stringResource(id = R.string.unit_g),
                    status = MetricStatus.NORMAL,
                    modifier = Modifier.fillMaxWidth(),
                    decimals = 1
                )
            }
            item {
                SectionHeader(text = stringResource(id = R.string.section_overview_predictions))
            }
            items(state.horizons) { horizon ->
                PredictionHorizonChip(
                    horizonMinutes = horizon.horizonMinutes,
                    pred = horizon.pred,
                    ciLow = horizon.ciLow,
                    ciHigh = horizon.ciHigh,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            item {
                Text(
                    text = stringResource(id = R.string.overview_hint),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(bottom = Spacing.md)
                )
            }
        }
    }
}

private fun glucoseStatus(value: Double?): MetricStatus {
    return when {
        value == null -> MetricStatus.NORMAL
        value < 3.9 -> MetricStatus.CRITICAL
        value > 10.0 -> MetricStatus.WARNING
        else -> MetricStatus.GOOD
    }
}

private fun deltaStatus(value: Double?): MetricStatus {
    return when {
        value == null -> MetricStatus.NORMAL
        value >= 0.25 || value <= -0.25 -> MetricStatus.WARNING
        else -> MetricStatus.GOOD
    }
}

@Composable
private fun glucoseStatusSubtitle(value: Double): String {
    return when {
        value < 3.9 -> stringResource(id = R.string.status_alert)
        value > 10.0 -> stringResource(id = R.string.status_warn)
        else -> stringResource(id = R.string.status_ok)
    }
}

@Composable
private fun deltaSubtitle(value: Double): String {
    val sign = if (value >= 0) "+" else ""
    return String.format(
        Locale.US,
        "%s%.2f",
        sign,
        value
    )
}

@Preview(showBackground = true)
@Composable
private fun OverviewScreenPreview() {
    AapsCopilotTheme {
        OverviewScreen(
            state = OverviewUiState(
                loadState = ScreenLoadState.READY,
                isStale = false,
                glucose = 6.4,
                delta = 0.12,
                iobUnits = 1.8,
                cobGrams = 22.0,
                horizons = listOf(
                    HorizonPredictionUi(5, 6.5, 6.1, 6.9),
                    HorizonPredictionUi(30, 6.8, 6.2, 7.4),
                    HorizonPredictionUi(60, 7.1, 6.4, 7.9)
                )
            )
        )
    }
}
