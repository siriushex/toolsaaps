package io.aaps.copilot.data.repository

import io.aaps.copilot.config.SensorLagCorrectionMode
import io.aaps.copilot.domain.model.DataQuality
import io.aaps.copilot.domain.model.Forecast
import io.aaps.copilot.domain.model.GlucosePoint
import io.aaps.copilot.domain.model.SensorLagAgeSource
import io.aaps.copilot.domain.model.SensorLagEstimate
import io.aaps.copilot.domain.model.TherapyEvent
import io.aaps.copilot.domain.predict.Glucose5mCanonicalizer
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sqrt

internal data class GlucoseInputMetadata(
    val key: String?,
    val kind: String?
)

internal object SensorLagRuntimeEstimator {

    data class Input(
        val nowTs: Long,
        val glucose: List<GlucosePoint>,
        val therapy: List<TherapyEvent>,
        val latestGlucose: GlucosePoint,
        val requestedMode: SensorLagCorrectionMode,
        val staleMaxMinutes: Int,
        val sensorQualityScore: Double,
        val sensorBlocked: Boolean,
        val sensorSuspectFalseLow: Boolean,
        val latestInput: GlucoseInputMetadata? = null,
        val devicestatusAgeHours: Double? = null,
        val devicestatusAgeTs: Long? = null,
        val devicestatusAgeSourceRaw: String? = null,
        val sageDays: Double? = null,
        val cageDays: Double? = null,
        val replayBucketStats: List<SensorLagReplayBucketStats> = emptyList()
    )

    internal fun estimate(input: Input): SensorLagEstimate {
        val ageResolution = resolveSensorAge(
            nowTs = input.nowTs,
            therapy = input.therapy,
            glucose = input.glucose,
            devicestatusAgeHours = input.devicestatusAgeHours,
            devicestatusAgeTs = input.devicestatusAgeTs
        )
        val raw = input.latestGlucose.valueMmol
        val wearBucket = ageResolution.ageHours?.let(::sensorLagWearBucket) ?: SENSOR_LAG_BUCKET_MISSING
        val rocBlend = effectiveRocBlend5m(input.glucose)
        val slopeConfidence = slopeConfidence(input.glucose)
        val baseLagMinutes = when (ageResolution.ageSource) {
            SensorLagAgeSource.MISSING -> DEFAULT_MISSING_AGE_LAG_MINUTES
            else -> lagProfileMinutes(ageResolution.ageHours ?: 0.0)
        }
        val replayMultiplier = resolveReplayMultiplier(
            wearBucket = wearBucket,
            replayBucketStats = input.replayBucketStats
        )
        val effectiveLagMinutes = (baseLagMinutes * replayMultiplier)
            .coerceIn(MIN_LAG_MINUTES, MAX_LAG_MINUTES)
        val trendConsistency = trendConsistency(rocBlend.components, rocBlend.roc5m)
        val qualityAttenuation = qualityAttenuation(
            sensorQualityScore = input.sensorQualityScore,
            sensorBlocked = input.sensorBlocked,
            sensorSuspectFalseLow = input.sensorSuspectFalseLow,
            sageDays = input.sageDays,
            cageDays = input.cageDays
        )
        val effectiveCorrectionCap = correctionCapForSource(
            ageSource = ageResolution.ageSource,
            sensorSuspectFalseLow = input.sensorSuspectFalseLow
        )
        val rawCorrection = rocBlend.roc5m * (effectiveLagMinutes / 5.0)
        val correctionMmol = (
            rawCorrection *
                trendConsistency *
                ageResolution.sourceConfidence *
                qualityAttenuation
            ).coerceIn(-effectiveCorrectionCap, effectiveCorrectionCap)
        val correctedGlucoseMmol = (raw + correctionMmol)
            .coerceIn(MIN_GLUCOSE_MMOL, MAX_GLUCOSE_MMOL)
        val confidence = (
            ageResolution.sourceConfidence *
                slopeConfidence *
                qualityConfidence(input.sensorQualityScore) *
                (0.55 + trendConsistency * 0.45) *
                (0.94 + (replayMultiplier - 1.0).coerceIn(-0.12, 0.12))
            ).coerceIn(0.0, 1.0)

        if (input.requestedMode == SensorLagCorrectionMode.OFF) {
            return SensorLagEstimate(
                rawGlucoseMmol = raw,
                correctedGlucoseMmol = raw,
                lagMinutes = 0.0,
                correctionMmol = 0.0,
                ageHours = ageResolution.ageHours,
                ageSource = ageResolution.ageSource,
                wearBucket = wearBucket,
                sourceConfidence = ageResolution.sourceConfidence,
                trendConsistency = trendConsistency,
                replayMultiplier = replayMultiplier,
                effectiveLagMinutes = 0.0,
                effectiveCorrectionCap = effectiveCorrectionCap,
                confidence = 0.0,
                mode = SensorLagCorrectionMode.OFF,
                disableReason = "mode_off",
                ageConflictHours = ageResolution.ageConflictHours
            )
        }

        val effectiveMode = resolveEffectiveMode(
            requestedMode = input.requestedMode,
            ageSource = ageResolution.ageSource,
            dataFresh = input.nowTs - input.latestGlucose.ts <=
                input.staleMaxMinutes.coerceAtLeast(1) * 60_000L,
            validRecentPointCount = recentValidPointCount(
                glucose = input.glucose,
                latestTs = input.latestGlucose.ts
            ),
            sensorQualityScore = input.sensorQualityScore,
            sensorBlocked = input.sensorBlocked,
            sensorSuspectFalseLow = input.sensorSuspectFalseLow,
            latestInput = input.latestInput
        )

        return SensorLagEstimate(
            rawGlucoseMmol = raw,
            correctedGlucoseMmol = correctedGlucoseMmol,
            lagMinutes = effectiveLagMinutes,
            correctionMmol = correctionMmol,
            ageHours = ageResolution.ageHours,
            ageSource = ageResolution.ageSource,
            wearBucket = wearBucket,
            sourceConfidence = ageResolution.sourceConfidence,
            trendConsistency = trendConsistency,
            replayMultiplier = replayMultiplier,
            effectiveLagMinutes = effectiveLagMinutes,
            effectiveCorrectionCap = effectiveCorrectionCap,
            confidence = confidence,
            mode = effectiveMode.mode,
            disableReason = effectiveMode.disableReason,
            ageConflictHours = ageResolution.ageConflictHours
        )
    }

    internal fun applyForecastBias(
        forecasts: List<Forecast>,
        estimate: SensorLagEstimate
    ): List<Forecast> {
        if (forecasts.isEmpty()) return forecasts
        if (abs(estimate.correctionMmol) < 1e-6) return forecasts

        return forecasts.map { forecast ->
            val shift = estimate.correctionMmol *
                horizonScale(forecast.horizonMinutes) *
                forecastBiasAttenuation(
                    wearBucket = estimate.wearBucket,
                    horizonMinutes = forecast.horizonMinutes,
                    replayMultiplier = estimate.replayMultiplier
                )
            if (abs(shift) < 1e-6) return@map forecast
            val shiftedValue = (forecast.valueMmol + shift).coerceIn(MIN_GLUCOSE_MMOL, MAX_GLUCOSE_MMOL)
            var shiftedLow = (forecast.ciLow + shift).coerceIn(MIN_GLUCOSE_MMOL, MAX_GLUCOSE_MMOL)
            var shiftedHigh = (forecast.ciHigh + shift).coerceIn(MIN_GLUCOSE_MMOL, MAX_GLUCOSE_MMOL)
            if (shiftedLow > shiftedValue) shiftedLow = shiftedValue
            if (shiftedHigh < shiftedValue) shiftedHigh = shiftedValue
            forecast.copy(
                valueMmol = shiftedValue,
                ciLow = shiftedLow,
                ciHigh = shiftedHigh,
                modelVersion = if (forecast.modelVersion.contains("|sensor_lag_v2")) {
                    forecast.modelVersion
                } else {
                    "${forecast.modelVersion}|sensor_lag_v2"
                }
            )
        }
    }

    internal fun lagProfileMinutes(ageHours: Double): Double {
        val safeAge = ageHours.coerceAtLeast(0.0)
        return when {
            safeAge <= 24.0 -> interpolate(
                value = safeAge,
                start = 0.0,
                end = 24.0,
                startValue = 8.0,
                endValue = 9.0
            )
            safeAge <= 240.0 -> interpolate(
                value = safeAge,
                start = 24.0,
                end = 240.0,
                startValue = 9.0,
                endValue = 11.0
            )
            safeAge <= 288.0 -> interpolate(
                value = safeAge,
                start = 240.0,
                end = 288.0,
                startValue = 11.0,
                endValue = 14.0
            )
            safeAge <= 336.0 -> interpolate(
                value = safeAge,
                start = 288.0,
                end = 336.0,
                startValue = 14.0,
                endValue = 18.0
            )
            else -> 18.0
        }.coerceAtMost(20.0)
    }

    internal fun sensorLagWearBucket(ageHours: Double): String {
        val safeAge = ageHours.coerceAtLeast(0.0)
        return when {
            safeAge < 24.0 -> "<24h"
            safeAge < 240.0 -> "1-10d"
            safeAge < 288.0 -> "10-12d"
            safeAge < 336.0 -> "12-14d"
            else -> ">14d"
        }
    }

    internal fun effectiveRoc5m(glucose: List<GlucosePoint>): Double {
        return effectiveRocBlend5m(glucose).roc5m
    }

    private data class SensorLagAgeResolution(
        val ageHours: Double?,
        val ageSource: SensorLagAgeSource,
        val sourceConfidence: Double,
        val ageConflictHours: Double?
    )

    private data class EffectiveMode(
        val mode: SensorLagCorrectionMode,
        val disableReason: String?
    )

    private data class RocBlend(
        val roc5m: Double,
        val components: List<Double>
    )

    private fun resolveSensorAge(
        nowTs: Long,
        therapy: List<TherapyEvent>,
        glucose: List<GlucosePoint>,
        devicestatusAgeHours: Double?,
        devicestatusAgeTs: Long?
    ): SensorLagAgeResolution {
        val explicitSensorChange = therapy
            .asSequence()
            .filter { event ->
                when (event.type.lowercase()) {
                    "sensor_change",
                    "cgm_sensor_change",
                    "sensor_start",
                    "sensor_started" -> true
                    else -> false
                }
            }
            .maxOfOrNull { it.ts }
        val explicitAgeHours = explicitSensorChange
            ?.let { ((nowTs - it).coerceAtLeast(0L)) / 3_600_000.0 }

        val freshDevicestatusAgeHours = devicestatusAgeHours
            ?.takeIf { it in 0.0..MAX_SENSOR_AGE_HOURS }
            ?.takeIf {
                devicestatusAgeTs != null &&
                    nowTs >= devicestatusAgeTs &&
                    nowTs - devicestatusAgeTs <= DEVICESTATUS_AGE_FRESHNESS_MS
            }
        if (freshDevicestatusAgeHours != null) {
            val conflictHours = explicitAgeHours
                ?.let { abs(it - freshDevicestatusAgeHours) }
                ?.takeIf { it > SENSOR_AGE_SOURCE_CONFLICT_TOLERANCE_HOURS }
            val sourceConfidence = if (conflictHours != null) {
                DEVICESTATUS_SOURCE_CONFIDENCE * DEVICESTATUS_CONFLICT_PENALTY
            } else {
                DEVICESTATUS_SOURCE_CONFIDENCE
            }
            return SensorLagAgeResolution(
                ageHours = freshDevicestatusAgeHours,
                ageSource = SensorLagAgeSource.DEVICESTATUS,
                sourceConfidence = sourceConfidence.coerceIn(0.0, 1.0),
                ageConflictHours = conflictHours
            )
        }

        if (explicitAgeHours != null) {
            return SensorLagAgeResolution(
                ageHours = explicitAgeHours,
                ageSource = SensorLagAgeSource.EXPLICIT_EVENT,
                sourceConfidence = EXPLICIT_SOURCE_CONFIDENCE,
                ageConflictHours = null
            )
        }

        val inferredBoundary = inferSensorSessionBoundary(glucose)
        if (inferredBoundary != null) {
            return SensorLagAgeResolution(
                ageHours = ((nowTs - inferredBoundary).coerceAtLeast(0L)) / 3_600_000.0,
                ageSource = SensorLagAgeSource.INFERRED_BOUNDARY,
                sourceConfidence = INFERRED_SOURCE_CONFIDENCE,
                ageConflictHours = null
            )
        }
        return SensorLagAgeResolution(
            ageHours = null,
            ageSource = SensorLagAgeSource.MISSING,
            sourceConfidence = MISSING_SOURCE_CONFIDENCE,
            ageConflictHours = null
        )
    }

    private fun inferSensorSessionBoundary(glucose: List<GlucosePoint>): Long? {
        val sorted = glucose
            .filter { it.quality != DataQuality.SENSOR_ERROR }
            .sortedBy { it.ts }
        if (sorted.size < MIN_STABLE_SESSION_POINTS) return null

        return sorted.zipWithNext()
            .asSequence()
            .mapNotNull { (previous, next) ->
                val gapCandidate = next.ts - previous.ts >= SESSION_GAP_TRIGGER_MS
                val sourceCandidate = !previous.source.equals(next.source, ignoreCase = true)
                if (!gapCandidate && !sourceCandidate) return@mapNotNull null
                next.ts.takeIf { hasStableCadenceAfter(boundaryTs = it, glucose = sorted) }
            }
            .maxOrNull()
    }

    private fun hasStableCadenceAfter(boundaryTs: Long, glucose: List<GlucosePoint>): Boolean {
        val windowEnd = boundaryTs + STABLE_CADENCE_WINDOW_MS
        val session = glucose
            .filter { it.ts in boundaryTs..windowEnd }
            .sortedBy { it.ts }
        if (session.size < MIN_STABLE_SESSION_POINTS) return false
        if ((session.last().ts - session.first().ts) < STABLE_CADENCE_WINDOW_MS) return false
        return session.zipWithNext().all { (prev, next) ->
            val gap = next.ts - prev.ts
            gap in 1L..MAX_STABLE_CADENCE_GAP_MS
        }
    }

    private fun resolveEffectiveMode(
        requestedMode: SensorLagCorrectionMode,
        ageSource: SensorLagAgeSource,
        dataFresh: Boolean,
        validRecentPointCount: Int,
        sensorQualityScore: Double,
        sensorBlocked: Boolean,
        sensorSuspectFalseLow: Boolean,
        latestInput: GlucoseInputMetadata?
    ): EffectiveMode {
        if (requestedMode != SensorLagCorrectionMode.ACTIVE) {
            return EffectiveMode(
                mode = SensorLagCorrectionMode.SHADOW,
                disableReason = if (ageSource == SensorLagAgeSource.MISSING) {
                    "sensor_age_unresolved"
                } else {
                    null
                }
            )
        }
        val reason = when {
            !dataFresh -> "stale_glucose"
            validRecentPointCount < 4 -> "insufficient_recent_points"
            sensorQualityScore < ACTIVE_MIN_SENSOR_QUALITY_SCORE -> "sensor_quality_low"
            sensorBlocked -> "sensor_quality_blocked"
            sensorSuspectFalseLow -> "sensor_suspect_false_low"
            ageSource == SensorLagAgeSource.MISSING -> "sensor_age_unresolved"
            latestInput?.kind.equals("raw", ignoreCase = true) -> "raw_glucose_input"
            else -> null
        }
        return if (reason == null) {
            EffectiveMode(mode = SensorLagCorrectionMode.ACTIVE, disableReason = null)
        } else {
            EffectiveMode(mode = SensorLagCorrectionMode.SHADOW, disableReason = reason)
        }
    }

    private fun recentValidPointCount(glucose: List<GlucosePoint>, latestTs: Long): Int {
        val since = latestTs - 20L * 60L * 1000L
        return glucose.count { point ->
            point.ts in since..latestTs && point.quality != DataQuality.SENSOR_ERROR
        }
    }

    private fun effectiveRocBlend5m(glucose: List<GlucosePoint>): RocBlend {
        val canonical = Glucose5mCanonicalizer.build(glucose)
        val points = canonical.points.sortedBy { it.ts }
        if (points.size < 2) return RocBlend(roc5m = 0.0, components = emptyList())
        val latest = points.last()
        val weighted = listOfNotNull(
            slopeOverMinutes(points, latest.ts, 5)?.let { 0.40 to it },
            slopeOverMinutes(points, latest.ts, 10)?.let { 0.30 to it },
            slopeOverMinutes(points, latest.ts, 15)?.let { 0.20 to it },
            slopeOverMinutes(points, latest.ts, 20)?.let { 0.10 to it }
        )
        if (weighted.isEmpty()) return RocBlend(roc5m = 0.0, components = emptyList())
        val weightSum = weighted.sumOf { it.first }.coerceAtLeast(1e-6)
        val roc = (weighted.sumOf { it.first * it.second } / weightSum)
            .coerceIn(-MAX_ROC_MMOL_PER_5M, MAX_ROC_MMOL_PER_5M)
        return RocBlend(
            roc5m = roc,
            components = weighted.map { it.second }
        )
    }

    private fun slopeOverMinutes(
        points: List<GlucosePoint>,
        latestTs: Long,
        minutes: Int
    ): Double? {
        val targetTs = latestTs - minutes * 60_000L
        val point = points.lastOrNull { it.ts <= targetTs } ?: return null
        val delta = points.last().valueMmol - point.valueMmol
        return delta / (minutes / 5.0)
    }

    private fun trendConsistency(
        components: List<Double>,
        blendedRoc: Double
    ): Double {
        if (components.isEmpty()) return MIN_TREND_CONSISTENCY
        if (abs(blendedRoc) < FLAT_TREND_THRESHOLD_MMOL_PER_5M) {
            return MIN_TREND_CONSISTENCY
        }
        val significant = components.filter { abs(it) >= FLAT_TREND_THRESHOLD_MMOL_PER_5M / 2.0 }
        val positives = significant.count { it > 0.0 }
        val negatives = significant.count { it < 0.0 }
        val signScore = when {
            significant.isEmpty() -> MIN_TREND_CONSISTENCY
            positives == 0 || negatives == 0 -> 1.0
            else -> (max(positives, negatives).toDouble() / significant.size.toDouble())
                .coerceIn(0.35, 1.0)
        }
        val mean = components.average()
        val variance = components.sumOf { (it - mean).pow(2.0) } / components.size.toDouble().coerceAtLeast(1.0)
        val stdev = sqrt(variance)
        val spreadScore = (1.0 - (stdev / TREND_SPREAD_SOFT_CAP_MMOL_PER_5M))
            .coerceIn(0.35, 1.0)
        return (signScore * 0.55 + spreadScore * 0.45)
            .coerceIn(MIN_TREND_CONSISTENCY, 1.0)
    }

    private fun slopeConfidence(glucose: List<GlucosePoint>): Double {
        val canonical = Glucose5mCanonicalizer.build(glucose)
        return when {
            canonical.points.size >= 7 -> 1.0
            canonical.points.size >= 5 -> 0.88
            canonical.points.size >= 3 -> 0.72
            else -> 0.55
        }
    }

    private fun qualityAttenuation(
        sensorQualityScore: Double,
        sensorBlocked: Boolean,
        sensorSuspectFalseLow: Boolean,
        sageDays: Double?,
        cageDays: Double?
    ): Double {
        var attenuation = qualityConfidence(sensorQualityScore)
        if (sensorBlocked) attenuation *= 0.65
        if (sensorSuspectFalseLow) attenuation *= 0.55
        if ((sageDays ?: 0.0) > 14.0) attenuation *= 0.94
        if ((cageDays ?: 0.0) > 4.0) attenuation *= 0.96
        return attenuation.coerceIn(0.25, 1.0)
    }

    private fun qualityConfidence(score: Double): Double {
        return (0.45 + score.coerceIn(0.0, 1.0) * 0.55).coerceIn(0.45, 1.0)
    }

    private fun correctionCapForSource(
        ageSource: SensorLagAgeSource,
        sensorSuspectFalseLow: Boolean
    ): Double {
        val baseCap = when (ageSource) {
            SensorLagAgeSource.DEVICESTATUS,
            SensorLagAgeSource.EXPLICIT_EVENT -> 1.5
            SensorLagAgeSource.INFERRED_BOUNDARY -> 1.0
            SensorLagAgeSource.MISSING -> 0.6
        }
        return if (sensorSuspectFalseLow) {
            (baseCap * SUSPECT_FALSE_LOW_CORRECTION_CAP_FACTOR).coerceAtLeast(0.25)
        } else {
            baseCap
        }
    }

    private fun resolveReplayMultiplier(
        wearBucket: String,
        replayBucketStats: List<SensorLagReplayBucketStats>
    ): Double {
        if (replayBucketStats.isEmpty() || wearBucket == SENSOR_LAG_BUCKET_MISSING) return 1.0
        val targetOrder = sensorLagWearBucketOrder(wearBucket) ?: return 1.0
        val horizonWeights = mapOf(30 to 0.45, 60 to 0.55)
        val horizonContributions = horizonWeights.mapNotNull { (horizonMinutes, horizonWeight) ->
            val horizonStats = replayBucketStats.filter { it.horizonMinutes == horizonMinutes }
            if (horizonStats.isEmpty()) return@mapNotNull null
            val exactEnough = horizonStats.any {
                it.bucket == wearBucket && it.sampleCount >= MIN_REPLAY_BUCKET_SAMPLE_COUNT
            }
            val contributions = horizonStats.mapNotNull { stat ->
                val multiplier = replayMultiplierForBucket(stat) ?: return@mapNotNull null
                val bucketOrder = sensorLagWearBucketOrder(stat.bucket) ?: return@mapNotNull null
                val distance = abs(bucketOrder - targetOrder)
                val proximityWeight = when (distance) {
                    0 -> 1.0
                    1 -> if (exactEnough) 0.30 else 0.55
                    2 -> if (exactEnough) 0.12 else 0.20
                    else -> 0.0
                }
                if (proximityWeight <= 0.0) return@mapNotNull null
                val sampleSupport = (stat.sampleCount / 12.0).coerceIn(0.35, 1.0)
                (proximityWeight * sampleSupport) to multiplier
            }
            if (contributions.isEmpty()) return@mapNotNull null
            val localWeight = contributions.sumOf { it.first }.coerceAtLeast(1e-6)
            horizonWeight to (contributions.sumOf { it.first * it.second } / localWeight)
        }
        if (horizonContributions.isEmpty()) return 1.0
        val weightSum = horizonContributions.sumOf { it.first }.coerceAtLeast(1e-6)
        val blended = (horizonContributions.sumOf { it.first * it.second } / weightSum)
            .coerceIn(MIN_REPLAY_MULTIPLIER, MAX_REPLAY_MULTIPLIER)
        val lateWearCap = lateWearNegativeReplayCap(
            wearBucket = wearBucket,
            replayBucketStats = replayBucketStats
        )
        return if (lateWearCap == null) blended else minOf(blended, lateWearCap)
    }

    private fun sensorLagWearBucketOrder(bucket: String): Int? = when (bucket) {
        "<24h" -> 0
        "1-10d" -> 1
        "10-12d" -> 2
        "12-14d" -> 3
        ">14d" -> 4
        else -> null
    }

    private fun replayMultiplierForBucket(stat: SensorLagReplayBucketStats): Double? {
        if (stat.sampleCount < MIN_REPLAY_BUCKET_SAMPLE_COUNT) return null
        val improvement = stat.maeImprovementMmol
        val rawBiasAbs = abs(stat.rawBias)
        val lagBiasAbs = abs(stat.lagBias)
        return when {
            improvement >= REPLAY_HELPFUL_IMPROVEMENT_MMOL &&
                lagBiasAbs <= rawBiasAbs + REPLAY_BIAS_SOFT_MARGIN_MMOL -> {
                interpolate(
                    value = improvement,
                    start = REPLAY_HELPFUL_IMPROVEMENT_MMOL,
                    end = 0.25,
                    startValue = 1.05,
                    endValue = MAX_REPLAY_MULTIPLIER
                )
            }
            improvement <= -REPLAY_HARMFUL_IMPROVEMENT_MMOL -> {
                interpolate(
                    value = abs(improvement),
                    start = REPLAY_HARMFUL_IMPROVEMENT_MMOL,
                    end = 0.25,
                    startValue = 0.97,
                    endValue = MIN_REPLAY_MULTIPLIER
                )
            }
            else -> 1.0
        }
    }

    private fun lateWearNegativeReplayCap(
        wearBucket: String,
        replayBucketStats: List<SensorLagReplayBucketStats>
    ): Double? {
        val cap = when (wearBucket) {
            "12-14d" -> LATE_WEAR_NEGATIVE_REPLAY_CAP_12_14D
            ">14d" -> LATE_WEAR_NEGATIVE_REPLAY_CAP_GT_14D
            else -> return null
        }
        val exactRows = replayBucketStats.filter {
            it.bucket == wearBucket && it.sampleCount >= MIN_REPLAY_BUCKET_SAMPLE_COUNT
        }
        if (exactRows.isEmpty()) return null
        val totalSamples = exactRows.sumOf { it.sampleCount }
        if (totalSamples < LATE_WEAR_NEGATIVE_REPLAY_MIN_TOTAL_SAMPLES) return null
        val weightedImprovement = exactRows.sumOf { it.maeImprovementMmol * it.sampleCount } / totalSamples.toDouble()
        return cap.takeIf { weightedImprovement <= -LATE_WEAR_NEGATIVE_REPLAY_MIN_IMPROVEMENT_MMOL }
    }

    private fun interpolate(
        value: Double,
        start: Double,
        end: Double,
        startValue: Double,
        endValue: Double
    ): Double {
        if (end <= start) return startValue
        val ratio = ((value - start) / (end - start)).coerceIn(0.0, 1.0)
        return startValue + (endValue - startValue) * ratio
    }

    private fun horizonScale(horizonMinutes: Int): Double {
        return when {
            horizonMinutes <= 5 -> 1.0
            horizonMinutes <= 30 -> interpolate(
                value = horizonMinutes.toDouble(),
                start = 5.0,
                end = 30.0,
                startValue = 1.0,
                endValue = 0.6
            )
            horizonMinutes <= 60 -> interpolate(
                value = horizonMinutes.toDouble(),
                start = 30.0,
                end = 60.0,
                startValue = 0.6,
                endValue = 0.3
            )
            else -> 0.3
        }
    }

    private fun forecastBiasAttenuation(
        wearBucket: String,
        horizonMinutes: Int,
        replayMultiplier: Double
    ): Double {
        val base = when (wearBucket) {
            "12-14d" -> when {
                horizonMinutes <= 5 -> 0.94
                horizonMinutes <= 30 -> 0.82
                else -> 0.68
            }
            ">14d" -> when {
                horizonMinutes <= 5 -> 0.90
                horizonMinutes <= 30 -> 0.72
                else -> 0.58
            }
            else -> 1.0
        }
        if (horizonMinutes <= 5) return base
        val replayAttenuation = when (wearBucket) {
            "12-14d" -> interpolate(
                value = replayMultiplier.coerceIn(MIN_REPLAY_MULTIPLIER, 1.0),
                start = MIN_REPLAY_MULTIPLIER,
                end = 1.0,
                startValue = 0.84,
                endValue = 1.0
            )
            ">14d" -> interpolate(
                value = replayMultiplier.coerceIn(MIN_REPLAY_MULTIPLIER, 1.0),
                start = MIN_REPLAY_MULTIPLIER,
                end = 1.0,
                startValue = 0.80,
                endValue = 1.0
            )
            else -> 1.0
        }
        val harmfulProjectionAttenuation = when (wearBucket) {
            "12-14d",
            ">14d" -> when {
                replayMultiplier <= LATE_WEAR_FORECAST_HARMFUL_REPLAY_THRESHOLD -> when {
                    horizonMinutes <= 30 -> 0.80
                    else -> 0.65
                }
                replayMultiplier < 1.0 -> when {
                    horizonMinutes <= 30 -> 0.90
                    else -> 0.80
                }
                else -> 1.0
            }
            else -> 1.0
        }
        return (base * replayAttenuation * harmfulProjectionAttenuation).coerceIn(0.0, 1.0)
    }

    private const val SESSION_GAP_TRIGGER_MS = 6L * 60L * 60L * 1000L
    private const val STABLE_CADENCE_WINDOW_MS = 90L * 60L * 1000L
    private const val MAX_STABLE_CADENCE_GAP_MS = 10L * 60L * 1000L
    private const val MIN_STABLE_SESSION_POINTS = 16
    private const val ACTIVE_MIN_SENSOR_QUALITY_SCORE = 0.55
    private const val DEVICESTATUS_AGE_FRESHNESS_MS = 6L * 60L * 60L * 1000L
    private const val SENSOR_AGE_SOURCE_CONFLICT_TOLERANCE_HOURS = 24.0
    private const val MAX_SENSOR_AGE_HOURS = 24.0 * 30.0
    private const val MAX_ROC_MMOL_PER_5M = 0.45
    private const val DEFAULT_MISSING_AGE_LAG_MINUTES = 10.0
    private const val MIN_LAG_MINUTES = 0.0
    private const val MAX_LAG_MINUTES = 20.0
    private const val MIN_GLUCOSE_MMOL = 2.2
    private const val MAX_GLUCOSE_MMOL = 22.0
    private const val MIN_TREND_CONSISTENCY = 0.35
    private const val FLAT_TREND_THRESHOLD_MMOL_PER_5M = 0.03
    private const val TREND_SPREAD_SOFT_CAP_MMOL_PER_5M = 0.24
    private const val DEVICESTATUS_SOURCE_CONFIDENCE = 0.96
    private const val DEVICESTATUS_CONFLICT_PENALTY = 0.75
    private const val EXPLICIT_SOURCE_CONFIDENCE = 0.90
    private const val INFERRED_SOURCE_CONFIDENCE = 0.68
    private const val MISSING_SOURCE_CONFIDENCE = 0.24
    private const val SUSPECT_FALSE_LOW_CORRECTION_CAP_FACTOR = 0.55
    private const val MIN_REPLAY_BUCKET_SAMPLE_COUNT = 6
    private const val REPLAY_HELPFUL_IMPROVEMENT_MMOL = 0.05
    private const val REPLAY_HARMFUL_IMPROVEMENT_MMOL = 0.02
    private const val REPLAY_BIAS_SOFT_MARGIN_MMOL = 0.10
    private const val MIN_REPLAY_MULTIPLIER = 0.88
    private const val MAX_REPLAY_MULTIPLIER = 1.12
    private const val LATE_WEAR_NEGATIVE_REPLAY_CAP_12_14D = 0.90
    private const val LATE_WEAR_NEGATIVE_REPLAY_CAP_GT_14D = 0.88
    private const val LATE_WEAR_NEGATIVE_REPLAY_MIN_TOTAL_SAMPLES = 10
    private const val LATE_WEAR_NEGATIVE_REPLAY_MIN_IMPROVEMENT_MMOL = 0.02
    private const val LATE_WEAR_FORECAST_HARMFUL_REPLAY_THRESHOLD = 0.90
    private const val SENSOR_LAG_BUCKET_MISSING = "missing"
}
