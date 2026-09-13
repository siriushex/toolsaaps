package io.aaps.copilot.domain.predict

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class IsfRuntimeSourceResolverTest {

    @Test
    fun evidenceUsesUsableCompatibilityCandidate() {
        val decision = resolve(
            preference = IsfRuntimeSourcePreference.EVIDENCE,
            aapsValue = 3.0,
            evidenceValue = 2.6,
            evidenceUsable = true
        )

        assertThat(decision.resolved).isEqualTo(IsfRuntimeSource.EVIDENCE_BLEND)
        assertThat(decision.valueMmolPerUnit).isEqualTo(2.6)
        assertThat(decision.fallbackReason).isNull()
    }

    @Test
    fun requestedAapsUsesFreshAapsWithoutEvidence() {
        val decision = resolve(
            preference = IsfRuntimeSourcePreference.AAPS,
            aapsValue = 3.0,
            evidenceValue = 2.6,
            evidenceUsable = true
        )

        assertThat(decision.resolved).isEqualTo(IsfRuntimeSource.AAPS)
        assertThat(decision.valueMmolPerUnit).isEqualTo(3.0)
        assertThat(decision.fallbackReason).isNull()
    }

    @Test
    fun requestedAapsFallsBackToEvidenceThenNative() {
        val evidenceFallback = resolve(
            preference = IsfRuntimeSourcePreference.AAPS,
            aapsValue = 0.5,
            evidenceValue = 2.6,
            evidenceUsable = true
        )
        val nativeFallback = resolve(
            preference = IsfRuntimeSourcePreference.AAPS,
            aapsValue = 0.5,
            evidenceValue = null,
            evidenceUsable = false
        )

        assertThat(evidenceFallback.resolved).isEqualTo(IsfRuntimeSource.EVIDENCE_BLEND)
        assertThat(evidenceFallback.valueMmolPerUnit).isEqualTo(2.6)
        assertThat(evidenceFallback.fallbackReason).isEqualTo("aaps_unavailable")
        assertThat(nativeFallback.resolved).isEqualTo(IsfRuntimeSource.COPILOT_NATIVE)
        assertThat(nativeFallback.valueMmolPerUnit).isNull()
    }

    @Test
    fun copilotPreferenceNeverUsesExternalCompatibilityCandidates() {
        val decision = resolve(
            preference = IsfRuntimeSourcePreference.COPILOT,
            aapsValue = 3.0,
            evidenceValue = 2.6,
            evidenceUsable = true
        )

        assertThat(decision.resolved).isEqualTo(IsfRuntimeSource.COPILOT_NATIVE)
        assertThat(decision.valueMmolPerUnit).isNull()
        assertThat(decision.fallbackReason).isNull()
    }

    @Test
    fun invalidStoredPreferenceDefaultsToEvidence() {
        assertThat(IsfRuntimeSourcePreference.fromRaw("unexpected"))
            .isEqualTo(IsfRuntimeSourcePreference.EVIDENCE)
    }

    private fun resolve(
        preference: IsfRuntimeSourcePreference,
        aapsValue: Double?,
        evidenceValue: Double?,
        evidenceUsable: Boolean
    ): IsfRuntimeSourceDecision = IsfRuntimeSourceResolver.resolve(
        preference = preference,
        aaps = AapsIsfSample(valueMmolPerUnit = aapsValue, timestamp = 1_000L),
        copilot = CopilotIsfCandidate(
            valueMmolPerUnit = evidenceValue,
            usable = evidenceUsable,
            fallbackReason = "gate_closed"
        ),
        nowTs = 2_000L,
        aapsFreshnessMs = 5_000L
    )
}
