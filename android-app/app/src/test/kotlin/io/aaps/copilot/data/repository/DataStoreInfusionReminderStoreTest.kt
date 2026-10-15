package io.aaps.copilot.data.repository

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import io.aaps.copilot.domain.pump.InfusionSetPolicy
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DataStoreInfusionReminderStoreTest {
    @get:Rule val folder = TemporaryFolder()
    private val start = 1_800_000_000_000L
    private val claimed = InfusionReminderRecord("infusion-$start", start, start + InfusionSetPolicy.INTERVAL_MS,
        InfusionNotice.CLAIMED, 1, start)

    @Test fun durableClaimAndWatermarkSurviveClosedScopeRestart() = runBlocking {
        val file = File(folder.root, "restart.preferences_pb")
        suspend fun open(action: suspend (DataStoreInfusionReminderStore) -> Unit) {
            val job = SupervisorJob()
            val data = PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.IO + job), produceFile = { file })
            try { action(DataStoreInfusionReminderStore(data)) } finally { job.cancelAndJoin() }
        }
        open { assertEquals(InfusionReminderRecord(), it.load()); it.save(claimed) }
        open { assertEquals(claimed, it.load()); it.save(InfusionReminderRecord(attemptedOccurrenceWatermark = start)) }
        open { assertEquals(start, it.load().attemptedOccurrenceWatermark); assertEquals("", it.load().generation) }
    }

    @Test fun knownBlockRoundtripsWithoutResettingItsAttemptBudget() = runBlocking {
        val job = SupervisorJob()
        val data = PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.IO + job),
            produceFile = { File(folder.root, "blocked.preferences_pb") })
        try {
            val store = DataStoreInfusionReminderStore(data)
            val blocked = claimed.copy(notice = InfusionNotice.BLOCKED, capabilityUnavailable = true)
            store.save(blocked)
            assertEquals(blocked, store.load())
            store.save(blocked.copy(attempts = 2))
            assertEquals(2, store.load().attempts)
        } finally { job.cancelAndJoin() }
    }

    @Test fun partialOrUnknownVersionStateFailsInsteadOfClearingOldClaims() = runBlocking {
        val job = SupervisorJob()
        val data = PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.IO + job),
            produceFile = { File(folder.root, "invalid.preferences_pb") })
        try {
            val store = DataStoreInfusionReminderStore(data)
            data.edit { it[intPreferencesKey("version")] = 99 }
            assertTrue(runCatching { store.load() }.isFailure)
            data.edit { it[intPreferencesKey("version")] = 1 }
            assertTrue(runCatching { store.load() }.isFailure)
            store.save(claimed)
            assertTrue(runCatching { store.save(claimed.copy(dueTs = start)) }.isFailure)
            assertEquals(claimed, store.load())
        } finally { job.cancelAndJoin() }
    }
}
