package io.aaps.copilot.domain.predict

import io.aaps.copilot.domain.model.Forecast
import io.aaps.copilot.domain.model.GlucosePoint
import io.aaps.copilot.domain.model.TherapyEvent
import kotlin.math.max

class HybridPredictionEngine : PredictionEngine {

    override suspend fun predict(glucose: List<GlucosePoint>, therapyEvents: List<TherapyEvent>): List<Forecast> {
        if (glucose.isEmpty()) return emptyList()
        val sorted = glucose.sortedBy { it.ts }
        val now = sorted.last()
        val slopePerFiveMinutes = estimateSlopePerFiveMinutes(sorted)
        val trendAdjusted = now.valueMmol + slopePerFiveMinutes
        val forecast5m = Forecast(
            ts = now.ts + FIVE_MINUTES,
            horizonMinutes = 5,
            valueMmol = trendAdjusted,
            ciLow = max(2.2, trendAdjusted - 0.4),
            ciHigh = trendAdjusted + 0.4,
            modelVersion = "local-hf-v1"
        )

        val hourBaseline = forecastOneHour(sorted)
        val cobInfluence = therapyEvents
            .filter { it.type.equals("carbs", ignoreCase = true) }
            .takeLast(3)
            .sumOf { it.payload["grams"]?.toDoubleOrNull() ?: 0.0 } / 120.0

        val halfHourBase = now.valueMmol + (hourBaseline - now.valueMmol) * 0.55
        val forecast30m = Forecast(
            ts = now.ts + THIRTY_MINUTES,
            horizonMinutes = 30,
            valueMmol = halfHourBase + cobInfluence * 0.5,
            ciLow = max(2.2, halfHourBase + cobInfluence * 0.5 - 0.8),
            ciHigh = halfHourBase + cobInfluence * 0.5 + 0.8,
            modelVersion = "local-ensemble-30m-v1"
        )

        val forecast60m = Forecast(
            ts = now.ts + ONE_HOUR,
            horizonMinutes = 60,
            valueMmol = hourBaseline + cobInfluence,
            ciLow = max(2.2, hourBaseline + cobInfluence - 1.2),
            ciHigh = hourBaseline + cobInfluence + 1.2,
            modelVersion = "local-ensemble-v1"
        )

        return listOf(forecast5m, forecast30m, forecast60m)
    }

    private fun estimateSlopePerFiveMinutes(points: List<GlucosePoint>): Double {
        val recent = points.takeLast(6)
        if (recent.size < 2) return 0.0
        val first = recent.first()
        val last = recent.last()
        val elapsedMs = (last.ts - first.ts).coerceAtLeast(1)
        val slopePerMs = (last.valueMmol - first.valueMmol) / elapsedMs.toDouble()
        return slopePerMs * FIVE_MINUTES
    }

    private fun forecastOneHour(points: List<GlucosePoint>): Double {
        val alpha = 0.3
        var smoothed = points.first().valueMmol
        points.forEach { point ->
            smoothed = alpha * point.valueMmol + (1 - alpha) * smoothed
        }
        val slope = estimateSlopePerFiveMinutes(points)
        return smoothed + slope * 12
    }

    private companion object {
        const val FIVE_MINUTES = 5 * 60 * 1000L
        const val THIRTY_MINUTES = 30 * 60 * 1000L
        const val ONE_HOUR = 60 * 60 * 1000L
    }
}
