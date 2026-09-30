package io.aaps.copilot.domain.meal

import io.aaps.copilot.domain.model.*
import io.aaps.copilot.domain.predict.HybridPredictionEngine
import io.aaps.copilot.domain.predict.MealFutureInsulinDelivery
import io.aaps.copilot.domain.predict.MealFutureInsulinPlan
import io.aaps.copilot.domain.profile.MealAbsorptionProfile
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class MealScenarioSimulatorTest {
    private val now = 1_700_000_000_000L
    private val glucose = (0..20).map { GlucosePoint(now - (20 - it) * 300_000L, 6.0, "sensor") }
    private val events = listOf(TherapyEvent(now, "carbs", mapOf("carbs" to "20"),
        componentTrust = TherapyEventComponentTrust(12, "r1")))
    private suspend fun context(extra: List<TherapyEvent> = emptyList()): MealSimulationContext {
        val engine = HybridPredictionEngine(enableEnhancedPredictionV3 = true, enableUam = false)
        return MealSimulationContext.capture(engine, glucose, events + extra, engine.predict(glucose, events + extra), "cycle-1", 7, now)
    }
    private fun scenarios() = MealHypothesisKind.entries.map { kind ->
        val noFood = kind in setOf(MealHypothesisKind.NOT_HAPPENING, MealHypothesisKind.NO_NEW_MEAL)
        val start = when (kind) {
            MealHypothesisKind.UPCOMING -> now
            MealHypothesisKind.JUST_STARTED -> now - 300_000
            MealHypothesisKind.STARTED_EARLIER -> now - 1_800_000
            else -> now - 3_600_000
        }
        MealScenario(kind.name, kind, if (noFood) null else MealStartInterval(start, start),
            MealCarbRange(if (noFood) 0.0 else 20.0, if (noFood) 0.0 else 20.0),
            listOf(MealAbsorptionAlternative(MealAbsorptionProfile.FAST, 60, 1.0)), 1.0 / 6, "12")
    }
    private fun belief(cases: List<MealScenario> = scenarios()) =
        MealBelief(MealInput("input-1", now, MealCarbRange(20.0, 20.0)), cases, 3)

    @Test fun completeMatrixCachesEquivalentCasesAndKeepsSourceHistory() = runBlocking {
        val context = context()
        val result = MealScenarioSimulator().simulate(context, belief(), 120)
        assertEquals(144, result.trajectories.size)
        assertTrue(result.evaluatedForecastCount < 30)
        assertEquals("cycle-1", result.forecastCycleId)
        assertEquals(7L, result.settingsRevision)
        assertEquals(3L, result.beliefRevision)
        assertEquals(now, context.therapy.single().ts)
        assertEquals("20", context.therapy.single().payload["carbs"])
        assertTrue(result.trajectories.all { !it.forecast.futureControlSimulated && !it.forecast.trajectoryUncertaintyValidated })
        assertThrows(UnsupportedOperationException::class.java) { (result.trajectories as MutableList).clear() }
        Unit
    }

    @Test fun delayChangesUpcomingFoodButCannotMovePastOrCancelledFood() = runBlocking {
        val result = MealScenarioSimulator().simulate(context(), belief(), 120)
        for (kind in MealHypothesisKind.entries.filter { it != MealHypothesisKind.UPCOMING }) {
            val paths = result.trajectories.filter { it.scenarioId == kind.name }
            assertEquals(1, paths.map { it.forecast.glucoseMmol }.distinct().size)
        }
        val future = result.trajectories.filter { it.scenarioId == "UPCOMING" }
        assertTrue(future.map { it.forecast.glucoseMmol }.distinct().size > 1)
        val absent = result.trajectories.first { it.scenarioId == "NOT_HAPPENING" }
        assertNull(absent.hypotheticalStartMs)
    }

    @Test fun orderCannotChangePredictions() = runBlocking {
        val context = context()
        val simulator = MealScenarioSimulator()
        val first = simulator.simulate(context, belief(), 120)
        val second = simulator.simulate(context, belief(scenarios().reversed()), 120)
        assertEquals(first.trajectories.map { it.forecast.glucoseMmol }, second.trajectories.map { it.forecast.glucoseMmol })
    }

    @Test fun unresolvedRangesAndIncompleteHypothesesAreRejected() = runBlocking {
        val context = context()
        for (cases in listOf(scenarios().drop(1), scenarios().map {
            if (it.kind != MealHypothesisKind.UPCOMING) it else MealScenario(it.id, it.kind,
                MealStartInterval(now, now + 300_000), it.carbs, it.absorption, it.probability, it.canonicalMealId)
        })) {
            try { MealScenarioSimulator().simulate(context, belief(cases), 120); fail("Unresolved scenario accepted") }
            catch (_: IllegalArgumentException) { }
        }
    }

    @Test fun cancellationNeverReturnsAPartialMatrix() = runBlocking {
        val context = context()
        val job = Job().apply { cancel() }
        try { withContext(job) { MealScenarioSimulator().simulate(context, belief(), 120) }; fail("Ignored cancellation") }
        catch (_: CancellationException) { }
    }

    @Test fun ambiguousCanonicalLinksAndUnsupportedTailAreRejected() = runBlocking {
        val context = context()
        val unknown = scenarios().map { MealScenario(it.id, it.kind, it.inferredStart, it.carbs,
            it.absorption, it.probability, "missing") }
        for ((cases, horizon) in listOf(unknown to 120, scenarios() to 60)) {
            try { MealScenarioSimulator().simulate(context, belief(cases), horizon); fail("Invalid request accepted") }
            catch (_: IllegalArgumentException) { }
        }
    }

    @Test fun largeEvidenceBudgetRejectsInsteadOfTruncating() = runBlocking {
        val context = context(List(1_000) { TherapyEvent(now - it, "note", emptyMap()) })
        try { MealScenarioSimulator().simulate(context, belief(), 120); fail("Budget ignored") }
        catch (error: IllegalArgumentException) { assertEquals("Meal scenario batch budget exceeded", error.message) }
    }

    @Test fun futureInputCannotUseAnOlderContext() = runBlocking {
        val context = context()
        val future = MealBelief(MealInput("input-1", now + 1, MealCarbRange(20.0, 20.0)), scenarios(), 3)
        try { MealScenarioSimulator().simulate(context, future, 120); fail("Future input accepted") }
        catch (_: IllegalArgumentException) { }
    }

    private fun insulinCases() = listOf(
        MealInsulinScenario("none", MealFutureInsulinPlan(now, emptyList())),
        MealInsulinScenario("pulse", MealFutureInsulinPlan(now, listOf(MealFutureInsulinDelivery(15, 0.5))))
    )

    @Test fun explicitInsulinWorldsStaySeparateInEveryMealCandidate() = runBlocking {
        val context = context()
        val worlds = insulinCases()
        val result = MealScenarioSimulator().simulate(context, belief(), 120, futureInsulinScenarios = worlds)
        assertEquals(288, result.trajectories.size)
        assertEquals(worlds, result.futureInsulinScenarios)
        val absent = result.trajectories.filter { it.scenarioId == "NOT_HAPPENING" }
        val none = absent.first { it.insulinScenarioId == "none" }
        val pulse = absent.first { it.insulinScenarioId == "pulse" }
        assertTrue(pulse.forecast.glucoseMmol.last() < none.forecast.glucoseMmol.last())
        assertEquals(0.5, pulse.forecast.hypotheticalFutureInsulinUnits, 0.0)
        assertTrue(result.trajectories.all { !it.forecast.futureControlSimulated && !it.forecast.trajectoryUncertaintyValidated })
        for (case in belief().scenarios) for (start in MealTimingPlanner.START_OFFSETS) for (delay in MealTimingPlanner.REACTION_DELAYS) {
            assertEquals(setOf("none", "pulse"), result.trajectories.filter {
                it.scenarioId == case.id && it.startOffsetMinutes == start && it.reactionDelayMinutes == delay
            }.map { it.insulinScenarioId }.toSet())
        }
        val food = MealAbsorptionProjection.project(now, now, 0.0, MealAbsorptionProfile.FAST, 60, 120)
        assertEquals(context.forwardForecast("12", food, 120, worlds.last().plan).glucoseMmol, pulse.forecast.glucoseMmol)
        assertEquals(now, context.therapy.single().ts)
        assertThrows(UnsupportedOperationException::class.java) { (result.futureInsulinScenarios as MutableList).clear() }
        Unit
    }

    @Test fun identicalInsulinPlansShareWorkWithoutLosingWorldIdentity() = runBlocking {
        val context = context()
        val simulator = MealScenarioSimulator()
        val world = insulinCases().last()
        val single = simulator.simulate(context, belief(), 120, listOf(world))
        val duplicate = simulator.simulate(context, belief(), 120, listOf(world, world.copy(id = "same-delivery")))
        assertEquals(single.evaluatedForecastCount, duplicate.evaluatedForecastCount)
        assertEquals(single.trajectories.size * 2, duplicate.trajectories.size)
    }

    @Test fun insulinWorldOrderingCannotChangeOutputOrAssumePumpPause() = runBlocking {
        val context = context()
        val simulator = MealScenarioSimulator()
        val baseline = simulator.simulate(context, belief(), 120)
        assertTrue(baseline.futureInsulinScenarios.isEmpty())
        assertTrue(baseline.trajectories.all { it.insulinScenarioId == null })
        val first = simulator.simulate(context, belief(), 120, insulinCases())
        val reverse = simulator.simulate(context, belief(), 120, insulinCases().reversed())
        assertEquals(first.trajectories, reverse.trajectories.mapIndexed { index, row ->
            assertEquals(first.trajectories[index].forecast.glucoseMmol, row.forecast.glucoseMmol)
            row.copy(forecast = first.trajectories[index].forecast)
        })
        assertEquals(baseline.trajectories.map { it.forecast.glucoseMmol },
            first.trajectories.filter { it.insulinScenarioId == "none" }.map { it.forecast.glucoseMmol })
    }

    @Test fun malformedInsulinWorldsRejectWithoutPartialMatrix() = runBlocking {
        val context = context()
        val world = insulinCases().last()
        val requests = listOf(emptyList(), listOf(world, world),
            List(5) { world.copy(id = "world-$it") },
            listOf(world.copy(plan = MealFutureInsulinPlan(now + 1, world.plan.deliveries))),
            listOf(world.copy(plan = MealFutureInsulinPlan(now, listOf(MealFutureInsulinDelivery(121, 0.1))))))
        for (worlds in requests) {
            try { MealScenarioSimulator().simulate(context, belief(), 120, worlds); fail("Invalid worlds accepted") }
            catch (_: IllegalArgumentException) { }
        }
    }

    @Test fun uncertaintyExpansionAndWorldsRetainTheOriginalBelief() = runBlocking {
        val context = context()
        val source = belief()
        val result = MealScenarioSimulator().simulateUncertain(context, source, 120, futureInsulinScenarios = insulinCases())
        assertSame(source.input, result.expansion.belief.input)
        assertEquals(source.stageProbabilities, result.expansion.belief.stageProbabilities)
        assertEquals(288, result.batch.trajectories.size)
    }

    @Test fun jointWorldBudgetRejectsRatherThanDroppingMealHypotheses() = runBlocking {
        val context = context()
        val cases = scenarios().flatMap { case -> (0..1).map { index ->
            MealScenario("${case.id}-$index", case.kind, case.inferredStart, case.carbs,
                case.absorption, case.probability / 2.0, case.canonicalMealId)
        } }
        val worlds = insulinCases() + insulinCases().last().copy(id = "third-world")
        try { MealScenarioSimulator().simulate(context, belief(cases), 120, worlds); fail("Dropped scenarios") }
        catch (error: IllegalArgumentException) { assertEquals("Meal joint scenario budget exceeded", error.message) }
    }

    @Test fun futureInsulinWorkIsIncludedInTheCpuAdmissionBudget() = runBlocking {
        val context = context(List(130) { TherapyEvent(now - it, "note", emptyMap()) })
        val plan = MealFutureInsulinPlan(now, (0..120).map { MealFutureInsulinDelivery(it, 0.001) })
        val worlds = List(4) { MealInsulinScenario("world-$it", plan) }
        try { MealScenarioSimulator().simulate(context, belief(), 120, worlds); fail("Work budget ignored") }
        catch (error: IllegalArgumentException) { assertEquals("Meal scenario batch budget exceeded", error.message) }
    }
}
