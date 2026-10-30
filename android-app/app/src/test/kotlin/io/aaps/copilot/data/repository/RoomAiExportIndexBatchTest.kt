package io.aaps.copilot.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.aaps.copilot.data.local.CopilotDatabase
import io.aaps.copilot.data.local.entity.GlucoseSampleEntity
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
class RoomAiExportIndexBatchTest {
    @Test fun reconciliationUsesOneIndexQueryPerNonemptySourcePage() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val selects = mutableListOf<String>()
        val source = Room.inMemoryDatabaseBuilder(context, CopilotDatabase::class.java).allowMainThreadQueries().build()
        val journal = Room.inMemoryDatabaseBuilder(context, RoomAiExportDatabase::class.java)
            .allowMainThreadQueries().setQueryCallback({ sql, _ ->
                if (sql.startsWith("SELECT * FROM export_index WHERE `key`")) selects += sql
            }, { it.run() }).build()
        try {
            val now = 1_800_000_000_000L
            source.glucoseDao().upsertAll((1L..70).map { GlucoseSampleEntity(it, now - 1000 + it, 6.0, "sensor", "OK") })
            var stored: String? = null
            val pending = object : ServerAiHistoryPersistence {
                override suspend fun read() = stored
                override suspend fun write(value: String) { stored = value.takeUnless { it == "{}" } }
            }
            val sent = mutableListOf<ServerAiHistoryRecord>()
            val sync = ServerAiRoomHistorySync(RoomAiHistorySource(source), RoomAiExportIndex(journal), pending,
                { "device-a" }, { now }, {}, { records, _ -> sent += records })
            assertTrue(sync.sync(64).scanFinished)
            assertEquals(70, sent.size)
            assertEquals(3, selects.size)
            selects.clear()
            assertTrue(sync.sync(64).scanFinished)
            assertEquals(70, sent.size)
            assertEquals(3, selects.size)
            assertTrue(selects.all { it.contains(" IN (") })
        } finally { journal.close(); source.close() }
    }

    @Test fun readsOneBoundedPageIncludingTombstonesWithoutPerRowQueries() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val selects = mutableListOf<String>()
        val db = Room.inMemoryDatabaseBuilder(context, RoomAiExportDatabase::class.java)
            .allowMainThreadQueries().setQueryCallback({ sql, _ ->
                if (sql.startsWith("SELECT * FROM export_index")) selects += sql
            }, { it.run() }).build()
        try {
            val rows = (0..63).map { n -> RoomAiExportEntry("key-$n", "record-$n", n + 1,
                n.toLong(), "GLUCOSE", "AAPS", "OK", "fingerprint-$n", "run-a", n % 2 == 0) }
            db.exportDao().saveEntries(rows)
            val index = RoomAiExportIndex(db)
            val actual = index.findAll(rows.reversed().map { it.key })
            assertEquals(rows.associateBy { it.key }, actual)
            assertEquals(1, selects.size)
            assertTrue(selects.single().contains(" IN ("))
            assertTrue(index.findAll(emptyList()).isEmpty())
            assertEquals(1, selects.size)
            assertEquals(mapOf(rows[0].key to rows[0]), index.findAll(listOf(rows[0].key, "absent", rows[0].key)))
            assertEquals(2, selects.size)
            try { index.findAll(List(65) { "key-$it" }); fail("page must be bounded") }
            catch (_: IllegalArgumentException) { }
            assertEquals(2, selects.size)
        } finally { db.close() }
    }
}
