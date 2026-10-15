package io.aaps.copilot.ui.foundation.screens

import io.aaps.copilot.domain.predict.AcceptedSensitivityTupleFreshness

data class FoodDisplayScope(
    val settingsRevision: Long, val isfSource: String, val crSource: String, val calibrationIdentity: String
)

data class AcceptedFoodDisplaySnapshot(
    val scope: FoodDisplayScope, val cycle: String, val generatedAt: Long,
    val points: List<ChartPointUi>, val complete: Boolean?, val giAdjusted: Boolean
)

enum class FoodDisplayStatus { CURRENT, UPDATING, OUTDATED, UNAVAILABLE, INACTIVE }

internal fun retainAcceptedFoodDisplay(previous: AcceptedFoodDisplaySnapshot?, current: OverviewUiState): OverviewUiState {
    val candidate = current.acceptedFoodDisplay
    val prior = previous?.takeIf {
        !current.foodDisplayScopeReset && it.scope == current.foodDisplayScope &&
            current.chart.historyPoints.any { point -> point == it.points.firstOrNull() }
    }
    var display = candidate?.takeIf { prior == null || it.generatedAt >= prior.generatedAt } ?: prior
    val now = current.foodDisplayNowTs
    val positiveCob = current.currentCobGrams?.let { it > 0.0 } == true
    val zeroCob = current.currentCobGrams == 0.0
    val hasRemainingInfluence = display?.points?.let { points ->
        points.last().value > points.first().value
    } == true
    if (zeroCob && display != null && (!hasRemainingInfluence || display.points.last().ts <= now)) display = null
    val status = when {
        display == null -> if (zeroCob) FoodDisplayStatus.INACTIVE else FoodDisplayStatus.UNAVAILABLE
        !AcceptedSensitivityTupleFreshness.isFresh(display.generatedAt, now) ||
            current.currentCobGrams == null || display.points.last().ts <= now ||
            (positiveCob && !hasRemainingInfluence) -> FoodDisplayStatus.OUTDATED
        current.chart.historyPoints.maxOfOrNull { it.ts } != display.points.first().ts ||
            (current.foodCobTimestamp ?: 0L) > display.generatedAt || candidate == null -> FoodDisplayStatus.UPDATING
        else -> FoodDisplayStatus.CURRENT
    }
    return current.copy(acceptedFoodDisplay = display, foodDisplayStatus = status,
        chart = current.chart.copy(mealImpactPoints = display?.points.orEmpty(),
            mealImpactComplete = display?.complete, mealImpactGiAdjusted = display?.giAdjusted == true))
}
