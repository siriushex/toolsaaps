package io.aaps.copilot.domain.profile

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class EnergyRequirementEstimatorTest {

    private val estimator = EnergyRequirementEstimator()

    @Test
    fun adultMaleInactiveMatchesNasem2023Equation() {
        val estimate = estimator.estimate(
            complete(age = 40, sex = PhysiologicalSex.MALE, activity = ActivityProfile.LOW)
        ) as EnergyEstimate.Available

        assertThat(estimate.maintenanceKcal.minimum).isWithin(0.01).of(
            753.07 - 10.83 * 40 + 6.50 * 180.0 + 14.10 * 80.0
        )
        assertThat(estimate.maintenanceKcal.maximum).isWithin(0.01)
            .of(estimate.maintenanceKcal.minimum)
        assertThat(estimate.provenance).isEqualTo(EnergyEstimateProvenance.NASEM_2023)
        assertThat(estimate.advisoryOnly).isTrue()
        assertThat(estimate.limitations).containsExactly(
            EnergyEstimateLimitation.GENERAL_POPULATION_EXCLUDES_DIAGNOSED_TYPE_1_DIABETES,
            EnergyEstimateLimitation.NOT_FOR_THERAPY
        )
    }

    @Test
    fun unspecifiedSexReturnsEnvelopeNotMidpoint() {
        val estimate = estimator.estimate(
            complete(age = 19, sex = PhysiologicalSex.UNSPECIFIED)
        ) as EnergyEstimate.Available

        assertThat(estimate.maintenanceKcal.minimum).isLessThan(estimate.maintenanceKcal.maximum)
        assertThat(estimate.provenance).isEqualTo(EnergyEstimateProvenance.NASEM_2023_SEX_ENVELOPE)
    }

    @Test
    fun childLossModeIsRejectedAndGrowthCostIsIncluded() {
        val loss = estimator.estimate(
            complete(age = 9, sex = PhysiologicalSex.FEMALE, goal = CalorieGoalMode.LOSS)
        )
        val maintenance = estimator.estimate(
            complete(age = 9, sex = PhysiologicalSex.FEMALE, activity = ActivityProfile.LOW)
        ) as EnergyEstimate.Available

        assertThat(loss).isEqualTo(
            EnergyEstimate.Invalid(setOf(ProfileViolation.PEDIATRIC_CALORIE_GOAL_RESTRICTED))
        )
        assertThat(maintenance.maintenanceKcal.minimum).isWithin(0.01).of(
            55.59 - 22.25 * 9 + 8.43 * 180.0 + 17.07 * 80.0 + 30.0
        )
    }

    @Test
    fun clinicianManualGoalKeepsMaintenanceEstimateAndUsesManualDisplayedGoal() {
        val estimate = estimator.estimate(
            complete(
                age = 16,
                sex = PhysiologicalSex.FEMALE,
                goal = CalorieGoalMode.MANUAL_CLINICIAN,
                manualCalories = 2_100
            )
        ) as EnergyEstimate.Available

        assertThat(estimate.displayedGoalKcal).isEqualTo(EnergyRange(2_100.0, 2_100.0))
        assertThat(estimate.provenance).isEqualTo(EnergyEstimateProvenance.CLINICIAN_MANUAL)
    }

    @Test
    fun adultLossAndGainAreLabelledPlanningRangesOnly() {
        val loss = estimator.estimate(
            complete(age = 40, sex = PhysiologicalSex.MALE, goal = CalorieGoalMode.LOSS)
        ) as EnergyEstimate.Available
        val gain = estimator.estimate(
            complete(age = 40, sex = PhysiologicalSex.MALE, goal = CalorieGoalMode.GAIN)
        ) as EnergyEstimate.Available

        assertThat(loss.displayedGoalLabel).isEqualTo(EnergyGoalLabel.LOSS_PLANNING_RANGE)
        assertThat(loss.displayedGoalKcal.maximum).isLessThan(loss.maintenanceKcal.minimum)
        assertThat(gain.displayedGoalLabel).isEqualTo(EnergyGoalLabel.GAIN_PLANNING_RANGE)
        assertThat(gain.displayedGoalKcal.minimum).isGreaterThan(gain.maintenanceKcal.maximum)
        assertThat(loss.advisoryOnly).isTrue()
        assertThat(gain.advisoryOnly).isTrue()
    }

    @Test
    fun incompleteAndUnsupportedProfilesNeverReceiveFabricatedEnergyValues() {
        val incomplete = estimator.estimate(
            ProfileResolution.Incomplete(setOf(ProfileField.HEIGHT_CM, ProfileField.WEIGHT_KG))
        )
        val unsupported = estimator.estimate(complete(age = 2, sex = PhysiologicalSex.MALE))

        assertThat(incomplete).isEqualTo(
            EnergyEstimate.Incomplete(setOf(ProfileField.HEIGHT_CM, ProfileField.WEIGHT_KG))
        )
        assertThat(unsupported).isEqualTo(EnergyEstimate.Unsupported)
    }

    @Test
    fun directCompleteWithInvalidMeasurementsOrManualGoalIsRejected() {
        listOf(
            complete(age = 40, sex = PhysiologicalSex.MALE).copy(
                settings = EnergyProfileSettings(heightCm = 0.0, weightKg = 80.0)
            ),
            complete(age = 40, sex = PhysiologicalSex.MALE).copy(
                settings = EnergyProfileSettings(heightCm = 180.0, weightKg = -1.0)
            ),
            complete(age = 40, sex = PhysiologicalSex.MALE).copy(
                settings = EnergyProfileSettings(heightCm = 180.0, weightKg = Double.MAX_VALUE)
            ),
            complete(age = 40, sex = PhysiologicalSex.MALE).copy(
                settings = EnergyProfileSettings(
                    heightCm = 180.0,
                    weightKg = 80.0,
                    calorieGoalMode = CalorieGoalMode.MANUAL_CLINICIAN,
                    manualCalorieTargetKcal = 499
                )
            ),
            complete(age = 40, sex = PhysiologicalSex.MALE).copy(
                settings = EnergyProfileSettings(
                    heightCm = 180.0,
                    weightKg = 80.0,
                    calorieGoalMode = CalorieGoalMode.MANUAL_CLINICIAN,
                    manualCalorieTargetKcal = 10_001
                )
            )
        ).forEach { resolution ->
            assertThat(estimator.estimate(resolution)).isInstanceOf(EnergyEstimate.Invalid::class.java)
        }
    }

    private fun complete(
        age: Int,
        sex: PhysiologicalSex,
        activity: ActivityProfile = ActivityProfile.MODERATE,
        goal: CalorieGoalMode = CalorieGoalMode.MAINTENANCE,
        manualCalories: Int? = null
    ): ProfileResolution.Complete = ProfileResolution.Complete(
        ageYears = age,
        settings = EnergyProfileSettings(
            physiologicalSex = sex,
            heightCm = 180.0,
            weightKg = 80.0,
            manualActivityProfile = activity,
            calorieGoalMode = goal,
            manualCalorieTargetKcal = manualCalories
        )
    )
}
