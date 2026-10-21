package io.aaps.copilot.data.repository

import io.aaps.copilot.security.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class ServerDailyReviewClientTest {
    private val f = DailyReviewFixtures
    private data class Call(val path: String, val body: ByteArray, val headers: Map<String, String>, val limit: Int)
    private fun manager(calls: MutableList<Call>, refreshExpiry: Long = f.now + 86_400_000,
        subscriptionExpiry: Long = f.now + 172_800_000,
        respond: suspend (Call) -> ServerAiConnectionResponse): ServerAiConnectionManager {
        val session = """{"session":{"access_token":"access.${"a".repeat(43)}","refresh_token":"refresh.${"b".repeat(43)}","access_expires_ms":${f.now + 60_000},"refresh_expires_ms":$refreshExpiry,"subscription_expires_ms":$subscriptionExpiry}}"""
        return ServerAiConnectionManager(object : ServerAiConnectionPersistence {
            override suspend fun read() = session
            override suspend fun write(value: String) = error("unexpected credential mutation")
        }, object : ServerAiConnectionIdentity {
            override fun existingChain() = listOf("synthetic-certificate")
            override fun create(challenge: ByteArray): List<String> = error("unexpected key creation")
            override fun sign(message: ByteArray) = "synthetic-signature"
            override fun fingerprint() = f.device
        }, ServerAiConnectionTransport { method, path, body, headers, limit ->
            assertEquals("POST", method)
            val call = Call(path, body.copyOf(), headers.toMap(), limit)
            calls += call
            respond(call)
        }, clock = { f.now })
    }

    private suspend fun failure(reason: ServerAiHistoryError, block: suspend () -> Unit) {
        try { block(); fail("expected bounded read failure") }
        catch (error: ServerAiHistoryFailure) {
            assertEquals(reason, error.reason); assertNull(error.cause)
            assertFalse(error.toString().contains(f.text))
        }
    }

    @Test fun exactSignedListAndSelectedReadNeverSubmitAJobOrUpload() = runTest {
        val calls = mutableListOf<Call>()
        val client = ServerAiHistoryClient(manager(calls) { call ->
            ServerAiConnectionResponse(200, HistoryJson.bytes(if (call.path.endsWith("daily-results")) f.page() else f.detail()))
        })
        assertEquals(f.id(), client.readDailyResults(DailyReviewQuery(), f.device).records.single().jobId)
        assertEquals(f.text, client.readDailyResult(f.id(), f.device).summary)
        assertEquals(listOf("/api/ai/v1/history/daily-results", "/api/ai/v1/history/daily-result"), calls.map { it.path })
        assertEquals("{\"afterSequence\":0,\"snapshotSequence\":null}", HistoryJson.decodeUtf8(calls.first().body))
        calls.forEach {
            assertEquals(131_072, it.limit)
            assertTrue(it.headers.containsKey("X-Copilot-Signature"))
            assertTrue(it.headers.containsKey("X-Copilot-Nonce"))
            assertFalse(it.headers.containsKey("X-Copilot-Request-Id"))
        }
    }

    @Test fun expectedDeviceMismatchFailsBeforeNetwork() = runTest {
        val calls = mutableListOf<Call>()
        val client = ServerAiHistoryClient(manager(calls) { error("network must not run") })
        failure(ServerAiHistoryError.DEVICE_MISMATCH) { client.readDailyResults(DailyReviewQuery(), "f".repeat(64)) }
        assertTrue(calls.isEmpty())
    }

    @Test fun unavailableRouteDoesNotBecomeMissingCredentials() = runTest {
        val client = ServerAiHistoryClient(manager(mutableListOf()) { ServerAiConnectionResponse(404, byteArrayOf()) })
        failure(ServerAiHistoryError.NOT_FOUND) { client.readDailyResults(DailyReviewQuery(), f.device) }
    }

    @Test fun invalidBodyCannotLeakOrReplaceAReport() = runTest {
        val bodies = listOf(byteArrayOf(0xc3.toByte(), 0x28), ByteArray(131_073),
            """{"revision":"daily-review-read-v1","revision":"daily-review-read-v1"}""".toByteArray(),
            HistoryJson.bytes(f.detail(2)))
        bodies.forEach { body ->
            val client = ServerAiHistoryClient(manager(mutableListOf()) { ServerAiConnectionResponse(200, body) })
            failure(ServerAiHistoryError.INVALID_RESPONSE) { client.readDailyResult(f.id(), f.device) }
        }
    }

    @Test fun malformedRequestFailsBeforeNetworkAndCancellationPropagates() = runTest {
        val calls = mutableListOf<Call>()
        val client = ServerAiHistoryClient(manager(calls) { throw CancellationException("synthetic cancellation") })
        failure(ServerAiHistoryError.INVALID_REQUEST) { client.readDailyResult("invalid", f.device) }
        assertTrue(calls.isEmpty())
        try { client.readDailyResult(f.id(), f.device); fail("expected cancellation") }
        catch (_: CancellationException) { }
    }

    @Test fun cachedAuditRequiresLocallyCurrentSubscriptionAndRefreshSession() = runTest {
        for (expiredRefresh in listOf(true, false)) {
            val calls = mutableListOf<Call>()
            val connection = manager(calls, refreshExpiry = if (expiredRefresh) f.now else f.now + 86_400_000,
                subscriptionExpiry = if (expiredRefresh) f.now + 86_400_000 else f.now) { error("no network") }
            assertEquals(f.device, connection.historyDeviceBinding())
            try { connection.currentHistoryDeviceBinding(); fail("expired session exposed cached archive") }
            catch (failure: ServerAiConnectionFailure) { assertEquals(ServerAiConnectionError.UNAUTHORIZED, failure.reason) }
            assertTrue(calls.isEmpty())
        }
    }
}
