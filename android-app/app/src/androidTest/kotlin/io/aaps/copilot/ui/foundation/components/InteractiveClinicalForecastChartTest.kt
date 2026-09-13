package io.aaps.copilot.ui.foundation.components

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.aaps.copilot.ui.foundation.screens.ChartPointUi
import io.aaps.copilot.ui.foundation.screens.ClinicalForecastChartUiState
import io.aaps.copilot.ui.foundation.theme.AapsCopilotTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class InteractiveClinicalForecastChartTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun interactiveChart_exposesOverviewPlotNode() {
        val now = 24L * 60L * 60_000L
        val chart = ClinicalForecastChartUiState(
            historyPoints = listOf(
                ChartPointUi(now - 3L * 60L * 60_000L, 5.2),
                ChartPointUi(now, 5.6)
            ),
            futurePath = listOf(
                ChartPointUi(now, 5.6),
                ChartPointUi(now + 30L * 60_000L, 6.0)
            )
        )
        val domain = requireNotNull(clinicalChartDomain(chart))
        val interactionState = InteractiveClinicalChartState(domain)

        composeRule.setContent {
            AapsCopilotTheme {
                InteractiveClinicalForecastChart(
                    state = chart,
                    interactionState = interactionState,
                    contentDescription = "Forecast chart",
                    viewportStateDescription = "Visible interval",
                    emptyText = "No data",
                    nowLabel = "Now",
                    plus30Label = "+30m",
                    zoomInLabel = "Zoom in",
                    zoomOutLabel = "Zoom out",
                    olderLabel = "Older",
                    newerLabel = "Newer",
                    resetLabel = "Reset chart view"
                )
            }
        }

        composeRule.onNodeWithTag("overviewInteractiveChart").assertIsDisplayed()
    }
}
