package io.aaps.copilot.data.repository

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class GlucoseAlertMuteOptionTest {

    @Test
    fun notificationActionsResolveToThirtyAndSixtyMinuteOptions() {
        assertThat(GlucoseAlertMuteOption.fromAction(GlucoseAlertMuteOption.MINUTES_30.action))
            .isEqualTo(GlucoseAlertMuteOption.MINUTES_30)
        assertThat(GlucoseAlertMuteOption.fromAction(GlucoseAlertMuteOption.MINUTES_60.action))
            .isEqualTo(GlucoseAlertMuteOption.MINUTES_60)
        assertThat(GlucoseAlertMuteOption.entries.map { it.minutes }).containsExactly(30, 60).inOrder()
        assertThat(GlucoseAlertMuteOption.entries.map { it.requestCode }.toSet()).hasSize(2)
    }
}
