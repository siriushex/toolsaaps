package io.aaps.copilot.service

import io.aaps.copilot.data.repository.HistoryJson
import io.aaps.copilot.data.repository.LocalNightscoutV3Store
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlinx.serialization.json.*

internal data class AapsV3TargetSpec(
    val lowMgdl: Double,
    val highMgdl: Double,
    val durationMinutes: Int,
    val reason: String,
    val notes: String = ""
)

internal class AapsV3TargetRequest internal constructor(val json: JsonObject) {
    override fun toString() = "AapsV3TargetRequest(redacted)"
}

internal enum class AapsV3TargetAck { UNCONFIRMED, EXECUTING, PREVIEW, APPLIED, REJECTED, EXPIRED }

/** Native target-only wire codec. Counter allocation, durable ownership and dispatch are separate. */
internal class AapsV3TargetProtocol(secret: ByteArray) : AutoCloseable {
    private val key = secret.copyOf().also { require(it.size == 32) }
    private var closed = false

    fun prepare(clientId: String, counter: Long, issuedAtMs: Long, target: AapsV3TargetSpec): AapsV3TargetRequest {
        validateTarget(target)
        val action = buildJsonObject {
            put("type", "temp_target"); put("reason", target.reason)
            put("lowMgdl", target.lowMgdl); put("highMgdl", target.highMgdl)
            put("durationMinutes", target.durationMinutes); put("startOffsetMinutes", 0)
            if (target.notes.isNotEmpty()) put("notes", target.notes)
        }
        return signed(clientId, counter, issuedAtMs, "batch_prepare", buildJsonObject {
            put("type", "batch_prepare"); put("actions", JsonArray(listOf(action)))
        })
    }

    fun commit(prepared: AapsV3TargetRequest, response: JsonObject, counter: Long, nowMs: Long): AapsV3TargetRequest {
        check(!closed)
        val request = ownRequest(prepared)
        require(string(request, "type") == "batch_prepare")
        require(nowMs in number(request, "timestamp")..number(request, "validUntil"))
        require(counter > number(request, "counter"))
        require(classify(prepared, response, nowMs) == AapsV3TargetAck.PREVIEW)
        val preview = preview(string(response, "payload"))
        return signed(string(request, "clientId"), counter, nowMs, "bolus_commit", buildJsonObject {
            put("type", "bolus_commit"); put("bolusId", number(preview, "bolusId"))
            // RC1 shares this commit type with target-only batches; no dose or advisor is requested.
            put("asAdvisor", false); put("correctionU", 0.0)
        })
    }

    fun classify(request: AapsV3TargetRequest, response: JsonObject, nowMs: Long): AapsV3TargetAck {
        check(!closed)
        return try {
            val original = ownRequest(request)
            require(nowMs in 1..LocalNightscoutV3Store.MAX_TIME)
            require(response.keys.all { it in ACK_FIELDS } && response.keys.containsAll(REQUIRED_ACK_FIELDS))
            val clientId = string(response, "clientId")
            val counter = number(response, "commandCounter")
            require(clientId == string(original, "clientId") && counter == number(original, "counter"))
            val phase = string(response, "phase")
            val status = string(response, "status")
            val reason = optionalString(response, "reason")
            val payload = optionalString(response, "payload")
            require(reason == null || reason.length <= 256)
            require(payload == null || payload.length <= MAX_PREVIEW_BYTES)
            val timestamp = number(response, "timestamp")
            require(timestamp >= number(original, "timestamp") - ACK_CLOCK_TOLERANCE_MS)
            require(timestamp <= minOf(LocalNightscoutV3Store.MAX_TIME, nowMs + ACK_CLOCK_TOLERANCE_MS))
            require(verify("$clientId|$counter|$phase|$status|${reason ?: ""}|${payload ?: ""}|$timestamp",
                string(response, "signature")))
            when {
                phase == "Done" && status == "Failed" -> AapsV3TargetAck.REJECTED
                phase == "Done" && status == "Expired" -> AapsV3TargetAck.EXPIRED
                else -> {
                    require(timestamp <= number(original, "validUntil") + ACK_CLOCK_TOLERANCE_MS)
                    when {
                        phase == "Executing" && status == "Pending" && reason == null && payload == null ->
                            AapsV3TargetAck.EXECUTING
                        phase == "Done" && status == "Ok" && reason == null -> {
                            if (string(original, "type") == "batch_prepare") {
                                preview(requireNotNull(payload))
                                AapsV3TargetAck.PREVIEW
                            } else {
                                require(payload == null)
                                AapsV3TargetAck.APPLIED
                            }
                        }
                        else -> AapsV3TargetAck.UNCONFIRMED
                    }
                }
            }
        } catch (_: RuntimeException) {
            AapsV3TargetAck.UNCONFIRMED
        }
    }

    private fun signed(clientId: String, counter: Long, at: Long, type: String, payload: JsonObject): AapsV3TargetRequest {
        check(!closed)
        require(CLIENT_ID.matches(clientId) && counter > 0)
        require(at in 1..LocalNightscoutV3Store.MAX_TIME - TTL_MS)
        val until = at + TTL_MS
        val raw = payload.toString()
        val signature = sign("$clientId|$counter|$at|$until|true|$type|$raw")
        return AapsV3TargetRequest(buildJsonObject {
            put("clientId", clientId); put("counter", counter); put("timestamp", at)
            put("type", type); put("payload", raw); put("signature", signature)
            put("validUntil", until); put("wantsAck", true)
        })
    }

    private fun ownRequest(request: AapsV3TargetRequest): JsonObject {
        val body = request.json
        require(body.keys == ENVELOPE_FIELDS)
        val client = string(body, "clientId")
        val counter = number(body, "counter")
        val timestamp = number(body, "timestamp")
        val until = number(body, "validUntil")
        val type = string(body, "type")
        val raw = string(body, "payload")
        require(CLIENT_ID.matches(client) && counter > 0 && timestamp in 1..LocalNightscoutV3Store.MAX_TIME - TTL_MS)
        require(until == timestamp + TTL_MS && HistoryJson.bool(body.getValue("wantsAck")))
        require(verify("$client|$counter|$timestamp|$until|true|$type|$raw", string(body, "signature")))
        val payload = parse(raw)
        require(string(payload, "type") == type)
        when (type) {
            "batch_prepare" -> {
                require(payload.keys == setOf("type", "actions"))
                val actions = HistoryJson.array(payload.getValue("actions"))
                require(actions.size == 1)
                val action = HistoryJson.obj(actions.single())
                require(action.keys == TARGET_FIELDS || action.keys == TARGET_FIELDS + "notes")
                require(string(action, "type") == "temp_target")
                require(number(action, "startOffsetMinutes") == 0L)
                val duration = number(action, "durationMinutes")
                require(duration in 0..1440)
                validateTarget(AapsV3TargetSpec(HistoryJson.double(action.getValue("lowMgdl")),
                    HistoryJson.double(action.getValue("highMgdl")), duration.toInt(), string(action, "reason"),
                    action["notes"]?.let(HistoryJson::string) ?: ""))
            }
            "bolus_commit" -> {
                require(payload.keys == setOf("type", "bolusId", "asAdvisor", "correctionU"))
                require(number(payload, "bolusId") > 0 && !HistoryJson.bool(payload.getValue("asAdvisor")))
                require(HistoryJson.double(payload.getValue("correctionU")) == 0.0)
            }
            else -> throw IllegalArgumentException("unsupported_target_message")
        }
        return body
    }

    private fun validateTarget(target: AapsV3TargetSpec) {
        require(target.reason in REASONS && target.durationMinutes in 0..1440)
        require(target.notes.length <= 512 && target.notes.none { it.isISOControl() })
        require(target.lowMgdl.isFinite() && target.highMgdl.isFinite())
        if (target.durationMinutes == 0) {
            require(target.lowMgdl == 0.0 && target.highMgdl == 0.0)
        } else {
            require(target.lowMgdl > 0 && target.lowMgdl <= target.highMgdl && target.highMgdl <= 1000)
        }
    }

    private fun preview(raw: String): JsonObject {
        val body = parse(raw)
        require(body.keys.all { it in PREVIEW_FIELDS } && number(body, "bolusId") > 0)
        require(body["advisorApplies"]?.let(HistoryJson::bool) != true)
        require(body["advisorLines"]?.let(HistoryJson::array).orEmpty().isEmpty())
        require(body["wizardDetail"] == null || body["wizardDetail"] == JsonNull)
        val lines = HistoryJson.array(body.getValue("lines"))
        require(lines.size in 1..32)
        lines.forEach {
            val line = HistoryJson.obj(it)
            require(line.keys == setOf("role", "text"))
            require(string(line, "role").length in 1..64 && string(line, "text").length in 1..1024)
        }
        return body
    }

    private fun parse(raw: String): JsonObject {
        require(raw.length <= MAX_PREVIEW_BYTES)
        return try { HistoryJson.parse(HistoryJson.encodeUtf8(raw), MAX_PREVIEW_BYTES) }
        catch (_: Exception) { throw IllegalArgumentException("invalid_target_wire_json") }
    }
    private fun string(body: JsonObject, name: String): String =
        body[name]?.let(HistoryJson::string) ?: throw IllegalArgumentException("missing_target_field")
    private fun number(body: JsonObject, name: String): Long =
        body[name]?.let(HistoryJson::long) ?: throw IllegalArgumentException("missing_target_field")
    private fun optionalString(body: JsonObject, name: String): String? =
        body[name]?.takeUnless { it == JsonNull }?.let(HistoryJson::string)
    private fun sign(canonical: String): String = Mac.getInstance("HmacSHA256").run {
        init(SecretKeySpec(key, "HmacSHA256"))
        doFinal(canonical.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 255) }
    }
    private fun verify(canonical: String, signature: String): Boolean = HEX.matches(signature) &&
        MessageDigest.isEqual(sign(canonical).toByteArray(Charsets.US_ASCII), signature.toByteArray(Charsets.US_ASCII))

    override fun close() { key.fill(0); closed = true }

    companion object {
        const val TTL_MS = 8000L
        private const val ACK_CLOCK_TOLERANCE_MS = 5000L
        private const val MAX_PREVIEW_BYTES = 16 * 1024
        private val CLIENT_ID = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
        private val HEX = Regex("[0-9a-f]{64}")
        private val REASONS = setOf("Custom", "Hypo", "Activity", "Eating Soon", "Automation")
        private val ENVELOPE_FIELDS = setOf("clientId", "counter", "timestamp", "validUntil", "type", "payload", "signature", "wantsAck")
        private val REQUIRED_ACK_FIELDS = setOf("clientId", "commandCounter", "phase", "status", "timestamp", "signature")
        private val ACK_FIELDS = REQUIRED_ACK_FIELDS + setOf("reason", "payload")
        private val TARGET_FIELDS = setOf("type", "reason", "lowMgdl", "highMgdl", "durationMinutes", "startOffsetMinutes")
        private val PREVIEW_FIELDS = setOf("bolusId", "lines", "advisorApplies", "advisorLines", "wizardDetail")
    }
}
