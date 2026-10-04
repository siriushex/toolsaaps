package io.aaps.copilot.ui.foundation.screens

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.aaps.copilot.domain.predict.MealFoodDisplayProjection
import io.aaps.copilot.domain.predict.MealRollingImpact

internal data class MealImpactChartData(
    val points: List<ChartPointUi>,
    val complete: Boolean?,
    val giAdjusted: Boolean
)

internal fun mealImpactChartPoints(
    payload: String?, acceptedCycle: String?, generationTs: Long?, anchor: ChartPointUi?
): List<ChartPointUi> = mealImpactChartData(payload, acceptedCycle, generationTs, anchor)?.points.orEmpty()

internal fun mealImpactChartData(
    payload: String?, acceptedCycle: String?, generationTs: Long?, anchor: ChartPointUi?
): MealImpactChartData? {
    if (payload == null || payload.length > 16_384 || payload.toByteArray(Charsets.UTF_8).size > 16_384 ||
        acceptedCycle.isNullOrBlank() || acceptedCycle.length > 256 ||
        generationTs == null || generationTs <= 0L || anchor == null ||
        anchor.ts <= 0L || !anchor.value.isFinite() || anchor.value <= 0.0) return null
    return try {
        val json = JsonParser.parseString(payload).asJsonObject
        val cycle = json.get("cycle")?.asJsonPrimitive
        if (cycle?.isString != true || cycle.asString != acceptedCycle) return null
        val versioned = json.has("schemaVersion")
        if (versioned && json.exactLong("schemaVersion") != 2L) return null
        val values = json.getAsJsonArray("steps")
        if (if (versioned) values.size() !in 2..145 else values.size() != 13) return null
        val steps = values.map { value ->
            require(value.isJsonPrimitive && value.asJsonPrimitive.isNumber)
            value.asDouble
        }
        val complete: Boolean?
        val giAdjusted: Boolean
        val cumulative: List<Double>
        if (versioned) {
            val predictionAt = json.exactLong("predictionAtMs")
            // The accepted remaining curve must not be moved to a newer CGM clock.
            if (predictionAt <= 0L || predictionAt != anchor.ts || predictionAt > generationTs) return null
            val model = json.get("modelVersion")?.asJsonPrimitive
            if (model?.isString != true || model.asString != MealFoodDisplayProjection.MODEL_VERSION) return null
            val completion = json.get("complete")?.asJsonPrimitive
            if (completion?.isBoolean != true) return null
            complete = completion.asBoolean
            val adjustedMeals = json.exactLong("giAdjustedMeals")
            if (adjustedMeals !in 0L..5000L) return null
            giAdjusted = adjustedMeals > 0L
            cumulative = MealRollingImpact.fullDisplayTail(steps)
        } else {
            complete = null
            giAdjusted = false
            cumulative = MealRollingImpact.nextThirtyMinutes(steps)
        }
        if (cumulative.isEmpty() || anchor.ts > Long.MAX_VALUE - (cumulative.size - 1) * 300_000L) return null
        val points = cumulative.mapIndexed { index, value ->
            ChartPointUi(anchor.ts + index * 300_000L, anchor.value + value)
        }
        if (points.any { !it.value.isFinite() }) null else MealImpactChartData(points, complete, giAdjusted)
    } catch (_: Exception) {
        null
    }
}

private fun JsonObject.exactLong(key: String): Long {
    val value = get(key).asJsonPrimitive
    require(value.isNumber)
    return value.asBigDecimal.longValueExact()
}
