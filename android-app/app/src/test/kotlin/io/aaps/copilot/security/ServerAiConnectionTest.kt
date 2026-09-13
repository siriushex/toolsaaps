package io.aaps.copilot.security

import java.io.IOException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test

class ServerAiConnectionTest {
    private val now = 1_800_000_000_000L
    private class MemoryStore : ServerAiConnectionPersistence {
        var value: String? = null
        override suspend fun read() = value
        override suspend fun write(value: String) { this.value = value }
    }
    private class Device : ServerAiConnectionIdentity {
        var created = 0
        private var chain: List<String>? = null
        override fun existingChain() = chain
        override fun create(challenge: ByteArray): List<String> {
            assertEquals(32, challenge.size)
            created++
            return listOf("certificate", "root").also { chain = it }
        }
        override fun sign(message: ByteArray) = "proof"
        override fun fingerprint() = "a".repeat(64)
    }
    private fun tokens(latency: Long = 0) = """{"access_token":"access.${"a".repeat(43)}","refresh_token":"refresh.${"b".repeat(43)}","access_expires_ms":${now + 600_000 + latency},"refresh_expires_ms":${now + 86_400_000},"subscription_expires_ms":${now + 86_400_000}}"""

    @Test fun activationPersistsAndRestartDoesNotReenterCode() = runTest {
        val store = MemoryStore()
        val key = Device()
        val calls = mutableListOf<String>()
        val transport = ServerAiConnectionTransport { _, path, body, headers ->
            calls += path
            when {
                path.endsWith("/start") -> {
                    assertFalse(headers.containsKey("Authorization"))
                    val id = kotlinx.serialization.json.Json.parseToJsonElement(body.toString(Charsets.UTF_8))
                        .let { it as kotlinx.serialization.json.JsonObject }.getValue("request_id")
                    """{"request_id":$id,"challenge":"${java.util.Base64.getEncoder().encodeToString(ByteArray(32))}","expires_ms":${now + 600_000},"server_time_ms":$now}"""
                }
                path.endsWith("/complete") -> {
                    assertTrue(headers.containsKey("X-Copilot-Signature"))
                    tokens(latency = 500)
                }
                else -> """{"subscription_expires_ms":${now + 86_400_000},"server_time_ms":$now,"inference_enabled":false}"""
            }
        }
        val manager = ServerAiConnectionManager(store, key, transport, clock = { now })
        manager.activate("abcd efgh jkmn pqrs")
        assertEquals(ServerAiConnectionPhase.ACTIVE, manager.state.value.phase)
        assertFalse(manager.state.value.inferenceReady)
        assertFalse(store.value!!.contains("ABCD-EFGH-JKMN-PQRS"))
        assertEquals(1, key.created)
        val reopened = ServerAiConnectionManager(store, key, transport, clock = { now })
        reopened.load()
        assertEquals(ServerAiConnectionPhase.SAVED, reopened.state.value.phase)
        assertEquals(2, calls.size)
        reopened.check()
        assertEquals(ServerAiConnectionPhase.ACTIVE, reopened.state.value.phase)
        assertEquals(1, key.created)
    }

    @Test fun unknownActivationResponsePreservesPendingAndReusesDeviceKey() = runTest {
        val store = MemoryStore()
        val key = Device()
        var fail = true
        val bodies = mutableListOf<String>()
        val transport = ServerAiConnectionTransport { _, path, body, _ ->
            val value = body.toString(Charsets.UTF_8)
            if (path.endsWith("/start")) {
                val id = (kotlinx.serialization.json.Json.parseToJsonElement(value) as kotlinx.serialization.json.JsonObject).getValue("request_id")
                """{"request_id":$id,"challenge":"${java.util.Base64.getEncoder().encodeToString(ByteArray(32))}","expires_ms":${now + 600_000},"server_time_ms":$now}"""
            } else {
                bodies += value
                if (fail) throw IOException("sensitive-network-detail")
                tokens()
            }
        }
        val manager = ServerAiConnectionManager(store, key, transport, clock = { now })
        manager.activate("ABCD-EFGH-JKMN-PQRS")
        assertEquals(ServerAiConnectionPhase.ERROR, manager.state.value.phase)
        assertTrue(manager.state.value.canResume)
        assertFalse(manager.state.value.toString().contains("sensitive-network-detail"))
        fail = false
        val reopened = ServerAiConnectionManager(store, key, transport, clock = { now })
        reopened.resume()
        assertEquals(ServerAiConnectionPhase.ACTIVE, reopened.state.value.phase)
        assertEquals(bodies[0], bodies[1])
        assertEquals(1, key.created)
    }

    @Test fun invalidCodeNeverCreatesKeyOrRequest() = runTest {
        val key = Device()
        val manager = ServerAiConnectionManager(MemoryStore(), key,
            ServerAiConnectionTransport { _, _, _, _ -> error("must not send") }, clock = { now })
        manager.activate("1234")
        assertEquals(ServerAiConnectionError.INVALID_CODE, manager.state.value.error)
        assertEquals(0, key.created)
    }

    @Test fun storageFailureDoesNotSendOrOverwriteDeviceIdentity() = runTest {
        val key = Device()
        val persistence = object : ServerAiConnectionPersistence {
            override suspend fun read(): String? = null
            override suspend fun write(value: String) { throw IOException("private-storage-detail") }
        }
        val manager = ServerAiConnectionManager(persistence, key,
            ServerAiConnectionTransport { _, _, _, _ -> error("must not send") }, clock = { now })
        manager.activate("ABCD-EFGH-JKMN-PQRS")
        assertEquals(ServerAiConnectionError.STORAGE, manager.state.value.error)
        assertEquals(0, key.created)
        assertFalse(manager.state.value.toString().contains("private-storage-detail"))
    }

    @Test fun existingOrphanKeyIsNeverSilentlyReplaced() = runTest {
        val key = Device().apply { create(ByteArray(32)) }
        val manager = ServerAiConnectionManager(MemoryStore(), key,
            ServerAiConnectionTransport { _, _, _, _ -> error("must not send") }, clock = { now })
        manager.activate("ABCD-EFGH-JKMN-PQRS")
        assertEquals(ServerAiConnectionError.DEVICE_KEY, manager.state.value.error)
        assertEquals(1, key.created)
    }

    @Test fun noNetworkIsStartedByLoadingEmptyState() = runTest {
        val manager = ServerAiConnectionManager(MemoryStore(), Device(),
            ServerAiConnectionTransport { _, _, _, _ -> error("must not send") }, clock = { now })
        manager.load()
        assertEquals(ServerAiConnectionPhase.DISCONNECTED, manager.state.value.phase)
        assertFalse(manager.state.value.hasStoredSession)
    }

    @Test fun definitivelyRejectedStartLetsUserCorrectCode() = runTest {
        val store = MemoryStore()
        val manager = ServerAiConnectionManager(store, Device(),
            ServerAiConnectionTransport { _, _, _, _ ->
                throw ServerAiConnectionFailure(ServerAiConnectionError.UNAUTHORIZED)
            }, clock = { now })
        manager.activate("ABCD-EFGH-JKMN-PQRS")
        assertEquals(ServerAiConnectionError.UNAUTHORIZED, manager.state.value.error)
        assertFalse(manager.state.value.canResume)
        assertFalse(store.value!!.contains("ABCDEFGHJKMNPQRS"))
    }

    @Test fun refreshCannotChangeTheSubscriptionBoundary() = runTest {
        for (delta in listOf(-1_000L, 86_400_000L)) {
            val store = MemoryStore().apply {
                value = """{"session":${tokens()},"pending":{"id":"2ec58479-e05b-4805-87f6-5bb06b04bb2d","refresh":"refresh.${"b".repeat(43)}"}}"""
            }
            val changed = Json.parseToJsonElement(tokens()).jsonObject.toMutableMap().apply {
                put("subscription_expires_ms", JsonPrimitive(now + 86_400_000L + delta))
                put("refresh_expires_ms", JsonPrimitive(now + 800_000L))
            }
            val manager = ServerAiConnectionManager(store, Device(),
                ServerAiConnectionTransport { _, path, _, _ ->
                    assertTrue(path.endsWith("/session/refresh"))
                    JsonObject(changed).toString()
                }, clock = { now })
            manager.resume()
            assertEquals(ServerAiConnectionError.INVALID_RESPONSE, manager.state.value.error)
            assertTrue(manager.state.value.canResume)
            assertEquals(now + 86_400_000L, manager.state.value.subscriptionExpiresMs)
            val saved = Json.parseToJsonElement(store.value!!).jsonObject.getValue("session").jsonObject
            assertEquals(JsonPrimitive(now + 86_400_000L), saved["subscription_expires_ms"])
        }
    }

    @Test fun recoveredExpiredAccessIsSavedThenRefreshedBeforeStatus() = runTest {
        val later = now + 660_000L
        val store = MemoryStore().apply {
            value = """{"pending":{"id":"2ec58479-e05b-4805-87f6-5bb06b04bb2d","challenge":"${java.util.Base64.getEncoder().encodeToString(ByteArray(32))}","chain":["certificate","root"]}}"""
        }
        val calls = mutableListOf<String>()
        val manager = ServerAiConnectionManager(store, Device(),
            ServerAiConnectionTransport { _, path, _, _ ->
                calls += path.substringAfterLast('/')
                when {
                    path.endsWith("/complete") -> tokens()
                    path.endsWith("/refresh") -> tokens(latency = 660_000L)
                    path.endsWith("/status") ->
                        """{"subscription_expires_ms":${now + 86_400_000L},"server_time_ms":$later,"inference_enabled":false}"""
                    else -> error("unexpected request")
                }
            }, clock = { later })
        manager.resume()
        assertEquals(ServerAiConnectionPhase.SAVED, manager.state.value.phase)
        assertTrue(manager.state.value.hasStoredSession)
        assertFalse(manager.state.value.canResume)
        assertFalse(manager.state.value.inferenceReady)
        manager.check()
        assertEquals(ServerAiConnectionPhase.ACTIVE, manager.state.value.phase)
        assertEquals(listOf("complete", "refresh", "status"), calls)
        assertEquals(now + 86_400_000L, manager.state.value.subscriptionExpiresMs)
    }
}
