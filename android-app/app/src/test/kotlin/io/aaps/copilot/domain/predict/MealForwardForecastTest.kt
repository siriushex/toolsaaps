package io.aaps.copilot.domain.predict

import io.aaps.copilot.domain.meal.MealAbsorptionProjection
import io.aaps.copilot.domain.model.*
import io.aaps.copilot.domain.profile.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class MealForwardForecastTest {
    private val now = 1_700_000_000_000L
    private val glucose = (0..20).map { GlucosePoint(now - (20 - it) * 300_000L, 6.0, "sensor") }
    private val events = listOf(TherapyEvent(now - 900_000L, "carbs", mapOf("carbs" to "30"),
        componentTrust = TherapyEventComponentTrust(12, "r1")))
    private fun engine() = HybridPredictionEngine(enableEnhancedPredictionV3 = true, enableUam = false)
    private fun replacement(grams: Double = 30.0, delay: Int = 0, horizon: Int = 120) =
        MealAbsorptionProjection.project(now, now + delay * 60_000L, grams,
            MealAbsorptionProfile.FAST, 60, horizon)

    @Test fun unchangedFoodReproducesFullEnginePathAndForecasts() = runBlocking {
        val engine = engine()
        val forecasts = engine.predict(glucose, events)
        val original = engine.diagnosticsSnapshot()!!
        val food = engine.projectMealTimingAnnouncedFood(glucose, events, 120).single().projection
        val scenario = engine.forecastMealTimingForward(glucose, events, "12", food)
        assertEquals(forecasts, scenario.forecasts)
        original.glucosePath.zip(scenario.glucoseMmol).forEach { (a, b) -> assertEquals(a, b, 1e-9) }
        assertEquals(13, scenario.glucoseMmol.size)
        assertSame(original, engine.diagnosticsSnapshot())
        assertFalse(scenario.futureControlSimulated)
        assertFalse(scenario.trajectoryUncertaintyValidated)
    }

    @Test fun replacementChangesFutureButNotHistoricalTrendOrSourceEngine() = runBlocking {
        val engine = engine()
        engine.predict(glucose, events)
        val before = engine.diagnosticsSnapshot()!!
        val absent = engine.forecastMealTimingForward(glucose, events, "12", replacement(0.0))
        val immediate = engine.forecastMealTimingForward(glucose, events, "12", replacement())
        val delayed = engine.forecastMealTimingForward(glucose, events, "12", replacement(delay = 30))
        assertEquals(absent.glucoseMmol.first(), immediate.glucoseMmol.first(), 0.0)
        assertEquals(absent.trendSteps, immediate.trendSteps)
        assertEquals(absent.trendSteps, delayed.trendSteps)
        assertTrue(immediate.glucoseMmol[6] > delayed.glucoseMmol[6])
        assertTrue(immediate.glucoseMmol[6] > absent.glucoseMmol[6])
        assertEquals(immediate.glucoseMmol,
            engine.forecastMealTimingForward(glucose, events, "12", replacement()).glucoseMmol)
        assertSame(before, engine.diagnosticsSnapshot())
        assertEquals("30", events.single().payload["carbs"])
        assertThrows(UnsupportedOperationException::class.java) { (immediate.glucoseMmol as MutableList).clear() }
        Unit
    }

    @Test fun coldStartDoesNotInferADifferentPastTrendFromFutureFood() {
        val engine = engine()
        val shortHistory = glucose.takeLast(2)
        val absent = engine.forecastMealTimingForward(shortHistory, events, "12", replacement(0.0))
        val eating = engine.forecastMealTimingForward(shortHistory, events, "12", replacement())
        assertEquals(absent.trendSteps, eating.trendSteps)
        assertTrue(eating.glucoseMmol[1] > absent.glucoseMmol[1])
        assertNull(engine.diagnosticsSnapshot())
    }

    @Test fun otherFoodAndInsulinRemainInTheFullForecast() = runBlocking {
        val engine = engine()
        val others = listOf(events.single().copy(componentTrust = TherapyEventComponentTrust(13, "r2")),
            TherapyEvent(now - 1_800_000L, "bolus", mapOf("units" to "1.0")))
        val all = events + others
        val expected = engine.predict(glucose, all)
        val original = engine.projectMealTimingAnnouncedFood(glucose, all, 120).first { it.canonicalMealId == "12" }
        assertEquals(expected, engine.forecastMealTimingForward(glucose, all, "12", original.projection).forecasts)
        val noInsulin = engine.forecastMealTimingForward(glucose, all.dropLast(1), "12", original.projection)
        assertTrue(noInsulin.glucoseMmol.last() > engine.forecastMealTimingForward(glucose, all, "12", original.projection).glucoseMmol.last())
    }

    @Test fun unknownIdentityAndInsufficientFoodTailAreRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            engine().forecastMealTimingForward(glucose, events, "unknown", replacement())
        }
        assertThrows(IllegalArgumentException::class.java) {
            engine().forecastMealTimingForward(glucose, events, "12", replacement(horizon = 60))
        }
        assertThrows(IllegalArgumentException::class.java) {
            HybridPredictionEngine().forecastMealTimingForward(glucose, events, "12", replacement())
        }
    }

    @Test fun unchangedFoodKeepsExistingUamReconciliation() = runBlocking {
        val rising = listOf(6.0, 6.1, 6.2, 6.35, 6.55, 6.8, 7.1, 7.45).mapIndexed { index, value ->
            GlucosePoint(now - (7 - index) * 300_000L, value, "sensor")
        }
        val engine = HybridPredictionEngine(enableEnhancedPredictionV3 = true, enableUam = true).apply {
            setUamSensitivityRuntimeContext(io.aaps.copilot.testSensitivityRuntimeContext(
                consumer = SensitivityRuntimeConsumer.UAM))
            setUamRuntimeQualityContext(0.92, 0.88, 1.0, false)
        }
        val smallMeal = events.map { it.copy(payload = mapOf("carbs" to "2")) }
        val expected = engine.predict(rising, smallMeal)
        assertTrue(engine.diagnosticsSnapshot()!!.rawUnifiedUamStep.any { it > 0.0 })
        val food = engine.projectMealTimingAnnouncedFood(rising, smallMeal, 120).single().projection
        assertEquals(expected, engine.forecastMealTimingForward(rising, smallMeal, "12", food).forecasts)
    }

    @Test fun wrongAnchorAndUntrustedRevisionAreRejectedWithoutMutatingEngine() {
        val engine = engine()
        val wrongAnchor = MealAbsorptionProjection.project(now + 300_000L, now, 30.0,
            MealAbsorptionProfile.FAST, 60, 120)
        assertThrows(IllegalArgumentException::class.java) {
            engine.forecastMealTimingForward(glucose, events, "12", wrongAnchor)
        }
        assertThrows(IllegalArgumentException::class.java) {
            engine.forecastMealTimingForward(glucose,
                events.map { it.copy(componentTrust = TherapyEventComponentTrust.NONE) }, "12", replacement())
        }
        assertNull(engine.diagnosticsSnapshot())
    }
}
