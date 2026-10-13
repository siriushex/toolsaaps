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
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first

class DataStoreInfusionReminderStore(private val data: DataStore<Preferences>) : InfusionReminderStore {
    override suspend fun load(): InfusionReminderRecord {
        val fields = data.data.first().asMap().mapKeys { it.key.name }
        if (fields.isEmpty()) return InfusionReminderRecord()
        try {
            require(fields.keys == setOf("version", "generation", "occurrence", "due", "notice", "attempts", "watermark", "blocked"))
            require(fields["version"] == 1)
            return InfusionReminderRecord(fields["generation"] as String, fields["occurrence"] as Long,
                fields["due"] as Long, InfusionNotice.valueOf(fields["notice"] as String), fields["attempts"] as Int,
                fields["watermark"] as Long, fields["blocked"] as Boolean).also { it.validate() }
        } catch (failure: RuntimeException) { throw IOException("Invalid infusion reminder state", failure) }
    }

    override suspend fun save(record: InfusionReminderRecord) {
        record.validate()
        data.edit { p ->
            p.clear()
            p[intPreferencesKey("version")] = 1
            p[stringPreferencesKey("generation")] = record.generation
            p[longPreferencesKey("occurrence")] = record.occurrenceTs
            p[longPreferencesKey("due")] = record.dueTs
            p[stringPreferencesKey("notice")] = record.notice.name
            p[intPreferencesKey("attempts")] = record.attempts
            p[longPreferencesKey("watermark")] = record.attemptedOccurrenceWatermark
            p[booleanPreferencesKey("blocked")] = record.capabilityUnavailable
        }
    }
    companion object {
        fun create(context: Context, scope: CoroutineScope) = DataStoreInfusionReminderStore(
            PreferenceDataStoreFactory.create(scope = scope,
                produceFile = { File(context.noBackupFilesDir, "infusion_reminder.preferences_pb") }))
    }
}
