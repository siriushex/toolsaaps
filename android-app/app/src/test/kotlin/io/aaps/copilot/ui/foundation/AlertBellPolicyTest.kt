package io.aaps.copilot.ui.foundation

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AlertBellPolicyTest {
    @Test
    fun unmutedBellOpensChoicesAndMutedBellResumesImmediately() {
        assertThat(alertBellPressAction(alertsMuted = false)).isEqualTo(AlertBellPressAction.OPEN_MENU)
        assertThat(alertBellPressAction(alertsMuted = true)).isEqualTo(AlertBellPressAction.RESUME)
    }

    @Test
    fun notebookRemainsBeforeBellInStableActionOrder() {
        assertThat(overviewClinicalActionOrder()).containsExactly("events", "alerts").inOrder()
    }
}
