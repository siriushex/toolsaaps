package io.aaps.copilot.historyexport

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.aaps.copilot.data.local.CopilotDatabase
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.io.File
import java.io.StringWriter
import kotlinx.coroutines.CancellationException

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [35])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class ClinicalJsonReportTest {
    private val end = 120 * 86_400_000L
    private fun database(test: (CopilotDatabase, SQLiteDatabase) -> Unit) {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val file = File.createTempFile("clinical-history-", ".db", app.cacheDir)
        val room = Room.databaseBuilder(app, CopilotDatabase::class.java, file.absolutePath)
            .setJournalMode(androidx.room.RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING).allowMainThreadQueries().build()
        room.openHelper.writableDatabase
        try { SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { test(room, it) } }
        finally { room.close(); app.deleteDatabase(file.path) }
    }
    private fun report(db: SQLiteDatabase, period: ClinicalJsonPeriod, active: () -> Unit = {}, progress: (Long) -> Unit = {}): JsonObject {
        val output = StringWriter()
        ClinicalJsonReport().write(db, output, period, end, "Asia/Tbilisi", "test", mapOf("insulinProfileId" to "FIASP"), active, progress)
        return JsonParser.parseString(output.toString()).asJsonObject
    }

    @Test fun allFivePeriodsKeepEveryNonGlucoseRecordAndRespectHalfOpenBounds() = database { room, db ->
        val writable = room.openHelper.writableDatabase
        for (days in listOf(0, 7, 14, 30, 60, 90)) {
            writable.execSQL("INSERT INTO therapy_events(id,timestamp,type,payloadJson) VALUES(?,?,?,?)",
                arrayOf<Any>("meal-$days", end - days * 86_400_000L, "meal_bolus", "{\"carbs\":40,\"insulin\":2.2}"))
        }
        // More than historical AI/UI row limits: the export cannot silently truncate.
        writable.beginTransaction()
        try {
            repeat(1205) { writable.execSQL("INSERT INTO therapy_events(id,timestamp,type,payloadJson) VALUES(?,?,?,?)",
                arrayOf<Any>("extra-$it", end - 1, "carbs", "{\"carbs\":1}")) }
            writable.setTransactionSuccessful()
        } finally { writable.endTransaction() }
        ClinicalJsonPeriod.entries.forEachIndexed { i, period ->
            val root = report(db, period)
            val section = root.getAsJsonObject("sections").getAsJsonObject("therapy_events")
            assertEquals(1205 + i + 1, section["exportedRows"].asInt)
            assertEquals(section["exportedRows"].asInt, section.getAsJsonArray("records").size())
            assertFalse(section["truncated"].asBoolean)
            assertTrue(root["complete"].asBoolean)
            assertFalse(section.getAsJsonArray("records").any { it.asJsonObject["id"].asString == "meal-0" })
            assertEquals(ClinicalJsonSchema.sections.size, root.getAsJsonObject("sections").size())
        }
    }

    @Test fun glucoseBinsRetainExtremaQualitySourcesAndGapsWithoutInventingSamples() = database { room, db ->
        val start = end - 7 * 86_400_000L
        fun add(offset: Long, mmol: Double, source: String = "sensor", quality: String = "OK") {
            room.openHelper.writableDatabase.execSQL("INSERT INTO glucose_samples(timestamp,mmol,source,quality) VALUES(?,?,?,?)",
                arrayOf<Any>(start + offset, mmol, source, quality))
        }
        add(0, 7.0); add(60_000, 3.0); add(120_000, 11.0); add(540_000, 6.0)
        add(100_000, 4.5, quality = "SUSPECT"); add(100_000, 5.0, source = "other")
        add(3_600_000, 8.0)
        var processed = 0L
        val glucose = report(db, ClinicalJsonPeriod.DAYS_7, progress = { processed = it }).getAsJsonObject("glucose")
        assertEquals(7L, processed)
        val bins = glucose.getAsJsonArray("bins").map { it.asJsonObject }
        assertEquals(4, bins.size)
        val first = bins.first { it["source"].asString == "sensor" && it["quality"].asString == "OK" }
        assertEquals(4, first["samples"].asInt)
        assertEquals(6.75, first["meanMmol"].asDouble, 0.0001)
        assertEquals(3.0, first["minMmol"].asDouble, 0.0)
        assertEquals(11.0, first["maxMmol"].asDouble, 0.0)
        assertEquals(start + 60_000, first["minAtMs"].asLong)
        assertEquals(7, glucose.getAsJsonObject("coverage")["rawSamples"].asInt)
        assertEquals(2, glucose.getAsJsonObject("coverage")["occupiedTimeBins"].asInt)
        assertEquals(3_060_000, glucose.getAsJsonObject("coverage")["largestObservedGapMs"].asInt)
    }

    @Config(sdk = [31, 35])
    @Test fun snapshotRemainsConsistentWhenClinicalWriterAddsRecordsDuringExport() = database { room, db ->
        room.openHelper.writableDatabase.execSQL("INSERT INTO therapy_events VALUES('before',?,'carbs','{\"carbs\":20}')", arrayOf(end - 100))
        var inserted = false
        val root = withClinicalReadSnapshot(db.path) { snapshot -> report(snapshot, ClinicalJsonPeriod.FULL, active = {
            if (!inserted) {
                inserted = true
                room.openHelper.writableDatabase.execSQL("INSERT INTO therapy_events VALUES('during',?,'carbs','{\"carbs\":30}')", arrayOf(end - 50))
            }
        }) }
        assertTrue(inserted)
        assertEquals(1, root.getAsJsonObject("sections").getAsJsonObject("therapy_events")["exportedRows"].asInt)
        assertEquals(2, report(db, ClinicalJsonPeriod.FULL).getAsJsonObject("sections").getAsJsonObject("therapy_events")["exportedRows"].asInt)
    }

    @Test fun malformedJsonIsRetainedAndLegacyNegativeTimestampIsNotLostFromFull() = database { room, db ->
        for ((index, payload) in listOf("", "{carbs:42}", "{\"carbs\":42} trailing", "{\"carbs\":NaN}").withIndex()) {
            room.openHelper.writableDatabase.execSQL("INSERT INTO therapy_events VALUES(?,?,'carbs',?)",
                arrayOf<Any>("malformed-$index", -1L, payload))
        }
        val rows = report(db, ClinicalJsonPeriod.FULL).getAsJsonObject("sections")
            .getAsJsonObject("therapy_events").getAsJsonArray("records")
        assertEquals(4, rows.size())
        assertEquals(listOf("", "{carbs:42}", "{\"carbs\":42} trailing", "{\"carbs\":NaN}"),
            rows.map { it.asJsonObject["payloadJson"].asString })
        assertEquals(0, report(db, ClinicalJsonPeriod.DAYS_7).getAsJsonObject("sections")
            .getAsJsonObject("therapy_events")["exportedRows"].asInt)
    }

    @Test fun childCoverageCountsChildrenInsteadOfEpisodesAndSelectsByParentTime() = database { room, db ->
        val writable = room.openHelper.writableDatabase
        for ((id, timestamp) in listOf("selected" to end - 1, "older" to 1L)) {
            writable.execSQL("INSERT INTO meal_states VALUES(?,?,10,30,1,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL)", arrayOf<Any>(id, timestamp))
            repeat(2) { writable.execSQL("INSERT INTO meal_state_scenarios VALUES(?,?,?,'MEAL',NULL,NULL,10,30,0.5,NULL)",
                arrayOf<Any>(id, "scenario-$it", it)) }
        }
        val child = report(db, ClinicalJsonPeriod.DAYS_7).getAsJsonObject("sections").getAsJsonObject("meal_state_scenarios")
        assertEquals(4, child.getAsJsonObject("available")["retainedRowsBeforeCutoff"].asInt)
        assertEquals(2, child["exportedRows"].asInt)
        assertTrue(child.getAsJsonArray("records").all { it.asJsonObject["episodeId"].asString == "selected" })
    }

    @Test fun credentialsAndPrivateJournalsAreExcludedButClinicalPayloadIsPreserved() = database { room, db ->
        room.openHelper.writableDatabase.execSQL("INSERT INTO therapy_events VALUES('sample',?,'meal_bolus',?)", arrayOf<Any>(end - 1,
            "{\"carbs\":42.5,\"glycemicIndex\":55,\"nested\":{\"apiSecret\":\"private-marker\",\"note\":\"meal note\"}}"))
        room.openHelper.writableDatabase.execSQL("INSERT INTO action_commands VALUES('command',?,'TEMP_TARGET','{}','{}','private-dedup','SENT')", arrayOf(end - 1))
        room.openHelper.writableDatabase.execSQL("INSERT INTO telemetry_samples VALUES('credential',?,'transport','access_token',NULL,'private-metric',NULL,'OK')", arrayOf(end - 1))
        val root = report(db, ClinicalJsonPeriod.FULL)
        val text = root.toString()
        assertFalse(text.contains("private-marker")); assertFalse(text.contains("private-dedup"))
        assertFalse(text.contains("private-metric"))
        val sections = root.getAsJsonObject("sections")
        assertFalse(sections.has("aaps_v3_target_journal")); assertFalse(sections.has("audit_logs"))
        val payload = sections.getAsJsonObject("therapy_events").getAsJsonArray("records")[0].asJsonObject.getAsJsonObject("payloadJson")
        assertEquals(42.5, payload["carbs"].asDouble, 0.0)
        assertEquals(55, payload["glycemicIndex"].asInt)
        assertEquals("meal note", payload.getAsJsonObject("nested")["note"].asString)
    }

    @Test fun sixtyDaysOfMinuteSamplesStreamToTheFullExpectedBinCount() = database { room, db ->
        room.openHelper.writableDatabase.execSQL("""
            WITH RECURSIVE sequence(n) AS (SELECT 1 UNION ALL SELECT n+1 FROM sequence WHERE n<86400)
            INSERT INTO glucose_samples(timestamp,mmol,source,quality)
            SELECT ? - n*60000, 6.0+(n%3), 'sensor', 'OK' FROM sequence
        """.trimIndent(), arrayOf(end))
        val file = File.createTempFile("clinical-sixty-", ".json")
        try {
            file.bufferedWriter().use { output ->
                ClinicalJsonReport().write(db, output, ClinicalJsonPeriod.DAYS_60, end, "UTC", "test", emptyMap())
            }
            var bins = 0; var raw = 0L; var complete = false
            file.reader().use { input ->
                com.google.gson.stream.JsonReader(input).use { reader ->
                    reader.beginObject()
                    while (reader.hasNext()) when (reader.nextName()) {
                        "glucose" -> {
                            reader.beginObject()
                            while (reader.hasNext()) when (reader.nextName()) {
                                "bins" -> { reader.beginArray(); while (reader.hasNext()) { reader.skipValue(); bins++ }; reader.endArray() }
                                "coverage" -> {
                                    reader.beginObject()
                                    while (reader.hasNext()) if (reader.nextName() == "rawSamples") raw = reader.nextLong() else reader.skipValue()
                                    reader.endObject()
                                }
                                else -> reader.skipValue()
                            }
                            reader.endObject()
                        }
                        "complete" -> complete = reader.nextBoolean()
                        else -> reader.skipValue()
                    }
                    reader.endObject()
                }
            }
            assertEquals(86_400L, raw); assertEquals(8_640, bins); assertTrue(complete)
        } finally { file.delete() }
    }

    @Test fun unreviewedSchemaDoesNotProduceAnApparentlyCompleteFile() = database { room, db ->
        room.openHelper.writableDatabase.execSQL("PRAGMA user_version=38")
        val output = StringWriter()
        assertThrows(IllegalArgumentException::class.java) {
            ClinicalJsonReport().write(db, output, ClinicalJsonPeriod.FULL, end, "UTC", "test", emptyMap())
        }
        assertTrue(output.toString().isEmpty())
    }

    @Test fun everyRoomTableHasAnExplicitDisposition() = database { _, db ->
        val tables = mutableSetOf<String>()
        db.rawQuery("SELECT name FROM sqlite_master WHERE type='table'", null).use { cursor ->
            while (cursor.moveToNext()) tables += cursor.getString(0)
        }
        tables.removeAll(setOf("android_metadata", "room_master_table", "sqlite_sequence"))
        val reviewed = ClinicalJsonSchema.sections.map { it.table }.toSet() + ClinicalJsonSchema.excluded.keys + "glucose_samples"
        assertEquals("Unreviewed tables", emptySet<String>(), tables - reviewed)
        assertEquals("Missing clinical sections", emptySet<String>(), ClinicalJsonSchema.sections.map { it.table }.toSet() - tables)
    }

    @Test fun emptyHistoryIsExplicitAndCancellationNeverProducesCompleteReport() = database { _, db ->
        val root = report(db, ClinicalJsonPeriod.FULL)
        assertTrue(root.getAsJsonObject("range")["fromInclusiveMs"].isJsonNull)
        assertEquals(0, root.getAsJsonObject("glucose").getAsJsonObject("coverage")["rawSamples"].asInt)
        val output = StringWriter()
        try {
            ClinicalJsonReport().write(db, output, ClinicalJsonPeriod.DAYS_30, end, "UTC", "test", emptyMap(),
                { throw CancellationException("cancelled") })
            fail("Expected cancellation")
        } catch (_: CancellationException) { }
        assertFalse(output.toString().contains("\"complete\":true"))
    }
}
