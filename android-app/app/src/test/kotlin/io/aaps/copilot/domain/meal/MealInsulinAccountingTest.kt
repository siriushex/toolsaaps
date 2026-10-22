package io.aaps.copilot.domain.meal

import io.aaps.copilot.domain.model.*
import io.aaps.copilot.domain.predict.*
import org.junit.Assert.*
import org.junit.Test

class MealInsulinAccountingTest {
    private val now = 1_700_000_000_000L
    private val glucose = (0..20).map { GlucosePoint(now - (20 - it) * 300_000L, 6.0, "sensor") }
    private val bolus = TherapyEvent(now - 600_000L, "bolus", mapOf("units" to "2.2"))
    private fun snapshot(bolus: Double = 2.2, basal: Double = -0.8, time: Long = now) =
        InsulinRuntimeSnapshotResolver.resolve(now, InsulinComponentTelemetry(TimedInsulinValue(bolus + basal, time),
            TimedInsulinValue(bolus, time), TimedInsulinValue(basal, time), TimedInsulinValue(0.02, time))).snapshot!!

    @Test fun comparesBolusNotSignedNetAndKeepsUnmodeledBasalExplicit() {
        val engine = HybridPredictionEngine(enableEnhancedPredictionV3 = true)
        val curve = engine.projectMealTimingInsulin(glucose, listOf(bolus), 360)
        val result = MealInsulinAccounting.compare(curve, engine.mealInsulinKernelIdentity(), snapshot(), engine.mealInsulinKernelIdentity())
        assertEquals(MealInsulinAccountingStatus.COMPARABLE_BOLUS_ONLY, result.status)
        assertEquals(2.2 - curve.activeModeledUnits, result.bolusDifferenceUnits!!, 1e-9)
        assertEquals(-0.8, result.signedBasalUnits!!, 0.0)
        assertEquals(0.0, result.positiveBasalUnits!!, 0.0)
        assertFalse(result.fullIobCoverage)
        val positiveBasal = MealInsulinAccounting.compare(curve, engine.mealInsulinKernelIdentity(), snapshot(basal = 1.5), engine.mealInsulinKernelIdentity())
        assertEquals(1.5, positiveBasal.positiveBasalUnits!!, 0.0)
        assertEquals(result.bolusDifferenceUnits, positiveBasal.bolusDifferenceUnits)
    }

    @Test fun differentKernelTimeOrInferredInventoryCannotClaimComparison() {
        val engine = HybridPredictionEngine(enableEnhancedPredictionV3 = true)
        val kernel = engine.mealInsulinKernelIdentity()
        val curve = engine.projectMealTimingInsulin(glucose, listOf(bolus), 360)
        val inferred = engine.projectMealTimingInsulin(glucose,
            listOf(bolus.copy(payload = bolus.payload + ("inferred" to "true"))), 360)
        val results = listOf(
            MealInsulinAccounting.compare(curve, kernel, snapshot(), null) to MealInsulinAccountingStatus.REFERENCE_KERNEL_UNAVAILABLE,
            MealInsulinAccounting.compare(curve, kernel, snapshot(), kernel.copy(durationMinutes = kernel.durationMinutes + 5)) to MealInsulinAccountingStatus.KERNEL_MISMATCH,
            MealInsulinAccounting.compare(curve, kernel, snapshot(time = now - 1), kernel) to MealInsulinAccountingStatus.SNAPSHOT_NOT_ALIGNED,
            MealInsulinAccounting.compare(inferred, kernel, snapshot(), kernel) to MealInsulinAccountingStatus.INFERRED_INVENTORY,
            MealInsulinAccounting.compare(curve, kernel, null, kernel) to MealInsulinAccountingStatus.SNAPSHOT_UNAVAILABLE
        )
        for ((result, expected) in results) {
            assertEquals(expected, result.status)
            assertNull(result.bolusDifferenceUnits)
            assertFalse(result.fullIobCoverage)
        }
    }

    @Test fun aggregateOrAbsentInventoryIsNotEquivalentToKnownBolusCoverage() {
        val engine = HybridPredictionEngine(enableEnhancedPredictionV3 = true)
        val kernel = engine.mealInsulinKernelIdentity()
        val curve = engine.projectMealTimingInsulin(glucose, listOf(bolus), 360)
        val aggregate = InsulinRuntimeSnapshotResolver.resolve(now, legacyIob = TimedInsulinValue(2.2, now)).snapshot
        assertEquals(MealInsulinAccountingStatus.COMPONENTS_UNAVAILABLE,
            MealInsulinAccounting.compare(curve, kernel, aggregate, kernel).status)
        val absent = engine.projectMealTimingInsulin(glucose, emptyList(), 360)
        assertEquals(MealInsulinAccountingStatus.INVENTORY_UNAVAILABLE,
            MealInsulinAccounting.compare(absent, kernel, snapshot(bolus = 0.0), kernel).status)
    }

    @Test fun copiedInvalidComponentsCannotBypassTheExistingSnapshotResolver() {
        val engine = HybridPredictionEngine(enableEnhancedPredictionV3 = true)
        val kernel = engine.mealInsulinKernelIdentity()
        val curve = engine.projectMealTimingInsulin(glucose, listOf(bolus), 360)
        val source = snapshot()
        for (invalid in listOf(source.copy(netIobUnits = 10.0), source.copy(bolusIobUnits = -1.0),
            source.copy(insulinActivity = null), source.copy(effectivePositiveIobUnits = Double.NaN))) {
            assertEquals(MealInsulinAccountingStatus.COMPONENTS_UNAVAILABLE,
                MealInsulinAccounting.compare(curve, kernel, invalid, kernel).status)
        }
    }
}
