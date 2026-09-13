package io.aaps.copilot.domain.profile

import com.google.common.truth.Truth.assertThat
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import org.junit.Test

class ActivityScheduleEngineTest {

    private val engine = ActivityScheduleEngine()

    @Test
    fun leadTimesAreExactly60_75_90Minutes() {
        assertThat(engine.leadMinutes(PlannedActivityIntensity.LIGHT)).isEqualTo(60L)
        assertThat(engine.leadMinutes(PlannedActivityIntensity.MEDIUM)).isEqualTo(75L)
        assertThat(engine.leadMinutes(PlannedActivityIntensity.HIGH)).isEqualTo(90L)
    }

    @Test
    fun missingDstTimeMovesToFirstValidInstantAndKeepsDisplayedResolution() {
        val event = schedule(
            localStart = LocalDateTime.of(2026, 3, 29, 2, 30),
            timezoneId = "Europe/Berlin",
            recurrenceDays = setOf(DayOfWeek.SUNDAY)
        )

        val occurrence = checkNotNull(engine.materialize(event, LocalDate.of(2026, 3, 29)))

        assertThat(occurrence.dstResolution).isEqualTo(DstResolution.MOVED_FORWARD)
        assertThat(occurrence.start.atZone(ZoneId.of("Europe/Berlin")).hour).isEqualTo(3)
        assertThat(occurrence.start.atZone(ZoneId.of("Europe/Berlin")).minute).isEqualTo(0)
    }

    @Test
    fun ambiguousDstTimeUsesEarlierOffsetDeterministically() {
        val event = schedule(
            localStart = LocalDateTime.of(2026, 10, 25, 2, 30),
            timezoneId = "Europe/Berlin",
            recurrenceDays = setOf(DayOfWeek.SUNDAY)
        )

        val occurrence = checkNotNull(engine.materialize(event, LocalDate.of(2026, 10, 25)))

        assertThat(occurrence.dstResolution).isEqualTo(DstResolution.EARLIER_OFFSET)
        assertThat(occurrence.start).isEqualTo(Instant.parse("2026-10-25T00:30:00Z"))
    }

    @Test
    fun recurrenceOnlyMaterializesSelectedWeekdaysThroughItsEndDate() {
        val event = schedule(
            localStart = LocalDateTime.of(2026, 8, 3, 8, 0),
            recurrenceDays = setOf(DayOfWeek.TUESDAY, DayOfWeek.THURSDAY),
            recurrenceEndEpochDay = LocalDate.of(2026, 8, 6).toEpochDay()
        )

        assertThat(engine.materialize(event, LocalDate.of(2026, 8, 4))).isNotNull()
        assertThat(engine.materialize(event, LocalDate.of(2026, 8, 5))).isNull()
        assertThat(engine.materialize(event, LocalDate.of(2026, 8, 7))).isNull()
    }

    @Test
    fun halfOpenAdjacentOccurrencesDoNotOverlap() {
        val validation = engine.validate(
            listOf(
                schedule(eventId = "first", localStart = LocalDateTime.of(2026, 8, 3, 10, 0), durationMinutes = 60),
                schedule(eventId = "second", localStart = LocalDateTime.of(2026, 8, 3, 11, 0), durationMinutes = 45)
            )
        )

        assertThat(validation).isEqualTo(ScheduleValidation.Valid)
    }

    @Test
    fun enabledOverlappingEventsCannotBeSaved() {
        val validation = engine.validate(
            listOf(
                schedule(eventId = "first", localStart = LocalDateTime.of(2026, 8, 3, 10, 0), durationMinutes = 60),
                schedule(eventId = "second", localStart = LocalDateTime.of(2026, 8, 3, 10, 30), durationMinutes = 45)
            )
        )

        assertThat(validation).isInstanceOf(ScheduleValidation.Overlap::class.java)
    }

    @Test
    fun unboundedRecurrencesStartingMoreThanFourHundredDaysApartStillOverlap() {
        val firstStart = LocalDate.of(2026, 8, 3)
        val validation = engine.validate(
            listOf(
                schedule(
                    eventId = "first",
                    localStart = firstStart.atTime(10, 0),
                    durationMinutes = 60,
                    recurrenceDays = setOf(DayOfWeek.MONDAY)
                ),
                schedule(
                    eventId = "second",
                    localStart = firstStart.plusWeeks(60).atTime(10, 30),
                    durationMinutes = 45,
                    recurrenceDays = setOf(DayOfWeek.MONDAY)
                )
            )
        )

        assertThat(validation).isInstanceOf(ScheduleValidation.Overlap::class.java)
    }

    @Test
    fun enabledAndDisabledEventsWithSameIdAreRejected() {
        val validation = engine.validate(
            listOf(
                schedule(eventId = "shared"),
                schedule(eventId = "shared", enabled = false)
            )
        )

        assertThat(validation).isInstanceOf(ScheduleValidation.Invalid::class.java)
    }

    @Test
    fun disabledEventWithBlankIdIsRejected() {
        val validation = engine.validate(listOf(schedule(eventId = "", enabled = false)))

        assertThat(validation).isInstanceOf(ScheduleValidation.Invalid::class.java)
    }

    @Test
    fun occurrencePreservesScheduleRevision() {
        val event = schedule(revision = 7L)

        val occurrence = checkNotNull(engine.materialize(event, event.localStart.toLocalDate()))

        assertThat(occurrence.revision).isEqualTo(7L)
    }

    @Test
    fun occurrenceIsInvalidAfterItsScheduleRevisionChanges() {
        val event = schedule(revision = 7L)
        val occurrence = checkNotNull(engine.materialize(event, event.localStart.toLocalDate()))

        assertThat(engine.isCurrent(occurrence, event.copy(revision = 8L))).isFalse()
    }

    @Test
    fun zoneParsingFallsBackOnlyForOrdinaryExceptions() {
        val event = schedule()
        val date = event.localStart.toLocalDate()

        val malformed = ActivityScheduleEngine(parseZoneId = {
            throw IllegalArgumentException("malformed timezone")
        })
        assertThat(malformed.materialize(event, date)).isNull()

        listOf(
            CancellationException("cancel timezone"),
            SimulatedScheduleVmError(),
            ThreadDeath()
        ).forEach { failure ->
            val failing = ActivityScheduleEngine(parseZoneId = { throw failure })
            var escaped: Throwable? = null
            try {
                failing.materialize(event, date)
            } catch (caught: Throwable) {
                escaped = caught
            }
            assertThat(escaped).isSameInstanceAs(failure)
        }
    }

    private fun schedule(
        eventId: String = "event",
        enabled: Boolean = true,
        localStart: LocalDateTime = LocalDateTime.of(2026, 8, 3, 10, 0),
        durationMinutes: Int = 60,
        timezoneId: String = "Asia/Tbilisi",
        recurrenceDays: Set<DayOfWeek> = emptySet(),
        recurrenceEndEpochDay: Long? = null,
        revision: Long = 1L
    ) = PlannedActivitySchedule(
        eventId = eventId,
        enabled = enabled,
        title = "Walk",
        type = PlannedActivityType.WALKING,
        intensity = PlannedActivityIntensity.LIGHT,
        localStart = localStart,
        durationMinutes = durationMinutes,
        timezoneId = timezoneId,
        recurrenceDays = recurrenceDays,
        recurrenceEndEpochDay = recurrenceEndEpochDay,
        revision = revision,
        createdAtMs = 100L,
        updatedAtMs = 100L
    )
}

private class SimulatedScheduleVmError : VirtualMachineError()
