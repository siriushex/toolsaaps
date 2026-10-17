package io.aaps.copilot.data.repository

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.gson.Gson
import io.aaps.copilot.data.local.CopilotDatabase
import io.aaps.copilot.data.local.entity.GlucoseSampleEntity
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class NightlyAdviceRoomTest {
    @Test fun actualRoomSnapshotIsObservableBoundedAndNotExportedAsTelemetry() = runTest {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), CopilotDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            val zone = ZoneId.of("Asia/Tbilisi")
            val end = ZonedDateTime.parse("2026-10-17T05:00:00+04:00[Asia/Tbilisi]").toInstant().toEpochMilli()
            db.glucoseDao().upsertAll((0..864).map {
                GlucoseSampleEntity(timestamp = end - 3 * 86_400_000L + it * 300_000L, mmol = 6.0, source = "xdrip", quality = "OK")
            })
            val repo = NightlyAdviceRepository(db, Gson())
            val first = repo.refreshIfDue(end + 1, zone)
            assertTrue(first.windows.first().usable)
            assertEquals(first, repo.snapshots.first())
            assertEquals(first, NightlyAdviceRepository(db, Gson()).refreshIfDue(end + 60_000, zone))
            repo.refreshIfDue(end + 86_400_000L, zone)
            assertEquals(1, db.telemetryDao().observeLatest(10).first().size)
            assertEquals(0, RoomAiHistorySource(db).read(4, 0, end, end + 86_400_001L, 64).scanned)
        } finally { db.close() }
    }
}
