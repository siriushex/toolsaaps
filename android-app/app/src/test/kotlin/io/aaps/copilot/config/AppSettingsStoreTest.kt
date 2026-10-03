package io.aaps.copilot.config

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.google.common.truth.Truth.assertThat
import io.aaps.copilot.domain.profile.ActivityProfile
import io.aaps.copilot.domain.profile.ActivityProfileMode
import io.aaps.copilot.domain.profile.CalorieGoalMode
import io.aaps.copilot.domain.profile.EnergyProfileSettings
import io.aaps.copilot.domain.profile.FoodProfileMode
import io.aaps.copilot.domain.profile.MealAbsorptionProfile
import io.aaps.copilot.domain.profile.PhysiologicalSex
import io.aaps.copilot.domain.predict.SensitivityRuntimeSettingsIdentity
import io.aaps.copilot.domain.predict.SensitivitySourcePreference
import io.aaps.copilot.domain.predict.SensitivityMetricKind
import io.aaps.copilot.domain.predict.UamExportMode
import io.aaps.copilot.data.repository.resolveAlertAiAnalysisSettings
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class AppSettingsStoreTest {

    @Test
    fun mealPortionDefaultsAndEditsSurviveStoreRecreation() = runTest {
        val harness = newHarness(backgroundScope)
        val initial = harness.store.settings.first()
        assertThat(initial.mealPortions).isEqualTo(io.aaps.copilot.domain.nutrition.MealPortionSettings())
        val changed = initial.mealPortions.copy(
            small = io.aaps.copilot.domain.nutrition.MealPortionRange(6.0, 14.0, 9.0),
            showCalories = true
        )
        harness.store.setMealPortionSettings(changed)
        val recreated = AppSettingsStore(harness.dataStore, "test-install-id-v1").settings.first()
        assertThat(recreated.mealPortions).isEqualTo(changed)
        assertThat(recreated.carbComputationMaxGrams).isEqualTo(initial.carbComputationMaxGrams)
        assertThat(recreated.uamMaxTotalG).isEqualTo(initial.uamMaxTotalG)
        assertThat(recreated.sensitivitySettingsRevision).isEqualTo(initial.sensitivitySettingsRevision)
    }

    @Test
    fun invalidPortionsRollBackEntireSettingsUpdate() = runTest {
        val harness = newHarness(backgroundScope)
        val before = harness.store.settings.first()
        val invalid = before.mealPortions.copy(
            small = io.aaps.copilot.domain.nutrition.MealPortionRange(7.0, 30.0, 10.0)
        )
        val failure = runCatching {
            harness.store.update { it.copy(mealPortions = invalid, killSwitch = !it.killSwitch) }
        }.exceptionOrNull()
        assertThat(failure).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(harness.store.settings.first()).isEqualTo(before)
    }

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun localNightscoutEnablementRequiresPersistedLegacyMigrationAcknowledgment() = runTest {
        val harness = newHarness(backgroundScope)

        val initial = harness.store.settings.first()
        assertThat(initial.localNightscoutLegacyMigrationAcknowledged).isFalse()
        assertThat(initial.localNightscoutEnabled).isFalse()

        harness.store.update {
            it.copy(
                localNightscoutLegacyMigrationAcknowledged = true,
                localNightscoutEnabled = true
            )
        }

        val recreated = AppSettingsStore(harness.dataStore, "test-install-id-v1")
            .settings.first()
        assertThat(recreated.localNightscoutLegacyMigrationAcknowledged).isTrue()
        assertThat(recreated.localNightscoutEnabled).isTrue()
    }

    @Test
    fun showEventsOnGraphRoundTripsAndSurvivesStoreRecreation() = runTest {
        val harness = newHarness(backgroundScope)

        assertThat(harness.store.settings.first().showEventsOnGraph).isTrue()
        harness.store.update { it.copy(showEventsOnGraph = false) }
        assertThat(harness.store.settings.first().showEventsOnGraph).isFalse()

        val recreated = AppSettingsStore(harness.dataStore, "test-install-id-v1")
        assertThat(recreated.settings.first().showEventsOnGraph).isFalse()
    }

    @Test
    fun energyProfileDefaultsAreDisabledWithAiSharingEnabled() = runTest {
        val harness = newHarness(backgroundScope)

        assertThat(harness.store.settings.first().energyProfile).isEqualTo(EnergyProfileSettings())
    }

    @Test
    fun energyProfileSettingsRoundTripAtomicallyWithoutMutatingOtherSettings() = runTest {
        val harness = newHarness(backgroundScope)
        harness.store.update { it.copy(killSwitch = true) }
        val value = EnergyProfileSettings(
            enabled = true,
            forecastActivityInfluenceEnabled = true,
            birthDateEpochDay = 7_777L,
            physiologicalSex = PhysiologicalSex.MALE,
            heightCm = 181.5,
            weightKg = 82.25,
            foodProfileMode = FoodProfileMode.MANUAL,
            manualFoodProfile = MealAbsorptionProfile.FAT_PROTEIN,
            activityProfileMode = ActivityProfileMode.MANUAL,
            manualActivityProfile = ActivityProfile.HIGH,
            calorieGoalMode = CalorieGoalMode.MANUAL_CLINICIAN,
            manualCalorieTargetKcal = 2_400,
            shareProfileWithAi = false
        )

        val mutation = harness.store.beginSensitivitySettingsMutation { current ->
            current.copy(
                energyProfile = current.energyProfile.copy(
                    physiologicalSex = value.physiologicalSex
                )
            )
        }
        assertThat(mutation.changed).isTrue()
        harness.store.setEnergyProfileSettings(value)

        assertThat(harness.store.settings.first().energyProfile).isEqualTo(value)
        assertThat(harness.store.settings.first().killSwitch).isTrue()
        assertThat(AppSettingsStore(harness.dataStore).settings.first().energyProfile).isEqualTo(value)
        val stored = harness.dataStore.data.first()
        assertThat(stored[ENERGY_PROFILE_ENABLED_KEY]).isTrue()
        assertThat(stored[ENERGY_PROFILE_FORECAST_ACTIVITY_INFLUENCE_ENABLED_KEY]).isTrue()
        assertThat(stored[BIRTH_DATE_EPOCH_DAY_KEY]).isEqualTo(7_777L)
        assertThat(stored[PHYSIOLOGICAL_SEX_KEY]).isEqualTo(PhysiologicalSex.MALE.name)
        assertThat(stored[HEIGHT_CM_KEY]).isEqualTo(181.5)
        assertThat(stored[WEIGHT_KG_KEY]).isEqualTo(82.25)
        assertThat(stored[FOOD_PROFILE_MODE_KEY]).isEqualTo(FoodProfileMode.MANUAL.name)
        assertThat(stored[MANUAL_FOOD_PROFILE_KEY])
            .isEqualTo(MealAbsorptionProfile.FAT_PROTEIN.name)
        assertThat(stored[ACTIVITY_PROFILE_MODE_KEY]).isEqualTo(ActivityProfileMode.MANUAL.name)
        assertThat(stored[MANUAL_ACTIVITY_PROFILE_KEY]).isEqualTo(ActivityProfile.HIGH.name)
        assertThat(stored[CALORIE_GOAL_MODE_KEY]).isEqualTo(CalorieGoalMode.MANUAL_CLINICIAN.name)
        assertThat(stored[MANUAL_CALORIE_TARGET_KEY]).isEqualTo(2_400)
        assertThat(stored[SHARE_PROFILE_WITH_AI_KEY]).isFalse()
    }

    @Test
    fun specialEnergyProfileSetterCannotChangePhysiologicalSexOutsideCycleLease() = runTest {
        val harness = newHarness(backgroundScope)

        val failure = runCatching {
            harness.store.setEnergyProfileSettings(
                harness.store.settings.first().energyProfile.copy(
                    physiologicalSex = PhysiologicalSex.FEMALE
                )
            )
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(IllegalArgumentException::class.java)
        val current = harness.store.settings.first()
        assertThat(current.energyProfile.physiologicalSex)
            .isEqualTo(PhysiologicalSex.UNSPECIFIED)
        assertThat(current.sensitivitySettingsRevision).isEqualTo(0L)
    }

    @Test
    fun energyProfileSettingsRemoveNullOptionalKeys() = runTest {
        val harness = newHarness(backgroundScope)
        harness.store.setEnergyProfileSettings(
            EnergyProfileSettings(
                birthDateEpochDay = 7_777L,
                heightCm = 181.5,
                weightKg = 82.25,
                manualCalorieTargetKcal = 2_400
            )
        )

        harness.store.setEnergyProfileSettings(EnergyProfileSettings())

        val stored = harness.dataStore.data.first()
        assertThat(stored[BIRTH_DATE_EPOCH_DAY_KEY]).isNull()
        assertThat(stored[HEIGHT_CM_KEY]).isNull()
        assertThat(stored[WEIGHT_KG_KEY]).isNull()
        assertThat(stored[MANUAL_CALORIE_TARGET_KEY]).isNull()
    }

    @Test
    fun unknownStoredEnergyProfileEnumsDecodeToDocumentedDefaults() = runTest {
        val harness = newHarness(backgroundScope)
        harness.dataStore.edit { prefs ->
            prefs[PHYSIOLOGICAL_SEX_KEY] = "unknown"
            prefs[FOOD_PROFILE_MODE_KEY] = "unknown"
            prefs[MANUAL_FOOD_PROFILE_KEY] = "unknown"
            prefs[ACTIVITY_PROFILE_MODE_KEY] = "unknown"
            prefs[MANUAL_ACTIVITY_PROFILE_KEY] = "unknown"
            prefs[CALORIE_GOAL_MODE_KEY] = "unknown"
        }

        val decoded = harness.store.settings.first().energyProfile

        assertThat(decoded.physiologicalSex).isEqualTo(PhysiologicalSex.UNSPECIFIED)
        assertThat(decoded.foodProfileMode).isEqualTo(FoodProfileMode.AUTO)
        assertThat(decoded.manualFoodProfile).isEqualTo(MealAbsorptionProfile.MIXED)
        assertThat(decoded.activityProfileMode).isEqualTo(ActivityProfileMode.AUTO)
        assertThat(decoded.manualActivityProfile).isEqualTo(ActivityProfile.MODERATE)
        assertThat(decoded.calorieGoalMode).isEqualTo(CalorieGoalMode.OFF)
    }

    @Test
    fun therapyActionsRemainBlockedUntilExplicitForegroundArmPersists() = runTest {
        val harness = newHarness(backgroundScope)

        assertThat(harness.store.settings.first().therapyActionsArmed).isFalse()

        assertThat(harness.store.setTherapyActionsArmed(true)).isTrue()

        assertThat(harness.store.settings.first().therapyActionsArmed).isTrue()
        assertThat(AppSettingsStore(harness.dataStore).settings.first().therapyActionsArmed).isTrue()
    }

    @Test
    fun restoredArmedPreferenceDoesNotArmDifferentInstallation() = runTest {
        val harness = newHarness(backgroundScope, currentInstallId = "install-a")
        assertThat(harness.store.setTherapyActionsArmed(true)).isTrue()

        val restoredStore = AppSettingsStore(
            dataStore = harness.dataStore,
            currentInstallId = "install-b"
        )

        assertThat(restoredStore.settings.first().therapyActionsArmed).isFalse()
    }

    @Test
    fun missingSecureInstallIdentityCannotBeArmed() = runTest {
        val harness = newHarness(backgroundScope, currentInstallId = null)

        assertThat(harness.store.setTherapyActionsArmed(true)).isFalse()
        assertThat(harness.store.settings.first().therapyActionsArmed).isFalse()
    }

    @Test
    fun genericSettingsUpdateCannotBypassBootstrapDecision() = runTest {
        val harness = newHarness(backgroundScope)

        harness.store.update { it.copy(therapyActionsArmed = true) }

        assertThat(harness.store.settings.first().therapyActionsArmed).isFalse()
        assertThat(harness.store.isTherapyActionBootstrapEvaluated()).isFalse()
    }

    @Test
    fun explicitKeepBlockedDecisionPersistsForCurrentInstallation() = runTest {
        val harness = newHarness(backgroundScope)

        assertThat(harness.store.setTherapyActionsArmed(false)).isFalse()

        assertThat(harness.store.settings.first().therapyActionsArmed).isFalse()
        assertThat(harness.store.isTherapyActionBootstrapEvaluated()).isTrue()
    }

    @Test
    fun migrationCompareAndSetCannotOverrideExplicitBlockedDecision() = runTest {
        val harness = newHarness(backgroundScope)
        harness.store.setTherapyActionsArmed(false)

        val result = harness.store.tryAutoArmTherapyActionsMigration()

        assertThat(result).isEqualTo(TherapyActionBootstrapMigrationResult.ALREADY_DECIDED)
        assertThat(harness.store.settings.first().therapyActionsArmed).isFalse()
    }

    @Test
    fun migrationCompareAndSetArmsOnlyUndecidedCurrentInstallation() = runTest {
        val harness = newHarness(backgroundScope)

        val result = harness.store.tryAutoArmTherapyActionsMigration()

        assertThat(result).isEqualTo(TherapyActionBootstrapMigrationResult.ARMED)
        assertThat(harness.store.settings.first().therapyActionsArmed).isTrue()
    }

    @Test
    fun leasedCommandsPersistIndependentIsfAndCrSourcesWithMonotonicRevision() = runTest {
        val harness = newHarness(backgroundScope)

        assertThat(harness.store.settings.first().isfSourcePreference)
            .isEqualTo(SensitivitySourcePreference.EVIDENCE)
        assertThat(harness.store.settings.first().crSourcePreference)
            .isEqualTo(SensitivitySourcePreference.EVIDENCE)
        assertThat(harness.store.settings.first().sensitivitySettingsRevision).isEqualTo(0L)

        harness.store.beginSensitivitySettingsMutation {
            it.copy(isfSourcePreference = SensitivitySourcePreference.AAPS)
        }
        harness.store.beginSensitivitySettingsMutation {
            it.copy(crSourcePreference = SensitivitySourcePreference.COPILOT)
        }

        val readBack = harness.store.settings.first()
        assertThat(readBack.isfSourcePreference)
            .isEqualTo(SensitivitySourcePreference.AAPS)
        assertThat(readBack.crSourcePreference)
            .isEqualTo(SensitivitySourcePreference.COPILOT)
        assertThat(readBack.sensitivitySettingsRevision).isEqualTo(2L)
        assertThat(harness.dataStore.data.first()[ISF_RUNTIME_SOURCE_KEY])
            .isEqualTo(SensitivitySourcePreference.AAPS.name)
        assertThat(harness.dataStore.data.first()[CR_RUNTIME_SOURCE_KEY])
            .isEqualTo(SensitivitySourcePreference.COPILOT.name)

        harness.store.update { it.copy(killSwitch = true) }

        assertThat(harness.store.settings.first().sensitivitySettingsRevision).isEqualTo(2L)
    }

    @Test
    fun leasedSensitivityWriteImmediatelyBecomesTheSingleConsumerIdentity() = runTest {
        val harness = newHarness(backgroundScope)

        harness.store.beginSensitivitySettingsMutation {
            it.copy(isfSourcePreference = SensitivitySourcePreference.AAPS)
        }

        val tentative = harness.store.settings.first()
        assertThat(tentative.isfSourcePreference).isEqualTo(SensitivitySourcePreference.AAPS)
        assertThat(tentative.sensitivitySettingsRevision).isEqualTo(1L)
        assertThat(tentative.sensitivityRuntimeIdentity()).isEqualTo(
            SensitivityRuntimeSettingsIdentity(
                revision = 1L,
                isfSource = SensitivitySourcePreference.AAPS,
                crSource = SensitivitySourcePreference.EVIDENCE
            )
        )
    }

    @Test
    fun processRestartReadsTheSameSingleAuthoritativeSensitivityIdentity() = runTest {
        val harness = newHarness(backgroundScope)
        harness.store.beginSensitivitySettingsMutation {
            it.copy(isfSourcePreference = SensitivitySourcePreference.AAPS)
        }

        val restarted = AppSettingsStore(harness.dataStore).settings.first()
        assertThat(restarted.sensitivityRuntimeIdentity().revision).isEqualTo(1L)
        assertThat(restarted.sensitivityRuntimeIdentity().isfSource)
            .isEqualTo(SensitivitySourcePreference.AAPS)
    }

    @Test
    fun physiologicalSexIsPartOfSensitivityRuntimeFingerprint() = runTest {
        val current = newHarness(backgroundScope).store.settings.first()

        val changed = current.copy(
            energyProfile = current.energyProfile.copy(physiologicalSex = PhysiologicalSex.MALE)
        )

        assertThat(changed.sensitivityRuntimeFingerprint())
            .isNotEqualTo(current.sensitivityRuntimeFingerprint())
    }

    @Test
    fun genericUpdateCannotBypassLeasedSensitivitySourceCommand() = runTest {
        val harness = newHarness(backgroundScope)

        val failure = runCatching {
            harness.store.update {
                it.copy(isfSourcePreference = SensitivitySourcePreference.AAPS)
            }
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(harness.store.settings.first().isfSourcePreference)
            .isEqualTo(SensitivitySourcePreference.EVIDENCE)
        assertThat(harness.store.settings.first().sensitivitySettingsRevision).isEqualTo(0L)
    }

    @Test
    fun genericUpdateCannotBypassLeasedSensitivityFingerprintCommand() = runTest {
        val harness = newHarness(backgroundScope)

        val failure = runCatching {
            harness.store.update {
                it.copy(
                    isfCrConfidenceThreshold = 0.72,
                    isfCrUseActivity = false
                )
            }
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(IllegalArgumentException::class.java)
        val current = harness.store.settings.first()
        assertThat(current.isfCrConfidenceThreshold).isEqualTo(0.55)
        assertThat(current.isfCrUseActivity).isTrue()
        assertThat(current.sensitivitySettingsRevision).isEqualTo(0L)
    }

    @Test
    fun sameValueLeasedRetryKeepsTheAuthoritativeRevisionStable() = runTest {
        val harness = newHarness(backgroundScope)
        val mutation = harness.store.beginSensitivitySettingsMutation { it }

        assertThat(mutation.changed).isFalse()
        assertThat(harness.store.settings.first().sensitivitySettingsRevision).isEqualTo(0L)
    }

    @Test
    fun failedCalculationDoesNotRollbackThePersistedAuthoritativeSource() = runTest {
        val harness = newHarness(backgroundScope)

        val mutation = harness.store.beginSensitivitySettingsMutation {
            it.copy(isfSourcePreference = SensitivitySourcePreference.AAPS)
        }

        val current = harness.store.settings.first()
        assertThat(current.isfSourcePreference).isEqualTo(SensitivitySourcePreference.AAPS)
        assertThat(current.crSourcePreference).isEqualTo(SensitivitySourcePreference.EVIDENCE)
        assertThat(current.sensitivitySettingsRevision).isEqualTo(1L)
        assertThat(current.sensitivityRuntimeIdentity().revision)
            .isEqualTo(mutation.applied.sensitivitySettingsRevision)
    }

    @Test
    fun everyRuntimeAffectingSensitivitySettingInvalidatesRevision() = runTest {
        val mutations: List<(AppSettings) -> AppSettings> = listOf(
            { it.copy(isfCrConfidenceThreshold = 0.71) },
            { it.copy(isfCrShadowMode = !it.isfCrShadowMode) },
            { it.copy(isfCrUseActivity = !it.isfCrUseActivity) },
            { it.copy(isfCrUseManualTags = !it.isfCrUseManualTags) },
            { it.copy(isfCrMinIsfEvidencePerHour = it.isfCrMinIsfEvidencePerHour + 1) },
            { it.copy(isfCrMinCrEvidencePerHour = it.isfCrMinCrEvidencePerHour + 1) },
            { it.copy(isfCrCrMaxGapMinutes = it.isfCrCrMaxGapMinutes + 1) },
            { it.copy(isfCrCrMaxSensorBlockedRatePct = it.isfCrCrMaxSensorBlockedRatePct + 1.0) },
            { it.copy(isfCrCrMaxUamAmbiguityRatePct = it.isfCrCrMaxUamAmbiguityRatePct + 1.0) },
            { it.copy(analyticsLookbackDays = it.analyticsLookbackDays + 1) },
            {
                it.copy(
                    energyProfile = it.energyProfile.copy(physiologicalSex = PhysiologicalSex.FEMALE)
                )
            }
        )

        mutations.forEach { mutation ->
            val harness = newHarness(backgroundScope)
            harness.dataStore.edit { preferences ->
                preferences[ISF_RUNTIME_SOURCE_KEY] = SensitivitySourcePreference.EVIDENCE.name
                preferences[CR_RUNTIME_SOURCE_KEY] = SensitivitySourcePreference.EVIDENCE.name
                preferences[SENSITIVITY_SETTINGS_REVISION_KEY] = 0L
            }
            harness.store.beginSensitivitySettingsMutation(mutation)
            assertThat(harness.store.settings.first().sensitivitySettingsRevision)
                .isEqualTo(1L)
        }
    }

    @Test
    fun retentionAndAutoActivationPolicyDoNotChurnCurrentRuntimeRevision() = runTest {
        val mutations: List<(AppSettings) -> AppSettings> = listOf(
            { it.copy(isfCrSnapshotRetentionDays = it.isfCrSnapshotRetentionDays + 1) },
            { it.copy(isfCrEvidenceRetentionDays = it.isfCrEvidenceRetentionDays + 1) },
            { it.copy(isfCrAutoActivationEnabled = !it.isfCrAutoActivationEnabled) },
            { it.copy(isfCrAutoActivationLookbackHours = it.isfCrAutoActivationLookbackHours + 1) },
            { it.copy(isfCrAutoActivationMinSamples = it.isfCrAutoActivationMinSamples + 1) },
            { it.copy(isfCrAutoActivationMinMeanConfidence = it.isfCrAutoActivationMinMeanConfidence + 0.01) },
            { it.copy(isfCrAutoActivationMaxMeanAbsIsfDeltaPct = it.isfCrAutoActivationMaxMeanAbsIsfDeltaPct + 1.0) },
            { it.copy(isfCrAutoActivationMaxMeanAbsCrDeltaPct = it.isfCrAutoActivationMaxMeanAbsCrDeltaPct + 1.0) },
            { it.copy(isfCrAutoActivationMinSensorQualityScore = it.isfCrAutoActivationMinSensorQualityScore + 0.01) },
            { it.copy(isfCrAutoActivationMinSensorFactor = it.isfCrAutoActivationMinSensorFactor - 0.01) },
            { it.copy(isfCrAutoActivationMaxWearConfidencePenalty = it.isfCrAutoActivationMaxWearConfidencePenalty + 0.01) },
            { it.copy(isfCrAutoActivationMaxSensorAgeHighRatePct = it.isfCrAutoActivationMaxSensorAgeHighRatePct + 1.0) },
            { it.copy(isfCrAutoActivationMaxSuspectFalseLowRatePct = it.isfCrAutoActivationMaxSuspectFalseLowRatePct + 1.0) },
            { it.copy(isfCrAutoActivationMinDayTypeRatio = it.isfCrAutoActivationMinDayTypeRatio + 0.01) },
            { it.copy(isfCrAutoActivationMaxDayTypeSparseRatePct = it.isfCrAutoActivationMaxDayTypeSparseRatePct + 1.0) },
            { it.copy(isfCrAutoActivationRequireDailyQualityGate = !it.isfCrAutoActivationRequireDailyQualityGate) },
            { it.copy(isfCrAutoActivationDailyRiskBlockLevel = 2) },
            { it.copy(isfCrAutoActivationMinDailyMatchedSamples = it.isfCrAutoActivationMinDailyMatchedSamples + 1) },
            { it.copy(isfCrAutoActivationMaxDailyMae30Mmol = it.isfCrAutoActivationMaxDailyMae30Mmol + 0.01) },
            { it.copy(isfCrAutoActivationMaxDailyMae60Mmol = it.isfCrAutoActivationMaxDailyMae60Mmol + 0.01) },
            { it.copy(isfCrAutoActivationMaxHypoRatePct = it.isfCrAutoActivationMaxHypoRatePct + 0.1) },
            { it.copy(isfCrAutoActivationMinDailyCiCoverage30Pct = it.isfCrAutoActivationMinDailyCiCoverage30Pct + 1.0) },
            { it.copy(isfCrAutoActivationMinDailyCiCoverage60Pct = it.isfCrAutoActivationMinDailyCiCoverage60Pct + 1.0) },
            { it.copy(isfCrAutoActivationMaxDailyCiWidth30Mmol = it.isfCrAutoActivationMaxDailyCiWidth30Mmol + 0.01) },
            { it.copy(isfCrAutoActivationMaxDailyCiWidth60Mmol = it.isfCrAutoActivationMaxDailyCiWidth60Mmol + 0.01) },
            { it.copy(isfCrAutoActivationRollingMinRequiredWindows = it.isfCrAutoActivationRollingMinRequiredWindows + 1) },
            { it.copy(isfCrAutoActivationRollingMaeRelaxFactor = it.isfCrAutoActivationRollingMaeRelaxFactor + 0.01) },
            { it.copy(isfCrAutoActivationRollingCiCoverageRelaxFactor = it.isfCrAutoActivationRollingCiCoverageRelaxFactor - 0.01) },
            { it.copy(isfCrAutoActivationRollingCiWidthRelaxFactor = it.isfCrAutoActivationRollingCiWidthRelaxFactor + 0.01) }
        )

        mutations.forEach { mutation ->
            val harness = newHarness(backgroundScope)
            harness.dataStore.edit { preferences ->
                preferences[ISF_RUNTIME_SOURCE_KEY] = SensitivitySourcePreference.EVIDENCE.name
                preferences[CR_RUNTIME_SOURCE_KEY] = SensitivitySourcePreference.EVIDENCE.name
                preferences[SENSITIVITY_SETTINGS_REVISION_KEY] = 0L
            }
            harness.store.update(mutation)
            assertThat(harness.store.settings.first().sensitivitySettingsRevision)
                .isEqualTo(0L)
        }
    }

    @Test
    fun visualAndUnrelatedSettingsDoNotInvalidateSensitivityRevision() = runTest {
        val harness = newHarness(backgroundScope)
        harness.dataStore.edit { preferences ->
            preferences[ISF_RUNTIME_SOURCE_KEY] = SensitivitySourcePreference.EVIDENCE.name
            preferences[CR_RUNTIME_SOURCE_KEY] = SensitivitySourcePreference.EVIDENCE.name
            preferences[SENSITIVITY_SETTINGS_REVISION_KEY] = 0L
        }

        harness.store.update {
            it.copy(
                uiStyle = UiStyle.DYNAMIC_GRADIENT,
                killSwitch = !it.killSwitch,
                softAlertAudioDisplayName = "different alert sound"
            )
        }

        assertThat(harness.store.settings.first().sensitivitySettingsRevision).isEqualTo(0L)
    }

    @Test
    fun canonicalNoOpDoesNotInvalidateSensitivityRevision() = runTest {
        val harness = newHarness(backgroundScope)
        harness.store.beginSensitivitySettingsMutation { it.copy(isfCrConfidenceThreshold = 0.95) }
        assertThat(harness.store.settings.first().sensitivitySettingsRevision).isEqualTo(1L)

        harness.store.beginSensitivitySettingsMutation { it.copy(isfCrConfidenceThreshold = 0.99) }

        val readBack = harness.store.settings.first()
        assertThat(readBack.isfCrConfidenceThreshold).isEqualTo(0.95)
        assertThat(readBack.sensitivitySettingsRevision).isEqualTo(1L)
    }

    @Test
    fun physiologicalSexInvalidatesThroughItsActualPersistencePathOnly() = runTest {
        val harness = newHarness(backgroundScope)
        val initialProfile = harness.store.settings.first().energyProfile

        val mutation = harness.store.beginSensitivitySettingsMutation { current ->
            current.copy(
                energyProfile = initialProfile.copy(
                    physiologicalSex = io.aaps.copilot.domain.profile.PhysiologicalSex.FEMALE
                )
            )
        }
        assertThat(mutation.changed).isTrue()
        assertThat(harness.store.settings.first().sensitivitySettingsRevision).isEqualTo(1L)

        harness.store.setEnergyProfileSettings(
            harness.store.settings.first().energyProfile.copy(heightCm = 172.0)
        )
        assertThat(harness.store.settings.first().sensitivitySettingsRevision).isEqualTo(1L)
    }

    @Test
    fun legacyAutoSourcesDecodeAsEvidenceAndRewriteOnNextSettingsWrite() = runTest {
        val harness = newHarness(backgroundScope)
        harness.dataStore.edit { preferences ->
            preferences[ISF_RUNTIME_SOURCE_KEY] = "AUTO"
            preferences[CR_RUNTIME_SOURCE_KEY] = "COPILOT"
            preferences[SENSITIVITY_SETTINGS_REVISION_KEY] = 41L
        }

        val migratedRead = harness.store.settings.first()
        assertThat(migratedRead.isfSourcePreference)
            .isEqualTo(SensitivitySourcePreference.EVIDENCE)
        assertThat(migratedRead.crSourcePreference)
            .isEqualTo(SensitivitySourcePreference.COPILOT)
        assertThat(migratedRead.sensitivitySettingsRevision).isEqualTo(41L)

        harness.store.update { it }

        val persisted = harness.dataStore.data.first()
        assertThat(persisted[ISF_RUNTIME_SOURCE_KEY])
            .isEqualTo(SensitivitySourcePreference.EVIDENCE.name)
        assertThat(persisted[CR_RUNTIME_SOURCE_KEY])
            .isEqualTo(SensitivitySourcePreference.COPILOT.name)
        assertThat(persisted[SENSITIVITY_SETTINGS_REVISION_KEY]).isEqualTo(41L)
    }

    @Test
    fun autoUamExportCapDefaultsOffAndPersistsWithinSafeBounds() = runTest {
        val harness = newHarness(backgroundScope)

        val defaults = harness.store.settings.first()
        assertThat(defaults.enableUamAutoExportCap).isFalse()
        assertThat(defaults.uamAutoExportCapGrams).isEqualTo(10)

        harness.store.update {
            it.copy(
                enableUamAutoExportCap = true,
                uamAutoExportCapGrams = 99
            )
        }

        val persisted = harness.store.settings.first()
        assertThat(persisted.enableUamAutoExportCap).isTrue()
        assertThat(persisted.uamAutoExportCapGrams).isEqualTo(15)
        assertThat(harness.dataStore.data.first()[UAM_AUTO_EXPORT_CAP_ENABLED_KEY]).isTrue()
        assertThat(harness.dataStore.data.first()[UAM_AUTO_EXPORT_CAP_GRAMS_KEY]).isEqualTo(15)
    }

    @Test
    fun threeModeConsentMigrationDowngradesPreexistingLiveEvenWhenBoundedMigrationIsDone() = runTest {
        val harness = newHarness(backgroundScope)
        harness.dataStore.edit { prefs ->
            prefs[ENABLE_UAM_EXPORT_KEY] = true
            prefs[UAM_EXPORT_MODE_KEY] = UamExportMode.INCREMENTAL.name
            prefs[DRY_RUN_EXPORT_KEY] = false
            prefs[OLD_BOUNDED_MIGRATION_DONE_KEY] = true
        }

        harness.store.ensureUamThreeModeConsentV1()

        val migrated = harness.store.settings.first()
        assertThat(migrated.enableUamExportToAaps).isTrue()
        assertThat(migrated.uamExportMode).isEqualTo(UamExportMode.INCREMENTAL)
        assertThat(migrated.dryRunExport).isTrue()
        assertThat(harness.dataStore.data.first()[THREE_MODE_CONSENT_MIGRATION_DONE_KEY]).isTrue()
    }

    @Test
    fun completedThreeModeConsentMigrationPreservesLaterExplicitAuto() = runTest {
        val harness = newHarness(backgroundScope)
        harness.store.update {
            it.copy(
                enableUamExportToAaps = true,
                uamExportMode = UamExportMode.INCREMENTAL,
                dryRunExport = true
            )
        }
        harness.store.ensureUamThreeModeConsentV1()
        harness.store.update {
            it.copy(
                enableUamExportToAaps = true,
                uamExportMode = UamExportMode.INCREMENTAL,
                dryRunExport = false
            )
        }

        val restartedStore = AppSettingsStore(harness.dataStore)
        restartedStore.ensureUamThreeModeConsentV1()

        val preserved = restartedStore.settings.first()
        assertThat(preserved.enableUamExportToAaps).isTrue()
        assertThat(preserved.uamExportMode).isEqualTo(UamExportMode.INCREMENTAL)
        assertThat(preserved.dryRunExport).isFalse()
    }

    @Test
    fun threeModeConsentMigrationPreservesOffAndObserveStates() = runTest {
        val offHarness = newHarness(backgroundScope)
        offHarness.store.update {
            it.copy(
                enableUamExportToAaps = false,
                uamExportMode = UamExportMode.OFF,
                dryRunExport = true
            )
        }
        offHarness.store.ensureUamThreeModeConsentV1()

        val off = offHarness.store.settings.first()
        assertThat(off.enableUamExportToAaps).isFalse()
        assertThat(off.uamExportMode).isEqualTo(UamExportMode.OFF)
        assertThat(off.dryRunExport).isTrue()

        val observeHarness = newHarness(backgroundScope)
        observeHarness.store.update {
            it.copy(
                enableUamExportToAaps = true,
                uamExportMode = UamExportMode.INCREMENTAL,
                dryRunExport = true
            )
        }
        observeHarness.store.ensureUamThreeModeConsentV1()

        val observe = observeHarness.store.settings.first()
        assertThat(observe.enableUamExportToAaps).isTrue()
        assertThat(observe.uamExportMode).isEqualTo(UamExportMode.INCREMENTAL)
        assertThat(observe.dryRunExport).isTrue()
    }

    @Test
    @Suppress("DEPRECATION")
    fun legacyOpenAiKeyAdapterReadsAndClearsOnlyLegacySetting() = runTest {
        val harness = newHarness(backgroundScope)
        harness.store.update { it.copy(cloudBaseUrl = "https://example.invalid") }
        harness.dataStore.edit { preferences ->
            preferences[LEGACY_OPENAI_KEY] = "synthetic-legacy-key"
        }
        val source = AppSettingsLegacyOpenAiKeySource(harness.store)

        assertThat(source.read()).isEqualTo("synthetic-legacy-key")
        assertThat(harness.store.settings.first().toString())
            .doesNotContain("synthetic-legacy-key")

        assertThat(source.clearIfMatches("synthetic-legacy-key")).isTrue()

        val settings = harness.store.settings.first()
        assertThat(source.read()).isNull()
        assertThat(harness.dataStore.data.first().asMap())
            .doesNotContainKey(LEGACY_OPENAI_KEY)
        assertThat(settings.cloudBaseUrl).isEqualTo("https://example.invalid")
    }

    @Test
    fun legacyOpenAiKeyAdapterClearsOnlyNormalizedExpectedValue() = runTest {
        val harness = newHarness(backgroundScope)
        harness.dataStore.edit { preferences ->
            preferences[LEGACY_OPENAI_KEY] = "  synthetic-legacy-a  "
        }
        val source = AppSettingsLegacyOpenAiKeySource(harness.store)

        assertThat(source.read()).isEqualTo("synthetic-legacy-a")
        assertThat(source.clearIfMatches("synthetic-legacy-b")).isFalse()
        assertThat(source.read()).isEqualTo("synthetic-legacy-a")

        assertThat(source.clearIfMatches("  synthetic-legacy-a  ")).isTrue()
        assertThat(source.read()).isNull()
        assertThat(harness.dataStore.data.first().asMap())
            .doesNotContainKey(LEGACY_OPENAI_KEY)
    }

    @Test
    fun normalProviderMutationNeverWritesLegacyOpenAiPreference() = runTest {
        val harness = newHarness(backgroundScope)

        harness.store.setClinicalAiConfig(
            ClinicalAiProviderConfig(ClinicalAiProviderId.ANTHROPIC, "synthetic-model")
        )
        assertThat(harness.dataStore.data.first().asMap())
            .doesNotContainKey(LEGACY_OPENAI_KEY)

        harness.store.update { current ->
            current.copy(
                clinicalAiConfigState = ClinicalAiConfigState.Valid(
                    ClinicalAiProviderConfig(ClinicalAiProviderId.GEMINI, "synthetic-model")
                )
            )
        }

        assertThat(harness.dataStore.data.first().asMap())
            .doesNotContainKey(LEGACY_OPENAI_KEY)
    }

    @Test
    fun normalSettingsWriteCannotRecreateClearedLegacyPlaintextKey() = runTest {
        val harness = newHarness(backgroundScope)
        harness.dataStore.edit { preferences ->
            preferences[LEGACY_OPENAI_KEY] = "synthetic-legacy-key"
        }
        val legacySource = AppSettingsLegacyOpenAiKeySource(harness.store)
        assertThat(legacySource.read()).isEqualTo("synthetic-legacy-key")

        assertThat(legacySource.clearIfMatches("synthetic-legacy-key")).isTrue()
        harness.store.update { current -> current.copy(killSwitch = true) }

        assertThat(harness.store.settings.first().killSwitch).isTrue()
        assertThat(harness.dataStore.data.first().asMap())
            .doesNotContainKey(LEGACY_OPENAI_KEY)
    }

    @Test
    fun unrelatedSettingsWritesPreserveLegacyKeyForMigrationCleanup() = runTest {
        val harness = newHarness(backgroundScope)
        harness.dataStore.edit { preferences ->
            preferences[LEGACY_OPENAI_KEY] = "synthetic-legacy-a"
        }
        val source = AppSettingsLegacyOpenAiKeySource(harness.store)

        harness.store.update { current -> current.copy(killSwitch = true) }

        assertThat(source.read()).isEqualTo("synthetic-legacy-a")
        assertThat(harness.store.settings.first().killSwitch).isTrue()
    }

    @Test
    fun settingsDefaultToRecommendedOpenAiWhenNewKeysAreMissing() = runTest {
        val harness = newHarness(backgroundScope)

        assertThat(harness.store.settings.first().clinicalAiConfigState).isEqualTo(
            ClinicalAiConfigState.UnconfiguredDefault(
                ClinicalAiProviderConfig(
                    providerId = ClinicalAiProviderId.OPENAI,
                    modelId = "gpt-5.6-terra"
                )
            )
        )
    }

    @Test
    fun automaticEventAiAnalysisDefaultsEnabledWhenPreferenceIsMissing() = runTest {
        val harness = newHarness(backgroundScope)

        assertThat(harness.store.settings.first().automaticEventAiAnalysisEnabled).isTrue()
        assertThat(harness.dataStore.data.first()[AUTOMATIC_EVENT_AI_ANALYSIS_KEY]).isNull()
    }

    @Test
    fun automaticEventAiAnalysisPersistsBothValuesAndEmitsImmediately() = runTest {
        val harness = newHarness(backgroundScope)
        val before = harness.store.settings.first()
        val emissions = mutableListOf<Boolean>()
        val collector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            harness.store.settings
                .drop(1)
                .take(2)
                .map { it.automaticEventAiAnalysisEnabled }
                .toList(emissions)
        }

        harness.store.update {
            it.copy(automaticEventAiAnalysisEnabled = false)
        }
        harness.store.update {
            it.copy(automaticEventAiAnalysisEnabled = true)
        }
        advanceUntilIdle()

        assertThat(emissions).containsExactly(false, true).inOrder()
        val after = harness.store.settings.first()
        assertThat(after.automaticEventAiAnalysisEnabled).isTrue()
        assertThat(after.clinicalAiConfigState).isEqualTo(before.clinicalAiConfigState)
        assertThat(after.sensitivitySettingsRevision)
            .isEqualTo(before.sensitivitySettingsRevision)
        assertThat(harness.dataStore.data.first()[AUTOMATIC_EVENT_AI_ANALYSIS_KEY]).isTrue()
        collector.cancel()
    }

    @Test
    fun unrelatedWritesPreserveAutomaticEventAiAnalysisWithoutSensitivityRevisionChange() = runTest {
        val harness = newHarness(backgroundScope)
        harness.store.update {
            it.copy(automaticEventAiAnalysisEnabled = false)
        }
        val before = harness.store.settings.first()

        harness.store.update { it.copy(killSwitch = true) }

        val after = harness.store.settings.first()
        assertThat(after.automaticEventAiAnalysisEnabled).isFalse()
        assertThat(after.killSwitch).isTrue()
        assertThat(after.sensitivitySettingsRevision)
            .isEqualTo(before.sensitivitySettingsRevision)
    }

    @Test
    fun productionAlertAiSettingsAcceptValidAndDefaultButRejectInvalid() = runTest {
        val harness = newHarness(backgroundScope)
        val base = harness.store.settings.first()
        val configured = ClinicalAiProviderConfig(
            ClinicalAiProviderId.GEMINI,
            "gemini-3.6-flash"
        )

        val valid = resolveAlertAiAnalysisSettings(
            base.copy(
                automaticEventAiAnalysisEnabled = false,
                clinicalAiConfigState = ClinicalAiConfigState.Valid(configured)
            )
        )
        val unconfiguredDefault = resolveAlertAiAnalysisSettings(base)
        val invalid = resolveAlertAiAnalysisSettings(
            base.copy(
                clinicalAiConfigState = ClinicalAiConfigState.Invalid(
                    ClinicalAiConfigInvalidReason.INVALID_MODEL
                )
            )
        )

        assertThat(valid?.enabled).isFalse()
        assertThat(valid?.config).isEqualTo(configured)
        assertThat(unconfiguredDefault?.config)
            .isEqualTo(ClinicalAiProviderConfig.defaultOpenAi())
        assertThat(invalid).isNull()
    }

    @Test
    fun settingsRoundTripEveryProviderProtocolAndCustomModel() = runTest {
        val harness = newHarness(backgroundScope)
        val configs = listOf(
            ClinicalAiProviderConfig(ClinicalAiProviderId.OPENAI, "gpt-5.6-sol"),
            ClinicalAiProviderConfig(ClinicalAiProviderId.ANTHROPIC, "claude-e\u0301-custom"),
            ClinicalAiProviderConfig(ClinicalAiProviderId.GEMINI, "gemini-3.5-flash-lite"),
            ClinicalAiProviderConfig.normalized(
                providerId = ClinicalAiProviderId.OPENAI_COMPATIBLE,
                modelId = "publisher/model_name:latest",
                endpoint = "https://Models.Example:443/v1/",
                compatibleProtocol = OpenAiCompatibleProtocol.RESPONSES
            ),
            ClinicalAiProviderConfig(
                providerId = ClinicalAiProviderId.OPENAI_COMPATIBLE,
                modelId = "local-model",
                endpoint = "http://127.0.0.1:11434/v1",
                compatibleProtocol = OpenAiCompatibleProtocol.CHAT_COMPLETIONS
            )
        )

        configs.forEach { config ->
            harness.store.setClinicalAiConfig(config)
            assertThat(harness.store.settings.first().clinicalAiConfigState)
                .isEqualTo(ClinicalAiConfigState.Valid(config))
        }

        val preferences = harness.dataStore.data.first()
        assertThat(preferences[CLINICAL_AI_PROVIDER_KEY])
            .isEqualTo(ClinicalAiProviderId.OPENAI_COMPATIBLE.name)
        assertThat(preferences[CLINICAL_AI_MODEL_KEY]).isEqualTo("local-model")
        assertThat(preferences[CLINICAL_AI_ENDPOINT_KEY])
            .isEqualTo("http://127.0.0.1:11434/v1")
        assertThat(preferences[CLINICAL_AI_PROTOCOL_KEY])
            .isEqualTo(OpenAiCompatibleProtocol.CHAT_COMPLETIONS.name)
    }

    @Test
    fun clinicalProviderChangePreservesLegacyCloudBaseUrl() = runTest {
        val harness = newHarness(backgroundScope)
        val legacyCloudUrl = "https://legacy-chat.example/v1"
        harness.store.update { it.copy(cloudBaseUrl = legacyCloudUrl) }

        harness.store.setClinicalAiConfig(
            ClinicalAiProviderConfig.normalized(
                providerId = ClinicalAiProviderId.OPENAI_COMPATIBLE,
                modelId = "local-model",
                endpoint = "https://clinical.example/v1",
                compatibleProtocol = OpenAiCompatibleProtocol.RESPONSES
            )
        )

        val settings = harness.store.settings.first()
        assertThat(settings.cloudBaseUrl).isEqualTo(legacyCloudUrl)
        assertThat(settings.clinicalAiConfigState).isEqualTo(
            ClinicalAiConfigState.Valid(
                ClinicalAiProviderConfig.normalized(
                    providerId = ClinicalAiProviderId.OPENAI_COMPATIBLE,
                    modelId = "local-model",
                    endpoint = "https://clinical.example/v1",
                    compatibleProtocol = OpenAiCompatibleProtocol.RESPONSES
                )
            )
        )
    }

    @Test
    fun genericUpdatePersistsValidClinicalConfigInOneEmission() = runTest {
        val harness = newHarness(backgroundScope)
        harness.store.settings.first()
        val config = ClinicalAiProviderConfig(
            providerId = ClinicalAiProviderId.OPENAI_COMPATIBLE,
            modelId = "local/model",
            endpoint = "http://localhost:11434/v1",
            compatibleProtocol = OpenAiCompatibleProtocol.CHAT_COMPLETIONS
        )
        val emissions = mutableListOf<AppSettings>()
        val collector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            harness.store.settings.drop(1).take(1).toList(emissions)
        }

        harness.store.update {
            it.copy(
                clinicalAiConfigState = ClinicalAiConfigState.Valid(config),
                killSwitch = true
            )
        }
        advanceUntilIdle()

        assertThat(emissions).hasSize(1)
        assertThat(emissions.single().clinicalAiConfigState)
            .isEqualTo(ClinicalAiConfigState.Valid(config))
        assertThat(emissions.single().killSwitch).isTrue()
        assertThat(harness.dataStore.data.first()[CLINICAL_AI_PROVIDER_KEY])
            .isEqualTo(ClinicalAiProviderId.OPENAI_COMPATIBLE.name)
        assertThat(harness.dataStore.data.first()[CLINICAL_AI_MODEL_KEY])
            .isEqualTo("local/model")
        assertThat(harness.dataStore.data.first()[CLINICAL_AI_ENDPOINT_KEY])
            .isEqualTo("http://localhost:11434/v1")
        assertThat(harness.dataStore.data.first()[CLINICAL_AI_PROTOCOL_KEY])
            .isEqualTo(OpenAiCompatibleProtocol.CHAT_COMPLETIONS.name)
        collector.cancel()
    }

    @Test
    fun genericUpdateRejectsInvalidClinicalConfigWithoutMutationOrEmission() = runTest {
        val harness = newHarness(backgroundScope)
        val before = harness.store.settings.first()
        val rawBefore = harness.dataStore.data.first().asMap()
        val emissions = mutableListOf<AppSettings>()
        val collector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            harness.store.settings.drop(1).toList(emissions)
        }

        val failure = try {
            harness.store.update {
                it.copy(
                    clinicalAiConfigState = ClinicalAiConfigState.Invalid(
                        ClinicalAiConfigInvalidReason.INVALID_MODEL
                    ),
                    killSwitch = true
                )
            }
            null
        } catch (error: IllegalArgumentException) {
            error
        }
        advanceUntilIdle()

        assertThat(failure).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(harness.store.settings.first()).isEqualTo(before)
        assertThat(harness.dataStore.data.first().asMap()).isEqualTo(rawBefore)
        assertThat(emissions).isEmpty()
        collector.cancel()
    }

    @Test
    fun genericUpdateCanClearClinicalConfigBackToUnconfiguredDefaultAtomically() = runTest {
        val harness = newHarness(backgroundScope)
        val configured = ClinicalAiProviderConfig(
            providerId = ClinicalAiProviderId.OPENAI_COMPATIBLE,
            modelId = "local/model",
            endpoint = "http://localhost:11434/v1",
            compatibleProtocol = OpenAiCompatibleProtocol.RESPONSES
        )
        harness.store.setClinicalAiConfig(configured)
        val defaultState = ClinicalAiConfigState.UnconfiguredDefault(
            ClinicalAiProviderConfig.defaultOpenAi()
        )

        harness.store.update {
            it.copy(
                clinicalAiConfigState = defaultState,
                killSwitch = true
            )
        }

        val settings = harness.store.settings.first()
        assertThat(settings.clinicalAiConfigState).isEqualTo(defaultState)
        assertThat(settings.killSwitch).isTrue()
        val preferences = harness.dataStore.data.first()
        assertThat(preferences[CLINICAL_AI_PROVIDER_KEY]).isNull()
        assertThat(preferences[CLINICAL_AI_MODEL_KEY]).isNull()
        assertThat(preferences[CLINICAL_AI_ENDPOINT_KEY]).isNull()
        assertThat(preferences[CLINICAL_AI_PROTOCOL_KEY]).isNull()
    }

    @Test
    fun nativeProviderMutationRemovesCompatibleOnlyKeysAndPreservesLegacyOpenAiKey() = runTest {
        val harness = newHarness(backgroundScope)
        harness.dataStore.edit { preferences ->
            preferences[LEGACY_OPENAI_KEY] = "synthetic-legacy-key"
        }
        harness.store.setClinicalAiConfig(
            ClinicalAiProviderConfig(
                providerId = ClinicalAiProviderId.OPENAI_COMPATIBLE,
                modelId = "local-model",
                endpoint = "http://localhost:11434/v1",
                compatibleProtocol = OpenAiCompatibleProtocol.RESPONSES
            )
        )

        harness.store.setClinicalAiConfig(
            ClinicalAiProviderConfig(ClinicalAiProviderId.GEMINI, "gemini-custom")
        )

        val preferences = harness.dataStore.data.first()
        assertThat(preferences[CLINICAL_AI_ENDPOINT_KEY]).isNull()
        assertThat(preferences[CLINICAL_AI_PROTOCOL_KEY]).isNull()
        assertThat(preferences[LEGACY_OPENAI_KEY]).isEqualTo("synthetic-legacy-key")
    }

    @Test
    fun malformedStoredConfigIsExplicitlyInvalidWithoutUsableFallback() = runTest {
        val malformedValues = listOf(
            StoredClinicalAiValues(
                providerId = "UNKNOWN",
                modelId = "model"
            ),
            StoredClinicalAiValues(
                providerId = ClinicalAiProviderId.OPENAI_COMPATIBLE.name,
                modelId = "model",
                endpoint = "http://remote.example/v1",
                protocol = OpenAiCompatibleProtocol.RESPONSES.name
            ),
            StoredClinicalAiValues(
                providerId = ClinicalAiProviderId.GEMINI.name,
                modelId = "model\u034fid"
            ),
            StoredClinicalAiValues(
                providerId = ClinicalAiProviderId.OPENAI.name,
                modelId = "gpt-5.6-terra",
                protocol = OpenAiCompatibleProtocol.RESPONSES.name
            ),
            StoredClinicalAiValues(
                providerId = ClinicalAiProviderId.OPENAI_COMPATIBLE.name,
                modelId = "model",
                endpoint = "https://models.example/v1",
                protocol = "UNKNOWN"
            )
        )

        malformedValues.forEach { values ->
            val harness = newHarness(backgroundScope)
            harness.writeStored(values)

            assertThat(harness.store.settings.first().clinicalAiConfigState)
                .isInstanceOf(ClinicalAiConfigState.Invalid::class.java)
        }
    }

    @Test
    fun everyPartialKeyCombinationIsInvalidForItsProviderContract() = runTest {
        for (mask in 1..15) {
            val nativeHarness = newHarness(backgroundScope)
            nativeHarness.writeStored(valuesForMask(mask, ClinicalAiProviderId.OPENAI))
            val nativeState = nativeHarness.store.settings.first().clinicalAiConfigState
            if (mask == PROVIDER_MASK or MODEL_MASK) {
                assertThat(nativeState).isInstanceOf(ClinicalAiConfigState.Valid::class.java)
            } else {
                assertThat(nativeState).isInstanceOf(ClinicalAiConfigState.Invalid::class.java)
            }

            val compatibleHarness = newHarness(backgroundScope)
            compatibleHarness.writeStored(
                valuesForMask(mask, ClinicalAiProviderId.OPENAI_COMPATIBLE)
            )
            val compatibleState =
                compatibleHarness.store.settings.first().clinicalAiConfigState
            if (mask == ALL_CONFIG_MASK) {
                assertThat(compatibleState)
                    .isInstanceOf(ClinicalAiConfigState.Valid::class.java)
            } else {
                assertThat(compatibleState)
                    .isInstanceOf(ClinicalAiConfigState.Invalid::class.java)
            }
        }
    }

    @Test
    fun unrelatedUpdatePreservesExistingInvalidRawConfigAndValidSetRecovers() = runTest {
        val harness = newHarness(backgroundScope)
        harness.dataStore.edit { preferences ->
            preferences[LEGACY_OPENAI_KEY] = "synthetic-legacy-key"
        }
        harness.writeStored(
            StoredClinicalAiValues(
                providerId = ClinicalAiProviderId.OPENAI_COMPATIBLE.name,
                modelId = "model",
                endpoint = "http://remote.example/v1",
                protocol = OpenAiCompatibleProtocol.RESPONSES.name
            )
        )
        val invalidState = harness.store.settings.first().clinicalAiConfigState
        assertThat(invalidState).isInstanceOf(ClinicalAiConfigState.Invalid::class.java)
        val rawBefore = harness.dataStore.data.first().let { preferences ->
            listOf(
                preferences[CLINICAL_AI_PROVIDER_KEY],
                preferences[CLINICAL_AI_MODEL_KEY],
                preferences[CLINICAL_AI_ENDPOINT_KEY],
                preferences[CLINICAL_AI_PROTOCOL_KEY]
            )
        }

        harness.store.update { it.copy(killSwitch = true) }

        val afterUnrelatedUpdate = harness.store.settings.first()
        assertThat(afterUnrelatedUpdate.killSwitch).isTrue()
        assertThat(afterUnrelatedUpdate.clinicalAiConfigState).isEqualTo(invalidState)
        val rawAfter = harness.dataStore.data.first().let { preferences ->
            listOf(
                preferences[CLINICAL_AI_PROVIDER_KEY],
                preferences[CLINICAL_AI_MODEL_KEY],
                preferences[CLINICAL_AI_ENDPOINT_KEY],
                preferences[CLINICAL_AI_PROTOCOL_KEY]
            )
        }
        assertThat(rawAfter).isEqualTo(rawBefore)

        val valid = ClinicalAiProviderConfig(
            ClinicalAiProviderId.ANTHROPIC,
            "модель/клиника:v1"
        )
        harness.store.setClinicalAiConfig(valid)

        assertThat(harness.store.settings.first().clinicalAiConfigState)
            .isEqualTo(ClinicalAiConfigState.Valid(valid))
        assertThat(harness.dataStore.data.first()[LEGACY_OPENAI_KEY])
            .isEqualTo("synthetic-legacy-key")
    }

    @Test
    fun genericUpdateRejectsChangedExistingInvalidWithoutMutationOrEmission() = runTest {
        val harness = newHarness(backgroundScope)
        harness.writeStored(
            StoredClinicalAiValues(
                providerId = ClinicalAiProviderId.OPENAI_COMPATIBLE.name,
                modelId = "model",
                endpoint = "http://remote.example/v1",
                protocol = OpenAiCompatibleProtocol.RESPONSES.name
            )
        )
        val before = harness.store.settings.first()
        assertThat(before.clinicalAiConfigState).isInstanceOf(
            ClinicalAiConfigState.Invalid::class.java
        )
        val rawBefore = harness.dataStore.data.first().asMap()
        val emissions = mutableListOf<AppSettings>()
        val collector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            harness.store.settings.drop(1).toList(emissions)
        }

        val failure = try {
            harness.store.update {
                it.copy(
                    clinicalAiConfigState = ClinicalAiConfigState.Invalid(
                        ClinicalAiConfigInvalidReason.INVALID_MODEL
                    ),
                    killSwitch = true
                )
            }
            null
        } catch (error: IllegalArgumentException) {
            error
        }
        advanceUntilIdle()

        assertThat(failure).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(harness.store.settings.first()).isEqualTo(before)
        assertThat(harness.dataStore.data.first().asMap()).isEqualTo(rawBefore)
        assertThat(emissions).isEmpty()
        collector.cancel()
    }

    @Test
    fun concurrentConfigUpdatesRemainAtomicAndEmitImmediately() = runTest {
        val harness = newHarness(backgroundScope)
        val configs = listOf(
            ClinicalAiProviderConfig(ClinicalAiProviderId.OPENAI, "gpt-5.6-luna"),
            ClinicalAiProviderConfig(ClinicalAiProviderId.ANTHROPIC, "claude-opus-5"),
            ClinicalAiProviderConfig(
                ClinicalAiProviderId.OPENAI_COMPATIBLE,
                "local/model",
                "http://localhost:11434/v1",
                OpenAiCompatibleProtocol.CHAT_COMPLETIONS
            )
        )

        configs.map { config ->
            async {
                harness.store.setClinicalAiConfig(config)
                harness.store.settings.first().clinicalAiConfigState
            }
        }.awaitAll()

        val effectiveState = harness.store.settings.first().clinicalAiConfigState
        assertThat(effectiveState).isInstanceOf(ClinicalAiConfigState.Valid::class.java)
        val effective = (effectiveState as ClinicalAiConfigState.Valid).config
        assertThat(configs).contains(effective)
        val preferences = harness.dataStore.data.first()
        assertThat(
            ClinicalAiConfigState.fromStored(
                providerId = preferences[CLINICAL_AI_PROVIDER_KEY],
                modelId = preferences[CLINICAL_AI_MODEL_KEY],
                endpoint = preferences[CLINICAL_AI_ENDPOINT_KEY],
                compatibleProtocol = preferences[CLINICAL_AI_PROTOCOL_KEY]
            )
        ).isEqualTo(ClinicalAiConfigState.Valid(effective))
    }

    private fun newHarness(
        scope: CoroutineScope,
        currentInstallId: String? = "test-install-id-v1"
    ): Harness {
        val directory = temporaryFolder.newFolder(UUID.randomUUID().toString())
        val dataStore = PreferenceDataStoreFactory.create(
            scope = scope,
            produceFile = { File(directory, "settings.preferences_pb") }
        )
        return Harness(
            store = AppSettingsStore(dataStore, currentInstallId),
            dataStore = dataStore
        )
    }

    private data class Harness(
        val store: AppSettingsStore,
        val dataStore: DataStore<Preferences>
    ) {
        suspend fun writeStored(values: StoredClinicalAiValues) {
            dataStore.edit { preferences ->
                values.providerId?.let { preferences[CLINICAL_AI_PROVIDER_KEY] = it }
                values.modelId?.let { preferences[CLINICAL_AI_MODEL_KEY] = it }
                values.endpoint?.let { preferences[CLINICAL_AI_ENDPOINT_KEY] = it }
                values.protocol?.let { preferences[CLINICAL_AI_PROTOCOL_KEY] = it }
            }
        }
    }

    private fun valuesForMask(
        mask: Int,
        providerId: ClinicalAiProviderId
    ): StoredClinicalAiValues = StoredClinicalAiValues(
        providerId = providerId.name.takeIf { mask and PROVIDER_MASK != 0 },
        modelId = "model".takeIf { mask and MODEL_MASK != 0 },
        endpoint = "https://models.example/v1".takeIf { mask and ENDPOINT_MASK != 0 },
        protocol = OpenAiCompatibleProtocol.RESPONSES.name
            .takeIf { mask and PROTOCOL_MASK != 0 }
    )

    private data class StoredClinicalAiValues(
        val providerId: String? = null,
        val modelId: String? = null,
        val endpoint: String? = null,
        val protocol: String? = null
    )

    private companion object {
        val ENERGY_PROFILE_ENABLED_KEY = booleanPreferencesKey("energy_profile_enabled")
        val ENERGY_PROFILE_FORECAST_ACTIVITY_INFLUENCE_ENABLED_KEY =
            booleanPreferencesKey("energy_profile_forecast_activity_influence_enabled")
        val BIRTH_DATE_EPOCH_DAY_KEY = longPreferencesKey("birth_date_epoch_day")
        val PHYSIOLOGICAL_SEX_KEY = stringPreferencesKey("physiological_sex")
        val HEIGHT_CM_KEY = doublePreferencesKey("height_cm")
        val WEIGHT_KG_KEY = doublePreferencesKey("weight_kg")
        val FOOD_PROFILE_MODE_KEY = stringPreferencesKey("food_profile_mode")
        val MANUAL_FOOD_PROFILE_KEY = stringPreferencesKey("manual_food_profile")
        val ACTIVITY_PROFILE_MODE_KEY = stringPreferencesKey("activity_profile_mode")
        val MANUAL_ACTIVITY_PROFILE_KEY = stringPreferencesKey("manual_activity_profile")
        val CALORIE_GOAL_MODE_KEY = stringPreferencesKey("calorie_goal_mode")
        val MANUAL_CALORIE_TARGET_KEY = intPreferencesKey("manual_calorie_target")
        val SHARE_PROFILE_WITH_AI_KEY = booleanPreferencesKey("share_profile_with_ai")
        val AUTOMATIC_EVENT_AI_ANALYSIS_KEY =
            booleanPreferencesKey("automatic_event_ai_analysis_enabled")
        val ISF_RUNTIME_SOURCE_KEY = stringPreferencesKey("isf_runtime_source")
        val CR_RUNTIME_SOURCE_KEY = stringPreferencesKey("cr_runtime_source")
        val SENSITIVITY_SETTINGS_REVISION_KEY = longPreferencesKey("sensitivity_settings_revision")
        val ENABLE_UAM_EXPORT_KEY = booleanPreferencesKey("enable_uam_export_to_aaps")
        val UAM_EXPORT_MODE_KEY = stringPreferencesKey("uam_export_mode")
        val DRY_RUN_EXPORT_KEY = booleanPreferencesKey("uam_dry_run_export")
        val UAM_AUTO_EXPORT_CAP_ENABLED_KEY = booleanPreferencesKey("uam_auto_export_cap_enabled")
        val UAM_AUTO_EXPORT_CAP_GRAMS_KEY = intPreferencesKey("uam_auto_export_cap_grams")
        val OLD_BOUNDED_MIGRATION_DONE_KEY =
            booleanPreferencesKey("uam_export_v2_bounded_migration_done")
        val THREE_MODE_CONSENT_MIGRATION_DONE_KEY =
            booleanPreferencesKey("uam_export_three_mode_consent_v1_migration_done")
        val CLINICAL_AI_PROVIDER_KEY = stringPreferencesKey("clinical_ai_provider_id")
        val CLINICAL_AI_MODEL_KEY = stringPreferencesKey("clinical_ai_model_id")
        val CLINICAL_AI_ENDPOINT_KEY = stringPreferencesKey("clinical_ai_endpoint")
        val CLINICAL_AI_PROTOCOL_KEY = stringPreferencesKey("clinical_ai_compatible_protocol")
        val LEGACY_OPENAI_KEY = stringPreferencesKey("openai_api_key")
        const val PROVIDER_MASK = 1
        const val MODEL_MASK = 2
        const val ENDPOINT_MASK = 4
        const val PROTOCOL_MASK = 8
        const val ALL_CONFIG_MASK =
            PROVIDER_MASK or MODEL_MASK or ENDPOINT_MASK or PROTOCOL_MASK
    }
}
