package io.aaps.copilot.service

import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class AapsV3PairingProtocolTest {
    private val client = "11111111-2222-4333-8444-555555555555"
    private val master = "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee"
    private val at = 1_800_000_000_000L
    private val secret = ByteArray(32) { it.toByte() }
    private fun payload() = buildJsonObject {
        put("v", 1); put("clientId", client); put("masterInstallId", master)
        put("secretHex", secret.joinToString("") { "%02x".format(it.toInt() and 255) }); put("expiresAt", at + 120_000)
    }
    private fun offer(inside: JsonObject = payload(), outerId: String = client): JsonObject {
        val salt = ByteArray(16) { (it + 1).toByte() }; val iv = ByteArray(12) { (it + 2).toByte() }
        val spec = PBEKeySpec("12345678".toCharArray(), salt, 200_000, 256)
        val key = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        val ciphertext = try { Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
            doFinal(inside.toString().toByteArray())
        } } finally { key.fill(0); spec.clearPassword() }
        return buildJsonObject {
            put("schemaVersion", 1); put("clientId", outerId); put("expiresAt", at + 120_000)
            put("kdfSaltB64", Base64.getEncoder().encodeToString(salt))
            put("ivB64", Base64.getEncoder().encodeToString(iv))
            put("wrappedB64", Base64.getEncoder().encodeToString(ciphertext))
        }
    }
    @Test fun nativePinEnvelopeUnwrapsWithoutRetainingPinOrPrintingSecret() {
        val pin = "12345678".toCharArray()
        AapsV3PairingProtocol.unwrap(offer(), pin, at).use { credential ->
            assertEquals(client, credential.clientId); assertEquals(master, credential.masterInstallId)
            credential.withSecret { assertArrayEquals(secret, it) }
            assertFalse(credential.toString().contains("00010203"))
        }
        assertArrayEquals(CharArray(8), pin)
    }
    @Test fun wrongPinAndTamperedCiphertextHaveFixedNonsecretFailure() {
        val bad = JsonObject(offer() + ("wrappedB64" to JsonPrimitive(Base64.getEncoder().encodeToString(ByteArray(80)))))
        listOf(offer() to "87654321", bad to "12345678").forEach { (doc, pin) ->
            val error = assertThrows(AapsV3PairingFailure::class.java) { AapsV3PairingProtocol.unwrap(doc, pin.toCharArray(), at) }
            assertEquals("native_pairing_unavailable", error.message)
        }
    }
    @Test fun expiredOrUnreasonablyLongOfferIsRefusedBeforeKeyDerivation() {
        val original = offer()
        listOf(at, at + 600_001).forEach { expiry ->
            assertThrows(AapsV3PairingFailure::class.java) {
                AapsV3PairingProtocol.unwrap(JsonObject(original + ("expiresAt" to JsonPrimitive(expiry))), "12345678".toCharArray(), at)
            }
        }
    }
    @Test fun authenticatedInnerIdentityAndExpiryMustMatchOuterOffer() {
        val changed = listOf(payload() + ("clientId" to JsonPrimitive(master)),
            payload() + ("expiresAt" to JsonPrimitive(at + 60_000)), payload() + ("v" to JsonPrimitive(2)))
        changed.forEach { fields -> assertThrows(AapsV3PairingFailure::class.java) {
            AapsV3PairingProtocol.unwrap(offer(JsonObject(fields)), "12345678".toCharArray(), at)
        } }
    }
    @Test fun strictFormatRejectsNonAsciiPinsExtraFieldsAndInvalidBase64() {
        val original = offer()
        listOf("123", "１２３４５６７８", "1234567x").forEach { pin ->
            assertThrows(AapsV3PairingFailure::class.java) { AapsV3PairingProtocol.unwrap(original, pin.toCharArray(), at) }
        }
        listOf(original + ("unexpected" to JsonPrimitive(1)), original + ("ivB64" to JsonPrimitive("!"))).forEach { fields ->
            assertThrows(AapsV3PairingFailure::class.java) { AapsV3PairingProtocol.unwrap(JsonObject(fields), "12345678".toCharArray(), at) }
        }
    }
    @Test fun sessionCodecCanOnlyProduceNativeHelloAndPing() {
        AapsV3SessionProtocol(secret).use { protocol ->
            val hello = protocol.hello(client, 1, at, at + 120_000)
            assertEquals("hello", hello.getValue("type").jsonPrimitive.content)
            assertFalse(hello.getValue("wantsAck").jsonPrimitive.boolean)
            assertEquals("""{"type":"hello","protocolVersion":1}""", hello.getValue("payload").jsonPrimitive.content)
            val ping = protocol.ping(client, 2, at)
            assertEquals("ping", ping.getValue("type").jsonPrimitive.content)
            assertEquals(at + 8000, ping.getValue("validUntil").jsonPrimitive.long)
            assertTrue(ping.getValue("wantsAck").jsonPrimitive.boolean)
            assertNotEquals(hello.getValue("signature"), ping.getValue("signature"))
        }
    }
    @Test fun closedCredentialCannotReopenOrExposeKey() {
        val credential = AapsV3PairingProtocol.unwrap(offer(), "12345678".toCharArray(), at)
        credential.close()
        assertThrows(IllegalStateException::class.java) { credential.withSecret { fail("closed key") } }
    }
}
