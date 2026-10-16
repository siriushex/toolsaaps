package io.aaps.copilot.config

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LocalObservationSettingsTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun explicitChoiceIsInstallBoundAndDoesNotChangeTherapyOrClinicalSettings() = runTest {
        val data = PreferenceDataStoreFactory.create(scope = backgroundScope) {
            temporary.newFolder().resolve("observation.preferences_pb")
        }
        val store = AppSettingsStore(data, "observation-install")
        val before = store.settings.first()
        assertThat(before.localObservationEnabled).isFalse()
        assertThat(store.setLocalObservationEnabled(false, true)).isTrue()
        assertThat(store.settings.first()).isEqualTo(before.copy(localObservationEnabled = true))
        assertThat(store.isTherapyActionBootstrapEvaluated()).isFalse()
        assertThat(AppSettingsStore(data, "observation-install").settings.first().localObservationEnabled).isTrue()
        assertThat(AppSettingsStore(data, "another-install").settings.first().localObservationEnabled).isFalse()
        assertThat(AppSettingsStore(data, null).settings.first().localObservationEnabled).isFalse()
        assertThat(AppSettingsStore(data, null).setLocalObservationEnabled(false, true)).isFalse()
        assertThat(store.setLocalObservationEnabled(true, false)).isTrue()
        assertThat(store.settings.first()).isEqualTo(before)
    }

    @Test fun generalSettingsAndSetupCannotGrantOrRevokeObservation() = runTest {
        val data = PreferenceDataStoreFactory.create(scope = backgroundScope) {
            temporary.newFolder().resolve("observation.preferences_pb")
        }
        val store = AppSettingsStore(data, "observation-install")
        store.update { it.copy(localObservationEnabled = true, therapyActionsArmed = true) }
        assertThat(store.settings.first().localObservationEnabled).isFalse()
        assertThat(store.settings.first().therapyActionsArmed).isFalse()
        store.setLocalObservationEnabled(false, true)
        store.update { it.copy(localObservationEnabled = false) }
        val progress = store.setupProgress.first()
        store.saveSetupProgress(progress, progress.copy(status = SetupStatus.COMPLETE,
            glucoseSource = SetupGlucoseSource.SENSOR, insulinSource = SetupInsulinSource.AAPS))
        assertThat(store.settings.first().localObservationEnabled).isTrue()
        assertThat(store.settings.first().therapyActionsArmed).isFalse()
    }

    @Test fun simultaneousAndStaleTogglesDoNotOverwriteCurrentChoice() = runTest {
        val data = PreferenceDataStoreFactory.create(scope = backgroundScope) {
            temporary.newFolder().resolve("observation.preferences_pb")
        }
        val store = AppSettingsStore(data, "observation-install")
        val first = async { store.setLocalObservationEnabled(false, true) }
        val second = async { store.setLocalObservationEnabled(false, true) }
        assertThat(listOf(first.await(), second.await()).count { it }).isEqualTo(1)
        assertThat(store.setLocalObservationEnabled(false, false)).isFalse()
        assertThat(store.settings.first().localObservationEnabled).isTrue()
    }

    @Test fun disablingIndependentObservationDoesNotDisarmExistingTherapy() = runTest {
        val data = PreferenceDataStoreFactory.create(scope = backgroundScope) {
            temporary.newFolder().resolve("observation.preferences_pb")
        }
        val store = AppSettingsStore(data, "observation-install")
        try {
            store.setTherapyActionsArmed(true)
            val before = store.settings.first()
            store.setLocalObservationEnabled(false, true)
            store.setLocalObservationEnabled(true, false)
            assertThat(store.settings.first()).isEqualTo(before)
            assertThat(store.isTherapyActionBootstrapEvaluated()).isTrue()
        } finally { store.setTherapyActionsArmed(false) }
    }

    @Test fun delayedLegacyMigrationCannotArmAfterExplicitObservationChoice() = runTest {
        val data = PreferenceDataStoreFactory.create(scope = backgroundScope) {
            temporary.newFolder().resolve("observation.preferences_pb")
        }
        val store = AppSettingsStore(data, "observation-install")
        try {
            store.setLocalObservationEnabled(false, true)
            assertThat(store.tryAutoArmTherapyActionsMigration())
                .isEqualTo(TherapyActionBootstrapMigrationResult.OBSERVATION_ONLY)
            assertThat(store.settings.first().therapyActionsArmed).isFalse()
            assertThat(store.isTherapyActionBootstrapEvaluated()).isFalse()
            store.setLocalObservationEnabled(true, false)
            assertThat(store.tryAutoArmTherapyActionsMigration())
                .isEqualTo(TherapyActionBootstrapMigrationResult.OBSERVATION_ONLY)
            assertThat(store.settings.first().therapyActionsArmed).isFalse()
            store.setLocalObservationEnabled(false, true)
            assertThat(store.setTherapyActionsArmed(true)).isTrue()
            assertThat(store.settings.first().therapyActionsArmed).isTrue()
            assertThat(store.settings.first().localObservationEnabled).isTrue()
        } finally { store.setTherapyActionsArmed(false) }
    }
}
