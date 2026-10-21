package io.aaps.copilot.ui.foundation.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.aaps.copilot.R
import io.aaps.copilot.ui.motion.*

@Composable
internal fun AiHeaderNavigation(band: OwlColorBand, navigationKey: String, gesture: OwlGesture,
    drawerExpanded: Boolean, waiting: Boolean, diagnosticsVisible: Boolean,
    onDiagnostics: () -> Unit, onDismissDiagnostics: () -> Unit,
    onMenu: () -> Unit, onSettings: () -> Unit, onsetCompletionKey: String? = null) {
    var expanded by remember { mutableStateOf(false) }
    if (diagnosticsVisible) {
        NativeIconButton(onDismissDiagnostics, Modifier.size(48.dp).testTag("ai_header_back")) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.ai_focus_back))
        }
        return
    }
    Box {
        OwlMenuButton(band, navigationKey, gesture, expanded || drawerExpanded, waiting,
            { expanded = !expanded }, label = stringResource(R.string.ai_focus_state_menu),
            onsetCompletionKey = onsetCompletionKey)
        DropdownMenu(expanded, { expanded = false }) {
            DropdownMenuItem(text = { Text(stringResource(R.string.ai_focus_diagnostics)) },
                onClick = { expanded = false; onDiagnostics() }, modifier = Modifier.testTag("ai_header_diagnostics"))
            DropdownMenuItem(text = { Text(stringResource(R.string.ai_focus_main_menu)) },
                onClick = { expanded = false; onMenu() }, modifier = Modifier.testTag("ai_header_main_menu"))
            DropdownMenuItem(text = { Text(stringResource(R.string.ai_focus_settings)) },
                onClick = { expanded = false; onSettings() }, modifier = Modifier.testTag("ai_header_settings"))
        }
    }
}
