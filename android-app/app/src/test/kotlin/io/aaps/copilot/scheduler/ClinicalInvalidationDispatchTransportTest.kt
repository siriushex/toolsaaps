package io.aaps.copilot.scheduler

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.work.Operation
import androidx.work.WorkInfo
import com.google.common.truth.Truth.assertThat
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import io.aaps.copilot.data.repository.AutomationRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ClinicalInvalidationDispatchTransportTest {

    @Test
    fun workInputRoundTripsGenerationModeAndAttempt() {
        val request = ClinicalInputInvalidationDispatch(
            generation = 41L,
            dispatchToken = "dispatch-41",
            mode = ClinicalInputInvalidationMode.LOCAL_READ_ONLY,
            sources = setOf(ClinicalInputInvalidationSource.BROADCAST_INGEST),
            attempt = 1
        )

        val restored = ClinicalInvalidationWorkData.from(request.toWorkData())

        assertThat(restored).isEqualTo(
            ClinicalInvalidationWorkData(
                generation = 41L,
                dispatchToken = "dispatch-41",
                mode = ClinicalInputInvalidationMode.LOCAL_READ_ONLY,
                coordinatorAttempt = 1
            )
        )
    }

    @Test
    fun missingOrMalformedWorkInputIsNotTreatedAsCoordinatorDispatch() {
        val malformedReserved = androidx.work.Data.Builder()
            .putLong("clinical_invalidation_generation", 7L)
            .putString("clinical_invalidation_mode", "WRITER_MODE")
            .build()

        assertThat(ClinicalInvalidationWorkData.from(androidx.work.Data.EMPTY)).isNull()
        assertThat(ClinicalInvalidationWorkData.from(malformedReserved)).isNull()
        assertThat(resolveSyncAndAutomateWorkRoute(androidx.work.Data.EMPTY))
            .isEqualTo(SyncAndAutomateWorkRoute.PERIODIC)
        assertThat(resolveSyncAndAutomateWorkRoute(request().toWorkData()))
            .isEqualTo(SyncAndAutomateWorkRoute.REACTIVE)
        assertThat(resolveSyncAndAutomateWorkRoute(malformedReserved))
            .isEqualTo(SyncAndAutomateWorkRoute.MALFORMED_REACTIVE)
    }

    @Test
    fun fixedReactiveModeCannotUpgradeLocalReadOnlyAtWorkerStart() {
        val policy = resolveReactiveWorkerPolicy(
            dispatchMode = ClinicalInputInvalidationMode.LOCAL_READ_ONLY,
            therapyActionsArmedAtStart = true,
            powerSaveActiveAtStart = false
        )

        assertThat(policy.cycleIntent)
            .isEqualTo(AutomationRepository.AutomationCycleIntent.LOCAL_READ_ONLY)
        assertThat(policy.skipForMinuteLoop).isFalse()
        assertThat(policy.cancelFutureRuntimeWork).isFalse()
        assertThat(policy.schedulePowerSaveResume).isFalse()
        assertThat(policy.stopRuntimeControllers).isFalse()
    }

    @Test
    fun fixedNormalReactiveModeIsNotSkippedByForegroundRace() {
        val policy = resolveReactiveWorkerPolicy(
            dispatchMode = ClinicalInputInvalidationMode.NORMAL,
            therapyActionsArmedAtStart = true,
            powerSaveActiveAtStart = false
        )

        assertThat(policy.cycleIntent).isEqualTo(AutomationRepository.AutomationCycleIntent.NORMAL)
        assertThat(policy.skipForMinuteLoop).isFalse()
        assertThat(policy.cancelFutureRuntimeWork).isFalse()
    }

    @Test
    fun queuedNormalDispatchDowngradesWhenTherapyGateClosesBeforeWorkerStart() {
        val disarmed = resolveReactiveWorkerPolicy(
            dispatchMode = ClinicalInputInvalidationMode.NORMAL,
            therapyActionsArmedAtStart = false,
            powerSaveActiveAtStart = false
        )
        val powerSave = resolveReactiveWorkerPolicy(
            dispatchMode = ClinicalInputInvalidationMode.NORMAL,
            therapyActionsArmedAtStart = true,
            powerSaveActiveAtStart = true
        )

        assertThat(disarmed.cycleIntent)
            .isEqualTo(AutomationRepository.AutomationCycleIntent.LOCAL_READ_ONLY)
        assertThat(powerSave.cycleIntent)
            .isEqualTo(AutomationRepository.AutomationCycleIntent.LOCAL_READ_ONLY)
        assertThat(disarmed.cancelFutureRuntimeWork).isTrue()
        assertThat(powerSave.cancelFutureRuntimeWork).isTrue()
    }

    @Test
    fun gateClosurePreservesAlreadyQueuedReactiveWork() {
        val cancelled = mutableListOf<String>()
        val transport = ClinicalInputInvalidationWorkManagerTransport(
            enqueueUniqueWork = { completedOperation() },
            cancelUniqueWork = { name -> cancelled += name; completedOperation() }
        )

        transport.cancelGateClosedRuntimeWork()

        assertThat(cancelled).containsExactly(
            WorkScheduler.SYNC_WORK_NAME,
            WorkScheduler.ANALYSIS_WORK_NAME,
            WorkScheduler.CIRCADIAN_TARGET_WORK_NAME,
            WorkScheduler.CIRCADIAN_TARGET_IMMEDIATE_WORK_NAME,
            WorkScheduler.LEGACY_RUNTIME_KEEPALIVE_WORK_NAME
        )
    }

    @Test
    fun workManagerRequestCarriesStableExactTokenTag() = runTest {
        var tags = emptySet<String>()
        val transport = ClinicalInputInvalidationWorkManagerTransport(
            enqueueUniqueWork = { request ->
                tags = request.tags
                completedOperation()
            },
            cancelUniqueWork = { completedOperation() }
        )

        assertThat(transport.enqueue(request())).isTrue()

        assertThat(tags).contains(clinicalInvalidationWorkTag("transport-token"))
    }

    @Test
    fun ownershipStatusAdapterMatchesActiveAndTerminalWorkByExactTokenTag() = runTest {
        val queriedTags = mutableListOf<String>()
        val active = WorkManagerClinicalInvalidationOwnershipStatusSource(
            queryStates = { tag -> queriedTags += tag; listOf(WorkInfo.State.BLOCKED) },
            timeoutMs = 100L
        )
        val terminal = WorkManagerClinicalInvalidationOwnershipStatusSource(
            queryStates = { listOf(WorkInfo.State.SUCCEEDED, WorkInfo.State.CANCELLED) },
            timeoutMs = 100L
        )

        assertThat(active.status(request())).isEqualTo(ClinicalInvalidationOwnershipStatus.ACTIVE)
        assertThat(queriedTags).containsExactly(clinicalInvalidationWorkTag("transport-token"))
        assertThat(terminal.status(request()))
            .isEqualTo(ClinicalInvalidationOwnershipStatus.ABSENT_OR_TERMINAL)
    }

    @Test
    fun terminalOutputEvidenceDistinguishesAcceptedUnacceptedAmbiguousFatalAndTokenMismatch() = runTest {
        val accepted = request().executionEvidence(ClinicalInvalidationExecutionOutcome.ACCEPTED)
        val unaccepted = request().executionEvidence(ClinicalInvalidationExecutionOutcome.UNACCEPTED)
        val ambiguous = request().executionEvidence(ClinicalInvalidationExecutionOutcome.STARTED_UNKNOWN)
        val fatal = request().executionEvidence(ClinicalInvalidationExecutionOutcome.FATAL)
        val mismatched = accepted.copy(dispatchToken = "different-token")

        suspend fun statusFor(evidence: ClinicalInvalidationExecutionEvidence) =
            WorkManagerClinicalInvalidationOwnershipStatusSource(
                queryWorkInfos = {
                    listOf(
                        ClinicalInvalidationWorkInfo(
                            state = WorkInfo.State.SUCCEEDED,
                            outputData = evidence.toWorkData()
                        )
                    )
                },
                queryEvidence = { null },
                timeoutMs = 100L
            ).status(request())

        assertThat(statusFor(accepted))
            .isEqualTo(ClinicalInvalidationOwnershipStatus.ACCEPTED_TERMINAL)
        assertThat(statusFor(unaccepted))
            .isEqualTo(ClinicalInvalidationOwnershipStatus.ABSENT_OR_TERMINAL)
        assertThat(statusFor(ambiguous))
            .isEqualTo(ClinicalInvalidationOwnershipStatus.AMBIGUOUS_TERMINAL)
        assertThat(statusFor(fatal))
            .isEqualTo(ClinicalInvalidationOwnershipStatus.FATAL_TERMINAL)
        assertThat(statusFor(mismatched))
            .isEqualTo(ClinicalInvalidationOwnershipStatus.UNKNOWN)
    }

    @Test
    fun retainedTerminalOutputRemainsAuthoritativeWhenDurableEvidenceTimesOut() = runTest {
        val expected = mapOf(
            ClinicalInvalidationExecutionOutcome.ACCEPTED to
                ClinicalInvalidationOwnershipStatus.ACCEPTED_TERMINAL,
            ClinicalInvalidationExecutionOutcome.UNACCEPTED to
                ClinicalInvalidationOwnershipStatus.ABSENT_OR_TERMINAL,
            ClinicalInvalidationExecutionOutcome.STARTED_UNKNOWN to
                ClinicalInvalidationOwnershipStatus.AMBIGUOUS_TERMINAL,
            ClinicalInvalidationExecutionOutcome.FATAL to
                ClinicalInvalidationOwnershipStatus.FATAL_TERMINAL
        )

        expected.forEach { (outcome, status) ->
            val source = retainedOutputSource(outcome) { awaitCancellation() }
            assertThat(source.status(request())).isEqualTo(status)
        }
    }

    @Test
    fun retainedTerminalOutputRemainsAuthoritativeWhenDurableEvidenceReadFails() = runTest {
        val expected = mapOf(
            ClinicalInvalidationExecutionOutcome.ACCEPTED to
                ClinicalInvalidationOwnershipStatus.ACCEPTED_TERMINAL,
            ClinicalInvalidationExecutionOutcome.UNACCEPTED to
                ClinicalInvalidationOwnershipStatus.ABSENT_OR_TERMINAL,
            ClinicalInvalidationExecutionOutcome.STARTED_UNKNOWN to
                ClinicalInvalidationOwnershipStatus.AMBIGUOUS_TERMINAL,
            ClinicalInvalidationExecutionOutcome.FATAL to
                ClinicalInvalidationOwnershipStatus.FATAL_TERMINAL
        )

        expected.forEach { (outcome, status) ->
            val source = retainedOutputSource(outcome) {
                throw IllegalStateException("durable_evidence_failed")
            }
            assertThat(source.status(request())).isEqualTo(status)
        }
    }

    @Test
    fun durableAndRetainedOutputConflictMalformedOrMismatchFailsClosed() = runTest {
        val accepted = request().executionEvidence(ClinicalInvalidationExecutionOutcome.ACCEPTED)
        val fatal = request().executionEvidence(ClinicalInvalidationExecutionOutcome.FATAL)
        val malformed = androidx.work.Data.Builder()
            .putLong("clinical_invalidation_evidence_generation", request().generation)
            .build()

        suspend fun status(
            output: androidx.work.Data,
            durable: ClinicalInvalidationExecutionEvidence
        ) = WorkManagerClinicalInvalidationOwnershipStatusSource(
            queryWorkInfos = {
                listOf(ClinicalInvalidationWorkInfo(WorkInfo.State.SUCCEEDED, output))
            },
            queryEvidence = { durable },
            timeoutMs = 100L
        ).status(request())

        assertThat(status(fatal.toWorkData(), accepted))
            .isEqualTo(ClinicalInvalidationOwnershipStatus.UNKNOWN)
        assertThat(status(malformed, accepted))
            .isEqualTo(ClinicalInvalidationOwnershipStatus.UNKNOWN)
        assertThat(status(accepted.toWorkData(), accepted.copy(dispatchToken = "stale-token")))
            .isEqualTo(ClinicalInvalidationOwnershipStatus.UNKNOWN)

        val multipleOutcomes = WorkManagerClinicalInvalidationOwnershipStatusSource(
            queryWorkInfos = {
                listOf(
                    ClinicalInvalidationWorkInfo(
                        WorkInfo.State.SUCCEEDED,
                        accepted.toWorkData()
                    ),
                    ClinicalInvalidationWorkInfo(
                        WorkInfo.State.FAILED,
                        fatal.toWorkData()
                    )
                )
            },
            queryEvidence = { null },
            timeoutMs = 100L
        ).status(request())
        assertThat(multipleOutcomes).isEqualTo(ClinicalInvalidationOwnershipStatus.UNKNOWN)
    }

    @Test
    fun activeWorkInfoSettlesOnlyExactAcceptedOrFatalEvidence() = runTest {
        suspend fun status(outcome: ClinicalInvalidationExecutionOutcome) =
            WorkManagerClinicalInvalidationOwnershipStatusSource(
                queryWorkInfos = {
                    listOf(
                        ClinicalInvalidationWorkInfo(
                            WorkInfo.State.RUNNING,
                            androidx.work.Data.EMPTY
                        )
                    )
                },
                queryEvidence = { request().executionEvidence(outcome) },
                timeoutMs = 100L
            ).status(request())

        assertThat(status(ClinicalInvalidationExecutionOutcome.ACCEPTED))
            .isEqualTo(ClinicalInvalidationOwnershipStatus.ACCEPTED_TERMINAL)
        assertThat(status(ClinicalInvalidationExecutionOutcome.FATAL))
            .isEqualTo(ClinicalInvalidationOwnershipStatus.FATAL_TERMINAL)
        assertThat(status(ClinicalInvalidationExecutionOutcome.STARTED_UNKNOWN))
            .isEqualTo(ClinicalInvalidationOwnershipStatus.ACTIVE)
        assertThat(status(ClinicalInvalidationExecutionOutcome.UNACCEPTED))
            .isEqualTo(ClinicalInvalidationOwnershipStatus.ACTIVE)
    }

    @Test
    fun durableAcceptedAndFatalEvidenceSurviveTerminalWorkWithoutOutput() = runTest {
        suspend fun statusFor(outcome: ClinicalInvalidationExecutionOutcome) =
            WorkManagerClinicalInvalidationOwnershipStatusSource(
                queryWorkInfos = {
                    listOf(
                        ClinicalInvalidationWorkInfo(
                            state = WorkInfo.State.CANCELLED,
                            outputData = androidx.work.Data.EMPTY
                        )
                    )
                },
                queryEvidence = { request().executionEvidence(outcome) },
                timeoutMs = 100L
            ).status(request())

        assertThat(statusFor(ClinicalInvalidationExecutionOutcome.ACCEPTED))
            .isEqualTo(ClinicalInvalidationOwnershipStatus.ACCEPTED_TERMINAL)
        assertThat(statusFor(ClinicalInvalidationExecutionOutcome.FATAL))
            .isEqualTo(ClinicalInvalidationOwnershipStatus.FATAL_TERMINAL)
        assertThat(statusFor(ClinicalInvalidationExecutionOutcome.STARTED_UNKNOWN))
            .isEqualTo(ClinicalInvalidationOwnershipStatus.AMBIGUOUS_TERMINAL)
    }

    @Test
    fun ownershipStatusTimeoutAndOrdinaryFailureAreUnknownButParentCancellationPropagates() = runTest {
        val timedOut = WorkManagerClinicalInvalidationOwnershipStatusSource(
            queryStates = { delay(1_000L); emptyList() },
            timeoutMs = 100L
        )
        val failed = WorkManagerClinicalInvalidationOwnershipStatusSource(
            queryStates = { error("query_failed") },
            timeoutMs = 100L
        )

        assertThat(timedOut.status(request())).isEqualTo(ClinicalInvalidationOwnershipStatus.UNKNOWN)
        assertThat(failed.status(request())).isEqualTo(ClinicalInvalidationOwnershipStatus.UNKNOWN)

        val cancelled = launch {
            WorkManagerClinicalInvalidationOwnershipStatusSource(
                queryStates = { awaitCancellation() },
                timeoutMs = 10_000L
            ).status(request())
        }
        runCurrent()
        cancelled.cancelAndJoin()
        assertThat(cancelled.isCancelled).isTrue()
    }

    @Test
    fun activeWorkInfoRetainsOwnershipWhenSharedEvidenceBudgetExpires() = runTest {
        val source = WorkManagerClinicalInvalidationOwnershipStatusSource(
            queryWorkInfos = {
                delay(60L)
                listOf(
                    ClinicalInvalidationWorkInfo(
                        state = WorkInfo.State.RUNNING,
                        outputData = androidx.work.Data.EMPTY
                    )
                )
            },
            queryEvidence = { awaitCancellation() },
            timeoutMs = 100L,
            nowMs = { testScheduler.currentTime }
        )

        assertThat(source.status(request()))
            .isEqualTo(ClinicalInvalidationOwnershipStatus.ACTIVE)
        assertThat(testScheduler.currentTime).isEqualTo(100L)
    }

    private fun kotlinx.coroutines.test.TestScope.retainedOutputSource(
        outcome: ClinicalInvalidationExecutionOutcome,
        queryEvidence: suspend () -> ClinicalInvalidationExecutionEvidence?
    ) = WorkManagerClinicalInvalidationOwnershipStatusSource(
        queryWorkInfos = {
            listOf(
                ClinicalInvalidationWorkInfo(
                    state = WorkInfo.State.SUCCEEDED,
                    outputData = request().executionEvidence(outcome).toWorkData()
                )
            )
        },
        queryEvidence = { queryEvidence() },
        timeoutMs = 100L,
        nowMs = { testScheduler.currentTime }
    )

    @Test
    fun workManagerTransportAcceptsOnlyAfterAsyncOperationSuccess() = runTest {
        val pending = FakeOperation()
        val transport = ClinicalInputInvalidationWorkManagerTransport(
            enqueueUniqueWork = { pending },
            cancelUniqueWork = { completedOperation() }
        )
        val accepted = launch { assertThat(transport.enqueue(request())).isTrue() }

        runCurrent()
        assertThat(accepted.isCompleted).isFalse()
        pending.succeed()
        accepted.join()
    }

    @Test
    fun workManagerTransportReturnsFalseForAsyncOperationFailure() = runTest {
        val pending = FakeOperation()
        val transport = ClinicalInputInvalidationWorkManagerTransport(
            enqueueUniqueWork = { pending },
            cancelUniqueWork = { completedOperation() }
        )
        val accepted = CompletableDeferred<Boolean>()
        launch { accepted.complete(transport.enqueue(request())) }

        runCurrent()
        pending.fail(IllegalStateException("enqueue_failed"))
        runCurrent()

        assertThat(accepted.await()).isFalse()
    }

    @Test
    fun workManagerTransportPropagatesCancellationFromActualCancelledJob() = runTest {
        val pending = FakeOperation()
        val transport = ClinicalInputInvalidationWorkManagerTransport(
            enqueueUniqueWork = { pending },
            cancelUniqueWork = { completedOperation() }
        )
        val job = launch { transport.enqueue(request()) }

        runCurrent()
        job.cancelAndJoin()

        assertThat(job.isCancelled).isTrue()
    }

    @Test
    fun workManagerTransportPropagatesAsyncOperationCancellation() = runTest {
        val pending = FakeOperation()
        val transport = ClinicalInputInvalidationWorkManagerTransport(
            enqueueUniqueWork = { pending },
            cancelUniqueWork = { completedOperation() }
        )
        val job = launch { transport.enqueue(request()) }

        runCurrent()
        pending.cancel()
        job.join()

        assertThat(job.isCancelled).isTrue()
    }

    @Test
    fun cancelledCoroutineStillTerminatesAcceptedDispatchInNonCancellableCleanup() = runTest {
        val cleanupCompleted = CompletableDeferred<Unit>()
        val job = launch {
            try {
                awaitCancellation()
            } finally {
                terminateReactiveDispatchNonCancellable(
                    generation = 7L,
                    dispatchToken = "accepted-token",
                    terminate = { generation, token ->
                        assertThat(generation).isEqualTo(7L)
                        assertThat(token).isEqualTo("accepted-token")
                        cleanupCompleted.complete(Unit)
                    }
                )
            }
        }

        runCurrent()
        job.cancelAndJoin()

        assertThat(cleanupCompleted.isCompleted).isTrue()
        assertThat(job.isCancelled).isTrue()
    }

    @Test
    fun reactiveWorkerRetriesAreBoundedBeforeCoordinatorTermination() {
        assertThat(resolveReactiveWorkerFailure(runAttemptCount = 0))
            .isEqualTo(ReactiveWorkerFailureAction.RETRY)
        assertThat(resolveReactiveWorkerFailure(runAttemptCount = 1))
            .isEqualTo(ReactiveWorkerFailureAction.RETRY)
        assertThat(resolveReactiveWorkerFailure(runAttemptCount = 2))
            .isEqualTo(ReactiveWorkerFailureAction.TERMINATE)
        assertThat(resolveReactiveWorkerFailure(runAttemptCount = 20))
            .isEqualTo(ReactiveWorkerFailureAction.TERMINATE)
    }

    @Test
    fun busyReactiveCycleReturnsUnacceptedAndStillRunsGateCleanup() = runTest {
        val cleanup = mutableListOf<String>()
        val policy = resolveReactiveWorkerPolicy(
            dispatchMode = ClinicalInputInvalidationMode.NORMAL,
            therapyActionsArmedAtStart = false,
            powerSaveActiveAtStart = true
        )

        val accepted = runReactiveWorkerCycle(
            policy = policy,
            runAutomationCycle = { false },
            cancelFutureRuntimeWork = { cleanup += "cancel_future" },
            schedulePowerSaveResume = { cleanup += "resume" },
            stopRuntimeControllers = { cleanup += "stop" }
        )

        assertThat(accepted).isFalse()
        assertThat(cleanup).containsExactly("cancel_future", "resume", "stop").inOrder()
    }

    @Test
    fun reactiveCycleCancellationPropagatesAfterGateCleanup() = runTest {
        val cleanup = mutableListOf<String>()
        val policy = resolveReactiveWorkerPolicy(
            dispatchMode = ClinicalInputInvalidationMode.LOCAL_READ_ONLY,
            therapyActionsArmedAtStart = false,
            powerSaveActiveAtStart = false
        )

        val result = runCatching {
            runReactiveWorkerCycle(
                policy = policy,
                runAutomationCycle = { throw CancellationException("cancelled") },
                cancelFutureRuntimeWork = { cleanup += "cancel_future" },
                schedulePowerSaveResume = { cleanup += "resume" },
                stopRuntimeControllers = { cleanup += "stop" }
            )
        }

        assertThat(result.exceptionOrNull()).isInstanceOf(CancellationException::class.java)
        assertThat(cleanup).containsExactly("cancel_future")
    }

    @Test
    fun localReadOnlyRepositoryPolicyHasNoWriterAlertActionOrWidgetSideEffects() {
        val policy = AutomationRepository.resolveCyclePolicyStatic(
            intent = AutomationRepository.AutomationCycleIntent.LOCAL_READ_ONLY,
            therapyActionsArmed = true,
            killSwitch = false,
            powerSaveActive = false
        )

        assertThat(policy.runRemoteRefresh).isFalse()
        assertThat(policy.therapyWritersAllowed).isFalse()
        assertThat(policy.allowActionRepositoryAccess).isFalse()
        assertThat(policy.allowAlertPublication).isFalse()
        assertThat(policy.publishWidgetAfterAcceptance).isFalse()
    }

    private fun request() = ClinicalInputInvalidationDispatch(
        generation = 1L,
        dispatchToken = "transport-token",
        mode = ClinicalInputInvalidationMode.NORMAL,
        sources = setOf(ClinicalInputInvalidationSource.BROADCAST_INGEST),
        attempt = 0
    )

    private class FakeOperation : Operation {
        private val state = MutableLiveData<Operation.State>(Operation.IN_PROGRESS)
        private val result = SettableFuture.create<Operation.State.SUCCESS>()

        override fun getState(): LiveData<Operation.State> = state

        override fun getResult(): ListenableFuture<Operation.State.SUCCESS> = result

        fun succeed() {
            result.set(Operation.SUCCESS)
        }

        fun fail(error: Throwable) {
            result.setException(error)
        }

        fun cancel() {
            result.cancel(false)
        }
    }

    private fun completedOperation(): Operation = FakeOperation().apply { succeed() }
}
