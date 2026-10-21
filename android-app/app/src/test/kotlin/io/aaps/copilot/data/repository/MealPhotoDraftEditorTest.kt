package io.aaps.copilot.data.repository

import io.aaps.copilot.domain.nutrition.CarbohydrateBasis
import io.aaps.copilot.domain.nutrition.EnergyUnit
import io.aaps.copilot.domain.nutrition.NutritionRange
import io.aaps.copilot.domain.nutrition.PreparationState
import io.aaps.copilot.domain.profile.MealAbsorptionProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MealPhotoDraftEditorTest {
    @Test
    fun previewIncludesMassAndNutrientUncertaintyWithoutConfirmingAnything() {
        val source = estimate().copy(ingredients = estimate().ingredients.map {
            it.copy(carbohydrates = NutritionRange(40.0, 50.0), protein = NutritionRange(5.0, 10.0),
                fat = NutritionRange(1.0, 3.0), energy = PhotoEnergyEstimate(NutritionRange(200.0, 250.0), EnergyUnit.KCAL))
        })
        val state = MealPhotoDraftEditor.initial(source)
        val preview = checkNotNull(MealPhotoDraftEditor.preview(state))
        assertTrue(preview.hasEstimatedMass)
        assertEquals(NutritionRange(16.0, 40.0), preview.carbohydrateGrams)
        assertEquals(NutritionRange(2.0, 8.0), preview.proteinGrams)
        assertEquals(0.4, preview.fatGrams!!.minimum, 1e-9)
        assertEquals(2.4, preview.fatGrams.maximum, 1e-9)
        assertEquals(NutritionRange(80.0, 200.0), preview.energyKilocalories)
        assertEquals(null, state.confirmedMassesGrams["bread"])
        assertTrue(MealPhotoDraftEditor.build(state) is MealPhotoDraftBuildResult.MissingMass)
        assertEquals(0L, state.revision)
    }

    @Test
    fun previewCombinesConfirmedAndUnconfirmedPortionsAndEatenFractions() {
        val source = estimate().copy(ingredients = listOf(estimate().ingredients.single(),
            estimate().ingredients.single().copy(id = "second", massGrams = NutritionRange(10.0, 20.0))))
        var state = MealPhotoDraftEditor.initial(source)
        state = updated(state, MealPhotoDraftEditor.setMass(state, "bread", 60.0))
        state = updated(state, MealPhotoDraftEditor.setEatenFraction(state, "second", 0.5))
        val preview = checkNotNull(MealPhotoDraftEditor.preview(state))
        assertEquals(NutritionRange(32.5, 35.0), preview.carbohydrateGrams)
        assertTrue(preview.hasEstimatedMass)
        state = updated(state, MealPhotoDraftEditor.setMass(state, "second", 10.0))
        assertEquals(NutritionRange.exact(32.5), MealPhotoDraftEditor.preview(state)!!.carbohydrateGrams)
        assertEquals(false, MealPhotoDraftEditor.preview(state)!!.hasEstimatedMass)
    }

    @Test
    fun previewRetainsZeroLowerBoundAndConvertsKilojoules() {
        val source = estimate().copy(ingredients = estimate().ingredients.map {
            it.copy(massGrams = NutritionRange(0.0, 80.0),
                energy = PhotoEnergyEstimate(NutritionRange.exact(418.4), EnergyUnit.KJ))
        })
        val preview = checkNotNull(MealPhotoDraftEditor.preview(MealPhotoDraftEditor.initial(source)))
        assertEquals(NutritionRange(0.0, 40.0), preview.carbohydrateGrams)
        assertEquals(0.0, preview.energyKilocalories!!.minimum, 0.0)
        assertEquals(80.0, preview.energyKilocalories.maximum, 1e-9)
    }

    @Test
    fun previewCannotTurnUnknownOrMixedFactsIntoWholeMealTotals() {
        val ingredient = estimate().ingredients.single()
        val variants = listOf(ingredient.copy(carbohydrates = null),
            ingredient.copy(preparationState = PreparationState.UNKNOWN),
            ingredient.copy(carbohydrateBasis = CarbohydrateBasis.UNKNOWN),
            ingredient.copy(carbohydrateBasis = CarbohydrateBasis.AVAILABLE))
        variants.forEach { unknown ->
            val source = estimate().copy(ingredients = listOf(ingredient, unknown.copy(id = "second")))
            val preview = checkNotNull(MealPhotoDraftEditor.preview(MealPhotoDraftEditor.initial(source)))
            assertEquals(null, preview.carbohydrateGrams)
            assertEquals(null, preview.proteinGrams)
            assertEquals(null, preview.energyKilocalories)
        }
    }

    @Test
    fun invalidEditsHidePreviewAndCorrectionRestoresIt() {
        val state = MealPhotoDraftEditor.initial(estimate())
        listOf(Double.NaN, Double.POSITIVE_INFINITY, -0.01, 1.01).forEach { invalid ->
            assertEquals(null, MealPhotoDraftEditor.preview(state.copy(eatenFractions = mapOf("bread" to invalid))))
        }
        val invalid = state.copy(invalidMassIds = setOf("bread"))
        assertEquals(null, MealPhotoDraftEditor.preview(invalid))
        assertTrue(MealPhotoDraftEditor.build(invalid) is MealPhotoDraftBuildResult.MissingMass)
        val fixed = updated(invalid, MealPhotoDraftEditor.setMass(invalid, "bread", 50.0))
        assertTrue(fixed.invalidMassIds.isEmpty())
        assertEquals(NutritionRange.exact(25.0), MealPhotoDraftEditor.preview(fixed)!!.carbohydrateGrams)
    }

    @Test
    fun doesNotChooseMidpointAndBuildsOnlyAfterEveryMassIsConfirmed() {
        val estimate = estimate()
        var state = MealPhotoDraftEditor.initial(estimate)
        assertEquals(null, state.confirmedMassesGrams["bread"])
        assertTrue(MealPhotoDraftEditor.build(state) is MealPhotoDraftBuildResult.MissingMass)

        state = updated(state, MealPhotoDraftEditor.setMass(state, "bread", 65.0))
        val built = MealPhotoDraftEditor.build(state)
        assertTrue(built is MealPhotoDraftBuildResult.Ready)
        assertEquals(65.0, (built as MealPhotoDraftBuildResult.Ready).draft.ingredients.first().amount.grams, 0.0)
    }

    @Test
    fun rejectsMassOutsideEstimateAndUnknownIngredient() {
        val state = MealPhotoDraftEditor.initial(estimate())
        assertEquals(
            MealPhotoDraftEditResult.Rejected.Reason.INVALID_MASS,
            (MealPhotoDraftEditor.setMass(state, "bread", 101.0) as MealPhotoDraftEditResult.Rejected).reason
        )
        assertEquals(
            MealPhotoDraftEditResult.Rejected.Reason.UNKNOWN_INGREDIENT,
            (MealPhotoDraftEditor.setMass(state, "unknown", 20.0) as MealPhotoDraftEditResult.Rejected).reason
        )
    }

    @Test
    fun massInsideEstimateStillMustBeSupportedByTheNutritionCalculator() {
        val estimate = estimate().copy(ingredients = estimate().ingredients.map {
            it.copy(massGrams = NutritionRange(0.0, 80.0))
        })
        val state = MealPhotoDraftEditor.initial(estimate)
        listOf(0.0, 0.05).forEach { grams ->
            assertEquals(MealPhotoDraftEditResult.Rejected(MealPhotoDraftEditResult.Rejected.Reason.INVALID_MASS),
                MealPhotoDraftEditor.setMass(state, "bread", grams))
            assertEquals(null, estimate.toOriginalEstimate(mapOf("bread" to grams)))
        }
        val confirmed = updated(state, MealPhotoDraftEditor.setMass(state, "bread", 0.1))
        assertTrue(MealPhotoDraftEditor.build(confirmed) is MealPhotoDraftBuildResult.Ready)
        assertEquals(0.0, estimate.ingredients.single().massGrams.minimum, 0.0)
        val invalidExact = estimate.copy(ingredients = estimate.ingredients.map {
            it.copy(massGrams = NutritionRange.exact(0.05))
        })
        assertEquals(null, MealPhotoDraftEditor.initial(invalidExact).confirmedMassesGrams["bread"])
    }

    @Test
    fun editedEatenFractionAndPreparationStayInDraftOnly() {
        val estimate = estimate().copy(
            ingredients = estimate().ingredients.map {
                it.copy(massGrams = NutritionRange.exact(50.0))
            }
        )
        var state = MealPhotoDraftEditor.initial(estimate)
        state = updated(state, MealPhotoDraftEditor.setEatenFraction(state, "bread", 0.5))
        state = updated(state, MealPhotoDraftEditor.setPreparationState(state, "bread", PreparationState.COOKED))
        val built = MealPhotoDraftEditor.build(state) as MealPhotoDraftBuildResult.Ready
        val ingredient = built.draft.ingredients.first()
        assertEquals(0.5, ingredient.amount.eatenFraction, 0.0)
        assertEquals(PreparationState.COOKED, ingredient.amount.preparationState)
        assertEquals(PreparationState.COOKED, ingredient.facts.preparationState)
        assertEquals(1L, built.draft.revision)
    }

    @Test
    fun profileDurationMustMatchExistingFoodProfileRanges() {
        val state = MealPhotoDraftEditor.initial(estimate())
        assertEquals(
            MealPhotoDraftEditResult.Rejected.Reason.INVALID_DURATION,
            (MealPhotoDraftEditor.setProfile(state, MealAbsorptionProfile.FAST, 90) as MealPhotoDraftEditResult.Rejected).reason
        )
        val accepted = MealPhotoDraftEditor.setProfile(state, MealAbsorptionProfile.FAST, 45)
        assertTrue(accepted is MealPhotoDraftEditResult.Updated)
        val builtState = (accepted as MealPhotoDraftEditResult.Updated).state
        val built = MealPhotoDraftEditor.build(
            MealPhotoDraftEditor.setMass(builtState, "bread", 50.0).let { updated(builtState, it) }
        ) as MealPhotoDraftBuildResult.Ready
        assertEquals(MealAbsorptionProfile.FAST, built.absorptionSelection?.profile)
        assertEquals(45, built.absorptionSelection?.durationMinutes)
    }

    private fun estimate(): MealPhotoNutritionEstimate = MealPhotoNutritionEstimate(
        schemaVersion = 1,
        estimateId = "estimate-1",
        mealName = "Breakfast",
        ingredients = listOf(
            MealPhotoIngredientEstimate(
                id = "bread",
                name = "Bread",
                massGrams = NutritionRange(40.0, 80.0),
                preparationState = PreparationState.COOKED,
                carbohydrateBasis = CarbohydrateBasis.TOTAL,
                carbohydrates = NutritionRange.exact(50.0),
                protein = null,
                fat = null,
                fiber = null,
                sugar = null,
                polyols = null,
                energy = null
            )
        ),
        suggestedProfile = MealAbsorptionProfile.MIXED,
        suggestedDurationMinutes = 120
    )

    private fun updated(
        state: MealPhotoDraftEditorState,
        result: MealPhotoDraftEditResult
    ): MealPhotoDraftEditorState = (result as MealPhotoDraftEditResult.Updated).state
}
