package io.aaps.copilot.service

import androidx.room.withTransaction
import io.aaps.copilot.data.local.ComboChunkEntity
import io.aaps.copilot.data.local.ComboJournalDatabase
import io.aaps.copilot.data.local.ComboReadEntity
import io.aaps.copilot.data.repository.HistoryJson
import java.util.Collections
import kotlinx.coroutines.CancellationException

internal enum class ComboRetentionError { INVALID, CONFLICT, CAPACITY, CORRUPT, STORAGE }
internal class ComboRetentionFailure(val reason: ComboRetentionError) :
    IllegalStateException("combo_retention_${reason.name.lowercase()}")

/** Receipt for retained bytes only. Neither caller-supplied identity nor storage proves delivery. */
internal class ComboRetentionReceipt(
    val readId: String, val sha256: String, val byteCount: Int,
    val status: AapsV3ComboHistoryStatus, val newlyStored: Boolean
) {
    val deliveryCompletenessVerified: Boolean get() = false
    val pumpBindingVerified: Boolean get() = false
    override fun toString() = "ComboRetentionReceipt(redacted)"
}

/** Bounded diagnostic retention. A future paired receiver must authenticate the caller separately. */
internal class AapsV3ComboJournalStore(
    private val db: ComboJournalDatabase,
    private val maximumReads: Int = 4096,
    private val maximumBytes: Long = 32L * 1024 * 1024
) {
    private val dao = db.journalDao()
    init { require(maximumReads in 1..4096 && maximumBytes in 1..32L * 1024 * 1024) }

    suspend fun retain(ownerId: String, binding: String, readId: String,
                       sha256: String, bytes: ByteArray): ComboRetentionReceipt {
        scope(ownerId, binding); identifier(readId)
        if (!HASH.matches(sha256) || bytes.size !in 1..AapsV3ComboJournalRead.MAX_READ_BYTES) fail(ComboRetentionError.INVALID)
        val raw = bytes.copyOf()
        try {
            if (AapsV3ComboJournalAssembly.digest(raw) != sha256) fail(ComboRetentionError.INVALID)
            val parsed = decode(raw, binding, ComboRetentionError.INVALID)
            val row = ComboReadEntity(ownerId, binding, readId, sha256, raw.size)
            return transaction {
                val old = dao.find(ownerId, binding, readId)
                if (old != null) {
                    if (old != row) fail(ComboRetentionError.CONFLICT)
                    if (!load(old).rawBytes().contentEquals(raw)) fail(ComboRetentionError.CORRUPT)
                    return@transaction receipt(row, parsed, false)
                }
                val totals = dao.totals()
                if (totals.bytes != totals.storedBytes) fail(ComboRetentionError.CORRUPT)
                if (totals.reads !in 0..maximumReads.toLong() || totals.bytes !in 0..maximumBytes) fail(ComboRetentionError.CAPACITY)
                if (totals.reads == maximumReads.toLong() || raw.size > maximumBytes - totals.bytes) fail(ComboRetentionError.CAPACITY)
                dao.insertRead(row)
                for (offset in raw.indices step CHUNK_BYTES) {
                    dao.insertChunk(ComboChunkEntity(ownerId, binding, readId, offset / CHUNK_BYTES,
                        raw.copyOfRange(offset, minOf(raw.size, offset + CHUNK_BYTES))))
                }
                // Verify the complete same-transaction result before returning a persistent receipt.
                val retained = load(row)
                receipt(row, retained, true)
            }
        } finally { raw.fill(0) }
    }

    suspend fun read(ownerId: String, binding: String, readId: String): AapsV3ComboJournalRead? {
        scope(ownerId, binding); identifier(readId)
        return transaction { dao.find(ownerId, binding, readId)?.let { load(it) } }
    }

    /** All scoped bytes are rechecked; a damaged row refuses the whole inventory, never a partial list. */
    suspend fun inventory(ownerId: String, binding: String): List<ComboRetentionReceipt> {
        scope(ownerId, binding)
        return transaction {
            val totals = dao.totals()
            if (totals.bytes != totals.storedBytes) fail(ComboRetentionError.CORRUPT)
            if (totals.reads !in 0..maximumReads.toLong() || totals.bytes !in 0..maximumBytes) fail(ComboRetentionError.CAPACITY)
            val rows = dao.inventory(ownerId, binding, maximumReads + 1)
            if (rows.size > maximumReads || rows.sumOf { it.byteCount.toLong() } > maximumBytes) fail(ComboRetentionError.CAPACITY)
            Collections.unmodifiableList(rows.map { receipt(it, load(it), false) })
        }
    }

    private suspend fun load(row: ComboReadEntity): AapsV3ComboJournalRead {
        if (!HASH.matches(row.binding) || runCatching {
            HistoryJson.uuid(row.ownerId); HistoryJson.uuid(row.readId)
        }.isFailure) fail(ComboRetentionError.CORRUPT)
        if (!HASH.matches(row.sha256) || row.byteCount !in 1..AapsV3ComboJournalRead.MAX_READ_BYTES) fail(ComboRetentionError.CORRUPT)
        val parts = (row.byteCount - 1) / CHUNK_BYTES + 1
        val positions = dao.positions(row.ownerId, row.binding, row.readId, MAX_CHUNKS + 1)
        if (positions.size != parts || positions.withIndex().any { (i, p) ->
            p.part != i || p.byteCount != minOf(CHUNK_BYTES, row.byteCount - i * CHUNK_BYTES)
        }) fail(ComboRetentionError.CORRUPT)
        val raw = ByteArray(row.byteCount)
        try {
            for (position in positions) {
                val bytes = dao.chunk(row.ownerId, row.binding, row.readId, position.part) ?: fail(ComboRetentionError.CORRUPT)
                if (bytes.size != position.byteCount) fail(ComboRetentionError.CORRUPT)
                bytes.copyInto(raw, position.part * CHUNK_BYTES)
            }
            if (AapsV3ComboJournalAssembly.digest(raw) != row.sha256) fail(ComboRetentionError.CORRUPT)
            return decode(raw, row.binding, ComboRetentionError.CORRUPT)
        } finally { raw.fill(0) }
    }

    private fun decode(bytes: ByteArray, binding: String, error: ComboRetentionError): AapsV3ComboJournalRead =
        AapsV3ComboJournalRead.read(bytes, binding).also { if (!it.prefixMatchesExpectedBinding) fail(error) }

    private fun receipt(row: ComboReadEntity, parsed: AapsV3ComboJournalRead, created: Boolean) =
        ComboRetentionReceipt(row.readId, row.sha256, row.byteCount, parsed.status, created)

    private suspend fun <T> transaction(block: suspend () -> T): T = try {
        db.withTransaction { block() }
    } catch (e: CancellationException) { throw e
    } catch (e: ComboRetentionFailure) { throw e
    } catch (_: Exception) { fail(ComboRetentionError.STORAGE) }

    private fun scope(owner: String, binding: String) {
        identifier(owner)
        if (!HASH.matches(binding)) fail(ComboRetentionError.INVALID)
    }
    private fun identifier(id: String) {
        try { HistoryJson.uuid(id) } catch (_: Exception) { fail(ComboRetentionError.INVALID) }
    }
    override fun toString() = "AapsV3ComboJournalStore(redacted)"
    private companion object {
        const val CHUNK_BYTES = 65_536
        const val MAX_CHUNKS = AapsV3ComboJournalRead.MAX_READ_BYTES / CHUNK_BYTES
        val HASH = Regex("[0-9a-f]{64}")
        fun fail(reason: ComboRetentionError): Nothing = throw ComboRetentionFailure(reason)
    }
}
