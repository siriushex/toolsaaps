package io.aaps.copilot.domain.eating

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.roundToInt

object EatingWindowAnalyzer {
    private const val DEFAULT_LOOKBACK_DAYS = 14
    private const val DAY_START_MINUTE = 5 * 60
    private const val DAY_END_MINUTE = 23 * 60
    private const val SAME_MEAL_GAP_MINUTES = 90
    private const val WINDOW_MERGE_DISTANCE_MINUTES = 180
    private const val MIN_ENTERED_EPISODE_GRAMS = 10.0
    private const val MIN_UAM_EPISODE_GRAMS_EXCLUSIVE = 20.0
    private const val MIN_SUPPORT_DAYS = 3
    private const val MAX_EPISODES_PER_DAY = 3
    private const val MAX_WINDOWS = 3

    fun analyze(
        evidence: List<EatingEvidencePoint>,
        generatedAt: Long,
        zoneId: ZoneId,
        lookbackDays: Int = DEFAULT_LOOKBACK_DAYS
    ): EatingWindowAnalysis {
        require(lookbackDays in 1..30)
        val generatedDate = Instant.ofEpochMilli(generatedAt).atZone(zoneId).toLocalDate()
        val lastCompleteDate = generatedDate.minusDays(1)
        val firstDate = lastCompleteDate.minusDays((lookbackDays - 1).toLong())
        val candidates = evidence.asSequence()
            .filter { it.ts > 0L && it.carbsG.isFinite() && it.carbsG > 0.0 && it.carbsG <= 300.0 }
            .map { point ->
                val local = Instant.ofEpochMilli(point.ts).atZone(zoneId)
                TimedEvidence(
                    date = local.toLocalDate(),
                    minuteOfDay = local.hour * 60 + local.minute,
                    carbsG = point.carbsG,
                    syntheticUam = point.syntheticUam
                )
            }
            .filter { it.date in firstDate..lastCompleteDate }
            .toList()

        var excludedNightEpisodes = 0
        val mealEpisodes = candidates.groupBy(TimedEvidence::date)
            .toSortedMap()
            .flatMap { (date, dayPoints) ->
                clusterDailyEvidence(date, dayPoints).mapNotNull { episode ->
                    if (episode.minuteOfDay !in DAY_START_MINUTE until DAY_END_MINUTE) {
                        excludedNightEpisodes += 1
                        null
                    } else {
                        episode.takeIf(MealEpisode::qualifies)
                    }
                }.sortedWith(
                    compareByDescending<MealEpisode> { it.totalCarbsG }
                        .thenBy { it.minuteOfDay }
                ).take(MAX_EPISODES_PER_DAY)
            }

        val clusters = mealEpisodes.map { mutableListOf(it) }.toMutableList()
        while (true) {
            val merge = closestMerge(clusters) ?: break
            val combined = (clusters[merge.first] + clusters[merge.second])
                .sortedWith(compareBy<MealEpisode> { it.date }.thenBy { it.minuteOfDay })
                .toMutableList()
            clusters.removeAt(merge.second)
            clusters.removeAt(merge.first)
            clusters += combined
        }

        val windows = clusters.asSequence()
            .mapNotNull { cluster -> cluster.toWindow(lookbackDays) }
            .sortedWith(
                compareByDescending<ProbableEatingWindow> { it.supportDays }
                    .thenBy { it.iqrMinutes }
                    .thenBy { it.medianMinuteOfDay }
            )
            .take(MAX_WINDOWS)
            .sortedBy(ProbableEatingWindow::medianMinuteOfDay)
            .toList()

        return EatingWindowAnalysis(
            lookbackDays = lookbackDays,
            windows = windows,
            excludedNightEpisodeCount = excludedNightEpisodes
        )
    }

    private fun clusterDailyEvidence(
        date: LocalDate,
        points: List<TimedEvidence>
    ): List<MealEpisode> {
        val sorted = points.sortedBy(TimedEvidence::minuteOfDay)
        if (sorted.isEmpty()) return emptyList()
        val groups = mutableListOf<MutableList<TimedEvidence>>()
        sorted.forEach { point ->
            val current = groups.lastOrNull()
            if (current == null || point.minuteOfDay - current.last().minuteOfDay > SAME_MEAL_GAP_MINUTES) {
                groups += mutableListOf(point)
            } else {
                current += point
            }
        }
        return groups.map { group ->
            val enteredPoints = group.filterNot(TimedEvidence::syntheticUam)
            val uamPoints = group.filter(TimedEvidence::syntheticUam)
            val entered = enteredPoints.sumOf(TimedEvidence::carbsG)
            val uam = uamPoints.sumOf(TimedEvidence::carbsG)
            val acceptedEntered = entered >= MIN_ENTERED_EPISODE_GRAMS
            val acceptedUam = uam > MIN_UAM_EPISODE_GRAMS_EXCLUSIVE
            val acceptedPoints = buildList {
                if (acceptedEntered) addAll(enteredPoints)
                if (acceptedUam) addAll(uamPoints)
            }.ifEmpty { group }
            MealEpisode(
                date = date,
                minuteOfDay = percentile(acceptedPoints.map(TimedEvidence::minuteOfDay), 0.5),
                enteredCarbsG = entered.takeIf { acceptedEntered } ?: 0.0,
                uamCarbsG = uam.takeIf { acceptedUam } ?: 0.0
            )
        }
    }

    private fun closestMerge(clusters: List<List<MealEpisode>>): Pair<Int, Int>? {
        var best: Pair<Int, Int>? = null
        var bestDistance = Int.MAX_VALUE
        for (left in clusters.indices) {
            for (right in (left + 1) until clusters.size) {
                val leftDates = clusters[left].mapTo(mutableSetOf(), MealEpisode::date)
                if (clusters[right].any { it.date in leftDates }) continue
                val distance = abs(
                    percentile(clusters[left].map(MealEpisode::minuteOfDay), 0.5) -
                        percentile(clusters[right].map(MealEpisode::minuteOfDay), 0.5)
                )
                if (distance <= WINDOW_MERGE_DISTANCE_MINUTES && distance < bestDistance) {
                    best = left to right
                    bestDistance = distance
                }
            }
        }
        return best
    }

    private fun List<MealEpisode>.toWindow(lookbackDays: Int): ProbableEatingWindow? {
        val supportDays = map(MealEpisode::date).distinct().size
        if (supportDays < MIN_SUPPORT_DAYS) return null
        val minutes = map(MealEpisode::minuteOfDay)
        val start = percentile(minutes, 0.25)
        val end = percentile(minutes, 0.75)
        return ProbableEatingWindow(
            medianMinuteOfDay = percentile(minutes, 0.5),
            startMinuteOfDay = start,
            endMinuteOfDay = end,
            iqrMinutes = end - start,
            supportDays = supportDays,
            lookbackDays = lookbackDays,
            episodeCount = size,
            enteredEpisodeCount = count { it.enteredCarbsG >= MIN_ENTERED_EPISODE_GRAMS },
            uamEpisodeCount = count { it.uamCarbsG > MIN_UAM_EPISODE_GRAMS_EXCLUSIVE },
            confidencePct = supportDays.toDouble() / lookbackDays.toDouble() * 100.0
        )
    }

    private fun percentile(values: List<Int>, fraction: Double): Int {
        require(values.isNotEmpty())
        val sorted = values.sorted()
        if (sorted.size == 1) return sorted.single()
        val position = fraction.coerceIn(0.0, 1.0) * (sorted.lastIndex)
        val lower = position.toInt()
        val upper = (lower + 1).coerceAtMost(sorted.lastIndex)
        val remainder = position - lower
        return (sorted[lower] + (sorted[upper] - sorted[lower]) * remainder).roundToInt()
    }

    private data class TimedEvidence(
        val date: LocalDate,
        val minuteOfDay: Int,
        val carbsG: Double,
        val syntheticUam: Boolean
    )

    private data class MealEpisode(
        val date: LocalDate,
        val minuteOfDay: Int,
        val enteredCarbsG: Double,
        val uamCarbsG: Double
    ) {
        val totalCarbsG: Double = enteredCarbsG + uamCarbsG

        fun qualifies(): Boolean =
            enteredCarbsG >= MIN_ENTERED_EPISODE_GRAMS ||
                uamCarbsG > MIN_UAM_EPISODE_GRAMS_EXCLUSIVE
    }
}
