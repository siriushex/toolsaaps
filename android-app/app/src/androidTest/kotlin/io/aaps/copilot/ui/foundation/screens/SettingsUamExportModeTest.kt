package io.aaps.copilot.ui.foundation.screens

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.aaps.copilot.R
import io.aaps.copilot.ui.MainUiState
import io.aaps.copilot.ui.foundation.theme.AapsCopilotTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingsUamExportModeTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun settingsUsesSharedNormalizedSelectorWithoutLegacyExportControls() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val selectedModes = mutableListOf<String>()
        val state = MainUiState().apply {
            enableUamExportToAaps = true
            uamExportMode = "CONFIRMED_ONLY"
            dryRunExport = false
            uamRuntimeReason = "dry_run"
        }.toSettingsUiState(
            verboseLogsEnabled = false,
            proModeEnabled = false
        )

        composeRule.setContent {
            AapsCopilotTheme {
                SettingsScreen(
                    state = state,
                    onVerboseLogsToggle = {},
                    onProModeToggle = {},
                    onUamExportUiModeChange = { selectedModes += it }
                )
            }
        }

        composeRule.onNodeWithTag("settingsUamExportMode").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("uamExportMode_OBSERVE").assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.settings_uam_export)).assertDoesNotExist()
        composeRule.onNodeWithText(context.getString(R.string.settings_uam_dry_run)).assertDoesNotExist()
        composeRule.onNodeWithText("CONFIRMED_ONLY").assertDoesNotExist()
        composeRule.onNodeWithText("INCREMENTAL").assertDoesNotExist()
        composeRule.onNodeWithText(
            context.getString(
                R.string.uam_mode_runtime_status,
                context.getString(R.string.uam_runtime_observation_active)
            )
        ).assertIsDisplayed()
        composeRule.onNodeWithText("BLOCKED", substring = true, ignoreCase = true)
            .assertDoesNotExist()
        composeRule.onNodeWithText("dry_run", substring = true, ignoreCase = true)
            .assertDoesNotExist()
        composeRule.onNodeWithText("dry run", substring = true, ignoreCase = true)
            .assertDoesNotExist()

        composeRule.onNodeWithTag("uamExportMode_OFF").performClick()
        composeRule.runOnIdle { assertEquals(listOf("OFF"), selectedModes) }
    }

    @Test
    fun settingsAutoConfirmationUsesSameLimitsAndCancelDoesNotMutate() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val selectedModes = mutableListOf<String>()
        val state = MainUiState().apply {
            enableUamExportToAaps = true
            uamExportMode = "INCREMENTAL"
            dryRunExport = true
        }.toSettingsUiState(
            verboseLogsEnabled = false,
            proModeEnabled = false
        )

        composeRule.setContent {
            AapsCopilotTheme {
                SettingsScreen(
                    state = state,
                    onVerboseLogsToggle = {},
                    onProModeToggle = {},
                    onUamExportUiModeChange = { selectedModes += it }
                )
            }
        }

        composeRule.onNodeWithTag("settingsUamExportMode").performScrollTo()
        composeRule.onNodeWithTag("uamExportMode_AUTO").performClick()
        composeRule.onNodeWithText(context.getString(R.string.uam_auto_confirm_limits))
            .assertIsDisplayed()
        composeRule.onNodeWithTag("uamAutoConfirmCancel").performClick()
        composeRule.runOnIdle { assertEquals(emptyList<String>(), selectedModes) }

        composeRule.onNodeWithTag("uamExportMode_AUTO").performClick()
        composeRule.onNodeWithTag("uamAutoConfirmAccept").performClick()
        composeRule.runOnIdle { assertEquals(listOf("AUTO"), selectedModes) }
    }

    @Test
    fun autoUamExportCapIsAvailableOnlyInAdvancedSettings() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val state = MainUiState().apply {
            enableUamInference = true
            enableUamAutoExportCap = false
            uamAutoExportCapGrams = 10
        }.toSettingsUiState(
            verboseLogsEnabled = false,
            proModeEnabled = false
        )

        composeRule.setContent {
            AapsCopilotTheme {
                SettingsScreen(
                    state = state,
                    onVerboseLogsToggle = {},
                    onProModeToggle = {}
                )
            }
        }

        composeRule.onNodeWithText(
            context.getString(R.string.settings_uam_auto_export_cap)
        ).assertDoesNotExist()
        composeRule.onNodeWithText(context.getString(R.string.settings_tab_advanced)).performClick()
        composeRule.onNodeWithText(
            context.getString(R.string.settings_uam_auto_export_cap)
        ).performScrollTo().assertIsDisplayed()
    }
}
