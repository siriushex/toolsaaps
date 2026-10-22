package io.aaps.copilot.data.repository

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import io.aaps.copilot.domain.meal.MealMessageEpisode
import io.aaps.copilot.domain.meal.MealRecordRevision
import java.io.File
import java.io.IOException
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class DataStoreMealMessageEpisodeStoreTest {
    private val now = 1_700_000_000_000L
    private fun episode() = MealMessageEpisode("episode-1", MealRecordRevision("meal-12", 3, now, 35.0, false), "bolus-1", now, "r1")

    @Test fun reopenAndConcurrentPostingKeepOneAssumptionWithoutRestartingTime() = runBlocking {
        val file = File.createTempFile("meal-message-", ".preferences_pb").also { it.delete() }
        var job = SupervisorJob()
        fun open() = DataStoreMealMessageEpisodeStore(PreferenceDataStoreFactory.create(
            scope = CoroutineScope(job + Dispatchers.IO), produceFile = { file }))
        var store = open()
        try {
            assertNull(store.load())
            store.begin(episode())
            coroutineScope { (1..12).map { i -> async { store.recordPosted("episode-1", 3, now + i * 1000) } }.awaitAll() }
            val posted = store.load()!!
            assertNotNull(posted.assumedStartAtMs)
            assertFalse(posted.intakeConfirmed)
            job.cancelAndJoin()
            job = SupervisorJob()
            store = open()
            assertEquals(posted, store.load())
            store.begin(episode())
            assertEquals(posted, store.load())
            assertFalse(store.recordPosted("other", 3, now + 60_000))
            assertFalse(store.recordPosted("episode-1", 2, now + 60_000))
            store.begin(episode().reconcile(episode().meal.copy(revision = 4)))
            assertNull(store.load()!!.assumedStartAtMs)
        } finally { job.cancelAndJoin(); file.delete() }
    }

    @Test fun malformedStateDoesNotSilentlyCreateAFreshEpisode() = runBlocking {
        val file = File.createTempFile("invalid-meal-message-", ".preferences_pb").also { it.delete() }
        val job = SupervisorJob()
        val data = PreferenceDataStoreFactory.create(scope = CoroutineScope(job + Dispatchers.IO), produceFile = { file })
        try {
            data.edit { it[intPreferencesKey("version")] = 9 }
            val store = DataStoreMealMessageEpisodeStore(data)
            try { store.load(); fail("Malformed state accepted") } catch (_: IOException) { }
            try { store.begin(episode()); fail("Malformed state overwritten") } catch (_: IOException) { }
        } finally { job.cancelAndJoin(); file.delete() }
    }

    @Test fun revisedDeletedOrNewerEpisodeRejectsLatePostingAndOldBegins() = runBlocking {
        val file = File.createTempFile("revised-meal-message-", ".preferences_pb").also { it.delete() }
        val job = SupervisorJob()
        val store = DataStoreMealMessageEpisodeStore(PreferenceDataStoreFactory.create(
            scope = CoroutineScope(job + Dispatchers.IO), produceFile = { file }))
        try {
            store.begin(episode())
            val revised = episode().reconcile(episode().meal.copy(revision = 4), "r2")
            store.begin(revised)
            assertFalse(store.recordPosted("episode-1", 3, now + 1000))
            store.begin(episode())
            assertEquals(revised, store.load())
            val deleted = revised.reconcile(revised.meal.copy(revision = 5, deleted = true), "r3")
            store.begin(deleted)
            assertFalse(store.recordPosted("episode-1", 5, now + 1000))
            val newer = episode().copy(id = "episode-2", insulinRecordId = "bolus-2", insulinRecordedAtMs = now + 60_000)
            store.begin(newer)
            store.begin(episode())
            assertEquals(newer, store.load())
            assertFalse(store.recordPosted("episode-1", 3, now + 120_000))
            try { store.begin(episode().copy(id = "ambiguous", insulinRecordedAtMs = newer.insulinRecordedAtMs)); fail("Ambiguous episode accepted") }
            catch (_: IllegalArgumentException) { }
        } finally { job.cancelAndJoin(); file.delete() }
    }
}
