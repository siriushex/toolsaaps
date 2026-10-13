package io.aaps.copilot.ui.foundation.components

import android.app.Application
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.text.TextLayoutResult
import io.aaps.copilot.R
import io.aaps.copilot.config.UiStyle
import io.aaps.copilot.data.repository.EventTimelineRepository
import io.aaps.copilot.domain.events.*
import io.aaps.copilot.domain.model.TherapyEvent
import io.aaps.copilot.ui.foundation.screens.ChartPointUi
import io.aaps.copilot.ui.foundation.screens.ClinicalForecastChartUiState
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
class ChartEventDetailsDialogRobolectricTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val origin = 1_800_000_000_000L
    private val meal = CompensationEvent("meal-1", origin, origin + 180 * 60_000,
        CompensationEventType.MEAL, source = EventSource.AAPS, title = "meal_bolus",
        attributes = mapOf("displayCarbsG" to "24", "displayInsulinU" to "1.4", "profile" to "MIXED"),
        note = "Lunch", provenance = "therapy_events")
    private val blood = CompensationEvent("blood-1", origin + 60_000, origin + 6 * 3_600_000,
        CompensationEventType.SENSOR_CALIBRATION, subtype = "MANUAL_BLOOD_CHECK", title = "Blood glucose check",
        attributes = mapOf("bloodMmol" to "6.2", "matchedCgmMmol" to "5.8", "status" to "VALID"),
        status = CompensationEventStatus.CLOSED, provenance = "blood_glucose_checks")
    private val events = mutableStateOf(listOf(meal))
    private var forecastActions = 0

    @Test fun singleMarkerShowsRecordedValuesAndCloseIsPassive() {
        withChart().use {
            open()
            compose.onNodeWithText("Углеводы в записи: 24 г").assertIsDisplayed()
            compose.onNodeWithText("Инсулин в записи: 1,4 Ед").assertIsDisplayed()
            compose.onNodeWithText("Запись AAPS").assertIsDisplayed()
            compose.onNodeWithText("meal_bolus").assertDoesNotExist()
            assertNoOverflow()
            capture("event-details-light-320.png")
            compose.onNodeWithTag("chartEventDetailsClose").performClick()
            compose.onNodeWithTag("chartEventDetailsDialog").assertDoesNotExist()
            assertEquals(0, forecastActions)
        }
    }

    @Test fun clusteredMarkerShowsEachEventAndBloodIsNotASixHourMeasurement() {
        events.value = listOf(meal, blood)
        withChart().use {
            open()
            compose.onNodeWithTag("eventClusterDetails").performScrollToNode(hasTestTag("eventDetailHeading:blood-1"))
            compose.onNodeWithText("Кровь: 6,2 mmol/L").assertExists()
            compose.onNodeWithText("CGM при сопоставлении: 5,8 mmol/L").assertExists()
            compose.onNodeWithText("Статус: Контрольное измерение принято").assertExists()
            compose.onNodeWithText("Blood glucose check").assertDoesNotExist()
            assertNoOverflow()
            assertEquals(0, forecastActions)
        }
    }

    @Test fun fullSourcePayloadShowsGramsWithoutTechnicalCommandNote() {
        val marker = "copilot:manual:meal:sample-meal"
        val payload = (1..24).associate { "extra$it" to "value" } +
            mapOf("grams" to "24", "units" to "1.4", "notes" to marker)
        val recorded = requireNotNull(EventTimelineRepository().prepareTherapyEvent(
            TherapyEvent(origin, "meal_bolus", payload)))
        events.value = listOf(recorded)
        withChart().use {
            compose.onNodeWithTag("eventMarker:${recorded.localId}").performClick()
            compose.onNodeWithText("Углеводы в записи: 24 г").assertIsDisplayed()
            compose.onNodeWithText("Инсулин в записи: 1,4 Ед").assertIsDisplayed()
            compose.onNodeWithText(marker, substring = true).assertDoesNotExist()
            assertNoOverflow()
            capture("event-details-full-payload.png")
            compose.onNodeWithTag("chartEventDetailsClose").performClick()
            assertEquals(0, forecastActions)
        }
    }

    @Test fun currentRecordUpdatesAndDeletedSelectionCannotReopen() {
        withChart().use {
            open()
            compose.runOnIdle { events.value = listOf(meal.copy(attributes = meal.attributes + ("displayCarbsG" to "32"))) }
            compose.onNodeWithText("Углеводы в записи: 32 г").assertExists()
            compose.runOnIdle { events.value = emptyList() }
            compose.onNodeWithTag("chartEventDetailsDialog").assertDoesNotExist()
            compose.runOnIdle { events.value = listOf(meal) }
            compose.onNodeWithTag("chartEventDetailsDialog").assertDoesNotExist()
        }
    }

    @Test fun darkLargeFontRemainsReadableAndCloseReachable() {
        events.value = listOf(blood.copy(note = "A longer reference note that wraps on a narrow screen."))
        withChart(dark = true, fontScale = 1.8f).use {
            compose.onNodeWithTag("eventMarker:blood-1").performClick()
            compose.onNodeWithTag("eventClusterDetails").performScrollToNode(hasText("Статус: Контрольное измерение принято"))
            assertNoOverflow()
            compose.onNodeWithTag("chartEventDetailsClose").assertIsDisplayed()
            capture("event-details-dark-large.png")
            compose.onNodeWithTag("chartEventDetailsClose").performClick()
            assertEquals(0, forecastActions)
        }
    }

    @Test @Config(qualifiers = "w640dp-h320dp-land")
    fun landscapeKeepsDetailsScrollableAndCloseVisible() {
        withChart().use {
            open()
            compose.onNodeWithTag("eventClusterDetails").performScrollToNode(hasText("Lunch"))
            compose.onNodeWithTag("chartEventDetailsClose").assertIsDisplayed()
            assertNoOverflow()
            capture("event-details-landscape.png")
        }
    }

    @Test fun backDismissesWithoutSendingAnyAction() {
        withChart().use {
            open()
            compose.runOnIdle {
                @Suppress("DEPRECATION")
                ShadowDialog.getLatestDialog().onBackPressed()
            }
            compose.onNodeWithTag("chartEventDetailsDialog").assertDoesNotExist()
            assertEquals(0, forecastActions)
        }
    }

    private fun open() = compose.onNodeWithTag("eventMarker:meal-1").performClick()
    private fun withChart(dark: Boolean = false, fontScale: Float = 1f): AutoCloseable {
        val app = RuntimeEnvironment.getApplication()
        val previous = Configuration(app.resources.configuration)
        @Suppress("DEPRECATION")
        app.resources.updateConfiguration(Configuration(previous).apply {
            this.fontScale = fontScale; setLocale(Locale.forLanguageTag("ru"))
        }, app.resources.displayMetrics)
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        controller.get().setContent {
            AapsCopilotTheme(darkTheme = dark, dynamicColor = false, uiStyle = UiStyle.CLARITY) {
                ClinicalForecastChart(ClinicalForecastChartUiState(
                    historyPoints = listOf(ChartPointUi(origin - 3_600_000, 6.0), ChartPointUi(origin + 300_000, 6.4)),
                    events = events.value, eventTimelineNowTs = origin + 300_000),
                    "Glucose", "No data", "Now", "+30", onForecastMarkerClick = { forecastActions++ })
            }
        }
        return AutoCloseable {
            controller.pause().stop().destroy()
            @Suppress("DEPRECATION")
            app.resources.updateConfiguration(previous, app.resources.displayMetrics)
        }
    }

    private fun assertNoOverflow() {
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
