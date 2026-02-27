package io.aaps.copilot.domain.predict

import com.google.common.truth.Truth.assertThat
import io.aaps.copilot.domain.model.DataQuality
import io.aaps.copilot.domain.model.DayType
import io.aaps.copilot.domain.model.GlucosePoint
import io.aaps.copilot.domain.model.ProfileTimeSlot
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

    @Test
    fun profileEstimator_buildsSegments_byDayTypeAndTimeSlot() {
        val zone = ZoneId.systemDefault()
        val saturdayMorning = LocalDateTime.of(2026, 2, 21, 8, 0).atZone(zone).toInstant().toEpochMilli()
        val mondayEvening = LocalDateTime.of(2026, 2, 23, 20, 0).atZone(zone).toInstant().toEpochMilli()

        val glucose = listOf(
            GlucosePoint(saturdayMorning - 10 * 60_000, 9.0, "test", DataQuality.OK),
            GlucosePoint(saturdayMorning + 90 * 60_000, 7.2, "test", DataQuality.OK),
            GlucosePoint((saturdayMorning + 24 * 60 * 60_000L) - 10 * 60_000, 8.8, "test", DataQuality.OK),
            GlucosePoint((saturdayMorning + 24 * 60 * 60_000L) + 90 * 60_000, 7.0, "test", DataQuality.OK),
            GlucosePoint(mondayEvening - 10 * 60_000, 10.0, "test", DataQuality.OK),
            GlucosePoint(mondayEvening + 90 * 60_000, 8.4, "test", DataQuality.OK),
            GlucosePoint((mondayEvening + 24 * 60 * 60_000L) - 10 * 60_000, 9.6, "test", DataQuality.OK),
            GlucosePoint((mondayEvening + 24 * 60 * 60_000L) + 90 * 60_000, 8.0, "test", DataQuality.OK)
        )

        val therapy = listOf(
            TherapyEvent(saturdayMorning, "correction_bolus", mapOf("units" to "1.0")),
            TherapyEvent(saturdayMorning - 120 * 60_000, "meal_bolus", mapOf("grams" to "24", "bolusUnits" to "2")),
            TherapyEvent(saturdayMorning + 24 * 60 * 60_000L, "correction_bolus", mapOf("units" to "1.0")),
            TherapyEvent((saturdayMorning + 24 * 60 * 60_000L) - 120 * 60_000, "meal_bolus", mapOf("grams" to "30", "bolusUnits" to "3")),
            TherapyEvent(mondayEvening, "correction_bolus", mapOf("units" to "1.0")),
            TherapyEvent(mondayEvening - 120 * 60_000, "meal_bolus", mapOf("grams" to "36", "bolusUnits" to "3")),
            TherapyEvent(mondayEvening + 24 * 60 * 60_000L, "correction_bolus", mapOf("units" to "1.0")),
            TherapyEvent((mondayEvening + 24 * 60 * 60_000L) - 120 * 60_000, "meal_bolus", mapOf("grams" to "28", "bolusUnits" to "2"))
        )

        val segments = ProfileEstimator(
            ProfileEstimatorConfig(minSegmentSamples = 2, trimFraction = 0.0)
        ).estimateSegments(glucose, therapy)

        val weekendMorning = segments.firstOrNull {
            it.dayType == DayType.WEEKEND && it.timeSlot == ProfileTimeSlot.MORNING
        }
        val weekdayEvening = segments.firstOrNull {
            it.dayType == DayType.WEEKDAY && it.timeSlot == ProfileTimeSlot.EVENING
        }

        assertThat(weekendMorning).isNotNull()
        assertThat(weekdayEvening).isNotNull()
        assertThat(weekendMorning!!.crGramPerUnit).isWithin(0.01).of(11.0)
        assertThat(weekdayEvening!!.isfMmolPerUnit).isWithin(0.01).of(1.6)
    }

    @Test
    fun profileEstimator_usesTelemetryForIsfAndCr_whenTherapySparse() {
        val now = System.currentTimeMillis()
        val glucose = listOf(
            GlucosePoint(ts = now, valueMmol = 6.0, source = "test", quality = DataQuality.OK)
        )
        val telemetry = listOf(
            TelemetrySignal(ts = now - 60_000, key = "isf_value", valueDouble = 54.0),
            TelemetrySignal(ts = now - 60_000, key = "cr_value", valueDouble = 11.0)
        )

        val estimate = ProfileEstimator(
            ProfileEstimatorConfig(
                minIsfSamples = 1,
                minCrSamples = 1,
                trimFraction = 0.0
            )
        ).estimate(glucose, therapyEvents = emptyList(), telemetrySignals = telemetry)

        assertThat(estimate).isNotNull()
        assertThat(estimate!!.isfMmolPerUnit).isWithin(0.02).of(3.0)
        assertThat(estimate.crGramPerUnit).isWithin(0.01).of(11.0)
        assertThat(estimate.telemetryIsfSampleCount).isEqualTo(1)
        assertThat(estimate.telemetryCrSampleCount).isEqualTo(1)
    }

    @Test
    fun profileEstimator_filtersIsfSamples_whenUamObservedNearCorrection() {
        val now = System.currentTimeMillis()
        val glucose = listOf(
            GlucosePoint(now - 10 * 60_000, 9.2, "test", DataQuality.OK),
            GlucosePoint(now + 90 * 60_000, 7.0, "test", DataQuality.OK)
        )
        val therapy = listOf(
            TherapyEvent(
                ts = now,
                type = "correction_bolus",
                payload = mapOf("units" to "1.0")
            )
        )
        val telemetry = listOf(
            TelemetrySignal(ts = now + 5 * 60_000, key = "ns_openaps_suggested_predbg_uam_0", valueDouble = 1.0),
            TelemetrySignal(ts = now - 30_000, key = "isf_value", valueDouble = 50.0),
            TelemetrySignal(ts = now - 30_000, key = "cr_value", valueDouble = 10.0)
        )

        val estimate = ProfileEstimator(
            ProfileEstimatorConfig(
                minIsfSamples = 1,
                minCrSamples = 1,
                trimFraction = 0.0
            )
        ).estimate(glucose, therapy, telemetry)

        assertThat(estimate).isNotNull()
        assertThat(estimate!!.uamObservedCount).isEqualTo(1)
        assertThat(estimate.uamFilteredIsfSamples).isAtLeast(1)
    }
}
