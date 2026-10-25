package io.aaps.copilot.service

import android.annotation.SuppressLint
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

@Serializable internal enum class AapsV3PairingPhase { WAITING, ACTIVE, REVOKED }
internal class AapsV3PairingSnapshot(val phase: AapsV3PairingPhase, val clientId: String,
    val masterInstallId: String, val controlEnabled: Boolean, val lastPongAt: Long, val pongCounter: Long,
    val publication: LocalV3Write? = null) {
    override fun toString() = "AapsV3PairingSnapshot($phase)"
}

internal class AapsV3TargetJournalSnapshot(
    val phase: AapsV3TargetPhase,
    val counter: Long,
    val publication: LocalV3Write? = null,
    val operationId: String? = null
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

    /** Failed PIN attempts survive restart; no PIN or wrapped offer is retained here. */
    suspend fun reservePairingAttempt(nowMs: Long) = transaction {
        check(nowMs in 1..LocalNightscoutV3Store.MAX_TIME)
        val previous = read(ATTEMPTS)?.let { json.decodeFromJsonElement<Attempts>(it) }
        check(previous == null || nowMs >= previous.lastAt)
        val count = if (previous != null && nowMs - previous.startedAt < 600_000) previous.count + 1 else 1
        check(count <= 5)
        put(ATTEMPTS, json.encodeToString(Attempts(if (count == 1) nowMs else previous!!.startedAt, nowMs, count)))
    }

    suspend fun hasNativeState(): Boolean = transaction {
        dao.get(ROOT) != null || dao.get(PAIRING) != null ||
            dao.count() > (if (dao.get(ATTEMPTS) == null) 0 else 1) || db.localNightscoutV3Dao().hasNativeControlOutbox()
    }

    suspend fun enroll(credential: AapsV3PairingCredential, nowMs: Long): AapsV3PairingSnapshot = transaction {
        val oldRoot = rootOrNull()
        val oldPair = pairingOrNull()
        if (oldPair != null && oldPair.clientId == credential.clientId) {
            check(oldPair.masterInstallId == credential.masterInstallId)
            credential.withSecret { check(secretHex(it) == oldPair.secretHex) }
            return@transaction oldPair.snapshot()
        }
        check(CLIENT_ID.matches(credential.clientId) && nowMs in 1..LocalNightscoutV3Store.MAX_TIME)
        check(credential.expiresAt - nowMs in 1..600_000)
        val attempt = read(ATTEMPTS)?.let { json.decodeFromJsonElement<Attempts>(it) }
        check(attempt == null || nowMs >= attempt.lastAt)
        check(dao.get("retired-owner:${credential.clientId}") == null)
        check(!db.actionCommandDao().hasPendingTempTarget())
        if (oldRoot != null) {
            check(oldRoot.pending == null && oldPair?.phase == AapsV3PairingPhase.REVOKED)
            // New owner consent may replace a different identity, never reset the old counter.
            put("retired-owner:${oldRoot.clientId}", json.encodeToString(oldRoot))
            put("retired-pair:${oldRoot.clientId}", json.encodeToString(oldPair))
        } else check(oldPair == null && dao.count() == if (attempt == null) 0L else 1L)
        val secret = credential.withSecret { it.copyOf() }
        try {
            val hello = AapsV3SessionProtocol(secret).use { it.hello(credential.clientId, 1, nowMs, credential.expiresAt) }
            val publication = publish(AapsV3TargetRequest(hello))
            val pair = Pairing(credential.clientId, credential.masterInstallId, secretHex(secret),
                credential.expiresAt, publication.document.modifiedRevision)
            put(ROOT, json.encodeToString(Root(credential.clientId, 1, nowMs)))
            put(PAIRING, json.encodeToString(pair))
            pair.snapshot(publication)
        } finally { secret.fill(0) }
    }

    suspend fun pairingSnapshot(): AapsV3PairingSnapshot? = transaction {
        pairingOrNull()?.also { check(root().clientId == it.clientId) }?.snapshot()
    }

    suspend fun revokePairing(): Unit = transaction {
        val pair = pairing()
        if (pair.phase != AapsV3PairingPhase.REVOKED) put(PAIRING, json.encodeToString(pair.copy(
            phase = AapsV3PairingPhase.REVOKED, controlEnabled = false)))
    }

    suspend fun observeRoster(row: io.aaps.copilot.data.local.entity.LocalNightscoutV3DocumentEntity): AapsV3PairingSnapshot = transaction {
        val pair = pairing()
        if (row.collection != "settings" || row.identifier != "aaps" || row.source != "local-v3-owner" ||
            row.modifiedRevision <= maxOf(pair.helloRevision, pair.rosterRevision)) return@transaction pair.snapshot()
        val roster = try {
            check(!row.deleted)
            val body = HistoryJson.parse(row.payloadJson.toByteArray(), 65_536)
            check(HistoryJson.long(body.getValue("schemaVersion")) == 1L)
            val config = HistoryJson.obj(body.getValue("runningConfig"))
            val ids = HistoryJson.array(HistoryJson.obj(config.getValue("authorizedClients")).getValue("clientIds"))
            check(ids.size <= 1024)
            val names = ids.map(HistoryJson::string)
            check(names.all { CLIENT_ID.matches(it) } && names.distinct().size == names.size)
            val enabled = HistoryJson.obj(config.getValue("syncedPrefs"))["ns_allow_client_control"] == JsonPrimitive("true")
            (pair.clientId in names) to enabled
        } catch (_: Exception) { null }
        val phase = when {
            pair.phase == AapsV3PairingPhase.REVOKED -> pair.phase
            roster?.first == true -> AapsV3PairingPhase.ACTIVE
            pair.phase == AapsV3PairingPhase.ACTIVE && (row.deleted || roster?.first == false) -> AapsV3PairingPhase.REVOKED
            else -> pair.phase
        }
        val updated = pair.copy(phase = phase, rosterRevision = row.modifiedRevision,
            controlEnabled = phase == AapsV3PairingPhase.ACTIVE && roster?.second == true)
        put(PAIRING, json.encodeToString(updated))
        updated.snapshot()
    }

    suspend fun probe(nowMs: Long, sessionStartedAt: Long = 0): LocalV3Write? = transaction {
        val root = root(); val pair = pairing()
        if (root.pending != null || pair.phase != AapsV3PairingPhase.ACTIVE || !pair.controlEnabled) return@transaction null
        check(nowMs >= root.lastIssuedAt)
        val previous = read(PROBE)?.let { json.decodeFromJsonElement<Probe>(it) }
        val previousAt = previous?.request?.getValue("timestamp")?.jsonPrimitive?.long
        if (previous?.request?.getValue("clientId") == JsonPrimitive(pair.clientId) &&
            previousAt != null && previousAt >= sessionStartedAt && nowMs - previousAt < 60_000) return@transaction null
        val secret = secretBytes(pair)
        try {
            val request = AapsV3SessionProtocol(secret).use { it.ping(root.clientId, next(root.counter), nowMs) }
            val publication = publish(AapsV3TargetRequest(request))
            put(PROBE, json.encodeToString(Probe(request)))
            put(ROOT, json.encodeToString(root.copy(counter = next(root.counter), lastIssuedAt = nowMs)))
            publication
        } finally { secret.fill(0) }
    }

    suspend fun acknowledgeProbe(ack: JsonObject, nowMs: Long): Boolean = transaction {
        val pair = pairing(); val probe = read(PROBE)?.let { json.decodeFromJsonElement<Probe>(it) } ?: return@transaction false
        val at = HistoryJson.long(probe.request.getValue("timestamp"))
        val counter = HistoryJson.long(probe.request.getValue("counter"))
        if (probe.request["clientId"] != JsonPrimitive(pair.clientId) || root().clientId != pair.clientId) return@transaction false
        if (counter <= pair.pongCounter || nowMs - at !in 0..13_000) return@transaction false
        val secret = secretBytes(pair)
        val valid = try { AapsV3SessionProtocol(secret).use { it.isPong(probe.request, ack, nowMs) } } finally { secret.fill(0) }
        if (valid) put(PAIRING, json.encodeToString(pair.copy(lastPongAt = at, pongCounter = counter)))
        valid
    }

    // Read-only assertion used by the guard inside prepare/commit; no state or receipt escapes.
    suspend fun requireLivePairing(nowMs: Long, sessionStartedAt: Long, minimumProbeCounter: Long = 0): Unit = try { db.withTransaction {
        val pair = pairing()
        check(pair.phase == AapsV3PairingPhase.ACTIVE && pair.controlEnabled && pair.lastPongAt >= sessionStartedAt)
        check(pair.pongCounter >= minimumProbeCounter)
        check(nowMs - pair.lastPongAt in 0..90_000)
        val row = LocalNightscoutV3Store(db).get(LocalV3Collection.SETTINGS, "aaps")
        check(row != null && !row.deleted && row.source == "local-v3-owner" && row.modifiedRevision == pair.rosterRevision)
    } } catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { throw AapsV3TargetJournalFailure() }

    suspend fun <T> withTargetProtocol(block: suspend (AapsV3TargetProtocol) -> T): T {
        val secret = transaction { secretBytes(pairing().also { check(root().clientId == it.clientId) }) }
        val protocol = try { AapsV3TargetProtocol(secret) } finally { secret.fill(0) }
        return try { block(protocol) } finally { protocol.close() }
    }

    suspend fun status(operationId: String): AapsV3TargetPhase? = transaction { recordOrNull(commandKey(operationId))?.phase }

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
                        nowMs: Long, currentTime: () -> Long = { nowMs },
                        checkGuard: suspend () -> Unit): AapsV3TargetJournalSnapshot = transaction {
        val root = root()
        val id = commandKey(operationId)
        val marker = "copilot:$operationId"
        check(target.notes.isEmpty() || target.notes == marker)
        var candidate = protocol.prepare(root.clientId, next(root.counter), nowMs, target.copy(notes = marker))
        val fingerprint = digest(candidate.json.getValue("payload").jsonPrimitive.content)
        val previous = recordOrNull(id)
        if (previous != null) {
            check(previous.fingerprint == fingerprint)
            return@transaction previous.snapshot()
        }
        check(root.pending == null && nowMs >= root.lastIssuedAt)
        checkGuard()
        val issuedAt = currentTime().also { check(it >= maxOf(nowMs, root.lastIssuedAt)) }
        candidate = protocol.prepare(root.clientId, next(root.counter), issuedAt, target.copy(notes = marker))
        val counter = candidate.json.getValue("counter").jsonPrimitive.long
        val record = Record(fingerprint, AapsV3TargetPhase.PREPARED, counter, candidate.json, operationId = operationId)
        val publication = publish(candidate)
        put(id, json.encodeToString(record))
        put(ROOT, json.encodeToString(root.copy(counter = counter, lastIssuedAt = issuedAt, pending = id)))
        record.snapshot(publication)
    }

    suspend fun commit(operationId: String, protocol: AapsV3TargetProtocol, nowMs: Long,
                       currentTime: () -> Long = { nowMs }, checkGuard: suspend () -> Unit): AapsV3TargetJournalSnapshot = transaction {
        val root = root(); val id = commandKey(operationId); val previous = record(id)
        if (previous.phase in setOf(AapsV3TargetPhase.COMMITTED, AapsV3TargetPhase.UNKNOWN,
                AapsV3TargetPhase.APPLIED, AapsV3TargetPhase.REJECTED, AapsV3TargetPhase.EXPIRED)) {
            return@transaction previous.snapshot()
        }
        check(root.pending == id && previous.phase == AapsV3TargetPhase.PREVIEW && nowMs >= root.lastIssuedAt)
        checkGuard()
        val issuedAt = currentTime().also { check(it >= maxOf(nowMs, root.lastIssuedAt)) }
        val request = protocol.commit(AapsV3TargetRequest(checkNotNull(previous.prepare)),
            checkNotNull(previous.preview), next(root.counter), issuedAt)
        val counter = request.json.getValue("counter").jsonPrimitive.long
        val committed = previous.copy(phase = AapsV3TargetPhase.COMMITTED, counter = counter, commit = request.json)
        val publication = publish(request)
        put(id, json.encodeToString(committed))
        put(ROOT, json.encodeToString(root.copy(counter = counter, lastIssuedAt = issuedAt)))
        committed.snapshot(publication)
    }

    suspend fun acknowledge(operationId: String, protocol: AapsV3TargetProtocol, ack: JsonObject,
                            nowMs: Long): AapsV3TargetJournalSnapshot = transaction {
        acknowledgeInTransaction(operationId, protocol, ack, nowMs)
    }

    private suspend fun acknowledgeInTransaction(operationId: String, protocol: AapsV3TargetProtocol,
                                                 ack: JsonObject, nowMs: Long): AapsV3TargetJournalSnapshot {
        val root = root(); val id = commandKey(operationId); val previous = record(id)
        if (previous.phase in TERMINAL) return previous.snapshot()
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
        if (phase == previous.phase) return previous.snapshot()
        val updated = previous.copy(phase = phase, preview = if (phase == AapsV3TargetPhase.PREVIEW) ack else previous.preview)
            .compactIfTerminal()
        put(id, json.encodeToString(updated))
        if (phase in TERMINAL) put(ROOT, json.encodeToString(root.copy(pending = null)))
        if (phase == AapsV3TargetPhase.APPLIED) {
            val action = db.actionCommandDao().forTargetObservationProof(operationId).singleOrNull()
            if (action?.type == "temp_target") db.actionCommandDao().updateStatusByIds(
                listOf(action.id), "PENDING", "SENT")
        }
        return updated.snapshot()
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

    suspend fun acknowledgePending(protocol: AapsV3TargetProtocol, ack: JsonObject, nowMs: Long): AapsV3TargetJournalSnapshot? = transaction {
        val pending = root().pending ?: return@transaction null
        val operation = record(pending).operationId ?: return@transaction record(pending).snapshot()
        acknowledgeInTransaction(operation, protocol, ack, nowMs)
    }

    /** Cold read-only lane. No runtime sender calls this until native capability/admission is implemented. */
    suspend fun requestComboRead(requestId: String, spec: AapsV3ComboReadSpec, sessionStartedAt: Long,
                                 minimumProbeCounter: Long, clock: () -> Long): AapsV3ComboReadSnapshot? = transaction {
        HistoryJson.uuid(requestId)
        val root = root(); val pair = pairing(); val previous = comboReadOrNull()
        val now = clock()
        if (previous?.clientId == root.clientId && previous.requestId == requestId) {
            check(previous.spec == spec)
            return@transaction comboSnapshot(previous, sessionStartedAt, minimumProbeCounter, now, clock)
        }
        // History cannot consume the target's ownership slot, even before target preparation.
        if (root.pending != null || db.actionCommandDao().hasPendingTempTarget()) return@transaction null
        if (previous != null && comboSnapshot(previous, sessionStartedAt, minimumProbeCounter, now, clock).phase ==
            AapsV3ComboReadPhase.ISSUED) return@transaction null
        check(sessionStartedAt in 1..now && minimumProbeCounter > 1)
        requireLivePairing(now, sessionStartedAt, minimumProbeCounter)
        val probe = read(PROBE)?.let { json.decodeFromJsonElement<Probe>(it) }
        if (probe != null && HistoryJson.long(probe.request.getValue("counter")) > pair.pongCounter) return@transaction null
        // Leave one journal row and two maximum-size wire documents for a target prepare/commit.
        if (dao.count() + (if (previous == null) 1 else 0) >= maximumRows) return@transaction null
        val capacity = checkNotNull(db.localNightscoutV3Dao().clock())
        if (capacity.documentCount > 100_000 - 3 || capacity.payloadBytes > 64L * 1024 * 1024 - 3L * 65_536)
            return@transaction null
        val issuedAt = clock().also { check(it >= maxOf(now, root.lastIssuedAt)) }
        requireLivePairing(issuedAt, sessionStartedAt, minimumProbeCounter)
        val counter = next(root.counter)
        val secret = secretBytes(pair)
        val request = try {
            AapsV3ComboReadProtocol(secret).use { it.request(root.clientId, pair.masterInstallId, requestId, counter, issuedAt, spec) }
        } finally { secret.fill(0) }
        val publication = publish(request)
        val record = ComboRead(root.clientId, pair.masterInstallId, requestId, counter, issuedAt,
            spec, publication.document.modifiedRevision)
        put(COMBO_READ, json.encodeToString(record))
        put(ROOT, json.encodeToString(root.copy(counter = counter, lastIssuedAt = issuedAt)))
        val completedAt = clock()
        check(completedAt in issuedAt until issuedAt + AapsV3ComboReadProtocol.TTL_MS)
        requireLivePairing(completedAt, sessionStartedAt, minimumProbeCounter)
        record.snapshot(publication)
    }

    /** Recover the same committed document; never resign, republish or advance a counter on restart. */
    suspend fun comboReadSnapshot(sessionStartedAt: Long, minimumProbeCounter: Long,
                                 clock: () -> Long): AapsV3ComboReadSnapshot? = transaction {
        comboReadOrNull()?.let { comboSnapshot(it, sessionStartedAt, minimumProbeCounter, clock(), clock) }
    }

    private suspend fun comboReadOrNull(): ComboRead? = read(COMBO_READ)?.let {
        json.decodeFromJsonElement<ComboRead>(it).also { record ->
            check(record.version == 1 && record.counter > 1 && record.publicationRevision > 0)
            listOf(record.clientId, record.masterInstallId, record.requestId).forEach(HistoryJson::uuid)
            check(record.issuedAt in 1..LocalNightscoutV3Store.MAX_TIME - AapsV3ComboReadProtocol.TTL_MS)
        }
    }

    private suspend fun comboSnapshot(record: ComboRead, sessionStartedAt: Long,
                                      minimumProbeCounter: Long, now: Long, clock: () -> Long): AapsV3ComboReadSnapshot {
        if (record.phase != AapsV3ComboReadPhase.ISSUED) return record.snapshot()
        val root = root(); val pair = pairing()
        val phase = when {
            record.clientId != root.clientId || record.masterInstallId != pair.masterInstallId ||
                pair.phase != AapsV3PairingPhase.ACTIVE || !pair.controlEnabled -> AapsV3ComboReadPhase.REVOKED
            root.pending != null || root.counter > record.counter || db.actionCommandDao().hasPendingTempTarget() ->
                AapsV3ComboReadPhase.SUPERSEDED
            now >= record.issuedAt + AapsV3ComboReadProtocol.TTL_MS -> AapsV3ComboReadPhase.EXPIRED
            else -> AapsV3ComboReadPhase.ISSUED
        }
        if (phase != record.phase) {
            val retired = record.copy(phase = phase)
            put(COMBO_READ, json.encodeToString(retired))
            return retired.snapshot()
        }
        check(root.counter == record.counter && now >= record.issuedAt)
        check(sessionStartedAt in 1..now && minimumProbeCounter > 1)
        requireLivePairing(now, sessionStartedAt, minimumProbeCounter)
        val secret = secretBytes(pair)
        val request = try {
            AapsV3ComboReadProtocol(secret).use { it.request(record.clientId, record.masterInstallId,
                record.requestId, record.counter, record.issuedAt, record.spec) }
        } finally { secret.fill(0) }
        val row = LocalNightscoutV3Store(db).get(LocalV3Collection.SETTINGS,
            "aaps_clientcontrol_cmd_${AapsV3ComboReadProtocol.TYPE}_${record.clientId}")
        check(row != null && !row.deleted && !row.readOnly && row.source == "local-v3-target" &&
            row.modifiedRevision == record.publicationRevision && row.eventAtMs == DOC_DATE &&
            HistoryJson.parse(row.payloadJson.toByteArray(), 65_536) == Json.parseToJsonElement(publicationBody(request)))
        val completedAt = clock()
        check(completedAt in now until record.issuedAt + AapsV3ComboReadProtocol.TTL_MS)
        requireLivePairing(completedAt, sessionStartedAt, minimumProbeCounter)
        return record.snapshot(LocalV3Write(row, false))
    }

    private suspend fun publish(request: AapsV3TargetRequest): LocalV3Write {
        val clientId = request.json.getValue("clientId").jsonPrimitive.content
        val type = request.json.getValue("type").jsonPrimitive.content
        val id = if (type == "hello") "aaps_clientcontrol_hello_$clientId" else "aaps_clientcontrol_cmd_${type}_$clientId"
        val store = LocalNightscoutV3Store(db)
        val previous = store.get(LocalV3Collection.SETTINGS, id)
        check(previous == null || previous.source == "local-v3-target")
        val input = LocalV3DocumentInput(LocalV3Collection.SETTINGS, id, "local-v3-target", DOC_DATE, publicationBody(request))
        return if (previous == null) store.create(input) else store.update(input, previous.modifiedRevision)
    }

    private fun publicationBody(request: AapsV3TargetRequest) = buildJsonObject {
        put("date", DOC_DATE); put("utcOffset", 0); put("app", "AAPS"); put("schemaVersion", 1)
        put("envelope", request.json)
    }.toString()

    private suspend fun rootOrNull(): Root? = read(ROOT)?.let {
        json.decodeFromJsonElement<Root>(it).also { root ->
            check(root.version == 1 && CLIENT_ID.matches(root.clientId) && root.counter >= 0)
            check(root.lastIssuedAt in 0..LocalNightscoutV3Store.MAX_TIME)
            check(root.pending == null || COMMAND_KEY.matches(root.pending))
        }
    }
    private suspend fun root(): Root = checkNotNull(rootOrNull())
    private suspend fun pairingOrNull(): Pairing? = read(PAIRING)?.let { json.decodeFromJsonElement<Pairing>(it).also { pair ->
        check(CLIENT_ID.matches(pair.clientId) && HEX.matches(pair.secretHex) && pair.masterInstallId.length in 1..128)
        check(pair.helloRevision > 0 && pair.rosterRevision >= 0 && pair.lastPongAt >= 0 && pair.pongCounter >= 0)
    } }
    private suspend fun pairing(): Pairing = checkNotNull(pairingOrNull())
    private fun secretBytes(pair: Pairing) = ByteArray(32) { pair.secretHex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    private fun secretHex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }
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
    @SuppressLint("RestrictedApi")
    private suspend fun <T> transaction(block: suspend () -> T): T = try {
        check(!db.inTransaction())
        // Pinned Room API catches inherited outer transactions after a dispatcher switch as well.
        db.assertNotSuspendingTransaction()
        db.withTransaction { block() }
    }
    catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { throw AapsV3TargetJournalFailure() }

    @Serializable private data class Root(val clientId: String, val counter: Long = 0,
        val lastIssuedAt: Long = 0, val pending: String? = null, val version: Int = 1)
    @Serializable private data class Record(val fingerprint: String, val phase: AapsV3TargetPhase,
        val counter: Long, val prepare: JsonObject? = null, val preview: JsonObject? = null,
        val commit: JsonObject? = null, val version: Int = 1, val operationId: String? = null) {
        fun snapshot(publication: LocalV3Write? = null) = AapsV3TargetJournalSnapshot(phase, counter, publication, operationId)
        fun compactIfTerminal() = if (phase in TERMINAL) copy(prepare = null, preview = null, commit = null) else this
    }
    @Serializable private data class Pairing(val clientId: String, val masterInstallId: String, val secretHex: String,
        val expiresAt: Long, val helloRevision: Long, val phase: AapsV3PairingPhase = AapsV3PairingPhase.WAITING,
        val rosterRevision: Long = 0, val controlEnabled: Boolean = false, val lastPongAt: Long = 0, val pongCounter: Long = 0) {
        fun snapshot(publication: LocalV3Write? = null) = AapsV3PairingSnapshot(phase, clientId, masterInstallId, controlEnabled, lastPongAt, pongCounter, publication)
        override fun toString() = "NativePairing(redacted)"
    }
    @Serializable private data class Probe(val request: JsonObject)
    @Serializable private data class ComboRead(val clientId: String, val masterInstallId: String,
        val requestId: String, val counter: Long, val issuedAt: Long, val spec: AapsV3ComboReadSpec,
        val publicationRevision: Long, val phase: AapsV3ComboReadPhase = AapsV3ComboReadPhase.ISSUED,
        val version: Int = 1) {
        fun snapshot(publication: LocalV3Write? = null) = AapsV3ComboReadSnapshot(requestId, counter, phase, publication)
        override fun toString() = "ComboReadRecord(redacted)"
    }
    @Serializable private data class Attempts(val startedAt: Long, val lastAt: Long, val count: Int)
    private companion object {
        const val ROOT = "owner"
        const val PAIRING = "pairing"
        const val PROBE = "probe"
        const val COMBO_READ = "combo-read"
        const val ATTEMPTS = "pin-attempts"
        const val DOC_DATE = 946_684_800_001L
        val HEX = Regex("[0-9a-f]{64}")
        val COMMAND_KEY = Regex("command:[0-9a-f]{64}")
        val CLIENT_ID = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
        val TERMINAL = setOf(AapsV3TargetPhase.APPLIED, AapsV3TargetPhase.REJECTED,
            AapsV3TargetPhase.EXPIRED, AapsV3TargetPhase.EXPIRED_NO_COMMIT)
    }
}
