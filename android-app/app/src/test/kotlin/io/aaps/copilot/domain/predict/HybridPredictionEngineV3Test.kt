package io.aaps.copilot.domain.predict

import com.google.common.truth.Truth.assertThat
import io.aaps.copilot.domain.model.DataQuality
import io.aaps.copilot.domain.model.GlucosePoint
import io.aaps.copilot.domain.model.TherapyEvent
import kotlinx.coroutines.runBlocking
import kotlin.math.abs
import org.junit.Test

class HybridPredictionEngineV3Test {

    @Test
    fun t1_v3DisabledMatchesLegacy() = runBlocking {
        val engine = HybridPredictionEngine(enableEnhancedPredictionV3 = false)
        val now = 1_000_000_000L
        val glucose = listOf(6.1, 6.2, 6.25, 6.35, 6.4, 6.5, 6.6, 6.75).series(now)
        val events = listOf(
            TherapyEvent(glucose.last().ts - 25 * 60_000L, "carbs", mapOf("grams" to "18")),
            TherapyEvent(glucose.last().ts - 15 * 60_000L, "correction_bolus", mapOf("units" to "0.9"))
        )

        val actual = engine.predict(glucose, events)
        val legacy = engine.predictLegacyForTest(glucose, events)

        assertThat(actual).isEqualTo(legacy)
    }

    @Test
    fun t2_kalmanAdaptiveRReducesOutlierImpact() = runBlocking {
        val engine = HybridPredictionEngine(enableEnhancedPredictionV3 = true, enableUam = false)
        val start = 2_000_000_000L

        val cycle1 = listOf(6.00, 6.05, 6.10, 6.15, 6.20, 6.25, 6.30, 6.35).series(start)
        engine.predict(cycle1, emptyList())
        val d1 = engine.lastDiagnosticsForTest()!!

        val cycle2Start = cycle1.last().ts + FIVE_MINUTES
        val cycle2 = listOf(6.40, 6.45, 6.50, 12.50, 6.60, 6.65, 6.70, 6.75).series(cycle2Start)
        engine.predict(cycle2, emptyList())
        val d2 = engine.lastDiagnosticsForTest()!!

        val cycle3Start = cycle2.last().ts + FIVE_MINUTES
        val cycle3 = listOf(6.80, 6.85, 6.90, 6.95, 7.00, 7.05, 7.10, 7.15).series(cycle3Start)
        engine.predict(cycle3, emptyList())
        val d3 = engine.lastDiagnosticsForTest()!!

        assertThat(d2.kfEwmaNis).isGreaterThan(d1.kfEwmaNis)
        assertThat(d2.kfSigmaZ).isGreaterThan(d1.kfSigmaZ)
        assertThat(abs(d2.rocPer5Used)).isAtMost(1.2)
        assertThat(d3.kfSigmaZ).isAtMost(d2.kfSigmaZ)
    }

    @Test
    fun t3_ar1CapturesPersistentDrift() = runBlocking {
        val engine = HybridPredictionEngine(enableEnhancedPredictionV3 = true, enableUam = false)
        val baseStart = 3_000_000_000L

        repeat(12) { idx ->
            val start = baseStart + idx * 10 * 60_000L
            val glucose = listOf(6.0, 6.08, 6.16, 6.24, 6.32, 6.40, 6.48, 6.56).series(start)
            engine.predict(glucose, emptyList())
        }

        val d = engine.lastDiagnosticsForTest()!!
        assertThat(d.arMu).isGreaterThan(0.0)
        assertThat(d.arPhi).isAtLeast(0.0)
        assertThat(d.arPhi).isAtMost(0.97)
        assertThat(d.trendCum60Clamped).isAtMost(0.55 * 12 + 0.7)
        assertThat(d.trendCum60Clamped).isAtLeast(-(0.55 * 12 + 0.7))
    }

    @Test
    fun t4_uamRisingNoTherapy() = runBlocking {
        val withUam = HybridPredictionEngine(enableEnhancedPredictionV3 = true, enableUam = true)
        val noUam = HybridPredictionEngine(enableEnhancedPredictionV3 = true, enableUam = false)

        val now = 4_000_000_000L
        val glucose = listOf(6.0, 6.01, 6.03, 6.05, 6.07, 6.10, 6.14, 6.72).series(now)

        val predWith = withUam.predict(glucose, emptyList()).associateBy { it.horizonMinutes }
        val dWith = withUam.lastDiagnosticsForTest()!!
        val predNo = noUam.predict(glucose, emptyList()).associateBy { it.horizonMinutes }

        assertThat(dWith.uci0).isGreaterThan(0.0)
        assertThat(dWith.uamStep[1]).isGreaterThan(0.0)
        assertThat(dWith.residualRoc0).isAtMost(0.0)
        assertThat(predWith.getValue(60).valueMmol).isGreaterThan(predNo.getValue(60).valueMmol)
    }

    @Test
    fun t5_growthExplainedByAnnouncedCarbs() = runBlocking {
        val engine = HybridPredictionEngine(enableEnhancedPredictionV3 = true, enableUam = true)
        val noUam = HybridPredictionEngine(enableEnhancedPredictionV3 = true, enableUam = false)

        val now = 5_000_000_000L
        val glucose = listOf(6.0, 6.08, 6.18, 6.3, 6.45, 6.62, 6.80, 6.95).series(now)
        val events = listOf(
            TherapyEvent(
                ts = glucose.last().ts - 35 * 60_000L,
                type = "carbs",
                payload = mapOf("grams" to "55")
            )
        )

        val pred = engine.predict(glucose, events).associateBy { it.horizonMinutes }
        val d = engine.lastDiagnosticsForTest()!!
        val predNoUam = noUam.predict(glucose, events).associateBy { it.horizonMinutes }

        assertThat(d.uci0).isAtMost(0.10)
        assertThat(abs(pred.getValue(60).valueMmol - predNoUam.getValue(60).valueMmol)).isLessThan(0.6)
    }

    @Test
    fun t6_fallingWithInsulin() = runBlocking {
        val engine = HybridPredictionEngine(enableEnhancedPredictionV3 = true, enableUam = true)

        val now = 6_000_000_000L
        val glucose = listOf(9.4, 9.2, 9.0, 8.75, 8.5, 8.25, 8.0, 7.8).series(now)
        val events = listOf(
            TherapyEvent(
                ts = glucose.last().ts - 20 * 60_000L,
                type = "correction_bolus",
                payload = mapOf("units" to "2.2")
            )
        )

        val pred = engine.predict(glucose, events).associateBy { it.horizonMinutes }
        val d = engine.lastDiagnosticsForTest()!!

        assertThat(d.uci0).isEqualTo(0.0)
        assertThat(pred.getValue(5).valueMmol).isLessThan(glucose.last().valueMmol)
        assertThat(pred.getValue(30).valueMmol).isLessThan(pred.getValue(5).valueMmol)
        assertThat(pred.getValue(60).valueMmol).isLessThan(pred.getValue(30).valueMmol)
    }

    @Test
    fun t7_clamps() = runBlocking {
        val engine = HybridPredictionEngine(enableEnhancedPredictionV3 = true, enableUam = true)

        val now = 7_000_000_000L
        val glucose = listOf(6.0, 6.1, 6.2, 6.25, 6.35, 6.45, 6.55, 11.2).series(now)
        val events = listOf(
            TherapyEvent(glucose.last().ts - 10 * 60_000L, "carbs", mapOf("grams" to "200")),
            TherapyEvent(glucose.last().ts - 5 * 60_000L, "correction_bolus", mapOf("units" to "10"))
        )

        val forecasts = engine.predict(glucose, events)
        val d = engine.lastDiagnosticsForTest()!!

        assertThat(d.uci0).isAtMost(d.uciMax + 1e-9)
        d.therapyCumClamped.forEach { v ->
            assertThat(v).isAtLeast(-6.0)
            assertThat(v).isAtMost(6.0)
        }
        val trendAbsLimit = 0.55 * 12 + 0.7
        assertThat(d.trendCum60Clamped).isAtMost(trendAbsLimit)
        assertThat(d.trendCum60Clamped).isAtLeast(-trendAbsLimit)
        assertThat(d.arPhi).isAtLeast(0.0)
        assertThat(d.arPhi).isAtMost(0.97)
        assertThat(d.arSigmaE).isAtLeast(0.05)
        assertThat(d.arSigmaE).isAtMost(0.60)

        forecasts.forEach { f ->
            assertThat(f.valueMmol).isAtLeast(2.2)
            assertThat(f.valueMmol).isAtMost(22.0)
            assertThat(f.ciLow).isAtLeast(2.2)
            assertThat(f.ciHigh).isAtMost(22.0)
        }
    }

    @Test
    fun t8_announcedCarbsOnFlatProfilePushesForecastUpByHorizon() = runBlocking {
        val engine = HybridPredictionEngine(enableEnhancedPredictionV3 = true, enableUam = true)
        val now = 8_000_000_000L
        val glucose = listOf(7.0, 7.0, 7.0, 7.0, 7.0, 7.0, 7.0, 7.0).series(now)
        val events = listOf(
            TherapyEvent(
                ts = glucose.last().ts - 15 * 60_000L,
                type = "carbs",
                payload = mapOf("carbs" to "40")
            )
        )

        val forecasts = engine.predict(glucose, events).associateBy { it.horizonMinutes }
        val f5 = forecasts.getValue(5).valueMmol
        val f30 = forecasts.getValue(30).valueMmol
        val f60 = forecasts.getValue(60).valueMmol

        assertThat(f5).isAtLeast(7.0)
        assertThat(f30).isGreaterThan(f5)
        assertThat(f60).isAtLeast(f30)
    }

    @Test
    fun t9_announcedInsulinOnFlatProfilePushesForecastDownByHorizon() = runBlocking {
        val engine = HybridPredictionEngine(enableEnhancedPredictionV3 = true, enableUam = true)
        val now = 9_000_000_000L
        val glucose = listOf(9.2, 9.2, 9.2, 9.2, 9.2, 9.2, 9.2, 9.2).series(now)
        val events = listOf(
            TherapyEvent(
                ts = glucose.last().ts - 10 * 60_000L,
                type = "correction_bolus",
                payload = mapOf("units" to "2.5")
            )
        )

        val forecasts = engine.predict(glucose, events).associateBy { it.horizonMinutes }
        val f5 = forecasts.getValue(5).valueMmol
        val f30 = forecasts.getValue(30).valueMmol
        val f60 = forecasts.getValue(60).valueMmol

        assertThat(f5).isAtMost(9.21)
        assertThat(f30).isLessThan(f5)
        assertThat(f60).isAtMost(f30)
    }

    @Test
    fun t10_announcedCarbsAndInsulinProduceNonFlatHorizons() = runBlocking {
        val engine = HybridPredictionEngine(enableEnhancedPredictionV3 = true, enableUam = true)
        val now = 10_000_000_000L
        val glucose = listOf(8.0, 8.0, 8.0, 8.0, 8.0, 8.0, 8.0, 8.0).series(now)
        val events = listOf(
            TherapyEvent(
                ts = glucose.last().ts - 20 * 60_000L,
                type = "carbs",
                payload = mapOf("grams" to "25")
            ),
            TherapyEvent(
                ts = glucose.last().ts - 10 * 60_000L,
                type = "correction_bolus",
                payload = mapOf("units" to "1.0")
            )
        )

        val forecasts = engine.predict(glucose, events).associateBy { it.horizonMinutes }
        val values = listOf(
            forecasts.getValue(5).valueMmol,
            forecasts.getValue(30).valueMmol,
            forecasts.getValue(60).valueMmol
        )
        val uniqueRounded = values.map { String.format("%.2f", it) }.toSet()

        assertThat(uniqueRounded.size).isGreaterThan(1)
    }

    @Test
    fun t13_fastCarbsPushEarlierThanProteinSlow() = runBlocking {
        val fastEngine = HybridPredictionEngine(enableEnhancedPredictionV3 = true, enableUam = false)
        val slowEngine = HybridPredictionEngine(enableEnhancedPredictionV3 = true, enableUam = false)
        val now = 12_000_000_000L
        val glucose = listOf(6.8, 6.8, 6.8, 6.8, 6.8, 6.8, 6.8, 6.8).series(now)

        val fastEvent = TherapyEvent(
            ts = glucose.last().ts - 10 * 60_000L,
            type = "carbs",
            payload = mapOf("grams" to "30", "food" to "honey and banana")
        )
        val slowEvent = TherapyEvent(
            ts = glucose.last().ts - 10 * 60_000L,
            type = "carbs",
            payload = mapOf("grams" to "30", "food" to "chicken breast")
        )

        val fast = fastEngine.predict(glucose, listOf(fastEvent)).associateBy { it.horizonMinutes }
        val slow = slowEngine.predict(glucose, listOf(slowEvent)).associateBy { it.horizonMinutes }

        assertThat(fast.getValue(30).valueMmol).isGreaterThan(slow.getValue(30).valueMmol)
        assertThat(slow.getValue(60).valueMmol).isAtLeast(slow.getValue(5).valueMmol)
    }

    @Test
    fun t11_defaultInsulinProfileIsNovorapid() = runBlocking {
        val engine = HybridPredictionEngine(enableEnhancedPredictionV3 = false)
        assertThat(engine.currentInsulinProfileForTest()).isEqualTo(InsulinActionProfileId.NOVORAPID)
    }

    @Test
    fun t12_lyumjevActsFasterThanNovorapidOnEarlyHorizons() = runBlocking {
        val now = 11_000_000_000L
        val glucose = listOf(9.2, 9.2, 9.2, 9.2, 9.2, 9.2, 9.2, 9.2).series(now)
        val events = listOf(
            TherapyEvent(
                ts = glucose.last().ts - 5 * 60_000L,
                type = "correction_bolus",
                payload = mapOf("units" to "2.4")
            )
        )

        val novorapid = HybridPredictionEngine(enableEnhancedPredictionV3 = false)
        novorapid.setInsulinProfile(InsulinActionProfileId.NOVORAPID)
        val novPred = novorapid.predict(glucose, events).associateBy { it.horizonMinutes }

        val lyumjev = HybridPredictionEngine(enableEnhancedPredictionV3 = false)
        lyumjev.setInsulinProfile(InsulinActionProfileId.LYUMJEV)
        val lyuPred = lyumjev.predict(glucose, events).associateBy { it.horizonMinutes }

        assertThat(lyuPred.getValue(5).valueMmol).isLessThan(novPred.getValue(5).valueMmol)
        assertThat(lyuPred.getValue(30).valueMmol).isLessThan(novPred.getValue(30).valueMmol)
        assertThat(lyuPred.getValue(60).valueMmol).isLessThan(novPred.getValue(60).valueMmol)
    }

    private fun List<Double>.series(startTs: Long): List<GlucosePoint> {
        return mapIndexed { idx, value ->
            GlucosePoint(
                ts = startTs + idx * FIVE_MINUTES,
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
