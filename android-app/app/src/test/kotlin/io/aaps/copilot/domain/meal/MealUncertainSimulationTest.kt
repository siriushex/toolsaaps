package io.aaps.copilot.domain.meal

import io.aaps.copilot.domain.model.*
import io.aaps.copilot.domain.predict.HybridPredictionEngine
import io.aaps.copilot.domain.profile.MealAbsorptionProfile
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class MealUncertainSimulationTest {
    private val now = 1_700_000_000_000L
    private suspend fun context(): MealSimulationContext {
        val glucose = (0..20).map { GlucosePoint(now - (20 - it) * 300_000L, 6.0, "sensor") }
        val therapy = listOf(TherapyEvent(now, "carbs", mapOf("carbs" to "20"),
            componentTrust = TherapyEventComponentTrust(12, "r1")))
        val engine = HybridPredictionEngine(enableEnhancedPredictionV3 = true, enableUam = false)
        return MealSimulationContext.capture(engine, glucose, therapy, engine.predict(glucose, therapy), "cycle-1", 7, now)
    }

    private fun belief(futureRange: Boolean = false): MealBelief {
        val cases = MealHypothesisKind.entries.map { kind ->
            val absent = kind in setOf(MealHypothesisKind.NOT_HAPPENING, MealHypothesisKind.NO_NEW_MEAL)
            val wide = kind == MealHypothesisKind.STARTED_EARLIER
            val onset = when {
                absent -> null
                kind == MealHypothesisKind.UPCOMING -> MealStartInterval(now, now + if (futureRange) 300_000 else 0)
                wide -> MealStartInterval(now - 1_800_000, now - 600_000)
                else -> MealStartInterval(now - 300_000, now - 300_000)
            }
            MealScenario(kind.name, kind, onset,
                if (absent) MealCarbRange(0.0, 0.0) else MealCarbRange(10.0, if (wide) 30.0 else 10.0),
                if (wide) listOf(MealAbsorptionAlternative(MealAbsorptionProfile.FAST, 60, 0.75),
                    MealAbsorptionAlternative(MealAbsorptionProfile.MIXED, 120, 0.25))
                else listOf(MealAbsorptionAlternative(MealAbsorptionProfile.FAST, 60, 1.0)), 1.0 / 6, "12")
        }
        return MealBelief(MealInput("input", now, MealCarbRange(10.0, 30.0)), cases, 9)
    }

    @Test fun completeGridRetainsBeliefWeightsAndMatchesDirectSimulation() = runBlocking {
        val context = context()
        val source = belief()
        val simulator = MealScenarioSimulator()
        val result = simulator.simulateUncertain(context, source, 180)
        val direct = simulator.simulate(context, result.expansion.belief, 180)
        assertEquals(13, result.expansion.belief.scenarios.size)
        assertEquals(13 * 6 * 4, result.batch.trajectories.size)
        assertEquals(direct.trajectories.map { it.forecast.glucoseMmol }, result.batch.trajectories.map { it.forecast.glucoseMmol })
        assertSame(source.input, result.expansion.belief.input)
        assertEquals(9L, result.batch.beliefRevision)
        val ids = result.expansion.belief.scenarios.map { it.id }.toSet()
        assertEquals(ids, result.batch.trajectories.map { it.scenarioId }.toSet())
        for (parent in source.scenarios) {
            assertEquals(parent.probability, result.expansion.belief.scenarios.filter {
                result.expansion.parentScenarioIds.getValue(it.id) == parent.id
            }.sumOf { it.probability }, 1e-12)
        }
        assertTrue(result.batch.trajectories.all { !it.forecast.futureControlSimulated && !it.forecast.trajectoryUncertaintyValidated })
        assertEquals(now, context.therapy.single().ts)
        assertThrows(UnsupportedOperationException::class.java) { (result.expansion.parentScenarioIds as MutableMap).clear() }
        assertThrows(UnsupportedOperationException::class.java) { (result.batch.trajectories as MutableList).clear() }
        Unit
    }

    @Test fun pastRangesRemainPastAcrossAllStartCandidates() = runBlocking {
        val result = MealScenarioSimulator().simulateUncertain(context(), belief(), 180)
        val cases = result.expansion.belief.scenarios.filter { it.kind == MealHypothesisKind.STARTED_EARLIER }
        assertEquals(8, cases.size)
        for (case in cases) {
            val paths = result.batch.trajectories.filter { it.scenarioId == case.id }
            assertEquals(setOf(case.inferredStart!!.earliestMs), paths.map { it.hypotheticalStartMs }.toSet())
            assertEquals(1, paths.map { it.forecast.glucoseMmol }.distinct().size)
        }
        val outcomes = cases.map { case -> result.batch.trajectories.first { it.scenarioId == case.id }.forecast.glucoseMmol }
        assertTrue("Different food cases collapsed to one forecast", outcomes.distinct().size > 1)
    }

    @Test fun uncertainUpcomingOnsetIsNotSilentlyReplacedByCandidateTime() = runBlocking {
        try { MealScenarioSimulator().simulateUncertain(context(), belief(true), 180); fail("Ambiguous intervention accepted") }
        catch (_: IllegalArgumentException) { }
    }

    @Test fun expansionBudgetIsCheckedBeforeSimulation() = runBlocking {
        try { MealScenarioSimulator().simulateUncertain(context(), belief(), 180, maximumScenarios = 12); fail("Truncated cases") }
        catch (error: IllegalArgumentException) { assertEquals("Meal scenario expansion budget exceeded", error.message) }
    }

    @Test fun cancellationPrecedesExpansionValidation() = runBlocking {
        val context = context()
        var cancelledAtEntry = false
        launch(start = CoroutineStart.UNDISPATCHED) {
            currentCoroutineContext().cancel()
            try {
                MealScenarioSimulator().simulateUncertain(context, belief(), 180, maximumScenarios = 0)
                fail("Ignored cancellation")
            } catch (_: CancellationException) { cancelledAtEntry = true }
        }.join()
        assertTrue(cancelledAtEntry)
    }

    @Test fun callerCannotRaiseTheSimulatorBudgetOrShortenProfileTail() = runBlocking {
        val context = context()
        for ((maximum, horizon) in listOf(25 to 180, 24 to 120)) {
            try { MealScenarioSimulator().simulateUncertain(context, belief(), horizon, maximum); fail("Invalid request accepted") }
            catch (_: IllegalArgumentException) { }
        }
    }
}
