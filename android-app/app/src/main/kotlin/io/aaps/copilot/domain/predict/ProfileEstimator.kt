package io.aaps.copilot.domain.predict

import io.aaps.copilot.domain.model.DayType
import io.aaps.copilot.domain.model.GlucosePoint
import io.aaps.copilot.domain.model.ProfileEstimate
import io.aaps.copilot.domain.model.ProfileSegmentEstimate
import io.aaps.copilot.domain.model.ProfileTimeSlot
import io.aaps.copilot.domain.model.TherapyEvent
import io.aaps.copilot.util.UnitConverter
import java.time.Instant
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.floor

data class ProfileEstimatorConfig(
    val minIsfSamples: Int = 4,
    val minCrSamples: Int = 4,
    val minSegmentSamples: Int = 2,
    val trimFraction: Double = 0.10,
    val lookbackDays: Int = 365,
    val uamWindowBeforeMinutes: Int = 20,
    val uamWindowAfterMinutes: Int = 110
)

data class TelemetrySignal(
    val ts: Long,
    val key: String,
    val valueDouble: Double?,
    val valueText: String? = null
)

class ProfileEstimator(
    private val config: ProfileEstimatorConfig = ProfileEstimatorConfig(),
    private val zoneId: ZoneId = ZoneId.systemDefault()
) {

    fun estimate(
        glucoseHistory: List<GlucosePoint>,
        therapyEvents: List<TherapyEvent>,
        telemetrySignals: List<TelemetrySignal> = emptyList()
    ): ProfileEstimate? {
        if (glucoseHistory.isEmpty()) return null
        val sortedGlucose = glucoseHistory.sortedBy { it.ts }
        val sortedEvents = therapyEvents.sortedBy { it.ts }
        val sortedTelemetry = telemetrySignals.sortedBy { it.ts }

        val uamPoints = buildUamPoints(sortedTelemetry)
        val isfBuild = buildIsfSamples(sortedGlucose, sortedEvents, uamPoints)
        val crTherapySamples = buildCrSamples(sortedEvents)
        val telemetryIsfSamples = buildTelemetryIsfSamples(sortedTelemetry)
        val telemetryCrSamples = buildTelemetryCrSamples(sortedTelemetry)

        val isfSamples = trimOutliers((isfBuild.samples + telemetryIsfSamples).map { it.value })
        val crSamples = trimOutliers((crTherapySamples + telemetryCrSamples).map { it.value })

        if (isfSamples.size < config.minIsfSamples || crSamples.size < config.minCrSamples) return null

        val isf = median(isfSamples)
        val cr = median(crSamples)
        val sampleCount = isfSamples.size + crSamples.size
        val rawConfidence = (
            (isfSamples.size / (config.minIsfSamples * 4.0)) * 0.5 +
                (crSamples.size / (config.minCrSamples * 4.0)) * 0.5
            ).coerceIn(0.20, 0.99)
        val uamPenalty = (uamPoints.size.coerceAtMost(40) / 200.0)
        val confidence = (rawConfidence - uamPenalty).coerceIn(0.20, 0.99)

        return ProfileEstimate(
            isfMmolPerUnit = isf,
            crGramPerUnit = cr,
            confidence = confidence,
            sampleCount = sampleCount,
            isfSampleCount = isfSamples.size,
            crSampleCount = crSamples.size,
            lookbackDays = config.lookbackDays,
            telemetryIsfSampleCount = telemetryIsfSamples.size,
            telemetryCrSampleCount = telemetryCrSamples.size,
            uamObservedCount = uamPoints.size,
            uamFilteredIsfSamples = isfBuild.filteredByUam
        )
    }

    fun estimateSegments(
        glucoseHistory: List<GlucosePoint>,
        therapyEvents: List<TherapyEvent>,
        telemetrySignals: List<TelemetrySignal> = emptyList()
    ): List<ProfileSegmentEstimate> {
        if (glucoseHistory.isEmpty()) return emptyList()
        val sortedGlucose = glucoseHistory.sortedBy { it.ts }
        val sortedEvents = therapyEvents.sortedBy { it.ts }
        val sortedTelemetry = telemetrySignals.sortedBy { it.ts }

        val uamPoints = buildUamPoints(sortedTelemetry)
        val isfSamples = buildIsfSamples(sortedGlucose, sortedEvents, uamPoints).samples + buildTelemetryIsfSamples(sortedTelemetry)
        val crSamples = buildCrSamples(sortedEvents) + buildTelemetryCrSamples(sortedTelemetry)
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
        events: List<TherapyEvent>,
        uamPoints: Set<Long>
    ): IsfBuildResult {
        val correctionEvents = events.filter {
            it.type.equals("correction_bolus", ignoreCase = true) ||
                (
                    it.type.equals("bolus", ignoreCase = true) &&
                        (
                            it.payload["isCorrection"]?.equals("true", ignoreCase = true) == true ||
                                it.payload["reason"]?.contains("correction", ignoreCase = true) == true
                            )
                    )
        }

        var filteredByUam = 0
        val samples = correctionEvents.mapNotNull { bolus ->
            val units = extractBolusUnits(bolus) ?: return@mapNotNull null
            if (units <= 0.0) return@mapNotNull null

            if (hasMealNearCorrection(events, bolus.ts)) return@mapNotNull null
            if (hasUamNearCorrection(uamPoints, bolus.ts)) {
                filteredByUam += 1
                return@mapNotNull null
            }

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
        return IsfBuildResult(samples = samples, filteredByUam = filteredByUam)
    }

    private fun buildCrSamples(events: List<TherapyEvent>): List<TimedSample> {
        val direct = events.mapNotNull { event ->
            if (!event.type.equals("meal_bolus", ignoreCase = true)) return@mapNotNull null
            val grams = extractCarbGrams(event) ?: return@mapNotNull null
            val units = extractBolusUnits(event) ?: return@mapNotNull null
            if (grams <= 0.0 || units <= 0.0) return@mapNotNull null
            (grams / units).takeIf { it in 2.0..60.0 }?.let { TimedSample(event.ts, it) }
        }

        val carbOnlyEvents = events.filter { event ->
            event.type.equals("carbs", ignoreCase = true) && extractBolusUnits(event) == null
        }
        val bolusCandidates = events.mapIndexedNotNull { index, event ->
            if (
                !event.type.equals("meal_bolus", ignoreCase = true) &&
                !event.type.equals("bolus", ignoreCase = true) &&
                !event.type.equals("correction_bolus", ignoreCase = true)
            ) {
                return@mapIndexedNotNull null
            }
            val units = extractBolusUnits(event) ?: return@mapIndexedNotNull null
            if (units <= 0.0) return@mapIndexedNotNull null
            Triple(index, event.ts, units)
        }

        val paired = mutableListOf<TimedSample>()
        val usedBolusIndexes = mutableSetOf<Int>()
        carbOnlyEvents.forEach { carbEvent ->
            val grams = extractCarbGrams(carbEvent) ?: return@forEach
            if (grams <= 0.0) return@forEach
            val nearest = bolusCandidates
                .asSequence()
                .filter { (index, _, _) -> index !in usedBolusIndexes }
                .filter { (_, bolusTs, _) -> abs(bolusTs - carbEvent.ts) <= 25 * 60_000L }
                .minByOrNull { (_, bolusTs, _) -> abs(bolusTs - carbEvent.ts) }
                ?: return@forEach
            usedBolusIndexes += nearest.first
            val units = nearest.third
            (grams / units).takeIf { it in 2.0..60.0 }?.let { paired += TimedSample(carbEvent.ts, it) }
        }

        return direct + paired
    }

    private fun buildTelemetryIsfSamples(signals: List<TelemetrySignal>): List<TimedSample> {
        return signals.mapNotNull { signal ->
            if (!isTelemetryIsfKey(signal.key)) return@mapNotNull null
            val raw = signal.numericValue() ?: return@mapNotNull null
            val mmolPerUnit = if (raw > 12.0) UnitConverter.mgdlToMmol(raw) else raw
            mmolPerUnit.takeIf { it in 0.2..18.0 }?.let { TimedSample(signal.ts, it) }
        }
    }

    private fun buildTelemetryCrSamples(signals: List<TelemetrySignal>): List<TimedSample> {
        return signals.mapNotNull { signal ->
            if (!isTelemetryCrKey(signal.key)) return@mapNotNull null
            val gramsPerUnit = signal.numericValue() ?: return@mapNotNull null
            gramsPerUnit.takeIf { it in 2.0..60.0 }?.let { TimedSample(signal.ts, it) }
        }
    }

    private fun buildUamPoints(signals: List<TelemetrySignal>): Set<Long> {
        return signals
            .asSequence()
            .filter { isUamKey(it.key) }
            .mapNotNull { signal ->
                val value = signal.numericValue() ?: return@mapNotNull null
                if (value > 0.0) signal.ts else null
            }
            .toSet()
    }

    private fun extractBolusUnits(event: TherapyEvent): Double? {
        val keys = listOf("units", "bolusUnits", "insulin", "enteredInsulin")
        return keys.firstNotNullOfOrNull { key -> event.payload[key]?.toDoubleOrNull() }
    }

    private fun extractCarbGrams(event: TherapyEvent): Double? {
        val keys = listOf("grams", "carbs", "enteredCarbs", "mealCarbs")
        return keys.firstNotNullOfOrNull { key -> event.payload[key]?.toDoubleOrNull() }
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

    private fun hasUamNearCorrection(uamPoints: Set<Long>, correctionTs: Long): Boolean {
        val from = correctionTs - config.uamWindowBeforeMinutes * 60_000L
        val to = correctionTs + config.uamWindowAfterMinutes * 60_000L
        return uamPoints.any { it in from..to }
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

    private fun TelemetrySignal.numericValue(): Double? {
        return valueDouble ?: valueText?.replace(",", ".")?.toDoubleOrNull()
    }

    private fun isTelemetryIsfKey(key: String): Boolean {
        val normalized = normalizeKey(key)
        return normalized == "isf_value" ||
            normalized.contains("_isf_") ||
            normalized.endsWith("_isf") ||
            normalized.contains("_sens_") ||
            normalized.endsWith("_sens") ||
            normalized.contains("sensitivity")
    }

    private fun isTelemetryCrKey(key: String): Boolean {
        val normalized = normalizeKey(key)
        return normalized == "cr_value" ||
            normalized.contains("carb_ratio") ||
            normalized.contains("ic_ratio") ||
            normalized.endsWith("_cr") ||
            normalized.contains("_cr_")
    }

    private fun isUamKey(key: String): Boolean {
        val normalized = normalizeKey(key)
        return Regex("(^|_)uam(_|$)").containsMatchIn(normalized)
    }

    private fun normalizeKey(raw: String): String {
        return raw
            .replace(Regex("([a-z0-9])([A-Z])"), "$1_$2")
            .lowercase()
            .replace(Regex("[^a-z0-9]+"), "_")
            .trim('_')
    }

    private data class TimedSample(
        val ts: Long,
        val value: Double
    )

    private data class IsfBuildResult(
        val samples: List<TimedSample>,
        val filteredByUam: Int
    )
}
