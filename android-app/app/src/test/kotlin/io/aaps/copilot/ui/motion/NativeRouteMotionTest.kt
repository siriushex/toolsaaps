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
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import io.aaps.copilot.ui.foundation.headerOwlGesture
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
@Config(sdk = [35], application = Application::class, qualifiers = "w320dp-h640dp-port")
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NativeRouteMotionTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun policyChangeDuringNavigationNeverHidesOrDelaysTheCurrentRoute() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        compose.mainClock.autoAdvance = false
        var enabled by mutableStateOf(true)
        lateinit var nav: NavHostController
        val actions = mutableListOf<String>()
        try {
            activity.get().setContent {
                NavigationFixture(enabled, { nav = it }, actions::add)
            }
            flush(); compose.mainClock.advanceTimeBy(800); compose.waitForIdle()
            compose.runOnIdle { nav.navigate("forecast") }; flush()
            compose.mainClock.advanceTimeBy(48); compose.waitForIdle()
            assertEquals("forecast", nav.currentDestination?.route)
            val visible = draw(activity.get().window.decorView)
            assertEquals("navigation is immediately opaque even with motion enabled",
                0xFFF5E4E1.toInt(), visible.getPixel(visible.width - 2, visible.height - 2))
            compose.runOnIdle { enabled = false }; flush()
            val suppressed = draw(activity.get().window.decorView)
            compose.mainClock.advanceTimeBy(400); compose.waitForIdle()
            save("route-suppressed", suppressed)
            save("route-after-clock", draw(activity.get().window.decorView))
            assertEquals("the current route must be fully visible, not frozen transparent",
                0xFFF5E4E1.toInt(), suppressed.getPixel(suppressed.width - 2, suppressed.height - 2))
            assertTrue("disabling motion during navigation must settle native pixels immediately",
                suppressed.sameAs(draw(activity.get().window.decorView)))
            compose.onNodeWithTag("action-forecast").assertIsDisplayed().performTouchInput { click(center) }
            assertEquals(listOf("forecast"), actions)
            compose.mainClock.advanceTimeBy(1_000); compose.waitForIdle()
            val settled = draw(activity.get().window.decorView)
            compose.runOnIdle { enabled = true }; flush()
            compose.mainClock.advanceTimeBy(400); compose.waitForIdle()
            assertTrue("reenabling cannot replay a consumed header or fade",
                settled.sameAs(draw(activity.get().window.decorView)))
            assertEquals(listOf("forecast"), actions)
        } finally { activity.pause().stop().destroy() }
    }

    @Test fun rapidRouteChangesAndPopLeaveOnlyTheFinalDestinationInteractive() =
        verifyRapidReturn(firstTouch = true)

    @Test fun returnAfterAccessibilityActionDoesNotRetainAnOldTouchHandler() =
        verifyRapidReturn(firstTouch = false)

    private fun verifyRapidReturn(firstTouch: Boolean) {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        compose.mainClock.autoAdvance = false
        var enabled by mutableStateOf(true)
        lateinit var nav: NavHostController
        val actions = mutableListOf<String>()
        try {
            activity.get().setContent {
                NavigationFixture(enabled, { nav = it }, actions::add)
            }
            flush(); compose.mainClock.advanceTimeBy(800); compose.waitForIdle()
            listOf("forecast", "analytics", "ai_analysis").forEach { route ->
                compose.runOnIdle { nav.navigate(route) }; flush()
            }
            compose.runOnIdle { assertTrue(nav.popBackStack()) }; flush()
            compose.mainClock.advanceTimeBy(1_000); compose.waitForIdle()
            assertEquals("analytics", nav.currentDestination?.route)
            listOf("overview", "forecast", "ai_analysis").forEach { route ->
                compose.onNodeWithTag("pane-$route").assertDoesNotExist()
            }
            val oldAction = compose.onNodeWithTag("action-analytics").assertIsDisplayed()
            if (firstTouch) oldAction.performTouchInput { click(center) } else oldAction.performClick()
            assertEquals(listOf("analytics"), actions)
            val popStart = compose.mainClock.currentTime
            compose.runOnIdle { enabled = false; assertTrue(nav.popBackStack()) }; flush()
            assertEquals("forecast", nav.currentDestination?.route)
            val visible = awaitCommittedPane(activity.get().window.decorView, "analytics", popStart)
            assertEquals(0xFFF5E4E1.toInt(), visible.getPixel(visible.width - 2, visible.height - 2))
            save("popped-route", visible)
            compose.onNodeWithTag("action-forecast").assertIsDisplayed().performTouchInput { click(center) }
            assertEquals(listOf("analytics", "forecast"), actions)
        } finally { activity.pause().stop().destroy() }
    }

    @Test fun changingMotionPolicyAndReturningPreserveTheRouteDraft() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        compose.mainClock.autoAdvance = false
        var enabled by mutableStateOf(true)
        lateinit var nav: NavHostController
        try {
            activity.get().setContent { NavigationFixture(enabled, { nav = it }, {}) }
            flush()
            compose.onNodeWithTag("draft-overview").performTextInput("review note")
            compose.runOnIdle { nav.navigate("forecast") }; flush()
            compose.runOnIdle { enabled = false }; flush()
            val popStart = compose.mainClock.currentTime
            compose.runOnIdle { assertTrue(nav.popBackStack()) }; flush()
            awaitCommittedPane(activity.get().window.decorView, "forecast", popStart)
            compose.onNodeWithTag("draft-overview").assertTextContains("review note")
            compose.runOnIdle { enabled = true }; flush()
            compose.onNodeWithTag("draft-overview").assertTextContains("review note")
        } finally { activity.pause().stop().destroy() }
    }

    @Composable
    private fun NavigationFixture(enabled: Boolean, captureNav: (NavHostController) -> Unit,
        onAction: (String) -> Unit) {
        val nav = rememberNavController()
        captureNav(nav)
        val entry by nav.currentBackStackEntryAsState()
        val route = entry?.destination?.route.orEmpty()
        MaterialTheme {
            ProvideCopilotMotion(enabled) {
                Column(Modifier.fillMaxSize().background(Color.White)) {
                    OwlMenuButton(OwlColorBand.GREEN, route, headerOwlGesture(route), false, false, {})
                    NavHost(nav, "overview", modifier = Modifier.weight(1f),
                        enterTransition = { NativeMotion.routeEnter },
                        exitTransition = { NativeMotion.routeExit },
                        popEnterTransition = { NativeMotion.routeEnter },
                        popExitTransition = { NativeMotion.routeExit }) {
                        listOf("overview", "forecast", "analytics", "ai_analysis").forEachIndexed { index, name ->
                            composable(name) {
                                var draft by rememberSaveable { mutableStateOf("") }
                                val colors = listOf(Color(0xFFEDF3F7), Color(0xFFF5E4E1),
                                    Color(0xFFE2F0E6), Color(0xFFE9E5F5))
                                Column(Modifier.fillMaxSize().background(colors[index]).testTag("pane-$name")) {
                                    Button({ onAction(name) }, Modifier.testTag("action-$name")) { Text(name) }
                                    OutlinedTextField(draft, { draft = it },
                                        modifier = Modifier.testTag("draft-$name"), label = { Text("Draft") })
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun flush() {
        compose.waitForIdle()
        compose.mainClock.advanceTimeByFrame(); compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
    }

    private fun awaitCommittedPane(view: View, outgoingRoute: String, start: Long): Bitmap {
        // Manual-clock Robolectric needs actual native draw/layout commits before a raw tap.
        while (compose.mainClock.currentTime - start <= 96L) {
            val image = draw(view)
            if (compose.onAllNodesWithTag("pane-$outgoingRoute").fetchSemanticsNodes().isEmpty()) return image
            compose.mainClock.advanceTimeByFrame(); compose.waitForIdle()
        }
        throw AssertionError("route did not commit within96ms")
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
