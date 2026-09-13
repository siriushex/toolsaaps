package io.aaps.copilot.domain.predict

/**
 * Compatibility adapter for the pre-snapshot prediction-engine override path.
 * Native Copilot values remain inside the engine, so native resolution carries
 * no external override value.
 */
enum class MetricRuntimeResolvedSource {
    AAPS,
    EVIDENCE_BLEND,
    COPILOT_NATIVE
}

data class MetricRuntimeBounds(
    val min: Double,
    val max: Double
)

data class AapsMetricSample(
    val value: Double?,
    val timestamp: Long?
)

data class EvidenceMetricCandidate(
    val value: Double?,
    val usable: Boolean,
    val fallbackReason: String
)

data class MetricRuntimeSourceDecision(
    val requested: IsfRuntimeSourcePreference,
    val resolved: MetricRuntimeResolvedSource,
    val overrideValue: Double?,
    val fallbackReason: String?
)

object MetricRuntimeSourceResolver {

    fun resolve(
        preference: IsfRuntimeSourcePreference,
        aaps: AapsMetricSample,
        evidence: EvidenceMetricCandidate,
        bounds: MetricRuntimeBounds,
        nowTs: Long,
        aapsFreshnessMs: Long
    ): MetricRuntimeSourceDecision {
        val freshAaps = aaps.value
            ?.takeIf { isValid(it, bounds) }
            ?.takeIf {
                val timestamp = aaps.timestamp
                timestamp != null && timestamp in 0..nowTs &&
                    nowTs - timestamp <= aapsFreshnessMs.coerceAtLeast(0L)
            }
        val usableEvidence = evidence.value
            ?.takeIf { evidence.usable && isValid(it, bounds) }

        return when (preference) {
            SensitivitySourcePreference.EVIDENCE -> usableEvidence?.let { value ->
                MetricRuntimeSourceDecision(
                    requested = preference,
                    resolved = MetricRuntimeResolvedSource.EVIDENCE_BLEND,
                    overrideValue = value,
                    fallbackReason = null
                )
            } ?: native(
                preference = preference,
                reason = evidence.fallbackReason.ifBlank { "evidence_unavailable" }
            )

            SensitivitySourcePreference.AAPS -> freshAaps?.let { value ->
                MetricRuntimeSourceDecision(
                    requested = preference,
                    resolved = MetricRuntimeResolvedSource.AAPS,
                    overrideValue = value,
                    fallbackReason = null
                )
            } ?: usableEvidence?.let { value ->
                MetricRuntimeSourceDecision(
                    requested = preference,
                    resolved = MetricRuntimeResolvedSource.EVIDENCE_BLEND,
                    overrideValue = value,
                    fallbackReason = "aaps_unavailable"
                )
            } ?: native(
                preference = preference,
                reason = "aaps_unavailable;${evidence.fallbackReason.ifBlank { "evidence_unavailable" }}"
            )

            SensitivitySourcePreference.COPILOT -> MetricRuntimeSourceDecision(
                requested = preference,
                resolved = MetricRuntimeResolvedSource.COPILOT_NATIVE,
                overrideValue = null,
                fallbackReason = null
            )
        }
    }

    private fun native(
        preference: IsfRuntimeSourcePreference,
        reason: String
    ) = MetricRuntimeSourceDecision(
        requested = preference,
        resolved = MetricRuntimeResolvedSource.COPILOT_NATIVE,
        overrideValue = null,
        fallbackReason = reason
    )

    private fun isValid(value: Double, bounds: MetricRuntimeBounds): Boolean =
        value.isFinite() &&
            bounds.min.isFinite() &&
            bounds.max.isFinite() &&
            bounds.min <= bounds.max &&
            value in bounds.min..bounds.max
}
