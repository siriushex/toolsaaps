package io.aaps.copilot.config

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import io.aaps.copilot.domain.profile.ActivityProfile
import io.aaps.copilot.domain.nutrition.MealPortion
import io.aaps.copilot.domain.nutrition.MealPortionRange
import io.aaps.copilot.domain.nutrition.MealPortionSettings
import io.aaps.copilot.domain.profile.ActivityProfileMode
import io.aaps.copilot.domain.profile.CalorieGoalMode
import io.aaps.copilot.domain.profile.EnergyProfileSettings
import io.aaps.copilot.domain.profile.FoodProfileMode
import io.aaps.copilot.domain.profile.MealAbsorptionProfile
import io.aaps.copilot.domain.profile.PhysiologicalSex
import io.aaps.copilot.domain.predict.InsulinActionProfileId
import io.aaps.copilot.domain.predict.SensitivityRuntimeSettingsIdentity
import io.aaps.copilot.domain.predict.SensitivitySourcePreference
import io.aaps.copilot.domain.predict.UamExportMode
import io.aaps.copilot.domain.predict.UamUserSettings
import io.aaps.copilot.domain.target.BaseTargetInterval
import io.aaps.copilot.domain.target.BaseTargetSchedule
import io.aaps.copilot.domain.target.BaseTargetScheduleCodec
import io.aaps.copilot.domain.target.BaseTargetScheduleDecodeResult
import io.aaps.copilot.domain.target.BaseTargetSchedulePolicy
import io.aaps.copilot.domain.target.TargetManagerMode
import io.aaps.copilot.security.TherapyActionInstallIdentity
import io.aaps.copilot.security.TherapyActionTransportGate
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.round
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

internal data class UamExportMigrationDecision(
    val enabled: Boolean,
    val mode: UamExportMode,
    val dryRun: Boolean,
    val maxBackdateMinutes: Int,
    val changed: Boolean
)

internal data class UamExportMigrationResult(
    val oldMode: UamExportMode,
    val newMode: UamExportMode,
    val oldDryRun: Boolean,
    val newDryRun: Boolean,
    val oldMaxBackdateMinutes: Int,
    val newMaxBackdateMinutes: Int
)

internal data class UamThreeModeConsentMigrationResult(
    val oldMode: UamExportMode,
    val newMode: UamExportMode,
    val oldDryRun: Boolean,
    val newDryRun: Boolean
)

internal data class SensitivitySettingsMutation(
    val before: AppSettings,
    val applied: AppSettings,
    val beforeFingerprint: List<Any?>,
    val appliedFingerprint: List<Any?>,
    val changed: Boolean
)

enum class TherapyActionBootstrapMigrationResult {
    ARMED,
    ALREADY_DECIDED,
    FAILED
}

internal fun decideBoundedUamExportV2Migration(
    enabled: Boolean,
    mode: UamExportMode,
    dryRun: Boolean,
    maxBackdateMinutes: Int
): UamExportMigrationDecision {
    if (!enabled || mode == UamExportMode.OFF) {
        return UamExportMigrationDecision(
            enabled = enabled,
            mode = mode,
            dryRun = dryRun,
            maxBackdateMinutes = maxBackdateMinutes,
            changed = false
        )
    }

    val migratedMode = UamExportMode.INCREMENTAL
    val migratedDryRun = true
    val migratedMaxBackdateMinutes = 5
    return UamExportMigrationDecision(
        enabled = true,
        mode = migratedMode,
        dryRun = migratedDryRun,
        maxBackdateMinutes = migratedMaxBackdateMinutes,
        changed = mode != migratedMode ||
            dryRun != migratedDryRun ||
            maxBackdateMinutes != migratedMaxBackdateMinutes
    )
}

class AppSettingsStore internal constructor(
    private val dataStore: DataStore<Preferences>,
    private val currentInstallId: String? = TEST_INSTALL_ID
) {
    @Volatile
    private var threeModeConsentMigrationChecked = false

    constructor(context: Context) : this(
        dataStore = PreferenceDataStoreFactory.create(
            produceFile = { context.preferencesDataStoreFile("copilot_settings.preferences_pb") }
        ),
        currentInstallId = TherapyActionInstallIdentity(context.noBackupFilesDir).loadOrCreate()
    )

    private val baseTargetScheduleCodec = BaseTargetScheduleCodec()

    val settings: Flow<AppSettings> = dataStore.data.map(::readSettings)

    internal suspend fun readLegacyOpenAiCredential(): String? =
        dataStore.data.first()[KEY_LEGACY_OPENAI_CREDENTIAL]
            ?.trim()
            ?.takeIf { it.isNotBlank() }

    internal suspend fun clearLegacyOpenAiCredentialIfMatches(expected: String): Boolean {
        val normalizedExpected = expected.trim().takeIf { it.isNotBlank() } ?: return false
        var cleared = false
        dataStore.edit { preferences ->
            val current = preferences[KEY_LEGACY_OPENAI_CREDENTIAL]
                ?.trim()
                ?.takeIf { it.isNotBlank() }
            if (current == normalizedExpected) {
                preferences.remove(KEY_LEGACY_OPENAI_CREDENTIAL)
                cleared = true
            }
        }
        return cleared
    }

    private fun readSettings(prefs: Preferences): AppSettings {
        val adaptiveEnabled = resolveAdaptiveControllerEnabled(prefs)
        val (safetyMinTargetMmol, safetyMaxTargetMmol) = resolveSafetyTargetBounds(prefs)
        val isfSource = SensitivitySourcePreference.fromPersisted(prefs[KEY_ISF_RUNTIME_SOURCE])
        val crSource = SensitivitySourcePreference.fromPersisted(prefs[KEY_CR_RUNTIME_SOURCE])
        val sensitivityRevision = (prefs[KEY_SENSITIVITY_SETTINGS_REVISION] ?: 0L)
            .coerceAtLeast(0L)
        val resolvedBaseTargetSchedule = resolveBaseTargetSchedule(
            prefs = prefs,
            minTarget = safetyMinTargetMmol,
            maxTarget = safetyMaxTargetMmol
        )
        val localNightscoutLegacyMigrationAcknowledged =
            prefs[KEY_LOCAL_NIGHTSCOUT_LEGACY_MIGRATION_ACKNOWLEDGED] ?: false
        return AppSettings(
            nightscoutUrl = prefs[KEY_NS_URL].orEmpty(),
            apiSecret = prefs[KEY_NS_SECRET].orEmpty(),
            cloudBaseUrl = prefs[KEY_CLOUD_URL]
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: DEFAULT_CLOUD_BASE_URL,
            clinicalAiConfigState = resolveClinicalAiConfigState(prefs),
            automaticEventAiAnalysisEnabled =
                prefs[KEY_AUTOMATIC_EVENT_AI_ANALYSIS_ENABLED] ?: true,
            uiStyle = resolveUiStyle(prefs[KEY_UI_STYLE]),
            showEventsOnGraph = prefs[KEY_SHOW_EVENTS_ON_GRAPH] ?: true,
            killSwitch = prefs[KEY_KILL_SWITCH] ?: false,
            therapyActionsArmed = therapyActionsArmedForInstallStatic(
                storedInstallId = prefs[KEY_THERAPY_ACTIONS_ARMED_INSTALL_ID],
                currentInstallId = currentInstallId
            ),
            powerSaveUntilMs = prefs[KEY_POWER_SAVE_UNTIL_MS] ?: 0L,
            rootExperimentalEnabled = prefs[KEY_ROOT_EXPERIMENTAL] ?: false,
            localBroadcastIngestEnabled = prefs[KEY_LOCAL_BROADCAST_INGEST] ?: true,
            strictBroadcastSenderValidation = prefs[KEY_STRICT_BROADCAST_VALIDATION] ?: false,
            localNightscoutEnabled = (
                prefs[KEY_LOCAL_NIGHTSCOUT_ENABLED]
                    ?: prefs[KEY_NS_URL].orEmpty().isBlank()
                ) && localNightscoutLegacyMigrationAcknowledged,
            localNightscoutPort = prefs[KEY_LOCAL_NIGHTSCOUT_PORT] ?: DEFAULT_LOCAL_NIGHTSCOUT_PORT,
            localNightscoutLegacyMigrationAcknowledged =
                localNightscoutLegacyMigrationAcknowledged,
            localCommandFallbackEnabled = prefs[KEY_LOCAL_COMMAND_FALLBACK_ENABLED] ?: true,
            localCommandPackage = prefs[KEY_LOCAL_COMMAND_PACKAGE] ?: DEFAULT_LOCAL_COMMAND_PACKAGE,
            localCommandAction = prefs[KEY_LOCAL_COMMAND_ACTION] ?: DEFAULT_LOCAL_COMMAND_ACTION,
            insulinProfileId = normalizeInsulinProfileId(prefs[KEY_INSULIN_PROFILE]),
            enableUamInference = prefs[KEY_ENABLE_UAM_INFERENCE] ?: DEFAULT_ENABLE_UAM_INFERENCE,
            enableUamBoost = prefs[KEY_ENABLE_UAM_BOOST] ?: DEFAULT_ENABLE_UAM_BOOST,
            enableUamExportToAaps = prefs[KEY_ENABLE_UAM_EXPORT] ?: DEFAULT_ENABLE_UAM_EXPORT,
            uamExportMode = resolveUamExportMode(prefs[KEY_UAM_EXPORT_MODE]),
            dryRunExport = prefs[KEY_DRY_RUN_EXPORT] ?: DEFAULT_DRY_RUN_EXPORT,
            enableUamAutoExportCap = prefs[KEY_UAM_AUTO_EXPORT_CAP_ENABLED]
                ?: DEFAULT_UAM_AUTO_EXPORT_CAP_ENABLED,
            uamAutoExportCapGrams = (prefs[KEY_UAM_AUTO_EXPORT_CAP_GRAMS]
                ?: DEFAULT_UAM_AUTO_EXPORT_CAP_GRAMS).coerceIn(
                MIN_UAM_AUTO_EXPORT_CAP_GRAMS,
                MAX_UAM_AUTO_EXPORT_CAP_GRAMS
            ),
            uamLearnedMultiplier = (prefs[KEY_UAM_LEARNED_MULTIPLIER] ?: DEFAULT_UAM_LEARNED_MULTIPLIER)
                .coerceIn(0.8, 1.6),
            uamMinSnackG = prefs[KEY_UAM_MIN_SNACK_G] ?: DEFAULT_UAM_MIN_SNACK_G,
            uamMaxSnackG = prefs[KEY_UAM_MAX_SNACK_G] ?: DEFAULT_UAM_MAX_SNACK_G,
            uamSnackStepG = prefs[KEY_UAM_SNACK_STEP_G] ?: DEFAULT_UAM_SNACK_STEP_G,
            uamBackdateMinutesDefault = prefs[KEY_UAM_BACKDATE_MINUTES] ?: DEFAULT_UAM_BACKDATE_MINUTES,
            uamDisableWhenManualCobActive = prefs[KEY_UAM_DISABLE_MANUAL_COB_ACTIVE] ?: DEFAULT_UAM_DISABLE_MANUAL_COB_ACTIVE,
            uamManualCobThresholdG = prefs[KEY_UAM_MANUAL_COB_THRESHOLD_G] ?: DEFAULT_UAM_MANUAL_COB_THRESHOLD_G,
            uamDisableIfManualCarbsNearby = prefs[KEY_UAM_DISABLE_MANUAL_CARBS_NEARBY] ?: DEFAULT_UAM_DISABLE_MANUAL_CARBS_NEARBY,
            uamManualMergeWindowMinutes = prefs[KEY_UAM_MANUAL_MERGE_WINDOW_MINUTES] ?: DEFAULT_UAM_MANUAL_MERGE_WINDOW_MINUTES,
            uamMaxAbsorbRateGphNormal = prefs[KEY_UAM_MAX_ABSORB_RATE_GPH_NORMAL] ?: DEFAULT_UAM_MAX_ABSORB_RATE_GPH_NORMAL,
            uamMaxAbsorbRateGphBoost = prefs[KEY_UAM_MAX_ABSORB_RATE_GPH_BOOST] ?: DEFAULT_UAM_MAX_ABSORB_RATE_GPH_BOOST,
            uamMaxTotalG = prefs[KEY_UAM_MAX_TOTAL_G] ?: DEFAULT_UAM_MAX_TOTAL_G,
            uamMaxActiveEvents = prefs[KEY_UAM_MAX_ACTIVE_EVENTS] ?: DEFAULT_UAM_MAX_ACTIVE_EVENTS,
            uamCarbMultiplierNormal = prefs[KEY_UAM_CARB_MULTIPLIER_NORMAL] ?: DEFAULT_UAM_CARB_MULTIPLIER_NORMAL,
            uamCarbMultiplierBoost = prefs[KEY_UAM_CARB_MULTIPLIER_BOOST] ?: DEFAULT_UAM_CARB_MULTIPLIER_BOOST,
            uamGAbsThresholdNormal = prefs[KEY_UAM_GABS_THRESHOLD_NORMAL] ?: DEFAULT_UAM_GABS_THRESHOLD_NORMAL,
            uamGAbsThresholdBoost = prefs[KEY_UAM_GABS_THRESHOLD_BOOST] ?: DEFAULT_UAM_GABS_THRESHOLD_BOOST,
            uamMOfNNormalM = prefs[KEY_UAM_M_OF_N_NORMAL_M] ?: DEFAULT_UAM_M_OF_N_NORMAL_M,
            uamMOfNNormalN = prefs[KEY_UAM_M_OF_N_NORMAL_N] ?: DEFAULT_UAM_M_OF_N_NORMAL_N,
            uamMOfNBoostM = prefs[KEY_UAM_M_OF_N_BOOST_M] ?: DEFAULT_UAM_M_OF_N_BOOST_M,
            uamMOfNBoostN = prefs[KEY_UAM_M_OF_N_BOOST_N] ?: DEFAULT_UAM_M_OF_N_BOOST_N,
            uamConfirmConfNormal = prefs[KEY_UAM_CONFIRM_CONF_NORMAL] ?: DEFAULT_UAM_CONFIRM_CONF_NORMAL,
            uamConfirmConfBoost = prefs[KEY_UAM_CONFIRM_CONF_BOOST] ?: DEFAULT_UAM_CONFIRM_CONF_BOOST,
            uamMinConfirmAgeMin = prefs[KEY_UAM_MIN_CONFIRM_AGE_MIN] ?: DEFAULT_UAM_MIN_CONFIRM_AGE_MIN,
            uamExportMinIntervalMin = prefs[KEY_UAM_EXPORT_MIN_INTERVAL_MIN] ?: DEFAULT_UAM_EXPORT_MIN_INTERVAL_MIN,
            uamExportMaxBackdateMin = prefs[KEY_UAM_EXPORT_MAX_BACKDATE_MIN] ?: DEFAULT_UAM_EXPORT_MAX_BACKDATE_MIN,
            carbAbsorptionMaxAgeMinutes = (prefs[KEY_CARB_ABSORPTION_MAX_AGE_MINUTES]
                ?: DEFAULT_CARB_ABSORPTION_MAX_AGE_MINUTES).coerceIn(60, 180),
            carbComputationMaxGrams = (prefs[KEY_CARB_COMPUTATION_MAX_GRAMS]
                ?: DEFAULT_CARB_COMPUTATION_MAX_GRAMS).coerceIn(20.0, 60.0),
            sensorLagCorrectionMode = resolveSensorLagCorrectionMode(prefs[KEY_SENSOR_LAG_CORRECTION_MODE]),
            targetManagerMode = resolveTargetManagerMode(prefs[KEY_TARGET_MANAGER_MODE]),
            targetManagerModeManualOverride = prefs[KEY_TARGET_MANAGER_MANUAL_OVERRIDE] ?: false,
            targetManagerCopilotPriorityEnabled = prefs[KEY_TARGET_MANAGER_COPILOT_PRIORITY] ?: false,
            targetManagerPolicyRevision = prefs[KEY_TARGET_MANAGER_POLICY_REVISION] ?: 0L,
            isfSourcePreference = isfSource,
            crSourcePreference = crSource,
            sensitivitySettingsRevision = sensitivityRevision,
            isfCrShadowMode = prefs[KEY_ISFCR_SHADOW_MODE] ?: DEFAULT_ISFCR_SHADOW_MODE,
            isfCrConfidenceThreshold = prefs[KEY_ISFCR_CONFIDENCE_THRESHOLD] ?: DEFAULT_ISFCR_CONFIDENCE_THRESHOLD,
            isfCrUseActivity = prefs[KEY_ISFCR_USE_ACTIVITY] ?: DEFAULT_ISFCR_USE_ACTIVITY,
            isfCrUseManualTags = prefs[KEY_ISFCR_USE_MANUAL_TAGS] ?: DEFAULT_ISFCR_USE_MANUAL_TAGS,
            isfCrMinIsfEvidencePerHour = prefs[KEY_ISFCR_MIN_ISF_EVIDENCE_PER_HOUR]
                ?: DEFAULT_ISFCR_MIN_ISF_EVIDENCE_PER_HOUR,
            isfCrMinCrEvidencePerHour = prefs[KEY_ISFCR_MIN_CR_EVIDENCE_PER_HOUR]
                ?: DEFAULT_ISFCR_MIN_CR_EVIDENCE_PER_HOUR,
            isfCrCrMaxGapMinutes = prefs[KEY_ISFCR_CR_MAX_GAP_MINUTES]
                ?: DEFAULT_ISFCR_CR_MAX_GAP_MINUTES,
            isfCrCrMaxSensorBlockedRatePct = prefs[KEY_ISFCR_CR_MAX_SENSOR_BLOCKED_RATE_PCT]
                ?: DEFAULT_ISFCR_CR_MAX_SENSOR_BLOCKED_RATE_PCT,
            isfCrCrMaxUamAmbiguityRatePct = prefs[KEY_ISFCR_CR_MAX_UAM_AMBIGUITY_RATE_PCT]
                ?: DEFAULT_ISFCR_CR_MAX_UAM_AMBIGUITY_RATE_PCT,
            isfCrSnapshotRetentionDays = prefs[KEY_ISFCR_SNAPSHOT_RETENTION_DAYS]
                ?: DEFAULT_ISFCR_SNAPSHOT_RETENTION_DAYS,
            isfCrEvidenceRetentionDays = prefs[KEY_ISFCR_EVIDENCE_RETENTION_DAYS]
                ?: DEFAULT_ISFCR_EVIDENCE_RETENTION_DAYS,
            isfCrAutoActivationEnabled = prefs[KEY_ISFCR_AUTO_ACTIVATION_ENABLED]
                ?: DEFAULT_ISFCR_AUTO_ACTIVATION_ENABLED,
            isfCrAutoActivationLookbackHours = prefs[KEY_ISFCR_AUTO_ACTIVATION_LOOKBACK_HOURS]
                ?: DEFAULT_ISFCR_AUTO_ACTIVATION_LOOKBACK_HOURS,
            isfCrAutoActivationMinSamples = prefs[KEY_ISFCR_AUTO_ACTIVATION_MIN_SAMPLES]
                ?: DEFAULT_ISFCR_AUTO_ACTIVATION_MIN_SAMPLES,
            isfCrAutoActivationMinMeanConfidence = prefs[KEY_ISFCR_AUTO_ACTIVATION_MIN_MEAN_CONFIDENCE]
                ?: DEFAULT_ISFCR_AUTO_ACTIVATION_MIN_MEAN_CONFIDENCE,
            isfCrAutoActivationMaxMeanAbsIsfDeltaPct = prefs[KEY_ISFCR_AUTO_ACTIVATION_MAX_MEAN_ABS_ISF_DELTA_PCT]
                ?: DEFAULT_ISFCR_AUTO_ACTIVATION_MAX_MEAN_ABS_ISF_DELTA_PCT,
            isfCrAutoActivationMaxMeanAbsCrDeltaPct = prefs[KEY_ISFCR_AUTO_ACTIVATION_MAX_MEAN_ABS_CR_DELTA_PCT]
                ?: DEFAULT_ISFCR_AUTO_ACTIVATION_MAX_MEAN_ABS_CR_DELTA_PCT,
            isfCrAutoActivationMinSensorQualityScore = prefs[KEY_ISFCR_AUTO_ACTIVATION_MIN_SENSOR_QUALITY_SCORE]
                ?: DEFAULT_ISFCR_AUTO_ACTIVATION_MIN_SENSOR_QUALITY_SCORE,
            isfCrAutoActivationMinSensorFactor = prefs[KEY_ISFCR_AUTO_ACTIVATION_MIN_SENSOR_FACTOR]
                ?: DEFAULT_ISFCR_AUTO_ACTIVATION_MIN_SENSOR_FACTOR,
            isfCrAutoActivationMaxWearConfidencePenalty = prefs[KEY_ISFCR_AUTO_ACTIVATION_MAX_WEAR_CONFIDENCE_PENALTY]
                ?: DEFAULT_ISFCR_AUTO_ACTIVATION_MAX_WEAR_CONFIDENCE_PENALTY,
            isfCrAutoActivationMaxSensorAgeHighRatePct = prefs[KEY_ISFCR_AUTO_ACTIVATION_MAX_SENSOR_AGE_HIGH_RATE_PCT]
                ?: DEFAULT_ISFCR_AUTO_ACTIVATION_MAX_SENSOR_AGE_HIGH_RATE_PCT,
            isfCrAutoActivationMaxSuspectFalseLowRatePct = prefs[KEY_ISFCR_AUTO_ACTIVATION_MAX_SUSPECT_FALSE_LOW_RATE_PCT]
                ?: DEFAULT_ISFCR_AUTO_ACTIVATION_MAX_SUSPECT_FALSE_LOW_RATE_PCT,
            isfCrAutoActivationMinDayTypeRatio = prefs[KEY_ISFCR_AUTO_ACTIVATION_MIN_DAY_TYPE_RATIO]
                ?: DEFAULT_ISFCR_AUTO_ACTIVATION_MIN_DAY_TYPE_RATIO,
            isfCrAutoActivationMaxDayTypeSparseRatePct = prefs[KEY_ISFCR_AUTO_ACTIVATION_MAX_DAY_TYPE_SPARSE_RATE_PCT]
                ?: DEFAULT_ISFCR_AUTO_ACTIVATION_MAX_DAY_TYPE_SPARSE_RATE_PCT,
            isfCrAutoActivationRequireDailyQualityGate = prefs[KEY_ISFCR_AUTO_ACTIVATION_REQUIRE_DAILY_QUALITY_GATE]
                ?: DEFAULT_ISFCR_AUTO_ACTIVATION_REQUIRE_DAILY_QUALITY_GATE,
            isfCrAutoActivationDailyRiskBlockLevel =
                (prefs[KEY_ISFCR_AUTO_ACTIVATION_DAILY_RISK_BLOCK_LEVEL]
                    ?: DEFAULT_ISFCR_AUTO_ACTIVATION_DAILY_RISK_BLOCK_LEVEL).coerceIn(2, 3),
            isfCrAutoActivationMinDailyMatchedSamples = prefs[KEY_ISFCR_AUTO_ACTIVATION_MIN_DAILY_MATCHED_SAMPLES]
                ?: DEFAULT_ISFCR_AUTO_ACTIVATION_MIN_DAILY_MATCHED_SAMPLES,
            isfCrAutoActivationMaxDailyMae30Mmol = prefs[KEY_ISFCR_AUTO_ACTIVATION_MAX_DAILY_MAE_30_MMOL]
                ?: DEFAULT_ISFCR_AUTO_ACTIVATION_MAX_DAILY_MAE_30_MMOL,
            isfCrAutoActivationMaxDailyMae60Mmol = prefs[KEY_ISFCR_AUTO_ACTIVATION_MAX_DAILY_MAE_60_MMOL]
                ?: DEFAULT_ISFCR_AUTO_ACTIVATION_MAX_DAILY_MAE_60_MMOL,
            isfCrAutoActivationMaxHypoRatePct = prefs[KEY_ISFCR_AUTO_ACTIVATION_MAX_HYPO_RATE_PCT]
                ?: DEFAULT_ISFCR_AUTO_ACTIVATION_MAX_HYPO_RATE_PCT,
            isfCrAutoActivationMinDailyCiCoverage30Pct = prefs[KEY_ISFCR_AUTO_ACTIVATION_MIN_DAILY_CI_COVERAGE_30_PCT]
                ?: DEFAULT_ISFCR_AUTO_ACTIVATION_MIN_DAILY_CI_COVERAGE_30_PCT,
            isfCrAutoActivationMinDailyCiCoverage60Pct = prefs[KEY_ISFCR_AUTO_ACTIVATION_MIN_DAILY_CI_COVERAGE_60_PCT]
                ?: DEFAULT_ISFCR_AUTO_ACTIVATION_MIN_DAILY_CI_COVERAGE_60_PCT,
            isfCrAutoActivationMaxDailyCiWidth30Mmol = prefs[KEY_ISFCR_AUTO_ACTIVATION_MAX_DAILY_CI_WIDTH_30_MMOL]
                ?: DEFAULT_ISFCR_AUTO_ACTIVATION_MAX_DAILY_CI_WIDTH_30_MMOL,
            isfCrAutoActivationMaxDailyCiWidth60Mmol = prefs[KEY_ISFCR_AUTO_ACTIVATION_MAX_DAILY_CI_WIDTH_60_MMOL]
                ?: DEFAULT_ISFCR_AUTO_ACTIVATION_MAX_DAILY_CI_WIDTH_60_MMOL,
            isfCrAutoActivationRollingMinRequiredWindows =
                prefs[KEY_ISFCR_AUTO_ACTIVATION_ROLLING_MIN_REQUIRED_WINDOWS]
                    ?: DEFAULT_ISFCR_AUTO_ACTIVATION_ROLLING_MIN_REQUIRED_WINDOWS,
            isfCrAutoActivationRollingMaeRelaxFactor =
                prefs[KEY_ISFCR_AUTO_ACTIVATION_ROLLING_MAE_RELAX_FACTOR]
                    ?: DEFAULT_ISFCR_AUTO_ACTIVATION_ROLLING_MAE_RELAX_FACTOR,
            isfCrAutoActivationRollingCiCoverageRelaxFactor =
                prefs[KEY_ISFCR_AUTO_ACTIVATION_ROLLING_CI_COVERAGE_RELAX_FACTOR]
                    ?: DEFAULT_ISFCR_AUTO_ACTIVATION_ROLLING_CI_COVERAGE_RELAX_FACTOR,
            isfCrAutoActivationRollingCiWidthRelaxFactor =
                prefs[KEY_ISFCR_AUTO_ACTIVATION_ROLLING_CI_WIDTH_RELAX_FACTOR]
                    ?: DEFAULT_ISFCR_AUTO_ACTIVATION_ROLLING_CI_WIDTH_RELAX_FACTOR,
            safetyMinTargetMmol = safetyMinTargetMmol,
            safetyMaxTargetMmol = safetyMaxTargetMmol,
            baseTargetMmol = resolvedBaseTargetSchedule.schedule.defaultTargetMmol,
            baseTargetSchedule = resolvedBaseTargetSchedule.schedule,
            baseTargetScheduleRecoveryReason = resolvedBaseTargetSchedule.recoveryReason,
            postHypoThresholdMmol = (prefs[KEY_POST_HYPO_THRESHOLD_MMOL]
                ?: DEFAULT_POST_HYPO_THRESHOLD_MMOL).coerceIn(safetyMinTargetMmol, safetyMaxTargetMmol),
            postHypoDeltaThresholdMmol5m = prefs[KEY_POST_HYPO_DELTA_THRESHOLD_MMOL_5M] ?: DEFAULT_POST_HYPO_DELTA_THRESHOLD_MMOL_5M,
            postHypoTargetMmol = (prefs[KEY_POST_HYPO_TARGET_MMOL]
                ?: DEFAULT_POST_HYPO_TARGET_MMOL).coerceIn(safetyMinTargetMmol, safetyMaxTargetMmol),
            postHypoDurationMinutes = prefs[KEY_POST_HYPO_DURATION_MINUTES] ?: DEFAULT_POST_HYPO_DURATION_MINUTES,
            postHypoLookbackMinutes = prefs[KEY_POST_HYPO_LOOKBACK_MINUTES] ?: DEFAULT_POST_HYPO_LOOKBACK_MINUTES,
            rulePostHypoEnabled = prefs[KEY_RULE_POST_HYPO_ENABLED] ?: true,
            rulePatternEnabled = prefs[KEY_RULE_PATTERN_ENABLED] ?: true,
            ruleSegmentEnabled = prefs[KEY_RULE_SEGMENT_ENABLED] ?: true,
            adaptiveControllerEnabled = adaptiveEnabled,
            rulePostHypoPriority = prefs[KEY_RULE_POST_HYPO_PRIORITY] ?: DEFAULT_POST_HYPO_PRIORITY,
            rulePatternPriority = prefs[KEY_RULE_PATTERN_PRIORITY] ?: DEFAULT_PATTERN_PRIORITY,
            ruleSegmentPriority = prefs[KEY_RULE_SEGMENT_PRIORITY] ?: DEFAULT_SEGMENT_PRIORITY,
            adaptiveControllerPriority = prefs[KEY_ADAPTIVE_CONTROLLER_PRIORITY] ?: DEFAULT_ADAPTIVE_CONTROLLER_PRIORITY,
            rulePostHypoCooldownMinutes = prefs[KEY_RULE_POST_HYPO_COOLDOWN] ?: DEFAULT_POST_HYPO_COOLDOWN_MIN,
            rulePatternCooldownMinutes = prefs[KEY_RULE_PATTERN_COOLDOWN] ?: DEFAULT_PATTERN_COOLDOWN_MIN,
            ruleSegmentCooldownMinutes = prefs[KEY_RULE_SEGMENT_COOLDOWN] ?: DEFAULT_SEGMENT_COOLDOWN_MIN,
            adaptiveControllerRetargetMinutes = prefs[KEY_ADAPTIVE_CONTROLLER_RETARGET_MINUTES]
                ?: DEFAULT_ADAPTIVE_CONTROLLER_RETARGET_MINUTES,
            adaptiveControllerSafetyProfile = prefs[KEY_ADAPTIVE_CONTROLLER_SAFETY_PROFILE]
                ?: DEFAULT_ADAPTIVE_CONTROLLER_SAFETY_PROFILE,
            adaptiveControllerStaleMaxMinutes = prefs[KEY_ADAPTIVE_CONTROLLER_STALE_MAX_MINUTES]
                ?: DEFAULT_ADAPTIVE_CONTROLLER_STALE_MAX_MINUTES,
            adaptiveControllerMaxActions6h = prefs[KEY_ADAPTIVE_CONTROLLER_MAX_ACTIONS_6H]
                ?: DEFAULT_ADAPTIVE_CONTROLLER_MAX_ACTIONS_6H,
            adaptiveControllerMaxStepMmol = prefs[KEY_ADAPTIVE_CONTROLLER_MAX_STEP_MMOL]
                ?: DEFAULT_ADAPTIVE_CONTROLLER_MAX_STEP_MMOL,
            patternMinSamplesPerWindow = prefs[KEY_PATTERN_MIN_SAMPLES] ?: DEFAULT_PATTERN_MIN_SAMPLES,
            patternMinActiveDaysPerWindow = prefs[KEY_PATTERN_MIN_ACTIVE_DAYS] ?: DEFAULT_PATTERN_MIN_ACTIVE_DAYS,
            patternLowRateTrigger = prefs[KEY_PATTERN_LOW_RATE_TRIGGER] ?: DEFAULT_PATTERN_LOW_RATE_TRIGGER,
            patternHighRateTrigger = prefs[KEY_PATTERN_HIGH_RATE_TRIGGER] ?: DEFAULT_PATTERN_HIGH_RATE_TRIGGER,
            analyticsLookbackDays = prefs[KEY_ANALYTICS_LOOKBACK_DAYS] ?: DEFAULT_ANALYTICS_LOOKBACK_DAYS,
            circadianPatternsEnabled = prefs[KEY_CIRCADIAN_PATTERNS_ENABLED] ?: DEFAULT_CIRCADIAN_PATTERNS_ENABLED,
            circadianStableLookbackDays = prefs[KEY_CIRCADIAN_STABLE_LOOKBACK_DAYS]
                ?: DEFAULT_CIRCADIAN_STABLE_LOOKBACK_DAYS,
            circadianRecencyLookbackDays = prefs[KEY_CIRCADIAN_RECENCY_LOOKBACK_DAYS]
                ?: DEFAULT_CIRCADIAN_RECENCY_LOOKBACK_DAYS,
            circadianUseWeekendSplit = prefs[KEY_CIRCADIAN_USE_WEEKEND_SPLIT]
                ?: DEFAULT_CIRCADIAN_USE_WEEKEND_SPLIT,
            circadianUseReplayResidualBias = prefs[KEY_CIRCADIAN_USE_REPLAY_RESIDUAL_BIAS]
                ?: DEFAULT_CIRCADIAN_USE_REPLAY_RESIDUAL_BIAS,
            circadianForecastWeight30 = prefs[KEY_CIRCADIAN_FORECAST_WEIGHT_30]
                ?: DEFAULT_CIRCADIAN_FORECAST_WEIGHT_30,
            circadianForecastWeight60 = prefs[KEY_CIRCADIAN_FORECAST_WEIGHT_60]
                ?: DEFAULT_CIRCADIAN_FORECAST_WEIGHT_60,
            softAlertEnabled = prefs[KEY_SOFT_ALERT_ENABLED] ?: DEFAULT_SOFT_ALERT_ENABLED,
            watch60AlertEnabled = prefs[KEY_WATCH_60_ALERT_ENABLED] ?: DEFAULT_WATCH_60_ALERT_ENABLED,
            warning30AlertEnabled = prefs[KEY_WARNING_30_ALERT_ENABLED] ?: DEFAULT_WARNING_30_ALERT_ENABLED,
            softHighAlertEnabled = prefs[KEY_SOFT_HIGH_ALERT_ENABLED] ?: DEFAULT_SOFT_HIGH_ALERT_ENABLED,
            critical5AlertEnabled = prefs[KEY_CRITICAL_5_ALERT_ENABLED] ?: DEFAULT_CRITICAL_5_ALERT_ENABLED,
            lowNowAlertEnabled = prefs[KEY_LOW_NOW_ALERT_ENABLED] ?: DEFAULT_LOW_NOW_ALERT_ENABLED,
            softAlertLowMmol = (prefs[KEY_SOFT_ALERT_LOW_MMOL] ?: DEFAULT_SOFT_ALERT_LOW_MMOL)
                .coerceIn(3.6, 6.0),
            softAlertHighMmol = (prefs[KEY_SOFT_ALERT_HIGH_MMOL] ?: DEFAULT_SOFT_ALERT_HIGH_MMOL)
                .coerceIn(7.0, 13.9),
            urgentLowMmol = (prefs[KEY_URGENT_LOW_MMOL] ?: DEFAULT_URGENT_LOW_MMOL)
                .coerceIn(3.0, 4.4),
            softAlertRepeatMinutes = (prefs[KEY_SOFT_ALERT_REPEAT_MINUTES] ?: DEFAULT_SOFT_ALERT_REPEAT_MINUTES)
                .coerceIn(5, 30),
            strongLowRepeatMinutes = (prefs[KEY_STRONG_LOW_REPEAT_MINUTES] ?: DEFAULT_STRONG_LOW_REPEAT_MINUTES)
                .coerceIn(1, 10),
            softAlertUseConfidenceBand = prefs[KEY_SOFT_ALERT_USE_CONFIDENCE_BAND]
                ?: DEFAULT_SOFT_ALERT_USE_CONFIDENCE_BAND,
            softAlertAudioStartMs = prefs[KEY_SOFT_ALERT_AUDIO_START_MS] ?: DEFAULT_SOFT_ALERT_AUDIO_START_MS,
            softAlertAudioDurationMs = (prefs[KEY_SOFT_ALERT_AUDIO_DURATION_MS] ?: DEFAULT_SOFT_ALERT_AUDIO_DURATION_MS)
                .takeIf { it in 1_000..5_000 } ?: DEFAULT_SOFT_ALERT_AUDIO_DURATION_MS,
            softAlertAudioUri = prefs[KEY_SOFT_ALERT_AUDIO_URI],
            softAlertAudioDisplayName = prefs[KEY_SOFT_ALERT_AUDIO_DISPLAY_NAME],
            criticalAlertAudio1StartMs = prefs[KEY_CRITICAL_ALERT_AUDIO1_START_MS] ?: DEFAULT_CRITICAL_ALERT_AUDIO1_START_MS,
            criticalAlertAudio1DurationMs = prefs[KEY_CRITICAL_ALERT_AUDIO1_DURATION_MS] ?: DEFAULT_CRITICAL_ALERT_AUDIO1_DURATION_MS,
            criticalAlertAudio1Uri = prefs[KEY_CRITICAL_ALERT_AUDIO1_URI],
            criticalAlertAudio1DisplayName = prefs[KEY_CRITICAL_ALERT_AUDIO1_DISPLAY_NAME],
            criticalAlertAudio2StartMs = prefs[KEY_CRITICAL_ALERT_AUDIO2_START_MS] ?: DEFAULT_CRITICAL_ALERT_AUDIO2_START_MS,
            criticalAlertAudio2DurationMs = prefs[KEY_CRITICAL_ALERT_AUDIO2_DURATION_MS] ?: DEFAULT_CRITICAL_ALERT_AUDIO2_DURATION_MS,
            criticalAlertAudio2Uri = prefs[KEY_CRITICAL_ALERT_AUDIO2_URI],
            criticalAlertAudio2DisplayName = prefs[KEY_CRITICAL_ALERT_AUDIO2_DISPLAY_NAME],
            maxActionsIn6Hours = prefs[KEY_MAX_ACTIONS_6H] ?: DEFAULT_MAX_ACTIONS_6H,
            staleDataMaxMinutes = prefs[KEY_STALE_DATA_MAX_MINUTES] ?: DEFAULT_STALE_DATA_MAX_MINUTES,
            exportFolderUri = prefs[KEY_EXPORT_URI],
            energyProfile = resolveEnergyProfileSettings(prefs),
            mealPortions = resolveMealPortionSettings(prefs)
        )
    }

    suspend fun update(updater: (AppSettings) -> AppSettings) {
        updateInternal(allowSensitivityRuntimeChange = false, updater = updater)
    }

    private suspend fun updateInternal(
        allowSensitivityRuntimeChange: Boolean,
        updater: (AppSettings) -> AppSettings
    ): Pair<AppSettings, AppSettings> {
        lateinit var before: AppSettings
        lateinit var applied: AppSettings
        dataStore.edit { prefs ->
            requireCurrentSafetyTargetBounds(prefs)
            val current = readSettings(prefs)
            val next = updater(current)
            require(next.mealPortions.isValid()) { "Invalid meal portion ranges or defaults" }
            val sensitivityRuntimeChanged =
                current.sensitivityRuntimeFingerprint() != next.sensitivityRuntimeFingerprint()
            require(allowSensitivityRuntimeChange || !sensitivityRuntimeChanged) {
                "sensitivity runtime settings require the leased automation command"
            }
            val nextSensitivitySettingsRevision = if (sensitivityRuntimeChanged) {
                incrementRevision(current.sensitivitySettingsRevision)
            } else {
                current.sensitivitySettingsRevision
            }
            writeClinicalAiConfigState(
                preferences = prefs,
                currentState = current.clinicalAiConfigState,
                nextState = next.clinicalAiConfigState
            )
            prefs[KEY_NS_URL] = next.nightscoutUrl
            prefs[KEY_NS_SECRET] = next.apiSecret
            prefs[KEY_CLOUD_URL] = next.cloudBaseUrl
            prefs[KEY_AUTOMATIC_EVENT_AI_ANALYSIS_ENABLED] =
                next.automaticEventAiAnalysisEnabled
            prefs[KEY_UI_STYLE] = next.uiStyle.name
            prefs[KEY_SHOW_EVENTS_ON_GRAPH] = next.showEventsOnGraph
            prefs[KEY_KILL_SWITCH] = next.killSwitch
            if (next.powerSaveUntilMs <= 0L) {
                prefs.remove(KEY_POWER_SAVE_UNTIL_MS)
            } else {
                prefs[KEY_POWER_SAVE_UNTIL_MS] = next.powerSaveUntilMs
            }
            prefs[KEY_ROOT_EXPERIMENTAL] = next.rootExperimentalEnabled
            prefs[KEY_LOCAL_BROADCAST_INGEST] = next.localBroadcastIngestEnabled
            prefs[KEY_STRICT_BROADCAST_VALIDATION] = next.strictBroadcastSenderValidation
            prefs[KEY_LOCAL_NIGHTSCOUT_ENABLED] = next.localNightscoutEnabled
            prefs[KEY_LOCAL_NIGHTSCOUT_PORT] = next.localNightscoutPort
            prefs[KEY_LOCAL_NIGHTSCOUT_LEGACY_MIGRATION_ACKNOWLEDGED] =
                next.localNightscoutLegacyMigrationAcknowledged
            prefs[KEY_LOCAL_COMMAND_FALLBACK_ENABLED] = next.localCommandFallbackEnabled
            prefs[KEY_LOCAL_COMMAND_PACKAGE] = next.localCommandPackage
            prefs[KEY_LOCAL_COMMAND_ACTION] = next.localCommandAction
            prefs[KEY_INSULIN_PROFILE] = normalizeInsulinProfileId(next.insulinProfileId)
            prefs[KEY_ENABLE_UAM_INFERENCE] = next.enableUamInference
            prefs[KEY_ENABLE_UAM_BOOST] = next.enableUamBoost
            prefs[KEY_ENABLE_UAM_EXPORT] = next.enableUamExportToAaps
            prefs[KEY_UAM_EXPORT_MODE] = next.uamExportMode.name
            prefs[KEY_DRY_RUN_EXPORT] = next.dryRunExport
            prefs[KEY_UAM_AUTO_EXPORT_CAP_ENABLED] = next.enableUamAutoExportCap
            prefs[KEY_UAM_AUTO_EXPORT_CAP_GRAMS] = next.uamAutoExportCapGrams.coerceIn(
                MIN_UAM_AUTO_EXPORT_CAP_GRAMS,
                MAX_UAM_AUTO_EXPORT_CAP_GRAMS
            )
            prefs[KEY_UAM_LEARNED_MULTIPLIER] = next.uamLearnedMultiplier.coerceIn(0.8, 1.6)
            prefs[KEY_UAM_MIN_SNACK_G] = next.uamMinSnackG
            prefs[KEY_UAM_MAX_SNACK_G] = next.uamMaxSnackG
            prefs[KEY_UAM_SNACK_STEP_G] = next.uamSnackStepG
            prefs[KEY_UAM_BACKDATE_MINUTES] = next.uamBackdateMinutesDefault
            prefs[KEY_UAM_DISABLE_MANUAL_COB_ACTIVE] = next.uamDisableWhenManualCobActive
            prefs[KEY_UAM_MANUAL_COB_THRESHOLD_G] = next.uamManualCobThresholdG
            prefs[KEY_UAM_DISABLE_MANUAL_CARBS_NEARBY] = next.uamDisableIfManualCarbsNearby
            prefs[KEY_UAM_MANUAL_MERGE_WINDOW_MINUTES] = next.uamManualMergeWindowMinutes
            prefs[KEY_UAM_MAX_ABSORB_RATE_GPH_NORMAL] = next.uamMaxAbsorbRateGphNormal
            prefs[KEY_UAM_MAX_ABSORB_RATE_GPH_BOOST] = next.uamMaxAbsorbRateGphBoost
            prefs[KEY_UAM_MAX_TOTAL_G] = next.uamMaxTotalG
            prefs[KEY_UAM_MAX_ACTIVE_EVENTS] = next.uamMaxActiveEvents
            prefs[KEY_UAM_CARB_MULTIPLIER_NORMAL] = next.uamCarbMultiplierNormal
            prefs[KEY_UAM_CARB_MULTIPLIER_BOOST] = next.uamCarbMultiplierBoost
            prefs[KEY_UAM_GABS_THRESHOLD_NORMAL] = next.uamGAbsThresholdNormal
            prefs[KEY_UAM_GABS_THRESHOLD_BOOST] = next.uamGAbsThresholdBoost
            prefs[KEY_UAM_M_OF_N_NORMAL_M] = next.uamMOfNNormalM
            prefs[KEY_UAM_M_OF_N_NORMAL_N] = next.uamMOfNNormalN
            prefs[KEY_UAM_M_OF_N_BOOST_M] = next.uamMOfNBoostM
            prefs[KEY_UAM_M_OF_N_BOOST_N] = next.uamMOfNBoostN
            prefs[KEY_UAM_CONFIRM_CONF_NORMAL] = next.uamConfirmConfNormal
            prefs[KEY_UAM_CONFIRM_CONF_BOOST] = next.uamConfirmConfBoost
            prefs[KEY_UAM_MIN_CONFIRM_AGE_MIN] = next.uamMinConfirmAgeMin
            prefs[KEY_UAM_EXPORT_MIN_INTERVAL_MIN] = next.uamExportMinIntervalMin
            prefs[KEY_UAM_EXPORT_MAX_BACKDATE_MIN] = next.uamExportMaxBackdateMin
            prefs[KEY_SENSOR_LAG_CORRECTION_MODE] = next.sensorLagCorrectionMode.name
            prefs[KEY_ISF_RUNTIME_SOURCE] = next.isfSourcePreference.name
            prefs[KEY_CR_RUNTIME_SOURCE] = next.crSourcePreference.name
            prefs[KEY_SENSITIVITY_SETTINGS_REVISION] = nextSensitivitySettingsRevision
            prefs[KEY_ISFCR_SHADOW_MODE] = next.isfCrShadowMode
            prefs[KEY_ISFCR_CONFIDENCE_THRESHOLD] = next.isfCrConfidenceThreshold.coerceIn(0.2, 0.95)
            prefs[KEY_ISFCR_USE_ACTIVITY] = next.isfCrUseActivity
            prefs[KEY_ISFCR_USE_MANUAL_TAGS] = next.isfCrUseManualTags
            prefs[KEY_ISFCR_MIN_ISF_EVIDENCE_PER_HOUR] = next.isfCrMinIsfEvidencePerHour.coerceIn(0, 12)
            prefs[KEY_ISFCR_MIN_CR_EVIDENCE_PER_HOUR] = next.isfCrMinCrEvidencePerHour.coerceIn(0, 12)
            prefs[KEY_ISFCR_CR_MAX_GAP_MINUTES] = next.isfCrCrMaxGapMinutes.coerceIn(10, 60)
            prefs[KEY_ISFCR_CR_MAX_SENSOR_BLOCKED_RATE_PCT] =
                next.isfCrCrMaxSensorBlockedRatePct.coerceIn(0.0, 100.0)
            prefs[KEY_ISFCR_CR_MAX_UAM_AMBIGUITY_RATE_PCT] =
                next.isfCrCrMaxUamAmbiguityRatePct.coerceIn(0.0, 100.0)
            prefs[KEY_ISFCR_SNAPSHOT_RETENTION_DAYS] = next.isfCrSnapshotRetentionDays.coerceIn(30, 730)
            prefs[KEY_ISFCR_EVIDENCE_RETENTION_DAYS] = next.isfCrEvidenceRetentionDays.coerceIn(30, 1095)
            prefs[KEY_ISFCR_AUTO_ACTIVATION_ENABLED] = next.isfCrAutoActivationEnabled
            prefs[KEY_ISFCR_AUTO_ACTIVATION_LOOKBACK_HOURS] =
                next.isfCrAutoActivationLookbackHours.coerceIn(6, 72)
            prefs[KEY_ISFCR_AUTO_ACTIVATION_MIN_SAMPLES] = next.isfCrAutoActivationMinSamples.coerceIn(12, 288)
            prefs[KEY_ISFCR_AUTO_ACTIVATION_MIN_MEAN_CONFIDENCE] =
                next.isfCrAutoActivationMinMeanConfidence.coerceIn(0.2, 0.95)
            prefs[KEY_ISFCR_AUTO_ACTIVATION_MAX_MEAN_ABS_ISF_DELTA_PCT] =
                next.isfCrAutoActivationMaxMeanAbsIsfDeltaPct.coerceIn(5.0, 100.0)
            prefs[KEY_ISFCR_AUTO_ACTIVATION_MAX_MEAN_ABS_CR_DELTA_PCT] =
                next.isfCrAutoActivationMaxMeanAbsCrDeltaPct.coerceIn(5.0, 100.0)
            prefs[KEY_ISFCR_AUTO_ACTIVATION_MIN_SENSOR_QUALITY_SCORE] =
                next.isfCrAutoActivationMinSensorQualityScore.coerceIn(0.0, 1.0)
            prefs[KEY_ISFCR_AUTO_ACTIVATION_MIN_SENSOR_FACTOR] =
                next.isfCrAutoActivationMinSensorFactor.coerceIn(0.0, 1.0)
            prefs[KEY_ISFCR_AUTO_ACTIVATION_MAX_WEAR_CONFIDENCE_PENALTY] =
                next.isfCrAutoActivationMaxWearConfidencePenalty.coerceIn(0.0, 1.0)
            prefs[KEY_ISFCR_AUTO_ACTIVATION_MAX_SENSOR_AGE_HIGH_RATE_PCT] =
                next.isfCrAutoActivationMaxSensorAgeHighRatePct.coerceIn(0.0, 100.0)
            prefs[KEY_ISFCR_AUTO_ACTIVATION_MAX_SUSPECT_FALSE_LOW_RATE_PCT] =
                next.isfCrAutoActivationMaxSuspectFalseLowRatePct.coerceIn(0.0, 100.0)
            prefs[KEY_ISFCR_AUTO_ACTIVATION_MIN_DAY_TYPE_RATIO] =
                next.isfCrAutoActivationMinDayTypeRatio.coerceIn(0.0, 1.0)
            prefs[KEY_ISFCR_AUTO_ACTIVATION_MAX_DAY_TYPE_SPARSE_RATE_PCT] =
                next.isfCrAutoActivationMaxDayTypeSparseRatePct.coerceIn(0.0, 100.0)
            prefs[KEY_ISFCR_AUTO_ACTIVATION_REQUIRE_DAILY_QUALITY_GATE] =
                next.isfCrAutoActivationRequireDailyQualityGate
            prefs[KEY_ISFCR_AUTO_ACTIVATION_DAILY_RISK_BLOCK_LEVEL] =
                next.isfCrAutoActivationDailyRiskBlockLevel.coerceIn(2, 3)
            prefs[KEY_ISFCR_AUTO_ACTIVATION_MIN_DAILY_MATCHED_SAMPLES] =
                next.isfCrAutoActivationMinDailyMatchedSamples.coerceIn(24, 720)
            prefs[KEY_ISFCR_AUTO_ACTIVATION_MAX_DAILY_MAE_30_MMOL] =
                next.isfCrAutoActivationMaxDailyMae30Mmol.coerceIn(0.3, 4.0)
            prefs[KEY_ISFCR_AUTO_ACTIVATION_MAX_DAILY_MAE_60_MMOL] =
                next.isfCrAutoActivationMaxDailyMae60Mmol.coerceIn(0.5, 6.0)
            prefs[KEY_ISFCR_AUTO_ACTIVATION_MAX_HYPO_RATE_PCT] =
                next.isfCrAutoActivationMaxHypoRatePct.coerceIn(0.5, 30.0)
            prefs[KEY_ISFCR_AUTO_ACTIVATION_MIN_DAILY_CI_COVERAGE_30_PCT] =
                next.isfCrAutoActivationMinDailyCiCoverage30Pct.coerceIn(20.0, 99.0)
            prefs[KEY_ISFCR_AUTO_ACTIVATION_MIN_DAILY_CI_COVERAGE_60_PCT] =
                next.isfCrAutoActivationMinDailyCiCoverage60Pct.coerceIn(20.0, 99.0)
            prefs[KEY_ISFCR_AUTO_ACTIVATION_MAX_DAILY_CI_WIDTH_30_MMOL] =
                next.isfCrAutoActivationMaxDailyCiWidth30Mmol.coerceIn(0.3, 6.0)
            prefs[KEY_ISFCR_AUTO_ACTIVATION_MAX_DAILY_CI_WIDTH_60_MMOL] =
                next.isfCrAutoActivationMaxDailyCiWidth60Mmol.coerceIn(0.5, 8.0)
            prefs[KEY_ISFCR_AUTO_ACTIVATION_ROLLING_MIN_REQUIRED_WINDOWS] =
                next.isfCrAutoActivationRollingMinRequiredWindows.coerceIn(1, 3)
            prefs[KEY_ISFCR_AUTO_ACTIVATION_ROLLING_MAE_RELAX_FACTOR] =
                next.isfCrAutoActivationRollingMaeRelaxFactor.coerceIn(1.0, 1.5)
            prefs[KEY_ISFCR_AUTO_ACTIVATION_ROLLING_CI_COVERAGE_RELAX_FACTOR] =
                next.isfCrAutoActivationRollingCiCoverageRelaxFactor.coerceIn(0.70, 1.0)
            prefs[KEY_ISFCR_AUTO_ACTIVATION_ROLLING_CI_WIDTH_RELAX_FACTOR] =
                next.isfCrAutoActivationRollingCiWidthRelaxFactor.coerceIn(1.0, 1.5)
            val normalizedSafetyMin = current.safetyMinTargetMmol
            val normalizedSafetyMax = current.safetyMaxTargetMmol
            prefs[KEY_SAFETY_MIN_TARGET_MMOL] = normalizedSafetyMin
            prefs[KEY_SAFETY_MAX_TARGET_MMOL] = normalizedSafetyMax
            prefs[KEY_BASE_TARGET_MMOL] = clampTargetToBounds(
                current.baseTargetSchedule.defaultTargetMmol,
                normalizedSafetyMin,
                normalizedSafetyMax
            )
            prefs[KEY_POST_HYPO_THRESHOLD_MMOL] =
                next.postHypoThresholdMmol.coerceIn(normalizedSafetyMin, normalizedSafetyMax)
            prefs[KEY_POST_HYPO_DELTA_THRESHOLD_MMOL_5M] = next.postHypoDeltaThresholdMmol5m
            prefs[KEY_POST_HYPO_TARGET_MMOL] =
                next.postHypoTargetMmol.coerceIn(normalizedSafetyMin, normalizedSafetyMax)
            prefs[KEY_POST_HYPO_DURATION_MINUTES] = next.postHypoDurationMinutes
            prefs[KEY_POST_HYPO_LOOKBACK_MINUTES] = next.postHypoLookbackMinutes
            prefs[KEY_RULE_POST_HYPO_ENABLED] = next.rulePostHypoEnabled
            prefs[KEY_RULE_PATTERN_ENABLED] = next.rulePatternEnabled
            prefs[KEY_RULE_SEGMENT_ENABLED] = next.ruleSegmentEnabled
            prefs[KEY_ADAPTIVE_CONTROLLER_ENABLED] = next.adaptiveControllerEnabled
            prefs[KEY_ADAPTIVE_DEFAULT_MIGRATION_DONE] = true
            prefs[KEY_RULE_POST_HYPO_PRIORITY] = next.rulePostHypoPriority
            prefs[KEY_RULE_PATTERN_PRIORITY] = next.rulePatternPriority
            prefs[KEY_RULE_SEGMENT_PRIORITY] = next.ruleSegmentPriority
            prefs[KEY_ADAPTIVE_CONTROLLER_PRIORITY] = next.adaptiveControllerPriority
            prefs[KEY_RULE_POST_HYPO_COOLDOWN] = next.rulePostHypoCooldownMinutes
            prefs[KEY_RULE_PATTERN_COOLDOWN] = next.rulePatternCooldownMinutes
            prefs[KEY_RULE_SEGMENT_COOLDOWN] = next.ruleSegmentCooldownMinutes
            prefs[KEY_ADAPTIVE_CONTROLLER_RETARGET_MINUTES] = next.adaptiveControllerRetargetMinutes
            prefs[KEY_ADAPTIVE_CONTROLLER_SAFETY_PROFILE] = next.adaptiveControllerSafetyProfile
            prefs[KEY_ADAPTIVE_CONTROLLER_STALE_MAX_MINUTES] = next.adaptiveControllerStaleMaxMinutes
            prefs[KEY_ADAPTIVE_CONTROLLER_MAX_ACTIONS_6H] = next.adaptiveControllerMaxActions6h
            prefs[KEY_ADAPTIVE_CONTROLLER_MAX_STEP_MMOL] = next.adaptiveControllerMaxStepMmol
            prefs[KEY_PATTERN_MIN_SAMPLES] = next.patternMinSamplesPerWindow
            prefs[KEY_PATTERN_MIN_ACTIVE_DAYS] = next.patternMinActiveDaysPerWindow
            prefs[KEY_PATTERN_LOW_RATE_TRIGGER] = next.patternLowRateTrigger
            prefs[KEY_PATTERN_HIGH_RATE_TRIGGER] = next.patternHighRateTrigger
            prefs[KEY_ANALYTICS_LOOKBACK_DAYS] = next.analyticsLookbackDays
            prefs[KEY_CIRCADIAN_PATTERNS_ENABLED] = next.circadianPatternsEnabled
            prefs[KEY_CIRCADIAN_STABLE_LOOKBACK_DAYS] = next.circadianStableLookbackDays
            prefs[KEY_CIRCADIAN_RECENCY_LOOKBACK_DAYS] = next.circadianRecencyLookbackDays
            prefs[KEY_CIRCADIAN_USE_WEEKEND_SPLIT] = next.circadianUseWeekendSplit
            prefs[KEY_CIRCADIAN_USE_REPLAY_RESIDUAL_BIAS] = next.circadianUseReplayResidualBias
            prefs[KEY_CIRCADIAN_FORECAST_WEIGHT_30] = next.circadianForecastWeight30
            prefs[KEY_CIRCADIAN_FORECAST_WEIGHT_60] = next.circadianForecastWeight60
            val normalizedSoftLow = next.softAlertLowMmol.coerceIn(3.6, 6.0)
            val normalizedUrgentLow = next.urgentLowMmol.coerceIn(3.0, 4.4).coerceAtMost(normalizedSoftLow)
            val normalizedSoftHigh = next.softAlertHighMmol
                .coerceIn(7.0, 13.9)
                .coerceAtLeast(normalizedSoftLow + 0.1)
            prefs[KEY_SOFT_ALERT_ENABLED] = next.softAlertEnabled
            prefs[KEY_WATCH_60_ALERT_ENABLED] = next.watch60AlertEnabled
            prefs[KEY_WARNING_30_ALERT_ENABLED] = next.warning30AlertEnabled
            prefs[KEY_SOFT_HIGH_ALERT_ENABLED] = next.softHighAlertEnabled
            prefs[KEY_CRITICAL_5_ALERT_ENABLED] = next.critical5AlertEnabled
            prefs[KEY_LOW_NOW_ALERT_ENABLED] = next.lowNowAlertEnabled
            prefs[KEY_SOFT_ALERT_LOW_MMOL] = normalizedSoftLow
            prefs[KEY_SOFT_ALERT_HIGH_MMOL] = normalizedSoftHigh
            prefs[KEY_URGENT_LOW_MMOL] = normalizedUrgentLow
            prefs[KEY_SOFT_ALERT_REPEAT_MINUTES] = next.softAlertRepeatMinutes.coerceIn(5, 30)
            prefs[KEY_STRONG_LOW_REPEAT_MINUTES] = next.strongLowRepeatMinutes.coerceIn(1, 10)
            prefs[KEY_SOFT_ALERT_USE_CONFIDENCE_BAND] = next.softAlertUseConfidenceBand
            prefs[KEY_SOFT_ALERT_AUDIO_START_MS] = next.softAlertAudioStartMs.coerceAtLeast(0)
            prefs[KEY_SOFT_ALERT_AUDIO_DURATION_MS] = next.softAlertAudioDurationMs.coerceIn(1_000, 5_000)
            if (next.softAlertAudioUri.isNullOrBlank()) {
                prefs.remove(KEY_SOFT_ALERT_AUDIO_URI)
            } else {
                prefs[KEY_SOFT_ALERT_AUDIO_URI] = next.softAlertAudioUri
            }
            if (next.softAlertAudioDisplayName.isNullOrBlank()) {
                prefs.remove(KEY_SOFT_ALERT_AUDIO_DISPLAY_NAME)
            } else {
                prefs[KEY_SOFT_ALERT_AUDIO_DISPLAY_NAME] = next.softAlertAudioDisplayName
            }
            prefs[KEY_CRITICAL_ALERT_AUDIO1_START_MS] = next.criticalAlertAudio1StartMs.coerceAtLeast(0)
            prefs[KEY_CRITICAL_ALERT_AUDIO1_DURATION_MS] = next.criticalAlertAudio1DurationMs.coerceIn(15_000, 30_000)
            if (next.criticalAlertAudio1Uri.isNullOrBlank()) {
                prefs.remove(KEY_CRITICAL_ALERT_AUDIO1_URI)
            } else {
                prefs[KEY_CRITICAL_ALERT_AUDIO1_URI] = next.criticalAlertAudio1Uri
            }
            if (next.criticalAlertAudio1DisplayName.isNullOrBlank()) {
                prefs.remove(KEY_CRITICAL_ALERT_AUDIO1_DISPLAY_NAME)
            } else {
                prefs[KEY_CRITICAL_ALERT_AUDIO1_DISPLAY_NAME] = next.criticalAlertAudio1DisplayName
            }
            prefs[KEY_CRITICAL_ALERT_AUDIO2_START_MS] = next.criticalAlertAudio2StartMs.coerceAtLeast(0)
            prefs[KEY_CRITICAL_ALERT_AUDIO2_DURATION_MS] = next.criticalAlertAudio2DurationMs.coerceIn(15_000, 30_000)
            if (next.criticalAlertAudio2Uri.isNullOrBlank()) {
                prefs.remove(KEY_CRITICAL_ALERT_AUDIO2_URI)
            } else {
                prefs[KEY_CRITICAL_ALERT_AUDIO2_URI] = next.criticalAlertAudio2Uri
            }
            if (next.criticalAlertAudio2DisplayName.isNullOrBlank()) {
                prefs.remove(KEY_CRITICAL_ALERT_AUDIO2_DISPLAY_NAME)
            } else {
                prefs[KEY_CRITICAL_ALERT_AUDIO2_DISPLAY_NAME] = next.criticalAlertAudio2DisplayName
            }
            writeRequestedSafetyFields(
                prefs = prefs,
                mutation = SafetyLimitsMutation(
                    maxActionsIn6Hours = next.maxActionsIn6Hours,
                    staleDataMaxMinutes = next.staleDataMaxMinutes,
                    carbAbsorptionMaxAgeMinutes = next.carbAbsorptionMaxAgeMinutes,
                    carbComputationMaxGrams = next.carbComputationMaxGrams
                )
            )
            if (next.exportFolderUri.isNullOrBlank()) {
                prefs.remove(KEY_EXPORT_URI)
            } else {
                prefs[KEY_EXPORT_URI] = next.exportFolderUri
            }
            writeEnergyProfileSettings(prefs, next.energyProfile)
            writeMealPortionSettings(prefs, next.mealPortions)
            before = current
            applied = readSettings(prefs)
        }
        return before to applied
    }

    internal suspend fun beginSensitivitySettingsMutation(
        updater: (AppSettings) -> AppSettings
    ): SensitivitySettingsMutation {
        val (before, applied) = updateInternal(allowSensitivityRuntimeChange = true) { current ->
            val requested = updater(current)
            require(requested.withSensitivityRuntimeSettingsFrom(current) == current) {
                "leased sensitivity mutation may change sensitivity runtime settings only"
            }
            requested
        }
        return SensitivitySettingsMutation(
            before = before,
            applied = applied,
            beforeFingerprint = before.sensitivityRuntimeFingerprint(),
            appliedFingerprint = applied.sensitivityRuntimeFingerprint(),
            changed = before.sensitivityRuntimeFingerprint() != applied.sensitivityRuntimeFingerprint()
        )
    }

    suspend fun setClinicalAiConfig(config: ClinicalAiProviderConfig) {
        dataStore.edit { preferences ->
            writeClinicalAiConfig(preferences, config)
        }
    }

    suspend fun setEnergyProfileSettings(value: EnergyProfileSettings) {
        update { current -> current.copy(energyProfile = value) }
    }

    suspend fun setMealPortionSettings(value: MealPortionSettings) {
        update { it.copy(mealPortions = value) }
    }

    private fun writeMealPortionSettings(prefs: MutablePreferences, value: MealPortionSettings) {
        for (portion in MealPortion.entries) {
            val range = value.range(portion)
            prefs[doublePreferencesKey("meal_portion_${portion.name}_min")] = range.minGrams
            prefs[doublePreferencesKey("meal_portion_${portion.name}_max")] = range.maxGrams
            prefs[doublePreferencesKey("meal_portion_${portion.name}_default")] = range.defaultGrams
        }
        prefs[KEY_MEAL_SHOW_CALORIES] = value.showCalories
    }

    private fun resolveMealPortionSettings(prefs: Preferences): MealPortionSettings {
        val defaults = MealPortionSettings()
        fun range(portion: MealPortion): MealPortionRange {
            val fallback = defaults.range(portion)
            return MealPortionRange(
                prefs[doublePreferencesKey("meal_portion_${portion.name}_min")] ?: fallback.minGrams,
                prefs[doublePreferencesKey("meal_portion_${portion.name}_max")] ?: fallback.maxGrams,
                prefs[doublePreferencesKey("meal_portion_${portion.name}_default")] ?: fallback.defaultGrams
            )
        }
        return MealPortionSettings(
            range(MealPortion.SMALL), range(MealPortion.MEDIUM), range(MealPortion.LARGE),
            prefs[KEY_MEAL_SHOW_CALORIES] ?: false
        ).takeIf { it.isValid() } ?: defaults
    }

    private fun writeEnergyProfileSettings(prefs: MutablePreferences, value: EnergyProfileSettings) {
        prefs[KEY_ENERGY_PROFILE_ENABLED] = value.enabled
        prefs[KEY_ENERGY_PROFILE_FORECAST_ACTIVITY_INFLUENCE_ENABLED] =
            value.forecastActivityInfluenceEnabled
        value.birthDateEpochDay?.let { prefs[KEY_BIRTH_DATE_EPOCH_DAY] = it }
            ?: prefs.remove(KEY_BIRTH_DATE_EPOCH_DAY)
        prefs[KEY_PHYSIOLOGICAL_SEX] = value.physiologicalSex.name
        value.heightCm?.let { prefs[KEY_HEIGHT_CM] = it } ?: prefs.remove(KEY_HEIGHT_CM)
        value.weightKg?.let { prefs[KEY_WEIGHT_KG] = it } ?: prefs.remove(KEY_WEIGHT_KG)
        prefs[KEY_FOOD_PROFILE_MODE] = value.foodProfileMode.name
        prefs[KEY_MANUAL_FOOD_PROFILE] = value.manualFoodProfile.name
        prefs[KEY_ACTIVITY_PROFILE_MODE] = value.activityProfileMode.name
        prefs[KEY_MANUAL_ACTIVITY_PROFILE] = value.manualActivityProfile.name
        prefs[KEY_CALORIE_GOAL_MODE] = value.calorieGoalMode.name
        value.manualCalorieTargetKcal?.let { prefs[KEY_MANUAL_CALORIE_TARGET] = it }
            ?: prefs.remove(KEY_MANUAL_CALORIE_TARGET)
        prefs[KEY_SHARE_PROFILE_WITH_AI] = value.shareProfileWithAi
    }

    private fun resolveEnergyProfileSettings(prefs: Preferences): EnergyProfileSettings =
        EnergyProfileSettings(
            enabled = prefs[KEY_ENERGY_PROFILE_ENABLED] ?: false,
            forecastActivityInfluenceEnabled =
                prefs[KEY_ENERGY_PROFILE_FORECAST_ACTIVITY_INFLUENCE_ENABLED] ?: false,
            birthDateEpochDay = prefs[KEY_BIRTH_DATE_EPOCH_DAY],
            physiologicalSex = resolveProfileEnum(
                prefs[KEY_PHYSIOLOGICAL_SEX],
                PhysiologicalSex.UNSPECIFIED
            ),
            heightCm = prefs[KEY_HEIGHT_CM],
            weightKg = prefs[KEY_WEIGHT_KG],
            foodProfileMode = resolveProfileEnum(prefs[KEY_FOOD_PROFILE_MODE], FoodProfileMode.AUTO),
            manualFoodProfile = resolveProfileEnum(
                prefs[KEY_MANUAL_FOOD_PROFILE],
                MealAbsorptionProfile.MIXED
            ),
            activityProfileMode = resolveProfileEnum(
                prefs[KEY_ACTIVITY_PROFILE_MODE],
                ActivityProfileMode.AUTO
            ),
            manualActivityProfile = resolveProfileEnum(
                prefs[KEY_MANUAL_ACTIVITY_PROFILE],
                ActivityProfile.MODERATE
            ),
            calorieGoalMode = resolveProfileEnum(prefs[KEY_CALORIE_GOAL_MODE], CalorieGoalMode.OFF),
            manualCalorieTargetKcal = prefs[KEY_MANUAL_CALORIE_TARGET],
            shareProfileWithAi = prefs[KEY_SHARE_PROFILE_WITH_AI] ?: true
        )

    private inline fun <reified T : Enum<T>> resolveProfileEnum(
        raw: String?,
        default: T
    ): T = runCatching {
        enumValueOf<T>(raw?.trim().orEmpty().uppercase())
    }.getOrDefault(default)

    private fun resolveClinicalAiConfigState(preferences: Preferences): ClinicalAiConfigState =
        ClinicalAiConfigState.fromStored(
            providerId = preferences[KEY_CLINICAL_AI_PROVIDER],
            modelId = preferences[KEY_CLINICAL_AI_MODEL],
            endpoint = preferences[KEY_CLINICAL_AI_ENDPOINT],
            compatibleProtocol = preferences[KEY_CLINICAL_AI_PROTOCOL]
        )

    private fun writeClinicalAiConfig(
        preferences: MutablePreferences,
        config: ClinicalAiProviderConfig
    ) {
        preferences[KEY_CLINICAL_AI_PROVIDER] = config.providerId.name
        preferences[KEY_CLINICAL_AI_MODEL] = config.modelId
        config.endpoint?.let { preferences[KEY_CLINICAL_AI_ENDPOINT] = it }
            ?: preferences.remove(KEY_CLINICAL_AI_ENDPOINT)
        config.compatibleProtocol?.let { preferences[KEY_CLINICAL_AI_PROTOCOL] = it.name }
            ?: preferences.remove(KEY_CLINICAL_AI_PROTOCOL)
    }

    private fun writeClinicalAiConfigState(
        preferences: MutablePreferences,
        currentState: ClinicalAiConfigState,
        nextState: ClinicalAiConfigState
    ) {
        when (nextState) {
            is ClinicalAiConfigState.Valid -> writeClinicalAiConfig(preferences, nextState.config)
            is ClinicalAiConfigState.Invalid -> require(nextState == currentState) {
                "New or changed invalid clinical AI configuration cannot be persisted: " +
                    nextState.reason
            }

            is ClinicalAiConfigState.UnconfiguredDefault -> {
                require(nextState.config == ClinicalAiProviderConfig.defaultOpenAi()) {
                    "Unconfigured clinical AI state must use the default configuration"
                }
                preferences.remove(KEY_CLINICAL_AI_PROVIDER)
                preferences.remove(KEY_CLINICAL_AI_MODEL)
                preferences.remove(KEY_CLINICAL_AI_ENDPOINT)
                preferences.remove(KEY_CLINICAL_AI_PROTOCOL)
            }
        }
    }

    suspend fun setTherapyActionsArmed(armed: Boolean): Boolean {
        return TherapyActionTransportGate.updateState(requestedArmed = armed) {
            val installId = currentInstallId ?: return@updateState false
            dataStore.edit { prefs ->
                prefs[KEY_THERAPY_ACTION_BOOTSTRAP_EVALUATED_INSTALL_ID] = installId
                if (armed) {
                    prefs[KEY_THERAPY_ACTIONS_ARMED_INSTALL_ID] = installId
                } else {
                    prefs.remove(KEY_THERAPY_ACTIONS_ARMED_INSTALL_ID)
                }
            }
            true
        }
    }

    suspend fun isTherapyActionBootstrapEvaluated(): Boolean {
        val installId = currentInstallId ?: return false
        return dataStore.data.first()[KEY_THERAPY_ACTION_BOOTSTRAP_EVALUATED_INSTALL_ID] == installId
    }

    suspend fun tryAutoArmTherapyActionsMigration(): TherapyActionBootstrapMigrationResult {
        val installId = currentInstallId ?: return TherapyActionBootstrapMigrationResult.FAILED
        var result = TherapyActionBootstrapMigrationResult.FAILED
        TherapyActionTransportGate.resolveState {
            var actualArmed = false
            dataStore.edit { prefs ->
                actualArmed = prefs[KEY_THERAPY_ACTIONS_ARMED_INSTALL_ID] == installId
                if (prefs[KEY_THERAPY_ACTION_BOOTSTRAP_EVALUATED_INSTALL_ID] == installId) {
                    result = TherapyActionBootstrapMigrationResult.ALREADY_DECIDED
                    return@edit
                }
                prefs[KEY_THERAPY_ACTION_BOOTSTRAP_EVALUATED_INSTALL_ID] = installId
                prefs[KEY_THERAPY_ACTIONS_ARMED_INSTALL_ID] = installId
                actualArmed = true
                result = TherapyActionBootstrapMigrationResult.ARMED
            }
            actualArmed
        }
        return result
    }

    suspend fun saveBaseTargetSchedule(
        candidate: BaseTargetSchedule
    ): BaseTargetSchedule {
        var savedSchedule: BaseTargetSchedule? = null
        dataStore.edit { prefs ->
            val (minTarget, maxTarget) = requireCurrentSafetyTargetBounds(prefs)
            val currentSchedule = resolveBaseTargetSchedule(
                prefs = prefs,
                minTarget = minTarget,
                maxTarget = maxTarget
            ).schedule
            require(candidate.revision == currentSchedule.revision) {
                "Base target schedule revision changed; reload before saving"
            }
            val nextSchedule = BaseTargetSchedule(
                schemaVersion = candidate.schemaVersion,
                revision = incrementRevision(currentSchedule.revision),
                defaultTargetMmol = candidate.defaultTargetMmol,
                autoEnabled = candidate.autoEnabled,
                intervals = candidate.intervals.toList()
            )
            requireValidSchedule(nextSchedule, minTarget, maxTarget)
            val encoded = baseTargetScheduleCodec.encode(nextSchedule)

            prefs[KEY_BASE_TARGET_SCHEDULE_JSON] = encoded
            prefs[KEY_BASE_TARGET_MMOL] = nextSchedule.defaultTargetMmol
            savedSchedule = nextSchedule
        }
        return checkNotNull(savedSchedule)
    }

    suspend fun promoteTargetManagerToActive(scheduleRevision: Long): Boolean {
        var promoted = false
        dataStore.edit { prefs ->
            if (prefs[KEY_TARGET_MANAGER_MANUAL_OVERRIDE] == true) return@edit
            val rawMode = prefs[KEY_TARGET_MANAGER_MODE]
            val currentMode = when {
                rawMode == null -> TargetManagerMode.SHADOW
                rawMode == TargetManagerMode.SHADOW.name -> TargetManagerMode.SHADOW
                rawMode == TargetManagerMode.ACTIVE.name -> TargetManagerMode.ACTIVE
                rawMode == TargetManagerMode.OFF.name -> TargetManagerMode.OFF
                else -> return@edit
            }
            if (currentMode != TargetManagerMode.SHADOW) return@edit

            val (minTarget, maxTarget) = requireCurrentSafetyTargetBounds(prefs)
            val resolved = resolveBaseTargetSchedule(prefs, minTarget, maxTarget)
            val schedule = resolved.schedule
            if (
                resolved.recoveryReason != null ||
                !schedule.autoEnabled ||
                schedule.revision != scheduleRevision
            ) {
                return@edit
            }

            prefs[KEY_TARGET_MANAGER_MODE] = TargetManagerMode.ACTIVE.name
            prefs[KEY_TARGET_MANAGER_POLICY_REVISION] = nextTargetManagerPolicyRevision(prefs)
            promoted = true
        }
        return promoted
    }

    suspend fun setTargetManagerModeManually(mode: TargetManagerMode) {
        dataStore.edit { prefs ->
            prefs[KEY_TARGET_MANAGER_POLICY_REVISION] = nextTargetManagerPolicyRevision(prefs)
            prefs[KEY_TARGET_MANAGER_MODE] = mode.name
            prefs[KEY_TARGET_MANAGER_MANUAL_OVERRIDE] = true
        }
    }

    suspend fun setTargetManagerCopilotPriorityEnabled(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[KEY_TARGET_MANAGER_POLICY_REVISION] = nextTargetManagerPolicyRevision(prefs)
            prefs[KEY_TARGET_MANAGER_COPILOT_PRIORITY] = enabled
        }
    }

    private fun nextTargetManagerPolicyRevision(prefs: Preferences): Long {
        val current = prefs[KEY_TARGET_MANAGER_POLICY_REVISION] ?: 0L
        check(current >= 0L) { "Invalid target policy revision" }
        return Math.addExact(current, 1L)
    }

    suspend fun enableAutomaticTargetManagerMode() {
        dataStore.edit { prefs ->
            prefs[KEY_TARGET_MANAGER_POLICY_REVISION] = nextTargetManagerPolicyRevision(prefs)
            prefs[KEY_TARGET_MANAGER_MODE] = TargetManagerMode.SHADOW.name
            prefs[KEY_TARGET_MANAGER_MANUAL_OVERRIDE] = false
        }
    }

    suspend fun updateBaseTargetDefault(targetMmol: Double): BaseTargetSchedule {
        require(targetMmol.isFinite()) {
            "Base target must be finite"
        }
        var savedSchedule: BaseTargetSchedule? = null
        dataStore.edit { prefs ->
            val (minTarget, maxTarget) = requireCurrentSafetyTargetBounds(prefs)
            val currentSchedule = resolveBaseTargetSchedule(
                prefs = prefs,
                minTarget = minTarget,
                maxTarget = maxTarget
            ).schedule
            val nextSchedule = currentSchedule.copy(
                revision = incrementRevision(currentSchedule.revision),
                defaultTargetMmol = clampTargetToBounds(targetMmol, minTarget, maxTarget)
            )
            requireValidSchedule(nextSchedule, minTarget, maxTarget)
            val encoded = baseTargetScheduleCodec.encode(nextSchedule)

            prefs[KEY_BASE_TARGET_SCHEDULE_JSON] = encoded
            prefs[KEY_BASE_TARGET_MMOL] = nextSchedule.defaultTargetMmol
            savedSchedule = nextSchedule
        }
        return checkNotNull(savedSchedule)
    }

    suspend fun updateSafetyLimits(
        maxActionsIn6Hours: Int,
        staleDataMaxMinutes: Int,
        safetyMinTargetMmol: Double? = null,
        safetyMaxTargetMmol: Double? = null,
        carbAbsorptionMaxAgeMinutes: Int? = null,
        carbComputationMaxGrams: Double? = null
    ) {
        applySafetyLimitsMutation(
            SafetyLimitsMutation(
                maxActionsIn6Hours = maxActionsIn6Hours,
                staleDataMaxMinutes = staleDataMaxMinutes,
                minTarget = safetyMinTargetMmol,
                maxTarget = safetyMaxTargetMmol,
                carbAbsorptionMaxAgeMinutes = carbAbsorptionMaxAgeMinutes,
                carbComputationMaxGrams = carbComputationMaxGrams
            )
        )
    }

    suspend fun updateBaseTargetScheduleForBounds(
        minTarget: Double,
        maxTarget: Double
    ): BaseTargetSchedule {
        return checkNotNull(
            applySafetyLimitsMutation(
                SafetyLimitsMutation(
                    minTarget = minTarget,
                    maxTarget = maxTarget
                )
            )
        )
    }

    private suspend fun applySafetyLimitsMutation(
        mutation: SafetyLimitsMutation
    ): BaseTargetSchedule? {
        var resolvedSchedule: BaseTargetSchedule? = null
        dataStore.edit { prefs ->
            val (currentMin, currentMax) = requireCurrentSafetyTargetBounds(prefs)
            if (!mutation.hasBoundUpdate) {
                writeRequestedSafetyFields(prefs, mutation)
                return@edit
            }

            val (normalizedMin, normalizedMax) = normalizeSuppliedSafetyTargetBounds(
                minTarget = mutation.minTarget ?: currentMin,
                maxTarget = mutation.maxTarget ?: currentMax
            )
            if (normalizedMin == currentMin && normalizedMax == currentMax) {
                resolvedSchedule = resolveBaseTargetSchedule(
                    prefs = prefs,
                    minTarget = currentMin,
                    maxTarget = currentMax
                ).schedule
                writeRequestedSafetyFields(prefs, mutation)
                return@edit
            }

            val currentScheduleResult = resolveBaseTargetSchedule(
                prefs = prefs,
                minTarget = currentMin,
                maxTarget = currentMax
            )
            val rawSchedule = prefs[KEY_BASE_TARGET_SCHEDULE_JSON]
            require(rawSchedule.isNullOrBlank() || currentScheduleResult.recoveryReason == null) {
                "Cannot change safety target bounds while the base target schedule requires recovery"
            }
            val currentSchedule = currentScheduleResult.schedule
            val nextSchedule = BaseTargetSchedule(
                schemaVersion = currentSchedule.schemaVersion,
                revision = incrementRevision(currentSchedule.revision),
                defaultTargetMmol = clampTargetToBounds(
                    currentSchedule.defaultTargetMmol,
                    normalizedMin,
                    normalizedMax
                ),
                autoEnabled = currentSchedule.autoEnabled,
                intervals = currentSchedule.intervals.map { interval ->
                    BaseTargetInterval(
                        id = interval.id,
                        startMinuteOfDay = interval.startMinuteOfDay,
                        endMinuteOfDay = interval.endMinuteOfDay,
                        targetMmol = clampTargetToBounds(
                            interval.targetMmol,
                            normalizedMin,
                            normalizedMax
                        )
                    )
                }
            )
            requireValidSchedule(nextSchedule, normalizedMin, normalizedMax)
            val encoded = baseTargetScheduleCodec.encode(nextSchedule)
            val postHypoThreshold = clampPostHypoToBounds(
                value = prefs[KEY_POST_HYPO_THRESHOLD_MMOL],
                defaultValue = DEFAULT_POST_HYPO_THRESHOLD_MMOL,
                minTarget = normalizedMin,
                maxTarget = normalizedMax
            )
            val postHypoTarget = clampPostHypoToBounds(
                value = prefs[KEY_POST_HYPO_TARGET_MMOL],
                defaultValue = DEFAULT_POST_HYPO_TARGET_MMOL,
                minTarget = normalizedMin,
                maxTarget = normalizedMax
            )

            writeRequestedSafetyFields(prefs, mutation)
            prefs[KEY_SAFETY_MIN_TARGET_MMOL] = normalizedMin
            prefs[KEY_SAFETY_MAX_TARGET_MMOL] = normalizedMax
            prefs[KEY_POST_HYPO_THRESHOLD_MMOL] = postHypoThreshold
            prefs[KEY_POST_HYPO_TARGET_MMOL] = postHypoTarget
            prefs[KEY_BASE_TARGET_SCHEDULE_JSON] = encoded
            prefs[KEY_BASE_TARGET_MMOL] = nextSchedule.defaultTargetMmol
            resolvedSchedule = nextSchedule
        }
        return resolvedSchedule
    }

    private fun writeRequestedSafetyFields(
        prefs: MutablePreferences,
        mutation: SafetyLimitsMutation
    ) {
        mutation.maxActionsIn6Hours?.let { value ->
            prefs[KEY_MAX_ACTIONS_6H] = value.coerceIn(1, 10)
        }
        mutation.staleDataMaxMinutes?.let { value ->
            prefs[KEY_STALE_DATA_MAX_MINUTES] = value.coerceIn(5, 60)
        }
        mutation.carbAbsorptionMaxAgeMinutes?.let { value ->
            prefs[KEY_CARB_ABSORPTION_MAX_AGE_MINUTES] = value.coerceIn(60, 180)
        }
        mutation.carbComputationMaxGrams?.let { value ->
            prefs[KEY_CARB_COMPUTATION_MAX_GRAMS] = value.coerceIn(20.0, 60.0)
        }
    }

    suspend fun ensureAdaptiveControllerDefaultEnabled(): Boolean {
        var enabledNow = false
        dataStore.edit { prefs ->
            if (prefs[KEY_ADAPTIVE_DEFAULT_MIGRATION_DONE] == true) return@edit
            prefs[KEY_ADAPTIVE_CONTROLLER_ENABLED] = true
            prefs[KEY_ADAPTIVE_DEFAULT_MIGRATION_DONE] = true
            enabledNow = true
        }
        return enabledNow
    }

    internal suspend fun ensureBoundedUamExportV2(): UamExportMigrationResult? {
        var migrationResult: UamExportMigrationResult? = null
        dataStore.edit { prefs ->
            if (prefs[KEY_UAM_EXPORT_V2_BOUNDED_MIGRATION_DONE] == true) return@edit

            val enabled = prefs[KEY_ENABLE_UAM_EXPORT] == true
            val oldMode = resolveUamExportMode(prefs[KEY_UAM_EXPORT_MODE])
            val oldDryRun = prefs[KEY_DRY_RUN_EXPORT] ?: LEGACY_DEFAULT_DRY_RUN_EXPORT
            val oldMaxBackdateMinutes = prefs[KEY_UAM_EXPORT_MAX_BACKDATE_MIN]
                ?: DEFAULT_UAM_EXPORT_MAX_BACKDATE_MIN
            val decision = decideBoundedUamExportV2Migration(
                enabled = enabled,
                mode = oldMode,
                dryRun = oldDryRun,
                maxBackdateMinutes = oldMaxBackdateMinutes
            )

            if (decision.changed) {
                prefs[KEY_UAM_EXPORT_MODE] = decision.mode.name
                prefs[KEY_DRY_RUN_EXPORT] = decision.dryRun
                prefs[KEY_UAM_EXPORT_MAX_BACKDATE_MIN] = decision.maxBackdateMinutes
                migrationResult = UamExportMigrationResult(
                    oldMode = oldMode,
                    newMode = decision.mode,
                    oldDryRun = oldDryRun,
                    newDryRun = decision.dryRun,
                    oldMaxBackdateMinutes = oldMaxBackdateMinutes,
                    newMaxBackdateMinutes = decision.maxBackdateMinutes
                )
            }
            prefs[KEY_UAM_EXPORT_V2_BOUNDED_MIGRATION_DONE] = true
        }
        return migrationResult
    }

    internal suspend fun ensureUamThreeModeConsentV1(): UamThreeModeConsentMigrationResult? {
        if (threeModeConsentMigrationChecked) return null
        var migrationResult: UamThreeModeConsentMigrationResult? = null
        dataStore.edit { prefs ->
            if (prefs[KEY_UAM_EXPORT_THREE_MODE_CONSENT_V1_MIGRATION_DONE] == true) {
                return@edit
            }

            val enabled = prefs[KEY_ENABLE_UAM_EXPORT] == true
            val oldMode = resolveUamExportMode(prefs[KEY_UAM_EXPORT_MODE])
            val oldDryRun = prefs[KEY_DRY_RUN_EXPORT] ?: LEGACY_DEFAULT_DRY_RUN_EXPORT
            if (enabled && oldMode == UamExportMode.INCREMENTAL && !oldDryRun) {
                prefs[KEY_ENABLE_UAM_EXPORT] = true
                prefs[KEY_UAM_EXPORT_MODE] = UamExportMode.INCREMENTAL.name
                prefs[KEY_DRY_RUN_EXPORT] = true
                migrationResult = UamThreeModeConsentMigrationResult(
                    oldMode = oldMode,
                    newMode = UamExportMode.INCREMENTAL,
                    oldDryRun = false,
                    newDryRun = true
                )
            }
            prefs[KEY_UAM_EXPORT_THREE_MODE_CONSENT_V1_MIGRATION_DONE] = true
        }
        threeModeConsentMigrationChecked = true
        return migrationResult
    }

    suspend fun ensureAnalyticsRetentionDefault30Days(): Boolean {
        var leasedMigrationRequired = false
        dataStore.edit { prefs ->
            if (prefs[KEY_ANALYTICS_RETENTION_DEFAULT_MIGRATION_DONE] == true) return@edit
            val stored = prefs[KEY_ANALYTICS_LOOKBACK_DAYS]
            if (stored == null || stored == LEGACY_DEFAULT_ANALYTICS_LOOKBACK_DAYS) {
                leasedMigrationRequired = true
            } else {
                prefs[KEY_ANALYTICS_RETENTION_DEFAULT_MIGRATION_DONE] = true
            }
        }
        return leasedMigrationRequired
    }

    internal suspend fun markAnalyticsRetentionDefaultMigrationComplete() {
        dataStore.edit { prefs ->
            prefs[KEY_ANALYTICS_RETENTION_DEFAULT_MIGRATION_DONE] = true
        }
    }

    suspend fun ensureUiStyleDefaultMidnightGlass(): Boolean {
        var migrated = false
        dataStore.edit { prefs ->
            if (prefs[KEY_UI_STYLE_DEFAULT_MIGRATION_DONE] == true) return@edit
            val stored = resolveUiStyle(prefs[KEY_UI_STYLE])
            if (prefs[KEY_UI_STYLE] == null || stored == UiStyle.CLASSIC) {
                prefs[KEY_UI_STYLE] = UiStyle.MIDNIGHT_GLASS.name
                migrated = true
            }
            prefs[KEY_UI_STYLE_DEFAULT_MIGRATION_DONE] = true
        }
        return migrated
    }

    suspend fun ensureLegacyUiStylesPromotedToMidnightGlass(): String? {
        var previousStyle: String? = null
        dataStore.edit { prefs ->
            if (prefs[KEY_UI_STYLE_REDESIGN_MIGRATION_DONE] == true) return@edit
            val storedRaw = prefs[KEY_UI_STYLE]
            val stored = resolveUiStyle(storedRaw)
            if (storedRaw == null || stored == UiStyle.CLASSIC || stored == UiStyle.DYNAMIC_GRADIENT) {
                previousStyle = storedRaw ?: "UNSET"
                prefs[KEY_UI_STYLE] = UiStyle.MIDNIGHT_GLASS.name
            }
            prefs[KEY_UI_STYLE_REDESIGN_MIGRATION_DONE] = true
        }
        return previousStyle
    }

    private fun resolveAdaptiveControllerEnabled(prefs: Preferences): Boolean {
        return prefs[KEY_ADAPTIVE_CONTROLLER_ENABLED] ?: true
    }

    private fun resolveSafetyTargetBounds(prefs: Preferences): Pair<Double, Double> {
        val storedMin = prefs[KEY_SAFETY_MIN_TARGET_MMOL] ?: DEFAULT_SAFETY_MIN_TARGET_MMOL
        val storedMax = prefs[KEY_SAFETY_MAX_TARGET_MMOL] ?: DEFAULT_SAFETY_MAX_TARGET_MMOL
        return normalizeSafetyTargetBoundsOrNull(storedMin, storedMax)
            ?: (DEFAULT_SAFETY_MIN_TARGET_MMOL to DEFAULT_SAFETY_MAX_TARGET_MMOL)
    }

    private fun requireCurrentSafetyTargetBounds(
        prefs: Preferences
    ): Pair<Double, Double> {
        val storedMin = prefs[KEY_SAFETY_MIN_TARGET_MMOL] ?: DEFAULT_SAFETY_MIN_TARGET_MMOL
        val storedMax = prefs[KEY_SAFETY_MAX_TARGET_MMOL] ?: DEFAULT_SAFETY_MAX_TARGET_MMOL
        require(storedMin.isFinite() && storedMax.isFinite() && storedMin <= storedMax) {
            "Current safety target bounds must be finite and ordered"
        }
        return normalizeSafetyTargetBounds(storedMin, storedMax)
    }

    private fun normalizeSuppliedSafetyTargetBounds(
        minTarget: Double,
        maxTarget: Double
    ): Pair<Double, Double> {
        require(minTarget.isFinite() && maxTarget.isFinite() && minTarget <= maxTarget) {
            "Safety target bounds must be finite and ordered"
        }
        return normalizeSafetyTargetBounds(minTarget, maxTarget)
    }

    private fun normalizeSafetyTargetBounds(
        minTarget: Double,
        maxTarget: Double
    ): Pair<Double, Double> {
        return requireNotNull(normalizeSafetyTargetBoundsOrNull(minTarget, maxTarget)) {
            "Safety target bounds must overlap the supported range, keep a 0.2 mmol/L gap, " +
                "and contain a 0.1 mmol/L value"
        }
    }

    private fun normalizeSafetyTargetBoundsOrNull(
        minTarget: Double,
        maxTarget: Double
    ): Pair<Double, Double>? {
        if (!minTarget.isFinite() || !maxTarget.isFinite() || minTarget > maxTarget) return null

        val normalizedMin = minTarget.coerceAtLeast(DEFAULT_SAFETY_MIN_TARGET_MMOL)
        val normalizedMax = maxTarget.coerceAtMost(DEFAULT_SAFETY_MAX_TARGET_MMOL)
        if (normalizedMin > normalizedMax) return null

        val normalizedGap = BigDecimal.valueOf(normalizedMax)
            .subtract(BigDecimal.valueOf(normalizedMin))
        if (normalizedGap < BigDecimal.valueOf(0.2)) return null

        val lowerTenth = BigDecimal.valueOf(normalizedMin).setScale(1, RoundingMode.CEILING)
        val upperTenth = BigDecimal.valueOf(normalizedMax).setScale(1, RoundingMode.FLOOR)
        if (lowerTenth > upperTenth) return null

        return normalizedMin to normalizedMax
    }

    private fun resolveBaseTargetSchedule(
        prefs: Preferences,
        minTarget: Double,
        maxTarget: Double
    ): ResolvedBaseTargetSchedule {
        val legacyTarget = clampTargetToBounds(
            target = prefs[KEY_BASE_TARGET_MMOL] ?: DEFAULT_BASE_TARGET_MMOL,
            minTarget = minTarget,
            maxTarget = maxTarget
        )
        return when (
            val decoded = baseTargetScheduleCodec.decode(
                raw = prefs[KEY_BASE_TARGET_SCHEDULE_JSON],
                legacyTarget = legacyTarget
            )
        ) {
            is BaseTargetScheduleDecodeResult.Fallback -> ResolvedBaseTargetSchedule(
                schedule = decoded.schedule,
                recoveryReason = decoded.reason
            )
            is BaseTargetScheduleDecodeResult.Valid -> {
                val (lowestTarget, highestTarget) = targetTenthBounds(minTarget, maxTarget)
                if (BaseTargetSchedulePolicy.validate(
                        schedule = decoded.schedule,
                        minTarget = lowestTarget,
                        maxTarget = highestTarget
                    ).isEmpty()
                ) {
                    ResolvedBaseTargetSchedule(
                        schedule = decoded.schedule,
                        recoveryReason = null
                    )
                } else {
                    ResolvedBaseTargetSchedule(
                        schedule = BaseTargetSchedule.legacy(legacyTarget),
                        recoveryReason = INVALID_SCHEDULE_PAYLOAD
                    )
                }
            }
        }
    }

    private fun clampTargetToBounds(
        target: Double,
        minTarget: Double,
        maxTarget: Double
    ): Double {
        val (lowerTenth, upperTenth) = targetTenthBounds(minTarget, maxTarget)
        val finiteTarget = target.takeIf(Double::isFinite) ?: DEFAULT_BASE_TARGET_MMOL
        return (round(finiteTarget * 10.0) / 10.0).coerceIn(lowerTenth, upperTenth)
    }

    private fun targetTenthBounds(
        minTarget: Double,
        maxTarget: Double
    ): Pair<Double, Double> {
        require(minTarget.isFinite() && maxTarget.isFinite() && minTarget <= maxTarget) {
            "Safety target bounds must be finite and ordered"
        }
        val lowerTenth = BigDecimal.valueOf(minTarget)
            .setScale(1, RoundingMode.CEILING)
            .toDouble()
        val upperTenth = BigDecimal.valueOf(maxTarget)
            .setScale(1, RoundingMode.FLOOR)
            .toDouble()
        require(lowerTenth <= upperTenth) {
            "Safety target bounds do not contain a 0.1 mmol/L value"
        }
        return lowerTenth to upperTenth
    }

    private fun clampPostHypoToBounds(
        value: Double?,
        defaultValue: Double,
        minTarget: Double,
        maxTarget: Double
    ): Double {
        return (value?.takeIf(Double::isFinite) ?: defaultValue).coerceIn(minTarget, maxTarget)
    }

    private fun requireValidSchedule(
        schedule: BaseTargetSchedule,
        minTarget: Double,
        maxTarget: Double
    ) {
        val (lowestTarget, highestTarget) = targetTenthBounds(minTarget, maxTarget)
        val errors = BaseTargetSchedulePolicy.validate(schedule, lowestTarget, highestTarget)
        require(errors.isEmpty()) {
            "Invalid base target schedule: ${errors.joinToString { it.code }}"
        }
    }

    private fun incrementRevision(currentRevision: Long): Long {
        require(currentRevision < Long.MAX_VALUE) {
            "Base target schedule revision is exhausted"
        }
        return currentRevision + 1L
    }

    private data class ResolvedBaseTargetSchedule(
        val schedule: BaseTargetSchedule,
        val recoveryReason: String?
    )

    private data class SafetyLimitsMutation(
        val maxActionsIn6Hours: Int? = null,
        val staleDataMaxMinutes: Int? = null,
        val minTarget: Double? = null,
        val maxTarget: Double? = null,
        val carbAbsorptionMaxAgeMinutes: Int? = null,
        val carbComputationMaxGrams: Double? = null
    ) {
        val hasBoundUpdate: Boolean
            get() = minTarget != null || maxTarget != null
    }

    private fun normalizeInsulinProfileId(raw: String?): String {
        return InsulinActionProfileId.fromRaw(raw).name
    }

    private fun resolveUamExportMode(raw: String?): UamExportMode {
        return runCatching {
            UamExportMode.valueOf(raw?.trim().orEmpty().uppercase())
        }.getOrDefault(UamExportMode.CONFIRMED_ONLY)
    }

    private fun resolveSensorLagCorrectionMode(raw: String?): SensorLagCorrectionMode {
        return SensorLagCorrectionMode.fromRaw(raw)
    }

    private fun resolveTargetManagerMode(raw: String?): TargetManagerMode {
        return raw?.let { value ->
            runCatching { TargetManagerMode.valueOf(value) }.getOrNull()
        } ?: TargetManagerMode.SHADOW
    }

    private fun resolveUiStyle(raw: String?): UiStyle {
        return UiStyle.fromRaw(raw)
    }

    companion object {
        private val KEY_NS_URL = stringPreferencesKey("nightscout_url")
        private val KEY_NS_SECRET = stringPreferencesKey("nightscout_secret")
        private val KEY_CLOUD_URL = stringPreferencesKey("cloud_base_url")
        private val KEY_LEGACY_OPENAI_CREDENTIAL = stringPreferencesKey("openai_api_key")
        private val KEY_ENERGY_PROFILE_ENABLED = booleanPreferencesKey("energy_profile_enabled")
        private val KEY_ENERGY_PROFILE_FORECAST_ACTIVITY_INFLUENCE_ENABLED =
            booleanPreferencesKey("energy_profile_forecast_activity_influence_enabled")
        private val KEY_BIRTH_DATE_EPOCH_DAY = longPreferencesKey("birth_date_epoch_day")
        private val KEY_PHYSIOLOGICAL_SEX = stringPreferencesKey("physiological_sex")
        private val KEY_HEIGHT_CM = doublePreferencesKey("height_cm")
        private val KEY_WEIGHT_KG = doublePreferencesKey("weight_kg")
        private val KEY_FOOD_PROFILE_MODE = stringPreferencesKey("food_profile_mode")
        private val KEY_MEAL_SHOW_CALORIES = booleanPreferencesKey("meal_show_calories")
        private val KEY_MANUAL_FOOD_PROFILE = stringPreferencesKey("manual_food_profile")
        private val KEY_ACTIVITY_PROFILE_MODE = stringPreferencesKey("activity_profile_mode")
        private val KEY_MANUAL_ACTIVITY_PROFILE = stringPreferencesKey("manual_activity_profile")
        private val KEY_CALORIE_GOAL_MODE = stringPreferencesKey("calorie_goal_mode")
        private val KEY_MANUAL_CALORIE_TARGET = intPreferencesKey("manual_calorie_target")
        private val KEY_SHARE_PROFILE_WITH_AI = booleanPreferencesKey("share_profile_with_ai")
        private val KEY_CLINICAL_AI_PROVIDER = stringPreferencesKey("clinical_ai_provider_id")
        private val KEY_CLINICAL_AI_MODEL = stringPreferencesKey("clinical_ai_model_id")
        private val KEY_CLINICAL_AI_ENDPOINT = stringPreferencesKey("clinical_ai_endpoint")
        private val KEY_CLINICAL_AI_PROTOCOL =
            stringPreferencesKey("clinical_ai_compatible_protocol")
        private val KEY_AUTOMATIC_EVENT_AI_ANALYSIS_ENABLED =
            booleanPreferencesKey("automatic_event_ai_analysis_enabled")
        private val KEY_UI_STYLE = stringPreferencesKey("ui_style")
        private val KEY_KILL_SWITCH = booleanPreferencesKey("kill_switch")
        private val KEY_THERAPY_ACTIONS_ARMED_INSTALL_ID =
            stringPreferencesKey("therapy_actions_armed_install_id_v1")
        private val KEY_THERAPY_ACTION_BOOTSTRAP_EVALUATED_INSTALL_ID =
            stringPreferencesKey("therapy_action_bootstrap_evaluated_install_id_v1")
        private val KEY_POWER_SAVE_UNTIL_MS = longPreferencesKey("power_save_until_ms")
        private val KEY_ROOT_EXPERIMENTAL = booleanPreferencesKey("root_experimental")
        private val KEY_LOCAL_BROADCAST_INGEST = booleanPreferencesKey("local_broadcast_ingest_enabled")
        private const val TEST_INSTALL_ID = "test-install-id-v1"

        internal fun therapyActionsArmedForInstallStatic(
            storedInstallId: String?,
            currentInstallId: String?
        ): Boolean = currentInstallId != null && storedInstallId == currentInstallId
        private val KEY_STRICT_BROADCAST_VALIDATION = booleanPreferencesKey("strict_broadcast_sender_validation")
        private val KEY_LOCAL_NIGHTSCOUT_ENABLED = booleanPreferencesKey("local_nightscout_enabled")
        private val KEY_LOCAL_NIGHTSCOUT_PORT = intPreferencesKey("local_nightscout_port")
        private val KEY_LOCAL_NIGHTSCOUT_LEGACY_MIGRATION_ACKNOWLEDGED =
            booleanPreferencesKey("local_nightscout_legacy_migration_acknowledged_v1")
        private val KEY_LOCAL_COMMAND_FALLBACK_ENABLED = booleanPreferencesKey("local_command_fallback_enabled")
        private val KEY_LOCAL_COMMAND_PACKAGE = stringPreferencesKey("local_command_package")
        private val KEY_LOCAL_COMMAND_ACTION = stringPreferencesKey("local_command_action")
        private val KEY_INSULIN_PROFILE = stringPreferencesKey("insulin_profile_id")
        private val KEY_ENABLE_UAM_INFERENCE = booleanPreferencesKey("enable_uam_inference")
        private val KEY_ENABLE_UAM_BOOST = booleanPreferencesKey("enable_uam_boost")
        private val KEY_ENABLE_UAM_EXPORT = booleanPreferencesKey("enable_uam_export_to_aaps")
        private val KEY_UAM_EXPORT_MODE = stringPreferencesKey("uam_export_mode")
        private val KEY_DRY_RUN_EXPORT = booleanPreferencesKey("uam_dry_run_export")
        private val KEY_UAM_AUTO_EXPORT_CAP_ENABLED =
            booleanPreferencesKey("uam_auto_export_cap_enabled")
        private val KEY_UAM_AUTO_EXPORT_CAP_GRAMS =
            intPreferencesKey("uam_auto_export_cap_grams")
        private val KEY_UAM_EXPORT_V2_BOUNDED_MIGRATION_DONE =
            booleanPreferencesKey("uam_export_v2_bounded_migration_done")
        private val KEY_UAM_EXPORT_THREE_MODE_CONSENT_V1_MIGRATION_DONE =
            booleanPreferencesKey("uam_export_three_mode_consent_v1_migration_done")
        private val KEY_ANALYTICS_RETENTION_DEFAULT_MIGRATION_DONE =
            booleanPreferencesKey("analytics_retention_default_migration_done")
        private val KEY_UI_STYLE_DEFAULT_MIGRATION_DONE =
            booleanPreferencesKey("ui_style_default_migration_done")
        private val KEY_UI_STYLE_REDESIGN_MIGRATION_DONE =
            booleanPreferencesKey("ui_style_redesign_migration_done")
        private val KEY_SHOW_EVENTS_ON_GRAPH = booleanPreferencesKey("show_events_on_graph")
        private val KEY_UAM_LEARNED_MULTIPLIER = doublePreferencesKey("uam_learned_multiplier")
        private val KEY_UAM_MIN_SNACK_G = intPreferencesKey("uam_min_snack_g")
        private val KEY_UAM_MAX_SNACK_G = intPreferencesKey("uam_max_snack_g")
        private val KEY_UAM_SNACK_STEP_G = intPreferencesKey("uam_snack_step_g")
        private val KEY_UAM_BACKDATE_MINUTES = intPreferencesKey("uam_backdate_minutes_default")
        private val KEY_UAM_DISABLE_MANUAL_COB_ACTIVE = booleanPreferencesKey("uam_disable_when_manual_cob_active")
        private val KEY_UAM_MANUAL_COB_THRESHOLD_G = doublePreferencesKey("uam_manual_cob_threshold_g")
        private val KEY_UAM_DISABLE_MANUAL_CARBS_NEARBY = booleanPreferencesKey("uam_disable_if_manual_carbs_nearby")
        private val KEY_UAM_MANUAL_MERGE_WINDOW_MINUTES = intPreferencesKey("uam_manual_merge_window_minutes")
        private val KEY_UAM_MAX_ABSORB_RATE_GPH_NORMAL = doublePreferencesKey("uam_max_absorb_rate_gph_normal")
        private val KEY_UAM_MAX_ABSORB_RATE_GPH_BOOST = doublePreferencesKey("uam_max_absorb_rate_gph_boost")
        private val KEY_UAM_MAX_TOTAL_G = doublePreferencesKey("uam_max_total_g")
        private val KEY_UAM_MAX_ACTIVE_EVENTS = intPreferencesKey("uam_max_active_events")
        private val KEY_UAM_CARB_MULTIPLIER_NORMAL = doublePreferencesKey("uam_carb_multiplier_normal")
        private val KEY_UAM_CARB_MULTIPLIER_BOOST = doublePreferencesKey("uam_carb_multiplier_boost")
        private val KEY_UAM_GABS_THRESHOLD_NORMAL = doublePreferencesKey("uam_gabs_threshold_normal")
        private val KEY_UAM_GABS_THRESHOLD_BOOST = doublePreferencesKey("uam_gabs_threshold_boost")
        private val KEY_UAM_M_OF_N_NORMAL_M = intPreferencesKey("uam_m_of_n_normal_m")
        private val KEY_UAM_M_OF_N_NORMAL_N = intPreferencesKey("uam_m_of_n_normal_n")
        private val KEY_UAM_M_OF_N_BOOST_M = intPreferencesKey("uam_m_of_n_boost_m")
        private val KEY_UAM_M_OF_N_BOOST_N = intPreferencesKey("uam_m_of_n_boost_n")
        private val KEY_UAM_CONFIRM_CONF_NORMAL = doublePreferencesKey("uam_confirm_conf_normal")
        private val KEY_UAM_CONFIRM_CONF_BOOST = doublePreferencesKey("uam_confirm_conf_boost")
        private val KEY_UAM_MIN_CONFIRM_AGE_MIN = intPreferencesKey("uam_min_confirm_age_min")
        private val KEY_UAM_EXPORT_MIN_INTERVAL_MIN = intPreferencesKey("uam_export_min_interval_min")
        private val KEY_UAM_EXPORT_MAX_BACKDATE_MIN = intPreferencesKey("uam_export_max_backdate_min")
        private val KEY_CARB_ABSORPTION_MAX_AGE_MINUTES = intPreferencesKey("carb_absorption_max_age_minutes")
        private val KEY_CARB_COMPUTATION_MAX_GRAMS = doublePreferencesKey("carb_computation_max_grams")
        private val KEY_SENSOR_LAG_CORRECTION_MODE = stringPreferencesKey("sensor_lag_correction_mode")
        private val KEY_TARGET_MANAGER_MODE = stringPreferencesKey("target_manager_mode")
        private val KEY_TARGET_MANAGER_COPILOT_PRIORITY =
            booleanPreferencesKey("target_manager_copilot_priority_enabled")
        private val KEY_TARGET_MANAGER_POLICY_REVISION = longPreferencesKey("target_manager_policy_revision")
        private val KEY_TARGET_MANAGER_MANUAL_OVERRIDE =
            booleanPreferencesKey("target_manager_mode_manual_override")
        private val KEY_ISF_RUNTIME_SOURCE = stringPreferencesKey("isf_runtime_source")
        private val KEY_CR_RUNTIME_SOURCE = stringPreferencesKey("cr_runtime_source")
        private val KEY_SENSITIVITY_SETTINGS_REVISION =
            longPreferencesKey("sensitivity_settings_revision")
        private val KEY_ISFCR_SHADOW_MODE = booleanPreferencesKey("isfcr_shadow_mode")
        private val KEY_ISFCR_CONFIDENCE_THRESHOLD = doublePreferencesKey("isfcr_confidence_threshold")
        private val KEY_ISFCR_USE_ACTIVITY = booleanPreferencesKey("isfcr_use_activity")
        private val KEY_ISFCR_USE_MANUAL_TAGS = booleanPreferencesKey("isfcr_use_manual_tags")
        private val KEY_ISFCR_MIN_ISF_EVIDENCE_PER_HOUR =
            intPreferencesKey("isfcr_min_isf_evidence_per_hour")
        private val KEY_ISFCR_MIN_CR_EVIDENCE_PER_HOUR =
            intPreferencesKey("isfcr_min_cr_evidence_per_hour")
        private val KEY_ISFCR_CR_MAX_GAP_MINUTES =
            intPreferencesKey("isfcr_cr_max_gap_minutes")
        private val KEY_ISFCR_CR_MAX_SENSOR_BLOCKED_RATE_PCT =
            doublePreferencesKey("isfcr_cr_max_sensor_blocked_rate_pct")
        private val KEY_ISFCR_CR_MAX_UAM_AMBIGUITY_RATE_PCT =
            doublePreferencesKey("isfcr_cr_max_uam_ambiguity_rate_pct")
        private val KEY_ISFCR_SNAPSHOT_RETENTION_DAYS = intPreferencesKey("isfcr_snapshot_retention_days")
        private val KEY_ISFCR_EVIDENCE_RETENTION_DAYS = intPreferencesKey("isfcr_evidence_retention_days")
        private val KEY_ISFCR_AUTO_ACTIVATION_ENABLED = booleanPreferencesKey("isfcr_auto_activation_enabled")
        private val KEY_ISFCR_AUTO_ACTIVATION_LOOKBACK_HOURS =
            intPreferencesKey("isfcr_auto_activation_lookback_hours")
        private val KEY_ISFCR_AUTO_ACTIVATION_MIN_SAMPLES =
            intPreferencesKey("isfcr_auto_activation_min_samples")
        private val KEY_ISFCR_AUTO_ACTIVATION_MIN_MEAN_CONFIDENCE =
            doublePreferencesKey("isfcr_auto_activation_min_mean_confidence")
        private val KEY_ISFCR_AUTO_ACTIVATION_MAX_MEAN_ABS_ISF_DELTA_PCT =
            doublePreferencesKey("isfcr_auto_activation_max_mean_abs_isf_delta_pct")
        private val KEY_ISFCR_AUTO_ACTIVATION_MAX_MEAN_ABS_CR_DELTA_PCT =
            doublePreferencesKey("isfcr_auto_activation_max_mean_abs_cr_delta_pct")
        private val KEY_ISFCR_AUTO_ACTIVATION_MIN_SENSOR_QUALITY_SCORE =
            doublePreferencesKey("isfcr_auto_activation_min_sensor_quality_score")
        private val KEY_ISFCR_AUTO_ACTIVATION_MIN_SENSOR_FACTOR =
            doublePreferencesKey("isfcr_auto_activation_min_sensor_factor")
        private val KEY_ISFCR_AUTO_ACTIVATION_MAX_WEAR_CONFIDENCE_PENALTY =
            doublePreferencesKey("isfcr_auto_activation_max_wear_confidence_penalty")
        private val KEY_ISFCR_AUTO_ACTIVATION_MAX_SENSOR_AGE_HIGH_RATE_PCT =
            doublePreferencesKey("isfcr_auto_activation_max_sensor_age_high_rate_pct")
        private val KEY_ISFCR_AUTO_ACTIVATION_MAX_SUSPECT_FALSE_LOW_RATE_PCT =
            doublePreferencesKey("isfcr_auto_activation_max_suspect_false_low_rate_pct")
        private val KEY_ISFCR_AUTO_ACTIVATION_MIN_DAY_TYPE_RATIO =
            doublePreferencesKey("isfcr_auto_activation_min_day_type_ratio")
        private val KEY_ISFCR_AUTO_ACTIVATION_MAX_DAY_TYPE_SPARSE_RATE_PCT =
            doublePreferencesKey("isfcr_auto_activation_max_day_type_sparse_rate_pct")
        private val KEY_ISFCR_AUTO_ACTIVATION_REQUIRE_DAILY_QUALITY_GATE =
            booleanPreferencesKey("isfcr_auto_activation_require_daily_quality_gate")
        private val KEY_ISFCR_AUTO_ACTIVATION_DAILY_RISK_BLOCK_LEVEL =
            intPreferencesKey("isfcr_auto_activation_daily_risk_block_level")
        private val KEY_ISFCR_AUTO_ACTIVATION_MIN_DAILY_MATCHED_SAMPLES =
            intPreferencesKey("isfcr_auto_activation_min_daily_matched_samples")
        private val KEY_ISFCR_AUTO_ACTIVATION_MAX_DAILY_MAE_30_MMOL =
            doublePreferencesKey("isfcr_auto_activation_max_daily_mae_30_mmol")
        private val KEY_ISFCR_AUTO_ACTIVATION_MAX_DAILY_MAE_60_MMOL =
            doublePreferencesKey("isfcr_auto_activation_max_daily_mae_60_mmol")
        private val KEY_ISFCR_AUTO_ACTIVATION_MAX_HYPO_RATE_PCT =
            doublePreferencesKey("isfcr_auto_activation_max_hypo_rate_pct")
        private val KEY_ISFCR_AUTO_ACTIVATION_MIN_DAILY_CI_COVERAGE_30_PCT =
            doublePreferencesKey("isfcr_auto_activation_min_daily_ci_coverage_30_pct")
        private val KEY_ISFCR_AUTO_ACTIVATION_MIN_DAILY_CI_COVERAGE_60_PCT =
            doublePreferencesKey("isfcr_auto_activation_min_daily_ci_coverage_60_pct")
        private val KEY_ISFCR_AUTO_ACTIVATION_MAX_DAILY_CI_WIDTH_30_MMOL =
            doublePreferencesKey("isfcr_auto_activation_max_daily_ci_width_30_mmol")
        private val KEY_ISFCR_AUTO_ACTIVATION_MAX_DAILY_CI_WIDTH_60_MMOL =
            doublePreferencesKey("isfcr_auto_activation_max_daily_ci_width_60_mmol")
        private val KEY_ISFCR_AUTO_ACTIVATION_ROLLING_MIN_REQUIRED_WINDOWS =
            intPreferencesKey("isfcr_auto_activation_rolling_min_required_windows")
        private val KEY_ISFCR_AUTO_ACTIVATION_ROLLING_MAE_RELAX_FACTOR =
            doublePreferencesKey("isfcr_auto_activation_rolling_mae_relax_factor")
        private val KEY_ISFCR_AUTO_ACTIVATION_ROLLING_CI_COVERAGE_RELAX_FACTOR =
            doublePreferencesKey("isfcr_auto_activation_rolling_ci_coverage_relax_factor")
        private val KEY_ISFCR_AUTO_ACTIVATION_ROLLING_CI_WIDTH_RELAX_FACTOR =
            doublePreferencesKey("isfcr_auto_activation_rolling_ci_width_relax_factor")
        private val KEY_SAFETY_MIN_TARGET_MMOL = doublePreferencesKey("safety_min_target_mmol")
        private val KEY_SAFETY_MAX_TARGET_MMOL = doublePreferencesKey("safety_max_target_mmol")
        private val KEY_BASE_TARGET_MMOL = doublePreferencesKey("base_target_mmol")
        private val KEY_BASE_TARGET_SCHEDULE_JSON = stringPreferencesKey("base_target_schedule_json")
        private val KEY_POST_HYPO_THRESHOLD_MMOL = doublePreferencesKey("post_hypo_threshold_mmol")
        private val KEY_POST_HYPO_DELTA_THRESHOLD_MMOL_5M = doublePreferencesKey("post_hypo_delta_threshold_mmol_5m")
        private val KEY_POST_HYPO_TARGET_MMOL = doublePreferencesKey("post_hypo_target_mmol")
        private val KEY_POST_HYPO_DURATION_MINUTES = intPreferencesKey("post_hypo_duration_minutes")
        private val KEY_POST_HYPO_LOOKBACK_MINUTES = intPreferencesKey("post_hypo_lookback_minutes")
        private val KEY_RULE_POST_HYPO_ENABLED = booleanPreferencesKey("rule_post_hypo_enabled")
        private val KEY_RULE_PATTERN_ENABLED = booleanPreferencesKey("rule_pattern_enabled")
        private val KEY_RULE_SEGMENT_ENABLED = booleanPreferencesKey("rule_segment_enabled")
        private val KEY_ADAPTIVE_CONTROLLER_ENABLED = booleanPreferencesKey("adaptive_controller_enabled")
        private val KEY_ADAPTIVE_DEFAULT_MIGRATION_DONE = booleanPreferencesKey("adaptive_default_migration_done")
        private val KEY_RULE_POST_HYPO_PRIORITY = intPreferencesKey("rule_post_hypo_priority")
        private val KEY_RULE_PATTERN_PRIORITY = intPreferencesKey("rule_pattern_priority")
        private val KEY_RULE_SEGMENT_PRIORITY = intPreferencesKey("rule_segment_priority")
        private val KEY_ADAPTIVE_CONTROLLER_PRIORITY = intPreferencesKey("adaptive_controller_priority")
        private val KEY_RULE_POST_HYPO_COOLDOWN = intPreferencesKey("rule_post_hypo_cooldown_minutes")
        private val KEY_RULE_PATTERN_COOLDOWN = intPreferencesKey("rule_pattern_cooldown_minutes")
        private val KEY_RULE_SEGMENT_COOLDOWN = intPreferencesKey("rule_segment_cooldown_minutes")
        private val KEY_ADAPTIVE_CONTROLLER_RETARGET_MINUTES = intPreferencesKey("adaptive_controller_retarget_minutes")
        private val KEY_ADAPTIVE_CONTROLLER_SAFETY_PROFILE = stringPreferencesKey("adaptive_controller_safety_profile")
        private val KEY_ADAPTIVE_CONTROLLER_STALE_MAX_MINUTES = intPreferencesKey("adaptive_controller_stale_max_minutes")
        private val KEY_ADAPTIVE_CONTROLLER_MAX_ACTIONS_6H = intPreferencesKey("adaptive_controller_max_actions_6h")
        private val KEY_ADAPTIVE_CONTROLLER_MAX_STEP_MMOL = doublePreferencesKey("adaptive_controller_max_step_mmol")
        private val KEY_PATTERN_MIN_SAMPLES = intPreferencesKey("pattern_min_samples")
        private val KEY_PATTERN_MIN_ACTIVE_DAYS = intPreferencesKey("pattern_min_active_days")
        private val KEY_PATTERN_LOW_RATE_TRIGGER = doublePreferencesKey("pattern_low_rate_trigger")
        private val KEY_PATTERN_HIGH_RATE_TRIGGER = doublePreferencesKey("pattern_high_rate_trigger")
        private val KEY_ANALYTICS_LOOKBACK_DAYS = intPreferencesKey("analytics_lookback_days")
        private val KEY_CIRCADIAN_PATTERNS_ENABLED = booleanPreferencesKey("circadian_patterns_enabled")
        private val KEY_CIRCADIAN_STABLE_LOOKBACK_DAYS = intPreferencesKey("circadian_stable_lookback_days")
        private val KEY_CIRCADIAN_RECENCY_LOOKBACK_DAYS = intPreferencesKey("circadian_recency_lookback_days")
        private val KEY_CIRCADIAN_USE_WEEKEND_SPLIT = booleanPreferencesKey("circadian_use_weekend_split")
        private val KEY_CIRCADIAN_USE_REPLAY_RESIDUAL_BIAS = booleanPreferencesKey("circadian_use_replay_residual_bias")
        private val KEY_CIRCADIAN_FORECAST_WEIGHT_30 = doublePreferencesKey("circadian_forecast_weight_30")
        private val KEY_CIRCADIAN_FORECAST_WEIGHT_60 = doublePreferencesKey("circadian_forecast_weight_60")
        private val KEY_SOFT_ALERT_ENABLED = booleanPreferencesKey("soft_alert_enabled")
        private val KEY_WATCH_60_ALERT_ENABLED = booleanPreferencesKey("watch_60_alert_enabled")
        private val KEY_WARNING_30_ALERT_ENABLED = booleanPreferencesKey("warning_30_alert_enabled")
        private val KEY_SOFT_HIGH_ALERT_ENABLED = booleanPreferencesKey("soft_high_alert_enabled")
        private val KEY_CRITICAL_5_ALERT_ENABLED = booleanPreferencesKey("critical_5_alert_enabled")
        private val KEY_LOW_NOW_ALERT_ENABLED = booleanPreferencesKey("low_now_alert_enabled")
        private val KEY_SOFT_ALERT_LOW_MMOL = doublePreferencesKey("soft_alert_low_mmol")
        private val KEY_SOFT_ALERT_HIGH_MMOL = doublePreferencesKey("soft_alert_high_mmol")
        private val KEY_URGENT_LOW_MMOL = doublePreferencesKey("urgent_low_mmol")
        private val KEY_SOFT_ALERT_REPEAT_MINUTES = intPreferencesKey("soft_alert_repeat_minutes")
        private val KEY_STRONG_LOW_REPEAT_MINUTES = intPreferencesKey("strong_low_repeat_minutes")
        private val KEY_SOFT_ALERT_USE_CONFIDENCE_BAND = booleanPreferencesKey("soft_alert_use_confidence_band")
        private val KEY_SOFT_ALERT_AUDIO_START_MS = intPreferencesKey("soft_alert_audio_start_ms")
        private val KEY_SOFT_ALERT_AUDIO_DURATION_MS = intPreferencesKey("soft_alert_audio_duration_ms")
        private val KEY_SOFT_ALERT_AUDIO_URI = stringPreferencesKey("soft_alert_audio_uri")
        private val KEY_SOFT_ALERT_AUDIO_DISPLAY_NAME = stringPreferencesKey("soft_alert_audio_display_name")
        private val KEY_CRITICAL_ALERT_AUDIO1_START_MS = intPreferencesKey("critical_alert_audio1_start_ms")
        private val KEY_CRITICAL_ALERT_AUDIO1_DURATION_MS = intPreferencesKey("critical_alert_audio1_duration_ms")
        private val KEY_CRITICAL_ALERT_AUDIO1_URI = stringPreferencesKey("critical_alert_audio1_uri")
        private val KEY_CRITICAL_ALERT_AUDIO1_DISPLAY_NAME = stringPreferencesKey("critical_alert_audio1_display_name")
        private val KEY_CRITICAL_ALERT_AUDIO2_START_MS = intPreferencesKey("critical_alert_audio2_start_ms")
        private val KEY_CRITICAL_ALERT_AUDIO2_DURATION_MS = intPreferencesKey("critical_alert_audio2_duration_ms")
        private val KEY_CRITICAL_ALERT_AUDIO2_URI = stringPreferencesKey("critical_alert_audio2_uri")
        private val KEY_CRITICAL_ALERT_AUDIO2_DISPLAY_NAME = stringPreferencesKey("critical_alert_audio2_display_name")
        private val KEY_MAX_ACTIONS_6H = intPreferencesKey("max_actions_in_6h")
        private val KEY_STALE_DATA_MAX_MINUTES = intPreferencesKey("stale_data_max_minutes")
        private val KEY_EXPORT_URI = stringPreferencesKey("export_folder_uri")
        private const val DEFAULT_CLOUD_BASE_URL = "https://api.openai.com/v1"
        private const val DEFAULT_BASE_TARGET_MMOL = 5.5
        private const val DEFAULT_POST_HYPO_THRESHOLD_MMOL = 4.0
        private const val DEFAULT_POST_HYPO_DELTA_THRESHOLD_MMOL_5M = 0.20
        private const val DEFAULT_POST_HYPO_TARGET_MMOL = 4.4
        private const val DEFAULT_POST_HYPO_DURATION_MINUTES = 60
        private const val DEFAULT_POST_HYPO_LOOKBACK_MINUTES = 90
        private const val DEFAULT_POST_HYPO_PRIORITY = 100
        private const val DEFAULT_PATTERN_PRIORITY = 50
        private const val DEFAULT_SEGMENT_PRIORITY = 40
        private const val DEFAULT_ADAPTIVE_CONTROLLER_PRIORITY = 120
        private const val DEFAULT_POST_HYPO_COOLDOWN_MIN = 30
        private const val DEFAULT_PATTERN_COOLDOWN_MIN = 60
        private const val DEFAULT_SEGMENT_COOLDOWN_MIN = 60
        private const val DEFAULT_ADAPTIVE_CONTROLLER_RETARGET_MINUTES = 5
        private const val DEFAULT_ADAPTIVE_CONTROLLER_SAFETY_PROFILE = "BALANCED"
        private const val DEFAULT_ADAPTIVE_CONTROLLER_STALE_MAX_MINUTES = 15
        private const val DEFAULT_ADAPTIVE_CONTROLLER_MAX_ACTIONS_6H = 4
        private const val DEFAULT_ADAPTIVE_CONTROLLER_MAX_STEP_MMOL = 0.25
        private const val DEFAULT_PATTERN_MIN_SAMPLES = 40
        private const val DEFAULT_PATTERN_MIN_ACTIVE_DAYS = 7
        private const val DEFAULT_PATTERN_LOW_RATE_TRIGGER = 0.12
        private const val DEFAULT_PATTERN_HIGH_RATE_TRIGGER = 0.18
        private const val DEFAULT_ANALYTICS_LOOKBACK_DAYS = 30
        private const val LEGACY_DEFAULT_ANALYTICS_LOOKBACK_DAYS = 365
        private const val DEFAULT_CIRCADIAN_PATTERNS_ENABLED = true
        private const val DEFAULT_CIRCADIAN_STABLE_LOOKBACK_DAYS = 14
        private const val DEFAULT_CIRCADIAN_RECENCY_LOOKBACK_DAYS = 5
        private const val DEFAULT_CIRCADIAN_USE_WEEKEND_SPLIT = true
        private const val DEFAULT_CIRCADIAN_USE_REPLAY_RESIDUAL_BIAS = true
        private const val DEFAULT_CIRCADIAN_FORECAST_WEIGHT_30 = 0.25
        private const val DEFAULT_CIRCADIAN_FORECAST_WEIGHT_60 = 0.35
        private const val DEFAULT_SOFT_ALERT_ENABLED = true
        private const val DEFAULT_WATCH_60_ALERT_ENABLED = true
        private const val DEFAULT_WARNING_30_ALERT_ENABLED = true
        private const val DEFAULT_SOFT_HIGH_ALERT_ENABLED = true
        private const val DEFAULT_CRITICAL_5_ALERT_ENABLED = true
        private const val DEFAULT_LOW_NOW_ALERT_ENABLED = true
        private const val DEFAULT_SOFT_ALERT_LOW_MMOL = 4.4
        private const val DEFAULT_SOFT_ALERT_HIGH_MMOL = 10.0
        private const val DEFAULT_URGENT_LOW_MMOL = 3.9
        private const val DEFAULT_SOFT_ALERT_REPEAT_MINUTES = 5
        private const val DEFAULT_STRONG_LOW_REPEAT_MINUTES = 2
        private const val DEFAULT_SOFT_ALERT_USE_CONFIDENCE_BAND = true
        private const val DEFAULT_SOFT_ALERT_AUDIO_START_MS = 32_000
        private const val DEFAULT_SOFT_ALERT_AUDIO_DURATION_MS = 2_000
        private const val DEFAULT_CRITICAL_ALERT_AUDIO1_START_MS = 42_000
        private const val DEFAULT_CRITICAL_ALERT_AUDIO1_DURATION_MS = 20_000
        private const val DEFAULT_CRITICAL_ALERT_AUDIO2_START_MS = 36_000
        private const val DEFAULT_CRITICAL_ALERT_AUDIO2_DURATION_MS = 20_000
        private const val DEFAULT_MAX_ACTIONS_6H = 3
        private const val DEFAULT_STALE_DATA_MAX_MINUTES = 10
        private const val DEFAULT_SAFETY_MIN_TARGET_MMOL = 4.0
        private const val DEFAULT_SAFETY_MAX_TARGET_MMOL = 10.0
        private const val INVALID_SCHEDULE_PAYLOAD = "invalid_schedule_payload"
        private const val DEFAULT_LOCAL_NIGHTSCOUT_PORT = 17580
        private const val DEFAULT_LOCAL_COMMAND_PACKAGE = "info.nightscout.androidaps"
        private const val DEFAULT_LOCAL_COMMAND_ACTION = "info.nightscout.client.NEW_TREATMENT"
        private const val DEFAULT_ENABLE_UAM_INFERENCE = true
        private const val DEFAULT_ENABLE_UAM_BOOST = true
        private const val DEFAULT_ENABLE_UAM_EXPORT = false
        private const val DEFAULT_DRY_RUN_EXPORT = true
        private const val DEFAULT_UAM_AUTO_EXPORT_CAP_ENABLED = false
        private const val DEFAULT_UAM_AUTO_EXPORT_CAP_GRAMS = 10
        private const val MIN_UAM_AUTO_EXPORT_CAP_GRAMS = 1
        private const val MAX_UAM_AUTO_EXPORT_CAP_GRAMS = 15
        private const val LEGACY_DEFAULT_DRY_RUN_EXPORT = false
        private const val DEFAULT_UAM_LEARNED_MULTIPLIER = 1.0
        private const val DEFAULT_UAM_MIN_SNACK_G = 15
        private const val DEFAULT_UAM_MAX_SNACK_G = 60
        private const val DEFAULT_UAM_SNACK_STEP_G = 5
        private const val DEFAULT_UAM_BACKDATE_MINUTES = 25
        private const val DEFAULT_UAM_DISABLE_MANUAL_COB_ACTIVE = true
        private const val DEFAULT_UAM_MANUAL_COB_THRESHOLD_G = 5.0
        private const val DEFAULT_UAM_DISABLE_MANUAL_CARBS_NEARBY = true
        private const val DEFAULT_UAM_MANUAL_MERGE_WINDOW_MINUTES = 45
        private const val DEFAULT_UAM_MAX_ABSORB_RATE_GPH_NORMAL = 30.0
        private const val DEFAULT_UAM_MAX_ABSORB_RATE_GPH_BOOST = 45.0
        private const val DEFAULT_UAM_MAX_TOTAL_G = 120.0
        private const val DEFAULT_UAM_MAX_ACTIVE_EVENTS = 2
        private const val DEFAULT_UAM_CARB_MULTIPLIER_NORMAL = 1.0
        private const val DEFAULT_UAM_CARB_MULTIPLIER_BOOST = 2.0
        private const val DEFAULT_UAM_GABS_THRESHOLD_NORMAL = 2.0
        private const val DEFAULT_UAM_GABS_THRESHOLD_BOOST = 1.2
        private const val DEFAULT_UAM_M_OF_N_NORMAL_M = 3
        private const val DEFAULT_UAM_M_OF_N_NORMAL_N = 4
        private const val DEFAULT_UAM_M_OF_N_BOOST_M = 2
        private const val DEFAULT_UAM_M_OF_N_BOOST_N = 3
        private const val DEFAULT_UAM_CONFIRM_CONF_NORMAL = 0.45
        private const val DEFAULT_UAM_CONFIRM_CONF_BOOST = 0.35
        private const val DEFAULT_UAM_MIN_CONFIRM_AGE_MIN = 10
        private const val DEFAULT_UAM_EXPORT_MIN_INTERVAL_MIN = 10
        private const val DEFAULT_UAM_EXPORT_MAX_BACKDATE_MIN = 180
        private const val DEFAULT_CARB_ABSORPTION_MAX_AGE_MINUTES = 180
        private const val DEFAULT_CARB_COMPUTATION_MAX_GRAMS = 60.0
        private const val DEFAULT_ISFCR_SHADOW_MODE = true
        private const val DEFAULT_ISFCR_CONFIDENCE_THRESHOLD = 0.55
        private const val DEFAULT_ISFCR_USE_ACTIVITY = true
        private const val DEFAULT_ISFCR_USE_MANUAL_TAGS = true
        private const val DEFAULT_ISFCR_MIN_ISF_EVIDENCE_PER_HOUR = 2
        private const val DEFAULT_ISFCR_MIN_CR_EVIDENCE_PER_HOUR = 2
        private const val DEFAULT_ISFCR_CR_MAX_GAP_MINUTES = 30
        private const val DEFAULT_ISFCR_CR_MAX_SENSOR_BLOCKED_RATE_PCT = 25.0
        private const val DEFAULT_ISFCR_CR_MAX_UAM_AMBIGUITY_RATE_PCT = 60.0
        private const val DEFAULT_ISFCR_SNAPSHOT_RETENTION_DAYS = 365
        private const val DEFAULT_ISFCR_EVIDENCE_RETENTION_DAYS = 730
        private const val DEFAULT_ISFCR_AUTO_ACTIVATION_ENABLED = false
        private const val DEFAULT_ISFCR_AUTO_ACTIVATION_LOOKBACK_HOURS = 24
        private const val DEFAULT_ISFCR_AUTO_ACTIVATION_MIN_SAMPLES = 72
        private const val DEFAULT_ISFCR_AUTO_ACTIVATION_MIN_MEAN_CONFIDENCE = 0.65
        private const val DEFAULT_ISFCR_AUTO_ACTIVATION_MAX_MEAN_ABS_ISF_DELTA_PCT = 25.0
        private const val DEFAULT_ISFCR_AUTO_ACTIVATION_MAX_MEAN_ABS_CR_DELTA_PCT = 25.0
        private const val DEFAULT_ISFCR_AUTO_ACTIVATION_MIN_SENSOR_QUALITY_SCORE = 0.46
        private const val DEFAULT_ISFCR_AUTO_ACTIVATION_MIN_SENSOR_FACTOR = 0.90
        private const val DEFAULT_ISFCR_AUTO_ACTIVATION_MAX_WEAR_CONFIDENCE_PENALTY = 0.12
        private const val DEFAULT_ISFCR_AUTO_ACTIVATION_MAX_SENSOR_AGE_HIGH_RATE_PCT = 70.0
        private const val DEFAULT_ISFCR_AUTO_ACTIVATION_MAX_SUSPECT_FALSE_LOW_RATE_PCT = 35.0
        private const val DEFAULT_ISFCR_AUTO_ACTIVATION_MIN_DAY_TYPE_RATIO = 0.30
        private const val DEFAULT_ISFCR_AUTO_ACTIVATION_MAX_DAY_TYPE_SPARSE_RATE_PCT = 75.0
        private const val DEFAULT_ISFCR_AUTO_ACTIVATION_REQUIRE_DAILY_QUALITY_GATE = true
        private const val DEFAULT_ISFCR_AUTO_ACTIVATION_DAILY_RISK_BLOCK_LEVEL = 3
        private const val DEFAULT_ISFCR_AUTO_ACTIVATION_MIN_DAILY_MATCHED_SAMPLES = 120
        private const val DEFAULT_ISFCR_AUTO_ACTIVATION_MAX_DAILY_MAE_30_MMOL = 0.90
        private const val DEFAULT_ISFCR_AUTO_ACTIVATION_MAX_DAILY_MAE_60_MMOL = 1.40
        private const val DEFAULT_ISFCR_AUTO_ACTIVATION_MAX_HYPO_RATE_PCT = 6.0
        private const val DEFAULT_ISFCR_AUTO_ACTIVATION_MIN_DAILY_CI_COVERAGE_30_PCT = 55.0
        private const val DEFAULT_ISFCR_AUTO_ACTIVATION_MIN_DAILY_CI_COVERAGE_60_PCT = 55.0
        private const val DEFAULT_ISFCR_AUTO_ACTIVATION_MAX_DAILY_CI_WIDTH_30_MMOL = 1.80
        private const val DEFAULT_ISFCR_AUTO_ACTIVATION_MAX_DAILY_CI_WIDTH_60_MMOL = 2.60
        private const val DEFAULT_ISFCR_AUTO_ACTIVATION_ROLLING_MIN_REQUIRED_WINDOWS = 2
        private const val DEFAULT_ISFCR_AUTO_ACTIVATION_ROLLING_MAE_RELAX_FACTOR = 1.15
        private const val DEFAULT_ISFCR_AUTO_ACTIVATION_ROLLING_CI_COVERAGE_RELAX_FACTOR = 0.90
        private const val DEFAULT_ISFCR_AUTO_ACTIVATION_ROLLING_CI_WIDTH_RELAX_FACTOR = 1.25
    }
}

data class AppSettings(
    val nightscoutUrl: String,
    val apiSecret: String,
    val cloudBaseUrl: String,
    val clinicalAiConfigState: ClinicalAiConfigState =
        ClinicalAiConfigState.UnconfiguredDefault(ClinicalAiProviderConfig.defaultOpenAi()),
    val automaticEventAiAnalysisEnabled: Boolean = true,
    val uiStyle: UiStyle = UiStyle.MIDNIGHT_GLASS,
    val showEventsOnGraph: Boolean = true,
    val killSwitch: Boolean,
    val powerSaveUntilMs: Long = 0L,
    val rootExperimentalEnabled: Boolean,
    val localBroadcastIngestEnabled: Boolean,
    val strictBroadcastSenderValidation: Boolean,
    val localNightscoutEnabled: Boolean,
    val localNightscoutPort: Int,
    val localNightscoutLegacyMigrationAcknowledged: Boolean = false,
    val localCommandFallbackEnabled: Boolean,
    val localCommandPackage: String,
    val localCommandAction: String,
    val insulinProfileId: String,
    val enableUamInference: Boolean = true,
    val enableUamBoost: Boolean = true,
    val enableUamExportToAaps: Boolean = false,
    val uamExportMode: UamExportMode = UamExportMode.CONFIRMED_ONLY,
    val dryRunExport: Boolean = true,
    val enableUamAutoExportCap: Boolean = false,
    val uamAutoExportCapGrams: Int = 10,
    val uamLearnedMultiplier: Double = 1.0,
    val uamMinSnackG: Int = 15,
    val uamMaxSnackG: Int = 60,
    val uamSnackStepG: Int = 5,
    val uamBackdateMinutesDefault: Int = 25,
    val uamDisableWhenManualCobActive: Boolean = true,
    val uamManualCobThresholdG: Double = 5.0,
    val uamDisableIfManualCarbsNearby: Boolean = true,
    val uamManualMergeWindowMinutes: Int = 45,
    val uamMaxAbsorbRateGphNormal: Double = 30.0,
    val uamMaxAbsorbRateGphBoost: Double = 45.0,
    val uamMaxTotalG: Double = 120.0,
    val uamMaxActiveEvents: Int = 2,
    val uamCarbMultiplierNormal: Double = 1.0,
    val uamCarbMultiplierBoost: Double = 2.0,
    val uamGAbsThresholdNormal: Double = 2.0,
    val uamGAbsThresholdBoost: Double = 1.2,
    val uamMOfNNormalM: Int = 3,
    val uamMOfNNormalN: Int = 4,
    val uamMOfNBoostM: Int = 2,
    val uamMOfNBoostN: Int = 3,
    val uamConfirmConfNormal: Double = 0.45,
    val uamConfirmConfBoost: Double = 0.35,
    val uamMinConfirmAgeMin: Int = 10,
    val uamExportMinIntervalMin: Int = 10,
    val uamExportMaxBackdateMin: Int = 180,
    val carbAbsorptionMaxAgeMinutes: Int = 180,
    val carbComputationMaxGrams: Double = 60.0,
    val sensorLagCorrectionMode: SensorLagCorrectionMode = SensorLagCorrectionMode.OFF,
    val targetManagerMode: TargetManagerMode = TargetManagerMode.SHADOW,
    val targetManagerModeManualOverride: Boolean = false,
    val targetManagerCopilotPriorityEnabled: Boolean = false,
    val targetManagerPolicyRevision: Long = 0L,
    val isfSourcePreference: SensitivitySourcePreference = SensitivitySourcePreference.EVIDENCE,
    val crSourcePreference: SensitivitySourcePreference = SensitivitySourcePreference.EVIDENCE,
    val sensitivitySettingsRevision: Long = 0L,
    val isfCrShadowMode: Boolean = true,
    val isfCrConfidenceThreshold: Double = 0.55,
    val isfCrUseActivity: Boolean = true,
    val isfCrUseManualTags: Boolean = true,
    val isfCrMinIsfEvidencePerHour: Int = 2,
    val isfCrMinCrEvidencePerHour: Int = 2,
    val isfCrCrMaxGapMinutes: Int = 30,
    val isfCrCrMaxSensorBlockedRatePct: Double = 25.0,
    val isfCrCrMaxUamAmbiguityRatePct: Double = 60.0,
    val isfCrSnapshotRetentionDays: Int = 365,
    val isfCrEvidenceRetentionDays: Int = 730,
    val isfCrAutoActivationEnabled: Boolean = false,
    val isfCrAutoActivationLookbackHours: Int = 24,
    val isfCrAutoActivationMinSamples: Int = 72,
    val isfCrAutoActivationMinMeanConfidence: Double = 0.65,
    val isfCrAutoActivationMaxMeanAbsIsfDeltaPct: Double = 25.0,
    val isfCrAutoActivationMaxMeanAbsCrDeltaPct: Double = 25.0,
    val isfCrAutoActivationMinSensorQualityScore: Double = 0.46,
    val isfCrAutoActivationMinSensorFactor: Double = 0.90,
    val isfCrAutoActivationMaxWearConfidencePenalty: Double = 0.12,
    val isfCrAutoActivationMaxSensorAgeHighRatePct: Double = 70.0,
    val isfCrAutoActivationMaxSuspectFalseLowRatePct: Double = 35.0,
    val isfCrAutoActivationMinDayTypeRatio: Double = 0.30,
    val isfCrAutoActivationMaxDayTypeSparseRatePct: Double = 75.0,
    val isfCrAutoActivationRequireDailyQualityGate: Boolean = true,
    val isfCrAutoActivationDailyRiskBlockLevel: Int = 3,
    val isfCrAutoActivationMinDailyMatchedSamples: Int = 120,
    val isfCrAutoActivationMaxDailyMae30Mmol: Double = 0.90,
    val isfCrAutoActivationMaxDailyMae60Mmol: Double = 1.40,
    val isfCrAutoActivationMaxHypoRatePct: Double = 6.0,
    val isfCrAutoActivationMinDailyCiCoverage30Pct: Double = 55.0,
    val isfCrAutoActivationMinDailyCiCoverage60Pct: Double = 55.0,
    val isfCrAutoActivationMaxDailyCiWidth30Mmol: Double = 1.80,
    val isfCrAutoActivationMaxDailyCiWidth60Mmol: Double = 2.60,
    val isfCrAutoActivationRollingMinRequiredWindows: Int = 2,
    val isfCrAutoActivationRollingMaeRelaxFactor: Double = 1.15,
    val isfCrAutoActivationRollingCiCoverageRelaxFactor: Double = 0.90,
    val isfCrAutoActivationRollingCiWidthRelaxFactor: Double = 1.25,
    val safetyMinTargetMmol: Double = 4.0,
    val safetyMaxTargetMmol: Double = 10.0,
    val baseTargetMmol: Double,
    val baseTargetSchedule: BaseTargetSchedule = BaseTargetSchedule.legacy(baseTargetMmol),
    val baseTargetScheduleRecoveryReason: String? = null,
    val postHypoThresholdMmol: Double,
    val postHypoDeltaThresholdMmol5m: Double,
    val postHypoTargetMmol: Double,
    val postHypoDurationMinutes: Int,
    val postHypoLookbackMinutes: Int,
    val rulePostHypoEnabled: Boolean,
    val rulePatternEnabled: Boolean,
    val ruleSegmentEnabled: Boolean,
    val adaptiveControllerEnabled: Boolean,
    val rulePostHypoPriority: Int,
    val rulePatternPriority: Int,
    val ruleSegmentPriority: Int,
    val adaptiveControllerPriority: Int,
    val rulePostHypoCooldownMinutes: Int,
    val rulePatternCooldownMinutes: Int,
    val ruleSegmentCooldownMinutes: Int,
    val adaptiveControllerRetargetMinutes: Int,
    val adaptiveControllerSafetyProfile: String,
    val adaptiveControllerStaleMaxMinutes: Int,
    val adaptiveControllerMaxActions6h: Int,
    val adaptiveControllerMaxStepMmol: Double,
    val patternMinSamplesPerWindow: Int,
    val patternMinActiveDaysPerWindow: Int,
    val patternLowRateTrigger: Double,
    val patternHighRateTrigger: Double,
    val analyticsLookbackDays: Int,
    val circadianPatternsEnabled: Boolean = true,
    val circadianStableLookbackDays: Int = 14,
    val circadianRecencyLookbackDays: Int = 5,
    val circadianUseWeekendSplit: Boolean = true,
    val circadianUseReplayResidualBias: Boolean = true,
    val circadianForecastWeight30: Double = 0.25,
    val circadianForecastWeight60: Double = 0.35,
    val softAlertEnabled: Boolean = true,
    val watch60AlertEnabled: Boolean = true,
    val warning30AlertEnabled: Boolean = true,
    val softHighAlertEnabled: Boolean = true,
    val critical5AlertEnabled: Boolean = true,
    val lowNowAlertEnabled: Boolean = true,
    val softAlertLowMmol: Double = 4.4,
    val softAlertHighMmol: Double = 10.0,
    val urgentLowMmol: Double = 3.9,
    val softAlertRepeatMinutes: Int = 5,
    val strongLowRepeatMinutes: Int = 2,
    val softAlertUseConfidenceBand: Boolean = true,
    val softAlertAudioStartMs: Int = 32_000,
    val softAlertAudioDurationMs: Int = 2_000,
    val softAlertAudioUri: String? = null,
    val softAlertAudioDisplayName: String? = null,
    val criticalAlertAudio1StartMs: Int = 42_000,
    val criticalAlertAudio1DurationMs: Int = 20_000,
    val criticalAlertAudio1Uri: String? = null,
    val criticalAlertAudio1DisplayName: String? = null,
    val criticalAlertAudio2StartMs: Int = 36_000,
    val criticalAlertAudio2DurationMs: Int = 20_000,
    val criticalAlertAudio2Uri: String? = null,
    val criticalAlertAudio2DisplayName: String? = null,
    val maxActionsIn6Hours: Int,
    val staleDataMaxMinutes: Int,
    val exportFolderUri: String?,
    val therapyActionsArmed: Boolean = false,
    val energyProfile: EnergyProfileSettings = EnergyProfileSettings(),
    val mealPortions: MealPortionSettings = MealPortionSettings()
)

/**
 * Exact settings boundary for the atomic ISF/CR runtime. Keep display, alert and transport
 * preferences out of this fingerprint; add a value only when it can change a candidate,
 * quality gate, source decision or effective ISF/CR.
 */
internal fun AppSettings.sensitivityRuntimeFingerprint(): List<Any?> = listOf(
    isfSourcePreference,
    crSourcePreference,
    isfCrShadowMode,
    isfCrConfidenceThreshold.coerceIn(0.2, 0.95),
    isfCrUseActivity,
    isfCrUseManualTags,
    isfCrMinIsfEvidencePerHour.coerceIn(0, 12),
    isfCrMinCrEvidencePerHour.coerceIn(0, 12),
    isfCrCrMaxGapMinutes.coerceIn(10, 60),
    isfCrCrMaxSensorBlockedRatePct.coerceIn(0.0, 100.0),
    isfCrCrMaxUamAmbiguityRatePct.coerceIn(0.0, 100.0),
    analyticsLookbackDays.coerceIn(30, 730),
    energyProfile.physiologicalSex
)

internal fun AppSettings.sensitivityRuntimeIdentity() = SensitivityRuntimeSettingsIdentity(
    revision = sensitivitySettingsRevision,
    isfSource = isfSourcePreference,
    crSource = crSourcePreference
)

private fun AppSettings.withSensitivityRuntimeSettingsFrom(source: AppSettings): AppSettings = copy(
    isfSourcePreference = source.isfSourcePreference,
    crSourcePreference = source.crSourcePreference,
    sensitivitySettingsRevision = source.sensitivitySettingsRevision,
    isfCrShadowMode = source.isfCrShadowMode,
    isfCrConfidenceThreshold = source.isfCrConfidenceThreshold,
    isfCrUseActivity = source.isfCrUseActivity,
    isfCrUseManualTags = source.isfCrUseManualTags,
    isfCrMinIsfEvidencePerHour = source.isfCrMinIsfEvidencePerHour,
    isfCrMinCrEvidencePerHour = source.isfCrMinCrEvidencePerHour,
    isfCrCrMaxGapMinutes = source.isfCrCrMaxGapMinutes,
    isfCrCrMaxSensorBlockedRatePct = source.isfCrCrMaxSensorBlockedRatePct,
    isfCrCrMaxUamAmbiguityRatePct = source.isfCrCrMaxUamAmbiguityRatePct,
    analyticsLookbackDays = source.analyticsLookbackDays,
    energyProfile = energyProfile.copy(
        physiologicalSex = source.energyProfile.physiologicalSex
    )
)

fun AppSettings.toUamUserSettings(): UamUserSettings = UamUserSettings(
    minSnackG = uamMinSnackG,
    maxSnackG = uamMaxSnackG,
    snackStepG = uamSnackStepG,
    backdateMinutesDefault = uamBackdateMinutesDefault,
    disableUamWhenManualCobActive = uamDisableWhenManualCobActive,
    manualCobThresholdG = uamManualCobThresholdG,
    disableUamIfManualCarbsNearby = uamDisableIfManualCarbsNearby,
    manualMergeWindowMinutes = uamManualMergeWindowMinutes,
    maxUamAbsorbRateGph_Normal = uamMaxAbsorbRateGphNormal,
    maxUamAbsorbRateGph_Boost = uamMaxAbsorbRateGphBoost,
    maxUamTotalG = uamMaxTotalG,
    maxActiveUamEvents = uamMaxActiveEvents,
    uamCarbMultiplier_Normal = uamCarbMultiplierNormal,
    uamCarbMultiplier_Boost = uamCarbMultiplierBoost,
    gAbsThreshold_Normal = uamGAbsThresholdNormal,
    gAbsThreshold_Boost = uamGAbsThresholdBoost,
    mOfN_Normal = uamMOfNNormalM to uamMOfNNormalN,
    mOfN_Boost = uamMOfNBoostM to uamMOfNBoostN,
    confirmConf_Normal = uamConfirmConfNormal,
    confirmConf_Boost = uamConfirmConfBoost,
    minConfirmAgeMin = uamMinConfirmAgeMin,
    exportMinIntervalMin = uamExportMinIntervalMin,
    exportMaxBackdateMin = uamExportMaxBackdateMin
)

fun AppSettings.resolvedNightscoutUrl(): String {
    val explicit = nightscoutUrl.trim()
    if (explicit.isNotBlank()) return explicit
    return if (localNightscoutEnabled) "https://127.0.0.1:$localNightscoutPort" else ""
}
