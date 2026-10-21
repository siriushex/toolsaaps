package io.aaps.copilot.ui.foundation.screens

import android.app.Application
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.text.TextLayoutResult
import io.aaps.copilot.config.UiStyle
import io.aaps.copilot.data.repository.*
import io.aaps.copilot.ui.foundation.theme.AapsCopilotTheme
import io.aaps.copilot.ui.motion.ProvideCopilotMotion
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
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalTestApi::class)
class ServerDailyReviewArchiveTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val f = DailyReviewFixtures
    private lateinit var root: View

    private fun ready() = ServerDailyArchiveState(
        records = listOf(DailyReviewCodec.summary(f.summary(), f.now)), fetchedAtMs = f.now,
        detail = DailyReviewCodec.detail(f.detail(), f.id(), f.now), detailFetchedAtMs = f.now)

    @Test fun datedServerAuditOpensSeparatelyWithUnreviewedAndSourceLabels() {
        var state by mutableStateOf(ready())
        screen({ state }, select = { state = state.copy(selectedJobId = it) }) {
            compose.onNodeWithTag("daily_archive_row_${f.id()}").performClick()
            compose.onNodeWithTag("daily_archive_detail").assertExists()
            compose.onNodeWithTag("daily_archive_unreviewed").assertTextContains("Не проверено", substring = true)
            compose.onNodeWithTag("daily_archive_source").assertTextContains("проверки", substring = true)
            compose.onNodeWithTag("daily_archive_summary").assertTextEquals(f.text)
            compose.onNodeWithTag("clinical_report_send").assertDoesNotExist()
            assertDialogText(1f)
            raster("server-daily-ru-small")
        }
    }

    @Test fun retainedRowsRemainWhileRefreshingAndAfterFailure() {
        var state by mutableStateOf(ready())
        screen({ state }) {
            compose.runOnIdle { state = state.copy(refreshing = true, failure = ServerAiHistoryError.NETWORK) }
            compose.onNodeWithTag("daily_archive_row_${f.id()}").assertExists()
            compose.onNodeWithTag("daily_archive_updated").assertExists()
            compose.onNodeWithTag("daily_archive_refreshing").assertExists()
            compose.onNodeWithTag("daily_archive_error").assertExists()
        }
    }

    @Test fun unavailableRouteDoesNotAskForNewCredentialsButRefusedAccessDoes() {
        var state by mutableStateOf(ServerDailyArchiveState(failure = ServerAiHistoryError.NOT_FOUND))
        var settings = 0
        screen({ state }, connection = { settings++ }) {
            compose.onNodeWithTag("daily_archive_connection").assertDoesNotExist()
            compose.runOnIdle { state = state.copy(failure = ServerAiHistoryError.UNAUTHORIZED) }
            compose.onNodeWithTag("daily_archive_connection").performClick()
            assertEquals(1, settings)
        }
    }

    @Test fun sourceChangeAndLargeFontKeepPlainTextScrollable() {
        val wire = kotlinx.serialization.json.JsonObject(f.detail() + mapOf(
            "currentState" to kotlinx.serialization.json.JsonPrimitive("SOURCE_CHANGED"),
            "sourceCheck" to f.source(status = "CHANGED")))
        val summary = "Review the observations and their limitations. ".repeat(5).trim()
        val finding = "Review the recorded context and measurement quality before interpreting this observation. ".repeat(4).trim()
        assertTrue(summary.length <= 260 && finding.length <= 360)
        val state = ready().let { saved -> saved.copy(selectedJobId = f.id(),
            records = saved.records.map { it.copy(summary = summary) },
            detail = DailyReviewCodec.detail(wire, f.id(), f.now).copy(summary = summary, findings = List(5) { finding })) }
        screen({ state }, fontScale = 1.8f) {
            raster("server-daily-ru-large-top")
            compose.onNodeWithTag("daily_archive_source").performScrollTo().assertTextContains("изменились", substring = true)
            compose.onNodeWithTag("daily_archive_scope").performScrollTo().assertIsDisplayed()
            assertDialogText(1.8f)
            compose.onNodeWithText("Закрыть").assertIsDisplayed()
            raster("server-daily-ru-large")
        }
    }

    @Test fun localPaginationAndSelectionDoNotInvokeRefresh() {
        var refresh = 0
        val rows = (1..25).map { DailyReviewCodec.summary(f.summary(it), f.now) }
        screen({ ready().copy(records = rows) }, refresh = { refresh++ }) {
            compose.onNodeWithTag("daily_archive_row_${f.id(21)}").assertDoesNotExist()
            compose.onNodeWithTag("daily_archive_more").performScrollTo().performClick()
            compose.onNodeWithTag("daily_archive_row_${f.id(21)}").assertExists()
            assertEquals(0, refresh)
        }
    }

    @Test fun reportsSlotIsLazySearchableAndDoesNotReplaceThePrimaryReport() {
        screen({ ready() }, workspace = {
            AiFocusWorkspace(AiAnalysisUiState(ScreenLoadState.READY, false), {},
                report = { Text("primary retained report", Modifier.testTag("primary-retained")) },
                chat = {}, diagnostics = {}, serverArchive = { search ->
                    ServerDailyReviewArchiveContent(ready(), {}, {}, {}, {}, {}, search)
                })
        }) {
            compose.onNodeWithTag("primary-retained").assertExists()
            compose.onNodeWithTag("daily_archive").assertDoesNotExist()
            compose.onNodeWithTag("ai_focus_tab_archive").performClick()
            compose.onNodeWithTag("daily_archive_row_${f.id()}").assertExists()
            compose.onNodeWithTag("ai_focus_archive_search").performTextInput("unmatched query")
            compose.onNodeWithTag("daily_archive_row_${f.id()}").assertDoesNotExist()
            compose.onNodeWithTag("ai_focus_tab_report").performClick()
            compose.onNodeWithTag("primary-retained").assertExists()
        }
    }

    private fun screen(state: () -> ServerDailyArchiveState, fontScale: Float = 1f,
        refresh: () -> Unit = {}, select: (String) -> Unit = {}, connection: () -> Unit = {},
        workspace: (@Composable () -> Unit)? = null, block: () -> Unit) {
        val app = RuntimeEnvironment.getApplication()
        val previous = Configuration(app.resources.configuration)
        @Suppress("DEPRECATION")
        app.resources.updateConfiguration(Configuration(previous).apply {
            this.fontScale = fontScale
            setLocale(Locale.forLanguageTag("ru"))
        }, app.resources.displayMetrics)
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        try {
            val activity = controller.get()
            activity.setContent {
                AapsCopilotTheme(dynamicColor = false, uiStyle = UiStyle.CLARITY) {
                    ProvideCopilotMotion(enabled = false) {
                        if (workspace != null) workspace() else Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                            ServerDailyReviewArchiveContent(state(), refresh, select, {}, {}, connection)
                        }
                    }
                }
            }
            root = activity.window.decorView
            compose.waitForIdle()
            block()
        } finally {
            controller.pause().stop().destroy()
            @Suppress("DEPRECATION")
            app.resources.updateConfiguration(previous, app.resources.displayMetrics)
        }
    }

    private fun assertDialogText(fontScale: Float) {
        for (tag in listOf("daily_archive_date", "daily_archive_unreviewed", "daily_archive_source", "daily_archive_summary", "daily_archive_scope")) {
            val layouts = mutableListOf<TextLayoutResult>()
            compose.onNodeWithTag(tag).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            val layout = layouts.single()
            assertEquals("Dialog must render the actual system font scale", fontScale, layout.layoutInput.density.fontScale, .01f)
            assertFalse(tag, layout.multiParagraph.didExceedMaxLines)
            repeat(layout.lineCount) { line ->
                assertTrue(tag, layout.getLineLeft(line) >= -1f)
                assertTrue(tag, layout.getLineRight(line) <= layout.size.width + 1f)
            }
        }
    }

    private fun raster(name: String) {
        compose.waitForIdle()
        val view = ShadowDialog.getLatestDialog()?.takeIf { it.isShowing }?.window?.decorView ?: root
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        assertTrue(pixels.toSet().size > 32)
        val folder = File("build/reports/server-daily-raster").apply { mkdirs() }
        File(folder, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
