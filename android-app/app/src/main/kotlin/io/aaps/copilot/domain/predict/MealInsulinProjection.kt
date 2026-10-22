package io.aaps.copilot.domain.predict

import java.util.Collections

internal enum class MealInsulinInventoryIssue {
    UNSUPPORTED_EVENT_TYPE, NON_INSTANT_OR_AMBIGUOUS_DELIVERY,
    INVALID_RECORD, DUPLICATE_OR_EMPTY_IDENTITY
}

/** Existing modeled delivery only; excludes future controller actions and uncertainty. */
internal class MealInsulinProjection(
    val predictionAtMs: Long,
    val isfMmolPerUnit: Double,
    val csfMmolPerGram: Double,
    stepEffectsMmol: List<Double>,
    val remainingModeledUnitsAtHorizon: Double,
    val completeByHorizon: Boolean,
    val modeledEventCount: Int,
    val inferredEventCount: Int,
    val activeModeledUnits: Double,
    val activeInferredUnits: Double,
    val unsupportedEventCount: Int,
    unsupportedReasons: Set<MealInsulinInventoryIssue>
) {
    val stepEffectsMmol: List<Double> = Collections.unmodifiableList(stepEffectsMmol.toList())
    val unsupportedReasons: Set<MealInsulinInventoryIssue> = Collections.unmodifiableSet(unsupportedReasons.toSet())

    internal fun requireUsableInventory() {
        require(unsupportedEventCount == 0 && inferredEventCount == 0) {
            "Meal research insulin inventory is unsupported"
        }
    }
}
