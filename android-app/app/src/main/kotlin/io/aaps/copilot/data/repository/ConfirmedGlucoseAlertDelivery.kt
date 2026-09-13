package io.aaps.copilot.data.repository

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

internal suspend fun deliverConfirmedGlucoseAlertChannels(
    notificationPermissionGranted: Boolean,
    postNotification: () -> Boolean,
    invokeVibration: () -> Boolean,
    audioStartTimeoutMs: Long = GLUCOSE_ALERT_AUDIO_START_TIMEOUT_MS,
    startAudio: suspend () -> GlucoseAlertAudioPlaybackResult
): GlucoseAlertDeliveryResult {
    require(audioStartTimeoutMs > 0L)
    val notificationPosted = notificationPermissionGranted && safeChannelInvocation(postNotification)
    val vibrationSucceeded = safeChannelInvocation(invokeVibration)
    val audio = try {
        withTimeoutOrNull(audioStartTimeoutMs) { startAudio() }
            ?: GlucoseAlertAudioPlaybackResult(failureReason = "audio_start_timeout")
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (error: Exception) {
        GlucoseAlertAudioPlaybackResult(
            failureReason = error::class.java.simpleName.ifBlank { "audio_start_failed" }
        )
    }
    val failureReason = when {
        notificationPosted || vibrationSucceeded || audio.played -> null
        !audio.failureReason.isNullOrBlank() -> audio.failureReason
        !notificationPermissionGranted -> "notifications_denied_no_fallback_channel"
        else -> "risk_side_effect_not_delivered"
    }
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
        audioDurationMs = audio.durationMs
    )
}

internal const val GLUCOSE_ALERT_AUDIO_START_TIMEOUT_MS = 4_000L

internal fun GlucoseAlertDeliveryResult.toAlertSideEffectResult(): AlertSideEffectResult =
    if (notificationPosted || vibrationSucceeded || audioPlayed) {
        AlertSideEffectResult.delivered()
    } else {
        AlertSideEffectResult.failed(degradedReason ?: "risk_side_effect_not_delivered")
    }

private inline fun safeChannelInvocation(block: () -> Boolean): Boolean = try {
    block()
} catch (_: Exception) {
    false
}
