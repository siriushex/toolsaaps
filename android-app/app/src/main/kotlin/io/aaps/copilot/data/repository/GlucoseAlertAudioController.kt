package io.aaps.copilot.data.repository

import android.content.Context
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import io.aaps.copilot.config.AppSettings
import io.aaps.copilot.domain.alerts.LocalAlarmCycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

class GlucoseAlertAudioController(
    context: Context,
    private val nowElapsedMs: () -> Long = SystemClock::elapsedRealtime
) {

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private var activePlayer: MediaPlayer? = null
    private var activeStopRunnable: Runnable? = null
    private var activeStartFailure: ((String) -> Unit)? = null
    private var activeFocusRequest: AudioFocusRequest? = null
    private var activeFocusListener: AudioManager.OnAudioFocusChangeListener? = null
    private var activeAlarmVolumeBeforeBoost: Int? = null
    private var activeFocusToken: Any? = null
    private var pendingLocalSession: LocalPlaybackSession? = null
    private var activeLocalSession: LocalPlaybackSession? = null
    private var lastLocalWindow: LocalAlarmPlaybackWindow? = null
    private var nextCriticalSecondary = false
    private val _playbackState = MutableStateFlow(GlucoseAlertAudioPlaybackState())

    val playbackState: StateFlow<GlucoseAlertAudioPlaybackState> = _playbackState.asStateFlow()

    suspend fun playForDecision(
        decision: GlucoseAlertDecision,
        settings: AppSettings
    ): GlucoseAlertAudioPlaybackResult {
        val slot = when (decision.notifyKind) {
            GlucoseAlertNotifyKind.WARNING_30,
            GlucoseAlertNotifyKind.SOFT_HIGH -> GlucoseAlertAudioSlot.SOFT
            GlucoseAlertNotifyKind.CRITICAL_5,
            GlucoseAlertNotifyKind.LOW_NOW -> {
                val selected = if (nextCriticalSecondary) {
                    GlucoseAlertAudioSlot.CRITICAL_SECONDARY
                } else {
                    GlucoseAlertAudioSlot.CRITICAL_PRIMARY
                }
                nextCriticalSecondary = !nextCriticalSecondary
                selected
            }
            GlucoseAlertNotifyKind.WATCH_60 -> {
                stop()
                return GlucoseAlertAudioPlaybackResult()
            }
            GlucoseAlertNotifyKind.NONE,
            GlucoseAlertNotifyKind.CLEAR -> {
                stop()
                return GlucoseAlertAudioPlaybackResult()
            }
        }
        return play(
            slot = slot,
            settings = settings,
            isPreview = false,
            ensureAudible = GlucoseAlertAudioVolumePolicy.shouldEnsureAudible(
                notifyKind = decision.notifyKind,
                currentGlucoseMmol = decision.currentGlucoseMmol,
                currentGlucoseFresh = decision.currentGlucoseFresh
            )
        )
    }

    suspend fun preview(
        slot: GlucoseAlertAudioSlot,
        settings: AppSettings
    ): GlucoseAlertAudioPlaybackResult = play(slot, settings, isPreview = true, ensureAudible = false)

    suspend fun playLocalAlarm(
        cycle: LocalAlarmCycle,
        stepIndex: Int,
        settings: AppSettings,
        admitted: (LocalAlarmCycle) -> Boolean
    ): GlucoseAlertAudioPlaybackResult = withContext(Dispatchers.Main.immediate) {
        val allowed = try {
            admitted(cycle)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            false
        }
        if (!allowed) return@withContext GlucoseAlertAudioPlaybackResult(failureReason = "local_alarm_denied")
        val slot = if (!cycle.level.strong) GlucoseAlertAudioSlot.SOFT
            else if (cycle.ordinal % 2L == 1L) GlucoseAlertAudioSlot.CRITICAL_PRIMARY
            else GlucoseAlertAudioSlot.CRITICAL_SECONDARY
        val spec = resolvePlaybackSpec(settings, slot)
        val window = LocalAlarmPlaybackWindow.create(cycle, stepIndex, spec.durationMs)
            ?: return@withContext GlucoseAlertAudioPlaybackResult(failureReason = "local_alarm_invalid_step")
        startPlaybackOnMain(slot, spec, isPreview = false, ensureAudible = false,
            localSession = LocalPlaybackSession(window, admitted))
    }

    fun stopLocalAlarm(cycle: LocalAlarmCycle) {
        val stop = Runnable {
            if (pendingLocalSession?.window?.cycle == cycle) pendingLocalSession = null
            if (activeLocalSession?.window?.cycle == cycle) stopActivePlayback("local_alarm_stopped")
        }
        if (Looper.myLooper() == Looper.getMainLooper()) stop.run() else mainHandler.post(stop)
    }

    fun stopPreview() {
        mainHandler.post {
            if (_playbackState.value.isPreview) {
                stopActivePlayback()
            }
        }
    }

    fun stop() {
        mainHandler.post { stopActivePlayback() }
    }

    private suspend fun play(
        slot: GlucoseAlertAudioSlot,
        settings: AppSettings,
        isPreview: Boolean,
        ensureAudible: Boolean
    ): GlucoseAlertAudioPlaybackResult {
        val spec = resolvePlaybackSpec(settings, slot)
        return withContext(Dispatchers.Main.immediate) {
            startPlaybackOnMain(slot, spec, isPreview, ensureAudible)
        }
    }

    private suspend fun startPlaybackOnMain(
        slot: GlucoseAlertAudioSlot,
        spec: GlucoseAlertAudioClipSpec,
        isPreview: Boolean,
        ensureAudible: Boolean,
        localSession: LocalPlaybackSession? = null
    ): GlucoseAlertAudioPlaybackResult = try {
        suspendCancellableCoroutine { continuation ->
            var startEnqueued = false
            var playbackStarted = false
            var playedDurationMs = spec.durationMs
            fun result(played: Boolean, failureReason: String? = null) = GlucoseAlertAudioPlaybackResult(
                played = played,
                startEnqueued = startEnqueued,
                failureReason = failureReason,
                clipLabel = spec.label,
                fallbackUsed = spec.fallbackUsed,
                startMs = spec.startMs,
                durationMs = playedDurationMs
            )
            fun resumeFailure(reason: String) {
                if (continuation.isActive) continuation.resume(result(played = false, failureReason = reason))
            }

            fun localAllowed(): Boolean {
                val session = localSession ?: return true
                if (pendingLocalSession !== session && activeLocalSession !== session) return false
                return try {
                    session.admitted(session.window.cycle) &&
                        (pendingLocalSession === session || activeLocalSession === session) &&
                        session.window.remainingStartMs(nowElapsedMs()) != null
                } catch (cancellation: CancellationException) {
                    continuation.cancel(cancellation)
                    false
                } catch (_: Throwable) {
                    false
                }
            }

            fun requireLocal(): Boolean {
                if (localAllowed()) return true
                if (activeLocalSession === localSession) stopActivePlayback("local_alarm_denied")
                if (pendingLocalSession === localSession) pendingLocalSession = null
                resumeFailure("local_alarm_denied")
                return false
            }

            try {
                if (localSession != null) {
                    val window = localSession.window
                    if (pendingLocalSession != null ||
                        (lastLocalWindow?.cycle == window.cycle && lastLocalWindow!!.stepIndex >= window.stepIndex)) {
                        resumeFailure("local_alarm_duplicate_or_busy")
                        return@suspendCancellableCoroutine
                    }
                    pendingLocalSession = localSession
                    if (!requireLocal()) return@suspendCancellableCoroutine
                }
                stopActivePlayback()
                if (!requireLocal()) return@suspendCancellableCoroutine
                activeLocalSession = localSession
                pendingLocalSession = null
                if (localSession != null) lastLocalWindow = localSession.window
                if (!retainAudioFocusOrCleanup(requestAudioFocus(slot, localSession != null), ::abandonAudioFocus)) {
                    stopActivePlayback("audio_focus_denied")
                    resumeFailure("audio_focus_denied")
                    return@suspendCancellableCoroutine
                }
                if (!requireLocal()) return@suspendCancellableCoroutine
                val player = createUnpreparedPlayer(spec, slot, localSession != null)
                if (player == null) {
                    stopActivePlayback("audio_player_create_failed")
                    resumeFailure("audio_player_create_failed")
                    return@suspendCancellableCoroutine
                }
                val volume = playbackVolume(slot)
                player.setVolume(volume, volume)
                activePlayer = player
                activeStartFailure = ::resumeFailure
                if (!requireLocal()) return@suspendCancellableCoroutine
                continuation.invokeOnCancellation {
                    mainHandler.post {
                        if (activePlayer === player) stopActivePlayback("audio_start_cancelled")
                    }
                }
                player.setOnCompletionListener {
                    if (activePlayer === it) {
                        stopActivePlayback()
                    }
                }
                player.setOnErrorListener { current, _, _ ->
                    if (activePlayer === current) stopActivePlayback("audio_player_error")
                    true
                }
                val startPlayback: () -> Unit = startPlayback@{
                    try {
                        if (localSession != null && playbackStarted) return@startPlayback
                        if (activePlayer !== player || !requireLocal()) return@startPlayback
                        val startedAt = if (localSession != null) nowElapsedMs() else null
                        if (localSession != null) {
                            val remaining = localSession.window.playDurationMs(requireNotNull(startedAt))
                            if (remaining == null) {
                                stopActivePlayback("local_alarm_expired")
                                return@startPlayback
                            }
                            playedDurationMs = remaining
                        }
                        playbackStarted = true
                        player.start()
                        if (!requireLocal()) return@startPlayback
                        val stopDelay = if (startedAt != null) {
                            val now = nowElapsedMs()
                            val end = startedAt + playedDurationMs
                            if (now < startedAt || now >= end) {
                                stopActivePlayback("local_alarm_expired")
                                return@startPlayback
                            }
                            end - now
                        } else playedDurationMs.toLong()
                        activeStopRunnable?.let(mainHandler::removeCallbacks)
                        activeStartFailure = null
                        if (ensureAudible) raiseAlarmVolumeIfNeeded()
                        _playbackState.value = GlucoseAlertAudioPlaybackState(
                            activeSlot = slot,
                            clipLabel = spec.label,
                            isPreview = isPreview
                        )
                        val stopRunnable = Runnable {
                            if (activePlayer === player) {
                                stopActivePlayback()
                            }
                        }
                        activeStopRunnable = stopRunnable
                        mainHandler.postDelayed(stopRunnable, stopDelay)
                        if (continuation.isActive) continuation.resume(result(played = true))
                    } catch (_: Throwable) {
                        stopActivePlayback("audio_start_failed")
                    }
                }
                player.setOnPreparedListener { prepared ->
                    if (activePlayer !== prepared) return@setOnPreparedListener
                    if (localSession != null && playbackStarted) return@setOnPreparedListener
                    if (!requireLocal()) return@setOnPreparedListener
                    if (spec.startMs > 0) {
                        prepared.setOnSeekCompleteListener {
                            if (activePlayer === it) startPlayback()
                        }
                        try {
                            prepared.seekTo(spec.startMs)
                        } catch (_: Throwable) {
                            startPlayback()
                        }
                    } else {
                        startPlayback()
                    }
                }
                if (localSession != null) {
                    if (!requireLocal()) return@suspendCancellableCoroutine
                    val remaining = localSession.window.remainingStartMs(nowElapsedMs())
                    if (remaining == null) {
                        stopActivePlayback("local_alarm_expired")
                        return@suspendCancellableCoroutine
                    }
                    val timeout = Runnable {
                        if (activePlayer === player) stopActivePlayback("local_alarm_prepare_timeout")
                    }
                    activeStopRunnable = timeout
                    mainHandler.postDelayed(timeout, remaining)
                }
                startEnqueued = true
                player.prepareAsync()
            } catch (_: Throwable) {
                if (pendingLocalSession === localSession) pendingLocalSession = null
                stopActivePlayback("audio_prepare_failed")
                resumeFailure("audio_prepare_failed")
            }
        }
    } catch (cancellation: CancellationException) {
        // This function runs on Dispatchers.Main. Cleanup therefore completes
        // before the bounded caller can release the alert coordinator mutex.
        if (localSession == null || activeLocalSession === localSession) stopActivePlayback("audio_start_cancelled")
        if (pendingLocalSession === localSession) pendingLocalSession = null
        throw cancellation
    }

    private fun resolvePlaybackSpec(
        settings: AppSettings,
        slot: GlucoseAlertAudioSlot
    ): GlucoseAlertAudioClipSpec {
        val spec = GlucoseAlertAudioProfiles.resolve(settings, slot)
        val uri = spec.sourceUri
        return if (uri != null && !canOpenUri(uri)) {
            GlucoseAlertAudioProfiles.recommendedSpec(slot, valid = false, fallbackUsed = true)
        } else {
            spec
        }
    }

    private fun canOpenUri(uri: Uri): Boolean {
        return runCatching {
            appContext.contentResolver.openAssetFileDescriptor(uri, "r")?.use { true } ?: false
        }.getOrDefault(false)
    }

    private fun createUnpreparedPlayer(
        spec: GlucoseAlertAudioClipSpec,
        slot: GlucoseAlertAudioSlot,
        alarmMode: Boolean = false
    ): MediaPlayer? {
        val uri = spec.sourceUri
        val attributes = audioAttributes(slot, alarmMode)
        val player = MediaPlayer()
        return try {
            player.apply {
                setAudioAttributes(attributes)
                if (uri != null) {
                    setDataSource(appContext, uri)
                } else {
                    val resId = requireNotNull(spec.resId)
                    appContext.resources.openRawResourceFd(resId).use { descriptor ->
                        setDataSource(
                            descriptor.fileDescriptor,
                            descriptor.startOffset,
                            descriptor.length
                        )
                    }
                }
            }
        } catch (_: Throwable) {
            runCatching { player.release() }
            null
        }
    }

    private fun stopActivePlayback(failureReason: String = "audio_stopped") {
        activeLocalSession = null
        val pendingFailure = activeStartFailure
        activeStartFailure = null
        _playbackState.value = GlucoseAlertAudioPlaybackState()
        activeStopRunnable?.let(mainHandler::removeCallbacks)
        activeStopRunnable = null
        val player = activePlayer
        activePlayer = null
        if (player != null) {
            try {
                if (player.isPlaying) {
                    player.stop()
                }
            } catch (_: Throwable) {
            }
            try {
                player.reset()
            } catch (_: Throwable) {
            }
            try {
                player.release()
            } catch (_: Throwable) {
            }
        }
        restoreAlarmVolume()
        abandonAudioFocus()
        pendingFailure?.invoke(failureReason)
    }

    private fun requestAudioFocus(slot: GlucoseAlertAudioSlot, alarmMode: Boolean = false): Boolean {
        val focusGain = when (slot) {
            GlucoseAlertAudioSlot.SOFT -> AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
            GlucoseAlertAudioSlot.CRITICAL_PRIMARY,
            GlucoseAlertAudioSlot.CRITICAL_SECONDARY -> AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
        }
        val token = Any()
        activeFocusToken = token
        val listener = AudioManager.OnAudioFocusChangeListener { change ->
            when (change) {
                AudioManager.AUDIOFOCUS_LOSS,
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> mainHandler.post {
                    if (activeFocusToken === token) stopActivePlayback("audio_focus_lost")
                }
            }
        }
        activeFocusListener = listener
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val request = AudioFocusRequest.Builder(focusGain)
                .setAudioAttributes(audioAttributes(slot, alarmMode))
                .setWillPauseWhenDucked(false)
                .setOnAudioFocusChangeListener(listener)
                .build()
            activeFocusRequest = request
            audioManager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(
                listener,
                if (alarmMode || slot.isCritical()) AudioManager.STREAM_ALARM else AudioManager.STREAM_MUSIC,
                focusGain
            ) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }
    }

    private fun audioAttributes(slot: GlucoseAlertAudioSlot, alarmMode: Boolean = false): AudioAttributes {
        return AudioAttributes.Builder()
            .setUsage(
                if (alarmMode || slot.isCritical()) {
                    AudioAttributes.USAGE_ALARM
                } else {
                    AudioAttributes.USAGE_NOTIFICATION_EVENT
                }
            )
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
    }

    private fun raiseAlarmVolumeIfNeeded() {
        if (audioManager.isVolumeFixed) return
        runCatching {
            val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM)
            val currentVolume = audioManager.getStreamVolume(AudioManager.STREAM_ALARM)
            val minimumVolume = GlucoseAlertAudioVolumePolicy.minimumAudibleVolume(maxVolume)
            if (currentVolume < minimumVolume) {
                audioManager.setStreamVolume(AudioManager.STREAM_ALARM, minimumVolume, 0)
                activeAlarmVolumeBeforeBoost = currentVolume
            }
        }
    }

    private fun restoreAlarmVolume() {
        val previousVolume = activeAlarmVolumeBeforeBoost ?: return
        activeAlarmVolumeBeforeBoost = null
        if (audioManager.isVolumeFixed) return
        runCatching {
            audioManager.setStreamVolume(AudioManager.STREAM_ALARM, previousVolume, 0)
        }
    }

    private fun abandonAudioFocus() {
        activeFocusToken = null
        val listener = activeFocusListener
        activeFocusListener = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            activeFocusRequest?.let { request ->
                audioManager.abandonAudioFocusRequest(request)
            }
            activeFocusRequest = null
        } else if (listener != null) {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(listener)
        }
    }

    private fun playbackVolume(slot: GlucoseAlertAudioSlot): Float = when (slot) {
        GlucoseAlertAudioSlot.SOFT -> 0.58f
        GlucoseAlertAudioSlot.CRITICAL_PRIMARY,
        GlucoseAlertAudioSlot.CRITICAL_SECONDARY -> 0.92f
    }

    private class LocalPlaybackSession(
        val window: LocalAlarmPlaybackWindow,
        val admitted: (LocalAlarmCycle) -> Boolean
    )
}

private fun GlucoseAlertAudioSlot.isCritical(): Boolean = this in setOf(
    GlucoseAlertAudioSlot.CRITICAL_PRIMARY,
    GlucoseAlertAudioSlot.CRITICAL_SECONDARY
)

internal inline fun retainAudioFocusOrCleanup(
    focusGranted: Boolean,
    cleanup: () -> Unit
): Boolean {
    if (!focusGranted) cleanup()
    return focusGranted
}

data class GlucoseAlertAudioPlaybackResult(
    val played: Boolean = false,
    val startEnqueued: Boolean = false,
    val failureReason: String? = null,
    val clipLabel: String? = null,
    val fallbackUsed: Boolean = false,
    val startMs: Int? = null,
    val durationMs: Int? = null
)

data class GlucoseAlertAudioPlaybackState(
    val activeSlot: GlucoseAlertAudioSlot? = null,
    val clipLabel: String? = null,
    val isPreview: Boolean = false
)
