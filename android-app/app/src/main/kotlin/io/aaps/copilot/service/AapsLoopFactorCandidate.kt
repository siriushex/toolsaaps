package io.aaps.copilot.service

import io.aaps.copilot.data.local.entity.TelemetrySampleEntity
import io.aaps.copilot.data.repository.LocalAapsLoopInputSync
import io.aaps.copilot.domain.predict.SensitivityCandidate
import io.aaps.copilot.domain.predict.SensitivityMetricKind
import io.aaps.copilot.domain.predict.SensitivityRuntimeContract

internal fun aapsLoopFactorCandidate(history: List<TelemetrySampleEntity>, activeNative: List<TelemetrySampleEntity>,
                                      now: Long, kind: SensitivityMetricKind): SensitivityCandidate {
    val isf = kind == SensitivityMetricKind.ISF
    val keys = if (isf) setOf("isf_value", "raw_isf", "aaps_isf", "isf") else setOf("cr_value", "raw_cr", "aaps_cr", "cr")
    val bounds = if (isf) SensitivityRuntimeContract.isf else SensitivityRuntimeContract.cr
    val nativeKey = if (isf) "aaps_isf" else "aaps_cr"
    val selected = (history.asSequence().filter { it.source.equals("aaps_broadcast", true) && it.key in keys } +
        activeNative.asSequence().filter { it.source == LocalAapsLoopInputSync.SOURCE && it.key == nativeKey &&
            LocalAapsLoopInputSync.current(it, now) })
        .filter { it.timestamp in 1..now && it.quality.trim().uppercase() in setOf("TRUSTED", "GOOD", "OK") &&
            it.valueDouble?.let { value -> value in bounds.diagnosticMin..bounds.maximum } == true }
        .maxWithOrNull(compareBy<TelemetrySampleEntity> { it.timestamp }
            .thenBy { it.source.equals("aaps_broadcast", true) }.thenBy { it.id })
    return SensitivityCandidate(value = selected?.valueDouble, timestamp = selected?.timestamp ?: 0L,
        confidence = if (selected == null) 0.0 else 1.0, qualityPassed = selected != null,
        sampleCount = if (selected == null) 0 else 1, coverage = if (selected == null) 0.0 else 1.0,
        unavailableReason = if (selected == null) "aaps_candidate_missing" else null)
}
