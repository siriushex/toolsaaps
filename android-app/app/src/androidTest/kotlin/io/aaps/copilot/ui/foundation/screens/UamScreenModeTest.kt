package io.aaps.copilot.ui.foundation.screens

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.aaps.copilot.R
import io.aaps.copilot.ui.foundation.theme.AapsCopilotTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class UamScreenModeTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun uamScreenShowsNormalizedReadOnlyModeWithoutManualExportPathOrLegacyWording() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext

        composeRule.setContent {
            AapsCopilotTheme {
                UamScreen(
                    state = UamUiState(
                        loadState = ScreenLoadState.READY,
                        isStale = false,
                        calculatedActive = true,
                        calculatedCarbsGrams = 12.0,
                        calculatedConfidence = 0.47,
                        uamExport = UamExportControlUi(mode = "OBSERVE"),
                        events = listOf(testEvent())
                    ),
                    onMarkCorrect = {},
                    onMarkWrong = {},
                    onMergeWithManual = {}
                )
            }
        }

        composeRule.onNodeWithTag("uamReadOnlyModeStatus").assertIsDisplayed()
        composeRule.onNodeWithText(
            context.getString(
                R.string.uam_mode_read_only,
                context.getString(R.string.uam_mode_observe)
            )
        ).assertIsDisplayed()
        composeRule.onNodeWithText("Export to AAPS").assertDoesNotExist()
        composeRule.onNodeWithText("Экспорт в AAPS").assertDoesNotExist()
        composeRule.onNodeWithText("Export enabled (dry-run)").assertDoesNotExist()
        composeRule.onNodeWithText("Export enabled (live)").assertDoesNotExist()
        composeRule.onNodeWithText("Export disabled").assertDoesNotExist()
        composeRule.onNodeWithText("Экспорт включен (dry-run)").assertDoesNotExist()
        composeRule.onNodeWithText("Экспорт включен (боевой)").assertDoesNotExist()
        composeRule.onNodeWithText("Экспорт выключен").assertDoesNotExist()
    }

    private fun testEvent() = UamEventUi(
        id = "event-visible",
        state = "CONFIRMED",
        mode = "NORMAL",
        createdAt = 1_000L,
        updatedAt = 2_000L,
        ingestionTs = 1_500L,
        carbsDisplayG = 12.0,
        confidence = 0.47,
        exportSeq = 0,
        exportedGrams = 0.0,
        tag = "UAM_ENGINE|id=event-visible|seq=1|ver=2|mode=NORMAL|",
        manualCarbsNearby = false,
        manualCobActive = false,
        exportBlockedReason = null
    )
}
