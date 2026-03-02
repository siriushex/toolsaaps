package io.aaps.copilot.config

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import io.aaps.copilot.domain.predict.InsulinActionProfileId
import io.aaps.copilot.domain.predict.UamExportMode
import io.aaps.copilot.domain.predict.UamUserSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class AppSettingsStore(context: Context) {

    private val dataStore = PreferenceDataStoreFactory.create(
        produceFile = { context.preferencesDataStoreFile("copilot_settings.preferences_pb") }
    )

    val settings: Flow<AppSettings> = dataStore.data.map { prefs ->
        val adaptiveEnabled = resolveAdaptiveControllerEnabled(prefs)
        AppSettings(
            nightscoutUrl = prefs[KEY_NS_URL].orEmpty(),
            apiSecret = prefs[KEY_NS_SECRET].orEmpty(),
            cloudBaseUrl = prefs[KEY_CLOUD_URL].orEmpty(),
            openAiApiKey = prefs[KEY_OPENAI_KEY].orEmpty(),
            killSwitch = prefs[KEY_KILL_SWITCH] ?: false,
            rootExperimentalEnabled = prefs[KEY_ROOT_EXPERIMENTAL] ?: false,
            localBroadcastIngestEnabled = prefs[KEY_LOCAL_BROADCAST_INGEST] ?: true,
            strictBroadcastSenderValidation = prefs[KEY_STRICT_BROADCAST_VALIDATION] ?: false,
            localNightscoutEnabled = prefs[KEY_LOCAL_NIGHTSCOUT_ENABLED]
                ?: prefs[KEY_NS_URL].orEmpty().isBlank(),
            localNightscoutPort = prefs[KEY_LOCAL_NIGHTSCOUT_PORT] ?: DEFAULT_LOCAL_NIGHTSCOUT_PORT,
            localCommandFallbackEnabled = prefs[KEY_LOCAL_COMMAND_FALLBACK_ENABLED] ?: true,
            localCommandPackage = prefs[KEY_LOCAL_COMMAND_PACKAGE] ?: DEFAULT_LOCAL_COMMAND_PACKAGE,
            localCommandAction = prefs[KEY_LOCAL_COMMAND_ACTION] ?: DEFAULT_LOCAL_COMMAND_ACTION,
            insulinProfileId = normalizeInsulinProfileId(prefs[KEY_INSULIN_PROFILE]),
            enableUamInference = prefs[KEY_ENABLE_UAM_INFERENCE] ?: DEFAULT_ENABLE_UAM_INFERENCE,
            enableUamBoost = prefs[KEY_ENABLE_UAM_BOOST] ?: DEFAULT_ENABLE_UAM_BOOST,
            enableUamExportToAaps = prefs[KEY_ENABLE_UAM_EXPORT] ?: DEFAULT_ENABLE_UAM_EXPORT,
            uamExportMode = resolveUamExportMode(prefs[KEY_UAM_EXPORT_MODE]),
            dryRunExport = prefs[KEY_DRY_RUN_EXPORT] ?: DEFAULT_DRY_RUN_EXPORT,
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
            baseTargetMmol = prefs[KEY_BASE_TARGET_MMOL] ?: DEFAULT_BASE_TARGET_MMOL,
            postHypoThresholdMmol = prefs[KEY_POST_HYPO_THRESHOLD_MMOL] ?: DEFAULT_POST_HYPO_THRESHOLD_MMOL,
            postHypoDeltaThresholdMmol5m = prefs[KEY_POST_HYPO_DELTA_THRESHOLD_MMOL_5M] ?: DEFAULT_POST_HYPO_DELTA_THRESHOLD_MMOL_5M,
            postHypoTargetMmol = prefs[KEY_POST_HYPO_TARGET_MMOL] ?: DEFAULT_POST_HYPO_TARGET_MMOL,
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
            maxActionsIn6Hours = prefs[KEY_MAX_ACTIONS_6H] ?: DEFAULT_MAX_ACTIONS_6H,
            staleDataMaxMinutes = prefs[KEY_STALE_DATA_MAX_MINUTES] ?: DEFAULT_STALE_DATA_MAX_MINUTES,
            exportFolderUri = prefs[KEY_EXPORT_URI]
        )
    }

    suspend fun update(updater: (AppSettings) -> AppSettings) {
        dataStore.edit { prefs ->
            val adaptiveEnabled = resolveAdaptiveControllerEnabled(prefs)
            val current = AppSettings(
                nightscoutUrl = prefs[KEY_NS_URL].orEmpty(),
                apiSecret = prefs[KEY_NS_SECRET].orEmpty(),
                cloudBaseUrl = prefs[KEY_CLOUD_URL].orEmpty(),
                openAiApiKey = prefs[KEY_OPENAI_KEY].orEmpty(),
                killSwitch = prefs[KEY_KILL_SWITCH] ?: false,
                rootExperimentalEnabled = prefs[KEY_ROOT_EXPERIMENTAL] ?: false,
                localBroadcastIngestEnabled = prefs[KEY_LOCAL_BROADCAST_INGEST] ?: true,
                strictBroadcastSenderValidation = prefs[KEY_STRICT_BROADCAST_VALIDATION] ?: false,
                localNightscoutEnabled = prefs[KEY_LOCAL_NIGHTSCOUT_ENABLED]
                    ?: prefs[KEY_NS_URL].orEmpty().isBlank(),
                localNightscoutPort = prefs[KEY_LOCAL_NIGHTSCOUT_PORT] ?: DEFAULT_LOCAL_NIGHTSCOUT_PORT,
                localCommandFallbackEnabled = prefs[KEY_LOCAL_COMMAND_FALLBACK_ENABLED] ?: true,
                localCommandPackage = prefs[KEY_LOCAL_COMMAND_PACKAGE] ?: DEFAULT_LOCAL_COMMAND_PACKAGE,
                localCommandAction = prefs[KEY_LOCAL_COMMAND_ACTION] ?: DEFAULT_LOCAL_COMMAND_ACTION,
                insulinProfileId = normalizeInsulinProfileId(prefs[KEY_INSULIN_PROFILE]),
                enableUamInference = prefs[KEY_ENABLE_UAM_INFERENCE] ?: DEFAULT_ENABLE_UAM_INFERENCE,
                enableUamBoost = prefs[KEY_ENABLE_UAM_BOOST] ?: DEFAULT_ENABLE_UAM_BOOST,
                enableUamExportToAaps = prefs[KEY_ENABLE_UAM_EXPORT] ?: DEFAULT_ENABLE_UAM_EXPORT,
                uamExportMode = resolveUamExportMode(prefs[KEY_UAM_EXPORT_MODE]),
                dryRunExport = prefs[KEY_DRY_RUN_EXPORT] ?: DEFAULT_DRY_RUN_EXPORT,
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
                baseTargetMmol = prefs[KEY_BASE_TARGET_MMOL] ?: DEFAULT_BASE_TARGET_MMOL,
                postHypoThresholdMmol = prefs[KEY_POST_HYPO_THRESHOLD_MMOL] ?: DEFAULT_POST_HYPO_THRESHOLD_MMOL,
                postHypoDeltaThresholdMmol5m = prefs[KEY_POST_HYPO_DELTA_THRESHOLD_MMOL_5M] ?: DEFAULT_POST_HYPO_DELTA_THRESHOLD_MMOL_5M,
                postHypoTargetMmol = prefs[KEY_POST_HYPO_TARGET_MMOL] ?: DEFAULT_POST_HYPO_TARGET_MMOL,
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
                maxActionsIn6Hours = prefs[KEY_MAX_ACTIONS_6H] ?: DEFAULT_MAX_ACTIONS_6H,
                staleDataMaxMinutes = prefs[KEY_STALE_DATA_MAX_MINUTES] ?: DEFAULT_STALE_DATA_MAX_MINUTES,
                exportFolderUri = prefs[KEY_EXPORT_URI]
            )
            val next = updater(current)
            prefs[KEY_NS_URL] = next.nightscoutUrl
            prefs[KEY_NS_SECRET] = next.apiSecret
            prefs[KEY_CLOUD_URL] = next.cloudBaseUrl
            prefs[KEY_OPENAI_KEY] = next.openAiApiKey
            prefs[KEY_KILL_SWITCH] = next.killSwitch
            prefs[KEY_ROOT_EXPERIMENTAL] = next.rootExperimentalEnabled
            prefs[KEY_LOCAL_BROADCAST_INGEST] = next.localBroadcastIngestEnabled
            prefs[KEY_STRICT_BROADCAST_VALIDATION] = next.strictBroadcastSenderValidation
            prefs[KEY_LOCAL_NIGHTSCOUT_ENABLED] = next.localNightscoutEnabled
            prefs[KEY_LOCAL_NIGHTSCOUT_PORT] = next.localNightscoutPort
            prefs[KEY_LOCAL_COMMAND_FALLBACK_ENABLED] = next.localCommandFallbackEnabled
            prefs[KEY_LOCAL_COMMAND_PACKAGE] = next.localCommandPackage
            prefs[KEY_LOCAL_COMMAND_ACTION] = next.localCommandAction
            prefs[KEY_INSULIN_PROFILE] = normalizeInsulinProfileId(next.insulinProfileId)
            prefs[KEY_ENABLE_UAM_INFERENCE] = next.enableUamInference
            prefs[KEY_ENABLE_UAM_BOOST] = next.enableUamBoost
            prefs[KEY_ENABLE_UAM_EXPORT] = next.enableUamExportToAaps
            prefs[KEY_UAM_EXPORT_MODE] = next.uamExportMode.name
            prefs[KEY_DRY_RUN_EXPORT] = next.dryRunExport
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
            prefs[KEY_BASE_TARGET_MMOL] = next.baseTargetMmol
            prefs[KEY_POST_HYPO_THRESHOLD_MMOL] = next.postHypoThresholdMmol
            prefs[KEY_POST_HYPO_DELTA_THRESHOLD_MMOL_5M] = next.postHypoDeltaThresholdMmol5m
            prefs[KEY_POST_HYPO_TARGET_MMOL] = next.postHypoTargetMmol
            prefs[KEY_POST_HYPO_DURATION_MINUTES] = next.postHypoDurationMinutes
            prefs[KEY_POST_HYPO_LOOKBACK_MINUTES] = next.postHypoLookbackMinutes
            prefs[KEY_RULE_POST_HYPO_ENABLED] = next.rulePostHypoEnabled
            prefs[KEY_RULE_PATTERN_ENABLED] = next.rulePatternEnabled
            prefs[KEY_RULE_SEGMENT_ENABLED] = next.ruleSegmentEnabled
            // Product policy: adaptive controller is always enabled in runtime.
            prefs[KEY_ADAPTIVE_CONTROLLER_ENABLED] = true
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
            prefs[KEY_MAX_ACTIONS_6H] = next.maxActionsIn6Hours
            prefs[KEY_STALE_DATA_MAX_MINUTES] = next.staleDataMaxMinutes
            if (next.exportFolderUri.isNullOrBlank()) {
                prefs.remove(KEY_EXPORT_URI)
            } else {
                prefs[KEY_EXPORT_URI] = next.exportFolderUri
            }
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

    suspend fun ensureUamExportDefaultsEnabled(): Boolean {
        var enabledNow = false
        dataStore.edit { prefs ->
            if (prefs[KEY_UAM_EXPORT_DEFAULT_MIGRATION_DONE] == true) return@edit
            var changed = false
            if (prefs[KEY_ENABLE_UAM_EXPORT] != true) {
                prefs[KEY_ENABLE_UAM_EXPORT] = true
                changed = true
            }
            val mode = resolveUamExportMode(prefs[KEY_UAM_EXPORT_MODE])
            if (mode == UamExportMode.OFF) {
                prefs[KEY_UAM_EXPORT_MODE] = UamExportMode.CONFIRMED_ONLY.name
                changed = true
            }
            if (prefs[KEY_DRY_RUN_EXPORT] != false) {
                prefs[KEY_DRY_RUN_EXPORT] = false
                changed = true
            }
            prefs[KEY_UAM_EXPORT_DEFAULT_MIGRATION_DONE] = true
            enabledNow = changed
        }
        return enabledNow
    }

    private fun resolveAdaptiveControllerEnabled(@Suppress("UNUSED_PARAMETER") prefs: Preferences): Boolean {
        // Keep controller permanently active even if old settings were saved with disabled flag.
        return true
    }

    private fun normalizeInsulinProfileId(raw: String?): String {
        return InsulinActionProfileId.fromRaw(raw).name
    }

    private fun resolveUamExportMode(raw: String?): UamExportMode {
        return runCatching {
            UamExportMode.valueOf(raw?.trim().orEmpty().uppercase())
        }.getOrDefault(UamExportMode.CONFIRMED_ONLY)
    }

    companion object {
        private val KEY_NS_URL = stringPreferencesKey("nightscout_url")
        private val KEY_NS_SECRET = stringPreferencesKey("nightscout_secret")
        private val KEY_CLOUD_URL = stringPreferencesKey("cloud_base_url")
        private val KEY_OPENAI_KEY = stringPreferencesKey("openai_api_key")
        private val KEY_KILL_SWITCH = booleanPreferencesKey("kill_switch")
        private val KEY_ROOT_EXPERIMENTAL = booleanPreferencesKey("root_experimental")
        private val KEY_LOCAL_BROADCAST_INGEST = booleanPreferencesKey("local_broadcast_ingest_enabled")
        private val KEY_STRICT_BROADCAST_VALIDATION = booleanPreferencesKey("strict_broadcast_sender_validation")
        private val KEY_LOCAL_NIGHTSCOUT_ENABLED = booleanPreferencesKey("local_nightscout_enabled")
        private val KEY_LOCAL_NIGHTSCOUT_PORT = intPreferencesKey("local_nightscout_port")
        private val KEY_LOCAL_COMMAND_FALLBACK_ENABLED = booleanPreferencesKey("local_command_fallback_enabled")
        private val KEY_LOCAL_COMMAND_PACKAGE = stringPreferencesKey("local_command_package")
        private val KEY_LOCAL_COMMAND_ACTION = stringPreferencesKey("local_command_action")
        private val KEY_INSULIN_PROFILE = stringPreferencesKey("insulin_profile_id")
        private val KEY_ENABLE_UAM_INFERENCE = booleanPreferencesKey("enable_uam_inference")
        private val KEY_ENABLE_UAM_BOOST = booleanPreferencesKey("enable_uam_boost")
        private val KEY_ENABLE_UAM_EXPORT = booleanPreferencesKey("enable_uam_export_to_aaps")
        private val KEY_UAM_EXPORT_MODE = stringPreferencesKey("uam_export_mode")
        private val KEY_DRY_RUN_EXPORT = booleanPreferencesKey("uam_dry_run_export")
        private val KEY_UAM_EXPORT_DEFAULT_MIGRATION_DONE =
            booleanPreferencesKey("uam_export_default_migration_done")
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
        private val KEY_BASE_TARGET_MMOL = doublePreferencesKey("base_target_mmol")
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
        private val KEY_MAX_ACTIONS_6H = intPreferencesKey("max_actions_in_6h")
        private val KEY_STALE_DATA_MAX_MINUTES = intPreferencesKey("stale_data_max_minutes")
        private val KEY_EXPORT_URI = stringPreferencesKey("export_folder_uri")
        private const val DEFAULT_BASE_TARGET_MMOL = 5.5
        private const val DEFAULT_POST_HYPO_THRESHOLD_MMOL = 3.0
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
        private const val DEFAULT_ANALYTICS_LOOKBACK_DAYS = 365
        private const val DEFAULT_MAX_ACTIONS_6H = 3
        private const val DEFAULT_STALE_DATA_MAX_MINUTES = 10
        private const val DEFAULT_LOCAL_NIGHTSCOUT_PORT = 17580
        private const val DEFAULT_LOCAL_COMMAND_PACKAGE = "info.nightscout.androidaps"
        private const val DEFAULT_LOCAL_COMMAND_ACTION = "info.nightscout.client.NEW_TREATMENT"
        private const val DEFAULT_ENABLE_UAM_INFERENCE = true
        private const val DEFAULT_ENABLE_UAM_BOOST = true
        private const val DEFAULT_ENABLE_UAM_EXPORT = true
        private const val DEFAULT_DRY_RUN_EXPORT = false
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
    }
}

data class AppSettings(
    val nightscoutUrl: String,
    val apiSecret: String,
    val cloudBaseUrl: String,
    val openAiApiKey: String,
    val killSwitch: Boolean,
    val rootExperimentalEnabled: Boolean,
    val localBroadcastIngestEnabled: Boolean,
    val strictBroadcastSenderValidation: Boolean,
    val localNightscoutEnabled: Boolean,
    val localNightscoutPort: Int,
    val localCommandFallbackEnabled: Boolean,
    val localCommandPackage: String,
    val localCommandAction: String,
    val insulinProfileId: String,
    val enableUamInference: Boolean = true,
    val enableUamBoost: Boolean = true,
    val enableUamExportToAaps: Boolean = true,
    val uamExportMode: UamExportMode = UamExportMode.CONFIRMED_ONLY,
    val dryRunExport: Boolean = false,
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
    val baseTargetMmol: Double,
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
    val maxActionsIn6Hours: Int,
    val staleDataMaxMinutes: Int,
    val exportFolderUri: String?
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
