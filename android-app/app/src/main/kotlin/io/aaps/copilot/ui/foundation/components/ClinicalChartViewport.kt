package io.aaps.copilot.ui.foundation.components

internal const val CLINICAL_CHART_MIN_VIEWPORT_MS = 45L * 60_000L
internal const val CLINICAL_CHART_DEFAULT_HISTORY_MS = 3L * 60L * 60_000L

internal enum class ClinicalChartViewportMode { FOLLOW_LIVE, EXPLORE }

internal data class ClinicalChartDomain(
    val startTs: Long,
    val endTs: Long,
    val nowTs: Long
) {
    init {
        require(startTs <= nowTs && nowTs <= endTs) {
            "Clinical chart domain must satisfy startTs <= nowTs <= endTs"
        }
    }

    val durationMs: Long get() = saturatedNonNegativeDuration(startTs, endTs)
}

internal data class ClinicalChartViewport(
    val startTs: Long,
    val endTs: Long,
    val mode: ClinicalChartViewportMode
) {
    init {
        require(startTs <= endTs) {
            "Clinical chart viewport must satisfy startTs <= endTs"
        }
    }

    val durationMs: Long get() = saturatedNonNegativeDuration(startTs, endTs)
}

internal fun defaultClinicalChartViewport(domain: ClinicalChartDomain): ClinicalChartViewport {
    val start = saturatedSubtract(domain.nowTs, CLINICAL_CHART_DEFAULT_HISTORY_MS)
        .coerceAtLeast(domain.startTs)
    return ClinicalChartViewport(start, domain.endTs, ClinicalChartViewportMode.FOLLOW_LIVE)
}

internal fun zoomClinicalChartViewport(
    viewport: ClinicalChartViewport,
    domain: ClinicalChartDomain,
    focalFraction: Double,
    zoomFactor: Float
): ClinicalChartViewport {
    if (!focalFraction.isFinite() || !zoomFactor.isFinite() || zoomFactor <= 0f) {
        return viewport
    }

    val fraction = focalFraction.coerceIn(0.0, 1.0)
    val minimumDuration = CLINICAL_CHART_MIN_VIEWPORT_MS.coerceAtMost(domain.durationMs)
    val duration = (viewport.durationMs.toDouble() / zoomFactor.toDouble())
        .toLong()
        .coerceIn(minimumDuration, domain.durationMs)
    val focalTs = saturatedAdd(viewport.startTs, (viewport.durationMs * fraction).toLong())
    val rawStart = saturatedSubtract(focalTs, (duration * fraction).toLong())
    return clampClinicalChartViewportDuration(
        requestedStartTs = rawStart,
        requestedDurationMs = duration,
        domain = domain,
        mode = ClinicalChartViewportMode.EXPLORE
    )
}

internal fun panClinicalChartViewport(
    viewport: ClinicalChartViewport,
    domain: ClinicalChartDomain,
    deltaMs: Long
): ClinicalChartViewport = clampClinicalChartViewportDuration(
    requestedStartTs = saturatedAdd(viewport.startTs, deltaMs),
    requestedDurationMs = viewport.durationMs,
    domain = domain,
    mode = ClinicalChartViewportMode.EXPLORE
)

internal fun reconcileClinicalChartViewport(
    viewport: ClinicalChartViewport,
    domain: ClinicalChartDomain
): ClinicalChartViewport = if (viewport.mode == ClinicalChartViewportMode.FOLLOW_LIVE) {
    defaultClinicalChartViewport(domain)
} else {
    clampClinicalChartViewport(viewport.startTs, viewport.endTs, domain, viewport.mode)
}

internal fun clampClinicalChartViewport(
    requestedStartTs: Long,
    requestedEndTs: Long,
    domain: ClinicalChartDomain,
    mode: ClinicalChartViewportMode
): ClinicalChartViewport {
    require(requestedStartTs <= requestedEndTs) {
        "Requested clinical chart viewport must satisfy startTs <= endTs"
    }
    return clampClinicalChartViewportDuration(
        requestedStartTs = requestedStartTs,
        requestedDurationMs = saturatedNonNegativeDuration(requestedStartTs, requestedEndTs),
        domain = domain,
        mode = mode
    )
}

private fun clampClinicalChartViewportDuration(
    requestedStartTs: Long,
    requestedDurationMs: Long,
    domain: ClinicalChartDomain,
    mode: ClinicalChartViewportMode
): ClinicalChartViewport {
    val requestedDuration = requestedDurationMs.coerceAtLeast(CLINICAL_CHART_MIN_VIEWPORT_MS)
    if (requestedDuration >= domain.durationMs) {
        return ClinicalChartViewport(domain.startTs, domain.endTs, mode)
    }

    val latestStart = saturatedSubtract(domain.endTs, requestedDuration)
    val start = requestedStartTs.coerceIn(domain.startTs, latestStart)
    return ClinicalChartViewport(
        startTs = start,
        endTs = saturatedAdd(start, requestedDuration),
        mode = mode
    )
}

private fun saturatedNonNegativeDuration(startTs: Long, endTs: Long): Long {
    require(startTs <= endTs) { "Timestamp range must satisfy startTs <= endTs" }
    val duration = endTs - startTs
    return if (duration < 0L) Long.MAX_VALUE else duration
}

private fun saturatedAdd(value: Long, delta: Long): Long {
    val result = value + delta
    val overflowed = ((value xor result) and (delta xor result)) < 0L
    return if (overflowed) {
        if (delta >= 0L) Long.MAX_VALUE else Long.MIN_VALUE
    } else {
        result
    }
}

private fun saturatedSubtract(value: Long, delta: Long): Long {
    val result = value - delta
    val overflowed = ((value xor delta) and (value xor result)) < 0L
    return if (overflowed) {
        if (delta >= 0L) Long.MIN_VALUE else Long.MAX_VALUE
    } else {
        result
    }
}
