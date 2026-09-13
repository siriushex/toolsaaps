package io.aaps.copilot.domain.predict

import io.aaps.copilot.domain.model.DataQuality
import io.aaps.copilot.domain.model.GlucosePoint
import kotlin.math.abs

internal data class CanonicalGlucoseSeries(
    val points: List<GlucosePoint>,
    val representativeTimestamps: List<Long?>,
    val observedCount: Int,
    val interpolatedCount: Int,
    val anchorTs: Long,
    val scanOperationCount: Long
)

internal data class CanonicalGlucoseConfig(
    val fromTs: Long? = null,
    val throughTs: Long? = null,
    val anchorTs: Long? = null,
    val maxLookbackMs: Long = 6L * 60L * 60L * 1_000L,
    val maxInterpolationGapMs: Long = 10L * 60L * 1_000L
)

internal object Glucose5mCanonicalizer {

    fun build(
        raw: List<GlucosePoint>,
        config: CanonicalGlucoseConfig = CanonicalGlucoseConfig(),
        checkpoint: (() -> Unit)? = null,
        beforeCanonicalPoint: () -> Unit = {}
    ): CanonicalGlucoseSeries {
        require(config.maxLookbackMs >= 0L)
        require(config.maxInterpolationGapMs >= 0L)
        require(config.fromTs == null || config.throughTs == null || config.fromTs <= config.throughTs)
        val operations = OperationCounter(checkpoint)
        val filtered = ArrayList<GlucosePoint>(raw.size)
        raw.forEach { point ->
            operations.tick()
            if (point.quality != DataQuality.SENSOR_ERROR &&
                (config.fromTs == null || point.ts >= config.fromTs) &&
                (config.throughTs == null || point.ts <= config.throughTs)
            ) {
                filtered += point
            }
        }
        filtered.sortWith(
            compareBy<GlucosePoint> { it.ts }
                .thenBy { qualityRank(it.quality) }
                .thenBy { it.source }
                .thenBy { it.valueMmol }
        )
        val cleaned = deduplicateTimestamps(filtered, operations)
        if (cleaned.isEmpty()) return emptySeries(operations.count)

        val anchorTs = config.anchorTs ?: cleaned.last().ts
        require(config.fromTs == null || anchorTs >= config.fromTs)
        require(config.throughTs == null || anchorTs <= config.throughTs)
        val lookbackStart = runCatching { Math.subtractExact(anchorTs, config.maxLookbackMs) }
            .getOrDefault(Long.MIN_VALUE)
        val minTs = maxOf(cleaned.first().ts, config.fromTs ?: Long.MIN_VALUE, lookbackStart)
        val firstScopedIndex = cleaned.binarySearchBy(minTs) { it.ts }
            .let { if (it >= 0) it else -it - 1 }
        val scoped = cleaned.subList(firstScopedIndex, cleaned.size)
        if (scoped.isEmpty()) return emptySeries(operations.count)

        val span = Math.subtractExact(anchorTs, scoped.first().ts)
        val stepCount = Math.floorDiv(span, STEP_MS)
        var targetTs = Math.subtractExact(anchorTs, Math.multiplyExact(stepCount, STEP_MS))
        var windowStart = 0
        var windowEnd = 0
        var interpolationCursor = 0
        var observedCount = 0
        var interpolatedCount = 0
        val points = mutableListOf<GlucosePoint>()
        val representatives = mutableListOf<Long?>()

        while (targetTs <= anchorTs) {
            operations.tick()
            val lower = subtractSaturated(targetTs, HALF_WINDOW_MS)
            val upper = addSaturated(targetTs, HALF_WINDOW_MS)
            while (windowStart < scoped.size && scoped[windowStart].ts < lower) {
                windowStart += 1
                operations.tick()
            }
            if (windowEnd < windowStart) windowEnd = windowStart
            while (windowEnd < scoped.size && scoped[windowEnd].ts < upper) {
                windowEnd += 1
                operations.tick()
            }
            while (interpolationCursor < scoped.size &&
                scoped[interpolationCursor].ts < targetTs
            ) {
                interpolationCursor += 1
                operations.tick()
            }

            val bucket = scoped.subList(windowStart, windowEnd)
            val exact = bucket.firstOrNull { point ->
                operations.tick()
                point.ts == targetTs
            }
            val canonical = when {
                exact != null -> {
                    observedCount += 1
                    beforeCanonicalPoint()
                    CanonicalPoint(exact.copy(quality = DataQuality.OK), exact.ts)
                }
                bucket.isNotEmpty() -> {
                    observedCount += 1
                    val medianMmol = median(bucket.map { point ->
                        operations.tick()
                        point.valueMmol
                    })
                    val representative = bucket.minWith(
                        compareBy<GlucosePoint> { abs(it.valueMmol - medianMmol) }
                            .thenBy { it.ts }
                            .thenBy { it.source }
                            .thenBy { it.valueMmol }
                    )
                    beforeCanonicalPoint()
                    CanonicalPoint(
                        GlucosePoint(
                            ts = targetTs,
                            valueMmol = medianMmol,
                            source = representative.source,
                            quality = DataQuality.OK
                        ),
                        representative.ts
                    )
                }
                else -> interpolate(
                    scoped,
                    interpolationCursor,
                    targetTs,
                    config.maxInterpolationGapMs,
                    beforeCanonicalPoint
                )?.also { interpolatedCount += 1 }
            }
            if (canonical != null) {
                points += canonical.point
                representatives += canonical.representativeTs
            }
            if (targetTs == anchorTs) break
            targetTs = Math.addExact(targetTs, STEP_MS)
        }

        return CanonicalGlucoseSeries(
            points = points,
            representativeTimestamps = representatives,
            observedCount = observedCount,
            interpolatedCount = interpolatedCount,
            anchorTs = anchorTs,
            scanOperationCount = operations.count
        )
    }

    private fun deduplicateTimestamps(
        sorted: List<GlucosePoint>,
        operations: OperationCounter
    ): List<GlucosePoint> {
        val output = ArrayList<GlucosePoint>(sorted.size)
        var index = 0
        while (index < sorted.size) {
            var winner = sorted[index]
            var next = index + 1
            while (next < sorted.size && sorted[next].ts == winner.ts) {
                operations.tick()
                winner = sorted[next]
                next += 1
            }
            output += winner
            operations.tick()
            index = next
        }
        return output
    }

    private fun interpolate(
        points: List<GlucosePoint>,
        nextIndex: Int,
        targetTs: Long,
        maxGapMs: Long,
        beforeCanonicalPoint: () -> Unit
    ): CanonicalPoint? {
        val prev = points.getOrNull(nextIndex - 1) ?: return null
        val next = points.getOrNull(nextIndex) ?: return null
        if (prev.ts >= targetTs || next.ts <= targetTs) return null
        val gapMs = runCatching { Math.subtractExact(next.ts, prev.ts) }.getOrNull() ?: return null
        if (gapMs <= 0L || gapMs > maxGapMs) return null
        val numerator = Math.subtractExact(targetTs, prev.ts)
        val ratio = numerator.toDouble() / gapMs.toDouble()
        beforeCanonicalPoint()
        return CanonicalPoint(
            point = GlucosePoint(
                ts = targetTs,
                valueMmol = prev.valueMmol + (next.valueMmol - prev.valueMmol) * ratio,
                source = "canonical_5m_interp",
                quality = DataQuality.OK
            ),
            representativeTs = null
        )
    }

    private fun median(values: List<Double>): Double {
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 0) {
            (sorted[mid - 1] + sorted[mid]) / 2.0
        } else {
            sorted[mid]
        }
    }

    private fun qualityRank(quality: DataQuality): Int = when (quality) {
        DataQuality.OK -> 2
        DataQuality.STALE -> 1
        DataQuality.SENSOR_ERROR -> 0
    }

    private fun emptySeries(operationCount: Long) = CanonicalGlucoseSeries(
        points = emptyList(),
        representativeTimestamps = emptyList(),
        observedCount = 0,
        interpolatedCount = 0,
        anchorTs = 0L,
        scanOperationCount = operationCount
    )

    private fun subtractSaturated(left: Long, right: Long): Long =
        runCatching { Math.subtractExact(left, right) }.getOrDefault(Long.MIN_VALUE)

    private fun addSaturated(left: Long, right: Long): Long =
        runCatching { Math.addExact(left, right) }.getOrDefault(Long.MAX_VALUE)

    private data class CanonicalPoint(
        val point: GlucosePoint,
        val representativeTs: Long?
    )

    private class OperationCounter(
        private val checkpoint: (() -> Unit)?
    ) {
        var count = 0L
            private set
        private var nextCheckpoint = CHECKPOINT_INTERVAL

        fun tick() {
            count += 1L
            if (checkpoint != null && count >= nextCheckpoint) {
                checkpoint.invoke()
                nextCheckpoint = Math.addExact(nextCheckpoint, CHECKPOINT_INTERVAL)
            }
        }
    }

    private const val STEP_MS = 5 * 60_000L
    private const val HALF_WINDOW_MS = STEP_MS / 2
    private const val CHECKPOINT_INTERVAL = 256L
}
