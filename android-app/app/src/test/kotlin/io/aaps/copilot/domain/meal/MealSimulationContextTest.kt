package io.aaps.copilot.domain.meal

import io.aaps.copilot.domain.model.GlucosePoint
import io.aaps.copilot.domain.model.TherapyEvent
import io.aaps.copilot.domain.predict.HybridPredictionEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

class MealSimulationContextTest {
    private val now = 1_700_000_000_000L
    private fun glucose() = (0..20).map { i ->
        GlucosePoint(now - (20 - i) * 300_000L, 6.0 + i * 0.02, "sensor")
    }.toMutableList()
    private fun engine() = HybridPredictionEngine(enableEnhancedPredictionV3 = true, enableUam = false)

    @Test fun capturePreservesBaselineAndInputDespiteCallerMutation() = runBlocking {
        val live = engine()
        val glucose = glucose()
        val payload = mutableMapOf("units" to "1.0")
        val events = mutableListOf(TherapyEvent(now - 1_800_000L, "bolus", payload))
        val expected = live.predict(glucose, events)
        val context = MealSimulationContext.capture(live, glucose, events, expected, "cycle-1", 7, now)
        val insulin = live.projectMealTimingInsulin(glucose, events, 60)
        payload["units"] = "20.0"
        events.clear()
        glucose.clear()
        assertEquals("1.0", context.therapy.single().payload["units"])
        assertEquals(21, context.glucose.size)
        assertEquals(expected, context.baselineForecasts)
        assertEquals(expected, context.newEngine().predict(context.glucose, context.therapy))
        assertEquals(insulin.stepEffectsMmol, context.insulinProjection(60).stepEffectsMmol)
        assertEquals("cycle-1", context.forecastCycleId)
        assertEquals(7L, context.settingsRevision)
    }

    @Test fun everyCandidateGetsIndependentEngineWithoutMutatingSource() = runBlocking {
        val live = engine()
        val points = glucose()
        val expected = live.predict(points, emptyList())
        val context = MealSimulationContext.capture(live, points, emptyList(), expected, "cycle-1", 0, now)
        val first = context.newEngine()
        val second = context.newEngine()
        assertNotSame(first, second)
        first.predict(points.map { it.copy(valueMmol = 12.0) }, emptyList())
        live.predict(points.map { it.copy(valueMmol = 3.0) }, emptyList())
        assertEquals(expected, second.predict(context.glucose, context.therapy))
        assertEquals(expected, context.newEngine().predict(context.glucose, context.therapy))
    }

    @Test fun exposedCollectionsCannotBeModified() = runBlocking {
        val live = engine()
        val points = glucose()
        val events = listOf(TherapyEvent(now, "carbs", mapOf("grams" to "15")))
        val expected = live.predict(points, events)
        val context = MealSimulationContext.capture(live, points, events, expected, "cycle-1", 0, now)
        assertThrows(UnsupportedOperationException::class.java) { (context.glucose as MutableList).clear() }
        assertThrows(UnsupportedOperationException::class.java) { (context.therapy as MutableList).clear() }
        assertThrows(UnsupportedOperationException::class.java) { (context.therapy[0].payload as MutableMap).clear() }
        assertThrows(UnsupportedOperationException::class.java) { (context.baselineForecasts as MutableList).clear() }
        Unit
    }

    @Test fun mismatchingAcceptedLocalForecastIsRejected() = runBlocking {
        val live = engine()
        val points = glucose()
        val expected = live.predict(points, emptyList())
        try {
            MealSimulationContext.capture(live, points, emptyList(), expected.map {
                it.copy(valueMmol = it.valueMmol + 1.0)
            }, "cycle-1", 0, now)
            fail("Mismatching baseline accepted")
        } catch (_: IllegalArgumentException) { }
    }

    @Test fun futureSamplesOrTherapyAreRejectedNotSilentlyDropped() = runBlocking {
        val live = engine()
        val points = glucose()
        val expected = live.predict(points, emptyList())
        for ((samples, events) in listOf(
            (points + points.last().copy(ts = now + 300_000)) to emptyList(),
            points to listOf(TherapyEvent(now + 60_000, "carbs", mapOf("grams" to "20")))
        )) {
            try {
                MealSimulationContext.capture(live, samples, events, expected, "cycle-1", 0, now)
                fail("Future evidence accepted")
            } catch (_: IllegalArgumentException) { }
        }
    }

    @Test fun invalidIdentityAndStaleCaptureAreRejected() = runBlocking {
        val live = engine()
        val points = glucose()
        val expected = live.predict(points, emptyList())
        for ((id, revision, capturedAt) in listOf(Triple("", 0L, now), Triple("cycle", -1L, now),
            Triple("cycle", 0L, now + 300_001L))) {
            try {
                MealSimulationContext.capture(live, points, emptyList(), expected, id, revision, capturedAt)
                fail("Invalid capture accepted")
            } catch (_: IllegalArgumentException) { }
        }
    }

    @Test fun cancellationDoesNotCaptureOrMutateLiveState() = runBlocking {
        val live = engine()
        val points = glucose()
        val expected = live.predict(points, emptyList())
        val diagnostics = live.diagnosticsSnapshot()
        try {
            withContext(Job().apply { cancel() }) {
                MealSimulationContext.capture(live, points, emptyList(), expected, "cycle", 0, now)
            }
            fail("Cancellation ignored")
        } catch (_: CancellationException) { }
        assertSame(diagnostics, live.diagnosticsSnapshot())
    }
}
