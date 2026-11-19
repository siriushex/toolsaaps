package io.aaps.copilot.ui.foundation.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
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
import io.aaps.copilot.service.AapsV3PairingIssue
import io.aaps.copilot.service.AapsV3PairingRoute
import io.aaps.copilot.service.AapsFinalClientRole
import io.aaps.copilot.service.AapsV3InboundGlucose
import io.aaps.copilot.service.AapsV3InboundSummary
import io.aaps.copilot.service.AapsV3ComboHistoryStatus
import io.aaps.copilot.service.AapsV3ObservedInsulinStatus
import io.aaps.copilot.service.AapsV3ObservedProfileStatus
import java.text.DateFormat
import java.util.Date

@Composable
internal fun NativeAapsConnectionSection(
    state: AapsV3ConnectionState,
    onPair: (CharArray, AapsV3PairingRoute) -> Unit,
    onCheck: () -> Unit,
    onRevoke: () -> Unit,
    inbound: AapsV3InboundSummary? = null,
    onInboundCheck: () -> Unit = {},
    localUpload: Boolean = false,
    onLocalUpload: (Boolean) -> Unit = {},
    commandsArmed: Boolean? = null,
    onReviewCommandPermission: (() -> Unit)? = null
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
            AapsV3ConnectionStatus.LOCAL_PEER_VERIFIED -> R.string.native_aaps_local_peer_verified
            AapsV3ConnectionStatus.CONNECTED -> R.string.native_aaps_connected
            AapsV3ConnectionStatus.CONTROL_DISABLED -> R.string.native_aaps_disabled
            AapsV3ConnectionStatus.REVOKED -> R.string.native_aaps_revoked
            AapsV3ConnectionStatus.OFFLINE -> R.string.native_aaps_offline
            AapsV3ConnectionStatus.ERROR -> R.string.native_aaps_error
        }), Modifier.testTag("native_aaps_status"), style = MaterialTheme.typography.bodyMedium)
        state.localLinkCheckedAtMs?.let {
            Text(stringResource(R.string.native_aaps_local_checked_before, DateFormat.getDateTimeInstance(
                DateFormat.SHORT, DateFormat.SHORT).format(Date(it))), Modifier.testTag("native_aaps_local_checked_before"),
                style = MaterialTheme.typography.bodySmall)
        }
        (state.localMembershipRole ?: state.localEnrollmentRole)?.let { role ->
            val label = when (role) {
                AapsFinalClientRole.READ_ONLY -> R.string.native_role_read
                AapsFinalClientRole.MEAL_ASSISTANT -> R.string.native_role_meal
                AapsFinalClientRole.CAREGIVER -> R.string.native_role_caregiver
                AapsFinalClientRole.ADMIN -> R.string.native_role_admin
            }
            val verifiedAt = state.localMembershipVerifiedAtMs
            val roleText = if (verifiedAt != null) stringResource(R.string.native_local_membership_verified, stringResource(label),
                DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(verifiedAt)))
                else stringResource(R.string.native_local_role_receipt, stringResource(label))
            Text(roleText,
                Modifier.testTag("native_local_role_receipt"), style = MaterialTheme.typography.bodySmall)
        }
        state.pairingIssue?.let {
            Text(stringResource(when (it) {
                AapsV3PairingIssue.LOCAL_INVITATION_MISSING -> R.string.native_aaps_local_offer_missing
                AapsV3PairingIssue.LOCAL_NOT_READY -> R.string.native_aaps_local_offer_not_ready
            }), Modifier.testTag("native_aaps_pair_issue"), style = MaterialTheme.typography.bodySmall)
        }
        state.localSettingsProbeVerified?.let { verified ->
            Text(stringResource(if (verified) R.string.native_settings_probe_verified else R.string.native_settings_probe_failed),
                Modifier.testTag("native_settings_probe"), style = MaterialTheme.typography.bodySmall)
        }
        if (state.status == AapsV3ConnectionStatus.UNPAIRED) state.settingsSubscribedAtCheck?.let { subscribed ->
            Text(stringResource(if (subscribed) R.string.native_storage_seen else R.string.native_storage_not_seen),
                Modifier.testTag("native_storage_status"), style = MaterialTheme.typography.bodySmall)
        }
        if (state.observedProfile != AapsV3ObservedProfileStatus.UNCHECKED) {
            Text(stringResource(observedProfileLabel(state.observedProfile)),
                Modifier.testTag("native_aaps_profile_status"), style = MaterialTheme.typography.bodySmall)
        }
        Text(stringResource(R.string.native_aaps_control_unavailable), style = MaterialTheme.typography.bodySmall)
        if (commandsArmed != null) {
            Text(stringResource(if (commandsArmed) R.string.native_aaps_permission_allowed
                else R.string.native_aaps_permission_blocked), Modifier.testTag("native_aaps_permission"))
            if (!commandsArmed && onReviewCommandPermission != null) {
                OutlinedButton(onClick = onReviewCommandPermission, enabled = !state.busy,
                    modifier = Modifier.testTag("native_aaps_review_permission")) {
                    Text(stringResource(R.string.setup_review_therapy))
                }
            }
        }
        if (state.status in setOf(AapsV3ConnectionStatus.CONNECTED, AapsV3ConnectionStatus.VERIFYING,
                AapsV3ConnectionStatus.CONTROL_DISABLED) || state.comboTransfer.lastAudit != null) {
            NativeComboHistorySection(state)
        }
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
            var route by remember { mutableStateOf(if (localUpload) AapsV3PairingRoute.SAME_PHONE else AapsV3PairingRoute.STORAGE) }
            DisposableEffect(Unit) { onDispose { pin = "" } }
            Column(Modifier.selectableGroup()) {
                for (option in AapsV3PairingRoute.entries) {
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(selected = route == option,
                        role = Role.RadioButton, onClick = {
                            if (route != option) { pin = ""; consent = false; route = option }
                        }).testTag("native_aaps_route_${option.name}"),
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        RadioButton(selected = route == option, onClick = null)
                        Text(stringResource(if (option == AapsV3PairingRoute.SAME_PHONE) R.string.native_aaps_route_phone
                            else R.string.native_aaps_route_storage), modifier = Modifier.padding(start = 8.dp))
                    }
                }
            }
            Text(stringResource(if (route == AapsV3PairingRoute.SAME_PHONE) R.string.native_aaps_local_pin_help else R.string.native_aaps_pin_help))
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
                    focus.clearFocus(force = true); onPair(ownedPin, route)
                }, enabled = pin.length == 8 && consent && !state.busy, modifier = Modifier.testTag("native_aaps_confirm")) {
                    Text(stringResource(R.string.native_aaps_pair))
                }
            }
        }
        if (!pairing) {
            Row(
                Modifier.fillMaxWidth().toggleable(value = localUpload, role = Role.Switch, onValueChange = onLocalUpload)
                    .testTag("native_aaps_local_upload"),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.native_aaps_local_upload), style = MaterialTheme.typography.bodyMedium)
                    Text(stringResource(R.string.native_aaps_local_upload_detail), style = MaterialTheme.typography.bodySmall)
                }
                androidx.compose.material3.Switch(checked = localUpload, onCheckedChange = null)
            }
            OutlinedButton(onClick = onInboundCheck, modifier = Modifier.testTag("native_aaps_inbound_check")) {
                Icon(Icons.Default.Refresh, contentDescription = null)
                Text(stringResource(R.string.native_aaps_inbound_check), Modifier.padding(start = 4.dp))
            }
            if (inbound != null) NativeAapsInboundSection(inbound)
        }
    }
    if (revoking) AlertDialog(onDismissRequest = { revoking = false },
        title = { Text(stringResource(R.string.native_aaps_revoke)) },
        text = { Text(stringResource(R.string.native_aaps_revoke_help)) },
        confirmButton = { TextButton(onClick = { revoking = false; onRevoke() }) { Text(stringResource(R.string.native_aaps_revoke)) } },
        dismissButton = { TextButton(onClick = { revoking = false }) { Text(stringResource(android.R.string.cancel)) } })
}

@Composable
private fun NativeAapsInboundSection(inbound: AapsV3InboundSummary) {
    val style = MaterialTheme.typography.bodySmall
    Text(stringResource(if (inbound.samePhone) R.string.native_aaps_local_checked else R.string.native_aaps_inbound_checked,
        DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(inbound.checkedAtMs))),
        style = style)
    Text(stringResource(when (inbound.glucose) {
        AapsV3InboundGlucose.FRESH -> R.string.native_aaps_inbound_cgm_fresh
        AapsV3InboundGlucose.STALE -> R.string.native_aaps_inbound_cgm_stale
        AapsV3InboundGlucose.NOT_OBSERVED ->
            if (inbound.samePhone) R.string.native_aaps_local_cgm_missing else R.string.native_aaps_inbound_cgm_missing
        AapsV3InboundGlucose.CONFLICTING -> R.string.native_aaps_inbound_cgm_conflict
        AapsV3InboundGlucose.CLOCK -> R.string.native_aaps_inbound_cgm_clock
        else -> R.string.native_aaps_inbound_cgm_unavailable
    }), Modifier.testTag("native_aaps_inbound_cgm"), style = style)
    Text(stringResource(observedProfileLabel(inbound.profile)), Modifier.testTag("native_aaps_inbound_profile"), style = style)
    inbound.targetRow?.let { receipt ->
        val text = receipt.recordedAt?.let { at -> stringResource(R.string.native_target_recorded,
            DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(at))) } ?: stringResource(R.string.native_target_record_unverified)
        Text(text, Modifier.testTag("native_aaps_target_record"), style = style)
    }
    val insulin = when (inbound.insulin) {
        AapsV3ObservedInsulinStatus.OBSERVED -> stringResource(R.string.native_aaps_inbound_insulin, inbound.insulinRecordCount)
        AapsV3ObservedInsulinStatus.PARTIAL_CONTEXT -> stringResource(R.string.native_aaps_inbound_insulin_partial, inbound.insulinRecordCount)
        AapsV3ObservedInsulinStatus.NOT_OBSERVED -> stringResource(R.string.native_aaps_inbound_insulin_missing)
        else -> stringResource(R.string.native_aaps_inbound_insulin_unavailable)
    }
    Text(insulin, Modifier.testTag("native_aaps_inbound_insulin"), style = style)
    val combo = when (inbound.comboHistory) {
        AapsV3ComboHistoryStatus.UNCHECKED -> null
        AapsV3ComboHistoryStatus.NOT_OBSERVED -> R.string.native_aaps_inbound_combo_missing
        AapsV3ComboHistoryStatus.RETAINED_UNVERIFIED -> R.string.native_aaps_inbound_combo_retained
        AapsV3ComboHistoryStatus.ADVERSE -> R.string.native_aaps_inbound_combo_adverse
        else -> R.string.native_aaps_inbound_combo_unavailable
    }
    combo?.let {
        Text(stringResource(it), Modifier.testTag("native_aaps_inbound_combo"), style = style)
    }
}

private fun observedProfileLabel(status: AapsV3ObservedProfileStatus) = when (status) {
    AapsV3ObservedProfileStatus.OBSERVED -> R.string.native_aaps_profile_observed
    AapsV3ObservedProfileStatus.NOT_OBSERVED -> R.string.native_aaps_profile_missing
    AapsV3ObservedProfileStatus.INVALIDATED -> R.string.native_aaps_profile_invalidated
    AapsV3ObservedProfileStatus.CONFLICTING -> R.string.native_aaps_profile_conflicting
    AapsV3ObservedProfileStatus.LIMIT_REACHED -> R.string.native_aaps_profile_limit
    else -> R.string.native_aaps_profile_unavailable
}
