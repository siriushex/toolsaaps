package io.aaps.copilot.data.repository

import com.google.gson.Strictness
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import io.aaps.copilot.security.ServerAiConnectionError
import io.aaps.copilot.security.ServerAiConnectionFailure
import io.aaps.copilot.security.ServerAiConnectionManager
import io.aaps.copilot.security.ServerAiConnectionResponse
import java.io.IOException
import java.io.StringReader
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

enum class ServerAiJobState {
    QUEUED,
    RUNNING,
    CANCEL_REQUESTED,
    SUCCEEDED,
    FAILED,
    CANCELLED,
    EXPIRED,
    UNKNOWN;

    val terminal: Boolean
        get() = this !in setOf(QUEUED, RUNNING, CANCEL_REQUESTED)
}

enum class ServerAiJobError {
    INVALID_REQUEST,
    UNAUTHORIZED,
    FORBIDDEN,
    NOT_FOUND,
    METHOD_NOT_ALLOWED,
    REQUEST_CONFLICT,
    REQUEST_TOO_LARGE,
    UNSUPPORTED_MEDIA,
    RATE_LIMITED,
    HEADERS_TOO_LARGE,
    SERVICE_UNAVAILABLE,
    NETWORK,
    INVALID_RESPONSE
}

class ServerAiJobFailure(val reason: ServerAiJobError) : IOException("server_ai_job_failed") {
    override fun toString() = "ServerAiJobFailure(reason=$reason)"
}

class ServerAiChatSubmission internal constructor(
    val requestId: String,
    val deadlineMs: Long,
    body: ByteArray
) {
    private val value = body.copyOf()
    internal val body: ByteArray get() = value.copyOf()
    override fun toString() =
        "ServerAiChatSubmission(requestId=$requestId, deadlineMs=$deadlineMs, bodyBytes=${value.size})"
}

data class ServerAiCapabilities(
    val revision: String,
    val inferenceEnabled: Boolean,
    val taskKinds: List<String>,
    val inputModalities: List<String>,
    val outputModalities: List<String>,
    val maxTextChars: Int,
    val maxTextBytes: Int,
    val maxResultChars: Int,
    val maxResultBytes: Int,
    val maxResponseBytes: Int,
    val maxDeadlineMs: Long
)

data class ServerAiJobResult(val text: String) {
    override fun toString() = "ServerAiJobResult(text=redacted, utf16Length=${text.length})"
}

data class ServerAiJobSnapshot(
    val jobId: String,
    val requestId: String,
    val kind: String,
    val state: ServerAiJobState,
    val createdMs: Long,
    val deadlineMs: Long,
    val startedMs: Long?,
    val finishedMs: Long?,
    val resultAvailable: Boolean,
    val result: ServerAiJobResult?,
    val resultExpiresMs: Long?
) {
    val terminal: Boolean get() = state.terminal

    override fun toString() =
        "ServerAiJobSnapshot(jobId=$jobId, requestId=$requestId, kind=$kind, state=$state, " +
            "createdMs=$createdMs, deadlineMs=$deadlineMs, startedMs=$startedMs, " +
            "finishedMs=$finishedMs, resultAvailable=$resultAvailable, " +
            "hasResult=${result != null}, resultExpiresMs=$resultExpiresMs)"
}

class ServerAiJobClient internal constructor(
    private val connection: ServerAiConnectionManager,
    private val uuidFactory: () -> UUID = UUID::randomUUID
) {
    private val requestJson = Json

    fun prepareChat(text: String, deadlineMs: Long): ServerAiChatSubmission = try {
        require(text.length in 1..MAX_TEXT_CHARS * 2)
        require(text.codePointCount(0, text.length) <= MAX_TEXT_CHARS)
        val inputBytes = strictUtf8(text)
        require(inputBytes.size <= MAX_TEXT_BYTES)
        val now = connection.currentServerTimeMs()
        val deadlineWindow = Math.subtractExact(deadlineMs, now)
        require(deadlineWindow in 1..MAX_DEADLINE_MS)
        val requestId = uuidFactory().toString()
        require(isCanonicalUuid(requestId))
        val body = requestJson.encodeToString(ChatRequest(text)).toByteArray(StandardCharsets.UTF_8)
        require(body.size <= MAX_BODY_BYTES)
        ServerAiChatSubmission(requestId, deadlineMs, body)
    } catch (failure: ServerAiJobFailure) {
        throw failure
    } catch (_: Exception) {
        throw ServerAiJobFailure(ServerAiJobError.INVALID_REQUEST)
    }

    suspend fun capabilities(): ServerAiCapabilities {
        val response = exchangeResponse("GET", CAPABILITIES_PATH, ByteArray(0), expectedStatus = 200)
        return decodeCapabilities(response.body)
    }

    suspend fun submit(submission: ServerAiChatSubmission): ServerAiJobSnapshot {
        val response = exchangeResponse(
            "POST",
            JOBS_PATH,
            submission.body,
            expectedStatus = 202,
            requestId = submission.requestId,
            deadlineMs = submission.deadlineMs
        )
        return decodeSnapshot(response.body).also { snapshot ->
            if (snapshot.requestId != submission.requestId || snapshot.deadlineMs != submission.deadlineMs) {
                throw ServerAiJobFailure(ServerAiJobError.INVALID_RESPONSE)
            }
        }
    }

    suspend fun status(jobId: String): ServerAiJobSnapshot = jobExchange("GET", jobId)

    suspend fun cancel(jobId: String): ServerAiJobSnapshot = jobExchange("DELETE", jobId)

    private suspend fun jobExchange(method: String, jobId: String): ServerAiJobSnapshot {
        if (!isCanonicalUuid(jobId)) throw ServerAiJobFailure(ServerAiJobError.INVALID_REQUEST)
        val response = exchangeResponse(method, "$JOBS_PATH/$jobId", ByteArray(0), expectedStatus = 200)
        return decodeSnapshot(response.body).also { snapshot ->
            if (snapshot.jobId != jobId) throw ServerAiJobFailure(ServerAiJobError.INVALID_RESPONSE)
        }
    }

    private suspend fun exchangeResponse(
        method: String,
        path: String,
        body: ByteArray,
        expectedStatus: Int,
        requestId: String? = null,
        deadlineMs: Long? = null
    ): ServerAiConnectionResponse {
        val response = try {
            connection.exchangeJob(method, path, body, requestId, deadlineMs)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: ServerAiConnectionFailure) {
            throw ServerAiJobFailure(
                when (failure.reason) {
                    ServerAiConnectionError.UNAUTHORIZED -> ServerAiJobError.UNAUTHORIZED
                    ServerAiConnectionError.NETWORK -> ServerAiJobError.NETWORK
                    else -> ServerAiJobError.INVALID_RESPONSE
                }
            )
        } catch (_: IOException) {
            throw ServerAiJobFailure(ServerAiJobError.NETWORK)
        } catch (_: Exception) {
            throw ServerAiJobFailure(ServerAiJobError.INVALID_RESPONSE)
        }
        if (response.statusCode != expectedStatus) throw statusFailure(response.statusCode)
        if (response.body.size > MAX_RESPONSE_BYTES) {
            throw ServerAiJobFailure(ServerAiJobError.INVALID_RESPONSE)
        }
        return response
    }

    private fun decodeCapabilities(body: ByteArray): ServerAiCapabilities = decode(body) { reader ->
        reader.beginObject()
        val seen = mutableSetOf<String>()
        var revision: String? = null
        var inferenceEnabled: Boolean? = null
        var taskKinds: List<String>? = null
        var inputModalities: List<String>? = null
        var outputModalities: List<String>? = null
        var maxTextChars: Long? = null
        var maxTextBytes: Long? = null
        var maxResultChars: Long? = null
        var maxResultBytes: Long? = null
        var maxResponseBytes: Long? = null
        var maxDeadlineMs: Long? = null
        while (reader.hasNext()) {
            when (uniqueName(reader, seen)) {
                "revision" -> revision = readString(reader)
                "inference_enabled" -> inferenceEnabled = readBoolean(reader)
                "task_kinds" -> taskKinds = readStringList(reader)
                "input_modalities" -> inputModalities = readStringList(reader)
                "output_modalities" -> outputModalities = readStringList(reader)
                "max_text_chars" -> maxTextChars = readUnsignedLong(reader)
                "max_text_bytes" -> maxTextBytes = readUnsignedLong(reader)
                "max_result_chars" -> maxResultChars = readUnsignedLong(reader)
                "max_result_bytes" -> maxResultBytes = readUnsignedLong(reader)
                "max_response_bytes" -> maxResponseBytes = readUnsignedLong(reader)
                "max_deadline_ms" -> maxDeadlineMs = readUnsignedLong(reader)
                else -> error("unexpected field")
            }
        }
        reader.endObject()
        require(seen == CAPABILITY_FIELDS)
        require(revision == EXPECTED_REVISION)
        require(taskKinds == listOf("CHAT"))
        require(inputModalities == listOf("TEXT"))
        require(outputModalities == listOf("TEXT"))
        require(maxTextChars == MAX_TEXT_CHARS.toLong())
        require(maxTextBytes == MAX_TEXT_BYTES.toLong())
        require(maxResultChars == MAX_RESULT_CHARS.toLong())
        require(maxResultBytes == MAX_RESULT_BYTES.toLong())
        require(maxResponseBytes == MAX_RESPONSE_BYTES.toLong())
        require(maxDeadlineMs == MAX_DEADLINE_MS)
        ServerAiCapabilities(
            revision ?: error("missing revision"),
            inferenceEnabled ?: error("missing readiness"),
            taskKinds,
            inputModalities,
            outputModalities,
            maxTextChars.toInt(),
            maxTextBytes.toInt(),
            maxResultChars.toInt(),
            maxResultBytes.toInt(),
            maxResponseBytes.toInt(),
            maxDeadlineMs
        )
    }

    private fun decodeSnapshot(body: ByteArray): ServerAiJobSnapshot = decode(body) { reader ->
        reader.beginObject()
        val seen = mutableSetOf<String>()
        var jobId: String? = null
        var requestId: String? = null
        var kind: String? = null
        var state: ServerAiJobState? = null
        var createdMs: Long? = null
        var deadlineMs: Long? = null
        var startedMs: Long? = null
        var finishedMs: Long? = null
        var resultAvailable: Boolean? = null
        var result: ServerAiJobResult? = null
        var resultExpiresMs: Long? = null
        while (reader.hasNext()) {
            when (uniqueName(reader, seen)) {
                "job_id" -> jobId = readString(reader)
                "request_id" -> requestId = readString(reader)
                "kind" -> kind = readString(reader)
                "state" -> state = ServerAiJobState.valueOf(readString(reader))
                "created_ms" -> createdMs = readUnsignedLong(reader)
                "deadline_ms" -> deadlineMs = readUnsignedLong(reader)
                "started_ms" -> startedMs = readNullableUnsignedLong(reader)
                "finished_ms" -> finishedMs = readNullableUnsignedLong(reader)
                "result_available" -> resultAvailable = readBoolean(reader)
                "result" -> result = readResult(reader)
                "result_expires_ms" -> resultExpiresMs = readUnsignedLong(reader)
                else -> error("unexpected field")
            }
        }
        reader.endObject()
        require(seen.containsAll(RECEIPT_FIELDS))
        require(seen.all { it in RECEIPT_FIELDS || it in RESULT_FIELDS })
        val snapshot = ServerAiJobSnapshot(
            jobId ?: error("missing job id"),
            requestId ?: error("missing request id"),
            kind ?: error("missing kind"),
            state ?: error("missing state"),
            createdMs ?: error("missing created"),
            deadlineMs ?: error("missing deadline"),
            startedMs,
            finishedMs,
            resultAvailable ?: error("missing result availability"),
            result,
            resultExpiresMs
        )
        validateSnapshot(snapshot)
        snapshot
    }

    private inline fun <T> decode(body: ByteArray, block: (JsonReader) -> T): T = try {
        require(body.size <= MAX_RESPONSE_BYTES)
        val reader = JsonReader(StringReader(strictUtf8(body))).apply { strictness = Strictness.STRICT }
        val value = block(reader)
        require(reader.peek() == JsonToken.END_DOCUMENT)
        value
    } catch (failure: ServerAiJobFailure) {
        throw failure
    } catch (_: Exception) {
        throw ServerAiJobFailure(ServerAiJobError.INVALID_RESPONSE)
    }

    private fun readResult(reader: JsonReader): ServerAiJobResult {
        reader.beginObject()
        val seen = mutableSetOf<String>()
        var text: String? = null
        while (reader.hasNext()) {
            when (uniqueName(reader, seen)) {
                "text" -> text = readString(reader)
                else -> error("unexpected result field")
            }
        }
        reader.endObject()
        require(seen == setOf("text"))
        val value = text ?: error("missing result text")
        val bytes = strictUtf8(value)
        require(value.isNotEmpty())
        require(value.codePointCount(0, value.length) <= MAX_RESULT_CHARS)
        require(bytes.size <= MAX_RESULT_BYTES)
        return ServerAiJobResult(value)
    }

    private fun validateSnapshot(value: ServerAiJobSnapshot) {
        require(isCanonicalUuid(value.jobId) && isCanonicalUuid(value.requestId))
        require(value.kind == "CHAT")
        require(value.createdMs > 0 && value.deadlineMs > value.createdMs)
        value.startedMs?.let { require(it in value.createdMs until value.deadlineMs) }
        value.finishedMs?.let { finished ->
            require(finished > 0)
            // Restart abandonment uses the current wall clock, not a monotonic timer.
            if (value.state != ServerAiJobState.UNKNOWN) {
                require(finished >= value.createdMs)
                value.startedMs?.let { require(finished >= it) }
            }
        }
        when (value.state) {
            ServerAiJobState.QUEUED -> require(value.startedMs == null && value.finishedMs == null)
            ServerAiJobState.RUNNING, ServerAiJobState.CANCEL_REQUESTED ->
                require(value.startedMs != null && value.finishedMs == null)
            ServerAiJobState.SUCCEEDED, ServerAiJobState.FAILED ->
                require(value.startedMs != null && value.finishedMs != null)
            ServerAiJobState.CANCELLED, ServerAiJobState.EXPIRED -> require(value.finishedMs != null)
            ServerAiJobState.UNKNOWN -> Unit
        }
        if (value.resultAvailable) {
            require(value.state == ServerAiJobState.SUCCEEDED)
            require(value.result != null && value.resultExpiresMs != null)
            // Publication TTL comes from a separate wall-clock read on the server.
            require(value.finishedMs != null && value.resultExpiresMs > 0)
        } else {
            require(value.result == null && value.resultExpiresMs == null)
        }
    }

    private fun uniqueName(reader: JsonReader, seen: MutableSet<String>): String {
        val name = reader.nextName()
        require(seen.add(name))
        return name
    }

    private fun readString(reader: JsonReader): String {
        require(reader.peek() == JsonToken.STRING)
        return reader.nextString()
    }

    private fun readBoolean(reader: JsonReader): Boolean {
        require(reader.peek() == JsonToken.BOOLEAN)
        return reader.nextBoolean()
    }

    private fun readStringList(reader: JsonReader): List<String> {
        require(reader.peek() == JsonToken.BEGIN_ARRAY)
        reader.beginArray()
        val values = mutableListOf<String>()
        while (reader.hasNext()) {
            require(values.size < 8)
            values += readString(reader)
        }
        reader.endArray()
        return values
    }

    private fun readNullableUnsignedLong(reader: JsonReader): Long? {
        if (reader.peek() == JsonToken.NULL) {
            reader.nextNull()
            return null
        }
        return readUnsignedLong(reader)
    }

    private fun readUnsignedLong(reader: JsonReader): Long {
        require(reader.peek() == JsonToken.NUMBER)
        val raw = reader.nextString()
        require(UNSIGNED_DECIMAL.matches(raw))
        return raw.toLong()
    }

    private fun statusFailure(statusCode: Int): ServerAiJobFailure = ServerAiJobFailure(
        when (statusCode) {
            400 -> ServerAiJobError.INVALID_REQUEST
            401 -> ServerAiJobError.UNAUTHORIZED
            403 -> ServerAiJobError.FORBIDDEN
            404 -> ServerAiJobError.NOT_FOUND
            405 -> ServerAiJobError.METHOD_NOT_ALLOWED
            409 -> ServerAiJobError.REQUEST_CONFLICT
            413 -> ServerAiJobError.REQUEST_TOO_LARGE
            415 -> ServerAiJobError.UNSUPPORTED_MEDIA
            429 -> ServerAiJobError.RATE_LIMITED
            431 -> ServerAiJobError.HEADERS_TOO_LARGE
            503 -> ServerAiJobError.SERVICE_UNAVAILABLE
            else -> ServerAiJobError.INVALID_RESPONSE
        }
    )

    private fun isCanonicalUuid(value: String): Boolean = runCatching {
        UUID.fromString(value).toString() == value
    }.getOrDefault(false)

    private fun strictUtf8(value: String): ByteArray {
        val buffer = StandardCharsets.UTF_8.newEncoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .encode(CharBuffer.wrap(value))
        return ByteArray(buffer.remaining()).also(buffer::get)
    }

    private fun strictUtf8(value: ByteArray): String = StandardCharsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(value))
        .toString()

    @Serializable
    private data class ChatRequest(val text: String)

    private companion object {
        const val CAPABILITIES_PATH = "/api/ai/v1/capabilities"
        const val JOBS_PATH = "/api/ai/v1/jobs"
        const val EXPECTED_REVISION = "server-codex-chat-r1a.1"
        const val MAX_TEXT_CHARS = 4_096
        const val MAX_TEXT_BYTES = 8_192
        const val MAX_BODY_BYTES = 16_384
        const val MAX_RESULT_CHARS = 8_192
        const val MAX_RESULT_BYTES = 16_384
        const val MAX_RESPONSE_BYTES = 65_536
        const val MAX_DEADLINE_MS = 900_000L
        val UNSIGNED_DECIMAL = Regex("0|[1-9][0-9]*")
        val CAPABILITY_FIELDS = setOf(
            "revision",
            "inference_enabled",
            "task_kinds",
            "input_modalities",
            "output_modalities",
            "max_text_chars",
            "max_text_bytes",
            "max_result_chars",
            "max_result_bytes",
            "max_response_bytes",
            "max_deadline_ms"
        )
        val RECEIPT_FIELDS = setOf(
            "job_id",
            "request_id",
            "kind",
            "state",
            "created_ms",
            "deadline_ms",
            "started_ms",
            "finished_ms",
            "result_available"
        )
        val RESULT_FIELDS = setOf("result", "result_expires_ms")
    }
}
