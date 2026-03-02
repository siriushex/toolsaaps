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
    val correctionBolusMinUnits: Double = 0.10,
    val correctionCarbsAroundMinutes: Int = 60,
    val correctionMaxCarbsAroundGrams: Double = 5.0,
    val correctionBaselineSearchMinutes: Int = 20,
    val correctionDropWindowStartMinutes: Int = 60,
    val correctionDropWindowEndMinutes: Int = 240,
    val correctionMinFutureSamples: Int = 1,
    val correctionMaxGapMinutes: Int = 120,
    val correctionMinDropMmol: Double = 0.20,
    val uamWindowBeforeMinutes: Int = 20,
    val uamWindowAfterMinutes: Int = 110,
    val uamEpisodeGapMinutes: Int = 20,
    val uamRecentWindowMinutes: Int = 360,
    val uamMinEpisodePoints: Int = 2,
    val uamEpisodeMaxMinutes: Int = 90,
    val uamPeakSearchMinutes: Int = 120,
    val uamIgnoreAnnouncedCarbsBeforeMinutes: Int = 45,
    val uamIgnoreAnnouncedCarbsAfterMinutes: Int = 120,
    val uamMinRiseMmol: Double = 0.35,
    val uamEpisodeMaxGrams: Double = 90.0,
    val uamTotalMaxGrams: Double = 240.0,
    val uamRecentMaxGrams: Double = 120.0
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

    companion object {
        private val CAMEL_CASE_BOUNDARY_REGEX = Regex("([a-z0-9])([A-Z])")
        private val NON_ALNUM_REGEX = Regex("[^a-z0-9]+")
    }

    fun estimate(
        glucoseHistory: List<GlucosePoint>,
        therapyEvents: List<TherapyEvent>,
        telemetrySignals: List<TelemetrySignal> = emptyList()
    ): ProfileEstimate? {
        if (glucoseHistory.isEmpty()) return null
        val sortedGlucose = glucoseHistory.sortedBy { it.ts }
        val sortedEvents = therapyEvents.sortedBy { it.ts }
        val sortedTelemetry = telemetrySignals.sortedBy { it.ts }

        val uamPoints = buildUamPoints(sortedGlucose, sortedEvents)
        val isfBuild = buildIsfSamples(sortedGlucose, sortedEvents, uamPoints)
        val crTherapySamples = buildCrSamples(sortedEvents)
        val telemetryIsfSamples = buildTelemetryIsfSamples(sortedTelemetry)
        val telemetryCrSamples = buildTelemetryCrSamples(sortedTelemetry)

        val isfSamples = trimOutliers((isfBuild.samples + telemetryIsfSamples).map { it.value })
        val crSamples = trimOutliers((crTherapySamples + telemetryCrSamples).map { it.value })

        if (isfSamples.size < config.minIsfSamples || crSamples.size < config.minCrSamples) return null

        val isf = median(isfSamples)
        val cr = median(crSamples)
        val uamCarbEstimate = estimateUamCarbs(
            glucose = sortedGlucose,
            therapyEvents = sortedEvents,
            uamPoints = uamPoints,
            isfMmolPerUnit = isf,
            crGramPerUnit = cr
        )
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
            uamFilteredIsfSamples = isfBuild.filteredByUam,
            uamEpisodeCount = uamCarbEstimate.episodeCount,
            uamEstimatedCarbsGrams = uamCarbEstimate.totalGrams,
            uamEstimatedRecentCarbsGrams = uamCarbEstimate.recentGrams
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

        val uamPoints = buildUamPoints(sortedGlucose, sortedEvents)
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

    data class HourlyProfileEstimate(
        val hour: Int,
        val isfMmolPerUnit: Double?,
        val crGramPerUnit: Double?,
        val confidence: Double,
        val isfSampleCount: Int,
        val crSampleCount: Int
    )

    data class HourlyDayTypeProfileEstimate(
        val dayType: DayType,
        val hour: Int,
        val isfMmolPerUnit: Double?,
        val crGramPerUnit: Double?,
        val confidence: Double,
        val isfSampleCount: Int,
        val crSampleCount: Int
    )

    fun estimateHourly(
        glucoseHistory: List<GlucosePoint>,
        therapyEvents: List<TherapyEvent>,
        telemetrySignals: List<TelemetrySignal> = emptyList()
    ): List<HourlyProfileEstimate> {
        if (glucoseHistory.isEmpty()) return emptyList()
        val sortedGlucose = glucoseHistory.sortedBy { it.ts }
        val sortedEvents = therapyEvents.sortedBy { it.ts }
        val sortedTelemetry = telemetrySignals.sortedBy { it.ts }

        val uamPoints = buildUamPoints(sortedGlucose, sortedEvents)
        val isfSamples = buildIsfSamples(sortedGlucose, sortedEvents, uamPoints).samples + buildTelemetryIsfSamples(sortedTelemetry)
        val crSamples = buildCrSamples(sortedEvents) + buildTelemetryCrSamples(sortedTelemetry)
        if (isfSamples.isEmpty() && crSamples.isEmpty()) return emptyList()

        val isfByHour = isfSamples.groupBy { hourOf(it.ts) }
        val crByHour = crSamples.groupBy { hourOf(it.ts) }

        return (0..23).mapNotNull { hour ->
            val isfValues = trimOutliers(isfByHour[hour].orEmpty().map { it.value })
            val crValues = trimOutliers(crByHour[hour].orEmpty().map { it.value })
            val isfCount = isfValues.size
            val crCount = crValues.size
            if (isfCount == 0 && crCount == 0) return@mapNotNull null

            val confidence = (
                (isfCount / (config.minSegmentSamples * 2.0)).coerceIn(0.0, 1.0) * 0.5 +
                    (crCount / (config.minSegmentSamples * 2.0)).coerceIn(0.0, 1.0) * 0.5
                ).coerceIn(0.10, 0.99)

            HourlyProfileEstimate(
                hour = hour,
                isfMmolPerUnit = isfValues.takeIf { it.isNotEmpty() }?.let(::median),
                crGramPerUnit = crValues.takeIf { it.isNotEmpty() }?.let(::median),
                confidence = confidence,
                isfSampleCount = isfCount,
                crSampleCount = crCount
            )
        }
    }

    fun estimateHourlyByDayType(
        glucoseHistory: List<GlucosePoint>,
        therapyEvents: List<TherapyEvent>,
        telemetrySignals: List<TelemetrySignal> = emptyList()
    ): List<HourlyDayTypeProfileEstimate> {
        if (glucoseHistory.isEmpty()) return emptyList()
        val sortedGlucose = glucoseHistory.sortedBy { it.ts }
        val sortedEvents = therapyEvents.sortedBy { it.ts }
        val sortedTelemetry = telemetrySignals.sortedBy { it.ts }

        val uamPoints = buildUamPoints(sortedGlucose, sortedEvents)
        val isfSamples = buildIsfSamples(sortedGlucose, sortedEvents, uamPoints).samples + buildTelemetryIsfSamples(sortedTelemetry)
        val crSamples = buildCrSamples(sortedEvents) + buildTelemetryCrSamples(sortedTelemetry)
        if (isfSamples.isEmpty() && crSamples.isEmpty()) return emptyList()

        val isfByHourDay = isfSamples.groupBy { dayTypeOf(it.ts) to hourOf(it.ts) }
        val crByHourDay = crSamples.groupBy { dayTypeOf(it.ts) to hourOf(it.ts) }

        return DayType.entries.flatMap { dayType ->
            (0..23).mapNotNull { hour ->
                val key = dayType to hour
                val isfValues = trimOutliers(isfByHourDay[key].orEmpty().map { it.value })
                val crValues = trimOutliers(crByHourDay[key].orEmpty().map { it.value })
                val isfCount = isfValues.size
                val crCount = crValues.size
                if (isfCount == 0 && crCount == 0) return@mapNotNull null

                val confidence = (
                    (isfCount / (config.minSegmentSamples * 2.0)).coerceIn(0.0, 1.0) * 0.5 +
                        (crCount / (config.minSegmentSamples * 2.0)).coerceIn(0.0, 1.0) * 0.5
                    ).coerceIn(0.10, 0.99)

                HourlyDayTypeProfileEstimate(
                    dayType = dayType,
                    hour = hour,
                    isfMmolPerUnit = isfValues.takeIf { it.isNotEmpty() }?.let(::median),
                    crGramPerUnit = crValues.takeIf { it.isNotEmpty() }?.let(::median),
                    confidence = confidence,
                    isfSampleCount = isfCount,
                    crSampleCount = crCount
                )
            }
        }
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
            if (units < config.correctionBolusMinUnits) return@mapNotNull null

            val carbsAround = announcedCarbsAroundCorrection(events, bolus.ts)
            if (carbsAround > config.correctionMaxCarbsAroundGrams) return@mapNotNull null
            if (hasUamNearCorrection(uamPoints, bolus.ts)) {
                filteredByUam += 1
                return@mapNotNull null
            }

            val before = glucose.closestTo(
                targetTs = bolus.ts,
                maxDistanceMs = config.correctionBaselineSearchMinutes * 60 * 1000L
            ) ?: return@mapNotNull null
            val windowStart = bolus.ts + config.correctionDropWindowStartMinutes * 60_000L
            val windowEnd = bolus.ts + config.correctionDropWindowEndMinutes * 60_000L
            val futureWindow = glucose
                .asSequence()
                .filter { point -> point.ts in windowStart..windowEnd }
                .sortedBy { it.ts }
                .toList()
            if (futureWindow.size < config.correctionMinFutureSamples) return@mapNotNull null
            if (!isGlucoseWindowDense(futureWindow)) return@mapNotNull null

            val minAfter = futureWindow.minByOrNull { it.valueMmol } ?: return@mapNotNull null
            val drop = before.valueMmol - minAfter.valueMmol
            if (drop < config.correctionMinDropMmol) return@mapNotNull null
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

    private fun buildUamPoints(
        glucose: List<GlucosePoint>,
        events: List<TherapyEvent>
    ): Set<Long> {
        return UamCalculator.detectSignals(
            glucose = glucose,
            therapyEvents = events
        ).map { it.ts }.toSet()
    }

    private fun extractBolusUnits(event: TherapyEvent): Double? {
        val keys = listOf("units", "bolusUnits", "insulin", "enteredInsulin")
        return keys.firstNotNullOfOrNull { key -> event.payload[key]?.toDoubleOrNull() }
    }

    private fun extractCarbGrams(event: TherapyEvent): Double? {
        val keys = listOf("grams", "carbs", "enteredCarbs", "mealCarbs")
        return keys.firstNotNullOfOrNull { key -> event.payload[key]?.toDoubleOrNull() }
    }

    private fun hasUamNearCorrection(uamPoints: Set<Long>, correctionTs: Long): Boolean {
        val from = correctionTs - config.uamWindowBeforeMinutes * 60_000L
        val to = correctionTs + config.uamWindowAfterMinutes * 60_000L
        return uamPoints.any { it in from..to }
    }

    private fun announcedCarbsAroundCorrection(events: List<TherapyEvent>, correctionTs: Long): Double {
        val windowMs = config.correctionCarbsAroundMinutes * 60_000L
        val from = correctionTs - windowMs
        val to = correctionTs + windowMs
        return events
            .asSequence()
            .filter { event ->
                event.ts in from..to &&
                    (
                        event.type.equals("meal_bolus", ignoreCase = true) ||
                            event.type.equals("carbs", ignoreCase = true)
                        )
            }
            .sumOf { event ->
                val grams = extractCarbGrams(event)
                when {
                    grams != null && grams > 0.0 -> grams
                    event.type.equals("meal_bolus", ignoreCase = true) -> config.correctionMaxCarbsAroundGrams + 1.0
                    else -> 0.0
                }
            }
    }

    private fun isGlucoseWindowDense(points: List<GlucosePoint>): Boolean {
        if (points.size <= 2) return true
        val maxGapMs = config.correctionMaxGapMinutes * 60_000L
        val sorted = points.sortedBy { it.ts }
        for (index in 1 until sorted.size) {
            if (sorted[index].ts - sorted[index - 1].ts > maxGapMs) return false
        }
        return true
    }

    private fun estimateUamCarbs(
        glucose: List<GlucosePoint>,
        therapyEvents: List<TherapyEvent>,
        uamPoints: Set<Long>,
        isfMmolPerUnit: Double,
        crGramPerUnit: Double
    ): UamCarbEstimate {
        if (glucose.isEmpty() || uamPoints.isEmpty()) return UamCarbEstimate.EMPTY
        if (isfMmolPerUnit <= 0.0 || crGramPerUnit <= 0.0) return UamCarbEstimate.EMPTY

        val csfMmolPerGram = (isfMmolPerUnit / crGramPerUnit).takeIf { it > 0.0 } ?: return UamCarbEstimate.EMPTY
        val episodes = buildUamEpisodes(uamPoints.toList().sorted())
        if (episodes.isEmpty()) return UamCarbEstimate.EMPTY

        val latestTs = glucose.maxOf { it.ts }
        val recentCutoff = latestTs - config.uamRecentWindowMinutes * 60_000L
        var total = 0.0
        var recent = 0.0
        var acceptedEpisodes = 0

        episodes.forEach { episode ->
            if (episode.points < config.uamMinEpisodePoints) return@forEach
            if (hasAnnouncedCarbsNearEpisode(therapyEvents, episode.startTs)) return@forEach

            val baseline = glucose.closestTo(
                targetTs = episode.startTs - 10 * 60_000L,
                maxDistanceMs = 35 * 60_000L
            ) ?: return@forEach

            val effectiveEnd = minOf(
                episode.endTs,
                episode.startTs + config.uamEpisodeMaxMinutes * 60_000L
            )
            val peakWindowEnd = minOf(
                effectiveEnd + 30 * 60_000L,
                episode.startTs + config.uamPeakSearchMinutes * 60_000L
            )
            val peak = glucose
                .asSequence()
                .filter { point ->
                    point.ts in (episode.startTs + 5 * 60_000L)..peakWindowEnd
                }
                .maxByOrNull { it.valueMmol }
                ?: return@forEach

            val rise = peak.valueMmol - baseline.valueMmol
            if (rise < config.uamMinRiseMmol) return@forEach

            val durationMinutes = ((effectiveEnd - episode.startTs).coerceAtLeast(5 * 60_000L)) / 60_000.0
            val expectedPoints = (durationMinutes / 5.0).coerceAtLeast(1.0)
            val densityFactor = (episode.points / expectedPoints).coerceIn(0.4, 1.0)
            val confidenceFactor = when {
                episode.points >= 6 -> 1.0
                episode.points >= 4 -> 0.85
                else -> 0.70
            }

            val gramsRaw = rise / csfMmolPerGram
            val grams = (gramsRaw * densityFactor * confidenceFactor).coerceIn(0.0, config.uamEpisodeMaxGrams)
            if (grams < 1.0) return@forEach

            acceptedEpisodes += 1
            total = (total + grams).coerceAtMost(config.uamTotalMaxGrams)
            if (episode.endTs >= recentCutoff) {
                recent = (recent + grams).coerceAtMost(config.uamRecentMaxGrams)
            }
        }

        return UamCarbEstimate(
            episodeCount = acceptedEpisodes,
            totalGrams = total,
            recentGrams = recent
        )
    }

    private fun hasAnnouncedCarbsNearEpisode(events: List<TherapyEvent>, episodeStartTs: Long): Boolean {
        val from = episodeStartTs - config.uamIgnoreAnnouncedCarbsBeforeMinutes * 60_000L
        val to = episodeStartTs + config.uamIgnoreAnnouncedCarbsAfterMinutes * 60_000L
        return events.any { event ->
            event.ts in from..to &&
                (
                    event.type.equals("meal_bolus", ignoreCase = true) ||
                        event.type.equals("carbs", ignoreCase = true)
                    )
        }
    }

    private fun buildUamEpisodes(sortedPoints: List<Long>): List<UamEpisode> {
        if (sortedPoints.isEmpty()) return emptyList()
        val maxGapMs = config.uamEpisodeGapMinutes * 60_000L
        val episodes = mutableListOf<UamEpisode>()

        var start = sortedPoints.first()
        var end = sortedPoints.first()
        var points = 1

        for (index in 1 until sortedPoints.size) {
            val ts = sortedPoints[index]
            if (ts - end <= maxGapMs) {
                end = ts
                points += 1
                continue
            }
            episodes += UamEpisode(startTs = start, endTs = end, points = points)
            start = ts
            end = ts
            points = 1
        }
        episodes += UamEpisode(startTs = start, endTs = end, points = points)
        return episodes
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

    private fun hourOf(ts: Long): Int = Instant.ofEpochMilli(ts).atZone(zoneId).hour

    private fun dayTypeOf(ts: Long): DayType {
        return when (Instant.ofEpochMilli(ts).atZone(zoneId).dayOfWeek.value) {
            6, 7 -> DayType.WEEKEND
            else -> DayType.WEEKDAY
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

    private fun normalizeKey(raw: String): String {
        return raw
            .replace(CAMEL_CASE_BOUNDARY_REGEX, "$1_$2")
            .lowercase()
            .replace(NON_ALNUM_REGEX, "_")
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

    private data class UamEpisode(
        val startTs: Long,
        val endTs: Long,
        val points: Int
    )

    private data class UamCarbEstimate(
        val episodeCount: Int,
        val totalGrams: Double,
        val recentGrams: Double
    ) {
        companion object {
            val EMPTY = UamCarbEstimate(
                episodeCount = 0,
                totalGrams = 0.0,
                recentGrams = 0.0
            )
        }
    }
}
