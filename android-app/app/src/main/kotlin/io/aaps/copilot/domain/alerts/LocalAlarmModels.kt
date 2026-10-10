package io.aaps.copilot.domain.alerts

internal const val LOCAL_ALARM_CYCLE_TIMEOUT_MS = 55_000L

enum class LocalAlarmLevel(val priority: Int, val strong: Boolean = false) {
    NONE(0),
    WATCH_60(0),
    SOFT_HIGH_RISK(1),
    PUMP_LINK(2),
    DELIVERY_DIAGNOSTIC(3),
    WARNING_30(4),
    CRITICAL_5(5, true),
    LOW_NOW(6, true);

    val audible: Boolean get() = this != NONE && this != WATCH_60
}

data class LocalAlarmTiming(
    val softClipMs: Int = 2_000,
    val strongClipMs: Int = 20_000,
    val softRepeatMinutes: Int = 5,
    val strongRepeatMinutes: Int = 2
) {
    internal fun valid(): Boolean = softClipMs in 1_000..5_000 &&
        strongClipMs in 15_000..30_000 && softRepeatMinutes in 5..30 && strongRepeatMinutes in 1..10
}

data class LocalAlarmStep(val offsetMs: Long, val targetPercent: Int, val startsClip: Boolean)

data class LocalAlarmProfile(
    val steps: List<LocalAlarmStep>,
    val clipDurationMs: Int,
    val repeatMs: Long,
    val cycleTimeoutMs: Long = LOCAL_ALARM_CYCLE_TIMEOUT_MS
)

enum class LocalAlarmSourceKind { GLUCOSE, PUMP_LINK, DELIVERY_DIAGNOSTIC }

data class LocalAlarmKey(val kind: LocalAlarmSourceKind, val id: String)

// Adapters must derive authorization and expiry from existing accepted source policy.
data class LocalAlarmEvidence(
    val key: LocalAlarmKey,
    val generation: Long,
    val level: LocalAlarmLevel,
    val bootCount: Int,
    val observedElapsedMs: Long,
    val validUntilElapsedMs: Long,
    val authorized: Boolean
)

data class LocalAlarmEnvironment(
    val enabled: Boolean,
    val armed: Boolean,
    val nowElapsedMs: Long,
    val nowWallMs: Long,
    val bootCount: Int,
    val mutedUntilWallMs: Long = 0L
)

data class LocalAlarmCycle(
    val key: LocalAlarmKey,
    val generation: Long,
    val ordinal: Long,
    val level: LocalAlarmLevel,
    val bootCount: Int,
    val startedElapsedMs: Long,
    val deadlineElapsedMs: Long
)

data class LocalAlarmPause(val startedWallMs: Long, val untilWallMs: Long, val level: LocalAlarmLevel)

data class LocalAlarmState(
    val key: LocalAlarmKey,
    val generation: Long,
    val level: LocalAlarmLevel,
    val bootCount: Int,
    val observedElapsedMs: Long,
    val validUntilElapsedMs: Long,
    val ordinal: Long = 0L,
    val lastCycle: LocalAlarmCycle? = null,
    val cycleActive: Boolean = false,
    val nextDueElapsedMs: Long? = null,
    val reachedPercent: Int = 0,
    val pause: LocalAlarmPause? = null
) {
    val activeCycle: LocalAlarmCycle? get() = lastCycle.takeIf { cycleActive }
}

data class LocalAlarmAcknowledgement(
    val key: LocalAlarmKey,
    val generation: Long,
    val ordinal: Long,
    val level: LocalAlarmLevel
)

enum class LocalAlarmAdmission {
    START, ACTIVE, WAITING, ACKNOWLEDGED, MUTED, DISABLED, UNAVAILABLE,
    INVALID_ENVIRONMENT, INVALID_EVIDENCE, INVALID_STATE, INVALID_TIMING, SILENT, OVERFLOW
}

data class LocalAlarmEvaluation(
    val state: LocalAlarmState?,
    val admission: LocalAlarmAdmission,
    val startCycle: LocalAlarmCycle? = null,
    val profile: LocalAlarmProfile? = null,
    val cancelActive: Boolean = false
)
