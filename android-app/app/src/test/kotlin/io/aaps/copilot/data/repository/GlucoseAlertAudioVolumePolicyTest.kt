package io.aaps.copilot.data.repository

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class GlucoseAlertAudioVolumePolicyTest {

    @Test
    fun minimumAudibleAlarmVolumeRoundsUpToSeventyPercent() {
        assertThat(GlucoseAlertAudioVolumePolicy.minimumAudibleVolume(maxVolume = 7)).isEqualTo(5)
        assertThat(GlucoseAlertAudioVolumePolicy.minimumAudibleVolume(maxVolume = 15)).isEqualTo(11)
    }

    @Test
    fun volumeIsRaisedOnlyForFreshCurrentLowNowBelowFour() {
        assertThat(
            GlucoseAlertAudioVolumePolicy.shouldEnsureAudible(
                notifyKind = GlucoseAlertNotifyKind.LOW_NOW,
                currentGlucoseMmol = 3.9,
                currentGlucoseFresh = true
            )
        ).isTrue()
        assertThat(
            GlucoseAlertAudioVolumePolicy.shouldEnsureAudible(
                notifyKind = GlucoseAlertNotifyKind.CRITICAL_5,
                currentGlucoseMmol = 3.9,
                currentGlucoseFresh = true
            )
        ).isFalse()
        assertThat(
            GlucoseAlertAudioVolumePolicy.shouldEnsureAudible(
                notifyKind = GlucoseAlertNotifyKind.LOW_NOW,
                currentGlucoseMmol = 4.0,
                currentGlucoseFresh = true
            )
        ).isFalse()
        assertThat(
            GlucoseAlertAudioVolumePolicy.shouldEnsureAudible(
                notifyKind = GlucoseAlertNotifyKind.LOW_NOW,
                currentGlucoseMmol = 3.8,
                currentGlucoseFresh = false
            )
        ).isFalse()
    }
}
