package io.aaps.copilot.scheduler

import java.time.Duration
import java.time.LocalTime
import java.time.ZonedDateTime

internal object EatingWindowRefreshSchedule {
    private val refreshTime = LocalTime.of(3, 15)

    fun initialDelayMillis(now: ZonedDateTime): Long {
        var scheduled = now.with(refreshTime)
        if (!scheduled.isAfter(now)) scheduled = scheduled.plusDays(1)
        return Duration.between(now, scheduled).toMillis().coerceAtLeast(0L)
    }
}
