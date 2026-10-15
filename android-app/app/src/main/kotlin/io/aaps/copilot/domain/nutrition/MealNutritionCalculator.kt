package io.aaps.copilot.domain.nutrition

import io.aaps.copilot.domain.profile.MealAbsorptionSelection

object MealNutritionCalculator {
    private const val KILOJOULES_PER_KILOCALORIE = 4.184

    fun calculate(ingredients: List<MealIngredient>): MealNutritionCalculationResult {
        val validation = MealNutritionValidator.validateIngredients(ingredients)
        if (!validation.isValid) {
            return MealNutritionCalculationResult.Invalid(validation.violations)
        }
        return calculateValidated(ingredients)
    }

    fun calculate(draft: MealNutritionDraft): MealNutritionCalculationResult {
        val validation = MealNutritionValidator.validateDraft(draft)
        if (!validation.isValid) {
            return MealNutritionCalculationResult.Invalid(validation.violations)
        }
        return calculateValidated(draft.ingredients)
    }

    fun confirm(
        draft: MealNutritionDraft,
        therapyCarbohydrateGrams: Double?,
        absorptionSelection: MealAbsorptionSelection? = null,
        canonicalTherapyMetadata: CanonicalTherapyMetadata? = null
    ): MealNutritionConfirmationResult {
        val violations = buildList {
            addAll(MealNutritionValidator.validateDraft(draft).violations)
            addAll(
                MealNutritionValidator.validateConfirmationInputs(
                    therapyCarbohydrateGrams,
                    canonicalTherapyMetadata
                ).violations
            )
        }
        if (violations.isNotEmpty()) return MealNutritionConfirmationResult.Invalid(violations)

        return when (val result = calculateValidated(draft.ingredients)) {
            is MealNutritionCalculationResult.Invalid ->
                MealNutritionConfirmationResult.Invalid(result.violations)

            is MealNutritionCalculationResult.Calculated -> MealNutritionConfirmationResult.Confirmed(
                ConfirmedMealNutritionSnapshot(
                    estimateId = draft.original.estimateId,
                    draftRevision = draft.revision,
                    mealName = draft.mealName,
                    ingredients = draft.ingredients,
                    nutrition = result.value,
                    explicitTherapyCarbohydrateGrams = therapyCarbohydrateGrams,
                    absorptionSelection = absorptionSelection,
                    canonicalTherapyMetadata = canonicalTherapyMetadata
                )
            )
        }
    }

    private fun calculateValidated(ingredients: List<MealIngredient>): MealNutritionCalculationResult {
        val includedCount = ingredients.size
        val carbohydrateAccumulator = RangeAccumulator(includedCount)
        val proteinAccumulator = RangeAccumulator(includedCount)
        val fatAccumulator = RangeAccumulator(includedCount)
        val fiberAccumulator = RangeAccumulator(includedCount)
        val sugarAccumulator = RangeAccumulator(includedCount)
        val polyolAccumulator = RangeAccumulator(includedCount)
        val energyAccumulator = EnergyAccumulator(includedCount)
        val carbohydrateBases = linkedSetOf<CarbohydrateBasis>()
        val diagnostics = mutableListOf<NutritionDiagnostic>()

        ingredients.forEach { ingredient ->
            if (!hasResolvedPreparation(ingredient)) {
                diagnostics += NutritionDiagnostic(
                    NutritionDiagnosticCode.PREPARATION_UNRESOLVED,
                    ingredient.id
                )
                return@forEach
            }

            val consumedMass = safeMultiply(ingredient.amount.grams, ingredient.amount.eatenFraction)
                ?: return overflow("amount.consumedMass", ingredient.id)
            val scale = consumedMass / ingredient.facts.reference.referenceGrams
            if (!scale.isFinite() || scale < 0.0) return overflow("amount.scale", ingredient.id)

            val facts = ingredient.facts
            if (!carbohydrateAccumulator.add(facts.carbohydrates, scale)) {
                return overflow("carbohydrates", ingredient.id)
            }
            if (facts.carbohydrates != null) {
                carbohydrateBases += facts.carbohydrateBasis
                when (facts.carbohydrateBasis) {
                    CarbohydrateBasis.UNKNOWN -> diagnostics += NutritionDiagnostic(
                        NutritionDiagnosticCode.CARBOHYDRATE_BASIS_UNRESOLVED,
                        ingredient.id
                    )

                    CarbohydrateBasis.TOTAL,
                    CarbohydrateBasis.AVAILABLE -> Unit
                }
            }
            if (facts.carbohydrateBasis == CarbohydrateBasis.UNKNOWN && facts.fiber != null) {
                diagnostics += NutritionDiagnostic(
                    NutritionDiagnosticCode.FIBER_INCLUSION_UNRESOLVED,
                    ingredient.id
                )
            }
            if (hasCarbohydrateSubfractionVariation(facts)) {
                diagnostics += NutritionDiagnostic(
                    NutritionDiagnosticCode.CARBOHYDRATE_SUBFRACTION_VARIATION,
                    ingredient.id
                )
            }

            if (!proteinAccumulator.add(facts.protein, scale)) return overflow("protein", ingredient.id)
            if (!fatAccumulator.add(facts.fat, scale)) return overflow("fat", ingredient.id)
            if (!fiberAccumulator.add(facts.fiber, scale)) return overflow("fiber", ingredient.id)
            if (!sugarAccumulator.add(facts.sugar, scale)) return overflow("sugar", ingredient.id)
            if (!polyolAccumulator.add(facts.polyols, scale)) return overflow("polyols", ingredient.id)
            if (!energyAccumulator.add(facts.energy, scale)) return overflow("energy", ingredient.id)
        }

        val carbohydrateBaseAggregate = carbohydrateAccumulator.finish()
        val compatibleBasis = carbohydrateBases.singleOrNull()?.takeUnless {
            it == CarbohydrateBasis.UNKNOWN
        }
        if (carbohydrateBases.size > 1) {
            diagnostics += NutritionDiagnostic(NutritionDiagnosticCode.MIXED_CARBOHYDRATE_BASIS)
        }
        val carbohydrateAggregate = CarbohydrateAggregate(
            knownSubtotalGrams = carbohydrateBaseAggregate.knownSubtotalGrams,
            completeTotalGrams = carbohydrateBaseAggregate.completeTotalGrams.takeIf {
                compatibleBasis != null
            },
            completeBasis = compatibleBasis.takeIf {
                carbohydrateBaseAggregate.completeTotalGrams != null
            },
            knownIngredientCount = carbohydrateBaseAggregate.knownIngredientCount,
            includedIngredientCount = includedCount
        )
        val proteinAggregate = proteinAccumulator.finish()
        val fatAggregate = fatAccumulator.finish()
        val fiberAggregate = fiberAccumulator.finish()
        val sugarAggregate = sugarAccumulator.finish()
        val polyolAggregate = polyolAccumulator.finish()
        val energyAggregate = energyAccumulator.finish()
        val macroEnergy = calculateMacroEnergy(
            carbohydrateAggregate.completeTotalGrams,
            proteinAggregate.completeTotalGrams,
            fatAggregate.completeTotalGrams
        ) ?: if (
            carbohydrateAggregate.completeTotalGrams != null &&
            proteinAggregate.completeTotalGrams != null &&
            fatAggregate.completeTotalGrams != null
        ) {
            return overflow("approximateMacroEnergy", null)
        } else {
            null
        }

        return MealNutritionCalculationResult.Calculated(
            MealNutritionCalculation(
                carbohydrates = carbohydrateAggregate,
                protein = proteinAggregate,
                fat = fatAggregate,
                fiber = fiberAggregate,
                sugar = sugarAggregate,
                polyols = polyolAggregate,
                declaredEnergy = energyAggregate,
                approximateMacroEnergyKilocalories = macroEnergy,
                diagnostics = diagnostics.distinct()
            )
        )
    }

    private fun hasResolvedPreparation(ingredient: MealIngredient): Boolean =
        ingredient.amount.preparationState != PreparationState.UNKNOWN &&
            ingredient.facts.preparationState != PreparationState.UNKNOWN

    private fun hasCarbohydrateSubfractionVariation(facts: NutrientFacts): Boolean {
        val carbohydrates = facts.carbohydrates?.amount ?: return false
        val sugar = facts.sugar?.amount?.maximum ?: 0.0
        val polyols = facts.polyols?.amount?.maximum ?: 0.0
        val subfractionMaximum = safeAdd(sugar, polyols) ?: return true
        return subfractionMaximum >
            carbohydrates.maximum + MealNutritionBounds.LABEL_ROUNDING_TOLERANCE_GRAMS
    }

    private fun calculateMacroEnergy(
        carbohydrates: NutritionRange?,
        protein: NutritionRange?,
        fat: NutritionRange?
    ): NutritionRange? {
        if (carbohydrates == null || protein == null || fat == null) return null
        val minimum = safeAdd(
            safeMultiply(carbohydrates.minimum, 4.0) ?: return null,
            safeMultiply(protein.minimum, 4.0) ?: return null,
            safeMultiply(fat.minimum, 9.0) ?: return null
        ) ?: return null
        val maximum = safeAdd(
            safeMultiply(carbohydrates.maximum, 4.0) ?: return null,
            safeMultiply(protein.maximum, 4.0) ?: return null,
            safeMultiply(fat.maximum, 9.0) ?: return null
        ) ?: return null
        return NutritionRange(minimum, maximum)
    }

    private fun overflow(field: String, ingredientId: String?): MealNutritionCalculationResult.Invalid =
        MealNutritionCalculationResult.Invalid(
            listOf(NutritionViolation(NutritionViolationCode.ARITHMETIC_OVERFLOW, ingredientId, field))
        )

    private class RangeAccumulator(
        private val includedCount: Int
    ) {
        private var minimum = 0.0
        private var maximum = 0.0
        private var knownCount = 0

        fun add(value: KnownNutrient?, scale: Double): Boolean {
            if (value == null) return true
            val scaledMinimum = safeMultiply(value.amount.minimum, scale) ?: return false
            val scaledMaximum = safeMultiply(value.amount.maximum, scale) ?: return false
            minimum = safeAdd(minimum, scaledMinimum) ?: return false
            maximum = safeAdd(maximum, scaledMaximum) ?: return false
            knownCount += 1
            return true
        }

        fun finish(): NutrientAggregate = NutrientAggregate(
            knownSubtotalGrams = NutritionRange(minimum, maximum),
            completeTotalGrams = NutritionRange(minimum, maximum).takeIf {
                knownCount == includedCount
            },
            knownIngredientCount = knownCount,
            includedIngredientCount = includedCount
        )
    }

    private class EnergyAccumulator(
        private val includedCount: Int
    ) {
        private var minimumKilocalories = 0.0
        private var maximumKilocalories = 0.0
        private var minimumKilojoules = 0.0
        private var maximumKilojoules = 0.0
        private var knownCount = 0

        fun add(value: DeclaredEnergy?, scale: Double): Boolean {
            if (value == null) return true
            val kcalRange = when (value.unit) {
                EnergyUnit.KCAL -> value.amount
                EnergyUnit.KJ -> NutritionRange(
                    value.amount.minimum / KILOJOULES_PER_KILOCALORIE,
                    value.amount.maximum / KILOJOULES_PER_KILOCALORIE
                )
            }
            val kjRange = when (value.unit) {
                EnergyUnit.KCAL -> NutritionRange(
                    safeMultiply(value.amount.minimum, KILOJOULES_PER_KILOCALORIE) ?: return false,
                    safeMultiply(value.amount.maximum, KILOJOULES_PER_KILOCALORIE) ?: return false
                )

                EnergyUnit.KJ -> value.amount
            }
            val scaledKcalMinimum = safeMultiply(kcalRange.minimum, scale) ?: return false
            val scaledKcalMaximum = safeMultiply(kcalRange.maximum, scale) ?: return false
            val scaledKjMinimum = safeMultiply(kjRange.minimum, scale) ?: return false
            val scaledKjMaximum = safeMultiply(kjRange.maximum, scale) ?: return false
            minimumKilocalories = safeAdd(minimumKilocalories, scaledKcalMinimum) ?: return false
            maximumKilocalories = safeAdd(maximumKilocalories, scaledKcalMaximum) ?: return false
            minimumKilojoules = safeAdd(minimumKilojoules, scaledKjMinimum) ?: return false
            maximumKilojoules = safeAdd(maximumKilojoules, scaledKjMaximum) ?: return false
            knownCount += 1
            return true
        }

        fun finish(): EnergyAggregate {
            val kilocalories = NutritionRange(minimumKilocalories, maximumKilocalories)
            val kilojoules = NutritionRange(minimumKilojoules, maximumKilojoules)
            val complete = knownCount == includedCount
            return EnergyAggregate(
                knownSubtotalKilocalories = kilocalories,
                knownSubtotalKilojoules = kilojoules,
                completeTotalKilocalories = kilocalories.takeIf { complete },
                completeTotalKilojoules = kilojoules.takeIf { complete },
                knownIngredientCount = knownCount,
                includedIngredientCount = includedCount
            )
        }
    }

    private fun safeMultiply(left: Double, right: Double): Double? =
        (left * right).takeIf { it.isFinite() && it >= 0.0 }

    private fun safeAdd(vararg values: Double): Double? {
        var result = 0.0
        values.forEach { value ->
            result += value
            if (!result.isFinite() || result < 0.0) return null
        }
        return result
    }
}
