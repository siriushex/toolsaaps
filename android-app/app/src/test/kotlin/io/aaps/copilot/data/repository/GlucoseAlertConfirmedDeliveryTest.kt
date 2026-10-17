package io.aaps.copilot.data.repository

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Test

class GlucoseAlertConfirmedDeliveryTest {

    @Test fun acceptedLocalStartDoesNotAlsoStartNativeChannels() = runTest {
        val result = deliverConfirmedGlucoseAlertChannels(true, { true }, { error("duplicate vibration") },
            startLocal = { LocalGlucoseInitialResult(GlucoseAlertAudioPlaybackResult(played = true), true) },
            startAudio = { error("duplicate audio") })
        assertThat(result.audioPlayed).isTrue()
        assertThat(result.vibrationSucceeded).isTrue()
    }

    @Test fun explicitLocalDeclinePreservesNativeFallback() = runTest {
        var audio = 0; var vibration = 0
        val result = deliverConfirmedGlucoseAlertChannels(true, { true }, { vibration++; true },
            startLocal = { null }, startAudio = { audio++; GlucoseAlertAudioPlaybackResult(played = true) })
        assertThat(result.audioPlayed).isTrue(); assertThat(audio).isEqualTo(1); assertThat(vibration).isEqualTo(1)
    }

    @Test fun unconfirmedLocalTimeoutNeverStartsDuplicateNativeChannels() = runTest {
        val result = deliverConfirmedGlucoseAlertChannels(true, { true }, { error("unsafe fallback") },
            audioStartTimeoutMs = 100, startLocal = { awaitCancellation() }, startAudio = { error("unsafe fallback") })
        assertThat(result.audioPlayed).isFalse()
        assertThat(result.audioFailureReason).isEqualTo("local_start_timeout")
    }

    @Test fun failedLocalOwnershipNeverStartsDuplicateNativeChannels() = runTest {
        val result = deliverConfirmedGlucoseAlertChannels(true, { true }, { error("unsafe fallback") },
            startLocal = { error("unknown owner") }, startAudio = { error("unsafe fallback") })
        assertThat(result.audioFailureReason).isEqualTo("local_start_failed")
        assertThat(result.audioPlayed).isFalse()
    }

    @Test fun localCancellationPropagatesWithoutNativeFallback() = runTest {
        val error = runCatching { deliverConfirmedGlucoseAlertChannels(true, { true }, { error("unsafe fallback") },
            startLocal = { throw CancellationException("withdrawn") }, startAudio = { error("unsafe fallback") }) }.exceptionOrNull()
        assertThat(error).isInstanceOf(CancellationException::class.java)
    }

    @Test fun postedNotificationDoesNotHideExplicitAudioFailure() = runTest {
        val result = deliverConfirmedGlucoseAlertChannels(
            notificationPermissionGranted = true, postNotification = { true }, invokeVibration = { true },
            startAudio = { GlucoseAlertAudioPlaybackResult(failureReason = "audio_start_failed") })
        assertThat(result.degradedReason).isEqualTo("audio_start_failed")
        assertThat(result.toAlertSideEffectResult().receiptResult).isEqualTo(AlertReceiptResult.DELIVERED)
        assertThat(result.toAlertSideEffectResult().failureReason).isEqualTo("audio_start_failed")
    }

    @Test fun postedNotificationDoesNotHideAudioTimeout() = runTest {
        val result = deliverConfirmedGlucoseAlertChannels(
            notificationPermissionGranted = true, postNotification = { true }, invokeVibration = { true },
            audioStartTimeoutMs = 100L, startAudio = { awaitCancellation() })
        assertThat(result.degradedReason).isEqualTo("audio_start_timeout")
        assertThat(result.notificationPosted).isTrue()
    }

    @Test fun successfulAudioDoesNotHideDeniedNotification() = runTest {
        val result = deliverConfirmedGlucoseAlertChannels(
            notificationPermissionGranted = false, postNotification = { error("not permitted") },
            invokeVibration = { true }, startAudio = { GlucoseAlertAudioPlaybackResult(played = true) })
        assertThat(result.degradedReason).isEqualTo("notifications_denied")
        assertThat(result.audioPlayed).isTrue()
    }

    @Test fun successfulAudioDoesNotHideFailedNotificationInvocation() = runTest {
        val result = deliverConfirmedGlucoseAlertChannels(
            notificationPermissionGranted = true, postNotification = { throw SecurityException() },
            invokeVibration = { true }, startAudio = { GlucoseAlertAudioPlaybackResult(played = true) })
        assertThat(result.degradedReason).isEqualTo("notification_post_failed_or_blocked")
        assertThat(result.audioPlayed).isTrue()
    }

    @Test fun audioCancellationIsNotConvertedToAHealthyNotificationResult() = runTest {
        val expected = CancellationException("synthetic_stop")
        val failure = runCatching {
            deliverConfirmedGlucoseAlertChannels(
                notificationPermissionGranted = true, postNotification = { true }, invokeVibration = { true },
                startAudio = { throw expected })
        }.exceptionOrNull()
        assertThat(failure).isInstanceOf(CancellationException::class.java)
        assertThat(failure).hasMessageThat().isEqualTo(expected.message)
    }

    @Test fun independentRequiredChannelFailuresAreAllRetained() = runTest {
        val result = deliverConfirmedGlucoseAlertChannels(
            notificationPermissionGranted = false, postNotification = { error("not permitted") },
            invokeVibration = { false }, audioRequired = true, vibrationRequired = true,
            startAudio = { GlucoseAlertAudioPlaybackResult(failureReason = "audio_start_failed") })
        assertThat(result.notificationFailureReason).isEqualTo("notifications_denied")
        assertThat(result.audioFailureReason).isEqualTo("audio_start_failed")
        assertThat(result.vibrationFailureReason).isEqualTo("vibration_unavailable")
        assertThat(result.toAlertSideEffectResult().receiptResult).isEqualTo(AlertReceiptResult.FAILED)
    }

    @Test fun requiredAudioAndVibrationCannotSilentlyReturnNoResult() = runTest {
        val result = deliverConfirmedGlucoseAlertChannels(
            notificationPermissionGranted = true, postNotification = { true },
            invokeVibration = { false }, audioRequired = true, vibrationRequired = true,
            startAudio = { GlucoseAlertAudioPlaybackResult() })
        assertThat(result.notificationFailureReason).isNull()
        assertThat(result.audioFailureReason).isEqualTo("audio_not_started")
        assertThat(result.vibrationFailureReason).isEqualTo("vibration_unavailable")
        assertThat(result.degradedReason).isEqualTo("audio_not_started")
    }

    @Test fun vibrationFailureIsVisibleEvenWhenBothOtherChannelsSucceed() = runTest {
        val result = deliverConfirmedGlucoseAlertChannels(
            notificationPermissionGranted = true, postNotification = { true },
            invokeVibration = { throw SecurityException() }, audioRequired = true, vibrationRequired = true,
            startAudio = { GlucoseAlertAudioPlaybackResult(played = true) })
        assertThat(result.notificationFailureReason).isNull()
        assertThat(result.audioFailureReason).isNull()
        assertThat(result.vibrationFailureReason).isEqualTo("vibration_unavailable")
        assertThat(result.degradedReason).isEqualTo("vibration_unavailable")
    }

    @Test
    fun enqueueButStartFailsIsNotDelivered() = runTest {
        val result = deliverConfirmedGlucoseAlertChannels(
            notificationPermissionGranted = false,
            postNotification = { error("notification must not be attempted") },
            invokeVibration = { false },
            startAudio = {
                GlucoseAlertAudioPlaybackResult(
                    played = false,
                    startEnqueued = true,
                    failureReason = "audio_start_failed"
                )
            }
        )

        assertThat(result.audioStartEnqueued).isTrue()
        assertThat(result.audioPlayed).isFalse()
        assertThat(result.toAlertSideEffectResult().receiptResult).isEqualTo(AlertReceiptResult.FAILED)
    }

    @Test
    fun notificationOnlyConfirmsDelivery() = runTest {
        val result = deliverConfirmedGlucoseAlertChannels(
            notificationPermissionGranted = true,
            postNotification = { true },
            invokeVibration = { false },
            startAudio = { GlucoseAlertAudioPlaybackResult() }
        )

        assertThat(result.notificationPosted).isTrue()
        assertThat(result.toAlertSideEffectResult().receiptResult).isEqualTo(AlertReceiptResult.DELIVERED)
    }

    @Test
    fun vibrationOnlyConfirmsDelivery() = runTest {
        val result = deliverConfirmedGlucoseAlertChannels(
            notificationPermissionGranted = false,
            postNotification = { error("notification must not be attempted") },
            invokeVibration = { true },
            startAudio = { GlucoseAlertAudioPlaybackResult() }
        )

        assertThat(result.vibrationSucceeded).isTrue()
        assertThat(result.toAlertSideEffectResult().receiptResult).isEqualTo(AlertReceiptResult.DELIVERED)
    }

    @Test
    fun audioStartSuccessConfirmsDelivery() = runTest {
        val result = deliverConfirmedGlucoseAlertChannels(
            notificationPermissionGranted = false,
            postNotification = { error("notification must not be attempted") },
            invokeVibration = { false },
            startAudio = { GlucoseAlertAudioPlaybackResult(played = true, startEnqueued = true) }
        )

        assertThat(result.audioPlayed).isTrue()
        assertThat(result.toAlertSideEffectResult().receiptResult).isEqualTo(AlertReceiptResult.DELIVERED)
    }

    @Test
    fun totalFailureIsTerminalFailed() = runTest {
        val result = deliverConfirmedGlucoseAlertChannels(
            notificationPermissionGranted = false,
            postNotification = { error("notification must not be attempted") },
            invokeVibration = { false },
            startAudio = {
                GlucoseAlertAudioPlaybackResult(
                    played = false,
                    startEnqueued = true,
                    failureReason = "audio_start_failed"
                )
            }
        )

        assertThat(result.toAlertSideEffectResult()).isEqualTo(
            AlertSideEffectResult.failed("audio_start_failed")
        )
    }

    @Test
    fun confirmedNotificationRemainsDeliveredWhenAudioStartTimesOut() = runTest {
        var audioCleanedUp = false

        val result = deliverConfirmedGlucoseAlertChannels(
            notificationPermissionGranted = true,
            postNotification = { true },
            invokeVibration = { false },
            audioStartTimeoutMs = 100L,
            startAudio = {
                try {
                    awaitCancellation()
                } finally {
                    audioCleanedUp = true
                }
            }
        )

        assertThat(audioCleanedUp).isTrue()
        assertThat(result.notificationPosted).isTrue()
        assertThat(result.audioPlayed).isFalse()
        assertThat(result.toAlertSideEffectResult().receiptResult)
            .isEqualTo(AlertReceiptResult.DELIVERED)
    }

    @Test
    fun audioOnlyTimeoutIsTerminalFailedAndCancelsPlayback() = runTest {
        var audioCleanedUp = false

        val result = deliverConfirmedGlucoseAlertChannels(
            notificationPermissionGranted = false,
            postNotification = { error("notification must not be attempted") },
            invokeVibration = { false },
            audioStartTimeoutMs = 100L,
            startAudio = {
                try {
                    awaitCancellation()
                } finally {
                    audioCleanedUp = true
                }
            }
        )

        assertThat(audioCleanedUp).isTrue()
        assertThat(result.toAlertSideEffectResult()).isEqualTo(
            AlertSideEffectResult.failed("audio_start_timeout")
        )
    }

    @Test
    fun deniedAudioFocusClearsFocusLease() {
        var cleanupCalls = 0

        val retained = retainAudioFocusOrCleanup(focusGranted = false) {
            cleanupCalls++
        }

        assertThat(retained).isFalse()
        assertThat(cleanupCalls).isEqualTo(1)
    }
}
