package io.aaps.copilot.service

import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import io.aaps.copilot.data.local.CopilotDatabase
import io.aaps.copilot.data.local.entity.LocalNightscoutV3DocumentEntity
import io.aaps.copilot.domain.predict.CircadianPatternConfig
import io.aaps.copilot.domain.predict.CircadianPatternEngine
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
class AapsObservedCircadianTest {
    private val start = LocalDate.of(2026, 1, 12).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
    private val now = start + 7 * 86_400_000L
    private fun row(minute: Int, sensor: String = "sample"): LocalNightscoutV3DocumentEntity {
        val ts = start + minute * 60_000L
        val value = if (minute % 1440 in 540..1100) 144 else 90
        val json = """{"app":"AAPS-local","date":$ts,"type":"sgv","device":"$sensor","sgv":$value,"units":"mg/dl"}"""
        return LocalNightscoutV3DocumentEntity("entries", "sample-$minute", "local-v3-owner", ts,
            json, json.length, ts, ts, false, false)
    }

    @Test fun weekOfOwnerRowsProducesDayNightDifferenceWithoutClinicalWrites() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), CopilotDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            val dao = db.localNightscoutV3Dao()
            db.withTransaction {
                for (minute in 0 until 7 * 1440 step 10) dao.put(row(minute))
                dao.put(row(1).copy(deleted = true))
                dao.put(row(2).copy(source = "other"))
                dao.put(row(-20 * 1440))
            }
            val points = AapsObservedCircadian.read(db, now)
            assertEquals(1008, points.size)
            val fit = CircadianPatternEngine().fit(points, emptyList(), emptyList(), now,
                CircadianPatternConfig(baseTargetMmol = 5.5, useWeekendSplit = false))
            val slots = fit.slotStats.filter { it.windowDays == 7 }
            assertTrue(slots.single { it.slotIndex == 48 }.medianBg > slots.single { it.slotIndex == 8 }.medianBg + 2)
            assertTrue(db.glucoseDao().since(0).isEmpty())
            assertTrue(db.therapyDao().since(0).isEmpty())
        } finally { db.close() }
    }

    @Test fun repeatedTimestampsMixedSourcesOrDenseShortBurstsCannotEstablishPattern() = runBlocking {
        val valid = (0 until 4 * 1440 step 10).map { row(it) }
        assertTrue(AapsObservedCircadian.decode(valid, now).isNotEmpty())
        for (bad in listOf(row(0), row(4 * 1440 - 10, "second"), row(1).copy(modifiedRevision = now + 1))) {
            assertTrue(AapsObservedCircadian.decode(valid + bad, now).isEmpty())
        }
        val bursts = (0 until 7).flatMap { d -> (0 until 130).map { row(d * 1440 + it) } }
        assertTrue(AapsObservedCircadian.decode(bursts, now).isEmpty())
        val switched = (0 until 7 * 1440 step 10).map { row(it, if (it < 2 * 1440) "previous" else "current") }
        val current = AapsObservedCircadian.decode(switched, now)
        assertEquals(5 * 144, current.size)
        assertTrue(current.all { it.source == "current" })
    }
}
