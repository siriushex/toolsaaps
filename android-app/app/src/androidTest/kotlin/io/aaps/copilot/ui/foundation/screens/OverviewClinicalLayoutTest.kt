package io.aaps.copilot.ui.foundation.screens

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performCustomAccessibilityActionWithLabel
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.test.espresso.Espresso.pressBack
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.aaps.copilot.R
import io.aaps.copilot.domain.profile.MealAbsorptionProfile
import io.aaps.copilot.ui.foundation.theme.AapsCopilotTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@RunWith(AndroidJUnit4::class)
class OverviewClinicalLayoutTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun energyActivityStatusRowIsExceptionOnly() {
        composeRule.setContent {
            AapsCopilotTheme {
                OverviewScreen(
                    state = clinicalState(),
                    onRunCycleNow = {},
                    onSetKillSwitch = {},
                    onDisablePowerSave = {},
                    onAddBloodCheck = { _, _, _, _ -> },
                    onOpenClinicalReport = {}
                )
            }
        }
        composeRule.onNodeWithTag("energy_activity_status").assertDoesNotExist()

        composeRule.setContent {
            AapsCopilotTheme {
                OverviewScreen(
                    state = clinicalState().copy(
                        energyActivityStatus = EnergyActivityStatusUi(
                            kind = EnergyActivityStatusKind.PROFILE_ISSUE
                        )
                    ),
                    onRunCycleNow = {},
                    onSetKillSwitch = {},
                    onDisablePowerSave = {},
                    onAddBloodCheck = { _, _, _, _ -> },
                    onOpenClinicalReport = {}
                )
            }
        }
        composeRule.onNodeWithTag("energy_activity_status")
            .assertIsDisplayed()
    }

    @Test
    fun normalOverview_showsClinicalHierarchyWithoutDuplicateGlucoseOrHealthCopy() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val state = clinicalState().copy(
            glucose = 7.9,
            delta = -0.2,
            sampleAgeMinutes = 0,
            currentIobUnits = 8.2,
            currentCobGrams = 47.9,
            currentIsfMmolPerUnit = 4.4,
            currentCrGramsPerUnit = 10.0,
            calculatedUamCarbsGrams = null,
            calculatedUamConfidence = 0.47,
            uamExport = UamExportControlUi(mode = "OBSERVE"),
            effectiveBaseTargetMmol = 5.4,
            horizons = listOf(HorizonPredictionUi(30, 2.3, 2.2, 5.1))
        )

        composeRule.setContent {
            AapsCopilotTheme {
                OverviewScreen(
                    state = state,
                    onRunCycleNow = {},
                    onSetKillSwitch = {},
                    onDisablePowerSave = {},
                    onAddBloodCheck = { _, _, _, _ -> },
                    onOpenClinicalReport = {}
                )
            }
        }

        composeRule.onNodeWithText(context.getString(R.string.overview_glucose_now)).assertIsDisplayed()
        composeRule.onNodeWithTag("overviewCompactHero").assertIsDisplayed()
        composeRule.onNodeWithTag("overviewClinicalMatrix").assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.overview_trend_short)).assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.overview_model_isf_now)).assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.overview_model_cr_now)).assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.overview_model_uam)).assertIsDisplayed()
        composeRule.onNodeWithText(
            context.getString(
                R.string.overview_uam_mode_confidence,
                context.getString(R.string.uam_mode_observe_short),
                "47%"
            )
        ).assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.overview_current_above_target)).assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.overview_forecast_low)).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(context.getString(R.string.overview_calibration_action))
            .assertIsDisplayed()
        composeRule.onAllNodesWithText(context.getString(R.string.overview_model_isf_now))
            .assertCountEquals(1)
        composeRule.onAllNodesWithText(context.getString(R.string.overview_model_cr_now))
            .assertCountEquals(1)
        composeRule.onAllNodesWithText(context.getString(R.string.overview_model_uam))
            .assertCountEquals(1)
        composeRule.onNodeWithText(context.getString(R.string.app_health_title)).assertDoesNotExist()
        composeRule.onNodeWithText(context.getString(R.string.overview_calibration_title)).assertDoesNotExist()

        composeRule.onNodeWithText(context.getString(R.string.overview_chart_title))
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithTag("overviewInteractiveChart").assertIsDisplayed()
        composeRule.onNodeWithContentDescription(context.getString(R.string.overview_chart_reset_view))
            .assertIsDisplayed()
        listOf(3, 6, 12, 24).forEach { hours ->
            composeRule.onNodeWithText(context.getString(R.string.overview_range_hours, hours))
                .assertDoesNotExist()
        }
    }

    @Test
    fun currentStatusCell_opensCompensationAnalysisExactlyOnce() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        var callbackCount = 0

        composeRule.setContent {
            AapsCopilotTheme {
                OverviewScreen(
                    state = clinicalState(),
                    onRunCycleNow = {},
                    onSetKillSwitch = {},
                    onDisablePowerSave = {},
                    onAddBloodCheck = { _, _, _, _ -> },
                    onOpenClinicalReport = { callbackCount += 1 }
                )
            }
        }

        composeRule.onNodeWithContentDescription(
            context.getString(R.string.overview_open_compensation_analysis)
        )
            .performScrollTo()
            .assertHasClickAction()
            .performClick()

        composeRule.runOnIdle { assertEquals(1, callbackCount) }
    }

    @Test
    fun sampleAgeCell_opensExistingBloodCheckDialogWithAccessibleClickSemantics() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext

        composeRule.setContent {
            AapsCopilotTheme {
                OverviewScreen(
                    state = clinicalState(),
                    onRunCycleNow = {},
                    onSetKillSwitch = {},
                    onDisablePowerSave = {},
                    onAddBloodCheck = { _, _, _, _ -> },
                    onOpenClinicalReport = {}
                )
            }
        }

        composeRule.onNodeWithContentDescription(
            context.getString(R.string.overview_sample_age_action_description)
        )
            .performScrollTo()
            .assertHasClickAction()
            .performClick()

        composeRule.onNodeWithTag("bloodCheckDialog").assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.overview_blood_check_dialog_title))
            .assertIsDisplayed()
    }

    @Test
    fun bloodCheckDialog_cancelDoesNotInvokeCallbackAndBloodDropStillOpensIt() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        var callbackCount = 0

        composeRule.setContent {
            AapsCopilotTheme {
                OverviewScreen(
                    state = clinicalState(),
                    onRunCycleNow = {},
                    onSetKillSwitch = {},
                    onDisablePowerSave = {},
                    onAddBloodCheck = { _, _, _, _ -> callbackCount += 1 },
                    onOpenClinicalReport = {}
                )
            }
        }

        composeRule.onNodeWithContentDescription(context.getString(R.string.overview_calibration_action))
            .performClick()
        composeRule.onNodeWithTag("bloodCheckDialog").assertIsDisplayed()
        composeRule.onNodeWithTag("bloodCheckCancel").performClick()
        composeRule.runOnIdle { assertEquals(0, callbackCount) }

        composeRule.onNodeWithContentDescription(context.getString(R.string.overview_calibration_action))
            .performClick()
        composeRule.onNodeWithTag("bloodCheckDialog").assertIsDisplayed()
        pressBack()
        composeRule.onNodeWithTag("bloodCheckDialog").assertDoesNotExist()
        composeRule.runOnIdle { assertEquals(0, callbackCount) }

        composeRule.onNodeWithContentDescription(context.getString(R.string.overview_calibration_action))
            .performClick()
        composeRule.onNodeWithTag("bloodCheckDialog").assertIsDisplayed()
    }

    @Test
    fun bloodCheckDialog_disablesSaveForNonFiniteAndOutOfRangeValuesInBothUnits() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext

        composeRule.setContent {
            AapsCopilotTheme {
                OverviewScreen(
                    state = clinicalState(),
                    onRunCycleNow = {},
                    onSetKillSwitch = {},
                    onDisablePowerSave = {},
                    onAddBloodCheck = { _, _, _, _ -> },
                    onOpenClinicalReport = {}
                )
            }
        }

        composeRule.onNodeWithContentDescription(
            context.getString(R.string.overview_sample_age_action_description)
        ).performClick()

        val value = composeRule.onNodeWithTag("bloodCheckValue")
        val save = composeRule.onNodeWithTag("bloodCheckSave")
        listOf("NaN", "Infinity", "5,6,7", "5,6.7", "2.1", "22.1").forEach { invalid ->
            value.performTextReplacement(invalid)
            save.assertIsNotEnabled()
        }

        composeRule.onNodeWithTag("bloodCheckUnitsMgdl").performClick()
        listOf("NaN", "Infinity", "39", "397").forEach { invalid ->
            value.performTextReplacement(invalid)
            save.assertIsNotEnabled()
        }
    }

    @Test
    fun bloodCheckDialog_validConfirmInvokesCallbackExactlyOnceWithAllFields() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val calls = mutableListOf<List<Any>>()
        val timestampText = "2026-07-26 09:15"
        val expectedTimestamp = LocalDateTime.parse(
            timestampText,
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
        ).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

        composeRule.setContent {
            AapsCopilotTheme {
                OverviewScreen(
                    state = clinicalState(),
                    onRunCycleNow = {},
                    onSetKillSwitch = {},
                    onDisablePowerSave = {},
                    onAddBloodCheck = { value, units, timestamp, note ->
                        calls += listOf(value, units, timestamp, note)
                    },
                    onOpenClinicalReport = {}
                )
            }
        }

        composeRule.onNodeWithContentDescription(
            context.getString(R.string.overview_sample_age_action_description)
        ).performClick()
        composeRule.onNodeWithTag("bloodCheckValue").performTextReplacement("5,6")
        composeRule.onNodeWithTag("bloodCheckTimestamp").performTextReplacement(timestampText)
        composeRule.onNodeWithTag("bloodCheckNote").performTextReplacement("meter check")
        composeRule.onNodeWithTag("bloodCheckSave").assertIsEnabled().performClick()

        composeRule.runOnIdle {
            assertEquals(1, calls.size)
            assertEquals(listOf("5.6", "mmol/L", expectedTimestamp, "meter check"), calls.single())
            assertTrue(calls.single()[2] is Long)
        }
    }

    @Test
    fun bloodCheckDialog_unitsExposeSingleChoiceRadioSemantics() {
        composeRule.setContent {
            AapsCopilotTheme {
                BloodCheckDialog(onDismiss = {}, onConfirm = { _, _, _, _ -> })
            }
        }

        val mmol = composeRule.onNodeWithTag("bloodCheckUnitsMmol")
        val mgdl = composeRule.onNodeWithTag("bloodCheckUnitsMgdl")
        val radioRole = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton)

        mmol.assert(radioRole).assertIsSelected().assertHasClickAction()
        mgdl.assert(radioRole).assertIsNotSelected().assertHasClickAction().performClick()
        mmol.assertIsNotSelected()
        mgdl.assertIsSelected()
    }

    @Test
    fun bloodCheckDialog_resetRequiresConfirmationAndInvokesCallbackOnce() {
        var resetCount = 0
        composeRule.setContent {
            AapsCopilotTheme {
                BloodCheckDialog(
                    onDismiss = {},
                    onConfirm = { _, _, _, _ -> },
                    resetEnabled = true,
                    onResetCalibration = { resetCount += 1 }
                )
            }
        }

        composeRule.onNodeWithTag("bloodCheckReset").assertIsEnabled().performClick()
        composeRule.onNodeWithTag("bloodCheckResetConfirmation").assertIsDisplayed()
        composeRule.runOnIdle { assertEquals(0, resetCount) }
        composeRule.onNodeWithTag("bloodCheckResetConfirm").performClick()
        composeRule.runOnIdle { assertEquals(1, resetCount) }
    }

    @Test
    fun bloodCheckDialog_restoresVisibilityEnteredFieldsAndSelectedUnits() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val restorationTester = StateRestorationTester(composeRule)

        restorationTester.setContent {
            AapsCopilotTheme {
                OverviewScreen(
                    state = clinicalState(),
                    onRunCycleNow = {},
                    onSetKillSwitch = {},
                    onDisablePowerSave = {},
                    onAddBloodCheck = { _, _, _, _ -> },
                    onOpenClinicalReport = {}
                )
            }
        }

        composeRule.onNodeWithContentDescription(
            context.getString(R.string.overview_sample_age_action_description)
        ).performClick()
        composeRule.onNodeWithTag("bloodCheckValue").performTextReplacement("104")
        composeRule.onNodeWithTag("bloodCheckUnitsMgdl").performClick()
        composeRule.onNodeWithTag("bloodCheckTimestamp").performTextReplacement("2026-07-26 09:15")
        composeRule.onNodeWithTag("bloodCheckNote").performTextReplacement("restore me")

        restorationTester.emulateSavedInstanceStateRestore()

        composeRule.onNodeWithTag("bloodCheckDialog").assertIsDisplayed()
        composeRule.onNodeWithTag("bloodCheckValue").assertTextContains("104")
        composeRule.onNodeWithTag("bloodCheckUnitsMmol").assertIsNotSelected()
        composeRule.onNodeWithTag("bloodCheckUnitsMgdl").assertIsSelected()
        composeRule.onNodeWithTag("bloodCheckTimestamp").assertTextContains("2026-07-26 09:15")
        composeRule.onNodeWithTag("bloodCheckNote").assertTextContains("restore me")
    }

    @Test
    fun bloodCheckDialog_submittedStateRestoresWithoutRepeatingCallback() {
        val restorationTester = StateRestorationTester(composeRule)
        var callbackCount = 0

        restorationTester.setContent {
            AapsCopilotTheme {
                BloodCheckDialog(
                    onDismiss = {},
                    onConfirm = { _, _, _, _ -> callbackCount += 1 }
                )
            }
        }

        composeRule.onNodeWithTag("bloodCheckValue").performTextReplacement("5,6")
        composeRule.onNodeWithTag("bloodCheckSave").assertIsEnabled().performClick()
        composeRule.runOnIdle { assertEquals(1, callbackCount) }
        composeRule.onNodeWithTag("bloodCheckSave").assertIsNotEnabled()

        restorationTester.emulateSavedInstanceStateRestore()

        composeRule.onNodeWithTag("bloodCheckDialog").assertIsDisplayed()
        composeRule.onNodeWithTag("bloodCheckSave").assertIsNotEnabled()
        composeRule.runOnIdle { assertEquals(1, callbackCount) }
    }

    @Test
    fun overviewTargetEditor_emitsSelectedValue() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        var selectedTarget: Double? = null

        composeRule.setContent {
            AapsCopilotTheme {
                OverviewScreen(
                    state = clinicalState(),
                    onRunCycleNow = {},
                    onSetKillSwitch = {},
                    onDisablePowerSave = {},
                    onAddBloodCheck = { _, _, _, _ -> },
                    onOpenClinicalReport = {},
                    onBaseTargetScheduleSave = { selectedTarget = it.defaultTargetMmol }
                )
            }
        }

        composeRule.onNodeWithText(context.getString(R.string.overview_model_uam))
            .performScrollTo()
        composeRule.onNode(hasScrollAction()).performTouchInput { swipeUp() }
        composeRule.onNodeWithContentDescription(context.getString(R.string.overview_target_edit))
            .performClick()
        composeRule.onNodeWithTag("baseTargetScheduleDialog").assertIsDisplayed()
        composeRule.onNodeWithTag("baseTargetScheduleSave").performClick()
        composeRule.runOnIdle { assertEquals(5.5, selectedTarget ?: 0.0, 0.001) }
    }

    @Test
    fun cobEditor_passesSelectedFoodProfileOnlyAfterConfirmation() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        var sentCarbs: Pair<String, String>? = null
        var sentProfile: MealAbsorptionProfile? = null
        var sentCalories: Double? = Double.NaN

        composeRule.setContent {
            AapsCopilotTheme {
                OverviewScreen(
                    state = clinicalState(),
                    onRunCycleNow = {},
                    onSetKillSwitch = {},
                    onDisablePowerSave = {},
                    onAddBloodCheck = { _, _, _, _ -> },
                    onOpenClinicalReport = {},
                    onManualCarbs = { grams, reason, profile, caloriesKcal, _, _ ->
                        sentCarbs = grams to reason
                        sentProfile = profile
                        sentCalories = caloriesKcal
                    }
                )
            }
        }

        composeRule.onNodeWithContentDescription(context.getString(R.string.overview_edit_cob))
            .performScrollTo()
            .performClick()
        composeRule.onNodeWithText(context.getString(R.string.overview_cob_preset, 20)).performClick()
        val fastProfile = composeRule.onNodeWithContentDescription(
            context.getString(R.string.food_profile_fast_content_description)
        )
        val mixedProfile = composeRule.onNodeWithContentDescription(
            context.getString(R.string.food_profile_mixed_content_description)
        )
        val radioRole = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton)
        fastProfile.assert(radioRole).assertIsNotSelected().assertHasClickAction().performClick()
        fastProfile.assert(radioRole).assertIsSelected()
        mixedProfile.assert(radioRole).assertIsNotSelected()
        composeRule.onNodeWithText(context.getString(R.string.overview_add_carbs, "20.0")).performClick()
        composeRule.runOnIdle { assertEquals(null, sentCarbs) }
        composeRule.onNodeWithText(context.getString(R.string.action_confirm)).performClick()
        composeRule.runOnIdle {
            assertEquals("20.0", sentCarbs?.first)
            assertEquals("overview_cob", sentCarbs?.second)
            assertEquals(MealAbsorptionProfile.FAST, sentProfile)
            assertEquals(null, sentCalories)
        }
    }

    @Test
    fun cobEditor_passesOptionalMealEnergyOnlyAfterConfirmationAndCancellationDoesNothing() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        var callbackCount = 0
        var sentCalories: Double? = null

        composeRule.setContent {
            AapsCopilotTheme {
                OverviewScreen(
                    state = clinicalState(),
                    onRunCycleNow = {},
                    onSetKillSwitch = {},
                    onDisablePowerSave = {},
                    onAddBloodCheck = { _, _, _, _ -> },
                    onOpenClinicalReport = {},
                    onManualCarbs = { _, _, _, caloriesKcal, _, _ ->
                        callbackCount += 1
                        sentCalories = caloriesKcal
                    }
                )
            }
        }

        composeRule.onNodeWithContentDescription(context.getString(R.string.overview_edit_cob))
            .performScrollTo()
            .performClick()
        composeRule.onNodeWithTag("overviewManualMealEnergyKcal")
            .performTextReplacement("540")
        composeRule.onNodeWithText(context.getString(R.string.overview_add_carbs, "10.0"))
            .performClick()
        composeRule.onNodeWithText(context.getString(R.string.action_cancel)).performClick()
        composeRule.runOnIdle { assertEquals(0, callbackCount) }
        composeRule.onNodeWithTag("overviewManualMealEnergyKcal").assertTextEquals("540")

        composeRule.onNodeWithText(context.getString(R.string.overview_add_carbs, "10.0"))
            .performClick()
        composeRule.onNodeWithText(context.getString(R.string.action_confirm)).performClick()
        composeRule.runOnIdle {
            assertEquals(1, callbackCount)
            assertEquals(540.0, sentCalories ?: 0.0, 0.001)
        }

        composeRule.onNodeWithContentDescription(context.getString(R.string.overview_edit_cob))
            .performScrollTo()
            .performClick()
        composeRule.onNodeWithTag("overviewManualMealEnergyKcal").assertTextEquals("")
        composeRule.onNodeWithTag("overviewManualMealEnergyKcal")
            .performTextReplacement("600")
        composeRule.onNodeWithText(context.getString(R.string.action_cancel)).performClick()

        composeRule.onNodeWithContentDescription(context.getString(R.string.overview_edit_cob))
            .performScrollTo()
            .performClick()
        composeRule.onNodeWithTag("overviewManualMealEnergyKcal").assertTextEquals("")
    }

    @Test
    fun iobDialog_cancelDoesNotLaunchAndConfirmLaunchesExactlyOnceWithoutDose() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        var launchCount = 0

        composeRule.setContent {
            AapsCopilotTheme {
                OverviewScreen(
                    state = clinicalState().copy(currentIobUnits = 2.1),
                    onRunCycleNow = {},
                    onSetKillSwitch = {},
                    onDisablePowerSave = {},
                    onAddBloodCheck = { _, _, _, _ -> },
                    onOpenClinicalReport = {},
                    onOpenAapsBolus = { launchCount += 1 }
                )
            }
        }

        val iobCell = composeRule.onNodeWithContentDescription(
            context.getString(R.string.overview_iob_action_description)
        )
        iobCell.performClick()
        composeRule.onNodeWithText(context.getString(R.string.overview_iob_dialog_title))
            .assertIsDisplayed()
        composeRule.onNodeWithText(
            context.getString(R.string.overview_iob_current_value, "2.1", context.getString(R.string.unit_u))
        ).assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.action_cancel)).performClick()
        composeRule.runOnIdle { assertEquals(0, launchCount) }

        iobCell.performClick()
        composeRule.onNodeWithText(context.getString(R.string.overview_iob_open_aaps)).performClick()
        composeRule.runOnIdle { assertEquals(1, launchCount) }
    }

    @Test
    fun iobDialogShowsFullSignedAtomicRuntimeDetailsWithoutZeroFilling() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val details = IobRuntimeDetailsUi(
            effectivePositiveIobUnits = 0.4,
            signedNetIobUnits = -0.5,
            bolusIobUnits = 0.4,
            basalIobUnits = -0.9,
            insulinActivity = 0.012,
            actualSource = "AAPS_COMPONENTS",
            sampleTimestamp = 1_800_000_000_000L,
            sampleAgeMinutes = 2L,
            confidence = 0.93,
            evidenceTimestamp = 1_799_999_940_000L,
            therapyCoverage = 0.87,
            fallbackReason = "aaps_components_partial"
        )
        composeRule.setContent {
            AapsCopilotTheme {
                OverviewScreen(
                    state = clinicalState().copy(currentIobUnits = 0.4, iobDetails = details),
                    onRunCycleNow = {},
                    onSetKillSwitch = {},
                    onDisablePowerSave = {},
                    onAddBloodCheck = { _, _, _, _ -> },
                    onOpenClinicalReport = {}
                )
            }
        }

        composeRule.onNodeWithContentDescription(context.getString(R.string.overview_iob_action_description))
            .performClick()

        composeRule.onNodeWithText(context.getString(R.string.overview_iob_net_value, "-0.5"))
            .assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.overview_iob_bolus_value, "0.4"))
            .assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.overview_iob_basal_value, "-0.9"))
            .assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.overview_iob_activity_value, "0.012"))
            .assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.overview_iob_source_value, "AAPS_COMPONENTS"))
            .assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.overview_iob_sample_age_value, "2"))
            .assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.overview_iob_confidence_value, "93%"))
            .assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.overview_iob_coverage_value, "87%"))
            .assertIsDisplayed()
        composeRule.onNodeWithText(
            context.getString(R.string.overview_iob_fallback_reason_value, "aaps_components_partial")
        ).assertIsDisplayed()
    }

    @Test
    fun uamCell_opensSharedSelectorAndObserveChangesImmediately() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val selectedModes = mutableListOf<String>()

        composeRule.setContent {
            AapsCopilotTheme {
                OverviewScreen(
                    state = clinicalState().copy(
                        uamExport = UamExportControlUi(mode = "OFF")
                    ),
                    onRunCycleNow = {},
                    onSetKillSwitch = {},
                    onDisablePowerSave = {},
                    onAddBloodCheck = { _, _, _, _ -> },
                    onOpenClinicalReport = {},
                    onUamExportUiModeChange = { selectedModes += it }
                )
            }
        }

        composeRule.onNodeWithContentDescription(context.getString(R.string.overview_uam_action_description))
            .performScrollTo()
            .performClick()
        composeRule.onNodeWithTag("uamExportModeDialog").assertIsDisplayed()
        composeRule.onNodeWithTag("uamExportMode_OBSERVE").performClick()

        composeRule.runOnIdle {
            assertEquals(listOf("OBSERVE"), selectedModes)
        }
    }

    @Test
    fun uamDialog_hidesDryRunImplementationReason() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        composeRule.setContent {
            AapsCopilotTheme {
                OverviewScreen(
                    state = clinicalState().copy(
                        uamExport = UamExportControlUi(
                            mode = "OBSERVE",
                            runtimeStatus = UamRuntimeStatusUi.OBSERVATION_ACTIVE
                        )
                    ),
                    onRunCycleNow = {},
                    onSetKillSwitch = {},
                    onDisablePowerSave = {},
                    onAddBloodCheck = { _, _, _, _ -> },
                    onOpenClinicalReport = {}
                )
            }
        }

        composeRule.onNodeWithTag("overviewUamMetric").performScrollTo().performClick()
        composeRule.onNodeWithTag("uamExportModeDialog").assertIsDisplayed()
        composeRule.onNodeWithTag("uamRuntimeStatus").assertIsDisplayed()
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
    }

    @Test
    fun uamDialog_showsLocalizedFreshRestrictionWithoutRawCode() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        composeRule.setContent {
            AapsCopilotTheme {
                OverviewScreen(
                    state = clinicalState().copy(
                        uamExport = UamExportControlUi(
                            mode = "AUTO",
                            runtimeStatus = UamRuntimeStatusUi.ACTIVE,
                            exportBlockedStatus = UamRuntimeStatusUi.MANUAL_CARBS
                        )
                    ),
                    onRunCycleNow = {},
                    onSetKillSwitch = {},
                    onDisablePowerSave = {},
                    onAddBloodCheck = { _, _, _, _ -> },
                    onOpenClinicalReport = {}
                )
            }
        }

        composeRule.onNodeWithTag("overviewUamMetric").performScrollTo().performClick()
        composeRule.onNodeWithText(
            context.getString(
                R.string.uam_mode_export_restriction,
                context.getString(R.string.uam_runtime_manual_carbs)
            )
        ).assertIsDisplayed()
        composeRule.onNodeWithText("manual_carbs_nearby", substring = true)
            .assertDoesNotExist()
    }

    @Test
    fun uamAuto_requiresConfirmationCancelDoesNotMutateAndConfirmEmitsOnce() {
        val selectedModes = mutableListOf<String>()

        composeRule.setContent {
            AapsCopilotTheme {
                OverviewScreen(
                    state = clinicalState().copy(
                        uamExport = UamExportControlUi(mode = "OBSERVE")
                    ),
                    onRunCycleNow = {},
                    onSetKillSwitch = {},
                    onDisablePowerSave = {},
                    onAddBloodCheck = { _, _, _, _ -> },
                    onOpenClinicalReport = {},
                    onUamExportUiModeChange = { selectedModes += it }
                )
            }
        }

        composeRule.onNodeWithTag("overviewUamMetric").performScrollTo().performClick()
        composeRule.onNodeWithTag("uamExportMode_AUTO").performClick()
        composeRule.onNodeWithTag("uamAutoConfirmDialog").assertIsDisplayed()
        composeRule.onNodeWithTag("uamAutoConfirmCancel").performClick()
        composeRule.runOnIdle { assertEquals(emptyList<String>(), selectedModes) }

        composeRule.onNodeWithTag("uamExportMode_AUTO").performClick()
        composeRule.onNodeWithTag("uamAutoConfirmAccept").performClick()
        composeRule.runOnIdle { assertEquals(listOf("AUTO"), selectedModes) }
    }

    @Test
    fun isfAndCrEditors_forwardTheirSelectedSourceIndependently() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        var isfSource: String? = null
        var crSource: String? = null

        composeRule.setContent {
            AapsCopilotTheme {
                OverviewScreen(
                    state = clinicalState(),
                    onRunCycleNow = {},
                    onSetKillSwitch = {},
                    onDisablePowerSave = {},
                    onAddBloodCheck = { _, _, _, _ -> },
                    onOpenClinicalReport = {},
                    onIsfRuntimeSourceChange = { isfSource = it },
                    onCrRuntimeSourceChange = { crSource = it }
                )
            }
        }

        composeRule.onNodeWithContentDescription(context.getString(R.string.overview_edit_isf))
            .performScrollTo()
            .performClick()
        composeRule.onNodeWithText(context.getString(R.string.overview_isf_source_title)).assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.overview_source_aaps)).performClick()
        composeRule.runOnIdle { assertEquals("AAPS", isfSource) }

        composeRule.onNodeWithContentDescription(context.getString(R.string.overview_edit_cr)).performClick()
        composeRule.onNodeWithText(context.getString(R.string.overview_cr_source_title)).assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.overview_source_copilot)).performClick()
        composeRule.runOnIdle { assertEquals("COPILOT", crSource) }
    }

    @Test
    fun matrixAndSensitivityDialogShowActualAcceptedSourceFallbackChainAndIdentity() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val runtime = MetricRuntimeSourceUi(
            requested = "EVIDENCE",
            resolved = "COPILOT_NATIVE",
            selectedValue = 3.1,
            aapsValue = null,
            evidenceValue = 4.2,
            copilotValue = 3.1,
            confidence = 0.61,
            fallbackActive = true,
            availability = MetricRuntimeAvailabilityUi.AVAILABLE,
            actualResolvedSource = "COPILOT",
            fallbackPath = listOf("EVIDENCE", "COPILOT"),
            fallbackReason = "evidence_quality_failed",
            acceptedSettingsRevision = 42L,
            acceptedCycleId = "accepted-overview-cycle",
            acceptedTimestamp = 1_800_000_060_000L,
            acceptedAgeMinutes = 2L,
            acceptedFresh = true,
            candidates = listOf(
                MetricCandidateDiagnosticsUi(source = "AAPS", value = null),
                MetricCandidateDiagnosticsUi(
                    source = "EVIDENCE",
                    value = 4.2,
                    diagnosticsAvailable = true,
                    timestamp = 1_799_999_880_000L,
                    ageMinutesAtDecision = 2L,
                    freshAtDecision = true,
                    confidence = 0.72,
                    sampleCount = 18,
                    coverage = 0.72,
                    qualityPassed = false,
                    unavailableReason = "quality_failed"
                ),
                MetricCandidateDiagnosticsUi(source = "COPILOT", value = 3.1)
            )
        )
        composeRule.setContent {
            AapsCopilotTheme {
                OverviewScreen(
                    state = clinicalState().copy(
                        currentIsfMmolPerUnit = 3.1,
                        isfRuntime = runtime
                    ),
                    onRunCycleNow = {},
                    onSetKillSwitch = {},
                    onDisablePowerSave = {},
                    onAddBloodCheck = { _, _, _, _ -> },
                    onOpenClinicalReport = {}
                )
            }
        }

        composeRule.onNodeWithText(
            "${context.getString(R.string.overview_model_isf_now)} · ${context.getString(R.string.overview_source_copilot)}"
        ).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(context.getString(R.string.overview_edit_isf))
            .performScrollTo()
            .performClick()

        composeRule.onNodeWithText(
            context.getString(R.string.overview_source_requested, context.getString(R.string.overview_source_evidence))
        ).assertIsDisplayed()
        composeRule.onNodeWithText(
            context.getString(R.string.overview_source_effective, "3.1 ${context.getString(R.string.unit_mmol_u)}")
        ).assertIsDisplayed()
        composeRule.onNodeWithText(
            context.getString(R.string.overview_source_actual, context.getString(R.string.overview_source_copilot))
        ).assertIsDisplayed()
        composeRule.onNodeWithText(
            context.getString(R.string.overview_source_fallback_chain, "Evidence unavailable -> Copilot")
        ).assertIsDisplayed()
        composeRule.onNodeWithText(
            context.getString(R.string.overview_source_fallback_reason, "evidence_quality_failed")
        ).assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.overview_source_revision, 42L))
            .assertIsDisplayed()
        composeRule.onNodeWithText(
            context.getString(R.string.overview_source_cycle, "accepted-overview-cycle")
        ).assertIsDisplayed()
        composeRule.onNodeWithText(
            context.getString(R.string.overview_source_candidate_samples, 18)
        ).assertIsDisplayed()
        composeRule.onNodeWithText(
            context.getString(R.string.overview_source_candidate_unavailable_reason, "quality_failed")
        ).assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.overview_source_analytics))
            .assertHasClickAction()
    }

    @Test
    @OptIn(ExperimentalTestApi::class)
    fun interactiveChart_exposesAndRunsAllAccessibilityActions() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext

        composeRule.setContent {
            AapsCopilotTheme {
                OverviewScreen(
                    state = clinicalState(),
                    onRunCycleNow = {},
                    onSetKillSwitch = {},
                    onDisablePowerSave = {},
                    onAddBloodCheck = { _, _, _, _ -> },
                    onOpenClinicalReport = {}
                )
            }
        }

        val chart = composeRule.onNodeWithTag("overviewInteractiveChart")
        chart.performScrollTo()
        listOf(
            R.string.overview_chart_zoom_in,
            R.string.overview_chart_zoom_out,
            R.string.overview_chart_older,
            R.string.overview_chart_newer,
            R.string.overview_chart_reset_view
        ).forEach { label ->
            chart.performCustomAccessibilityActionWithLabel(context.getString(label))
        }
    }

    @Test
    fun activeLowAlert_doesNotDuplicateAlertControlsInsideOverview() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext

        composeRule.setContent {
            AapsCopilotTheme {
                OverviewScreen(
                    state = clinicalState().copy(
                        glucose = 4.2,
                        glucoseAlertState = "LOW_NOW",
                        glucoseAlertDirection = "LOW",
                        glucoseAlertStrongActive = true
                    ),
                    onRunCycleNow = {},
                    onSetKillSwitch = {},
                    onDisablePowerSave = {},
                    onAddBloodCheck = { _, _, _, _ -> },
                    onOpenClinicalReport = {}
                )
            }
        }

        composeRule.onNodeWithContentDescription(context.getString(R.string.glucose_alert_action_mute_bell_30m))
            .assertDoesNotExist()
        composeRule.onNodeWithText(context.getString(R.string.glucose_alert_state_low_now))
            .assertDoesNotExist()
    }

    @Test
    fun activePowerSaveShowsResumeWhenStoppedRuntimeMakesDataStale() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        var resumed = false

        composeRule.setContent {
            AapsCopilotTheme {
                OverviewScreen(
                    state = clinicalState().copy(
                        isStale = true,
                        warning = OverviewWarningUi(OverviewWarningKind.SENSOR_OR_STALE),
                        powerSaveActive = true,
                        powerSaveIndefinite = true,
                        canRunCycleNow = false
                    ),
                    onRunCycleNow = {},
                    onSetKillSwitch = {},
                    onDisablePowerSave = { resumed = true },
                    onAddBloodCheck = { _, _, _, _ -> },
                    onOpenClinicalReport = {}
                )
            }
        }

        composeRule.onNodeWithText(context.getString(R.string.overview_power_save_turn_on))
            .assertIsDisplayed()
            .performClick()
        composeRule.runOnIdle { assertEquals(true, resumed) }
    }

    @Test
    fun eatingSoonDefaultsOnAndCancelDoesNotSubmit() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        var submissions = 0
        composeRule.setContent {
            AapsCopilotTheme {
                OverviewScreen(
                    state = clinicalState(), onRunCycleNow = {}, onSetKillSwitch = {},
                    onDisablePowerSave = {}, onAddBloodCheck = { _, _, _, _ -> },
                    onManualCarbs = { _, _, _, _, _, _ -> submissions++ },
                    onOpenClinicalReport = {}
                )
            }
        }
        composeRule.onNodeWithContentDescription(context.getString(R.string.overview_edit_cob)).performClick()
        composeRule.onNodeWithTag("overviewEatingSoon").performScrollTo().assertIsOn().performClick().assertIsOff()
        pressBack()
        composeRule.runOnIdle { assertEquals(0, submissions) }
        composeRule.onNodeWithContentDescription(context.getString(R.string.overview_edit_cob)).performClick()
        composeRule.onNodeWithTag("overviewEatingSoon").performScrollTo().assertIsOn()
        composeRule.runOnIdle { assertEquals(0, submissions) }
    }

    @Test
    fun eatingSoonCheckedIsVisibleInFinalConfirmationAndForwardedOnce() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val submissions = mutableListOf<Pair<Boolean, String>>()
        composeRule.setContent {
            AapsCopilotTheme {
                OverviewScreen(
                    state = clinicalState(), onRunCycleNow = {}, onSetKillSwitch = {},
                    onDisablePowerSave = {}, onAddBloodCheck = { _, _, _, _ -> },
                    onManualCarbs = { _, _, _, _, selected, id -> submissions += selected to id },
                    onOpenClinicalReport = {}
                )
            }
        }
        composeRule.onNodeWithContentDescription(context.getString(R.string.overview_edit_cob)).performClick()
        composeRule.onNodeWithTag("overviewPrepareMeal").performClick()
        composeRule.onNodeWithText(context.getString(R.string.overview_confirm_carbs_eating_soon, "10.0"))
            .assertIsDisplayed()
        composeRule.onNodeWithTag("overviewConfirmMeal").performClick()
        composeRule.runOnIdle {
            assertEquals(1, submissions.size)
            assertTrue(submissions.single().first)
            assertTrue(submissions.single().second.isNotBlank())
        }
    }

    @Test
    fun eatingSoonUncheckedSendsOnlyCarbSelection() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val options = mutableListOf<Boolean>()
        composeRule.setContent {
            AapsCopilotTheme {
                OverviewScreen(
                    state = clinicalState(), onRunCycleNow = {}, onSetKillSwitch = {},
                    onDisablePowerSave = {}, onAddBloodCheck = { _, _, _, _ -> },
                    onManualCarbs = { _, _, _, _, selected, _ -> options += selected },
                    onOpenClinicalReport = {}
                )
            }
        }
        composeRule.onNodeWithContentDescription(context.getString(R.string.overview_edit_cob)).performClick()
        composeRule.onNodeWithTag("overviewEatingSoon").performScrollTo().performClick()
        composeRule.onNodeWithTag("overviewPrepareMeal").performClick()
        composeRule.onNodeWithTag("overviewConfirmMeal").performClick()
        composeRule.runOnIdle { assertEquals(listOf(false), options) }
    }

    private fun clinicalState(): OverviewUiState {
        val now = System.currentTimeMillis()
        return OverviewUiState(
            loadState = ScreenLoadState.READY,
            isStale = false,
            glucose = 5.6,
            delta = 0.1,
            sampleAgeMinutes = 1,
            chart = ClinicalForecastChartUiState(
                historyPoints = listOf(
                    ChartPointUi(now - 60L * 60_000L, 5.4),
                    ChartPointUi(now, 5.6)
                ),
                futurePath = listOf(
                    ChartPointUi(now, 5.6),
                    ChartPointUi(now + 30L * 60_000L, 6.2),
                    ChartPointUi(now + 60L * 60_000L, 6.5)
                ),
                futureCi = listOf(
                    ChartCiPointUi(now, 5.4, 5.8),
                    ChartCiPointUi(now + 30L * 60_000L, 5.7, 6.7),
                    ChartCiPointUi(now + 60L * 60_000L, 5.8, 7.2)
                )
            ),
            baseTargetMmol = 5.5,
            currentIobUnits = 1.4,
            currentCobGrams = 18.0,
            currentIsfMmolPerUnit = 2.2,
            currentCrGramsPerUnit = 9.5,
            calculatedUamCarbsGrams = 12.0,
            calculatedUamConfidence = 0.72,
            horizons = listOf(
                HorizonPredictionUi(5, 5.7, 5.5, 5.9),
                HorizonPredictionUi(30, 6.2, 5.7, 6.7),
                HorizonPredictionUi(60, 6.5, 5.8, 7.2)
            ),
            canRunCycleNow = true
        )
    }
}
