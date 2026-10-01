package io.aaps.copilot.domain.nutrition

import io.aaps.copilot.domain.profile.MealAbsorptionProfile
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.min

enum class MealPortionProvenance {
    UNKNOWN, USER_ENTERED, USER_CORRECTED, ACCEPTED_SUGGESTION, SYNTHETIC_UAM,
    RESCUE, PREPARATORY
}

/** Input must come from canonical therapy records, not inferred glucose excursions. */
data class MealPortionObservation(
    val canonicalId: String,
    val timestamp: Instant,
    val grams: Double,
    val profile: MealAbsorptionProfile,
    val provenance: MealPortionProvenance = MealPortionProvenance.UNKNOWN,
    val portion: MealPortion? = null,
    val deleted: Boolean = false,
    val availableAt: Instant = timestamp,
    val therapyRevision: String? = null
)

data class MealPortionEstimate(
    val grams: Double,
    val fromHistory: Boolean,
    val supportMeals: Int,
    val supportDays: Int
)

/** Offline candidate only. Does not authorize submission or alter saved presets. */
class MealPortionEstimator(private val clock: Clock) {
    fun estimate(
        observations: List<MealPortionObservation>,
        settings: MealPortionSettings,
        portion: MealPortion,
        profile: MealAbsorptionProfile,
        zone: ZoneId
    ): MealPortionEstimate {
        require(settings.isValid())
        val now = clock.instant()
        val start = now.minus(Duration.ofDays(14))
        val range = settings.range(portion)
        // Conflicting revisions (including tombstones) need upstream reconciliation.
        // Do not choose a revision by input order or revive a deleted meal.
        val valid = observations.groupBy { it.canonicalId }.values.mapNotNull { versions ->
            versions.distinct().singleOrNull()
        }.filter {
            it.canonicalId.isNotBlank() && !it.deleted &&
                it.timestamp >= start && it.timestamp < now &&
                it.availableAt >= it.timestamp && it.availableAt <= now &&
                it.grams.isFinite() && it.grams > 0.0 &&
                (it.provenance == MealPortionProvenance.USER_ENTERED ||
                    it.provenance == MealPortionProvenance.USER_CORRECTED)
        }
        val minuteNow = now.atZone(zone).toLocalTime().toSecondOfDay() / 60
        val comparable = valid.filter {
            val category = it.portion ?: settings.classifyHistoricalGrams(it.grams)
            val minute = it.timestamp.atZone(zone).toLocalTime().toSecondOfDay() / 60
            val difference = abs(minute - minuteNow)
            it.profile == profile && category == portion &&
                it.grams in range.minGrams..range.maxGrams &&
                min(difference, 1440 - difference) <= 120
        }
        val days = comparable.map { it.timestamp.atZone(zone).toLocalDate() }.distinct().size
        val historyDays = valid.map { it.timestamp.atZone(zone).toLocalDate() }.distinct().size
        if (historyDays < 5 || comparable.size < 5 || days < 3) {
            return MealPortionEstimate(range.defaultGrams, false, comparable.size, days)
        }
        val weighted = comparable.map {
            val ageDays = Duration.between(it.timestamp, now).toMillis() / 86_400_000.0
            it.grams to 1.0 / (1.0 + ageDays)
        }.sortedWith(compareBy<Pair<Double, Double>> { it.first }.thenBy { it.second })
        val halfway = weighted.sumOf { it.second } / 2.0
        var cumulative = 0.0
        val median = weighted.first {
            cumulative += it.second
            cumulative >= halfway
        }.first
        val historyWeight = comparable.size.toDouble() / (comparable.size + 5.0)
        val estimate = range.defaultGrams + historyWeight * (median - range.defaultGrams)
        return MealPortionEstimate(
            estimate.coerceIn(range.minGrams, range.maxGrams), true, comparable.size, days
        )
    }
}
