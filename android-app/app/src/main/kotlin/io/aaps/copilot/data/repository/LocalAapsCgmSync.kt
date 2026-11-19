package io.aaps.copilot.data.repository

import androidx.room.withTransaction
import io.aaps.copilot.data.local.CopilotDatabase
import io.aaps.copilot.data.local.entity.GlucoseSampleEntity
import io.aaps.copilot.data.local.entity.SyncStateEntity
import io.aaps.copilot.util.UnitConverter
import java.util.UUID
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** A verified, complete source window. A transport failure is never an empty window. */
internal class LocalAapsCgmSync(
    private val db: CopilotDatabase,
    private val read: suspend (Long, Long, String) -> ByteArray?,
    private val enabled: suspend () -> Boolean,
    private val invalidate: suspend () -> Boolean,
    private val clock: () -> Long = System::currentTimeMillis,
    private val nonce: () -> String = { UUID.randomUUID().toString() }
) {
    private val mutex = Mutex()

    suspend fun sync() = mutex.withLock {
        if (!enabled()) return@withLock
        val now = clock()
        val from = now - WINDOW + 1
        try {
            if (!window(from, now, historical = false)) return@withLock
            val oldest = now - LOOKBACK
            val cursor = db.syncStateDao().bySource(CURSOR)?.lastSyncedTimestamp
            val end = cursor?.takeIf { it in oldest until from } ?: (from - 1)
            window(maxOf(oldest, end - WINDOW + 1), end, historical = true)
        } finally {
            if (enabled() && db.syncStateDao().bySource(DIRTY) != null && invalidate()) {
                db.syncStateDao().deleteBySource(DIRTY)
            }
        }
    }

    private suspend fun window(from: Long, through: Long, historical: Boolean): Boolean {
        val issued = clock()
        val key = nonce()
        val bytes = read(from, through, key) ?: return false
        val received = clock()
        require(received in issued..issued + 10_000)
        val body = HistoryJson.parse(bytes, 65_536)
        HistoryJson.fields(body, setOf("v", "nonce", "from", "through", "at", "unit", "samples"))
        require(HistoryJson.long(body.getValue("v")) == 1L && HistoryJson.string(body.getValue("nonce")) == key &&
            HistoryJson.long(body.getValue("from")) == from && HistoryJson.long(body.getValue("through")) == through &&
            HistoryJson.long(body.getValue("at")) in issued..received && HistoryJson.string(body.getValue("unit")) == "mg/dL")
        val rows = HistoryJson.array(body.getValue("samples"))
        require(rows.size <= 256)
        val candidates = rows.map { raw ->
            val v = HistoryJson.obj(raw)
            HistoryJson.fields(v, setOf("ts", "mgdl", "sensor"))
            val ts = HistoryJson.long(v.getValue("ts")).also { require(it in from..through) }
            val sensor = HistoryJson.string(v.getValue("sensor")).also { require(it in SENSORS) }
            val mgdl = HistoryJson.double(v.getValue("mgdl")).also { require(it.isFinite() && it >= 39) }
            GlucoseSampleEntity(timestamp = ts, mmol = UnitConverter.mgdlToMmol(mgdl), source = PREFIX + sensor, quality = "OK")
        }.groupBy { it.timestamp }.mapNotNull { (_, group) ->
            group.distinct().singleOrNull()?.takeIf { it.mmol in 1.0..33.0 }
        }.associateBy { it.timestamp }
        if (!enabled()) return false
        db.withTransaction {
            val dao = db.glucoseDao()
            val (existing, independent) = dao.between(from, through).partition { it.source.startsWith(PREFIX) }
            val others = GlucoseSanitizer.filterEntities(independent.filterNot {
                GlucoseSanitizer.isKnownInvalidQuality(it.quality) || !it.mmol.isFinite() || it.mmol <= 0
            }).associateBy { it.timestamp }
            val wanted = mutableMapOf<Long, GlucoseSampleEntity>()
            var previous = dao.latestValidDistinctAtOrBefore(from - 1, 1).firstOrNull()
            for (time in (candidates.keys + others.keys).sorted()) {
                val sample = candidates[time]
                val other = others[time]
                if (sample != null && listOfNotNull(previous, other).none { GlucoseSanitizer.isInputOutlier(sample, it) }) {
                    wanted[time] = sample
                    previous = sample
                } else if (other != null) previous = other
            }
            val keep = mutableSetOf<Long>()
            val remove = existing.filterNot {
                wanted[it.timestamp] == it.copy(id = 0) && keep.add(it.timestamp)
            }.map { it.id }
            val add = wanted.filterKeys { it !in keep }.values.toList()
            if (remove.isNotEmpty() || add.isNotEmpty()) {
                remove.chunked(256).forEach { dao.deleteByIds(it) }
                if (add.isNotEmpty()) dao.upsertAll(add)
            }
            val latest = if (historical) null else wanted.values.maxByOrNull { it.timestamp }
            val initialized = latest != null && clock().let { at -> at >= received &&
                initializeRawAuthorityForFirstLocalCgm(db, latest, at) }
            if (remove.isNotEmpty() || add.isNotEmpty() || initialized) {
                db.syncStateDao().upsert(SyncStateEntity(DIRTY, received))
            }
            if (historical) db.syncStateDao().upsert(SyncStateEntity(CURSOR, from - 1))
        }
        return true
    }

    internal companion object {
        const val WINDOW = 3 * 3_600_000L
        const val LOOKBACK = 14 * 86_400_000L
        const val PREFIX = "aaps_local_cgm:"
        private const val CURSOR = "aaps_local_cgm_backfill_through"
        private const val DIRTY = "aaps_local_cgm_invalidation_pending"
        // AAPS 4 sensor enum: no random source, generic UNKNOWN or prediction traces.
        private val SENSORS = setOf("DEXCOM_UNKNOWN", "DEXCOM_G6", "DEXCOM_G7", "LIBRE_1", "LIBRE_2",
            "LIBRE_3", "MEDTRUM_A6", "MEDTRUM_UNKNOWN", "MM_600_SERIES", "MM_SIMPLERA", "MM_UNKNOWN",
            "SIBIONIC_UNKNOWN", "SIBIONIC_GS1", "SIBIONIC_GS3", "ACCU_CHEK", "CARESENS_AIR", "AIDEX",
            "AIDEX_X", "POCTECH_NATIVE", "GLUNOVO_NATIVE", "INTELLIGO_NATIVE", "SINOCARE", "ANYTIME",
            "GLUTEC", "GLUPRO", "EVERSENSE", "SYAI_TAG", "INSTARA")
    }
}
