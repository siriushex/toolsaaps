package io.aaps.copilot.ui.foundation

import com.google.common.truth.Truth.assertThat
import io.aaps.copilot.ui.AutomaticEventAiAnalysisSettingsAction
import org.junit.Test

class CopilotFoundationAutomaticAlertAiWiringTest {
    @Test
    fun rootCallbackBundleForwardsExactSelectionToProductionBinding() {
        val observed = mutableListOf<Boolean>()
        val callbacks = clinicalAiSettingsRootCallbacks(
            AutomaticEventAiAnalysisSettingsAction { observed += it }
        )

        callbacks.onAutomaticCauseAnalysisToggle(false)
        callbacks.onAutomaticCauseAnalysisToggle(true)

        assertThat(observed).containsExactly(false, true).inOrder()
    }
}
