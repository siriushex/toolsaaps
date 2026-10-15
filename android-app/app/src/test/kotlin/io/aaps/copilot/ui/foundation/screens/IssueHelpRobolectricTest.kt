package io.aaps.copilot.ui.foundation.screens

import android.app.Application
import android.app.DatePickerDialog
import android.content.DialogInterface
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import io.aaps.copilot.R
import io.aaps.copilot.domain.profile.CalorieGoalMode
import io.aaps.copilot.domain.profile.EnergyProfileSettings
import io.aaps.copilot.domain.profile.PhysiologicalSex
import io.aaps.copilot.ui.MainUiState
import io.aaps.copilot.ui.EnergyProfileSettingsUpdate
import io.aaps.copilot.ui.applyEnergyProfileUserDraft
import io.aaps.copilot.ui.evaluateEnergyProfileSettingsUpdate
import io.aaps.copilot.ui.foundation.components.SafetyBanner
import io.aaps.copilot.ui.foundation.components.SafetyBannerType
import io.aaps.copilot.ui.foundation.theme.AapsCopilotTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowDialog
import java.io.File
import java.time.LocalDate
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w320dp-h640dp-port")
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class IssueHelpRobolectricTest {
    @get:Rule val compose = createEmptyComposeRule()
    private var activity: ComponentActivity? = null

    @Test fun profileWarningOpensExactFormAndSavesOnlyExplicitCorrection() = profileFlow(false)
    @Test fun enlargedDarkRussianProfileFlowKeepsActionsReachable() = profileFlow(true)

    private fun profileFlow(dark: Boolean) {
        val saves = mutableListOf<UserProfileDraftUi>()
        var therapy = 0
        withUi(dark) {
            var request by remember { mutableStateOf<IssueSettingsRequest?>(null) }
            CompositionLocalProvider(LocalIssueNavigator provides { request = IssueSettingsRequest(1, it) }) {
                if (request == null) OverviewScreen(
                    state = OverviewUiState(loadState = ScreenLoadState.READY, isStale = false, glucose = 5.6,
                        energyActivityStatus = EnergyActivityStatusUi(EnergyActivityStatusKind.PROFILE_ISSUE,
                            issues = listOf(UiIssueKind.HEIGHT_MISSING))),
                    onRunCycleNow = { therapy++ }, onSetKillSwitch = { therapy++ }, onDisablePowerSave = { therapy++ },
                    onAddBloodCheck = { _, _, _, _ -> therapy++ }, onOpenClinicalReport = { therapy++ }
                ) else SettingsScreen(
                    state = MainUiState().toSettingsUiState(false, false).copy(isStale = false,
                        energyProfile = EnergyProfileSettingsUiState(enabled = true,
                            userProfile = UserProfileDraftUi(LocalDate.of(1990, 1, 1).toEpochDay(), PhysiologicalSex.UNSPECIFIED, null, 70.0))),
                    onVerboseLogsToggle = {}, onProModeToggle = {}, issueRequest = request,
                    onEnergyProfileUserProfileSave = { draft, done -> saves += draft; done(EnergyProfileSaveResult.Saved) }
                )
            }
        }.use {
            compose.onNodeWithTag("energy_activity_status").performClick()
            compose.onNodeWithText(text(R.string.issue_height_missing)).assertIsDisplayed()
            capture("profile-help-$dark.png")
            compose.onNodeWithTag("issue_open_USER_PROFILE").performScrollTo().performClick()
            compose.onNodeWithTag("issue_user_profile_form").assertIsDisplayed()
            compose.onNodeWithTag("issue_profile_height").performScrollTo().performTextReplacement("175,5")
            compose.onNodeWithText(text(android.R.string.ok)).assertIsEnabled()
            capture("profile-form-$dark.png")
            assertTrue(saves.isEmpty())
            assertEquals(0, therapy)
            compose.onNodeWithText(text(android.R.string.ok)).performClick()
            compose.runOnIdle {
                assertEquals(1, saves.size)
                assertEquals(175.5, saves.single().heightCm!!, 0.0)
                assertEquals(70.0, saves.single().weightKg!!, 0.0)
                assertEquals(0, therapy)
            }
        }
    }

    @Test fun missingCaloriesDeepLinkOpensCalorieEditorWithoutWriting() {
        val saves = mutableListOf<EnergyGoalSettingsUi>()
        withUi {
            SettingsScreen(state = MainUiState().toSettingsUiState(false, false).copy(isStale = false,
                energyProfile = EnergyProfileSettingsUiState(derivedAgeYears = 30,
                    userProfile = UserProfileDraftUi(LocalDate.of(1990, 1, 1).toEpochDay(), heightCm = 175.0, weightKg = 70.0),
                    energyGoalSettings = EnergyGoalSettingsUi(CalorieGoalMode.MANUAL_CLINICIAN, null, true))),
                onVerboseLogsToggle = {}, onProModeToggle = {},
                issueRequest = IssueSettingsRequest(1, IssueDestination.ENERGY_GOAL),
                onEnergyGoalSettingsSave = { draft, done -> saves += draft; done(EnergyProfileSaveResult.Saved) })
        }.use {
            compose.onNodeWithTag("issue_energy_goal_form").assertIsDisplayed()
            compose.onNodeWithText(text(android.R.string.ok)).assertIsNotEnabled()
            compose.onNodeWithTag("issue_profile_calories").performScrollTo().performTextReplacement("2000")
            compose.onNodeWithText(text(android.R.string.ok)).assertIsEnabled()
            assertTrue(saves.isEmpty())
            compose.onNodeWithText(text(android.R.string.ok)).performClick()
            compose.runOnIdle { assertEquals(2000, saves.single().manualTargetKcal) }
        }
    }

    @Test fun invalidStoredCaloriesCanBeCorrectedEvenWithGoalOff() {
        withUi {
            EnergyActivitySettingsSection(state = EnergyProfileSettingsUiState(
                energyGoalSettings = EnergyGoalSettingsUi(CalorieGoalMode.OFF, 1, true)),
                onEnabledChange = {}, onSaveUserProfile = { _, _ -> }, onSaveFoodSettings = { _, _ -> }, onSaveActivitySettings = { _, _ -> },
                onSaveEvent = { _, _ -> }, onDeleteEvent = {}, onSaveEnergySettings = { _, _ -> }, editorRequest = IssueDestination.ENERGY_GOAL)
        }.use {
            compose.onNodeWithTag("issue_profile_calories").assertIsDisplayed()
            compose.onNodeWithText(text(android.R.string.ok)).assertIsNotEnabled()
            compose.onNodeWithTag("issue_profile_calories").performTextReplacement("")
            compose.onNodeWithText(text(android.R.string.ok)).assertIsEnabled()
        }
    }

    @Test fun calorieErrorWithInvalidProfileOpensJointCorrectionAndSavesOnce() {
        val current = EnergyProfileSettings(enabled = true, birthDateEpochDay = LocalDate.of(1990, 1, 1).toEpochDay(),
            heightCm = 999.0, weightKg = 70.0, calorieGoalMode = CalorieGoalMode.MANUAL_CLINICIAN, manualCalorieTargetKcal = 499)
        val saves = mutableListOf<EnergyProfileSettingsUpdate>()
        var separateSaves = 0
        withUi(true) {
            SettingsScreen(state = MainUiState().toSettingsUiState(false, false).copy(isStale = false,
                energyProfile = EnergyProfileSettingsUiState(derivedAgeYears = 30,
                    userProfile = UserProfileDraftUi(current.birthDateEpochDay, heightCm = current.heightCm, weightKg = current.weightKg),
                    energyGoalSettings = EnergyGoalSettingsUi(current.calorieGoalMode, current.manualCalorieTargetKcal))),
                onVerboseLogsToggle = {}, onProModeToggle = {},
                issueRequest = IssueSettingsRequest(1, IssueDestination.ENERGY_GOAL),
                onEnergyProfileUserProfileSave = { _, _ -> separateSaves++ }, onEnergyGoalSettingsSave = { _, _ -> separateSaves++ },
                onEnergyProfileCorrectionSave = { draft, calories, done ->
                    saves += evaluateEnergyProfileSettingsUpdate(current, applyEnergyProfileUserDraft(current, draft, calories), LocalDate.now())
                    done(EnergyProfileSaveResult.Saved)
                })
        }.use {
            compose.onNodeWithTag("issue_user_profile_form").assertIsDisplayed()
            compose.onNodeWithText(text(android.R.string.ok)).assertIsNotEnabled()
            compose.onNodeWithTag("issue_profile_height").performScrollTo().performTextReplacement("175")
            compose.onNodeWithText(text(android.R.string.ok)).assertIsNotEnabled()
            compose.onNodeWithTag("issue_joint_calories").performScrollTo().performTextReplacement("2000")
            compose.onNodeWithText(text(android.R.string.ok)).assertIsEnabled()
            capture("joint-profile-calorie-form-dark.png")
            assertTrue(saves.isEmpty())
            assertEquals(0, separateSaves)
            compose.onNodeWithText(text(android.R.string.ok)).performClick()
            compose.runOnIdle {
                assertEquals(0, separateSaves)
                assertEquals(1, saves.size)
                val saved = saves.single() as EnergyProfileSettingsUpdate.Persist
                assertEquals(175.0, saved.settings.heightCm!!, 0.0)
                assertEquals(2000, saved.settings.manualCalorieTargetKcal)
            }
        }
    }

    @Test fun sharedErrorOpensHelpThenJournalWithoutRetry() {
        val navigation = mutableListOf<IssueDestination>()
        withUi {
            CompositionLocalProvider(LocalIssueNavigator provides { navigation += it }) {
                ScreenStateLayout(ScreenLoadState.ERROR, false, "Fixture operation failed", "Empty") { error("No ready content") }
            }
        }.use {
            compose.onNodeWithText("Fixture operation failed").performClick()
            compose.onNodeWithText(text(R.string.issue_generic_error)).assertIsDisplayed()
            assertTrue(navigation.isEmpty())
            compose.onNodeWithTag("issue_open_AUDIT").performScrollTo().performClick()
            compose.runOnIdle { assertEquals(listOf(IssueDestination.AUDIT), navigation) }
        }
    }

    @Test fun rejectedProfileSaveMessageOffersDiagnosticsWithoutAnotherSave() {
        var saves = 0
        withUi {
            CompositionLocalProvider(LocalIssueNavigator provides {}) {
                EnergyActivitySettingsSection(state = EnergyProfileSettingsUiState(validationMessage = "Fixture rejected profile save"),
                    onEnabledChange = {}, onSaveUserProfile = { _, _ -> saves++ }, onSaveFoodSettings = { _, _ -> saves++ },
                    onSaveActivitySettings = { _, _ -> saves++ }, onSaveEvent = { _, _ -> saves++ }, onDeleteEvent = { saves++ },
                    onSaveEnergySettings = { _, _ -> saves++ })
            }
        }.use {
            compose.onNodeWithText("Fixture rejected profile save").performClick()
            compose.onNodeWithText(text(R.string.issue_generic_error)).assertIsDisplayed()
            compose.onNodeWithTag("issue_open_AUDIT").performScrollTo().assertIsDisplayed()
            compose.runOnIdle { assertEquals(0, saves) }
        }
    }

    @Test fun consumedScheduleRequestDoesNotInterceptNextDataSourceRequest() {
        var request by mutableStateOf<IssueSettingsRequest?>(IssueSettingsRequest(1, IssueDestination.ACTIVITY_SCHEDULE))
        val handled = mutableListOf<IssueSettingsRequest>()
        withUi {
            SettingsScreen(state = MainUiState().toSettingsUiState(false, false).copy(isStale = false),
                onVerboseLogsToggle = {}, onProModeToggle = {}, issueRequest = request,
                onIssueRequestHandled = { handled += it; request = null })
        }.use {
            compose.onAllNodesWithText(text(R.string.energy_activity_and_schedule)).filterToOne(hasAnyAncestor(isDialog())).assertIsDisplayed()
            compose.onNodeWithText(text(android.R.string.cancel)).performClick()
            compose.runOnIdle { request = IssueSettingsRequest(2, IssueDestination.DATA_SOURCES) }
            compose.waitForIdle()
            captureActivity("navigation-data-sources.png")
            compose.onNodeWithText(text(R.string.section_settings_data_sources), ignoreCase = true).assertIsDisplayed()
            compose.onNode(isDialog()).assertDoesNotExist()
            compose.runOnIdle { assertEquals(listOf(IssueSettingsRequest(1, IssueDestination.ACTIVITY_SCHEDULE),
                IssueSettingsRequest(2, IssueDestination.DATA_SOURCES)), handled) }
        }
    }

    @Test fun staleBannerOffersDataSourceSettings() {
        withUi {
            CompositionLocalProvider(LocalIssueNavigator provides {}) {
                SafetyBanner(SafetyBannerType.WARNING, "Fixture stale data", issue = UiIssueKind.DATA_STALE)
            }
        }.use {
            compose.onNodeWithText("Fixture stale data").performClick()
            compose.onNodeWithTag("issue_open_DATA_SOURCES").performScrollTo().assertIsDisplayed()
        }
    }

    @Test fun managerGuardHelpDoesNotDisableProtection() {
        val navigation = mutableListOf<IssueDestination>()
        withUi {
            CompositionLocalProvider(LocalIssueNavigator provides { navigation += it }) {
                TargetManagerLiveStatusSection(TargetManagerLiveStatusUi(
                    availability = TargetManagerLiveStatusAvailabilityUi.CURRENT, reason = "kill_switch"), false)
            }
        }.use {
            compose.onNodeWithTag("overview_target_manager_live_status").performClick()
            compose.onNodeWithText(text(R.string.issue_kill_switch)).assertIsDisplayed()
            assertTrue(navigation.isEmpty())
            compose.onNodeWithTag("issue_open_SAFETY").performScrollTo().performClick()
            compose.runOnIdle { assertEquals(listOf(IssueDestination.SAFETY), navigation) }
        }
    }

    @Test fun missingBirthDateCanBeFilledUsingTheActualDatePicker() {
        val saves = mutableListOf<UserProfileDraftUi>()
        withUi {
            EnergyActivitySettingsSection(state = EnergyProfileSettingsUiState(
                userProfile = UserProfileDraftUi(null, PhysiologicalSex.UNSPECIFIED, 175.0, 70.0)),
                onEnabledChange = {}, onSaveUserProfile = { draft, done -> saves += draft; done(EnergyProfileSaveResult.Saved) }, onSaveFoodSettings = { _, _ -> }, onSaveActivitySettings = { _, _ -> },
                onSaveEvent = { _, _ -> }, onDeleteEvent = {}, onSaveEnergySettings = { _, _ -> }, editorRequest = IssueDestination.USER_PROFILE)
        }.use {
            compose.onNodeWithText(text(android.R.string.ok)).assertIsNotEnabled()
            compose.onNodeWithTag("issue_profile_birth_date").performScrollTo().performClick()
            compose.runOnIdle {
                val picker = ShadowDialog.getLatestDialog() as DatePickerDialog
                picker.datePicker.updateDate(1990, 0, 1)
                picker.getButton(DialogInterface.BUTTON_POSITIVE).performClick()
            }
            compose.onNodeWithText(text(android.R.string.ok)).assertIsEnabled().performClick()
            compose.runOnIdle { assertEquals(LocalDate.of(1990, 1, 1).toEpochDay(), saves.single().birthDateEpochDay) }
        }
    }

    @Test fun profileSaveWaitsForPersistenceBeforeClosing() {
        var requests = 0
        withUi {
            EnergyActivitySettingsSection(state = EnergyProfileSettingsUiState(
                userProfile = UserProfileDraftUi(LocalDate.of(1990, 1, 1).toEpochDay(),
                    PhysiologicalSex.UNSPECIFIED, 175.0, 70.0)),
                onEnabledChange = {}, onSaveUserProfile = { _, _ -> requests++ },
                onSaveFoodSettings = { _, _ -> }, onSaveActivitySettings = { _, _ -> }, onSaveEvent = { _, _ -> },
                onDeleteEvent = {}, onSaveEnergySettings = { _, _ -> }, editorRequest = IssueDestination.USER_PROFILE)
        }.use {
            val before = compose.onNodeWithTag("profile_save_confirm").fetchSemanticsNode().boundsInRoot
            compose.onNodeWithText(text(android.R.string.ok)).performClick()
            compose.runOnIdle { assertEquals(1, requests) }
            compose.onNodeWithTag("issue_user_profile_form").assertIsDisplayed()
            compose.onNodeWithTag("profile_save_confirm").assertIsNotEnabled()
            val after = compose.onNodeWithTag("profile_save_confirm").fetchSemanticsNode().boundsInRoot
            assertEquals(before.width, after.width, 0f)
            assertEquals(before.height, after.height, 0f)
        }
    }

    @Test fun failedProfileSaveKeepsEditedValuesInTheForm() {
        val state = mutableStateOf(EnergyProfileSettingsUiState(
            userProfile = UserProfileDraftUi(LocalDate.of(1990, 1, 1).toEpochDay(),
                PhysiologicalSex.UNSPECIFIED, 175.0, 70.0)))
        withUi {
            EnergyActivitySettingsSection(state = state.value,
                onEnabledChange = {}, onSaveUserProfile = { _, done ->
                    state.value = state.value.copy(validationMessage = "Profile write rejected")
                    done(EnergyProfileSaveResult.Failed("Profile write rejected"))
                }, onSaveFoodSettings = { _, _ -> }, onSaveActivitySettings = { _, _ -> }, onSaveEvent = { _, _ -> },
                onDeleteEvent = {}, onSaveEnergySettings = { _, _ -> }, editorRequest = IssueDestination.USER_PROFILE)
        }.use {
            compose.onNodeWithTag("issue_profile_height").performScrollTo().performTextReplacement("176,5")
            compose.onNodeWithTag("issue_profile_weight").performScrollTo().performTextReplacement("71,2")
            compose.onNodeWithText(text(android.R.string.ok)).performClick()
            compose.onNodeWithTag("issue_user_profile_form").assertIsDisplayed()
            compose.onNodeWithTag("issue_profile_height").assertTextContains("176,5")
            compose.onNodeWithTag("issue_profile_weight").assertTextContains("71,2")
            compose.onNodeWithTag("profile_save_error").performScrollTo().assertTextContains("Profile write rejected")
            compose.onNodeWithTag("profile_save_confirm").assertIsEnabled()
        }
    }

    @Test fun consumedRequestDoesNotReopenButNewRequestCanOpenAgain() {
        val request = mutableStateOf<IssueSettingsRequest?>(IssueSettingsRequest(1, IssueDestination.USER_PROFILE))
        var handled = 0
        withUi {
            SettingsScreen(state = MainUiState().toSettingsUiState(false, false).copy(isStale = false),
                onVerboseLogsToggle = {}, onProModeToggle = {}, issueRequest = request.value,
                onIssueRequestHandled = { handled++; request.value = null })
        }.use {
            compose.onNodeWithTag("issue_user_profile_form").assertIsDisplayed()
            compose.onNodeWithText(text(android.R.string.cancel)).performClick()
            compose.onNodeWithTag("issue_user_profile_form").assertDoesNotExist()
            compose.runOnIdle { assertEquals(1, handled); request.value = IssueSettingsRequest(2, IssueDestination.USER_PROFILE) }
            compose.onNodeWithTag("issue_user_profile_form").assertIsDisplayed()
            compose.runOnIdle { assertEquals(2, handled) }
        }
    }

    @Test fun failedProfileSaveRetriesTheSameDraftAndReopensPersistedValues() {
        val state = mutableStateOf(EnergyProfileSettingsUiState(
            userProfile = UserProfileDraftUi(LocalDate.of(1990, 1, 1).toEpochDay(),
                PhysiologicalSex.UNSPECIFIED, 175.0, 70.0)))
        val drafts = mutableListOf<UserProfileDraftUi>()
        withUi(dark = true) {
            EnergyActivitySettingsSection(state = state.value, onEnabledChange = {},
                onSaveUserProfile = { draft, done ->
                    drafts += draft
                    if (drafts.size == 1) done(EnergyProfileSaveResult.Failed("Fixture profile write failed"))
                    else {
                        state.value = state.value.copy(userProfile = draft)
                        done(EnergyProfileSaveResult.Saved)
                    }
                }, onSaveFoodSettings = { _, _ -> }, onSaveActivitySettings = { _, _ -> },
                onSaveEvent = { _, _ -> }, onDeleteEvent = {}, onSaveEnergySettings = { _, _ -> },
                editorRequest = IssueDestination.USER_PROFILE)
        }.use {
            compose.onNodeWithTag("issue_profile_height").performScrollTo().performTextReplacement("176,5")
            compose.onNodeWithTag("issue_profile_weight").performScrollTo().performTextReplacement("71,2")
            compose.onNodeWithTag("profile_save_confirm").performClick()
            compose.onNodeWithTag("profile_save_error").performScrollTo().assertIsDisplayed()
            capture("profile-save-error-dark.png")
            compose.onNodeWithTag("profile_save_confirm").assertIsEnabled().performClick()
            compose.onNodeWithTag("issue_user_profile_form").assertDoesNotExist()
            compose.runOnIdle { assertEquals(2, drafts.size); assertEquals(drafts[0], drafts[1]) }
            compose.onNodeWithText(text(R.string.energy_activity_user_profile)).performClick()
            compose.onNodeWithTag("issue_profile_height").performScrollTo().assertTextContains("176.5")
            compose.onNodeWithTag("issue_profile_weight").performScrollTo().assertTextContains("71.2")
        }
    }

    @Test fun foodSettingsWaitForAcknowledgmentAndKeepTheFormOnFailure() = settingsSaveFlow(
        R.string.energy_activity_food_absorption, "issue_food_profile_form")

    @Test fun activitySettingsWaitForAcknowledgmentAndKeepTheFormOnFailure() = settingsSaveFlow(
        R.string.energy_activity_and_schedule, "issue_activity_profile_form")

    @Test fun calorieSettingsWaitForAcknowledgmentAndKeepTheFormOnFailure() = settingsSaveFlow(
        R.string.energy_activity_energy_and_ai, "issue_energy_goal_form")

    private fun settingsSaveFlow(title: Int, formTag: String) {
        lateinit var complete: EnergyProfileSaveCompletion
        var requests = 0
        fun submitted(done: EnergyProfileSaveCompletion) { requests++; complete = done }
        withUi {
            EnergyActivitySettingsSection(state = EnergyProfileSettingsUiState(), onEnabledChange = {},
                onSaveUserProfile = { _, done -> submitted(done) },
                onSaveFoodSettings = { _, done -> submitted(done) },
                onSaveActivitySettings = { _, done -> submitted(done) },
                onSaveEvent = { _, _ -> }, onDeleteEvent = {}, onSaveEnergySettings = { _, done -> submitted(done) })
        }.use {
            compose.onNodeWithText(text(title)).performClick()
            compose.onNodeWithTag("profile_save_confirm").performClick()
            compose.onNodeWithTag(formTag).assertIsDisplayed()
            compose.onNodeWithTag("profile_save_confirm").assertIsNotEnabled()
            compose.onNodeWithText(text(android.R.string.cancel)).assertIsNotEnabled()
            compose.runOnIdle { assertEquals(1, requests); complete(EnergyProfileSaveResult.Failed("Fixture save rejected")) }
            compose.onNodeWithTag("profile_save_error").performScrollTo().assertIsDisplayed()
            compose.onNodeWithTag("profile_save_confirm").assertIsEnabled().performClick()
            compose.runOnIdle { assertEquals(2, requests); complete(EnergyProfileSaveResult.Saved) }
            compose.onNodeWithTag(formTag).assertDoesNotExist()
        }
    }

    @Test fun plannedActivityEditorWaitsForTheActualSaveResult() {
        var requests = 0
        withUi {
            EnergyActivitySettingsSection(state = EnergyProfileSettingsUiState(), onEnabledChange = {},
                onSaveUserProfile = { _, _ -> }, onSaveFoodSettings = { _, _ -> },
                onSaveActivitySettings = { _, _ -> }, onSaveEvent = { _, _ -> requests++ },
                onDeleteEvent = {}, onSaveEnergySettings = { _, _ -> },
                editorRequest = IssueDestination.ACTIVITY_SCHEDULE)
        }.use {
            compose.onNodeWithContentDescription(text(R.string.energy_activity_add_event)).performClick()
            compose.onNodeWithTag("planned_activity_title").performScrollTo().performTextReplacement("Fixture evening walk")
            compose.onNodeWithTag("planned_activity_save").assertIsEnabled().performClick()
            compose.runOnIdle { assertEquals(1, requests) }
            compose.onNodeWithTag("planned_activity_title").assertIsDisplayed()
            compose.onNodeWithTag("planned_activity_save").assertIsNotEnabled()
        }
    }

    @Test fun plannedActivitySaveFailureRetainsFieldsAndCanRetry() {
        val state = mutableStateOf(EnergyProfileSettingsUiState())
        val drafts = mutableListOf<PlannedActivityEventUi>()
        withUi(dark = true) {
            EnergyActivitySettingsSection(state = state.value, onEnabledChange = {},
                onSaveUserProfile = { _, _ -> }, onSaveFoodSettings = { _, _ -> },
                onSaveActivitySettings = { _, _ -> }, onSaveEvent = { draft, done ->
                    drafts += draft
                    if (drafts.size == 1) done(EnergyProfileSaveResult.Failed("Fixture schedule write failed"))
                    else {
                        state.value = state.value.copy(plannedEvents = listOf(draft))
                        done(EnergyProfileSaveResult.Saved)
                    }
                }, onDeleteEvent = {}, onSaveEnergySettings = { _, _ -> },
                editorRequest = IssueDestination.ACTIVITY_SCHEDULE)
        }.use {
            val parentDialog = compose.runOnIdle { ShadowDialog.getLatestDialog() }
            compose.onNodeWithContentDescription(text(R.string.energy_activity_add_event)).performClick()
            compose.onNodeWithTag("planned_activity_title").performScrollTo().performTextReplacement("Fixture evening walk")
            compose.onNodeWithTag("planned_activity_duration").performScrollTo().performTextReplacement("0")
            compose.onNodeWithTag("planned_activity_save").assertIsNotEnabled()
            compose.onNodeWithTag("planned_activity_duration").performTextReplacement("75")
            compose.onNodeWithTag("planned_activity_save").assertIsEnabled().performClick()
            compose.onNodeWithTag("profile_save_error").performScrollTo().assertIsDisplayed()
            capture("planned-save-error-dark.png")
            compose.onNodeWithTag("planned_activity_title").performScrollTo().assertTextContains("Fixture evening walk")
            compose.onNodeWithTag("planned_activity_duration").performScrollTo().assertTextContains("75")
            compose.onNodeWithTag("planned_activity_save").assertIsEnabled().performClick()
            compose.onNodeWithTag("planned_activity_form").assertDoesNotExist()
            compose.runOnIdle {
                assertEquals(2, drafts.size)
                assertEquals(drafts[0].eventId, drafts[1].eventId)
                assertEquals(drafts[0].title, drafts[1].title)
                assertEquals(75, drafts[1].durationMinutes)
            }
            compose.onNodeWithText("Fixture evening walk", substring = true).performScrollTo().assertIsDisplayed()
            capture("planned-saved-list-dark.png", parentDialog)
        }
    }

    @Test fun missingCalculationOffersProfileStatusAndJournalNotPersonalEditor() {
        withUi {
            CompositionLocalProvider(LocalIssueNavigator provides {}) {
                IssueHelpDialog("Fixture profile", listOf(UiIssueKind.PROFILE_SNAPSHOT_MISSING)) {}
            }
        }.use {
            compose.onNodeWithTag("issue_open_USER_PROFILE").assertDoesNotExist()
            compose.onNodeWithTag("issue_open_ENERGY_PROFILE").assertIsDisplayed()
            compose.onNodeWithTag("issue_open_AUDIT").assertIsDisplayed()
        }
    }

    private fun text(id: Int) = RuntimeEnvironment.getApplication().getString(id)

    private fun withUi(dark: Boolean = false, content: @Composable () -> Unit): AutoCloseable {
        val app = RuntimeEnvironment.getApplication()
        val old = Configuration(app.resources.configuration)
        @Suppress("DEPRECATION")
        app.resources.updateConfiguration(Configuration(old).apply {
            fontScale = if (dark) 1.8f else 1.0f
            setLocale(Locale.forLanguageTag("ru"))
        }, app.resources.displayMetrics)
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        activity = controller.get()
        controller.get().setContent { AapsCopilotTheme(darkTheme = dark, dynamicColor = false) { content() } }
        return AutoCloseable {
            controller.pause().stop().destroy()
            @Suppress("DEPRECATION")
            app.resources.updateConfiguration(old, app.resources.displayMetrics)
        }
    }

    private fun capture(name: String, dialog: android.app.Dialog? = null) {
        val directory = System.getenv("ISSUE_UI_EVIDENCE_DIR") ?: return
        val shown = dialog ?: ShadowDialog.getLatestDialog()
        assertTrue(shown.isShowing)
        val view = checkNotNull(shown.window).decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        File(directory, name).outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
    }

    private fun captureActivity(name: String) {
        val directory = System.getenv("ISSUE_UI_EVIDENCE_DIR") ?: return
        val view = checkNotNull(activity).window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        File(directory, name).outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
    }

    @Test fun enlargedRussianCalorieMenuDoesNotClipAndDoesNotSaveSelection() {
        var saves = 0
        withUi(dark = true) {
            EnergyActivitySettingsSection(state = EnergyProfileSettingsUiState(derivedAgeYears = 30,
                energyGoalSettings = EnergyGoalSettingsUi(CalorieGoalMode.MANUAL_CLINICIAN, null, true)),
                onEnabledChange = {}, onSaveUserProfile = { _, _ -> }, onSaveFoodSettings = { _, _ -> }, onSaveActivitySettings = { _, _ -> },
                onSaveEvent = { _, _ -> }, onDeleteEvent = {}, onSaveEnergySettings = { _, _ -> saves++ }, editorRequest = IssueDestination.ENERGY_GOAL)
        }.use {
            val layouts = mutableListOf<TextLayoutResult>()
            val label = text(R.string.issue_goal_manual)
            compose.onNodeWithText(label).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            assertFalse(layouts.single().hasVisualOverflow)
            capture("calorie-form-dark.png")
            compose.onNodeWithText(label).performClick()
            compose.onNodeWithText(text(R.string.issue_goal_maintenance)).performScrollTo().performClick()
            compose.onNodeWithTag("issue_energy_goal_form").assertIsDisplayed()
            compose.runOnIdle { assertEquals(0, saves) }
        }
    }

    @Test fun longUnknownOperationMessageCannotHideCloseOrDiagnosticAction() {
        val navigation = mutableListOf<IssueDestination>()
        withUi(dark = true) {
            CompositionLocalProvider(LocalIssueNavigator provides { navigation += it }) {
                IssueHelpDialog("Fixture operation detail ".repeat(60), listOf(UiIssueKind.OPERATION_MESSAGE)) {}
            }
        }.use {
            compose.onNodeWithText(text(R.string.action_close)).assertIsDisplayed()
            compose.onNodeWithTag("issue_open_AUDIT").performScrollTo().assertIsDisplayed().performClick()
            compose.runOnIdle { assertEquals(listOf(IssueDestination.AUDIT), navigation) }
        }
    }
}
