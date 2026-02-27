package io.aaps.copilot.data.repository

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class TelemetryMetricMapperTest {

    @Test
    fun mapsCanonicalAndRawMetrics_fromBroadcastPayload() {
        val ts = 1_700_000_000_000L
        val samples = TelemetryMetricMapper.fromKeyValueMap(
            timestamp = ts,
            source = "xdrip_broadcast",
            values = mapOf(
                "iob" to "1.25",
                "cob" to "18",
                "carbs" to "24",
                "insulin" to "1.8",
                "dia" to "5",
                "steps" to "6512",
                "activityRatio" to "0.84",
                "heartRate" to "98",
                "custom.metric" to "42.4",
                "api_secret" to "should_not_be_saved"
            )
        )

        val byKey = samples.groupBy { it.key }
        assertThat(byKey["iob_units"]?.first()?.valueDouble).isEqualTo(1.25)
        assertThat(byKey["cob_grams"]?.first()?.valueDouble).isEqualTo(18.0)
        assertThat(byKey["carbs_grams"]?.first()?.valueDouble).isEqualTo(24.0)
        assertThat(byKey["insulin_units"]?.first()?.valueDouble).isEqualTo(1.8)
        assertThat(byKey["dia_hours"]?.first()?.valueDouble).isEqualTo(5.0)
        assertThat(byKey["steps_count"]?.first()?.valueDouble).isEqualTo(6512.0)
        assertThat(byKey["activity_ratio"]?.first()?.valueDouble).isEqualTo(0.84)
        assertThat(byKey["heart_rate_bpm"]?.first()?.valueDouble).isEqualTo(98.0)
        assertThat(byKey).doesNotContainKey("basal_rate_u_h")

        assertThat(byKey).containsKey("raw_custom_metric")
        assertThat(byKey.keys.any { it.contains("secret") }).isFalse()
    }

    @Test
    fun mapsNestedAliasTokensAndNightscoutRawFields() {
        val ts = 1_700_000_500_000L

        val tokenizedSamples = TelemetryMetricMapper.fromKeyValueMap(
            timestamp = ts,
            source = "aaps_broadcast",
            values = mapOf(
                "openaps.iob.iob" to "1.42",
                "openaps.suggested.COB" to "14"
            )
        )
        val tokenizedKeys = tokenizedSamples.associateBy { it.key }
        assertThat(tokenizedKeys["iob_units"]?.valueDouble).isEqualTo(1.42)
        assertThat(tokenizedKeys["cob_grams"]?.valueDouble).isEqualTo(14.0)

        val nsSamples = TelemetryMetricMapper.fromFlattenedNightscoutDeviceStatus(
            timestamp = ts,
            source = "nightscout_devicestatus",
            flattened = mapOf(
                "openaps.iob.iob" to "1.42",
                "openaps.suggested.COB" to "14",
                "uploader.battery" to "88"
            )
        )
        val nsByKey = nsSamples.associateBy { it.key }
        assertThat(nsByKey["iob_units"]?.valueDouble).isEqualTo(1.42)
        assertThat(nsByKey["cob_grams"]?.valueDouble).isEqualTo(14.0)
        assertThat(nsByKey).containsKey("ns_openaps_iob_iob")
        assertThat(nsByKey).containsKey("ns_uploader_battery")
    }
}
