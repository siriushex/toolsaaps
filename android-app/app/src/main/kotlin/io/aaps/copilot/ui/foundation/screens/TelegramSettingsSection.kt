package io.aaps.copilot.ui.foundation.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.aaps.copilot.R
import io.aaps.copilot.telegram.TelegramDeliveryResult
import io.aaps.copilot.telegram.TelegramFailure
import io.aaps.copilot.telegram.TelegramIssue
import io.aaps.copilot.telegram.TelegramRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
fun TelegramSettingsSection(repository: TelegramRepository, onSendSummary: suspend () -> Unit) {
    val state by repository.state.collectAsState()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var token by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var showInfo by remember { mutableStateOf(false) }
    var confirmDisconnect by remember { mutableStateOf(false) }
    var feedback by remember { mutableStateOf<TelegramIssue?>(null) }
    val perform: (suspend () -> Unit) -> Unit = { operation ->
        if (!busy) scope.launch {
            busy = true
            feedback = null
            try { operation() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { feedback = (e as? TelegramFailure)?.issue ?: TelegramIssue.NETWORK }
            finally { busy = false }
        }
    }
    LaunchedEffect(repository) { repository.initialize() }
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp).testTag("telegram-settings"),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Telegram", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            IconButton(onClick = { showInfo = true }) {
                Icon(Icons.Default.Info, stringResource(R.string.telegram_info))
            }
        }
        Text(
            if (state.configured) stringResource(R.string.telegram_configured, state.botUsername)
            else stringResource(R.string.telegram_not_configured),
            style = MaterialTheme.typography.bodyMedium
        )
        if (!state.configured) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = token, onValueChange = { token = it.take(160) },
                    modifier = Modifier.weight(1f).testTag("telegram-token"),
                    label = { Text(stringResource(R.string.telegram_token)) },
                    singleLine = true, visualTransformation = PasswordVisualTransformation(),
                    enabled = !busy
                )
                IconButton(enabled = !busy && token.isNotBlank(), onClick = {
                    val entered = token
                    token = ""
                    perform { repository.replaceToken(entered) }
                }) { Icon(Icons.Default.Check, stringResource(R.string.telegram_verify_save)) }
            }
        } else {
            TelegramToggle(stringResource(R.string.telegram_enabled), state.enabled, !busy && state.recipients.isNotEmpty()) {
                perform { repository.setEnabled(it) }
            }
            TelegramToggle(stringResource(R.string.telegram_soft), state.includeSoftAlerts, !busy) {
                perform { repository.setOptions(it, state.automaticReports) }
            }
            TelegramToggle(stringResource(R.string.telegram_auto_reports), state.automaticReports, !busy) {
                perform { repository.setOptions(state.includeSoftAlerts, it) }
            }
            HorizontalDivider()
            Text(stringResource(R.string.telegram_recipients), style = MaterialTheme.typography.titleSmall)
            state.recipients.forEach { recipient ->
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("@${recipient.username}", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        IconButton(enabled = !busy, onClick = { perform { repository.removeRecipient(recipient.chatId) } }) {
                            Icon(Icons.Default.DeleteOutline, stringResource(R.string.telegram_remove))
                        }
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(recipient.alerts, enabled = !busy, onCheckedChange = {
                                perform { repository.updateRecipient(recipient.chatId, it, recipient.reports) }
                            })
                            Text(stringResource(R.string.telegram_alerts))
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(recipient.reports, enabled = !busy, onCheckedChange = {
                                perform { repository.updateRecipient(recipient.chatId, recipient.alerts, it) }
                            })
                            Text(stringResource(R.string.telegram_reports))
                        }
                    }
                    val result = when (recipient.lastResult) {
                        TelegramDeliveryResult.NONE -> R.string.telegram_no_delivery
                        TelegramDeliveryResult.CLAIMED -> R.string.telegram_delivery_unknown
                        TelegramDeliveryResult.UNKNOWN -> R.string.telegram_delivery_unknown
                        TelegramDeliveryResult.DELIVERED -> R.string.telegram_delivered
                    }
                    Text(stringResource(result), style = MaterialTheme.typography.bodySmall)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    username, { username = it.take(33) }, Modifier.weight(1f),
                    label = { Text(stringResource(R.string.telegram_username)) }, singleLine = true, enabled = !busy
                )
                IconButton(enabled = !busy && username.isNotBlank(), onClick = {
                    perform { repository.beginPairing(username) }
                }) { Icon(Icons.Default.Add, stringResource(R.string.telegram_add)) }
            }
            state.pairingLink?.let { link ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.telegram_pair_instruction), Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall)
                    IconButton(onClick = {
                        (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                            .setPrimaryClip(ClipData.newPlainText("Copilot Telegram", link))
                    }) { Icon(Icons.Default.ContentCopy, stringResource(R.string.telegram_copy_link)) }
                }
                OutlinedButton(enabled = !busy, onClick = { perform { repository.checkPairing() } }) {
                    Text(stringResource(R.string.telegram_check_pairing))
                }
            }
            state.candidates.forEach { candidate ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("@${candidate.username} · ${candidate.chatId}", Modifier.weight(1f))
                    TextButton(enabled = !busy, onClick = { perform { repository.confirmRecipient(candidate.chatId) } }) {
                        Text(stringResource(R.string.telegram_confirm))
                    }
                }
            }
            OutlinedButton(
                enabled = !busy && state.enabled && state.recipients.any { it.reports },
                onClick = { perform { onSendSummary() } }, modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.AutoMirrored.Filled.Send, null)
                Text(stringResource(R.string.telegram_send_summary), modifier = Modifier.padding(start = 8.dp))
            }
            TextButton(enabled = !busy, onClick = { confirmDisconnect = true }) {
                Text(stringResource(R.string.telegram_disconnect))
            }
        }
        if (busy) Text(stringResource(R.string.telegram_working), style = MaterialTheme.typography.bodySmall)
        (feedback ?: state.issue)?.let {
            Text(stringResource(when (it) {
                TelegramIssue.INVALID_TOKEN -> R.string.telegram_invalid_token
                TelegramIssue.NETWORK -> R.string.telegram_network_error
                TelegramIssue.REJECTED -> R.string.telegram_rejected
                TelegramIssue.RESPONSE -> R.string.telegram_response_error
                TelegramIssue.PAIRING_EXPIRED -> R.string.telegram_pair_expired
                TelegramIssue.NO_MATCH -> R.string.telegram_no_match
                TelegramIssue.STORAGE -> R.string.telegram_storage_error
                TelegramIssue.NOT_READY -> R.string.telegram_not_ready
            }), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
    }
    if (showInfo) AlertDialog(
        onDismissRequest = { showInfo = false },
        title = { Text(stringResource(R.string.telegram_info)) },
        text = { Text(stringResource(R.string.telegram_privacy)) },
        confirmButton = { TextButton(onClick = { showInfo = false }) { Text(stringResource(android.R.string.ok)) } }
    )
    if (confirmDisconnect) AlertDialog(
        onDismissRequest = { confirmDisconnect = false },
        text = { Text(stringResource(R.string.telegram_disconnect_confirm)) },
        confirmButton = { TextButton(onClick = {
            confirmDisconnect = false
            perform { repository.disconnect() }
        }) { Text(stringResource(R.string.telegram_disconnect)) } },
        dismissButton = { TextButton(onClick = { confirmDisconnect = false }) { Text(stringResource(android.R.string.cancel)) } }
    )
}

@Composable
private fun TelegramToggle(label: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(checked, onChange, enabled = enabled)
    }
}
