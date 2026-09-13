package io.aaps.copilot.ui.foundation.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.style.TextOverflow
import io.aaps.copilot.R
import io.aaps.copilot.ui.EventIconResources
import io.aaps.copilot.ui.foundation.screens.ChartEventMarkerUi
import io.aaps.copilot.ui.foundation.screens.clusterEventMarkers
import io.aaps.copilot.ui.foundation.screens.clusterSelectionMarker
import io.aaps.copilot.ui.foundation.screens.eventTypeLabelRes

@Composable
internal fun EventTimelineRail(
    markers: List<ChartEventMarkerUi>,
    rangeStartTs: Long,
    rangeEndTs: Long,
    onEventSelected: (ChartEventMarkerUi) -> Unit,
    modifier: Modifier = Modifier
) {
    if (markers.isEmpty() || rangeEndTs <= rangeStartTs) return
    val clusters = remember(markers) { clusterEventMarkers(markers) }
    val geometry = eventMarkerGeometry()
    val railColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.28f)
    val stemColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.58f)
    BoxWithConstraints(modifier.height(EVENT_RAIL_HEIGHT)) {
        val availableWidth = maxWidth - EVENT_MARKER_SIZE
        fun markerOffset(ts: Long) = availableWidth * timelineRatio(ts, rangeStartTs, rangeEndTs)

        Canvas(Modifier.fillMaxSize()) {
            clusters.flatten().forEach { marker ->
                val endTs = marker.endTs ?: marker.startTs
                if (endTs > marker.startTs) {
                    val startX = size.width * timelineRatio(marker.startTs, rangeStartTs, rangeEndTs)
                    val endX = size.width * timelineRatio(endTs, rangeStartTs, rangeEndTs)
                    drawLine(
                        color = railColor,
                        start = androidx.compose.ui.geometry.Offset(startX, geometry.durationRailYDp.dp.toPx()),
                        end = androidx.compose.ui.geometry.Offset(endX, geometry.durationRailYDp.dp.toPx()),
                        strokeWidth = 4.dp.toPx()
                    )
                }
            }
            val availableWidthPx = size.width - geometry.touchTargetSizeDp.dp.toPx()
            pointStemMarkers(clusters).forEach { marker ->
                val centerX = availableWidthPx * timelineRatio(marker.startTs, rangeStartTs, rangeEndTs) +
                    geometry.touchTargetSizeDp.dp.toPx() / 2f
                drawLine(
                    color = stemColor,
                    start = androidx.compose.ui.geometry.Offset(centerX, geometry.pointStemStartDp.dp.toPx()),
                    end = androidx.compose.ui.geometry.Offset(centerX, geometry.pointStemEndDp.dp.toPx()),
                    strokeWidth = 1.5.dp.toPx()
                )
            }
        }
        clusters.forEach { cluster ->
            val marker = clusterSelectionMarker(cluster)
            val extraCount = marker.overflowCount
            val typeLabel = stringResource(eventTypeLabelRes(marker.type))
            val description = stringResource(R.string.events_marker_description, typeLabel)
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .offset(x = markerOffset(marker.startTs))
                    .size(EVENT_MARKER_SIZE)
            ) {
                IconButton(
                    onClick = { onEventSelected(marker) },
                    modifier = Modifier
                        .size(EVENT_MARKER_SIZE)
                        .testTag("eventMarker:${marker.id}")
                ) {
                    Icon(
                        painter = painterResource(EventIconResources.byType.getValue(marker.type).resourceId),
                        contentDescription = description,
                        tint = Color.Unspecified,
                        modifier = Modifier
                            .size(EVENT_MARKER_ICON_SIZE)
                            .alpha(if (marker.active) 1f else 0.72f)
                            .testTag("eventMarkerIcon:${marker.id}")
                    )
                }
                if (extraCount > 0) {
                    val moreDescription = stringResource(R.string.events_marker_more_description, extraCount)
                    Text(
                        text = "+$extraCount",
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 8.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Clip,
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .semantics { contentDescription = moreDescription }
                    )
                }
            }
        }
    }
}

internal fun timelineRatio(ts: Long, rangeStartTs: Long, rangeEndTs: Long): Float {
    if (rangeEndTs <= rangeStartTs) return 0f
    val duration = (rangeEndTs.toULong() - rangeStartTs.toULong()).toDouble()
    val boundedTs = ts.coerceIn(rangeStartTs, rangeEndTs)
    val offset = (boundedTs.toULong() - rangeStartTs.toULong()).toDouble()
    return (offset / duration).toFloat().coerceIn(0f, 1f)
}

internal data class EventMarkerGeometry(
    val touchTargetSizeDp: Float,
    val iconSizeDp: Float,
    val iconBottomDp: Float,
    val pointStemStartDp: Float,
    val pointStemEndDp: Float,
    val durationRailYDp: Float
)

internal fun eventMarkerGeometry(): EventMarkerGeometry = EventMarkerGeometry(
    touchTargetSizeDp = 36f,
    iconSizeDp = 16f,
    iconBottomDp = 26f,
    pointStemStartDp = 28f,
    pointStemEndDp = 35f,
    durationRailYDp = 37f
)

@Suppress("UNUSED_PARAMETER")
internal fun eventMarkerTouchTargetSizeDp(overflowCount: Int): Float = eventMarkerGeometry().touchTargetSizeDp

internal fun pointStemMarkers(
    clusters: List<List<ChartEventMarkerUi>>
): List<ChartEventMarkerUi> = clusters.map(::clusterSelectionMarker).filter(ChartEventMarkerUi::hasPointEventStem)

internal fun ChartEventMarkerUi.hasPointEventStem(): Boolean = containsPointEvent

private val EVENT_RAIL_HEIGHT = 40.dp
private val EVENT_MARKER_SIZE = 36.dp
private val EVENT_MARKER_ICON_SIZE = 16.dp
