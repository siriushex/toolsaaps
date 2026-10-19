package io.aaps.copilot.ui.foundation.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import io.aaps.copilot.R
import io.aaps.copilot.config.AiHistoryUploadSettings
import io.aaps.copilot.ui.foundation.design.Spacing

@Composable
internal fun ServerAiHistoryUploadSection(
    settings: AiHistoryUploadSettings,
    connected: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    onIntervalChange: (Int) -> Unit,
    onUpload: () -> Unit
) {
    var menuOpen by remember { mutableStateOf(false) }
    val backgroundLabel = stringResource(R.string.settings_history_background)
    Column(Modifier.fillMaxWidth().testTag("history_upload_settings"),
        verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(backgroundLabel, Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium)
            Switch(settings.enabled, onEnabledChange,
                modifier = Modifier.testTag("history_upload_enabled").semantics { contentDescription = backgroundLabel })
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween) {
            Text(stringResource(R.string.settings_history_interval), Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium)
            Column {
                TextButton(onClick = { menuOpen = true }, enabled = settings.enabled,
                    modifier = Modifier.testTag("history_upload_interval")) {
                    Text(stringResource(R.string.settings_history_minutes, settings.intervalMinutes))
                }
                DropdownMenu(expanded = menuOpen && settings.enabled, onDismissRequest = { menuOpen = false }) {
                    AiHistoryUploadSettings.INTERVALS.forEach { minutes ->
                        DropdownMenuItem(text = { Text(stringResource(R.string.settings_history_minutes, minutes)) },
                            onClick = { menuOpen = false; onIntervalChange(minutes) },
                            modifier = Modifier.testTag("history_upload_interval_$minutes"))
                    }
                }
            }
        }
        OutlinedButton(onClick = onUpload, enabled = connected,
            modifier = Modifier.testTag("history_upload_now")) {
            Icon(Icons.Default.Sync, contentDescription = null)
            Text(stringResource(R.string.settings_history_send))
        }
    }
}
