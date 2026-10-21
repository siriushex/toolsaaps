package io.aaps.copilot.ui.motion

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import io.aaps.copilot.config.UiStyle
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
@Config(sdk = [35], application = Application::class, qualifiers = "ru-rRU-w360dp-h640dp-port")
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ChatWaitingRenderTest {
    @get:Rule val compose = createEmptyComposeRule()
    @Test fun realNativeFramesMoveButReducedMotionIsStationary() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        val frame = mutableLongStateOf(0)
        val enabled = mutableStateOf(true)
        try {
            controller.get().setContent {
                AapsCopilotTheme(darkTheme = false, dynamicColor = false, uiStyle = UiStyle.CLARITY) {
                    CompositionLocalProvider(LocalCopilotMotionEnabled provides enabled.value) {
                        Surface { Box(Modifier.padding(16.dp)) { AiChatWaitingIndicator("sample-request", frameClock = frame) } }
                    }
                }
            }
            fun pixels(time: Long): Bitmap {
                compose.runOnIdle { frame.longValue = time }
                val view = controller.get().window.decorView
                return Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also { view.draw(Canvas(it)) }
            }
            val first = pixels(0)
            val middle = pixels(480)
            assertFalse(first.sameAs(middle))
            compose.runOnIdle { enabled.value = false }
            val reduced = pixels(480)
            val later = pixels(1_920)
            assertTrue(reduced.sameAs(later))
            first.recycle(); middle.recycle(); reduced.recycle(); later.recycle()
            System.getenv("CHAT_MOTION_RENDER_DIR")?.let { directory ->
                File(directory).mkdirs()
                compose.runOnIdle { enabled.value = true }
                for (index in 0..108) {
                    val bitmap = pixels(index * 7_200L / 108)
                    File(directory, "frame-%03d.png".format(index)).outputStream().use {
                        assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
                    }
                    bitmap.recycle()
                }
            }
        } finally { controller.pause().stop().destroy() }
    }
}
