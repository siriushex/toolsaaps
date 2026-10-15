package io.aaps.copilot.domain.alerts

import com.google.common.truth.Truth.assertThat
import io.aaps.copilot.data.repository.GlucoseAlertAudioVolumePolicy
import org.junit.Test

class LocalAlarmProfilesTest {
    private val timing = LocalAlarmTiming()

    @Test fun softLevelsUseFourBoundedClips() {
        for (level in listOf(LocalAlarmLevel.SOFT_HIGH_RISK, LocalAlarmLevel.WARNING_30,
            LocalAlarmLevel.PUMP_LINK, LocalAlarmLevel.DELIVERY_DIAGNOSTIC)) {
            val profile = LocalAlarmProfiles.create(level, timing)!!
            assertThat(profile.steps.map { it.offsetMs }).containsExactly(0L, 15_000L, 30_000L, 45_000L).inOrder()
            assertThat(profile.steps.map { it.targetPercent }).containsExactly(25, 50, 75, 100).inOrder()
            assertThat(profile.steps.all { it.startsClip }).isTrue()
            assertThat(profile.clipDurationMs).isEqualTo(2_000)
            assertThat(profile.repeatMs).isEqualTo(300_000L)
            assertThat(profile.cycleTimeoutMs).isEqualTo(55_000L)
        }
    }

    @Test fun criticalLevelUsesOneClipAndQuarterDurationSteps() {
        val profile = LocalAlarmProfiles.create(LocalAlarmLevel.CRITICAL_5, timing)!!
        assertThat(profile.steps.map { it.offsetMs }).containsExactly(0L, 5_000L, 10_000L, 15_000L).inOrder()
        assertThat(profile.steps.map { it.targetPercent }).containsExactly(25, 50, 75, 100).inOrder()
        assertThat(profile.steps.map { it.startsClip }).containsExactly(true, false, false, false).inOrder()
        assertThat(profile.clipDurationMs).isEqualTo(20_000)
        assertThat(profile.repeatMs).isEqualTo(120_000L)
    }

    @Test fun lowNowStartsAtExistingUrgentFloorImmediately() {
        val profile = LocalAlarmProfiles.create(LocalAlarmLevel.LOW_NOW, timing)!!
        assertThat(profile.steps.map { it.targetPercent }).containsExactly(70, 85, 100, 100).inOrder()
        assertThat(profile.steps.first().offsetMs).isEqualTo(0L)
        for (maxVolume in 1..100) {
            assertThat(LocalAlarmProfiles.targetVolume(70, maxVolume, 0))
                .isEqualTo(GlucoseAlertAudioVolumePolicy.minimumAudibleVolume(maxVolume))
        }
    }

    @Test fun silentLevelsNeverHaveAnAudibleProfile() {
        assertThat(LocalAlarmProfiles.create(LocalAlarmLevel.NONE, timing)).isNull()
        assertThat(LocalAlarmProfiles.create(LocalAlarmLevel.WATCH_60, timing)).isNull()
    }

    @Test fun actuallyReachedProgressionIsRetainedAcrossRepeats() {
        val profile = LocalAlarmProfiles.create(LocalAlarmLevel.WARNING_30, timing, reachedPercent = 75)!!
        assertThat(profile.steps.map { it.targetPercent }).containsExactly(75, 75, 75, 100).inOrder()
        val completed = LocalAlarmProfiles.create(LocalAlarmLevel.LOW_NOW, timing, reachedPercent = 100)!!
        assertThat(completed.steps.map { it.targetPercent }).containsExactly(100, 100, 100, 100).inOrder()
    }

    @Test fun minimumAndMaximumConfiguredTimingsStayBounded() {
        for (softDuration in listOf(1_000, 5_000)) {
            for (strongDuration in listOf(15_000, 30_000)) {
                val limits = timing.copy(softClipMs = softDuration, strongClipMs = strongDuration,
                    softRepeatMinutes = 30, strongRepeatMinutes = 10)
                for (level in listOf(LocalAlarmLevel.WARNING_30, LocalAlarmLevel.LOW_NOW)) {
                    val profile = LocalAlarmProfiles.create(level, limits)!!
                    assertThat(profile.steps.last().offsetMs + profile.clipDurationMs).isAtMost(profile.cycleTimeoutMs)
                }
            }
        }
        val minimum = timing.copy(softRepeatMinutes = 5, strongRepeatMinutes = 1)
        assertThat(LocalAlarmProfiles.create(LocalAlarmLevel.LOW_NOW, minimum)!!.repeatMs).isEqualTo(60_000L)
    }

    @Test fun corruptTimingAndProgressionFailClosed() {
        val invalid = listOf(timing.copy(softClipMs = 999), timing.copy(softClipMs = 5_001),
            timing.copy(strongClipMs = 14_999), timing.copy(strongClipMs = 30_001),
            timing.copy(softRepeatMinutes = 4), timing.copy(softRepeatMinutes = 31),
            timing.copy(strongRepeatMinutes = 0), timing.copy(strongRepeatMinutes = 11))
        invalid.forEach { assertThat(LocalAlarmProfiles.create(LocalAlarmLevel.LOW_NOW, it)).isNull() }
        for (progress in listOf(-1, 101, Int.MAX_VALUE)) {
            assertThat(LocalAlarmProfiles.create(LocalAlarmLevel.LOW_NOW, timing, progress)).isNull()
        }
    }

    @Test fun hardwareRoundingIsUpwardAndNeverReducesCurrentVolume() {
        assertThat(LocalAlarmProfiles.targetVolume(25, 7, 0)).isEqualTo(2)
        assertThat(LocalAlarmProfiles.targetVolume(70, 7, 0)).isEqualTo(5)
        assertThat(LocalAlarmProfiles.targetVolume(25, 7, 7)).isEqualTo(7)
        assertThat(LocalAlarmProfiles.targetVolume(100, 7, 0)).isEqualTo(7)
    }

    @Test fun hardwareArithmeticDoesNotOverflow() {
        assertThat(LocalAlarmProfiles.targetVolume(100, Int.MAX_VALUE, 0)).isEqualTo(Int.MAX_VALUE)
        assertThat(LocalAlarmProfiles.targetVolume(25, Int.MAX_VALUE, 0)).isEqualTo(536_870_912)
    }

    @Test fun unknownOrCorruptHardwareRangeCannotAuthorizeVolumeMutation() {
        for (maxVolume in listOf(-1, 0)) assertThat(LocalAlarmProfiles.targetVolume(70, maxVolume, 0)).isNull()
        for (percent in listOf(-1, 0, 101)) assertThat(LocalAlarmProfiles.targetVolume(percent, 7, 0)).isNull()
        assertThat(LocalAlarmProfiles.targetVolume(70, 7, -1)).isNull()
        assertThat(LocalAlarmProfiles.targetVolume(70, 7, 8)).isNull()
    }
}
