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
class AapsV3TargetJournalMigrationTest {
    @Test fun addsEmptyPrivateJournalAndPreservesClinicalAndWireRows() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "native-target-migration.db"
        context.deleteDatabase(name)
        fun open() = Room.databaseBuilder(context, CopilotDatabase::class.java, name)
            .addMigrations(*CopilotMigrations.ALL).allowMainThreadQueries().build()
        try {
            val seed = open()
            try {
                val sql = seed.openHelper.writableDatabase
                sql.execSQL("INSERT INTO glucose_samples (timestamp,mmol,source,quality) VALUES (100,6.0,'sensor','OK')")
                sql.execSQL("INSERT INTO local_ns_v3_documents VALUES ('settings','kept','owner',100,'{}',2,100,100,0,0)")
            } finally { seed.close() }
            SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READWRITE).use {
                it.execSQL("DROP TABLE aaps_v3_target_journal"); it.version = 36
            }
            val migrated = open()
            try {
                assertEquals(37, migrated.openHelper.writableDatabase.version)
                assertEquals(0L, migrated.aapsV3TargetJournalDao().count())
                assertEquals(6.0, migrated.glucoseDao().since(0).single().mmol, 0.0)
                assertEquals("{}", migrated.localNightscoutV3Dao().get("settings", "kept")!!.payloadJson)
                migrated.openHelper.readableDatabase.query("PRAGMA integrity_check").use {
                    assertTrue(it.moveToFirst()); assertEquals("ok", it.getString(0))
                }
            } finally { migrated.close() }
        } finally { context.deleteDatabase(name) }
    }
}
