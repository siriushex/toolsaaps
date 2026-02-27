package io.aaps.copilot.domain.predict

import io.aaps.copilot.domain.model.Forecast
import io.aaps.copilot.domain.model.GlucosePoint
import io.aaps.copilot.domain.model.TherapyEvent
import java.util.Locale
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sqrt

class HybridPredictionEngine : PredictionEngine {

    override suspend fun predict(glucose: List<GlucosePoint>, therapyEvents: List<TherapyEvent>): List<Forecast> {
        if (glucose.isEmpty()) return emptyList()
        val sortedGlucose = glucose.sortedBy { it.ts }
        val nowPoint = sortedGlucose.last()
        val nowTs = nowPoint.ts
        val nowGlucose = nowPoint.valueMmol.coerceIn(MIN_GLUCOSE_MMOL, MAX_GLUCOSE_MMOL)

        val trend = estimateTrend(sortedGlucose)
        val factors = estimateSensitivityFactors(sortedGlucose, therapyEvents)
        val volatility = estimateVolatility(sortedGlucose)
        val intervalPenalty = estimateIntervalPenalty(sortedGlucose)

        return HORIZONS_MINUTES.map { horizon ->
            val trendDelta = trendDeltaAtHorizon(trend, horizon)
            val therapyDelta = therapyDeltaAtHorizon(
                events = therapyEvents,
                nowTs = nowTs,
                horizonMinutes = horizon,
                factors = factors
            )
            val predicted = (nowGlucose + trendDelta + therapyDelta).coerceIn(MIN_GLUCOSE_MMOL, MAX_GLUCOSE_MMOL)
            val ciHalfWidth = ciHalfWidth(
                horizonMinutes = horizon,
                volatility = volatility,
                intervalPenalty = intervalPenalty
            )

            Forecast(
                ts = nowTs + horizon * MINUTE_MS,
                horizonMinutes = horizon.toInt(),
                valueMmol = roundToStep(predicted, 0.01),
                ciLow = roundToStep((predicted - ciHalfWidth).coerceAtLeast(MIN_GLUCOSE_MMOL), 0.01),
                ciHigh = roundToStep((predicted + ciHalfWidth).coerceAtMost(MAX_GLUCOSE_MMOL), 0.01),
                modelVersion = MODEL_VERSION
            )
        }
    }

    private fun estimateTrend(points: List<GlucosePoint>): TrendEstimate {
        val shortWindow = points.takeLast(10)
        val longWindow = points.takeLast(24)
        val shortSlope = weightedSlopePer5m(shortWindow, halfLifeMinutes = 14.0)
        val longSlope = weightedSlopePer5m(longWindow, halfLifeMinutes = 40.0)
        val acceleration = (shortSlope - longSlope).coerceIn(-0.35, 0.35)
        return TrendEstimate(shortSlope, longSlope, acceleration)
    }

    private fun weightedSlopePer5m(points: List<GlucosePoint>, halfLifeMinutes: Double): Double {
        if (points.size < 2) return 0.0
        val lastTs = points.last().ts
        var weightedSum = 0.0
        var weightTotal = 0.0

        points.zipWithNext().forEach { (a, b) ->
            val dtMinutes = (b.ts - a.ts) / 60_000.0
            if (dtMinutes !in 2.0..15.0) return@forEach
            val slopePer5 = (b.valueMmol - a.valueMmol) / (dtMinutes / 5.0)
            val ageMinutes = ((lastTs - b.ts).coerceAtLeast(0L)) / 60_000.0
            val weight = exp(-ln(2.0) * ageMinutes / halfLifeMinutes)
            weightedSum += slopePer5 * weight
            weightTotal += weight
        }

        if (weightTotal <= 1e-6) return 0.0
        return (weightedSum / weightTotal).coerceIn(-1.2, 1.2)
    }

    private fun trendDeltaAtHorizon(trend: TrendEstimate, horizonMinutes: Long): Double {
        val steps = horizonMinutes / 5.0
        val blendedSlope = 0.65 * trend.shortSlopePer5m + 0.35 * trend.longSlopePer5m
        val accelerationPart = 0.5 * trend.accelerationPer5m * steps.pow(2.0) * 0.35
        val raw = blendedSlope * steps + accelerationPart
        val maxAbs = 0.55 * steps + 0.7
        return raw.coerceIn(-maxAbs, maxAbs)
    }

    private fun therapyDeltaAtHorizon(
        events: List<TherapyEvent>,
        nowTs: Long,
        horizonMinutes: Long,
        factors: SensitivityFactors
    ): Double {
        if (events.isEmpty()) return 0.0
        val horizon = horizonMinutes.toDouble()
        var delta = 0.0

        events.asSequence()
            .filter { it.ts <= nowTs + horizonMinutes * MINUTE_MS }
            .filter { nowTs - it.ts <= EVENT_LOOKBACK_MS }
            .forEach { event ->
                val ageStart = ((nowTs - event.ts).coerceAtLeast(0L)) / 60_000.0
                val ageEnd = ageStart + horizon

                val carbs = extractCarbsGrams(event)
                if (carbs != null && carbs > 0.0) {
                    val absorbed = (carbCumulative(ageEnd) - carbCumulative(ageStart)).coerceAtLeast(0.0)
                    delta += carbs * factors.carbSensitivityMmolPerGram * absorbed
                }

                val insulinUnits = extractInsulinUnits(event)
                if (insulinUnits != null && insulinUnits > 0.0 && eventCanCarryInsulin(event)) {
                    val active = (insulinCumulative(ageEnd) - insulinCumulative(ageStart)).coerceAtLeast(0.0)
                    delta -= insulinUnits * factors.isfMmolPerUnit * active
                }
            }

        return delta.coerceIn(-6.0, 6.0)
    }

    private fun estimateSensitivityFactors(
        glucose: List<GlucosePoint>,
        events: List<TherapyEvent>
    ): SensitivityFactors {
        val sortedGlucose = glucose.sortedBy { it.ts }
        val sortedEvents = events.sortedBy { it.ts }

        val crSamples = sortedEvents.mapNotNull { event ->
            val grams = extractCarbsGrams(event) ?: return@mapNotNull null
            val units = extractInsulinUnits(event) ?: return@mapNotNull null
            if (grams <= 0.0 || units <= 0.0) return@mapNotNull null
            (grams / units).takeIf { it in 2.0..60.0 }
        }

        val correctionSamples = sortedEvents.mapNotNull { event ->
            if (!eventIsCorrection(event)) return@mapNotNull null
            val units = extractInsulinUnits(event) ?: return@mapNotNull null
            if (units <= 0.0) return@mapNotNull null
            val before = sortedGlucose.closestTo(event.ts - 10 * MINUTE_MS, maxDistanceMs = 25 * MINUTE_MS)
                ?: return@mapNotNull null
            val after = sortedGlucose.closestTo(event.ts + 90 * MINUTE_MS, maxDistanceMs = 45 * MINUTE_MS)
                ?: return@mapNotNull null
            val drop = before.valueMmol - after.valueMmol
            if (drop <= 0.1) return@mapNotNull null
            (drop / units).takeIf { it in 0.2..18.0 }
        }

        val isf = median(correctionSamples).coerceIn(0.8, 8.0).takeIf { correctionSamples.isNotEmpty() }
            ?: DEFAULT_ISF_MMOL_PER_UNIT
        val cr = median(crSamples).coerceIn(4.0, 30.0).takeIf { crSamples.isNotEmpty() }
            ?: DEFAULT_CR_GRAM_PER_UNIT
        val csf = (isf / cr).coerceIn(0.05, 0.40)
        return SensitivityFactors(isfMmolPerUnit = isf, carbSensitivityMmolPerGram = csf)
    }

    private fun eventIsCorrection(event: TherapyEvent): Boolean {
        val type = normalize(event.type)
        if (type == "correction_bolus") return true
        if (type != "bolus") return false
        val reason = event.payload["reason"]?.lowercase(Locale.US).orEmpty()
        val flag = event.payload["isCorrection"]?.lowercase(Locale.US).orEmpty()
        return reason.contains("correction") || flag == "true"
    }

    private fun eventCanCarryInsulin(event: TherapyEvent): Boolean {
        val type = normalize(event.type)
        return type.contains("bolus") || type.contains("correction") || type == "insulin"
    }

    private fun extractCarbsGrams(event: TherapyEvent): Double? {
        return payloadDouble(event, "grams", "carbs", "enteredCarbs", "mealCarbs")
            ?.takeIf { it in 0.5..400.0 }
    }

    private fun extractInsulinUnits(event: TherapyEvent): Double? {
        return payloadDouble(event, "units", "bolusUnits", "insulin", "enteredInsulin")
            ?.takeIf { it in 0.02..30.0 }
    }

    private fun payloadDouble(event: TherapyEvent, vararg keys: String): Double? {
        val normalizedPayload = event.payload.entries.associate { normalize(it.key) to it.value }
        return keys.firstNotNullOfOrNull { key ->
            normalizedPayload[normalize(key)]?.replace(",", ".")?.toDoubleOrNull()
        }
    }

    private fun carbCumulative(ageMinutes: Double): Double {
        return when {
            ageMinutes <= 0.0 -> 0.0
            ageMinutes < 15.0 -> 0.04 * (ageMinutes / 15.0)
            ageMinutes < 45.0 -> 0.04 + 0.26 * ((ageMinutes - 15.0) / 30.0)
            ageMinutes < 90.0 -> 0.30 + 0.35 * ((ageMinutes - 45.0) / 45.0)
            ageMinutes < 150.0 -> 0.65 + 0.25 * ((ageMinutes - 90.0) / 60.0)
            ageMinutes < 240.0 -> 0.90 + 0.10 * ((ageMinutes - 150.0) / 90.0)
            else -> 1.0
        }
    }

    private fun insulinCumulative(ageMinutes: Double): Double {
        return when {
            ageMinutes <= 10.0 -> 0.0
            ageMinutes < 45.0 -> 0.10 * ((ageMinutes - 10.0) / 35.0)
            ageMinutes < 90.0 -> 0.10 + 0.35 * ((ageMinutes - 45.0) / 45.0)
            ageMinutes < 180.0 -> 0.45 + 0.40 * ((ageMinutes - 90.0) / 90.0)
            ageMinutes < 300.0 -> 0.85 + 0.15 * ((ageMinutes - 180.0) / 120.0)
            else -> 1.0
        }
    }

    private fun estimateVolatility(points: List<GlucosePoint>): Double {
        val deltasPer5m = points
            .takeLast(16)
            .zipWithNext()
            .mapNotNull { (a, b) ->
                val dtMinutes = (b.ts - a.ts) / 60_000.0
                if (dtMinutes !in 2.0..15.0) return@mapNotNull null
                (b.valueMmol - a.valueMmol) / (dtMinutes / 5.0)
            }
        if (deltasPer5m.size < 2) return 0.0
        return stddev(deltasPer5m).coerceIn(0.0, 1.5)
    }

    private fun estimateIntervalPenalty(points: List<GlucosePoint>): Double {
        val intervals = points.takeLast(16).zipWithNext().map { (a, b) ->
            ((b.ts - a.ts).coerceAtLeast(0L)) / 60_000.0
        }.filter { it > 0.0 }
        if (intervals.isEmpty()) return 0.2
        val median = median(intervals)
        return when {
            median > 9.0 -> 0.30
            median > 7.0 -> 0.16
            median > 6.0 -> 0.08
            else -> 0.0
        }
    }

    private fun ciHalfWidth(horizonMinutes: Long, volatility: Double, intervalPenalty: Double): Double {
        val base = when (horizonMinutes) {
            5L -> 0.35
            30L -> 0.90
            60L -> 1.25
            else -> 1.0
        }
        val volatilityGain = when (horizonMinutes) {
            5L -> 0.45
            30L -> 0.80
            60L -> 1.15
            else -> 0.75
        }
        return (base + volatility * volatilityGain + intervalPenalty).coerceIn(0.30, 3.2)
    }

    private fun stddev(values: List<Double>): Double {
        if (values.size < 2) return 0.0
        val mean = values.average()
        val variance = values.sumOf { (it - mean).pow(2.0) } / values.size
        return sqrt(variance)
    }

    private fun median(values: List<Double>): Double {
        if (values.isEmpty()) return 0.0
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 0) {
            (sorted[mid - 1] + sorted[mid]) / 2.0
        } else {
            sorted[mid]
        }
    }

    private fun List<GlucosePoint>.closestTo(targetTs: Long, maxDistanceMs: Long): GlucosePoint? {
        return this.minByOrNull { point ->
            kotlin.math.abs(point.ts - targetTs)
        }?.takeIf { kotlin.math.abs(it.ts - targetTs) <= maxDistanceMs }
    }

    private fun roundToStep(value: Double, step: Double): Double {
        if (step <= 0.0) return value
        val scaled = value / step
        return floor(scaled + 0.5) * step
    }

    private fun normalize(value: String): String {
        return value
            .replace(Regex("([a-z0-9])([A-Z])"), "$1_$2")
            .lowercase(Locale.US)
            .replace(Regex("[^a-z0-9]+"), "_")
            .trim('_')
    }

    private data class TrendEstimate(
        val shortSlopePer5m: Double,
        val longSlopePer5m: Double,
        val accelerationPer5m: Double
    )

    private data class SensitivityFactors(
        val isfMmolPerUnit: Double,
        val carbSensitivityMmolPerGram: Double
    )

    private companion object {
        const val MODEL_VERSION = "local-hybrid-v2"
        const val MIN_GLUCOSE_MMOL = 2.2
        const val MAX_GLUCOSE_MMOL = 22.0
        const val DEFAULT_ISF_MMOL_PER_UNIT = 2.3
        const val DEFAULT_CR_GRAM_PER_UNIT = 10.0
        const val MINUTE_MS = 60_000L
        const val EVENT_LOOKBACK_MS = 8 * 60 * MINUTE_MS
        val HORIZONS_MINUTES = listOf(5L, 30L, 60L)
    }
}
