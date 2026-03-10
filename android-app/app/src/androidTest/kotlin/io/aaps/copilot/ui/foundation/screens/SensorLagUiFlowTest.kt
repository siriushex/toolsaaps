package io.aaps.copilot.ui.foundation.screens

import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import io.aaps.copilot.R
import io.aaps.copilot.ui.foundation.theme.AapsCopilotTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SensorLagUiFlowTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun overviewScreen_rolloutVerdictClickInvokesAnalyticsCallback() {
        var clicked = false
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val verdictText = context.getString(
            R.string.overview_sensor_lag_rollout_template,
            context.getString(R.string.analytics_daily_report_sensor_lag_rollout_active),
            context.getString(R.string.analytics_daily_report_sensor_lag_bucket_10_12d)
        )

        composeRule.setContent {
            AapsCopilotTheme {
                OverviewScreen(
                    state = overviewState(),
                    onRunCycleNow = {},
                    onSetKillSwitch = {},
                    onOpenSensorLagAnalytics = { clicked = true }
                )
            }
        }

        composeRule.onNodeWithContentDescription(verdictText).assertIsDisplayed().performClick()
        composeRule.runOnIdle {
            assertTrue(clicked)
        }
    }

    @Test
    fun analyticsScreen_openRequestShowsDetailAndSwitchesToActive() {
        var requestedMode: String? = null
        var detailHandled = false
        val context = InstrumentationRegistry.getInstrumentation().targetContext

        composeRule.setContent {
            AapsCopilotTheme {
                AnalyticsScreen(
                    state = analyticsState(),
                    onSensorLagCorrectionModeChange = { requestedMode = it },
                    openSensorLagTrendDetailRequest = true,
                    onSensorLagTrendDetailHandled = { detailHandled = true }
                )
            }
        }

        composeRule.onNodeWithText(
            context.getString(R.string.analytics_sensor_lag_detail_title)
        ).assertIsDisplayed()
        composeRule.runOnIdle {
            assertTrue(detailHandled)
        }
        composeRule.onNodeWithText(
            context.getString(R.string.analytics_sensor_lag_action_active)
        ).assertIsDisplayed().performClick()
        composeRule.runOnIdle {
            assertEquals("ACTIVE", requestedMode)
        }
    }

    @Test
    fun sensorLagNavigationHarness_clickVerdictOpensAnalyticsDetail() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val verdictText = context.getString(
            R.string.overview_sensor_lag_rollout_template,
            context.getString(R.string.analytics_daily_report_sensor_lag_rollout_active),
            context.getString(R.string.analytics_daily_report_sensor_lag_bucket_10_12d)
        )

        composeRule.setContent {
            AapsCopilotTheme {
                SensorLagNavigationHarness()
            }
        }

        composeRule.onNodeWithContentDescription(verdictText).assertIsDisplayed().performClick()
        composeRule.onNodeWithText(
            context.getString(R.string.analytics_sensor_lag_detail_title)
        ).assertIsDisplayed()
    }

    private fun overviewState(): OverviewUiState {
        return OverviewUiState(
            loadState = ScreenLoadState.READY,
            isStale = false,
            glucose = 8.7,
            correctedGlucose = 9.0,
            delta = 0.24,
            sampleAgeMinutes = 7,
            sensorLagMode = "SHADOW",
            sensorLagMinutes = 14.0,
            sensorLagRolloutVerdict = SensorLagRolloutVerdictUi(
                status = "ACTIVE_CANDIDATE",
                bucket = "10-12d"
            ),
            horizons = listOf(
                HorizonPredictionUi(5, 8.92, 8.60, 9.22),
                HorizonPredictionUi(30, 9.78, 8.10, 10.95),
                HorizonPredictionUi(60, 10.26, 8.05, 12.12, warningWideCi = true)
            ),
            canRunCycleNow = true,
            killSwitchEnabled = false
        )
    }

    private fun analyticsState(): AnalyticsUiState {
        val now = System.currentTimeMillis()
        return AnalyticsUiState(
            loadState = ScreenLoadState.READY,
            isStale = false,
            sensorLagDiagnostics = SensorLagDiagnosticsUi(
                configuredMode = "SHADOW",
                runtimeMode = "SHADOW",
                rawGlucoseMmol = 7.0,
                correctedGlucoseMmol = 7.4,
                correctionMmol = 0.4,
                lagMinutes = 16.0,
                ageHours = 260.0,
                ageSource = "explicit",
                confidence = 0.84,
                disableReason = null,
                sensorQualityScore = 0.82,
                sensorQualityBlocked = false,
                sensorQualitySuspectFalseLow = false,
                lagTrendPoints = listOf(
                    ChartPointUi(now - 60 * 60_000L, 14.0),
                    ChartPointUi(now, 16.0)
                ),
                correctionTrendPoints = listOf(
                    ChartPointUi(now - 60 * 60_000L, 0.2),
                    ChartPointUi(now, 0.4)
                ),
                trendStartAgeHours = 236.0,
                trendEndAgeHours = 260.0
            ),
            dailyReportSensorLagReplayBuckets = listOf(
                DailyReportSensorLagReplayUi(
                    horizonMinutes = 30,
                    bucket = "10-12d",
                    sampleCount = 5,
                    rawMae = 0.42,
                    lagMae = 0.28,
                    maeImprovementMmol = 0.14,
                    rawBias = 0.10,
                    lagBias = 0.04
                ),
                DailyReportSensorLagReplayUi(
                    horizonMinutes = 60,
                    bucket = "10-12d",
                    sampleCount = 5,
                    rawMae = 0.58,
                    lagMae = 0.46,
                    maeImprovementMmol = 0.12,
                    rawBias = 0.16,
                    lagBias = 0.07
                ),
                DailyReportSensorLagReplayUi(
                    horizonMinutes = 30,
                    bucket = "12-14d",
                    sampleCount = 4,
                    rawMae = 0.47,
                    lagMae = 0.41,
                    maeImprovementMmol = 0.06,
                    rawBias = 0.14,
                    lagBias = 0.10
                )
            ),
            dailyReportSensorLagShadowBuckets = listOf(
                DailyReportSensorLagShadowUi(
                    bucket = "10-12d",
                    sampleCount = 10,
                    ruleChangedRatePct = 12.0,
                    meanAbsTargetDeltaMmol = 0.20
                ),
                DailyReportSensorLagShadowUi(
                    bucket = "12-14d",
                    sampleCount = 6,
                    ruleChangedRatePct = 9.0,
                    meanAbsTargetDeltaMmol = 0.18
                )
            )
        )
    }

    @Composable
    private fun SensorLagNavigationHarness() {
        val navController = rememberNavController()
        var openSensorLagTrendDetailRequest by remember { mutableStateOf(false) }

        NavHost(navController = navController, startDestination = "overview") {
            composable("overview") {
                OverviewScreen(
                    state = overviewState(),
                    onRunCycleNow = {},
                    onSetKillSwitch = {},
                    onOpenSensorLagAnalytics = {
                        openSensorLagTrendDetailRequest = true
                        navController.navigate("analytics")
                    }
                )
            }
            composable("analytics") {
                AnalyticsScreen(
                    state = analyticsState(),
                    onSensorLagCorrectionModeChange = {},
                    openSensorLagTrendDetailRequest = openSensorLagTrendDetailRequest,
                    onSensorLagTrendDetailHandled = {
                        openSensorLagTrendDetailRequest = false
                    }
                )
            }
        }
    }
}
