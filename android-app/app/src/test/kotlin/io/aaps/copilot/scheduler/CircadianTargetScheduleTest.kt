package io.aaps.copilot.scheduler

import com.google.common.truth.Truth.assertThat
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Test

class CircadianTargetScheduleTest {

    @Test
    fun nextRun_beforeNineUsesTodayAtLocalNine() {
        val now = at("2026-07-20T08:30:00", TBILISI)

        val next = CircadianTargetSchedule.nextRun(now)

        assertThat(next).isEqualTo(at("2026-07-20T09:00:00", TBILISI))
        assertThat(CircadianTargetSchedule.initialDelayMillis(now))
            .isEqualTo(Duration.ofMinutes(30).toMillis())
    }

    @Test
    fun nextRun_atOrAfterNineUsesTomorrowAtLocalNine() {
        val atNine = at("2026-07-20T09:00:00", TBILISI)
        val afterNine = at("2026-07-20T18:45:00", TBILISI)

        assertThat(CircadianTargetSchedule.nextRun(atNine))
            .isEqualTo(at("2026-07-21T09:00:00", TBILISI))
        assertThat(CircadianTargetSchedule.nextRun(afterNine))
            .isEqualTo(at("2026-07-21T09:00:00", TBILISI))
    }

    @Test
    fun nextRun_preservesLocalNineAcrossSpringDst() {
        val berlin = ZoneId.of("Europe/Berlin")
        val now = at("2026-03-28T10:00:00", berlin)

        val next = CircadianTargetSchedule.nextRun(now)

        assertThat(next.toLocalDate()).isEqualTo(LocalDate.of(2026, 3, 29))
        assertThat(next.hour).isEqualTo(9)
        assertThat(next.zone).isEqualTo(berlin)
        assertThat(Duration.between(now.toInstant(), next.toInstant()).toHours()).isEqualTo(22L)
    }

    @Test
    fun nextRun_preservesLocalNineAcrossFallDst() {
        val berlin = ZoneId.of("Europe/Berlin")
        val now = at("2026-10-24T10:00:00", berlin)

        val next = CircadianTargetSchedule.nextRun(now)

        assertThat(next.toLocalDate()).isEqualTo(LocalDate.of(2026, 10, 25))
        assertThat(next.hour).isEqualTo(9)
        assertThat(next.zone).isEqualTo(berlin)
        assertThat(Duration.between(now.toInstant(), next.toInstant()).toHours()).isEqualTo(24L)
    }

    @Test
    fun needsCatchUp_onlyAfterNineWhenAutoEnabledAndTodayMissing() {
        val beforeNine = at("2026-07-20T08:59:59", TBILISI)
        val atNine = at("2026-07-20T09:00:00", TBILISI)
        val today = LocalDate.of(2026, 7, 20)

        assertThat(CircadianTargetSchedule.needsCatchUp(true, beforeNine, null)).isFalse()
        assertThat(CircadianTargetSchedule.needsCatchUp(false, atNine, null)).isFalse()
        assertThat(CircadianTargetSchedule.needsCatchUp(true, atNine, today)).isFalse()
        assertThat(CircadianTargetSchedule.needsCatchUp(true, atNine, today.minusDays(1))).isTrue()
    }

    private fun at(local: String, zoneId: ZoneId): ZonedDateTime =
        LocalDateTime.parse(local).atZone(zoneId)

    private companion object {
        val TBILISI: ZoneId = ZoneId.of("Asia/Tbilisi")
    }
}
