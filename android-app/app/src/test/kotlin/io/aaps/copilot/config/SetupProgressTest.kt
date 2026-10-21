package io.aaps.copilot.config

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SetupProgressTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun newInstallOffersSetupButUpdateAndUnknownDoNotInterruptExistingUsers() {
        assertThat(offerSetupForInstall(100, 100)).isTrue()
        assertThat(offerSetupForInstall(100, 200)).isFalse()
        assertThat(offerSetupForInstall(0, 0)).isFalse()
        assertThat(offerSetupForInstall(100, 0)).isFalse()
    }

    @Test fun everyEquipmentCombinationSavesAndResumesWithoutChangingAnyClinicalSettings() = runTest {
        val data = PreferenceDataStoreFactory.create(scope = backgroundScope) {
            temporary.newFolder().resolve("setup.preferences_pb")
        }
        val store = AppSettingsStore(data, "setup-install", offerInitialSetup = true)
        val settings = store.settings.first()
        assertThat(store.setupProgress.first().status).isEqualTo(SetupStatus.NOT_STARTED)
        for (glucose in SetupGlucoseSource.entries) for (insulin in SetupInsulinSource.entries) {
            val before = store.setupProgress.first()
            val next = before.copy(status = SetupStatus.IN_PROGRESS, step = SetupStep.PROFILE,
                glucoseSource = glucose, insulinSource = insulin)
            assertThat(store.saveSetupProgress(before, next)).isTrue()
            val reopened = AppSettingsStore(data, "setup-install", offerInitialSetup = false)
            assertThat(reopened.setupProgress.first()).isEqualTo(next)
            assertThat(reopened.setupProgress.first().shouldOffer).isTrue()
            assertThat(reopened.settings.first()).isEqualTo(settings)
        }
    }

    @Test fun deferredAndCompletedProgressRemainSavedAcrossGeneralSettingsWrites() = runTest {
        val file = temporary.newFolder().resolve("setup.preferences_pb")
        val data = PreferenceDataStoreFactory.create(scope = backgroundScope) { file }
        val store = AppSettingsStore(data, "setup-install", true)
        for (status in listOf(SetupStatus.DEFERRED, SetupStatus.COMPLETE)) {
            val before = store.setupProgress.first()
            val next = before.copy(status = status, step = SetupStep.REVIEW, insulinSource = SetupInsulinSource.AAPS)
            assertThat(store.saveSetupProgress(before, next)).isTrue()
            store.update { it.copy(showEventsOnGraph = !it.showEventsOnGraph) }
            assertThat(store.setupProgress.first()).isEqualTo(next)
            assertThat(store.setupProgress.first().shouldOffer).isFalse()
            assertThat(store.settings.first().therapyActionsArmed).isFalse()
        }
    }

    @Test fun staleSaveCannotOverwriteNewerEquipmentOrProgress() = runTest {
        val file = temporary.newFolder().resolve("setup.preferences_pb")
        val data = PreferenceDataStoreFactory.create(scope = backgroundScope) { file }
        val store = AppSettingsStore(data)
        val initial = store.setupProgress.first()
        assertThat(initial.status).isEqualTo(SetupStatus.LEGACY)
        val next = initial.moveTo(SetupStep.DATA)
        assertThat(store.saveSetupProgress(initial, next)).isTrue()
        assertThat(store.saveSetupProgress(initial, initial.copy(status = SetupStatus.COMPLETE))).isFalse()
        assertThat(store.setupProgress.first()).isEqualTo(next)
    }

    @Test fun existingArmedSettingsRemainArmedWhenChecklistSaysNoPump() = runTest {
        val file = temporary.newFolder().resolve("setup.preferences_pb")
        val data = PreferenceDataStoreFactory.create(scope = backgroundScope) { file }
        val store = AppSettingsStore(data, "setup-install")
        store.setTherapyActionsArmed(true)
        val beforeSettings = store.settings.first()
        val before = store.setupProgress.first()
        store.saveSetupProgress(before, before.copy(status = SetupStatus.COMPLETE, insulinSource = SetupInsulinSource.NONE))
        assertThat(store.settings.first()).isEqualTo(beforeSettings)
        assertThat(store.isTherapyActionBootstrapEvaluated()).isTrue()
        store.setTherapyActionsArmed(false)
    }

    @Test fun unknownChecklistValuesDoNotInventHardwareOrAuthorizeTherapy() = runTest {
        val file = temporary.newFolder().resolve("setup.preferences_pb")
        val data = PreferenceDataStoreFactory.create(scope = backgroundScope) { file }
        data.edit { prefs ->
            listOf("setup_step_v1", "setup_status_v1", "setup_glucose_v1", "setup_insulin_v1")
                .forEach { prefs[stringPreferencesKey(it)] = "FUTURE_VALUE" }
        }
        val store = AppSettingsStore(data)
        assertThat(store.setupProgress.first()).isEqualTo(SetupProgress())
        assertThat(store.settings.first().therapyActionsArmed).isFalse()
    }

    @Test fun onlyUndecidedLegacyStartupSuggestsTherapyAndDeclineIsRespected() {
        for (status in SetupStatus.entries) for (armed in listOf(false, true)) for (evaluated in listOf(false, true)) {
            assertThat(shouldShowLegacyTherapyBootstrap(SetupProgress(status = status), armed, evaluated))
                .isEqualTo(status == SetupStatus.LEGACY && !armed && !evaluated)
        }
    }

    @Test fun setupDoesNotTakeOverAlertDeepLinksOrAnAlreadyChosenScreen() {
        val setup = SetupProgress(status = SetupStatus.IN_PROGRESS)
        assertThat(shouldAutoOpenSetup(setup, true, false, false)).isTrue()
        assertThat(shouldAutoOpenSetup(setup, false, false, false)).isFalse()
        assertThat(shouldAutoOpenSetup(setup, true, true, false)).isFalse()
        assertThat(shouldAutoOpenSetup(setup, true, false, true)).isFalse()
        assertThat(shouldAutoOpenSetup(setup.copy(status = SetupStatus.COMPLETE), true, false, false)).isFalse()
    }
}
