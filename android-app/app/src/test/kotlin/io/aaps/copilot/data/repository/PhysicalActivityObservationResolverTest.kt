package io.aaps.copilot.data.repository

import com.google.common.truth.Truth.assertThat
import io.aaps.copilot.data.local.entity.TelemetrySampleEntity
import java.time.ZoneId
import org.junit.Test

class PhysicalActivityObservationResolverTest {
    private val now = 1_800_000_000_000L
    private val zone = ZoneId.of("Asia/Tbilisi")

    @Test
    fun keepsOriginalTimestampAndOnlyCoTimedPhysicalValues() {
        val result = resolve(listOf(row("activity_ratio", 1.3, now - 1000),
            row("steps_count", 500.0, now - 2000), row("steps_count", 400.0, now - 1000)))
        assertThat(result?.timestamp).isEqualTo(now - 1000)
        assertThat(result?.stepsCount).isEqualTo(400.0)
        assertThat(result?.source).isEqualTo("local_sensor")
        assertThat(result?.counterDay).startsWith("Asia/Tbilisi:")
    }

    @Test
    fun refusesSensitivityAliasesAndUntrustedCollectors() {
        assertThat(resolve(listOf(row("sensitivity_ratio", 1.6),
            row("raw_activityratio", 1.8), row("activity_ratio", 1.4).copy(source = "aaps_broadcast")))).isNull()
    }

    @Test
    fun newerInvalidAcquisitionDoesNotExposeAnOlderGoodOne() {
        listOf(row("activity_ratio", Double.NaN), row("activity_ratio", 1.4).copy(quality = "SUSPECT"),
            row("activity_ratio", 1.4).copy(unit = "U"), row("steps_count", 1.5),
            row("steps_count", -1.0), row("activity_ratio", 4.0)).forEach { invalid ->
            assertThat(resolve(listOf(row("activity_ratio", 1.0, now - 1000), invalid))).isNull()
        }
    }

    @Test
    fun rejectsFutureStaleAndConflictingAcquisitions() {
        assertThat(resolve(listOf(row("activity_ratio", 1.3, now + 1)))).isNull()
        assertThat(resolve(listOf(row("activity_ratio", 1.3, now - 10 * 60_000 - 1)))).isNull()
        assertThat(resolve(listOf(row("activity_ratio", 1.3), row("activity_ratio", 1.0).copy(id = "other")))).isNull()
    }

    @Test
    fun duplicatesAreIdempotentAndSourcesAreNotSummed() {
        val local = row("steps_count", 100.0)
        val watch = row("steps_count", 500.0).copy(source = "health_connect")
        val result = resolve(listOf(watch, local, local.copy(id = "duplicate")))
        assertThat(result?.stepsCount).isEqualTo(100.0)
        assertThat(resolve(listOf(local, watch))).isEqualTo(result)
    }

    @Test
    fun healthConnectFreshnessAllowsItsFifteenMinuteHeartbeatButRemainsBounded() {
        val sample = row("activity_ratio", 1.0, now - 15 * 60_000).copy(source = "health_connect")
        assertThat(resolve(listOf(sample))).isNotNull()
        assertThat(resolve(listOf(sample.copy(timestamp = now - 20 * 60_000 - 1)))).isNull()
        assertThat(resolve(listOf(sample.copy(source = "local_sensor")))).isNull()
    }

    @Test
    fun aSourceSwitchRetainsIdentityAndDoesNotCopyOlderMetrics() {
        val result = resolve(listOf(row("steps_count", 300.0, now - 1000),
            row("activity_ratio", 1.4).copy(source = "health_connect")))
        assertThat(result?.source).isEqualTo("health_connect")
        assertThat(result?.stepsCount).isNull()
    }

    @Test
    fun localDayAndZoneArePartOfCounterContinuity() {
        val samples = listOf(row("steps_count", 10.0))
        assertThat(PhysicalActivityObservationResolver.resolve(samples, now, ZoneId.of("UTC"))?.counterDay)
            .isNotEqualTo(resolve(samples)?.counterDay)
    }

    private fun resolve(rows: List<TelemetrySampleEntity>) = PhysicalActivityObservationResolver.resolve(rows, now, zone)
    private fun row(key: String, value: Double, timestamp: Long = now) = TelemetrySampleEntity(
        id = "sample-$key-$timestamp", timestamp = timestamp, source = "local_sensor", key = key,
        valueDouble = value, valueText = null, unit = if (key == "steps_count") "steps" else "ratio", quality = "OK"
    )
}
