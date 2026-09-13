package io.aaps.copilot.scheduler

import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime

object CircadianTargetSchedule {

    private val runTime: LocalTime = LocalTime.of(9, 0)

    fun nextRun(now: ZonedDateTime): ZonedDateTime {
        val today = now.toLocalDate().atTime(runTime).atZone(now.zone)
        return if (now.isBefore(today)) today else {
            now.toLocalDate().plusDays(1).atTime(runTime).atZone(now.zone)
        }
    }

    fun initialDelayMillis(now: ZonedDateTime): Long =
        Duration.between(now.toInstant(), nextRun(now).toInstant())
            .toMillis()
            .coerceAtLeast(MINIMUM_DELAY_MILLIS)

    fun needsCatchUp(
        autoEnabled: Boolean,
        now: ZonedDateTime,
        lastCompletedDate: LocalDate?
    ): Boolean = autoEnabled &&
        !now.toLocalTime().isBefore(runTime) &&
        lastCompletedDate != now.toLocalDate()

    private const val MINIMUM_DELAY_MILLIS = 1_000L
}
