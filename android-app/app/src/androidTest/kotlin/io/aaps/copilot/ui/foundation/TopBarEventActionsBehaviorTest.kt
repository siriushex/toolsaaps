package io.aaps.copilot.ui.foundation

import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import io.aaps.copilot.R
import io.aaps.copilot.ui.foundation.theme.AapsCopilotTheme
import java.util.Locale
import org.junit.Rule
import org.junit.Test

class TopBarEventActionsBehaviorTest {
    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test fun notebookIsImmediatelyBeforeAlertBell() {
        composeRule.setContent {
            AapsCopilotTheme {
                Row {
                    EventAlertActions(
                        activeEventCount = 2,
                        alertsMuted = false,
                        remainingMuteMinutes = 0,
                        onOpenEvents = {}, onMute30m = {}, onMute60m = {}, onResume = {}, onOpenHistory = {}
                    )
                }
            }
        }

        val notebook = composeRule.onNodeWithTag("eventsNotebookAction").fetchSemanticsNode().boundsInRoot
        val bell = composeRule.onNodeWithTag("glucoseAlertBellAction").fetchSemanticsNode().boundsInRoot
        check(notebook.right <= bell.left)
        composeRule.onNodeWithContentDescription(
            composeRule.activity.getString(R.string.events_notebook_description)
        ).assertExists()
    }

    @Test fun badgeAppearsOnlyForPositiveCountAndUsesStableOverflowText() {
        val count = mutableIntStateOf(0)
        composeRule.setContent {
            AapsCopilotTheme {
                Row {
                    EventAlertActions(
                        activeEventCount = count.intValue,
                        alertsMuted = false,
                        remainingMuteMinutes = 0,
                        onOpenEvents = {}, onMute30m = {}, onMute60m = {}, onResume = {}, onOpenHistory = {}
                    )
                }
            }
        }

        composeRule.onNodeWithTag("eventsActiveBadge").assertDoesNotExist()
        composeRule.runOnIdle { count.intValue = 7 }
        composeRule.onNodeWithTag("eventsActiveBadge").assertTextEquals("7")
        composeRule.runOnIdle { count.intValue = 100 }
        composeRule.onNodeWithTag("eventsActiveBadge").assertTextEquals("99+")
        composeRule.runOnIdle { count.intValue = -1 }
        composeRule.onNodeWithTag("eventsActiveBadge").assertDoesNotExist()
    }

    @Test fun russianConfigurationLocalizesNotebookBellAndBadgeSemantics() {
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
                    Row {
                        EventAlertActions(
                            activeEventCount = 3,
                            alertsMuted = false,
                            remainingMuteMinutes = 0,
                            onOpenEvents = {}, onMute30m = {}, onMute60m = {}, onResume = {}, onOpenHistory = {}
                        )
                    }
                }
            }
        }

        composeRule.onNodeWithContentDescription(
            localizedContext.getString(R.string.events_notebook_description)
        ).assertExists()
        composeRule.onNodeWithContentDescription(
            localizedContext.getString(R.string.alerts_options)
        ).assertExists()
        composeRule.onNodeWithContentDescription(
            localizedContext.getString(R.string.events_active_badge_description, "3")
        ).assertExists()
    }

    @Test fun unmutedBellShowsExactChoicesAndMutedBellResumesWithoutMenu() {
        val muted = androidx.compose.runtime.mutableStateOf(false)
        val resumed = mutableIntStateOf(0)
        composeRule.setContent {
            AapsCopilotTheme {
                Row {
                    EventAlertActions(
                        activeEventCount = 0,
                        alertsMuted = muted.value,
                        remainingMuteMinutes = if (muted.value) 25 else 0,
                        onOpenEvents = {},
                        onMute30m = {},
                        onMute60m = {},
                        onResume = { resumed.intValue += 1 },
                        onOpenHistory = {}
                    )
                }
            }
        }

        composeRule.onNodeWithTag("glucoseAlertBellAction").performClick()
        composeRule.onNodeWithTag("glucoseAlertMute30Action").assertIsDisplayed()
        composeRule.onNodeWithTag("glucoseAlertMute60Action").assertIsDisplayed()
        composeRule.onNodeWithTag("glucoseAlertHistoryAction").assertIsDisplayed()
        composeRule.onNodeWithTag("glucoseAlertBellAction").performClick()
        composeRule.runOnIdle { muted.value = true }
        composeRule.onNodeWithTag("glucoseAlertBellAction").performClick()
        composeRule.runOnIdle { check(resumed.intValue == 1) }
        composeRule.onNodeWithTag("glucoseAlertMute30Action").assertDoesNotExist()
    }
}
