package io.aaps.copilot.service

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PowerSaveControllerTest {

    @Test
    fun isActiveUntil_handlesOffTimedAndIndefiniteModes() {
        val now = 1_800_000_000_000L

        assertThat(PowerSaveController.isActiveUntil(PowerSaveController.OFF_UNTIL_MS, now)).isFalse()
        assertThat(PowerSaveController.isActiveUntil(now - 1L, now)).isFalse()
        assertThat(PowerSaveController.isActiveUntil(now + 1L, now)).isTrue()
        assertThat(PowerSaveController.isActiveUntil(PowerSaveController.INDEFINITE_UNTIL_MS, now)).isTrue()
    }

    @Test
    fun untilForDuration_returnsFutureDeadline() {
        val now = 1_800_000_000_000L

        val until = PowerSaveController.untilForDuration(now, PowerSaveController.TWO_HOURS_MS)

        assertThat(until).isEqualTo(now + 2L * 60L * 60_000L)
    }
}
