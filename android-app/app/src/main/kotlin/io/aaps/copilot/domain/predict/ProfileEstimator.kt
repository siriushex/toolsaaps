package io.aaps.copilot.domain.predict

import io.aaps.copilot.domain.model.GlucosePoint
import io.aaps.copilot.domain.model.ProfileEstimate
import io.aaps.copilot.domain.model.TherapyEvent
import kotlin.math.abs

class ProfileEstimator {

    fun estimate(
        glucoseHistory: List<GlucosePoint>,
        therapyEvents: List<TherapyEvent>
    ): ProfileEstimate? {
        if (glucoseHistory.isEmpty() || therapyEvents.isEmpty()) return null
        val sortedGlucose = glucoseHistory.sortedBy { it.ts }

        val isfSamples = buildIsfSamples(sortedGlucose, therapyEvents)
        val crSamples = buildCrSamples(therapyEvents)

        if (isfSamples.isEmpty() || crSamples.isEmpty()) return null

        val isf = median(isfSamples)
        val cr = median(crSamples)
        val sampleCount = isfSamples.size + crSamples.size
        val confidence = (sampleCount / 120.0).coerceIn(0.15, 0.99)

        return ProfileEstimate(
            isfMmolPerUnit = isf,
            crGramPerUnit = cr,
            confidence = confidence,
            sampleCount = sampleCount
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

            val before = glucose.closestTo(bolus.ts, maxDistanceMs = 15 * 60 * 1000L) ?: return@mapNotNull null
            val after = glucose.closestTo(bolus.ts + 90 * 60 * 1000L, maxDistanceMs = 30 * 60 * 1000L) ?: return@mapNotNull null

            val drop = before.valueMmol - after.valueMmol
            if (drop <= 0.0) return@mapNotNull null
            (drop / units).takeIf { it in 0.1..15.0 }
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
