package io.aaps.copilot.data.repository

import io.aaps.copilot.data.local.entity.TelemetrySampleEntity
import io.aaps.copilot.domain.predict.InsulinRuntimeSnapshotResolver
import io.aaps.copilot.domain.predict.InsulinRuntimeSource
import kotlin.math.abs
import kotlin.math.max

internal object AtomicInsulinRuntimePacketContract {
    const val PUBLICATION_SOURCE = "copilot_runtime_cob_iob"

    val PACKET_KEYS: Set<String> = linkedSetOf(
        "iob_units",
        "iob_real_units",
        "iob_effective_units",
        "iob_effective_positive_units",
        "iob_net_units",
        "iob_bolus_units",
        "iob_basal_units",
        "insulin_activity",
        "iob_runtime_source",
        "iob_runtime_source_code",
        "iob_runtime_confidence",
        "iob_runtime_timestamp_ms",
        "iob_runtime_evidence_timestamp_ms",
        "iob_runtime_therapy_coverage",
        "iob_runtime_fallback_active",
        "iob_runtime_fallback_reason"
    )

    fun decodeNewest(
        samples: List<TelemetrySampleEntity>
    ): Map<String, TelemetrySampleEntity> {
        val publicationTimestamp = samples.asSequence()
            .filter { sample ->
                sample.source == PUBLICATION_SOURCE && sample.key == "iob_runtime_source"
            }
            .maxOfOrNull(TelemetrySampleEntity::timestamp)
            ?: return emptyMap()
        val packetRows = samples.asSequence()
            .filter { sample ->
                sample.source == PUBLICATION_SOURCE &&
                    sample.timestamp == publicationTimestamp &&
                    sample.key in PACKET_KEYS
            }
            .toList()
        if (!isValid(packetRows, publicationTimestamp)) return emptyMap()
        return packetRows.associateBy(TelemetrySampleEntity::key)
    }

    fun mergeWithLatest(
        latestByKey: Map<String, TelemetrySampleEntity>,
        samples: List<TelemetrySampleEntity>
    ): Map<String, TelemetrySampleEntity> {
        val atomicPacket = decodeNewest(samples)
        return latestByKey.toMutableMap().apply {
            PACKET_KEYS.forEach(::remove)
            putAll(atomicPacket)
        }
    }

    fun effectivePositiveIob(
        packet: Map<String, TelemetrySampleEntity>
    ): Double? {
        if ("iob_effective_positive_units" in packet) {
            return packet["iob_effective_positive_units"].numericValue()
        }
        return sequenceOf("iob_effective_units", "iob_units")
            .mapNotNull { key -> packet[key].numericValue() }
            .firstOrNull()
    }

    fun isSampleFresh(
        publicationTimestamp: Long,
        sampleTimestamp: Long
    ): Boolean {
        val ageMs = try {
            Math.subtractExact(publicationTimestamp, sampleTimestamp)
        } catch (_: ArithmeticException) {
            return false
        }
        return ageMs in 0L..InsulinRuntimeSnapshotResolver.MAX_AGE_MS
    }

    private fun isValid(
        rows: List<TelemetrySampleEntity>,
        publicationTimestamp: Long
    ): Boolean {
        if (publicationTimestamp <= 0L || rows.size != PACKET_KEYS.size) return false
        if (rows.map(TelemetrySampleEntity::key).toSet() != PACKET_KEYS) return false
        if (rows.any { row ->
                row.source != PUBLICATION_SOURCE ||
                    row.timestamp != publicationTimestamp ||
                    row.quality != "OK"
            }
        ) {
            return false
        }
        val packet = rows.associateBy(TelemetrySampleEntity::key)
        val sourceName = packet["iob_runtime_source"].strictText() ?: return false
        if (sourceName == "UNAVAILABLE") return isValidUnavailable(packet)
        val source = runCatching { InsulinRuntimeSource.valueOf(sourceName) }.getOrNull() ?: return false
        val sourceCode = packet.strictFiniteNumeric("iob_runtime_source_code") ?: return false
        if (sourceCode != InsulinRuntimeSnapshotResolver.sourceCode(source)) return false

        val effective = packet.strictFiniteNumeric("iob_effective_positive_units") ?: return false
        val effectiveAlias = packet.strictFiniteNumeric("iob_effective_units") ?: return false
        val primaryAlias = packet.strictFiniteNumeric("iob_units") ?: return false
        val net = packet.strictFiniteNumeric("iob_net_units") ?: return false
        val netAlias = packet.strictFiniteNumeric("iob_real_units") ?: return false
        val confidence = packet.strictFiniteNumeric("iob_runtime_confidence") ?: return false
        val coverage = packet.strictFiniteNumeric("iob_runtime_therapy_coverage") ?: return false
        val fallbackActive = packet.strictFiniteNumeric("iob_runtime_fallback_active") ?: return false
        val sampleTimestamp = packet.strictPositiveLong("iob_runtime_timestamp_ms") ?: return false
        val evidenceTimestamp = packet.strictPositiveLong("iob_runtime_evidence_timestamp_ms") ?: return false
        if (
            effective !in 0.0..60.0 ||
            net !in -30.0..30.0 ||
            confidence !in 0.0..1.0 ||
            coverage !in 0.0..1.0 ||
            fallbackActive !in setOf(0.0, 1.0) ||
            !isSampleFresh(publicationTimestamp, sampleTimestamp) ||
            evidenceTimestamp > sampleTimestamp ||
            !approximatelyEqual(effective, effectiveAlias) ||
            !approximatelyEqual(effective, primaryAlias) ||
            !approximatelyEqual(net, netAlias)
        ) {
            return false
        }

        val fallbackReasonRow = packet.getValue("iob_runtime_fallback_reason")
        if (fallbackReasonRow.valueDouble != null) return false
        return when (source) {
            InsulinRuntimeSource.AAPS_COMPONENTS -> {
                val bolus = packet.strictFiniteNumeric("iob_bolus_units") ?: return false
                val basal = packet.strictFiniteNumeric("iob_basal_units") ?: return false
                val activity = packet.strictFiniteNumeric("insulin_activity") ?: return false
                bolus in 0.0..30.0 &&
                    basal in -30.0..30.0 &&
                    activity in -5.0..5.0 &&
                    abs(net - (bolus + basal)) <= COMPONENT_EPSILON &&
                    abs(effective - (max(0.0, bolus) + max(0.0, basal))) <= COMPONENT_EPSILON &&
                    confidence == 1.0 &&
                    coverage == 1.0 &&
                    fallbackActive == 0.0 &&
                    fallbackReasonRow.valueText == null
            }
            InsulinRuntimeSource.LEGACY_AAPS,
            InsulinRuntimeSource.EXTERNAL_ESTIMATE,
            InsulinRuntimeSource.LOCAL_ESTIMATE -> {
                val expectedConfidenceAndCoverage = when (source) {
                    InsulinRuntimeSource.LEGACY_AAPS -> 0.65 to 1.0
                    InsulinRuntimeSource.EXTERNAL_ESTIMATE -> 0.4 to 0.5
                    InsulinRuntimeSource.LOCAL_ESTIMATE -> null
                    InsulinRuntimeSource.AAPS_COMPONENTS -> error("handled above")
                }
                val fixedMetadataMatches = expectedConfidenceAndCoverage?.let { expected ->
                    confidence == expected.first && coverage == expected.second
                } ?: true
                listOf("iob_bolus_units", "iob_basal_units", "insulin_activity")
                    .all { key -> packet.getValue(key).isExplicitNullValue() } &&
                    effective <= 30.0 &&
                    approximatelyEqual(effective, max(0.0, net)) &&
                    fallbackActive == 1.0 &&
                    fixedMetadataMatches &&
                    fallbackReasonRow.valueText?.takeIf { it.isNotBlank() && it.length <= 96 } != null
            }
        }
    }

    private fun isValidUnavailable(
        packet: Map<String, TelemetrySampleEntity>
    ): Boolean = PACKET_KEYS
        .filterNot { it == "iob_runtime_source" }
        .all { key -> packet.getValue(key).isExplicitNullValue() }

    private fun Map<String, TelemetrySampleEntity>.strictFiniteNumeric(key: String): Double? {
        val row = getValue(key)
        if (row.valueText != null) return null
        return row.valueDouble?.takeIf(Double::isFinite)
    }

    private fun Map<String, TelemetrySampleEntity>.strictPositiveLong(key: String): Long? {
        val value = strictFiniteNumeric(key) ?: return null
        if (value <= 0.0 || value > Long.MAX_VALUE.toDouble()) return null
        val converted = value.toLong()
        return converted.takeIf { it.toDouble() == value }
    }

    private fun TelemetrySampleEntity?.strictText(): String? = this
        ?.takeIf { it.valueDouble == null }
        ?.valueText
        ?.takeIf { it.isNotBlank() && it == it.trim() }

    private fun TelemetrySampleEntity.isExplicitNullValue(): Boolean =
        valueDouble == null && valueText == null

    private fun TelemetrySampleEntity?.numericValue(): Double? =
        this?.valueDouble ?: this?.valueText?.replace(",", ".")?.toDoubleOrNull()

    private fun approximatelyEqual(first: Double, second: Double): Boolean =
        abs(first - second) <= ALIAS_EPSILON

    private const val ALIAS_EPSILON = 1e-9
    private const val COMPONENT_EPSILON = 0.05
}
