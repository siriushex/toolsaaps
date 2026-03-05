package io.aaps.copilot.domain.isfcr

import io.aaps.copilot.domain.model.DayType
import io.aaps.copilot.domain.model.GlucosePoint
import io.aaps.copilot.domain.model.TherapyEvent
import io.aaps.copilot.domain.predict.CarbAbsorptionProfiles
import io.aaps.copilot.domain.predict.CarbAbsorptionType
import io.aaps.copilot.domain.predict.InsulinActionProfileId
import io.aaps.copilot.domain.predict.InsulinActionProfiles
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

class IsfCrWindowExtractor(
    private val qualityScorer: IsfCrQualityScorer = IsfCrQualityScorer(),
    private val zoneId: ZoneId = ZoneId.systemDefault()
) {

    data class ExtractionResult(
        val evidence: List<IsfCrEvidenceSample>,
        val droppedCount: Int,
        val droppedReasonCounts: Map<String, Int>
    )

    private data class SampleExtractionResult(
        val sample: IsfCrEvidenceSample?,
        val dropReason: String?
    )

    fun extract(
        history: IsfCrHistoryBundle,
        settings: IsfCrSettings,
        isfReference: Double
    ): ExtractionResult {
        val glucose = history.glucose.sortedBy { it.ts }
        val therapy = history.therapy.sortedBy { it.ts }
        val therapyWithImplicitCorrections = mergeWithImplicitCorrections(
            therapy = therapy,
            telemetry = history.telemetry
        )
        if (glucose.size < 8 || therapyWithImplicitCorrections.isEmpty()) {
            return ExtractionResult(
                evidence = emptyList(),
                droppedCount = 0,
                droppedReasonCounts = emptyMap()
            )
        }

        val isfSamples = mutableListOf<IsfCrEvidenceSample>()
        val crSamples = mutableListOf<IsfCrEvidenceSample>()
        val seenUamMealCandidates = mutableSetOf<String>()
        var dropped = 0
        val droppedReasonCounts = linkedMapOf<String, Int>()

        fun markDropped(reason: String?) {
            dropped += 1
            val normalizedReason = reason?.takeIf { it.isNotBlank() } ?: DROP_REASON_UNKNOWN
            droppedReasonCounts[normalizedReason] = (droppedReasonCounts[normalizedReason] ?: 0) + 1
        }

        therapyWithImplicitCorrections.forEach { event ->
            if (isCorrectionEvent(event)) {
                val sample = extractIsfSample(
                    event = event,
                    glucose = glucose,
                    therapy = therapyWithImplicitCorrections,
                    settings = settings
                )
                sample.sample?.let { isfSamples += it } ?: markDropped(sample.dropReason)
            }
            if (isMealEvent(event)) {
                val mealDedupKey = uamMealDedupKey(event)
                if (mealDedupKey != null && !seenUamMealCandidates.add(mealDedupKey)) {
                    return@forEach
                }
                val sample = extractCrSample(
                    mealEvent = event,
                    glucose = glucose,
                    therapy = therapyWithImplicitCorrections,
                    telemetry = history.telemetry,
                    settings = settings,
                    isfReference = isfReference
                )
                sample.sample?.let { crSamples += it } ?: markDropped(sample.dropReason)
            }
        }

        return ExtractionResult(
            evidence = (isfSamples + crSamples).sortedByDescending { it.ts },
            droppedCount = dropped,
            droppedReasonCounts = droppedReasonCounts.toMap()
        )
    }

    private fun extractIsfSample(
        event: TherapyEvent,
        glucose: List<GlucosePoint>,
        therapy: List<TherapyEvent>,
        settings: IsfCrSettings
    ): SampleExtractionResult {
        val units = extractInsulinUnits(event)
            ?: return droppedExtraction(DROP_REASON_ISF_MISSING_UNITS)
        if (units < 0.1) return droppedExtraction(DROP_REASON_ISF_SMALL_UNITS)

        val carbsAround = therapy
            .asSequence()
            .filter { abs(it.ts - event.ts) <= 60 * MINUTE_MS }
            .mapNotNull(::extractCarbsGrams)
            .sum()
        if (carbsAround > 5.0) return droppedExtraction(DROP_REASON_ISF_CARBS_AROUND)

        val baseline = glucose.closestTo(
            targetTs = event.ts,
            maxDistanceMs = 20 * MINUTE_MS
        ) ?: return droppedExtraction(DROP_REASON_ISF_MISSING_BASELINE)

        val windowStart = event.ts + 60 * MINUTE_MS
        val windowEnd = event.ts + 240 * MINUTE_MS
        val future = glucose.filter { it.ts in windowStart..windowEnd }
        if (future.isEmpty()) return droppedExtraction(DROP_REASON_ISF_MISSING_FUTURE)
        val minFuture = future.minByOrNull { it.valueMmol } ?: return droppedExtraction(DROP_REASON_ISF_MISSING_FUTURE)
        val drop = baseline.valueMmol - minFuture.valueMmol
        if (drop <= 0.0) return droppedExtraction(DROP_REASON_ISF_NON_POSITIVE_DROP)

        val isf = (drop / units).takeIf { it in 0.2..18.0 } ?: return droppedExtraction(DROP_REASON_ISF_OUT_OF_RANGE)
        val qualityPoints = glucose.filter { it.ts in (event.ts - 15 * MINUTE_MS)..windowEnd }
        val quality = qualityScorer.scoreWindow(qualityPoints)
        val setAgeHours = ageHoursAt(
            ts = event.ts,
            therapy = therapy,
            markerTypes = SET_CHANGE_TYPES
        )
        val sensorAgeHours = ageHoursAt(
            ts = event.ts,
            therapy = therapy,
            markerTypes = SENSOR_CHANGE_TYPES
        )
        val setAgeWeight = wearAgeWeight(
            ageHours = setAgeHours,
            stableHours = 24.0,
            rampHours = 120.0,
            minWeight = 0.55
        )
        val sensorAgeWeight = wearAgeWeight(
            ageHours = sensorAgeHours,
            stableHours = 120.0,
            rampHours = 120.0,
            minWeight = 0.65
        )
        val wearWeight = (setAgeWeight * sensorAgeWeight).coerceIn(0.35, 1.0)
        val weight = (quality.score * wearWeight).coerceIn(0.0, 1.0)
        if (weight <= 0.0) return droppedExtraction(DROP_REASON_ISF_LOW_QUALITY)

        val zoned = Instant.ofEpochMilli(event.ts).atZone(zoneId)
        val dayType = if (zoned.dayOfWeek.value in setOf(6, 7)) DayType.WEEKEND else DayType.WEEKDAY
        return extractedSample(
            IsfCrEvidenceSample(
            id = "isf:${event.ts}:${(isf * 100).roundToInt()}",
            ts = event.ts,
            sampleType = IsfCrSampleType.ISF,
            hourLocal = zoned.hour,
            dayType = dayType,
            value = isf,
            weight = weight,
            qualityScore = quality.score,
            context = mapOf(
                "units" to units.toString(),
                "drop" to drop.toString(),
                "carbsAround" to carbsAround.toString(),
                "setAgeHours" to (setAgeHours ?: -1.0).toString(),
                "sensorAgeHours" to (sensorAgeHours ?: -1.0).toString(),
                "setAgeWeight" to setAgeWeight.toString(),
                "sensorAgeWeight" to sensorAgeWeight.toString()
            ),
            window = mapOf(
                "startTs" to windowStart.toDouble(),
                "endTs" to windowEnd.toDouble(),
                "setAgeHours" to (setAgeHours ?: -1.0),
                "sensorAgeHours" to (sensorAgeHours ?: -1.0)
            )
        )
        )
    }

    private fun extractCrSample(
        mealEvent: TherapyEvent,
        glucose: List<GlucosePoint>,
        therapy: List<TherapyEvent>,
        telemetry: List<io.aaps.copilot.domain.predict.TelemetrySignal>,
        settings: IsfCrSettings,
        isfReference: Double
    ): SampleExtractionResult {
        val carbs = extractCarbsGrams(mealEvent) ?: return droppedExtraction(DROP_REASON_CR_MISSING_CARBS)
        if (carbs < 10.0) return droppedExtraction(DROP_REASON_CR_SMALL_CARBS)
        val explicitBolusNearby = therapy.firstOrNull { event ->
            if (event.payload["source"]?.equals("isfcr_inference", ignoreCase = true) == true) {
                return@firstOrNull false
            }
            val units = extractInsulinUnits(event) ?: return@firstOrNull false
            if (units < 0.1) return@firstOrNull false
            val deltaMs = event.ts - mealEvent.ts
            deltaMs in (-20L * MINUTE_MS)..(30L * MINUTE_MS)
        }
        val implicitBolusNearby = if (explicitBolusNearby == null) {
            estimateImplicitBolusFromTelemetry(
                telemetry = telemetry,
                mealTs = mealEvent.ts
            )
        } else {
            null
        }
        val iobContextCount = countIobContextPoints(
            telemetry = telemetry,
            fromTs = mealEvent.ts - 30L * MINUTE_MS,
            toTs = mealEvent.ts + 45L * MINUTE_MS
        )
        val uamTaggedMeal = isUamEngineMealEvent(mealEvent)
        val hasIobContext = iobContextCount >= MIN_IOB_CONTEXT_POINTS
        val allowCarbsOnlyMeal = uamTaggedMeal
        if (
            explicitBolusNearby == null &&
            implicitBolusNearby == null &&
            !hasIobContext &&
            !allowCarbsOnlyMeal
        ) {
            return droppedExtraction(DROP_REASON_CR_NO_BOLUS_NEARBY)
        }
        val bolusUnits = explicitBolusNearby?.let(::extractInsulinUnits)
            ?: implicitBolusNearby?.units
            ?: 0.0
        val bolusSource = when {
            explicitBolusNearby != null -> "explicit_therapy"
            implicitBolusNearby != null -> "implicit_iob"
            uamTaggedMeal -> "carbs_only_uam_tag"
            else -> "carbs_only_iob_context"
        }
        val therapyForFit = if (explicitBolusNearby != null || implicitBolusNearby == null) {
            therapy
        } else {
            therapy + TherapyEvent(
                ts = implicitBolusNearby.ts,
                type = "insulin",
                payload = mapOf(
                    "units" to implicitBolusNearby.units.toString(),
                    "source" to "implicit_iob",
                    "reason" to "isfcr_cr_inference"
                )
            )
        }

        val windowStart = mealEvent.ts
        val windowEnd = mealEvent.ts + 240 * MINUTE_MS
        val points = glucose.filter { it.ts in windowStart..windowEnd }
        if (points.size < 6) return droppedExtraction(DROP_REASON_CR_SPARSE_POINTS)
        val quality = qualityScorer.scoreWindow(points)
        if (quality.maxGapMinutes > settings.crGrossGapMinutes.coerceIn(10.0, 60.0)) {
            return droppedExtraction(DROP_REASON_CR_GROSS_GAP)
        }
        val minQualityScore = if (uamTaggedMeal) 0.08 else 0.20
        if (quality.score < minQualityScore) return droppedExtraction(DROP_REASON_CR_LOW_QUALITY)
        val telemetryStats = evaluateCrTelemetryWindow(
            telemetry = telemetry,
            windowStart = windowStart,
            windowEnd = windowEnd
        )
        if (telemetryStats.sensorBlockedRate >= settings.crSensorBlockedRateThreshold.coerceIn(0.0, 1.0)) {
            return droppedExtraction(DROP_REASON_CR_SENSOR_BLOCKED)
        }
        val ambiguityThreshold = settings.crUamAmbiguityRateThreshold.coerceIn(0.0, 1.0)
        val uamAmbiguityPenalty = if (telemetryStats.uamAmbiguityRate < ambiguityThreshold) {
            1.0
        } else if (uamTaggedMeal) {
            val over = (telemetryStats.uamAmbiguityRate - ambiguityThreshold).coerceIn(0.0, 1.0)
            (1.0 - over * 0.45).coerceIn(0.55, 1.0)
        } else {
            return droppedExtraction(DROP_REASON_CR_UAM_AMBIGUITY)
        }
        val setAgeHours = ageHoursAt(
            ts = mealEvent.ts,
            therapy = therapy,
            markerTypes = SET_CHANGE_TYPES
        )
        val sensorAgeHours = ageHoursAt(
            ts = mealEvent.ts,
            therapy = therapy,
            markerTypes = SENSOR_CHANGE_TYPES
        )
        val setAgeWeight = wearAgeWeight(
            ageHours = setAgeHours,
            stableHours = 24.0,
            rampHours = 120.0,
            minWeight = 0.55
        )
        val sensorAgeWeight = wearAgeWeight(
            ageHours = sensorAgeHours,
            stableHours = 120.0,
            rampHours = 120.0,
            minWeight = 0.65
        )
        val wearWeight = (setAgeWeight * sensorAgeWeight).coerceIn(0.35, 1.0)
        val sourcePenalty = when {
            explicitBolusNearby != null -> 1.0
            implicitBolusNearby != null -> 0.72
            hasIobContext -> 0.55
            uamTaggedMeal -> 0.38
            allowCarbsOnlyMeal -> 0.32
            else -> 0.45
        }

        val minCr = 2.0
        val maxCr = 60.0
        val stepCr = 0.5
        var bestCr = 0.0
        var bestSse = Double.POSITIVE_INFINITY

        val intervals = points.sortedBy { it.ts }
            .zipWithNext()
            .filter { (a, b) ->
                val dt = (b.ts - a.ts) / 60_000.0
                dt in 1.0..15.0
            }
        val minIntervalsRequired = if (uamTaggedMeal || allowCarbsOnlyMeal) 2 else 4
        if (intervals.size < minIntervalsRequired) return droppedExtraction(DROP_REASON_CR_SPARSE_INTERVALS)
        val intervalCoveragePenalty = (intervals.size / 8.0)
            .coerceIn(if (uamTaggedMeal || allowCarbsOnlyMeal) 0.45 else 0.65, 1.0)
        val sampleWeight = (quality.score * wearWeight * sourcePenalty * intervalCoveragePenalty * uamAmbiguityPenalty)
            .coerceIn(0.0, 1.0)

        var cr = minCr
        while (cr <= maxCr + 1e-9) {
            val sse = intervals.sumOf { (a, b) ->
                val dGobs = b.valueMmol - a.valueMmol
                val dGins = therapyDeltaInsulin(
                    therapy = therapyForFit,
                    intervalStartTs = a.ts,
                    intervalEndTs = b.ts,
                    isf = isfReference
                )
                val dGcarb = therapyDeltaCarbs(
                    therapy = therapyForFit,
                    intervalStartTs = a.ts,
                    intervalEndTs = b.ts,
                    csf = (isfReference / cr).coerceIn(0.05, 0.40)
                )
                val residual = dGobs - dGins - dGcarb
                val dt = (b.ts - a.ts) / 60_000.0
                val w = 1.0 / dt.coerceIn(1.0, 15.0)
                w * residual * residual
            }
            if (sse < bestSse) {
                bestSse = sse
                bestCr = cr
            }
            cr += stepCr
        }
        if (bestCr !in minCr..maxCr) return droppedExtraction(DROP_REASON_CR_FIT_INVALID)

        val zoned = Instant.ofEpochMilli(mealEvent.ts).atZone(zoneId)
        val dayType = if (zoned.dayOfWeek.value in setOf(6, 7)) DayType.WEEKEND else DayType.WEEKDAY
        return extractedSample(
            IsfCrEvidenceSample(
            id = "cr:${mealEvent.ts}:${(bestCr * 10).roundToInt()}",
            ts = mealEvent.ts,
            sampleType = IsfCrSampleType.CR,
            hourLocal = zoned.hour,
            dayType = dayType,
            value = bestCr,
            weight = sampleWeight,
            qualityScore = quality.score,
            context = mapOf(
                "mealCarbs" to carbs.toString(),
                "mealBolusUnits" to bolusUnits.toString(),
                "mealBolusSource" to bolusSource,
                "iobContextPoints" to iobContextCount.toString(),
                "mealFromUamEngine" to if (uamTaggedMeal) "1" else "0",
                "fitSse" to bestSse.toString(),
                "sensorBlockedRate" to telemetryStats.sensorBlockedRate.toString(),
                "uamAmbiguityRate" to telemetryStats.uamAmbiguityRate.toString(),
                "setAgeHours" to (setAgeHours ?: -1.0).toString(),
                "sensorAgeHours" to (sensorAgeHours ?: -1.0).toString(),
                "setAgeWeight" to setAgeWeight.toString(),
                "sensorAgeWeight" to sensorAgeWeight.toString()
            ),
            window = mapOf(
                "startTs" to windowStart.toDouble(),
                "endTs" to windowEnd.toDouble(),
                "setAgeHours" to (setAgeHours ?: -1.0),
                "sensorAgeHours" to (sensorAgeHours ?: -1.0)
            )
        )
        )
    }

    private fun extractedSample(sample: IsfCrEvidenceSample): SampleExtractionResult {
        return SampleExtractionResult(sample = sample, dropReason = null)
    }

    private fun droppedExtraction(reason: String): SampleExtractionResult {
        return SampleExtractionResult(sample = null, dropReason = reason)
    }

    private fun therapyDeltaInsulin(
        therapy: List<TherapyEvent>,
        intervalStartTs: Long,
        intervalEndTs: Long,
        isf: Double
    ): Double {
        return therapy.sumOf { event ->
            val units = extractInsulinUnits(event) ?: return@sumOf 0.0
            if (units <= 0.0 || !canCarryInsulin(event)) return@sumOf 0.0
            val age0 = ((intervalStartTs - event.ts) / 60_000.0).coerceAtLeast(0.0)
            val age1 = ((intervalEndTs - event.ts) / 60_000.0).coerceAtLeast(0.0)
            if (age0 > 8 * 60.0) return@sumOf 0.0
            val profile = InsulinActionProfiles.profile(InsulinActionProfileId.NOVORAPID)
            val deltaCum = (profile.cumulativeAt(age1) - profile.cumulativeAt(age0)).coerceAtLeast(0.0)
            -units * isf * deltaCum
        }
    }

    private fun therapyDeltaCarbs(
        therapy: List<TherapyEvent>,
        intervalStartTs: Long,
        intervalEndTs: Long,
        csf: Double
    ): Double {
        return therapy.sumOf { event ->
            val carbs = extractCarbsGrams(event) ?: return@sumOf 0.0
            if (carbs <= 0.0) return@sumOf 0.0
            val age0 = ((intervalStartTs - event.ts) / 60_000.0).coerceAtLeast(0.0)
            val age1 = ((intervalEndTs - event.ts) / 60_000.0).coerceAtLeast(0.0)
            if (age0 > 8 * 60.0) return@sumOf 0.0
            val type = classifyCarbType(event)
            val deltaCum = (CarbAbsorptionProfiles.cumulative(type, age1) -
                CarbAbsorptionProfiles.cumulative(type, age0)).coerceAtLeast(0.0)
            carbs * csf * deltaCum
        }
    }

    private fun classifyCarbType(event: TherapyEvent): CarbAbsorptionType {
        val raw = event.payload["carbType"]
            ?: event.payload["carb_type"]
            ?: event.payload["mealType"]
            ?: return CarbAbsorptionType.MEDIUM
        val normalized = raw.lowercase(Locale.US)
        return when {
            normalized.contains("fast") -> CarbAbsorptionType.FAST
            normalized.contains("slow") || normalized.contains("protein") -> CarbAbsorptionType.PROTEIN_SLOW
            else -> CarbAbsorptionType.MEDIUM
        }
    }

    private fun isCorrectionEvent(event: TherapyEvent): Boolean {
        val type = normalize(event.type)
        if (type == "correction_bolus") return true
        if (type != "bolus" && type != "insulin") return false
        val reason = event.payload["reason"]?.lowercase(Locale.US).orEmpty()
        val flag = event.payload["isCorrection"]?.lowercase(Locale.US).orEmpty()
        return reason.contains("correction") || flag == "true"
    }

    private fun isMealEvent(event: TherapyEvent): Boolean {
        return extractCarbsGrams(event)?.let { it >= 10.0 } == true
    }

    private fun isUamEngineMealEvent(event: TherapyEvent): Boolean {
        val reason = event.payload["reason"]?.lowercase(Locale.US).orEmpty()
        val notes = event.payload["notes"]?.lowercase(Locale.US).orEmpty()
        val source = event.payload["source"]?.lowercase(Locale.US).orEmpty()
        return reason.contains("uam_engine") || source.contains("uam_engine") || notes.contains("uam_engine|")
    }

    private fun uamMealDedupKey(event: TherapyEvent): String? {
        if (!isUamEngineMealEvent(event)) return null
        val carbs = extractCarbsGrams(event) ?: return null
        val bucket = event.ts / (5L * MINUTE_MS)
        val carbsBucket = (carbs * 10.0).roundToInt()
        return "uam:$bucket:$carbsBucket"
    }

    private fun canCarryInsulin(event: TherapyEvent): Boolean {
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

    private fun ageHoursAt(
        ts: Long,
        therapy: List<TherapyEvent>,
        markerTypes: Set<String>
    ): Double? {
        val markerTs = therapy
            .asSequence()
            .filter { it.ts <= ts }
            .filter { markerTypes.contains(normalize(it.type)) }
            .map { it.ts }
            .maxOrNull()
            ?: return null
        val ageMinutes = ((ts - markerTs) / 60_000.0).coerceAtLeast(0.0)
        return ageMinutes / 60.0
    }

    private fun wearAgeWeight(
        ageHours: Double?,
        stableHours: Double,
        rampHours: Double,
        minWeight: Double
    ): Double {
        if (ageHours == null || ageHours <= stableHours) return 1.0
        val progress = ((ageHours - stableHours) / rampHours).coerceIn(0.0, 1.0)
        return (1.0 - progress * (1.0 - minWeight)).coerceIn(minWeight, 1.0)
    }

    private fun payloadDouble(event: TherapyEvent, vararg keys: String): Double? {
        val normalizedPayload = event.payload.entries.associate { normalize(it.key) to it.value }
        return keys.firstNotNullOfOrNull { key ->
            normalizedPayload[normalize(key)]?.replace(",", ".")?.toDoubleOrNull()
        }
    }

    private fun normalize(value: String): String {
        return value
            .replace(Regex("([a-z0-9])([A-Z])"), "$1_$2")
            .lowercase(Locale.US)
            .replace(Regex("[^a-z0-9]+"), "_")
            .trim('_')
    }

    private fun mergeWithImplicitCorrections(
        therapy: List<TherapyEvent>,
        telemetry: List<io.aaps.copilot.domain.predict.TelemetrySignal>
    ): List<TherapyEvent> {
        if (telemetry.isEmpty()) return therapy
        val inferred = inferImplicitCorrectionEvents(therapy = therapy, telemetry = telemetry)
        if (inferred.isEmpty()) return therapy
        return (therapy + inferred).sortedBy { it.ts }
    }

    private fun inferImplicitCorrectionEvents(
        therapy: List<TherapyEvent>,
        telemetry: List<io.aaps.copilot.domain.predict.TelemetrySignal>
    ): List<TherapyEvent> {
        val iobSeries = telemetry.asSequence()
            .mapNotNull { sample ->
                if (!CR_IOB_KEYS.contains(normalize(sample.key))) return@mapNotNull null
                val value = sample.valueDouble ?: sample.valueText?.replace(",", ".")?.toDoubleOrNull()
                val numeric = value ?: return@mapNotNull null
                sample.ts to numeric
            }
            .filter { (_, value) -> value >= -0.1 }
            .groupBy({ it.first }, { it.second })
            .mapNotNull { (ts, values) -> values.maxOrNull()?.let { ts to it } }
            .sortedBy { it.first }
            .toList()
        if (iobSeries.size < 2) return emptyList()

        val existingInsulinTs = therapy.asSequence()
            .filter { (extractInsulinUnits(it) ?: 0.0) >= 0.1 }
            .map { it.ts }
            .toList()
        val inferredByBucket = linkedMapOf<Long, TherapyEvent>()

        iobSeries.zipWithNext().forEach { (a, b) ->
            val dtMin = (b.first - a.first) / 60_000.0
            if (dtMin !in 0.5..IMPLICIT_CORRECTION_MAX_DT_MIN) return@forEach
            val jump = (b.second - a.second)
            if (jump < IMPLICIT_CORRECTION_MIN_UNITS || jump > IMPLICIT_CORRECTION_MAX_UNITS) return@forEach
            val ts = b.first
            val nearExplicit = existingInsulinTs.any { abs(it - ts) <= IMPLICIT_CORRECTION_NEAR_EXPLICIT_MS }
            if (nearExplicit) return@forEach
            val bucket = ts / (IMPLICIT_CORRECTION_BUCKET_MINUTES * MINUTE_MS)
            val previous = inferredByBucket[bucket]
            val previousUnits = previous?.payload?.get("units")?.toDoubleOrNull() ?: 0.0
            if (jump <= previousUnits) return@forEach
            inferredByBucket[bucket] = TherapyEvent(
                ts = ts,
                type = "correction_bolus",
                payload = mapOf(
                    "units" to jump.toString(),
                    "reason" to "implicit_iob_jump",
                    "source" to "isfcr_inference"
                )
            )
        }

        return inferredByBucket.values.toList()
    }

    private data class CrTelemetryWindowStats(
        val sensorBlockedRate: Double,
        val uamAmbiguityRate: Double
    )

    private data class ImplicitBolusEstimate(
        val ts: Long,
        val units: Double
    )

    private fun evaluateCrTelemetryWindow(
        telemetry: List<io.aaps.copilot.domain.predict.TelemetrySignal>,
        windowStart: Long,
        windowEnd: Long
    ): CrTelemetryWindowStats {
        val inWindow = telemetry.asSequence()
            .filter { it.ts in windowStart..windowEnd }
            .toList()
        val sensorBlockedRate = ratioAboveThreshold(
            samples = inWindow,
            normalizedKeys = CR_SENSOR_BLOCKED_KEYS,
            threshold = 0.5
        )
        val uamAmbiguityRate = ratioAboveThreshold(
            samples = inWindow,
            normalizedKeys = CR_UAM_AMBIGUITY_KEYS,
            threshold = 0.5
        )
        return CrTelemetryWindowStats(
            sensorBlockedRate = sensorBlockedRate,
            uamAmbiguityRate = uamAmbiguityRate
        )
    }

    private fun ratioAboveThreshold(
        samples: List<io.aaps.copilot.domain.predict.TelemetrySignal>,
        normalizedKeys: Set<String>,
        threshold: Double
    ): Double {
        var total = 0
        var hit = 0
        samples.forEach { sample ->
            val key = normalize(sample.key)
            if (!normalizedKeys.contains(key)) return@forEach
            val value = sample.valueDouble ?: sample.valueText?.replace(",", ".")?.toDoubleOrNull()
            total += 1
            if ((value ?: 0.0) >= threshold) {
                hit += 1
            }
        }
        if (total == 0) return 0.0
        return hit.toDouble() / total.toDouble()
    }

    private fun estimateImplicitBolusFromTelemetry(
        telemetry: List<io.aaps.copilot.domain.predict.TelemetrySignal>,
        mealTs: Long
    ): ImplicitBolusEstimate? {
        val startTs = mealTs - 30L * MINUTE_MS
        val endTs = mealTs + 45L * MINUTE_MS
        val iobSeries = telemetry.asSequence()
            .filter { it.ts in startTs..endTs }
            .mapNotNull { sample ->
                val key = normalize(sample.key)
                if (!CR_IOB_KEYS.contains(key)) return@mapNotNull null
                val value = sample.valueDouble ?: sample.valueText?.replace(",", ".")?.toDoubleOrNull()
                val numeric = value ?: return@mapNotNull null
                sample.ts to numeric
            }
            .groupBy({ it.first }, { it.second })
            .mapNotNull { (ts, values) ->
                values.maxOrNull()?.let { ts to it }
            }
            .sortedBy { it.first }
            .toList()
        if (iobSeries.size < 2) return null

        var bestJumpUnits = 0.0
        var bestJumpTs = 0L
        iobSeries.zipWithNext().forEach { (a, b) ->
            val dtMin = (b.first - a.first) / 60_000.0
            if (dtMin !in 0.5..15.0) return@forEach
            if (b.first < mealTs - 5L * MINUTE_MS || b.first > endTs) return@forEach
            val jump = b.second - a.second
            if (jump > bestJumpUnits) {
                bestJumpUnits = jump
                bestJumpTs = b.first
            }
        }
        if (bestJumpUnits < IMPLICIT_BOLUS_MIN_UNITS) return null
        return ImplicitBolusEstimate(
            ts = bestJumpTs.takeIf { it > 0L } ?: mealTs,
            units = bestJumpUnits.coerceIn(IMPLICIT_BOLUS_MIN_UNITS, IMPLICIT_BOLUS_MAX_UNITS)
        )
    }

    private fun countIobContextPoints(
        telemetry: List<io.aaps.copilot.domain.predict.TelemetrySignal>,
        fromTs: Long,
        toTs: Long
    ): Int {
        return telemetry.count { sample ->
            sample.ts in fromTs..toTs && CR_IOB_KEYS.contains(normalize(sample.key))
        }
    }

    private fun List<GlucosePoint>.closestTo(targetTs: Long, maxDistanceMs: Long): GlucosePoint? {
        return this.minByOrNull { abs(it.ts - targetTs) }?.takeIf { abs(it.ts - targetTs) <= maxDistanceMs }
    }

    private companion object {
        private const val MINUTE_MS = 60_000L
        private val SET_CHANGE_TYPES = setOf(
            "infusion_set_change",
            "site_change",
            "set_change",
            "cannula_change"
        )
        private val SENSOR_CHANGE_TYPES = setOf(
            "sensor_change",
            "cgm_sensor_change",
            "sensor_start",
            "sensor_started"
        )
        private const val DROP_REASON_UNKNOWN = "unknown"
        private const val DROP_REASON_ISF_MISSING_UNITS = "isf_missing_units"
        private const val DROP_REASON_ISF_SMALL_UNITS = "isf_small_units"
        private const val DROP_REASON_ISF_CARBS_AROUND = "isf_carbs_around"
        private const val DROP_REASON_ISF_MISSING_BASELINE = "isf_missing_baseline"
        private const val DROP_REASON_ISF_MISSING_FUTURE = "isf_missing_future"
        private const val DROP_REASON_ISF_NON_POSITIVE_DROP = "isf_non_positive_drop"
        private const val DROP_REASON_ISF_OUT_OF_RANGE = "isf_out_of_range"
        private const val DROP_REASON_ISF_LOW_QUALITY = "isf_low_quality"
        private const val DROP_REASON_CR_MISSING_CARBS = "cr_missing_carbs"
        private const val DROP_REASON_CR_SMALL_CARBS = "cr_small_carbs"
        private const val DROP_REASON_CR_NO_BOLUS_NEARBY = "cr_no_bolus_nearby"
        private const val DROP_REASON_CR_SPARSE_POINTS = "cr_sparse_points"
        private const val DROP_REASON_CR_LOW_QUALITY = "cr_low_quality"
        private const val DROP_REASON_CR_SPARSE_INTERVALS = "cr_sparse_intervals"
        private const val DROP_REASON_CR_FIT_INVALID = "cr_fit_invalid"
        private const val DROP_REASON_CR_SENSOR_BLOCKED = "cr_sensor_blocked"
        private const val DROP_REASON_CR_UAM_AMBIGUITY = "cr_uam_ambiguity"
        private const val DROP_REASON_CR_GROSS_GAP = "cr_gross_gap"
        private const val IMPLICIT_BOLUS_MIN_UNITS = 0.15
        private const val IMPLICIT_BOLUS_MAX_UNITS = 8.0
        private const val MIN_IOB_CONTEXT_POINTS = 3
        private const val IMPLICIT_CORRECTION_MIN_UNITS = 0.2
        private const val IMPLICIT_CORRECTION_MAX_UNITS = 8.0
        private const val IMPLICIT_CORRECTION_MAX_DT_MIN = 20.0
        private const val IMPLICIT_CORRECTION_BUCKET_MINUTES = 15L
        private const val IMPLICIT_CORRECTION_NEAR_EXPLICIT_MS = 15L * MINUTE_MS
        private val CR_IOB_KEYS = setOf(
            "iob",
            "iob_units",
            "iob_effective_units",
            "raw_iob",
            "raw_iob_units",
            "raw_iob_iob",
            "openaps_iob",
            "openaps_iob_iob",
            "openaps_iob_basaliob",
            "openaps_iob_activity",
            "iob_iob",
            "iob_basaliob"
        )
        private val CR_SENSOR_BLOCKED_KEYS = setOf(
            "sensor_quality_blocked",
            "sensor_blocked",
            "sensor_quality_suspect_false_low"
        )
        private val CR_UAM_AMBIGUITY_KEYS = setOf(
            "uam_value",
            "uam_calculated_flag",
            "uam_inferred_flag",
            "uam_active",
            "uam_detected",
            "has_uam",
            "is_uam"
        )
    }
}
