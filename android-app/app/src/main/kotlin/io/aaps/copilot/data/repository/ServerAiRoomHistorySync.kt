package io.aaps.copilot.data.repository

import java.util.UUID
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import io.aaps.copilot.security.ServerAiHistoryPersistence

internal data class RoomAiExportStatus(val scanFinished: Boolean, val fromMs: Long, val throughMs: Long,
    val scannedRows: Long, val omittedRows: Long, val lastFullThroughMs: Long) {
    val needsBackfill get() = lastFullThroughMs == 0L
    fun contextSummary() = "ROOM_CORE_HISTORY_EXPORT: scan_finished=$scanFinished; " +
        "event_window_ms=$fromMs..$throughMs; scanned_source_rows=$scannedRows; omitted_source_rows=$omittedRows; " +
        "last_full_scan_through_ms=$lastFullThroughMs; " +
        "sources=glucose,recorded_therapy,forecasts,effective_sensitivity,allowlisted_numeric_telemetry. " +
        "Not a point-in-time snapshot or complete database export. Edits during scanning may await the next scan. " +
        "Corrections older than six hours may await daily reconciliation. Zero last-full-scan time means not yet completed. " +
        "Expired queued observations are excluded without changing original timestamps. " +
        "Export revision is local transport revision, not an AAPS revision. Observation time is export time. " +
        "Recorded insulin is not confirmed delivery; carbohydrates are device-declared, not weighed evidence. " +
        "Historical calibration, timezone, GI overlays, plans, alarm receipts and unlisted tables are not supplied. " +
        "Do not sum separate measurements of IOB/COB or repeated sensor/forecast observations as doses or meals."
}

/** One app-owned writer; bounded pages and durable exact recovery, never a model/therapy operation. */
internal class ServerAiRoomHistorySync(
    private val source: RoomAiHistorySource,
    private val index: RoomAiExportIndex,
    private val pending: ServerAiHistoryPersistence,
    private val deviceBinding: suspend () -> String,
    private val clock: () -> Long,
    private val retryPending: suspend () -> Unit,
    private val upload: suspend (List<ServerAiHistoryRecord>, String) -> Unit
) {
    private val mutex = Mutex()

    suspend fun sync(maxPages: Int): RoomAiExportStatus = mutex.withLock {
        require(maxPages in 1..256)
        val device = deviceBinding()
        check(device.isNotEmpty() && device.length <= 256)
        var progress = index.progress()
        check(progress == null || progress.device == device)
        var used = 0
        val saved = readPlan()
        if (saved != null) {
            check(saved.progress.device == device)
            settle(saved)
            progress = index.progress()
            used++
        } else retryPending()
        if (progress == null || (progress.complete && used == 0)) {
            val now = clock().also(HistoryJson::time)
            val run = UUID.randomUUID().toString()
            // Populate recent context first, then backfill; avoid rescanning a year every few minutes.
            val full = progress != null && (progress.lastFullThroughMs == 0L || now - progress.lastFullThroughMs >= FULL_SCAN_INTERVAL_MS)
            progress = RoomAiExportProgress(device = device, run = run,
                fromMs = maxOf(1, now - if (full) HistoryJson.retentionMs - EXPIRY_MARGIN_MS else RECENT_WINDOW_MS), throughMs = now,
                fullScan = full, lastFullThroughMs = progress?.lastFullThroughMs ?: 0,
                lastPlan = UUID.randomUUID().toString())
            index.commit(progress, emptyList())
        }
        while (used < maxPages && !progress!!.complete) {
            val next = if (progress.stream < source.streamCount) scan(progress) else deletions(progress)
            writePlan(next) // Freeze records and metadata together before any network operation.
            settle(next)
            progress = next.progress
            used++
        }
        index.prune(maxOf(0, clock() - HistoryJson.retentionMs))
        val result = progress!!
        RoomAiExportStatus(result.complete, result.fromMs, result.throughMs, result.scannedRows, result.omittedRows, result.lastFullThroughMs)
    }

    private suspend fun scan(progress: RoomAiExportProgress): Plan {
        val from = maxOf(progress.fromMs, clock() - HistoryJson.retentionMs + EXPIRY_MARGIN_MS)
        val page = if (from > progress.throughMs) RoomAiHistoryPage(progress.afterRowId, progress.afterEventAtMs, 0, emptyList(), 0)
            else source.read(progress.stream, progress.afterRowId, from, progress.throughMs, PAGE_ROWS,
                maxOf(from - 1, progress.afterEventAtMs))
        val records = mutableListOf<ServerAiHistoryRecord>()
        val entries = mutableListOf<RoomAiExportEntry>()
        val observedAt = clock()
        val priorEntries = index.findAll(page.observations.map { it.key })
        page.observations.forEach { item ->
            val prior = priorEntries[item.key]
            val fingerprint = fingerprint(item)
            val unchanged = prior != null && !prior.deleted && prior.fingerprint == fingerprint
            val entry = if (unchanged) prior!!.copy(seenRun = progress.run) else RoomAiExportEntry(
                key = item.key, recordId = if (prior == null || prior.deleted) UUID.randomUUID().toString() else prior.recordId,
                revision = if (prior == null || prior.deleted) 1 else Math.addExact(prior.revision, 1),
                eventAtMs = item.eventAtMs, kind = item.data.kind.name, source = item.source.name,
                quality = item.quality.name, fingerprint = fingerprint, seenRun = progress.run)
            entries += entry
            if (!unchanged) records += ServerAiHistoryRecord(entry.recordId, entry.revision, entry.eventAtMs,
                observedAt, item.data.kind, item.source, item.quality, data = item.data, context = item.context)
        }
        val exhausted = page.scanned < PAGE_ROWS
        return Plan(UUID.randomUUID().toString(), progress.copy(
            stream = if (exhausted) progress.stream + 1 else progress.stream,
            afterRowId = if (exhausted) 0 else page.afterRowId,
            afterEventAtMs = if (exhausted) 0 else page.afterEventAtMs,
            scannedRows = Math.addExact(progress.scannedRows, page.scanned.toLong()),
            omittedRows = Math.addExact(progress.omittedRows, page.omitted.toLong())
        ), entries, records.map { it.toWire(observedAt) })
    }

    private suspend fun deletions(progress: RoomAiExportProgress): Plan {
        val from = maxOf(progress.fromMs, clock() - HistoryJson.retentionMs + EXPIRY_MARGIN_MS)
        val stale = if (from > progress.throughMs) emptyList() else index.stale(progress.copy(fromMs = from), PAGE_ROWS)
        val observedAt = clock()
        val entries = stale.map { it.copy(revision = Math.addExact(it.revision, 1), deleted = true, seenRun = progress.run) }
        val records = entries.map { row -> ServerAiHistoryRecord(row.recordId, row.revision, row.eventAtMs, observedAt,
            enumValueOf(row.kind), enumValueOf(row.source), enumValueOf(row.quality), deleted = true).toWire(observedAt) }
        return Plan(UUID.randomUUID().toString(), progress.copy(complete = stale.isEmpty(),
            deletionAfterTimeMs = stale.lastOrNull()?.eventAtMs ?: progress.deletionAfterTimeMs,
            deletionAfterKey = stale.lastOrNull()?.key ?: progress.deletionAfterKey,
            lastFullThroughMs = if (stale.isEmpty() && progress.fullScan) progress.throughMs else progress.lastFullThroughMs), entries, records)
    }

    private suspend fun settle(plan: Plan) {
        validate(plan)
        val current = index.progress()
        check(current == null || current.device == plan.progress.device)
        if (current?.lastPlan != plan.id) {
            check(deviceBinding() == plan.progress.device)
            val cutoff = clock() - HistoryJson.retentionMs
            val records = plan.records.filter { HistoryJson.long(it.getValue("eventAtMs")) > cutoff }
                .map { HistoryCodec.record(it, clock()) }
            if (records.isNotEmpty()) upload(records, plan.progress.device)
            else if (plan.records.isNotEmpty()) retryPending()
            check(deviceBinding() == plan.progress.device)
            index.commit(plan.progress.copy(lastPlan = plan.id), plan.entries.filter { it.eventAtMs > cutoff })
        }
        // A failed clear leaves the page safely recoverable after the already-atomic index commit.
        pending.write("{}")
    }

    private fun fingerprint(item: RoomAiObservation) = roomAiDigest(HistoryJson.decodeUtf8(HistoryJson.bytes(buildJsonObject {
        put("quality", item.quality.name); put("data", item.data.toWire())
        put("context", item.context?.toWire() ?: JsonNull)
    })))

    private suspend fun readPlan(): Plan? {
        val raw = pending.read() ?: return null
        val obj = HistoryJson.parse(HistoryJson.encodeUtf8(raw), MAX_PLAN_BYTES)
        if (obj.isEmpty()) return null
        return Json.decodeFromJsonElement<Plan>(obj).also(::validate)
    }

    private suspend fun writePlan(plan: Plan) {
        validate(plan)
        val raw = Json.encodeToString(plan)
        check(HistoryJson.encodeUtf8(raw).size <= MAX_PLAN_BYTES)
        pending.write(raw)
    }

    private fun validate(plan: Plan) {
        HistoryJson.uuid(plan.id); HistoryJson.uuid(plan.progress.run)
        val p = plan.progress
        check(p.id == 1 && p.device.isNotEmpty() && p.device.length <= 256)
        check(p.stream in 0..source.streamCount && p.afterRowId >= 0 && p.afterEventAtMs >= 0 &&
            p.scannedRows >= 0 && p.omittedRows in 0..p.scannedRows)
        check(p.fromMs > 0 && p.throughMs >= p.fromMs && (!p.complete || p.stream == source.streamCount))
        check(p.lastFullThroughMs in 0..p.throughMs)
        check(p.deletionAfterTimeMs >= 0 && (p.deletionAfterKey.isEmpty() || Regex("[0-9a-f]{64}").matches(p.deletionAfterKey)))
        check(plan.entries.size <= PAGE_ROWS * 2 && plan.records.size <= PAGE_ROWS * 2)
        check(plan.entries.map { it.key }.distinct().size == plan.entries.size)
        val entries = plan.entries.associateBy { it.recordId }
        check(entries.size == plan.entries.size)
        plan.entries.forEach {
            check(Regex("[0-9a-f]{64}").matches(it.key) && Regex("[0-9a-f]{64}").matches(it.fingerprint))
            HistoryJson.uuid(it.recordId)
            check(it.revision > 0 && it.seenRun == p.run && it.eventAtMs in p.fromMs..p.throughMs)
        }
        check(plan.records.map { it.getValue("recordId") }.distinct().size == plan.records.size)
        plan.records.forEach {
            val observed = HistoryJson.long(it.getValue("observedAtMs"))
            val record = HistoryCodec.record(it, observed)
            check(observed <= clock())
            val entry = entries[record.recordId] ?: error("invalid_export_page")
            check(record.revision == entry.revision && record.eventAtMs == entry.eventAtMs && record.kind.name == entry.kind &&
                record.source.name == entry.source && record.quality.name == entry.quality && record.deleted == entry.deleted)
        }
    }

    @Serializable private data class Plan(val id: String, val progress: RoomAiExportProgress,
        val entries: List<RoomAiExportEntry>, val records: List<JsonObject>) {
        override fun toString() = "RoomAiExportPlan(redacted)"
    }

    private companion object {
        const val PAGE_ROWS = 32
        const val EXPIRY_MARGIN_MS = 600_000L
        const val MAX_PLAN_BYTES = 1_048_576
        const val RECENT_WINDOW_MS = 6 * 3_600_000L
        const val FULL_SCAN_INTERVAL_MS = 86_400_000L
    }
}
