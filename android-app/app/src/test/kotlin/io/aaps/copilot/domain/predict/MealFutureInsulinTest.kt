package io.aaps.copilot.domain.predict

import io.aaps.copilot.domain.meal.MealAbsorptionProjection
import io.aaps.copilot.domain.meal.MealSimulationContext
import io.aaps.copilot.domain.model.*
import io.aaps.copilot.domain.profile.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class MealFutureInsulinTest {
    private val now = 1_700_000_000_000L
    private val glucose = (0..20).map { GlucosePoint(now - (20 - it) * 300_000L, 7.0, "sensor") }
    private val therapy = listOf(TherapyEvent(now, "carbs", mapOf("carbs" to "20"),
        componentTrust = TherapyEventComponentTrust(12, "r1")))
    private fun engine() = HybridPredictionEngine(enableEnhancedPredictionV3 = true, enableUam = false).apply {
        setSensitivityOverrides(SensitivityMetricOverride(3.0, 1.0, 0.0, 1.0, "test", authoritative = true),
            SensitivityMetricOverride(10.0, 1.0, 0.0, 1.0, "test", authoritative = true))
        setInsulinDurationHours(4.0)
        setInsulinOnsetMinutes(15.0, 35.0)
    }
    private fun plan(offset: Int = 15, units: Double = 0.5) =
        MealFutureInsulinPlan(now, listOf(MealFutureInsulinDelivery(offset, units)))
    private fun food(horizon: Int) = MealAbsorptionProjection.project(now, now, 20.0,
        MealAbsorptionProfile.FAST, 60, maxOf(120, horizon))

    @Test fun deliveryAtAnchorUsesExactExistingInsulinKernel() {
        val engine = engine()
        val known = engine.projectMealTimingInsulin(glucose,
            listOf(TherapyEvent(now, "bolus", mapOf("units" to "0.5"))), 360)
        val future = engine.projectMealTimingFutureInsulin(glucose, emptyList(), plan(0), 360)
        assertEquals(known.stepEffectsMmol, future.stepEffectsMmol)
        assertEquals(known.remainingModeledUnitsAtHorizon, future.remainingUnitsAtHorizon, 1e-12)
        assertEquals(-1.5, future.stepEffectsMmol.sum(), 1e-10)
    }

    @Test fun delayedDeliveryPreservesCausalityAndMassAcrossTail() {
        val engine = engine()
        val future = engine.projectMealTimingFutureInsulin(glucose, therapy, plan(30), 60)
        assertTrue(future.stepEffectsMmol.take(7).all { it == 0.0 })
        assertEquals(0.5, -future.stepEffectsMmol.sum() / 3.0 + future.remainingUnitsAtHorizon, 1e-10)
        assertTrue(future.remainingUnitsAtHorizon > 0)
        val longer = engine.projectMealTimingFutureInsulin(glucose, therapy, plan(30), 360)
        assertEquals(future.stepEffectsMmol, longer.stepEffectsMmol.take(13))
        assertEquals(0.0, longer.remainingUnitsAtHorizon, 1e-12)
    }

    @Test fun emptyPlanIsExactBaselineAndNonemptyChangesOnlyFuture() = runBlocking {
        val engine = engine()
        engine.predict(glucose, therapy)
        val diagnostics = engine.diagnosticsSnapshot()
        val baseline = engine.forecastMealTimingForward(glucose, therapy, "12", food(180), 180)
        val empty = engine.forecastMealTimingForward(glucose, therapy, "12", food(180), 180,
            MealFutureInsulinPlan(now, emptyList()))
        val future = engine.forecastMealTimingForward(glucose, therapy, "12", food(180), 180, plan())
        assertEquals(baseline.glucoseMmol, empty.glucoseMmol)
        assertEquals(baseline.forecasts, empty.forecasts)
        assertEquals(baseline.glucoseMmol.take(4), future.glucoseMmol.take(4))
        assertEquals(baseline.trendSteps, future.trendSteps)
        assertTrue(future.glucoseMmol.last() < baseline.glucoseMmol.last())
        assertEquals(0.5, future.hypotheticalFutureInsulinUnits, 0.0)
        assertFalse(future.futureControlSimulated)
        assertFalse(future.trajectoryUncertaintyValidated)
        assertSame(diagnostics, engine.diagnosticsSnapshot())
        assertEquals(1, therapy.size)
        assertEquals("20", therapy.single().payload["carbs"])
    }

    @Test fun coldStartCannotLearnHistoricalTrendFromFutureInsulin() {
        val engine = engine()
        val short = glucose.takeLast(2)
        val baseline = engine.forecastMealTimingForward(short, therapy, "12", food(180), 180)
        val future = engine.forecastMealTimingForward(short, therapy, "12", food(180), 180, plan(0))
        assertEquals(baseline.trendSteps, future.trendSteps)
        assertEquals(baseline.glucoseMmol.first(), future.glucoseMmol.first(), 0.0)
        assertNull(engine.diagnosticsSnapshot())
    }

    @Test fun horizonBoundaryDeliveryRetainsEntireUnfinishedTail() {
        val forecast = engine().forecastMealTimingForward(glucose, therapy, "12", food(60), 60, plan(60))
        assertEquals(0.5, forecast.remainingInsulinUnits, 0.0)
        assertFalse(forecast.modeledTailsComplete)
    }

    @Test fun malformedOrOutOfHorizonPlansRejectInsteadOfDroppingDelivery() {
        assertThrows(IllegalArgumentException::class.java) { MealFutureInsulinDelivery(-1, 1.0) }
        assertThrows(IllegalArgumentException::class.java) { MealFutureInsulinDelivery(0, Double.NaN) }
        assertThrows(IllegalArgumentException::class.java) { MealFutureInsulinDelivery(0, -1.0) }
        assertThrows(IllegalArgumentException::class.java) { MealFutureInsulinPlan(now, listOf(
            MealFutureInsulinDelivery(10, 0.1), MealFutureInsulinDelivery(10, 0.1))) }
        val engine = engine()
        for (invalid in listOf(plan(61), MealFutureInsulinPlan(now + 1, plan().deliveries))) {
            assertThrows(IllegalArgumentException::class.java) {
                engine.forecastMealTimingForward(glucose, therapy, "12", food(60), 60, invalid)
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            engine.projectMealTimingFutureInsulin(glucose, therapy, plan(0, Double.MAX_VALUE), 360)
        }
    }

    @Test fun planIsFrozenAndOrderIndependent() {
        val deliveries = mutableListOf(MealFutureInsulinDelivery(30, 0.2), MealFutureInsulinDelivery(10, 0.1))
        val first = MealFutureInsulinPlan(now, deliveries)
        val second = MealFutureInsulinPlan(now, deliveries.reversed())
        deliveries.clear()
        val engine = engine()
        val projection = engine.projectMealTimingFutureInsulin(glucose, therapy, first, 360)
        assertEquals(projection.stepEffectsMmol,
            engine.projectMealTimingFutureInsulin(glucose, therapy, second, 360).stepEffectsMmol)
        assertThrows(UnsupportedOperationException::class.java) { (first.deliveries as MutableList).clear() }
        assertThrows(UnsupportedOperationException::class.java) { (projection.stepEffectsMmol as MutableList).clear() }
    }

    @Test fun frozenContextExposesProjectionWithoutMutatingAcceptedForecast() = runBlocking {
        val engine = engine()
        val accepted = engine.predict(glucose, therapy)
        val context = MealSimulationContext.capture(engine, glucose, therapy, accepted, "cycle-1", 7, now)
        val expected = engine.forecastMealTimingForward(glucose, therapy, "12", food(180), 180, plan())
        assertEquals(expected.glucoseMmol, context.forwardForecast("12", food(180), 180, plan()).glucoseMmol)
        assertEquals(accepted, context.baselineForecasts)
        assertEquals(therapy, context.therapy)
    }

    @Test fun shiftedKernelCannotInventEffectBeforeFutureDelivery() {
        val engine = engine().apply { setInsulinOnsetMinutes(30.0, 0.0) }
        assertThrows(IllegalArgumentException::class.java) {
            engine.projectMealTimingFutureInsulin(glucose, therapy, plan(30), 180)
        }
        val empty = engine.projectMealTimingFutureInsulin(glucose, therapy, MealFutureInsulinPlan(now, emptyList()), 180)
        assertTrue(empty.stepEffectsMmol.all { it == 0.0 })
    }

    @Test fun deliverySuperpositionAndLongDiaPreserveUnits() {
        val engine = engine().apply { setInsulinDurationHours(12.0); setInsulinOnsetMinutes(0.0, 75.0) }
        val first = engine.projectMealTimingFutureInsulin(glucose, therapy, plan(10, 0.2), 720)
        val second = engine.projectMealTimingFutureInsulin(glucose, therapy, plan(30, 0.3), 720)
        val both = engine.projectMealTimingFutureInsulin(glucose, therapy,
            MealFutureInsulinPlan(now, listOf(MealFutureInsulinDelivery(10, 0.2), MealFutureInsulinDelivery(30, 0.3))), 720)
        both.stepEffectsMmol.indices.forEach { i ->
            assertEquals(first.stepEffectsMmol[i] + second.stepEffectsMmol[i], both.stepEffectsMmol[i], 1e-12)
        }
        assertTrue(both.remainingUnitsAtHorizon > 0.0)
        assertEquals(0.5, -both.stepEffectsMmol.sum() / 3.0 + both.remainingUnitsAtHorizon, 1e-10)
    }

    @Test fun allocationBudgetAndDeliveryRangeAreExplicit() {
        assertThrows(IllegalArgumentException::class.java) { MealFutureInsulinDelivery(721, 0.1) }
        assertThrows(IllegalArgumentException::class.java) {
            MealFutureInsulinPlan(now, (0..145).map { MealFutureInsulinDelivery(it, 0.1) })
        }
    }

    @Test fun allSupportedInsulinProfilesUseTheirExistingKernel() {
        for (profile in InsulinActionProfileId.entries) {
            val engine = HybridPredictionEngine(enableEnhancedPredictionV3 = true, enableUam = false,
                defaultInsulinProfileId = profile)
            val known = engine.projectMealTimingInsulin(glucose,
                listOf(TherapyEvent(now, "bolus", mapOf("units" to "0.5"))), 720)
            val future = engine.projectMealTimingFutureInsulin(glucose, emptyList(), plan(0), 720)
            assertEquals(profile.name, known.stepEffectsMmol, future.stepEffectsMmol)
            assertEquals(0.5, -future.stepEffectsMmol.sum() / known.isfMmolPerUnit + future.remainingUnitsAtHorizon, 1e-10)
        }
    }
}
