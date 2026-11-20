package io.aaps.copilot.data.repository

import androidx.room.withTransaction
import io.aaps.copilot.data.local.CopilotDatabase
import io.aaps.copilot.data.local.entity.SyncStateEntity
import io.aaps.copilot.data.local.entity.TelemetrySampleEntity
import java.util.UUID
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Ephemeral, exact inputs to one forecast. Never persist as delivery evidence or send to AI. */
class LocalLoopForecastSelection private constructor(
    private val enabled: Boolean,
    internal val samples: List<TelemetrySampleEntity>
) {
    internal suspend fun requireCurrent(db: CopilotDatabase, allowed: Boolean, at: Long) {
        require(allowed == enabled && (!enabled || LocalAapsLoopInputSync.activeSamples(db, at) == samples)) {
            "local AAPS loop inputs changed before forecast publication"
        }
    }

    internal companion object {
        suspend fun capture(db: CopilotDatabase, enabled: Boolean, now: Long) = LocalLoopForecastSelection(
            enabled, if (enabled) LocalAapsLoopInputSync.activeSamples(db, now) else emptyList()
        )
    }
}

internal class LocalAapsLoopInputSync(
    private val db: CopilotDatabase,
    private val read: suspend (String) -> ByteArray?,
    private val enabled: suspend () -> Boolean,
    private val invalidate: suspend () -> Boolean,
    private val clock: () -> Long = System::currentTimeMillis,
    private val nonce: () -> String = { UUID.randomUUID().toString() },
    private val readComponents: suspend (String) -> ByteArray? = { null }
) {
    private val mutex = Mutex()

    suspend fun sync() = mutex.withLock {
        if (!enabled()) return@withLock
        try {
            val issued = clock()
            val key = nonce()
            val components = readComponents(key)
            val bytes = components ?: read(key) ?: return@withLock
            val received = clock()
            val rows = decodeSamples(bytes, key, issued, received, if (components == null) 1L else 3L)
            if (!enabled()) return@withLock
            db.withTransaction {
                val current = db.telemetryDao().byIds(rows.map { it.id }).associateBy { it.id }
                val changed = rows.filter { it != current[it.id] }
                if (changed.isNotEmpty()) db.telemetryDao().upsertAll(changed)
                var selectionChanged = false
                for (name in NAMES + COMPONENT_NAMES) {
                    val selection = selectionKey(name)
                    val timestamp = rows.singleOrNull { it.key == name }?.timestamp ?: 0L
                    if ((db.syncStateDao().bySource(selection)?.lastSyncedTimestamp ?: 0L) != timestamp) {
                        db.syncStateDao().upsert(SyncStateEntity(selection, timestamp))
                        selectionChanged = true
                    }
                }
                if (changed.isNotEmpty() || selectionChanged) db.syncStateDao().upsert(SyncStateEntity(DIRTY, received))
            }
        } finally {
            if (enabled() && db.syncStateDao().bySource(DIRTY) != null && invalidate()) db.syncStateDao().deleteBySource(DIRTY)
        }
    }

    companion object {
        internal fun decodeSamples(bytes: ByteArray, key: String, issued: Long, received: Long, version: Long = 1L): List<TelemetrySampleEntity> {
            require(issued in 1..HistoryJson.maxTime && received in issued..issued + 10_000L)
            val body = HistoryJson.parse(bytes, 1024)
            require(version == 1L || version == 3L)
            val components = setOf("bolusIobU", "basalIobU", "insulinActivityUPerMin")
            HistoryJson.fields(body, setOf("v", "at", "nonce"),
                setOf("netIobU", "iobAt", "cobG", "cobAt", "inputIsfMgdlPerU", "isfAt", "inputCrGPerU", "crAt") +
                    if (version == 3L) components else emptySet())
            val at = HistoryJson.long(body.getValue("at"))
            require(HistoryJson.long(body.getValue("v")) == version && HistoryJson.string(body.getValue("nonce")) == key &&
                at in issued..received)
            fun metric(field: String, ts: String, age: Long, range: ClosedFloatingPointRange<Double>,
                       name: String, unit: String, divisor: Double = 1.0): TelemetrySampleEntity? {
                require((field in body) == (ts in body))
                val value = body[field]?.let(HistoryJson::double) ?: return null
                val timestamp = HistoryJson.long(body.getValue(ts))
                require(timestamp in maxOf(1L, at - age)..at && value in range)
                if (received - timestamp > age) return null
                return TelemetrySampleEntity("$SOURCE:$name:$timestamp", timestamp, SOURCE, name, value / divisor, null, unit, "OK")
            }
            val parts = if (version == 3L) {
                require(components.all { (it in body) == ("netIobU" in body) })
                if ("netIobU" in body) require(kotlin.math.abs(HistoryJson.double(body.getValue("netIobU")) -
                    (HistoryJson.double(body.getValue("bolusIobU")) + HistoryJson.double(body.getValue("basalIobU")))) <= 0.000001)
                listOfNotNull(metric("bolusIobU", "iobAt", 300_000, -30.0..30.0, "iob_bolus_units", "U"),
                    metric("basalIobU", "iobAt", 300_000, -30.0..30.0, "iob_basal_units", "U"),
                    metric("insulinActivityUPerMin", "iobAt", 300_000, -5.0..5.0, "insulin_activity", "U/min"))
            } else emptyList()
            return parts + listOfNotNull(metric("netIobU", "iobAt", 300_000, -30.0..30.0, "iob_net_units", "U"),
                metric("cobG", "cobAt", 600_000, 0.0..400.0, "cob_grams", "g"),
                // The validated 3.x relay expressed profile ISF in mmol/L/U using exactly 18.
                metric("inputIsfMgdlPerU", "isfAt", 300_000, 14.4..324.0, "aaps_isf", "mmol/L/U", 18.0),
                metric("inputCrGPerU", "crAt", 300_000, 2.0..60.0, "aaps_cr", "g/U"))
        }

        const val SOURCE = "aaps_local_loop"
        private const val DIRTY = "aaps_local_loop_invalidation_pending"
        private val NAMES = listOf("iob_net_units", "cob_grams", "aaps_isf", "aaps_cr")
        private val COMPONENT_NAMES = listOf("iob_bolus_units", "iob_basal_units", "insulin_activity")
        internal suspend fun matchesPairedSamples(db: CopilotDatabase, reply: ByteArray, nonce: String,
                                                 issued: Long, received: Long, clock: () -> Long): Boolean {
            val selected = activeSamples(db, received)
            val version = if (selected.any { it.key in COMPONENT_NAMES }) 3L else 1L
            val paired = try {
                decodeSamples(reply, nonce, issued, received, version)
            } catch (_: IllegalArgumentException) { return false }
            val required = if (version == 3L) NAMES + COMPONENT_NAMES else NAMES
            if (paired.map { it.key }.toSet() != required.toSet()) return false
            val checkedAt = clock()
            return checkedAt >= received && checkedAt - issued in 0..10_000L &&
                selected.size == paired.size && selected.toSet() == paired.toSet() && selected.all { current(it, checkedAt) }
        }
        private fun selectionKey(name: String) = "aaps_local_loop_current_$name"

        suspend fun activeSamples(db: CopilotDatabase, now: Long): List<TelemetrySampleEntity> = db.withTransaction {
            val ids = (NAMES + COMPONENT_NAMES).mapNotNull { name ->
                db.syncStateDao().bySource(selectionKey(name))?.lastSyncedTimestamp?.takeIf { it > 0 }?.let { "$SOURCE:$name:$it" }
            }
            if (ids.isEmpty()) emptyList() else db.telemetryDao().byIds(ids).filter { it.source == SOURCE && current(it, now) }
        }
        fun current(row: TelemetrySampleEntity, now: Long): Boolean {
            val age = when (row.key) {
                "iob_net_units", "iob_bolus_units", "iob_basal_units", "insulin_activity", "aaps_isf", "aaps_cr" -> 300_000L
                "cob_grams" -> 600_000L
                else -> return false
            }
            return row.timestamp in maxOf(1L, now - age)..now
        }
    }
}
