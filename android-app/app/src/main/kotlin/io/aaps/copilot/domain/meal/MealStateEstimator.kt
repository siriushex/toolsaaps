package io.aaps.copilot.domain.meal

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.ln1p

/** Pure causal update. Likelihood predictions must come from a frozen, prior forecast. */
class MealStateEstimator {
    fun initialize(input: MealInput, scenarios: List<MealScenario>): MealBelief {
        require(scenarios.size in 6..96)
        require(scenarios.map { it.id }.distinct().size == scenarios.size)
        require(scenarios.map { it.kind }.toSet() == MealHypothesisKind.entries.toSet())
        val max = scenarios.maxOf { it.probability }
        val sum = scenarios.sumOf { it.probability / max }
        val normalized = scenarios.map { it.weighted((it.probability / max) / sum) }
        return MealBelief(input, normalized, revision = 0)
    }

    fun observe(prior: MealBelief, observation: MealObservation): MealUpdate {
        fun unchanged(reason: MealUpdateReason) = MealUpdate(prior, reason)
        if (observation.sampleId == prior.lastSampleId ||
            observation.sampleAtMs <= (prior.lastSampleAtMs ?: prior.input.recordedAtMs)) {
            return unchanged(MealUpdateReason.DUPLICATE_OR_OLD)
        }
        if (observation.basedOnRevision != prior.revision || prior.revision == Long.MAX_VALUE) {
            return unchanged(MealUpdateReason.STALE_REVISION)
        }
        if (observation.predictedAtMs <= 0 || observation.predictedAtMs >= observation.sampleAtMs ||
            observation.predictedAtMs < (prior.lastSampleAtMs ?: prior.input.recordedAtMs)) {
            return unchanged(MealUpdateReason.NON_CAUSAL)
        }
        if (prior.runtimeIdentity != null && observation.runtimeIdentity != prior.runtimeIdentity) {
            return unchanged(MealUpdateReason.MODEL_CHANGED)
        }
        if (prior.lastSampleAtMs != null && observation.sampleAtMs - prior.lastSampleAtMs < 300_000L) {
            return unchanged(MealUpdateReason.CORRELATED_SAMPLE)
        }
        if (!observation.qualityTrusted || observation.sampleId.isBlank() || observation.runtimeIdentity.isBlank() ||
            !observation.glucoseMmol.isFinite() || observation.glucoseMmol <= 0 ||
            observation.expected.keys != prior.scenarios.map { it.id }.toSet() ||
            observation.expected.values.any { !it.meanMmol.isFinite() || it.meanMmol <= 0 ||
                !it.standardDeviationMmol.isFinite() || it.standardDeviationMmol <= 0 }) {
            return unchanged(MealUpdateReason.INVALID_EVIDENCE)
        }
        // Student-t innovations limit outlier leverage. Bound relative evidence per
        // observation as a second safeguard against unmodelled, correlated effects.
        val likelihoods = prior.scenarios.map { scenario ->
            val predicted = observation.expected.getValue(scenario.id)
            val residual = abs(observation.glucoseMmol - predicted.meanMmol)
            val logRatio = if (residual == 0.0) Double.NEGATIVE_INFINITY
                else 2 * (ln(residual) - ln(predicted.standardDeviationMmol)) - ln(4.0)
            val logKernel = if (logRatio > 0) logRatio + ln1p(exp(-logRatio)) else ln1p(exp(logRatio))
            -ln(predicted.standardDeviationMmol) - 2.5 * logKernel
        }
        val best = likelihoods.max()
        val logs = prior.scenarios.mapIndexed { index, scenario ->
            ln(scenario.probability) + (likelihoods[index] - best).coerceAtLeast(-2.0)
        }
        val max = logs.max()
        val weights = logs.map { exp(it - max).coerceAtLeast(1e-12) }
        val total = weights.sum()
        if (!total.isFinite()) return unchanged(MealUpdateReason.INVALID_EVIDENCE)
        val updated = prior.scenarios.mapIndexed { index, scenario -> scenario.weighted(weights[index] / total) }
        return MealUpdate(MealBelief(prior.input, updated, prior.revision + 1,
            observation.sampleAtMs, observation.sampleId, observation.runtimeIdentity), MealUpdateReason.UPDATED)
    }
}
