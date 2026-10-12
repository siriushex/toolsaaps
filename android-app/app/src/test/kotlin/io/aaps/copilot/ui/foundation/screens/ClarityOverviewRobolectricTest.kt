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
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material.icons.filled.Info
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
        currentIsfMmolPerUnit = 2.4, currentCrGramsPerUnit = 10.0, calculatedUamCarbsGrams = 8.0,
        isfRuntime = MetricRuntimeSourceUi(availability = MetricRuntimeAvailabilityUi.AVAILABLE, actualResolvedSource = "AAPS"),
        crRuntime = MetricRuntimeSourceUi(availability = MetricRuntimeAvailabilityUi.AVAILABLE, actualResolvedSource = "AAPS"),
        horizons = listOf(5, 30, 60).map { HorizonPredictionUi(it, 7.5, 7.0, 8.0) },
        targetManagerLiveStatus = TargetManagerLiveStatusUi(availability = TargetManagerLiveStatusAvailabilityUi.CURRENT,
            currentTargetMmol = 5.6, proposedTargetMmol = 5.4, mode = "SHADOW"),
        acceptedFoodDisplay = AcceptedFoodDisplaySnapshot(scope, "meal-cycle", origin, points, true, true),
        foodDisplayStatus = FoodDisplayStatus.CURRENT,
        chart = ClinicalForecastChartUiState(historyPoints = List(37) { ChartPointUi(origin - (36 - it) * 300_000L,
            7.2 + kotlin.math.sin(it * Math.PI / 18.0) * 0.8) },
            futurePath = listOf(ChartPointUi(origin, 7.2)) + listOf(5, 30, 60).map { ChartPointUi(origin + it * 60_000L, 7.5) },
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

    @Test @Config(qualifiers = "w390dp-h844dp-port")
    fun balanceExposesSixMetricsBeforeCompactChart() {
        withUi { screen(ready().copy(currentIsfMmolPerUnit = 2.4,
            currentCrGramsPerUnit = 10.0, calculatedUamCarbsGrams = 8.0), {}) }.use {
            listOf("clarityIob", "clarityCob", "clarityIsf", "clarityCr", "clarityUam", "clarityBaseTarget")
                .forEach { compose.onNodeWithTag(it).assertIsDisplayed() }
            compose.onNodeWithTag("clarityHorizon30").assertDoesNotExist()
            val metrics = compose.onNodeWithTag("clarityMetrics").fetchSemanticsNode().boundsInRoot
            val chart = compose.onNodeWithTag("clarityGlucoseChart").fetchSemanticsNode().boundsInRoot
            assertTrue(metrics.toString(), metrics.bottom <= chart.top)
            assertNoTextOverflow()
            capture("balance-home-light.png")
        }
    }

    @Test fun cobOpensFullFoodAndPlusKeepsExistingMealEntryWithoutSending() {
        var changes = 0
        withUi { screen(ready(), { changes++ }, { changes++ }) }.use {
            compose.onNodeWithTag("clarityCob").performClick()
            compose.onNodeWithTag("clarityFoodDetails").assertIsDisplayed()
            compose.onNodeWithTag("clarityFoodAddMeal").performClick()
            compose.onNodeWithTag("clarityFoodDetails").assertDoesNotExist()
            compose.onNodeWithText(text(R.string.meal_entry_title)).assertIsDisplayed()
            compose.onNodeWithTag("mealManualCarbs").performScrollTo().assertIsDisplayed()
            compose.onNodeWithTag("mealGlycemicIndex").assertDoesNotExist()
            compose.onNodeWithTag("mealEntryClose").performClick()
            compose.onNodeWithTag("clarityAddMeal").performClick()
            compose.onNodeWithText(text(R.string.meal_entry_title)).assertIsDisplayed()
            compose.onNodeWithTag("clarityFoodDetails").assertDoesNotExist()
            compose.onNodeWithTag("mealEntryClose").performClick()
            assertEquals(0, changes)
        }
    }

    @Test fun foodFormKeepsEatingSoonAndManualGramsWithoutManualGi() {
        var changes = 0
        withUi { screen(ready(), { changes++ }, { changes++ }) }.use {
            compose.onNodeWithTag("clarityAddMeal").performClick()
            compose.onNodeWithTag("mealGlycemicIndex").assertDoesNotExist()
            compose.onNodeWithTag("overviewEatingSoon").performScrollTo().assertIsOn()
            compose.onNodeWithTag("mealManualCarbs").performScrollTo().assertIsDisplayed()
            compose.onNodeWithTag("overviewPrepareMeal").assertIsDisplayed()
            assertNoTextOverflow()
            capture("compact-food-form-light.png", true)
            compose.onNodeWithTag("mealEntryClose").performClick()
            assertEquals(0, changes)
        }
    }

    @Test fun ordinaryCompactOverviewNeedsNoContentScroll() {
        withUi { screen(ready(), {}) }.use {
            compose.waitForIdle()
            val axis = compose.onNodeWithTag("clarityContent").fetchSemanticsNode()
                .config[SemanticsProperties.VerticalScrollAxisRange]
            assertEquals("ordinary overview must fit", 0f, axis.maxValue(), 1f)
            assertNoTextOverflow()
            capture("compact-main-320-light.png")
        }
    }

    @Test fun otherPrimaryMetricsRetainExistingMenusWithoutChangesOnCancel() {
        var changes = 0
        withUi { screen(ready(), { changes++ }, { changes++ }) }.use {
            val routes = listOf(
                Triple("clarityIob", R.string.overview_iob_dialog_title, R.string.action_cancel),
                Triple("clarityIsf", R.string.overview_isf_source_title, R.string.action_cancel),
                Triple("clarityCr", R.string.overview_cr_source_title, R.string.action_cancel),
                Triple("clarityUam", R.string.overview_uam_dialog_title, R.string.overview_uam_dialog_close)
            )
            for ((tag, title, close) in routes) {
                compose.onNodeWithTag("clarityContent").performScrollToNode(hasTestTag(tag))
                compose.onNodeWithTag(tag).performClick()
                if (tag == "clarityUam") {
                    compose.onNodeWithTag("uamExportModeDialog").assertIsDisplayed()
                } else compose.onNodeWithText(text(title)).assertIsDisplayed()
                compose.onNodeWithText(text(close)).performClick()
                assertEquals(0, changes)
            }
            compose.onNodeWithTag("clarityContent").performScrollToNode(hasTestTag("clarityBaseTarget"))
            compose.onNodeWithTag("clarityBaseTarget").performClick()
            compose.onNodeWithTag("baseTargetScheduleDialog").assertIsDisplayed()
            compose.onNodeWithTag("baseTargetScheduleCancel").performClick()
            assertEquals(0, changes)
        }
    }

    @Test fun currentForecastMarkersOpenPassiveDetailsAndExpansion() {
        var sends = 0
        val future = listOf(5, 30, 60).map { ChartPointUi(origin + it * 60_000L, 7.5) }
        withUi { screen(ready().copy(chart = ready().chart.copy(futurePath = future)), { sends++ }) }.use {
            compose.onNodeWithTag("clarityContent").performScrollToNode(hasTestTag("forecastMarker30"))
            compose.onNodeWithTag("forecastMarker30").assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
            compose.onNodeWithTag("forecastMarker30").performClick()
            compose.onNodeWithTag("clarityForecastDetails").assertIsDisplayed()
            compose.onNodeWithText(text(R.string.action_close)).performClick()
            compose.onNodeWithTag("clarityChartExpand").performClick()
            compose.onNodeWithTag("clarityChartDetails").assertIsDisplayed()
            assertNoTextOverflow()
            capture("balance-expanded-light.png", true)
            assertEquals(0, sends)
        }
    }

    @Test fun enlargedRussianMetricsAndMarkersRemainReachableWithoutClipping() {
        withUi(dark = true, fontScale = 2f) { screen(ready(), {}) }.use {
            listOf("clarityIob", "clarityCob", "clarityIsf", "clarityCr", "clarityUam", "clarityBaseTarget").forEach {
                compose.onNodeWithTag("clarityContent").performScrollToNode(hasTestTag(it))
                compose.onNodeWithTag(it).assertIsDisplayed()
                assertNoTextOverflow()
            }
            capture("balance-metrics-dark-large.png")
            compose.onNodeWithTag("clarityContent").performScrollToNode(hasTestTag("forecastMarker30"))
            compose.onNodeWithTag("forecastMarker30").assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
            assertNoTextOverflow()
            capture("balance-chart-dark-large.png")
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

    @Test fun fullFoodOnlyCurveShowsTwelveHoursByDefaultWithoutZoomButtonsOrTherapy() {
        var sends = 0
        withUi { screen(ready(), { sends++ }) }.use {
            compose.onNodeWithTag("clarityCob").performClick()
            compose.onNodeWithTag("foodCurveStart").assertDoesNotExist()
            compose.onNodeWithTag("foodCurveEnd").assertDoesNotExist()
            compose.onNodeWithTag("foodCurveFit").assertDoesNotExist()
            val fullDescription = compose.onNodeWithTag("foodFullCurve")
                .fetchSemanticsNode().config[SemanticsProperties.StateDescription]
            capture("compact-food-full-light.png", true)
            assertTrue(fullDescription, fullDescription.contains(io.aaps.copilot.ui.foundation.format.UiFormatters.formatTimestamp(origin)))
            assertTrue(fullDescription, fullDescription.contains(io.aaps.copilot.ui.foundation.format.UiFormatters.formatTimestamp(points.last().ts)))
            assertNoTextOverflow()
            assertEquals(0, sends)
        }
    }

    @Test fun enlargedFoodKeepsUnknownEndAndExistingEatingStatusWithoutRetry() {
        var sends = 0
        withUi(dark = true, fontScale = 2f) {
            screen(ready().copy(foodDisplayStatus = FoodDisplayStatus.OUTDATED,
                eatingSoon = EatingSoonUiState(status = EatingSoonUiStatus.UNKNOWN)), { sends++ })
        }.use {
            compose.onNodeWithTag("clarityContent").performScrollToNode(hasTestTag("clarityCob"))
            compose.onNodeWithTag("clarityCob").performClick()
            compose.onNodeWithText(text(R.string.clarity_food_unknown)).performScrollTo().assertIsDisplayed()
            assertNoTextOverflow()
            capture("compact-food-dark-large.png", true)
            compose.onNodeWithTag("clarityFoodEatingSoon").performClick()
            compose.onNodeWithTag("clarityFoodDetails").assertDoesNotExist()
            compose.onNodeWithTag("clarityEatingDetails").assertIsDisplayed()
            compose.onNodeWithTag("clarityEatingNewRequest").assertDoesNotExist()
            assertEquals(0, sends)
        }
    }

    @Test fun latestCobDoesNotRewriteAcceptedFoodOriginOrFullPeriod() {
        var state by mutableStateOf(ready())
        withUi { screen(state, {}) }.use {
            compose.onNodeWithTag("clarityCob").performClick()
            val before = compose.onNodeWithTag("foodFullCurve").fetchSemanticsNode().config[SemanticsProperties.StateDescription]
            compose.runOnIdle { state = state.copy(currentCobGrams = 18.0, foodDisplayStatus = FoodDisplayStatus.UPDATING) }
            val after = compose.onNodeWithTag("foodFullCurve").fetchSemanticsNode().config[SemanticsProperties.StateDescription]
            assertEquals(before, after)
            compose.onNodeWithTag("clarityFoodCob").assertTextContains("18.0", substring = true)
        }
    }

    @Test fun staleForecastAndMissingObservedTargetNeverShowProposedAsActual() {
        withUi { screen(ready().copy(forecastsCurrent = false,
            targetManagerLiveStatus = ready().targetManagerLiveStatus.copy(currentTargetMmol = null)), {}) }.use {
            compose.onNodeWithTag("clarityObservedTarget", useUnmergedTree = true).assertTextEquals("--")
            compose.onNodeWithTag("forecastMarker30").assertDoesNotExist()
            compose.onNodeWithTag("clarityContent").performScrollToNode(hasTestTag("clarityForecastInfo"))
            compose.onNodeWithTag("clarityForecastInfo").performClick()
            compose.onNodeWithText(text(R.string.clarity_forecast_unavailable)).assertIsDisplayed()
        }
    }

    @Test fun ordinaryCompactOverviewFitsWithNativeTopAndBottomChrome() {
        withUi(chrome = true) { screen(ready(), {}) }.use {
            val axis = compose.onNodeWithTag("clarityContent").fetchSemanticsNode()
                .config[SemanticsProperties.VerticalScrollAxisRange]
            capture("compact-main-with-chrome-320.png")
            assertEquals("normal main with root chrome must fit", 0f, axis.maxValue(), 1f)
            compose.onNodeWithTag("overviewTopBloodCheck").assertIsDisplayed()
            compose.onNodeWithTag("clarityBaseTarget").assertIsDisplayed()
            assertNoTextOverflow()
            capture("compact-main-with-chrome-320.png")
        }
    }

    @Test fun pumpIndicatorKeepsPassiveHelpAndAttentionMessageStaysVisible() {
        var changes = 0
        withUi { screen(ready(), {}, { changes++ }) }.use {
            compose.onNodeWithTag("pump_link_indicator").performClick()
            compose.onNodeWithTag("pump_link_status").assertDoesNotExist()
            assertEquals(0, changes)
        }
        withUi {
            screen(ready().copy(pumpLinkStatus = io.aaps.copilot.data.repository.PumpLinkUiStatus(
                io.aaps.copilot.domain.pump.PumpLinkCondition.BLUETOOTH_OFF, episodeOpen = true)),
                {}, { changes++ })
        }.use {
            compose.onNodeWithTag("clarityContent").performScrollToNode(hasTestTag("pump_link_status"))
            compose.onNodeWithTag("pump_link_status").assertIsDisplayed()
            assertNoTextOverflow()
            assertEquals(0, changes)
        }
    }

    @Test
    @Config(qualifiers = "w360dp-h800dp-port")
    fun largerPhoneKeepsNativeMainAndFullFoodReadable() {
        withUi(chrome = true) { screen(ready(), {}) }.use {
            val axis = compose.onNodeWithTag("clarityContent").fetchSemanticsNode()
                .config[SemanticsProperties.VerticalScrollAxisRange]
            assertEquals(0f, axis.maxValue(), 1f)
            capture("compact-main-with-chrome-360.png")
            compose.onNodeWithTag("clarityCob").performClick()
            compose.onNodeWithTag("foodFullCurve").assertIsDisplayed()
            assertNoTextOverflow()
            capture("compact-food-360.png", true)
        }
    }

    @Test fun sourceErrorStaysVisibleAndGlucoseOpensExistingReportPassively() {
        var reports = 0; var changes = 0
        withUi { screen(ready().copy(sensitivitySourceApplyError = "Source not confirmed"),
            {}, { changes++ }, { reports++ }) }.use {
            compose.onNodeWithTag("overviewSensitivitySourceStatus").assertIsDisplayed()
            compose.onNodeWithTag("clarityClinicalReport").performClick()
            assertEquals(1, reports)
            assertEquals(0, changes)
        }
    }

    @Composable private fun screen(state: OverviewUiState, onEating: (String) -> Unit,
        onChange: () -> Unit = {}, onClinical: () -> Unit = {}) {
        OverviewScreen(state, onRunCycleNow = onChange, onSetKillSwitch = { onChange() }, onDisablePowerSave = onChange,
            onAddBloodCheck = { _, _, _, _ -> onChange() }, onOpenClinicalReport = onClinical, onEatingSoon = onEating,
            onManualCarbs = { _, _, _, _, _, _, _, _ -> onChange() },
            onOpenAapsBolus = onChange, onBaseTargetScheduleSave = { onChange() },
            onUamExportUiModeChange = { onChange() }, onIsfRuntimeSourceChange = { onChange() },
            onCrRuntimeSourceChange = { onChange() })
    }

    private fun text(id: Int) = RuntimeEnvironment.getApplication().getString(id)
    private fun withUi(dark: Boolean = false, fontScale: Float = 1f, chrome: Boolean = false,
        content: @Composable () -> Unit): AutoCloseable {
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
                    if (chrome) {
                        androidx.compose.material3.Scaffold(
                            topBar = { io.aaps.copilot.ui.foundation.TopBar("Главная", UiStyle.CLARITY,
                                false, false, true, true, 0L, true, true, {}, {}, {}, {}, {}, {}, {}, {}, 0, {}) },
                            bottomBar = {
                                androidx.compose.material3.NavigationBar {
                                    listOf("Главная", "Прогноз", "Анализ").forEachIndexed { index, label ->
                                        NavigationBarItem(index == 0, {},
                                            icon = { androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Default.Info, null) },
                                            label = { androidx.compose.material3.Text(label) })
                                    }
                                }
                            }
                        ) { padding ->
                            androidx.compose.foundation.layout.Box(Modifier.fillMaxSize().padding(padding)
                                .padding(horizontal = 16.dp, vertical = 4.dp)) { content() }
                        }
                    } else androidx.compose.foundation.layout.Box(Modifier.fillMaxSize().padding(16.dp)) { content() }
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
