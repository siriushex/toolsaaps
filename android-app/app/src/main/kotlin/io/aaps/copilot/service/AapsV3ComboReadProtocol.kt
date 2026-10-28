package io.aaps.copilot.service

import io.aaps.copilot.data.repository.HistoryJson
import io.aaps.copilot.data.repository.LocalNightscoutV3Store
import io.aaps.copilot.data.repository.LocalV3Write
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

@Serializable
internal sealed class AapsV3ComboReadSpec {
    @Serializable @SerialName("inventory")
    data class Inventory(val snapshotId: String? = null, val afterReadId: String? = null,
                         val limit: Int = 32) : AapsV3ComboReadSpec() {
        fun requireValid() {
            require(limit in 1..32)
            require((snapshotId == null) == (afterReadId == null))
            snapshotId?.let(HistoryJson::uuid); afterReadId?.let(HistoryJson::uuid)
        }
        override fun toString() = "ComboInventoryRequest(redacted)"
    }
    @Serializable @SerialName("chunk")
    data class Chunk(val snapshotId: String, val readId: String, val pumpBinding: String,
                     val sha256: String, val totalBytes: Int, val offset: Int) : AapsV3ComboReadSpec() {
        override fun toString() = "ComboChunkRequest(redacted)"
    }
}

@Serializable internal enum class AapsV3ComboReadPhase { ISSUED, RECEIVED, REJECTED, SUPERSEDED, EXPIRED, REVOKED }
internal class AapsV3ComboReadSnapshot(val requestId: String, val counter: Long, val phase: AapsV3ComboReadPhase,
                                      val publication: LocalV3Write? = null) {
    override fun toString() = "ComboReadSnapshot($phase)"
}

/** Candidate read-only extension. The stock peer does not support this message type. */
internal class AapsV3ComboReadProtocol(secret: ByteArray) : AutoCloseable {
    private val key = secret.also { require(it.size == 32) }.copyOf()
    private var closed = false

    fun request(clientId: String, masterInstallId: String, requestId: String, counter: Long,
                issuedAt: Long, spec: AapsV3ComboReadSpec): AapsV3TargetRequest {
        check(!closed)
        listOf(clientId, masterInstallId, requestId).forEach(HistoryJson::uuid)
        require(clientId != masterInstallId && counter > 1)
        require(issuedAt in 1..LocalNightscoutV3Store.MAX_TIME - TTL_MS)
        val until = issuedAt + TTL_MS
        val query = when (spec) {
            is AapsV3ComboReadSpec.Inventory -> {
                spec.requireValid()
                buildJsonObject {
                    put("kind", "inventory"); put("limit", spec.limit)
                    spec.snapshotId?.let { put("snapshotId", it) }
                    spec.afterReadId?.let { put("afterReadId", it) }
                }
            }
            is AapsV3ComboReadSpec.Chunk -> {
                val binding = AapsV3ComboChunkBinding(clientId, masterInstallId, requestId,
                    spec.snapshotId, spec.readId, spec.pumpBinding, spec.sha256, counter, issuedAt,
                    until, spec.totalBytes, spec.offset)
                buildJsonObject {
                    put("kind", "chunk"); put("snapshotId", spec.snapshotId); put("readId", spec.readId)
                    put("pumpBinding", spec.pumpBinding); put("sha256", spec.sha256)
                    put("totalBytes", spec.totalBytes); put("offset", spec.offset); put("length", binding.chunkBytes)
                }
            }
        }
        val raw = buildJsonObject {
            put("type", TYPE); put("version", 1); put("masterInstallId", masterInstallId)
            put("requestId", requestId); put("query", query)
        }.toString()
        val signature = Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(key, "HmacSHA256"))
            doFinal("$clientId|$counter|$issuedAt|$until|true|$TYPE|$raw".toByteArray())
                .joinToString("") { "%02x".format(it.toInt() and 255) }
        }
        return AapsV3TargetRequest(buildJsonObject {
            put("clientId", clientId); put("counter", counter); put("timestamp", issuedAt)
            put("validUntil", until); put("wantsAck", true); put("type", TYPE)
            put("payload", raw); put("signature", signature)
        })
    }

    /** Validate against the locally committed request, never response-supplied scope. */
    fun response(ack: JsonObject, clientId: String, masterInstallId: String, requestId: String,
                 counter: Long, issuedAt: Long, spec: AapsV3ComboReadSpec, now: Long): AapsV3ComboReadPhase {
        request(clientId, masterInstallId, requestId, counter, issuedAt, spec)
        require(now in issuedAt until issuedAt + TTL_MS)
        val required = setOf("clientId", "commandCounter", "phase", "status", "timestamp", "signature")
        require(ack.keys.containsAll(required) && ack.keys.all { it in required || it == "reason" || it == "payload" })
        val encoded = HistoryJson.bytes(ack)
        try { require(encoded.size <= 60 * 1024) } finally { encoded.fill(0) }
        fun text(name: String) = HistoryJson.string(ack.getValue(name))
        fun optional(name: String) = ack[name]?.takeUnless { it == JsonNull }?.let(HistoryJson::string)
        require(text("clientId") == clientId && HistoryJson.long(ack.getValue("commandCounter")) == counter)
        val phase = text("phase"); val status = text("status")
        val timestamp = HistoryJson.long(ack.getValue("timestamp"))
        require(phase == "Done" && status in setOf("Ok", "Failed", "Expired"))
        require(timestamp in maxOf(1, issuedAt - 5000)..minOf(now + 5000, issuedAt + TTL_MS - 1))
        val reason = optional("reason"); val payload = optional("payload")
        require(reason == null || reason.length <= 256)
        require(payload == null || payload.length <= AapsV3ComboChunkEnvelope.MAX_ENVELOPE_BYTES)
        val signature = text("signature")
        require(signature.matches(Regex("[0-9a-f]{64}")))
        val expected = Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(key, "HmacSHA256"))
            doFinal("$clientId|$counter|$phase|$status|${reason.orEmpty()}|${payload.orEmpty()}|$timestamp".toByteArray())
        }
        val supplied = ByteArray(32) { signature.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        try { require(MessageDigest.isEqual(expected, supplied)) }
        finally { expected.fill(0); supplied.fill(0) }
        if (status != "Ok") {
            require(payload == null)
            return if (status == "Expired") AapsV3ComboReadPhase.EXPIRED else AapsV3ComboReadPhase.REJECTED
        }
        require(reason == null && payload != null)
        val wire = HistoryJson.encodeUtf8(payload)
        try {
            when (spec) {
                is AapsV3ComboReadSpec.Inventory -> AapsV3ComboInventoryEnvelope(key).use {
                    it.open(AapsV3ComboInventoryBinding(clientId, masterInstallId, requestId, counter,
                        issuedAt, issuedAt + TTL_MS, spec), wire, now)
                }
                is AapsV3ComboReadSpec.Chunk -> AapsV3ComboChunkEnvelope(key).use {
                    it.open(AapsV3ComboChunkBinding(clientId, masterInstallId, requestId, spec.snapshotId,
                        spec.readId, spec.pumpBinding, spec.sha256, counter, issuedAt, issuedAt + TTL_MS,
                        spec.totalBytes, spec.offset), wire, now).fill(0)
                }
            }
        } finally { wire.fill(0) }
        return AapsV3ComboReadPhase.RECEIVED
    }
    override fun close() { key.fill(0); closed = true }
    override fun toString() = "ComboReadProtocol(redacted)"
    companion object {
        const val TYPE = "combo_history_read_v1"
        const val TTL_MS = 30_000L
    }
}
