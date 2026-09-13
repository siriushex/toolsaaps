package io.aaps.copilot.domain.profile

enum class PhysiologicalSex { MALE, FEMALE, UNSPECIFIED }

enum class FoodProfileMode { AUTO, MANUAL }

enum class MealAbsorptionProfile { FAST, MIXED, FAT_PROTEIN }

enum class ActivityProfileMode { AUTO, MANUAL }

enum class ActivityProfile { LOW, MODERATE, HIGH }

enum class CalorieGoalMode { OFF, MAINTENANCE, LOSS, GAIN, MANUAL_CLINICIAN }

enum class EvidenceTier { INSUFFICIENT_DATA, PROVISIONAL, STABLE, HIGH_CONFIDENCE }

enum class ResolutionSource { PER_MEAL, MANUAL, AUTO, DEFAULT }

data class EnergyProfileSettings(
    val enabled: Boolean = false,
    val forecastActivityInfluenceEnabled: Boolean = false,
    val birthDateEpochDay: Long? = null,
    val physiologicalSex: PhysiologicalSex = PhysiologicalSex.UNSPECIFIED,
    val heightCm: Double? = null,
    val weightKg: Double? = null,
    val foodProfileMode: FoodProfileMode = FoodProfileMode.AUTO,
    val manualFoodProfile: MealAbsorptionProfile = MealAbsorptionProfile.MIXED,
    val activityProfileMode: ActivityProfileMode = ActivityProfileMode.AUTO,
    val manualActivityProfile: ActivityProfile = ActivityProfile.MODERATE,
    val calorieGoalMode: CalorieGoalMode = CalorieGoalMode.OFF,
    val manualCalorieTargetKcal: Int? = null,
    val shareProfileWithAi: Boolean = true
)

enum class ProfileField {
    BIRTH_DATE,
    PHYSIOLOGICAL_SEX,
    HEIGHT_CM,
    WEIGHT_KG,
    MANUAL_CALORIE_TARGET_KCAL
}

enum class ProfileViolation {
    AGE_OUT_OF_RANGE,
    HEIGHT_OUT_OF_RANGE,
    WEIGHT_OUT_OF_RANGE,
    MANUAL_CALORIE_TARGET_OUT_OF_RANGE,
    PEDIATRIC_CALORIE_GOAL_RESTRICTED
}

sealed interface ProfileResolution {
    data class Complete(
        val ageYears: Int,
        val settings: EnergyProfileSettings
    ) : ProfileResolution

    data class Incomplete(
        val missing: Set<ProfileField>
    ) : ProfileResolution

    data class Invalid(
        val violations: Set<ProfileViolation>
    ) : ProfileResolution
}
