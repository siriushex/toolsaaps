package io.aaps.copilot.domain.meal

import io.aaps.copilot.domain.model.DataQuality

/** Counts overlapping receipt windows, not unique low episodes or counterfactual safety. */
internal data class MealProspectiveWindowAudit(
    val evaluatedWindows: Int,
    val windowsWithoutUsableObservations: Int,
    val windowsWithObservedLow: Int,
    val windowsWithLowOutsideCheckpoints: Int,
    val windowsWithConflictingObservations: Int,
    val windowsWithRejectedObservations: Int
) {
    val observationCoverageVerified: Boolean get() = false
}

/** Same-context outcomes already bounded by the evaluation's availability cutoff. */
internal class MealProspectiveWindowIndex(outcomes: List<MealProspectiveOutcome>, lowThresholdMmol: Double) {
    private val groups = outcomes.groupBy { it.timestampMs }.toSortedMap()
    private val timestamps = groups.keys.toLongArray()
    private val usable = IntArray(timestamps.size + 1)
    private val lows = IntArray(timestamps.size + 1)
    private val conflicts = IntArray(timestamps.size + 1)
    private val rejected = IntArray(timestamps.size + 1)

    init {
        groups.values.forEachIndexed { index, same ->
            val conflict = same.map { it.mmol to it.quality }.distinct().size != 1
            val invalid = same.any { !it.mmol.isFinite() || it.mmol !in 0.1..100.0 ||
                it.quality != DataQuality.OK || it.availableAtMs == null || it.availableAtMs < it.timestampMs }
            val valid = !conflict && !invalid
            usable[index + 1] = usable[index] + if (valid) 1 else 0
            lows[index + 1] = lows[index] + if (valid && same.first().mmol < lowThresholdMmol) 1 else 0
            conflicts[index + 1] = conflicts[index] + if (conflict) 1 else 0
            rejected[index + 1] = rejected[index] + if (!conflict && invalid) 1 else 0
        }
    }

    fun summarize(frames: List<MealProspectiveFrame>, horizonMinutes: Int, evaluatedAtMs: Long): MealProspectiveWindowAudit {
        var evaluated = 0; var empty = 0; var low = 0; var between = 0; var conflict = 0; var invalid = 0
        for (frame in frames) {
            val anchor = frame.identity.predictionAtMs
            val target = anchor + horizonMinutes * 60_000L
            if (target > evaluatedAtMs) continue
            evaluated++
            // Do not score an outcome that occurred before the forecast receipt existed.
            val from = upperBound(frame.registeredAtMs)
            val until = upperBound(target)
            val lowCount = lows[until] - lows[from]
            if (usable[until] == usable[from]) empty++
            if (lowCount > 0) low++
            val checkpointLows = listOf(5, 30, 60).count { minutes ->
                val time = anchor + minutes * 60_000L
                val index = timestamps.binarySearch(time)
                time > frame.registeredAtMs && time <= target && index >= 0 && lows[index + 1] > lows[index]
            }
            if (lowCount > checkpointLows) between++
            if (conflicts[until] > conflicts[from]) conflict++
            if (rejected[until] > rejected[from]) invalid++
        }
        return MealProspectiveWindowAudit(evaluated, empty, low, between, conflict, invalid)
    }

    private fun upperBound(time: Long): Int {
        val index = timestamps.binarySearch(time)
        return if (index >= 0) index + 1 else -index - 1
    }
}
