package io.aaps.copilot.ui.foundation

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import java.io.IOException
import java.util.concurrent.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class AlertNavigationCoordinatorTest {
    @Test
    fun fifoPreservesDuplicateCandidatesAndBlocksNextValidationUntilAcknowledged() = runTest {
        val releaseFirst = CompletableDeferred<Unit>()
        val validated = mutableListOf<String?>()
        val owner = SupervisorJob()
        val coordinator = AlertNavigationCoordinator(
            CoroutineScope(owner + UnconfinedTestDispatcher(testScheduler))
        ) { candidate ->
            validated += candidate
            if (candidate == "first") releaseFirst.await()
            candidate
        }

        assertThat(coordinator.enqueue("first")).isTrue()
        assertThat(coordinator.enqueue("second")).isTrue()
        assertThat(coordinator.enqueue("second")).isTrue()
        advanceUntilIdle()
        assertThat(validated).containsExactly("first")
        assertThat(coordinator.pendingRequest.value).isNull()

        releaseFirst.complete(Unit)
        advanceUntilIdle()
        val first = requireNotNull(coordinator.pendingRequest.value)
        assertThat(first.episodeId).isEqualTo("first")
        assertThat(validated).containsExactly("first")

        assertThat(coordinator.acknowledge(first.token)).isTrue()
        advanceUntilIdle()
        val second = requireNotNull(coordinator.pendingRequest.value)
        assertThat(second.episodeId).isEqualTo("second")
        assertThat(second.token).isGreaterThan(first.token)
        assertThat(validated).containsExactly("first", "second").inOrder()

        assertThat(coordinator.acknowledge(second.token)).isTrue()
        advanceUntilIdle()
        val duplicate = requireNotNull(coordinator.pendingRequest.value)
        assertThat(duplicate.episodeId).isEqualTo("second")
        assertThat(duplicate.token).isGreaterThan(second.token)
        assertThat(validated).containsExactly("first", "second", "second").inOrder()
        assertThat(coordinator.acknowledge(duplicate.token)).isTrue()
        owner.cancel()
    }

    @Test
    fun coldSubscriberAndRecreatedCollectorObserveSamePendingTokenUntilAck() = runTest {
        val owner = SupervisorJob()
        val coordinator = AlertNavigationCoordinator(
            CoroutineScope(owner + UnconfinedTestDispatcher(testScheduler))
        ) { it }
        coordinator.enqueue("glucose-alert-1")
        advanceUntilIdle()

        val first = coordinator.pendingRequest.filterNotNull().first()
        val afterRecreation = coordinator.pendingRequest.filterNotNull().first()

        assertThat(afterRecreation).isEqualTo(first)
        assertThat(coordinator.pendingRequest.value).isEqualTo(first)
        assertThat(coordinator.acknowledge(first.token + 1L)).isFalse()
        assertThat(coordinator.pendingRequest.value).isEqualTo(first)
        assertThat(coordinator.acknowledge(first.token)).isTrue()
        owner.cancel()
    }

    @Test
    fun ordinaryValidationFailureBecomesAcknowledgedHistoryOnlyRequest() = runTest {
        val owner = SupervisorJob()
        val failures = mutableListOf<AlertNavigationFailureCode>()
        val coordinator = AlertNavigationCoordinator(
            scope = CoroutineScope(owner + UnconfinedTestDispatcher(testScheduler)),
            onValidationFailure = failures::add
        ) { candidate ->
            if (candidate == "broken") throw IOException("room unavailable")
            candidate
        }
        coordinator.enqueue("broken")
        advanceUntilIdle()

        val request = requireNotNull(coordinator.pendingRequest.value)
        assertThat(request.episodeId).isNull()
        assertThat(failures).containsExactly(AlertNavigationFailureCode.VALIDATION_EXCEPTION)
        assertThat(coordinator.acknowledge(request.token)).isTrue()
        advanceUntilIdle()
        assertThat(coordinator.pendingRequest.value).isNull()
        owner.cancel()
    }

    @Test
    fun unknownIdDoesNotReportValidationFailure() = runTest {
        val owner = SupervisorJob()
        val failures = mutableListOf<AlertNavigationFailureCode>()
        val coordinator = AlertNavigationCoordinator(
            scope = CoroutineScope(owner + UnconfinedTestDispatcher(testScheduler)),
            onValidationFailure = failures::add
        ) { null }

        assertThat(coordinator.enqueue("unknown")).isTrue()
        advanceUntilIdle()

        assertThat(coordinator.pendingRequest.value?.episodeId).isNull()
        assertThat(failures).isEmpty()
        owner.cancel()
    }

    @Test
    fun ordinaryCallbackFailureIsIsolatedFromHistoryOnlyNavigation() = runTest {
        val owner = SupervisorJob()
        var callbacks = 0
        val coordinator = AlertNavigationCoordinator(
            scope = CoroutineScope(owner + UnconfinedTestDispatcher(testScheduler)),
            onValidationFailure = {
                callbacks += 1
                throw IOException("local sink unavailable")
            }
        ) { throw IOException("room unavailable") }

        assertThat(coordinator.enqueue("broken")).isTrue()
        advanceUntilIdle()

        assertThat(callbacks).isEqualTo(1)
        assertThat(coordinator.pendingRequest.value?.episodeId).isNull()
        owner.cancel()
    }

    @Test
    fun callbackCancellationAndFatalErrorClosePipeline() = runTest {
        val cancellationScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val cancelledCoordinator = AlertNavigationCoordinator(
            scope = cancellationScope,
            onValidationFailure = { throw CancellationException("sink cancelled") }
        ) { throw IOException("room unavailable") }
        assertThat(cancelledCoordinator.enqueue("cancelled-callback")).isTrue()
        advanceUntilIdle()
        assertThat(cancelledCoordinator.pendingRequest.value).isNull()
        assertThat(cancelledCoordinator.enqueue("after-cancelled-callback")).isFalse()

        val captured = mutableListOf<Throwable>()
        val fatalScope = CoroutineScope(
            SupervisorJob() + StandardTestDispatcher(testScheduler) +
                CoroutineExceptionHandler { _, throwable -> captured += throwable }
        )
        val fatal = AssertionError("fatal sink")
        val fatalCoordinator = AlertNavigationCoordinator(
            scope = fatalScope,
            onValidationFailure = { throw fatal }
        ) { throw IOException("room unavailable") }
        assertThat(fatalCoordinator.enqueue("fatal-callback")).isTrue()
        advanceUntilIdle()
        assertThat(captured).containsExactly(fatal)
        assertThat(fatalCoordinator.pendingRequest.value).isNull()
        assertThat(fatalCoordinator.enqueue("after-fatal-callback")).isFalse()
        cancellationScope.coroutineContext[Job]?.cancel()
        fatalScope.coroutineContext[Job]?.cancel()
    }

    @Test
    fun boundedQueueRejectsSaturationAndRecoversAfterAcknowledgement() = runTest {
        val releaseValidation = CompletableDeferred<Unit>()
        val owner = SupervisorJob()
        val coordinator = AlertNavigationCoordinator(
            CoroutineScope(owner + UnconfinedTestDispatcher(testScheduler))
        ) { candidate ->
            if (candidate == "first") releaseValidation.await()
            candidate
        }

        assertThat(coordinator.enqueue("first")).isTrue()
        advanceUntilIdle()
        repeat(8) {
            assertWithMessage("queue slot $it").that(coordinator.enqueue("duplicate")).isTrue()
        }
        assertThat(coordinator.enqueue("saturated")).isFalse()

        releaseValidation.complete(Unit)
        advanceUntilIdle()
        val first = requireNotNull(coordinator.pendingRequest.value)
        assertThat(first.episodeId).isEqualTo("first")
        assertThat(coordinator.acknowledge(first.token)).isTrue()
        advanceUntilIdle()
        assertThat(coordinator.pendingRequest.value?.episodeId).isEqualTo("duplicate")
        assertThat(coordinator.enqueue("recovered")).isTrue()
        owner.cancel()
    }

    @Test
    fun cancellationAndFatalErrorClosePipelineInsteadOfBecomingHistory() = runTest {
        val captured = mutableListOf<Throwable>()
        val failures = mutableListOf<AlertNavigationFailureCode>()
        val fatalScope = CoroutineScope(
            SupervisorJob() + StandardTestDispatcher(testScheduler) +
                CoroutineExceptionHandler { _, throwable -> captured += throwable }
        )
        val fatal = AssertionError("fatal")
        val fatalCoordinator = AlertNavigationCoordinator(
            scope = fatalScope,
            onValidationFailure = failures::add
        ) { throw fatal }
        fatalCoordinator.enqueue("fatal")
        advanceUntilIdle()

        assertThat(captured).containsExactly(fatal)
        assertThat(fatalCoordinator.pendingRequest.value).isNull()
        assertThat(fatalCoordinator.enqueue("after-fatal")).isFalse()

        val cancellationScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val cancelledCoordinator = AlertNavigationCoordinator(
            scope = cancellationScope,
            onValidationFailure = failures::add
        ) {
            throw CancellationException("cancelled")
        }
        cancelledCoordinator.enqueue("cancelled")
        advanceUntilIdle()
        assertThat(cancelledCoordinator.pendingRequest.value).isNull()
        assertThat(cancelledCoordinator.enqueue("after-cancel")).isFalse()
        assertThat(failures).isEmpty()
        fatalScope.coroutineContext[Job]?.cancel()
        cancellationScope.coroutineContext[Job]?.cancel()
    }

    @Test
    fun owningScopeCompletionClosesInputAndClearsPendingRequest() = runTest {
        val owner = SupervisorJob()
        val scope = CoroutineScope(owner + StandardTestDispatcher(testScheduler))
        val coordinator = AlertNavigationCoordinator(scope) { it }
        coordinator.enqueue("pending")
        advanceUntilIdle()
        assertThat(coordinator.pendingRequest.value?.episodeId).isEqualTo("pending")

        owner.cancel()
        advanceUntilIdle()

        assertThat(coordinator.pendingRequest.value).isNull()
        assertThat(coordinator.enqueue("too-late")).isFalse()
    }
}
