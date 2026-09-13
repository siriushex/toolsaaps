package io.aaps.copilot.ui.foundation.screens

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.aaps.copilot.domain.target.BaseTargetSchedule
import io.aaps.copilot.domain.target.CircadianAutoState
import io.aaps.copilot.ui.foundation.components.BaseTargetScheduleDialog
import io.aaps.copilot.ui.foundation.theme.AapsCopilotTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BaseTargetScheduleUiTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun editor_hasOneAutoSwitchAndSavesAddedInterval() {
        var saved: BaseTargetSchedule? = null

        composeRule.setContent {
            AapsCopilotTheme {
                BaseTargetScheduleDialog(
                    schedule = BaseTargetSchedule.legacy(6.0),
                    effectiveTargetMmol = 6.0,
                    autoDeltaMmol = 0.0,
                    autoState = CircadianAutoState.OFF,
                    autoReason = null,
                    minTargetMmol = 4.0,
                    maxTargetMmol = 10.0,
                    onDismiss = {},
                    onSave = { saved = it }
                )
            }
        }

        composeRule.onNodeWithTag("baseTargetScheduleDialog").assertIsDisplayed()
        composeRule.onAllNodesWithTag("baseTargetAutoSwitch").assertCountEquals(1)
        composeRule.onNodeWithTag("baseTargetAutoSwitch").performClick()
        composeRule.onNodeWithTag("baseTargetAddInterval").performClick()
        composeRule.onNodeWithTag("baseTargetScheduleSave").performClick()

        composeRule.runOnIdle {
            assertEquals(1, saved?.intervals?.size)
            assertTrue(saved?.autoEnabled == true)
            assertEquals(6.0, saved?.defaultTargetMmol ?: 0.0, 0.001)
        }
    }

    @Test
    fun cancelDoesNotSaveDraft() {
        var saved: BaseTargetSchedule? = null

        composeRule.setContent {
            AapsCopilotTheme {
                BaseTargetScheduleDialog(
                    schedule = BaseTargetSchedule.legacy(5.8),
                    effectiveTargetMmol = 5.8,
                    autoDeltaMmol = 0.0,
                    autoState = CircadianAutoState.OFF,
                    autoReason = null,
                    minTargetMmol = 4.0,
                    maxTargetMmol = 10.0,
                    onDismiss = {},
                    onSave = { saved = it }
                )
            }
        }

        composeRule.onNodeWithTag("baseTargetAddInterval").performClick()
        composeRule.onNodeWithTag("baseTargetScheduleCancel").performClick()
        composeRule.runOnIdle { assertNull(saved) }
    }
}
