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

/** Quantiles of entered quantities, not bounds/confidence for actual eaten mass. */
data class MealQuantityPrior(
    val estimate: MealPortionEstimate,
    val declaredP10Grams: Double?,
    val declaredP90Grams: Double?,
    val inputOverflow: Boolean = false
) {
    val actualMassAccuracyValidated: Boolean get() = false
    val clinicalInfluenceAllowed: Boolean get() = false
}

/** Offline candidate only. Does not authorize submission or alter saved presets. */
class MealPortionEstimator(private val clock: Clock) {
    fun estimate(
        observations: List<MealPortionObservation>,
        settings: MealPortionSettings,
        portion: MealPortion,
        profile: MealAbsorptionProfile,
        zone: ZoneId,
        minuteOfDay: Int? = null
    ): MealPortionEstimate {
        require(settings.isValid())
        require(minuteOfDay == null || minuteOfDay in 0..1439)
        val now = clock.instant()
        val (valid, comparable) = selectObservations(observations, settings, portion, profile, zone, now, minuteOfDay)
        val days = comparable.map { it.timestamp.atZone(zone).toLocalDate() }.distinct().size
        val historyDays = valid.map { it.timestamp.atZone(zone).toLocalDate() }.distinct().size
        val range = settings.range(portion)
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
        return MealPortionEstimate(estimate.coerceIn(range.minGrams, range.maxGrams), true, comparable.size, days)
    }

    fun describePrior(
        observations: List<MealPortionObservation>, settings: MealPortionSettings,
        portion: MealPortion, profile: MealAbsorptionProfile, zone: ZoneId,
        minuteOfDay: Int? = null
    ): MealQuantityPrior {
        require(settings.isValid())
        require(minuteOfDay == null || minuteOfDay in 0..1439)
        val now = clock.instant()
        val overflow = observations.size > 5000
        val bounded = if (overflow) emptyList() else observations
        val estimate = MealPortionEstimator(Clock.fixed(now, zone)).estimate(bounded, settings, portion, profile, zone, minuteOfDay)
        if (!estimate.fromHistory) return MealQuantityPrior(estimate, null, null, overflow)
        val declared = selectObservations(bounded, settings, portion, profile, zone, now, minuteOfDay).second.map { it.grams }.sorted()
        fun quantile(p: Double): Double {
            val position = (declared.size - 1) * p
            val lower = position.toInt()
            val upper = (lower + 1).coerceAtMost(declared.lastIndex)
            return declared[lower] + (declared[upper] - declared[lower]) * (position - lower)
        }
        return MealQuantityPrior(estimate, quantile(0.1), quantile(0.9))
    }

    internal fun comparableForPreview(
        observations: List<MealPortionObservation>, settings: MealPortionSettings,
        portion: MealPortion, profile: MealAbsorptionProfile, zone: ZoneId, minuteOfDay: Int
    ): List<MealPortionObservation> {
        require(settings.isValid() && minuteOfDay in 0..1439)
        if (observations.size > 5000) return emptyList()
        return selectObservations(observations, settings, portion, profile, zone, clock.instant(), minuteOfDay).second
    }

    private fun selectObservations(
        observations: List<MealPortionObservation>, settings: MealPortionSettings,
        portion: MealPortion, profile: MealAbsorptionProfile, zone: ZoneId, now: Instant,
        minuteOfDay: Int?
    ): Pair<List<MealPortionObservation>, List<MealPortionObservation>> {
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
        val minuteNow = minuteOfDay ?: (now.atZone(zone).toLocalTime().toSecondOfDay() / 60)
        val comparable = valid.filter {
            val category = it.portion ?: settings.classifyHistoricalGrams(it.grams)
            val minute = it.timestamp.atZone(zone).toLocalTime().toSecondOfDay() / 60
            val difference = abs(minute - minuteNow)
            it.profile == profile && category == portion &&
                it.grams in range.minGrams..range.maxGrams &&
                min(difference, 1440 - difference) <= 120
        }
        return valid to comparable
    }
}
