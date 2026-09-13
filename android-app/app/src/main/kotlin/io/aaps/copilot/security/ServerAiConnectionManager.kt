package io.aaps.copilot.security

import android.content.Context
import io.aaps.copilot.data.repository.OneShotJsonRequestBody
import java.io.IOException
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.util.Base64
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request

enum class ServerAiConnectionPhase { DISCONNECTED, SAVED, CONNECTING, ACTIVE, ERROR }
enum class ServerAiConnectionError { INVALID_CODE, NETWORK, UNAUTHORIZED, DEVICE_KEY, STORAGE, INVALID_RESPONSE }
data class ServerAiConnectionState(
    val phase: ServerAiConnectionPhase = ServerAiConnectionPhase.DISCONNECTED,
    val subscriptionExpiresMs: Long? = null,
    val inferenceReady: Boolean = false,
    val canResume: Boolean = false,
    val hasStoredSession: Boolean = false,
    val error: ServerAiConnectionError? = null,
    val revision: Long = 0
)

internal interface ServerAiConnectionPersistence {
    suspend fun read(): String?
    suspend fun write(value: String)
}
internal interface ServerAiConnectionIdentity {
    fun existingChain(): List<String>?
    fun create(challenge: ByteArray): List<String>
    fun sign(message: ByteArray): String
    fun fingerprint(): String
}
internal class ServerAiConnectionResponse(statusCode: Int, body: ByteArray) {
    val statusCode: Int = statusCode.also { require(it in 100..599) }
    private val value = body.copyOf()
    val body: ByteArray get() = value.copyOf()
    override fun toString() = "ServerAiConnectionResponse(statusCode=$statusCode, bodyBytes=${value.size})"
}
internal fun interface ServerAiConnectionTransport {
    suspend fun exchange(
        method: String,
        path: String,
        body: ByteArray,
        headers: Map<String, String>,
        responseLimit: Int
    ): ServerAiConnectionResponse
}
internal class ServerAiConnectionFailure(val reason: ServerAiConnectionError) : Exception("server_ai_connection_failed")

@Serializable
private data class ServerSession(
    @SerialName("access_token") val access: String,
    @SerialName("refresh_token") val refresh: String,
    @SerialName("access_expires_ms") val accessExpires: Long,
    @SerialName("refresh_expires_ms") val refreshExpires: Long,
    @SerialName("subscription_expires_ms") val subscriptionExpires: Long
) { override fun toString() = "ServerSession(redacted)" }

@Serializable
private data class PendingConnection(
    val id: String,
    val code: String? = null,
    val challenge: String? = null,
    val chain: List<String>? = null,
    val refresh: String? = null
) { override fun toString() = "PendingConnection(redacted)" }

@Serializable
private data class ConnectionRecord(
    val session: ServerSession? = null,
    val pending: PendingConnection? = null,
    val offsetMs: Long = 0
) { override fun toString() = "ConnectionRecord(redacted)" }

@Serializable private data class StartRequest(val code: String, @SerialName("request_id") val requestId: String)
@Serializable private data class CompleteRequest(@SerialName("request_id") val requestId: String,
    @SerialName("certificate_chain") val chain: List<String>)
@Serializable private data class RefreshRequest(@SerialName("request_id") val requestId: String,
    @SerialName("refresh_token") val refresh: String)
@Serializable private data class StartResponse(@SerialName("request_id") val requestId: String, val challenge: String,
    @SerialName("expires_ms") val expires: Long, @SerialName("server_time_ms") val serverTime: Long)
@Serializable private data class StatusResponse(@SerialName("subscription_expires_ms") val expires: Long,
    @SerialName("server_time_ms") val serverTime: Long, @SerialName("inference_enabled") val ready: Boolean)

class ServerAiConnectionManager internal constructor(
    private val persistence: ServerAiConnectionPersistence,
    private val identity: ServerAiConnectionIdentity,
    private val transport: ServerAiConnectionTransport,
    private val clock: () -> Long = System::currentTimeMillis
) {
    companion object {
        private const val BASE = "/api/ai/v1"
        private const val CONNECTION_RESPONSE_LIMIT = 16_384
        private const val JOB_RESPONSE_LIMIT = 65_536
        fun create(context: Context): ServerAiConnectionManager = ServerAiConnectionManager(
            EncryptedConnectionPersistence(KeystoreSecretStorage(context.applicationContext,
                RuntimeSecretStorageNamespaces.SERVER_AI_CONNECTION)),
            HardwareConnectionIdentity(), HttpsConnectionTransport()
        )
    }
    private val mutex = Mutex()
    private val json = Json { encodeDefaults = true }
    private val mutableState = MutableStateFlow(ServerAiConnectionState())
    val state = mutableState.asStateFlow()
    private var record = ConnectionRecord()

    suspend fun load() = operation {
        record = read()
        publish(ServerAiConnectionPhase.SAVED.takeIf { record.session != null }
            ?: ServerAiConnectionPhase.DISCONNECTED)
    }

    suspend fun activate(code: String) = operation {
        record = read()
        if (record.pending != null || record.session != null) {
            publish(ServerAiConnectionPhase.SAVED)
            return@operation
        }
        val normalized = code.uppercase(java.util.Locale.ROOT).replace(" ", "").replace("-", "")
        if (code.length > 64 || code.any { it.code > 127 } ||
            !Regex("[0-9A-HJKMNP-TV-Z]{16}").matches(normalized)) {
            throw ServerAiConnectionFailure(ServerAiConnectionError.INVALID_CODE)
        }
        keyCall { check(identity.existingChain() == null) }
        save(ConnectionRecord(pending = PendingConnection(UUID.randomUUID().toString(), code = normalized)))
        continuePending()
    }

    suspend fun resume() = operation {
        record = read()
        continuePending()
    }

    suspend fun check() = operation {
        record = read()
        if (record.pending != null) {
            continuePending()
            return@operation
        }
        val session = record.session ?: throw ServerAiConnectionFailure(ServerAiConnectionError.UNAUTHORIZED)
        if (session.accessExpires <= serverNow() + 30_000) {
            save(record.copy(pending = PendingConnection(UUID.randomUUID().toString(), refresh = session.refresh)))
            continuePending()
        }
        val active = record.session ?: throw ServerAiConnectionFailure(ServerAiConnectionError.UNAUTHORIZED)
        val response = response<StatusResponse>(transport.exchange("GET", "$BASE/session/status", byteArrayOf(),
            proof("GET", "$BASE/session/status", byteArrayOf(), active.access, bound = true) +
                ("Authorization" to "Bearer ${active.access}"), CONNECTION_RESPONSE_LIMIT))
        if (response.expires != active.subscriptionExpires || response.serverTime >= response.expires) {
            throw ServerAiConnectionFailure(ServerAiConnectionError.INVALID_RESPONSE)
        }
        publish(ServerAiConnectionPhase.ACTIVE, response.ready)
    }

    private suspend fun continuePending() {
        var pending = record.pending ?: throw ServerAiConnectionFailure(ServerAiConnectionError.UNAUTHORIZED)
        if (pending.refresh != null) {
            val body = json.encodeToString(RefreshRequest(pending.id, pending.refresh)).toByteArray()
            val tokens = response<ServerSession>(transport.exchange("POST", "$BASE/session/refresh", body,
                proof("POST", "$BASE/session/refresh", body, pending.refresh, bound = true), CONNECTION_RESPONSE_LIMIT))
            if (tokens.subscriptionExpires != record.session?.subscriptionExpires) {
                throw ServerAiConnectionFailure(ServerAiConnectionError.INVALID_RESPONSE)
            }
            accept(tokens)
            return
        }
        if (pending.challenge == null) {
            val body = json.encodeToString(StartRequest(pending.code ?: error("pending_code_missing"), pending.id)).toByteArray()
            val response = try {
                response<StartResponse>(transport.exchange(
                    "POST", "$BASE/activation/start", body, emptyMap(), CONNECTION_RESPONSE_LIMIT
                ))
            } catch (failure: ServerAiConnectionFailure) {
                // A rejected start cannot have registered a key. Unknown network
                // outcomes retain the same request ID for safe continuation.
                if (failure.reason == ServerAiConnectionError.UNAUTHORIZED && keyCall { identity.existingChain() == null }) {
                    save(record.copy(pending = null))
                }
                throw failure
            }
            if (response.requestId != pending.id || response.serverTime <= 60_000 ||
                response.expires <= response.serverTime || response.expires - response.serverTime > 600_000 ||
                runCatching { Base64.getDecoder().decode(response.challenge).size }.getOrNull() != 32) {
                throw ServerAiConnectionFailure(ServerAiConnectionError.INVALID_RESPONSE)
            }
            pending = pending.copy(code = null, challenge = response.challenge)
            save(record.copy(pending = pending, offsetMs = Math.subtractExact(response.serverTime, clock())))
        }
        if (pending.chain == null) {
            val chain = keyCall { identity.existingChain() ?: identity.create(Base64.getDecoder().decode(pending.challenge)) }
            pending = pending.copy(chain = chain)
            save(record.copy(pending = pending))
        }
        val body = json.encodeToString(CompleteRequest(pending.id, pending.chain ?: error("chain_missing"))).toByteArray()
        val tokens = response<ServerSession>(transport.exchange("POST", "$BASE/activation/complete", body,
            proof("POST", "$BASE/activation/complete", body, pending.id, bound = false), CONNECTION_RESPONSE_LIMIT))
        accept(tokens)
    }

    internal fun currentServerTimeMs(): Long = serverNow()

    internal suspend fun exchangeJob(
        method: String,
        path: String,
        body: ByteArray,
        requestId: String? = null,
        deadlineMs: Long? = null
    ): ServerAiConnectionResponse {
        require(isJobRequest(method, path, body, requestId, deadlineMs)) { "invalid_request" }
        mutex.lock()
        try {
            return withContext(Dispatchers.IO) {
                record = read()
                var refreshed = false
                record.pending?.let { pending ->
                    if (pending.refresh == null) {
                        throw ServerAiConnectionFailure(ServerAiConnectionError.UNAUTHORIZED)
                    }
                    continuePending()
                    refreshed = true
                }
                var active = record.session
                    ?: throw ServerAiConnectionFailure(ServerAiConnectionError.UNAUTHORIZED)
                var now = serverNow()
                if (active.subscriptionExpires <= now || active.refreshExpires <= now) {
                    throw ServerAiConnectionFailure(ServerAiConnectionError.UNAUTHORIZED)
                }
                if (!refreshed && (active.accessExpires <= now || active.accessExpires - now <= 30_000L)) {
                    save(record.copy(pending = PendingConnection(UUID.randomUUID().toString(), refresh = active.refresh)))
                    continuePending()
                    active = record.session
                        ?: throw ServerAiConnectionFailure(ServerAiConnectionError.UNAUTHORIZED)
                    now = serverNow()
                }
                if (active.accessExpires <= now || active.subscriptionExpires <= now || active.refreshExpires <= now) {
                    throw ServerAiConnectionFailure(ServerAiConnectionError.UNAUTHORIZED)
                }
                val credential = if (requestId != null && deadlineMs != null) {
                    "${active.access}\n$requestId\n$deadlineMs"
                } else {
                    active.access
                }
                val headers = buildMap {
                    putAll(proof(method, path, body, credential, bound = true))
                    put("Authorization", "Bearer ${active.access}")
                    if (requestId != null && deadlineMs != null) {
                        put("X-Copilot-Request-Id", requestId)
                        put("X-Copilot-Deadline-Ms", deadlineMs.toString())
                    }
                }
                transport.exchange(method, path, body, headers, JOB_RESPONSE_LIMIT)
            }
        } finally {
            mutex.unlock()
        }
    }

    private fun isJobRequest(
        method: String,
        path: String,
        body: ByteArray,
        requestId: String?,
        deadlineMs: Long?
    ): Boolean = when {
        method == "GET" && path == "$BASE/capabilities" ->
            body.isEmpty() && requestId == null && deadlineMs == null
        method == "POST" && path == "$BASE/jobs" ->
            body.isNotEmpty() && body.size <= 16_384 && isCanonicalUuid(requestId) && deadlineMs != null && deadlineMs > 0
        method in setOf("GET", "DELETE") && path.startsWith("$BASE/jobs/") ->
            body.isEmpty() && requestId == null && deadlineMs == null &&
                isCanonicalUuid(path.removePrefix("$BASE/jobs/"))
        else -> false
    }

    private fun isCanonicalUuid(value: String?): Boolean = runCatching {
        value != null && UUID.fromString(value).toString() == value
    }.getOrDefault(false)

    private suspend fun accept(tokens: ServerSession) {
        val now = serverNow()
        if (!Regex("access\\.[A-Za-z0-9_-]{43}").matches(tokens.access) ||
            !Regex("refresh\\.[A-Za-z0-9_-]{43}").matches(tokens.refresh) ||
            tokens.accessExpires <= 0 || tokens.accessExpires > now + 660_000 ||
            tokens.refreshExpires <= now || tokens.refreshExpires > now + 30L * 86_400_000 + 60_000 ||
            tokens.subscriptionExpires < tokens.refreshExpires || tokens.refreshExpires < tokens.accessExpires ||
            tokens.subscriptionExpires > now + 366L * 86_400_000 + 60_000) {
            throw ServerAiConnectionFailure(ServerAiConnectionError.INVALID_RESPONSE)
        }
        save(record.copy(session = tokens, pending = null))
        // An idempotent recovery may return the original expired access token.
        // Keep its valid refresh token, but require a check before claiming active access.
        publish(if (tokens.accessExpires <= now) ServerAiConnectionPhase.SAVED else ServerAiConnectionPhase.ACTIVE)
    }

    private fun proof(method: String, path: String, body: ByteArray, credential: String, bound: Boolean): Map<String, String> {
        val now = serverNow()
        val nonce = UUID.randomUUID().toString()
        val signature = keyCall { identity.sign(ServerAiRequestProof.signingBytes(method, path, body, credential, now, nonce)) }
        return buildMap {
            put("X-Copilot-Signature", signature)
            put("X-Copilot-Issued-Ms", now.toString())
            put("X-Copilot-Nonce", nonce)
            if (bound) put("X-Copilot-Key", keyCall { identity.fingerprint() })
        }
    }

    private fun serverNow() = Math.addExact(clock(), record.offsetMs)
    private inline fun <reified T> response(value: ServerAiConnectionResponse): T = try {
        if (value.statusCode == 401 || value.statusCode == 403) {
            throw ServerAiConnectionFailure(ServerAiConnectionError.UNAUTHORIZED)
        }
        if (value.statusCode != 200) throw ServerAiConnectionFailure(ServerAiConnectionError.NETWORK)
        val body = value.body
        check(body.size <= CONNECTION_RESPONSE_LIMIT)
        json.decodeFromString<T>(body.toString(Charsets.UTF_8))
    } catch (failure: ServerAiConnectionFailure) {
        throw failure
    } catch (_: Exception) { throw ServerAiConnectionFailure(ServerAiConnectionError.INVALID_RESPONSE) }

    private inline fun <T> keyCall(block: () -> T): T = try { block() }
        catch (_: Exception) { throw ServerAiConnectionFailure(ServerAiConnectionError.DEVICE_KEY) }

    private suspend fun read(): ConnectionRecord = try {
        persistence.read()?.let { check(it.length <= 131_072); json.decodeFromString<ConnectionRecord>(it) } ?: ConnectionRecord()
    } catch (cancelled: CancellationException) { throw cancelled }
      catch (_: Exception) { throw ServerAiConnectionFailure(ServerAiConnectionError.STORAGE) }

    private suspend fun save(value: ConnectionRecord) {
        try { persistence.write(json.encodeToString(value)) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { throw ServerAiConnectionFailure(ServerAiConnectionError.STORAGE) }
        record = value
    }

    private fun publish(phase: ServerAiConnectionPhase, ready: Boolean = false, error: ServerAiConnectionError? = null) {
        mutableState.value = ServerAiConnectionState(phase, record.session?.subscriptionExpires, ready,
            record.pending != null, record.session != null, error, mutableState.value.revision + 1)
    }

    private suspend fun operation(block: suspend () -> Unit) {
        if (!mutex.tryLock()) return
        publish(ServerAiConnectionPhase.CONNECTING)
        try { withContext(Dispatchers.IO) { block() } }
        catch (cancelled: CancellationException) { publish(ServerAiConnectionPhase.ERROR, error = ServerAiConnectionError.NETWORK); throw cancelled }
        catch (failure: ServerAiConnectionFailure) { publish(ServerAiConnectionPhase.ERROR, error = failure.reason) }
        catch (_: IOException) { publish(ServerAiConnectionPhase.ERROR, error = ServerAiConnectionError.NETWORK) }
        catch (_: Exception) { publish(ServerAiConnectionPhase.ERROR, error = ServerAiConnectionError.INVALID_RESPONSE) }
        finally { mutex.unlock() }
    }

}

private class EncryptedConnectionPersistence(private val storage: SecretStorage) : ServerAiConnectionPersistence {
    override suspend fun read(): String? {
        storage.rollbackPromotion()
        return storage.read().also { storage.discardRollback() }
    }
    override suspend fun write(value: String) {
        storage.stagePending(value, null)
        check(storage.readPending() == value)
        storage.commitPending()
        check(storage.read() == value)
        storage.discardRollback()
    }
}

private class HardwareConnectionIdentity(private val key: ServerAiDeviceKey = ServerAiDeviceKey()) : ServerAiConnectionIdentity {
    override fun existingChain() = key.existingChain()
    override fun create(challenge: ByteArray) = key.create(challenge)
    override fun sign(message: ByteArray) = key.sign(message)
    override fun fingerprint(): String {
        val leaf = Base64.getDecoder().decode(key.existingChain()?.firstOrNull() ?: error("device_key_missing"))
        val certificate = CertificateFactory.getInstance("X.509").generateCertificate(leaf.inputStream())
        return MessageDigest.getInstance("SHA-256").digest(certificate.publicKey.encoded).joinToString("") { "%02x".format(it) }
    }
}

private class HttpsConnectionTransport : ServerAiConnectionTransport {
    private val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .addNetworkInterceptor { chain ->
            val response = chain.proceed(chain.request())
            // OkHttp otherwise replays 503 + Retry-After: 0, including GET/DELETE single-use proofs.
            if (response.code == 503) response.newBuilder().removeHeader("Retry-After").build() else response
        }
        .callTimeout(20, TimeUnit.SECONDS).connectTimeout(10, TimeUnit.SECONDS).build()

    override suspend fun exchange(
        method: String,
        path: String,
        body: ByteArray,
        headers: Map<String, String>,
        responseLimit: Int
    ): ServerAiConnectionResponse {
        require(isAllowed(method, path, body)) { "invalid_request" }
        require(body.size <= 96_000)
        require(responseLimit in 1..65_536)
        val request = Request.Builder().url("https://diai.centv.ru$path").apply {
            headers.forEach { (name, value) -> header(name, value) }
            when (method) {
                "GET" -> get()
                "POST" -> post(OneShotJsonRequestBody(body))
                "DELETE" -> delete()
            }
        }.build()
        return kotlinx.coroutines.suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : okhttp3.Callback {
                override fun onFailure(call: okhttp3.Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWith(Result.failure(IOException("server_unavailable")))
                }
                override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                    val result = runCatching {
                        response.use {
                            if (it.body.contentLength() > responseLimit) {
                                throw ServerAiConnectionFailure(ServerAiConnectionError.INVALID_RESPONSE)
                            }
                            val stream = it.body.byteStream()
                            val output = java.io.ByteArrayOutputStream()
                            val buffer = ByteArray(2048)
                            while (true) {
                                val count = stream.read(buffer)
                                if (count < 0) break
                                if (output.size() + count > responseLimit) {
                                    throw ServerAiConnectionFailure(ServerAiConnectionError.INVALID_RESPONSE)
                                }
                                output.write(buffer, 0, count)
                            }
                            ServerAiConnectionResponse(it.code, output.toByteArray())
                        }
                    }
                    if (continuation.isActive) continuation.resumeWith(result)
                }
            })
        }
    }

    private fun isAllowed(method: String, path: String, body: ByteArray): Boolean {
        val activationPost = path in setOf(
            "/api/ai/v1/activation/start",
            "/api/ai/v1/activation/complete",
            "/api/ai/v1/session/refresh"
        )
        if (method == "POST" && activationPost) return true
        if (method == "GET" && path in setOf("/api/ai/v1/session/status", "/api/ai/v1/capabilities")) {
            return body.isEmpty()
        }
        if (method == "POST" && path == "/api/ai/v1/jobs") return body.size in 1..16_384
        if (method !in setOf("GET", "DELETE") || body.isNotEmpty()) return false
        val id = path.removePrefix("/api/ai/v1/jobs/")
        if (id == path) return false
        return runCatching { UUID.fromString(id).toString() == id }.getOrDefault(false)
    }
}
