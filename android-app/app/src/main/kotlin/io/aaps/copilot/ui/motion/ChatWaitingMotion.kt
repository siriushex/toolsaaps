package io.aaps.copilot.ui.motion

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.progressSemantics
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.aaps.copilot.R

internal object ChatWaitingMotion {
    const val durationMs = 7_200L
    fun pose(elapsedMs: Long): OwlPose {
        require(elapsedMs >= 0)
        if (elapsedMs >= durationMs) return OwlPose()
        val cycle = (elapsedMs % 2_400).toFloat()
        val energy = 1f - .15f * (elapsedMs / 2_400)
        fun beat(start: Float, end: Float): Float =
            NativeMotion.accent(((cycle - start) / (end - start)).coerceIn(0f, 1f))
        return OwlPose(
            gazeX = 9f * energy * beat(120f, 1_640f),
            headAngle = -2.4f * energy * beat(360f, 1_920f),
            headY = .7f * beat(480f, 2_040f),
            blink = beat(1_920f, 2_160f), active = true
        )
    }
    fun dotAlpha(index: Int, elapsedMs: Long): Float {
        require(index in 0..2 && elapsedMs >= 0)
        if (elapsedMs >= durationMs) return .45f
        val phase = ((elapsedMs % 2_400) - index * 240).toFloat() / 960f
        return .28f + .72f * NativeMotion.accent(phase.coerceIn(0f, 1f))
    }
}

/** A finite acknowledgement of real work, never a timer-based progress estimate. */
@Composable
internal fun AiChatWaitingIndicator(requestKey: String, modifier: Modifier = Modifier, frameClock: State<Long>? = null,
    showOwl: Boolean = true) {
    val label = stringResource(R.string.ai_chat_waiting)
    val enabled = LocalCopilotMotionEnabled.current
    val clock = frameClock ?: rememberFiniteMotionClock(ChatWaitingMotion.durationMs, requestKey, true)
    val color = MaterialTheme.colorScheme.primary
    Row(modifier.fillMaxWidth().heightIn(min = 72.dp).testTag("ai_chat_waiting")
        .progressSemantics().semantics(mergeDescendants = true) { contentDescription = label }
        .background(MaterialTheme.colorScheme.surfaceContainerLow, RoundedCornerShape(8.dp))
        .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        if (showOwl) CopilotOwl(size = 48.dp,
            poseOverride = { if (enabled) ChatWaitingMotion.pose(clock.value) else OwlPose() })
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(label, modifier = Modifier.clearAndSetSemantics {}, style = MaterialTheme.typography.bodyMedium)
            Canvas(Modifier.size(width = 36.dp, height = 8.dp)) {
                repeat(3) { index ->
                    val alpha = if (enabled) ChatWaitingMotion.dotAlpha(index, clock.value) else .45f
                    drawCircle(color.copy(alpha = alpha), radius = 2.5.dp.toPx(),
                        center = Offset((4 + index * 12).dp.toPx(), size.height / 2f))
                }
            }
        }
    }
}
