package io.aaps.copilot.service

import io.aaps.copilot.data.repository.HistoryJson
import java.io.IOException
import java.security.MessageDigest
import java.util.Collections
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*

internal class AapsV3ComboInventoryFailure : IOException("combo_inventory_unavailable")

internal data class AapsV3ComboInventoryBinding(
    val clientId: String, val masterInstallId: String, val requestId: String,
    val counter: Long, val issuedAt: Long, val validUntil: Long, val query: AapsV3ComboReadSpec.Inventory
) {
    init { inventoryOperation {
        listOf(clientId, masterInstallId, requestId).forEach(HistoryJson::uuid)
        require(clientId != masterInstallId && counter > 1)
        HistoryJson.time(issuedAt); HistoryJson.time(validUntil)
        require(validUntil > issuedAt && validUntil - issuedAt <= 30_000)
        query.requireValid()
    } }
    fun authenticatedContext() = listOf("aaps-combo-inventory-page-v1", "master-to-client", "aes-256-gcm",
        clientId, masterInstallId, requestId, counter, issuedAt, validUntil,
        query.snapshotId ?: "-", query.afterReadId ?: "-", query.limit).joinToString("|").toByteArray(Charsets.US_ASCII)
    fun requireCurrent(now: Long) { require(now >= issuedAt && now < validUntil) }
    override fun toString() = "ComboInventoryBinding(redacted)"
}

internal data class AapsV3ComboInventoryEntry(
    val readId: String, val byteCount: Int, val sha256: String,
    val closed: Boolean, val corrupt: Boolean, val adverse: Boolean, val readFailed: Boolean
) {
    init { inventoryOperation {
        HistoryJson.uuid(readId)
        require(byteCount in 1..AapsV3ComboJournalRead.MAX_READ_BYTES && HEX.matches(sha256))
    } }
    internal fun toJson() = buildJsonObject {
        put("readId", readId); put("byteCount", byteCount); put("sha256", sha256)
        put("closed", closed); put("corrupt", corrupt); put("adverse", adverse); put("readFailed", readFailed)
    }
    override fun toString() = "ComboInventoryEntry(redacted)"
    companion object {
        internal fun parse(value: JsonElement): AapsV3ComboInventoryEntry {
            val row = HistoryJson.obj(value)
            HistoryJson.fields(row, setOf("readId", "byteCount", "sha256", "closed", "corrupt", "adverse", "readFailed"))
            val count = HistoryJson.long(row.getValue("byteCount"))
            require(count in 1..AapsV3ComboJournalRead.MAX_READ_BYTES.toLong())
            return AapsV3ComboInventoryEntry(HistoryJson.string(row.getValue("readId")), count.toInt(),
                HistoryJson.string(row.getValue("sha256")), HistoryJson.bool(row.getValue("closed")),
                HistoryJson.bool(row.getValue("corrupt")), HistoryJson.bool(row.getValue("adverse")),
                HistoryJson.bool(row.getValue("readFailed")))
        }
    }
}

/** A complete retained inventory is still not complete pump delivery history. */
internal class AapsV3ComboInventory private constructor(
    val snapshotId: String, val pumpBinding: String, entries: List<AapsV3ComboInventoryEntry>,
    val unscopedCorruptReads: Int, val writerFailed: Boolean
) {
    val reads: List<AapsV3ComboInventoryEntry> = Collections.unmodifiableList(ArrayList(entries))
    val totalBytes: Long = reads.sumOf { it.byteCount.toLong() }
    val sha256: String = inventoryDigest(snapshotId, pumpBinding, reads, totalBytes, unscopedCorruptReads, writerFailed)
    val pumpBindingVerified = false
    val deliveryCompletenessVerified = false

    fun page(query: AapsV3ComboReadSpec.Inventory): AapsV3ComboInventoryPage = inventoryOperation {
        query.requireValid()
        val start = if (query.snapshotId == null) 0 else {
            require(query.snapshotId == snapshotId)
            val previous = reads.indexOfFirst { it.readId == query.afterReadId }
            require(previous >= 0 && previous < reads.lastIndex)
            previous + 1
        }
        val end = minOf(reads.size, start + query.limit)
        AapsV3ComboInventoryPage(snapshotId, pumpBinding, sha256, reads.size, totalBytes,
            unscopedCorruptReads, writerFailed, query.afterReadId, end < reads.size, reads.subList(start, end))
            .also { it.requireFor(query) }
    }
    override fun toString() = "ComboInventory(redacted)"
    companion object {
        const val MAX_READS = 4096
        const val MAX_BYTES = 32L * 1024 * 1024
        fun create(snapshotId: String, pumpBinding: String, entries: List<AapsV3ComboInventoryEntry>,
                   unscopedCorruptReads: Int = 0, writerFailed: Boolean = false): AapsV3ComboInventory = inventoryOperation {
            require(entries.size <= MAX_READS)
            val copy = entries.toList()
            requireOrdered(copy)
            requireHeader(snapshotId, pumpBinding, copy.size, copy.sumOf { it.byteCount.toLong() }, unscopedCorruptReads)
            AapsV3ComboInventory(snapshotId, pumpBinding, copy, unscopedCorruptReads, writerFailed)
        }
    }
}

internal class AapsV3ComboInventoryPage internal constructor(
    val snapshotId: String, val pumpBinding: String, val sha256: String,
    val totalReads: Int, val totalBytes: Long, val unscopedCorruptReads: Int, val writerFailed: Boolean,
    val afterReadId: String?, val hasMore: Boolean, entries: List<AapsV3ComboInventoryEntry>
) {
    val reads: List<AapsV3ComboInventoryEntry> = Collections.unmodifiableList(ArrayList(entries))
    internal fun requireFor(query: AapsV3ComboReadSpec.Inventory) {
        query.requireValid()
        requireHeader(snapshotId, pumpBinding, totalReads, totalBytes, unscopedCorruptReads)
        require(HEX.matches(sha256) && afterReadId == query.afterReadId)
        require(query.snapshotId == null || query.snapshotId == snapshotId)
        requireOrdered(reads)
        require(reads.size <= query.limit && reads.size <= totalReads)
        require(reads.sumOf { it.byteCount.toLong() } <= totalBytes)
        require(reads.all { afterReadId == null || it.readId > afterReadId })
        if (totalReads == 0) require(reads.isEmpty() && !hasMore && afterReadId == null)
        else require(reads.isNotEmpty())
        if (hasMore) require(reads.size == query.limit && totalReads > reads.size)
        if (afterReadId == null && !hasMore) require(reads.size == totalReads)
    }
    internal fun toJson() = buildJsonObject {
        put("version", 1); put("snapshotId", snapshotId); put("pumpBinding", pumpBinding); put("sha256", sha256)
        put("totalReads", totalReads); put("totalBytes", totalBytes); put("unscopedCorruptReads", unscopedCorruptReads)
        put("writerFailed", writerFailed); put("hasMore", hasMore); put("reads", JsonArray(reads.map { it.toJson() }))
    }
    override fun toString() = "ComboInventoryPage(redacted)"
    companion object {
        const val MAX_PAGE_BYTES = 16_384
        fun parse(bytes: ByteArray, query: AapsV3ComboReadSpec.Inventory): AapsV3ComboInventoryPage = inventoryOperation {
            val row = HistoryJson.parse(bytes, MAX_PAGE_BYTES)
            HistoryJson.fields(row, setOf("version", "snapshotId", "pumpBinding", "sha256", "totalReads", "totalBytes",
                "unscopedCorruptReads", "writerFailed", "hasMore", "reads"))
            require(HistoryJson.long(row.getValue("version")) == 1L)
            fun count(key: String): Int {
                val value = HistoryJson.long(row.getValue(key))
                require(value in 0..AapsV3ComboInventory.MAX_READS.toLong())
                return value.toInt()
            }
            val entries = HistoryJson.array(row.getValue("reads"))
            require(entries.size <= 32)
            AapsV3ComboInventoryPage(HistoryJson.string(row.getValue("snapshotId")),
                HistoryJson.string(row.getValue("pumpBinding")), HistoryJson.string(row.getValue("sha256")),
                count("totalReads"), HistoryJson.long(row.getValue("totalBytes")), count("unscopedCorruptReads"),
                HistoryJson.bool(row.getValue("writerFailed")), query.afterReadId, HistoryJson.bool(row.getValue("hasMore")),
                entries.map(AapsV3ComboInventoryEntry::parse)).also { it.requireFor(query) }
        }
    }
}

/** Bounded in-memory assembly; no receipt, durable resume, or clinical permission. */
internal class AapsV3ComboInventoryAssembly : AutoCloseable {
    private val entries = mutableListOf<AapsV3ComboInventoryEntry>()
    private var first: AapsV3ComboInventoryPage? = null
    private var previous: AapsV3ComboInventoryBinding? = null
    private var previousBody: JsonObject? = null
    private var result: AapsV3ComboInventory? = null
    private var failed = false

    @Synchronized fun accept(binding: AapsV3ComboInventoryBinding, page: AapsV3ComboInventoryPage, now: Long) {
        try {
            check(!failed)
            binding.requireCurrent(now); page.requireFor(binding.query)
            val body = page.toJson()
            if (binding == previous && body == previousBody) return
            check(result == null)
            val old = previous
            if (old == null) {
                require(binding.query.snapshotId == null && binding.query.afterReadId == null)
                first = page
            } else {
                require(binding.clientId == old.clientId && binding.masterInstallId == old.masterInstallId)
                require(binding.counter > old.counter && binding.requestId != old.requestId)
                require(binding.query.snapshotId == first!!.snapshotId && binding.query.afterReadId == entries.last().readId)
                val original = first!!
                require(page.snapshotId == original.snapshotId && page.pumpBinding == original.pumpBinding &&
                    page.sha256 == original.sha256 && page.totalReads == original.totalReads &&
                    page.totalBytes == original.totalBytes && page.unscopedCorruptReads == original.unscopedCorruptReads &&
                    page.writerFailed == original.writerFailed)
            }
            require(entries.size + page.reads.size <= page.totalReads)
            entries.addAll(page.reads)
            requireOrdered(entries)
            require(entries.sumOf { it.byteCount.toLong() } <= page.totalBytes)
            if (page.hasMore) require(entries.size < page.totalReads)
            else {
                require(entries.size == page.totalReads)
                val complete = AapsV3ComboInventory.create(page.snapshotId, page.pumpBinding, entries,
                    page.unscopedCorruptReads, page.writerFailed)
                require(complete.totalBytes == page.totalBytes && complete.sha256 == page.sha256)
                result = complete
            }
            previous = binding; previousBody = body
        } catch (error: Exception) {
            close()
            if (error is CancellationException) throw error
            throw AapsV3ComboInventoryFailure()
        }
    }
    @Synchronized fun completed(): AapsV3ComboInventory? = result
    @Synchronized override fun close() { failed = true; entries.clear(); first = null; previous = null; previousBody = null; result = null }
    override fun toString() = "ComboInventoryAssembly(redacted)"
}

private val HEX = Regex("[0-9a-f]{64}")
private fun requireOrdered(reads: List<AapsV3ComboInventoryEntry>) {
    require(reads.zipWithNext().all { (a, b) -> a.readId < b.readId })
}
private fun requireHeader(snapshot: String, binding: String, reads: Int, bytes: Long, unscoped: Int) {
    HistoryJson.uuid(snapshot)
    require(HEX.matches(binding) && reads in 0..4096 && unscoped in 0..4096 && reads + unscoped <= 4096)
    require(bytes in reads.toLong()..minOf(AapsV3ComboInventory.MAX_BYTES, reads.toLong() * AapsV3ComboJournalRead.MAX_READ_BYTES))
}
private fun inventoryDigest(snapshot: String, binding: String, reads: List<AapsV3ComboInventoryEntry>,
                            bytes: Long, unscoped: Int, failed: Boolean): String {
    val digest = MessageDigest.getInstance("SHA-256")
    fun line(values: List<Any>) { digest.update((values.joinToString("|") + "\n").toByteArray(Charsets.US_ASCII)) }
    line(listOf("aaps-combo-inventory-v1", snapshot, binding, reads.size, bytes, unscoped, failed))
    reads.forEach { line(listOf(it.readId, it.byteCount, it.sha256, it.closed, it.corrupt, it.adverse, it.readFailed)) }
    return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
}
internal inline fun <T> inventoryOperation(block: () -> T): T = try { block() }
catch (error: CancellationException) { throw error }
catch (_: Exception) { throw AapsV3ComboInventoryFailure() }
