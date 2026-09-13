package io.aaps.copilot.domain.profile

import io.aaps.copilot.util.ordinaryExceptionOrNull
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

enum class PlannedActivityType { WALKING, AEROBIC, STRENGTH, MIXED }

enum class PlannedActivityIntensity { LIGHT, MEDIUM, HIGH }

enum class DstResolution { EXACT, EARLIER_OFFSET, MOVED_FORWARD }

data class PlannedActivitySchedule(
    val eventId: String,
    val enabled: Boolean,
    val title: String,
    val type: PlannedActivityType,
    val intensity: PlannedActivityIntensity,
    val localStart: LocalDateTime,
    val durationMinutes: Int,
    val timezoneId: String,
    val recurrenceDays: Set<DayOfWeek>,
    val recurrenceEndEpochDay: Long?,
    val revision: Long,
    val createdAtMs: Long,
    val updatedAtMs: Long
)

data class PlannedActivityOccurrence(
    val eventId: String,
    val revision: Long,
    val type: PlannedActivityType,
    val intensity: PlannedActivityIntensity,
    val start: Instant,
    val end: Instant,
    val evaluationStarts: Instant,
    val dstResolution: DstResolution
)

sealed interface ScheduleValidation {
    data object Valid : ScheduleValidation

    data class Invalid(
        val eventIds: Set<String>,
        val code: String
    ) : ScheduleValidation

    data class Overlap(
        val eventIds: Set<String>
    ) : ScheduleValidation
}

sealed interface PlannedActivityScheduleSaveResult {
    data object Saved : PlannedActivityScheduleSaveResult

    data class Invalid(
        val validation: ScheduleValidation
    ) : PlannedActivityScheduleSaveResult
}

class ActivityScheduleEngine(
    private val parseZoneId: (String) -> ZoneId = ZoneId::of
) {

    fun leadMinutes(intensity: PlannedActivityIntensity): Long = when (intensity) {
        PlannedActivityIntensity.LIGHT -> 60L
        PlannedActivityIntensity.MEDIUM -> 75L
        PlannedActivityIntensity.HIGH -> 90L
    }

    fun materialize(
        event: PlannedActivitySchedule,
        date: LocalDate,
        beforeOccurrence: () -> Unit = {}
    ): PlannedActivityOccurrence? {
        if (!event.enabled || !event.occursOn(date) || !event.hasValidDefinition()) return null
        val zone = ordinaryExceptionOrNull { parseZoneId(event.timezoneId) } ?: return null
        val localStart = LocalDateTime.of(date, event.localStart.toLocalTime())
        val (start, dstResolution) = resolveStart(localStart, zone) ?: return null
        beforeOccurrence()
        return PlannedActivityOccurrence(
            eventId = event.eventId,
            revision = event.revision,
            type = event.type,
            intensity = event.intensity,
            start = start,
            end = start.plusSeconds(event.durationMinutes * SECONDS_PER_MINUTE),
            evaluationStarts = start.minusSeconds(leadMinutes(event.intensity) * SECONDS_PER_MINUTE),
            dstResolution = dstResolution
        )
    }

    fun isCurrent(
        occurrence: PlannedActivityOccurrence,
        event: PlannedActivitySchedule
    ): Boolean = occurrence.eventId == event.eventId && occurrence.revision == event.revision

    fun validate(events: List<PlannedActivitySchedule>): ScheduleValidation {
        events.groupBy(PlannedActivitySchedule::eventId)
            .firstNotNullOfOrNull { (eventId, duplicates) ->
                eventId.takeIf { it.isBlank() || duplicates.size > 1 }
            }
            ?.let { return ScheduleValidation.Invalid(setOf(it), DUPLICATE_OR_BLANK_EVENT_ID) }

        val enabledEvents = events.filter(PlannedActivitySchedule::enabled)
        enabledEvents.firstOrNull { !it.hasValidDefinition() }
            ?.let { return ScheduleValidation.Invalid(setOf(it.eventId), INVALID_EVENT) }

        val sharedHorizonEnd = enabledEvents.maxOfOrNull {
            it.localStart.toLocalDate()
        }?.plusDays(RECURRENCE_VALIDATION_DAYS)
        val occurrences = enabledEvents.flatMap { event ->
            validationDates(event, checkNotNull(sharedHorizonEnd)).mapNotNull { date ->
                materialize(event, date)
            }
        }.sortedWith(compareBy(PlannedActivityOccurrence::start, PlannedActivityOccurrence::end))

        occurrences.indices.forEach { leftIndex ->
            val left = occurrences[leftIndex]
            for (rightIndex in leftIndex + 1 until occurrences.size) {
                val right = occurrences[rightIndex]
                if (right.start >= left.end) break
                if (left.overlaps(right)) {
                    return ScheduleValidation.Overlap(setOf(left.eventId, right.eventId))
                }
            }
        }
        return ScheduleValidation.Valid
    }

    private fun PlannedActivitySchedule.occursOn(date: LocalDate): Boolean {
        val firstDate = localStart.toLocalDate()
        if (date < firstDate || recurrenceEndEpochDay?.let { date.toEpochDay() > it } == true) return false
        return recurrenceDays.isEmpty() && date == firstDate ||
            recurrenceDays.isNotEmpty() && date.dayOfWeek in recurrenceDays
    }

    private fun PlannedActivitySchedule.hasValidDefinition(): Boolean =
        eventId.isNotBlank() &&
            title.isNotBlank() &&
            durationMinutes > 0 &&
            recurrenceEndEpochDay?.let { it >= localStart.toLocalDate().toEpochDay() } != false &&
            revision >= 0L &&
            ordinaryExceptionOrNull { parseZoneId(timezoneId) } != null

    private fun validationDates(
        event: PlannedActivitySchedule,
        sharedHorizonEnd: LocalDate
    ): Sequence<LocalDate> {
        val firstDate = event.localStart.toLocalDate()
        if (event.recurrenceDays.isEmpty()) return sequenceOf(firstDate)
        val lastDate = event.recurrenceEndEpochDay
            ?.let(LocalDate::ofEpochDay)
            ?.coerceAtMost(sharedHorizonEnd)
            ?: sharedHorizonEnd
        return generateSequence(firstDate) { it.plusDays(1) }.takeWhile { it <= lastDate }
    }

    private fun resolveStart(localStart: LocalDateTime, zone: ZoneId): Pair<Instant, DstResolution>? {
        val rules = zone.rules
        val offsets = rules.getValidOffsets(localStart)
        return when (offsets.size) {
            1 -> localStart.atOffset(offsets.single()).toInstant() to DstResolution.EXACT
            2 -> localStart.atOffset(offsets.first()).toInstant() to DstResolution.EARLIER_OFFSET
            0 -> rules.getTransition(localStart)?.let { transition ->
                transition.dateTimeAfter.atOffset(transition.offsetAfter).toInstant() to
                    DstResolution.MOVED_FORWARD
            }
            else -> null
        }
    }

    private fun PlannedActivityOccurrence.overlaps(other: PlannedActivityOccurrence): Boolean =
        start < other.end && other.start < end

    private companion object {
        const val SECONDS_PER_MINUTE = 60L
        const val RECURRENCE_VALIDATION_DAYS = 400L
        const val DUPLICATE_OR_BLANK_EVENT_ID = "duplicate_or_blank_event_id"
        const val INVALID_EVENT = "invalid_event"

    }
}
