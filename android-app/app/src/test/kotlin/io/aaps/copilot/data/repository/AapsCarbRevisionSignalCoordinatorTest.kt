package io.aaps.copilot.data.repository

import com.google.common.truth.Truth.assertThat
import java.util.concurrent.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Test

class AapsCarbRevisionSignalCoordinatorTest {

    @Test
    fun duplicateDuringInFlightFailureIsRetriedBySameWorker() = runTest {
        val coordinator = AapsCarbRevisionSignalCoordinator()
        var attempts = 0

        assertThat(coordinator.tryStart(40L)).isTrue()
        runAapsCarbRevisionSignalWorker(
            coordinator = coordinator,
            syncForRevision = { revisionId ->
                attempts += 1
                assertThat(revisionId).isEqualTo(40L)
                if (attempts == 1) {
                    assertThat(coordinator.tryStart(40L)).isFalse()
                    AapsCarbHistorySyncResult.Failed("busy", pages = 0, rows = 0)
                } else {
                    AapsCarbHistorySyncResult.Completed(40L, pages = 1, rows = 1)
                }
            },
            relaunch = { error("failure retry must stay on the current worker") }
        )

        assertThat(attempts).isEqualTo(2)
        assertThat(coordinator.tryStart(40L)).isFalse()
    }

    @Test
    fun duplicateDuringPageCapIsRetriedBySameWorker() = runTest {
        val coordinator = AapsCarbRevisionSignalCoordinator()
        var attempts = 0

        assertThat(coordinator.tryStart(41L)).isTrue()
        runAapsCarbRevisionSignalWorker(
            coordinator = coordinator,
            syncForRevision = { revisionId ->
                attempts += 1
                if (attempts == 1) {
                    assertThat(coordinator.tryStart(revisionId)).isFalse()
                    AapsCarbHistorySyncResult.PageCapReached(revisionId, pages = 64, rows = 100)
                } else {
                    AapsCarbHistorySyncResult.UpToDate(revisionId)
                }
            },
            relaunch = { error("page-cap retry must stay on the current worker") }
        )

        assertThat(attempts).isEqualTo(2)
    }

    @Test
    fun duplicateDuringExceptionTransfersPendingRetryAndRethrows() = runTest {
        val coordinator = AapsCarbRevisionSignalCoordinator()
        val failure = IllegalStateException("injected")
        var relaunches = 0

        assertThat(coordinator.tryStart(42L)).isTrue()
        val observed = runCatching {
            runAapsCarbRevisionSignalWorker(
                coordinator = coordinator,
                syncForRevision = { revisionId ->
                    assertThat(coordinator.tryStart(revisionId)).isFalse()
                    throw failure
                },
                relaunch = { relaunches += 1 }
            )
        }.exceptionOrNull()

        assertThat(observed).isSameInstanceAs(failure)
        assertThat(relaunches).isEqualTo(1)
        var retriedRevision: Long? = null
        runAapsCarbRevisionSignalWorker(
            coordinator = coordinator,
            syncForRevision = { revisionId ->
                retriedRevision = revisionId
                AapsCarbHistorySyncResult.Completed(revisionId, pages = 1, rows = 1)
            },
            relaunch = { error("replacement worker must complete") }
        )
        assertThat(retriedRevision).isEqualTo(42L)
    }

    @Test
    fun duplicateDuringCancellationTransfersPendingRetryAndRethrows() = runTest {
        val coordinator = AapsCarbRevisionSignalCoordinator()
        val cancellation = CancellationException("injected")
        var relaunches = 0

        assertThat(coordinator.tryStart(43L)).isTrue()
        val observed = runCatching {
            runAapsCarbRevisionSignalWorker(
                coordinator = coordinator,
                syncForRevision = { revisionId ->
                    assertThat(coordinator.tryStart(revisionId)).isFalse()
                    throw cancellation
                },
                relaunch = { relaunches += 1 }
            )
        }.exceptionOrNull()

        assertThat(observed).isSameInstanceAs(cancellation)
        assertThat(relaunches).isEqualTo(1)
        var retriedRevision: Long? = null
        runAapsCarbRevisionSignalWorker(
            coordinator = coordinator,
            syncForRevision = { revisionId ->
                retriedRevision = revisionId
                AapsCarbHistorySyncResult.UpToDate(revisionId)
            },
            relaunch = { error("replacement worker must complete") }
        )
        assertThat(retriedRevision).isEqualTo(43L)
    }

    @Test
    fun lowerSupersededRevisionIsObsoleteOnlyWhenHigherRevisionIsOwned() = runTest {
        val coordinator = AapsCarbRevisionSignalCoordinator()
        val attempts = mutableListOf<Long>()

        assertThat(coordinator.tryStart(50L)).isTrue()
        runAapsCarbRevisionSignalWorker(
            coordinator = coordinator,
            syncForRevision = { revisionId ->
                attempts += revisionId
                when (revisionId) {
                    50L -> {
                        assertThat(coordinator.tryStart(51L)).isFalse()
                        AapsCarbHistorySyncResult.Superseded(50L, 51L)
                    }
                    51L -> AapsCarbHistorySyncResult.Failed("busy", pages = 0, rows = 0)
                    else -> error("unexpected revision $revisionId")
                }
            },
            relaunch = { error("supersession must stay on the current worker") }
        )

        assertThat(attempts).containsExactly(50L, 51L).inOrder()
        assertThat(coordinator.tryStart(50L)).isFalse()
        assertThat(coordinator.tryStart(51L)).isTrue()

        val unscheduled = AapsCarbRevisionSignalCoordinator()
        assertThat(unscheduled.tryStart(60L)).isTrue()
        runAapsCarbRevisionSignalWorker(
            coordinator = unscheduled,
            syncForRevision = { AapsCarbHistorySyncResult.Superseded(60L, 61L) },
            relaunch = { error("unscheduled supersession must stop") }
        )
        assertThat(unscheduled.tryStart(60L)).isTrue()
    }
}
