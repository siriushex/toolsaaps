package io.aaps.copilot.ui.setup

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import io.aaps.copilot.config.*
import io.aaps.copilot.ui.foundation.screens.IssueDestination
import io.aaps.copilot.ui.foundation.theme.AapsCopilotTheme
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
@Config(sdk = [35], application = Application::class, qualifiers = "w360dp-h640dp-port")
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SetupScreenTest {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var root: View

    @Test fun manualPathCanFinishWithoutHardwarePermissionsOrTherapy() {
        var state by mutableStateOf(SetupUiState(SetupProgress(status = SetupStatus.NOT_STARTED)))
        var closed = 0
        screen({ state }, onSave = { next, saved -> state = state.copy(progress = next); saved() }, onClose = { closed++ }) {
            repeat(4) { compose.onNodeWithTag("setup_next").performClick() }
            compose.onNodeWithTag("setup_review_therapy").assertDoesNotExist()
            compose.onNodeWithTag("setup_next").performClick()
            assertEquals(1, closed)
            assertEquals(SetupStatus.COMPLETE, state.progress?.status)
            assertFalse(state.therapyArmed)
        }
    }

    @Test fun equipmentUpdatesRetainOtherSelectionAndDoNotInvokeActions() {
        var state by mutableStateOf(SetupUiState(SetupProgress()))
        screen({ state }, onSave = { next, saved -> state = state.copy(progress = next); saved() }) {
            compose.onNodeWithTag("setup_glucose_SENSOR").performClick()
            compose.onNodeWithTag("setup_insulin_INJECTIONS").performScrollTo().performClick()
            assertEquals(SetupGlucoseSource.SENSOR, state.progress?.glucoseSource)
            assertEquals(SetupInsulinSource.INJECTIONS, state.progress?.insulinSource)
            compose.onNodeWithTag("setup_insulin_INJECTIONS").assertIsSelected()
            compose.onNodeWithTag("setup_next").performClick()
            compose.onNodeWithTag("setup_blood_check").assertDoesNotExist()
            compose.onNodeWithTag("setup_sources").assertExists()
        }
    }

    @Test fun manualMeasurementRoutesOnlyOnExplicitTap() {
        var opens = 0
        screen({ SetupUiState(SetupProgress(step = SetupStep.DATA)) }, onBlood = { opens++ }) {
            assertEquals(0, opens)
            compose.onNodeWithTag("setup_blood_check").performScrollTo().performClick()
            assertEquals(1, opens)
            compose.onNodeWithTag("setup_sources").assertDoesNotExist()
        }
    }

    @Config(qualifiers = "ru-rRU-w320dp-h640dp-port")
    @Test fun observationRequiresExplicitSwitchAndDoesNotReviewTherapyOrFinishSetup() {
        var state by mutableStateOf(SetupUiState(SetupProgress(step = SetupStep.DATA,
            glucoseSource = SetupGlucoseSource.SENSOR)))
        val changes = mutableListOf<Boolean>()
        screen({ state }, onObservation = { changes += it; state = state.copy(observationEnabled = it) },
            fontScale = 1.5f) {
            assertTrue(changes.isEmpty())
            compose.onNodeWithTag("setup_observation").performScrollTo().performClick().assertIsOn()
            assertEquals(listOf(true), changes)
            assertFalse(state.therapyArmed)
            compose.onNodeWithTag("setup_next").assertIsDisplayed()
            saveRaster("setup-observation-large")
            compose.onNodeWithTag("setup_observation").performClick().assertIsOff()
            assertEquals(listOf(true, false), changes)
        }
    }

    @Test fun failedOrSavingObservationCannotBeToggledAndArmedModeShowsActualAuthority() {
        var state by mutableStateOf(SetupUiState(SetupProgress(step = SetupStep.DATA), saving = true))
        screen({ state }) {
            compose.onNodeWithTag("setup_observation").assertIsNotEnabled()
            compose.runOnIdle { state = state.copy(saving = false, failed = true) }
            compose.onNodeWithTag("setup_observation").assertIsNotEnabled()
            compose.runOnIdle { state = state.copy(failed = false, therapyArmed = true) }
            compose.onNodeWithTag("setup_observation").assertDoesNotExist()
            compose.onNodeWithText("Observation is included", substring = true).assertExists()
        }
    }

    @Test fun localDiaryOpensOnlyOnExplicitTap() {
        var opens = 0
        screen({ SetupUiState(SetupProgress(step = SetupStep.DATA)) }, onDiary = { opens++ }) {
            assertEquals(0, opens)
            compose.onNodeWithTag("setup_diary").performScrollTo().performClick()
            assertEquals(1, opens)
        }
    }

    @Test fun profileActionsOpenTheExistingEditorsAndDoNotCompleteTheStep() {
        val opened = mutableListOf<IssueDestination>()
        screen({ SetupUiState(SetupProgress(step = SetupStep.PROFILE)) }, onSettings = { opened += it }) {
            compose.onNodeWithTag("setup_profile").performClick()
            compose.onNodeWithTag("setup_energy").performClick()
            assertEquals(listOf(IssueDestination.USER_PROFILE, IssueDestination.ENERGY_GOAL), opened)
        }
    }

    @Test fun aapsChoiceStillRequiresExplicitTherapyReview() {
        var reviews = 0
        screen({ SetupUiState(SetupProgress(step = SetupStep.REVIEW, insulinSource = SetupInsulinSource.AAPS)) },
            onTherapy = { reviews++ }) {
            assertEquals(0, reviews)
            compose.onNodeWithTag("setup_review_therapy").performScrollTo().performClick()
            assertEquals(1, reviews)
        }
    }

    @Test fun alreadyArmedWithoutPumpShowsSafetyInsteadOfClaimingCommandsAreOff() {
        screen({ SetupUiState(SetupProgress(step = SetupStep.REVIEW), therapyArmed = true) }) {
            compose.onNodeWithText("Therapy commands were already enabled.", substring = true).assertExists()
            compose.onNodeWithTag("setup_safety").assertExists()
            compose.onNodeWithTag("setup_review_therapy").assertDoesNotExist()
        }
    }

    @Test fun pendingSaveDisablesProgressAndSelection() {
        screen({ SetupUiState(SetupProgress(), saving = true) }) {
            compose.onNodeWithTag("setup_next").assertIsNotEnabled()
            compose.onNodeWithTag("setup_later").assertIsNotEnabled()
            compose.onNodeWithTag("setup_glucose_SENSOR").assertIsNotEnabled()
        }
    }

    @Test fun failedSaveCannotClaimFinishedAndCanBeRetried() {
        var retries = 0
        screen({ SetupUiState(SetupProgress(step = SetupStep.REVIEW), failed = true) }, onRetry = { retries++ }) {
            compose.onNodeWithTag("setup_next").assertIsNotEnabled()
            compose.onNodeWithText("Retry").performClick()
            assertEquals(1, retries)
        }
    }

    @Test fun deferRetainsSelectionsAndCurrentStepOnlyAfterSave() {
        var next: SetupProgress? = null
        var closed = 0
        var callback: (() -> Unit)? = null
        screen({ SetupUiState(SetupProgress(step = SetupStep.ALERTS, insulinSource = SetupInsulinSource.PUMP)) },
            onSave = { value, saved -> next = value; callback = saved }, onClose = { closed++ }) {
            compose.onNodeWithTag("setup_later").performClick()
            assertEquals(0, closed)
            assertEquals(SetupStep.ALERTS, next?.step)
            assertEquals(SetupStatus.DEFERRED, next?.status)
            assertEquals(SetupInsulinSource.PUMP, next?.insulinSource)
            callback!!.invoke()
            assertEquals(1, closed)
        }
    }

    @Config(qualifiers = "ru-rRU-w320dp-h640dp-port")
    @Test fun allStepsRenderOnSmallRussianScreenWithReachableFooter() {
        var state by mutableStateOf(SetupUiState(SetupProgress()))
        screen({ state }, onSave = { next, saved -> state = state.copy(progress = next); saved() }) {
            for (step in SetupStep.entries) {
                compose.onNodeWithTag("setup_next").assertIsDisplayed()
                compose.onNodeWithTag("setup_later").assertIsDisplayed()
                saveRaster("setup-small-${step.name.lowercase()}")
                if (step != SetupStep.REVIEW) compose.onNodeWithTag("setup_next").performClick()
            }
        }
    }

    @Config(qualifiers = "ru-rRU-w320dp-h640dp-port")
    @Test fun largeTextKeepsFooterAndScrollableChoicesReachable() {
        screen({ SetupUiState(SetupProgress()) }, fontScale = 1.5f) {
            compose.onNodeWithTag("setup_next").assertIsDisplayed()
            compose.onNodeWithTag("setup_insulin_AAPS").performScrollTo().assertIsDisplayed()
            saveRaster("setup-large-text")
        }
    }

    private fun saveRaster(name: String) {
        val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bitmap))
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        assertTrue(pixels.toSet().size > 32)
        val directory = File("build/reports/setup-raster").apply { mkdirs() }
        File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun screen(
        state: () -> SetupUiState,
        onSave: (SetupProgress, () -> Unit) -> Unit = { _, _ -> error("Unexpected save") },
        onClose: () -> Unit = { error("Unexpected close") },
        onSettings: (IssueDestination) -> Unit = { error("Unexpected settings") },
        onBlood: () -> Unit = { error("Unexpected blood entry") },
        onTherapy: () -> Unit = { error("Unexpected therapy review") },
        onRetry: () -> Unit = { error("Unexpected reload") },
        onDiary: () -> Unit = { error("Unexpected diary") },
        onObservation: (Boolean) -> Unit = { error("Unexpected observation") },
        fontScale: Float = 1f,
        test: () -> Unit
    ) {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        root = controller.get().window.decorView
        try {
            controller.get().setContent {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                    AapsCopilotTheme(darkTheme = false, dynamicColor = false, uiStyle = UiStyle.CLARITY) {
                        SetupScreen(state(), onSave, onClose, onSettings, onBlood, onTherapy,
                            onNotifications = { error("Unexpected permission") }, onRetry = onRetry, onDiary = onDiary,
                            onObservation = onObservation)
                    }
                }
            }
            compose.waitForIdle()
            test()
            compose.waitForIdle()
        } finally { controller.pause().stop().destroy() }
    }
}
