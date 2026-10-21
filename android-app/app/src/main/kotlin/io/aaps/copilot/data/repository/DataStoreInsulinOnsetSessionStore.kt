package io.aaps.copilot.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import io.aaps.copilot.domain.predict.InsulinActionProfileId
import io.aaps.copilot.ui.InsulinOnsetCountdown
import java.io.File
import java.io.IOException
import kotlin.math.roundToLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first

internal class DataStoreInsulinOnsetSessionStore(private val data: DataStore<Preferences>) : InsulinOnsetSessionStore {
    override suspend fun load(): InsulinOnsetSession? {
        val fields = data.data.first().asMap().mapKeys { it.key.name }
        if (fields.isEmpty()) return null
        try {
            require(fields.keys == setOf("version", "record", "recorded", "minutes", "meal", "review", "profile", "dismissed"))
            require(fields["version"] == 1)
            val recorded = fields["recorded"] as Long
            val minutes = fields["minutes"] as Double
            val timer = InsulinOnsetCountdown(fields["record"] as String, recorded,
                recorded + (minutes * 60_000).roundToLong(), minutes,
                mealAssociated = fields["meal"] as Boolean,
                validUntilMs = recorded + (minutes * 60_000).roundToLong() + 15 * 60_000L, retained = true,
                requiresReview = fields["review"] as Boolean)
            return InsulinOnsetSession(timer, fields["profile"] as String, fields["dismissed"] as Boolean)
                .also(::validate)
        } catch (failure: RuntimeException) { throw IOException("Invalid insulin countdown state", failure) }
    }

    override suspend fun save(session: InsulinOnsetSession) {
        validate(session)
        data.edit { p ->
            p.clear()
            p[intPreferencesKey("version")] = 1
            p[stringPreferencesKey("record")] = session.timer.recordKey
            p[longPreferencesKey("recorded")] = session.timer.recordedAtMs
            p[doublePreferencesKey("minutes")] = session.timer.onsetMinutes
            p[booleanPreferencesKey("meal")] = session.timer.mealAssociated
            p[booleanPreferencesKey("review")] = session.timer.requiresReview
            p[stringPreferencesKey("profile")] = session.profileId
            p[booleanPreferencesKey("dismissed")] = session.dismissed
        }
    }

    private fun validate(session: InsulinOnsetSession) {
        val timer = session.timer
        require(timer.recordKey.isNotBlank() && timer.recordKey.length <= 512)
        require(timer.recordedAtMs in 1..(Long.MAX_VALUE - 195 * 60_000L))
        require(timer.onsetMinutes.isFinite() && timer.onsetMinutes in 1.0..180.0)
        require(timer.estimatedAtMs == timer.recordedAtMs + (timer.onsetMinutes * 60_000).roundToLong())
        require(InsulinActionProfileId.entries.any { it.name == session.profileId })
    }

    companion object {
        fun create(context: Context, scope: CoroutineScope) = DataStoreInsulinOnsetSessionStore(
            PreferenceDataStoreFactory.create(scope = scope,
                produceFile = { File(context.noBackupFilesDir, "insulin_onset_session.preferences_pb") }))
    }
}
