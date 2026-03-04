package io.aaps.copilot.domain.isfcr

import io.aaps.copilot.domain.model.GlucosePoint
import kotlin.math.abs
import kotlin.math.sqrt

data class IsfCrWindowQuality(
    val score: Double,
    val sampleCount: Int,
    val unsafeInterpolationShare: Double,
    val maxGapMinutes: Double,
    val spikeCount: Int,
    val flatlineShare: Double
)

class IsfCrQualityScorer {

    fun scoreWindow(points: List<GlucosePoint>): IsfCrWindowQuality {
        if (points.size < 3) {
            return IsfCrWindowQuality(
                score = 0.0,
                sampleCount = points.size,
                unsafeInterpolationShare = 1.0,
                maxGapMinutes = 999.0,
                spikeCount = 0,
                flatlineShare = 1.0
            )
        }
        val sorted = points.sortedBy { it.ts }
        var unsafeIntervals = 0
        var intervalCount = 0
        var maxGap = 0.0
        val deltas = mutableListOf<Double>()
        sorted.zipWithNext().forEach { (a, b) ->
            val dtMin = (b.ts - a.ts) / 60_000.0
            if (dtMin <= 0.0) return@forEach
            intervalCount += 1
            maxGap = maxOf(maxGap, dtMin)
            if (dtMin !in 2.0..15.0) unsafeIntervals += 1
            deltas += (b.valueMmol - a.valueMmol) / (dtMin / 5.0)
        }
        val unsafeShare = if (intervalCount == 0) 1.0 else unsafeIntervals / intervalCount.toDouble()
        val meanDelta = if (deltas.isEmpty()) 0.0 else deltas.average()
        val variance = if (deltas.size < 2) {
            0.0
        } else {
            deltas.sumOf { (it - meanDelta) * (it - meanDelta) } / (deltas.size - 1)
        }
        val std = sqrt(variance).coerceIn(0.0, 4.0)
        val spikeCount = deltas.count { abs(it) >= 1.5 }
        val flatlineShare = if (deltas.isEmpty()) 0.0 else deltas.count { abs(it) <= 0.02 } / deltas.size.toDouble()

        var score = 1.0
        score -= unsafeShare * 0.35
        score -= ((maxGap - 10.0).coerceAtLeast(0.0) / 30.0).coerceAtMost(0.35)
        score -= (std / 2.5).coerceAtMost(0.2)
        score -= (spikeCount / deltas.size.coerceAtLeast(1).toDouble()).coerceAtMost(0.2)
        score -= (flatlineShare * 0.1)

        return IsfCrWindowQuality(
            score = score.coerceIn(0.0, 1.0),
            sampleCount = points.size,
            unsafeInterpolationShare = unsafeShare,
            maxGapMinutes = maxGap,
            spikeCount = spikeCount,
            flatlineShare = flatlineShare
        )
    }
}

