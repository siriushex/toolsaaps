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
class LocalAlarmRoomMigrationTest {
    @Test fun migration32PreservesClinicalRowsReceiptsAndMuteAcrossRestart() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "local-alarm-migration.db"
        context.deleteDatabase(name)
        fun open(migrate: Boolean = false) = Room.databaseBuilder(context, CopilotDatabase::class.java, name)
            .apply { if (migrate) addMigrations(*CopilotMigrations.ALL) }
            .allowMainThreadQueries().build()
        try {
            val seed = open()
            try {
                val sql = seed.openHelper.writableDatabase
                sql.execSQL("INSERT INTO glucose_samples (timestamp,mmol,source,quality) VALUES (100,7.5,'source-a','GOOD')")
                sql.execSQL("INSERT INTO sync_state (source,lastSyncedTimestamp) VALUES ('source-a',100)")
                sql.execSQL("INSERT INTO alert_events (episodeId,eventType,stage,status,severity,createdAt,updatedAt,localSnapshotJson,revision,suppressionUntil) " +
                    "VALUES ('episode-a','GLUCOSE','HIGH','ACTIVE','HIGH',100,100,'{}',1,500)")
                sql.execSQL("INSERT INTO alert_delivery_receipts (receiptId,episodeId,kind,attemptedAt,result) " +
                    "VALUES ('receipt-a','episode-a','INITIAL',100,'SENT')")
            } finally { seed.close() }
            SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READWRITE).use {
                it.execSQL("DROP TABLE IF EXISTS alert_local_cycles")
                it.execSQL("DROP TABLE IF EXISTS alert_local_state")
                it.version = 31
            }
            for (migrate in listOf(true, false)) {
                val db = open(migrate)
                try {
                    val sql = db.openHelper.writableDatabase
                    assertEquals(32, sql.version)
                    sql.query("SELECT mmol FROM glucose_samples").use { assertTrue(it.moveToFirst()); assertEquals(7.5, it.getDouble(0), 0.0) }
                    sql.query("SELECT lastSyncedTimestamp FROM sync_state").use { assertTrue(it.moveToFirst()); assertEquals(100L, it.getLong(0)) }
                    sql.query("SELECT suppressionUntil,revision FROM alert_events").use {
                        assertTrue(it.moveToFirst()); assertEquals(500L, it.getLong(0)); assertEquals(1L, it.getLong(1))
                    }
                    sql.query("SELECT result FROM alert_delivery_receipts").use { assertTrue(it.moveToFirst()); assertEquals("SENT", it.getString(0)) }
                    for (table in listOf("alert_local_state", "alert_local_cycles")) {
                        sql.query("SELECT COUNT(*) FROM $table").use { assertTrue(it.moveToFirst()); assertEquals(0L, it.getLong(0)) }
                    }
                    sql.query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) }
                    sql.query("PRAGMA integrity_check").use { assertTrue(it.moveToFirst()); assertEquals("ok", it.getString(0)) }
                } finally { db.close() }
            }
        } finally { context.deleteDatabase(name) }
    }
}
