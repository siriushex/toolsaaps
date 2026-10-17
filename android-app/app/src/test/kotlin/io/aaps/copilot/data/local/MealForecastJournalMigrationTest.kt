package io.aaps.copilot.data.local

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.aaps.copilot.data.local.entity.MealForecastJournalEntity
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
class MealForecastJournalMigrationTest {
    @Test fun migration33PreservesClinicalDataAndJournalSurvivesRestart() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "forecast-journal-migration.db"
        context.deleteDatabase(name)
        fun open(migrate: Boolean) = Room.databaseBuilder(context, CopilotDatabase::class.java, name)
            .apply { if (migrate) addMigrations(*CopilotMigrations.ALL) }.allowMainThreadQueries().build()
        val receipt = MealForecastJournalEntity("cycle-a", 100, 110, 120, 130, 7, "digest-a",
            "sensor", "session-a", null, "Asia/Tbilisi", "baseline-v1", 6.0, "OK", "[]")
        try {
            val seed = open(false)
            seed.openHelper.writableDatabase.execSQL("INSERT INTO glucose_samples (timestamp,mmol,source,quality) VALUES (100,6.0,'sensor','OK')")
            seed.close()
            SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READWRITE).use {
                it.execSQL("DROP TABLE meal_forecast_journal")
                it.version = 32
            }
            for (migrate in listOf(true, false)) {
                val db = open(migrate)
                try {
                    assertEquals(35, db.openHelper.writableDatabase.version)
                    assertEquals(6.0, db.glucoseDao().since(0).single().mmol, 0.0)
                    if (migrate) db.mealForecastJournalDao().insert(receipt)
                    assertEquals(receipt, db.mealForecastJournalDao().between(0, 1_000, 10).single())
                    db.openHelper.writableDatabase.query("PRAGMA integrity_check").use {
                        assertTrue(it.moveToFirst()); assertEquals("ok", it.getString(0))
                    }
                } finally { db.close() }
            }
        } finally { context.deleteDatabase(name) }
    }
}
