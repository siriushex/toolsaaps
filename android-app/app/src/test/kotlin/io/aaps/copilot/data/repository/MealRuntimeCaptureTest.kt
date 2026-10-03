package io.aaps.copilot.data.repository

import io.aaps.copilot.data.local.entity.ForecastEntity
import io.aaps.copilot.domain.model.Forecast
import io.aaps.copilot.domain.model.GlucoseCalibrationCycleIdentity
import io.aaps.copilot.domain.model.GlucosePoint
import io.aaps.copilot.domain.model.TherapyEvent
import io.aaps.copilot.domain.predict.HybridPredictionEngine
import io.aaps.copilot.domain.predict.PredictionEngine
import io.aaps.copilot.domain.predict.SensitivityAcceptedForecastDecomposition
import io.aaps.copilot.domain.predict.SensitivityAcceptedForecastDigest
import io.aaps.copilot.domain.predict.SensitivityAcceptedForecastRow
import io.aaps.copilot.domain.predict.SensitivityMetricDecision
import io.aaps.copilot.domain.predict.SensitivityResolvedSource
import io.aaps.copilot.domain.predict.SensitivityRuntimeSnapshot
import io.aaps.copilot.domain.predict.SensitivitySourcePreference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test

class MealRuntimeCaptureTest {
    private val now = 1_700_000_000_000L
    private val calibration = GlucoseCalibrationCycleIdentity(null, null, now)

    private data class Fixture(
        val engine: HybridPredictionEngine,
        val glucose: MutableList<GlucosePoint>,
        val therapy: MutableList<TherapyEvent>,
        val local: List<Forecast>,
        val sensitivity: SensitivityRuntimeSnapshot,
        val tuple: AcceptedSensitivityRoomTuple
    )

    private suspend fun fixture(): Fixture {
        val engine = HybridPredictionEngine(enableEnhancedPredictionV3 = true, enableUam = false)
        val glucose = (0..20).map { i ->
            GlucosePoint(now - (20 - i) * 300_000L, 6.0 + i * 0.02, "sensor")
        }.toMutableList()
        val therapy = mutableListOf(TherapyEvent(now - 1_800_000L, "bolus", mutableMapOf("units" to "0.3")))
        val local = engine.predict(glucose, therapy)
        fun decision(value: Double) = SensitivityMetricDecision(SensitivitySourcePreference.COPILOT,
            SensitivityResolvedSource.COPILOT_NATIVE, null, null, value, null, value, 1.0, null)
        val sensitivity = SensitivityRuntimeSnapshot(7, "meal-cycle-7", now, decision(3.0), decision(10.0))
        val rows = local.map { ForecastEntity(timestamp = it.ts, horizonMinutes = it.horizonMinutes,
            valueMmol = it.valueMmol + 0.2, ciLow = it.ciLow + 0.2, ciHigh = it.ciHigh + 0.2,
            modelVersion = it.modelVersion + "-control") }
        val decomposition = SensitivityAcceptedForecastDecomposition.unavailable()
        val digest = requireNotNull(SensitivityAcceptedForecastDigest.compute(
            sensitivity.forecastCycleId, sensitivity.settingsRevision,
            rows.map { SensitivityAcceptedForecastRow(it.horizonMinutes, it.timestamp,
                it.valueMmol, it.ciLow, it.ciHigh, it.modelVersion) }, decomposition))
        val accepted = AcceptedForecastTuple(sensitivity, rows.associateBy { it.horizonMinutes },
            now, digest, decomposition)
        return Fixture(engine, glucose, therapy, local, sensitivity,
            AcceptedSensitivityRoomTuple(sensitivity, sensitivity.toEntity(), rows, accepted, null, now + 100))
    }

    private suspend fun update(
        fixture: Fixture,
        tuple: AcceptedSensitivityRoomTuple = fixture.tuple,
        sensitivity: SensitivityRuntimeSnapshot = fixture.sensitivity,
        sourceCalibration: GlucoseCalibrationCycleIdentity = calibration,
        local: List<Forecast> = fixture.local,
        capturedAt: Long = now + 100
    ): MealRuntimeUpdate = coroutineScope {
        val relay = MealRuntimeCaptureRelay { capturedAt }
        val result = async(start = CoroutineStart.UNDISPATCHED) { relay.updates.first() }
        relay.publish(fixture.engine, fixture.glucose, fixture.therapy, local,
            sensitivity, sourceCalibration, tuple)
        result.await()
    }

    @Test fun noSubscriberDoesNotReadClockOrRunPrediction() = runBlocking {
        var clockReads = 0
        var predictions = 0
        val relay = MealRuntimeCaptureRelay { clockReads++; now }
        val unsupported = object : PredictionEngine {
            override suspend fun predict(glucose: List<GlucosePoint>, therapyEvents: List<TherapyEvent>): List<Forecast> {
                predictions++
                error("Unexpected prediction")
            }
        }
        val fixture = fixture()
        relay.publish(unsupported, emptyList(), emptyList(), emptyList(),
            fixture.sensitivity, calibration, fixture.tuple)
        assertEquals(0, clockReads)
        assertEquals(0, predictions)
    }

    @Test fun acceptedCaptureFreezesRawEngineAndSeparateControlForecasts() = runBlocking {
        val fixture = fixture()
        val diagnostics = fixture.engine.diagnosticsSnapshot()
        val captured = (update(fixture) as MealRuntimeUpdate.Captured).snapshot
        assertSame(diagnostics, fixture.engine.diagnosticsSnapshot())
        assertEquals(fixture.local, captured.simulation.baselineForecasts)
        assertNotEquals(captured.simulation.baselineForecasts, captured.controlForecasts)
        assertEquals(fixture.tuple.accepted.forecastDigest, captured.forecastDigest)
        assertEquals(fixture.tuple.acceptedAtTs, captured.acceptedAtMs)
        assertEquals(calibration, captured.calibrationIdentity)
        (fixture.therapy[0].payload as MutableMap)["units"] = "10.0"
        fixture.therapy.clear()
        fixture.glucose.clear()
        assertEquals("0.3", captured.simulation.therapy.single().payload["units"])
        assertEquals(fixture.local, captured.simulation.newEngine().predict(
            captured.simulation.glucose, captured.simulation.therapy))
        assertThrows(UnsupportedOperationException::class.java) {
            (captured.controlForecasts as MutableList).clear()
        }
        Unit
    }

    @Test fun tupleIntegrityFailuresNeverPublishAContext() = runBlocking {
        val fixture = fixture()
        val tuple = fixture.tuple
        val invalid = listOf(
            tuple.copy(accepted = tuple.accepted.copy(error = AcceptedForecastTupleError.CYCLE_MISMATCH)),
            tuple.copy(accepted = tuple.accepted.copy(forecastDigest = "0".repeat(64))),
            tuple.copy(snapshotEntity = tuple.snapshotEntity.copy(settingsRevision = 8)),
            tuple.copy(forecasts = tuple.forecasts.dropLast(1)),
            tuple.copy(accepted = tuple.accepted.copy(calibrationSessionKey = "different-session")),
            tuple.copy(acceptedAtTs = now + 101)
        )
        for (candidate in invalid) {
            assertEquals(MealRuntimeUpdate.Unavailable(MealRuntimeUnavailableReason.INVALID_ACCEPTED_CONTEXT),
                update(fixture, tuple = candidate))
        }
    }

    @Test fun sourceIdentityOrRawBaselineMismatchIsRejected() = runBlocking {
        val fixture = fixture()
        assertTrue(update(fixture, sensitivity = fixture.sensitivity.copy(forecastCycleId = "different-cycle"))
            is MealRuntimeUpdate.Unavailable)
        assertTrue(update(fixture, sourceCalibration = calibration.copy(modelId = "different-model"))
            is MealRuntimeUpdate.Unavailable)
        assertTrue(update(fixture, local = fixture.local.map { it.copy(valueMmol = it.valueMmol + 0.1) })
            is MealRuntimeUpdate.Unavailable)
    }

    @Test fun staleOrFutureInputsDoNotBecomeAnAcceptedSnapshot() = runBlocking {
        val fixture = fixture()
        assertTrue(update(fixture, capturedAt = now + 300_001) is MealRuntimeUpdate.Unavailable)
        fixture.therapy.add(TherapyEvent(now + 1, "carbs", mapOf("grams" to "15")))
        assertTrue(update(fixture) is MealRuntimeUpdate.Unavailable)
    }

    @Test fun cancellationIsNotConvertedIntoUnavailableAndNeverMutatesLiveEngine() = runBlocking {
        val fixture = fixture()
        val diagnostics = fixture.engine.diagnosticsSnapshot()
        val relay = MealRuntimeCaptureRelay { throw CancellationException("capture cancelled") }
        val next = async(start = CoroutineStart.UNDISPATCHED) { relay.updates.first() }
        try {
            relay.publish(fixture.engine, fixture.glucose, fixture.therapy, fixture.local,
                fixture.sensitivity, calibration, fixture.tuple)
            fail("Cancellation was swallowed")
        } catch (_: CancellationException) { }
        yield()
        assertFalse(next.isCompleted)
        next.cancel()
        assertSame(diagnostics, fixture.engine.diagnosticsSnapshot())
    }

    @Test fun startingAnotherCycleInvalidatesThePreviousResearchContext() = runBlocking {
        val relay = MealRuntimeCaptureRelay { now }
        val result = async(start = CoroutineStart.UNDISPATCHED) { relay.updates.first() }
        relay.invalidate()
        assertEquals(MealRuntimeUpdate.Unavailable(MealRuntimeUnavailableReason.CYCLE_PENDING), result.await())
        val late = async(start = CoroutineStart.UNDISPATCHED) { relay.updates.first() }
        yield()
        assertFalse(late.isCompleted)
        late.cancel()
    }
}
