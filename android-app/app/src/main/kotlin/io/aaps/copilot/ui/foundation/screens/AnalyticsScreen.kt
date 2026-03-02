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
import io.aaps.copilot.ui.foundation.components.SectionHeader
import io.aaps.copilot.ui.foundation.design.AppElevation
import io.aaps.copilot.ui.foundation.design.Spacing
import io.aaps.copilot.ui.foundation.theme.AapsCopilotTheme

@Composable
fun AnalyticsScreen(
    state: AnalyticsUiState,
    modifier: Modifier = Modifier
) {
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
                SectionHeader(text = stringResource(id = R.string.section_analytics_quality))
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
            item {
                SectionHeader(text = stringResource(id = R.string.section_analytics_baseline))
            }
            items(state.baselineDeltaLines) { line ->
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
private fun AnalyticsScreenPreview() {
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
                )
            )
        )
    }
}
