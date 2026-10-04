package io.aaps.copilot.domain.profile

import io.aaps.copilot.domain.model.TherapyEvent

data class MealAbsorptionSelection(
    val profile: MealAbsorptionProfile,
    val durationMinutes: Int? = null,
    val therapyRevision: String? = null,
    val portionMetadata: io.aaps.copilot.domain.nutrition.MealPortionMetadata? = null,
    val glycemicIndex: MealGlycemicIndex? = null
)

class MealTherapyReference(
    identity: String,
    revision: String?,
    val trust: MealTherapyReferenceTrust = MealTherapyReferenceTrust.MISSING
) {
    val identity: String = identity.takeIf { trust == MealTherapyReferenceTrust.TRUSTED } ?: "absent"
    val revision: String? = revision.takeIf { trust == MealTherapyReferenceTrust.TRUSTED }
}

fun TherapyEvent.toMealTherapyReference(): MealTherapyReference {
    val trust = when {
        componentTrust.canonicalSemanticConflict || componentTrust.canonicalReferenceConflict ->
            MealTherapyReferenceTrust.CONFLICT
        componentTrust.canonicalCarbId == null || componentTrust.canonicalCarbRevision == null ->
            MealTherapyReferenceTrust.MISSING
        else -> MealTherapyReferenceTrust.TRUSTED
    }
    return MealTherapyReference(
        identity = componentTrust.canonicalCarbId
            ?.takeIf { trust == MealTherapyReferenceTrust.TRUSTED }
            ?.toString()
            ?: "absent",
        revision = componentTrust.canonicalCarbRevision.takeIf {
            trust == MealTherapyReferenceTrust.TRUSTED
        },
        trust = trust
    )
}

enum class MealTherapyReferenceTrust {
    TRUSTED,
    MISSING,
    CONFLICT
}

data class ResolvedMealAbsorption(
    val profile: MealAbsorptionProfile,
    val durationMinutes: Int,
    val source: ResolutionSource
)

/**
 * Immutable runtime input for prediction. Repositories may build this from
 * settings and persistent overrides, but the prediction engine only receives
 * this already-resolved value object.
 */
data class MealAbsorptionContext(
    val enabled: Boolean = false,
    private val perMealOverrides: Map<String, MealAbsorptionSelection> = emptyMap(),
    val manual: MealAbsorptionSelection? = null,
    val auto: MealAbsorptionSelection? = null
) {
    private val overrides = perMealOverrides.toMap()

    fun perMealOverride(reference: MealTherapyReference): MealAbsorptionSelection? {
        if (reference.trust != MealTherapyReferenceTrust.TRUSTED) return null
        val override = overrides[reference.identity] ?: return null
        val revision = reference.revision ?: return null
        return override.takeIf { it.therapyRevision != null && it.therapyRevision == revision }
    }

    companion object {
        val DISABLED = MealAbsorptionContext()
    }
}

class MealAbsorptionProfileResolver {

    fun resolve(
        perMeal: MealAbsorptionSelection?,
        manual: MealAbsorptionSelection?,
        auto: MealAbsorptionSelection?
    ): ResolvedMealAbsorption {
        val (selection, source) = when {
            perMeal != null -> perMeal to ResolutionSource.PER_MEAL
            manual != null -> manual to ResolutionSource.MANUAL
            auto != null -> auto to ResolutionSource.AUTO
            else -> MealAbsorptionSelection(MealAbsorptionProfile.MIXED) to ResolutionSource.DEFAULT
        }
        val curve = MealAbsorptionCurve(selection.profile, selection.durationMinutes)
        return ResolvedMealAbsorption(
            profile = curve.profile,
            durationMinutes = curve.durationMinutes,
            source = source
        )
    }
}
