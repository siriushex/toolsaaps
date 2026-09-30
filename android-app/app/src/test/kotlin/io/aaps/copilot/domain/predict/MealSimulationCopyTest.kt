package io.aaps.copilot.domain.predict

import io.aaps.copilot.domain.model.GlucosePoint
import io.aaps.copilot.domain.model.TherapyEvent
import io.aaps.copilot.domain.profile.MealAbsorptionContext
import io.aaps.copilot.domain.profile.MealAbsorptionProfile
import io.aaps.copilot.domain.profile.MealAbsorptionSelection
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class MealSimulationCopyTest {
    private val start = 1_700_000_000_000L
    private val glucose = (0..30).map { i ->
        GlucosePoint(start + i * 300_000L, 6.0 + i * 0.035 + (i % 3) * 0.04, "sensor")
    }
    private val events = listOf(
        TherapyEvent(start + 60 * 60_000L, "bolus", mapOf("units" to "1.2")),
        TherapyEvent(start + 130 * 60_000L, "carbs", mapOf("grams" to "35"))
    )
    private fun configured(logger: ((String) -> Unit)? = null) = HybridPredictionEngine(
        enableEnhancedPredictionV3 = true, enableUam = true,
        enableDebugLogs = logger != null, debugLogger = logger
    ).apply {
        setInsulinProfile(InsulinActionProfileId.NOVORAPID)
        setInsulinDurationHours(4.0)
        setInsulinOnsetMinutes(15.0, 30.0)
        setSensitivityOverride(2.3, 9.0, 1.0)
        setCarbSafetyLimits(150, 45.0)
        setMealAbsorptionContext(MealAbsorptionContext(enabled = true,
            manual = MealAbsorptionSelection(MealAbsorptionProfile.FAT_PROTEIN, 300)))
        setUamRuntimeHint(start + 125 * 60_000L, 12.0, 0.8)
        setUamRuntimeQualityContext(0.95, 0.9, 0.8, false)
    }
    private suspend fun warm(engine: HybridPredictionEngine) {
        for (n in 12..29) engine.predict(glucose.take(n), events)
    }

    @Test fun copyPreservesWarmStateAndAllConfiguredPredictionInputs() = runBlocking {
        val live = configured()
        warm(live)
        assertFalse(checkNotNull(live.lastDiagnosticsForTest()).arUsedFallback)
        val copy = live.forkForMealSimulation()
        val actual = copy.predict(glucose, events)
        val expected = live.predict(glucose, events)
        assertEquals(expected, actual)
        assertEquals(live.lastDiagnosticsForTest(), copy.lastDiagnosticsForTest())
    }

    @Test fun baselineReproducesAlreadyAcceptedCycleWithoutResettingHistory() = runBlocking {
        val live = configured()
        warm(live)
        val expected = live.predict(glucose, events)
        val diagnostics = live.lastDiagnosticsForTest()
        val copy = live.forkForMealSimulation()
        assertEquals(expected, copy.predict(glucose, events))
        // Repeating the accepted input must not apply the same CGM updates twice.
        assertEquals(diagnostics?.copy(kfAppliedUpdates = 0), copy.lastDiagnosticsForTest())
        assertSame(diagnostics, live.lastDiagnosticsForTest())
    }

    @Test fun uamSensitivitySnapshotAndQualityGateSurviveCopy() = runBlocking {
        fun metric(value: Double) = SensitivityMetricDecision(
            SensitivitySourcePreference.AAPS, SensitivityResolvedSource.AAPS,
            value, null, value, null, value, 1.0, null)
        val live = configured().apply {
            setUamSensitivityRuntimeContext(SensitivityRuntimeConsumerContext(
                SensitivityRuntimeConsumer.UAM,
                SensitivityRuntimeSnapshot(17L, "meal-cycle", glucose.last().ts, metric(2.3), metric(9.0))))
            setUamRuntimeQualityContext(0.95, 0.9, 0.8, true)
        }
        warm(live)
        val copy = live.forkForMealSimulation()
        assertEquals(live.predict(glucose, events), copy.predict(glucose, events))
        assertEquals(live.lastDiagnosticsForTest(), copy.lastDiagnosticsForTest())
        assertEquals("meal-cycle", copy.lastDiagnosticsForTest()?.unifiedUamSensitivityCycleId)
    }

    @Test fun uninitializedFilterCopyRemainsUninitializedAndIndependent() {
        val live = KalmanGlucoseFilterV3()
        val copy = live.copyForSimulation()
        assertNull(copy.snapshotOrNull())
        copy.update(7.0, start, 0.0)
        assertNull(live.snapshotOrNull())
        assertEquals(1, copy.snapshotOrNull()?.updatesCount)
    }

    @Test fun candidateOrderCannotMutateSeedOrLiveEngine() = runBlocking {
        val live = configured()
        val control = configured()
        warm(live)
        warm(control)
        val originalDiagnostics = live.lastDiagnosticsForTest()
        val seed = live.forkForMealSimulation()
        val profiles = listOf(MealAbsorptionProfile.FAST, MealAbsorptionProfile.FAT_PROTEIN)
        suspend fun run(order: List<MealAbsorptionProfile>) = order.associateWith { profile ->
            seed.forkForMealSimulation().apply {
                setMealAbsorptionContext(MealAbsorptionContext(enabled = true,
                    manual = MealAbsorptionSelection(profile)))
            }.predict(glucose, events)
        }
        val forward = run(profiles)
        assertEquals(forward, run(profiles.reversed()))
        assertNotEquals(forward[profiles[0]], forward[profiles[1]])
        assertSame(originalDiagnostics, live.lastDiagnosticsForTest())
        assertEquals(control.predict(glucose, events), live.predict(glucose, events))
    }

    @Test fun frozenCopyIgnoresLaterLiveSettingsAndNeverCallsLiveLogger() = runBlocking {
        var logs = 0
        val live = configured { logs++ }
        warm(live)
        val seed = live.forkForMealSimulation()
        val expected = seed.forkForMealSimulation().predict(glucose, events)
        val previousLogs = logs
        live.setSensitivityOverride(5.0, 20.0, 1.0)
        live.setMealAbsorptionContext(MealAbsorptionContext.DISABLED)
        live.setInsulinDurationHours(8.0)
        assertEquals(expected, seed.forkForMealSimulation().predict(glucose, events))
        assertEquals(previousLogs, logs)
        assertNotEquals(expected, live.predict(glucose, events))
    }

    @Test fun legacyEngineAlsoRetainsItsConfiguration() = runBlocking {
        val live = HybridPredictionEngine(enableEnhancedPredictionV3 = false).apply {
            setInsulinDurationHours(4.0)
            setSensitivityOverride(2.1, 8.0, 1.0)
        }
        assertEquals(live.predict(glucose, events), live.forkForMealSimulation().predict(glucose, events))
    }

    @Test fun kalmanCopyRetainsCovarianceAndHasIndependentUpdates() {
        val live = KalmanGlucoseFilterV3()
        glucose.take(20).forEach { live.update(it.valueMmol, it.ts, 0.35, -0.01) }
        val copy = live.copyForSimulation()
        val control = live.copyForSimulation()
        val next = glucose[20]
        assertEquals(live.update(next.valueMmol, next.ts, 0.4), copy.update(next.valueMmol, next.ts, 0.4))
        copy.reset(14.0, next.ts)
        assertEquals(control.update(next.valueMmol, next.ts, 0.4), live.snapshotOrNull())
    }

    @Test fun revisionAwareCopyRetainsHistoryAndRevisionDetection() {
        val live = RevisionAwareKalmanFilter()
        val inputs = glucose.take(20).map { KalmanHistoryInput(it.ts, it.valueMmol, -0.01) }
        live.update(inputs, 0.3, glucose.take(20))
        val copy = live.copyForSimulation()
        assertEquals(0, copy.update(inputs, 0.3, glucose.take(20)).appliedUpdates)
        val revised = inputs.mapIndexed { i, p -> if (i == 10) p.copy(glucose = p.glucose + 1) else p }
        assertTrue(copy.update(revised, 0.3, glucose.take(20)).rebuilt)
        assertEquals(0, live.update(inputs, 0.3, glucose.take(20)).appliedUpdates)
    }

    @Test fun residualCopyRetainsBucketHistoryWithoutSharingIt() {
        val live = ResidualArModel()
        for (i in 0..20) live.appendOrUpdate(start + i * 300_000L, (i % 5) * 0.07)
        val expected = live.fit(false, 25.0)
        val copy = live.copyForSimulation()
        assertFalse(expected.usedFallback)
        assertEquals(expected, copy.fit(false, 25.0))
        copy.appendOrUpdate(start + 21 * 300_000L, -1.0)
        assertNotEquals(expected, copy.fit(false, 25.0))
        assertEquals(expected, live.fit(false, 25.0))
    }
}
