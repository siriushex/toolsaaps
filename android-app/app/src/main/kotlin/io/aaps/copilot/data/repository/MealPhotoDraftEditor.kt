package io.aaps.copilot.data.repository

import io.aaps.copilot.domain.nutrition.IngredientAmount
import io.aaps.copilot.domain.nutrition.MealIngredient
import io.aaps.copilot.domain.nutrition.MealNutritionDraft
import io.aaps.copilot.domain.nutrition.MealNutritionCalculation
import io.aaps.copilot.domain.nutrition.MealNutritionCalculationResult
import io.aaps.copilot.domain.nutrition.MealNutritionCalculator
import io.aaps.copilot.domain.nutrition.NutritionRange
import io.aaps.copilot.domain.nutrition.OriginalAiMealEstimate
import io.aaps.copilot.domain.nutrition.PreparationState
import io.aaps.copilot.domain.profile.MealAbsorptionProfile
import io.aaps.copilot.domain.profile.MealAbsorptionSelection

data class MealPhotoDraftEditorState(
    val estimate: MealPhotoNutritionEstimate,
    val confirmedMassesGrams: Map<String, Double?>,
    val eatenFractions: Map<String, Double>,
    val preparationStates: Map<String, PreparationState>,
    val profile: MealAbsorptionProfile?,
    val durationMinutes: Int?,
    val revision: Long = 0L,
    val invalidMassIds: Set<String> = emptySet()
)

data class MealPhotoNutritionPreview(
    val hasEstimatedMass: Boolean,
    val energyKilocalories: NutritionRange?,
    val proteinGrams: NutritionRange?,
    val fatGrams: NutritionRange?,
    val carbohydrateGrams: NutritionRange?
)

sealed interface MealPhotoDraftEditResult {
    data class Updated(val state: MealPhotoDraftEditorState) : MealPhotoDraftEditResult

    data class Rejected(val reason: Reason) : MealPhotoDraftEditResult {
        enum class Reason {
            UNKNOWN_INGREDIENT,
            INVALID_MASS,
            INVALID_EATEN_FRACTION,
            INVALID_DURATION
        }
    }
}

sealed interface MealPhotoDraftBuildResult {
    data class Ready(
        val draft: MealNutritionDraft,
        val absorptionSelection: MealAbsorptionSelection?
    ) : MealPhotoDraftBuildResult

    data object MissingMass : MealPhotoDraftBuildResult
}

/** Pure editor boundary between an AI estimate and an explicitly confirmed draft. */
object MealPhotoDraftEditor {
    fun initial(estimate: MealPhotoNutritionEstimate): MealPhotoDraftEditorState {
        val ids = estimate.ingredients.map { it.id }
        return MealPhotoDraftEditorState(
            estimate = estimate,
            confirmedMassesGrams = estimate.ingredients.associate { ingredient ->
                ingredient.id to ingredient.massGrams.singleValueOrNull()?.takeIf(ingredient::acceptsConfirmedMass)
            },
            eatenFractions = ids.associateWith { 1.0 },
            preparationStates = estimate.ingredients.associate { it.id to it.preparationState },
            profile = estimate.suggestedProfile,
            durationMinutes = estimate.suggestedDurationMinutes
        )
    }

    fun setMass(
        state: MealPhotoDraftEditorState,
        ingredientId: String,
        grams: Double
    ): MealPhotoDraftEditResult {
        val ingredient = state.estimate.ingredients.firstOrNull { it.id == ingredientId }
            ?: return MealPhotoDraftEditResult.Rejected(
                MealPhotoDraftEditResult.Rejected.Reason.UNKNOWN_INGREDIENT
            )
        if (!ingredient.acceptsConfirmedMass(grams)) {
            return MealPhotoDraftEditResult.Rejected(
                MealPhotoDraftEditResult.Rejected.Reason.INVALID_MASS
            )
        }
        return MealPhotoDraftEditResult.Updated(
            state.copy(
                confirmedMassesGrams = state.confirmedMassesGrams + (ingredientId to grams),
                invalidMassIds = state.invalidMassIds - ingredientId,
                revision = nextRevision(state.revision)
            )
        )
    }

    fun setEatenFraction(
        state: MealPhotoDraftEditorState,
        ingredientId: String,
        fraction: Double
    ): MealPhotoDraftEditResult {
        if (ingredientId !in state.confirmedMassesGrams) {
            return MealPhotoDraftEditResult.Rejected(
                MealPhotoDraftEditResult.Rejected.Reason.UNKNOWN_INGREDIENT
            )
        }
        if (!fraction.isFinite() || fraction !in 0.0..1.0) {
            return MealPhotoDraftEditResult.Rejected(
                MealPhotoDraftEditResult.Rejected.Reason.INVALID_EATEN_FRACTION
            )
        }
        return MealPhotoDraftEditResult.Updated(
            state.copy(
                eatenFractions = state.eatenFractions + (ingredientId to fraction),
                revision = nextRevision(state.revision)
            )
        )
    }

    fun setPreparationState(
        state: MealPhotoDraftEditorState,
        ingredientId: String,
        preparationState: PreparationState
    ): MealPhotoDraftEditResult {
        if (ingredientId !in state.confirmedMassesGrams) {
            return MealPhotoDraftEditResult.Rejected(
                MealPhotoDraftEditResult.Rejected.Reason.UNKNOWN_INGREDIENT
            )
        }
        return MealPhotoDraftEditResult.Updated(
            state.copy(
                preparationStates = state.preparationStates + (ingredientId to preparationState),
                revision = nextRevision(state.revision)
            )
        )
    }

    fun setProfile(
        state: MealPhotoDraftEditorState,
        profile: MealAbsorptionProfile?,
        durationMinutes: Int?
    ): MealPhotoDraftEditResult {
        if (!validDuration(profile, durationMinutes)) {
            return MealPhotoDraftEditResult.Rejected(
                MealPhotoDraftEditResult.Rejected.Reason.INVALID_DURATION
            )
        }
        return MealPhotoDraftEditResult.Updated(
            state.copy(
                profile = profile,
                durationMinutes = durationMinutes,
                revision = nextRevision(state.revision)
            )
        )
    }

    fun build(state: MealPhotoDraftEditorState): MealPhotoDraftBuildResult {
        if (state.invalidMassIds.isNotEmpty()) return MealPhotoDraftBuildResult.MissingMass
        val masses = state.confirmedMassesGrams.mapValues { (_, value) -> value ?: return MealPhotoDraftBuildResult.MissingMass }
        val original = state.estimate.toOriginalEstimate(masses)
            ?: return MealPhotoDraftBuildResult.MissingMass
        val editedIngredients = original.ingredients.map { ingredient ->
            val preparation = state.preparationStates[ingredient.id] ?: ingredient.amount.preparationState
            ingredient.copy(
                amount = IngredientAmount(
                    grams = ingredient.amount.grams,
                    eatenFraction = state.eatenFractions[ingredient.id] ?: 1.0,
                    preparationState = preparation
                ),
                facts = ingredient.facts.copy(preparationState = preparation)
            )
        }
        val draft = original.toDraft().revise(ingredients = editedIngredients)
        return MealPhotoDraftBuildResult.Ready(
            draft = draft,
            absorptionSelection = state.profile?.let { MealAbsorptionSelection(it, state.durationMinutes) }
        )
    }

    /** Display-only envelope; neither endpoint becomes a confirmed mass or food entry. */
    fun preview(state: MealPhotoDraftEditorState): MealPhotoNutritionPreview? {
        if (state.invalidMassIds.isNotEmpty()) return null
        fun endpoint(lower: Boolean): MealNutritionCalculation? {
            val ingredients = state.estimate.ingredients.map { estimate ->
                val confirmed = state.confirmedMassesGrams[estimate.id]
                val mass = confirmed ?: estimate.massGrams.maximum
                val ingredient = estimate.toMealIngredient(mass) ?: return null
                val fraction = state.eatenFractions[estimate.id] ?: return null
                if (!fraction.isFinite() || fraction !in 0.0..1.0) return null
                // Scaling the upper endpoint supports a genuine zero lower bound.
                val scale = if (lower && confirmed == null) estimate.massGrams.minimum / mass else 1.0
                val preparation = state.preparationStates[estimate.id] ?: estimate.preparationState
                ingredient.copy(amount = IngredientAmount(mass, fraction * scale, preparation),
                    facts = ingredient.facts.copy(preparationState = preparation))
            }
            return (MealNutritionCalculator.calculate(ingredients) as? MealNutritionCalculationResult.Calculated)?.value
        }
        val lower = endpoint(true) ?: return null
        val upper = endpoint(false) ?: return null
        fun envelope(low: NutritionRange?, high: NutritionRange?): NutritionRange? =
            if (low == null || high == null) null else NutritionRange(low.minimum, high.maximum)
        return MealPhotoNutritionPreview(
            hasEstimatedMass = state.estimate.ingredients.any { state.confirmedMassesGrams[it.id] == null },
            energyKilocalories = envelope(lower.declaredEnergy.completeTotalKilocalories, upper.declaredEnergy.completeTotalKilocalories),
            proteinGrams = envelope(lower.protein.completeTotalGrams, upper.protein.completeTotalGrams),
            fatGrams = envelope(lower.fat.completeTotalGrams, upper.fat.completeTotalGrams),
            carbohydrateGrams = envelope(lower.carbohydrates.completeTotalGrams, upper.carbohydrates.completeTotalGrams)
        )
    }

    private fun validDuration(profile: MealAbsorptionProfile?, durationMinutes: Int?): Boolean {
        if (profile == null) return durationMinutes == null
        val duration = durationMinutes ?: return false
        return duration in when (profile) {
            MealAbsorptionProfile.FAST -> 30..60
            MealAbsorptionProfile.MIXED -> 60..180
            MealAbsorptionProfile.FAT_PROTEIN -> 180..360
        }
    }

    private fun nextRevision(revision: Long): Long {
        check(revision < Long.MAX_VALUE) { "Meal photo draft revision exhausted" }
        return revision + 1L
    }
}

private fun io.aaps.copilot.domain.nutrition.NutritionRange.singleValueOrNull(): Double? =
    minimum.takeIf { it == maximum }
