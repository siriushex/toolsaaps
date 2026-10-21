package io.aaps.copilot.ui.foundation.screens

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import io.aaps.copilot.BuildConfig
import io.aaps.copilot.R
import io.aaps.copilot.update.*
import kotlinx.coroutines.launch
import java.io.File

class AppUpdateViewModel(app: Application) : AndroidViewModel(app) {
    val controller = AppUpdateController(AppUpdateRepository(app), BuildConfig.VERSION_CODE.toLong(),
        Build.VERSION.SDK_INT, viewModelScope)
}

internal fun updateInstallerIntent(context: Context, file: File): Intent = Intent(Intent.ACTION_VIEW)
    .setDataAndType(FileProvider.getUriForFile(context, "${context.packageName}.app-update", file),
        "application/vnd.android.package-archive")
    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

@Composable
fun AboutScreen(model: AppUpdateViewModel = viewModel()) {
    val state by model.controller.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    AboutContent(state, onCheck = model.controller::check, onDownload = model.controller::download,
        onCancel = model.controller::cancel, onInstall = {
            scope.launch {
                try {
                    if (!context.packageManager.canRequestPackageInstalls()) {
                        context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                            Uri.parse("package:${context.packageName}")))
                    } else {
                        model.controller.verifiedFile()?.let { context.startActivity(updateInstallerIntent(context, it)) }
                    }
                } catch (_: android.content.ActivityNotFoundException) { model.controller.installerFailed() }
                catch (_: SecurityException) { model.controller.installerFailed() }
                catch (_: IllegalArgumentException) { model.controller.installerFailed() }
            }
        })
}

@Composable
internal fun AboutContent(state: AppUpdateState, onCheck: () -> Unit, onDownload: () -> Unit,
    onCancel: () -> Unit, onInstall: () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Image(painterResource(R.drawable.owl_status_brand), null, Modifier.size(56.dp))
            Column {
                Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleLarge)
                Text(stringResource(R.string.app_update_version, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE),
                    style = MaterialTheme.typography.bodyMedium)
            }
        }
        HorizontalDivider()
        Text(stringResource(R.string.app_update_heading), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(when (state.phase) {
            UpdatePhase.IDLE -> R.string.app_update_idle
            UpdatePhase.CHECKING -> R.string.app_update_checking
            UpdatePhase.CURRENT -> R.string.app_update_current
            UpdatePhase.AVAILABLE -> R.string.app_update_available
            UpdatePhase.DOWNLOADING -> R.string.app_update_downloading
            UpdatePhase.READY -> R.string.app_update_ready
            UpdatePhase.VERIFYING -> R.string.app_update_verifying
            UpdatePhase.ERROR -> R.string.app_update_failed
        }), Modifier.testTag("updateStatus"))
        if (state.release != null && state.phase !in setOf(UpdatePhase.CURRENT, UpdatePhase.ERROR)) {
            Text(stringResource(R.string.app_update_release, state.release.versionName,
                state.release.sizeBytes / (1024.0 * 1024)), style = MaterialTheme.typography.bodyMedium)
            if (state.release.notes.isNotBlank()) Text(state.release.notes, style = MaterialTheme.typography.bodyMedium)
        }
        if (state.phase == UpdatePhase.DOWNLOADING || state.canResume) {
            LinearProgressIndicator(progress = { state.percent / 100f }, modifier = Modifier.fillMaxWidth())
            Text("${state.percent}%", Modifier.testTag("updateProgress"))
        } else if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        state.failure?.let { failure ->
            Text(stringResource(when (failure) {
                UpdateFailure.NETWORK -> R.string.app_update_error_network
                UpdateFailure.INVALID_RELEASE -> R.string.app_update_error_release
                UpdateFailure.INCOMPATIBLE -> R.string.app_update_error_incompatible
                UpdateFailure.STORAGE -> R.string.app_update_error_storage
                UpdateFailure.INVALID_APK -> R.string.app_update_error_apk
                UpdateFailure.INSTALLER -> R.string.app_update_error_installer
            }), color = MaterialTheme.colorScheme.error)
        }
        if (state.phase == UpdatePhase.READY) Text(stringResource(R.string.app_update_install_notice),
            style = MaterialTheme.typography.bodySmall)
        when (state.phase) {
            UpdatePhase.CHECKING, UpdatePhase.DOWNLOADING -> TextButton(onCancel,
                modifier = Modifier.testTag("updateCancel")) { Text(stringResource(R.string.app_update_cancel)) }
            UpdatePhase.VERIFYING -> Unit
            else -> Button(onClick = when (state.phase) {
                UpdatePhase.AVAILABLE -> onDownload
                UpdatePhase.READY -> onInstall
                else -> if (state.canResume) onDownload else onCheck
            }, modifier = Modifier.fillMaxWidth().testTag("updateAction")) {
                Icon(when (state.phase) {
                    UpdatePhase.AVAILABLE -> Icons.Default.Download
                    UpdatePhase.READY -> Icons.Default.SystemUpdate
                    else -> if (state.canResume) Icons.Default.Download else Icons.Default.Refresh
                }, null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(when (state.phase) {
                    UpdatePhase.AVAILABLE -> R.string.app_update_download
                    UpdatePhase.READY -> R.string.app_update_install
                    else -> if (state.canResume) R.string.app_update_resume else R.string.app_update_check
                }))
            }
        }
    }
}
