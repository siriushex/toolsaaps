package io.aaps.copilot.ui.motion

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
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
class NativeInteractionMotionTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun springRetargetsDuringRapidPressesAndCancelRestoresNeutral() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        compose.mainClock.autoAdvance = false
        val interactions = MutableInteractionSource()
        var observed = 1f
        var enabled by mutableStateOf(true)
        try {
            controller.get().setContent {
                ProvideCopilotMotion(enabled) {
                    val scale = rememberNativePressScale(interactions, true)
                    observed = scale.value
                    Text(observed.toString())
                }
            }
            compose.waitForIdle()
            val first = PressInteraction.Press(Offset.Zero)
            compose.runOnIdle { assertTrue(interactions.tryEmit(first)) }
            flush()
            compose.mainClock.advanceTimeBy(80); compose.waitForIdle()
            assertTrue("press should move toward its target", observed in .939f.. .99f)
            compose.runOnIdle { assertTrue(interactions.tryEmit(PressInteraction.Release(first))) }
            flush()
            repeat(8) {
                val press = PressInteraction.Press(Offset.Zero)
                compose.runOnIdle { assertTrue(interactions.tryEmit(press)) }
                flush(); compose.mainClock.advanceTimeBy(32); compose.waitForIdle()
                assertTrue(observed in .939f..1.001f)
                compose.runOnIdle { assertTrue(interactions.tryEmit(PressInteraction.Cancel(press))) }
                flush(); compose.mainClock.advanceTimeBy(32); compose.waitForIdle()
                assertTrue(observed in .939f..1.001f)
            }
            compose.mainClock.advanceTimeBy(1_000); compose.waitForIdle()
            assertEquals(1f, observed, .0001f)
            val held = PressInteraction.Press(Offset.Zero)
            compose.runOnIdle { interactions.tryEmit(held) }
            flush(); compose.mainClock.advanceTimeBy(400); compose.waitForIdle()
            assertEquals(NativeMotion.pressedIconScale, observed, .001f)
            compose.runOnIdle { enabled = false }; flush()
            assertEquals("reduced motion resets immediately", 1f, observed, 0f)
            compose.runOnIdle { interactions.tryEmit(PressInteraction.Cancel(held)) }
            compose.runOnIdle { enabled = true }; flush()
            compose.mainClock.advanceTimeBy(1_000); compose.waitForIdle()
            assertEquals(1f, observed, .0001f)
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun nativeButtonKeepsHitBoundsAndDispatchesOnlyTheExistingAction() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        compose.mainClock.autoAdvance = false
        var clicks = 0
        var enabled by mutableStateOf(true)
        try {
            controller.get().setContent {
                MaterialTheme {
                    ProvideCopilotMotion(true) {
                        Column(Modifier.fillMaxSize().background(Color.White)) {
                            NativeIconButton({ clicks++ }, Modifier.size(48.dp).testTag("button"), enabled) {
                                Icon(Icons.Default.Menu, "Menu", tint = Color.Black)
                            }
                        }
                    }
                }
            }
            compose.waitForIdle()
            val button = compose.onNodeWithTag("button")
            button.assertHasClickAction().assertIsEnabled()
            val bounds = button.fetchSemanticsNode().boundsInRoot
            assertTrue(bounds.width >= 48f && bounds.height >= 48f)
            val resting = draw(controller.get().window.decorView)
            button.performTouchInput { down(center) }
            flush(); compose.mainClock.advanceTimeBy(80); compose.waitForIdle()
            assertEquals(bounds, button.fetchSemanticsNode().boundsInRoot)
            assertEquals(0, clicks)
            val pressed = draw(controller.get().window.decorView)
            assertFalse("press feedback must change native pixels", resting.sameAs(pressed))
            button.performTouchInput { cancel() }
            flush(); compose.mainClock.advanceTimeBy(1_000); compose.waitForIdle()
            assertEquals(0, clicks)
            button.performTouchInput { down(center); advanceEventTime(40); up() }
            assertEquals(1, clicks)
            repeat(5) { button.performClick() }
            assertEquals("actions must not wait for animation completion", 6, clicks)
            compose.runOnIdle { enabled = false }; flush()
            button.assertIsNotEnabled().performClick()
            assertEquals(6, clicks)
            save("native-press-resting", resting); save("native-press-active", pressed)
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun drawerReversesInPlaceAndComesFromTheStartEdgeLtr() = verifyDrawer(LayoutDirection.Ltr)
    @Test fun drawerReversesInPlaceAndComesFromTheStartEdgeRtl() = verifyDrawer(LayoutDirection.Rtl)

    private fun verifyDrawer(direction: LayoutDirection) {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        compose.mainClock.autoAdvance = false
        var visible by mutableStateOf(false)
        var dismissals = 0
        try {
            controller.get().setContent {
                CompositionLocalProvider(LocalLayoutDirection provides direction) {
                    ProvideCopilotMotion(true) {
                        NativeDrawerMotion(visible, Color.Black.copy(alpha = .3f), { dismissals++ }, Modifier.testTag("overlay")) { motion ->
                            Box(Modifier.width(240.dp).fillMaxHeight().then(motion)
                                .background(Color(0xFFF2F7F7)).clickable { }.testTag("panel")) { Text("Menu") }
                        }
                    }
                }
            }
            compose.waitForIdle()
            compose.runOnIdle { visible = true }; flush()
            compose.mainClock.advanceTimeBy(64); compose.waitForIdle()
            val panel = compose.onNodeWithTag("panel")
            fun left() = panel.getUnclippedBoundsInRoot().left.value
            val entering = left()
            assertTrue("entry must start from the logical start edge: $entering",
                if (direction == LayoutDirection.Ltr) entering in -240f.. -.1f else entering in 80.1f..320f)
            save("native-drawer-${direction.name}-entering", draw(controller.get().window.decorView))
            compose.mainClock.advanceTimeBy(1_000); compose.waitForIdle()
            assertEquals(if (direction == LayoutDirection.Ltr) 0f else 80f, left(), .5f)
            compose.onNodeWithTag("overlay").performTouchInput {
                click(Offset(if (direction == LayoutDirection.Ltr) width - 10f else 10f, height / 2f))
            }
            assertEquals(1, dismissals)
            compose.runOnIdle { visible = false }; flush()
            compose.mainClock.advanceTimeBy(64); compose.waitForIdle()
            val exiting = left()
            compose.runOnIdle { visible = true }; flush()
            val reversed = left()
            assertTrue("a fast reversal must not reset to an endpoint", kotlin.math.abs(reversed - exiting) < 120f)
            compose.mainClock.advanceTimeBy(1_000); compose.waitForIdle()
            panel.assertIsDisplayed()
            assertEquals(if (direction == LayoutDirection.Ltr) 0f else 80f, left(), .5f)
            compose.runOnIdle { visible = false }; flush()
            compose.mainClock.advanceTimeBy(1_000); compose.waitForIdle()
            compose.onNodeWithTag("overlay").assertDoesNotExist()
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun disabledMotionDrawerSnapsAndLeavesNoInvisibleTouchBlocker() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        compose.mainClock.autoAdvance = false
        var visible by mutableStateOf(false)
        var enabled by mutableStateOf(false)
        var contentInstances = 0
        try {
            controller.get().setContent {
                ProvideCopilotMotion(enabled) {
                    NativeDrawerMotion(visible, Color.Black, {}, Modifier.testTag("overlay")) { motion ->
                        val identity = remember { ++contentInstances }
                        Box(Modifier.width(240.dp).fillMaxHeight().then(motion).testTag("panel")) { Text(identity.toString()) }
                    }
                }
            }
            compose.waitForIdle()
            compose.runOnIdle { visible = true }; flush()
            assertEquals(0f, compose.onNodeWithTag("panel").getUnclippedBoundsInRoot().left.value, .1f)
            compose.runOnIdle { enabled = true }; flush()
            compose.runOnIdle { visible = false }; flush()
            compose.mainClock.advanceTimeBy(64); compose.waitForIdle()
            compose.onNodeWithTag("overlay").assertExists()
            compose.runOnIdle { enabled = false }; flush()
            compose.onNodeWithTag("overlay").assertDoesNotExist()
            compose.runOnIdle { enabled = true }; flush()
            compose.runOnIdle { visible = true }; flush()
            compose.mainClock.advanceTimeBy(64); compose.waitForIdle()
            val instancesBeforeDisable = contentInstances
            compose.runOnIdle { enabled = false }; flush()
            assertEquals(0f, compose.onNodeWithTag("panel").getUnclippedBoundsInRoot().left.value, .1f)
            assertEquals("disabling entry motion must preserve visible content state", instancesBeforeDisable, contentInstances)
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun systemAnimatorSettingCancelsPressMotionWithoutChangingTheAction() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        val resolver = RuntimeEnvironment.getApplication().contentResolver
        val name = Settings.Global.ANIMATOR_DURATION_SCALE
        val previous = Settings.Global.getFloat(resolver, name, 1f)
        compose.mainClock.autoAdvance = false
        val interactions = MutableInteractionSource()
        var observed = 1f
        var gate = true
        try {
            controller.get().setContent {
                ProvideCopilotMotion(true) {
                    gate = LocalCopilotMotionEnabled.current
                    val scale = rememberNativePressScale(interactions, true)
                    observed = scale.value
                    Text(observed.toString())
                }
            }
            compose.waitForIdle()
            val press = PressInteraction.Press(Offset.Zero)
            compose.runOnIdle { interactions.tryEmit(press) }
            flush(); compose.mainClock.advanceTimeBy(400); compose.waitForIdle()
            assertEquals(NativeMotion.pressedIconScale, observed, .001f)
            compose.runOnIdle {
                Settings.Global.putFloat(resolver, name, 0f)
                resolver.notifyChange(Settings.Global.getUriFor(name), null)
            }
            flush()
            assertFalse(gate)
            assertEquals(1f, observed, 0f)
            compose.mainClock.advanceTimeBy(1_000); compose.waitForIdle()
            assertEquals(1f, observed, 0f)
        } finally {
            Settings.Global.putFloat(resolver, name, previous)
            controller.pause().stop().destroy()
        }
    }

    private fun flush() {
        compose.waitForIdle()
        compose.mainClock.advanceTimeByFrame(); compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
    }

    private fun save(name: String, bitmap: Bitmap) {
        System.getenv("FEATHER_UI_EVIDENCE_DIR")?.let { dir ->
            val file = File(dir, "$name.png"); checkNotNull(file.parentFile).mkdirs()
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    private fun draw(view: View): Bitmap {
        fun find(v: View): View? {
            if (v.javaClass.simpleName == "AndroidComposeView") return v
            if (v is ViewGroup) for (i in 0 until v.childCount) find(v.getChildAt(i))?.let { return it }
            return null
        }
        val target = checkNotNull(find(view))
        return Bitmap.createBitmap(target.width, target.height, Bitmap.Config.ARGB_8888).also { target.draw(Canvas(it)) }
    }
}
