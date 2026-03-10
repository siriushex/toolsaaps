package io.aaps.copilot.data.repository

import io.aaps.copilot.config.SensorLagCorrectionMode
import io.aaps.copilot.domain.model.Forecast
import io.aaps.copilot.domain.model.GlucosePoint
import io.aaps.copilot.domain.model.SensorLagAgeSource
import io.aaps.copilot.domain.model.SensorLagEstimate
import io.aaps.copilot.domain.model.TherapyEvent
import io.aaps.copilot.domain.predict.Glucose5mCanonicalizer
import kotlin.math.abs

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
        val latestInput: GlucoseInputMetadata? = null
    )

    internal fun estimate(input: Input): SensorLagEstimate {
        val ageResolution = resolveSensorAge(
            nowTs = input.nowTs,
            therapy = input.therapy,
            glucose = input.glucose
        )
        val raw = input.latestGlucose.valueMmol
        if (input.requestedMode == SensorLagCorrectionMode.OFF) {
            return SensorLagEstimate(
                rawGlucoseMmol = raw,
                correctedGlucoseMmol = raw,
                lagMinutes = 0.0,
                correctionMmol = 0.0,
                ageHours = ageResolution.ageHours,
                ageSource = ageResolution.ageSource,
                confidence = 0.0,
                mode = SensorLagCorrectionMode.OFF,
                disableReason = "mode_off"
            )
        }

        val effectiveMode = resolveEffectiveMode(
            requestedMode = input.requestedMode,
            ageSource = ageResolution.ageSource,
            dataFresh = input.nowTs - input.latestGlucose.ts <= input.staleMaxMinutes.coerceAtLeast(1) * 60_000L,
            validRecentPointCount = recentValidPointCount(
                glucose = input.glucose,
                latestTs = input.latestGlucose.ts
            ),
            sensorQualityScore = input.sensorQualityScore,
            sensorBlocked = input.sensorBlocked,
            sensorSuspectFalseLow = input.sensorSuspectFalseLow,
            latestInput = input.latestInput
        )

        val baseLagMinutes = when (ageResolution.ageSource) {
            SensorLagAgeSource.MISSING -> DEFAULT_MISSING_AGE_LAG_MINUTES
            else -> lagProfileMinutes(ageResolution.ageHours ?: 0.0)
        }
        val roc = effectiveRoc5m(input.glucose)
        val slopeConfidence = slopeConfidence(input.glucose)

        val activeInferredAge = effectiveMode.mode == SensorLagCorrectionMode.ACTIVE &&
            ageResolution.ageSource == SensorLagAgeSource.INFERRED
        val appliedLagMinutes = when {
            activeInferredAge -> baseLagMinutes.coerceAtMost(INFERRED_ACTIVE_LAG_CAP_MINUTES)
            else -> baseLagMinutes
        }
        val correctionAttenuation = when {
            ageResolution.ageSource == SensorLagAgeSource.MISSING -> MISSING_AGE_CORRECTION_ATTENUATION
            activeInferredAge -> INFERRED_ACTIVE_CORRECTION_ATTENUATION
            else -> 1.0
        }
        val correctionMmol = (
            roc * (appliedLagMinutes / 5.0) * correctionAttenuation
            ).coerceIn(-MAX_CORRECTION_MMOL, MAX_CORRECTION_MMOL)
        val correctedGlucoseMmol = (raw + correctionMmol)
            .coerceIn(MIN_GLUCOSE_MMOL, MAX_GLUCOSE_MMOL)

        val confidence = when (ageResolution.ageSource) {
            SensorLagAgeSource.EXPLICIT -> 0.92
            SensorLagAgeSource.INFERRED -> 0.68
            SensorLagAgeSource.MISSING -> 0.24
        } * slopeConfidence * qualityConfidence(input.sensorQualityScore)

        return SensorLagEstimate(
            rawGlucoseMmol = raw,
            correctedGlucoseMmol = correctedGlucoseMmol,
            lagMinutes = appliedLagMinutes,
            correctionMmol = correctionMmol,
            ageHours = ageResolution.ageHours,
            ageSource = ageResolution.ageSource,
            confidence = confidence.coerceIn(0.0, 1.0),
            mode = effectiveMode.mode,
            disableReason = effectiveMode.disableReason
        )
    }

    internal fun applyForecastBias(
        forecasts: List<Forecast>,
        estimate: SensorLagEstimate
    ): List<Forecast> {
        if (forecasts.isEmpty()) return forecasts
        if (abs(estimate.correctionMmol) < 1e-6) return forecasts

        return forecasts.map { forecast ->
            val shift = estimate.correctionMmol * horizonScale(forecast.horizonMinutes)
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
                modelVersion = if (forecast.modelVersion.contains("|sensor_lag_v1")) {
                    forecast.modelVersion
                } else {
                    "${forecast.modelVersion}|sensor_lag_v1"
                }
            )
        }
    }

    internal fun lagProfileMinutes(ageHours: Double): Double {
        val safeAge = ageHours.coerceAtLeast(0.0)
        return when {
            safeAge <= 24.0 -> 8.0
            safeAge <= 240.0 -> interpolate(
                value = safeAge,
                start = 24.0,
                end = 240.0,
                startValue = 8.0,
                endValue = 10.0
            )
            safeAge <= 336.0 -> interpolate(
                value = safeAge,
                start = 240.0,
                end = 336.0,
                startValue = 10.0,
                endValue = 20.0
            )
            else -> 20.0
        }
    }

    internal fun effectiveRoc5m(glucose: List<GlucosePoint>): Double {
        val canonical = Glucose5mCanonicalizer.build(glucose)
        val points = canonical.points.sortedBy { it.ts }
        if (points.size < 2) return 0.0
        val latest = points.last()
        val slope5 = slopeOverMinutes(points, latest.ts, 5)
        val slope15 = slopeOverMinutes(points, latest.ts, 15)
        val slope30 = slopeOverMinutes(points, latest.ts, 30)
        val weighted = listOfNotNull(
            slope5?.let { 0.5 to it },
            slope15?.let { 0.3 to it },
            slope30?.let { 0.2 to it }
        )
        if (weighted.isEmpty()) return 0.0
        val weightSum = weighted.sumOf { it.first }.coerceAtLeast(1e-6)
        return (weighted.sumOf { it.first * it.second } / weightSum)
            .coerceIn(-MAX_ROC_MMOL_PER_5M, MAX_ROC_MMOL_PER_5M)
    }

    private data class AgeResolution(
        val ageHours: Double?,
        val ageSource: SensorLagAgeSource
    )

    private data class EffectiveMode(
        val mode: SensorLagCorrectionMode,
        val disableReason: String?
    )

    private fun resolveSensorAge(
        nowTs: Long,
        therapy: List<TherapyEvent>,
        glucose: List<GlucosePoint>
    ): AgeResolution {
        val explicitSensorChange = therapy
            .asSequence()
            .filter { event ->
                val type = event.type.lowercase()
                type == "sensor_change" ||
                    type == "cgm_sensor_change" ||
                    type == "sensor_start" ||
                    type == "sensor_started"
            }
            .maxOfOrNull { it.ts }
        if (explicitSensorChange != null) {
            return AgeResolution(
                ageHours = ((nowTs - explicitSensorChange).coerceAtLeast(0L)) / 3_600_000.0,
                ageSource = SensorLagAgeSource.EXPLICIT
            )
        }

        val inferredBoundary = inferSensorSessionBoundary(glucose)
        if (inferredBoundary != null) {
            return AgeResolution(
                ageHours = ((nowTs - inferredBoundary).coerceAtLeast(0L)) / 3_600_000.0,
                ageSource = SensorLagAgeSource.INFERRED
            )
        }
        return AgeResolution(ageHours = null, ageSource = SensorLagAgeSource.MISSING)
    }

    private fun inferSensorSessionBoundary(glucose: List<GlucosePoint>): Long? {
        val sorted = glucose
            .filter { it.quality != io.aaps.copilot.domain.model.DataQuality.SENSOR_ERROR }
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
            point.ts in since..latestTs &&
                point.quality != io.aaps.copilot.domain.model.DataQuality.SENSOR_ERROR
        }
    }

    private fun slopeOverMinutes(points: List<GlucosePoint>, latestTs: Long, minutes: Int): Double? {
        val targetTs = latestTs - minutes * 60_000L
        val point = points.lastOrNull { it.ts <= targetTs } ?: return null
        val delta = points.last().valueMmol - point.valueMmol
        return delta / (minutes / 5.0)
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

    private fun qualityConfidence(score: Double): Double {
        return (0.45 + score.coerceIn(0.0, 1.0) * 0.55).coerceIn(0.45, 1.0)
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

    private const val SESSION_GAP_TRIGGER_MS = 6L * 60L * 60L * 1000L
    private const val STABLE_CADENCE_WINDOW_MS = 90L * 60L * 1000L
    private const val MAX_STABLE_CADENCE_GAP_MS = 10L * 60L * 1000L
    private const val MIN_STABLE_SESSION_POINTS = 16
    private const val ACTIVE_MIN_SENSOR_QUALITY_SCORE = 0.55
    private const val MAX_ROC_MMOL_PER_5M = 0.45
    private const val MAX_CORRECTION_MMOL = 1.5
    private const val DEFAULT_MISSING_AGE_LAG_MINUTES = 10.0
    private const val MISSING_AGE_CORRECTION_ATTENUATION = 0.5
    private const val INFERRED_ACTIVE_LAG_CAP_MINUTES = 12.0
    private const val INFERRED_ACTIVE_CORRECTION_ATTENUATION = 0.5
    private const val MIN_GLUCOSE_MMOL = 2.2
    private const val MAX_GLUCOSE_MMOL = 22.0
}
