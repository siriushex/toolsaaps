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

    val settings: Flow<AppSettings> = dataStore.data.map(::readSettings)

    private fun readSettings(prefs: Preferences): AppSettings {
        val adaptiveEnabled = resolveAdaptiveControllerEnabled(prefs)
        val (safetyMinTargetMmol, safetyMaxTargetMmol) = resolveSafetyTargetBounds(prefs)
        return AppSettings(
            nightscoutUrl = prefs[KEY_NS_URL].orEmpty(),
            apiSecret = prefs[KEY_NS_SECRET].orEmpty(),
            cloudBaseUrl = prefs[KEY_CLOUD_URL]
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: DEFAULT_CLOUD_BASE_URL,
            openAiApiKey = prefs[KEY_OPENAI_KEY]
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: DEFAULT_OPENAI_API_KEY,
            uiStyle = resolveUiStyle(prefs[KEY_UI_STYLE]),
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
            carbAbsorptionMaxAgeMinutes = (prefs[KEY_CARB_ABSORPTION_MAX_AGE_MINUTES]
                ?: DEFAULT_CARB_ABSORPTION_MAX_AGE_MINUTES).coerceIn(60, 180),
            carbComputationMaxGrams = (prefs[KEY_CARB_COMPUTATION_MAX_GRAMS]
                ?: DEFAULT_CARB_COMPUTATION_MAX_GRAMS).coerceIn(20.0, 60.0),
            sensorLagCorrectionMode = resolveSensorLagCorrectionMode(prefs[KEY_SENSOR_LAG_CORRECTION_MODE]),
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
            baseTargetMmol = (prefs[KEY_BASE_TARGET_MMOL] ?: DEFAULT_BASE_TARGET_MMOL)
                .coerceIn(safetyMinTargetMmol, safetyMaxTargetMmol),
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
            maxActionsIn6Hours = prefs[KEY_MAX_ACTIONS_6H] ?: DEFAULT_MAX_ACTIONS_6H,
            staleDataMaxMinutes = prefs[KEY_STALE_DATA_MAX_MINUTES] ?: DEFAULT_STALE_DATA_MAX_MINUTES,
            exportFolderUri = prefs[KEY_EXPORT_URI]
        )
    }

    suspend fun update(updater: (AppSettings) -> AppSettings) {
        dataStore.edit { prefs ->
            val current = readSettings(prefs)
            val next = updater(current)
            prefs[KEY_NS_URL] = next.nightscoutUrl
            prefs[KEY_NS_SECRET] = next.apiSecret
            prefs[KEY_CLOUD_URL] = next.cloudBaseUrl
            prefs[KEY_OPENAI_KEY] = next.openAiApiKey
            prefs[KEY_UI_STYLE] = next.uiStyle.name
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
            prefs[KEY_CARB_ABSORPTION_MAX_AGE_MINUTES] = next.carbAbsorptionMaxAgeMinutes.coerceIn(60, 180)
            prefs[KEY_CARB_COMPUTATION_MAX_GRAMS] = next.carbComputationMaxGrams.coerceIn(20.0, 60.0)
            prefs[KEY_SENSOR_LAG_CORRECTION_MODE] = next.sensorLagCorrectionMode.name
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
            val normalizedSafetyMax = next.safetyMaxTargetMmol.coerceIn(4.2, 10.0)
            val normalizedSafetyMin = next.safetyMinTargetMmol
                .coerceIn(4.0, 9.8)
                .coerceAtMost(normalizedSafetyMax - 0.2)
            prefs[KEY_SAFETY_MIN_TARGET_MMOL] = normalizedSafetyMin
            prefs[KEY_SAFETY_MAX_TARGET_MMOL] = normalizedSafetyMax
            prefs[KEY_BASE_TARGET_MMOL] = next.baseTargetMmol.coerceIn(normalizedSafetyMin, normalizedSafetyMax)
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

    private fun resolveAdaptiveControllerEnabled(prefs: Preferences): Boolean {
        return prefs[KEY_ADAPTIVE_CONTROLLER_ENABLED] ?: true
    }

    private fun resolveSafetyTargetBounds(prefs: Preferences): Pair<Double, Double> {
        val maxBound = (prefs[KEY_SAFETY_MAX_TARGET_MMOL] ?: DEFAULT_SAFETY_MAX_TARGET_MMOL)
            .coerceIn(4.2, 10.0)
        val minBound = (prefs[KEY_SAFETY_MIN_TARGET_MMOL] ?: DEFAULT_SAFETY_MIN_TARGET_MMOL)
            .coerceIn(4.0, 9.8)
            .coerceAtMost(maxBound - 0.2)
        return minBound to maxBound
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

    private fun resolveUiStyle(raw: String?): UiStyle {
        return UiStyle.fromRaw(raw)
    }

    companion object {
        private val KEY_NS_URL = stringPreferencesKey("nightscout_url")
        private val KEY_NS_SECRET = stringPreferencesKey("nightscout_secret")
        private val KEY_CLOUD_URL = stringPreferencesKey("cloud_base_url")
        private val KEY_OPENAI_KEY = stringPreferencesKey("openai_api_key")
        private val KEY_UI_STYLE = stringPreferencesKey("ui_style")
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
        private val KEY_CARB_ABSORPTION_MAX_AGE_MINUTES = intPreferencesKey("carb_absorption_max_age_minutes")
        private val KEY_CARB_COMPUTATION_MAX_GRAMS = doublePreferencesKey("carb_computation_max_grams")
        private val KEY_SENSOR_LAG_CORRECTION_MODE = stringPreferencesKey("sensor_lag_correction_mode")
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
        private val KEY_MAX_ACTIONS_6H = intPreferencesKey("max_actions_in_6h")
        private val KEY_STALE_DATA_MAX_MINUTES = intPreferencesKey("stale_data_max_minutes")
        private val KEY_EXPORT_URI = stringPreferencesKey("export_folder_uri")
        private const val DEFAULT_CLOUD_BASE_URL = "https://api.openai.com/v1"
        private const val DEFAULT_OPENAI_API_KEY = ""
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
        private const val DEFAULT_ANALYTICS_LOOKBACK_DAYS = 365
        private const val DEFAULT_CIRCADIAN_PATTERNS_ENABLED = true
        private const val DEFAULT_CIRCADIAN_STABLE_LOOKBACK_DAYS = 14
        private const val DEFAULT_CIRCADIAN_RECENCY_LOOKBACK_DAYS = 5
        private const val DEFAULT_CIRCADIAN_USE_WEEKEND_SPLIT = true
        private const val DEFAULT_CIRCADIAN_USE_REPLAY_RESIDUAL_BIAS = true
        private const val DEFAULT_CIRCADIAN_FORECAST_WEIGHT_30 = 0.25
        private const val DEFAULT_CIRCADIAN_FORECAST_WEIGHT_60 = 0.35
        private const val DEFAULT_MAX_ACTIONS_6H = 3
        private const val DEFAULT_STALE_DATA_MAX_MINUTES = 10
        private const val DEFAULT_SAFETY_MIN_TARGET_MMOL = 4.0
        private const val DEFAULT_SAFETY_MAX_TARGET_MMOL = 10.0
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
    val openAiApiKey: String,
    val uiStyle: UiStyle = UiStyle.CLASSIC,
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
    val carbAbsorptionMaxAgeMinutes: Int = 180,
    val carbComputationMaxGrams: Double = 60.0,
    val sensorLagCorrectionMode: SensorLagCorrectionMode = SensorLagCorrectionMode.OFF,
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
