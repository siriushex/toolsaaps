package io.aaps.copilot.domain.meal

import io.aaps.copilot.domain.predict.MealInsulinProjection
import java.util.Collections

internal data class MealComponentPoint(
    val offsetMinutes: Int,
    val foodStepMmol: Double,
    val insulinStepMmol: Double,
    val cumulativeModeledEffectMmol: Double
)

/** One hypothetical meal plus existing modeled insulin, NOT an absolute glucose forecast. */
internal class MealComponentProjection private constructor(
    points: List<MealComponentPoint>,
    val modeledTailsComplete: Boolean
) {
    val points: List<MealComponentPoint> = Collections.unmodifiableList(points.toList())

    companion object {
        fun combine(food: MealAbsorptionProjection, insulin: MealInsulinProjection): MealComponentProjection =
            combine(food.predictionAtMs, food.points, food.completeByHorizon, insulin)

        fun combine(food: MealScenarioFoodProjection, insulin: MealInsulinProjection): MealComponentProjection =
            combine(food.predictionAtMs, food.points, food.completeByHorizon, insulin)

        private fun combine(
            predictionAtMs: Long,
            points: List<MealAbsorptionPoint>,
            foodComplete: Boolean,
            insulin: MealInsulinProjection
        ): MealComponentProjection {
            require(predictionAtMs == insulin.predictionAtMs)
            require(points.size == insulin.stepEffectsMmol.size)
            require(insulin.csfMmolPerGram.isFinite() && insulin.csfMmolPerGram > 0.0)
            require(insulin.stepEffectsMmol.all { it.isFinite() })
            var cumulative = 0.0
            val combined = points.mapIndexed { index, point ->
                val foodStep = point.absorbedStepGrams * insulin.csfMmolPerGram
                val insulinStep = insulin.stepEffectsMmol[index]
                cumulative += foodStep + insulinStep
                require(foodStep.isFinite() && cumulative.isFinite())
                MealComponentPoint(point.offsetMinutes, foodStep, insulinStep, cumulative)
            }
            return MealComponentProjection(combined, foodComplete && insulin.completeByHorizon)
        }
    }
}
