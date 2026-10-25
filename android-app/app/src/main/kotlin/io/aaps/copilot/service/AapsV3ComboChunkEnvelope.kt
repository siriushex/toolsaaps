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
    private val payload = chunkOperation { ComboPayloadCipher(secret) }

    fun seal(binding: AapsV3ComboChunkBinding, raw: ByteArray, now: Long): ByteArray = chunkOperation {
        binding.requireCurrent(now)
        payload.seal(binding.authenticatedContext(), raw, binding.chunkBytes..binding.chunkBytes)
    }

    /** Caller owns the returned bytes and must clear them after private assembly/retention. */
    fun open(binding: AapsV3ComboChunkBinding, wire: ByteArray, now: Long): ByteArray = chunkOperation {
        binding.requireCurrent(now)
        payload.open(binding.authenticatedContext(), wire, binding.chunkBytes..binding.chunkBytes)
    }

    override fun close() = payload.close()
    override fun toString() = "ComboChunkEnvelope(redacted)"

    companion object {
        // Leaves 16 KiB for the outer settings envelope and transport metadata.
        const val MAX_ENVELOPE_BYTES = 48 * 1024
        internal fun deriveKey(input: ByteArray, salt: ByteArray, context: ByteArray): ByteArray =
            ComboPayloadCipher.deriveKey(input, salt, context)
    }
}

/** Same paired secret, distinct authenticated context: pages cannot be reused as chunks. */
internal class AapsV3ComboInventoryEnvelope(secret: ByteArray) : Closeable {
    private val payload = inventoryOperation { ComboPayloadCipher(secret) }
    fun seal(binding: AapsV3ComboInventoryBinding, page: AapsV3ComboInventoryPage, now: Long): ByteArray = inventoryOperation {
        binding.requireCurrent(now)
        page.requireFor(binding.query)
        val plain = HistoryJson.bytes(page.toJson())
        try { payload.seal(binding.authenticatedContext(), plain, 1..AapsV3ComboInventoryPage.MAX_PAGE_BYTES) }
        finally { plain.fill(0) }
    }
    fun open(binding: AapsV3ComboInventoryBinding, wire: ByteArray, now: Long): AapsV3ComboInventoryPage = inventoryOperation {
        binding.requireCurrent(now)
        val plain = payload.open(binding.authenticatedContext(), wire, 1..AapsV3ComboInventoryPage.MAX_PAGE_BYTES)
        try { AapsV3ComboInventoryPage.parse(plain, binding.query) }
        finally { plain.fill(0) }
    }
    override fun close() = payload.close()
    override fun toString() = "ComboInventoryEnvelope(redacted)"
}

private class ComboPayloadCipher(secret: ByteArray) : Closeable {
    private var ownedSecret: ByteArray? = secret.let { require(it.size == 32); it.copyOf() }

    @Synchronized
    fun seal(context: ByteArray, raw: ByteArray, size: IntRange): ByteArray {
        val secret = checkNotNull(ownedSecret)
        require(raw.size in size && size.first >= 1 && size.last <= AapsV3ComboChunkBinding.MAX_CHUNK_BYTES)
        val plain = raw.copyOf()
        return try {
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
            }).also { require(it.size <= AapsV3ComboChunkEnvelope.MAX_ENVELOPE_BYTES) }
        } finally { plain.fill(0) }
    }

    @Synchronized
    fun open(context: ByteArray, wire: ByteArray, size: IntRange): ByteArray {
        val secret = checkNotNull(ownedSecret)
        require(size.first >= 1 && size.last <= AapsV3ComboChunkBinding.MAX_CHUNK_BYTES)
        require(wire.size in 1..AapsV3ComboChunkEnvelope.MAX_ENVELOPE_BYTES)
        val document = HistoryJson.parse(wire.copyOf(), AapsV3ComboChunkEnvelope.MAX_ENVELOPE_BYTES)
        HistoryJson.fields(document, setOf("version", "saltB64", "ivB64", "ciphertextB64"))
        require(HistoryJson.long(document.getValue("version")) == 1L)
        fun decode(name: String, lengths: IntRange): ByteArray {
            val text = HistoryJson.string(document.getValue(name))
            require(text.length in ((lengths.first + 2) / 3) * 4..((lengths.last + 2) / 3) * 4)
            return decoder.decode(text).also { require(it.size in lengths && encoder.encodeToString(it) == text) }
        }
        val salt = decode("saltB64", 32..32)
        val iv = decode("ivB64", 12..12)
        val encrypted = decode("ciphertextB64", size.first + 16..size.last + 16)
        val key = deriveKey(secret, salt, context)
        return try {
            cipher(Cipher.DECRYPT_MODE, key, iv, context).doFinal(encrypted)
        } finally { key.fill(0) }
    }

    @Synchronized override fun close() { ownedSecret?.fill(0); ownedSecret = null }

    companion object {
        private val random = SecureRandom()
        private val encoder = Base64.getEncoder()
        private val decoder = Base64.getDecoder()

        /** RFC 5869 SHA-256 extract + the single expand block required for a 32-byte key. */
        fun deriveKey(input: ByteArray, salt: ByteArray, context: ByteArray): ByteArray {
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
