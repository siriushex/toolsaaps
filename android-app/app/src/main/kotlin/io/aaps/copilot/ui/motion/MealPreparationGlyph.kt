package io.aaps.copilot.ui.motion

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LunchDining
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

internal data class MealPreparationPose(val x: Float = 0f, val y: Float = 0f, val angle: Float = 0f)

internal fun mealPreparationPose(elapsedMs: Long, enabled: Boolean): MealPreparationPose {
    if (!enabled || elapsedMs <= 0L || elapsedMs >= 2_400L) return MealPreparationPose()
    val accent = NativeMotion.accent((elapsedMs % 800L) / 800f)
    return MealPreparationPose(8f * accent, -3f * accent, 22f * accent)
}

/** Three short scoops per preparation, then rest; no timer or clinical state follows these frames. */
@Composable
internal fun MealPreparationGlyph(episodeKey: String, active: Boolean, modifier: Modifier = Modifier) {
    val elapsed = rememberFiniteMotionClock(2_400L, "meal-preparation:$episodeKey", active)
    Box(modifier.size(36.dp).testTag("mealPreparationGlyph")) {
        Icon(Icons.Default.LunchDining, null, Modifier.size(25.dp).offset(11.dp, 8.dp))
        Icon(Icons.Default.Restaurant, null, Modifier.size(18.dp).graphicsLayer {
            val pose = mealPreparationPose(elapsed.value, active)
            translationX = pose.x * density
            translationY = pose.y * density
            rotationZ = pose.angle
        })
    }
}
