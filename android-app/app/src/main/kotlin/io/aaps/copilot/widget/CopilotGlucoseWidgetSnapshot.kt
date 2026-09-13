package io.aaps.copilot.widget

data class CopilotGlucoseWidgetSnapshot(
    val currentGlucoseMmol: Double?,
    val rawGlucoseMmol: Double?,
    val predicted30Mmol: Double?,
    val iobUnits: Double?,
    val cobGrams: Double?,
    val trendDeltaMmol: Double?,
    val sampleAgeMinutes: Long?,
    val forecastAgeMinutes: Long?,
    val updatedAtMs: Long,
    val calibrationApplied: Boolean
)

data class CopilotGlucoseWidgetText(
    val current: String,
    val predicted30: String,
    val iob: String,
    val cob: String,
    val trend: String,
    val age: String,
    val status: String
)

object CopilotGlucoseWidgetFormatter {
    private const val MISSING = "--"

    fun format(snapshot: CopilotGlucoseWidgetSnapshot): CopilotGlucoseWidgetText {
        val status = when {
            snapshot.currentGlucoseMmol == null -> "No glucose"
            (snapshot.sampleAgeMinutes ?: Long.MAX_VALUE) > 15L -> "Stale"
            snapshot.calibrationApplied -> "Calibrated"
            else -> "Live"
        }
        return CopilotGlucoseWidgetText(
            current = formatMmol(snapshot.currentGlucoseMmol, decimals = 1),
            predicted30 = formatMmol(snapshot.predicted30Mmol, decimals = 1),
            iob = formatUnits(snapshot.iobUnits),
            cob = formatGrams(snapshot.cobGrams),
            trend = formatTrend(snapshot.trendDeltaMmol),
            age = formatAge(snapshot.sampleAgeMinutes),
            status = status
        )
    }

    fun formatTrend(deltaMmol: Double?): String {
        val delta = deltaMmol ?: return "trend $MISSING"
        val arrow = when {
            delta >= 0.30 -> "↑"
            delta >= 0.10 -> "↗"
            delta <= -0.30 -> "↓"
            delta <= -0.10 -> "↘"
            else -> "→"
        }
        return "$arrow ${formatSigned(delta)}"
    }

    private fun formatMmol(value: Double?, decimals: Int): String {
        val safe = value?.takeIf { it.isFinite() } ?: return MISSING
        return "%.${decimals}f".format(java.util.Locale.US, safe)
    }

    private fun formatUnits(value: Double?): String {
        val safe = value?.takeIf { it.isFinite() } ?: return MISSING
        return "%.2f".format(java.util.Locale.US, safe.coerceIn(0.0, 30.0))
    }

    private fun formatGrams(value: Double?): String {
        val safe = value?.takeIf { it.isFinite() } ?: return MISSING
        return "%.0f".format(java.util.Locale.US, safe.coerceIn(0.0, 400.0))
    }

    private fun formatSigned(value: Double): String {
        val sign = if (value >= 0.0) "+" else ""
        return "$sign${"%.1f".format(java.util.Locale.US, value)}"
    }

    private fun formatAge(ageMinutes: Long?): String {
        val age = ageMinutes ?: return "age $MISSING"
        return when {
            age <= 0L -> "now"
            age < 60L -> "${age}m ago"
            else -> "${age / 60L}h ${age % 60L}m ago"
        }
    }
}
