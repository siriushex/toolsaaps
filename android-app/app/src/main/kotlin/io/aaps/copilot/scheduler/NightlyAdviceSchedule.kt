package io.aaps.copilot.scheduler

import java.time.LocalTime
import java.time.ZonedDateTime

internal object NightlyAdviceSchedule {
    private val time = LocalTime.of(5, 0)

    fun windowEnd(now: ZonedDateTime): ZonedDateTime {
        val today = now.with(time)
        return if (now.isBefore(today)) today.minusDays(1) else today
    }

    fun next(now: ZonedDateTime): ZonedDateTime = windowEnd(now).plusDays(1)
}
