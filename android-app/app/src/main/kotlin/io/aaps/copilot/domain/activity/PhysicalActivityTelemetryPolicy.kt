package io.aaps.copilot.domain.activity

import java.util.Locale

/** Canonical boundary between movement telemetry and sensitivity/autosens telemetry. */
object PhysicalActivityTelemetryPolicy {
    const val ACTIVITY_RATIO_KEY = "activity_ratio"
    const val ACTIVITY_RATIO_PEAK_KEY = "activity_ratio_peak"
    const val STEPS_COUNT_KEY = "steps_count"
    const val DISTANCE_KM_KEY = "distance_km"
    const val ACTIVE_MINUTES_KEY = "active_minutes"
    const val ACTIVE_CALORIES_KCAL_KEY = "calories_active_kcal"
    const val LOCAL_SENSOR_SOURCE = "local_sensor"
    const val HEALTH_CONNECT_SOURCE = "health_connect"
    const val MINUTE_MS = 60_000L
    const val REPORT_BUCKET_MS = 5L * MINUTE_MS
    const val MAX_REPORT_BUCKETS_PER_SOURCE = 30 * 24 * 12

    val TRUSTED_SOURCES: Set<String> = setOf(LOCAL_SENSOR_SOURCE, HEALTH_CONNECT_SOURCE)
    val ACCEPTABLE_QUALITIES: Set<String> = setOf("OK", "TRUSTED")
    val PERSISTED_ACTIVITY_METRIC_KEYS: Set<String> = setOf(
        ACTIVITY_RATIO_KEY,
        STEPS_COUNT_KEY,
        DISTANCE_KM_KEY,
        ACTIVE_MINUTES_KEY,
        ACTIVE_CALORIES_KCAL_KEY
    )
    val CLINICAL_ACTIVITY_METRIC_KEYS: Set<String> = setOf(
        ACTIVITY_RATIO_PEAK_KEY,
    ) + PERSISTED_ACTIVITY_METRIC_KEYS

    fun isTrustedPhysicalActivity(
        source: String,
        key: String,
        quality: String,
        value: Double?
    ): Boolean = source in TRUSTED_SOURCES &&
        key == ACTIVITY_RATIO_KEY &&
        normalizeQuality(quality) in ACCEPTABLE_QUALITIES &&
        value?.isFinite() == true &&
        value in 0.2..3.0

    fun isTrustedSourceAndQuality(source: String, quality: String): Boolean =
        source in TRUSTED_SOURCES && normalizeQuality(quality) in ACCEPTABLE_QUALITIES

    fun isTrustedClinicalMetric(source: String, key: String, quality: String): Boolean =
        key in CLINICAL_ACTIVITY_METRIC_KEYS && isTrustedSourceAndQuality(source, quality)

    /** SQL bounds preserve the newest sample while bounding a 30-day report to 8640 buckets/source. */
    fun reportBucketWindow(fromTs: Long, throughTsInclusive: Long): ReportBucketWindow {
        require(fromTs <= throughTsInclusive)
        val lastBucketTs = Math.floorDiv(throughTsInclusive, REPORT_BUCKET_MS) * REPORT_BUCKET_MS
        val maximumSpan = (MAX_REPORT_BUCKETS_PER_SOURCE - 1L) * REPORT_BUCKET_MS
        val earliestAllowedBucket = runCatching { Math.subtractExact(lastBucketTs, maximumSpan) }
            .getOrDefault(Long.MIN_VALUE)
        return ReportBucketWindow(
            fromTs = fromTs,
            firstBucketTs = maxOf(
                Math.floorDiv(fromTs, REPORT_BUCKET_MS) * REPORT_BUCKET_MS,
                earliestAllowedBucket
            ),
            toTsExclusive = if (throughTsInclusive == Long.MAX_VALUE) {
                Long.MAX_VALUE
            } else {
                throughTsInclusive + 1L
            }
        )
    }

    fun minuteBucket(timestamp: Long): Long = Math.floorDiv(timestamp, MINUTE_MS) * MINUTE_MS

    fun minuteDurableId(source: String, key: String, timestamp: Long): String =
        "physical-minute:$source:$key:${minuteBucket(timestamp)}"

    fun normalizeQuality(quality: String): String = quality.trim().uppercase(Locale.US)
}

data class ReportBucketWindow(
    val fromTs: Long,
    val firstBucketTs: Long,
    val toTsExclusive: Long
)
