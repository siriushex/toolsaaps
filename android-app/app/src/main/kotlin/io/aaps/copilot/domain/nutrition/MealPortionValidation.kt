package io.aaps.copilot.domain.nutrition

import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.max

data class MealPortionValidationScore(
    val meals: Int,
    val historyPredictions: Int,
    val candidateMaeGrams: Double,
    val presetMaeGrams: Double,
    val candidateMeanOverestimateGrams: Double,
    val presetMeanOverestimateGrams: Double
)

data class MealPortionValidationReport(
    val total: MealPortionValidationScore?,
    val byPortion: Map<MealPortion, MealPortionValidationScore>,
    /** Six-hour local-time blocks: 0=00-05, 1=06-11, 2=12-17, 3=18-23. */
    val byLocalHourBlock: Map<Int, MealPortionValidationScore>
)

/** Walk-forward research only. A report never enables history suggestions. */
class MealPortionValidation(private val clock: Clock) {
    private data class Error(
        val portion: MealPortion, val hourBlock: Int, val fromHistory: Boolean,
        val candidate: Double, val preset: Double
    )

    fun evaluate(
        observations: List<MealPortionObservation>, settings: MealPortionSettings,
        zone: ZoneId, heldOutFrom: Instant
    ): MealPortionValidationReport {
        require(settings.isValid())
        require(observations.size <= 5000)
        val now = clock.instant()
        require(heldOutFrom <= now)
        val independent = observations.groupBy { it.canonicalId }.values
            .mapNotNull { it.distinct().singleOrNull() }.filter {
                it.canonicalId.isNotBlank() && !it.deleted && it.timestamp < now &&
                    it.availableAt >= it.timestamp && it.availableAt <= now &&
                    it.grams.isFinite() && it.grams > 0 &&
                    it.provenance in setOf(MealPortionProvenance.USER_ENTERED, MealPortionProvenance.USER_CORRECTED)
            }
        val errors = independent.filter { it.timestamp >= heldOutFrom && it.portion != null }
            .sortedBy { it.timestamp }.map { target ->
                // Target category is explicit, never inferred from the quantity being scored.
                val portion = checkNotNull(target.portion)
                val past = independent.filter { it.timestamp < target.timestamp && it.availableAt <= target.timestamp }
                val candidate = MealPortionEstimator(Clock.fixed(target.timestamp, zone))
                    .estimate(past, settings, portion, target.profile, zone)
                Error(portion, target.timestamp.atZone(zone).hour / 6, candidate.fromHistory,
                    candidate.grams - target.grams, settings.range(portion).defaultGrams - target.grams)
            }
        return MealPortionValidationReport(score(errors),
            errors.groupBy { it.portion }.mapValues { checkNotNull(score(it.value)) },
            errors.groupBy { it.hourBlock }.mapValues { checkNotNull(score(it.value)) })
    }

    private fun score(errors: List<Error>): MealPortionValidationScore? =
        if (errors.isEmpty()) null else MealPortionValidationScore(
            errors.size, errors.count { it.fromHistory },
            errors.sumOf { abs(it.candidate) } / errors.size,
            errors.sumOf { abs(it.preset) } / errors.size,
            errors.sumOf { max(0.0, it.candidate) } / errors.size,
            errors.sumOf { max(0.0, it.preset) } / errors.size
        )
}
