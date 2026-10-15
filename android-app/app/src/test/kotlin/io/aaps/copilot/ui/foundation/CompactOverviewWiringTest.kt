package io.aaps.copilot.ui.foundation

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CompactOverviewWiringTest {
    private fun source(path: String) = File("src/main/kotlin/io/aaps/copilot/ui/foundation/$path").readText()

    @Test fun topActionsKeepOriginalCycleAndConsumedBloodEditor() {
        val root = source("CopilotFoundationRoot.kt")
        assertTrue(root.contains("onOverviewRunCycle = viewModel::runCycleNow"))
        assertTrue(root.contains("onOverviewBloodCheck"))
        assertTrue(root.contains("OverviewAuthoritativeEditor.BLOOD_CHECK"))
        assertTrue(root.contains("ClarityOverviewActions("))
        assertFalse(root.contains("Icons.Default.MoreVert"))
    }

    @Test fun drawerRetainsPowerSaveAndSafetyWhileLowerDuplicateIsRemoved() {
        val root = source("CopilotFoundationRoot.kt")
        assertTrue(root.contains("object Safety : MoreDestination"))
        assertTrue(root.contains("showPowerSaveDialog = true"))
        assertTrue(root.contains("testTag(\"drawerPowerSave\")"))
        val main = source("screens/ClarityOverview.kt")
        assertFalse(main.contains("clarityMoreDetails"))
        assertFalse(main.contains("clarityFoodOpen"))
        assertTrue(main.contains("OverviewSensitivitySourceStatus(state)"))
    }
}
