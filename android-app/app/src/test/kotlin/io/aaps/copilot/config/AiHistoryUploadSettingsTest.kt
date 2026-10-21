package io.aaps.copilot.config

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AiHistoryUploadSettingsTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun intervalsAreClosedAndStoredInvalidValuesUseTheDefault() {
        assertEquals(listOf(60, 120, 180, 240), AiHistoryUploadSettings.INTERVALS)
        for (minutes in listOf(-1, 0, 59, 61, 90, 241, Int.MAX_VALUE)) {
            assertTrue(runCatching { AiHistoryUploadSettings(intervalMinutes = minutes) }.isFailure)
            assertEquals(120, AiHistoryUploadSettings.fromStored(false, minutes).intervalMinutes)
        }
        assertFalse(AiHistoryUploadSettings.fromStored(false, 240).enabled)
        assertEquals(AiHistoryUploadSettings(), AiHistoryUploadSettings.fromStored(null, null))
    }

    @Test fun settingsSurviveRecreationWithoutChangingTherapy() = runTest {
        val data = PreferenceDataStoreFactory.create(scope = backgroundScope,
            produceFile = { temporary.root.resolve("settings.preferences_pb") })
        val store = AppSettingsStore(data)
        val original = store.settings.first()
        for (minutes in AiHistoryUploadSettings.INTERVALS) {
            val changed = AiHistoryUploadSettings(enabled = false, intervalMinutes = minutes)
            store.setAiHistoryUploadEnabled(false)
            store.setAiHistoryUploadInterval(minutes)
            val restored = AppSettingsStore(data).settings.first()
            assertEquals(changed, restored.aiHistoryUpload)
            assertEquals(original.sensitivitySettingsRevision, restored.sensitivitySettingsRevision)
            assertEquals(original.therapyActionsArmed, restored.therapyActionsArmed)
            assertEquals(original.insulinProfileId, restored.insulinProfileId)
            assertEquals(original.baseTargetMmol, restored.baseTargetMmol, 0.0)
            assertEquals(original, restored.copy(aiHistoryUpload = original.aiHistoryUpload))
        }
        assertTrue(runCatching { store.setAiHistoryUploadInterval(5) }.isFailure)
        assertEquals(240, store.settings.first().aiHistoryUpload.intervalMinutes)
        data.edit { it[intPreferencesKey("ai_history_upload_interval_minutes")] = 5 }
        assertEquals(AiHistoryUploadSettings(false, 120), store.settings.first().aiHistoryUpload)
    }
}
