package io.aaps.copilot.service

import com.google.common.truth.Truth.assertThat
import io.aaps.copilot.data.local.entity.GlucoseSampleEntity
import io.aaps.copilot.data.local.entity.TelemetrySampleEntity
import org.junit.Test

class LocalNightscoutDeviceStatusComposerTest {

    @Test
    fun composesNightscoutCompatibleDeviceStatusPayload() {
        val ts = 1_700_000_000_000L
        val latestByKey = listOf(
            telemetry(ts, "iob_units", 2.3),
            telemetry(ts, "cob_grams", 18.0),
            telemetry(ts, "activity_ratio", 1.15),
            telemetry(ts, "sensor_age_days", 12.5),
            telemetry(ts, "sensor_age_hours", 300.0),
            telemetry(ts, "sensor_age_source_raw", text = "cgm.sensorAgeDays"),
            telemetry(ts, "sage_days", 12.5),
            telemetry(ts, "cage_days", 2.0),
            telemetry(ts, "steps_count", 1234.0),
            telemetry(ts, "isf_value", 3.1),
            telemetry(ts, "cr_value", 10.0)
        ).associateBy { it.key }

        val payload = LocalNightscoutDeviceStatusComposer.compose(
            timestamp = ts,
            latestByKey = latestByKey,
            latestGlucose = GlucoseSampleEntity(
                timestamp = ts,
                mmol = 7.8,
                source = "test",
                quality = "OK"
            )
        )

        assertThat(payload["date"]).isEqualTo(ts)
        val cgm = payload["cgm"] as Map<*, *>
        assertThat(cgm["sensorAgeDays"]).isEqualTo(12.5)
        assertThat(cgm["sensorAgeHours"]).isEqualTo(300.0)
        assertThat((cgm["sgv"] as Number).toDouble()).isGreaterThan(100.0)

        val openaps = payload["openaps"] as Map<*, *>
        val suggested = openaps["suggested"] as Map<*, *>
        assertThat(suggested["cob"]).isEqualTo(18.0)
        assertThat(suggested["activity"]).isEqualTo(1.15)
        assertThat(suggested["isf"]).isEqualTo(3.1)
        assertThat(suggested["carb_ratio"]).isEqualTo(10.0)
    }

    @Test
    fun resolvesAnchorTimestampFromRecentTelemetryOrGlucose() {
        val olderTelemetry = telemetry(1000L, "iob_units", 1.0)
        val latestGlucose = GlucoseSampleEntity(
            timestamp = 2000L,
            mmol = 6.0,
            source = "test",
            quality = "OK"
        )
        assertThat(
            LocalNightscoutDeviceStatusComposer.resolveAnchorTimestamp(
                recentRows = listOf(olderTelemetry),
                latestGlucose = latestGlucose,
                nowTs = 1500L
            )
        ).isEqualTo(2000L)
    }

    @Test
    fun doesNotEchoTreatmentLikePumpSignalsFromSyntheticDeviceStatusFeed() {
        val ts = 1_700_000_000_000L
        val latestByKey = listOf(
            telemetry(ts, "insulin_units", value = 0.534, source = "nightscout_devicestatus"),
            telemetry(ts, "carbs_grams", value = 6.0, source = "nightscout_devicestatus"),
            telemetry(ts, "iob_units", value = 2.3)
        ).associateBy { it.key }

        val payload = LocalNightscoutDeviceStatusComposer.compose(
            timestamp = ts,
            latestByKey = latestByKey,
            latestGlucose = null
        )

        val pump = payload["pump"] as? Map<*, *>
        assertThat(pump).isNull()
    }

    @Test
    fun keepsPumpTherapySignalsOnlyFromDirectLocalDeviceStatusSource() {
        val ts = 1_700_000_000_000L
        val latestByKey = listOf(
            telemetry(ts, "insulin_units", value = 0.534, source = "local_nightscout_devicestatus"),
            telemetry(ts, "carbs_grams", value = 6.0, source = "local_nightscout_devicestatus")
        ).associateBy { it.key }

        val payload = LocalNightscoutDeviceStatusComposer.compose(
            timestamp = ts,
            latestByKey = latestByKey,
            latestGlucose = null
        )

        val pump = payload["pump"] as Map<*, *>
        assertThat(pump["insulin"]).isEqualTo(0.534)
        assertThat(pump["carbs"]).isEqualTo(6.0)
    }

    private fun telemetry(
        ts: Long,
        key: String,
        value: Double? = null,
        text: String? = null,
        source: String = "test"
    ): TelemetrySampleEntity = TelemetrySampleEntity(
        id = "tm-test-$key-$ts",
        timestamp = ts,
        source = source,
        key = key,
        valueDouble = value,
        valueText = text,
        unit = null,
        quality = "OK"
    )
}
