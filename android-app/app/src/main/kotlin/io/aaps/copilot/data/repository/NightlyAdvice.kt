package io.aaps.copilot.data.repository

import io.aaps.copilot.data.local.dao.ClinicalGlucoseProjection
import java.time.ZoneId

data class NightlyAdviceWindow(
    val days: Int,
    val fromTs: Long,
    val coveragePct: Double,
    val qualityDays: Int,
    val inRangePct: Double?,
    val belowPct: Double?,
    val abovePct: Double?,
    val lowDays: Int,
    val highDays: Int,
    val recentDeltaPp: Double?
) {
    val usable: Boolean get() = qualityDays >= 3 && coveragePct >= 70.0
}

data class NightlyAdviceSnapshot(
    val version: Int = 1,
    val generatedAt: Long,
    val throughTs: Long,
    val zoneId: String,
    val windows: List<NightlyAdviceWindow>
)

/** Observation-only summaries; these reporting filters are not treatment or alarm thresholds. */
internal object NightlyAdviceCalculator {
    const val DAY_MS = 86_400_000L
    private val acceptedQualities = setOf("OK", "GOOD", "VALID", "TRUSTED")

    fun calculate(
        throughTs: Long,
        generatedAt: Long,
        zoneId: ZoneId,
        rows: List<ClinicalGlucoseProjection>
    ): NightlyAdviceSnapshot {
        require(throughTs > 14 * DAY_MS && generatedAt >= throughTs)
        val from = throughTs - 14 * DAY_MS
        val buckets = ClinicalReportDatasetBuilder.canonicalizeGlucoseBuckets(
            rows.filter { it.ts in from..throughTs && it.quality.trim().uppercase(java.util.Locale.ROOT) in acceptedQualities },
            from, throughTs
        )
        val points = ClinicalReportDatasetBuilder.summaryGlucose(buckets)
        val daily = (14 downTo 1).map { offset ->
            val start = throughTs - offset * DAY_MS
            ClinicalSummaryCalculator.calculate(1, start, start + DAY_MS, zoneId, points, emptyList(), emptyList())
        }
        fun covered(summary: ClinicalPeriodSummary) = summary.coveragePct * DAY_MS / 100.0
        fun weighted(summaries: List<ClinicalPeriodSummary>, value: (ClinicalPeriodSummary) -> Double?): Double? {
            val duration = summaries.sumOf(::covered)
            return if (duration <= 0) null else summaries.sumOf { covered(it) * (value(it) ?: 0.0) } / duration
        }
        fun quality(summaries: List<ClinicalPeriodSummary>) = summaries.count { it.coveragePct >= 70.0 }
        val windows = listOf(3, 7, 14).map { days ->
            val selected = daily.takeLast(days)
            val recent = selected.takeLast(3)
            val earlier = selected.dropLast(3)
            val delta = if (quality(recent) == 3 && quality(earlier) >= 3 &&
                earlier.map { it.coveragePct }.average() >= 70.0
            ) {
                weighted(recent) { it.timeInRangePct }!! - weighted(earlier) { it.timeInRangePct }!!
            } else null
            NightlyAdviceWindow(
                days, throughTs - days * DAY_MS, selected.map { it.coveragePct }.average(), quality(selected),
                weighted(selected) { it.timeInRangePct }, weighted(selected) { it.timeBelow4Pct },
                weighted(selected) { it.timeAboveRangePct },
                selected.count { it.coveragePct >= 70.0 && covered(it) * (it.timeBelow4Pct ?: 0.0) / 100.0 >= 15 * 60_000 },
                selected.count { it.coveragePct >= 70.0 && covered(it) * (it.timeAboveRangePct ?: 0.0) / 100.0 >= 30 * 60_000 },
                delta
            )
        }
        return NightlyAdviceSnapshot(generatedAt = generatedAt, throughTs = throughTs,
            zoneId = zoneId.id, windows = windows)
    }
}
