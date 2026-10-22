package io.aaps.copilot.data.repository

import androidx.room.withTransaction
import io.aaps.copilot.data.local.CopilotDatabase
import io.aaps.copilot.data.local.dao.LocalV3DocumentPosition
import io.aaps.copilot.data.local.entity.LocalNightscoutV3ClockEntity
import io.aaps.copilot.data.local.entity.LocalNightscoutV3DocumentEntity
import kotlinx.coroutines.CancellationException

internal enum class LocalV3Collection(val wireName: String) {
    ENTRIES("entries"), TREATMENTS("treatments"), DEVICE_STATUS("devicestatus"),
    FOOD("food"), PROFILE("profile"), SETTINGS("settings")
}

internal enum class LocalV3StoreError { INVALID, CONFLICT, NOT_FOUND, READ_ONLY, CAPACITY, CLOCK, STATE, PAGE_CAPACITY }
internal class LocalV3StoreException(val reason: LocalV3StoreError) : IllegalStateException("local_v3_${reason.name.lowercase()}")

internal data class LocalV3DocumentInput(
    val collection: LocalV3Collection,
    val identifier: String,
    val source: String,
    val eventAtMs: Long?,
    val payloadJson: String,
    val readOnly: Boolean = false
) {
    override fun toString() = "LocalV3DocumentInput(redacted)"
}

internal data class LocalV3Write(val document: LocalNightscoutV3DocumentEntity, val changed: Boolean)
internal data class LocalV3Page(
    val documents: List<LocalNightscoutV3DocumentEntity>,
    val nextCursor: Long,
    val collectionRevision: Long,
    val hasMore: Boolean
)

/** Latest retained identities, including tombstones. Not evidence of upstream backfill completion. */
internal sealed interface LocalV3RetainedSnapshot {
    val collectionRevision: Long
    data class Complete(override val collectionRevision: Long, val documents: List<LocalNightscoutV3DocumentEntity>) : LocalV3RetainedSnapshot {
        override fun toString() = "LocalV3RetainedSnapshot.Complete(redacted)"
    }
    data class OverLimit(override val collectionRevision: Long) : LocalV3RetainedSnapshot
}

internal data class LocalV3StoreLimits(
    val documents: Long = 100_000,
    val payloadBytes: Long = 64L * 1024 * 1024,
    val pageBytes: Long = 1024 * 1024,
    val tieRows: Int = 1000
) {
    init {
        require(documents in 1..100_000 && payloadBytes in 1..64L * 1024 * 1024)
        require(pageBytes in 1..1024L * 1024 && tieRows in 1..1000)
    }
}

/** Wire storage only: no clinical projection, command dispatch or background owner. */
internal class LocalNightscoutV3Store(
    private val db: CopilotDatabase,
    private val now: () -> Long = System::currentTimeMillis,
    private val limits: LocalV3StoreLimits = LocalV3StoreLimits()
) {
    private val dao = db.localNightscoutV3Dao()

    suspend fun create(input: LocalV3DocumentInput): LocalV3Write {
        val candidate = validated(input)
        return db.withTransaction {
            val previous = dao.get(candidate.collection, candidate.identifier)
            if (previous != null) {
                if (!previous.deleted && sameContent(previous, candidate)) return@withTransaction LocalV3Write(previous, false)
                fail(LocalV3StoreError.CONFLICT)
            }
            commit(candidate, null)
        }
    }

    suspend fun update(input: LocalV3DocumentInput, expectedRevision: Long): LocalV3Write {
        val candidate = validated(input)
        revision(expectedRevision)
        return db.withTransaction {
            val previous = dao.get(candidate.collection, candidate.identifier) ?: fail(LocalV3StoreError.NOT_FOUND)
            if (previous.deleted || previous.source != candidate.source) fail(LocalV3StoreError.CONFLICT)
            if (expectedRevision <= previous.modifiedRevision && sameContent(previous, candidate)) {
                return@withTransaction LocalV3Write(previous, false)
            }
            if (previous.readOnly) fail(LocalV3StoreError.READ_ONLY)
            if (previous.modifiedRevision != expectedRevision) fail(LocalV3StoreError.CONFLICT)
            commit(candidate.copy(createdRevision = previous.createdRevision), previous)
        }
    }

    suspend fun delete(collection: LocalV3Collection, id: String, expectedRevision: Long): LocalV3Write {
        identifier(id)
        revision(expectedRevision)
        return db.withTransaction {
            val previous = dao.get(collection.wireName, id) ?: fail(LocalV3StoreError.NOT_FOUND)
            if (previous.deleted && expectedRevision <= previous.modifiedRevision) return@withTransaction LocalV3Write(previous, false)
            if (previous.readOnly) fail(LocalV3StoreError.READ_ONLY)
            if (previous.modifiedRevision != expectedRevision) fail(LocalV3StoreError.CONFLICT)
            commit(previous.copy(deleted = true), previous)
        }
    }

    suspend fun get(collection: LocalV3Collection, id: String): LocalNightscoutV3DocumentEntity? {
        identifier(id)
        return dao.get(collection.wireName, id)
    }

    /** One-use PIN offers must not retain wrapped secrets in a readable deletion record. */
    suspend fun erasePairingOfferPayload(id: String): LocalV3Write = db.withTransaction {
        if (!Regex("aaps_clientcontrol_offer_[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}").matches(id)) {
            fail(LocalV3StoreError.INVALID)
        }
        val previous = dao.get(LocalV3Collection.SETTINGS.wireName, id) ?: fail(LocalV3StoreError.NOT_FOUND)
        if (previous.source != "local-v3-owner" || previous.readOnly) fail(LocalV3StoreError.READ_ONLY)
        if (previous.deleted && previous.payloadJson == "{}") return@withTransaction LocalV3Write(previous, false)
        // Retain only a non-secret tombstone so collection revision and restart replay cannot regress.
        commit(previous.copy(payloadJson = "{}", payloadBytes = 2, deleted = true), previous)
    }

    suspend fun lastModified(): Map<LocalV3Collection, Long> = db.withTransaction {
        LocalV3Collection.entries.associateWith { dao.head(it.wireName) }
    }

    /** Refuse a partial snapshot before loading payloads, without splitting transactions across pages. */
    suspend fun retainedSnapshot(collection: LocalV3Collection, maxRows: Int, maxPayloadBytes: Long): LocalV3RetainedSnapshot {
        require(maxRows in 1..10_000 && maxPayloadBytes in 1..4L * 1024 * 1024)
        return db.withTransaction {
            val head = dao.head(collection.wireName)
            val positions = dao.historyPositions(collection.wireName, 0, head, maxRows + 1)
            if (positions.any { it.payloadBytes !in 1..MAX_PAYLOAD_BYTES }) fail(LocalV3StoreError.STATE)
            if (positions.size > maxRows || positions.sumOf { it.payloadBytes.toLong() } > maxPayloadBytes) {
                return@withTransaction LocalV3RetainedSnapshot.OverLimit(head)
            }
            val rows = dao.history(collection.wireName, 0, head, positions.size)
            if (rows.size != positions.size) fail(LocalV3StoreError.STATE)
            LocalV3RetainedSnapshot.Complete(head, rows)
        }
    }

    suspend fun history(collection: LocalV3Collection, after: Long, limit: Int): LocalV3Page {
        pageArguments(after, limit)
        return db.withTransaction {
            val head = dao.head(collection.wireName)
            val candidates = dao.historyPositions(collection.wireName, after, head, limit + 1)
            val positions = candidates.take(limit)
            checkPageBytes(positions)
            val cursor = positions.lastOrNull()?.modifiedRevision ?: after
            val rows = dao.history(collection.wireName, after, cursor, positions.size)
            LocalV3Page(rows, cursor, head, candidates.size > limit)
        }
    }

    suspend fun chronological(collection: LocalV3Collection, after: Long, limit: Int): LocalV3Page {
        pageArguments(after, limit)
        return db.withTransaction {
            val head = dao.head(collection.wireName)
            val candidates = dao.chronologicalPositions(collection.wireName, after, limit)
            var positions = candidates
            val boundary = positions.lastOrNull()?.eventAtMs
            // AAPS first-load resumes using time > cursor, so never split its final time group.
            if (boundary != null) {
                val ties = dao.atTimePositions(collection.wireName, boundary, limits.tieRows + 1)
                if (ties.size > limits.tieRows) fail(LocalV3StoreError.PAGE_CAPACITY)
                positions = positions.takeWhile { it.eventAtMs != boundary } + ties
            }
            checkPageBytes(positions)
            val cursor = positions.lastOrNull()?.eventAtMs ?: after
            val rows = dao.chronological(collection.wireName, after, cursor, positions.size)
            LocalV3Page(rows, cursor, head, dao.hasAfterTime(collection.wireName, cursor))
        }
    }

    suspend fun latest(collection: LocalV3Collection, limit: Int): LocalV3Page {
        pageArguments(0, limit)
        return db.withTransaction {
            val candidates = dao.latestPositions(collection.wireName, limit + 1)
            checkPageBytes(candidates.take(limit))
            LocalV3Page(dao.latest(collection.wireName, limit), 0, dao.head(collection.wireName), candidates.size > limit)
        }
    }

    private suspend fun commit(candidate: LocalNightscoutV3DocumentEntity, previous: LocalNightscoutV3DocumentEntity?): LocalV3Write {
        val clock = dao.clock() ?: run {
            if (dao.count() != 0L) fail(LocalV3StoreError.STATE)
            LocalNightscoutV3ClockEntity(revision = 0, documentCount = 0, payloadBytes = 0)
        }
        if (clock.revision !in 0 until MAX_TIME || clock.documentCount !in 0..100_000 ||
            clock.payloadBytes !in 0..64L * 1024 * 1024 ||
            (previous != null && (previous.modifiedRevision > clock.revision || previous.payloadBytes < 0))) {
            fail(LocalV3StoreError.STATE)
        }
        val time = now()
        if (time !in 1..MAX_TIME) fail(LocalV3StoreError.CLOCK)
        val next = maxOf(time, clock.revision + 1)
        val count = clock.documentCount + if (previous == null) 1 else 0
        val bytes = clock.payloadBytes - (previous?.payloadBytes ?: 0) + candidate.payloadBytes
        if (count !in 1..limits.documents || bytes !in 0..limits.payloadBytes) fail(LocalV3StoreError.CAPACITY)
        val committed = candidate.copy(
            createdRevision = previous?.createdRevision ?: next,
            modifiedRevision = next
        )
        dao.put(committed)
        dao.putClock(LocalNightscoutV3ClockEntity(revision = next, documentCount = count, payloadBytes = bytes))
        return LocalV3Write(committed, true)
    }

    private fun validated(input: LocalV3DocumentInput): LocalNightscoutV3DocumentEntity {
        identifier(input.identifier)
        if (!ID.matches(input.source) || input.eventAtMs?.let { it !in 1..MAX_TIME } == true ||
            (input.collection in setOf(LocalV3Collection.ENTRIES, LocalV3Collection.TREATMENTS) && input.eventAtMs == null) ||
            input.payloadJson.length > MAX_PAYLOAD_BYTES) fail(LocalV3StoreError.INVALID)
        val bytes = try {
            val body = HistoryJson.parse(HistoryJson.encodeUtf8(input.payloadJson), MAX_PAYLOAD_BYTES)
            if (body.keys.any { it in SERVER_FIELDS }) fail(LocalV3StoreError.INVALID)
            HistoryJson.bytes(body).also { if (it.size > MAX_PAYLOAD_BYTES) fail(LocalV3StoreError.INVALID) }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            fail(LocalV3StoreError.INVALID)
        }
        return LocalNightscoutV3DocumentEntity(input.collection.wireName, input.identifier, input.source,
            input.eventAtMs, HistoryJson.decodeUtf8(bytes), bytes.size, 0, 0, false, input.readOnly)
    }

    private fun sameContent(a: LocalNightscoutV3DocumentEntity, b: LocalNightscoutV3DocumentEntity) =
        a.source == b.source && a.eventAtMs == b.eventAtMs && a.payloadJson == b.payloadJson && a.readOnly == b.readOnly

    private fun identifier(value: String) { if (!ID.matches(value)) fail(LocalV3StoreError.INVALID) }
    private fun revision(value: Long) { if (value !in 1..MAX_TIME) fail(LocalV3StoreError.INVALID) }
    private fun pageArguments(after: Long, limit: Int) {
        if (after !in 0..MAX_TIME || limit !in 1..1000) fail(LocalV3StoreError.INVALID)
    }
    private fun checkPageBytes(rows: List<LocalV3DocumentPosition>) {
        if (rows.any { it.payloadBytes !in 1..MAX_PAYLOAD_BYTES }) fail(LocalV3StoreError.STATE)
        if (rows.sumOf { it.payloadBytes.toLong() } > limits.pageBytes) fail(LocalV3StoreError.PAGE_CAPACITY)
    }
    private fun fail(reason: LocalV3StoreError): Nothing = throw LocalV3StoreException(reason)

    companion object {
        const val MAX_TIME = 253_402_300_799_999L
        const val MAX_PAYLOAD_BYTES = 65_536
        private val ID = Regex("[A-Za-z0-9][A-Za-z0-9_.:-]{0,127}")
        private val SERVER_FIELDS = setOf("_id", "identifier", "srvCreated", "srvModified", "isValid", "isReadOnly", "subject", "modifiedBy")
    }
}
