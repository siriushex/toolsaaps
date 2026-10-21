package io.aaps.copilot.data.repository

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import io.aaps.copilot.data.local.entity.TherapyEventEntity
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class DataStoreInsulinOnsetSessionStoreTest {
    @Test fun realFileReopensCompletedAndDismissedSessionWithoutRestartingDeadline() = runBlocking {
        val file = File.createTempFile("insulin-timer-", ".preferences_pb").also { it.delete() }
        var job = SupervisorJob()
        fun open() = DataStoreInsulinOnsetSessionStore(PreferenceDataStoreFactory.create(
            scope = CoroutineScope(job + Dispatchers.IO), produceFile = { file }))
        var store = open()
        try {
            assertNull(store.load())
            val now = 10_000_000L
            val event = TherapyEventEntity("insulin-one", now, "meal_bolus", "{\"insulin\":2.2}")
            val model = AiInsulinContext("FIASP", 27.5, now, fittedProfileId = "FIASP")
            val session = retainInsulinOnsetSession(null, listOf(event), model, now)!!
            store.save(session)
            job.cancelAndJoin()
            job = SupervisorJob()
            store = open()
            assertEquals(session, store.load())
            val restored = retainInsulinOnsetSession(store.load(), listOf(event), model, now + 24 * 60 * 60_000L)!!
            assertEquals(session.timer.estimatedAtMs, restored.timer.estimatedAtMs)
            assertTrue(restored.timer.visibleAt(now + 24 * 60 * 60_000L))
            store.save(restored.copy(dismissed = true))
            job.cancelAndJoin()
            job = SupervisorJob()
            store = open()
            assertTrue(store.load()!!.dismissed)
        } finally { job.cancelAndJoin(); file.delete() }
    }

    @Test fun incompleteOrNonfiniteStateCannotCreateAClock() = runBlocking {
        val file = File.createTempFile("invalid-timer-", ".preferences_pb").also { it.delete() }
        val job = SupervisorJob()
        val data = PreferenceDataStoreFactory.create(scope = CoroutineScope(job + Dispatchers.IO), produceFile = { file })
        try {
            data.edit { it[intPreferencesKey("version")] = 1; it[doublePreferencesKey("minutes")] = Double.NaN }
            try {
                DataStoreInsulinOnsetSessionStore(data).load()
                fail("Malformed state must not be used")
            } catch (_: IOException) { }
        } finally { job.cancelAndJoin(); file.delete() }
    }
}
