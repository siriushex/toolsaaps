package io.aaps.copilot.ui.foundation.format

import java.util.Locale
import kotlin.math.abs

object UiFormatters {

    fun formatMmol(value: Double?, decimals: Int = 2): String =
        formatDecimal(value = value, decimals = decimals)

    fun formatUnits(value: Double?, decimals: Int = 1): String =
        formatDecimal(value = value, decimals = decimals)

    fun formatGrams(value: Double?, decimals: Int = 1): String =
        formatDecimal(value = value, decimals = decimals)

    fun formatPercent(value: Double?, decimals: Int = 0): String {
        val numeric = value ?: return "--"
        return String.format(Locale.US, "%.${decimals}f%%", numeric * 100.0)
    }

    fun formatSignedDelta(value: Double?, decimals: Int = 2): String {
        val numeric = value ?: return "--"
        return String.format(Locale.US, "%+.${decimals}f", numeric)
    }

    fun formatMinutes(minutes: Long?): String {
        val value = minutes ?: return "--"
        return if (value < 1L) "<1 min" else "$value min"
    }

    fun formatDecimalOrPlaceholder(value: Double?, decimals: Int): String {
        val numeric = value ?: return "--"
        return String.format(Locale.US, "%.${decimals}f", numeric)
    }

    fun formatTimestamp(ts: Long?): String {
        return if (ts == null || ts <= 0L) "--" else java.time.Instant.ofEpochMilli(ts)
            .atZone(java.time.ZoneId.systemDefault())
            .toLocalTime()
            .withSecond(0)
            .withNano(0)
            .toString()
    }

    fun minutesSince(nowTs: Long, ts: Long?): Long? {
        if (ts == null || ts <= 0L) return null
        val diff = nowTs - ts
        if (diff < 0L) return 0L
        return diff / 60_000L
    }

    fun hasWideCi(ciLow: Double?, ciHigh: Double?, widthThreshold: Double = 2.0): Boolean {
        val low = ciLow ?: return false
        val high = ciHigh ?: return false
        if (high < low) return true
        return abs(high - low) >= widthThreshold
    }

    private fun formatDecimal(value: Double?, decimals: Int): String {
        val numeric = value ?: return "--"
        return String.format(Locale.US, "%.${decimals}f", numeric)
    }
}
