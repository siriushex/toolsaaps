package io.aaps.copilot.domain.activity

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale

enum class ActivityIntensityBand { LIGHT, MEDIUM, HIGH }

data class PhysicalActivityBucket(
    val bucketTs: Long,
    val firstTs: Long,
    val lastTs: Long,
    val peakRatio: Double,
    val meanRatio: Double,
    val sampleCount: Int,
    val source: String,
    val qualityEvidence: String
)

data class ActivityIntensityTransition(
    val timestamp: Long,
    val intensity: ActivityIntensityBand
)

data class ActualActivityEpisode(
    val stableId: String,
    val startTs: Long,
    val endTs: Long,
    val sources: List<String>,
    val peakRatio: Double,
    val weightedMeanRatio: Double,
    val dominantIntensity: ActivityIntensityBand,
    val sampleCount: Int,
    val intensityTransitions: List<ActivityIntensityTransition>,
    val qualityEvidence: List<String>
)

class ActualActivityEpisodeBuilder {

    fun build(
        input: List<PhysicalActivityBucket>,
        beforeEpisode: () -> Unit = {}
    ): List<ActualActivityEpisode> {
        val buckets = input.asSequence()
            .filter(::valid)
            .filter { effectiveEnd(it) != null }
            .distinct()
            .sortedWith(
                compareBy<PhysicalActivityBucket> { it.firstTs }
                    .thenBy { it.lastTs }
                    .thenBy { it.source }
                    .thenBy { it.bucketTs }
            )
            .toList()
        if (buckets.isEmpty()) return emptyList()

        val groups = mutableListOf<MutableList<PhysicalActivityBucket>>()
        buckets.forEach { bucket ->
            val current = groups.lastOrNull()
            val currentEnd = current?.maxOfOrNull { requireNotNull(effectiveEnd(it)) }
            if (current == null || currentEnd == null || bucket.firstTs - currentEnd > MAX_GAP_MS) {
                groups += mutableListOf(bucket)
            } else {
                current += bucket
            }
        }
        return groups.map { group ->
            beforeEpisode()
            episode(group)
        }
    }

    private fun valid(bucket: PhysicalActivityBucket): Boolean {
        val qualities = bucket.qualityEvidence.split(',')
            .map(PhysicalActivityTelemetryPolicy::normalizeQuality)
            .filter(String::isNotBlank)
        return bucket.bucketTs >= 0L &&
            bucket.firstTs >= 0L &&
            bucket.lastTs >= bucket.firstTs &&
            bucket.sampleCount > 0 &&
            bucket.peakRatio.isFinite() &&
            bucket.meanRatio.isFinite() &&
            bucket.peakRatio > ACTIVITY_RATIO_THRESHOLD &&
            bucket.peakRatio in 0.2..3.0 &&
            bucket.meanRatio in 0.2..3.0 &&
            bucket.source in PhysicalActivityTelemetryPolicy.TRUSTED_SOURCES &&
            qualities.isNotEmpty() &&
            qualities.all { it in PhysicalActivityTelemetryPolicy.ACCEPTABLE_QUALITIES }
    }

    private fun episode(buckets: List<PhysicalActivityBucket>): ActualActivityEpisode {
        val count = buckets.sumOf { it.sampleCount.toLong() }.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        val weightedMean = buckets.sumOf { it.meanRatio * it.sampleCount.toDouble() } / count.toDouble()
        val transitions = transitions(buckets)
        val dominant = buckets.groupingBy { intensity(it.meanRatio) }
            .fold(0L) { total, bucket -> total + bucket.sampleCount.toLong() }
            .entries
            .maxWithOrNull(
                compareBy<Map.Entry<ActivityIntensityBand, Long>> { it.value }
                    .thenBy { it.key.ordinal }
            )
            ?.key
            ?: ActivityIntensityBand.LIGHT
        val normalized = buckets.sortedWith(
            compareBy<PhysicalActivityBucket> { it.bucketTs }
                .thenBy { it.source }
                .thenBy { it.firstTs }
                .thenBy { it.lastTs }
        )
        val startTs = normalized.minOf(PhysicalActivityBucket::firstTs)
        val endTs = normalized.maxOf { requireNotNull(effectiveEnd(it)) }
        return ActualActivityEpisode(
            stableId = stableId(normalized),
            startTs = startTs,
            endTs = endTs,
            sources = normalized.map(PhysicalActivityBucket::source).distinct().sorted(),
            peakRatio = normalized.maxOf(PhysicalActivityBucket::peakRatio),
            weightedMeanRatio = weightedMean,
            dominantIntensity = dominant,
            sampleCount = count,
            intensityTransitions = transitions.take(MAX_TRANSITIONS),
            qualityEvidence = normalized.flatMap { it.qualityEvidence.split(',') }
                .map(PhysicalActivityTelemetryPolicy::normalizeQuality)
                .filter(String::isNotBlank)
                .distinct()
                .sorted()
        )
    }

    private fun transitions(buckets: List<PhysicalActivityBucket>): List<ActivityIntensityTransition> {
        var previous: ActivityIntensityBand? = null
        return buckets.groupBy(PhysicalActivityBucket::bucketTs)
            .toSortedMap()
            .mapNotNull { (bucketTs, rows) ->
                val count = rows.sumOf { it.sampleCount.toLong() }
                val mean = rows.sumOf { it.meanRatio * it.sampleCount.toDouble() } / count.toDouble()
                val current = intensity(mean)
                if (current == previous) {
                    null
                } else {
                    previous = current
                    ActivityIntensityTransition(bucketTs, current)
                }
            }
    }

    private fun stableId(buckets: List<PhysicalActivityBucket>): String {
        val canonical = buckets.joinToString(separator = "|") { bucket ->
            listOf(
                bucket.bucketTs,
                bucket.firstTs,
                bucket.lastTs,
                String.format(Locale.US, "%.6f", bucket.peakRatio),
                String.format(Locale.US, "%.6f", bucket.meanRatio),
                bucket.sampleCount,
                bucket.source,
                bucket.qualityEvidence.trim().uppercase(Locale.US)
            ).joinToString(":")
        }
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(StandardCharsets.UTF_8))
            .joinToString(separator = "") { byte -> "%02x".format(Locale.US, byte.toInt() and 0xff) }
        return "actual:$digest"
    }

    private fun intensity(ratio: Double): ActivityIntensityBand = when {
        ratio >= 1.6 -> ActivityIntensityBand.HIGH
        ratio >= 1.25 -> ActivityIntensityBand.MEDIUM
        else -> ActivityIntensityBand.LIGHT
    }

    private fun effectiveEnd(bucket: PhysicalActivityBucket): Long? = when {
        bucket.lastTs > bucket.firstTs -> bucket.lastTs
        bucket.lastTs < bucket.firstTs -> null
        else -> runCatching {
            Math.addExact(bucket.firstTs, PhysicalActivityTelemetryPolicy.REPORT_BUCKET_MS)
        }.getOrNull()
    }

    private companion object {
        const val MAX_GAP_MS = 15L * 60_000L
        const val ACTIVITY_RATIO_THRESHOLD = 1.05
        const val MAX_TRANSITIONS = 12
    }
}
