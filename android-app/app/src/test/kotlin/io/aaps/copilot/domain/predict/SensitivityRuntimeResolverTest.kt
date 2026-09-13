package io.aaps.copilot.domain.predict

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

class SensitivityRuntimeResolverTest {

    @Test
    fun legacyAutoDecodesAsEvidence() {
        assertThat(SensitivitySourcePreference.fromPersisted("AUTO"))
            .isEqualTo(SensitivitySourcePreference.EVIDENCE)
        assertThat(SensitivitySourcePreference.fromPersisted(" evidence "))
            .isEqualTo(SensitivitySourcePreference.EVIDENCE)
        assertThat(SensitivitySourcePreference.fromUserInput("AUTO")).isNull()
    }

    @Test
    fun aapsUsesFreshRawValueWithoutBlend() {
        val decision = SensitivityRuntimeResolver.resolveIsf(
            requested = SensitivitySourcePreference.AAPS,
            candidates = candidates(
                aaps = candidate(value = 3.2, confidence = 0.73),
                evidence = candidate(value = 7.0, confidence = 1.0),
                copilot = candidate(value = 4.0, confidence = 0.81)
            ),
            nowTs = NOW_TS,
            freshnessMs = FRESHNESS_MS
        )

        assertThat(decision.resolved).isEqualTo(SensitivityResolvedSource.AAPS)
        assertThat(decision.rawAaps).isEqualTo(3.2)
        assertThat(decision.blended).isNull()
        assertThat(decision.effective).isEqualTo(3.2)
        assertThat(decision.confidence).isEqualTo(0.73)
        assertThat(decision.fallbackReason).isNull()
    }

    @Test
    fun aapsFallsBackToEvidenceThenCopilot() {
        val staleAaps = candidate(value = 12.0, timestamp = NOW_TS - FRESHNESS_MS - 1L)
        val evidenceFallback = SensitivityRuntimeResolver.resolveCr(
            requested = SensitivitySourcePreference.AAPS,
            candidates = candidates(
                aaps = staleAaps,
                evidence = candidate(value = 14.0, confidence = 0.5),
                copilot = candidate(value = 10.0)
            ),
            nowTs = NOW_TS,
            freshnessMs = FRESHNESS_MS
        )
        val nativeFallback = SensitivityRuntimeResolver.resolveCr(
            requested = SensitivitySourcePreference.AAPS,
            candidates = candidates(
                aaps = staleAaps,
                evidence = candidate(value = 14.0, confidence = 0.5, qualityPassed = false),
                copilot = candidate(value = 10.0)
            ),
            nowTs = NOW_TS,
            freshnessMs = FRESHNESS_MS
        )

        assertThat(evidenceFallback.resolved).isEqualTo(SensitivityResolvedSource.EVIDENCE_BLEND)
        assertThat(evidenceFallback.blended).isEqualTo(12.0)
        assertThat(evidenceFallback.effective).isEqualTo(12.0)
        assertThat(evidenceFallback.fallbackReason).contains("aaps_timestamp_stale")
        assertThat(nativeFallback.resolved).isEqualTo(SensitivityResolvedSource.COPILOT_NATIVE)
        assertThat(nativeFallback.effective).isEqualTo(10.0)
        assertThat(nativeFallback.fallbackReason).contains("evidence_quality_gate_failed")
    }

    @Test
    fun evidenceBlendsWithNativeByBoundedConfidence() {
        val decision = SensitivityRuntimeResolver.resolveIsf(
            requested = SensitivitySourcePreference.EVIDENCE,
            candidates = candidates(
                aaps = candidate(value = 2.0),
                evidence = candidate(value = 8.0, confidence = 1.4),
                copilot = candidate(value = 4.0)
            ),
            nowTs = NOW_TS,
            freshnessMs = FRESHNESS_MS
        )

        assertThat(decision.resolved).isEqualTo(SensitivityResolvedSource.EVIDENCE_BLEND)
        assertThat(decision.blended).isEqualTo(8.0)
        assertThat(decision.effective).isEqualTo(8.0)
        assertThat(decision.confidence).isEqualTo(1.0)
    }

    @Test
    fun evidenceCrFallsBackToFreshQualifiedAapsWhenGateFails() {
        val decision = SensitivityRuntimeResolver.resolveCr(
            requested = SensitivitySourcePreference.EVIDENCE,
            candidates = candidates(
                aaps = candidate(value = 10.0, confidence = 1.0),
                evidence = candidate(value = 8.59339, confidence = 0.435, qualityPassed = false),
                copilot = candidate(value = 22.5416, confidence = 0.435)
            ),
            nowTs = NOW_TS,
            freshnessMs = FRESHNESS_MS
        )

        assertThat(decision.resolved).isEqualTo(SensitivityResolvedSource.AAPS)
        assertThat(decision.blended).isNull()
        assertThat(decision.rawAaps).isEqualTo(10.0)
        assertThat(decision.rawEvidence).isEqualTo(8.59339)
        assertThat(decision.rawCopilot).isEqualTo(22.5416)
        assertThat(decision.effective).isEqualTo(10.0)
        assertThat(decision.confidence).isEqualTo(1.0)
        assertThat(decision.fallbackReason)
            .isEqualTo("evidence_quality_gate_failed;aaps_fallback_selected")
    }

    @Test
    fun evidenceCrRejectsInvalidAapsBeforeFallingBackToNative() {
        val invalidAaps = listOf(
            candidate(value = null, unavailableReason = "missing") to "aaps_missing",
            candidate(value = 10.0, timestamp = NOW_TS - FRESHNESS_MS - 1L) to "aaps_timestamp_stale",
            candidate(value = 10.0, timestamp = NOW_TS + 1L) to "aaps_timestamp_future",
            candidate(value = 1.99) to "aaps_out_of_bounds",
            candidate(value = 60.01) to "aaps_out_of_bounds",
            candidate(value = 10.0, qualityPassed = false) to "aaps_quality_gate_failed"
        )

        invalidAaps.forEach { (aaps, expectedAapsReason) ->
            val decision = SensitivityRuntimeResolver.resolveCr(
                requested = SensitivitySourcePreference.EVIDENCE,
                candidates = candidates(
                    aaps = aaps,
                    evidence = candidate(value = 8.59339, confidence = 0.435, qualityPassed = false),
                    copilot = candidate(value = 22.5416, confidence = 0.435)
                ),
                nowTs = NOW_TS,
                freshnessMs = FRESHNESS_MS
            )

            assertThat(decision.resolved).isEqualTo(SensitivityResolvedSource.COPILOT_NATIVE)
            assertThat(decision.effective).isEqualTo(22.5416)
            assertThat(decision.confidence).isEqualTo(0.435)
            assertThat(decision.fallbackReason)
                .isEqualTo("evidence_quality_gate_failed;$expectedAapsReason")
        }
    }

    @Test
    fun evidenceIsfKeepsExistingNativeFallbackWhenEvidenceGateFails() {
        val decision = SensitivityRuntimeResolver.resolveIsf(
            requested = SensitivitySourcePreference.EVIDENCE,
            candidates = candidates(
                aaps = candidate(value = 2.2),
                evidence = candidate(value = 3.4, qualityPassed = false),
                copilot = candidate(value = 4.1, confidence = 0.64)
            ),
            nowTs = NOW_TS,
            freshnessMs = FRESHNESS_MS
        )

        assertThat(decision.resolved).isEqualTo(SensitivityResolvedSource.COPILOT_NATIVE)
        assertThat(decision.effective).isEqualTo(4.1)
        assertThat(decision.confidence).isEqualTo(0.64)
        assertThat(decision.fallbackReason).isEqualTo("evidence_quality_gate_failed")
    }

    @Test
    fun evidenceWithNonPositiveConfidenceFallsBackToCopilotNative() {
        listOf(0.0, -0.1).forEach { confidence ->
            val decision = SensitivityRuntimeResolver.resolveIsf(
                requested = SensitivitySourcePreference.EVIDENCE,
                candidates = candidates(
                    evidence = candidate(value = 6.0, confidence = confidence),
                    copilot = candidate(value = 4.0)
                ),
                nowTs = NOW_TS,
                freshnessMs = FRESHNESS_MS
            )

            assertThat(decision.resolved).isEqualTo(SensitivityResolvedSource.COPILOT_NATIVE)
            assertThat(decision.effective).isEqualTo(4.0)
            assertThat(decision.blended).isNull()
            assertThat(decision.fallbackReason).isEqualTo("evidence_confidence_gate_failed")
        }
    }

    @Test
    fun evidenceRejectsCoverageAboveOne() {
        val decision = resolveEvidenceCr(coverage = 1.01)

        assertThat(decision.resolved).isEqualTo(SensitivityResolvedSource.COPILOT_NATIVE)
        assertThat(decision.fallbackReason)
            .isEqualTo("evidence_coverage_gate_failed;aaps_missing")
    }

    @Test
    fun evidenceRejectsNonFiniteCoverage() {
        val decision = resolveEvidenceCr(coverage = Double.NaN)

        assertThat(decision.resolved).isEqualTo(SensitivityResolvedSource.COPILOT_NATIVE)
        assertThat(decision.fallbackReason)
            .isEqualTo("evidence_coverage_gate_failed;aaps_missing")
    }

    @Test
    fun evidenceRejectsNonPositiveCoverage() {
        listOf(0.0, -0.01).forEach { coverage ->
            val decision = resolveEvidenceCr(coverage = coverage)

            assertThat(decision.resolved).isEqualTo(SensitivityResolvedSource.COPILOT_NATIVE)
            assertThat(decision.fallbackReason)
                .isEqualTo("evidence_coverage_gate_failed;aaps_missing")
        }
    }

    @Test
    fun copilotAlwaysUsesNativeCandidate() {
        val decision = SensitivityRuntimeResolver.resolveCr(
            requested = SensitivitySourcePreference.COPILOT,
            candidates = candidates(
                aaps = candidate(value = 7.0),
                evidence = candidate(value = 8.0, confidence = 1.0),
                copilot = candidate(value = 60.0, confidence = 0.92)
            ),
            nowTs = NOW_TS,
            freshnessMs = FRESHNESS_MS
        )

        assertThat(decision.resolved).isEqualTo(SensitivityResolvedSource.COPILOT_NATIVE)
        assertThat(decision.rawCopilot).isEqualTo(60.0)
        assertThat(decision.effective).isEqualTo(60.0)
        assertThat(decision.blended).isNull()
        assertThat(decision.fallbackReason).isNull()
    }

    @Test
    fun staleAapsCannotAppearAsResolvedAaps() {
        val invalidAapsCandidates = listOf(
            candidate(value = 3.0, timestamp = NOW_TS - FRESHNESS_MS - 1L),
            candidate(value = 3.0, timestamp = NOW_TS + 1L),
            candidate(value = 30.0),
            candidate(value = Double.NaN)
        )

        invalidAapsCandidates.forEach { aaps ->
            val decision = SensitivityRuntimeResolver.resolveIsf(
                requested = SensitivitySourcePreference.AAPS,
                candidates = candidates(
                    aaps = aaps,
                    evidence = candidate(value = null, unavailableReason = "missing"),
                    copilot = candidate(value = 4.0)
                ),
                nowTs = NOW_TS,
                freshnessMs = FRESHNESS_MS
            )

            assertThat(decision.resolved).isEqualTo(SensitivityResolvedSource.COPILOT_NATIVE)
            assertThat(decision.effective).isEqualTo(4.0)
            assertThat(decision.fallbackReason).contains("aaps_")
        }
    }

    @Test
    fun isfAndCrCanResolveDifferentSourcesInOneSnapshot() {
        val snapshot = SensitivityRuntimeResolver.resolve(
            settingsRevision = 17L,
            forecastCycleId = "cycle-17",
            timestamp = NOW_TS,
            isfPreference = SensitivitySourcePreference.AAPS,
            crPreference = SensitivitySourcePreference.EVIDENCE,
            isfCandidates = candidates(
                aaps = candidate(value = 2.8),
                evidence = candidate(value = 4.0),
                copilot = candidate(value = 3.5)
            ),
            crCandidates = candidates(
                aaps = candidate(value = 8.0),
                evidence = candidate(value = 14.0, confidence = 0.25),
                copilot = candidate(value = 10.0)
            ),
            freshnessMs = FRESHNESS_MS
        )

        assertThat(snapshot.settingsRevision).isEqualTo(17L)
        assertThat(snapshot.forecastCycleId).isEqualTo("cycle-17")
        assertThat(snapshot.timestamp).isEqualTo(NOW_TS)
        assertThat(snapshot.isf.resolved).isEqualTo(SensitivityResolvedSource.AAPS)
        assertThat(snapshot.isf.effective).isEqualTo(2.8)
        assertThat(snapshot.cr.resolved).isEqualTo(SensitivityResolvedSource.EVIDENCE_BLEND)
        assertThat(snapshot.cr.effective).isEqualTo(11.0)
    }

    @Test
    fun futureEvidenceAndZeroSupportFallBackToValidatedNative() {
        val futureEvidence = SensitivityRuntimeResolver.resolveIsf(
            requested = SensitivitySourcePreference.EVIDENCE,
            candidates = candidates(
                evidence = candidate(value = 6.0, timestamp = NOW_TS + 1L),
                copilot = candidate(value = 0.2)
            ),
            nowTs = NOW_TS,
            freshnessMs = FRESHNESS_MS
        )
        val zeroSupportEvidence = SensitivityRuntimeResolver.resolveCr(
            requested = SensitivitySourcePreference.EVIDENCE,
            candidates = candidates(
                evidence = candidate(value = 12.0, sampleCount = 0, coverage = 0.0),
                copilot = candidate(value = 60.0)
            ),
            nowTs = NOW_TS,
            freshnessMs = FRESHNESS_MS
        )

        assertThat(futureEvidence.resolved).isEqualTo(SensitivityResolvedSource.COPILOT_NATIVE)
        assertThat(futureEvidence.effective).isEqualTo(0.8)
        assertThat(futureEvidence.fallbackReason).isEqualTo("evidence_timestamp_future")
        assertThat(zeroSupportEvidence.resolved).isEqualTo(SensitivityResolvedSource.COPILOT_NATIVE)
        assertThat(zeroSupportEvidence.effective).isEqualTo(60.0)
        assertThat(zeroSupportEvidence.fallbackReason)
            .isEqualTo("evidence_sample_count_empty;aaps_missing")
    }

    @Test
    fun effectiveBoundaryValuesAndConfidenceStayCanonical() {
        val isfLow = resolveNativeIsf(value = 0.2, confidence = -0.4)
        val isfHigh = resolveNativeIsf(value = 18.0)
        val crLow = resolveNativeCr(value = 2.0)
        val crHigh = resolveNativeCr(value = 60.0)

        assertThat(isfLow.effective).isEqualTo(0.8)
        assertThat(isfLow.confidence).isEqualTo(0.0)
        assertThat(isfHigh.effective).isEqualTo(18.0)
        assertThat(crLow.effective).isEqualTo(2.0)
        assertThat(crHigh.effective).isEqualTo(60.0)
    }

    @Test
    fun copilotNativeCandidateOutsideIngressBoundsIsRejected() {
        listOf(-2.0, 0.19, 18.01, Double.NaN, Double.POSITIVE_INFINITY).forEach { value ->
            assertThrows(IllegalArgumentException::class.java) {
                resolveNativeIsf(value = value)
            }
        }
        listOf(-5.0, 1.99, 60.01, Double.NaN, Double.NEGATIVE_INFINITY).forEach { value ->
            assertThrows(IllegalArgumentException::class.java) {
                resolveNativeCr(value = value)
            }
        }
    }

    private fun resolveNativeIsf(
        value: Double,
        confidence: Double = 1.0
    ) = SensitivityRuntimeResolver.resolveIsf(
        requested = SensitivitySourcePreference.COPILOT,
        candidates = candidates(copilot = candidate(value = value, confidence = confidence)),
        nowTs = NOW_TS,
        freshnessMs = FRESHNESS_MS
    )

    private fun resolveNativeCr(value: Double) = SensitivityRuntimeResolver.resolveCr(
        requested = SensitivitySourcePreference.COPILOT,
        candidates = candidates(copilot = candidate(value = value)),
        nowTs = NOW_TS,
        freshnessMs = FRESHNESS_MS
    )

    private fun resolveEvidenceCr(coverage: Double) = SensitivityRuntimeResolver.resolveCr(
        requested = SensitivitySourcePreference.EVIDENCE,
        candidates = candidates(
            evidence = candidate(value = 12.0, coverage = coverage),
            copilot = candidate(value = 9.0)
        ),
        nowTs = NOW_TS,
        freshnessMs = FRESHNESS_MS
    )

    private fun candidates(
        aaps: SensitivityCandidate = candidate(value = null, unavailableReason = "missing"),
        evidence: SensitivityCandidate = candidate(value = null, unavailableReason = "missing"),
        copilot: SensitivityCandidate
    ) = SensitivityCandidates(aaps = aaps, evidence = evidence, copilot = copilot)

    private fun candidate(
        value: Double?,
        timestamp: Long? = NOW_TS,
        confidence: Double = 1.0,
        qualityPassed: Boolean = true,
        sampleCount: Int = 8,
        coverage: Double = 0.9,
        unavailableReason: String? = null
    ) = SensitivityCandidate(
        value = value,
        timestamp = timestamp,
        confidence = confidence,
        qualityPassed = qualityPassed,
        sampleCount = sampleCount,
        coverage = coverage,
        unavailableReason = unavailableReason
    )

    private companion object {
        const val NOW_TS = 1_000_000L
        const val FRESHNESS_MS = 10_000L
    }
}
