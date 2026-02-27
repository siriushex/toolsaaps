package io.aaps.copilot.domain.predict

import io.aaps.copilot.domain.model.GlucosePoint
import io.aaps.copilot.domain.model.ProfileEstimate
import io.aaps.copilot.domain.model.TherapyEvent
import kotlin.math.abs
import kotlin.math.floor

data class ProfileEstimatorConfig(
    val minIsfSamples: Int = 4,
    val minCrSamples: Int = 4,
    val trimFraction: Double = 0.10,
    val lookbackDays: Int = 365
)

class ProfileEstimator(
    private val config: ProfileEstimatorConfig = ProfileEstimatorConfig()
) {

    fun estimate(
        glucoseHistory: List<GlucosePoint>,
        therapyEvents: List<TherapyEvent>
    ): ProfileEstimate? {
        if (glucoseHistory.isEmpty() || therapyEvents.isEmpty()) return null
        val sortedGlucose = glucoseHistory.sortedBy { it.ts }
        val sortedEvents = therapyEvents.sortedBy { it.ts }

        val isfSamples = trimOutliers(buildIsfSamples(sortedGlucose, sortedEvents))
        val crSamples = trimOutliers(buildCrSamples(sortedEvents))

        if (isfSamples.size < config.minIsfSamples || crSamples.size < config.minCrSamples) return null

        val isf = median(isfSamples)
        val cr = median(crSamples)
        val sampleCount = isfSamples.size + crSamples.size
        val confidence = (
            (isfSamples.size / (config.minIsfSamples * 4.0)) * 0.5 +
                (crSamples.size / (config.minCrSamples * 4.0)) * 0.5
            ).coerceIn(0.20, 0.99)

        return ProfileEstimate(
            isfMmolPerUnit = isf,
            crGramPerUnit = cr,
            confidence = confidence,
            sampleCount = sampleCount,
            isfSampleCount = isfSamples.size,
            crSampleCount = crSamples.size,
            lookbackDays = config.lookbackDays
        )
    }

    private fun buildIsfSamples(
        glucose: List<GlucosePoint>,
        events: List<TherapyEvent>
    ): List<Double> {
        val bolusEvents = events.filter {
            it.type.equals("correction_bolus", ignoreCase = true) ||
                (it.type.equals("bolus", ignoreCase = true) && (it.payload["isCorrection"] == "true"))
        }

        return bolusEvents.mapNotNull { bolus ->
            val units = bolus.payload["units"]?.toDoubleOrNull() ?: return@mapNotNull null
            if (units <= 0.0) return@mapNotNull null

            if (hasMealNearCorrection(events, bolus.ts)) return@mapNotNull null

            val before = glucose.closestTo(
                targetTs = bolus.ts - 10 * 60 * 1000L,
                maxDistanceMs = 20 * 60 * 1000L
            ) ?: return@mapNotNull null
            val after = glucose.closestTo(
                targetTs = bolus.ts + 90 * 60 * 1000L,
                maxDistanceMs = 35 * 60 * 1000L
            ) ?: return@mapNotNull null

            val drop = before.valueMmol - after.valueMmol
            if (drop <= 0.15) return@mapNotNull null
            (drop / units).takeIf { it in 0.2..18.0 }
        }
    }

    private fun buildCrSamples(events: List<TherapyEvent>): List<Double> {
        val mealEvents = events.filter {
            it.type.equals("meal_bolus", ignoreCase = true) ||
                (it.type.equals("carbs", ignoreCase = true) && it.payload["bolusUnits"] != null)
        }

        return mealEvents.mapNotNull { event ->
            val grams = event.payload["grams"]?.toDoubleOrNull() ?: return@mapNotNull null
            val units = event.payload["bolusUnits"]?.toDoubleOrNull() ?: return@mapNotNull null
            if (grams <= 0.0 || units <= 0.0) return@mapNotNull null
            (grams / units).takeIf { it in 2.0..60.0 }
        }
    }

    private fun hasMealNearCorrection(events: List<TherapyEvent>, correctionTs: Long): Boolean {
        val from = correctionTs - 20 * 60 * 1000L
        val to = correctionTs + 110 * 60 * 1000L
        return events.any { event ->
            event.ts in from..to &&
                (
                    event.type.equals("meal_bolus", ignoreCase = true) ||
                        event.type.equals("carbs", ignoreCase = true)
                    )
        }
    }

    private fun trimOutliers(values: List<Double>): List<Double> {
        if (values.size < 5) return values.sorted()
        val sorted = values.sorted()
        val trim = floor(sorted.size * config.trimFraction).toInt().coerceAtMost(sorted.size / 4)
        if (trim == 0 || trim * 2 >= sorted.size) return sorted
        return sorted.subList(trim, sorted.size - trim)
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
        val candidate = minByOrNull { abs(it.ts - targetTs) } ?: return null
        return candidate.takeIf { abs(candidate.ts - targetTs) <= maxDistanceMs }
    }
}
