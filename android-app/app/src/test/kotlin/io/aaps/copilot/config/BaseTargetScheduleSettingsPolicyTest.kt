package io.aaps.copilot.config

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.google.common.truth.Truth.assertThat
import io.aaps.copilot.domain.target.BaseTargetInterval
import io.aaps.copilot.domain.target.BaseTargetSchedule
import io.aaps.copilot.domain.target.BaseTargetScheduleCodec
import io.aaps.copilot.domain.target.TargetManagerMode
import io.aaps.copilot.domain.predict.IsfRuntimeSourcePreference
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BaseTargetScheduleSettingsPolicyTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val codec = BaseTargetScheduleCodec()

    @Test
    fun targetPolicyRevisionFencesPriorityAndModeAbaChanges() = runTest {
        val harness = newHarness(backgroundScope)
        val initial = harness.store.settings.first().targetManagerPolicyRevision
        harness.store.setTargetManagerCopilotPriorityEnabled(true)
        val enabled = harness.store.settings.first().targetManagerPolicyRevision
        assertThat(enabled).isGreaterThan(initial)
        harness.store.setTargetManagerCopilotPriorityEnabled(false)
        harness.store.setTargetManagerCopilotPriorityEnabled(true)
        assertThat(harness.store.settings.first().targetManagerPolicyRevision).isGreaterThan(enabled)
        val beforePause = harness.store.settings.first().targetManagerPolicyRevision
        harness.store.setTargetManagerModeManually(TargetManagerMode.OFF)
        harness.store.setTargetManagerModeManually(TargetManagerMode.ACTIVE)
        assertThat(harness.store.settings.first().targetManagerPolicyRevision).isGreaterThan(beforePause)
        val beforeGeneric = harness.store.settings.first().targetManagerPolicyRevision
        harness.store.update { it.copy(targetManagerPolicyRevision = 0L) }
        assertThat(harness.store.settings.first().targetManagerPolicyRevision).isEqualTo(beforeGeneric)
    }

    @Test
    fun copilotPriorityRequiresExplicitOptInAndDoesNotChangeManagerMode() = runTest {
        val harness = newHarness(backgroundScope)
        val original = harness.store.settings.first()
        assertThat(original.targetManagerCopilotPriorityEnabled).isFalse()

        harness.store.update { it.copy(targetManagerCopilotPriorityEnabled = true) }
        assertThat(harness.store.settings.first().targetManagerCopilotPriorityEnabled).isFalse()

        harness.store.setTargetManagerCopilotPriorityEnabled(true)
        assertThat(harness.store.settings.first().targetManagerCopilotPriorityEnabled).isTrue()
        assertThat(harness.store.settings.first().targetManagerMode).isEqualTo(original.targetManagerMode)
        assertThat(harness.store.settings.first().baseTargetSchedule).isEqualTo(original.baseTargetSchedule)

        harness.store.setTargetManagerModeManually(TargetManagerMode.OFF)
        assertThat(harness.store.settings.first().targetManagerMode).isEqualTo(TargetManagerMode.OFF)
        harness.store.setTargetManagerCopilotPriorityEnabled(false)
        assertThat(harness.store.settings.first().targetManagerCopilotPriorityEnabled).isFalse()
        assertThat(harness.store.settings.first().targetManagerMode).isEqualTo(TargetManagerMode.OFF)
    }

    @Test
    fun targetManagerModeDefaultsToShadowAndMalformedValueFailsSafe() = runTest {
        val harness = newHarness(backgroundScope)

        assertThat(harness.store.settings.first().targetManagerMode)
            .isEqualTo(TargetManagerMode.SHADOW)

        harness.dataStore.edit { prefs -> prefs[TARGET_MANAGER_MODE_KEY] = "corrupt" }

        assertThat(harness.store.settings.first().targetManagerMode)
            .isEqualTo(TargetManagerMode.SHADOW)
    }

    @Test
    fun runtimeIsfSourceDefaultsPersistsAndMalformedValueFailsSafe() = runTest {
        val harness = newHarness(backgroundScope)

        assertThat(harness.store.settings.first().isfSourcePreference)
            .isEqualTo(IsfRuntimeSourcePreference.EVIDENCE)

        harness.store.beginSensitivitySettingsMutation {
            it.copy(isfSourcePreference = IsfRuntimeSourcePreference.AAPS)
        }

        assertThat(harness.store.settings.first().isfSourcePreference)
            .isEqualTo(IsfRuntimeSourcePreference.AAPS)
        assertThat(harness.dataStore.data.first()[ISF_RUNTIME_SOURCE_KEY])
            .isEqualTo(IsfRuntimeSourcePreference.AAPS.name)

        harness.dataStore.edit { prefs -> prefs[ISF_RUNTIME_SOURCE_KEY] = "corrupt" }

        assertThat(harness.store.settings.first().isfSourcePreference)
            .isEqualTo(IsfRuntimeSourcePreference.EVIDENCE)
    }

    @Test
    fun promotionRejectsMalformedAndOffPersistedModes() = runTest {
        val malformed = newHarness(backgroundScope)
        val malformedSchedule = malformed.store.saveBaseTargetSchedule(schedule(autoEnabled = true))
        malformed.dataStore.edit { prefs -> prefs[TARGET_MANAGER_MODE_KEY] = "corrupt" }

        assertThat(malformed.store.promoteTargetManagerToActive(malformedSchedule.revision)).isFalse()
        assertThat(malformed.dataStore.data.first()[TARGET_MANAGER_MODE_KEY]).isEqualTo("corrupt")

        val off = newHarness(backgroundScope)
        val offSchedule = off.store.saveBaseTargetSchedule(schedule(autoEnabled = true))
        off.dataStore.edit { prefs -> prefs[TARGET_MANAGER_MODE_KEY] = TargetManagerMode.OFF.name }

        assertThat(off.store.promoteTargetManagerToActive(offSchedule.revision)).isFalse()
        assertThat(off.store.settings.first().targetManagerMode).isEqualTo(TargetManagerMode.OFF)
    }

    @Test
    fun genericUpdateCannotChangeTargetManagerMode() = runTest {
        val harness = newHarness(backgroundScope)

        harness.store.update { it.copy(targetManagerMode = TargetManagerMode.ACTIVE) }

        assertThat(harness.store.settings.first().targetManagerMode)
            .isEqualTo(TargetManagerMode.SHADOW)
        assertThat(harness.dataStore.data.first()[TARGET_MANAGER_MODE_KEY]).isNull()
    }

    @Test
    fun explicitManualModePersistsAndBlocksAutomaticPromotion() = runTest {
        val harness = newHarness(backgroundScope)
        val schedule = harness.store.saveBaseTargetSchedule(schedule(autoEnabled = true))

        harness.store.setTargetManagerModeManually(TargetManagerMode.SHADOW)

        assertThat(harness.store.settings.first().targetManagerMode)
            .isEqualTo(TargetManagerMode.SHADOW)
        assertThat(harness.store.settings.first().targetManagerModeManualOverride).isTrue()
        assertThat(harness.store.promoteTargetManagerToActive(schedule.revision)).isFalse()

        harness.store.setTargetManagerModeManually(TargetManagerMode.ACTIVE)

        assertThat(harness.store.settings.first().targetManagerMode)
            .isEqualTo(TargetManagerMode.ACTIVE)
        assertThat(harness.dataStore.data.first()[TARGET_MANAGER_MODE_KEY])
            .isEqualTo(TargetManagerMode.ACTIVE.name)
        assertThat(harness.dataStore.data.first()[TARGET_MANAGER_MANUAL_OVERRIDE_KEY]).isTrue()
    }

    @Test
    fun automaticModeClearsManualOverrideAndReturnsToShadowGate() = runTest {
        val harness = newHarness(backgroundScope)

        harness.store.setTargetManagerModeManually(TargetManagerMode.ACTIVE)
        harness.store.enableAutomaticTargetManagerMode()

        assertThat(harness.store.settings.first().targetManagerMode)
            .isEqualTo(TargetManagerMode.SHADOW)
        assertThat(harness.store.settings.first().targetManagerModeManualOverride).isFalse()
        assertThat(harness.dataStore.data.first()[TARGET_MANAGER_MODE_KEY])
            .isEqualTo(TargetManagerMode.SHADOW.name)
        assertThat(harness.dataStore.data.first()[TARGET_MANAGER_MANUAL_OVERRIDE_KEY]).isFalse()
    }

    @Test
    fun promotionRequiresAutoEnabledCurrentRevisionAndIsOneWay() = runTest {
        val harness = newHarness(backgroundScope)
        val manual = harness.store.saveBaseTargetSchedule(schedule(autoEnabled = false))

        assertThat(harness.store.promoteTargetManagerToActive(manual.revision)).isFalse()
        assertThat(harness.store.settings.first().targetManagerMode)
            .isEqualTo(TargetManagerMode.SHADOW)

        val automatic = harness.store.saveBaseTargetSchedule(manual.copy(autoEnabled = true))

        assertThat(harness.store.promoteTargetManagerToActive(automatic.revision - 1L)).isFalse()
        assertThat(harness.store.promoteTargetManagerToActive(automatic.revision)).isTrue()
        assertThat(harness.store.settings.first().targetManagerMode)
            .isEqualTo(TargetManagerMode.ACTIVE)
        assertThat(harness.store.promoteTargetManagerToActive(automatic.revision)).isFalse()

        harness.store.update { it.copy(targetManagerMode = TargetManagerMode.OFF) }

        assertThat(harness.store.settings.first().targetManagerMode)
            .isEqualTo(TargetManagerMode.ACTIVE)
        assertThat(harness.dataStore.data.first()[TARGET_MANAGER_MODE_KEY])
            .isEqualTo(TargetManagerMode.ACTIVE.name)
    }

    @Test
    fun saveIncrementsRevisionExactlyOnce() = runTest {
        val harness = newHarness(backgroundScope)
        val persisted = schedule(revision = 12L)
        harness.dataStore.edit { prefs ->
            prefs[SCHEDULE_KEY] = codec.encode(persisted)
            prefs[LEGACY_TARGET_KEY] = persisted.defaultTargetMmol
        }
        val candidate = schedule(revision = 12L, defaultTarget = 6.4)

        val saved = harness.store.saveBaseTargetSchedule(candidate)

        assertThat(saved.revision).isEqualTo(13L)
        assertThat(saved.defaultTargetMmol).isEqualTo(6.4)
        assertThat(readPersistedSchedule(harness.dataStore)).isEqualTo(saved)
    }

    @Test
    fun saveRejectsOverlapWithoutMutatingPersistedValue() = runTest {
        val harness = newHarness(backgroundScope)
        harness.store.saveBaseTargetSchedule(schedule(defaultTarget = 6.0))
        val before = harness.dataStore.data.first().asMap()
        val overlapping = schedule(
            revision = 1L,
            intervals = listOf(
                BaseTargetInterval("first", 60, 300, 6.0),
                BaseTargetInterval("second", 240, 420, 6.2)
            )
        )

        val error = captureFailure {
            harness.store.saveBaseTargetSchedule(overlapping)
        }

        assertThat(error).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(harness.dataStore.data.first().asMap()).isEqualTo(before)
    }

    @Test
    fun saveRejectsNonFiniteOrReversedBoundsWithoutMutation() = runTest {
        listOf(
            Double.NaN to 10.0,
            8.0 to 6.0
        ).forEach { (minTarget, maxTarget) ->
            val harness = newHarness(backgroundScope)
            val persisted = schedule(revision = 4L)
            harness.dataStore.edit { prefs ->
                prefs[SCHEDULE_KEY] = codec.encode(persisted)
                prefs[LEGACY_TARGET_KEY] = persisted.defaultTargetMmol
                prefs[SAFETY_MIN_KEY] = minTarget
                prefs[SAFETY_MAX_KEY] = maxTarget
            }
            val before = harness.dataStore.data.first().asMap()

            val error = captureFailure {
                harness.store.saveBaseTargetSchedule(schedule(revision = 4L, defaultTarget = 6.4))
            }

            assertThat(error).isInstanceOf(IllegalArgumentException::class.java)
            assertThat(harness.dataStore.data.first().asMap()).isEqualTo(before)
        }
    }

    @Test
    fun safetyBoundsClampDefaultAndEveryIntervalAndIncrementRevision() = runTest {
        val harness = newHarness(backgroundScope)
        val persisted = schedule(
            revision = 7L,
            defaultTarget = 4.4,
            autoEnabled = true,
            intervals = listOf(
                BaseTargetInterval("low", 0, 360, 4.2),
                BaseTargetInterval("middle", 360, 720, 6.2),
                BaseTargetInterval("high", 720, 1080, 9.8)
            )
        )
        harness.dataStore.edit { prefs ->
            prefs[SCHEDULE_KEY] = codec.encode(persisted)
            prefs[LEGACY_TARGET_KEY] = persisted.defaultTargetMmol
        }

        val saved = harness.store.updateBaseTargetScheduleForBounds(
            minTarget = 5.05,
            maxTarget = 7.04
        )

        assertThat(saved.revision).isEqualTo(8L)
        assertThat(saved.defaultTargetMmol).isEqualTo(5.1)
        assertThat(saved.autoEnabled).isTrue()
        assertThat(saved.intervals).containsExactly(
            BaseTargetInterval("low", 0, 360, 5.1),
            BaseTargetInterval("middle", 360, 720, 6.2),
            BaseTargetInterval("high", 720, 1080, 7.0)
        ).inOrder()
        val persistedPrefs = harness.dataStore.data.first()
        assertThat(persistedPrefs[SAFETY_MIN_KEY]).isEqualTo(5.05)
        assertThat(persistedPrefs[SAFETY_MAX_KEY]).isEqualTo(7.04)
        assertThat(persistedPrefs[LEGACY_TARGET_KEY]).isEqualTo(5.1)
        assertThat(readPersistedSchedule(harness.dataStore)).isEqualTo(saved)
    }

    @Test
    fun transactionalDefaultUpdatePreservesLatestScheduleAndIncrementsOnce() = runTest {
        val harness = newHarness(backgroundScope)
        val original = harness.store.saveBaseTargetSchedule(
            schedule(
                defaultTarget = 6.0,
                autoEnabled = true,
                intervals = listOf(BaseTargetInterval("night", 1320, 360, 6.4))
            )
        )

        val saved = harness.store.updateBaseTargetDefault(6.7)

        assertThat(saved.revision).isEqualTo(original.revision + 1L)
        assertThat(saved.defaultTargetMmol).isEqualTo(6.7)
        assertThat(saved.autoEnabled).isTrue()
        assertThat(saved.intervals).containsExactlyElementsIn(original.intervals).inOrder()
        assertThat(readPersistedSchedule(harness.dataStore)).isEqualTo(saved)
        assertThat(harness.dataStore.data.first()[LEGACY_TARGET_KEY]).isEqualTo(6.7)
    }

    @Test
    fun staleEditorIsRejectedWithoutOverwritingNewerSchedule() = runTest {
        val harness = newHarness(backgroundScope)
        val stale = harness.store.saveBaseTargetSchedule(
            schedule(
                defaultTarget = 6.0,
                autoEnabled = true,
                intervals = listOf(BaseTargetInterval("kept", 60, 300, 6.3))
            )
        )
        val newer = harness.store.updateBaseTargetDefault(6.8)
        val before = harness.dataStore.data.first().asMap()

        val error = captureFailure {
            harness.store.saveBaseTargetSchedule(
                stale.copy(
                    defaultTargetMmol = 5.5,
                    autoEnabled = false,
                    intervals = listOf(BaseTargetInterval("stale", 300, 600, 5.7))
                )
            )
        }

        assertThat(error).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(harness.dataStore.data.first().asMap()).isEqualTo(before)
        assertThat(readPersistedSchedule(harness.dataStore)).isEqualTo(newer)
        assertThat(newer.defaultTargetMmol).isEqualTo(6.8)
        assertThat(newer.autoEnabled).isTrue()
        assertThat(newer.intervals).containsExactly(BaseTargetInterval("kept", 60, 300, 6.3))
    }

    @Test
    fun noBoundSafetyUpdatePreservesBoundsScheduleMirrorAndRevision() = runTest {
        val harness = newHarness(backgroundScope)
        val raw = """
            {
              "schemaVersion": 1,
              "revision": 21,
              "defaultTargetMmol": 6.2,
              "autoEnabled": true,
              "intervals": []
            }
        """.trimIndent()
        harness.dataStore.edit { prefs ->
            prefs[SCHEDULE_KEY] = raw
            prefs[LEGACY_TARGET_KEY] = 6.2
            prefs[SAFETY_MIN_KEY] = 4.5
            prefs[SAFETY_MAX_KEY] = 8.5
        }

        harness.store.updateSafetyLimits(
            maxActionsIn6Hours = 8,
            staleDataMaxMinutes = 25,
            carbAbsorptionMaxAgeMinutes = 100,
            carbComputationMaxGrams = 40.0
        )

        val persisted = harness.dataStore.data.first()
        assertThat(persisted[SCHEDULE_KEY]).isEqualTo(raw)
        assertThat(persisted[LEGACY_TARGET_KEY]).isEqualTo(6.2)
        assertThat(persisted[SAFETY_MIN_KEY]).isEqualTo(4.5)
        assertThat(persisted[SAFETY_MAX_KEY]).isEqualTo(8.5)
        assertThat(readPersistedSchedule(harness.dataStore).revision).isEqualTo(21L)
        assertThat(persisted[MAX_ACTIONS_KEY]).isEqualTo(8)
        assertThat(persisted[STALE_DATA_KEY]).isEqualTo(25)
        assertThat(persisted[CARB_AGE_KEY]).isEqualTo(100)
        assertThat(persisted[CARB_GRAMS_KEY]).isEqualTo(40.0)
    }

    @Test
    fun unchangedBoundsDoNotRewriteScheduleOrIncrementRevision() = runTest {
        val harness = newHarness(backgroundScope)
        val raw = """
            {
              "schemaVersion": 1,
              "revision": 31,
              "defaultTargetMmol": 6.2,
              "autoEnabled": true,
              "intervals": []
            }
        """.trimIndent()
        harness.dataStore.edit { prefs ->
            prefs[SCHEDULE_KEY] = raw
            prefs[LEGACY_TARGET_KEY] = 6.2
            prefs[SAFETY_MIN_KEY] = 4.0
            prefs[SAFETY_MAX_KEY] = 10.0
        }
        val before = harness.dataStore.data.first().asMap()

        val result = harness.store.updateBaseTargetScheduleForBounds(4.0, 10.0)

        assertThat(result.revision).isEqualTo(31L)
        assertThat(harness.dataStore.data.first().asMap()).isEqualTo(before)
    }

    @Test
    fun reversedRequestedBoundsRejectWithoutMutation() = runTest {
        val harness = newHarness(backgroundScope)
        harness.store.saveBaseTargetSchedule(schedule())
        val before = harness.dataStore.data.first().asMap()

        val error = captureFailure {
            harness.store.updateBaseTargetScheduleForBounds(8.0, 6.0)
        }

        assertThat(error).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(harness.dataStore.data.first().asMap()).isEqualTo(before)
    }

    @Test
    fun boundsThatCannotKeepMinimumGapRejectWithoutMutation() = runTest {
        val harness = newHarness(backgroundScope)
        harness.store.saveBaseTargetSchedule(schedule())
        val before = harness.dataStore.data.first().asMap()

        val error = captureFailure {
            harness.store.updateBaseTargetScheduleForBounds(
                minTarget = 5.95,
                maxTarget = 6.0
            )
        }

        assertThat(error).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(harness.dataStore.data.first().asMap()).isEqualTo(before)
    }

    @Test
    fun boundsWithoutRepresentableTenthRejectWithoutMutation() = runTest {
        val harness = newHarness(backgroundScope)
        harness.store.saveBaseTargetSchedule(schedule())
        val before = harness.dataStore.data.first().asMap()

        val error = captureFailure {
            harness.store.updateBaseTargetScheduleForBounds(
                minTarget = 5.91,
                maxTarget = 5.99
            )
        }

        assertThat(error).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(harness.dataStore.data.first().asMap()).isEqualTo(before)
    }

    @Test
    fun readRecoversNarrowPersistedBoundsToDefaultsWithoutMutation() = runTest {
        val harness = newHarness(backgroundScope)
        val persistedSchedule = schedule(
            revision = 9L,
            defaultTarget = 6.2,
            autoEnabled = true,
            intervals = listOf(BaseTargetInterval("morning", 360, 720, 6.4))
        )
        harness.dataStore.edit { prefs ->
            prefs[SCHEDULE_KEY] = codec.encode(persistedSchedule)
            prefs[LEGACY_TARGET_KEY] = 6.3
            prefs[SAFETY_MIN_KEY] = 5.95
            prefs[SAFETY_MAX_KEY] = 6.0
        }
        val before = harness.dataStore.data.first().asMap()

        val settings = harness.store.settings.first()

        assertThat(settings.safetyMinTargetMmol).isEqualTo(4.0)
        assertThat(settings.safetyMaxTargetMmol).isEqualTo(10.0)
        assertThat(settings.baseTargetSchedule).isEqualTo(persistedSchedule)
        assertThat(settings.baseTargetMmol).isEqualTo(6.2)
        assertThat(settings.baseTargetScheduleRecoveryReason).isNull()
        assertThat(harness.dataStore.data.first().asMap()).isEqualTo(before)
    }

    @Test
    fun readRecoversNonOverlappingPersistedBoundsUsingLegacyWithoutMutation() = runTest {
        val harness = newHarness(backgroundScope)
        harness.dataStore.edit { prefs ->
            prefs[LEGACY_TARGET_KEY] = 11.5
            prefs[SAFETY_MIN_KEY] = 11.0
            prefs[SAFETY_MAX_KEY] = 12.0
        }
        val before = harness.dataStore.data.first().asMap()

        val settings = harness.store.settings.first()

        assertThat(settings.safetyMinTargetMmol).isEqualTo(4.0)
        assertThat(settings.safetyMaxTargetMmol).isEqualTo(10.0)
        assertThat(settings.baseTargetSchedule).isEqualTo(BaseTargetSchedule.legacy(10.0))
        assertThat(settings.baseTargetMmol).isEqualTo(10.0)
        assertThat(settings.baseTargetScheduleRecoveryReason)
            .isEqualTo("legacy_schedule_migrated")
        assertThat(harness.dataStore.data.first().asMap()).isEqualTo(before)
    }

    @Test
    fun genericUpdateRejectsInvalidPersistedBoundsBeforeUpdaterWithoutMutation() = runTest {
        val harness = newHarness(backgroundScope)
        harness.dataStore.edit { prefs ->
            prefs[LEGACY_TARGET_KEY] = 6.3
            prefs[SAFETY_MIN_KEY] = 5.95
            prefs[SAFETY_MAX_KEY] = 6.0
        }
        val before = harness.dataStore.data.first().asMap()
        var updaterCalled = false

        val error = captureFailure {
            harness.store.update { current ->
                updaterCalled = true
                current.copy(killSwitch = !current.killSwitch)
            }
        }

        assertThat(error).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(updaterCalled).isFalse()
        assertThat(harness.dataStore.data.first().asMap()).isEqualTo(before)
    }

    @Test
    fun safetyUpdateWithoutBoundsRejectsInvalidPersistedBoundsWithoutMutation() = runTest {
        val harness = newHarness(backgroundScope)
        harness.dataStore.edit { prefs ->
            prefs[LEGACY_TARGET_KEY] = 6.3
            prefs[SAFETY_MIN_KEY] = 5.95
            prefs[SAFETY_MAX_KEY] = 6.0
        }
        val before = harness.dataStore.data.first().asMap()

        val error = captureFailure {
            harness.store.updateSafetyLimits(
                maxActionsIn6Hours = 8,
                staleDataMaxMinutes = 25
            )
        }

        assertThat(error).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(harness.dataStore.data.first().asMap()).isEqualTo(before)
    }

    @Test
    fun boundUpdateRejectsInvalidPersistedBoundsWithoutMutation() = runTest {
        val harness = newHarness(backgroundScope)
        harness.dataStore.edit { prefs ->
            prefs[LEGACY_TARGET_KEY] = 6.3
            prefs[SAFETY_MIN_KEY] = 5.95
            prefs[SAFETY_MAX_KEY] = 6.0
        }
        val before = harness.dataStore.data.first().asMap()

        val error = captureFailure {
            harness.store.updateBaseTargetScheduleForBounds(
                minTarget = 5.0,
                maxTarget = 7.0
            )
        }

        assertThat(error).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(harness.dataStore.data.first().asMap()).isEqualTo(before)
    }

    @Test
    fun oversizedScheduleSaveRejectsWithoutMutation() = runTest {
        val harness = newHarness(backgroundScope)
        val before = harness.dataStore.data.first().asMap()
        val oversized = schedule(
            intervals = listOf(
                BaseTargetInterval(
                    id = "x".repeat(64 * 1024),
                    startMinuteOfDay = 60,
                    endMinuteOfDay = 300,
                    targetMmol = 6.0
                )
            )
        )

        val error = captureFailure {
            harness.store.saveBaseTargetSchedule(oversized)
        }

        assertThat(error).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(harness.dataStore.data.first().asMap()).isEqualTo(before)
    }

    @Test
    fun malformedScheduleAllowsUnrelatedSafetyUpdateWithoutRewrite() = runTest {
        val harness = newHarness(backgroundScope)
        val invalidRaw = """{"schemaVersion":1,"revision":4,"defaultTargetMmol":6.2"""
        harness.dataStore.edit { prefs ->
            prefs[SCHEDULE_KEY] = invalidRaw
            prefs[LEGACY_TARGET_KEY] = 6.3
            prefs[SAFETY_MIN_KEY] = 4.5
            prefs[SAFETY_MAX_KEY] = 8.5
            prefs[POST_HYPO_THRESHOLD_KEY] = 4.7
            prefs[POST_HYPO_TARGET_KEY] = 7.9
        }

        harness.store.updateSafetyLimits(
            maxActionsIn6Hours = 99,
            staleDataMaxMinutes = 1
        )

        val persisted = harness.dataStore.data.first()
        assertThat(persisted[SCHEDULE_KEY]).isEqualTo(invalidRaw)
        assertThat(persisted[LEGACY_TARGET_KEY]).isEqualTo(6.3)
        assertThat(persisted[SAFETY_MIN_KEY]).isEqualTo(4.5)
        assertThat(persisted[SAFETY_MAX_KEY]).isEqualTo(8.5)
        assertThat(persisted[POST_HYPO_THRESHOLD_KEY]).isEqualTo(4.7)
        assertThat(persisted[POST_HYPO_TARGET_KEY]).isEqualTo(7.9)
        assertThat(persisted[MAX_ACTIONS_KEY]).isEqualTo(10)
        assertThat(persisted[STALE_DATA_KEY]).isEqualTo(5)
    }

    @Test
    fun malformedScheduleBlocksActualBoundChangeWithoutMutation() = runTest {
        val harness = newHarness(backgroundScope)
        val invalidRaw = """{"schemaVersion":2,"futurePayload":{"target":6.4}}"""
        harness.dataStore.edit { prefs ->
            prefs[SCHEDULE_KEY] = invalidRaw
            prefs[LEGACY_TARGET_KEY] = 6.3
        }
        val before = harness.dataStore.data.first().asMap()

        val error = captureFailure {
            harness.store.updateSafetyLimits(
                maxActionsIn6Hours = 8,
                staleDataMaxMinutes = 25,
                safetyMinTargetMmol = 5.0,
                safetyMaxTargetMmol = 7.0,
                carbAbsorptionMaxAgeMinutes = 100,
                carbComputationMaxGrams = 40.0
            )
        }

        assertThat(error).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(harness.dataStore.data.first().asMap()).isEqualTo(before)
    }

    @Test
    fun safetyLimitsPersistOneAtomicCoherentState() = runTest {
        val harness = newHarness(backgroundScope)
        harness.store.saveBaseTargetSchedule(
            schedule(
                defaultTarget = 4.4,
                autoEnabled = true,
                intervals = listOf(
                    BaseTargetInterval("low", 0, 360, 4.2),
                    BaseTargetInterval("high", 360, 720, 9.8)
                )
            )
        )
        harness.dataStore.edit { prefs ->
            prefs[POST_HYPO_THRESHOLD_KEY] = 4.2
            prefs[POST_HYPO_TARGET_KEY] = 9.5
        }

        harness.store.updateSafetyLimits(
            maxActionsIn6Hours = 99,
            staleDataMaxMinutes = 1,
            safetyMinTargetMmol = 5.05,
            safetyMaxTargetMmol = 7.04,
            carbAbsorptionMaxAgeMinutes = 999,
            carbComputationMaxGrams = 2.0
        )

        val persisted = harness.dataStore.data.first()
        val settings = harness.store.settings.first()
        val expectedSchedule = schedule(
            revision = 2L,
            defaultTarget = 5.1,
            autoEnabled = true,
            intervals = listOf(
                BaseTargetInterval("low", 0, 360, 5.1),
                BaseTargetInterval("high", 360, 720, 7.0)
            )
        )
        assertThat(persisted[SAFETY_MIN_KEY]).isEqualTo(5.05)
        assertThat(persisted[SAFETY_MAX_KEY]).isEqualTo(7.04)
        assertThat(persisted[LEGACY_TARGET_KEY]).isEqualTo(5.1)
        assertThat(persisted[POST_HYPO_THRESHOLD_KEY]).isEqualTo(5.05)
        assertThat(persisted[POST_HYPO_TARGET_KEY]).isEqualTo(7.04)
        assertThat(persisted[MAX_ACTIONS_KEY]).isEqualTo(10)
        assertThat(persisted[STALE_DATA_KEY]).isEqualTo(5)
        assertThat(persisted[CARB_AGE_KEY]).isEqualTo(180)
        assertThat(persisted[CARB_GRAMS_KEY]).isEqualTo(20.0)
        assertThat(readPersistedSchedule(harness.dataStore)).isEqualTo(expectedSchedule)
        assertThat(settings.baseTargetSchedule).isEqualTo(expectedSchedule)
        assertThat(settings.baseTargetMmol).isEqualTo(5.1)
        assertThat(settings.baseTargetScheduleRecoveryReason).isNull()
    }

    @Test
    fun quantizedBoundsNeverSelectATenthOutsideRawBounds() = runTest {
        val aboveBelow = newHarness(backgroundScope)
        aboveBelow.store.saveBaseTargetSchedule(
            schedule(
                defaultTarget = 5.1,
                intervals = listOf(BaseTargetInterval("edge", 60, 300, 6.1))
            )
        )

        val narrowed = aboveBelow.store.updateBaseTargetScheduleForBounds(
            minTarget = 5.1000000001,
            maxTarget = 6.0999999999
        )

        assertThat(narrowed.defaultTargetMmol).isEqualTo(5.2)
        assertThat(narrowed.intervals.single().targetMmol).isEqualTo(6.0)

        val belowAbove = newHarness(backgroundScope)
        belowAbove.store.saveBaseTargetSchedule(
            schedule(
                defaultTarget = 5.1,
                intervals = listOf(BaseTargetInterval("edge", 60, 300, 6.1))
            )
        )

        val retained = belowAbove.store.updateBaseTargetScheduleForBounds(
            minTarget = 5.0999999999,
            maxTarget = 6.1000000001
        )

        assertThat(retained.defaultTargetMmol).isEqualTo(5.1)
        assertThat(retained.intervals.single().targetMmol).isEqualTo(6.1)
    }

    @Test
    fun revisionExhaustionRejectsEveryScheduleMutationWithoutMutation() = runTest {
        val harness = newHarness(backgroundScope)
        val exhausted = schedule(revision = Long.MAX_VALUE, defaultTarget = 6.0)
        harness.dataStore.edit { prefs ->
            prefs[SCHEDULE_KEY] = codec.encode(exhausted)
            prefs[LEGACY_TARGET_KEY] = exhausted.defaultTargetMmol
        }
        val before = harness.dataStore.data.first().asMap()

        val saveError = captureFailure {
            harness.store.saveBaseTargetSchedule(exhausted.copy(defaultTargetMmol = 6.1))
        }
        val defaultError = captureFailure {
            harness.store.updateBaseTargetDefault(6.2)
        }
        val boundsError = captureFailure {
            harness.store.updateBaseTargetScheduleForBounds(5.0, 7.0)
        }

        assertThat(saveError).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(defaultError).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(boundsError).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(harness.dataStore.data.first().asMap()).isEqualTo(before)
    }

    @Test
    fun genericSettingsUpdateDoesNotEraseOrRewriteScheduleJson() = runTest {
        val harness = newHarness(backgroundScope)
        val staleSettings = harness.store.settings.first()
        val raw = """
            {
              "schemaVersion": 1,
              "revision": 31,
              "defaultTargetMmol": 6.2,
              "autoEnabled": true,
              "intervals": []
            }
        """.trimIndent()
        harness.dataStore.edit { prefs ->
            prefs[SCHEDULE_KEY] = raw
            prefs[LEGACY_TARGET_KEY] = 6.2
        }

        harness.store.update {
            staleSettings.copy(killSwitch = !staleSettings.killSwitch)
        }

        assertThat(harness.dataStore.data.first()[SCHEDULE_KEY]).isEqualTo(raw)
        assertThat(harness.store.settings.first().baseTargetSchedule.revision).isEqualTo(31L)
    }

    @Test
    fun genericSettingsUpdatePreservesNewerBoundsScheduleAndMirrorFromStaleSnapshot() = runTest {
        val harness = newHarness(backgroundScope)
        harness.store.saveBaseTargetSchedule(
            schedule(
                defaultTarget = 7.2,
                autoEnabled = true,
                intervals = listOf(
                    BaseTargetInterval("low", 0, 360, 4.4),
                    BaseTargetInterval("high", 360, 720, 8.8)
                )
            )
        )
        val staleSettings = harness.store.settings.first()
        val tightenedSchedule = harness.store.updateBaseTargetScheduleForBounds(
            minTarget = 5.5,
            maxTarget = 6.5
        )
        val tightenedPrefs = harness.dataStore.data.first()
        val tightenedRaw = tightenedPrefs[SCHEDULE_KEY]
        val tightenedLegacyMirror = tightenedPrefs[LEGACY_TARGET_KEY]

        harness.store.update {
            staleSettings.copy(killSwitch = !staleSettings.killSwitch)
        }

        val persisted = harness.dataStore.data.first()
        val settings = harness.store.settings.first()
        assertThat(persisted[SAFETY_MIN_KEY]).isEqualTo(5.5)
        assertThat(persisted[SAFETY_MAX_KEY]).isEqualTo(6.5)
        assertThat(persisted[SCHEDULE_KEY]).isEqualTo(tightenedRaw)
        assertThat(persisted[LEGACY_TARGET_KEY]).isEqualTo(tightenedLegacyMirror)
        assertThat(settings.safetyMinTargetMmol).isEqualTo(5.5)
        assertThat(settings.safetyMaxTargetMmol).isEqualTo(6.5)
        assertThat(settings.baseTargetSchedule).isEqualTo(tightenedSchedule)
        assertThat(settings.baseTargetSchedule.revision).isEqualTo(2L)
        assertThat(settings.baseTargetMmol).isEqualTo(tightenedSchedule.defaultTargetMmol)
        assertThat(settings.postHypoThresholdMmol).isEqualTo(5.5)
        assertThat(settings.postHypoTargetMmol).isEqualTo(5.5)
        assertThat(settings.killSwitch).isEqualTo(!staleSettings.killSwitch)
    }

    @Test
    fun legacyMirrorEqualsSavedScheduleDefaultTargetMmol() = runTest {
        val harness = newHarness(backgroundScope)

        val saved = harness.store.saveBaseTargetSchedule(
            schedule(revision = 0L, defaultTarget = 6.7)
        )

        assertThat(harness.dataStore.data.first()[LEGACY_TARGET_KEY])
            .isEqualTo(saved.defaultTargetMmol)
        assertThat(harness.store.settings.first().baseTargetMmol)
            .isEqualTo(saved.defaultTargetMmol)
    }

    @Test
    fun recoveryReadPreservesInvalidRawJsonAndForcesAutoOff() = runTest {
        val harness = newHarness(backgroundScope)
        val invalidRaw = """{"schemaVersion":1,"revision":44,"defaultTargetMmol":6.8,"autoEnabled":true,"intervals":[{"id":"bad","startMinuteOfDay":60,"endMinuteOfDay":60,"targetMmol":6.8}]}"""
        harness.dataStore.edit { prefs ->
            prefs[SCHEDULE_KEY] = invalidRaw
            prefs[LEGACY_TARGET_KEY] = 6.3
        }

        val settings = harness.store.settings.first()

        assertThat(settings.baseTargetScheduleRecoveryReason).isEqualTo("invalid_schedule_payload")
        assertThat(settings.baseTargetSchedule).isEqualTo(BaseTargetSchedule.legacy(6.3))
        assertThat(settings.baseTargetSchedule.autoEnabled).isFalse()
        assertThat(settings.baseTargetSchedule.intervals).isEmpty()
        assertThat(settings.baseTargetMmol).isEqualTo(6.3)
        assertThat(harness.dataStore.data.first()[SCHEDULE_KEY]).isEqualTo(invalidRaw)
    }

    private fun newHarness(scope: CoroutineScope): Harness {
        val directory = temporaryFolder.newFolder(UUID.randomUUID().toString())
        val dataStore = PreferenceDataStoreFactory.create(
            scope = scope,
            produceFile = { File(directory, "settings.preferences_pb") }
        )
        return Harness(
            store = AppSettingsStore(dataStore),
            dataStore = dataStore
        )
    }

    private suspend fun readPersistedSchedule(
        dataStore: DataStore<Preferences>
    ): BaseTargetSchedule {
        val raw = dataStore.data.first()[SCHEDULE_KEY]
        return (codec.decode(raw, legacyTarget = 5.5)
            as io.aaps.copilot.domain.target.BaseTargetScheduleDecodeResult.Valid).schedule
    }

    private suspend fun captureFailure(block: suspend () -> Unit): Throwable? {
        return try {
            block()
            null
        } catch (error: Throwable) {
            error
        }
    }

    private fun schedule(
        revision: Long = 0L,
        defaultTarget: Double = 6.0,
        autoEnabled: Boolean = false,
        intervals: List<BaseTargetInterval> = emptyList()
    ): BaseTargetSchedule {
        return BaseTargetSchedule(
            revision = revision,
            defaultTargetMmol = defaultTarget,
            autoEnabled = autoEnabled,
            intervals = intervals
        )
    }

    private data class Harness(
        val store: AppSettingsStore,
        val dataStore: DataStore<Preferences>
    )

    private companion object {
        val SCHEDULE_KEY = stringPreferencesKey("base_target_schedule_json")
        val LEGACY_TARGET_KEY = doublePreferencesKey("base_target_mmol")
        val SAFETY_MIN_KEY = doublePreferencesKey("safety_min_target_mmol")
        val SAFETY_MAX_KEY = doublePreferencesKey("safety_max_target_mmol")
        val POST_HYPO_THRESHOLD_KEY = doublePreferencesKey("post_hypo_threshold_mmol")
        val POST_HYPO_TARGET_KEY = doublePreferencesKey("post_hypo_target_mmol")
        val MAX_ACTIONS_KEY = intPreferencesKey("max_actions_in_6h")
        val STALE_DATA_KEY = intPreferencesKey("stale_data_max_minutes")
        val CARB_AGE_KEY = intPreferencesKey("carb_absorption_max_age_minutes")
        val CARB_GRAMS_KEY = doublePreferencesKey("carb_computation_max_grams")
        val TARGET_MANAGER_MODE_KEY = stringPreferencesKey("target_manager_mode")
        val TARGET_MANAGER_MANUAL_OVERRIDE_KEY = booleanPreferencesKey("target_manager_mode_manual_override")
        val ISF_RUNTIME_SOURCE_KEY = stringPreferencesKey("isf_runtime_source")
    }
}
