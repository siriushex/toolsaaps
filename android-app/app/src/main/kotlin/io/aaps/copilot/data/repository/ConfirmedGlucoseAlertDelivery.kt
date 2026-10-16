package io.aaps.copilot.data.repository

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

internal suspend fun deliverConfirmedGlucoseAlertChannels(
    notificationPermissionGranted: Boolean,
    postNotification: () -> Boolean,
    invokeVibration: () -> Boolean,
    audioStartTimeoutMs: Long = GLUCOSE_ALERT_AUDIO_START_TIMEOUT_MS,
    audioRequired: Boolean = false,
    vibrationRequired: Boolean = false,
    startLocal: (suspend () -> LocalGlucoseInitialResult?)? = null,
    startAudio: suspend () -> GlucoseAlertAudioPlaybackResult
): GlucoseAlertDeliveryResult {
    require(audioStartTimeoutMs > 0L)
    val notificationPosted = notificationPermissionGranted && safeChannelInvocation(postNotification)
    val local = try {
        if (startLocal == null) null else {
            // An explicit decline permits fallback; an unfinished handoff does not.
            val attempt = withTimeoutOrNull(audioStartTimeoutMs) { LocalStartAttempt(startLocal()) }
            if (attempt == null) LocalGlucoseInitialResult(
                GlucoseAlertAudioPlaybackResult(failureReason = "local_start_timeout"), false)
            else attempt.result
        }
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { LocalGlucoseInitialResult(GlucoseAlertAudioPlaybackResult(failureReason = "local_start_failed"), false) }
    val vibrationSucceeded = local?.vibration ?: safeChannelInvocation(invokeVibration)
    val audio = local?.audio ?: try {
        withTimeoutOrNull(audioStartTimeoutMs) { startAudio() }
            ?: GlucoseAlertAudioPlaybackResult(failureReason = "audio_start_timeout")
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (error: Exception) {
        GlucoseAlertAudioPlaybackResult(
            failureReason = error::class.java.simpleName.ifBlank { "audio_start_failed" }
        )
    }
    val notificationFailure = when {
        !notificationPermissionGranted -> "notifications_denied"
        !notificationPosted -> "notification_post_failed_or_blocked"
        else -> null
    }
    val audioFailure = if (audio.played) null else audio.failureReason?.takeIf { it.isNotBlank() }
        ?: "audio_not_started".takeIf { audioRequired }
    val vibrationFailure = "vibration_unavailable".takeIf { vibrationRequired && !vibrationSucceeded }
    val failureReason = audioFailure ?: notificationFailure ?: vibrationFailure
    return GlucoseAlertDeliveryResult(
        notificationPermissionGranted = notificationPermissionGranted,
        notificationPosted = notificationPosted,
        vibrationSucceeded = vibrationSucceeded,
        degradedReason = failureReason,
        audioPlayed = audio.played,
        audioStartEnqueued = audio.startEnqueued,
        audioClipLabel = audio.clipLabel,
        audioFallbackUsed = audio.fallbackUsed,
        audioStartMs = audio.startMs,
        audioDurationMs = audio.durationMs,
        notificationFailureReason = notificationFailure,
        audioFailureReason = audioFailure,
        vibrationFailureReason = vibrationFailure
    )
}

internal const val GLUCOSE_ALERT_AUDIO_START_TIMEOUT_MS = 4_000L

private data class LocalStartAttempt(val result: LocalGlucoseInitialResult?)

internal fun GlucoseAlertDeliveryResult.toAlertSideEffectResult(): AlertSideEffectResult =
    if (notificationPosted || vibrationSucceeded || audioPlayed) {
        AlertSideEffectResult(AlertReceiptResult.DELIVERED, degradedReason?.take(160))
    } else {
        AlertSideEffectResult.failed(degradedReason ?: "risk_side_effect_not_delivered")
    }

private inline fun safeChannelInvocation(block: () -> Boolean): Boolean = try {
    block()
} catch (_: Exception) {
    false
}
