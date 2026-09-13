package io.aaps.copilot.domain.pump

enum class PumpLinkAdapterState { ON, OFF, TURNING_ON, TURNING_OFF, UNAVAILABLE, PERMISSION_DENIED }
enum class PumpLinkDriverState { UNKNOWN, UNPAIRED, IDLE, CONNECTING, CONNECTED, BUSY, ERROR }

data class PumpLinkSnapshot(
    val bootCount: Int,
    val sessionStartedElapsedMs: Long,
    val sequence: Long,
    val sampledElapsedMs: Long,
    val supported: Boolean,
    val adapterState: PumpLinkAdapterState,
    val driverState: PumpLinkDriverState,
    val intentionalDisconnect: Boolean,
    val lastVerifiedStatusElapsedMs: Long?
)

enum class PumpLinkCondition(val needsAttention: Boolean = false) {
    UNKNOWN, VERIFYING, HEALTHY, INTENTIONALLY_DISCONNECTED,
    AAPS_UNREACHABLE(true), STATUS_STALE(true), BLUETOOTH_OFF(true),
    PERMISSION_MISSING(true), DRIVER_ERROR(true), MONITOR_UNAVAILABLE(true)
}

object PumpLinkHealthPolicy {
    const val HEARTBEAT_TIMEOUT_MS = 450_000L
    const val STATUS_TIMEOUT_MS = 1_200_000L
    const val MAX_PACKET_AGE_MS = 120_000L

    fun accepts(previous: PumpLinkSnapshot?, candidate: PumpLinkSnapshot, nowElapsedMs: Long, bootCount: Int): Boolean {
        if (!valid(candidate, nowElapsedMs, bootCount) || nowElapsedMs - candidate.sampledElapsedMs > MAX_PACKET_AGE_MS) return false
        if (previous == null || !valid(previous, nowElapsedMs, bootCount)) return true
        if (candidate.sessionStartedElapsedMs < previous.sessionStartedElapsedMs || candidate.sampledElapsedMs < previous.sampledElapsedMs) return false
        if (candidate.sessionStartedElapsedMs == previous.sessionStartedElapsedMs) {
            if (candidate.sequence <= previous.sequence) return false
            if (candidate.lastVerifiedStatusElapsedMs != null && previous.lastVerifiedStatusElapsedMs != null &&
                candidate.lastVerifiedStatusElapsedMs < previous.lastVerifiedStatusElapsedMs
            ) return false
        }
        return true
    }

    fun condition(snapshot: PumpLinkSnapshot?, nowElapsedMs: Long, bootCount: Int): PumpLinkCondition {
        if (snapshot == null || !snapshot.supported || !valid(snapshot, nowElapsedMs, bootCount) ||
            snapshot.driverState == PumpLinkDriverState.UNPAIRED
        ) return PumpLinkCondition.UNKNOWN
        if (nowElapsedMs - snapshot.sampledElapsedMs >= HEARTBEAT_TIMEOUT_MS) return PumpLinkCondition.AAPS_UNREACHABLE
        if (snapshot.intentionalDisconnect) return PumpLinkCondition.INTENTIONALLY_DISCONNECTED
        if (snapshot.adapterState == PumpLinkAdapterState.PERMISSION_DENIED) return PumpLinkCondition.PERMISSION_MISSING
        if (snapshot.adapterState in setOf(PumpLinkAdapterState.OFF, PumpLinkAdapterState.UNAVAILABLE)) return PumpLinkCondition.BLUETOOTH_OFF
        if (snapshot.driverState == PumpLinkDriverState.ERROR) return PumpLinkCondition.DRIVER_ERROR
        val responseOrSessionStart = snapshot.lastVerifiedStatusElapsedMs ?: snapshot.sessionStartedElapsedMs
        if (nowElapsedMs - responseOrSessionStart >= STATUS_TIMEOUT_MS) return PumpLinkCondition.STATUS_STALE
        if (snapshot.lastVerifiedStatusElapsedMs == null || snapshot.adapterState != PumpLinkAdapterState.ON ||
            snapshot.driverState == PumpLinkDriverState.CONNECTING
        ) return PumpLinkCondition.VERIFYING
        return if (snapshot.driverState == PumpLinkDriverState.UNKNOWN) PumpLinkCondition.UNKNOWN else PumpLinkCondition.HEALTHY
    }

    fun nextDeadline(snapshot: PumpLinkSnapshot?, nowElapsedMs: Long, bootCount: Int): Long? {
        if (snapshot == null || !snapshot.supported || !valid(snapshot, nowElapsedMs, bootCount) ||
            snapshot.driverState == PumpLinkDriverState.UNPAIRED ||
            nowElapsedMs - snapshot.sampledElapsedMs >= HEARTBEAT_TIMEOUT_MS
        ) return null
        return listOf(
            deadline(snapshot.sampledElapsedMs, HEARTBEAT_TIMEOUT_MS),
            deadline(snapshot.lastVerifiedStatusElapsedMs ?: snapshot.sessionStartedElapsedMs, STATUS_TIMEOUT_MS)
        ).filter { it > nowElapsedMs }.minOrNull()
    }

    private fun valid(snapshot: PumpLinkSnapshot, now: Long, boot: Int): Boolean =
        now >= 0 && boot >= 0 && snapshot.bootCount == boot && snapshot.sequence > 0 &&
            snapshot.sessionStartedElapsedMs >= 0 && snapshot.sessionStartedElapsedMs <= snapshot.sampledElapsedMs &&
            snapshot.sampledElapsedMs <= now &&
            (snapshot.lastVerifiedStatusElapsedMs == null ||
                snapshot.lastVerifiedStatusElapsedMs in maxOf(1L, snapshot.sessionStartedElapsedMs)..snapshot.sampledElapsedMs)

    private fun deadline(start: Long, interval: Long): Long =
        if (start > Long.MAX_VALUE - interval) Long.MAX_VALUE else start + interval
}
