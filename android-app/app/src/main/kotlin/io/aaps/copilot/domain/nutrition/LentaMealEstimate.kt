package io.aaps.copilot.domain.nutrition

import io.aaps.copilot.domain.predict.MealFoodDisplayProjection
import io.aaps.copilot.domain.profile.MealGlycemicIndex
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.util.Collections
import kotlin.math.pow

enum class LentaMealHistoryStatus { READY, OVERFLOW, UNAVAILABLE }
data class LentaMealHistoryEntry(val meal: MealPortionObservation, val glycemicIndex: MealGlycemicIndex? = null)
data class LentaMealHistory(val cutoffMs: Long, val zoneId: String,
    val entries: List<LentaMealHistoryEntry> = emptyList(), val status: LentaMealHistoryStatus = LentaMealHistoryStatus.READY)
data class LentaMealEstimate(val modeledGrams: Double, val durationMinutes: Int,
    val curve: List<HybridMealCurvePoint>, val supportMeals: Int, val supportDays: Int,
    val historyGi: Double?, val giSupportMeals: Int, val historyStatus: LentaMealHistoryStatus) {
    val clinicalInfluenceAllowed: Boolean get() = false
    val actualMassAccuracyValidated: Boolean get() = false
}

/** Independent declared history, not CGM-derived GI or a source of therapy parameters. */
class LentaMealEstimator {
    fun estimate(request: HybridMealResponseRequest, history: LentaMealHistory): LentaMealEstimate {
        val baseline = HybridMealResponseEstimator().preview(request)
        val zone = ZoneId.of(request.recordedZoneId)
        val now = Instant.ofEpochMilli(request.cutoffMs)
        val dayStart = now.atZone(zone).toLocalDate().atStartOfDay(zone).toInstant()
        val matches = history.cutoffMs == request.cutoffMs && history.zoneId == request.recordedZoneId
        val status = when {
            !matches -> LentaMealHistoryStatus.UNAVAILABLE
            history.entries.size > 5000 -> LentaMealHistoryStatus.OVERFLOW
            else -> history.status
        }
        val entries = if (status == LentaMealHistoryStatus.READY) history.entries else emptyList()
        // Retain conflicting/tombstone versions until the shared selector rejects them.
        val prior = MealPortionEstimator(Clock.fixed(now, zone))
        val comparable = prior.comparableForPreview(entries.map { it.meal },
            request.portionSettings, request.portion, baseline.shape.profile, zone, request.minuteOfDay)
            .filter { it.timestamp < dayStart }
        val count = comparable.size
        val range = request.portionSettings.range(request.portion)
        val declaredMedian = comparable.map { it.grams }.sorted().let { values ->
            if (values.isEmpty()) range.defaultGrams else median(values)
        }
        val weight = (count.toDouble() / (count + 8.0)).coerceAtMost(0.5)
        val grams = request.declaredGrams ?: (range.defaultGrams + weight * (declaredMedian - range.defaultGrams))
            .coerceIn(range.minGrams, range.maxGrams)
        val accepted = comparable.toSet()
        val giValues = entries.filter { it.meal in accepted }.groupBy { it.meal.canonicalId }.values
            .mapNotNull { versions -> versions.distinct().singleOrNull()?.glycemicIndex?.value }.sorted()
        val gi = giValues.takeIf { it.isNotEmpty() }?.let(::median)
        val giWeight = (giValues.size.toDouble() / (giValues.size + 8.0)).coerceAtMost(0.5)
        val exponent = gi?.let { 1.0 + giWeight * (MealFoodDisplayProjection.giShapeExponent(it) - 1.0) } ?: 1.0
        val curve = baseline.curve.map { point ->
            val fraction = point.cumulativeFraction.pow(exponent)
            val absorbed = grams * fraction
            HybridMealCurvePoint(point.ageMinutes, fraction, absorbed, grams - absorbed)
        }
        return LentaMealEstimate(grams, baseline.shape.durationMinutes, Collections.unmodifiableList(curve),
            count, comparable.map { it.timestamp.atZone(zone).toLocalDate() }.distinct().size,
            gi, giValues.size, status)
    }

    private fun median(values: List<Double>): Double =
        values[(values.size - 1) / 2] + (values[values.size / 2] - values[(values.size - 1) / 2]) / 2.0
}
