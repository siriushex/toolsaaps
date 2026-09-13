package io.aaps.copilot.ui.foundation.components

import io.aaps.copilot.ui.foundation.screens.ChartCiPointUi
import io.aaps.copilot.ui.foundation.screens.ChartPointUi

private const val CLINICAL_CHART_MAX_BOUNDARY_SCAN_POINTS = 32

/**
 * Selects finite chart points from a random-access list sorted by ascending timestamp.
 *
 * The sorted, random-access input contract keeps selection O(log n + visible n + constant).
 * One usable boundary per side is retained when found within the fixed scan budget; more
 * distant boundaries are deliberately omitted to keep gesture work bounded.
 */
internal fun selectClinicalChartPoints(
    points: List<ChartPointUi>,
    startTs: Long,
    endTs: Long
): List<ChartPointUi> = selectWithBoundaryPoints(
    points = points,
    startTs = startTs,
    endTs = endTs,
    timestamp = ChartPointUi::ts,
    isFinite = { it.value.isFinite() }
)

/**
 * Selects finite confidence-interval points from a random-access list sorted by timestamp.
 *
 * The sorted, random-access input contract keeps selection O(log n + visible n + constant).
 * One usable boundary per side is retained when found within the fixed scan budget; more
 * distant boundaries are deliberately omitted to keep gesture work bounded.
 */
internal fun selectClinicalChartCiPoints(
    points: List<ChartCiPointUi>,
    startTs: Long,
    endTs: Long
): List<ChartCiPointUi> = selectWithBoundaryPoints(
    points = points,
    startTs = startTs,
    endTs = endTs,
    timestamp = ChartCiPointUi::ts,
    isFinite = { it.low.isFinite() && it.high.isFinite() }
)

/** Maps a timestamp to a clamped pixel column without overflowing [Long] arithmetic. */
internal fun clinicalChartPixelColumn(
    ts: Long,
    startTs: Long,
    endTs: Long,
    pixelColumns: Int
): Int {
    if (pixelColumns <= 0 || endTs <= startTs) return 0
    if (ts <= startTs) return 0
    if (ts >= endTs) return pixelColumns - 1
    val duration = (endTs.toULong() - startTs.toULong()).toDouble()
    val offset = (ts.toULong() - startTs.toULong()).toDouble()
    val ratio = (offset / duration).coerceIn(0.0, 1.0)
    return (ratio * pixelColumns)
        .toInt()
        .coerceIn(0, pixelColumns - 1)
}

/**
 * Reduces a timestamp-sorted point list to at most its minimum and maximum per pixel column.
 */
internal fun reduceClinicalChartPoints(
    points: List<ChartPointUi>,
    startTs: Long,
    endTs: Long,
    pixelColumns: Int
): List<ChartPointUi> {
    val selected = selectClinicalChartPoints(points, startTs, endTs)
    val columns = pixelColumns.coerceAtLeast(1)
    val maximumOutputSize = (columns.toLong() * 2L + 2L)
        .coerceAtMost(Int.MAX_VALUE.toLong())
        .toInt()
    val reduced = ArrayList<ChartPointUi>(minOf(selected.size, maximumOutputSize))
    var rightBoundary: ChartPointUi? = null
    var currentColumn = -1
    var currentMin: ChartPointUi? = null
    var currentMax: ChartPointUi? = null

    for (point in selected) {
        when {
            point.ts < startTs -> reduced.add(point)
            point.ts > endTs -> if (rightBoundary == null) rightBoundary = point
            else -> {
                val column = clinicalChartPixelColumn(point.ts, startTs, endTs, columns)
                if (column != currentColumn) {
                    currentMin?.let { minimum ->
                        reduced.addClinicalChartBucketExtrema(minimum, requireNotNull(currentMax))
                    }
                    currentColumn = column
                    currentMin = point
                    currentMax = point
                } else {
                    if (point.value < currentMin!!.value) currentMin = point
                    if (point.value > currentMax!!.value) currentMax = point
                }
            }
        }
    }

    currentMin?.let { minimum ->
        reduced.addClinicalChartBucketExtrema(minimum, requireNotNull(currentMax))
    }
    rightBoundary?.let(reduced::add)
    return reduced
}

private fun MutableList<ChartPointUi>.addClinicalChartBucketExtrema(
    minimum: ChartPointUi,
    maximum: ChartPointUi
) {
    if (minimum.ts <= maximum.ts) {
        add(minimum)
        if (minimum !== maximum) add(maximum)
    } else {
        add(maximum)
        add(minimum)
    }
}

private inline fun <T> selectWithBoundaryPoints(
    points: List<T>,
    startTs: Long,
    endTs: Long,
    timestamp: (T) -> Long,
    isFinite: (T) -> Boolean
): List<T> {
    if (points.isEmpty() || endTs < startTs) return emptyList()
    val firstInside = lowerBound(points, startTs, timestamp)
    val afterLastInside = upperBound(points, endTs, timestamp)
    return buildList {
        var index = firstInside - 1
        var scanned = 0
        while (index >= 0 && scanned < CLINICAL_CHART_MAX_BOUNDARY_SCAN_POINTS) {
            val point = points[index]
            scanned += 1
            if (isFinite(point)) {
                add(point)
                break
            }
            index -= 1
        }
        for (insideIndex in firstInside until afterLastInside) {
            val point = points[insideIndex]
            if (isFinite(point)) add(point)
        }
        index = afterLastInside
        scanned = 0
        while (index < points.size && scanned < CLINICAL_CHART_MAX_BOUNDARY_SCAN_POINTS) {
            val point = points[index]
            scanned += 1
            if (isFinite(point)) {
                add(point)
                break
            }
            index += 1
        }
    }
}

private inline fun <T> lowerBound(
    points: List<T>,
    targetTs: Long,
    timestamp: (T) -> Long
): Int {
    var low = 0
    var high = points.size
    while (low < high) {
        val middle = (low + high) ushr 1
        if (timestamp(points[middle]) < targetTs) low = middle + 1 else high = middle
    }
    return low
}

private inline fun <T> upperBound(
    points: List<T>,
    targetTs: Long,
    timestamp: (T) -> Long
): Int {
    var low = 0
    var high = points.size
    while (low < high) {
        val middle = (low + high) ushr 1
        if (timestamp(points[middle]) <= targetTs) low = middle + 1 else high = middle
    }
    return low
}
