package io.aaps.copilot.ui.motion

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import androidx.lifecycle.repeatOnLifecycle
import kotlin.coroutines.coroutineContext

// Urgency and motion settings change at runtime; invalidate their readers directly.
internal val LocalCopilotMotionEnabled = compositionLocalOf { true }
private val stationaryClock = object : State<Long> { override val value = 0L }

/** One observer per root, not one settings/battery listener per glyph. */
@Composable
fun ProvideCopilotMotion(enabled: Boolean, content: @Composable () -> Unit) {
    val context = LocalContext.current.applicationContext
    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    fun systemAllowsMotion(): Boolean =
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f &&
            context.getSystemService(PowerManager::class.java)?.isPowerSaveMode != true
    var systemEnabled by remember { mutableStateOf(systemAllowsMotion()) }
    DisposableEffect(context) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) { systemEnabled = systemAllowsMotion() }
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) { systemEnabled = systemAllowsMotion() }
        }
        context.contentResolver.registerContentObserver(
            Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), false, observer)
        ContextCompat.registerReceiver(context, receiver, IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED)
        onDispose {
            context.contentResolver.unregisterContentObserver(observer)
            context.unregisterReceiver(receiver)
        }
    }
    CompositionLocalProvider(
        LocalCopilotMotionEnabled provides (enabled && systemEnabled && lifecycleState.isAtLeast(Lifecycle.State.RESUMED)),
        content = content
    )
}

/** Frames invalidate drawing only. Resume never replays a consumed decorative gesture. */
@Composable
internal fun rememberFiniteMotionClock(durationMs: Long, key: String, requested: Boolean): State<Long> {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val enabled = requested && LocalCopilotMotionEnabled.current
    var consumed by rememberSaveable(key) { mutableStateOf(false) }
    val elapsed = remember(key) { mutableLongStateOf(0L) }
    LaunchedEffect(key, enabled) {
        elapsed.longValue = 0L
        if (!enabled || durationMs <= 0) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            if (consumed) return@repeatOnLifecycle
            val motionScale = coroutineContext[MotionDurationScale]
            if ((motionScale?.scaleFactor ?: 1f) <= 0f) return@repeatOnLifecycle
            consumed = true
            try {
                val start = withFrameNanos { it }
                do {
                    val now = withFrameNanos { it }
                    val scale = motionScale?.scaleFactor ?: 1f
                    if (scale <= 0f) { elapsed.longValue = 0L; return@repeatOnLifecycle }
                    val wallMs = (now - start) / 1_000_000L
                    elapsed.longValue = if (wallMs >= 8_000L) durationMs else (wallMs / scale).toLong().coerceAtMost(durationMs)
                } while (elapsed.longValue < durationMs)
            } finally {
                if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) elapsed.longValue = 0L
            }
        }
    }
    // Rendering must not wait another composition frame for effect cancellation.
    return if (enabled) elapsed else stationaryClock
}
