package io.aaps.copilot.domain.nutrition

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class MealNutritionValidatorTest {

    @Test
    fun rejectsNonFiniteUnorderedAndOutOfBoundsNumbers() {
        val ingredients = listOf(
            ingredient("nan-amount", amount = IngredientAmount(Double.NaN, 1.0, PreparationState.RAW)),
            ingredient("infinite-fraction", amount = IngredientAmount(100.0, Double.POSITIVE_INFINITY, PreparationState.RAW)),
            ingredient(
                "zero-reference",
                facts = facts(reference = NutritionReference(NutrientReferenceType.PER_SERVING, 0.0))
            ),
            ingredient(
                "unordered-range",
                facts = facts(carbohydrates = KnownNutrient(NutritionRange(12.0, 8.0), NutrientSource.USER))
            ),
            ingredient(
                "infinite-energy",
                facts = facts(
                    energy = DeclaredEnergy(
                        NutritionRange.exact(Double.POSITIVE_INFINITY),
                        EnergyUnit.KCAL,
                        NutrientSource.LABEL
                    )
                )
            ),
            ingredient(
                "overflow-range",
                facts = facts(carbohydrates = KnownNutrient(NutritionRange.exact(Double.MAX_VALUE), NutrientSource.USER))
            )
        )

        val violations = MealNutritionValidator.validateIngredients(ingredients).violations

        assertThat(violations.map { it.code }).containsAtLeast(
            NutritionViolationCode.QUANTITY_OUT_OF_RANGE,
            NutritionViolationCode.EATEN_FRACTION_OUT_OF_RANGE,
            NutritionViolationCode.REFERENCE_GRAMS_OUT_OF_RANGE,
            NutritionViolationCode.NUTRIENT_RANGE_UNORDERED,
            NutritionViolationCode.ENERGY_OUT_OF_RANGE,
            NutritionViolationCode.NUTRIENT_OUT_OF_RANGE
        )
    }

    @Test
    fun guardsIngredientCountIdentityNamesAndReferenceSemantics() {
        val tooMany = (1..21).map { ingredient("ingredient-$it") }
        val malformed = listOf(
            ingredient(" "),
            ingredient("duplicate"),
            ingredient("duplicate"),
            ingredient("long-name", name = "n".repeat(121)),
            ingredient(
                "hybrid-reference",
                facts = facts(reference = NutritionReference(NutrientReferenceType.PER_100G, 80.0))
            ),
            ingredient("too-heavy", amount = IngredientAmount(5_001.0, 1.0, PreparationState.RAW))
        )

        assertThat(MealNutritionValidator.validateIngredients(emptyList()).violations.map { it.code })
            .contains(NutritionViolationCode.INGREDIENT_COUNT_OUT_OF_RANGE)
        assertThat(MealNutritionValidator.validateIngredients(tooMany).violations.map { it.code })
            .contains(NutritionViolationCode.INGREDIENT_COUNT_OUT_OF_RANGE)
        assertThat(MealNutritionValidator.validateIngredients(malformed).violations.map { it.code })
            .containsAtLeast(
                NutritionViolationCode.INGREDIENT_ID_INVALID,
                NutritionViolationCode.INGREDIENT_ID_DUPLICATE,
                NutritionViolationCode.INGREDIENT_NAME_INVALID,
                NutritionViolationCode.PER_100G_REFERENCE_INVALID,
                NutritionViolationCode.QUANTITY_OUT_OF_RANGE
            )
    }

    @Test
    fun requiresBoundedCatalogIdentityOnlyForVerifiedCatalogValues() {
        val missingCatalog = ingredient(
            "missing-catalog",
            facts = facts(
                carbohydrates = KnownNutrient(
                    NutritionRange.exact(10.0),
                    NutrientSource.VERIFIED_CATALOG
                )
            )
        )
        val malformedCatalog = ingredient(
            "malformed-catalog",
            facts = facts(
                carbohydrates = KnownNutrient(
                    NutritionRange.exact(10.0),
                    NutrientSource.VERIFIED_CATALOG,
                    CatalogReference(" ", "v".repeat(65))
                )
            )
        )
        val implicitPromotion = ingredient(
            "implicit-promotion",
            facts = facts(
                carbohydrates = KnownNutrient(
                    NutritionRange.exact(10.0),
                    NutrientSource.AI_ESTIMATE,
                    CatalogReference("fdc-1", "2026-09")
                )
            )
        )
        val validCatalog = ingredient(
            "valid-catalog",
            facts = facts(
                carbohydrates = KnownNutrient(
                    NutritionRange.exact(10.0),
                    NutrientSource.VERIFIED_CATALOG,
                    CatalogReference("fdc-1", "2026-09")
                )
            )
        )

        assertThat(MealNutritionValidator.validateIngredients(listOf(missingCatalog)).violations.map { it.code })
            .contains(NutritionViolationCode.CATALOG_REFERENCE_REQUIRED)
        assertThat(MealNutritionValidator.validateIngredients(listOf(malformedCatalog)).violations.map { it.code })
            .contains(NutritionViolationCode.CATALOG_REFERENCE_INVALID)
        assertThat(MealNutritionValidator.validateIngredients(listOf(implicitPromotion)).violations.map { it.code })
            .contains(NutritionViolationCode.CATALOG_REFERENCE_NOT_ALLOWED)
        assertThat(MealNutritionValidator.validateIngredients(listOf(validCatalog)).isValid).isTrue()
    }

    @Test
    fun physicalMassUsesCarbohydrateBasisAndIgnoresSugarAndPolyolsAsAdditionalMass() {
        val totalBasis = ingredient(
            "total",
            facts = facts(
                carbohydrateBasis = CarbohydrateBasis.TOTAL,
                carbohydrates = nutrient(80.0),
                protein = nutrient(20.0),
                fat = nutrient(1.0),
                fiber = nutrient(30.0),
                sugar = nutrient(90.0),
                polyols = nutrient(90.0)
            )
        )
        val availableBasis = ingredient(
            "available",
            facts = facts(
                carbohydrateBasis = CarbohydrateBasis.AVAILABLE,
                carbohydrates = nutrient(80.0),
                protein = nutrient(10.0),
                fat = nutrient(1.0),
                fiber = nutrient(11.01)
            )
        )

        assertThat(MealNutritionValidator.validateIngredients(listOf(totalBasis)).isValid).isTrue()
        assertThat(MealNutritionValidator.validateIngredients(listOf(availableBasis)).violations.map { it.code })
            .contains(NutritionViolationCode.PHYSICAL_MASS_EXCEEDS_REFERENCE)
    }

    @Test
    fun physicalMassAllowsExplicitRoundingToleranceButRejectsBeyondIt() {
        val withinTolerance = ingredient(
            "within",
            facts = facts(carbohydrates = nutrient(50.5), protein = nutrient(49.5), fat = nutrient(1.0))
        )
        val beyondTolerance = ingredient(
            "beyond",
            facts = facts(carbohydrates = nutrient(50.51), protein = nutrient(49.5), fat = nutrient(1.0))
        )

        assertThat(MealNutritionValidator.validateIngredients(listOf(withinTolerance)).isValid).isTrue()
        assertThat(MealNutritionValidator.validateIngredients(listOf(beyondTolerance)).violations.map { it.code })
            .contains(NutritionViolationCode.PHYSICAL_MASS_EXCEEDS_REFERENCE)
    }

    @Test
    fun validatesDraftAndConfirmationMetadataBounds() {
        val original = OriginalAiMealEstimate(
            estimateId = " ",
            mealName = "m".repeat(121),
            ingredients = listOf(ingredient("valid"))
        )
        val draft = original.toDraft()

        val draftCodes = MealNutritionValidator.validateDraft(draft).violations.map { it.code }
        val confirmationCodes = MealNutritionValidator.validateConfirmationInputs(
            therapyCarbohydrateGrams = Double.NaN,
            canonicalTherapyMetadata = CanonicalTherapyMetadata(" ", "r".repeat(129))
        ).violations.map { it.code }

        assertThat(draftCodes).containsAtLeast(
            NutritionViolationCode.ESTIMATE_ID_INVALID,
            NutritionViolationCode.MEAL_NAME_INVALID
        )
        assertThat(confirmationCodes).containsAtLeast(
            NutritionViolationCode.THERAPY_CARBOHYDRATES_OUT_OF_RANGE,
            NutritionViolationCode.CANONICAL_THERAPY_METADATA_INVALID
        )
    }

    private fun ingredient(
        id: String,
        name: String = "Ingredient $id",
        amount: IngredientAmount = IngredientAmount(100.0, 1.0, PreparationState.RAW),
        facts: NutrientFacts = facts()
    ): MealIngredient = MealIngredient(id, name, amount, facts)

    private fun facts(
        reference: NutritionReference = NutritionReference(NutrientReferenceType.PER_100G, 100.0),
        carbohydrateBasis: CarbohydrateBasis = CarbohydrateBasis.TOTAL,
        carbohydrates: KnownNutrient? = nutrient(10.0),
        protein: KnownNutrient? = nutrient(5.0),
        fat: KnownNutrient? = nutrient(2.0),
        fiber: KnownNutrient? = null,
        sugar: KnownNutrient? = null,
        polyols: KnownNutrient? = null,
        energy: DeclaredEnergy? = null
    ): NutrientFacts = NutrientFacts(
        reference = reference,
        preparationState = PreparationState.RAW,
        carbohydrateBasis = carbohydrateBasis,
        carbohydrates = carbohydrates,
        protein = protein,
        fat = fat,
        fiber = fiber,
        sugar = sugar,
        polyols = polyols,
        energy = energy
    )

    private fun nutrient(grams: Double): KnownNutrient =
        KnownNutrient(NutritionRange.exact(grams), NutrientSource.LABEL)
}
