package io.aaps.copilot.domain.meal

import io.aaps.copilot.domain.model.*
import io.aaps.copilot.domain.predict.HybridPredictionEngine
import io.aaps.copilot.domain.profile.MealAbsorptionProfile
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class MealObservationForecastTest {
    private val now = 1_700_000_000_000L
    private val glucose = (0..20).map { GlucosePoint(now - (20 - it) * 300_000L, 6.0, "sensor") }
    private val events = listOf(TherapyEvent(now, "carbs", mapOf("carbs" to "20"),
        componentTrust = TherapyEventComponentTrust(12, "r1")))
    private suspend fun context(extra: List<TherapyEvent> = emptyList()): MealSimulationContext {
        val engine = HybridPredictionEngine(enableEnhancedPredictionV3 = true, enableUam = false)
        val therapy = events + extra
        return MealSimulationContext.capture(engine, glucose, therapy, engine.predict(glucose, therapy), "cycle-1", 7, now)
    }
    private fun belief() = MealStateEstimator().initialize(
        MealInput("input-1", now, MealCarbRange(20.0, 20.0)), MealHypothesisKind.entries.map { kind ->
            val absent = kind in setOf(MealHypothesisKind.NOT_HAPPENING, MealHypothesisKind.NO_NEW_MEAL)
            val start = when (kind) {
                MealHypothesisKind.UPCOMING -> now + 600_000
                MealHypothesisKind.JUST_STARTED -> now
                MealHypothesisKind.STARTED_EARLIER -> now - 600_000
                else -> now - 1_800_000
            }
            MealScenario(kind.name, kind, if (absent) null else MealStartInterval(start, start),
                MealCarbRange(if (absent) 0.0 else 20.0, if (absent) 0.0 else 20.0),
                listOf(MealAbsorptionAlternative(MealAbsorptionProfile.FAST, 60, 1.0)), 1.0, "12")
        })
    private fun errorModel(cases: MealBelief = belief()) = MealObservationErrorModel(
        "test-errors-v1", "runtime-1", now - 1, cases.scenarios.associate { it.id to 0.5 })

    @Test fun passiveMeansMatchEngineWithoutMovingUpcomingFood() = runBlocking {
        val context = context()
        val source = belief()
        val packet = MealObservationForecast.prepare(context, source, 4, "runtime-1", errorModel()) { now + 1 }
        assertEquals(now + 300_000, packet.sampleAtMs)
        assertEquals(4L, packet.storageRevision)
        assertEquals("cycle-1", packet.forecastCycleId)
        assertEquals(7L, packet.settingsRevision)
        assertEquals("input-1", packet.inputId)
        for (case in source.scenarios) {
            val profile = case.absorption.single()
            val food = MealAbsorptionProjection.project(now, case.inferredStart?.earliestMs ?: now,
                case.carbs.maximumGrams, profile.profile, profile.durationMinutes, 120)
            val expected = context.forwardForecast("12", food).glucoseMmol[1]
            assertEquals(expected, packet.expected.getValue(case.id).meanMmol, 1e-12)
        }
        assertEquals(packet.expected.getValue("NOT_HAPPENING"), packet.expected.getValue("UPCOMING"))
        assertTrue(packet.expected.getValue("JUST_STARTED").meanMmol > packet.expected.getValue("UPCOMING").meanMmol)
        assertEquals(now, context.therapy.single().ts)
        assertEquals(now + 600_000, source.scenarios.first { it.kind == MealHypothesisKind.UPCOMING }.inferredStart!!.earliestMs)
    }

    @Test fun futureSampleCanUpdateEstimatorButWrongTimeOrRuntimeCannot() = runBlocking {
        val source = belief()
        val packet = MealObservationForecast.prepare(context(), source, 4, "runtime-1", errorModel()) { now + 1 }
        val sample = GlucosePoint(packet.sampleAtMs, packet.expected.getValue("JUST_STARTED").meanMmol, "sensor")
        val observation = packet.observation("sample-1", sample, "runtime-1", 0, true, sample.ts)!!
        assertEquals(MealUpdateReason.UPDATED, MealStateEstimator().observe(source, observation).reason)
        assertEquals(now + 1, observation.predictedAtMs)
        assertNull(packet.observation("sample-1", sample.copy(ts = now), "runtime-1", 0, true, sample.ts))
        assertNull(packet.observation("sample-1", sample.copy(ts = sample.ts + 1), "runtime-1", 0, true, sample.ts + 1))
        assertNull(packet.observation("sample-1", sample, "runtime-2", 0, true, sample.ts))
        assertNull(packet.observation("sample-1", sample, "runtime-1", 1, true, sample.ts))
        assertNull(packet.observation("sample-1", sample, "runtime-1", 0, false, sample.ts))
        assertNull(packet.observation("sample-1", sample, "runtime-1", 0, true, sample.ts + 300_001))
    }

    @Test fun completionAfterSampleDeadlineCannotBeBackdated() = runBlocking {
        val context = context()
        var call = 0
        try {
            MealObservationForecast.prepare(context, belief(), 4, "runtime-1", errorModel()) {
                if (call++ == 0) now + 1 else now + 300_000
            }
            fail("Late calculation accepted")
        } catch (_: IllegalArgumentException) { }
    }

    @Test fun errorModelMustExistBeforeCaptureAndMatchEveryScenario() = runBlocking {
        val context = context()
        val valid = belief().scenarios.associate { it.id to 0.5 }
        for (model in listOf(
            MealObservationErrorModel("future", "runtime-1", now + 1, valid),
            MealObservationErrorModel("other", "runtime-2", now - 1, valid),
            MealObservationErrorModel("other-case", "runtime-1", now - 1, valid - "UPCOMING" + ("unknown" to 0.5))
        )) {
            try { MealObservationForecast.prepare(context, belief(), 4, "runtime-1", model) { now + 1 }; fail("Invalid error model accepted") }
            catch (_: IllegalArgumentException) { }
        }
    }

    @Test fun errorMapAndPreparedEvidenceAreImmutable() = runBlocking {
        val scales = belief().scenarios.associate { it.id to 0.5 }.toMutableMap()
        val model = MealObservationErrorModel("test-errors-v1", "runtime-1", now - 1, scales)
        scales.clear()
        val packet = MealObservationForecast.prepare(context(), belief(), 4, "runtime-1", model) { now + 1 }
        assertEquals(6, packet.expected.size)
        assertThrows(UnsupportedOperationException::class.java) { (packet.expected as MutableMap).clear() }
        Unit
    }

    @Test fun intervalsAreRejectedRatherThanSilentlyAveraged() = runBlocking {
        val source = belief()
        val cases = source.scenarios.map { case ->
            if (case.kind != MealHypothesisKind.UPCOMING) case else MealScenario(case.id, case.kind,
                MealStartInterval(now, now + 600_000), case.carbs, case.absorption, case.probability, case.canonicalMealId)
        }
        val ranged = MealBelief(source.input, cases, 0)
        try { MealObservationForecast.prepare(context(), ranged, 4, "runtime-1", errorModel()) { now + 1 }; fail("Unresolved interval accepted") }
        catch (_: IllegalArgumentException) { }
    }

    @Test fun cancelledPreparationCannotReturnPartialEvidence() = runBlocking {
        val context = context()
        val cancelled = Job().apply { cancel() }
        try {
            withContext(cancelled) { MealObservationForecast.prepare(context, belief(), 4, "runtime-1", errorModel()) { now + 1 } }
            fail("Cancellation ignored")
        } catch (_: CancellationException) { }
    }

    @Test fun errorScalesCannotBeMissingZeroOrNonFinite() {
        val valid = belief().scenarios.associate { it.id to 0.5 }
        for (scales in listOf(valid - "UPCOMING", valid + ("UPCOMING" to 0.0),
            valid + ("UPCOMING" to Double.NaN), valid + ("UPCOMING" to Double.POSITIVE_INFINITY))) {
            assertThrows(IllegalArgumentException::class.java) {
                MealObservationErrorModel("test-errors-v1", "runtime-1", now - 1, scales)
            }
        }
    }

    @Test fun repeatedCasesShareWorkWithoutLosingTheirIdentities() = runBlocking {
        val context = context()
        val source = belief()
        val first = MealObservationForecast.prepare(context, source, 4, "runtime-1", errorModel()) { now + 1 }
        val reversed = MealBelief(source.input, source.scenarios.reversed(), source.revision)
        val second = MealObservationForecast.prepare(context, reversed, 4, "runtime-1", errorModel()) { now + 1 }
        assertEquals(first.expected, second.expected)
        assertEquals(5, first.evaluatedForecastCount)
        assertEquals(6, first.expected.size)
    }

    @Test fun clockRollbackAndExpiredCaptureRejectWithoutBackdating() = runBlocking {
        val context = context()
        for (times in listOf(listOf(now - 1, now), listOf(now + 300_000, now + 300_000), listOf(now + 2, now + 1))) {
            val clock = times.iterator()
            try { MealObservationForecast.prepare(context, belief(), 4, "runtime-1", errorModel()) { clock.next() }; fail("Invalid clock accepted") }
            catch (_: IllegalArgumentException) { }
        }
    }

    @Test fun ambiguousIdentityAndOversizedHistoryRejectWithoutDroppingEvidence() = runBlocking {
        val source = belief()
        val unknown = MealBelief(source.input, source.scenarios.map { case ->
            MealScenario(case.id, case.kind, case.inferredStart, case.carbs, case.absorption, case.probability, "missing")
        }, 0)
        try { MealObservationForecast.prepare(context(), unknown, 4, "runtime-1", errorModel()) { now + 1 }; fail("Unknown meal accepted") }
        catch (_: IllegalArgumentException) { }
        val large = context(List(19_000) { TherapyEvent(now - it, "note", emptyMap()) })
        try { MealObservationForecast.prepare(large, source, 4, "runtime-1", errorModel()) { now + 1 }; fail("Oversized work accepted") }
        catch (error: IllegalArgumentException) { assertEquals("Meal observation work budget exceeded", error.message) }
    }
}
