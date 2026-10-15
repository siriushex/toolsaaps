package io.aaps.copilot.data.repository

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.google.gson.Gson
import io.aaps.copilot.config.AppSettingsStore
import io.aaps.copilot.data.local.CopilotDatabase
import io.aaps.copilot.data.local.entity.TherapyEventEntity
import io.aaps.copilot.service.ApiFactory
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
class SyncRepositoryCausalHistoryRoomTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private lateinit var db: CopilotDatabase
    private lateinit var dataStoreScope: CoroutineScope
    private lateinit var repository: SyncRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, CopilotDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dataStoreScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val settingsFile = File(temporaryFolder.newFolder(), "settings.preferences_pb")
        val settingsStore = AppSettingsStore(
            PreferenceDataStoreFactory.create(
                scope = dataStoreScope,
                produceFile = { settingsFile }
            ),
            "causal-history-test"
        )
        val gson = Gson()
        repository = SyncRepository(
            context, db, settingsStore, ApiFactory(), gson, AuditLogger(db.auditLogDao(), gson)
        )
    }

    @After
    fun tearDown() {
        dataStoreScope.cancel()
        db.close()
    }

    @Test
    fun recentTherapyDoesNotIncludeFutureRows() = runBlocking {
        val now = System.currentTimeMillis()
        db.therapyDao().upsertAll(
            listOf(event("past", now - MINUTE_MS), event("future", now + HOUR_MS))
        )

        val events = repository.recentTherapyEvents(hoursBack = 24, nowTs = now)

        assertThat(events.map { it.ts }).containsExactly(now - MINUTE_MS)
        assertThat(db.therapyDao().byId("future")).isNotNull()
    }

    @Test
    fun rowsArrivingAfterFrozenCycleBecomeVisibleOnlyInTheNextCycle() = runBlocking {
        val cycleNow = System.currentTimeMillis() - 2 * MINUTE_MS
        db.therapyDao().upsertAll(listOf(event("causal", cycleNow)))
        db.therapyDao().upsertAll(listOf(event("new-arrival", cycleNow + 1L)))

        assertThat(repository.recentTherapyEvents(hoursBack = 24, nowTs = cycleNow).map { it.ts })
            .containsExactly(cycleNow)
        assertThat(repository.recentTherapyEvents(hoursBack = 24, nowTs = cycleNow + 1L).map { it.ts })
            .containsExactly(cycleNow, cycleNow + 1L).inOrder()
        assertThat(db.therapyDao().byId("new-arrival")).isNotNull()
    }

    @Test
    fun frozenLookbackIncludesBothBoundariesButNotOutsideRows() = runBlocking {
        val cycleNow = System.currentTimeMillis() - 2 * MINUTE_MS
        val since = cycleNow - 24 * HOUR_MS
        db.therapyDao().upsertAll(
            listOf(
                event("too-old", since - 1L), event("lower", since),
                event("upper", cycleNow), event("too-new", cycleNow + 1L)
            )
        )

        val events = repository.recentTherapyEvents(hoursBack = 24, nowTs = cycleNow)

        assertThat(events.map { it.ts }).containsExactly(since, cycleNow).inOrder()
        assertThat(db.therapyDao().since(since - 1L)).hasSize(4)
    }

    @Test
    fun boundedHistoryRetainsExistingSanitizerRules() = runBlocking {
        val now = System.currentTimeMillis()
        db.therapyDao().upsertAll(
            listOf(
                event("valid", now), event("br-local_broadcast-artifact", now),
                event("invalid", now).copy(payloadJson = """{"carbs":0.0}"""),
                event("malformed", now).copy(payloadJson = "{")
            )
        )

        assertThat(repository.recentTherapyEvents(hoursBack = 24, nowTs = now).map { it.ts })
            .containsExactly(now)
        assertThat(db.therapyDao().between(now, now)).hasSize(4)
    }

    @Test
    fun automationBindsBothTherapyHistoriesToItsFrozenCycleClock() {
        val source = File(
            "src/main/kotlin/io/aaps/copilot/data/repository/AutomationRepository.kt"
        ).readText().substringAfter("private suspend fun prepareCalibrationRuntimeContext(")
            .substringBefore("private data class CalibrationRuntimeContext(")

        assertThat(source).contains("recentTherapyEvents(hoursBack = 24, nowTs = nowTs)")
        assertThat(source).contains("therapyDao().between(nowTs - SENSOR_LAG_HISTORY_LOOKBACK_MS, nowTs)")
        assertThat(source).doesNotContain("therapyDao().since(")
    }

    private fun event(id: String, timestamp: Long) = TherapyEventEntity(
        id = id,
        timestamp = timestamp,
        type = "carbs",
        payloadJson = """{"carbs":10.0}"""
    )

    companion object {
        private const val MINUTE_MS = 60_000L
        private const val HOUR_MS = 60 * MINUTE_MS
    }
}
