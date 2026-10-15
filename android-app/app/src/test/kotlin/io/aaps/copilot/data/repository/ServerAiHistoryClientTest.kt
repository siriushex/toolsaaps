package io.aaps.copilot.data.repository

import io.aaps.copilot.security.ServerAiConnectionIdentity
import io.aaps.copilot.security.ServerAiConnectionManager
import io.aaps.copilot.security.ServerAiConnectionPersistence
import io.aaps.copilot.security.ServerAiConnectionResponse
import io.aaps.copilot.security.ServerAiConnectionTransport
import io.aaps.copilot.security.ServerAiHistoryPersistence
import io.aaps.copilot.security.ServerAiHistoryRoute
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ServerAiHistoryClientTest {
    private val now = 1_800_000_000_000L
    private val id = "2ec58479-e05b-4805-87f6-5bb06b04bb2d"

    private class Pending : ServerAiHistoryPersistence {
        var value: String? = null
        var failWrites = false
        var onRead: (() -> Unit)? = null
        override suspend fun read(): String? { onRead?.invoke(); return value }
        override suspend fun write(value: String) { if (failWrites) throw IOException("synthetic storage failure"); this.value = value }
    }

    private class Call(val path: String, val body: ByteArray, val headers: Map<String, String>, val limit: Int)

    private fun manager(pending: Pending, calls: MutableList<Call>, proofs: MutableList<ByteArray> = mutableListOf(),
        accessExpiry: Long = now + 600_000,
        clock: () -> Long = { now },
        fingerprintProvider: () -> String = { "c".repeat(64) },
        onSign: () -> Unit = { },
        respond: (Call) -> ServerAiConnectionResponse): ServerAiConnectionManager {
        var session = """{"session":{"access_token":"access.${"a".repeat(43)}","refresh_token":"refresh.${"b".repeat(43)}","access_expires_ms":$accessExpiry,"refresh_expires_ms":${now + 86_400_000},"subscription_expires_ms":${now + 2 * 86_400_000}}}"""
        return ServerAiConnectionManager(object : ServerAiConnectionPersistence {
            override suspend fun read() = session
            override suspend fun write(value: String) { session = value }
        }, object : ServerAiConnectionIdentity {
            override fun existingChain() = listOf("synthetic-certificate")
            override fun create(challenge: ByteArray): List<String> = error("unexpected_key_creation")
            override fun sign(message: ByteArray): String { proofs += message.copyOf(); onSign(); return "synthetic-signature" }
            override fun fingerprint() = fingerprintProvider()
        }, ServerAiConnectionTransport { method, path, body, headers, limit ->
            assertEquals("POST", method)
            val call = Call(path, body.copyOf(), headers.toMap(), limit)
            synchronized(calls) { calls += call }
            respond(call)
        }, clock = clock, historyPersistence = pending)
    }

    private fun ok(body: String) = ServerAiConnectionResponse(200, body.toByteArray())
    private fun emptyPage(sequence: Long = 41) = ok("""{"snapshotSequence":$sequence,"nextAfterSequence":$sequence,"hasMore":false,"provenance":"DEVICE_DECLARED","records":[]}""")
    private fun receipt(call: Call, sequence: Long? = null): ServerAiConnectionResponse {
        val body = Json.parseToJsonElement(call.body.toString(Charsets.UTF_8)).jsonObject
        val count = body.getValue("records").jsonArray.size
        return ok("""{"batchId":${body.getValue("batchId")},"sequence":${sequence ?: (41L + count)},"changedRecords":$count}""")
    }

    private suspend fun failure(reason: ServerAiHistoryError, block: suspend () -> Unit) {
        try { block(); fail("expected_history_failure") }
        catch (e: ServerAiHistoryFailure) { assertEquals(reason, e.reason); assertNull(e.cause) }
    }

    @Test fun retryAfterRestartKeepsExactBodyAndBatchWithFreshProof() = runTest {
        val pending = Pending()
        val calls = mutableListOf<Call>()
        var unavailable = true
        val connection = manager(pending, calls) { call ->
            if (call.path.endsWith("/query")) emptyPage()
            else if (unavailable) throw IOException("sensitive synthetic failure") else receipt(call)
        }
        failure(ServerAiHistoryError.NETWORK) {
            ServerAiHistoryClient(connection).syncChatContext("synthetic context", listOf(AiChatTurn("user", "synthetic question")))
        }
        val first = calls.last()
        assertNotNull(pending.value)
        unavailable = false
        // A fresh manager models process restart using the same encrypted-state abstraction.
        ServerAiHistoryClient(manager(pending, calls) { call ->
            if (call.path.endsWith("/query")) emptyPage() else receipt(call)
        }).syncChatContext("synthetic context", listOf(AiChatTurn("user", "synthetic question")))
        val last = calls.last()
        assertArrayEquals(first.body, last.body)
        assertNotEquals(first.headers["X-Copilot-Nonce"], last.headers["X-Copilot-Nonce"])
        assertEquals(65_536, last.limit)
        assertFalse(last.headers.containsKey("X-Copilot-Request-Id"))
    }

    @Test fun snapshotStatesMissingHistoricalTimeAndNoFullHistoryClaim() = runTest {
        val calls = mutableListOf<Call>()
        val client = ServerAiHistoryClient(manager(Pending(), calls) { if (it.path.endsWith("/query")) emptyPage() else receipt(it) })
        client.syncChatContext("synthetic summary", listOf(AiChatTurn("assistant", "synthetic answer")))
        val records = Json.parseToJsonElement(calls.last().body.toString(Charsets.UTF_8)).jsonObject.getValue("records").jsonArray
        assertEquals(2, records.size)
        records.forEach {
            val row = it.jsonObject
            assertEquals(now, row.getValue("eventAtMs").jsonPrimitive.long)
            assertEquals(now, row.getValue("observedAtMs").jsonPrimitive.long)
            assertEquals("UNKNOWN", row.getValue("quality").jsonPrimitive.content)
            assertEquals("DEVICE_ARCHIVE", row.getValue("data").jsonObject.getValue("origin").jsonPrimitive.content)
            assertFalse(row.containsKey("context"))
        }
        val texts = records.map { it.jsonObject.getValue("data").jsonObject.getValue("text").jsonPrimitive.content }
        assertTrue(texts.last().contains("CURRENT_DEVICE_CONTEXT_SNAPSHOT"))
        assertTrue(texts.last().contains("not a full structured medical-history export"))
        assertTrue(texts.first().contains("original message time is unknown"))
    }

    @Test fun invalidReceiptRemainsPendingAndCannotAuthorizeSync() = runTest {
        val pending = Pending()
        val calls = mutableListOf<Call>()
        val client = ServerAiHistoryClient(manager(pending, calls) { if (it.path.endsWith("/query")) emptyPage() else ok("""{"batchId":"$id","sequence":0,"changedRecords":1}""") })
        failure(ServerAiHistoryError.INVALID_RESPONSE) { client.syncChatContext("synthetic", emptyList()) }
        assertNotNull(pending.value)
    }

    @Test fun fullLengthServerAnswersArePreservedAsBoundedObservationalFragments() = runTest {
        for (answer in listOf("x".repeat(8192), "\uD83D\uDE42".repeat(4096), "\u0001".repeat(8192))) {
            val calls = mutableListOf<Call>()
            val client = ServerAiHistoryClient(manager(Pending(), calls) {
                if (it.path.endsWith("/query")) emptyPage() else receipt(it)
            })

            client.syncChatContext("synthetic summary", listOf(AiChatTurn("assistant", answer)))

            val rows = calls.filter { it.path.endsWith("/batches") }.flatMap {
                Json.parseToJsonElement(it.body.toString(Charsets.UTF_8)).jsonObject.getValue("records").jsonArray
            }
            val texts = rows.map { it.jsonObject.getValue("data").jsonObject.getValue("text").jsonPrimitive.content }
            val fragments = texts.filter { it.startsWith("LOCAL_CHAT_CONTEXT_SNAPSHOT") }
            assertTrue(fragments.size > 1)
            assertEquals(answer, fragments.joinToString("") { it.substringAfter('\n') })
            assertTrue(texts.last().startsWith("CURRENT_DEVICE_CONTEXT_SNAPSHOT"))
            assertTrue(rows.all { HistoryJson.bytes(it).size <= 16_384 })
        }
    }

    @Test fun unsupportedHistoryTextFailsBeforeAnyUploadWithoutTruncation() = runTest {
        val calls = mutableListOf<Call>()
        val client = ServerAiHistoryClient(manager(Pending(), calls) { emptyPage() })
        for (text in listOf("x".repeat(8193), "\u754c".repeat(6000))) {
            failure(ServerAiHistoryError.INVALID_REQUEST) {
                client.syncChatContext("synthetic summary", listOf(AiChatTurn("assistant", text)))
            }
        }
        assertTrue(calls.isEmpty())
    }

    @Test fun conflictDoesNotReidentifyOrReplayAndRefreshesSequence() = runTest {
        val pending = Pending()
        val calls = mutableListOf<Call>()
        val client = ServerAiHistoryClient(manager(pending, calls) { if (it.path.endsWith("/query")) emptyPage(48) else ServerAiConnectionResponse(409, byteArrayOf()) })
        failure(ServerAiHistoryError.CONFLICT) { client.syncChatContext("synthetic", emptyList()) }
        val first = calls.first { it.path.endsWith("/batches") }.body
        failure(ServerAiHistoryError.CONFLICT) { client.syncChatContext("synthetic", emptyList()) }
        assertArrayEquals(first, calls.last { it.path.endsWith("/batches") }.body)
        assertEquals(2, calls.count { it.path.endsWith("/batches") })
        assertTrue(calls.count { it.path.endsWith("/query") } >= 2)
    }

    @Test fun closedParsingRejectsDuplicateUnknownFieldsAndInvalidUtf8() = runTest {
        val invalid = listOf(
            """{"snapshotSequence":0,"snapshotSequence":0,"nextAfterSequence":0,"hasMore":false,"provenance":"DEVICE_DECLARED","records":[]}""".toByteArray(),
            """{"snapshotSequence":0,"nextAfterSequence":0,"hasMore":false,"provenance":"DEVICE_DECLARED","records":[],"unexpected":1}""".toByteArray(),
            byteArrayOf(0xc3.toByte(), 0x28)
        )
        for (body in invalid) {
            val client = ServerAiHistoryClient(manager(Pending(), mutableListOf()) { ServerAiConnectionResponse(200, body) })
            failure(ServerAiHistoryError.INVALID_RESPONSE) { client.status() }
        }
    }

    @Test fun oversizeOrMalformedInputFailsBeforeUpload() = runTest {
        val calls = mutableListOf<Call>()
        val client = ServerAiHistoryClient(manager(Pending(), calls) { emptyPage() })
        failure(ServerAiHistoryError.INVALID_REQUEST) { client.syncChatContext("x".repeat(8001), emptyList()) }
        failure(ServerAiHistoryError.INVALID_REQUEST) { client.syncChatContext("\uD800", emptyList()) }
        failure(ServerAiHistoryError.INVALID_REQUEST) { client.syncChatContext("synthetic", listOf(AiChatTurn("system", "synthetic"))) }
        assertTrue(calls.isEmpty())
    }

    @Test fun conversationUsesSeparateExactBudgetsAndChecksOutcome() = runTest {
        val calls = mutableListOf<Call>()
        val client = ServerAiHistoryClient(manager(Pending(), calls) {
            ok("""{"revision":"server-chat-archive-v1","origin":"SERVER_ARCHIVE","generatedAtMs":$now,"snapshotScope":"MEMBERSHIP_ONLY","records":[],"hasMore":false,"nextCursor":null}""")
        })
        val page = client.readConversations(ServerAiConversationQuery(now - 10_000, now + 1))
        assertFalse(page.hasMore)
        assertEquals(131_072, calls.single().limit)
        assertEquals(ServerAiHistoryRoute.CONVERSATIONS.path, calls.single().path)
        assertTrue(calls.single().body.size <= 4096)
    }

    @Test fun typedRecordsRejectDuplicateIdsAndRedactValues() = runTest {
        val record = ServerAiHistoryRecord(id, 1, now, now, ServerAiHistoryKind.CHAT,
            ServerAiHistorySource.COPILOT, ServerAiHistoryQuality.UNKNOWN,
            data = ServerAiHistoryData.Chat(id, ServerAiHistoryRole.USER, "private synthetic text"))
        val calls = mutableListOf<Call>()
        val client = ServerAiHistoryClient(manager(Pending(), calls) { emptyPage() })
        failure(ServerAiHistoryError.INVALID_REQUEST) { client.uploadRecords(listOf(record, record)) }
        assertFalse(record.toString().contains("private synthetic text"))
        assertFalse(record.data.toString().contains("private synthetic text"))
        assertFalse(ServerAiHistoryFailure(ServerAiHistoryError.NETWORK).toString().contains("private"))
        assertTrue(calls.isEmpty())
    }

    @Test fun boundedBatchesCarryOnlyServerAllocatedSequences() = runTest {
        val calls = mutableListOf<Call>()
        var sequence = 41L
        val client = ServerAiHistoryClient(manager(Pending(), calls) { call ->
            if (call.path.endsWith("/query")) emptyPage(sequence) else {
                sequence += Json.parseToJsonElement(call.body.toString(Charsets.UTF_8)).jsonObject.getValue("records").jsonArray.size
                receipt(call, sequence)
            }
        })
        val records = (1..33).map { n -> ServerAiHistoryRecord(UUID(0, n.toLong()).toString(), 1, now, now,
            ServerAiHistoryKind.GLUCOSE, ServerAiHistorySource.CGM, ServerAiHistoryQuality.UNKNOWN,
            data = ServerAiHistoryData.Glucose(5.0)) }
        val receipts = client.uploadRecords(records)
        assertEquals(listOf(73L, 74L), receipts.map { it.sequence })
        val batches = calls.filter { it.path.endsWith("/batches") }
        assertEquals(listOf(32, 1), batches.map { Json.parseToJsonElement(it.body.toString(Charsets.UTF_8)).jsonObject.getValue("records").jsonArray.size })
        batches.forEach { assertTrue(it.body.size <= 131_072); assertFalse(Json.parseToJsonElement(it.body.toString(Charsets.UTF_8)).jsonObject.containsKey("sequence")) }
    }

    @Test fun utf8ByteBudgetSplitsBeforeRecordCountBudget() = runTest {
        val calls = mutableListOf<Call>()
        var sequence = 41L
        val client = ServerAiHistoryClient(manager(Pending(), calls) { call ->
            if (call.path.endsWith("/query")) emptyPage(sequence) else {
                sequence += Json.parseToJsonElement(call.body.toString(Charsets.UTF_8)).jsonObject.getValue("records").jsonArray.size
                receipt(call, sequence)
            }
        })
        val records = (1..10).map { n -> ServerAiHistoryRecord(UUID(0, n.toLong()).toString(), 1, now, now,
            ServerAiHistoryKind.CHAT, ServerAiHistorySource.COPILOT, ServerAiHistoryQuality.UNKNOWN,
            data = ServerAiHistoryData.Chat(id, ServerAiHistoryRole.USER, "я".repeat(7900))) }
        client.uploadRecords(records)
        val batches = calls.filter { it.path.endsWith("/batches") }
        assertEquals(2, batches.size)
        assertTrue(batches.all { it.body.size <= 131_072 })
        assertEquals(10, batches.sumOf { Json.parseToJsonElement(it.body.toString(Charsets.UTF_8)).jsonObject.getValue("records").jsonArray.size })
    }

    @Test fun newInputCannotReplaceAnAmbiguousPendingBody() = runTest {
        val calls = mutableListOf<Call>()
        val client = ServerAiHistoryClient(manager(Pending(), calls) { if (it.path.endsWith("/query")) emptyPage() else throw IOException("synthetic") })
        failure(ServerAiHistoryError.NETWORK) { client.syncChatContext("first synthetic", emptyList()) }
        val original = calls.last().body
        failure(ServerAiHistoryError.NETWORK) { client.syncChatContext("second synthetic", emptyList()) }
        assertArrayEquals(original, calls.last().body)
    }

    @Test fun confirmedUploadsUseFreshIdentitiesForDifferentBodiesAcrossRestart() = runTest {
        val pending = Pending()
        val calls = mutableListOf<Call>()
        var sequence = 41L
        fun response(call: Call): ServerAiConnectionResponse = if (call.path.endsWith("/query")) emptyPage(sequence)
            else receipt(call, ++sequence)
        ServerAiHistoryClient(manager(pending, calls, respond = ::response)).syncChatContext("first synthetic", emptyList())
        ServerAiHistoryClient(manager(pending, calls, respond = ::response)).syncChatContext("second synthetic", emptyList())
        val batches = calls.filter { it.path.endsWith("/batches") }.map { Json.parseToJsonElement(it.body.toString(Charsets.UTF_8)).jsonObject }
        assertNotEquals(batches[0].getValue("batchId"), batches[1].getValue("batchId"))
        assertNotEquals(batches[0].getValue("records").jsonArray[0].jsonObject.getValue("recordId"), batches[1].getValue("records").jsonArray[0].jsonObject.getValue("recordId"))
    }

    @Test fun persistenceMustSucceedBeforeFirstUpload() = runTest {
        val calls = mutableListOf<Call>()
        val pending = Pending().apply { failWrites = true }
        val client = ServerAiHistoryClient(manager(pending, calls) { emptyPage() })
        failure(ServerAiHistoryError.STORAGE) { client.syncChatContext("synthetic", emptyList()) }
        assertTrue(calls.none { it.path.endsWith("/batches") })
    }

    @Test fun bindingMismatchAndServerSequenceRegressionFailClosed() = runTest {
        val calls = mutableListOf<Call>()
        val pending = Pending()
        var sequence = 41L
        val client = ServerAiHistoryClient(manager(pending, calls) { if (it.path.endsWith("/query")) emptyPage(sequence) else receipt(it, ++sequence) })
        client.syncChatContext("first synthetic", emptyList())
        sequence = 0
        failure(ServerAiHistoryError.INVALID_RESPONSE) { client.syncChatContext("second synthetic", emptyList()) }
        pending.value = pending.value!!.replace("c".repeat(64), "d".repeat(64))
        failure(ServerAiHistoryError.DEVICE_MISMATCH) { client.retryPending() }
    }

    @Test fun historyProofUsesAccessCredentialOnlyAndJobsKeepOriginalBounds() = runTest {
        val calls = mutableListOf<Call>()
        val proofs = mutableListOf<ByteArray>()
        val connection = manager(Pending(), calls, proofs) { emptyPage() }
        connection.exchangeHistory(ServerAiHistoryRoute.QUERY, ByteArray(131_072) { 32 })
        connection.exchangeHistory(ServerAiHistoryRoute.CONVERSATIONS, ByteArray(4096) { 32 })
        assertEquals(listOf(65_536, 131_072), calls.map { it.limit })
        fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        proofs.forEachIndexed { index, proof ->
            val p = Json.parseToJsonElement(proof.toString(Charsets.US_ASCII)).jsonObject
            assertEquals(hash("access.${"a".repeat(43)}".toByteArray()), p.getValue("credential_sha256").jsonPrimitive.content)
            assertEquals(hash(calls[index].body), p.getValue("body_sha256").jsonPrimitive.content)
            assertEquals(calls[index].path, p.getValue("path").jsonPrimitive.content)
        }
        try {
            connection.exchangeHistory(ServerAiHistoryRoute.CONVERSATIONS, ByteArray(4097))
            fail("expected_conversation_body_limit")
        } catch (_: IllegalArgumentException) { }
        try {
            connection.exchangeJob("POST", "/api/ai/v1/jobs", ByteArray(16_385), id, now + 1000)
            fail("expected_original_job_body_limit")
        } catch (_: IllegalArgumentException) { }
        connection.exchangeJob("POST", "/api/ai/v1/jobs", ByteArray(16_384), id, now + 1000)
        assertEquals(65_536, calls.last().limit)
    }

    @Test fun archive409DoesNotRestartTheScanOrSubmitAModel() = runTest {
        val calls = mutableListOf<Call>()
        val client = ServerAiHistoryClient(manager(Pending(), calls) { ServerAiConnectionResponse(409, byteArrayOf()) })
        failure(ServerAiHistoryError.CURSOR_EXPIRED) {
            client.readConversations(ServerAiConversationQuery(now - 100, now + 1, "a".repeat(40)))
        }
        assertEquals(1, calls.size)
        assertTrue(calls.single().path.endsWith("/conversations"))
    }

    @Test fun historyRefreshUsesExistingRotationAndDoesNotRetryUnauthorizedRequests() = runTest {
        val calls = mutableListOf<Call>()
        val proofs = mutableListOf<ByteArray>()
        val connection = manager(Pending(), calls, proofs, accessExpiry = now - 1) { call ->
            if (call.path.endsWith("/refresh")) ok("""{"access_token":"access.${"e".repeat(43)}","refresh_token":"refresh.${"f".repeat(43)}","access_expires_ms":${now + 600_000},"refresh_expires_ms":${now + 86_400_000},"subscription_expires_ms":${now + 2 * 86_400_000}}""")
            else ServerAiConnectionResponse(401, "sensitive synthetic error".toByteArray())
        }
        failure(ServerAiHistoryError.UNAUTHORIZED) { ServerAiHistoryClient(connection).status() }
        assertEquals(listOf("/api/ai/v1/session/refresh", "/api/ai/v1/history/query"), calls.map { it.path })
        assertEquals(16_384, calls.first().limit)
        assertEquals("Bearer access.${"e".repeat(43)}", calls.last().headers["Authorization"])
        assertFalse(calls.first().headers.containsKey("Authorization"))
        assertTrue(calls.all { it.headers.containsKey("X-Copilot-Key") })
    }

    @Test fun receiptCheckpointFailureRetainsOriginalBodyForRecovery() = runTest {
        val pending = Pending()
        val calls = mutableListOf<Call>()
        var rejectCheckpoint = true
        val connection = manager(pending, calls) { call ->
            if (call.path.endsWith("/query")) emptyPage() else {
                if (rejectCheckpoint) pending.failWrites = true
                receipt(call)
            }
        }
        failure(ServerAiHistoryError.STORAGE) { ServerAiHistoryClient(connection).syncChatContext("synthetic", emptyList()) }
        val firstBody = calls.last().body
        pending.failWrites = false; rejectCheckpoint = false
        ServerAiHistoryClient(connection).retryPending()
        assertArrayEquals(firstBody, calls.last().body)
    }

    @Test fun restartResumesAtFirstUnacknowledgedBatch() = runTest {
        val pending = Pending()
        val calls = mutableListOf<Call>()
        var sequence = 41L
        var uploads = 0
        val first = manager(pending, calls) { call ->
            if (call.path.endsWith("/query")) emptyPage(sequence) else {
                uploads++
                if (uploads == 2) throw IOException("synthetic")
                sequence += 32
                receipt(call, sequence)
            }
        }
        val records = (1..33).map { n -> ServerAiHistoryRecord(UUID(0, n.toLong()).toString(), 1, now, now,
            ServerAiHistoryKind.GLUCOSE, ServerAiHistorySource.CGM, ServerAiHistoryQuality.UNKNOWN,
            data = ServerAiHistoryData.Glucose(5.0)) }
        failure(ServerAiHistoryError.NETWORK) { ServerAiHistoryClient(first).uploadRecords(records) }
        val ambiguous = calls.last().body
        val receipts = ServerAiHistoryClient(manager(pending, calls) { receipt(it, 74) }).retryPending()
        assertEquals(3, calls.count { it.path.endsWith("/batches") })
        assertArrayEquals(ambiguous, calls.last().body)
        assertEquals(listOf(73L, 74L), receipts.map { it.sequence })
    }

    @Test fun oversizedDurablePlanFailsBeforeAnyNetworkCall() = runTest {
        val calls = mutableListOf<Call>()
        val client = ServerAiHistoryClient(manager(Pending(), calls) { emptyPage() })
        val records = (1..80).map { n -> ServerAiHistoryRecord(UUID(0, n.toLong()).toString(), 1, now, now,
            ServerAiHistoryKind.CHAT, ServerAiHistorySource.COPILOT, ServerAiHistoryQuality.UNKNOWN,
            data = ServerAiHistoryData.Chat(id, ServerAiHistoryRole.USER, "я".repeat(7900))) }
        failure(ServerAiHistoryError.INVALID_REQUEST) { client.uploadRecords(records) }
        assertTrue(calls.isEmpty())
    }

    @Test fun laterStatusHighwaterDoesNotInvalidateEarlierIdempotentReceipt() = runTest {
        val pending = Pending()
        val calls = mutableListOf<Call>()
        var unavailable = true
        var observed = 41L
        val connection = manager(pending, calls) { call ->
            if (call.path.endsWith("/query")) emptyPage(observed)
            else if (unavailable) throw IOException("synthetic") else receipt(call, 42)
        }
        val client = ServerAiHistoryClient(connection)
        failure(ServerAiHistoryError.NETWORK) { client.syncChatContext("synthetic", emptyList()) }
        val original = calls.last().body
        observed = 100
        assertEquals(100L, client.status().sequence)
        unavailable = false
        assertEquals(42L, client.retryPending().single().sequence)
        assertArrayEquals(original, calls.last().body)
        // Recovering an older receipt must not lower the separately remembered highwater.
        observed = 99
        failure(ServerAiHistoryError.INVALID_RESPONSE) { client.status() }
    }
    @Test fun expiredMixedBatchIsRetiredWithExactRemainingRecordAndAuditDigest() = runTest {
        val pending = Pending()
        val calls = mutableListOf<Call>()
        var time = now
        var offline = true
        val client = ServerAiHistoryClient(manager(pending, calls, clock = { time }) { call ->
            if (call.path.endsWith("/query")) emptyPage()
            else if (offline) throw IOException("offline") else receipt(call)
        })
        fun row(uuid: String, event: Long) = ServerAiHistoryRecord(uuid, 1, event, now,
            ServerAiHistoryKind.GLUCOSE, ServerAiHistorySource.CGM, ServerAiHistoryQuality.UNKNOWN,
            data = ServerAiHistoryData.Glucose(6.0))
        failure(ServerAiHistoryError.NETWORK) {
            client.uploadRecords(listOf(row(id, now - HistoryJson.retentionMs + 60_000),
                row("a58d6578-94b1-4c27-957f-32286b93261e", now - 1000)))
        }
        val originalBytes = calls.last().body.copyOf()
        val old = Json.parseToJsonElement(originalBytes.toString(Charsets.UTF_8)).jsonObject
        val valid = old.getValue("records").jsonArray.last()
        time += 120_000
        offline = false
        client.retryPending()
        val replacement = Json.parseToJsonElement(calls.last().body.toString(Charsets.UTF_8)).jsonObject
        assertNotEquals(old.getValue("batchId"), replacement.getValue("batchId"))
        assertEquals(listOf(valid), replacement.getValue("records").jsonArray.toList())
        val state = Json.parseToJsonElement(pending.value!!).jsonObject
        assertEquals(JsonNull, state.getValue("pending"))
        val audit = state.getValue("retired").jsonArray.single().jsonObject
        assertEquals(old.getValue("batchId"), audit.getValue("batchId"))
        assertEquals(time, audit.getValue("retiredAtMs").jsonPrimitive.long)
        assertEquals(MessageDigest.getInstance("SHA-256").digest(originalBytes).joinToString("") { "%02x".format(it) },
            audit.getValue("bodySha256").jsonPrimitive.content)
    }

    @Test fun expiredAttemptCannotBeReplacedBeforeDurableRetirement() = runTest {
        val pending = Pending()
        val calls = mutableListOf<Call>()
        var time = now
        val client = ServerAiHistoryClient(manager(pending, calls, clock = { time }) { call ->
            if (call.path.endsWith("/query")) emptyPage() else throw IOException("offline")
        })
        failure(ServerAiHistoryError.NETWORK) { client.uploadRecords(listOf(ServerAiHistoryRecord(id, 1,
            now - HistoryJson.retentionMs + 60_000, now, ServerAiHistoryKind.GLUCOSE,
            ServerAiHistorySource.CGM, ServerAiHistoryQuality.UNKNOWN, data = ServerAiHistoryData.Glucose(6.0)))) }
        val prior = pending.value
        val requests = calls.size
        time += 120_000
        pending.failWrites = true
        failure(ServerAiHistoryError.STORAGE) { client.retryPending() }
        assertEquals(prior, pending.value)
        assertEquals(requests, calls.size)
        pending.failWrites = false
        assertTrue(client.retryPending().isEmpty())
        assertEquals(requests, calls.size)
    }

    @Test fun deviceChangeDuringCheckpointReadCannotSendUnderNewIdentity() = runTest {
        val pending = Pending()
        val calls = mutableListOf<Call>()
        var fingerprint = "c".repeat(64)
        val client = ServerAiHistoryClient(manager(pending, calls, fingerprintProvider = { fingerprint }) { emptyPage() })
        pending.onRead = { fingerprint = "d".repeat(64) }
        failure(ServerAiHistoryError.DEVICE_MISMATCH) {
            client.uploadRecords(listOf(ServerAiHistoryRecord(id, 1, now - 1000, now,
                ServerAiHistoryKind.GLUCOSE, ServerAiHistorySource.CGM, ServerAiHistoryQuality.UNKNOWN,
                data = ServerAiHistoryData.Glucose(6.0))), expectedDeviceBinding = "c".repeat(64))
        }
        assertTrue(calls.isEmpty())
    }

    @Test fun deviceChangeDuringSigningCannotReachTransport() = runTest {
        val pending = Pending()
        val calls = mutableListOf<Call>()
        var fingerprint = "c".repeat(64)
        val client = ServerAiHistoryClient(manager(pending, calls, fingerprintProvider = { fingerprint },
            onSign = { fingerprint = "d".repeat(64) }) { emptyPage() })
        failure(ServerAiHistoryError.DEVICE_MISMATCH) {
            client.uploadRecords(listOf(ServerAiHistoryRecord(id, 1, now - 1000, now,
                ServerAiHistoryKind.GLUCOSE, ServerAiHistorySource.CGM, ServerAiHistoryQuality.UNKNOWN,
                data = ServerAiHistoryData.Glucose(6.0))))
        }
        assertTrue(calls.isEmpty())
    }
}
