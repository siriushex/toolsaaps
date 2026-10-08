package io.aaps.copilot.data.local

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class MealReceiptRoomMigrationTest {
    @Test fun migrationPreservesOriginalInputAndCreatesEmptyReceiptJournal() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "meal-receipt-migration.db"
        context.deleteDatabase(name)
        try {
            val seed = Room.databaseBuilder(context, CopilotDatabase::class.java, name).allowMainThreadQueries().build()
            try {
                seed.openHelper.writableDatabase.execSQL("INSERT INTO meal_states " +
                    "(episodeId,recordedAtMs,minimumGrams,maximumGrams,storageRevision) VALUES ('existing',1000,20,20,0)")
            } finally { seed.close() }
            SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READWRITE).use {
                it.dropGiColumnsForLegacyFixture()
                it.execSQL("DROP TABLE meal_state_receipts")
                it.version = 29
            }
            val migrated = Room.databaseBuilder(context, CopilotDatabase::class.java, name)
                .addMigrations(*CopilotMigrations.ALL).allowMainThreadQueries().build()
            try {
                val sql = migrated.openHelper.writableDatabase
                assertEquals(32, sql.version)
                sql.query("SELECT episodeId,minimumGrams FROM meal_states").use {
                    assertTrue(it.moveToFirst()); assertEquals("existing", it.getString(0)); assertEquals(20.0, it.getDouble(1), 0.0)
                }
                sql.query("SELECT COUNT(*) FROM meal_state_receipts").use { assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0)) }
                sql.query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) }
                sql.query("PRAGMA integrity_check").use { assertTrue(it.moveToFirst()); assertEquals("ok", it.getString(0)) }
            } finally { migrated.close() }
        } finally { context.deleteDatabase(name) }
    }
}
