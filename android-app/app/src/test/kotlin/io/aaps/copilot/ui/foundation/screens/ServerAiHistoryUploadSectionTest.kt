package io.aaps.copilot.ui.foundation.screens

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.aaps.copilot.config.AiHistoryUploadSettings
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
@Config(sdk = [35], application = Application::class, qualifiers = "ru-rRU-w320dp-h640dp-port")
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ServerAiHistoryUploadSectionTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun intervalMenuAndBackgroundSwitchUseTheRealCallbacks() = screen(1f, true) { current, uploads ->
        compose.onNodeWithTag("history_upload_interval").performClick()
        compose.onNodeWithTag("history_upload_interval_240").performClick()
        assertEquals(240, current().intervalMinutes)
        compose.onNodeWithTag("history_upload_enabled").performClick()
        assertFalse(current().enabled)
        compose.onNodeWithTag("history_upload_interval").assertIsNotEnabled()
        compose.onNodeWithTag("history_upload_now").performClick()
        assertEquals(1, uploads())
    }

    @Test fun unactivatedConnectionCannotSubmitAndLargeTextFits() = screen(1.8f, false) { _, uploads ->
        compose.onNodeWithTag("history_upload_now").assertIsNotEnabled()
        assertEquals(0, uploads())
        val background = compose.onNodeWithTag("history_upload_settings").fetchSemanticsNode().boundsInRoot
        val button = compose.onNodeWithTag("history_upload_now").fetchSemanticsNode().boundsInRoot
        assertTrue(button.left >= background.left && button.right <= background.right)
        assertTrue(button.bottom <= background.bottom)
    }

    private fun screen(scale: Float, connected: Boolean, checks: (() -> AiHistoryUploadSettings, () -> Int) -> Unit) {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val activity = controller.get()
        var settings by mutableStateOf(AiHistoryUploadSettings())
        var uploads = 0
        activity.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, scale)) {
                MaterialTheme {
                    Box(Modifier.fillMaxSize().padding(16.dp)) {
                        ServerAiHistoryUploadSection(settings, connected,
                            { settings = settings.copy(enabled = it) },
                            { settings = settings.copy(intervalMinutes = it) }, { uploads++ })
                    }
                }
            }
        }
        try {
            compose.waitForIdle()
            checks({ settings }, { uploads })
            compose.waitForIdle()
            val root = activity.window.decorView
            val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
            root.draw(Canvas(bitmap))
            val file = File("build/test-results/history-upload-$scale.png")
            file.parentFile.mkdirs()
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        } finally { controller.pause().stop().destroy() }
    }
}
