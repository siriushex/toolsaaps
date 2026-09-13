package io.aaps.copilot.data.repository

data class CausalSafetyClock(
    val throughTs: Long,
    val evidenceResolved: Boolean
)

fun interface CausalSafetyClockReader {
    suspend fun observe(nowTs: Long): CausalSafetyClock
}

internal class LocalTargetSafetyTimeWindow private constructor(
    val nowTs: Long,
    val causalThroughInclusive: Long,
    val chronologyResolved: Boolean,
    val actionSinceInclusive: Long,
    val targetSinceInclusive: Long,
    val targetEvidenceThroughInclusive: Long
) {
    companion object {
        const val ACTION_LOOKBACK_MS = 6L * 60L * 60L * 1_000L
        const val ACTIVE_TARGET_MAX_DURATION_MINUTES = 720L
        const val ACTIVE_TARGET_LOOKBACK_MS =
            ACTIVE_TARGET_MAX_DURATION_MINUTES * 60L * 1_000L
        const val SUPPORTED_FUTURE_SKEW_MS = 2L * 60L * 1_000L
        const val FUTURE_AMBIGUITY_MS = 5L * 60L * 1_000L
        const val TARGET_EVIDENCE_MAX_ROWS = 256
        const val TARGET_EVIDENCE_PROBE_ROWS = TARGET_EVIDENCE_MAX_ROWS + 1

        fun at(nowTs: Long, highWaterTs: Long = nowTs): LocalTargetSafetyTimeWindow {
            require(nowTs > 0L) { "local target safety timestamp must be positive" }
            require(highWaterTs > 0L) { "local target safety high-water must be positive" }
            val causalThrough = maxOf(nowTs, highWaterTs)
            val chronologyResolved = saturatingAdd(nowTs, SUPPORTED_FUTURE_SKEW_MS) >= causalThrough
            return LocalTargetSafetyTimeWindow(
                nowTs = nowTs,
                causalThroughInclusive = causalThrough,
                chronologyResolved = chronologyResolved,
                actionSinceInclusive = subtractFloorOne(causalThrough, ACTION_LOOKBACK_MS),
                targetSinceInclusive = subtractFloorOne(causalThrough, ACTIVE_TARGET_LOOKBACK_MS),
                // Only the documented two-minute causal skew is inspected above high-water.
                targetEvidenceThroughInclusive = saturatingAdd(
                    causalThrough,
                    SUPPORTED_FUTURE_SKEW_MS
                )
            )
        }

        private fun subtractFloorOne(value: Long, decrement: Long): Long = try {
            Math.subtractExact(value, decrement).coerceAtLeast(1L)
        } catch (_: ArithmeticException) {
            1L
        }

        private fun saturatingAdd(value: Long, increment: Long): Long = try {
            Math.addExact(value, increment)
        } catch (_: ArithmeticException) {
            Long.MAX_VALUE
        }
    }
}
