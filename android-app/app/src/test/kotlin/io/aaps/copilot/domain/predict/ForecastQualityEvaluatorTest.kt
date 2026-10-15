package io.aaps.copilot.domain.predict

import com.google.common.truth.Truth.assertThat
import io.aaps.copilot.data.local.entity.ForecastEntity
import io.aaps.copilot.data.local.entity.GlucoseSampleEntity
import org.junit.Test

class ForecastQualityEvaluatorTest {

    @Test
    fun futureForecastDoesNotUseLatestGlucoseAsItsOutcome() {
        val now = 1_800_000_000_000L
        val glucose = listOf(GlucoseSampleEntity(timestamp = now, mmol = 6.0, source = "test", quality = "OK"))
        for (horizon in listOf(5, 30, 60)) {
            val forecast = ForecastEntity(timestamp = now + 60_000L, horizonMinutes = horizon,
                valueMmol = 6.0, ciLow = 5.0, ciHigh = 7.0, modelVersion = "test")
            assertThat(ForecastQualityEvaluator().evaluate(listOf(forecast), glucose, now)).isEmpty()
        }
    }

    @Test
    fun forecastBeforeObservedWindowDoesNotUseFirstGlucoseAsItsOutcome() {
        val now = 1_800_000_000_000L
        val forecast = ForecastEntity(timestamp = now - 60_000L, horizonMinutes = 30,
            valueMmol = 6.0, ciLow = 5.0, ciHigh = 7.0, modelVersion = "test")
        val glucose = listOf(GlucoseSampleEntity(timestamp = now, mmol = 6.0, source = "test", quality = "OK"))
        assertThat(ForecastQualityEvaluator().evaluate(listOf(forecast), glucose, now)).isEmpty()
    }

    @Test
    fun evaluatesMetricsByHorizon() {
        val t0 = 1_000_000L
        val forecasts = listOf(
            ForecastEntity(timestamp = t0 + 5 * 60_000, horizonMinutes = 5, valueMmol = 6.0, ciLow = 5.5, ciHigh = 6.5, modelVersion = "m"),
            ForecastEntity(timestamp = t0 + 60 * 60_000, horizonMinutes = 60, valueMmol = 7.0, ciLow = 6.0, ciHigh = 8.0, modelVersion = "m")
        )
        val glucose = listOf(
            GlucoseSampleEntity(timestamp = t0 + 5 * 60_000, mmol = 6.4, source = "test", quality = "OK"),
            GlucoseSampleEntity(timestamp = t0 + 60 * 60_000, mmol = 8.2, source = "test", quality = "OK")
        )

        val metrics = ForecastQualityEvaluator().evaluate(forecasts, glucose, t0 + 60 * 60_000L)

        assertThat(metrics).hasSize(2)
        val m5 = metrics.first { it.horizonMinutes == 5 }
        val m60 = metrics.first { it.horizonMinutes == 60 }

        assertThat(m5.sampleCount).isEqualTo(1)
        assertThat(m5.mae).isWithin(0.001).of(0.4)

        assertThat(m60.sampleCount).isEqualTo(1)
        assertThat(m60.mae).isWithin(0.001).of(1.2)
    }

    @Test
    fun interpolatesActualInsteadOfPickingClosestSample() {
        val t0 = 2_000_000L
        val forecasts = listOf(
            ForecastEntity(
                timestamp = t0 + 10 * 60_000,
                horizonMinutes = 5,
                valueMmol = 7.0,
                ciLow = 6.5,
                ciHigh = 7.5,
                modelVersion = "m"
            )
        )
        val glucose = listOf(
            GlucoseSampleEntity(timestamp = t0 + 5 * 60_000, mmol = 6.0, source = "test", quality = "OK"),
            GlucoseSampleEntity(timestamp = t0 + 15 * 60_000, mmol = 8.0, source = "test", quality = "OK")
        )

        val metrics = ForecastQualityEvaluator().evaluate(forecasts, glucose, t0 + 15 * 60_000L)

        assertThat(metrics).hasSize(1)
        assertThat(metrics.single().mae).isWithin(0.001).of(0.0)
    }

    @Test
    fun futureCgmTimestampDoesNotMakeAnOutcomeMature() {
        val now = 1_800_000_000_000L
        val forecast = ForecastEntity(timestamp = now + 60_000L, horizonMinutes = 5,
            valueMmol = 6.0, ciLow = 5.0, ciHigh = 7.0, modelVersion = "test")
        val glucose = listOf(
            GlucoseSampleEntity(timestamp = now, mmol = 5.0, source = "test", quality = "OK"),
            GlucoseSampleEntity(timestamp = now + 60_000L, mmol = 6.0, source = "test", quality = "OK")
        )
        assertThat(ForecastQualityEvaluator().evaluate(listOf(forecast), glucose, now)).isEmpty()
        assertThat(ForecastQualityEvaluator().evaluate(listOf(forecast), glucose, now + 60_000L)
            .single().mae).isEqualTo(0.0)
    }

    @Test
    fun interpolationCannotUseObservationBeyondEvaluationClock() {
        val now = 1_800_000_000_000L
        val forecast = ForecastEntity(timestamp = now - 60_000L, horizonMinutes = 5,
            valueMmol = 6.0, ciLow = 5.0, ciHigh = 7.0, modelVersion = "test")
        val glucose = listOf(
            GlucoseSampleEntity(timestamp = now - 2 * 60_000L, mmol = 5.0, source = "test", quality = "OK"),
            GlucoseSampleEntity(timestamp = now + 60_000L, mmol = 8.0, source = "test", quality = "OK")
        )
        assertThat(ForecastQualityEvaluator().evaluate(listOf(forecast), glucose, now)).isEmpty()
    }

    @Test
    fun observedExactEndpointsAndUnsortedHistoryStayEligible() {
        val now = 1_800_000_000_000L
        val glucose = listOf(
            GlucoseSampleEntity(timestamp = now, mmol = 8.0, source = "test", quality = "OK"),
            GlucoseSampleEntity(timestamp = now - 10 * 60_000L, mmol = 6.0, source = "test", quality = "OK")
        )
        val forecasts = glucose.map { sample -> ForecastEntity(timestamp = sample.timestamp, horizonMinutes = 5,
            valueMmol = sample.mmol + 0.5, ciLow = 5.0, ciHigh = 9.0, modelVersion = "test") }
        val metrics = ForecastQualityEvaluator().evaluate(forecasts, glucose, now).single()
        assertThat(metrics.sampleCount).isEqualTo(2)
        assertThat(metrics.mae).isEqualTo(0.5)
    }

    @Test
    fun distantInteriorGapDoesNotInventAnActual() {
        val now = 1_800_000_000_000L
        val glucose = listOf(
            GlucoseSampleEntity(timestamp = now - 40 * 60_000L, mmol = 6.0, source = "test", quality = "OK"),
            GlucoseSampleEntity(timestamp = now, mmol = 8.0, source = "test", quality = "OK")
        )
        val forecast = ForecastEntity(timestamp = now - 20 * 60_000L, horizonMinutes = 30,
            valueMmol = 7.0, ciLow = 5.0, ciHigh = 9.0, modelVersion = "test")
        assertThat(ForecastQualityEvaluator().evaluate(listOf(forecast), glucose, now)).isEmpty()
    }
}
