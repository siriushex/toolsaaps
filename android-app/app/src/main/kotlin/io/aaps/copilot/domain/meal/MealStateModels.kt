package io.aaps.copilot.domain.meal

import io.aaps.copilot.domain.profile.MealAbsorptionProfile
import java.util.Collections

enum class MealHypothesisKind {
    UPCOMING, JUST_STARTED, STARTED_EARLIER, PREVIOUS_MEAL, NOT_HAPPENING, NO_NEW_MEAL
}

data class MealStartInterval(val earliestMs: Long, val latestMs: Long) {
    init { require(earliestMs > 0 && latestMs >= earliestMs) }
}

data class MealCarbRange(val minimumGrams: Double, val maximumGrams: Double) {
    init {
        require(minimumGrams.isFinite() && maximumGrams.isFinite())
        require(minimumGrams >= 0 && maximumGrams >= minimumGrams)
    }
}

/** Original user record, never replaced by an inferred ingestion time. */
data class MealInput(val id: String, val recordedAtMs: Long, val carbs: MealCarbRange) {
    init { require(id.isNotBlank() && recordedAtMs > 0) }
}

data class MealAbsorptionAlternative(
    val profile: MealAbsorptionProfile,
    val durationMinutes: Int,
    val probability: Double
) {
    init {
        require(probability.isFinite() && probability > 0 && probability <= 1)
        require(durationMinutes in when (profile) {
            MealAbsorptionProfile.FAST -> 30..60
            MealAbsorptionProfile.MIXED -> 60..180
            MealAbsorptionProfile.FAT_PROTEIN -> 180..360
        })
    }
}

class MealScenario(
    val id: String,
    val kind: MealHypothesisKind,
    val inferredStart: MealStartInterval?,
    val carbs: MealCarbRange,
    absorption: List<MealAbsorptionAlternative>,
    val probability: Double,
    val canonicalMealId: String? = null
) {
    val absorption: List<MealAbsorptionAlternative> = Collections.unmodifiableList(absorption.toList())
    init {
        require(id.isNotBlank() && probability.isFinite() && probability > 0)
        require(this.absorption.isNotEmpty())
        require(kotlin.math.abs(this.absorption.sumOf { it.probability } - 1.0) < 1e-9)
        require(canonicalMealId == null || canonicalMealId.isNotBlank())
        require(kind != MealHypothesisKind.NOT_HAPPENING || inferredStart == null)
        require(kind !in setOf(MealHypothesisKind.UPCOMING, MealHypothesisKind.JUST_STARTED,
            MealHypothesisKind.STARTED_EARLIER) || inferredStart != null)
    }
    internal fun weighted(weight: Double) = MealScenario(
        id, kind, inferredStart, carbs, absorption, weight, canonicalMealId
    )
}

/** Experimental posterior; normalization does not establish clinical calibration. */
class MealBelief internal constructor(
    val input: MealInput,
    scenarios: List<MealScenario>,
    val revision: Long,
    val lastSampleAtMs: Long? = null,
    val lastSampleId: String? = null,
    val runtimeIdentity: String? = null
) {
    val modelVersion: String = "meal-state-shadow-v1"
    val scenarios: List<MealScenario> = Collections.unmodifiableList(scenarios.toList())
    val stageProbabilities: Map<MealHypothesisKind, Double> = Collections.unmodifiableMap(
        MealHypothesisKind.entries.associateWith { kind -> this.scenarios.filter { it.kind == kind }.sumOf { it.probability } }
    )
}

data class MealExpectedObservation(val meanMmol: Double, val standardDeviationMmol: Double)

data class MealObservation(
    val sampleId: String,
    val sampleAtMs: Long,
    val glucoseMmol: Double,
    val predictedAtMs: Long,
    val runtimeIdentity: String,
    val basedOnRevision: Long,
    val expected: Map<String, MealExpectedObservation>,
    val qualityTrusted: Boolean
)

enum class MealUpdateReason {
    UPDATED, DUPLICATE_OR_OLD, CORRELATED_SAMPLE, NON_CAUSAL, INVALID_EVIDENCE,
    STALE_REVISION, MODEL_CHANGED
}
data class MealUpdate(val belief: MealBelief, val reason: MealUpdateReason)
