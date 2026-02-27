package io.aaps.copilot.domain.model

enum class DayType {
    WEEKDAY,
    WEEKEND
}

enum class ProfileTimeSlot {
    NIGHT,
    MORNING,
    AFTERNOON,
    EVENING
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
    val lookbackDays: Int,
    val telemetryIsfSampleCount: Int = 0,
    val telemetryCrSampleCount: Int = 0,
    val uamObservedCount: Int = 0,
    val uamFilteredIsfSamples: Int = 0,
    val uamEpisodeCount: Int = 0,
    val uamEstimatedCarbsGrams: Double = 0.0,
    val uamEstimatedRecentCarbsGrams: Double = 0.0
)

data class ProfileSegmentEstimate(
    val dayType: DayType,
    val timeSlot: ProfileTimeSlot,
    val isfMmolPerUnit: Double?,
    val crGramPerUnit: Double?,
    val confidence: Double,
    val isfSampleCount: Int,
    val crSampleCount: Int,
    val lookbackDays: Int
)
