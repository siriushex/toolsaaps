package io.aaps.copilot.domain.meal

import java.util.Collections

internal enum class MealFoodOrigin { ANNOUNCED, UAM }

/** Canonical linkage must come from reconciliation, never timestamp proximity. */
internal data class MealFoodComponent(
    val componentId: String,
    val canonicalMealId: String?,
    val origin: MealFoodOrigin,
    val projection: MealAbsorptionProjection
)

/** Counterfactual food component only. Source therapy and UAM records are untouched. */
internal class MealScenarioFoodProjection private constructor(
    val predictionAtMs: Long,
    val alreadyAbsorbedGrams: Double,
    points: List<MealAbsorptionPoint>,
    val completeByHorizon: Boolean,
    replacedComponentIds: List<String>,
    retainedComponentIds: List<String>
) {
    val points: List<MealAbsorptionPoint> = Collections.unmodifiableList(points.toList())
    val replacedComponentIds: List<String> = Collections.unmodifiableList(replacedComponentIds.toList())
    val retainedComponentIds: List<String> = Collections.unmodifiableList(retainedComponentIds.toList())

    companion object {
        fun replace(
            baseline: List<MealFoodComponent>,
            canonicalMealId: String,
            replacement: MealAbsorptionProjection
        ): MealScenarioFoodProjection {
            require(canonicalMealId.isNotBlank())
            require(baseline.size <= 5_000 &&
                (baseline.size.toLong() + 1) * replacement.points.size <= 1_000_000L) {
                "Meal food projection budget exceeded"
            }
            require(baseline.all { it.componentId.isNotBlank() && !it.canonicalMealId.isNullOrBlank() }) {
                "Unresolved food component linkage"
            }
            require(baseline.map { it.componentId }.distinct().size == baseline.size)
            require(baseline.all { it.projection.predictionAtMs == replacement.predictionAtMs &&
                it.projection.points.size == replacement.points.size })
            val (replaced, retained) = baseline.sortedBy { it.componentId }
                .partition { it.canonicalMealId == canonicalMealId }
            // Other meals must already be reconciled: summing an announcement
            // and its inferred UAM would duplicate carbohydrate influence.
            require(retained.map { it.canonicalMealId }.distinct().size == retained.size) {
                "Overlapping retained food components"
            }
            val projections = retained.map { it.projection } + replacement
            val alreadyAbsorbed = projections.sumOf { it.alreadyAbsorbedGrams }
            require(alreadyAbsorbed.isFinite())
            val points = replacement.points.indices.map { index ->
                val step = projections.sumOf { it.points[index].absorbedStepGrams }
                val cumulative = projections.sumOf { it.points[index].cumulativeFutureGrams }
                val remaining = projections.sumOf { it.points[index].remainingGrams }
                require(step.isFinite() && cumulative.isFinite() && remaining.isFinite())
                MealAbsorptionPoint(index * 5, step, cumulative, remaining)
            }
            return MealScenarioFoodProjection(replacement.predictionAtMs, alreadyAbsorbed, points,
                projections.all { it.completeByHorizon }, replaced.map { it.componentId },
                retained.map { it.componentId })
        }
    }
}
