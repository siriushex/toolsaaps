package io.aaps.copilot.domain.profile

import java.time.DateTimeException
import java.time.LocalDate
import java.time.Period

class EnergyProfilePolicy {

    fun ageOn(birthDate: LocalDate, today: LocalDate): Int =
        Period.between(birthDate, today).years

    fun resolve(
        settings: EnergyProfileSettings,
        today: LocalDate
    ): ProfileResolution {
        val missing = missingFields(settings)
        if (missing.isNotEmpty()) {
            return ProfileResolution.Incomplete(missing)
        }

        val ageYears = try {
            ageOn(LocalDate.ofEpochDay(requireNotNull(settings.birthDateEpochDay)), today)
        } catch (_: DateTimeException) {
            return ProfileResolution.Invalid(setOf(ProfileViolation.AGE_OUT_OF_RANGE))
        }
        val violations = linkedSetOf<ProfileViolation>()
        if (ageYears !in MIN_AGE_YEARS..MAX_AGE_YEARS) {
            violations += ProfileViolation.AGE_OUT_OF_RANGE
        }
        if (!settings.heightCm.isWithin(HEIGHT_RANGE_CM)) {
            violations += ProfileViolation.HEIGHT_OUT_OF_RANGE
        }
        if (!settings.weightKg.isWithin(WEIGHT_RANGE_KG)) {
            violations += ProfileViolation.WEIGHT_OUT_OF_RANGE
        }
        if (settings.manualCalorieTargetKcal != null &&
            settings.manualCalorieTargetKcal !in MIN_MANUAL_CALORIE_TARGET_KCAL..MAX_MANUAL_CALORIE_TARGET_KCAL
        ) {
            violations += ProfileViolation.MANUAL_CALORIE_TARGET_OUT_OF_RANGE
        }
        if (ageYears < ADULT_CALORIE_GOAL_MIN_AGE &&
            settings.calorieGoalMode in setOf(CalorieGoalMode.LOSS, CalorieGoalMode.GAIN)
        ) {
            violations += ProfileViolation.PEDIATRIC_CALORIE_GOAL_RESTRICTED
        }

        return if (violations.isEmpty()) {
            ProfileResolution.Complete(ageYears = ageYears, settings = settings)
        } else {
            ProfileResolution.Invalid(violations)
        }
    }

    private fun missingFields(settings: EnergyProfileSettings): Set<ProfileField> {
        val missing = linkedSetOf<ProfileField>()
        if (settings.birthDateEpochDay == null) missing += ProfileField.BIRTH_DATE
        if (settings.heightCm == null) missing += ProfileField.HEIGHT_CM
        if (settings.weightKg == null) missing += ProfileField.WEIGHT_KG
        if (settings.calorieGoalMode == CalorieGoalMode.MANUAL_CLINICIAN &&
            settings.manualCalorieTargetKcal == null
        ) {
            missing += ProfileField.MANUAL_CALORIE_TARGET_KCAL
        }
        return missing
    }

    private fun Double?.isWithin(range: ClosedFloatingPointRange<Double>): Boolean =
        this != null && isFinite() && this in range

    private companion object {
        const val MIN_AGE_YEARS = 3
        const val MAX_AGE_YEARS = 120
        const val ADULT_CALORIE_GOAL_MIN_AGE = 19
        val HEIGHT_RANGE_CM = 80.0..230.0
        val WEIGHT_RANGE_KG = 10.0..350.0
        const val MIN_MANUAL_CALORIE_TARGET_KCAL = 500
        const val MAX_MANUAL_CALORIE_TARGET_KCAL = 10_000
    }
}
