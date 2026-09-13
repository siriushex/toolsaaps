package io.aaps.copilot.ui.foundation.screens

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Test

class UamExportModeUiSourceTest {

    @Test
    fun selectorDisablesEveryModeAndExplainsInferenceDependencyWhenUnavailable() {
        val source = sourceFile("UamExportModeSelector.kt").readText()

        assertThat(source).contains("enabled: Boolean")
        assertThat(source).contains("enabled = enabled")
        assertThat(source).contains("R.string.uam_mode_inference_required")
    }

    @Test
    fun overviewAndSettingsUseSharedUamExportAvailability() {
        listOf("OverviewScreen.kt", "SettingsScreen.kt").forEach { name ->
            val source = sourceFile(name).readText()
            assertThat(source).contains("enabled = state.uamExport.available")
        }
    }

    private fun sourceFile(name: String): File =
        File("src/main/kotlin/io/aaps/copilot/ui/foundation/screens/$name")
}
