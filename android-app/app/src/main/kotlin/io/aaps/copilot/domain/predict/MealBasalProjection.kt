package io.aaps.copilot.domain.predict

import java.util.Collections

/** Observed historical rates, not requested pump settings. Zero delivery represents suspension. */
internal data class MealBasalInterval(
    val id: String,
    val revision: String,
    val startMs: Long,
    val endMs: Long,
    val deliveredUnitsPerHour: Double,
    val scheduledUnitsPerHour: Double,
    val kernel: MealInsulinKernelIdentity
)

/** A locally declared interval inventory; its existence does not prove upstream completeness. */
internal class MealBasalHistory(
    val predictionAtMs: Long,
    val availableAtMs: Long,
    val sourceRevision: String,
    intervals: List<MealBasalInterval>
) {
    val intervals: List<MealBasalInterval>
    init {
        require(predictionAtMs > 0 && availableAtMs >= predictionAtMs)
        require(sourceRevision.isNotBlank() && sourceRevision.length <= 256)
        require(intervals.size in 1..2_880)
        require(intervals.all {
            it.id.isNotBlank() && it.id.length <= 256 && it.revision.isNotBlank() && it.revision.length <= 256 &&
                it.startMs > 0 && it.endMs > it.startMs && it.endMs <= predictionAtMs &&
                it.deliveredUnitsPerHour.isFinite() && it.deliveredUnitsPerHour >= 0.0 &&
                it.scheduledUnitsPerHour.isFinite() && it.scheduledUnitsPerHour >= 0.0
        })
        require(intervals.map { it.id }.distinct().size == intervals.size)
        val sorted = intervals.sortedBy { it.startMs }
        require(sorted.zipWithNext().all { (a, b) -> a.endMs == b.startMs }) { "Incomplete basal interval inventory" }
        require(sorted.last().endMs == predictionAtMs)
        require(predictionAtMs - sorted.first().startMs <= 2_880 * 60_000L)
        this.intervals = Collections.unmodifiableList(sorted)
    }
}

internal class MealBasalComponent private constructor(
    val activeUnits: Double,
    stepUnits: List<Double>,
    val remainingUnits: Double
) {
    val stepUnits: List<Double> = Collections.unmodifiableList(stepUnits.toList())
    companion object {
        internal fun fromRemaining(remaining: DoubleArray): MealBasalComponent {
            require(remaining.size >= 2 && remaining.all { it.isFinite() && it >= 0.0 })
            val steps = remaining.mapIndexed { index, value ->
                if (index == 0) 0.0 else {
                    val consumed = remaining[index - 1] - value
                    require(consumed >= -1e-10)
                    maxOf(0.0, consumed)
                }
            }
            return MealBasalComponent(remaining.first(), steps, remaining.last())
        }
    }
}

/** Interval integration of the existing kernel; no future basal or controller response. */
internal class MealBasalProjection private constructor(
    val history: MealBasalHistory,
    val kernel: MealInsulinKernelIdentity,
    val requiredHistoryStartMs: Long,
    val isfMmolPerUnit: Double,
    val delivered: MealBasalComponent,
    val scheduled: MealBasalComponent,
    val positiveDeviation: MealBasalComponent,
    val negativeDeviation: MealBasalComponent
) {
    val predictionAtMs: Long get() = history.predictionAtMs
    val signedDeviationActiveUnits: Double get() = positiveDeviation.activeUnits - negativeDeviation.activeUnits
    val remainingDeviationMagnitudeUnits: Double get() = positiveDeviation.remainingUnits + negativeDeviation.remainingUnits
    val signedDeviationStepEffectsMmol: List<Double> = Collections.unmodifiableList(delivered.stepUnits.indices.map {
        -isfMmolPerUnit * (positiveDeviation.stepUnits[it] - negativeDeviation.stepUnits[it])
    })
    val completeByHorizon: Boolean get() = delivered.remainingUnits == 0.0 && scheduled.remainingUnits == 0.0 &&
        remainingDeviationMagnitudeUnits == 0.0
    val historyWindowCovered: Boolean get() = true
    val upstreamCompletenessVerified: Boolean get() = false

    companion object {
        internal fun project(history: MealBasalHistory, kernel: MealInsulinKernelIdentity,
            knownAtMs: Long, horizonMinutes: Int, isf: Double, ageKnots: List<Double>,
            cumulative: (Double) -> Double): MealBasalProjection {
            require(horizonMinutes in 5..720 && horizonMinutes % 5 == 0)
            require(knownAtMs >= history.predictionAtMs && knownAtMs - history.predictionAtMs <= 300_000L &&
                history.availableAtMs <= knownAtMs)
            require(isf.isFinite() && isf > 0.0)
            require(ageKnots.isNotEmpty() && ageKnots.all { it.isFinite() && it >= 0.0 } &&
                ageKnots.zipWithNext().all { (a, b) -> a < b })
            require(cumulative(ageKnots.last()) == 1.0)
            val supportMs = kotlin.math.ceil(ageKnots.last() * 60_000.0)
            require(supportMs.isFinite() && supportMs in 1.0..(2_880 * 60_000.0))
            val requiredStart = history.predictionAtMs - supportMs.toLong()
            require(history.intervals.first().startMs <= requiredStart) { "Basal history does not cover the insulin tail" }
            require(history.intervals.all { it.kernel == kernel }) { "Historical basal kernel mismatch" }
            val count = horizonMinutes / 5 + 1
            require(history.intervals.size.toLong() * count * ageKnots.size <= 2_000_000L) { "Basal projection budget exceeded" }
            val delivered = DoubleArray(count)
            val scheduled = DoubleArray(count)
            val positive = DoubleArray(count)
            val negative = DoubleArray(count)
            for (interval in history.intervals) {
                val fromAge = (history.predictionAtMs - interval.endMs) / 60_000.0
                val toAge = (history.predictionAtMs - interval.startMs) / 60_000.0
                val positiveRate = maxOf(0.0, interval.deliveredUnitsPerHour - interval.scheduledUnitsPerHour)
                val negativeRate = maxOf(0.0, interval.scheduledUnitsPerHour - interval.deliveredUnitsPerHour)
                for (step in 0 until count) {
                    val from = fromAge + step * 5.0
                    val to = toAge + step * 5.0
                    // The cumulative kernel is linear between these transformed profile knots.
                    val knots = listOf(from) + ageKnots.filter { it > from && it < to } + to
                    val remainingMinutes = knots.zipWithNext().sumOf { (a, b) ->
                        (b - a) * ((1.0 - cumulative(a)) + (1.0 - cumulative(b))) / 2.0
                    }
                    require(remainingMinutes.isFinite() && remainingMinutes >= 0.0)
                    val hours = remainingMinutes / 60.0
                    delivered[step] += interval.deliveredUnitsPerHour * hours
                    scheduled[step] += interval.scheduledUnitsPerHour * hours
                    positive[step] += positiveRate * hours
                    negative[step] += negativeRate * hours
                }
            }
            return MealBasalProjection(history, kernel, requiredStart, isf,
                MealBasalComponent.fromRemaining(delivered), MealBasalComponent.fromRemaining(scheduled),
                MealBasalComponent.fromRemaining(positive), MealBasalComponent.fromRemaining(negative)).also {
                require(it.signedDeviationStepEffectsMmol.all { value -> value.isFinite() })
            }
        }
    }
}
