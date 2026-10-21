package io.aaps.copilot.ui.motion

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LunchDining
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

/** Three finite bites mark model-clock completion, not a clinical eating instruction. */
@Composable
internal fun OwlMealCompletion(band: OwlColorBand, episodeKey: String) {
    val allowed = LocalCopilotMotionEnabled.current && band !in
        setOf(OwlColorBand.WHITE, OwlColorBand.YELLOW, OwlColorBand.UNKNOWN)
    val clock = rememberFiniteMotionClock(2_400L, "onset-finished:$episodeKey", allowed)
    Box(Modifier.size(38.dp).testTag("owlModelTimeFinished")) {
        CopilotOwl(size = 38.dp, band = band, poseOverride = {
            val pose = mealPreparationPose(clock.value, allowed)
            OwlPose(headY = -pose.y, headAngle = pose.angle * .1f)
        })
        Icon(Icons.Default.LunchDining, null, tint = MaterialTheme.colorScheme.tertiary,
            modifier = Modifier.align(Alignment.BottomEnd).size(17.dp).graphicsLayer {
                val pose = mealPreparationPose(clock.value, allowed)
                translationX = -pose.x * density
                translationY = pose.y * density
                scaleX = 1f - pose.x / 45f
                scaleY = scaleX
            })
    }
}
