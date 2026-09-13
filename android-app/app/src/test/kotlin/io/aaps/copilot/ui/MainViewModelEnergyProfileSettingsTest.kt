package io.aaps.copilot.ui

import com.google.common.truth.Truth.assertThat
import io.aaps.copilot.domain.profile.CalorieGoalMode
import io.aaps.copilot.domain.profile.EnergyProfileSettings
import io.aaps.copilot.domain.profile.FoodProfileMode
import io.aaps.copilot.domain.profile.ProfileViolation
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

    private fun completeAdultSettings() = EnergyProfileSettings(
        birthDateEpochDay = LocalDate.of(1990, 1, 1).toEpochDay(),
        heightCm = 175.0,
        weightKg = 70.0
    )
}
