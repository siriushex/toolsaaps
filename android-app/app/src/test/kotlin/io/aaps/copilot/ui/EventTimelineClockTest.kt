package io.aaps.copilot.ui

import com.google.common.truth.Truth.assertThat
import io.aaps.copilot.domain.events.CompensationEvent
import io.aaps.copilot.domain.events.CompensationEventLifecycle
import io.aaps.copilot.domain.events.CompensationEventStatus
import io.aaps.copilot.domain.events.CompensationEventType
import io.aaps.copilot.domain.events.EventTimeline
import io.aaps.copilot.ui.foundation.screens.EventSourceFilter
import io.aaps.copilot.ui.foundation.screens.eventTimelineSections
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.io.File
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class EventTimelineClockTest {
    @Test fun upcomingBecomesActiveExactlyAtStartBoundary() = runTest {
        val event = event("upcoming", startTs = 100L, endTs = 200L)
        val emissions = mutableListOf<EventTimeline>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            boundaryFlow(MutableStateFlow(listOf(event))).take(2).toList(emissions)
        }

        runCurrent()
        assertThat(emissions.single().events.single().lifecycleAt(emissions.single().generatedAt))
            .isEqualTo(CompensationEventLifecycle.UPCOMING)

        advanceTimeBy(100L)
        runCurrent()

        assertThat(emissions).hasSize(2)
        assertThat(emissions.last().generatedAt).isEqualTo(100L)
        assertThat(emissions.last().events.single().lifecycleAt(emissions.last().generatedAt))
            .isEqualTo(CompensationEventLifecycle.ACTIVE)
    }

    @Test fun activeBecomesElapsedExactlyAtEndBoundary() = runTest {
        val event = event("active", startTs = 0L, endTs = 100L)
        val emissions = mutableListOf<EventTimeline>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            boundaryFlow(MutableStateFlow(listOf(event))).take(2).toList(emissions)
        }

        runCurrent()
        assertThat(emissions.single().events.single().lifecycleAt(0L))
            .isEqualTo(CompensationEventLifecycle.ACTIVE)

        advanceTimeBy(100L)
        runCurrent()

        assertThat(emissions.last().events.single().lifecycleAt(emissions.last().generatedAt))
            .isEqualTo(CompensationEventLifecycle.ELAPSED)
    }

    @Test fun recentItemExpiresOnlyAfterInclusiveTwentyFourHourBoundary() = runTest {
        val event = event("recent", startTs = 0L, endTs = 0L)
        val emissions = mutableListOf<EventTimeline>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            boundaryFlow(MutableStateFlow(listOf(event))).take(2).toList(emissions)
        }

        runCurrent()
        assertThat(eventTimelineSections(emissions.single().events, 0L, EventSourceFilter.ALL).recent24h)
            .containsExactly(event)

        advanceTimeBy(RECENT_24H_EXPIRY_MS)
        runCurrent()

        assertThat(emissions.last().generatedAt).isEqualTo(RECENT_24H_EXPIRY_MS)
        assertThat(
            eventTimelineSections(
                emissions.last().events,
                emissions.last().generatedAt,
                EventSourceFilter.ALL
            ).recent24h
        ).isEmpty()
    }

    @Test fun noEventsSchedulesNoTimer() = runTest {
        val scheduled = mutableListOf<Long>()
        val emissions = mutableListOf<EventTimeline>()
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            boundaryDrivenEventTimeline(
                inputsForWindow = { MutableStateFlow(emptyList<CompensationEvent>()) },
                pastWindowMs = DAY_MS,
                futureWindowMs = DAY_MS,
                clock = { testScheduler.currentTime },
                project = { events, _ -> events },
                onTimerScheduled = scheduled::add
            ).toList(emissions)
        }

        runCurrent()
        assertThat(emissions).hasSize(1)
        assertThat(scheduled).isEmpty()
        advanceTimeBy(DAY_MS)
        runCurrent()
        assertThat(emissions).hasSize(1)
        job.cancel()
    }

    @Test fun replacingInputsCancelsOldBoundaryAndSchedulesNewOne() = runTest {
        val inputs = MutableStateFlow(listOf(event("old", 100L, 150L)))
        val emissions = mutableListOf<EventTimeline>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            boundaryFlow(inputs).take(3).toList(emissions)
        }

        runCurrent()
        advanceTimeBy(10L)
        inputs.value = listOf(event("new", 200L, 250L))
        runCurrent()
        assertThat(emissions.map { it.generatedAt }).containsExactly(0L, 10L).inOrder()

        advanceTimeBy(90L)
        runCurrent()
        assertThat(emissions).hasSize(2)

        advanceTimeBy(100L)
        runCurrent()
        assertThat(emissions.map { it.generatedAt }).containsExactly(0L, 10L, 200L).inOrder()
        assertThat(emissions.last().events.single().localId).isEqualTo("new")
    }

    @Test fun closeInputCancelsElapsedBoundaryAndProjectsClosedImmediately() = runTest {
        val inputs = MutableStateFlow(listOf(event("context", 0L, 100L)))
        val emissions = mutableListOf<EventTimeline>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            boundaryFlow(inputs).take(2).toList(emissions)
        }

        runCurrent()
        advanceTimeBy(20L)
        inputs.value = listOf(event("context", 0L, 100L).copy(status = CompensationEventStatus.CLOSED))
        runCurrent()

        assertThat(emissions.last().generatedAt).isEqualTo(20L)
        assertThat(emissions.last().events.single().lifecycleAt(20L))
            .isEqualTo(CompensationEventLifecycle.CLOSED)
    }

    @Test fun boundariesAndDelaysAreOverflowSafeAndNeverZero() {
        val activeThroughMax = event("max", Long.MAX_VALUE - 2L, Long.MAX_VALUE)
        assertThat(nextEventTimelineBoundaryTs(listOf(activeThroughMax), Long.MAX_VALUE - 1L))
            .isEqualTo(Long.MAX_VALUE)
        assertThat(eventTimelineBoundaryDelayMs(Long.MAX_VALUE, Long.MAX_VALUE - 1L)).isEqualTo(1L)
        assertThat(eventTimelineBoundaryDelayMs(0L, 0L)).isEqualTo(1L)

        val closedAtMax = activeThroughMax.copy(status = CompensationEventStatus.CLOSED)
        assertThat(nextEventTimelineBoundaryTs(listOf(closedAtMax), Long.MAX_VALUE - 1L)).isNull()
    }

    @Test fun recentExpiryCanBeDisabledByViewPolicy() {
        val elapsed = event("elapsed", startTs = 0L, endTs = 10L)

        assertThat(
            nextEventTimelineBoundaryTs(
                events = listOf(elapsed),
                nowTs = 20L,
                policy = EventTimelineBoundaryPolicy(includeRecent24h = false)
            )
        ).isNull()
    }

    @Test fun productionOverviewHasNoFixedCadenceClock() {
        val source = File("src/main/kotlin/io/aaps/copilot/ui/MainViewModel.kt").readText()

        assertThat(source).contains("boundaryDrivenEventTimeline(")
        assertThat(source).doesNotContain("EVENT_TIMELINE_CLOCK_INTERVAL_MS")
        assertThat(source).doesNotContain("lowFrequencyEventTimelineClock")
        assertThat(source).doesNotContain("movingEventTimelineWindows")
        assertThat(source).doesNotContain("while (true)")
    }

    private fun kotlinx.coroutines.test.TestScope.boundaryFlow(
        inputs: MutableStateFlow<List<CompensationEvent>>
    ) = boundaryDrivenEventTimeline(
        inputsForWindow = { inputs },
        pastWindowMs = DAY_MS,
        futureWindowMs = DAY_MS,
        clock = { testScheduler.currentTime },
        project = { events, _ -> events }
    )

    private fun event(id: String, startTs: Long, endTs: Long) = CompensationEvent(
        localId = id,
        startTs = startTs,
        endTs = endTs,
        type = CompensationEventType.STRESS
    )

    private companion object {
        const val DAY_MS = 24L * 60L * 60L * 1_000L
        const val RECENT_24H_EXPIRY_MS = DAY_MS + 1L
    }
}
