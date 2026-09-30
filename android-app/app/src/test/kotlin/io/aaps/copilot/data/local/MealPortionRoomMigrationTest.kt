package io.aaps.copilot.data.local

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class MealPortionRoomMigrationTest {
    @Test fun roomValidatesMigratedSchemaAndPreservesUnknownLegacyOrigins() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "meal-portion-migration.db"
        context.deleteDatabase(name)
        try {
            // Keep all unrelated tables current; restore the two affected tables to v26.
            val seed = Room.databaseBuilder(context, CopilotDatabase::class.java, name)
                .allowMainThreadQueries().build()
            try {
                    seed.openHelper.writableDatabase.execSQL(
                        "INSERT INTO meal_profile_overrides " +
                            "(canonicalTherapyIdentity,therapyRevisionHash,profile,durationMinutes,source,revision,updatedAtMs) " +
                            "VALUES ('legacy-meal','r1','MIXED',120,'COPILOT_UI',1,1)"
                    )
            } finally {
                seed.close()
            }
            SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
                listOf("portion", "portionProvenance", "confirmedCarbsGrams").forEach {
                    db.execSQL("ALTER TABLE meal_profile_overrides DROP COLUMN $it")
                }
                listOf("portion", "portionProvenance").forEach {
                    db.execSQL("ALTER TABLE pending_meal_profile_intents DROP COLUMN $it")
                }
                db.version = 26
            }
            val migrated = Room.databaseBuilder(context, CopilotDatabase::class.java, name)
                .addMigrations(*CopilotMigrations.ALL)
                .allowMainThreadQueries().build()
            try {
                    val sqlite = migrated.openHelper.writableDatabase
                    assertThat(sqlite.version).isEqualTo(30)
                    sqlite.query("SELECT profile,portion,portionProvenance,confirmedCarbsGrams FROM meal_profile_overrides").use {
                        assertThat(it.moveToFirst()).isTrue()
                        assertThat(it.getString(0)).isEqualTo("MIXED")
                        (1..3).forEach { index -> assertThat(it.isNull(index)).isTrue() }
                        assertThat(it.moveToNext()).isFalse()
                    }
                    sqlite.query("PRAGMA integrity_check").use {
                        assertThat(it.moveToFirst()).isTrue()
                        assertThat(it.getString(0)).isEqualTo("ok")
                    }
            } finally {
                migrated.close()
            }
        } finally {
            context.deleteDatabase(name)
        }
    }
}
