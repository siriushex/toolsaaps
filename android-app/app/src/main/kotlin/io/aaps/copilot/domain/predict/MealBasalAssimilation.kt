package io.aaps.copilot.domain.predict

import java.util.Collections

/** Frozen known inputs for a disposable research replay, not clinical delivery attestation. */
internal class MealBasalAssimilation private constructor(
    val projection: MealBasalProjection,
    timestamps: List<Long>,
    effects: List<Double>
) {
    val timestamps: List<Long> = Collections.unmodifiableList(timestamps.toList())
    val historicalEffectsMmol: List<Double> = Collections.unmodifiableList(effects.toList())
    private val byInterval = timestamps.zipWithNext().zip(effects.drop(1)).toMap()
    val hasEffect: Boolean = effects.any { it != 0.0 } || projection.signedDeviationStepEffectsMmol.any { it != 0.0 }

    fun effectBetween(startMs: Long, endMs: Long): Double =
        requireNotNull(byInterval[startMs to endMs]) { "Basal replay interval is not covered" }

    companion object {
        internal fun prepare(projection: MealBasalProjection, timestamps: List<Long>, ageKnots: List<Double>,
            cumulative: (Double) -> Double): MealBasalAssimilation {
            require(timestamps.size in 4..20_000 && timestamps.last() == projection.predictionAtMs)
            require(timestamps.zipWithNext().all { (a, b) -> a > 0 && b > a && b - a <= 600_000L })
            require(cumulative(0.0) == 0.0) { "Basal replay requires a causal delivery kernel" }
            val supportMs = projection.predictionAtMs - projection.requiredHistoryStartMs
            require(projection.history.intervals.first().startMs <= timestamps.first() - supportMs) {
                "Basal inventory does not cover the complete glucose replay"
            }
            require(projection.history.intervals.size.toLong() *
                (timestamps.size * 2L + projection.delivered.stepUnits.size) * ageKnots.size <= 2_000_000L) {
                "Basal replay budget exceeded"
            }
            val effects = listOf(0.0) + timestamps.zipWithNext().map { (start, end) ->
                val units = projection.history.intervals.sumOf { interval ->
                    val from = maxOf(interval.startMs, start - supportMs).toDouble()
                    val to = minOf(interval.endMs, end).toDouble()
                    if (from >= to) 0.0 else {
                        // Split at BOTH transformed kernels. Future delivery contributes exactly zero.
                        val knots = (listOf(from, to) + ageKnots.flatMap {
                            listOf(start - it * 60_000.0, end - it * 60_000.0)
                        }.filter { it > from && it < to }).distinct().sorted()
                        fun consumed(at: Double): Double = cumulative((end - at) / 60_000.0) -
                            cumulative((start - at) / 60_000.0)
                        val hours = knots.zipWithNext().sumOf { (a, b) ->
                            (b - a) / 3_600_000.0 * (consumed(a) + consumed(b)) / 2.0
                        }
                        require(hours.isFinite() && hours >= 0.0)
                        (interval.deliveredUnitsPerHour - interval.scheduledUnitsPerHour) * hours
                    }
                }
                (-projection.isfMmolPerUnit * units).also { require(it.isFinite()) }
            }
            return MealBasalAssimilation(projection, timestamps, effects)
        }
    }
}
