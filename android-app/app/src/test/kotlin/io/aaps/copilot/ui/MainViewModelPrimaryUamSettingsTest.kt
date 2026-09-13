package io.aaps.copilot.ui

import com.google.common.truth.Truth.assertThat
import io.aaps.copilot.config.AppSettings
import io.aaps.copilot.config.UamExportUiMode
import io.aaps.copilot.domain.predict.InsulinActionProfileId
import io.aaps.copilot.domain.predict.UamExportMode
import io.aaps.copilot.ui.foundation.screens.toUamExportControlUi
import org.junit.Test

class MainViewModelPrimaryUamSettingsTest {

    @Test
    fun primaryOverviewStateReflectsAutoUamSettings() {
        val state = MainUiState().applyPrimaryUamSettingsForUi(
            testSettings().copy(
                enableUamInference = true,
                enableUamExportToAaps = true,
                uamExportMode = UamExportMode.INCREMENTAL,
                dryRunExport = false
            )
        )

        assertThat(state.toUamExportControlUi().mode)
            .isEqualTo(UamExportUiMode.AUTO.name)
    }

    @Test
    fun primaryOverviewStateReflectsObserveUamSettings() {
        val state = MainUiState().applyPrimaryUamSettingsForUi(
            testSettings().copy(
                enableUamInference = true,
                enableUamExportToAaps = true,
                uamExportMode = UamExportMode.INCREMENTAL,
                dryRunExport = true
            )
        )

        assertThat(state.toUamExportControlUi().mode)
            .isEqualTo(UamExportUiMode.OBSERVE.name)
    }

    @Test
    fun primaryOverviewUsesDirectAapsMetricWhenRuntimeRawMetricIsNotPersisted() {
        assertThat(resolvePrimaryAapsMetricValue(rawValue = null, directAapsValue = 2.47))
            .isEqualTo(2.47)
        assertThat(resolvePrimaryAapsMetricValue(rawValue = null, directAapsValue = 10.0))
            .isEqualTo(10.0)
    }

    @Test
    fun primaryOverviewPrefersValidatedRuntimeRawMetric() {
        assertThat(resolvePrimaryAapsMetricValue(rawValue = 2.45, directAapsValue = 2.47))
            .isEqualTo(2.45)
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
