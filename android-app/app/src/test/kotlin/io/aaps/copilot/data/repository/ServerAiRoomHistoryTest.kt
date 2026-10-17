package io.aaps.copilot.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.aaps.copilot.data.local.CopilotDatabase
import io.aaps.copilot.data.local.entity.GlucoseSampleEntity
import io.aaps.copilot.data.local.entity.TherapyEventEntity
import io.aaps.copilot.data.local.entity.TelemetrySampleEntity
import io.aaps.copilot.data.local.entity.ForecastEntity
import io.aaps.copilot.data.local.entity.SensitivityRuntimeSnapshotEntity
import io.aaps.copilot.security.ServerAiHistoryPersistence
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class ServerAiRoomHistoryTest {
    private val now = 1_800_000_000_000L

    @Test fun coreSourceUsesOriginalTimeAndBoundedPages() = fixture { db, _ ->
        db.glucoseDao().upsertAll((1L..70).map { GlucoseSampleEntity(it, now - 1000 + it, 6.0, "aaps_broadcast", "OK") })
        val source = RoomAiHistorySource(db)
        val first = source.read(0, 0, now - 10_000, now, 32)
        assertEquals(32, first.scanned)
        assertEquals(32, first.observations.size)
        assertEquals(now - 999, first.observations.first().eventAtMs)
        assertEquals(ServerAiHistorySource.AAPS, first.observations.first().source)
        assertEquals(ServerAiGlucoseBasis.UNKNOWN, first.observations.first().context?.glucoseBasis)
        val second = source.read(0, first.afterRowId, now - 10_000, now, 32, first.afterEventAtMs)
        assertEquals(32, second.observations.size)
        assertTrue(first.observations.map { it.key }.toSet().intersect(second.observations.map { it.key }.toSet()).isEmpty())
    }

    @Test fun therapySeparatesRecordedInsulinAndRealCarbsWithoutDeliveryClaim() = fixture { db, _ ->
        db.therapyDao().upsertAll(listOf(
            TherapyEventEntity("meal-a", now - 1000, "meal_bolus", """{"grams":60,"units":2.2}"""),
            TherapyEventEntity("synthetic-a", now - 900, "carbs", """{"grams":25,"synthetic":true}"""),
            TherapyEventEntity("br-local_broadcast-meal", now - 800, "meal_bolus", """{"grams":60,"units":2.2}""")))
        val result = RoomAiHistorySource(db).read(1, 0, now - 10_000, now, 32)
        assertEquals(3, result.scanned)
        assertEquals(2, result.observations.size)
        val data = result.observations.map { it.data as ServerAiHistoryData.Therapy }
        assertEquals(2.2, data.single { it.eventType == ServerAiTherapyEvent.BOLUS }.insulinUnits!!, 0.0)
        assertEquals(60.0, data.single { it.eventType == ServerAiTherapyEvent.CARBS }.carbsGrams!!, 0.0)
        assertTrue(data.all { it.deliveryState == null })
    }

    @Test fun canonicalCarbAliasDoesNotDuplicateTheAuthoritativeMeal() = fixture { db, _ ->
        val payload = """{"aapsCarbId":42,"aapsCarbAmount":60,"aapsCarbIsValid":true,"aapsCarbClassification":"AAPS_REAL","units":2.2}"""
        db.therapyDao().upsertAll(listOf(TherapyEventEntity("aaps-carb-42", now - 1000, "carbs", payload),
            TherapyEventEntity("alias-a", now - 1000, "meal_bolus", payload)))
        val page = RoomAiHistorySource(db).read(1, 0, now - 10_000, now, 32)
        val data = page.observations.map { it.data as ServerAiHistoryData.Therapy }
        assertEquals(1, data.count { it.eventType == ServerAiTherapyEvent.CARBS })
        assertEquals(1, data.count { it.eventType == ServerAiTherapyEvent.BOLUS })
    }

    @Test fun timeKeysetSurvivesRemovalOfCursorRowAndNonMonotonicRowIds() = fixture { db, _ ->
        db.glucoseDao().upsertAll(listOf(GlucoseSampleEntity(90, now - 3000, 6.0, "sensor", "OK"),
            GlucoseSampleEntity(2, now - 2000, 7.0, "sensor", "OK"), GlucoseSampleEntity(1, now - 1000, 8.0, "sensor", "OK")))
        val source = RoomAiHistorySource(db)
        val first = source.read(0, 0, now - 10_000, now, 1)
        db.glucoseDao().deleteByIds(listOf(90))
        val next = source.read(0, first.afterRowId, now - 10_000, now, 2, first.afterEventAtMs)
        assertEquals(listOf(now - 2000, now - 1000), next.observations.map { it.eventAtMs })
    }

    @Test fun retryAfterAmbiguousUploadReusesFrozenRecordsAndCompletesOriginalPage() = fixture { db, index ->
        db.glucoseDao().upsertAll(listOf(GlucoseSampleEntity(1, now - 1000, 6.0, "sensor", "OK")))
        val pending = MemoryPending()
        val attempts = mutableListOf<List<ServerAiHistoryRecord>>()
        var failNext = true
        fun sync() = ServerAiRoomHistorySync(RoomAiHistorySource(db), index, pending, { "device-a" }, { now }, { }, { records, _ ->
            attempts += records
            if (failNext) { failNext = false; throw java.io.IOException("offline") }
        })
        try { sync().sync(1); fail("expected transport failure") } catch (_: java.io.IOException) { }
        db.glucoseDao().upsertAll(listOf(GlucoseSampleEntity(1, now - 1000, 7.0, "sensor", "OK")))
        sync().sync(1)
        assertEquals(attempts[0], attempts[1])
        assertNull(pending.value)
        assertEquals(1, index.entries().size)
        assertEquals(6.0, (attempts[1].single().data as ServerAiHistoryData.Glucose).glucoseMmol, 0.0)
    }

    @Test fun completedSweepRevisesChangedRowsAndTombstonesDeletedRows() = fixture { db, index ->
        val rows = listOf(GlucoseSampleEntity(1, now - 1000, 6.0, "sensor", "OK"),
            GlucoseSampleEntity(2, now - 900, 7.0, "sensor", "OK"))
        db.glucoseDao().upsertAll(rows)
        val sent = mutableListOf<ServerAiHistoryRecord>()
        val sync = ServerAiRoomHistorySync(RoomAiHistorySource(db), index, MemoryPending(), { "device-a" }, { now }, { }, { records, _ -> sent += records })
        assertTrue(sync.sync(64).scanFinished)
        val original = sent.associateBy { it.eventAtMs }
        db.glucoseDao().upsertAll(listOf(rows[0].copy(mmol = 8.0)))
        db.glucoseDao().deleteByIds(listOf(2))
        assertTrue(sync.sync(64).scanFinished)
        val changed = sent.last { it.eventAtMs == rows[0].timestamp }
        assertEquals(original.getValue(rows[0].timestamp).recordId, changed.recordId)
        assertEquals(2, changed.revision)
        val deleted = sent.last { it.eventAtMs == rows[1].timestamp }
        assertEquals(original.getValue(rows[1].timestamp).recordId, deleted.recordId)
        assertTrue(deleted.deleted)
        assertNull(deleted.data)
    }

    @Test fun deviceBindingChangeDoesNotReuseAnotherDevicesIndex() = fixture { db, index ->
        var device = "device-a"
        val sync = ServerAiRoomHistorySync(RoomAiHistorySource(db), index, MemoryPending(), { device }, { now }, { }, { _, _ -> })
        sync.sync(1)
        device = "device-b"
        try { sync.sync(1); fail("binding must fail closed") } catch (_: IllegalStateException) { }
    }

    @Test fun deviceChangeWhileFreezingPageCannotSendUnderNewDevice() = fixture { db, index ->
        db.glucoseDao().upsertAll(listOf(GlucoseSampleEntity(1, now - 1000, 6.0, "sensor", "OK")))
        var device = "device-a"
        val pending = MemoryPending().apply { onSave = { device = "device-b" } }
        var sends = 0
        val sync = ServerAiRoomHistorySync(RoomAiHistorySource(db), index, pending, { device }, { now }, { }, { _, expected ->
            assertEquals("device-a", expected); sends++
        })
        try { sync.sync(1); fail("binding must fail closed") } catch (_: IllegalStateException) { }
        assertEquals(0, sends)
        assertNotNull(pending.value)
        assertTrue(index.entries().isEmpty())
        device = "device-a"
        pending.onSave = null
        sync.sync(1)
        assertEquals(1, sends)
    }

    @Test fun expiredFrozenPageCannotPermanentlyBlockFreshHistory() = fixture { db, index ->
        db.glucoseDao().upsertAll(listOf(GlucoseSampleEntity(1, now - HistoryJson.retentionMs + 660_000, 6.0, "sensor", "OK")))
        val pending = MemoryPending()
        var time = now
        var offline = false
        val sent = mutableListOf<ServerAiHistoryRecord>()
        val sync = ServerAiRoomHistorySync(RoomAiHistorySource(db), index, pending, { "device-a" }, { time }, { }, { rows, _ ->
            if (offline) throw java.io.IOException("offline") else sent += rows
        })
        sync.sync(64) // Recent context first; old row belongs only to the following full scan.
        offline = true
        try { sync.sync(1); fail("expected transport failure") } catch (_: java.io.IOException) { }
        time += 720_000
        offline = false
        sync.sync(1)
        assertNull(pending.value)
        assertTrue(sent.isEmpty())
        sync.sync(64)
        db.glucoseDao().upsertAll(listOf(GlucoseSampleEntity(2, time - 1000, 7.0, "sensor", "OK")))
        sync.sync(64)
        assertEquals(time - 1000, sent.single().eventAtMs)
    }

    @Test fun unusedLargeTextAndOversizedPayloadCannotFillTheCursorWindow() = fixture { db, _ ->
        val large = "x".repeat(3_000_000)
        db.telemetryDao().upsertAll(listOf(TelemetrySampleEntity("numeric-a", now - 1000, "sensor",
            "steps_count", 100.0, large, "steps", "OK")))
        db.therapyDao().upsertAll(listOf(TherapyEventEntity("large-a", now - 1000, "carbs", large)))
        val source = RoomAiHistorySource(db)
        assertEquals(1, source.read(4, 0, now - 10_000, now, 32).observations.size)
        val omitted = source.read(1, 0, now - 10_000, now, 32)
        assertTrue(omitted.observations.isEmpty())
        assertEquals(1, omitted.omitted)
    }

    @Test fun deletionKeysetHandlesManyEqualTimeRowsWithoutGapsOrDuplicates() = fixture { db, index ->
        db.glucoseDao().upsertAll((1L..100).map { GlucoseSampleEntity(it, now - 1000, 6.0, "sensor", "OK") })
        val sent = mutableListOf<ServerAiHistoryRecord>()
        val sync = ServerAiRoomHistorySync(RoomAiHistorySource(db), index, MemoryPending(), { "device-a" }, { now }, { }, { rows, _ -> sent += rows })
        sync.sync(64)
        assertEquals(100, sent.size)
        db.glucoseDao().deleteByIds((1L..100).toList())
        sync.sync(64)
        val deleted = sent.filter { it.deleted }
        assertEquals(100, deleted.size)
        assertEquals(100, deleted.map { it.recordId }.distinct().size)
        assertTrue(deleted.all { it.revision == 2 })
    }

    @Test fun unchangedSweepsDoNotResendMeasurementsAndRecreationGetsNewIdentity() = fixture { db, index ->
        val row = GlucoseSampleEntity(1, now - 1000, 6.0, "sensor", "OK")
        db.glucoseDao().upsertAll(listOf(row))
        val sent = mutableListOf<ServerAiHistoryRecord>()
        val sync = ServerAiRoomHistorySync(RoomAiHistorySource(db), index, MemoryPending(), { "device-a" }, { now }, { }, { records, _ -> sent += records })
        sync.sync(64)
        val id = sent.single().recordId
        sync.sync(64)
        assertEquals(1, sent.size)
        db.glucoseDao().deleteByIds(listOf(1))
        sync.sync(64)
        assertTrue(sent.last().deleted)
        db.glucoseDao().upsertAll(listOf(row))
        sync.sync(64)
        assertNotEquals(id, sent.last().recordId)
        assertEquals(1, sent.last().revision)
    }

    @Test fun failedPendingWriteCannotSendAndFailedClearCannotReplayKnownCommit() = fixture { db, index ->
        db.glucoseDao().upsertAll(listOf(GlucoseSampleEntity(1, now - 1000, 6.0, "sensor", "OK")))
        val pending = MemoryPending()
        var sends = 0
        fun sync() = ServerAiRoomHistorySync(RoomAiHistorySource(db), index, pending, { "device-a" }, { now }, { }, { _, _ -> sends++ })
        pending.failSave = true
        try { sync().sync(1); fail("expected storage failure") } catch (_: java.io.IOException) { }
        assertEquals(0, sends)
        pending.failSave = false
        pending.failClear = true
        try { sync().sync(1); fail("expected clear failure") } catch (_: java.io.IOException) { }
        assertEquals(1, sends)
        pending.failClear = false
        sync().sync(1)
        assertEquals(1, sends)
        assertNull(pending.value)
    }

    @Test fun malformedEncryptedPlanFailsBeforeNetwork() = fixture { db, index ->
        val pending = MemoryPending().apply { value = """{"id":"a","id":"b"}""" }
        var network = 0
        val sync = ServerAiRoomHistorySync(RoomAiHistorySource(db), index, pending, { "device-a" }, { now }, { network++ }, { _, _ -> network++ })
        try { sync.sync(1); fail("expected invalid plan") } catch (_: IllegalStateException) { }
        assertEquals(0, network)
    }

    @Test fun telemetryRequiresKnownUnitsAndKeepsPositiveAndSignedIobSeparate() = fixture { db, _ ->
        db.telemetryDao().upsertAll(listOf(
            TelemetrySampleEntity("positive", now - 1000, "aaps_broadcast", "iob_effective_positive_units", 3.3, null, "U", "OK"),
            TelemetrySampleEntity("net", now - 1000, "aaps_broadcast", "iob_net_units", -2.2, null, "U", "OK"),
            TelemetrySampleEntity("bad-unit", now - 1000, "aaps_broadcast", "cob_grams", 60.0, null, "mg", "OK"),
            TelemetrySampleEntity("not-allowed", now - 1000, "sensor", "private_text", null, "untrusted text", null, "OK")))
        val page = RoomAiHistorySource(db).read(4, 0, now - 10_000, now, 32)
        assertEquals(3, page.scanned)
        assertEquals(1, page.omitted)
        val values = page.observations.map { it.data as ServerAiHistoryData.Sensitivity }
        assertEquals(3.3, values.single { it.positiveIobUnits != null }.positiveIobUnits!!, 0.0)
        assertEquals(-2.2, values.single { it.netIobUnits != null }.netIobUnits!!, 0.0)
    }

    @Test fun forecastAndEffectiveSensitivityKeepTheirGenerationTimesAndUnits() = fixture { db, _ ->
        db.forecastDao().insertAll(listOf(ForecastEntity(1, now - 2000, 30, 8.0, 7.0, 9.0, "model-v1")))
        db.sensitivityRuntimeSnapshotDao().upsert(SensitivityRuntimeSnapshotEntity(
            cycleId = "cycle-a", settingsRevision = 1, generatedAt = now - 1000,
            isfRequestedSource = "AAPS", isfResolvedSource = "AAPS", isfRawAaps = 2.1, isfRawEvidence = null,
            isfRawCopilot = 2.0, isfBlended = null, isfEffective = 2.1, isfConfidence = 0.8, isfFallbackReason = null,
            crRequestedSource = "AAPS", crResolvedSource = "AAPS", crRawAaps = 10.0, crRawEvidence = null,
            crRawCopilot = 11.0, crBlended = null, crEffective = 10.0, crConfidence = 0.8, crFallbackReason = null))
        val source = RoomAiHistorySource(db)
        val forecast = source.read(2, 0, now - 10_000, now, 32).observations.single()
        assertEquals(now - 2000, forecast.eventAtMs)
        assertEquals(ServerAiHistoryData.Forecast(30, 8.0, "model-v1", 7.0, 9.0), forecast.data)
        val sensitivity = source.read(3, 0, now - 10_000, now, 32).observations.single()
        assertEquals(now - 1000, sensitivity.eventAtMs)
        assertEquals(ServerAiHistoryData.Sensitivity(2.1, 10.0), sensitivity.data)
    }

    @Test fun acknowledgementFollowedByIndexFailureKeepsExactPageForRecovery() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, CopilotDatabase::class.java).allowMainThreadQueries().build()
        val journal = Room.inMemoryDatabaseBuilder(context, RoomAiExportDatabase::class.java).allowMainThreadQueries().build()
        try {
            val index = RoomAiExportIndex(journal)
            val pending = MemoryPending()
            val sent = mutableListOf<List<ServerAiHistoryRecord>>()
            db.glucoseDao().upsertAll(listOf(GlucoseSampleEntity(1, now - 1000, 6.0, "sensor", "OK")))
            journal.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_export BEFORE INSERT ON export_index " +
                "BEGIN SELECT RAISE(ABORT, 'synthetic storage failure'); END")
            fun sync() = ServerAiRoomHistorySync(RoomAiHistorySource(db), index, pending,
                { "device-a" }, { now }, { }, { records, _ -> sent += records })
            try { sync().sync(1); fail("expected index failure") } catch (_: Exception) { }
            assertEquals(1, sent.size)
            assertNotNull(pending.value)
            assertTrue(index.entries().isEmpty())
            journal.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_export")
            sync().sync(1)
            assertEquals(sent[0], sent[1])
            assertNull(pending.value)
            assertEquals(1, index.entries().size)
        } finally { journal.close(); db.close() }
    }

    @Test fun partialCoverageCannotClaimFullHistoryAndOriginalWindowIsStable() = fixture { db, index ->
        db.glucoseDao().upsertAll((1L..70).map { GlucoseSampleEntity(it, now - 1000 + it, 6.0, "sensor", "OK") })
        var time = now
        val sync = ServerAiRoomHistorySync(RoomAiHistorySource(db), index, MemoryPending(), { "device-a" }, { time }, { }, { _, _ -> })
        val first = sync.sync(1)
        time += 5000
        val second = sync.sync(1)
        assertFalse(first.scanFinished)
        assertEquals(first.throughMs, second.throughMs)
        assertTrue(second.contextSummary().contains("Not a point-in-time snapshot or complete database export"))
        assertEquals(64L, second.scannedRows)
    }

    @Test fun recentContextPrecedesYearBackfillAndOldCorrectionsWaitForDailyScan() = fixture { db, index ->
        val old = GlucoseSampleEntity(1, now - 7 * 86_400_000, 6.0, "sensor", "OK")
        val recent = GlucoseSampleEntity(2, now - 1000, 7.0, "sensor", "OK")
        db.glucoseDao().upsertAll(listOf(old, recent))
        val sent = mutableListOf<ServerAiHistoryRecord>()
        var time = now
        val sync = ServerAiRoomHistorySync(RoomAiHistorySource(db), index, MemoryPending(), { "device-a" }, { time }, { }, { records, _ -> sent += records })
        val first = sync.sync(64)
        assertTrue(first.needsBackfill)
        assertEquals(listOf(recent.timestamp), sent.map { it.eventAtMs })
        val second = sync.sync(64)
        assertFalse(second.needsBackfill)
        assertEquals(now, second.lastFullThroughMs)
        assertEquals(2, sent.size)
        db.glucoseDao().upsertAll(listOf(old.copy(mmol = 8.0)))
        sync.sync(64)
        assertEquals(2, sent.size)
        time += 86_400_000
        sync.sync(64)
        assertEquals(3, sent.size)
        assertEquals(old.timestamp, sent.last().eventAtMs)
        assertEquals(2, sent.last().revision)
    }

    @Test fun indexAndFrozenPageSurviveActualRoomCloseAndReopen() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "server-ai-export-restart-test.db"
        context.deleteDatabase(name)
        val sourceDb = Room.inMemoryDatabaseBuilder(context, CopilotDatabase::class.java).allowMainThreadQueries().build()
        fun open() = Room.databaseBuilder(context, RoomAiExportDatabase::class.java, name).allowMainThreadQueries().build()
        var journal = open()
        val pending = MemoryPending()
        val sent = mutableListOf<List<ServerAiHistoryRecord>>()
        try {
            sourceDb.glucoseDao().upsertAll(listOf(GlucoseSampleEntity(1, now - 1000, 6.0, "sensor", "OK")))
            val first = ServerAiRoomHistorySync(RoomAiHistorySource(sourceDb), RoomAiExportIndex(journal), pending,
                { "device-a" }, { now }, { }, { records, _ -> sent += records; throw java.io.IOException("offline") })
            try { first.sync(1); fail("expected transport failure") } catch (_: java.io.IOException) { }
            journal.close()
            journal = open()
            val index = RoomAiExportIndex(journal)
            val second = ServerAiRoomHistorySync(RoomAiHistorySource(sourceDb), index, pending,
                { "device-a" }, { now }, { }, { records, _ -> sent += records })
            second.sync(1)
            assertEquals(sent[0], sent[1])
            assertEquals(sent[0].single().recordId, index.entries().single().recordId)
            assertNull(pending.value)
        } finally { journal.close(); sourceDb.close(); context.deleteDatabase(name) }
    }

    private fun fixture(block: suspend (CopilotDatabase, RoomAiExportIndex) -> Unit) = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, CopilotDatabase::class.java).allowMainThreadQueries().build()
        val journal = Room.inMemoryDatabaseBuilder(context, RoomAiExportDatabase::class.java).allowMainThreadQueries().build()
        try { block(db, RoomAiExportIndex(journal)) } finally { journal.close(); db.close() }
    }

    private class MemoryPending : ServerAiHistoryPersistence {
        var value: String? = null
        var failSave = false
        var failClear = false
        var onSave: (() -> Unit)? = null
        override suspend fun read() = value
        override suspend fun write(value: String) {
            if ((value == "{}" && failClear) || (value != "{}" && failSave)) throw java.io.IOException("synthetic storage failure")
            this.value = value.takeUnless { it == "{}" }
            if (value != "{}") onSave?.invoke()
        }
    }
}
