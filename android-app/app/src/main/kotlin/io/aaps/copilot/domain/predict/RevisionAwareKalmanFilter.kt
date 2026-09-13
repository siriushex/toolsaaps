package io.aaps.copilot.domain.predict

import io.aaps.copilot.domain.model.GlucosePoint

internal data class KalmanHistoryInput(val ts: Long, val glucose: Double, val knownRocPerMin: Double)

internal data class KalmanHistoryUpdate(
    val snapshot: KalmanSnapshotV3?,
    val rebuilt: Boolean,
    val appliedUpdates: Int,
    val causalHistoryRevised: Boolean = false
)

internal class RevisionAwareKalmanFilter {
    private val filter = KalmanGlucoseFilterV3()
    private var previous = emptyList<KalmanHistoryInput>()
    private var previousSource = emptyList<GlucosePoint>()

    fun update(
        inputs: List<KalmanHistoryInput>,
        volNorm: Double,
        sourceHistory: List<GlucosePoint> = emptyList(),
        revalidateKnownInputs: ((List<KalmanHistoryInput>) -> DoubleArray)? = null
    ): KalmanHistoryUpdate {
        if (inputs.isEmpty()) return KalmanHistoryUpdate(filter.snapshotOrNull(), false, 0)
        val resumeIndex = resumeIndex(inputs)
        val knownInputsUnchanged = if (previous.isEmpty() || revalidateKnownInputs == null) {
            true
        } else {
            val known = revalidateKnownInputs(previous)
            known.size == previous.size && previous.indices.all { index ->
                index == 0 || previous[index].knownRocPerMin == known[index]
            }
        }
        // Minute CGM cadence moves the canonical grid without revising the source history.
        // Replay actual canonical intervals, but preserve AR for a validated source append.
        val causalHistoryRevised = previous.isNotEmpty() && (!knownInputsUnchanged ||
            (resumeIndex == null && (revalidateKnownInputs == null || !unchangedSourceAppend(sourceHistory))))
        val replay = resumeIndex == null || !knownInputsUnchanged
        val rebuilt = previous.isNotEmpty() && replay
        var applied = 0
        if (replay) {
            filter.reset(inputs.first().glucose, inputs.first().ts)
            applied = 1
        }
        val startIndex = if (replay) 1 else checkNotNull(resumeIndex)
        for (index in startIndex until inputs.size) {
            val point = inputs[index]
            filter.update(point.glucose, point.ts, volNorm, point.knownRocPerMin)
            applied += 1
        }
        // Retain only the current canonical window, never accumulate earlier cycles.
        previous = inputs.toList()
        previousSource = sourceHistory.toList()
        return KalmanHistoryUpdate(filter.snapshotOrNull(), rebuilt, applied, causalHistoryRevised)
    }

    private fun unchangedSourceAppend(source: List<GlucosePoint>): Boolean {
        if (previous.isEmpty() || previousSource.isEmpty() || source.isEmpty() ||
            source.last().ts <= previousSource.last().ts
        ) return false
        var oldIndex = 0
        while (oldIndex < previousSource.size && previousSource[oldIndex].ts < source.first().ts) oldIndex += 1
        if (oldIndex == previousSource.size) return false
        var newIndex = 0
        while (oldIndex < previousSource.size) {
            if (source.getOrNull(newIndex) != previousSource[oldIndex]) return false
            oldIndex += 1
            newIndex += 1
        }
        return true
    }

    private fun resumeIndex(inputs: List<KalmanHistoryInput>): Int? {
        if (previous.isEmpty() || inputs.last().ts < previous.last().ts) return null
        var oldIndex = 0
        while (oldIndex < previous.size && previous[oldIndex].ts < inputs.first().ts) oldIndex += 1
        if (oldIndex == previous.size) return null
        var newIndex = 0
        while (oldIndex < previous.size) {
            val old = previous[oldIndex]
            val current = inputs.getOrNull(newIndex) ?: return null
            if (old.ts != current.ts || old.glucose != current.glucose) return null
            // The first point has no preceding interval in a trimmed window.
            if (newIndex > 0 && old.knownRocPerMin != current.knownRocPerMin) return null
            oldIndex += 1
            newIndex += 1
        }
        return newIndex
    }
}
