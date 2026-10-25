package io.aaps.copilot.service

import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import io.aaps.copilot.data.local.CopilotDatabase
import io.aaps.copilot.data.repository.LocalNightscoutV3Store
import io.aaps.copilot.data.repository.LocalV3Collection
import io.aaps.copilot.data.repository.LocalV3DocumentInput
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
class AapsV3PairingJournalTest {
    private lateinit var db: CopilotDatabase
    private val client = "11111111-2222-4333-8444-555555555555"
    private val master = "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee"
    private val secret = ByteArray(32) { it.toByte() }
    private val at = 1_800_000_000_000L
    private val cipher = KeystoreAapsV3JournalCipher { SecretKeySpec(ByteArray(32) { (it + 1).toByte() }, "AES") }
    private fun owner() = AapsV3TargetJournal(db, cipher)
    private fun credential(id: String = client, expiry: Long = at + 120_000) = AapsV3PairingCredential(id, master, expiry, secret)
    @Before fun setup() { db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), CopilotDatabase::class.java).allowMainThreadQueries().build() }
    @After fun teardown() { db.close() }
    private suspend fun refused(block: suspend () -> Unit) {
        val failure = runCatching { block() }.exceptionOrNull()
        assertTrue("Expected fixed journal failure, got $failure", failure is AapsV3TargetJournalFailure)
        assertNull(failure!!.cause)
    }

    @Test fun externalTransactionCannotExposeEnrollmentOrConsumePinAttempt() = runBlocking {
        db.withTransaction {
            refused { owner().reservePairingAttempt(at) }
            credential().use { c -> refused { owner().enroll(c, at) } }
        }
        assertEquals(0L, db.aapsV3TargetJournalDao().count())
        assertEquals(0L, db.localNightscoutV3Dao().count())
        credential().use { assertNotNull(owner().enroll(it, at).publication) }
    }

    @Test fun externalTransactionCannotReturnPairingStateOrRosterActivation() = runBlocking {
        credential().use { owner().enroll(it, at) }
        val row = roster(listOf(client))
        db.withTransaction {
            refused { owner().pairingSnapshot() }
            refused { owner().observeRoster(row) }
            refused { owner().revokePairing() }
        }
        assertEquals(AapsV3PairingPhase.WAITING, owner().pairingSnapshot()!!.phase)
        assertEquals(AapsV3PairingPhase.ACTIVE, owner().observeRoster(row).phase)
    }

    @Test fun externalTransactionCannotPublishProbeBeforeCounterCommit() = runBlocking {
        credential().use { owner().enroll(it, at) }
        owner().observeRoster(roster(listOf(client)))
        db.withTransaction { refused { owner().probe(at + 200) } }
        assertEquals(2L, db.localNightscoutV3Dao().count())
        val wire = owner().probe(at + 200)!!.document.payloadJson
        val envelope = Json.parseToJsonElement(wire).jsonObject.getValue("envelope").jsonObject
        assertEquals(2L, envelope.getValue("counter").jsonPrimitive.long)
    }

    @Test fun inheritedTransactionCannotExposeSigningProtocolOrPairingState() = runBlocking {
        credential().use { owner().enroll(it, at) }
        db.withTransaction {
            withContext(Dispatchers.IO) {
                refused { owner().withTargetProtocol { fail("protocol must not escape") } }
                refused { owner().pairingSnapshot() }
            }
        }
        owner().withTargetProtocol { assertNotNull(it) }
    }
    private suspend fun roster(ids: List<String>, enabled: Boolean = true): io.aaps.copilot.data.local.entity.LocalNightscoutV3DocumentEntity {
        val store = LocalNightscoutV3Store(db, { at + 100 })
        val input = LocalV3DocumentInput(LocalV3Collection.SETTINGS, "aaps", "local-v3-owner", 946_684_800_001,
            buildJsonObject { put("schemaVersion", 1); put("runningConfig", buildJsonObject {
                put("authorizedClients", buildJsonObject { put("clientIds", JsonArray(ids.map(::JsonPrimitive))) })
                put("syncedPrefs", buildJsonObject { put("ns_allow_client_control", enabled.toString()) })
            }) }.toString())
        val old = store.get(LocalV3Collection.SETTINGS, "aaps")
        return (if (old == null) store.create(input) else store.update(input, old.modifiedRevision)).document
    }

    @Test fun enrollmentPersistsHelloAndCounterAtomicallyAndNeverRepublishes() = runBlocking {
        val journal = owner()
        credential().use { journal.enroll(it, at) }
        val hello = LocalNightscoutV3Store(db).get(LocalV3Collection.SETTINGS, "aaps_clientcontrol_hello_$client")!!
        val envelope = Json.parseToJsonElement(hello.payloadJson).jsonObject.getValue("envelope").jsonObject
        assertEquals(1L, envelope.getValue("counter").jsonPrimitive.long)
        assertFalse(envelope.getValue("wantsAck").jsonPrimitive.boolean)
        assertEquals(AapsV3PairingPhase.WAITING, journal.pairingSnapshot()!!.phase)
        credential().use { assertNull(owner().enroll(it, at + 1).publication) }
        assertEquals(1L, db.localNightscoutV3Dao().count())
        assertFalse(hello.payloadJson.contains("secretHex"))
        assertFalse(hello.payloadJson.contains("00010203"))
    }

    @Test fun rosterAfterHelloIsRequiredAndRemovalIsStickyRevocation() = runBlocking {
        credential().use { owner().enroll(it, at) }
        val document = roster(listOf(client))
        assertEquals(AapsV3PairingPhase.ACTIVE, owner().observeRoster(document).phase)
        val removed = document.copy(modifiedRevision = document.modifiedRevision + 1,
            payloadJson = document.payloadJson.replace("\"$client\"", ""))
        assertEquals(AapsV3PairingPhase.REVOKED, owner().observeRoster(removed).phase)
        assertEquals(AapsV3PairingPhase.REVOKED, owner().observeRoster(document.copy(modifiedRevision = removed.modifiedRevision + 1)).phase)
    }

    @Test fun wrongOrOlderRosterNeverActivatesPairing() = runBlocking {
        val old = roster(listOf(client))
        credential().use { owner().enroll(it, at + 200) }
        assertEquals(AapsV3PairingPhase.WAITING, owner().observeRoster(old).phase)
        assertEquals(AapsV3PairingPhase.WAITING, owner().observeRoster(old.copy(identifier = "aaps_state", modifiedRevision = at + 300)).phase)
    }

    @Test fun probeAndTargetUseSamePersistentCounterAfterEnrollment() = runBlocking {
        credential().use { owner().enroll(it, at) }
        owner().observeRoster(roster(listOf(client)))
        val probe = owner().probe(at + 200)!!
        val request = Json.parseToJsonElement(probe.document.payloadJson).jsonObject.getValue("envelope").jsonObject
        assertEquals(2L, request.getValue("counter").jsonPrimitive.long)
        val timestamp = at + 201
        val signature = Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(secret, "HmacSHA256")); doFinal("$client|2|Done|Ok|||$timestamp".toByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) }
        }
        val ack = buildJsonObject {
            put("clientId", client); put("commandCounter", 2); put("phase", "Done"); put("status", "Ok")
            put("timestamp", timestamp); put("signature", signature)
        }
        assertTrue(owner().acknowledgeProbe(ack, at + 202))
        assertFalse(owner().acknowledgeProbe(ack, at + 90_000))
        db.withTransaction { refused { owner().acknowledgeProbe(ack, at + 202) } }
        owner().withTargetProtocol { protocol ->
            val prepared = owner().prepare("native-test", AapsV3TargetSpec(90.0, 99.0, 30, "Eating Soon"), protocol, at + 300) {
                owner().requireLivePairing(at + 300, at, 2)
            }
            assertEquals(3L, prepared.counter)
            assertEquals("native-test", owner().pending()!!.operationId)
            assertNull(owner().probe(at + 301))
        }
    }

    @Test fun explicitRevokeCannotClearPendingCommandOrResetCounter() = runBlocking<Unit> {
        credential().use { owner().enroll(it, at) }; owner().observeRoster(roster(listOf(client)))
        owner().withTargetProtocol { protocol -> owner().prepare("pending", AapsV3TargetSpec(90.0, 99.0, 30, "Eating Soon"), protocol, at + 200) {} }
        owner().revokePairing()
        assertEquals(AapsV3PairingPhase.REVOKED, owner().pairingSnapshot()!!.phase)
        assertNotNull(owner().pending())
        assertThrows(AapsV3TargetJournalFailure::class.java) { runBlocking { credential(master).use { owner().enroll(it, at + 300) } } }
    }

    @Test fun pinAttemptBudgetPersistsAndDoesNotResetOnClockRegression() = runBlocking<Unit> {
        repeat(5) { owner().reservePairingAttempt(at + it) }
        assertThrows(AapsV3TargetJournalFailure::class.java) { runBlocking { owner().reservePairingAttempt(at + 6) } }
        assertThrows(AapsV3TargetJournalFailure::class.java) { runBlocking { owner().reservePairingAttempt(at - 1) } }
        owner().reservePairingAttempt(at + 600_000)
        credential(expiry = at + 720_000).use { owner().enroll(it, at + 600_001) }
    }

    @Test fun newIdentityAfterExplicitRevocationKeepsOldCounterAndCannotReviveOldIdentity() = runBlocking<Unit> {
        credential().use { owner().enroll(it, at) }
        owner().observeRoster(roster(listOf(client)))
        owner().probe(at + 200)
        owner().revokePairing()
        val replacement = "99999999-8888-4777-8666-555555555555"
        credential(replacement).use { owner().enroll(it, at + 300) }
        owner().observeRoster(roster(listOf(replacement)))
        val probe = owner().probe(at + 400)!!
        val wire = Json.parseToJsonElement(probe.document.payloadJson).jsonObject.getValue("envelope").jsonObject
        assertEquals(JsonPrimitive(replacement), wire["clientId"])
        assertEquals(JsonPrimitive(2), wire["counter"])
        assertNotNull(db.aapsV3TargetJournalDao().get("retired-owner:$client"))
        owner().revokePairing()
        assertThrows(AapsV3TargetJournalFailure::class.java) { runBlocking { credential().use { owner().enroll(it, at + 500) } } }
    }

    @Test fun missingPrivateStateWithExistingOutboxCannotSelectUnpairedLegacyRoute() = runBlocking {
        assertFalse(owner().hasNativeState())
        owner().reservePairingAttempt(at)
        assertFalse(owner().hasNativeState())
        credential().use { owner().enroll(it, at) }
        db.openHelper.writableDatabase.execSQL("DELETE FROM aaps_v3_target_journal")
        assertTrue(owner().hasNativeState())
        assertNull(owner().pairingSnapshot())
    }

    @Test fun pendingLegacyTargetBlocksEnrollmentBeforeHelloPublication() = runBlocking<Unit> {
        db.actionCommandDao().upsert(io.aaps.copilot.data.local.entity.ActionCommandEntity(
            id = "legacy-pending", timestamp = at, type = "temp_target", payloadJson = "{}", safetyJson = "{}",
            idempotencyKey = "manual:target:pending", status = "PENDING"))
        assertThrows(AapsV3TargetJournalFailure::class.java) { runBlocking { credential().use { owner().enroll(it, at) } } }
        assertFalse(owner().hasNativeState())
        assertEquals(0L, db.localNightscoutV3Dao().count())
    }
}
