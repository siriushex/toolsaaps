package io.aaps.copilot.service

import androidx.room.withTransaction
import io.aaps.copilot.data.local.CopilotDatabase
import io.aaps.copilot.data.local.entity.AapsV3TargetJournalEntity
import io.aaps.copilot.data.repository.HistoryJson
import io.aaps.copilot.data.repository.LocalNightscoutV3Store
import io.aaps.copilot.data.repository.LocalV3Collection
import io.aaps.copilot.data.repository.LocalV3DocumentInput
import io.aaps.copilot.data.repository.LocalV3Write
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

@Serializable
internal enum class AapsV3TargetPhase { PREPARED, PREVIEW, COMMITTED, UNKNOWN, APPLIED, REJECTED, EXPIRED, EXPIRED_NO_COMMIT }

internal class AapsV3TargetJournalSnapshot(
    val phase: AapsV3TargetPhase,
    val counter: Long,
    val publication: LocalV3Write? = null
) {
    override fun toString() = "AapsV3TargetJournalSnapshot($phase)"
}

internal class AapsV3TargetJournalFailure : IllegalStateException("native_target_journal_unavailable")

/** Counter, single owner, receipt and wire outbox share one Room transaction. No automatic replay. */
internal class AapsV3TargetJournal(
    private val db: CopilotDatabase,
    private val cipher: AapsV3JournalCipher,
    private val maximumRows: Long = 100_000
) {
    private val dao = db.aapsV3TargetJournalDao()
    private val json = Json { encodeDefaults = true }
    init { require(maximumRows in 2..100_000) }

    /** Binding is not pairing authorization. Secure enrollment must verify owner proof separately. */
    suspend fun bind(clientId: String) = transaction {
        require(CLIENT_ID.matches(clientId))
        val current = rootOrNull()
        if (current == null) {
            check(dao.count() == 0L)
            put(ROOT, json.encodeToString(Root(clientId)))
        } else check(current.clientId == clientId)
    }

    suspend fun prepare(operationId: String, target: AapsV3TargetSpec, protocol: AapsV3TargetProtocol,
                        nowMs: Long, checkGuard: () -> Unit): AapsV3TargetJournalSnapshot = transaction {
        val root = root()
        val id = commandKey(operationId)
        val marker = "copilot:$operationId"
        check(target.notes.isEmpty() || target.notes == marker)
        val candidate = protocol.prepare(root.clientId, next(root.counter), nowMs, target.copy(notes = marker))
        val fingerprint = digest(candidate.json.getValue("payload").jsonPrimitive.content)
        val previous = recordOrNull(id)
        if (previous != null) {
            check(previous.fingerprint == fingerprint)
            return@transaction previous.snapshot()
        }
        check(root.pending == null && nowMs >= root.lastIssuedAt)
        checkGuard()
        val counter = candidate.json.getValue("counter").jsonPrimitive.long
        val record = Record(fingerprint, AapsV3TargetPhase.PREPARED, counter, candidate.json)
        val publication = publish(candidate)
        put(id, json.encodeToString(record))
        put(ROOT, json.encodeToString(root.copy(counter = counter, lastIssuedAt = nowMs, pending = id)))
        record.snapshot(publication)
    }

    suspend fun commit(operationId: String, protocol: AapsV3TargetProtocol, nowMs: Long,
                       checkGuard: () -> Unit): AapsV3TargetJournalSnapshot = transaction {
        val root = root(); val id = commandKey(operationId); val previous = record(id)
        if (previous.phase in setOf(AapsV3TargetPhase.COMMITTED, AapsV3TargetPhase.UNKNOWN,
                AapsV3TargetPhase.APPLIED, AapsV3TargetPhase.REJECTED, AapsV3TargetPhase.EXPIRED)) {
            return@transaction previous.snapshot()
        }
        check(root.pending == id && previous.phase == AapsV3TargetPhase.PREVIEW && nowMs >= root.lastIssuedAt)
        checkGuard()
        val request = protocol.commit(AapsV3TargetRequest(checkNotNull(previous.prepare)),
            checkNotNull(previous.preview), next(root.counter), nowMs)
        val counter = request.json.getValue("counter").jsonPrimitive.long
        val committed = previous.copy(phase = AapsV3TargetPhase.COMMITTED, counter = counter, commit = request.json)
        val publication = publish(request)
        put(id, json.encodeToString(committed))
        put(ROOT, json.encodeToString(root.copy(counter = counter, lastIssuedAt = nowMs)))
        committed.snapshot(publication)
    }

    suspend fun acknowledge(operationId: String, protocol: AapsV3TargetProtocol, ack: JsonObject,
                            nowMs: Long): AapsV3TargetJournalSnapshot = transaction {
        val root = root(); val id = commandKey(operationId); val previous = record(id)
        if (previous.phase in TERMINAL) return@transaction previous.snapshot()
        check(root.pending == id)
        val isCommit = previous.commit != null
        val request = AapsV3TargetRequest(checkNotNull(previous.commit ?: previous.prepare))
        val phase = when (protocol.classify(request, ack, nowMs)) {
            AapsV3TargetAck.PREVIEW -> if (!isCommit) AapsV3TargetPhase.PREVIEW else previous.phase
            AapsV3TargetAck.APPLIED -> if (isCommit) AapsV3TargetPhase.APPLIED else previous.phase
            AapsV3TargetAck.REJECTED -> AapsV3TargetPhase.REJECTED
            AapsV3TargetAck.EXPIRED -> if (isCommit) AapsV3TargetPhase.EXPIRED else AapsV3TargetPhase.EXPIRED_NO_COMMIT
            else -> previous.phase
        }
        if (phase == previous.phase) return@transaction previous.snapshot()
        val updated = previous.copy(phase = phase, preview = if (phase == AapsV3TargetPhase.PREVIEW) ack else previous.preview)
            .compactIfTerminal()
        put(id, json.encodeToString(updated))
        if (phase in TERMINAL) put(ROOT, json.encodeToString(root.copy(pending = null)))
        updated.snapshot()
    }

    /** A prepare cannot apply anything; a published commit with no result must continue holding ownership. */
    suspend fun expire(nowMs: Long): AapsV3TargetJournalSnapshot? = transaction {
        check(nowMs in 1..LocalNightscoutV3Store.MAX_TIME)
        val root = root(); val id = root.pending ?: return@transaction null
        val previous = record(id)
        val request = checkNotNull(previous.commit ?: previous.prepare)
        if (nowMs <= request.getValue("validUntil").jsonPrimitive.long) return@transaction previous.snapshot()
        val phase = if (previous.commit == null) AapsV3TargetPhase.EXPIRED_NO_COMMIT else AapsV3TargetPhase.UNKNOWN
        if (phase == previous.phase) return@transaction previous.snapshot()
        val updated = previous.copy(phase = phase).compactIfTerminal()
        put(id, json.encodeToString(updated))
        if (phase in TERMINAL) put(ROOT, json.encodeToString(root.copy(pending = null)))
        updated.snapshot()
    }

    suspend fun pending(): AapsV3TargetJournalSnapshot? = transaction {
        val root = root()
        root.pending?.let {
            val pending = record(it)
            check(pending.phase !in TERMINAL && pending.counter == root.counter)
            pending.snapshot()
        }
    }

    private suspend fun publish(request: AapsV3TargetRequest): LocalV3Write {
        val clientId = request.json.getValue("clientId").jsonPrimitive.content
        val type = request.json.getValue("type").jsonPrimitive.content
        val id = "aaps_clientcontrol_cmd_${type}_$clientId"
        val body = buildJsonObject {
            put("date", DOC_DATE); put("utcOffset", 0); put("app", "AAPS"); put("schemaVersion", 1)
            put("envelope", request.json)
        }
        val store = LocalNightscoutV3Store(db)
        val previous = store.get(LocalV3Collection.SETTINGS, id)
        check(previous == null || previous.source == "local-v3-target")
        val input = LocalV3DocumentInput(LocalV3Collection.SETTINGS, id, "local-v3-target", DOC_DATE, body.toString())
        return if (previous == null) store.create(input) else store.update(input, previous.modifiedRevision)
    }

    private suspend fun rootOrNull(): Root? = read(ROOT)?.let {
        json.decodeFromJsonElement<Root>(it).also { root ->
            check(root.version == 1 && CLIENT_ID.matches(root.clientId) && root.counter >= 0)
            check(root.lastIssuedAt in 0..LocalNightscoutV3Store.MAX_TIME)
            check(root.pending == null || COMMAND_KEY.matches(root.pending))
        }
    }
    private suspend fun root(): Root = checkNotNull(rootOrNull())
    private suspend fun record(id: String): Record = checkNotNull(recordOrNull(id))
    private suspend fun recordOrNull(id: String): Record? = read(id)?.let {
        json.decodeFromJsonElement<Record>(it).also { record ->
            check(record.version == 1 && HEX.matches(record.fingerprint) && record.counter > 0)
            if (record.phase !in TERMINAL) {
                check(record.prepare != null)
                check((record.commit != null) == (record.phase in setOf(AapsV3TargetPhase.COMMITTED, AapsV3TargetPhase.UNKNOWN)))
                if (record.phase == AapsV3TargetPhase.PREVIEW) check(record.preview != null)
            }
        }
    }
    private suspend fun read(id: String): JsonObject? = dao.get(id)?.let {
        check(it.revision > 0 && it.sealed.size in 28..65_536)
        val plain = cipher.open(id, it.revision, it.sealed)
        try { HistoryJson.parse(plain, 65_000) } finally { plain.fill(0) }
    }
    private suspend fun put(id: String, value: String) {
        val previous = dao.get(id)
        if (previous == null) check(dao.count() < maximumRows)
        val revision = next(previous?.revision ?: 0)
        val plain = HistoryJson.encodeUtf8(value)
        try {
            check(plain.size <= 65_000)
            val sealed = cipher.seal(id, revision, plain)
            check(sealed.size in 28..65_536)
            dao.put(AapsV3TargetJournalEntity(id, revision, sealed))
        } finally { plain.fill(0) }
    }
    private fun commandKey(id: String): String {
        require(id.length in 1..256 && id.all { it.code in 33..126 })
        return "command:${digest(id)}"
    }
    private fun digest(value: String) = MessageDigest.getInstance("SHA-256").digest(HistoryJson.encodeUtf8(value))
        .joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun next(counter: Long): Long { check(counter in 0 until Long.MAX_VALUE); return counter + 1 }
    private suspend fun <T> transaction(block: suspend () -> T): T = try { db.withTransaction { block() } }
    catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { throw AapsV3TargetJournalFailure() }

    @Serializable private data class Root(val clientId: String, val counter: Long = 0,
        val lastIssuedAt: Long = 0, val pending: String? = null, val version: Int = 1)
    @Serializable private data class Record(val fingerprint: String, val phase: AapsV3TargetPhase,
        val counter: Long, val prepare: JsonObject? = null, val preview: JsonObject? = null,
        val commit: JsonObject? = null, val version: Int = 1) {
        fun snapshot(publication: LocalV3Write? = null) = AapsV3TargetJournalSnapshot(phase, counter, publication)
        fun compactIfTerminal() = if (phase in TERMINAL) copy(prepare = null, preview = null, commit = null) else this
    }
    private companion object {
        const val ROOT = "owner"
        const val DOC_DATE = 946_684_800_001L
        val HEX = Regex("[0-9a-f]{64}")
        val COMMAND_KEY = Regex("command:[0-9a-f]{64}")
        val CLIENT_ID = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
        val TERMINAL = setOf(AapsV3TargetPhase.APPLIED, AapsV3TargetPhase.REJECTED,
            AapsV3TargetPhase.EXPIRED, AapsV3TargetPhase.EXPIRED_NO_COMMIT)
    }
}
