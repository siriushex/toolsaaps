package io.aaps.copilot.domain.nutrition

import java.time.Instant
import java.time.ZoneId
import java.util.Collections
import kotlin.math.abs
import kotlin.math.min

internal data class PersonalAbsorptionCohort(
    val episodes: List<PersonalAbsorptionEpisode>,
    val rejections: Map<PersonalAbsorptionRejection, Int>,
    val overflow: Boolean = false
)

/** Shared eligibility; a sparse descriptive view cannot weaken the timing fit. */
internal fun preparePersonalAbsorptionCohort(
    request: PersonalAbsorptionRequest,
    inputs: List<PersonalAbsorptionInput>
): PersonalAbsorptionCohort {
    require(request.cutoffMs > 0 && request.minuteOfDay in 0..1439)
    val zone = ZoneId.of(request.recordedZoneId)
    val end = Instant.ofEpochMilli(request.cutoffMs).atZone(zone).toLocalDate().atStartOfDay(zone).toInstant()
    val start = end.atZone(zone).minusDays(21).toInstant()
    if (inputs.size > 240) return PersonalAbsorptionCohort(emptyList(),
        mapOf(PersonalAbsorptionRejection.OVERFLOW to 1), true)
    val rejects = linkedMapOf<PersonalAbsorptionRejection, Int>()
    fun dropped(reason: PersonalAbsorptionRejection) { rejects[reason] = (rejects[reason] ?: 0) + 1 }
    val known = inputs.filter { input ->
        (input.meal.availableAt.toEpochMilli() <= request.cutoffMs).also {
            if (!it) dropped(PersonalAbsorptionRejection.FUTURE_EVIDENCE)
        }
    }.groupBy { it.meal.canonicalId }.values.mapNotNull { versions ->
        versions.distinct().singleOrNull().also {
            if (it == null) dropped(PersonalAbsorptionRejection.CONFLICTING_IDENTITY)
        }
    }.sortedWith(compareBy<PersonalAbsorptionInput> { it.meal.timestamp }.thenBy { it.meal.canonicalId })
    val adapter = PersonalAbsorptionEvidenceAdapter()
    val episodes = known.mapNotNull { input ->
        val meal = input.meal
        if (meal.timestamp < start || meal.timestamp >= end) {
            dropped(PersonalAbsorptionRejection.OUTSIDE_WINDOW); return@mapNotNull null
        }
        val local = meal.timestamp.atZone(zone)
        val distance = abs(local.toLocalTime().toSecondOfDay() / 60 - request.minuteOfDay)
        if (input.recordedZoneId != request.recordedZoneId || meal.profile != request.profile ||
            meal.portion != request.portion || min(distance, 1440 - distance) > 120) {
            dropped(PersonalAbsorptionRejection.NOT_COMPARABLE); return@mapNotNull null
        }
        val a = meal.timestamp.toEpochMilli()
        val b = a + personalAbsorptionDurationRange(meal.profile).last * 60000L
        if (known.any { other -> other.meal.canonicalId != meal.canonicalId && !other.meal.deleted &&
                other.meal.provenance != MealPortionProvenance.SYNTHETIC_UAM &&
                other.meal.grams.isFinite() && other.meal.grams > 0 &&
                other.meal.timestamp.toEpochMilli() < b &&
                a < other.meal.timestamp.toEpochMilli() + personalAbsorptionDurationRange(other.meal.profile).last * 60000L }) {
            dropped(PersonalAbsorptionRejection.OVERLAPPING_MEAL); return@mapNotNull null
        }
        val prepared = adapter.prepare(input, request.cutoffMs)
        prepared.rejections.forEach(::dropped)
        prepared.episode
    }
    return PersonalAbsorptionCohort(Collections.unmodifiableList(episodes), Collections.unmodifiableMap(rejects))
}
