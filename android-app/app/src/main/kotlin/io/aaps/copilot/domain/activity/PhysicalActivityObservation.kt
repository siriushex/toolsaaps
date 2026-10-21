package io.aaps.copilot.domain.activity

/** One acquisition from a trusted physical collector, not an autosensitivity ratio. */
data class PhysicalActivityObservation(
    val timestamp: Long,
    val source: String,
    val counterDay: String,
    val activityRatio: Double?,
    val stepsCount: Double?
) {
    val maxAgeMs: Long get() = maxAgeMs(source)

    fun usableAt(nowTs: Long): Boolean =
        timestamp in 1..nowTs && nowTs - timestamp <= maxAgeMs &&
            source in PhysicalActivityTelemetryPolicy.TRUSTED_SOURCES &&
            counterDay.isNotBlank() &&
            (activityRatio != null || stepsCount != null) &&
            (activityRatio == null || activityRatio.isFinite() && activityRatio in 0.2..3.0) &&
            (stepsCount == null || stepsCount.isFinite() && stepsCount in 0.0..150_000.0 && stepsCount % 1.0 == 0.0)

    companion object {
        // Health Connect persists unchanged values every 15 minutes on a five-minute poll.
        fun maxAgeMs(source: String): Long =
            if (source == PhysicalActivityTelemetryPolicy.HEALTH_CONNECT_SOURCE) 20L * 60_000L else 10L * 60_000L
    }
}
