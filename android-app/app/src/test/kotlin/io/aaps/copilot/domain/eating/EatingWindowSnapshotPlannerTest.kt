package io.aaps.copilot.domain.eating

import com.google.common.truth.Truth.assertThat
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import org.junit.Test

class EatingWindowSnapshotPlannerTest {

    private val zone: ZoneId = ZoneOffset.ofHours(4)
    private val generatedAt = ZonedDateTime.of(2026, 8, 2, 12, 0, 0, 0, zone)
        .toInstant()
        .toEpochMilli()

    @Test
    fun preservesRecentSevenDayAndStableFourteenDayWindowsSeparately() {
        val evidence = buildList {
            repeat(14) { dayOffset -> add(point(dayOffset, 12, 0, 30.0)) }
            repeat(8) { dayOffset -> add(point(dayOffset + 6, 14, 0, 35.0)) }
            repeat(5) { dayOffset -> add(point(dayOffset, 20, 30, 40.0)) }
        }

        val snapshot = EatingWindowSnapshotPlanner.plan(
            evidence = evidence,
            generatedAt = generatedAt,
            zoneId = zone
        )

        assertThat(snapshot.recent.lookbackDays).isEqualTo(7)
        assertThat(snapshot.stable.lookbackDays).isEqualTo(14)
        assertThat(snapshot.recent.windows.map(ProbableEatingWindow::medianMinuteOfDay))
            .containsExactly(12 * 60, 20 * 60 + 30)
            .inOrder()
        assertThat(snapshot.stable.windows.map(ProbableEatingWindow::medianMinuteOfDay))
            .containsExactly(12 * 60, 14 * 60, 20 * 60 + 30)
            .inOrder()
    }

    private fun point(dayOffset: Int, hour: Int, minute: Int, carbs: Double): EatingEvidencePoint {
        val date = ZonedDateTime.ofInstant(
            java.time.Instant.ofEpochMilli(generatedAt),
            zone
        ).toLocalDate().minusDays(1L + dayOffset)
        return EatingEvidencePoint(
            ts = date.atTime(hour, minute).atZone(zone).toInstant().toEpochMilli(),
            carbsG = carbs,
            syntheticUam = false
        )
    }
}
