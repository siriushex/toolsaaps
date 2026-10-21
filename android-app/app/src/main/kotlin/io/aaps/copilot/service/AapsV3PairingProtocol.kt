package io.aaps.copilot.service

import io.aaps.copilot.data.repository.HistoryJson
import io.aaps.copilot.data.repository.LocalNightscoutV3Store
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*

internal class AapsV3PairingFailure : IllegalStateException("native_pairing_unavailable")

internal class AapsV3PairingCredential internal constructor(
    val clientId: String,
    val masterInstallId: String,
    val expiresAt: Long,
    secret: ByteArray
) : AutoCloseable {
    private val key = secret.copyOf().also { require(it.size == 32) }
    private var closed = false
    fun <T> withSecret(block: (ByteArray) -> T): T {
        check(!closed)
        val copy = key.copyOf()
        return try { block(copy) } finally { copy.fill(0) }
    }
    override fun close() { key.fill(0); closed = true }
    override fun toString() = "AapsV3PairingCredential(redacted)"
}

/** Native PIN offer format: PBKDF2-HMAC-SHA256/200000, AES256-GCM, separate 12-byte IV. */
internal object AapsV3PairingProtocol {
    private val clientId = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
    private val masterId = Regex("[A-Za-z0-9][A-Za-z0-9_.:-]{0,127}")
    private val secretHex = Regex("[0-9a-fA-F]{64}")
    private val fields = setOf("schemaVersion", "clientId", "expiresAt", "kdfSaltB64", "ivB64", "wrappedB64")

    fun unwrap(offer: JsonObject, pin: CharArray, nowMs: Long): AapsV3PairingCredential = try {
        require(pin.size == 8 && pin.all { it in '0'..'9' })
        require(offer.keys == fields && HistoryJson.long(offer.getValue("schemaVersion")) == 1L)
        val id = HistoryJson.string(offer.getValue("clientId"))
        val expiry = HistoryJson.long(offer.getValue("expiresAt"))
        require(clientId.matches(id) && nowMs in 1..LocalNightscoutV3Store.MAX_TIME)
        require(expiry in 1..LocalNightscoutV3Store.MAX_TIME && expiry - nowMs in 1..600_000)
        val salt = bytes(offer, "kdfSaltB64", 16..16)
        val iv = bytes(offer, "ivB64", 12..12)
        val wrapped = bytes(offer, "wrappedB64", 17..2048)
        val spec = PBEKeySpec(pin, salt, 200_000, 256)
        val key = try { SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded }
            finally { spec.clearPassword() }
        val plain = try { Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
            doFinal(wrapped)
        } } finally { key.fill(0) }
        try {
            val payload = HistoryJson.parse(plain, 2048)
            require(payload.keys == setOf("v", "masterInstallId", "clientId", "secretHex", "expiresAt"))
            require(HistoryJson.long(payload.getValue("v")) == 1L)
            require(HistoryJson.string(payload.getValue("clientId")) == id && HistoryJson.long(payload.getValue("expiresAt")) == expiry)
            val master = HistoryJson.string(payload.getValue("masterInstallId"))
            val hex = HistoryJson.string(payload.getValue("secretHex"))
            require(masterId.matches(master) && secretHex.matches(hex))
            val secret = ByteArray(32) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
            try { AapsV3PairingCredential(id, master, expiry, secret) } finally { secret.fill(0) }
        } finally { plain.fill(0) }
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { throw AapsV3PairingFailure() }
    finally { pin.fill('\u0000') }

    private fun bytes(body: JsonObject, field: String, size: IntRange): ByteArray {
        val text = HistoryJson.string(body.getValue(field))
        require(text.length <= 2800)
        return Base64.getDecoder().decode(text).also {
            require(it.size in size && Base64.getEncoder().encodeToString(it) == text)
        }
    }
}

/** Only enrollment and non-mutating liveness. No generic message-signing entry point. */
internal class AapsV3SessionProtocol(secret: ByteArray) : AutoCloseable {
    private val key = secret.copyOf().also { require(it.size == 32) }
    private var closed = false
    fun hello(clientId: String, counter: Long, at: Long, expiresAt: Long): JsonObject {
        require(counter == 1L && expiresAt - at in 1..600_000)
        return envelope(clientId, counter, at, expiresAt, false, "hello", """{"type":"hello","protocolVersion":1}""")
    }
    fun ping(clientId: String, counter: Long, at: Long): JsonObject {
        require(counter > 1L && at <= LocalNightscoutV3Store.MAX_TIME - 8000)
        return envelope(clientId, counter, at, at + 8000, true, "ping", """{"type":"ping"}""")
    }
    fun isPong(request: JsonObject, ack: JsonObject, nowMs: Long): Boolean {
        check(!closed)
        return try {
            val id = HistoryJson.string(request.getValue("clientId"))
            val counter = HistoryJson.long(request.getValue("counter"))
            val at = HistoryJson.long(request.getValue("timestamp"))
            val expected = ping(id, counter, at)
            require(request == expected && nowMs in 1..LocalNightscoutV3Store.MAX_TIME)
            require(ack.keys.containsAll(setOf("clientId", "commandCounter", "phase", "status", "timestamp", "signature")))
            require(ack.keys.all { it in setOf("clientId", "commandCounter", "phase", "status", "timestamp", "signature", "reason", "payload") })
            require(HistoryJson.string(ack.getValue("clientId")) == id && HistoryJson.long(ack.getValue("commandCounter")) == counter)
            require(ack["phase"] == JsonPrimitive("Done") && ack["status"] == JsonPrimitive("Ok"))
            require(ack["reason"].let { it == null || it == JsonNull } && ack["payload"].let { it == null || it == JsonNull })
            val timestamp = HistoryJson.long(ack.getValue("timestamp"))
            require(timestamp in (at - 5000)..minOf(at + 13_000, nowMs + 5000))
            val signature = HistoryJson.string(ack.getValue("signature"))
            require(Regex("[0-9a-f]{64}").matches(signature))
            MessageDigest.isEqual(sign("$id|$counter|Done|Ok|||$timestamp").toByteArray(), signature.toByteArray())
        } catch (_: RuntimeException) { false }
    }
    private fun envelope(id: String, counter: Long, at: Long, until: Long, ack: Boolean, type: String, raw: String): JsonObject {
        check(!closed)
        require(Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}").matches(id))
        require(at in 1..LocalNightscoutV3Store.MAX_TIME && until in at..LocalNightscoutV3Store.MAX_TIME)
        return buildJsonObject {
            put("clientId", id); put("counter", counter); put("timestamp", at); put("validUntil", until)
            put("wantsAck", ack); put("type", type); put("payload", raw)
            put("signature", sign("$id|$counter|$at|$until|$ack|$type|$raw"))
        }
    }
    private fun sign(raw: String) = Mac.getInstance("HmacSHA256").run {
        init(SecretKeySpec(key, "HmacSHA256"))
        doFinal(raw.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 255) }
    }
    override fun close() { key.fill(0); closed = true }
    override fun toString() = "AapsV3SessionProtocol(redacted)"
}
