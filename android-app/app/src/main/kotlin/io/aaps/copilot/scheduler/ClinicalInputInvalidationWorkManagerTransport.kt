package io.aaps.copilot.scheduler

import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.Operation
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkInfo
import androidx.work.await
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.TimeUnit

internal fun clinicalInvalidationWorkTag(dispatchToken: String): String =
    "copilot.clinical-invalidation.token.$dispatchToken"

internal data class ClinicalInvalidationWorkInfo(
    val state: WorkInfo.State,
    val outputData: androidx.work.Data
)

private data class ClinicalInvalidationEvidenceSnapshot(
    val evidence: ClinicalInvalidationExecutionEvidence?
)

private data class ClinicalInvalidationEvidenceEvaluation(
    val outcome: ClinicalInvalidationExecutionOutcome? = null,
    val invalid: Boolean = false
)

internal class WorkManagerClinicalInvalidationOwnershipStatusSource(
    private val queryWorkInfos: suspend (String) -> List<ClinicalInvalidationWorkInfo>,
    private val queryEvidence: suspend (ClinicalInputInvalidationDispatch) ->
        ClinicalInvalidationExecutionEvidence?,
    private val timeoutMs: Long = DEFAULT_QUERY_TIMEOUT_MS,
    private val nowMs: () -> Long = MONOTONIC_NOW_MS
) : ClinicalInvalidationOwnershipStatusSource {
    constructor(
        queryStates: suspend (String) -> List<WorkInfo.State>,
        timeoutMs: Long = DEFAULT_QUERY_TIMEOUT_MS
    ) : this(
        queryWorkInfos = { tag ->
            queryStates(tag).map { state ->
                ClinicalInvalidationWorkInfo(state, androidx.work.Data.EMPTY)
            }
        },
        queryEvidence = { null },
        timeoutMs = timeoutMs
    )

    override suspend fun status(
        dispatch: ClinicalInputInvalidationDispatch
    ): ClinicalInvalidationOwnershipStatus {
        val deadlineMs = deadlineFrom(nowMs(), timeoutMs)
        val workInfos = try {
            val remainingMs = remainingUntil(deadlineMs)
            if (remainingMs == 0L) return ClinicalInvalidationOwnershipStatus.UNKNOWN
            withTimeoutOrNull(remainingMs) {
                queryWorkInfos(clinicalInvalidationWorkTag(dispatch.dispatchToken))
            } ?: return ClinicalInvalidationOwnershipStatus.UNKNOWN
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (fatal: Error) {
            throw fatal
        } catch (_: Exception) {
            return ClinicalInvalidationOwnershipStatus.UNKNOWN
        }
        val outputEvidence = evaluateTerminalOutputEvidence(workInfos, dispatch)
        if (outputEvidence.invalid) return ClinicalInvalidationOwnershipStatus.UNKNOWN
        val durableEvidence = try {
            val remainingMs = remainingUntil(deadlineMs)
            if (remainingMs == 0L) {
                return statusFromEvidence(
                    workInfos = workInfos,
                    outputEvidence = outputEvidence,
                    durableEvidenceAvailable = false
                )
            }
            withTimeoutOrNull(remainingMs) {
                ClinicalInvalidationEvidenceSnapshot(queryEvidence(dispatch))
            }
                ?: return statusFromEvidence(
                    workInfos = workInfos,
                    outputEvidence = outputEvidence,
                    durableEvidenceAvailable = false
                )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (fatal: Error) {
            throw fatal
        } catch (_: MalformedClinicalInvalidationExecutionEvidenceException) {
            return if (workInfos.any { it.state in ACTIVE_STATES }) {
                ClinicalInvalidationOwnershipStatus.ACTIVE
            } else {
                ClinicalInvalidationOwnershipStatus.AMBIGUOUS_TERMINAL
            }
        } catch (_: Exception) {
            return statusFromEvidence(
                workInfos = workInfos,
                outputEvidence = outputEvidence,
                durableEvidenceAvailable = false
            )
        }.evidence

        return statusFromEvidence(
            workInfos = workInfos,
            outputEvidence = outputEvidence,
            durableEvidenceAvailable = true,
            durableEvidence = durableEvidence,
            dispatch = dispatch
        )
    }

    private fun evaluateTerminalOutputEvidence(
        workInfos: List<ClinicalInvalidationWorkInfo>,
        dispatch: ClinicalInputInvalidationDispatch
    ): ClinicalInvalidationEvidenceEvaluation {
        val evidence = workInfos
            .filter { it.state.isFinished }
            .mapNotNull { info ->
                val parsed = ClinicalInvalidationExecutionEvidence.fromWorkData(info.outputData)
                when {
                    parsed != null -> parsed
                    ClinicalInvalidationExecutionEvidence.hasEvidenceFields(info.outputData) ->
                        return ClinicalInvalidationEvidenceEvaluation(invalid = true)
                    else -> null
                }
            }
        if (evidence.any { !it.matches(dispatch) }) {
            return ClinicalInvalidationEvidenceEvaluation(invalid = true)
        }
        val outcomes = evidence.mapTo(linkedSetOf()) { it.outcome }
        return if (outcomes.size > 1) {
            ClinicalInvalidationEvidenceEvaluation(invalid = true)
        } else {
            ClinicalInvalidationEvidenceEvaluation(outcome = outcomes.singleOrNull())
        }
    }

    private fun remainingUntil(deadlineMs: Long): Long {
        val current = nowMs()
        return if (current >= deadlineMs) 0L else deadlineMs - current
    }

    private fun statusFromEvidence(
        workInfos: List<ClinicalInvalidationWorkInfo>,
        outputEvidence: ClinicalInvalidationEvidenceEvaluation,
        durableEvidenceAvailable: Boolean,
        durableEvidence: ClinicalInvalidationExecutionEvidence? = null,
        dispatch: ClinicalInputInvalidationDispatch? = null
    ): ClinicalInvalidationOwnershipStatus {
        if (outputEvidence.invalid) return ClinicalInvalidationOwnershipStatus.UNKNOWN
        if (durableEvidenceAvailable && durableEvidence != null) {
            if (dispatch == null || !durableEvidence.matches(dispatch)) {
                return ClinicalInvalidationOwnershipStatus.UNKNOWN
            }
            if (
                outputEvidence.outcome != null &&
                outputEvidence.outcome != durableEvidence.outcome
            ) {
                return ClinicalInvalidationOwnershipStatus.UNKNOWN
            }
        }
        val outcome = durableEvidence?.outcome ?: outputEvidence.outcome
        val active = workInfos.any { it.state in ACTIVE_STATES }
        return when (outcome) {
            ClinicalInvalidationExecutionOutcome.ACCEPTED ->
                ClinicalInvalidationOwnershipStatus.ACCEPTED_TERMINAL
            ClinicalInvalidationExecutionOutcome.FATAL ->
                ClinicalInvalidationOwnershipStatus.FATAL_TERMINAL
            ClinicalInvalidationExecutionOutcome.STARTED_UNKNOWN -> {
                if (active) ClinicalInvalidationOwnershipStatus.ACTIVE
                else ClinicalInvalidationOwnershipStatus.AMBIGUOUS_TERMINAL
            }
            ClinicalInvalidationExecutionOutcome.UNACCEPTED -> {
                if (active) ClinicalInvalidationOwnershipStatus.ACTIVE
                else ClinicalInvalidationOwnershipStatus.ABSENT_OR_TERMINAL
            }
            null -> when {
                active -> ClinicalInvalidationOwnershipStatus.ACTIVE
                durableEvidenceAvailable -> ClinicalInvalidationOwnershipStatus.ABSENT_OR_TERMINAL
                else -> ClinicalInvalidationOwnershipStatus.AMBIGUOUS_TERMINAL
            }
        }
    }

    private companion object {
        const val DEFAULT_QUERY_TIMEOUT_MS = 1_500L
        val MONOTONIC_NOW_MS: () -> Long = { System.nanoTime() / 1_000_000L }

        fun deadlineFrom(startMs: Long, timeoutMs: Long): Long = when {
            timeoutMs <= 0L -> startMs
            startMs >= Long.MAX_VALUE - timeoutMs -> Long.MAX_VALUE
            else -> startMs + timeoutMs
        }
        val ACTIVE_STATES = setOf(
            WorkInfo.State.ENQUEUED,
            WorkInfo.State.RUNNING,
            WorkInfo.State.BLOCKED
        )
    }
}

internal class ClinicalInputInvalidationWorkManagerTransport(
    private val enqueueUniqueWork: (OneTimeWorkRequest) -> Operation,
    private val cancelUniqueWork: (String) -> Operation
) {
    suspend fun enqueue(request: ClinicalInputInvalidationDispatch): Boolean {
        val work = OneTimeWorkRequestBuilder<SyncAndAutomateWorker>()
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .setInputData(request.toWorkData())
            .addTag(clinicalInvalidationWorkTag(request.dispatchToken))
            .keepResultsForAtLeast(1L, TimeUnit.DAYS)
            .build()
        return try {
            enqueueUniqueWork(work).await()
            true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (fatal: Error) {
            throw fatal
        } catch (_: Exception) {
            false
        }
    }

    fun cancelGateClosedRuntimeWork() {
        GATE_CLOSED_RUNTIME_WORK_NAMES.forEach { name -> cancelUniqueWork(name) }
    }

    companion object {
        fun enqueueWithWorkManager(
            enqueue: (String, ExistingWorkPolicy, OneTimeWorkRequest) -> Operation,
            cancel: (String) -> Operation
        ) = ClinicalInputInvalidationWorkManagerTransport(
            enqueueUniqueWork = { request ->
                enqueue(WorkScheduler.REACTIVE_WORK_NAME, ExistingWorkPolicy.REPLACE, request)
            },
            cancelUniqueWork = cancel
        )

        private val GATE_CLOSED_RUNTIME_WORK_NAMES = listOf(
            WorkScheduler.SYNC_WORK_NAME,
            WorkScheduler.ANALYSIS_WORK_NAME,
            WorkScheduler.CIRCADIAN_TARGET_WORK_NAME,
            WorkScheduler.CIRCADIAN_TARGET_IMMEDIATE_WORK_NAME,
            WorkScheduler.LEGACY_RUNTIME_KEEPALIVE_WORK_NAME
        )
    }
}
