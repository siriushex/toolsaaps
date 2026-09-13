package io.aaps.copilot.data.local

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.aaps.copilot.data.local.entity.PhysioContextTagEntity
import io.aaps.copilot.data.local.entity.ContextEventSyncEntity
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CopilotRoomSchemaValidationTest {

    private lateinit var database: CopilotDatabase
    private lateinit var databaseFile: File

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        databaseFile = File(context.cacheDir, "copilot-room-schema-${System.nanoTime()}.db")
        database = Room.databaseBuilder(context, CopilotDatabase::class.java, databaseFile.name)
            .addMigrations(*CopilotMigrations.ALL)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        database.close()
        databaseFile.delete()
        File(databaseFile.path + "-shm").delete()
        File(databaseFile.path + "-wal").delete()
    }

    @Test
    fun roomOpensV26SchemaWithPhysioContextDefaults() {
        val columns = mutableMapOf<String, String?>()
        database.openHelper.readableDatabase.query("PRAGMA table_info(physio_context_tags)").use { cursor ->
            val nameIndex = cursor.getColumnIndexOrThrow("name")
            val defaultIndex = cursor.getColumnIndexOrThrow("dflt_value")
            while (cursor.moveToNext()) {
                columns[cursor.getString(nameIndex)] =
                    if (cursor.isNull(defaultIndex)) null else cursor.getString(defaultIndex)
            }
        }

        assertEquals("''", columns["subtype"])
        assertEquals("''", columns["title"])
        assertEquals("'{}'", columns["attributesJson"])
        assertEquals("1", columns["revision"])
        assertEquals("0", columns["updatedAt"])
        assertEquals("'ACTIVE'", columns["status"])
    }

    @Test
    fun roomCanOpenDatabaseWithAllRegisteredMigrations() = runBlocking {
        database.physioContextTagDao().upsert(
            PhysioContextTagEntity(
                id = "room-open",
                tsStart = 1L,
                tsEnd = 2L,
                tagType = "TEST",
                severity = 0.0,
                source = "instrumentation",
                note = ""
            )
        )

        val row = database.physioContextTagDao().activeAt(1L).single()
        assertEquals("", row.subtype)
        assertEquals("ACTIVE", row.status)
    }

    @Test
    fun contextRetentionKeepsRowsWithPendingSync() = runBlocking {
        val pendingTag = PhysioContextTagEntity(
            id = "pending-context",
            tsStart = 1L,
            tsEnd = 2L,
            tagType = "STRESS",
            severity = 0.5,
            source = "USER",
            note = "pending"
        )
        val completedTag = pendingTag.copy(id = "completed-context", note = "completed")
        database.physioContextTagDao().upsertAll(listOf(pendingTag, completedTag))
        database.contextEventSyncDao().insert(
            ContextEventSyncEntity(
                syncId = "pending-sync",
                eventId = pendingTag.id,
                revision = 1L,
                operation = "CREATE",
                requestHash = "hash.payload",
                status = "PENDING",
                attemptedAt = 1L,
                completedAt = null,
                sanitizedError = "ack_timeout"
            )
        )

        database.physioContextTagDao().deleteOlderThanWithoutPendingSync(3L)

        assertEquals(pendingTag, database.physioContextTagDao().byId(pendingTag.id))
        assertEquals(null, database.physioContextTagDao().byId(completedTag.id))
    }
}
