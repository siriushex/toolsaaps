package io.aaps.copilot.ui

import com.google.common.truth.Truth.assertThat
import io.aaps.copilot.domain.profile.CalorieGoalMode
import io.aaps.copilot.domain.profile.EnergyProfileSettings
import io.aaps.copilot.domain.profile.FoodProfileMode
import io.aaps.copilot.domain.profile.ProfileViolation
import io.aaps.copilot.ui.foundation.screens.UserProfileDraftUi
import io.aaps.copilot.ui.foundation.screens.EnergyGoalSettingsUi
import java.time.LocalDate
import org.junit.Test

class MainViewModelEnergyProfileSettingsTest {

    private val today = LocalDate.of(2026, 8, 8)

    @Test
    fun incompleteDemographicsDoNotBlockFoodOrActivityDrafts() {
        val candidate = EnergyProfileSettings(
            foodProfileMode = FoodProfileMode.MANUAL
        )

        val result = evaluateEnergyProfileSettingsUpdate(
            current = EnergyProfileSettings(),
            candidate = candidate,
            today = today
        )

        assertThat(result).isInstanceOf(EnergyProfileSettingsUpdate.Persist::class.java)
        assertThat((result as EnergyProfileSettingsUpdate.Persist).settings.foodProfileMode)
            .isEqualTo(FoodProfileMode.MANUAL)
    }

    @Test
    fun pediatricBirthDateResetsExistingLossGoalBeforePersisting() {
        val current = completeAdultSettings().copy(calorieGoalMode = CalorieGoalMode.LOSS)
        val candidate = current.copy(
            birthDateEpochDay = LocalDate.of(2012, 8, 8).toEpochDay()
        )

        val result = evaluateEnergyProfileSettingsUpdate(current, candidate, today)

        assertThat(result).isInstanceOf(EnergyProfileSettingsUpdate.Persist::class.java)
        result as EnergyProfileSettingsUpdate.Persist
        assertThat(result.settings.calorieGoalMode).isEqualTo(CalorieGoalMode.OFF)
        assertThat(result.pediatricGoalReset).isTrue()
    }

    @Test
    fun manualCaloriesOutsidePolicyRangeAreRejected() {
        val result = evaluateEnergyProfileSettingsUpdate(
            current = completeAdultSettings(),
            candidate = completeAdultSettings().copy(
                calorieGoalMode = CalorieGoalMode.MANUAL_CLINICIAN,
                manualCalorieTargetKcal = 499
            ),
            today = today
        )

        assertThat(result).isEqualTo(
            EnergyProfileSettingsUpdate.Reject(
                setOf(ProfileViolation.MANUAL_CALORIE_TARGET_OUT_OF_RANGE)
            )
        )
    }

    @Test
    fun simultaneousProfileAndCalorieErrorsCanBeCorrectedInOneValidatedSave() {
        val current = completeAdultSettings().copy(heightCm = 999.0, manualCalorieTargetKcal = 499)
        val draft = UserProfileDraftUi(current.birthDateEpochDay, current.physiologicalSex, 175.0, 70.0)
        val profileOnly = applyEnergyProfileUserDraft(current, draft)
        assertThat(evaluateEnergyProfileSettingsUpdate(current, profileOnly, today))
            .isInstanceOf(EnergyProfileSettingsUpdate.Reject::class.java)
        assertThat(evaluateEnergyProfileSettingsUpdate(current, current.copy(manualCalorieTargetKcal = 2000), today))
            .isInstanceOf(EnergyProfileSettingsUpdate.Reject::class.java)

        val corrected = applyEnergyProfileUserDraft(current, draft, EnergyGoalSettingsUi(manualTargetKcal = 2000))
        val result = evaluateEnergyProfileSettingsUpdate(current, corrected, today)
        assertThat(result).isInstanceOf(EnergyProfileSettingsUpdate.Persist::class.java)
        assertThat((result as EnergyProfileSettingsUpdate.Persist).settings).isEqualTo(corrected)
    }

    @Test
    fun jointCorrectionStillRejectsOutOfRangeValues() {
        val current = completeAdultSettings()
        val draft = UserProfileDraftUi(current.birthDateEpochDay, current.physiologicalSex, 999.0, 70.0)
        val corrected = applyEnergyProfileUserDraft(current, draft, EnergyGoalSettingsUi(manualTargetKcal = 499))
        assertThat(evaluateEnergyProfileSettingsUpdate(current, corrected, today)).isEqualTo(
            EnergyProfileSettingsUpdate.Reject(setOf(ProfileViolation.HEIGHT_OUT_OF_RANGE,
                ProfileViolation.MANUAL_CALORIE_TARGET_OUT_OF_RANGE)))
    }

    @Test
    fun correctionPreservesCurrentModesAndSharingInsteadOfStaleFormValues() {
        val current = completeAdultSettings().copy(enabled = true, calorieGoalMode = CalorieGoalMode.MAINTENANCE,
            shareProfileWithAi = false, foodProfileMode = FoodProfileMode.MANUAL, forecastActivityInfluenceEnabled = true)
        val draft = UserProfileDraftUi(current.birthDateEpochDay, current.physiologicalSex, 176.0, 71.0)
        val corrected = applyEnergyProfileUserDraft(current, draft,
            EnergyGoalSettingsUi(CalorieGoalMode.MANUAL_CLINICIAN, 2000, true))
        assertThat(corrected).isEqualTo(current.copy(heightCm = 176.0, weightKg = 71.0, manualCalorieTargetKcal = 2000))
    }

    @Test
    fun ordinaryProfileSavePreservesExistingCalorieValue() {
        val current = completeAdultSettings().copy(manualCalorieTargetKcal = 2100)
        val draft = UserProfileDraftUi(current.birthDateEpochDay, current.physiologicalSex, 176.0, 71.0)
        assertThat(applyEnergyProfileUserDraft(current, draft).manualCalorieTargetKcal).isEqualTo(2100)
    }

    private fun completeAdultSettings() = EnergyProfileSettings(
        birthDateEpochDay = LocalDate.of(1990, 1, 1).toEpochDay(),
        heightCm = 175.0,
        weightKg = 70.0
    )
}
