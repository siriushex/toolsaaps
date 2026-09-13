package io.aaps.copilot.ui.foundation.screens

import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.aaps.copilot.R
import io.aaps.copilot.data.repository.PumpLinkUiStatus
import io.aaps.copilot.domain.pump.PumpLinkCondition
import io.aaps.copilot.ui.foundation.theme.AapsCopilotTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PumpLinkOverviewUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun faultInfoIsPassiveAndHealthyStateRemovesTheRow() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        var commands = 0
        val status = mutableStateOf(PumpLinkUiStatus(PumpLinkCondition.DRIVER_ERROR, true))
        compose.setContent {
            AapsCopilotTheme {
                OverviewScreen(
                    state = OverviewUiState(loadState = ScreenLoadState.READY, glucose = 5.6,
                        isStale = false, pumpLinkStatus = status.value),
                    onRunCycleNow = { commands++ },
                    onSetKillSwitch = { commands++ },
                    onDisablePowerSave = { commands++ },
                    onAddBloodCheck = { _, _, _, _ -> commands++ },
                    onOpenClinicalReport = { commands++ }
                )
            }
        }
        compose.onNodeWithTag("pump_link_status").assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.pump_link_driver_error)).assertIsDisplayed()
        compose.onNodeWithContentDescription(context.getString(R.string.pump_link_channel)).performClick()
        compose.onNodeWithText(context.getString(R.string.pump_link_details)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.action_close)).performClick()
        compose.runOnIdle {
            assertEquals(0, commands)
            status.value = PumpLinkUiStatus(PumpLinkCondition.HEALTHY)
        }
        compose.onNodeWithTag("pump_link_status").assertDoesNotExist()
    }
}
