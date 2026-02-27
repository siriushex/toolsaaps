package io.aaps.copilot.domain.model

enum class DayType {
    WEEKDAY,
    WEEKEND
}

data class PatternWindow(
    val dayType: DayType,
    val hour: Int,
    val lowRate: Double,
    val highRate: Double,
    val recommendedTargetMmol: Double
)

data class ProfileEstimate(
    val isfMmolPerUnit: Double,
    val crGramPerUnit: Double,
    val confidence: Double,
    val sampleCount: Int
)
