package io.aaps.copilot.data.local

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.aaps.copilot.data.repository.*
import kotlinx.coroutines.flow.first
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
class LocalDiaryMigrationTest {
    @Test fun additiveMigrationPreservesClinicalRowsAndDiarySurvivesRestart() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "diary-migration.db"
        context.deleteDatabase(name)
        fun open() = Room.databaseBuilder(context, CopilotDatabase::class.java, name)
            .addMigrations(*CopilotMigrations.ALL).allowMainThreadQueries().build()
        try {
            val seed = open()
            try {
                seed.openHelper.writableDatabase.execSQL("INSERT INTO glucose_samples (timestamp,mmol,source,quality) VALUES (100,6.0,'sensor','OK')")
            } finally { seed.close() }
            SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READWRITE).use {
                it.execSQL("DROP TABLE local_diary_entries")
                it.version = 34
            }
            val migrated = open()
            try {
                assertEquals(36, migrated.openHelper.writableDatabase.version)
                val store = LocalDiaryRepository(migrated) { 1000L }
                val row = (store.save(DiaryDraft("meal", kind = DiaryKind.MEAL, occurredAtMs = 100,
                    zoneId = "UTC", amount = "42")) as DiarySaveResult.Saved).entry
                store.save(row.toDiaryDraft().copy(amount = "40"))
            } finally { migrated.close() }
            val reopened = open()
            try {
                assertEquals(6.0, reopened.glucoseDao().since(0).single().mmol, 0.0)
                val store = LocalDiaryRepository(reopened)
                assertEquals(2, store.history("meal").size)
                assertEquals(40_000L, store.observe(false, 100).first().single().amountMilli)
                reopened.openHelper.writableDatabase.query("PRAGMA integrity_check").use {
                    assertTrue(it.moveToFirst()); assertEquals("ok", it.getString(0))
                }
            } finally { reopened.close() }
        } finally { context.deleteDatabase(name) }
    }
}
