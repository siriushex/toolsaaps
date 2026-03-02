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
import io.aaps.copilot.ui.foundation.components.SectionHeader
import io.aaps.copilot.ui.foundation.design.AppElevation
import io.aaps.copilot.ui.foundation.design.Spacing
import io.aaps.copilot.ui.foundation.theme.AapsCopilotTheme
import java.util.Locale

@Composable
fun SettingsScreen(
    state: SettingsUiState,
    modifier: Modifier = Modifier
) {
    val connectionRows = listOf(
        stringResource(id = R.string.settings_base_target) to String.format(Locale.US, "%.2f", state.baseTarget),
        stringResource(id = R.string.settings_nightscout_url) to state.nightscoutUrl.ifBlank {
            stringResource(id = R.string.placeholder_missing)
        },
        stringResource(id = R.string.settings_resolved_url) to state.resolvedNightscoutUrl.ifBlank {
            stringResource(id = R.string.placeholder_missing)
        },
        stringResource(id = R.string.settings_insulin_profile) to state.insulinProfileId,
        stringResource(id = R.string.settings_local_nightscout) to if (state.localNightscoutEnabled) {
            stringResource(id = R.string.status_on_short)
        } else {
            stringResource(id = R.string.status_off_short)
        }
    )

    ScreenStateLayout(
        loadState = state.loadState,
        isStale = state.isStale,
        errorText = state.errorText,
        emptyText = stringResource(id = R.string.settings_empty)
    ) {
        LazyColumn(
            modifier = modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            item {
                SectionHeader(text = stringResource(id = R.string.section_settings_connection))
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
                        items(connectionRows) { (key, value) ->
                            DebugRow(key = key, value = value)
                        }
                    }
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun SettingsScreenPreview() {
    AapsCopilotTheme {
        SettingsScreen(
            state = SettingsUiState(
                loadState = ScreenLoadState.READY,
                isStale = false,
                baseTarget = 5.5,
                nightscoutUrl = "https://example.ns",
                insulinProfileId = "NOVORAPID",
                localNightscoutEnabled = true,
                resolvedNightscoutUrl = "https://127.0.0.1:17582"
            )
        )
    }
}
