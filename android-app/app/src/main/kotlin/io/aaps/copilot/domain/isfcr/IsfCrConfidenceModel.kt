package io.aaps.copilot.domain.isfcr

import io.aaps.copilot.domain.predict.TelemetrySignal
import kotlin.math.abs

data class IsfCrConfidenceOutput(
    val confidence: Double,
    val qualityScore: Double,
    val ciIsfLow: Double,
    val ciIsfHigh: Double,
    val ciCrLow: Double,
    val ciCrHigh: Double
)

class IsfCrConfidenceModel {

    fun evaluate(
        isfEff: Double,
        crEff: Double,
        evidence: List<IsfCrEvidenceSample>,
        telemetry: List<TelemetrySignal>,
        factors: Map<String, Double> = emptyMap()
    ): IsfCrConfidenceOutput {
        val isfEvidence = evidence.count { it.sampleType == IsfCrSampleType.ISF }
        val crEvidence = evidence.count { it.sampleType == IsfCrSampleType.CR }
        val quality = evidence.map { it.qualityScore }.average().takeIf { !it.isNaN() } ?: 0.0

        val latestTelemetry = telemetry.groupBy { normalizeKey(it.key) }
            .mapValues { it.value.maxByOrNull { s -> s.ts }?.valueDouble }
        val sensorQuality = (latestTelemetry["sensor_quality_score"] ?: 1.0).coerceIn(0.0, 1.0)
        val uamActive = (latestTelemetry["uam_value"] ?: 0.0).coerceIn(0.0, 1.0)
        val noiseStd = abs(latestTelemetry["sensor_quality_noise_std5"] ?: 0.0).coerceIn(0.0, 2.0)
        val setAgeHours = (factors["set_age_hours"] ?: latestTelemetry["set_age_hours"] ?: 0.0).coerceAtLeast(0.0)
        val sensorAgeHours = (factors["sensor_age_hours"] ?: latestTelemetry["sensor_age_hours"] ?: 0.0).coerceAtLeast(0.0)
        val isfHourEvidenceEnough = (factors["isf_hour_window_evidence_enough"] ?: 0.0) >= 0.5
        val crHourEvidenceEnough = (factors["cr_hour_window_evidence_enough"] ?: 0.0) >= 0.5
        val isfStrongGlobalEvidence = (factors["isf_global_evidence_strong"] ?: 0.0) >= 0.5
        val crStrongGlobalEvidence = (factors["cr_global_evidence_strong"] ?: 0.0) >= 0.5
        val isfSameDayRatio = (factors["isf_hour_window_same_day_type_ratio"] ?: 0.0).coerceIn(0.0, 1.0)
        val crSameDayRatio = (factors["cr_hour_window_same_day_type_ratio"] ?: 0.0).coerceIn(0.0, 1.0)
        val manualStressTag = (factors["manual_stress_tag"] ?: 0.0).coerceIn(0.0, 1.0)
        val manualIllnessTag = (factors["manual_illness_tag"] ?: 0.0).coerceIn(0.0, 1.0)
        val manualHormoneTag = (factors["manual_hormone_tag"] ?: 0.0).coerceIn(0.0, 1.0)
        val manualSteroidTag = (factors["manual_steroid_tag"] ?: 0.0).coerceIn(0.0, 1.0)
        val manualDawnTag = (factors["manual_dawn_tag"] ?: 0.0).coerceIn(0.0, 1.0)
        val latentStress = (factors["latent_stress"] ?: latestTelemetry["stress_score"] ?: 0.0).coerceIn(0.0, 1.0)
        val falseLowFlag = (latestTelemetry["sensor_quality_suspect_false_low"] ?: 0.0).coerceIn(0.0, 1.0)
        val contextAmbiguity = maxOf(
            uamActive,
            latentStress,
            manualStressTag,
            manualIllnessTag,
            manualHormoneTag,
            manualSteroidTag,
            manualDawnTag
        ).coerceIn(0.0, 1.0)

        val setAgePenalty = if (setAgeHours <= 72.0) 0.0 else ((setAgeHours - 72.0) / 96.0).coerceIn(0.0, 1.0) * 0.08
        val sensorAgePenalty = if (sensorAgeHours <= 120.0) 0.0 else ((sensorAgeHours - 120.0) / 96.0).coerceIn(0.0, 1.0) * 0.08
        val falseLowPenalty = falseLowFlag * 0.08

        val isfVolumeScore = evidenceCoverageScore(
            evidenceCount = isfEvidence,
            fullCount = when {
                isfHourEvidenceEnough -> 3.0
                isfStrongGlobalEvidence -> 4.0
                else -> 6.0
            }
        )
        val crVolumeScore = evidenceCoverageScore(
            evidenceCount = crEvidence,
            fullCount = when {
                crHourEvidenceEnough -> 4.0
                crStrongGlobalEvidence -> 5.0
                else -> 8.0
            }
        )
        val volumeScore = (
            isfVolumeScore * 0.5 +
                crVolumeScore * 0.5
            )
            .coerceIn(0.0, 1.0)
        val structuralSupport = (
            (if (isfHourEvidenceEnough) 0.03 else 0.0) +
                (if (crHourEvidenceEnough) 0.03 else 0.0) +
                (if (isfStrongGlobalEvidence) 0.04 else 0.0) +
                (if (crStrongGlobalEvidence) 0.04 else 0.0) +
                isfSameDayRatio * 0.02 +
                crSameDayRatio * 0.02
            )
            .coerceIn(0.0, 0.18)
        val metricCoverageRatio = (
            (if (isfEvidence > 0) 0.5 else 0.0) +
                (if (crEvidence > 0) 0.5 else 0.0)
            )
            .coerceIn(0.0, 1.0)
        val crossMetricPenalty = if (isfEvidence == 0 || crEvidence == 0) 0.15 else 0.0
        val qualityScore = (
            quality * 0.50 +
                sensorQuality * 0.30 +
                (1.0 - noiseStd / 2.0) * 0.15 +
                (1.0 - contextAmbiguity * 0.5) * 0.05 -
                setAgePenalty * 0.5 -
                sensorAgePenalty * 0.5 -
                falseLowPenalty
            )
            .coerceIn(0.0, 1.0)
        val confidence = (
            volumeScore * 0.46 +
                qualityScore * 0.32 +
                structuralSupport * metricCoverageRatio +
                (1.0 - uamActive * 0.5) * 0.08 +
                (1.0 - contextAmbiguity * 0.5) * 0.06 -
                crossMetricPenalty -
                setAgePenalty -
                sensorAgePenalty -
                falseLowPenalty
            )
            .coerceIn(0.0, 0.99)

        val isfHalf = (
            isfEff * (
                0.08 +
                    (1.0 - confidence) * 0.45 +
                    uamActive * 0.08 +
                    contextAmbiguity * 0.06 +
                    noiseStd * 0.05 +
                    setAgePenalty * 1.8 +
                    sensorAgePenalty * 1.8 +
                    falseLowPenalty * 1.6
                )
            )
            .coerceIn(0.15, 4.0)
        val crHalf = (
            crEff * (
                0.10 +
                    (1.0 - confidence) * 0.50 +
                    uamActive * 0.06 +
                    contextAmbiguity * 0.05 +
                    noiseStd * 0.04 +
                    setAgePenalty * 1.5 +
                    sensorAgePenalty * 1.5 +
                    falseLowPenalty * 1.4
                )
            )
            .coerceIn(0.5, 12.0)

        return IsfCrConfidenceOutput(
            confidence = confidence,
            qualityScore = qualityScore,
            ciIsfLow = (isfEff - isfHalf).coerceAtLeast(0.8),
            ciIsfHigh = (isfEff + isfHalf).coerceAtMost(18.0),
            ciCrLow = (crEff - crHalf).coerceAtLeast(2.0),
            ciCrHigh = (crEff + crHalf).coerceAtMost(60.0)
        )
    }

    private fun normalizeKey(value: String): String {
        return value
            .replace(Regex("([a-z0-9])([A-Z])"), "$1_$2")
            .lowercase()
            .replace(Regex("[^a-z0-9]+"), "_")
            .trim('_')
    }

    private fun evidenceCoverageScore(
        evidenceCount: Int,
        fullCount: Double
    ): Double {
        if (evidenceCount <= 0 || fullCount <= 0.0) return 0.0
        return (evidenceCount / fullCount).coerceIn(0.0, 1.0)
    }
}
