package io.aaps.copilot.scheduler

import androidx.work.Data
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

internal enum class ClinicalInputInvalidationMode {
    NORMAL,
    LOCAL_READ_ONLY
}

internal enum class ClinicalInputInvalidationSource {
    PLANNED_ACTIVITY,
    CONTEXT_EVENT,
    STARTUP_RECONCILIATION,
    NIGHTSCOUT_RECONCILIATION,
    LOCAL_NIGHTSCOUT,
    BROADCAST_INGEST,
    LOCAL_ACTIVITY,
    HEALTH_CONNECT_ACTIVITY,
    RUNTIME_CONFIGURATION,
    LEDGER_RECOVERY
}

internal enum class ClinicalInvalidationOwnershipStatus {
    ACTIVE,
    ACCEPTED_TERMINAL,
    AMBIGUOUS_TERMINAL,
    ABSENT_OR_TERMINAL,
    FATAL_TERMINAL,
    UNKNOWN
}

private enum class ExactSettlementKind {
    ACKNOWLEDGE,
    AMBIGUOUS_TERMINATE,
    TERMINATE,
    FATAL_TERMINATE
}

internal const val MAX_CLINICAL_INVALIDATION_REDISPATCH_ATTEMPTS = 1
internal const val MAX_CLINICAL_INVALIDATION_PERSISTED_ATTEMPT =
    MAX_CLINICAL_INVALIDATION_REDISPATCH_ATTEMPTS + 1

internal fun interface ClinicalInvalidationOwnershipStatusSource {
    suspend fun status(dispatch: ClinicalInputInvalidationDispatch): ClinicalInvalidationOwnershipStatus
}

internal data class ClinicalInputInvalidationDispatch(
    val generation: Long,
    val dispatchToken: String,
    val mode: ClinicalInputInvalidationMode,
    val sources: Set<ClinicalInputInvalidationSource>,
    val attempt: Int
)

internal data class ClinicalInvalidationWorkData(
    val generation: Long,
    val dispatchToken: String,
    val mode: ClinicalInputInvalidationMode,
    val coordinatorAttempt: Int
) {
    companion object {
        fun from(data: Data): ClinicalInvalidationWorkData? {
            val generation = data.getLong(WORK_DATA_GENERATION, -1L)
            if (generation <= 0L) return null
            val dispatchToken = data.getString(WORK_DATA_DISPATCH_TOKEN)
                ?.takeIf(String::isNotBlank)
                ?: return null
            val mode = data.getString(WORK_DATA_MODE)
                ?.let { value ->
                    ClinicalInputInvalidationMode.entries.firstOrNull { it.name == value }
                }
                ?: return null
            val attempt = data.getInt(WORK_DATA_COORDINATOR_ATTEMPT, -1)
            if (attempt !in 0..MAX_CLINICAL_INVALIDATION_REDISPATCH_ATTEMPTS) return null
            return ClinicalInvalidationWorkData(generation, dispatchToken, mode, attempt)
        }

        fun hasReservedFields(data: Data): Boolean =
            data.keyValueMap.keys.any { it in WORK_DATA_KEYS }
    }
}

internal fun ClinicalInputInvalidationDispatch.toWorkData(): Data {
    require(generation > 0L) { "Clinical invalidation generation must be positive" }
    require(dispatchToken.isNotBlank()) { "Clinical invalidation dispatch token must not be blank" }
    require(attempt in 0..MAX_CLINICAL_INVALIDATION_REDISPATCH_ATTEMPTS) {
        "Clinical invalidation dispatch attempt is outside the bounded retry domain"
    }
    return Data.Builder()
        .putLong(WORK_DATA_GENERATION, generation)
        .putString(WORK_DATA_DISPATCH_TOKEN, dispatchToken)
        .putString(WORK_DATA_MODE, mode.name)
        .putInt(WORK_DATA_COORDINATOR_ATTEMPT, attempt)
        .build()
}

internal data class ClinicalInputInvalidationSnapshot(
    val latestGeneration: Long,
    val acknowledgedGeneration: Long,
    val inFlightGeneration: Long?,
    val inFlightDispatchToken: String?,
    val hasPendingInvalidation: Boolean
)

internal fun resolveClinicalInputInvalidationMode(
    therapyActionsArmed: Boolean,
    powerSaveActive: Boolean
): ClinicalInputInvalidationMode = if (therapyActionsArmed && !powerSaveActive) {
    ClinicalInputInvalidationMode.NORMAL
} else {
    ClinicalInputInvalidationMode.LOCAL_READ_ONLY
}

internal suspend fun resolveClinicalInputInvalidationModeFailClosed(
    timeoutMs: Long = DEFAULT_MODE_RESOLUTION_TIMEOUT_MS,
    modeSource: suspend () -> ClinicalInputInvalidationMode
): ClinicalInputInvalidationMode = try {
    withTimeoutOrNull(timeoutMs) { modeSource() }
        ?: ClinicalInputInvalidationMode.LOCAL_READ_ONLY
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (_: Exception) {
    ClinicalInputInvalidationMode.LOCAL_READ_ONLY
}

internal suspend fun dispatchClinicalInputInvalidationFailClosed(
    request: ClinicalInputInvalidationDispatch,
    sink: suspend (ClinicalInputInvalidationDispatch) -> Boolean
): Boolean = try {
    sink(request)
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (_: Exception) {
    false
}

internal suspend fun notifyClinicalInputInvalidationIfApplied(
    appliedCount: Int,
    source: ClinicalInputInvalidationSource,
    invalidate: suspend (ClinicalInputInvalidationSource) -> Unit
): Boolean {
    if (appliedCount <= 0) return false
    invalidate(source)
    return true
}

internal suspend fun notifyClinicalInputInvalidationAfterReconciliation(
    appliedCount: Int,
    startup: Boolean,
    invalidate: suspend (ClinicalInputInvalidationSource) -> Unit
): Boolean = notifyClinicalInputInvalidationIfApplied(
    appliedCount = appliedCount,
    source = if (startup) {
        ClinicalInputInvalidationSource.STARTUP_RECONCILIATION
    } else {
        ClinicalInputInvalidationSource.NIGHTSCOUT_RECONCILIATION
    },
    invalidate = invalidate
)

internal suspend fun settleClinicalInvalidationCycle(
    generation: Long,
    dispatchToken: String,
    cycleAccepted: Boolean,
    acknowledge: suspend (Long, String) -> Boolean,
    terminate: suspend (Long, String) -> Boolean
) {
    if (cycleAccepted) {
        acknowledge(generation, dispatchToken)
    } else {
        terminate(generation, dispatchToken)
    }
}

internal suspend fun terminateReactiveDispatchNonCancellable(
    generation: Long,
    dispatchToken: String,
    timeoutMs: Long = TERMINATION_CLEANUP_TIMEOUT_MS,
    terminate: suspend (Long, String) -> Unit
) {
    withContext(NonCancellable) {
        withTimeout(timeoutMs) {
            terminate(generation, dispatchToken)
        }
    }
}

private const val WORK_DATA_GENERATION = "clinical_invalidation_generation"
private const val WORK_DATA_DISPATCH_TOKEN = "clinical_invalidation_dispatch_token"
private const val WORK_DATA_MODE = "clinical_invalidation_mode"
private const val WORK_DATA_COORDINATOR_ATTEMPT = "clinical_invalidation_coordinator_attempt"
private val WORK_DATA_KEYS = setOf(
    WORK_DATA_GENERATION,
    WORK_DATA_DISPATCH_TOKEN,
    WORK_DATA_MODE,
    WORK_DATA_COORDINATOR_ATTEMPT
)
private const val TERMINATION_CLEANUP_TIMEOUT_MS = 5_000L
private const val DEFAULT_MODE_RESOLUTION_TIMEOUT_MS = 250L

internal class ClinicalInputInvalidationCoordinator(
    private val scope: CoroutineScope,
    private val ledger: ClinicalInputInvalidationLedger = InMemoryClinicalInputInvalidationLedger(),
    private val trailingDelayMs: Long = DEFAULT_TRAILING_DELAY_MS,
    private val maxTrailingWaitMs: Long = DEFAULT_MAX_TRAILING_WAIT_MS,
    private val retryDelayMs: Long = DEFAULT_RETRY_DELAY_MS,
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000L },
    private val tokenSource: () -> String = { UUID.randomUUID().toString() },
    private val modeResolutionTimeoutMs: Long = DEFAULT_MODE_RESOLUTION_TIMEOUT_MS,
    private val ownershipQueryTimeoutMs: Long = DEFAULT_OWNERSHIP_QUERY_TIMEOUT_MS,
    private val ownershipStatusSource: ClinicalInvalidationOwnershipStatusSource =
        ClinicalInvalidationOwnershipStatusSource {
            ClinicalInvalidationOwnershipStatus.ABSENT_OR_TERMINAL
        },
    private val failureReporter: suspend (Throwable) -> Unit = {},
    private val modeSource: suspend () -> ClinicalInputInvalidationMode,
    private val sink: suspend (ClinicalInputInvalidationDispatch) -> Boolean
) {
    private val mutex = Mutex()
    private val hydrationMutex = Mutex()
    private var state = ClinicalInputInvalidationLedgerState()
    @Volatile
    private var hydrated = false
    private var trailingTimer: Job? = null
    private var dispatchPreparation: Job? = null
    private var burstStartedAtMs: Long? = null
    private var claimWriteRetryGeneration: Long? = null
    private var lateOwnershipRequeryCandidate: ClinicalInputInvalidationDispatch? = null
    private var lateOwnershipRequeryInProgress = false

    fun invalidate(source: ClinicalInputInvalidationSource) {
        scope.launch {
            invalidatePersistedInput(source)
        }
    }

    suspend fun invalidatePersistedInput(source: ClinicalInputInvalidationSource): Boolean = try {
        invalidateAndAwait(source)
        true
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (fatal: Error) {
        throw fatal
    } catch (failure: Exception) {
        reportFailure(failure)
        false
    }

    suspend fun hydrateAndRedriveSafely(): Boolean = try {
        hydrateAndRedrive()
        true
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (fatal: Error) {
        throw fatal
    } catch (failure: Exception) {
        reportFailure(failure)
        false
    }

    suspend fun invalidateAndAwait(source: ClinicalInputInvalidationSource) {
        ensureHydrated()
        val requeryDispatch = mutex.withLock {
            val generation = nextGeneration(state.latestGeneration)
            val pendingSources = state.pending?.sources.orEmpty() + source
            val next = state.copy(
                latestGeneration = generation,
                pending = ClinicalInputInvalidationPending(
                    generation = generation,
                    sources = pendingSources,
                    attempt = 0
                )
            )
            ledger.write(next)
            claimWriteRetryGeneration = null
            if (state.pending == null) burstStartedAtMs = nowMs()
            state = next
            if (state.inFlight == null) {
                scheduleTrailingLocked()
            }
            claimLateOwnershipRequeryLocked()
        }
        requeryDispatch?.let { requeryUnknownOwnership(it) }
    }

    suspend fun hydrateAndRedrive() {
        ensureHydrated()
        mutex.withLock {
            if (state.pending == null || state.inFlight != null) return
            burstStartedAtMs = nowMs()
            if (state.pending!!.attempt <= MAX_CLINICAL_INVALIDATION_REDISPATCH_ATTEMPTS) {
                scheduleRetryLocked()
            }
        }
    }

    suspend fun acknowledge(generation: Long, dispatchToken: String): Boolean {
        return settleExactDispatch(generation, dispatchToken, ExactSettlementKind.ACKNOWLEDGE)
    }

    suspend fun dispatchTerminated(generation: Long, dispatchToken: String): Boolean {
        return settleExactDispatch(generation, dispatchToken, ExactSettlementKind.TERMINATE)
    }

    suspend fun dispatchFatal(generation: Long, dispatchToken: String): Boolean {
        return settleExactDispatch(generation, dispatchToken, ExactSettlementKind.FATAL_TERMINATE)
    }

    suspend fun dispatchAmbiguous(generation: Long, dispatchToken: String): Boolean {
        return settleExactDispatch(
            generation,
            dispatchToken,
            ExactSettlementKind.AMBIGUOUS_TERMINATE
        )
    }

    private suspend fun settleExactDispatch(
        generation: Long,
        dispatchToken: String,
        kind: ExactSettlementKind
    ): Boolean {
        ensureHydrated()
        val writeFailures = mutableListOf<Exception>()
        var propagation: Throwable? = null
        val settled = mutex.withLock {
            val active = state.inFlight ?: return false
            if (!active.matches(generation, dispatchToken)) return false
            val pending = state.pending
            val next = when (kind) {
                ExactSettlementKind.ACKNOWLEDGE -> state.copy(
                    acknowledgedGeneration = maxOf(state.acknowledgedGeneration, generation),
                    inFlight = null
                )
                ExactSettlementKind.AMBIGUOUS_TERMINATE,
                ExactSettlementKind.FATAL_TERMINATE -> state.toTerminalPending(active)
                ExactSettlementKind.TERMINATE -> {
                    if (pending != null) {
                        state.copy(
                            pending = pending.copy(sources = active.sources + pending.sources),
                            inFlight = null
                        )
                    } else {
                        state.copy(
                            pending = ClinicalInputInvalidationPending(
                                generation = active.generation,
                                sources = active.sources,
                                attempt = nextAttempt(active.attempt)
                            ),
                            inFlight = null
                        )
                    }
                }
            }
            var persisted = false
            for (writeAttempt in 0 until MAX_SETTLEMENT_WRITE_ATTEMPTS) {
                try {
                    ledger.write(next)
                    persisted = true
                    break
                } catch (cancelled: CancellationException) {
                    propagation = cancelled
                    break
                } catch (fatal: Error) {
                    propagation = fatal
                    break
                } catch (failure: Exception) {
                    writeFailures += failure
                }
            }
            state = next
            val maySchedule = persisted && propagation == null
            when {
                maySchedule && kind == ExactSettlementKind.ACKNOWLEDGE && next.pending != null -> {
                    burstStartedAtMs = nowMs()
                    scheduleTrailingLocked()
                }
                maySchedule &&
                    (kind == ExactSettlementKind.AMBIGUOUS_TERMINATE ||
                        kind == ExactSettlementKind.FATAL_TERMINATE) &&
                    pending != null -> {
                    burstStartedAtMs = nowMs()
                    scheduleTrailingLocked()
                }
                maySchedule &&
                    kind == ExactSettlementKind.TERMINATE &&
                    pending != null &&
                    next.pending!!.attempt <= MAX_CLINICAL_INVALIDATION_REDISPATCH_ATTEMPTS -> {
                    burstStartedAtMs = nowMs()
                    scheduleTrailingLocked()
                }
                maySchedule &&
                    kind == ExactSettlementKind.TERMINATE &&
                    pending == null &&
                    next.pending!!.attempt <= MAX_CLINICAL_INVALIDATION_REDISPATCH_ATTEMPTS -> {
                    burstStartedAtMs = nowMs()
                    scheduleRetryLocked()
                }
                else -> {
                    burstStartedAtMs = next.pending?.let { nowMs() }
                    trailingTimer?.cancel()
                    trailingTimer = null
                }
            }
            if (next.pending == null) {
                burstStartedAtMs = null
                trailingTimer?.cancel()
                trailingTimer = null
            }
            true
        }
        propagation?.let { failure ->
            when (failure) {
                is CancellationException -> throw failure
                is Error -> throw failure
                else -> throw failure
            }
        }
        writeFailures.forEach { reportFailure(it) }
        return settled
    }

    suspend fun snapshot(): ClinicalInputInvalidationSnapshot {
        ensureHydrated()
        return mutex.withLock {
            ClinicalInputInvalidationSnapshot(
            latestGeneration = state.latestGeneration,
            acknowledgedGeneration = state.acknowledgedGeneration,
            inFlightGeneration = state.inFlight?.generation,
            inFlightDispatchToken = state.inFlight?.dispatchToken,
            hasPendingInvalidation = state.pending != null ||
                state.latestGeneration > state.acknowledgedGeneration
        )
        }
    }

    private fun scheduleTimerLocked(delayMs: Long, dispatchNotBeforeMs: Long) {
        if (dispatchPreparation?.isActive == true) return
        trailingTimer?.cancel()
        trailingTimer = scope.launch {
            delay(delayMs.coerceAtLeast(0L))
            beginDispatchPreparation(dispatchNotBeforeMs)
        }
    }

    private fun scheduleTrailingLocked() {
        if (dispatchPreparation?.isActive == true) return
        val startedAt = burstStartedAtMs ?: nowMs().also { burstStartedAtMs = it }
        val now = nowMs()
        val maxDueAt = safeAdd(startedAt, maxTrailingWaitMs)
        val trailingDueAt = safeAdd(now, trailingDelayMs)
        val dispatchDueAt = minOf(maxDueAt, trailingDueAt)
        val preparationLead = modeResolutionTimeoutMs.coerceIn(0L, maxTrailingWaitMs)
        val preparationAt = (dispatchDueAt - preparationLead).coerceAtLeast(startedAt)
        scheduleTimerLocked(
            delayMs = (preparationAt - now).coerceAtLeast(0L),
            dispatchNotBeforeMs = dispatchDueAt
        )
    }

    private fun scheduleRetryLocked() {
        val now = nowMs()
        scheduleTimerLocked(retryDelayMs, safeAdd(now, retryDelayMs))
    }

    private suspend fun beginDispatchPreparation(dispatchNotBeforeMs: Long) {
        mutex.withLock {
            trailingTimer = null
            if (dispatchPreparation?.isActive == true || state.inFlight != null || state.pending == null) {
                return
            }
            dispatchPreparation = scope.launch {
                try {
                    prepareAndDispatch(dispatchNotBeforeMs)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (fatal: Error) {
                    throw fatal
                } catch (failure: Exception) {
                    clearDispatchPreparation()
                    reportFailure(failure)
                }
            }
        }
    }

    private suspend fun prepareAndDispatch(dispatchNotBeforeMs: Long) {
        val mode = try {
            resolveClinicalInputInvalidationModeFailClosed(modeResolutionTimeoutMs, modeSource)
        } catch (cancelled: CancellationException) {
            clearDispatchPreparation()
            throw cancelled
        }
        delay((dispatchNotBeforeMs - nowMs()).coerceAtLeast(0L))
        var claimedGeneration: Long? = null
        val request = try {
            mutex.withLock {
                dispatchPreparation = null
                if (state.inFlight != null) return
                val pending = state.pending ?: return
                claimedGeneration = pending.generation
                val dispatchToken = tokenSource().takeIf(String::isNotBlank)
                    ?: error("Clinical invalidation dispatch token must not be blank")
                ClinicalInputInvalidationDispatch(
                    generation = pending.generation,
                    dispatchToken = dispatchToken,
                    mode = mode,
                    sources = pending.sources,
                    attempt = pending.attempt
                ).also {
                    val next = state.copy(pending = null, inFlight = it)
                    ledger.write(next)
                    state = next
                    burstStartedAtMs = null
                    claimWriteRetryGeneration = null
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (fatal: Error) {
            throw fatal
        } catch (failure: Exception) {
            claimedGeneration?.let { scheduleClaimWriteRetryAfterFailure(it) }
            throw failure
        }
        val accepted = try {
            dispatchClinicalInputInvalidationFailClosed(request, sink)
        } catch (cancelled: CancellationException) {
            try {
                terminateReactiveDispatchNonCancellable(
                    generation = request.generation,
                    dispatchToken = request.dispatchToken,
                    terminate = { generation, token -> dispatchTerminated(generation, token) }
                )
            } catch (fatal: Error) {
                throw fatal
            } catch (cleanupFailure: Exception) {
                withContext(NonCancellable) {
                    reportFailure(cleanupFailure)
                }
            }
            throw cancelled
        }
        if (!accepted) dispatchTerminated(request.generation, request.dispatchToken)
    }

    private suspend fun scheduleClaimWriteRetryAfterFailure(generation: Long) {
        mutex.withLock {
            if (
                state.inFlight != null ||
                state.pending?.generation != generation ||
                claimWriteRetryGeneration == generation
            ) {
                return
            }
            claimWriteRetryGeneration = generation
            scheduleRetryLocked()
        }
    }

    private suspend fun clearDispatchPreparation() {
        mutex.withLock {
            dispatchPreparation = null
        }
    }

    private suspend fun ensureHydrated() {
        if (hydrated) return
        hydrationMutex.withLock {
            if (hydrated) return
            var restored = ledger.read()
            val restoredInFlight = restored.inFlight
            val ownershipStatus = restoredInFlight?.let { dispatch ->
                resolveOwnershipStatus(dispatch)
            }
            if (restoredInFlight != null) {
                restored = when (ownershipStatus) {
                    ClinicalInvalidationOwnershipStatus.ACTIVE,
                    ClinicalInvalidationOwnershipStatus.UNKNOWN -> restored
                    ClinicalInvalidationOwnershipStatus.ACCEPTED_TERMINAL -> restored.copy(
                        acknowledgedGeneration = maxOf(
                            restored.acknowledgedGeneration,
                            restoredInFlight.generation
                        ),
                        inFlight = null
                    )
                    ClinicalInvalidationOwnershipStatus.AMBIGUOUS_TERMINAL -> {
                        restored.toTerminalPending(restoredInFlight)
                    }
                    ClinicalInvalidationOwnershipStatus.FATAL_TERMINAL -> {
                        restored.toTerminalPending(restoredInFlight)
                    }
                    ClinicalInvalidationOwnershipStatus.ABSENT_OR_TERMINAL -> {
                        restored.toRedispatchPending(restoredInFlight)
                    }
                    null -> restored
                }
                if (restored.inFlight !== restoredInFlight) ledger.write(restored)
            }
            mutex.withLock {
                state = restored
                lateOwnershipRequeryCandidate = restored.inFlight?.takeIf {
                    ownershipStatus == ClinicalInvalidationOwnershipStatus.UNKNOWN &&
                        it.matches(restoredInFlight.generation, restoredInFlight.dispatchToken)
                }
                hydrated = true
            }
        }
    }

    private fun claimLateOwnershipRequeryLocked(): ClinicalInputInvalidationDispatch? {
        val candidate = lateOwnershipRequeryCandidate ?: return null
        val active = state.inFlight
        if (active == null || !active.matches(candidate.generation, candidate.dispatchToken)) {
            lateOwnershipRequeryCandidate = null
            return null
        }
        if (lateOwnershipRequeryInProgress) return null
        lateOwnershipRequeryInProgress = true
        return candidate
    }

    private suspend fun requeryUnknownOwnership(dispatch: ClinicalInputInvalidationDispatch) {
        var consumeCandidate = false
        try {
            val status = resolveOwnershipStatus(dispatch)
            when (status) {
                ClinicalInvalidationOwnershipStatus.ACTIVE,
                ClinicalInvalidationOwnershipStatus.UNKNOWN -> {
                    consumeCandidate = true
                }
                ClinicalInvalidationOwnershipStatus.ACCEPTED_TERMINAL -> {
                    consumeCandidate = acknowledge(dispatch.generation, dispatch.dispatchToken)
                }
                ClinicalInvalidationOwnershipStatus.AMBIGUOUS_TERMINAL -> {
                    consumeCandidate =
                        dispatchAmbiguous(dispatch.generation, dispatch.dispatchToken)
                }
                ClinicalInvalidationOwnershipStatus.FATAL_TERMINAL -> {
                    consumeCandidate = dispatchFatal(dispatch.generation, dispatch.dispatchToken)
                }
                ClinicalInvalidationOwnershipStatus.ABSENT_OR_TERMINAL -> {
                    consumeCandidate =
                        dispatchTerminated(dispatch.generation, dispatch.dispatchToken)
                }
            }
        } finally {
            withContext(NonCancellable) {
                mutex.withLock {
                    lateOwnershipRequeryInProgress = false
                    val current = state.inFlight
                    if (
                        consumeCandidate ||
                        current == null ||
                        !current.matches(dispatch.generation, dispatch.dispatchToken)
                    ) {
                        lateOwnershipRequeryCandidate = null
                    }
                }
            }
        }
    }

    private fun ClinicalInputInvalidationLedgerState.toRedispatchPending(
        active: ClinicalInputInvalidationDispatch
    ): ClinicalInputInvalidationLedgerState {
        val currentPending = pending
        val generation = maxOf(active.generation, currentPending?.generation ?: 0L)
        val sources = active.sources + currentPending?.sources.orEmpty()
        val attempt = maxOf(active.attempt, currentPending?.attempt ?: 0)
        return copy(
            latestGeneration = maxOf(latestGeneration, generation),
            pending = ClinicalInputInvalidationPending(generation, sources, attempt),
            inFlight = null
        )
    }

    private fun ClinicalInputInvalidationLedgerState.toTerminalPending(
        active: ClinicalInputInvalidationDispatch
    ): ClinicalInputInvalidationLedgerState {
        val currentPending = pending
        if (currentPending != null) {
            return copy(
                latestGeneration = maxOf(latestGeneration, currentPending.generation),
                pending = currentPending,
                inFlight = null
            )
        }
        return copy(
            latestGeneration = maxOf(latestGeneration, active.generation),
            pending = ClinicalInputInvalidationPending(
                generation = active.generation,
                sources = active.sources,
                attempt = MAX_CLINICAL_INVALIDATION_PERSISTED_ATTEMPT
            ),
            inFlight = null
        )
    }

    private suspend fun resolveOwnershipStatus(
        dispatch: ClinicalInputInvalidationDispatch
    ): ClinicalInvalidationOwnershipStatus = try {
        withTimeoutOrNull(ownershipQueryTimeoutMs) {
            ownershipStatusSource.status(dispatch)
        } ?: ClinicalInvalidationOwnershipStatus.UNKNOWN
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        ClinicalInvalidationOwnershipStatus.UNKNOWN
    }

    private suspend fun reportFailure(failure: Throwable) {
        try {
            failureReporter(failure)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (fatal: Error) {
            throw fatal
        } catch (_: Exception) {
            // The durable producer data remains authoritative; periodic runtime work is the fallback.
        }
    }

    private fun ClinicalInputInvalidationDispatch.matches(generation: Long, token: String): Boolean =
        this.generation == generation && dispatchToken == token

    private fun nextGeneration(current: Long): Long = when {
        current < 0L -> 1L
        current == Long.MAX_VALUE -> Long.MAX_VALUE
        else -> current + 1L
    }

    private fun nextAttempt(current: Int): Int = when {
        current < 0 -> 0
        current >= MAX_CLINICAL_INVALIDATION_PERSISTED_ATTEMPT ->
            MAX_CLINICAL_INVALIDATION_PERSISTED_ATTEMPT
        else -> current + 1
    }

    private fun safeAdd(value: Long, increment: Long): Long =
        if (increment > 0L && value > Long.MAX_VALUE - increment) Long.MAX_VALUE else value + increment

    private companion object {
        const val DEFAULT_TRAILING_DELAY_MS = 2_000L
        const val DEFAULT_MAX_TRAILING_WAIT_MS = 10_000L
        const val DEFAULT_RETRY_DELAY_MS = 5_000L
        const val DEFAULT_OWNERSHIP_QUERY_TIMEOUT_MS = 2_000L
        const val MAX_SETTLEMENT_WRITE_ATTEMPTS = 2
    }
}
