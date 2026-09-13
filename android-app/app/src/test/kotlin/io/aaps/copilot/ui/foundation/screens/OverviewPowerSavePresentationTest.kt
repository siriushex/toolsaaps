package io.aaps.copilot.ui.foundation.screens

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class OverviewPowerSavePresentationTest {

    @Test
    fun activePowerSaveOwnsBannerEvenWhenDataIsStale() {
        val displayedKind = resolveOverviewBannerKind(
            warningKind = OverviewWarningKind.SENSOR_OR_STALE,
            powerSaveActive = true
        )

        assertThat(displayedKind).isEqualTo(OverviewWarningKind.POWER_SAVE)
    }

    @Test
    fun inactivePowerSaveKeepsExistingWarning() {
        val displayedKind = resolveOverviewBannerKind(
            warningKind = OverviewWarningKind.KILL_SWITCH,
            powerSaveActive = false
        )

        assertThat(displayedKind).isEqualTo(OverviewWarningKind.KILL_SWITCH)
    }
}
