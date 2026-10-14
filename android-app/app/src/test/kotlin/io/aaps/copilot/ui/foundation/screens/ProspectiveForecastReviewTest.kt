package io.aaps.copilot.ui.foundation.screens

import android.app.Application
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.text.TextLayoutResult
import io.aaps.copilot.config.UiStyle
import io.aaps.copilot.data.repository.MealForecastJournalReview
import io.aaps.copilot.ui.foundation.theme.AapsCopilotTheme
import java.io.File
import java.util.Locale
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
import org.robolectric.shadows.ShadowDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w320dp-h640dp-port")
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ProspectiveForecastReviewTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun missingAndOverflowReportsNeverInventZeroAccuracy() {
        assertEquals(ProspectiveReviewStatus.UNAVAILABLE, MealForecastJournalReview(0, emptyMap(), null).toReviewUi().status)
        val overflow = MealForecastJournalReview(7_001, emptyMap(), null, true).toReviewUi()
        assertEquals(ProspectiveReviewStatus.OVERFLOW, overflow.status)
        assertTrue(overflow.rows.isEmpty())
    }

    @Test fun dialogShowsSupportAndRefreshCloseAreExplicitPassiveCommands() {
        var refresh = 0; var close = 0
        val state = ProspectiveForecastReviewUi(ProspectiveReviewStatus.READY, 10, 2,
            rows = listOf(5, 30, 60).map { ProspectiveForecastRowUi(1, it, 4, 2, 0.4, 1, 2, 3, 2) })
        show(state, refresh = { refresh++ }, close = { close++ }).use {
            compose.onNodeWithText("5 мин", substring = true).assertExists()
            compose.onNodeWithText("30 мин", substring = true).assertExists()
            compose.onNodeWithText("60 мин", substring = true).assertExists()
            assertNoTextOverflow()
            capture("forecast-review-320.png")
            compose.onNodeWithContentDescription("Обновить проверку").performClick()
            compose.onNodeWithText("Закрыть").assertIsDisplayed().performClick()
            assertEquals(1, refresh); assertEquals(1, close)
        }
    }

    @Test fun largeFontShowsUnknownSensorAndCloseRemainsReachable() {
        show(ProspectiveForecastReviewUi(ProspectiveReviewStatus.EMPTY, 3, 3, 3), fontScale = 1.8f).use {
            compose.onNodeWithText("Неизвестна сессия сенсора", substring = true).assertExists()
            compose.onNodeWithText("Закрыть").assertIsDisplayed()
            assertNoTextOverflow()
            capture("forecast-review-large.png")
        }
    }

    private fun show(state: ProspectiveForecastReviewUi, fontScale: Float = 1f,
        refresh: () -> Unit = {}, close: () -> Unit = {}): AutoCloseable {
        val app = RuntimeEnvironment.getApplication()
        val previous = Configuration(app.resources.configuration)
        @Suppress("DEPRECATION")
        app.resources.updateConfiguration(Configuration(previous).apply {
            this.fontScale = fontScale; setLocale(Locale.forLanguageTag("ru"))
        }, app.resources.displayMetrics)
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        activity.get().setContent {
            AapsCopilotTheme(dynamicColor = false, uiStyle = UiStyle.CLARITY) {
                ProspectiveForecastReviewDialog(state, false, refresh, close)
            }
        }
        return AutoCloseable {
            activity.pause().stop().destroy()
            @Suppress("DEPRECATION")
            app.resources.updateConfiguration(previous, app.resources.displayMetrics)
        }
    }

    private fun assertNoTextOverflow() {
        val nodes = compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult), useUnmergedTree = true)
        repeat(nodes.fetchSemanticsNodes().size) { index ->
            val layouts = mutableListOf<TextLayoutResult>()
            nodes[index].performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            layouts.forEach { layout ->
                assertFalse(layout.layoutInput.text.text, layout.multiParagraph.didExceedMaxLines)
                repeat(layout.lineCount) { line ->
                    assertTrue(layout.layoutInput.text.text, layout.getLineLeft(line) >= -1f)
                    assertTrue(layout.layoutInput.text.text, layout.getLineRight(line) <= layout.size.width + 1f)
                }
            }
        }
    }

    private fun capture(name: String) {
        val directory = System.getenv("CLARITY_UI_EVIDENCE_DIR") ?: return
        compose.runOnIdle {
            val view = checkNotNull(ShadowDialog.getLatestDialog().window).decorView
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            File(directory, name).outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        }
    }
}
