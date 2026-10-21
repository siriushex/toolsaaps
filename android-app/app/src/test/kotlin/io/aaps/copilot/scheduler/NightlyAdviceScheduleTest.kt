package io.aaps.copilot.scheduler

import java.time.ZonedDateTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class NightlyAdviceScheduleTest {
    @Test fun beforeFiveUsesPreviousNightAndNextIsToday() {
        val now = ZonedDateTime.parse("2026-10-17T04:59:00+04:00[Asia/Tbilisi]")
        assertEquals(now.minusDays(1).withHour(5).withMinute(0), NightlyAdviceSchedule.windowEnd(now))
        assertEquals(now.withHour(5).withMinute(0), NightlyAdviceSchedule.next(now))
    }

    @Test fun fiveIsDueAndNextIsTomorrow() {
        val now = ZonedDateTime.parse("2026-10-17T05:00:00+04:00[Asia/Tbilisi]")
        assertEquals(now, NightlyAdviceSchedule.windowEnd(now))
        assertEquals(now.plusDays(1), NightlyAdviceSchedule.next(now))
    }

    @Test fun localFiveDoesNotDriftAcrossDaylightSaving() {
        val now = ZonedDateTime.parse("2026-10-24T12:00:00+02:00[Europe/Berlin]")
        val next = NightlyAdviceSchedule.next(now)
        assertEquals(5, next.hour)
        assertEquals("+01:00", next.offset.toString())
    }

    @Test fun powerSaveDoesNotReadHistoryOrRescheduleHeavyWork() = runTest {
        assertFalse(refreshNightlyAdvice(true, { error("history") }, { error("reschedule") }))
    }

    @Test fun successReschedulesOnlyAfterCommittedRefresh() = runTest {
        val calls = mutableListOf<String>()
        assertTrue(refreshNightlyAdvice(false, { calls += "refresh" }, { calls += "schedule" }))
        assertEquals(listOf("refresh", "schedule"), calls)
    }

    @Test fun failureCannotBeReportedAsACompletedNight() = runTest {
        val failure = IllegalStateException("read failed")
        val result = runCatching { refreshNightlyAdvice(false, { throw failure }, { error("schedule") }) }
        assertSame(failure, result.exceptionOrNull())
    }
}
