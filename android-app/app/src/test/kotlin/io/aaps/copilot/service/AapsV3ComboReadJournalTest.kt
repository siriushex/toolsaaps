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
    private suspend fun roster(enabled: Boolean = true) {
        val store = LocalNightscoutV3Store(db, { now })
        val input = LocalV3DocumentInput(LocalV3Collection.SETTINGS, "aaps", "local-v3-owner", 946_684_800_001,
            buildJsonObject { put("schemaVersion", 1); put("runningConfig", buildJsonObject {
                put("authorizedClients", buildJsonObject { put("clientIds", buildJsonArray { add(client) }) })
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
