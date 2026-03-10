package io.aaps.copilot.domain.isfcr

import io.aaps.copilot.domain.model.DayType
import io.aaps.copilot.domain.model.GlucosePoint
import io.aaps.copilot.domain.model.TherapyEvent
import io.aaps.copilot.domain.predict.TelemetrySignal
import java.time.Instant
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.min

class IsfCrEngine(
    private val zoneId: ZoneId = ZoneId.systemDefault(),
    private val extractor: IsfCrWindowExtractor = IsfCrWindowExtractor(zoneId = zoneId),
    private val baseFitter: IsfCrBaseFitter = IsfCrBaseFitter(),
    private val contextModel: IsfCrContextModel = IsfCrContextModel(zoneId),
    private val confidenceModel: IsfCrConfidenceModel = IsfCrConfidenceModel(),
    private val fallbackResolver: IsfCrFallbackResolver = IsfCrFallbackResolver()
) {

    data class FitResult(
        val state: IsfCrModelState,
        val evidence: List<IsfCrEvidenceSample>,
        val droppedEvidenceCount: Int,
        val droppedReasonCounts: Map<String, Int>
    )

    data class RealtimeResult(
        val snapshot: IsfCrRealtimeSnapshot,
        val diagnostics: IsfCrDiagnostics,
        val evidence: List<IsfCrEvidenceSample>
    )

    fun fitBaseModel(
        history: IsfCrHistoryBundle,
        settings: IsfCrSettings,
        existingState: IsfCrModelState?,
        fallbackIsf: Double,
        fallbackCr: Double
    ): FitResult {
        val referenceIsf = existingState?.hourlyIsf?.filterNotNull()?.average()?.takeIf { it > 0.0 } ?: fallbackIsf
        val extraction = extractor.extract(history = history, settings = settings, isfReference = referenceIsf)
        val fit = baseFitter.fit(
            evidence = extraction.evidence,
            defaults = fallbackIsf to fallbackCr
        )
        val params = mutableMapOf(
            "lookbackDays" to settings.lookbackDays.toDouble(),
            "confidenceThreshold" to settings.confidenceThreshold
        )
        params += encodeDayTypeHourlyParams(prefix = "weekday_isf", values = fit.weekdayHourlyIsf)
        params += encodeDayTypeHourlyParams(prefix = "weekend_isf", values = fit.weekendHourlyIsf)
        params += encodeDayTypeHourlyParams(prefix = "weekday_cr", values = fit.weekdayHourlyCr)
        params += encodeDayTypeHourlyParams(prefix = "weekend_cr", values = fit.weekendHourlyCr)
        val state = IsfCrModelState(
            updatedAt = System.currentTimeMillis(),
            hourlyIsf = fit.hourlyIsf,
            hourlyCr = fit.hourlyCr,
            params = params,
            fitMetrics = fit.metrics + mapOf(
                "droppedEvidenceCount" to extraction.droppedCount.toDouble()
            )
        )
        return FitResult(
            state = state,
            evidence = extraction.evidence,
            droppedEvidenceCount = extraction.droppedCount,
            droppedReasonCounts = extraction.droppedReasonCounts
        )
    }

    fun computeRealtime(
        nowTs: Long,
        glucose: List<GlucosePoint>,
        therapy: List<TherapyEvent>,
        telemetry: List<TelemetrySignal>,
        tags: List<PhysioContextTag>,
        activeModel: IsfCrModelState?,
        previousSnapshot: IsfCrRealtimeSnapshot?,
        settings: IsfCrSettings,
        fallbackIsf: Double,
        fallbackCr: Double
    ): RealtimeResult {
        val nowZoned = Instant.ofEpochMilli(nowTs).atZone(zoneId)
        val localHour = nowZoned.hour
        val currentDayType = if (nowZoned.dayOfWeek.value in setOf(6, 7)) {
            DayType.WEEKEND
        } else {
            DayType.WEEKDAY
        }
        val dayTypeIsfBase = resolveDayTypeHourlyBase(
            state = activeModel,
            dayType = currentDayType,
            hour = localHour,
            metric = "isf"
        )
        val dayTypeCrBase = resolveDayTypeHourlyBase(
            state = activeModel,
            dayType = currentDayType,
            hour = localHour,
            metric = "cr"
        )
        val stateIsfBase = activeModel?.hourlyIsf?.getOrNull(localHour)
        val stateCrBase = activeModel?.hourlyCr?.getOrNull(localHour)
        val isfBaseSource = when {
            dayTypeIsfBase != null -> "day_type"
            stateIsfBase != null -> "hourly"
            else -> "fallback"
        }
        val crBaseSource = when {
            dayTypeCrBase != null -> "day_type"
            stateCrBase != null -> "hourly"
            else -> "fallback"
        }
        val baseIsf = (dayTypeIsfBase ?: stateIsfBase ?: fallbackIsf).coerceIn(0.8, 18.0)
        val baseCr = (dayTypeCrBase ?: stateCrBase ?: fallbackCr).coerceIn(2.0, 60.0)

        val extraction = extractor.extract(
            history = IsfCrHistoryBundle(
                glucose = glucose,
                therapy = therapy,
                telemetry = telemetry,
                tags = tags,
                zoneId = zoneId
            ),
            settings = settings,
            isfReference = baseIsf
        )
        val evidence = extraction.evidence
        val isfEvidence = evidence.filter { it.sampleType == IsfCrSampleType.ISF }
        val crEvidence = evidence.filter { it.sampleType == IsfCrSampleType.CR }
        val minIsfEvidencePerHour = settings.minIsfEvidencePerHour.coerceAtLeast(0)
        val minCrEvidencePerHour = settings.minCrEvidencePerHour.coerceAtLeast(0)
        val isfHourWindowSamples = isfEvidence.filter { sample ->
            minHourDistance(localHour, sample.hourLocal) <= 1
        }
        val crHourWindowSamples = crEvidence.filter { sample ->
            minHourDistance(localHour, sample.hourLocal) <= 1
        }
        val isfHourWindowEvidenceCount = isfHourWindowSamples.size
        val crHourWindowEvidenceCount = crHourWindowSamples.size
        val isfHourWindowSameDayTypeCount = isfHourWindowSamples.count { it.dayType == currentDayType }
        val crHourWindowSameDayTypeCount = crHourWindowSamples.count { it.dayType == currentDayType }
        val isfHourEvidenceEnough = isfHourWindowEvidenceCount >= minIsfEvidencePerHour
        val crHourEvidenceEnough = crHourWindowEvidenceCount >= minCrEvidencePerHour
        val isfStrongGlobalEvidence = hasStrongGlobalEvidence(
            totalEvidenceCount = isfEvidence.size,
            minEvidencePerHour = minIsfEvidencePerHour,
            absoluteMin = 2
        )
        val crStrongGlobalEvidence = hasStrongGlobalEvidence(
            totalEvidenceCount = crEvidence.size,
            minEvidencePerHour = minCrEvidencePerHour,
            absoluteMin = 4
        )
        val isfDayTypeEvidenceSparse = isfHourWindowEvidenceCount > 0 && isfHourWindowSameDayTypeCount == 0
        val crDayTypeEvidenceSparse = crHourWindowEvidenceCount > 0 && crHourWindowSameDayTypeCount == 0

        val recentIsf = weightedRecentValue(
            nowTs = nowTs,
            currentHour = localHour,
            currentDayType = currentDayType,
            samples = isfEvidence
        )
        val recentCr = weightedRecentValue(
            nowTs = nowTs,
            currentHour = localHour,
            currentDayType = currentDayType,
            samples = crEvidence
        )
        val blendedIsfBase = blendBaseWithEvidence(
            base = baseIsf,
            evidence = recentIsf,
            evidenceCount = isfEvidence.size,
            minAlpha = if (isfHourEvidenceEnough) 0.07 else 0.03,
            maxAlpha = if (isfHourEvidenceEnough) 0.55 else 0.18
        )
            .coerceIn(0.8, 18.0)
        val blendedCrBase = blendBaseWithEvidence(
            base = baseCr,
            evidence = recentCr,
            evidenceCount = crEvidence.size,
            minAlpha = if (crHourEvidenceEnough) 0.10 else 0.05,
            maxAlpha = if (crHourEvidenceEnough) 0.55 else 0.25
        )
            .coerceIn(2.0, 60.0)

        val context = contextModel.apply(
            nowTs = nowTs,
            isfBase = blendedIsfBase,
            crBase = blendedCrBase,
            therapy = therapy,
            telemetry = telemetry,
            tags = tags,
            previous = previousSnapshot,
            settings = settings
        )
        val latestTelemetry = telemetry
            .groupBy { normalizeTelemetryKey(it.key) }
            .mapValues { (_, rows) -> rows.maxByOrNull { it.ts }?.valueDouble }
        val sensorSuspectFalseLowFlag = (latestTelemetry["sensor_quality_suspect_false_low"] ?: 0.0)
            .coerceIn(0.0, 1.0)
        val sensorQualityScore = (latestTelemetry["sensor_quality_score"] ?: 1.0)
            .coerceIn(0.0, 1.0)
        val confidence = confidenceModel.evaluate(
            isfEff = context.isfEff,
            crEff = context.crEff,
            evidence = evidence,
            telemetry = telemetry,
            factors = context.factors
        )
        var adjustedConfidence = confidence.confidence
        var adjustedQuality = confidence.qualityScore
        var ciIsfLow = confidence.ciIsfLow
        var ciIsfHigh = confidence.ciIsfHigh
        var ciCrLow = confidence.ciCrLow
        var ciCrHigh = confidence.ciCrHigh
        if (!isfHourEvidenceEnough) {
            if (!isfStrongGlobalEvidence) {
                adjustedConfidence = min(
                    adjustedConfidence,
                    (settings.confidenceThreshold - 0.01).coerceAtLeast(0.0)
                )
            }
            adjustedQuality = (adjustedQuality * if (isfStrongGlobalEvidence) 0.96 else 0.92).coerceIn(0.0, 1.0)
            val bounds = inflateCiBounds(
                center = context.isfEff,
                low = ciIsfLow,
                high = ciIsfHigh,
                factor = if (isfStrongGlobalEvidence) 1.12 else 1.18,
                minBound = 0.8,
                maxBound = 18.0
            )
            ciIsfLow = bounds.first
            ciIsfHigh = bounds.second
        }
        if (!crHourEvidenceEnough) {
            if (!crStrongGlobalEvidence) {
                adjustedConfidence = min(
                    adjustedConfidence,
                    (settings.confidenceThreshold - 0.01).coerceAtLeast(0.0)
                )
            }
            adjustedQuality = (adjustedQuality * if (crStrongGlobalEvidence) 0.96 else 0.92).coerceIn(0.0, 1.0)
            val bounds = inflateCiBounds(
                center = context.crEff,
                low = ciCrLow,
                high = ciCrHigh,
                factor = if (crStrongGlobalEvidence) 1.12 else 1.18,
                minBound = 2.0,
                maxBound = 60.0
            )
            ciCrLow = bounds.first
            ciCrHigh = bounds.second
        }
        if (isfDayTypeEvidenceSparse) {
            if (!isfStrongGlobalEvidence) {
                adjustedConfidence = min(
                    adjustedConfidence,
                    (settings.confidenceThreshold - 0.01).coerceAtLeast(0.0)
                )
            }
            adjustedQuality = (adjustedQuality * if (isfStrongGlobalEvidence) 0.98 else 0.96).coerceIn(0.0, 1.0)
            val bounds = inflateCiBounds(
                center = context.isfEff,
                low = ciIsfLow,
                high = ciIsfHigh,
                factor = if (isfStrongGlobalEvidence) 1.06 else 1.10,
                minBound = 0.8,
                maxBound = 18.0
            )
            ciIsfLow = bounds.first
            ciIsfHigh = bounds.second
        }
        if (crDayTypeEvidenceSparse) {
            if (!crStrongGlobalEvidence) {
                adjustedConfidence = min(
                    adjustedConfidence,
                    (settings.confidenceThreshold - 0.01).coerceAtLeast(0.0)
                )
            }
            adjustedQuality = (adjustedQuality * if (crStrongGlobalEvidence) 0.98 else 0.96).coerceIn(0.0, 1.0)
            val bounds = inflateCiBounds(
                center = context.crEff,
                low = ciCrLow,
                high = ciCrHigh,
                factor = if (crStrongGlobalEvidence) 1.06 else 1.10,
                minBound = 2.0,
                maxBound = 60.0
            )
            ciCrLow = bounds.first
            ciCrHigh = bounds.second
        }
        if (activeModel != null && dayTypeIsfBase == null) {
            adjustedConfidence = (adjustedConfidence - 0.03).coerceAtLeast(0.0)
            adjustedQuality = (adjustedQuality * 0.98).coerceIn(0.0, 1.0)
            val bounds = inflateCiBounds(
                center = context.isfEff,
                low = ciIsfLow,
                high = ciIsfHigh,
                factor = 1.06,
                minBound = 0.8,
                maxBound = 18.0
            )
            ciIsfLow = bounds.first
            ciIsfHigh = bounds.second
        }
        if (activeModel != null && dayTypeCrBase == null) {
            adjustedConfidence = (adjustedConfidence - 0.03).coerceAtLeast(0.0)
            adjustedQuality = (adjustedQuality * 0.98).coerceIn(0.0, 1.0)
            val bounds = inflateCiBounds(
                center = context.crEff,
                low = ciCrLow,
                high = ciCrHigh,
                factor = 1.06,
                minBound = 2.0,
                maxBound = 60.0
            )
            ciCrLow = bounds.first
            ciCrHigh = bounds.second
        }
        if (sensorSuspectFalseLowFlag >= 0.5) {
            adjustedConfidence = min(
                adjustedConfidence,
                (settings.confidenceThreshold - 0.01).coerceAtLeast(0.0)
            )
            adjustedQuality = (adjustedQuality * 0.90).coerceIn(0.0, 1.0)
            val isfBounds = inflateCiBounds(
                center = context.isfEff,
                low = ciIsfLow,
                high = ciIsfHigh,
                factor = 1.12,
                minBound = 0.8,
                maxBound = 18.0
            )
            val crBounds = inflateCiBounds(
                center = context.crEff,
                low = ciCrLow,
                high = ciCrHigh,
                factor = 1.12,
                minBound = 2.0,
                maxBound = 60.0
            )
            ciIsfLow = isfBounds.first
            ciIsfHigh = isfBounds.second
            ciCrLow = crBounds.first
            ciCrHigh = crBounds.second
        }

        val runtimeFactors = context.factors + mapOf(
            "current_day_type_weekend" to if (currentDayType == DayType.WEEKEND) 1.0 else 0.0,
            "isf_base_source_day_type" to if (dayTypeIsfBase != null) 1.0 else 0.0,
            "cr_base_source_day_type" to if (dayTypeCrBase != null) 1.0 else 0.0,
            "isf_base_source_hourly" to if (dayTypeIsfBase == null && stateIsfBase != null) 1.0 else 0.0,
            "cr_base_source_hourly" to if (dayTypeCrBase == null && stateCrBase != null) 1.0 else 0.0,
            "isf_base_source_fallback" to if (dayTypeIsfBase == null && stateIsfBase == null) 1.0 else 0.0,
            "cr_base_source_fallback" to if (dayTypeCrBase == null && stateCrBase == null) 1.0 else 0.0,
            "isf_hour_window_same_day_type_count" to isfHourWindowSameDayTypeCount.toDouble(),
            "cr_hour_window_same_day_type_count" to crHourWindowSameDayTypeCount.toDouble(),
            "isf_hour_window_evidence_enough" to if (isfHourEvidenceEnough) 1.0 else 0.0,
            "cr_hour_window_evidence_enough" to if (crHourEvidenceEnough) 1.0 else 0.0,
            "isf_global_evidence_strong" to if (isfStrongGlobalEvidence) 1.0 else 0.0,
            "cr_global_evidence_strong" to if (crStrongGlobalEvidence) 1.0 else 0.0,
            "raw_isf_eff" to context.isfEff,
            "raw_cr_eff" to context.crEff,
            "raw_confidence" to adjustedConfidence,
            "sensor_quality_score" to sensorQualityScore,
            "sensor_quality_suspect_false_low" to sensorSuspectFalseLowFlag,
            "isf_hour_window_same_day_type_ratio" to if (isfHourWindowEvidenceCount == 0) 0.0 else {
                isfHourWindowSameDayTypeCount.toDouble() / isfHourWindowEvidenceCount
            },
            "cr_hour_window_same_day_type_ratio" to if (crHourWindowEvidenceCount == 0) 0.0 else {
                crHourWindowSameDayTypeCount.toDouble() / crHourWindowEvidenceCount
            }
        )

        val candidate = IsfCrRealtimeSnapshot(
            id = "snapshot-$nowTs",
            ts = nowTs,
            isfEff = context.isfEff,
            crEff = context.crEff,
            isfBase = blendedIsfBase,
            crBase = blendedCrBase,
            ciIsfLow = ciIsfLow,
            ciIsfHigh = ciIsfHigh,
            ciCrLow = ciCrLow,
            ciCrHigh = ciCrHigh,
            confidence = adjustedConfidence,
            qualityScore = adjustedQuality,
            factors = runtimeFactors,
            mode = if (settings.shadowMode) IsfCrRuntimeMode.SHADOW else IsfCrRuntimeMode.ACTIVE,
            isfEvidenceCount = isfEvidence.size,
            crEvidenceCount = crEvidence.size,
            reasons = buildList {
                if (activeModel == null) add("model_state_missing")
                if (activeModel != null && dayTypeIsfBase == null) add("isf_day_type_base_missing")
                if (activeModel != null && dayTypeCrBase == null) add("cr_day_type_base_missing")
                if (isfEvidence.isEmpty()) add("isf_evidence_sparse")
                if (crEvidence.isEmpty()) add("cr_evidence_sparse")
                if (!isfHourEvidenceEnough) add("isf_hourly_evidence_below_min")
                if (!crHourEvidenceEnough) add("cr_hourly_evidence_below_min")
                if (isfDayTypeEvidenceSparse) add("isf_day_type_evidence_sparse")
                if (crDayTypeEvidenceSparse) add("cr_day_type_evidence_sparse")
                if (sensorQualityScore < 0.50) add("sensor_quality_low")
                if (sensorSuspectFalseLowFlag >= 0.50) add("sensor_quality_suspect_false_low")
                if ((context.factors["set_age_hours"] ?: 0.0) > 72.0) add("set_age_high")
                if ((context.factors["sensor_age_hours"] ?: 0.0) > 120.0) add("sensor_age_high")
                val contextAmbiguity = maxOf(
                    context.factors["latent_stress"] ?: 0.0,
                    context.factors["manual_stress_tag"] ?: 0.0,
                    context.factors["manual_illness_tag"] ?: 0.0,
                    context.factors["manual_hormone_tag"] ?: 0.0,
                    context.factors["manual_steroid_tag"] ?: 0.0,
                    context.factors["manual_dawn_tag"] ?: 0.0
                )
                if (contextAmbiguity >= 0.70) add("context_ambiguity_high")
            }
        )

        val resolved = fallbackResolver.resolve(
            snapshot = candidate,
            settings = settings,
            fallbackIsf = fallbackIsf,
            fallbackCr = fallbackCr
        )

        val diagnostics = IsfCrDiagnostics(
            usedEvidenceCount = evidence.size,
            isfEvidenceCount = isfEvidence.size,
            crEvidenceCount = crEvidence.size,
            droppedEvidenceCount = extraction.droppedCount,
            droppedReasonCounts = extraction.droppedReasonCounts,
            currentDayType = currentDayType.name,
            isfBaseSource = isfBaseSource,
            crBaseSource = crBaseSource,
            isfDayTypeBaseAvailable = dayTypeIsfBase != null,
            crDayTypeBaseAvailable = dayTypeCrBase != null,
            hourWindowIsfEvidenceCount = isfHourWindowEvidenceCount,
            hourWindowCrEvidenceCount = crHourWindowEvidenceCount,
            hourWindowIsfSameDayTypeCount = isfHourWindowSameDayTypeCount,
            hourWindowCrSameDayTypeCount = crHourWindowSameDayTypeCount,
            minIsfEvidencePerHour = minIsfEvidencePerHour,
            minCrEvidencePerHour = minCrEvidencePerHour,
            crMaxGapMinutes = settings.crGrossGapMinutes.coerceIn(10.0, 60.0),
            crMaxSensorBlockedRate = settings.crSensorBlockedRateThreshold.coerceIn(0.0, 1.0),
            crMaxUamAmbiguityRate = settings.crUamAmbiguityRateThreshold.coerceIn(0.0, 1.0),
            coverageHoursIsf = isfEvidence.map { it.hourLocal }.distinct().size,
            coverageHoursCr = crEvidence.map { it.hourLocal }.distinct().size,
            qualityScore = resolved.qualityScore,
            confidence = resolved.confidence,
            lowConfidence = resolved.confidence < settings.confidenceThreshold,
            mode = resolved.mode,
            reasons = resolved.reasons
        )

        return RealtimeResult(
            snapshot = resolved,
            diagnostics = diagnostics,
            evidence = evidence
        )
    }

    private fun blendBaseWithEvidence(
        base: Double,
        evidence: Double?,
        evidenceCount: Int,
        minAlpha: Double = 0.05,
        maxAlpha: Double = 0.55
    ): Double {
        evidence ?: return base
        val alpha = (evidenceCount / 20.0).coerceIn(minAlpha.coerceAtLeast(0.01), maxAlpha.coerceAtMost(0.95))
        return (base * (1.0 - alpha)) + (evidence * alpha)
    }

    private fun weightedRecentValue(
        nowTs: Long,
        currentHour: Int,
        currentDayType: DayType,
        samples: List<IsfCrEvidenceSample>
    ): Double? {
        if (samples.isEmpty()) return null
        val weighted = samples.mapNotNull { sample ->
            if (sample.value <= 0.0) return@mapNotNull null
            val ageHours = ((nowTs - sample.ts).coerceAtLeast(0L)) / 3_600_000.0
            val hourDistance = minHourDistance(currentHour, sample.hourLocal).toDouble()
            val recencyWeight = expDecay(ageHours, halfLifeHours = 48.0)
            val hourWeight = expDecay(hourDistance, halfLifeHours = 2.0)
            val dayTypeWeight = if (sample.dayType == currentDayType) 1.0 else 0.72
            val w = (sample.weight * sample.qualityScore * recencyWeight * hourWeight * dayTypeWeight).coerceAtLeast(1e-4)
            ln(sample.value) to w
        }
        if (weighted.isEmpty()) return null
        val meanLog = weighted.sumOf { it.first * it.second } / weighted.sumOf { it.second }
        return kotlin.math.exp(meanLog)
    }

    private fun minHourDistance(h1: Int, h2: Int): Int {
        val diff = abs(h1 - h2)
        return minOf(diff, 24 - diff)
    }

    private fun expDecay(distance: Double, halfLifeHours: Double): Double {
        if (distance <= 0.0) return 1.0
        return kotlin.math.exp(-ln(2.0) * distance / halfLifeHours)
    }

    private fun hasStrongGlobalEvidence(
        totalEvidenceCount: Int,
        minEvidencePerHour: Int,
        absoluteMin: Int
    ): Boolean {
        val target = maxOf(absoluteMin, minEvidencePerHour.coerceAtLeast(0))
        return totalEvidenceCount >= target
    }

    private fun inflateCiBounds(
        center: Double,
        low: Double,
        high: Double,
        factor: Double,
        minBound: Double,
        maxBound: Double
    ): Pair<Double, Double> {
        val baseHalf = ((high - low).coerceAtLeast(0.05)) * 0.5
        val widenedHalf = (baseHalf * factor).coerceAtLeast(baseHalf)
        val nextLow = (center - widenedHalf).coerceIn(minBound, maxBound)
        val nextHigh = (center + widenedHalf).coerceIn(minBound, maxBound)
        return nextLow to nextHigh
    }

    private fun normalizeTelemetryKey(value: String): String {
        return value
            .replace(Regex("([a-z0-9])([A-Z])"), "$1_$2")
            .lowercase()
            .replace(Regex("[^a-z0-9]+"), "_")
            .trim('_')
    }

    private fun encodeDayTypeHourlyParams(
        prefix: String,
        values: List<Double?>
    ): Map<String, Double> {
        if (values.isEmpty()) return emptyMap()
        return buildMap {
            values.forEachIndexed { hour, value ->
                if (value == null || !value.isFinite() || value <= 0.0) return@forEachIndexed
                put("${prefix}_h${hour.toString().padStart(2, '0')}", value)
            }
        }
    }

    private fun resolveDayTypeHourlyBase(
        state: IsfCrModelState?,
        dayType: DayType,
        hour: Int,
        metric: String
    ): Double? {
        state ?: return null
        val dayTypePrefix = when (dayType) {
            DayType.WEEKDAY -> "weekday"
            DayType.WEEKEND -> "weekend"
        }
        val key = "${dayTypePrefix}_${metric}_h${hour.toString().padStart(2, '0')}"
        return state.params[key]?.takeIf { it.isFinite() && it > 0.0 }
    }
}
