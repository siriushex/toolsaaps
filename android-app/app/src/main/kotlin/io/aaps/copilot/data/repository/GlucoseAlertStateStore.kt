package io.aaps.copilot.data.repository

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class GlucoseAlertStateStore(context: Context) {

    private val dataStore = PreferenceDataStoreFactory.create(
        produceFile = { context.preferencesDataStoreFile("glucose_alert_state.preferences_pb") }
    )

    val state: Flow<GlucoseAlertRuntimeState> = dataStore.data.map(::readState)

    suspend fun update(transform: (GlucoseAlertRuntimeState) -> GlucoseAlertRuntimeState) {
        dataStore.edit { prefs ->
            val next = transform(readState(prefs))
            prefs[KEY_LAST_SOFT_ALERT_AT_TS] = next.lastSoftAlertAtTs
            prefs[KEY_LAST_STRONG_ALERT_AT_TS] = next.lastStrongAlertAtTs
            prefs[KEY_ACTIVE_ALERT_STATE] = next.activeAlertState.name
            prefs[KEY_ACTIVE_DIRECTION] = next.activeDirection?.name.orEmpty()
            prefs[KEY_LAST_NOTIFIED_RISK_KEY] = next.lastNotifiedRiskKey.orEmpty()
            prefs[KEY_PENDING_RISK_KEY] = next.pendingRiskKey.orEmpty()
            prefs[KEY_PENDING_RISK_COUNT] = next.pendingRiskCount.toLong()
            if (next.pendingEvidenceTimestamp == null) prefs.remove(KEY_PENDING_EVIDENCE_TS)
            else prefs[KEY_PENDING_EVIDENCE_TS] = next.pendingEvidenceTimestamp
            prefs[KEY_SAFE_SAMPLES_COUNT] = next.safeSamplesCount.toLong()
            prefs[KEY_SAFE_SINCE_TS] = next.safeSinceTs
            prefs[KEY_LAST_STAGE_CHANGE_TS] = next.lastStageChangeTs
            prefs[KEY_MUTED_UNTIL_TS] = next.mutedUntilTs
            prefs[KEY_ACTIVE_EPISODE_ID] = next.activeEpisodeId
            prefs[KEY_STRONG_ALERT_SEQUENCE] = next.strongAlertSequence.toLong()
        }
    }

    private fun readState(prefs: Preferences): GlucoseAlertRuntimeState {
        return GlucoseAlertRuntimeState(
            lastSoftAlertAtTs = prefs[KEY_LAST_SOFT_ALERT_AT_TS] ?: 0L,
            lastStrongAlertAtTs = prefs[KEY_LAST_STRONG_ALERT_AT_TS] ?: 0L,
            activeAlertState = GlucoseAlertState.fromRaw(prefs[KEY_ACTIVE_ALERT_STATE]),
            activeDirection = GlucoseAlertDirection.fromRaw(prefs[KEY_ACTIVE_DIRECTION]),
            lastNotifiedRiskKey = prefs[KEY_LAST_NOTIFIED_RISK_KEY]?.takeIf { it.isNotBlank() },
            pendingRiskKey = prefs[KEY_PENDING_RISK_KEY]?.takeIf { it.isNotBlank() },
            pendingRiskCount = (prefs[KEY_PENDING_RISK_COUNT] ?: 0L).toInt().coerceAtLeast(0),
            pendingEvidenceTimestamp = prefs[KEY_PENDING_EVIDENCE_TS],
            safeSamplesCount = (prefs[KEY_SAFE_SAMPLES_COUNT] ?: 0L).toInt().coerceAtLeast(0),
            safeSinceTs = prefs[KEY_SAFE_SINCE_TS] ?: 0L,
            lastStageChangeTs = prefs[KEY_LAST_STAGE_CHANGE_TS] ?: 0L,
            mutedUntilTs = prefs[KEY_MUTED_UNTIL_TS] ?: 0L,
            activeEpisodeId = prefs[KEY_ACTIVE_EPISODE_ID].orEmpty(),
            strongAlertSequence = (prefs[KEY_STRONG_ALERT_SEQUENCE] ?: 0L).toInt().coerceAtLeast(0)
        )
    }

    companion object {
        private val KEY_LAST_SOFT_ALERT_AT_TS = longPreferencesKey("last_soft_alert_at_ts")
        private val KEY_LAST_STRONG_ALERT_AT_TS = longPreferencesKey("last_strong_alert_at_ts")
        private val KEY_ACTIVE_ALERT_STATE = stringPreferencesKey("active_alert_state")
        private val KEY_ACTIVE_DIRECTION = stringPreferencesKey("active_alert_direction")
        private val KEY_LAST_NOTIFIED_RISK_KEY = stringPreferencesKey("last_notified_risk_key")
        private val KEY_PENDING_RISK_KEY = stringPreferencesKey("pending_risk_key")
        private val KEY_PENDING_RISK_COUNT = longPreferencesKey("pending_risk_count")
        private val KEY_PENDING_EVIDENCE_TS = longPreferencesKey("pending_evidence_timestamp")
        private val KEY_SAFE_SAMPLES_COUNT = longPreferencesKey("safe_samples_count")
        private val KEY_SAFE_SINCE_TS = longPreferencesKey("safe_since_ts")
        private val KEY_LAST_STAGE_CHANGE_TS = longPreferencesKey("last_stage_change_ts")
        private val KEY_MUTED_UNTIL_TS = longPreferencesKey("muted_until_ts")
        private val KEY_ACTIVE_EPISODE_ID = stringPreferencesKey("active_episode_id")
        private val KEY_STRONG_ALERT_SEQUENCE = longPreferencesKey("strong_alert_sequence")
    }
}

data class GlucoseAlertRuntimeState(
    val lastSoftAlertAtTs: Long = 0L,
    val lastStrongAlertAtTs: Long = 0L,
    val activeAlertState: GlucoseAlertState = GlucoseAlertState.NONE,
    val activeDirection: GlucoseAlertDirection? = null,
    val lastNotifiedRiskKey: String? = null,
    val pendingRiskKey: String? = null,
    val pendingRiskCount: Int = 0,
    val safeSamplesCount: Int = 0,
    val safeSinceTs: Long = 0L,
    val lastStageChangeTs: Long = 0L,
    val mutedUntilTs: Long = 0L,
    val activeEpisodeId: String = "",
    val strongAlertSequence: Int = 0,
    val pendingEvidenceTimestamp: Long? = null
)

enum class GlucoseAlertBellAction {
    MUTE_30_MINUTES,
    RESUME
}

internal fun glucoseAlertMuteExpiry(nowTs: Long, option: GlucoseAlertMuteOption): Long =
    nowTs + option.durationMs

fun glucoseAlertBellAction(mutedUntilTs: Long, nowTs: Long): GlucoseAlertBellAction =
    if (mutedUntilTs > nowTs) {
        GlucoseAlertBellAction.RESUME
    } else {
        GlucoseAlertBellAction.MUTE_30_MINUTES
    }

enum class GlucoseAlertState {
    NONE,
    WATCH_60,
    WARNING_30,
    SOFT_HIGH_RISK,
    CRITICAL_5,
    LOW_NOW;

    companion object {
        fun fromRaw(raw: String?): GlucoseAlertState {
            return when {
                raw.equals("SOFT_LOW_RISK", ignoreCase = true) -> WARNING_30
                raw.equals("STRONG_LOW", ignoreCase = true) -> LOW_NOW
                else -> entries.firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: NONE
            }
        }
    }
}

enum class GlucoseAlertDirection {
    LOW,
    HIGH;

    companion object {
        fun fromRaw(raw: String?): GlucoseAlertDirection? {
            return entries.firstOrNull { it.name.equals(raw, ignoreCase = true) }
        }
    }
}
