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
import io.aaps.copilot.ui.foundation.components.DebugRow
import io.aaps.copilot.ui.foundation.components.MetricCard
import io.aaps.copilot.ui.foundation.components.MetricStatus
import io.aaps.copilot.ui.foundation.components.SectionHeader
import io.aaps.copilot.ui.foundation.design.AppElevation
import io.aaps.copilot.ui.foundation.design.Spacing
import io.aaps.copilot.ui.foundation.theme.AapsCopilotTheme
import java.util.Locale

@Composable
fun UamScreen(
    state: UamUiState,
    modifier: Modifier = Modifier
) {
    ScreenStateLayout(
        loadState = state.loadState,
        isStale = state.isStale,
        errorText = state.errorText,
        emptyText = stringResource(id = R.string.uam_empty)
    ) {
        LazyColumn(
            modifier = modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            item {
                SectionHeader(text = stringResource(id = R.string.section_uam_inferred))
            }
            item {
                MetricCard(
                    title = stringResource(id = R.string.label_uam_inferred),
                    value = state.inferredCarbsGrams,
                    unit = stringResource(id = R.string.unit_g),
                    status = boolStatus(state.inferredActive),
                    subtitle = activeLabel(state.inferredActive),
                    modifier = Modifier.fillMaxWidth(),
                    decimals = 1
                )
            }
            item {
                DetailCard(
                    lines = listOf(
                        stringResource(id = R.string.label_confidence) to confidenceText(state.inferredConfidence),
                        stringResource(id = R.string.label_active_state) to activeLabel(state.inferredActive)
                    )
                )
            }

            item {
                SectionHeader(text = stringResource(id = R.string.section_uam_calculated))
            }
            item {
                MetricCard(
                    title = stringResource(id = R.string.label_uam_calculated),
                    value = state.calculatedCarbsGrams,
                    unit = stringResource(id = R.string.unit_g),
                    status = boolStatus(state.calculatedActive),
                    subtitle = activeLabel(state.calculatedActive),
                    modifier = Modifier.fillMaxWidth(),
                    decimals = 1
                )
            }
            item {
                DetailCard(
                    lines = listOf(
                        stringResource(id = R.string.label_confidence) to confidenceText(state.calculatedConfidence),
                        stringResource(id = R.string.label_active_state) to activeLabel(state.calculatedActive)
                    )
                )
            }
            item {
                Text(
                    text = stringResource(id = R.string.uam_hint),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(bottom = Spacing.md)
                )
            }
        }
    }
}

@Composable
private fun DetailCard(lines: List<Pair<String, String>>) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = AppElevation.level1)
    ) {
        LazyColumn(
            modifier = Modifier.padding(Spacing.md),
            verticalArrangement = Arrangement.spacedBy(Spacing.xxs)
        ) {
            items(lines) { (k, v) ->
                DebugRow(key = k, value = v)
            }
        }
    }
}

@Composable
private fun activeLabel(active: Boolean?): String {
    return when (active) {
        true -> stringResource(id = R.string.status_on_short)
        false -> stringResource(id = R.string.status_off_short)
        null -> stringResource(id = R.string.placeholder_missing)
    }
}

private fun boolStatus(active: Boolean?): MetricStatus {
    return when (active) {
        true -> MetricStatus.GOOD
        false -> MetricStatus.NORMAL
        null -> MetricStatus.NORMAL
    }
}

@Composable
private fun confidenceText(value: Double?): String {
    return value?.let { String.format(Locale.US, "%.0f%%", it * 100.0) }
        ?: stringResource(id = R.string.placeholder_missing)
}

@Preview(showBackground = true)
@Composable
private fun UamScreenPreview() {
    AapsCopilotTheme {
        UamScreen(
            state = UamUiState(
                loadState = ScreenLoadState.READY,
                isStale = false,
                inferredActive = true,
                inferredCarbsGrams = 24.0,
                inferredConfidence = 0.72,
                calculatedActive = true,
                calculatedCarbsGrams = 19.0,
                calculatedConfidence = 0.66
            )
        )
    }
}
