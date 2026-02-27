package io.aaps.copilot.domain.model

enum class DayType {
    WEEKDAY,
    WEEKEND
}

data class PatternWindow(
    val dayType: DayType,
    val hour: Int,
    val sampleCount: Int,
    val activeDays: Int,
    val lowRate: Double,
    val highRate: Double,
    val recommendedTargetMmol: Double,
    val isRiskWindow: Boolean
)

data class ProfileEstimate(
    val isfMmolPerUnit: Double,
    val crGramPerUnit: Double,
    val confidence: Double,
    val sampleCount: Int,
    val isfSampleCount: Int,
    val crSampleCount: Int,
    val lookbackDays: Int
)
