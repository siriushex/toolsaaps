package io.aaps.copilot.domain.meal

import io.aaps.copilot.domain.predict.MealForwardForecast
import io.aaps.copilot.domain.predict.MealFutureInsulinDelivery
import io.aaps.copilot.domain.predict.MealFutureInsulinPlan
import io.aaps.copilot.domain.profile.MealAbsorptionProfile
import java.util.Collections
import kotlin.math.abs
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal data class MealConditionalTrajectory(
    val scenarioId: String,
    val startOffsetMinutes: Int,
    val reactionDelayMinutes: Int,
    val hypotheticalStartMs: Long?,
    val forecast: MealForwardForecast,
    val insulinScenarioId: String? = null
)

/** Explicit hypothetical schedule, not a calibrated controller-world probability. */
internal data class MealInsulinScenario(val id: String, val plan: MealFutureInsulinPlan) {
    init { require(id.isNotBlank()) }
}

/** Conditional means only; deliberately not a planner uncertainty envelope. */
internal class MealSimulationBatch(
    val forecastCycleId: String,
    val settingsRevision: Long,
    val beliefRevision: Long,
    val evaluatedForecastCount: Int,
    trajectories: List<MealConditionalTrajectory>,
    futureInsulinScenarios: List<MealInsulinScenario> = emptyList()
) {
    val trajectories: List<MealConditionalTrajectory> = Collections.unmodifiableList(trajectories.toList())
    val futureInsulinScenarios: List<MealInsulinScenario> = Collections.unmodifiableList(futureInsulinScenarios.toList())
}

/** Candidate times and reaction delays are interventions, not additional probability mass. */
internal class MealUncertainSimulation(
    val expansion: ExpandedMealScenarios,
    val batch: MealSimulationBatch
)

/** Requires explicit discrete cases. Never silently replaces intervals by their midpoint. */
internal class MealScenarioSimulator {
    suspend fun simulateUncertain(
        context: MealSimulationContext,
        belief: MealBelief,
        horizonMinutes: Int,
        maximumScenarios: Int = 24,
        futureInsulinScenarios: List<MealInsulinScenario>? = null
    ): MealUncertainSimulation {
        currentCoroutineContext().ensureActive()
        require(maximumScenarios in 6..24)
        val expansion = MealScenarioExpansion.expand(belief, maximumScenarios)
        val batch = simulate(context, expansion.belief, horizonMinutes, futureInsulinScenarios)
        currentCoroutineContext().ensureActive()
        return MealUncertainSimulation(expansion, batch)
    }

    suspend fun simulate(context: MealSimulationContext, belief: MealBelief, horizonMinutes: Int,
        futureInsulinScenarios: List<MealInsulinScenario>? = null): MealSimulationBatch {
        val coroutine = currentCoroutineContext()
        coroutine.ensureActive()
        val cases = belief.scenarios.sortedBy { it.id }
        val insulinWorlds: List<MealInsulinScenario?> = if (futureInsulinScenarios == null) listOf(null) else {
            require(futureInsulinScenarios.size in 1..4)
            futureInsulinScenarios.sortedBy { it.id }.also { worlds ->
                require(worlds.map { it.id }.distinct().size == worlds.size)
            }
        }
        require(horizonMinutes in 60..720 && horizonMinutes % 5 == 0)
        require(cases.size in 6..24 && cases.map { it.id }.distinct().size == cases.size)
        require(cases.size * insulinWorlds.size <= 24) { "Meal joint scenario budget exceeded" }
        require(cases.map { it.kind }.toSet() == MealHypothesisKind.entries.toSet())
        require(abs(cases.sumOf { it.probability } - 1.0) < 1e-9)
        require(belief.input.recordedAtMs <= context.capturedAtMs && belief.revision >= 0)
        require(cases.all { !it.canonicalMealId.isNullOrBlank() } && cases.map { it.canonicalMealId }.distinct().size == 1) {
            "A reconciled single canonical meal is required"
        }
        require(cases.all { it.carbs.minimumGrams == it.carbs.maximumGrams && it.absorption.size == 1 &&
            (it.inferredStart == null || it.inferredStart.earliestMs == it.inferredStart.latestMs) }) {
            "Expand uncertain intervals and profiles into explicit cases first"
        }
        require(horizonMinutes >= cases.maxOf { it.absorption.single().durationMinutes } +
            MealTimingPlanner.START_OFFSETS.last() + MealTimingPlanner.REACTION_DELAYS.last())
        // Conservative work admission before any engine execution; no evidence truncation.
        val futureInsulinCells = insulinWorlds.maxOf { it?.plan?.deliveries?.size ?: 0 }.toLong() * (horizonMinutes / 5 + 1)
        val cellsPerForecast = (context.glucose.size.toLong() + horizonMinutes / 5 + 1) * maxOf(1, context.therapy.size) + futureInsulinCells
        val maximumCalls = cases.size.toLong() * MealTimingPlanner.START_OFFSETS.size * MealTimingPlanner.REACTION_DELAYS.size * insulinWorlds.size
        require(cellsPerForecast * maximumCalls <= 5_000_000L) { "Meal scenario batch budget exceeded" }
        val canonicalId = requireNotNull(cases.first().canonicalMealId)
        val foodHorizon = maxOf(120, horizonMinutes)
        val baseline = context.announcedFoodProjection(foodHorizon)
        val anchor = requireNotNull(baseline.singleOrNull { it.canonicalMealId == canonicalId }).projection.predictionAtMs
        for (world in insulinWorlds.filterNotNull()) {
            require(world.plan.predictionAtMs == anchor)
            require(world.plan.deliveries.all { it.offsetMinutes <= horizonMinutes })
        }
        for (case in cases) {
            when (case.kind) {
                MealHypothesisKind.UPCOMING -> require(case.inferredStart?.earliestMs == anchor)
                MealHypothesisKind.NOT_HAPPENING, MealHypothesisKind.NO_NEW_MEAL ->
                    require(case.inferredStart == null && case.carbs.maximumGrams == 0.0)
                else -> require(case.inferredStart != null && case.inferredStart.latestMs <= anchor)
            }
        }
        val cache = mutableMapOf<ProjectionKey, MealForwardForecast>()
        val trajectories = ArrayList<MealConditionalTrajectory>()
        for (case in cases) for (start in MealTimingPlanner.START_OFFSETS) for (delay in MealTimingPlanner.REACTION_DELAYS) for (world in insulinWorlds) {
            coroutine.ensureActive()
            val onset = when (case.kind) {
                MealHypothesisKind.UPCOMING -> Math.addExact(anchor, (start + delay) * 60_000L)
                MealHypothesisKind.NOT_HAPPENING, MealHypothesisKind.NO_NEW_MEAL -> null
                else -> requireNotNull(case.inferredStart).earliestMs
            }
            val profile = case.absorption.single()
            val key = ProjectionKey(onset, case.carbs.maximumGrams, profile.profile, profile.durationMinutes, world?.plan?.deliveries)
            val forecast = cache.getOrPut(key) {
                val replacement = MealAbsorptionProjection.project(anchor, onset ?: anchor, key.grams,
                    key.profile, key.duration, foodHorizon)
                context.forwardForecast(canonicalId, replacement, horizonMinutes, world?.plan)
            }
            coroutine.ensureActive()
            trajectories.add(MealConditionalTrajectory(case.id, start, delay, onset, forecast, world?.id))
        }
        return MealSimulationBatch(context.forecastCycleId, context.settingsRevision, belief.revision, cache.size, trajectories,
            insulinWorlds.filterNotNull())
    }

    private data class ProjectionKey(val onset: Long?, val grams: Double, val profile: MealAbsorptionProfile, val duration: Int,
        val futureDeliveries: List<MealFutureInsulinDelivery>?)
}
