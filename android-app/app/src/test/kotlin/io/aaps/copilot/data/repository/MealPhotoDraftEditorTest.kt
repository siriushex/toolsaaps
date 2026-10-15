package io.aaps.copilot.data.repository

import io.aaps.copilot.domain.nutrition.CarbohydrateBasis
import io.aaps.copilot.domain.nutrition.NutritionRange
import io.aaps.copilot.domain.nutrition.PreparationState
import io.aaps.copilot.domain.profile.MealAbsorptionProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MealPhotoDraftEditorTest {
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
