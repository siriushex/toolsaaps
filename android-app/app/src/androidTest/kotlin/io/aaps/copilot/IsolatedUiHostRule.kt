package io.aaps.copilot

import android.app.Activity
import android.app.KeyguardManager
import android.app.UiAutomation
import android.content.res.Configuration
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.rules.ExternalResource

internal class IsolatedUiHostRule : ExternalResource() {
    override fun before() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        check(instrumentation.targetContext.packageName == "io.aaps.predictivecopilot.uitest") {
            "Refusing a non-isolated UI target"
        }
        val context = instrumentation.targetContext
        check(context.getSystemService(PowerManager::class.java).isInteractive &&
            !context.getSystemService(KeyguardManager::class.java).isKeyguardLocked) {
            "Unlock the phone and keep its screen on for isolated UI tests"
        }
        // Keep the inert host foreground while ActivityScenario launches its own Activity.
        val command = "am start -W -n io.aaps.predictivecopilot.uitest/androidx.activity.ComponentActivity"
        val result = ParcelFileDescriptor.AutoCloseInputStream(
            instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
                .executeShellCommand(command)
        ).bufferedReader().use { it.readText() }
        check(result.lineSequence().any { it.trim() == "Status: ok" }) {
            "Isolated UI host launch failed: ${result.take(512)}"
        }
    }

    @Suppress("DEPRECATION")
    fun withFontScale(activity: Activity, fontScale: Float, body: () -> Unit) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        check(activity.packageName == "io.aaps.predictivecopilot.uitest" &&
            instrumentation.targetContext.packageName == activity.packageName) {
            "Refusing a non-isolated font-scale override"
        }
        val resources = listOf(
            activity.resources,
            instrumentation.targetContext.resources,
            instrumentation.targetContext.applicationContext.resources
        ).distinct()
        val originals = resources.associateWith { Configuration(it.configuration) }
        // Native Dialog creates a new window density from the isolated process resources.
        try {
            instrumentation.runOnMainSync {
                resources.forEach { resource ->
                    val configuration = Configuration(originals.getValue(resource))
                    configuration.fontScale = fontScale
                    resource.updateConfiguration(configuration, resource.displayMetrics)
                }
            }
            body()
        } finally {
            instrumentation.runOnMainSync {
                resources.forEach { resource ->
                    resource.updateConfiguration(originals.getValue(resource), resource.displayMetrics)
                }
                check(resources.all { it.configuration.fontScale == originals.getValue(it).fontScale }) {
                    "Isolated font-scale resources were not restored"
                }
            }
        }
    }
}
