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
class MealStateRoomMigrationTest {
    @Test fun migrationCreatesNormalizedStateAndPreservesExistingClaims() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "meal-state-migration.db"
        context.deleteDatabase(name)
        try {
            val seed = Room.databaseBuilder(context, CopilotDatabase::class.java, name).allowMainThreadQueries().build()
            try {
                seed.openHelper.writableDatabase.execSQL("INSERT INTO meal_notification_claims " +
                    "(id,episodeId,bootId,elapsedAtMs,wallAtMs) VALUES (1,'existing','boot-a',10,20)")
                seed.openHelper.writableDatabase.execSQL("INSERT INTO meal_notification_claim_aliases VALUES ('existing',1)")
            } finally { seed.close() }
            SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READWRITE).use {
                it.dropGiColumnsForLegacyFixture()
                it.execSQL("DROP TABLE meal_state_absorption")
                it.execSQL("DROP TABLE meal_state_scenarios")
                it.execSQL("DROP TABLE meal_states")
                it.version = 28
            }
            val migrated = Room.databaseBuilder(context, CopilotDatabase::class.java, name)
                .addMigrations(*CopilotMigrations.ALL).allowMainThreadQueries().build()
            try {
                val sqlite = migrated.openHelper.writableDatabase
                assertEquals(32, sqlite.version)
                sqlite.query("SELECT episodeId FROM meal_notification_claims").use {
                    assertTrue(it.moveToFirst()); assertEquals("existing", it.getString(0)); assertFalse(it.moveToNext())
                }
                sqlite.query("SELECT alias FROM meal_notification_claim_aliases").use {
                    assertTrue(it.moveToFirst()); assertEquals("existing", it.getString(0)); assertFalse(it.moveToNext())
                }
                for (table in listOf("meal_states", "meal_state_scenarios", "meal_state_absorption")) {
                    sqlite.query("SELECT COUNT(*) FROM $table").use {
                        assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0))
                    }
                }
                sqlite.query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) }
                sqlite.query("PRAGMA integrity_check").use {
                    assertTrue(it.moveToFirst()); assertEquals("ok", it.getString(0))
                }
            } finally { migrated.close() }
        } finally { context.deleteDatabase(name) }
    }
}
