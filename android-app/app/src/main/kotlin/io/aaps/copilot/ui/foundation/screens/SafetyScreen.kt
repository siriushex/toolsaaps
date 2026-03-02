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
import io.aaps.copilot.ui.foundation.components.SafetyBanner
import io.aaps.copilot.ui.foundation.components.SafetyBannerType
import io.aaps.copilot.ui.foundation.components.SectionHeader
import io.aaps.copilot.ui.foundation.design.AppElevation
import io.aaps.copilot.ui.foundation.design.Spacing
import io.aaps.copilot.ui.foundation.theme.AapsCopilotTheme

@Composable
fun SafetyScreen(
    state: SafetyUiState,
    modifier: Modifier = Modifier
) {
    val debugRows = listOf(
        stringResource(id = R.string.label_kill_switch) to if (state.killSwitchEnabled) {
            stringResource(id = R.string.status_on_short)
        } else {
            stringResource(id = R.string.status_off_short)
        },
        stringResource(id = R.string.label_sensor_quality) to state.sensorQualitySummary
    )

    ScreenStateLayout(
        loadState = state.loadState,
        isStale = state.isStale,
        errorText = state.errorText,
        emptyText = stringResource(id = R.string.safety_empty)
    ) {
        LazyColumn(
            modifier = modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            item {
                SafetyBanner(
                    type = if (state.killSwitchEnabled) SafetyBannerType.WARNING else SafetyBannerType.SUCCESS,
                    text = if (state.killSwitchEnabled) {
                        stringResource(id = R.string.safety_kill_switch_on)
                    } else {
                        stringResource(id = R.string.safety_kill_switch_off)
                    }
                )
            }
            item {
                SectionHeader(text = stringResource(id = R.string.section_safety_limits))
            }
            item {
                MetricCard(
                    title = stringResource(id = R.string.metric_base_target),
                    value = state.baseTarget,
                    unit = stringResource(id = R.string.unit_mmol_l),
                    status = MetricStatus.NORMAL,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            item {
                MetricCard(
                    title = stringResource(id = R.string.metric_stale_limit),
                    value = state.staleMinutesLimit.toDouble(),
                    unit = stringResource(id = R.string.unit_minutes),
                    status = MetricStatus.NORMAL,
                    modifier = Modifier.fillMaxWidth(),
                    decimals = 0
                )
            }
            item {
                MetricCard(
                    title = stringResource(id = R.string.metric_max_actions),
                    value = state.maxActionsIn6h.toDouble(),
                    unit = stringResource(id = R.string.unit_actions_6h),
                    status = MetricStatus.NORMAL,
                    modifier = Modifier.fillMaxWidth(),
                    decimals = 0
                )
            }
            item {
                SectionHeader(text = stringResource(id = R.string.section_safety_debug))
            }
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    elevation = CardDefaults.cardElevation(defaultElevation = AppElevation.level1)
                ) {
                    LazyColumn(
                        modifier = Modifier.padding(Spacing.md),
                        verticalArrangement = Arrangement.spacedBy(Spacing.xxs)
                    ) {
                        items(debugRows) { (k, v) ->
                            DebugRow(key = k, value = v)
                        }
                    }
                }
            }
            item {
                Text(
                    text = stringResource(id = R.string.safety_hint),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(bottom = Spacing.md)
                )
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun SafetyScreenPreview() {
    AapsCopilotTheme {
        SafetyScreen(
            state = SafetyUiState(
                loadState = ScreenLoadState.READY,
                isStale = false,
                killSwitchEnabled = false,
                baseTarget = 5.5,
                staleMinutesLimit = 10,
                maxActionsIn6h = 4,
                sensorQualitySummary = "score=0.92, blocked=no"
            )
        )
    }
}
