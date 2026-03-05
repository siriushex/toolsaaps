package io.aaps.copilot.ui

import kotlin.math.ceil

private const val MINUTE_MS = 60_000L
private const val ISF_MIN = 0.2
private const val ISF_MAX = 18.0
private const val CR_MIN = 2.0
private const val CR_MAX = 60.0

enum class IsfCrHistoryWindow(val label: String, val durationMs: Long?) {
    LAST_12H("12h", 12L * 60L * MINUTE_MS),
    LAST_24H("24h", 24L * 60L * MINUTE_MS),
    LAST_3D("3d", 3L * 24L * 60L * MINUTE_MS),
    LAST_7D("7d", 7L * 24L * 60L * MINUTE_MS),
    LAST_30D("30d", 30L * 24L * 60L * MINUTE_MS)
}

data class IsfCrHistoryPointUi(
    val timestamp: Long,
    val isfMerged: Double,
    val crMerged: Double,
    val isfCalculated: Double?,
    val crCalculated: Double?,
    val isfAaps: Double? = null,
    val crAaps: Double? = null
) {
    val isfRealStrict: Double?
        get() = isfCalculated

    val crRealStrict: Double?
        get() = crCalculated

    val isfAapsStrict: Double?
        get() = isfAaps

    val crAapsStrict: Double?
        get() = crAaps

    val isfReal: Double
        get() = isfCalculated ?: isfMerged

    val crReal: Double
        get() = crCalculated ?: crMerged

    val isfAapsResolved: Double
        get() = isfAaps ?: isfMerged

    val crAapsResolved: Double
        get() = crAaps ?: crMerged
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
        val sortedSource = points
            .asSequence()
            .mapNotNull { sanitizePoint(it) }
            .sortedBy { it.timestamp }
            .toList()
        if (sortedSource.isEmpty()) return emptyList()

        val withinWindow = sortedSource
            .asSequence()
            .filter { point -> cutoff == null || point.timestamp >= cutoff }
            .toList()

        // Add one anchor point before the selected window so 3d/7d charts are continuous at the left edge.
        val withAnchor = if (cutoff != null && (window.durationMs ?: 0L) >= 3L * 24L * 60L * MINUTE_MS) {
            val anchor = sortedSource.lastOrNull { it.timestamp < cutoff }
            if (anchor != null) listOf(anchor) + withinWindow else withinWindow
        } else {
            withinWindow
        }

        val sorted = withAnchor.deduplicateByTimestamp()
        if (sorted.size <= safeMax) return sorted

        val stride = ceil(sorted.size.toDouble() / safeMax.toDouble()).toInt().coerceAtLeast(1)
        val sampled = sorted.filterIndexed { index, _ ->
            index == 0 || index == sorted.lastIndex || index % stride == 0
        }.toMutableList()
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
            val existing = unique[point.timestamp]
            if (existing == null || point.qualityScore() >= existing.qualityScore()) {
                unique[point.timestamp] = point
            }
        }
        return unique.values.toList()
    }

    private fun IsfCrHistoryPointUi.qualityScore(): Int {
        var score = 0
        if (isfCalculated != null) score += 2
        if (crCalculated != null) score += 2
        if (isfAaps != null) score += 1
        if (crAaps != null) score += 1
        return score
    }

    private fun sanitizePoint(point: IsfCrHistoryPointUi): IsfCrHistoryPointUi? {
        if (point.timestamp <= 0L) return null
        val isfMerged = point.isfMerged.takeIf { it.isFinite() && it in ISF_MIN..ISF_MAX } ?: return null
        val crMerged = point.crMerged.takeIf { it.isFinite() && it in CR_MIN..CR_MAX } ?: return null
        val isfCalculated = point.isfCalculated?.takeIf { it.isFinite() && it in ISF_MIN..ISF_MAX }
        val crCalculated = point.crCalculated?.takeIf { it.isFinite() && it in CR_MIN..CR_MAX }
        val isfAaps = point.isfAaps?.takeIf { it.isFinite() && it in ISF_MIN..ISF_MAX }
        val crAaps = point.crAaps?.takeIf { it.isFinite() && it in CR_MIN..CR_MAX }
        return point.copy(
            isfMerged = isfMerged,
            crMerged = crMerged,
            isfCalculated = isfCalculated,
            crCalculated = crCalculated,
            isfAaps = isfAaps,
            crAaps = crAaps
        )
    }
}
