package io.aaps.copilot.domain.predict

import com.google.common.truth.Truth.assertThat
import io.aaps.copilot.data.local.entity.ForecastEntity
import io.aaps.copilot.data.local.entity.GlucoseSampleEntity
import org.junit.Test

class ForecastQualityEvaluatorTest {

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

        val metrics = ForecastQualityEvaluator().evaluate(forecasts, glucose)

        assertThat(metrics).hasSize(2)
        val m5 = metrics.first { it.horizonMinutes == 5 }
        val m60 = metrics.first { it.horizonMinutes == 60 }

        assertThat(m5.sampleCount).isEqualTo(1)
        assertThat(m5.mae).isWithin(0.001).of(0.4)

        assertThat(m60.sampleCount).isEqualTo(1)
        assertThat(m60.mae).isWithin(0.001).of(1.2)
    }
}
