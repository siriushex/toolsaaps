package io.aaps.copilot.data.repository

import com.google.common.truth.Truth.assertThat
import io.aaps.copilot.data.local.entity.TelemetrySampleEntity
import io.aaps.copilot.domain.predict.InsulinRuntimeSnapshotResolver
import org.junit.Test

class AtomicInsulinRuntimePacketContractTest {

    @Test
    fun completeComponentPacketUsesEffectivePositiveIobAsPrimary() {
        val packet = AtomicInsulinRuntimePacketContract.decodeNewest(
            componentPacket(timestamp = 1_700_000_000_000L)
        )

        assertThat(AtomicInsulinRuntimePacketContract.effectivePositiveIob(packet)).isEqualTo(1.2)
        assertThat(packet["iob_net_units"]?.valueDouble).isEqualTo(1.0)
        assertThat(packet["iob_runtime_source_code"]?.valueDouble).isEqualTo(1.0)
    }

    @Test
    fun explicitMissingEffectivePositiveIobDoesNotFallThroughToLegacySignedValue() {
        val timestamp = 1_700_000_000_000L
        val packet = mapOf(
            "iob_effective_positive_units" to sample(timestamp, "iob_effective_positive_units"),
            "iob_units" to sample(timestamp, "iob_units", value = 2.9)
        )

        assertThat(AtomicInsulinRuntimePacketContract.effectivePositiveIob(packet)).isNull()
    }

    @Test
    fun newestValidFallbackPacketClearsOlderAapsComponents() {
        val oldTs = 1_700_000_000_000L
        val currentTs = oldTs + 5_000L
        val latestByKey = componentPacket(oldTs).associateBy { it.key }
        val currentPacket = fallbackPacket(currentTs, "LOCAL_ESTIMATE", net = 0.6)

        val merged = AtomicInsulinRuntimePacketContract.mergeWithLatest(latestByKey, currentPacket)

        assertThat(merged["iob_effective_positive_units"]?.valueDouble).isEqualTo(0.6)
        assertThat(merged["iob_bolus_units"]?.valueDouble).isNull()
        assertThat(merged["iob_basal_units"]?.valueDouble).isNull()
        assertThat(merged["insulin_activity"]?.valueDouble).isNull()
        assertThat(merged["iob_runtime_source_code"]?.valueDouble).isEqualTo(4.0)
    }

    @Test
    fun markerAbsentStripsConflictingLooseIobValuesAndDetails() {
        val startTs = 1_700_000_000_000L
        val loose = listOf(
            sample(startTs, "iob_effective_positive_units", value = 0.7),
            sample(startTs + 1_000L, "iob_net_units", value = -0.5),
            sample(startTs + 2_000L, "iob_bolus_units", value = 0.4),
            sample(startTs + 3_000L, "iob_basal_units", value = -0.9),
            sample(startTs + 4_000L, "insulin_activity", value = 0.012),
            sample(startTs + 5_000L, "iob_runtime_timestamp_ms", value = startTs.toDouble()),
            sample(startTs + 6_000L, "iob_runtime_confidence", value = 0.93),
            sample(startTs + 7_000L, "iob_runtime_evidence_timestamp_ms", value = (startTs - 60_000L).toDouble()),
            sample(startTs + 8_000L, "iob_runtime_therapy_coverage", value = 0.87),
            sample(startTs + 9_000L, "iob_runtime_fallback_reason", text = "loose_fallback"),
            sample(startTs + 10_000L, "cob_effective_grams", value = 18.0)
        )

        val merged = AtomicInsulinRuntimePacketContract.mergeWithLatest(
            latestByKey = loose.associateBy(TelemetrySampleEntity::key),
            samples = loose
        )

        assertThat(AtomicInsulinRuntimePacketContract.effectivePositiveIob(merged)).isNull()
        AtomicInsulinRuntimePacketContract.PACKET_KEYS.forEach { key ->
            assertThat(merged[key]).isNull()
        }
        assertThat(merged["cob_effective_grams"]?.valueDouble).isEqualTo(18.0)
    }

    @Test
    fun everyFallbackSourceRequiresExplicitNullComponentPlaceholders() {
        listOf("LEGACY_AAPS", "EXTERNAL_ESTIMATE", "LOCAL_ESTIMATE").forEachIndexed { index, sourceName ->
            val timestamp = 1_700_000_000_000L + index * 10_000L
            val packet = AtomicInsulinRuntimePacketContract.decodeNewest(
                fallbackPacket(timestamp, sourceName, net = 0.6 + index)
            )

            assertThat(packet["iob_runtime_source"]?.valueText).isEqualTo(sourceName)
            assertThat(packet["iob_units"]?.valueDouble).isEqualTo(0.6 + index)
            assertThat(packet["iob_net_units"]?.valueDouble).isEqualTo(0.6 + index)
            assertThat(packet["iob_bolus_units"]?.valueDouble).isNull()
            assertThat(packet["iob_basal_units"]?.valueDouble).isNull()
            assertThat(packet["insulin_activity"]?.valueDouble).isNull()
            assertThat(packet.values.map { it.timestamp }.distinct()).containsExactly(timestamp)
        }
    }

    @Test
    fun validUnavailablePacketRemainsTruthfullyUnavailable() {
        val packet = AtomicInsulinRuntimePacketContract.decodeNewest(
            unavailablePacket(1_700_000_000_000L)
        )

        assertThat(packet["iob_runtime_source"]?.valueText).isEqualTo("UNAVAILABLE")
        assertThat(AtomicInsulinRuntimePacketContract.effectivePositiveIob(packet)).isNull()
        assertThat(packet["iob_runtime_timestamp_ms"]?.valueDouble).isNull()
        assertThat(packet["iob_runtime_source_code"]?.valueDouble).isNull()
    }

    @Test
    fun malformedSourceNameOrCodeMismatchRejectsTheNewestPacket() {
        val timestamp = 1_700_000_000_000L
        val malformedPackets = listOf(
            componentPacket(timestamp).replace("iob_runtime_source") { it.copy(valueText = "UNKNOWN") },
            componentPacket(timestamp).replace("iob_runtime_source_code") { it.copy(valueDouble = 3.0) },
            componentPacket(timestamp).replace("iob_runtime_source") {
                it.copy(source = "aaps_broadcast")
            }
        )

        malformedPackets.forEach { samples ->
            assertThat(AtomicInsulinRuntimePacketContract.decodeNewest(samples)).isEmpty()
        }
    }

    @Test
    fun malformedRuntimeOrEvidenceTimestampRejectsTheNewestPacket() {
        val timestamp = 1_700_000_000_000L
        val sampleTs = timestamp - 1_000L
        val malformedPackets = listOf(
            componentPacket(timestamp).replace("iob_runtime_timestamp_ms") { it.copy(valueDouble = 0.0) },
            componentPacket(timestamp).replace("iob_runtime_timestamp_ms") {
                it.copy(valueDouble = timestamp.plus(1L).toDouble())
            },
            componentPacket(timestamp).replace("iob_runtime_timestamp_ms") { it.copy(valueDouble = 1.5) },
            componentPacket(timestamp).replace("iob_runtime_evidence_timestamp_ms") {
                it.copy(valueDouble = sampleTs.plus(1L).toDouble())
            }
        )

        malformedPackets.forEach { samples ->
            assertThat(AtomicInsulinRuntimePacketContract.decodeNewest(samples)).isEmpty()
        }
    }

    @Test
    fun sampleAtInclusiveMaximumAgeIsAccepted() {
        val publicationTimestamp = 1_700_000_000_000L

        val packet = AtomicInsulinRuntimePacketContract.decodeNewest(
            componentPacket(
                timestamp = publicationTimestamp,
                sampleTimestamp = publicationTimestamp - InsulinRuntimeSnapshotResolver.MAX_AGE_MS
            )
        )

        assertThat(packet).isNotEmpty()
    }

    @Test
    fun sampleBeyondMaximumAgeIsRejected() {
        val publicationTimestamp = 1_700_000_000_000L

        val packet = AtomicInsulinRuntimePacketContract.decodeNewest(
            componentPacket(
                timestamp = publicationTimestamp,
                sampleTimestamp = publicationTimestamp - InsulinRuntimeSnapshotResolver.MAX_AGE_MS - 1L
            )
        )

        assertThat(packet).isEmpty()
    }

    @Test
    fun futureSampleIsRejected() {
        val publicationTimestamp = 1_700_000_000_000L

        val packet = AtomicInsulinRuntimePacketContract.decodeNewest(
            componentPacket(
                timestamp = publicationTimestamp,
                sampleTimestamp = publicationTimestamp + 1L
            )
        )

        assertThat(packet).isEmpty()
    }

    @Test
    fun sampleAgeSubtractionOverflowFailsClosed() {
        assertThat(
            AtomicInsulinRuntimePacketContract.isSampleFresh(
                publicationTimestamp = Long.MAX_VALUE,
                sampleTimestamp = Long.MIN_VALUE
            )
        ).isFalse()
    }

    @Test
    fun malformedRowQualityRejectsTheNewestPacket() {
        val malformed = componentPacket(1_700_000_000_000L)
            .replace("iob_runtime_confidence") { it.copy(quality = "UNKNOWN") }

        assertThat(AtomicInsulinRuntimePacketContract.decodeNewest(malformed)).isEmpty()
    }

    @Test
    fun nonFiniteNumericValuesRejectTheNewestPacket() {
        val timestamp = 1_700_000_000_000L
        val malformedPackets = listOf(
            componentPacket(timestamp).replace("iob_effective_positive_units") {
                it.copy(valueDouble = Double.NaN)
            },
            componentPacket(timestamp).replace("iob_runtime_confidence") {
                it.copy(valueDouble = Double.POSITIVE_INFINITY)
            }
        )

        malformedPackets.forEach { samples ->
            assertThat(AtomicInsulinRuntimePacketContract.decodeNewest(samples)).isEmpty()
        }
    }

    @Test
    fun outOfBoundsValuesRejectTheNewestPacketWithoutCoercingIobToZero() {
        val timestamp = 1_700_000_000_000L
        val malformedPackets = listOf(
            fallbackPacket(timestamp, "LEGACY_AAPS", net = -0.5)
                .replace("iob_effective_positive_units") { it.copy(valueDouble = -0.5) },
            componentPacket(timestamp).replace("iob_bolus_units") { it.copy(valueDouble = 31.0) },
            componentPacket(timestamp).replace("insulin_activity") { it.copy(valueDouble = 5.1) },
            componentPacket(timestamp).replace("iob_runtime_confidence") { it.copy(valueDouble = 1.1) },
            componentPacket(timestamp).replace("iob_runtime_therapy_coverage") { it.copy(valueDouble = -0.1) }
        )

        malformedPackets.forEach { samples ->
            val packet = AtomicInsulinRuntimePacketContract.decodeNewest(samples)
            assertThat(packet).isEmpty()
            assertThat(AtomicInsulinRuntimePacketContract.effectivePositiveIob(packet)).isNull()
        }
    }

    @Test
    fun incompleteRequiredRowsRejectTheNewestPacket() {
        val timestamp = 1_700_000_000_000L
        listOf(
            "iob_effective_positive_units",
            "iob_runtime_timestamp_ms",
            "iob_runtime_evidence_timestamp_ms",
            "iob_runtime_source_code",
            "iob_runtime_fallback_reason"
        ).forEach { missingKey ->
            val incomplete = componentPacket(timestamp).filterNot { it.key == missingKey }
            assertThat(AtomicInsulinRuntimePacketContract.decodeNewest(incomplete)).isEmpty()
        }
    }

    @Test
    fun aliasOrComponentSumInconsistencyRejectsTheNewestPacket() {
        val timestamp = 1_700_000_000_000L
        val malformedPackets = listOf(
            componentPacket(timestamp).replace("iob_units") { it.copy(valueDouble = 2.0) },
            componentPacket(timestamp).replace("iob_real_units") { it.copy(valueDouble = 2.0) },
            componentPacket(timestamp).replace("iob_net_units") { it.copy(valueDouble = 0.5) },
            fallbackPacket(timestamp, "EXTERNAL_ESTIMATE", net = -0.5)
                .replace("iob_effective_units") { it.copy(valueDouble = 0.5) }
        )

        malformedPackets.forEach { samples ->
            assertThat(AtomicInsulinRuntimePacketContract.decodeNewest(samples)).isEmpty()
        }
    }

    @Test
    fun sourceSpecificComponentCompletenessRejectsMalformedPackets() {
        val timestamp = 1_700_000_000_000L
        val malformedPackets = listOf(
            componentPacket(timestamp).replace("iob_bolus_units") { it.copy(valueDouble = null) },
            fallbackPacket(timestamp, "LOCAL_ESTIMATE", net = 0.5)
                .replace("iob_basal_units") { it.copy(valueDouble = -0.1) },
            fallbackPacket(timestamp, "LEGACY_AAPS", net = 0.5)
                .replace("iob_runtime_fallback_reason") { it.copy(valueText = null) }
        )

        malformedPackets.forEach { samples ->
            assertThat(AtomicInsulinRuntimePacketContract.decodeNewest(samples)).isEmpty()
        }
    }

    @Test
    fun malformedNewestMarkerClearsIobInsteadOfFallingBackToOlderValidPacket() {
        val oldTs = 1_700_000_000_000L
        val newestTs = oldTs + 5_000L
        val olderValid = componentPacket(oldTs)
        val newestMalformed = fallbackPacket(newestTs, "EXTERNAL_ESTIMATE", net = 0.8)
            .replace("iob_runtime_source_code") { it.copy(valueDouble = 2.0) }

        val packet = AtomicInsulinRuntimePacketContract.decodeNewest(olderValid + newestMalformed)
        val merged = AtomicInsulinRuntimePacketContract.mergeWithLatest(
            latestByKey = (olderValid + newestMalformed).associateBy { it.key },
            samples = olderValid + newestMalformed
        )

        assertThat(packet).isEmpty()
        assertThat(AtomicInsulinRuntimePacketContract.effectivePositiveIob(merged)).isNull()
        AtomicInsulinRuntimePacketContract.PACKET_KEYS.forEach { key ->
            assertThat(merged[key]).isNull()
        }
    }

    private fun componentPacket(
        timestamp: Long,
        sampleTimestamp: Long = timestamp - 1_000L
    ): List<TelemetrySampleEntity> {
        return listOf(
            sample(timestamp, "iob_effective_units", value = 1.2),
            sample(timestamp, "iob_effective_positive_units", value = 1.2),
            sample(timestamp, "iob_real_units", value = 1.0),
            sample(timestamp, "iob_units", value = 1.2),
            sample(timestamp, "iob_net_units", value = 1.0),
            sample(timestamp, "iob_bolus_units", value = 1.2),
            sample(timestamp, "iob_basal_units", value = -0.2),
            sample(timestamp, "insulin_activity", value = 0.02),
            sample(timestamp, "iob_runtime_timestamp_ms", value = sampleTimestamp.toDouble()),
            sample(timestamp, "iob_runtime_evidence_timestamp_ms", value = sampleTimestamp.toDouble()),
            sample(timestamp, "iob_runtime_therapy_coverage", value = 1.0),
            sample(timestamp, "iob_runtime_confidence", value = 1.0),
            sample(timestamp, "iob_runtime_source_code", value = 1.0),
            sample(timestamp, "iob_runtime_fallback_active", value = 0.0),
            sample(timestamp, "iob_runtime_source", text = "AAPS_COMPONENTS"),
            sample(timestamp, "iob_runtime_fallback_reason")
        )
    }

    private fun fallbackPacket(
        timestamp: Long,
        sourceName: String,
        net: Double
    ): List<TelemetrySampleEntity> {
        val sampleTs = timestamp - 1_000L
        val effective = net.coerceAtLeast(0.0)
        val (sourceCode, confidence, coverage) = when (sourceName) {
            "LEGACY_AAPS" -> Triple(2.0, 0.65, 1.0)
            "EXTERNAL_ESTIMATE" -> Triple(3.0, 0.4, 0.5)
            "LOCAL_ESTIMATE" -> Triple(4.0, 0.7, 0.8)
            else -> error("unsupported fixture source: $sourceName")
        }
        return listOf(
            sample(timestamp, "iob_effective_units", value = effective),
            sample(timestamp, "iob_effective_positive_units", value = effective),
            sample(timestamp, "iob_real_units", value = net),
            sample(timestamp, "iob_units", value = effective),
            sample(timestamp, "iob_net_units", value = net),
            sample(timestamp, "iob_bolus_units"),
            sample(timestamp, "iob_basal_units"),
            sample(timestamp, "insulin_activity"),
            sample(timestamp, "iob_runtime_timestamp_ms", value = sampleTs.toDouble()),
            sample(timestamp, "iob_runtime_evidence_timestamp_ms", value = (sampleTs - 100L).toDouble()),
            sample(timestamp, "iob_runtime_therapy_coverage", value = coverage),
            sample(timestamp, "iob_runtime_confidence", value = confidence),
            sample(timestamp, "iob_runtime_source_code", value = sourceCode),
            sample(timestamp, "iob_runtime_fallback_active", value = 1.0),
            sample(timestamp, "iob_runtime_source", text = sourceName),
            sample(timestamp, "iob_runtime_fallback_reason", text = "component_incomplete")
        )
    }

    private fun unavailablePacket(timestamp: Long): List<TelemetrySampleEntity> = listOf(
        sample(timestamp, "iob_effective_units"),
        sample(timestamp, "iob_effective_positive_units"),
        sample(timestamp, "iob_real_units"),
        sample(timestamp, "iob_units"),
        sample(timestamp, "iob_net_units"),
        sample(timestamp, "iob_bolus_units"),
        sample(timestamp, "iob_basal_units"),
        sample(timestamp, "insulin_activity"),
        sample(timestamp, "iob_runtime_timestamp_ms"),
        sample(timestamp, "iob_runtime_evidence_timestamp_ms"),
        sample(timestamp, "iob_runtime_therapy_coverage"),
        sample(timestamp, "iob_runtime_confidence"),
        sample(timestamp, "iob_runtime_source_code"),
        sample(timestamp, "iob_runtime_fallback_active"),
        sample(timestamp, "iob_runtime_source", text = "UNAVAILABLE"),
        sample(timestamp, "iob_runtime_fallback_reason")
    )

    private fun List<TelemetrySampleEntity>.replace(
        key: String,
        transform: (TelemetrySampleEntity) -> TelemetrySampleEntity
    ): List<TelemetrySampleEntity> = map { row -> if (row.key == key) transform(row) else row }

    private fun sample(
        timestamp: Long,
        key: String,
        value: Double? = null,
        text: String? = null
    ) = TelemetrySampleEntity(
        id = "runtime-$key-$timestamp",
        timestamp = timestamp,
        source = "copilot_runtime_cob_iob",
        key = key,
        valueDouble = value,
        valueText = text,
        unit = null,
        quality = "OK"
    )

}
