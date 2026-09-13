package io.aaps.copilot.ui.foundation.screens

import com.google.common.truth.Truth.assertThat
import io.aaps.copilot.data.repository.GlucoseAlertBellAction
import io.aaps.copilot.data.repository.glucoseAlertBellAction
import org.junit.Test

class OverviewAlertPresentationTest {

    @Test
    fun roundsActiveMuteUpToWholeMinutes() {
        assertThat(
            remainingGlucoseAlertMuteMinutes(
                mutedUntilTs = 31 * 60_000L,
                nowTs = 60_001L
            )
        ).isEqualTo(30)
    }

    @Test
    fun returnsZeroAfterMuteExpires() {
        assertThat(remainingGlucoseAlertMuteMinutes(mutedUntilTs = 60_000L, nowTs = 60_000L))
            .isEqualTo(0)
    }

    @Test
    fun deliveredMuteTimestampIsTheOnlyUiAuthority() {
        assertThat(isAuthoritativelyMuted(1L)).isTrue()
        assertThat(isAuthoritativelyMuted(0L)).isFalse()
    }

    @Test
    fun bellMutesForThirtyMinutesWhenAlertsAreActive() {
        assertThat(glucoseAlertBellAction(mutedUntilTs = 0L, nowTs = 1_000L))
            .isEqualTo(GlucoseAlertBellAction.MUTE_30_MINUTES)
    }

    @Test
    fun bellResumesImmediatelyWhileMuteIsActive() {
        assertThat(glucoseAlertBellAction(mutedUntilTs = 61_000L, nowTs = 1_000L))
            .isEqualTo(GlucoseAlertBellAction.RESUME)
    }
}
