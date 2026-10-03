package io.aaps.copilot.ui.foundation.components

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import io.aaps.copilot.ui.foundation.screens.ClinicalForecastChartUiState

@Composable
internal fun rememberInteractiveClinicalChartState(
    chart: ClinicalForecastChartUiState
): InteractiveClinicalChartState? {
    val domain = remember(chart) { clinicalChartDomain(chart) } ?: return null
    val initialDomain = remember { domain }
    val interactionState = rememberSaveable(
        saver = interactiveClinicalChartStateSaver(initialDomain)
    ) {
        InteractiveClinicalChartState(initialDomain)
    }
    LaunchedEffect(domain) {
        interactionState.updateDomain(domain)
    }
    return interactionState
}

internal fun clinicalChartDomain(chart: ClinicalForecastChartUiState): ClinicalChartDomain? {
    var finiteSeriesCount = 0
    var startTs = Long.MAX_VALUE
    var endTs = Long.MIN_VALUE
    var nowTs: Long? = null

    fun include(ts: Long) {
        startTs = minOf(startTs, ts)
        endTs = maxOf(endTs, ts)
    }

    chart.historyPoints.forEach { point ->
        if (point.value.isFinite()) {
            finiteSeriesCount += 1
            include(point.ts)
            nowTs = maxOf(nowTs ?: Long.MIN_VALUE, point.ts)
        }
    }
    chart.futurePath.forEach { point ->
        if (point.value.isFinite()) {
            finiteSeriesCount += 1
            include(point.ts)
        }
    }
    if (finiteSeriesCount < 2) return null
    chart.futureCi.forEach { point ->
        if (point.low.isFinite() && point.high.isFinite()) include(point.ts)
    }
    return ClinicalChartDomain(
        startTs = startTs,
        endTs = endTs,
        nowTs = nowTs ?: endTs
    )
}

@Composable
internal fun InteractiveClinicalForecastChart(
    state: ClinicalForecastChartUiState,
    interactionState: InteractiveClinicalChartState,
    contentDescription: String,
    viewportStateDescription: String,
    emptyText: String,
    nowLabel: String,
    plus30Label: String,
    zoomInLabel: String,
    zoomOutLabel: String,
    olderLabel: String,
    newerLabel: String,
    resetLabel: String,
    modifier: Modifier = Modifier,
    components: List<ClinicalForecastComponent> = emptyList(),
    onViewportChanged: ((ClinicalChartViewport) -> Unit)? = null
) {
    val density = LocalDensity.current
    var plotBounds by remember { mutableStateOf(ClinicalChartPlotHorizontalBounds(0f, 1f)) }
    LaunchedEffect(interactionState.viewport, onViewportChanged) {
        onViewportChanged?.invoke(interactionState.viewport)
    }
    ClinicalForecastChart(
        state = state,
        contentDescription = contentDescription,
        emptyText = emptyText,
        nowLabel = nowLabel,
        plus30Label = plus30Label,
        components = components,
        viewport = interactionState.viewport,
        modifier = modifier
            .testTag("overviewInteractiveChart")
            .onSizeChanged { size ->
                plotBounds = with(density) {
                    clinicalChartPlotHorizontalBounds(
                        totalWidthPx = size.width.toFloat(),
                        leftInsetPx = 34.dp.toPx(),
                        rightInsetPx = 40.dp.toPx()
                    )
                }
            }
            .clinicalChartGestures(interactionState, plotBounds)
            .semantics {
                stateDescription = viewportStateDescription
                customActions = listOf(
                    CustomAccessibilityAction(zoomInLabel) {
                        interactionState.zoomBy(1.5f, plotBounds.widthPx / 2f, plotBounds.widthPx)
                        true
                    },
                    CustomAccessibilityAction(zoomOutLabel) {
                        interactionState.zoomBy(1f / 1.5f, plotBounds.widthPx / 2f, plotBounds.widthPx)
                        true
                    },
                    CustomAccessibilityAction(olderLabel) {
                        interactionState.panByPixels(plotBounds.widthPx * 0.25f, plotBounds.widthPx)
                        true
                    },
                    CustomAccessibilityAction(newerLabel) {
                        interactionState.panByPixels(-plotBounds.widthPx * 0.25f, plotBounds.widthPx)
                        true
                    },
                    CustomAccessibilityAction(resetLabel) {
                        interactionState.reset()
                        true
                    }
                )
            }
    )
}

private fun Modifier.clinicalChartGestures(
    interactionState: InteractiveClinicalChartState,
    plotBounds: ClinicalChartPlotHorizontalBounds
): Modifier = pointerInput(interactionState, plotBounds) {
    var previousTapUptime = Long.MIN_VALUE
    var previousTapPosition: Offset? = null
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        var intent = ClinicalChartGestureIntent.UNDECIDED
        var accumulated = Offset.Zero
        var finalPosition = down.position
        var finalUptime = down.uptimeMillis
        var multiTouch = false
        var event: androidx.compose.ui.input.pointer.PointerEvent

        do {
            event = awaitPointerEvent()
            val pressed = event.changes.filter { it.pressed }
            finalPosition = event.changes.firstOrNull()?.position ?: finalPosition
            finalUptime = event.changes.maxOfOrNull { it.uptimeMillis } ?: finalUptime

            if (pressed.size >= 2) {
                multiTouch = true
            }
            if (pressed.size >= 2 && intent == ClinicalChartGestureIntent.UNDECIDED) {
                intent = ClinicalChartGestureIntent.PINCH
            }
            if (pressed.size >= 2 && intent == ClinicalChartGestureIntent.PINCH) {
                interactionState.zoomBy(
                    scale = event.calculateZoom(),
                    focalXPx = event.calculateCentroid(useCurrent = true).x - plotBounds.leftPx,
                    plotWidthPx = plotBounds.widthPx
                )
                event.changes.forEach { it.consume() }
            } else if (pressed.size == 1 && intent != ClinicalChartGestureIntent.PINCH) {
                val change = pressed.single()
                val delta = change.positionChange()
                accumulated += delta
                val previousIntent = intent
                intent = nextClinicalChartGestureIntent(
                    currentIntent = intent,
                    pointerCount = 1,
                    accumulatedX = accumulated.x,
                    accumulatedY = accumulated.y,
                    touchSlop = viewConfiguration.touchSlop
                )
                if (intent == ClinicalChartGestureIntent.HORIZONTAL_PAN) {
                    interactionState.panByPixels(
                        deltaPx = if (previousIntent == ClinicalChartGestureIntent.UNDECIDED) {
                            accumulated.x
                        } else {
                            delta.x
                        },
                        plotWidthPx = plotBounds.widthPx
                    )
                    change.consume()
                }
            }
        } while (event.changes.any { it.pressed })

        if (!multiTouch && intent == ClinicalChartGestureIntent.UNDECIDED) {
            val elapsed = finalUptime - previousTapUptime
            val previousPosition = previousTapPosition
            val isDoubleTap = previousPosition != null &&
                elapsed in viewConfiguration.doubleTapMinTimeMillis..viewConfiguration.doubleTapTimeoutMillis &&
                (finalPosition - previousPosition).getDistance() <= viewConfiguration.touchSlop * 2f
            if (isDoubleTap) {
                interactionState.reset()
                previousTapUptime = Long.MIN_VALUE
                previousTapPosition = null
            } else {
                previousTapUptime = finalUptime
                previousTapPosition = finalPosition
            }
        } else {
            previousTapUptime = Long.MIN_VALUE
            previousTapPosition = null
        }
    }
}
