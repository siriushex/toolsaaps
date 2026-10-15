package io.aaps.copilot.domain.nutrition

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class MealNutritionCalculatorTest {

    @Test
    fun scalesPer100gAndPerServingByConsumedMassAndEatenFraction() {
        val result = calculated(
            listOf(
                ingredient(
                    id = "rice",
                    amountGrams = 250.0,
                    eatenFraction = 0.5,
                    reference = NutritionReference(NutrientReferenceType.PER_100G, 100.0),
                    carbohydrates = nutrient(10.0)
                ),
                ingredient(
                    id = "sauce",
                    amountGrams = 80.0,
                    eatenFraction = 0.5,
                    reference = NutritionReference(NutrientReferenceType.PER_SERVING, 40.0),
                    carbohydrates = nutrient(6.0)
                )
            )
        )

        assertThat(result.carbohydrates.knownSubtotalGrams.minimum).isWithin(1e-9).of(18.5)
        assertThat(result.carbohydrates.completeTotalGrams!!.maximum).isWithin(1e-9).of(18.5)
        assertThat(result.carbohydrates.completeBasis).isEqualTo(CarbohydrateBasis.TOTAL)
        assertThat(result.carbohydrates.knownIngredientCount).isEqualTo(2)
        assertThat(result.carbohydrates.includedIngredientCount).isEqualTo(2)
    }

    @Test
    fun knownZeroContributesCoverageWhileUnknownLeavesOnlyAPartialSubtotal() {
        val result = calculated(
            listOf(
                ingredient(id = "zero", carbohydrates = nutrient(0.0)),
                ingredient(id = "unknown", carbohydrates = null)
            )
        )

        assertThat(result.carbohydrates.knownSubtotalGrams).isEqualTo(NutritionRange.exact(0.0))
        assertThat(result.carbohydrates.knownIngredientCount).isEqualTo(1)
        assertThat(result.carbohydrates.includedIngredientCount).isEqualTo(2)
        assertThat(result.carbohydrates.completeTotalGrams).isNull()
    }

    @Test
    fun rejectsRawCookedMismatchAndLeavesUnknownPreparationUnresolved() {
        val mismatch = MealNutritionCalculator.calculate(
            listOf(
                ingredient(
                    id = "mismatch",
                    amountPreparation = PreparationState.COOKED,
                    factsPreparation = PreparationState.RAW,
                    carbohydrates = nutrient(20.0)
                )
            )
        )

        assertThat(mismatch).isInstanceOf(MealNutritionCalculationResult.Invalid::class.java)
        assertThat((mismatch as MealNutritionCalculationResult.Invalid).violations.map { it.code })
            .contains(NutritionViolationCode.PREPARATION_MISMATCH)

        val unresolved = calculated(
            listOf(
                ingredient(
                    id = "unknown-prep",
                    amountPreparation = PreparationState.UNKNOWN,
                    factsPreparation = PreparationState.COOKED,
                    carbohydrates = nutrient(20.0)
                )
            )
        )

        assertThat(unresolved.carbohydrates.knownIngredientCount).isEqualTo(0)
        assertThat(unresolved.carbohydrates.completeTotalGrams).isNull()
        assertThat(unresolved.diagnostics.map { it.code })
            .contains(NutritionDiagnosticCode.PREPARATION_UNRESOLVED)
    }

    @Test
    fun totalCarbsDoNotDoubleCountFiberAndAnalyticSubfractionsOnlyProduceADiagnostic() {
        val result = calculated(
            listOf(
                ingredient(
                    id = "foundation-food",
                    carbohydrates = nutrient(10.0),
                    protein = nutrient(2.0),
                    fat = nutrient(1.0),
                    fiber = nutrient(95.0),
                    sugar = nutrient(12.0),
                    polyols = nutrient(3.0),
                    carbohydrateBasis = CarbohydrateBasis.TOTAL
                )
            )
        )

        assertThat(result.carbohydrates.completeTotalGrams).isEqualTo(NutritionRange.exact(10.0))
        assertThat(result.carbohydrates.completeBasis).isEqualTo(CarbohydrateBasis.TOTAL)
        assertThat(result.diagnostics.map { it.code })
            .contains(NutritionDiagnosticCode.CARBOHYDRATE_SUBFRACTION_VARIATION)
    }

    @Test
    fun mixedAndUnknownCarbohydrateBasesNeverBecomeACompatibleCompleteTotal() {
        val mixed = calculated(
            listOf(
                ingredient(id = "total", carbohydrates = nutrient(10.0)),
                ingredient(
                    id = "available",
                    carbohydrates = nutrient(8.0),
                    carbohydrateBasis = CarbohydrateBasis.AVAILABLE
                )
            )
        )

        assertThat(mixed.carbohydrates.knownSubtotalGrams).isEqualTo(NutritionRange.exact(18.0))
        assertThat(mixed.carbohydrates.completeTotalGrams).isNull()
        assertThat(mixed.carbohydrates.completeBasis).isNull()
        assertThat(mixed.diagnostics.map { it.code })
            .contains(NutritionDiagnosticCode.MIXED_CARBOHYDRATE_BASIS)

        val unknown = calculated(
            listOf(
                ingredient(
                    id = "unknown-basis",
                    carbohydrates = nutrient(10.0),
                    fiber = nutrient(4.0),
                    carbohydrateBasis = CarbohydrateBasis.UNKNOWN
                )
            )
        )

        assertThat(unknown.carbohydrates.completeTotalGrams).isNull()
        assertThat(unknown.carbohydrates.completeBasis).isNull()
        assertThat(unknown.diagnostics.map { it.code })
            .containsAtLeast(
                NutritionDiagnosticCode.CARBOHYDRATE_BASIS_UNRESOLVED,
                NutritionDiagnosticCode.FIBER_INCLUSION_UNRESOLVED
            )
    }

    @Test
    fun convertsDeclaredKilojoulesWithoutReplacingThemWithMacroEnergy() {
        val food = ingredient(
            id = "labelled",
            carbohydrates = nutrient(10.0, NutrientSource.LABEL),
            protein = nutrient(5.0, NutrientSource.LABEL),
            fat = nutrient(2.0, NutrientSource.LABEL),
            energy = DeclaredEnergy(
                amount = NutritionRange.exact(100.0),
                unit = EnergyUnit.KJ,
                source = NutrientSource.LABEL
            )
        )

        val result = calculated(listOf(food))

        assertThat(food.facts.energy!!.amount).isEqualTo(NutritionRange.exact(100.0))
        assertThat(food.facts.energy!!.unit).isEqualTo(EnergyUnit.KJ)
        assertThat(result.declaredEnergy.completeTotalKilojoules).isEqualTo(NutritionRange.exact(100.0))
        assertThat(result.declaredEnergy.completeTotalKilocalories!!.minimum)
            .isWithin(1e-9).of(100.0 / 4.184)
        assertThat(result.approximateMacroEnergyKilocalories).isEqualTo(NutritionRange.exact(78.0))
        assertThat(result.approximateMacroEnergyKilocalories)
            .isNotEqualTo(result.declaredEnergy.completeTotalKilocalories)

        val kcalResult = calculated(
            listOf(
                ingredient(
                    id = "kcal-label",
                    energy = DeclaredEnergy(
                        amount = NutritionRange.exact(10.0),
                        unit = EnergyUnit.KCAL,
                        source = NutrientSource.LABEL
                    )
                )
            )
        )
        assertThat(kcalResult.declaredEnergy.completeTotalKilocalories)
            .isEqualTo(NutritionRange.exact(10.0))
        assertThat(kcalResult.declaredEnergy.completeTotalKilojoules!!.minimum)
            .isWithin(1e-9).of(41.84)
    }

    @Test
    fun approximateMacroEnergyRequiresCompleteCompatibleMacros() {
        val missingProtein = calculated(
            listOf(
                ingredient(
                    id = "partial",
                    carbohydrates = nutrient(10.0),
                    protein = null,
                    fat = nutrient(2.0)
                )
            )
        )
        val unknownCarbBasis = calculated(
            listOf(
                ingredient(
                    id = "ambiguous",
                    carbohydrates = nutrient(10.0),
                    protein = nutrient(5.0),
                    fat = nutrient(2.0),
                    carbohydrateBasis = CarbohydrateBasis.UNKNOWN
                )
            )
        )

        assertThat(missingProtein.approximateMacroEnergyKilocalories).isNull()
        assertThat(unknownCarbBasis.approximateMacroEnergyKilocalories).isNull()
    }

    @Test
    fun scalesOrderedRangesWithoutCollapsingUncertainty() {
        val result = calculated(
            listOf(
                ingredient(
                    id = "range",
                    amountGrams = 50.0,
                    carbohydrates = KnownNutrient(
                        amount = NutritionRange(8.0, 12.0),
                        source = NutrientSource.AI_ESTIMATE
                    )
                )
            )
        )

        assertThat(result.carbohydrates.completeTotalGrams).isEqualTo(NutritionRange(4.0, 6.0))
    }

    private fun calculated(ingredients: List<MealIngredient>): MealNutritionCalculation =
        when (val result = MealNutritionCalculator.calculate(ingredients)) {
            is MealNutritionCalculationResult.Calculated -> result.value
            is MealNutritionCalculationResult.Invalid -> error("Unexpected invalid result: ${result.violations}")
        }

    private fun ingredient(
        id: String,
        amountGrams: Double = 100.0,
        eatenFraction: Double = 1.0,
        amountPreparation: PreparationState = PreparationState.COOKED,
        factsPreparation: PreparationState = amountPreparation,
        reference: NutritionReference = NutritionReference(NutrientReferenceType.PER_100G, 100.0),
        carbohydrates: KnownNutrient? = nutrient(10.0),
        protein: KnownNutrient? = nutrient(5.0),
        fat: KnownNutrient? = nutrient(2.0),
        fiber: KnownNutrient? = null,
        sugar: KnownNutrient? = null,
        polyols: KnownNutrient? = null,
        carbohydrateBasis: CarbohydrateBasis = CarbohydrateBasis.TOTAL,
        energy: DeclaredEnergy? = null
    ): MealIngredient = MealIngredient(
        id = id,
        name = "Ingredient $id",
        amount = IngredientAmount(amountGrams, eatenFraction, amountPreparation),
        facts = NutrientFacts(
            reference = reference,
            preparationState = factsPreparation,
            carbohydrateBasis = carbohydrateBasis,
            carbohydrates = carbohydrates,
            protein = protein,
            fat = fat,
            fiber = fiber,
            sugar = sugar,
            polyols = polyols,
            energy = energy
        )
    )

    private fun nutrient(
        grams: Double,
        source: NutrientSource = NutrientSource.AI_ESTIMATE
    ): KnownNutrient = KnownNutrient(NutritionRange.exact(grams), source)
}
