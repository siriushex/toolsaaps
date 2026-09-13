package io.aaps.copilot.data.repository

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.google.common.truth.Truth.assertThat
import io.aaps.copilot.domain.pump.PumpLinkAdapterState
import io.aaps.copilot.domain.pump.PumpLinkCondition
import io.aaps.copilot.domain.pump.PumpLinkDriverState
import io.aaps.copilot.domain.pump.PumpLinkSnapshot
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DataStorePumpLinkRecordStoreTest {
    @get:Rule val temporary = TemporaryFolder()
    private val snapshot = PumpLinkSnapshot(12, 1_000, 3, 3_000_000, true,
        PumpLinkAdapterState.ON, PumpLinkDriverState.ERROR, false, 2_000_000)

    @Test fun persistedFaultAndMuteSurviveStoreRecreationTogether() = runBlocking {
        val job = SupervisorJob()
        val data = PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.IO + job),
            produceFile = { File(temporary.root, "pump.preferences_pb") })
        try {
            val first = DataStorePumpLinkRecordStore(data)
            assertThat(first.load()).isEqualTo(PumpLinkRecord())
            val record = PumpLinkRecord(snapshot, PumpLinkEpisode(12, 3_000_000, 2_000_000,
                PumpLinkCondition.DRIVER_ERROR, PumpLinkNotice.SUPPRESSED))
            first.save(record)
            assertThat(DataStorePumpLinkRecordStore(data).load()).isEqualTo(record)
            first.save(PumpLinkRecord(snapshot.copy(lastVerifiedStatusElapsedMs = null)))
            assertThat(first.load()).isEqualTo(PumpLinkRecord(snapshot.copy(lastVerifiedStatusElapsedMs = null)))
        } finally { job.cancelAndJoin() }
    }

    @Test fun partialOrFutureStateFailsInsteadOfDiscardingPriorDelivery() = runBlocking {
        val job = SupervisorJob()
        val data = PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.IO + job),
            produceFile = { File(temporary.root, "pump.preferences_pb") })
        try {
            val store = DataStorePumpLinkRecordStore(data)
            data.edit { it[stringPreferencesKey("episodeNotice")] = "SUPPRESSED" }
            assertThat(runCatching { store.load() }.isFailure).isTrue()
            assertThat(data.data.first()[stringPreferencesKey("episodeNotice")]).isEqualTo("SUPPRESSED")
            data.edit { it.clear(); it[intPreferencesKey("version")] = 99 }
            assertThat(runCatching { store.load() }.isFailure).isTrue()
        } finally { job.cancelAndJoin() }
    }

    @Test fun durableEpisodeLoadsAfterTheOriginalDataStoreScopeHasStopped() = runBlocking {
        val file = File(temporary.root, "restart.preferences_pb")
        val expected = PumpLinkRecord(snapshot, PumpLinkEpisode(12, 3_000_000, 2_000_000,
            PumpLinkCondition.DRIVER_ERROR, PumpLinkNotice.CLAIMED))
        suspend fun opened(action: suspend (DataStorePumpLinkRecordStore) -> Unit) {
            val job = SupervisorJob()
            val data = PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.IO + job), produceFile = { file })
            try { action(DataStorePumpLinkRecordStore(data)) } finally { job.cancelAndJoin() }
        }
        opened { it.save(expected) }
        assertThat(file.length()).isGreaterThan(0L)
        opened { assertThat(it.load()).isEqualTo(expected) }
    }
}
