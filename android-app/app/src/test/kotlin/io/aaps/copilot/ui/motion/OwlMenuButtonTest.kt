package io.aaps.copilot.ui.motion

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import io.aaps.copilot.R
import io.aaps.copilot.ui.foundation.headerOwlGesture
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w320dp-h640dp-port")
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class OwlMenuButtonTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun menuActionIsImmediateFixedAndReturnsFocusWhenClosed() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        var expanded by mutableStateOf(false)
        var clicks = 0
        lateinit var inputMode: InputModeManager
        compose.mainClock.autoAdvance = false
        try {
            controller.get().setContent {
                inputMode = LocalInputModeManager.current
                MaterialTheme {
                    ProvideCopilotMotion(true) {
                        OwlMenuButton(OwlColorBand.GREEN, "overview", OwlGesture.GREET,
                            expanded, false, { clicks++; expanded = !expanded })
                    }
                }
            }
            compose.waitForIdle()
            compose.runOnIdle { assertTrue(inputMode.requestInputMode(InputMode.Keyboard)) }
            flush()
            val button = compose.onNodeWithTag("owlMenuAction")
            button.assertHasClickAction().assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
            val bounds = button.fetchSemanticsNode().boundsInRoot
            assertTrue(bounds.width >= 48f && bounds.height >= 48f)
            button.performClick()
            assertEquals("click is not delayed by artwork", 1, clicks)
            flush()
            compose.onNodeWithContentDescription(RuntimeEnvironment.getApplication().getString(R.string.menu_close)).assertIsDisplayed()
            button.performClick(); assertEquals(2, clicks); flush()
            button.assertIsFocused()
            assertEquals(bounds, button.fetchSemanticsNode().boundsInRoot)
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun routeRasterMovesSettlesAndColorChangeDoesNotReplay() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        compose.mainClock.autoAdvance = false
        var band by mutableStateOf(OwlColorBand.GREEN)
        try {
            controller.get().setContent {
                MaterialTheme {
                    ProvideCopilotMotion(true) {
                        Column(Modifier.fillMaxSize().background(Color.White)) {
                            OwlMenuButton(band, "forecast", OwlGesture.GAZE, false, false, {})
                        }
                    }
                }
            }
            flush(); compose.mainClock.advanceTimeBy(160); compose.waitForIdle()
            val early = draw(controller.get().window.decorView)
            compose.mainClock.advanceTimeBy(260); compose.waitForIdle()
            val moving = draw(controller.get().window.decorView)
            assertFalse("forward gaze must affect native pixels", early.sameAs(moving))
            compose.mainClock.advanceTimeBy(1_000); compose.waitForIdle()
            val settled = draw(controller.get().window.decorView)
            compose.mainClock.advanceTimeBy(1_000); compose.waitForIdle()
            assertTrue(settled.sameAs(draw(controller.get().window.decorView)))
            compose.runOnIdle { band = OwlColorBand.RED }; flush()
            val recolored = draw(controller.get().window.decorView)
            assertFalse(settled.sameAs(recolored))
            compose.mainClock.advanceTimeBy(360); compose.waitForIdle()
            assertTrue("color update cannot become another visit", recolored.sameAs(draw(controller.get().window.decorView)))
            save("owl-menu-forward-early", early); save("owl-menu-forward-moving", moving)
            save("owl-menu-forward-settled", settled); save("owl-menu-recolored", recolored)
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun busySemanticsSurviveFiniteCapAndReducedMotion() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        compose.mainClock.autoAdvance = false
        var enabled by mutableStateOf(true)
        var busy by mutableStateOf(true)
        val progress = SemanticsMatcher.expectValue(SemanticsProperties.ProgressBarRangeInfo, ProgressBarRangeInfo.Indeterminate)
        try {
            controller.get().setContent {
                MaterialTheme {
                    ProvideCopilotMotion(enabled) {
                        OwlMenuButton(OwlColorBand.UNKNOWN, "ai", OwlGesture.LISTEN, false, busy, {})
                    }
                }
            }
            flush(); compose.mainClock.advanceTimeBy(10_000); compose.waitForIdle()
            compose.onNodeWithTag("owlMenuAction").assert(progress).assertHasClickAction()
            compose.runOnIdle { enabled = false }; flush()
            compose.onNodeWithTag("owlMenuAction").assert(progress)
            compose.runOnIdle { busy = false }; flush()
            compose.onNodeWithTag("owlMenuAction").assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.ProgressBarRangeInfo))
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun initiallySuppressedGestureNeverAppearsOnReenable() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        compose.mainClock.autoAdvance = false
        var enabled by mutableStateOf(false)
        try {
            controller.get().setContent {
                MaterialTheme {
                    ProvideCopilotMotion(enabled) {
                        Column(Modifier.fillMaxSize().background(Color.White)) {
                            OwlMenuButton(OwlColorBand.GREEN, "overview", OwlGesture.GREET, false, false, {})
                        }
                    }
                }
            }
            flush()
            val before = draw(controller.get().window.decorView)
            compose.runOnIdle { enabled = true }; flush()
            compose.mainClock.advanceTimeBy(200); compose.waitForIdle()
            assertTrue(before.sameAs(draw(controller.get().window.decorView)))
            compose.mainClock.advanceTimeBy(240); compose.waitForIdle()
            assertTrue(before.sameAs(draw(controller.get().window.decorView)))
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun actualRoutesMapToTheApprovedGestures() {
        assertEquals(OwlGesture.GREET, headerOwlGesture("overview"))
        assertEquals(OwlGesture.GAZE, headerOwlGesture("forecast"))
        assertEquals(OwlGesture.NOD, headerOwlGesture("analytics"))
        assertEquals(OwlGesture.LISTEN, headerOwlGesture("ai_analysis"))
        assertEquals(OwlGesture.STILL, headerOwlGesture("settings"))
        assertEquals(OwlGesture.STILL, headerOwlGesture(null))
    }

    private fun flush() {
        compose.waitForIdle()
        compose.mainClock.advanceTimeByFrame(); compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
    }

    private fun draw(view: View): Bitmap {
        fun owner(node: View): View? {
            if (node.javaClass.simpleName == "AndroidComposeView") return node
            if (node is ViewGroup) repeat(node.childCount) { owner(node.getChildAt(it))?.let { return it } }
            return null
        }
        val target = checkNotNull(owner(view))
        return Bitmap.createBitmap(target.width, target.height, Bitmap.Config.ARGB_8888).also { target.draw(Canvas(it)) }
    }

    private fun save(name: String, image: Bitmap) {
        System.getenv("FEATHER_UI_EVIDENCE_DIR")?.let { root ->
            File(root, "$name.png").outputStream().use { assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        }
    }
}
