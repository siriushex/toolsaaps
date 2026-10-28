package io.aaps.copilot.service

import java.io.File
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Assume
import org.junit.Test

class AapsV3ComboNativeReplyTest {
    private val at = 1_800_000_000_000L
    private val secret = ByteArray(32) { it.toByte() }
    private val client = "11111111-2222-4333-8444-555555555555"
    private val master = "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee"
    private val pump = "a".repeat(64)
    private fun id(n: Int) = "00000000-0000-4000-8000-%012d".format(n)
    private fun raw(size: Int) = ByteArray(size) { (it % 251).toByte() }
    private fun hash(size: Int) = MessageDigest.getInstance("SHA-256").digest(raw(size)).joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun binding(spec: AapsV3ComboReadSpec.Inventory, counter: Long = 3) =
        AapsV3ComboInventoryBinding(client, master, id(8000 + counter.toInt()), counter, at, at + 30000, spec)
    private fun chunk(size: Int, offset: Int) = AapsV3ComboReadSpec.Chunk(id(9999), id(1), pump, hash(size), size, offset)
    private fun binding(spec: AapsV3ComboReadSpec.Chunk) = AapsV3ComboChunkBinding(client, master, id(8003),
        spec.snapshotId, spec.readId, spec.pumpBinding, spec.sha256, 3, at, at + 30000, spec.totalBytes, spec.offset)

    private fun reply(spec: AapsV3ComboReadSpec, count: Int = 3, size: Int = 32771, unknown: Int = 1,
                      failed: Boolean = true, counter: Long = 3, accepted: Boolean = true): ByteArray {
        val cp = System.getenv("AAPS_INTEROP_CLASSPATH")?.let(::File)
        val java = System.getenv("AAPS_INTEROP_JAVA_HOME")
        if (System.getenv("CI") == "true") check(cp?.isFile == true && !java.isNullOrBlank())
        Assume.assumeTrue("Prepare pinned native reply driver", cp?.isFile == true && !java.isNullOrBlank())
        val output = File.createTempFile("native-reply-", ".out")
        val errors = File.createTempFile("native-reply-", ".err")
        var process: Process? = null
        try {
            val envelope = AapsV3ComboReadProtocol(secret).use {
                it.request(client, master, id(8000 + counter.toInt()), counter, at, spec).json
            }
            val input = buildJsonObject {
                put("envelope", envelope); put("count", count); put("readBytes", size)
                put("unscoped", unknown); put("writerFailed", failed)
            }
            process = ProcessBuilder("$java/bin/java", "-cp", cp!!.readText(), "integration.entries.ComboNativeReplyInteropKt")
                .redirectOutput(output).redirectError(errors).start()
            process.outputStream.use { it.write(input.toString().toByteArray()) }
            assertTrue("Native reply timed out", process.waitFor(30, TimeUnit.SECONDS))
            if (accepted) {
                assertEquals("Native reply failed", 0, process.exitValue())
                assertEquals(0L, errors.length()); assertTrue(output.length() in 1..49152)
            } else {
                assertEquals(1, process.exitValue()); assertEquals(0L, output.length())
                assertEquals("native_reply_check_failed", errors.readText())
            }
            return output.readBytes()
        } finally {
            if (process?.isAlive == true) { process.destroyForcibly(); check(process.waitFor(10, TimeUnit.SECONDS)) }
            output.delete(); errors.delete()
        }
    }

    @Test fun actualNativePagesReassembleExactCopilotCatalogIncludingAdverseEvidence() {
        val expected = AapsV3ComboInventory.create(id(9999), pump, (1..3).map { n ->
            AapsV3ComboInventoryEntry(id(n), 32771, hash(32771), n != 1, n == 1, true, n == 2)
        }, 1, true)
        AapsV3ComboInventoryEnvelope(secret).use { codec ->
            AapsV3ComboInventoryAssembly().use { assembly ->
                for ((spec, counter) in listOf(AapsV3ComboReadSpec.Inventory(limit = 2) to 3L,
                    AapsV3ComboReadSpec.Inventory(id(9999), id(2), 2) to 4L)) {
                    val b = binding(spec, counter)
                    val page = codec.open(b, reply(spec, counter = counter), at)
                    assembly.accept(b, page, at)
                    assertEquals(expected.sha256, page.sha256)
                }
                val result = assembly.completed()!!
                assertEquals(expected.reads, result.reads); assertEquals(expected.sha256, result.sha256)
                assertTrue(result.writerFailed); assertEquals(1, result.unscopedCorruptReads)
                assertFalse(result.deliveryCompletenessVerified); assertFalse(result.pumpBindingVerified)
            }
        }
    }
    @Test fun actualNativeChunksDecryptExactlyAtAllBoundaryShapes() {
        AapsV3ComboChunkEnvelope(secret).use { codec ->
            for ((size, offset) in listOf(1 to 0, 32768 to 0, 32771 to 0, 32771 to 32768,
                2097152 to 2064384)) {
                val spec = chunk(size, offset); val b = binding(spec)
                val wire = reply(spec, count = 1, size = size)
                val plain = codec.open(b, wire, at)
                try { assertArrayEquals(raw(size).copyOfRange(offset, offset + b.chunkBytes), plain) }
                finally { plain.fill(0) }
            }
        }
    }
    @Test fun nativeReplyCannotBeMovedToAnotherRequestOrAltered() {
        val spec = chunk(32771, 32768); val b = binding(spec); val wire = reply(spec)
        AapsV3ComboChunkEnvelope(secret).use { codec ->
            for (wrong in listOf(b.copy(clientId = id(90)), b.copy(masterInstallId = id(91)), b.copy(requestId = id(92)),
                b.copy(snapshotId = id(93)), b.copy(readId = id(94)), b.copy(pumpBinding = "c".repeat(64)),
                b.copy(sha256 = "d".repeat(64)), b.copy(counter = 4), b.copy(issuedAt = at + 1), b.copy(validUntil = at + 29999))) {
                assertThrows(AapsV3ComboChunkFailure::class.java) { codec.open(wrong, wire, at + 1) }
            }
            val json = Json.parseToJsonElement(wire.decodeToString()).jsonObject
            for (field in listOf("saltB64", "ivB64", "ciphertextB64")) {
                val bytes = Base64.getDecoder().decode(json.getValue(field).jsonPrimitive.content)
                bytes[0] = (bytes[0].toInt() xor 1).toByte()
                val changed = JsonObject(json + (field to JsonPrimitive(Base64.getEncoder().encodeToString(bytes))))
                assertThrows(AapsV3ComboChunkFailure::class.java) { codec.open(b, changed.toString().toByteArray(), at) }
            }
            assertThrows(AapsV3ComboChunkFailure::class.java) { codec.open(b, wire, at + 30000) }
            assertThrows(AapsV3ComboChunkFailure::class.java) { codec.open(b, wire, at - 1) }
        }
        AapsV3ComboInventoryEnvelope(secret).use { codec ->
            assertThrows(AapsV3ComboInventoryFailure::class.java) { codec.open(binding(AapsV3ComboReadSpec.Inventory()), wire, at) }
        }
    }
    @Test fun nativeEmptyAndMaximumPagesStayBoundedAndPreserveSourceWarnings() {
        AapsV3ComboInventoryEnvelope(secret).use { codec ->
            val query = AapsV3ComboReadSpec.Inventory()
            val empty = codec.open(binding(query), reply(query, count = 0, size = 1, unknown = 2), at)
            assertEquals(0, empty.totalReads); assertEquals(2, empty.unscopedCorruptReads); assertTrue(empty.writerFailed)
            for (spec in listOf(query, AapsV3ComboReadSpec.Inventory(id(9999), id(4064), 32))) {
                val page = codec.open(binding(spec), reply(spec, count = 4096, size = 1, unknown = 0), at)
                assertEquals(4096, page.totalReads); assertEquals(32, page.reads.size)
                assertEquals(spec.snapshotId == null, page.hasMore)
            }
        }
    }
    @Test fun nativeSourceMismatchCannotProduceSuccessfulCiphertext() {
        val c = chunk(32771, 32768)
        for (spec in listOf(c.copy(sha256 = "b".repeat(64)), c.copy(pumpBinding = "c".repeat(64)),
            c.copy(readId = id(80)), c.copy(snapshotId = id(81)),
            AapsV3ComboReadSpec.Inventory(id(9999), id(3)), AapsV3ComboReadSpec.Inventory(id(80), id(1)))) {
            reply(spec, accepted = false)
        }
    }
    @Test fun nativeRetriesHaveIndependentSaltAndNonce() {
        val spec = AapsV3ComboReadSpec.Inventory()
        val a = Json.parseToJsonElement(reply(spec).decodeToString()).jsonObject
        val b = Json.parseToJsonElement(reply(spec).decodeToString()).jsonObject
        assertNotEquals(a["saltB64"], b["saltB64"]); assertNotEquals(a["ivB64"], b["ivB64"])
    }
}
