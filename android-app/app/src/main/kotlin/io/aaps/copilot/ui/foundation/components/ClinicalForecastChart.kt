package io.aaps.copilot.ui.foundation.components

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.aaps.copilot.ui.foundation.screens.ChartCiPointUi
import io.aaps.copilot.ui.foundation.screens.ChartPointUi
import io.aaps.copilot.ui.foundation.screens.ClinicalForecastChartUiState
import io.aaps.copilot.ui.foundation.screens.eventSourceLabelRes
import io.aaps.copilot.ui.foundation.screens.eventTypeLabelRes
import io.aaps.copilot.ui.foundation.screens.toChartMarkerUi
import io.aaps.copilot.R
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

internal data class ClinicalChartBounds(
    val min: Double,
    val max: Double
)

internal data class ClinicalChartPlotHorizontalBounds(
    val leftPx: Float,
    val rightPx: Float
) {
    val widthPx: Float get() = (rightPx - leftPx).coerceAtLeast(1f)
}

internal fun clinicalChartPlotHorizontalBounds(
    totalWidthPx: Float,
    leftInsetPx: Float,
    rightInsetPx: Float
): ClinicalChartPlotHorizontalBounds {
    val width = totalWidthPx.coerceAtLeast(0f)
    val left = leftInsetPx.coerceIn(0f, width)
    val right = (width - rightInsetPx.coerceAtLeast(0f)).coerceAtLeast(left)
    return ClinicalChartPlotHorizontalBounds(leftPx = left, rightPx = right)
}

internal fun clinicalChartBounds(
    history: List<ChartPointUi>,
    future: List<ChartPointUi>,
    ci: List<ChartCiPointUi>,
    displayLow: Double,
    displayHigh: Double
): ClinicalChartBounds? {
    val series = history + future
    if (series.size < 2) return null
    val values = buildList {
        addAll(series.map { it.value })
        addAll(ci.map { it.low })
        addAll(ci.map { it.high })
        add(displayLow)
        add(displayHigh)
    }.filter { it.isFinite() }
    if (values.isEmpty()) return null
    val rawMin = values.minOrNull() ?: return null
    val rawMax = values.maxOrNull() ?: return null
    val padding = max(0.4, (rawMax - rawMin) * 0.12)
    val quantizedMin = kotlin.math.floor((rawMin - padding) * 2.0) / 2.0
    val quantizedMax = kotlin.math.ceil((rawMax + padding) * 2.0) / 2.0
    val boundedMin = quantizedMin.coerceIn(0.0, 22.0).normalizeClinicalChartZero()
    val boundedMax = quantizedMax.coerceIn(0.0, 22.0).normalizeClinicalChartZero()
    return ClinicalChartBounds(
        min = min(boundedMin, boundedMax),
        max = max(boundedMin, boundedMax)
    )
}

private fun Double.normalizeClinicalChartZero(): Double = if (this == 0.0) 0.0 else this

internal fun shouldDrawClinicalChartStartLabel(
    startX: Float,
    startWidth: Float,
    nowX: Float,
    minimumGap: Float
): Boolean = startX + startWidth + minimumGap <= nowX

internal fun shouldDrawClinicalChartPlus30Label(
    domainMaxTs: Long,
    plus30Ts: Long,
    nowLabelX: Float,
    nowLabelWidth: Float,
    plus30LabelX: Float,
    plus30LabelWidth: Float,
    plotRight: Float,
    minimumGap: Float
): Boolean {
    if (domainMaxTs < plus30Ts) return false
    val separatedFromNow = plus30LabelX >= nowLabelX + nowLabelWidth + minimumGap
    val insidePlot = plus30LabelX + plus30LabelWidth <= plotRight
    return separatedFromNow && insidePlot
}

data class ClinicalForecastComponent(
    val enabled: Boolean,
    val delta60Mmol: Double?,
    val color: Color
)

@Composable
internal fun ClinicalForecastChart(
    state: ClinicalForecastChartUiState,
    contentDescription: String,
    emptyText: String,
    nowLabel: String,
    plus30Label: String,
    modifier: Modifier = Modifier,
    components: List<ClinicalForecastComponent> = emptyList(),
    viewport: ClinicalChartViewport? = null
) {
    var selectedEventIds by remember { mutableStateOf<List<String>>(emptyList()) }
    val visibleHistory = remember(state.historyPoints, viewport) {
        viewport?.let { selectClinicalChartPoints(state.historyPoints, it.startTs, it.endTs) }
            ?: state.historyPoints
    }
    val visibleFuture = remember(state.futurePath, viewport) {
        viewport?.let { selectClinicalChartPoints(state.futurePath, it.startTs, it.endTs) }
            ?: state.futurePath
    }
    val visibleCi = remember(state.futureCi, viewport) {
        viewport?.let { selectClinicalChartCiPoints(state.futureCi, it.startTs, it.endTs) }
            ?: state.futureCi
    }
    val foodPoints = remember(state.mealImpactPoints, viewport) {
        val points = state.mealImpactPoints.filter { it.value.isFinite() && it.value > 0.0 }
        viewport?.let { selectClinicalChartPoints(points, it.startTs, it.endTs) } ?: points
    }
    val bounds = remember(
        visibleHistory,
        visibleFuture,
        foodPoints,
        visibleCi,
        state.displayRangeLowMmol,
        state.displayRangeHighMmol,
        viewport
    ) {
        clinicalChartBounds(
            history = visibleHistory.filter { viewport == null || it.ts in viewport.startTs..viewport.endTs },
            future = (visibleFuture + foodPoints).filter { viewport == null || it.ts in viewport.startTs..viewport.endTs },
            ci = visibleCi.filter { viewport == null || it.ts in viewport.startTs..viewport.endTs },
            displayLow = state.displayRangeLowMmol,
            displayHigh = state.displayRangeHighMmol
        )
    }
    if (bounds == null) {
        Box(
            modifier = modifier
                .fillMaxWidth()
                .height(280.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(text = emptyText, style = MaterialTheme.typography.bodyMedium)
        }
        return
    }

    val gridColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.18f)
    val chartColors = clinicalChartColors(MaterialTheme.colorScheme.surface)
    val displayBandColor = chartColors.displayBand
    val targetLineColor = Color(0xFF55B66D)
    val ciColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
    val historyColor = MaterialTheme.colorScheme.onSurface
    val futureColor = chartColors.future
    val markerColor = Color(0xFF2F9E55)
    val markerLineColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.42f)
    val axisColor = MaterialTheme.colorScheme.onSurfaceVariant
    val foodColor = if (MaterialTheme.colorScheme.surface.luminance() > 0.5f)
        Color(0xFFA97900) else Color(0xFFFFD54F)
    val timeFormatter = remember { DateTimeFormatter.ofPattern("HH:mm") }
    val axisPaint = remember { Paint(Paint.ANTI_ALIAS_FLAG) }
    axisPaint.color = axisColor.toArgb()
    axisPaint.textSize = with(LocalDensity.current) { 10.sp.toPx() }
    val series = visibleHistory + visibleFuture
    val minTs = viewport?.startTs ?: series.minOf { it.ts }
    val maxTs = viewport?.endTs ?: series.maxOf { it.ts }
    val eventTypeLabels = mutableListOf<String>()
    for (event in state.events.take(12)) {
        eventTypeLabels += stringResource(eventTypeLabelRes(event.type))
    }
    val eventTypeSummary = eventTypeLabels.joinToString(", ")
    val accessibleChartDescription = if (state.showEvents && eventTypeSummary.isNotEmpty()) {
        stringResource(R.string.events_chart_description, contentDescription, eventTypeSummary)
    } else {
        contentDescription
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(280.dp)
    ) {
        Canvas(
            modifier = Modifier
            .fillMaxSize()
            .semantics {
                this.contentDescription = accessibleChartDescription
            }
    ) {
        val horizontalBounds = clinicalChartPlotHorizontalBounds(
            totalWidthPx = size.width,
            leftInsetPx = 34.dp.toPx(),
            rightInsetPx = 40.dp.toPx()
        )
        val plotLeft = horizontalBounds.leftPx
        val plotTop = 44.dp.toPx()
        val plotRight = horizontalBounds.rightPx
        val plotBottom = size.height - 20.dp.toPx()
        val plotWidth = horizontalBounds.widthPx
        val plotHeight = (plotBottom - plotTop).coerceAtLeast(1f)
        val historyForPath = if (viewport == null) {
            visibleHistory
        } else {
            reduceClinicalChartPoints(visibleHistory, minTs, maxTs, plotWidth.toInt())
        }
        val futureForPath = if (viewport == null) {
            visibleFuture
        } else {
            reduceClinicalChartPoints(visibleFuture, minTs, maxTs, plotWidth.toInt())
        }
        val now = state.historyPoints.lastOrNull { it.value.isFinite() }
        val nowVisible = now?.takeIf { it.ts in minTs..maxTs }
        val plus30Ts = now?.ts?.plus(30L * 60_000L)
        val plus30Visible = plus30Ts?.takeIf { it in minTs..maxTs }

        fun xOf(ts: Long): Float {
            if (maxTs <= minTs) return plotLeft
            if (ts <= minTs) return plotLeft
            if (ts >= maxTs) return plotRight
            val duration = (maxTs.toULong() - minTs.toULong()).toDouble()
            val offset = (ts.toULong() - minTs.toULong()).toDouble()
            val ratio = offset / duration
            return plotLeft + (ratio * plotWidth).toFloat()
        }

        fun yOf(value: Double): Float {
            if (bounds.max <= bounds.min) return plotTop + plotHeight / 2f
            val ratio = (value - bounds.min) / (bounds.max - bounds.min)
            return plotBottom - (ratio * plotHeight).toFloat()
        }

        repeat(5) { index ->
            val y = plotTop + plotHeight * index / 4f
            drawLine(
                color = gridColor,
                start = Offset(plotLeft, y),
                end = Offset(plotRight, y),
                strokeWidth = 1f
            )
        }

        val bandTop = yOf(state.displayRangeHighMmol)
        val bandBottom = yOf(state.displayRangeLowMmol)
        clipRect(left = plotLeft, top = plotTop, right = plotRight, bottom = plotBottom) {
            drawRect(
                color = displayBandColor,
                topLeft = Offset(plotLeft, bandTop),
                size = Size(plotWidth, (bandBottom - bandTop).coerceAtLeast(0f))
            )
            val targetDash = PathEffect.dashPathEffect(floatArrayOf(7f, 6f))
            drawLine(
                color = targetLineColor,
                start = Offset(plotLeft, bandTop),
                end = Offset(plotRight, bandTop),
                pathEffect = targetDash
            )
            drawLine(
                color = targetLineColor,
                start = Offset(plotLeft, bandBottom),
                end = Offset(plotRight, bandBottom),
                pathEffect = targetDash
            )

            if (visibleCi.size >= 2) {
                val area = Path()
                visibleCi.forEachIndexed { index, point ->
                    val x = xOf(point.ts)
                    val y = yOf(point.high)
                    if (index == 0) area.moveTo(x, y) else area.lineTo(x, y)
                }
                visibleCi.asReversed().forEach { point ->
                    area.lineTo(xOf(point.ts), yOf(point.low))
                }
                area.close()
                drawPath(path = area, color = ciColor)
            }

            drawClinicalPolyline(
                points = historyForPath,
                xOf = ::xOf,
                yOf = ::yOf,
                color = historyColor
            )
            drawClinicalPolyline(
                points = futureForPath,
                xOf = ::xOf,
                yOf = ::yOf,
                color = futureColor,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f))
            )
            drawClinicalPolyline(
                points = foodPoints,
                xOf = ::xOf,
                yOf = ::yOf,
                color = foodColor
            )

            nowVisible?.let { current ->
                val markerDash = PathEffect.dashPathEffect(floatArrayOf(7f, 7f))
                drawLine(
                    color = markerLineColor,
                    start = Offset(xOf(current.ts), plotTop),
                    end = Offset(xOf(current.ts), plotBottom),
                    strokeWidth = 1.5f,
                    pathEffect = markerDash
                )
                drawCircle(
                    color = markerColor,
                    radius = 7f,
                    center = Offset(xOf(current.ts), yOf(current.value))
                )
                drawCircle(
                    color = Color.White,
                    radius = 3.2f,
                    center = Offset(xOf(current.ts), yOf(current.value))
                )
                plus30Visible?.let { visiblePlus30Ts ->
                    visibleFuture
                        .minByOrNull { abs(it.ts - visiblePlus30Ts) }
                        ?.takeIf {
                            it.ts in minTs..maxTs &&
                                abs(it.ts - visiblePlus30Ts) <= 5L * 60_000L
                        }
                        ?.let { point ->
                            drawCircle(
                                color = Color.White,
                                radius = 6f,
                                center = Offset(xOf(point.ts), yOf(point.value))
                            )
                            drawCircle(
                                color = futureColor,
                                radius = 6f,
                                center = Offset(xOf(point.ts), yOf(point.value)),
                                style = Stroke(width = 2.5f)
                            )
                        }
                }
                components
                    .filter { it.enabled }
                    .forEach { component ->
                        drawClinicalComponent(
                            base = current,
                            endTs = futureForPath.lastOrNull()?.ts,
                            delta60Mmol = component.delta60Mmol,
                            xOf = ::xOf,
                            yOf = ::yOf,
                            color = component.color
                        )
                    }
            }
        }

        val yLabels = listOf(
            bounds.max to yOf(bounds.max),
            state.displayRangeHighMmol to bandTop,
            state.displayRangeLowMmol to bandBottom,
            bounds.min to yOf(bounds.min)
        ).distinctBy { (value, _) -> String.format("%.1f", value) }
        yLabels.forEach { (value, y) ->
            drawContext.canvas.nativeCanvas.drawText(
                String.format("%.1f", value),
                1.dp.toPx(),
                (y + 3.dp.toPx()).coerceIn(plotTop + 8.dp.toPx(), plotBottom),
                axisPaint
            )
        }

        fun timeLabel(ts: Long): String = Instant.ofEpochMilli(ts)
            .atZone(ZoneId.systemDefault())
            .format(timeFormatter)
        val startLabel = timeLabel(minTs)
        val nowLabelWidth = axisPaint.measureText(nowLabel)
        val nowLabelX = nowVisible?.let {
            (xOf(it.ts) - nowLabelWidth / 2f)
                .coerceIn(plotLeft, plotRight - nowLabelWidth)
        }
        if (
            nowLabelX == null || shouldDrawClinicalChartStartLabel(
                startX = plotLeft,
                startWidth = axisPaint.measureText(startLabel),
                nowX = nowLabelX,
                minimumGap = 6.dp.toPx()
            )
        ) {
            drawContext.canvas.nativeCanvas.drawText(
                startLabel,
                plotLeft,
                size.height - 3.dp.toPx(),
                axisPaint
            )
        }
        nowVisible?.let {
            drawContext.canvas.nativeCanvas.drawText(
                nowLabel,
                nowLabelX ?: plotLeft,
                size.height - 3.dp.toPx(),
                axisPaint
            )
            plus30Visible?.let { visiblePlus30Ts ->
                val plus30LabelWidth = axisPaint.measureText(plus30Label)
                val plus30LabelX = (xOf(visiblePlus30Ts) - plus30LabelWidth / 2f)
                    .coerceIn(plotLeft, plotRight - plus30LabelWidth)
                if (
                    shouldDrawClinicalChartPlus30Label(
                        domainMaxTs = maxTs,
                        plus30Ts = visiblePlus30Ts,
                        nowLabelX = nowLabelX ?: plotLeft,
                        nowLabelWidth = nowLabelWidth,
                        plus30LabelX = plus30LabelX,
                        plus30LabelWidth = plus30LabelWidth,
                        plotRight = plotRight,
                        minimumGap = 6.dp.toPx()
                    )
                ) {
                    drawContext.canvas.nativeCanvas.drawText(
                        plus30Label,
                        plus30LabelX,
                        size.height - 3.dp.toPx(),
                        axisPaint
                    )
                }
            }
        }
        }
        if (state.showEvents) {
            EventTimelineRail(
                markers = state.events
                    .filter { it.startTs <= maxTs && it.endTs >= minTs }
                    .map { it.toChartMarkerUi(state.eventTimelineNowTs) },
                rangeStartTs = minTs,
                rangeEndTs = maxTs,
                onEventSelected = { selectedEventIds = it.representedEventIds },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 34.dp, end = 4.dp)
                    .align(Alignment.TopStart)
            )
        }
    }
    val selectedEvents = selectedEventIds.mapNotNull { selectedId ->
        state.events.firstOrNull { it.localId == selectedId }
    }
    if (selectedEvents.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = { selectedEventIds = emptyList() },
            title = { Text(stringResource(R.string.events_details_title)) },
            text = {
                LazyColumn(
                    modifier = Modifier
                        .heightIn(max = 320.dp)
                        .testTag("eventClusterDetails"),
                    verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)
                ) {
                    items(selectedEvents, key = { it.localId }) { selected ->
                        Column {
                            val typeLabel = stringResource(eventTypeLabelRes(selected.type))
                            Text(selected.title.ifBlank { typeLabel })
                            Text(typeLabel)
                            Text(stringResource(eventSourceLabelRes(selected.source)))
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { selectedEventIds = emptyList() }) {
                    Text(stringResource(R.string.events_close))
                }
            }
        )
    }
}

private fun DrawScope.drawClinicalPolyline(
    points: List<ChartPointUi>,
    xOf: (Long) -> Float,
    yOf: (Double) -> Float,
    color: Color,
    pathEffect: PathEffect? = null
) {
    val finite = points.filter { it.value.isFinite() }
    if (finite.size < 2) return
    val path = Path().apply {
        finite.forEachIndexed { index, point ->
            if (index == 0) moveTo(xOf(point.ts), yOf(point.value))
            else lineTo(xOf(point.ts), yOf(point.value))
        }
    }
    drawPath(
        path = path,
        color = color,
        style = Stroke(width = 3.5f, cap = StrokeCap.Round, pathEffect = pathEffect)
    )
}

private fun DrawScope.drawClinicalComponent(
    base: ChartPointUi,
    endTs: Long?,
    delta60Mmol: Double?,
    xOf: (Long) -> Float,
    yOf: (Double) -> Float,
    color: Color
) {
    if (endTs == null || endTs <= base.ts || delta60Mmol?.isFinite() != true) return
    val hours = (endTs - base.ts).toDouble() / 3_600_000.0
    val endValue = base.value + delta60Mmol * hours
    drawLine(
        color = color,
        start = Offset(xOf(base.ts), yOf(base.value)),
        end = Offset(xOf(endTs), yOf(endValue)),
        strokeWidth = 2f,
        pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f))
    )
}
