package integration.entries

import dev.whyoleg.cryptography.BinarySize.Companion.bytes
import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.DelicateCryptographyApi
import dev.whyoleg.cryptography.algorithms.AES
import dev.whyoleg.cryptography.algorithms.HKDF
import dev.whyoleg.cryptography.algorithms.SHA256
import dev.whyoleg.cryptography.random.CryptographyRandom
import java.security.MessageDigest
import java.util.Base64
import kotlinx.serialization.json.*
import kotlin.system.exitProcess

/** Offline provider oracle with an independently framed synthetic inventory. */
@OptIn(DelicateCryptographyApi::class)
fun main() {
    try {
        val input = System.`in`.readNBytes(49_153)
        check(input.size in 1..49_152)
        val wire = Json.parseToJsonElement(input.decodeToString()).jsonObject
        val context = listOf("aaps-combo-inventory-page-v1", "master-to-client", "aes-256-gcm",
            "11111111-2222-4333-8444-555555555555", "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee",
            "00000000-0000-4000-8000-000000008002", "2", "1800000000000", "1800000030000",
            "-", "-", "32").joinToString("|").toByteArray(Charsets.US_ASCII)
        val provider = CryptographyProvider.Default
        fun derive(salt: ByteArray) = provider.get(HKDF).secretDerivation(
            digest = SHA256, outputSize = 32.bytes, salt = salt, info = context
        ).deriveSecretToByteArrayBlocking(ByteArray(32) { it.toByte() })
        fun decode(field: String) = Base64.getDecoder().decode(wire.getValue(field).jsonPrimitive.content)
        val key = derive(decode("saltB64"))
        val plain = try {
            provider.get(AES.GCM).keyDecoder().decodeFromByteArrayBlocking(AES.Key.Format.RAW, key)
                .cipher().decryptWithIvBlocking(decode("ivB64"), decode("ciphertextB64"), context)
        } finally { key.fill(0) }
        try {
            check(plain.size in 1..16_384)
            val body = Json.parseToJsonElement(plain.decodeToString()).jsonObject
            check(body.getValue("reads").jsonArray.size == 3)
            val expected = buildString {
                append("aaps-combo-inventory-v1|00000000-0000-4000-8000-000000009999|")
                append("a".repeat(64)); append("|3|300|1|true\n")
                for (n in 1..3) {
                    append("00000000-0000-4000-8000-%012d|100|".format(n))
                    append("b".repeat(64)); append("|true|false|false|false\n")
                }
            }.toByteArray(Charsets.US_ASCII)
            val hash = MessageDigest.getInstance("SHA-256").digest(expected)
                .joinToString("") { "%02x".format(it.toInt() and 255) }
            check(body.getValue("sha256").jsonPrimitive.content == hash)
            check(body.getValue("writerFailed").jsonPrimitive.boolean)
            check(body.getValue("unscopedCorruptReads").jsonPrimitive.int == 1)
            val salt = CryptographyRandom.nextBytes(32)
            val iv = CryptographyRandom.nextBytes(12)
            val nextKey = derive(salt)
            val encrypted = try {
                provider.get(AES.GCM).keyDecoder().decodeFromByteArrayBlocking(AES.Key.Format.RAW, nextKey)
                    .cipher().encryptWithIvBlocking(iv, plain, context)
            } finally { nextKey.fill(0) }
            print(buildJsonObject {
                put("version", 1)
                put("saltB64", Base64.getEncoder().encodeToString(salt))
                put("ivB64", Base64.getEncoder().encodeToString(iv))
                put("ciphertextB64", Base64.getEncoder().encodeToString(encrypted))
            })
        } finally { plain.fill(0) }
    } catch (_: Exception) {
        System.err.print("inventory_crypto_check_failed")
        exitProcess(1)
    }
}
