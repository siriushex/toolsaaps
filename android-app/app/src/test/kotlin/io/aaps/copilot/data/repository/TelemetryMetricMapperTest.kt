package io.aaps.copilot.data.repository

import com.google.common.truth.Truth.assertThat
import io.aaps.copilot.util.UnitConverter
import org.junit.Test

class TelemetryMetricMapperTest {

    @Test
    fun mapsAtomicSignedComponentIobUsingRelayTimestampNotIngestBucket() {
        val ingestBucket = 1_700_000_000_000L
        val relayTimestamp = 1_700_000_120_000L
        val receivedAt = relayTimestamp + 15_000L

        val samples = TelemetryMetricMapper.fromKeyValueMap(
            timestamp = ingestBucket,
            observedAtTimestamp = receivedAt,
            source = "aaps_broadcast",
            values = mapOf(
                "iob" to "-0.5",
                "netIob" to "-0.5",
                "bolusIob" to "0.4",
                "basalIob" to "-0.9",
                "insulinActivity" to "0.012",
                "iobTimestamp" to relayTimestamp.toString()
            )
        )

        val byKey = samples.associateBy { it.key }
        assertThat(byKey["iob_units"]?.valueDouble).isEqualTo(0.4)
        assertThat(byKey["iob_net_units"]?.valueDouble).isEqualTo(-0.5)
        assertThat(byKey["iob_bolus_units"]?.valueDouble).isEqualTo(0.4)
        assertThat(byKey["iob_basal_units"]?.valueDouble).isEqualTo(-0.9)
        assertThat(byKey["insulin_activity"]?.valueDouble).isEqualTo(0.012)
        assertThat(byKey["iob_effective_positive_units"]?.valueDouble).isEqualTo(0.4)
        assertThat(byKey["iob_relay_timestamp_ms"]?.valueDouble).isEqualTo(relayTimestamp.toDouble())
        assertThat(byKey["iob_runtime_source"]?.valueText).isEqualTo("AAPS_COMPONENTS")
        assertThat(byKey["iob_runtime_source_code"]?.valueDouble).isEqualTo(1.0)
        assertThat(byKey.values.filterNot { it.key.startsWith("raw_") }.map { it.timestamp }.distinct())
            .containsExactly(ingestBucket)
    }

    @Test
    fun partialComponentPacketFallsBackAtomicallyWithoutRetainingComponentValues() {
        val relayTimestamp = 1_700_000_120_000L
        val samples = TelemetryMetricMapper.fromKeyValueMap(
            timestamp = 1_700_000_000_000L,
            observedAtTimestamp = relayTimestamp + 15_000L,
            source = "aaps_broadcast",
            values = mapOf(
                "iob" to "-0.5",
                "netIob" to "-0.5",
                "bolusIob" to "0.4",
                "iobTimestamp" to relayTimestamp.toString()
            )
        )

        val byKey = samples.associateBy { it.key }
        assertThat(byKey["iob_units"]?.valueDouble).isEqualTo(0.0)
        assertThat(byKey["iob_runtime_source"]?.valueText).isEqualTo("LEGACY_AAPS")
        assertThat(byKey["iob_runtime_fallback_reason"]?.valueText).contains("component_incomplete")
        assertThat(byKey["iob_bolus_units"]?.valueDouble).isNull()
        assertThat(byKey["iob_basal_units"]?.valueDouble).isNull()
        assertThat(byKey["insulin_activity"]?.valueDouble).isNull()
    }

    @Test
    fun partialComponentPacketWithoutExactLegacyIobFailsClosed() {
        val relayTimestamp = 1_700_000_120_000L
        val samples = TelemetryMetricMapper.fromKeyValueMap(
            timestamp = 1_700_000_000_000L,
            observedAtTimestamp = relayTimestamp + 15_000L,
            source = "aaps_broadcast",
            values = mapOf(
                "netIob" to "-0.5",
                "bolusIob" to "0.4",
                "iobTimestamp" to relayTimestamp.toString()
            )
        )

        val byKey = samples.associateBy { it.key }
        assertThat(byKey["iob_units"]?.valueDouble).isNull()
        assertThat(byKey["iob_runtime_source"]?.valueText).isNull()
        assertThat(byKey["iob_runtime_fallback_reason"]?.valueText).contains("component_incomplete")
        assertThat(byKey["iob_bolus_units"]?.valueDouble).isNull()
        assertThat(byKey["iob_basal_units"]?.valueDouble).isNull()
        assertThat(byKey["insulin_activity"]?.valueDouble).isNull()
    }

    @Test
    fun deceptiveAapsSourceCannotCreateTrustedIobSnapshot() {
        val relayTimestamp = 1_700_000_120_000L
        val samples = TelemetryMetricMapper.fromKeyValueMap(
            timestamp = 1_700_000_000_000L,
            observedAtTimestamp = relayTimestamp + 15_000L,
            source = "not_aaps_relay",
            values = mapOf(
                "iob" to "-0.5",
                "netIob" to "-0.5",
                "bolusIob" to "0.4",
                "basalIob" to "-0.9",
                "insulinActivity" to "0.012",
                "iobTimestamp" to relayTimestamp.toString()
            )
        )

        val byKey = samples.associateBy { it.key }
        assertThat(byKey["iob_runtime_source"]?.valueText).isEqualTo("EXTERNAL_ESTIMATE")
        assertThat(byKey["iob_runtime_source"]?.valueText).isNotEqualTo("AAPS_COMPONENTS")
        assertThat(byKey["iob_runtime_source"]?.valueText).isNotEqualTo("LEGACY_AAPS")
        assertThat(byKey["iob_bolus_units"]?.valueDouble).isNull()
        assertThat(byKey["iob_basal_units"]?.valueDouble).isNull()
        assertThat(byKey["insulin_activity"]?.valueDouble).isNull()
    }

    @Test
    fun rejectsStaleComponentPacketInsteadOfUsingStaleLegacyIob() {
        val receivedAt = 1_700_001_000_000L
        val relayTimestamp = receivedAt - 10 * 60_000L - 1L
        val samples = TelemetryMetricMapper.fromKeyValueMap(
            timestamp = 1_700_000_900_000L,
            observedAtTimestamp = receivedAt,
            source = "aaps_broadcast",
            values = mapOf(
                "iob" to "1.0",
                "netIob" to "1.0",
                "bolusIob" to "1.2",
                "basalIob" to "-0.2",
                "insulinActivity" to "0.02",
                "iobTimestamp" to relayTimestamp.toString()
            )
        )

        val byKey = samples.associateBy { it.key }
        assertThat(byKey["iob_units"]?.valueDouble).isNull()
        assertThat(byKey["iob_bolus_units"]?.valueDouble).isNull()
        assertThat(byKey["iob_runtime_fallback_reason"]?.valueText).contains("timestamp_stale")
    }

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
                "distanceKm" to "4.3",
                "activeMinutes" to "37",
                "activeCalories" to "286",
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
        assertThat(byKey["distance_km"]?.first()?.valueDouble).isEqualTo(4.3)
        assertThat(byKey["active_minutes"]?.first()?.valueDouble).isEqualTo(37.0)
        assertThat(byKey["calories_active_kcal"]?.first()?.valueDouble).isEqualTo(286.0)
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
                "openaps.suggested.COB" to "14",
                "iobTimestamp" to ts.toString()
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
                "fitness.distanceKm" to "2.1",
                "uploader.battery" to "88"
            )
        )
        val nsByKey = nsSamples.associateBy { it.key }
        assertThat(nsByKey["iob_units"]?.valueDouble).isEqualTo(1.42)
        assertThat(nsByKey["iob_runtime_source"]?.valueText).isEqualTo("EXTERNAL_ESTIMATE")
        assertThat(nsByKey["iob_runtime_source_code"]?.valueDouble).isEqualTo(3.0)
        assertThat(nsByKey["cob_grams"]?.valueDouble).isEqualTo(14.0)
        assertThat(nsByKey["distance_km"]?.valueDouble).isEqualTo(2.1)
        assertThat(nsByKey).containsKey("ns_openaps_iob_iob")
        assertThat(nsByKey).containsKey("ns_uploader_battery")
    }

    @Test
    fun preservesKnownNestedNightscoutIobAliasesAsExternalEstimate() {
        val timestamp = 1_700_000_500_000L

        listOf(
            "loop.iob.iob" to "1.21",
            "openaps.iob" to "-0.35"
        ).forEach { (key, value) ->
            val samples = TelemetryMetricMapper.fromFlattenedNightscoutDeviceStatus(
                timestamp = timestamp,
                source = "aaps_broadcast",
                flattened = mapOf(key to value)
            )
            val byKey = samples.associateBy { it.key }

            assertThat(byKey["iob_units"]?.valueDouble).isEqualTo(value.toDouble().coerceAtLeast(0.0))
            assertThat(byKey["iob_net_units"]?.valueDouble).isEqualTo(value.toDouble())
            assertThat(byKey["iob_runtime_source"]?.valueText).isEqualTo("EXTERNAL_ESTIMATE")
            assertThat(byKey["iob_runtime_confidence"]?.valueDouble).isEqualTo(0.4)
            assertThat(byKey["iob_bolus_units"]?.valueDouble).isNull()
            assertThat(byKey["iob_basal_units"]?.valueDouble).isNull()
        }
    }

    @Test
    fun exactLegacyAapsIobWithAuthoritativeTimestampRetainsTrustedLegacyConfidence() {
        val timestamp = 1_700_000_500_000L
        val samples = TelemetryMetricMapper.fromKeyValueMap(
            timestamp = timestamp,
            source = "aaps_broadcast",
            values = mapOf(
                "iob" to "1.25",
                "iobTimestamp" to timestamp.toString()
            )
        )
        val byKey = samples.associateBy { it.key }

        assertThat(byKey["iob_units"]?.valueDouble).isEqualTo(1.25)
        assertThat(byKey["iob_runtime_source"]?.valueText).isEqualTo("LEGACY_AAPS")
        assertThat(byKey["iob_runtime_source_code"]?.valueDouble).isEqualTo(2.0)
        assertThat(byKey["iob_runtime_confidence"]?.valueDouble).isEqualTo(0.65)
        assertThat(byKey["iob_bolus_units"]?.valueDouble).isNull()
        assertThat(byKey["iob_basal_units"]?.valueDouble).isNull()
    }

    @Test
    fun nonAapsComponentFieldsCannotBecomeTrustedAapsComponentSnapshot() {
        val timestamp = 1_700_000_600_000L
        val samples = TelemetryMetricMapper.fromKeyValueMap(
            timestamp = timestamp,
            observedAtTimestamp = timestamp + 5_000L,
            source = "xdrip_broadcast",
            values = mapOf(
                "iob" to "1.0",
                "netIob" to "1.0",
                "bolusIob" to "1.2",
                "basalIob" to "-0.2",
                "insulinActivity" to "0.02",
                "iobTimestamp" to timestamp.toString()
            )
        )

        val byKey = samples.associateBy { it.key }
        assertThat(byKey["iob_runtime_source"]?.valueText).isEqualTo("EXTERNAL_ESTIMATE")
        assertThat(byKey["iob_units"]?.valueDouble).isEqualTo(1.0)
        assertThat(byKey["iob_bolus_units"]?.valueDouble).isNull()
        assertThat(byKey["iob_basal_units"]?.valueDouble).isNull()
        assertThat(byKey["insulin_activity"]?.valueDouble).isNull()
    }

    @Test
    fun mapsProfileAndActiveTemporaryTargetTelemetrySeparately() {
        val ts = 1_700_000_525_000L
        val startedAt = ts - 5 * 60_000L
        val expiresAt = ts + 25 * 60_000L

        val samples = TelemetryMetricMapper.fromKeyValueMap(
            timestamp = ts,
            source = "aaps_broadcast",
            values = mapOf(
                "profileTargetBottom" to "90",
                "profileTargetTop" to "110",
                "targetBottom" to "72",
                "targetTop" to "72",
                "tempTargetActive" to "true",
                "tempTargetStartedAt" to startedAt.toString(),
                "tempTargetExpiresAt" to expiresAt.toString(),
                "tempTargetDurationMs" to (30 * 60_000L).toString(),
                "tempTargetReason" to "Automation",
                "tempTargetId" to "copilot-tt-ack-123"
            )
        )

        val byKey = samples.associateBy { it.key }
        assertThat(byKey["profile_target_low_mmol"]?.valueDouble)
            .isWithin(0.001).of(UnitConverter.mgdlToMmol(90.0))
        assertThat(byKey["profile_target_high_mmol"]?.valueDouble)
            .isWithin(0.001).of(UnitConverter.mgdlToMmol(110.0))
        assertThat(byKey["temp_target_low_mmol"]?.valueDouble)
            .isWithin(0.001).of(UnitConverter.mgdlToMmol(72.0))
        assertThat(byKey["temp_target_high_mmol"]?.valueDouble)
            .isWithin(0.001).of(UnitConverter.mgdlToMmol(72.0))
        assertThat(byKey["aaps_temp_target_active"]?.valueDouble).isEqualTo(1.0)
        assertThat(byKey["aaps_temp_target_started_at_ms"]?.valueDouble).isEqualTo(startedAt.toDouble())
        assertThat(byKey["aaps_temp_target_expires_at_ms"]?.valueDouble).isEqualTo(expiresAt.toDouble())
        assertThat(byKey["aaps_temp_target_duration_ms"]?.valueDouble).isEqualTo(1_800_000.0)
        assertThat(byKey["aaps_temp_target_reason"]?.valueText).isEqualTo("Automation")
        assertThat(byKey["aaps_temp_target_id"]?.valueText).isEqualTo("copilot-tt-ack-123")
    }

    @Test
    fun mapsSensorAgeAndWearSignals_fromNightscoutDeviceStatus() {
        val ts = 1_700_000_550_000L
        val samples = TelemetryMetricMapper.fromFlattenedNightscoutDeviceStatus(
            timestamp = ts,
            source = "nightscout_devicestatus",
            flattened = mapOf(
                "cgm.sensorAgeDays" to "9.5",
                "cgm.sageDays" to "9.5",
                "uploader.cageDays" to "3.0"
            )
        )

        val byKey = samples.associateBy { it.key }
        assertThat(byKey["sensor_age_days"]?.valueDouble).isWithin(0.001).of(9.5)
        assertThat(byKey["sensor_age_hours"]?.valueDouble).isWithin(0.001).of(228.0)
        assertThat(byKey["sage_days"]?.valueDouble).isWithin(0.001).of(9.5)
        assertThat(byKey["cage_days"]?.valueDouble).isWithin(0.001).of(3.0)
        assertThat(byKey["sensor_age_source_raw"]?.valueText).isEqualTo("cgm.sensoragedays")
    }

    @Test
    fun derivesSensorAgeHoursFromBroadcastAgeDays() {
        val ts = 1_700_000_560_000L
        val samples = TelemetryMetricMapper.fromKeyValueMap(
            timestamp = ts,
            source = "aaps_broadcast",
            values = mapOf(
                "sensorAgeDays" to "6.5",
                "sageDays" to "6.5",
                "cageDays" to "2.0"
            )
        )

        val byKey = samples.associateBy { it.key }
        assertThat(byKey["sensor_age_days"]?.valueDouble).isWithin(0.001).of(6.5)
        assertThat(byKey["sensor_age_hours"]?.valueDouble).isWithin(0.001).of(156.0)
        assertThat(byKey["sage_days"]?.valueDouble).isWithin(0.001).of(6.5)
        assertThat(byKey["cage_days"]?.valueDouble).isWithin(0.001).of(2.0)
    }

    @Test
    fun derivesSensorAgeFromBroadcastSensorStartedAt() {
        val ts = 1_700_100_000_000L
        val startedAt = ts - (36L * 3_600_000L)
        val samples = TelemetryMetricMapper.fromKeyValueMap(
            timestamp = ts,
            source = "xdrip_broadcast",
            values = mapOf(
                "com.eveningoutpost.dexdrip.Extras.SensorStartedAt" to startedAt.toString()
            )
        )

        val byKey = samples.associateBy { it.key }
        assertThat(byKey["sensor_age_hours"]?.valueDouble).isWithin(0.001).of(36.0)
        assertThat(byKey["sensor_age_days"]?.valueDouble).isWithin(0.001).of(1.5)
        assertThat(byKey["sensor_age_source_raw"]?.valueText)
            .isEqualTo("com.eveningoutpost.dexdrip.Extras.SensorStartedAt")
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
    fun keepsNegativeNetIobSeparateFromPositivePrimaryIob() {
        val ts = 1_700_001_150_000L
        val samples = TelemetryMetricMapper.fromKeyValueMap(
            timestamp = ts,
            source = "xdrip_broadcast",
            values = mapOf("iob" to "-0.72")
        )
        val byKey = samples.associateBy { it.key }
        assertThat(byKey["iob_units"]?.valueDouble).isEqualTo(0.0)
        assertThat(byKey["iob_net_units"]?.valueDouble).isWithin(1e-9).of(-0.72)
    }

    @Test
    fun dropsIobOutsideExtendedNegativeRange() {
        val ts = 1_700_001_160_000L
        val samples = TelemetryMetricMapper.fromKeyValueMap(
            timestamp = ts,
            source = "xdrip_broadcast",
            values = mapOf("iob" to "-45")
        )
        val byKey = samples.associateBy { it.key }
        assertThat(byKey).doesNotContainKey("iob_units")
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

    @Test
    fun rewritesNonPositiveTimestampToWallClockTime() {
        val before = System.currentTimeMillis()
        val samples = TelemetryMetricMapper.fromKeyValueMap(
            timestamp = 0L,
            source = "aaps_broadcast",
            values = mapOf("custom.metric" to "1.2")
        )
        val after = System.currentTimeMillis()
        val sample = samples.first { it.key == "raw_custom_metric" }

        assertThat(sample.timestamp).isAtLeast(before)
        assertThat(sample.timestamp).isAtMost(after)
    }

    @Test
    fun skipsRawSamplesForLocalSensorSource() {
        val ts = 1_700_001_450_000L
        val samples = TelemetryMetricMapper.fromKeyValueMap(
            timestamp = ts,
            source = "local_sensor",
            values = mapOf(
                "steps" to "1234",
                "activeMinutes" to "22",
                "distanceKm" to "1.1"
            )
        )
        val keys = samples.map { it.key }.toSet()
        assertThat(keys).contains("steps_count")
        assertThat(keys).contains("active_minutes")
        assertThat(keys).contains("distance_km")
        assertThat(keys.any { it.startsWith("raw_") }).isFalse()
    }
}
