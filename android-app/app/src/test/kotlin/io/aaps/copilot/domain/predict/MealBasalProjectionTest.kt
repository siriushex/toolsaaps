package io.aaps.copilot.domain.predict

import io.aaps.copilot.domain.model.GlucosePoint
import org.junit.Assert.*
import org.junit.Test

class MealBasalProjectionTest {
    private val now = 1_700_000_000_000L
    private val minute = 60_000L
    private val glucose = listOf(GlucosePoint(now, 6.0, "sensor"))
    private fun engine() = HybridPredictionEngine(enableEnhancedPredictionV3 = true, enableUam = false)
    private fun interval(engine: HybridPredictionEngine, id: String, start: Long, end: Long,
        delivered: Double, scheduled: Double) = MealBasalInterval(id, "r1", start, end, delivered, scheduled,
        engine.mealInsulinKernelIdentity())
    private fun history(engine: HybridPredictionEngine, delivered: Double = 1.0, scheduled: Double = 1.0) =
        MealBasalHistory(now, now, "history-1", listOf(interval(engine, "segment-1", now - 1_440 * minute,
            now, delivered, scheduled)))
    private fun project(engine: HybridPredictionEngine, history: MealBasalHistory, horizon: Int = 360) =
        engine.projectMealTimingBasal(glucose, emptyList(), history, horizon, now)

    @Test fun scheduledDeliveryHasActivePhysicalInsulinButNoNetDeviation() {
        val engine = engine()
        val result = project(engine, history(engine))
        assertTrue(result.delivered.activeUnits > 0)
        assertEquals(116.375 / 60.0, result.delivered.activeUnits, 1e-12)
        assertEquals(result.delivered.activeUnits, result.scheduled.activeUnits, 0.0)
        assertEquals(0.0, result.signedDeviationActiveUnits, 0.0)
        assertEquals(0.0, result.positiveDeviation.activeUnits, 0.0)
        assertEquals(0.0, result.negativeDeviation.activeUnits, 0.0)
        assertTrue(result.signedDeviationStepEffectsMmol.all { it == 0.0 })
        assertTrue(result.historyWindowCovered)
        assertFalse(result.upstreamCompletenessVerified)
    }

    @Test fun suspensionRetainsNegativeDeviationRatherThanInventingNegativeDelivery() {
        val engine = engine()
        val result = project(engine, history(engine, delivered = 0.0))
        assertEquals(0.0, result.delivered.activeUnits, 0.0)
        assertEquals(-result.scheduled.activeUnits, result.signedDeviationActiveUnits, 1e-12)
        assertEquals(result.scheduled.activeUnits, result.negativeDeviation.activeUnits, 1e-12)
        assertTrue(result.signedDeviationStepEffectsMmol.drop(1).any { it > 0.0 })
        assertEquals(0.0, result.positiveDeviation.activeUnits, 0.0)
    }

    @Test fun opposingIntervalsDoNotCancelPositiveRiskOrUnfinishedTails() {
        val engine = engine()
        val source = MealBasalHistory(now, now, "history-1", listOf(
            interval(engine, "older", now - 1_440 * minute, now - 30 * minute, 0.0, 1.0),
            interval(engine, "recent", now - 30 * minute, now, 2.0, 1.0)))
        val result = project(engine, source, 60)
        assertTrue(result.positiveDeviation.activeUnits > 0.0)
        assertTrue(result.negativeDeviation.activeUnits > 0.0)
        assertEquals(result.positiveDeviation.activeUnits - result.negativeDeviation.activeUnits,
            result.signedDeviationActiveUnits, 1e-12)
        assertEquals(result.delivered.activeUnits - result.scheduled.activeUnits,
            result.signedDeviationActiveUnits, 1e-12)
        assertTrue(result.remainingDeviationMagnitudeUnits > 0.0)
        assertFalse(result.completeByHorizon)
        assertTrue(result.positiveDeviation.activeUnits > maxOf(0.0, result.signedDeviationActiveUnits))
    }

    @Test fun everyComponentConservesMassAndExtendingHorizonPreservesPrefix() {
        for (profile in InsulinActionProfileId.entries) {
            val engine = HybridPredictionEngine(enableEnhancedPredictionV3 = true, defaultInsulinProfileId = profile)
            engine.setInsulinDurationHours(12.0)
            engine.setInsulinOnsetMinutes(0.0, 75.0)
            val input = history(engine, delivered = 2.0)
            val short = project(engine, input, 60)
            val long = project(engine, input, 720)
            for ((first, second) in listOf(short.delivered to long.delivered, short.scheduled to long.scheduled,
                short.positiveDeviation to long.positiveDeviation, short.negativeDeviation to long.negativeDeviation)) {
                assertEquals(first.stepUnits, second.stepUnits.take(13))
                assertEquals(first.activeUnits, first.stepUnits.sum() + first.remainingUnits, 1e-10)
                assertEquals(second.activeUnits, second.stepUnits.sum() + second.remainingUnits, 1e-10)
                assertTrue(first.stepUnits.all { it >= 0.0 && it.isFinite() })
            }
            assertTrue(long.requiredHistoryStartMs < now - 720 * minute)
        }
    }

    @Test fun exactPiecewiseIntegrationDoesNotDependOnHowIntervalsAreSplit() {
        val engine = engine()
        engine.setInsulinDurationHours(4.13)
        engine.setInsulinOnsetMinutes(10.0, 27.5)
        val start = now - 1_440 * minute
        val split = MealBasalHistory(now, now, "split-1", listOf(
            interval(engine, "first", start, now - 23 * minute - 12_345, 1.75, 0.8),
            interval(engine, "last", now - 23 * minute - 12_345, now, 1.75, 0.8)))
        val a = project(engine, history(engine, 1.75, 0.8))
        val b = project(engine, split)
        assertEquals(a.delivered.activeUnits, b.delivered.activeUnits, 1e-12)
        a.delivered.stepUnits.indices.forEach { assertEquals(a.delivered.stepUnits[it], b.delivered.stepUnits[it], 1e-12) }
        assertEquals(a.signedDeviationActiveUnits, b.signedDeviationActiveUnits, 1e-12)
    }

    @Test fun missingGapOverlapAndFutureIntervalsCannotBecomeACompleteHistory() {
        val engine = engine()
        val full = history(engine).intervals.single()
        fun invalid(intervals: List<MealBasalInterval>) = assertThrows(IllegalArgumentException::class.java) {
            project(engine, MealBasalHistory(now, now, "invalid-1", intervals))
        }
        invalid(emptyList())
        invalid(listOf(full.copy(startMs = now - minute)))
        invalid(listOf(full.copy(endMs = now - minute)))
        invalid(listOf(full.copy(endMs = now + 1)))
        invalid(listOf(full.copy(endMs = now - minute), full.copy(id = "second", startMs = now - minute + 1)))
        invalid(listOf(full.copy(endMs = now - minute), full.copy(id = "second", startMs = now - minute - 1)))
        invalid(listOf(full, full))
    }

    @Test fun kernelCausalityAndAnchorMismatchCannotBeHiddenByTheSameNumericIob() {
        val engine = engine()
        val input = history(engine)
        val other = engine().apply { setInsulinProfile(InsulinActionProfileId.FIASP) }
        for (invalid in listOf(input, MealBasalHistory(now + 1, now + 1, "future-1",
            listOf(input.intervals.single().copy(endMs = now + 1))))) {
            assertThrows(IllegalArgumentException::class.java) { project(other, invalid) }
        }
        val late = MealBasalHistory(now, now + 1, "late-1", input.intervals)
        assertThrows(IllegalArgumentException::class.java) { project(engine, late) }
        assertThrows(IllegalArgumentException::class.java) { project(engine, input, 61) }
    }

    @Test fun malformedRatesRevisionsAndAllocationBudgetsRejectExplicitly() {
        val engine = engine()
        val valid = history(engine).intervals.single()
        for (bad in listOf(valid.copy(deliveredUnitsPerHour = Double.NaN),
            valid.copy(scheduledUnitsPerHour = -1.0), valid.copy(revision = ""),
            valid.copy(endMs = valid.startMs), valid.copy(id = ""))) {
            assertThrows(IllegalArgumentException::class.java) {
                project(engine, MealBasalHistory(now, now, "invalid-1", listOf(bad)))
            }
        }
        val intervals = (0..2_880).map { i -> valid.copy(id = "part-$i", startMs = now - (2_881 - i) * minute,
            endMs = now - (2_880 - i) * minute) }
        assertThrows(IllegalArgumentException::class.java) { MealBasalHistory(now, now, "large-1", intervals) }
        assertThrows(IllegalArgumentException::class.java) {
            project(engine, history(engine, Double.MAX_VALUE, 0.0))
        }
    }

    @Test fun frozenHistoryAndProjectionCannotBeChangedByTheCaller() {
        val engine = engine()
        val mutable = history(engine).intervals.toMutableList()
        val frozen = MealBasalHistory(now, now, "history-1", mutable)
        mutable.clear()
        val projected = project(engine, frozen)
        assertEquals(1, frozen.intervals.size)
        assertThrows(UnsupportedOperationException::class.java) { (frozen.intervals as MutableList).clear() }
        assertThrows(UnsupportedOperationException::class.java) { (projected.delivered.stepUnits as MutableList).clear() }
        assertNull(engine.diagnosticsSnapshot())
    }

    @Test fun zeroNetBasalCannotClaimThatThePhysicalBasalTailHasFinished() {
        val engine = engine()
        val result = project(engine, history(engine), 60)
        assertEquals(0.0, result.remainingDeviationMagnitudeUnits, 0.0)
        assertTrue(result.delivered.remainingUnits > 0.0)
        assertTrue(result.scheduled.remainingUnits > 0.0)
        assertFalse(result.completeByHorizon)
    }

    @Test fun coverageUsesExactShiftedSupportAndCannotBeOneMillisecondShort() {
        val engine = engine().apply { setInsulinOnsetMinutes(0.0, 75.0) }
        val reference = project(engine, history(engine))
        val interval = history(engine).intervals.single()
        val exact = MealBasalHistory(now, now, "exact-1", listOf(interval.copy(startMs = reference.requiredHistoryStartMs)))
        assertEquals(reference.delivered.activeUnits, project(engine, exact).delivered.activeUnits, 1e-12)
        assertThrows(IllegalArgumentException::class.java) {
            project(engine, MealBasalHistory(now, now, "short-1", listOf(
                interval.copy(startMs = reference.requiredHistoryStartMs + 1))))
        }
        for (shift in listOf(-30.0, 0.0, 17.5, 75.0)) {
            engine.setInsulinOnsetMinutes(30.0, 30.0 + shift)
            val result = project(engine, history(engine))
            assertEquals(result.delivered.activeUnits,
                result.delivered.stepUnits.sum() + result.delivered.remainingUnits, 1e-10)
        }
    }

    @Test fun sameNetScalarCannotCancelOppositeAgeComponents() {
        val engine = engine()
        val kernel = engine.mealInsulinKernelIdentity()
        val older = interval(engine, "older", now - 1_440 * minute, now - 30 * minute, 0.0, 1.0)
        val recent = interval(engine, "recent", now - 30 * minute, now, 2.0, 1.0)
        val initial = project(engine, MealBasalHistory(now, now, "initial-1", listOf(older, recent)), 60)
        val rate = 1.0 + initial.negativeDeviation.activeUnits / initial.positiveDeviation.activeUnits
        val balanced = project(engine, MealBasalHistory(now, now, "balanced-1", listOf(older,
            recent.copy(deliveredUnitsPerHour = rate, kernel = kernel))), 60)
        assertEquals(0.0, balanced.signedDeviationActiveUnits, 1e-12)
        assertTrue(balanced.positiveDeviation.activeUnits > 0)
        assertTrue(balanced.negativeDeviation.activeUnits > 0)
        assertTrue(balanced.remainingDeviationMagnitudeUnits > 0)
        assertFalse(balanced.completeByHorizon)
        assertTrue(balanced.signedDeviationStepEffectsMmol.drop(1).any { kotlin.math.abs(it) > 1e-6 })
    }

    @Test fun projectionWorkBudgetCannotSilentlyTruncateAValidIntervalInventory() {
        val engine = engine()
        val intervals = (0 until 2_880).map { i -> interval(engine, "part-$i",
            now - (2_880 - i) * minute, now - (2_879 - i) * minute, 1.0, 1.0) }
        val source = MealBasalHistory(now, now, "large-1", intervals)
        assertThrows(IllegalArgumentException::class.java) { project(engine, source, 720) }
    }
}
