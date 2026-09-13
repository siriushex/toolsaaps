package io.aaps.copilot.domain.eating

import com.google.common.truth.Truth.assertThat
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import org.junit.Test

class EatingWindowAnalyzerTest {

    private val zone: ZoneId = ZoneOffset.ofHours(4)
    private val generatedAt = ZonedDateTime.of(2026, 8, 1, 12, 0, 0, 0, zone)
        .toInstant()
        .toEpochMilli()

    @Test
    fun calculatesProbableWindowsFromFourteenCompletedLocalDays() {
        val points = buildList {
            repeat(10) { dayOffset ->
                add(point(dayOffset, 8, 30 + (dayOffset % 3) * 10, 28.0))
            }
            repeat(8) { dayOffset ->
                add(point(dayOffset, 13, 0 + (dayOffset % 2) * 15, 12.0, syntheticUam = true))
                add(point(dayOffset, 13, 20 + (dayOffset % 2) * 15, 13.0, syntheticUam = true))
            }
            repeat(14) { dayOffset ->
                add(point(dayOffset, 2, 0, 60.0))
            }
        }

        val result = EatingWindowAnalyzer.analyze(
            evidence = points,
            generatedAt = generatedAt,
            zoneId = zone
        )

        assertThat(result.lookbackDays).isEqualTo(14)
        assertThat(result.windows).hasSize(2)
        assertThat(result.windows[0].medianMinuteOfDay).isEqualTo(8 * 60 + 40)
        assertThat(result.windows[0].supportDays).isEqualTo(10)
        assertThat(result.windows[0].enteredEpisodeCount).isEqualTo(10)
        assertThat(result.windows[1].supportDays).isEqualTo(8)
        assertThat(result.windows[1].uamEpisodeCount).isEqualTo(8)
        assertThat(result.excludedNightEpisodeCount).isEqualTo(14)
    }

    @Test
    fun requiresUamEpisodeTotalToBeStrictlyGreaterThanTwentyGrams() {
        val points = buildList {
            repeat(4) { dayOffset ->
                add(point(dayOffset, 12, 0, 10.0, syntheticUam = true))
                add(point(dayOffset, 12, 20, 10.0, syntheticUam = true))
            }
            repeat(4) { dayOffset ->
                add(point(dayOffset, 18, 0, 10.0, syntheticUam = true))
                add(point(dayOffset, 18, 20, 11.0, syntheticUam = true))
            }
        }

        val result = EatingWindowAnalyzer.analyze(points, generatedAt, zone)

        assertThat(result.windows).hasSize(1)
        assertThat(result.windows.single().medianMinuteOfDay).isEqualTo(18 * 60 + 10)
        assertThat(result.windows.single().uamEpisodeCount).isEqualTo(4)
    }

    @Test
    fun subthresholdUamDoesNotShiftAnEnteredMealWindow() {
        val points = buildList {
            repeat(4) { dayOffset ->
                add(point(dayOffset, 8, 0, 30.0))
                add(point(dayOffset, 8, 50, 10.0, syntheticUam = true))
            }
        }

        val result = EatingWindowAnalyzer.analyze(points, generatedAt, zone)

        assertThat(result.windows).hasSize(1)
        assertThat(result.windows.single().medianMinuteOfDay).isEqualTo(8 * 60)
        assertThat(result.windows.single().uamEpisodeCount).isEqualTo(0)
    }

    @Test
    fun keepsAtMostThreeLargestMealEpisodesPerDay() {
        val points = buildList {
            repeat(4) { dayOffset ->
                add(point(dayOffset, 7, 0, 25.0))
                add(point(dayOffset, 11, 0, 30.0))
                add(point(dayOffset, 15, 0, 35.0))
                add(point(dayOffset, 20, 0, 5.0))
            }
        }

        val result = EatingWindowAnalyzer.analyze(points, generatedAt, zone)

        assertThat(result.windows.map { it.medianMinuteOfDay })
            .containsExactly(7 * 60, 11 * 60, 15 * 60)
            .inOrder()
    }

    private fun point(
        dayOffset: Int,
        hour: Int,
        minute: Int,
        carbs: Double,
        syntheticUam: Boolean = false
    ): EatingEvidencePoint {
        val localDate = ZonedDateTime.ofInstant(
            java.time.Instant.ofEpochMilli(generatedAt),
            zone
        ).toLocalDate().minusDays(1L + dayOffset)
        return EatingEvidencePoint(
            ts = localDate.atTime(hour, minute).atZone(zone).toInstant().toEpochMilli(),
            carbsG = carbs,
            syntheticUam = syntheticUam
        )
    }
}
