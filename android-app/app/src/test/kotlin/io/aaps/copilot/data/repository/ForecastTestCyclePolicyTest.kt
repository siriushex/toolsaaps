package io.aaps.copilot.data.repository

import com.google.common.truth.Truth.assertThat
import io.aaps.copilot.testSensitivityRuntimeSnapshot
import kotlinx.coroutines.test.runTest
import org.junit.Test

class ForecastTestCyclePolicyTest {
    @Test fun minuteTestCannotSendCommandsOrRefreshRemoteInAnyArmingState() = runTest {
        for (armed in listOf(false, true)) for (kill in listOf(false, true)) {
            val policy = AutomationRepository.resolveCyclePolicyStatic(
                AutomationRepository.AutomationCycleIntent.FORECAST_TEST, armed, kill, false)
            assertThat(policy.runCalculations).isTrue()
            assertThat(policy.allowLocalSafetyEvidence).isTrue()
            assertThat(policy.allowActionRepositoryAccess).isFalse()
            assertThat(policy.allowAlertPublication).isFalse()
            assertThat(policy.publishWidgetAfterAcceptance).isTrue()
            AutomationRepository.runRemoteRefreshStatic(policy) { error("remote request") }
            AutomationRepository.runSensitivityMaintenanceStatic(policy,
                { error("training") }, { error("activation") }, { error("maintenance") })
            AutomationRepository.runTherapyStageStatic(policy, { error("carb writer") }) {
                assertThat(it).isFalse()
            }
        }
    }

    @Test fun modeCannotAcquireWriterRightsAfterSettingsRaceAndPowerSaveBlocksAdmission() {
        val intent = AutomationRepository.AutomationCycleIntent.FORECAST_TEST
        val initial = AutomationRepository.resolveCyclePolicyStatic(intent, false, true, false)
        val current = AutomationRepository.restrictCyclePolicyStatic(initial, intent, true, false, false)
        assertThat(current.therapyWritersAllowed).isFalse()
        assertThat(current.runRemoteRefresh).isFalse()
        assertThat(AutomationRepository.resolveCyclePolicyStatic(intent, true, false, true).runCalculations).isFalse()
    }

    @Test fun acceptedTestForecastPublishesUiButNotNormalMaintenance() = runTest {
        var ui = 0
        var widgets = 0
        val intent = AutomationRepository.AutomationCycleIntent.FORECAST_TEST
        AutomationRepository.publishAcceptedCycleStateStatic(intent, testSensitivityRuntimeSnapshot(),
            { ui++ }, { error("normal maintenance") })
        AutomationRepository.publishAcceptedForecastConsumerStatic(intent) { widgets++ }
        assertThat(ui).isEqualTo(1)
        assertThat(widgets).isEqualTo(1)
        assertThat(AutomationRepository.allowsCalibrationMaintenanceStatic(intent)).isFalse()
    }
}
