package io.aaps.copilot.ui.motion

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.unit.dp
import io.aaps.copilot.ui.foundation.theme.AapsCopilotTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MealPreparationGlyphRenderTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun realForkAndBurgerPixelsMoveThenRestAndRespectDisabledMotion() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        compose.mainClock.autoAdvance = false
        var enabled by mutableStateOf(true)
        try {
            controller.get().setContent {
                AapsCopilotTheme(dynamicColor = false) {
                    ProvideCopilotMotion(enabled) {
                        Box(Modifier.fillMaxSize().background(Color.White).padding(32.dp)) {
                            MealPreparationGlyph("meal-motion", true)
                        }
                    }
                }
            }
            compose.waitForIdle()
            compose.mainClock.advanceTimeBy(160); compose.waitForIdle()
            val early = draw(controller.get().window.decorView)
            compose.mainClock.advanceTimeBy(240); compose.waitForIdle()
            val moving = draw(controller.get().window.decorView)
            assertFalse("Food gesture must move actual rendered pixels", early.sameAs(moving))
            compose.mainClock.advanceTimeBy(3_000); compose.waitForIdle()
            val rest = draw(controller.get().window.decorView)
            compose.mainClock.advanceTimeBy(2_000); compose.waitForIdle()
            assertTrue("No perpetual drawing after finite preparation gesture", rest.sameAs(draw(controller.get().window.decorView)))
            compose.runOnIdle { enabled = false }
            compose.mainClock.advanceTimeByFrame(); compose.mainClock.advanceTimeByFrame(); compose.waitForIdle()
            assertTrue(rest.sameAs(draw(controller.get().window.decorView)))
            System.getenv("MEAL_UI_EVIDENCE_DIR")?.let { directory ->
                mapOf("early" to early, "moving" to moving, "rest" to rest).forEach { (name, bitmap) ->
                    File(directory, "meal-motion-$name.png").outputStream().use {
                        assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
                    }
                }
            }
        } finally { controller.pause().stop().destroy() }
    }

    private fun draw(view: View): Bitmap {
        fun composeView(value: View): View? {
            if (value.javaClass.simpleName == "AndroidComposeView") return value
            if (value is ViewGroup) for (index in 0 until value.childCount)
                composeView(value.getChildAt(index))?.let { return it }
            return null
        }
        val target = checkNotNull(composeView(view))
        assertTrue(target.width > 0 && target.height > 0)
        return Bitmap.createBitmap(target.width, target.height, Bitmap.Config.ARGB_8888)
            .also { target.draw(Canvas(it)) }
    }
}
