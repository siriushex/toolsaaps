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
import java.util.Base64
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

internal const val NATIVE_MEAL_RECEIPT = "local_native_meal_pending_readback"
@Serializable
internal enum class AapsV3TargetPhase { PREPARED, PREVIEW, COMMITTED, UNKNOWN, APPLIED, REJECTED, EXPIRED, EXPIRED_NO_COMMIT }

@Serializable internal enum class AapsV3PairingPhase { WAITING, ACTIVE, REVOKED }
internal class AapsV3PairingSnapshot(val phase: AapsV3PairingPhase, val clientId: String,
    val masterInstallId: String, val controlEnabled: Boolean, val lastPongAt: Long, val pongCounter: Long,
    val publication: LocalV3Write? = null, val localEnrollment: Boolean = false,
    val enrollmentRole: AapsFinalClientRole? = null) {
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

    suspend fun enroll(credential: AapsV3PairingCredential, nowMs: Long,
                       localHandle: String? = null,
                       routeReady: () -> Boolean = { true }): AapsV3PairingSnapshot = transaction {
        check(routeReady())
        val localBinding = localHandle?.let { require(CLIENT_ID.matches(it) && credential.requiresSignedRoster); digest(it) }
        val oldRoot = rootOrNull()
        val oldPair = pairingOrNull()
        if (oldPair != null && oldPair.clientId == credential.clientId) {
            check(oldPair.masterInstallId == credential.masterInstallId &&
                oldPair.requiresSignedRoster == credential.requiresSignedRoster && oldPair.localBinding == localBinding)
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
        var exchange: AapsFinalKeyExchange.Pending? = null
        try {
            if (credential.requiresSignedRoster) exchange = AapsFinalKeyExchange.begin()
            val hello = AapsV3SessionProtocol(secret).use {
                it.hello(credential.clientId, 1, nowMs, credential.expiresAt, exchange?.publicPoint)
            }
            val publication = publish(AapsV3TargetRequest(hello))
            val pair = Pairing(credential.clientId, credential.masterInstallId, secretHex(secret),
                credential.expiresAt, publication.document.modifiedRevision, requiresSignedRoster = credential.requiresSignedRoster,
                enrolledAt = nowMs, localBinding = localBinding, pendingExchange = exchange?.let {
                    PendingExchange(it.publicPoint, Base64.getEncoder().encodeToString(it.privateDer))
                })
            put(ROOT, json.encodeToString(Root(credential.clientId, 1, nowMs)))
            put(PAIRING, json.encodeToString(pair))
            check(routeReady())
            pair.snapshot(publication)
        } finally { exchange?.close(); secret.fill(0) }
    }

    suspend fun pairingSnapshot(): AapsV3PairingSnapshot? = transaction {
        pairingOrNull()?.also { check(root().clientId == it.clientId) }?.snapshot()
    }

    /** Called by the trusted local issuer, never with a token or claimed subject from a settings write. */
    suspend fun issuePendingClientToken(clientId: String, masterId: String, clientPoint: String, subject: String, nowMs: Long,
                                        localOffers: LocalEnrollmentOffers? = null): ByteArray = transaction {
        val pair = pairing()
        check(pair.requiresSignedRoster && pair.pendingExchange != null && pair.phase == AapsV3PairingPhase.WAITING &&
            nowMs in pair.enrolledAt until pair.expiresAt && !pair.secretRotated &&
            pair.clientId == clientId && root().clientId == clientId && pair.masterInstallId == masterId &&
            pair.pendingExchange.publicPoint == clientPoint)
        require(isLocalClientSubject(subject))
        val local = pair.localBinding?.let { binding ->
            val offer = checkNotNull(localOffers?.current())
            check(offer.clientId == clientId && digest(offer.handle) == binding && localOffers.ready(offer))
            offer
        }
        val issued = pair.pendingToken
        if (issued != null) {
            check(issued.subject == subject)
            return@transaction issued.token.encodeToByteArray()
        }
        val random = ByteArray(32).also(java.security.SecureRandom()::nextBytes)
        val token = try { Base64.getUrlEncoder().withoutPadding().encodeToString(random) } finally { random.fill(0) }
        put(PAIRING, json.encodeToString(pair.copy(pendingToken = PendingToken(subject, token))))
        if (local != null) check(localOffers?.ready(local) == true)
        token.encodeToByteArray()
    }

    suspend fun revokePairing(): Unit = transaction {
        val pair = pairing()
        if (pair.phase != AapsV3PairingPhase.REVOKED) put(PAIRING, json.encodeToString(pair.copy(
            phase = AapsV3PairingPhase.REVOKED, controlEnabled = false, pendingExchange = null,
            pendingToken = null, verifiedSubject = null)))
    }

    suspend fun rotateFinalSecret(nowMs: Long): Boolean = transaction {
        val pair = pairing()
        if (pair.localBinding != null || pair.pendingExchange == null) return@transaction false
        val row = LocalNightscoutV3Store(db).get(LocalV3Collection.SETTINGS, "aaps_clientcontrol_key_${pair.clientId}")
        if (row == null || row.deleted || row.source != "local-v3-owner" ||
            row.modifiedRevision <= pair.helloRevision) return@transaction false
        val answer = try {
            val body = HistoryJson.parse(row.payloadJson.toByteArray(), 8192)
            require(HistoryJson.long(body.getValue("schemaVersion")) == 1L)
            HistoryJson.obj(body.getValue("key"))
        } catch (_: Exception) { return@transaction false }
        finishKeyRotation(pair, answer, nowMs)
    }

    suspend fun acceptLocalKey(offers: LocalEnrollmentOffers, clientId: String, handle: String,
                               bytes: ByteArray, clock: () -> Long = System::currentTimeMillis): Boolean = transaction {
        val offer = offers.current() ?: return@transaction false
        if (offer.clientId != clientId || offer.handle != handle || bytes.size !in 1..2048 || !offers.ready(offer)) return@transaction false
        val pair = pairingOrNull() ?: return@transaction false
        if (pair.clientId != clientId || pair.localBinding != digest(handle) || pair.pendingToken == null) return@transaction false
        val answer = HistoryJson.parse(bytes, 2048)
        if (!finishKeyRotation(pair, answer, clock())) return@transaction false
        check(offers.ready(offer) && clock() in pair.enrolledAt until pair.expiresAt)
        true
    }

    private suspend fun finishKeyRotation(pair: Pairing, answer: JsonObject, nowMs: Long): Boolean {
        val pending = pair.pendingExchange ?: return false
        if (!pair.requiresSignedRoster || pair.secretRotated || pair.phase != AapsV3PairingPhase.WAITING ||
            nowMs !in pair.enrolledAt until pair.expiresAt) return false
        val root = root()
        check(root.clientId == pair.clientId && root.counter == 1L && root.pending == null)
        val privateDer = Base64.getDecoder().decode(pending.privateDer)
        val oldSecret = secretBytes(pair)
        var verifiedSubject: String? = null
        val rotated = try {
            AapsFinalKeyExchange.finish(answer, pair.clientId, pending.publicPoint, privateDer, oldSecret,
                pair.enrolledAt, pair.expiresAt, nowMs) { presented ->
                pair.pendingToken?.let { grant ->
                    val expected = grant.token.encodeToByteArray()
                    val matches = try { MessageDigest.isEqual(expected, presented) } finally { expected.fill(0) }
                    if (matches) verifiedSubject = grant.subject
                    matches
                } == true
            }
        } finally { privateDer.fill(0); oldSecret.fill(0) } ?: return false
        return try {
            if (pair.pendingToken != null && verifiedSubject == null) return false
            put(PAIRING, json.encodeToString(pair.copy(secretHex = secretHex(rotated),
                pendingExchange = null, secretRotated = true, pendingToken = null, verifiedSubject = verifiedSubject)))
            true
        } finally { rotated.fill(0) }
    }

    suspend fun observeRoster(row: io.aaps.copilot.data.local.entity.LocalNightscoutV3DocumentEntity,
                              nowMs: Long = System.currentTimeMillis()): AapsV3PairingSnapshot = transaction {
        val pair = pairing()
        // A single-client local enrollment must not become active from an ambient settings document.
        if (pair.localBinding != null) return@transaction pair.snapshot()
        if (row.collection != "settings" || row.identifier != "aaps" || row.source != "local-v3-owner" ||
            row.modifiedRevision <= maxOf(pair.helloRevision, pair.rosterRevision)) return@transaction pair.snapshot()
        if (pair.requiresSignedRoster) {
            if (pair.masterPublicPoint == null || pair.phase == AapsV3PairingPhase.REVOKED) return@transaction pair.snapshot()
            val proof = if (row.deleted) null else try {
                AapsFinalMasterKey.fromEncodedPoint(pair.masterPublicPoint)?.verifyRoster(
                    HistoryJson.parse(row.payloadJson.toByteArray(), 65_536), pair.clientId, nowMs,
                    maxOf(pair.enrolledAt - 5_000, pair.rosterSignedAt + 1)
                )
            } catch (_: Exception) { null }
            val phase = when {
                proof?.member == true -> AapsV3PairingPhase.ACTIVE
                proof?.member == false && pair.phase == AapsV3PairingPhase.ACTIVE -> AapsV3PairingPhase.REVOKED
                else -> pair.phase
            }
            val updated = pair.copy(phase = phase, rosterRevision = row.modifiedRevision,
                rosterSignedAt = proof?.signedAt ?: pair.rosterSignedAt,
                controlEnabled = proof?.controlEnabled == true, verifiedRole = proof?.role)
            put(PAIRING, json.encodeToString(updated))
            return@transaction updated.snapshot()
        }
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

    suspend fun probe(nowMs: Long, sessionStartedAt: Long = 0, localOffers: LocalEnrollmentOffers? = null): LocalV3Write? = transaction {
        val root = root(); val pair = pairing()
        if (pair.requiresSignedRoster && !pair.secretRotated) return@transaction null
        val active = pair.phase == AapsV3PairingPhase.ACTIVE && pair.controlEnabled
        // A final master can answer a probe while enrollment waits for its signed roster.
        val bootstrap = pair.requiresSignedRoster && pair.phase == AapsV3PairingPhase.WAITING && nowMs < pair.expiresAt
        if (root.pending != null || (!active && !bootstrap)) return@transaction null
        val local = if (bootstrap && pair.localBinding != null) {
            if (pair.pongCounter != 0L) return@transaction null
            val offer = localOffers?.current() ?: return@transaction null
            if (offer.clientId != pair.clientId || digest(offer.handle) != pair.localBinding ||
                pair.verifiedSubject == null || !localOffers.ready(offer)) return@transaction null
            offer
        } else null
        if (bootstrap && local == null) {
            // An early Ping can consume the master's replay counter before it processes Hello.
            val offer = LocalNightscoutV3Store(db).get(LocalV3Collection.SETTINGS, "aaps_clientcontrol_offer_${pair.clientId}")
            if (offer == null || !offer.deleted || offer.source != "local-v3-owner" ||
                offer.payloadJson != "{}" || offer.modifiedRevision <= pair.helloRevision) return@transaction null
        }
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
            if (local != null) check(localOffers?.ready(local) == true)
            publication
        } finally { secret.fill(0) }
    }

    suspend fun acknowledgeProbe(ack: JsonObject, nowMs: Long): Boolean = transaction {
        val pair = pairing()
        if (pair.localBinding != null && pair.phase == AapsV3PairingPhase.WAITING) return@transaction false
        recordProbeAnswer(pair, ack, nowMs)
    }

    suspend fun acceptLocalPong(offers: LocalEnrollmentOffers, id: String, handle: String, bytes: ByteArray,
                                clock: () -> Long = System::currentTimeMillis): Boolean = transaction {
        val offer = offers.current() ?: return@transaction false
        if (offer.clientId != id || offer.handle != handle || bytes.size !in 1..2048 || !offers.ready(offer)) return@transaction false
        val pair = pairingOrNull() ?: return@transaction false
        val at = clock()
        if (pair.phase != AapsV3PairingPhase.WAITING || pair.clientId != id || pair.localBinding != digest(handle) ||
            !pair.secretRotated || pair.verifiedSubject == null || pair.pongCounter != 0L || at !in pair.enrolledAt until pair.expiresAt)
            return@transaction false
        val ack = HistoryJson.parse(bytes, 2048)
        val request = read(PROBE)?.let { json.decodeFromJsonElement<Probe>(it).request } ?: return@transaction false
        val deadline = minOf(pair.expiresAt, HistoryJson.long(request.getValue("validUntil")))
        if (at >= deadline) return@transaction false
        if (ack["commandCounter"] != JsonPrimitive(2L) || !recordProbeAnswer(pair, ack, at)) return@transaction false
        check(offers.ready(offer) && clock() in at until deadline)
        true
    }

    suspend fun acceptLocalRole(offers: LocalEnrollmentOffers, id: String, handle: String, bytes: ByteArray,
                                clock: () -> Long = System::currentTimeMillis): Boolean = transaction {
        val offer = offers.current() ?: return@transaction false
        if (bytes.size !in 1..2048 || offer.clientId != id || offer.handle != handle || !offers.ready(offer)) return@transaction false
        val pair = pairingOrNull() ?: return@transaction false
        if (pair.phase != AapsV3PairingPhase.WAITING || pair.localBinding != digest(handle) || pair.clientId != id ||
            pair.pongCounter != 2L || pair.enrollmentRole != null || pair.verifiedSubject == null || !pair.secretRotated) return@transaction false
        val point = pair.masterPublicPoint?.let(AapsFinalMasterKey::fromEncodedPoint) ?: return@transaction false
        val request = read(PROBE)?.let { json.decodeFromJsonElement<Probe>(it).request } ?: return@transaction false
        if (request["counter"] != JsonPrimitive(2L) || request["clientId"] != JsonPrimitive(id)) return@transaction false
        val at = clock()
        val deadline = minOf(pair.expiresAt, HistoryJson.long(request.getValue("validUntil")))
        val proof = point.verifyLocalRole(HistoryJson.parse(bytes, 2048), id, pair.masterInstallId, handle,
            maxOf(pair.enrolledAt, pair.lastPongAt), deadline, at) ?: return@transaction false
        put(PAIRING, json.encodeToString(pair.copy(enrollmentRole = proof.role)))
        check(offers.ready(offer) && clock() in at until deadline)
        true
    }

    suspend fun checkLocalMembership(clock: () -> Long, ask: suspend (String, String, ByteArray) -> ByteArray?): AapsLocalRoleReceipt? {
        val pair = transaction { pairingOrNull() } ?: return null
        if (pair.localBinding == null || !pair.secretRotated || pair.phase == AapsV3PairingPhase.REVOKED ||
            pair.verifiedSubject == null || pair.enrollmentRole == null) return null
        val key = pair.masterPublicPoint?.let(AapsFinalMasterKey::fromEncodedPoint) ?: return null
        val issued = clock()
        val nonce = java.util.UUID.randomUUID().toString()
        val secret = secretBytes(pair)
        val request = try {
            AapsV3SessionProtocol(secret).use { it.localWitness(pair.clientId, pair.masterInstallId, nonce, issued) }
                .toString().encodeToByteArray()
        } finally { secret.fill(0) }
        // IPC must never hold the database transaction or prevent local revocation.
        val bytes = try { ask(pair.masterInstallId, nonce, request) } finally { request.fill(0) } ?: return null
        return try {
            if (bytes.size !in 1..2048) return null
            val doc = HistoryJson.parse(bytes, 2048)
            transaction {
                if (pairingOrNull() != pair) null else key.verifyLocalWitness(doc, pair.clientId, pair.masterInstallId, nonce, issued, clock())
            }
        } catch (e: CancellationException) { throw e } catch (_: Exception) { null } finally { bytes.fill(0) }
    }

    private suspend fun recordProbeAnswer(pair: Pairing, ack: JsonObject, nowMs: Long): Boolean {
        val probe = read(PROBE)?.let { json.decodeFromJsonElement<Probe>(it) } ?: return false
        if (pair.requiresSignedRoster && !pair.secretRotated) return false
        val at = HistoryJson.long(probe.request.getValue("timestamp"))
        val counter = HistoryJson.long(probe.request.getValue("counter"))
        if (pair.phase == AapsV3PairingPhase.REVOKED || probe.request["clientId"] != JsonPrimitive(pair.clientId) ||
            root().clientId != pair.clientId) return false
        if (counter <= pair.pongCounter || nowMs - at !in 0..13_000) return false
        val secret = secretBytes(pair)
        val updated = try {
            AapsV3SessionProtocol(secret).use { protocol ->
                if (pair.requiresSignedRoster) {
                    val key = protocol.finalMasterKey(probe.request, ack, nowMs) ?: return@use null
                    if (pair.masterPublicPoint != null && pair.masterPublicPoint != key.encodedPoint) return@use null
                    pair.copy(masterPublicPoint = key.encodedPoint, lastPongAt = at, pongCounter = counter)
                } else if (protocol.isPong(probe.request, ack, nowMs)) {
                    pair.copy(lastPongAt = at, pongCounter = counter)
                } else null
            }
        } finally { secret.fill(0) }
        if (updated != null) put(PAIRING, json.encodeToString(updated))
        return updated != null
    }

    // Read-only assertion used by the guard inside prepare/commit; no state or receipt escapes.
    suspend fun requireLivePairing(nowMs: Long, sessionStartedAt: Long, minimumProbeCounter: Long = 0,
                                   requireTargetRole: Boolean = false): String = try { db.withTransaction {
        val pair = pairing()
        check(!pair.requiresSignedRoster || pair.secretRotated)
        check(pair.phase == AapsV3PairingPhase.ACTIVE && pair.controlEnabled && pair.lastPongAt >= sessionStartedAt)
        check(pair.pongCounter >= minimumProbeCounter)
        check(nowMs - pair.lastPongAt in 0..90_000)
        if (requireTargetRole && pair.requiresSignedRoster) {
            check(pair.verifiedRole == AapsFinalClientRole.CAREGIVER || pair.verifiedRole == AapsFinalClientRole.ADMIN)
        }
        val row = LocalNightscoutV3Store(db).get(LocalV3Collection.SETTINGS, "aaps")
        check(row != null && !row.deleted && row.source == "local-v3-owner" && row.modifiedRevision == pair.rosterRevision)
        pair.masterInstallId
    } } catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { throw AapsV3TargetJournalFailure() }

    suspend fun <T> withTargetProtocol(block: suspend (AapsV3TargetProtocol) -> T): T {
        val secret = transaction { secretBytes(pairing().also { check(root().clientId == it.clientId) }) }
        val protocol = try { AapsV3TargetProtocol(secret) } finally { secret.fill(0) }
        return try { block(protocol) } finally { protocol.close() }
    }

    suspend fun status(operationId: String): AapsV3TargetPhase? = transaction { recordOrNull(commandKey(operationId))?.phase }

    /** Only the first reservation gets a request, for a dedicated local peer, not the settings feed.
     * The guard must be local. Enrollment is not current authorization: AAPS must check the
     * caller's role when it receives this request. No commit or delivery is implied. */
    suspend fun reserveLocalMealPreview(operationId: String, clock: () -> Long,
                                        checkGuard: suspend () -> Unit): AapsV3MealPrepare? = transaction {
        val id = commandKey(operationId)
        val row = db.actionCommandDao().forTargetObservationProof(operationId).singleOrNull()
            ?: return@transaction null
        check(row.type == "carbs" && row.status == "PENDING")
        val input = HistoryJson.parse(row.payloadJson.encodeToByteArray(), 16_384)
        val grams = HistoryJson.string(input.getValue("carbsGrams")).toDouble()
        check("deliveryChannel" !in input && "carbBlockReason" !in input)
        val root = root()
        val pair = pairing()
        check(pair.clientId == root.clientId && pair.localBinding != null && pair.requiresSignedRoster &&
            pair.phase != AapsV3PairingPhase.REVOKED && pair.secretRotated && pair.verifiedSubject != null &&
            pair.masterPublicPoint != null && pair.pongCounter >= 2 &&
            pair.enrollmentRole in setOf(AapsFinalClientRole.MEAL_ASSISTANT, AapsFinalClientRole.CAREGIVER, AapsFinalClientRole.ADMIN))
        recordOrNull(id)?.let {
            check(it.mealGrams?.toDouble() == grams && it.operationId == operationId && it.mealInput == actionFingerprint(row))
            return@transaction null
        }
        if (root.pending != null || db.actionCommandDao().hasPendingTempTarget()) return@transaction null
        checkGuard()
        val issued = clock()
        check(issued >= root.lastIssuedAt && row.timestamp in (issued - AapsV3TargetProtocol.TTL_MS + 1)..issued)
        val key = secretBytes(pair)
        val req = try { AapsV3TargetProtocol(key).use {
            it.prepareMeal(pair.clientId, next(root.counter), issued, operationId, grams)
        } } finally { key.fill(0) }
        val wire = req.envelope.json
        val counter = HistoryJson.long(wire.getValue("counter"))
        put(id, json.encodeToString(Record(digest(HistoryJson.string(wire.getValue("payload"))),
            AapsV3TargetPhase.PREPARED, counter, wire, operationId = operationId, mealGrams = req.grams, mealInput = actionFingerprint(row))))
        put(ROOT, json.encodeToString(root.copy(counter = counter, lastIssuedAt = issued, pending = id)))
        checkGuard()
        check(clock() in issued until HistoryJson.long(wire.getValue("validUntil")))
        req
    }

    suspend fun acceptLocalMealPreview(operationId: String, ack: JsonObject, nowMs: Long): Boolean = transaction {
        val root = root()
        val id = commandKey(operationId)
        val prior = recordOrNull(id) ?: return@transaction false
        val grams = prior.mealGrams ?: return@transaction false
        if (root.pending != id || root.counter != prior.counter || prior.operationId != operationId ||
            prior.phase != AapsV3TargetPhase.PREPARED) return@transaction false
        val pair = pairing()
        if (pair.clientId != root.clientId || pair.localBinding == null || pair.phase == AapsV3PairingPhase.REVOKED)
            return@transaction false
        val row = db.actionCommandDao().forTargetObservationProof(operationId).singleOrNull() ?: return@transaction false
        if (row.status != "PENDING" || row.type != "carbs" || actionFingerprint(row) != prior.mealInput) return@transaction false
        val req = AapsV3MealPrepare(AapsV3TargetRequest(checkNotNull(prior.prepare)), operationId, grams)
        val key = secretBytes(pair)
        val ok = try { AapsV3TargetProtocol(key).use { it.verifiedMealPreview(req, ack, nowMs) } }
            finally { key.fill(0) }
        if (!ok) return@transaction false
        put(id, json.encodeToString(prior.copy(phase = AapsV3TargetPhase.PREVIEW, preview = ack)))
        true
    }

    /** Claim before Binder: process death or an ambiguous reply can only lead to readback, never resend. */
    suspend fun claimLocalMealRecord(operationId: String, clock: () -> Long, guard: suspend () -> Unit): AapsV3TargetRequest = transaction {
        val id = commandKey(operationId)
        val root = root()
        val prior = record(id)
        val row = db.actionCommandDao().forTargetObservationProof(operationId).single()
        check(root.pending == id && root.counter == prior.counter && prior.phase == AapsV3TargetPhase.PREVIEW &&
            prior.operationId == operationId && row.status == "PENDING" && row.type == "carbs" && actionFingerprint(row) == prior.mealInput)
        val pair = pairing()
        check(pair.clientId == root.clientId && pair.localBinding != null && pair.phase != AapsV3PairingPhase.REVOKED)
        guard()
        val now = clock()
        check(!db.actionCommandDao().hasPendingTempTarget())
        check(io.aaps.copilot.data.repository.CarbsSendThrottle(db.actionCommandDao()).evaluatePersisted(now).allowed)
        val next = next(root.counter)
        val prepared = AapsV3MealPrepare(AapsV3TargetRequest(checkNotNull(prior.prepare)), operationId, checkNotNull(prior.mealGrams))
        val key = secretBytes(pair)
        val wire = try { AapsV3TargetProtocol(key).use { it.recordMeal(prepared, checkNotNull(prior.preview), next, now) } }
            finally { key.fill(0) }
        val payload = HistoryJson.parse(row.payloadJson.encodeToByteArray(), 16_384)
        check("deliveryChannel" !in payload && "carbBlockReason" !in payload)
        val waiting = row.copy(timestamp = now, status = io.aaps.copilot.data.repository.NightscoutActionRepository.STATUS_LOCAL_MEAL_RECEIPT,
            payloadJson = JsonObject(payload + ("deliveryChannel" to JsonPrimitive(NATIVE_MEAL_RECEIPT))).toString())
        db.actionCommandDao().upsert(waiting)
        put(id, json.encodeToString(prior.copy(phase = AapsV3TargetPhase.COMMITTED, counter = next, commit = wire.json, mealInput = actionFingerprint(waiting))))
        put(ROOT, json.encodeToString(root.copy(counter = next, lastIssuedAt = now, pending = null)))
        guard()
        check(clock() in now until HistoryJson.long(checkNotNull(prior.prepare).getValue("validUntil")))
        wire
    }

    suspend fun confirmNativeMeal(row: io.aaps.copilot.data.local.entity.ActionCommandEntity, at: Long, grams: Double): Boolean = transaction {
        val id = commandKey(row.idempotencyKey)
        val record = record(id)
        val current = db.actionCommandDao().forTargetObservationProof(row.idempotencyKey).singleOrNull() ?: return@transaction false
        if (record.operationId != row.idempotencyKey || record.mealGrams?.toDouble() != grams || record.phase != AapsV3TargetPhase.COMMITTED ||
            row != current || row.status != io.aaps.copilot.data.repository.NightscoutActionRepository.STATUS_LOCAL_MEAL_RECEIPT ||
            record.mealInput != actionFingerprint(row)) return@transaction false
        val wire = checkNotNull(record.commit)
        check(row.timestamp == HistoryJson.long(wire.getValue("timestamp")) && at in row.timestamp until HistoryJson.long(wire.getValue("validUntil")))
        val payload = HistoryJson.parse(row.payloadJson.encodeToByteArray(), 16_384)
        check(HistoryJson.string(payload.getValue("deliveryChannel")) == NATIVE_MEAL_RECEIPT)
        db.actionCommandDao().upsert(row.copy(status = "SENT", timestamp = at,
            payloadJson = JsonObject(payload + ("deliveryChannel" to JsonPrimitive("aaps_meal_record"))).toString()))
        put(id, json.encodeToString(record.copy(phase = AapsV3TargetPhase.APPLIED).compactIfTerminal()))
        true
    }

    suspend fun retireLocalMealPreview(operationId: String) = transaction {
        val root = root()
        val id = commandKey(operationId)
        if (root.pending == id) {
            val meal = record(id)
            check(meal.mealGrams != null && meal.commit == null &&
                meal.phase in setOf(AapsV3TargetPhase.PREPARED, AapsV3TargetPhase.PREVIEW))
            put(id, json.encodeToString(meal.copy(phase = AapsV3TargetPhase.EXPIRED_NO_COMMIT)))
            put(ROOT, json.encodeToString(root.copy(pending = null)))
        }
    }

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
                        nowMs: Long, currentTime: () -> Long = { nowMs }, expectedActionId: String? = null,
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
        check(nowMs >= root.lastIssuedAt)
        root.pending?.let { priorId ->
            val prior = record(priorId)
            check(prior.mealGrams != null && prior.commit == null &&
                prior.phase in setOf(AapsV3TargetPhase.PREPARED, AapsV3TargetPhase.PREVIEW))
            put(priorId, json.encodeToString(prior.copy(phase = AapsV3TargetPhase.EXPIRED_NO_COMMIT)))
        }
        checkGuard()
        val issuedAt = currentTime().also { check(it >= maxOf(nowMs, root.lastIssuedAt)) }
        candidate = protocol.prepare(root.clientId, next(root.counter), issuedAt, target.copy(notes = marker))
        val counter = candidate.json.getValue("counter").jsonPrimitive.long
        val receipt = expectedActionId?.let { id -> pendingTargetAction(operationId)?.takeIf { it.id == id } }
        val record = Record(fingerprint, AapsV3TargetPhase.PREPARED, counter, candidate.json, operationId = operationId,
            targetInput = receipt?.let(::actionFingerprint))
        val publication = publish(candidate)
        put(id, json.encodeToString(record))
        put(ROOT, json.encodeToString(root.copy(counter = counter, lastIssuedAt = issuedAt, pending = id)))
        record.snapshot(publication)
    }

    suspend fun commit(operationId: String, protocol: AapsV3TargetProtocol, nowMs: Long,
                       currentTime: () -> Long = { nowMs }, checkGuard: suspend () -> Unit): AapsV3TargetJournalSnapshot = transaction {
        val root = root(); val id = commandKey(operationId); val previous = record(id)
        check(previous.mealGrams == null)
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
        if (previous.mealGrams != null) return previous.snapshot()
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
            // Keep optional diagnostics out of the owner schema and never require a spare row to settle an ACK.
            val target = LocalTargetReceiptQuery.from(operationId, previous.prepare, previous.commit, ack)
            val hasPointer = dao.get(LAST_TARGET) != null
            if (hasPointer || (target != null && dao.count() < maximumRows - 1))
                put(LAST_TARGET, json.encodeToString(TargetPointer(root.clientId, target)))
            val action = db.actionCommandDao().forTargetObservationProof(operationId).singleOrNull()
            if (action?.type == "temp_target") db.actionCommandDao().updateStatusByIds(
                listOf(action.id), "PENDING", "SENT")
        } else if (phase in TERMINAL) {
            settleUnsentTarget(previous)
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
        if (phase == AapsV3TargetPhase.EXPIRED_NO_COMMIT) settleUnsentTarget(previous)
        updated.snapshot()
    }

    suspend fun localReceiptQuery(now: Long): Pair<String, LocalTargetReceiptQuery>? = transaction {
        val pair = pairingOrNull() ?: return@transaction null
        if (pair.localBinding == null || !pair.secretRotated || pair.phase == AapsV3PairingPhase.REVOKED) return@transaction null
        val root = root()
        if (root.clientId != pair.clientId) return@transaction null
        val pointer = read(LAST_TARGET)?.let { json.decodeFromJsonElement<TargetPointer>(it) } ?: return@transaction null
        val target = pointer.target?.takeIf { pointer.clientId == root.clientId && it.fresh(now) } ?: return@transaction null
        val key = target.marker.removePrefix("copilot:")
        val record = recordOrNull(commandKey(key))
        if (record?.operationId != key || record.phase != AapsV3TargetPhase.APPLIED) null else pair.masterInstallId to target
    }

    private suspend fun pendingTargetAction(key: String) =
        db.actionCommandDao().forTargetObservationProof(key).singleOrNull()?.takeIf {
            it.type == "temp_target" && it.status == "PENDING"
        }

    private suspend fun settleUnsentTarget(record: Record) {
        val expected = record.targetInput ?: return
        val action = pendingTargetAction(checkNotNull(record.operationId)) ?: return
        if (actionFingerprint(action) == expected)
            db.actionCommandDao().updateStatusByIds(listOf(action.id), "PENDING", "BLOCKED")
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

    /** Read-only lane sharing counter ownership and yielding to pending targets. */
    suspend fun requestComboRead(requestId: String, spec: AapsV3ComboReadSpec, sessionStartedAt: Long,
                                 minimumProbeCounter: Long, clock: () -> Long): AapsV3ComboReadSnapshot? =
        requestComboRead(requestId, spec, sessionStartedAt, minimumProbeCounter, clock) { }

    suspend fun requestComboRead(requestId: String, spec: AapsV3ComboReadSpec, sessionStartedAt: Long,
                                 minimumProbeCounter: Long, clock: () -> Long,
                                 admit: suspend () -> Unit): AapsV3ComboReadSnapshot? = transaction {
        admit()
        HistoryJson.uuid(requestId)
        val root = root(); val pair = pairing(); val previous = comboReadOrNull()
        val now = clock()
        if (previous?.clientId == root.clientId && previous.requestId == requestId) {
            check(previous.spec == spec)
            return@transaction comboSnapshot(previous, sessionStartedAt, minimumProbeCounter, now, clock)
        }
        // History cannot consume the target's ownership slot, even before target preparation.
        if (root.pending != null || db.actionCommandDao().hasPendingTempTarget()) return@transaction null
        if (previous != null && comboSnapshot(previous, sessionStartedAt, minimumProbeCounter, now, clock).phase in
            COMBO_ACTIVE) return@transaction null
        check(sessionStartedAt in 1..now && minimumProbeCounter > 1)
        requireLivePairing(now, sessionStartedAt, minimumProbeCounter)
        val probe = read(PROBE)?.let { json.decodeFromJsonElement<Probe>(it) }
        if (probe != null && HistoryJson.long(probe.request.getValue("counter")) > pair.pongCounter) return@transaction null
        // Budget the request/reply and leave a journal row plus two wire documents for a target.
        if (dao.count() + (if (previous == null) 1 else 0) >= maximumRows) return@transaction null
        val capacity = checkNotNull(db.localNightscoutV3Dao().clock())
        if (capacity.documentCount > 100_000 - 4 || capacity.payloadBytes > 64L * 1024 * 1024 - 4L * 65_536)
            return@transaction null
        val issuedAt = clock().also { check(it >= maxOf(now, root.lastIssuedAt)) }
        requireLivePairing(issuedAt, sessionStartedAt, minimumProbeCounter)
        val counter = next(root.counter)
        val secret = secretBytes(pair)
        val request = try {
            AapsV3ComboReadProtocol(secret).use { it.request(root.clientId, pair.masterInstallId, requestId, counter, issuedAt, spec) }
        } finally { secret.fill(0) }
        admit()
        val publication = publish(request)
        val record = ComboRead(root.clientId, pair.masterInstallId, requestId, counter, issuedAt,
            spec, publication.document.modifiedRevision, rosterRevision = pair.rosterRevision)
        put(COMBO_READ, json.encodeToString(record))
        put(ROOT, json.encodeToString(root.copy(counter = counter, lastIssuedAt = issuedAt)))
        admit()
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

    /** Cold inbound lane: stores only authenticated ciphertext, not a raw-history retention receipt. */
    suspend fun receiveComboResponse(sessionStartedAt: Long, minimumProbeCounter: Long,
                                     clock: () -> Long): AapsV3ComboReadSnapshot? = transaction {
        val record = comboReadOrNull() ?: return@transaction null
        val now = clock()
        val current = comboSnapshot(record, sessionStartedAt, minimumProbeCounter, now, clock)
        if (current.phase !in COMBO_ACTIVE) return@transaction current
        val (ack, sourceRevision) = comboAck(record) ?: return@transaction current
        val secret = secretBytes(pairing())
        val phase = try {
            AapsV3ComboReadProtocol(secret).use { it.response(ack, record.clientId, record.masterInstallId,
                record.requestId, record.counter, record.issuedAt, record.spec, now) }
        } finally { secret.fill(0) }
        val previous = record.response
        if (previous != null) {
            check(previous.ack == ack && previous.sourceRevision == sourceRevision && phase == record.phase)
        }
        val completedAt = clock()
        check(completedAt in now until record.issuedAt + AapsV3ComboReadProtocol.TTL_MS)
        requireLivePairing(completedAt, sessionStartedAt, minimumProbeCounter)
        if (previous != null) return@transaction current
        val accepted = record.copy(phase = phase, response = if (phase == AapsV3ComboReadPhase.RECEIVED)
            ComboResponse(ack, sourceRevision, completedAt) else null)
        put(COMBO_READ, json.encodeToString(accepted))
        val committedAt = clock()
        check(committedAt in completedAt until record.issuedAt + AapsV3ComboReadProtocol.TTL_MS)
        requireLivePairing(committedAt, sessionStartedAt, minimumProbeCounter)
        accepted.snapshot()
    }

    /** Release one committed response after transaction success. CONSUMED never acknowledges source retention. */
    suspend fun takeComboResponse(sessionStartedAt: Long, minimumProbeCounter: Long,
                                  clock: () -> Long): ComboResponseHandoff? {
        var owned: ComboResponseContent? = null
        var deadline = 0L
        var admittedAt = 0L
        try {
            val handoff = transaction {
                val record = comboReadOrNull() ?: return@transaction null
                val now = clock()
                if (comboSnapshot(record, sessionStartedAt, minimumProbeCounter, now, clock).phase !=
                    AapsV3ComboReadPhase.RECEIVED) return@transaction null
                val receipt = checkNotNull(record.response)
                check(now >= receipt.receivedAt)
                val (ack, revision) = checkNotNull(comboAck(record))
                check(ack == receipt.ack && revision == receipt.sourceRevision)
                val secret = secretBytes(pairing())
                val content = try {
                    AapsV3ComboReadProtocol(secret).use { it.openResponse(ack, record.clientId, record.masterInstallId,
                        record.requestId, record.counter, record.issuedAt, record.spec, now) }
                } finally { secret.fill(0) }
                owned = content
                check(content.phase == AapsV3ComboReadPhase.RECEIVED)
                deadline = record.issuedAt + AapsV3ComboReadProtocol.TTL_MS
                val completedAt = clock()
                check(completedAt in now until deadline)
                requireLivePairing(completedAt, sessionStartedAt, minimumProbeCounter)
                put(COMBO_READ, json.encodeToString(record.copy(phase = AapsV3ComboReadPhase.CONSUMED, response = null)))
                admittedAt = clock()
                check(admittedAt in completedAt until deadline)
                requireLivePairing(admittedAt, sessionStartedAt, minimumProbeCounter)
                ComboResponseHandoff(record.rosterRevision, content, sessionStartedAt, admittedAt)
            }
            if (handoff != null) check(clock() in admittedAt until deadline)
            owned = null
            return handoff
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { throw AapsV3TargetJournalFailure() }
        finally { owned?.close() }
    }

    private suspend fun comboAck(record: ComboRead): Pair<JsonObject, Long>? {
        val row = LocalNightscoutV3Store(db).get(LocalV3Collection.SETTINGS,
            "aaps_clientcontrol_ack_history_${record.clientId}") ?: return null
        check(!row.deleted && row.source == "local-v3-owner")
        if (row.modifiedRevision <= record.publicationRevision) return null
        val body = HistoryJson.parse(row.payloadJson.toByteArray(), 65_536)
        check(body["schemaVersion"] == JsonPrimitive(1))
        return HistoryJson.obj(body.getValue("ack")) to row.modifiedRevision
    }

    private suspend fun comboReadOrNull(): ComboRead? = read(COMBO_READ)?.let {
        json.decodeFromJsonElement<ComboRead>(it).also { record ->
            check(record.version == 1 && record.counter > 1 && record.publicationRevision > 0 && record.rosterRevision >= 0)
            listOf(record.clientId, record.masterInstallId, record.requestId).forEach(HistoryJson::uuid)
            check(record.issuedAt in 1..LocalNightscoutV3Store.MAX_TIME - AapsV3ComboReadProtocol.TTL_MS)
            check((record.phase == AapsV3ComboReadPhase.RECEIVED) == (record.response != null))
            record.response?.let { response ->
                check(response.sourceRevision > record.publicationRevision &&
                    response.receivedAt in record.issuedAt until record.issuedAt + AapsV3ComboReadProtocol.TTL_MS)
            }
        }
    }

    private suspend fun comboSnapshot(record: ComboRead, sessionStartedAt: Long,
                                      minimumProbeCounter: Long, now: Long, clock: () -> Long): AapsV3ComboReadSnapshot {
        if (record.phase !in COMBO_ACTIVE) return record.snapshot()
        val root = root(); val pair = pairing()
        val phase = when {
            record.clientId != root.clientId || record.masterInstallId != pair.masterInstallId ||
                record.rosterRevision <= 0 || record.rosterRevision != pair.rosterRevision ||
                pair.phase != AapsV3PairingPhase.ACTIVE || !pair.controlEnabled -> AapsV3ComboReadPhase.REVOKED
            root.pending != null || root.counter > record.counter || db.actionCommandDao().hasPendingTempTarget() ->
                AapsV3ComboReadPhase.SUPERSEDED
            now >= record.issuedAt + AapsV3ComboReadProtocol.TTL_MS -> AapsV3ComboReadPhase.EXPIRED
            else -> record.phase
        }
        if (phase != record.phase) {
            val retired = record.copy(phase = phase, response = null)
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
            HistoryJson.parse(row.payloadJson.toByteArray(), 65_536) == Json.parseToJsonElement(publicationBody(request, pair.verifiedSubject)))
        val completedAt = clock()
        check(completedAt in now until record.issuedAt + AapsV3ComboReadProtocol.TTL_MS)
        requireLivePairing(completedAt, sessionStartedAt, minimumProbeCounter)
        return record.snapshot(if (record.phase == AapsV3ComboReadPhase.ISSUED) LocalV3Write(row, false) else null)
    }

    private suspend fun publish(request: AapsV3TargetRequest): LocalV3Write {
        val clientId = request.json.getValue("clientId").jsonPrimitive.content
        val type = request.json.getValue("type").jsonPrimitive.content
        val id = if (type == "hello") "aaps_clientcontrol_hello_$clientId" else "aaps_clientcontrol_cmd_${type}_$clientId"
        val store = LocalNightscoutV3Store(db)
        val previous = store.get(LocalV3Collection.SETTINGS, id)
        check(previous == null || previous.source == "local-v3-target")
        val subject = if (type == "hello") null else pairingOrNull()?.takeIf {
            it.clientId == clientId && it.phase != AapsV3PairingPhase.REVOKED
        }?.verifiedSubject
        val input = LocalV3DocumentInput(LocalV3Collection.SETTINGS, id, "local-v3-target", DOC_DATE, publicationBody(request, subject))
        return if (previous == null) store.create(input) else store.update(input, previous.modifiedRevision)
    }

    private fun publicationBody(request: AapsV3TargetRequest, subject: String?) = buildJsonObject {
        put("date", DOC_DATE); put("utcOffset", 0); put("app", "AAPS"); put("schemaVersion", 1)
        subject?.let { put(LOCAL_SUBJECT_FIELD, it) }
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
        pair.localBinding?.let { check(pair.requiresSignedRoster && HEX.matches(it)) }
        check(pair.helloRevision > 0 && pair.rosterRevision >= 0 && pair.lastPongAt >= 0 && pair.pongCounter >= 0)
        pair.verifiedSubject?.let { check(pair.requiresSignedRoster && pair.secretRotated && isLocalClientSubject(it)) }
        pair.pendingToken?.let { check(pair.requiresSignedRoster && pair.pendingExchange != null && !pair.secretRotated &&
            isLocalClientSubject(it.subject) && it.token.matches(Regex("[A-Za-z0-9_-]{43}"))) }
        if (pair.requiresSignedRoster) {
            check(pair.enrolledAt in 1 until pair.expiresAt && pair.rosterSignedAt in 0..HistoryJson.maxTime + 5_000)
            check(pair.masterPublicPoint == null || pair.masterPublicPoint.length == 88)
            check(!pair.controlEnabled || (pair.masterPublicPoint != null && pair.rosterSignedAt > 0 && pair.verifiedRole != null))
            pair.pendingExchange?.let {
                check(!pair.secretRotated && pair.phase == AapsV3PairingPhase.WAITING &&
                    it.publicPoint.length == 88 && it.privateDer.length in 1..684)
            }
        } else {
            check(pair.pendingExchange == null && !pair.secretRotated)
        }
    } }
    private suspend fun pairing(): Pairing = checkNotNull(pairingOrNull())
    private fun secretBytes(pair: Pairing) = ByteArray(32) { pair.secretHex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    private fun secretHex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }
    private suspend fun record(id: String): Record = checkNotNull(recordOrNull(id))
    private suspend fun recordOrNull(id: String): Record? = read(id)?.let {
        json.decodeFromJsonElement<Record>(it).also { record ->
            check(record.version == 1 && HEX.matches(record.fingerprint) && record.counter > 0)
            record.targetInput?.let { check(HEX.matches(it) && record.mealGrams == null && record.operationId != null) }
            if (record.mealGrams != null || record.mealInput != null) {
                check(record.mealGrams != null && record.mealGrams in 1..120 && record.mealInput?.matches(HEX) == true &&
                    (record.commit != null) == (record.phase == AapsV3TargetPhase.COMMITTED) &&
                    record.phase in setOf(AapsV3TargetPhase.PREPARED, AapsV3TargetPhase.PREVIEW, AapsV3TargetPhase.EXPIRED_NO_COMMIT,
                        AapsV3TargetPhase.COMMITTED, AapsV3TargetPhase.APPLIED))
            }
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
    private fun actionFingerprint(row: io.aaps.copilot.data.local.entity.ActionCommandEntity) = digest(buildJsonArray {
        add(row.id); add(row.timestamp); add(row.idempotencyKey); add(row.payloadJson); add(row.safetyJson)
    }.toString())
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
    @Serializable private data class TargetPointer(val clientId: String, val target: LocalTargetReceiptQuery?)
    @OptIn(ExperimentalSerializationApi::class)
    @Serializable private data class Record(val fingerprint: String, val phase: AapsV3TargetPhase,
        val counter: Long, val prepare: JsonObject? = null, val preview: JsonObject? = null,
        val commit: JsonObject? = null, val version: Int = 1, val operationId: String? = null,
        @EncodeDefault(EncodeDefault.Mode.NEVER) val mealGrams: Int? = null,
        @EncodeDefault(EncodeDefault.Mode.NEVER) val mealInput: String? = null,
        @EncodeDefault(EncodeDefault.Mode.NEVER) val targetInput: String? = null) {
        fun snapshot(publication: LocalV3Write? = null) = AapsV3TargetJournalSnapshot(phase, counter, publication, operationId)
        fun compactIfTerminal() = if (phase in TERMINAL) copy(prepare = null, preview = null, commit = null, targetInput = null) else this
    }
    @Serializable private data class Pairing(val clientId: String, val masterInstallId: String, val secretHex: String,
        val expiresAt: Long, val helloRevision: Long, val phase: AapsV3PairingPhase = AapsV3PairingPhase.WAITING,
        val rosterRevision: Long = 0, val controlEnabled: Boolean = false, val lastPongAt: Long = 0, val pongCounter: Long = 0,
        val requiresSignedRoster: Boolean = false, val enrolledAt: Long = 0, val masterPublicPoint: String? = null,
        val rosterSignedAt: Long = 0, val verifiedRole: AapsFinalClientRole? = null,
        val pendingExchange: PendingExchange? = null, val secretRotated: Boolean = false,
        val pendingToken: PendingToken? = null, val verifiedSubject: String? = null, val localBinding: String? = null,
        val enrollmentRole: AapsFinalClientRole? = null) {
        fun snapshot(publication: LocalV3Write? = null) = AapsV3PairingSnapshot(phase, clientId, masterInstallId, controlEnabled,
            lastPongAt, pongCounter, publication, localBinding != null, enrollmentRole)
        override fun toString() = "NativePairing(redacted)"
    }
    @Serializable private class PendingToken(val subject: String, val token: String)
    @Serializable private data class PendingExchange(val publicPoint: String, val privateDer: String) {
        override fun toString() = "PendingExchange(redacted)"
    }
    @Serializable private data class Probe(val request: JsonObject)
    @Serializable private data class ComboRead(val clientId: String, val masterInstallId: String,
        val requestId: String, val counter: Long, val issuedAt: Long, val spec: AapsV3ComboReadSpec,
        val publicationRevision: Long, val phase: AapsV3ComboReadPhase = AapsV3ComboReadPhase.ISSUED,
        val version: Int = 1, val rosterRevision: Long = 0, val response: ComboResponse? = null) {
        fun snapshot(publication: LocalV3Write? = null) = AapsV3ComboReadSnapshot(requestId, counter, phase, publication)
        override fun toString() = "ComboReadRecord(redacted)"
    }
    @Serializable private data class ComboResponse(val ack: JsonObject, val sourceRevision: Long, val receivedAt: Long) {
        override fun toString() = "ComboResponse(redacted)"
    }
    @Serializable private data class Attempts(val startedAt: Long, val lastAt: Long, val count: Int)
    private companion object {
        const val ROOT = "owner"
        const val PAIRING = "pairing"
        const val PROBE = "probe"
        const val LAST_TARGET = "target-readback"
        const val COMBO_READ = "combo-read"
        const val ATTEMPTS = "pin-attempts"
        const val DOC_DATE = 946_684_800_001L
        val COMBO_ACTIVE = setOf(AapsV3ComboReadPhase.ISSUED, AapsV3ComboReadPhase.RECEIVED)
        val HEX = Regex("[0-9a-f]{64}")
        val COMMAND_KEY = Regex("command:[0-9a-f]{64}")
        val CLIENT_ID = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
        val TERMINAL = setOf(AapsV3TargetPhase.APPLIED, AapsV3TargetPhase.REJECTED,
            AapsV3TargetPhase.EXPIRED, AapsV3TargetPhase.EXPIRED_NO_COMMIT)
    }
}
