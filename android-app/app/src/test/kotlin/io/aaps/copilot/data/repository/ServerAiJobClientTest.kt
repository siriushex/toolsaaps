package io.aaps.copilot.data.repository

import io.aaps.copilot.security.ServerAiConnectionIdentity
import io.aaps.copilot.security.ServerAiConnectionManager
import io.aaps.copilot.security.ServerAiConnectionPersistence
import io.aaps.copilot.security.ServerAiConnectionResponse
import io.aaps.copilot.security.ServerAiConnectionTransport
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Test

class ServerAiJobClientTest {
    private val initialNow = 1_800_000_000_000L
    private val accessToken = "access.${"a".repeat(43)}"
    private val requestId = "2ec58479-e05b-4805-87f6-5bb06b04bb2d"
    private val jobId = "f1e19b82-e0f2-4c50-b2bf-03c80c906f2c"

    private class MemoryStore(var value: String) : ServerAiConnectionPersistence {
        override suspend fun read() = value
        override suspend fun write(value: String) {
            this.value = value
        }
    }

    private class CapturingIdentity : ServerAiConnectionIdentity {
        val messages = mutableListOf<ByteArray>()
        override fun existingChain() = listOf("certificate", "root")
        override fun create(challenge: ByteArray) = error("must not create")
        override fun sign(message: ByteArray): String {
            messages += message.copyOf()
            return "proof-${messages.size}"
        }
        override fun fingerprint() = "c".repeat(64)
    }

    private data class Call(
        val method: String,
        val path: String,
        val body: ByteArray,
        val headers: Map<String, String>,
        val responseLimit: Int
    )

    @Test
    fun submitRetryReusesExactBodyRequestIdAndDeadlineWithFreshProof() = runTest {
        var now = initialNow
        val deadline = initialNow + 120_000L
        val identity = CapturingIdentity()
        val calls = mutableListOf<Call>()
        val receipt = receipt(requestId, deadline)
        val manager = ServerAiConnectionManager(
            MemoryStore(session()),
            identity,
            ServerAiConnectionTransport { method, path, body, headers, responseLimit ->
                calls += Call(method, path, body.copyOf(), headers.toMap(), responseLimit)
                now += 1
                ServerAiConnectionResponse(202, receipt.toByteArray(Charsets.UTF_8))
            },
            clock = { now }
        )
        val client = ServerAiJobClient(manager, uuidFactory = { UUID.fromString(requestId) })
        val submission = client.prepareChat("synthetic hello", deadline)

        val first = client.submit(submission)
        val recovered = client.submit(submission)

        assertEquals(jobId, first.jobId)
        assertEquals(jobId, recovered.jobId)
        assertEquals(2, calls.size)
        calls.forEach { call ->
            assertEquals("POST", call.method)
            assertEquals("/api/ai/v1/jobs", call.path)
            assertArrayEquals("{\"text\":\"synthetic hello\"}".toByteArray(), call.body)
            assertEquals("Bearer $accessToken", call.headers["Authorization"])
            assertEquals(requestId, call.headers["X-Copilot-Request-Id"])
            assertEquals(deadline.toString(), call.headers["X-Copilot-Deadline-Ms"])
            assertEquals(65_536, call.responseLimit)
        }
        assertArrayEquals(calls[0].body, calls[1].body)
        assertEquals(2, identity.messages.size)
        val firstProof = Json.parseToJsonElement(identity.messages[0].toString(Charsets.US_ASCII)).jsonObject
        val secondProof = Json.parseToJsonElement(identity.messages[1].toString(Charsets.US_ASCII)).jsonObject
        assertEquals(sha256(calls[0].body), firstProof.getValue("body_sha256").jsonPrimitive.content)
        assertEquals(
            sha256("$accessToken\n$requestId\n$deadline".toByteArray(Charsets.US_ASCII)),
            firstProof.getValue("credential_sha256").jsonPrimitive.content
        )
        assertNotEquals(
            firstProof.getValue("nonce").jsonPrimitive.content,
            secondProof.getValue("nonce").jsonPrimitive.content
        )
        assertNotEquals(
            firstProof.getValue("issued_ms").jsonPrimitive.content,
            secondProof.getValue("issued_ms").jsonPrimitive.content
        )
    }

    @Test
    fun capabilitiesUsesExactBackendShapeAndExposesReadiness() = runTest {
        val manager = manager { method, path, body, _, limit ->
            assertEquals("GET", method)
            assertEquals("/api/ai/v1/capabilities", path)
            assertTrue(body.isEmpty())
            assertEquals(65_536, limit)
            response(200, capabilities(inferenceEnabled = true))
        }

        val value = ServerAiJobClient(manager).capabilities()

        assertEquals("server-codex-chat-r1a.1", value.revision)
        assertTrue(value.inferenceEnabled)
        assertEquals(listOf("CHAT"), value.taskKinds)
        assertEquals(listOf("TEXT"), value.inputModalities)
        assertEquals(listOf("TEXT"), value.outputModalities)
        assertEquals(4_096, value.maxTextChars)
        assertEquals(8_192, value.maxTextBytes)
        assertEquals(8_192, value.maxResultChars)
        assertEquals(16_384, value.maxResultBytes)
        assertEquals(65_536, value.maxResponseBytes)
        assertEquals(900_000L, value.maxDeadlineMs)
    }

    @Test
    fun supplementaryUnicodeUsesCodePointsAndExactUtf8ByteLimit() = runTest {
        val accepted = "\uD83D\uDE00".repeat(2_047) + "abcd"
        assertTrue(accepted.length > 4_096)
        assertEquals(2_051, accepted.codePointCount(0, accepted.length))
        assertEquals(8_192, accepted.toByteArray(Charsets.UTF_8).size)
        var sentBody: ByteArray? = null
        val manager = manager { _, _, body, _, _ ->
            sentBody = body.copyOf()
            response(202, receipt(requestId, initialNow + 120_000L))
        }
        val client = ServerAiJobClient(manager, uuidFactory = { UUID.fromString(requestId) })

        client.submit(client.prepareChat(accepted, initialNow + 120_000L))

        val encoded = sentBody ?: error("body missing")
        assertTrue(encoded.toString(Charsets.UTF_8).contains(accepted))
        assertEquals(8_203, encoded.size)
    }

    @Test
    fun inputLimitsRejectExcessCodePointsBytesMalformedUtf16AndEscapedBody() {
        val client = ServerAiJobClient(manager { _, _, _, _, _ -> error("must not send") })

        assertInvalidRequest { client.prepareChat("a".repeat(4_097), initialNow + 120_000L) }
        assertInvalidRequest { client.prepareChat("\uD83D\uDE00".repeat(2_048) + "a", initialNow + 120_000L) }
        assertInvalidRequest { client.prepareChat("\uD800", initialNow + 120_000L) }
        assertInvalidRequest { client.prepareChat("\u0000".repeat(4_096), initialNow + 120_000L) }
        assertInvalidRequest { client.prepareChat("", initialNow + 120_000L) }
        assertInvalidRequest { client.prepareChat("ok", initialNow) }
        assertInvalidRequest { client.prepareChat("ok", initialNow + 900_001L) }
    }

    @Test
    fun refreshRunsBeforeSubmitAndKeepsSubscriptionAndPreparedBytes() = runTest {
        var now = initialNow
        val deadline = initialNow + 120_000L
        val newAccess = "access.${"d".repeat(43)}"
        val paths = mutableListOf<String>()
        val bodies = mutableListOf<ByteArray>()
        val headers = mutableListOf<Map<String, String>>()
        val identity = CapturingIdentity()
        val manager = ServerAiConnectionManager(
            MemoryStore(session(accessExpiresMs = initialNow + 1_000L)),
            identity,
            ServerAiConnectionTransport { _, path, body, requestHeaders, _ ->
                paths += path
                bodies += body.copyOf()
                headers += requestHeaders.toMap()
                when (path) {
                    "/api/ai/v1/session/refresh" -> response(
                        200,
                        sessionTokens(access = newAccess, accessExpiresMs = initialNow + 600_000L)
                    )
                    "/api/ai/v1/jobs" -> response(202, receipt(requestId, deadline))
                    else -> error("unexpected path")
                }
            },
            clock = { now }
        )
        val client = ServerAiJobClient(manager, uuidFactory = { UUID.fromString(requestId) })
        val submission = client.prepareChat("synthetic refresh", deadline)

        client.submit(submission)

        assertEquals(listOf("/api/ai/v1/session/refresh", "/api/ai/v1/jobs"), paths)
        assertArrayEquals("{\"text\":\"synthetic refresh\"}".toByteArray(), bodies[1])
        assertEquals("Bearer $newAccess", headers[1]["Authorization"])
        assertEquals(requestId, headers[1]["X-Copilot-Request-Id"])
        assertEquals(deadline.toString(), headers[1]["X-Copilot-Deadline-Ms"])
        assertEquals(2, identity.messages.size)
    }

    @Test
    fun recoveredExpiredAccessNeverSignsOrSendsTheJob() = runTest {
        val pendingId = "0f2de8e0-6a65-4a72-8237-26cfdb92e3b2"
        val expiredTokens = sessionTokens(accessExpiresMs = initialNow - 1L)
        val stored = """{"session":$expiredTokens,"pending":{"id":"$pendingId","refresh":"refresh.${"b".repeat(43)}"}}"""
        val paths = mutableListOf<String>()
        val manager = ServerAiConnectionManager(
            MemoryStore(stored),
            CapturingIdentity(),
            ServerAiConnectionTransport { _, path, _, _, _ ->
                paths += path
                if (path == "/api/ai/v1/jobs") error("expired access must not submit")
                response(200, expiredTokens)
            },
            clock = { initialNow }
        )
        val client = ServerAiJobClient(manager, uuidFactory = { UUID.fromString(requestId) })
        val submission = client.prepareChat("synthetic recovery", initialNow + 120_000L)

        val failure = jobFailure { client.submit(submission) }

        assertEquals(ServerAiJobError.UNAUTHORIZED, failure.reason)
        assertEquals(listOf("/api/ai/v1/session/refresh"), paths)
    }

    @Test
    fun ambiguousSubmitIsNotRetriedAndCallerCanRecoverWithSameSubmission() = runTest {
        val deadline = initialNow + 120_000L
        var now = initialNow
        var attempts = 0
        val calls = mutableListOf<Call>()
        val identity = CapturingIdentity()
        val manager = ServerAiConnectionManager(
            MemoryStore(session()),
            identity,
            ServerAiConnectionTransport { method, path, body, headers, limit ->
                calls += Call(method, path, body.copyOf(), headers.toMap(), limit)
                attempts += 1
                if (attempts == 1) throw IOException("raw-network-secret")
                response(202, receipt(requestId, deadline))
            },
            clock = { now }
        )
        val client = ServerAiJobClient(manager, uuidFactory = { UUID.fromString(requestId) })
        val submission = client.prepareChat("lost receipt", deadline)

        val first = jobFailure { client.submit(submission) }
        assertEquals(ServerAiJobError.NETWORK, first.reason)
        assertEquals(1, calls.size)
        assertFalse(first.toString().contains("raw-network-secret"))

        now = deadline + 1L
        val recovered = client.submit(submission)

        assertEquals(jobId, recovered.jobId)
        assertEquals(2, calls.size)
        assertArrayEquals(calls[0].body, calls[1].body)
        assertEquals(calls[0].headers["X-Copilot-Request-Id"], calls[1].headers["X-Copilot-Request-Id"])
        assertEquals(calls[0].headers["X-Copilot-Deadline-Ms"], calls[1].headers["X-Copilot-Deadline-Ms"])
        assertNotEquals(calls[0].headers["X-Copilot-Nonce"], calls[1].headers["X-Copilot-Nonce"])
    }

    @Test
    fun concurrentCallsShareOneRefreshThroughTheConnectionMutex() = runTest {
        var refreshes = 0
        var capabilityCalls = 0
        val store = MemoryStore(session(accessExpiresMs = initialNow + 1_000L))
        val manager = ServerAiConnectionManager(
            store,
            CapturingIdentity(),
            ServerAiConnectionTransport { _, path, _, _, _ ->
                when (path) {
                    "/api/ai/v1/session/refresh" -> {
                        refreshes += 1
                        response(200, sessionTokens(accessExpiresMs = initialNow + 600_000L))
                    }
                    "/api/ai/v1/capabilities" -> {
                        capabilityCalls += 1
                        response(200, capabilities(false))
                    }
                    else -> error("unexpected path")
                }
            },
            clock = { initialNow }
        )
        val client = ServerAiJobClient(manager)

        val first = launch { assertFalse(client.capabilities().inferenceEnabled) }
        val second = launch { assertFalse(client.capabilities().inferenceEnabled) }
        first.join()
        second.join()

        assertEquals(1, refreshes)
        assertEquals(2, capabilityCalls)
    }

    @Test
    fun statusAndCancelUseCanonicalOwnedJobPathAndEmptyBodyProof() = runTest {
        val calls = mutableListOf<Call>()
        val identity = CapturingIdentity()
        val manager = ServerAiConnectionManager(
            MemoryStore(session()),
            identity,
            ServerAiConnectionTransport { method, path, body, headers, limit ->
                calls += Call(method, path, body.copyOf(), headers.toMap(), limit)
                response(
                    200,
                    if (method == "DELETE") cancelledReceipt() else runningReceipt()
                )
            },
            clock = { initialNow }
        )
        val client = ServerAiJobClient(manager)

        val running = client.status(jobId)
        val cancelled = client.cancel(jobId)

        assertEquals(ServerAiJobState.RUNNING, running.state)
        assertEquals(ServerAiJobState.CANCELLED, cancelled.state)
        assertTrue(cancelled.terminal)
        assertEquals(listOf("GET", "DELETE"), calls.map { it.method })
        calls.forEach { call ->
            assertEquals("/api/ai/v1/jobs/$jobId", call.path)
            assertTrue(call.body.isEmpty())
            assertNull(call.headers["X-Copilot-Request-Id"])
            assertNull(call.headers["X-Copilot-Deadline-Ms"])
        }
        val signed = identity.messages.map {
            Json.parseToJsonElement(it.toString(Charsets.US_ASCII)).jsonObject
        }
        signed.forEach { proof ->
            assertEquals(sha256(ByteArray(0)), proof.getValue("body_sha256").jsonPrimitive.content)
            assertEquals(sha256(accessToken.toByteArray()), proof.getValue("credential_sha256").jsonPrimitive.content)
        }
    }

    @Test
    fun invalidJobIdsNeverReachTransport() = runTest {
        var calls = 0
        val client = ServerAiJobClient(manager { _, _, _, _, _ ->
            calls += 1
            error("must not send")
        })

        for (invalid in listOf(jobId.uppercase(), "../$jobId", "$jobId?owner=other", "not-a-uuid")) {
            val status = jobFailure { client.status(invalid) }
            val cancel = jobFailure { client.cancel(invalid) }
            assertEquals(ServerAiJobError.INVALID_REQUEST, status.reason)
            assertEquals(ServerAiJobError.INVALID_REQUEST, cancel.reason)
        }
        assertEquals(0, calls)
    }

    @Test
    fun terminalSuccessRemainsValidAfterResultCacheExpiry() = runTest {
        var available = true
        val manager = manager { _, _, _, _, _ ->
            response(200, if (available) succeededReceipt() else succeededExpiredReceipt())
        }
        val client = ServerAiJobClient(manager)

        val withResult = client.status(jobId)
        available = false
        val expired = client.status(jobId)

        assertTrue(withResult.terminal)
        assertTrue(withResult.resultAvailable)
        assertEquals("synthetic response", withResult.result?.text)
        assertNotNull(withResult.resultExpiresMs)
        assertTrue(expired.terminal)
        assertFalse(expired.resultAvailable)
        assertNull(expired.result)
        assertNull(expired.resultExpiresMs)
        assertFalse(withResult.toString().contains("synthetic response"))
    }

    @Test
    fun unknownStateIsExplicitTerminalFailSafeMetadata() = runTest {
        val unknown = runningReceipt()
            .replace("\"RUNNING\"", "\"UNKNOWN\"")
            .replace("\"started_ms\":${initialNow + 1L}", "\"started_ms\":null")
        val client = ServerAiJobClient(manager { _, _, _, _, _ -> response(200, unknown) })

        val snapshot = client.status(jobId)

        assertEquals(ServerAiJobState.UNKNOWN, snapshot.state)
        assertTrue(snapshot.terminal)
        assertFalse(snapshot.resultAvailable)
    }

    @Test
    fun resultUsesUnicodeCodePointsAndRejectsInvalidOrOversizedText() = runTest {
        val accepted = "\uD83D\uDE00".repeat(4_096)
        assertEquals(4_096, accepted.codePointCount(0, accepted.length))
        assertEquals(16_384, accepted.toByteArray(Charsets.UTF_8).size)
        val invalid = listOf(
            succeededReceipt(""),
            succeededReceipt("a".repeat(8_193)),
            succeededReceipt("\uD83D\uDE00".repeat(4_096) + "a"),
            succeededReceipt("ok").replace("\"text\":\"ok\"", "\"text\":\"ok\",\"text\":\"duplicate\""),
            succeededReceipt("ok").replace("\"text\":\"ok\"", "\"text\":\"\\uD800\"")
        )
        var index = -1
        val client = ServerAiJobClient(manager { _, _, _, _, _ ->
            index += 1
            response(200, if (index == 0) succeededReceipt(accepted) else invalid[index - 1])
        })

        val result = client.status(jobId)
        assertEquals(accepted, result.result?.text)
        assertFalse(result.result.toString().contains(accepted))
        repeat(invalid.size) {
            assertEquals(ServerAiJobError.INVALID_RESPONSE, jobFailure { client.status(jobId) }.reason)
        }
    }

    @Test
    fun unknownOrIncoherentLifecycleAndStrictJsonAreRejected() = runTest {
        val invalidBodies = listOf(
            runningReceipt().replace("\"RUNNING\"", "\"FUTURE_STATE\""),
            runningReceipt().replace("\"result_available\":false", "\"result_available\":true"),
            runningReceipt().replace("\"state\":\"RUNNING\"", "\"state\":\"RUNNING\",\"state\":\"FAILED\""),
            runningReceipt().dropLast(1) + ",\"extra\":1}",
            runningReceipt().replace("\"created_ms\":$initialNow", "\"created_ms\":NaN")
        )
        var index = 0
        val client = ServerAiJobClient(manager { _, _, _, _, _ -> response(200, invalidBodies[index++]) })

        repeat(invalidBodies.size) {
            val failure = jobFailure { client.status(jobId) }
            assertEquals(ServerAiJobError.INVALID_RESPONSE, failure.reason)
        }
    }

    @Test
    fun responseSizeMalformedUtf8AndDuplicateCapabilitiesAreRejected() = runTest {
        val responses = listOf(
            ServerAiConnectionResponse(200, ByteArray(65_537) { ' '.code.toByte() }),
            ServerAiConnectionResponse(200, byteArrayOf(0xC3.toByte(), 0x28)),
            response(200, capabilities(true).replace("\"inference_enabled\":true", "\"inference_enabled\":true,\"inference_enabled\":false"))
        )
        var index = 0
        val client = ServerAiJobClient(manager { _, _, _, _, _ -> responses[index++] })

        repeat(responses.size) {
            val failure = jobFailure { client.capabilities() }
            assertEquals(ServerAiJobError.INVALID_RESPONSE, failure.reason)
        }
    }

    @Test
    fun stableHttpStatusesMapToTypedRedactedFailures() = runTest {
        val expected = mapOf(
            400 to ServerAiJobError.INVALID_REQUEST,
            401 to ServerAiJobError.UNAUTHORIZED,
            403 to ServerAiJobError.FORBIDDEN,
            404 to ServerAiJobError.NOT_FOUND,
            405 to ServerAiJobError.METHOD_NOT_ALLOWED,
            409 to ServerAiJobError.REQUEST_CONFLICT,
            413 to ServerAiJobError.REQUEST_TOO_LARGE,
            415 to ServerAiJobError.UNSUPPORTED_MEDIA,
            429 to ServerAiJobError.RATE_LIMITED,
            431 to ServerAiJobError.HEADERS_TOO_LARGE,
            503 to ServerAiJobError.SERVICE_UNAVAILABLE
        )
        var status = 400
        val client = ServerAiJobClient(manager { _, _, _, _, _ ->
            response(status, "{\"error\":\"raw-token-$accessToken\"}")
        }, uuidFactory = { UUID.fromString(requestId) })
        val submission = client.prepareChat("private text", initialNow + 120_000L)

        expected.forEach { (code, reason) ->
            status = code
            val failure = jobFailure { client.submit(submission) }
            assertEquals(reason, failure.reason)
            assertFalse(failure.toString().contains(accessToken))
            assertFalse(failure.toString().contains("private text"))
            assertFalse(failure.toString().contains("raw-token"))
        }
        assertFalse(submission.toString().contains("private text"))
    }

    @Test
    fun coroutineCancellationReachesTransportAndReleasesManagerMutex() = runTest {
        val started = CompletableDeferred<Unit>()
        val cancelled = AtomicBoolean(false)
        var block = true
        val manager = manager { _, _, _, _, _ ->
            if (block) {
                suspendCancellableCoroutine<ServerAiConnectionResponse> { continuation ->
                    started.complete(Unit)
                    continuation.invokeOnCancellation { cancelled.set(true) }
                }
            } else {
                response(200, capabilities(false))
            }
        }
        val client = ServerAiJobClient(manager)
        val job = launch { client.capabilities() }
        started.await()

        job.cancelAndJoin()

        assertTrue(cancelled.get())
        block = false
        assertFalse(client.capabilities().inferenceEnabled)
    }

    private fun manager(
        handler: suspend (String, String, ByteArray, Map<String, String>, Int) -> ServerAiConnectionResponse
    ) = ServerAiConnectionManager(
        MemoryStore(session()),
        CapturingIdentity(),
        ServerAiConnectionTransport(handler),
        clock = { initialNow }
    )

    private fun session(accessExpiresMs: Long = initialNow + 600_000L): String =
        """{"session":${sessionTokens(accessExpiresMs = accessExpiresMs)}}"""

    private fun sessionTokens(
        access: String = accessToken,
        accessExpiresMs: Long = initialNow + 600_000L
    ): String =
        """{"access_token":"$access","refresh_token":"refresh.${"b".repeat(43)}","access_expires_ms":$accessExpiresMs,"refresh_expires_ms":${initialNow + 86_400_000L},"subscription_expires_ms":${initialNow + 86_400_000L}}"""

    private fun receipt(request: String, deadline: Long): String =
        """{"job_id":"$jobId","request_id":"$request","kind":"CHAT","state":"QUEUED","created_ms":$initialNow,"deadline_ms":$deadline,"started_ms":null,"finished_ms":null,"result_available":false}"""

    private fun capabilities(inferenceEnabled: Boolean): String =
        """{"revision":"server-codex-chat-r1a.1","inference_enabled":$inferenceEnabled,"task_kinds":["CHAT"],"input_modalities":["TEXT"],"output_modalities":["TEXT"],"max_text_chars":4096,"max_text_bytes":8192,"max_result_chars":8192,"max_result_bytes":16384,"max_response_bytes":65536,"max_deadline_ms":900000}"""

    private fun runningReceipt(): String =
        """{"job_id":"$jobId","request_id":"$requestId","kind":"CHAT","state":"RUNNING","created_ms":$initialNow,"deadline_ms":${initialNow + 120_000L},"started_ms":${initialNow + 1L},"finished_ms":null,"result_available":false}"""

    private fun cancelledReceipt(): String =
        """{"job_id":"$jobId","request_id":"$requestId","kind":"CHAT","state":"CANCELLED","created_ms":$initialNow,"deadline_ms":${initialNow + 120_000L},"started_ms":null,"finished_ms":${initialNow + 2L},"result_available":false}"""

    private fun succeededReceipt(text: String = "synthetic response"): String =
        """{"job_id":"$jobId","request_id":"$requestId","kind":"CHAT","state":"SUCCEEDED","created_ms":$initialNow,"deadline_ms":${initialNow + 120_000L},"started_ms":${initialNow + 1L},"finished_ms":${initialNow + 2L},"result_available":true,"result":{"text":${Json.encodeToString(kotlinx.serialization.serializer<String>(), text)}},"result_expires_ms":${initialNow + 900_002L}}"""

    private fun succeededExpiredReceipt(): String =
        """{"job_id":"$jobId","request_id":"$requestId","kind":"CHAT","state":"SUCCEEDED","created_ms":$initialNow,"deadline_ms":${initialNow + 120_000L},"started_ms":${initialNow + 1L},"finished_ms":${initialNow + 2L},"result_available":false}"""

    private fun response(status: Int, body: String) =
        ServerAiConnectionResponse(status, body.toByteArray(Charsets.UTF_8))

    private fun assertInvalidRequest(block: () -> Unit) {
        val failure = assertThrows(ServerAiJobFailure::class.java, block)
        assertEquals(ServerAiJobError.INVALID_REQUEST, failure.reason)
    }

    private suspend fun jobFailure(block: suspend () -> Unit): ServerAiJobFailure = try {
        block()
        throw AssertionError("expected ServerAiJobFailure")
    } catch (failure: ServerAiJobFailure) {
        failure
    }

    private fun sha256(value: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(value).joinToString("") { "%02x".format(it) }
}
