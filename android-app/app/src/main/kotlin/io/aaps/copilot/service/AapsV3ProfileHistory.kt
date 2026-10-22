package io.aaps.copilot.service

import java.util.Collections

/** A half-open interval of retained effective-profile evidence, not a delivery receipt. */
internal data class AapsV3ProfileHistoryInterval(
    val startMs: Long,
    val endMs: Long,
    val observation: AapsV3EffectiveProfileObservation
) {
    override fun toString() = "AapsV3ProfileHistoryInterval(redacted)"
}

internal class AapsV3ProfileHistoryRead(
    val status: AapsV3ObservedProfileStatus,
    val retainedRevision: Long?,
    intervals: List<AapsV3ProfileHistoryInterval> = emptyList()
) {
    val intervals: List<AapsV3ProfileHistoryInterval> = Collections.unmodifiableList(intervals.toList())
    val upstreamCompletenessVerified: Boolean get() = false
    override fun toString() = "AapsV3ProfileHistoryRead(status=$status, redacted)"
}
