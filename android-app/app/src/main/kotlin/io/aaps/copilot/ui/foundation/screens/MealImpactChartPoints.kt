package io.aaps.copilot.ui.foundation.screens

import com.google.gson.JsonParser
import io.aaps.copilot.domain.predict.MealRollingImpact

internal fun mealImpactChartPoints(
    payload: String?, acceptedCycle: String?, generationTs: Long?, anchor: ChartPointUi?
): List<ChartPointUi> {
    if (payload == null || payload.length > 2048 || acceptedCycle.isNullOrBlank() ||
        generationTs == null || generationTs <= 0L || anchor == null ||
        anchor.ts <= 0L || !anchor.value.isFinite() || anchor.value <= 0.0) return emptyList()
    return runCatching {
        val json = JsonParser.parseString(payload).asJsonObject
        if (json.get("cycle")?.asString != acceptedCycle) return emptyList()
        val values = json.getAsJsonArray("steps")
        if (values.size() != 13) return emptyList()
        MealRollingImpact.nextThirtyMinutes(values.map { it.asDouble }).mapIndexed { index, value ->
            ChartPointUi(ts = anchor.ts + index * 300_000L, value = anchor.value + value)
        }.takeIf { points -> points.all { it.value.isFinite() } } ?: emptyList()
    }.getOrDefault(emptyList())
}
