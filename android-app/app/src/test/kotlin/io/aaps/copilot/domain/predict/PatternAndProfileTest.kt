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
    fun profileEstimator_usesMinDropIn60to240Window_forRealIsf() {
        val now = System.currentTimeMillis()
        val correctionTs = now - 6 * 60 * 60_000L
        val glucose = listOf(
            GlucosePoint(correctionTs, 10.0, "test", DataQuality.OK),
            GlucosePoint(correctionTs + 90 * 60_000L, 8.6, "test", DataQuality.OK),
            GlucosePoint(correctionTs + 180 * 60_000L, 7.0, "test", DataQuality.OK),
            GlucosePoint(correctionTs + 210 * 60_000L, 7.2, "test", DataQuality.OK)
        )
        val therapy = listOf(
            TherapyEvent(correctionTs, "correction_bolus", mapOf("units" to "1.0")),
            TherapyEvent(correctionTs - 4 * 60 * 60_000L, "meal_bolus", mapOf("grams" to "30", "bolusUnits" to "3"))
        )

        val estimate = ProfileEstimator(
            ProfileEstimatorConfig(
                minIsfSamples = 1,
                minCrSamples = 1,
                trimFraction = 0.0
            )
        ).estimate(glucose, therapy)

        assertThat(estimate).isNotNull()
        // drop = 10.0 - 7.0 over 60..240m window
        assertThat(estimate!!.isfMmolPerUnit).isWithin(0.01).of(3.0)
    }

    @Test
    fun profileEstimator_skipsCorrectionAsMeal_whenCarbsNearby() {
        val now = System.currentTimeMillis()
        val correctionTs = now - 8 * 60 * 60_000L
        val glucose = listOf(
            GlucosePoint(correctionTs, 9.8, "test", DataQuality.OK),
            GlucosePoint(correctionTs + 90 * 60_000L, 7.6, "test", DataQuality.OK),
            GlucosePoint(correctionTs + 180 * 60_000L, 7.0, "test", DataQuality.OK)
        )
        val therapy = listOf(
            TherapyEvent(correctionTs, "correction_bolus", mapOf("units" to "1.0")),
            TherapyEvent(correctionTs + 10 * 60_000L, "carbs", mapOf("grams" to "20"))
        )

        val estimate = ProfileEstimator(
            ProfileEstimatorConfig(
                minIsfSamples = 1,
                minCrSamples = 1,
                trimFraction = 0.0
            )
        ).estimate(glucose, therapy, telemetrySignals = emptyList())

        assertThat(estimate).isNull()
    }

    @Test
    fun profileEstimator_treatsPlainBolusWithoutCarbs_asCorrectionForRealIsf() {
        val now = System.currentTimeMillis()
        val bolusTs = now - 7 * 60 * 60_000L
        val glucose = listOf(
            GlucosePoint(bolusTs, 9.2, "test", DataQuality.OK),
            GlucosePoint(bolusTs + 70 * 60_000L, 8.1, "test", DataQuality.OK),
            GlucosePoint(bolusTs + 120 * 60_000L, 7.4, "test", DataQuality.OK),
            GlucosePoint(bolusTs + 200 * 60_000L, 7.1, "test", DataQuality.OK)
        )
        val therapy = listOf(
            TherapyEvent(bolusTs, "bolus", mapOf("units" to "1.0")),
            TherapyEvent(bolusTs - 3 * 60 * 60_000L, "meal_bolus", mapOf("grams" to "30", "bolusUnits" to "3"))
        )

        val estimate = ProfileEstimator(
            ProfileEstimatorConfig(
                minIsfSamples = 1,
                minCrSamples = 1,
                trimFraction = 0.0
            )
        ).estimate(glucose, therapy)

        assertThat(estimate).isNotNull()
        assertThat(estimate!!.isfMmolPerUnit).isWithin(0.01).of(2.1)
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
    fun profileEstimator_buildsHourlyRealIsfCr() {
        val zone = ZoneId.systemDefault()
        val day = LocalDateTime.of(2026, 2, 24, 0, 0).atZone(zone).toInstant().toEpochMilli()
        val h8 = day + 8 * 60 * 60_000L
        val h14 = day + 14 * 60 * 60_000L
        val h20 = day + 20 * 60 * 60_000L

        val glucose = listOf(
            GlucosePoint(h8 - 10 * 60_000, 9.0, "test", DataQuality.OK),
            GlucosePoint(h8 + 90 * 60_000, 7.0, "test", DataQuality.OK),
            GlucosePoint(h20 - 10 * 60_000, 10.0, "test", DataQuality.OK),
            GlucosePoint(h20 + 90 * 60_000, 8.5, "test", DataQuality.OK)
        )
        val therapy = listOf(
            TherapyEvent(h8, "correction_bolus", mapOf("units" to "1.0")),
            TherapyEvent(h20, "correction_bolus", mapOf("units" to "1.0")),
            TherapyEvent(h14, "meal_bolus", mapOf("grams" to "30", "bolusUnits" to "3"))
        )

        val hourly = ProfileEstimator(
            ProfileEstimatorConfig(minSegmentSamples = 1, trimFraction = 0.0)
        ).estimateHourly(glucose, therapy)

        val hour8 = hourly.firstOrNull { it.hour == 8 }
        val hour14 = hourly.firstOrNull { it.hour == 14 }
        val hour20 = hourly.firstOrNull { it.hour == 20 }

        assertThat(hour8).isNotNull()
        assertThat(hour14).isNotNull()
        assertThat(hour20).isNotNull()
        assertThat(hour8!!.isfMmolPerUnit).isWithin(0.01).of(2.0)
        assertThat(hour20!!.isfMmolPerUnit).isWithin(0.01).of(1.5)
        assertThat(hour14!!.crGramPerUnit).isWithin(0.01).of(10.0)
    }

    @Test
    fun profileEstimator_buildsHourlyByDayType() {
        val zone = ZoneId.systemDefault()
        val weekday = LocalDateTime.of(2026, 2, 24, 9, 0).atZone(zone).toInstant().toEpochMilli() // Tuesday
        val weekend = LocalDateTime.of(2026, 2, 28, 9, 0).atZone(zone).toInstant().toEpochMilli() // Saturday
        val weekdayMeal = LocalDateTime.of(2026, 2, 24, 12, 0).atZone(zone).toInstant().toEpochMilli()
        val weekendMeal = LocalDateTime.of(2026, 2, 28, 12, 0).atZone(zone).toInstant().toEpochMilli()

        val glucose = listOf(
            GlucosePoint(weekday - 10 * 60_000, 9.5, "test", DataQuality.OK),
            GlucosePoint(weekday + 90 * 60_000, 7.5, "test", DataQuality.OK),
            GlucosePoint(weekend - 10 * 60_000, 10.0, "test", DataQuality.OK),
            GlucosePoint(weekend + 90 * 60_000, 8.0, "test", DataQuality.OK)
        )
        val therapy = listOf(
            TherapyEvent(weekday, "correction_bolus", mapOf("units" to "1.0")),
            TherapyEvent(weekend, "correction_bolus", mapOf("units" to "1.0")),
            TherapyEvent(weekdayMeal, "meal_bolus", mapOf("grams" to "30", "bolusUnits" to "3")),
            TherapyEvent(weekendMeal, "meal_bolus", mapOf("grams" to "24", "bolusUnits" to "2"))
        )

        val rows = ProfileEstimator(
            ProfileEstimatorConfig(minSegmentSamples = 1, trimFraction = 0.0)
        ).estimateHourlyByDayType(glucose, therapy)

        val weekdayIsfRow = rows.firstOrNull { it.dayType == DayType.WEEKDAY && it.hour == 9 }
        val weekendIsfRow = rows.firstOrNull { it.dayType == DayType.WEEKEND && it.hour == 9 }
        val weekdayCrRow = rows.firstOrNull { it.dayType == DayType.WEEKDAY && it.hour == 12 }
        val weekendCrRow = rows.firstOrNull { it.dayType == DayType.WEEKEND && it.hour == 12 }

        assertThat(weekdayIsfRow).isNotNull()
        assertThat(weekendIsfRow).isNotNull()
        assertThat(weekdayCrRow).isNotNull()
        assertThat(weekendCrRow).isNotNull()

        assertThat(weekdayIsfRow!!.isfMmolPerUnit).isWithin(0.01).of(2.0)
        assertThat(weekendIsfRow!!.isfMmolPerUnit).isWithin(0.01).of(2.0)
        assertThat(weekdayCrRow!!.crGramPerUnit).isWithin(0.01).of(10.0)
        assertThat(weekendCrRow!!.crGramPerUnit).isWithin(0.01).of(12.0)
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
            GlucosePoint(now - 35 * 60_000, 5.8, "test", DataQuality.OK),
            GlucosePoint(now - 30 * 60_000, 6.0, "test", DataQuality.OK),
            GlucosePoint(now - 25 * 60_000, 6.2, "test", DataQuality.OK),
            GlucosePoint(now - 20 * 60_000, 6.5, "test", DataQuality.OK),
            GlucosePoint(now - 15 * 60_000, 6.9, "test", DataQuality.OK),
            GlucosePoint(now - 10 * 60_000, 7.3, "test", DataQuality.OK),
            GlucosePoint(now - 5 * 60_000, 7.7, "test", DataQuality.OK),
            GlucosePoint(now, 8.1, "test", DataQuality.OK),
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
        assertThat(estimate!!.uamObservedCount).isAtLeast(1)
        assertThat(estimate.uamFilteredIsfSamples).isAtLeast(1)
    }

    @Test
    fun profileEstimator_ignoresPredictedUamBgSeries_whenEstimatingUam() {
        val now = System.currentTimeMillis()
        val glucose = listOf(
            GlucosePoint(now - 10 * 60_000, 9.0, "test", DataQuality.OK),
            GlucosePoint(now + 90 * 60_000, 7.2, "test", DataQuality.OK)
        )
        val therapy = listOf(
            TherapyEvent(
                ts = now,
                type = "correction_bolus",
                payload = mapOf("units" to "1.0")
            )
        )
        val telemetry = listOf(
            TelemetrySignal(ts = now + 5 * 60_000, key = "raw_predbgs_uam_0", valueDouble = 155.0),
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
        assertThat(estimate!!.uamObservedCount).isEqualTo(0)
        assertThat(estimate.uamFilteredIsfSamples).isEqualTo(0)
        assertThat(estimate.uamEstimatedCarbsGrams).isWithin(0.001).of(0.0)
    }

    @Test
    fun profileEstimator_skipsUamCarbEstimate_whenAnnouncedCarbsNearby() {
        val now = System.currentTimeMillis()
        val glucose = listOf(
            GlucosePoint(now, 6.0, "test", DataQuality.OK),
            GlucosePoint(now + 5 * 60_000, 6.0, "test", DataQuality.OK),
            GlucosePoint(now + 10 * 60_000, 6.1, "test", DataQuality.OK),
            GlucosePoint(now + 15 * 60_000, 6.3, "test", DataQuality.OK),
            GlucosePoint(now + 20 * 60_000, 6.6, "test", DataQuality.OK),
            GlucosePoint(now + 25 * 60_000, 7.0, "test", DataQuality.OK),
            GlucosePoint(now + 30 * 60_000, 7.4, "test", DataQuality.OK),
            GlucosePoint(now + 35 * 60_000, 7.7, "test", DataQuality.OK)
        )
        val therapy = listOf(
            TherapyEvent(now + 2 * 60_000, "carbs", mapOf("grams" to "18"))
        )
        val telemetry = listOf(
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
        assertThat(estimate!!.uamObservedCount).isEqualTo(0)
        assertThat(estimate.uamEpisodeCount).isEqualTo(0)
        assertThat(estimate.uamEstimatedCarbsGrams).isWithin(0.001).of(0.0)
        assertThat(estimate.uamEstimatedRecentCarbsGrams).isWithin(0.001).of(0.0)
    }

    @Test
    fun profileEstimator_requiresAtLeastTwoUamPoints_forUamCarbEpisode() {
        val now = System.currentTimeMillis()
        val glucose = listOf(
            GlucosePoint(now, 6.0, "test", DataQuality.OK),
            GlucosePoint(now + 5 * 60_000, 6.0, "test", DataQuality.OK),
            GlucosePoint(now + 10 * 60_000, 6.2, "test", DataQuality.OK),
            GlucosePoint(now + 15 * 60_000, 6.5, "test", DataQuality.OK),
            GlucosePoint(now + 20 * 60_000, 6.9, "test", DataQuality.OK),
            GlucosePoint(now + 25 * 60_000, 7.2, "test", DataQuality.OK),
            GlucosePoint(now + 30 * 60_000, 7.4, "test", DataQuality.OK),
            GlucosePoint(now + 35 * 60_000, 7.45, "test", DataQuality.OK)
        )
        val telemetry = listOf(
            TelemetrySignal(ts = now - 30_000, key = "isf_value", valueDouble = 50.0),
            TelemetrySignal(ts = now - 30_000, key = "cr_value", valueDouble = 10.0)
        )

        val estimate = ProfileEstimator(
            ProfileEstimatorConfig(
                minIsfSamples = 1,
                minCrSamples = 1,
                trimFraction = 0.0
            )
        ).estimate(glucose, therapyEvents = emptyList(), telemetrySignals = telemetry)

        assertThat(estimate).isNotNull()
        assertThat(estimate!!.uamObservedCount).isAtMost(1)
        assertThat(estimate.uamEpisodeCount).isEqualTo(0)
        assertThat(estimate.uamEstimatedCarbsGrams).isWithin(0.001).of(0.0)
    }

    @Test
    fun profileEstimator_prefersHistorySamples_overTelemetry_whenHistorySufficient() {
        val now = System.currentTimeMillis()
        val correctionTs1 = now - 10 * 60 * 60_000L
        val correctionTs2 = now - 6 * 60 * 60_000L
        val mealTs1 = now - 12 * 60 * 60_000L
        val mealTs2 = now - 8 * 60 * 60_000L

        val glucose = listOf(
            GlucosePoint(correctionTs1, 9.0, "test", DataQuality.OK),
            GlucosePoint(correctionTs1 + 90 * 60_000L, 7.0, "test", DataQuality.OK),
            GlucosePoint(correctionTs2, 10.0, "test", DataQuality.OK),
            GlucosePoint(correctionTs2 + 90 * 60_000L, 8.0, "test", DataQuality.OK)
        )
        val therapy = listOf(
            TherapyEvent(correctionTs1, "correction_bolus", mapOf("units" to "1.0")),
            TherapyEvent(correctionTs2, "correction_bolus", mapOf("units" to "1.0")),
            TherapyEvent(mealTs1, "meal_bolus", mapOf("grams" to "30", "bolusUnits" to "3.0")),
            TherapyEvent(mealTs2, "meal_bolus", mapOf("grams" to "24", "bolusUnits" to "2.4"))
        )
        val telemetry = listOf(
            TelemetrySignal(ts = now - 120_000, key = "isf_value", valueDouble = 90.0),
            TelemetrySignal(ts = now - 120_000, key = "cr_value", valueDouble = 28.0)
        )

        val estimate = ProfileEstimator(
            ProfileEstimatorConfig(
                minIsfSamples = 2,
                minCrSamples = 2,
                trimFraction = 0.0
            )
        ).estimate(glucose, therapy, telemetry)

        assertThat(estimate).isNotNull()
        assertThat(estimate!!.isfMmolPerUnit).isWithin(0.01).of(2.0)
        assertThat(estimate.crGramPerUnit).isWithin(0.01).of(10.0)
        assertThat(estimate.telemetryIsfSampleCount).isEqualTo(0)
        assertThat(estimate.telemetryCrSampleCount).isEqualTo(0)
    }
}
