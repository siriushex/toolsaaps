package io.aaps.copilot

import android.Manifest
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.content.ContextCompat
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.lifecycleScope
import io.aaps.copilot.service.AppVisibilityTracker
import io.aaps.copilot.service.ForegroundStartupJob
import io.aaps.copilot.service.HealthConnectActivityCollector
import io.aaps.copilot.service.LocalNightscoutServiceController
import io.aaps.copilot.service.LocalNightscoutSecretClipboard
import io.aaps.copilot.service.TherapyActionRuntimeState
import io.aaps.copilot.ui.foundation.CopilotFoundationRoot
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val healthConnectEnabled = false
    private val foregroundStartup by lazy {
        ForegroundStartupJob(
            scope = lifecycleScope,
            isResumed = { lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED) },
            postAfterDraw = { action -> window.decorView.post { action() } }
        )
    }
    private var therapyBootstrapDialog: AlertDialog? = null

    private val healthConnectPermissionLauncher = registerForActivityResult(
        PermissionController.createRequestPermissionResultContract()
    ) { granted ->
        if (granted.containsAll(HealthConnectActivityCollector.REQUIRED_PERMISSIONS)) {
            (application as? CopilotApp)?.container?.startHealthConnectCollection()
        }
    }

    private val activityRecognitionPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            (application as? CopilotApp)?.container?.startLocalActivitySensors()
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        continuePermissionStartup(notificationPermissionRequestStarted = false)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            CopilotFoundationRoot()
        }
    }

    override fun onStart() {
        super.onStart()
        AppVisibilityTracker.markForeground(true)
        LocalNightscoutSecretClipboard.reconcile(this)
    }

    override fun onResume() {
        super.onResume()
        enqueuePostDrawStartup()
    }

    override fun onPause() {
        foregroundStartup.cancel()
        super.onPause()
    }

    override fun onStop() {
        AppVisibilityTracker.markForeground(false)
        if (!isChangingConfigurations) {
            val app = application as? CopilotApp
            app?.container?.clinicalReportRepository?.releasePreparedPayloadWhenIdleAsync()
        }
        super.onStop()
    }

    private fun enqueuePostDrawStartup() {
        foregroundStartup.enqueue {
            if (!lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) {
                return@enqueue
            }
            val app = application as? CopilotApp ?: return@enqueue
            app.container.migrateTherapyActionBootstrapForInPlaceUpdate()
            val settings = app.container.settingsStore.settings.first()
            currentCoroutineContext().ensureActive()
            if (!lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) return@enqueue
            if (!settings.therapyActionsArmed) {
                showTherapyActionBootstrapDialog(app)
                return@enqueue
            }
            continueStartupAfterTherapyBootstrap()
        }
    }

    private fun continueStartupAfterTherapyBootstrap() {
        if (!lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) return
        LocalNightscoutServiceController.start(this)
        foregroundStartup.runPermissionsOnce {
            continuePermissionStartup(
                notificationPermissionRequestStarted = ensurePostNotificationPermission()
            )
        }
    }

    private fun showTherapyActionBootstrapDialog(app: CopilotApp) {
        if (therapyBootstrapDialog?.isShowing == true || isFinishing || isDestroyed) return
        therapyBootstrapDialog = AlertDialog.Builder(this)
            .setTitle(R.string.therapy_action_bootstrap_title)
            .setMessage(R.string.therapy_action_bootstrap_message)
            .setNegativeButton(R.string.therapy_action_bootstrap_keep_blocked) { _, _ ->
                lifecycleScope.launch {
                    app.container.settingsStore.setTherapyActionsArmed(false)
                    TherapyActionRuntimeState.setArmed(false)
                    therapyBootstrapDialog = null
                }
            }
            .setPositiveButton(R.string.therapy_action_bootstrap_review_enable) { _, _ ->
                showTherapyActionBootstrapFinalConfirmation(app)
            }
            .setCancelable(false)
            .create()
            .also { dialog ->
                dialog.setOnDismissListener {
                    if (therapyBootstrapDialog === dialog) {
                        therapyBootstrapDialog = null
                    }
                }
                therapyBootstrapDialog = dialog
                dialog.show()
            }
    }

    private fun showTherapyActionBootstrapFinalConfirmation(app: CopilotApp) {
        if (isFinishing || isDestroyed) return
        therapyBootstrapDialog = AlertDialog.Builder(this)
            .setTitle(R.string.therapy_action_bootstrap_final_title)
            .setMessage(R.string.therapy_action_bootstrap_final_message)
            .setNegativeButton(R.string.therapy_action_bootstrap_keep_blocked) { _, _ ->
                lifecycleScope.launch {
                    app.container.settingsStore.setTherapyActionsArmed(false)
                    TherapyActionRuntimeState.setArmed(false)
                    therapyBootstrapDialog = null
                }
            }
            .setPositiveButton(R.string.therapy_action_bootstrap_enable) { _, _ ->
                lifecycleScope.launch {
                    val armed = app.container.settingsStore.setTherapyActionsArmed(true)
                    TherapyActionRuntimeState.setArmed(armed)
                    therapyBootstrapDialog = null
                    if (armed) continueStartupAfterTherapyBootstrap()
                }
            }
            .setCancelable(false)
            .create()
            .also { dialog ->
                dialog.setOnDismissListener {
                    if (therapyBootstrapDialog === dialog) {
                        therapyBootstrapDialog = null
                    }
                }
                therapyBootstrapDialog = dialog
                dialog.show()
            }
    }

    private fun continuePermissionStartup(notificationPermissionRequestStarted: Boolean) {
        if (notificationPermissionRequestStarted) return
        if (!lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) return

        val activityRecognitionGranted = hasActivityRecognitionPermission()
        if (activityRecognitionGranted) {
            (application as? CopilotApp)?.container?.startLocalActivitySensors()
        } else if (
            shouldRequestActivityRecognitionNow(
                notificationPermissionRequestStarted = false,
                activityRecognitionGranted = activityRecognitionGranted
            )
        ) {
            ensureActivityRecognitionPermission()
        }
        if (healthConnectEnabled) {
            ensureHealthConnectPermissions()
            (application as? CopilotApp)?.container?.startHealthConnectCollection()
        }
    }

    private fun hasActivityRecognitionPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return true
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACTIVITY_RECOGNITION
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun ensurePostNotificationPermission(): Boolean {
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
        if (shouldRequestPostNotifications(Build.VERSION.SDK_INT, granted)) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            return true
        }
        return false
    }

    private fun ensureActivityRecognitionPermission() {
        if (!hasActivityRecognitionPermission()) {
            activityRecognitionPermissionLauncher.launch(Manifest.permission.ACTIVITY_RECOGNITION)
        }
    }

    private fun ensureHealthConnectPermissions() {
        val sdkStatus = HealthConnectClient.getSdkStatus(
            this,
            "com.google.android.apps.healthdata"
        )
        if (sdkStatus != HealthConnectClient.SDK_AVAILABLE) return
        lifecycleScope.launch {
            val client = runCatching { HealthConnectClient.getOrCreate(this@MainActivity) }.getOrNull() ?: return@launch
            val granted = runCatching { client.permissionController.getGrantedPermissions() }.getOrDefault(emptySet())
            if (!granted.containsAll(HealthConnectActivityCollector.REQUIRED_PERMISSIONS)) {
                healthConnectPermissionLauncher.launch(HealthConnectActivityCollector.REQUIRED_PERMISSIONS)
            }
        }
    }

}

internal fun shouldRequestPostNotifications(sdkInt: Int, granted: Boolean): Boolean =
    sdkInt >= Build.VERSION_CODES.TIRAMISU && !granted

internal fun shouldRequestActivityRecognitionNow(
    notificationPermissionRequestStarted: Boolean,
    activityRecognitionGranted: Boolean
): Boolean = !notificationPermissionRequestStarted && !activityRecognitionGranted
