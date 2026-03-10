package io.aaps.copilot

import android.Manifest
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
import io.aaps.copilot.service.HealthConnectActivityCollector
import io.aaps.copilot.service.LocalNightscoutServiceController
import io.aaps.copilot.ui.foundation.CopilotFoundationRoot
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val healthConnectEnabled = false
    private var postDrawStartupQueued = false

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
        enqueuePostDrawStartup()
    }

    override fun onStop() {
        postDrawStartupQueued = false
        AppVisibilityTracker.markForeground(false)
        super.onStop()
    }

    private fun enqueuePostDrawStartup() {
        if (postDrawStartupQueued) return
        postDrawStartupQueued = true
        window.decorView.post {
            lifecycleScope.launch {
                delay(250)
                postDrawStartupQueued = false
                if (!lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) {
                    return@launch
                }
                LocalNightscoutServiceController.start(this@MainActivity)
                if (hasActivityRecognitionPermission()) {
                    (application as? CopilotApp)?.container?.startLocalActivitySensors()
                } else {
                    ensureActivityRecognitionPermission()
                }
                if (healthConnectEnabled) {
                    ensureHealthConnectPermissions()
                    (application as? CopilotApp)?.container?.startHealthConnectCollection()
                }
            }
        }
    }

    private fun hasActivityRecognitionPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return true
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACTIVITY_RECOGNITION
        ) == PackageManager.PERMISSION_GRANTED
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
