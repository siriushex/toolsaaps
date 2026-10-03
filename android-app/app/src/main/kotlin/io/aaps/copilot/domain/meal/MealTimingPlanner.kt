package io.aaps.copilot.domain.meal

import kotlin.math.abs
import kotlin.math.max

data class MealTimingPoint(val offsetMinutes: Int, val meanMmol: Double, val lowMmol: Double, val highMmol: Double)
data class MealTimingTrajectory(
    val scenarioId: String,
    val startOffsetMinutes: Int,
    val reactionDelayMinutes: Int,
    val points: List<MealTimingPoint>,
    val insulinScenarioId: String? = null
)
enum class MealUncertaintyScope { POINTWISE, SIMULTANEOUS_TRAJECTORY }

/**
 * Provenance supplied by the scenario producer, not a calibration certificate.
 * Support must cover every five-minute point, including the delayed meal tail.
 * Existing single-horizon forecast calibration cannot populate trajectory support.
 * Even a supported research envelope cannot enable notifications.
 */
data class MealTimingUncertainty(
    val modelIdentity: String,
    val runtimeIdentity: String,
    val generatedAtMs: Long,
    val calibrationAvailableAtMs: Long,
    val supportedThroughMinutes: Int,
    val scope: MealUncertaintyScope
)

data class MealTimingEnvelope(
    val generatedAtMs: Long,
    val beliefRevision: Long,
    val runtimeIdentity: String,
    val currentGlucoseMmol: Double,
    val targetMmol: Double,
    val requiredHorizonMinutes: Int,
    val qualityTrusted: Boolean,
    val deliveryReliable: Boolean,
    val futureInsulinSupported: Boolean,
    val trajectories: List<MealTimingTrajectory>,
    val uncertainty: MealTimingUncertainty? = null,
    val insulinScenarioIds: List<String>? = null
)

enum class MealTimingStatus { SHADOW_READY, OBSERVE, ABSTAIN, LATE_ENTRY }
enum class MealTimingReason {
    SHADOW_ONLY, LATE_ENTRY, STAGE_UNCERTAIN, NOT_NOW, INVALID_CONTEXT,
    INCOMPLETE_SCENARIOS, INCOMPLETE_HORIZON, UNSAFE_TRAJECTORY, CONFLICTING_SCENARIOS, BUDGET_EXCEEDED,
    NO_FOOD_URGENCY, UNCERTAINTY_UNSUPPORTED
}
data class MealNoFoodRisk(val firstLowOffsetMinutes: Int?)
data class MealTimingDecision(
    val status: MealTimingStatus,
    val reason: MealTimingReason,
    val candidateOffsetMinutes: Int? = null,
    val noFoodRisk: MealNoFoodRisk? = null
) {
    // No notification may consume a research decision before clinical validation.
    val notificationAllowed: Boolean get() = false
}

/** Research policy only. These thresholds are not a clinically validated safety claim. */
class MealTimingPlanner {
    fun evaluate(belief: MealBelief, envelope: MealTimingEnvelope, nowMs: Long): MealTimingDecision {
        fun abstain(reason: MealTimingReason) = MealTimingDecision(MealTimingStatus.ABSTAIN, reason)
        if (nowMs <= 0 || envelope.generatedAtMs <= 0 || envelope.generatedAtMs > nowMs ||
            nowMs - envelope.generatedAtMs > 300_000L || envelope.beliefRevision != belief.revision ||
            envelope.runtimeIdentity.isBlank() ||
            (belief.runtimeIdentity != null && belief.runtimeIdentity != envelope.runtimeIdentity) ||
            !envelope.currentGlucoseMmol.isFinite() || envelope.currentGlucoseMmol < 4.0 ||
            !envelope.targetMmol.isFinite() || envelope.targetMmol < 4.0 ||
            !envelope.qualityTrusted || !envelope.deliveryReliable || !envelope.futureInsulinSupported ||
            envelope.requiredHorizonMinutes !in 60..720 || envelope.requiredHorizonMinutes % 5 != 0) {
            return abstain(MealTimingReason.INVALID_CONTEXT)
        }
        if (belief.scenarios.size !in 6..96 || belief.revision < 0 ||
            belief.input.recordedAtMs > envelope.generatedAtMs ||
            belief.scenarios.map { it.id }.distinct().size != belief.scenarios.size ||
            belief.scenarios.map { it.kind }.toSet() != MealHypothesisKind.entries.toSet() ||
            abs(belief.scenarios.sumOf { it.probability } - 1.0) > 1e-9) {
            return abstain(MealTimingReason.INVALID_CONTEXT)
        }
        val scenarios = belief.scenarios.associateBy { it.id }
        val manifest = envelope.insulinScenarioIds
        if (manifest != null && (manifest.size !in 1..4 || manifest.any { it.isBlank() } ||
                manifest.distinct().size != manifest.size)) {
            return abstain(MealTimingReason.INCOMPLETE_SCENARIOS)
        }
        val insulinWorlds: List<String?> = manifest ?: listOf(null)
        if (scenarios.size * insulinWorlds.size * REACTION_DELAYS.size > 96) {
            return abstain(MealTimingReason.BUDGET_EXCEEDED)
        }
        val paths = envelope.trajectories
        val keys = paths.map { PathKey(it.scenarioId, it.startOffsetMinutes, it.reactionDelayMinutes, it.insulinScenarioId) }
        if (paths.size != scenarios.size * insulinWorlds.size * START_OFFSETS.size * REACTION_DELAYS.size ||
            keys.distinct().size != paths.size || paths.any {
                it.scenarioId !in scenarios || it.startOffsetMinutes !in START_OFFSETS ||
                    it.reactionDelayMinutes !in REACTION_DELAYS || it.insulinScenarioId !in insulinWorlds
            }) return abstain(MealTimingReason.INCOMPLETE_SCENARIOS)

        val mealTail = scenarios.values.maxOf { s -> s.absorption.maxOf { it.durationMinutes } } +
            START_OFFSETS.last() + REACTION_DELAYS.last()
        val horizon = max(envelope.requiredHorizonMinutes, mealTail)
        if (paths.any { t -> t.points.size !in (horizon / 5 + 1)..145 ||
                t.points.indices.any { t.points[it].offsetMinutes != it * 5 } }) {
            return abstain(MealTimingReason.INCOMPLETE_HORIZON)
        }
        if (paths.any { t -> abs(t.points.first().meanMmol - envelope.currentGlucoseMmol) > 1e-6 ||
                t.points.any { !it.meanMmol.isFinite() || !it.lowMmol.isFinite() || !it.highMmol.isFinite() ||
                    it.lowMmol > it.meanMmol || it.meanMmol > it.highMmol } }) {
            return abstain(MealTimingReason.INVALID_CONTEXT)
        }
        val uncertainty = envelope.uncertainty
        if (uncertainty == null || uncertainty.modelIdentity.isBlank() ||
            uncertainty.runtimeIdentity != envelope.runtimeIdentity ||
            uncertainty.generatedAtMs != envelope.generatedAtMs ||
            uncertainty.calibrationAvailableAtMs !in 1L..envelope.generatedAtMs ||
            uncertainty.scope != MealUncertaintyScope.SIMULTANEOUS_TRAJECTORY ||
            uncertainty.supportedThroughMinutes !in horizon..720 ||
            uncertainty.supportedThroughMinutes % 5 != 0) {
            return abstain(MealTimingReason.UNCERTAINTY_UNSUPPORTED)
        }
        val noFoodLow = paths.asSequence()
            .filter { scenarios.getValue(it.scenarioId).kind == MealHypothesisKind.NOT_HAPPENING }
            .flatMap { it.points.asSequence().take(horizon / 5 + 1) }
            .filter { it.lowMmol < 4.0 }.minOfOrNull { it.offsetMinutes }
        val noFoodRisk = MealNoFoodRisk(noFoodLow)
        fun assessed(status: MealTimingStatus, reason: MealTimingReason, start: Int? = null) =
            MealTimingDecision(status, reason, start, noFoodRisk)
        // Imminent risk without food belongs to the hypo pathway, not a routine
        // invitation whose delivery and user response are not guaranteed.
        if (noFoodLow != null && noFoodLow <= REACTION_DELAYS.last()) {
            return assessed(MealTimingStatus.ABSTAIN, MealTimingReason.NO_FOOD_URGENCY)
        }
        val eatingPaths = paths.filter {
            scenarios.getValue(it.scenarioId).kind != MealHypothesisKind.NOT_HAPPENING
        }
        // Conditional eating safety includes low-weight and already-started worlds.
        // A distant no-food low is reported separately, not averaged or suppressed.
        val feasible = START_OFFSETS.filter { start ->
            (noFoodLow == null || noFoodLow > start + REACTION_DELAYS.last()) &&
                eatingPaths.filter { it.startOffsetMinutes == start }.all { t ->
                    t.points.take(horizon / 5 + 1).all { it.lowMmol >= 4.0 }
                }
        }.toSet()
        if (feasible.isEmpty()) return assessed(MealTimingStatus.ABSTAIN, MealTimingReason.UNSAFE_TRAJECTORY)
        fun cost(t: MealTimingTrajectory): Double = t.points.take(horizon / 5 + 1).map { p ->
            val deviation = (p.meanMmol - envelope.targetMmol).coerceIn(-100.0, 100.0)
            val high = (p.highMmol - 9.0).coerceIn(0.0, 100.0)
            deviation * deviation + high * high
        }.average()

        val costs = paths.associate {
            PathKey(it.scenarioId, it.startOffsetMinutes, it.reactionDelayMinutes, it.insulinScenarioId) to cost(it)
        }
        var common = feasible
        // Numerical subdivision must not turn a plausible stage into many ignored
        // low-weight cases. Retain every boundary of a plausible stage.
        for (scenario in scenarios.values.filter {
            belief.stageProbabilities.getValue(it.kind) >= 0.05 && it.kind != MealHypothesisKind.NOT_HAPPENING
        }) {
            for (insulinWorld in insulinWorlds) {
                for (delay in REACTION_DELAYS) {
                    val best = feasible.minOf { costs.getValue(PathKey(scenario.id, it, delay, insulinWorld)) }
                    val acceptable = feasible.filter {
                        costs.getValue(PathKey(scenario.id, it, delay, insulinWorld)) <= best + 0.1
                    }.toSet()
                    common = common.intersect(acceptable)
                }
            }
        }
        if (common.isEmpty()) return assessed(MealTimingStatus.ABSTAIN, MealTimingReason.CONFLICTING_SCENARIOS)
        val late = belief.stageProbabilities.getValue(MealHypothesisKind.STARTED_EARLIER) +
            belief.stageProbabilities.getValue(MealHypothesisKind.PREVIOUS_MEAL)
        if (late >= 0.5) return assessed(MealTimingStatus.LATE_ENTRY, MealTimingReason.LATE_ENTRY)
        if (belief.stageProbabilities.getValue(MealHypothesisKind.UPCOMING) < 0.8) {
            return assessed(MealTimingStatus.OBSERVE, MealTimingReason.STAGE_UNCERTAIN)
        }
        // All remaining starts are near-optimal in every plausible world. Do not
        // recommend waiting for an immaterial improvement in the weighted mean.
        val bestStart = common.min()
        return if (bestStart == 0) assessed(MealTimingStatus.SHADOW_READY, MealTimingReason.SHADOW_ONLY, 0)
        else assessed(MealTimingStatus.OBSERVE, MealTimingReason.NOT_NOW, bestStart)
    }

    private data class PathKey(val scenarioId: String, val start: Int, val delay: Int, val insulinId: String?)

    companion object {
        val START_OFFSETS: List<Int> = java.util.Collections.unmodifiableList(listOf(0, 5, 10, 15, 20, 30))
        val REACTION_DELAYS: List<Int> = java.util.Collections.unmodifiableList(listOf(0, 5, 10, 20))
    }
}
