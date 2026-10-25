package io.aaps.copilot.service

import java.io.File
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Assume
import org.junit.Test

class AapsV3ComboInventoryTest {
    private val at = 1_800_000_000_000L
    private val secret = ByteArray(32) { it.toByte() }
    private val client = "11111111-2222-4333-8444-555555555555"
    private val master = "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee"
    private fun id(n: Int) = "00000000-0000-4000-8000-%012d".format(n)
    private fun entry(n: Int, size: Int = 100) = AapsV3ComboInventoryEntry(id(n), size, "b".repeat(64),
        closed = true, corrupt = false, adverse = false, readFailed = false)
    private fun inventory(count: Int = 3) = AapsV3ComboInventory.create(id(9999), "a".repeat(64),
        (1..count).map { entry(it) }, unscopedCorruptReads = 1, writerFailed = true)
    private fun binding(counter: Long = 2, query: AapsV3ComboReadSpec.Inventory = AapsV3ComboReadSpec.Inventory(limit = 2)) =
        AapsV3ComboInventoryBinding(client, master, id(8000 + counter.toInt()), counter, at, at + 30_000, query)
    private fun refused(block: () -> Unit) {
        val error = assertThrows(AapsV3ComboInventoryFailure::class.java) { block() }
        assertEquals("combo_inventory_unavailable", error.message)
        assertNull(error.cause)
    }

    @Test fun encryptedPagesReassembleExactInventoryWithoutDeliveryAuthority() {
        val source = inventory()
        val first = binding()
        val next = binding(3, AapsV3ComboReadSpec.Inventory(source.snapshotId, id(2), 2))
        val assembly = AapsV3ComboInventoryAssembly()
        AapsV3ComboInventoryEnvelope(secret).use { codec ->
            val wire = codec.seal(first, source.page(first.query), at)
            assertFalse(wire.contentEquals(codec.seal(first, source.page(first.query), at)))
            for (value in listOf(source.snapshotId, source.pumpBinding, source.sha256, id(1)))
                assertFalse(wire.decodeToString().contains(value))
            val firstPage = codec.open(first, wire, at)
            assembly.accept(first, firstPage, at)
            assembly.accept(first, firstPage, at + 1)
            assertNull(assembly.completed())
            assembly.accept(next, codec.open(next, codec.seal(next, source.page(next.query), at), at), at)
        }
        val result = assembly.completed()!!
        assertEquals(source.sha256, result.sha256)
        assertEquals(source.reads, result.reads)
        assertTrue(result.writerFailed)
        assertEquals(1, result.unscopedCorruptReads)
        assertFalse(result.deliveryCompletenessVerified)
        assertFalse(result.pumpBindingVerified)
        assertThrows(UnsupportedOperationException::class.java) { (result.reads as MutableList).clear() }
        assertFalse(result.toString().contains(client))
    }

    @Test fun requestScopeExpiryAndClosedCodecCannotBeBypassed() {
        val first = binding()
        val codec = AapsV3ComboInventoryEnvelope(secret)
        val wire = codec.seal(first, inventory().page(first.query), at)
        val alternatives = listOf(first.copy(clientId = id(50)), first.copy(masterInstallId = id(51)),
            first.copy(requestId = id(52)), first.copy(counter = 7), first.copy(issuedAt = at - 1, validUntil = at + 29_999),
            first.copy(validUntil = at + 29_999), first.copy(query = first.query.copy(limit = 3)),
            first.copy(query = AapsV3ComboReadSpec.Inventory(id(9999), id(2), 2)))
        alternatives.forEach { refused { codec.open(it, wire, at) } }
        refused { codec.open(first, wire, at - 1) }
        refused { codec.open(first, wire, at + 30_000) }
        AapsV3ComboInventoryEnvelope(ByteArray(32)).use { wrong -> refused { wrong.open(first, wire, at) } }
        codec.close(); codec.close()
        refused { codec.open(first, wire, at) }
    }

    @Test fun missingReorderedMixedOrChangedPagesLatchFailure() {
        val source = inventory(5)
        val first = binding()
        val second = binding(3, AapsV3ComboReadSpec.Inventory(source.snapshotId, id(2), 2))
        val third = binding(4, AapsV3ComboReadSpec.Inventory(source.snapshotId, id(4), 2))
        for (bad in listOf(third, second.copy(clientId = id(50)), second.copy(masterInstallId = id(51)),
            second.copy(counter = 2), second.copy(requestId = first.requestId))) {
            val assembly = AapsV3ComboInventoryAssembly()
            assembly.accept(first, source.page(first.query), at)
            refused { assembly.accept(bad, source.page(bad.query), at) }
            assertNull(assembly.completed())
            refused { assembly.accept(second, source.page(second.query), at) }
        }
        val other = AapsV3ComboInventory.create(source.snapshotId, source.pumpBinding,
            source.reads.map { if (it.readId == id(4)) it.copy(sha256 = "c".repeat(64)) else it }, 1, true)
        val assembly = AapsV3ComboInventoryAssembly()
        assembly.accept(first, source.page(first.query), at)
        refused { assembly.accept(second, other.page(second.query), at) }
    }

    @Test fun manifestHashDetectsTruncatedInventoryAndChangedMetadata() {
        val source = inventory(3)
        val request = binding(query = AapsV3ComboReadSpec.Inventory(limit = 32))
        val json = source.page(request.query).toJson()
        val body = JsonObject(json + ("reads" to JsonArray(source.reads.take(2).map { it.toJson() })))
        refused { AapsV3ComboInventoryPage.parse(body.toString().toByteArray(), request.query) }
        val changed = JsonObject(json + ("sha256" to JsonPrimitive("c".repeat(64))))
        val page = AapsV3ComboInventoryPage.parse(changed.toString().toByteArray(), request.query)
        val assembly = AapsV3ComboInventoryAssembly()
        refused { assembly.accept(request, page, at) }
        assertNull(assembly.completed())
    }

    @Test fun strictPageParsingRejectsAmbiguousOrUnboundedMetadata() {
        val request = binding()
        val json = inventory().page(request.query).toJson()
        val invalid = listOf(JsonObject(json + ("totalReads" to JsonPrimitive(4097))),
            JsonObject(json + ("totalBytes" to JsonPrimitive(33L * 1024 * 1024))),
            JsonObject(json + ("version" to JsonPrimitive("1"))),
            JsonObject(json + ("unscopedCorruptReads" to JsonPrimitive(-1))),
            JsonObject(json + ("extra" to JsonPrimitive(true))))
        invalid.forEach { refused { AapsV3ComboInventoryPage.parse(it.toString().toByteArray(), request.query) } }
        refused { AapsV3ComboInventoryPage.parse(("{\"version\":1," + json.toString().drop(1)).toByteArray(), request.query) }
        refused { AapsV3ComboInventoryPage.parse(byteArrayOf(0xc3.toByte()), request.query) }
        refused { AapsV3ComboInventory.create(id(9999), "a".repeat(64), listOf(entry(2), entry(1))) }
        refused { AapsV3ComboInventory.create(id(9999), "a".repeat(64), listOf(entry(1), entry(1))) }
    }

    @Test fun emptyAndMaximumInventoriesRemainBoundedAndUnverified() {
        val empty = AapsV3ComboInventory.create(id(9999), "a".repeat(64), emptyList(), 2, true)
        val request = binding()
        val assembly = AapsV3ComboInventoryAssembly()
        assembly.accept(request, empty.page(request.query), at)
        assertTrue(assembly.completed()!!.reads.isEmpty())
        assertFalse(assembly.completed()!!.deliveryCompletenessVerified)
        val source = AapsV3ComboInventory.create(id(9999), "a".repeat(64), (1..4096).map { entry(it, 1) })
        val full = AapsV3ComboInventoryAssembly()
        AapsV3ComboInventoryEnvelope(secret).use { codec ->
            var after: String? = null
            var counter = 2L
            do {
                val b = binding(counter++, AapsV3ComboReadSpec.Inventory(if (after == null) null else source.snapshotId, after, 32))
                val page = source.page(b.query)
                val wire = codec.seal(b, page, at)
                assertTrue(wire.size <= 49_152)
                full.accept(b, codec.open(b, wire, at), at)
                after = if (page.hasMore) page.reads.last().readId else null
            } while (after != null)
        }
        assertEquals(4096, full.completed()!!.reads.size)
    }

    @Test fun everyManifestFieldIsCoveredByWholeInventoryDigest() {
        val source = inventory()
        val request = binding(query = AapsV3ComboReadSpec.Inventory(limit = 32))
        val original = source.page(request.query).toJson()
        val variants = listOf("snapshotId" to JsonPrimitive(id(500)), "pumpBinding" to JsonPrimitive("e".repeat(64)),
            "totalBytes" to JsonPrimitive(301), "unscopedCorruptReads" to JsonPrimitive(2),
            "writerFailed" to JsonPrimitive(false))
        val entryVariants = listOf("readId" to JsonPrimitive(id(4)), "byteCount" to JsonPrimitive(99),
            "sha256" to JsonPrimitive("e".repeat(64)), "closed" to JsonPrimitive(false),
            "corrupt" to JsonPrimitive(true), "adverse" to JsonPrimitive(true), "readFailed" to JsonPrimitive(true))
        val documents = variants.map { JsonObject(original + it) } + entryVariants.map { change ->
            val rows = original.getValue("reads").jsonArray.toMutableList()
            rows[2] = JsonObject(rows[2].jsonObject + change)
            JsonObject(original + ("reads" to JsonArray(rows)))
        }
        documents.forEach { document ->
            val page = AapsV3ComboInventoryPage.parse(document.toString().toByteArray(), request.query)
            AapsV3ComboInventoryAssembly().use { assembly ->
                refused { assembly.accept(request, page, at) }
                assertNull(assembly.completed())
            }
        }
    }

    @Test fun inventoryKeyOwnershipTamperingAndEnvelopeBoundsFailClosed() {
        listOf(0, 31, 33).forEach { refused { AapsV3ComboInventoryEnvelope(ByteArray(it)) } }
        val input = secret.copyOf()
        val request = binding()
        AapsV3ComboInventoryEnvelope(input).use { codec ->
            input.fill(0)
            val wire = codec.seal(request, inventory().page(request.query), at)
            val document = Json.parseToJsonElement(wire.decodeToString()).jsonObject
            AapsV3ComboInventoryEnvelope(secret).use { assertEquals(2, it.open(request, wire, at).reads.size) }
            for (field in listOf("saltB64", "ivB64", "ciphertextB64")) {
                val bytes = Base64.getDecoder().decode(document.getValue(field).jsonPrimitive.content)
                bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
                val changed = JsonObject(document + (field to JsonPrimitive(Base64.getEncoder().encodeToString(bytes))))
                refused { codec.open(request, changed.toString().toByteArray(), at) }
            }
            val invalid = listOf(ByteArray(49_153), byteArrayOf(), (wire.decodeToString() + "{}").toByteArray(),
                ("{\"version\":1," + wire.decodeToString().drop(1)).toByteArray(),
                JsonObject(document + ("extra" to JsonPrimitive(1))).toString().toByteArray(),
                JsonObject(document + ("ciphertextB64" to JsonPrimitive("AA=="))).toString().toByteArray())
            invalid.forEach { refused { codec.open(request, it, at) } }
        }
    }

    @Test fun invalidScopeLimitsSnapshotCursorsAndFinalReplayNeverGainAuthority() {
        val source = inventory()
        val request = binding(query = AapsV3ComboReadSpec.Inventory(limit = 32))
        refused { request.copy(counter = 1) }
        refused { request.copy(masterInstallId = client) }
        refused { request.copy(validUntil = at + 30_001) }
        refused { request.copy(query = AapsV3ComboReadSpec.Inventory(limit = 33)) }
        refused { request.copy(query = AapsV3ComboReadSpec.Inventory(source.snapshotId)) }
        refused { source.page(AapsV3ComboReadSpec.Inventory(id(90), id(1))) }
        refused { source.page(AapsV3ComboReadSpec.Inventory(source.snapshotId, id(3))) }
        refused { source.page(AapsV3ComboReadSpec.Inventory(source.snapshotId, id(90))) }
        refused { AapsV3ComboInventory.create(source.snapshotId, source.pumpBinding, source.reads, 4096) }
        refused { AapsV3ComboInventory.create(source.snapshotId, source.pumpBinding,
            (1..17).map { entry(it, 2 * 1024 * 1024) }) }
        val original = source.reads.toMutableList()
        val copy = AapsV3ComboInventory.create(source.snapshotId, source.pumpBinding, original)
        original.clear()
        assertEquals(3, copy.reads.size)
        AapsV3ComboInventoryAssembly().use { assembly ->
            val page = source.page(request.query)
            assembly.accept(request, page, at)
            assembly.accept(request, page, at + 1)
            assertFalse(assembly.completed()!!.deliveryCompletenessVerified)
            refused { assembly.accept(request, page, request.validUntil) }
            assertNull(assembly.completed())
        }
    }

    @Test fun nativeRc1CryptoProviderChecksInventoryInBothDirections() {
        val classpath = System.getenv("AAPS_INTEROP_CLASSPATH")?.let(::File)
        val javaHome = System.getenv("AAPS_INTEROP_JAVA_HOME")
        if (System.getenv("CI") == "true") check(classpath?.isFile == true && !javaHome.isNullOrBlank())
        Assume.assumeTrue("Prepare pristine RC1 crypto driver", classpath?.isFile == true && !javaHome.isNullOrBlank())
        val output = File.createTempFile("inventory-crypto-", ".json")
        val errors = File.createTempFile("inventory-crypto-", ".err")
        var process: Process? = null
        try {
            AapsV3ComboInventoryEnvelope(secret).use { codec ->
                val request = binding(query = AapsV3ComboReadSpec.Inventory(limit = 32))
                val source = inventory()
                process = ProcessBuilder("$javaHome/bin/java", "-cp", classpath!!.readText(),
                    "integration.entries.ComboInventoryCryptoInteropKt").redirectOutput(output).redirectError(errors).start()
                process!!.outputStream.use { it.write(codec.seal(request, source.page(request.query), at)) }
                assertTrue("Native inventory driver timed out", process!!.waitFor(30, TimeUnit.SECONDS))
                assertEquals("Native inventory driver failed", 0, process!!.exitValue())
                AapsV3ComboInventoryAssembly().use { assembly ->
                    assembly.accept(request, codec.open(request, output.readBytes(), at), at)
                    assertEquals(source.sha256, assembly.completed()!!.sha256)
                }
            }
        } finally {
            process?.let { if (it.isAlive) { it.destroyForcibly(); check(it.waitFor(10, TimeUnit.SECONDS)) } }
            output.delete(); errors.delete()
        }
    }
}
