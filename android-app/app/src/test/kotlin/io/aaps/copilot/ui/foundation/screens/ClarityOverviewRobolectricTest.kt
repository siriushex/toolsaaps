package io.aaps.copilot.ui.foundation.screens

import android.app.Application
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Surface
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
import org.robolectric.shadows.ShadowDialog
import java.io.File
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w320dp-h640dp-port")
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ClarityOverviewRobolectricTest {
    @get:Rule val compose = createEmptyComposeRule()
    private var activity: ComponentActivity? = null
    private val origin = 1_800_000_000_000L
    private val points = List(145) { ChartPointUi(origin + it * 300_000L, 6.0 + it * 0.01) }
    private val scope = FoodDisplayScope(1, "EVIDENCE", "EVIDENCE", "session-a")
    private fun ready() = OverviewUiState(ScreenLoadState.READY, false, glucose = 7.2, delta = 0.2,
        sampleAgeMinutes = 1, syncAgeMinutes = 2, forecastsCurrent = true,
        currentIobUnits = 1.2, currentCobGrams = 22.0,
        horizons = listOf(5, 30, 60).map { HorizonPredictionUi(it, 7.5, 7.0, 8.0) },
        targetManagerLiveStatus = TargetManagerLiveStatusUi(availability = TargetManagerLiveStatusAvailabilityUi.CURRENT,
            currentTargetMmol = 5.6, proposedTargetMmol = 5.4, mode = "SHADOW"),
        acceptedFoodDisplay = AcceptedFoodDisplaySnapshot(scope, "meal-cycle", origin, points, true, true),
        foodDisplayStatus = FoodDisplayStatus.CURRENT,
        chart = ClinicalForecastChartUiState(historyPoints = listOf(ChartPointUi(origin - 300_000L, 5.8), points.first()),
            mealImpactPoints = points, mealImpactComplete = true, mealImpactGiAdjusted = true))

    @Test fun compactTargetAndPassiveDialogsNeverSendTherapy() {
        var sends = 0
        withUi { screen(ready(), { sends++ }) }.use {
            compose.onNodeWithTag("clarityObservedTarget", useUnmergedTree = true).assertTextEquals("5.6")
            capture("clarity-home-light.png")
            assertNoTextOverflow()
            compose.onNodeWithTag("clarityTargetSummary").performClick()
            compose.onNodeWithTag("clarityTargetDetails").assertIsDisplayed()
            capture("clarity-target-light.png", true)
            compose.onNodeWithText(text(R.string.action_close)).performClick()
            compose.onNodeWithTag("clarityEatingSoon").performClick()
            compose.onNodeWithTag("clarityEatingConfirm").assertIsDisplayed()
            compose.onNodeWithText(text(R.string.action_cancel)).performClick()
            assertEquals(0, sends)
        }
    }

    @Test fun explicitConfirmationSubmitsOnceAndQueueDoesNotOfferAReplay() {
        val sent = mutableListOf<String>()
        var state by mutableStateOf(ready())
        withUi { screen(state, {
            sent += it
            state = state.copy(eatingSoon = EatingSoonUiState(EatingSoonUiStatus.QUEUED, it, origin))
        }) }.use {
            compose.onNodeWithTag("clarityEatingSoon").performClick()
            assertTrue(sent.isEmpty())
            compose.onNodeWithTag("clarityEatingSubmit").performClick()
            assertEquals(1, sent.size)
            assertTrue(sent.single().matches(Regex("[A-Za-z0-9_-]{1,80}")))
            compose.onNodeWithTag("clarityEatingSoon").performClick()
            compose.onNodeWithTag("clarityEatingDetails").assertIsDisplayed()
            compose.onNodeWithTag("clarityEatingNewRequest").assertDoesNotExist()
            assertEquals(1, sent.size)
        }
    }

    @Test fun enlargedDarkRussianLayoutHasNoClippedTextAndUnknownCannotRetry() {
        var sends = 0
        withUi(dark = true, fontScale = 2f) {
            screen(ready().copy(glucose = 28.9,
                eatingSoon = EatingSoonUiState(EatingSoonUiStatus.UNKNOWN, "manual:meal:unknown:eating-soon", origin)), { sends++ })
        }.use {
            capture("clarity-home-dark-large.png")
            assertNoTextOverflow()
            compose.onNodeWithTag("clarityEatingSoon").performClick()
            compose.onNodeWithTag("clarityEatingNewRequest").assertDoesNotExist()
            assertNoTextOverflow()
            capture("clarity-eating-dark-large.png", true)
            assertEquals(0, sends)
            compose.onNodeWithText(text(R.string.action_close)).performClick()
            compose.onNodeWithTag("clarityContent").performScrollToNode(hasTestTag("clarityTargetSummary"))
            compose.onNodeWithTag("clarityTargetSummary").performClick()
            val label = text(R.string.overview_target_manager_current_target)
            compose.onNodeWithText(label, useUnmergedTree = true).performScrollTo().assertIsDisplayed()
            val layouts = mutableListOf<TextLayoutResult>()
            compose.onNodeWithText(label, useUnmergedTree = true)
                .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            assertTrue(layouts.single().getLineEnd(0) >= label.indexOf(' '))
            assertNoTextOverflow()
            capture("clarity-target-dark-large.png", true)
        }
    }

    @Test fun fullFoodOnlyCurveReachesItsTwelveHourEndpointWithoutTherapy() {
        var sends = 0
        withUi { screen(ready(), { sends++ }) }.use {
            compose.onNodeWithTag("clarityContent").performScrollToNode(hasTestTag("clarityFoodOpen"))
            compose.onNodeWithTag("clarityFoodOpen").performClick()
            compose.onNodeWithTag("foodCurveEnd").performScrollTo().performClick()
            val endDescription = compose.onNodeWithTag("foodFullCurve")
                .fetchSemanticsNode().config[SemanticsProperties.StateDescription]
            assertTrue(endDescription.contains(io.aaps.copilot.ui.foundation.format.UiFormatters.formatTimestamp(points.last().ts)))
            capture("clarity-food-end-light.png", true)
            compose.onNodeWithTag("foodCurveFit").performScrollTo().performClick()
            val fullDescription = compose.onNodeWithTag("foodFullCurve")
                .fetchSemanticsNode().config[SemanticsProperties.StateDescription]
            capture("clarity-food-full-light.png", true)
            assertTrue(fullDescription, fullDescription.contains(io.aaps.copilot.ui.foundation.format.UiFormatters.formatTimestamp(origin)))
            assertTrue(fullDescription, fullDescription.contains(io.aaps.copilot.ui.foundation.format.UiFormatters.formatTimestamp(points.last().ts)))
            assertEquals(0, sends)
        }
    }

    @Test fun staleForecastAndMissingObservedTargetNeverShowProposedAsActual() {
        withUi { screen(ready().copy(forecastsCurrent = false,
            targetManagerLiveStatus = ready().targetManagerLiveStatus.copy(currentTargetMmol = null)), {}) }.use {
            compose.onNodeWithTag("clarityObservedTarget", useUnmergedTree = true).assertTextEquals("--")
            compose.onNodeWithTag("clarityHorizon30").performClick()
            compose.onNodeWithText(text(R.string.clarity_forecast_unavailable)).assertIsDisplayed()
        }
    }

    @Composable private fun screen(state: OverviewUiState, onEating: (String) -> Unit) {
        OverviewScreen(state, onRunCycleNow = {}, onSetKillSwitch = {}, onDisablePowerSave = {},
            onAddBloodCheck = { _, _, _, _ -> }, onOpenClinicalReport = {}, onEatingSoon = onEating)
    }

    private fun text(id: Int) = RuntimeEnvironment.getApplication().getString(id)
    private fun withUi(dark: Boolean = false, fontScale: Float = 1f, content: @Composable () -> Unit): AutoCloseable {
        val app = RuntimeEnvironment.getApplication()
        val old = Configuration(app.resources.configuration)
        @Suppress("DEPRECATION")
        app.resources.updateConfiguration(Configuration(old).apply {
            this.fontScale = fontScale; setLocale(Locale.forLanguageTag("ru"))
        }, app.resources.displayMetrics)
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        activity = controller.get()
        controller.get().setContent {
            AapsCopilotTheme(darkTheme = dark, dynamicColor = false, uiStyle = UiStyle.CLARITY) {
                Surface(Modifier.fillMaxSize()) {
                    androidx.compose.foundation.layout.Box(Modifier.fillMaxSize().padding(16.dp)) { content() }
                }
            }
        }
        return AutoCloseable {
            controller.pause().stop().destroy()
            @Suppress("DEPRECATION")
            app.resources.updateConfiguration(old, app.resources.displayMetrics)
        }
    }

    private fun assertNoTextOverflow() {
        val nodes = compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult), useUnmergedTree = true)
        repeat(nodes.fetchSemanticsNodes().size) { index ->
            val layouts = mutableListOf<TextLayoutResult>()
            nodes[index].performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            layouts.forEach { layout ->
                // Paragraph width can be the loose parent constraint rather than rendered text width.
                assertFalse(layout.layoutInput.text.text, layout.multiParagraph.didExceedMaxLines)
                assertTrue(layout.layoutInput.text.text, layout.multiParagraph.height <= layout.size.height + 1f)
                repeat(layout.lineCount) { line ->
                    assertTrue(layout.layoutInput.text.text, layout.getLineLeft(line) >= -1f)
                    assertTrue(layout.layoutInput.text.text, layout.getLineRight(line) <= layout.size.width + 1f)
                }
            }
        }
    }

    private fun capture(name: String, dialog: Boolean = false) {
        val directory = System.getenv("CLARITY_UI_EVIDENCE_DIR") ?: return
        compose.runOnIdle {
            val view = if (dialog) checkNotNull(ShadowDialog.getLatestDialog().window).decorView else checkNotNull(activity).window.decorView
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            File(directory, name).outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        }
    }
}
