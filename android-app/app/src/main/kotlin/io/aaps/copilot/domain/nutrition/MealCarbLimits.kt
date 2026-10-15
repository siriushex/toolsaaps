package io.aaps.copilot.domain.nutrition

import io.aaps.copilot.domain.model.TherapyCarbComponentKind
import io.aaps.copilot.domain.model.TherapyEvent
import io.aaps.copilot.domain.model.resolveTherapyComponents

/** An explicit meal is not an inferred UAM estimate or an automatic carb command. */
internal object MealCarbLimits {
    const val MAX_MANUAL_MEAL_GRAMS = 80.0

    fun submissionMaximum(idempotencyKey: String, computationMaximum: Double): Double =
        if (idempotencyKey.startsWith("manual:meal:")) MAX_MANUAL_MEAL_GRAMS
        else boundedComputationMaximum(computationMaximum)

    fun announcedGrams(event: TherapyEvent, computationMaximum: Double): Double? {
        val components = resolveTherapyComponents(event)
        if (components.carbKind != TherapyCarbComponentKind.REAL) return null
        val grams = components.carbsG?.takeIf { it.isFinite() && it in 0.5..400.0 } ?: return null
        val maximum = if (components.canonicalCarbAuthoritative && hasCanonicalTrust(event)) {
            MAX_MANUAL_MEAL_GRAMS
        } else boundedComputationMaximum(computationMaximum)
        return grams.coerceAtMost(maximum)
    }

    fun effectiveCobMaximum(
        therapy: List<TherapyEvent>, referenceTs: Long, maxAgeMinutes: Int,
        computationMaximum: Double
    ): Double {
        val fallback = boundedComputationMaximum(computationMaximum)
        val maxAgeMs = maxAgeMinutes.coerceIn(60, 180) * 60_000L
        return if (therapy.any { event ->
            event.ts in 0..referenceTs && referenceTs - event.ts <= maxAgeMs &&
                hasCanonicalTrust(event) && (announcedGrams(event, fallback) ?: 0.0) > fallback
        }) MAX_MANUAL_MEAL_GRAMS else fallback
    }

    private fun hasCanonicalTrust(event: TherapyEvent): Boolean = with(event.componentTrust) {
        canonicalCarbId?.let { it > 0 } == true && !canonicalCarbRevision.isNullOrBlank() &&
            !canonicalReferenceConflict && !canonicalSemanticConflict && !legacyValidityConflict
    }

    private fun boundedComputationMaximum(value: Double): Double =
        if (value.isFinite()) value.coerceIn(20.0, 60.0) else 20.0
}
