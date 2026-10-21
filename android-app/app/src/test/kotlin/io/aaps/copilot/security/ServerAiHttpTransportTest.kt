package io.aaps.copilot.security

import io.aaps.copilot.data.repository.MealPhotoGatewayRequest
import io.aaps.copilot.data.repository.ServerMealPhotoProtocol
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.*
import org.junit.Test

class ServerAiHttpTransportTest {
    private val jobPath = "/api/ai/v1/jobs/f1e19b82-e0f2-4c50-b2bf-03c80c906f2c"
    private val headers = mapOf("X-Copilot-Nonce" to "synthetic-proof", "Authorization" to "Bearer synthetic")

    @Test
    fun largestPreparedPhotoReachesHttpWithoutTheTextBodyLimit() = runBlocking {
        val image = ByteArray(1_048_576).apply {
            this[0] = -1; this[1] = -40; this[2] = -1
            this[lastIndex - 1] = -1; this[lastIndex] = -39
        }
        val submission = ServerMealPhotoProtocol.prepare(
            MealPhotoGatewayRequest(UUID.randomUUID().toString(), image, "image/jpeg"),
            1_800_000_000_000L, 1_800_000_060_000L
        )
        assertTrue(submission.body.size > 96_000)
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(202).setBody("{}"))
            val response = transport(server).exchange("POST", ServerMealPhotoProtocol.JOBS_PATH,
                submission.body, headers, 65_536)
            assertEquals(202, response.statusCode)
            assertEquals(1, server.requestCount)
            val request = requireNotNull(server.takeRequest(1, TimeUnit.SECONDS))
            assertEquals(ServerMealPhotoProtocol.JOBS_PATH, request.path)
            assertArrayEquals(submission.body, request.body.readByteArray())
            assertEquals("synthetic-proof", request.getHeader("X-Copilot-Nonce"))
            assertTrue(request.getHeader("Content-Type")!!.startsWith("application/json"))
        }
    }

    @Test
    fun photoBodyAllowanceEndsAtItsExactRouteBound() = runBlocking {
        MockWebServer().use { server ->
            val transport = transport(server)
            server.enqueue(MockResponse().setResponseCode(202).setBody("{}"))
            val limit = 3 * 1024 * 1024
            assertEquals(202, transport.exchange("POST", "/api/ai/v1/meal-photo/jobs",
                ByteArray(limit), headers, 65_536).statusCode)
            val error = runCatching {
                transport.exchange("POST", "/api/ai/v1/meal-photo/jobs",
                    ByteArray(limit + 1), headers, 65_536)
            }.exceptionOrNull()
            assertTrue(error is IllegalArgumentException)
            assertEquals(1, server.requestCount)
        }
    }

    @Test
    fun photoAllowanceCannotWidenOtherRoutesOrResponseBounds() = runBlocking {
        MockWebServer().use { server ->
            val transport = transport(server)
            for ((path, size) in listOf(
                "/api/ai/v1/jobs" to 16_385,
                "/api/ai/v1/activation/start" to 96_001,
                "/api/ai/v1/meal-photo/jobs/extra" to 96_001,
                "/api/ai/v1/meal-photo/jobs?extra=1" to 96_001
            )) {
                val error = runCatching {
                    transport.exchange("POST", path, ByteArray(size), headers, 65_536)
                }.exceptionOrNull()
                assertTrue("Invalid route or body must be rejected locally", error is IllegalArgumentException)
            }
            assertTrue(runCatching {
                transport.exchange("POST", "/api/ai/v1/meal-photo/jobs", byteArrayOf(1), headers, 65_537)
            }.exceptionOrNull() is IllegalArgumentException)
            assertEquals(0, server.requestCount)
        }
    }

    @Test
    fun largePhotoPostDoesNotFollowRetryAfterZero() = runBlocking {
        assertSingle503("POST", "/api/ai/v1/meal-photo/jobs", ByteArray(128_000))
    }

    @Test
    fun signedPostDoesNotFollowRetryAfterZero() = runBlocking {
        assertSingle503("POST", "/api/ai/v1/jobs", "{\"text\":\"synthetic\"}".toByteArray())
    }

    @Test
    fun signedGetDoesNotFollowRetryAfterZero() = runBlocking {
        assertSingle503("GET", jobPath, byteArrayOf())
    }

    @Test
    fun signedDeleteDoesNotFollowRetryAfterZero() = runBlocking {
        assertSingle503("DELETE", jobPath, byteArrayOf())
    }

    private suspend fun assertSingle503(method: String, path: String, body: ByteArray) {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(503).setHeader("Retry-After", "0")
                .setBody("{\"error\":\"service_unavailable\"}"))
            server.enqueue(MockResponse().setBody("must not be reached"))
            val response = transport(server).exchange(method, path, body, headers, 65_536)
            assertEquals(503, response.statusCode)
            assertEquals(1, server.requestCount)
            val request = requireNotNull(server.takeRequest(1, TimeUnit.SECONDS))
            assertEquals(method, request.method)
            assertEquals(path, request.path)
            assertArrayEquals(body, request.body.readByteArray())
            assertEquals("synthetic-proof", request.getHeader("X-Copilot-Nonce"))
        }
    }

    @Test
    fun streamedResponseHonorsSeparateByteBound() = runBlocking {
        MockWebServer().use { server ->
            val transport = transport(server)
            server.enqueue(MockResponse().setChunkedBody("x".repeat(65_536), 1024))
            assertEquals(65_536, transport.exchange("GET", jobPath, byteArrayOf(), headers, 65_536).body.size)
            server.enqueue(MockResponse().setChunkedBody("x".repeat(65_537), 1024))
            val error = runCatching {
                transport.exchange("GET", jobPath, byteArrayOf(), headers, 65_536)
            }.exceptionOrNull()
            assertTrue(error is ServerAiConnectionFailure)
            assertEquals(ServerAiConnectionError.INVALID_RESPONSE, (error as ServerAiConnectionFailure).reason)
        }
    }

    @Test
    fun activationStillUsesItsSmallerResponseLimit() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setChunkedBody("x".repeat(16_385), 1024))
            val error = runCatching {
                transport(server).exchange("GET", "/api/ai/v1/session/status", byteArrayOf(), headers, 16_384)
            }.exceptionOrNull()
            assertTrue(error is ServerAiConnectionFailure)
            assertEquals(ServerAiConnectionError.INVALID_RESPONSE, (error as ServerAiConnectionFailure).reason)
        }
    }

    @Test
    fun cancellationReachesTheActualOkHttpCall() = runBlocking {
        MockWebServer().use { server ->
            val cancelled = CompletableDeferred<Unit>()
            val transport = transport(server) { cancelled.complete(Unit) }
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val job = launch { transport.exchange("GET", jobPath, byteArrayOf(), headers, 65_536) }
            assertNotNull(withContext(Dispatchers.IO) { server.takeRequest(2, TimeUnit.SECONDS) })
            job.cancelAndJoin()
            withTimeout(2_000) { cancelled.await() }
            assertEquals(1, server.requestCount)
        }
    }

    @Test
    fun redirectsRemainTerminalWithoutForwardingProof() = runBlocking {
        MockWebServer().use { first ->
            MockWebServer().use { second ->
                first.enqueue(MockResponse().setResponseCode(307).setHeader("Location", second.url("/capture")))
                val response = transport(first).exchange("GET", jobPath, byteArrayOf(), headers, 65_536)
                assertEquals(307, response.statusCode)
                assertEquals(1, first.requestCount)
                assertEquals(0, second.requestCount)
            }
        }
    }

    private fun transport(server: MockWebServer, onFailure: () -> Unit = {}): ServerAiConnectionTransport {
        val type = Class.forName("io.aaps.copilot.security.HttpsConnectionTransport")
        val transport = type.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
        val clientField = type.getDeclaredField("client").apply { isAccessible = true }
        val original = clientField.get(transport) as OkHttpClient
        // Keep real production policies; remap the fixed host only inside this JVM fixture.
        val testClient = original.newBuilder()
            .callTimeout(3, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                val request = chain.request()
                check(request.url.host == "diai.centv.ru" && request.url.isHttps)
                chain.proceed(request.newBuilder().url(server.url(request.url.encodedPath)).build())
            }
            .eventListener(object : EventListener() {
                override fun callFailed(call: Call, ioe: IOException) = onFailure()
            })
            .build()
        clientField.set(transport, testClient)
        return transport as ServerAiConnectionTransport
    }
}
