package io.aaps.copilot.data.repository

import com.google.common.truth.Truth.assertThat
import io.aaps.copilot.data.local.entity.ForecastEntity
import io.aaps.copilot.domain.model.CircadianDayType
import io.aaps.copilot.domain.model.DataQuality
import io.aaps.copilot.domain.model.GlucosePoint
import io.aaps.copilot.domain.target.CircadianTargetSlot
import java.time.Instant
import java.time.ZoneOffset
import org.junit.Test

class CircadianLowerTailReplayEvaluatorTest {

    @Test
    fun computesWorstMatureLowerBoundRmseAcrossThirtyAndSixtyMinutes() {
        val glucose = mutableListOf<GlucosePoint>()
        val forecasts = mutableListOf<ForecastEntity>()
        repeat(7) { daysAgo ->
            listOf(30, 60).forEach { horizon ->
                val targetTs = NOW - daysAgo * DAY_MS
                glucose += GlucosePoint(targetTs, 5.0, "calibrated", DataQuality.OK)
                forecasts += ForecastEntity(
                    id = (daysAgo * 10 + horizon).toLong(),
                    timestamp = targetTs,
                    horizonMinutes = horizon,
                    valueMmol = 6.0,
                    ciLow = if (horizon == 30) 5.3 else 5.5,
                    ciHigh = 7.0,
                    modelVersion = "test"
                )
            }
        }

        val result = calculateCircadianLowerTailReplayErrors(
            forecasts = forecasts,
            canonicalGlucose = glucose,
            zoneId = ZoneOffset.UTC
        )

        assertThat(result[CircadianTargetSlot(CircadianDayType.ALL, 9)])
            .isWithin(1e-9)
            .of(0.5)
    }

    @Test
    fun requiresBothHorizonsAndSevenDayCoverageForAllDaySlot() {
        val glucose = mutableListOf<GlucosePoint>()
        val forecasts = mutableListOf<ForecastEntity>()
        repeat(6) { daysAgo ->
            val targetTs = NOW - daysAgo * DAY_MS
            glucose += GlucosePoint(targetTs, 5.0, "calibrated", DataQuality.OK)
            listOf(30, 60).forEach { horizon ->
                forecasts += ForecastEntity(
                    id = (daysAgo * 10 + horizon).toLong(),
                    timestamp = targetTs,
                    horizonMinutes = horizon,
                    valueMmol = 6.0,
                    ciLow = 5.5,
                    ciHigh = 7.0,
                    modelVersion = "test"
                )
            }
        }

        val result = calculateCircadianLowerTailReplayErrors(
            forecasts = forecasts,
            canonicalGlucose = glucose,
            zoneId = ZoneOffset.UTC
        )

        assertThat(result).doesNotContainKey(CircadianTargetSlot(CircadianDayType.ALL, 9))
    }

    @Test
    fun ignoresStaleOrMalformedActualEvidence() {
        val targetTs = NOW
        val forecasts = listOf(
            ForecastEntity(
                id = 1L,
                timestamp = targetTs,
                horizonMinutes = 30,
                valueMmol = 6.0,
                ciLow = 5.5,
                ciHigh = 7.0,
                modelVersion = "test"
            )
        )
        val glucose = listOf(
            GlucosePoint(targetTs, 5.0, "calibrated", DataQuality.STALE),
            GlucosePoint(targetTs + 20 * 60_000L, 5.0, "calibrated", DataQuality.OK)
        )

        val result = calculateCircadianLowerTailReplayErrors(
            forecasts = forecasts,
            canonicalGlucose = glucose,
            zoneId = ZoneOffset.UTC
        )

        assertThat(result).isEmpty()
    }

    private companion object {
        val NOW: Long = Instant.parse("2026-07-20T09:30:00Z").toEpochMilli()
        const val DAY_MS = 24L * 60L * 60L * 1_000L
    }
}
