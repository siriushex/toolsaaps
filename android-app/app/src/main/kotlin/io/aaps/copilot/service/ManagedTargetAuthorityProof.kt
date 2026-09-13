package io.aaps.copilot.service

import androidx.room.withTransaction
import io.aaps.copilot.data.local.CopilotDatabase
import io.aaps.copilot.data.repository.LocalTargetSafetyTimeWindow
import io.aaps.copilot.domain.predict.AcceptedSensitivityTupleFreshness
import io.aaps.copilot.domain.target.BaseTargetSchedule
import io.aaps.copilot.domain.target.BaseTargetSchedulePolicy
import io.aaps.copilot.domain.target.TargetCommandCandidate
import io.aaps.copilot.domain.target.TargetCommandObservation
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.withTimeoutOrNull

internal data class ManagedTargetReadClock(val wallNowTs: Long, val monotonicNowTs: Long)

private data class ManagedTargetProofResult(val failure: String?)

// Keep clinical reads coherent; no transport or retry may run inside this proof.
internal suspend fun withManagedTargetAuthorityProof(
    db: CopilotDatabase,
    timeoutMs: Long = 2_000L,
    block: suspend () -> String?
): String? {
    require(timeoutMs > 0L)
    val result = withTimeoutOrNull(timeoutMs) {
        ManagedTargetProofResult(db.withTransaction { block() })
    }
    return if (result == null) "target_authority_read_timeout" else result.failure
}

internal fun managedTargetFinalAuthorityFailureStatic(
    candidate: TargetCommandCandidate,
    schedule: BaseTargetSchedule,
    acceptedCycleId: String?,
    forecastGenerationTs: Long?,
    latestGlucoseTs: Long?,
    observation: TargetCommandObservation,
    started: ManagedTargetReadClock,
    finished: ManagedTargetReadClock,
    glucoseFreshnessMs: Long
): String? {
    val clockConsistent = try {
        val elapsed = Math.subtractExact(finished.monotonicNowTs, started.monotonicNowTs)
        val wallElapsed = Math.subtractExact(finished.wallNowTs, started.wallNowTs)
        val drift = Math.subtractExact(wallElapsed, elapsed)
        started.wallNowTs > 0L && started.monotonicNowTs >= 0L && elapsed >= 0L &&
            wallElapsed >= 0L && drift in
            -LocalTargetSafetyTimeWindow.SUPPORTED_FUTURE_SKEW_MS..
                LocalTargetSafetyTimeWindow.SUPPORTED_FUTURE_SKEW_MS
    } catch (_: ArithmeticException) {
        false
    }
    if (!clockConsistent) return "local_safety_chronology_unresolved"
    managedTargetFreshnessPreflightFailureStatic(candidate, acceptedCycleId, latestGlucoseTs,
        finished.wallNowTs, glucoseFreshnessMs)?.let { return it }
    if (forecastGenerationTs == null || forecastGenerationTs <= 0L ||
        !AcceptedSensitivityTupleFreshness.isFresh(forecastGenerationTs, finished.wallNowTs)
    ) return "accepted_forecast_missing_or_stale"
    managedTargetOwnershipPreflightFailureStatic(candidate, observation, finished.wallNowTs)
        ?.let { return it }
    val provenance = candidate.baseProvenance ?: return "base_provenance_missing"
    if (schedule.revision != provenance.scheduleRevision) return "schedule_revision_changed"
    val interval = try {
        BaseTargetSchedulePolicy.resolveManual(schedule, Instant.ofEpochMilli(finished.wallNowTs),
            ZoneId.systemDefault()).intervalId
    } catch (_: IllegalArgumentException) {
        return "schedule_resolution_failed"
    }
    return if (interval != provenance.intervalId) "schedule_interval_changed" else null
}
