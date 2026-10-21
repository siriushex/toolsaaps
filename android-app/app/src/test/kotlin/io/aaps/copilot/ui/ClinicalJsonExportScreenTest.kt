package io.aaps.copilot.ui

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
import io.aaps.copilot.historyexport.ClinicalJsonPeriod
import io.aaps.copilot.ui.foundation.screens.*
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
@Config(sdk = [35], application = Application::class, qualifiers = "ru-w320dp-h640dp-port")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class ClinicalJsonExportScreenTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun allFivePeriodsRequireExplicitSaveAndDefaultIsThirtyDays() {
        val chosen = mutableListOf<ClinicalJsonPeriod>()
        screen(onExport = { chosen += it }).use {
            assertTrue(chosen.isEmpty())
            compose.onNodeWithTag("jsonReport").performClick()
            compose.onNodeWithTag("jsonPeriodDAYS_30").assertIsSelected()
            capture("periods-320.png")
            compose.onNodeWithText("Отмена").performClick()
            assertTrue(chosen.isEmpty())
            for (period in ClinicalJsonPeriod.entries) {
                compose.onNodeWithTag("jsonReport").performClick()
                compose.onNodeWithTag("jsonPeriod${period.name}").performScrollTo().performClick().assertIsSelected()
                assertNoTextOverflow()
                compose.onNodeWithTag("jsonReportSave").assertIsDisplayed().performClick()
            }
            assertEquals(ClinicalJsonPeriod.entries, chosen)
        }
    }

    @Test fun fullAndSaveRemainReachableWithDoubleFont() {
        var chosen: ClinicalJsonPeriod? = null
        screen(fontScale = 2f, onExport = { chosen = it }).use {
            compose.onNodeWithTag("jsonReport").performClick()
            compose.onNodeWithTag("jsonPeriodFULL").performScrollTo().performClick()
            compose.onNodeWithTag("jsonReportSave").assertIsDisplayed()
            assertNoTextOverflow(); capture("periods-320-large.png")
            compose.onNodeWithTag("jsonReportSave").performClick()
            assertEquals(ClinicalJsonPeriod.FULL, chosen)
        }
    }

    @Test fun runningPreventsDuplicateExportAndOffersCancellation() {
        var cancelled = 0
        screen(JsonExportState(JsonExportPhase.RUNNING, rows = 2000), onCancel = { cancelled++ }).use {
            compose.onNodeWithTag("jsonReport").assertIsNotEnabled()
            compose.onNodeWithTag("jsonReportProgress").assertIsDisplayed()
            compose.onNodeWithText("Отмена").performClick()
            assertEquals(1, cancelled)
        }
    }

    @Test fun incompleteDocumentIsNotPresentedAsSuccess() {
        screen(JsonExportState(JsonExportPhase.CLEANUP_FAILED), fontScale = 2f).use {
            compose.onNodeWithText("Удалите неполный файл", substring = true).assertIsDisplayed()
            compose.onNodeWithText("JSON сохранён", substring = true).assertDoesNotExist()
            assertNoTextOverflow()
        }
    }

    private fun screen(state: JsonExportState = JsonExportState(), fontScale: Float = 1f,
        onExport: (ClinicalJsonPeriod) -> Unit = { fail("Unexpected export") }, onCancel: () -> Unit = {}): AutoCloseable {
        val app = RuntimeEnvironment.getApplication()
        val previous = Configuration(app.resources.configuration)
        @Suppress("DEPRECATION")
        app.resources.updateConfiguration(Configuration(previous).apply {
            this.fontScale = fontScale; setLocale(Locale.forLanguageTag("ru"))
        }, app.resources.displayMetrics)
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        activity.get().setContent { AapsCopilotTheme(dynamicColor = false) {
            ClinicalJsonExportContent(state, onExport = onExport, onCancel = onCancel)
        } }
        return AutoCloseable {
            activity.pause().stop().destroy()
            @Suppress("DEPRECATION")
            app.resources.updateConfiguration(previous, app.resources.displayMetrics)
        }
    }

    private fun assertNoTextOverflow() {
        val nodes = compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult), useUnmergedTree = true)
        repeat(nodes.fetchSemanticsNodes().size) { index ->
            val results = mutableListOf<TextLayoutResult>()
            nodes[index].performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
            results.forEach { result ->
                assertFalse(result.layoutInput.text.text, result.multiParagraph.didExceedMaxLines)
                repeat(result.lineCount) { line ->
                    assertTrue(result.layoutInput.text.text, result.getLineLeft(line) >= -1f)
                    assertTrue(result.layoutInput.text.text, result.getLineRight(line) <= result.size.width + 1f)
                }
            }
        }
    }

    private fun capture(name: String) = compose.runOnIdle {
        val view = checkNotNull(ShadowDialog.getLatestDialog().window).decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        try {
            view.draw(Canvas(bitmap))
            File("build/reports/clinical-json-native", name).apply { parentFile!!.mkdirs() }.outputStream().use {
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
        } finally { bitmap.recycle() }
    }
}
