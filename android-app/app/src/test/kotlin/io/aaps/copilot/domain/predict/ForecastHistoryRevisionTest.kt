package io.aaps.copilot.domain.predict

import io.aaps.copilot.domain.model.DataQuality
import io.aaps.copilot.domain.model.Forecast
import io.aaps.copilot.domain.model.GlucosePoint
import io.aaps.copilot.domain.model.TherapyEvent
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ForecastHistoryRevisionTest {
    private val end = 1_788_609_000_000L

    private fun series(level: Double = 8.0) = (0 until 72).map { index ->
        GlucosePoint(end - (71 - index) * 300_000L, level, "test", DataQuality.OK)
    }

    private fun engine(uam: Boolean = false) =
        HybridPredictionEngine(enableEnhancedPredictionV3 = true, enableUam = uam).also {
            sensitivity(it, 2.0, 10.0)
        }

    private fun sensitivity(engine: HybridPredictionEngine, isf: Double, cr: Double) {
        engine.setSensitivityOverrides(
            isf = SensitivityMetricOverride(isf, 1.0, 0.0, 1.0, "AAPS", true),
            cr = SensitivityMetricOverride(cr, 1.0, 0.0, 1.0, "AAPS", true)
        )
    }

    private fun assertParity(expected: List<Forecast>, actual: List<Forecast>) {
        assertEquals(expected.map { it.horizonMinutes }, actual.map { it.horizonMinutes })
        expected.zip(actual).forEach { (a, b) ->
            assertEquals("glucose at ${a.horizonMinutes}", a.valueMmol, b.valueMmol, 1e-9)
            assertEquals("CI low at ${a.horizonMinutes}", a.ciLow, b.ciLow, 1e-9)
            assertEquals("CI high at ${a.horizonMinutes}", a.ciHigh, b.ciHigh, 1e-9)
        }
    }

    @Test fun calibrationRevisionRebuildsBothDirectionsWithAndWithoutUam() = runBlocking {
        for (uam in listOf(false, true)) {
            for ((before, after) in listOf(6.0 to 8.0, 8.0 to 6.0)) {
                val live = engine(uam)
                live.predict(series(before), emptyList())
                assertParity(engine(uam).predict(series(after), emptyList()),
                    live.predict(series(after), emptyList()))
                assertEquals(after, live.lastDiagnosticsForTest()!!.gNowUsed, 1e-9)
            }
        }
    }

    @Test fun lateBolusAndItsCorrectionMatchRebuiltState() = runBlocking {
        val live = engine()
        live.predict(series(), emptyList())
        for (units in listOf("3.0", "1.0", "0.0")) {
            val events = listOf(TherapyEvent(end - 30 * 60_000L, "bolus", mapOf("units" to units)))
            assertParity(engine().predict(series(), events), live.predict(series(), events))
        }
    }

    @Test fun removingHistoricalBolusDoesNotRetainOldState() = runBlocking {
        val live = engine()
        live.predict(series(), listOf(TherapyEvent(end - 30 * 60_000L, "bolus", mapOf("units" to "3"))))
        assertParity(engine().predict(series(), emptyList()), live.predict(series(), emptyList()))
    }

    @Test fun revisedSensitivityRebuildsHistoricalKnownInputs() = runBlocking {
        val events = listOf(
            TherapyEvent(end - 30 * 60_000L, "bolus", mapOf("units" to "3")),
            TherapyEvent(end - 20 * 60_000L, "carbs", mapOf("grams" to "30"))
        )
        val live = engine()
        live.predict(series(), events)
        sensitivity(live, 3.0, 6.0)
        val fresh = engine().also { sensitivity(it, 3.0, 6.0) }
        assertParity(fresh.predict(series(), events), live.predict(series(), events))
    }

    @Test fun calibrationRevisionDiscardsPreviouslyTrainedResiduals() = runBlocking {
        val live = engine()
        val rising = series().mapIndexed { index, point -> point.copy(valueMmol = 5.0 + index * .05) }
        for (size in 60..72) live.predict(rising.take(size), emptyList())
        assertParity(engine().predict(series(7.0), emptyList()), live.predict(series(7.0), emptyList()))
    }

    @Test fun unchangedInputsRemainRepeatableAfterRevision() = runBlocking {
        val live = engine(true)
        live.predict(series(6.0), emptyList())
        val first = live.predict(series(8.0), emptyList())
        repeat(5) { assertParity(first, live.predict(series(8.0), emptyList())) }
    }

    @Test fun minuteCadenceDoesNotEraseLearnedTrendWhenCanonicalGridMoves() = runBlocking {
        val live = engine()
        val points = (0 until 360).map { index ->
            GlucosePoint(end + index * 60_000L, 6.0 + index * .008, "test", DataQuality.OK)
        }
        for (size in 300..360) {
            live.predict(points.take(size), emptyList())
            val diagnostics = live.lastDiagnosticsForTest()!!
            assertFalse("unmodified source history at size=$size", diagnostics.kfHistoryRevised)
            if (size > 300) assertTrue(diagnostics.kfHistoryRebuilt)
        }
        assertTrue(live.lastDiagnosticsForTest()!!.arMu > 0.0)
    }

    private fun minuteSeries() = (0 until 360).map { index ->
        GlucosePoint(end + index * 60_000L, 8.0, "test", DataQuality.OK)
    }

    @Test fun gridShiftDoesNotHideCalibrationRevision() = runBlocking {
        val live = engine(true)
        val points = minuteSeries()
        live.predict(points.take(300), emptyList())
        val corrected = points.take(301).map { it.copy(valueMmol = 6.0) }
        assertParity(engine(true).predict(corrected, emptyList()), live.predict(corrected, emptyList()))
        assertTrue(live.lastDiagnosticsForTest()!!.kfHistoryRebuilt)
    }

    @Test fun gridShiftDoesNotHideLateTherapyOrSensitivityRevision() = runBlocking {
        val points = minuteSeries()
        val events = listOf(TherapyEvent(points[270].ts, "bolus", mapOf("units" to "3")))
        for (changeSensitivity in listOf(false, true)) {
            val live = engine()
            live.predict(points.take(300), if (changeSensitivity) events else emptyList())
            val fresh = engine()
            if (changeSensitivity) {
                sensitivity(live, 3.0, 10.0)
                sensitivity(fresh, 3.0, 10.0)
            }
            assertParity(fresh.predict(points.take(301), events), live.predict(points.take(301), events))
            assertTrue(live.lastDiagnosticsForTest()!!.kfHistoryRebuilt)
        }
    }

    @Test fun unchangedInsulinHistoryPreservesArDuringMinuteGridReplay() = runBlocking {
        val points = minuteSeries()
        val events = listOf(TherapyEvent(points[270].ts, "bolus", mapOf("units" to "3")))
        val live = engine()
        for (size in 300..330) {
            live.predict(points.take(size), events)
            val diagnostics = live.lastDiagnosticsForTest()!!
            assertFalse(diagnostics.kfHistoryRevised)
            if (size > 300) assertTrue(diagnostics.kfHistoryRebuilt)
        }
    }
}
