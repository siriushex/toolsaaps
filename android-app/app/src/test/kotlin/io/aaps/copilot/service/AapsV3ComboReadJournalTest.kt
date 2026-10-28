package io.aaps.copilot.service

import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import io.aaps.copilot.data.local.CopilotDatabase
import io.aaps.copilot.data.local.entity.ActionCommandEntity
import io.aaps.copilot.data.repository.LocalNightscoutV3Store
import io.aaps.copilot.data.repository.LocalV3Collection
import io.aaps.copilot.data.repository.LocalV3DocumentInput
import java.io.File
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class AapsV3ComboReadJournalTest {
    private lateinit var db: CopilotDatabase
    private lateinit var path: File
    private val client = "11111111-2222-4333-8444-555555555555"
    private val master = "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee"
    private val requestId = "00000000-0000-4000-8000-000000000001"
    private val secondId = "00000000-0000-4000-8000-000000000002"
    private val secret = ByteArray(32) { it.toByte() }
    private val at = 1_800_000_000_000L
    private var now = at + 1000
    private val cipher = KeystoreAapsV3JournalCipher { SecretKeySpec(ByteArray(32) { 7 }, "AES") }
    private val inventory = AapsV3ComboReadSpec.Inventory()
    private val target = AapsV3TargetSpec(90.0, 99.0, 30, "Eating Soon")
    private fun owner(rows: Long = 100_000) = AapsV3TargetJournal(db, cipher, rows)
    private fun openDb() = Room.databaseBuilder(ApplicationProvider.getApplicationContext<Context>(),
        CopilotDatabase::class.java, path.absolutePath).allowMainThreadQueries().build()
    @Before fun setup() {
        path = File.createTempFile("paired-read-", ".db").also { check(it.delete()) }
        db = openDb()
    }
    @After fun teardown() {
        db.close()
        listOf(path, File("${path.path}-wal"), File("${path.path}-shm")).forEach { it.delete() }
    }
    private suspend fun issue(id: String = requestId, spec: AapsV3ComboReadSpec = inventory,
                              journal: AapsV3TargetJournal = owner(), clock: () -> Long = { now }) =
        journal.requestComboRead(id, spec, at, 2, clock)
    private suspend fun snapshot() = owner().comboReadSnapshot(at, 2) { now }
    private suspend fun refused(block: suspend () -> Unit) {
        val failure = runCatching { block() }.exceptionOrNull()
        assertTrue("Expected journal failure", failure is AapsV3TargetJournalFailure)
        assertNull(failure!!.cause)
    }
    private suspend fun roster(enabled: Boolean = true, extraClient: String? = null) {
        val store = LocalNightscoutV3Store(db, { now })
        val input = LocalV3DocumentInput(LocalV3Collection.SETTINGS, "aaps", "local-v3-owner", 946_684_800_001,
            buildJsonObject { put("schemaVersion", 1); put("runningConfig", buildJsonObject {
                put("authorizedClients", buildJsonObject { put("clientIds", buildJsonArray {
                    add(client); extraClient?.let { add(it) }
                }) })
                put("syncedPrefs", buildJsonObject { put("ns_allow_client_control", enabled.toString()) })
            }) }.toString())
        val old = store.get(LocalV3Collection.SETTINGS, "aaps")
        owner().observeRoster((if (old == null) store.create(input) else store.update(input, old.modifiedRevision)).document)
    }
    private fun sign(raw: String) = Mac.getInstance("HmacSHA256").run {
        init(SecretKeySpec(secret, "HmacSHA256")); doFinal(raw.toByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) }
    }
    private suspend fun active() {
        AapsV3PairingCredential(client, master, at + 120_000, secret).use { owner().enroll(it, at) }
        roster()
        owner().probe(at + 100)!!
        val timestamp = at + 101
        assertTrue(owner().acknowledgeProbe(buildJsonObject {
            put("clientId", client); put("commandCounter", 2); put("phase", "Done"); put("status", "Ok")
            put("timestamp", timestamp); put("signature", sign("$client|2|Done|Ok|||$timestamp"))
        }, at + 102))
    }
    private suspend fun queuedTarget() = db.actionCommandDao().upsert(ActionCommandEntity(
        "waiting-target", now, "temp_target", "{}", "{}", "meal-request", "PENDING"))

    private fun encryptedPage(id: String = requestId): String = AapsV3ComboInventoryEnvelope(secret).use { envelope ->
        val page = AapsV3ComboInventory.create(secondId, "a".repeat(64), emptyList(), writerFailed = true).page(inventory)
        envelope.seal(AapsV3ComboInventoryBinding(client, master, id, 3, at + 1000, at + 31_000, inventory), page, now)
            .decodeToString()
    }
    private fun ack(payload: String? = encryptedPage(), status: String = "Ok", reason: String? = null,
                    counter: Long = 3, timestamp: Long = now, identity: String = client, phase: String = "Done") = buildJsonObject {
        put("clientId", identity); put("commandCounter", counter); put("phase", phase); put("status", status)
        put("timestamp", timestamp)
        reason?.let { put("reason", it) }; payload?.let { put("payload", it) }
        put("signature", sign("$identity|$counter|$phase|$status|${reason.orEmpty()}|${payload.orEmpty()}|$timestamp"))
    }
    private suspend fun publishAck(value: JsonObject, key: String = "aaps_clientcontrol_ack_history_$client",
                                   source: String = "local-v3-owner") {
        val store = LocalNightscoutV3Store(db, { now })
        val input = LocalV3DocumentInput(LocalV3Collection.SETTINGS, key, source, 946_684_800_001,
            buildJsonObject { put("schemaVersion", 1); put("ack", value) }.toString())
        val previous = store.get(LocalV3Collection.SETTINGS, key)
        if (previous == null) store.create(input) else store.update(input, previous.modifiedRevision)
    }
    private suspend fun receive(clock: () -> Long = { now }, journal: AapsV3TargetJournal = owner()) =
        journal.receiveComboResponse(at, 2, clock)
    private suspend fun readRow() = db.aapsV3TargetJournalDao().get("combo-read")!!

    @Test fun responseCommitsCiphertextOnlyAndReopensWithoutRepublicationOrCounterChange() = runBlocking {
        active(); issue()
        val reply = ack()
        publishAck(reply)
        val before = db.localNightscoutV3Dao().clock()
        val result = receive()!!
        assertEquals(AapsV3ComboReadPhase.RECEIVED, result.phase)
        assertNull(result.publication); assertNull(owner().pending())
        assertFalse(result.toString().contains(client))
        val retained = readRow()
        val plain = cipher.open(retained.row, retained.revision, retained.sealed)
        try {
            val record = Json.parseToJsonElement(plain.decodeToString()).jsonObject
            assertEquals(reply, record.getValue("response").jsonObject.getValue("ack"))
            assertFalse(plain.decodeToString().contains("writerFailed"))
        } finally { plain.fill(0) }
        db.close(); db = openDb()
        assertEquals(AapsV3ComboReadPhase.RECEIVED, snapshot()!!.phase)
        assertEquals(AapsV3ComboReadPhase.RECEIVED, receive()!!.phase)
        assertEquals(retained.revision, readRow().revision)
        assertArrayEquals(retained.sealed, readRow().sealed)
        assertEquals(before, db.localNightscoutV3Dao().clock())
        assertNull(issue(secondId))
        assertTrue(db.therapyDao().since(0).isEmpty())
        owner().withTargetProtocol { p -> assertEquals(4L, owner().prepare("meal-request", target, p, now) {}.counter) }
    }

    @Test fun maximumChunkResponseFitsJournalAndRemainsEncryptedAtRest() = runBlocking {
        active()
        val spec = AapsV3ComboReadSpec.Chunk(secondId, requestId, "a".repeat(64), "b".repeat(64), 32_768, 0)
        issue(spec = spec)
        val raw = ByteArray(32_768) { (it % 127).toByte() }
        val binding = AapsV3ComboChunkBinding(client, master, requestId, secondId, requestId,
            spec.pumpBinding, spec.sha256, 3, now, now + 30_000, spec.totalBytes, 0)
        val wire = AapsV3ComboChunkEnvelope(secret).use { it.seal(binding, raw, now).decodeToString() }
        publishAck(ack(wire))
        assertEquals(AapsV3ComboReadPhase.RECEIVED, receive()!!.phase)
        assertTrue(readRow().sealed.size in 40_000..65_536)
        assertArrayEquals(ByteArray(32_768) { (it % 127).toByte() }, raw)
    }

    @Test fun missingAndOrdinaryTargetAckCannotCompleteHistoryRead() = runBlocking {
        active(); issue()
        assertEquals(AapsV3ComboReadPhase.ISSUED, receive()!!.phase)
        publishAck(ack(), key = "aaps_clientcontrol_ack_$client")
        val before = readRow()
        assertEquals(AapsV3ComboReadPhase.ISSUED, receive()!!.phase)
        assertEquals(before.revision, readRow().revision)
        assertNull(owner().pending())
    }

    @Test fun wrongSourceAndOlderRowCannotBecomeReceived() = runBlocking {
        active(); publishAck(ack()); issue()
        refused { receive() }
        val store = LocalNightscoutV3Store(db)
        val row = store.get(LocalV3Collection.SETTINGS, "aaps_clientcontrol_ack_history_$client")!!
        db.localNightscoutV3Dao().put(row.copy(modifiedRevision = row.modifiedRevision + 100, source = "local-v3-target"))
        refused { receive() }
        assertEquals(AapsV3ComboReadPhase.ISSUED, snapshot()!!.phase)
    }

    @Test fun malformedSignedOrUnauthenticatedRepliesDoNotMutateReceipt() = runBlocking {
        active(); issue()
        val before = readRow()
        val valid = ack()
        val invalid = listOf(
            JsonObject(valid + ("signature" to JsonPrimitive("0".repeat(64)))),
            JsonObject(valid + ("unexpected" to JsonPrimitive(true))),
            JsonObject(valid + ("commandCounter" to JsonPrimitive("3"))),
            ack(counter = 4), ack(identity = secondId), ack(phase = "Accepted"), ack(status = "Unknown"),
            ack(timestamp = now - 5001), ack(timestamp = now + 5001),
            ack(reason = "unexpected"), ack(payload = null), ack(payload = "{}"),
            ack(encryptedPage(secondId)), ack(reason = "x".repeat(257), status = "Failed", payload = null),
            ack(status = "Failed"), ack(payload = "x".repeat(49_153)))
        for (reply in invalid) {
            publishAck(reply)
            refused { receive() }
            assertArrayEquals(before.sealed, readRow().sealed)
        }
        publishAck(valid)
        assertEquals(AapsV3ComboReadPhase.RECEIVED, receive()!!.phase)
    }

    @Test fun validSignatureCannotHideChangedAuthenticatedChunkGeometry() = runBlocking {
        active()
        val spec = AapsV3ComboReadSpec.Chunk(secondId, requestId, "a".repeat(64), "b".repeat(64), 32_771, 32_768)
        issue(spec = spec)
        val binding = AapsV3ComboChunkBinding(client, master, requestId, secondId, requestId,
            spec.pumpBinding, spec.sha256, 3, now, now + 30_000, spec.totalBytes, spec.offset)
        val wrong = AapsV3ComboChunkEnvelope(secret).use {
            it.seal(binding.copy(readId = secondId), byteArrayOf(1, 2, 3), now).decodeToString()
        }
        publishAck(ack(wrong)); refused { receive() }
        val right = AapsV3ComboChunkEnvelope(secret).use { it.seal(binding, byteArrayOf(1, 2, 3), now).decodeToString() }
        publishAck(ack(right))
        assertEquals(AapsV3ComboReadPhase.RECEIVED, receive()!!.phase)
    }

    @Test fun firstAcceptedResponseCannotBeReplacedEvenByAnotherValidEncryption() = runBlocking {
        active(); issue(); publishAck(ack()); receive()
        val before = readRow()
        publishAck(ack())
        refused { receive() }
        assertArrayEquals(before.sealed, readRow().sealed)
    }

    @Test fun signedFailureIsTerminalAndCannotBeResurrectedWithSuccess() = runBlocking {
        active(); issue(); publishAck(ack(payload = null, status = "Failed", reason = "unsupported"))
        assertEquals(AapsV3ComboReadPhase.REJECTED, receive()!!.phase)
        val before = readRow()
        publishAck(ack())
        assertEquals(AapsV3ComboReadPhase.REJECTED, receive()!!.phase)
        assertArrayEquals(before.sealed, readRow().sealed)
        assertEquals(4L, issue(secondId)!!.counter)
    }

    @Test fun signedExpiryIsTerminalWithoutRawPayloadOrTargetAck() = runBlocking {
        active(); issue(); publishAck(ack(payload = null, status = "Expired", reason = "expired"))
        assertEquals(AapsV3ComboReadPhase.EXPIRED, receive()!!.phase)
        assertNull(owner().pending())
        assertNull(snapshot()!!.publication)
    }

    @Test fun targetPreemptsReceivedReadAndOrdinaryAckIsUntouched() = runBlocking {
        active(); issue(); publishAck(ack()); receive()
        publishAck(ack(payload = null), key = "aaps_clientcontrol_ack_$client")
        val ordinary = LocalNightscoutV3Store(db).get(LocalV3Collection.SETTINGS, "aaps_clientcontrol_ack_$client")!!
        owner().withTargetProtocol { p -> owner().prepare("meal-request", target, p, now) {} }
        assertEquals(AapsV3ComboReadPhase.SUPERSEDED, receive()!!.phase)
        assertEquals(AapsV3TargetPhase.PREPARED, owner().pending()!!.phase)
        assertEquals(ordinary, LocalNightscoutV3Store(db).get(LocalV3Collection.SETTINGS, ordinary.identifier))
    }

    @Test fun queuedTargetPreemptsLateResponseBeforeParsingUntrustedWire() = runBlocking {
        active(); issue(); publishAck(buildJsonObject { put("invalid", true) }); queuedTarget()
        assertEquals(AapsV3ComboReadPhase.SUPERSEDED, receive()!!.phase)
    }

    @Test fun receivedReadStillExpiresAndNeverReturnsARequestForResend() = runBlocking {
        active(); issue(); publishAck(ack()); receive()
        assertNull(issue()!!.publication)
        now += 30_000
        assertEquals(AapsV3ComboReadPhase.EXPIRED, receive()!!.phase)
        assertEquals(4L, issue(secondId)!!.counter)
        // The isolated ACK still belongs to the old shared counter.
        refused { receive() }
    }

    @Test fun receivedReadCannotSurviveUnobservedControlOffOnOrUnverifiedSession() = runBlocking {
        active(); issue(); publishAck(ack()); receive()
        refused { owner().receiveComboResponse(at + 500, 2) { now } }
        refused { owner().receiveComboResponse(at, 3) { now } }
        roster(false); roster(true)
        assertEquals(AapsV3ComboReadPhase.REVOKED, receive()!!.phase)
    }

    @Test fun responseClockExpiryAfterReceiptWriteRollsBackAndCancellationPropagates() = runBlocking {
        active(); issue(); publishAck(ack())
        val before = readRow()
        var calls = 0
        refused { receive(clock = { if (++calls < 4) now else now + 30_000 }) }
        assertArrayEquals(before.sealed, readRow().sealed)
        calls = 0
        val failure = runCatching { receive(clock = {
            if (++calls == 4) throw CancellationException("synthetic cancellation") else now
        }) }.exceptionOrNull()
        assertTrue(failure is CancellationException)
        assertArrayEquals(before.sealed, readRow().sealed)
    }

    @Test fun responseStorageFailureAndOuterTransactionCannotExposeSuccessfulReceipt() = runBlocking {
        active(); issue(); publishAck(ack())
        val before = readRow()
        val broken = object : AapsV3JournalCipher by cipher {
            override fun seal(row: String, revision: Long, plain: ByteArray): ByteArray {
                if (row == "combo-read") throw java.io.IOException("synthetic storage failure")
                return cipher.seal(row, revision, plain)
            }
        }
        refused { receive(journal = AapsV3TargetJournal(db, broken)) }
        db.withTransaction {
            refused { receive() }
            withContext(Dispatchers.IO) { refused { receive() } }
        }
        assertArrayEquals(before.sealed, readRow().sealed)
        assertEquals(AapsV3ComboReadPhase.RECEIVED, receive()!!.phase)
    }

    @Test fun readUsesSharedCounterButNeverClaimsTherapySlot() = runBlocking {
        active()
        val read = issue()!!
        assertEquals(AapsV3ComboReadPhase.ISSUED, read.phase)
        assertEquals(3L, read.counter)
        assertNull(owner().pending())
        assertFalse(read.toString().contains(client))
        val envelope = Json.parseToJsonElement(read.publication!!.document.payloadJson).jsonObject.getValue("envelope").jsonObject
        val raw = envelope.getValue("payload").jsonPrimitive.content
        assertEquals("combo_history_read_v1", envelope.getValue("type").jsonPrimitive.content)
        assertEquals(master, Json.parseToJsonElement(raw).jsonObject.getValue("masterInstallId").jsonPrimitive.content)
        assertEquals(sign("$client|3|$now|${now + 30_000}|true|combo_history_read_v1|$raw"),
            envelope.getValue("signature").jsonPrimitive.content)
        owner().withTargetProtocol { protocol ->
            assertEquals(4L, owner().prepare("meal-request", target, protocol, now + 1) {}.counter)
        }
        assertEquals(AapsV3ComboReadPhase.SUPERSEDED, snapshot()!!.phase)
        assertEquals("meal-request", owner().pending()!!.operationId)
        assertTrue(db.therapyDao().since(0).isEmpty())
    }

    @Test fun queuedOrUnresolvedTargetAlwaysDefersHistoryWithoutCounterConsumption() = runBlocking {
        active(); queuedTarget()
        assertNull(issue())
        owner().withTargetProtocol { protocol ->
            assertEquals(3L, owner().prepare("meal-request", target, protocol, now) {}.counter)
        }
        assertNull(issue())
        assertEquals(3L, owner().pending()!!.counter)
    }

    @Test fun queuedTargetWithdrawsRecoverableReadEvenBeforeTargetPreparation() = runBlocking {
        active(); issue(); queuedTarget()
        assertEquals(AapsV3ComboReadPhase.SUPERSEDED, snapshot()!!.phase)
        assertNull(snapshot()!!.publication)
        assertEquals("PENDING", db.actionCommandDao().byIdempotencyKey("meal-request")!!.status)
    }

    @Test fun sameFileReopenRecoversExactPublicationWithoutRepublishingOrIncrementingCounter() = runBlocking {
        active()
        val first = issue()!!
        val before = db.localNightscoutV3Dao().clock()
        db.close(); db = openDb()
        val recovered = snapshot()!!
        assertEquals(first.publication!!.document, recovered.publication!!.document)
        assertFalse(recovered.publication!!.changed)
        assertEquals(before, db.localNightscoutV3Dao().clock())
        assertEquals(first.counter, issue()!!.counter)
        assertNull(issue(secondId))
        refused { issue(requestId, AapsV3ComboReadSpec.Inventory(limit = 8)) }
    }

    @Test fun expiryAndCounterPreemptionNeverResignOrReactivateOldRead() = runBlocking {
        active(); issue()
        now += 30_000
        assertEquals(AapsV3ComboReadPhase.EXPIRED, snapshot()!!.phase)
        assertNull(issue()!!.publication)
        assertEquals(4L, issue(secondId)!!.counter)
        now = at + 61_000
        assertNotNull(owner().probe(now))
        assertEquals(AapsV3ComboReadPhase.SUPERSEDED, snapshot()!!.phase)
        assertNull(snapshot()!!.publication)
    }

    @Test fun revokeAndControlOffCannotReturnCommittedWireOrClearTargets() = runBlocking {
        active(); issue(); roster(false)
        assertEquals(AapsV3ComboReadPhase.REVOKED, snapshot()!!.phase)
        roster(true)
        assertNull(snapshot()!!.publication)
        refused { issue(secondId, clock = { at + 100_000 }) }
        owner().revokePairing()
        refused { issue(secondId) }
    }

    @Test fun currentSessionAndOutstandingProbeMustBeVerifiedBeforeRead() = runBlocking {
        active()
        refused { owner().requestComboRead(requestId, inventory, at + 500, 2) { now } }
        refused { owner().requestComboRead(requestId, inventory, at, 3) { now } }
        refused { owner().requestComboRead(requestId, inventory, at, 0) { now } }
        now = at + 61_000
        owner().probe(now)!!
        assertNull(issue())
    }

    @Test fun disableThenEnableWithoutReadObservationCannotResurrectPublicationAfterReopen() = runBlocking {
        active(); issue()
        roster(false); roster(true)
        db.close(); db = openDb()
        assertEquals(AapsV3ComboReadPhase.REVOKED, snapshot()!!.phase)
        assertNull(issue()!!.publication)
        assertEquals(4L, issue(secondId)!!.counter)
    }

    @Test fun anyNewRosterRequiresFreshReadAdmissionButDoesNotBlockTarget() = runBlocking {
        active(); issue(); roster(extraClient = secondId)
        assertEquals(AapsV3ComboReadPhase.REVOKED, snapshot()!!.phase)
        owner().withTargetProtocol { p ->
            assertEquals(4L, owner().prepare("meal-request", target, p, now + 1) {}.counter)
        }
    }

    @Test fun identicalRosterObservationDoesNotInvalidateRead() = runBlocking {
        active()
        val first = issue()!!
        roster()
        assertEquals(AapsV3ComboReadPhase.ISSUED, snapshot()!!.phase)
        assertEquals(first.publication!!.document, snapshot()!!.publication!!.document)
    }

    @Test fun legacyReadWithoutRosterBindingCannotReturnSendablePublication() = runBlocking {
        active(); issue()
        val dao = db.aapsV3TargetJournalDao()
        val row = dao.get("combo-read")!!
        val plain = cipher.open(row.row, row.revision, row.sealed)
        val oldBody = Json.parseToJsonElement(plain.decodeToString()).jsonObject
        plain.fill(0)
        val legacy = JsonObject(oldBody - "rosterRevision").toString().toByteArray()
        dao.put(row.copy(revision = row.revision + 1,
            sealed = cipher.seal(row.row, row.revision + 1, legacy)))
        legacy.fill(0)
        assertEquals(AapsV3ComboReadPhase.REVOKED, snapshot()!!.phase)
        assertNull(snapshot()!!.publication)
        assertEquals(4L, issue(secondId)!!.counter)
    }

    @Test fun externalAndInheritedTransactionsCannotLeakReadRequestsOrReceipts() = runBlocking {
        active()
        db.withTransaction {
            refused { issue() }
            withContext(Dispatchers.IO) { refused { snapshot() } }
        }
        assertEquals(3L, issue()!!.counter)
        db.withTransaction { refused { snapshot() } }
    }

    @Test fun admissionClockChangeAndCancellationRollBackOutboxAndCounter() = runBlocking {
        active()
        var calls = 0
        refused { issue(clock = { if (++calls < 3) now else now + 90_001 }) }
        assertNull(snapshot())
        calls = 0
        val cancellation = runCatching { issue(clock = {
            if (++calls >= 3) throw CancellationException("synthetic cancellation") else now
        }) }.exceptionOrNull()
        assertTrue(cancellation is CancellationException)
        assertNull(snapshot())
        assertEquals(3L, issue()!!.counter)
    }

    @Test fun boundedLaneReservesSpaceForTherapyAndRejectsCorruptWire() = runBlocking {
        active()
        assertNull(issue(journal = owner(rows = 4)))
        val read = issue(journal = owner(rows = 5))!!
        val row = read.publication!!.document
        db.localNightscoutV3Dao().put(row.copy(payloadJson = "{}"))
        refused { snapshot() }
        owner().withTargetProtocol { p ->
            assertEquals(4L, owner(rows = 5).prepare("meal-request", target, p, now + 1) {}.counter)
        }
        assertEquals(AapsV3ComboReadPhase.SUPERSEDED, snapshot()!!.phase)
    }

    @Test fun concurrentReadersReserveOnlyOneRequestAndTargetCanPreemptIt() = runBlocking {
        active()
        val results = listOf(requestId, secondId).map { id -> async(Dispatchers.IO) { issue(id) } }.awaitAll()
        assertEquals(1, results.count { it != null })
        assertEquals(3L, results.single { it != null }!!.counter)
        owner().withTargetProtocol { p -> assertEquals(4L, owner().prepare("meal-request", target, p, now + 1) {}.counter) }
    }

    @Test fun rootWriteFailureRollsBackReadDocumentReceiptAndSharedCounter() = runBlocking {
        active()
        val before = db.localNightscoutV3Dao().clock()
        val broken = object : AapsV3JournalCipher by cipher {
            override fun seal(row: String, revision: Long, plain: ByteArray): ByteArray {
                if (row == "owner") throw java.io.IOException("synthetic storage failure")
                return cipher.seal(row, revision, plain)
            }
        }
        refused { issue(journal = AapsV3TargetJournal(db, broken)) }
        assertEquals(before, db.localNightscoutV3Dao().clock())
        assertNull(snapshot())
        assertEquals(3L, issue()!!.counter)
    }

    @Test fun historyCannotTakeReservedWireCapacityForTargetCommands() = runBlocking {
        active()
        val clock = db.localNightscoutV3Dao().clock()!!
        db.localNightscoutV3Dao().putClock(clock.copy(payloadBytes = 64L * 1024 * 1024 - 65_536))
        assertNull(issue())
        db.localNightscoutV3Dao().putClock(clock.copy(documentCount = 99_998))
        assertNull(issue())
        db.localNightscoutV3Dao().putClock(clock.copy(documentCount = 99_997))
        assertNull(issue())
        db.localNightscoutV3Dao().putClock(clock.copy(payloadBytes = 64L * 1024 * 1024 - 3L * 65_536))
        assertNull(issue())
        db.localNightscoutV3Dao().putClock(clock)
        owner().withTargetProtocol { p -> assertEquals(3L, owner().prepare("meal-request", target, p, now) {}.counter) }
    }

    @Test fun recoveredWireCannotEscapeAfterClockAdvancedDuringStorageRead() = runBlocking {
        active(); issue()
        var calls = 0
        refused { owner().comboReadSnapshot(at, 2) { if (++calls == 1) now else now + 30_000 } }
        assertEquals(3L, snapshot()!!.counter)
    }
}
