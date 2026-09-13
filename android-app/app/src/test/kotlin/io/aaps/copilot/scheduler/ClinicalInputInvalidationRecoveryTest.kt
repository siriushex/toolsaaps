package io.aaps.copilot.scheduler

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
class ClinicalInputInvalidationRecoveryTest {

    @Test
    fun crashBeforeEnqueueRedrivesWithoutConsumingCoordinatorAttempt() = runTest {
        val ledger = FakeLedger(inFlightState(attempt = 0))
        val requests = mutableListOf<ClinicalInputInvalidationDispatch>()
        val coordinator = coordinator(
            ledger = ledger,
            ownershipStatus = ClinicalInvalidationOwnershipStatus.ABSENT_OR_TERMINAL,
            requests = requests
        )

        coordinator.hydrateAndRedrive()
        advanceTimeBy(RETRY_DELAY_MS)
        runCurrent()

        assertThat(requests).hasSize(1)
        assertThat(requests.single().generation).isEqualTo(1L)
        assertThat(requests.single().attempt).isEqualTo(0)
        assertThat(requests.single().dispatchToken).isEqualTo("new-token-1")
    }

    @Test
    fun acceptedEnqueuePreservesExactOwnershipAndRestoredWorkerAcksWithZeroRedrive() = runTest {
        val ledger = FakeLedger(inFlightState(attempt = 0))
        val requests = mutableListOf<ClinicalInputInvalidationDispatch>()
        val coordinator = coordinator(
            ledger = ledger,
            ownershipStatus = ClinicalInvalidationOwnershipStatus.ACTIVE,
            requests = requests
        )

        coordinator.hydrateAndRedrive()
        advanceTimeBy(RETRY_DELAY_MS * 4)
        runCurrent()

        assertThat(requests).isEmpty()
        assertThat(coordinator.snapshot().inFlightDispatchToken).isEqualTo("restored-token")
        assertThat(coordinator.acknowledge(1L, "stale-token")).isFalse()
        assertThat(coordinator.acknowledge(1L, "restored-token")).isTrue()
        advanceTimeBy(RETRY_DELAY_MS * 4)
        runCurrent()
        assertThat(requests).isEmpty()
    }

    @Test
    fun acceptedTerminalEvidenceCompletesAckMetadataWithoutRerunningClinicalCycle() = runTest {
        val ledger = FakeLedger(inFlightState(attempt = 0))
        var cycleCount = 1
        val requests = mutableListOf<ClinicalInputInvalidationDispatch>()
        val coordinator = coordinator(
            ledger = ledger,
            ownershipStatus = ClinicalInvalidationOwnershipStatus.ACCEPTED_TERMINAL,
            requests = requests,
            onDispatch = { cycleCount++ }
        )

        coordinator.hydrateAndRedrive()
        advanceTimeBy(RETRY_DELAY_MS * 4)
        runCurrent()

        assertThat(cycleCount).isEqualTo(1)
        assertThat(requests).isEmpty()
        assertThat(ledger.state.acknowledgedGeneration).isEqualTo(1L)
        assertThat(ledger.state.inFlight).isNull()
        assertThat(ledger.state.pending).isNull()
    }

    @Test
    fun acceptedAckTimeoutEvidenceAfterRecreationNeverRerunsClinicalCycle() = runTest {
        val ledger = FakeLedger(inFlightState(attempt = 1))
        var cycleCount = 1
        val requests = mutableListOf<ClinicalInputInvalidationDispatch>()
        val coordinator = coordinator(
            ledger = ledger,
            ownershipStatus = ClinicalInvalidationOwnershipStatus.ACCEPTED_TERMINAL,
            requests = requests,
            onDispatch = { cycleCount++ }
        )

        coordinator.hydrateAndRedrive()
        advanceTimeBy(RETRY_DELAY_MS * 8)
        runCurrent()

        assertThat(cycleCount).isEqualTo(1)
        assertThat(requests).isEmpty()
        assertThat(ledger.state.acknowledgedGeneration).isEqualTo(1L)
        assertThat(ledger.state.inFlight).isNull()
    }

    @Test
    fun fatalTerminalEvidenceCompletesFatalMetadataWithoutClinicalRedrive() = runTest {
        val ledger = FakeLedger(inFlightState(attempt = 0))
        var cycleCount = 1
        val requests = mutableListOf<ClinicalInputInvalidationDispatch>()
        val coordinator = coordinator(
            ledger = ledger,
            ownershipStatus = ClinicalInvalidationOwnershipStatus.FATAL_TERMINAL,
            requests = requests,
            onDispatch = { cycleCount++ }
        )

        coordinator.hydrateAndRedrive()
        advanceTimeBy(RETRY_DELAY_MS * 8)
        runCurrent()

        assertThat(cycleCount).isEqualTo(1)
        assertThat(requests).isEmpty()
        assertThat(ledger.state.inFlight).isNull()
        assertThat(ledger.state.pending?.generation).isEqualTo(1L)
        assertThat(ledger.state.pending?.attempt)
            .isEqualTo(MAX_CLINICAL_INVALIDATION_PERSISTED_ATTEMPT)
    }

    @Test
    fun fatalTerminalOwnerPreservesOneDistinctPendingFollowUpAfterRecreation() = runTest {
        val ledger = FakeLedger(
            inFlightState(attempt = 0).copy(
                latestGeneration = 2L,
                pending = ClinicalInputInvalidationPending(
                    generation = 2L,
                    sources = setOf(ClinicalInputInvalidationSource.CONTEXT_EVENT),
                    attempt = 0
                )
            )
        )
        val requests = mutableListOf<ClinicalInputInvalidationDispatch>()
        val coordinator = coordinator(
            ledger = ledger,
            ownershipStatus = ClinicalInvalidationOwnershipStatus.FATAL_TERMINAL,
            requests = requests
        )

        coordinator.hydrateAndRedrive()
        advanceTimeBy(RETRY_DELAY_MS)
        runCurrent()

        assertThat(requests).hasSize(1)
        assertThat(requests.single().generation).isEqualTo(2L)
        assertThat(requests.single().sources)
            .containsExactly(ClinicalInputInvalidationSource.CONTEXT_EVENT)
        assertThat(requests.single().attempt).isEqualTo(0)
    }

    @Test
    fun activeAndPendingSurviveRecreationAsOneExactFollowUp() = runTest {
        val state = inFlightState(attempt = 0).copy(
            latestGeneration = 2L,
            pending = ClinicalInputInvalidationPending(
                generation = 2L,
                sources = setOf(ClinicalInputInvalidationSource.CONTEXT_EVENT),
                attempt = 0
            )
        )
        val ledger = FakeLedger(state)
        val requests = mutableListOf<ClinicalInputInvalidationDispatch>()
        val coordinator = coordinator(
            ledger = ledger,
            ownershipStatus = ClinicalInvalidationOwnershipStatus.ACTIVE,
            requests = requests
        )

        coordinator.hydrateAndRedrive()
        assertThat(requests).isEmpty()
        assertThat(coordinator.acknowledge(1L, "restored-token")).isTrue()
        advanceTimeBy(TRAILING_DELAY_MS)
        runCurrent()

        assertThat(requests).hasSize(1)
        assertThat(requests.single().generation).isEqualTo(2L)
        assertThat(requests.single().sources)
            .containsExactly(ClinicalInputInvalidationSource.CONTEXT_EVENT)
    }

    @Test
    fun attemptOneCrashWithoutMatchingWorkStillGetsOneBoundedRedrive() = runTest {
        val ledger = FakeLedger(inFlightState(attempt = 1))
        val requests = mutableListOf<ClinicalInputInvalidationDispatch>()
        val coordinator = coordinator(
            ledger = ledger,
            ownershipStatus = ClinicalInvalidationOwnershipStatus.ABSENT_OR_TERMINAL,
            requests = requests
        )

        coordinator.hydrateAndRedrive()
        advanceTimeBy(RETRY_DELAY_MS)
        runCurrent()

        assertThat(requests).hasSize(1)
        assertThat(requests.single().attempt).isEqualTo(1)
    }

    @Test
    fun unknownTerminalEvidenceFailsClosedWithoutClinicalRedrive() = runTest {
        val ledger = FakeLedger(inFlightState(attempt = 1))
        val requests = mutableListOf<ClinicalInputInvalidationDispatch>()
        val coordinator = coordinator(
            ledger = ledger,
            ownershipStatus = ClinicalInvalidationOwnershipStatus.UNKNOWN,
            requests = requests
        )

        coordinator.hydrateAndRedrive()
        advanceTimeBy(RETRY_DELAY_MS)
        runCurrent()

        assertThat(requests).isEmpty()
        assertThat(coordinator.snapshot().inFlightDispatchToken).isEqualTo("restored-token")
        assertThat(coordinator.acknowledge(1L, "different-token")).isFalse()
        assertThat(coordinator.snapshot().inFlightDispatchToken).isEqualTo("restored-token")
    }

    @Test
    fun laterDurableInputRequeriesUnknownOwnerAndSchedulesFollowUpAfterExactAcceptedTerminal() =
        runTest {
            val ledger = FakeLedger(inFlightState(attempt = 0))
            val requests = mutableListOf<ClinicalInputInvalidationDispatch>()
            val queried = mutableListOf<ClinicalInputInvalidationDispatch>()
            val statuses = ArrayDeque(
                listOf(
                    ClinicalInvalidationOwnershipStatus.UNKNOWN,
                    ClinicalInvalidationOwnershipStatus.ACCEPTED_TERMINAL
                )
            )
            val coordinator = ClinicalInputInvalidationCoordinator(
                scope = this,
                ledger = ledger,
                ownershipStatusSource = { dispatch ->
                    queried += dispatch
                    statuses.removeFirst()
                },
                trailingDelayMs = TRAILING_DELAY_MS,
                retryDelayMs = RETRY_DELAY_MS,
                nowMs = { testScheduler.currentTime },
                tokenSource = { "new-token-${requests.size + 1}" },
                modeSource = { ClinicalInputInvalidationMode.NORMAL },
                sink = { request ->
                    requests += request
                    true
                }
            )

            coordinator.hydrateAndRedrive()
            coordinator.invalidateAndAwait(ClinicalInputInvalidationSource.CONTEXT_EVENT)
            advanceTimeBy(TRAILING_DELAY_MS)
            runCurrent()

            assertThat(queried).hasSize(2)
            assertThat(queried.map { it.generation }).containsExactly(1L, 1L).inOrder()
            assertThat(queried.map { it.dispatchToken })
                .containsExactly("restored-token", "restored-token")
                .inOrder()
            assertThat(requests).hasSize(1)
            assertThat(requests.single().generation).isEqualTo(2L)
            assertThat(requests.single().attempt).isEqualTo(0)
            assertThat(requests.single().sources)
                .containsExactly(ClinicalInputInvalidationSource.CONTEXT_EVENT)
            assertThat(ledger.state.acknowledgedGeneration).isEqualTo(1L)
        }

    @Test
    fun laterDurableInputRequeriesUnknownOwnerButActiveRetainsOwnerAndPending() = runTest {
        val ledger = FakeLedger(inFlightState(attempt = 0))
        val requests = mutableListOf<ClinicalInputInvalidationDispatch>()
        val queried = mutableListOf<ClinicalInputInvalidationDispatch>()
        val statuses = ArrayDeque(
            listOf(
                ClinicalInvalidationOwnershipStatus.UNKNOWN,
                ClinicalInvalidationOwnershipStatus.ACTIVE
            )
        )
        val coordinator = ClinicalInputInvalidationCoordinator(
            scope = this,
            ledger = ledger,
            ownershipStatusSource = { dispatch ->
                queried += dispatch
                statuses.removeFirst()
            },
            trailingDelayMs = TRAILING_DELAY_MS,
            retryDelayMs = RETRY_DELAY_MS,
            nowMs = { testScheduler.currentTime },
            tokenSource = { "unexpected-token" },
            modeSource = { ClinicalInputInvalidationMode.NORMAL },
            sink = { request ->
                requests += request
                true
            }
        )

        coordinator.hydrateAndRedrive()
        coordinator.invalidateAndAwait(ClinicalInputInvalidationSource.LOCAL_ACTIVITY)
        advanceTimeBy(RETRY_DELAY_MS * 4)
        runCurrent()

        assertThat(queried).hasSize(2)
        assertThat(queried.map { it.dispatchToken })
            .containsExactly("restored-token", "restored-token")
            .inOrder()
        assertThat(requests).isEmpty()
        assertThat(ledger.state.inFlight?.dispatchToken).isEqualTo("restored-token")
        assertThat(ledger.state.pending?.generation).isEqualTo(2L)
        assertThat(ledger.state.pending?.attempt).isEqualTo(0)
        assertThat(ledger.state.pending?.sources)
            .containsExactly(ClinicalInputInvalidationSource.LOCAL_ACTIVITY)
    }

    @Test
    fun durableInputsConflateWhileUnknownOwnershipRequeryIsActive() = runTest {
        val ledger = FakeLedger(inFlightState(attempt = 0))
        val requests = mutableListOf<ClinicalInputInvalidationDispatch>()
        val requeryStarted = CompletableDeferred<Unit>()
        val releaseRequery = CompletableDeferred<Unit>()
        val queried = mutableListOf<ClinicalInputInvalidationDispatch>()
        val coordinator = ClinicalInputInvalidationCoordinator(
            scope = this,
            ledger = ledger,
            ownershipStatusSource = { dispatch ->
                queried += dispatch
                if (queried.size == 1) {
                    ClinicalInvalidationOwnershipStatus.UNKNOWN
                } else {
                    requeryStarted.complete(Unit)
                    releaseRequery.await()
                    ClinicalInvalidationOwnershipStatus.UNKNOWN
                }
            },
            trailingDelayMs = TRAILING_DELAY_MS,
            retryDelayMs = RETRY_DELAY_MS,
            nowMs = { testScheduler.currentTime },
            tokenSource = { "unexpected-token" },
            modeSource = { ClinicalInputInvalidationMode.NORMAL },
            sink = { request ->
                requests += request
                true
            }
        )

        coordinator.hydrateAndRedrive()
        val first = async {
            coordinator.invalidateAndAwait(ClinicalInputInvalidationSource.CONTEXT_EVENT)
        }
        runCurrent()
        assertThat(requeryStarted.isCompleted).isTrue()
        coordinator.invalidateAndAwait(ClinicalInputInvalidationSource.BROADCAST_INGEST)
        coordinator.invalidateAndAwait(ClinicalInputInvalidationSource.HEALTH_CONNECT_ACTIVITY)

        assertThat(queried).hasSize(2)
        releaseRequery.complete(Unit)
        first.await()
        coordinator.invalidateAndAwait(ClinicalInputInvalidationSource.LOCAL_NIGHTSCOUT)
        advanceTimeBy(RETRY_DELAY_MS * 4)
        runCurrent()

        assertThat(queried).hasSize(2)
        assertThat(queried.map { it.dispatchToken })
            .containsExactly("restored-token", "restored-token")
            .inOrder()
        assertThat(requests).isEmpty()
        assertThat(ledger.state.inFlight?.dispatchToken).isEqualTo("restored-token")
        assertThat(ledger.state.pending?.generation).isEqualTo(5L)
        assertThat(ledger.state.pending?.attempt).isEqualTo(0)
        assertThat(ledger.state.pending?.sources).containsExactly(
            ClinicalInputInvalidationSource.CONTEXT_EVENT,
            ClinicalInputInvalidationSource.BROADCAST_INGEST,
            ClinicalInputInvalidationSource.HEALTH_CONNECT_ACTIVITY,
            ClinicalInputInvalidationSource.LOCAL_NIGHTSCOUT
        )
    }

    @Test
    fun cancelledUnknownOwnershipRequeryPropagatesAndLeavesLaterInputRecoverable() = runTest {
        val ledger = FakeLedger(inFlightState(attempt = 0))
        val requeryStarted = CompletableDeferred<Unit>()
        val queried = mutableListOf<ClinicalInputInvalidationDispatch>()
        val coordinator = ClinicalInputInvalidationCoordinator(
            scope = this,
            ledger = ledger,
            ownershipStatusSource = { dispatch ->
                queried += dispatch
                when (queried.size) {
                    1 -> ClinicalInvalidationOwnershipStatus.UNKNOWN
                    2 -> {
                        requeryStarted.complete(Unit)
                        awaitCancellation()
                    }
                    else -> ClinicalInvalidationOwnershipStatus.ACTIVE
                }
            },
            trailingDelayMs = TRAILING_DELAY_MS,
            retryDelayMs = RETRY_DELAY_MS,
            nowMs = { testScheduler.currentTime },
            tokenSource = { "unexpected-token" },
            modeSource = { ClinicalInputInvalidationMode.NORMAL },
            sink = { false }
        )

        coordinator.hydrateAndRedrive()
        val cancelled = launch {
            coordinator.invalidateAndAwait(ClinicalInputInvalidationSource.CONTEXT_EVENT)
        }
        requeryStarted.await()
        cancelled.cancelAndJoin()

        assertThat(cancelled.isCancelled).isTrue()
        coordinator.invalidateAndAwait(ClinicalInputInvalidationSource.LOCAL_ACTIVITY)

        assertThat(queried).hasSize(3)
        assertThat(queried.map { it.dispatchToken })
            .containsExactly("restored-token", "restored-token", "restored-token")
            .inOrder()
        assertThat(ledger.state.inFlight?.dispatchToken).isEqualTo("restored-token")
        assertThat(ledger.state.pending?.generation).isEqualTo(3L)
    }

    @Test
    fun cancelledUnknownOwnershipRequeryClearsClaimAfterConcurrentLedgerWrite() = runTest {
        var ledgerState = inFlightState(attempt = 0)
        val writeStarted = CompletableDeferred<Unit>()
        val releaseWrite = CompletableDeferred<Unit>()
        val ledger = object : ClinicalInputInvalidationLedger {
            override suspend fun read(): ClinicalInputInvalidationLedgerState = ledgerState

            override suspend fun write(state: ClinicalInputInvalidationLedgerState) {
                if (state.latestGeneration == 3L) {
                    writeStarted.complete(Unit)
                    releaseWrite.await()
                }
                ledgerState = state
            }
        }
        val requeryStarted = CompletableDeferred<Unit>()
        val queried = mutableListOf<ClinicalInputInvalidationDispatch>()
        val coordinator = ClinicalInputInvalidationCoordinator(
            scope = this,
            ledger = ledger,
            ownershipStatusSource = { dispatch ->
                queried += dispatch
                when (queried.size) {
                    1 -> ClinicalInvalidationOwnershipStatus.UNKNOWN
                    2 -> {
                        requeryStarted.complete(Unit)
                        awaitCancellation()
                    }
                    else -> ClinicalInvalidationOwnershipStatus.ACTIVE
                }
            },
            trailingDelayMs = TRAILING_DELAY_MS,
            retryDelayMs = RETRY_DELAY_MS,
            nowMs = { testScheduler.currentTime },
            tokenSource = { "unexpected-token" },
            modeSource = { ClinicalInputInvalidationMode.NORMAL },
            sink = { false }
        )

        coordinator.hydrateAndRedrive()
        val cancelled = launch {
            coordinator.invalidateAndAwait(ClinicalInputInvalidationSource.CONTEXT_EVENT)
        }
        requeryStarted.await()
        val concurrent = async {
            coordinator.invalidateAndAwait(ClinicalInputInvalidationSource.BROADCAST_INGEST)
        }
        writeStarted.await()

        cancelled.cancel()
        runCurrent()
        releaseWrite.complete(Unit)
        concurrent.await()
        cancelled.join()
        coordinator.invalidateAndAwait(ClinicalInputInvalidationSource.LOCAL_ACTIVITY)

        assertThat(cancelled.isCancelled).isTrue()
        assertThat(queried).hasSize(3)
        assertThat(queried.map { it.dispatchToken })
            .containsExactly("restored-token", "restored-token", "restored-token")
            .inOrder()
        assertThat(ledgerState.inFlight?.dispatchToken).isEqualTo("restored-token")
        assertThat(ledgerState.pending?.generation).isEqualTo(4L)
    }

    @Test
    fun cancellationWhileTerminalAckWaitsPreservesExactCandidateForLaterRequery() = runTest {
        var ledgerState = inFlightState(attempt = 0)
        val terminalQueryStarted = CompletableDeferred<Unit>()
        val allowTerminalReturn = CompletableDeferred<Unit>()
        val writeStarted = CompletableDeferred<Unit>()
        val releaseWrite = CompletableDeferred<Unit>()
        val ledger = object : ClinicalInputInvalidationLedger {
            override suspend fun read(): ClinicalInputInvalidationLedgerState = ledgerState

            override suspend fun write(state: ClinicalInputInvalidationLedgerState) {
                if (
                    state.latestGeneration == 3L &&
                    state.inFlight?.dispatchToken == "restored-token" &&
                    state.pending?.generation == 3L
                ) {
                    writeStarted.complete(Unit)
                    releaseWrite.await()
                }
                ledgerState = state
            }
        }
        val queried = mutableListOf<ClinicalInputInvalidationDispatch>()
        val requests = mutableListOf<ClinicalInputInvalidationDispatch>()
        val coordinator = ClinicalInputInvalidationCoordinator(
            scope = this,
            ledger = ledger,
            ownershipStatusSource = { dispatch ->
                queried += dispatch
                when (queried.size) {
                    1 -> ClinicalInvalidationOwnershipStatus.UNKNOWN
                    2 -> {
                        terminalQueryStarted.complete(Unit)
                        allowTerminalReturn.await()
                        ClinicalInvalidationOwnershipStatus.ACCEPTED_TERMINAL
                    }
                    3 -> ClinicalInvalidationOwnershipStatus.ACCEPTED_TERMINAL
                    else -> error("unexpected ownership query")
                }
            },
            trailingDelayMs = TRAILING_DELAY_MS,
            retryDelayMs = RETRY_DELAY_MS,
            nowMs = { testScheduler.currentTime },
            tokenSource = { "new-token-${requests.size + 1}" },
            modeSource = { ClinicalInputInvalidationMode.NORMAL },
            sink = { request ->
                requests += request
                true
            }
        )

        coordinator.hydrateAndRedrive()
        val cancelled = launch {
            coordinator.invalidateAndAwait(ClinicalInputInvalidationSource.CONTEXT_EVENT)
        }
        terminalQueryStarted.await()
        val concurrent = async {
            coordinator.invalidateAndAwait(ClinicalInputInvalidationSource.BROADCAST_INGEST)
        }
        writeStarted.await()
        allowTerminalReturn.complete(Unit)
        runCurrent()

        cancelled.cancel()
        runCurrent()
        releaseWrite.complete(Unit)
        concurrent.await()
        cancelled.join()
        assertThat(cancelled.isCancelled).isTrue()
        assertThat(ledgerState.inFlight?.dispatchToken).isEqualTo("restored-token")

        coordinator.invalidateAndAwait(ClinicalInputInvalidationSource.LOCAL_ACTIVITY)
        coordinator.invalidateAndAwait(ClinicalInputInvalidationSource.LOCAL_NIGHTSCOUT)
        advanceTimeBy(TRAILING_DELAY_MS)
        runCurrent()

        assertThat(queried).hasSize(3)
        assertThat(queried.map { it.generation }).containsExactly(1L, 1L, 1L).inOrder()
        assertThat(queried.map { it.dispatchToken })
            .containsExactly("restored-token", "restored-token", "restored-token")
            .inOrder()
        assertThat(ledgerState.acknowledgedGeneration).isEqualTo(1L)
        assertThat(requests).hasSize(1)
        assertThat(requests.single().generation).isEqualTo(5L)
        assertThat(requests.single().attempt).isEqualTo(0)
        assertThat(requests.single().sources).containsExactly(
            ClinicalInputInvalidationSource.CONTEXT_EVENT,
            ClinicalInputInvalidationSource.BROADCAST_INGEST,
            ClinicalInputInvalidationSource.LOCAL_ACTIVITY,
            ClinicalInputInvalidationSource.LOCAL_NIGHTSCOUT
        )
    }

    @Test
    fun ambiguousTerminalWithoutPendingNeverRedrivesOldCycleButLaterInputRunsOnce() = runTest {
        val ledger = FakeLedger(inFlightState(attempt = 0))
        val requests = mutableListOf<ClinicalInputInvalidationDispatch>()
        val coordinator = coordinator(
            ledger = ledger,
            ownershipStatus = ClinicalInvalidationOwnershipStatus.AMBIGUOUS_TERMINAL,
            requests = requests
        )

        coordinator.hydrateAndRedrive()
        advanceTimeBy(RETRY_DELAY_MS * 4)
        runCurrent()

        assertThat(requests).isEmpty()
        assertThat(ledger.state.inFlight).isNull()
        assertThat(ledger.state.pending?.attempt)
            .isEqualTo(MAX_CLINICAL_INVALIDATION_PERSISTED_ATTEMPT)

        coordinator.invalidateAndAwait(ClinicalInputInvalidationSource.CONTEXT_EVENT)
        advanceTimeBy(TRAILING_DELAY_MS)
        runCurrent()

        assertThat(requests).hasSize(1)
        assertThat(requests.single().generation).isEqualTo(2L)
        assertThat(requests.single().sources)
            .contains(ClinicalInputInvalidationSource.CONTEXT_EVENT)
    }

    @Test
    fun ambiguousTerminalRecreationPreservesAndSchedulesOneDistinctPendingFollowUp() = runTest {
        val ledger = FakeLedger(
            inFlightState(attempt = 0).copy(
                latestGeneration = 2L,
                pending = ClinicalInputInvalidationPending(
                    generation = 2L,
                    sources = setOf(ClinicalInputInvalidationSource.HEALTH_CONNECT_ACTIVITY),
                    attempt = 0
                )
            )
        )
        val requests = mutableListOf<ClinicalInputInvalidationDispatch>()
        val coordinator = coordinator(
            ledger = ledger,
            ownershipStatus = ClinicalInvalidationOwnershipStatus.AMBIGUOUS_TERMINAL,
            requests = requests
        )

        coordinator.hydrateAndRedrive()
        advanceTimeBy(RETRY_DELAY_MS)
        runCurrent()

        assertThat(requests).hasSize(1)
        assertThat(requests.single().generation).isEqualTo(2L)
        assertThat(requests.single().sources)
            .containsExactly(ClinicalInputInvalidationSource.HEALTH_CONNECT_ACTIVITY)
        assertThat(requests.single().attempt).isEqualTo(0)
    }

    @Test
    fun sameProcessAmbiguousSettlementReleasesOldOwnerAndRunsDistinctPendingOnce() = runTest {
        val ledger = FakeLedger(
            inFlightState(attempt = 0).copy(
                latestGeneration = 2L,
                pending = ClinicalInputInvalidationPending(
                    generation = 2L,
                    sources = setOf(ClinicalInputInvalidationSource.BROADCAST_INGEST),
                    attempt = 0
                )
            )
        )
        val requests = mutableListOf<ClinicalInputInvalidationDispatch>()
        val coordinator = coordinator(
            ledger = ledger,
            ownershipStatus = ClinicalInvalidationOwnershipStatus.ACTIVE,
            requests = requests
        )

        coordinator.hydrateAndRedrive()
        assertThat(coordinator.dispatchAmbiguous(1L, "restored-token")).isTrue()
        advanceTimeBy(TRAILING_DELAY_MS)
        runCurrent()

        assertThat(requests).hasSize(1)
        assertThat(requests.single().generation).isEqualTo(2L)
        assertThat(requests.single().sources)
            .containsExactly(ClinicalInputInvalidationSource.BROADCAST_INGEST)
        assertThat(ledger.state.inFlight?.dispatchToken).isEqualTo(requests.single().dispatchToken)
    }

    @Test
    fun runtimeFatalSettlementPreservesAndSchedulesDistinctPendingAtOriginalAttempt() = runTest {
        val ledger = FakeLedger(
            inFlightState(attempt = 0).copy(
                latestGeneration = 2L,
                pending = ClinicalInputInvalidationPending(
                    generation = 2L,
                    sources = setOf(ClinicalInputInvalidationSource.CONTEXT_EVENT),
                    attempt = 0
                )
            )
        )
        val requests = mutableListOf<ClinicalInputInvalidationDispatch>()
        val coordinator = coordinator(
            ledger = ledger,
            ownershipStatus = ClinicalInvalidationOwnershipStatus.ACTIVE,
            requests = requests
        )

        coordinator.hydrateAndRedrive()
        assertThat(coordinator.dispatchFatal(1L, "restored-token")).isTrue()
        advanceTimeBy(TRAILING_DELAY_MS)
        runCurrent()

        assertThat(requests).hasSize(1)
        assertThat(requests.single().generation).isEqualTo(2L)
        assertThat(requests.single().attempt).isEqualTo(0)
        assertThat(requests.single().sources)
            .containsExactly(ClinicalInputInvalidationSource.CONTEXT_EVENT)
    }

    @Test
    fun explicitRejectedResultWithEvidenceFailureRedrivesThroughCoordinatorExactlyOnce() = runTest {
        val ledger = FakeLedger(inFlightState(attempt = 0))
        val requests = mutableListOf<ClinicalInputInvalidationDispatch>()
        val coordinator = coordinator(
            ledger = ledger,
            ownershipStatus = ClinicalInvalidationOwnershipStatus.ACTIVE,
            requests = requests
        )
        val cycleState = ReactiveClinicalCycleState()
        var cycleCount = 0
        val evidenceStore = object : ClinicalInvalidationExecutionEvidenceStore {
            private var evidence: ClinicalInvalidationExecutionEvidence? = null

            override suspend fun read(): ClinicalInvalidationExecutionEvidence? = evidence

            override suspend fun write(evidence: ClinicalInvalidationExecutionEvidence) {
                if (this.evidence == null) this.evidence = evidence
                else throw IllegalStateException("unaccepted_write_failed")
            }
        }
        coordinator.hydrateAndRedrive()

        val first = runReactiveWorkerOrchestration(
            reactiveWork = ClinicalInvalidationWorkData(
                generation = 1L,
                dispatchToken = "restored-token",
                mode = ClinicalInputInvalidationMode.NORMAL,
                coordinatorAttempt = 0
            ),
            runAttemptCount = 0,
            timeoutMs = 1_000L,
            prepare = {
                PreparedReactiveWorkerCycle {
                    runClinicalCycleWithExecutionEvidence(
                        ClinicalInvalidationWorkData(
                            generation = 1L,
                            dispatchToken = "restored-token",
                            mode = ClinicalInputInvalidationMode.NORMAL,
                            coordinatorAttempt = 0
                        ),
                        evidenceStore,
                        cycleState
                    ) {
                        cycleCount++
                        false
                    }
                }
            },
            onStarted = {},
            onCompleted = {},
            onFailure = {},
            acknowledge = coordinator::acknowledge,
            terminate = coordinator::dispatchTerminated,
            terminateAmbiguous = coordinator::dispatchAmbiguous,
            terminateFatal = coordinator::dispatchFatal,
            clinicalCycleState = cycleState
        )

        assertThat(first).isEqualTo(ReactiveWorkerOrchestrationResult.FAILURE)
        advanceTimeBy(RETRY_DELAY_MS)
        runCurrent()
        assertThat(requests).hasSize(1)
        assertThat(requests.single().attempt).isEqualTo(1)

        val nextWork = checkNotNull(ClinicalInvalidationWorkData.from(requests.single().toWorkData()))
        val accepted = runClinicalCycleWithExecutionEvidence(
            nextWork,
            InMemoryClinicalInvalidationExecutionEvidenceStore()
        ) {
            cycleCount++
            true
        }
        assertThat(accepted).isTrue()
        assertThat(coordinator.acknowledge(nextWork.generation, nextWork.dispatchToken)).isTrue()
        advanceTimeBy(RETRY_DELAY_MS * 4)
        runCurrent()

        assertThat(cycleCount).isEqualTo(2)
        assertThat(requests).hasSize(1)
    }

    @Test
    fun acceptedAckFailureThenTerminalPreflightReadFailureNeverRedrivesOldOwner() = runTest {
        val fixture = acceptedAckFailureFixture()
        fixture.store.readBehavior = { throw IllegalStateException("recreated_read_failed") }

        val preflight = runReactiveWorkerEvidencePreflight(
            reactiveWork = fixture.work,
            runAttemptCount = 2,
            timeoutMs = 1_000L,
            readEvidence = fixture.store::read,
            onFailure = {},
            acknowledge = fixture.restored::acknowledge,
            terminate = fixture.restored::dispatchTerminated,
            terminateAmbiguous = fixture.restored::dispatchAmbiguous,
            terminateFatal = fixture.restored::dispatchFatal
        )

        assertThat(preflight.action).isEqualTo(ReactiveWorkerPreflightAction.FAILURE)
        assertThat(fixture.cycleCount.get()).isEqualTo(1)
        advanceTimeBy(RETRY_DELAY_MS * 4)
        runCurrent()
        assertThat(fixture.requests).isEmpty()

        fixture.restored.invalidateAndAwait(ClinicalInputInvalidationSource.PLANNED_ACTIVITY)
        advanceTimeBy(TRAILING_DELAY_MS)
        runCurrent()
        assertThat(fixture.requests).hasSize(1)
        assertThat(fixture.cycleCount.get()).isEqualTo(2)
    }

    @Test
    fun acceptedAckFailureThenPreflightReadTimeoutClosesAmbiguousOwner() = runTest {
        val fixture = acceptedAckFailureFixture()
        fixture.store.readBehavior = { awaitCancellation() }

        val result = runCatching {
            runReactiveWorkerEvidencePreflight(
                reactiveWork = fixture.work,
                runAttemptCount = 0,
                timeoutMs = 100L,
                readEvidence = fixture.store::read,
                onFailure = {},
                acknowledge = fixture.restored::acknowledge,
                terminate = fixture.restored::dispatchTerminated,
                terminateAmbiguous = fixture.restored::dispatchAmbiguous,
                terminateFatal = fixture.restored::dispatchFatal
            )
        }

        assertThat(result.exceptionOrNull()).isInstanceOf(TimeoutCancellationException::class.java)
        assertThat(fixture.cycleCount.get()).isEqualTo(1)
        advanceTimeBy(RETRY_DELAY_MS * 4)
        runCurrent()
        assertThat(fixture.requests).isEmpty()

        fixture.restored.invalidateAndAwait(ClinicalInputInvalidationSource.HEALTH_CONNECT_ACTIVITY)
        advanceTimeBy(TRAILING_DELAY_MS)
        runCurrent()
        assertThat(fixture.requests).hasSize(1)
        assertThat(fixture.cycleCount.get()).isEqualTo(2)
    }

    @Test
    fun acceptedAckFailureThenCancelledPreflightReadClosesAmbiguousOwner() = runTest {
        val fixture = acceptedAckFailureFixture()
        val readStarted = kotlinx.coroutines.CompletableDeferred<Unit>()
        fixture.store.readBehavior = {
            readStarted.complete(Unit)
            awaitCancellation()
        }

        val job = launch {
            runReactiveWorkerEvidencePreflight(
                reactiveWork = fixture.work,
                runAttemptCount = 0,
                timeoutMs = 10_000L,
                readEvidence = fixture.store::read,
                onFailure = {},
                acknowledge = fixture.restored::acknowledge,
                terminate = fixture.restored::dispatchTerminated,
                terminateAmbiguous = fixture.restored::dispatchAmbiguous,
                terminateFatal = fixture.restored::dispatchFatal
            )
        }
        runCurrent()
        assertThat(readStarted.isCompleted).isTrue()
        job.cancelAndJoin()

        assertThat(job.isCancelled).isTrue()
        assertThat(fixture.cycleCount.get()).isEqualTo(1)
        advanceTimeBy(RETRY_DELAY_MS * 4)
        runCurrent()
        assertThat(fixture.requests).isEmpty()

        fixture.restored.invalidateAndAwait(ClinicalInputInvalidationSource.LOCAL_ACTIVITY)
        advanceTimeBy(TRAILING_DELAY_MS)
        runCurrent()
        assertThat(fixture.requests).hasSize(1)
        assertThat(fixture.cycleCount.get()).isEqualTo(2)
    }

    @Test
    fun sharedOwnershipBudgetReturnsTerminalAmbiguousBeforeCoordinatorDeadline() = runTest {
        val ledger = FakeLedger(
            inFlightState(attempt = 0).copy(
                latestGeneration = 2L,
                pending = ClinicalInputInvalidationPending(
                    generation = 2L,
                    sources = setOf(ClinicalInputInvalidationSource.HEALTH_CONNECT_ACTIVITY),
                    attempt = 0
                )
            )
        )
        val requests = mutableListOf<ClinicalInputInvalidationDispatch>()
        val statusSource = WorkManagerClinicalInvalidationOwnershipStatusSource(
            queryWorkInfos = {
                delay(1_000L)
                listOf(
                    ClinicalInvalidationWorkInfo(
                        state = androidx.work.WorkInfo.State.SUCCEEDED,
                        outputData = androidx.work.Data.EMPTY
                    )
                )
            },
            queryEvidence = { awaitCancellation() },
            timeoutMs = 1_500L,
            nowMs = { testScheduler.currentTime }
        )
        val coordinator = ClinicalInputInvalidationCoordinator(
            scope = this,
            ledger = ledger,
            ownershipStatusSource = statusSource,
            ownershipQueryTimeoutMs = 2_000L,
            trailingDelayMs = TRAILING_DELAY_MS,
            retryDelayMs = RETRY_DELAY_MS,
            nowMs = { testScheduler.currentTime },
            tokenSource = { "new-token-${requests.size + 1}" },
            modeSource = { ClinicalInputInvalidationMode.NORMAL },
            sink = { request -> requests += request; true }
        )

        coordinator.hydrateAndRedrive()
        assertThat(testScheduler.currentTime).isEqualTo(1_500L)
        advanceTimeBy(RETRY_DELAY_MS)
        runCurrent()

        assertThat(requests).hasSize(1)
        assertThat(requests.single().generation).isEqualTo(2L)
        assertThat(requests.single().attempt).isEqualTo(0)
        assertThat(requests.single().sources)
            .containsExactly(ClinicalInputInvalidationSource.HEALTH_CONNECT_ACTIVITY)
    }

    @Test
    fun malformedPreflightTerminatesBoundedlyAndLaterDistinctInputCanRunOnce() = runTest {
        val ledger = FakeLedger(inFlightState(attempt = 1))
        val requests = mutableListOf<ClinicalInputInvalidationDispatch>()
        val coordinator = coordinator(
            ledger = ledger,
            ownershipStatus = ClinicalInvalidationOwnershipStatus.ACTIVE,
            requests = requests
        )
        coordinator.hydrateAndRedrive()

        val preflight = runReactiveWorkerEvidencePreflight(
            reactiveWork = ClinicalInvalidationWorkData(
                generation = 1L,
                dispatchToken = "restored-token",
                mode = ClinicalInputInvalidationMode.NORMAL,
                coordinatorAttempt = 1
            ),
            runAttemptCount = 2,
            readEvidence = { throw MalformedClinicalInvalidationExecutionEvidenceException() },
            onFailure = {},
            acknowledge = coordinator::acknowledge,
            terminate = coordinator::dispatchTerminated,
            terminateAmbiguous = coordinator::dispatchAmbiguous,
            terminateFatal = coordinator::dispatchFatal
        )

        assertThat(preflight.action).isEqualTo(ReactiveWorkerPreflightAction.FAILURE)
        advanceTimeBy(RETRY_DELAY_MS * 4)
        runCurrent()
        assertThat(requests).isEmpty()
        assertThat(ledger.state.pending?.attempt)
            .isEqualTo(MAX_CLINICAL_INVALIDATION_PERSISTED_ATTEMPT)

        coordinator.invalidateAndAwait(ClinicalInputInvalidationSource.PLANNED_ACTIVITY)
        advanceTimeBy(TRAILING_DELAY_MS)
        runCurrent()

        assertThat(requests).hasSize(1)
        assertThat(requests.single().generation).isEqualTo(2L)
        assertThat(requests.single().sources)
            .contains(ClinicalInputInvalidationSource.PLANNED_ACTIVITY)
    }

    @Test
    fun recreationAfterDurableAcknowledgeDoesNotQueryOrCreateWork() = runTest {
        var ownershipQueries = 0
        val ledger = FakeLedger(
            ClinicalInputInvalidationLedgerState(
                latestGeneration = 1L,
                acknowledgedGeneration = 1L
            )
        )
        val requests = mutableListOf<ClinicalInputInvalidationDispatch>()
        val coordinator = ClinicalInputInvalidationCoordinator(
            scope = this,
            ledger = ledger,
            ownershipStatusSource = {
                ownershipQueries++
                ClinicalInvalidationOwnershipStatus.ACTIVE
            },
            trailingDelayMs = TRAILING_DELAY_MS,
            retryDelayMs = RETRY_DELAY_MS,
            nowMs = { testScheduler.currentTime },
            tokenSource = { "unexpected-token" },
            modeSource = { ClinicalInputInvalidationMode.NORMAL },
            sink = { request -> requests += request; true }
        )

        coordinator.hydrateAndRedrive()
        advanceTimeBy(RETRY_DELAY_MS * 4)
        runCurrent()

        assertThat(ownershipQueries).isEqualTo(0)
        assertThat(requests).isEmpty()
        assertThat(coordinator.snapshot().hasPendingInvalidation).isFalse()
    }

    @Test
    fun maximumAttemptTerminationCannotWrapToNegativeOrScheduleWork() = runTest {
        val ledger = FakeLedger(inFlightState(attempt = Int.MAX_VALUE))
        val requests = mutableListOf<ClinicalInputInvalidationDispatch>()
        val coordinator = coordinator(
            ledger = ledger,
            ownershipStatus = ClinicalInvalidationOwnershipStatus.ACTIVE,
            requests = requests
        )

        coordinator.hydrateAndRedrive()
        assertThat(coordinator.dispatchTerminated(1L, "restored-token")).isTrue()
        advanceTimeBy(RETRY_DELAY_MS * 20)
        runCurrent()

        assertThat(ledger.state.pending?.attempt).isAtLeast(0)
        assertThat(ledger.state.pending?.attempt).isAtMost(2)
        assertThat(requests).isEmpty()
    }

    @Test
    fun activeAttemptZeroTerminationPreservesDistinctPendingRetryBudget() = runTest {
        assertOrdinaryTerminationPreservesDistinctPending(activeAttempt = 0)
    }

    @Test
    fun activeAttemptOneTerminationPreservesDistinctPendingRetryBudget() = runTest {
        assertOrdinaryTerminationPreservesDistinctPending(activeAttempt = 1)
    }

    @Test
    fun retainedAcceptedOutputAcksAfterDurableEvidenceFailureWithoutClinicalRerun() = runTest {
        val ledger = FakeLedger(inFlightState(attempt = 0))
        var cycleCount = 1
        val requests = mutableListOf<ClinicalInputInvalidationDispatch>()
        val coordinator = coordinatorWithRetainedOutput(
            ledger = ledger,
            outcome = ClinicalInvalidationExecutionOutcome.ACCEPTED,
            requests = requests,
            onDispatch = { cycleCount++ }
        )

        coordinator.hydrateAndRedrive()
        advanceTimeBy(RETRY_DELAY_MS * 4)
        runCurrent()

        assertThat(cycleCount).isEqualTo(1)
        assertThat(requests).isEmpty()
        assertThat(ledger.state.acknowledgedGeneration).isEqualTo(1L)
        assertThat(ledger.state.inFlight).isNull()
    }

    @Test
    fun retainedUnacceptedOutputUsesOnlyBoundedCoordinatorRedrive() = runTest {
        val ledger = FakeLedger(inFlightState(attempt = 0))
        val requests = mutableListOf<ClinicalInputInvalidationDispatch>()
        val coordinator = coordinatorWithRetainedOutput(
            ledger = ledger,
            outcome = ClinicalInvalidationExecutionOutcome.UNACCEPTED,
            requests = requests
        )

        coordinator.hydrateAndRedrive()
        advanceTimeBy(RETRY_DELAY_MS)
        runCurrent()
        assertThat(requests.map { it.attempt }).containsExactly(0)

        assertThat(
            coordinator.dispatchTerminated(
                requests.last().generation,
                requests.last().dispatchToken
            )
        ).isTrue()
        advanceTimeBy(RETRY_DELAY_MS)
        runCurrent()
        assertThat(requests.map { it.attempt }).containsExactly(0, 1).inOrder()

        assertThat(
            coordinator.dispatchTerminated(
                requests.last().generation,
                requests.last().dispatchToken
            )
        ).isTrue()
        advanceTimeBy(RETRY_DELAY_MS * 8)
        runCurrent()
        assertThat(requests).hasSize(2)
    }

    @Test
    fun retainedFatalOutputNeverRerunsOldClinicalOwner() = runTest {
        val ledger = FakeLedger(inFlightState(attempt = 0))
        var cycleCount = 1
        val requests = mutableListOf<ClinicalInputInvalidationDispatch>()
        val coordinator = coordinatorWithRetainedOutput(
            ledger = ledger,
            outcome = ClinicalInvalidationExecutionOutcome.FATAL,
            requests = requests,
            onDispatch = { cycleCount++ }
        )

        coordinator.hydrateAndRedrive()
        advanceTimeBy(RETRY_DELAY_MS * 8)
        runCurrent()

        assertThat(cycleCount).isEqualTo(1)
        assertThat(requests).isEmpty()
        assertThat(ledger.state.pending?.attempt)
            .isEqualTo(MAX_CLINICAL_INVALIDATION_PERSISTED_ATTEMPT)
    }

    @Test
    fun retainedStartedUnknownOutputNeverRerunsOldClinicalOwner() = runTest {
        val ledger = FakeLedger(inFlightState(attempt = 0))
        var cycleCount = 1
        val requests = mutableListOf<ClinicalInputInvalidationDispatch>()
        val coordinator = coordinatorWithRetainedOutput(
            ledger = ledger,
            outcome = ClinicalInvalidationExecutionOutcome.STARTED_UNKNOWN,
            requests = requests,
            onDispatch = { cycleCount++ }
        )

        coordinator.hydrateAndRedrive()
        advanceTimeBy(RETRY_DELAY_MS * 8)
        runCurrent()

        assertThat(cycleCount).isEqualTo(1)
        assertThat(requests).isEmpty()
        assertThat(ledger.state.pending?.attempt)
            .isEqualTo(MAX_CLINICAL_INVALIDATION_PERSISTED_ATTEMPT)
    }

    private suspend fun kotlinx.coroutines.test.TestScope.assertOrdinaryTerminationPreservesDistinctPending(
        activeAttempt: Int
    ) {
        val ledger = FakeLedger(
            inFlightState(attempt = activeAttempt).copy(
                latestGeneration = 2L,
                pending = ClinicalInputInvalidationPending(
                    generation = 2L,
                    sources = setOf(ClinicalInputInvalidationSource.CONTEXT_EVENT),
                    attempt = 0
                )
            )
        )
        val requests = mutableListOf<ClinicalInputInvalidationDispatch>()
        val coordinator = coordinator(
            ledger = ledger,
            ownershipStatus = ClinicalInvalidationOwnershipStatus.ACTIVE,
            requests = requests
        )

        coordinator.hydrateAndRedrive()
        assertThat(coordinator.dispatchTerminated(1L, "restored-token")).isTrue()
        advanceTimeBy(TRAILING_DELAY_MS)
        runCurrent()

        assertThat(requests).hasSize(1)
        assertThat(requests.single().generation).isEqualTo(2L)
        assertThat(requests.single().attempt).isEqualTo(0)
        assertThat(requests.single().sources).containsExactly(
            ClinicalInputInvalidationSource.BROADCAST_INGEST,
            ClinicalInputInvalidationSource.CONTEXT_EVENT
        )

        assertThat(
            coordinator.dispatchTerminated(
                requests.single().generation,
                requests.single().dispatchToken
            )
        ).isTrue()
        advanceTimeBy(RETRY_DELAY_MS)
        runCurrent()

        assertThat(requests).hasSize(2)
        assertThat(requests.last().generation).isEqualTo(2L)
        assertThat(requests.last().attempt).isEqualTo(1)
    }

    private fun kotlinx.coroutines.test.TestScope.coordinatorWithRetainedOutput(
        ledger: ClinicalInputInvalidationLedger,
        outcome: ClinicalInvalidationExecutionOutcome,
        requests: MutableList<ClinicalInputInvalidationDispatch>,
        onDispatch: () -> Unit = {}
    ): ClinicalInputInvalidationCoordinator {
        val output = inFlightState(attempt = 0).inFlight!!
            .executionEvidence(outcome)
            .toWorkData()
        val statusSource = WorkManagerClinicalInvalidationOwnershipStatusSource(
            queryWorkInfos = {
                listOf(
                    ClinicalInvalidationWorkInfo(
                        state = androidx.work.WorkInfo.State.SUCCEEDED,
                        outputData = output
                    )
                )
            },
            queryEvidence = { throw IllegalStateException("evidence_read_failed") },
            timeoutMs = 100L,
            nowMs = { testScheduler.currentTime }
        )
        return ClinicalInputInvalidationCoordinator(
            scope = this,
            ledger = ledger,
            ownershipStatusSource = statusSource,
            trailingDelayMs = TRAILING_DELAY_MS,
            retryDelayMs = RETRY_DELAY_MS,
            nowMs = { testScheduler.currentTime },
            tokenSource = { "new-token-${requests.size + 1}" },
            modeSource = { ClinicalInputInvalidationMode.NORMAL },
            sink = { request ->
                onDispatch()
                requests += request
                true
            }
        )
    }

    private fun kotlinx.coroutines.test.TestScope.coordinator(
        ledger: ClinicalInputInvalidationLedger,
        ownershipStatus: ClinicalInvalidationOwnershipStatus,
        requests: MutableList<ClinicalInputInvalidationDispatch>,
        onDispatch: () -> Unit = {}
    ) = ClinicalInputInvalidationCoordinator(
        scope = this,
        ledger = ledger,
        ownershipStatusSource = { ownershipStatus },
        trailingDelayMs = TRAILING_DELAY_MS,
        retryDelayMs = RETRY_DELAY_MS,
        nowMs = { testScheduler.currentTime },
        tokenSource = { "new-token-${requests.size + 1}" },
        modeSource = { ClinicalInputInvalidationMode.NORMAL },
        sink = { request ->
            onDispatch()
            requests += request
            true
        }
    )

    private fun inFlightState(attempt: Int) = ClinicalInputInvalidationLedgerState(
        latestGeneration = 1L,
        acknowledgedGeneration = 0L,
        inFlight = ClinicalInputInvalidationDispatch(
            generation = 1L,
            dispatchToken = "restored-token",
            mode = ClinicalInputInvalidationMode.NORMAL,
            sources = setOf(ClinicalInputInvalidationSource.BROADCAST_INGEST),
            attempt = attempt
        )
    )

    private class FakeLedger(
        var state: ClinicalInputInvalidationLedgerState
    ) : ClinicalInputInvalidationLedger {
        override suspend fun read(): ClinicalInputInvalidationLedgerState = state

        override suspend fun write(state: ClinicalInputInvalidationLedgerState) {
            this.state = state
        }
    }

    private suspend fun kotlinx.coroutines.test.TestScope.acceptedAckFailureFixture(): AcceptedAckFailureFixture {
        val ledger = FailingAckLedger(inFlightState(attempt = 0))
        val requests = mutableListOf<ClinicalInputInvalidationDispatch>()
        val cycleCount = AtomicInteger()
        val store = RecreatedEvidenceStore()
        val first = coordinator(
            ledger = ledger,
            ownershipStatus = ClinicalInvalidationOwnershipStatus.ACTIVE,
            requests = mutableListOf()
        )
        val work = ClinicalInvalidationWorkData(
            generation = 1L,
            dispatchToken = "restored-token",
            mode = ClinicalInputInvalidationMode.NORMAL,
            coordinatorAttempt = 0
        )
        first.hydrateAndRedrive()
        assertThat(
            runClinicalCycleWithExecutionEvidence(
                work,
                store
            ) {
                cycleCount.incrementAndGet()
                true
            }
        ).isTrue()
        assertThat(first.acknowledge(work.generation, work.dispatchToken)).isTrue()
        assertThat(ledger.ackAttempts).isEqualTo(2)
        assertThat(ledger.state.inFlight?.dispatchToken).isEqualTo(work.dispatchToken)

        val restored = ClinicalInputInvalidationCoordinator(
            scope = this,
            ledger = ledger,
            ownershipStatusSource = { ClinicalInvalidationOwnershipStatus.ACTIVE },
            trailingDelayMs = TRAILING_DELAY_MS,
            retryDelayMs = RETRY_DELAY_MS,
            nowMs = { testScheduler.currentTime },
            tokenSource = { "new-token-${requests.size + 1}" },
            modeSource = { ClinicalInputInvalidationMode.NORMAL },
            sink = { request ->
                requests += request
                cycleCount.incrementAndGet()
                true
            }
        )
        return AcceptedAckFailureFixture(work, restored, requests, cycleCount, store)
    }

    private data class AcceptedAckFailureFixture(
        val work: ClinicalInvalidationWorkData,
        val restored: ClinicalInputInvalidationCoordinator,
        val requests: MutableList<ClinicalInputInvalidationDispatch>,
        val cycleCount: AtomicInteger,
        val store: RecreatedEvidenceStore
    )

    private class RecreatedEvidenceStore : ClinicalInvalidationExecutionEvidenceStore {
        private var evidence: ClinicalInvalidationExecutionEvidence? = null
        var readBehavior: suspend () -> ClinicalInvalidationExecutionEvidence? = { evidence }

        override suspend fun read(): ClinicalInvalidationExecutionEvidence? = readBehavior()

        override suspend fun write(evidence: ClinicalInvalidationExecutionEvidence) {
            this.evidence = evidence
        }
    }

    private class FailingAckLedger(
        var state: ClinicalInputInvalidationLedgerState
    ) : ClinicalInputInvalidationLedger {
        var ackAttempts: Int = 0
            private set

        override suspend fun read(): ClinicalInputInvalidationLedgerState = state

        override suspend fun write(state: ClinicalInputInvalidationLedgerState) {
            val active = this.state.inFlight
            if (
                active != null &&
                state.inFlight == null &&
                state.acknowledgedGeneration >= active.generation
            ) {
                ackAttempts++
                throw IllegalStateException("ack_write_failed")
            }
            this.state = state
        }
    }

    private companion object {
        const val TRAILING_DELAY_MS = 1_000L
        const val RETRY_DELAY_MS = 250L
    }
}
