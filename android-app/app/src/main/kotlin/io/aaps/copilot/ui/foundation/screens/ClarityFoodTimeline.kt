package io.aaps.copilot.ui.foundation.screens

import io.aaps.copilot.domain.events.CompensationEvent
import io.aaps.copilot.domain.events.CompensationEventType
import io.aaps.copilot.domain.events.EventSource
import java.util.Locale
import kotlin.math.abs

internal fun clarityMealEnteredGrams(event: CompensationEvent): Double? {
    val raw = listOfNotNull(event.attributes["carbs"], event.attributes["carbsGrams"])
    if (raw.isEmpty()) return null
    val values = raw.map { it.toDoubleOrNull()?.takeIf { grams -> grams.isFinite() && grams > 0.0 } ?: return null }
    return values.first().takeIf { first -> values.all { abs(it - first) < 0.000001 } }
}

internal fun clarityFoodTimelineEvents(state: OverviewUiState): List<CompensationEvent> {
    val reference = state.acceptedFoodDisplay?.generatedAt ?: state.foodDisplayNowTs
    if (reference <= 0L) return emptyList()
    // Meal records are context, never a per-meal allocation of the aggregate COB.
    return state.events.asSequence()
        .sortedWith(compareByDescending<CompensationEvent> { it.revision }.thenByDescending { it.startTs })
        .distinctBy { it.localId }
        .filter { it.startTs > 0L && it.startTs in (reference - 12 * 60 * 60_000L).coerceAtLeast(0L)..reference }
        .filter { it.type == CompensationEventType.MEAL && it.source in setOf(EventSource.USER, EventSource.AAPS) }
        .filter { it.subtype.uppercase(Locale.US) != "UAM" && it.attributes.values.none { value -> value.contains("UAM_ENGINE", true) } }
        .filter { clarityMealEnteredGrams(it) != null }
        .sortedWith(compareBy<CompensationEvent> { it.startTs }.thenBy { it.localId })
        .toList()
}
