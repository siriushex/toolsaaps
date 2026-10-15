package io.aaps.copilot.domain.nutrition

import kotlin.math.abs
import kotlin.math.max

object MealNutritionBounds {
    const val MAX_INGREDIENTS = 20
    const val MAX_INGREDIENT_ID_LENGTH = 128
    const val MAX_INGREDIENT_NAME_LENGTH = 120
    const val MAX_ESTIMATE_ID_LENGTH = 128
    const val MAX_MEAL_NAME_LENGTH = 120
    const val MAX_CATALOG_ID_LENGTH = 128
    const val MAX_CATALOG_VERSION_LENGTH = 64
    const val MAX_CANONICAL_ID_LENGTH = 256
    const val MAX_CANONICAL_REVISION_LENGTH = 128
    const val MIN_QUANTITY_GRAMS = 0.1
    const val MAX_QUANTITY_GRAMS = 5_000.0
    const val MIN_REFERENCE_GRAMS = 0.1
    const val MAX_REFERENCE_GRAMS = 5_000.0
    const val MAX_NUTRIENT_GRAMS = 5_000.0
    const val MAX_ENERGY = 100_000.0
    const val MAX_THERAPY_CARBOHYDRATE_GRAMS = 5_000.0
    const val LABEL_ROUNDING_TOLERANCE_GRAMS = 1.0
}

enum class NutritionViolationCode {
    INGREDIENT_COUNT_OUT_OF_RANGE,
    INGREDIENT_ID_INVALID,
    INGREDIENT_ID_DUPLICATE,
    INGREDIENT_NAME_INVALID,
    QUANTITY_OUT_OF_RANGE,
    EATEN_FRACTION_OUT_OF_RANGE,
    REFERENCE_GRAMS_OUT_OF_RANGE,
    PER_100G_REFERENCE_INVALID,
    PREPARATION_MISMATCH,
    NUTRIENT_OUT_OF_RANGE,
    NUTRIENT_RANGE_UNORDERED,
    ENERGY_OUT_OF_RANGE,
    ENERGY_RANGE_UNORDERED,
    CATALOG_REFERENCE_REQUIRED,
    CATALOG_REFERENCE_INVALID,
    CATALOG_REFERENCE_NOT_ALLOWED,
    PHYSICAL_MASS_EXCEEDS_REFERENCE,
    ESTIMATE_ID_INVALID,
    MEAL_NAME_INVALID,
    DRAFT_REVISION_INVALID,
    THERAPY_CARBOHYDRATES_OUT_OF_RANGE,
    CANONICAL_THERAPY_METADATA_INVALID,
    ARITHMETIC_OVERFLOW
}

data class NutritionViolation(
    val code: NutritionViolationCode,
    val ingredientId: String? = null,
    val field: String? = null
)

class MealNutritionValidationResult(violations: List<NutritionViolation>) {
    val violations: List<NutritionViolation> = violations.toList()
    val isValid: Boolean get() = violations.isEmpty()
}

object MealNutritionValidator {

    fun validateIngredients(ingredients: List<MealIngredient>): MealNutritionValidationResult {
        val violations = mutableListOf<NutritionViolation>()
        if (ingredients.size !in 1..MealNutritionBounds.MAX_INGREDIENTS) {
            violations += NutritionViolation(NutritionViolationCode.INGREDIENT_COUNT_OUT_OF_RANGE)
        }

        val seenIds = mutableSetOf<String>()
        ingredients.forEach { ingredient ->
            validateIngredient(ingredient, seenIds, violations)
        }
        return MealNutritionValidationResult(violations)
    }

    fun validateDraft(draft: MealNutritionDraft): MealNutritionValidationResult {
        val violations = mutableListOf<NutritionViolation>()
        if (!isBoundedText(draft.original.estimateId, MealNutritionBounds.MAX_ESTIMATE_ID_LENGTH)) {
            violations += NutritionViolation(NutritionViolationCode.ESTIMATE_ID_INVALID)
        }
        if (!isBoundedText(draft.mealName, MealNutritionBounds.MAX_MEAL_NAME_LENGTH)) {
            violations += NutritionViolation(NutritionViolationCode.MEAL_NAME_INVALID)
        }
        if (draft.revision < 0L) {
            violations += NutritionViolation(NutritionViolationCode.DRAFT_REVISION_INVALID)
        }
        violations += validateIngredients(draft.ingredients).violations
        return MealNutritionValidationResult(violations)
    }

    fun validateConfirmationInputs(
        therapyCarbohydrateGrams: Double?,
        canonicalTherapyMetadata: CanonicalTherapyMetadata?
    ): MealNutritionValidationResult {
        val violations = mutableListOf<NutritionViolation>()
        if (therapyCarbohydrateGrams != null && !therapyCarbohydrateGrams.isFiniteNonNegativeAtMost(
                MealNutritionBounds.MAX_THERAPY_CARBOHYDRATE_GRAMS
            )
        ) {
            violations += NutritionViolation(NutritionViolationCode.THERAPY_CARBOHYDRATES_OUT_OF_RANGE)
        }
        if (canonicalTherapyMetadata != null && (
                !isBoundedText(canonicalTherapyMetadata.boundId, MealNutritionBounds.MAX_CANONICAL_ID_LENGTH) ||
                    !isBoundedText(
                        canonicalTherapyMetadata.revision,
                        MealNutritionBounds.MAX_CANONICAL_REVISION_LENGTH
                    )
                )
        ) {
            violations += NutritionViolation(NutritionViolationCode.CANONICAL_THERAPY_METADATA_INVALID)
        }
        return MealNutritionValidationResult(violations)
    }

    private fun validateIngredient(
        ingredient: MealIngredient,
        seenIds: MutableSet<String>,
        violations: MutableList<NutritionViolation>
    ) {
        if (!isBoundedText(ingredient.id, MealNutritionBounds.MAX_INGREDIENT_ID_LENGTH)) {
            violations += ingredient.violation(NutritionViolationCode.INGREDIENT_ID_INVALID, "id")
        } else if (!seenIds.add(ingredient.id)) {
            violations += ingredient.violation(NutritionViolationCode.INGREDIENT_ID_DUPLICATE, "id")
        }
        if (!isBoundedText(ingredient.name, MealNutritionBounds.MAX_INGREDIENT_NAME_LENGTH)) {
            violations += ingredient.violation(NutritionViolationCode.INGREDIENT_NAME_INVALID, "name")
        }
        if (!ingredient.amount.grams.isFiniteIn(
                MealNutritionBounds.MIN_QUANTITY_GRAMS,
                MealNutritionBounds.MAX_QUANTITY_GRAMS
            )
        ) {
            violations += ingredient.violation(NutritionViolationCode.QUANTITY_OUT_OF_RANGE, "amount.grams")
        }
        if (!ingredient.amount.eatenFraction.isFiniteIn(0.0, 1.0)) {
            violations += ingredient.violation(
                NutritionViolationCode.EATEN_FRACTION_OUT_OF_RANGE,
                "amount.eatenFraction"
            )
        }

        val reference = ingredient.facts.reference
        if (!reference.referenceGrams.isFiniteIn(
                MealNutritionBounds.MIN_REFERENCE_GRAMS,
                MealNutritionBounds.MAX_REFERENCE_GRAMS
            )
        ) {
            violations += ingredient.violation(
                NutritionViolationCode.REFERENCE_GRAMS_OUT_OF_RANGE,
                "facts.reference.referenceGrams"
            )
        }
        if (reference.type == NutrientReferenceType.PER_100G &&
            (!reference.referenceGrams.isFinite() || abs(reference.referenceGrams - 100.0) > 1e-9)
        ) {
            violations += ingredient.violation(
                NutritionViolationCode.PER_100G_REFERENCE_INVALID,
                "facts.reference.referenceGrams"
            )
        }

        val amountPreparation = ingredient.amount.preparationState
        val factsPreparation = ingredient.facts.preparationState
        if (amountPreparation != PreparationState.UNKNOWN &&
            factsPreparation != PreparationState.UNKNOWN &&
            amountPreparation != factsPreparation
        ) {
            violations += ingredient.violation(NutritionViolationCode.PREPARATION_MISMATCH, "preparationState")
        }

        ingredient.facts.presentNutrients().forEach { (field, nutrient) ->
            validateNutrient(ingredient, field, nutrient, reference.referenceGrams, violations)
        }
        ingredient.facts.energy?.let { energy ->
            validateEnergy(ingredient, energy, violations)
        }
        validatePhysicalMass(ingredient, violations)
    }

    private fun validateNutrient(
        ingredient: MealIngredient,
        field: String,
        nutrient: KnownNutrient,
        referenceGrams: Double,
        violations: MutableList<NutritionViolation>
    ) {
        val rangeValid = validateRange(
            ingredient = ingredient,
            field = field,
            range = nutrient.amount,
            maximum = MealNutritionBounds.MAX_NUTRIENT_GRAMS,
            outOfRangeCode = NutritionViolationCode.NUTRIENT_OUT_OF_RANGE,
            unorderedCode = NutritionViolationCode.NUTRIENT_RANGE_UNORDERED,
            violations = violations
        )
        validateCatalogReference(ingredient, field, nutrient.source, nutrient.catalogReference, violations)
        if (rangeValid && referenceGrams.isFinite() &&
            nutrient.amount.maximum > referenceGrams + MealNutritionBounds.LABEL_ROUNDING_TOLERANCE_GRAMS
        ) {
            violations += ingredient.violation(NutritionViolationCode.PHYSICAL_MASS_EXCEEDS_REFERENCE, field)
        }
    }

    private fun validateEnergy(
        ingredient: MealIngredient,
        energy: DeclaredEnergy,
        violations: MutableList<NutritionViolation>
    ) {
        validateRange(
            ingredient = ingredient,
            field = "energy",
            range = energy.amount,
            maximum = MealNutritionBounds.MAX_ENERGY,
            outOfRangeCode = NutritionViolationCode.ENERGY_OUT_OF_RANGE,
            unorderedCode = NutritionViolationCode.ENERGY_RANGE_UNORDERED,
            violations = violations
        )
        validateCatalogReference(ingredient, "energy", energy.source, energy.catalogReference, violations)
    }

    private fun validateRange(
        ingredient: MealIngredient,
        field: String,
        range: NutritionRange,
        maximum: Double,
        outOfRangeCode: NutritionViolationCode,
        unorderedCode: NutritionViolationCode,
        violations: MutableList<NutritionViolation>
    ): Boolean {
        val valuesValid = range.minimum.isFiniteNonNegativeAtMost(maximum) &&
            range.maximum.isFiniteNonNegativeAtMost(maximum)
        if (!valuesValid) {
            violations += ingredient.violation(outOfRangeCode, field)
        }
        val ordered = range.minimum <= range.maximum
        if (valuesValid && !ordered) {
            violations += ingredient.violation(unorderedCode, field)
        }
        return valuesValid && ordered
    }

    private fun validateCatalogReference(
        ingredient: MealIngredient,
        field: String,
        source: NutrientSource,
        catalogReference: CatalogReference?,
        violations: MutableList<NutritionViolation>
    ) {
        when {
            source == NutrientSource.VERIFIED_CATALOG && catalogReference == null ->
                violations += ingredient.violation(NutritionViolationCode.CATALOG_REFERENCE_REQUIRED, field)

            source == NutrientSource.VERIFIED_CATALOG &&
                catalogReference != null &&
                !catalogReference.isValid() ->
                violations += ingredient.violation(NutritionViolationCode.CATALOG_REFERENCE_INVALID, field)

            source != NutrientSource.VERIFIED_CATALOG && catalogReference != null ->
                violations += ingredient.violation(NutritionViolationCode.CATALOG_REFERENCE_NOT_ALLOWED, field)
        }
    }

    private fun validatePhysicalMass(
        ingredient: MealIngredient,
        violations: MutableList<NutritionViolation>
    ) {
        val facts = ingredient.facts
        if (!facts.reference.referenceGrams.isFinite()) return
        val protein = facts.protein.physicalUpperOrZero() ?: return
        val fat = facts.fat.physicalUpperOrZero() ?: return
        val carbohydrates = facts.carbohydrates.physicalUpperOrZero() ?: return
        val fiber = facts.fiber.physicalUpperOrZero() ?: return
        val carbohydrateMass = when (facts.carbohydrateBasis) {
            CarbohydrateBasis.TOTAL -> max(carbohydrates, fiber)
            CarbohydrateBasis.AVAILABLE -> safeSum(carbohydrates, fiber) ?: return
            CarbohydrateBasis.UNKNOWN -> max(carbohydrates, fiber)
        }
        val physicalMass = safeSum(protein, fat, carbohydrateMass) ?: run {
            violations += ingredient.violation(NutritionViolationCode.ARITHMETIC_OVERFLOW, "physicalMass")
            return
        }
        if (physicalMass > facts.reference.referenceGrams + MealNutritionBounds.LABEL_ROUNDING_TOLERANCE_GRAMS) {
            violations += ingredient.violation(
                NutritionViolationCode.PHYSICAL_MASS_EXCEEDS_REFERENCE,
                "physicalMass"
            )
        }
    }

    private fun CatalogReference.isValid(): Boolean =
        isBoundedText(id, MealNutritionBounds.MAX_CATALOG_ID_LENGTH) &&
            isBoundedText(version, MealNutritionBounds.MAX_CATALOG_VERSION_LENGTH)

    private fun MealIngredient.violation(
        code: NutritionViolationCode,
        field: String
    ): NutritionViolation = NutritionViolation(code, id, field)

    private fun NutritionRange.validUpperOrNull(): Double? = maximum.takeIf {
        minimum.isFinite() && maximum.isFinite() && minimum >= 0.0 && minimum <= maximum
    }

    private fun KnownNutrient?.physicalUpperOrZero(): Double? =
        this?.amount?.validUpperOrNull() ?: if (this == null) 0.0 else null

    private fun isBoundedText(value: String, maximumLength: Int): Boolean =
        value.isNotBlank() && value.length <= maximumLength

    private fun Double.isFiniteIn(minimum: Double, maximum: Double): Boolean =
        isFinite() && this in minimum..maximum

    private fun Double.isFiniteNonNegativeAtMost(maximum: Double): Boolean =
        isFinite() && this >= 0.0 && this <= maximum

    private fun safeSum(vararg values: Double): Double? {
        var result = 0.0
        values.forEach { value ->
            result += value
            if (!result.isFinite()) return null
        }
        return result
    }
}

private fun NutrientFacts.presentNutrients(): List<Pair<String, KnownNutrient>> = listOfNotNull(
    carbohydrates?.let { "carbohydrates" to it },
    protein?.let { "protein" to it },
    fat?.let { "fat" to it },
    fiber?.let { "fiber" to it },
    sugar?.let { "sugar" to it },
    polyols?.let { "polyols" to it }
)
