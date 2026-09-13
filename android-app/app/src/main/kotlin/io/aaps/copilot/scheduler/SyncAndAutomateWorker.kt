package io.aaps.copilot.scheduler

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import io.aaps.copilot.CopilotApp
import io.aaps.copilot.data.repository.AutomationRepository
import io.aaps.copilot.service.LocalNightscoutForegroundService
import io.aaps.copilot.service.PowerSaveController
import io.aaps.copilot.service.PowerSaveRuntimeState
import io.aaps.copilot.service.TherapyActionRuntimeState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout

internal data class StartedWorkerPolicy(
    val cycleIntent: AutomationRepository.AutomationCycleIntent,
    val skipForMinuteLoop: Boolean,
    val cancelFutureRuntimeWork: Boolean,
    val schedulePowerSaveResume: Boolean,
    val stopRuntimeControllers: Boolean
)

internal enum class StartedWorkerOutcome {
    EXECUTED,
    NOT_ACCEPTED,
    SKIPPED_MINUTE_LOOP
}

internal fun resolveStartedWorkerPolicy(
    therapyActionsArmed: Boolean,
    powerSaveActive: Boolean,
    minuteLoopActive: Boolean
): StartedWorkerPolicy {
    val localOnly = !therapyActionsArmed || powerSaveActive
    return StartedWorkerPolicy(
        cycleIntent = if (localOnly) {
            AutomationRepository.AutomationCycleIntent.LOCAL_READ_ONLY
        } else {
            AutomationRepository.AutomationCycleIntent.NORMAL
        },
        skipForMinuteLoop = minuteLoopActive && !localOnly,
        cancelFutureRuntimeWork = localOnly,
        schedulePowerSaveResume = powerSaveActive,
        stopRuntimeControllers = powerSaveActive
    )
}

internal fun resolveReactiveWorkerPolicy(
    dispatchMode: ClinicalInputInvalidationMode,
    therapyActionsArmedAtStart: Boolean,
    powerSaveActiveAtStart: Boolean
): StartedWorkerPolicy {
    val currentGateClosed = !therapyActionsArmedAtStart || powerSaveActiveAtStart
    val localOnly = dispatchMode == ClinicalInputInvalidationMode.LOCAL_READ_ONLY ||
        currentGateClosed
    return StartedWorkerPolicy(
        cycleIntent = if (localOnly) {
            AutomationRepository.AutomationCycleIntent.LOCAL_READ_ONLY
        } else {
            AutomationRepository.AutomationCycleIntent.NORMAL
        },
        skipForMinuteLoop = false,
        cancelFutureRuntimeWork = currentGateClosed,
        schedulePowerSaveResume = powerSaveActiveAtStart,
        stopRuntimeControllers = powerSaveActiveAtStart
    )
}

internal enum class ReactiveWorkerFailureAction {
    RETRY,
    TERMINATE
}

internal fun resolveReactiveWorkerFailure(runAttemptCount: Int): ReactiveWorkerFailureAction =
    if (runAttemptCount < MAX_REACTIVE_WORKER_RUN_ATTEMPT_COUNT - 1) {
        ReactiveWorkerFailureAction.RETRY
    } else {
        ReactiveWorkerFailureAction.TERMINATE
    }

internal enum class ReactiveWorkerOrchestrationResult {
    SUCCESS,
    RETRY,
    FAILURE
}

internal fun interface PreparedReactiveWorkerCycle {
    suspend fun run(): Boolean
}

private enum class ReactiveWorkerPhase {
    BOUNDED_CYCLE,
    SETTLEMENT,
    COMPLETION
}

internal suspend fun runReactiveWorkerOrchestration(
    reactiveWork: ClinicalInvalidationWorkData,
    runAttemptCount: Int,
    timeoutMs: Long,
    prepare: suspend () -> PreparedReactiveWorkerCycle,
    onStarted: suspend () -> Unit,
    onCompleted: suspend (Boolean) -> Unit,
    onFailure: suspend (Throwable) -> Unit,
    acknowledge: suspend (Long, String) -> Boolean,
    terminate: suspend (Long, String) -> Boolean,
    terminateAmbiguous: suspend (Long, String) -> Boolean = terminate,
    terminateFatal: suspend (Long, String) -> Boolean,
    beforeFatalSettlement: suspend (Error) -> Unit = {},
    clinicalCycleState: ReactiveClinicalCycleState? = null
): ReactiveWorkerOrchestrationResult {
    var ownershipSettled = false
    var phase = ReactiveWorkerPhase.BOUNDED_CYCLE
    var acceptedCycle: Boolean? = null
    fun acceptedResultObserved(): Boolean =
        acceptedCycle == true || clinicalCycleState?.accepted == true
    fun rejectedResultObserved(): Boolean =
        acceptedCycle == false || clinicalCycleState?.accepted == false
    fun ambiguousInvocationObserved(): Boolean =
        clinicalCycleState?.invocationStarted == true && clinicalCycleState.accepted == null
    return try {
        val cycleAccepted = withTimeout(timeoutMs) {
            val cycle = prepare()
            onStarted()
            cycle.run()
        }
        acceptedCycle = cycleAccepted
        phase = ReactiveWorkerPhase.SETTLEMENT
        settleClinicalInvalidationCycle(
            generation = reactiveWork.generation,
            dispatchToken = reactiveWork.dispatchToken,
            cycleAccepted = cycleAccepted,
            acknowledge = acknowledge,
            terminate = terminate
        )
        ownershipSettled = true
        phase = ReactiveWorkerPhase.COMPLETION
        try {
            onCompleted(cycleAccepted)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (fatal: Error) {
            throw fatal
        } catch (failure: Exception) {
            notifyReactiveWorkerFailure(onFailure, failure)
        }
        ReactiveWorkerOrchestrationResult.SUCCESS
    } catch (timeout: TimeoutCancellationException) {
        if (!ownershipSettled && acceptedResultObserved()) {
            settleAcceptedReactiveWorkerAfterCancellation(reactiveWork, timeout, acknowledge)
        }
        if (!ownershipSettled && rejectedResultObserved()) {
            terminateReactiveWorkerAfterCancellation(reactiveWork, timeout, terminate)
        }
        if (!ownershipSettled && ambiguousInvocationObserved()) {
            terminateReactiveWorkerAfterCancellation(reactiveWork, timeout, terminateAmbiguous)
        }
        if (phase == ReactiveWorkerPhase.BOUNDED_CYCLE) {
            handleReactiveWorkerOrdinaryFailure(
                reactiveWork,
                runAttemptCount,
                timeout,
                onFailure,
                terminate,
                terminateFatal
            )
        } else {
            if (!ownershipSettled) {
                terminateReactiveWorkerAfterCancellation(reactiveWork, timeout, terminate)
            }
            throw timeout
        }
    } catch (cancelled: CancellationException) {
        val original = cancelled.originalCoroutineCopy()
        if (!ownershipSettled) {
            if (acceptedResultObserved()) {
                settleAcceptedReactiveWorkerAfterCancellation(
                    reactiveWork,
                    original,
                    acknowledge
                )
            } else if (rejectedResultObserved()) {
                terminateReactiveWorkerAfterCancellation(reactiveWork, original, terminate)
            } else if (ambiguousInvocationObserved()) {
                terminateReactiveWorkerAfterCancellation(
                    reactiveWork,
                    original,
                    terminateAmbiguous
                )
            } else {
                terminateReactiveWorkerAfterCancellation(reactiveWork, original, terminate)
            }
        }
        throw original
    } catch (fatal: Error) {
        val original = fatal.originalCoroutineCopy()
        if (!ownershipSettled) {
            if (acceptedResultObserved() && !original.isProcessFatalError()) {
                settleAcceptedReactiveWorkerAfterFatalFailure(
                    reactiveWork,
                    original,
                    acknowledge
                )
            }
            if (rejectedResultObserved() && !original.isProcessFatalError()) {
                terminateReactiveWorkerAfterFatalFailure(reactiveWork, original, terminate)
            }
            if (phase == ReactiveWorkerPhase.BOUNDED_CYCLE && !original.isProcessFatalError()) {
                try {
                    beforeFatalSettlement(original)
                } catch (processFatal: VirtualMachineError) {
                    throw processFatal
                } catch (processFatal: ThreadDeath) {
                    throw processFatal
                } catch (processFatal: LinkageError) {
                    throw processFatal
                } catch (evidenceFailure: Throwable) {
                    if (evidenceFailure !== original) original.addSuppressed(evidenceFailure)
                }
            }
            terminateReactiveWorkerAfterFatalFailure(reactiveWork, original, terminateFatal)
        }
        throw original
    } catch (failure: Exception) {
        if (ownershipSettled) {
            notifyReactiveWorkerFailure(onFailure, failure)
            ReactiveWorkerOrchestrationResult.SUCCESS
        } else if (acceptedResultObserved()) {
            settleAcceptedReactiveWorkerAfterOrdinaryFailure(
                reactiveWork,
                failure,
                acknowledge,
                onFailure
            )
        } else if (rejectedResultObserved()) {
            settleRejectedReactiveWorkerAfterOrdinaryFailure(
                reactiveWork,
                failure,
                terminate,
                onFailure
            )
        } else if (ambiguousInvocationObserved()) {
            settleAmbiguousReactiveWorkerAfterOrdinaryFailure(
                reactiveWork,
                failure,
                terminateAmbiguous,
                onFailure
            )
        } else {
            handleReactiveWorkerOrdinaryFailure(
                reactiveWork,
                runAttemptCount,
                failure,
                onFailure,
                terminate,
                terminateFatal
            )
        }
    }
}

private suspend fun settleRejectedReactiveWorkerAfterOrdinaryFailure(
    reactiveWork: ClinicalInvalidationWorkData,
    failure: Exception,
    terminate: suspend (Long, String) -> Boolean,
    onFailure: suspend (Throwable) -> Unit
): ReactiveWorkerOrchestrationResult {
    try {
        terminateReactiveDispatchNonCancellable(
            generation = reactiveWork.generation,
            dispatchToken = reactiveWork.dispatchToken,
            terminate = { generation, token -> terminate(generation, token) }
        )
    } catch (fatal: VirtualMachineError) {
        fatal.addSuppressed(failure)
        throw fatal
    } catch (fatal: ThreadDeath) {
        fatal.addSuppressed(failure)
        throw fatal
    } catch (fatal: LinkageError) {
        fatal.addSuppressed(failure)
        throw fatal
    } catch (cleanupFailure: Throwable) {
        if (cleanupFailure !== failure) failure.addSuppressed(cleanupFailure)
    }
    notifyReactiveWorkerFailure(onFailure, failure)
    return ReactiveWorkerOrchestrationResult.FAILURE
}

private suspend fun settleAmbiguousReactiveWorkerAfterOrdinaryFailure(
    reactiveWork: ClinicalInvalidationWorkData,
    failure: Exception,
    terminateAmbiguous: suspend (Long, String) -> Boolean,
    onFailure: suspend (Throwable) -> Unit
): ReactiveWorkerOrchestrationResult {
    try {
        terminateReactiveDispatchNonCancellable(
            generation = reactiveWork.generation,
            dispatchToken = reactiveWork.dispatchToken,
            terminate = { generation, token -> terminateAmbiguous(generation, token) }
        )
    } catch (fatal: VirtualMachineError) {
        fatal.addSuppressed(failure)
        throw fatal
    } catch (fatal: ThreadDeath) {
        fatal.addSuppressed(failure)
        throw fatal
    } catch (fatal: LinkageError) {
        fatal.addSuppressed(failure)
        throw fatal
    } catch (cleanupFailure: Throwable) {
        if (cleanupFailure !== failure) failure.addSuppressed(cleanupFailure)
    }
    notifyReactiveWorkerFailure(onFailure, failure)
    return ReactiveWorkerOrchestrationResult.FAILURE
}

private suspend fun settleAcceptedReactiveWorkerAfterOrdinaryFailure(
    reactiveWork: ClinicalInvalidationWorkData,
    failure: Exception,
    acknowledge: suspend (Long, String) -> Boolean,
    onFailure: suspend (Throwable) -> Unit
): ReactiveWorkerOrchestrationResult {
    try {
        terminateReactiveDispatchNonCancellable(
            generation = reactiveWork.generation,
            dispatchToken = reactiveWork.dispatchToken,
            terminate = { generation, token -> acknowledge(generation, token) }
        )
    } catch (fatal: VirtualMachineError) {
        fatal.addSuppressed(failure)
        throw fatal
    } catch (fatal: ThreadDeath) {
        fatal.addSuppressed(failure)
        throw fatal
    } catch (fatal: LinkageError) {
        fatal.addSuppressed(failure)
        throw fatal
    } catch (cleanupFailure: Throwable) {
        if (cleanupFailure !== failure) failure.addSuppressed(cleanupFailure)
    }
    notifyReactiveWorkerFailure(onFailure, failure)
    return ReactiveWorkerOrchestrationResult.SUCCESS
}

private suspend fun settleAcceptedReactiveWorkerAfterFatalFailure(
    reactiveWork: ClinicalInvalidationWorkData,
    fatal: Error,
    acknowledge: suspend (Long, String) -> Boolean
): Nothing {
    try {
        terminateReactiveDispatchNonCancellable(
            generation = reactiveWork.generation,
            dispatchToken = reactiveWork.dispatchToken,
            terminate = { generation, token -> acknowledge(generation, token) }
        )
    } catch (processFatal: VirtualMachineError) {
        processFatal.addSuppressed(fatal)
        throw processFatal
    } catch (processFatal: ThreadDeath) {
        processFatal.addSuppressed(fatal)
        throw processFatal
    } catch (processFatal: LinkageError) {
        processFatal.addSuppressed(fatal)
        throw processFatal
    } catch (cleanupFailure: Throwable) {
        if (cleanupFailure !== fatal) fatal.addSuppressed(cleanupFailure)
    }
    throw fatal
}

private suspend fun settleAcceptedReactiveWorkerAfterCancellation(
    reactiveWork: ClinicalInvalidationWorkData,
    cancelled: CancellationException,
    acknowledge: suspend (Long, String) -> Boolean
): Nothing {
    try {
        terminateReactiveDispatchNonCancellable(
            generation = reactiveWork.generation,
            dispatchToken = reactiveWork.dispatchToken,
            terminate = { generation, token -> acknowledge(generation, token) }
        )
    } catch (fatal: Error) {
        fatal.addSuppressed(cancelled)
        throw fatal
    } catch (cleanupFailure: Exception) {
        cancelled.addSuppressed(cleanupFailure)
    }
    throw cancelled
}

private suspend fun handleReactiveWorkerOrdinaryFailure(
    reactiveWork: ClinicalInvalidationWorkData,
    runAttemptCount: Int,
    failure: Throwable,
    onFailure: suspend (Throwable) -> Unit,
    terminate: suspend (Long, String) -> Boolean,
    terminateFatal: suspend (Long, String) -> Boolean
): ReactiveWorkerOrchestrationResult {
    try {
        notifyReactiveWorkerFailure(onFailure, failure)
    } catch (cancelled: CancellationException) {
        terminateReactiveWorkerAfterCancellation(reactiveWork, cancelled, terminate)
        throw cancelled
    } catch (fatal: Error) {
        terminateReactiveWorkerAfterFatalFailure(reactiveWork, fatal, terminateFatal)
        throw fatal
    }
    return when (resolveReactiveWorkerFailure(runAttemptCount)) {
        ReactiveWorkerFailureAction.RETRY -> ReactiveWorkerOrchestrationResult.RETRY
        ReactiveWorkerFailureAction.TERMINATE -> {
            terminateReactiveDispatchNonCancellable(
                generation = reactiveWork.generation,
                dispatchToken = reactiveWork.dispatchToken,
                terminate = { generation, token -> terminate(generation, token) }
            )
            ReactiveWorkerOrchestrationResult.FAILURE
        }
    }
}

private suspend fun notifyReactiveWorkerFailure(
    onFailure: suspend (Throwable) -> Unit,
    failure: Throwable
) {
    try {
        onFailure(failure)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (fatal: Error) {
        throw fatal
    } catch (_: Exception) {
        // Audit failure cannot change reactive ownership or worker retry semantics.
    }
}

private suspend fun terminateReactiveWorkerAfterCancellation(
    reactiveWork: ClinicalInvalidationWorkData,
    cancelled: CancellationException,
    terminate: suspend (Long, String) -> Boolean
): Nothing {
    try {
        terminateReactiveDispatchNonCancellable(
            generation = reactiveWork.generation,
            dispatchToken = reactiveWork.dispatchToken,
            terminate = { generation, token -> terminate(generation, token) }
        )
    } catch (fatal: Error) {
        fatal.addSuppressed(cancelled)
        throw fatal
    } catch (cleanupFailure: Exception) {
        cancelled.addSuppressed(cleanupFailure)
    }
    throw cancelled
}

private suspend fun terminateReactiveWorkerAfterFatalFailure(
    reactiveWork: ClinicalInvalidationWorkData,
    fatal: Error,
    terminateFatal: suspend (Long, String) -> Boolean
): Nothing {
    try {
        terminateReactiveDispatchNonCancellable(
            generation = reactiveWork.generation,
            dispatchToken = reactiveWork.dispatchToken,
            terminate = { generation, token -> terminateFatal(generation, token) }
        )
    } catch (processFatal: VirtualMachineError) {
        if (processFatal !== fatal) processFatal.addSuppressed(fatal)
        throw processFatal
    } catch (processFatal: ThreadDeath) {
        if (processFatal !== fatal) processFatal.addSuppressed(fatal)
        throw processFatal
    } catch (processFatal: LinkageError) {
        if (processFatal !== fatal) processFatal.addSuppressed(fatal)
        throw processFatal
    } catch (cleanupFailure: Throwable) {
        if (cleanupFailure !== fatal) fatal.addSuppressed(cleanupFailure)
    }
    throw fatal
}

internal suspend fun runStartedWorkerCycle(
    policy: StartedWorkerPolicy,
    runAutomationCycle: suspend (AutomationRepository.AutomationCycleIntent) -> Boolean,
    cancelFutureRuntimeWork: suspend () -> Unit,
    schedulePowerSaveResume: suspend () -> Unit,
    stopRuntimeControllers: suspend () -> Unit
): StartedWorkerOutcome {
    if (policy.skipForMinuteLoop) return StartedWorkerOutcome.SKIPPED_MINUTE_LOOP
    val accepted = try {
        runAutomationCycle(policy.cycleIntent)
    } finally {
        if (policy.cancelFutureRuntimeWork) cancelFutureRuntimeWork()
        if (policy.schedulePowerSaveResume) schedulePowerSaveResume()
        if (policy.stopRuntimeControllers) stopRuntimeControllers()
    }
    return if (accepted) StartedWorkerOutcome.EXECUTED else StartedWorkerOutcome.NOT_ACCEPTED
}

internal suspend fun runReactiveWorkerCycle(
    policy: StartedWorkerPolicy,
    runAutomationCycle: suspend (AutomationRepository.AutomationCycleIntent) -> Boolean,
    cancelFutureRuntimeWork: suspend () -> Unit,
    schedulePowerSaveResume: suspend () -> Unit,
    stopRuntimeControllers: suspend () -> Unit
): Boolean = try {
    runAutomationCycle(policy.cycleIntent)
} finally {
    if (policy.cancelFutureRuntimeWork) cancelFutureRuntimeWork()
    if (policy.schedulePowerSaveResume) schedulePowerSaveResume()
    if (policy.stopRuntimeControllers) stopRuntimeControllers()
}

internal enum class ReactiveWorkerPreflightAction {
    PROCEED,
    SUCCESS,
    FAILURE,
    RETRY
}

internal data class ReactiveWorkerPreflightResult(
    val action: ReactiveWorkerPreflightAction,
    val evidence: ClinicalInvalidationExecutionEvidence? = null
)

internal class ReactiveWorkerDeadline(
    timeoutMs: Long,
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000L }
) {
    private val deadlineMs = deadlineFrom(nowMs(), timeoutMs)

    fun remainingMs(): Long {
        val current = nowMs()
        return if (current >= deadlineMs) 0L else deadlineMs - current
    }

    private fun deadlineFrom(startMs: Long, timeoutMs: Long): Long = when {
        timeoutMs <= 0L -> startMs
        startMs >= Long.MAX_VALUE - timeoutMs -> Long.MAX_VALUE
        else -> startMs + timeoutMs
    }
}

internal suspend fun runReactiveWorkerEvidencePreflight(
    reactiveWork: ClinicalInvalidationWorkData,
    runAttemptCount: Int,
    timeoutMs: Long = DEFAULT_REACTIVE_WORKER_TIMEOUT_MS,
    readEvidence: suspend () -> ClinicalInvalidationExecutionEvidence?,
    onFailure: suspend (Throwable) -> Unit,
    acknowledge: suspend (Long, String) -> Boolean,
    terminate: suspend (Long, String) -> Boolean,
    terminateAmbiguous: suspend (Long, String) -> Boolean,
    terminateFatal: suspend (Long, String) -> Boolean
): ReactiveWorkerPreflightResult {
    val evidence = try {
        withTimeout(timeoutMs) {
            readEvidence()
        }
    } catch (cancelled: CancellationException) {
        terminateReactiveWorkerAfterCancellation(
            reactiveWork,
            cancelled.originalCoroutineCopy(),
            terminateAmbiguous
        )
    } catch (fatal: Error) {
        terminateReactiveWorkerAfterFatalFailure(
            reactiveWork,
            fatal.originalCoroutineCopy(),
            terminateFatal
        )
    } catch (failure: Exception) {
        try {
            notifyReactiveWorkerFailure(onFailure, failure)
        } catch (cancelled: CancellationException) {
            terminateReactiveWorkerAfterCancellation(
                reactiveWork,
                cancelled,
                terminateAmbiguous
            )
        } catch (fatal: Error) {
            terminateReactiveWorkerAfterFatalFailure(reactiveWork, fatal, terminateFatal)
        }
        return when (resolveReactiveWorkerFailure(runAttemptCount)) {
            ReactiveWorkerFailureAction.RETRY -> ReactiveWorkerPreflightResult(
                ReactiveWorkerPreflightAction.RETRY
            )
            ReactiveWorkerFailureAction.TERMINATE -> {
                terminateReactiveDispatchNonCancellable(
                    generation = reactiveWork.generation,
                    dispatchToken = reactiveWork.dispatchToken,
                    terminate = { generation, token ->
                        terminateAmbiguous(generation, token)
                    }
                )
                ReactiveWorkerPreflightResult(ReactiveWorkerPreflightAction.FAILURE)
            }
        }
    }
    val recovery = recoverReactiveWorkFromExecutionEvidence(
        reactiveWork = reactiveWork,
        evidence = evidence,
        acknowledge = acknowledge,
        terminateAmbiguous = terminateAmbiguous,
        terminateFatal = terminateFatal
    )
    val action = when (recovery) {
        ReactiveExecutionEvidenceRecovery.NONE,
        ReactiveExecutionEvidenceRecovery.REDRIVE_ALLOWED -> ReactiveWorkerPreflightAction.PROCEED
        ReactiveExecutionEvidenceRecovery.ACKNOWLEDGED -> ReactiveWorkerPreflightAction.SUCCESS
        ReactiveExecutionEvidenceRecovery.AMBIGUOUSLY_TERMINATED,
        ReactiveExecutionEvidenceRecovery.FATALLY_TERMINATED,
        ReactiveExecutionEvidenceRecovery.FAIL_CLOSED_UNKNOWN,
        ReactiveExecutionEvidenceRecovery.FAIL_CLOSED_STALE_OWNER ->
            ReactiveWorkerPreflightAction.FAILURE
    }
    return ReactiveWorkerPreflightResult(action, evidence)
}

private fun CancellationException.originalCoroutineCopy(): CancellationException {
    var original = this
    while (
        original.cause is CancellationException &&
        original.cause!!::class == original::class &&
        original.cause!!.message == original.message
    ) {
        original = original.cause as CancellationException
    }
    return original
}

private fun Error.originalCoroutineCopy(): Error {
    var original = this
    while (
        original.cause is Error &&
        original.cause!!::class == original::class &&
        original.cause!!.message == original.message
    ) {
        original = original.cause as Error
    }
    return original
}

internal enum class SyncAndAutomateWorkRoute {
    PERIODIC,
    REACTIVE,
    MALFORMED_REACTIVE
}

internal fun resolveSyncAndAutomateWorkRoute(data: androidx.work.Data): SyncAndAutomateWorkRoute =
    when {
        ClinicalInvalidationWorkData.from(data) != null -> SyncAndAutomateWorkRoute.REACTIVE
        ClinicalInvalidationWorkData.hasReservedFields(data) ->
            SyncAndAutomateWorkRoute.MALFORMED_REACTIVE
        else -> SyncAndAutomateWorkRoute.PERIODIC
    }

class SyncAndAutomateWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val workRoute = resolveSyncAndAutomateWorkRoute(inputData)
        if (workRoute == SyncAndAutomateWorkRoute.MALFORMED_REACTIVE) {
            return Result.failure()
        }
        val container = (applicationContext as CopilotApp).container
        if (workRoute == SyncAndAutomateWorkRoute.REACTIVE) {
            return runReactiveWork(container, checkNotNull(ClinicalInvalidationWorkData.from(inputData)))
        }

        val settings = container.settingsStore.settings.first()
        PowerSaveRuntimeState.update(settings)
        TherapyActionRuntimeState.update(settings)
        val powerSaveActive = PowerSaveController.isActive(settings)
        val executionPolicy = resolveStartedWorkerPolicy(
            therapyActionsArmed = settings.therapyActionsArmed,
            powerSaveActive = powerSaveActive,
            minuteLoopActive = LocalNightscoutForegroundService.isMinuteLoopActive()
        )
        if (executionPolicy.skipForMinuteLoop) {
            container.auditLogger.infoThrottled(
                throttleKey = "automation_worker_skipped:minute_loop_active",
                intervalMs = 15 * 60_000L,
                message = "automation_worker_skipped",
                metadata = mapOf(
                    "workId" to id.toString(),
                    "runAttemptCount" to runAttemptCount,
                    "reason" to "minute_loop_active"
                )
            )
            return Result.success()
        }
        val startedAt = System.currentTimeMillis()
        container.auditLogger.info(
            "automation_worker_started",
            mapOf(
                "workId" to id.toString(),
                "runAttemptCount" to runAttemptCount
            )
        )
        return try {
            val outcome = withTimeout(WORKER_TIMEOUT_MS) {
                runStartedWorkerCycle(
                    policy = executionPolicy,
                    runAutomationCycle = { intent ->
                        when (intent) {
                            AutomationRepository.AutomationCycleIntent.NORMAL ->
                                container.automationRepository.runAutomationCycle() != null
                            AutomationRepository.AutomationCycleIntent.LOCAL_READ_ONLY ->
                                container.automationRepository.runLocalReadOnlyCycle() != null
                            AutomationRepository.AutomationCycleIntent.SENSITIVITY_SOURCE_CHANGE ->
                                error("worker cannot start a sensitivity source-change cycle")
                        }
                    },
                    cancelFutureRuntimeWork = { WorkScheduler.cancelRuntimeWork(applicationContext) },
                    schedulePowerSaveResume = {
                        WorkScheduler.schedulePowerSaveResume(applicationContext, settings.powerSaveUntilMs)
                    },
                    stopRuntimeControllers = container::stopRuntimeControllers
                )
            }
            container.auditLogger.info(
                "automation_worker_completed",
                mapOf(
                    "workId" to id.toString(),
                    "runAttemptCount" to runAttemptCount,
                    "cycleAccepted" to (outcome == StartedWorkerOutcome.EXECUTED),
                    "durationMs" to (System.currentTimeMillis() - startedAt)
                )
            )
            Result.success()
        } catch (cancelled: CancellationException) {
            if (cancelled !is TimeoutCancellationException) {
                throw cancelled
            }
            handleStartedWorkerFailure(container, startedAt, cancelled)
        } catch (error: Throwable) {
            handleStartedWorkerFailure(container, startedAt, error)
        }
    }

    private suspend fun runReactiveWork(
        container: io.aaps.copilot.service.AppContainer,
        reactiveWork: ClinicalInvalidationWorkData
    ): Result {
        val startedAt = System.currentTimeMillis()
        val evidenceStore = container.clinicalInvalidationExecutionEvidenceStore
        val clinicalCycleState = ReactiveClinicalCycleState()
        val deadline = ReactiveWorkerDeadline(WORKER_TIMEOUT_MS)
        val preflight = runReactiveWorkerEvidencePreflight(
            reactiveWork = reactiveWork,
            runAttemptCount = runAttemptCount,
            timeoutMs = deadline.remainingMs(),
            readEvidence = evidenceStore::read,
            onFailure = { error -> logWorkerFailure(container, startedAt, error) },
            acknowledge = container.clinicalInputInvalidationCoordinator::acknowledge,
            terminate = container.clinicalInputInvalidationCoordinator::dispatchTerminated,
            terminateAmbiguous = container.clinicalInputInvalidationCoordinator::dispatchAmbiguous,
            terminateFatal = container.clinicalInputInvalidationCoordinator::dispatchFatal
        )
        val preflightOutput = preflight.evidence?.toWorkData() ?: androidx.work.Data.EMPTY
        when (preflight.action) {
            ReactiveWorkerPreflightAction.SUCCESS -> return Result.success(preflightOutput)
            ReactiveWorkerPreflightAction.FAILURE -> return Result.failure(preflightOutput)
            ReactiveWorkerPreflightAction.RETRY -> return Result.retry()
            ReactiveWorkerPreflightAction.PROCEED -> Unit
        }
        val outcome = runReactiveWorkerOrchestration(
            reactiveWork = reactiveWork,
            runAttemptCount = runAttemptCount,
            timeoutMs = deadline.remainingMs(),
            prepare = {
                val settings = container.settingsStore.settings.first()
                PowerSaveRuntimeState.update(settings)
                TherapyActionRuntimeState.update(settings)
                val executionPolicy = resolveReactiveWorkerPolicy(
                    dispatchMode = reactiveWork.mode,
                    therapyActionsArmedAtStart = settings.therapyActionsArmed,
                    powerSaveActiveAtStart = PowerSaveController.isActive(settings)
                )
                PreparedReactiveWorkerCycle {
                    runClinicalCycleWithExecutionEvidence(
                        reactiveWork,
                        evidenceStore,
                        clinicalCycleState
                    ) {
                        runReactiveWorkerCycle(
                            policy = executionPolicy,
                            runAutomationCycle = { intent ->
                                when (intent) {
                                    AutomationRepository.AutomationCycleIntent.NORMAL ->
                                        container.automationRepository.runAutomationCycle() != null
                                    AutomationRepository.AutomationCycleIntent.LOCAL_READ_ONLY ->
                                        container.automationRepository.runLocalReadOnlyCycle() != null
                                    AutomationRepository.AutomationCycleIntent.SENSITIVITY_SOURCE_CHANGE ->
                                        error("worker cannot start a sensitivity source-change cycle")
                                }
                            },
                            cancelFutureRuntimeWork = {
                                WorkScheduler.cancelRuntimeWork(applicationContext)
                            },
                            schedulePowerSaveResume = {
                                WorkScheduler.schedulePowerSaveResume(
                                    applicationContext,
                                    settings.powerSaveUntilMs
                                )
                            },
                            stopRuntimeControllers = container::stopRuntimeControllers
                        )
                    }
                }
            },
            onStarted = {
                container.auditLogger.info(
                    "automation_worker_started",
                    mapOf(
                        "workId" to id.toString(),
                        "runAttemptCount" to runAttemptCount
                    )
                )
            },
            onCompleted = { cycleAccepted ->
                container.auditLogger.info(
                    "automation_worker_completed",
                    mapOf(
                        "workId" to id.toString(),
                        "runAttemptCount" to runAttemptCount,
                        "cycleAccepted" to cycleAccepted,
                        "durationMs" to (System.currentTimeMillis() - startedAt)
                    )
                )
            },
            onFailure = { error -> logWorkerFailure(container, startedAt, error) },
            acknowledge = container.clinicalInputInvalidationCoordinator::acknowledge,
            terminate = container.clinicalInputInvalidationCoordinator::dispatchTerminated,
            terminateAmbiguous = container.clinicalInputInvalidationCoordinator::dispatchAmbiguous,
            terminateFatal = container.clinicalInputInvalidationCoordinator::dispatchFatal,
            beforeFatalSettlement = { fatal ->
                if (!fatal.isProcessFatalError()) {
                    evidenceStore.write(
                        reactiveWork.executionEvidence(ClinicalInvalidationExecutionOutcome.FATAL)
                    )
                }
            },
            clinicalCycleState = clinicalCycleState
        )
        val terminalEvidence = try {
            evidenceStore.read()?.takeIf {
                it.generation == reactiveWork.generation &&
                    it.dispatchToken == reactiveWork.dispatchToken
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (fatal: Error) {
            throw fatal
        } catch (failure: Exception) {
            logWorkerFailure(container, startedAt, failure)
            null
        }
        return when (outcome) {
            ReactiveWorkerOrchestrationResult.SUCCESS ->
                Result.success(terminalEvidence?.toWorkData() ?: androidx.work.Data.EMPTY)
            ReactiveWorkerOrchestrationResult.RETRY -> Result.retry()
            ReactiveWorkerOrchestrationResult.FAILURE ->
                Result.failure(terminalEvidence?.toWorkData() ?: androidx.work.Data.EMPTY)
        }
    }

    private suspend fun handleStartedWorkerFailure(
        container: io.aaps.copilot.service.AppContainer,
        startedAt: Long,
        error: Throwable
    ): Result {
        logWorkerFailure(container, startedAt, error)
        return Result.retry()
    }

    private suspend fun logWorkerFailure(
        container: io.aaps.copilot.service.AppContainer,
        startedAt: Long,
        error: Throwable
    ) {
        container.auditLogger.warn(
            "automation_worker_failed",
            mapOf(
                "workId" to id.toString(),
                "runAttemptCount" to runAttemptCount,
                "durationMs" to (System.currentTimeMillis() - startedAt),
                "timeout" to (error is TimeoutCancellationException),
                "error" to (error.message ?: error::class.simpleName.orEmpty())
            )
        )
    }

    private companion object {
        private const val WORKER_TIMEOUT_MS = DEFAULT_REACTIVE_WORKER_TIMEOUT_MS
    }
}

private const val DEFAULT_REACTIVE_WORKER_TIMEOUT_MS = 240_000L
private const val MAX_REACTIVE_WORKER_RUN_ATTEMPT_COUNT = 3
