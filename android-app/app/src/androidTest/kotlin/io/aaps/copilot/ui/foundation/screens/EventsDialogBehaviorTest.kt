package io.aaps.copilot.ui.foundation.screens

import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.aaps.copilot.domain.events.CompensationEvent
import io.aaps.copilot.domain.events.CompensationEventType
import io.aaps.copilot.domain.events.EventSource
import io.aaps.copilot.domain.profile.PhysiologicalSex
import io.aaps.copilot.ui.foundation.components.ClinicalForecastChart
import io.aaps.copilot.ui.foundation.components.EventTimelineRail
import io.aaps.copilot.ui.ContextEventCommandAction
import io.aaps.copilot.ui.ContextEventCommandUiDisposition
import io.aaps.copilot.ui.ContextEventCommandUiPolicy
import io.aaps.copilot.data.repository.ContextEventCommandResult
import io.aaps.copilot.ui.foundation.theme.AapsCopilotTheme
import java.util.Locale
import org.junit.Rule
import org.junit.Test

class EventsDialogBehaviorTest {
    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun filtersToggleAndLargeFontSectionsRemainOperable() {
        var showOnGraph = true
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 2f)) {
                EventsDialog(
                    events = listOf(
                        CompensationEvent("active", 1_000L, 10_000L, CompensationEventType.STRESS),
                        CompensationEvent("recent", 100L, 500L, CompensationEventType.CUSTOM)
                    ),
                    nowTs = 5_000L,
                    sex = PhysiologicalSex.FEMALE,
                    showOnGraph = showOnGraph,
                    onShowOnGraphChange = { showOnGraph = it },
                    onDismiss = {},
                    onSave = { _, _, _ -> },
                    onClose = {},
                    onDelete = {},
                    onOpenMealAction = {},
                    onOpenPlannedActivity = {},
                    onOpenBloodCheck = {},
                    onOpenReadOnlyDiagnostic = {}
                )
            }
        }

        composeRule.onNodeWithTag("eventsActiveSection").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("eventsRecentSection").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Manual").performClick()
        composeRule.onNodeWithTag("eventsShowOnGraph").assertIsOn().performClick()
        composeRule.runOnIdle { check(!showOnGraph) }
        eventActionTypes(PhysiologicalSex.FEMALE).forEach { type ->
            composeRule.onNodeWithTag("eventAction:${type.name}")
                .assertExists()
                .assertHeightIsAtLeast(64.dp)
        }
    }

    @Test
    fun actionGridDispatchesOnlyAuthoritativeRoutes() {
        val dispatched = mutableListOf<String>()
        composeRule.setContent {
            AapsCopilotTheme {
                EventsDialog(
                    events = emptyList(),
                    nowTs = 5_000L,
                    sex = PhysiologicalSex.FEMALE,
                    showOnGraph = true,
                    onShowOnGraphChange = {},
                    onDismiss = {},
                    onSave = { event, _, _ -> dispatched += "context:${event.type.name}" },
                    onClose = {},
                    onDelete = {},
                    onOpenMealAction = { dispatched += "meal" },
                    onOpenPlannedActivity = { dispatched += "activity" },
                    onOpenBloodCheck = { dispatched += "blood-check" },
                    onOpenReadOnlyDiagnostic = { dispatched += "diagnostic" }
                )
            }
        }

        composeRule.onNodeWithTag("eventAction:MEAL").performScrollTo().performClick()
        composeRule.onNodeWithTag("eventAction:ACTIVITY").performScrollTo().performClick()
        composeRule.onNodeWithTag("eventAction:SENSOR_CALIBRATION").performScrollTo().performClick()
        composeRule.onNodeWithTag("eventAction:INFUSION_PUMP_INSULIN").performScrollTo().performClick()
        composeRule.runOnIdle {
            check(dispatched == listOf("meal", "activity", "blood-check", "diagnostic"))
        }
        composeRule.onNodeWithTag("eventAction:STRESS").performScrollTo().performClick()
        composeRule.onNodeWithText("Add event").assertIsDisplayed()
        composeRule.onNodeWithText("Stress").assertIsDisplayed()
    }

    @Test
    fun maleProfileHidesCycleCreationButKeepsUserHistoryManageableAndImportsReadOnly() {
        composeRule.setContent {
            AapsCopilotTheme {
                EventsDialog(
                    events = listOf(
                        CompensationEvent(
                            localId = "history",
                            startTs = 1_000L,
                            endTs = 10_000L,
                            type = CompensationEventType.MENSTRUAL_CYCLE,
                            source = EventSource.USER,
                            title = "Historical cycle"
                        ),
                        CompensationEvent(
                            localId = "aaps",
                            startTs = 1_000L,
                            endTs = 10_000L,
                            type = CompensationEventType.STRESS,
                            source = EventSource.AAPS,
                            title = "Imported stress"
                        )
                    ),
                    nowTs = 5_000L,
                    sex = PhysiologicalSex.MALE,
                    showOnGraph = true,
                    onShowOnGraphChange = {},
                    onDismiss = {},
                    onSave = { _, _, _ -> },
                    onClose = {},
                    onDelete = {},
                    onOpenMealAction = {},
                    onOpenPlannedActivity = {},
                    onOpenBloodCheck = {},
                    onOpenReadOnlyDiagnostic = {}
                )
            }
        }

        composeRule.onNodeWithTag("eventAction:MENSTRUAL_CYCLE").assertDoesNotExist()
        composeRule.onNodeWithTag("eventEdit:history").assertDoesNotExist()
        composeRule.onNodeWithTag("eventFinish:history").assertExists()
        composeRule.onNodeWithTag("eventDelete:history").assertExists()
        composeRule.onNodeWithTag("eventEdit:aaps").assertDoesNotExist()
        composeRule.onNodeWithTag("eventFinish:aaps").assertDoesNotExist()
        composeRule.onNodeWithTag("eventDelete:aaps").assertDoesNotExist()
    }

    @Test
    fun russianResourcesDriveActionAndMarkerSemantics() {
        val configuration = Configuration(composeRule.activity.resources.configuration).apply {
            setLocale(Locale.forLanguageTag("ru-RU"))
        }
        val localizedContext = composeRule.activity.createConfigurationContext(configuration)
        composeRule.setContent {
            CompositionLocalProvider(
                LocalConfiguration provides configuration,
                LocalContext provides localizedContext
            ) {
                AapsCopilotTheme {
                    EventsDialog(
                        events = emptyList(),
                        nowTs = 5_000L,
                        sex = PhysiologicalSex.FEMALE,
                        showOnGraph = true,
                        onShowOnGraphChange = {},
                        onDismiss = {},
                        onSave = { _, _, _ -> },
                        onClose = {},
                        onDelete = {},
                        onOpenMealAction = {},
                        onOpenPlannedActivity = {},
                        onOpenBloodCheck = {},
                        onOpenReadOnlyDiagnostic = {}
                    )
                    EventTimelineRail(
                        markers = listOf(
                            CompensationEvent("stress", 1_000L, 2_000L, CompensationEventType.STRESS)
                                .toChartMarkerUi(1_500L)
                        ),
                        rangeStartTs = 0L,
                        rangeEndTs = 3_000L,
                        onEventSelected = {}
                    )
                }
            }
        }

        composeRule.onNodeWithContentDescription("Добавить контекст: Стресс").assertExists()
        composeRule.onNodeWithContentDescription("Событие: Стресс").assertExists()
    }

    @Test
    fun eventRailTapSelectsMarkerWithoutOwningChartPanOrPinch() {
        var selected: List<String> = emptyList()
        composeRule.setContent {
            EventTimelineRail(
                markers = listOf(
                    CompensationEvent("event-1", 1_000L, 2_000L, CompensationEventType.STRESS)
                        .toChartMarkerUi(1_500L)
                ),
                rangeStartTs = 0L,
                rangeEndTs = 3_000L,
                onEventSelected = { selected = it.representedEventIds }
            )
        }

        composeRule.onNodeWithContentDescription("Stress event").performClick()
        composeRule.onNodeWithTag("eventMarkerIcon:event-1")
            .assertWidthIsEqualTo(16.dp)
            .assertHeightIsEqualTo(16.dp)
        composeRule.runOnIdle { check(selected == listOf("event-1")) }
    }

    @Test
    fun pendingSaveKeepsDraftVisibleAndAcknowledgementClosesEditor() {
        var completion: ((ContextEventCommandUiDisposition) -> Unit)? = null
        val submitted = mutableListOf<CompensationEvent>()
        composeRule.setContent {
            AapsCopilotTheme {
                EventsDialog(
                    events = emptyList(),
                    nowTs = 5_000L,
                    sex = PhysiologicalSex.FEMALE,
                    showOnGraph = true,
                    onShowOnGraphChange = {},
                    onDismiss = {},
                    onSave = { event, _, callback ->
                        submitted += event
                        completion = callback
                    },
                    onClose = {},
                    onDelete = {},
                    onOpenMealAction = {},
                    onOpenPlannedActivity = {},
                    onOpenBloodCheck = {},
                    onOpenReadOnlyDiagnostic = {}
                )
            }
        }

        composeRule.onNodeWithTag("eventAction:STRESS").performScrollTo().performClick()
        composeRule.onNodeWithTag("eventEditorTitle").performTextInput("Persistent draft")
        composeRule.onNodeWithText("Save").performClick()
        composeRule.runOnIdle {
            completion?.invoke(
                ContextEventCommandUiPolicy.resolve(
                    ContextEventCommandAction.SAVE,
                    ContextEventCommandResult.PENDING
                )
            )
        }
        composeRule.onNodeWithText("Persistent draft").assertIsDisplayed()
        composeRule.onNodeWithText("Waiting for AAPS confirmation. The draft is preserved.")
            .assertIsDisplayed()
        composeRule.onNodeWithText("Save").performClick()
        composeRule.runOnIdle {
            check(submitted.size == 2)
            check(submitted[0].localId == submitted[1].localId)
            check(submitted[0].startTs == submitted[1].startTs)
            check(submitted[0].title == submitted[1].title)
        }

        composeRule.runOnIdle {
            completion?.invoke(
                ContextEventCommandUiPolicy.resolve(
                    ContextEventCommandAction.SAVE,
                    ContextEventCommandResult.ACKNOWLEDGED
                )
            )
        }
        composeRule.onNodeWithText("Persistent draft").assertDoesNotExist()
    }

    @Test
    fun chartMarkerTapOpensEventDetails() {
        composeRule.setContent {
            ClinicalForecastChart(
                state = ClinicalForecastChartUiState(
                    historyPoints = listOf(ChartPointUi(0L, 5.0), ChartPointUi(3_000L, 5.5)),
                    events = listOf(
                        CompensationEvent("event-1", 1_000L, 2_000L, CompensationEventType.STRESS)
                    ),
                    eventTimelineNowTs = 1_500L,
                    showEvents = true
                ),
                contentDescription = "Forecast chart",
                emptyText = "No data",
                nowLabel = "Now",
                plus30Label = "+30m"
            )
        }

        composeRule.onNodeWithContentDescription("Stress event").performClick()
        composeRule.onNodeWithText("Event details").assertIsDisplayed()
    }

    @Test
    fun clusteredMarkerOpensScrollableDetailsContainingEveryMember() {
        val events = (0 until 20).map { index ->
            CompensationEvent(
                localId = "event-$index",
                startTs = 1_000L + index,
                endTs = 2_000L,
                type = CompensationEventType.STRESS,
                title = "Member $index"
            )
        }
        composeRule.setContent {
            AapsCopilotTheme {
                ClinicalForecastChart(
                    state = ClinicalForecastChartUiState(
                        historyPoints = listOf(ChartPointUi(0L, 5.0), ChartPointUi(3_000L, 5.5)),
                        events = events,
                        eventTimelineNowTs = 1_500L,
                        showEvents = true
                    ),
                    contentDescription = "Forecast chart",
                    emptyText = "No data",
                    nowLabel = "Now",
                    plus30Label = "+30m"
                )
            }
        }

        composeRule.onNodeWithContentDescription("Stress event").performClick()
        composeRule.onNodeWithTag("eventClusterDetails").assert(hasScrollAction())
        composeRule.onNodeWithText("Member 0").assertExists()
        composeRule.onNodeWithText("Member 19").assertExists()
    }
}
