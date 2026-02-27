package io.aaps.copilot.domain.predict

import io.aaps.copilot.domain.model.DayType
import io.aaps.copilot.domain.model.GlucosePoint
import io.aaps.copilot.domain.model.PatternWindow
import java.time.Instant
import java.time.ZoneId
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

data class PatternAnalyzerConfig(
    val baseTargetMmol: Double,
    val lowThresholdMmol: Double = 3.9,
    val highThresholdMmol: Double = 10.0,
    val minSamplesPerWindow: Int = 40,
    val minActiveDaysPerWindow: Int = 7,
    val lowRateTrigger: Double = 0.12,
    val highRateTrigger: Double = 0.18
)

class PatternAnalyzer(
    private val zoneId: ZoneId = ZoneId.systemDefault()
) {

    fun analyze(
        glucoseHistory: List<GlucosePoint>,
        config: PatternAnalyzerConfig
    ): List<PatternWindow> {
        if (glucoseHistory.isEmpty()) return emptyList()
        val grouped = glucoseHistory.groupBy { point ->
            val dateTime = Instant.ofEpochMilli(point.ts).atZone(zoneId)
            val dayType = when (dateTime.dayOfWeek.value) {
                6, 7 -> DayType.WEEKEND
                else -> DayType.WEEKDAY
            }
            dayType to dateTime.hour
        }

        return grouped.map { (key, points) ->
            val (dayType, hour) = key
            val total = points.size.toDouble().coerceAtLeast(1.0)
            val sampleCount = points.size
            val activeDays = points.asSequence()
                .map { Instant.ofEpochMilli(it.ts).atZone(zoneId).toLocalDate() }
                .distinct()
                .count()
            val lows = points.count { it.valueMmol <= config.lowThresholdMmol } / total
            val highs = points.count { it.valueMmol >= config.highThresholdMmol } / total
            val hasEvidence = sampleCount >= config.minSamplesPerWindow && activeDays >= config.minActiveDaysPerWindow
            val isRiskWindow = hasEvidence && (lows >= config.lowRateTrigger || highs >= config.highRateTrigger)
            val adaptiveTarget = buildTarget(config, lows, highs, isRiskWindow)

            PatternWindow(
                dayType = dayType,
                hour = hour,
                sampleCount = sampleCount,
                activeDays = activeDays,
                lowRate = lows,
                highRate = highs,
                recommendedTargetMmol = adaptiveTarget,
                isRiskWindow = isRiskWindow
            )
        }.sortedWith(compareBy<PatternWindow> { it.dayType.name }.thenBy { it.hour })
    }

    private fun buildTarget(
        config: PatternAnalyzerConfig,
        lowRate: Double,
        highRate: Double,
        isRiskWindow: Boolean
    ): Double {
        if (!isRiskWindow) return config.baseTargetMmol
        val baseTarget = config.baseTargetMmol
        val lowSevere = max(config.lowRateTrigger + 0.16, 0.30)
        val lowModerate = config.lowRateTrigger + 0.08
        val highSevere = max(config.highRateTrigger + 0.18, 0.35)
        val highModerate = config.highRateTrigger + 0.10
        val adjusted = when {
            lowRate >= lowSevere -> baseTarget + 0.6
            lowRate >= lowModerate -> baseTarget + 0.4
            lowRate >= config.lowRateTrigger -> baseTarget + 0.25
            highRate >= highSevere && lowRate < config.lowRateTrigger * 0.5 -> baseTarget - 0.45
            highRate >= highModerate && lowRate < config.lowRateTrigger * 0.5 -> baseTarget - 0.30
            highRate >= config.highRateTrigger && lowRate < config.lowRateTrigger * 0.6 -> baseTarget - 0.20
            else -> baseTarget
        }
        return roundToStep(min(8.0, max(4.4, adjusted)), step = 0.05)
    }

    private fun roundToStep(value: Double, step: Double): Double {
        val scaled = value / step
        return floor(scaled + 0.5) * step
    }
}
