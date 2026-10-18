package io.aaps.copilot.domain.nutrition

import io.aaps.copilot.domain.profile.MealGlycemicIndex
import java.time.Instant
import java.time.ZoneId
import java.util.Collections

data class MealTimingHistoryWindow(
    val days: Int,
    val fromMs: Long,
    val throughExclusiveMs: Long,
    val candidateMeals: Int,
    val qualifiedAbsorptionEpisodes: Int,
    val qualifiedDays: Int,
    val comparableCohorts: Int,
    val largestCohortDays: Int,
    val independentGiEpisodes: Int,
    val rejections: Map<String, Int>
)

/** Source readiness, not a timing fit or clinical admission certificate. */
class MealTimingHistoryReport private constructor(
    val cutoffMs: Long,
    val recordedZoneId: String,
    val status: PersonalAbsorptionSourceStatus,
    windows: List<MealTimingHistoryWindow>
) {
    val windows: List<MealTimingHistoryWindow> = Collections.unmodifiableList(windows.toList())
    val recommendedWaitMinutes: Double? get() = null
    val notificationAllowed: Boolean get() = false

    fun contextSummary(): String = buildString {
        append("meal_timing_history_kind=SOURCE_READINESS_NOT_TRAINED_MODEL\n")
        append("meal_timing_history_status=${status.name}\n")
        append("meal_timing_history_cutoff_ms=$cutoffMs zone=$recordedZoneId\n")
        append("meal_timing_history_coverage=QUALIFIED_DECLARED_MEALS_NOT_ALL_FOOD\n")
        append("meal_timing_observed_response_is_not_causal_insulin_onset=true\n")
        append("meal_timing_confirmed_bolus_to_actual_meal_link=not_available\n")
        append("meal_timing_recommended_wait_minutes=unknown notification_allowed=false\n")
        append("meal_timing_declared_grams_are_not_weighed_ground_truth=true\n")
        windows.forEach { w ->
            append("meal_timing_${w.days}d from_ms=${w.fromMs} through_exclusive_ms=${w.throughExclusiveMs}")
            append(" candidates=${w.candidateMeals} qualified_absorption_episodes=${w.qualifiedAbsorptionEpisodes}")
            append(" qualified_days=${w.qualifiedDays} cohorts=${w.comparableCohorts}")
            append(" largest_cohort_days=${w.largestCohortDays} independent_gi_episodes=${w.independentGiEpisodes}\n")
            if (w.rejections.isNotEmpty()) append("meal_timing_${w.days}d_rejections=" +
                w.rejections.entries.joinToString(",") { "${it.key}:${it.value}" } + "\n")
        }
    }.trimEnd()

    companion object {
        fun calculate(source: PersonalAbsorptionSourceSnapshot,
            giEvidence: List<PersonalGiEvidence> = emptyList()): MealTimingHistoryReport {
            val zone = ZoneId.of(source.recordedZoneId)
            val end = Instant.ofEpochMilli(source.cutoffMs).atZone(zone).toLocalDate().atStartOfDay(zone)
            val overflow = source.outcomes.size > 5000 || source.episodes.size > 240 || giEvidence.size > 5000
            val status = if (overflow) PersonalAbsorptionSourceStatus.OVERFLOW else source.status
            if (status != PersonalAbsorptionSourceStatus.READY)
                return MealTimingHistoryReport(source.cutoffMs, zone.id, status, emptyList())
            val indexes = giEvidence.groupBy { it.canonicalId }
            val windows = listOf(3, 7, 14).map { days ->
                val from = end.minusDays(days.toLong()).toInstant().toEpochMilli()
                val through = end.toInstant().toEpochMilli()
                val rows = source.outcomes.filter { it.timestampMs >= from && it.timestampMs < through }
                val rejects = sortedMapOf<String, Int>()
                fun reject(reason: String) { rejects[reason] = (rejects[reason] ?: 0) + 1 }
                rows.forEach { row ->
                    row.sourceProblems.forEach { reject("SOURCE_${it.name}") }
                    row.preparationRejections.forEach { reject("EPISODE_${it.name}") }
                }
                // Late/revised evidence cannot count as known before this audit's cutoff.
                val episodes = rows.mapNotNull { row -> row.episode?.takeIf { episode ->
                    val valid = row.sourceProblems.isEmpty() && row.preparationRejections.isEmpty() &&
                        episode.latestEvidenceAtMs in 1..source.cutoffMs &&
                        episode.meal.availableAt.toEpochMilli() <= source.cutoffMs
                    if (!valid) reject("UNUSABLE_EPISODE")
                    valid
                } }
                fun dayCount(items: List<PersonalAbsorptionEpisode>) = items.map {
                    it.meal.timestamp.atZone(zone).toLocalDate()
                }.distinct().size
                val cohorts = episodes.filter { !it.comparisonCohort.isNullOrBlank() }
                    .groupBy { it.comparisonCohort }
                val independentGi = episodes.count { episode ->
                    val versions = indexes[episode.meal.canonicalId].orEmpty().distinct()
                    val gi = versions.singleOrNull()
                    val valid = gi != null && gi.therapyRevision == episode.meal.therapyRevision &&
                        gi.availableAtMs in 1..source.cutoffMs && gi.source in setOf("USER", "CATALOG") &&
                        MealGlycemicIndex.fromStored(gi.value, gi.source, gi.reference) != null
                    if (!valid) reject("INDEPENDENT_GI_UNAVAILABLE")
                    valid
                }
                MealTimingHistoryWindow(days, from, through, rows.size, episodes.size, dayCount(episodes),
                    cohorts.size, cohorts.values.maxOfOrNull(::dayCount) ?: 0, independentGi,
                    Collections.unmodifiableMap(rejects))
            }
            return MealTimingHistoryReport(source.cutoffMs, zone.id, status, windows)
        }
    }
}
