package io.aaps.copilot.ui

import kotlin.math.roundToInt

internal fun formatIsfCrDroppedReasonSummaryLines(
    eventCount: Int,
    droppedTotal: Int,
    reasonCounts: Map<String, Int>,
    topLimit: Int = 8
): List<String> {
    val topReasons = reasonCounts.entries
        .sortedByDescending { it.value }
        .take(topLimit)

    val header = buildString {
        append("Events=$eventCount, dropped total=$droppedTotal")
        if (topReasons.isEmpty()) append(", no reason counters")
    }

    if (topReasons.isEmpty()) {
        return listOf(header)
    }

    val crGap = reasonCounts["cr_gross_gap"] ?: 0
    val crSensorBlocked = reasonCounts["cr_sensor_blocked"] ?: 0
    val crUamAmbiguity = reasonCounts["cr_uam_ambiguity"] ?: 0
    val hasCrIntegrityCounts = crGap > 0 || crSensorBlocked > 0 || crUamAmbiguity > 0

    fun pct(value: Int, denominator: Int): Int {
        if (denominator <= 0) return 0
        return ((value * 100.0) / denominator).roundToInt()
    }

    return buildList {
        add(header)
        if (hasCrIntegrityCounts) {
            add(
                "CR integrity drops: gap=${pct(crGap, droppedTotal)}% ($crGap), " +
                    "sensorBlocked=${pct(crSensorBlocked, droppedTotal)}% ($crSensorBlocked), " +
                    "uamAmbiguity=${pct(crUamAmbiguity, droppedTotal)}% ($crUamAmbiguity)"
            )
        }
        topReasons.forEach { (reason, count) -> add("$reason=$count") }
    }
}
