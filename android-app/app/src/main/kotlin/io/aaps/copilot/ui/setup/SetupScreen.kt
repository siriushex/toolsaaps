package io.aaps.copilot.ui.setup

import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.aaps.copilot.R
import io.aaps.copilot.config.*
import io.aaps.copilot.ui.foundation.screens.IssueDestination

@Composable
internal fun SetupScreen(
    state: SetupUiState,
    onSave: (SetupProgress, () -> Unit) -> Unit,
    onClose: () -> Unit,
    onSettings: (IssueDestination) -> Unit,
    onBloodCheck: () -> Unit,
    onReviewTherapy: () -> Unit,
    onNotifications: () -> Unit,
    onRetry: () -> Unit
) {
    val progress = state.progress
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var notificationsAllowed by remember { mutableStateOf(false) }
    DisposableEffect(lifecycle, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                notificationsAllowed = NotificationManagerCompat.from(context).areNotificationsEnabled()
            }
        }
        notificationsAllowed = NotificationManagerCompat.from(context).areNotificationsEnabled()
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    val pause = {
        if (progress != null) onSave(progress.copy(status = SetupStatus.DEFERRED), onClose)
        else onClose()
    }
    BackHandler {
        if (!state.saving) {
            if (progress != null && progress.step.ordinal > 0) {
                onSave(progress.moveTo(SetupStep.entries[progress.step.ordinal - 1])) {}
            } else pause()
        }
    }
    Column(Modifier.fillMaxSize().testTag("setup_screen")) {
        if (progress == null) {
            if (!state.failed) Text(stringResource(R.string.setup_loading))
            Spacer(Modifier.weight(1f))
        } else {
            Text(stringResource(R.string.setup_step, progress.step.ordinal + 1, SetupStep.entries.size),
                style = MaterialTheme.typography.labelLarge)
            LinearProgressIndicator(progress = { (progress.step.ordinal + 1f) / SetupStep.entries.size },
                modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp))
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(stepTitle(progress.step)), style = MaterialTheme.typography.headlineSmall)
                when (progress.step) {
                    SetupStep.EQUIPMENT -> {
                        Text(stringResource(R.string.setup_glucose), style = MaterialTheme.typography.titleMedium)
                        SetupGlucoseSource.entries.forEach { value ->
                            SetupChoice(stringResource(if (value == SetupGlucoseSource.MANUAL) R.string.setup_manual else R.string.setup_sensor),
                                value == progress.glucoseSource, !state.saving, "setup_glucose_${value.name}") {
                                onSave(progress.copy(status = SetupStatus.IN_PROGRESS, glucoseSource = value)) {}
                            }
                        }
                        Text(stringResource(R.string.setup_insulin), style = MaterialTheme.typography.titleMedium)
                        SetupInsulinSource.entries.forEach { value ->
                            SetupChoice(stringResource(insulinTitle(value)), value == progress.insulinSource,
                                !state.saving, "setup_insulin_${value.name}") {
                                onSave(progress.copy(status = SetupStatus.IN_PROGRESS, insulinSource = value)) {}
                            }
                        }
                        Text(stringResource(R.string.setup_equipment_note), style = MaterialTheme.typography.bodySmall)
                    }
                    SetupStep.DATA -> {
                        if (progress.glucoseSource == SetupGlucoseSource.MANUAL) {
                            Text(stringResource(R.string.setup_manual_detail))
                            SetupAction(R.string.setup_blood_check, "setup_blood_check", !state.saving, onBloodCheck)
                        } else {
                            Text(stringResource(R.string.setup_sensor_detail))
                            SetupAction(R.string.setup_sources, "setup_sources", !state.saving) {
                                onSettings(IssueDestination.DATA_SOURCES)
                            }
                        }
                        Text(stringResource(if (progress.insulinSource == SetupInsulinSource.AAPS)
                            R.string.setup_aaps_detail else R.string.setup_without_aaps_detail))
                        if (progress.insulinSource == SetupInsulinSource.AAPS) {
                            SetupAction(R.string.setup_sources, "setup_aaps_sources", !state.saving) {
                                onSettings(IssueDestination.DATA_SOURCES)
                            }
                        }
                    }
                    SetupStep.PROFILE -> {
                        Text(stringResource(R.string.setup_profile_detail))
                        SetupAction(R.string.setup_profile_open, "setup_profile", !state.saving) {
                            onSettings(IssueDestination.USER_PROFILE)
                        }
                        SetupAction(R.string.setup_energy_open, "setup_energy", !state.saving) {
                            onSettings(IssueDestination.ENERGY_GOAL)
                        }
                    }
                    SetupStep.ALERTS -> {
                        Text(stringResource(if (notificationsAllowed) R.string.setup_notifications_allowed else R.string.setup_notifications_blocked))
                        Text(stringResource(R.string.setup_alerts_detail))
                        SetupAction(R.string.setup_notifications, "setup_notifications", !state.saving) {
                            if (notificationsAllowed) {
                                context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                    .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
                            } else onNotifications()
                        }
                        SetupAction(R.string.setup_alert_options, "setup_alert_options", !state.saving) {
                            onSettings(IssueDestination.ALERT_SETTINGS)
                        }
                    }
                    SetupStep.REVIEW -> {
                        Text(stringResource(if (progress.glucoseSource == SetupGlucoseSource.MANUAL)
                            R.string.setup_manual else R.string.setup_sensor), style = MaterialTheme.typography.titleMedium)
                        Text(stringResource(insulinTitle(progress.insulinSource)))
                        Text(stringResource(R.string.setup_review_detail))
                        Text(stringResource(if (state.therapyArmed) R.string.setup_therapy_enabled else R.string.setup_therapy_blocked))
                        if (progress.insulinSource == SetupInsulinSource.AAPS && !state.therapyArmed) {
                            SetupAction(R.string.setup_review_therapy, "setup_review_therapy", !state.saving, onReviewTherapy)
                        }
                        if (state.therapyArmed) {
                            SetupAction(R.string.nav_safety, "setup_safety", !state.saving) { onSettings(IssueDestination.SAFETY) }
                        }
                    }
                }
            }
        }
        if (state.failed) {
            Text(stringResource(R.string.setup_save_failed), color = MaterialTheme.colorScheme.error)
            TextButton(onClick = onRetry, enabled = !state.saving) { Text(stringResource(R.string.setup_retry)) }
        }
        if (progress != null) {
            Button(
                onClick = {
                    if (progress.step == SetupStep.REVIEW) onSave(progress.copy(status = SetupStatus.COMPLETE), onClose)
                    else onSave(progress.moveTo(SetupStep.entries[progress.step.ordinal + 1])) {}
                },
                enabled = !state.saving && !state.failed,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp).testTag("setup_next")
            ) {
                Text(stringResource(if (progress.step == SetupStep.REVIEW) R.string.setup_finish else R.string.setup_next))
                Spacer(Modifier.width(8.dp))
                Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null)
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            if (progress != null && progress.step.ordinal > 0) TextButton(
                onClick = { onSave(progress.moveTo(SetupStep.entries[progress.step.ordinal - 1])) {} },
                enabled = !state.saving, modifier = Modifier.testTag("setup_back")
            ) { Text(stringResource(R.string.setup_back)) }
            TextButton(onClick = pause, enabled = !state.saving, modifier = Modifier.testTag("setup_later")) {
                Text(stringResource(R.string.setup_later))
            }
        }
    }
}

@Composable
private fun SetupChoice(text: String, selected: Boolean, enabled: Boolean, tag: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag(tag)
        .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected, onClick = null, enabled = enabled)
        Text(text, Modifier.weight(1f).padding(start = 8.dp))
    }
}

@Composable
private fun SetupAction(label: Int, tag: String, enabled: Boolean, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth().testTag(tag)) {
        Icon(Icons.Default.Tune, contentDescription = null)
        Spacer(Modifier.width(8.dp))
        Text(stringResource(label))
    }
}

private fun stepTitle(step: SetupStep): Int = when (step) {
    SetupStep.EQUIPMENT -> R.string.setup_equipment
    SetupStep.DATA -> R.string.setup_data
    SetupStep.PROFILE -> R.string.setup_profile_title
    SetupStep.ALERTS -> R.string.setup_alerts
    SetupStep.REVIEW -> R.string.setup_review
}

private fun insulinTitle(source: SetupInsulinSource): Int = when (source) {
    SetupInsulinSource.NONE -> R.string.setup_no_insulin
    SetupInsulinSource.INJECTIONS -> R.string.setup_injections
    SetupInsulinSource.PUMP -> R.string.setup_pump
    SetupInsulinSource.AAPS -> R.string.setup_aaps
}
