package io.aaps.copilot.data.local

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
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
class LocalNightscoutV3MigrationTest {
    @Test fun migrationPreservesClinicalDataWithoutInventingWireHistory() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "local-v3-migration.db"
        context.deleteDatabase(name)
        fun open() = Room.databaseBuilder(context, CopilotDatabase::class.java, name)
            .addMigrations(*CopilotMigrations.ALL).allowMainThreadQueries().build()
        try {
            val seed = open()
            try {
                val db = seed
                db.openHelper.writableDatabase.execSQL(
                    "INSERT INTO glucose_samples (timestamp,mmol,source,quality) VALUES (100,6.0,'sensor','OK')"
                )
                db.openHelper.writableDatabase.execSQL(
                    "INSERT INTO therapy_events (id,timestamp,type,payloadJson) VALUES ('meal-kept',90,'CARBS','{\"carbs\":20}')"
                )
            } finally { seed.close() }
            SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READWRITE).use {
                it.execSQL("DROP TABLE IF EXISTS local_ns_v3_documents")
                it.execSQL("DROP TABLE IF EXISTS local_ns_v3_clock")
                it.version = 35
            }
            val db = open()
            try {
                val sql = db.openHelper.writableDatabase
                assertEquals(36, sql.version)
                for (table in listOf("local_ns_v3_documents", "local_ns_v3_clock")) {
                    sql.query("SELECT COUNT(*) FROM $table").use {
                        assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0))
                    }
                }
                assertEquals(6.0, db.glucoseDao().since(0).single().mmol, 0.0)
                assertEquals("{\"carbs\":20}", db.therapyDao().byId("meal-kept")!!.payloadJson)
                sql.query("PRAGMA integrity_check").use {
                    assertTrue(it.moveToFirst()); assertEquals("ok", it.getString(0))
                }
            } finally { db.close() }
        } finally { context.deleteDatabase(name) }
    }
}
