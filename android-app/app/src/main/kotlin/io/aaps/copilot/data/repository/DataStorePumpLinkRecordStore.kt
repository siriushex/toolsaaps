package io.aaps.copilot.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import io.aaps.copilot.domain.pump.PumpLinkAdapterState
import io.aaps.copilot.domain.pump.PumpLinkCondition
import io.aaps.copilot.domain.pump.PumpLinkDriverState
import io.aaps.copilot.domain.pump.PumpLinkHealthPolicy
import io.aaps.copilot.domain.pump.PumpLinkSnapshot
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first

class DataStorePumpLinkRecordStore(private val dataStore: DataStore<Preferences>) : PumpLinkRecordStore {
    override suspend fun load(): PumpLinkRecord = decode(dataStore.data.first().asMap().mapKeys { it.key.name })

    override suspend fun save(record: PumpLinkRecord) {
        val fields = encode(record)
        check(decode(fields) == record)
        dataStore.edit { preferences ->
            preferences.clear()
            fields.forEach { (name, value) ->
                when (value) {
                    is Int -> preferences[intPreferencesKey(name)] = value
                    is Long -> preferences[longPreferencesKey(name)] = value
                    is Boolean -> preferences[booleanPreferencesKey(name)] = value
                    is String -> preferences[stringPreferencesKey(name)] = value
                    else -> error("Unsupported pump monitor field")
                }
            }
        }
    }

    private fun encode(record: PumpLinkRecord): Map<String, Any> = buildMap {
        put("version", 1)
        put("hasSnapshot", record.snapshot != null)
        put("hasEpisode", record.episode != null)
        record.snapshot?.let {
            put("boot", it.bootCount)
            put("session", it.sessionStartedElapsedMs)
            put("sequence", it.sequence)
            put("sampled", it.sampledElapsedMs)
            put("supported", it.supported)
            put("adapter", it.adapterState.name)
            put("driver", it.driverState.name)
            put("intentional", it.intentionalDisconnect)
            it.lastVerifiedStatusElapsedMs?.let { response -> put("response", response) }
        }
        record.episode?.let {
            put("episodeBoot", it.bootCount)
            put("episodeStarted", it.startedElapsedMs)
            put("episodeCause", it.cause.name)
            put("episodeNotice", it.notice.name)
            it.responseAtStartElapsedMs?.let { response -> put("episodeResponse", response) }
        }
    }

    private fun decode(fields: Map<String, Any>): PumpLinkRecord {
        if (fields.isEmpty()) return PumpLinkRecord()
        try {
            require(fields.required<Int>("version") == 1)
            val snapshot = if (fields.required<Boolean>("hasSnapshot")) PumpLinkSnapshot(
                fields.required("boot"), fields.required("session"), fields.required("sequence"),
                fields.required("sampled"), fields.required("supported"),
                PumpLinkAdapterState.valueOf(fields.required("adapter")),
                PumpLinkDriverState.valueOf(fields.required("driver")), fields.required("intentional"),
                if (fields.containsKey("response")) fields.required<Long>("response") else null
            ) else null
            if (snapshot != null) require(PumpLinkHealthPolicy.accepts(null, snapshot,
                snapshot.sampledElapsedMs, snapshot.bootCount))
            val episode = if (fields.required<Boolean>("hasEpisode")) PumpLinkEpisode(
                fields.required("episodeBoot"), fields.required("episodeStarted"),
                if (fields.containsKey("episodeResponse")) fields.required<Long>("episodeResponse") else null,
                PumpLinkCondition.valueOf(fields.required("episodeCause")),
                PumpLinkNotice.valueOf(fields.required("episodeNotice"))
            ) else null
            if (episode != null) {
                require(snapshot != null && episode.bootCount >= 0 && episode.startedElapsedMs >= 0 && episode.cause.needsAttention)
                require(episode.responseAtStartElapsedMs == null || episode.responseAtStartElapsedMs in 1..episode.startedElapsedMs)
            }
            val record = PumpLinkRecord(snapshot, episode)
            require(encode(record) == fields)
            return record
        } catch (failure: IllegalArgumentException) {
            throw IOException("Invalid pump monitor state", failure)
        }
    }

    private inline fun <reified T> Map<String, Any>.required(name: String): T =
        get(name) as? T ?: throw IllegalArgumentException("Missing or invalid pump monitor field")

    companion object {
        fun create(context: Context, scope: CoroutineScope): DataStorePumpLinkRecordStore = DataStorePumpLinkRecordStore(
            PreferenceDataStoreFactory.create(scope = scope,
                produceFile = { File(context.noBackupFilesDir, "pump_link_health.preferences_pb") })
        )
    }
}
