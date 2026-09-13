package io.aaps.copilot.scheduler

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ReactiveWorkerOrchestrationTest {

    @Test
    fun settingsFailureOnFirstWorkerAttemptRetriesWithoutStartingCycleOrSettlingOwnership() = runTest {
        val fixture = fixture()

        val result = runReactiveWorkerOrchestration(
            reactiveWork = WORK,
            runAttemptCount = 0,
            timeoutMs = 1_000L,
            prepare = {
                fixture.settingsReads += 1
                throw IllegalStateException("settings_failed")
            },
            onStarted = { fixture.startAudits += 1 },
            onCompleted = fixture::completed,
            onFailure = fixture::failed,
            acknowledge = fixture::acknowledge,
            terminate = fixture::terminate,
            terminateFatal = fixture::terminateFatal
        )

        assertThat(result).isEqualTo(ReactiveWorkerOrchestrationResult.RETRY)
        assertThat(fixture.settingsReads).isEqualTo(1)
        assertThat(fixture.cycleStarts).isEqualTo(0)
        assertThat(fixture.acknowledged).isEmpty()
        assertThat(fixture.terminated).isEmpty()
        assertThat(fixture.fatallyTerminated).isEmpty()
        assertThat(fixture.failures).hasSize(1)
    }

    @Test
    fun startAuditFailureOnTerminalWorkerAttemptTerminatesExactOwnershipOnceWithoutCycle() = runTest {
        val fixture = fixture()

        val result = runReactiveWorkerOrchestration(
            reactiveWork = WORK,
            runAttemptCount = 2,
            timeoutMs = 1_000L,
            prepare = {
                fixture.settingsReads += 1
                PreparedReactiveWorkerCycle {
                    fixture.cycleStarts += 1
                    true
                }
            },
            onStarted = {
                fixture.startAudits += 1
                throw IllegalStateException("start_audit_failed")
            },
            onCompleted = fixture::completed,
            onFailure = fixture::failed,
            acknowledge = fixture::acknowledge,
            terminate = fixture::terminate,
            terminateFatal = fixture::terminateFatal
        )

        assertThat(result).isEqualTo(ReactiveWorkerOrchestrationResult.FAILURE)
        assertThat(fixture.settingsReads).isEqualTo(1)
        assertThat(fixture.startAudits).isEqualTo(1)
        assertThat(fixture.cycleStarts).isEqualTo(0)
        assertThat(fixture.terminated).containsExactly(WORK.generation to WORK.dispatchToken)
        assertThat(fixture.acknowledged).isEmpty()
    }

    @Test
    fun timeoutBeforeCycleUsesBoundedWorkerRetryAndRetainsExactOwnership() = runTest {
        val fixture = fixture()

        val result = runReactiveWorkerOrchestration(
            reactiveWork = WORK,
            runAttemptCount = 0,
            timeoutMs = 100L,
            prepare = { awaitCancellation() },
            onStarted = { fixture.startAudits += 1 },
            onCompleted = fixture::completed,
            onFailure = fixture::failed,
            acknowledge = fixture::acknowledge,
            terminate = fixture::terminate,
            terminateFatal = fixture::terminateFatal
        )

        assertThat(result).isEqualTo(ReactiveWorkerOrchestrationResult.RETRY)
        assertThat(fixture.cycleStarts).isEqualTo(0)
        assertThat(fixture.terminated).isEmpty()
        assertThat(fixture.failures).hasSize(1)
    }

    @Test
    fun actualCancellationBeforeCycleTerminatesExactOwnershipOnceAndPropagates() = runTest {
        val fixture = fixture()
        val setupStarted = CompletableDeferred<Unit>()
        val job = launch {
            runReactiveWorkerOrchestration(
                reactiveWork = WORK,
                runAttemptCount = 0,
                timeoutMs = 10_000L,
                prepare = {
                    setupStarted.complete(Unit)
                    awaitCancellation()
                },
                onStarted = { fixture.startAudits += 1 },
                onCompleted = fixture::completed,
                onFailure = fixture::failed,
                acknowledge = fixture::acknowledge,
                terminate = fixture::terminate,
                terminateFatal = fixture::terminateFatal
            )
        }

        runCurrent()
        assertThat(setupStarted.isCompleted).isTrue()
        job.cancelAndJoin()

        assertThat(job.isCancelled).isTrue()
        assertThat(fixture.cycleStarts).isEqualTo(0)
        assertThat(fixture.terminated).containsExactly(WORK.generation to WORK.dispatchToken)
        assertThat(fixture.failures).isEmpty()
    }

    @Test
    fun cancellationDuringAcceptedSettlementFinishesAckAndNeverDowngradesToRedrive() = runTest {
        val fixture = fixture()
        val acknowledgeStarted = CompletableDeferred<Unit>()
        var acknowledgeCalls = 0
        val job = launch {
            runReactiveWorkerOrchestration(
                reactiveWork = WORK,
                runAttemptCount = 0,
                timeoutMs = 1_000L,
                prepare = {
                    PreparedReactiveWorkerCycle {
                        fixture.cycleStarts += 1
                        true
                    }
                },
                onStarted = { fixture.startAudits += 1 },
                onCompleted = fixture::completed,
                onFailure = fixture::failed,
                acknowledge = { generation, token ->
                    acknowledgeCalls++
                    if (acknowledgeCalls == 1) {
                        acknowledgeStarted.complete(Unit)
                        awaitCancellation()
                    }
                    fixture.acknowledge(generation, token)
                },
                terminate = fixture::terminate,
                terminateFatal = fixture::terminateFatal
            )
        }

        runCurrent()
        assertThat(acknowledgeStarted.isCompleted).isTrue()
        job.cancelAndJoin()

        assertThat(job.isCancelled).isTrue()
        assertThat(fixture.cycleStarts).isEqualTo(1)
        assertThat(acknowledgeCalls).isEqualTo(2)
        assertThat(fixture.acknowledged).containsExactly(WORK.generation to WORK.dispatchToken)
        assertThat(fixture.terminated).isEmpty()
    }

    @Test
    fun fatalStartFailureTerminatesWithoutRedispatchAndRethrowsSameError() = runTest {
        val fixture = fixture()
        val fatal = FatalStartError(Any())

        val result = runCatching {
            runReactiveWorkerOrchestration(
                reactiveWork = WORK,
                runAttemptCount = 0,
                timeoutMs = 1_000L,
                prepare = {
                    PreparedReactiveWorkerCycle {
                        fixture.cycleStarts += 1
                        true
                    }
                },
                onStarted = {
                    fixture.startAudits += 1
                    throw fatal
                },
                onCompleted = fixture::completed,
                onFailure = fixture::failed,
                acknowledge = fixture::acknowledge,
                terminate = fixture::terminate,
                terminateFatal = fixture::terminateFatal
            )
        }

        assertThat(result.exceptionOrNull()).isSameInstanceAs(fatal)
        assertThat(fixture.cycleStarts).isEqualTo(0)
        assertThat(fixture.terminated).isEmpty()
        assertThat(fixture.fatallyTerminated)
            .containsExactly(WORK.generation to WORK.dispatchToken)
        assertThat(fixture.failures).isEmpty()
    }

    @Test
    fun processFatalDuringExactFatalCleanupOverridesRecoverableErrorWithoutClinicalRedrive() = runTest {
        val fixture = fixture()
        val recoverable = AssertionError("recoverable_start_failure")
        val cleanupProcessFatal = TestVirtualMachineError()
        val fatalSettlements = mutableListOf<Pair<Long, String>>()

        val result = runCatching {
            runReactiveWorkerOrchestration(
                reactiveWork = WORK,
                runAttemptCount = 0,
                timeoutMs = 1_000L,
                prepare = {
                    PreparedReactiveWorkerCycle {
                        fixture.cycleStarts += 1
                        true
                    }
                },
                onStarted = { throw recoverable },
                onCompleted = fixture::completed,
                onFailure = fixture::failed,
                acknowledge = fixture::acknowledge,
                terminate = fixture::terminate,
                terminateFatal = { generation, token ->
                    fatalSettlements += generation to token
                    throw cleanupProcessFatal
                }
            )
        }

        assertThat(result.exceptionOrNull()).isSameInstanceAs(cleanupProcessFatal)
        assertThat(cleanupProcessFatal.suppressed).hasLength(1)
        val suppressedRecoverable = cleanupProcessFatal.suppressed.single()
        assertThat(suppressedRecoverable).isInstanceOf(AssertionError::class.java)
        assertThat(suppressedRecoverable).hasMessageThat().isEqualTo(recoverable.message)
        assertThat(generateSequence(suppressedRecoverable) { it.cause }.any { it === recoverable })
            .isTrue()
        assertThat(fatalSettlements).containsExactly(WORK.generation to WORK.dispatchToken)
        assertThat(fixture.cycleStarts).isEqualTo(0)
        assertThat(fixture.acknowledged).isEmpty()
        assertThat(fixture.terminated).isEmpty()
        assertThat(fixture.failures).isEmpty()
    }

    @Test
    fun ordinaryEvidenceReadFailureRetriesThenTerminatesExactOwnerAtWorkerBound() = runTest {
        val fixture = fixture()
        val failure = IllegalStateException("evidence_read_failed")
        val ambiguousSettlements = mutableListOf<Pair<Long, String>>()

        val first = runReactiveWorkerEvidencePreflight(
            reactiveWork = WORK,
            runAttemptCount = 0,
            readEvidence = { throw failure },
            onFailure = fixture::failed,
            acknowledge = fixture::acknowledge,
            terminate = fixture::terminate,
            terminateAmbiguous = { generation, token ->
                ambiguousSettlements += generation to token
                true
            },
            terminateFatal = fixture::terminateFatal
        )
        val terminal = runReactiveWorkerEvidencePreflight(
            reactiveWork = WORK,
            runAttemptCount = 2,
            readEvidence = { throw MalformedClinicalInvalidationExecutionEvidenceException() },
            onFailure = fixture::failed,
            acknowledge = fixture::acknowledge,
            terminate = fixture::terminate,
            terminateAmbiguous = { generation, token ->
                ambiguousSettlements += generation to token
                true
            },
            terminateFatal = fixture::terminateFatal
        )

        assertThat(first.action).isEqualTo(ReactiveWorkerPreflightAction.RETRY)
        assertThat(terminal.action).isEqualTo(ReactiveWorkerPreflightAction.FAILURE)
        assertThat(ambiguousSettlements).containsExactly(WORK.generation to WORK.dispatchToken)
        assertThat(fixture.terminated).isEmpty()
        assertThat(fixture.acknowledged).isEmpty()
        assertThat(fixture.fatallyTerminated).isEmpty()
        assertThat(fixture.failures).hasSize(2)
        assertThat(fixture.cycleStarts).isEqualTo(0)
    }

    @Test
    fun actualCancellationReadingEvidenceTerminatesExactOwnerNonCancellablyAndPropagates() = runTest {
        val fixture = fixture()
        val readStarted = CompletableDeferred<Unit>()
        val ambiguousSettlements = mutableListOf<Pair<Long, String>>()

        val job = launch {
            runReactiveWorkerEvidencePreflight(
                reactiveWork = WORK,
                runAttemptCount = 0,
                readEvidence = {
                    readStarted.complete(Unit)
                    awaitCancellation()
                },
                onFailure = fixture::failed,
                acknowledge = fixture::acknowledge,
                terminate = fixture::terminate,
                terminateAmbiguous = { generation, token ->
                    ambiguousSettlements += generation to token
                    true
                },
                terminateFatal = fixture::terminateFatal
            )
        }

        runCurrent()
        assertThat(readStarted.isCompleted).isTrue()
        job.cancelAndJoin()

        assertThat(job.isCancelled).isTrue()
        assertThat(ambiguousSettlements).containsExactly(WORK.generation to WORK.dispatchToken)
        assertThat(fixture.terminated).isEmpty()
        assertThat(fixture.acknowledged).isEmpty()
        assertThat(fixture.fatallyTerminated).isEmpty()
        assertThat(fixture.cycleStarts).isEqualTo(0)
    }

    @Test
    fun evidenceReadTimeoutUsesExactAmbiguousCleanupAndPropagatesWithoutCycle() = runTest {
        val fixture = fixture()
        val ambiguousSettlements = mutableListOf<Pair<Long, String>>()

        val result = runCatching {
            runReactiveWorkerEvidencePreflight(
                reactiveWork = WORK,
                runAttemptCount = 0,
                timeoutMs = 100L,
                readEvidence = { awaitCancellation() },
                onFailure = fixture::failed,
                acknowledge = fixture::acknowledge,
                terminate = fixture::terminate,
                terminateAmbiguous = { generation, token ->
                    ambiguousSettlements += generation to token
                    true
                },
                terminateFatal = fixture::terminateFatal
            )
        }

        assertThat(result.exceptionOrNull()).isInstanceOf(TimeoutCancellationException::class.java)
        assertThat(ambiguousSettlements).containsExactly(WORK.generation to WORK.dispatchToken)
        assertThat(fixture.terminated).isEmpty()
        assertThat(fixture.cycleStarts).isEqualTo(0)
    }

    @Test
    fun reactiveWorkerDeadlinePassesOnlyRemainingBudgetAfterPreflight() = runTest {
        val deadline = ReactiveWorkerDeadline(
            timeoutMs = 240L,
            nowMs = { testScheduler.currentTime }
        )

        val preflight = runReactiveWorkerEvidencePreflight(
            reactiveWork = WORK,
            runAttemptCount = 0,
            timeoutMs = deadline.remainingMs(),
            readEvidence = {
                delay(90L)
                null
            },
            onFailure = fixture()::failed,
            acknowledge = { _, _ -> true },
            terminate = { _, _ -> true },
            terminateAmbiguous = { _, _ -> true },
            terminateFatal = { _, _ -> true }
        )

        assertThat(preflight.action).isEqualTo(ReactiveWorkerPreflightAction.PROCEED)
        assertThat(testScheduler.currentTime).isEqualTo(90L)
        assertThat(deadline.remainingMs()).isEqualTo(150L)
        advanceTimeBy(151L)
        assertThat(deadline.remainingMs()).isEqualTo(0L)
    }

    @Test
    fun recoverableErrorReadingEvidenceUsesExactFatalSettlementAndRethrows() = runTest {
        val fixture = fixture()
        val fatal = AssertionError("evidence_read_error")

        val result = runCatching {
            runReactiveWorkerEvidencePreflight(
                reactiveWork = WORK,
                runAttemptCount = 0,
                readEvidence = { throw fatal },
                onFailure = fixture::failed,
                acknowledge = fixture::acknowledge,
                terminate = fixture::terminate,
                terminateAmbiguous = { _, _ -> error("unexpected ambiguous settlement") },
                terminateFatal = fixture::terminateFatal
            )
        }

        assertThat(result.exceptionOrNull()).isSameInstanceAs(fatal)
        assertThat(fixture.fatallyTerminated)
            .containsExactly(WORK.generation to WORK.dispatchToken)
        assertThat(fixture.terminated).isEmpty()
        assertThat(fixture.acknowledged).isEmpty()
        assertThat(fixture.cycleStarts).isEqualTo(0)
    }

    private fun fixture() = Fixture()

    private class Fixture {
        var settingsReads = 0
        var startAudits = 0
        var cycleStarts = 0
        val acknowledged = mutableListOf<Pair<Long, String>>()
        val terminated = mutableListOf<Pair<Long, String>>()
        val fatallyTerminated = mutableListOf<Pair<Long, String>>()
        val failures = mutableListOf<Throwable>()

        suspend fun completed(cycleAccepted: Boolean) {
            check(cycleAccepted)
        }

        suspend fun failed(error: Throwable) {
            failures += error
        }

        suspend fun acknowledge(generation: Long, token: String): Boolean {
            acknowledged += generation to token
            return true
        }

        suspend fun terminate(generation: Long, token: String): Boolean {
            terminated += generation to token
            return true
        }

        suspend fun terminateFatal(generation: Long, token: String): Boolean {
            fatallyTerminated += generation to token
            return true
        }
    }

    private class FatalStartError(@Suppress("unused") marker: Any) : Error("fatal_start")

    private class TestVirtualMachineError : VirtualMachineError("fatal_cleanup")

    private companion object {
        val WORK = ClinicalInvalidationWorkData(
            generation = 17L,
            dispatchToken = "reactive-worker-token",
            mode = ClinicalInputInvalidationMode.NORMAL,
            coordinatorAttempt = 0
        )
    }
}
