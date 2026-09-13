package io.aaps.copilot.ui

import com.google.common.truth.Truth.assertThat
import com.google.gson.Gson
import io.aaps.copilot.data.local.entity.TherapyEventEntity
import io.aaps.copilot.data.repository.EventTimelineRepository
import io.aaps.copilot.data.repository.EventTimelineSources
import io.aaps.copilot.data.repository.TherapySanitizer
import io.aaps.copilot.domain.events.EventTimeline
import io.aaps.copilot.domain.events.CompensationEvent
import io.aaps.copilot.domain.events.CompensationEventType
import java.io.File
import kotlin.coroutines.ContinuationInterceptor
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
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
class OverviewEventTimelineProjectionTest {
    private val gson = Gson()
    private val repository = EventTimelineRepository(gson)

    @Test fun entityWindowMatchesLegacyDomainWindowEvenWithConflictingPayloadTimestamps() {
        val rows = listOf(
            row("before", 99L), row("from", 100L), row("through", 300L), row("after", 301L),
            row("payload-time", 99L, payload = """{"timestamp":200,"date":200,"startTs":200,"endTs":500}"""),
            row("entity-time", 200L, payload = """{"timestamp":9999,"date":9999}"""),
            row("malformed", 200L, payload = "{"), row("null", 200L, payload = "null")
        )

        val legacy = TherapySanitizer.toDomainEvents(rows, gson).filter { it.ts in 100L..300L }
        val prefiltered = TherapySanitizer.toDomainEvents(rows.filter { it.timestamp in 100L..300L }, gson)

        assertThat(prefiltered).containsExactlyElementsIn(legacy).inOrder()
        assertThat(legacy.map { it.sourceRowId }).containsExactly("from", "through", "entity-time").inOrder()
    }

    @Test fun unchangedHistoryReusesAcceptedAndRejectedRowsBeforeConversion() = runTest {
        val rows = (0 until 1_437).map { row("old-$it", 99L) } +
            (0 until 61).map { row("visible-$it", 200L) } +
            listOf(row("malformed", 200L, payload = "{"), row("null", 200L, payload = "null"))
        val inputs = MutableStateFlow(Snapshot(rows))
        val converted = mutableListOf<TherapyEventEntity>()
        val projected = mutableListOf<List<CompensationEvent>>()
        val emissions = mutableListOf<EventTimeline>()
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            pipeline(inputs, converted = converted, projected = projected).toList(emissions)
        }
        runCurrent()
        repeat(50) { revision ->
            inputs.value = Snapshot(rows.map { it.copy() }, revision + 1)
            runCurrent()
        }

        assertThat(projected).hasSize(51)
        projected.forEach { assertThat(it).isEqualTo(legacyPrepared(rows, 200L)) }
        val expected = repository.aggregate(EventTimelineSources(therapyEvents = legacy(rows, 200L)), 200L)
        assertThat(emissions).hasSize(51)
        emissions.forEach { assertThat(it).isEqualTo(EventTimeline(expected, 200L)) }
        assertThat(converted.size).isEqualTo(63)
        job.cancel()
    }

    @Test fun sameIdCorrectionsSourceTypeTimestampDuplicatesAndRemovalMatchLegacy() = runTest {
        val first = row("same", 200L, payload = """{"notes":"first","source":"aaps"}""")
        val corrected = first.copy(payloadJson = """{"notes":"corrected","source":"user","revision":2}""")
        val inputs = MutableStateFlow(Snapshot(listOf(first, first, corrected)))
        val converted = mutableListOf<TherapyEventEntity>()
        val projected = mutableListOf<List<CompensationEvent>>()
        val emissions = mutableListOf<EventTimeline>()
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            pipeline(inputs, converted = converted, projected = projected).toList(emissions)
        }
        val snapshots = listOf(
            inputs.value.rows,
            listOf(corrected, first, first),
            listOf(corrected.copy(type = "sensor_change")),
            listOf(corrected.copy(timestamp = 250L)),
            listOf(corrected.copy(payloadJson = "{")),
            emptyList(),
            listOf(first)
        )
        runCurrent()
        snapshots.drop(1).forEachIndexed { index, rows ->
            inputs.value = Snapshot(rows, index + 1)
            runCurrent()
        }

        assertThat(projected).containsExactlyElementsIn(snapshots.map { legacyPrepared(it, 200L) }).inOrder()
        assertThat(emissions.map { it.events }).containsExactlyElementsIn(snapshots.map {
            repository.aggregate(EventTimelineSources(therapyEvents = legacy(it, 200L)), 200L)
        }).inOrder()
        assertThat(converted).hasSize(6)
        job.cancel()
    }

    @Test fun movingWindowIncludesBothEdgesAndExpiresOrReentersUnchangedRows() = runTest {
        val rows = listOf(row("expired", 100L), row("inside", 200L), row("future", 301L))
        val inputs = MutableStateFlow(Snapshot(rows))
        var now = 200L
        val converted = mutableListOf<TherapyEventEntity>()
        val projected = mutableListOf<List<CompensationEvent>>()
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            pipeline(inputs, clock = { now }, converted = converted, projected = projected).toList()
        }
        runCurrent()
        now = 201L
        inputs.value = Snapshot(rows, 1)
        runCurrent()
        now = 200L
        inputs.value = Snapshot(rows, 2)
        runCurrent()

        assertThat(projected).containsExactly(legacyPrepared(rows, 200L), legacyPrepared(rows, 201L), legacyPrepared(rows, 200L)).inOrder()
        assertThat(converted.map { it.id }).containsExactly("expired", "inside", "future", "expired").inOrder()
        job.cancel()
    }

    @Test fun retentionIsCappedWithoutDroppingOutputAndOldSnapshotsAreEvicted() = runTest {
        val rows = listOf(row("a", 200L), row("b", 200L), row("c", 200L))
        val inputs = MutableStateFlow(Snapshot(rows))
        val converted = mutableListOf<TherapyEventEntity>()
        val projected = mutableListOf<List<CompensationEvent>>()
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            pipeline(inputs, converted = converted, projected = projected, maxRetainedRows = 2).toList()
        }
        runCurrent()
        inputs.value = Snapshot(rows, 1)
        runCurrent()
        assertThat(converted).hasSize(4)
        assertThat(projected).containsExactly(legacyPrepared(rows, 200L), legacyPrepared(rows, 200L))

        inputs.value = Snapshot(emptyList(), 2)
        runCurrent()
        inputs.value = Snapshot(rows, 3)
        runCurrent()
        assertThat(converted).hasSize(7)
        job.cancel()
    }

    @Test fun newCollectorDoesNotInheritHistoryAndCancellationUnsubscribes() = runTest {
        val inputs = MutableStateFlow(Snapshot(listOf(row("a", 200L))))
        val converted = mutableListOf<TherapyEventEntity>()
        val timeline = pipeline(inputs, converted = converted)
        repeat(2) {
            val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { timeline.toList() }
            runCurrent()
            inputs.value = inputs.value.copy(revision = it + 1)
            runCurrent()
            job.cancel()
            runCurrent()
            assertThat(inputs.subscriptionCount.value).isEqualTo(0)
        }
        assertThat(converted).hasSize(2)
    }

    @Test fun pureProjectionUsesSuppliedBoundedDispatcher() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler, "projection")
        val inputs = MutableStateFlow(Snapshot(listOf(row("a", 200L))))
        val dispatchers = mutableListOf<ContinuationInterceptor?>()
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            pipeline(inputs, dispatcher = dispatcher, onProjection = {
                dispatchers += currentCoroutineContext()[ContinuationInterceptor]
            }).toList()
        }
        runCurrent()
        assertThat(dispatchers).containsExactly(dispatcher)
        job.cancel()
    }

    @Test fun cancellationInsideConversionStopsRemainingRowsAndCannotPublish() = runTest {
        val inputs = MutableStateFlow(Snapshot((0 until 100).map { row("row-$it", 200L) }))
        val converted = mutableListOf<String>()
        val emissions = mutableListOf<EventTimeline>()
        lateinit var collector: Job
        collector = backgroundScope.launch(StandardTestDispatcher(testScheduler)) {
            projectOverviewEventTimeline(
                inputsForWindow = { inputs }, pastWindowMs = 100L, futureWindowMs = 100L,
                clock = { 200L }, gson = gson,
                projectionDispatcher = StandardTestDispatcher(testScheduler),
                therapyRows = { it.rows }, maxRetainedRows = 1_500,
                prepareTherapyEvent = repository::prepareTherapyEvent,
                convertTherapyRow = { row ->
                    converted += row.id
                    collector.cancel()
                    TherapySanitizer.toDomainEvents(listOf(row), gson).singleOrNull()
                },
                project = { _, window, therapy ->
                    repository.aggregatePreparedTherapy(EventTimelineSources(), therapy, window.nowTs)
                }
            ).toList(emissions)
        }
        runCurrent()

        assertThat(converted).containsExactly("row-0")
        assertThat(emissions).isEmpty()
        assertThat(inputs.subscriptionCount.value).isEqualTo(0)
    }

    @Test fun malformedAndCanonicalPayloadsKeepCompleteDeterministicTimelineParity() = runTest {
        val payloads = listOf(
            "{", "null", "[]", "", """{"carbs":NaN}""", """{"carbs":999}""",
            """{"units":3,"carbs":40,"isValid":true,"is_valid":false}""",
            """{"units":{"value":3},"carbs":90,"aapsCarbAmount":24,"aapsCarbIsValid":true,"aapsCarbClassification":"AAPS_REAL","aapsCarbSynthetic":false,"aapsCarbSuperseded":false}""",
            """{"units":3,"carbs":80,"aapsCarbAmount":24,"aaps_carb_amount":30,"notes":"retained"}""",
            """{"notes":"context","source":"AAPS","revision":2,"duration":30}"""
        )
        val rows = payloads.flatMapIndexed { index, payload ->
            listOf("meal_bolus", "note", "carbs", "temp_target", "sensor_change").map { type ->
                row("$index-$type", 200L, type, payload)
            }
        }
        val inputs = MutableStateFlow(Snapshot(rows))
        val converted = mutableListOf<TherapyEventEntity>()
        val emissions = mutableListOf<EventTimeline>()
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            pipeline(inputs, converted = converted).toList(emissions)
        }
        runCurrent()
        inputs.value = Snapshot(rows.reversed(), 1)
        runCurrent()
        val expected = EventTimeline(repository.aggregate(EventTimelineSources(therapyEvents = legacy(rows, 200L)), 200L), 200L)

        assertThat(emissions).containsExactly(expected, expected).inOrder()
        assertThat(converted.size).isEqualTo(rows.size)
        job.cancel()
    }

    @Test fun projectionErrorsAreNotConvertedIntoAnEmptyTimeline() = runTest {
        val inputs = MutableStateFlow(Snapshot(listOf(row("a", 200L))))
        val failure = AssertionError("projection failed")

        val thrown = runCatching { pipeline(inputs, onProjection = { throw failure }).first() }.exceptionOrNull()

        assertThat(generateSequence(thrown) { it.cause }.last()).isSameInstanceAs(failure)
    }

    @Test fun offMainProjectionTimeDoesNotAccumulateIntoBoundaryTimerDelay() = runTest {
        val inputs = MutableStateFlow(Snapshot(emptyList()))
        val windows = mutableListOf<EventTimelineWindow>()
        val emissions = mutableListOf<EventTimeline>()
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            projectOverviewEventTimeline(
                inputsForWindow = { window -> windows += window; inputs },
                pastWindowMs = 100L, futureWindowMs = 100L,
                clock = { testScheduler.currentTime }, gson = gson,
                projectionDispatcher = StandardTestDispatcher(testScheduler),
                therapyRows = { it.rows }, maxRetainedRows = 1_500,
                prepareTherapyEvent = repository::prepareTherapyEvent,
                project = { _, _, _ ->
                    delay(20L)
                    listOf(CompensationEvent("boundary", startTs = 100L, endTs = 200L, type = CompensationEventType.STRESS))
                }
            ).toList(emissions)
        }
        runCurrent()
        advanceTimeBy(120L)
        runCurrent()

        assertThat(windows.map { it.nowTs }).containsAtLeast(0L, 100L).inOrder()
        assertThat(emissions.last().generatedAt).isEqualTo(100L)
        job.cancel()
        runCurrent()
        val count = emissions.size
        advanceTimeBy(1_000L)
        runCurrent()
        assertThat(emissions.size).isEqualTo(count)
        assertThat(inputs.subscriptionCount.value).isEqualTo(0)
    }

    @Test fun mainViewModelWiresRealProjectionHelperWithoutGlobalTherapyCache() {
        val source = File("src/main/kotlin/io/aaps/copilot/ui/MainViewModel.kt").readText()
        val timeline = source.substringAfter("private val overviewEventTimeline =")
            .substringBefore("    init {")

        assertThat(timeline).contains("projectOverviewEventTimeline(")
        assertThat(timeline).contains("projectionDispatcher = uiStateDispatcher")
        assertThat(timeline).contains("therapyRows = { it.therapyRows }")
        assertThat(timeline).contains("prepareTherapyEvent = eventTimelineRepository::prepareTherapyEvent")
        assertThat(timeline).contains("eventTimelineRepository.aggregatePreparedTherapy(")
        assertThat(timeline).contains("preparedTherapyEvents = preparedTherapyEvents")
        assertThat(timeline).doesNotContain("therapyEvents =")
        assertThat(timeline).doesNotContain("TherapySanitizer.toDomainEvents")
        assertThat(source).contains("Dispatchers.Default.limitedParallelism(1)")
    }

    private fun TestScope.pipeline(
        inputs: MutableStateFlow<Snapshot>,
        converted: MutableList<TherapyEventEntity> = mutableListOf(),
        projected: MutableList<List<CompensationEvent>> = mutableListOf(),
        clock: () -> Long = { 200L },
        dispatcher: CoroutineDispatcher = StandardTestDispatcher(testScheduler),
        maxRetainedRows: Int = 1_500,
        onProjection: suspend () -> Unit = {}
    ) = projectOverviewEventTimeline(
        inputsForWindow = { inputs },
        pastWindowMs = 100L,
        futureWindowMs = 100L,
        clock = clock,
        gson = gson,
        projectionDispatcher = dispatcher,
        therapyRows = { it.rows },
        maxRetainedRows = maxRetainedRows,
        prepareTherapyEvent = repository::prepareTherapyEvent,
        convertTherapyRow = { row ->
            converted += row
            TherapySanitizer.toDomainEvents(listOf(row), gson).singleOrNull()
        },
        project = { _, window, therapy ->
            onProjection()
            projected += therapy
            repository.aggregatePreparedTherapy(EventTimelineSources(), therapy, window.nowTs)
        }
    )

    private fun legacy(rows: List<TherapyEventEntity>, now: Long) =
        TherapySanitizer.toDomainEvents(rows, gson).filter { it.ts in (now - 100L)..(now + 100L) }

    private fun legacyPrepared(rows: List<TherapyEventEntity>, now: Long) =
        legacy(rows, now).mapNotNull(repository::prepareTherapyEvent)

    private fun row(id: String, ts: Long, type: String = "note", payload: String = """{"notes":"local note"}""") =
        TherapyEventEntity(id, ts, type, payload)

    private data class Snapshot(val rows: List<TherapyEventEntity>, val revision: Int = 0)
}
