package io.aaps.copilot.config

import com.google.common.truth.Truth.assertThat
import io.aaps.copilot.domain.predict.InsulinActionProfileId
import io.aaps.copilot.domain.predict.UamExportMode
import org.junit.Test

class UamExportUiModeCommandTest {

    @Test
    fun exactUiModesApplyThroughPolicy() {
        val initial = testSettings().copy(
            enableUamExportToAaps = true,
            uamExportMode = UamExportMode.CONFIRMED_ONLY,
            dryRunExport = false
        )

        val off = UamExportUiModeCommand.apply(initial, "OFF")
        val observe = UamExportUiModeCommand.apply(initial, "OBSERVE")
        val auto = UamExportUiModeCommand.apply(initial, "AUTO")

        assertThat(off).isEqualTo(UamExportUiModePolicy.apply(initial, UamExportUiMode.OFF))
        assertThat(observe).isEqualTo(UamExportUiModePolicy.apply(initial, UamExportUiMode.OBSERVE))
        assertThat(auto).isEqualTo(UamExportUiModePolicy.apply(initial, UamExportUiMode.AUTO))
    }

    @Test
    fun unknownLegacyOrCaseVariantFailsClosedWithoutMutation() {
        val initial = testSettings().copy(
            enableUamExportToAaps = true,
            uamExportMode = UamExportMode.INCREMENTAL,
            dryRunExport = true
        )

        listOf("", "auto", "INCREMENTAL", "CONFIRMED_ONLY", " AUTO ").forEach { raw ->
            assertThat(UamExportUiModeCommand.apply(initial, raw)).isNull()
        }
        assertThat(initial.enableUamExportToAaps).isTrue()
        assertThat(initial.uamExportMode).isEqualTo(UamExportMode.INCREMENTAL)
        assertThat(initial.dryRunExport).isTrue()
    }

    @Test
    fun inferenceOffRejectsAllModeCommandsWithoutChangingPersistedAuto() {
        val initial = testSettings().copy(
            enableUamInference = false,
            enableUamExportToAaps = true,
            uamExportMode = UamExportMode.INCREMENTAL,
            dryRunExport = false
        )

        UamExportUiMode.entries.forEach { mode ->
            assertThat(UamExportUiModeCommand.apply(initial, mode.name)).isNull()
        }
        assertThat(initial.enableUamExportToAaps).isTrue()
        assertThat(initial.uamExportMode).isEqualTo(UamExportMode.INCREMENTAL)
        assertThat(initial.dryRunExport).isFalse()
    }

    private fun testSettings() = AppSettings(
        nightscoutUrl = "",
        apiSecret = "",
        cloudBaseUrl = "",
        killSwitch = false,
        rootExperimentalEnabled = false,
        localBroadcastIngestEnabled = true,
        strictBroadcastSenderValidation = false,
        localNightscoutEnabled = true,
        localNightscoutPort = 17_582,
        localCommandFallbackEnabled = true,
        localCommandPackage = "info.nightscout.androidaps",
        localCommandAction = "io.aaps.copilot.ACTION_COMMAND",
        insulinProfileId = InsulinActionProfileId.FIASP.name,
        baseTargetMmol = 5.5,
        postHypoThresholdMmol = 3.0,
        postHypoDeltaThresholdMmol5m = 0.2,
        postHypoTargetMmol = 4.4,
        postHypoDurationMinutes = 60,
        postHypoLookbackMinutes = 90,
        rulePostHypoEnabled = true,
        rulePatternEnabled = true,
        ruleSegmentEnabled = true,
        adaptiveControllerEnabled = true,
        rulePostHypoPriority = 100,
        rulePatternPriority = 50,
        ruleSegmentPriority = 40,
        adaptiveControllerPriority = 120,
        rulePostHypoCooldownMinutes = 30,
        rulePatternCooldownMinutes = 30,
        ruleSegmentCooldownMinutes = 30,
        adaptiveControllerRetargetMinutes = 5,
        adaptiveControllerSafetyProfile = "BALANCED",
        adaptiveControllerStaleMaxMinutes = 15,
        adaptiveControllerMaxActions6h = 4,
        adaptiveControllerMaxStepMmol = 0.25,
        patternMinSamplesPerWindow = 40,
        patternMinActiveDaysPerWindow = 7,
        patternLowRateTrigger = 0.12,
        patternHighRateTrigger = 0.18,
        analyticsLookbackDays = 365,
        maxActionsIn6Hours = 3,
        staleDataMaxMinutes = 10,
        exportFolderUri = null
    )
}
