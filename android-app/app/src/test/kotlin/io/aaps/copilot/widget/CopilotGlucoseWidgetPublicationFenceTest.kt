package io.aaps.copilot.widget

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Test
import java.util.concurrent.CountDownLatch

class CopilotGlucoseWidgetPublicationFenceTest {

    @Test
    fun failureDiagnosticContainsOnlyBoundedTypeAndNoClinicalMessage() {
        val diagnostic = copilotGlucoseWidgetPublicationFailureDiagnostic(
            IllegalStateException("glucose=12.4 patient-context")
        )

        assertThat(diagnostic).contains("IllegalStateException")
        assertThat(diagnostic).doesNotContain("12.4")
        assertThat(diagnostic).doesNotContain("patient-context")
        assertThat(diagnostic.length).isAtMost(120)
    }

    @Test
    fun awaitedFailureIsSanitizedAndNotReportedCentrally() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val reported = mutableListOf<CopilotGlucoseWidgetPublicationFailure>()
            val coordinator = CopilotGlucoseWidgetPublicationCoordinator(
                scope = scope,
                onFailure = { reported += it }
            )
            val privateFailureMessage = "glucose=12.4 patient-context widget binder failed"
            val receipt = coordinator.enqueue<String>(
                failureReporting = CopilotGlucoseWidgetFailureReporting.AWAITED,
                load = { throw IllegalStateException(privateFailureMessage) },
                publish = {}
            )

            val result = withTimeout(2_000L) { receipt.await() }
            assertThat(result).isInstanceOf(CopilotGlucoseWidgetPublicationResult.Failed::class.java)
            val failure = (result as CopilotGlucoseWidgetPublicationResult.Failed).failure
            assertThat(failure.errorCode).isEqualTo("WIDGET_PUBLICATION_FAILED")
            assertThat(failure.errorType).isEqualTo("IllegalStateException")

            val exception = failure.toAwaitedException()
            assertThat(exception.message)
                .isEqualTo("WIDGET_PUBLICATION_FAILED:IllegalStateException")
            assertThat(exception.message).doesNotContain(privateFailureMessage)
            assertThat(exception.cause).isNull()
            assertThat(reported).isEmpty()
            Unit
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun timedOutResetWaitDoesNotDropRawOrAllowOlderActivePublication() = runBlocking {
        verifyTimedOutLatestRequest(initialState = "ACTIVE", latestState = "RAW")
    }

    @Test
    fun timedOutAddWaitDoesNotDropActiveOrAllowOlderRawPublication() = runBlocking {
        verifyTimedOutLatestRequest(initialState = "RAW", latestState = "ACTIVE")
    }

    @Test
    fun newerGenerationCannotBeAcceptedInsideOlderSynchronousPublicationBatch() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val releaseOldPublication = CountDownLatch(1)
        try {
            val coordinator = CopilotGlucoseWidgetPublicationCoordinator(scope)
            val oldPublicationEntered = CompletableDeferred<Unit>()
            val published = mutableListOf<String>()
            val oldReceipt = coordinator.enqueue(
                load = { "OLD" },
                publish = { value ->
                    published += "$value-1"
                    oldPublicationEntered.complete(Unit)
                    releaseOldPublication.await()
                    published += "$value-2"
                }
            )
            oldPublicationEntered.await()
            val newerEnqueueStarted = CompletableDeferred<Unit>()
            val newerEnqueue = async(Dispatchers.Default) {
                newerEnqueueStarted.complete(Unit)
                coordinator.enqueue(
                    load = { "NEW" },
                    publish = { value ->
                        published += "$value-1"
                        published += "$value-2"
                    }
                )
            }
            newerEnqueueStarted.await()
            delay(50L)
            val acceptedBeforeOldPublicationFinished = newerEnqueue.isCompleted

            releaseOldPublication.countDown()
            val newReceipt = withTimeout(2_000L) { newerEnqueue.await() }

            assertThat(acceptedBeforeOldPublicationFinished).isFalse()
            assertThat(withTimeout(2_000L) { oldReceipt.await() })
                .isEqualTo(CopilotGlucoseWidgetPublicationResult.Published)
            assertThat(withTimeout(2_000L) { newReceipt.await() })
                .isEqualTo(CopilotGlucoseWidgetPublicationResult.Published)
            assertThat(published)
                .containsExactly("OLD-1", "OLD-2", "NEW-1", "NEW-2")
                .inOrder()
        } finally {
            releaseOldPublication.countDown()
            scope.cancel()
        }
    }

    @Test
    fun threeQueuedRequestsCoalesceToOneLatestLoadAndCompleteReceipts() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val coordinator = CopilotGlucoseWidgetPublicationCoordinator(scope)
            val holderLoaded = CompletableDeferred<Unit>()
            val releaseHolder = CompletableDeferred<Unit>()
            val published = mutableListOf<String>()
            val holder = coordinator.enqueue(
                load = {
                    holderLoaded.complete(Unit)
                    releaseHolder.await()
                    "OLD"
                },
                publish = { published += it }
            )
            holderLoaded.await()
            var firstLoadCount = 0
            var secondLoadCount = 0
            var latestLoadCount = 0

            val first = coordinator.enqueue(
                load = { firstLoadCount += 1; "FIRST" },
                publish = { published += it }
            )
            val second = coordinator.enqueue(
                load = { secondLoadCount += 1; "SECOND" },
                publish = { published += it }
            )
            val latest = coordinator.enqueue(
                load = { latestLoadCount += 1; "LATEST" },
                publish = { published += it }
            )

            assertThat(withTimeout(2_000L) { first.await() })
                .isEqualTo(CopilotGlucoseWidgetPublicationResult.Superseded)
            assertThat(withTimeout(2_000L) { second.await() })
                .isEqualTo(CopilotGlucoseWidgetPublicationResult.Superseded)
            assertThat(withTimeout(2_000L) { holder.await() })
                .isEqualTo(CopilotGlucoseWidgetPublicationResult.Superseded)
            releaseHolder.complete(Unit)
            assertThat(withTimeout(2_000L) { latest.await() })
                .isEqualTo(CopilotGlucoseWidgetPublicationResult.Published)
            assertThat(firstLoadCount).isEqualTo(0)
            assertThat(secondLoadCount).isEqualTo(0)
            assertThat(latestLoadCount).isEqualTo(1)
            assertThat(published).containsExactly("LATEST")
            Unit
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun ordinaryLoadAndPublishFailuresDoNotStopLaterPublication() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val coordinator = CopilotGlucoseWidgetPublicationCoordinator(scope)
            val loadFailure = IllegalStateException("load failed")
            val failedLoad = coordinator.enqueue<String>(
                load = { throw loadFailure },
                publish = {}
            )
            val loadResult = withTimeout(2_000L) { failedLoad.await() }

            assertThat(loadResult).isInstanceOf(CopilotGlucoseWidgetPublicationResult.Failed::class.java)
            assertThat((loadResult as CopilotGlucoseWidgetPublicationResult.Failed).failure)
                .isEqualTo(
                    CopilotGlucoseWidgetPublicationFailure(
                        errorCode = "WIDGET_PUBLICATION_FAILED",
                        errorType = "IllegalStateException"
                    )
                )

            val publishFailure = IllegalStateException("publish failed")
            val failedPublish = coordinator.enqueue(
                load = { "FAILED_PUBLISH" },
                publish = { throw publishFailure }
            )
            val publishResult = withTimeout(2_000L) { failedPublish.await() }

            assertThat(publishResult).isInstanceOf(CopilotGlucoseWidgetPublicationResult.Failed::class.java)
            assertThat((publishResult as CopilotGlucoseWidgetPublicationResult.Failed).failure)
                .isEqualTo(
                    CopilotGlucoseWidgetPublicationFailure(
                        errorCode = "WIDGET_PUBLICATION_FAILED",
                        errorType = "IllegalStateException"
                    )
                )

            val published = mutableListOf<String>()
            val recovered = coordinator.enqueue(
                load = { "RECOVERED" },
                publish = { published += it }
            )

            assertThat(withTimeout(2_000L) { recovered.await() })
                .isEqualTo(CopilotGlucoseWidgetPublicationResult.Published)
            assertThat(published).containsExactly("RECOVERED")
            Unit
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun centralizedObserverReportsFailedOnceAndKeepsPublishedSupersededSilent() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val reported = mutableListOf<CopilotGlucoseWidgetPublicationFailure>()
            val failureReported = CompletableDeferred<Unit>()
            val coordinator = CopilotGlucoseWidgetPublicationCoordinator(
                scope = scope,
                onFailure = {
                    reported += it
                    failureReported.complete(Unit)
                }
            )
            val holderLoaded = CompletableDeferred<Unit>()
            val releaseHolder = CompletableDeferred<Unit>()
            val holder = coordinator.enqueue(
                load = {
                    holderLoaded.complete(Unit)
                    releaseHolder.await()
                    "HOLDER"
                },
                publish = {}
            )
            holderLoaded.await()
            val superseded = coordinator.enqueue(
                load = { "SUPERSEDED" },
                publish = {}
            )
            val failure = IllegalStateException("private clinical context must not be logged")
            val failed = coordinator.enqueue<String>(
                failureReporting = CopilotGlucoseWidgetFailureReporting.FIRE_AND_FORGET,
                load = { throw failure },
                publish = {}
            )

            assertThat(withTimeout(2_000L) { holder.await() })
                .isEqualTo(CopilotGlucoseWidgetPublicationResult.Superseded)
            assertThat(withTimeout(2_000L) { superseded.await() })
                .isEqualTo(CopilotGlucoseWidgetPublicationResult.Superseded)
            releaseHolder.complete(Unit)
            val failedResult = withTimeout(2_000L) { failed.await() }

            assertThat(failedResult).isInstanceOf(CopilotGlucoseWidgetPublicationResult.Failed::class.java)
            // The receipt completes before the asynchronous diagnostic callback.
            withTimeout(2_000L) { failureReported.await() }
            assertThat(reported).containsExactly(
                CopilotGlucoseWidgetPublicationFailure(
                    errorCode = "WIDGET_PUBLICATION_FAILED",
                    errorType = "IllegalStateException"
                )
            )

            val published = coordinator.enqueue(
                load = { "PUBLISHED" },
                publish = {}
            )
            assertThat(withTimeout(2_000L) { published.await() })
                .isEqualTo(CopilotGlucoseWidgetPublicationResult.Published)
            assertThat(reported).hasSize(1)
            Unit
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun supersededOperationalFailureDoesNotEmitFailureDiagnostic() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val reported = mutableListOf<CopilotGlucoseWidgetPublicationFailure>()
            val coordinator = CopilotGlucoseWidgetPublicationCoordinator(
                scope = scope,
                onFailure = { reported += it }
            )
            val staleLoaded = CompletableDeferred<Unit>()
            val releaseStaleFailure = CompletableDeferred<Unit>()
            val stale = coordinator.enqueue<String>(
                failureReporting = CopilotGlucoseWidgetFailureReporting.FIRE_AND_FORGET,
                load = {
                    staleLoaded.complete(Unit)
                    releaseStaleFailure.await()
                    throw IllegalStateException("superseded failure")
                },
                publish = {}
            )
            staleLoaded.await()
            val latest = coordinator.enqueue(
                load = { "LATEST" },
                publish = {}
            )

            assertThat(withTimeout(2_000L) { stale.await() })
                .isEqualTo(CopilotGlucoseWidgetPublicationResult.Superseded)
            releaseStaleFailure.complete(Unit)
            assertThat(withTimeout(2_000L) { latest.await() })
                .isEqualTo(CopilotGlucoseWidgetPublicationResult.Published)
            assertThat(reported).isEmpty()
            Unit
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun diagnosticSinkThrowableCannotWedgeLaterPublication() = runBlocking {
        listOf(
            IllegalStateException("diagnostic exception"),
            CancellationException("diagnostic cancellation"),
            LinkageError("diagnostic linkage"),
            DirectWidgetPublicationThrowable("diagnostic direct throwable")
        ).forEach { sinkFailure ->
            verifyDiagnosticSinkFailureDoesNotWedge(sinkFailure)
        }
    }

    @Test
    fun scopeCancellationCompletesPendingReceiptAndDoesNotReportFailure() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val reported = mutableListOf<CopilotGlucoseWidgetPublicationFailure>()
        val coordinator = CopilotGlucoseWidgetPublicationCoordinator(
            scope = scope,
            onFailure = { reported += it }
        )
        val activeLoaded = CompletableDeferred<Unit>()
        val active = coordinator.enqueue<String>(
            load = {
                activeLoaded.complete(Unit)
                awaitCancellation()
            },
            publish = {}
        )
        activeLoaded.await()
        val pending = coordinator.enqueue(
            load = { "PENDING" },
            publish = {}
        )

        scope.cancel(CancellationException("coordinator stopped"))

        assertThat(withTimeout(2_000L) { active.await() })
            .isEqualTo(CopilotGlucoseWidgetPublicationResult.Superseded)
        val pendingFailure = try {
            withTimeout(2_000L) { pending.await() }
            null
        } catch (failure: Throwable) {
            failure
        }
        assertThat(pendingFailure).isInstanceOf(CancellationException::class.java)
        assertThat(reported).isEmpty()
    }

    @Test
    fun directFatalRestartsDrainForAlreadyAcceptedLatestRequest() = runBlocking {
        val fatal = DirectWidgetPublicationThrowable("fatal publication boundary")
        val observedFatal = CompletableDeferred<Throwable>()
        val exceptionHandler = CoroutineExceptionHandler { _, failure ->
            observedFatal.complete(failure)
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + exceptionHandler)
        try {
            val coordinator = CopilotGlucoseWidgetPublicationCoordinator(scope)
            val fatalLoaded = CompletableDeferred<Unit>()
            val releaseFatal = CompletableDeferred<Unit>()
            val fatalReceipt = coordinator.enqueue<String>(
                load = {
                    fatalLoaded.complete(Unit)
                    releaseFatal.await()
                    throw fatal
                },
                publish = {}
            )
            fatalLoaded.await()
            val published = mutableListOf<String>()
            val latest = coordinator.enqueue(
                load = { "LATEST" },
                publish = { published += it }
            )

            assertThat(withTimeout(2_000L) { fatalReceipt.await() })
                .isEqualTo(CopilotGlucoseWidgetPublicationResult.Superseded)
            releaseFatal.complete(Unit)

            assertThat(withTimeout(2_000L) { observedFatal.await() }).isSameInstanceAs(fatal)
            assertThat(withTimeout(2_000L) { latest.await() })
                .isEqualTo(CopilotGlucoseWidgetPublicationResult.Published)
            assertThat(published).containsExactly("LATEST")
            Unit
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun clinicalPublishDirectThrowableSurfacesAndCoordinatorRestarts() = runBlocking {
        val fatal = DirectWidgetPublicationThrowable("fatal clinical publication boundary")
        val observedFatal = CompletableDeferred<Throwable>()
        val exceptionHandler = CoroutineExceptionHandler { _, failure ->
            observedFatal.complete(failure)
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + exceptionHandler)
        try {
            val coordinator = CopilotGlucoseWidgetPublicationCoordinator(scope)
            val failed = coordinator.enqueue(
                load = { "CLINICAL" },
                publish = { throw fatal }
            )

            val receiptFailure = try {
                withTimeout(2_000L) { failed.await() }
                null
            } catch (failure: Throwable) {
                failure
            }
            assertThat(receiptFailure).isSameInstanceAs(fatal)
            assertThat(withTimeout(2_000L) { observedFatal.await() }).isSameInstanceAs(fatal)

            val published = mutableListOf<String>()
            val later = coordinator.enqueue(
                load = { "LATER" },
                publish = { published += it }
            )
            assertThat(withTimeout(2_000L) { later.await() })
                .isEqualTo(CopilotGlucoseWidgetPublicationResult.Published)
            assertThat(published).containsExactly("LATER")
            Unit
        } finally {
            scope.cancel()
        }
    }

    private suspend fun verifyTimedOutLatestRequest(
        initialState: String,
        latestState: String
    ) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val coordinator = CopilotGlucoseWidgetPublicationCoordinator(scope)
            var durableState = initialState
            val oldLoaded = CompletableDeferred<Unit>()
            val releaseOld = CompletableDeferred<Unit>()
            val published = mutableListOf<String>()
            val oldReceipt = coordinator.enqueue(
                load = {
                    durableState.also {
                        oldLoaded.complete(Unit)
                        releaseOld.await()
                    }
                },
                publish = { published += it }
            )
            oldLoaded.await()

            durableState = latestState
            val latestReceipt = coordinator.enqueue(
                load = { durableState },
                publish = { published += it }
            )
            val callerResult = withTimeoutOrNull(25L) { latestReceipt.await() }

            assertThat(callerResult).isNull()
            assertThat(withTimeout(2_000L) { oldReceipt.await() })
                .isEqualTo(CopilotGlucoseWidgetPublicationResult.Superseded)
            releaseOld.complete(Unit)
            assertThat(withTimeout(2_000L) { latestReceipt.await() })
                .isEqualTo(CopilotGlucoseWidgetPublicationResult.Published)
            assertThat(published).containsExactly(latestState)
        } finally {
            scope.cancel()
        }
    }

    private suspend fun verifyDiagnosticSinkFailureDoesNotWedge(sinkFailure: Throwable) {
        val exceptionHandler = CoroutineExceptionHandler { _, _ -> }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + exceptionHandler)
        val releaseDiagnosticSink = CountDownLatch(1)
        try {
            val diagnosticSinkEntered = CompletableDeferred<Unit>()
            val coordinator = CopilotGlucoseWidgetPublicationCoordinator(
                scope = scope,
                onFailure = {
                    diagnosticSinkEntered.complete(Unit)
                    releaseDiagnosticSink.await()
                    throw sinkFailure
                }
            )
            val failed = coordinator.enqueue<String>(
                failureReporting = CopilotGlucoseWidgetFailureReporting.FIRE_AND_FORGET,
                load = { throw IllegalStateException("clinical-looking diagnostic payload") },
                publish = {}
            )

            assertThat(withTimeout(2_000L) { failed.await() })
                .isInstanceOf(CopilotGlucoseWidgetPublicationResult.Failed::class.java)
            diagnosticSinkEntered.await()

            val published = mutableListOf<String>()
            val later = coordinator.enqueue(
                load = { "LATER" },
                publish = { published += it }
            )
            releaseDiagnosticSink.countDown()

            assertThat(withTimeout(2_000L) { later.await() })
                .isEqualTo(CopilotGlucoseWidgetPublicationResult.Published)
            assertThat(published).containsExactly("LATER")
        } finally {
            releaseDiagnosticSink.countDown()
            scope.cancel()
        }
    }
}

private class DirectWidgetPublicationThrowable(message: String) : Throwable(message)
