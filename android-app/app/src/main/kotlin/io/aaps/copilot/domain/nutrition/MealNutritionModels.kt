package io.aaps.copilot.domain.nutrition

import io.aaps.copilot.domain.profile.MealAbsorptionSelection

enum class NutrientSource {
    USER,
    LABEL,
    VERIFIED_CATALOG,
    AI_ESTIMATE
}

enum class PreparationState {
    RAW,
    COOKED,
    UNKNOWN
}

enum class NutrientReferenceType {
    PER_100G,
    PER_SERVING
}

enum class CarbohydrateBasis {
    TOTAL,
    AVAILABLE,
    UNKNOWN
}

enum class EnergyUnit {
    KCAL,
    KJ
}

data class NutritionRange(
    val minimum: Double,
    val maximum: Double
) {
    companion object {
        fun exact(value: Double): NutritionRange = NutritionRange(value, value)
    }
}

data class CatalogReference(
    val id: String,
    val version: String
)

data class KnownNutrient(
    val amount: NutritionRange,
    val source: NutrientSource,
    val catalogReference: CatalogReference? = null
)

data class DeclaredEnergy(
    val amount: NutritionRange,
    val unit: EnergyUnit,
    val source: NutrientSource,
    val catalogReference: CatalogReference? = null
)

data class NutritionReference(
    val type: NutrientReferenceType,
    val referenceGrams: Double
)

data class NutrientFacts(
    val reference: NutritionReference,
    val preparationState: PreparationState,
    val carbohydrateBasis: CarbohydrateBasis,
    val carbohydrates: KnownNutrient? = null,
    val protein: KnownNutrient? = null,
    val fat: KnownNutrient? = null,
    val fiber: KnownNutrient? = null,
    val sugar: KnownNutrient? = null,
    val polyols: KnownNutrient? = null,
    val energy: DeclaredEnergy? = null
)

data class IngredientAmount(
    val grams: Double,
    val eatenFraction: Double,
    val preparationState: PreparationState
)

data class MealIngredient(
    val id: String,
    val name: String,
    val amount: IngredientAmount,
    val facts: NutrientFacts
)

/** The immutable estimate returned by an AI boundary before any user edits. */
class OriginalAiMealEstimate(
    val estimateId: String,
    val mealName: String,
    ingredients: List<MealIngredient>
) {
    val ingredients: List<MealIngredient> = ingredients.toList()

    fun toDraft(): MealNutritionDraft = MealNutritionDraft(
        original = this,
        revision = 0L,
        mealName = mealName,
        ingredients = ingredients
    )
}

/** A new value is returned for every user edit; the original estimate is retained. */
class MealNutritionDraft internal constructor(
    val original: OriginalAiMealEstimate,
    val revision: Long,
    val mealName: String,
    ingredients: List<MealIngredient>
) {
    val ingredients: List<MealIngredient> = ingredients.toList()

    fun revise(
        mealName: String = this.mealName,
        ingredients: List<MealIngredient> = this.ingredients
    ): MealNutritionDraft {
        check(revision < Long.MAX_VALUE) { "Meal nutrition revision exhausted" }
        return MealNutritionDraft(
            original = original,
            revision = revision + 1L,
            mealName = mealName,
            ingredients = ingredients
        )
    }
}

/** Canonical therapy identity only; delivery and reconciliation state live elsewhere. */
data class CanonicalTherapyMetadata(
    val boundId: String,
    val revision: String
)

data class NutrientAggregate(
    val knownSubtotalGrams: NutritionRange,
    val completeTotalGrams: NutritionRange?,
    val knownIngredientCount: Int,
    val includedIngredientCount: Int
)

data class CarbohydrateAggregate(
    val knownSubtotalGrams: NutritionRange,
    val completeTotalGrams: NutritionRange?,
    val completeBasis: CarbohydrateBasis?,
    val knownIngredientCount: Int,
    val includedIngredientCount: Int
)

data class EnergyAggregate(
    val knownSubtotalKilocalories: NutritionRange,
    val knownSubtotalKilojoules: NutritionRange,
    val completeTotalKilocalories: NutritionRange?,
    val completeTotalKilojoules: NutritionRange?,
    val knownIngredientCount: Int,
    val includedIngredientCount: Int
)

enum class NutritionDiagnosticCode {
    PREPARATION_UNRESOLVED,
    CARBOHYDRATE_BASIS_UNRESOLVED,
    MIXED_CARBOHYDRATE_BASIS,
    FIBER_INCLUSION_UNRESOLVED,
    CARBOHYDRATE_SUBFRACTION_VARIATION
}

data class NutritionDiagnostic(
    val code: NutritionDiagnosticCode,
    val ingredientId: String? = null
)

class MealNutritionCalculation(
    val carbohydrates: CarbohydrateAggregate,
    val protein: NutrientAggregate,
    val fat: NutrientAggregate,
    val fiber: NutrientAggregate,
    val sugar: NutrientAggregate,
    val polyols: NutrientAggregate,
    val declaredEnergy: EnergyAggregate,
    val approximateMacroEnergyKilocalories: NutritionRange?,
    diagnostics: List<NutritionDiagnostic>
) {
    val diagnostics: List<NutritionDiagnostic> = diagnostics.toList()
}

sealed interface MealNutritionCalculationResult {
    data class Calculated(val value: MealNutritionCalculation) : MealNutritionCalculationResult

    class Invalid(violations: List<NutritionViolation>) : MealNutritionCalculationResult {
        val violations: List<NutritionViolation> = violations.toList()
    }
}

/** An immutable snapshot of exactly one explicitly confirmed draft revision. */
class ConfirmedMealNutritionSnapshot internal constructor(
    val estimateId: String,
    val draftRevision: Long,
    val mealName: String,
    ingredients: List<MealIngredient>,
    val nutrition: MealNutritionCalculation,
    val explicitTherapyCarbohydrateGrams: Double?,
    val absorptionSelection: MealAbsorptionSelection?,
    val canonicalTherapyMetadata: CanonicalTherapyMetadata?
) {
    val ingredients: List<MealIngredient> = ingredients.toList()
}

sealed interface MealNutritionConfirmationResult {
    data class Confirmed(val snapshot: ConfirmedMealNutritionSnapshot) : MealNutritionConfirmationResult

    class Invalid(violations: List<NutritionViolation>) : MealNutritionConfirmationResult {
        val violations: List<NutritionViolation> = violations.toList()
    }
}
