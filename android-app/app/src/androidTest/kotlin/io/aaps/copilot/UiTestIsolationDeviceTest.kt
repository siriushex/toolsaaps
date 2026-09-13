package io.aaps.copilot

import android.Manifest
import android.content.Intent
import android.content.pm.ComponentInfo
import android.content.pm.PackageManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class UiTestIsolationDeviceTest {

    @Test
    fun targetIsIsolatedFromProductionRuntime() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val packageManager = context.packageManager

        assertEquals(UI_TEST_TARGET_PACKAGE, context.packageName)
        assertEquals(UiTestApplication::class.java, context.applicationContext::class.java)
        assertEquals(
            PackageManager.PERMISSION_DENIED,
            packageManager.checkPermission(Manifest.permission.INTERNET, context.packageName)
        )

        val packageInfo = packageInfo(packageManager, context.packageName)
        val installedComponents = buildList {
            addAll(packageInfo.activities.orEmpty())
            addAll(packageInfo.services.orEmpty())
            addAll(packageInfo.receivers.orEmpty())
            addAll(packageInfo.providers.orEmpty())
        }.associateBy(ComponentInfo::name)

        assertEquals(setOf(TEST_COMPONENT_ACTIVITY, TEST_PDF_ACTIVITY), installedComponents.keys)
        assertFalse(installedComponents.getValue(TEST_PDF_ACTIVITY).exported)
        PRODUCTION_COMPONENTS.forEach { componentName ->
            assertFalse(
                "$componentName must be absent or disabled",
                installedComponents[componentName]?.enabled == true
            )
        }

        val launcherIntent = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setPackage(context.packageName)
        assertTrue(packageManager.queryIntentActivities(launcherIntent, 0).isEmpty())
    }

    @Suppress("DEPRECATION")
    private fun packageInfo(packageManager: PackageManager, packageName: String) =
        packageManager.getPackageInfo(
            packageName,
            PackageManager.GET_ACTIVITIES or
                PackageManager.GET_SERVICES or
                PackageManager.GET_RECEIVERS or
                PackageManager.GET_PROVIDERS
        )

    private companion object {
        const val UI_TEST_TARGET_PACKAGE = "io.aaps.predictivecopilot.uitest"
        const val TEST_COMPONENT_ACTIVITY = "androidx.activity.ComponentActivity"
        const val TEST_PDF_ACTIVITY = "io.aaps.copilot.ClinicalPdfPickerRecreationActivity"
        val PRODUCTION_COMPONENTS = setOf(
            "io.aaps.copilot.MainActivity",
            "io.aaps.copilot.service.LocalNightscoutForegroundService",
            "io.aaps.copilot.receiver.BootCompletedReceiver",
            "io.aaps.copilot.receiver.LocalDataBroadcastReceiver",
            "io.aaps.copilot.receiver.GlucoseAlertActionReceiver",
            "io.aaps.copilot.widget.CopilotGlucoseWidgetProvider",
            "io.aaps.copilot.receiver.DebugActionReceiver",
            "androidx.startup.InitializationProvider"
        )
    }
}
