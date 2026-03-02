package io.aaps.copilot.ui.foundation.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import io.aaps.copilot.R
import io.aaps.copilot.ui.foundation.components.PredictionHorizonChip
import io.aaps.copilot.ui.foundation.components.SectionHeader
import io.aaps.copilot.ui.foundation.design.AppElevation
import io.aaps.copilot.ui.foundation.design.Spacing
import io.aaps.copilot.ui.foundation.theme.AapsCopilotTheme

@Composable
fun ForecastScreen(
    state: ForecastUiState,
    modifier: Modifier = Modifier
) {
    ScreenStateLayout(
        loadState = state.loadState,
        isStale = state.isStale,
        errorText = state.errorText,
        emptyText = stringResource(id = R.string.forecast_empty)
    ) {
        LazyColumn(
            modifier = modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            item {
                SectionHeader(text = stringResource(id = R.string.section_forecast_horizons))
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
                SectionHeader(text = stringResource(id = R.string.section_forecast_quality))
            }
            items(state.qualityLines) { line ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    elevation = CardDefaults.cardElevation(defaultElevation = AppElevation.level1)
                ) {
                    Text(
                        text = line,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(Spacing.md)
                    )
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun ForecastScreenPreview() {
    AapsCopilotTheme {
        ForecastScreen(
            state = ForecastUiState(
                loadState = ScreenLoadState.READY,
                isStale = false,
                horizons = listOf(
                    HorizonPredictionUi(5, 6.1, 5.8, 6.4),
                    HorizonPredictionUi(30, 6.6, 6.0, 7.2),
                    HorizonPredictionUi(60, 7.0, 6.2, 7.8)
                ),
                qualityLines = listOf(
                    "5m MAE 0.22 | RMSE 0.35 | MARD 4.8% | n=390",
                    "60m MAE 0.55 | RMSE 0.82 | MARD 8.7% | n=387"
                )
            )
        )
    }
}
