package io.aaps.copilot.domain.meal

import io.aaps.copilot.domain.model.GlucosePoint
import io.aaps.copilot.domain.profile.MealAbsorptionProfile
import java.util.Collections
import kotlin.math.abs
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Explicit estimator error scales; no default or conversion of a glucose CI to likelihood noise. */
internal class MealObservationErrorModel(
    val id: String,
    val runtimeIdentity: String,
    val availableAtMs: Long,
    standardDeviationsMmol: Map<String, Double>
) {
    val standardDeviationsMmol: Map<String, Double> = Collections.unmodifiableMap(LinkedHashMap(standardDeviationsMmol))
    init {
        require(id.isNotBlank() && id.length <= 256 && runtimeIdentity.isNotBlank() && runtimeIdentity.length <= 512)
        require(availableAtMs > 0 && this.standardDeviationsMmol.size in 6..96)
        require(this.standardDeviationsMmol.all { (id, scale) ->
            id.isNotBlank() && id.length <= 512 && scale.isFinite() && scale > 0.0
        })
    }
}

/**
 * Passive one-step conditional evidence, not a meal-timing intervention or safety envelope.
 * Caller authenticates the captured runtime and error model; metadata alone is not attestation.
 */
internal class MealObservationForecast private constructor(
    val inputId: String,
    val storageRevision: Long,
    val beliefRevision: Long,
    val forecastCycleId: String,
    val settingsRevision: Long,
    val runtimeIdentity: String,
    val errorModelId: String,
    val preparedAtMs: Long,
    val sampleAtMs: Long,
    val evaluatedForecastCount: Int,
    expected: Map<String, MealExpectedObservation>
) {
    val expected: Map<String, MealExpectedObservation> = Collections.unmodifiableMap(LinkedHashMap(expected))

    /** Only the exact next grid sample can consume these predictions; no rounding or delayed replay. */
    fun observation(
        sampleId: String,
        sample: GlucosePoint,
        currentRuntimeIdentity: String,
        currentBeliefRevision: Long,
        qualityTrusted: Boolean,
        receivedAtMs: Long
    ): MealObservation? {
        if (sample.ts != sampleAtMs || receivedAtMs < sample.ts || receivedAtMs - sample.ts > 300_000L ||
            sampleId.isBlank() || sampleId.length > 512 || !sample.valueMmol.isFinite() || sample.valueMmol <= 0.0 ||
            !qualityTrusted || currentRuntimeIdentity != runtimeIdentity || currentBeliefRevision != beliefRevision) return null
        return MealObservation(sampleId, sample.ts, sample.valueMmol, preparedAtMs,
            runtimeIdentity, beliefRevision, expected, true)
    }

    companion object {
        suspend fun prepare(
            context: MealSimulationContext,
            belief: MealBelief,
            storageRevision: Long,
            runtimeIdentity: String,
            errorModel: MealObservationErrorModel,
            clock: () -> Long
        ): MealObservationForecast {
            val coroutine = currentCoroutineContext()
            coroutine.ensureActive()
            val startedAt = clock()
            val anchor = context.glucose.last().ts
            val sampleAt = Math.addExact(anchor, 300_000L)
            require(startedAt >= context.capturedAtMs && startedAt < sampleAt)
            require(storageRevision >= 0 && belief.revision >= 0 && belief.revision < Long.MAX_VALUE)
            require(belief.input.recordedAtMs <= anchor && (belief.lastSampleAtMs ?: 0) <= anchor)
            require(runtimeIdentity.isNotBlank() && runtimeIdentity.length <= 512)
            require(belief.runtimeIdentity == null || belief.runtimeIdentity == runtimeIdentity)
            require(errorModel.runtimeIdentity == runtimeIdentity && errorModel.availableAtMs <= anchor)
            val cases = belief.scenarios.sortedBy { it.id }
            require(cases.size in 6..24 && cases.map { it.id }.distinct().size == cases.size)
            require(cases.map { it.kind }.toSet() == MealHypothesisKind.entries.toSet())
            require(abs(cases.sumOf { it.probability } - 1.0) < 1e-9)
            require(errorModel.standardDeviationsMmol.keys == cases.map { it.id }.toSet())
            require(cases.all { !it.canonicalMealId.isNullOrBlank() } && cases.map { it.canonicalMealId }.distinct().size == 1)
            // A distribution must be expanded explicitly, never replaced by its midpoint.
            require(cases.all { it.carbs.minimumGrams == it.carbs.maximumGrams && it.absorption.size == 1 &&
                (it.inferredStart == null || it.inferredStart.earliestMs == it.inferredStart.latestMs) })
            for (case in cases) {
                when (case.kind) {
                    MealHypothesisKind.UPCOMING -> require(case.inferredStart != null && case.inferredStart.earliestMs >= anchor)
                    MealHypothesisKind.NOT_HAPPENING, MealHypothesisKind.NO_NEW_MEAL ->
                        require(case.inferredStart == null && case.carbs.maximumGrams == 0.0)
                    else -> require(case.inferredStart != null && case.inferredStart.latestMs <= anchor)
                }
            }
            val cellsPerCall = (context.glucose.size.toLong() + 25) * maxOf(1, context.therapy.size)
            require(cellsPerCall * cases.size <= 5_000_000L) { "Meal observation work budget exceeded" }
            val canonicalId = requireNotNull(cases.first().canonicalMealId)
            val cache = mutableMapOf<ProjectionKey, Double>()
            val expected = LinkedHashMap<String, MealExpectedObservation>()
            for (case in cases) {
                coroutine.ensureActive()
                val profile = case.absorption.single()
                val key = ProjectionKey(case.inferredStart?.earliestMs, case.carbs.maximumGrams,
                    profile.profile, profile.durationMinutes)
                val mean = cache.getOrPut(key) {
                    val food = MealAbsorptionProjection.project(anchor, key.startMs ?: anchor,
                        key.grams, key.profile, key.durationMinutes, 120)
                    val forecast = context.forwardForecast(canonicalId, food)
                    require(forecast.predictionAtMs == anchor && !forecast.numericLimitsReached) {
                        "Clipped or mismatched observation forecast"
                    }
                    forecast.glucoseMmol[1].also { require(it.isFinite() && it > 0.0) }
                }
                expected[case.id] = MealExpectedObservation(mean, errorModel.standardDeviationsMmol.getValue(case.id))
            }
            coroutine.ensureActive()
            val completedAt = clock()
            require(completedAt >= startedAt && completedAt < sampleAt) { "Observation forecast missed its causal deadline" }
            return MealObservationForecast(belief.input.id, storageRevision, belief.revision,
                context.forecastCycleId, context.settingsRevision, runtimeIdentity, errorModel.id,
                completedAt, sampleAt, cache.size, expected)
        }
    }

    private data class ProjectionKey(val startMs: Long?, val grams: Double,
        val profile: MealAbsorptionProfile, val durationMinutes: Int)
}
