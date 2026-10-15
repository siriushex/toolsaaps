package io.aaps.copilot.domain.predict

import java.util.Collections

/** Existing modeled delivery only; excludes future controller actions and uncertainty. */
internal class MealInsulinProjection(
    val predictionAtMs: Long,
    val isfMmolPerUnit: Double,
    val csfMmolPerGram: Double,
    stepEffectsMmol: List<Double>,
    val remainingModeledUnitsAtHorizon: Double,
    val completeByHorizon: Boolean,
    val modeledEventCount: Int,
    val inferredEventCount: Int
) {
    val stepEffectsMmol: List<Double> = Collections.unmodifiableList(stepEffectsMmol.toList())
}
