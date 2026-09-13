package io.aaps.copilot.data.repository

import com.google.gson.Gson
import com.google.common.truth.Truth.assertThat
import io.aaps.copilot.config.SensorLagCorrectionMode
import io.aaps.copilot.data.local.entity.TelemetrySampleEntity
import io.aaps.copilot.data.local.entity.TherapyEventEntity
import io.aaps.copilot.domain.model.DataQuality
import io.aaps.copilot.domain.model.DayType
import io.aaps.copilot.domain.model.Forecast
import io.aaps.copilot.domain.model.GlucosePoint
import io.aaps.copilot.domain.model.ProfileEstimate
import io.aaps.copilot.domain.model.ProfileSegmentEstimate
import io.aaps.copilot.domain.model.ProfileTimeSlot
import io.aaps.copilot.domain.model.ActionProposal
import io.aaps.copilot.domain.model.CircadianDayType
import io.aaps.copilot.domain.model.CircadianForecastPrior
import io.aaps.copilot.domain.model.CircadianReplayBucketStatus
import io.aaps.copilot.domain.model.RuleDecision
import io.aaps.copilot.domain.model.RuleState
import io.aaps.copilot.domain.model.TherapyEvent
import io.aaps.copilot.domain.model.resolveTherapyComponents
import io.aaps.copilot.domain.model.SensorLagAgeSource
import io.aaps.copilot.domain.model.SensorLagEstimate
import io.aaps.copilot.domain.isfcr.IsfCrRealtimeSnapshot
import io.aaps.copilot.domain.isfcr.IsfCrRuntimeMode
import io.aaps.copilot.domain.predict.HybridPredictionEngine
import io.aaps.copilot.domain.predict.InsulinRuntimeSnapshot
import io.aaps.copilot.domain.predict.InsulinRuntimeSource
import io.aaps.copilot.domain.predict.IsfRuntimeSourcePreference
import io.aaps.copilot.domain.predict.MetricRuntimeResolvedSource
import io.aaps.copilot.domain.predict.SensitivityMetricDecision
import io.aaps.copilot.domain.predict.SensitivityResolvedSource
import io.aaps.copilot.domain.predict.SensitivityRuntimeSnapshot
import io.aaps.copilot.domain.predict.UamMode
import io.aaps.copilot.domain.predict.UamTagCodec
import io.aaps.copilot.domain.profile.ActivityCoverage
import io.aaps.copilot.domain.profile.ActivityContextSource
import io.aaps.copilot.domain.profile.ActivityEffectContext
import io.aaps.copilot.domain.profile.ActivityShadowSuppressionReason
import io.aaps.copilot.domain.rules.AdaptiveTempTargetController
import io.aaps.copilot.domain.target.ActiveTargetOwnership
import io.aaps.copilot.domain.target.HorizonReliabilityState
import io.aaps.copilot.domain.target.TargetManagerMode
import kotlinx.coroutines.test.runTest
import org.junit.Test

class AutomationRepositoryForecastBiasTest {

    private val gson = Gson()

    @Test
    fun targetManagerSegmentPreservesLocalRatioAfterAuthoritativeSourceChange() {
        val localProfile = ProfileEstimate(
            isfMmolPerUnit = 6.0,
            crGramPerUnit = 10.0,
            confidence = 0.8,
            sampleCount = 80,
            isfSampleCount = 40,
            crSampleCount = 40,
            lookbackDays = 30
        )
        val authoritativeProfile = localProfile.copy(
            isfMmolPerUnit = 2.0,
            crGramPerUnit = 8.0
        )
        val localSegment = ProfileSegmentEstimate(
            dayType = DayType.WEEKDAY,
            timeSlot = ProfileTimeSlot.MORNING,
            isfMmolPerUnit = 7.2,
            crGramPerUnit = 9.0,
            confidence = 0.7,
            isfSampleCount = 8,
            crSampleCount = 8,
            lookbackDays = 30
        )

        val normalized = AutomationRepository.normalizeSegmentForRuntimeStatic(
            segment = localSegment,
            localProfile = localProfile,
            runtimeProfile = authoritativeProfile
        )

        assertThat(normalized?.isfMmolPerUnit).isWithin(0.0001).of(2.4)
        assertThat(normalized?.crGramPerUnit).isWithin(0.0001).of(7.2)
        assertThat(normalized!!.isfMmolPerUnit!! / authoritativeProfile.isfMmolPerUnit)
            .isWithin(0.0001).of(1.2)
        assertThat(normalized.crGramPerUnit!! / authoritativeProfile.crGramPerUnit)
            .isWithin(0.0001).of(0.9)
    }

    @Test
    fun targetManagerSegmentIsUnavailableWithoutItsLocalGlobalProfile() {
        val segment = ProfileSegmentEstimate(
            dayType = DayType.WEEKDAY,
            timeSlot = ProfileTimeSlot.MORNING,
            isfMmolPerUnit = 7.2,
            crGramPerUnit = null,
            confidence = 0.7,
            isfSampleCount = 8,
            crSampleCount = 0,
            lookbackDays = 30
        )
        val runtimeProfile = ProfileEstimate(
            isfMmolPerUnit = 2.0,
            crGramPerUnit = 8.0,
            confidence = 0.8,
            sampleCount = 80,
            isfSampleCount = 40,
            crSampleCount = 40,
            lookbackDays = 30
        )

        assertThat(
            AutomationRepository.normalizeSegmentForRuntimeStatic(
                segment = segment,
                localProfile = null,
                runtimeProfile = runtimeProfile
            )
        ).isNull()
    }

    @Test
    fun targetManagerSegmentRequiresTheSameProfileGeneration() {
        assertThat(
            AutomationRepository.isMatchingProfileSegmentGenerationStatic(
                profileTimestamp = 10_000L,
                segmentUpdatedAt = 10_000L
            )
        ).isTrue()
        assertThat(
            AutomationRepository.isMatchingProfileSegmentGenerationStatic(
                profileTimestamp = 10_000L,
                segmentUpdatedAt = 10_001L
            )
        ).isFalse()
        assertThat(
            AutomationRepository.isMatchingProfileSegmentGenerationStatic(
                profileTimestamp = null,
                segmentUpdatedAt = 10_000L
            )
        ).isFalse()
    }

    @Test
    fun strictIobSelectionPreservesNegativeAapsNetIob() {
        val selected = AutomationRepository.selectStrictAapsIobStatic(
            listOf(iobSample("signed", 10_000L, "aaps_broadcast", -0.5))
        )

        assertThat(selected?.valueDouble).isEqualTo(-0.5)
    }

    @Test
    fun strictIobSelectionPrefersSignedNetOverPositivePrimaryFromSamePacket() {
        val selected = AutomationRepository.selectStrictAapsIobStatic(
            listOf(
                telemetryRow("primary", 10_000L, "aaps_broadcast", "iob_units", 0.4),
                telemetryRow("net", 10_000L, "aaps_broadcast", "iob_net_units", -0.5)
            )
        )

        assertThat(selected?.key).isEqualTo("iob_net_units")
        assertThat(selected?.valueDouble).isEqualTo(-0.5)
    }

    @Test
    fun newestPartialAapsPacketClearsOlderComponentsInsteadOfMixingCycles() {
        val authoritativeTimestamp = 1_700_000_000_000L
        val latestByKey = mutableMapOf<String, Double?>(
            "iob_units" to 1.0,
            "iob_net_units" to 1.0,
            "iob_bolus_units" to 1.2,
            "iob_basal_units" to -0.2,
            "insulin_activity" to 0.02,
            "iob_relay_timestamp_ms" to 1_700_000_000_000.0,
            "iob_runtime_confidence" to 1.0
        )
        val latestTimestamps = latestByKey.keys.associateWith { 1_000L }.toMutableMap()
        val rows = listOf(
            telemetryRow("old-net", 1_000L, "aaps_broadcast", "iob_net_units", 1.0),
            telemetryRow("new-iob", 2_000L, "aaps_broadcast", "iob_units", 0.0),
            telemetryRow("new-net", 2_000L, "aaps_broadcast", "iob_net_units", -0.5),
            telemetryRow("new-bolus-tombstone", 2_000L, "aaps_broadcast", "iob_bolus_units", null),
            telemetryRow("new-basal-tombstone", 2_000L, "aaps_broadcast", "iob_basal_units", null),
            telemetryRow("new-activity-tombstone", 2_000L, "aaps_broadcast", "insulin_activity", null),
            telemetryRow("new-confidence", 2_000L, "aaps_broadcast", "iob_runtime_confidence", 0.65),
            telemetryRow(
                "new-relay-timestamp",
                2_000L,
                "aaps_broadcast",
                "iob_relay_timestamp_ms",
                authoritativeTimestamp.toDouble()
            ),
            telemetryRow(
                "new-runtime-source",
                2_000L,
                "aaps_broadcast",
                "iob_runtime_source_code",
                1.0
            )
        )

        val packetTs = AutomationRepository.applyAtomicAapsInsulinPacketStatic(
            rows = rows,
            latestByKey = latestByKey,
            latestTimestampByKey = latestTimestamps
        )

        assertThat(packetTs).isEqualTo(authoritativeTimestamp)
        assertThat(latestByKey["iob_units"]).isEqualTo(0.0)
        assertThat(latestByKey["iob_net_units"]).isEqualTo(-0.5)
        assertThat(latestByKey["iob_bolus_units"]).isNull()
        assertThat(latestByKey["iob_basal_units"]).isNull()
        assertThat(latestByKey["insulin_activity"]).isNull()
        assertThat(latestTimestamps["iob_bolus_units"]).isEqualTo(authoritativeTimestamp)
    }

    @Test
    fun stalePartialAapsPacketDoesNotMaskFreshExternalIob() {
        val now = 1_700_001_000_000L
        val stale = now - 20 * 60_000L
        val fresh = now - 60_000L
        listOf("xdrip_broadcast", "nightscout_devicestatus").forEach { externalSource ->
            val rows = listOf(
                telemetryRow("stale-net", stale, "aaps_broadcast", "iob_net_units", 1.8),
                telemetryRow("stale-marker", stale, "aaps_broadcast", "iob_runtime_confidence", 1.0),
                telemetryRow("fresh-$externalSource", fresh, externalSource, "iob_units", 0.7)
            )

            val resolved = AutomationRepository.resolveFreshRuntimeInsulinTelemetryStatic(
                rows = rows,
                nowTs = now,
                freshnessMs = 10 * 60_000L
            )

            assertThat(resolved.snapshot?.source).isEqualTo(InsulinRuntimeSource.EXTERNAL_ESTIMATE)
            assertThat(resolved.snapshot?.netIobUnits).isEqualTo(0.7)
            assertThat(resolved.snapshot?.timestamp).isEqualTo(fresh)
        }
    }

    @Test
    fun freshStandaloneExternalNetIobRemainsValidWithoutRuntimePacketMarkers() {
        val now = 1_700_001_000_000L
        val externalTimestamp = now - 60_000L
        val resolved = AutomationRepository.resolveFreshRuntimeInsulinTelemetryStatic(
            rows = listOf(
                telemetryRow(
                    "external-net",
                    externalTimestamp,
                    "xdrip_broadcast",
                    "iob_net_units",
                    0.7
                )
            ),
            nowTs = now,
            freshnessMs = 10 * 60_000L
        )

        assertThat(resolved.snapshot?.source).isEqualTo(InsulinRuntimeSource.EXTERNAL_ESTIMATE)
        assertThat(resolved.snapshot?.netIobUnits).isEqualTo(0.7)
        assertThat(resolved.snapshot?.timestamp).isEqualTo(externalTimestamp)
    }

    @Test
    fun freshPartialAapsComponentPacketCannotReenterAsLegacyAndMaskExternalIob() {
        val now = 1_700_001_000_000L
        val fresh = now - 60_000L
        val rows = listOf(
            telemetryRow("partial-net", fresh, "aaps_broadcast", "iob_net_units", 1.8),
            telemetryRow("partial-marker", fresh, "aaps_broadcast", "iob_runtime_confidence", 1.0),
            telemetryRow("fresh-external", fresh, "nightscout_devicestatus", "iob_units", 0.7)
        )

        val resolved = AutomationRepository.resolveFreshRuntimeInsulinTelemetryStatic(
            rows = rows,
            nowTs = now,
            freshnessMs = 10 * 60_000L
        )

        assertThat(resolved.snapshot?.source).isEqualTo(InsulinRuntimeSource.EXTERNAL_ESTIMATE)
        assertThat(resolved.snapshot?.netIobUnits).isEqualTo(0.7)
    }

    @Test
    fun everyRuntimePacketRowIsExcludedFromStandaloneExternalFallback() {
        val now = 1_700_001_200_000L
        val oldPacketIngestTs = now - 30_000L
        val invalidLatestPacketIngestTs = now - 10_000L
        val standaloneTs = now - 60_000L
        val rows = listOf(
            telemetryRow("old-packet-net", oldPacketIngestTs, "xdrip_broadcast", "iob_net_units", 2.4),
            telemetryRow(
                "old-packet-source",
                oldPacketIngestTs,
                "xdrip_broadcast",
                "iob_runtime_source_code",
                3.0
            ),
            telemetryRow(
                "old-packet-relay",
                oldPacketIngestTs,
                "xdrip_broadcast",
                "iob_relay_timestamp_ms",
                (now - 4 * 60_000L).toDouble()
            ),
            telemetryRow(
                "latest-invalid-net",
                invalidLatestPacketIngestTs,
                "xdrip_broadcast",
                "iob_net_units",
                3.1
            ),
            telemetryRow(
                "latest-invalid-source",
                invalidLatestPacketIngestTs,
                "xdrip_broadcast",
                "iob_runtime_source_code",
                3.0
            ),
            telemetryRow(
                "latest-invalid-relay",
                invalidLatestPacketIngestTs,
                "xdrip_broadcast",
                "iob_relay_timestamp_ms",
                (now + 60_000L).toDouble()
            ),
            telemetryRow(
                "standalone-external",
                standaloneTs,
                "nightscout_devicestatus",
                "iob_units",
                0.7
            )
        )

        val resolved = AutomationRepository.resolveFreshRuntimeInsulinTelemetryStatic(
            rows = rows,
            nowTs = now,
            freshnessMs = 10 * 60_000L
        )

        assertThat(resolved.snapshot?.source).isEqualTo(InsulinRuntimeSource.EXTERNAL_ESTIMATE)
        assertThat(resolved.snapshot?.netIobUnits).isEqualTo(0.7)
        assertThat(resolved.snapshot?.timestamp).isEqualTo(standaloneTs)
    }

    @Test
    fun mapperRelayTimestampControlsResolverFreshnessAndCausalAlignment() {
        val now = 1_700_001_200_000L
        val relayTimestamp = now - 8 * 60_000L
        val ingestTimestamp = now - 10_000L
        val rows = TelemetryMetricMapper.fromKeyValueMap(
            timestamp = ingestTimestamp,
            observedAtTimestamp = now,
            source = "aaps_broadcast",
            values = mapOf(
                "netIob" to "1.0",
                "bolusIob" to "1.0",
                "basalIob" to "0.0",
                "insulinActivity" to "0.01",
                "iobTimestamp" to relayTimestamp.toString()
            )
        )

        val stale = AutomationRepository.resolveFreshRuntimeInsulinTelemetryStatic(
            rows = rows,
            nowTs = now,
            freshnessMs = 5 * 60_000L
        )
        val accepted = AutomationRepository.resolveFreshRuntimeInsulinTelemetryStatic(
            rows = rows,
            nowTs = now,
            freshnessMs = 10 * 60_000L
        )
        val cycle = AutomationRepository.buildInsulinCycleContextStatic(
            cycleTimestamp = now,
            causalReferenceTimestamp = relayTimestamp,
            insulinSnapshot = accepted.snapshot,
            modeledActiveInsulinUnits = 1.0,
            therapyModelAvailable = true,
            freshnessMs = 10 * 60_000L
        )

        assertThat(rows.first { it.key == "iob_net_units" }.timestamp).isEqualTo(ingestTimestamp)
        assertThat(rows.first { it.key == "iob_relay_timestamp_ms" }.valueDouble)
            .isEqualTo(relayTimestamp.toDouble())
        assertThat(stale.snapshot).isNull()
        assertThat(accepted.snapshot?.source).isEqualTo(InsulinRuntimeSource.AAPS_COMPONENTS)
        assertThat(accepted.snapshot?.timestamp).isEqualTo(relayTimestamp)
        assertThat(cycle.residualComparisonAllowed).isTrue()
        assertThat(cycle.signedResidualUnits).isWithin(1e-12).of(0.0)
    }

    @Test
    fun mapperExplicitLegacyPacketRemainsValidAndUsesRelayTimestamp() {
        val now = 1_700_001_200_000L
        val relayTimestamp = now - 2 * 60_000L
        val rows = TelemetryMetricMapper.fromKeyValueMap(
            timestamp = now - 5_000L,
            observedAtTimestamp = now,
            source = "aaps_broadcast",
            values = mapOf(
                "iob" to "1.25",
                "iobTimestamp" to relayTimestamp.toString()
            )
        )

        val resolved = AutomationRepository.resolveFreshRuntimeInsulinTelemetryStatic(
            rows = rows,
            nowTs = now,
            freshnessMs = 10 * 60_000L
        )

        assertThat(rows.first { it.key == "iob_runtime_source" }.valueText).isEqualTo("LEGACY_AAPS")
        assertThat(resolved.snapshot?.source).isEqualTo(InsulinRuntimeSource.LEGACY_AAPS)
        assertThat(resolved.snapshot?.netIobUnits).isEqualTo(1.25)
        assertThat(resolved.snapshot?.timestamp).isEqualTo(relayTimestamp)
    }

    @Test
    fun mapperIncompleteComponentPacketCannotReenterLegacyAndFreshExternalWins() {
        val now = 1_700_001_200_000L
        val aapsRelayTimestamp = now - 2 * 60_000L
        val externalRelayTimestamp = now - 60_000L
        val aapsRows = TelemetryMetricMapper.fromKeyValueMap(
            timestamp = now - 5_000L,
            observedAtTimestamp = now,
            source = "aaps_broadcast",
            values = mapOf(
                "netIob" to "1.8",
                "bolusIob" to "1.8",
                "basalIob" to "0.0",
                "insulinActivity" to "0.01",
                "iobTimestamp" to aapsRelayTimestamp.toString()
            )
        ).map { row ->
            if (row.key in setOf("iob_bolus_units", "iob_basal_units", "insulin_activity")) {
                row.copy(valueDouble = null)
            } else {
                row
            }
        }
        val externalRows = TelemetryMetricMapper.fromKeyValueMap(
            timestamp = now - 4_000L,
            observedAtTimestamp = now,
            source = "xdrip_broadcast",
            values = mapOf(
                "iob" to "0.7",
                "iobTimestamp" to externalRelayTimestamp.toString()
            )
        )

        val resolved = AutomationRepository.resolveFreshRuntimeInsulinTelemetryStatic(
            rows = aapsRows + externalRows,
            nowTs = now,
            freshnessMs = 10 * 60_000L
        )

        assertThat(aapsRows.first { it.key == "iob_runtime_source" }.valueText)
            .isEqualTo("AAPS_COMPONENTS")
        assertThat(resolved.snapshot?.source).isEqualTo(InsulinRuntimeSource.EXTERNAL_ESTIMATE)
        assertThat(resolved.snapshot?.netIobUnits).isEqualTo(0.7)
        assertThat(resolved.snapshot?.timestamp).isEqualTo(externalRelayTimestamp)
    }

    @Test
    fun componentPacketWithMissingOrInvalidAuthoritativeMarkerFailsClosed() {
        val now = 1_700_001_200_000L
        val validAapsRows = TelemetryMetricMapper.fromKeyValueMap(
            timestamp = now - 5_000L,
            observedAtTimestamp = now,
            source = "aaps_broadcast",
            values = mapOf(
                "netIob" to "1.8",
                "bolusIob" to "1.8",
                "basalIob" to "0.0",
                "insulinActivity" to "0.01",
                "iobTimestamp" to (now - 60_000L).toString()
            )
        )
        val externalRelayTimestamp = now - 30_000L
        val externalRows = TelemetryMetricMapper.fromKeyValueMap(
            timestamp = now - 4_000L,
            observedAtTimestamp = now,
            source = "xdrip_broadcast",
            values = mapOf(
                "iob" to "0.6",
                "iobTimestamp" to externalRelayTimestamp.toString()
            )
        )
        val invalidPackets = listOf(
            validAapsRows.filterNot { it.key == "iob_relay_timestamp_ms" },
            validAapsRows.map { row ->
                if (row.key == "iob_relay_timestamp_ms") row.copy(valueDouble = Double.NaN) else row
            },
            validAapsRows.map { row ->
                if (row.key == "iob_relay_timestamp_ms") {
                    row.copy(valueDouble = (now - 60_000L).toDouble() + 0.5)
                } else {
                    row
                }
            },
            validAapsRows.map { row ->
                if (row.key == "iob_relay_timestamp_ms") row.copy(valueDouble = (now + 60_000L).toDouble()) else row
            }
        )

        invalidPackets.forEach { aapsRows ->
            val resolved = AutomationRepository.resolveFreshRuntimeInsulinTelemetryStatic(
                rows = aapsRows + externalRows,
                nowTs = now,
                freshnessMs = 10 * 60_000L
            )

            assertThat(resolved.snapshot?.source).isEqualTo(InsulinRuntimeSource.EXTERNAL_ESTIMATE)
            assertThat(resolved.snapshot?.timestamp).isEqualTo(externalRelayTimestamp)
        }
    }

    @Test
    fun mapperAapsPacketWithoutAuthoritativeTimestampFailsClosedEndToEnd() {
        val now = 1_700_001_200_000L
        val ingestTimestamp = now - 5_000L
        val rows = TelemetryMetricMapper.fromKeyValueMap(
            timestamp = ingestTimestamp,
            observedAtTimestamp = now,
            source = "aaps_broadcast",
            values = mapOf(
                "iob" to "1.0",
                "netIob" to "1.0",
                "bolusIob" to "1.0",
                "basalIob" to "0.0",
                "insulinActivity" to "0.01"
            )
        )

        val resolved = AutomationRepository.resolveFreshRuntimeInsulinTelemetryStatic(
            rows = rows,
            nowTs = now,
            freshnessMs = 10 * 60_000L
        )

        assertThat(rows.firstOrNull { it.key == "iob_runtime_source" }?.valueText).isNull()
        assertThat(rows.firstOrNull { it.key == "iob_relay_timestamp_ms" }?.valueDouble).isNull()
        assertThat(resolved.snapshot).isNull()
    }

    @Test
    fun mapperFractionalAapsAuthoritativeTimestampFailsClosedEndToEnd() {
        val now = 1_700_001_200_000L
        val rows = TelemetryMetricMapper.fromKeyValueMap(
            timestamp = now - 5_000L,
            observedAtTimestamp = now,
            source = "aaps_broadcast",
            values = mapOf(
                "netIob" to "1.0",
                "bolusIob" to "1.0",
                "basalIob" to "0.0",
                "insulinActivity" to "0.01",
                "iobTimestamp" to "${now - 60_000L}.5"
            )
        )

        val resolved = AutomationRepository.resolveFreshRuntimeInsulinTelemetryStatic(
            rows = rows,
            nowTs = now,
            freshnessMs = 10 * 60_000L
        )

        assertThat(rows.firstOrNull { it.key == "iob_runtime_source" }?.valueText).isNull()
        assertThat(rows.firstOrNull { it.key == "iob_relay_timestamp_ms" }?.valueDouble).isNull()
        assertThat(resolved.snapshot).isNull()
    }

    @Test
    fun mapperOldAapsIobReingestedNowRemainsStaleEndToEnd() {
        val now = 1_700_001_200_000L
        val ingestTimestamp = now - 5_000L
        val sourceTimestamp = now - 20 * 60_000L
        val rows = TelemetryMetricMapper.fromKeyValueMap(
            timestamp = ingestTimestamp,
            observedAtTimestamp = now,
            source = "aaps_broadcast",
            values = mapOf(
                "netIob" to "1.0",
                "bolusIob" to "1.0",
                "basalIob" to "0.0",
                "insulinActivity" to "0.01",
                "iobTimestamp" to sourceTimestamp.toString()
            )
        )

        val resolved = AutomationRepository.resolveFreshRuntimeInsulinTelemetryStatic(
            rows = rows,
            nowTs = now,
            freshnessMs = 10 * 60_000L
        )

        assertThat(rows).isNotEmpty()
        assertThat(rows.map { it.timestamp }.distinct()).containsExactly(ingestTimestamp)
        assertThat(rows.firstOrNull { it.key == "iob_relay_timestamp_ms" }?.valueDouble).isNull()
        assertThat(resolved.snapshot).isNull()
    }

    @Test
    fun mapperFutureAapsAuthoritativeTimestampFailsClosedEndToEnd() {
        val now = 1_700_001_200_000L
        val rows = TelemetryMetricMapper.fromKeyValueMap(
            timestamp = now - 5_000L,
            observedAtTimestamp = now,
            source = "aaps_broadcast",
            values = mapOf(
                "netIob" to "1.0",
                "bolusIob" to "1.0",
                "basalIob" to "0.0",
                "insulinActivity" to "0.01",
                "iobTimestamp" to (now + 60_000L).toString()
            )
        )

        val resolved = AutomationRepository.resolveFreshRuntimeInsulinTelemetryStatic(
            rows = rows,
            nowTs = now,
            freshnessMs = 10 * 60_000L
        )

        assertThat(rows.firstOrNull { it.key == "iob_runtime_source" }?.valueText).isNull()
        assertThat(rows.firstOrNull { it.key == "iob_relay_timestamp_ms" }?.valueDouble).isNull()
        assertThat(resolved.snapshot).isNull()
    }

    @Test
    fun mapperNonfiniteNonpositiveOrPreEpochAapsTimestampFailsClosedEndToEnd() {
        val now = 1_700_001_200_000L
        listOf("NaN", "0", "999999999999").forEach { invalidTimestamp ->
            val rows = TelemetryMetricMapper.fromKeyValueMap(
                timestamp = now - 5_000L,
                observedAtTimestamp = now,
                source = "aaps_broadcast",
                values = mapOf(
                    "netIob" to "1.0",
                    "bolusIob" to "1.0",
                    "basalIob" to "0.0",
                    "insulinActivity" to "0.01",
                    "iobTimestamp" to invalidTimestamp
                )
            )

            val resolved = AutomationRepository.resolveFreshRuntimeInsulinTelemetryStatic(
                rows = rows,
                nowTs = now,
                freshnessMs = 10 * 60_000L
            )

            assertThat(rows.firstOrNull { it.key == "iob_runtime_source" }?.valueText).isNull()
            assertThat(rows.firstOrNull { it.key == "iob_relay_timestamp_ms" }?.valueDouble).isNull()
            assertThat(resolved.snapshot).isNull()
        }
    }

    @Test
    fun mapperExternalLooseScalarUsesItsSourceObservationTimestampEndToEnd() {
        val now = 1_700_001_200_000L
        val sourceObservationTimestamp = now - 60_000L
        val rows = TelemetryMetricMapper.fromKeyValueMap(
            timestamp = sourceObservationTimestamp,
            observedAtTimestamp = now,
            source = "xdrip_broadcast",
            values = mapOf("iob" to "0.7")
        )

        val resolved = AutomationRepository.resolveFreshRuntimeInsulinTelemetryStatic(
            rows = rows,
            nowTs = now,
            freshnessMs = 10 * 60_000L
        )

        assertThat(rows.first { it.key == "iob_runtime_source" }.valueText)
            .isEqualTo("EXTERNAL_ESTIMATE")
        assertThat(rows.first { it.key == "iob_relay_timestamp_ms" }.valueDouble)
            .isEqualTo(sourceObservationTimestamp.toDouble())
        assertThat(resolved.snapshot?.source).isEqualTo(InsulinRuntimeSource.EXTERNAL_ESTIMATE)
        assertThat(resolved.snapshot?.timestamp).isEqualTo(sourceObservationTimestamp)
    }

    @Test
    fun targetManagerRejectsUnknownIobWhenNoQualifiedSnapshotExists() {
        assertThat(
            AutomationRepository.resolveTargetManagerSafetyIobStatic(
                insulinSnapshot = null,
                cycleTimestamp = 1_700_000_120_000L,
                freshnessMs = 15 * 60_000L
            )
        ).isNull()
    }

    @Test
    fun localSnapshotsRemainDiagnosticOnlyRegardlessOfSyntheticCoverage() {
        val now = 1_700_000_120_000L
        val unsupported = InsulinRuntimeSnapshot(
            timestamp = now,
            source = InsulinRuntimeSource.LOCAL_ESTIMATE,
            netIobUnits = 0.0,
            bolusIobUnits = null,
            basalIobUnits = null,
            insulinActivity = null,
            effectivePositiveIobUnits = 0.0,
            confidence = 0.20,
            fallbackReason = "local",
            evidenceTimestamp = now - 60_000L,
            therapyCoverage = 0.20
        )
        val covered = unsupported.copy(
            netIobUnits = 0.8,
            effectivePositiveIobUnits = 0.8,
            confidence = 0.75,
            therapyCoverage = 0.85
        )

        val unsupportedQuality = AutomationRepository.resolveUamRuntimeQualityStatic(
            sensorQualityScore = 1.0,
            sensorBlocked = false,
            nowTs = now,
            effectiveDiaHours = 5.0,
            insulinEvidenceTimestamps = listOf(now - 60_000L),
            iobSampleTs = now,
            iobUnits = 0.0,
            effectiveCobGrams = 0.0,
            externalCobGrams = null,
            carbTherapyAvailable = false,
            insulinSnapshot = unsupported
        )
        val coveredQuality = AutomationRepository.resolveUamRuntimeQualityStatic(
            sensorQualityScore = 1.0,
            sensorBlocked = false,
            nowTs = now,
            effectiveDiaHours = 5.0,
            insulinEvidenceTimestamps = listOf(now - 60_000L),
            iobSampleTs = now,
            iobUnits = 0.8,
            effectiveCobGrams = 0.0,
            externalCobGrams = null,
            carbTherapyAvailable = false,
            insulinSnapshot = covered
        )

        assertThat(unsupportedQuality.therapyCoverage).isEqualTo(0.0)
        assertThat(unsupportedQuality.reasons).contains("iob_snapshot_unqualified")
        assertThat(
            AutomationRepository.resolveTargetManagerSafetyIobStatic(unsupported, now, 15 * 60_000L)
        ).isNull()
        assertThat(coveredQuality.therapyCoverage).isEqualTo(0.0)
        assertThat(coveredQuality.reasons).contains("iob_snapshot_unqualified")
        assertThat(AutomationRepository.isInsulinSnapshotSafetyQualifiedStatic(covered)).isFalse()
        assertThat(
            AutomationRepository.resolveTargetManagerSafetyIobStatic(covered, now, 15 * 60_000L)
        ).isNull()
    }

    @Test
    fun externalSnapshotMustPassSourceAwareSafetyQualificationForTargetManager() {
        val now = 1_700_000_120_000L
        val lowQuality = InsulinRuntimeSnapshot(
            timestamp = now,
            source = InsulinRuntimeSource.EXTERNAL_ESTIMATE,
            netIobUnits = 0.8,
            bolusIobUnits = null,
            basalIobUnits = null,
            insulinActivity = null,
            effectivePositiveIobUnits = 0.8,
            confidence = 0.4,
            fallbackReason = "external",
            evidenceTimestamp = now - 60_000L,
            therapyCoverage = 0.5
        )
        val qualified = lowQuality.copy(confidence = 0.8, therapyCoverage = 0.8)

        assertThat(AutomationRepository.isInsulinSnapshotSafetyQualifiedStatic(lowQuality)).isFalse()
        assertThat(
            AutomationRepository.resolveTargetManagerSafetyIobStatic(lowQuality, now, 15 * 60_000L)
        ).isNull()
        assertThat(AutomationRepository.isInsulinSnapshotSafetyQualifiedStatic(qualified)).isTrue()
        assertThat(
            AutomationRepository.resolveTargetManagerSafetyIobStatic(qualified, now, 15 * 60_000L)
        ).isEqualTo(0.8)
    }

    @Test
    fun oneCycleContextRejectsMissingAndUnqualifiedIobButAcceptsFreshQualifiedSnapshot() {
        val cycleTs = 1_700_000_600_000L
        val causalTs = cycleTs - 8 * 60_000L
        fun snapshot(source: InsulinRuntimeSource, confidence: Double, coverage: Double) =
            InsulinRuntimeSnapshot(
                timestamp = causalTs,
                source = source,
                netIobUnits = 0.8,
                bolusIobUnits = if (source == InsulinRuntimeSource.AAPS_COMPONENTS) 0.8 else null,
                basalIobUnits = if (source == InsulinRuntimeSource.AAPS_COMPONENTS) 0.0 else null,
                insulinActivity = if (source == InsulinRuntimeSource.AAPS_COMPONENTS) 0.01 else null,
                effectivePositiveIobUnits = 0.8,
                confidence = confidence,
                fallbackReason = source.name,
                evidenceTimestamp = causalTs,
                therapyCoverage = coverage
            )

        val missing = AutomationRepository.buildInsulinCycleContextStatic(
            cycleTimestamp = cycleTs,
            causalReferenceTimestamp = causalTs,
            insulinSnapshot = null,
            modeledActiveInsulinUnits = 0.8,
            therapyModelAvailable = true,
            freshnessMs = 15 * 60_000L
        )
        val weakExternal = AutomationRepository.buildInsulinCycleContextStatic(
            cycleTimestamp = cycleTs,
            causalReferenceTimestamp = causalTs,
            insulinSnapshot = snapshot(InsulinRuntimeSource.EXTERNAL_ESTIMATE, 0.4, 0.5),
            modeledActiveInsulinUnits = 0.8,
            therapyModelAvailable = true,
            freshnessMs = 15 * 60_000L
        )
        val weakLocal = AutomationRepository.buildInsulinCycleContextStatic(
            cycleTimestamp = cycleTs,
            causalReferenceTimestamp = causalTs,
            insulinSnapshot = snapshot(InsulinRuntimeSource.LOCAL_ESTIMATE, 0.4, 0.5),
            modeledActiveInsulinUnits = 0.8,
            therapyModelAvailable = true,
            freshnessMs = 15 * 60_000L
        )
        val qualified = AutomationRepository.buildInsulinCycleContextStatic(
            cycleTimestamp = cycleTs,
            causalReferenceTimestamp = causalTs,
            insulinSnapshot = snapshot(InsulinRuntimeSource.AAPS_COMPONENTS, 1.0, 1.0),
            modeledActiveInsulinUnits = 0.8,
            therapyModelAvailable = true,
            freshnessMs = 15 * 60_000L
        )

        assertThat(missing.safetyIobUnits).isNull()
        assertThat(weakExternal.safetyIobUnits).isNull()
        assertThat(weakLocal.safetyIobUnits).isNull()
        assertThat(qualified.safetyIobUnits).isEqualTo(0.8)
    }

    @Test
    fun uamQualityUsesSnapshotTimestampAndEffectivePositiveIob() {
        val now = 1_700_000_120_000L
        val snapshot = InsulinRuntimeSnapshot(
            timestamp = now - 60_000L,
            source = InsulinRuntimeSource.AAPS_COMPONENTS,
            netIobUnits = -0.5,
            bolusIobUnits = 0.2,
            basalIobUnits = -0.7,
            insulinActivity = 0.01,
            effectivePositiveIobUnits = 0.2,
            confidence = 1.0,
            fallbackReason = null,
            evidenceTimestamp = now - 60_000L,
            therapyCoverage = 1.0
        )

        val quality = AutomationRepository.resolveUamRuntimeQualityStatic(
            sensorQualityScore = 1.0,
            sensorBlocked = false,
            nowTs = now,
            effectiveDiaHours = 5.0,
            insulinEvidenceTimestamps = emptyList(),
            iobSampleTs = now - 24 * 60 * 60_000L,
            iobUnits = 2.0,
            effectiveCobGrams = 0.0,
            externalCobGrams = null,
            carbTherapyAvailable = false,
            insulinSnapshot = snapshot
        )

        assertThat(quality.therapyCoverage).isEqualTo(1.0)
        assertThat(quality.reasons).doesNotContain("iob_sample_missing_or_stale")
        assertThat(quality.reasons).doesNotContain("iob_without_causal_insulin")
    }

    @Test
    fun componentIobFallbackUsesSignedNetWithoutDoubleCountingBasal() {
        val forecasts = listOf(
            Forecast(10_000L, 5, 6.0, 5.5, 6.5, "base"),
            Forecast(10_000L, 30, 6.0, 5.5, 6.5, "base"),
            Forecast(10_000L, 60, 6.0, 5.5, 6.5, "base")
        )
        val positiveBolus = InsulinRuntimeSnapshot(
            timestamp = 10_000L,
            source = InsulinRuntimeSource.AAPS_COMPONENTS,
            netIobUnits = 0.4,
            bolusIobUnits = 0.4,
            basalIobUnits = 0.0,
            insulinActivity = 0.0,
            effectivePositiveIobUnits = 0.4,
            confidence = 1.0,
            fallbackReason = null
        )
        val basalDeficit = positiveBolus.copy(
            netIobUnits = -0.5,
            basalIobUnits = -0.9
        )

        val bolusAdjusted = AutomationRepository.applyCobIobForecastBiasStatic(
            forecasts = forecasts,
            cobGrams = 0.0,
            diagnosticIobUnits = null,
            insulinCycleContext = insulinCycleContext(positiveBolus),
            isfMmolPerUnit = 3.0
        )
        val deficitAdjusted = AutomationRepository.applyCobIobForecastBiasStatic(
            forecasts = forecasts,
            cobGrams = 0.0,
            diagnosticIobUnits = null,
            insulinCycleContext = insulinCycleContext(basalDeficit),
            isfMmolPerUnit = 3.0
        )

        assertThat(bolusAdjusted.last().valueMmol).isLessThan(6.0)
        assertThat(deficitAdjusted.last().valueMmol).isGreaterThan(bolusAdjusted.last().valueMmol)
        assertThat(deficitAdjusted.last().modelVersion).contains("component_iob_residual_v3")
    }

    @Test
    fun componentMatchingLocallyModeledIobAddsNoSecondInsulinCorrection() {
        val forecasts = listOf(Forecast(10_000L, 60, 6.0, 5.5, 6.5, "therapy_model"))
        val snapshot = InsulinRuntimeSnapshot(
            timestamp = 10_000L,
            source = InsulinRuntimeSource.AAPS_COMPONENTS,
            netIobUnits = 1.2,
            bolusIobUnits = 1.2,
            basalIobUnits = 0.0,
            insulinActivity = 0.02,
            effectivePositiveIobUnits = 1.2,
            confidence = 1.0,
            fallbackReason = null
        )

        val adjusted = AutomationRepository.applyCobIobForecastBiasStatic(
            forecasts = forecasts,
            cobGrams = 0.0,
            diagnosticIobUnits = 1.2,
            insulinCycleContext = insulinCycleContext(snapshot, modeledIobUnits = 1.2),
            isfMmolPerUnit = 3.0,
        )

        assertThat(adjusted).isEqualTo(forecasts)
        assertThat(snapshot.effectivePositiveIobUnits).isEqualTo(1.2)
    }

    @Test
    fun authoritativeComponentAppliesOnlyResidualAboveLocallyModeledIob() {
        val forecasts = listOf(Forecast(10_000L, 60, 7.0, 6.5, 7.5, "therapy_model"))
        val snapshot = InsulinRuntimeSnapshot(
            timestamp = 10_000L,
            source = InsulinRuntimeSource.AAPS_COMPONENTS,
            netIobUnits = 1.5,
            bolusIobUnits = 1.5,
            basalIobUnits = 0.0,
            insulinActivity = 0.01,
            effectivePositiveIobUnits = 1.5,
            confidence = 1.0,
            fallbackReason = null,
            evidenceTimestamp = 10_000L,
            therapyCoverage = 1.0
        )

        val residual = AutomationRepository.applyCobIobForecastBiasStatic(
            forecasts, 0.0, 1.5,
            insulinCycleContext = insulinCycleContext(snapshot, modeledIobUnits = 1.0),
            isfMmolPerUnit = 3.0,
        ).single()
        val fallback = AutomationRepository.applyCobIobForecastBiasStatic(
            forecasts, 0.0, 1.5,
            insulinCycleContext = insulinCycleContext(snapshot, therapyModelAvailable = false),
            isfMmolPerUnit = 3.0,
        ).single()

        assertThat(residual.valueMmol).isLessThan(7.0)
        assertThat(residual.valueMmol).isGreaterThan(fallback.valueMmol)
        assertThat(residual.modelVersion).contains("component_iob_residual_v3")
        assertThat(
            AutomationRepository.resolveTargetManagerSafetyIobStatic(
                snapshot,
                cycleTimestamp = snapshot.timestamp,
                freshnessMs = 15 * 60_000L
            )
        ).isEqualTo(1.5)
    }

    @Test
    fun negativeBasalIsRepresentedOnceBySignedNetResidual() {
        val forecasts = listOf(Forecast(10_000L, 60, 6.0, 5.5, 6.5, "therapy_model"))
        val first = InsulinRuntimeSnapshot(
            timestamp = 10_000L,
            source = InsulinRuntimeSource.AAPS_COMPONENTS,
            netIobUnits = -0.5,
            bolusIobUnits = 0.4,
            basalIobUnits = -0.9,
            insulinActivity = 0.0,
            effectivePositiveIobUnits = 0.4,
            confidence = 1.0,
            fallbackReason = null
        )
        val sameNetDifferentComponents = first.copy(
            bolusIobUnits = 0.9,
            basalIobUnits = -1.4,
            effectivePositiveIobUnits = 0.9
        )

        val firstAdjusted = AutomationRepository.applyCobIobForecastBiasStatic(
            forecasts, 0.0, 0.4,
            insulinCycleContext = insulinCycleContext(first, modeledIobUnits = 0.4),
            isfMmolPerUnit = 3.0,
        )
        val secondAdjusted = AutomationRepository.applyCobIobForecastBiasStatic(
            forecasts, 0.0, 0.9,
            insulinCycleContext = insulinCycleContext(sameNetDifferentComponents, modeledIobUnits = 0.4),
            isfMmolPerUnit = 3.0,
        )

        assertThat(secondAdjusted).isEqualTo(firstAdjusted)
        assertThat(firstAdjusted.single().valueMmol).isGreaterThan(6.0)
    }

    @Test
    fun noTherapyModelUsesSingleBoundedComponentFallback() {
        val forecast = Forecast(10_000L, 60, 20.0, 19.0, 21.0, "no_therapy_model")
        val snapshot = InsulinRuntimeSnapshot(
            timestamp = 10_000L,
            source = InsulinRuntimeSource.AAPS_COMPONENTS,
            netIobUnits = 30.0,
            bolusIobUnits = 30.0,
            basalIobUnits = 0.0,
            insulinActivity = 5.0,
            effectivePositiveIobUnits = 30.0,
            confidence = 1.0,
            fallbackReason = null
        )

        val adjusted = AutomationRepository.applyCobIobForecastBiasStatic(
            listOf(forecast), 0.0, 30.0,
            insulinCycleContext = insulinCycleContext(snapshot, therapyModelAvailable = false),
            isfMmolPerUnit = 18.0,
        ).single()

        assertThat(adjusted.valueMmol).isAtLeast(18.5)
        assertThat(adjusted.valueMmol).isAtMost(20.0)
    }

    @Test
    fun insulinActivityChangesForecastImmediatelyAndIsBounded() {
        val forecasts = listOf(Forecast(10_000L, 60, 7.0, 6.0, 8.0, "base"))
        val noActivity = InsulinRuntimeSnapshot(
            timestamp = 10_000L,
            source = InsulinRuntimeSource.AAPS_COMPONENTS,
            netIobUnits = 1.0,
            bolusIobUnits = 1.0,
            basalIobUnits = 0.0,
            insulinActivity = 0.0,
            effectivePositiveIobUnits = 1.0,
            confidence = 1.0,
            fallbackReason = null
        )
        val active = noActivity.copy(insulinActivity = 0.012)

        val baseline = AutomationRepository.applyCobIobForecastBiasStatic(
            forecasts, 0.0, null,
            insulinCycleContext = insulinCycleContext(noActivity, therapyModelAvailable = false),
            isfMmolPerUnit = 3.0
        )
        val adjusted = AutomationRepository.applyCobIobForecastBiasStatic(
            forecasts, 0.0, null,
            insulinCycleContext = insulinCycleContext(active, therapyModelAvailable = false),
            isfMmolPerUnit = 3.0
        )

        assertThat(adjusted.single().valueMmol).isLessThan(baseline.single().valueMmol)
        assertThat(adjusted.single().valueMmol).isAtLeast(2.2)
    }

    @Test
    fun nonFiniteComponentAdjustmentCannotProduceNanOrEscapeGlucoseBounds() {
        val snapshot = InsulinRuntimeSnapshot(
            timestamp = 10_000L,
            source = InsulinRuntimeSource.AAPS_COMPONENTS,
            netIobUnits = Double.NaN,
            bolusIobUnits = 1.0,
            basalIobUnits = -1.0,
            insulinActivity = Double.NaN,
            effectivePositiveIobUnits = 1.0,
            confidence = 1.0,
            fallbackReason = null
        )

        val adjusted = AutomationRepository.applyCobIobForecastBiasStatic(
            forecasts = listOf(Forecast(10_000L, 60, 39.9, 39.8, 40.0, "base")),
            cobGrams = 0.0,
            diagnosticIobUnits = 1.0,
            insulinCycleContext = insulinCycleContext(snapshot, therapyModelAvailable = false),
            isfMmolPerUnit = Double.NaN
        ).single()

        assertThat(adjusted.valueMmol.isFinite()).isTrue()
        assertThat(adjusted.ciLow.isFinite()).isTrue()
        assertThat(adjusted.ciHigh.isFinite()).isTrue()
        assertThat(adjusted.valueMmol).isAtMost(40.0)
        assertThat(adjusted.ciLow).isAtMost(adjusted.valueMmol)
        assertThat(adjusted.ciHigh).isAtLeast(adjusted.valueMmol)
    }

    @Test
    fun cancelledPlannedOccurrenceRequestsReturnEvenWhenMeasuredActivityIsCurrent() {
        assertThat(
            AutomationRepository.activityReturnToBaseRequestedStatic(
                plannedActivityTargetOccurrence = null,
                activityContextSource = ActivityContextSource.MEASURED
            )
        ).isEqualTo(true)
    }

    @Test
    fun physicalActivityMeasurementRejectsAapsAutosensitivityTelemetry() {
        val nowTs = 1_000_000L
        val aaps = telemetryRow(
            id = "autosens",
            timestamp = nowTs - 60_000L,
            source = "aaps_broadcast",
            key = "activity_ratio",
            value = 1.35
        )
        val healthConnect = telemetryRow(
            id = "health-connect",
            timestamp = nowTs - 60_000L,
            source = "health_connect",
            key = "activity_ratio",
            value = 1.35
        )
        val localSensor = telemetryRow(
            id = "local-sensor",
            timestamp = nowTs - 60_000L,
            source = "local_sensor",
            key = "activity_ratio",
            value = 1.30
        )
        val nightscout = telemetryRow(
            id = "nightscout",
            timestamp = nowTs - 60_000L,
            source = "nightscout",
            key = "activity_ratio",
            value = 1.35
        )

        assertThat(AutomationRepository.physicalActivityMeasurementStatic(aaps, nowTs).coverage)
            .isEqualTo(ActivityCoverage.UNKNOWN)
        assertThat(AutomationRepository.physicalActivityMeasurementStatic(nightscout, nowTs).coverage)
            .isEqualTo(ActivityCoverage.UNKNOWN)
        assertThat(AutomationRepository.physicalActivityMeasurementStatic(healthConnect, nowTs).coverage)
            .isEqualTo(ActivityCoverage.AVAILABLE)
        assertThat(AutomationRepository.physicalActivityMeasurementStatic(localSensor, nowTs).activityRatio)
            .isEqualTo(1.30)
        assertThat(
            AutomationRepository.physicalActivityMeasurementStatic(
                healthConnect.copy(quality = "STALE"),
                nowTs
            ).coverage
        ).isEqualTo(ActivityCoverage.UNKNOWN)
    }

    @Test
    fun nonIdentityControlActivityFactorSuppressesShadowAndLeavesControlByteIdentical() {
        val controlSource = sampleForecasts()
        val localMeasurement = AutomationRepository.physicalActivityMeasurementStatic(
            sample = telemetryRow(
                id = "local-sensor",
                timestamp = 999_000L,
                source = "local_sensor",
                key = "activity_ratio",
                value = 1.30
            ),
            nowTs = 1_000_000L
        )
        assertThat(localMeasurement.coverage).isEqualTo(ActivityCoverage.AVAILABLE)
        val isfCrActivityTelemetry = mapOf(
            "isf_factor_activity_factor" to 1.10,
            "activity_factor" to 1.10
        )
        val control = AutomationRepository.applyContextFactorForecastBiasStatic(
            forecasts = controlSource,
            telemetry = isfCrActivityTelemetry,
            latestGlucoseMmol = 7.0,
            pattern = null
        )
        val shadowPlan = AutomationRepository.resolveActivityForecastPlanStatic(
            controlForecasts = control,
            context = ActivityEffectContext(
                source = ActivityContextSource.MEASURED,
                factor5 = localMeasurement.activityRatio!!,
                factor30 = localMeasurement.activityRatio!!,
                factor60 = localMeasurement.activityRatio!!,
                ciWidthMultiplier30 = 1.0,
                ciWidthMultiplier60 = 1.0,
                confidence = 0.85,
                blockers = emptySet()
            ),
            existingControlActivityFactor = AutomationRepository.resolveContextActivityFactorStatic(
                isfCrActivityTelemetry
            )
        )

        assertThat(shadowPlan.controlForecasts).isSameInstanceAs(control)
        assertThat(gson.toJson(shadowPlan.controlForecasts)).isEqualTo(gson.toJson(control))
        assertThat(gson.toJson(control)).isNotEqualTo(gson.toJson(controlSource))
        assertThat(shadowPlan.shadowForecasts).isNull()
        assertThat(shadowPlan.suppressionReasons).containsExactly(
            ActivityShadowSuppressionReason.UNPROVEN_PREEXISTING_CONTROL_ACTIVITY_FACTOR
        )
    }

    @Test
    fun disabledActivityInfluenceCreatesNoShadowAndCannotDisplaceControlForecasts() {
        val controlSource = sampleForecasts()

        val shadowPlan = AutomationRepository.resolveActivityForecastPlanStatic(
            controlForecasts = controlSource,
            context = ActivityEffectContext.DISABLED
        )

        assertThat(shadowPlan.controlForecasts).isSameInstanceAs(controlSource)
        assertThat(gson.toJson(shadowPlan.controlForecasts)).isEqualTo(gson.toJson(controlSource))
        assertThat(shadowPlan.shadowForecasts).isNull()
    }

    @Test
    fun identityOrAbsentControlActivityFactorRetainsNumericShadow() {
        val control = sampleForecasts()
        val context = ActivityEffectContext(
            source = ActivityContextSource.MEASURED,
            factor5 = 1.30,
            factor30 = 1.30,
            factor60 = 1.30,
            ciWidthMultiplier30 = 1.0,
            ciWidthMultiplier60 = 1.0,
            confidence = 0.85,
            blockers = emptySet()
        )

        val identityPlan = AutomationRepository.resolveActivityForecastPlanStatic(
            controlForecasts = control,
            context = context,
            existingControlActivityFactor = 1.0
        )
        val absentPlan = AutomationRepository.resolveActivityForecastPlanStatic(
            controlForecasts = control,
            context = context,
            existingControlActivityFactor = null
        )

        listOf(identityPlan, absentPlan).forEach { plan ->
            assertThat(plan.controlForecasts).isSameInstanceAs(control)
            assertThat(gson.toJson(plan.controlForecasts)).isEqualTo(gson.toJson(control))
            assertThat(plan.suppressionReasons).isEmpty()
            assertThat(plan.shadowForecasts).isNotNull()
            assertThat(plan.shadowForecasts).isNotEqualTo(control)
        }
    }

    @Test
    fun iobChangeRecomputesAllHorizonsInSameCycle() {
        val source = sampleForecasts()
        val lowIob = AutomationRepository.applyCobIobForecastBiasStatic(
            forecasts = source,
            cobGrams = 0.0,
            diagnosticIobUnits = 0.5,
            insulinCycleContext = insulinCycleContext(componentSnapshot(0.5), therapyModelAvailable = false),
            latestGlucoseMmol = 7.0
        )
        val highIob = AutomationRepository.applyCobIobForecastBiasStatic(
            forecasts = source,
            cobGrams = 0.0,
            diagnosticIobUnits = 2.5,
            insulinCycleContext = insulinCycleContext(componentSnapshot(2.5), therapyModelAvailable = false),
            latestGlucoseMmol = 7.0
        )

        listOf(5, 30, 60).forEach { horizon ->
            val lowValue = lowIob.single { it.horizonMinutes == horizon }.valueMmol
            val highValue = highIob.single { it.horizonMinutes == horizon }.valueMmol
            assertThat(highValue).isNotEqualTo(lowValue)
            assertThat(highValue).isLessThan(lowValue)
        }
    }

    @Test
    fun dryRunTherapyMappingUsesBoundedTypedMapper() {
        val event = AutomationRepository.mapDryRunTherapyEventStatic(
            row = TherapyEventEntity(
                id = "dry-run-bounded",
                timestamp = 100_000L,
                type = "meal_bolus",
                payloadJson =
                    """{"units":3,"carbs":40,"isValid":false,"confidence":0.82,"deltaIob":-0.4,"recoverySource":"nightscout","__copilotCanonicalCarbSemanticConflict":true,"nested":{"value":1},"samples":[1,2],"missing":null}"""
            ),
            gson = gson
        )

        assertThat(event.payload).containsAtLeast(
            "units", "3",
            "confidence", "0.82",
            "deltaIob", "-0.4",
            "recoverySource", "nightscout"
        )
        assertThat(event.payload).doesNotContainKey("nested")
        assertThat(event.payload).doesNotContainKey("samples")
        assertThat(event.payload).doesNotContainKey("missing")
        val components = resolveTherapyComponents(event)
        assertThat(components.canonicalCarbAuthoritative).isFalse()
        assertThat(components.insulinU).isNull()
        assertThat(components.carbsG).isNull()
    }

    @Test
    fun directAapsIsfWinsOverNewerLoopbackWithinPreferenceWindow() {
        val nowTs = 1_000_000L
        val selected = AutomationRepository.selectStrictAapsIsfStatic(
            rows = listOf(
                telemetryRow(
                    id = "direct-aaps",
                    timestamp = nowTs - 30_000L,
                    source = "aaps_broadcast",
                    key = "raw_isf",
                    value = 2.4
                ),
                telemetryRow(
                    id = "loopback",
                    timestamp = nowTs,
                    source = "local_nightscout_devicestatus",
                    key = "raw_isf",
                    value = 4.8
                )
            )
        )

        assertThat(selected?.id).isEqualTo("direct-aaps")
        assertThat(selected?.valueDouble).isEqualTo(2.4)
    }

    @Test
    fun directAapsCrWinsOverNewerLoopbackWithinPreferenceWindow() {
        val nowTs = 1_000_000L
        val selected = AutomationRepository.selectStrictAapsCrStatic(
            rows = listOf(
                telemetryRow(
                    id = "direct-aaps",
                    timestamp = nowTs - 30_000L,
                    source = "aaps_broadcast",
                    key = "cr",
                    value = 9.0
                ),
                telemetryRow(
                    id = "loopback",
                    timestamp = nowTs,
                    source = "local_nightscout_devicestatus",
                    key = "cr",
                    value = 20.0
                )
            )
        )

        assertThat(selected?.id).isEqualTo("direct-aaps")
        assertThat(selected?.valueDouble).isEqualTo(9.0)
    }

    @Test
    fun sourcesAreResolvedIndependentlyForIsfAndCr() {
        val application = AutomationRepository.sensitivityRuntimeApplicationFromSnapshotStatic(
            SensitivityRuntimeSnapshot(
                settingsRevision = 5L,
                forecastCycleId = "cycle-independent",
                timestamp = 1_000_000L,
                isf = SensitivityMetricDecision(
                    requested = IsfRuntimeSourcePreference.AAPS,
                    resolved = SensitivityResolvedSource.AAPS,
                    rawAaps = 2.4,
                    rawEvidence = 1.7,
                    rawCopilot = 1.8,
                    blended = null,
                    effective = 2.4,
                    confidence = 1.0,
                    fallbackReason = null
                ),
                cr = SensitivityMetricDecision(
                    requested = IsfRuntimeSourcePreference.EVIDENCE,
                    resolved = SensitivityResolvedSource.EVIDENCE_BLEND,
                    rawAaps = 10.0,
                    rawEvidence = 9.5,
                    rawCopilot = 10.5,
                    blended = 9.5,
                    effective = 9.5,
                    confidence = 0.91,
                    fallbackReason = null
                )
            )
        )

        assertThat(application.isf.decision.resolved).isEqualTo(MetricRuntimeResolvedSource.AAPS)
        assertThat(application.cr.decision.resolved).isEqualTo(MetricRuntimeResolvedSource.EVIDENCE_BLEND)
        assertThat(application.isf.override?.value).isEqualTo(2.4)
        assertThat(application.cr.override?.value).isEqualTo(9.5)
    }

    @Test
    fun acceptedRuntimeSensitivityIsAuthoritativeForTheRealPredictionEngine() = runTest {
        val now = 1_700_010_000_000L
        val acceptedDecision = SensitivityMetricDecision(
            requested = IsfRuntimeSourcePreference.COPILOT,
            resolved = SensitivityResolvedSource.COPILOT_NATIVE,
            rawAaps = 2.0,
            rawEvidence = 4.5,
            rawCopilot = 5.0,
            blended = null,
            effective = 5.0,
            confidence = 0.55,
            fallbackReason = null
        )
        val accepted = SensitivityRuntimeSnapshot(
            settingsRevision = 7L,
            forecastCycleId = "accepted-authoritative",
            timestamp = now,
            isf = acceptedDecision,
            cr = acceptedDecision.copy(
                rawAaps = 10.0,
                rawEvidence = 10.0,
                rawCopilot = 10.0,
                effective = 10.0
            )
        )
        val application = AutomationRepository.sensitivityRuntimeApplicationFromSnapshotStatic(accepted)
        val acceptedEngine = HybridPredictionEngine(enableEnhancedPredictionV3 = false)
        val exactEngine = HybridPredictionEngine(enableEnhancedPredictionV3 = false)
        acceptedEngine.setSensitivityOverrides(application.isf.override, application.cr.override)
        exactEngine.setSensitivityOverride(
            isfMmolPerUnit = 5.0,
            crGramPerUnit = 10.0,
            confidence = 1.0,
            minConfidenceRequired = 1.0,
            blendWeight = 1.0
        )
        val glucose = (0..7).map { index ->
            GlucosePoint(now - (7 - index) * 5 * 60_000L, 8.6, "test", DataQuality.OK)
        }
        val correction = TherapyEvent(
            ts = now - 10 * 60_000L,
            type = "correction_bolus",
            payload = mapOf("units" to "2.0")
        )

        val acceptedForecast = acceptedEngine.predict(glucose, listOf(correction))
        val exactForecast = exactEngine.predict(glucose, listOf(correction))

        assertThat(acceptedForecast).isEqualTo(exactForecast)
    }

    @Test
    fun writerDispatchRequiresTheExactCycleLocalSnapshot() {
        val published = io.aaps.copilot.testSensitivityRuntimeSnapshot(
            cycleId = "published-cycle",
            settingsRevision = 9L
        )
        val targetContext = io.aaps.copilot.testSensitivityRuntimeContext(
            consumer = io.aaps.copilot.domain.predict.SensitivityRuntimeConsumer.TARGET_MANAGER,
            snapshot = published
        )

        AutomationRepository.validateSensitivityRuntimeDispatchStatic(
            context = targetContext,
            expectedConsumer = io.aaps.copilot.domain.predict.SensitivityRuntimeConsumer.TARGET_MANAGER,
            currentSettingsRevision = 9L,
            cycleSnapshot = published
        )

        val replacement = published.copy(forecastCycleId = "replacement-cycle")
        assertThat(
            runCatching {
                AutomationRepository.validateSensitivityRuntimeDispatchStatic(
                    context = targetContext,
                    expectedConsumer = io.aaps.copilot.domain.predict.SensitivityRuntimeConsumer.TARGET_MANAGER,
                    currentSettingsRevision = 9L,
                    cycleSnapshot = replacement
                )
            }.exceptionOrNull()
        ).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(
            runCatching {
                AutomationRepository.validateSensitivityRuntimeDispatchStatic(
                    context = targetContext,
                    expectedConsumer = io.aaps.copilot.domain.predict.SensitivityRuntimeConsumer.TARGET_MANAGER,
                    currentSettingsRevision = 10L,
                    cycleSnapshot = published
                )
            }.exceptionOrNull()
        ).isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun unifiedUamProductionProjectionRejectsNonUamConsumer() {
        val forecastContext = io.aaps.copilot.testSensitivityRuntimeContext(
            consumer = io.aaps.copilot.domain.predict.SensitivityRuntimeConsumer.FORECAST_5_30_60
        )

        assertThat(
            runCatching {
                AutomationRepository.projectUnifiedUamRuntimeStatic(
                    diagnostics = null,
                    effectiveCobGrams = 0.0,
                    sensorBlocked = false,
                    sensitivityRuntime = forecastContext
                )
            }.exceptionOrNull()
        ).isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun realPredictionEngineCarriesExactFanOutSnapshotIntoUamDiagnostics() = runTest {
        val snapshot = io.aaps.copilot.testSensitivityRuntimeSnapshot(
            cycleId = "real-engine-cycle",
            settingsRevision = 31L,
            isf = 2.6,
            cr = 8.8
        )
        val fanOut = io.aaps.copilot.domain.predict.SensitivityRuntimeFanOut(snapshot)
        val application = AutomationRepository.sensitivityRuntimeApplicationFromSnapshotStatic(snapshot)
        val engine = HybridPredictionEngine(enableEnhancedPredictionV3 = true, enableUam = true)
        engine.setSensitivityOverrides(application.isf.override, application.cr.override)
        engine.setUamSensitivityRuntimeContext(
            fanOut.contextFor(io.aaps.copilot.domain.predict.SensitivityRuntimeConsumer.UAM)
        )
        engine.setUamRuntimeQualityContext(
            sensorTrust = 0.95,
            therapyCoverage = 0.90,
            announcedCarbCoverage = 1.0,
            sensorBlocked = false
        )
        val now = 1_760_000_600_000L

        engine.predict(
            glucose = listOf(
                GlucosePoint(now - 15 * 60_000L, 5.8, "test"),
                GlucosePoint(now - 10 * 60_000L, 6.0, "test"),
                GlucosePoint(now - 5 * 60_000L, 6.3, "test"),
                GlucosePoint(now, 6.7, "test")
            ),
            therapyEvents = emptyList()
        )
        val diagnostics = checkNotNull(engine.diagnosticsSnapshot())
        val projection = AutomationRepository.projectUnifiedUamRuntimeStatic(
            diagnostics = diagnostics,
            effectiveCobGrams = 0.0,
            sensorBlocked = false,
            sensitivityRuntime = fanOut.contextFor(
                io.aaps.copilot.domain.predict.SensitivityRuntimeConsumer.UAM
            )
        )

        assertThat(diagnostics.unifiedUamSensitivityCycleId).isEqualTo(snapshot.forecastCycleId)
        assertThat(diagnostics.unifiedUamSensitivitySettingsRevision).isEqualTo(snapshot.settingsRevision)
        assertThat(diagnostics.unifiedUamSensitivityIsfMmolPerUnit).isEqualTo(snapshot.isf.effective)
        assertThat(diagnostics.unifiedUamSensitivityCrGramPerUnit).isEqualTo(snapshot.cr.effective)
        assertThat(projection.sensitivityCycleId).isEqualTo(snapshot.forecastCycleId)
        assertThat(projection.sensitivitySettingsRevision).isEqualTo(snapshot.settingsRevision)
    }

    @Test
    fun activeManagerFailureDoesNotCallLegacyTempTargetPath() {
        val routing = AutomationRepository.automaticTargetWriterRoutingStatic(TargetManagerMode.ACTIVE)

        assertThat(routing.evaluateManager).isTrue()
        assertThat(routing.submitLegacyAutomatic).isFalse()
        assertThat(routing.fallbackToLegacyOnManagerFailure).isFalse()
    }

    @Test
    fun shadowModeEvaluatesWithoutWritingThroughLegacyPath() {
        val routing = AutomationRepository.automaticTargetWriterRoutingStatic(TargetManagerMode.SHADOW)

        assertThat(routing.evaluateManager).isTrue()
        assertThat(routing.submitLegacyAutomatic).isFalse()
        assertThat(routing.fallbackToLegacyOnManagerFailure).isFalse()
    }

    @Test
    fun offModeDisablesAllAutomaticTargetWriters() {
        val routing = AutomationRepository.automaticTargetWriterRoutingStatic(TargetManagerMode.OFF)

        assertThat(routing.evaluateManager).isFalse()
        assertThat(routing.submitLegacyAutomatic).isFalse()
        assertThat(routing.fallbackToLegacyOnManagerFailure).isFalse()
    }

    @Test
    fun patternTargetRuleIsDisabledWhenScheduleAutoIsEnabled() {
        val enabled = AutomationRepository.enabledTargetRuleIdsStatic(
            postHypoEnabled = true,
            patternEnabled = true,
            segmentEnabled = true,
            scheduleAutoEnabled = true
        )

        assertThat(enabled).doesNotContain("PatternAdaptiveTarget.v1")
    }

    @Test
    fun cobAboveTwentyDoesNotReplaceManualOrScheduledBase() {
        val controller = AdaptiveTempTargetController()
        val output = controller.evaluate(
            AdaptiveTempTargetController.Input(
                nowTs = 1_000L,
                baseTarget = 6.1,
                targetMinMmol = 4.0,
                targetMaxMmol = 9.0,
                currentGlucoseMmol = 6.1,
                observedDelta5Mmol = 0.0,
                pred5 = 6.1,
                pred30 = 6.1,
                pred60 = 6.1,
                ciLow5 = 5.5,
                ciHigh5 = 6.7,
                ciLow30 = 5.4,
                ciHigh30 = 6.8,
                ciLow60 = 5.3,
                ciHigh60 = 6.9,
                uamActive = false,
                previousTempTarget = null,
                previousI = 0.0,
                cobGrams = 35.0,
                safetyIobUnits = 0.0
            )
        )

        assertThat(output.debugFields["Tb"]).isEqualTo(6.1)
    }

    @Test
    fun finiteForecastWithoutMaturedHistoryIsInsufficient() {
        val reliability = AutomationRepository.buildHorizonReliabilityStatic(
            horizonMinutes = 30,
            currentForecast = Forecast(
                ts = 1_000L,
                horizonMinutes = 30,
                valueMmol = 6.0,
                ciLow = 5.0,
                ciHigh = 7.0,
                modelVersion = "test"
            ),
            maturedPoints = listOf(
                AutomationRepository.ForecastCalibrationPoint(
                    horizonMinutes = 30,
                    errorMmol = 0.2,
                    ageMs = 60_000L,
                    predictedMmol = 6.0,
                    ciLowMmol = 5.0,
                    ciHighMmol = 7.0,
                    sensorTrusted = true,
                    modelVersion = "test"
                )
            ),
            evaluatedAt = 2_000L
        )

        assertThat(reliability.state).isEqualTo(HorizonReliabilityState.INSUFFICIENT)
        assertThat(reliability.sampleCount).isEqualTo(1)
    }

    @Test
    fun maturedTrustedForecastHistoryCanBecomeReliable() {
        val points = List(48) { index ->
            AutomationRepository.ForecastCalibrationPoint(
                horizonMinutes = 30,
                errorMmol = if (index % 2 == 0) 0.3 else -0.2,
                ageMs = (index + 1L) * 60_000L,
                predictedMmol = 6.0,
                ciLowMmol = 5.2,
                ciHighMmol = 6.8,
                sensorTrusted = true,
                modelVersion = "test"
            )
        }
        val reliability = AutomationRepository.buildHorizonReliabilityStatic(
            horizonMinutes = 30,
            currentForecast = Forecast(
                ts = 1_000L,
                horizonMinutes = 30,
                valueMmol = 6.0,
                ciLow = 5.0,
                ciHigh = 7.0,
                modelVersion = "test"
            ),
            maturedPoints = points,
            evaluatedAt = 2_000L
        )

        assertThat(reliability.state).isEqualTo(HorizonReliabilityState.RELIABLE)
        assertThat(reliability.sampleCount).isEqualTo(48)
        assertThat(reliability.ciCoverage).isEqualTo(1.0)
    }

    @Test
    fun untrustedSensorHistoryCannotEnableForecastReliability() {
        val points = List(48) {
            AutomationRepository.ForecastCalibrationPoint(
                horizonMinutes = 5,
                errorMmol = 0.1,
                ageMs = 60_000L,
                predictedMmol = 6.0,
                ciLowMmol = 5.0,
                ciHighMmol = 7.0,
                sensorTrusted = false,
                modelVersion = "test"
            )
        }
        val reliability = AutomationRepository.buildHorizonReliabilityStatic(
            horizonMinutes = 5,
            currentForecast = Forecast(
                ts = 1_000L,
                horizonMinutes = 5,
                valueMmol = 6.0,
                ciLow = 5.0,
                ciHigh = 7.0,
                modelVersion = "test"
            ),
            maturedPoints = points,
            evaluatedAt = 2_000L
        )

        assertThat(reliability.state).isEqualTo(HorizonReliabilityState.INSUFFICIENT)
        assertThat(reliability.sampleCount).isEqualTo(0)
    }

    @Test
    fun reliabilityDoesNotMixForecastModelVersions() {
        val points = List(48) {
            AutomationRepository.ForecastCalibrationPoint(
                horizonMinutes = 30,
                errorMmol = 0.1,
                ageMs = 60_000L,
                predictedMmol = 6.0,
                ciLowMmol = 5.0,
                ciHighMmol = 7.0,
                sensorTrusted = true,
                modelVersion = "old-model"
            )
        }

        val reliability = AutomationRepository.buildHorizonReliabilityStatic(
            horizonMinutes = 30,
            currentForecast = Forecast(
                ts = 1_000L,
                horizonMinutes = 30,
                valueMmol = 6.0,
                ciLow = 5.0,
                ciHigh = 7.0,
                modelVersion = "new-model"
            ),
            maturedPoints = points,
            evaluatedAt = 2_000L
        )

        assertThat(reliability.state).isEqualTo(HorizonReliabilityState.INSUFFICIENT)
        assertThat(reliability.sampleCount).isEqualTo(0)
    }

    @Test
    fun forecastTrustUsesForecastCreationTimeNotTargetTime() {
        val targetTimestamp = 1_800_000_000_000L

        val generatedAt = AutomationRepository.forecastGeneratedAtStatic(
            targetTimestamp = targetTimestamp,
            horizonMinutes = 30
        )

        assertThat(generatedAt).isEqualTo(targetTimestamp - 30L * 60_000L)
    }

    @Test
    fun cobBias_raisesForecasts() {
        val source = sampleForecasts()
        val adjusted = AutomationRepository.applyCobIobForecastBiasStatic(
            forecasts = source,
            cobGrams = 40.0,
            diagnosticIobUnits = 0.0
        )

        assertThat(adjusted[0].valueMmol).isGreaterThan(source[0].valueMmol)
        assertThat(adjusted[1].valueMmol).isGreaterThan(source[1].valueMmol)
        assertThat(adjusted[2].valueMmol).isGreaterThan(source[2].valueMmol)
        assertThat(adjusted.all { it.modelVersion.contains("cob_iob_bias_v1") }).isTrue()
    }

    @Test
    fun externalCobAdjustment_subtractsSyntheticUamResidual() {
        val adjusted = AutomationRepository.adjustExternalCobForSyntheticUamStatic(
            externalCobGrams = 28.0,
            syntheticUamCobGrams = 9.5,
            carbMaxGrams = 60.0
        )

        assertThat(adjusted).isWithin(0.001).of(18.5)
    }

    @Test
    fun externalCobAdjustment_neverDropsBelowZero() {
        val adjusted = AutomationRepository.adjustExternalCobForSyntheticUamStatic(
            externalCobGrams = 6.0,
            syntheticUamCobGrams = 12.0,
            carbMaxGrams = 60.0
        )

        assertThat(adjusted).isWithin(0.001).of(0.0)
    }

    @Test
    fun iobBias_lowersForecasts() {
        val source = sampleForecasts()
        val adjusted = AutomationRepository.applyCobIobForecastBiasStatic(
            forecasts = source,
            cobGrams = 0.0,
            diagnosticIobUnits = 3.0,
            insulinCycleContext = insulinCycleContext(componentSnapshot(3.0), therapyModelAvailable = false)
        )

        assertThat(adjusted[0].valueMmol).isLessThan(source[0].valueMmol)
        assertThat(adjusted[1].valueMmol).isLessThan(source[1].valueMmol)
        assertThat(adjusted[2].valueMmol).isLessThan(source[2].valueMmol)
    }

    @Test
    fun circadianPrior_biasesForecastsAndMarksModelVersion() {
        val source = sampleForecasts()
        val adjusted = AutomationRepository.applyCircadianPatternForecastBiasStatic(
            forecasts = source,
            prior = sampleCircadianPrior(
                delta15 = 0.6,
                delta30 = 1.4,
                delta60 = 2.1,
                confidence = 0.9,
                acuteAttenuation = 1.0,
                staleBlocked = false
            ),
            latestGlucoseMmol = 6.0,
            weight30 = 0.25,
            weight60 = 0.35
        )

        assertThat(adjusted.first { it.horizonMinutes == 30 }.valueMmol)
            .isGreaterThan(source.first { it.horizonMinutes == 30 }.valueMmol)
        assertThat(adjusted.first { it.horizonMinutes == 60 }.valueMmol)
            .isGreaterThan(source.first { it.horizonMinutes == 60 }.valueMmol)
        assertThat(adjusted.all { it.modelVersion.contains("circadian_v2") }).isTrue()
    }

    @Test
    fun circadianPrior_acuteAttenuationReducesBiasMagnitude() {
        val source = sampleForecasts()
        val calm = AutomationRepository.applyCircadianPatternForecastBiasStatic(
            forecasts = source,
            prior = sampleCircadianPrior(
                delta15 = 0.3,
                delta30 = 1.0,
                delta60 = 1.8,
                confidence = 1.0,
                acuteAttenuation = 1.0,
                staleBlocked = false
            ),
            latestGlucoseMmol = 6.0,
            weight30 = 0.25,
            weight60 = 0.35
        )
        val acute = AutomationRepository.applyCircadianPatternForecastBiasStatic(
            forecasts = source,
            prior = sampleCircadianPrior(
                delta15 = 0.3,
                delta30 = 1.0,
                delta60 = 1.8,
                confidence = 1.0,
                acuteAttenuation = 0.4,
                staleBlocked = false
            ),
            latestGlucoseMmol = 6.0,
            weight30 = 0.25,
            weight60 = 0.35
        )

        val calm60Shift = calm.first { it.horizonMinutes == 60 }.valueMmol - source.first { it.horizonMinutes == 60 }.valueMmol
        val acute60Shift = acute.first { it.horizonMinutes == 60 }.valueMmol - source.first { it.horizonMinutes == 60 }.valueMmol
        assertThat(acute60Shift).isLessThan(calm60Shift)
        assertThat(acute60Shift).isGreaterThan(0.0)
    }

    @Test
    fun circadianPrior_helpfulLowAcuteBucketsGetStrongerLongHorizonWeight() {
        val prior = sampleCircadianPrior(
            delta15 = 0.3,
            delta30 = 1.0,
            delta60 = 1.8,
            confidence = 1.0,
            acuteAttenuation = 1.0,
            staleBlocked = false
        ).copy(
            replaySampleCount30 = 16,
            replaySampleCount60 = 18,
            replayWinRate30 = 0.62,
            replayWinRate60 = 0.64,
            replayMaeImprovement30 = -0.11,
            replayMaeImprovement60 = -0.18,
            replayBucketStatus30 = CircadianReplayBucketStatus.HELPFUL,
            replayBucketStatus60 = CircadianReplayBucketStatus.HELPFUL
        )
        val boosted30 = AutomationRepository.patternPriorWeightForHorizonStatic(
            horizonMinutes = 30,
            prior = prior,
            weight30 = 0.25,
            weight60 = 0.35
        )
        val boosted60 = AutomationRepository.patternPriorWeightForHorizonStatic(
            horizonMinutes = 60,
            prior = prior,
            weight30 = 0.25,
            weight60 = 0.35
        )
        val acutePrior = prior.copy(acuteAttenuation = 0.4)
        val acute30 = AutomationRepository.patternPriorWeightForHorizonStatic(
            horizonMinutes = 30,
            prior = acutePrior,
            weight30 = 0.25,
            weight60 = 0.35
        )
        val acute60 = AutomationRepository.patternPriorWeightForHorizonStatic(
            horizonMinutes = 60,
            prior = acutePrior,
            weight30 = 0.25,
            weight60 = 0.35
        )

        assertThat(boosted30).isGreaterThan(acute30)
        assertThat(boosted60).isGreaterThan(acute60)
    }

    @Test
    fun circadianPrior_skipsWhenStaleBlocked() {
        val source = sampleForecasts()
        val adjusted = AutomationRepository.applyCircadianPatternForecastBiasStatic(
            forecasts = source,
            prior = sampleCircadianPrior(
                delta15 = 0.8,
                delta30 = 1.6,
                delta60 = 2.4,
                confidence = 0.9,
                acuteAttenuation = 1.0,
                staleBlocked = true
            ),
            latestGlucoseMmol = 6.0,
            weight30 = 0.25,
            weight60 = 0.35
        )

        assertThat(adjusted).isEqualTo(source)
    }

    @Test
    fun circadianPrior_medianReversionPullsLongHorizonsBackTowardSlotMedian() {
        val source = sampleForecasts()
        val withoutReversion = AutomationRepository.applyCircadianPatternForecastBiasStatic(
            forecasts = source,
            prior = sampleCircadianPrior(
                delta15 = 0.2,
                delta30 = 0.4,
                delta60 = 0.6,
                confidence = 0.95,
                acuteAttenuation = 1.0,
                staleBlocked = false
            ),
            latestGlucoseMmol = 7.0,
            weight30 = 0.25,
            weight60 = 0.35
        )
        val withReversion = AutomationRepository.applyCircadianPatternForecastBiasStatic(
            forecasts = source,
            prior = sampleCircadianPrior(
                delta15 = 0.2,
                delta30 = 0.4,
                delta60 = 0.6,
                confidence = 0.95,
                acuteAttenuation = 1.0,
                staleBlocked = false
            ).copy(
                medianReversion30 = -0.12,
                medianReversion60 = -0.28
            ),
            latestGlucoseMmol = 7.0,
            weight30 = 0.25,
            weight60 = 0.35
        )

        assertThat(
            AutomationRepository.priorDeltaForHorizonStatic(
                prior = sampleCircadianPrior(
                    delta15 = 0.2,
                    delta30 = 0.4,
                    delta60 = 0.6,
                    confidence = 0.95,
                    acuteAttenuation = 1.0,
                    staleBlocked = false
                ).copy(
                    medianReversion30 = -0.12,
                    medianReversion60 = -0.28
                ),
                horizonMinutes = 30
            )
        ).isLessThan(
            AutomationRepository.priorDeltaForHorizonStatic(
                prior = sampleCircadianPrior(
                    delta15 = 0.2,
                    delta30 = 0.4,
                    delta60 = 0.6,
                    confidence = 0.95,
                    acuteAttenuation = 1.0,
                    staleBlocked = false
                ),
                horizonMinutes = 30
            )
        )
        assertThat(withReversion.first { it.horizonMinutes == 60 }.valueMmol)
            .isLessThan(withoutReversion.first { it.horizonMinutes == 60 }.valueMmol)
    }

    @Test
    fun circadianTelemetryRows_includeReplayWeightsAndBiases() {
        val nowTs = 1_773_104_160_000L
        val rows = AutomationRepository.buildCircadianPriorTelemetryRowsStatic(
            nowTs = nowTs,
            prior = sampleCircadianPrior(
                delta15 = 0.1,
                delta30 = 0.8,
                delta60 = 1.2,
                confidence = 0.9,
                acuteAttenuation = 0.4,
                staleBlocked = false
            ).copy(
                replayBias30 = 0.12,
                replayBias60 = -0.18,
                stabilityScore = 0.82,
                horizonQuality30 = 0.74,
                horizonQuality60 = 0.68,
                replayBucketStatus30 = CircadianReplayBucketStatus.HELPFUL,
                replayBucketStatus60 = CircadianReplayBucketStatus.HARMFUL
            ),
            weight30 = 0.25,
            weight60 = 0.35
        )

        fun row(key: String): TelemetrySampleEntity = rows.first { it.key == key }

        assertThat(row("pattern_prior_replay_weight_30").valueDouble).isGreaterThan(0.0)
        assertThat(row("pattern_prior_replay_weight_60").valueDouble).isGreaterThan(0.0)
        assertThat(row("pattern_prior_replay_bias_30").valueDouble).isWithin(1e-6).of(0.12)
        assertThat(row("pattern_prior_replay_bias_60").valueDouble).isWithin(1e-6).of(-0.18)
        assertThat(row("pattern_prior_horizon_quality_30").valueDouble).isWithin(1e-6).of(0.74)
        assertThat(row("pattern_prior_horizon_quality_60").valueDouble).isWithin(1e-6).of(0.68)
        assertThat(row("pattern_prior_stability_score").valueDouble).isWithin(1e-6).of(0.82)
        assertThat(row("pattern_prior_replay_status_30").valueText).isEqualTo("HELPFUL")
        assertThat(row("pattern_prior_replay_status_60").valueText).isEqualTo("HARMFUL")
    }

    @Test
    fun cobIobBias_appliesLowRiskDownshiftWhenGlucoseLowAndIobHigh() {
        val source = listOf(
            Forecast(System.currentTimeMillis() + 5 * 60_000L, 5, 7.6, 5.8, 9.2, "test"),
            Forecast(System.currentTimeMillis() + 30 * 60_000L, 30, 8.6, 6.0, 11.2, "test"),
            Forecast(System.currentTimeMillis() + 60 * 60_000L, 60, 9.6, 6.2, 12.8, "test")
        )
        val adjusted = AutomationRepository.applyCobIobForecastBiasStatic(
            forecasts = source,
            cobGrams = 60.0,
            diagnosticIobUnits = 4.0,
            insulinCycleContext = insulinCycleContext(componentSnapshot(4.0), therapyModelAvailable = false),
            latestGlucoseMmol = 3.8,
            uamActive = false
        )

        assertThat(adjusted.first { it.horizonMinutes == 60 }.valueMmol)
            .isLessThan(source.first { it.horizonMinutes == 60 }.valueMmol)
        assertThat(adjusted.first { it.horizonMinutes == 30 }.valueMmol)
            .isLessThan(source.first { it.horizonMinutes == 30 }.valueMmol)
    }

    @Test
    fun cobIobBias_doesNotApplyExtraLowRiskDownshiftWhenUamActive() {
        val source = listOf(
            Forecast(System.currentTimeMillis() + 5 * 60_000L, 5, 7.6, 5.8, 9.2, "test"),
            Forecast(System.currentTimeMillis() + 30 * 60_000L, 30, 8.6, 6.0, 11.2, "test"),
            Forecast(System.currentTimeMillis() + 60 * 60_000L, 60, 9.6, 6.2, 12.8, "test")
        )
        val lowRiskAdjusted = AutomationRepository.applyCobIobForecastBiasStatic(
            forecasts = source,
            cobGrams = 60.0,
            diagnosticIobUnits = 4.0,
            insulinCycleContext = insulinCycleContext(componentSnapshot(4.0), therapyModelAvailable = false),
            latestGlucoseMmol = 3.8,
            uamActive = false
        )
        val uamAdjusted = AutomationRepository.applyCobIobForecastBiasStatic(
            forecasts = source,
            cobGrams = 60.0,
            diagnosticIobUnits = 4.0,
            insulinCycleContext = insulinCycleContext(componentSnapshot(4.0), therapyModelAvailable = false),
            latestGlucoseMmol = 3.8,
            uamActive = true
        )

        val lowRisk60 = lowRiskAdjusted.first { it.horizonMinutes == 60 }.valueMmol
        val uam60 = uamAdjusted.first { it.horizonMinutes == 60 }.valueMmol
        assertThat(lowRisk60).isLessThan(uam60)
    }

    @Test
    fun cobIobBiasUsesCurrentCycleUamStateInsteadOfStaleTelemetry() {
        val staleTelemetry = mapOf("uam_runtime_flag" to 1.0)

        val resolved = AutomationRepository.resolveCobIobBiasUamActiveStatic(
            latestTelemetry = staleTelemetry,
            currentUnifiedUamFlag = 0.0
        )

        assertThat(resolved).isFalse()
    }

    @Test
    fun cobIobBiasFallsBackToTelemetryWhenCurrentCycleUamStateMissing() {
        val staleTelemetry = mapOf("uam_runtime_flag" to 1.0)

        val resolved = AutomationRepository.resolveCobIobBiasUamActiveStatic(
            latestTelemetry = staleTelemetry,
            currentUnifiedUamFlag = null
        )

        assertThat(resolved).isTrue()
    }

    @Test
    fun repositoryUamResolverUsesOnlyCurrentRuntimeFlagsWithExplicitZeroWinning() {
        assertThat(
            AutomationRepository.resolveUamActiveTelemetryStatic(
                mapOf(
                    "uam_runtime_control_flag" to 0.0,
                    "uam_runtime_flag" to 1.0,
                    "uam_value" to 1.0,
                    "uam_inferred_flag" to 1.0,
                    "uam_calculated_flag" to 1.0
                )
            )
        ).isFalse()
        assertThat(
            AutomationRepository.resolveUamActiveTelemetryStatic(
                mapOf("uam_runtime_flag" to 1.0, "uam_calculated_flag" to 0.0)
            )
        ).isTrue()
        assertThat(
            AutomationRepository.resolveUamActiveTelemetryStatic(
                mapOf("uam_value" to 1.0, "uam_inferred_flag" to 1.0)
            )
        ).isFalse()
    }

    @Test
    fun iobSampleProvenanceExposesTimestampAgeAndFreshness() {
        val now = 1_760_000_600_000L
        val freshRow = TelemetrySampleEntity(
            id = "fresh-iob",
            timestamp = now - 5 * 60_000L,
            source = "aaps_broadcast",
            key = "iob_units",
            valueDouble = 0.0,
            valueText = null,
            unit = "U",
            quality = "OK"
        )

        val fresh = AutomationRepository.resolveIobSampleProvenanceStatic(freshRow, now)
        val stale = AutomationRepository.resolveIobSampleProvenanceStatic(
            freshRow.copy(timestamp = now - 16 * 60_000L),
            now
        )
        val configuredStale = AutomationRepository.resolveIobSampleProvenanceStatic(
            freshRow.copy(timestamp = now - 12 * 60_000L),
            now,
            freshnessMs = 10 * 60_000L
        )
        val missing = AutomationRepository.resolveIobSampleProvenanceStatic(null, now)

        assertThat(fresh.sampleTs).isEqualTo(freshRow.timestamp)
        assertThat(fresh.ageMinutes).isWithin(1e-12).of(5.0)
        assertThat(fresh.fresh).isTrue()
        assertThat(stale.fresh).isFalse()
        assertThat(configuredStale.fresh).isFalse()
        assertThat(missing.sampleTs).isNull()
        assertThat(missing.fresh).isFalse()
    }

    @Test
    fun directAapsIobWinsBetweenFiveMinuteBroadcasts() {
        val bucket = 1_784_617_200_000L
        val directAaps = iobSample(
            id = "aaps-iob",
            timestamp = bucket,
            source = "aaps_broadcast",
            value = 2.93
        )
        val deviceStatus = iobSample(
            id = "devicestatus-iob",
            timestamp = bucket + 155_413L,
            source = "nightscout_devicestatus",
            value = 1.11
        )

        val selected = AutomationRepository.selectStrictAapsIobStatic(
            listOf(directAaps, deviceStatus)
        )

        assertThat(selected).isEqualTo(directAaps)
    }

    @Test
    fun newerDeviceStatusWinsWhenDirectAapsIobIsOutsideCycleWindow() {
        val now = 1_784_617_500_000L
        val staleDirectAaps = iobSample(
            id = "stale-aaps-iob",
            timestamp = now - 7 * 60_000L,
            source = "aaps_broadcast",
            value = 2.93
        )
        val deviceStatus = iobSample(
            id = "fresh-devicestatus-iob",
            timestamp = now,
            source = "nightscout_devicestatus",
            value = 1.11
        )

        val selected = AutomationRepository.selectStrictAapsIobStatic(
            listOf(staleDirectAaps, deviceStatus)
        )

        assertThat(selected).isEqualTo(deviceStatus)
    }

    @Test
    fun canonicalCobWinsOverConflictingRawAliasesInSameBroadcastBucket() {
        val canonical = therapySample(
            id = "tm-aaps_broadcast-cob_grams-1788591000000",
            timestamp = 1_788_591_000_000L,
            source = "aaps_broadcast", key = "cob_grams", value = 20.0, unit = "g"
        )
        val oldRaw = canonical.copy(
            id = "tm-aaps_broadcast-raw_cob-1788591000000-1418158846",
            key = "raw_cob", valueDouble = 0.0
        )
        val newRaw = oldRaw.copy(
            id = "tm-aaps_broadcast-raw_cob-1788591000000--1451297360", valueDouble = 20.0
        )
        listOf(listOf(canonical, oldRaw, newRaw), listOf(newRaw, oldRaw, canonical)).forEach {
            assertThat(AutomationRepository.selectStrictAapsCobStatic(it)).isEqualTo(canonical)
        }
        assertThat(AutomationRepository.selectStrictAapsCobStatic(listOf(oldRaw))).isEqualTo(oldRaw)
        val newerRaw = oldRaw.copy(timestamp = canonical.timestamp + 300_000L)
        assertThat(AutomationRepository.selectStrictAapsCobStatic(listOf(canonical, newerRaw)))
            .isEqualTo(newerRaw)
    }

    @Test
    fun directAapsCobWinsBetweenFiveMinuteBroadcasts() {
        val bucket = 1_784_618_400_000L
        val directAaps = therapySample(
            id = "aaps-cob",
            timestamp = bucket,
            source = "aaps_broadcast",
            key = "cob_grams",
            value = 65.66,
            unit = "g"
        )
        val deviceStatus = therapySample(
            id = "devicestatus-cob",
            timestamp = bucket + 155_413L,
            source = "nightscout_devicestatus",
            key = "cob_grams",
            value = 34.27,
            unit = "g"
        )

        val selected = AutomationRepository.selectStrictAapsCobStatic(
            listOf(directAaps, deviceStatus)
        )

        assertThat(selected).isEqualTo(directAaps)
    }

    @Test
    fun uamRuntimeQualityBlocksTherapyCoverageWhenIobHasNoCausalInsulinRows() {
        val now = 1_760_000_600_000L
        val quality = AutomationRepository.resolveUamRuntimeQualityStatic(
            sensorQualityScore = 0.93,
            sensorBlocked = false,
            nowTs = now,
            effectiveDiaHours = 5.0,
            insulinEvidenceTimestamps = emptyList(),
            iobSampleTs = now - 60_000L,
            iobUnits = 1.2,
            effectiveCobGrams = 0.0,
            externalCobGrams = null,
            carbTherapyAvailable = false
        )

        assertThat(quality.sensorTrust).isWithin(1e-12).of(0.93)
        assertThat(quality.therapyCoverage).isEqualTo(0.0)
        assertThat(quality.reasons).contains("iob_without_causal_insulin")
    }

    @Test
    fun uamRuntimeQualityRejectsOldOnlyInsulinOutsideEffectiveDia() {
        val now = 1_760_000_600_000L

        val quality = AutomationRepository.resolveUamRuntimeQualityStatic(
            sensorQualityScore = 0.93,
            sensorBlocked = false,
            nowTs = now,
            effectiveDiaHours = 5.0,
            insulinEvidenceTimestamps = listOf(now - 8 * 60 * 60_000L),
            iobSampleTs = now - 60_000L,
            iobUnits = 1.2,
            effectiveCobGrams = 0.0,
            externalCobGrams = null,
            carbTherapyAvailable = false
        )

        assertThat(quality.therapyCoverage).isEqualTo(0.0)
        assertThat(quality.reasons).contains("iob_without_recent_dia_insulin")
    }

    @Test
    fun uamRuntimeQualityRejectsLocalEstimateEvenWithSyntheticCoverage() {
        val now = 1_760_000_600_000L
        val snapshot = InsulinRuntimeSnapshot(
            timestamp = now - 60_000L,
            source = InsulinRuntimeSource.LOCAL_ESTIMATE,
            netIobUnits = 1.2,
            bolusIobUnits = null,
            basalIobUnits = null,
            insulinActivity = null,
            effectivePositiveIobUnits = 1.2,
            confidence = 0.75,
            fallbackReason = "local",
            evidenceTimestamp = now - 90 * 60_000L,
            therapyCoverage = 0.85
        )

        val quality = AutomationRepository.resolveUamRuntimeQualityStatic(
            sensorQualityScore = 0.93,
            sensorBlocked = false,
            nowTs = now,
            effectiveDiaHours = 5.0,
            insulinEvidenceTimestamps = listOf(now - 90 * 60_000L),
            iobSampleTs = now - 60_000L,
            iobUnits = 1.2,
            effectiveCobGrams = 0.0,
            externalCobGrams = null,
            carbTherapyAvailable = false,
            insulinSnapshot = snapshot
        )

        assertThat(quality.therapyCoverage).isEqualTo(0.0)
        assertThat(quality.reasons).contains("iob_snapshot_unqualified")
    }

    @Test
    fun uamRuntimeQualityAllowsFreshQualifiedAapsZeroIob() {
        val now = 1_760_000_600_000L
        val snapshot = InsulinRuntimeSnapshot(
            timestamp = now - 60_000L,
            source = InsulinRuntimeSource.AAPS_COMPONENTS,
            netIobUnits = 0.0,
            bolusIobUnits = 0.0,
            basalIobUnits = 0.0,
            insulinActivity = 0.0,
            effectivePositiveIobUnits = 0.0,
            confidence = 1.0,
            fallbackReason = null,
            evidenceTimestamp = now - 60_000L,
            therapyCoverage = 1.0
        )

        val quality = AutomationRepository.resolveUamRuntimeQualityStatic(
            sensorQualityScore = 0.93,
            sensorBlocked = false,
            nowTs = now,
            effectiveDiaHours = 5.0,
            insulinEvidenceTimestamps = emptyList(),
            iobSampleTs = now - 60_000L,
            iobUnits = 0.0,
            effectiveCobGrams = 0.0,
            externalCobGrams = null,
            carbTherapyAvailable = false,
            insulinSnapshot = snapshot
        )

        assertThat(quality.therapyCoverage).isEqualTo(1.0)
        assertThat(quality.reasons).doesNotContain("iob_without_causal_insulin")
    }

    @Test
    fun uamRuntimeQualityRejectsFreshLooseZeroWithoutQualifiedSnapshot() {
        val now = 1_760_000_600_000L

        val quality = AutomationRepository.resolveUamRuntimeQualityStatic(
            sensorQualityScore = 0.93,
            sensorBlocked = false,
            nowTs = now,
            effectiveDiaHours = 5.0,
            insulinEvidenceTimestamps = emptyList(),
            iobSampleTs = now - 60_000L,
            iobUnits = 0.0,
            effectiveCobGrams = 0.0,
            externalCobGrams = null,
            carbTherapyAvailable = false,
            insulinSnapshot = null
        )

        assertThat(quality.therapyCoverage).isEqualTo(0.0)
        assertThat(quality.reasons).contains("iob_snapshot_unqualified")
    }

    @Test
    fun uamRuntimeQualityFailsClosedForMissingOrStaleZeroIobWithoutDiaEvidence() {
        val now = 1_760_000_600_000L

        listOf<Long?>(null, now - 16 * 60_000L).forEach { sampleTs ->
            val quality = AutomationRepository.resolveUamRuntimeQualityStatic(
                sensorQualityScore = 0.93,
                sensorBlocked = false,
                nowTs = now,
                effectiveDiaHours = 5.0,
                insulinEvidenceTimestamps = emptyList(),
                iobSampleTs = sampleTs,
                iobUnits = 0.0,
                effectiveCobGrams = 0.0,
                externalCobGrams = null,
                carbTherapyAvailable = false
            )

            assertThat(quality.therapyCoverage).isEqualTo(0.0)
            assertThat(quality.reasons).contains("iob_sample_missing_or_stale")
        }
    }

    @Test
    fun uamRuntimeQualityFailsClosedWithoutSnapshotEvenWithDiaEvidence() {
        val now = 1_760_000_600_000L
        val partial = AutomationRepository.resolveUamRuntimeQualityStatic(
            sensorQualityScore = 0.93,
            sensorBlocked = false,
            nowTs = now,
            effectiveDiaHours = 5.0,
            insulinEvidenceTimestamps = listOf(now - 90 * 60_000L),
            iobSampleTs = null,
            iobUnits = 0.0,
            effectiveCobGrams = 0.0,
            externalCobGrams = null,
            carbTherapyAvailable = false
        )
        val strong = AutomationRepository.resolveUamRuntimeQualityStatic(
            sensorQualityScore = 0.93,
            sensorBlocked = false,
            nowTs = now,
            effectiveDiaHours = 5.0,
            insulinEvidenceTimestamps = listOf(now - 4 * 60 * 60_000L, now - 30 * 60_000L),
            iobSampleTs = null,
            iobUnits = 0.0,
            effectiveCobGrams = 0.0,
            externalCobGrams = null,
            carbTherapyAvailable = false
        )

        assertThat(partial.therapyCoverage).isEqualTo(0.0)
        assertThat(strong.therapyCoverage).isEqualTo(0.0)
        assertThat(partial.reasons).contains("iob_snapshot_unqualified")
        assertThat(strong.reasons).contains("iob_snapshot_unqualified")
    }

    @Test
    fun uamRuntimeQualityKeepsExternalCobWithoutCarbRowsDiagnosticOnly() {
        val now = 1_760_000_600_000L
        val quality = AutomationRepository.resolveUamRuntimeQualityStatic(
            sensorQualityScore = 0.95,
            sensorBlocked = false,
            nowTs = now,
            effectiveDiaHours = 5.0,
            insulinEvidenceTimestamps = listOf(now - 30 * 60_000L),
            iobSampleTs = now - 60_000L,
            iobUnits = 1.0,
            effectiveCobGrams = 18.0,
            externalCobGrams = 18.0,
            carbTherapyAvailable = false
        )

        assertThat(quality.therapyCoverage).isLessThan(UamExportPolicy.MIN_THERAPY_COVERAGE)
        assertThat(quality.announcedCarbCoverage).isEqualTo(0.0)
        assertThat(quality.reasons).contains("external_cob_without_causal_carbs")
    }

    @Test
    fun uamRuntimeQualityUsesZeroTrustWheneverSensorIsBlocked() {
        val now = 1_760_000_600_000L
        val quality = AutomationRepository.resolveUamRuntimeQualityStatic(
            sensorQualityScore = 0.99,
            sensorBlocked = true,
            nowTs = now,
            effectiveDiaHours = 5.0,
            insulinEvidenceTimestamps = listOf(now - 30 * 60_000L),
            iobSampleTs = now - 60_000L,
            iobUnits = 1.0,
            effectiveCobGrams = 0.0,
            externalCobGrams = null,
            carbTherapyAvailable = false
        )

        assertThat(quality.sensorTrust).isEqualTo(0.0)
        assertThat(quality.sensorBlocked).isTrue()
    }

    @Test
    fun unifiedRuntimeProjectionUsesOnlyCurrentDiagnosticsAndClearsInactiveValues() {
        val sensitivity = io.aaps.copilot.testSensitivityRuntimeSnapshot(
            cycleId = "cycle-uam-inactive",
            settingsRevision = 17L,
            isf = 2.8,
            cr = 9.5
        )
        val diagnostics = buildDiagnostics().copy(
            unifiedUamState = "INACTIVE",
            unifiedUamActiveForForecast = false,
            unifiedUamActiveForControl = false,
            unifiedUamImpactMmol5 = 0.0,
            unifiedUamSignedResidualMmol5 = -0.05,
            unifiedUamShortAverageDeltaMmol5 = -0.02,
            unifiedUamEquivalentCarbsGrams = null,
            unifiedUamLowerBoundCarbsGrams = null,
            unifiedUamOnsetTs = null,
            unifiedUamActiveSinceTs = null,
            unifiedUamAlgorithmVersion = "unified-uam-test",
            unifiedUamSensitivityCycleId = sensitivity.forecastCycleId,
            unifiedUamSensitivitySettingsRevision = sensitivity.settingsRevision,
            unifiedUamSensitivityIsfMmolPerUnit = sensitivity.isf.effective,
            unifiedUamSensitivityCrGramPerUnit = sensitivity.cr.effective
        )

        val projection = AutomationRepository.projectUnifiedUamRuntimeStatic(
            diagnostics = diagnostics,
            effectiveCobGrams = 4.25,
            sensorBlocked = true,
            sensitivityRuntime = io.aaps.copilot.testSensitivityRuntimeContext(
                consumer = io.aaps.copilot.domain.predict.SensitivityRuntimeConsumer.UAM,
                snapshot = sensitivity
            )
        )

        assertThat(projection.flag).isEqualTo(0.0)
        assertThat(projection.controlFlag).isEqualTo(0.0)
        assertThat(projection.equivalentCarbsGrams).isNull()
        assertThat(projection.supportedLowerBoundGrams).isNull()
        assertThat(projection.episodeId).isEmpty()
        assertThat(projection.source).isEqualTo(diagnostics.unifiedUamSource)
        assertThat(projection.effectiveCobGrams).isEqualTo(4.25)
        assertThat(projection.sensorBlocked).isTrue()
        assertThat(projection.sensitivityCycleId).isEqualTo("cycle-uam-inactive")
        assertThat(projection.sensitivitySettingsRevision).isEqualTo(17L)
        assertThat(projection.sensitivityIsfMmolPerUnit).isEqualTo(2.8)
        assertThat(projection.sensitivityCrGramPerUnit).isEqualTo(9.5)
    }

    @Test
    fun unifiedSourceTimestampRemainsLatestCausalCgmWithoutSeparateExportTtl() {
        val latestCgmTs = 1_760_000_600_000L
        val sensitivity = io.aaps.copilot.testSensitivityRuntimeSnapshot(
            cycleId = "cycle-uam-active",
            settingsRevision = 18L
        )
        val diagnostics = buildDiagnostics().copy(
            unifiedUamTimestamp = latestCgmTs,
            unifiedUamState = "ACTIVE",
            unifiedUamActiveForForecast = true,
            unifiedUamActiveForControl = true,
            unifiedUamConfidence = 0.90,
            unifiedUamLowerBoundCarbsGrams = 10.0,
            unifiedUamOnsetTs = latestCgmTs - 10 * 60_000L,
            unifiedUamActiveSinceTs = latestCgmTs - 10 * 60_000L,
            unifiedUamLowerBoundStableBuckets = 2,
            unifiedUamSensorTrust = 0.95,
            unifiedUamTherapyCoverage = 0.90,
            unifiedUamAlgorithmVersion = "unified-uam-test",
            unifiedUamSensitivityCycleId = sensitivity.forecastCycleId,
            unifiedUamSensitivitySettingsRevision = sensitivity.settingsRevision,
            unifiedUamSensitivityIsfMmolPerUnit = sensitivity.isf.effective,
            unifiedUamSensitivityCrGramPerUnit = sensitivity.cr.effective
        )
        val projection = AutomationRepository.projectUnifiedUamRuntimeStatic(
            diagnostics = diagnostics,
            effectiveCobGrams = 0.0,
            sensorBlocked = false,
            sensitivityRuntime = io.aaps.copilot.testSensitivityRuntimeContext(
                consumer = io.aaps.copilot.domain.predict.SensitivityRuntimeConsumer.UAM,
                snapshot = sensitivity
            )
        )
        val wallClockNow = latestCgmTs + 7 * 60_000L + 1L

        val decision = UamExportPolicy.decide(
            UamExportPolicyInput(
                nowTs = wallClockNow,
                episodeId = projection.episodeId,
                activeSinceTs = projection.activeSinceTs,
                confidence = projection.confidence,
                supportedLowerBoundGrams = projection.supportedLowerBoundGrams,
                lowerBoundStableBuckets = projection.lowerBoundStableBuckets,
                sensorTrust = projection.sensorTrust,
                sensorBlocked = projection.sensorBlocked,
                signedResidualMmol5 = 0.2,
                shortAverageDeltaMmol5 = 0.2,
                currentGlucoseMmol = 8.0,
                forecastMinimumMmol = 8.0,
                effectiveCobGrams = projection.effectiveCobGrams,
                therapyCoverage = projection.therapyCoverage,
                remoteLedger = emptyList(),
                sourceSnapshotTs = projection.timestamp
            )
        )

        assertThat(projection.timestamp).isEqualTo(latestCgmTs)
        assertThat(decision).isEqualTo(
            UamExportDecision.Send(
                grams = 10.0,
                treatmentTs = (wallClockNow / (5 * 60_000L)) * (5 * 60_000L),
                seq = 1
            )
        )
    }

    @Test
    fun unifiedUamEpisodeIdUsesAlgorithmVersionAndCausalFiveMinuteOnsetBucket() {
        val onset = (1_760_000_612_345L / (5 * 60_000L)) * (5 * 60_000L)

        val first = AutomationRepository.buildUnifiedUamEpisodeIdStatic("unified-uam-v1", onset)
        val sameBucket = AutomationRepository.buildUnifiedUamEpisodeIdStatic(
            "unified-uam-v1",
            onset + 2 * 60_000L
        )
        val nextBucket = AutomationRepository.buildUnifiedUamEpisodeIdStatic(
            "unified-uam-v1",
            onset + 5 * 60_000L
        )

        assertThat(first).isNotEmpty()
        assertThat(sameBucket).isEqualTo(first)
        assertThat(nextBucket).isNotEqualTo(first)
        assertThat(AutomationRepository.buildUnifiedUamEpisodeIdStatic("unified-uam-v1", null)).isEmpty()
        assertThat(AutomationRepository.buildUnifiedUamEpisodeIdStatic("", onset)).isEmpty()
    }

    @Test
    fun unifiedExportTelemetryCountsSuccessfulLiveIncrementButNeverDryRunAsDelivered() {
        val now = 1_760_000_900_000L
        val episodeId = "unified-uam-v1:1"
        val decision = UamExportDecision.Send(grams = 15.0, treatmentTs = now, seq = 2)
        val deliveredOutcome = UamExportCoordinator.Outcome(
            events = emptyList(),
            remoteEntries = listOf(
                AapsCarbEntry(
                    remoteId = "prior",
                    tsMs = now - 12 * 60_000L,
                    grams = 10.0,
                    note = UamTagCodec.buildTag(episodeId, 1, UamMode.NORMAL, version = 2)
                )
            ),
            decision = decision,
            delivered = true,
            reason = "delivered",
            reconciledGrams = 10.0,
            reconciliationValid = true
        )

        val live = AutomationRepository.resolveUnifiedUamExportTelemetryStatic(
            nowTs = now,
            episodeId = episodeId,
            onsetTs = now - 20 * 60_000L,
            lowerBoundGrams = 30.0,
            enabled = true,
            dryRun = false,
            outcome = deliveredOutcome
        )
        val dryRun = AutomationRepository.resolveUnifiedUamExportTelemetryStatic(
            nowTs = now,
            episodeId = episodeId,
            onsetTs = now - 20 * 60_000L,
            lowerBoundGrams = 30.0,
            enabled = true,
            dryRun = true,
            outcome = deliveredOutcome.copy(delivered = false, reason = "dry_run")
        )

        assertThat(live.delivered).isTrue()
        assertThat(live.cumulativeGrams).isWithin(1e-12).of(25.0)
        assertThat(live.rolling30Grams).isWithin(1e-12).of(25.0)
        assertThat(live.rolling60Grams).isWithin(1e-12).of(25.0)
        assertThat(live.lastIncrementGrams).isWithin(1e-12).of(15.0)
        assertThat(live.lastIncrementTs).isEqualTo(now)
        assertThat(dryRun.delivered).isFalse()
        assertThat(dryRun.cumulativeGrams).isWithin(1e-12).of(10.0)
        assertThat(dryRun.lastIncrementGrams).isEqualTo(10.0)
        assertThat(dryRun.lastIncrementTs).isEqualTo(now - 12 * 60_000L)
    }

    @Test
    fun unifiedExportRollingTelemetryIncludesAllValidV2EpisodesOnly() {
        val now = 1_760_000_900_000L
        fun v2(id: String, seq: Int, minutesAgo: Long, grams: Double) = AapsCarbEntry(
            remoteId = "$id-$seq",
            tsMs = now - minutesAgo * 60_000L,
            grams = grams,
            note = UamTagCodec.buildTag(id, seq, UamMode.NORMAL, version = 2)
        )
        val outcome = UamExportCoordinator.Outcome(
            events = emptyList(),
            remoteEntries = listOf(
                v2("unified-uam-v1:1", 1, 5, 10.0),
                v2("unified-uam-v1:2", 1, 20, 12.0),
                v2("unified-uam-v1:2", 2, 40, 8.0),
                AapsCarbEntry(
                    remoteId = "legacy",
                    tsMs = now - 2 * 60_000L,
                    grams = 50.0,
                    note = UamTagCodec.buildTag("legacy", 1, UamMode.NORMAL, version = 1)
                )
            ),
            reason = "reconciled"
        )

        val telemetry = AutomationRepository.resolveUnifiedUamExportTelemetryStatic(
            nowTs = now,
            episodeId = "unified-uam-v1:1",
            onsetTs = now - 15 * 60_000L,
            lowerBoundGrams = 10.0,
            enabled = true,
            dryRun = true,
            outcome = outcome
        )

        assertThat(telemetry.rolling30Grams).isEqualTo(22.0)
        assertThat(telemetry.rolling60Grams).isEqualTo(30.0)
        assertThat(telemetry.cumulativeGrams).isEqualTo(30.0)
    }

    @Test
    fun calmTailForecastVirtualMealIsReleasedWithoutInferredDecay() {
        val active = AutomationRepository.shouldKeepForecastVirtualMealRuntimeActiveStatic(
            usingVirtualMeal = true,
            virtualMealCarbs = 16.5,
            virtualMealConfidence = 0.40,
            forecastUam60Mmol = 1.57,
            forecastUci0 = 0.011,
            forecastRocPer5Used = 0.10,
            inferredStaleDecayApplied = false
        )

        assertThat(active).isFalse()
    }

    @Test
    fun forecastVirtualMealStaysActiveWhenCalmTailStillHasUciSupport() {
        val active = AutomationRepository.shouldKeepForecastVirtualMealRuntimeActiveStatic(
            usingVirtualMeal = true,
            virtualMealCarbs = 19.9,
            virtualMealConfidence = 0.40,
            forecastUam60Mmol = 1.87,
            forecastUci0 = 0.088,
            forecastRocPer5Used = 0.10,
            inferredStaleDecayApplied = false
        )

        assertThat(active).isTrue()
    }

    @Test
    fun lateWeakForecastTailIsReleasedEvenWhenConfidenceStaysModerate() {
        val active = AutomationRepository.shouldKeepForecastVirtualMealRuntimeActiveStatic(
            usingVirtualMeal = true,
            virtualMealCarbs = 16.53,
            virtualMealConfidence = 0.651,
            forecastUam60Mmol = 1.571,
            forecastUci0 = 0.011,
            forecastRocPer5Used = -0.087,
            inferredStaleDecayApplied = false
        )

        assertThat(active).isFalse()
    }

    @Test
    fun confidenceAloneDoesNotReleaseWhenDirectForecastSupportRemainsStrong() {
        val active = AutomationRepository.shouldKeepForecastVirtualMealRuntimeActiveStatic(
            usingVirtualMeal = true,
            virtualMealCarbs = 24.8,
            virtualMealConfidence = 0.66,
            forecastUam60Mmol = 1.82,
            forecastUci0 = 0.11,
            forecastRocPer5Used = -0.04,
            inferredStaleDecayApplied = false
        )

        assertThat(active).isTrue()
    }

    @Test
    fun ciAndValueStayWithinPhysiologicBounds() {
        val source = listOf(
            Forecast(
                ts = System.currentTimeMillis() + 60 * 60_000L,
                horizonMinutes = 60,
                valueMmol = 21.8,
                ciLow = 20.8,
                ciHigh = 22.0,
                modelVersion = "test"
            )
        )

        val adjusted = AutomationRepository.applyCobIobForecastBiasStatic(
            forecasts = source,
            cobGrams = 200.0,
            diagnosticIobUnits = 0.0
        )

        assertThat(adjusted.single().valueMmol).isAtMost(22.0)
        assertThat(adjusted.single().ciLow).isAtLeast(2.2)
        assertThat(adjusted.single().ciHigh).isAtMost(22.0)
        assertThat(adjusted.single().ciLow).isAtMost(adjusted.single().valueMmol)
        assertThat(adjusted.single().ciHigh).isAtLeast(adjusted.single().valueMmol)
    }

    @Test
    fun contextBias_preservesBaselineActivityFactorAlongsideOtherContextInputs() {
        val source = sampleForecasts()
        val pattern = io.aaps.copilot.domain.model.PatternWindow(
            dayType = DayType.WEEKDAY,
            hour = 9,
            sampleCount = 100,
            activeDays = 30,
            lowRate = 0.36,
            highRate = 0.10,
            recommendedTargetMmol = 6.1,
            isRiskWindow = true
        )
        val telemetry = mapOf(
            "isf_factor_activity_factor" to 1.30,
            "isf_factor_set_factor" to 1.0,
            "isf_factor_dawn_factor" to 1.0,
            "isf_factor_stress_factor" to 1.0,
            "isf_factor_hormone_factor" to 1.0,
            "isf_factor_steroid_factor" to 1.0,
            "sensor_quality_score" to 0.9,
            "isf_factor_context_ambiguity" to 0.1
        )
        val adjusted = AutomationRepository.applyContextFactorForecastBiasStatic(
            forecasts = source,
            telemetry = telemetry,
            latestGlucoseMmol = 7.2,
            pattern = pattern
        )
        val withoutActivity = AutomationRepository.applyContextFactorForecastBiasStatic(
            forecasts = source,
            telemetry = telemetry + ("isf_factor_activity_factor" to 1.0),
            latestGlucoseMmol = 7.2,
            pattern = pattern
        )

        assertThat(gson.toJson(adjusted)).isNotEqualTo(gson.toJson(withoutActivity))
        assertThat(adjusted.single { it.horizonMinutes == 60 }.valueMmol)
            .isLessThan(withoutActivity.single { it.horizonMinutes == 60 }.valueMmol)
        assertThat(adjusted[2].modelVersion).contains("ctx_bias_v1")
    }

    @Test
    fun contextBias_lowSensorQualityWidensCi() {
        val source = sampleForecasts()
        val adjusted = AutomationRepository.applyContextFactorForecastBiasStatic(
            forecasts = source,
            telemetry = mapOf(
                "sensor_quality_score" to 0.2,
                "isf_factor_context_ambiguity" to 0.8
            ),
            latestGlucoseMmol = 8.0,
            pattern = null
        )
        val src60 = source.first { it.horizonMinutes == 60 }
        val adj60 = adjusted.first { it.horizonMinutes == 60 }
        val srcWidth = src60.ciHigh - src60.ciLow
        val adjWidth = adj60.ciHigh - adj60.ciLow

        assertThat(adjWidth).isGreaterThan(srcWidth)
        assertThat(adj60.modelVersion).contains("ctx_bias_v1")
    }

    @Test
    fun contextBias_doesNotRaiseForecastWhenCurrentGlucoseLow() {
        val source = sampleForecasts()
        val adjusted = AutomationRepository.applyContextFactorForecastBiasStatic(
            forecasts = source,
            telemetry = mapOf(
                "isf_factor_set_factor" to 0.70,
                "isf_factor_dawn_factor" to 0.75,
                "isf_factor_stress_factor" to 0.80,
                "sensor_quality_score" to 0.95,
                "isf_factor_context_ambiguity" to 0.0
            ),
            latestGlucoseMmol = 3.9,
            pattern = null
        )

        assertThat(adjusted[2].valueMmol).isAtMost(source[2].valueMmol)
    }

    @Test
    fun staleInferredUamRuntimeIsReleasedWhenTailIsWeakAndCobIsLow() {
        val keep = AutomationRepository.shouldKeepInferredUamRuntimeActiveStatic(
            nowTs = 1_773_120_000_000L,
            activeFlag = 1.0,
            inferredCarbsGrams = 18.0,
            ingestionTs = 1_773_114_600_000L,
            manualCobGrams = 1.5,
            gAbsRecent = listOf(0.10, 0.08, 0.06, 0.04),
            forecastRocPer5Used = 0.04,
            forecastUci0 = 0.03,
            forecastVirtualMealConfidence = 0.10,
            forecastUam60Mmol = 0.32
        )

        assertThat(keep).isFalse()
    }

    @Test
    fun veryWeakCalmInferredUamIsReleasedAfterSixtyMinutes() {
        val keep = AutomationRepository.shouldKeepInferredUamRuntimeActiveStatic(
            nowTs = 1_773_120_000_000L,
            activeFlag = 1.0,
            inferredCarbsGrams = 16.0,
            ingestionTs = 1_773_116_400_000L,
            manualCobGrams = 0.0,
            gAbsRecent = listOf(0.06, 0.04, 0.03, 0.02),
            forecastRocPer5Used = 0.04,
            forecastUci0 = 0.02,
            forecastVirtualMealConfidence = 0.10,
            forecastUam60Mmol = 0.20
        )

        assertThat(keep).isFalse()
    }

    @Test
    fun moderatelyWeakCalmInferredUamIsReleasedAfterNinetyMinutes() {
        val keep = AutomationRepository.shouldKeepInferredUamRuntimeActiveStatic(
            nowTs = 1_773_120_000_000L,
            activeFlag = 1.0,
            inferredCarbsGrams = 24.0,
            ingestionTs = 1_773_114_300_000L,
            manualCobGrams = 1.8,
            gAbsRecent = listOf(0.20, 0.18, 0.16, 0.14),
            forecastRocPer5Used = 0.06,
            forecastUci0 = 0.05,
            forecastVirtualMealConfidence = 0.18,
            forecastUam60Mmol = 0.92
        )

        assertThat(keep).isFalse()
    }

    @Test
    fun freshInferredUamRuntimeRemainsActiveEvenWhenTailIsStillSmall() {
        val keep = AutomationRepository.shouldKeepInferredUamRuntimeActiveStatic(
            nowTs = 1_773_120_000_000L,
            activeFlag = 1.0,
            inferredCarbsGrams = 18.0,
            ingestionTs = 1_773_118_500_000L,
            manualCobGrams = 1.5,
            gAbsRecent = listOf(0.10, 0.08, 0.06, 0.04),
            forecastRocPer5Used = 0.04,
            forecastUci0 = 0.03,
            forecastVirtualMealConfidence = 0.10,
            forecastUam60Mmol = 0.32
        )

        assertThat(keep).isTrue()
    }

    @Test
    fun weakForecastVirtualMealIsReleasedAfterInferredStaleDecay() {
        val keep = AutomationRepository.shouldKeepForecastVirtualMealRuntimeActiveStatic(
            usingVirtualMeal = true,
            virtualMealCarbs = 22.0,
            virtualMealConfidence = 0.28,
            forecastUam60Mmol = 0.42,
            forecastUci0 = 0.03,
            forecastRocPer5Used = 0.05,
            inferredStaleDecayApplied = true
        )

        assertThat(keep).isFalse()
    }

    @Test
    fun modestForecastVirtualMealIsReleasedAfterInferredStaleDecay() {
        val keep = AutomationRepository.shouldKeepForecastVirtualMealRuntimeActiveStatic(
            usingVirtualMeal = true,
            virtualMealCarbs = 24.0,
            virtualMealConfidence = 0.34,
            forecastUam60Mmol = 0.96,
            forecastUci0 = 0.08,
            forecastRocPer5Used = 0.09,
            inferredStaleDecayApplied = true
        )

        assertThat(keep).isFalse()
    }

    @Test
    fun strongForecastVirtualMealRemainsActive() {
        val keep = AutomationRepository.shouldKeepForecastVirtualMealRuntimeActiveStatic(
            usingVirtualMeal = true,
            virtualMealCarbs = 46.0,
            virtualMealConfidence = 0.82,
            forecastUam60Mmol = 2.4,
            forecastUci0 = 0.22,
            forecastRocPer5Used = 0.31,
            inferredStaleDecayApplied = true
        )

        assertThat(keep).isTrue()
    }

    @Test
    fun baseAlignment_isSkippedForAdaptiveRule() {
        val skipped = AutomationRepository.shouldSkipBaseAlignmentStatic(
            sourceRuleId = "AdaptiveTargetController.v1",
            actionReason = "adaptive_pi_ci_v2|mode=control_pi"
        )

        assertThat(skipped).isTrue()
    }

    @Test
    fun baseAlignment_isAllowedForNonAdaptiveRules() {
        val skipped = AutomationRepository.shouldSkipBaseAlignmentStatic(
            sourceRuleId = "PatternAdaptiveTargetRule.v1",
            actionReason = "pattern_weekday_high"
        )

        assertThat(skipped).isFalse()
    }

    @Test
    fun runtimeDia_ignoresRawTelemetryWithoutProfileDerivedRefinement() {
        val resolution = AutomationRepository.resolveEffectiveDiaHoursStatic(
            profileDurationHours = 4.67,
            profileBaseOnsetMinutes = 8.0,
            realProfileOnsetMinutes = null,
            realProfileShapeScale = null,
            realProfileConfidence = null,
            realProfileStatus = null,
            realProfileSourceId = null,
            selectedProfileId = "APIDRA"
        )

        assertThat(resolution.profileHours).isWithin(0.001).of(4.67)
        assertThat(resolution.effectiveHours).isWithin(0.001).of(4.67)
        assertThat(resolution.source).isEqualTo("profile_default")
    }

    @Test
    fun runtimeDia_usesRealProfileScaleWhenConfident() {
        val resolution = AutomationRepository.resolveEffectiveDiaHoursStatic(
            profileDurationHours = 5.0,
            profileBaseOnsetMinutes = 10.0,
            realProfileOnsetMinutes = 18.0,
            realProfileShapeScale = 0.82,
            realProfileConfidence = 0.74,
            realProfileStatus = "estimated_daily",
            realProfileSourceId = "NOVORAPID",
            selectedProfileId = "NOVORAPID"
        )

        val rawEstimated = ((5.0 * 60.0 * 0.82) + (18.0 - 10.0)) / 60.0
        val expected = 5.0 + (rawEstimated - 5.0) * 0.74
        assertThat(resolution.rawEstimatedHours).isWithin(0.001).of(rawEstimated)
        assertThat(resolution.effectiveHours).isWithin(0.001).of(expected)
        assertThat(resolution.source).isEqualTo("profile_real_blended")
    }

    @Test
    fun runtimeDia_isHardClampedToFiftyPercentOfProfile() {
        val resolution = AutomationRepository.resolveEffectiveDiaHoursStatic(
            profileDurationHours = 5.0,
            profileBaseOnsetMinutes = 10.0,
            realProfileOnsetMinutes = 95.0,
            realProfileShapeScale = 1.80,
            realProfileConfidence = 0.95,
            realProfileStatus = "estimated_daily",
            realProfileSourceId = "NOVORAPID",
            selectedProfileId = "NOVORAPID"
        )

        assertThat(resolution.rawEstimatedHours).isWithin(0.001).of(7.5)
        assertThat(resolution.effectiveHours).isAtMost(7.5)
        assertThat(resolution.effectiveHours).isAtLeast(2.5)
    }

    @Test
    fun modeledIobUsesNewlyResolvedSameCycleDiaInsteadOfOldTelemetryDia() {
        val now = 1_760_000_600_000L
        val event = TherapyEvent(
            ts = now - 120 * 60_000L,
            type = "bolus",
            payload = mapOf("units" to "2.0")
        )
        val oldTelemetryDiaHours = 3.0
        val resolvedRuntimeDiaHours = 7.0
        val oldDiaEngine = HybridPredictionEngine(enableEnhancedPredictionV3 = true, enableUam = false).apply {
            setInsulinProfile("NOVORAPID")
            setInsulinDurationHours(oldTelemetryDiaHours)
            setInsulinOnsetMinutes(baseOnsetMinutes = 10.0, realOnsetMinutes = 10.0)
        }
        val expectedOldDiaIob = oldDiaEngine.modeledActiveInsulinEvidence(listOf(event), now).activeUnits

        val sameCycle = AutomationRepository.modelSameCycleActiveInsulinStatic(
            engine = HybridPredictionEngine(enableEnhancedPredictionV3 = true, enableUam = false),
            profileIdRaw = "NOVORAPID",
            resolvedEffectiveDiaHours = resolvedRuntimeDiaHours,
            baseOnsetMinutes = 10.0,
            realOnsetMinutes = 10.0,
            therapyEvents = listOf(event),
            nowTs = now
        )

        assertThat(sameCycle.activeUnits).isGreaterThan(expectedOldDiaIob)
    }

    @Test
    fun aapsActivityNormalizesOneAndFiveMinuteRelayOffsetsToCausalCgm() {
        val causalTs = 1_760_001_200_000L

        listOf(1L, 5L).forEach { offsetMinutes ->
            val sourceTs = causalTs + offsetMinutes * 60_000L
            val modeledAtCausal = 1.0
            val activityUnitsPerMinute = 0.01
            val authoritativeAtSource = modeledAtCausal - activityUnitsPerMinute * offsetMinutes
            val cycle = AutomationRepository.buildInsulinCycleContextStatic(
                cycleTimestamp = sourceTs,
                causalReferenceTimestamp = causalTs,
                insulinSnapshot = componentSnapshot(
                    netIobUnits = authoritativeAtSource,
                    timestamp = sourceTs,
                    insulinActivity = activityUnitsPerMinute
                ),
                modeledActiveInsulinUnits = modeledAtCausal,
                therapyModelAvailable = true,
                freshnessMs = 15 * 60_000L
            )

            assertThat(cycle.residualComparisonAllowed).isTrue()
            assertThat(cycle.signedResidualUnits).isWithin(1e-9).of(0.0)
            assertThat(cycle.alignedSnapshot?.timestamp).isEqualTo(causalTs)
            assertThat(cycle.alignedSnapshot?.netIobUnits).isWithin(1e-9).of(modeledAtCausal)
        }
    }

    @Test
    fun sharedLocalModelRatioNormalizesRelayIobWithoutFalseResidual() {
        val causalTs = 1_760_001_200_000L
        val sourceTs = causalTs + 5 * 60_000L
        val modeledAtCausal = 1.0
        val modeledAtSource = 0.8
        val cycle = AutomationRepository.buildInsulinCycleContextStatic(
            cycleTimestamp = sourceTs,
            causalReferenceTimestamp = causalTs,
            insulinSnapshot = componentSnapshot(
                netIobUnits = modeledAtSource,
                timestamp = sourceTs,
                insulinActivity = 0.0
            ),
            modeledActiveInsulinUnits = modeledAtCausal,
            modeledActiveInsulinAtSnapshotUnits = modeledAtSource,
            therapyModelAvailable = true,
            freshnessMs = 15 * 60_000L
        )

        assertThat(cycle.residualComparisonAllowed).isTrue()
        assertThat(cycle.signedResidualUnits).isWithin(1e-12).of(0.0)
        assertThat(cycle.alignedSnapshot?.netIobUnits).isWithin(1e-12).of(modeledAtCausal)
        val localControl = listOf(
            Forecast(causalTs + 5 * 60_000L, 5, 6.1, 5.7, 6.5, "local-hybrid-v3|therapy"),
            Forecast(causalTs + 30 * 60_000L, 30, 5.5, 4.8, 6.2, "local-hybrid-v3|therapy"),
            Forecast(causalTs + 60 * 60_000L, 60, 4.9, 4.1, 5.7, "local-hybrid-v3|therapy")
        )
        val control = AutomationRepository.applyCobIobForecastBiasStatic(
            forecasts = localControl,
            cobGrams = 0.0,
            diagnosticIobUnits = modeledAtSource,
            insulinCycleContext = cycle,
            isfMmolPerUnit = 3.0
        )
        assertThat(control).containsExactlyElementsIn(localControl).inOrder()
    }

    @Test
    fun mapperResolverAndCycleNormalizeRealisticAapsRelayOffsets() {
        val causalTs = 1_760_001_200_000L

        listOf(1L, 5L).forEach { offsetMinutes ->
            val relayTs = causalTs + offsetMinutes * 60_000L
            val ingestTs = relayTs + 15_000L
            val modeledAtCausal = 1.0
            val modeledAtRelay = 1.0 - 0.01 * offsetMinutes
            val rows = TelemetryMetricMapper.fromKeyValueMap(
                timestamp = ingestTs,
                observedAtTimestamp = ingestTs,
                source = "aaps_broadcast",
                values = mapOf(
                    "netIob" to modeledAtRelay.toString(),
                    "bolusIob" to modeledAtRelay.toString(),
                    "basalIob" to "0.0",
                    "insulinActivity" to "0.01",
                    "iobTimestamp" to relayTs.toString()
                )
            )
            val resolved = AutomationRepository.resolveFreshRuntimeInsulinTelemetryStatic(
                rows = rows,
                nowTs = ingestTs,
                freshnessMs = 15 * 60_000L
            )
            val cycle = AutomationRepository.buildInsulinCycleContextStatic(
                cycleTimestamp = ingestTs,
                causalReferenceTimestamp = causalTs,
                insulinSnapshot = resolved.snapshot,
                modeledActiveInsulinUnits = modeledAtCausal,
                modeledActiveInsulinAtSnapshotUnits = modeledAtRelay,
                therapyModelAvailable = true,
                freshnessMs = 15 * 60_000L
            )

            assertThat(resolved.snapshot?.timestamp).isEqualTo(relayTs)
            assertThat(cycle.residualComparisonAllowed).isTrue()
            assertThat(cycle.signedResidualUnits).isWithin(1e-9).of(0.0)
            assertThat(cycle.alignedSnapshot?.timestamp).isEqualTo(causalTs)
        }
    }

    @Test
    fun delayedCgmBeyondNormalizationBoundRejectsResidualComparison() {
        val cycleTs = 1_760_001_200_000L
        val cgmTs = cycleTs - 11 * 60_000L
        val event = TherapyEvent(
            ts = cgmTs - 150 * 60_000L,
            type = "bolus",
            payload = mapOf("units" to "2.0")
        )
        val engineAtCgm = HybridPredictionEngine(enableEnhancedPredictionV3 = true, enableUam = false)
        val modeledAtCgm = AutomationRepository.modelSameCycleActiveInsulinStatic(
            engine = engineAtCgm,
            profileIdRaw = "NOVORAPID",
            resolvedEffectiveDiaHours = 6.0,
            baseOnsetMinutes = 10.0,
            realOnsetMinutes = 10.0,
            therapyEvents = listOf(event),
            nowTs = cgmTs
        ).activeUnits
        val modeledAtWallClock = AutomationRepository.modelSameCycleActiveInsulinStatic(
            engine = HybridPredictionEngine(enableEnhancedPredictionV3 = true, enableUam = false),
            profileIdRaw = "NOVORAPID",
            resolvedEffectiveDiaHours = 6.0,
            baseOnsetMinutes = 10.0,
            realOnsetMinutes = 10.0,
            therapyEvents = listOf(event),
            nowTs = cycleTs
        ).activeUnits
        assertThat(modeledAtWallClock).isNotEqualTo(modeledAtCgm)

        val causalReference = AutomationRepository.resolveCycleCausalReferenceTimestampStatic(
            glucose = listOf(
                GlucosePoint(cgmTs - 5 * 60_000L, 7.0, "test", DataQuality.OK),
                GlucosePoint(cgmTs, 7.2, "test", DataQuality.OK)
            )
        )
        val authoritativeAtCausal = InsulinRuntimeSnapshot(
            timestamp = cgmTs,
            source = InsulinRuntimeSource.AAPS_COMPONENTS,
            netIobUnits = modeledAtCgm,
            bolusIobUnits = modeledAtCgm,
            basalIobUnits = 0.0,
            insulinActivity = 0.01,
            effectivePositiveIobUnits = modeledAtCgm,
            confidence = 1.0,
            fallbackReason = null,
            evidenceTimestamp = cgmTs,
            therapyCoverage = 1.0
        )
        val aligned = AutomationRepository.buildInsulinCycleContextStatic(
            cycleTimestamp = cycleTs,
            causalReferenceTimestamp = causalReference,
            insulinSnapshot = authoritativeAtCausal,
            modeledActiveInsulinUnits = modeledAtCgm,
            therapyModelAvailable = true,
            freshnessMs = 15 * 60_000L
        )
        val unaligned = AutomationRepository.buildInsulinCycleContextStatic(
            cycleTimestamp = cycleTs,
            causalReferenceTimestamp = causalReference,
            insulinSnapshot = authoritativeAtCausal.copy(timestamp = cycleTs, evidenceTimestamp = cycleTs),
            modeledActiveInsulinUnits = modeledAtCgm,
            modeledActiveInsulinAtSnapshotUnits = modeledAtWallClock,
            therapyModelAvailable = true,
            freshnessMs = 15 * 60_000L
        )

        assertThat(causalReference).isEqualTo(cgmTs)
        assertThat(aligned.residualComparisonAllowed).isTrue()
        assertThat(aligned.signedResidualUnits).isWithin(1e-12).of(0.0)
        assertThat(unaligned.residualComparisonAllowed).isFalse()
        assertThat(unaligned.signedResidualUnits).isNull()
        assertThat(unaligned.alignedSnapshot).isNull()
    }

    @Test
    fun realProfileRecompute_waitsUntilSeventyTwoHoursForStableEstimate() {
        val nowTs = 10L * 24 * 60 * 60 * 1000

        val shouldRecompute = AutomationRepository.shouldRecomputeRealInsulinProfileStatic(
            existingUpdatedTs = nowTs - (48L * 60 * 60 * 1000),
            nowTs = nowTs,
            existingAlgoVersion = "v2",
            existingStatus = "estimated_daily",
            existingSampleCount = 4
        )

        assertThat(shouldRecompute).isFalse()
    }

    @Test
    fun realProfileRecompute_runsAfterSeventyTwoHours() {
        val nowTs = 10L * 24 * 60 * 60 * 1000

        val shouldRecompute = AutomationRepository.shouldRecomputeRealInsulinProfileStatic(
            existingUpdatedTs = nowTs - (73L * 60 * 60 * 1000),
            nowTs = nowTs,
            existingAlgoVersion = "v2",
            existingStatus = "estimated_daily",
            existingSampleCount = 4
        )

        assertThat(shouldRecompute).isTrue()
    }

    @Test
    fun adaptiveCooldownBypass_allowsMaterialRetarget() {
        val bypass = AutomationRepository.shouldBypassAdaptiveCooldownStatic(
            decision = RuleDecision(
                ruleId = "AdaptiveTargetController.v1",
                state = RuleState.TRIGGERED,
                reasons = listOf("reason=control_pi"),
                actionProposal = ActionProposal(
                    type = "temp_target",
                    targetMmol = 4.1,
                    durationMinutes = 30,
                    reason = "adaptive_pi_ci_v2|mode=control_pi"
                )
            ),
            activeTempTargetMmol = 5.0
        )

        assertThat(bypass).isTrue()
    }

    @Test
    fun adaptiveCooldownBypass_rejectsSameTargetResend() {
        val bypass = AutomationRepository.shouldBypassAdaptiveCooldownStatic(
            decision = RuleDecision(
                ruleId = "AdaptiveTargetController.v1",
                state = RuleState.TRIGGERED,
                reasons = listOf("reason=control_pi"),
                actionProposal = ActionProposal(
                    type = "temp_target",
                    targetMmol = 4.1,
                    durationMinutes = 30,
                    reason = "adaptive_pi_ci_v2|mode=control_pi"
                )
            ),
            activeTempTargetMmol = 4.1
        )

        assertThat(bypass).isFalse()
    }

    @Test
    fun adaptiveCooldownBypass_allowsSingleStepRetarget() {
        val bypass = AutomationRepository.shouldBypassAdaptiveCooldownStatic(
            decision = RuleDecision(
                ruleId = "AdaptiveTargetController.v1",
                state = RuleState.TRIGGERED,
                reasons = listOf("reason=control_pi"),
                actionProposal = ActionProposal(
                    type = "temp_target",
                    targetMmol = 4.95,
                    durationMinutes = 30,
                    reason = "adaptive_pi_ci_v2|mode=control_pi"
                )
            ),
            activeTempTargetMmol = 5.0
        )

        assertThat(bypass).isTrue()
    }

    @Test
    fun resolveActiveTempTarget_convertsMgdlPayloadToMmol() {
        val now = 1_000_000L
        val resolved = AutomationRepository.resolveActiveTempTargetStatic(
            now = now,
            recentTargets = listOf(
                TherapyEventEntity(
                    id = "tt-1",
                    timestamp = now - 5 * 60_000L,
                    type = "temp_target",
                    payloadJson = """
                        {"targetBottom":"74.0","targetTop":"74.0","duration":"30"}
                    """.trimIndent()
                )
            ),
            gson = gson
        )

        assertThat(resolved).isWithin(0.01).of(4.1069)
    }

    @Test
    fun resolveActiveTempTarget_prefersExplicitMmolOverMgdlFields() {
        val now = 2_000_000L
        val resolved = AutomationRepository.resolveActiveTempTargetStatic(
            now = now,
            recentTargets = listOf(
                TherapyEventEntity(
                    id = "tt-2",
                    timestamp = now - 2 * 60_000L,
                    type = "temp_target",
                    payloadJson = """
                        {"targetBottom":"74.0","targetTop":"74.0","targetBottomMmol":"4.20","targetTopMmol":"4.20","duration":"30"}
                    """.trimIndent()
                )
            ),
            gson = gson
        )

        assertThat(resolved).isWithin(0.0001).of(4.20)
    }

    @Test
    fun resolveActiveTempTarget_returnsNullWhenLatestTempTargetExpired() {
        val now = 3_000_000L
        val resolved = AutomationRepository.resolveActiveTempTargetStatic(
            now = now,
            recentTargets = listOf(
                TherapyEventEntity(
                    id = "tt-old",
                    timestamp = now - 40 * 60_000L,
                    type = "temp_target",
                    payloadJson = """
                        {"targetBottom":"74.0","targetTop":"74.0","duration":"30"}
                    """.trimIndent()
                ),
                TherapyEventEntity(
                    id = "tt-new-expired",
                    timestamp = now - 10 * 60_000L,
                    type = "temp_target",
                    payloadJson = """
                        {"targetBottom":"85.0","targetTop":"85.0","duration":"5"}
                    """.trimIndent()
                )
            ),
            gson = gson
        )

        assertThat(resolved).isNull()
    }

    @Test
    fun resolveActiveAapsTarget_recognizesManagerOwnershipFromNotes() {
        val now = 4_000_000L
        val resolved = AutomationRepository.resolveActiveAapsTargetStatic(
            now = now,
            recentTargets = listOf(
                TherapyEventEntity(
                    id = "tt-manager",
                    timestamp = now - 2 * 60_000L,
                    type = "temp_target",
                    payloadJson = """
                        {"targetBottom":"5.8","targetTop":"5.8","duration":"30","notes":"copilot:TargetManager.v1:fingerprint"}
                    """.trimIndent()
                )
            ),
            gson = gson
        )

        assertThat(resolved?.ownership).isEqualTo(ActiveTargetOwnership.TARGET_MANAGER)
        assertThat(resolved?.idempotencyKey).isEqualTo("TargetManager.v1:fingerprint")
    }

    @Test
    fun resolveActiveAapsTarget_treatsMissingOwnershipEvidenceAsUnknown() {
        val now = 5_000_000L
        val resolved = AutomationRepository.resolveActiveAapsTargetStatic(
            now = now,
            recentTargets = listOf(
                TherapyEventEntity(
                    id = "tt-unknown",
                    timestamp = now - 2 * 60_000L,
                    type = "temp_target",
                    payloadJson = """
                        {"targetBottom":"5.8","targetTop":"5.8","duration":"30"}
                    """.trimIndent()
                )
            ),
            gson = gson
        )

        assertThat(resolved?.ownership).isEqualTo(ActiveTargetOwnership.UNKNOWN)
    }

    @Test
    fun calibrationBias_raisesWhenHistoryUnderpredicts() {
        val source = sampleForecasts()
        val history = buildList {
            repeat(40) { idx ->
                add(
                    AutomationRepository.ForecastCalibrationPoint(
                        horizonMinutes = 60,
                        errorMmol = 1.0, // actual > pred
                        ageMs = (idx + 1) * 5 * 60_000L
                    )
                )
            }
        }

        val adjusted = AutomationRepository.applyRecentForecastCalibrationBiasStatic(
            forecasts = source,
            history = history
        )

        val src60 = source.first { it.horizonMinutes == 60 }
        val adj60 = adjusted.first { it.horizonMinutes == 60 }
        assertThat(adj60.valueMmol).isGreaterThan(src60.valueMmol)
        assertThat(adj60.modelVersion).contains("calib_v1")
    }

    @Test
    fun calibrationBias_lowersWhenHistoryOverpredicts_withClamp() {
        val source = sampleForecasts()
        val history = buildList {
            repeat(40) { idx ->
                add(
                    AutomationRepository.ForecastCalibrationPoint(
                        horizonMinutes = 30,
                        errorMmol = -2.0, // actual < pred
                        ageMs = (idx + 1) * 5 * 60_000L
                    )
                )
            }
        }

        val adjusted = AutomationRepository.applyRecentForecastCalibrationBiasStatic(
            forecasts = source,
            history = history
        )

        val src30 = source.first { it.horizonMinutes == 30 }
        val adj30 = adjusted.first { it.horizonMinutes == 30 }
        assertThat(adj30.valueMmol).isLessThan(src30.valueMmol)
        assertThat(src30.valueMmol - adj30.valueMmol).isAtMost(0.46)
    }

    @Test
    fun calibrationBias_notAppliedWhenSamplesInsufficient() {
        val source = sampleForecasts()
        val history = listOf(
            AutomationRepository.ForecastCalibrationPoint(
                horizonMinutes = 60,
                errorMmol = 1.5,
                ageMs = 10 * 60_000L
            )
        )

        val adjusted = AutomationRepository.applyRecentForecastCalibrationBiasStatic(
            forecasts = source,
            history = history
        )

        assertThat(adjusted).isEqualTo(source)
    }

    @Test
    fun calibrationBias_aiTuningIncreasesPositiveCorrectionFor60m() {
        val source = sampleForecasts()
        val history = buildList {
            repeat(40) { idx ->
                add(
                    AutomationRepository.ForecastCalibrationPoint(
                        horizonMinutes = 60,
                        errorMmol = 1.0,
                        ageMs = (idx + 1) * 5 * 60_000L
                    )
                )
            }
        }

        val baseline = AutomationRepository.applyRecentForecastCalibrationBiasStatic(
            forecasts = source,
            history = history
        )
        val tuned = AutomationRepository.applyRecentForecastCalibrationBiasStatic(
            forecasts = source,
            history = history,
            aiTuning = mapOf(
                60 to AutomationRepository.CalibrationAiTuning(
                    gainScale = 1.35,
                    maxUpScale = 1.25,
                    maxDownScale = 1.0
                )
            )
        )

        val src60 = source.first { it.horizonMinutes == 60 }.valueMmol
        val baseline60 = baseline.first { it.horizonMinutes == 60 }.valueMmol
        val tuned60 = tuned.first { it.horizonMinutes == 60 }.valueMmol
        assertThat(baseline60).isGreaterThan(src60)
        assertThat(tuned60).isGreaterThan(baseline60)
    }

    @Test
    fun calibrationBias_expandsCiWhenRecentResidualsAreWide() {
        val source = sampleForecasts()
        val history = buildList {
            repeat(36) { idx ->
                add(
                    AutomationRepository.ForecastCalibrationPoint(
                        horizonMinutes = 60,
                        errorMmol = if (idx % 2 == 0) 2.2 else -2.0,
                        ageMs = (idx + 1) * 5 * 60_000L,
                        predictedMmol = 8.6
                    )
                )
            }
        }

        val adjusted = AutomationRepository.applyRecentForecastCalibrationBiasStatic(
            forecasts = source,
            history = history
        )

        val src60 = source.first { it.horizonMinutes == 60 }
        val adj60 = adjusted.first { it.horizonMinutes == 60 }
        val srcWidth = src60.ciHigh - src60.ciLow
        val adjWidth = adj60.ciHigh - adj60.ciLow

        assertThat(adjWidth).isGreaterThan(srcWidth)
        assertThat(adj60.modelVersion).contains("ci_calib_v1")
    }

    @Test
    fun calibrationBias_canModestlyShrinkCiWhenResidualsAreTight() {
        val source = sampleForecasts()
        val history = buildList {
            repeat(36) { idx ->
                add(
                    AutomationRepository.ForecastCalibrationPoint(
                        horizonMinutes = 30,
                        errorMmol = if (idx % 2 == 0) 0.08 else -0.10,
                        ageMs = (idx + 1) * 5 * 60_000L,
                        predictedMmol = 6.3
                    )
                )
            }
        }

        val adjusted = AutomationRepository.applyRecentForecastCalibrationBiasStatic(
            forecasts = source,
            history = history
        )

        val src30 = source.first { it.horizonMinutes == 30 }
        val adj30 = adjusted.first { it.horizonMinutes == 30 }
        val srcWidth = src30.ciHigh - src30.ciLow
        val adjWidth = adj30.ciHigh - adj30.ciLow

        assertThat(adjWidth).isLessThan(srcWidth)
        assertThat(adjWidth).isAtLeast(srcWidth * 0.80)
        assertThat(adj30.modelVersion).contains("ci_calib_v1")
    }

    @Test
    fun resolveAiCalibrationTuning_blocksStaleOptimizerPayload() {
        val nowTs = System.currentTimeMillis()
        val staleTelemetry = mapOf(
            "daily_report_ai_opt_apply_flag" to 1.0,
            "daily_report_ai_opt_confidence" to 0.80,
            "daily_report_ai_opt_generated_ts" to (nowTs - 48L * 60 * 60 * 1000).toDouble(),
            "daily_report_matched_samples" to 200.0,
            "daily_report_isfcr_quality_risk_level" to 1.0,
            "daily_report_ai_opt_gain_scale_60m" to 1.25,
            "daily_report_ai_opt_max_up_scale_60m" to 1.20,
            "daily_report_ai_opt_max_down_scale_60m" to 1.0
        )

        val tuning = AutomationRepository.resolveAiCalibrationTuningStatic(
            latestTelemetry = staleTelemetry,
            nowTs = nowTs
        )

        assertThat(tuning).isEmpty()
    }

    @Test
    fun resolveAiCalibrationTuning_blocksWhenIsfCrRiskHigh() {
        val nowTs = System.currentTimeMillis()
        val highRiskTelemetry = mapOf(
            "daily_report_ai_opt_apply_flag" to 1.0,
            "daily_report_ai_opt_confidence" to 0.80,
            "daily_report_ai_opt_generated_ts" to (nowTs - 30 * 60_000L).toDouble(),
            "daily_report_matched_samples" to 240.0,
            "daily_report_isfcr_quality_risk_level" to 3.0,
            "daily_report_ai_opt_gain_scale_30m" to 1.25,
            "daily_report_ai_opt_max_up_scale_30m" to 1.20,
            "daily_report_ai_opt_max_down_scale_30m" to 0.95
        )

        val tuning = AutomationRepository.resolveAiCalibrationTuningStatic(
            latestTelemetry = highRiskTelemetry,
            nowTs = nowTs
        )

        assertThat(tuning).isEmpty()
    }

    @Test
    fun resolveAiCalibrationTuning_blocksWhenMatchedSamplesTooLow() {
        val nowTs = System.currentTimeMillis()
        val sparseTelemetry = mapOf(
            "daily_report_ai_opt_apply_flag" to 1.0,
            "daily_report_ai_opt_confidence" to 0.80,
            "daily_report_ai_opt_generated_ts" to (nowTs - 30 * 60_000L).toDouble(),
            "daily_report_matched_samples" to 12.0,
            "daily_report_isfcr_quality_risk_level" to 1.0,
            "daily_report_ai_opt_gain_scale_30m" to 1.25,
            "daily_report_ai_opt_max_up_scale_30m" to 1.20,
            "daily_report_ai_opt_max_down_scale_30m" to 0.95
        )

        val tuning = AutomationRepository.resolveAiCalibrationTuningStatic(
            latestTelemetry = sparseTelemetry,
            nowTs = nowTs
        )

        assertThat(tuning).isEmpty()
    }

    @Test
    fun resolveAiCalibrationTuning_returnsClampedValuesWhenFreshAndValid() {
        val nowTs = System.currentTimeMillis()
        val telemetry = mapOf(
            "daily_report_ai_opt_apply_flag" to 1.0,
            "daily_report_ai_opt_confidence" to 0.90,
            "daily_report_ai_opt_generated_ts" to (nowTs - 20 * 60_000L).toDouble(),
            "daily_report_matched_samples" to 220.0,
            "daily_report_isfcr_quality_risk_level" to 1.0,
            "daily_report_ai_opt_gain_scale_5m" to 4.0,
            "daily_report_ai_opt_max_up_scale_5m" to 0.1,
            "daily_report_ai_opt_max_down_scale_5m" to 9.0,
            "daily_report_ai_opt_gain_scale_30m" to 1.2,
            "daily_report_ai_opt_max_up_scale_30m" to 1.3,
            "daily_report_ai_opt_max_down_scale_30m" to 1.1,
            "daily_report_ai_opt_gain_scale_60m" to 1.35,
            "daily_report_ai_opt_max_up_scale_60m" to 1.25,
            "daily_report_ai_opt_max_down_scale_60m" to 1.0
        )

        val tuning = AutomationRepository.resolveAiCalibrationTuningStatic(
            latestTelemetry = telemetry,
            nowTs = nowTs
        )

        assertThat(tuning.keys).containsAtLeast(5, 30, 60)
        assertThat(tuning.getValue(5).gainScale).isAtMost(1.50)
        assertThat(tuning.getValue(5).maxUpScale).isAtLeast(0.80)
        assertThat(tuning.getValue(5).maxDownScale).isAtMost(1.50)
        assertThat(tuning.getValue(60).gainScale).isWithin(1e-9).of(1.35)
    }

    @Test
    fun sensorQuality_detectsSuspectFalseLow() {
        val now = System.currentTimeMillis()
        val glucose = listOf(
            GlucosePoint(now - 10 * 60_000L, 8.8, "test", DataQuality.OK),
            GlucosePoint(now - 5 * 60_000L, 8.6, "test", DataQuality.OK),
            GlucosePoint(now, 3.3, "test", DataQuality.OK)
        )

        val assessment = AutomationRepository.evaluateSensorQualityStatic(
            glucose = glucose,
            nowTs = now,
            staleMaxMinutes = 15
        )

        assertThat(assessment.blocked).isTrue()
        assertThat(assessment.suspectFalseLow).isTrue()
        assertThat(assessment.reason).isEqualTo("suspect_false_low")
    }

    @Test
    fun sensorQuality_stableSeriesRemainsOk() {
        val now = System.currentTimeMillis()
        val glucose = listOf(
            GlucosePoint(now - 20 * 60_000L, 6.1, "test"),
            GlucosePoint(now - 15 * 60_000L, 6.0, "test"),
            GlucosePoint(now - 10 * 60_000L, 6.1, "test"),
            GlucosePoint(now - 5 * 60_000L, 6.0, "test"),
            GlucosePoint(now, 6.1, "test")
        )

        val assessment = AutomationRepository.evaluateSensorQualityStatic(
            glucose = glucose,
            nowTs = now,
            staleMaxMinutes = 15
        )

        assertThat(assessment.blocked).isFalse()
        assertThat(assessment.score).isGreaterThan(0.70)
    }

    @Test
    fun sensorQuality_deduplicatesMixedSourceTimestampsBeforeDeltaGate() {
        val now = System.currentTimeMillis()
        val sameTs = now - 5 * 60_000L
        val glucose = listOf(
            GlucosePoint(now - 10 * 60_000L, 6.2, "nightscout"),
            GlucosePoint(sameTs, 9.5, "local_broadcast"),
            GlucosePoint(sameTs, 6.1, "nightscout"),
            GlucosePoint(now, 6.0, "nightscout")
        )

        val assessment = AutomationRepository.evaluateSensorQualityStatic(
            glucose = glucose,
            nowTs = now,
            staleMaxMinutes = 15
        )

        assertThat(assessment.blocked).isFalse()
        assertThat(assessment.delta5Mmol).isWithin(0.01).of(-0.1)
    }

    @Test
    fun sensorQualityRollback_sentWhenBlockedAndTargetDrifted() {
        val assessment = AutomationRepository.SensorQualityAssessment(
            score = 0.2,
            blocked = true,
            reason = "suspect_false_low",
            suspectFalseLow = true,
            delta5Mmol = -1.8,
            noiseStd5Mmol = 0.9,
            gapMinutes = 1.0
        )

        val shouldRollback = AutomationRepository.shouldSendSensorQualityRollbackStatic(
            activeTempTarget = 8.0,
            baseTargetMmol = 5.5,
            assessment = assessment
        )

        assertThat(shouldRollback).isTrue()
    }

    @Test
    fun sensorQualityRollback_notSentWhenNearBase() {
        val assessment = AutomationRepository.SensorQualityAssessment(
            score = 0.4,
            blocked = true,
            reason = "rapid_delta",
            suspectFalseLow = false,
            delta5Mmol = 1.7,
            noiseStd5Mmol = 0.2,
            gapMinutes = 1.0
        )

        val shouldRollback = AutomationRepository.shouldSendSensorQualityRollbackStatic(
            activeTempTarget = 5.6,
            baseTargetMmol = 5.5,
            assessment = assessment
        )

        assertThat(shouldRollback).isFalse()
    }

    @Test
    fun normalizeForecastSetPrefersLocalPhysiologyOverNewerCloudDuplicate() {
        val now = System.currentTimeMillis()
        val source = listOf(
            Forecast(now + 5 * 60_000L, 5, 6.0, 5.0, 7.0, "local-hybrid-v3"),
            Forecast(now + 5 * 60_000L, 5, 6.2, 5.6, 6.8, "local-hybrid-v3"),
            Forecast(now + 30 * 60_000L, 30, 6.6, 5.5, 7.7, "local-hybrid-v3"),
            Forecast(now + 31 * 60_000L, 30, 6.5, 5.9, 7.1, "cloud-v1"),
            Forecast(now + 60 * 60_000L, 60, 7.0, 5.8, 8.2, "local-hybrid-v3")
        )

        val normalized = AutomationRepository.normalizeForecastSetStatic(source)

        assertThat(normalized.map { it.horizonMinutes }).containsExactly(5, 30, 60)
        assertThat(normalized.first { it.horizonMinutes == 5 }.valueMmol).isEqualTo(6.2)
        assertThat(normalized.first { it.horizonMinutes == 30 }.modelVersion).contains("local-hybrid")
    }

    @Test
    fun cloudWithoutTherapyMetadataCannotReplaceLocalInsulinAwareControlHorizons() {
        val now = 1_760_001_200_000L
        val authoritativeIob = 1.2
        val cycle = insulinCycleContext(
            snapshot = componentSnapshot(netIobUnits = authoritativeIob, timestamp = now),
            modeledIobUnits = authoritativeIob,
            therapyModelAvailable = true
        )
        assertThat(cycle.signedResidualUnits).isWithin(1e-12).of(0.0)
        val localWithInsulinAction = listOf(
            Forecast(now + 5 * 60_000L, 5, 6.1, 5.7, 6.5, "local-hybrid-v3|therapy"),
            Forecast(now + 30 * 60_000L, 30, 5.5, 4.8, 6.2, "local-hybrid-v3|therapy"),
            Forecast(now + 60 * 60_000L, 60, 4.9, 4.1, 5.7, "local-hybrid-v3|therapy")
        )
        val localControl = AutomationRepository.applyCobIobForecastBiasStatic(
            forecasts = localWithInsulinAction,
            cobGrams = 0.0,
            diagnosticIobUnits = authoritativeIob,
            insulinCycleContext = cycle,
            isfMmolPerUnit = 3.0,
            latestGlucoseMmol = 6.4,
            uamActive = false
        )
        val cloudWithoutTherapyDecomposition = listOf(
            Forecast(now + 5 * 60_000L, 5, 6.5, 6.1, 6.9, "cloud-v1"),
            Forecast(now + 30 * 60_000L, 30, 7.0, 6.4, 7.6, "cloud-v1"),
            Forecast(now + 60 * 60_000L, 60, 7.5, 6.8, 8.2, "cloud-v1")
        )

        val control = AutomationRepository.selectCloudCompatibleControlForecastsStatic(
            local = localControl,
            cloud = cloudWithoutTherapyDecomposition
        )

        assertThat(control.map { it.horizonMinutes }).containsExactly(5, 30, 60).inOrder()
        listOf(5, 30, 60).forEach { horizon ->
            val expected = localControl.single { it.horizonMinutes == horizon }
            val actual = control.single { it.horizonMinutes == horizon }
            assertThat(actual).isEqualTo(expected)
            assertThat(actual.modelVersion).contains("local-hybrid")
        }
    }

    @Test
    fun automationControlForecastStageDoesNotInvokeUnverifiedForecastGateway() {
        val now = 1_760_001_200_000L
        val local = listOf(
            Forecast(now + 5 * 60_000L, 5, 6.1, 5.7, 6.5, "local-hybrid-v3|therapy"),
            Forecast(now + 30 * 60_000L, 30, 5.5, 4.8, 6.2, "local-hybrid-v3|therapy"),
            Forecast(now + 60 * 60_000L, 60, 4.9, 4.1, 5.7, "local-hybrid-v3|therapy")
        )
        var gatewayCalls = 0
        val gateway = AutomationRepository.ForecastGateway {
            gatewayCalls += 1
            listOf(Forecast(now + 30 * 60_000L, 30, 8.0, 7.5, 8.5, "cloud"))
        }

        val control = AutomationRepository.selectAutomationControlForecastsStatic(
            local = local,
            forecastGateway = gateway
        )

        assertThat(gatewayCalls).isEqualTo(0)
        assertThat(control).containsExactlyElementsIn(local).inOrder()
    }

    @Test
    fun extractForecastDecomposition_usesDiagnosticsComponents() {
        val now = System.currentTimeMillis()
        val diagnostics = buildDiagnostics()
        val localForecasts = listOf(
            Forecast(now + 5 * 60_000L, 5, 6.0, 5.2, 6.8, "local-hybrid-v3"),
            Forecast(now + 30 * 60_000L, 30, 6.4, 5.6, 7.2, "local-hybrid-v3"),
            Forecast(now + 60 * 60_000L, 60, 6.8, 5.9, 7.7, "local-hybrid-v3|insulin=novorapid")
        )

        val snapshot = AutomationRepository.extractForecastDecompositionSnapshotStatic(
            diagnostics = diagnostics,
            localForecasts = localForecasts
        )

        requireNotNull(snapshot)
        assertThat(snapshot.trend60Mmol).isWithin(1e-9).of(1.2)
        assertThat(snapshot.therapy60Mmol).isWithin(1e-9).of(3.0)
        assertThat(snapshot.uam60Mmol).isWithin(1e-9).of(0.6)
        assertThat(snapshot.residualRoc0Mmol5).isWithin(1e-9).of(-0.12)
        assertThat(snapshot.sigmaEMmol5).isWithin(1e-9).of(0.18)
        assertThat(snapshot.kfSigmaGMmol).isWithin(1e-9).of(0.21)
        assertThat(snapshot.modelVersion).isEqualTo("local-hybrid-v3|insulin=novorapid")
    }

    @Test
    fun extractForecastDecomposition_returnsNullWhenNoDiagnostics() {
        val snapshot = AutomationRepository.extractForecastDecompositionSnapshotStatic(
            diagnostics = null,
            localForecasts = sampleForecasts()
        )

        assertThat(snapshot).isNull()
    }

    @Test
    fun isfCrRuntimeGate_blocksWhenSnapshotMissing() {
        val gate = AutomationRepository.resolveIsfCrRuntimeGateStatic(
            snapshot = null,
            confidenceThreshold = 0.55
        )

        assertThat(gate.applyToRuntime).isFalse()
        assertThat(gate.reason).isEqualTo("no_snapshot")
    }

    @Test
    fun isfCrRuntimeGate_blocksInShadowMode() {
        val gate = AutomationRepository.resolveIsfCrRuntimeGateStatic(
            snapshot = sampleIsfCrSnapshot(mode = IsfCrRuntimeMode.SHADOW, confidence = 0.90),
            confidenceThreshold = 0.55
        )

        assertThat(gate.applyToRuntime).isFalse()
        assertThat(gate.reason).isEqualTo("shadow_mode")
    }

    @Test
    fun isfCrRuntimeGate_blocksInFallbackMode() {
        val gate = AutomationRepository.resolveIsfCrRuntimeGateStatic(
            snapshot = sampleIsfCrSnapshot(mode = IsfCrRuntimeMode.FALLBACK, confidence = 0.90),
            confidenceThreshold = 0.55
        )

        assertThat(gate.applyToRuntime).isFalse()
        assertThat(gate.reason).isEqualTo("fallback_mode")
    }

    @Test
    fun isfCrRuntimeGate_blocksInSparseRealFetchedMode() {
        val gate = AutomationRepository.resolveIsfCrRuntimeGateStatic(
            snapshot = sampleIsfCrSnapshot(mode = IsfCrRuntimeMode.SPARSE_REAL_FETCHED, confidence = 0.42),
            confidenceThreshold = 0.55
        )

        assertThat(gate.applyToRuntime).isFalse()
        assertThat(gate.reason).isEqualTo("sparse_real_fetched_mode")
    }

    @Test
    fun isfCrRuntimeGate_blocksWhenConfidenceBelowThreshold() {
        val gate = AutomationRepository.resolveIsfCrRuntimeGateStatic(
            snapshot = sampleIsfCrSnapshot(mode = IsfCrRuntimeMode.ACTIVE, confidence = 0.49),
            confidenceThreshold = 0.55
        )

        assertThat(gate.applyToRuntime).isFalse()
        assertThat(gate.reason).isEqualTo("low_confidence")
    }

    @Test
    fun isfCrRuntimeGate_allowsWhenActiveAndConfident() {
        val gate = AutomationRepository.resolveIsfCrRuntimeGateStatic(
            snapshot = sampleIsfCrSnapshot(mode = IsfCrRuntimeMode.ACTIVE, confidence = 0.72),
            confidenceThreshold = 0.55
        )

        assertThat(gate.applyToRuntime).isTrue()
        assertThat(gate.reason).isEqualTo("active_confident")
    }

    @Test
    fun isfCrOverrideBlendWeight_isFullWhenActiveAndApplied() {
        val snapshot = sampleIsfCrSnapshot(mode = IsfCrRuntimeMode.ACTIVE, confidence = 0.80)
        val gate = AutomationRepository.resolveIsfCrRuntimeGateStatic(snapshot = snapshot, confidenceThreshold = 0.55)

        val weight = AutomationRepository.resolveIsfCrOverrideBlendWeightStatic(
            snapshot = snapshot,
            runtimeGate = gate,
            confidenceThreshold = 0.55
        )

        assertThat(weight).isEqualTo(1.0)
    }

    @Test
    fun isfCrOverrideBlendWeight_usesSoftBlendInShadow() {
        val snapshot = sampleIsfCrSnapshot(mode = IsfCrRuntimeMode.SHADOW, confidence = 0.90)
        val gate = AutomationRepository.resolveIsfCrRuntimeGateStatic(snapshot = snapshot, confidenceThreshold = 0.55)

        val weight = AutomationRepository.resolveIsfCrOverrideBlendWeightStatic(
            snapshot = snapshot,
            runtimeGate = gate,
            confidenceThreshold = 0.55
        )

        requireNotNull(weight)
        assertThat(weight).isGreaterThan(0.24)
        assertThat(weight).isAtMost(0.65)
    }

    @Test
    fun isfCrOverrideBlendWeight_returnsNullWhenConfidenceLow() {
        val snapshot = sampleIsfCrSnapshot(mode = IsfCrRuntimeMode.SHADOW, confidence = 0.50)
        val gate = AutomationRepository.resolveIsfCrRuntimeGateStatic(snapshot = snapshot, confidenceThreshold = 0.55)

        val weight = AutomationRepository.resolveIsfCrOverrideBlendWeightStatic(
            snapshot = snapshot,
            runtimeGate = gate,
            confidenceThreshold = 0.55
        )

        assertThat(weight).isNull()
    }

    @Test
    fun isfCrOverrideBlendWeight_usesBoundedSparseBlend() {
        val snapshot = sampleIsfCrSnapshot(
            mode = IsfCrRuntimeMode.SPARSE_REAL_FETCHED,
            confidence = 0.44,
            factors = mapOf(
                "activity" to 1.0,
                "therapy_history_real_fetched_insulin_30d" to 4.0,
                "therapy_history_usable_insulin_30d" to 18.0
            )
        )
        val gate = AutomationRepository.resolveIsfCrRuntimeGateStatic(snapshot = snapshot, confidenceThreshold = 0.55)

        val weight = AutomationRepository.resolveIsfCrOverrideBlendWeightStatic(
            snapshot = snapshot,
            runtimeGate = gate,
            confidenceThreshold = 0.55
        )

        requireNotNull(weight)
        assertThat(weight).isAtLeast(0.12)
        assertThat(weight).isAtMost(0.32)
    }

    @Test
    fun isfCrOverrideBlendWeight_ignoresRecoveredInflationForSparseBlend() {
        val sparseSnapshot = sampleIsfCrSnapshot(
            mode = IsfCrRuntimeMode.SPARSE_REAL_FETCHED,
            confidence = 0.44,
            factors = mapOf(
                "activity" to 1.0,
                "therapy_history_real_fetched_insulin_30d" to 6.0,
                "therapy_history_usable_insulin_30d" to 18.0
            )
        )
        val inflatedSnapshot = sampleIsfCrSnapshot(
            mode = IsfCrRuntimeMode.SPARSE_REAL_FETCHED,
            confidence = 0.44,
            factors = mapOf(
                "activity" to 1.0,
                "therapy_history_real_fetched_insulin_30d" to 6.0,
                "therapy_history_usable_insulin_30d" to 200.0
            )
        )
        val gate = AutomationRepository.resolveIsfCrRuntimeGateStatic(
            snapshot = sparseSnapshot,
            confidenceThreshold = 0.55
        )

        val sparseWeight = AutomationRepository.resolveIsfCrOverrideBlendWeightStatic(
            snapshot = sparseSnapshot,
            runtimeGate = gate,
            confidenceThreshold = 0.55
        )
        val inflatedWeight = AutomationRepository.resolveIsfCrOverrideBlendWeightStatic(
            snapshot = inflatedSnapshot,
            runtimeGate = gate,
            confidenceThreshold = 0.55
        )

        requireNotNull(sparseWeight)
        requireNotNull(inflatedWeight)
        assertThat(inflatedWeight).isWithin(0.000001).of(sparseWeight)
    }

    @Test
    fun isfCrShadowActivation_blocksWhenSamplesInsufficient() {
        val assessment = AutomationRepository.evaluateIsfCrShadowActivationStatic(
            samples = listOf(
                AutomationRepository.IsfCrShadowDiffSample(0.8, 10.0, 10.0),
                AutomationRepository.IsfCrShadowDiffSample(0.9, 8.0, 7.0)
            ),
            minSamples = 12,
            minMeanConfidence = 0.65,
            maxMeanAbsIsfDeltaPct = 25.0,
            maxMeanAbsCrDeltaPct = 25.0
        )

        assertThat(assessment.eligible).isFalse()
        assertThat(assessment.reason).isEqualTo("insufficient_samples")
    }

    @Test
    fun isfCrShadowActivation_blocksWhenConfidenceLow() {
        val samples = List(20) {
            AutomationRepository.IsfCrShadowDiffSample(
                confidence = 0.5,
                isfDeltaPct = 5.0,
                crDeltaPct = 4.0
            )
        }
        val assessment = AutomationRepository.evaluateIsfCrShadowActivationStatic(
            samples = samples,
            minSamples = 12,
            minMeanConfidence = 0.65,
            maxMeanAbsIsfDeltaPct = 25.0,
            maxMeanAbsCrDeltaPct = 25.0
        )

        assertThat(assessment.eligible).isFalse()
        assertThat(assessment.reason).isEqualTo("low_mean_confidence")
    }

    @Test
    fun isfCrShadowActivation_blocksWhenDeltaTooHigh() {
        val samples = List(30) {
            AutomationRepository.IsfCrShadowDiffSample(
                confidence = 0.9,
                isfDeltaPct = 35.0,
                crDeltaPct = 10.0
            )
        }
        val assessment = AutomationRepository.evaluateIsfCrShadowActivationStatic(
            samples = samples,
            minSamples = 12,
            minMeanConfidence = 0.65,
            maxMeanAbsIsfDeltaPct = 25.0,
            maxMeanAbsCrDeltaPct = 25.0
        )

        assertThat(assessment.eligible).isFalse()
        assertThat(assessment.reason).isEqualTo("isf_delta_out_of_bounds")
    }

    @Test
    fun isfCrShadowActivation_allowsWhenKpiPasses() {
        val samples = List(80) { idx ->
            val confidence = if (idx % 2 == 0) 0.76 else 0.82
            AutomationRepository.IsfCrShadowDiffSample(
                confidence = confidence,
                isfDeltaPct = 12.0,
                crDeltaPct = 15.0
            )
        }
        val assessment = AutomationRepository.evaluateIsfCrShadowActivationStatic(
            samples = samples,
            minSamples = 72,
            minMeanConfidence = 0.65,
            maxMeanAbsIsfDeltaPct = 25.0,
            maxMeanAbsCrDeltaPct = 25.0
        )

        assertThat(assessment.eligible).isTrue()
        assertThat(assessment.reason).isEqualTo("eligible")
        assertThat(assessment.sampleCount).isEqualTo(80)
        assertThat(assessment.meanConfidence).isGreaterThan(0.75)
    }

    @Test
    fun isfCrDayTypeGate_blocksWhenSamplesInsufficient() {
        val samples = listOf(
            AutomationRepository.IsfCrDayTypeStabilitySample(
                isfSameDayTypeRatio = 0.8,
                crSameDayTypeRatio = 0.7,
                isfSparseFlag = false,
                crSparseFlag = false
            )
        )

        val assessment = AutomationRepository.evaluateIsfCrDayTypeStabilityStatic(
            samples = samples,
            minSamples = 12,
            minMeanSameDayTypeRatio = 0.3,
            maxSparseRatePct = 75.0
        )

        assertThat(assessment.eligible).isFalse()
        assertThat(assessment.reason).isEqualTo("insufficient_day_type_samples")
    }

    @Test
    fun isfCrDayTypeGate_blocksWhenSameDayTypeRatioLow() {
        val samples = List(20) {
            AutomationRepository.IsfCrDayTypeStabilitySample(
                isfSameDayTypeRatio = 0.20,
                crSameDayTypeRatio = 0.65,
                isfSparseFlag = false,
                crSparseFlag = false
            )
        }

        val assessment = AutomationRepository.evaluateIsfCrDayTypeStabilityStatic(
            samples = samples,
            minSamples = 12,
            minMeanSameDayTypeRatio = 0.30,
            maxSparseRatePct = 75.0
        )

        assertThat(assessment.eligible).isFalse()
        assertThat(assessment.reason).isEqualTo("isf_day_type_ratio_low")
    }

    @Test
    fun isfCrDayTypeGate_blocksWhenSparseRateTooHigh() {
        val samples = List(30) { idx ->
            AutomationRepository.IsfCrDayTypeStabilitySample(
                isfSameDayTypeRatio = 0.8,
                crSameDayTypeRatio = 0.78,
                isfSparseFlag = idx < 26,
                crSparseFlag = false
            )
        }

        val assessment = AutomationRepository.evaluateIsfCrDayTypeStabilityStatic(
            samples = samples,
            minSamples = 12,
            minMeanSameDayTypeRatio = 0.30,
            maxSparseRatePct = 75.0
        )

        assertThat(assessment.eligible).isFalse()
        assertThat(assessment.reason).isEqualTo("isf_day_type_sparse_rate_high")
    }

    @Test
    fun isfCrDayTypeGate_allowsWhenRatiosAndSparseRatesStable() {
        val samples = List(48) {
            AutomationRepository.IsfCrDayTypeStabilitySample(
                isfSameDayTypeRatio = 0.62,
                crSameDayTypeRatio = 0.58,
                isfSparseFlag = false,
                crSparseFlag = false
            )
        }

        val assessment = AutomationRepository.evaluateIsfCrDayTypeStabilityStatic(
            samples = samples,
            minSamples = 24,
            minMeanSameDayTypeRatio = 0.30,
            maxSparseRatePct = 75.0
        )

        assertThat(assessment.eligible).isTrue()
        assertThat(assessment.reason).isEqualTo("eligible")
        assertThat(assessment.meanIsfSameDayTypeRatio).isAtLeast(0.6)
        assertThat(assessment.meanCrSameDayTypeRatio).isAtLeast(0.5)
        assertThat(assessment.isfSparseRatePct).isEqualTo(0.0)
        assertThat(assessment.crSparseRatePct).isEqualTo(0.0)
    }

    @Test
    fun isfCrSensorGate_blocksWhenSamplesInsufficient() {
        val samples = listOf(
            AutomationRepository.IsfCrSensorQualitySample(
                qualityScore = 0.75,
                sensorFactor = 0.95,
                wearConfidencePenalty = 0.05,
                sensorAgeHighFlag = false,
                suspectFalseLowFlag = false
            )
        )

        val assessment = AutomationRepository.evaluateIsfCrSensorQualityStatic(
            samples = samples,
            minSamples = 12,
            minMeanQualityScore = 0.46,
            minMeanSensorFactor = 0.90,
            maxMeanWearPenalty = 0.12,
            maxSensorAgeHighRatePct = 70.0,
            maxSuspectFalseLowRatePct = 35.0
        )

        assertThat(assessment.eligible).isFalse()
        assertThat(assessment.reason).isEqualTo("insufficient_sensor_quality_samples")
    }

    @Test
    fun isfCrSensorGate_blocksWhenQualityLow() {
        val samples = List(24) {
            AutomationRepository.IsfCrSensorQualitySample(
                qualityScore = 0.35,
                sensorFactor = 0.94,
                wearConfidencePenalty = 0.04,
                sensorAgeHighFlag = false,
                suspectFalseLowFlag = false
            )
        }

        val assessment = AutomationRepository.evaluateIsfCrSensorQualityStatic(
            samples = samples,
            minSamples = 12,
            minMeanQualityScore = 0.46,
            minMeanSensorFactor = 0.90,
            maxMeanWearPenalty = 0.12,
            maxSensorAgeHighRatePct = 70.0,
            maxSuspectFalseLowRatePct = 35.0
        )

        assertThat(assessment.eligible).isFalse()
        assertThat(assessment.reason).isEqualTo("sensor_quality_score_low")
    }

    @Test
    fun isfCrSensorGate_blocksWhenWearPenaltyHigh() {
        val samples = List(24) {
            AutomationRepository.IsfCrSensorQualitySample(
                qualityScore = 0.78,
                sensorFactor = 0.94,
                wearConfidencePenalty = 0.17,
                sensorAgeHighFlag = false,
                suspectFalseLowFlag = false
            )
        }

        val assessment = AutomationRepository.evaluateIsfCrSensorQualityStatic(
            samples = samples,
            minSamples = 12,
            minMeanQualityScore = 0.46,
            minMeanSensorFactor = 0.90,
            maxMeanWearPenalty = 0.12,
            maxSensorAgeHighRatePct = 70.0,
            maxSuspectFalseLowRatePct = 35.0
        )

        assertThat(assessment.eligible).isFalse()
        assertThat(assessment.reason).isEqualTo("wear_penalty_high")
    }

    @Test
    fun isfCrSensorGate_allowsWhenMetricsStable() {
        val samples = List(36) { idx ->
            AutomationRepository.IsfCrSensorQualitySample(
                qualityScore = 0.72,
                sensorFactor = 0.95,
                wearConfidencePenalty = 0.06,
                sensorAgeHighFlag = idx < 8,
                suspectFalseLowFlag = idx < 10
            )
        }

        val assessment = AutomationRepository.evaluateIsfCrSensorQualityStatic(
            samples = samples,
            minSamples = 18,
            minMeanQualityScore = 0.46,
            minMeanSensorFactor = 0.90,
            maxMeanWearPenalty = 0.12,
            maxSensorAgeHighRatePct = 70.0,
            maxSuspectFalseLowRatePct = 35.0
        )

        assertThat(assessment.eligible).isTrue()
        assertThat(assessment.reason).isEqualTo("eligible")
        assertThat(assessment.meanQualityScore).isAtLeast(0.7)
        assertThat(assessment.meanSensorFactor).isAtLeast(0.9)
        assertThat(assessment.meanWearPenalty).isAtMost(0.12)
        assertThat(assessment.sensorAgeHighRatePct).isAtMost(70.0)
        assertThat(assessment.suspectFalseLowRatePct).isAtMost(35.0)
    }

    @Test
    fun isfCrSensorGate_blocksWhenSuspectFalseLowRateHigh() {
        val samples = List(36) { idx ->
            AutomationRepository.IsfCrSensorQualitySample(
                qualityScore = 0.75,
                sensorFactor = 0.95,
                wearConfidencePenalty = 0.06,
                sensorAgeHighFlag = false,
                suspectFalseLowFlag = idx < 20
            )
        }

        val assessment = AutomationRepository.evaluateIsfCrSensorQualityStatic(
            samples = samples,
            minSamples = 18,
            minMeanQualityScore = 0.46,
            minMeanSensorFactor = 0.90,
            maxMeanWearPenalty = 0.12,
            maxSensorAgeHighRatePct = 70.0,
            maxSuspectFalseLowRatePct = 35.0
        )

        assertThat(assessment.eligible).isFalse()
        assertThat(assessment.reason).isEqualTo("sensor_suspect_false_low_rate")
    }

    @Test
    fun isfCrDailyQualityGate_blocksWhenDailyReportMissing() {
        val assessment = AutomationRepository.evaluateIsfCrDailyQualityGateStatic(
            matchedSamples = null,
            mae30Mmol = null,
            mae60Mmol = null,
            hypoRatePct24h = 2.0,
            ciCoverage30Pct = 65.0,
            ciCoverage60Pct = 60.0,
            ciWidth30Mmol = 1.1,
            ciWidth60Mmol = 1.8,
            minDailyMatchedSamples = 120,
            maxDailyMae30Mmol = 0.9,
            maxDailyMae60Mmol = 1.4,
            maxHypoRatePct = 6.0,
            minDailyCiCoverage30Pct = 55.0,
            minDailyCiCoverage60Pct = 55.0,
            maxDailyCiWidth30Mmol = 1.8,
            maxDailyCiWidth60Mmol = 2.6
        )

        assertThat(assessment.eligible).isFalse()
        assertThat(assessment.reason).isEqualTo("daily_report_missing")
    }

    @Test
    fun isfCrDailyQualityGate_blocksWhenMaeOrHypoOutOfBounds() {
        val highMae = AutomationRepository.evaluateIsfCrDailyQualityGateStatic(
            matchedSamples = 240,
            mae30Mmol = 1.2,
            mae60Mmol = 1.3,
            hypoRatePct24h = 2.0,
            ciCoverage30Pct = 65.0,
            ciCoverage60Pct = 60.0,
            ciWidth30Mmol = 1.1,
            ciWidth60Mmol = 1.8,
            minDailyMatchedSamples = 120,
            maxDailyMae30Mmol = 0.9,
            maxDailyMae60Mmol = 1.4,
            maxHypoRatePct = 6.0,
            minDailyCiCoverage30Pct = 55.0,
            minDailyCiCoverage60Pct = 55.0,
            maxDailyCiWidth30Mmol = 1.8,
            maxDailyCiWidth60Mmol = 2.6
        )
        val highHypo = AutomationRepository.evaluateIsfCrDailyQualityGateStatic(
            matchedSamples = 240,
            mae30Mmol = 0.6,
            mae60Mmol = 1.1,
            hypoRatePct24h = 9.0,
            ciCoverage30Pct = 65.0,
            ciCoverage60Pct = 60.0,
            ciWidth30Mmol = 1.1,
            ciWidth60Mmol = 1.8,
            minDailyMatchedSamples = 120,
            maxDailyMae30Mmol = 0.9,
            maxDailyMae60Mmol = 1.4,
            maxHypoRatePct = 6.0,
            minDailyCiCoverage30Pct = 55.0,
            minDailyCiCoverage60Pct = 55.0,
            maxDailyCiWidth30Mmol = 1.8,
            maxDailyCiWidth60Mmol = 2.6
        )

        assertThat(highMae.eligible).isFalse()
        assertThat(highMae.reason).isEqualTo("daily_mae30_out_of_bounds")
        assertThat(highHypo.eligible).isFalse()
        assertThat(highHypo.reason).isEqualTo("daily_hypo_rate_out_of_bounds")
    }

    @Test
    fun isfCrDailyQualityGate_allowsWhenThresholdsPass() {
        val assessment = AutomationRepository.evaluateIsfCrDailyQualityGateStatic(
            matchedSamples = 260,
            mae30Mmol = 0.58,
            mae60Mmol = 1.08,
            hypoRatePct24h = 3.1,
            ciCoverage30Pct = 66.0,
            ciCoverage60Pct = 58.0,
            ciWidth30Mmol = 1.25,
            ciWidth60Mmol = 1.95,
            minDailyMatchedSamples = 120,
            maxDailyMae30Mmol = 0.9,
            maxDailyMae60Mmol = 1.4,
            maxHypoRatePct = 6.0,
            minDailyCiCoverage30Pct = 55.0,
            minDailyCiCoverage60Pct = 55.0,
            maxDailyCiWidth30Mmol = 1.8,
            maxDailyCiWidth60Mmol = 2.6
        )

        assertThat(assessment.eligible).isTrue()
        assertThat(assessment.reason).isEqualTo("eligible")
        assertThat(assessment.matchedSamples).isEqualTo(260)
    }

    @Test
    fun isfCrDailyQualityGate_blocksWhenCiCoverageOrWidthOutOfBounds() {
        val lowCoverage = AutomationRepository.evaluateIsfCrDailyQualityGateStatic(
            matchedSamples = 260,
            mae30Mmol = 0.58,
            mae60Mmol = 1.08,
            hypoRatePct24h = 3.1,
            ciCoverage30Pct = 49.0,
            ciCoverage60Pct = 58.0,
            ciWidth30Mmol = 1.25,
            ciWidth60Mmol = 1.95,
            minDailyMatchedSamples = 120,
            maxDailyMae30Mmol = 0.9,
            maxDailyMae60Mmol = 1.4,
            maxHypoRatePct = 6.0,
            minDailyCiCoverage30Pct = 55.0,
            minDailyCiCoverage60Pct = 55.0,
            maxDailyCiWidth30Mmol = 1.8,
            maxDailyCiWidth60Mmol = 2.6
        )
        val wideCi = AutomationRepository.evaluateIsfCrDailyQualityGateStatic(
            matchedSamples = 260,
            mae30Mmol = 0.58,
            mae60Mmol = 1.08,
            hypoRatePct24h = 3.1,
            ciCoverage30Pct = 66.0,
            ciCoverage60Pct = 58.0,
            ciWidth30Mmol = 2.4,
            ciWidth60Mmol = 1.95,
            minDailyMatchedSamples = 120,
            maxDailyMae30Mmol = 0.9,
            maxDailyMae60Mmol = 1.4,
            maxHypoRatePct = 6.0,
            minDailyCiCoverage30Pct = 55.0,
            minDailyCiCoverage60Pct = 55.0,
            maxDailyCiWidth30Mmol = 1.8,
            maxDailyCiWidth60Mmol = 2.6
        )

        assertThat(lowCoverage.eligible).isFalse()
        assertThat(lowCoverage.reason).isEqualTo("daily_ci_coverage30_out_of_bounds")
        assertThat(wideCi.eligible).isFalse()
        assertThat(wideCi.reason).isEqualTo("daily_ci_width30_out_of_bounds")
    }

    @Test
    fun isfCrRollingGate_blocksWhenNotEnoughWindowsAvailable() {
        val windows = listOf(
            AutomationRepository.IsfCrRollingQualityWindowAssessment(
                days = 14,
                available = true,
                eligible = true,
                reason = "eligible",
                matchedSamples = 900,
                mae30Mmol = 0.7,
                mae60Mmol = 1.1,
                ciCoverage30Pct = 60.0,
                ciCoverage60Pct = 58.0,
                ciWidth30Mmol = 1.4,
                ciWidth60Mmol = 2.0
            ),
            AutomationRepository.IsfCrRollingQualityWindowAssessment(
                days = 30,
                available = false,
                eligible = false,
                reason = "rolling_report_missing",
                matchedSamples = null,
                mae30Mmol = null,
                mae60Mmol = null,
                ciCoverage30Pct = null,
                ciCoverage60Pct = null,
                ciWidth30Mmol = null,
                ciWidth60Mmol = null
            ),
            AutomationRepository.IsfCrRollingQualityWindowAssessment(
                days = 90,
                available = false,
                eligible = false,
                reason = "rolling_report_missing",
                matchedSamples = null,
                mae30Mmol = null,
                mae60Mmol = null,
                ciCoverage30Pct = null,
                ciCoverage60Pct = null,
                ciWidth30Mmol = null,
                ciWidth60Mmol = null
            )
        )

        val assessment = AutomationRepository.evaluateIsfCrRollingQualityGateStatic(
            windows = windows,
            minRequiredWindows = 2
        )

        assertThat(assessment.eligible).isFalse()
        assertThat(assessment.reason).isEqualTo("rolling_windows_insufficient")
        assertThat(assessment.requiredWindowCount).isEqualTo(2)
        assertThat(assessment.evaluatedWindowCount).isEqualTo(1)
    }

    @Test
    fun isfCrRollingGate_blocksWhenAnyAvailableWindowFails() {
        val windows = listOf(
            AutomationRepository.IsfCrRollingQualityWindowAssessment(
                days = 14,
                available = true,
                eligible = true,
                reason = "eligible",
                matchedSamples = 900,
                mae30Mmol = 0.7,
                mae60Mmol = 1.1,
                ciCoverage30Pct = 60.0,
                ciCoverage60Pct = 58.0,
                ciWidth30Mmol = 1.4,
                ciWidth60Mmol = 2.0
            ),
            AutomationRepository.IsfCrRollingQualityWindowAssessment(
                days = 30,
                available = true,
                eligible = false,
                reason = "daily_mae60_out_of_bounds",
                matchedSamples = 1800,
                mae30Mmol = 0.9,
                mae60Mmol = 1.8,
                ciCoverage30Pct = 58.0,
                ciCoverage60Pct = 56.0,
                ciWidth30Mmol = 1.5,
                ciWidth60Mmol = 2.2
            ),
            AutomationRepository.IsfCrRollingQualityWindowAssessment(
                days = 90,
                available = false,
                eligible = false,
                reason = "rolling_report_missing",
                matchedSamples = null,
                mae30Mmol = null,
                mae60Mmol = null,
                ciCoverage30Pct = null,
                ciCoverage60Pct = null,
                ciWidth30Mmol = null,
                ciWidth60Mmol = null
            )
        )

        val assessment = AutomationRepository.evaluateIsfCrRollingQualityGateStatic(
            windows = windows,
            minRequiredWindows = 2
        )

        assertThat(assessment.eligible).isFalse()
        assertThat(assessment.reason).isEqualTo("rolling_30d_daily_mae60_out_of_bounds")
        assertThat(assessment.evaluatedWindowCount).isEqualTo(2)
        assertThat(assessment.passedWindowCount).isEqualTo(1)
    }

    @Test
    fun isfCrRollingGate_allowsWhenRequiredWindowsPass() {
        val windows = listOf(
            AutomationRepository.IsfCrRollingQualityWindowAssessment(
                days = 14,
                available = true,
                eligible = true,
                reason = "eligible",
                matchedSamples = 900,
                mae30Mmol = 0.7,
                mae60Mmol = 1.1,
                ciCoverage30Pct = 60.0,
                ciCoverage60Pct = 58.0,
                ciWidth30Mmol = 1.4,
                ciWidth60Mmol = 2.0
            ),
            AutomationRepository.IsfCrRollingQualityWindowAssessment(
                days = 30,
                available = true,
                eligible = true,
                reason = "eligible",
                matchedSamples = 1800,
                mae30Mmol = 0.8,
                mae60Mmol = 1.2,
                ciCoverage30Pct = 59.0,
                ciCoverage60Pct = 57.0,
                ciWidth30Mmol = 1.5,
                ciWidth60Mmol = 2.2
            ),
            AutomationRepository.IsfCrRollingQualityWindowAssessment(
                days = 90,
                available = false,
                eligible = false,
                reason = "rolling_report_missing",
                matchedSamples = null,
                mae30Mmol = null,
                mae60Mmol = null,
                ciCoverage30Pct = null,
                ciCoverage60Pct = null,
                ciWidth30Mmol = null,
                ciWidth60Mmol = null
            )
        )

        val assessment = AutomationRepository.evaluateIsfCrRollingQualityGateStatic(
            windows = windows,
            minRequiredWindows = 2
        )

        assertThat(assessment.eligible).isTrue()
        assertThat(assessment.reason).isEqualTo("eligible")
        assertThat(assessment.requiredWindowCount).isEqualTo(2)
        assertThat(assessment.evaluatedWindowCount).isEqualTo(2)
        assertThat(assessment.passedWindowCount).isEqualTo(2)
    }

    @Test
    fun isfCrDailyRiskGate_blocksWhenRiskHigh() {
        val assessment = AutomationRepository.evaluateIsfCrDailyRiskGateStatic(
            riskLevel = 3,
            blockedRiskLevel = 3
        )

        assertThat(assessment.eligible).isFalse()
        assertThat(assessment.reason).isEqualTo("daily_risk_high")
        assertThat(assessment.riskLevel).isEqualTo(3)
    }

    @Test
    fun isfCrDailyRiskGate_allowsWhenRiskMedium() {
        val assessment = AutomationRepository.evaluateIsfCrDailyRiskGateStatic(
            riskLevel = 2,
            blockedRiskLevel = 3
        )

        assertThat(assessment.eligible).isTrue()
        assertThat(assessment.reason).isEqualTo("eligible")
        assertThat(assessment.riskLevel).isEqualTo(2)
    }

    @Test
    fun isfCrDailyRiskGate_allowsWhenRiskMissing() {
        val assessment = AutomationRepository.evaluateIsfCrDailyRiskGateStatic(
            riskLevel = null,
            blockedRiskLevel = 3
        )

        assertThat(assessment.eligible).isTrue()
        assertThat(assessment.reason).isEqualTo("daily_risk_missing_or_unknown")
        assertThat(assessment.riskLevel).isEqualTo(0)
    }

    @Test
    fun isfCrDailyRiskGate_blocksMediumWhenThresholdIsMedium() {
        val assessment = AutomationRepository.evaluateIsfCrDailyRiskGateStatic(
            riskLevel = 2,
            blockedRiskLevel = 2
        )

        assertThat(assessment.eligible).isFalse()
        assertThat(assessment.reason).isEqualTo("daily_risk_high")
        assertThat(assessment.riskLevel).isEqualTo(2)
    }

    @Test
    fun isfCrDailyRiskGate_clampsConfiguredThresholdIntoSafeRange() {
        val assessment = AutomationRepository.evaluateIsfCrDailyRiskGateStatic(
            riskLevel = 2,
            blockedRiskLevel = 99
        )

        assertThat(assessment.eligible).isTrue()
        assertThat(assessment.reason).isEqualTo("eligible")
        assertThat(assessment.riskLevel).isEqualTo(2)
    }

    @Test
    fun parseIsfCrQualityRiskLevel_parsesEnglishLabels() {
        assertThat(
            AutomationRepository.parseIsfCrQualityRiskLevelFromTextStatic("HIGH(gap)")
        ).isEqualTo(3)
        assertThat(
            AutomationRepository.parseIsfCrQualityRiskLevelFromTextStatic("MEDIUM(sensorBlocked)")
        ).isEqualTo(2)
        assertThat(
            AutomationRepository.parseIsfCrQualityRiskLevelFromTextStatic("LOW")
        ).isEqualTo(1)
        assertThat(
            AutomationRepository.parseIsfCrQualityRiskLevelFromTextStatic("UNKNOWN")
        ).isEqualTo(0)
    }

    @Test
    fun parseIsfCrQualityRiskLevel_parsesRussianLabelsAndNumeric() {
        assertThat(
            AutomationRepository.parseIsfCrQualityRiskLevelFromTextStatic("ВЫСОКИЙ (sensor)")
        ).isEqualTo(3)
        assertThat(
            AutomationRepository.parseIsfCrQualityRiskLevelFromTextStatic("средний")
        ).isEqualTo(2)
        assertThat(
            AutomationRepository.parseIsfCrQualityRiskLevelFromTextStatic("низкий риск")
        ).isEqualTo(1)
        assertThat(
            AutomationRepository.parseIsfCrQualityRiskLevelFromTextStatic("неизвестно")
        ).isEqualTo(0)
        assertThat(
            AutomationRepository.parseIsfCrQualityRiskLevelFromTextStatic("3")
        ).isEqualTo(3)
        assertThat(
            AutomationRepository.parseIsfCrQualityRiskLevelFromTextStatic("2")
        ).isEqualTo(2)
    }

    @Test
    fun parseIsfCrQualityRiskLevel_returnsNullForUnrecognizedText() {
        assertThat(
            AutomationRepository.parseIsfCrQualityRiskLevelFromTextStatic("RISK?")
        ).isNull()
        assertThat(
            AutomationRepository.parseIsfCrQualityRiskLevelFromTextStatic("")
        ).isNull()
        assertThat(
            AutomationRepository.parseIsfCrQualityRiskLevelFromTextStatic(null)
        ).isNull()
    }

    @Test
    fun resolveIsfCrDailyRiskLevelSource_prefersTextFallbackFlag() {
        val source = AutomationRepository.resolveIsfCrDailyRiskLevelSourceStatic(
            riskLevel = 2,
            fallbackUsed = true
        )

        assertThat(source).isEqualTo("text_fallback")
    }

    @Test
    fun resolveIsfCrDailyRiskLevelSource_marksMissingWhenUnknown() {
        val source = AutomationRepository.resolveIsfCrDailyRiskLevelSourceStatic(
            riskLevel = 0,
            fallbackUsed = false
        )

        assertThat(source).isEqualTo("missing_or_unknown")
    }

    @Test
    fun sensorLagShadowRuleChanged_detectsRuleOrActionDrift() {
        val baseline = listOf(
            triggeredDecision(ruleId = "adaptive_target", type = "temp_target", targetMmol = 5.6)
        )
        val changedRule = listOf(
            triggeredDecision(ruleId = "pattern_target", type = "temp_target", targetMmol = 5.6)
        )
        val changedTarget = listOf(
            triggeredDecision(ruleId = "adaptive_target", type = "temp_target", targetMmol = 5.68)
        )
        val unchanged = listOf(
            triggeredDecision(ruleId = "adaptive_target", type = "temp_target", targetMmol = 5.62)
        )

        assertThat(
            AutomationRepository.hasSensorLagShadowRuleChangedStatic(
                baseline = baseline,
                candidate = changedRule
            )
        ).isTrue()
        assertThat(
            AutomationRepository.hasSensorLagShadowRuleChangedStatic(
                baseline = baseline,
                candidate = changedTarget
            )
        ).isTrue()
        assertThat(
            AutomationRepository.hasSensorLagShadowRuleChangedStatic(
                baseline = baseline,
                candidate = unchanged
            )
        ).isFalse()
    }

    @Test
    fun sensorLagShadowTargetDelta_returnsSignedTargetShift() {
        val baseline = listOf(
            triggeredDecision(ruleId = "adaptive_target", type = "temp_target", targetMmol = 5.5)
        )
        val higherCandidate = listOf(
            triggeredDecision(ruleId = "adaptive_target", type = "temp_target", targetMmol = 5.9)
        )
        val noCandidate = emptyList<RuleDecision>()

        assertThat(
            AutomationRepository.resolveSensorLagShadowTargetDeltaMmolStatic(
                baseline = baseline,
                candidate = higherCandidate
            )
        ).isWithin(0.001).of(0.4)
        assertThat(
            AutomationRepository.resolveSensorLagShadowTargetDeltaMmolStatic(
                baseline = baseline,
                candidate = noCandidate
            )
        ).isWithin(0.001).of(-5.5)
        assertThat(
            AutomationRepository.resolveSensorLagShadowTargetDeltaMmolStatic(
                baseline = emptyList(),
                candidate = emptyList()
            )
        ).isNull()
    }

    @Test
    fun sensorLagControlPlan_offModeKeepsRawControlPath() {
        val merged = sampleForecasts()
        val lagCorrected = merged.map {
            it.copy(valueMmol = it.valueMmol + 0.3, modelVersion = "${it.modelVersion}|lag")
        }

        val plan = AutomationRepository.resolveSensorLagControlPlanStatic(
            requestedMode = SensorLagCorrectionMode.OFF,
            estimate = sampleSensorLagEstimate(mode = SensorLagCorrectionMode.OFF),
            rawCurrentGlucoseMmol = 6.0,
            mergedForecasts = merged,
            lagCorrectedForecasts = lagCorrected
        )

        assertThat(plan.effectiveCurrentGlucoseMmol).isWithin(0.001).of(6.0)
        assertThat(plan.controlForecasts).containsExactlyElementsIn(merged).inOrder()
        assertThat(plan.shouldEvaluateShadow).isFalse()
        assertThat(plan.shadowCurrentGlucoseMmol).isNull()
        assertThat(plan.shadowForecasts).isNull()
    }

    @Test
    fun sensorLagControlPlan_shadowFallbackKeepsLiveRawButRunsShadowPath() {
        val merged = sampleForecasts()
        val lagCorrected = merged.map {
            it.copy(valueMmol = it.valueMmol + 0.4, modelVersion = "${it.modelVersion}|lag")
        }

        val plan = AutomationRepository.resolveSensorLagControlPlanStatic(
            requestedMode = SensorLagCorrectionMode.ACTIVE,
            estimate = sampleSensorLagEstimate(
                mode = SensorLagCorrectionMode.SHADOW,
                rawGlucoseMmol = 6.0,
                correctedGlucoseMmol = 6.6
            ),
            rawCurrentGlucoseMmol = 6.0,
            mergedForecasts = merged,
            lagCorrectedForecasts = lagCorrected
        )

        assertThat(plan.effectiveCurrentGlucoseMmol).isWithin(0.001).of(6.0)
        assertThat(plan.controlForecasts).containsExactlyElementsIn(merged).inOrder()
        assertThat(plan.shouldEvaluateShadow).isTrue()
        assertThat(plan.shadowCurrentGlucoseMmol).isWithin(0.001).of(6.6)
        assertThat(plan.shadowForecasts).containsExactlyElementsIn(lagCorrected).inOrder()
    }

    @Test
    fun sensorLagControlPlan_activeModeUsesCorrectedControlPath() {
        val merged = sampleForecasts()
        val lagCorrected = merged.map {
            it.copy(valueMmol = it.valueMmol + 0.5, modelVersion = "${it.modelVersion}|lag")
        }

        val plan = AutomationRepository.resolveSensorLagControlPlanStatic(
            requestedMode = SensorLagCorrectionMode.ACTIVE,
            estimate = sampleSensorLagEstimate(
                mode = SensorLagCorrectionMode.ACTIVE,
                rawGlucoseMmol = 6.0,
                correctedGlucoseMmol = 6.7
            ),
            rawCurrentGlucoseMmol = 6.0,
            mergedForecasts = merged,
            lagCorrectedForecasts = lagCorrected
        )

        assertThat(plan.effectiveCurrentGlucoseMmol).isWithin(0.001).of(6.7)
        assertThat(plan.controlForecasts).containsExactlyElementsIn(lagCorrected).inOrder()
        assertThat(plan.shouldEvaluateShadow).isFalse()
        assertThat(plan.shadowCurrentGlucoseMmol).isNull()
        assertThat(plan.shadowForecasts).isNull()
    }

    @Test
    fun sensorLagTelemetryRows_includeExpectedKeysAndForecastHorizons() {
        val nowTs = 1_736_000_000_000L
        val rows = AutomationRepository.buildSensorLagTelemetryRowsStatic(
            nowTs = nowTs,
            estimate = sampleSensorLagEstimate(
                mode = SensorLagCorrectionMode.ACTIVE,
                rawGlucoseMmol = 6.1,
                correctedGlucoseMmol = 6.6
            ),
            latestGlucoseInput = GlucoseInputMetadata(
                key = "raw_sgv",
                kind = "raw"
            ),
            controlForecasts = listOf(
                Forecast(nowTs + 5 * 60_000L, 5, 6.6, 6.2, 7.0, "control"),
                Forecast(nowTs + 15 * 60_000L, 15, 6.8, 6.1, 7.5, "control"),
                Forecast(nowTs + 30 * 60_000L, 30, 7.0, 6.3, 7.7, "control"),
                Forecast(nowTs + 60 * 60_000L, 60, 7.3, 6.4, 8.2, "control")
            ),
            candidateForecasts = listOf(
                Forecast(nowTs + 5 * 60_000L, 5, 6.7, 6.3, 7.1, "lag"),
                Forecast(nowTs + 15 * 60_000L, 15, 6.9, 6.2, 7.6, "lag"),
                Forecast(nowTs + 30 * 60_000L, 30, 7.1, 6.4, 7.8, "lag"),
                Forecast(nowTs + 60 * 60_000L, 60, 7.4, 6.5, 8.3, "lag")
            ),
            shadowRuleChanged = true,
            shadowTargetDeltaMmol = 0.25
        )

        val byKey = rows.associateBy { it.key }
        assertThat(byKey.keys).containsAtLeast(
            "sensor_lag_age_hours",
            "sensor_lag_age_source",
            "sensor_lag_minutes",
            "sensor_lag_correction_mmol",
            "sensor_lag_corrected_glucose_mmol",
            "sensor_lag_confidence",
            "sensor_lag_active",
            "sensor_lag_mode",
            "sensor_lag_disable_reason",
            "sensor_lag_shadow_rule_changed",
            "sensor_lag_shadow_target_delta_mmol",
            "sensor_lag_control_forecast_5m",
            "sensor_lag_control_forecast_30m",
            "sensor_lag_control_forecast_60m",
            "sensor_lag_candidate_forecast_5m",
            "sensor_lag_candidate_forecast_30m",
            "sensor_lag_candidate_forecast_60m",
            "glucose_input_key",
            "glucose_input_kind"
        )
        assertThat(byKey.keys).doesNotContain("sensor_lag_control_forecast_15m")
        assertThat(byKey.keys).doesNotContain("sensor_lag_candidate_forecast_15m")
        assertThat(byKey["sensor_lag_active"]?.valueDouble).isWithin(0.001).of(1.0)
        assertThat(byKey["sensor_lag_mode"]?.valueText).isEqualTo("ACTIVE")
        assertThat(byKey["sensor_lag_age_source"]?.valueText).isEqualTo("explicit_event")
        assertThat(byKey["sensor_lag_wear_bucket"]?.valueText).isEqualTo("10-12d")
        assertThat(byKey["sensor_lag_source_confidence"]?.valueDouble).isWithin(0.001).of(0.82)
        assertThat(byKey["sensor_lag_trend_consistency"]?.valueDouble).isWithin(0.001).of(0.91)
        assertThat(byKey["sensor_lag_replay_multiplier"]?.valueDouble).isWithin(0.001).of(1.0)
        assertThat(byKey["sensor_lag_effective_lag_minutes"]?.valueDouble).isWithin(0.001).of(12.0)
        assertThat(byKey["sensor_lag_effective_correction_cap"]?.valueDouble).isWithin(0.001).of(1.5)
        assertThat(byKey["sensor_lag_shadow_rule_changed"]?.valueDouble).isWithin(0.001).of(1.0)
        assertThat(byKey["sensor_lag_shadow_target_delta_mmol"]?.valueDouble).isWithin(0.001).of(0.25)
        assertThat(byKey["sensor_lag_control_forecast_30m"]?.valueDouble).isWithin(0.001).of(7.0)
        assertThat(byKey["sensor_lag_candidate_forecast_30m"]?.valueDouble).isWithin(0.001).of(7.1)
        assertThat(byKey["glucose_input_key"]?.valueText).isEqualTo("raw_sgv")
        assertThat(byKey["glucose_input_kind"]?.valueText).isEqualTo("raw")
        assertThat(rows.all { it.timestamp == nowTs }).isTrue()
        assertThat(rows.all { it.source == "copilot_sensor_lag" }).isTrue()
    }

    @Test
    fun sensorLagTelemetryRows_markMissingOptionalValuesAsStale() {
        val rows = AutomationRepository.buildSensorLagTelemetryRowsStatic(
            nowTs = 42L,
            estimate = sampleSensorLagEstimate(
                mode = SensorLagCorrectionMode.SHADOW,
                rawGlucoseMmol = 5.8,
                correctedGlucoseMmol = 6.0
            ).copy(
                ageHours = null,
                ageSource = SensorLagAgeSource.MISSING,
                disableReason = "missing_sensor_age"
            ),
            latestGlucoseInput = GlucoseInputMetadata(
                key = " ",
                kind = " "
            ),
            controlForecasts = emptyList(),
            candidateForecasts = emptyList(),
            shadowRuleChanged = null,
            shadowTargetDeltaMmol = null
        )

        val byKey = rows.associateBy { it.key }
        assertThat(byKey["sensor_lag_age_hours"]?.quality).isEqualTo("STALE")
        assertThat(byKey["sensor_lag_age_source"]?.valueText).isEqualTo("missing")
        assertThat(byKey["sensor_lag_active"]?.valueDouble).isWithin(0.001).of(0.0)
        assertThat(byKey["sensor_lag_mode"]?.valueText).isEqualTo("SHADOW")
        assertThat(byKey["sensor_lag_disable_reason"]?.valueText).isEqualTo("missing_sensor_age")
        assertThat(byKey["sensor_lag_shadow_rule_changed"]?.quality).isEqualTo("STALE")
        assertThat(byKey["sensor_lag_shadow_target_delta_mmol"]?.quality).isEqualTo("STALE")
        assertThat(byKey["glucose_input_key"]?.quality).isEqualTo("STALE")
        assertThat(byKey["glucose_input_kind"]?.quality).isEqualTo("STALE")
    }

    @Test
    fun sensorLagTelemetryRows_offModeOmitsCandidateForecasts() {
        val nowTs = 1_736_000_000_000L
        val rows = AutomationRepository.buildSensorLagTelemetryRowsStatic(
            nowTs = nowTs,
            estimate = sampleSensorLagEstimate(
                mode = SensorLagCorrectionMode.OFF,
                rawGlucoseMmol = 6.1,
                correctedGlucoseMmol = 6.1
            ).copy(
                lagMinutes = 0.0,
                correctionMmol = 0.0,
                confidence = 0.0,
                disableReason = "mode_off"
            ),
            latestGlucoseInput = GlucoseInputMetadata(
                key = "sgv",
                kind = "sgv"
            ),
            controlForecasts = listOf(
                Forecast(nowTs + 5 * 60_000L, 5, 6.1, 5.8, 6.4, "control"),
                Forecast(nowTs + 30 * 60_000L, 30, 6.4, 5.9, 6.9, "control"),
                Forecast(nowTs + 60 * 60_000L, 60, 6.8, 6.0, 7.4, "control")
            ),
            candidateForecasts = listOf(
                Forecast(nowTs + 5 * 60_000L, 5, 6.2, 5.9, 6.5, "lag"),
                Forecast(nowTs + 30 * 60_000L, 30, 6.5, 6.0, 7.0, "lag"),
                Forecast(nowTs + 60 * 60_000L, 60, 6.9, 6.1, 7.5, "lag")
            ),
            shadowRuleChanged = null,
            shadowTargetDeltaMmol = null
        )

        val byKey = rows.associateBy { it.key }
        assertThat(byKey["sensor_lag_mode"]?.valueText).isEqualTo("OFF")
        assertThat(byKey.keys).containsAtLeast(
            "sensor_lag_control_forecast_5m",
            "sensor_lag_control_forecast_30m",
            "sensor_lag_control_forecast_60m"
        )
        assertThat(byKey.keys).doesNotContain("sensor_lag_candidate_forecast_5m")
        assertThat(byKey.keys).doesNotContain("sensor_lag_candidate_forecast_30m")
        assertThat(byKey.keys).doesNotContain("sensor_lag_candidate_forecast_60m")
    }

    @Test
    fun resolveLatestGlucoseInputMetadata_prefersMatchingSourceRows() {
        val ts = 1_736_000_000_000L
        val resolved = AutomationRepository.resolveLatestGlucoseInputMetadataStatic(
            rows = listOf(
                telemetryText(ts = ts, source = "nightscout", key = "glucose_input_key", value = "sgv"),
                telemetryText(ts = ts, source = "nightscout", key = "glucose_input_kind", value = "sgv"),
                telemetryText(ts = ts, source = "aaps_broadcast", key = "glucose_input_key", value = "raw_sgv"),
                telemetryText(ts = ts, source = "aaps_broadcast", key = "glucose_input_kind", value = "raw")
            ),
            latestGlucoseTs = ts,
            latestGlucoseSource = "aaps_broadcast"
        )

        assertThat(resolved).isEqualTo(GlucoseInputMetadata(key = "raw_sgv", kind = "raw"))
    }

    @Test
    fun resolveLatestGlucoseInputMetadata_fallsBackToTimestampWhenSourceDoesNotMatch() {
        val ts = 1_736_000_000_000L
        val resolved = AutomationRepository.resolveLatestGlucoseInputMetadataStatic(
            rows = listOf(
                telemetryText(ts = ts, source = "nightscout", key = "glucose_input_key", value = "sgv"),
                telemetryText(ts = ts, source = "nightscout", key = "glucose_input_kind", value = "estimate"),
                telemetryText(ts = ts - 5_000L, source = "nightscout", key = "glucose_input_key", value = "older")
            ),
            latestGlucoseTs = ts,
            latestGlucoseSource = "xdrip_broadcast"
        )

        assertThat(resolved).isEqualTo(GlucoseInputMetadata(key = "sgv", kind = "estimate"))
    }

    @Test
    fun sensorLagTelemetryMapUpdates_encodeAgeSourceAndInputKind() {
        val updates = AutomationRepository.buildSensorLagTelemetryMapUpdatesStatic(
            estimate = sampleSensorLagEstimate(
                mode = SensorLagCorrectionMode.ACTIVE,
                rawGlucoseMmol = 6.0,
                correctedGlucoseMmol = 6.5
            ).copy(ageSource = SensorLagAgeSource.INFERRED_BOUNDARY),
            latestGlucoseInput = GlucoseInputMetadata(
                key = "raw_sgv",
                kind = "raw"
            )
        )

        assertThat(updates["sensor_lag_age_hours"]).isWithin(0.001).of(280.0)
        assertThat(updates["sensor_lag_minutes"]).isWithin(0.001).of(12.0)
        assertThat(updates["sensor_lag_correction_mmol"]).isWithin(0.001).of(0.5)
        assertThat(updates["sensor_lag_corrected_glucose_mmol"]).isWithin(0.001).of(6.5)
        assertThat(updates["sensor_lag_confidence"]).isWithin(0.001).of(0.82)
        assertThat(updates["sensor_lag_source_confidence"]).isWithin(0.001).of(0.82)
        assertThat(updates["sensor_lag_trend_consistency"]).isWithin(0.001).of(0.91)
        assertThat(updates["sensor_lag_replay_multiplier"]).isWithin(0.001).of(1.0)
        assertThat(updates["sensor_lag_effective_lag_minutes"]).isWithin(0.001).of(12.0)
        assertThat(updates["sensor_lag_effective_correction_cap"]).isWithin(0.001).of(1.5)
        assertThat(updates["sensor_lag_active"]).isWithin(0.001).of(1.0)
        assertThat(updates["sensor_lag_age_source"]).isWithin(0.001).of(0.5)
        assertThat(updates["glucose_input_kind"]).isWithin(0.001).of(1.0)
    }

    @Test
    fun sensorLagTelemetryMapUpdates_defaultUnknownInputKindToZero() {
        val updates = AutomationRepository.buildSensorLagTelemetryMapUpdatesStatic(
            estimate = sampleSensorLagEstimate(
                mode = SensorLagCorrectionMode.SHADOW
            ).copy(ageSource = SensorLagAgeSource.MISSING),
            latestGlucoseInput = GlucoseInputMetadata(
                key = "sgv",
                kind = "sgv"
            )
        )

        assertThat(updates["sensor_lag_active"]).isWithin(0.001).of(0.0)
        assertThat(updates["sensor_lag_age_source"]).isWithin(0.001).of(0.0)
        assertThat(updates["glucose_input_kind"]).isWithin(0.001).of(0.0)
    }

    private fun sampleForecasts(): List<Forecast> {
        val now = System.currentTimeMillis()
        return listOf(
            Forecast(now + 5 * 60_000L, 5, 6.0, 5.5, 6.5, "test"),
            Forecast(now + 30 * 60_000L, 30, 6.4, 5.7, 7.1, "test"),
            Forecast(now + 60 * 60_000L, 60, 6.8, 5.8, 7.8, "test")
        )
    }

    private fun sampleIsfCrSnapshot(
        mode: IsfCrRuntimeMode,
        confidence: Double,
        factors: Map<String, Double> = mapOf("activity" to 1.0)
    ): IsfCrRealtimeSnapshot {
        val now = System.currentTimeMillis()
        return IsfCrRealtimeSnapshot(
            id = "snapshot-$now",
            ts = now,
            isfEff = 2.8,
            crEff = 11.0,
            isfBase = 2.5,
            crBase = 10.0,
            ciIsfLow = 2.0,
            ciIsfHigh = 3.2,
            ciCrLow = 9.0,
            ciCrHigh = 12.5,
            confidence = confidence,
            qualityScore = 0.8,
            factors = factors,
            mode = mode,
            isfEvidenceCount = 4,
            crEvidenceCount = 5,
            reasons = emptyList()
        )
    }

    private fun triggeredDecision(
        ruleId: String,
        type: String,
        targetMmol: Double
    ): RuleDecision {
        return RuleDecision(
            ruleId = ruleId,
            state = RuleState.TRIGGERED,
            reasons = listOf("test"),
            actionProposal = ActionProposal(
                type = type,
                targetMmol = targetMmol,
                durationMinutes = 30,
                reason = "test"
            )
        )
    }

    private fun sampleSensorLagEstimate(
        mode: SensorLagCorrectionMode,
        rawGlucoseMmol: Double = 6.0,
        correctedGlucoseMmol: Double = 6.4
    ): SensorLagEstimate {
        return SensorLagEstimate(
            rawGlucoseMmol = rawGlucoseMmol,
            correctedGlucoseMmol = correctedGlucoseMmol,
            lagMinutes = 12.0,
            correctionMmol = correctedGlucoseMmol - rawGlucoseMmol,
            ageHours = 280.0,
            ageSource = SensorLagAgeSource.EXPLICIT_EVENT,
            wearBucket = "10-12d",
            sourceConfidence = 0.82,
            trendConsistency = 0.91,
            replayMultiplier = 1.0,
            effectiveLagMinutes = 12.0,
            effectiveCorrectionCap = 1.5,
            confidence = 0.82,
            mode = mode,
            disableReason = null
        )
    }

    private fun telemetryText(
        ts: Long,
        source: String,
        key: String,
        value: String
    ): TelemetrySampleEntity {
        return TelemetrySampleEntity(
            id = "tm-$key-$ts-$source",
            timestamp = ts,
            source = source,
            key = key,
            valueDouble = null,
            valueText = value,
            unit = null,
            quality = "OK"
        )
    }

    private fun sampleCircadianPrior(
        delta15: Double,
        delta30: Double,
        delta60: Double,
        confidence: Double,
        acuteAttenuation: Double,
        staleBlocked: Boolean
    ): CircadianForecastPrior {
        return CircadianForecastPrior(
            requestedDayType = CircadianDayType.WEEKDAY,
            segmentSource = CircadianDayType.WEEKDAY,
            slotIndex = 48,
            bgMedian = 6.4,
            slotP10 = 5.7,
            slotP25 = 6.0,
            slotP75 = 6.8,
            slotP90 = 7.1,
            delta15 = delta15,
            delta30 = delta30,
            delta60 = delta60,
            residualBias30 = 0.0,
            residualBias60 = 0.0,
            medianReversion30 = 0.0,
            medianReversion60 = 0.0,
            replayBias30 = 0.0,
            replayBias60 = 0.0,
            replaySampleCount30 = 12,
            replaySampleCount60 = 12,
            replayWinRate30 = 0.60,
            replayWinRate60 = 0.60,
            replayMaeImprovement30 = -0.08,
            replayMaeImprovement60 = -0.10,
            replayBucketStatus30 = CircadianReplayBucketStatus.HELPFUL,
            replayBucketStatus60 = CircadianReplayBucketStatus.HELPFUL,
            confidence = confidence,
            qualityScore = 0.8,
            stabilityScore = 0.85,
            horizonQuality30 = 0.90,
            horizonQuality60 = 0.92,
            acuteAttenuation = acuteAttenuation,
            staleBlocked = staleBlocked
        )
    }

    private fun buildDiagnostics(): HybridPredictionEngine.V3Diagnostics {
        val therapyCum = MutableList(13) { idx -> idx * 0.25 }
        val trendStep = listOf(0.0) + List(12) { 0.1 }
        val uamStep = listOf(0.0) + List(12) { 0.05 }
        val therapyStep = listOf(0.0) + List(12) { 0.25 }
        val glucosePath = listOf(6.0) + List(12) { idx -> 6.0 + (idx + 1) * 0.08 }
        return HybridPredictionEngine.V3Diagnostics(
            gNowRaw = 6.0,
            gNowUsed = 6.0,
            rocPer5Used = 0.05,
            kfSigmaG = 0.21,
            kfEwmaNis = 1.0,
            kfSigmaZ = 0.2,
            kfSigmaA = 0.03,
            kfWarmedUp = true,
            insulinProfileId = "novorapid",
            insulinDurationHours = 5.0,
            insulinAgeScale = 1.0,
            residualRoc0 = -0.12,
            uci0 = 0.08,
            uciMax = 0.25,
            k = -0.01,
            uamActive = false,
            virtualMealCarbs = null,
            virtualMealConfidence = null,
            usingVirtualMeal = false,
            uamTailGuardMultiplier = 1.0,
            arMu = -0.03,
            arPhi = 0.82,
            arSigmaE = 0.18,
            arUsedFallback = false,
            therapyStep = therapyStep,
            therapyCumClamped = therapyCum,
            carbFastActiveGrams = 0.0,
            carbMediumActiveGrams = 0.0,
            carbProteinSlowActiveGrams = 0.0,
            residualCarbsNowGrams = 0.0,
            residualCarbs30mGrams = 0.0,
            residualCarbs60mGrams = 0.0,
            residualCarbs120mGrams = 0.0,
            uamStep = uamStep,
            trendStep = trendStep,
            glucosePath = glucosePath,
            trendCum60Raw = 1.2,
            trendCum60Clamped = 1.2,
            predByHorizon = mapOf(5 to 6.1, 30 to 6.5, 60 to 6.9)
        )
    }

    private fun iobSample(
        id: String,
        timestamp: Long,
        source: String,
        value: Double
    ): TelemetrySampleEntity = therapySample(
        id = id,
        timestamp = timestamp,
        source = source,
        key = "iob_units",
        value = value,
        unit = "U"
    )

    private fun componentSnapshot(
        netIobUnits: Double,
        timestamp: Long = 10_000L,
        insulinActivity: Double = 0.0
    ): InsulinRuntimeSnapshot = InsulinRuntimeSnapshot(
        timestamp = timestamp,
        source = InsulinRuntimeSource.AAPS_COMPONENTS,
        netIobUnits = netIobUnits,
        bolusIobUnits = netIobUnits.coerceAtLeast(0.0),
        basalIobUnits = netIobUnits.coerceAtMost(0.0),
        insulinActivity = insulinActivity,
        effectivePositiveIobUnits = netIobUnits.coerceAtLeast(0.0),
        confidence = 1.0,
        fallbackReason = null,
        evidenceTimestamp = timestamp,
        therapyCoverage = 1.0
    )

    private fun insulinCycleContext(
        snapshot: InsulinRuntimeSnapshot,
        modeledIobUnits: Double? = null,
        therapyModelAvailable: Boolean = modeledIobUnits != null
    ): AutomationRepository.InsulinCycleContext =
        AutomationRepository.buildInsulinCycleContextStatic(
            cycleTimestamp = snapshot.timestamp,
            causalReferenceTimestamp = snapshot.timestamp,
            insulinSnapshot = snapshot,
            modeledActiveInsulinUnits = modeledIobUnits,
            therapyModelAvailable = therapyModelAvailable,
            freshnessMs = 15 * 60_000L
        )

    private fun telemetryRow(
        id: String,
        timestamp: Long,
        source: String,
        key: String,
        value: Double?
    ): TelemetrySampleEntity = TelemetrySampleEntity(
        id = id,
        timestamp = timestamp,
        source = source,
        key = key,
        valueDouble = value,
        valueText = null,
        unit = "mmol/L/U",
        quality = "OK"
    )

    private fun therapySample(
        id: String,
        timestamp: Long,
        source: String,
        key: String,
        value: Double,
        unit: String
    ): TelemetrySampleEntity = TelemetrySampleEntity(
        id = id,
        timestamp = timestamp,
        source = source,
        key = key,
        valueDouble = value,
        valueText = null,
        unit = unit,
        quality = "OK"
    )
}
