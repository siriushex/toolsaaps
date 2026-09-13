package io.aaps.copilot.domain.nutrition

import com.google.common.truth.Truth.assertThat
import io.aaps.copilot.domain.profile.MealAbsorptionProfile
import io.aaps.copilot.domain.profile.MealAbsorptionSelection
import org.junit.Test

class MealNutritionModelsTest {

    @Test
    fun repeatedDraftEditsIncrementRevisionAndRetainTheSameUntouchedOriginal() {
        val originalIngredient = ingredient("original", 10.0)
        val original = OriginalAiMealEstimate("estimate-1", "Lunch", listOf(originalIngredient))

        val first = original.toDraft()
        val second = first.revise(ingredients = listOf(ingredient("edited-once", 20.0)))
        val third = second.revise(
            mealName = "Late lunch",
            ingredients = listOf(ingredient("edited-twice", 30.0))
        )

        assertThat(first.revision).isEqualTo(0L)
        assertThat(second.revision).isEqualTo(1L)
        assertThat(third.revision).isEqualTo(2L)
        assertThat(second.original).isSameInstanceAs(original)
        assertThat(third.original).isSameInstanceAs(original)
        assertThat(original.mealName).isEqualTo("Lunch")
        assertThat(original.ingredients).containsExactly(originalIngredient)
        assertThat(third.mealName).isEqualTo("Late lunch")
    }

    @Test
    fun originalDraftAndConfirmationDefensivelyCopyInputLists() {
        val source = mutableListOf(ingredient("one", 10.0))
        val original = OriginalAiMealEstimate("estimate-1", "Meal", source)
        source += ingredient("source-mutation", 20.0)

        val draftSource = mutableListOf(ingredient("draft", 15.0))
        val draft = original.toDraft().revise(ingredients = draftSource)
        draftSource += ingredient("draft-mutation", 25.0)

        val confirmed = confirmed(draft, therapyCarbohydrateGrams = null)
        draftSource += ingredient("post-confirmation-mutation", 35.0)

        assertThat(original.ingredients.map { it.id }).containsExactly("one")
        assertThat(draft.ingredients.map { it.id }).containsExactly("draft")
        assertThat(confirmed.ingredients.map { it.id }).containsExactly("draft")
    }

    @Test
    fun confirmationCapturesExactRevisionAndExplicitZeroWithoutInferringTherapyCarbs() {
        val selection = MealAbsorptionSelection(MealAbsorptionProfile.MIXED, 120)
        val canonical = CanonicalTherapyMetadata("therapy-42", "revision-7")
        val draft = OriginalAiMealEstimate(
            "estimate-1",
            "Meal",
            listOf(ingredient("known-carbs", 25.0))
        ).toDraft().revise(ingredients = listOf(ingredient("confirmed-carbs", 30.0)))

        val explicitZero = confirmed(draft, 0.0, selection, canonical)
        val unspecified = confirmed(draft, null)

        assertThat(explicitZero.draftRevision).isEqualTo(1L)
        assertThat(explicitZero.nutrition.carbohydrates.completeTotalGrams)
            .isEqualTo(NutritionRange.exact(30.0))
        assertThat(explicitZero.explicitTherapyCarbohydrateGrams).isEqualTo(0.0)
        assertThat(explicitZero.absorptionSelection).isEqualTo(selection)
        assertThat(explicitZero.canonicalTherapyMetadata).isEqualTo(canonical)
        assertThat(unspecified.explicitTherapyCarbohydrateGrams).isNull()
    }

    @Test
    fun confirmationRejectsInvalidTherapyOrCanonicalMetadataWithoutCreatingASnapshot() {
        val draft = OriginalAiMealEstimate(
            "estimate-1",
            "Meal",
            listOf(ingredient("known-carbs", 25.0))
        ).toDraft()

        val result = MealNutritionCalculator.confirm(
            draft = draft,
            therapyCarbohydrateGrams = Double.POSITIVE_INFINITY,
            canonicalTherapyMetadata = CanonicalTherapyMetadata("therapy-42", " ")
        )

        assertThat(result).isInstanceOf(MealNutritionConfirmationResult.Invalid::class.java)
        assertThat((result as MealNutritionConfirmationResult.Invalid).violations.map { it.code })
            .containsAtLeast(
                NutritionViolationCode.THERAPY_CARBOHYDRATES_OUT_OF_RANGE,
                NutritionViolationCode.CANONICAL_THERAPY_METADATA_INVALID
            )
    }

    private fun confirmed(
        draft: MealNutritionDraft,
        therapyCarbohydrateGrams: Double?,
        absorptionSelection: MealAbsorptionSelection? = null,
        canonicalTherapyMetadata: CanonicalTherapyMetadata? = null
    ): ConfirmedMealNutritionSnapshot = when (
        val result = MealNutritionCalculator.confirm(
            draft,
            therapyCarbohydrateGrams,
            absorptionSelection,
            canonicalTherapyMetadata
        )
    ) {
        is MealNutritionConfirmationResult.Confirmed -> result.snapshot
        is MealNutritionConfirmationResult.Invalid -> error("Unexpected invalid result: ${result.violations}")
    }

    private fun ingredient(id: String, carbohydrates: Double): MealIngredient = MealIngredient(
        id = id,
        name = "Ingredient $id",
        amount = IngredientAmount(100.0, 1.0, PreparationState.COOKED),
        facts = NutrientFacts(
            reference = NutritionReference(NutrientReferenceType.PER_100G, 100.0),
            preparationState = PreparationState.COOKED,
            carbohydrateBasis = CarbohydrateBasis.TOTAL,
            carbohydrates = KnownNutrient(
                NutritionRange.exact(carbohydrates),
                NutrientSource.AI_ESTIMATE
            ),
            protein = KnownNutrient(NutritionRange.exact(5.0), NutrientSource.AI_ESTIMATE),
            fat = KnownNutrient(NutritionRange.exact(2.0), NutrientSource.AI_ESTIMATE)
        )
    )
}
