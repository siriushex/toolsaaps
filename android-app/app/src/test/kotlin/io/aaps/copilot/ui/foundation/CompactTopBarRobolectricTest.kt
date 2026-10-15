package io.aaps.copilot.ui.foundation

import android.app.Application
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.Surface
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.text.TextLayoutResult
import io.aaps.copilot.R
import io.aaps.copilot.config.UiStyle
import io.aaps.copilot.ui.foundation.theme.AapsCopilotTheme
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
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w320dp-h640dp-port")
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CompactTopBarRobolectricTest {
    @get:Rule val compose = createEmptyComposeRule()
    @Test fun ordinaryHeaderPreservesActionsAndDisabledRefresh() = verifyHeader(1f, false)
    @Test fun enlargedDarkHeaderPreservesActionsAndDisabledRefresh() = verifyHeader(2f, true)
    @Test fun approvedOwlOwnsTheFixedMenuTarget() = verifyHeader(1f, false, approvedOwl = true)

    private fun verifyHeader(fontScale: Float, dark: Boolean, approvedOwl: Boolean = false) {
        val app = RuntimeEnvironment.getApplication()
        val old = Configuration(app.resources.configuration)
        @Suppress("DEPRECATION")
        app.resources.updateConfiguration(Configuration(old).apply {
            this.fontScale = fontScale; setLocale(Locale.forLanguageTag("ru"))
        }, app.resources.displayMetrics)
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        var canRun by mutableStateOf(false)
        var cycles = 0; var blood = 0; var events = 0; var history = 0; var menu = 0
        try {
            controller.get().setContent {
                AapsCopilotTheme(darkTheme = dark, dynamicColor = false, uiStyle = UiStyle.CLARITY) {
                    Surface(Modifier.fillMaxSize()) {
                        androidx.compose.foundation.layout.Column {
                            TopBar("Главная", UiStyle.CLARITY, false, false, true, true, 0L,
                                true, canRun, { cycles++ }, { blood++ }, { menu++ },
                                {}, {}, {}, { history++ }, { events++ }, 2, {})
                        }
                    }
                }
            }
            compose.onNodeWithTag("overviewTopRefresh").assertIsDisplayed().assertIsNotEnabled().performClick()
            if (approvedOwl) {
                val owlMenu = compose.onNodeWithTag("owlMenuAction").assertIsDisplayed()
                val bounds = owlMenu.fetchSemanticsNode().boundsInRoot
                assertTrue("fixed menu target: $bounds", bounds.width >= 48f && bounds.height >= 48f)
                compose.onNodeWithTag("copilotBrand").assertDoesNotExist()
            }
            assertEquals(0, cycles)
            compose.runOnIdle { canRun = true }
            compose.onNodeWithTag("overviewTopRefresh").assertIsEnabled().performClick()
            compose.onNodeWithTag("overviewTopBloodCheck").performClick()
            compose.onNodeWithTag("eventsNotebookAction").performClick()
            assertEquals(1, cycles); assertEquals(1, blood); assertEquals(1, events)
            val bloodBounds = compose.onNodeWithTag("overviewTopBloodCheck").fetchSemanticsNode().boundsInRoot
            val refreshBounds = compose.onNodeWithTag("overviewTopRefresh").fetchSemanticsNode().boundsInRoot
            val badgeBounds = compose.onNodeWithTag("eventsActiveBadge").getUnclippedBoundsInRoot()
            assertTrue("event badge must remain below the top edge: $badgeBounds", badgeBounds.top.value >= -0.5f)
            assertTrue(refreshBounds.right <= bloodBounds.left + 0.5f)
            assertTrue("48dp blood control, actual $bloodBounds", bloodBounds.width >= 48f && bloodBounds.height >= 48f)
            val layouts = compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult), useUnmergedTree = true)
            repeat(layouts.fetchSemanticsNodes().size) { i ->
                val results = mutableListOf<TextLayoutResult>()
                layouts[i].performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
                results.forEach { result ->
                    assertFalse(result.layoutInput.text.text, result.multiParagraph.didExceedMaxLines)
                    assertTrue(result.layoutInput.text.text, result.multiParagraph.height <= result.size.height + 1f)
                    repeat(result.lineCount) { line ->
                        assertTrue(result.layoutInput.text.text, result.getLineLeft(line) >= -1f)
                        assertTrue(result.layoutInput.text.text, result.getLineRight(line) <= result.size.width + 1f)
                    }
                }
            }
            System.getenv("CLARITY_UI_EVIDENCE_DIR")?.let { dir ->
                compose.runOnIdle {
                    val view = controller.get().window.decorView
                    val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
                    view.draw(Canvas(bitmap))
                    File(dir, "compact-header-$fontScale.png").outputStream().use {
                        assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
                    }
                }
            }
            compose.onNodeWithContentDescription(app.getString(R.string.menu_open)).performClick()
            assertEquals(1, menu)
            compose.onNodeWithTag("glucoseAlertBellAction").performClick()
            compose.onNodeWithTag("glucoseAlertHistoryAction").performClick()
            assertEquals(1, history)
        } finally {
            controller.pause().stop().destroy()
            @Suppress("DEPRECATION")
            app.resources.updateConfiguration(old, app.resources.displayMetrics)
        }
    }
}
