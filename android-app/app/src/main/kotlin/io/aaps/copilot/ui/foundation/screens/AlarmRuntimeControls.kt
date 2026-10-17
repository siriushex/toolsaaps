package io.aaps.copilot.ui.foundation.screens

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.aaps.copilot.CopilotApp
import io.aaps.copilot.R
import io.aaps.copilot.data.repository.GlucoseAlarmOwnerPhase
import io.aaps.copilot.data.repository.GlucoseAlarmOwnerState
import io.aaps.copilot.service.GlucoseAlarmRuntimeState
import io.aaps.copilot.service.GlucoseAlarmServiceController
import io.aaps.copilot.service.alarmRuntimeStatusText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class AlarmRuntimeViewModel(app: Application) : AndroidViewModel(app) {
    private val container = (app as CopilotApp).container
    val enabled = container.settingsStore.settings.map { it.localAlarmRuntimeEnabled }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val state = GlucoseAlarmRuntimeState.state
    private val mutableBusy = MutableStateFlow(false)
    val busy = mutableBusy.asStateFlow()
    private val mutableFailure = MutableStateFlow(false)
    val failure = mutableFailure.asStateFlow()

    fun toggle(expected: Boolean, next: Boolean) {
        if (mutableBusy.value) return
        mutableBusy.value = true
        viewModelScope.launch {
            try {
                if (!next) container.glucoseAlarmRouter.stop()
                val saved = container.settingsStore.setLocalAlarmRuntimeEnabled(expected, next)
                mutableFailure.value = !saved
                GlucoseAlarmServiceController.reconcile(getApplication())
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutableFailure.value = true }
            finally { mutableBusy.value = false }
        }
    }

    fun retry() { viewModelScope.launch { GlucoseAlarmServiceController.reconcile(getApplication()) } }
}

@Composable
fun AlarmRuntimeControls(model: AlarmRuntimeViewModel = viewModel()) {
    val enabled by model.enabled.collectAsStateWithLifecycle()
    val state by model.state.collectAsStateWithLifecycle()
    val busy by model.busy.collectAsStateWithLifecycle()
    val failure by model.failure.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var settingsFailed by remember { mutableStateOf(false) }
    fun open(intent: Intent) {
        try { context.startActivity(intent); settingsFailed = false }
        catch (_: RuntimeException) { settingsFailed = true }
    }
    AlarmRuntimeControlsContent(enabled, state, busy, failure || settingsFailed,
        onToggle = { model.toggle(enabled == true, it) },
        onAlarmSettings = { open(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
            Uri.parse("package:${context.packageName}"))) },
        onNotificationSettings = { open(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)) }, onRetry = model::retry)
}

@Composable
internal fun AlarmRuntimeControlsContent(enabled: Boolean?, state: GlucoseAlarmOwnerState, busy: Boolean,
    failure: Boolean, onToggle: (Boolean) -> Unit, onAlarmSettings: () -> Unit,
    onNotificationSettings: () -> Unit, onRetry: () -> Unit) {
    val title = stringResource(R.string.alarm_runtime_title)
    Column(Modifier.fillMaxWidth().padding(12.dp).testTag("alarmRuntimeControls")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
            Switch(checked = enabled == true, enabled = enabled != null && !busy,
                onCheckedChange = onToggle, modifier = Modifier.testTag("alarmRuntimeSwitch").semantics { contentDescription = title })
        }
        Text(stringResource(if (failure) R.string.alarm_runtime_settings_failed else
            if (enabled == true && state.phase == GlucoseAlarmOwnerPhase.OFF) R.string.alarm_runtime_unavailable
            else alarmRuntimeStatusText(state.phase)), style = MaterialTheme.typography.bodySmall)
        if (enabled == true) {
            Text(stringResource(R.string.alarm_runtime_system_clock), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AlarmSettingsAction(Icons.Default.Alarm, stringResource(R.string.alarm_runtime_alarm_permission), onAlarmSettings)
                AlarmSettingsAction(Icons.Default.Notifications, stringResource(R.string.alarm_runtime_notification_permission), onNotificationSettings)
                if (state.phase in setOf(GlucoseAlarmOwnerPhase.UNAVAILABLE, GlucoseAlarmOwnerPhase.OFF)) {
                    AlarmSettingsAction(Icons.Default.Refresh, stringResource(R.string.alarm_runtime_retry), onRetry)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AlarmSettingsAction(icon: ImageVector, label: String, action: () -> Unit) {
    TooltipBox(positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = { PlainTooltip { Text(label) } }, state = rememberTooltipState()) {
        IconButton(onClick = action) { Icon(icon, label) }
    }
}
