package io.aaps.copilot.domain.predict

typealias IsfRuntimeSource = SensitivityResolvedSource

data class AapsIsfSample(
    val valueMmolPerUnit: Double?,
    val timestamp: Long?
)

data class CopilotIsfCandidate(
    val valueMmolPerUnit: Double?,
    val usable: Boolean,
    val fallbackReason: String
)

data class IsfRuntimeSourceDecision(
    val requested: IsfRuntimeSourcePreference,
    val resolved: IsfRuntimeSource,
    val valueMmolPerUnit: Double?,
    val fallbackReason: String?
)

object IsfRuntimeSourceResolver {

    fun resolve(
        preference: IsfRuntimeSourcePreference,
        aaps: AapsIsfSample,
        copilot: CopilotIsfCandidate,
        nowTs: Long,
        aapsFreshnessMs: Long
    ): IsfRuntimeSourceDecision {
        val decision = MetricRuntimeSourceResolver.resolve(
            preference = preference,
            aaps = AapsMetricSample(
                value = aaps.valueMmolPerUnit,
                timestamp = aaps.timestamp
            ),
            evidence = EvidenceMetricCandidate(
                value = copilot.valueMmolPerUnit,
                usable = copilot.usable,
                fallbackReason = copilot.fallbackReason
            ),
            bounds = MetricRuntimeBounds(min = 0.8, max = 18.0),
            nowTs = nowTs,
            aapsFreshnessMs = aapsFreshnessMs
        )
        return IsfRuntimeSourceDecision(
            requested = preference,
            resolved = when (decision.resolved) {
                MetricRuntimeResolvedSource.AAPS -> SensitivityResolvedSource.AAPS
                MetricRuntimeResolvedSource.EVIDENCE_BLEND -> SensitivityResolvedSource.EVIDENCE_BLEND
                MetricRuntimeResolvedSource.COPILOT_NATIVE -> SensitivityResolvedSource.COPILOT_NATIVE
            },
            valueMmolPerUnit = decision.overrideValue,
            fallbackReason = decision.fallbackReason
        )
    }
}
