package io.aaps.copilot.domain.predict

import io.aaps.copilot.domain.model.DayType
import io.aaps.copilot.domain.model.GlucosePoint
import io.aaps.copilot.domain.model.PatternWindow
import java.time.Instant
import java.time.ZoneId
import kotlin.math.max
import kotlin.math.min

class PatternAnalyzer(
    private val zoneId: ZoneId = ZoneId.systemDefault()
) {

    fun analyze(
        glucoseHistory: List<GlucosePoint>,
        baseTargetMmol: Double,
        lowThresholdMmol: Double = 3.9,
        highThresholdMmol: Double = 10.0
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
            val lows = points.count { it.valueMmol <= lowThresholdMmol } / total
            val highs = points.count { it.valueMmol >= highThresholdMmol } / total
            val adaptiveTarget = buildTarget(baseTargetMmol, lows, highs)

            PatternWindow(
                dayType = dayType,
                hour = hour,
                lowRate = lows,
                highRate = highs,
                recommendedTargetMmol = adaptiveTarget
            )
        }.sortedWith(compareBy<PatternWindow> { it.dayType.name }.thenBy { it.hour })
    }

    private fun buildTarget(baseTarget: Double, lowRate: Double, highRate: Double): Double {
        val adjusted = when {
            lowRate >= 0.20 -> baseTarget + 0.5
            lowRate >= 0.10 -> baseTarget + 0.3
            highRate >= 0.25 && lowRate < 0.05 -> baseTarget - 0.3
            highRate >= 0.15 && lowRate < 0.05 -> baseTarget - 0.2
            else -> baseTarget
        }
        return min(8.0, max(4.4, adjusted))
    }
}
