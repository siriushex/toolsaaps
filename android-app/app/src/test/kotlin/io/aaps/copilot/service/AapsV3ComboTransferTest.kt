package io.aaps.copilot.service

import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import io.aaps.copilot.data.local.ComboJournalDatabase
import io.aaps.copilot.data.local.CopilotDatabase
import io.aaps.copilot.data.local.entity.ActionCommandEntity
import io.aaps.copilot.data.repository.LocalNightscoutV3Store
import io.aaps.copilot.data.repository.LocalV3Collection
import io.aaps.copilot.data.repository.LocalV3DocumentInput
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.CRC32
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
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
class AapsV3ComboTransferTest {
    private lateinit var db: CopilotDatabase
    private lateinit var privateDb: ComboJournalDatabase
    private lateinit var journal: AapsV3TargetJournal
    private lateinit var store: AapsV3ComboJournalStore
    private val client = "11111111-2222-4333-8444-555555555555"
    private val master = "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee"
    private val snapshot = "00000000-0000-4000-8000-000000000001"
    private val binding = "a".repeat(64)
    private val at = 1_800_000_000_000L
    private val now = AtomicLong(at + 1000)
    private val secret = ByteArray(32) { it.toByte() }
    private val readKey = "aaps_clientcontrol_cmd_combo_history_read_v1_$client"
    private val replies = "aaps_clientcontrol_ack_history_$client"
    private val counter = AtomicLong(0)

    @Before fun setup() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, CopilotDatabase::class.java).allowMainThreadQueries().build()
        privateDb = Room.inMemoryDatabaseBuilder(context, ComboJournalDatabase::class.java).allowMainThreadQueries().build()
        journal = AapsV3TargetJournal(db, KeystoreAapsV3JournalCipher { SecretKeySpec(ByteArray(32) { 7 }, "AES") })
        store = AapsV3ComboJournalStore(privateDb)
        AapsV3PairingCredential(client, master, at + 120_000, secret).use { journal.enroll(it, at) }
        val row = settings("aaps", buildJsonObject { put("runningConfig", buildJsonObject {
            put("authorizedClients", buildJsonObject { put("clientIds", buildJsonArray { add(client) }) })
            put("syncedPrefs", buildJsonObject { put("ns_allow_client_control", "true") })
        }) })
        journal.observeRoster(row)
        journal.probe(at + 100)
        check(journal.acknowledgeProbe(ack(2, null, at + 101), at + 102))
    }
    @After fun teardown() { privateDb.close(); db.close() }

    private suspend fun settings(id: String, body: JsonObject) = LocalNightscoutV3Store(db, now::get).let { wire ->
        val old = wire.get(LocalV3Collection.SETTINGS, id)
        val input = LocalV3DocumentInput(LocalV3Collection.SETTINGS, id, "local-v3-owner", 946_684_800_001,
            JsonObject(body + ("schemaVersion" to JsonPrimitive(1))).toString())
        (if (old == null) wire.create(input) else wire.update(input, old.modifiedRevision)).document
    }
    private fun ack(count: Long, payload: String?, timestamp: Long = now.get(), failed: Boolean = false): JsonObject {
        val status = if (failed) "Failed" else "Ok"
        val signature = Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(secret, "HmacSHA256"))
            doFinal("$client|$count|Done|$status||${payload.orEmpty()}|$timestamp".toByteArray())
                .joinToString("") { "%02x".format(it.toInt() and 255) }
        }
        return buildJsonObject {
            put("clientId", client); put("commandCounter", count); put("phase", "Done"); put("status", status)
            put("timestamp", timestamp); put("signature", signature); payload?.let { put("payload", it) }
        }
    }
    private fun raw(size: Int = 1000): ByteArray {
        val body = buildJsonObject {
            put("version", 1); put("binding", binding); put("phase", "BEGIN")
            put("observation", buildJsonObject {
                put("version", 1); put("scope", "reader_session"); put("coverage", "unverified")
                put("state", "READING"); put("readStartedAt", 1000)
                listOf("validatedBlocks", "confirmedBlocks", "parsedEvents", "unmappedEvents").forEach { put(it, 0) }
                listOf("gapObserved", "clockChangeObserved", "unmappedEventObserved", "interruptedReadObserved",
                    "malformedBlockObserved", "journalWriteFailed").forEach { put(it, false) }
            })
        }.toString().toByteArray()
        val prefix = ByteArrayOutputStream().also { output -> DataOutputStream(output).use {
            it.writeInt(body.size); it.write(body); it.writeInt(CRC32().apply { update(body) }.value.toInt())
        } }.toByteArray()
        return prefix + ByteArray(size - prefix.size)
    }
    private fun reads(count: Int = 1, size: Int = 1000) = (1..count).associate {
        "10000000-0000-4000-8000-${it.toString().padStart(12, '0')}" to raw(size)
    }
    private fun catalog(reads: Map<String, ByteArray>) = AapsV3ComboInventory.create(snapshot, binding,
        reads.map { (id, bytes) -> AapsV3ComboInventoryEntry(id, bytes.size, AapsV3ComboJournalAssembly.digest(bytes),
            false, true, true, false) }, unscopedCorruptReads = 2, writerFailed = true)

    private fun CoroutineScope.peer(reads: Map<String, ByteArray>, after: suspend () -> Unit = {}, failed: Boolean = false) =
        launch(Dispatchers.IO) {
            val inventory = catalog(reads)
            db.localNightscoutV3Dao().observeRevision().collect {
                val row = LocalNightscoutV3Store(db).get(LocalV3Collection.SETTINGS, readKey) ?: return@collect
                val request = Json.parseToJsonElement(row.payloadJson).jsonObject.getValue("envelope").jsonObject
                val count = request.getValue("counter").jsonPrimitive.long
                if (count <= counter.get()) return@collect
                counter.set(count)
                val start = request.getValue("timestamp").jsonPrimitive.long
                val payload = Json.parseToJsonElement(request.getValue("payload").jsonPrimitive.content).jsonObject
                val id = payload.getValue("requestId").jsonPrimitive.content
                val query = payload.getValue("query").jsonObject
                val encoded = if (failed) null else when (query.getValue("kind").jsonPrimitive.content) {
                    "inventory" -> {
                        val spec = AapsV3ComboReadSpec.Inventory(query["snapshotId"]?.jsonPrimitive?.content,
                            query["afterReadId"]?.jsonPrimitive?.content, query.getValue("limit").jsonPrimitive.int)
                        AapsV3ComboInventoryEnvelope(secret).use { it.seal(AapsV3ComboInventoryBinding(client, master,
                            id, count, start, start + 30_000, spec), inventory.page(spec), now.get()).decodeToString() }
                    }
                    else -> {
                        val readId = query.getValue("readId").jsonPrimitive.content
                        val offset = query.getValue("offset").jsonPrimitive.int
                        val bytes = reads.getValue(readId)
                        val bound = AapsV3ComboChunkBinding(client, master, id, snapshot, readId, binding,
                            AapsV3ComboJournalAssembly.digest(bytes), count, start, start + 30_000, bytes.size, offset)
                        AapsV3ComboChunkEnvelope(secret).use {
                            it.seal(bound, bytes.copyOfRange(offset, offset + bound.chunkBytes), now.get()).decodeToString()
                        }
                    }
                }
                after()
                settings(replies, buildJsonObject { put("ack", ack(count, encoded, failed = failed)) })
            }
        }
    private suspend fun transfer(admit: suspend () -> Unit = {}) = withTimeout(15_000) {
        AapsV3ComboTransfer(db, journal, store, now::get).run(at, 2, admit)
    }
    private suspend fun waitingRequest() = withTimeout(4000) {
        db.localNightscoutV3Dao().observeRevision().map { LocalNightscoutV3Store(db).get(LocalV3Collection.SETTINGS, readKey) }
            .filterNotNull().first()
    }
    private suspend fun fails(block: suspend () -> Unit) {
        val error = runCatching { block() }.exceptionOrNull()
        assertTrue(error.toString(), error is ComboTransferFailure)
        // Coroutine stack recovery may copy the same redacted exception into cause.
        generateSequence(error) { it.cause }.take(8).forEach {
            assertTrue(it is ComboTransferFailure)
            assertEquals("combo_transfer_unavailable", it.message)
        }
    }

    @Test fun transfersExactFragmentsAndSecondCycleDoesNotDownloadRetainedBytes() = runBlocking {
        val reads = reads(size = 40_000)
        val peer = peer(reads)
        try {
            val result = transfer()
            assertEquals(ComboTransferPhase.RETAINED, result.phase)
            assertEquals(1, result.retainedReads); assertTrue(result.hazardKinds > 0)
            assertArrayEquals(reads.values.single(), store.read(master, binding, reads.keys.single())!!.rawBytes())
            assertEquals(5, counter.get().toInt())
            assertEquals(ComboTransferPhase.RETAINED, transfer().phase)
            assertEquals(6, counter.get().toInt())
            assertTrue(db.therapyDao().since(0).isEmpty()); assertNull(journal.pending())
        } finally { peer.cancelAndJoin() }
    }
    @Test fun multiPageInventoryPreservesEveryReadAndAdverseEvidence() = runBlocking {
        val reads = reads(33); val peer = peer(reads)
        try {
            val result = transfer()
            assertEquals(ComboTransferPhase.RETAINED, result.phase); assertEquals(33, result.retainedReads)
            assertEquals(37, counter.get().toInt()); assertTrue(result.hazardKinds >= 3)
        } finally { peer.cancelAndJoin() }
    }
    @Test fun byteBudgetStopsOnlyBetweenWholeReads() = runBlocking {
        val reads = reads(3, 1024 * 1024); val peer = peer(reads)
        try {
            val result = transfer()
            assertEquals(ComboTransferPhase.PARTIAL, result.phase)
            assertEquals(2, result.retainedReads); assertEquals(1, result.missingReads)
            assertEquals(2 * 1024 * 1024L, privateDb.journalDao().totals().bytes)
        } finally { peer.cancelAndJoin() }
    }
    @Test fun existingConflictIsNotOverwrittenOrDownloaded() = runBlocking {
        val reads = reads(); val old = raw(1001); val id = reads.keys.single()
        store.retain(master, binding, id, AapsV3ComboJournalAssembly.digest(old), old)
        val peer = peer(reads)
        try {
            val result = transfer()
            assertEquals(ComboTransferPhase.PARTIAL, result.phase); assertEquals(1, result.conflictingReads)
            assertEquals(3, counter.get().toInt()); assertArrayEquals(old, store.read(master, binding, id)!!.rawBytes())
        } finally { peer.cancelAndJoin() }
    }
    @Test fun signedUnsupportedReplyDoesNotRetryOrPublishTherapy() = runBlocking {
        val peer = peer(emptyMap(), failed = true)
        try {
            fails { transfer() }; assertEquals(3, counter.get().toInt())
            assertEquals(0, privateDb.journalDao().totals().reads.toInt()); assertNull(journal.pending())
        } finally { peer.cancelAndJoin() }
    }
    @Test fun expiredOrRewoundReplyCannotRetainData() = runBlocking {
        for (delta in listOf(30_000L, -1L)) {
            now.set(at + 1000)
            val peer = peer(emptyMap(), after = { now.addAndGet(delta) })
            try { fails { transfer() } } finally { peer.cancelAndJoin() }
            // Advance beyond the last request before the next independent attempt.
            now.set(at + 32_000)
            journal.comboReadSnapshot(at, 2, now::get)
        }
        assertEquals(0, privateDb.journalDao().totals().reads.toInt())
    }
    @Test fun cancellationWhileWaitingDoesNotResendOrRetainPartialData() = runBlocking {
        val worker = launch { transfer() }
        val before = waitingRequest()
        worker.cancelAndJoin()
        assertEquals(before, LocalNightscoutV3Store(db).get(LocalV3Collection.SETTINGS, readKey))
        assertEquals(0, privateDb.journalDao().totals().reads.toInt())
    }
    @Test fun secondCallerCannotInvalidateTheActiveCycle() = runBlocking {
        val transfer = AapsV3ComboTransfer(db, journal, store, now::get)
        val first = async { transfer.run(at, 2) {} }; waitingRequest()
        fails { transfer.run(at, 2) {} }
        val peer = peer(emptyMap())
        try { assertEquals(ComboTransferPhase.RETAINED, withTimeout(4000) { first.await() }.phase) }
        finally { peer.cancelAndJoin(); first.cancelAndJoin() }
    }
    @Test fun stoppedRuntimeAfterReplyCannotConsumeOrRetain() = runBlocking {
        val allowed = AtomicBoolean(true)
        val peer = peer(reads(), after = { allowed.set(false) })
        try { fails { transfer { check(allowed.get()) } }; assertEquals(0, privateDb.journalDao().totals().reads.toInt()) }
        finally { peer.cancelAndJoin() }
    }
    @Test fun queuedTargetPreemptsAnIncomingHistoryReply() = runBlocking {
        val peer = peer(reads(), after = {
            db.actionCommandDao().upsert(ActionCommandEntity("queued-target", now.get(), "temp_target", "{}", "{}",
                "waiting-meal", "PENDING"))
        })
        try { fails { transfer() }; assertEquals(0, privateDb.journalDao().totals().reads.toInt()) }
        finally { peer.cancelAndJoin() }
    }
    @Test fun privateAuditDoesNotHoldMainJournalAndLateCompletionIsSuppressed() = runBlocking {
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val blocker = launch(Dispatchers.IO) { privateDb.withTransaction { entered.complete(Unit); release.await() } }
        entered.await()
        val peer = peer(emptyMap())
        val worker = async { runCatching { transfer() } }
        try {
            withTimeout(4000) {
                db.aapsV3TargetJournalDao().let {
                    while (journal.comboReadSnapshot(at, 2, now::get)?.phase != AapsV3ComboReadPhase.CONSUMED) delay(10)
                }
            }
            val target = journal.withTargetProtocol { journal.prepare("pending-target",
                AapsV3TargetSpec(100.0, 100.0, 30, "Eating Soon"), it, now.get()) {} }
            assertNotNull(target.publication)
            release.complete(Unit); blocker.join()
            assertTrue(worker.await().exceptionOrNull() is ComboTransferFailure)
        } finally { release.complete(Unit); blocker.cancelAndJoin(); worker.cancelAndJoin(); peer.cancelAndJoin() }
    }

    @Test fun chunkExpiryDuringPrivateWriteCannotReturnSuccessfulTransfer() = runBlocking {
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        var blocker: Job? = null
        val peer = peer(reads(), after = {
            if (counter.get() == 4L) {
                blocker = launch(Dispatchers.IO) {
                    privateDb.withTransaction { entered.complete(Unit); release.await() }
                }
                entered.await()
            }
        })
        val worker = async { runCatching { transfer() } }
        try {
            withTimeout(4000) {
                entered.await()
                while (journal.comboReadSnapshot(at, 2, now::get)?.phase != AapsV3ComboReadPhase.CONSUMED) delay(10)
            }
            now.addAndGet(30_000); release.complete(Unit); blocker!!.join()
            assertTrue(worker.await().exceptionOrNull() is ComboTransferFailure)
        } finally { release.complete(Unit); blocker?.cancelAndJoin(); worker.cancelAndJoin(); peer.cancelAndJoin() }
    }
}
