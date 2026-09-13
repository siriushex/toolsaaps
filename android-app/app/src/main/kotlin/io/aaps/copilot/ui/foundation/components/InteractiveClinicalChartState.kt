package io.aaps.copilot.ui.foundation.components

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.setValue
import kotlin.math.abs

internal enum class ClinicalChartGestureIntent {
    UNDECIDED,
    HORIZONTAL_PAN,
    PINCH,
    PASS_TO_PARENT
}

internal fun resolveClinicalChartGestureIntent(
    pointerCount: Int,
    accumulatedX: Float,
    accumulatedY: Float,
    touchSlop: Float
): ClinicalChartGestureIntent = when {
    pointerCount >= 2 -> ClinicalChartGestureIntent.PINCH
    abs(accumulatedX) < touchSlop && abs(accumulatedY) < touchSlop -> {
        ClinicalChartGestureIntent.UNDECIDED
    }
    abs(accumulatedX) > abs(accumulatedY) -> ClinicalChartGestureIntent.HORIZONTAL_PAN
    else -> ClinicalChartGestureIntent.PASS_TO_PARENT
}

internal fun nextClinicalChartGestureIntent(
    currentIntent: ClinicalChartGestureIntent,
    pointerCount: Int,
    accumulatedX: Float,
    accumulatedY: Float,
    touchSlop: Float
): ClinicalChartGestureIntent = if (currentIntent == ClinicalChartGestureIntent.UNDECIDED) {
    resolveClinicalChartGestureIntent(pointerCount, accumulatedX, accumulatedY, touchSlop)
} else {
    currentIntent
}

internal data class ClinicalChartViewportSnapshot(
    val durationMs: Long,
    val endOffsetFromNowMs: Long,
    val mode: ClinicalChartViewportMode
)

@Stable
internal class InteractiveClinicalChartState(initialDomain: ClinicalChartDomain) {
    var viewport by mutableStateOf(defaultClinicalChartViewport(initialDomain))
        private set

    var domain by mutableStateOf(initialDomain)
        private set

    fun updateDomain(updatedDomain: ClinicalChartDomain) {
        domain = updatedDomain
        viewport = reconcileClinicalChartViewport(viewport, domain)
    }

    fun panByPixels(deltaPx: Float, plotWidthPx: Float) {
        if (plotWidthPx <= 0f || !deltaPx.isFinite()) return

        val deltaMs = (-deltaPx.toDouble() / plotWidthPx * viewport.durationMs).toLong()
        viewport = panClinicalChartViewport(viewport, domain, deltaMs)
    }

    fun zoomBy(scale: Float, focalXPx: Float, plotWidthPx: Float) {
        if (plotWidthPx <= 0f || !scale.isFinite() || scale <= 0f) return

        viewport = zoomClinicalChartViewport(
            viewport = viewport,
            domain = domain,
            focalFraction = (focalXPx / plotWidthPx).toDouble(),
            zoomFactor = scale
        )
    }

    fun restore(snapshot: ClinicalChartViewportSnapshot) {
        val endTs = domain.nowTs + snapshot.endOffsetFromNowMs
        viewport = clampClinicalChartViewport(
            requestedStartTs = endTs - snapshot.durationMs,
            requestedEndTs = endTs,
            domain = domain,
            mode = snapshot.mode
        )
    }

    fun snapshot(): ClinicalChartViewportSnapshot = ClinicalChartViewportSnapshot(
        durationMs = viewport.durationMs,
        endOffsetFromNowMs = viewport.endTs - domain.nowTs,
        mode = viewport.mode
    )

    fun reset() {
        viewport = defaultClinicalChartViewport(domain)
    }
}

internal fun interactiveClinicalChartStateSaver(domain: ClinicalChartDomain) =
    listSaver<InteractiveClinicalChartState, Any>(
        save = { state ->
            val snapshot = state.snapshot()
            listOf(snapshot.durationMs, snapshot.endOffsetFromNowMs, snapshot.mode.name)
        },
        restore = { restored ->
            val snapshot = ClinicalChartViewportSnapshot(
                durationMs = restored[0] as Long,
                endOffsetFromNowMs = restored[1] as Long,
                mode = ClinicalChartViewportMode.valueOf(restored[2] as String)
            )
            InteractiveClinicalChartState(domain).apply { restore(snapshot) }
        }
    )
