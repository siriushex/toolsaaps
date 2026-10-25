package io.aaps.copilot.service

import java.io.File
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Assume
import org.junit.Test

class AapsV3ComboChunkEnvelopeTest {
    private val at = 1_800_000_000_000L
    private val secret = ByteArray(32) { it.toByte() }
    private val binding = AapsV3ComboChunkBinding(
        clientId = "11111111-2222-4333-8444-555555555555",
        masterInstallId = "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee",
        requestId = "00000000-0000-4000-8000-000000000001",
        snapshotId = "00000000-0000-4000-8000-000000000002",
        readId = "00000000-0000-4000-8000-000000000003",
        pumpBinding = "a".repeat(64), sha256 = "b".repeat(64),
        counter = 2, issuedAt = at, validUntil = at + 30_000, totalBytes = 65_539, offset = 0
    )
    private fun bytes(size: Int = binding.chunkBytes) = ByteArray(size) { (it % 251).toByte() }
    private fun changed(wire: ByteArray, key: String, value: JsonElement): ByteArray =
        JsonObject(Json.parseToJsonElement(wire.decodeToString()).jsonObject + (key to value))
            .toString().toByteArray()
    private fun refused(block: () -> Unit) {
        val error = assertThrows(AapsV3ComboChunkFailure::class.java) { block() }
        assertEquals("combo_chunk_unavailable", error.message)
        assertNull(error.cause)
    }

    @Test fun roundTripHasRandomizedCiphertextAndNoPlainHistoryOrScopeMetadata() {
        AapsV3ComboChunkEnvelope(secret).use { codec ->
            val raw = bytes()
            val first = codec.seal(binding, raw, at)
            val second = codec.seal(binding, raw, at)
            assertFalse(first.contentEquals(second))
            assertArrayEquals(raw, codec.open(binding, first, at))
            assertArrayEquals(raw, codec.open(binding, second, at + 29_999))
            val json = first.decodeToString()
            listOf(binding.clientId, binding.readId, binding.pumpBinding, binding.sha256,
                Base64.getEncoder().encodeToString(raw)).forEach { assertFalse(json.contains(it)) }
            assertFalse(codec.toString().contains("00010203"))
            assertFalse(binding.toString().contains(binding.clientId))
        }
    }

    @Test fun everyScopeFieldIsAuthenticatedAndCannotBeRebound() {
        val other = "00000000-0000-4000-8000-000000000004"
        val variants = listOf(binding.copy(clientId = other), binding.copy(masterInstallId = other),
            binding.copy(requestId = other), binding.copy(snapshotId = other), binding.copy(readId = other),
            binding.copy(pumpBinding = "c".repeat(64)), binding.copy(sha256 = "c".repeat(64)),
            binding.copy(counter = 3), binding.copy(issuedAt = at - 1, validUntil = at + 29_999),
            binding.copy(validUntil = at + 20_000), binding.copy(totalBytes = 65_540),
            binding.copy(offset = 32_768))
        AapsV3ComboChunkEnvelope(secret).use { codec ->
            val wire = codec.seal(binding, bytes(), at)
            variants.forEach { alternative -> refused { codec.open(alternative, wire, at) } }
            AapsV3ComboChunkEnvelope(ByteArray(32) { 7 }).use { wrong ->
                refused { wrong.open(binding, wire, at) }
            }
        }
    }

    @Test fun saltNonceCiphertextAndTagTamperingAreIndistinguishable() {
        AapsV3ComboChunkEnvelope(secret).use { codec ->
            val wire = codec.seal(binding, bytes(), at)
            for (field in listOf("saltB64", "ivB64", "ciphertextB64")) {
                val original = Base64.getDecoder().decode(Json.parseToJsonElement(wire.decodeToString())
                    .jsonObject.getValue(field).jsonPrimitive.content)
                for (index in listOf(0, original.lastIndex)) {
                    val mutated = original.copyOf().apply { this[index] = (this[index].toInt() xor 1).toByte() }
                    refused { codec.open(binding, changed(wire, field,
                        JsonPrimitive(Base64.getEncoder().encodeToString(mutated))), at) }
                }
            }
        }
    }

    @Test fun malformedDocumentsAndNonCanonicalBase64AreRejected() {
        AapsV3ComboChunkEnvelope(secret).use { codec ->
            val wire = codec.seal(binding, bytes(), at)
            val salt = Json.parseToJsonElement(wire.decodeToString()).jsonObject.getValue("saltB64").jsonPrimitive.content
            val malformed = listOf(changed(wire, "version", JsonPrimitive(2)),
                changed(wire, "version", JsonPrimitive("1")), changed(wire, "extra", JsonPrimitive(true)),
                changed(wire, "saltB64", JsonPrimitive(salt.trimEnd('='))),
                changed(wire, "ivB64", JsonPrimitive("!")),
                changed(wire, "ciphertextB64", JsonNull),
                ("{\"version\":1," + wire.decodeToString().drop(1)).toByteArray(),
                (wire.decodeToString() + "{}").toByteArray(), byteArrayOf(0xc3.toByte()),
                ByteArray(65_537), byteArrayOf())
            malformed.forEach { refused { codec.open(binding, it, at) } }
        }
    }

    @Test fun timeKeyLengthAndClosedLifecycleFailWithoutReturningPlaintext() {
        listOf(0, 31, 33).forEach { size -> refused { AapsV3ComboChunkEnvelope(ByteArray(size)) } }
        val ownedKey = secret.copyOf()
        val codec = AapsV3ComboChunkEnvelope(ownedKey)
        ownedKey.fill(0)
        val wire = codec.seal(binding, bytes(), at)
        AapsV3ComboChunkEnvelope(secret).use { verifier ->
            assertArrayEquals(bytes(), verifier.open(binding, wire, at))
        }
        listOf(0L, at - 1, binding.validUntil, Long.MAX_VALUE).forEach { now ->
            refused { codec.open(binding, wire, now) }
            refused { codec.seal(binding, bytes(), now) }
        }
        codec.close(); codec.close()
        refused { codec.open(binding, wire, at) }
        refused { codec.seal(binding, bytes(), at) }
    }

    @Test fun fixedChunkGeometryBoundsWorkAndWholeSettingsDocument() {
        refused { binding.copy(counter = 1) }
        refused { binding.copy(clientId = "../owner") }
        refused { binding.copy(pumpBinding = "A".repeat(64)) }
        refused { binding.copy(totalBytes = Int.MAX_VALUE) }
        refused { binding.copy(offset = 1) }
        refused { binding.copy(offset = -1) }
        refused { binding.copy(offset = 98_304) }
        refused { binding.copy(validUntil = at + 30_001) }
        refused { binding.copy(issuedAt = Long.MAX_VALUE, validUntil = Long.MIN_VALUE) }
        AapsV3ComboChunkEnvelope(secret).use { codec ->
            refused { codec.seal(binding, bytes(32_769), at) }
            refused { codec.seal(binding, bytes(32_767), at) }
            val last = binding.copy(offset = 65_536)
            assertEquals(3, last.chunkBytes)
            assertArrayEquals(bytes(3), codec.open(last, codec.seal(last, bytes(3), at), at))
            val document = buildJsonObject {
                put("identifier", "aaps_combo_journal_chunk_${binding.clientId}")
                put("envelope", Json.parseToJsonElement(codec.seal(binding, bytes(), at).decodeToString()))
                put("srvModified", Long.MAX_VALUE); put("srvCreated", Long.MAX_VALUE)
                put("isValid", true)
            }.toString().toByteArray()
            assertTrue(document.size < 48 * 1024)
            assertEquals(64, (AapsV3ComboJournalRead.MAX_READ_BYTES + 32_767) / 32_768)
        }
    }

    @Test fun hkdfMatchesRfc5869Sha256CaseOneFirst32Bytes() {
        val key = AapsV3ComboChunkEnvelope.deriveKey(ByteArray(22) { 0x0b },
            ByteArray(13) { it.toByte() }, ByteArray(10) { (0xf0 + it).toByte() })
        assertEquals("3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf",
            key.joinToString("") { "%02x".format(it.toInt() and 255) })
    }

    @Test fun authenticatedBytesStillRequireAssemblyDigestAndClinicalEvidence() {
        val raw = bytes()
        val scope = binding.copy(totalBytes = raw.size, sha256 = AapsV3ComboJournalAssembly.digest(raw))
        AapsV3ComboChunkEnvelope(secret).use { codec ->
            val decrypted = codec.open(scope, codec.seal(scope, raw, at), at)
            try {
                AapsV3ComboJournalAssembly(scope.readId, scope.totalBytes, scope.sha256, scope.pumpBinding).use { assembly ->
                    val read = assembly.append(scope.readId, scope.offset, scope.totalBytes, scope.sha256,
                        decrypted, endOfRead = true)!!
                    assertEquals(AapsV3ComboHistoryStatus.INVALID, read.status)
                    assertFalse(read.pumpBindingVerified)
                    assertFalse(read.deliveryCompletenessVerified)
                }
                AapsV3ComboJournalAssembly(scope.readId, scope.totalBytes, "e".repeat(64), scope.pumpBinding).use { assembly ->
                    assertThrows(java.io.IOException::class.java) {
                        assembly.append(scope.readId, 0, scope.totalBytes, "e".repeat(64), decrypted, true)
                    }
                }
            } finally { decrypted.fill(0) }
        }
    }

    @Test fun nativeRc1CryptoProviderInteroperatesInBothDirections() {
        val classpath = System.getenv("AAPS_INTEROP_CLASSPATH")?.let(::File)
        val javaHome = System.getenv("AAPS_INTEROP_JAVA_HOME")
        if (System.getenv("CI") == "true") check(classpath?.isFile == true && !javaHome.isNullOrBlank())
        Assume.assumeTrue("Prepare the pristine RC1 crypto test driver", classpath?.isFile == true && !javaHome.isNullOrBlank())
        val output = File.createTempFile("chunk-crypto-", ".json")
        val errors = File.createTempFile("chunk-crypto-", ".err")
        var process: Process? = null
        try {
            AapsV3ComboChunkEnvelope(secret).use { codec ->
                val wire = codec.seal(binding, bytes(), at)
                process = ProcessBuilder("$javaHome/bin/java", "-cp", classpath!!.readText(),
                    "integration.entries.ComboChunkCryptoInteropKt").redirectOutput(output).redirectError(errors).start()
                process!!.outputStream.use { it.write(wire) }
                assertTrue("Native crypto driver timed out", process!!.waitFor(30, TimeUnit.SECONDS))
                assertEquals("Native crypto driver failed", 0, process!!.exitValue())
                assertArrayEquals(bytes(), codec.open(binding, output.readBytes(), at))
            }
        } finally {
            process?.let { if (it.isAlive) { it.destroyForcibly(); check(it.waitFor(10, TimeUnit.SECONDS)) } }
            output.delete(); errors.delete()
        }
    }
}
