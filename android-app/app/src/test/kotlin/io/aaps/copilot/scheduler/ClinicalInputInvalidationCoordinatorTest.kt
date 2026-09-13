package io.aaps.copilot.scheduler

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ClinicalInputInvalidationCoordinatorTest {

    @Test
    fun burstUsesTrailingEdgeAndDispatchesLatestGenerationOnce() = runTest {
        val fixture = fixture()

        fixture.coordinator.invalidate(ClinicalInputInvalidationSource.PLANNED_ACTIVITY)
        runCurrent()
        advanceTimeBy(600L)
        fixture.coordinator.invalidate(ClinicalInputInvalidationSource.BROADCAST_INGEST)
        runCurrent()
        advanceTimeBy(600L)
        fixture.coordinator.invalidate(ClinicalInputInvalidationSource.LOCAL_NIGHTSCOUT)
        runCurrent()
        advanceTimeBy(999L)

        assertThat(fixture.requests).isEmpty()

        advanceTimeBy(1L)
        runCurrent()

        assertThat(fixture.requests).hasSize(1)
        assertThat(fixture.requests.single().generation).isEqualTo(3L)
        assertThat(fixture.requests.single().sources).containsExactly(
            ClinicalInputInvalidationSource.PLANNED_ACTIVITY,
            ClinicalInputInvalidationSource.BROADCAST_INGEST,
            ClinicalInputInvalidationSource.LOCAL_NIGHTSCOUT
        )
    }

    @Test
    fun continuousEventsCannotPostponeDispatchPastMaximumTrailingWait() = runTest {
        val fixture = fixture(maxTrailingWaitMs = 3_000L)

        fixture.coordinator.invalidate(ClinicalInputInvalidationSource.BROADCAST_INGEST)
        runCurrent()
        repeat(5) {
            advanceTimeBy(500L)
            fixture.coordinator.invalidate(ClinicalInputInvalidationSource.BROADCAST_INGEST)
            runCurrent()
            assertThat(fixture.requests).isEmpty()
        }

        advanceTimeBy(499L)
        runCurrent()
        assertThat(fixture.requests).isEmpty()

        advanceTimeBy(1L)
        runCurrent()
        assertThat(fixture.requests).hasSize(1)
        assertThat(fixture.requests.single().generation).isEqualTo(6L)
        assertThat(fixture.requests.single().sources)
            .containsExactly(ClinicalInputInvalidationSource.BROADCAST_INGEST)
    }

    @Test
    fun suspendedModeResolutionCannotBeRestartedPastMaximumEnqueueDeadline() = runTest {
        val requests = mutableListOf<ClinicalInputInvalidationDispatch>()
        var modeCalls = 0
        val coordinator = ClinicalInputInvalidationCoordinator(
            scope = this,
            trailingDelayMs = 2_000L,
            maxTrailingWaitMs = 1_000L,
            modeResolutionTimeoutMs = 200L,
            retryDelayMs = RETRY_DELAY_MS,
            nowMs = { testScheduler.currentTime },
            tokenSource = { "starvation-token-${requests.size + 1}" },
            modeSource = {
                modeCalls++
                if (modeCalls == 1) awaitCancellation()
                ClinicalInputInvalidationMode.NORMAL
            },
            sink = { request -> requests += request; true }
        )

        coordinator.invalidateAndAwait(ClinicalInputInvalidationSource.BROADCAST_INGEST)
        repeat(9) {
            advanceTimeBy(100L)
            coordinator.invalidateAndAwait(ClinicalInputInvalidationSource.BROADCAST_INGEST)
            runCurrent()
        }
        advanceTimeBy(100L)
        runCurrent()

        assertThat(requests).hasSize(1)
        assertThat(requests.single().mode).isEqualTo(ClinicalInputInvalidationMode.LOCAL_READ_ONLY)
        assertThat(requests.single().generation).isEqualTo(10L)

        repeat(4) {
            coordinator.invalidateAndAwait(ClinicalInputInvalidationSource.CONTEXT_EVENT)
        }
        runCurrent()
        assertThat(requests).hasSize(1)

        val active = requests.single()
        coordinator.acknowledge(active.generation, active.dispatchToken)
        advanceTimeBy(1_000L)
        runCurrent()

        assertThat(requests).hasSize(2)
        assertThat(requests.last().sources)
            .containsExactly(ClinicalInputInvalidationSource.CONTEXT_EVENT)
    }

    @Test
    fun cancelledTrailingTimerCannotClaimPreparationAfterNewTrailingEdgeWins() = runTest {
        val secondWriteStarted = CompletableDeferred<Unit>()
        val releaseSecondWrite = CompletableDeferred<Unit>()
        val ledger = BlockingSecondWriteLedger(secondWriteStarted, releaseSecondWrite)
        val requests = mutableListOf<ClinicalInputInvalidationDispatch>()
        val coordinator = ClinicalInputInvalidationCoordinator(
            scope = this,
            ledger = ledger,
            trailingDelayMs = 100L,
            maxTrailingWaitMs = 1_000L,
            modeResolutionTimeoutMs = 1L,
            nowMs = { testScheduler.currentTime },
            tokenSource = { "handoff-token-${requests.size + 1}" },
            modeSource = { ClinicalInputInvalidationMode.NORMAL },
            sink = { request -> requests += request; true }
        )

        coordinator.invalidateAndAwait(ClinicalInputInvalidationSource.LOCAL_ACTIVITY)
        advanceTimeBy(98L)
        val second = async {
            coordinator.invalidateAndAwait(ClinicalInputInvalidationSource.CONTEXT_EVENT)
        }
        runCurrent()
        secondWriteStarted.await()

        advanceTimeBy(1L)
        runCurrent()
        releaseSecondWrite.complete(Unit)
        runCurrent()
        second.await()
        advanceTimeBy(2L)
        runCurrent()

        assertThat(requests).isEmpty()

        advanceTimeBy(98L)
        runCurrent()
        assertThat(requests).hasSize(1)
        assertThat(requests.single().generation).isEqualTo(2L)
        assertThat(requests.single().sources).containsExactly(
            ClinicalInputInvalidationSource.LOCAL_ACTIVITY,
            ClinicalInputInvalidationSource.CONTEXT_EVENT
        )
    }

    @Test
    fun eventDuringActiveCycleProducesOneFollowUpWithLatestGeneration() = runTest {
        val fixture = fixture()
        fixture.coordinator.invalidate(ClinicalInputInvalidationSource.BROADCAST_INGEST)
        runCurrent()
        advanceTimeBy(DEBOUNCE_MS)
        runCurrent()
        val active = fixture.requests.single()

        fixture.coordinator.invalidate(ClinicalInputInvalidationSource.CONTEXT_EVENT)
        runCurrent()
        advanceTimeBy(DEBOUNCE_MS * 2)
        runCurrent()
        assertThat(fixture.requests).hasSize(1)

        fixture.coordinator.acknowledge(active.generation, active.dispatchToken)
        advanceTimeBy(DEBOUNCE_MS)
        runCurrent()

        assertThat(fixture.requests).hasSize(2)
        assertThat(fixture.requests.last().generation).isEqualTo(2L)
        assertThat(fixture.requests.last().sources)
            .containsExactly(ClinicalInputInvalidationSource.CONTEXT_EVENT)
    }

    @Test
    fun multipleEventsDuringActiveCycleStillProduceExactlyOneFollowUp() = runTest {
        val fixture = fixture()
        fixture.coordinator.invalidate(ClinicalInputInvalidationSource.LOCAL_ACTIVITY)
        runCurrent()
        advanceTimeBy(DEBOUNCE_MS)
        runCurrent()
        val active = fixture.requests.single()

        repeat(5) {
            fixture.coordinator.invalidate(ClinicalInputInvalidationSource.HEALTH_CONNECT_ACTIVITY)
            runCurrent()
        }
        fixture.coordinator.acknowledge(active.generation, active.dispatchToken)
        advanceTimeBy(DEBOUNCE_MS)
        runCurrent()

        assertThat(fixture.requests).hasSize(2)
        assertThat(fixture.requests.last().generation).isEqualTo(6L)
        assertThat(fixture.requests.last().sources)
            .containsExactly(ClinicalInputInvalidationSource.HEALTH_CONNECT_ACTIVITY)
    }

    @Test
    fun failureRetriesOnceWithoutAcknowledgingOrSpinning() = runTest {
        val fixture = fixture()
        fixture.coordinator.invalidate(ClinicalInputInvalidationSource.STARTUP_RECONCILIATION)
        runCurrent()
        advanceTimeBy(DEBOUNCE_MS)
        runCurrent()
        val first = fixture.requests.single()

        fixture.coordinator.dispatchTerminated(first.generation, first.dispatchToken)
        advanceTimeBy(RETRY_DELAY_MS)
        runCurrent()

        assertThat(fixture.requests).hasSize(2)
        assertThat(fixture.requests.last().generation).isEqualTo(first.generation)
        assertThat(fixture.requests.last().attempt).isEqualTo(1)

        fixture.coordinator.dispatchTerminated(
            fixture.requests.last().generation,
            fixture.requests.last().dispatchToken
        )
        advanceTimeBy(RETRY_DELAY_MS * 10)
        runCurrent()

        assertThat(fixture.requests).hasSize(2)
        val snapshot = fixture.coordinator.snapshot()
        assertThat(snapshot.latestGeneration).isEqualTo(1L)
        assertThat(snapshot.acknowledgedGeneration).isEqualTo(0L)
        assertThat(snapshot.inFlightGeneration).isNull()
        assertThat(snapshot.hasPendingInvalidation).isTrue()
    }

    @Test
    fun transientClaimWriteFailureRetriesSamePendingIdentityOnceBeforeEnqueue() = runTest {
        val ledger = ClaimWriteFailingLedger(failuresBeforeSuccess = 1)
        val requests = mutableListOf<ClinicalInputInvalidationDispatch>()
        val coordinator = ClinicalInputInvalidationCoordinator(
            scope = this,
            ledger = ledger,
            trailingDelayMs = DEBOUNCE_MS,
            retryDelayMs = RETRY_DELAY_MS,
            nowMs = { testScheduler.currentTime },
            tokenSource = { "claim-token-${ledger.claimWriteAttempts}" },
            modeSource = { ClinicalInputInvalidationMode.NORMAL },
            sink = { request -> requests += request; true }
        )

        coordinator.invalidateAndAwait(ClinicalInputInvalidationSource.LOCAL_ACTIVITY)
        advanceTimeBy(DEBOUNCE_MS)
        runCurrent()

        assertThat(requests).isEmpty()
        assertThat(ledger.claimWriteAttempts).isEqualTo(1)
        assertThat(ledger.state.pending?.generation).isEqualTo(1L)
        assertThat(ledger.state.pending?.sources)
            .containsExactly(ClinicalInputInvalidationSource.LOCAL_ACTIVITY)
        assertThat(ledger.state.pending?.attempt).isEqualTo(0)

        advanceTimeBy(RETRY_DELAY_MS)
        runCurrent()

        assertThat(ledger.claimWriteAttempts).isEqualTo(2)
        assertThat(requests).hasSize(1)
        assertThat(requests.single().generation).isEqualTo(1L)
        assertThat(requests.single().sources)
            .containsExactly(ClinicalInputInvalidationSource.LOCAL_ACTIVITY)
        assertThat(requests.single().attempt).isEqualTo(0)
    }

    @Test
    fun permanentClaimWriteFailureStopsAfterOneBoundedPreparationRetry() = runTest {
        val ledger = ClaimWriteFailingLedger(failuresBeforeSuccess = Int.MAX_VALUE)
        val requests = mutableListOf<ClinicalInputInvalidationDispatch>()
        val coordinator = ClinicalInputInvalidationCoordinator(
            scope = this,
            ledger = ledger,
            trailingDelayMs = DEBOUNCE_MS,
            retryDelayMs = RETRY_DELAY_MS,
            nowMs = { testScheduler.currentTime },
            modeSource = { ClinicalInputInvalidationMode.NORMAL },
            sink = { request -> requests += request; true }
        )

        coordinator.invalidateAndAwait(ClinicalInputInvalidationSource.HEALTH_CONNECT_ACTIVITY)
        advanceTimeBy(DEBOUNCE_MS + RETRY_DELAY_MS * 20)
        runCurrent()

        assertThat(ledger.claimWriteAttempts).isEqualTo(2)
        assertThat(requests).isEmpty()
        assertThat(ledger.state.pending?.generation).isEqualTo(1L)
        assertThat(ledger.state.pending?.sources)
            .containsExactly(ClinicalInputInvalidationSource.HEALTH_CONNECT_ACTIVITY)
        assertThat(ledger.state.pending?.attempt).isEqualTo(0)
        assertThat(coordinator.snapshot().hasPendingInvalidation).isTrue()
    }

    @Test
    fun newDurableInputAtMaximumGenerationGetsFreshBoundedClaimRetryBudget() = runTest {
        val ledger = ClaimWriteFailingLedger(
            failuresBeforeSuccess = 3,
            initialState = ClinicalInputInvalidationLedgerState(
                latestGeneration = Long.MAX_VALUE,
                acknowledgedGeneration = Long.MAX_VALUE
            )
        )
        val requests = mutableListOf<ClinicalInputInvalidationDispatch>()
        val coordinator = ClinicalInputInvalidationCoordinator(
            scope = this,
            ledger = ledger,
            trailingDelayMs = DEBOUNCE_MS,
            retryDelayMs = RETRY_DELAY_MS,
            nowMs = { testScheduler.currentTime },
            tokenSource = { "maximum-claim-token-${ledger.claimWriteAttempts}" },
            modeSource = { ClinicalInputInvalidationMode.NORMAL },
            sink = { request -> requests += request; true }
        )

        coordinator.invalidateAndAwait(ClinicalInputInvalidationSource.LOCAL_ACTIVITY)
        advanceTimeBy(DEBOUNCE_MS + RETRY_DELAY_MS)
        runCurrent()

        assertThat(ledger.claimWriteAttempts).isEqualTo(2)
        assertThat(requests).isEmpty()

        coordinator.invalidateAndAwait(ClinicalInputInvalidationSource.CONTEXT_EVENT)
        advanceTimeBy(DEBOUNCE_MS + RETRY_DELAY_MS)
        runCurrent()

        assertThat(ledger.claimWriteAttempts).isEqualTo(4)
        assertThat(requests).hasSize(1)
        assertThat(requests.single().generation).isEqualTo(Long.MAX_VALUE)
        assertThat(requests.single().attempt).isEqualTo(0)
        assertThat(requests.single().sources).containsExactly(
            ClinicalInputInvalidationSource.LOCAL_ACTIVITY,
            ClinicalInputInvalidationSource.CONTEXT_EVENT
        )

        advanceTimeBy(DEBOUNCE_MS + RETRY_DELAY_MS * 20)
        runCurrent()
        assertThat(ledger.claimWriteAttempts).isEqualTo(4)
        assertThat(requests).hasSize(1)
    }

    @Test
    fun sinkRejectionUsesTheSameBoundedFailureSemantics() = runTest {
        val requests = mutableListOf<ClinicalInputInvalidationDispatch>()
        val coordinator = ClinicalInputInvalidationCoordinator(
            scope = this,
            trailingDelayMs = DEBOUNCE_MS,
            retryDelayMs = RETRY_DELAY_MS,
            modeSource = { ClinicalInputInvalidationMode.NORMAL },
            sink = { request ->
                requests += request
                false
            }
        )

        coordinator.invalidate(ClinicalInputInvalidationSource.NIGHTSCOUT_RECONCILIATION)
        runCurrent()
        advanceTimeBy(DEBOUNCE_MS + RETRY_DELAY_MS)
        runCurrent()
        advanceTimeBy(RETRY_DELAY_MS * 10)
        runCurrent()

        assertThat(requests).hasSize(2)
        assertThat(requests.map { it.attempt }).containsExactly(0, 1).inOrder()
        assertThat(coordinator.snapshot().acknowledgedGeneration).isEqualTo(0L)
    }

    @Test
    fun acceptedCycleRetriesAckMetadataOnceWithoutRerunningClinicalCycle() = runTest {
        val ledger = AckWriteFailingLedger(failuresBeforeSuccess = 1)
        val requests = mutableListOf<ClinicalInputInvalidationDispatch>()
        val failures = mutableListOf<Throwable>()
        var cycles = 0
        val coordinator = ClinicalInputInvalidationCoordinator(
            scope = this,
            ledger = ledger,
            trailingDelayMs = DEBOUNCE_MS,
            retryDelayMs = RETRY_DELAY_MS,
            nowMs = { testScheduler.currentTime },
            failureReporter = { failures += it },
            modeSource = { ClinicalInputInvalidationMode.NORMAL },
            sink = { request -> requests += request; true }
        )
        coordinator.invalidateAndAwait(ClinicalInputInvalidationSource.LOCAL_ACTIVITY)
        advanceTimeBy(DEBOUNCE_MS)
        runCurrent()
        val active = requests.single()

        val result = runAcceptedReactiveCycle(active, coordinator) { cycles++ }

        assertThat(result).isEqualTo(ReactiveWorkerOrchestrationResult.SUCCESS)
        assertThat(cycles).isEqualTo(1)
        assertThat(ledger.ackWriteAttempts).isEqualTo(2)
        assertThat(failures).hasSize(1)
        assertThat(coordinator.snapshot().acknowledgedGeneration).isEqualTo(active.generation)
        assertThat(coordinator.snapshot().inFlightGeneration).isNull()
        advanceTimeBy(RETRY_DELAY_MS * 20)
        runCurrent()
        assertThat(requests).hasSize(1)
    }

    @Test
    fun permanentAckWriteFailureReturnsSuccessAndLaterInputMergesPendingFollowUp() = runTest {
        val ledger = AckWriteFailingLedger(failuresBeforeSuccess = Int.MAX_VALUE)
        val requests = mutableListOf<ClinicalInputInvalidationDispatch>()
        val failures = mutableListOf<Throwable>()
        var cycles = 0
        val coordinator = ClinicalInputInvalidationCoordinator(
            scope = this,
            ledger = ledger,
            trailingDelayMs = DEBOUNCE_MS,
            retryDelayMs = RETRY_DELAY_MS,
            nowMs = { testScheduler.currentTime },
            failureReporter = { failures += it },
            modeSource = { ClinicalInputInvalidationMode.NORMAL },
            sink = { request -> requests += request; true }
        )
        coordinator.invalidateAndAwait(ClinicalInputInvalidationSource.LOCAL_ACTIVITY)
        advanceTimeBy(DEBOUNCE_MS)
        runCurrent()
        val active = requests.single()

        val result = runAcceptedReactiveCycle(active, coordinator, runAttemptCount = 2) {
            cycles++
            coordinator.invalidateAndAwait(ClinicalInputInvalidationSource.CONTEXT_EVENT)
        }

        assertThat(result).isEqualTo(ReactiveWorkerOrchestrationResult.SUCCESS)
        assertThat(cycles).isEqualTo(1)
        assertThat(ledger.ackWriteAttempts).isEqualTo(2)
        assertThat(failures).hasSize(2)
        assertThat(coordinator.snapshot().acknowledgedGeneration).isEqualTo(active.generation)
        assertThat(coordinator.snapshot().inFlightGeneration).isNull()
        assertThat(coordinator.snapshot().hasPendingInvalidation).isTrue()
        advanceTimeBy(RETRY_DELAY_MS * 20)
        runCurrent()
        assertThat(requests).hasSize(1)

        coordinator.invalidateAndAwait(ClinicalInputInvalidationSource.HEALTH_CONNECT_ACTIVITY)
        advanceTimeBy(DEBOUNCE_MS)
        runCurrent()

        assertThat(requests).hasSize(2)
        assertThat(requests.last().generation).isEqualTo(3L)
        assertThat(requests.last().attempt).isEqualTo(0)
        assertThat(requests.last().sources).containsExactly(
            ClinicalInputInvalidationSource.CONTEXT_EVENT,
            ClinicalInputInvalidationSource.HEALTH_CONNECT_ACTIVITY
        )
    }

    @Test
    fun cancellationWhileAckWriteBlocksClearsInMemoryOwnershipWithoutRedispatch() = runTest {
        val ackWriteStarted = CompletableDeferred<Unit>()
        val ledger = AckWriteFailingLedger(blockWriteStarted = ackWriteStarted)
        val requests = mutableListOf<ClinicalInputInvalidationDispatch>()
        var cycles = 0
        val coordinator = ClinicalInputInvalidationCoordinator(
            scope = this,
            ledger = ledger,
            trailingDelayMs = DEBOUNCE_MS,
            retryDelayMs = RETRY_DELAY_MS,
            nowMs = { testScheduler.currentTime },
            modeSource = { ClinicalInputInvalidationMode.NORMAL },
            sink = { request -> requests += request; true }
        )
        coordinator.invalidateAndAwait(ClinicalInputInvalidationSource.BROADCAST_INGEST)
        advanceTimeBy(DEBOUNCE_MS)
        runCurrent()
        val active = requests.single()
        val job = launch {
            runAcceptedReactiveCycle(active, coordinator) { cycles++ }
        }

        runCurrent()
        assertThat(ackWriteStarted.isCompleted).isTrue()
        job.cancelAndJoin()

        assertThat(job.isCancelled).isTrue()
        assertThat(cycles).isEqualTo(1)
        assertThat(ledger.ackWriteAttempts).isEqualTo(1)
        assertThat(coordinator.snapshot().acknowledgedGeneration).isEqualTo(active.generation)
        assertThat(coordinator.snapshot().inFlightGeneration).isNull()
        advanceTimeBy(RETRY_DELAY_MS * 20)
        runCurrent()
        assertThat(requests).hasSize(1)
    }

    @Test
    fun ackPersistedBeforeCancellationIsNotDowngradedToTerminationPending() = runTest {
        val cancellation = AckSettlementCancellation(Any())
        val ledger = AckWriteFailingLedger(
            cancellation = cancellation,
            persistBeforeCancellation = true
        )
        val requests = mutableListOf<ClinicalInputInvalidationDispatch>()
        var cycles = 0
        val coordinator = ClinicalInputInvalidationCoordinator(
            scope = this,
            ledger = ledger,
            trailingDelayMs = DEBOUNCE_MS,
            retryDelayMs = RETRY_DELAY_MS,
            nowMs = { testScheduler.currentTime },
            modeSource = { ClinicalInputInvalidationMode.NORMAL },
            sink = { request -> requests += request; true }
        )
        coordinator.invalidateAndAwait(ClinicalInputInvalidationSource.LOCAL_ACTIVITY)
        advanceTimeBy(DEBOUNCE_MS)
        runCurrent()
        val active = requests.single()

        val result = runCatching {
            runAcceptedReactiveCycle(active, coordinator) { cycles++ }
        }

        assertThat(result.exceptionOrNull()).isSameInstanceAs(cancellation)
        assertThat(cycles).isEqualTo(1)
        assertThat(ledger.state.acknowledgedGeneration).isEqualTo(active.generation)
        assertThat(ledger.state.inFlight).isNull()
        assertThat(coordinator.snapshot().acknowledgedGeneration).isEqualTo(active.generation)
        assertThat(coordinator.snapshot().inFlightGeneration).isNull()
        assertThat(coordinator.snapshot().hasPendingInvalidation).isFalse()
        advanceTimeBy(RETRY_DELAY_MS * 20)
        runCurrent()
        assertThat(requests).hasSize(1)
    }

    @Test
    fun acceptedCycleAckTimeoutUsesExactAckCleanupWithoutWorkerRetryOrSecondCycle() = runTest {
        val ackWriteStarted = CompletableDeferred<Unit>()
        val ledger = AckWriteFailingLedger(blockWriteStarted = ackWriteStarted)
        val requests = mutableListOf<ClinicalInputInvalidationDispatch>()
        var cycles = 0
        var terminations = 0
        var exactTimeout: TimeoutCancellationException? = null
        val coordinator = ClinicalInputInvalidationCoordinator(
            scope = this,
            ledger = ledger,
            trailingDelayMs = DEBOUNCE_MS,
            retryDelayMs = RETRY_DELAY_MS,
            nowMs = { testScheduler.currentTime },
            modeSource = { ClinicalInputInvalidationMode.NORMAL },
            sink = { request -> requests += request; true }
        )
        coordinator.invalidateAndAwait(ClinicalInputInvalidationSource.LOCAL_ACTIVITY)
        advanceTimeBy(DEBOUNCE_MS)
        runCurrent()
        val active = requests.single()

        val result = runCatching {
            runReactiveWorkerOrchestration(
                reactiveWork = ClinicalInvalidationWorkData(
                    active.generation,
                    active.dispatchToken,
                    active.mode,
                    active.attempt
                ),
                runAttemptCount = 0,
                timeoutMs = 10_000L,
                prepare = {
                    PreparedReactiveWorkerCycle {
                        cycles++
                        true
                    }
                },
                onStarted = {},
                onCompleted = {},
                onFailure = {},
                acknowledge = { generation, token ->
                    try {
                        withTimeout(100L) {
                            coordinator.acknowledge(generation, token)
                        }
                    } catch (timeout: TimeoutCancellationException) {
                        exactTimeout = timeout
                        throw timeout
                    }
                },
                terminate = { generation, token ->
                    terminations++
                    coordinator.dispatchTerminated(generation, token)
                },
                terminateFatal = coordinator::dispatchFatal
            )
        }

        assertThat(ackWriteStarted.isCompleted).isTrue()
        assertThat(result.exceptionOrNull()).isSameInstanceAs(exactTimeout)
        assertThat(cycles).isEqualTo(1)
        assertThat(terminations).isEqualTo(0)
        assertThat(coordinator.snapshot().acknowledgedGeneration).isEqualTo(active.generation)
        assertThat(coordinator.snapshot().inFlightGeneration).isNull()
        advanceTimeBy(RETRY_DELAY_MS * 20)
        runCurrent()
        assertThat(requests).hasSize(1)
    }

    @Test
    fun transientTerminationWriteFailureRetriesMetadataOnceWithoutDuplicateImmediateDispatch() = runTest {
        val ledger = SettlementWriteFailingLedger(failuresBeforeSuccess = 1)
        val requests = mutableListOf<ClinicalInputInvalidationDispatch>()
        val failures = mutableListOf<Throwable>()
        val coordinator = ClinicalInputInvalidationCoordinator(
            scope = this,
            ledger = ledger,
            trailingDelayMs = DEBOUNCE_MS,
            retryDelayMs = RETRY_DELAY_MS,
            nowMs = { testScheduler.currentTime },
            failureReporter = { failures += it },
            modeSource = { ClinicalInputInvalidationMode.NORMAL },
            sink = { request -> requests += request; false }
        )

        coordinator.invalidateAndAwait(ClinicalInputInvalidationSource.LOCAL_ACTIVITY)
        advanceTimeBy(DEBOUNCE_MS)
        runCurrent()

        assertThat(ledger.settlementWriteAttempts).isEqualTo(2)
        assertThat(failures).hasSize(1)
        assertThat(requests).hasSize(1)
        assertThat(ledger.state.pending?.generation).isEqualTo(1L)
        assertThat(ledger.state.pending?.sources)
            .containsExactly(ClinicalInputInvalidationSource.LOCAL_ACTIVITY)
        assertThat(ledger.state.pending?.attempt).isEqualTo(1)

        advanceTimeBy(RETRY_DELAY_MS)
        runCurrent()
        assertThat(requests).hasSize(2)
        assertThat(requests.last().generation).isEqualTo(1L)
        assertThat(requests.last().attempt).isEqualTo(1)
    }

    @Test
    fun permanentTerminationWriteFailureStopsBoundedAndNewDurableInputCanProgress() = runTest {
        val ledger = SettlementWriteFailingLedger(failuresBeforeSuccess = Int.MAX_VALUE)
        val requests = mutableListOf<ClinicalInputInvalidationDispatch>()
        val failures = mutableListOf<Throwable>()
        val coordinator = ClinicalInputInvalidationCoordinator(
            scope = this,
            ledger = ledger,
            trailingDelayMs = DEBOUNCE_MS,
            retryDelayMs = RETRY_DELAY_MS,
            nowMs = { testScheduler.currentTime },
            failureReporter = { failures += it },
            modeSource = { ClinicalInputInvalidationMode.NORMAL },
            sink = { request ->
                requests += request
                requests.size > 1
            }
        )

        coordinator.invalidateAndAwait(ClinicalInputInvalidationSource.LOCAL_ACTIVITY)
        advanceTimeBy(DEBOUNCE_MS + RETRY_DELAY_MS * 20)
        runCurrent()

        assertThat(ledger.settlementWriteAttempts).isEqualTo(2)
        assertThat(failures).hasSize(2)
        assertThat(requests).hasSize(1)
        assertThat(coordinator.snapshot().inFlightGeneration).isNull()
        assertThat(coordinator.snapshot().hasPendingInvalidation).isTrue()

        coordinator.invalidateAndAwait(ClinicalInputInvalidationSource.CONTEXT_EVENT)
        advanceTimeBy(DEBOUNCE_MS)
        runCurrent()

        assertThat(requests).hasSize(2)
        assertThat(requests.last().generation).isEqualTo(2L)
        assertThat(requests.last().attempt).isEqualTo(0)
        assertThat(requests.last().sources).containsExactly(
            ClinicalInputInvalidationSource.LOCAL_ACTIVITY,
            ClinicalInputInvalidationSource.CONTEXT_EVENT
        )
    }

    @Test
    fun cancelledWorkerCleanupRetriesTerminationMetadataOnceAndStillPropagatesCancellation() = runTest {
        val ledger = SettlementWriteFailingLedger(failuresBeforeSuccess = 1)
        val requests = mutableListOf<ClinicalInputInvalidationDispatch>()
        val coordinator = ClinicalInputInvalidationCoordinator(
            scope = this,
            ledger = ledger,
            trailingDelayMs = DEBOUNCE_MS,
            retryDelayMs = RETRY_DELAY_MS,
            nowMs = { testScheduler.currentTime },
            modeSource = { ClinicalInputInvalidationMode.NORMAL },
            sink = { request -> requests += request; true }
        )
        coordinator.invalidateAndAwait(ClinicalInputInvalidationSource.BROADCAST_INGEST)
        advanceTimeBy(DEBOUNCE_MS)
        runCurrent()
        val active = requests.single()
        val setupStarted = CompletableDeferred<Unit>()
        val job = launch {
            runReactiveWorkerOrchestration(
                reactiveWork = ClinicalInvalidationWorkData(
                    active.generation,
                    active.dispatchToken,
                    active.mode,
                    active.attempt
                ),
                runAttemptCount = 0,
                timeoutMs = 10_000L,
                prepare = {
                    setupStarted.complete(Unit)
                    awaitCancellation()
                },
                onStarted = {},
                onCompleted = {},
                onFailure = {},
                acknowledge = coordinator::acknowledge,
                terminate = coordinator::dispatchTerminated,
                terminateFatal = coordinator::dispatchFatal
            )
        }

        runCurrent()
        job.cancelAndJoin()

        assertThat(job.isCancelled).isTrue()
        assertThat(ledger.settlementWriteAttempts).isEqualTo(2)
        assertThat(coordinator.snapshot().inFlightGeneration).isNull()
        assertThat(coordinator.snapshot().hasPendingInvalidation).isTrue()
    }

    @Test
    fun fatalTerminationClearsExactOwnershipWithoutSchedulingClinicalRedispatch() = runTest {
        val ledger = FakeClinicalInputInvalidationLedger()
        val requests = mutableListOf<ClinicalInputInvalidationDispatch>()
        val coordinator = ClinicalInputInvalidationCoordinator(
            scope = this,
            ledger = ledger,
            trailingDelayMs = DEBOUNCE_MS,
            retryDelayMs = RETRY_DELAY_MS,
            nowMs = { testScheduler.currentTime },
            modeSource = { ClinicalInputInvalidationMode.NORMAL },
            sink = { request -> requests += request; true }
        )
        coordinator.invalidateAndAwait(ClinicalInputInvalidationSource.HEALTH_CONNECT_ACTIVITY)
        advanceTimeBy(DEBOUNCE_MS)
        runCurrent()
        val active = requests.single()

        assertThat(coordinator.dispatchFatal(active.generation, active.dispatchToken)).isTrue()
        advanceTimeBy(RETRY_DELAY_MS * 20)
        runCurrent()

        assertThat(requests).hasSize(1)
        assertThat(ledger.state.inFlight).isNull()
        assertThat(ledger.state.pending?.attempt)
            .isEqualTo(MAX_CLINICAL_INVALIDATION_PERSISTED_ATTEMPT)
    }

    @Test
    fun fatalSettlementWriteErrorPropagatesWithoutMetadataRetry() = runTest {
        val fatal = FatalSettlementError(Any())
        val ledger = SettlementWriteFailingLedger(fatal = fatal)
        val requests = mutableListOf<ClinicalInputInvalidationDispatch>()
        val coordinator = ClinicalInputInvalidationCoordinator(
            scope = this,
            ledger = ledger,
            trailingDelayMs = DEBOUNCE_MS,
            nowMs = { testScheduler.currentTime },
            modeSource = { ClinicalInputInvalidationMode.NORMAL },
            sink = { request -> requests += request; true }
        )
        coordinator.invalidateAndAwait(ClinicalInputInvalidationSource.CONTEXT_EVENT)
        advanceTimeBy(DEBOUNCE_MS)
        runCurrent()
        val active = requests.single()

        assertThat(coordinator.dispatchFatal(active.generation, "stale-token")).isFalse()
        assertThat(coordinator.snapshot().inFlightDispatchToken).isEqualTo(active.dispatchToken)

        val result = runCatching {
            coordinator.dispatchFatal(active.generation, active.dispatchToken)
        }

        assertThat(result.exceptionOrNull()).isSameInstanceAs(fatal)
        assertThat(ledger.settlementWriteAttempts).isEqualTo(1)
        assertThat(coordinator.snapshot().inFlightGeneration).isNull()
        assertThat(coordinator.snapshot().hasPendingInvalidation).isTrue()
        advanceTimeBy(RETRY_DELAY_MS * 20)
        runCurrent()
        assertThat(requests).hasSize(1)
    }

    @Test
    fun terminationWriteCancellationClearsExactOwnerUnscheduledAndLaterInputProgresses() = runTest {
        val cancellation = ExactSettlementCancellation(Any())
        val ledger = SettlementWriteFailingLedger(cancellation = cancellation)
        val requests = mutableListOf<ClinicalInputInvalidationDispatch>()
        val coordinator = ClinicalInputInvalidationCoordinator(
            scope = this,
            ledger = ledger,
            trailingDelayMs = DEBOUNCE_MS,
            retryDelayMs = RETRY_DELAY_MS,
            nowMs = { testScheduler.currentTime },
            modeSource = { ClinicalInputInvalidationMode.NORMAL },
            sink = { request -> requests += request; true }
        )
        coordinator.invalidateAndAwait(ClinicalInputInvalidationSource.LOCAL_ACTIVITY)
        advanceTimeBy(DEBOUNCE_MS)
        runCurrent()
        val active = requests.single()

        val result = runCatching {
            coordinator.dispatchTerminated(active.generation, active.dispatchToken)
        }

        assertThat(result.exceptionOrNull()).isSameInstanceAs(cancellation)
        assertThat(ledger.settlementWriteAttempts).isEqualTo(1)
        assertThat(coordinator.snapshot().inFlightGeneration).isNull()
        assertThat(coordinator.snapshot().hasPendingInvalidation).isTrue()
        advanceTimeBy(RETRY_DELAY_MS * 20)
        runCurrent()
        assertThat(requests).hasSize(1)

        coordinator.invalidateAndAwait(ClinicalInputInvalidationSource.CONTEXT_EVENT)
        advanceTimeBy(DEBOUNCE_MS)
        runCurrent()

        assertThat(requests).hasSize(2)
        assertThat(requests.last().generation).isEqualTo(2L)
        assertThat(requests.last().attempt).isEqualTo(0)
        assertThat(requests.last().sources).containsExactly(
            ClinicalInputInvalidationSource.LOCAL_ACTIVITY,
            ClinicalInputInvalidationSource.CONTEXT_EVENT
        )
    }

    @Test
    fun boundedTerminationCleanupTimeoutCannotStrandCurrentProcessOwnership() = runTest {
        val writeStarted = CompletableDeferred<Unit>()
        val ledger = SettlementWriteFailingLedger(blockWriteStarted = writeStarted)
        val requests = mutableListOf<ClinicalInputInvalidationDispatch>()
        val coordinator = ClinicalInputInvalidationCoordinator(
            scope = this,
            ledger = ledger,
            trailingDelayMs = DEBOUNCE_MS,
            retryDelayMs = RETRY_DELAY_MS,
            nowMs = { testScheduler.currentTime },
            modeSource = { ClinicalInputInvalidationMode.NORMAL },
            sink = { request -> requests += request; true }
        )
        coordinator.invalidateAndAwait(ClinicalInputInvalidationSource.BROADCAST_INGEST)
        advanceTimeBy(DEBOUNCE_MS)
        runCurrent()
        val active = requests.single()

        val result = runCatching {
            terminateReactiveDispatchNonCancellable(
                generation = active.generation,
                dispatchToken = active.dispatchToken,
                timeoutMs = 100L,
                terminate = { generation, token -> coordinator.dispatchTerminated(generation, token) }
            )
        }

        assertThat(writeStarted.isCompleted).isTrue()
        assertThat(result.exceptionOrNull()).isInstanceOf(TimeoutCancellationException::class.java)
        assertThat(ledger.settlementWriteAttempts).isEqualTo(1)
        assertThat(coordinator.snapshot().inFlightGeneration).isNull()
        assertThat(coordinator.snapshot().hasPendingInvalidation).isTrue()
        advanceTimeBy(RETRY_DELAY_MS * 20)
        runCurrent()
        assertThat(requests).hasSize(1)
    }

    @Test
    fun nullSkippedCycleDoesNotAckAndRetriesLatestGenerationOnce() = runTest {
        val fixture = fixture()
        fixture.coordinator.invalidate(ClinicalInputInvalidationSource.BROADCAST_INGEST)
        runCurrent()
        advanceTimeBy(DEBOUNCE_MS)
        runCurrent()
        val skipped = fixture.requests.single()

        fixture.coordinator.invalidate(ClinicalInputInvalidationSource.CONTEXT_EVENT)
        runCurrent()
        settleClinicalInvalidationCycle(
            generation = skipped.generation,
            dispatchToken = skipped.dispatchToken,
            cycleAccepted = false,
            acknowledge = fixture.coordinator::acknowledge,
            terminate = fixture.coordinator::dispatchTerminated
        )

        assertThat(fixture.coordinator.snapshot().acknowledgedGeneration).isEqualTo(0L)
        advanceTimeBy(DEBOUNCE_MS)
        runCurrent()

        assertThat(fixture.requests).hasSize(2)
        val followUp = fixture.requests.last()
        assertThat(followUp.generation).isEqualTo(2L)
        assertThat(followUp.attempt).isEqualTo(0)

        settleClinicalInvalidationCycle(
            generation = followUp.generation,
            dispatchToken = followUp.dispatchToken,
            cycleAccepted = true,
            acknowledge = fixture.coordinator::acknowledge,
            terminate = fixture.coordinator::dispatchTerminated
        )

        assertThat(fixture.coordinator.snapshot().acknowledgedGeneration).isEqualTo(2L)
        advanceTimeBy(DEBOUNCE_MS * 2)
        runCurrent()
        assertThat(fixture.requests).hasSize(2)
    }

    @Test
    fun modeIsSelectedForEachDispatchFromCurrentTherapyAndPowerState() {
        assertThat(resolveClinicalInputInvalidationMode(therapyActionsArmed = true, powerSaveActive = false))
            .isEqualTo(ClinicalInputInvalidationMode.NORMAL)
        assertThat(resolveClinicalInputInvalidationMode(therapyActionsArmed = false, powerSaveActive = false))
            .isEqualTo(ClinicalInputInvalidationMode.LOCAL_READ_ONLY)
        assertThat(resolveClinicalInputInvalidationMode(therapyActionsArmed = true, powerSaveActive = true))
            .isEqualTo(ClinicalInputInvalidationMode.LOCAL_READ_ONLY)
        assertThat(resolveClinicalInputInvalidationMode(therapyActionsArmed = false, powerSaveActive = true))
            .isEqualTo(ClinicalInputInvalidationMode.LOCAL_READ_ONLY)
    }

    @Test
    fun cancellationFromModeSourceAndSinkIsRethrownWhileOrdinaryFailuresFailClosed() = runTest {
        val request = ClinicalInputInvalidationDispatch(
            generation = 1L,
            dispatchToken = "failure-semantics-token",
            mode = ClinicalInputInvalidationMode.NORMAL,
            sources = setOf(ClinicalInputInvalidationSource.CONTEXT_EVENT),
            attempt = 0
        )

        val modeCancellation = runCatching {
            resolveClinicalInputInvalidationModeFailClosed { throw CancellationException("mode_cancelled") }
        }
        val sinkCancellation = runCatching {
            dispatchClinicalInputInvalidationFailClosed(request) {
                throw CancellationException("sink_cancelled")
            }
        }
        val fatalModeFailure = runCatching {
            resolveClinicalInputInvalidationModeFailClosed { throw AssertionError("fatal_mode") }
        }
        val fatalSinkFailure = runCatching {
            dispatchClinicalInputInvalidationFailClosed(request) { throw AssertionError("fatal_sink") }
        }

        assertThat(modeCancellation.exceptionOrNull()).isInstanceOf(CancellationException::class.java)
        assertThat(sinkCancellation.exceptionOrNull()).isInstanceOf(CancellationException::class.java)
        assertThat(fatalModeFailure.exceptionOrNull()).isInstanceOf(AssertionError::class.java)
        assertThat(fatalSinkFailure.exceptionOrNull()).isInstanceOf(AssertionError::class.java)
        assertThat(resolveClinicalInputInvalidationModeFailClosed { error("mode_failed") })
            .isEqualTo(ClinicalInputInvalidationMode.LOCAL_READ_ONLY)
        assertThat(dispatchClinicalInputInvalidationFailClosed(request) { error("sink_failed") })
            .isFalse()
    }

    @Test
    fun actualParentCancellationIsNotConvertedToModeTimeoutFallback() = runTest {
        val started = CompletableDeferred<Unit>()
        val job = launch {
            resolveClinicalInputInvalidationModeFailClosed(timeoutMs = 10_000L) {
                started.complete(Unit)
                awaitCancellation()
            }
        }

        runCurrent()
        assertThat(started.isCompleted).isTrue()
        job.cancelAndJoin()

        assertThat(job.isCancelled).isTrue()
    }

    @Test
    fun eventDuringActiveCycleSurvivesProcessRecreationAndStaleTokenCannotSettleNewDispatch() = runTest {
        val ledger = FakeClinicalInputInvalidationLedger()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val firstScope = CoroutineScope(SupervisorJob() + dispatcher)
        val firstRequests = mutableListOf<ClinicalInputInvalidationDispatch>()
        val first = ClinicalInputInvalidationCoordinator(
            scope = firstScope,
            ledger = ledger,
            trailingDelayMs = DEBOUNCE_MS,
            retryDelayMs = RETRY_DELAY_MS,
            nowMs = { testScheduler.currentTime },
            tokenSource = { "old-process-token" },
            modeSource = { ClinicalInputInvalidationMode.NORMAL },
            sink = { request -> firstRequests += request; true }
        )

        first.invalidateAndAwait(ClinicalInputInvalidationSource.BROADCAST_INGEST)
        advanceTimeBy(DEBOUNCE_MS)
        runCurrent()
        val stale = firstRequests.single()
        first.invalidateAndAwait(ClinicalInputInvalidationSource.CONTEXT_EVENT)
        firstScope.cancel()

        val secondScope = CoroutineScope(SupervisorJob() + dispatcher)
        val secondRequests = mutableListOf<ClinicalInputInvalidationDispatch>()
        val second = ClinicalInputInvalidationCoordinator(
            scope = secondScope,
            ledger = ledger,
            trailingDelayMs = DEBOUNCE_MS,
            retryDelayMs = RETRY_DELAY_MS,
            nowMs = { testScheduler.currentTime },
            tokenSource = { "new-process-token" },
            modeSource = { ClinicalInputInvalidationMode.NORMAL },
            sink = { request -> secondRequests += request; true }
        )

        second.hydrateAndRedrive()
        advanceTimeBy(RETRY_DELAY_MS)
        runCurrent()

        val current = secondRequests.single()
        assertThat(current.generation).isEqualTo(2L)
        assertThat(current.dispatchToken).isEqualTo("new-process-token")
        assertThat(current.sources).containsExactly(
            ClinicalInputInvalidationSource.BROADCAST_INGEST,
            ClinicalInputInvalidationSource.CONTEXT_EVENT
        )
        assertThat(second.acknowledge(stale.generation, stale.dispatchToken)).isFalse()
        assertThat(second.acknowledge(current.generation, stale.dispatchToken)).isFalse()
        assertThat(second.snapshot().inFlightDispatchToken).isEqualTo("new-process-token")
        assertThat(second.acknowledge(current.generation, current.dispatchToken)).isTrue()
        assertThat(second.snapshot().acknowledgedGeneration).isEqualTo(2L)
        secondScope.cancel()
    }

    @Test
    fun enqueueFailureRemainsDurableAndIsRedrivenAfterRecreation() = runTest {
        val ledger = FakeClinicalInputInvalidationLedger()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val firstScope = CoroutineScope(SupervisorJob() + dispatcher)
        val first = ClinicalInputInvalidationCoordinator(
            scope = firstScope,
            ledger = ledger,
            trailingDelayMs = DEBOUNCE_MS,
            retryDelayMs = RETRY_DELAY_MS,
            nowMs = { testScheduler.currentTime },
            tokenSource = { "failed-enqueue-token" },
            modeSource = { ClinicalInputInvalidationMode.NORMAL },
            sink = { false }
        )

        first.invalidateAndAwait(ClinicalInputInvalidationSource.LOCAL_ACTIVITY)
        advanceTimeBy(DEBOUNCE_MS)
        runCurrent()
        assertThat(ledger.state.pending?.sources)
            .containsExactly(ClinicalInputInvalidationSource.LOCAL_ACTIVITY)
        assertThat(ledger.state.inFlight).isNull()
        firstScope.cancel()

        val secondScope = CoroutineScope(SupervisorJob() + dispatcher)
        val recovered = mutableListOf<ClinicalInputInvalidationDispatch>()
        val second = ClinicalInputInvalidationCoordinator(
            scope = secondScope,
            ledger = ledger,
            trailingDelayMs = DEBOUNCE_MS,
            retryDelayMs = RETRY_DELAY_MS,
            nowMs = { testScheduler.currentTime },
            tokenSource = { "recovered-token" },
            modeSource = { ClinicalInputInvalidationMode.NORMAL },
            sink = { request -> recovered += request; true }
        )

        second.hydrateAndRedrive()
        advanceTimeBy(RETRY_DELAY_MS)
        runCurrent()

        assertThat(recovered.single().dispatchToken).isEqualTo("recovered-token")
        assertThat(recovered.single().generation).isEqualTo(1L)
        secondScope.cancel()
    }

    @Test
    fun actualDispatchJobCancellationRestoresDurablePendingState() = runTest {
        val ledger = FakeClinicalInputInvalidationLedger()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val coordinatorScope = CoroutineScope(SupervisorJob() + dispatcher)
        val sinkStarted = CompletableDeferred<Unit>()
        val coordinator = ClinicalInputInvalidationCoordinator(
            scope = coordinatorScope,
            ledger = ledger,
            trailingDelayMs = DEBOUNCE_MS,
            retryDelayMs = RETRY_DELAY_MS,
            nowMs = { testScheduler.currentTime },
            tokenSource = { "cancelled-dispatch-token" },
            modeSource = { ClinicalInputInvalidationMode.NORMAL },
            sink = {
                sinkStarted.complete(Unit)
                awaitCancellation()
            }
        )

        coordinator.invalidateAndAwait(ClinicalInputInvalidationSource.CONTEXT_EVENT)
        advanceTimeBy(DEBOUNCE_MS)
        runCurrent()
        assertThat(sinkStarted.isCompleted).isTrue()

        coordinatorScope.cancel()
        runCurrent()

        assertThat(ledger.state.inFlight).isNull()
        assertThat(ledger.state.pending?.sources)
            .containsExactly(ClinicalInputInvalidationSource.CONTEXT_EVENT)
    }

    private fun TestScope.fixture(maxTrailingWaitMs: Long = 10_000L): Fixture {
        val requests = mutableListOf<ClinicalInputInvalidationDispatch>()
        val coordinator = ClinicalInputInvalidationCoordinator(
            scope = this,
            trailingDelayMs = DEBOUNCE_MS,
            maxTrailingWaitMs = maxTrailingWaitMs,
            retryDelayMs = RETRY_DELAY_MS,
            nowMs = { testScheduler.currentTime },
            tokenSource = { "fixture-token-${requests.size + 1}" },
            modeSource = { ClinicalInputInvalidationMode.NORMAL },
            sink = { request ->
                requests += request
                true
            }
        )
        return Fixture(coordinator, requests)
    }

    private data class Fixture(
        val coordinator: ClinicalInputInvalidationCoordinator,
        val requests: MutableList<ClinicalInputInvalidationDispatch>
    )

    private suspend fun runAcceptedReactiveCycle(
        active: ClinicalInputInvalidationDispatch,
        coordinator: ClinicalInputInvalidationCoordinator,
        runAttemptCount: Int = 0,
        cycle: suspend () -> Unit
    ): ReactiveWorkerOrchestrationResult = runReactiveWorkerOrchestration(
        reactiveWork = ClinicalInvalidationWorkData(
            active.generation,
            active.dispatchToken,
            active.mode,
            active.attempt
        ),
        runAttemptCount = runAttemptCount,
        timeoutMs = 10_000L,
        prepare = {
            PreparedReactiveWorkerCycle {
                cycle()
                true
            }
        },
        onStarted = {},
        onCompleted = {},
        onFailure = {},
        acknowledge = coordinator::acknowledge,
        terminate = coordinator::dispatchTerminated,
        terminateFatal = coordinator::dispatchFatal
    )

    private class FakeClinicalInputInvalidationLedger(
        initial: ClinicalInputInvalidationLedgerState = ClinicalInputInvalidationLedgerState()
    ) : ClinicalInputInvalidationLedger {
        var state: ClinicalInputInvalidationLedgerState = initial

        override suspend fun read(): ClinicalInputInvalidationLedgerState = state

        override suspend fun write(state: ClinicalInputInvalidationLedgerState) {
            this.state = state
        }
    }

    private class BlockingSecondWriteLedger(
        private val secondWriteStarted: CompletableDeferred<Unit>,
        private val releaseSecondWrite: CompletableDeferred<Unit>
    ) : ClinicalInputInvalidationLedger {
        private var writes = 0
        private var state = ClinicalInputInvalidationLedgerState()

        override suspend fun read(): ClinicalInputInvalidationLedgerState = state

        override suspend fun write(state: ClinicalInputInvalidationLedgerState) {
            writes++
            if (writes == 2) {
                secondWriteStarted.complete(Unit)
                releaseSecondWrite.await()
            }
            this.state = state
        }
    }

    private class ClaimWriteFailingLedger(
        private val failuresBeforeSuccess: Int,
        initialState: ClinicalInputInvalidationLedgerState = ClinicalInputInvalidationLedgerState()
    ) : ClinicalInputInvalidationLedger {
        var state = initialState
            private set
        var claimWriteAttempts = 0
            private set

        override suspend fun read(): ClinicalInputInvalidationLedgerState = state

        override suspend fun write(state: ClinicalInputInvalidationLedgerState) {
            if (state.pending == null && state.inFlight != null) {
                claimWriteAttempts++
                if (claimWriteAttempts <= failuresBeforeSuccess) {
                    throw IllegalStateException("claim_write_failed")
                }
            }
            this.state = state
        }
    }

    private class AckWriteFailingLedger(
        private val failuresBeforeSuccess: Int = 0,
        private val blockWriteStarted: CompletableDeferred<Unit>? = null,
        private val cancellation: CancellationException? = null,
        private val persistBeforeCancellation: Boolean = false
    ) : ClinicalInputInvalidationLedger {
        var state = ClinicalInputInvalidationLedgerState()
            private set
        var ackWriteAttempts = 0
            private set

        override suspend fun read(): ClinicalInputInvalidationLedgerState = state

        override suspend fun write(state: ClinicalInputInvalidationLedgerState) {
            val active = this.state.inFlight
            val isAckSettlement = active != null &&
                state.inFlight == null &&
                state.acknowledgedGeneration >= active.generation &&
                state.latestGeneration == this.state.latestGeneration
            if (isAckSettlement) {
                ackWriteAttempts++
                blockWriteStarted?.let {
                    it.complete(Unit)
                    awaitCancellation()
                }
                if (persistBeforeCancellation) this.state = state
                cancellation?.let { throw it }
                if (ackWriteAttempts <= failuresBeforeSuccess) {
                    throw IllegalStateException("ack_write_failed")
                }
            }
            this.state = state
        }
    }

    private class SettlementWriteFailingLedger(
        private val failuresBeforeSuccess: Int = 0,
        private val fatal: Error? = null,
        private val cancellation: CancellationException? = null,
        private val blockWriteStarted: CompletableDeferred<Unit>? = null
    ) : ClinicalInputInvalidationLedger {
        var state = ClinicalInputInvalidationLedgerState()
            private set
        var settlementWriteAttempts = 0
            private set

        override suspend fun read(): ClinicalInputInvalidationLedgerState = state

        override suspend fun write(state: ClinicalInputInvalidationLedgerState) {
            val isSettlement = this.state.inFlight != null &&
                state.inFlight == null &&
                state.pending != null &&
                state.latestGeneration == this.state.latestGeneration
            if (isSettlement) {
                settlementWriteAttempts++
                blockWriteStarted?.let {
                    it.complete(Unit)
                    awaitCancellation()
                }
                cancellation?.let { throw it }
                fatal?.let { throw it }
                if (settlementWriteAttempts <= failuresBeforeSuccess) {
                    throw IllegalStateException("settlement_write_failed")
                }
            }
            this.state = state
        }
    }

    private class FatalSettlementError(@Suppress("unused") marker: Any) : Error("fatal_settlement")

    private class AckSettlementCancellation(
        @Suppress("unused") marker: Any
    ) : CancellationException("ack_cancelled")

    private class ExactSettlementCancellation(
        @Suppress("unused") marker: Any
    ) : CancellationException("settlement_cancelled")

    private companion object {
        const val DEBOUNCE_MS = 1_000L
        const val RETRY_DELAY_MS = 250L
    }
}
