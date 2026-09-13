package io.aaps.copilot.domain.activity

import com.google.common.truth.Truth.assertThat
import io.aaps.copilot.data.local.entity.TelemetrySampleEntity
import org.junit.Test

class PhysicalActivityTelemetryPolicyTest {

    @Test
    fun onlyExactTrustedLocalSourcesArePhysicalActivity() {
        listOf("OK", "trusted", " TRUSTED ").forEach { quality ->
            assertThat(
                sample(source = "local_sensor", quality = quality).isTrustedPhysicalActivity()
            ).isTrue()
            assertThat(
                sample(source = "health_connect", quality = quality).isTrustedPhysicalActivity()
            ).isTrue()
        }

        listOf("aaps", "nightscout", "local_sensor_extra", "unknown").forEach { source ->
            assertThat(
                sample(source = source).isTrustedPhysicalActivity()
            ).isFalse()
        }
        listOf("BAD", "STALE", "SENSOR_ERROR", "").forEach { quality ->
            assertThat(
                sample(source = "local_sensor", quality = quality).isTrustedPhysicalActivity()
            ).isFalse()
        }
        assertThat(
            sample(source = "local_sensor", key = "autosensRatio").isTrustedPhysicalActivity()
        ).isFalse()
    }

    @Test
    fun minuteIdentityIsDeterministicAcrossRestartAndPreservesExactSourceAndKey() {
        val first = sample(
            id = "volatile-1",
            timestamp = 10L * MINUTE_MS + 1_000L,
            source = "local_sensor"
        )
        val afterRestart = first.copy(id = "volatile-2", timestamp = 10L * MINUTE_MS + 59_999L)

        val firstBucketed = first.copy(
            id = PhysicalActivityTelemetryPolicy.minuteDurableId(first.source, first.key, first.timestamp)
        )
        val restartedBucketed = afterRestart.copy(
            id = PhysicalActivityTelemetryPolicy.minuteDurableId(
                afterRestart.source,
                afterRestart.key,
                afterRestart.timestamp
            )
        )

        assertThat(firstBucketed.id).isEqualTo(restartedBucketed.id)
        assertThat(firstBucketed.id).contains("local_sensor")
        assertThat(firstBucketed.id).contains("activity_ratio")
        assertThat(firstBucketed.timestamp).isEqualTo(first.timestamp)
        assertThat(restartedBucketed.timestamp).isEqualTo(afterRestart.timestamp)
    }

    @Test
    fun reportWindowKeepsNewestBoundaryAndCapsThirtyDaysTo8640Buckets() {
        val throughTs = 100L * DAY_MS
        val fromTs = throughTs - 30L * DAY_MS

        val window = PhysicalActivityTelemetryPolicy.reportBucketWindow(fromTs, throughTs)

        assertThat(window.fromTs).isEqualTo(fromTs)
        assertThat(window.firstBucketTs)
            .isEqualTo(fromTs + PhysicalActivityTelemetryPolicy.REPORT_BUCKET_MS)
        assertThat(window.toTsExclusive).isEqualTo(throughTs + 1L)
        assertThat(
            Math.floorDiv(throughTs, PhysicalActivityTelemetryPolicy.REPORT_BUCKET_MS) -
                Math.floorDiv(window.firstBucketTs, PhysicalActivityTelemetryPolicy.REPORT_BUCKET_MS) + 1L
        ).isEqualTo(PhysicalActivityTelemetryPolicy.MAX_REPORT_BUCKETS_PER_SOURCE.toLong())
    }

    private fun sample(
        id: String = "row",
        timestamp: Long = MINUTE_MS,
        source: String = "local_sensor",
        key: String = "activity_ratio",
        quality: String = "OK"
    ) = TelemetrySampleEntity(
        id = id,
        timestamp = timestamp,
        source = source,
        key = key,
        valueDouble = 1.25,
        valueText = null,
        unit = "ratio",
        quality = quality
    )

    private fun TelemetrySampleEntity.isTrustedPhysicalActivity(): Boolean =
        PhysicalActivityTelemetryPolicy.isTrustedPhysicalActivity(
            source = source,
            key = key,
            quality = quality,
            value = valueDouble
        )

    private companion object {
        const val MINUTE_MS = 60_000L
        const val DAY_MS = 24L * 60L * MINUTE_MS
    }
}
