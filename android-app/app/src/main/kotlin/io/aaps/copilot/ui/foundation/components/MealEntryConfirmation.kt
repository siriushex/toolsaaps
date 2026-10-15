package io.aaps.copilot.ui.foundation.components

import io.aaps.copilot.domain.nutrition.MealPortion
import io.aaps.copilot.domain.nutrition.MealPortionProvenance
import io.aaps.copilot.domain.profile.MealAbsorptionProfile
import java.io.Serializable

internal data class MealEntryConfirmation(
    val grams: Double,
    val portion: MealPortion,
    val profile: MealAbsorptionProfile,
    val energyKcal: Double?,
    val eatingSoon: Boolean,
    val submissionId: String,
    val provenance: MealPortionProvenance = MealPortionProvenance.UNKNOWN,
    val glycemicIndex: io.aaps.copilot.domain.profile.MealGlycemicIndex? = null
) : Serializable

internal fun confirmedMealFromInput(
    gramsRaw: String,
    portion: MealPortion,
    profile: MealAbsorptionProfile,
    energyRaw: String,
    showCalories: Boolean,
    eatingSoon: Boolean,
    submissionId: String,
    maximumGrams: Double,
    proposedGrams: Double? = null,
    glycemicIndexRaw: String = ""
): MealEntryConfirmation? {
    val grams = gramsRaw.trim().replace(',', '.').toDoubleOrNull() ?: return null
    if (!maximumGrams.isFinite() || !grams.isFinite() || grams !in 1.0..maximumGrams || submissionId.isBlank()) return null
    val energy = if (!showCalories || energyRaw.isBlank()) null else {
        val parsed = energyRaw.trim().replace(',', '.').toDoubleOrNull() ?: return null
        if (!parsed.isFinite() || parsed !in 1.0..10_000.0) return null
        parsed
    }
    val provenance = when {
        proposedGrams == null || !proposedGrams.isFinite() -> MealPortionProvenance.UNKNOWN
        grams == proposedGrams -> MealPortionProvenance.ACCEPTED_SUGGESTION
        else -> MealPortionProvenance.USER_CORRECTED
    }
    return MealEntryConfirmation(grams, portion, profile, energy, eatingSoon, submissionId, provenance,
        io.aaps.copilot.domain.profile.MealGlycemicIndex.fromManualInput(glycemicIndexRaw))
}
