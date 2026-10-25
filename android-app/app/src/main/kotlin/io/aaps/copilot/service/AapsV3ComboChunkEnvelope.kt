package io.aaps.copilot.service

import io.aaps.copilot.data.repository.HistoryJson
import java.io.Closeable
import java.io.IOException
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal class AapsV3ComboChunkFailure : IOException("combo_chunk_unavailable")

/** Expected local request, not authorization. The paired owner must authenticate the manifest. */
internal data class AapsV3ComboChunkBinding(
    val clientId: String, val masterInstallId: String, val requestId: String,
    val snapshotId: String, val readId: String, val pumpBinding: String, val sha256: String,
    val counter: Long, val issuedAt: Long, val validUntil: Long, val totalBytes: Int, val offset: Int
) {
    init {
        chunkOperation {
            listOf(clientId, masterInstallId, requestId, snapshotId, readId).forEach(HistoryJson::uuid)
            require(clientId != masterInstallId)
            require(pumpBinding.matches(HEX) && sha256.matches(HEX))
            require(counter > 1)
            HistoryJson.time(issuedAt); HistoryJson.time(validUntil)
            require(validUntil > issuedAt && validUntil - issuedAt <= 30_000)
            require(totalBytes in 1..AapsV3ComboJournalRead.MAX_READ_BYTES)
            require(offset in 0 until totalBytes && offset % MAX_CHUNK_BYTES == 0)
        }
    }
    val chunkBytes: Int get() = minOf(MAX_CHUNK_BYTES, totalBytes - offset)

    // All fields use closed ASCII alphabets, making delimiter framing unambiguous.
    fun authenticatedContext(): ByteArray = listOf(
        "aaps-combo-journal-chunk-v1", "master-to-client", "aes-256-gcm",
        clientId, masterInstallId, requestId, snapshotId, readId, pumpBinding, sha256,
        counter, issuedAt, validUntil, totalBytes, offset, chunkBytes
    ).joinToString("|").toByteArray(Charsets.US_ASCII)

    fun requireCurrent(now: Long) {
        require(now >= issuedAt && now < validUntil)
    }
    override fun toString() = "ComboChunkBinding(redacted)"

    companion object {
        const val MAX_CHUNK_BYTES = 32_768
        private val HEX = Regex("[0-9a-f]{64}")
    }
}

/** Pure response codec. No network, counter allocation, replay admission or therapy permission. */
internal class AapsV3ComboChunkEnvelope(secret: ByteArray) : Closeable {
    private var ownedSecret: ByteArray? = chunkOperation {
        require(secret.size == 32)
        secret.copyOf()
    }

    @Synchronized
    fun seal(binding: AapsV3ComboChunkBinding, raw: ByteArray, now: Long): ByteArray = chunkOperation {
        val secret = checkNotNull(ownedSecret)
        binding.requireCurrent(now)
        require(raw.size == binding.chunkBytes)
        val plain = raw.copyOf()
        try {
            val context = binding.authenticatedContext()
            // A fresh salt gives each response its own key, including retries after a restart.
            val salt = ByteArray(32).also(random::nextBytes)
            val iv = ByteArray(12).also(random::nextBytes)
            val key = deriveKey(secret, salt, context)
            val encrypted = try {
                cipher(Cipher.ENCRYPT_MODE, key, iv, context).doFinal(plain)
            } finally { key.fill(0) }
            HistoryJson.bytes(buildJsonObject {
                put("version", 1)
                put("saltB64", encoder.encodeToString(salt))
                put("ivB64", encoder.encodeToString(iv))
                put("ciphertextB64", encoder.encodeToString(encrypted))
            }).also { require(it.size <= MAX_ENVELOPE_BYTES) }
        } finally { plain.fill(0) }
    }

    /** Caller owns the returned bytes and must clear them after private assembly/retention. */
    @Synchronized
    fun open(binding: AapsV3ComboChunkBinding, wire: ByteArray, now: Long): ByteArray = chunkOperation {
        val secret = checkNotNull(ownedSecret)
        binding.requireCurrent(now)
        require(wire.size in 1..MAX_ENVELOPE_BYTES)
        val document = HistoryJson.parse(wire.copyOf(), MAX_ENVELOPE_BYTES)
        HistoryJson.fields(document, setOf("version", "saltB64", "ivB64", "ciphertextB64"))
        require(HistoryJson.long(document.getValue("version")) == 1L)
        fun decode(name: String, size: Int): ByteArray {
            val text = HistoryJson.string(document.getValue(name))
            require(text.length == ((size + 2) / 3) * 4)
            return decoder.decode(text).also { require(it.size == size && encoder.encodeToString(it) == text) }
        }
        val salt = decode("saltB64", 32)
        val iv = decode("ivB64", 12)
        val encrypted = decode("ciphertextB64", binding.chunkBytes + 16)
        val context = binding.authenticatedContext()
        val key = deriveKey(secret, salt, context)
        try {
            cipher(Cipher.DECRYPT_MODE, key, iv, context).doFinal(encrypted)
        } finally { key.fill(0) }
    }

    @Synchronized override fun close() { ownedSecret?.fill(0); ownedSecret = null }
    override fun toString() = "ComboChunkEnvelope(redacted)"

    companion object {
        // Leaves 16 KiB for the outer settings envelope and transport metadata.
        const val MAX_ENVELOPE_BYTES = 48 * 1024
        private val random = SecureRandom()
        private val encoder = Base64.getEncoder()
        private val decoder = Base64.getDecoder()

        /** RFC 5869 SHA-256 extract + the single expand block required for a 32-byte key. */
        internal fun deriveKey(input: ByteArray, salt: ByteArray, context: ByteArray): ByteArray {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(salt, "HmacSHA256"))
            val extracted = mac.doFinal(input)
            return try {
                mac.init(SecretKeySpec(extracted, "HmacSHA256"))
                mac.update(context)
                mac.doFinal(byteArrayOf(1))
            } finally { extracted.fill(0) }
        }

        private fun cipher(mode: Int, key: ByteArray, iv: ByteArray, context: ByteArray) =
            Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
                updateAAD(context)
            }
    }
}

private inline fun <T> chunkOperation(block: () -> T): T = try {
    block()
} catch (e: CancellationException) {
    throw e
} catch (_: Exception) {
    throw AapsV3ComboChunkFailure()
}
