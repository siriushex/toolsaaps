package io.aaps.copilot.domain.predict

import io.aaps.copilot.domain.meal.MealAbsorptionProjection
import io.aaps.copilot.domain.model.*
import io.aaps.copilot.domain.profile.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class MealExtendedForecastTest {
    private val now = 1_700_000_000_000L
    private val glucose = (0..20).map { GlucosePoint(now - (20 - it) * 300_000L, 6.0, "sensor") }
    private val events = listOf(TherapyEvent(now, "carbs", mapOf("carbs" to "25"),
        componentTrust = TherapyEventComponentTrust(12, "r1")),
        TherapyEvent(now - 300_000L, "bolus", mapOf("units" to "1.0")))
    private fun engine() = HybridPredictionEngine(enableEnhancedPredictionV3 = true, enableUam = false)
    private fun food(horizon: Int = 360, grams: Double = 25.0, delay: Int = 0) =
        MealAbsorptionProjection.project(now, now + delay * 60_000L, grams,
            MealAbsorptionProfile.FAT_PROTEIN, 360, horizon)

    @Test fun longHorizonPreservesFirstHourAndSourceState() = runBlocking {
        val engine = engine()
        engine.predict(glucose, events)
        val state = engine.diagnosticsSnapshot()
        val short = engine.forecastMealTimingForward(glucose, events, "12", food())
        val long = engine.forecastMealTimingForward(glucose, events, "12", food(), horizonMinutes = 360)
        assertEquals(short.forecasts, long.forecasts)
        assertEquals(short.glucoseMmol, long.glucoseMmol.take(13))
        assertEquals(short.trendSteps, long.trendSteps.take(13))
        assertEquals(360, long.horizonMinutes)
        assertEquals(73, long.glucoseMmol.size)
        assertSame(state, engine.diagnosticsSnapshot())
        assertFalse(long.futureControlSimulated)
        assertFalse(long.trajectoryUncertaintyValidated)
    }

    @Test fun tailReportsFoodAndInsulinRatherThanAssumingTheyEnded() {
        val engine = engine().apply { setInsulinDurationHours(6.0) }
        val delayed = food(horizon = 420, delay = 30)
        val short = engine.forecastMealTimingForward(glucose, events, "12", delayed, horizonMinutes = 120)
        val long = engine.forecastMealTimingForward(glucose, events, "12", delayed, horizonMinutes = 420)
        assertTrue(short.remainingFoodGrams > 0.0)
        assertTrue(short.remainingInsulinUnits > 0.0)
        assertFalse(short.modeledTailsComplete)
        assertEquals(0.0, long.remainingFoodGrams, 1e-9)
        assertEquals(0.0, long.remainingInsulinUnits, 1e-9)
        assertTrue(long.modeledTailsComplete)
        assertTrue(long.glucoseMmol.drop(13).zipWithNext().any { (a, b) -> a != b })
    }

    @Test fun firstHourIsIndependentOfLaterHorizonEvenWithColdStart() {
        val engine = engine()
        val shortHistory = glucose.takeLast(2)
        val short = engine.forecastMealTimingForward(shortHistory, events, "12", food())
        val long = engine.forecastMealTimingForward(shortHistory, events, "12", food(), horizonMinutes = 360)
        assertEquals(short.glucoseMmol, long.glucoseMmol.take(13))
        assertEquals(short.forecasts, long.forecasts)
    }

    @Test fun activeUamCannotBeSilentlyExtendedWithZeros() {
        val rising = listOf(6.0, 6.1, 6.2, 6.35, 6.55, 6.8, 7.1, 7.45).mapIndexed { i, value ->
            GlucosePoint(now - (7 - i) * 300_000L, value, "sensor")
        }
        val engine = HybridPredictionEngine(enableEnhancedPredictionV3 = true, enableUam = true).apply {
            setUamSensitivityRuntimeContext(io.aaps.copilot.testSensitivityRuntimeContext(
                consumer = SensitivityRuntimeConsumer.UAM))
            setUamRuntimeQualityContext(0.92, 0.88, 1.0, false)
        }
        assertThrows(IllegalArgumentException::class.java) {
            engine.forecastMealTimingForward(rising, events, "12", food(), horizonMinutes = 360)
        }
        assertNull(engine.diagnosticsSnapshot())
    }

    @Test fun invalidHorizonsAndIncompleteFoodGridAreRejected() {
        for (horizon in listOf(0, 55, 61, 725)) {
            assertThrows(IllegalArgumentException::class.java) {
                engine().forecastMealTimingForward(glucose, events, "12", food(), horizonMinutes = horizon)
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            engine().forecastMealTimingForward(glucose, events, "12", food(120), horizonMinutes = 360)
        }
    }

    @Test fun activeInsulinOutsideLookbackCannotDisappearInLongScenario() {
        val engine = engine().apply { setInsulinDurationHours(12.0) }
        val older = events.map { if (it.type == "bolus") it.copy(ts = now - 9 * 3_600_000L) else it }
        assertThrows(IllegalArgumentException::class.java) {
            engine.forecastMealTimingForward(glucose, older, "12", food(), horizonMinutes = 360)
        }
    }

    @Test fun lateFoodChangesTheTailNotTheFirstHour() {
        val engine = engine()
        val late = engine.forecastMealTimingForward(glucose, events, "12", food(420, delay = 90), 420)
        val none = engine.forecastMealTimingForward(glucose, events, "12", food(420, grams = 0.0), 420)
        assertEquals(none.glucoseMmol.take(13), late.glucoseMmol.take(13))
        assertTrue(late.glucoseMmol[36] > none.glucoseMmol[36])
        assertTrue(late.remainingFoodGrams > 0.0)
    }

    @Test fun numericalClippingIsVisibleRatherThanClaimedAsStableGlucose() {
        val engine = engine()
        val saturated = engine.forecastMealTimingForward(glucose, events, "12", food(360, grams = 200.0), 360)
        assertTrue(saturated.numericLimitsReached)
        val quiet = engine.forecastMealTimingForward(glucose, events.take(1), "12", food(360, grams = 0.0), 360)
        assertFalse(quiet.numericLimitsReached)
    }

    @Test fun maximumHorizonDoesNotPretendDelayedInsulinFinished() {
        val engine = engine().apply {
            setInsulinDurationHours(12.0)
            setInsulinOnsetMinutes(0.0, 75.0)
        }
        val long = engine.forecastMealTimingForward(glucose, events, "12", food(720), 720)
        assertEquals(145, long.glucoseMmol.size)
        assertTrue(long.glucoseMmol.all { it.isFinite() })
        assertTrue(long.remainingInsulinUnits > 0.0)
        assertFalse(long.modeledTailsComplete)
    }

    @Test fun longCandidateOrderCannotChangeResults() {
        val engine = engine()
        val before = engine.forecastMealTimingForward(glucose, events, "12", food(), 360)
        engine.forecastMealTimingForward(glucose, events, "12", food(420, grams = 0.0), 420)
        val after = engine.forecastMealTimingForward(glucose, events, "12", food(), 360)
        assertEquals(before.glucoseMmol, after.glucoseMmol)
        assertEquals(before.trendSteps, after.trendSteps)
        assertEquals(before.remainingInsulinUnits, after.remainingInsulinUnits, 0.0)
        assertNull(engine.diagnosticsSnapshot())
    }
}
