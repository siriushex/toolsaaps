package io.aaps.copilot.ui

import kotlin.math.ceil

private const val MINUTE_MS = 60_000L

enum class IsfCrHistoryWindow(val label: String, val durationMs: Long?) {
    LAST_HOUR("1h", 60L * MINUTE_MS),
    DAY("24h", 24L * 60L * MINUTE_MS),
    WEEK("7d", 7L * 24L * 60L * MINUTE_MS),
    MONTH("30d", 30L * 24L * 60L * MINUTE_MS),
    YEAR("365d", 365L * 24L * 60L * MINUTE_MS),
    ALL("All", null)
}

data class IsfCrHistoryPointUi(
    val timestamp: Long,
    val isfMerged: Double,
    val crMerged: Double,
    val isfCalculated: Double?,
    val crCalculated: Double?
) {
    val isfReal: Double
        get() = isfCalculated ?: isfMerged

    val crReal: Double
        get() = crCalculated ?: crMerged
}

object IsfCrHistoryResolver {

    fun resolve(
        points: List<IsfCrHistoryPointUi>,
        nowTs: Long,
        window: IsfCrHistoryWindow,
        maxPoints: Int = 480
    ): List<IsfCrHistoryPointUi> {
        if (points.isEmpty()) return emptyList()
        val safeMax = maxPoints.coerceAtLeast(2)
        val cutoff = window.durationMs?.let { nowTs - it }
        val sorted = points
            .asSequence()
            .filter { point -> cutoff == null || point.timestamp >= cutoff }
            .sortedBy { it.timestamp }
            .toList()
            .deduplicateByTimestamp()
        if (sorted.size <= safeMax) return sorted

        val stride = ceil(sorted.size.toDouble() / safeMax.toDouble()).toInt().coerceAtLeast(1)
        val sampled = sorted.filterIndexed { index, _ -> index % stride == 0 }.toMutableList()
        val last = sorted.last()
        if (sampled.lastOrNull()?.timestamp != last.timestamp) {
            sampled += last
        }
        if (sampled.size <= safeMax) return sampled
        return sampled.takeLast(safeMax)
    }

    private fun List<IsfCrHistoryPointUi>.deduplicateByTimestamp(): List<IsfCrHistoryPointUi> {
        if (isEmpty()) return this
        val unique = LinkedHashMap<Long, IsfCrHistoryPointUi>(size)
        for (point in this) {
            unique[point.timestamp] = point
        }
        return unique.values.toList()
    }
}
