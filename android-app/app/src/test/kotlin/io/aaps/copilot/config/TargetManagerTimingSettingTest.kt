package io.aaps.copilot.config

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.google.common.truth.Truth.assertThat
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TargetManagerTimingSettingTest {
    @get:Rule val folder = TemporaryFolder()

    @Test
    fun updatesOnlyTheSelectedTimingFieldAndPreservesTherapyPolicy() = runTest {
        val store = AppSettingsStore(PreferenceDataStoreFactory.create(
            scope = backgroundScope,
            produceFile = { File(folder.newFolder(), "settings.preferences_pb") }
        ))
        for ((setting, value) in listOf(
            TargetManagerTimingSetting.RETARGET to 15,
            TargetManagerTimingSetting.POST_HYPO to 45,
            TargetManagerTimingSetting.PATTERN to 35,
            TargetManagerTimingSetting.SEGMENT to 25
        )) {
            val before = store.settings.first()
            store.update { it.withTargetManagerTiming(setting, value) }
            val after = store.settings.first()
            val expected = timings(before).toMutableList().apply { this[setting.ordinal] = value }
            assertThat(timings(after)).containsExactlyElementsIn(expected).inOrder()
            assertThat(after.targetManagerMode).isEqualTo(before.targetManagerMode)
            assertThat(after.targetManagerCopilotPriorityEnabled).isEqualTo(before.targetManagerCopilotPriorityEnabled)
            assertThat(after.targetManagerPolicyRevision).isEqualTo(before.targetManagerPolicyRevision)
            assertThat(after.baseTargetSchedule).isEqualTo(before.baseTargetSchedule)
            assertThat(after.safetyMinTargetMmol).isEqualTo(before.safetyMinTargetMmol)
            assertThat(after.safetyMaxTargetMmol).isEqualTo(before.safetyMaxTargetMmol)
            assertThat(after.killSwitch).isEqualTo(before.killSwitch)
            assertThat(after.therapyActionsArmed).isEqualTo(before.therapyActionsArmed)
        }
    }

    @Test
    fun unsupportedTimingNeverMutatesPreferences() = runTest {
        val dataStore = PreferenceDataStoreFactory.create(
            scope = backgroundScope,
            produceFile = { File(folder.newFolder(), "settings.preferences_pb") }
        )
        val store = AppSettingsStore(dataStore)
        for ((setting, value) in listOf(
            TargetManagerTimingSetting.RETARGET to 0,
            TargetManagerTimingSetting.RETARGET to 10,
            TargetManagerTimingSetting.POST_HYPO to -1,
            TargetManagerTimingSetting.PATTERN to 241,
            TargetManagerTimingSetting.SEGMENT to Int.MAX_VALUE
        )) {
            val before = dataStore.data.first()
            val result = runCatching { store.update { it.withTargetManagerTiming(setting, value) } }
            assertThat(result.exceptionOrNull()).isInstanceOf(IllegalArgumentException::class.java)
            assertThat(dataStore.data.first()).isEqualTo(before)
        }
    }

    private fun timings(s: AppSettings) = listOf(
        s.adaptiveControllerRetargetMinutes, s.rulePostHypoCooldownMinutes,
        s.rulePatternCooldownMinutes, s.ruleSegmentCooldownMinutes
    )
}
