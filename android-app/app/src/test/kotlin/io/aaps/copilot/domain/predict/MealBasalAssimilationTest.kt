package io.aaps.copilot.domain.predict

import io.aaps.copilot.domain.meal.MealAbsorptionProjection
import io.aaps.copilot.domain.model.*
import io.aaps.copilot.domain.profile.MealAbsorptionProfile
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class MealBasalAssimilationTest {
    private val now = 1_700_000_000_000L
    private val minute = 60_000L
    private val glucose = (0..20).map { GlucosePoint(now - (20 - it) * 5 * minute, 6.0, "sensor") }
    private val events = listOf(TherapyEvent(now, "carbs", mapOf("carbs" to "30"),
        componentTrust = TherapyEventComponentTrust(12, "r1")))
    private fun engine() = HybridPredictionEngine(enableEnhancedPredictionV3 = true, enableUam = false)
    private fun history(engine: HybridPredictionEngine, delivered: Double = 1.5, scheduled: Double = 1.0) =
        MealBasalHistory(now, now, "history-1", listOf(MealBasalInterval("basal-1", "r1",
            now - 1_440 * minute, now, delivered, scheduled, engine.mealInsulinKernelIdentity())))
    private fun food(grams: Double = 0.0, delay: Int = 0, horizon: Int = 360) =
        MealAbsorptionProjection.project(now, now + delay * minute, grams, MealAbsorptionProfile.MIXED, 120, horizon)
    private fun prepare(engine: HybridPredictionEngine, history: MealBasalHistory = history(engine), horizon: Int = 360) =
        engine.prepareMealTimingBasal(glucose, events, history, horizon, now)

    @Test fun steadyDeviationHasAnalyticHistoricalEffectAndScheduledDeliveryHasNone() {
        val engine = engine()
        val receipt = prepare(engine)
        val expected = -receipt.projection.isfMmolPerUnit * 0.5 * 5.0 / 60.0
        receipt.historicalEffectsMmol.drop(1).forEach { assertEquals(expected, it, 1e-12) }
        val scheduled = prepare(engine, history(engine, 1.0, 1.0))
        assertFalse(scheduled.hasEffect)
        assertTrue(scheduled.historicalEffectsMmol.all { it == 0.0 })
    }

    @Test fun laterDeliveryCannotLeakIntoEarlierObservedIntervals() {
        val engine = engine()
        val normal = history(engine, 1.0).intervals.single()
        val source = MealBasalHistory(now, now, "changed-1", listOf(normal.copy(endMs = now - 20 * minute),
            normal.copy(id = "late", startMs = now - 20 * minute, deliveredUnitsPerHour = 2.0)))
        val result = prepare(engine, source)
        result.timestamps.indices.filter { result.timestamps[it] <= now - 20 * minute }.forEach {
            assertEquals(0.0, result.historicalEffectsMmol[it], 0.0)
        }
        assertTrue(result.projection.positiveDeviation.activeUnits > 0.0)
    }

    @Test fun intervalPartitionAndInputOrderDoNotChangeTheConvolution() {
        val engine = engine()
        val normal = history(engine).intervals.single()
        val split = MealBasalHistory(now, now, "split-1", listOf(normal.copy(id = "b", startMs = now - 33 * minute),
            normal.copy(endMs = now - 33 * minute)))
        val a = prepare(engine)
        val b = prepare(engine, split)
        a.historicalEffectsMmol.zip(b.historicalEffectsMmol).forEach { (x, y) -> assertEquals(x, y, 1e-12) }
    }

    @Test fun replayRequiresHistoryBeforeTheEarliestGlucoseNotOnlyBeforeNow() {
        val engine = engine()
        val projected = prepare(engine).projection
        val interval = history(engine).intervals.single()
        val anchorOnly = MealBasalHistory(now, now, "anchor-only", listOf(
            interval.copy(startMs = projected.requiredHistoryStartMs)))
        assertNotNull(engine.projectMealTimingBasal(glucose, events, anchorOnly, 360, now))
        assertThrows(IllegalArgumentException::class.java) { prepare(engine, anchorOnly) }
        val exactStart = projected.requiredHistoryStartMs - (now - glucose.first().ts)
        assertNotNull(prepare(engine, MealBasalHistory(now, now, "exact-1", listOf(interval.copy(startMs = exactStart)))))
        assertThrows(IllegalArgumentException::class.java) { prepare(engine,
            MealBasalHistory(now, now, "short-1", listOf(interval.copy(startMs = exactStart + 1)))) }
    }

    @Test fun unknownReplayIntervalAndPreDeliveryKernelReject() {
        val engine = engine()
        val result = prepare(engine)
        assertThrows(IllegalArgumentException::class.java) { result.effectBetween(now - minute, now) }
        engine.setInsulinOnsetMinutes(30.0, 0.0)
        assertThrows(IllegalArgumentException::class.java) { prepare(engine) }
    }

    @Test fun historyTooShortOrStaleOrBadQualityCannotStartAReplay() {
        val engine = engine()
        for (points in listOf(glucose.takeLast(2), glucose.map { it.copy(quality = DataQuality.SENSOR_ERROR) })) {
            assertThrows(IllegalArgumentException::class.java) {
                engine.prepareMealTimingBasal(points, events, history(engine), 360, now)
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            engine.prepareMealTimingBasal(glucose, events, history(engine), 360, now + 300_001L)
        }
    }

    @Test fun futureMealAndInsulinDoNotChangeRefittedHistoryAndLiveEngineIsUntouched() = runBlocking {
        val engine = engine()
        engine.predict(glucose, events)
        val live = engine.diagnosticsSnapshot()
        val receipt = prepare(engine)
        val absent = engine.forecastMealTimingForward(glucose, events, "12", food(), 360, basalAssimilation = receipt)
        val eating = engine.forecastMealTimingForward(glucose, events, "12", food(30.0, 15), 360,
            MealFutureInsulinPlan(now, listOf(MealFutureInsulinDelivery(10, 1.0))), receipt)
        assertEquals(absent.trendSteps, eating.trendSteps)
        assertEquals(absent.glucoseMmol.first(), eating.glucoseMmol.first(), 0.0)
        assertNotEquals(absent.glucoseMmol, eating.glucoseMmol)
        assertSame(live, engine.diagnosticsSnapshot())
        assertFalse(absent.trajectoryUncertaintyValidated)
        assertFalse(absent.futureControlSimulated)
    }

    @Test fun refitCannotReuseResidualsLearnedFromAnotherHistory() = runBlocking {
        val used = engine()
        used.predict(glucose.map { it.copy(valueMmol = 9.0) }, events)
        used.predict(glucose, events)
        val fresh = engine()
        val a = used.forecastMealTimingForward(glucose, events, "12", food(), 360, basalAssimilation = prepare(used))
        val b = fresh.forecastMealTimingForward(glucose, events, "12", food(), 360, basalAssimilation = prepare(fresh))
        assertEquals(a.glucoseMmol, b.glucoseMmol)
        assertEquals(a.trendSteps, b.trendSteps)
    }

    @Test fun basalIsNotSimplySubtractedAgainFromTheOldPath() {
        val engine = engine()
        val before = engine.forecastMealTimingForward(glucose, events, "12", food(), 360)
        val receipt = prepare(engine)
        val after = engine.forecastMealTimingForward(glucose, events, "12", food(), 360, basalAssimilation = receipt)
        assertNotEquals(before.trendSteps, after.trendSteps)
        assertNotEquals(before.glucoseMmol[12] + receipt.projection.signedDeviationStepEffectsMmol.take(13).sum(),
            after.glucoseMmol[12], 1e-6)
    }

    @Test fun scheduledTailMustStillFinishAndZeroDeviationDoesNotChangeForecast() {
        val engine = engine()
        val receipt = prepare(engine, history(engine, 1.0), 60)
        val before = engine.forecastMealTimingForward(glucose, events, "12", food(), 60)
        val after = engine.forecastMealTimingForward(glucose, events, "12", food(), 60, basalAssimilation = receipt)
        assertEquals(before.glucoseMmol, after.glucoseMmol)
        assertTrue(before.modeledTailsComplete)
        assertFalse(after.modeledTailsComplete)
        assertEquals(0.0, after.basalProjection!!.remainingDeviationMagnitudeUnits, 0.0)
    }

    @Test fun incompatibleKernelHorizonOrTimelineCannotReuseAReceipt() {
        val engine = engine()
        val receipt = prepare(engine)
        for (action in listOf<() -> Unit>(
            { engine.forecastMealTimingForward(glucose, events, "12", food(), 60, basalAssimilation = receipt) },
            { engine.forecastMealTimingForward(glucose.drop(1), events, "12", food(), 360, basalAssimilation = receipt) },
            { engine.setInsulinProfile(InsulinActionProfileId.FIASP)
                engine.forecastMealTimingForward(glucose, events, "12", food(), 360, basalAssimilation = receipt) }
        )) assertThrows(IllegalArgumentException::class.java) { action() }
    }

    @Test fun unsupportedExtendedAndInferredDeliveryCannotBeAddedToBasal() {
        val engine = engine()
        for (event in listOf(TherapyEvent(now - minute, "extended_bolus", mapOf("insulin" to "1.0")),
            TherapyEvent(now - minute, "bolus", mapOf("insulin" to "1.0", "inferred" to "true")))) {
            assertThrows(IllegalArgumentException::class.java) {
                engine.prepareMealTimingBasal(glucose, events + event, history(engine), 360, now)
            }
        }
    }

    @Test fun missingRawGlucoseCannotBeHiddenByCanonicalInterpolation() {
        val engine = engine()
        val gap = glucose.take(4) + glucose.takeLast(4)
        assertThrows(IllegalArgumentException::class.java) {
            engine.prepareMealTimingBasal(gap, events, history(engine), 360, now)
        }
    }
}
