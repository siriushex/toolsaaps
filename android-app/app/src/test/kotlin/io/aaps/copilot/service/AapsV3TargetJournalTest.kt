package io.aaps.copilot.service

import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import io.aaps.copilot.data.local.CopilotDatabase
import io.aaps.copilot.data.repository.LocalNightscoutV3Store
import io.aaps.copilot.data.repository.LocalV3Collection
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
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
class AapsV3TargetJournalTest {
    private lateinit var db: CopilotDatabase
    private val client = "11111111-2222-4333-8444-555555555555"
    private val at = 1_800_000_000_000L
    private val secret = ByteArray(32) { it.toByte() }
    private val cipher = TestCipher()
    private val target get() = AapsV3TargetSpec(90.0, 99.0, 30, "Eating Soon")
    private val protocol get() = AapsV3TargetProtocol(secret)
    private fun journal() = AapsV3TargetJournal(db, cipher)

    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), CopilotDatabase::class.java)
            .allowMainThreadQueries().build()
    }
    @After fun teardown() { db.close() }

    private fun ack(counter: Long, payload: String? = null, timestamp: Long = at + 1,
                    status: String = "Ok", reason: String? = null): JsonObject {
        val raw = "$client|$counter|Done|$status|${reason ?: ""}|${payload ?: ""}|$timestamp"
        val signature = Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(secret, "HmacSHA256"))
            doFinal(raw.toByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) }
        }
        return buildJsonObject {
            put("clientId", client); put("commandCounter", counter); put("phase", "Done"); put("status", status)
            reason?.let { put("reason", it) }
            put("payload", payload?.let(::JsonPrimitive) ?: JsonNull); put("timestamp", timestamp); put("signature", signature)
        }
    }
    private suspend fun preview(owner: AapsV3TargetJournal, p: AapsV3TargetProtocol) {
        owner.acknowledge("meal-1", p, ack(1,
            """{"bolusId":321,"lines":[{"role":"Normal","text":"synthetic target"}]}"""), at + 2)
    }

    private suspend fun refused(block: suspend () -> Unit) {
        val failure = runCatching { block() }.exceptionOrNull()
        assertTrue("Expected fixed journal failure, got $failure", failure is AapsV3TargetJournalFailure)
        assertNull(failure!!.cause)
    }

    @Test fun externalTransactionCannotPublishPrepareBeforeCounterCommit() = runBlocking {
        val owner = journal(); owner.bind(client)
        protocol.use { p ->
            db.withTransaction {
                refused { owner.prepare("meal-1", target, p, at) { fail("guard must not run") } }
            }
            assertEquals(0L, db.localNightscoutV3Dao().count())
            assertNull(owner.pending())
            assertEquals(1L, owner.prepare("meal-1", target, p, at) {}.counter)
        }
    }

    @Test fun externalTransactionCannotReturnAReplayedPrepareReceipt() = runBlocking {
        val owner = journal(); owner.bind(client)
        protocol.use { p ->
            owner.prepare("meal-1", target, p, at) {}
            db.withTransaction { refused { owner.prepare("meal-1", target, p, at + 1) {} } }
            assertNull(owner.prepare("meal-1", target, p, at + 2) {}.publication)
        }
    }

    @Test fun externalTransactionCannotPublishCommitBeforeCounterCommit() = runBlocking {
        val owner = journal(); owner.bind(client)
        protocol.use { p ->
            owner.prepare("meal-1", target, p, at) {}; preview(owner, p)
            db.withTransaction {
                refused { owner.commit("meal-1", p, at + 3) { fail("guard must not run") } }
            }
            assertEquals(AapsV3TargetPhase.PREVIEW, owner.pending()!!.phase)
            assertEquals(1L, db.localNightscoutV3Dao().count())
            assertEquals(2L, owner.commit("meal-1", p, at + 3) {}.counter)
        }
    }

    @Test fun externalTransactionCannotExposeAppliedAckOrSettlePendingOwner() = runBlocking {
        val owner = journal(); owner.bind(client)
        protocol.use { p ->
            owner.prepare("meal-1", target, p, at) {}; preview(owner, p)
            owner.commit("meal-1", p, at + 3) {}
            db.withTransaction {
                refused { owner.acknowledge("meal-1", p, ack(2, timestamp = at + 4), at + 5) }
                refused { owner.acknowledgePending(p, ack(2, timestamp = at + 4), at + 5) }
            }
            assertEquals(AapsV3TargetPhase.COMMITTED, owner.pending()!!.phase)
            assertEquals(AapsV3TargetPhase.APPLIED,
                owner.acknowledgePending(p, ack(2, timestamp = at + 4), at + 5)!!.phase)
            assertNull(owner.pending())
        }
    }

    @Test fun externalTransactionCannotExposeJournalReadsOrExpiryResult() = runBlocking {
        val owner = journal(); owner.bind(client)
        protocol.use { p -> owner.prepare("meal-1", target, p, at) {} }
        db.withTransaction {
            refused { owner.status("meal-1") }
            refused { owner.pending() }
            refused { owner.hasNativeState() }
            refused { owner.expire(at + 8001) }
            refused { owner.bind(client) }
        }
        assertEquals(AapsV3TargetPhase.PREPARED, owner.pending()!!.phase)
        assertEquals(AapsV3TargetPhase.EXPIRED_NO_COMMIT, owner.expire(at + 8001)!!.phase)
    }

    @Test fun inheritedTransactionCannotPublishAfterDispatcherSwitch() = runBlocking {
        val owner = journal(); owner.bind(client)
        protocol.use { p ->
            db.withTransaction {
                withContext(Dispatchers.IO) {
                    refused { owner.prepare("meal-1", target, p, at) {} }
                    refused { journal().pending() }
                }
            }
            assertNull(owner.pending())
            assertEquals(0L, db.localNightscoutV3Dao().count())
            assertEquals(1L, owner.prepare("meal-1", target, p, at) {}.counter)
        }
    }

    @Test fun guardCannotReenterCounterOwnerThroughAnotherJournalInstance() = runBlocking {
        val owner = journal(); owner.bind(client)
        protocol.use { p ->
            refused {
                owner.prepare("meal-1", target, p, at) {
                    journal().prepare("meal-2", target, p, at) {}
                }
            }
            assertNull(owner.pending())
            assertNull(owner.status("meal-1"))
            assertNull(owner.status("meal-2"))
            assertEquals(0L, db.localNightscoutV3Dao().count())
            assertEquals(1L, owner.prepare("meal-1", target, p, at) {}.counter)
        }
    }

    @Test fun requestCounterAndPublicationCommitTogetherAndDuplicateNeverPublishesAgain() = runBlocking {
        val owner = journal(); owner.bind(client)
        protocol.use { p ->
            val first = owner.prepare("meal-1", target, p, at) {}
            assertEquals(AapsV3TargetPhase.PREPARED, first.phase)
            assertNotNull(first.publication)
            val again = journal().prepare("meal-1", target, p, at + 1) { error("duplicate must not dispatch") }
            assertNull(again.publication)
            assertEquals(1L, again.counter)
            assertEquals(1L, db.localNightscoutV3Dao().count())
        }
    }

    @Test fun changedIdempotentPayloadAndConcurrentOwnerAreRejectedWithoutCounterAllocation() = runBlocking {
        val owner = journal(); owner.bind(client)
        protocol.use { p ->
            owner.prepare("meal-1", target, p, at) {}
            assertTrue(runCatching { owner.prepare("meal-1", target.copy(lowMgdl = 91.0), p, at + 1) {} }.isFailure)
            assertTrue(runCatching { owner.prepare("meal-2", target, p, at + 1) {} }.isFailure)
            assertEquals(1L, owner.pending()!!.counter)
        }
    }

    @Test fun previewIsNotApplicationAndCommitNeedsASeparateFreshGuard() = runBlocking {
        val owner = journal(); owner.bind(client)
        protocol.use { p ->
            owner.prepare("meal-1", target, p, at) {}; preview(owner, p)
            assertEquals(AapsV3TargetPhase.PREVIEW, owner.pending()!!.phase)
            assertTrue(runCatching { owner.commit("meal-1", p, at + 3) { error("not armed") } }.isFailure)
            assertEquals(1L, owner.pending()!!.counter)
            val result = owner.commit("meal-1", p, at + 3) {}
            assertEquals(AapsV3TargetPhase.COMMITTED, result.phase); assertEquals(2L, result.counter)
            assertNotNull(result.publication)
            assertNull(journal().commit("meal-1", p, at + 4) { error("no retry") }.publication)
            assertEquals(2L, db.localNightscoutV3Dao().count())
        }
    }

    @Test fun restartAndTimeoutHoldUnknownCommitUntilSignedAckAndNeverFallback() = runBlocking {
        val owner = journal(); owner.bind(client)
        protocol.use { p ->
            owner.prepare("meal-1", target, p, at) {}; preview(owner, p); owner.commit("meal-1", p, at + 3) {}
            val restarted = journal()
            assertEquals(AapsV3TargetPhase.UNKNOWN, restarted.expire(at + 60_000)!!.phase)
            assertTrue(runCatching { restarted.prepare("meal-2", target, p, at + 60_001) {} }.isFailure)
            assertNull(restarted.commit("meal-1", p, at + 60_001) { error("no replay") }.publication)
            assertEquals(AapsV3TargetPhase.APPLIED, restarted.acknowledge("meal-1", p,
                ack(2, timestamp = at + 4), at + 60_001).phase)
            assertNull(restarted.pending())
            assertEquals(3L, restarted.prepare("meal-2", target, p, at + 60_002) {}.counter)
            assertNull(restarted.prepare("meal-1", target, p, at + 60_003) {}.publication)
        }
    }

    @Test fun expiredPreparationCannotBeCommittedOrReplayedAfterRestart() = runBlocking {
        val owner = journal(); owner.bind(client)
        protocol.use { p ->
            owner.prepare("meal-1", target, p, at) {}; preview(owner, p)
            assertEquals(AapsV3TargetPhase.EXPIRED_NO_COMMIT, journal().expire(at + 8001)!!.phase)
            assertNull(owner.pending())
            assertTrue(runCatching { owner.commit("meal-1", p, at + 8002) {} }.isFailure)
            assertNull(owner.prepare("meal-1", target, p, at + 8002) {}.publication)
            assertEquals(2L, owner.prepare("meal-2", target, p, at + 8003) {}.counter)
        }
    }

    @Test fun lateMasterTransactionExpirySettlesWithoutApplicationOrReplay() = runBlocking {
        assertLateMasterRefusal("Expired", "Expired", AapsV3TargetPhase.EXPIRED)
    }

    @Test fun lateMasterRevocationSettlesWithoutApplicationOrReplay() = runBlocking {
        assertLateMasterRefusal("Failed", "NotPaired", AapsV3TargetPhase.REJECTED)
    }

    @Test fun lateMasterControlOffSettlesWithoutApplicationOrReplay() = runBlocking {
        assertLateMasterRefusal("Failed", "ControlDisabled", AapsV3TargetPhase.REJECTED)
    }

    private suspend fun assertLateMasterRefusal(status: String, reason: String, phase: AapsV3TargetPhase) {
        val owner = journal(); owner.bind(client)
        db.actionCommandDao().upsert(io.aaps.copilot.data.local.entity.ActionCommandEntity(
            id = "pending-target", timestamp = at, type = "temp_target", payloadJson = "{}", safetyJson = "{}",
            idempotencyKey = "meal-1", status = "PENDING"))
        protocol.use { p ->
            owner.prepare("meal-1", target, p, at) {}; preview(owner, p)
            owner.commit("meal-1", p, at + 3) {}
            val restarted = journal()
            assertEquals(AapsV3TargetPhase.UNKNOWN, restarted.expire(at + 60_000)!!.phase)
            val response = ack(2, timestamp = at + 8003, status = status, reason = reason)
            val forged = JsonObject(response + ("signature" to JsonPrimitive("0".repeat(64))))
            assertEquals(AapsV3TargetPhase.UNKNOWN, restarted.acknowledge("meal-1", p, forged, at + 60_001).phase)
            assertNotNull(restarted.pending())
            assertEquals(phase, restarted.acknowledge("meal-1", p, response, at + 60_002).phase)
            assertNull(restarted.pending())
            assertNull(restarted.commit("meal-1", p, at + 60_003) { error("refused command cannot replay") }.publication)
            assertEquals(2L, db.localNightscoutV3Dao().count())
            assertEquals("PENDING", db.actionCommandDao().byIdempotencyKey("meal-1")!!.status)
            assertTrue(db.therapyDao().since(0).isEmpty())
            assertEquals(3L, restarted.prepare("meal-2", target, p, at + 60_004) {}.counter)
        }
    }

    @Test fun suspendedGuardCannotSignUsingItsOldClockOrCommitAnExpiredPreview() = runBlocking {
        val owner = journal(); owner.bind(client)
        protocol.use { p ->
            var current = at
            owner.prepare("meal-1", target, p, at, currentTime = { current }) { current += 5000 }
            val wire = LocalNightscoutV3Store(db).get(LocalV3Collection.SETTINGS, "aaps_clientcontrol_cmd_batch_prepare_$client")!!
            val envelope = Json.parseToJsonElement(wire.payloadJson).jsonObject.getValue("envelope").jsonObject
            assertEquals(at + 5000, envelope.getValue("timestamp").jsonPrimitive.long)
            owner.acknowledge("meal-1", p, ack(1,
                """{"bolusId":321,"lines":[{"role":"Normal","text":"synthetic target"}]}""", at + 5001), at + 5002)
            assertTrue(runCatching {
                owner.commit("meal-1", p, at + 5003, currentTime = { current }) { current = at + 14_000 }
            }.isFailure)
            assertEquals(AapsV3TargetPhase.PREVIEW, owner.pending()!!.phase)
            assertNull(LocalNightscoutV3Store(db).get(LocalV3Collection.SETTINGS, "aaps_clientcontrol_cmd_bolus_commit_$client"))
        }
    }

    @Test fun backgroundCommitAckSettlesOnlyExistingPendingTargetWithoutClinicalWrites() = runBlocking {
        val owner = journal(); owner.bind(client)
        db.actionCommandDao().upsert(io.aaps.copilot.data.local.entity.ActionCommandEntity(
            id = "pending-target", timestamp = at, type = "temp_target", payloadJson = "{}", safetyJson = "{}",
            idempotencyKey = "meal-1", status = "PENDING"))
        protocol.use { p ->
            owner.prepare("meal-1", target, p, at) {}; preview(owner, p)
            assertEquals("PENDING", db.actionCommandDao().byIdempotencyKey("meal-1")!!.status)
            owner.commit("meal-1", p, at + 3) {}
            owner.acknowledgePending(p, ack(2, timestamp = at + 4), at + 5)
        }
        assertEquals("SENT", db.actionCommandDao().byIdempotencyKey("meal-1")!!.status)
        assertTrue(db.therapyDao().since(0).isEmpty())
        assertTrue(db.glucoseDao().since(0).isEmpty())
    }

    @Test fun encryptionOrWireFailureRollsBackCounterAndDocumentTogether() = runBlocking {
        val owner = journal(); owner.bind(client)
        protocol.use { p ->
            cipher.failSeal = true
            assertTrue(runCatching { owner.prepare("meal-1", target, p, at) {} }.isFailure)
            assertEquals(0L, db.localNightscoutV3Dao().count())
            cipher.failSeal = false
            assertNull(owner.pending())
            assertEquals(1L, owner.prepare("meal-1", target, p, at) {}.counter)
        }
    }

    @Test fun corruptionMissingKeyAndChangedPairingFailClosedWithoutReplacingState() = runBlocking {
        val owner = journal(); owner.bind(client)
        protocol.use { p -> owner.prepare("meal-1", target, p, at) {} }
        assertTrue(runCatching { owner.bind("11111111-2222-4333-8444-666666666666") }.isFailure)
        assertTrue(runCatching { AapsV3TargetJournal(db, TestCipher()).pending() }.isFailure)
        db.openHelper.writableDatabase.execSQL("UPDATE aaps_v3_target_journal SET revision = revision + 1")
        assertTrue(runCatching { owner.pending() }.isFailure)
        assertEquals(1L, db.localNightscoutV3Dao().count())
    }

    @Test fun concurrentDuplicateOperationsReserveOnlyOneCounterAndPublication() = runBlocking {
        journal().bind(client)
        protocol.use { p ->
            val results = (1..8).map { async { journal().prepare("meal-1", target, p, at) {} } }.awaitAll()
            assertEquals(1, results.count { it.publication != null })
            assertTrue(results.all { it.counter == 1L })
        }
    }

    @Test fun forgedAckCannotUnlockTheOwnerOrCreateCommit() = runBlocking {
        val owner = journal(); owner.bind(client)
        protocol.use { p ->
            owner.prepare("meal-1", target, p, at) {}
            val bad = JsonObject(ack(1) + ("signature" to JsonPrimitive("0".repeat(64))))
            assertEquals(AapsV3TargetPhase.PREPARED, owner.acknowledge("meal-1", p, bad, at + 1).phase)
            assertTrue(runCatching { owner.commit("meal-1", p, at + 2) {} }.isFailure)
            assertEquals(1L, owner.pending()!!.counter)
        }
    }

    @Test fun durableRowsAreSealedAndWireKeepsOnlyTheTargetEnvelope() = runBlocking {
        val owner = journal(); owner.bind(client)
        protocol.use { p -> owner.prepare("meal-1", target, p, at) {} }
        db.openHelper.readableDatabase.query("SELECT sealed FROM aaps_v3_target_journal").use {
            while (it.moveToNext()) {
                val raw = it.getBlob(0).toString(Charsets.UTF_8)
                assertFalse(raw.contains(client)); assertFalse(raw.contains("meal-1")); assertFalse(raw.contains("signature"))
            }
        }
        val wire = LocalNightscoutV3Store(db).get(LocalV3Collection.SETTINGS,
            "aaps_clientcontrol_cmd_batch_prepare_$client")!!
        assertEquals("local-v3-target", wire.source)
        val envelope = Json.parseToJsonElement(wire.payloadJson).jsonObject.getValue("envelope").jsonObject
        val action = Json.parseToJsonElement(envelope.getValue("payload").jsonPrimitive.content)
            .jsonObject.getValue("actions").jsonArray.single().jsonObject
        assertEquals("copilot:meal-1", action.getValue("notes").jsonPrimitive.content)
        assertEquals(0, db.therapyDao().since(0).size)
        assertFalse(owner.pending().toString().contains(client))
    }

    @Test fun capacityFailureAndCancelledGuardDoNotConsumeCounterOrPublish() = runBlocking {
        val owner = AapsV3TargetJournal(db, cipher, maximumRows = 2); owner.bind(client)
        protocol.use { p ->
            val cancelled = runCatching { owner.prepare("meal-1", target, p, at) { throw CancellationException() } }
            assertTrue(cancelled.exceptionOrNull() is CancellationException)
            assertEquals(0L, db.localNightscoutV3Dao().count())
            assertEquals(1L, owner.prepare("meal-1", target, p, at) {}.counter)
            owner.expire(at + 8001)
            assertTrue(runCatching { owner.prepare("meal-2", target, p, at + 8002) {} }.isFailure)
            assertNull(owner.pending())
            assertEquals(1L, db.localNightscoutV3Dao().count())
            val wire = LocalNightscoutV3Store(db).get(LocalV3Collection.SETTINGS,
                "aaps_clientcontrol_cmd_batch_prepare_$client")!!
            val body = Json.parseToJsonElement(wire.payloadJson).jsonObject.getValue("envelope").jsonObject
            assertEquals(1L, body.getValue("counter").jsonPrimitive.long)
        }
    }

    @Test fun finalRootWriteFailureRollsBackWireAndReceiptToo() = runBlocking {
        val owner = journal(); owner.bind(client)
        protocol.use { p ->
            cipher.remainingSeals = 1
            assertTrue(runCatching { owner.prepare("meal-1", target, p, at) {} }.isFailure)
            assertEquals(0L, db.localNightscoutV3Dao().count())
            assertEquals(1L, db.aapsV3TargetJournalDao().count())
            cipher.remainingSeals = null
            assertEquals(1L, journal().prepare("meal-1", target, p, at) {}.counter)
        }
    }

    @Test fun reopeningTheActualDatabaseKeepsTheUnknownOwnerAndCounter() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "native-target-restart.db"
        context.deleteDatabase(name); db.close()
        fun open() = Room.databaseBuilder(context, CopilotDatabase::class.java, name).allowMainThreadQueries().build()
        try {
            db = open()
            val owner = journal(); owner.bind(client)
            protocol.use { p ->
                owner.prepare("meal-1", target, p, at) {}; preview(owner, p); owner.commit("meal-1", p, at + 3) {}
                db.close(); db = open()
                assertEquals(AapsV3TargetPhase.COMMITTED, journal().pending()!!.phase)
                assertEquals(AapsV3TargetPhase.UNKNOWN, journal().expire(at + 30_000)!!.phase)
                assertTrue(runCatching { journal().prepare("meal-2", target, p, at + 30_001) {} }.isFailure)
                journal().acknowledge("meal-1", p, ack(2, timestamp = at + 4), at + 30_001)
                assertEquals(3L, journal().prepare("meal-2", target, p, at + 30_002) {}.counter)
            }
        } finally { db.close(); context.deleteDatabase(name) }
    }

    @Test fun clockRegressionAndUnrelatedCorrelationNotesNeverCreateAnotherCommand() = runBlocking {
        val owner = journal(); owner.bind(client)
        protocol.use { p ->
            assertTrue(runCatching { owner.prepare("meal-1", target.copy(notes = "unrelated"), p, at) {} }.isFailure)
            owner.prepare("meal-1", target, p, at) {}; owner.expire(at + 8001)
            assertTrue(runCatching { owner.prepare("meal-2", target, p, at - 1) {} }.isFailure)
            assertEquals(2L, owner.prepare("meal-2", target, p, at + 8002) {}.counter)
        }
    }

    private class TestCipher : AapsV3JournalCipher {
        private val key = SecretKeySpec(ByteArray(32).also { SecureRandom().nextBytes(it) }, "AES")
        var failSeal = false
        var remainingSeals: Int? = null
        override fun seal(row: String, revision: Long, plain: ByteArray): ByteArray {
            check(!failSeal)
            remainingSeals?.let { check(it > 0); remainingSeals = it - 1 }
            return Cipher.getInstance("AES/GCM/NoPadding").run {
                init(Cipher.ENCRYPT_MODE, key); updateAAD("$row|$revision".toByteArray()); iv + doFinal(plain)
            }
        }
        override fun open(row: String, revision: Long, sealed: ByteArray): ByteArray = Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, sealed.copyOfRange(0, 12)))
            updateAAD("$row|$revision".toByteArray()); doFinal(sealed.copyOfRange(12, sealed.size))
        }
    }
}
