package io.aaps.copilot.ui.foundation.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MedicalServices
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material.icons.filled.TrendingUp
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.aaps.copilot.R
import io.aaps.copilot.ui.foundation.components.DebugRow
import io.aaps.copilot.ui.foundation.design.AppElevation
import io.aaps.copilot.ui.foundation.design.Spacing
import io.aaps.copilot.ui.foundation.format.UiFormatters
import io.aaps.copilot.ui.foundation.theme.AapsCopilotTheme
import kotlin.math.max
import kotlin.math.min
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue

private val ForecastSectionShape = RoundedCornerShape(18.dp)
private val ForecastInfoShape = RoundedCornerShape(12.dp)
private data class LayerChipPalette(
    val container: Color,
    val onContainer: Color,
    val border: Color
)

@Composable
fun ForecastScreen(
    state: ForecastUiState,
    onSelectRange: (ForecastRangeUi) -> Unit,
    onLayerChange: (ForecastLayerState) -> Unit,
    modifier: Modifier = Modifier
) {
    ScreenStateLayout(
        loadState = state.loadState,
        isStale = state.isStale,
        errorText = state.errorText,
        emptyText = stringResource(id = R.string.forecast_empty)
    ) {
        LazyColumn(
            modifier = modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            item {
                ForecastControlsCard(
                    state = state,
                    onSelectRange = onSelectRange,
                    onLayerChange = onLayerChange
                )
            }
            item {
                ForecastChartCard(state = state)
            }
            item {
                ForecastHorizonsCard(horizons = state.horizons)
            }
            item {
                DecompositionCard(state = state, showProMetrics = state.isProMode)
            }
            if (state.qualityLines.isNotEmpty()) {
                item {
                    QualityCard(lines = state.qualityLines)
                }
            }
        }
    }
}

@Composable
private fun ForecastControlsCard(
    state: ForecastUiState,
    onSelectRange: (ForecastRangeUi) -> Unit,
    onLayerChange: (ForecastLayerState) -> Unit
) {
    ForecastSectionCard {
        ForecastSectionLabel(
            text = stringResource(id = R.string.section_forecast_range),
            infoText = stringResource(id = R.string.forecast_info_range_section)
        )
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            val ranges = listOf(ForecastRangeUi.H3, ForecastRangeUi.H6, ForecastRangeUi.H24)
            ranges.forEachIndexed { index, range ->
                SegmentedButton(
                    selected = state.range == range,
                    onClick = { onSelectRange(range) },
                    shape = SegmentedButtonDefaults.itemShape(
                        index = index,
                        count = ranges.size
                    ),
                    label = {
                        Text(
                            text = when (range) {
                                ForecastRangeUi.H3 -> "3h"
                                ForecastRangeUi.H6 -> "6h"
                                ForecastRangeUi.H24 -> "24h"
                            }
                        )
                    }
                )
            }
        }

        ForecastSectionLabel(
            text = stringResource(id = R.string.section_forecast_layers),
            infoText = stringResource(id = R.string.forecast_info_layers_section)
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
            verticalArrangement = Arrangement.spacedBy(Spacing.xs)
        ) {
            LayerToggleChip(
                selected = state.layers.showTrend,
                onClick = { onLayerChange(state.layers.copy(showTrend = !state.layers.showTrend)) },
                label = stringResource(id = R.string.forecast_layer_trend),
                icon = Icons.Default.TrendingUp,
                palette = LayerChipPalette(
                    container = Color(0xFFE6EEFF),
                    onContainer = Color(0xFF113A86),
                    border = Color(0xFF2D5DAE)
                )
            )
            LayerToggleChip(
                selected = state.layers.showTherapy,
                onClick = { onLayerChange(state.layers.copy(showTherapy = !state.layers.showTherapy)) },
                label = stringResource(id = R.string.forecast_layer_therapy),
                icon = Icons.Default.MedicalServices,
                palette = LayerChipPalette(
                    container = Color(0xFFE6F8F0),
                    onContainer = Color(0xFF116D3D),
                    border = Color(0xFF2F8A57)
                )
            )
            LayerToggleChip(
                selected = state.layers.showUam,
                onClick = { onLayerChange(state.layers.copy(showUam = !state.layers.showUam)) },
                label = stringResource(id = R.string.forecast_layer_uam),
                icon = Icons.Default.Restaurant,
                palette = LayerChipPalette(
                    container = MaterialTheme.colorScheme.tertiaryContainer,
                    onContainer = MaterialTheme.colorScheme.onTertiaryContainer,
                    border = Color(0xFFE5B454)
                )
            )
            LayerToggleChip(
                selected = state.layers.showCi,
                onClick = { onLayerChange(state.layers.copy(showCi = !state.layers.showCi)) },
                label = stringResource(id = R.string.forecast_layer_ci),
                icon = Icons.Default.ShowChart,
                palette = LayerChipPalette(
                    container = Color(0xFFEAF3FF),
                    onContainer = Color(0xFF104D8C),
                    border = Color(0xFF4A82BF)
                )
            )
        }
    }
}

@Composable
private fun ForecastChartCard(state: ForecastUiState) {
    val allSeries = buildList {
        addAll(state.historyPoints)
        addAll(state.futurePath)
    }
    val allY = buildList {
        addAll(allSeries.map { it.value })
        addAll(state.futureCi.map { it.low })
        addAll(state.futureCi.map { it.high })
    }

    if (allSeries.size < 2 || allY.isEmpty()) {
        ForecastSectionCard {
            ForecastSectionLabel(
                text = stringResource(id = R.string.nav_forecast),
                infoText = stringResource(id = R.string.forecast_info_chart_section)
            )
            Text(
                text = stringResource(id = R.string.forecast_chart_empty),
                style = MaterialTheme.typography.bodyMedium
            )
        }
        return
    }

    val minTs = allSeries.minOf { it.ts }
    val maxTs = allSeries.maxOf { it.ts }
    val yMinRaw = allY.minOrNull() ?: 2.2
    val yMaxRaw = allY.maxOrNull() ?: 12.0
    val yPadding = max(0.4, (yMaxRaw - yMinRaw) * 0.12)
    val yMin = max(2.2, yMinRaw - yPadding)
    val yMax = min(22.0, yMaxRaw + yPadding)

    ForecastSectionCard {
        ForecastSectionLabel(
            text = stringResource(id = R.string.nav_forecast),
            infoText = stringResource(id = R.string.forecast_info_chart_section)
        )
        Text(
            text = stringResource(
                id = R.string.forecast_chart_range,
                UiFormatters.formatMmol(yMin, 2),
                UiFormatters.formatMmol(yMax, 2)
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        val outlineMajor = MaterialTheme.colorScheme.outline.copy(alpha = 0.18f)
        val outlineMinor = MaterialTheme.colorScheme.outline.copy(alpha = 0.14f)
        val ciColor = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.18f)
        val historyColor = MaterialTheme.colorScheme.primary
        val futureColor = MaterialTheme.colorScheme.secondary
        val currentMarkerColor = MaterialTheme.colorScheme.error
        val markerLineColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
        val nowValue = state.historyPoints.lastOrNull()?.value
        val pred60 = state.horizons.firstOrNull { it.horizonMinutes == 60 }?.pred
        val chartDescription = stringResource(
            id = R.string.forecast_chart_accessibility_values,
            UiFormatters.formatMmol(nowValue, 2),
            UiFormatters.formatMmol(pred60, 2)
        )

        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(240.dp)
                .semantics { contentDescription = chartDescription }
        ) {
            val width = size.width
            val height = size.height

            fun xOf(ts: Long): Float {
                if (maxTs <= minTs) return 0f
                return ((ts - minTs).toDouble() / (maxTs - minTs).toDouble() * width).toFloat()
            }

            fun yOf(v: Double): Float {
                if (yMax <= yMin) return height / 2f
                val ratio = (v - yMin) / (yMax - yMin)
                return (height - ratio * height).toFloat()
            }

            for (i in 0..4) {
                val y = height / 4f * i
                drawLine(
                    color = outlineMajor,
                    start = Offset(0f, y),
                    end = Offset(width, y),
                    strokeWidth = 1f
                )
            }
            for (i in 0..6) {
                val x = width / 6f * i
                drawLine(
                    color = outlineMinor,
                    start = Offset(x, 0f),
                    end = Offset(x, height),
                    strokeWidth = 1f
                )
            }

            val nowPoint = state.historyPoints.lastOrNull()
            val nowTs = nowPoint?.ts
            if (nowTs != null) {
                fun markerAt(ts: Long) {
                    if (ts < minTs || ts > maxTs) return
                    drawLine(
                        color = markerLineColor,
                        start = Offset(xOf(ts), 0f),
                        end = Offset(xOf(ts), height),
                        strokeWidth = 2f,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f), 0f)
                    )
                }
                markerAt(nowTs)
                markerAt(nowTs + 30 * 60_000L)
                markerAt(nowTs + 60 * 60_000L)
            }

            if (state.layers.showCi && state.futureCi.size >= 2) {
                val lowerPath = Path()
                val upperPath = Path()
                state.futureCi.forEachIndexed { idx, point ->
                    val x = xOf(point.ts)
                    val yl = yOf(point.low)
                    val yh = yOf(point.high)
                    if (idx == 0) {
                        lowerPath.moveTo(x, yl)
                        upperPath.moveTo(x, yh)
                    } else {
                        lowerPath.lineTo(x, yl)
                        upperPath.lineTo(x, yh)
                    }
                }
                val area = Path().apply {
                    addPath(upperPath)
                    val reversed = state.futureCi.asReversed()
                    reversed.forEach { point ->
                        lineTo(xOf(point.ts), yOf(point.low))
                    }
                    close()
                }
                drawPath(path = area, color = ciColor)
            }

            drawPolyline(
                points = state.historyPoints,
                xOf = ::xOf,
                yOf = ::yOf,
                color = historyColor,
                width = 4f
            )
            drawPolyline(
                points = state.futurePath,
                xOf = ::xOf,
                yOf = ::yOf,
                color = futureColor,
                width = 4f
            )

            if (nowPoint != null) {
                drawCircle(
                    color = currentMarkerColor,
                    radius = 6f,
                    center = Offset(xOf(nowPoint.ts), yOf(nowPoint.value))
                )
            }

            drawComponentLine(
                enabled = state.layers.showTrend,
                component60 = state.decomposition.trend60,
                base = nowPoint?.value,
                nowTs = nowPoint?.ts,
                endTs = state.futurePath.lastOrNull()?.ts,
                xOf = ::xOf,
                yOf = ::yOf,
                color = Color(0xFF1565C0)
            )
            drawComponentLine(
                enabled = state.layers.showTherapy,
                component60 = state.decomposition.therapy60,
                base = nowPoint?.value,
                nowTs = nowPoint?.ts,
                endTs = state.futurePath.lastOrNull()?.ts,
                xOf = ::xOf,
                yOf = ::yOf,
                color = Color(0xFF2E7D32)
            )
            drawComponentLine(
                enabled = state.layers.showUam,
                component60 = state.decomposition.uam60,
                base = nowPoint?.value,
                nowTs = nowPoint?.ts,
                endTs = state.futurePath.lastOrNull()?.ts,
                xOf = ::xOf,
                yOf = ::yOf,
                color = Color(0xFFEF6C00)
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(text = stringResource(id = R.string.forecast_axis_past), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(text = stringResource(id = R.string.forecast_axis_now), style = MaterialTheme.typography.bodySmall)
            Text(text = stringResource(id = R.string.forecast_axis_30m), style = MaterialTheme.typography.bodySmall)
            Text(text = stringResource(id = R.string.forecast_axis_60m), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun ForecastHorizonsCard(horizons: List<HorizonPredictionUi>) {
    val sorted = horizons.sortedBy { it.horizonMinutes }
    ForecastSectionCard {
        ForecastSectionLabel(
            text = stringResource(id = R.string.section_forecast_horizons),
            infoText = stringResource(id = R.string.forecast_info_horizons_section)
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Spacing.xs)
        ) {
            sorted.forEach { horizon ->
                Surface(
                    modifier = Modifier.weight(1f),
                    shape = ForecastInfoShape,
                    color = MaterialTheme.colorScheme.primaryContainer,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Text(
                            text = "${horizon.horizonMinutes}m",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = UiFormatters.formatMmol(horizon.pred, 2),
                            style = MaterialTheme.typography.titleLarge.copy(letterSpacing = (-0.4).sp)
                        )
                        Text(
                            text = stringResource(
                                id = R.string.overview_ci_template,
                                UiFormatters.formatMmol(horizon.ciLow, 2),
                                UiFormatters.formatMmol(horizon.ciHigh, 2)
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawPolyline(
    points: List<ChartPointUi>,
    xOf: (Long) -> Float,
    yOf: (Double) -> Float,
    color: Color,
    width: Float
) {
    if (points.size < 2) return
    val path = Path()
    points.forEachIndexed { idx, point ->
        val x = xOf(point.ts)
        val y = yOf(point.value)
        if (idx == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    drawPath(
        path = path,
        color = color,
        style = Stroke(width = width, cap = StrokeCap.Round)
    )
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawComponentLine(
    enabled: Boolean,
    component60: Double?,
    base: Double?,
    nowTs: Long?,
    endTs: Long?,
    xOf: (Long) -> Float,
    yOf: (Double) -> Float,
    color: Color
) {
    if (!enabled || component60 == null || base == null || nowTs == null || endTs == null) return
    val componentEnd = base + component60
    drawLine(
        color = color.copy(alpha = 0.65f),
        start = Offset(xOf(nowTs), yOf(base)),
        end = Offset(xOf(endTs), yOf(componentEnd)),
        strokeWidth = 2f,
        pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f), 0f)
    )
}

@Composable
private fun DecompositionCard(
    state: ForecastUiState,
    showProMetrics: Boolean
) {
    val trend60 = state.decomposition.trend60
    val therapy60 = state.decomposition.therapy60
    val uam60 = state.decomposition.uam60
    val netChange = listOfNotNull(trend60, therapy60, uam60).takeIf { it.isNotEmpty() }?.sum()

    ForecastSectionCard {
        ForecastSectionLabel(
            text = stringResource(id = R.string.section_forecast_decomposition),
            infoText = stringResource(id = R.string.forecast_info_decomposition_section)
        )
        DecompositionRow(
            label = stringResource(id = R.string.forecast_decomp_trend60),
            value = trend60,
            color = Color(0xFF2D5DAE)
        )
        DecompositionRow(
            label = stringResource(id = R.string.forecast_decomp_therapy60),
            value = therapy60,
            color = Color(0xFF2F8A57)
        )
        DecompositionRow(
            label = stringResource(id = R.string.forecast_decomp_uam60),
            value = uam60,
            color = Color(0xFFE58A00)
        )
        Surface(
            shape = ForecastInfoShape,
            color = MaterialTheme.colorScheme.surfaceVariant,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 9.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(id = R.string.forecast_decomp_net_change),
                    style = MaterialTheme.typography.titleSmall
                )
                Text(
                    text = "${UiFormatters.formatSignedDelta(netChange, 2)} ${stringResource(id = R.string.unit_mmol_l)}",
                    style = MaterialTheme.typography.titleMedium.copy(letterSpacing = (-0.2).sp),
                    color = if ((netChange ?: 0.0) >= 0.0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary
                )
            }
        }
        if (showProMetrics) {
            DebugRow(key = stringResource(id = R.string.forecast_decomp_residual_roc0), value = UiFormatters.formatMmol(state.decomposition.residualRoc0, 2))
            DebugRow(key = stringResource(id = R.string.forecast_decomp_sigma_e), value = UiFormatters.formatMmol(state.decomposition.sigmaE, 2))
            DebugRow(key = stringResource(id = R.string.forecast_decomp_kf_sigma), value = UiFormatters.formatMmol(state.decomposition.kfSigmaG, 2))
        }
    }
}

@Composable
private fun LayerToggleChip(
    selected: Boolean,
    onClick: () -> Unit,
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    palette: LayerChipPalette
) {
    val animatedContainer = animateColorAsState(
        targetValue = if (selected) palette.container else MaterialTheme.colorScheme.surfaceVariant,
        animationSpec = tween(durationMillis = 220),
        label = "layerChipContainer"
    )
    val animatedText = animateColorAsState(
        targetValue = if (selected) palette.onContainer else MaterialTheme.colorScheme.onSurfaceVariant,
        animationSpec = tween(durationMillis = 220),
        label = "layerChipText"
    )
    val animatedBorder = animateColorAsState(
        targetValue = if (selected) palette.border else MaterialTheme.colorScheme.outlineVariant,
        animationSpec = tween(durationMillis = 220),
        label = "layerChipBorder"
    )

    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        leadingIcon = {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = animatedText.value
            )
        },
        border = FilterChipDefaults.filterChipBorder(
            enabled = true,
            selected = selected,
            borderColor = animatedBorder.value,
            selectedBorderColor = animatedBorder.value
        ),
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = animatedContainer.value,
            containerColor = animatedContainer.value,
            selectedLabelColor = animatedText.value,
            labelColor = animatedText.value,
            selectedLeadingIconColor = animatedText.value,
            iconColor = animatedText.value
        )
    )
}

@Composable
private fun DecompositionRow(
    label: String,
    value: Double?,
    color: Color
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.xs)
        ) {
            Surface(
                shape = RoundedCornerShape(999.dp),
                color = color,
                modifier = Modifier
                    .width(10.dp)
                    .height(10.dp)
            ) {}
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium
            )
        }
        Text(
            text = "${UiFormatters.formatSignedDelta(value, 2)} ${stringResource(id = R.string.unit_mmol_l)}",
            style = MaterialTheme.typography.bodyMedium.copy(letterSpacing = (-0.1).sp),
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
private fun QualityCard(lines: List<String>) {
    ForecastSectionCard {
        ForecastSectionLabel(text = stringResource(id = R.string.section_forecast_quality))
        lines.forEach { line ->
            Surface(
                shape = ForecastInfoShape,
                color = MaterialTheme.colorScheme.surfaceVariant,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                Text(
                    text = line,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(Spacing.sm)
                )
            }
        }
    }
}

@Composable
private fun ForecastSectionCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = ForecastSectionShape,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = AppElevation.level1)
    ) {
        Column(
            modifier = Modifier.padding(Spacing.md),
            verticalArrangement = Arrangement.spacedBy(Spacing.xs),
            content = content
        )
    }
}

@Composable
private fun ForecastSectionLabel(
    text: String,
    infoText: String? = null
) {
    var showInfo by rememberSaveable(text, infoText) { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = text.uppercase(),
            style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.7.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (!infoText.isNullOrBlank()) {
            IconButton(onClick = { showInfo = true }) {
                Icon(
                    imageVector = Icons.Default.Info,
                    contentDescription = stringResource(id = R.string.settings_info_button_cd, text),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
    if (showInfo && !infoText.isNullOrBlank()) {
        AlertDialog(
            onDismissRequest = { showInfo = false },
            title = { Text(text = text) },
            text = { Text(text = infoText) },
            confirmButton = {
                TextButton(onClick = { showInfo = false }) {
                    Text(text = stringResource(id = R.string.action_close))
                }
            }
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun ForecastScreenPreview() {
    AapsCopilotTheme {
        ForecastScreen(
            state = ForecastUiState(
                loadState = ScreenLoadState.READY,
                isStale = false,
                isProMode = true,
                range = ForecastRangeUi.H3,
                layers = ForecastLayerState(),
                horizons = listOf(
                    HorizonPredictionUi(5, 6.1, 5.8, 6.4),
                    HorizonPredictionUi(30, 6.6, 6.0, 7.2),
                    HorizonPredictionUi(60, 7.0, 6.2, 7.8)
                ),
                historyPoints = listOf(
                    ChartPointUi(0L, 6.0),
                    ChartPointUi(300_000L, 6.1),
                    ChartPointUi(600_000L, 6.2),
                    ChartPointUi(900_000L, 6.3)
                ),
                futurePath = listOf(
                    ChartPointUi(900_000L, 6.3),
                    ChartPointUi(1_200_000L, 6.4),
                    ChartPointUi(2_700_000L, 6.7),
                    ChartPointUi(4_500_000L, 7.0)
                ),
                futureCi = listOf(
                    ChartCiPointUi(900_000L, 6.3, 6.3),
                    ChartCiPointUi(1_200_000L, 6.1, 6.7),
                    ChartCiPointUi(2_700_000L, 6.0, 7.5),
                    ChartCiPointUi(4_500_000L, 6.2, 7.8)
                ),
                decomposition = ForecastDecompositionUi(
                    trend60 = 0.4,
                    therapy60 = 0.2,
                    uam60 = 0.1,
                    residualRoc0 = 0.08,
                    sigmaE = 0.11,
                    kfSigmaG = 0.09
                ),
                qualityLines = listOf(
                    "5m MAE 0.22 | RMSE 0.35 | MARD 4.8% | n=390",
                    "60m MAE 0.55 | RMSE 0.82 | MARD 8.7% | n=387"
                )
            ),
            onSelectRange = {},
            onLayerChange = {}
        )
    }
}
