package io.aaps.copilot.ui.foundation.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.aaps.copilot.R
import io.aaps.copilot.service.AapsV3ConnectionState
import io.aaps.copilot.service.AapsV3ConnectionStatus
import io.aaps.copilot.service.AapsV3ObservedProfileStatus

@Composable
internal fun NativeAapsConnectionSection(
    state: AapsV3ConnectionState,
    onPair: (CharArray) -> Unit,
    onCheck: () -> Unit,
    onRevoke: () -> Unit
) {
    var pairing by remember { mutableStateOf(false) }
    var revoking by remember { mutableStateOf(false) }
    val lifecycleOwner = LocalLifecycleOwner.current
    val focus = LocalFocusManager.current
    ProtectCredentialDialogTaskPreview(active = pairing)
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP) pairing = false
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.native_aaps_title), style = MaterialTheme.typography.titleSmall)
        Text(stringResource(when (state.status) {
            AapsV3ConnectionStatus.UNPAIRED -> R.string.native_aaps_unpaired
            AapsV3ConnectionStatus.WAITING -> R.string.native_aaps_waiting
            AapsV3ConnectionStatus.VERIFYING -> R.string.native_aaps_verifying
            AapsV3ConnectionStatus.CONNECTED -> R.string.native_aaps_connected
            AapsV3ConnectionStatus.CONTROL_DISABLED -> R.string.native_aaps_disabled
            AapsV3ConnectionStatus.REVOKED -> R.string.native_aaps_revoked
            AapsV3ConnectionStatus.OFFLINE -> R.string.native_aaps_offline
            AapsV3ConnectionStatus.ERROR -> R.string.native_aaps_error
        }), Modifier.testTag("native_aaps_status"), style = MaterialTheme.typography.bodyMedium)
        if (state.observedProfile != AapsV3ObservedProfileStatus.UNCHECKED) {
            Text(stringResource(when (state.observedProfile) {
                AapsV3ObservedProfileStatus.OBSERVED -> R.string.native_aaps_profile_observed
                AapsV3ObservedProfileStatus.NOT_OBSERVED -> R.string.native_aaps_profile_missing
                AapsV3ObservedProfileStatus.INVALIDATED -> R.string.native_aaps_profile_invalidated
                AapsV3ObservedProfileStatus.CONFLICTING -> R.string.native_aaps_profile_conflicting
                AapsV3ObservedProfileStatus.LIMIT_REACHED -> R.string.native_aaps_profile_limit
                else -> R.string.native_aaps_profile_unavailable
            }), Modifier.testTag("native_aaps_profile_status"), style = MaterialTheme.typography.bodySmall)
        }
        Text(stringResource(R.string.native_aaps_control_unavailable), style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { pairing = true }, enabled = !state.busy,
                modifier = Modifier.testTag("native_aaps_pair")) {
                Icon(Icons.Default.Link, contentDescription = null)
                Text(stringResource(R.string.native_aaps_pair), Modifier.padding(start = 4.dp))
            }
            IconButton(onClick = onCheck, enabled = !state.busy) {
                Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.native_aaps_check))
            }
            if (state.status !in setOf(AapsV3ConnectionStatus.UNPAIRED, AapsV3ConnectionStatus.REVOKED)) {
                IconButton(onClick = { revoking = true }, enabled = !state.busy) {
                    Icon(Icons.Default.LinkOff, contentDescription = stringResource(R.string.native_aaps_revoke))
                }
            }
        }
        if (pairing) {
            var pin by remember { mutableStateOf("") }
            var consent by remember { mutableStateOf(false) }
            DisposableEffect(Unit) { onDispose { pin = "" } }
            Text(stringResource(R.string.native_aaps_pin_help))
            OutlinedTextField(value = pin, onValueChange = { raw ->
                pin = raw.filter { it in '0'..'9' }.take(8)
            }, label = { Text(stringResource(R.string.native_aaps_pin)) }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                modifier = Modifier.fillMaxWidth().testTag("native_aaps_pin"))
            Row(Modifier.toggleable(value = consent, role = Role.Checkbox, onValueChange = { consent = it })
                .testTag("native_aaps_consent")) {
                Checkbox(checked = consent, onCheckedChange = null)
                Text(stringResource(R.string.native_aaps_permission_consent), Modifier.weight(1f))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = { pin = ""; pairing = false; focus.clearFocus(force = true) }) {
                    Text(stringResource(android.R.string.cancel))
                }
                Button(onClick = {
                    val ownedPin = pin.toCharArray(); pin = ""; pairing = false
                    focus.clearFocus(force = true); onPair(ownedPin)
                }, enabled = pin.length == 8 && consent && !state.busy, modifier = Modifier.testTag("native_aaps_confirm")) {
                    Text(stringResource(R.string.native_aaps_pair))
                }
            }
        }
    }
    if (revoking) AlertDialog(onDismissRequest = { revoking = false },
        title = { Text(stringResource(R.string.native_aaps_revoke)) },
        text = { Text(stringResource(R.string.native_aaps_revoke_help)) },
        confirmButton = { TextButton(onClick = { revoking = false; onRevoke() }) { Text(stringResource(R.string.native_aaps_revoke)) } },
        dismissButton = { TextButton(onClick = { revoking = false }) { Text(stringResource(android.R.string.cancel)) } })
}
