package io.aaps.copilot.service

import io.aaps.copilot.data.repository.HistoryJson
import java.math.BigInteger
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PublicKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import javax.crypto.spec.GCMParameterSpec
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject

/** Verify the master's reply before opening its optional token. Only a local issuer can attest a token. */
internal object AapsFinalKeyExchange {
    private val curve = ECGenParameterSpec("secp256r1")
    private val hex = Regex("[0-9a-f]{64}")
    private val pointLength = 65

    class Pending(val publicPoint: String, val privateDer: ByteArray) : AutoCloseable {
        override fun close() { privateDer.fill(0) }
        override fun toString() = "PendingKeyExchange(redacted)"
    }

    fun begin(): Pending {
        val pair = KeyPairGenerator.getInstance("EC").apply { initialize(curve) }.generateKeyPair()
        val point = (pair.public as ECPublicKey).w
        return Pending(Base64.getEncoder().encodeToString(byteArrayOf(4) +
            coordinate(point.affineX) + coordinate(point.affineY)), pair.private.encoded)
    }

    fun finish(answer: JsonObject, clientId: String, clientPoint: String, privateDer: ByteArray,
               oldSecret: ByteArray, startedAt: Long, expiresAt: Long, now: Long,
               acceptsIssuedToken: (ByteArray) -> Boolean = { false }): ByteArray? = try {
        require(oldSecret.size == 32 && privateDer.size in 1..512)
        require(now in startedAt until expiresAt)
        HistoryJson.fields(answer, setOf("clientId", "clientPublicKey", "masterPublicKey", "timestamp", "signature"),
            setOf("tokenIv", "token"))
        require(HistoryJson.string(answer.getValue("clientId")) == clientId)
        require(HistoryJson.string(answer.getValue("clientPublicKey")) == clientPoint)
        val master = HistoryJson.string(answer.getValue("masterPublicKey"))
        require(master != clientPoint)
        val ivText = answer["tokenIv"]?.takeUnless { it == JsonNull }?.let(HistoryJson::string)
        val sealedText = answer["token"]?.takeUnless { it == JsonNull }?.let(HistoryJson::string)
        require((ivText == null) == (sealedText == null))
        require(ivText == null || ivText.length == 16)
        require(sealedText == null || sealedText.length in 24..364)
        val at = HistoryJson.long(answer.getValue("timestamp"))
        require(at in maxOf(1, startedAt - 5_000)..minOf(now + 5_000, expiresAt - 1))
        val signature = HistoryJson.string(answer.getValue("signature"))
        require(hex.matches(signature))
        val expected = mac(oldSecret, "$clientId|$clientPoint|$master|${ivText.orEmpty()}|${sealedText.orEmpty()}|$at".encodeToByteArray())
        val valid = try {
            MessageDigest.isEqual(expected, ByteArray(32) { signature.substring(it * 2, it * 2 + 2).toInt(16).toByte() })
        } finally { expected.fill(0) }
        require(valid)
        val secret = KeyAgreement.getInstance("ECDH").run {
            init(KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(privateDer)))
            doPhase(decodePoint(master), true)
            generateSecret()
        }
        try {
            val extracted = mac(oldSecret, secret)
            try {
                val info = "aaps-clientcontrol-key-v1|$clientId|$clientPoint|$master".encodeToByteArray()
                val rotated = mac(extracted, info + byteArrayOf(1))
                try {
                    if (ivText != null && sealedText != null) {
                        val iv = canonicalBase64(ivText, 12..12)
                        val ciphertext = canonicalBase64(sealedText, 17..272)
                        val expand = rotated + info + byteArrayOf(2)
                        val tokenKey = try { mac(extracted, expand) } finally { expand.fill(0) }
                        val token = try { Cipher.getInstance("AES/GCM/NoPadding").run {
                            init(Cipher.DECRYPT_MODE, SecretKeySpec(tokenKey, "AES"), GCMParameterSpec(128, iv))
                            doFinal(ciphertext)
                        } } finally { tokenKey.fill(0) }
                        try {
                            require(token.size in 1..256 && token.all { it.toInt() in 33..126 })
                            require(acceptsIssuedToken(token))
                        } finally { token.fill(0) }
                    }
                    rotated.copyOf()
                } finally { rotated.fill(0) }
            } finally { extracted.fill(0) }
        } finally { secret.fill(0) }
    } catch (cancelled: kotlinx.coroutines.CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }

    private fun canonicalBase64(text: String, size: IntRange): ByteArray = Base64.getDecoder().decode(text).also {
        require(it.size in size && Base64.getEncoder().encodeToString(it) == text)
    }

    fun decodePoint(encoded: String): PublicKey {
        require(encoded.length == 88)
        val raw = Base64.getDecoder().decode(encoded)
        require(raw.size == pointLength && raw.first() == 4.toByte() && Base64.getEncoder().encodeToString(raw) == encoded)
        val params = AlgorithmParameters.getInstance("EC").apply { init(curve) }
            .getParameterSpec(ECParameterSpec::class.java)
        val point = ECPoint(BigInteger(1, raw.copyOfRange(1, 33)), BigInteger(1, raw.copyOfRange(33, 65)))
        return KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(point, params))
    }

    private fun coordinate(value: BigInteger): ByteArray {
        val raw = value.toByteArray()
        val from = maxOf(0, raw.size - 32)
        return ByteArray(32).also { raw.copyInto(it, 32 - (raw.size - from), from) }
    }

    private fun mac(key: ByteArray, value: ByteArray): ByteArray = Mac.getInstance("HmacSHA256").run {
        init(SecretKeySpec(key, "HmacSHA256")); doFinal(value)
    }
}
