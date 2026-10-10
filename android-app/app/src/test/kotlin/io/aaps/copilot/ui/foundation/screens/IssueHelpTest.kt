package io.aaps.copilot.ui.foundation.screens

import io.aaps.copilot.data.local.entity.EnergyProfileSnapshotEntity
import io.aaps.copilot.domain.profile.CalorieGoalMode
import io.aaps.copilot.domain.profile.EnergyProfileSettings
import io.aaps.copilot.ui.MainUiState
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

class IssueHelpTest {
    private val today = LocalDate.of(2026, 10, 10)
    private val settings = EnergyProfileSettings(enabled = true,
        birthDateEpochDay = LocalDate.of(1990, 1, 1).toEpochDay(), heightCm = 175.0, weightKg = 70.0)
    private val snapshot = EnergyProfileSnapshotEntity("profile-fixture", 1, 1L, 2L,
        7, "PROVISIONAL", "MIXED", 120, null, 0.7, false, "fixture-hash", 3L, false)

    @Test fun missingFieldsRemainSpecificEvenWithoutSnapshot() {
        assertEquals(listOf(UiIssueKind.BIRTH_DATE_MISSING, UiIssueKind.HEIGHT_MISSING, UiIssueKind.WEIGHT_MISSING),
            energyProfileIssues(settings.copy(birthDateEpochDay = null, heightCm = null, weightKg = null), null, today))
    }

    @Test fun invalidFieldsAreEditableUserProfileIssues() {
        assertEquals(setOf(UiIssueKind.BIRTH_DATE_INVALID, UiIssueKind.HEIGHT_INVALID, UiIssueKind.WEIGHT_INVALID),
            energyProfileIssues(settings.copy(birthDateEpochDay = today.plusDays(1).toEpochDay(),
                heightCm = Double.NaN, weightKg = 500.0), snapshot, today).toSet())
    }

    @Test fun missingManualCaloriesOpenEnergyGoal() {
        val issue = energyProfileIssues(settings.copy(calorieGoalMode = CalorieGoalMode.MANUAL_CLINICIAN), snapshot, today).single()
        assertEquals(UiIssueKind.CALORIES_MISSING, issue)
        assertEquals(IssueDestination.ENERGY_GOAL, issue.destination)
    }

    @Test fun invalidStoredCaloriesAreDiagnosedEvenWhenGoalOff() {
        assertEquals(listOf(UiIssueKind.CALORIES_INVALID), energyProfileIssues(
            settings.copy(manualCalorieTargetKcal = 1), snapshot, today))
    }

    @Test fun pediatricGoalDoesNotSuggestChangingBirthDate() {
        assertEquals(listOf(UiIssueKind.CALORIES_RESTRICTED), energyProfileIssues(
            settings.copy(birthDateEpochDay = today.minusYears(15).toEpochDay(), calorieGoalMode = CalorieGoalMode.LOSS), snapshot, today))
    }

    @Test fun validPersonalDataAndMissingSnapshotDoNotOpenPersonalEditor() {
        val issue = energyProfileIssues(settings, null, today).single()
        assertEquals(UiIssueKind.PROFILE_SNAPSHOT_MISSING, issue)
        assertEquals(IssueDestination.ENERGY_PROFILE, issue.destination)
    }

    @Test fun staleSnapshotHasSeparateReason() {
        assertEquals(listOf(UiIssueKind.PROFILE_SNAPSHOT_STALE), energyProfileIssues(settings, snapshot.copy(stale = true), today))
    }

    @Test fun disabledOrCompleteProfileDoesNotWarn() {
        assertTrue(energyProfileIssues(EnergyProfileSettings(), null, today).isEmpty())
        assertTrue(energyProfileIssues(settings, snapshot, today).isEmpty())
    }

    @Test fun overviewKeepsSpecificIssuesInsteadOfOnlyGenericStatus() {
        val status = energyActivityOverviewStatus(settings.copy(heightCm = null), null, emptyList(),
            Instant.parse("2026-10-10T12:00:00Z"))
        assertEquals(EnergyActivityStatusKind.PROFILE_ISSUE, status?.kind)
        assertEquals(listOf(UiIssueKind.HEIGHT_MISSING), status?.issues)
    }

    @Test fun everyProfileProblemHasExactEditableLocation() {
        for (issue in UiIssueKind.entries.filter { it.destination == IssueDestination.USER_PROFILE || it.destination == IssueDestination.ENERGY_GOAL }) {
            val location = requireNotNull(issueSettingsLocation(issue.destination))
            assertTrue(location.advanced)
            assertEquals(2, location.itemIndex)
            assertEquals(issue.destination, location.editor)
        }
    }

    @Test fun pendingRuntimeDoesNotInventACommandDeliveryFailure() {
        val item = MainUiState().toSafetyUiState().checklist.single { it.title == "Nightscout local runtime" }
        assertFalse(item.ok)
        assertEquals(UiIssueKind.DATA_SOURCE_PENDING, item.issue)
        assertEquals(IssueDestination.DATA_SOURCES, item.issue.destination)
    }

    @Test fun nonEditorSettingsRoutesHaveStableSections() {
        assertEquals(IssueSettingsLocation(false, 1), issueSettingsLocation(IssueDestination.DATA_SOURCES))
        assertEquals(IssueSettingsLocation(false, 4), issueSettingsLocation(IssueDestination.ALERT_SETTINGS))
        assertEquals(IssueSettingsLocation(true, 4), issueSettingsLocation(IssueDestination.TARGET_MANAGER))
        assertEquals(IssueSettingsLocation(true, 2), issueSettingsLocation(IssueDestination.ENERGY_PROFILE))
        assertNull(issueSettingsLocation(IssueDestination.SAFETY))
    }

    @Test fun knownManagerReasonsRouteWithoutDroppingSafety() {
        val cases = mapOf("stale_data" to UiIssueKind.DATA_STALE, "kill_switch" to UiIssueKind.KILL_SWITCH,
            "sensor_untrusted" to UiIssueKind.SENSOR, "delivery_failed" to UiIssueKind.DELIVERY,
            "forecast_unreliable" to UiIssueKind.FORECAST, "eating_soon_target_active" to UiIssueKind.TARGET_RETAINED,
            "safety_bounds" to UiIssueKind.TARGET_GUARD, "mode_off" to UiIssueKind.TARGET_MANAGER)
        for ((reason, expected) in cases) assertEquals(reason, expected, targetManagerIssue(
            TargetManagerLiveStatusUi(availability = TargetManagerLiveStatusAvailabilityUi.CURRENT, reason = reason)))
    }

    @Test fun allIssueKindsHaveLocalizedExplanationAndDestination() {
        UiIssueKind.entries.forEach { assertTrue(it.explanationRes != 0); assertTrue(it.destination.labelRes != 0) }
    }

    @Test fun preflightForecastAndFreshnessFailuresHaveRelevantHelp() {
        assertEquals(UiIssueKind.FORECAST, targetManagerIssue(TargetManagerLiveStatusUi(
            availability = TargetManagerLiveStatusAvailabilityUi.CURRENT, reason = "accepted_forecast_lookup_failed")))
        assertEquals(UiIssueKind.DATA_STALE, targetManagerIssue(TargetManagerLiveStatusUi(
            availability = TargetManagerLiveStatusAvailabilityUi.CURRENT, reason = "current_glucose_freshness_invalid")))
    }

    @Test fun unknownManagerReasonIsNotInventedAsNormalNoProposal() {
        assertEquals(UiIssueKind.GENERIC_ERROR, targetManagerIssue(TargetManagerLiveStatusUi(
            availability = TargetManagerLiveStatusAvailabilityUi.CURRENT, reason = "unknown_fixture_reason")))
        assertEquals(UiIssueKind.NO_PROPOSAL, targetManagerIssue(TargetManagerLiveStatusUi(
            availability = TargetManagerLiveStatusAvailabilityUi.CURRENT, reason = "no_eligible_proposal")))
    }

    @Test fun jointCorrectionChecksDoNotInventMissingOrValidCalorieProblems() {
        assertFalse(needsCalorieCorrection(EnergyGoalSettingsUi()))
        assertFalse(needsCalorieCorrection(EnergyGoalSettingsUi(CalorieGoalMode.MANUAL_CLINICIAN, 2000)))
        assertTrue(needsCalorieCorrection(EnergyGoalSettingsUi(CalorieGoalMode.MANUAL_CLINICIAN)))
        assertTrue(needsCalorieCorrection(EnergyGoalSettingsUi(CalorieGoalMode.OFF, 499)))
    }

    @Test fun jointEditorUsesTheExistingPersonalFieldPolicy() {
        val valid = UserProfileDraftUi(settings.birthDateEpochDay, settings.physiologicalSex, 175.0, 70.0)
        assertTrue(userProfileDraftComplete(valid, today))
        assertFalse(userProfileDraftComplete(valid.copy(heightCm = null), today))
        assertFalse(userProfileDraftComplete(valid.copy(weightKg = Double.NaN), today))
        assertFalse(userProfileDraftComplete(valid.copy(birthDateEpochDay = today.plusDays(1).toEpochDay()), today))
        assertFalse(userProfileDraftComplete(valid.copy(birthDateEpochDay = Long.MAX_VALUE), today))
    }
}
