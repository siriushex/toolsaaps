package io.aaps.copilot.ui.foundation.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.aaps.copilot.ui.foundation.screens.ChartPointUi

internal data class ClinicalForecastMarker(
    val minutes: Int,
    val point: ChartPointUi,
    val description: String
)

internal data class ForecastMarkerPlacement(
    val marker: ClinicalForecastMarker,
    val x: Float,
    val y: Float,
    val centerX: Float,
    val top: Float
)

internal fun forecastMarkerPlacements(
    markers: List<ClinicalForecastMarker>, width: Float, height: Float,
    minTs: Long, maxTs: Long, bounds: ClinicalChartBounds
): List<ForecastMarkerPlacement> {
    if (width < 144f || height < 132f || maxTs <= minTs || bounds.max <= bounds.min) return emptyList()
    val visible = markers.filter { it.minutes in listOf(5, 30, 60) &&
        it.point.ts in minTs..maxTs && it.point.value.isFinite() && it.point.value in bounds.min..bounds.max }
        .distinctBy { it.minutes }.sortedBy { it.point.ts }.take(3)
    var previousCenter = -24f
    return visible.mapIndexed { index, marker ->
        val ratio = (marker.point.ts.toDouble() - minTs.toDouble()) / (maxTs.toDouble() - minTs.toDouble())
        val x = 34f + ratio.toFloat() * (width - 74f)
        val y = height - 20f - ((marker.point.value - bounds.min) / (bounds.max - bounds.min)).toFloat() * (height - 64f)
        // Fan close points apart without shrinking or overlapping their 48dp hit areas.
        val center = x.coerceIn(maxOf(24f, previousCenter + 48f), width - 24f - (visible.lastIndex - index) * 48f)
        previousCenter = center
        val top = if (y >= 96f) y - 52f else y + 8f
        ForecastMarkerPlacement(marker, x, y, center, top.coerceIn(44f, height - 68f))
    }
}

@Composable
internal fun ClinicalForecastMarkers(
    markers: List<ClinicalForecastMarker>, minTs: Long, maxTs: Long,
    bounds: ClinicalChartBounds, onClick: (Int) -> Unit
) {
    val color = clinicalChartColors(MaterialTheme.colorScheme.surface).future
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val placements = forecastMarkerPlacements(markers, maxWidth.value, maxHeight.value, minTs, maxTs, bounds)
        Canvas(Modifier.fillMaxSize()) {
            placements.forEach { p ->
                val leaderY = p.top + if (p.y >= p.top + 48f) 32f else 16f
                drawLine(color.copy(alpha = 0.4f), Offset(p.centerX.dp.toPx(), leaderY.dp.toPx()),
                    Offset(p.x.dp.toPx(), p.y.dp.toPx()), strokeWidth = 1.dp.toPx())
                drawCircle(color, 3.dp.toPx(), Offset(p.x.dp.toPx(), p.y.dp.toPx()))
            }
        }
        placements.forEach { p ->
            Box(Modifier.offset((p.centerX - 24f).dp, p.top.dp).size(48.dp)
                .testTag("forecastMarker${p.marker.minutes}")
                .semantics(mergeDescendants = true) { contentDescription = p.marker.description }
                .clickable(role = Role.Button, onClick = { onClick(p.marker.minutes) }),
                contentAlignment = Alignment.Center) {
                Text(p.marker.minutes.toString(), color = color, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}
