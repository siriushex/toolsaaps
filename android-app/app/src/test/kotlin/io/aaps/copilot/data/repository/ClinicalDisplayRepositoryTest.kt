package io.aaps.copilot.data.repository

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.gson.Gson
import io.aaps.copilot.data.local.CopilotDatabase
import io.aaps.copilot.data.local.entity.ClinicalReportEntity
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ClinicalDisplayRepositoryTest {
    private val zone = ZoneId.of("Asia/Tbilisi")
    private val end = ZonedDateTime.parse("2026-10-17T05:00:00+04:00[Asia/Tbilisi]").toInstant().toEpochMilli()
    private val day = 86_400_000L

    @Test fun readsAndNewInstancesNeverBuildOrSend() = room { db ->
        var calls = 0
        val repo = repository(db, { end + 60_000 }) { cutoff, z -> calls++; payload(cutoff, z) }
        repo.refreshIfDue(end + 60_000, zone)
        val first = repo.snapshots.first()!!
        assertEquals(end, first.local.generatedAt)
        assertEquals(first, repo.snapshots.first())
        val reopened = repository(db, { end + 120_000 }) { _, _ -> error("display read built context") }
        assertEquals(first, reopened.snapshots.first())
        reopened.refreshIfDue(end + 120_000, zone)
        assertEquals(1, calls)
        assertEquals(0, RoomAiHistorySource(db).read(4, 0, end, end + day, 64).scanned)
    }

    @Test fun concurrentInstancesReserveOnlyOneCycle() = room { db ->
        val calls = AtomicInteger()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val factory: suspend (Long, ZoneId) -> ClinicalReportPayload = { cutoff, z ->
            calls.incrementAndGet(); entered.complete(Unit); release.await(); payload(cutoff, z)
        }
        val a = repository(db, { end + 1000 }, factory)
        val b = repository(db, { end + 1000 }, factory)
        coroutineScope {
            val first = async { a.refreshIfDue(end + 1000, zone) }
            entered.await()
            b.refreshIfDue(end + 1000, zone)
            release.complete(Unit); first.await()
        }
        assertEquals(1, calls.get())
        assertNotNull(a.snapshots.first())
    }

    @Test fun failureRetainsSuccessAndNeedsExplicitLocalRetry() = room { db ->
        var now = end - day + 1000
        var fail = false
        var calls = 0
        val repo = repository(db, { now }) { cutoff, z ->
            calls++; if (fail) error("synthetic local build failure") else payload(cutoff, z)
        }
        repo.refreshIfDue(now, zone)
        val saved = repo.snapshots.first()
        now = end + 1000; fail = true
        repo.refreshIfDue(now, zone)
        assertEquals(saved, repo.snapshots.first())
        assertTrue(repo.failed.first())
        repo.refreshIfDue(now + 100, zone)
        assertEquals(2, calls)
        fail = false
        repo.refreshIfDue(now, zone, force = true)
        assertEquals(3, calls)
        assertEquals(end, repo.snapshots.first()!!.local.generatedAt)
        assertFalse(repo.failed.first())
    }

    @Test fun rollbackRetainsTheDatedSnapshotWithoutRebuilding() = room { db ->
        var now = end + 1000
        var calls = 0
        val repo = repository(db, { now }) { cutoff, z -> calls++; payload(cutoff, z) }
        repo.refreshIfDue(now, zone)
        val saved = repo.snapshots.first()!!
        now = end - day
        assertEquals(saved, repo.snapshots.first())
        repo.refreshIfDue(now, zone)
        assertEquals(saved, repo.snapshots.first())
        assertEquals(1, calls)
    }

    @Test fun failedAndPartialSourceRowsCannotReplaceSuccess() = room { db ->
        val repo = repository(db, { end + 1000 }) { cutoff, z -> payload(cutoff, z) }
        val local = source(end, zone)
        repo.recordSuccessfulSource(local)
        val saved = repo.snapshots.first()
        listOf("FAILED", "UPLOADING", "CANCELLED", "INTERRUPTED").forEach { status ->
            repo.recordSuccessfulSource(local.copy(status = status, sanitizedError = "UNKNOWN_REMOTE_OUTCOME"))
            assertEquals(saved, repo.snapshots.first())
        }
        repo.recordSuccessfulSource(local.copy(responseJson = "{}"))
        assertEquals(saved, repo.snapshots.first())
    }

    @Test fun cancellationCannotEraseTheSuccessfulSnapshot() = room { db ->
        var now = end - day + 1000
        val entered = CompletableDeferred<Unit>()
        val repo = repository(db, { now }) { cutoff, z ->
            if (cutoff == end) { entered.complete(Unit); awaitCancellation() }
            payload(cutoff, z)
        }
        repo.refreshIfDue(now, zone)
        val saved = repo.snapshots.first()
        now = end + 1000
        coroutineScope {
            val job = launch { repo.refreshIfDue(now, zone) }
            entered.await(); job.cancelAndJoin()
        }
        assertEquals(saved, repo.snapshots.first())
        assertTrue(repo.failed.first())
        assertFalse(repo.refreshing.value)
    }

    @Test fun anOlderLateCycleCannotOverwriteTheNextDay() = room { db ->
        var now = end + 1000
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val a = repository(db, { now }) { cutoff, z -> entered.complete(Unit); release.await(); payload(cutoff, z) }
        val b = repository(db, { now }) { cutoff, z -> payload(cutoff, z) }
        coroutineScope {
            val old = async { a.refreshIfDue(now, zone) }
            entered.await(); now += day
            b.refreshIfDue(now, zone)
            release.complete(Unit); old.await()
        }
        assertEquals(end + day, b.snapshots.first()!!.local.generatedAt)
    }

    @Test fun changingZoneDuringBuildDoesNotPublishTheOldCycle() = room { db ->
        var currentZone = zone
        val repo = ClinicalDisplayRepository(db, Gson(), ClinicalReportDatasetFactory { cutoff, z ->
            currentZone = ZoneId.of("UTC"); payload(cutoff, z)
        }, clock = { end + 1000 }, currentZone = { currentZone })
        repo.refreshIfDue(end + 1000, zone)
        assertNull(repo.snapshots.first())
        assertTrue(repo.failed.first())
    }

    @Test fun corruptedProjectionCannotBecomeAnAnswerOrTriggerAnAutomaticBuild() = room { db ->
        var calls = 0
        val repo = repository(db, { end + 1000 }) { cutoff, z -> calls++; payload(cutoff, z) }
        repo.refreshIfDue(end + 1000, zone)
        val original = db.telemetryDao().byId(ClinicalDisplayRepository.SNAPSHOT_KEY)!!
        db.telemetryDao().upsertAll(listOf(original.copy(valueText = "{\"version\":1}")))
        assertNull(repo.snapshots.first())
        assertTrue(repo.failed.first())
        repo.refreshIfDue(end + 1100, zone)
        assertEquals(1, calls)
        repo.refreshIfDue(end + 1000, zone, force = true)
        assertNotNull(repo.snapshots.first())
        assertEquals(2, calls)
    }

    @Test fun interruptedProcessNeedsExplicitLocalRetryAndCannotReplayAnAiRequest() = room { db ->
        val repo = repository(db, { end + 1000 }) { cutoff, z -> payload(cutoff, z) }
        repo.refreshIfDue(end + 1000, zone)
        val row = db.telemetryDao().byId(ClinicalDisplayRepository.ATTEMPT_KEY)!!
        val json = com.google.gson.JsonParser.parseString(row.valueText).asJsonObject
        json.addProperty("status", "RUNNING"); json.addProperty("owner", "previous-process")
        db.telemetryDao().upsertAll(listOf(row.copy(valueText = json.toString())))
        val saved = repo.snapshots.first()
        var calls = 0
        val restarted = repository(db, { end + day + 1000 }) { cutoff, z -> calls++; payload(cutoff, z) }
        assertTrue(restarted.failed.first())
        restarted.refreshIfDue(end + 1000, zone)
        assertEquals(0, calls)
        assertEquals(saved, restarted.snapshots.first())
        restarted.refreshIfDue(end + day + 1000, zone)
        assertEquals(1, calls)
    }

    @Test fun validCompleteCanUpgradeLocalButFailedOrMalformedResultsCannotReplaceIt() = room { db ->
        val repo = repository(db, { end + 3000 }) { cutoff, z -> payload(cutoff, z) }
        val local = source(end, zone)
        repo.recordSuccessfulSource(local)
        val report = ClinicalAdvisoryReport(ClinicalSummaryStatus.STABLE, ClinicalSummaryStatus.MIXED,
            listOf(ClinicalDataQualityFlag.COMPLETE), emptyList(),
            listOf(ClinicalSafetyObservation.NONE_IDENTIFIED), emptyList(), emptyList())
        val complete = local.copy(requestId = "complete-source", status = "COMPLETE", model = "sample-model",
            provider = "OPENAI", completedAt = end + 2000, responseJson = ClinicalReportPersistenceSerializer.response(report))
        repo.recordSuccessfulSource(complete)
        val saved = repo.snapshots.first()!!
        assertEquals(report, saved.report)
        assertEquals("{}", saved.source.coverageJson)
        assertNull(saved.source.renderedText)
        val ui = io.aaps.copilot.ui.foundation.screens.mapClinicalDisplay(saved)!!
        assertEquals("sample-model", ui.complete!!.metadata.model)
        assertNull(ui.complete.metadata.schemaName)
        listOf(complete.copy(status = "FAILED"), complete.copy(responseJson = "{}"),
            complete.copy(provider = "UNKNOWN"), complete.copy(model = null), complete.copy(completedAt = null),
            complete.copy(requestedThroughTs = end - 1)).forEach {
            repo.recordSuccessfulSource(it)
            assertEquals(saved, repo.snapshots.first())
        }
        repo.recordSuccessfulSource(local.copy(requestId = "late-local"))
        assertEquals(saved, repo.snapshots.first())
    }

    @Test fun roomCloseAndReopenRestoresOnlyTheDatedDisplayProjection() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val name = "display-reopen-test"
        context.deleteDatabase(name)
        fun open() = Room.databaseBuilder(context, CopilotDatabase::class.java, name).allowMainThreadQueries().build()
        var db = open()
        try {
            val first = repository(db, { end + 1000 }) { cutoff, z -> payload(cutoff, z) }
            first.refreshIfDue(end + 1000, zone)
            val saved = first.snapshots.first()
            db.close(); db = open()
            val reopened = repository(db, { end + 2000 }) { _, _ -> error("reopen built a live payload") }
            assertEquals(saved, reopened.snapshots.first())
            reopened.refreshIfDue(end + 2000, zone)
            assertTrue(db.clinicalReportDao().latest(8).isEmpty())
        } finally { db.close(); context.deleteDatabase(name) }
    }

    @Test fun rollingTelemetryCleanupRetainsOnlyTheTwoFixedDisplayRows() = room { db ->
        val repo = repository(db, { end + 1000 }) { cutoff, z -> payload(cutoff, z) }
        repo.refreshIfDue(end + 1000, zone)
        val original = db.telemetryDao().byId(ClinicalDisplayRepository.SNAPSHOT_KEY)!!
        db.telemetryDao().upsertAll(listOf(original.copy(id = "unrelated-old", key = "unrelated")))
        assertEquals(1, db.telemetryDao().deleteOlderThanWithReportProfileAndPhysicalActivityRetentionLimit(
            end + day, end + day, end + day, emptyList(), emptyList(), emptyList(), 100))
        assertNotNull(repo.snapshots.first())
        assertNotNull(db.telemetryDao().byId(ClinicalDisplayRepository.ATTEMPT_KEY))
    }

    private fun repository(db: CopilotDatabase, clock: () -> Long,
        factory: suspend (Long, ZoneId) -> ClinicalReportPayload) =
        ClinicalDisplayRepository(db, Gson(), ClinicalReportDatasetFactory(factory), clock, { zone })

    private fun room(test: suspend (CopilotDatabase) -> Unit) = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), CopilotDatabase::class.java)
            .allowMainThreadQueries().build()
        try { withTimeout(15_000) { test(db) } } finally { db.close() }
    }

    private fun source(at: Long, z: ZoneId): ClinicalReportEntity {
        val payload = payload(at, z); val d = payload.dataset
        val local = ClinicalLocalReport(d.summary7d, d.summary30d, payload.sha256, at, z.id, summary24h = d.summary24h)
        return ClinicalReportEntity("sample-source", "LOCAL_READY", at - 30 * day, at, payload.sha256, "{}",
            ClinicalReportPersistenceSerializer.displaySummary(local), null, null, null, "LOCAL_DISPLAY", 1, at, null, null)
    }

    private fun payload(at: Long, z: ZoneId): ClinicalReportPayload {
        fun summary(days: Int) = ClinicalPeriodSummary(days, at - days * day, at, 100.0,
            6.0, 6.0, 10.0, 1.0, 90.0, 9.0, 0.0, 0.0, 6.0, emptyList(), emptyList(),
            ClinicalDataQuality(days * 288, days * 288, 0, 5))
        val data = ClinicalReportDataset(ClinicalReportDatasetBuilder.SCHEMA_VERSION, at, z.id,
            ClinicalDetailWindow(at - day, at, emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList()),
            emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), summary(7), summary(30), summary(1))
        val json = ClinicalReportDatasetBuilder.serialize(data)
        return ClinicalReportPayload(data, json, ClinicalReportDatasetBuilder.sha256(json))
    }
}
