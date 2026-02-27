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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class AppSettingsStore(context: Context) {

    private val dataStore = PreferenceDataStoreFactory.create(
        produceFile = { context.preferencesDataStoreFile("copilot_settings.preferences_pb") }
    )

    val settings: Flow<AppSettings> = dataStore.data.map { prefs ->
        AppSettings(
            nightscoutUrl = prefs[KEY_NS_URL].orEmpty(),
            apiSecret = prefs[KEY_NS_SECRET].orEmpty(),
            cloudBaseUrl = prefs[KEY_CLOUD_URL].orEmpty(),
            openAiApiKey = prefs[KEY_OPENAI_KEY].orEmpty(),
            killSwitch = prefs[KEY_KILL_SWITCH] ?: false,
            rootExperimentalEnabled = prefs[KEY_ROOT_EXPERIMENTAL] ?: false,
            localBroadcastIngestEnabled = prefs[KEY_LOCAL_BROADCAST_INGEST] ?: true,
            strictBroadcastSenderValidation = prefs[KEY_STRICT_BROADCAST_VALIDATION] ?: false,
            baseTargetMmol = prefs[KEY_BASE_TARGET_MMOL] ?: DEFAULT_BASE_TARGET_MMOL,
            postHypoThresholdMmol = prefs[KEY_POST_HYPO_THRESHOLD_MMOL] ?: DEFAULT_POST_HYPO_THRESHOLD_MMOL,
            postHypoDeltaThresholdMmol5m = prefs[KEY_POST_HYPO_DELTA_THRESHOLD_MMOL_5M] ?: DEFAULT_POST_HYPO_DELTA_THRESHOLD_MMOL_5M,
            postHypoTargetMmol = prefs[KEY_POST_HYPO_TARGET_MMOL] ?: DEFAULT_POST_HYPO_TARGET_MMOL,
            postHypoDurationMinutes = prefs[KEY_POST_HYPO_DURATION_MINUTES] ?: DEFAULT_POST_HYPO_DURATION_MINUTES,
            postHypoLookbackMinutes = prefs[KEY_POST_HYPO_LOOKBACK_MINUTES] ?: DEFAULT_POST_HYPO_LOOKBACK_MINUTES,
            rulePostHypoEnabled = prefs[KEY_RULE_POST_HYPO_ENABLED] ?: true,
            rulePatternEnabled = prefs[KEY_RULE_PATTERN_ENABLED] ?: true,
            ruleSegmentEnabled = prefs[KEY_RULE_SEGMENT_ENABLED] ?: true,
            rulePostHypoPriority = prefs[KEY_RULE_POST_HYPO_PRIORITY] ?: DEFAULT_POST_HYPO_PRIORITY,
            rulePatternPriority = prefs[KEY_RULE_PATTERN_PRIORITY] ?: DEFAULT_PATTERN_PRIORITY,
            ruleSegmentPriority = prefs[KEY_RULE_SEGMENT_PRIORITY] ?: DEFAULT_SEGMENT_PRIORITY,
            rulePostHypoCooldownMinutes = prefs[KEY_RULE_POST_HYPO_COOLDOWN] ?: DEFAULT_POST_HYPO_COOLDOWN_MIN,
            rulePatternCooldownMinutes = prefs[KEY_RULE_PATTERN_COOLDOWN] ?: DEFAULT_PATTERN_COOLDOWN_MIN,
            ruleSegmentCooldownMinutes = prefs[KEY_RULE_SEGMENT_COOLDOWN] ?: DEFAULT_SEGMENT_COOLDOWN_MIN,
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
            val current = AppSettings(
                nightscoutUrl = prefs[KEY_NS_URL].orEmpty(),
                apiSecret = prefs[KEY_NS_SECRET].orEmpty(),
                cloudBaseUrl = prefs[KEY_CLOUD_URL].orEmpty(),
                openAiApiKey = prefs[KEY_OPENAI_KEY].orEmpty(),
                killSwitch = prefs[KEY_KILL_SWITCH] ?: false,
                rootExperimentalEnabled = prefs[KEY_ROOT_EXPERIMENTAL] ?: false,
                localBroadcastIngestEnabled = prefs[KEY_LOCAL_BROADCAST_INGEST] ?: true,
                strictBroadcastSenderValidation = prefs[KEY_STRICT_BROADCAST_VALIDATION] ?: false,
                baseTargetMmol = prefs[KEY_BASE_TARGET_MMOL] ?: DEFAULT_BASE_TARGET_MMOL,
                postHypoThresholdMmol = prefs[KEY_POST_HYPO_THRESHOLD_MMOL] ?: DEFAULT_POST_HYPO_THRESHOLD_MMOL,
                postHypoDeltaThresholdMmol5m = prefs[KEY_POST_HYPO_DELTA_THRESHOLD_MMOL_5M] ?: DEFAULT_POST_HYPO_DELTA_THRESHOLD_MMOL_5M,
                postHypoTargetMmol = prefs[KEY_POST_HYPO_TARGET_MMOL] ?: DEFAULT_POST_HYPO_TARGET_MMOL,
                postHypoDurationMinutes = prefs[KEY_POST_HYPO_DURATION_MINUTES] ?: DEFAULT_POST_HYPO_DURATION_MINUTES,
                postHypoLookbackMinutes = prefs[KEY_POST_HYPO_LOOKBACK_MINUTES] ?: DEFAULT_POST_HYPO_LOOKBACK_MINUTES,
                rulePostHypoEnabled = prefs[KEY_RULE_POST_HYPO_ENABLED] ?: true,
                rulePatternEnabled = prefs[KEY_RULE_PATTERN_ENABLED] ?: true,
                ruleSegmentEnabled = prefs[KEY_RULE_SEGMENT_ENABLED] ?: true,
                rulePostHypoPriority = prefs[KEY_RULE_POST_HYPO_PRIORITY] ?: DEFAULT_POST_HYPO_PRIORITY,
                rulePatternPriority = prefs[KEY_RULE_PATTERN_PRIORITY] ?: DEFAULT_PATTERN_PRIORITY,
                ruleSegmentPriority = prefs[KEY_RULE_SEGMENT_PRIORITY] ?: DEFAULT_SEGMENT_PRIORITY,
                rulePostHypoCooldownMinutes = prefs[KEY_RULE_POST_HYPO_COOLDOWN] ?: DEFAULT_POST_HYPO_COOLDOWN_MIN,
                rulePatternCooldownMinutes = prefs[KEY_RULE_PATTERN_COOLDOWN] ?: DEFAULT_PATTERN_COOLDOWN_MIN,
                ruleSegmentCooldownMinutes = prefs[KEY_RULE_SEGMENT_COOLDOWN] ?: DEFAULT_SEGMENT_COOLDOWN_MIN,
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
            prefs[KEY_BASE_TARGET_MMOL] = next.baseTargetMmol
            prefs[KEY_POST_HYPO_THRESHOLD_MMOL] = next.postHypoThresholdMmol
            prefs[KEY_POST_HYPO_DELTA_THRESHOLD_MMOL_5M] = next.postHypoDeltaThresholdMmol5m
            prefs[KEY_POST_HYPO_TARGET_MMOL] = next.postHypoTargetMmol
            prefs[KEY_POST_HYPO_DURATION_MINUTES] = next.postHypoDurationMinutes
            prefs[KEY_POST_HYPO_LOOKBACK_MINUTES] = next.postHypoLookbackMinutes
            prefs[KEY_RULE_POST_HYPO_ENABLED] = next.rulePostHypoEnabled
            prefs[KEY_RULE_PATTERN_ENABLED] = next.rulePatternEnabled
            prefs[KEY_RULE_SEGMENT_ENABLED] = next.ruleSegmentEnabled
            prefs[KEY_RULE_POST_HYPO_PRIORITY] = next.rulePostHypoPriority
            prefs[KEY_RULE_PATTERN_PRIORITY] = next.rulePatternPriority
            prefs[KEY_RULE_SEGMENT_PRIORITY] = next.ruleSegmentPriority
            prefs[KEY_RULE_POST_HYPO_COOLDOWN] = next.rulePostHypoCooldownMinutes
            prefs[KEY_RULE_PATTERN_COOLDOWN] = next.rulePatternCooldownMinutes
            prefs[KEY_RULE_SEGMENT_COOLDOWN] = next.ruleSegmentCooldownMinutes
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

    companion object {
        private val KEY_NS_URL = stringPreferencesKey("nightscout_url")
        private val KEY_NS_SECRET = stringPreferencesKey("nightscout_secret")
        private val KEY_CLOUD_URL = stringPreferencesKey("cloud_base_url")
        private val KEY_OPENAI_KEY = stringPreferencesKey("openai_api_key")
        private val KEY_KILL_SWITCH = booleanPreferencesKey("kill_switch")
        private val KEY_ROOT_EXPERIMENTAL = booleanPreferencesKey("root_experimental")
        private val KEY_LOCAL_BROADCAST_INGEST = booleanPreferencesKey("local_broadcast_ingest_enabled")
        private val KEY_STRICT_BROADCAST_VALIDATION = booleanPreferencesKey("strict_broadcast_sender_validation")
        private val KEY_BASE_TARGET_MMOL = doublePreferencesKey("base_target_mmol")
        private val KEY_POST_HYPO_THRESHOLD_MMOL = doublePreferencesKey("post_hypo_threshold_mmol")
        private val KEY_POST_HYPO_DELTA_THRESHOLD_MMOL_5M = doublePreferencesKey("post_hypo_delta_threshold_mmol_5m")
        private val KEY_POST_HYPO_TARGET_MMOL = doublePreferencesKey("post_hypo_target_mmol")
        private val KEY_POST_HYPO_DURATION_MINUTES = intPreferencesKey("post_hypo_duration_minutes")
        private val KEY_POST_HYPO_LOOKBACK_MINUTES = intPreferencesKey("post_hypo_lookback_minutes")
        private val KEY_RULE_POST_HYPO_ENABLED = booleanPreferencesKey("rule_post_hypo_enabled")
        private val KEY_RULE_PATTERN_ENABLED = booleanPreferencesKey("rule_pattern_enabled")
        private val KEY_RULE_SEGMENT_ENABLED = booleanPreferencesKey("rule_segment_enabled")
        private val KEY_RULE_POST_HYPO_PRIORITY = intPreferencesKey("rule_post_hypo_priority")
        private val KEY_RULE_PATTERN_PRIORITY = intPreferencesKey("rule_pattern_priority")
        private val KEY_RULE_SEGMENT_PRIORITY = intPreferencesKey("rule_segment_priority")
        private val KEY_RULE_POST_HYPO_COOLDOWN = intPreferencesKey("rule_post_hypo_cooldown_minutes")
        private val KEY_RULE_PATTERN_COOLDOWN = intPreferencesKey("rule_pattern_cooldown_minutes")
        private val KEY_RULE_SEGMENT_COOLDOWN = intPreferencesKey("rule_segment_cooldown_minutes")
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
        private const val DEFAULT_POST_HYPO_COOLDOWN_MIN = 30
        private const val DEFAULT_PATTERN_COOLDOWN_MIN = 60
        private const val DEFAULT_SEGMENT_COOLDOWN_MIN = 60
        private const val DEFAULT_PATTERN_MIN_SAMPLES = 40
        private const val DEFAULT_PATTERN_MIN_ACTIVE_DAYS = 7
        private const val DEFAULT_PATTERN_LOW_RATE_TRIGGER = 0.12
        private const val DEFAULT_PATTERN_HIGH_RATE_TRIGGER = 0.18
        private const val DEFAULT_ANALYTICS_LOOKBACK_DAYS = 365
        private const val DEFAULT_MAX_ACTIONS_6H = 3
        private const val DEFAULT_STALE_DATA_MAX_MINUTES = 10
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
    val baseTargetMmol: Double,
    val postHypoThresholdMmol: Double,
    val postHypoDeltaThresholdMmol5m: Double,
    val postHypoTargetMmol: Double,
    val postHypoDurationMinutes: Int,
    val postHypoLookbackMinutes: Int,
    val rulePostHypoEnabled: Boolean,
    val rulePatternEnabled: Boolean,
    val ruleSegmentEnabled: Boolean,
    val rulePostHypoPriority: Int,
    val rulePatternPriority: Int,
    val ruleSegmentPriority: Int,
    val rulePostHypoCooldownMinutes: Int,
    val rulePatternCooldownMinutes: Int,
    val ruleSegmentCooldownMinutes: Int,
    val patternMinSamplesPerWindow: Int,
    val patternMinActiveDaysPerWindow: Int,
    val patternLowRateTrigger: Double,
    val patternHighRateTrigger: Double,
    val analyticsLookbackDays: Int,
    val maxActionsIn6Hours: Int,
    val staleDataMaxMinutes: Int,
    val exportFolderUri: String?
)
