package io.aaps.copilot.ui.foundation.screens

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.unit.Density
import io.aaps.copilot.config.UiStyle
import io.aaps.copilot.data.repository.*
import io.aaps.copilot.ui.foundation.theme.AapsCopilotTheme
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w320dp-h640dp-port")
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AlarmRuntimeControlsTest {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var root: View
    @Test fun explicitToggleDoesNotOpenSystemSettingsOrStartOnComposition() {
        val changes = mutableListOf<Boolean>()
        screen(false, onToggle = { changes += it }) {
            assertTrue(changes.isEmpty())
            compose.onNodeWithTag("alarmRuntimeSwitch").assertIsOff().performClick()
            assertEquals(listOf(true), changes)
            compose.onNodeWithContentDescription("Alarms and reminders permission").assertDoesNotExist()
        }
    }
    @Test fun loadingAndPendingSaveCannotToggleConsent() {
        screen(null, busy = true) {
            compose.onNodeWithTag("alarmRuntimeSwitch").assertIsNotEnabled()
        }
    }
    @Test fun permissionAndRetryActionsRemainExplicitAndIndependent() {
        var alarm = 0; var notifications = 0; var retry = 0
        screen(true, state = GlucoseAlarmOwnerState(GlucoseAlarmOwnerPhase.UNAVAILABLE),
            onAlarm = { alarm++ }, onNotifications = { notifications++ }, onRetry = { retry++ }) {
            assertEquals(0, alarm + notifications + retry)
            compose.onNodeWithContentDescription("Alarms and reminders permission").performClick()
            compose.onNodeWithContentDescription("Notification permission").performClick()
            compose.onNodeWithContentDescription("Retry background alerts").performClick()
            assertEquals(1, alarm); assertEquals(1, notifications); assertEquals(1, retry)
        }
    }
    @Config(qualifiers = "ru-rRU-w320dp-h640dp-port")
    @Test fun russianLargeTextKeepsToggleAndPermissionActionsVisible() {
        screen(true, state = GlucoseAlarmOwnerState(GlucoseAlarmOwnerPhase.LIMITED), fontScale = 1.5f) {
            compose.onNodeWithTag("alarmRuntimeSwitch").assertIsDisplayed()
            compose.onNodeWithContentDescription("Разрешение будильников и напоминаний").assertIsDisplayed()
            compose.onNodeWithContentDescription("Разрешение уведомлений").assertIsDisplayed()
            val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
            root.draw(Canvas(bitmap))
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            assertTrue(pixels.toSet().size > 32)
            val dir = File("build/reports/alarm-runtime-raster").apply { mkdirs() }
            File(dir, "alarm-controls-large.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
    private fun screen(enabled: Boolean?, state: GlucoseAlarmOwnerState = GlucoseAlarmOwnerState(), busy: Boolean = false,
        fontScale: Float = 1f, onToggle: (Boolean) -> Unit = { error("unexpected consent") },
        onAlarm: () -> Unit = { error("unexpected alarm settings") },
        onNotifications: () -> Unit = { error("unexpected notification settings") },
        onRetry: () -> Unit = { error("unexpected restart") }, test: () -> Unit) {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        root = controller.get().window.decorView
        try {
            controller.get().setContent {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                    AapsCopilotTheme(darkTheme = false, dynamicColor = false, uiStyle = UiStyle.CLARITY) {
                        Surface(Modifier.fillMaxSize()) {
                            AlarmRuntimeControlsContent(enabled, state, busy, false, onToggle, onAlarm, onNotifications, onRetry)
                        }
                    }
                }
            }
            compose.waitForIdle(); test(); compose.waitForIdle()
        } finally { controller.pause().stop().destroy() }
    }
}
