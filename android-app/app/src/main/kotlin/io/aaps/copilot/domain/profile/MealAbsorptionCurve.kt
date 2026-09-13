package io.aaps.copilot.domain.profile

/**
 * Pure per-meal absorption curve. It models only timing; entered carbohydrate
 * amounts and the underlying therapy event remain unchanged.
 */
class MealAbsorptionCurve(
    val profile: MealAbsorptionProfile,
    requestedDurationMinutes: Int?
) {
    val durationMinutes: Int = profile.resolveDurationMinutes(requestedDurationMinutes)

    fun absorbedFraction(ageMinutes: Double): Double {
        if (ageMinutes == Double.POSITIVE_INFINITY) return 1.0
        if (!ageMinutes.isFinite() || ageMinutes <= 0.0) return 0.0
        if (ageMinutes >= durationMinutes) return 1.0
        val x = (ageMinutes / durationMinutes.toDouble()).coerceIn(0.0, 1.0)
        return when (profile) {
            MealAbsorptionProfile.FAST -> 1.0 - (1.0 - x) * (1.0 - x)
            MealAbsorptionProfile.MIXED -> 3.0 * x * x - 2.0 * x * x * x
            MealAbsorptionProfile.FAT_PROTEIN -> x * x
        }.takeIf(Double::isFinite)?.coerceIn(0.0, 1.0) ?: 0.0
    }

    fun absorbedGrams(grams: Double, ageMinutes: Double): Double =
        safeFractionOf(grams, absorbedFraction(ageMinutes))

    fun remainingGrams(grams: Double, ageMinutes: Double): Double =
        safeFractionOf(grams, 1.0 - absorbedFraction(ageMinutes))

    private fun safeFractionOf(grams: Double, fraction: Double): Double {
        if (!grams.isFinite() || grams <= 0.0) return 0.0
        val value = grams * fraction.coerceIn(0.0, 1.0)
        return value.takeIf { it.isFinite() && it >= 0.0 } ?: Double.MAX_VALUE
    }
}

internal fun MealAbsorptionProfile.resolveDurationMinutes(requestedDurationMinutes: Int?): Int {
    val range = when (this) {
        MealAbsorptionProfile.FAST -> FAST_DURATION_RANGE
        MealAbsorptionProfile.MIXED -> MIXED_DURATION_RANGE
        MealAbsorptionProfile.FAT_PROTEIN -> FAT_PROTEIN_DURATION_RANGE
    }
    return requestedDurationMinutes?.takeIf { it in range } ?: when (this) {
        MealAbsorptionProfile.FAST -> FAST_DEFAULT_DURATION_MINUTES
        MealAbsorptionProfile.MIXED -> MIXED_DEFAULT_DURATION_MINUTES
        MealAbsorptionProfile.FAT_PROTEIN -> FAT_PROTEIN_DEFAULT_DURATION_MINUTES
    }
}

private val FAST_DURATION_RANGE = 30..60
private val MIXED_DURATION_RANGE = 60..180
private val FAT_PROTEIN_DURATION_RANGE = 180..360
private const val FAST_DEFAULT_DURATION_MINUTES = 45
private const val MIXED_DEFAULT_DURATION_MINUTES = 120
private const val FAT_PROTEIN_DEFAULT_DURATION_MINUTES = 270
