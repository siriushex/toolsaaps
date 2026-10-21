package io.aaps.copilot.historyexport

import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.room.Room
import com.google.gson.Gson
import com.google.gson.JsonParser
import io.aaps.copilot.config.AppSettingsStore
import io.aaps.copilot.data.local.CopilotDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import org.robolectric.shadows.ShadowContentResolver
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [31, 35])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class ClinicalJsonExportStoreTest {
    private fun settings() = AppSettingsStore(object : DataStore<Preferences> {
        override val data = flowOf(emptyPreferences())
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences = error("Read-only export")
    })

    @Test fun realStoreWritesAndReadsBackACompleteDocumentWithoutNetworkOrDatabaseChanges() = runTest {
        val app = RuntimeEnvironment.getApplication()
        val dbFile = File.createTempFile("clinical-store-", ".db", app.cacheDir)
        val output = File.createTempFile("clinical-document-", ".json", app.cacheDir)
        val room = Room.databaseBuilder(app, CopilotDatabase::class.java, dbFile.path)
            .setJournalMode(androidx.room.RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING).allowMainThreadQueries().build()
        val writer = room.openHelper.writableDatabase
        writer.execSQL("INSERT INTO therapy_events VALUES('known',1,'carbs','{\"carbs\":25}')")
        val uri = Uri.parse("content://local.report.documents/output")
        ShadowContentResolver.registerProviderInternal(uri.authority, object : ContentProvider() {
            override fun onCreate() = true
            override fun getType(uri: Uri) = "application/json"
            override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
            override fun insert(uri: Uri, values: ContentValues?): Uri? = error("Unexpected insert")
            override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = error("Unexpected delete")
            override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = error("Unexpected update")
            override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor =
                ParcelFileDescriptor.open(output, ParcelFileDescriptor.parseMode(mode))
        }.apply { attachInfo(app, android.content.pm.ProviderInfo().apply { authority = uri.authority }) })
        try {
            val bytes = ClinicalJsonExportStore(app, room, settings()).export(uri, ClinicalJsonPeriod.FULL) {}
            assertEquals(output.length(), bytes)
            val report = output.reader().use { JsonParser.parseReader(it).asJsonObject }
            assertTrue(report["complete"].asBoolean)
            assertEquals(1, report.getAsJsonObject("sections").getAsJsonObject("therapy_events")["exportedRows"].asInt)
            writer.query("SELECT COUNT(*) FROM therapy_events").use { it.moveToFirst(); assertEquals(1, it.getInt(0)) }
            assertTrue(File(app.cacheDir, "clinical-json").listFiles().orEmpty().isEmpty())
        } finally { room.close(); app.deleteDatabase(dbFile.path); output.delete() }
    }

    @Test fun profileProjectionRetainsClinicalSettingsButNotPrivateConnectionFields() = runTest {
        val profile = settings().settings.first().copy(insulinProfileId = "FIASP", apiSecret = "private-value")
        val values = ClinicalJsonSchema.currentProfile(profile)
        assertEquals("FIASP", values["insulinProfileId"])
        assertEquals(profile.energyProfile, values["energyProfile"])
        assertEquals(profile.baseTargetSchedule, values["baseTargetSchedule"])
        val json = Gson().toJson(values)
        assertFalse(json.contains("private-value")); assertFalse(values.containsKey("apiSecret"))
        assertFalse(values.containsKey("nightscoutUrl"))
    }
}
