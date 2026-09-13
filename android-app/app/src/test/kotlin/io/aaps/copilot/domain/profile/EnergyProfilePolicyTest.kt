package io.aaps.copilot.domain.profile

import com.google.common.truth.Truth.assertThat
import java.time.LocalDate
import org.junit.Test

class EnergyProfilePolicyTest {

    private val policy = EnergyProfilePolicy()

    @Test
    fun settingsDefaultsKeepTheModuleDisabledAndUseNeutralProfiles() {
        assertThat(EnergyProfileSettings()).isEqualTo(
            EnergyProfileSettings(
                enabled = false,
                birthDateEpochDay = null,
                physiologicalSex = PhysiologicalSex.UNSPECIFIED,
                heightCm = null,
                weightKg = null,
                foodProfileMode = FoodProfileMode.AUTO,
                manualFoodProfile = MealAbsorptionProfile.MIXED,
                activityProfileMode = ActivityProfileMode.AUTO,
                manualActivityProfile = ActivityProfile.MODERATE,
                calorieGoalMode = CalorieGoalMode.OFF,
                manualCalorieTargetKcal = null,
                shareProfileWithAi = true
            )
        )
    }

    @Test
    fun leapDayBirthDateUsesCompletedYears() {
        val birthDate = LocalDate.of(2008, 2, 29)

        assertThat(policy.ageOn(birthDate, LocalDate.of(2026, 2, 28))).isEqualTo(17)
        assertThat(policy.ageOn(birthDate, LocalDate.of(2026, 3, 1))).isEqualTo(18)
    }

    @Test
    fun missingPhysicalMeasurementsRemainAnIncompleteDraft() {
        val resolution = policy.resolve(
            EnergyProfileSettings(
                birthDateEpochDay = LocalDate.of(1990, 5, 1).toEpochDay(),
                physiologicalSex = PhysiologicalSex.UNSPECIFIED,
                heightCm = null,
                weightKg = null
            ),
            LocalDate.of(2026, 8, 2)
        )

        assertThat(resolution).isEqualTo(
            ProfileResolution.Incomplete(
                setOf(
                    ProfileField.HEIGHT_CM,
                    ProfileField.WEIGHT_KG
                )
            )
        )
    }

    @Test
    fun unspecifiedPhysiologicalSexIsACompleteValidProfileChoice() {
        val settings = completeSettings().copy(
            physiologicalSex = PhysiologicalSex.UNSPECIFIED
        )

        assertThat(policy.resolve(settings, LocalDate.of(2026, 8, 2))).isEqualTo(
            ProfileResolution.Complete(ageYears = 36, settings = settings)
        )
    }

    @Test
    fun inclusivePhysiologicalBoundsResolveACompleteProfile() {
        val minimum = completeSettings(
            birthDate = LocalDate.of(2023, 8, 2),
            heightCm = 80.0,
            weightKg = 10.0,
            calorieGoalMode = CalorieGoalMode.MANUAL_CLINICIAN,
            manualCalorieTargetKcal = 500
        )
        val maximum = completeSettings(
            birthDate = LocalDate.of(1906, 8, 2),
            heightCm = 230.0,
            weightKg = 350.0,
            calorieGoalMode = CalorieGoalMode.MANUAL_CLINICIAN,
            manualCalorieTargetKcal = 10_000
        )

        assertThat(policy.resolve(minimum, LocalDate.of(2026, 8, 2))).isEqualTo(
            ProfileResolution.Complete(ageYears = 3, settings = minimum)
        )
        assertThat(policy.resolve(maximum, LocalDate.of(2026, 8, 2))).isEqualTo(
            ProfileResolution.Complete(ageYears = 120, settings = maximum)
        )
    }

    @Test
    fun outOfRangeValuesAreInvalid() {
        val draft = completeSettings(
            heightCm = 79.9,
            weightKg = 350.1,
            calorieGoalMode = CalorieGoalMode.MANUAL_CLINICIAN,
            manualCalorieTargetKcal = 499
        )

        assertThat(policy.resolve(draft, LocalDate.of(2026, 8, 2))).isEqualTo(
            ProfileResolution.Invalid(
                setOf(
                    ProfileViolation.HEIGHT_OUT_OF_RANGE,
                    ProfileViolation.WEIGHT_OUT_OF_RANGE,
                    ProfileViolation.MANUAL_CALORIE_TARGET_OUT_OF_RANGE
                )
            )
        )
    }

    @Test
    fun extremeStoredBirthDateEpochDaysResolveAsTypedAgeViolations() {
        listOf(Long.MAX_VALUE, Long.MIN_VALUE).forEach { birthDateEpochDay ->
            val resolution = policy.resolve(
                completeSettings().copy(birthDateEpochDay = birthDateEpochDay),
                LocalDate.of(2026, 8, 2)
            )

            assertThat(resolution).isEqualTo(
                ProfileResolution.Invalid(setOf(ProfileViolation.AGE_OUT_OF_RANGE))
            )
        }
    }

    @Test
    fun minorCannotResolveLossOrGainMode() {
        val today = LocalDate.of(2026, 8, 2)
        val loss = completeSettings(
            birthDate = LocalDate.of(2012, 5, 1),
            calorieGoalMode = CalorieGoalMode.LOSS
        )
        val gain = loss.copy(calorieGoalMode = CalorieGoalMode.GAIN)

        assertThat(policy.resolve(loss, today)).isEqualTo(
            ProfileResolution.Invalid(
                setOf(ProfileViolation.PEDIATRIC_CALORIE_GOAL_RESTRICTED)
            )
        )
        assertThat(policy.resolve(gain, today)).isEqualTo(
            ProfileResolution.Invalid(
                setOf(ProfileViolation.PEDIATRIC_CALORIE_GOAL_RESTRICTED)
            )
        )
    }

    private fun completeSettings(
        birthDate: LocalDate = LocalDate.of(1990, 5, 1),
        heightCm: Double = 170.0,
        weightKg: Double = 70.0,
        calorieGoalMode: CalorieGoalMode = CalorieGoalMode.OFF,
        manualCalorieTargetKcal: Int? = null
    ): EnergyProfileSettings = EnergyProfileSettings(
        birthDateEpochDay = birthDate.toEpochDay(),
        physiologicalSex = PhysiologicalSex.FEMALE,
        heightCm = heightCm,
        weightKg = weightKg,
        calorieGoalMode = calorieGoalMode,
        manualCalorieTargetKcal = manualCalorieTargetKcal
    )
}
