package io.aaps.copilot.ui.foundation.screens

import io.aaps.copilot.domain.events.CompensationEventStatus
import io.aaps.copilot.domain.events.CompensationEventType
import io.aaps.copilot.domain.events.EventSource
import io.aaps.copilot.domain.target.EatingSoonPolicy

internal enum class MealPreparationStatus { IDLE, WAITING_FOR_IOB, IOB_PRESENT, CHECK_DATA, CHECK_GLUCOSE }

internal data class MealPreparationPresentation(
    val status: MealPreparationStatus = MealPreparationStatus.IDLE,
    val episodeKey: String = "",
    val plannedMealAtMs: Long? = null,
    val minutesUntilPlan: Int? = null
)

/** Presentation only. IOB neither proves a food bolus nor authorizes waiting to eat. */
internal fun mealPreparationPresentation(state: OverviewUiState): MealPreparationPresentation {
    val now = state.foodDisplayNowTs
    if (now <= 0L) return MealPreparationPresentation()
    val upcoming = (if (state.events.size <= 5000) state.events else emptyList()).groupBy { it.localId }.values.mapNotNull { versions ->
        val revision = versions.maxOf { it.revision }
        versions.filter { it.revision == revision }.distinct().singleOrNull()
    }.filter {
        it.type == CompensationEventType.MEAL && it.source == EventSource.AAPS &&
            it.status == CompensationEventStatus.ACTIVE &&
            !it.displayMealIdentity.isNullOrBlank() && !it.displayMealRevision.isNullOrBlank() &&
            it.displayCarbsGrams?.let { grams -> grams.isFinite() && grams > 0.0 } == true &&
            it.startTs > now && it.startTs - now <= 60 * 60_000L
    }.groupBy { it.displayMealIdentity }.values.mapNotNull { it.distinct().singleOrNull() }
    val plan = upcoming.singleOrNull()
    val intent = state.eatingSoon.takeIf {
        it.status in setOf(EatingSoonUiStatus.QUEUED, EatingSoonUiStatus.AWAITING_AAPS, EatingSoonUiStatus.CONFIRMED) &&
            !it.requestKey.isNullOrBlank() &&
            it.timestamp?.let { ts -> ts > 0L && ts <= now && now - ts < EatingSoonPolicy.DURATION_MINUTES * 60_000L } == true
    }
    if (intent == null && plan == null) return MealPreparationPresentation()
    val sample = state.iobDetails.sampleTimestamp
    val freshIob = sample != null && sample > 0L && sample <= now && now - sample <= 5 * 60_000L
    val freshGlucose = !state.isStale && state.sampleAgeMinutes?.let { it in 0L..5L } == true &&
        state.glucose?.let { it.isFinite() && it > 0.0 } == true
    val status = when {
        !freshGlucose || state.killSwitchEnabled -> MealPreparationStatus.CHECK_DATA
        state.glucose!! <= 4.0 || state.glucoseAlertStrongActive -> MealPreparationStatus.CHECK_GLUCOSE
        !freshIob || state.currentIobUnits?.isFinite() != true || state.currentIobUnits < 0.0 -> MealPreparationStatus.CHECK_DATA
        state.currentIobUnits > 0.0 -> MealPreparationStatus.IOB_PRESENT
        else -> MealPreparationStatus.WAITING_FOR_IOB
    }
    // A conflicting/missing plan never becomes a calculated meal-start time.
    return MealPreparationPresentation(status, intent?.requestKey ?: "meal:${plan!!.displayMealIdentity}:${plan.displayMealRevision}",
        plan?.startTs, plan?.let { ((it.startTs - now + 59_999L) / 60_000L).toInt() })
}
