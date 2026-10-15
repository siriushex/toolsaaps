package io.aaps.copilot.domain.nutrition

enum class MealPortion { SMALL, MEDIUM, LARGE }

data class MealPortionRange(val minGrams: Double, val maxGrams: Double, val defaultGrams: Double) {
    fun isValid(): Boolean = minGrams.isFinite() && maxGrams.isFinite() && defaultGrams.isFinite() &&
        minGrams > 0.0 && minGrams < maxGrams && defaultGrams in minGrams..maxGrams
}

/** Portion suggestions are not authorization to exceed the manual submission limit. */
data class MealPortionSettings(
    val small: MealPortionRange = MealPortionRange(7.0, 15.0, 10.0),
    val medium: MealPortionRange = MealPortionRange(15.0, 40.0, 25.0),
    val large: MealPortionRange = MealPortionRange(40.0, 80.0, 60.0),
    val showCalories: Boolean = false
) {
    fun range(portion: MealPortion): MealPortionRange = when (portion) {
        MealPortion.SMALL -> small
        MealPortion.MEDIUM -> medium
        MealPortion.LARGE -> large
    }

    fun isValid(): Boolean = MealPortion.entries.all { range(it).isValid() } &&
        small.maxGrams <= medium.minGrams && medium.maxGrams <= large.minGrams

    // Historical records have no explicit category; shared boundaries belong to the larger one.
    fun classifyHistoricalGrams(grams: Double): MealPortion? =
        if (!grams.isFinite() || !isValid()) null else MealPortion.entries.reversed().firstOrNull {
            grams in range(it).minGrams..range(it).maxGrams
        }
}
