package io.aaps.copilot.ui.foundation.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.foundation.text.BasicText
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import io.aaps.copilot.R
import io.aaps.copilot.ui.InsulinOnsetCountdown
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.util.Date
import java.util.Locale

@Composable
internal fun rememberOnsetNow(timer: InsulinOnsetCountdown?): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(timer, lifecycle) {
        if (timer == null) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            do {
                now = System.currentTimeMillis()
                if (!timer.visibleAt(now)) break
                delay(if (timer.remainingSeconds(now) == 0L) 60_000L else 1_000L)
            } while (true)
        }
    }
    return now
}

@Composable
internal fun InsulinOnsetTimer(timer: InsulinOnsetCountdown, nowMs: Long) {
    var details by remember { mutableStateOf(false) }
    val seconds = timer.remainingSeconds(nowMs)
    val label = if (seconds == 0L) stringResource(R.string.onset_timer_elapsed)
        else String.format(Locale.getDefault(), "≈ %d:%02d", seconds / 60, seconds % 60)
    val description = if (seconds == 0L) stringResource(R.string.onset_timer_finished)
        else stringResource(R.string.onset_timer_remaining, label)
    Column(Modifier.width(68.dp).heightIn(min = 48.dp).testTag("insulinOnsetTimer")
        .clickable { details = true }.semantics { contentDescription = description },
        verticalArrangement = Arrangement.Center) {
        TimerText(stringResource(R.string.onset_timer_model), MaterialTheme.typography.labelSmall, 11)
        TimerText(label, MaterialTheme.typography.labelMedium, 14)
    }
    if (details) AlertDialog(onDismissRequest = { details = false },
        title = { Text(description) },
        text = { Text(stringResource(R.string.onset_timer_details,
            String.format(Locale.getDefault(), "%.1f", timer.onsetMinutes),
            DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(timer.recordedAtMs))),
            modifier = Modifier.verticalScroll(rememberScrollState())) },
        confirmButton = { TextButton(onClick = { details = false }) { Text(stringResource(android.R.string.ok)) } })
}

@Composable
private fun TimerText(text: String, typography: TextStyle, maximumSp: Int) {
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val width = with(density) { 68.dp.roundToPx() }
    val style = typography.copy(color = LocalContentColor.current, letterSpacing = 0.sp)
    val font = remember(text, style, width, density, measurer) {
        (maximumSp downTo 7).firstOrNull { size ->
            !measurer.measure(text, style = style.copy(fontSize = size.sp),
                maxLines = 1, constraints = Constraints(maxWidth = width)).hasVisualOverflow
        } ?: 7
    }
    BasicText(text, style = style.copy(fontSize = font.sp), maxLines = 1)
}
