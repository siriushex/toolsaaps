package io.aaps.copilot.ui.motion

import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import io.aaps.copilot.R

/** Explicit UI events only; color and clock samples never become visits. */
internal data class OwlHeaderMotionState(
    val routeKey: String,
    val menuExpanded: Boolean,
    val waiting: Boolean,
    val gesture: OwlGesture,
    val generation: Long,
    val animate: Boolean
) {
    val durationMs: Long get() = if (gesture == OwlGesture.WAITING) gesture.durationMs
        else minOf(720L, gesture.durationMs)

    fun next(routeKey: String, visitGesture: OwlGesture, menuExpanded: Boolean,
        waiting: Boolean, motionAllowed: Boolean): OwlHeaderMotionState {
        if (routeKey.isEmpty()) return if (motionAllowed) this else copy(animate = false)
        val nextGesture = when {
            menuExpanded && !this.menuExpanded -> OwlGesture.MENU
            waiting && (!this.waiting || routeKey != this.routeKey) -> OwlGesture.WAITING
            routeKey != this.routeKey -> visitGesture
            !waiting && this.waiting && gesture == OwlGesture.WAITING -> OwlGesture.STILL
            else -> null
        }
        return copy(routeKey = routeKey, menuExpanded = menuExpanded, waiting = waiting,
            gesture = nextGesture ?: gesture,
            generation = if (nextGesture == null) generation else generation + 1,
            animate = motionAllowed && (if (nextGesture == null) animate else true))
    }

    companion object {
        fun start(routeKey: String, visitGesture: OwlGesture, menuExpanded: Boolean,
            waiting: Boolean, motionAllowed: Boolean) = OwlHeaderMotionState(
            routeKey, menuExpanded, waiting,
            if (menuExpanded) OwlGesture.MENU else if (waiting) OwlGesture.WAITING else visitGesture,
            0, motionAllowed
        )
    }
}

private val headerMotionSaver = listSaver<OwlHeaderMotionState, Any>(
    save = { listOf(it.routeKey, it.menuExpanded, it.waiting, it.gesture.name, it.generation, it.animate) },
    restore = { OwlHeaderMotionState(it[0] as String, it[1] as Boolean, it[2] as Boolean,
        OwlGesture.valueOf(it[3] as String), it[4] as Long, it[5] as Boolean) }
)

@Composable
@OptIn(ExperimentalMaterial3Api::class)
internal fun OwlMenuButton(
    band: OwlColorBand,
    navigationKey: String,
    visitGesture: OwlGesture,
    expanded: Boolean,
    waiting: Boolean,
    onClick: () -> Unit,
    label: String? = null
) {
    val motionAllowed = LocalCopilotMotionEnabled.current
    var motion by rememberSaveable(stateSaver = headerMotionSaver) {
        mutableStateOf(OwlHeaderMotionState.start(navigationKey, visitGesture, expanded, waiting, motionAllowed))
    }
    val focus = remember { FocusRequester() }
    LaunchedEffect(navigationKey, visitGesture, expanded, waiting, motionAllowed) {
        val closing = motion.menuExpanded && !expanded
        motion = motion.next(navigationKey, visitGesture, expanded, waiting, motionAllowed)
        if (closing) focus.requestFocus()
    }
    val actionLabel = label ?: stringResource(if (expanded) R.string.menu_close else R.string.menu_open)
    val disclosure = stringResource(if (expanded) R.string.menu_state_expanded else R.string.menu_state_collapsed)
    val state = if (waiting) "$disclosure. ${stringResource(R.string.owl_ai_waiting)}" else disclosure
    TooltipBox(positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
        tooltip = { PlainTooltip { Text(actionLabel) } }, state = rememberTooltipState()) {
        NativeIconButton(onClick, Modifier.size(48.dp).focusRequester(focus)
            .testTag("owlMenuAction").semantics {
                contentDescription = actionLabel
                stateDescription = state
                if (waiting) progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate
            }) {
            CopilotOwl(size = 38.dp, band = band, gesture = motion.gesture,
                motionKey = "header-${motion.generation}", animate = motion.animate,
                motionDurationMs = motion.durationMs,
                modifier = Modifier.clearAndSetSemantics { })
        }
    }
}
