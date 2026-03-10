package io.aaps.copilot.domain.predict

import com.google.common.truth.Truth.assertThat
import io.aaps.copilot.domain.model.DataQuality
import io.aaps.copilot.domain.model.GlucosePoint
import io.aaps.copilot.domain.model.TherapyEvent
import kotlinx.coroutines.runBlocking
import org.junit.Test

class HybridPredictionEngineTest {

    @Test
    fun legacyPredictsRisingTrajectoryWhenTrendIsUp() = runBlocking {
        val engine = HybridPredictionEngine(enableEnhancedPredictionV3 = false)
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
    fun legacyOutputIsClampedAndHasExpectedHorizons() = runBlocking {
        val engine = HybridPredictionEngine(enableEnhancedPredictionV3 = false)
        val now = 2_000_000_000L
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
            assertThat(forecast.modelVersion).isEqualTo("local-hybrid-v2")
        }
    }

    @Test
    fun legacyIgnoresCarbsOlderThanThreeHours() = runBlocking {
        val engine = HybridPredictionEngine(enableEnhancedPredictionV3 = false)
        val now = 3_000_000_000L
        val glucose = List(8) { 7.1 }.mapIndexed { idx, value ->
            GlucosePoint(
                ts = now + idx * FIVE_MINUTES,
                valueMmol = value,
                source = "test",
                quality = DataQuality.OK
            )
        }
        val oldCarbs = listOf(
            TherapyEvent(
                ts = glucose.last().ts - 4 * 60 * 60_000L,
                type = "carbs",
                payload = mapOf("grams" to "40")
            )
        )

        val noEvents = engine.predict(glucose, emptyList()).associateBy { it.horizonMinutes }
        val withOldCarbs = engine.predict(glucose, oldCarbs).associateBy { it.horizonMinutes }

        assertThat(withOldCarbs.getValue(5).valueMmol).isEqualTo(noEvents.getValue(5).valueMmol)
        assertThat(withOldCarbs.getValue(30).valueMmol).isEqualTo(noEvents.getValue(30).valueMmol)
        assertThat(withOldCarbs.getValue(60).valueMmol).isEqualTo(noEvents.getValue(60).valueMmol)
    }

    @Test
    fun legacyCapsCarbComputationToSixtyGrams() = runBlocking {
        val engine = HybridPredictionEngine(enableEnhancedPredictionV3 = false)
        val now = 4_000_000_000L
        val glucose = List(8) { 7.4 }.mapIndexed { idx, value ->
            GlucosePoint(
                ts = now + idx * FIVE_MINUTES,
                valueMmol = value,
                source = "test",
                quality = DataQuality.OK
            )
        }
        val carbs60 = listOf(
            TherapyEvent(
                ts = glucose.last().ts - 20 * 60_000L,
                type = "carbs",
                payload = mapOf("grams" to "60")
            )
        )
        val carbs120 = listOf(
            TherapyEvent(
                ts = glucose.last().ts - 20 * 60_000L,
                type = "carbs",
                payload = mapOf("grams" to "120")
            )
        )

        val pred60 = engine.predict(glucose, carbs60).associateBy { it.horizonMinutes }
        val pred120 = engine.predict(glucose, carbs120).associateBy { it.horizonMinutes }

        assertThat(pred120.getValue(5).valueMmol).isEqualTo(pred60.getValue(5).valueMmol)
        assertThat(pred120.getValue(30).valueMmol).isEqualTo(pred60.getValue(30).valueMmol)
        assertThat(pred120.getValue(60).valueMmol).isEqualTo(pred60.getValue(60).valueMmol)
    }

    @Test
    fun legacyIgnoresSyntheticUamCarbsWithoutTagNote() = runBlocking {
        val engine = HybridPredictionEngine(enableEnhancedPredictionV3 = false)
        val now = 5_000_000_000L
        val glucose = List(8) { 7.2 }.mapIndexed { idx, value ->
            GlucosePoint(
                ts = now + idx * FIVE_MINUTES,
                valueMmol = value,
                source = "test",
                quality = DataQuality.OK
            )
        }
        val syntheticCarbs = listOf(
            TherapyEvent(
                ts = glucose.last().ts - 20 * 60_000L,
                type = "carbs",
                payload = mapOf(
                    "grams" to "30",
                    "source" to "uam_engine",
                    "reason" to "uam_engine"
                )
            )
        )

        val noEvents = engine.predict(glucose, emptyList()).associateBy { it.horizonMinutes }
        val withSynthetic = engine.predict(glucose, syntheticCarbs).associateBy { it.horizonMinutes }

        assertThat(withSynthetic.getValue(5).valueMmol).isEqualTo(noEvents.getValue(5).valueMmol)
        assertThat(withSynthetic.getValue(30).valueMmol).isEqualTo(noEvents.getValue(30).valueMmol)
        assertThat(withSynthetic.getValue(60).valueMmol).isEqualTo(noEvents.getValue(60).valueMmol)
    }

    private companion object {
        const val FIVE_MINUTES = 5 * 60_000L
    }
}
