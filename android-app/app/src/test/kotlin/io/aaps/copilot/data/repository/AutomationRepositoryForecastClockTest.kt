package io.aaps.copilot.data.repository

import io.aaps.copilot.domain.model.Forecast
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class AutomationRepositoryForecastClockTest {
    private val origin = 1_800_000_000_000L

    private fun forecast(horizon: Int, at: Long = origin) = Forecast(
        ts = at + horizon * 60_000L,
        horizonMinutes = horizon,
        valueMmol = 6.0 + horizon / 60.0,
        ciLow = 5.0,
        ciHigh = 8.0,
        modelVersion = "local-test"
    )

    @Test fun reconstructedThirtyMinuteForecastUsesCommonPredictionOrigin() {
        val result = AutomationRepository.ensureForecast30Static(listOf(forecast(5), forecast(60)))
        assertEquals(listOf(5, 30, 60), result.map { it.horizonMinutes })
        assertEquals(origin + 30 * 60_000L, result.single { it.horizonMinutes == 30 }.ts)
        assertEquals(setOf(origin), result.map { it.ts - it.horizonMinutes * 60_000L }.toSet())
    }

    @Test fun inconsistentPredictionOriginsCannotProduceSyntheticHorizon() {
        val result = AutomationRepository.ensureForecast30Static(listOf(forecast(5), forecast(60, origin + 60_000L)))
        assertFalse(result.any { it.horizonMinutes == 30 })
    }

    @Test fun completeForecastIsNotReplaced() {
        val existing = listOf(forecast(5), forecast(30), forecast(60))
        assertEquals(existing, AutomationRepository.ensureForecast30Static(existing))
    }

    @Test fun missingAnchorDoesNotInventHorizon() {
        val existing = listOf(forecast(5))
        assertEquals(existing, AutomationRepository.ensureForecast30Static(existing))
    }

    @Test fun additionalLongHorizonCannotMoveReconstructedTimestamp() {
        val result = AutomationRepository.ensureForecast30Static(listOf(forecast(5), forecast(60), forecast(120)))
        assertEquals(origin + 30 * 60_000L, result.single { it.horizonMinutes == 30 }.ts)
    }
}
