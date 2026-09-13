package io.aaps.copilot.ui.foundation.components

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class InteractiveClinicalChartStateTest {
    private val hour = 60L * 60_000L
    private val domain = ClinicalChartDomain(0L, 25L * hour, 24L * hour)

    @Test
    fun gestureIntent_requiresHorizontalDominanceOrTwoPointers() {
        assertThat(resolveClinicalChartGestureIntent(1, 30f, 8f, 12f))
            .isEqualTo(ClinicalChartGestureIntent.HORIZONTAL_PAN)
        assertThat(resolveClinicalChartGestureIntent(1, 8f, 30f, 12f))
            .isEqualTo(ClinicalChartGestureIntent.PASS_TO_PARENT)
        assertThat(resolveClinicalChartGestureIntent(2, 0f, 0f, 12f))
            .isEqualTo(ClinicalChartGestureIntent.PINCH)
    }

    @Test
    fun gestureIntent_keepsParentScrollDecisionUntilPointerUp() {
        val intent = nextClinicalChartGestureIntent(
            currentIntent = ClinicalChartGestureIntent.PASS_TO_PARENT,
            pointerCount = 1,
            accumulatedX = 80f,
            accumulatedY = 16f,
            touchSlop = 12f
        )

        assertThat(intent).isEqualTo(ClinicalChartGestureIntent.PASS_TO_PARENT)
    }

    @Test
    fun panByPixels_mapsAQuarterWidthToAQuarterDuration() {
        val state = InteractiveClinicalChartState(domain)

        state.panByPixels(deltaPx = 100f, plotWidthPx = 400f)

        assertThat(state.viewport.startTs).isEqualTo(20L * hour)
        assertThat(state.viewport.endTs).isEqualTo(24L * hour)
        assertThat(state.viewport.mode).isEqualTo(ClinicalChartViewportMode.EXPLORE)
    }

    @Test
    fun zoomBy_usesHorizontalFocalPointAndBounds() {
        val state = InteractiveClinicalChartState(domain)

        state.zoomBy(scale = 2f, focalXPx = 100f, plotWidthPx = 400f)

        assertThat(state.viewport.durationMs).isEqualTo(2L * hour)
        assertThat(state.viewport.startTs).isEqualTo(21L * hour + 30L * 60_000L)
    }

    @Test
    fun reset_returnsStateToFollowLive() {
        val state = InteractiveClinicalChartState(domain)
        state.panByPixels(deltaPx = -100f, plotWidthPx = 400f)
        assertThat(state.viewport.mode).isEqualTo(ClinicalChartViewportMode.EXPLORE)
        state.reset()
        assertThat(state.viewport).isEqualTo(defaultClinicalChartViewport(domain))
    }

    @Test
    fun snapshot_restoresDurationOffsetAndMode() {
        val state = InteractiveClinicalChartState(domain)
        state.panByPixels(deltaPx = 100f, plotWidthPx = 400f)
        val snapshot = state.snapshot()

        val restored = InteractiveClinicalChartState(domain).apply { restore(snapshot) }

        assertThat(restored.viewport).isEqualTo(state.viewport)
    }
}
