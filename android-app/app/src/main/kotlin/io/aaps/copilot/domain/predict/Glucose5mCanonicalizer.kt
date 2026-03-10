package io.aaps.copilot.domain.predict

import io.aaps.copilot.domain.model.DataQuality
import io.aaps.copilot.domain.model.GlucosePoint
import kotlin.math.abs

internal data class CanonicalGlucoseSeries(
    val points: List<GlucosePoint>,
    val observedCount: Int,
    val interpolatedCount: Int,
    val anchorTs: Long
)

internal object Glucose5mCanonicalizer {

    fun build(raw: List<GlucosePoint>): CanonicalGlucoseSeries {
        val cleaned = raw
            .asSequence()
            .filter { it.quality != DataQuality.SENSOR_ERROR }
            .sortedBy { it.ts }
            .fold(linkedMapOf<Long, GlucosePoint>()) { acc, point ->
                acc[point.ts] = point
                acc
            }
            .values
            .toList()
        if (cleaned.isEmpty()) {
            return CanonicalGlucoseSeries(
                points = emptyList(),
                observedCount = 0,
                interpolatedCount = 0,
                anchorTs = 0L
            )
        }

        val anchorTs = cleaned.last().ts
        val minTs = maxOf(cleaned.first().ts, anchorTs - MAX_LOOKBACK_MS)
        val scoped = cleaned.filter { it.ts >= minTs }
        if (scoped.size == 1) {
            return CanonicalGlucoseSeries(
                points = scoped,
                observedCount = 1,
                interpolatedCount = 0,
                anchorTs = anchorTs
            )
        }

        val out = mutableListOf<GlucosePoint>()
        var observedCount = 0
        var interpolatedCount = 0
        var targetTs = anchorTs
        while (targetTs >= scoped.first().ts) {
            val bucketPoints = scoped.filter { abs(it.ts - targetTs) <= HALF_WINDOW_MS }
            val canonical = if (bucketPoints.isNotEmpty()) {
                observedCount += 1
                GlucosePoint(
                    ts = targetTs,
                    valueMmol = median(bucketPoints.map { it.valueMmol }),
                    source = bucketPoints.last().source,
                    quality = DataQuality.OK
                )
            } else {
                interpolate(scoped, targetTs)?.also { interpolatedCount += 1 }
            }
            if (canonical != null) {
                out += canonical
            }
            targetTs -= STEP_MS
        }

        return CanonicalGlucoseSeries(
            points = out.asReversed(),
            observedCount = observedCount,
            interpolatedCount = interpolatedCount,
            anchorTs = anchorTs
        )
    }

    private fun interpolate(points: List<GlucosePoint>, targetTs: Long): GlucosePoint? {
        val prev = points.lastOrNull { it.ts < targetTs } ?: return null
        val next = points.firstOrNull { it.ts > targetTs } ?: return null
        val gapMs = next.ts - prev.ts
        if (gapMs <= 0L || gapMs > MAX_INTERPOLATION_GAP_MS) return null
        val ratio = (targetTs - prev.ts).toDouble() / gapMs.toDouble()
        val value = prev.valueMmol + (next.valueMmol - prev.valueMmol) * ratio
        return GlucosePoint(
            ts = targetTs,
            valueMmol = value,
            source = "canonical_5m_interp",
            quality = DataQuality.OK
        )
    }

    private fun median(values: List<Double>): Double {
        if (values.isEmpty()) return 0.0
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 0) {
            (sorted[mid - 1] + sorted[mid]) / 2.0
        } else {
            sorted[mid]
        }
    }

    private const val STEP_MS = 5 * 60_000L
    private const val HALF_WINDOW_MS = STEP_MS / 2
    private const val MAX_INTERPOLATION_GAP_MS = 10 * 60_000L
    private const val MAX_LOOKBACK_MS = 6 * 60 * 60_000L
}
