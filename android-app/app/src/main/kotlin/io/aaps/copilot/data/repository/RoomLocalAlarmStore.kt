package io.aaps.copilot.data.repository

import androidx.room.withTransaction
import io.aaps.copilot.data.local.CopilotDatabase
import io.aaps.copilot.data.local.entity.AlertLocalCycleEntity
import io.aaps.copilot.data.local.entity.AlertLocalStateEntity
import io.aaps.copilot.domain.alerts.LocalAlarmAcknowledgement
import io.aaps.copilot.domain.alerts.LocalAlarmAdmission
import io.aaps.copilot.domain.alerts.LocalAlarmArbitrationDecision
import io.aaps.copilot.domain.alerts.LocalAlarmArbitrationPolicy
import io.aaps.copilot.domain.alerts.LocalAlarmArbitrationStatus
import io.aaps.copilot.domain.alerts.LocalAlarmCycle
import io.aaps.copilot.domain.alerts.LocalAlarmCycleResult
import io.aaps.copilot.domain.alerts.LocalAlarmEnvironment
import io.aaps.copilot.domain.alerts.LocalAlarmEvaluation
import io.aaps.copilot.domain.alerts.LocalAlarmEvidence
import io.aaps.copilot.domain.alerts.LocalAlarmKey
import io.aaps.copilot.domain.alerts.LocalAlarmPersistenceCodec
import io.aaps.copilot.domain.alerts.LocalAlarmPolicy
import io.aaps.copilot.domain.alerts.LocalAlarmProfiles
import io.aaps.copilot.domain.alerts.LocalAlarmRequest
import io.aaps.copilot.domain.alerts.LocalAlarmState
import io.aaps.copilot.domain.alerts.LocalAlarmStepResult
import io.aaps.copilot.domain.alerts.LocalAlarmTiming
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.withLock

/** Persistence only. A committed START is not proof that any side effect occurred. */
class RoomLocalAlarmStore(
    private val db: CopilotDatabase,
    private val environmentProvider: () -> LocalAlarmEnvironment
) {
    private val mutex = EpisodeAlertOperationLocks.forIdentity(db)
    private val runtime = LocalAlarmRuntimeOwnership.forIdentity(db)
    internal val runtimeMutex get() = runtime.mutex
    internal val runtimeUnavailable get() = runtime.unavailable
    private val dao get() = db.alertLocalDao()
    private data class Loaded(val row: AlertLocalStateEntity, val state: LocalAlarmState)

    /** Null means storage is unavailable/corrupt; callers must not start a cycle. */
    suspend fun evaluate(evidence: LocalAlarmEvidence, timing: LocalAlarmTiming): LocalAlarmEvaluation? =
        guarded(null) { environment -> evaluateLocked(evidence, timing, environment, recover = false) }

    /** Called by a new runtime owner, not on every evidence update. */
    suspend fun recover(evidence: LocalAlarmEvidence, timing: LocalAlarmTiming): LocalAlarmEvaluation? =
        guarded(null) { environment -> evaluateLocked(evidence, timing, environment, recover = true) }

    /** No claims or transitions: the selected source still needs a fresh committed START. */
    suspend fun previewArbitration(requests: List<LocalAlarmRequest>, owner: LocalAlarmCycle?,
        timing: LocalAlarmTiming): LocalAlarmArbitrationDecision? = guarded(null) { environment ->
        if (requests.size > 64) return@guarded LocalAlarmArbitrationDecision(
            LocalAlarmArbitrationStatus.INVALID_SNAPSHOT, cancelCycle = owner)
        // Freeze the batch before suspending reads; selection must use exactly
        // the sources whose persisted state/claims were validated in this transaction.
        val snapshot = requests.toList()
        val keys = LocalAlarmArbitrationPolicy.boundedKeys(snapshot, owner)
            ?: return@guarded LocalAlarmArbitrationDecision(LocalAlarmArbitrationStatus.INVALID_SNAPSHOT, cancelCycle = owner)
        val states = keys.mapNotNull { key -> load(key, environment)?.let { key to it.state } }.toMap()
        LocalAlarmArbitrationPolicy.evaluate(snapshot, states, owner, environment, timing)
    }

    /** Read-only execution gate: never creates a claim, advances progress or refreshes state. */
    suspend fun admits(evidence: LocalAlarmEvidence, cycle: LocalAlarmCycle,
        timing: LocalAlarmTiming): Boolean = guarded(false) { environment ->
        if (evidence.key != cycle.key) return@guarded false
        val loaded = load(cycle.key, environment) ?: return@guarded false
        val evaluation = LocalAlarmPolicy.evaluate(evidence, loaded.state, environment, timing)
        evaluation.admission == LocalAlarmAdmission.ACTIVE && evaluation.state?.activeCycle == cycle
    }

    suspend fun acknowledge(evidence: LocalAlarmEvidence, timing: LocalAlarmTiming,
        action: LocalAlarmAcknowledgement): Boolean = guarded(false) { environment ->
        val loaded = load(evidence.key, environment) ?: return@guarded false
        if (environment.mutedUntilWallMs > environment.nowWallMs) return@guarded false
        val next = LocalAlarmPolicy.acknowledge(evidence, loaded.state, environment, timing, action)
            ?: return@guarded false
        if (next == loaded.state) return@guarded false
        loaded.state.activeCycle?.let { terminal(it, "CANCELLED", environment.nowWallMs) }
        persist(loaded, next, environment.nowWallMs)
        true
    }

    suspend fun recordStep(evidence: LocalAlarmEvidence, cycle: LocalAlarmCycle, step: LocalAlarmStepResult,
        timing: LocalAlarmTiming): Boolean = guarded(false) { environment ->
        if (evidence.key != cycle.key) return@guarded false
        val loaded = load(cycle.key, environment) ?: return@guarded false
        val evaluation = LocalAlarmPolicy.evaluate(evidence, loaded.state, environment, timing)
        if (evaluation.admission != LocalAlarmAdmission.ACTIVE || evaluation.state?.activeCycle != cycle) return@guarded false
        val state = evaluation.state
        if (step.atElapsedMs < cycle.startedElapsedMs || step.atElapsedMs > environment.nowElapsedMs ||
            step.atElapsedMs >= cycle.deadlineElapsedMs) return@guarded false
        val row = activeRow(cycle)
        val result = requireNotNull(LocalAlarmPersistenceCodec.decodeResult(row.resultJson))
        result.steps.firstOrNull { it.index == step.index }?.let { return@guarded it == step }
        val profile = LocalAlarmProfiles.create(cycle.level, timing, state.reachedPercent) ?: return@guarded false
        val scheduled = profile.steps.getOrNull(step.index) ?: return@guarded false
        if (step.atElapsedMs - cycle.startedElapsedMs < scheduled.offsetMs) return@guarded false
        if (step.reachedPercent != null && step.reachedPercent != scheduled.targetPercent) return@guarded false
        val json = LocalAlarmPersistenceCodec.encodeResult(result.copy(steps = result.steps + step)) ?: return@guarded false
        val next = step.reachedPercent?.let { LocalAlarmPolicy.recordReached(state, cycle, it, environment) } ?: state
        check(dao.updateCycle(row.copy(resultJson = json)) == 1)
        persist(loaded, next, environment.nowWallMs)
        true
    }

    /** Finishing or cancelling clears ownership, never resolves risk or acknowledges it. */
    suspend fun finish(cycle: LocalAlarmCycle, cancelled: Boolean = false): Boolean = guarded(false) { environment ->
        val loaded = load(cycle.key, environment) ?: return@guarded false
        if (loaded.state.activeCycle != cycle || environment.bootCount != cycle.bootCount ||
            environment.nowElapsedMs < cycle.startedElapsedMs) return@guarded false
        terminal(cycle, if (cancelled) "CANCELLED" else "FINISHED", environment.nowWallMs)
        persist(loaded, loaded.state.copy(cycleActive = false), environment.nowWallMs)
        true
    }

    /** Exclusive runtime owner only: no policy evaluation or replacement claim. */
    internal suspend fun markInterrupted(cycle: LocalAlarmCycle): Boolean = guarded(false) { environment ->
        val loaded = load(cycle.key, environment) ?: return@guarded false
        if (loaded.state.activeCycle != cycle) return@guarded false
        terminal(cycle, "UNCERTAIN", environment.nowWallMs)
        persist(loaded, loaded.state.copy(cycleActive = false), environment.nowWallMs)
        true
    }

    /** Terminal journal rows only; ordinal state must never be pruned here. */
    suspend fun prune(nowWallMs: Long, limit: Int = 100): Int = guarded(0) {
        val retention = 30L * 86_400_000
        if (nowWallMs < retention) 0 else dao.pruneTerminal(nowWallMs - retention, limit)
    }

    private suspend fun evaluateLocked(evidence: LocalAlarmEvidence, timing: LocalAlarmTiming,
        environment: LocalAlarmEnvironment, recover: Boolean): LocalAlarmEvaluation {
        val loaded = load(evidence.key, environment)
        var previous = loaded?.state
        val interrupted = if (recover) previous?.activeCycle else null
        if (interrupted != null) {
            terminal(interrupted, "UNCERTAIN", environment.nowWallMs)
            previous = previous?.copy(cycleActive = false)
        }
        val evaluation = LocalAlarmPolicy.evaluate(evidence, previous, environment, timing)
        val next = evaluation.state
        if (interrupted == null) loaded?.state?.activeCycle?.let { old ->
            if (next?.activeCycle != old) {
                val expired = old.bootCount != environment.bootCount || environment.nowElapsedMs >= old.deadlineElapsedMs
                terminal(old, if (expired) "UNCERTAIN" else "CANCELLED", environment.nowWallMs)
            }
        }
        evaluation.startCycle?.let { cycle ->
            dao.insertCycle(AlertLocalCycleEntity(cycle.key.kind.name, cycle.key.id, cycle.generation,
                cycle.ordinal, cycle.bootCount, cycle.level.name, cycle.startedElapsedMs, cycle.deadlineElapsedMs,
                environment.nowWallMs, null, "CLAIMED", requireNotNull(LocalAlarmPersistenceCodec.encodeResult(LocalAlarmCycleResult()))))
        }
        if (next != null) persist(loaded, next, environment.nowWallMs)
        return evaluation.copy(cancelActive = evaluation.cancelActive || interrupted != null)
    }

    private suspend fun load(key: LocalAlarmKey, environment: LocalAlarmEnvironment): Loaded? {
        val row = dao.state(key.kind.name, key.id) ?: return null
        val state = requireNotNull(LocalAlarmPersistenceCodec.decodeState(row.stateJson))
        check(row.revision > 0 && row.updatedAtMs >= 0 && state.key == key &&
            row.generation == state.generation && row.bootCount == state.bootCount && row.ordinal == state.ordinal &&
            row.nextDueElapsedMs == state.nextDueElapsedMs && row.pauseUntilWallMs == state.pause?.untilWallMs &&
            row.cycleDeadlineElapsedMs == state.activeCycle?.deadlineElapsedMs)
        state.activeCycle?.let { cycle ->
            val result = requireNotNull(LocalAlarmPersistenceCodec.decodeResult(activeRow(cycle).resultJson))
            check(result.steps.all { it.atElapsedMs >= cycle.startedElapsedMs && it.atElapsedMs < cycle.deadlineElapsedMs &&
                (cycle.bootCount != environment.bootCount || it.atElapsedMs <= environment.nowElapsedMs) &&
                (it.reachedPercent == null || it.reachedPercent <= state.reachedPercent) })
        }
        return Loaded(row, state)
    }

    private suspend fun activeRow(cycle: LocalAlarmCycle): AlertLocalCycleEntity {
        val row = requireNotNull(dao.cycle(cycle.key.kind.name, cycle.key.id, cycle.generation, cycle.ordinal))
        check(row.bootCount == cycle.bootCount && row.level == cycle.level.name &&
            row.startedElapsedMs == cycle.startedElapsedMs && row.deadlineElapsedMs == cycle.deadlineElapsedMs &&
            row.claimedAtMs >= 0 && row.status == "CLAIMED" && row.terminalAtMs == null)
        requireNotNull(LocalAlarmPersistenceCodec.decodeResult(row.resultJson))
        return row
    }

    private suspend fun terminal(cycle: LocalAlarmCycle, status: String, nowWallMs: Long) {
        check(nowWallMs >= 0)
        check(dao.updateCycle(activeRow(cycle).copy(status = status, terminalAtMs = nowWallMs)) == 1)
    }

    private suspend fun persist(previous: Loaded?, state: LocalAlarmState, nowWallMs: Long) {
        if (previous?.state == state) return
        check(nowWallMs >= 0 && (previous == null || previous.row.revision < Long.MAX_VALUE))
        val json = requireNotNull(LocalAlarmPersistenceCodec.encodeState(state))
        val row = AlertLocalStateEntity(state.key.kind.name, state.key.id, state.generation, state.bootCount,
            state.ordinal, (previous?.row?.revision ?: 0L) + 1L, nowWallMs, state.nextDueElapsedMs,
            state.pause?.untilWallMs, state.activeCycle?.deadlineElapsedMs, json)
        if (previous == null) check(dao.insertState(row) != -1L) else {
            check(dao.compareAndSetState(row.sourceKind, row.sourceId, previous.row.revision, row.generation,
                row.bootCount, row.ordinal, row.revision, row.updatedAtMs, row.nextDueElapsedMs,
                row.pauseUntilWallMs, row.cycleDeadlineElapsedMs, row.stateJson) == 1)
        }
    }

    private suspend fun <T> guarded(unavailable: T, operation: suspend (LocalAlarmEnvironment) -> T): T = try {
        mutex.withLock {
            db.withTransaction {
                val mute = db.alertEventDao().byEpisodeId(EpisodeAlertDeliveryStateMachine.MUTE_STATE_ID)?.suppressionUntil ?: 0L
                operation(environmentProvider().copy(mutedUntilWallMs = mute))
            }
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        unavailable
    }
}
