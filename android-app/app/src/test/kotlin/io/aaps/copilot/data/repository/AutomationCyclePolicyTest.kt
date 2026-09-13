package io.aaps.copilot.data.repository

import com.google.common.truth.Truth.assertThat
import io.aaps.copilot.domain.model.Forecast
import io.aaps.copilot.testSensitivityRuntimeSnapshot
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class AutomationCyclePolicyTest {

    @Test
    fun profileRevisionPendingReadFailureIsFailClosed() = runTest {
        var rebuildCalls = 0
        var failureReason = ""

        val result = AutomationRepository.prepareProfileEstimatorRevisionStatic(
            isPending = { error("revision state unavailable") },
            rebuild = {
                rebuildCalls += 1
                true
            },
            onNotReady = { failureReason = it }
        )

        assertThat(result.wasPending).isTrue()
        assertThat(result.ready).isFalse()
        assertThat(rebuildCalls).isEqualTo(0)
        assertThat(failureReason).contains("revision state unavailable")
    }

    @Test
    fun profileRevisionFailureRemainsFailClosedWhenAuditReportingAlsoFails() = runTest {
        val result = AutomationRepository.prepareProfileEstimatorRevisionStatic(
            isPending = { error("revision state unavailable") },
            rebuild = { true },
            onNotReady = { error("audit unavailable") }
        )

        assertThat(result.wasPending).isTrue()
        assertThat(result.ready).isFalse()
    }

    @Test
    fun acceptedCycleStatePublicationIncludesSourceChangeButMaintenanceRemainsNormalOnly() = runTest {
        val accepted = testSensitivityRuntimeSnapshot(cycleId = "accepted-uam-cycle")
        val events = mutableListOf<String>()

        AutomationRepository.publishAcceptedCycleStateStatic(
            intent = AutomationRepository.AutomationCycleIntent.NORMAL,
            acceptedSnapshot = accepted,
            publishUiTelemetry = { snapshot -> events += "uam:${snapshot.forecastCycleId}" },
            runLocalMaintenance = { events += "maintenance" }
        )
        AutomationRepository.publishAcceptedCycleStateStatic(
            intent = AutomationRepository.AutomationCycleIntent.LOCAL_READ_ONLY,
            acceptedSnapshot = accepted,
            publishUiTelemetry = { events += "local-uam" },
            runLocalMaintenance = { events += "local-maintenance" }
        )
        AutomationRepository.publishAcceptedCycleStateStatic(
            intent = AutomationRepository.AutomationCycleIntent.SENSITIVITY_SOURCE_CHANGE,
            acceptedSnapshot = accepted,
            publishUiTelemetry = { events += "source-uam" },
            runLocalMaintenance = { events += "source-maintenance" }
        )

        assertThat(events).containsExactly(
            "uam:accepted-uam-cycle",
            "maintenance",
            "source-uam"
        ).inOrder()
    }

    @Test
    fun failedAcceptedFenceLeavesExistingUamTelemetryAndPendingSelectionsUnchanged() = runTest {
        val candidate = testSensitivityRuntimeSnapshot(cycleId = "rejected-uam-cycle")
        var visibleUamCycle = "previous-uam-cycle"
        var pendingSelectionRevision = 7

        val failure = runCatching {
            AutomationRepository.runAfterAcceptedSensitivityCycleStatic(
                persistPendingRoomTuple = {},
                reserveAccepted = { "reservation" },
                commitAcceptedRoomTuple = { error("Room commit failed") },
                reconcileAcceptedRoomTuple = { false },
                finalizeAccepted = {},
                clinicalSideEffects = {
                    AutomationRepository.publishAcceptedCycleStateStatic(
                        intent = AutomationRepository.AutomationCycleIntent.NORMAL,
                        acceptedSnapshot = candidate,
                        publishUiTelemetry = { visibleUamCycle = it.forecastCycleId },
                        runLocalMaintenance = { pendingSelectionRevision += 1 }
                    )
                }
            )
        }.exceptionOrNull()

        assertThat(failure).isNotNull()
        assertThat(visibleUamCycle).isEqualTo("previous-uam-cycle")
        assertThat(pendingSelectionRevision).isEqualTo(7)
    }

    @Test
    fun acceptedForecastConsumerRefreshesForNormalAndSourceChangeButNotLocalReadOnly() = runTest {
        val refreshed = mutableListOf<AutomationRepository.AutomationCycleIntent>()

        AutomationRepository.publishAcceptedForecastConsumerStatic(
            AutomationRepository.AutomationCycleIntent.NORMAL
        ) { refreshed += AutomationRepository.AutomationCycleIntent.NORMAL }
        AutomationRepository.publishAcceptedForecastConsumerStatic(
            AutomationRepository.AutomationCycleIntent.SENSITIVITY_SOURCE_CHANGE
        ) { refreshed += AutomationRepository.AutomationCycleIntent.SENSITIVITY_SOURCE_CHANGE }
        AutomationRepository.publishAcceptedForecastConsumerStatic(
            AutomationRepository.AutomationCycleIntent.LOCAL_READ_ONLY
        ) { refreshed += AutomationRepository.AutomationCycleIntent.LOCAL_READ_ONLY }

        assertThat(refreshed).containsExactly(
            AutomationRepository.AutomationCycleIntent.NORMAL,
            AutomationRepository.AutomationCycleIntent.SENSITIVITY_SOURCE_CHANGE
        ).inOrder()
    }

    @Test
    fun rejectedSourceForecastNeverRefreshesWidgetConsumer() = runTest {
        var refreshes = 0

        runCatching {
            AutomationRepository.runAfterAcceptedSensitivityCycleStatic(
                persistPendingRoomTuple = {},
                reserveAccepted = { "reservation" },
                commitAcceptedRoomTuple = { error("forecast transaction failed") },
                reconcileAcceptedRoomTuple = { false },
                finalizeAccepted = {},
                clinicalSideEffects = {
                    AutomationRepository.publishAcceptedForecastConsumerStatic(
                        AutomationRepository.AutomationCycleIntent.SENSITIVITY_SOURCE_CHANGE
                    ) { refreshes += 1 }
                }
            )
        }

        assertThat(refreshes).isEqualTo(0)
    }

    @Test
    fun acceptedCommitAndPublicationPrecedeEveryClinicalSideEffect() = runTest {
        val candidate = testSensitivityRuntimeSnapshot(cycleId = "accepted-cycle")
        val events = mutableListOf<String>()
        var current: io.aaps.copilot.domain.predict.SensitivityRuntimeSnapshot? = null

        val result = AutomationRepository.runAfterAcceptedSensitivityCycleStatic(
            persistPendingRoomTuple = { events += "room-pending" },
            reserveAccepted = { events += "stateflow-reserved"; "reservation" },
            commitAcceptedRoomTuple = { events += "room-commit-attempt" },
            reconcileAcceptedRoomTuple = { events += "room-reconciled"; true },
            finalizeAccepted = { reservation ->
                assertThat(reservation).isEqualTo("reservation")
                events += "stateflow-accepted"
                current = candidate
            },
            clinicalSideEffects = {
                events += listOf("uam", "target-manager", "actions", "alerts", "widget")
                17
            }
        )

        assertThat(result).isEqualTo(17)
        assertThat(events).containsExactly(
            "room-pending",
            "stateflow-reserved",
            "room-commit-attempt",
            "room-reconciled",
            "stateflow-accepted",
            "uam",
            "target-manager",
            "actions",
            "alerts",
            "widget"
        ).inOrder()
    }

    @Test
    fun anyAcceptedFenceFailureSuppressesAllClinicalSideEffects() = runTest {
        val failures = listOf<suspend (MutableList<String>) -> Unit>(
            { effects ->
                AutomationRepository.runAfterAcceptedSensitivityCycleStatic(
                    persistPendingRoomTuple = { error("room transaction failed") },
                    reserveAccepted = { "reservation" },
                    commitAcceptedRoomTuple = {},
                    reconcileAcceptedRoomTuple = { true },
                    finalizeAccepted = {},
                    clinicalSideEffects = {
                        effects += listOf("uam", "target-manager", "actions", "alerts", "widget")
                    }
                )
            },
            { effects ->
                AutomationRepository.runAfterAcceptedSensitivityCycleStatic(
                    persistPendingRoomTuple = {},
                    reserveAccepted = { error("revision fence failed") },
                    commitAcceptedRoomTuple = {},
                    reconcileAcceptedRoomTuple = { true },
                    finalizeAccepted = {},
                    clinicalSideEffects = {
                        effects += listOf("uam", "target-manager", "actions", "alerts", "widget")
                    }
                )
            },
            { effects ->
                AutomationRepository.runAfterAcceptedSensitivityCycleStatic(
                    persistPendingRoomTuple = {},
                    reserveAccepted = { null },
                    commitAcceptedRoomTuple = {},
                    reconcileAcceptedRoomTuple = { true },
                    finalizeAccepted = {},
                    clinicalSideEffects = {
                        effects += listOf("uam", "target-manager", "actions", "alerts", "widget")
                    }
                )
            },
            { effects ->
                AutomationRepository.runAfterAcceptedSensitivityCycleStatic(
                    persistPendingRoomTuple = {},
                    reserveAccepted = { "reservation" },
                    commitAcceptedRoomTuple = { error("Room commit failed") },
                    reconcileAcceptedRoomTuple = { false },
                    finalizeAccepted = {},
                    clinicalSideEffects = {
                        effects += listOf("uam", "target-manager", "actions", "alerts", "widget")
                    }
                )
            },
            { effects ->
                AutomationRepository.runAfterAcceptedSensitivityCycleStatic(
                    persistPendingRoomTuple = {},
                    reserveAccepted = { "reservation" },
                    commitAcceptedRoomTuple = {},
                    reconcileAcceptedRoomTuple = { false },
                    finalizeAccepted = {},
                    clinicalSideEffects = {
                        effects += listOf("uam", "target-manager", "actions", "alerts", "widget")
                    }
                )
            },
            { effects ->
                AutomationRepository.runAfterAcceptedSensitivityCycleStatic(
                    persistPendingRoomTuple = {},
                    reserveAccepted = { "reservation" },
                    commitAcceptedRoomTuple = {},
                    reconcileAcceptedRoomTuple = { error("Room readback unavailable") },
                    finalizeAccepted = {},
                    clinicalSideEffects = {
                        effects += listOf("uam", "target-manager", "actions", "alerts", "widget")
                    }
                )
            },
            { effects ->
                AutomationRepository.runAfterAcceptedSensitivityCycleStatic(
                    persistPendingRoomTuple = {},
                    reserveAccepted = { "reservation" },
                    commitAcceptedRoomTuple = {},
                    reconcileAcceptedRoomTuple = { true },
                    finalizeAccepted = { error("accepted publication failed") },
                    clinicalSideEffects = {
                        effects += listOf("uam", "target-manager", "actions", "alerts", "widget")
                    }
                )
            }
        )

        failures.forEach { runFailure ->
            val effects = mutableListOf<String>()
            assertThat(runCatching { runFailure(effects) }.exceptionOrNull()).isNotNull()
            assertThat(effects).isEmpty()
        }
    }

    @Test
    fun fatalCommitFailuresPropagateWithoutReadbackOrPublication() = runTest {
        val failures = listOf<Throwable>(
            LinkageError("linkage failure"),
            DirectFatalThrowable("direct fatal failure"),
            CancellationException("commit cancelled")
        )

        failures.forEach { expected ->
            var readbacks = 0
            var finalizations = 0
            var effects = 0

            val actual = try {
                AutomationRepository.runAfterAcceptedSensitivityCycleStatic(
                    persistPendingRoomTuple = {},
                    reserveAccepted = { "reservation" },
                    commitAcceptedRoomTuple = { throw expected },
                    reconcileAcceptedRoomTuple = { readbacks += 1; true },
                    finalizeAccepted = { finalizations += 1 },
                    clinicalSideEffects = { effects += 1 }
                )
                null
            } catch (failure: Throwable) {
                failure
            }

            assertThat(actual?.javaClass).isEqualTo(expected.javaClass)
            assertThat(actual).hasMessageThat().isEqualTo(expected.message)
            assertThat(readbacks).isEqualTo(0)
            assertThat(finalizations).isEqualTo(0)
            assertThat(effects).isEqualTo(0)
        }
    }

    @Test
    fun fatalReadbackFailuresPropagateWithoutPublication() = runTest {
        val failures = listOf<Throwable>(
            LinkageError("readback linkage failure"),
            DirectFatalThrowable("readback direct fatal failure"),
            CancellationException("readback cancelled")
        )

        failures.forEach { expected ->
            var finalizations = 0
            var effects = 0

            val actual = try {
                AutomationRepository.runAfterAcceptedSensitivityCycleStatic(
                    persistPendingRoomTuple = {},
                    reserveAccepted = { "reservation" },
                    commitAcceptedRoomTuple = {},
                    reconcileAcceptedRoomTuple = { throw expected },
                    finalizeAccepted = { finalizations += 1 },
                    clinicalSideEffects = { effects += 1 }
                )
                null
            } catch (failure: Throwable) {
                failure
            }

            assertThat(actual?.javaClass).isEqualTo(expected.javaClass)
            assertThat(actual).hasMessageThat().isEqualTo(expected.message)
            assertThat(finalizations).isEqualTo(0)
            assertThat(effects).isEqualTo(0)
        }
    }

    @Test
    fun nestedCommitTimeoutCancellationPropagatesWithoutReadback() = runTest {
        var readbacks = 0
        var finalizations = 0
        var effects = 0

        val failure = try {
            AutomationRepository.runAfterAcceptedSensitivityCycleStatic(
                persistPendingRoomTuple = {},
                reserveAccepted = { "reservation" },
                commitAcceptedRoomTuple = {
                    withTimeout(1L) { awaitCancellation() }
                },
                reconcileAcceptedRoomTuple = { readbacks += 1; true },
                finalizeAccepted = { finalizations += 1 },
                clinicalSideEffects = { effects += 1 }
            )
            null
        } catch (cancellation: CancellationException) {
            cancellation
        }

        assertThat(failure).isInstanceOf(TimeoutCancellationException::class.java)
        assertThat(readbacks).isEqualTo(0)
        assertThat(finalizations).isEqualTo(0)
        assertThat(effects).isEqualTo(0)
    }

    @Test
    fun roomCommitFailureAfterReservationCannotPublishCandidateCurrent() = runTest {
        val previous = testSensitivityRuntimeSnapshot(cycleId = "previous-cycle")
        val candidate = testSensitivityRuntimeSnapshot(cycleId = "candidate-cycle")
        var current = previous
        var finalizations = 0
        var effects = 0

        val failure = runCatching {
            AutomationRepository.runAfterAcceptedSensitivityCycleStatic(
                persistPendingRoomTuple = {},
                reserveAccepted = { "reservation" },
                commitAcceptedRoomTuple = { error("Room commit failed") },
                reconcileAcceptedRoomTuple = { false },
                finalizeAccepted = {
                    finalizations += 1
                    current = candidate
                },
                clinicalSideEffects = { effects += 1 }
            )
        }.exceptionOrNull()

        assertThat(failure).hasMessageThat().contains("Room commit failed")
        assertThat(current).isSameInstanceAs(previous)
        assertThat(finalizations).isEqualTo(0)
        assertThat(effects).isEqualTo(0)
    }

    @Test
    fun neverReturningRoomCommitTimesOutReleasesNormalLeaseAndSuppressesEffects() = runTest {
        val mutex = Mutex()
        val previous = testSensitivityRuntimeSnapshot(cycleId = "previous-cycle")
        val candidate = testSensitivityRuntimeSnapshot(cycleId = "candidate-cycle")
        val commitGate = CompletableDeferred<Unit>()
        var current = previous
        var finalizations = 0
        var effects = 0

        val attempt = async {
            runCatching {
                AutomationRepository.runNormalCycleIfIdleStatic(mutex) {
                    AutomationRepository.runAfterAcceptedSensitivityCycleStatic(
                        persistPendingRoomTuple = {},
                        reserveAccepted = { "reservation" },
                        commitAcceptedRoomTuple = { commitGate.await() },
                        reconcileAcceptedRoomTuple = { false },
                        finalizeAccepted = {
                            finalizations += 1
                            current = candidate
                        },
                        clinicalSideEffects = { effects += 1 }
                    )
                }
            }.exceptionOrNull()
        }

        runCurrent()
        advanceTimeBy(5_001L)
        runCurrent()
        val completedWithinBound = attempt.isCompleted
        commitGate.complete(Unit)
        runCurrent()
        val failure = attempt.await()
        val subsequent = AutomationRepository.runNormalCycleIfIdleStatic(mutex) { "next" }

        assertThat(completedWithinBound).isTrue()
        assertThat(failure).hasMessageThat().contains("Room commit timed out")
        assertThat(current).isSameInstanceAs(previous)
        assertThat(finalizations).isEqualTo(0)
        assertThat(effects).isEqualTo(0)
        assertThat(mutex.isLocked).isFalse()
        assertThat(subsequent.acquired).isTrue()
        assertThat(subsequent.value).isEqualTo("next")
    }

    @Test
    fun commitTimeoutWithExactCommittedReadbackFinalizesAndRunsEffects() =
        runTest {
            val mutex = Mutex()
            val previous = testSensitivityRuntimeSnapshot(cycleId = "previous-cycle")
            val candidate = testSensitivityRuntimeSnapshot(cycleId = "candidate-cycle")
            val commitGate = CompletableDeferred<Unit>()
            var durableCommit = false
            var current = previous
            var finalizations = 0
            var effects = 0

            val attempt = async {
                runCatching {
                    AutomationRepository.runSerializedSensitivitySourceChangeStatic(
                        mutex = mutex,
                        lockTimeoutMs = 10_000L
                    ) {
                        AutomationRepository.runAfterAcceptedSensitivityCycleStatic(
                            persistPendingRoomTuple = {},
                            reserveAccepted = { "reservation" },
                            commitAcceptedRoomTuple = {
                                durableCommit = true
                                commitGate.await()
                            },
                            reconcileAcceptedRoomTuple = { durableCommit },
                            finalizeAccepted = {
                                finalizations += 1
                                current = candidate
                            },
                            clinicalSideEffects = { effects += 1 }
                        )
                    }
                }.getOrThrow()
            }

            runCurrent()
            advanceTimeBy(5_001L)
            runCurrent()
            val completedWithinBound = attempt.isCompleted
            commitGate.complete(Unit)
            runCurrent()
            val result = attempt.await()
            val subsequent = AutomationRepository.runSerializedSensitivitySourceChangeStatic(
                mutex = mutex,
                lockTimeoutMs = 100L
            ) { "next" }

            assertThat(completedWithinBound).isTrue()
            assertThat(result).isEqualTo(kotlin.Unit)
            assertThat(current).isSameInstanceAs(candidate)
            assertThat(finalizations).isEqualTo(1)
            assertThat(effects).isEqualTo(1)
            assertThat(mutex.isLocked).isFalse()
            assertThat(subsequent).isEqualTo("next")
        }

    @Test
    fun commitExceptionAfterDurableWriteIsReconciledAsSuccess() = runTest {
        var durableCommit = false
        var finalizations = 0
        var effects = 0

        val result = AutomationRepository.runAfterAcceptedSensitivityCycleStatic(
            persistPendingRoomTuple = {},
            reserveAccepted = { "reservation" },
            commitAcceptedRoomTuple = {
                durableCommit = true
                error("transport lost after durable commit")
            },
            reconcileAcceptedRoomTuple = { durableCommit },
            finalizeAccepted = { finalizations += 1 },
            clinicalSideEffects = { effects += 1; "accepted" }
        )

        assertThat(result).isEqualTo("accepted")
        assertThat(finalizations).isEqualTo(1)
        assertThat(effects).isEqualTo(1)
    }

    @Test
    fun parentCancellationAfterDurableReadbackFinalizesButSuppressesClinicalEffects() = runTest {
        val commitEntered = CompletableDeferred<Unit>()
        val releaseCommit = CompletableDeferred<Unit>()
        var finalizations = 0
        var effects = 0
        var propagated: CancellationException? = null

        val job = launch {
            try {
                AutomationRepository.runAfterAcceptedSensitivityCycleStatic(
                    persistPendingRoomTuple = {},
                    reserveAccepted = { "reservation" },
                    commitAcceptedRoomTuple = {
                        commitEntered.complete(Unit)
                        releaseCommit.await()
                    },
                    reconcileAcceptedRoomTuple = { true },
                    finalizeAccepted = { finalizations += 1 },
                    clinicalSideEffects = { effects += 1 }
                )
            } catch (cancellation: CancellationException) {
                propagated = cancellation
                throw cancellation
            }
        }

        commitEntered.await()
        job.cancel(CancellationException("parent cancelled after durable commit"))
        releaseCommit.complete(Unit)
        job.join()

        assertThat(finalizations).isEqualTo(1)
        assertThat(effects).isEqualTo(0)
        assertThat(propagated).isNotNull()
        assertThat(job.isCancelled).isTrue()
    }

    @Test
    fun nonFatalStepLocalTimeoutIsReportedAndCycleContinues() = runTest {
        val failures = mutableListOf<Pair<Throwable?, Boolean>>()
        var continued = false

        val result = AutomationRepository.runNonFatalCycleStepStatic(
            timeoutMs = 100L,
            onNonFatalFailure = { error, timedOut -> failures += error to timedOut }
        ) {
            delay(101L)
            "late"
        }
        continued = true

        assertThat(result).isNull()
        assertThat(continued).isTrue()
        assertThat(failures).hasSize(1)
        assertThat(failures.single().first).isNull()
        assertThat(failures.single().second).isTrue()
    }

    @Test
    fun nonFatalStepPropagatesParentCancellation() = runTest {
        val entered = CompletableDeferred<Unit>()
        var propagated: CancellationException? = null

        val job = launch {
            try {
                AutomationRepository.runNonFatalCycleStepStatic(timeoutMs = 10_000L) {
                    entered.complete(Unit)
                    awaitCancellation()
                }
            } catch (cancellation: CancellationException) {
                propagated = cancellation
                throw cancellation
            }
        }

        entered.await()
        job.cancelAndJoin()

        assertThat(propagated).isNotNull()
        assertThat(job.isCancelled).isTrue()
    }

    @Test
    fun nonFatalStepPropagatesFatalError() = runTest {
        val fatal = LinkageError("fatal")

        val failure = runCatching {
            AutomationRepository.runNonFatalCycleStepStatic<Unit> { throw fatal }
        }.exceptionOrNull()

        assertThat(failure).isSameInstanceAs(fatal)
    }

    @Test
    fun nonFatalStepPropagatesDirectNonExceptionThrowable() = runTest {
        val fatalBoundary = object : Throwable("fatal boundary") {}

        val failure = runCatching {
            AutomationRepository.runNonFatalCycleStepStatic<Unit> { throw fatalBoundary }
        }.exceptionOrNull()

        assertThat(failure).isSameInstanceAs(fatalBoundary)
    }

    @Test
    fun nonFatalStepReportsOperationalExceptionAndContinues() = runTest {
        val operational = IllegalStateException("provider unavailable")
        val failures = mutableListOf<Pair<Throwable?, Boolean>>()

        val result = AutomationRepository.runNonFatalCycleStepStatic<Unit>(
            onNonFatalFailure = { error, timedOut -> failures += error to timedOut }
        ) { throw operational }

        assertThat(result).isNull()
        assertThat(failures).containsExactly(operational to false)
    }

    @Test
    fun nonFatalStepPreservesSuccessfulNullableResult() = runTest {
        var failures = 0

        val result = AutomationRepository.runNonFatalCycleStepStatic<String?>(
            timeoutMs = 100L,
            onNonFatalFailure = { _, _ -> failures += 1 }
        ) { null }

        assertThat(result).isNull()
        assertThat(failures).isEqualTo(0)
    }

    @Test
    fun unavailableReadbackAfterDurableWriteIsAmbiguousAndSuppressesPublicationAndEffects() =
        runTest {
            var finalizations = 0
            var effects = 0
            var aborted = 0

            val failure = runCatching {
                AutomationRepository.runAfterAcceptedSensitivityCycleStatic(
                    persistPendingRoomTuple = {},
                    reserveAccepted = { "reservation" },
                    commitAcceptedRoomTuple = {},
                    reconcileAcceptedRoomTuple = { error("database unavailable") },
                    finalizeAccepted = { finalizations += 1 },
                    abortReservation = { aborted += 1 },
                    clinicalSideEffects = { effects += 1 }
                )
            }.exceptionOrNull()

            assertThat(failure).isNotNull()
            assertThat(failure!!.message).contains("ambiguous")
            assertThat(finalizations).isEqualTo(0)
            assertThat(effects).isEqualTo(0)
            assertThat(aborted).isEqualTo(1)
        }

    @Test
    fun revisionMarkerFailureAfterAcceptedTupleSuppressesClinicalSideEffects() = runTest {
        var finalizations = 0
        var markerAttempts = 0
        var effects = 0

        val failure = runCatching {
            AutomationRepository.runAfterAcceptedSensitivityCycleStatic(
                persistPendingRoomTuple = {},
                reserveAccepted = { "reservation" },
                commitAcceptedRoomTuple = {},
                reconcileAcceptedRoomTuple = { true },
                finalizeAccepted = { finalizations += 1 },
                beforeClinicalSideEffects = {
                    markerAttempts += 1
                    error("revision marker unavailable")
                },
                clinicalSideEffects = { effects += 1 }
            )
        }.exceptionOrNull()

        assertThat(failure).isNotNull()
        assertThat(finalizations).isEqualTo(1)
        assertThat(markerAttempts).isEqualTo(1)
        assertThat(effects).isEqualTo(0)
    }

    @Test
    fun cancellationAfterReservationSynchronouslyAbortsAndDoesNotLeakPublicationOwnership() =
        runTest {
            val commitGate = CompletableDeferred<Unit>()
            var reservationActive = false
            var effects = 0
            val abort: (String) -> Unit = { reservation ->
                assertThat(reservation).isEqualTo("reservation")
                reservationActive = false
            }

            val job = launch {
                runCatching {
                    AutomationRepository.runAfterAcceptedSensitivityCycleStatic(
                        persistPendingRoomTuple = {},
                        reserveAccepted = {
                            check(!reservationActive)
                            reservationActive = true
                            "reservation"
                        },
                        commitAcceptedRoomTuple = { commitGate.await() },
                        reconcileAcceptedRoomTuple = { false },
                        finalizeAccepted = { reservationActive = false },
                        abortReservation = abort,
                        clinicalSideEffects = { effects += 1 }
                    )
                }
            }

            runCurrent()
            assertThat(reservationActive).isTrue()
            job.cancel()
            advanceTimeBy(5_001L)
            runCurrent()
            job.join()

            assertThat(reservationActive).isFalse()
            assertThat(effects).isEqualTo(0)
            val next = AutomationRepository.runAfterAcceptedSensitivityCycleStatic(
                persistPendingRoomTuple = {},
                reserveAccepted = {
                    check(!reservationActive)
                    reservationActive = true
                    "next-reservation"
                },
                commitAcceptedRoomTuple = {},
                reconcileAcceptedRoomTuple = { true },
                finalizeAccepted = { reservationActive = false },
                abortReservation = { reservationActive = false },
                clinicalSideEffects = { "accepted" }
            )
            assertThat(next).isEqualTo("accepted")
            assertThat(reservationActive).isFalse()
        }

    @Test
    fun sensitivitySourceChangeRunsCalculationsWhileDisarmedAndNeverEntersWriterStage() = runTest {
        val policy = AutomationRepository.resolveCyclePolicyStatic(
            intent = AutomationRepository.AutomationCycleIntent.SENSITIVITY_SOURCE_CHANGE,
            therapyActionsArmed = false,
            killSwitch = false,
            powerSaveActive = false
        )
        assertThat(policy.runCalculations).isTrue()
        assertThat(policy.runRemoteRefresh).isFalse()
        assertThat(policy.therapyWritersAllowed).isFalse()
        assertThat(policy.allowSensitivityMaintenance).isFalse()
        assertThat(policy.allowActionRepositoryAccess).isFalse()
        assertThat(policy.allowLocalSafetyEvidence).isTrue()
        assertThat(policy.allowAlertPublication).isFalse()
        assertThat(policy.publishWidgetAfterAcceptance).isTrue()
    }

    @Test
    fun localReadOnlyIntentCannotUpgradeWhenSettingsBecomeArmedAndPowerSaveEnds() = runTest {
        val bootstrap = AutomationRepository.resolveCyclePolicyStatic(
            intent = AutomationRepository.AutomationCycleIntent.LOCAL_READ_ONLY,
            therapyActionsArmed = false,
            killSwitch = false,
            powerSaveActive = true
        )
        val afterSettingsRace = AutomationRepository.restrictCyclePolicyStatic(
            bootstrapPolicy = bootstrap,
            intent = AutomationRepository.AutomationCycleIntent.LOCAL_READ_ONLY,
            therapyActionsArmed = true,
            killSwitch = false,
            powerSaveActive = false
        )
        var uamWrites = 0
        var targetEvaluations = 0
        var externalTargetWrites = 0
        var remoteRefreshes = 0
        var sensitivityMaintenance = 0

        AutomationRepository.runRemoteRefreshStatic(afterSettingsRace) { remoteRefreshes += 1 }
        AutomationRepository.runSensitivityMaintenanceStatic(
            policy = afterSettingsRace,
            refreshRealtimeCandidate = { sensitivityMaintenance += 1 },
            evaluateShadowAutoActivation = { sensitivityMaintenance += 1 },
            runRetentionMaintenance = { sensitivityMaintenance += 1 }
        )

        AutomationRepository.runTherapyStageStatic(
            policy = afterSettingsRace,
            writeUamCarbs = { uamWrites += 1 },
            evaluateRulesAndTargetManager = { writersAllowed ->
                targetEvaluations += 1
                if (writersAllowed) externalTargetWrites += 1
            }
        )
        assertThat(afterSettingsRace.runCalculations).isTrue()
        assertThat(afterSettingsRace.runRemoteRefresh).isFalse()
        assertThat(afterSettingsRace.allowSensitivityMaintenance).isFalse()
        assertThat(afterSettingsRace.allowActionRepositoryAccess).isFalse()
        assertThat(afterSettingsRace.allowLocalSafetyEvidence).isFalse()
        assertThat(afterSettingsRace.allowAlertPublication).isFalse()
        assertThat(afterSettingsRace.therapyWritersAllowed).isFalse()
        assertThat(afterSettingsRace.publishWidgetAfterAcceptance).isFalse()
        assertThat(uamWrites).isEqualTo(0)
        assertThat(targetEvaluations).isEqualTo(1)
        assertThat(externalTargetWrites).isEqualTo(0)
        assertThat(remoteRefreshes).isEqualTo(0)
        assertThat(sensitivityMaintenance).isEqualTo(0)
    }

    @Test
    fun sourceChangeUsesPersistedCandidatesWithoutRefreshAutoActivationOrMaintenance() = runTest {
        val policy = AutomationRepository.resolveCyclePolicyStatic(
            intent = AutomationRepository.AutomationCycleIntent.SENSITIVITY_SOURCE_CHANGE,
            therapyActionsArmed = true,
            killSwitch = false,
            powerSaveActive = false
        )
        var refreshes = 0
        var autoActivations = 0
        var maintenanceRuns = 0
        var revision = 42L

        AutomationRepository.runSensitivityMaintenanceStatic(
            policy = policy,
            refreshRealtimeCandidate = { refreshes += 1 },
            evaluateShadowAutoActivation = {
                autoActivations += 1
                revision += 1
            },
            runRetentionMaintenance = { maintenanceRuns += 1 }
        )

        assertThat(refreshes).isEqualTo(0)
        assertThat(autoActivations).isEqualTo(0)
        assertThat(maintenanceRuns).isEqualTo(0)
        assertThat(revision).isEqualTo(42L)
    }

    @Test
    fun normalCycleUsesOneCommonArmedKillAndPowerGateForEveryWriter() = runTest {
        assertThat(normalPolicy(armed = true, killSwitch = false, powerSave = false).therapyWritersAllowed).isTrue()
        assertThat(normalPolicy(armed = false, killSwitch = false, powerSave = false).therapyWritersAllowed).isFalse()
        assertThat(normalPolicy(armed = true, killSwitch = true, powerSave = false).therapyWritersAllowed).isFalse()
        assertThat(normalPolicy(armed = true, killSwitch = false, powerSave = true).therapyWritersAllowed).isFalse()

        val allowedOrder = mutableListOf<String>()
        AutomationRepository.runTherapyStageStatic(
            policy = normalPolicy(armed = true, killSwitch = false, powerSave = false),
            writeUamCarbs = { allowedOrder += "uam" },
            evaluateRulesAndTargetManager = { writersAllowed -> allowedOrder += "target-evaluate:$writersAllowed" }
        )
        assertThat(allowedOrder).containsExactly("uam", "target-evaluate:true").inOrder()

        listOf(
            Triple(false, false, false),
            Triple(true, true, false),
            Triple(true, false, true)
        ).forEach { (armed, killSwitch, powerSave) ->
            var evaluations = 0
            var writerCallbacks = 0
            AutomationRepository.runTherapyStageStatic(
                policy = normalPolicy(armed, killSwitch, powerSave),
                writeUamCarbs = { writerCallbacks += 1 },
                evaluateRulesAndTargetManager = { writersAllowed ->
                    evaluations += 1
                    if (writersAllowed) writerCallbacks += 1
                }
            )
            assertThat(evaluations).isEqualTo(1)
            assertThat(writerCallbacks).isEqualTo(0)
        }
    }

    @Test
    fun sourceChangeWaitsBehindBusyCycleThenRunsExactlyOnce() = runTest {
        val mutex = Mutex(locked = true)
        val runs = AtomicInteger()
        val result = async {
            AutomationRepository.runSerializedSensitivitySourceChangeStatic(
                mutex = mutex,
                lockTimeoutMs = 1_000L
            ) {
                runs.incrementAndGet()
                17L
            }
        }

        runCurrent()
        assertThat(runs.get()).isEqualTo(0)
        mutex.unlock()
        runCurrent()

        assertThat(result.await()).isEqualTo(17L)
        assertThat(runs.get()).isEqualTo(1)
        assertThat(mutex.isLocked).isFalse()
    }

    @Test
    fun sourceChangeLockTimeoutDoesNotStealOrUnlockCurrentOwner() = runTest {
        val mutex = Mutex(locked = true)
        val runs = AtomicInteger()
        val result = async {
            runCatching {
                AutomationRepository.runSerializedSensitivitySourceChangeStatic(
                    mutex = mutex,
                    lockTimeoutMs = 100L
                ) { runs.incrementAndGet() }
            }.exceptionOrNull()
        }

        advanceTimeBy(101L)
        runCurrent()

        assertThat(result.await()).isInstanceOf(IllegalStateException::class.java)
        assertThat(runs.get()).isEqualTo(0)
        assertThat(mutex.isLocked).isTrue()
        mutex.unlock()
    }

    @Test
    fun cancelledSourceChangeWaitDoesNotRunOrUnlockCurrentOwner() = runTest {
        val mutex = Mutex(locked = true)
        val runs = AtomicInteger()
        val waiter = launch {
            AutomationRepository.runSerializedSensitivitySourceChangeStatic(
                mutex = mutex,
                lockTimeoutMs = 1_000L
            ) { runs.incrementAndGet() }
        }

        runCurrent()
        waiter.cancelAndJoin()

        assertThat(waiter.isCancelled).isTrue()
        assertThat(runs.get()).isEqualTo(0)
        assertThat(mutex.isLocked).isTrue()
        mutex.unlock()
    }

    @Test
    fun stableCycleMutexSkipsSecondNormalAndSerializesSourceChangeWithoutOverlap() = runTest {
        val mutex = Mutex()
        val ownerEntered = CompletableDeferred<Unit>()
        val releaseOwner = CompletableDeferred<Unit>()
        val activeSections = AtomicInteger()
        val maxActiveSections = AtomicInteger()
        val uamStages = AtomicInteger()
        val targetManagerEvaluations = AtomicInteger()
        val acceptedPublications = AtomicInteger()

        suspend fun enterCriticalSection(label: String): String {
            val active = activeSections.incrementAndGet()
            maxActiveSections.updateAndGet { current -> maxOf(current, active) }
            if (label == "owner") {
                ownerEntered.complete(Unit)
                releaseOwner.await()
            }
            uamStages.incrementAndGet()
            targetManagerEvaluations.incrementAndGet()
            acceptedPublications.incrementAndGet()
            activeSections.decrementAndGet()
            return label
        }

        val owner = async {
            AutomationRepository.runNormalCycleIfIdleStatic(mutex) {
                enterCriticalSection("owner")
            }
        }
        ownerEntered.await()

        val overlappingNormal = AutomationRepository.runNormalCycleIfIdleStatic(mutex) {
            enterCriticalSection("overlap")
        }
        val sourceChange = async {
            AutomationRepository.runSerializedSensitivitySourceChangeStatic(
                mutex = mutex,
                lockTimeoutMs = 1_000L
            ) {
                enterCriticalSection("source")
            }
        }
        runCurrent()

        assertThat(overlappingNormal.acquired).isFalse()
        assertThat(overlappingNormal.value).isNull()
        assertThat(activeSections.get()).isEqualTo(1)
        assertThat(acceptedPublications.get()).isEqualTo(0)

        releaseOwner.complete(Unit)
        runCurrent()

        assertThat(owner.await().value).isEqualTo("owner")
        assertThat(sourceChange.await()).isEqualTo("source")
        assertThat(maxActiveSections.get()).isEqualTo(1)
        assertThat(uamStages.get()).isEqualTo(2)
        assertThat(targetManagerEvaluations.get()).isEqualTo(2)
        assertThat(acceptedPublications.get()).isEqualTo(2)
        assertThat(mutex.isLocked).isFalse()
    }

    @Test
    fun sourceMutationRequestedAfterOldFinalFenceCannotMakeOldClinicalEffectsStale() = runTest {
        val mutex = Mutex()
        val oldFinalFence = CompletableDeferred<Unit>()
        val releaseOldCycle = CompletableDeferred<Unit>()
        val order = mutableListOf<String>()
        val staleEffects = mutableListOf<String>()
        var settingsRevision = 10L

        val oldCycle = async {
            AutomationRepository.runNormalCycleIfIdleStatic(mutex) {
                order += "old-final-fence"
                oldFinalFence.complete(Unit)
                releaseOldCycle.await()
                listOf("uam", "target-manager", "actions", "alerts", "widget").forEach { effect ->
                    if (settingsRevision != 10L) staleEffects += effect
                    order += "old-$effect"
                }
            }
        }
        oldFinalFence.await()

        val sourceChange = async {
            AutomationRepository.applySensitivitySourceChangeUnderCycleLeaseStatic(
                mutex = mutex,
                lockTimeoutMs = 1_000L,
                mutateSource = {
                    settingsRevision = 11L
                    order += "source-mutated"
                    11L
                },
                runAcceptedCycle = { revision ->
                    order += "source-accepted-$revision"
                    revision
                }
            )
        }
        runCurrent()

        assertThat(settingsRevision).isEqualTo(10L)
        releaseOldCycle.complete(Unit)
        oldCycle.await()
        assertThat(sourceChange.await()).isEqualTo(11L)

        assertThat(staleEffects).isEmpty()
        assertThat(order).containsExactly(
            "old-final-fence",
            "old-uam",
            "old-target-manager",
            "old-actions",
            "old-alerts",
            "old-widget",
            "source-mutated",
            "source-accepted-11"
        ).inOrder()
    }

    @Test
    fun runtimeFingerprintMutationAfterOldFinalFenceWaitsUntilEveryOldClinicalEffectFinishes() = runTest {
        val mutex = Mutex()
        val oldFinalFence = CompletableDeferred<Unit>()
        val releaseOldCycle = CompletableDeferred<Unit>()
        val order = mutableListOf<String>()
        val staleEffects = mutableListOf<String>()
        var settingsRevision = 31L
        var confidenceThreshold = 0.55
        var activityEnabled = true

        val oldCycle = async {
            AutomationRepository.runNormalCycleIfIdleStatic(mutex) {
                order += "old-final-fence"
                oldFinalFence.complete(Unit)
                releaseOldCycle.await()
                listOf("uam", "target-manager", "actions", "alerts", "widget").forEach { effect ->
                    if (settingsRevision != 31L) staleEffects += effect
                    order += "old-$effect"
                }
            }
        }
        oldFinalFence.await()

        val settingsChange = async {
            AutomationRepository.applySensitivitySettingsChangeUnderCycleLeaseStatic(
                mutex = mutex,
                lockTimeoutMs = 1_000L,
                mutateSettings = {
                    settingsRevision = 32L
                    confidenceThreshold = 0.70
                    activityEnabled = false
                    order += "settings-mutated"
                    32L
                },
                runAcceptedCycle = { revision ->
                    order += "settings-accepted-$revision"
                    revision
                }
            )
        }
        runCurrent()

        assertThat(settingsRevision).isEqualTo(31L)
        assertThat(confidenceThreshold).isEqualTo(0.55)
        assertThat(activityEnabled).isTrue()
        releaseOldCycle.complete(Unit)
        oldCycle.await()
        assertThat(settingsChange.await()).isEqualTo(32L)

        assertThat(staleEffects).isEmpty()
        assertThat(order).containsExactly(
            "old-final-fence",
            "old-uam",
            "old-target-manager",
            "old-actions",
            "old-alerts",
            "old-widget",
            "settings-mutated",
            "settings-accepted-32"
        ).inOrder()
    }

    @Test
    fun physiologicalSexMutationAfterOldFinalFenceUsesTheSameCycleLease() = runTest {
        val mutex = Mutex()
        val oldFinalFence = CompletableDeferred<Unit>()
        val releaseOldCycle = CompletableDeferred<Unit>()
        val order = mutableListOf<String>()
        var sex = "UNSPECIFIED"

        val oldCycle = async {
            AutomationRepository.runNormalCycleIfIdleStatic(mutex) {
                oldFinalFence.complete(Unit)
                releaseOldCycle.await()
                order += "old-clinical-effects"
            }
        }
        oldFinalFence.await()
        val mutation = async {
            AutomationRepository.applySensitivitySettingsChangeUnderCycleLeaseStatic(
                mutex = mutex,
                lockTimeoutMs = 1_000L,
                mutateSettings = { sex = "FEMALE"; order += "sex-mutated"; Unit },
                runAcceptedCycle = { order += "sex-accepted" }
            )
        }
        runCurrent()

        assertThat(sex).isEqualTo("UNSPECIFIED")
        releaseOldCycle.complete(Unit)
        oldCycle.await()
        mutation.await()

        assertThat(order).containsExactly(
            "old-clinical-effects",
            "sex-mutated",
            "sex-accepted"
        ).inOrder()
    }

    @Test
    fun overlappingSourceMutationsRunAndAcceptSequentiallyOnOneLease() = runTest {
        val mutex = Mutex()
        val firstEntered = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val order = mutableListOf<String>()

        val first = async {
            AutomationRepository.applySensitivitySourceChangeUnderCycleLeaseStatic(
                mutex = mutex,
                lockTimeoutMs = 1_000L,
                mutateSource = { order += "first-mutate"; "first" },
                runAcceptedCycle = { mutation ->
                    firstEntered.complete(Unit)
                    releaseFirst.await()
                    order += "first-accept"
                    mutation
                }
            )
        }
        firstEntered.await()
        val second = async {
            AutomationRepository.applySensitivitySourceChangeUnderCycleLeaseStatic(
                mutex = mutex,
                lockTimeoutMs = 1_000L,
                mutateSource = { order += "second-mutate"; "second" },
                runAcceptedCycle = { mutation -> order += "second-accept"; mutation }
            )
        }
        runCurrent()

        assertThat(order).containsExactly("first-mutate")
        releaseFirst.complete(Unit)

        assertThat(first.await()).isEqualTo("first")
        assertThat(second.await()).isEqualTo("second")
        assertThat(order).containsExactly(
            "first-mutate",
            "first-accept",
            "second-mutate",
            "second-accept"
        ).inOrder()
    }

    @Test
    fun failedSourceRecomputeKeepsForwardAuthoritativeMutationAndOldTupleIsRevisionStale() = runTest {
        val mutex = Mutex()
        val oldAccepted = testSensitivityRuntimeSnapshot(
            settingsRevision = 20L,
            cycleId = "old-accepted"
        )
        val currentAccepted = oldAccepted
        var settingsRevision = 20L
        var source = "EVIDENCE"
        val order = mutableListOf<String>()

        val failure = runCatching {
            AutomationRepository.applySensitivitySourceChangeUnderCycleLeaseStatic(
                mutex = mutex,
                lockTimeoutMs = 1_000L,
                mutateSource = {
                    source = "AAPS"
                    settingsRevision = 21L
                    order += "mutate"
                    Unit
                },
                runAcceptedCycle = {
                    order += "recompute"
                    error("forecast acceptance failed")
                }
            )
        }.exceptionOrNull()

        assertThat(failure).hasMessageThat().contains("forecast acceptance failed")
        assertThat(order).containsExactly("mutate", "recompute").inOrder()
        assertThat(source).isEqualTo("AAPS")
        assertThat(settingsRevision).isEqualTo(21L)
        assertThat(currentAccepted === oldAccepted).isTrue()
        assertThat(currentAccepted.settingsRevision).isNotEqualTo(settingsRevision)
        assertThat(mutex.isLocked).isFalse()
    }

    @Test
    fun cancellationAfterSourceMutationKeepsForwardSettingAndReleasesLease() = runTest {
        val mutex = Mutex()
        val cycleEntered = CompletableDeferred<Unit>()
        var authoritativeSource = false
        val sourceChange = launch {
            AutomationRepository.applySensitivitySourceChangeUnderCycleLeaseStatic(
                mutex = mutex,
                lockTimeoutMs = 1_000L,
                mutateSource = {
                    authoritativeSource = true
                    Unit
                },
                runAcceptedCycle = {
                    cycleEntered.complete(Unit)
                    awaitCancellation()
                }
            )
        }
        cycleEntered.await()

        sourceChange.cancelAndJoin()

        assertThat(authoritativeSource).isTrue()
        assertThat(mutex.isLocked).isFalse()
        assertThat(AutomationRepository.runNormalCycleIfIdleStatic(mutex) { "next" }.value)
            .isEqualTo("next")
    }

    @Test
    fun sameValueRetryRunsAcceptedCycleWithoutRevisionChurnOrRecoveryWrites() = runTest {
        val mutex = Mutex()
        var settingsRevision = 50L
        var acceptedCycles = 0

        val result = AutomationRepository.applySensitivitySettingsChangeUnderCycleLeaseStatic(
            mutex = mutex,
            lockTimeoutMs = 1_000L,
            mutateSettings = { settingsRevision },
            runAcceptedCycle = { revision ->
                acceptedCycles += 1
                revision
            }
        )

        assertThat(result).isEqualTo(50L)
        assertThat(acceptedCycles).isEqualTo(1)
        assertThat(settingsRevision).isEqualTo(50L)
        assertThat(mutex.isLocked).isFalse()
        assertThat(AutomationRepository.runNormalCycleIfIdleStatic(mutex) { "next" }.value)
            .isEqualTo("next")
    }

    @Test
    fun sourceChangeTimeoutBoundsLeaseWaitButNotAcceptedCycleExecution() = runTest {
        val mutex = Mutex()

        val result = AutomationRepository.applySensitivitySettingsChangeUnderCycleLeaseStatic(
            mutex = mutex,
            lockTimeoutMs = 10L,
            mutateSettings = { "mutation" },
            runAcceptedCycle = { mutation ->
                delay(100L)
                "$mutation-accepted"
            }
        )

        assertThat(result).isEqualTo("mutation-accepted")
        assertThat(mutex.isLocked).isFalse()
    }

    @Test
    fun timedOutNormalOwnerReleasesStableMutexExactlyOnce() = runTest {
        val mutex = Mutex()

        val failure = runCatching {
            AutomationRepository.runNormalCycleIfIdleStatic(mutex) {
                withTimeout(100L) { awaitCancellation() }
            }
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(TimeoutCancellationException::class.java)
        assertThat(mutex.isLocked).isFalse()
        assertThat(AutomationRepository.runNormalCycleIfIdleStatic(mutex) { "next" }.value)
            .isEqualTo("next")
        assertThat(mutex.isLocked).isFalse()
    }

    @Test
    fun cancellationOfAcquiredSourceCycleReleasesStableMutexExactlyOnce() = runTest {
        val mutex = Mutex()
        val entered = CompletableDeferred<Unit>()
        val sourceChange = launch {
            AutomationRepository.runSerializedSensitivitySourceChangeStatic(
                mutex = mutex,
                lockTimeoutMs = 1_000L
            ) {
                entered.complete(Unit)
                awaitCancellation()
            }
        }

        entered.await()
        assertThat(mutex.isLocked).isTrue()
        sourceChange.cancelAndJoin()
        assertThat(mutex.isLocked).isFalse()

        val next = AutomationRepository.runNormalCycleIfIdleStatic(mutex) { "next" }
        assertThat(next.acquired).isTrue()
        assertThat(next.value).isEqualTo("next")
        assertThat(mutex.isLocked).isFalse()
    }

    @Test
    fun sourceChangeLeaseTimeoutDoesNotCancelExecutionAfterAcquisition() = runTest {
        val mutex = Mutex()
        val entered = CompletableDeferred<Unit>()
        val timedOut = async {
            runCatching {
                AutomationRepository.runSerializedSensitivitySourceChangeStatic(
                    mutex = mutex,
                    lockTimeoutMs = 100L
                ) {
                    entered.complete(Unit)
                    awaitCancellation()
                }
            }.exceptionOrNull()
        }

        entered.await()
        assertThat(mutex.isLocked).isTrue()
        advanceTimeBy(101L)
        runCurrent()

        assertThat(timedOut.isActive).isTrue()
        timedOut.cancelAndJoin()
        assertThat(mutex.isLocked).isFalse()

        val normal = AutomationRepository.runNormalCycleIfIdleStatic(mutex) { "normal" }
        assertThat(normal.acquired).isTrue()
        assertThat(normal.value).isEqualTo("normal")
        assertThat(
            AutomationRepository.runSerializedSensitivitySourceChangeStatic(
                mutex = mutex,
                lockTimeoutMs = 100L
            ) { "source" }
        ).isEqualTo("source")
        assertThat(mutex.isLocked).isFalse()
    }

    @Test
    fun normalCycleStillCalculatesDiagnosticsWhenEveryWriterGateIsClosed() {
        listOf(
            normalPolicy(armed = false, killSwitch = false, powerSave = false),
            normalPolicy(armed = true, killSwitch = true, powerSave = false),
            normalPolicy(armed = true, killSwitch = false, powerSave = true)
        ).forEach { policy ->
            assertThat(policy.runCalculations).isTrue()
            assertThat(policy.therapyWritersAllowed).isFalse()
        }
    }

    @Test
    fun acceptedForecastTimestampIsTheExactSharedGenerationTime() {
        val forecasts = listOf(5, 30, 60).map { horizon ->
            Forecast(
                ts = GENERATION_TS + horizon * 60_000L,
                horizonMinutes = horizon,
                valueMmol = 6.0,
                ciLow = 5.0,
                ciHigh = 7.0,
                modelVersion = "test"
            )
        }

        assertThat(
            AutomationRepository.resolveAcceptedForecastTimestampStatic(forecasts)
        ).isEqualTo(GENERATION_TS)
    }

    private fun normalPolicy(armed: Boolean, killSwitch: Boolean, powerSave: Boolean) =
        AutomationRepository.resolveCyclePolicyStatic(
            intent = AutomationRepository.AutomationCycleIntent.NORMAL,
            therapyActionsArmed = armed,
            killSwitch = killSwitch,
            powerSaveActive = powerSave
        )

    private companion object {
        const val GENERATION_TS = 1_786_435_200_000L
    }
}

private class DirectFatalThrowable(message: String) : Throwable(message)
