package io.aaps.copilot.data.repository

internal data class ClinicalPreparedPeriodWindow(
    val fromTs: Long,
    val throughTs: Long
)

/** Report endpoints are inclusive; planned occurrences remain half-open [start, end). */
internal object ClinicalPlannedActivityPeriodPolicy {
    private const val DAY_MS = 24L * 60L * 60L * 1_000L
    const val MAX_SUPPORTED_DURATION_MINUTES = 1_440
    const val MAX_SUPPORTED_DURATION_MS =
        MAX_SUPPORTED_DURATION_MINUTES.toLong() * 60_000L
    const val MAX_PREPARED_REPORT_SPAN_MS =
        30L * DAY_MS + MAX_SUPPORTED_DURATION_MS + ClinicalSummaryCalculator.BUCKET_MS - 1L

    fun preparedWindow(dataset: ClinicalReportDataset): ClinicalPreparedPeriodWindow? {
        val periods = listOf(
            dataset.detail24h.fromTs to dataset.detail24h.throughTs,
            dataset.summary7d.fromTs to dataset.summary7d.throughTs,
            dataset.summary30d.fromTs to dataset.summary30d.throughTs
        )
        if (periods.any { (fromTs, throughTs) -> fromTs > throughTs }) return null
        return ClinicalPreparedPeriodWindow(
            fromTs = periods.minOf { it.first },
            throughTs = periods.maxOf { it.second }
        )
    }

    fun requireBoundedPreparedWindow(dataset: ClinicalReportDataset): ClinicalPreparedPeriodWindow {
        val window = preparedWindow(dataset) ?: throw ClinicalOpenAiException.InvalidInput()
        val span = try {
            Math.subtractExact(window.throughTs, window.fromTs)
        } catch (_: ArithmeticException) {
            throw ClinicalOpenAiException.DatasetTooLarge()
        }
        if (span > MAX_PREPARED_REPORT_SPAN_MS) {
            throw ClinicalOpenAiException.DatasetTooLarge()
        }
        return window
    }

    fun overlaps(
        activity: ClinicalPlannedActivitySummary,
        window: ClinicalPreparedPeriodWindow
    ): Boolean {
        if (activity.plannedStartMs < 0L ||
            activity.plannedDurationMinutes !in 1..MAX_SUPPORTED_DURATION_MINUTES
        ) {
            return false
        }
        val durationMs = runCatching {
            Math.multiplyExact(activity.plannedDurationMinutes.toLong(), 60_000L)
        }.getOrNull() ?: return false
        val endTsExclusive = runCatching {
            Math.addExact(activity.plannedStartMs, durationMs)
        }.getOrNull() ?: return false
        return overlaps(
            startTs = activity.plannedStartMs,
            endTsExclusive = endTsExclusive,
            window = window
        )
    }

    fun overlaps(
        startTs: Long,
        endTsExclusive: Long,
        window: ClinicalPreparedPeriodWindow
    ): Boolean =
        window.fromTs <= window.throughTs &&
            endTsExclusive > startTs &&
            startTs <= window.throughTs &&
            endTsExclusive > window.fromTs

    fun ownershipTimestamp(
        activity: ClinicalPlannedActivitySummary,
        window: ClinicalPreparedPeriodWindow
    ): Long? = maxOf(activity.plannedStartMs, window.fromTs)
        .takeIf { overlaps(activity, window) }

    fun earliestPotentialStart(fromTs: Long): Long = runCatching {
        Math.subtractExact(fromTs, MAX_SUPPORTED_DURATION_MS)
    }.getOrDefault(Long.MIN_VALUE)
}
