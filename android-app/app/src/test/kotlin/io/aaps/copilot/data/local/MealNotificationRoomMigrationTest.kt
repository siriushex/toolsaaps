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
class MealNotificationRoomMigrationTest {
    @Test fun migrationCreatesQuotaTablesWithoutChangingMealHistory() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "meal-quota-migration.db"
        context.deleteDatabase(name)
        try {
            val seed = Room.databaseBuilder(context, CopilotDatabase::class.java, name).allowMainThreadQueries().build()
            try {
                seed.openHelper.writableDatabase.execSQL("INSERT INTO meal_profile_overrides " +
                    "(canonicalTherapyIdentity,therapyRevisionHash,profile,durationMinutes,source,revision,updatedAtMs) " +
                    "VALUES ('retained-meal','r1','MIXED',120,'COPILOT_UI',1,1)")
            } finally { seed.close() }
            SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READWRITE).use {
                it.dropGiColumnsForLegacyFixture()
                it.execSQL("DROP TABLE meal_notification_claim_aliases")
                it.execSQL("DROP TABLE meal_notification_claims")
                it.version = 27
            }
            val migrated = Room.databaseBuilder(context, CopilotDatabase::class.java, name)
                .addMigrations(*CopilotMigrations.ALL).allowMainThreadQueries().build()
            try {
                val sqlite = migrated.openHelper.writableDatabase
                assertEquals(31, sqlite.version)
                sqlite.query("SELECT canonicalTherapyIdentity,profile FROM meal_profile_overrides").use {
                    assertTrue(it.moveToFirst())
                    assertEquals("retained-meal", it.getString(0))
                    assertEquals("MIXED", it.getString(1))
                    assertFalse(it.moveToNext())
                }
                sqlite.query("SELECT COUNT(*) FROM meal_notification_claims").use {
                    assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0))
                }
                sqlite.query("PRAGMA integrity_check").use {
                    assertTrue(it.moveToFirst()); assertEquals("ok", it.getString(0))
                }
            } finally { migrated.close() }
        } finally { context.deleteDatabase(name) }
    }
}
