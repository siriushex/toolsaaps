package io.aaps.copilot.ui.motion

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection

/** Shared presentation tokens; no click, clinical value or request is timed by these. */
internal object NativeMotion {
    const val pressedIconScale = .94f
    const val pressStiffness = 1_100f
    const val releaseStiffness = 650f
    const val drawerStiffness = 600f
    const val scrimEnterMs = 180
    const val scrimExitMs = 120

    // A quick accent followed by a longer settle, with zero endpoint velocity.
    fun accent(progress: Float): Float {
        val p = progress.coerceIn(0f, 1f)
        return if (p <= .32f) FastOutSlowInEasing.transform(p / .32f)
        else 1f - FastOutSlowInEasing.transform((p - .32f) / .68f)
    }
}

private val restingIconScale = object : State<Float> { override val value = 1f }

@Composable
internal fun rememberNativePressScale(interactions: MutableInteractionSource, enabled: Boolean): State<Float> {
    val pressed by interactions.collectIsPressedAsState()
    val motionAllowed = enabled && LocalCopilotMotionEnabled.current
    val scale = remember { Animatable(1f) }
    LaunchedEffect(pressed, motionAllowed) {
        if (!motionAllowed) scale.snapTo(1f)
        else scale.animateTo(
            if (pressed) NativeMotion.pressedIconScale else 1f,
            spring(dampingRatio = 1f, stiffness = if (pressed) NativeMotion.pressStiffness else NativeMotion.releaseStiffness)
        )
    }
    return if (motionAllowed) scale.asState() else restingIconScale
}

/** Material owns ripple, semantics, cancellation and click dispatch. Only the artwork scales. */
@Composable
internal fun NativeIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable () -> Unit
) {
    val interactions = remember { MutableInteractionSource() }
    val scale = rememberNativePressScale(interactions, enabled)
    IconButton(onClick = onClick, modifier = modifier, enabled = enabled, interactionSource = interactions) {
        Box(Modifier.graphicsLayer { scaleX = scale.value; scaleY = scale.value }) { content() }
    }
}

/** One visibility lifetime: scrim and panel finish together, including interrupted exits. */
@Composable
internal fun NativeDrawerMotion(
    visible: Boolean,
    scrimColor: Color,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable (Modifier) -> Unit
) {
    val enabled = LocalCopilotMotionEnabled.current
    val start = if (LocalLayoutDirection.current == LayoutDirection.Ltr) -1 else 1
    // An in-flight exit must not retain a touch-blocking overlay after motion is disabled.
    if (!visible && !enabled) return
    AnimatedVisibility(visible, modifier, enter = EnterTransition.None, exit = ExitTransition.None) {
        Box(Modifier.fillMaxSize()) {
            val scrimMotion = if (enabled) Modifier.animateEnterExit(
                enter = fadeIn(tween(NativeMotion.scrimEnterMs)),
                exit = fadeOut(tween(NativeMotion.scrimExitMs))
            ) else Modifier
            val panelAlignment = Modifier.align(Alignment.CenterStart)
            val panelMotion = if (enabled) panelAlignment.animateEnterExit(
                enter = slideInHorizontally(
                    initialOffsetX = { start * it },
                    animationSpec = spring(dampingRatio = 1f, stiffness = NativeMotion.drawerStiffness)
                ),
                exit = slideOutHorizontally(
                    targetOffsetX = { start * it },
                    animationSpec = spring(dampingRatio = 1f, stiffness = NativeMotion.drawerStiffness)
                )
            ) else panelAlignment
            Box(Modifier.fillMaxSize().then(scrimMotion)
                .background(scrimColor)
                .clickable(onClick = onDismiss))
            content(panelMotion)
        }
    }
}
