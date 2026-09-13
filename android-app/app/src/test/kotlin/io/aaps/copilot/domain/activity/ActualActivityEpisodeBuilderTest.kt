package io.aaps.copilot.domain.activity

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ActualActivityEpisodeBuilderTest {

    private val builder = ActualActivityEpisodeBuilder()

    @Test
    fun overlapMultipleSourcesAndIntensityTransitionsRemainOneEpisode() {
        val buckets = listOf(
            bucket(10, "local_sensor", 1.12, 1.10, 4),
            bucket(10, "health_connect", 1.30, 1.26, 2, quality = "TRUSTED"),
            bucket(20, "local_sensor", 1.72, 1.65, 3)
        )

        val episodes = builder.build(buckets)

        assertThat(episodes).hasSize(1)
        val episode = episodes.single()
        assertThat(episode.startTs).isEqualTo(10L * MINUTE_MS)
        assertThat(episode.endTs).isEqualTo(20L * MINUTE_MS + MINUTE_MS)
        assertThat(episode.sources).containsExactly("health_connect", "local_sensor").inOrder()
        assertThat(episode.peakRatio).isEqualTo(1.72)
        assertThat(episode.sampleCount).isEqualTo(9)
        assertThat(episode.dominantIntensity).isEqualTo(ActivityIntensityBand.LIGHT)
        assertThat(episode.intensityTransitions).containsExactly(
            ActivityIntensityTransition(10L * MINUTE_MS, ActivityIntensityBand.LIGHT),
            ActivityIntensityTransition(20L * MINUTE_MS, ActivityIntensityBand.HIGH)
        ).inOrder()
    }

    @Test
    fun gapOver15MinutesStartsNewEpisodeWhileExactBoundaryMerges() {
        val merged = builder.build(
            listOf(
                bucket(0, "local_sensor", 1.2, 1.2, 1),
                bucket(16, "local_sensor", 1.2, 1.2, 1)
            )
        )
        val split = builder.build(
            listOf(
                bucket(0, "local_sensor", 1.2, 1.2, 1),
                bucket(17, "local_sensor", 1.2, 1.2, 1)
            )
        )

        assertThat(merged).hasSize(1)
        assertThat(split).hasSize(2)
    }

    @Test
    fun duplicatesAndOutOfOrderInputProduceStableContentIdentityAndAttributes() {
        val input = listOf(
            bucket(20, "local_sensor", 1.7, 1.6, 3),
            bucket(10, "health_connect", 1.3, 1.25, 2),
            bucket(10, "health_connect", 1.3, 1.25, 2),
            bucket(10, "local_sensor", 1.2, 1.15, 4)
        )

        val first = builder.build(input)
        val second = builder.build(input.reversed())

        assertThat(first).isEqualTo(second)
        assertThat(first.single().stableId).matches("actual:[0-9a-f]{64}")
        assertThat(first.single().sampleCount).isEqualTo(9)
        assertThat(first.single().qualityEvidence).containsExactly("OK")
    }

    @Test
    fun inactiveOrMalformedBucketsNeverBecomeEpisodes() {
        assertThat(
            builder.build(
                listOf(
                    bucket(1, "aaps", 1.9, 1.9, 1),
                    bucket(2, "local_sensor", 1.0, 1.0, 1),
                    bucket(3, "local_sensor", Double.NaN, 1.2, 1),
                    bucket(4, "local_sensor", 1.3, 1.2, 0)
                )
            )
        ).isEmpty()
    }

    @Test
    fun singletonBucketUsesDeterministicFiveMinuteDurationAndInclusiveBoundary() {
        val bucketTs = 30L * MINUTE_MS
        val episodes = builder.build(
            listOf(
                PhysicalActivityBucket(
                    bucketTs = bucketTs,
                    firstTs = bucketTs + MINUTE_MS,
                    lastTs = bucketTs + MINUTE_MS,
                    peakRatio = 1.3,
                    meanRatio = 1.3,
                    sampleCount = 1,
                    source = "local_sensor",
                    qualityEvidence = "TRUSTED"
                )
            )
        )

        val episode = episodes.single()
        assertThat(episode.startTs).isEqualTo(bucketTs + MINUTE_MS)
        assertThat(episode.endTs).isEqualTo(bucketTs + 6L * MINUTE_MS)
        assertThat(bucketTs + 6L * MINUTE_MS).isAtMost(episode.endTs)
        assertThat(bucketTs + 6L * MINUTE_MS + 1L).isGreaterThan(episode.endTs)
    }

    @Test
    fun groupingUsesEffectiveSingletonEndForFifteenMinuteGap() {
        val firstTs = MINUTE_MS
        val secondTs = 20L * MINUTE_MS

        val episodes = builder.build(
            listOf(
                singleton(firstTs),
                singleton(secondTs)
            )
        )

        assertThat(episodes).hasSize(1)
        assertThat(episodes.single().startTs).isEqualTo(firstTs)
        assertThat(episodes.single().endTs).isEqualTo(secondTs + 5L * MINUTE_MS)
    }

    @Test
    fun impossibleMaxTimestampSingletonIsDroppedButLastSafeTimestampHasPositiveDuration() {
        val lastSafeStart = Long.MAX_VALUE - 5L * MINUTE_MS

        assertThat(builder.build(listOf(singleton(Long.MAX_VALUE)))).isEmpty()

        val safe = builder.build(listOf(singleton(lastSafeStart))).single()
        assertThat(safe.startTs).isEqualTo(lastSafeStart)
        assertThat(safe.endTs).isEqualTo(Long.MAX_VALUE)
        assertThat(safe.endTs).isGreaterThan(safe.startTs)
    }

    private fun singleton(timestamp: Long) = PhysicalActivityBucket(
        bucketTs = timestamp,
        firstTs = timestamp,
        lastTs = timestamp,
        peakRatio = 1.3,
        meanRatio = 1.3,
        sampleCount = 1,
        source = "local_sensor",
        qualityEvidence = "TRUSTED"
    )

    private fun bucket(
        minute: Int,
        source: String,
        peak: Double,
        mean: Double,
        count: Int,
        quality: String = "OK"
    ) = PhysicalActivityBucket(
        bucketTs = minute.toLong() * MINUTE_MS,
        firstTs = minute.toLong() * MINUTE_MS,
        lastTs = minute.toLong() * MINUTE_MS + MINUTE_MS,
        peakRatio = peak,
        meanRatio = mean,
        sampleCount = count,
        source = source,
        qualityEvidence = quality
    )

    private companion object {
        const val MINUTE_MS = 60_000L
    }
}
