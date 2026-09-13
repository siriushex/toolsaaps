package io.aaps.copilot.domain.target

import com.google.common.truth.Truth.assertThat
import io.aaps.copilot.domain.model.GlucosePoint
import io.aaps.copilot.domain.model.TherapyEvent
import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertThrows
import org.junit.Test

class CircadianTargetCohortBuilderTest {

    private val builder = CircadianTargetCohortBuilder(zoneId = ZONE)

    @Test
    fun ageFourteenDaysIsExcludedFromLoweringEvidence() {
        val cohorts = build(sensorAgeHours = 14.0 * 24.0)

        assertThat(cohorts.clean).isEmpty()
        assertThat(cohorts.allContext).hasSize(1)
        assertThat(cohorts.reasonCounts).containsEntry("sensor_age_old", 1)
    }

    @Test
    fun ageTwelveToFourteenDaysIsDownWeightedAndCannotAuthorizeLoweringAlone() {
        val cohorts = build(sensorAgeHours = 13.0 * 24.0)

        assertThat(cohorts.clean).hasSize(1)
        assertThat(cohorts.clean.single().cleanWeight).isEqualTo(0.5)
        assertThat(cohorts.clean.any { it.cleanWeight >= 1.0 }).isFalse()
        assertThat(cohorts.reasonCounts).containsEntry("sensor_age_down_weighted", 1)
    }

    @Test
    fun unknownSensorAgeRemainsInSafetyCohortButNotLoweringCohort() {
        val cohorts = build(sensorAgeHours = null)

        assertThat(cohorts.clean).isEmpty()
        assertThat(cohorts.allContext).hasSize(1)
        assertThat(cohorts.validDates).containsExactly(cohorts.allContext.single().localDate)
        assertThat(cohorts.reasonCounts).containsEntry("sensor_age_unknown", 1)
    }

    @Test
    fun activeCobAboveFiveGramsExcludesCleanSample() {
        val cohorts = build(effectiveCobGrams = 5.1)

        assertThat(cohorts.clean).isEmpty()
        assertThat(cohorts.allContext).hasSize(1)
        assertThat(cohorts.reasonCounts).containsEntry("cob_active", 1)
    }

    @Test
    fun activeUnifiedUamExcludesCleanSample() {
        val telemetry = trustedTelemetry(TS).filterNot {
            it.key == "uam_runtime_control_flag"
        } + listOf(
            signal(TS, "uam_runtime_control_flag", 0.0),
            signal(TS, "uam_runtime_flag", 1.0)
        )

        val cohorts = builder.build(
            canonicalGlucose = listOf(glucose(TS, 6.2)),
            telemetry = telemetry,
            therapyEvents = emptyList()
        )

        assertThat(cohorts.clean).isEmpty()
        assertThat(cohorts.allContext.single().uamActive).isTrue()
        assertThat(cohorts.reasonCounts).containsEntry("uam_active", 1)
    }

    @Test
    fun announcedCarbsExcludeTheConfiguredAbsorptionWindow() {
        val meal = TherapyEvent(
            ts = TS - minutes(60),
            type = "carbs",
            payload = mapOf("grams" to "30")
        )

        val cohorts = build(therapyEvents = listOf(meal))

        assertThat(cohorts.clean).isEmpty()
        assertThat(cohorts.allContext.single().acuteCarbs).isTrue()
        assertThat(cohorts.reasonCounts).containsEntry("acute_carbs", 1)
    }

    @Test
    fun trustedLowRemainsInAllContextVetoEvenWhenMealConfounded() {
        val meal = TherapyEvent(
            ts = TS - minutes(30),
            type = "meal_bolus",
            payload = mapOf("enteredCarbs" to "45")
        )

        val cohorts = build(
            glucoseMmol = 3.8,
            effectiveCobGrams = 20.0,
            therapyEvents = listOf(meal)
        )

        assertThat(cohorts.clean).isEmpty()
        assertThat(cohorts.allContext.map { it.glucoseMmol }).containsExactly(3.8)
        assertThat(cohorts.validDates).containsExactly(cohorts.allContext.single().localDate)
    }

    @Test
    fun telemetryOlderThanFifteenMinutesIsNotCarriedForward() {
        val oldTelemetry = trustedTelemetry(TS - minutes(16))

        val cohorts = builder.build(
            canonicalGlucose = listOf(glucose(TS, 6.2)),
            telemetry = oldTelemetry,
            therapyEvents = emptyList()
        )

        assertThat(cohorts.clean).isEmpty()
        assertThat(cohorts.allContext).isEmpty()
        assertThat(cohorts.validDates).isEmpty()
        assertThat(cohorts.reasonCounts).containsEntry("sensor_signal_missing_or_stale", 1)
    }

    @Test
    fun telemetryExactlyFifteenMinutesOldRemainsUsable() {
        val cohorts = builder.build(
            canonicalGlucose = listOf(glucose(TS, 6.2)),
            telemetry = trustedTelemetry(TS - minutes(15)),
            therapyEvents = emptyList()
        )

        assertThat(cohorts.clean).hasSize(1)
        assertThat(cohorts.allContext).hasSize(1)
    }

    @Test
    fun announcedCarbWindowUsesConfiguredBoundary() {
        val boundedBuilder = CircadianTargetCohortBuilder(
            zoneId = ZONE,
            config = CircadianTargetCohortConfig(announcedCarbAbsorptionMinutes = 45L)
        )
        val atBoundary = TherapyEvent(
            ts = TS - minutes(45),
            type = "carbs",
            payload = mapOf("grams" to "20")
        )
        val outsideBoundary = atBoundary.copy(ts = TS - minutes(46))

        val blocked = boundedBuilder.build(
            canonicalGlucose = listOf(glucose(TS, 6.2)),
            telemetry = trustedTelemetry(TS),
            therapyEvents = listOf(atBoundary)
        )
        val clean = boundedBuilder.build(
            canonicalGlucose = listOf(glucose(TS, 6.2)),
            telemetry = trustedTelemetry(TS),
            therapyEvents = listOf(outsideBoundary)
        )

        assertThat(blocked.clean).isEmpty()
        assertThat(clean.clean).hasSize(1)
    }

    @Test
    fun conflictingUamRepresentationsFailClosed() {
        val telemetry = trustedTelemetry(TS)
            .filterNot { it.key == "uam_runtime_control_flag" }
            .plus(
                CircadianTelemetrySignal(
                    timestamp = TS,
                    key = "uam_runtime_control_flag",
                    valueDouble = 1.0,
                    valueText = "false"
                )
            )

        val cohorts = builder.build(
            canonicalGlucose = listOf(glucose(TS, 6.2)),
            telemetry = telemetry,
            therapyEvents = emptyList()
        )

        assertThat(cohorts.clean).isEmpty()
        assertThat(cohorts.reasonCounts).containsEntry("uam_unknown", 1)
    }

    @Test
    fun nonFiniteNumericSignalDoesNotFallBackToText() {
        val telemetry = trustedTelemetry(TS)
            .filterNot { it.key == "sensor_quality_score" }
            .plus(
                CircadianTelemetrySignal(
                    timestamp = TS,
                    key = "sensor_quality_score",
                    valueDouble = Double.NaN,
                    valueText = "0.95"
                )
            )

        val cohorts = builder.build(
            canonicalGlucose = listOf(glucose(TS, 6.2)),
            telemetry = telemetry,
            therapyEvents = emptyList()
        )

        assertThat(cohorts.clean).isEmpty()
        assertThat(cohorts.allContext).isEmpty()
    }

    @Test
    fun overflowingCohortDurationsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            CircadianTargetCohortConfig(announcedCarbAbsorptionMinutes = Long.MAX_VALUE)
        }
    }

    private fun build(
        glucoseMmol: Double = 6.2,
        sensorAgeHours: Double? = 24.0,
        effectiveCobGrams: Double = 0.0,
        therapyEvents: List<TherapyEvent> = emptyList()
    ): CircadianTargetCohorts {
        val telemetry = trustedTelemetry(TS)
            .filterNot { it.key == "sensor_age_hours" || it.key == "cob_effective_grams" }
            .toMutableList()
            .apply {
                sensorAgeHours?.let { add(signal(TS, "sensor_age_hours", it)) }
                add(signal(TS, "cob_effective_grams", effectiveCobGrams))
            }
        return builder.build(
            canonicalGlucose = listOf(glucose(TS, glucoseMmol)),
            telemetry = telemetry.sortedBy { it.timestamp },
            therapyEvents = therapyEvents
        )
    }

    private fun trustedTelemetry(timestamp: Long): List<CircadianTelemetrySignal> = listOf(
        signal(timestamp, "sensor_quality_score", 0.95),
        signal(timestamp, "sensor_quality_blocked", 0.0),
        signal(timestamp, "sensor_quality_suspect_false_low", 0.0),
        signal(timestamp, "sensor_age_hours", 24.0),
        signal(timestamp, "cob_effective_grams", 0.0),
        signal(timestamp, "iob_units", 1.2),
        signal(timestamp, "uam_runtime_control_flag", 0.0),
        signal(timestamp, "target_low_risk_active", 0.0),
        signal(timestamp, "target_low_risk_latched", 0.0),
        signal(timestamp, "cage_days", 4.0)
    )

    private fun signal(timestamp: Long, key: String, value: Double) =
        CircadianTelemetrySignal(timestamp = timestamp, key = key, valueDouble = value)

    private fun glucose(timestamp: Long, valueMmol: Double) = GlucosePoint(
        ts = timestamp,
        valueMmol = valueMmol,
        source = "canonical"
    )

    private fun minutes(value: Long): Long = value * 60_000L

    private companion object {
        val ZONE: ZoneId = ZoneId.of("UTC")
        val TS: Long = Instant.parse("2026-07-20T09:00:00Z").toEpochMilli()
    }
}
