package io.aaps.copilot.domain.model

import io.aaps.copilot.config.SensorLagCorrectionMode

enum class SensorLagAgeSource {
    EXPLICIT,
    INFERRED,
    MISSING
}

data class SensorLagEstimate(
    val rawGlucoseMmol: Double,
    val correctedGlucoseMmol: Double,
    val lagMinutes: Double,
    val correctionMmol: Double,
    val ageHours: Double?,
    val ageSource: SensorLagAgeSource,
    val confidence: Double,
    val mode: SensorLagCorrectionMode,
    val disableReason: String?
)
