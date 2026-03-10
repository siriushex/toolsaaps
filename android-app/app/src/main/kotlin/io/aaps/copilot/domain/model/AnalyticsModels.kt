package io.aaps.copilot.domain.model

enum class DayType {
    WEEKDAY,
    WEEKEND
}

enum class CircadianDayType {
    WEEKDAY,
    WEEKEND,
    ALL
}

enum class CircadianReplayBucketStatus {
    HELPFUL,
    NEUTRAL,
    HARMFUL,
    INSUFFICIENT
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

data class CircadianSlotStat(
    val dayType: CircadianDayType,
    val windowDays: Int,
    val slotIndex: Int,
    val sampleCount: Int,
    val activeDays: Int,
    val medianBg: Double,
    val p10: Double,
    val p25: Double,
    val p75: Double,
    val p90: Double,
    val pLow: Double,
    val pHigh: Double,
    val pInRange: Double,
    val fastRiseRate: Double,
    val fastDropRate: Double,
    val meanCob: Double?,
    val meanIob: Double?,
    val meanUam: Double?,
    val meanActivity: Double?,
    val confidence: Double,
    val qualityScore: Double,
    val updatedAt: Long
)

data class CircadianTransitionStat(
    val dayType: CircadianDayType,
    val windowDays: Int,
    val slotIndex: Int,
    val horizonMinutes: Int,
    val sampleCount: Int,
    val deltaMedian: Double,
    val deltaP25: Double,
    val deltaP75: Double,
    val residualBiasMmol: Double,
    val confidence: Double,
    val updatedAt: Long
)

data class CircadianPatternSnapshot(
    val requestedDayType: CircadianDayType,
    val segmentSource: CircadianDayType,
    val stableWindowDays: Int,
    val recencyWindowDays: Int,
    val recencyWeight: Double,
    val coverageDays: Int,
    val sampleCount: Int,
    val segmentFallback: Boolean,
    val fallbackReason: String?,
    val confidence: Double,
    val qualityScore: Double,
    val updatedAt: Long
)

data class CircadianReplaySlotStat(
    val dayType: CircadianDayType,
    val windowDays: Int,
    val slotIndex: Int,
    val horizonMinutes: Int,
    val sampleCount: Int,
    val coverageDays: Int,
    val maeBaseline: Double,
    val maeCircadian: Double,
    val maeImprovementMmol: Double,
    val medianSignedErrorBaseline: Double,
    val medianSignedErrorCircadian: Double,
    val winRate: Double,
    val qualityScore: Double,
    val updatedAt: Long
)

data class CircadianForecastPrior(
    val requestedDayType: CircadianDayType,
    val segmentSource: CircadianDayType,
    val slotIndex: Int,
    val bgMedian: Double,
    val slotP10: Double,
    val slotP25: Double,
    val slotP75: Double,
    val slotP90: Double,
    val delta15: Double,
    val delta30: Double,
    val delta60: Double,
    val residualBias30: Double,
    val residualBias60: Double,
    val medianReversion30: Double,
    val medianReversion60: Double,
    val replayBias30: Double,
    val replayBias60: Double,
    val replaySampleCount30: Int,
    val replaySampleCount60: Int,
    val replayWinRate30: Double,
    val replayWinRate60: Double,
    val replayMaeImprovement30: Double,
    val replayMaeImprovement60: Double,
    val replayBucketStatus30: CircadianReplayBucketStatus,
    val replayBucketStatus60: CircadianReplayBucketStatus,
    val confidence: Double,
    val qualityScore: Double,
    val stabilityScore: Double,
    val horizonQuality30: Double,
    val horizonQuality60: Double,
    val acuteAttenuation: Double,
    val staleBlocked: Boolean
)
