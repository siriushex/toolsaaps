package io.aaps.copilot.domain.profile

import kotlin.math.max
import kotlin.math.min

/**
 * An advisory NASEM 2023 energy estimate. It is deliberately isolated from
 * glucose, insulin, carbohydrate, target, and action code.
 */
class EnergyRequirementEstimator {

    fun estimate(resolution: ProfileResolution): EnergyEstimate = when (resolution) {
        is ProfileResolution.Incomplete -> EnergyEstimate.Incomplete(resolution.missing)
        is ProfileResolution.Invalid -> EnergyEstimate.Invalid(resolution.violations)
        is ProfileResolution.Complete -> estimateComplete(resolution)
    }

    private fun estimateComplete(resolution: ProfileResolution.Complete): EnergyEstimate {
        val age = resolution.ageYears
        if (age !in SUPPORTED_AGES) return EnergyEstimate.Unsupported

        val settings = resolution.settings
        if (age < ADULT_MIN_AGE && settings.calorieGoalMode in PEDIATRIC_RESTRICTED_GOALS) {
            return EnergyEstimate.Invalid(setOf(ProfileViolation.PEDIATRIC_CALORIE_GOAL_RESTRICTED))
        }
        val heightCm = settings.heightCm ?: return EnergyEstimate.Incomplete(setOf(ProfileField.HEIGHT_CM))
        val weightKg = settings.weightKg ?: return EnergyEstimate.Incomplete(setOf(ProfileField.WEIGHT_KG))
        val violations = linkedSetOf<ProfileViolation>()
        if (heightCm !in HEIGHT_CM_RANGE) {
            violations += ProfileViolation.HEIGHT_OUT_OF_RANGE
        }
        if (weightKg !in WEIGHT_KG_RANGE) {
            violations += ProfileViolation.WEIGHT_OUT_OF_RANGE
        }
        val manualCalories = settings.manualCalorieTargetKcal
        if (settings.calorieGoalMode == CalorieGoalMode.MANUAL_CLINICIAN &&
            (manualCalories == null || manualCalories !in MANUAL_CALORIE_TARGET_RANGE)
        ) {
            violations += ProfileViolation.MANUAL_CALORIE_TARGET_OUT_OF_RANGE
        }
        if (violations.isNotEmpty()) return EnergyEstimate.Invalid(violations)

        val maintenance = maintenanceRange(
            age = age,
            sex = settings.physiologicalSex,
            heightCm = heightCm,
            weightKg = weightKg,
            activity = settings.manualActivityProfile
        )
        val goal = displayedGoal(
            maintenance = maintenance,
            goalMode = settings.calorieGoalMode,
            manualCalories = manualCalories
        )
        return EnergyEstimate.Available(
            maintenanceKcal = maintenance,
            displayedGoalKcal = goal.range,
            displayedGoalLabel = goal.label,
            provenance = provenanceFor(settings.physiologicalSex, settings.calorieGoalMode),
            advisoryOnly = true,
            limitations = LIMITATIONS
        )
    }

    private fun maintenanceRange(
        age: Int,
        sex: PhysiologicalSex,
        heightCm: Double,
        weightKg: Double,
        activity: ActivityProfile
    ): EnergyRange {
        fun estimateFor(actualSex: PhysiologicalSex): Double =
            equationFor(age, actualSex, activity).estimate(age, heightCm, weightKg) + growthKcal(age, actualSex)

        return when (sex) {
            PhysiologicalSex.MALE -> EnergyRange.exact(estimateFor(PhysiologicalSex.MALE))
            PhysiologicalSex.FEMALE -> EnergyRange.exact(estimateFor(PhysiologicalSex.FEMALE))
            PhysiologicalSex.UNSPECIFIED -> EnergyRange(
                minimum = min(estimateFor(PhysiologicalSex.MALE), estimateFor(PhysiologicalSex.FEMALE)),
                maximum = max(estimateFor(PhysiologicalSex.MALE), estimateFor(PhysiologicalSex.FEMALE))
            )
        }
    }

    private fun displayedGoal(
        maintenance: EnergyRange,
        goalMode: CalorieGoalMode,
        manualCalories: Int?
    ): GoalPresentation = when (goalMode) {
        CalorieGoalMode.LOSS -> GoalPresentation(
            range = EnergyRange(
                minimum = (maintenance.minimum - LOSS_DEFICIT_MAX_KCAL).coerceAtLeast(MINIMUM_DISPLAY_KCAL),
                maximum = (maintenance.maximum - LOSS_DEFICIT_MIN_KCAL).coerceAtLeast(MINIMUM_DISPLAY_KCAL)
            ),
            label = EnergyGoalLabel.LOSS_PLANNING_RANGE
        )
        CalorieGoalMode.GAIN -> GoalPresentation(
            range = EnergyRange(
                minimum = maintenance.minimum + GAIN_SURPLUS_MIN_KCAL,
                maximum = maintenance.maximum + GAIN_SURPLUS_MAX_KCAL
            ),
            label = EnergyGoalLabel.GAIN_PLANNING_RANGE
        )
        CalorieGoalMode.MANUAL_CLINICIAN -> GoalPresentation(
            range = EnergyRange.exact(requireNotNull(manualCalories).toDouble()),
            label = EnergyGoalLabel.CLINICIAN_MANUAL
        )
        CalorieGoalMode.OFF,
        CalorieGoalMode.MAINTENANCE -> GoalPresentation(maintenance, EnergyGoalLabel.MAINTENANCE)
    }

    private fun equationFor(age: Int, sex: PhysiologicalSex, activity: ActivityProfile): Equation = when {
        age < ADULT_MIN_AGE && sex == PhysiologicalSex.MALE -> PEDIATRIC_BOY.getValue(activity)
        age < ADULT_MIN_AGE && sex == PhysiologicalSex.FEMALE -> PEDIATRIC_GIRL.getValue(activity)
        age >= ADULT_MIN_AGE && sex == PhysiologicalSex.MALE -> ADULT_MALE.getValue(activity)
        age >= ADULT_MIN_AGE && sex == PhysiologicalSex.FEMALE -> ADULT_FEMALE.getValue(activity)
        else -> error("Unspecified sex must be resolved to an envelope endpoint")
    }

    private fun provenanceFor(sex: PhysiologicalSex, goalMode: CalorieGoalMode): EnergyEstimateProvenance = when {
        sex == PhysiologicalSex.UNSPECIFIED -> EnergyEstimateProvenance.NASEM_2023_SEX_ENVELOPE
        goalMode == CalorieGoalMode.MANUAL_CLINICIAN -> EnergyEstimateProvenance.CLINICIAN_MANUAL
        else -> EnergyEstimateProvenance.NASEM_2023
    }

    private fun growthKcal(age: Int, sex: PhysiologicalSex): Double = when (sex) {
        PhysiologicalSex.MALE -> when (age) {
            3 -> 20.0
            in 4..8 -> 15.0
            in 9..13 -> 25.0
            in 14..18 -> 20.0
            else -> 0.0
        }
        PhysiologicalSex.FEMALE -> when (age) {
            in 3..8 -> 15.0
            in 9..13 -> 30.0
            in 14..18 -> 20.0
            else -> 0.0
        }
        PhysiologicalSex.UNSPECIFIED -> 0.0
    }

    private data class Equation(
        val intercept: Double,
        val ageCoefficient: Double,
        val heightCoefficient: Double,
        val weightCoefficient: Double
    ) {
        fun estimate(age: Int, heightCm: Double, weightKg: Double): Double =
            intercept + ageCoefficient * age + heightCoefficient * heightCm + weightCoefficient * weightKg
    }

    private data class GoalPresentation(val range: EnergyRange, val label: EnergyGoalLabel)

    private companion object {
        val SUPPORTED_AGES = 3..120
        const val ADULT_MIN_AGE = 19
        val PEDIATRIC_RESTRICTED_GOALS = setOf(CalorieGoalMode.LOSS, CalorieGoalMode.GAIN)
        const val LOSS_DEFICIT_MIN_KCAL = 250.0
        const val LOSS_DEFICIT_MAX_KCAL = 500.0
        const val GAIN_SURPLUS_MIN_KCAL = 250.0
        const val GAIN_SURPLUS_MAX_KCAL = 500.0
        const val MINIMUM_DISPLAY_KCAL = 500.0
        val HEIGHT_CM_RANGE = 80.0..230.0
        val WEIGHT_KG_RANGE = 10.0..350.0
        val MANUAL_CALORIE_TARGET_RANGE = 500..10_000
        val LIMITATIONS = listOf(
            EnergyEstimateLimitation.GENERAL_POPULATION_EXCLUDES_DIAGNOSED_TYPE_1_DIABETES,
            EnergyEstimateLimitation.NOT_FOR_THERAPY
        )

        val ADULT_MALE = mapOf(
            ActivityProfile.LOW to Equation(753.07, -10.83, 6.50, 14.10),
            ActivityProfile.MODERATE to Equation(581.47, -10.83, 8.30, 14.94),
            ActivityProfile.HIGH to Equation(1004.82, -10.83, 6.52, 15.91)
        )
        val ADULT_FEMALE = mapOf(
            ActivityProfile.LOW to Equation(584.90, -7.01, 5.72, 11.71),
            ActivityProfile.MODERATE to Equation(575.77, -7.01, 6.60, 12.14),
            ActivityProfile.HIGH to Equation(710.25, -7.01, 6.54, 12.34)
        )
        val PEDIATRIC_BOY = mapOf(
            ActivityProfile.LOW to Equation(-447.51, 3.68, 13.01, 13.15),
            ActivityProfile.MODERATE to Equation(19.12, 3.68, 8.62, 20.28),
            ActivityProfile.HIGH to Equation(-388.19, 3.68, 12.66, 20.46)
        )
        val PEDIATRIC_GIRL = mapOf(
            ActivityProfile.LOW to Equation(55.59, -22.25, 8.43, 17.07),
            ActivityProfile.MODERATE to Equation(-297.54, -22.25, 12.77, 14.73),
            ActivityProfile.HIGH to Equation(-189.55, -22.25, 11.74, 18.34)
        )
    }
}

data class EnergyRange(val minimum: Double, val maximum: Double) {
    init {
        require(minimum.isFinite() && maximum.isFinite() && minimum <= maximum)
    }

    companion object {
        fun exact(value: Double): EnergyRange = EnergyRange(value, value)
    }
}

enum class EnergyEstimateProvenance {
    NASEM_2023,
    NASEM_2023_SEX_ENVELOPE,
    CLINICIAN_MANUAL
}

enum class EnergyGoalLabel {
    MAINTENANCE,
    LOSS_PLANNING_RANGE,
    GAIN_PLANNING_RANGE,
    CLINICIAN_MANUAL
}

enum class EnergyEstimateLimitation {
    GENERAL_POPULATION_EXCLUDES_DIAGNOSED_TYPE_1_DIABETES,
    NOT_FOR_THERAPY
}

sealed interface EnergyEstimate {
    data class Available(
        val maintenanceKcal: EnergyRange,
        val displayedGoalKcal: EnergyRange,
        val displayedGoalLabel: EnergyGoalLabel,
        val provenance: EnergyEstimateProvenance,
        val advisoryOnly: Boolean = true,
        val limitations: List<EnergyEstimateLimitation>
    ) : EnergyEstimate

    data class Incomplete(val missing: Set<ProfileField>) : EnergyEstimate
    data class Invalid(val violations: Set<ProfileViolation>) : EnergyEstimate
    data object Unsupported : EnergyEstimate
}
