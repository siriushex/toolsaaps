package io.aaps.copilot.domain.predict

import io.aaps.copilot.domain.model.DayType
import io.aaps.copilot.domain.model.GlucosePoint
import io.aaps.copilot.domain.model.ProfileEstimate
import io.aaps.copilot.domain.model.ProfileSegmentEstimate
import io.aaps.copilot.domain.model.ProfileTimeSlot
import io.aaps.copilot.domain.model.TherapyEvent
import java.time.Instant
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.floor

data class ProfileEstimatorConfig(
    val minIsfSamples: Int = 4,
    val minCrSamples: Int = 4,
    val minSegmentSamples: Int = 2,
    val trimFraction: Double = 0.10,
    val lookbackDays: Int = 365
)

class ProfileEstimator(
    private val config: ProfileEstimatorConfig = ProfileEstimatorConfig(),
    private val zoneId: ZoneId = ZoneId.systemDefault()
) {

    fun estimate(
        glucoseHistory: List<GlucosePoint>,
        therapyEvents: List<TherapyEvent>
    ): ProfileEstimate? {
        if (glucoseHistory.isEmpty() || therapyEvents.isEmpty()) return null
        val sortedGlucose = glucoseHistory.sortedBy { it.ts }
        val sortedEvents = therapyEvents.sortedBy { it.ts }

        val isfSamples = trimOutliers(buildIsfSamples(sortedGlucose, sortedEvents).map { it.value })
        val crSamples = trimOutliers(buildCrSamples(sortedEvents).map { it.value })

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

    fun estimateSegments(
        glucoseHistory: List<GlucosePoint>,
        therapyEvents: List<TherapyEvent>
    ): List<ProfileSegmentEstimate> {
        if (glucoseHistory.isEmpty() || therapyEvents.isEmpty()) return emptyList()
        val sortedGlucose = glucoseHistory.sortedBy { it.ts }
        val sortedEvents = therapyEvents.sortedBy { it.ts }
        val isfSamples = buildIsfSamples(sortedGlucose, sortedEvents)
        val crSamples = buildCrSamples(sortedEvents)
        if (isfSamples.isEmpty() && crSamples.isEmpty()) return emptyList()

        val isfBySegment = isfSamples.groupBy { segmentKey(it.ts) }
        val crBySegment = crSamples.groupBy { segmentKey(it.ts) }
        val keys = (isfBySegment.keys + crBySegment.keys).distinct()

        return keys.mapNotNull { key ->
            val isfValues = trimOutliers(isfBySegment[key].orEmpty().map { it.value })
            val crValues = trimOutliers(crBySegment[key].orEmpty().map { it.value })
            val isfCount = isfValues.size
            val crCount = crValues.size
            if (isfCount < config.minSegmentSamples && crCount < config.minSegmentSamples) {
                return@mapNotNull null
            }
            val confidence = (
                (isfCount / (config.minSegmentSamples * 3.0)) * 0.5 +
                    (crCount / (config.minSegmentSamples * 3.0)) * 0.5
                ).coerceIn(0.15, 0.95)

            ProfileSegmentEstimate(
                dayType = key.first,
                timeSlot = key.second,
                isfMmolPerUnit = isfValues.takeIf { it.size >= config.minSegmentSamples }?.let(::median),
                crGramPerUnit = crValues.takeIf { it.size >= config.minSegmentSamples }?.let(::median),
                confidence = confidence,
                isfSampleCount = isfCount,
                crSampleCount = crCount,
                lookbackDays = config.lookbackDays
            )
        }.sortedWith(
            compareByDescending<ProfileSegmentEstimate> { it.confidence * (it.isfSampleCount + it.crSampleCount) }
                .thenBy { it.dayType.name }
                .thenBy { it.timeSlot.name }
        )
    }

    private fun buildIsfSamples(
        glucose: List<GlucosePoint>,
        events: List<TherapyEvent>
    ): List<TimedSample> {
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
            (drop / units).takeIf { it in 0.2..18.0 }?.let { TimedSample(bolus.ts, it) }
        }
    }

    private fun buildCrSamples(events: List<TherapyEvent>): List<TimedSample> {
        val mealEvents = events.filter {
            it.type.equals("meal_bolus", ignoreCase = true) ||
                (it.type.equals("carbs", ignoreCase = true) && it.payload["bolusUnits"] != null)
        }

        return mealEvents.mapNotNull { event ->
            val grams = event.payload["grams"]?.toDoubleOrNull() ?: return@mapNotNull null
            val units = event.payload["bolusUnits"]?.toDoubleOrNull() ?: return@mapNotNull null
            if (grams <= 0.0 || units <= 0.0) return@mapNotNull null
            (grams / units).takeIf { it in 2.0..60.0 }?.let { TimedSample(event.ts, it) }
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

    private fun segmentKey(ts: Long): Pair<DayType, ProfileTimeSlot> {
        val dateTime = Instant.ofEpochMilli(ts).atZone(zoneId)
        val dayType = when (dateTime.dayOfWeek.value) {
            6, 7 -> DayType.WEEKEND
            else -> DayType.WEEKDAY
        }
        val slot = when (dateTime.hour) {
            in 0..5 -> ProfileTimeSlot.NIGHT
            in 6..11 -> ProfileTimeSlot.MORNING
            in 12..17 -> ProfileTimeSlot.AFTERNOON
            else -> ProfileTimeSlot.EVENING
        }
        return dayType to slot
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

    private data class TimedSample(
        val ts: Long,
        val value: Double
    )
}
