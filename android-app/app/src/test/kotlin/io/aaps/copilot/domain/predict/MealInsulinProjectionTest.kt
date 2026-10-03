package io.aaps.copilot.domain.predict

import io.aaps.copilot.domain.model.GlucosePoint
import io.aaps.copilot.domain.model.TherapyEvent
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class MealInsulinProjectionTest {
    private val now = 1_700_000_000_000L
    private val glucose = (0..20).map { GlucosePoint(now - (20 - it) * 300_000L, 6.0, "sensor") }
    private val bolus = TherapyEvent(now - 30 * 60_000L, "bolus", mapOf("units" to "2.0"))
    private fun engine() = HybridPredictionEngine(enableEnhancedPredictionV3 = true, enableUam = false).apply {
        setSensitivityOverrides(
            SensitivityMetricOverride(3.0, 1.0, 0.0, 1.0, "test", authoritative = true),
            SensitivityMetricOverride(10.0, 1.0, 0.0, 1.0, "test", authoritative = true))
    }

    @Test fun firstSixtyMinutesExactlyMatchExistingInsulinComponent() = runBlocking {
        val engine = engine().apply { setInsulinDurationHours(4.0); setInsulinOnsetMinutes(15.0, 35.0) }
        val events = listOf(bolus, bolus.copy(ts = now - 5 * 60_000L, payload = mapOf("units" to "0.3")))
        engine.predict(glucose, events)
        val diagnostics = engine.diagnosticsSnapshot()
        val result = engine.projectMealTimingInsulin(glucose, events, 360)
        assertEquals(diagnostics!!.insulinStep, result.stepEffectsMmol.take(13))
        assertSame(diagnostics, engine.diagnosticsSnapshot())
        assertEquals(3.0, result.isfMmolPerUnit, 0.0)
        assertEquals(0.3, result.csfMmolPerGram, 1e-12)
    }

    @Test fun completeTailAccountsForRemainingModeledInsulin() {
        val engine = engine()
        val result = engine.projectMealTimingInsulin(glucose, listOf(bolus), 720)
        assertTrue(result.completeByHorizon)
        assertEquals(0.0, result.remainingModeledUnitsAtHorizon, 1e-9)
        assertEquals(-engine.modeledActiveInsulinEvidence(listOf(bolus), now).activeUnits * 3.0,
            result.stepEffectsMmol.sum(), 1e-9)
        assertTrue(result.stepEffectsMmol.all { it <= 0.0 })
    }

    @Test fun sixtyMinutesDoesNotPretendInsulinFinished() {
        val result = engine().projectMealTimingInsulin(glucose, listOf(bolus), 60)
        assertFalse(result.completeByHorizon)
        assertTrue(result.remainingModeledUnitsAtHorizon > 0.0)
    }

    @Test fun lateOnsetAndLongDiaKeepTailIncompleteAtBudgetBoundary() {
        val result = engine().apply {
            setInsulinDurationHours(12.0)
            setInsulinOnsetMinutes(0.0, 75.0)
        }.projectMealTimingInsulin(glucose, listOf(bolus.copy(ts = now)), 720)
        assertFalse(result.completeByHorizon)
        assertTrue(result.remainingModeledUnitsAtHorizon > 0.0)
    }

    @Test fun inferredInsulinIsIdentifiedNotPresentedAsConfirmedDelivery() = runBlocking {
        val engine = engine()
        val inferred = bolus.copy(payload = mapOf("units" to "2.0", "inferred" to "true"))
        engine.predict(glucose, listOf(inferred))
        val result = engine.projectMealTimingInsulin(glucose, listOf(inferred), 60)
        assertEquals(1, result.inferredEventCount)
        assertEquals(1, result.modeledEventCount)
        assertEquals(engine.diagnosticsSnapshot()!!.insulinStep, result.stepEffectsMmol)
    }

    @Test fun nonCarryingRecordsDoNotBecomeInsulinAndEmptyIsNotProofOfCoverage() {
        val result = engine().projectMealTimingInsulin(glucose,
            listOf(TherapyEvent(now, "meal", mapOf("units" to "10.0"))), 60)
        assertEquals(0, result.modeledEventCount)
        assertTrue(result.stepEffectsMmol.all { it == 0.0 })
    }

    @Test fun futureEvidenceAndInvalidHorizonAreRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            engine().projectMealTimingInsulin(glucose, listOf(bolus.copy(ts = now + 60_000)), 60)
        }
        for (horizon in listOf(0, 61, 725)) {
            assertThrows(IllegalArgumentException::class.java) {
                engine().projectMealTimingInsulin(glucose, listOf(bolus), horizon)
            }
        }
    }

    @Test fun returnedStepsAreImmutable() {
        val result = engine().projectMealTimingInsulin(glucose, listOf(bolus), 60)
        assertThrows(UnsupportedOperationException::class.java) { (result.stepEffectsMmol as MutableList).clear() }
    }

    @Test fun activeInsulinOutsideExistingLookbackCannotBeSilentlyOmitted() {
        val longDia = engine().apply { setInsulinDurationHours(12.0) }
        assertThrows(IllegalArgumentException::class.java) {
            longDia.projectMealTimingInsulin(glucose, listOf(bolus.copy(ts = now - 9 * 60 * 60_000L)), 720)
        }
    }

    @Test fun completedOldInsulinDoesNotBlockProjection() {
        val result = engine().projectMealTimingInsulin(glucose,
            listOf(bolus.copy(ts = now - 9 * 60 * 60_000L)), 60)
        assertEquals(0, result.modeledEventCount)
        assertEquals(0.0, result.stepEffectsMmol.sum(), 0.0)
    }

    @Test fun allocationBudgetExcessDoesNotSilentlyDropEvents() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            engine().projectMealTimingInsulin(glucose, List(7_000) { bolus }, 720)
        }
        assertEquals("Meal insulin projection budget exceeded", error.message)
    }
}
