package io.aaps.copilot.domain.rules

import io.aaps.copilot.domain.model.DataQuality

/** Evidence gate only. Target Manager remains responsible for permission and delivery. */
internal object SustainedRiseTargetPolicy {
    const val SOURCE = "SustainedRiseTarget.v1"
    const val MODE = "sustained_rise_control"
    const val TARGET_MMOL = 4.1
    const val DURATION_MINUTES = 30
    private const val WINDOW_MS = 10 * 60_000L
    private const val MAX_GAP_MS = 5 * 60_000L

    fun qualifies(context: RuleContext): Boolean {
        if (!context.dataFresh || context.sensorBlocked || !context.actionChronologyResolved || context.nowTs <= 0) return false
        if (TARGET_MMOL !in context.adaptiveMinTargetMmol..context.adaptiveMaxTargetMmol) return false
        val iob = context.safetyIobUnits ?: return false
        if (!iob.isFinite() || iob !in 0.0..30.0) return false
        val current = context.currentGlucoseMmol ?: context.glucose.maxByOrNull { it.ts }?.valueMmol ?: return false
        if (!current.isFinite() || current <= 8.5) return false
        if (context.glucose.any { it.ts > context.nowTs }) return false
        val latestTs = context.glucose.maxOfOrNull { it.ts } ?: return false
        if (latestTs <= WINDOW_MS + MAX_GAP_MS || context.nowTs - latestTs > MAX_GAP_MS) return false
        val recent = context.glucose.filter { it.ts >= latestTs - WINDOW_MS - MAX_GAP_MS }
        if (recent.any { !it.valueMmol.isFinite() || it.valueMmol <= 0.0 || it.quality != DataQuality.OK }) return false
        val grouped = recent.groupBy { it.ts }
        if (grouped.values.any { rows -> rows.any { it.valueMmol != rows.first().valueMmol } }) return false
        val points = grouped.values.map { it.first() }.sortedBy { it.ts }
        val start = points.indexOfLast { it.ts <= latestTs - WINDOW_MS }
        if (start < 0) return false
        val window = points.drop(start)
        if (window.size < 3 || window.zipWithNext().any { (a, b) ->
                b.ts - a.ts > MAX_GAP_MS || b.valueMmol <= a.valueMmol
            }) return false

        val forecasts = listOf(5, 30, 60).map { horizon ->
            context.forecasts.filter { it.horizonMinutes == horizon }.maxByOrNull { it.ts } ?: return false
        }
        if (forecasts.any { !it.valueMmol.isFinite() || !it.ciLow.isFinite() || !it.ciHigh.isFinite() ||
                it.ciLow <= 4.0 || it.ciLow > it.valueMmol || it.valueMmol > it.ciHigh }) return false
        val issued = forecasts.map { it.ts - it.horizonMinutes * 60_000L }
        return issued.distinct().size == 1 && issued.all {
            it > 0 && it <= context.nowTs && context.nowTs - it <= MAX_GAP_MS && it >= latestTs - MAX_GAP_MS
        }
    }
}
