package io.aaps.copilot.data.repository

import java.util.Base64
import kotlinx.serialization.json.*

internal class MealPhotoSubmission(val requestId: String, val deadlineMs: Long, body: ByteArray) {
    private val frozen = body.copyOf()
    val body: ByteArray get() = frozen.copyOf()
    override fun toString() = "MealPhotoSubmission(requestId=$requestId, bodyBytes=${frozen.size})"
}

internal data class MealPhotoJobSnapshot(
    val jobId: String, val requestId: String, val state: ServerAiJobState,
    val createdMs: Long, val deadlineMs: Long, val startedMs: Long?, val finishedMs: Long?,
    val resultAvailable: Boolean, val estimateJson: String?, val resultExpiresMs: Long?
) {
    override fun toString() = "MealPhotoJobSnapshot(jobId=$jobId, state=$state, result=redacted)"
}

internal object ServerMealPhotoProtocol {
    const val REVISION = "server-codex-meal-photo-r1.1"
    const val JOBS_PATH = "/api/ai/v1/meal-photo/jobs"
    const val RESPONSE_LIMIT = 65_536
    const val MAX_DEADLINE_MS = 60_000L
    private val receiptFields = setOf("job_id", "request_id", "kind", "state", "created_ms",
        "deadline_ms", "started_ms", "finished_ms", "result_available")

    fun prepare(request: MealPhotoGatewayRequest, now: Long, deadline: Long): MealPhotoSubmission = requestValue {
        val bytes = request.bytes
        check(now > 0 && Math.subtractExact(deadline, now) in 1..MAX_DEADLINE_MS)
        check(bytes.size in 5..1_048_576 && bytes[0] == (-1).toByte() && bytes[1] == (-40).toByte() &&
            bytes[2] == (-1).toByte() && bytes[bytes.lastIndex - 1] == (-1).toByte() && bytes.last() == (-39).toByte())
        HistoryJson.uuid(request.requestId)
        check(request.mimeType == "image/jpeg")
        val body = HistoryJson.bytes(buildJsonObject {
            put("schemaVersion", 1); put("mimeType", "image/jpeg")
            put("imageBase64", Base64.getEncoder().encodeToString(bytes))
        })
        check(body.size <= 3 * 1024 * 1024)
        MealPhotoSubmission(request.requestId, deadline, body)
    }

    fun ready(body: ByteArray): Boolean = responseValue {
        val route = ServerTaskCapabilities.select(body, "MEAL_PHOTO") ?: return@responseValue false
        val fields = setOf("revision", "inference_enabled", "task_kinds", "input_modalities", "output_modalities",
            "max_result_chars", "max_result_bytes", "max_response_bytes", "max_deadline_ms",
            "max_request_body_bytes", "max_image_bytes", "max_ingredients")
        HistoryJson.fields(route, fields)
        check(HistoryJson.string(route.getValue("revision")) == REVISION)
        check(strings(route.getValue("task_kinds")) == listOf("MEAL_PHOTO"))
        check(strings(route.getValue("input_modalities")) == listOf("IMAGE"))
        check(strings(route.getValue("output_modalities")) == listOf("MEAL_ESTIMATE"))
        mapOf("max_result_chars" to 65_536L, "max_result_bytes" to 65_536L,
            "max_response_bytes" to 65_536L, "max_deadline_ms" to MAX_DEADLINE_MS,
            "max_request_body_bytes" to 3L * 1024 * 1024, "max_image_bytes" to 1_048_576L,
            "max_ingredients" to 20L).forEach { (name, limit) ->
                check(HistoryJson.long(route.getValue(name)) == limit)
            }
        HistoryJson.bool(route.getValue("inference_enabled"))
    }

    fun snapshot(body: ByteArray, submission: MealPhotoSubmission, expectedJobId: String?): MealPhotoJobSnapshot = responseValue {
        val value = HistoryJson.parse(body, RESPONSE_LIMIT)
        HistoryJson.fields(value, receiptFields, setOf("result", "result_expires_ms"))
        fun number(name: String) = HistoryJson.long(value.getValue(name))
        fun nullable(name: String) = value.getValue(name).takeUnless { it == JsonNull }?.let(HistoryJson::long)
        val jobId = HistoryJson.string(value.getValue("job_id")).also(HistoryJson::uuid)
        val requestId = HistoryJson.string(value.getValue("request_id")).also(HistoryJson::uuid)
        check(expectedJobId == null || jobId == expectedJobId)
        check(requestId == submission.requestId && number("deadline_ms") == submission.deadlineMs)
        check(HistoryJson.string(value.getValue("kind")) == "MEAL_PHOTO")
        val state = ServerAiJobState.valueOf(HistoryJson.string(value.getValue("state")))
        val created = number("created_ms")
        val started = nullable("started_ms")
        val finished = nullable("finished_ms")
        val available = HistoryJson.bool(value.getValue("result_available"))
        check(created > 0 && submission.deadlineMs > created && submission.deadlineMs - created <= MAX_DEADLINE_MS)
        started?.let { check(it in created until submission.deadlineMs) }
        finished?.let { check(it > 0 && (state == ServerAiJobState.UNKNOWN || it >= (started ?: created))) }
        when (state) {
            ServerAiJobState.QUEUED -> check(started == null && finished == null)
            ServerAiJobState.RUNNING, ServerAiJobState.CANCEL_REQUESTED -> check(started != null && finished == null)
            ServerAiJobState.SUCCEEDED, ServerAiJobState.FAILED -> check(started != null && finished != null)
            ServerAiJobState.CANCELLED, ServerAiJobState.EXPIRED, ServerAiJobState.UNKNOWN -> check(finished != null)
        }
        val result = value["result"]?.let { HistoryJson.obj(it) }
        val expires = value["result_expires_ms"]?.let(HistoryJson::long)
        if (available) {
            check(state == ServerAiJobState.SUCCEEDED && result != null && expires != null && finished != null)
            check(expires >= finished && expires - finished <= 900_000)
        } else check(result == null && expires == null)
        val json = result?.let { HistoryJson.decodeUtf8(HistoryJson.bytes(it)) }
        if (json != null) check(MealPhotoEstimateParser.parse(json) is MealPhotoParseResult.Valid)
        MealPhotoJobSnapshot(jobId, requestId, state, created, submission.deadlineMs, started, finished, available, json, expires)
    }

    private fun strings(value: JsonElement) = HistoryJson.array(value).map(HistoryJson::string)
    private inline fun <T> responseValue(block: () -> T): T = try { block() } catch (failure: ServerAiJobFailure) {
        throw failure
    } catch (_: Exception) { throw ServerAiJobFailure(ServerAiJobError.INVALID_RESPONSE) }
    private inline fun <T> requestValue(block: () -> T): T = try { block() } catch (_: Exception) {
        throw ServerAiJobFailure(ServerAiJobError.INVALID_REQUEST)
    }
}
