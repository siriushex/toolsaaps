package io.aaps.copilot.ui

import com.google.common.truth.Truth.assertThat
import com.google.gson.Gson
import io.aaps.copilot.data.local.entity.TherapyEventEntity
import io.aaps.copilot.data.repository.AapsContextEventGateway
import io.aaps.copilot.data.repository.CopilotContextNoteMarker
import io.aaps.copilot.data.repository.EventTimelineRepository
import io.aaps.copilot.data.repository.EventTimelineSources
import io.aaps.copilot.data.repository.TherapySanitizer
import io.aaps.copilot.domain.events.CompensationEvent
import io.aaps.copilot.domain.events.CompensationEventStatus
import io.aaps.copilot.domain.events.CompensationEventType
import io.aaps.copilot.domain.events.EventSource
import io.aaps.copilot.domain.events.EventTimeline
import io.aaps.copilot.domain.model.TherapyEvent
import io.aaps.copilot.ui.foundation.screens.EventSourceFilter
import io.aaps.copilot.ui.foundation.screens.eventTimelineSections
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class OverviewEventTimelineStaticPreparationTest {
    private val gson = Gson()
    private val repository = EventTimelineRepository(gson)

    @Test fun emittedAttributesRejectMutationAndKeepCurrentOutputUnchanged() = runTest {
        val output = pipeline(listOf(Snapshot(listOf(row("immutable")))).asFlow(), Work()).first()
        val emitted = output.events.single()
        val expected = emitted.copy(attributes = LinkedHashMap(emitted.attributes))
        val failure = runCatching {
            (emitted.attributes as MutableMap<String, String>)["notes"] = "contaminated"
        }.exceptionOrNull()

        assertThat(output.events.single()).isEqualTo(expected)
        assertThat(failure).isInstanceOf(UnsupportedOperationException::class.java)
    }

    @Test fun emittedAttributesCannotContaminateFutureCachedLifecycleOutput() = runTest {
        val end = NOW + 100
        val rows = listOf(row("immutable", payload = """{"notes":"original","endTs":$end}"""))
        val inputs = MutableStateFlow(Snapshot(rows))
        val work = Work()
        val output = mutableListOf<EventTimeline>()
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            pipeline(inputs, work, clock = { NOW + testScheduler.currentTime }).toList(output)
        }
        runCurrent()
        val emitted = output.single().events.single()
        val expected = emitted.copy(attributes = LinkedHashMap(emitted.attributes))
        val failure = runCatching {
            (emitted.attributes as MutableMap<String, String>)["notes"] = "contaminated"
        }.exceptionOrNull()
        inputs.value = Snapshot(rows.map { it.copy() }, listOf(other))
        runCurrent()
        assertThat(output.last().events.single { it.localId == "immutable" }).isEqualTo(expected)
        advanceTimeBy(100)
        runCurrent()
        val closed = output.last().events.single { it.localId == "immutable" }
        assertThat(closed).isEqualTo(expected.copy(status = CompensationEventStatus.CLOSED))
        assertThat(closed).isNotSameInstanceAs(emitted)
        org.junit.Assert.assertThrows(UnsupportedOperationException::class.java) {
            (closed.attributes as MutableMap<String, String>).clear()
        }
        inputs.value = Snapshot(rows)
        runCurrent()

        assertThat(output.last().events.single()).isEqualTo(closed)
        assertThat(emitted).isEqualTo(expected)
        assertThat(failure).isInstanceOf(UnsupportedOperationException::class.java)
        assertThat(work.parsed).hasSize(1)
        assertThat(work.classified).hasSize(1)
        job.cancel()
    }

    @Test fun realStaticClassificationRunsOnceFor1500RowsAnd50UnrelatedUpdates() = runTest {
        val types = List(712) { "temp_target" } + List(495) { "correction_bolus" } +
            List(223) { "carbs" } + List(51) { "note" } + List(18) { "profile_switch" } + "openaps_offline"
        val rows = types.mapIndexed { index, type -> row("row-$index", type = type) }
        val snapshots = (0..50).map { update ->
            Snapshot(rows.map { it.copy() }, listOf(other.copy(title = "update-$update")))
        }
        val work = Work()
        val output = pipeline(snapshots.asFlow(), work).take(snapshots.size).toList()

        assertThat(output).hasSize(51)
        assertThat(output.first().events).hasSize(275)
        assertThat(output.last().events.single { it.localId == "other" }.title).isEqualTo("update-50")
        assertThat(work.parsed).hasSize(1_500)
        assertThat(work.classified.size).isEqualTo(1_500)
    }

    @Test fun everySameIdEntityCorrectionInvalidatesIncludingIgnoredAndRevisionOnlyChanges() = runTest {
        val original = row("same")
        val content = original.copy(payloadJson = """{"notes":"changed","units":2,"source":"AAPS"}""")
        val source = content.copy(payloadJson = """{"notes":"changed","units":2,"source":"uam_engine"}""")
        val type = content.copy(type = "bolus")
        val time = content.copy(timestamp = NOW + 1)
        val revision = content.copy(payloadJson = """{"notes":"changed","units":2,"source":"AAPS","revision":2}""")
        val variants = listOf(original, content, source, type, time, revision)
        val snapshots = variants.flatMap { listOf(Snapshot(listOf(it)), Snapshot(listOf(it.copy()))) }
        val work = Work()
        val output = pipeline(snapshots.asFlow(), work).take(snapshots.size).toList()

        assertThat(work.parsed).containsExactlyElementsIn(variants).inOrder()
        assertThat(work.classified).hasSize(variants.size)
        assertThat(output[2].events.single().note).isEqualTo("changed")
        assertThat(output[4].events.single().source).isEqualTo(EventSource.AUTOMATIC)
        assertThat(output[6].events).isEmpty()
        assertThat(output[8].events.single().startTs).isEqualTo(NOW + 1)
    }

    @Test fun rejectedPayloadsAndIgnoredTherapyAreRetainedButNeverBecomeEvents() = runTest {
        val rows = listOf(
            row("malformed", payload = "{"), row("null", payload = "null"),
            row("array", payload = "[]"), row("empty", payload = ""),
            row("invalid-carbs", type = "carbs", payload = """{"carbs":999}"""),
            row("ignored-insulin", type = "bolus"), row("ignored-target", type = "temp_target"),
            row("unknown", type = "unrecognized"), row("accepted")
        )
        val snapshots = List(4) { Snapshot(rows.map { it.copy() }) }
        val work = Work()
        val output = pipeline(snapshots.asFlow(), work).take(snapshots.size).toList()

        assertThat(output.map { it.events.map(CompensationEvent::localId) }).containsExactly(
            listOf("accepted"), listOf("accepted"), listOf("accepted"), listOf("accepted")
        )
        assertThat(work.parsed).hasSize(rows.size)
        assertThat(work.classified.map { it.sourceRowId }).containsExactly(
            "ignored-insulin", "ignored-target", "unknown", "accepted"
        ).inOrder()
    }

    @Test fun protectedEchoAndLookalikesKeepAuthoritativeNotesAndIndependentIds() = runTest {
        val marker = CopilotContextNoteMarker.commandHeader(
            "manual:test", 2, AapsContextEventGateway.Operation.UPDATE
        )
        fun note(id: String, payload: Map<String, String>) = row(id, payload = gson.toJson(payload))
        val rows = listOf(
            note("echo", mapOf("notes" to "$marker|title|note")),
            note("legacy-echo", mapOf("note" to "$marker|title|note")),
            note("same-time-a", mapOf("notes" to "ordinary", "note" to marker)),
            note("same-time-b", mapOf("notes" to "ordinary", "note" to marker)),
            note("mid-marker", mapOf("notes" to "ordinary $marker|text")),
            note("whitespace", mapOf("notes" to " $marker|text")),
            note("wrong-hash", mapOf("notes" to marker.replace("id=copilot:", "id=copilot:wrong"))),
            note("base-only", mapOf("notes" to "COPILOT_CONTEXT_V1"))
        )
        val work = Work()
        val output = pipeline(listOf(Snapshot(rows), Snapshot(rows.reversed())).asFlow(), work).take(2).toList()

        assertThat(output[0]).isEqualTo(output[1])
        assertThat(output[0].events.map { it.localId }).containsExactly(
            "same-time-a", "same-time-b", "mid-marker", "whitespace", "wrong-hash", "base-only"
        )
        output[0].events.filter { it.localId in setOf("mid-marker", "whitespace") }
            .forEach { assertThat(it.title).doesNotContain("COPILOT_CONTEXT_V1") }
        assertThat(output[0].events.single { it.localId == "base-only" }.title).isEqualTo("COPILOT_CONTEXT_V1")
        assertThat(work.classified).hasSize(rows.size)
    }

    @Test fun duplicateOrderRevisionAndSourcePrecedenceRemainDownstreamOfReuse() = runTest {
        val a = row("same", payload = """{"notes":"first","endTs":1000200}""")
        val b = a.copy(payloadJson = """{"notes":"second","endTs":1000200}""")
        val low = other.copy(localId = "same", revision = 1, source = EventSource.USER, endTs = 1_000_200)
        val high = low.copy(revision = 2)
        val snapshots = listOf(
            Snapshot(listOf(a, a, b), listOf(low)),
            Snapshot(listOf(b, a, a), listOf(low)),
            Snapshot(listOf(a, b), listOf(high))
        )
        val work = Work()
        val output = pipeline(snapshots.asFlow(), work).take(3).toList()

        assertThat(output.map { it.events.single().title }).containsExactly("first", "second", high.title).inOrder()
        assertThat(output.map { it.events.single().source }).containsExactly(
            EventSource.AAPS, EventSource.AAPS, EventSource.USER
        ).inOrder()
        assertThat(work.classified).hasSize(2)
    }

    @Test fun capLimitsRetentionNotOutputAndEmptySnapshotEvictsBothPositiveAndNegativeRows() = runTest {
        val a = row("a")
        val ignored = row("ignored", type = "temp_target")
        val overflow = row("overflow")
        val rows = listOf(a, ignored, overflow, a, overflow)
        val snapshots = listOf(Snapshot(rows), Snapshot(rows), Snapshot(emptyList()), Snapshot(rows))
        val work = Work()
        val output = pipeline(snapshots.asFlow(), work, cap = 2).take(4).toList()

        assertThat(output.map { it.events.size }).containsExactly(2, 2, 0, 2).inOrder()
        assertThat(work.classified.map { it.sourceRowId }).containsExactly(
            "a", "ignored", "overflow", "overflow", "overflow", "overflow",
            "a", "ignored", "overflow", "overflow"
        ).inOrder()
    }

    @Test fun inclusiveWindowEvictsRowsAndReentryReclassifiesWithoutLosingEdges() = runTest {
        val rows = listOf(row("lower", ts = NOW - 100), row("upper", ts = NOW + 100), row("next", ts = NOW + 101))
        val inputs = MutableStateFlow(Snapshot(rows))
        var now = NOW
        val work = Work()
        val output = mutableListOf<EventTimeline>()
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            pipeline(inputs, work, past = 100, future = 100, clock = { now }).toList(output)
        }
        runCurrent()
        now++
        inputs.value = Snapshot(rows, listOf(other))
        runCurrent()
        now--
        inputs.value = Snapshot(rows)
        runCurrent()

        assertThat(output.map { it.events.filterNot { event -> event.localId == "other" }.map { event -> event.localId } })
            .containsExactly(listOf("lower", "upper"), listOf("upper", "next"), listOf("lower", "upper")).inOrder()
        assertThat(work.classified.map { it.sourceRowId }).containsExactly("lower", "upper", "next", "lower").inOrder()
        job.cancel()
    }

    @Test fun futureStartEndAndRecent24hBoundariesReaggregateWithoutReclassification() = runTest {
        val start = NOW + 100
        val end = NOW + 200
        val rows = listOf(row("future", ts = start, payload = """{"notes":"future","endTs":$end}"""))
        val inputs = MutableStateFlow(Snapshot(rows))
        val work = Work()
        val output = mutableListOf<EventTimeline>()
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            pipeline(inputs, work, clock = { NOW + testScheduler.currentTime }).toList(output)
        }
        runCurrent()
        assertThat(output.last().events.single().isActiveAt(NOW)).isFalse()
        advanceTimeBy(100)
        runCurrent()
        assertThat(output.last().generatedAt).isEqualTo(start)
        assertThat(output.last().events.single().isActiveAt(start)).isTrue()
        advanceTimeBy(100)
        runCurrent()
        assertThat(output.last().generatedAt).isEqualTo(end)
        assertThat(output.last().events.single().status).isEqualTo(CompensationEventStatus.CLOSED)
        advanceTimeBy(DAY)
        runCurrent()
        assertThat(eventTimelineSections(output.last().events, end + DAY, EventSourceFilter.ALL).recent24h).hasSize(1)
        advanceTimeBy(1)
        runCurrent()
        assertThat(output.last().generatedAt).isEqualTo(end + DAY + 1)
        assertThat(eventTimelineSections(output.last().events, output.last().generatedAt, EventSourceFilter.ALL).recent24h).isEmpty()
        val count = output.size
        advanceTimeBy(DAY * 2)
        runCurrent()
        assertThat(output).hasSize(count)
        assertThat(work.classified).hasSize(1)
        job.cancel()
        runCurrent()
        assertThat(inputs.subscriptionCount.value).isEqualTo(0)
    }

    @Test fun newSubscriptionRepreparesAcceptedIgnoredAndEchoRowsWithoutRepositoryLifetimeReuse() = runTest {
        val echo = CopilotContextNoteMarker.commandHeader("manual:subscription", 1, AapsContextEventGateway.Operation.CREATE)
        val rows = listOf(
            row("accepted"), row("ignored", type = "temp_target"), row("rejected", payload = "null"),
            row("echo", payload = gson.toJson(mapOf("notes" to echo)))
        )
        val inputs = MutableStateFlow(Snapshot(rows))
        val work = Work()
        val timeline = pipeline(inputs, work)
        repeat(2) { subscription ->
            val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { timeline.toList() }
            runCurrent()
            inputs.value = Snapshot(rows, listOf(other.copy(title = "subscription-$subscription")))
            runCurrent()
            job.cancel()
            runCurrent()
            assertThat(inputs.subscriptionCount.value).isEqualTo(0)
        }
        assertThat(work.parsed.size).isEqualTo(8)
        assertThat(work.classified.map { it.sourceRowId }).containsExactly(
            "accepted", "ignored", "echo", "accepted", "ignored", "echo"
        ).inOrder()
    }

    @Test fun cancellationInsideRealPreparationStopsRemainingRowsAndPublishesNothing() = runTest {
        val inputs = MutableStateFlow(Snapshot(List(100) { row("row-$it") }))
        val output = mutableListOf<EventTimeline>()
        var parsed = 0
        var classified = 0
        var projected = 0
        lateinit var collector: Job
        collector = backgroundScope.launch(StandardTestDispatcher(testScheduler)) {
            projectOverviewEventTimeline(
                inputsForWindow = { inputs }, pastWindowMs = DAY, futureWindowMs = DAY,
                clock = { NOW }, gson = gson, projectionDispatcher = StandardTestDispatcher(testScheduler),
                therapyRows = { it.rows }, maxRetainedRows = 1_500,
                convertTherapyRow = { row ->
                    parsed++
                    TherapySanitizer.toDomainEvents(listOf(row), gson).singleOrNull()
                },
                prepareTherapyEvent = { event ->
                    classified++
                    repository.prepareTherapyEvent(event).also { collector.cancel() }
                },
                project = { _, window, therapy ->
                    projected++
                    repository.aggregatePreparedTherapy(EventTimelineSources(), therapy, window.nowTs)
                }
            ).toList(output)
        }
        runCurrent()
        assertThat(parsed).isEqualTo(1)
        assertThat(classified).isEqualTo(1)
        assertThat(projected).isEqualTo(0)
        assertThat(output).isEmpty()
        assertThat(inputs.subscriptionCount.value).isEqualTo(0)
    }

    @Test fun conversionPreparationAndProjectionFailuresPropagateAndNextCollectionIsFresh() = runTest {
        val inputs = MutableStateFlow(Snapshot(List(3) { row("row-$it") }))
        for (stage in listOf("parse", "prepare", "project")) {
            for (failure in listOf(IllegalStateException("ordinary"), CancellationException("cancel"), AssertionError("fatal"))) {
                var fail = true
                var parsed = 0
                var classified = 0
                val output = mutableListOf<EventTimeline>()
                val timeline = projectOverviewEventTimeline(
                    inputsForWindow = { inputs }, pastWindowMs = DAY, futureWindowMs = DAY,
                    clock = { NOW }, gson = gson, projectionDispatcher = StandardTestDispatcher(testScheduler),
                    therapyRows = { it.rows }, maxRetainedRows = 1_500,
                    convertTherapyRow = { row ->
                        parsed++
                        if (fail && stage == "parse") throw failure
                        TherapySanitizer.toDomainEvents(listOf(row), gson).singleOrNull()
                    },
                    prepareTherapyEvent = { event ->
                        classified++
                        if (fail && stage == "prepare") throw failure
                        repository.prepareTherapyEvent(event)
                    },
                    project = { _, window, therapy ->
                        if (fail && stage == "project") throw failure
                        repository.aggregatePreparedTherapy(EventTimelineSources(), therapy, window.nowTs)
                    }
                )
                val thrown = runCatching { timeline.take(1).toList(output) }.exceptionOrNull()
                assertThat(generateSequence(thrown) { it.cause }.last()).isSameInstanceAs(failure)
                assertThat(output).isEmpty()
                assertThat(parsed).isEqualTo(if (stage == "project") 3 else 1)
                assertThat(classified).isEqualTo(when (stage) { "parse" -> 0; "prepare" -> 1; else -> 3 })
                assertThat(inputs.subscriptionCount.value).isEqualTo(0)
                fail = false
                parsed = 0
                classified = 0
                assertThat(timeline.first().events).isEqualTo(repository.aggregate(
                    EventTimelineSources(therapyEvents = TherapySanitizer.toDomainEvents(inputs.value.rows, gson)), NOW
                ))
                assertThat(parsed).isEqualTo(3)
                assertThat(classified).isEqualTo(3)
            }
        }
    }

    private fun TestScope.pipeline(
        inputs: Flow<Snapshot>,
        work: Work,
        cap: Int = 1_500,
        past: Long = DAY * 30,
        future: Long = DAY * 30,
        clock: () -> Long = { NOW }
    ) = projectOverviewEventTimeline(
        inputsForWindow = { inputs }, pastWindowMs = past, futureWindowMs = future,
        clock = clock, gson = gson, projectionDispatcher = StandardTestDispatcher(testScheduler),
        therapyRows = { it.rows }, maxRetainedRows = cap,
        prepareTherapyEvent = { therapy ->
            work.classified += therapy
            repository.prepareTherapyEvent(therapy)
        },
        convertTherapyRow = { row ->
            work.parsed += row
            TherapySanitizer.toDomainEvents(listOf(row), gson).singleOrNull()
        },
        project = { input, window, therapy ->
            val result = repository.aggregatePreparedTherapy(
                EventTimelineSources(plannedActivity = input.other), therapy, window.nowTs
            )
            val reference = repository.aggregate(
                EventTimelineSources(
                    therapyEvents = TherapySanitizer.toDomainEvents(input.rows, gson)
                        .filter { it.ts in window.fromTs..window.throughTs },
                    plannedActivity = input.other
                ), window.nowTs
            )
            assertThat(result).isEqualTo(reference)
            result
        }
    )

    private class Work {
        val parsed = mutableListOf<TherapyEventEntity>()
        val classified = mutableListOf<TherapyEvent>()
    }

    private data class Snapshot(val rows: List<TherapyEventEntity>, val other: List<CompensationEvent> = emptyList())

    private fun row(
        id: String, ts: Long = NOW, type: String = "note",
        payload: String = """{"notes":"ordinary","units":2,"carbs":24,"durationMinutes":30,"source":"AAPS"}"""
    ) = TherapyEventEntity(id, ts, type, payload)

    private val other = CompensationEvent("other", NOW, NOW + 1_000, CompensationEventType.STRESS, title = "other")

    companion object {
        private const val NOW = 1_000_000L
        private const val DAY = 86_400_000L
    }
}
