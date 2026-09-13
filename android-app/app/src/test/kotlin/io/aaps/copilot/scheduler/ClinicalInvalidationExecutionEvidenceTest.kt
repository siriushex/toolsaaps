package io.aaps.copilot.scheduler

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class ClinicalInvalidationExecutionEvidenceTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun acceptedCyclePersistsExactEvidenceBeforeAckSettlement() = runTest {
        val store = RecordingEvidenceStore()
        var cycleCount = 0

        val accepted = runClinicalCycleWithExecutionEvidence(WORK, store) {
            cycleCount++
            true
        }

        assertThat(accepted).isTrue()
        assertThat(cycleCount).isEqualTo(1)
        assertThat(store.writes.map { it.outcome }).containsExactly(
            ClinicalInvalidationExecutionOutcome.STARTED_UNKNOWN,
            ClinicalInvalidationExecutionOutcome.ACCEPTED
        ).inOrder()
        assertThat(store.writes.last().generation).isEqualTo(WORK.generation)
        assertThat(store.writes.last().dispatchToken).isEqualTo(WORK.dispatchToken)
    }

    @Test
    fun ordinaryFailureAfterCycleStartRemainsAmbiguousAndFailsClosed() = runTest {
        val store = RecordingEvidenceStore()

        val result = runCatching {
            runClinicalCycleWithExecutionEvidence(WORK, store) {
                throw IllegalStateException("not_accepted")
            }
        }

        assertThat(result.exceptionOrNull()).isInstanceOf(IllegalStateException::class.java)
        assertThat(store.writes.map { it.outcome })
            .containsExactly(ClinicalInvalidationExecutionOutcome.STARTED_UNKNOWN)
    }

    @Test
    fun explicitFalseCycleResultIsPersistedAsRedriveable() = runTest {
        val store = RecordingEvidenceStore()

        val accepted = runClinicalCycleWithExecutionEvidence(WORK, store) { false }

        assertThat(accepted).isFalse()
        assertThat(store.writes.map { it.outcome }).containsExactly(
            ClinicalInvalidationExecutionOutcome.STARTED_UNKNOWN,
            ClinicalInvalidationExecutionOutcome.UNACCEPTED
        ).inOrder()
    }

    @Test
    fun recoverableFatalPersistsTerminalEvidenceAndRethrowsSameError() = runTest {
        val store = RecordingEvidenceStore()
        val fatal = AssertionError("fatal_cycle")

        val result = runCatching {
            runClinicalCycleWithExecutionEvidence(WORK, store) { throw fatal }
        }

        assertThat(result.exceptionOrNull()).isSameInstanceAs(fatal)
        assertThat(store.writes.map { it.outcome }).containsExactly(
            ClinicalInvalidationExecutionOutcome.STARTED_UNKNOWN,
            ClinicalInvalidationExecutionOutcome.FATAL
        ).inOrder()
    }

    @Test
    fun cancellationLeavesRestartVisibleUnknownEvidenceAndPropagates() = runTest {
        val store = RecordingEvidenceStore()
        val cancellation = CancellationException("cycle_cancelled")
        val cycleState = ReactiveClinicalCycleState()

        val result = runCatching {
            runClinicalCycleWithExecutionEvidence(WORK, store, cycleState) { throw cancellation }
        }

        assertThat(result.exceptionOrNull()).isSameInstanceAs(cancellation)
        assertThat(cycleState.invocationStarted).isTrue()
        assertThat(cycleState.accepted).isNull()
        assertThat(store.writes.map { it.outcome })
            .containsExactly(ClinicalInvalidationExecutionOutcome.STARTED_UNKNOWN)
    }

    @Test
    fun failedInitialEvidenceWriteNeverMarksClinicalInvocationStarted() = runTest {
        val failure = IllegalStateException("initial_evidence_write_failed")
        val cycleState = ReactiveClinicalCycleState()
        var cycleCount = 0

        val result = runCatching {
            runClinicalCycleWithExecutionEvidence(
                reactiveWork = WORK,
                store = object : ClinicalInvalidationExecutionEvidenceStore {
                    override suspend fun read(): ClinicalInvalidationExecutionEvidence? = null
                    override suspend fun write(evidence: ClinicalInvalidationExecutionEvidence) {
                        throw failure
                    }
                },
                cycleState = cycleState
            ) {
                cycleCount++
                true
            }
        }

        assertThat(result.exceptionOrNull()).isInstanceOf(IllegalStateException::class.java)
        assertThat(cycleState.invocationStarted).isFalse()
        assertThat(cycleState.accepted).isNull()
        assertThat(cycleCount).isEqualTo(0)
    }

    @Test
    fun timeoutAfterCycleStartLeavesUnknownAndNextPreflightDoesNotRunCycle() = runTest {
        val store = RecordingEvidenceStore()
        var cycleCount = 0

        val result = runCatching {
            runClinicalCycleWithExecutionEvidence(WORK, store) {
                cycleCount++
                withTimeout(10L) { awaitCancellation() }
            }
        }
        val recovery = recoverReactiveWorkFromExecutionEvidence(
            reactiveWork = WORK,
            evidence = store.read(),
            acknowledge = { _, _ -> error("unexpected acknowledge") },
            terminateAmbiguous = { generation, token ->
                assertThat(generation).isEqualTo(WORK.generation)
                assertThat(token).isEqualTo(WORK.dispatchToken)
                true
            },
            terminateFatal = { _, _ -> error("unexpected fatal settlement") }
        )
        if (recovery == ReactiveExecutionEvidenceRecovery.NONE ||
            recovery == ReactiveExecutionEvidenceRecovery.REDRIVE_ALLOWED
        ) {
            cycleCount++
        }

        assertThat(result.exceptionOrNull()).isInstanceOf(
            kotlinx.coroutines.TimeoutCancellationException::class.java
        )
        assertThat(recovery)
            .isEqualTo(ReactiveExecutionEvidenceRecovery.AMBIGUOUSLY_TERMINATED)
        assertThat(cycleCount).isEqualTo(1)
    }

    @Test
    fun processFatalErrorIsNeverConvertedOrSwallowed() = runTest {
        val store = RecordingEvidenceStore()
        val fatal = TestVirtualMachineError()

        val result = runCatching {
            runClinicalCycleWithExecutionEvidence(WORK, store) { throw fatal }
        }

        assertThat(result.exceptionOrNull()).isSameInstanceAs(fatal)
        assertThat(store.writes.map { it.outcome })
            .containsExactly(ClinicalInvalidationExecutionOutcome.STARTED_UNKNOWN)
    }

    @Test
    fun exactAcceptedPreflightSettlesMetadataWithoutRunningCycle() = runTest {
        val evidence = WORK.executionEvidence(ClinicalInvalidationExecutionOutcome.ACCEPTED)
        var acknowledged = 0

        val result = recoverReactiveWorkFromExecutionEvidence(
            reactiveWork = WORK,
            evidence = evidence,
            acknowledge = { generation, token ->
                assertThat(generation).isEqualTo(WORK.generation)
                assertThat(token).isEqualTo(WORK.dispatchToken)
                acknowledged++
                true
            },
            terminateFatal = { _, _ -> error("unexpected fatal settlement") }
        )

        assertThat(result).isEqualTo(ReactiveExecutionEvidenceRecovery.ACKNOWLEDGED)
        assertThat(acknowledged).isEqualTo(1)
    }

    @Test
    fun exactStartedUnknownPreflightSettlesAmbiguousMetadataWithoutCycle() = runTest {
        var settlements = 0
        var cycleCount = 0

        val recovery = recoverReactiveWorkFromExecutionEvidence(
            reactiveWork = WORK,
            evidence = WORK.executionEvidence(
                ClinicalInvalidationExecutionOutcome.STARTED_UNKNOWN
            ),
            acknowledge = { _, _ -> settlements++; true },
            terminateAmbiguous = { generation, token ->
                assertThat(generation).isEqualTo(WORK.generation)
                assertThat(token).isEqualTo(WORK.dispatchToken)
                settlements++
                true
            },
            terminateFatal = { _, _ -> settlements++; true }
        )
        if (recovery == ReactiveExecutionEvidenceRecovery.NONE ||
            recovery == ReactiveExecutionEvidenceRecovery.REDRIVE_ALLOWED
        ) {
            cycleCount++
        }

        assertThat(recovery)
            .isEqualTo(ReactiveExecutionEvidenceRecovery.AMBIGUOUSLY_TERMINATED)
        assertThat(settlements).isEqualTo(1)
        assertThat(cycleCount).isEqualTo(0)
    }

    @Test
    fun timeoutAfterInvocationUsesExactAmbiguousSettlementAndNeverRetriesCycle() = runTest {
        val store = RecordingEvidenceStore()
        val cycleState = ReactiveClinicalCycleState()
        var cycles = 0
        val ambiguousSettlements = mutableListOf<Pair<Long, String>>()
        var ordinaryTerminations = 0

        val result = runCatching {
            runReactiveWorkerOrchestration(
                reactiveWork = WORK,
                runAttemptCount = 0,
                timeoutMs = 1_000L,
                prepare = {
                    PreparedReactiveWorkerCycle {
                        runClinicalCycleWithExecutionEvidence(WORK, store, cycleState) {
                            cycles++
                            withTimeout(10L) { awaitCancellation() }
                        }
                    }
                },
                onStarted = {},
                onCompleted = {},
                onFailure = {},
                acknowledge = { _, _ -> error("unexpected acknowledge") },
                terminate = { _, _ -> ordinaryTerminations++; true },
                terminateAmbiguous = { generation, token ->
                    ambiguousSettlements += generation to token
                    true
                },
                terminateFatal = { _, _ -> error("unexpected fatal settlement") },
                clinicalCycleState = cycleState
            )
        }

        assertThat(result.exceptionOrNull()).isInstanceOf(TimeoutCancellationException::class.java)
        assertThat(cycles).isEqualTo(1)
        assertThat(ambiguousSettlements).containsExactly(WORK.generation to WORK.dispatchToken)
        assertThat(ordinaryTerminations).isEqualTo(0)
    }

    @Test
    fun parentCancellationAfterInvocationSettlesAmbiguousNonCancellably() = runTest {
        val store = RecordingEvidenceStore()
        val cycleState = ReactiveClinicalCycleState()
        val cycleStarted = CompletableDeferred<Unit>()
        val ambiguousSettlements = mutableListOf<Pair<Long, String>>()
        var ordinaryTerminations = 0
        var cycles = 0

        val job = launch {
            runReactiveWorkerOrchestration(
                reactiveWork = WORK,
                runAttemptCount = 0,
                timeoutMs = 10_000L,
                prepare = {
                    PreparedReactiveWorkerCycle {
                        runClinicalCycleWithExecutionEvidence(WORK, store, cycleState) {
                            cycles++
                            cycleStarted.complete(Unit)
                            awaitCancellation()
                        }
                    }
                },
                onStarted = {},
                onCompleted = {},
                onFailure = {},
                acknowledge = { _, _ -> error("unexpected acknowledge") },
                terminate = { _, _ -> ordinaryTerminations++; true },
                terminateAmbiguous = { generation, token ->
                    ambiguousSettlements += generation to token
                    true
                },
                terminateFatal = { _, _ -> error("unexpected fatal settlement") },
                clinicalCycleState = cycleState
            )
        }

        runCurrent()
        assertThat(cycleStarted.isCompleted).isTrue()
        job.cancelAndJoin()

        assertThat(job.isCancelled).isTrue()
        assertThat(cycles).isEqualTo(1)
        assertThat(ambiguousSettlements).containsExactly(WORK.generation to WORK.dispatchToken)
        assertThat(ordinaryTerminations).isEqualTo(0)
    }

    @Test
    fun ordinaryFailureAfterInvocationSettlesAmbiguousAndReturnsTerminalFailure() = runTest {
        val store = RecordingEvidenceStore()
        val cycleState = ReactiveClinicalCycleState()
        val failure = IllegalStateException("cycle_result_unknown")
        val ambiguousSettlements = mutableListOf<Pair<Long, String>>()
        val failures = mutableListOf<Throwable>()
        var cycles = 0

        val result = runReactiveWorkerOrchestration(
            reactiveWork = WORK,
            runAttemptCount = 0,
            timeoutMs = 1_000L,
            prepare = {
                PreparedReactiveWorkerCycle {
                    runClinicalCycleWithExecutionEvidence(WORK, store, cycleState) {
                        cycles++
                        throw failure
                    }
                }
            },
            onStarted = {},
            onCompleted = {},
            onFailure = { failures += it },
            acknowledge = { _, _ -> error("unexpected acknowledge") },
            terminate = { _, _ -> error("unexpected ordinary termination") },
            terminateAmbiguous = { generation, token ->
                ambiguousSettlements += generation to token
                true
            },
            terminateFatal = { _, _ -> error("unexpected fatal settlement") },
            clinicalCycleState = cycleState
        )

        assertThat(result).isEqualTo(ReactiveWorkerOrchestrationResult.FAILURE)
        assertThat(cycles).isEqualTo(1)
        assertThat(ambiguousSettlements).containsExactly(WORK.generation to WORK.dispatchToken)
        assertThat(failures).hasSize(1)
    }

    @Test
    fun exactUnacceptedEvidenceIsExplicitlyRedriveable() = runTest {
        val recovery = recoverReactiveWorkFromExecutionEvidence(
            reactiveWork = WORK,
            evidence = WORK.executionEvidence(ClinicalInvalidationExecutionOutcome.UNACCEPTED),
            acknowledge = { _, _ -> error("unexpected acknowledge") },
            terminateFatal = { _, _ -> error("unexpected fatal settlement") }
        )

        assertThat(recovery).isEqualTo(ReactiveExecutionEvidenceRecovery.REDRIVE_ALLOWED)
    }

    @Test
    fun acceptedCycleWithPermanentAckFailureSettlesMetadataOnlyAfterRecreation() = runTest {
        val ledger = SettlementFailingLedger(inFlightState(), failAck = true)
        val store = InMemoryClinicalInvalidationExecutionEvidenceStore()
        var cycleCount = 0
        val first = coordinator(ledger, ClinicalInvalidationOwnershipStatus.ACTIVE) {
            cycleCount++
        }
        first.hydrateAndRedrive()

        assertThat(runClinicalCycleWithExecutionEvidence(WORK, store) {
            cycleCount++
            true
        }).isTrue()
        assertThat(first.acknowledge(WORK.generation, WORK.dispatchToken)).isTrue()
        assertThat(ledger.ackAttempts).isEqualTo(2)
        assertThat(ledger.state.inFlight?.dispatchToken).isEqualTo(WORK.dispatchToken)

        ledger.failAck = false
        val restored = coordinator(
            ledger,
            terminalStatus(store, ClinicalInvalidationExecutionOutcome.ACCEPTED)
        ) { cycleCount++ }
        restored.hydrateAndRedrive()
        advanceTimeBy(100L)
        runCurrent()

        assertThat(cycleCount).isEqualTo(1)
        assertThat(ledger.state.acknowledgedGeneration).isEqualTo(WORK.generation)
        assertThat(ledger.state.inFlight).isNull()
    }

    @Test
    fun acceptedEvidenceAfterAckCancellationEquivalentNeverRerunsAfterRecreation() = runTest {
        val ledger = SettlementFailingLedger(inFlightState())
        val store = InMemoryClinicalInvalidationExecutionEvidenceStore()
        var cycleCount = 0

        runClinicalCycleWithExecutionEvidence(WORK, store) {
            cycleCount++
            true
        }

        val restored = coordinator(
            ledger,
            terminalStatus(store, ClinicalInvalidationExecutionOutcome.ACCEPTED)
        ) { cycleCount++ }
        restored.hydrateAndRedrive()
        advanceTimeBy(100L)
        runCurrent()

        assertThat(cycleCount).isEqualTo(1)
        assertThat(ledger.state.acknowledgedGeneration).isEqualTo(WORK.generation)
        assertThat(ledger.state.inFlight).isNull()
    }

    @Test
    fun cancellationWritingAcceptedEvidenceUsesExactAckCleanupWithoutNewClinicalToken() = runTest {
        val cancellation = CancellationException("accepted_evidence_cancelled")
        val store = CancellingTerminalEvidenceStore(cancellation)
        val cycleState = ReactiveClinicalCycleState()
        var cycleCount = 0
        var acknowledgements = 0
        var terminations = 0

        val result = runCatching {
            runReactiveWorkerOrchestration(
                reactiveWork = WORK,
                runAttemptCount = 0,
                timeoutMs = 1_000L,
                prepare = {
                    PreparedReactiveWorkerCycle {
                        runClinicalCycleWithExecutionEvidence(WORK, store, cycleState) {
                            cycleCount++
                            true
                        }
                    }
                },
                onStarted = {},
                onCompleted = {},
                onFailure = {},
                acknowledge = { _, _ -> acknowledgements++; true },
                terminate = { _, _ -> terminations++; true },
                terminateFatal = { _, _ -> error("unexpected fatal settlement") },
                clinicalCycleState = cycleState
            )
        }

        val propagated = result.exceptionOrNull()
        assertThat(propagated).isInstanceOf(CancellationException::class.java)
        assertThat(generateSequence(propagated) { it.cause }.any { it === cancellation }).isTrue()
        assertThat(cycleCount).isEqualTo(1)
        assertThat(acknowledgements).isEqualTo(1)
        assertThat(terminations).isEqualTo(0)
        assertThat(store.persisted?.outcome)
            .isEqualTo(ClinicalInvalidationExecutionOutcome.STARTED_UNKNOWN)
    }

    @Test
    fun timeoutWritingAcceptedEvidenceAcksExactlyAndNeverRetriesClinicalCycle() = runTest {
        val store = FailingTerminalEvidenceStore {
            withTimeout(1L) { awaitCancellation() }
        }
        val cycleState = ReactiveClinicalCycleState()
        var cycleCount = 0
        var acknowledgements = 0
        var terminations = 0

        val result = runCatching {
            runReactiveWorkerOrchestration(
                reactiveWork = WORK,
                runAttemptCount = 0,
                timeoutMs = 1_000L,
                prepare = {
                    PreparedReactiveWorkerCycle {
                        runClinicalCycleWithExecutionEvidence(WORK, store, cycleState) {
                            cycleCount++
                            true
                        }
                    }
                },
                onStarted = {},
                onCompleted = {},
                onFailure = {},
                acknowledge = { _, _ -> acknowledgements++; true },
                terminate = { _, _ -> terminations++; true },
                terminateFatal = { _, _ -> error("unexpected fatal settlement") },
                clinicalCycleState = cycleState
            )
        }

        assertThat(result.exceptionOrNull()).isInstanceOf(TimeoutCancellationException::class.java)
        assertThat(cycleCount).isEqualTo(1)
        assertThat(acknowledgements).isEqualTo(1)
        assertThat(terminations).isEqualTo(0)
        assertThat(store.persisted?.outcome)
            .isEqualTo(ClinicalInvalidationExecutionOutcome.STARTED_UNKNOWN)
    }

    @Test
    fun ordinaryFailureWritingAcceptedEvidenceAcksAndReturnsDiagnosticSuccess() = runTest {
        val failure = IllegalStateException("accepted_evidence_write_failed")
        val store = FailingTerminalEvidenceStore { throw failure }
        val cycleState = ReactiveClinicalCycleState()
        var cycleCount = 0
        var acknowledgements = 0
        var terminations = 0
        val failures = mutableListOf<Throwable>()

        val result = runReactiveWorkerOrchestration(
            reactiveWork = WORK,
            runAttemptCount = 0,
            timeoutMs = 1_000L,
            prepare = {
                PreparedReactiveWorkerCycle {
                    runClinicalCycleWithExecutionEvidence(WORK, store, cycleState) {
                        cycleCount++
                        true
                    }
                }
            },
            onStarted = {},
            onCompleted = {},
            onFailure = { failures += it },
            acknowledge = { _, _ -> acknowledgements++; true },
            terminate = { _, _ -> terminations++; true },
            terminateFatal = { _, _ -> error("unexpected fatal settlement") },
            clinicalCycleState = cycleState
        )

        assertThat(result).isEqualTo(ReactiveWorkerOrchestrationResult.SUCCESS)
        assertThat(cycleCount).isEqualTo(1)
        assertThat(acknowledgements).isEqualTo(1)
        assertThat(terminations).isEqualTo(0)
        assertThat(failures).hasSize(1)
        assertThat(failures.single()).isInstanceOf(IllegalStateException::class.java)
        assertThat(failures.single()).hasMessageThat().isEqualTo(failure.message)
        assertThat(generateSequence(failures.single()) { it.cause }.any { it === failure }).isTrue()
        assertThat(store.persisted?.outcome)
            .isEqualTo(ClinicalInvalidationExecutionOutcome.STARTED_UNKNOWN)
    }

    @Test
    fun recoverableErrorWritingAcceptedEvidenceAcksWithoutFatalRelabelAndRethrows() = runTest {
        val failure = AssertionError("accepted_evidence_write_error")
        val store = FailingTerminalEvidenceStore { throw failure }
        val cycleState = ReactiveClinicalCycleState()
        var cycleCount = 0
        var acknowledgements = 0
        var terminations = 0
        var fatalTerminations = 0
        var fatalEvidenceWrites = 0

        val result = runCatching {
            runReactiveWorkerOrchestration(
                reactiveWork = WORK,
                runAttemptCount = 0,
                timeoutMs = 1_000L,
                prepare = {
                    PreparedReactiveWorkerCycle {
                        runClinicalCycleWithExecutionEvidence(WORK, store, cycleState) {
                            cycleCount++
                            true
                        }
                    }
                },
                onStarted = {},
                onCompleted = {},
                onFailure = {},
                acknowledge = { _, _ -> acknowledgements++; true },
                terminate = { _, _ -> terminations++; true },
                terminateFatal = { _, _ -> fatalTerminations++; true },
                beforeFatalSettlement = { fatalEvidenceWrites++ },
                clinicalCycleState = cycleState
            )
        }

        val propagated = result.exceptionOrNull()
        assertThat(propagated).isInstanceOf(AssertionError::class.java)
        assertThat(propagated).hasMessageThat().isEqualTo(failure.message)
        assertThat(generateSequence(propagated) { it.cause }.any { it === failure }).isTrue()
        assertThat(cycleCount).isEqualTo(1)
        assertThat(acknowledgements).isEqualTo(1)
        assertThat(terminations).isEqualTo(0)
        assertThat(fatalTerminations).isEqualTo(0)
        assertThat(fatalEvidenceWrites).isEqualTo(0)
        assertThat(store.persisted?.outcome)
            .isEqualTo(ClinicalInvalidationExecutionOutcome.STARTED_UNKNOWN)
    }

    @Test
    fun ordinaryFailureWritingExplicitUnacceptedEvidenceTerminatesForCoordinatorRedrive() = runTest {
        val failure = IllegalStateException("unaccepted_evidence_write_failed")
        val store = FailingTerminalEvidenceStore { throw failure }
        val cycleState = ReactiveClinicalCycleState()
        var cycleCount = 0
        var acknowledgements = 0
        var terminations = 0
        val failures = mutableListOf<Throwable>()

        val result = runReactiveWorkerOrchestration(
            reactiveWork = WORK,
            runAttemptCount = 0,
            timeoutMs = 1_000L,
            prepare = {
                PreparedReactiveWorkerCycle {
                    runClinicalCycleWithExecutionEvidence(WORK, store, cycleState) {
                        cycleCount++
                        false
                    }
                }
            },
            onStarted = {},
            onCompleted = {},
            onFailure = { failures += it },
            acknowledge = { _, _ -> acknowledgements++; true },
            terminate = { _, _ -> terminations++; true },
            terminateFatal = { _, _ -> error("unexpected fatal settlement") },
            clinicalCycleState = cycleState
        )

        assertThat(result).isEqualTo(ReactiveWorkerOrchestrationResult.FAILURE)
        assertThat(cycleCount).isEqualTo(1)
        assertThat(acknowledgements).isEqualTo(0)
        assertThat(terminations).isEqualTo(1)
        assertThat(failures).hasSize(1)
        assertThat(store.persisted?.outcome)
            .isEqualTo(ClinicalInvalidationExecutionOutcome.STARTED_UNKNOWN)
    }

    @Test
    fun timeoutWritingExplicitUnacceptedEvidenceTerminatesThenPropagates() = runTest {
        val store = FailingTerminalEvidenceStore {
            withTimeout(1L) { awaitCancellation() }
        }
        val cycleState = ReactiveClinicalCycleState()
        var cycleCount = 0
        var terminations = 0

        val result = runCatching {
            runReactiveWorkerOrchestration(
                reactiveWork = WORK,
                runAttemptCount = 0,
                timeoutMs = 1_000L,
                prepare = {
                    PreparedReactiveWorkerCycle {
                        runClinicalCycleWithExecutionEvidence(WORK, store, cycleState) {
                            cycleCount++
                            false
                        }
                    }
                },
                onStarted = {},
                onCompleted = {},
                onFailure = {},
                acknowledge = { _, _ -> error("unexpected acknowledge") },
                terminate = { generation, token ->
                    assertThat(generation).isEqualTo(WORK.generation)
                    assertThat(token).isEqualTo(WORK.dispatchToken)
                    terminations++
                    true
                },
                terminateAmbiguous = { _, _ -> error("unexpected ambiguous settlement") },
                terminateFatal = { _, _ -> error("unexpected fatal settlement") },
                clinicalCycleState = cycleState
            )
        }

        assertThat(result.exceptionOrNull()).isInstanceOf(TimeoutCancellationException::class.java)
        assertThat(cycleCount).isEqualTo(1)
        assertThat(terminations).isEqualTo(1)
    }

    @Test
    fun cancellationWritingExplicitUnacceptedEvidenceTerminatesThenPropagates() = runTest {
        val cancellation = CancellationException("unaccepted_evidence_cancelled")
        val store = FailingTerminalEvidenceStore { throw cancellation }
        val cycleState = ReactiveClinicalCycleState()
        var terminations = 0

        val result = runCatching {
            runReactiveWorkerOrchestration(
                reactiveWork = WORK,
                runAttemptCount = 0,
                timeoutMs = 1_000L,
                prepare = {
                    PreparedReactiveWorkerCycle {
                        runClinicalCycleWithExecutionEvidence(WORK, store, cycleState) { false }
                    }
                },
                onStarted = {},
                onCompleted = {},
                onFailure = {},
                acknowledge = { _, _ -> error("unexpected acknowledge") },
                terminate = { _, _ -> terminations++; true },
                terminateAmbiguous = { _, _ -> error("unexpected ambiguous settlement") },
                terminateFatal = { _, _ -> error("unexpected fatal settlement") },
                clinicalCycleState = cycleState
            )
        }

        assertThat(result.exceptionOrNull()).isSameInstanceAs(cancellation)
        assertThat(terminations).isEqualTo(1)
    }

    @Test
    fun recoverableErrorWritingExplicitUnacceptedEvidenceTerminatesWithoutFatalRelabel() = runTest {
        val error = AssertionError("unaccepted_evidence_error")
        val store = FailingTerminalEvidenceStore { throw error }
        val cycleState = ReactiveClinicalCycleState()
        var terminations = 0
        var fatalTerminations = 0

        val result = runCatching {
            runReactiveWorkerOrchestration(
                reactiveWork = WORK,
                runAttemptCount = 0,
                timeoutMs = 1_000L,
                prepare = {
                    PreparedReactiveWorkerCycle {
                        runClinicalCycleWithExecutionEvidence(WORK, store, cycleState) { false }
                    }
                },
                onStarted = {},
                onCompleted = {},
                onFailure = {},
                acknowledge = { _, _ -> error("unexpected acknowledge") },
                terminate = { _, _ -> terminations++; true },
                terminateAmbiguous = { _, _ -> error("unexpected ambiguous settlement") },
                terminateFatal = { _, _ -> fatalTerminations++; true },
                clinicalCycleState = cycleState
            )
        }

        assertThat(result.exceptionOrNull()).isSameInstanceAs(error)
        assertThat(terminations).isEqualTo(1)
        assertThat(fatalTerminations).isEqualTo(0)
    }

    @Test
    fun fatalCycleWithFailedFatalSettlementNeverRedrivesAfterRecreation() = runTest {
        val ledger = SettlementFailingLedger(inFlightState(), failFatal = true)
        val store = InMemoryClinicalInvalidationExecutionEvidenceStore()
        var cycleCount = 0
        val fatal = AssertionError("fatal_cycle")

        val cycleResult = runCatching {
            runClinicalCycleWithExecutionEvidence(WORK, store) {
                cycleCount++
                throw fatal
            }
        }
        assertThat(cycleResult.exceptionOrNull()).isSameInstanceAs(fatal)
        assertThat(firstFatalSettlement(ledger)).isTrue()
        assertThat(ledger.fatalAttempts).isEqualTo(2)
        assertThat(ledger.state.inFlight?.dispatchToken).isEqualTo(WORK.dispatchToken)

        ledger.failFatal = false
        val restored = coordinator(
            ledger,
            terminalStatus(store, ClinicalInvalidationExecutionOutcome.FATAL)
        ) { cycleCount++ }
        restored.hydrateAndRedrive()
        advanceTimeBy(100L)
        runCurrent()

        assertThat(cycleCount).isEqualTo(1)
        assertThat(ledger.state.inFlight).isNull()
        assertThat(ledger.state.pending?.attempt)
            .isEqualTo(MAX_CLINICAL_INVALIDATION_PERSISTED_ATTEMPT)
    }

    @Test
    fun preferencesDataStoreRoundTripsExactExecutionEvidence() = runTest {
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        val dataStore = PreferenceDataStoreFactory.create(scope = scope) {
            temporaryFolder.newFile("execution-evidence.preferences_pb")
        }
        val store = DataStoreClinicalInvalidationExecutionEvidenceStore(dataStore)
        val expected = WORK.executionEvidence(ClinicalInvalidationExecutionOutcome.ACCEPTED)

        store.write(expected)

        assertThat(store.read()).isEqualTo(expected)
        scope.cancel()
    }

    @Test
    fun malformedDurableEvidenceWithNoActiveWorkIsTerminalAmbiguousAndNeverRedrives() = runTest {
        val malformedWriters: List<suspend (androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences>) -> Unit> =
            listOf(
                { dataStore ->
                    dataStore.edit { it[longPreferencesKey("generation")] = WORK.generation }
                },
                { dataStore ->
                    dataStore.edit {
                        it[longPreferencesKey("generation")] = WORK.generation
                        it[stringPreferencesKey("dispatch_token")] = WORK.dispatchToken
                        it[stringPreferencesKey("outcome")] = "NOT_A_REAL_OUTCOME"
                    }
                },
                { dataStore ->
                    dataStore.edit {
                        it[longPreferencesKey("generation")] = 0L
                        it[stringPreferencesKey("dispatch_token")] = WORK.dispatchToken
                        it[stringPreferencesKey("outcome")] =
                            ClinicalInvalidationExecutionOutcome.ACCEPTED.name
                    }
                }
            )
        var cycleCount = 0

        malformedWriters.forEachIndexed { index, writeMalformed ->
            val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
            val dataStore = PreferenceDataStoreFactory.create(scope = scope) {
                temporaryFolder.newFile("malformed-evidence-$index.preferences_pb")
            }
            writeMalformed(dataStore)
            val store = DataStoreClinicalInvalidationExecutionEvidenceStore(dataStore)
            val status = WorkManagerClinicalInvalidationOwnershipStatusSource(
                queryWorkInfos = { emptyList() },
                queryEvidence = { store.read() },
                timeoutMs = 100L
            ).status(inFlightState().inFlight!!)

            if (status == ClinicalInvalidationOwnershipStatus.ABSENT_OR_TERMINAL) {
                cycleCount++
            }
            assertThat(status)
                .isEqualTo(ClinicalInvalidationOwnershipStatus.AMBIGUOUS_TERMINAL)
            scope.cancel()
        }

        assertThat(cycleCount).isEqualTo(0)
    }

    @Test
    fun tokenMismatchCannotSettleCurrentDispatch() = runTest {
        var settlements = 0
        val mismatched = WORK.executionEvidence(ClinicalInvalidationExecutionOutcome.ACCEPTED)
            .copy(dispatchToken = "stale-token")

        val result = recoverReactiveWorkFromExecutionEvidence(
            reactiveWork = WORK,
            evidence = mismatched,
            acknowledge = { _, _ -> settlements++; true },
            terminateFatal = { _, _ -> settlements++; true }
        )

        assertThat(result).isEqualTo(ReactiveExecutionEvidenceRecovery.NONE)
        assertThat(settlements).isEqualTo(0)
    }

    @Test
    fun exactAcceptedEvidenceWithMissingLedgerOwnerFailsClosedWithoutFalseAckOrCycle() = runTest {
        var cycleCount = 0

        val recovery = recoverReactiveWorkFromExecutionEvidence(
            reactiveWork = WORK,
            evidence = WORK.executionEvidence(ClinicalInvalidationExecutionOutcome.ACCEPTED),
            acknowledge = { _, _ -> false },
            terminateFatal = { _, _ -> error("unexpected fatal settlement") }
        )
        if (recovery == ReactiveExecutionEvidenceRecovery.NONE ||
            recovery == ReactiveExecutionEvidenceRecovery.REDRIVE_ALLOWED
        ) {
            cycleCount++
        }

        assertThat(recovery)
            .isEqualTo(ReactiveExecutionEvidenceRecovery.FAIL_CLOSED_STALE_OWNER)
        assertThat(cycleCount).isEqualTo(0)
    }

    @Test
    fun exactFatalEvidenceWithMissingLedgerOwnerFailsClosedWithoutFalseSettlement() = runTest {
        val recovery = recoverReactiveWorkFromExecutionEvidence(
            reactiveWork = WORK,
            evidence = WORK.executionEvidence(ClinicalInvalidationExecutionOutcome.FATAL),
            acknowledge = { _, _ -> error("unexpected acknowledge") },
            terminateFatal = { _, _ -> false }
        )

        assertThat(recovery)
            .isEqualTo(ReactiveExecutionEvidenceRecovery.FAIL_CLOSED_STALE_OWNER)
    }

    private class RecordingEvidenceStore : ClinicalInvalidationExecutionEvidenceStore {
        val writes = mutableListOf<ClinicalInvalidationExecutionEvidence>()

        override suspend fun read(): ClinicalInvalidationExecutionEvidence? = writes.lastOrNull()

        override suspend fun write(evidence: ClinicalInvalidationExecutionEvidence) {
            writes += evidence
        }
    }

    private class CancellingTerminalEvidenceStore(
        private val cancellation: CancellationException
    ) : ClinicalInvalidationExecutionEvidenceStore {
        var persisted: ClinicalInvalidationExecutionEvidence? = null

        override suspend fun read(): ClinicalInvalidationExecutionEvidence? = persisted

        override suspend fun write(evidence: ClinicalInvalidationExecutionEvidence) {
            if (persisted == null) {
                persisted = evidence
            } else {
                throw cancellation
            }
        }
    }

    private class FailingTerminalEvidenceStore(
        private val failTerminalWrite: suspend () -> Nothing
    ) : ClinicalInvalidationExecutionEvidenceStore {
        var persisted: ClinicalInvalidationExecutionEvidence? = null

        override suspend fun read(): ClinicalInvalidationExecutionEvidence? = persisted

        override suspend fun write(evidence: ClinicalInvalidationExecutionEvidence) {
            if (persisted == null) {
                persisted = evidence
            } else {
                failTerminalWrite()
            }
        }
    }

    private class TestVirtualMachineError : VirtualMachineError("process_fatal")

    private suspend fun terminalStatus(
        store: ClinicalInvalidationExecutionEvidenceStore,
        outcome: ClinicalInvalidationExecutionOutcome
    ): ClinicalInvalidationOwnershipStatus {
        val evidence = checkNotNull(store.read())
        assertThat(evidence.outcome).isEqualTo(outcome)
        return WorkManagerClinicalInvalidationOwnershipStatusSource(
            queryWorkInfos = {
                listOf(
                    ClinicalInvalidationWorkInfo(
                        androidx.work.WorkInfo.State.SUCCEEDED,
                        evidence.toWorkData()
                    )
                )
            },
            queryEvidence = { store.read() },
            timeoutMs = 100L
        ).status(inFlightState().inFlight!!)
    }

    private fun kotlinx.coroutines.test.TestScope.coordinator(
        ledger: ClinicalInputInvalidationLedger,
        status: ClinicalInvalidationOwnershipStatus,
        onClinicalDispatch: () -> Unit
    ) = ClinicalInputInvalidationCoordinator(
        scope = this,
        ledger = ledger,
        ownershipStatusSource = { status },
        trailingDelayMs = 10L,
        retryDelayMs = 10L,
        nowMs = { testScheduler.currentTime },
        tokenSource = { "unexpected-redrive-token" },
        modeSource = { ClinicalInputInvalidationMode.NORMAL },
        sink = {
            onClinicalDispatch()
            true
        }
    )

    private suspend fun kotlinx.coroutines.test.TestScope.firstFatalSettlement(
        ledger: SettlementFailingLedger
    ): Boolean {
        val coordinator = coordinator(
            ledger,
            ClinicalInvalidationOwnershipStatus.ACTIVE
        ) { error("unexpected clinical dispatch") }
        coordinator.hydrateAndRedrive()
        return coordinator.dispatchFatal(WORK.generation, WORK.dispatchToken)
    }

    private class SettlementFailingLedger(
        initial: ClinicalInputInvalidationLedgerState,
        var failAck: Boolean = false,
        var failFatal: Boolean = false
    ) : ClinicalInputInvalidationLedger {
        var state = initial
            private set
        var ackAttempts = 0
            private set
        var fatalAttempts = 0
            private set

        override suspend fun read(): ClinicalInputInvalidationLedgerState = state

        override suspend fun write(state: ClinicalInputInvalidationLedgerState) {
            val active = this.state.inFlight
            if (active != null && state.inFlight == null) {
                if (state.acknowledgedGeneration >= active.generation) {
                    ackAttempts++
                    if (failAck) throw IllegalStateException("ack_write_failed")
                } else if (state.pending?.attempt == MAX_CLINICAL_INVALIDATION_PERSISTED_ATTEMPT) {
                    fatalAttempts++
                    if (failFatal) throw IllegalStateException("fatal_write_failed")
                }
            }
            this.state = state
        }
    }

    private companion object {
        val WORK = ClinicalInvalidationWorkData(
            generation = 31L,
            dispatchToken = "execution-token",
            mode = ClinicalInputInvalidationMode.NORMAL,
            coordinatorAttempt = 0
        )

        fun inFlightState() = ClinicalInputInvalidationLedgerState(
            latestGeneration = WORK.generation,
            inFlight = ClinicalInputInvalidationDispatch(
                generation = WORK.generation,
                dispatchToken = WORK.dispatchToken,
                mode = WORK.mode,
                sources = setOf(ClinicalInputInvalidationSource.LOCAL_ACTIVITY),
                attempt = WORK.coordinatorAttempt
            )
        )
    }
}
