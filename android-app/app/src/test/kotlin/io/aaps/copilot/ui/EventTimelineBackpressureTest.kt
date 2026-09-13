package io.aaps.copilot.ui

import com.google.common.truth.Truth.assertThat
import io.aaps.copilot.domain.events.CompensationEvent
import io.aaps.copilot.domain.events.CompensationEventType
import io.aaps.copilot.domain.events.EventTimeline
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class EventTimelineBackpressureTest {
    @Test fun blockedProjectionBoundsProducerBacklogAndCompletesEveryInputInOrder() = runTest {
        val release = CompletableDeferred<Unit>()
        val dispatcher = StandardTestDispatcher(testScheduler, "projection")
        var produced = 0
        var maxOutstanding = 0
        var active = 0
        var maxConcurrency = 0
        val completed = mutableListOf<Int>()
        val emissions = mutableListOf<EventTimeline>()
        val collector = backgroundScope.launch {
            boundaryDrivenEventTimeline<Int>(
                inputsForWindow = {
                    flow {
                        repeat(INPUT_COUNT) { value ->
                            produced++ // Count before emit, including a producer suspended in send.
                            maxOutstanding = maxOf(maxOutstanding, produced - completed.size)
                            emit(value)
                        }
                    }
                },
                pastWindowMs = 1_000L, futureWindowMs = 1_000L,
                clock = { testScheduler.currentTime },
                policy = EventTimelineBoundaryPolicy(includeRecent24h = false),
                project = { value, _ ->
                    withContext(dispatcher) {
                        active++
                        maxConcurrency = maxOf(maxConcurrency, active)
                        try {
                            if (value == 0) release.await()
                            completed += value
                            listOf(event(value.toString(), 0L, 0L))
                        } finally {
                            active--
                        }
                    }
                }
            ).take(INPUT_COUNT).toList(emissions)
        }

        runCurrent()
        println("blocked: produced=$produced completed=${completed.size} outstanding=${produced - completed.size} active=$active")
        assertThat(active).isEqualTo(1)
        assertThat(completed).isEmpty()
        assertThat(produced - completed.size).isAtMost(4)

        release.complete(Unit)
        runCurrent()
        collector.join()
        println("released: produced=$produced completed=${completed.size} emitted=${emissions.size} maxOutstanding=$maxOutstanding maxConcurrency=$maxConcurrency")
        assertThat(completed).containsExactlyElementsIn(0 until INPUT_COUNT).inOrder()
        assertThat(emissions.map { it.events.single().localId })
            .containsExactlyElementsIn((0 until INPUT_COUNT).map(Int::toString)).inOrder()
        assertThat(maxOutstanding).isAtMost(4)
        assertThat(maxConcurrency).isEqualTo(1)
        assertThat(active).isEqualTo(0)
    }

    @Test fun continuousInputsRefreshActualWindowByEachBoundaryPlusOneProjection() = runTest {
        val windows = mutableListOf<Pair<Long, Long>>()
        var active = 0
        var maxConcurrency = 0
        val collector = backgroundScope.launch {
            boundaryDrivenEventTimeline<Int>(
                inputsForWindow = { window ->
                    windows += window.nowTs to testScheduler.currentTime
                    flow { repeat(INPUT_COUNT) { emit(it) } }
                },
                pastWindowMs = 1_000L, futureWindowMs = 1_000L,
                clock = { testScheduler.currentTime },
                policy = EventTimelineBoundaryPolicy(includeRecent24h = false),
                project = { _, _ ->
                    active++
                    maxConcurrency = maxOf(maxConcurrency, active)
                    try {
                        delay(20L)
                        listOf(event("boundary", 100L, 200L))
                    } finally {
                        active--
                    }
                }
            ).toList()
        }

        runCurrent()
        advanceTimeBy(125L)
        runCurrent()
        val by125 = windows.toList()
        advanceTimeBy(100L)
        runCurrent()
        val by225 = windows.toList()
        collector.cancelAndJoin()

        println("window refreshes: by125=$by125 by225=$by225 maxConcurrency=$maxConcurrency")
        assertThat(by125).containsExactly(0L to 0L, 100L to 120L).inOrder()
        assertThat(by225).containsExactly(0L to 0L, 100L to 120L, 200L to 220L).inOrder()
        assertThat(maxConcurrency).isEqualTo(1)
        assertThat(active).isEqualTo(0)
    }

    @Test fun cancellationReleasesSuspendedProducerProjectionAndSubscription() = runTest {
        val release = CompletableDeferred<Unit>()
        var produced = 0
        var emitted = 0
        var subscriptions = 0
        var sourceCancelled = false
        var projectionCancelled = false
        val emissions = mutableListOf<EventTimeline>()
        val collector = backgroundScope.launch {
            boundaryDrivenEventTimeline<Int>(
                inputsForWindow = {
                    flow {
                        subscriptions++
                        try {
                            repeat(INPUT_COUNT) {
                                produced++
                                emit(it)
                                emitted++
                            }
                            awaitCancellation()
                        } finally {
                            subscriptions--
                            sourceCancelled = true
                        }
                    }
                },
                pastWindowMs = 1_000L, futureWindowMs = 1_000L,
                clock = { testScheduler.currentTime },
                project = { _, _ ->
                    try {
                        release.await()
                        emptyList()
                    } finally {
                        projectionCancelled = true
                    }
                }
            ).toList(emissions)
        }

        runCurrent()
        val pendingEmits = produced - emitted
        assertThat(subscriptions).isEqualTo(1)
        collector.cancelAndJoin()
        val producedAtCancellation = produced
        release.complete(Unit)
        advanceTimeBy(1_000L)
        runCurrent()

        println("cancelled: pendingEmits=$pendingEmits produced=$produced subscriptions=$subscriptions sourceCancelled=$sourceCancelled projectionCancelled=$projectionCancelled")
        assertThat(pendingEmits).isEqualTo(1)
        assertThat(sourceCancelled).isTrue()
        assertThat(projectionCancelled).isTrue()
        assertThat(subscriptions).isEqualTo(0)
        assertThat(produced).isEqualTo(producedAtCancellation)
        assertThat(emissions).isEmpty()
    }

    @Test fun windowRestartRejectsQueuedOldInputsAndCancelsOldSubscription() = runTest {
        val started = mutableListOf<Pair<Long, Long>>()
        val cancelledWindows = mutableListOf<Long>()
        val emissions = mutableListOf<EventTimeline>()
        val collector = backgroundScope.launch {
            boundaryDrivenEventTimeline<Long>(
                inputsForWindow = { window ->
                    flow {
                        try {
                            repeat(INPUT_COUNT) { emit(window.nowTs) }
                            awaitCancellation()
                        } finally {
                            cancelledWindows += window.nowTs
                        }
                    }
                },
                pastWindowMs = 1_000L, futureWindowMs = 1_000L,
                clock = { testScheduler.currentTime },
                policy = EventTimelineBoundaryPolicy(includeRecent24h = false),
                project = { sourceWindow, window ->
                    started += sourceWindow to window.nowTs
                    delay(20L)
                    listOf(event(sourceWindow.toString(), 100L, 200L))
                }
            ).toList(emissions)
        }

        runCurrent()
        advanceTimeBy(165L)
        runCurrent()
        collector.cancelAndJoin()

        println("restart: started=$started cancelledWindows=$cancelledWindows")
        assertThat(started.filter { it.second < 120L }.map { it.first }.toSet()).containsExactly(0L)
        assertThat(started.filter { it.second >= 120L }.map { it.first }.toSet()).containsExactly(100L)
        assertThat(cancelledWindows).containsExactly(0L, 100L).inOrder()
        assertThat(emissions.filter { it.generatedAt >= 120L }.map { it.events.single().localId }.toSet())
            .containsExactly("100")
    }

    @Test fun replacedTimerGenerationCannotRefreshAtOldBoundariesOrAfterCancellation() = runTest {
        val inputs = MutableStateFlow(listOf(event("old", 100L, 200L)))
        val windows = mutableListOf<Pair<Long, Long>>()
        val emissions = mutableListOf<EventTimeline>()
        val collector = backgroundScope.launch {
            boundaryDrivenEventTimeline(
                inputsForWindow = { window ->
                    windows += window.nowTs to testScheduler.currentTime
                    inputs
                },
                pastWindowMs = 1_000L, futureWindowMs = 1_000L,
                clock = { testScheduler.currentTime },
                policy = EventTimelineBoundaryPolicy(includeRecent24h = false),
                project = { events, _ ->
                    delay(20L)
                    events
                }
            ).toList(emissions)
        }

        runCurrent()
        advanceTimeBy(40L)
        inputs.value = listOf(event("new", 300L, 400L))
        runCurrent()
        advanceTimeBy(185L)
        runCurrent()
        assertThat(windows).containsExactly(0L to 0L)
        assertThat(emissions.map { it.generatedAt }).containsExactly(0L, 40L).inOrder()

        advanceTimeBy(100L)
        runCurrent()
        assertThat(windows).containsExactly(0L to 0L, 300L to 320L).inOrder()
        assertThat(emissions.map { it.generatedAt }).containsExactly(0L, 40L, 300L).inOrder()
        assertThat(emissions.last().events.single().localId).isEqualTo("new")
        collector.cancelAndJoin()
        advanceTimeBy(1_000L)
        runCurrent()

        println("replacement: windows=$windows emitted=${emissions.map { it.generatedAt }} subscriptions=${inputs.subscriptionCount.value}")
        assertThat(inputs.subscriptionCount.value).isEqualTo(0)
        assertThat(windows).containsExactly(0L to 0L, 300L to 320L).inOrder()
        assertThat(emissions).hasSize(3)
    }

    private fun event(id: String, startTs: Long, endTs: Long) = CompensationEvent(
        localId = id, startTs = startTs, endTs = endTs, type = CompensationEventType.STRESS
    )

    private companion object {
        const val INPUT_COUNT = 10_000
    }
}
