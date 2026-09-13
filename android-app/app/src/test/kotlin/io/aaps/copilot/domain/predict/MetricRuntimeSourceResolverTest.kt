package io.aaps.copilot.domain.predict

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class MetricRuntimeSourceResolverTest {

    @Test
    fun evidenceUsesUsableEvidence() {
        val decision = MetricRuntimeSourceResolver.resolve(
            preference = IsfRuntimeSourcePreference.EVIDENCE,
            aaps = AapsMetricSample(value = 3.0, timestamp = 1_000L),
            evidence = EvidenceMetricCandidate(value = 2.6, usable = true, fallbackReason = "active_confident"),
            bounds = MetricRuntimeBounds(min = 0.8, max = 18.0),
            nowTs = 2_000L,
            aapsFreshnessMs = 5_000L
        )

        assertThat(decision.resolved).isEqualTo(MetricRuntimeResolvedSource.EVIDENCE_BLEND)
        assertThat(decision.overrideValue).isEqualTo(2.6)
        assertThat(decision.fallbackReason).isNull()
    }

    @Test
    fun evidenceFallsBackToCopilotNativeWhenEvidenceGateIsClosed() {
        val decision = MetricRuntimeSourceResolver.resolve(
            preference = IsfRuntimeSourcePreference.EVIDENCE,
            aaps = AapsMetricSample(value = 3.0, timestamp = 1_000L),
            evidence = EvidenceMetricCandidate(value = 2.6, usable = false, fallbackReason = "gate_closed"),
            bounds = MetricRuntimeBounds(min = 0.8, max = 18.0),
            nowTs = 2_000L,
            aapsFreshnessMs = 5_000L
        )

        assertThat(decision.resolved).isEqualTo(MetricRuntimeResolvedSource.COPILOT_NATIVE)
        assertThat(decision.overrideValue).isNull()
        assertThat(decision.fallbackReason).isEqualTo("gate_closed")
    }

    @Test
    fun requestedCopilotKeepsNativeEstimateWithoutExternalOverride() {
        val decision = MetricRuntimeSourceResolver.resolve(
            preference = IsfRuntimeSourcePreference.COPILOT,
            aaps = AapsMetricSample(value = 3.0, timestamp = 1_000L),
            evidence = EvidenceMetricCandidate(value = 2.6, usable = true, fallbackReason = "active_confident"),
            bounds = MetricRuntimeBounds(min = 0.8, max = 18.0),
            nowTs = 2_000L,
            aapsFreshnessMs = 5_000L
        )

        assertThat(decision.resolved).isEqualTo(MetricRuntimeResolvedSource.COPILOT_NATIVE)
        assertThat(decision.overrideValue).isNull()
    }

    @Test
    fun staleOrInvalidAapsNeverBecomesAnOverride() {
        val decision = MetricRuntimeSourceResolver.resolve(
            preference = IsfRuntimeSourcePreference.AAPS,
            aaps = AapsMetricSample(value = 0.5, timestamp = 1_000L),
            evidence = EvidenceMetricCandidate(value = null, usable = false, fallbackReason = "no_snapshot"),
            bounds = MetricRuntimeBounds(min = 0.8, max = 18.0),
            nowTs = 10_000L,
            aapsFreshnessMs = 5_000L
        )

        assertThat(decision.resolved).isEqualTo(MetricRuntimeResolvedSource.COPILOT_NATIVE)
        assertThat(decision.overrideValue).isNull()
        assertThat(decision.fallbackReason).contains("aaps_unavailable")
    }
}
