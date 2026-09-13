package io.aaps.copilot.domain.predict

internal const val SENSITIVITY_ISF_RAW_MIN = 0.2
internal const val SENSITIVITY_ISF_EFFECTIVE_MIN = 0.8
internal const val SENSITIVITY_ISF_MAX = 18.0
internal const val SENSITIVITY_CR_RAW_MIN = 2.0
internal const val SENSITIVITY_CR_EFFECTIVE_MIN = 2.0
internal const val SENSITIVITY_CR_MAX = 60.0

internal enum class SensitivityMetricKind { ISF, CR }

internal data class SensitivityMetricBounds(
    val diagnosticMin: Double,
    val effectiveMin: Double,
    val maximum: Double
) {
    fun diagnosticContains(value: Double): Boolean = value.isFinite() && value in diagnosticMin..maximum
    fun effectiveContains(value: Double): Boolean = value.isFinite() && value in effectiveMin..maximum
    fun nativeEffective(value: Double): Double = value.coerceIn(effectiveMin, maximum)
}

internal object SensitivityRuntimeContract {
    val isf = SensitivityMetricBounds(
        diagnosticMin = SENSITIVITY_ISF_RAW_MIN,
        effectiveMin = SENSITIVITY_ISF_EFFECTIVE_MIN,
        maximum = SENSITIVITY_ISF_MAX
    )
    val cr = SensitivityMetricBounds(
        diagnosticMin = SENSITIVITY_CR_RAW_MIN,
        effectiveMin = SENSITIVITY_CR_EFFECTIVE_MIN,
        maximum = SENSITIVITY_CR_MAX
    )

    fun bounds(kind: SensitivityMetricKind): SensitivityMetricBounds = when (kind) {
        SensitivityMetricKind.ISF -> isf
        SensitivityMetricKind.CR -> cr
    }
}

internal object SensitivityFallbackContract {
    private const val EVIDENCE_TO_AAPS_SUFFIX = ";aaps_fallback_selected"

    fun aapsToEvidence(aapsReason: String): String =
        "aaps_${requireReasonSegment(aapsReason)}"

    fun aapsToCopilot(aapsReason: String, evidenceReason: String): String =
        "aaps_${requireReasonSegment(aapsReason)};evidence_${requireReasonSegment(evidenceReason)}"

    fun evidenceToCopilot(evidenceReason: String): String =
        "evidence_${requireReasonSegment(evidenceReason)}"

    fun evidenceToAaps(evidenceReason: String): String =
        "evidence_${requireReasonSegment(evidenceReason)}$EVIDENCE_TO_AAPS_SUFFIX"

    fun evidenceToCopilotViaAaps(evidenceReason: String, aapsReason: String): String =
        "evidence_${requireReasonSegment(evidenceReason)};aaps_${requireReasonSegment(aapsReason)}"

    fun isAapsToEvidence(value: String?): Boolean = value
        ?.takeIf { it.startsWith("aaps_") }
        ?.removePrefix("aaps_")
        ?.let(::isReasonSegment)
        ?: false

    fun isAapsToCopilot(value: String?): Boolean {
        if (value == null || !value.startsWith("aaps_")) return false
        val separator = ";evidence_"
        val splitAt = value.indexOf(separator)
        if (splitAt <= "aaps_".length || value.indexOf(';', splitAt + 1) >= 0) return false
        return isReasonSegment(value.substring("aaps_".length, splitAt)) &&
            isReasonSegment(value.substring(splitAt + separator.length))
    }

    fun isEvidenceToCopilot(value: String?): Boolean = value
        ?.takeIf { it.startsWith("evidence_") }
        ?.removePrefix("evidence_")
        ?.let(::isReasonSegment)
        ?: false

    fun isEvidenceToAaps(value: String?): Boolean = value
        ?.takeIf { it.startsWith("evidence_") && it.endsWith(EVIDENCE_TO_AAPS_SUFFIX) }
        ?.removePrefix("evidence_")
        ?.removeSuffix(EVIDENCE_TO_AAPS_SUFFIX)
        ?.let(::isReasonSegment)
        ?: false

    fun isEvidenceToCopilotViaAaps(value: String?): Boolean {
        if (value == null || !value.startsWith("evidence_")) return false
        val separator = ";aaps_"
        val splitAt = value.indexOf(separator)
        if (splitAt <= "evidence_".length || value.indexOf(';', splitAt + 1) >= 0) return false
        val aapsReason = value.substring(splitAt + separator.length)
        return aapsReason != "fallback_selected" &&
            isReasonSegment(value.substring("evidence_".length, splitAt)) &&
            isReasonSegment(aapsReason)
    }

    private fun requireReasonSegment(value: String): String = value.also {
        require(isReasonSegment(it)) { "sensitivity fallback reason must be one segment" }
    }

    private fun isReasonSegment(value: String): Boolean = value.isNotBlank() && ';' !in value
}

internal object SensitivityMetricDecisionValidator {
    fun isValid(kind: SensitivityMetricKind, decision: SensitivityMetricDecision): Boolean {
        val bounds = SensitivityRuntimeContract.bounds(kind)
        if (!bounds.effectiveContains(decision.effective)) return false
        if (!decision.confidence.isFinite() || decision.confidence !in 0.0..1.0) return false
        if (decision.rawAaps?.let(bounds::diagnosticContains) == false) return false
        if (decision.rawEvidence?.let(bounds::diagnosticContains) == false) return false
        if (!bounds.diagnosticContains(decision.rawCopilot)) return false
        if (decision.blended?.let(bounds::effectiveContains) == false) return false

        val nativeEffective = bounds.nativeEffective(decision.rawCopilot)
        return when (decision.resolved) {
            SensitivityResolvedSource.AAPS ->
                when (decision.requested) {
                    SensitivitySourcePreference.AAPS -> decision.fallbackReason == null
                    SensitivitySourcePreference.EVIDENCE ->
                        kind == SensitivityMetricKind.CR &&
                            decision.confidence > 0.0 &&
                            SensitivityFallbackContract.isEvidenceToAaps(decision.fallbackReason)
                    SensitivitySourcePreference.COPILOT -> false
                } &&
                    decision.rawAaps?.let(bounds::effectiveContains) == true &&
                    decision.blended == null &&
                    sameCanonicalValue(decision.effective, decision.rawAaps)

            SensitivityResolvedSource.EVIDENCE_BLEND -> {
                val evidence = decision.rawEvidence ?: return false
                val blended = decision.blended ?: return false
                if (decision.confidence <= 0.0) return false
                val expectedBlend = (
                    nativeEffective + decision.confidence * (evidence - nativeEffective)
                    ).coerceIn(bounds.effectiveMin, bounds.maximum)
                val fallbackValid = when (decision.requested) {
                    SensitivitySourcePreference.AAPS ->
                        SensitivityFallbackContract.isAapsToEvidence(decision.fallbackReason)
                    SensitivitySourcePreference.EVIDENCE -> decision.fallbackReason == null
                    SensitivitySourcePreference.COPILOT -> false
                }
                bounds.effectiveContains(evidence) &&
                    fallbackValid &&
                    sameCanonicalValue(blended, expectedBlend) &&
                    sameCanonicalValue(decision.effective, blended)
            }

            SensitivityResolvedSource.COPILOT_NATIVE -> {
                val fallbackValid = when (decision.requested) {
                    SensitivitySourcePreference.COPILOT -> decision.fallbackReason == null
                    SensitivitySourcePreference.EVIDENCE ->
                        SensitivityFallbackContract.isEvidenceToCopilot(decision.fallbackReason) ||
                            (
                                kind == SensitivityMetricKind.CR &&
                                    SensitivityFallbackContract.isEvidenceToCopilotViaAaps(
                                        decision.fallbackReason
                                    )
                                )
                    SensitivitySourcePreference.AAPS ->
                        SensitivityFallbackContract.isAapsToCopilot(decision.fallbackReason)
                }
                decision.blended == null &&
                    fallbackValid &&
                    sameCanonicalValue(decision.effective, nativeEffective)
            }
        }
    }

    private fun sameCanonicalValue(first: Double, second: Double): Boolean =
        canonicalBits(first) == canonicalBits(second)

    private fun canonicalBits(value: Double): Long =
        if (value == 0.0) 0.0.toRawBits() else value.toRawBits()
}

object SensitivityRuntimeResolver {

    private val isfBounds = SensitivityRuntimeContract.isf
    private val crBounds = SensitivityRuntimeContract.cr

    fun resolve(
        settingsRevision: Long,
        forecastCycleId: String,
        timestamp: Long,
        isfPreference: SensitivitySourcePreference,
        crPreference: SensitivitySourcePreference,
        isfCandidates: SensitivityCandidates,
        crCandidates: SensitivityCandidates,
        freshnessMs: Long
    ): SensitivityRuntimeSnapshot {
        require(settingsRevision >= 0L) { "settingsRevision must be non-negative" }
        require(forecastCycleId.isNotBlank()) { "forecastCycleId must not be blank" }
        require(timestamp > 0L) { "timestamp must be positive" }
        return SensitivityRuntimeSnapshot(
            settingsRevision = settingsRevision,
            forecastCycleId = forecastCycleId,
            timestamp = timestamp,
            isf = resolveMetric(
                requested = isfPreference,
                candidates = isfCandidates,
                bounds = isfBounds,
                kind = SensitivityMetricKind.ISF,
                nowTs = timestamp,
                freshnessMs = freshnessMs
            ),
            cr = resolveMetric(
                requested = crPreference,
                candidates = crCandidates,
                bounds = crBounds,
                kind = SensitivityMetricKind.CR,
                nowTs = timestamp,
                freshnessMs = freshnessMs
            )
        )
    }

    fun resolveIsf(
        requested: SensitivitySourcePreference,
        candidates: SensitivityCandidates,
        nowTs: Long,
        freshnessMs: Long
    ): SensitivityMetricDecision = resolveMetric(
        requested = requested,
        candidates = candidates,
        bounds = isfBounds,
        kind = SensitivityMetricKind.ISF,
        nowTs = nowTs,
        freshnessMs = freshnessMs
    )

    fun resolveCr(
        requested: SensitivitySourcePreference,
        candidates: SensitivityCandidates,
        nowTs: Long,
        freshnessMs: Long
    ): SensitivityMetricDecision = resolveMetric(
        requested = requested,
        candidates = candidates,
        bounds = crBounds,
        kind = SensitivityMetricKind.CR,
        nowTs = nowTs,
        freshnessMs = freshnessMs
    )

    private fun resolveMetric(
        requested: SensitivitySourcePreference,
        candidates: SensitivityCandidates,
        bounds: SensitivityMetricBounds,
        kind: SensitivityMetricKind,
        nowTs: Long,
        freshnessMs: Long
    ): SensitivityMetricDecision {
        require(nowTs > 0L) { "nowTs must be positive" }
        val boundedFreshnessMs = freshnessMs.coerceAtLeast(0L)
        val rawCopilot = requireNotNull(candidates.copilot.value) {
            "Copilot native candidate is required"
        }
        require(rawCopilot.isFinite()) { "Copilot native candidate must be finite" }
        require(bounds.diagnosticContains(rawCopilot)) {
            "Copilot native candidate must be within ingress bounds"
        }
        require(candidateTimestampReason(candidates.copilot, nowTs, boundedFreshnessMs) == null) {
            "Copilot native candidate must be fresh and causal"
        }

        val nativeEffective = bounds.nativeEffective(rawCopilot)
        val rawAaps = candidates.aaps.value?.takeIf(bounds::diagnosticContains)
        val rawEvidence = candidates.evidence.value?.takeIf(bounds::diagnosticContains)
        val aapsReason = externalReason(
            candidate = candidates.aaps,
            bounds = bounds,
            nowTs = nowTs,
            freshnessMs = boundedFreshnessMs
        )
        val evidenceReason = qualifiedExternalReason(
            candidate = candidates.evidence,
            bounds = bounds,
            nowTs = nowTs,
            freshnessMs = boundedFreshnessMs
        )
        val evidenceConfidence = normalizedConfidence(candidates.evidence.confidence)

        fun aaps(fallbackReason: String?): SensitivityMetricDecision = SensitivityMetricDecision(
            requested = requested,
            resolved = SensitivityResolvedSource.AAPS,
            rawAaps = rawAaps,
            rawEvidence = rawEvidence,
            rawCopilot = rawCopilot,
            blended = null,
            effective = requireNotNull(rawAaps),
            confidence = normalizedConfidence(candidates.aaps.confidence),
            fallbackReason = fallbackReason
        )

        fun native(fallbackReason: String?): SensitivityMetricDecision = SensitivityMetricDecision(
            requested = requested,
            resolved = SensitivityResolvedSource.COPILOT_NATIVE,
            rawAaps = rawAaps,
            rawEvidence = rawEvidence,
            rawCopilot = rawCopilot,
            blended = null,
            effective = nativeEffective,
            confidence = normalizedConfidence(candidates.copilot.confidence),
            fallbackReason = fallbackReason
        )

        fun evidence(fallbackReason: String?): SensitivityMetricDecision {
            val evidenceValue = requireNotNull(rawEvidence)
            val blended = (
                nativeEffective + evidenceConfidence * (evidenceValue - nativeEffective)
                ).coerceIn(bounds.effectiveMin, bounds.maximum)
            return SensitivityMetricDecision(
                requested = requested,
                resolved = SensitivityResolvedSource.EVIDENCE_BLEND,
                rawAaps = rawAaps,
                rawEvidence = rawEvidence,
                rawCopilot = rawCopilot,
                blended = blended,
                effective = blended,
                confidence = evidenceConfidence,
                fallbackReason = fallbackReason
            )
        }

        val decision = when (requested) {
            SensitivitySourcePreference.AAPS -> when {
                aapsReason == null -> aaps(fallbackReason = null)
                evidenceReason == null -> evidence(SensitivityFallbackContract.aapsToEvidence(aapsReason))
                else -> native(SensitivityFallbackContract.aapsToCopilot(aapsReason, evidenceReason))
            }

            SensitivitySourcePreference.EVIDENCE -> {
                if (evidenceReason == null) {
                    evidence(fallbackReason = null)
                } else if (kind == SensitivityMetricKind.CR) {
                    val crAapsReason = qualifiedExternalReason(
                        candidate = candidates.aaps,
                        bounds = bounds,
                        nowTs = nowTs,
                        freshnessMs = boundedFreshnessMs
                    )
                    if (crAapsReason == null) {
                        aaps(SensitivityFallbackContract.evidenceToAaps(evidenceReason))
                    } else {
                        native(
                            SensitivityFallbackContract.evidenceToCopilotViaAaps(
                                evidenceReason = evidenceReason,
                                aapsReason = crAapsReason
                            )
                        )
                    }
                } else {
                    native(SensitivityFallbackContract.evidenceToCopilot(evidenceReason))
                }
            }

            SensitivitySourcePreference.COPILOT -> native(fallbackReason = null)
        }
        check(SensitivityMetricDecisionValidator.isValid(kind, decision)) {
            "resolved sensitivity decision violates the persisted runtime contract"
        }
        return decision
    }

    private fun externalReason(
        candidate: SensitivityCandidate,
        bounds: SensitivityMetricBounds,
        nowTs: Long,
        freshnessMs: Long
    ): String? {
        val value = candidate.value ?: return candidate.unavailableReason.normalizedReason("value_missing")
        if (!value.isFinite()) return "non_finite"
        if (!bounds.effectiveContains(value)) return "out_of_bounds"
        return candidateTimestampReason(candidate, nowTs, freshnessMs)
    }

    private fun qualifiedExternalReason(
        candidate: SensitivityCandidate,
        bounds: SensitivityMetricBounds,
        nowTs: Long,
        freshnessMs: Long
    ): String? {
        externalReason(candidate, bounds, nowTs, freshnessMs)?.let { return it }
        if (!candidate.qualityPassed) return "quality_gate_failed"
        if (candidate.sampleCount <= 0) return "sample_count_empty"
        if (!candidate.coverage.isFinite() || candidate.coverage <= 0.0 || candidate.coverage > 1.0) {
            return "coverage_gate_failed"
        }
        if (!candidate.confidence.isFinite() || normalizedConfidence(candidate.confidence) <= 0.0) {
            return "confidence_gate_failed"
        }
        return null
    }

    private fun candidateTimestampReason(
        candidate: SensitivityCandidate,
        nowTs: Long,
        freshnessMs: Long
    ): String? {
        val timestamp = candidate.timestamp ?: return "timestamp_missing"
        if (timestamp <= 0L) return "timestamp_missing"
        if (timestamp > nowTs) return "timestamp_future"
        if (nowTs - timestamp > freshnessMs) return "timestamp_stale"
        return null
    }

    private fun normalizedConfidence(value: Double): Double =
        value.takeIf(Double::isFinite)?.coerceIn(0.0, 1.0) ?: 0.0

    private fun String?.normalizedReason(default: String): String =
        this?.trim()?.takeIf { it.isNotEmpty() } ?: default

}
