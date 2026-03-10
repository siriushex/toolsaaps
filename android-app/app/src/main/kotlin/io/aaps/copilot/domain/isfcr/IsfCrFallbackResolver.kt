package io.aaps.copilot.domain.isfcr

class IsfCrFallbackResolver {

    fun resolve(
        snapshot: IsfCrRealtimeSnapshot,
        settings: IsfCrSettings,
        fallbackIsf: Double?,
        fallbackCr: Double?
    ): IsfCrRealtimeSnapshot {
        if (snapshot.confidence >= settings.confidenceThreshold) {
            return if (settings.shadowMode) {
                snapshot.copy(mode = IsfCrRuntimeMode.SHADOW)
            } else {
                snapshot.copy(mode = IsfCrRuntimeMode.ACTIVE)
            }
        }

        val keepIsfCandidate = shouldKeepMetricCandidate(
            snapshot = snapshot,
            metric = Metric.ISF,
            minEvidencePerHour = settings.minIsfEvidencePerHour,
            absoluteMinEvidence = 2
        )
        val keepCrCandidate = shouldKeepMetricCandidate(
            snapshot = snapshot,
            metric = Metric.CR,
            minEvidencePerHour = settings.minCrEvidencePerHour,
            absoluteMinEvidence = 4
        )
        if (shouldAllowSoftShadow(snapshot, settings, keepIsfCandidate, keepCrCandidate)) {
            return snapshot.copy(
                mode = IsfCrRuntimeMode.SHADOW,
                reasons = (snapshot.reasons + "soft_shadow_keep").distinct()
            )
        }
        val nextIsf = if (keepIsfCandidate) {
            snapshot.isfEff
        } else {
            fallbackIsf ?: snapshot.isfBase
        }
        val nextCr = if (keepCrCandidate) {
            snapshot.crEff
        } else {
            fallbackCr ?: snapshot.crBase
        }
        val extraReasons = buildList {
            if (!keepIsfCandidate) add("isf_metric_fallback_applied")
            if (!keepCrCandidate) add("cr_metric_fallback_applied")
            if (keepIsfCandidate || keepCrCandidate) add("partial_metric_keep")
        }
        return snapshot.copy(
            isfEff = nextIsf.coerceIn(0.8, 18.0),
            crEff = nextCr.coerceIn(2.0, 60.0),
            mode = IsfCrRuntimeMode.FALLBACK,
            reasons = (snapshot.reasons + extraReasons + "low_confidence_fallback").distinct()
        )
    }

    private fun shouldKeepMetricCandidate(
        snapshot: IsfCrRealtimeSnapshot,
        metric: Metric,
        minEvidencePerHour: Int,
        absoluteMinEvidence: Int
    ): Boolean {
        val evidenceCount = when (metric) {
            Metric.ISF -> snapshot.isfEvidenceCount
            Metric.CR -> snapshot.crEvidenceCount
        }
        if (evidenceCount <= 0) return false
        val sensorFalseLow = snapshot.factors["sensor_quality_suspect_false_low"] ?: 0.0
        if (sensorFalseLow >= 0.5) return false

        val hourEnough = when (metric) {
            Metric.ISF -> (snapshot.factors["isf_hour_window_evidence_enough"] ?: 0.0) >= 0.5
            Metric.CR -> (snapshot.factors["cr_hour_window_evidence_enough"] ?: 0.0) >= 0.5
        }
        val strongGlobalEvidence = when (metric) {
            Metric.ISF -> (snapshot.factors["isf_global_evidence_strong"] ?: 0.0) >= 0.5
            Metric.CR -> (snapshot.factors["cr_global_evidence_strong"] ?: 0.0) >= 0.5
        } || evidenceCount >= maxOf(absoluteMinEvidence, minEvidencePerHour.coerceAtLeast(0) * 2)

        if (!(hourEnough || strongGlobalEvidence)) return false
        return snapshot.qualityScore >= 0.35
    }

    private fun shouldAllowSoftShadow(
        snapshot: IsfCrRealtimeSnapshot,
        settings: IsfCrSettings,
        keepIsfCandidate: Boolean,
        keepCrCandidate: Boolean
    ): Boolean {
        if (!settings.shadowMode) return false
        if (!(keepIsfCandidate && keepCrCandidate)) return false
        if ((snapshot.factors["sensor_quality_suspect_false_low"] ?: 0.0) >= 0.5) return false
        if (snapshot.qualityScore < 0.45) return false

        val threshold = settings.confidenceThreshold.coerceIn(0.2, 0.95)
        val softThreshold = maxOf(0.42, threshold - 0.10)
        if (snapshot.confidence < softThreshold) return false

        val isfSupport = (snapshot.factors["isf_hour_window_evidence_enough"] ?: 0.0) >= 0.5 ||
            (snapshot.factors["isf_global_evidence_strong"] ?: 0.0) >= 0.5
        val crSupport = (snapshot.factors["cr_hour_window_evidence_enough"] ?: 0.0) >= 0.5 ||
            (snapshot.factors["cr_global_evidence_strong"] ?: 0.0) >= 0.5
        return isfSupport && crSupport
    }

    private enum class Metric {
        ISF,
        CR
    }
}
