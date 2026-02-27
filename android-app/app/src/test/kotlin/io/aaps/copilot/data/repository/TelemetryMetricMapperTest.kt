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

    @Test
    fun doesNotTreatEnableUamConfig_asUamEvent() {
        val ts = 1_700_000_900_000L
        val samples = TelemetryMetricMapper.fromKeyValueMap(
            timestamp = ts,
            source = "aaps_broadcast",
            values = mapOf(
                "predBGs.UAM[0]" to "155",
                "predBGs.UAM[1]" to "149",
                "openaps.suggested.enableUAM" to "false"
            )
        )
        val byKey = samples.associateBy { it.key }
        assertThat(byKey).doesNotContainKey("uam_value")
    }

    @Test
    fun mapsFutureCarbsAndDropsZeroTherapyCarbs() {
        val ts = 1_700_000_950_000L
        val samples = TelemetryMetricMapper.fromKeyValueMap(
            timestamp = ts,
            source = "aaps_broadcast",
            values = mapOf(
                "carbs" to "0",
                "futureCarbs" to "12.5"
            )
        )
        val byKey = samples.associateBy { it.key }
        assertThat(byKey).doesNotContainKey("carbs_grams")
        assertThat(byKey["future_carbs_grams"]?.valueDouble).isEqualTo(12.5)
    }

    @Test
    fun extractsProfilePercentAndIsfCr_fromStatusReasonText() {
        val ts = 1_700_001_000_000L
        val samples = TelemetryMetricMapper.fromKeyValueMap(
            timestamp = ts,
            source = "aaps_broadcast",
            values = mapOf(
                "profile" to "основной (120%)",
                "enacted.reason" to "COB: 24,8, ISF: 2,3, CR: 8,33, Target: 4,4"
            )
        )
        val byKey = samples.groupBy { it.key }
        assertThat(byKey["profile_percent"]?.first()?.valueDouble).isWithin(0.01).of(120.0)
        assertThat(byKey["isf_value"]?.first()?.valueDouble).isWithin(0.01).of(2.3)
        assertThat(byKey["cr_value"]?.first()?.valueDouble).isWithin(0.01).of(8.33)
    }

    @Test
    fun dropsOutOfRangeCanonicalValues() {
        val ts = 1_700_001_100_000L
        val samples = TelemetryMetricMapper.fromKeyValueMap(
            timestamp = ts,
            source = "aaps_broadcast",
            values = mapOf(
                "iob" to "999",
                "cob" to "1200",
                "dia" to "0.1",
                "heartRate" to "4",
                "isf" to "200",
                "cr" to "0.5",
                "custom.metric" to "42"
            )
        )

        val byKey = samples.groupBy { it.key }
        assertThat(byKey).doesNotContainKey("iob_units")
        assertThat(byKey).doesNotContainKey("cob_grams")
        assertThat(byKey).doesNotContainKey("dia_hours")
        assertThat(byKey).doesNotContainKey("heart_rate_bpm")
        assertThat(byKey).doesNotContainKey("isf_value")
        assertThat(byKey).doesNotContainKey("cr_value")
        assertThat(byKey).containsKey("raw_custom_metric")
    }

    @Test
    fun derivesUamFlag_fromPredictedUamDeltaWhenReasonAbsent() {
        val ts = 1_700_001_200_000L
        val samples = TelemetryMetricMapper.fromKeyValueMap(
            timestamp = ts,
            source = "aaps_broadcast",
            values = mapOf(
                "predBGs.UAM[0]" to "180",
                "openaps.suggested.bg" to "150"
            )
        )
        val byKey = samples.associateBy { it.key }
        assertThat(byKey["uam_value"]?.valueDouble).isEqualTo(1.0)
    }

    @Test
    fun doesNotDeriveUamFlag_fromReasonDevOnly() {
        val ts = 1_700_001_300_000L
        val samples = TelemetryMetricMapper.fromKeyValueMap(
            timestamp = ts,
            source = "aaps_broadcast",
            values = mapOf(
                "reason" to "COB: 24,8, Dev: 3,4, BGI: -0,4, ISF: 2,3, CR: 8,33"
            )
        )
        val byKey = samples.associateBy { it.key }
        assertThat(byKey).doesNotContainKey("uam_value")
    }

    @Test
    fun derivesUamFlag_fromPredictionsAgainstIobCobBaseline() {
        val ts = 1_700_001_400_000L
        val samples = TelemetryMetricMapper.fromKeyValueMap(
            timestamp = ts,
            source = "aaps_broadcast",
            values = mapOf(
                "predBGs.UAM[0]" to "158",
                "predBGs.IOB[0]" to "154",
                "predBGs.COB[0]" to "153",
                "glucoseMgdl" to "150"
            )
        )
        val byKey = samples.associateBy { it.key }
        assertThat(byKey["uam_value"]?.valueDouble).isEqualTo(0.0)
    }
}
