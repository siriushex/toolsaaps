package io.aaps.copilot.domain.predict

import io.aaps.copilot.domain.model.GlucosePoint
import io.aaps.copilot.domain.model.TherapyEvent
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class MealInsulinProjectionTest {
    @Test fun unmodeledInsulinRowsRemainVisibleAsUnsupportedInventory() {
        for (payload in listOf(emptyMap(), mapOf("units" to "NaN"), mapOf("units" to "-1"),
            mapOf("units" to "40"), mapOf("units" to "0.01"), mapOf("insulinUnits" to "2.0"))) {
            val result = engine().projectMealTimingInsulin(glucose, listOf(bolus.copy(payload = payload)), 60)
            assertEquals(payload.toString(), 1, result.unsupportedEventCount)
        }
    }

    @Test fun conflictingDoseAliasesCannotHideBehindTheFirstValidAmount() {
        for (payload in listOf(mapOf("units" to "2", "insulin" to "3"),
            mapOf("units" to "2", "Units" to "4"), mapOf("units" to "2", "bolusUnits" to "invalid"))) {
            assertEquals(1, engine().projectMealTimingInsulin(glucose,
                listOf(bolus.copy(payload = payload)), 60).unsupportedEventCount)
        }
    }

    @Test fun repeatedSourceIdentityIsNotTwoIndependentDeliveries() {
        val first = bolus.copy(sourceRowId = "delivery-1")
        val result = engine().projectMealTimingInsulin(glucose,
            listOf(first, first.copy(ts = first.ts + 60_000)), 60)
        assertEquals(2, result.unsupportedEventCount)
    }

    @Test fun commandCancellationAndPrimingAreNotConfirmedInventory() {
        for (fields in listOf(mapOf("cancelled" to "true"), mapOf("isCanceled" to "1"),
            mapOf("recovered" to "true"), mapOf("classification" to "LOCAL_ACTION"),
            mapOf("bolusType" to "PRIMING"))) {
            assertEquals(1, engine().projectMealTimingInsulin(glucose,
                listOf(bolus.copy(payload = bolus.payload + fields)), 60).unsupportedEventCount)
        }
    }

    @Test fun validZeroDoseAndRecordedSmbDoNotBecomeInventoryErrors() {
        val events = listOf(bolus.copy(payload = mapOf("units" to "0")),
            bolus.copy(payload = mapOf("units" to "0.2", "insulin" to "0,2", "isSMB" to "true")))
        val result = engine().projectMealTimingInsulin(glucose, events, 60)
        assertEquals(0, result.unsupportedEventCount)
        assertEquals(1, result.modeledEventCount)
    }

    @Test fun activeUnmodeledRowOutsideOrdinaryLookbackCannotDisappear() {
        val result = engine().apply { setInsulinDurationHours(12.0) }.projectMealTimingInsulin(glucose,
            listOf(bolus.copy(ts = now - 9 * 60 * 60_000L, payload = mapOf("units" to "40"))), 720)
        assertEquals(1, result.unsupportedEventCount)
    }

    @Test fun expiredUnmodeledRowThatAffectedHistoricalReplayRemainsUnsupported() {
        val history = (0..72).map { GlucosePoint(now - (72 - it) * 300_000L, 6.0, "sensor") }
        val result = engine().projectMealTimingInsulin(history,
            listOf(bolus.copy(ts = now - 9 * 60 * 60_000L, payload = mapOf("units" to "40"))), 60)
        assertEquals(1, result.unsupportedEventCount)
    }

    @Test fun inferredHistoricalReplayCannotBecomeObservedDeliveryAfterItsTailEnds() {
        val history = (0..72).map { GlucosePoint(now - (72 - it) * 300_000L, 6.0, "sensor") }
        val result = engine().projectMealTimingInsulin(history,
            listOf(bolus.copy(ts = now - 9 * 60 * 60_000L,
                payload = mapOf("units" to "2", "inferred" to "true"))), 60)
        assertEquals(1, result.inferredEventCount)
    }

    @Test fun sourceAndFlagConflictsRemainUnsupported() {
        for (row in listOf(bolus.copy(sourceRowId = "br-local_broadcast-1"), bolus.copy(sourceRowId = ""),
            bolus.copy(payload = bolus.payload + mapOf("inferred" to "false", "Inferred" to "true")),
            bolus.copy(payload = bolus.payload + mapOf("isValid" to "true", "IsValid" to "false")))) {
            assertEquals(1, engine().projectMealTimingInsulin(glucose, listOf(row), 60).unsupportedEventCount)
        }
    }

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

    @Test fun activeAndInferredMassAreConservedWithoutDisplayClamping() {
        val engine = engine()
        val inferred = bolus.copy(payload = mapOf("units" to "2.0", "inferred" to "true"))
        val projection = engine.projectMealTimingInsulin(glucose, List(20) { bolus } + inferred, 60)
        assertTrue(projection.activeModeledUnits > 30.0)
        assertTrue(projection.activeInferredUnits > 0.0)
        assertEquals(projection.activeModeledUnits,
            -projection.stepEffectsMmol.sum() / projection.isfMmolPerUnit + projection.remainingModeledUnitsAtHorizon, 1e-9)
        assertTrue(projection.activeInferredUnits < projection.activeModeledUnits)
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
