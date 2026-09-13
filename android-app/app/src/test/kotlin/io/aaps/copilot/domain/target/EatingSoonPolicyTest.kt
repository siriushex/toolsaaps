package io.aaps.copilot.domain.target

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class EatingSoonPolicyTest {

    @Test
    fun approvedRequestConstantsAreFixed() {
        assertThat(EatingSoonPolicy.TARGET_MMOL).isEqualTo(4.1)
        assertThat(EatingSoonPolicy.DURATION_MINUTES).isEqualTo(30)
        assertThat(EatingSoonPolicy.MAX_DATA_AGE_MS).isEqualTo(300_000L)
    }

    @Test
    fun completeSafeEvidenceIsEligibleAndExtraHorizonsAreIgnored() {
        val evidence = validEvidence().copy(
            forecasts = validEvidence().forecasts + (
                10 to EatingSoonForecast(Double.NaN, Double.NaN, Double.NaN)
                )
        )

        assertThat(EatingSoonPolicy.blockReason(evidence)).isNull()
    }

    @Test
    fun independentSafetyGatesReturnStableReasons() {
        val cases: List<Pair<String, (EatingSoonEvidence) -> EatingSoonEvidence>> = listOf(
            "kill_switch_active" to { it.copy(killSwitch = true) },
            "actions_disarmed" to { it.copy(actionsArmed = false) },
            "chronology_unresolved" to { it.copy(chronologyResolved = false) },
            "sensor_untrusted" to { it.copy(sensorTrusted = false) }
        )

        cases.forEach { (reason, mutate) ->
            assertThat(EatingSoonPolicy.blockReason(mutate(validEvidence()))).isEqualTo(reason)
        }
    }

    @Test
    fun targetBoundsMustBeFiniteOrderedAndContainFourPointOne() {
        val invalidBounds = listOf(
            validEvidence().copy(minTargetMmol = Double.NaN),
            validEvidence().copy(maxTargetMmol = Double.POSITIVE_INFINITY),
            validEvidence().copy(minTargetMmol = 5.0, maxTargetMmol = 4.0)
        )
        invalidBounds.forEach {
            assertThat(EatingSoonPolicy.blockReason(it)).isEqualTo("target_bounds_invalid")
        }
        assertThat(
            EatingSoonPolicy.blockReason(validEvidence().copy(minTargetMmol = 4.2))
        ).isEqualTo("target_out_of_bounds")
        assertThat(
            EatingSoonPolicy.blockReason(validEvidence().copy(maxTargetMmol = 4.0))
        ).isEqualTo("target_out_of_bounds")
        assertThat(
            EatingSoonPolicy.blockReason(
                validEvidence().copy(minTargetMmol = 4.1, maxTargetMmol = 4.1)
            )
        ).isNull()
    }

    @Test
    fun glucoseMustBePresentFiniteAndStrictlyAboveFour() {
        val cases = listOf(
            validEvidence().copy(glucoseMmol = null) to "glucose_missing",
            validEvidence().copy(glucoseMmol = Double.NaN) to "glucose_invalid",
            validEvidence().copy(glucoseMmol = Double.NEGATIVE_INFINITY) to "glucose_invalid",
            validEvidence().copy(glucoseMmol = 4.0) to "glucose_too_low"
        )

        cases.forEach { (evidence, reason) ->
            assertThat(EatingSoonPolicy.blockReason(evidence)).isEqualTo(reason)
        }
    }

    @Test
    fun glucoseTimestampMustBePositiveNonFutureAndNoOlderThanFiveMinutes() {
        val cases = listOf(
            validEvidence().copy(glucoseTs = null) to "glucose_timestamp_missing",
            validEvidence().copy(glucoseTs = 0L) to "glucose_timestamp_invalid",
            validEvidence().copy(glucoseTs = NOW + 1L) to "glucose_timestamp_future",
            validEvidence().copy(
                glucoseTs = NOW - EatingSoonPolicy.MAX_DATA_AGE_MS - 1L
            ) to "glucose_stale"
        )

        cases.forEach { (evidence, reason) ->
            assertThat(EatingSoonPolicy.blockReason(evidence)).isEqualTo(reason)
        }
    }

    @Test
    fun forecastTimestampMustBePositiveNonFutureAndNoOlderThanFiveMinutes() {
        val cases = listOf(
            validEvidence().copy(forecastGeneratedAt = null) to "forecast_timestamp_missing",
            validEvidence().copy(forecastGeneratedAt = 0L) to "forecast_timestamp_invalid",
            validEvidence().copy(forecastGeneratedAt = NOW + 1L) to "forecast_timestamp_future",
            validEvidence().copy(
                forecastGeneratedAt = NOW - EatingSoonPolicy.MAX_DATA_AGE_MS - 1L
            ) to "forecast_stale"
        )

        cases.forEach { (evidence, reason) ->
            assertThat(EatingSoonPolicy.blockReason(evidence)).isEqualTo(reason)
        }
    }

    @Test
    fun everyRequiredForecastHorizonMustBePresent() {
        REQUIRED_HORIZONS.forEach { horizon ->
            val evidence = validEvidence()
            val missing = evidence.copy(forecasts = evidence.forecasts - horizon)

            assertThat(EatingSoonPolicy.blockReason(missing))
                .isEqualTo("forecast_" + horizon + "m_missing")
        }
    }

    @Test
    fun requiredForecastsMustBeFiniteAndInternallyConsistent() {
        val invalidForecasts = listOf(
            forecast().copy(valueMmol = Double.NaN),
            forecast().copy(ciLow = Double.NEGATIVE_INFINITY),
            forecast().copy(ciHigh = Double.POSITIVE_INFINITY),
            forecast().copy(ciLow = 5.1, valueMmol = 5.0),
            forecast().copy(valueMmol = 5.1, ciHigh = 5.0)
        )

        REQUIRED_HORIZONS.forEach { horizon ->
            invalidForecasts.forEach { invalid ->
                assertThat(EatingSoonPolicy.blockReason(withForecast(horizon, invalid)))
                    .isEqualTo("forecast_" + horizon + "m_invalid")
            }
        }
    }

    @Test
    fun requiredForecastValuesAndLowerBoundsMustBeStrictlyAboveFour() {
        REQUIRED_HORIZONS.forEach { horizon ->
            assertThat(
                EatingSoonPolicy.blockReason(
                    withForecast(horizon, EatingSoonForecast(4.0, 3.9, 4.2))
                )
            ).isEqualTo("forecast_" + horizon + "m_too_low")
            assertThat(
                EatingSoonPolicy.blockReason(
                    withForecast(horizon, EatingSoonForecast(4.1, 4.0, 4.2))
                )
            ).isEqualTo("forecast_" + horizon + "m_ci_low")
        }
    }

    @Test
    fun inclusiveAgeAndTargetBoundsAllowValuesJustAboveLowFloor() {
        val aboveFour = Math.nextUp(4.0)
        val safeForecast = EatingSoonForecast(aboveFour, aboveFour, aboveFour)
        val evidence = validEvidence().copy(
            minTargetMmol = 4.1,
            maxTargetMmol = 4.1,
            glucoseTs = NOW - EatingSoonPolicy.MAX_DATA_AGE_MS,
            glucoseMmol = aboveFour,
            forecastGeneratedAt = NOW - EatingSoonPolicy.MAX_DATA_AGE_MS,
            forecasts = REQUIRED_HORIZONS.associateWith { safeForecast }
        )

        assertThat(EatingSoonPolicy.blockReason(evidence)).isNull()
    }

    private fun withForecast(
        horizon: Int,
        replacement: EatingSoonForecast
    ): EatingSoonEvidence {
        val evidence = validEvidence()
        return evidence.copy(forecasts = evidence.forecasts + (horizon to replacement))
    }

    private fun validEvidence() = EatingSoonEvidence(
        nowTs = NOW,
        killSwitch = false,
        actionsArmed = true,
        minTargetMmol = 4.0,
        maxTargetMmol = 8.0,
        glucoseTs = NOW,
        glucoseMmol = 6.0,
        forecastGeneratedAt = NOW,
        forecasts = REQUIRED_HORIZONS.associateWith { forecast() },
        sensorTrusted = true,
        chronologyResolved = true
    )

    private fun forecast() = EatingSoonForecast(
        valueMmol = 5.0,
        ciLow = 4.5,
        ciHigh = 5.5
    )

    companion object {
        private const val NOW = 1_800_000_000_000L
        private val REQUIRED_HORIZONS = listOf(5, 30, 60)
    }
}
