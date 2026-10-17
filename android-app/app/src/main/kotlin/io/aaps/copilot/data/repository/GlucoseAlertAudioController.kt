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
) : LocalAlarmAudioPort {

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val ownership = AlertAudioOwnership()
    private var activeRequest: AlertAudioRequest? = null

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
    @Volatile private var localAudioOwner: LocalAlarmAudioOwner? = null
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

    override suspend fun prepareLocalAlarm(owner: LocalAlarmAudioOwner): Boolean {
        val stamp = ownership.stamp()
        return withContext(Dispatchers.Main.immediate) {
            localAudioOwner?.let { previous ->
                if (previous.retained() || !previous.awaitRelease()) return@withContext false
                if (localAudioOwner === previous) localAudioOwner = null
            }
            if (!owner.current()) return@withContext false
            val request = ownership.beginLocalIfCurrent(stamp, owner.cycle) ?: return@withContext false
            var prepared = false
            try {
                stopActivePlayback()
                if (!ownership.isCurrent(request) || !owner.current()) return@withContext false
                localAudioOwner = owner
                prepared = ownership.isCurrent(request) && owner.current()
                prepared
            } finally {
                if (!prepared) {
                    if (localAudioOwner === owner) localAudioOwner = null
                    ownership.release(request)
                    owner.revoke()
                }
            }
        }
    }

    override fun ownsLocalAlarm(cycle: LocalAlarmCycle): Boolean = localAudioOwner?.let {
        it.cycle == cycle && it.retained() && localAudioOwner === it
    } == true

    override suspend fun playLocalAlarm(
        cycle: LocalAlarmCycle,
        stepIndex: Int,
        settings: AppSettings,
        admitted: (LocalAlarmCycle) -> Boolean
    ): GlucoseAlertAudioPlaybackResult {
        val stamp = ownership.stamp()
        return withContext(Dispatchers.Main.immediate) {
            val allowed = try {
                admitted(cycle)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                false
            }
            if (!allowed) return@withContext GlucoseAlertAudioPlaybackResult(failureReason = "local_alarm_denied")
            localAudioOwner?.let {
                if (it.cycle != cycle || !it.current()) return@withContext GlucoseAlertAudioPlaybackResult(
                    failureReason = "local_alarm_owner_changed")
            }
            val slot = if (!cycle.level.strong) GlucoseAlertAudioSlot.SOFT
                else if (cycle.ordinal % 2L == 1L) GlucoseAlertAudioSlot.CRITICAL_PRIMARY
                else GlucoseAlertAudioSlot.CRITICAL_SECONDARY
            val spec = resolvePlaybackSpec(settings, slot)
            val window = LocalAlarmPlaybackWindow.create(cycle, stepIndex, spec.durationMs)
                ?: return@withContext GlucoseAlertAudioPlaybackResult(failureReason = "local_alarm_invalid_step")
            if (pendingLocalSession != null ||
                (lastLocalWindow?.cycle == window.cycle && lastLocalWindow!!.stepIndex >= window.stepIndex)) {
                return@withContext GlucoseAlertAudioPlaybackResult(failureReason = "local_alarm_duplicate_or_busy")
            }
            val request = ownership.beginLocalIfCurrent(stamp, cycle)
                ?: return@withContext GlucoseAlertAudioPlaybackResult(failureReason = "audio_request_superseded")
            startPlaybackOnMain(slot, spec, isPreview = false, ensureAudible = false,
                request = request, localSession = LocalPlaybackSession(window, admitted, request))
        }
    }

    override fun stopLocalAlarm(cycle: LocalAlarmCycle) {
        val revoked = ownership.revokeCycle(cycle)
        localAudioOwner?.takeIf { it.cycle == cycle }?.retire()
        val stop = Runnable {
            stopCapturedRequests(revoked, "local_alarm_stopped")
            // A cycle stop still applies to a later step of that exact cycle, never a newer ordinal.
            stopCapturedRequests(ownership.revokeCycle(cycle), "local_alarm_stopped")
            // Keep the retired lease visible until its executor restores volume.
        }
        if (Looper.myLooper() == Looper.getMainLooper()) stop.run() else mainHandler.post(stop)
    }

    fun stopPreview() {
        val revoked = ownership.revokePreview()
        mainHandler.post { stopCapturedRequests(revoked) }
    }

    fun stop() {
        val revoked = ownership.revokeAll()
        localAudioOwner?.revoke()
        mainHandler.post { stopCapturedRequests(revoked) }
    }

    private suspend fun play(
        slot: GlucoseAlertAudioSlot,
        settings: AppSettings,
        isPreview: Boolean,
        ensureAudible: Boolean
    ): GlucoseAlertAudioPlaybackResult {
        val request = ownership.begin(if (isPreview) AlertAudioOwnerKind.PREVIEW else AlertAudioOwnerKind.LEGACY)
        try {
            val spec = resolvePlaybackSpec(settings, slot)
            return withContext(Dispatchers.Main.immediate) {
                if (!ownership.isCurrent(request)) return@withContext GlucoseAlertAudioPlaybackResult(
                    failureReason = "audio_request_superseded")
                val interrupted = localAudioOwner
                interrupted?.revoke()
                // The former executor restores its volume before another clip can use the stream.
                if (interrupted != null) {
                    if (!interrupted.awaitRelease()) {
                        ownership.release(request)
                        return@withContext GlucoseAlertAudioPlaybackResult(failureReason = "local_audio_cleanup_unconfirmed")
                    }
                    if (localAudioOwner === interrupted) localAudioOwner = null
                }
                startPlaybackOnMain(slot, spec, isPreview, ensureAudible, request)
            }
        } catch (failure: Throwable) {
            ownership.revoke(request)
            mainHandler.post { stopOwnedPlayback(request, "audio_request_cancelled") }
            throw failure
        }
    }

    private suspend fun startPlaybackOnMain(
        slot: GlucoseAlertAudioSlot,
        spec: GlucoseAlertAudioClipSpec,
        isPreview: Boolean,
        ensureAudible: Boolean,
        request: AlertAudioRequest,
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

            fun requireOwnership(): Boolean {
                if (ownership.isCurrent(request)) return true
                stopOwnedPlayback(request, "audio_request_superseded")
                resumeFailure("audio_request_superseded")
                return false
            }

            fun playbackAllowed(): Boolean {
                if (!ownership.isCurrent(request)) return false
                val session = localSession ?: return true
                if (pendingLocalSession !== session && activeLocalSession !== session) return false
                return try {
                    session.admitted(session.window.cycle) &&
                        ownership.isCurrent(request) &&
                        (pendingLocalSession === session || activeLocalSession === session) &&
                        session.window.remainingStartMs(nowElapsedMs()) != null && ownership.isCurrent(request)
                } catch (cancellation: CancellationException) {
                    continuation.cancel(cancellation)
                    false
                } catch (_: Throwable) {
                    false
                }
            }

            fun requirePlayback(): Boolean {
                if (playbackAllowed()) return true
                val reason = if (localSession != null) "local_alarm_denied" else "audio_request_superseded"
                stopOwnedPlayback(request, reason)
                resumeFailure(reason)
                return false
            }

            try {
                if (localSession != null) {
                    pendingLocalSession = localSession
                }
                if (!requirePlayback()) return@suspendCancellableCoroutine
                stopActivePlayback()
                if (!ownership.activate(request)) {
                    stopOwnedPlayback(request, "audio_request_superseded")
                    resumeFailure("audio_request_superseded")
                    return@suspendCancellableCoroutine
                }
                activeRequest = request
                activeLocalSession = localSession
                pendingLocalSession = null
                if (localSession != null) lastLocalWindow = localSession.window
                if (!requirePlayback()) return@suspendCancellableCoroutine
                if (!retainAudioFocusOrCleanup(requestAudioFocus(slot, localSession != null),
                    { if (activeRequest === request) abandonAudioFocus() })) {
                    stopOwnedPlayback(request, "audio_focus_denied")
                    resumeFailure("audio_focus_denied")
                    return@suspendCancellableCoroutine
                }
                if (!requirePlayback()) return@suspendCancellableCoroutine
                val player = createUnpreparedPlayer(spec, slot, localSession != null)
                if (player == null) {
                    stopOwnedPlayback(request, "audio_player_create_failed")
                    resumeFailure("audio_player_create_failed")
                    return@suspendCancellableCoroutine
                }
                val volume = playbackVolume(slot)
                player.setVolume(volume, volume)
                activePlayer = player
                activeStartFailure = ::resumeFailure
                if (!requirePlayback()) return@suspendCancellableCoroutine
                continuation.invokeOnCancellation {
                    ownership.revoke(request)
                    mainHandler.post {
                        stopOwnedPlayback(request, "audio_start_cancelled")
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
                        if (playbackStarted) return@startPlayback
                        if (activePlayer !== player || !requirePlayback()) return@startPlayback
                        val startedAt = if (localSession != null) nowElapsedMs() else null
                        if (localSession != null) {
                            val remaining = localSession.window.playDurationMs(requireNotNull(startedAt))
                            if (remaining == null) {
                                stopActivePlayback("local_alarm_expired")
                                return@startPlayback
                            }
                            playedDurationMs = remaining
                        }
                        if (!requireOwnership()) return@startPlayback
                        playbackStarted = true
                        player.start()
                        if (!requirePlayback()) return@startPlayback
                        val stopDelay = if (startedAt != null) {
                            val now = nowElapsedMs()
                            val end = startedAt + playedDurationMs
                            if (now < startedAt || now >= end) {
                                stopActivePlayback("local_alarm_expired")
                                return@startPlayback
                            }
                            end - now
                        } else playedDurationMs.toLong()
                        if (!requireOwnership()) return@startPlayback
                        activeStopRunnable?.let(mainHandler::removeCallbacks)
                        val stopRunnable = Runnable {
                            if (activePlayer === player) {
                                stopActivePlayback()
                            }
                        }
                        activeStopRunnable = stopRunnable
                        mainHandler.postDelayed(stopRunnable, stopDelay)
                        if (ensureAudible) raiseAlarmVolumeIfNeeded()
                        _playbackState.value = GlucoseAlertAudioPlaybackState(
                            activeSlot = slot,
                            clipLabel = spec.label,
                            isPreview = isPreview
                        )
                        if (!requireOwnership()) return@startPlayback
                        activeStartFailure = null
                        if (continuation.isActive) continuation.resume(result(played = true))
                    } catch (_: Throwable) {
                        stopOwnedPlayback(request, "audio_start_failed")
                    }
                }
                player.setOnPreparedListener { prepared ->
                    if (activePlayer !== prepared) return@setOnPreparedListener
                    if (playbackStarted) return@setOnPreparedListener
                    if (!requirePlayback()) return@setOnPreparedListener
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
                    if (!requirePlayback()) return@suspendCancellableCoroutine
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
                stopOwnedPlayback(request, "audio_prepare_failed")
                resumeFailure("audio_prepare_failed")
            }
        }
    } catch (cancellation: CancellationException) {
        // This function runs on Dispatchers.Main. Cleanup therefore completes
        // before the bounded caller can release the alert coordinator mutex.
        stopOwnedPlayback(request, "audio_start_cancelled")
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

    private fun stopCapturedRequests(requests: Set<AlertAudioRequest>, reason: String = "audio_stopped") {
        pendingLocalSession?.takeIf { it.request in requests }?.let { pendingLocalSession = null }
        activeRequest?.takeIf { it in requests }?.let { stopOwnedPlayback(it, reason) }
    }

    private fun stopOwnedPlayback(request: AlertAudioRequest, reason: String) {
        if (pendingLocalSession?.request === request) pendingLocalSession = null
        if (activeRequest === request) stopActivePlayback(reason) else ownership.release(request)
    }

    private fun stopActivePlayback(failureReason: String = "audio_stopped") {
        val request = activeRequest
        activeRequest = null
        activeLocalSession = null
        val pendingFailure = activeStartFailure
        activeStartFailure = null
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
        request?.let(ownership::release)
        // Observers may start a replacement immediately; finish all old cleanup first.
        _playbackState.value = GlucoseAlertAudioPlaybackState()
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
        val admitted: (LocalAlarmCycle) -> Boolean,
        val request: AlertAudioRequest
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
