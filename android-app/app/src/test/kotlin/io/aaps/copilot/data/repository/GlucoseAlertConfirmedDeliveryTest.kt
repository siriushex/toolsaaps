package io.aaps.copilot.data.repository

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import org.junit.Test

class GlucoseAlertConfirmedDeliveryTest {

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
