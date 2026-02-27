package io.aaps.copilot.domain.predict

import com.google.common.truth.Truth.assertThat
import io.aaps.copilot.domain.model.DataQuality
import io.aaps.copilot.domain.model.GlucosePoint
import io.aaps.copilot.domain.model.TherapyEvent
import kotlinx.coroutines.runBlocking
import org.junit.Test

class HybridPredictionEngineTest {

    private val engine = HybridPredictionEngine()

    @Test
    fun predictsRisingTrajectoryWhenTrendIsUp() = runBlocking {
        val now = 1_000_000_000L
        val glucose = listOf(6.0, 6.1, 6.2, 6.35, 6.5, 6.7).mapIndexed { idx, value ->
            GlucosePoint(
                ts = now + idx * FIVE_MINUTES,
                valueMmol = value,
                source = "test",
                quality = DataQuality.OK
            )
        }

        val forecasts = engine.predict(glucose, emptyList()).associateBy { it.horizonMinutes }
        val latest = glucose.last().valueMmol

        assertThat(forecasts.keys).containsExactly(5, 30, 60)
        assertThat(forecasts.getValue(5).valueMmol).isGreaterThan(latest)
        assertThat(forecasts.getValue(60).valueMmol).isGreaterThan(forecasts.getValue(5).valueMmol)
    }

    @Test
    fun carbsIncreaseFutureForecast() = runBlocking {
        val now = 2_000_000_000L
        val glucose = flatSeries(now = now, value = 6.2)
        val noTherapy = engine.predict(glucose, emptyList()).associateBy { it.horizonMinutes }
        val withCarbs = engine.predict(
            glucose = glucose,
            therapyEvents = listOf(
                TherapyEvent(
                    ts = glucose.last().ts - FIVE_MINUTES,
                    type = "carbs",
                    payload = mapOf("grams" to "30")
                )
            )
        ).associateBy { it.horizonMinutes }

        assertThat(withCarbs.getValue(60).valueMmol).isGreaterThan(noTherapy.getValue(60).valueMmol)
    }

    @Test
    fun correctionBolusLowersFutureForecast() = runBlocking {
        val now = 3_000_000_000L
        val glucose = flatSeries(now = now, value = 8.3)
        val noTherapy = engine.predict(glucose, emptyList()).associateBy { it.horizonMinutes }
        val withInsulin = engine.predict(
            glucose = glucose,
            therapyEvents = listOf(
                TherapyEvent(
                    ts = glucose.last().ts - 10 * 60_000L,
                    type = "correction_bolus",
                    payload = mapOf("units" to "2.0")
                )
            )
        ).associateBy { it.horizonMinutes }

        assertThat(withInsulin.getValue(60).valueMmol).isLessThan(noTherapy.getValue(60).valueMmol)
    }

    @Test
    fun outputIsClampedAndHasExpectedHorizons() = runBlocking {
        val now = 4_000_000_000L
        val glucose = listOf(14.0, 15.5, 17.0, 18.5, 20.0, 21.5).mapIndexed { idx, value ->
            GlucosePoint(
                ts = now + idx * FIVE_MINUTES,
                valueMmol = value,
                source = "test",
                quality = DataQuality.OK
            )
        }

        val forecasts = engine.predict(glucose, emptyList())

        assertThat(forecasts.map { it.horizonMinutes }).containsExactly(5, 30, 60).inOrder()
        forecasts.forEach { forecast ->
            assertThat(forecast.valueMmol).isAtLeast(2.2)
            assertThat(forecast.valueMmol).isAtMost(22.0)
            assertThat(forecast.ciLow).isAtLeast(2.2)
            assertThat(forecast.ciHigh).isAtMost(22.0)
            assertThat(forecast.ciLow).isAtMost(forecast.valueMmol)
            assertThat(forecast.ciHigh).isAtLeast(forecast.valueMmol)
            assertThat(forecast.modelVersion).isEqualTo("local-hybrid-v2")
        }
    }

    private fun flatSeries(now: Long, value: Double): List<GlucosePoint> {
        return List(12) { idx ->
            GlucosePoint(
                ts = now + idx * FIVE_MINUTES,
                valueMmol = value,
                source = "test",
                quality = DataQuality.OK
            )
        }
    }

    private companion object {
        const val FIVE_MINUTES = 5 * 60_000L
    }
}
