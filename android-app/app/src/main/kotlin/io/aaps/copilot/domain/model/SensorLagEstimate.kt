package io.aaps.copilot.domain.model

import io.aaps.copilot.config.SensorLagCorrectionMode

enum class SensorLagAgeSource {
    DEVICESTATUS,
    EXPLICIT_EVENT,
    INFERRED_BOUNDARY,
    MISSING
}

data class SensorLagEstimate(
    val rawGlucoseMmol: Double,
    val correctedGlucoseMmol: Double,
    val lagMinutes: Double,
    val correctionMmol: Double,
    val ageHours: Double?,
    val ageSource: SensorLagAgeSource,
    val wearBucket: String,
    val sourceConfidence: Double,
    val trendConsistency: Double,
    val replayMultiplier: Double,
    val effectiveLagMinutes: Double,
    val effectiveCorrectionCap: Double,
    val confidence: Double,
    val mode: SensorLagCorrectionMode,
    val disableReason: String?,
    val ageConflictHours: Double? = null
)
