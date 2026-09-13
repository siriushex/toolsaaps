package io.aaps.copilot.ui.foundation.screens

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.aaps.copilot.R
import io.aaps.copilot.security.ServerAiConnectionError
import io.aaps.copilot.security.ServerAiConnectionPhase
import io.aaps.copilot.security.ServerAiConnectionState
import io.aaps.copilot.ui.foundation.design.Spacing
import java.text.DateFormat
import java.util.Date

@Composable
internal fun ServerAiConnectionSection(
    state: ServerAiConnectionState,
    onActivate: (String) -> Unit,
    onResume: () -> Unit,
    onCheck: () -> Unit,
    modifier: Modifier = Modifier
) {
    var accessCode by remember { mutableStateOf("") }
    var codeFieldFocused by remember { mutableStateOf(false) }
    var submissionPending by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    val connecting = state.phase == ServerAiConnectionPhase.CONNECTING
    val busy = connecting || submissionPending

    ProtectCredentialDialogTaskPreview(
        active = codeFieldFocused || accessCode.isNotEmpty()
    )
    LaunchedEffect(state.revision, state.phase) {
        submissionPending = false
    }
    LaunchedEffect(state.phase, state.hasStoredSession, state.canResume) {
        if (
            state.phase == ServerAiConnectionPhase.ACTIVE ||
            state.hasStoredSession ||
            state.canResume
        ) {
            accessCode = ""
            codeFieldFocused = false
            focusManager.clearFocus(force = true)
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            accessCode = ""
            codeFieldFocused = false
            submissionPending = false
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .testTag("server_ai_connection_section"),
        verticalArrangement = Arrangement.spacedBy(Spacing.xs)
    ) {
        Text(
            text = stringResource(id = R.string.settings_server_ai_section),
            style = MaterialTheme.typography.titleSmall
        )

        state.subscriptionExpiresMs?.let { expiresMs ->
            Text(
                text = stringResource(
                    id = R.string.settings_server_ai_access_until,
                    formatServerAiSubscriptionDate(expiresMs)
                ),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.testTag("server_ai_subscription_expiry")
            )
        }

        if (state.hasStoredSession) {
            Text(
                text = stringResource(
                    id = if (state.inferenceReady) {
                        R.string.settings_server_ai_analysis_connected
                    } else {
                        R.string.settings_server_ai_analysis_not_connected
                    }
                ),
                style = MaterialTheme.typography.bodySmall,
                color = if (state.inferenceReady) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.testTag("server_ai_inference_status")
            )
        }

        state.error?.let { error ->
            Text(
                text = stringResource(id = error.messageRes()),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.testTag("server_ai_error")
            )
        }

        when {
            state.canResume -> {
                Text(
                    text = stringResource(id = R.string.settings_server_ai_resume_pending),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Button(
                    onClick = {
                        submissionPending = true
                        onResume()
                    },
                    enabled = !busy,
                    modifier = Modifier.testTag("server_ai_resume")
                ) {
                    ServerAiActionContent(
                        busy = busy,
                        idleText = stringResource(id = R.string.settings_server_ai_resume),
                        busyText = stringResource(id = R.string.settings_server_ai_connecting)
                    )
                }
            }

            state.hasStoredSession -> {
                OutlinedButton(
                    onClick = {
                        submissionPending = true
                        onCheck()
                    },
                    enabled = !busy,
                    modifier = Modifier.testTag("server_ai_check")
                ) {
                    ServerAiActionContent(
                        busy = busy,
                        idleText = stringResource(id = R.string.settings_server_ai_check),
                        busyText = stringResource(id = R.string.settings_server_ai_connecting)
                    )
                }
            }

            else -> {
                OutlinedTextField(
                    value = accessCode,
                    onValueChange = { accessCode = it.take(MAX_ACCESS_CODE_INPUT_LENGTH) },
                    label = { Text(text = stringResource(id = R.string.settings_server_ai_access_code)) },
                    keyboardOptions = CredentialKeyboardOptions,
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    enabled = !busy,
                    modifier = Modifier
                        .fillMaxWidth()
                        .onFocusChanged { codeFieldFocused = it.isFocused }
                        .testTag("server_ai_access_code")
                )
                Button(
                    onClick = {
                        val submittedCode = accessCode
                        submissionPending = true
                        accessCode = ""
                        codeFieldFocused = false
                        focusManager.clearFocus(force = true)
                        onActivate(submittedCode)
                    },
                    enabled = accessCode.isNotBlank() && !busy,
                    modifier = Modifier.testTag("server_ai_activate")
                ) {
                    ServerAiActionContent(
                        busy = busy,
                        idleText = stringResource(id = R.string.settings_server_ai_activate),
                        busyText = stringResource(id = R.string.settings_server_ai_connecting)
                    )
                }
            }
        }
    }
}

@Composable
private fun ServerAiActionContent(
    busy: Boolean,
    idleText: String,
    busyText: String
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (busy) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                color = LocalContentColor.current,
                strokeWidth = 2.dp
            )
        }
        Text(text = if (busy) busyText else idleText)
    }
}

@StringRes
private fun ServerAiConnectionError.messageRes(): Int = when (this) {
    ServerAiConnectionError.INVALID_CODE -> R.string.settings_server_ai_error_invalid_code
    ServerAiConnectionError.NETWORK -> R.string.settings_server_ai_error_network
    ServerAiConnectionError.UNAUTHORIZED -> R.string.settings_server_ai_error_unauthorized
    ServerAiConnectionError.DEVICE_KEY -> R.string.settings_server_ai_error_device_key
    ServerAiConnectionError.STORAGE -> R.string.settings_server_ai_error_storage
    ServerAiConnectionError.INVALID_RESPONSE -> R.string.settings_server_ai_error_invalid_response
}

private fun formatServerAiSubscriptionDate(expiresMs: Long): String =
    DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(expiresMs))

private const val MAX_ACCESS_CODE_INPUT_LENGTH = 64
