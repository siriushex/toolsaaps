package io.aaps.copilot.ui.foundation.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import io.aaps.copilot.R
import io.aaps.copilot.service.ForecastTestState
import io.aaps.copilot.service.ForecastTestStatus

@Composable
internal fun ForecastTestSettings(state: ForecastTestState, onToggle: (Boolean) -> Unit) {
    var confirm by rememberSaveable { mutableStateOf(false) }
    val title = stringResource(R.string.forecast_test_title)
    Column {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(stringResource(R.string.forecast_test_subtitle), style = MaterialTheme.typography.bodySmall)
            }
            Switch(state.active, onCheckedChange = { if (it) confirm = true else onToggle(false) },
                modifier = Modifier.testTag("forecast_test_toggle").semantics { contentDescription = title })
        }
        if (state.status != ForecastTestStatus.OFF) {
            Text(stringResource(when (state.status) {
                ForecastTestStatus.STARTING -> R.string.forecast_test_starting
                ForecastTestStatus.CALCULATING -> R.string.forecast_test_calculating
                ForecastTestStatus.COMPLETED -> R.string.forecast_test_completed
                ForecastTestStatus.SKIPPED -> R.string.forecast_test_skipped
                ForecastTestStatus.TIMEOUT -> R.string.forecast_test_timeout
                ForecastTestStatus.FAILED -> R.string.forecast_test_failed
                ForecastTestStatus.EXPIRED -> R.string.forecast_test_expired
                ForecastTestStatus.THERMAL -> R.string.forecast_test_thermal
                ForecastTestStatus.POWER_SAVE -> R.string.forecast_test_power_save
                else -> R.string.forecast_test_unavailable
            }), style = MaterialTheme.typography.bodySmall)
        }
        if (state.active) Text(stringResource(R.string.forecast_test_remaining, state.remainingMinutes),
            style = MaterialTheme.typography.bodySmall)
        state.lastDurationMs?.let {
            Text(stringResource(R.string.forecast_test_results, state.completedCycles, it),
                style = MaterialTheme.typography.bodySmall)
        }
    }
    if (confirm) AlertDialog(
        onDismissRequest = { confirm = false },
        title = { Text(stringResource(R.string.forecast_test_title)) },
        text = { Text(stringResource(R.string.forecast_test_warning)) },
        confirmButton = { TextButton(onClick = { confirm = false; onToggle(true) }) {
            Text(stringResource(R.string.forecast_test_start))
        } },
        dismissButton = { TextButton(onClick = { confirm = false }) {
            Text(stringResource(android.R.string.cancel))
        } }
    )
}
