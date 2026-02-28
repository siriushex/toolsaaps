package io.aaps.copilot

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import io.aaps.copilot.service.AppVisibilityTracker
import io.aaps.copilot.service.LocalNightscoutServiceController
import io.aaps.copilot.ui.CopilotRoot

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            Surface(color = MaterialTheme.colorScheme.background) {
                CopilotRoot()
            }
        }
    }

    override fun onStart() {
        super.onStart()
        AppVisibilityTracker.markForeground(true)
        LocalNightscoutServiceController.start(this)
    }

    override fun onStop() {
        AppVisibilityTracker.markForeground(false)
        super.onStop()
    }
}
