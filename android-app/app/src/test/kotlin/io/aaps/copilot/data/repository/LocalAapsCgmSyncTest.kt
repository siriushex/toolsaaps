package io.aaps.copilot.data.repository

import android.content.Context
import android.content.Intent
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.aaps.copilot.data.local.CopilotDatabase
import io.aaps.copilot.data.local.entity.GlucoseSampleEntity
import io.aaps.copilot.domain.predict.HybridPredictionEngine
import io.aaps.copilot.domain.model.TherapyEvent
import com.google.gson.Gson
import io.aaps.copilot.util.UnitConverter
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
class LocalAapsCgmSyncTest {
    private val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), CopilotDatabase::class.java)
        .allowMainThreadQueries().build()
    private var now = 1_800_000_000_000L
    private val key = "sample-window-0001"
    private val sampleTs = now - 20_000
    private var allowed = true
    private var acknowledged = true
    private var notified = 0
    private var body: ByteArray? = null
    private var pastWindows = mutableListOf<LongRange>()
    private var offerPast = false
    private fun syncer() = LocalAapsCgmSync(db, { from, end, _ ->
        if (end == now) body else {
            pastWindows.add(from..end)
            if (offerPast) packet(listOf(end to 115.0), from = from, through = end) else null
        }
    }, { allowed }, { notified++; acknowledged }, { now }, { key })

    private fun packet(samples: List<Pair<Long, Double>>, from: Long = now - LocalAapsCgmSync.WINDOW + 1, through: Long = now,
                       sensor: String = "LIBRE_3", sentKey: String = key, at: Long = now) = buildJsonObject {
        put("v", 1); put("at", at); put("nonce", sentKey); put("unit", "mg/dL"); put("from", from); put("through", through)
        put("samples", buildJsonArray { samples.forEach { (ts, x) ->
            add(buildJsonObject { put("ts", ts); put("mgdl", x); put("sensor", sensor) })
        } })
    }.toString().encodeToByteArray()

    @After fun close() = db.close()

    @Test fun legacyStatusAndLocalProviderGiveSameForecastWithIdenticalMealAndInsulin() = runBlocking {
        // The legacy receiver clamps future sensor timestamps against the Android wall clock.
        now = System.currentTimeMillis()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val legacy = BroadcastIngestRepository(context, db, AuditLogger(db.auditLogDao(), Gson()), clock = { now })
        val points = (0 until 24).map { now - (24 - it) * 300_000L to (112.0 + it % 8) }
        val therapy = listOf(TherapyEvent(now - 2_700_000L, "meal_bolus", mapOf("grams" to "15", "units" to "1.2")))
        for ((ts, mgdl) in points) {
            val result = legacy.ingest(Intent("info.nightscout.androidaps.status").apply {
                putExtra("timestamp", now); putExtra("source", "aaps_validated_relay")
                putExtra("sgv", mgdl); putExtra("date", ts); putExtra("units", "mg/dl"); putExtra("sensorSource", "LIBRE_3")
            })
            assertEquals(1, result.glucoseImported)
        }
        suspend fun inputs() = GlucoseSanitizer.filterEntities(db.glucoseDao().since(0)).map { it.toDomain() }
        val old = inputs()
        assertTrue(old.all { it.source == "aaps_broadcast" })
        val expected = HybridPredictionEngine(enableEnhancedPredictionV3 = true).predict(old, therapy)
        assertEquals(setOf(5, 30, 60), expected.map { it.horizonMinutes }.toSet())
        body = packet(points)
        syncer().sync()
        val current = inputs()
        assertEquals(points.size, current.size)
        assertTrue(current.all { it.source == "aaps_local_cgm:LIBRE_3" })
        assertEquals(old.map { it.ts to it.valueMmol }, current.map { it.ts to it.valueMmol })
        assertEquals(expected, HybridPredictionEngine(enableEnhancedPredictionV3 = true).predict(current, therapy))
    }

    @Test fun exactProducerFixtureCorrectionReplayAndRemovalPreserveOtherOrigins() = runBlocking {
        val dao = db.glucoseDao()
        dao.upsertAll(listOf(GlucoseSampleEntity(timestamp = sampleTs, mmol = 6.3, source = "nightscout", quality = "OK")))
        body = requireNotNull(javaClass.getResource("/fixtures/local-cgm-window-v1.json")).readBytes()
        val reader = syncer()
        reader.sync()
        suspend fun latest() = dao.latestValidDistinctAtOrBefore(now, 1).single()
        val native = latest()
        assertEquals(sampleTs, native.timestamp)
        assertEquals("aaps_local_cgm:LIBRE_3", native.source)
        assertEquals(UnitConverter.mgdlToMmol(107.0), native.mmol, 0.0)
        assertEquals(emptyList<Long>(), GlucoseSanitizer.duplicateEntityIdsToDelete(dao.since(0)))
        assertEquals(0, dao.deleteDuplicateByTimestampWithPriority())
        reader.sync()
        assertEquals(native, latest()); assertEquals(1, notified)
        body = packet(listOf(sampleTs to 113.0))
        reader.sync()
        assertEquals(UnitConverter.mgdlToMmol(113.0), latest().mmol, 0.0)
        body = null
        reader.sync()
        assertEquals(2, dao.since(0).size)
        body = packet(emptyList())
        reader.sync()
        assertEquals("nightscout", dao.since(0).single().source); assertEquals(3, notified)
    }

    @Test fun onlyValidatedWindowsCanReplaceDataAndRetryPendingNotification() = runBlocking {
        body = packet(listOf(sampleTs to 100.0))
        acknowledged = false
        syncer().sync()
        val previous = db.glucoseDao().since(0).single()
        acknowledged = true; body = null
        syncer().sync()
        assertEquals(2, notified)
        for (bad in listOf(packet(emptyList(), sentKey = "other-window-0001"), packet(emptyList(), at = now - 20_000),
                packet(listOf(sampleTs to 100.0), sensor = "IOB_PREDICTION"), packet(listOf(now + 1 to 100.0)))) {
            body = bad
            assertTrue(runCatching { syncer().sync() }.isFailure)
            assertEquals(previous, db.glucoseDao().since(0).single())
        }
        allowed = false; body = packet(emptyList())
        syncer().sync()
        assertEquals(previous, db.glucoseDao().since(0).single())
    }

    @Test fun recentAndResumableHistoryFeedExistingThreeHorizonEngine() = runBlocking {
        val points = (0 until 8).map { (now - (8 - it) * 300_000L) to (105.0 + it) }
        body = packet(points + listOf((now - 60_000L) to 280.0, sampleTs to 111.0, sampleTs to 109.0))
        offerPast = true
        syncer().sync()
        syncer().sync()
        assertEquals(2, pastWindows.size)
        assertEquals(pastWindows[0].first - 1, pastWindows[1].last)
        val rows = GlucoseSanitizer.filterEntities(db.glucoseDao().since(now - LocalAapsCgmSync.WINDOW + 1))
        assertEquals(points.map { it.first }, rows.map { it.timestamp })
        val forecasts = HybridPredictionEngine(enableEnhancedPredictionV3 = true).predict(rows.map { it.toDomain() }, emptyList())
        assertEquals(setOf(5, 30, 60), forecasts.map { it.horizonMinutes }.toSet())
        assertTrue(forecasts.all { it.valueMmol.isFinite() && it.valueMmol in it.ciLow..it.ciHigh })
    }
}
