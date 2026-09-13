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
import io.aaps.copilot.config.AppSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

class GlucoseAlertAudioController(
    context: Context
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
        ensureAudible: Boolean
    ): GlucoseAlertAudioPlaybackResult = try {
        suspendCancellableCoroutine { continuation ->
            var startEnqueued = false
            fun result(played: Boolean, failureReason: String? = null) = GlucoseAlertAudioPlaybackResult(
                played = played,
                startEnqueued = startEnqueued,
                failureReason = failureReason,
                clipLabel = spec.label,
                fallbackUsed = spec.fallbackUsed,
                startMs = spec.startMs,
                durationMs = spec.durationMs
            )
            fun resumeFailure(reason: String) {
                if (continuation.isActive) continuation.resume(result(played = false, failureReason = reason))
            }

            try {
                stopActivePlayback()
                if (!retainAudioFocusOrCleanup(requestAudioFocus(slot), ::abandonAudioFocus)) {
                    resumeFailure("audio_focus_denied")
                    return@suspendCancellableCoroutine
                }
                val player = createUnpreparedPlayer(spec, slot)
                if (player == null) {
                    abandonAudioFocus()
                    resumeFailure("audio_player_create_failed")
                    return@suspendCancellableCoroutine
                }
                val volume = playbackVolume(slot)
                player.setVolume(volume, volume)
                activePlayer = player
                activeStartFailure = ::resumeFailure
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
                val startPlayback: () -> Unit = {
                    try {
                        player.start()
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
                        mainHandler.postDelayed(stopRunnable, spec.durationMs.toLong())
                        if (continuation.isActive) continuation.resume(result(played = true))
                    } catch (_: Throwable) {
                        stopActivePlayback("audio_start_failed")
                    }
                }
                player.setOnPreparedListener { prepared ->
                    if (activePlayer !== prepared) return@setOnPreparedListener
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
                startEnqueued = true
                player.prepareAsync()
            } catch (_: Throwable) {
                stopActivePlayback("audio_prepare_failed")
                resumeFailure("audio_prepare_failed")
            }
        }
    } catch (cancellation: CancellationException) {
        // This function runs on Dispatchers.Main. Cleanup therefore completes
        // before the bounded caller can release the alert coordinator mutex.
        stopActivePlayback("audio_start_cancelled")
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
        slot: GlucoseAlertAudioSlot
    ): MediaPlayer? {
        val uri = spec.sourceUri
        val attributes = audioAttributes(slot)
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

    private fun requestAudioFocus(slot: GlucoseAlertAudioSlot): Boolean {
        val focusGain = when (slot) {
            GlucoseAlertAudioSlot.SOFT -> AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
            GlucoseAlertAudioSlot.CRITICAL_PRIMARY,
            GlucoseAlertAudioSlot.CRITICAL_SECONDARY -> AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
        }
        val listener = AudioManager.OnAudioFocusChangeListener { change ->
            when (change) {
                AudioManager.AUDIOFOCUS_LOSS,
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> stop()
            }
        }
        activeFocusListener = listener
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val request = AudioFocusRequest.Builder(focusGain)
                .setAudioAttributes(audioAttributes(slot))
                .setWillPauseWhenDucked(false)
                .setOnAudioFocusChangeListener(listener)
                .build()
            activeFocusRequest = request
            audioManager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(
                listener,
                if (slot.isCritical()) AudioManager.STREAM_ALARM else AudioManager.STREAM_MUSIC,
                focusGain
            ) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }
    }

    private fun audioAttributes(slot: GlucoseAlertAudioSlot): AudioAttributes {
        return AudioAttributes.Builder()
            .setUsage(
                if (slot.isCritical()) {
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
