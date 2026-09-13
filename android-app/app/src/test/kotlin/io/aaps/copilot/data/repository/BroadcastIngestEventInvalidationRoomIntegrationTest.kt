package io.aaps.copilot.data.repository

import android.content.Context
import android.content.Intent
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.google.gson.Gson
import io.aaps.copilot.data.local.CopilotDatabase
import io.aaps.copilot.domain.events.CompensationEventType
import io.aaps.copilot.domain.events.EventSource
import io.aaps.copilot.scheduler.ClinicalInputInvalidationSource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
class BroadcastIngestIntegratedClinicalRuntimeRoomTest {

    private lateinit var db: CopilotDatabase
    private val gson = Gson()

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, CopilotDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun sensorChangePersistsProjectsThenInvalidatesExactlyOnce() = runBlocking {
        val callbackSources = mutableListOf<ClinicalInputInvalidationSource>()
        val rowsObservedByCallback = mutableListOf<Int>()
        val repository = BroadcastIngestRepository(
            context = ApplicationProvider.getApplicationContext(),
            db = db,
            auditLogger = AuditLogger(db.auditLogDao(), gson, clock = { NOW }),
            onClinicalInputPersisted = { source ->
                rowsObservedByCallback += db.therapyDao().since(0L).size
                callbackSources += source
                true
            }
        )
        val sensorChange = Intent(AAPS_STATUS_ACTION)
            .putExtra("timestamp", NOW)
            .putExtra("eventType", "Sensor Change")

        val accepted = repository.ingest(sensorChange)

        assertThat(accepted.therapyImported).isEqualTo(1)
        assertThat(accepted.reactiveInvalidationRequested).isTrue()
        assertThat(rowsObservedByCallback).containsExactly(1)
        assertThat(callbackSources)
            .containsExactly(ClinicalInputInvalidationSource.BROADCAST_INGEST)

        val persisted = db.therapyDao().since(0L)
        assertThat(persisted).hasSize(1)
        assertThat(persisted.single().timestamp).isEqualTo(NOW)
        assertThat(persisted.single().type).isEqualTo("sensor_change")

        val sanitized = TherapySanitizer.toDomainEvents(persisted, gson)
        assertThat(sanitized).hasSize(1)
        val timelineRepository = EventTimelineRepository(gson)
        assertThat(timelineRepository.isAlertCauseContextTherapyEvent(sanitized.single())).isTrue()
        val timeline = timelineRepository.aggregate(
            sources = EventTimelineSources(therapyEvents = sanitized),
            nowTs = NOW
        )
        assertThat(timeline).hasSize(1)
        assertThat(timeline.single().type).isEqualTo(CompensationEventType.SENSOR_CALIBRATION)
        assertThat(timeline.single().subtype).isEqualTo("SENSOR_EVENT")
        assertThat(timeline.single().source).isEqualTo(EventSource.AAPS)
        assertThat(timeline.single().provenance).isEqualTo("therapy_events")

        val duplicate = repository.ingest(Intent(sensorChange))
        val rejected = repository.ingest(Intent())

        assertThat(duplicate.therapyImported).isEqualTo(0)
        assertThat(duplicate.reactiveInvalidationRequested).isFalse()
        assertThat(rejected.warning).isEqualTo("missing_action")
        assertThat(rejected.reactiveInvalidationRequested).isFalse()
        assertThat(db.therapyDao().since(0L)).hasSize(1)
        assertThat(rowsObservedByCallback).containsExactly(1)
        assertThat(callbackSources)
            .containsExactly(ClinicalInputInvalidationSource.BROADCAST_INGEST)
        Unit
    }

    @Test
    fun callbackFailureLeavesOutboxAndDuplicateRetriesThenAcknowledges() = runBlocking {
        var callbackAttempts = 0
        var failCallback = true
        val repository = BroadcastIngestRepository(
            context = ApplicationProvider.getApplicationContext(),
            db = db,
            auditLogger = AuditLogger(db.auditLogDao(), gson, clock = { NOW }),
            onClinicalInputPersisted = {
                callbackAttempts += 1
                if (failCallback) error("invalidation_failed")
                true
            }
        )
        val sensorChange = Intent(AAPS_STATUS_ACTION)
            .putExtra("timestamp", NOW)
            .putExtra("eventType", "Sensor Change")

        val failed = runCatching { repository.ingest(sensorChange) }

        assertThat(failed.exceptionOrNull()).hasMessageThat().isEqualTo("invalidation_failed")
        assertThat(callbackAttempts).isEqualTo(1)
        assertThat(db.therapyDao().since(0L)).hasSize(1)
        assertThat(db.syncStateDao().bySource(BROADCAST_INVALIDATION_OUTBOX_SOURCE)).isNotNull()

        failCallback = false
        val duplicate = repository.ingest(Intent(sensorChange))

        assertThat(duplicate.therapyImported).isEqualTo(0)
        assertThat(duplicate.reactiveInvalidationRequested).isTrue()
        assertThat(callbackAttempts).isAtLeast(2)
        assertThat(db.syncStateDao().bySource(BROADCAST_INVALIDATION_OUTBOX_SOURCE)).isNull()
        val attemptsAfterAcknowledgement = callbackAttempts

        val ordinaryDuplicate = repository.ingest(Intent(sensorChange))
        assertThat(ordinaryDuplicate.reactiveInvalidationRequested).isFalse()
        assertThat(callbackAttempts).isEqualTo(attemptsAfterAcknowledgement)
    }

    @Test
    fun productionCallbackFalseLeavesOutboxAndDoesNotClaimReactiveThrottle() = runBlocking {
        var callbackAttempts = 0
        var acceptInvalidation = false
        val repository = BroadcastIngestRepository(
            context = ApplicationProvider.getApplicationContext(),
            db = db,
            auditLogger = AuditLogger(db.auditLogDao(), gson, clock = { NOW }),
            onClinicalInputPersisted = {
                callbackAttempts += 1
                acceptInvalidation
            }
        )
        val sensorChange = Intent(AAPS_STATUS_ACTION)
            .putExtra("timestamp", NOW)
            .putExtra("eventType", "Sensor Change")

        val rejected = repository.ingest(sensorChange)

        assertThat(rejected.therapyImported).isEqualTo(1)
        assertThat(rejected.reactiveInvalidationRequested).isFalse()
        assertThat(callbackAttempts).isAtLeast(1)
        assertThat(db.syncStateDao().bySource(BROADCAST_INVALIDATION_OUTBOX_SOURCE)).isNotNull()
        val rejectedAttempts = callbackAttempts

        acceptInvalidation = true
        val retried = repository.ingest(Intent(sensorChange))

        assertThat(retried.therapyImported).isEqualTo(0)
        assertThat(retried.reactiveInvalidationRequested).isTrue()
        assertThat(callbackAttempts).isGreaterThan(rejectedAttempts)
        assertThat(db.syncStateDao().bySource(BROADCAST_INVALIDATION_OUTBOX_SOURCE)).isNull()
        val attemptsAfterAcknowledgement = callbackAttempts

        val ordinaryDuplicate = repository.ingest(Intent(sensorChange))
        assertThat(ordinaryDuplicate.reactiveInvalidationRequested).isFalse()
        assertThat(callbackAttempts).isEqualTo(attemptsAfterAcknowledgement)
    }

    @Test
    fun startupRetriesPendingOutboxAndAcknowledgesAfterSuccess() = runBlocking {
        db.syncStateDao().upsert(
            io.aaps.copilot.data.local.entity.SyncStateEntity(
                source = BROADCAST_INVALIDATION_OUTBOX_SOURCE,
                lastSyncedTimestamp = NOW
            )
        )
        val callback = CompletableDeferred<ClinicalInputInvalidationSource>()

        BroadcastIngestRepository(
            context = ApplicationProvider.getApplicationContext(),
            db = db,
            auditLogger = AuditLogger(db.auditLogDao(), gson, clock = { NOW }),
            onClinicalInputPersisted = { callback.complete(it) }
        )

        assertThat(withTimeout(5_000L) { callback.await() })
            .isEqualTo(ClinicalInputInvalidationSource.BROADCAST_INGEST)
        withTimeout(5_000L) {
            while (db.syncStateDao().bySource(BROADCAST_INVALIDATION_OUTBOX_SOURCE) != null) {
                kotlinx.coroutines.yield()
            }
        }
        assertThat(db.syncStateDao().bySource(BROADCAST_INVALIDATION_OUTBOX_SOURCE)).isNull()
    }

    @Test
    fun failureAfterClinicalWritesRollsBackAllRowsAndOutbox() = runBlocking {
        var callbacks = 0
        val repository = BroadcastIngestRepository(
            context = ApplicationProvider.getApplicationContext(),
            db = db,
            auditLogger = AuditLogger(db.auditLogDao(), gson, clock = { NOW }),
            onClinicalInputPersisted = {
                callbacks += 1
                true
            },
            beforeOutboxPersist = { error("mid_write_failure") }
        )
        val combined = Intent(AAPS_STATUS_ACTION)
            .putExtra("timestamp", NOW)
            .putExtra("date", NOW)
            .putExtra("sgv", 126)
            .putExtra("units", "mg/dL")
            .putExtra("eventType", "Sensor Change")
            .putExtra("iob", 0.5)

        val failed = runCatching { repository.ingest(combined) }

        assertThat(failed.exceptionOrNull()).hasMessageThat().isEqualTo("mid_write_failure")
        assertThat(db.glucoseDao().since(0L)).isEmpty()
        assertThat(db.therapyDao().since(0L)).isEmpty()
        assertThat(db.telemetryDao().since(0L)).isEmpty()
        assertThat(db.syncStateDao().bySource(BROADCAST_INVALIDATION_OUTBOX_SOURCE)).isNull()
        assertThat(callbacks).isEqualTo(0)
    }

    private companion object {
        const val NOW = 1_780_000_000_000L
        const val AAPS_STATUS_ACTION = "info.nightscout.androidaps.status"
    }
}
