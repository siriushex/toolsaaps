package io.aaps.copilot.domain.isfcr

import io.aaps.copilot.domain.model.DayType
import io.aaps.copilot.domain.model.GlucosePoint
import io.aaps.copilot.domain.model.TherapyEvent
import io.aaps.copilot.domain.predict.CarbAbsorptionProfiles
import io.aaps.copilot.domain.predict.CarbAbsorptionType
import io.aaps.copilot.domain.predict.InsulinActionProfileId
import io.aaps.copilot.domain.predict.InsulinActionProfiles
import io.aaps.copilot.domain.predict.isSyntheticUamCarbEvent
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

    private data class CrFitInterval(
        val observedDelta: Double,
        val insulinDelta: Double,
        val carbBaseDelta: Double,
        val weight: Double
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
        val correctionCandidates = therapyWithImplicitCorrections.filter(::isCorrectionEvent)
        val mealCandidates = therapyWithImplicitCorrections.filter(::isMealEvent)
        var dropped = 0
        val droppedReasonCounts = linkedMapOf<String, Int>()

        fun markDropped(reason: String?) {
            dropped += 1
            val normalizedReason = reason?.takeIf { it.isNotBlank() } ?: DROP_REASON_UNKNOWN
            droppedReasonCounts[normalizedReason] = (droppedReasonCounts[normalizedReason] ?: 0) + 1
        }

        correctionCandidates.forEach { event ->
            val sample = extractIsfSample(
                event = event,
                glucose = glucose,
                therapy = therapyWithImplicitCorrections,
                settings = settings,
                isfReference = isfReference
            )
            sample.sample?.let { isfSamples += it } ?: markDropped(sample.dropReason)
        }

        mealCandidates.forEach { event ->
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
        settings: IsfCrSettings,
        isfReference: Double
    ): SampleExtractionResult {
        val units = extractInsulinUnits(event)
            ?: return droppedExtraction(DROP_REASON_ISF_MISSING_UNITS)
        if (units < 0.1) return droppedExtraction(DROP_REASON_ISF_SMALL_UNITS)

        val carbsAround = therapy
            .eventsInRange(event.ts - 60 * MINUTE_MS, event.ts + 60 * MINUTE_MS)
            .asSequence()
            .filterNot(::isSyntheticUamCarbEvent)
            .mapNotNull(::extractCarbsGrams)
            .sum()
        if (carbsAround > 5.0) return droppedExtraction(DROP_REASON_ISF_CARBS_AROUND)

        val baseline = glucose.closestTo(
            targetTs = event.ts,
            maxDistanceMs = 20 * MINUTE_MS
        ) ?: return droppedExtraction(DROP_REASON_ISF_MISSING_BASELINE)

        val windowStart = event.ts + 60 * MINUTE_MS
        val windowEnd = event.ts + 240 * MINUTE_MS
        val future = glucose.pointsInRange(windowStart, windowEnd)
        if (future.isEmpty()) return droppedExtraction(DROP_REASON_ISF_MISSING_FUTURE)
        val minFuture = future.minByOrNull { it.valueMmol } ?: return droppedExtraction(DROP_REASON_ISF_MISSING_FUTURE)
        val drop = baseline.valueMmol - minFuture.valueMmol
        if (drop <= 0.0) return droppedExtraction(DROP_REASON_ISF_NON_POSITIVE_DROP)

        val rawIsf = (drop / units).takeIf { it in 0.2..18.0 } ?: return droppedExtraction(DROP_REASON_ISF_OUT_OF_RANGE)
        val qualityPoints = glucose.pointsInRange(event.ts - 15 * MINUTE_MS, windowEnd)
        val quality = qualityScorer.scoreWindow(qualityPoints)
        val onsetTs = detectSlopeOnsetTs(
            anchorTs = event.ts,
            points = qualityPoints,
            direction = SlopeDirection.DROP,
            slopeThresholdPer5m = ISF_ONSET_DROP_SLOPE_THRESHOLD_PER_5M,
            minConsecutive = ISF_ONSET_MIN_CONSECUTIVE,
            minDelayMinutes = ISF_ONSET_MIN_DELAY_MINUTES,
            maxDelayMinutes = ISF_ONSET_MAX_DELAY_MINUTES
        )
        val onsetMinutes = onsetTs?.let { (it - event.ts) / 60_000.0 }
        val peakDropSlopePer5m = slopeExtremumPer5m(
            anchorTs = event.ts,
            points = qualityPoints,
            minDelayMinutes = ISF_PEAK_WINDOW_MIN_DELAY_MINUTES,
            maxDelayMinutes = ISF_PEAK_WINDOW_MAX_DELAY_MINUTES,
            preferDrop = true
        )
        val outlierRatio = if (isfReference > 0.0) rawIsf / isfReference else 1.0
        val outlier = outlierRatio < ISF_OUTLIER_MIN_RATIO || outlierRatio > ISF_OUTLIER_MAX_RATIO
        val adjustedIsf = if (!outlier || isfReference <= 0.0) {
            rawIsf
        } else {
            val alpha = (quality.score * ISF_OUTLIER_BLEND_ALPHA_BASE).coerceIn(0.15, ISF_OUTLIER_BLEND_ALPHA_BASE)
            val blended = isfReference * (1.0 - alpha) + rawIsf * alpha
            blended.coerceIn(isfReference * ISF_OUTLIER_MIN_RATIO, isfReference * ISF_OUTLIER_MAX_RATIO)
        }
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
        val onsetPenalty = if (onsetMinutes == null) ISF_NO_ONSET_WEIGHT_PENALTY else 1.0
        val outlierPenalty = if (outlier) ISF_OUTLIER_WEIGHT_PENALTY else 1.0
        val weight = (quality.score * wearWeight * onsetPenalty * outlierPenalty).coerceIn(0.0, 1.0)
        if (weight <= 0.0) return droppedExtraction(DROP_REASON_ISF_LOW_QUALITY)

        val zoned = Instant.ofEpochMilli(event.ts).atZone(zoneId)
        val dayType = if (zoned.dayOfWeek.value in setOf(6, 7)) DayType.WEEKEND else DayType.WEEKDAY
        return extractedSample(
            IsfCrEvidenceSample(
            id = "isf:${event.ts}:${(adjustedIsf * 100).roundToInt()}",
            ts = event.ts,
            sampleType = IsfCrSampleType.ISF,
            hourLocal = zoned.hour,
            dayType = dayType,
            value = adjustedIsf,
            weight = weight,
            qualityScore = quality.score,
            context = mapOf(
                "units" to units.toString(),
                "drop" to drop.toString(),
                "rawIsf" to rawIsf.toString(),
                "isfOutlier" to if (outlier) "1" else "0",
                "isfOutlierRatio" to outlierRatio.toString(),
                "isfAdjustedByReference" to if (outlier) "1" else "0",
                "insulinOnsetMin" to (onsetMinutes?.toString() ?: "nan"),
                "peakDropSlopePer5m" to (peakDropSlopePer5m?.toString() ?: "nan"),
                "carbsAround" to carbsAround.toString(),
                "setAgeHours" to (setAgeHours ?: -1.0).toString(),
                "sensorAgeHours" to (sensorAgeHours ?: -1.0).toString(),
                "setAgeWeight" to setAgeWeight.toString(),
                "sensorAgeWeight" to sensorAgeWeight.toString(),
                "onsetPenalty" to onsetPenalty.toString(),
                "outlierPenalty" to outlierPenalty.toString()
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
        val alignedMealTs = alignMealTimestampForCr(
            mealTs = mealEvent.ts,
            glucose = glucose
        )
        val effectiveMealTs = alignedMealTs ?: mealEvent.ts
        val mealAlignmentShiftMinutes = (effectiveMealTs - mealEvent.ts) / 60_000.0

        val explicitBolusNearby = therapy.firstOrNull { event ->
            if (event.payload["source"]?.equals("isfcr_inference", ignoreCase = true) == true) {
                return@firstOrNull false
            }
            val units = extractInsulinUnits(event) ?: return@firstOrNull false
            if (units < 0.1) return@firstOrNull false
            val deltaMs = event.ts - effectiveMealTs
            deltaMs in (-20L * MINUTE_MS)..(30L * MINUTE_MS)
        }
        val implicitBolusNearby = if (explicitBolusNearby == null) {
            estimateImplicitBolusFromTelemetry(
                telemetry = telemetry,
                mealTs = effectiveMealTs
            )
        } else {
            null
        }
        val iobContextCount = countIobContextPoints(
            telemetry = telemetry,
            fromTs = effectiveMealTs - 30L * MINUTE_MS,
            toTs = effectiveMealTs + 45L * MINUTE_MS
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

        val windowStart = effectiveMealTs
        val windowEnd = effectiveMealTs + 240 * MINUTE_MS
        val points = glucose.pointsInRange(windowStart, windowEnd)
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

        val intervals = points
            .zipWithNext()
            .filter { (a, b) ->
                val dt = (b.ts - a.ts) / 60_000.0
                dt in 1.0..15.0
            }
        val minIntervalsRequired = if (uamTaggedMeal || allowCarbsOnlyMeal) 2 else 4
        if (intervals.size < minIntervalsRequired) return droppedExtraction(DROP_REASON_CR_SPARSE_INTERVALS)
        val intervalCoveragePenalty = (intervals.size / 8.0)
            .coerceIn(if (uamTaggedMeal || allowCarbsOnlyMeal) 0.45 else 0.65, 1.0)
        val alignmentPenalty = when {
            abs(mealAlignmentShiftMinutes) >= 24.0 -> 0.85
            abs(mealAlignmentShiftMinutes) >= 12.0 -> 0.92
            abs(mealAlignmentShiftMinutes) >= 5.0 -> 0.97
            else -> 1.0
        }
        val sampleWeight = (quality.score * wearWeight * sourcePenalty * intervalCoveragePenalty * uamAmbiguityPenalty)
            .times(alignmentPenalty)
            .coerceIn(0.0, 1.0)
        val insulinProfile = InsulinActionProfiles.profile(InsulinActionProfileId.NOVORAPID)
        val fitIntervals = intervals.map { (a, b) ->
            val dt = (b.ts - a.ts) / 60_000.0
            CrFitInterval(
                observedDelta = b.valueMmol - a.valueMmol,
                insulinDelta = therapyDeltaInsulinBase(
                    therapy = therapyForFit,
                    intervalStartTs = a.ts,
                    intervalEndTs = b.ts,
                    isf = isfReference,
                    profile = insulinProfile
                ),
                carbBaseDelta = therapyDeltaCarbBase(
                    therapy = therapyForFit,
                    intervalStartTs = a.ts,
                    intervalEndTs = b.ts
                ),
                weight = 1.0 / dt.coerceIn(1.0, 15.0)
            )
        }

        var cr = minCr
        while (cr <= maxCr + 1e-9) {
            val csf = (isfReference / cr).coerceIn(0.05, 0.40)
            val sse = fitIntervals.sumOf { interval ->
                val residual = interval.observedDelta - interval.insulinDelta - (interval.carbBaseDelta * csf)
                interval.weight * residual * residual
            }
            if (sse < bestSse) {
                bestSse = sse
                bestCr = cr
            }
            cr += stepCr
        }
        if (bestCr !in minCr..maxCr) return droppedExtraction(DROP_REASON_CR_FIT_INVALID)

        val zoned = Instant.ofEpochMilli(effectiveMealTs).atZone(zoneId)
        val dayType = if (zoned.dayOfWeek.value in setOf(6, 7)) DayType.WEEKEND else DayType.WEEKDAY
        return extractedSample(
            IsfCrEvidenceSample(
            id = "cr:${mealEvent.ts}:${(bestCr * 10).roundToInt()}",
            ts = effectiveMealTs,
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
                "mealAligned" to if (alignedMealTs != null) "1" else "0",
                "mealAlignmentShiftMin" to mealAlignmentShiftMinutes.toString(),
                "mealOriginalTs" to mealEvent.ts.toString(),
                "mealEffectiveTs" to effectiveMealTs.toString(),
                "fitSse" to bestSse.toString(),
                "sensorBlockedRate" to telemetryStats.sensorBlockedRate.toString(),
                "uamAmbiguityRate" to telemetryStats.uamAmbiguityRate.toString(),
                "setAgeHours" to (setAgeHours ?: -1.0).toString(),
                "sensorAgeHours" to (sensorAgeHours ?: -1.0).toString(),
                "setAgeWeight" to setAgeWeight.toString(),
                "sensorAgeWeight" to sensorAgeWeight.toString(),
                "alignmentPenalty" to alignmentPenalty.toString()
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

    private fun therapyDeltaInsulinBase(
        therapy: List<TherapyEvent>,
        intervalStartTs: Long,
        intervalEndTs: Long,
        isf: Double,
        profile: io.aaps.copilot.domain.predict.InsulinActionProfile
    ): Double {
        return therapy.sumOf { event ->
            val units = extractInsulinUnits(event) ?: return@sumOf 0.0
            if (units <= 0.0 || !canCarryInsulin(event)) return@sumOf 0.0
            val age0 = ((intervalStartTs - event.ts) / 60_000.0).coerceAtLeast(0.0)
            val age1 = ((intervalEndTs - event.ts) / 60_000.0).coerceAtLeast(0.0)
            if (age0 > 8 * 60.0) return@sumOf 0.0
            val deltaCum = (profile.cumulativeAt(age1) - profile.cumulativeAt(age0)).coerceAtLeast(0.0)
            -units * isf * deltaCum
        }
    }

    private fun therapyDeltaCarbBase(
        therapy: List<TherapyEvent>,
        intervalStartTs: Long,
        intervalEndTs: Long
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
            carbs * deltaCum
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
        return isSyntheticUamCarbEvent(event)
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
        var markerTs: Long? = null
        for (index in therapy.indices.reversed()) {
            val event = therapy[index]
            if (event.ts > ts) continue
            if (markerTypes.contains(normalize(event.type))) {
                markerTs = event.ts
                break
            }
        }
        val resolvedMarkerTs = markerTs ?: return null
        val ageMinutes = ((ts - resolvedMarkerTs) / 60_000.0).coerceAtLeast(0.0)
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
        val normalizedKeys = keys.asSequence()
            .map(::normalize)
            .toSet()
        event.payload.entries.forEach { (rawKey, rawValue) ->
            if (normalizedKeys.contains(normalize(rawKey))) {
                return rawValue.replace(",", ".").toDoubleOrNull()
            }
        }
        return null
    }

    private fun normalize(value: String): String {
        return value
            .replace(CAMEL_BOUNDARY_REGEX, "$1_$2")
            .lowercase(Locale.US)
            .replace(NON_ALNUM_REGEX, "_")
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
        if (isEmpty()) return null
        val insertion = lowerBoundGlucose(targetTs)
        val candidates = mutableListOf<GlucosePoint>()
        this.getOrNull(insertion - 1)?.let { candidates += it }
        this.getOrNull(insertion)?.let { candidates += it }
        return candidates
            .minByOrNull { abs(it.ts - targetTs) }
            ?.takeIf { abs(it.ts - targetTs) <= maxDistanceMs }
    }

    private fun alignMealTimestampForCr(
        mealTs: Long,
        glucose: List<GlucosePoint>
    ): Long? {
        val windowStart = mealTs - (CR_MEAL_ALIGN_SEARCH_MINUTES * MINUTE_MS)
        val windowEnd = mealTs + (CR_MEAL_ALIGN_SEARCH_MINUTES * MINUTE_MS)
        val points = glucose.pointsInRange(windowStart, windowEnd)
        if (points.size < CR_MEAL_ALIGN_MIN_POINTS) return null
        val onsetTs = detectSlopeOnsetTs(
            anchorTs = mealTs,
            points = points,
            direction = SlopeDirection.RISE,
            slopeThresholdPer5m = CR_MEAL_ALIGN_RISE_SLOPE_THRESHOLD_PER_5M,
            minConsecutive = CR_MEAL_ALIGN_MIN_CONSECUTIVE,
            minDelayMinutes = -CR_MEAL_ALIGN_MAX_SHIFT_MINUTES,
            maxDelayMinutes = CR_MEAL_ALIGN_MAX_SHIFT_MINUTES
        ) ?: return null
        val shiftMin = abs((onsetTs - mealTs) / 60_000.0)
        if (shiftMin > CR_MEAL_ALIGN_MAX_SHIFT_MINUTES + 1e-6) return null
        return onsetTs
    }

    private enum class SlopeDirection {
        RISE,
        DROP
    }

    private data class SlopeSegment(
        val startTs: Long,
        val endTs: Long,
        val slopePer5m: Double
    )

    private fun detectSlopeOnsetTs(
        anchorTs: Long,
        points: List<GlucosePoint>,
        direction: SlopeDirection,
        slopeThresholdPer5m: Double,
        minConsecutive: Int,
        minDelayMinutes: Double,
        maxDelayMinutes: Double
    ): Long? {
        val segments = buildSlopeSegments(points)
        if (segments.isEmpty()) return null
        var consecutive = 0
        for (index in segments.indices) {
            val segment = segments[index]
            val matches = when (direction) {
                SlopeDirection.RISE -> segment.slopePer5m >= slopeThresholdPer5m
                SlopeDirection.DROP -> segment.slopePer5m <= -slopeThresholdPer5m
            }
            if (matches) {
                consecutive += 1
                if (consecutive >= minConsecutive) {
                    val onset = segments[index - minConsecutive + 1].startTs
                    val delayMin = (onset - anchorTs) / 60_000.0
                    if (delayMin in minDelayMinutes..maxDelayMinutes) {
                        return onset
                    }
                }
            } else {
                consecutive = 0
            }
        }
        return null
    }

    private fun slopeExtremumPer5m(
        anchorTs: Long,
        points: List<GlucosePoint>,
        minDelayMinutes: Double,
        maxDelayMinutes: Double,
        preferDrop: Boolean
    ): Double? {
        val segments = buildSlopeSegments(points)
        if (segments.isEmpty()) return null
        val inRange = segments.filter { segment ->
            val delayMin = (segment.endTs - anchorTs) / 60_000.0
            delayMin in minDelayMinutes..maxDelayMinutes
        }
        if (inRange.isEmpty()) return null
        return if (preferDrop) {
            inRange.minOfOrNull { it.slopePer5m }
        } else {
            inRange.maxOfOrNull { it.slopePer5m }
        }
    }

    private fun buildSlopeSegments(points: List<GlucosePoint>): List<SlopeSegment> {
        return points
            .zipWithNext()
            .mapNotNull { (a, b) ->
                val dtMin = (b.ts - a.ts) / 60_000.0
                if (dtMin !in 2.0..15.0) return@mapNotNull null
                val slopePer5m = (b.valueMmol - a.valueMmol) / (dtMin / 5.0)
                SlopeSegment(
                    startTs = a.ts,
                    endTs = b.ts,
                    slopePer5m = slopePer5m
                )
            }
    }

    private fun List<GlucosePoint>.pointsInRange(startTs: Long, endTs: Long): List<GlucosePoint> {
        if (isEmpty() || startTs > endTs) return emptyList()
        val fromIndex = lowerBoundGlucose(startTs)
        val toIndex = upperBoundGlucose(endTs)
        if (fromIndex >= toIndex) return emptyList()
        return subList(fromIndex, toIndex)
    }

    private fun List<TherapyEvent>.eventsInRange(startTs: Long, endTs: Long): List<TherapyEvent> {
        if (isEmpty() || startTs > endTs) return emptyList()
        val fromIndex = lowerBoundTherapy(startTs)
        val toIndex = upperBoundTherapy(endTs)
        if (fromIndex >= toIndex) return emptyList()
        return subList(fromIndex, toIndex)
    }

    private fun List<GlucosePoint>.lowerBoundGlucose(targetTs: Long): Int {
        var low = 0
        var high = size
        while (low < high) {
            val mid = (low + high) ushr 1
            if (this[mid].ts < targetTs) {
                low = mid + 1
            } else {
                high = mid
            }
        }
        return low
    }

    private fun List<GlucosePoint>.upperBoundGlucose(targetTs: Long): Int {
        var low = 0
        var high = size
        while (low < high) {
            val mid = (low + high) ushr 1
            if (this[mid].ts <= targetTs) {
                low = mid + 1
            } else {
                high = mid
            }
        }
        return low
    }

    private fun List<TherapyEvent>.lowerBoundTherapy(targetTs: Long): Int {
        var low = 0
        var high = size
        while (low < high) {
            val mid = (low + high) ushr 1
            if (this[mid].ts < targetTs) {
                low = mid + 1
            } else {
                high = mid
            }
        }
        return low
    }

    private fun List<TherapyEvent>.upperBoundTherapy(targetTs: Long): Int {
        var low = 0
        var high = size
        while (low < high) {
            val mid = (low + high) ushr 1
            if (this[mid].ts <= targetTs) {
                low = mid + 1
            } else {
                high = mid
            }
        }
        return low
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
        private const val ISF_OUTLIER_MIN_RATIO = 0.5
        private const val ISF_OUTLIER_MAX_RATIO = 2.0
        private const val ISF_OUTLIER_BLEND_ALPHA_BASE = 0.55
        private const val ISF_OUTLIER_WEIGHT_PENALTY = 0.65
        private const val ISF_NO_ONSET_WEIGHT_PENALTY = 0.90
        private const val ISF_ONSET_DROP_SLOPE_THRESHOLD_PER_5M = 0.10
        private const val ISF_ONSET_MIN_CONSECUTIVE = 2
        private const val ISF_ONSET_MIN_DELAY_MINUTES = 10.0
        private const val ISF_ONSET_MAX_DELAY_MINUTES = 120.0
        private const val ISF_PEAK_WINDOW_MIN_DELAY_MINUTES = 20.0
        private const val ISF_PEAK_WINDOW_MAX_DELAY_MINUTES = 240.0
        private const val CR_MEAL_ALIGN_MIN_POINTS = 6
        private const val CR_MEAL_ALIGN_SEARCH_MINUTES = 30L
        private const val CR_MEAL_ALIGN_MAX_SHIFT_MINUTES = 30.0
        private const val CR_MEAL_ALIGN_MIN_CONSECUTIVE = 2
        private const val CR_MEAL_ALIGN_RISE_SLOPE_THRESHOLD_PER_5M = 0.10
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
        private val CAMEL_BOUNDARY_REGEX = Regex("([a-z0-9])([A-Z])")
        private val NON_ALNUM_REGEX = Regex("[^a-z0-9]+")
    }
}
