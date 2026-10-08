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
class MealGlycemicRoomMigrationTest {
    @Test fun migration31PreservesMealAndPendingRowsAndGiSurvivesRestart() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "meal-gi-migration.db"
        context.deleteDatabase(name)
        try {
            val seed = Room.databaseBuilder(context, CopilotDatabase::class.java, name)
                .allowMainThreadQueries().build()
            try {
                val sql = seed.openHelper.writableDatabase
                sql.execSQL("INSERT INTO meal_profile_overrides " +
                    "(canonicalTherapyIdentity,therapyRevisionHash,profile,durationMinutes,source,revision,updatedAtMs) " +
                    "VALUES ('902','r1','MIXED',120,'COPILOT_UI',1,100)")
                sql.execSQL("INSERT INTO pending_meal_profile_intents " +
                    "(idempotencyKey,copilotNote,profile,durationMinutes,expectedCarbsGrams,submittedAtMs,expiresAtMs) " +
                    "VALUES ('pending-1','copilot:pending-1','FAST',45,20,100,100000)")
            } finally { seed.close() }
            SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
                for (table in listOf("meal_profile_overrides", "pending_meal_profile_intents")) {
                    val columns = mutableSetOf<String>()
                    db.rawQuery("PRAGMA table_info($table)", null).use { rows ->
                        while (rows.moveToNext()) columns += rows.getString(rows.getColumnIndexOrThrow("name"))
                    }
                    for (column in listOf("glycemicIndexValue", "glycemicIndexSource", "glycemicIndexReference")) {
                        if (column in columns) db.execSQL("ALTER TABLE $table DROP COLUMN $column")
                    }
                }
                db.version = 30
            }
            val migrated = Room.databaseBuilder(context, CopilotDatabase::class.java, name)
                .addMigrations(*CopilotMigrations.ALL).allowMainThreadQueries().build()
            try {
                val sql = migrated.openHelper.writableDatabase
                assertEquals(32, sql.version)
                sql.query("SELECT profile,durationMinutes,glycemicIndexValue,glycemicIndexSource,glycemicIndexReference FROM meal_profile_overrides").use {
                    assertTrue(it.moveToFirst())
                    assertEquals("MIXED", it.getString(0)); assertEquals(120, it.getInt(1))
                    for (index in 2..4) assertTrue(it.isNull(index))
                    assertFalse(it.moveToNext())
                }
                sql.query("SELECT expectedCarbsGrams,glycemicIndexValue,glycemicIndexSource FROM pending_meal_profile_intents").use {
                    assertTrue(it.moveToFirst()); assertEquals(20.0, it.getDouble(0), 0.0)
                    assertTrue(it.isNull(1)); assertTrue(it.isNull(2)); assertFalse(it.moveToNext())
                }
                sql.execSQL("UPDATE meal_profile_overrides SET glycemicIndexValue=80,glycemicIndexSource='USER' WHERE canonicalTherapyIdentity='902'")
            } finally { migrated.close() }
            val reopened = Room.databaseBuilder(context, CopilotDatabase::class.java, name)
                .allowMainThreadQueries().build()
            try {
                val sql = reopened.openHelper.writableDatabase
                sql.query("SELECT glycemicIndexValue,glycemicIndexSource,profile FROM meal_profile_overrides").use {
                    assertTrue(it.moveToFirst()); assertEquals(80.0, it.getDouble(0), 0.0)
                    assertEquals("USER", it.getString(1)); assertEquals("MIXED", it.getString(2))
                }
                sql.query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) }
                sql.query("PRAGMA integrity_check").use { assertTrue(it.moveToFirst()); assertEquals("ok", it.getString(0)) }
            } finally { reopened.close() }
        } finally { context.deleteDatabase(name) }
    }
}
