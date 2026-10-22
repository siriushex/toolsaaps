package io.aaps.copilot.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import io.aaps.copilot.domain.meal.MealMessageEpisode
import io.aaps.copilot.domain.meal.MealRecordRevision
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first

/** Private research journal, not a notification sender or an additional timer owner. */
internal class DataStoreMealMessageEpisodeStore(private val data: DataStore<Preferences>) {
    suspend fun load(): MealMessageEpisode? = decode(data.data.first())

    suspend fun begin(episode: MealMessageEpisode) {
        require(episode.messagePostedAtMs == null)
        data.edit { preferences ->
            val current = decode(preferences)
            val next = when {
                current == null -> episode
                current.id == episode.id -> {
                    require(current.insulinRecordId == episode.insulinRecordId &&
                        current.insulinRecordedAtMs == episode.insulinRecordedAtMs)
                    current.reconcile(episode.meal, episode.canonicalTherapyRevision)
                }
                episode.insulinRecordedAtMs < current.insulinRecordedAtMs -> current
                else -> {
                    require(episode.insulinRecordedAtMs > current.insulinRecordedAtMs) { "Ambiguous episode replacement" }
                    episode
                }
            }
            encode(preferences, next)
        }
    }

    /** Call only after a successful posting. This is not notification admission. */
    suspend fun recordPosted(episodeId: String, expectedMealRevision: Long, atMs: Long): Boolean {
        var matched = false
        data.edit { preferences ->
            val current = decode(preferences)
            if (current != null && current.id == episodeId && current.meal.revision == expectedMealRevision && !current.meal.deleted) {
                encode(preferences, current.recordPosted(atMs))
                matched = true
            }
        }
        return matched
    }

    private fun decode(preferences: Preferences): MealMessageEpisode? {
        val fields = preferences.asMap().mapKeys { it.key.name }
        if (fields.isEmpty()) return null
        try {
            val required = setOf("version", "episode", "meal", "revision", "source_revision", "recorded", "grams", "deleted", "insulin", "insulin_recorded")
            require(fields.keys == required || fields.keys == required + "posted")
            require(fields["version"] == 1)
            return MealMessageEpisode(fields["episode"] as String,
                MealRecordRevision(fields["meal"] as String, fields["revision"] as Long,
                    fields["recorded"] as Long, fields["grams"] as Double, fields["deleted"] as Boolean),
                fields["insulin"] as String, fields["insulin_recorded"] as Long,
                fields["source_revision"] as String, fields["posted"] as Long?)
        } catch (failure: RuntimeException) { throw IOException("Invalid meal message episode", failure) }
    }

    private fun encode(p: MutablePreferences, episode: MealMessageEpisode) {
        p.clear()
        p[intPreferencesKey("version")] = 1
        p[stringPreferencesKey("episode")] = episode.id
        p[stringPreferencesKey("meal")] = episode.meal.canonicalId
        p[longPreferencesKey("revision")] = episode.meal.revision
        p[stringPreferencesKey("source_revision")] = episode.canonicalTherapyRevision
        p[longPreferencesKey("recorded")] = episode.meal.recordedAtMs
        p[doublePreferencesKey("grams")] = episode.meal.grams
        p[booleanPreferencesKey("deleted")] = episode.meal.deleted
        p[stringPreferencesKey("insulin")] = episode.insulinRecordId
        p[longPreferencesKey("insulin_recorded")] = episode.insulinRecordedAtMs
        episode.messagePostedAtMs?.let { p[longPreferencesKey("posted")] = it }
    }

    companion object {
        fun create(context: Context, scope: CoroutineScope) = DataStoreMealMessageEpisodeStore(
            PreferenceDataStoreFactory.create(scope = scope,
                produceFile = { File(context.noBackupFilesDir, "meal_message_episode.preferences_pb") }))
    }
}
