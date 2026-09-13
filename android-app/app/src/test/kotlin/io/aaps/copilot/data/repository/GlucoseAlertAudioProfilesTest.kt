package io.aaps.copilot.data.repository

import com.google.common.truth.Truth.assertThat
import io.aaps.copilot.config.AppSettings
import io.aaps.copilot.domain.predict.InsulinActionProfileId
import io.aaps.copilot.domain.predict.UamExportMode
import org.junit.Test

class GlucoseAlertAudioProfilesTest {

    @Test
    fun resolveSoft_usesElkCreekDefaults() {
        val spec = GlucoseAlertAudioProfiles.resolve(
            settings = testSettings(),
            slot = GlucoseAlertAudioSlot.SOFT
        )

        assertThat(spec.label).isEqualTo("Elk Creek.mp3")
        assertThat(spec.startMs).isEqualTo(32_000)
        assertThat(spec.durationMs).isEqualTo(18_000)
        assertThat(spec.valid).isTrue()
        assertThat(spec.fallbackUsed).isFalse()
    }

    @Test
    fun resolveCriticalProfiles_useNightAndSnowDefaults() {
        val settings = testSettings()

        val primary = GlucoseAlertAudioProfiles.resolve(settings, GlucoseAlertAudioSlot.CRITICAL_PRIMARY)
        val secondary = GlucoseAlertAudioProfiles.resolve(settings, GlucoseAlertAudioSlot.CRITICAL_SECONDARY)

        assertThat(primary.label).isEqualTo("Night Waltz.mp3")
        assertThat(primary.startMs).isEqualTo(42_000)
        assertThat(primary.durationMs).isEqualTo(20_000)
        assertThat(secondary.label).isEqualTo("Snowbirds.mp3")
        assertThat(secondary.startMs).isEqualTo(36_000)
        assertThat(secondary.durationMs).isEqualTo(20_000)
    }

    @Test
    fun invalidSoftWindow_fallsBackToRecommended() {
        val settings = testSettings().copy(
            softAlertAudioStartMs = 200_000,
            softAlertAudioDurationMs = 18_000
        )

        val spec = GlucoseAlertAudioProfiles.resolve(settings, GlucoseAlertAudioSlot.SOFT)

        assertThat(spec.label).isEqualTo("Elk Creek.mp3")
        assertThat(spec.startMs).isEqualTo(32_000)
        assertThat(spec.durationMs).isEqualTo(18_000)
        assertThat(spec.valid).isFalse()
        assertThat(spec.fallbackUsed).isTrue()
    }

    @Test
    fun customSoftUri_preservesStoredSourceAndDisplayName() {
        val settings = testSettings().copy(
            softAlertAudioUri = "content://alerts/elk",
            softAlertAudioDisplayName = "Custom Elk.mp3",
            softAlertAudioStartMs = 25_000,
            softAlertAudioDurationMs = 20_000
        )

        assertThat(GlucoseAlertAudioProfiles.requestedUri(settings, GlucoseAlertAudioSlot.SOFT)).isEqualTo("content://alerts/elk")
        assertThat(GlucoseAlertAudioProfiles.requestedDisplayName(settings, GlucoseAlertAudioSlot.SOFT)).isEqualTo("Custom Elk.mp3")
        assertThat(GlucoseAlertAudioProfiles.displayLabel(settings, GlucoseAlertAudioSlot.SOFT)).isEqualTo("Custom Elk.mp3")
    }

    @Test
    fun invalidCustomSoftWindow_stillShowsCustomLabelButFallsBackAtRuntime() {
        val settings = testSettings().copy(
            softAlertAudioUri = "content://alerts/elk",
            softAlertAudioDisplayName = "Custom Elk.mp3",
            softAlertAudioStartMs = -1,
            softAlertAudioDurationMs = 20_000
        )

        assertThat(
            GlucoseAlertAudioProfiles.isValid(
                slot = GlucoseAlertAudioSlot.SOFT,
                startMs = settings.softAlertAudioStartMs,
                durationMs = settings.softAlertAudioDurationMs
            )
        ).isFalse()
        assertThat(GlucoseAlertAudioProfiles.displayLabel(settings, GlucoseAlertAudioSlot.SOFT)).isEqualTo("Custom Elk.mp3")
    }

    @Test
    fun durationWindow_validationRequires15to30Seconds() {
        assertThat(
            GlucoseAlertAudioProfiles.isValid(
                slot = GlucoseAlertAudioSlot.SOFT,
                startMs = 32_000,
                durationMs = 14_000
            )
        ).isFalse()

        assertThat(
            GlucoseAlertAudioProfiles.isValid(
                slot = GlucoseAlertAudioSlot.SOFT,
                startMs = 32_000,
                durationMs = 31_000
            )
        ).isFalse()

        assertThat(
            GlucoseAlertAudioProfiles.isValid(
                slot = GlucoseAlertAudioSlot.SOFT,
                startMs = 32_000,
                durationMs = 18_000
            )
        ).isTrue()
    }

    private fun testSettings(): AppSettings {
        return AppSettings(
            nightscoutUrl = "",
            apiSecret = "",
            cloudBaseUrl = "",
            killSwitch = false,
            rootExperimentalEnabled = false,
            localBroadcastIngestEnabled = true,
            strictBroadcastSenderValidation = false,
            localNightscoutEnabled = true,
            localNightscoutPort = 17582,
            localCommandFallbackEnabled = true,
            localCommandPackage = "info.nightscout.androidaps",
            localCommandAction = "io.aaps.copilot.ACTION_COMMAND",
            insulinProfileId = InsulinActionProfileId.FIASP.name,
            enableUamInference = true,
            enableUamBoost = true,
            enableUamExportToAaps = false,
            uamExportMode = UamExportMode.OFF,
            dryRunExport = true,
            baseTargetMmol = 5.5,
            postHypoThresholdMmol = 3.0,
            postHypoDeltaThresholdMmol5m = 0.2,
            postHypoTargetMmol = 4.4,
            postHypoDurationMinutes = 90,
            postHypoLookbackMinutes = 240,
            rulePostHypoEnabled = true,
            rulePatternEnabled = true,
            ruleSegmentEnabled = true,
            adaptiveControllerEnabled = true,
            rulePostHypoPriority = 50,
            rulePatternPriority = 40,
            ruleSegmentPriority = 30,
            adaptiveControllerPriority = 60,
            rulePostHypoCooldownMinutes = 30,
            rulePatternCooldownMinutes = 60,
            ruleSegmentCooldownMinutes = 60,
            adaptiveControllerRetargetMinutes = 5,
            adaptiveControllerSafetyProfile = "default",
            adaptiveControllerStaleMaxMinutes = 20,
            adaptiveControllerMaxActions6h = 24,
            adaptiveControllerMaxStepMmol = 0.6,
            patternMinSamplesPerWindow = 12,
            patternMinActiveDaysPerWindow = 3,
            patternLowRateTrigger = 0.25,
            patternHighRateTrigger = 0.25,
            analyticsLookbackDays = 30,
            maxActionsIn6Hours = 24,
            staleDataMaxMinutes = 20,
            exportFolderUri = null
        )
    }
}
