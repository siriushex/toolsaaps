package io.aaps.copilot.domain.predict

import kotlin.math.abs
import kotlin.math.max

enum class InsulinRuntimeSource {
    AAPS_COMPONENTS,
    LEGACY_AAPS,
    EXTERNAL_ESTIMATE,
    LOCAL_ESTIMATE
}

data class TimedInsulinValue(
    val value: Double,
    val timestamp: Long
)

data class QualifiedLocalInsulinEstimate(
    val value: Double,
    val timestamp: Long,
    val evidenceTimestamp: Long,
    val therapyCoverage: Double,
    val confidence: Double
)

data class InsulinComponentTelemetry(
    val netIob: TimedInsulinValue?,
    val bolusIob: TimedInsulinValue?,
    val basalIob: TimedInsulinValue?,
    val insulinActivity: TimedInsulinValue?
)

data class InsulinRuntimeSnapshot(
    val timestamp: Long,
    val source: InsulinRuntimeSource,
    val netIobUnits: Double,
    val bolusIobUnits: Double?,
    val basalIobUnits: Double?,
    val insulinActivity: Double?,
    val effectivePositiveIobUnits: Double,
    val confidence: Double,
    val fallbackReason: String?,
    val evidenceTimestamp: Long? = null,
    val therapyCoverage: Double = 0.0
)

data class InsulinRuntimeResolution(
    val snapshot: InsulinRuntimeSnapshot?,
    val rejectionReason: String?
)

object InsulinRuntimeSnapshotResolver {
    const val MAX_AGE_MS = 10L * 60L * 1_000L

    private const val COMPONENT_CONFIDENCE = 1.0
    private const val LEGACY_CONFIDENCE = 0.65
    private const val EXTERNAL_CONFIDENCE = 0.4
    private const val MIN_IOB_UNITS = -30.0
    private const val MAX_IOB_UNITS = 30.0
    private const val MIN_ACTIVITY = -5.0
    private const val MAX_ACTIVITY = 5.0
    private const val COMPONENT_SUM_EPSILON = 0.05

    fun resolve(
        nowTimestamp: Long,
        components: InsulinComponentTelemetry? = null,
        legacyIob: TimedInsulinValue? = null,
        externalEstimate: TimedInsulinValue? = null,
        localEstimate: QualifiedLocalInsulinEstimate? = null
    ): InsulinRuntimeResolution {
        require(nowTimestamp > 0L) { "nowTimestamp must be positive" }

        val componentReason = componentReason(components, nowTimestamp)
        if (componentReason == null) {
            val complete = requireNotNull(components)
            val net = requireNotNull(complete.netIob)
            val bolus = requireNotNull(complete.bolusIob)
            val basal = requireNotNull(complete.basalIob)
            val activity = requireNotNull(complete.insulinActivity)
            return InsulinRuntimeResolution(
                snapshot = InsulinRuntimeSnapshot(
                    timestamp = net.timestamp,
                    source = InsulinRuntimeSource.AAPS_COMPONENTS,
                    netIobUnits = net.value,
                    bolusIobUnits = bolus.value,
                    basalIobUnits = basal.value,
                    insulinActivity = activity.value,
                    effectivePositiveIobUnits = max(0.0, bolus.value) + max(0.0, basal.value),
                    confidence = COMPONENT_CONFIDENCE,
                    fallbackReason = null,
                    evidenceTimestamp = net.timestamp,
                    therapyCoverage = 1.0
                ),
                rejectionReason = null
            )
        }

        fallbackSnapshot(
            candidate = legacyIob,
            nowTimestamp = nowTimestamp,
            source = InsulinRuntimeSource.LEGACY_AAPS,
            confidence = LEGACY_CONFIDENCE,
            label = "legacy_aaps",
            fallbackReason = componentReason
        )?.let { return InsulinRuntimeResolution(it, componentReason) }

        fallbackSnapshot(
            candidate = externalEstimate,
            nowTimestamp = nowTimestamp,
            source = InsulinRuntimeSource.EXTERNAL_ESTIMATE,
            confidence = EXTERNAL_CONFIDENCE,
            label = "external_estimate",
            fallbackReason = listOf(componentReason, "legacy_aaps_absent").joinToString(";")
        )?.let { return InsulinRuntimeResolution(it, componentReason) }

        localSnapshot(
            candidate = localEstimate,
            nowTimestamp = nowTimestamp,
            fallbackReason = listOf(componentReason, "legacy_aaps_absent", "external_estimate_absent")
                .joinToString(";")
        )?.let { return InsulinRuntimeResolution(it, componentReason) }

        val reasons = buildList {
            add(componentReason)
            fallbackReason(legacyIob, nowTimestamp, "legacy_aaps")?.let(::add)
            fallbackReason(externalEstimate, nowTimestamp, "external_estimate")?.let(::add)
            localFallbackReason(localEstimate, nowTimestamp)?.let(::add)
        }.distinct()
        return InsulinRuntimeResolution(
            snapshot = null,
            rejectionReason = reasons.joinToString(";")
        )
    }

    fun resolveTelemetry(
        nowTimestamp: Long,
        telemetry: Map<String, Double?>,
        localEstimate: QualifiedLocalInsulinEstimate? = null
    ): InsulinRuntimeResolution {
        val relayTimestamp = telemetry["iob_relay_timestamp_ms"]
            ?.takeIf { it.isFinite() }
            ?.toLong()
        val sampleTimestamp = relayTimestamp ?: telemetry["iob_sample_ts"]
            ?.takeIf { it.isFinite() }
            ?.toLong()
        fun timed(key: String): TimedInsulinValue? {
            val timestamp = relayTimestamp ?: return null
            val value = telemetry[key]?.takeIf { it.isFinite() } ?: return null
            return TimedInsulinValue(value, timestamp)
        }

        val components = InsulinComponentTelemetry(
            netIob = timed("iob_net_units"),
            bolusIob = timed("iob_bolus_units"),
            basalIob = timed("iob_basal_units"),
            insulinActivity = timed("insulin_activity")
        )
        val fallback = telemetry["iob_net_units"]
            ?.takeIf { it.isFinite() }
            ?: telemetry["iob_units"]?.takeIf { it.isFinite() }
        val timedFallback = if (fallback != null && sampleTimestamp != null) {
            TimedInsulinValue(fallback, sampleTimestamp)
        } else {
            null
        }
        val explicitSource = sourceFromCode(telemetry["iob_runtime_source_code"])
        return resolve(
            nowTimestamp = nowTimestamp,
            components = components,
            legacyIob = timedFallback.takeIf { explicitSource == InsulinRuntimeSource.LEGACY_AAPS },
            externalEstimate = timedFallback.takeIf {
                explicitSource == InsulinRuntimeSource.EXTERNAL_ESTIMATE || explicitSource == null
            },
            localEstimate = if (explicitSource == InsulinRuntimeSource.LOCAL_ESTIMATE && timedFallback != null) {
                QualifiedLocalInsulinEstimate(
                    value = timedFallback.value,
                    timestamp = timedFallback.timestamp,
                    evidenceTimestamp = telemetry["iob_runtime_evidence_timestamp_ms"]
                        ?.takeIf { it.isFinite() }
                        ?.toLong()
                        ?: 0L,
                    therapyCoverage = telemetry["iob_runtime_therapy_coverage"]
                        ?.takeIf { it.isFinite() }
                        ?: 0.0,
                    confidence = telemetry["iob_runtime_confidence"]
                        ?.takeIf { it.isFinite() }
                        ?: 0.0
                )
            } else {
                localEstimate
            }
        )
    }

    internal fun sourceCode(source: InsulinRuntimeSource): Double = when (source) {
        InsulinRuntimeSource.AAPS_COMPONENTS -> 1.0
        InsulinRuntimeSource.LEGACY_AAPS -> 2.0
        InsulinRuntimeSource.EXTERNAL_ESTIMATE -> 3.0
        InsulinRuntimeSource.LOCAL_ESTIMATE -> 4.0
    }

    private fun sourceFromCode(code: Double?): InsulinRuntimeSource? = when (code) {
        1.0 -> InsulinRuntimeSource.AAPS_COMPONENTS
        2.0 -> InsulinRuntimeSource.LEGACY_AAPS
        3.0 -> InsulinRuntimeSource.EXTERNAL_ESTIMATE
        4.0 -> InsulinRuntimeSource.LOCAL_ESTIMATE
        else -> null
    }

    private fun componentReason(
        components: InsulinComponentTelemetry?,
        nowTimestamp: Long
    ): String? {
        components ?: return "components_absent"
        val values = listOf(
            components.netIob,
            components.bolusIob,
            components.basalIob,
            components.insulinActivity
        )
        if (values.any { it == null }) return "component_incomplete"
        val complete = values.filterNotNull()
        if (complete.map { it.timestamp }.distinct().size != 1) return "component_timestamp_mismatch"
        timestampReason(complete.first().timestamp, nowTimestamp)?.let { return "component_$it" }
        if (complete.any { !it.value.isFinite() }) return "component_non_finite"

        val net = requireNotNull(components.netIob).value
        val bolus = requireNotNull(components.bolusIob).value
        val basal = requireNotNull(components.basalIob).value
        val activity = requireNotNull(components.insulinActivity).value
        if (
            net !in MIN_IOB_UNITS..MAX_IOB_UNITS ||
            bolus !in 0.0..MAX_IOB_UNITS ||
            basal !in MIN_IOB_UNITS..MAX_IOB_UNITS ||
            activity !in MIN_ACTIVITY..MAX_ACTIVITY
        ) {
            return "component_out_of_bounds"
        }
        if (abs(net - (bolus + basal)) > COMPONENT_SUM_EPSILON) return "component_net_mismatch"
        return null
    }

    private fun fallbackSnapshot(
        candidate: TimedInsulinValue?,
        nowTimestamp: Long,
        source: InsulinRuntimeSource,
        confidence: Double,
        label: String,
        fallbackReason: String
    ): InsulinRuntimeSnapshot? {
        if (fallbackReason(candidate, nowTimestamp, label) != null) return null
        val valid = requireNotNull(candidate)
        return InsulinRuntimeSnapshot(
            timestamp = valid.timestamp,
            source = source,
            netIobUnits = valid.value,
            bolusIobUnits = null,
            basalIobUnits = null,
            insulinActivity = null,
            effectivePositiveIobUnits = max(0.0, valid.value),
            confidence = confidence,
            fallbackReason = fallbackReason,
            evidenceTimestamp = valid.timestamp,
            therapyCoverage = when (source) {
                InsulinRuntimeSource.LEGACY_AAPS -> 1.0
                InsulinRuntimeSource.EXTERNAL_ESTIMATE -> 0.5
                else -> 0.0
            }
        )
    }

    private fun localSnapshot(
        candidate: QualifiedLocalInsulinEstimate?,
        nowTimestamp: Long,
        fallbackReason: String
    ): InsulinRuntimeSnapshot? {
        if (localFallbackReason(candidate, nowTimestamp) != null) return null
        val valid = requireNotNull(candidate)
        return InsulinRuntimeSnapshot(
            timestamp = valid.timestamp,
            source = InsulinRuntimeSource.LOCAL_ESTIMATE,
            netIobUnits = valid.value,
            bolusIobUnits = null,
            basalIobUnits = null,
            insulinActivity = null,
            effectivePositiveIobUnits = max(0.0, valid.value),
            confidence = valid.confidence.coerceIn(0.0, 1.0),
            fallbackReason = fallbackReason,
            evidenceTimestamp = valid.evidenceTimestamp,
            therapyCoverage = valid.therapyCoverage.coerceIn(0.0, 1.0)
        )
    }

    private fun localFallbackReason(
        candidate: QualifiedLocalInsulinEstimate?,
        nowTimestamp: Long
    ): String? {
        candidate ?: return "local_estimate_absent"
        fallbackReason(
            candidate = TimedInsulinValue(candidate.value, candidate.timestamp),
            nowTimestamp = nowTimestamp,
            label = "local_estimate"
        )?.let { return it }
        if (candidate.evidenceTimestamp <= 0L || candidate.evidenceTimestamp > nowTimestamp) {
            return "local_estimate_evidence_timestamp"
        }
        if (!candidate.therapyCoverage.isFinite() || candidate.therapyCoverage !in 0.0..1.0) {
            return "local_estimate_therapy_coverage"
        }
        if (!candidate.confidence.isFinite() || candidate.confidence !in 0.0..1.0) {
            return "local_estimate_confidence"
        }
        return null
    }

    private fun fallbackReason(
        candidate: TimedInsulinValue?,
        nowTimestamp: Long,
        label: String
    ): String? {
        candidate ?: return "${label}_absent"
        if (!candidate.value.isFinite()) return "${label}_non_finite"
        if (candidate.value !in MIN_IOB_UNITS..MAX_IOB_UNITS) return "${label}_out_of_bounds"
        return timestampReason(candidate.timestamp, nowTimestamp)?.let { "${label}_$it" }
    }

    private fun timestampReason(timestamp: Long, nowTimestamp: Long): String? {
        if (timestamp <= 0L) return "timestamp_missing"
        if (timestamp > nowTimestamp) return "timestamp_future"
        if (nowTimestamp - timestamp > MAX_AGE_MS) return "timestamp_stale"
        return null
    }
}
