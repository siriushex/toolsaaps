package io.aaps.copilot.domain.predict

import com.google.common.truth.Truth.assertThat
import io.aaps.copilot.domain.model.DataQuality
import io.aaps.copilot.domain.model.GlucosePoint
import io.aaps.copilot.domain.model.TherapyEvent
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Test

class PatternAndProfileTest {

    @Test
    fun patternAnalyzer_detectsWeekendWindow() {
        val zone = ZoneId.systemDefault()
        val points = buildList {
            repeat(16) { dayOffset ->
                val saturdayNoon = LocalDateTime.of(2026, 2, 7 + dayOffset, 12, 0)
                    .atZone(zone)
                    .toInstant()
                    .toEpochMilli()
                repeat(12) { idx ->
                    add(
                        GlucosePoint(
                            ts = saturdayNoon + idx * 5 * 60_000L,
                            valueMmol = if (idx % 2 == 0) 3.7 else 4.3,
                            source = "test",
                            quality = DataQuality.OK
                        )
                    )
                }
            }
        }

        val windows = PatternAnalyzer(zone).analyze(
            glucoseHistory = points,
            config = PatternAnalyzerConfig(
                baseTargetMmol = 5.5,
                minSamplesPerWindow = 20,
                minActiveDaysPerWindow = 4,
                lowRateTrigger = 0.10,
                highRateTrigger = 0.20
            )
        )
        val weekendNoon = windows.firstOrNull { it.dayType.name == "WEEKEND" && it.hour == 12 }

        assertThat(weekendNoon).isNotNull()
        assertThat(weekendNoon!!.lowRate).isGreaterThan(0.3)
        assertThat(weekendNoon.isRiskWindow).isTrue()
        assertThat(weekendNoon.sampleCount).isAtLeast(40)
        assertThat(weekendNoon.activeDays).isAtLeast(4)
        assertThat(weekendNoon.recommendedTargetMmol).isGreaterThan(5.5)
    }

    @Test
    fun profileEstimator_returnsNonNull_whenDataEnough() {
        val now = System.currentTimeMillis()
        val glucose = buildList {
            repeat(4) { idx ->
                val eventTs = now - (idx + 2) * 4L * 60 * 60 * 1000
                add(GlucosePoint(eventTs - 10 * 60_000, 9.2, "test", DataQuality.OK))
                add(GlucosePoint(eventTs + 90 * 60_000, 7.0, "test", DataQuality.OK))
            }
        }
        val therapy = buildList {
            repeat(4) { idx ->
                val eventTs = now - (idx + 2) * 4L * 60 * 60 * 1000
                add(
                    TherapyEvent(
                        ts = eventTs,
                        type = "correction_bolus",
                        payload = mapOf("units" to "1.0")
                    )
                )
                add(
                    TherapyEvent(
                        ts = eventTs - 120 * 60_000,
                        type = "meal_bolus",
                        payload = mapOf("grams" to "30", "bolusUnits" to "3")
                    )
                )
            }
        }

        val estimate = ProfileEstimator(
            ProfileEstimatorConfig(
                minIsfSamples = 2,
                minCrSamples = 2,
                trimFraction = 0.0,
                lookbackDays = 365
            )
        ).estimate(glucose, therapy)

        assertThat(estimate).isNotNull()
        assertThat(estimate!!.isfMmolPerUnit).isWithin(0.01).of(2.2)
        assertThat(estimate.crGramPerUnit).isWithin(0.01).of(10.0)
        assertThat(estimate.isfSampleCount).isAtLeast(2)
        assertThat(estimate.crSampleCount).isAtLeast(2)
        assertThat(estimate.lookbackDays).isEqualTo(365)
    }
}
