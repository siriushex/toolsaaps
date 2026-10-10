package io.aaps.copilot.domain.nutrition

import io.aaps.copilot.domain.model.TherapyCarbComponentKind
import io.aaps.copilot.domain.model.TherapyEvent
import io.aaps.copilot.domain.model.resolveTherapyComponents
import io.aaps.copilot.domain.profile.MealAbsorptionProfile
import io.aaps.copilot.domain.profile.MealTherapyReferenceTrust
import io.aaps.copilot.domain.profile.toMealTherapyReference
import java.time.Instant

data class MealPortionMetadata(
    val portion: MealPortion,
    val provenance: MealPortionProvenance
)

/** Local confirmation evidence, never reconstructed from a free-text therapy note. */
data class MealPortionEvidence(
    val canonicalId: String,
    val therapyRevision: String,
    val confirmedGrams: Double,
    val portion: MealPortion,
    val profile: MealAbsorptionProfile,
    val provenance: MealPortionProvenance,
    val confirmedAt: Instant? = null
)

/** Read-only boundary for the offline estimator; missing evidence excludes the meal. */
fun TherapyEvent.toPortionObservation(evidence: MealPortionEvidence?): MealPortionObservation? {
    evidence ?: return null
    if (evidence.provenance != MealPortionProvenance.USER_ENTERED &&
        evidence.provenance != MealPortionProvenance.USER_CORRECTED) return null
    val reference = toMealTherapyReference()
    if (reference.trust != MealTherapyReferenceTrust.TRUSTED ||
        evidence.canonicalId.isBlank() || evidence.therapyRevision.isBlank() ||
        reference.identity != evidence.canonicalId || reference.revision != evidence.therapyRevision) return null
    val components = resolveTherapyComponents(this)
    val grams = components.carbsG ?: return null
    if (!components.canonicalCarbAuthoritative || components.carbKind != TherapyCarbComponentKind.REAL ||
        components.learningCarbsSuppressed ||
        !grams.isFinite() || grams <= 0.0 || grams != evidence.confirmedGrams) return null
    val timestamp = Instant.ofEpochMilli(ts)
    val availableAt = evidence.confirmedAt ?: return null
    if (availableAt < timestamp) return null
    return MealPortionObservation(
        canonicalId = reference.identity,
        timestamp = timestamp,
        grams = grams,
        profile = evidence.profile,
        provenance = evidence.provenance,
        portion = evidence.portion,
        availableAt = availableAt,
        therapyRevision = reference.revision
    )
}
