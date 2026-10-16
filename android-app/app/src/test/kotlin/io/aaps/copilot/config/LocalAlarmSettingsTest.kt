package io.aaps.copilot.config

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LocalAlarmSettingsTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun consentIsExplicitInstallBoundAndDoesNotChangeTherapy() = runTest {
        val data = PreferenceDataStoreFactory.create(scope = backgroundScope) {
            temporary.newFolder().resolve("alerts.preferences_pb")
        }
        val store = AppSettingsStore(data, "alarm-install")
        val before = store.settings.first()
        assertFalse(before.localAlarmRuntimeEnabled)
        store.update { it.copy(localAlarmRuntimeEnabled = true) }
        assertFalse(store.settings.first().localAlarmRuntimeEnabled)
        assertTrue(store.setLocalAlarmRuntimeEnabled(false, true))
        assertEquals(before.copy(localAlarmRuntimeEnabled = true), store.settings.first())
        store.update { it.copy(localAlarmRuntimeEnabled = false) }
        assertTrue(store.settings.first().localAlarmRuntimeEnabled)
        assertFalse(AppSettingsStore(data, "different-install").settings.first().localAlarmRuntimeEnabled)
        assertFalse(AppSettingsStore(data, null).settings.first().localAlarmRuntimeEnabled)
        assertFalse(AppSettingsStore(data, null).setLocalAlarmRuntimeEnabled(false, true))
        assertTrue(store.setLocalAlarmRuntimeEnabled(true, false))
        assertEquals(before, store.settings.first())
    }

    @Test fun simultaneousAndStaleToggleCannotOverwriteChoice() = runTest {
        val data = PreferenceDataStoreFactory.create(scope = backgroundScope) {
            temporary.newFolder().resolve("alerts.preferences_pb")
        }
        val store = AppSettingsStore(data, "alarm-install")
        val a = async { store.setLocalAlarmRuntimeEnabled(false, true) }
        val b = async { store.setLocalAlarmRuntimeEnabled(false, true) }
        assertEquals(1, listOf(a.await(), b.await()).count { it })
        assertFalse(store.setLocalAlarmRuntimeEnabled(false, false))
        assertTrue(store.settings.first().localAlarmRuntimeEnabled)
    }
}
