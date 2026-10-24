package io.aaps.copilot.service

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.IOException
import java.util.zip.CRC32
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class AapsV3ComboJournalReadTest {
    private val binding = "a".repeat(64)
    private val id = "00000000-0000-4000-8000-000000000001"
    private val flags = listOf("gapObserved", "clockChangeObserved", "unmappedEventObserved", "interruptedReadObserved", "malformedBlockObserved", "journalWriteFailed")
    private fun observation(phase: String, gap: Boolean = false, finish: Long = 2000, vararg changes: Pair<String, JsonElement>): JsonObject =
        JsonObject(buildJsonObject {
            put("version", 1); put("scope", "reader_session"); put("coverage", "unverified")
            put("state", if (phase == "END") "RETURNED" else "READING")
            put("readStartedAt", 1000); if (phase == "END") put("readFinishedAt", finish)
            put("validatedBlocks", if (phase == "BEGIN") 0 else 1)
            put("confirmedBlocks", if (phase in setOf("CONFIRMED", "END")) 1 else 0)
            put("parsedEvents", 0); put("unmappedEvents", 0)
            flags.forEach { put(it, it == "gapObserved" && gap) }
        } + changes)
    private fun record(phase: String, owner: String = binding, obs: JsonObject = observation(phase), hex: String = "00000000b7b700") = buildJsonObject {
        put("version", 1); put("binding", owner); put("phase", phase); put("observation", obs)
        if (phase == "BLOCK") put("payloadHex", hex)
    }
    private fun frame(raw: ByteArray): ByteArray = ByteArrayOutputStream().also { out ->
        DataOutputStream(out).use { it.writeInt(raw.size); it.write(raw); it.writeInt(CRC32().apply { update(raw) }.value.toInt()) }
    }.toByteArray()
    private fun encode(records: List<JsonObject>) = records.fold(byteArrayOf()) { raw, record -> raw + frame(record.toString().toByteArray()) }
    private fun records() = listOf("BEGIN", "BLOCK", "CONFIRMED", "END").map { record(it) }
    private fun read(records: List<JsonObject>) = AapsV3ComboJournalRead.read(encode(records), binding)
    private fun changed(phase: String, vararg changes: Pair<String, JsonElement>) = record(phase, obs = observation(phase, changes = changes))
    private fun repeatedBlocks(count: Int): List<JsonObject> = buildList {
        add(record("BEGIN"))
        for (i in 1..count) {
            add(record("BLOCK", obs = observation("BLOCK", changes = arrayOf(
                "validatedBlocks" to JsonPrimitive(i), "confirmedBlocks" to JsonPrimitive(i - 1))),
                hex = if (i == count) "00000000b7b700" else "0000e80348b700"))
            add(changed("CONFIRMED", "validatedBlocks" to JsonPrimitive(i), "confirmedBlocks" to JsonPrimitive(i)))
        }
        add(changed("END", "validatedBlocks" to JsonPrimitive(count), "confirmedBlocks" to JsonPrimitive(count)))
    }

    @Test fun closedReadIsUnverifiedAndImmutable() {
        val raw = encode(records())
        val result = AapsV3ComboJournalRead.read(raw, binding)
        assertEquals(ComboJournalIssue.NONE, result.issue)
        assertEquals(AapsV3ComboHistoryStatus.RETAINED_UNVERIFIED, result.status)
        assertTrue(result.closed); assertTrue(result.prefixMatchesExpectedBinding)
        assertFalse(result.pumpBindingVerified); assertFalse(result.deliveryCompletenessVerified)
        assertEquals(listOf("BEGIN", "BLOCK", "CONFIRMED", "END"), result.frames.map { it.phase })
        assertArrayEquals(raw, result.rawBytes())
        raw.fill(0); result.rawBytes().fill(0); result.frames[1].payloadBytes().fill(0)
        assertArrayEquals(encode(records()), result.rawBytes())
        assertEquals(0xb7.toByte(), result.frames[1].payloadBytes()[4])
        assertThrows(UnsupportedOperationException::class.java) { (result.frames as MutableList).clear() }
        assertThrows(UnsupportedOperationException::class.java) { (result.hazards as MutableSet).clear() }
        assertFalse(result.toString().contains(binding)); assertFalse(result.frames[0].toString().contains("1000"))
    }

    @Test fun nativeWriterGoldenReadPreservesBackwardsClockAndExactPayload() {
        val raw = encode(listOf("BEGIN", "BLOCK", "CONFIRMED", "END").map {
            record(it, obs = observation(it, gap = true, finish = 900), hex = "00000000b74800")
        })
        // Independently pinned by the native journal writer test in the compatibility patch.
        assertEquals("905c31d3a78a019f31c3da1963e259e70cd519ab02f44c5b040d21d08b128e2c", AapsV3ComboJournalAssembly.digest(raw))
        val result = AapsV3ComboJournalRead.read(raw, binding)
        assertEquals(900L, result.frames.last().observation.finishedAtMs)
        assertEquals(setOf(AapsV3ComboHistoryHazard.CLOCK_CHANGE, AapsV3ComboHistoryHazard.FIFO_GAP), result.hazards)
        assertFalse(result.deliveryCompletenessVerified)
    }

    @Test fun truncatedFramesNeverLookCompleteAndRetainOnlyValidatedPrefix() {
        val raw = encode(records())
        for (size in 0 until raw.size) {
            val cut = raw.copyOf(size)
            val result = AapsV3ComboJournalRead.read(cut, binding)
            assertFalse("prefix $size", result.closed)
            assertTrue(result.status in setOf(AapsV3ComboHistoryStatus.INVALID, AapsV3ComboHistoryStatus.ADVERSE))
            assertTrue(AapsV3ComboHistoryHazard.UNCLOSED_READ in result.hazards)
            if (result.prefixMatchesExpectedBinding) assertArrayEquals(cut, result.rawBytes())
            else assertTrue(result.rawBytes().isEmpty())
        }
    }

    @Test fun damageDoesNotEraseEarlierHazardsOrUnconfirmedEvidence() {
        val begin = record("BEGIN", obs = observation("BEGIN", gap = true))
        val block = record("BLOCK", obs = observation("BLOCK", gap = true), hex = "00000000b74800")
        val raw = encode(listOf(begin, block)) + byteArrayOf(0, 1)
        val result = AapsV3ComboJournalRead.read(raw, binding)
        assertEquals(ComboJournalIssue.FRAME, result.issue)
        assertTrue(result.hazards.containsAll(setOf(AapsV3ComboHistoryHazard.FIFO_GAP, AapsV3ComboHistoryHazard.CORRUPT_READ,
            AapsV3ComboHistoryHazard.UNCLOSED_READ, AapsV3ComboHistoryHazard.UNCONFIRMED_BLOCK)))
        assertEquals(2, result.frames.size)
    }

    @Test fun checksumDuplicateKeysUnknownFieldsAndMalformedUtf8AreRejected() {
        val begin = record("BEGIN").toString()
        val badBodies = listOf(
            begin.replace("\"version\":1", "\"version\":1,\"version\":1").toByteArray(),
            begin.dropLast(1).plus(",\"extra\":true}").toByteArray(),
            begin.replace("\"version\":1", "\"version\":\"1\"").toByteArray(),
            byteArrayOf(0xc0.toByte(), 0xaf.toByte())
        )
        badBodies.forEach { body ->
            val result = AapsV3ComboJournalRead.read(frame(body), binding)
            assertEquals(AapsV3ComboHistoryStatus.INVALID, result.status)
            assertFalse(result.prefixMatchesExpectedBinding)
        }
        val crc = frame(begin.toByteArray()).also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        assertEquals(ComboJournalIssue.FRAME, AapsV3ComboJournalRead.read(crc, binding).issue)
    }

    @Test fun mismatchedOwnerCannotExposeOtherPairingAndMixedReadCannotPass() {
        val wrong = read(listOf(record("BEGIN", owner = "b".repeat(64))))
        assertEquals(ComboJournalIssue.BINDING, wrong.issue)
        assertFalse(wrong.prefixMatchesExpectedBinding); assertTrue(wrong.frames.isEmpty()); assertTrue(wrong.rawBytes().isEmpty())
        val mixed = read(listOf(record("BEGIN"), record("BLOCK", owner = "b".repeat(64))))
        assertEquals(ComboJournalIssue.BINDING, mixed.issue); assertFalse(mixed.closed)
        assertFalse(mixed.prefixMatchesExpectedBinding)
        assertTrue(mixed.rawBytes().isEmpty()); assertTrue(mixed.frames.isEmpty())
    }

    @Test fun impossibleTransitionsAndClockOriginChangesAreRejected() {
        listOf(listOf("BLOCK"), listOf("CONFIRMED"), listOf("END"), listOf("BEGIN", "BEGIN"),
            listOf("BEGIN", "CONFIRMED"), listOf("BEGIN", "BLOCK", "BLOCK"),
            listOf("BEGIN", "BLOCK", "END"), listOf("BEGIN", "BLOCK", "CONFIRMED", "END", "END")
        ).forEach { phases -> assertEquals(ComboJournalIssue.SEQUENCE, read(phases.map { record(it) }).issue) }
        assertEquals(ComboJournalIssue.COUNTERS, read(listOf(record("BEGIN"), changed("BLOCK", "readStartedAt" to JsonPrimitive(999)))).issue)
        assertEquals(ComboJournalIssue.RECORD, read(listOf(changed("BEGIN", "readStartedAt" to JsonPrimitive(0)))).issue)
    }

    @Test fun counterJumpsDecreasesAndDisappearingFlagsAreRejected() {
        listOf("validatedBlocks" to 2, "confirmedBlocks" to 1, "parsedEvents" to 1, "unmappedEvents" to 1).forEach { (key, value) ->
            val result = read(listOf(record("BEGIN"), changed("BLOCK", key to JsonPrimitive(value))))
            assertNotEquals(ComboJournalIssue.NONE, result.issue)
        }
        assertEquals(ComboJournalIssue.COUNTERS, read(listOf(record("BEGIN"), record("BLOCK"), changed("CONFIRMED", "confirmedBlocks" to JsonPrimitive(0)))).issue)
        assertEquals(ComboJournalIssue.COUNTERS, read(listOf(record("BEGIN", obs = observation("BEGIN", gap = true)), record("BLOCK"))).issue)
        assertEquals(ComboJournalIssue.COUNTERS, read(listOf(changed("BEGIN", "validatedBlocks" to JsonPrimitive(1)))).issue)
    }

    @Test fun unknownEventCountsAreNotMistakenForParsedEvents() {
        val event = "00".repeat(18)
        val records = records().map { frame ->
            val phase = frame.getValue("phase").jsonPrimitive.content
            if (phase == "BEGIN") frame else record(phase, obs = observation(phase, changes = arrayOf(
                "unmappedEvents" to JsonPrimitive(1), "unmappedEventObserved" to JsonPrimitive(true))), hex = "00000000b7b701$event")
        }
        val result = read(records)
        assertEquals(ComboJournalIssue.NONE, result.issue)
        assertEquals(0L, result.frames.last().observation.parsedEvents)
        assertEquals(1L, result.frames.last().observation.unmappedEvents)
        assertTrue(AapsV3ComboHistoryHazard.UNMAPPED_EVENT in result.hazards)
        // Event semantic/date/CRC decoding is a separate gate; this verifier retains exact bytes only.
        assertFalse(result.deliveryCompletenessVerified)
    }

    @Test fun malformedBlockHeadersAndPayloadPlacementAreRejected() {
        listOf("00", "00000000b7b701", "000000007fb700", "00000000b77f00", "01000000b7b700", "00000000b74800").forEach {
            assertEquals(ComboJournalIssue.BLOCK, read(listOf(record("BEGIN"), record("BLOCK", hex = it))).issue)
        }
        assertEquals(ComboJournalIssue.RECORD, read(listOf(JsonObject(record("BEGIN") + ("payloadHex" to JsonPrimitive("00"))))).issue)
        assertEquals(ComboJournalIssue.RECORD, read(listOf(record("BEGIN"), JsonObject(record("BLOCK") - "payloadHex"))).issue)
    }

    @Test fun failedAndUnclosedReadsAreKeptWithoutRepair() {
        val end = changed("END", "state" to JsonPrimitive("FAILED"), "confirmedBlocks" to JsonPrimitive(0), "interruptedReadObserved" to JsonPrimitive(true))
        val result = read(listOf(record("BEGIN"), record("BLOCK"), end))
        assertEquals(ComboJournalIssue.NONE, result.issue); assertTrue(result.closed)
        assertTrue(result.hazards.containsAll(setOf(AapsV3ComboHistoryHazard.FAILED_READ, AapsV3ComboHistoryHazard.INTERRUPTED_READ,
            AapsV3ComboHistoryHazard.UNCONFIRMED_BLOCK)))
        val open = read(listOf(record("BEGIN"), record("BLOCK")))
        assertFalse(open.closed); assertEquals(ComboJournalIssue.NONE, open.issue)
        assertTrue(AapsV3ComboHistoryHazard.UNCONFIRMED_BLOCK in open.hazards)
    }

    @Test fun boundedInputAndFramesDoNotReturnTruncatedSuccess() {
        val oversized = ByteArray(AapsV3ComboJournalRead.MAX_READ_BYTES + 1)
        assertEquals(ComboJournalIssue.LIMIT, AapsV3ComboJournalRead.read(oversized, binding).issue)
        assertEquals(ComboJournalIssue.FRAME, AapsV3ComboJournalRead.read(frame(ByteArray(16385)), binding).issue)
        val result = read(repeatedBlocks(128))
        assertEquals(ComboJournalIssue.LIMIT, result.issue); assertEquals(256, result.frames.size); assertFalse(result.closed)
    }

    @Test fun readerTerminationMustMatchRetainedContinuationHeader() {
        val stillMore = records().toMutableList().also { it[1] = record("BLOCK", hex = "0000e80348b700") }
        assertEquals(ComboJournalIssue.SEQUENCE, read(stillMore).issue)
        val endedTooSoon = repeatedBlocks(2).toMutableList().also { it[1] = record("BLOCK") }
        assertEquals(ComboJournalIssue.SEQUENCE, read(endedTooSoon).issue)
        assertEquals(ComboJournalIssue.NONE, read(repeatedBlocks(2)).issue)
    }

    private fun assembly(raw: ByteArray) = AapsV3ComboJournalAssembly(id, raw.size, AapsV3ComboJournalAssembly.digest(raw), binding)
    private fun append(assembly: AapsV3ComboJournalAssembly, raw: ByteArray, offset: Int, count: Int, end: Boolean = offset + count == raw.size) =
        assembly.append(id, offset, raw.size, AapsV3ComboJournalAssembly.digest(raw), raw.copyOfRange(offset, offset + count), end)

    @Test fun chunkAssemblyIsExactIdempotentAndRequiresExplicitEnd() {
        val raw = encode(records())
        assembly(raw).use { assembly ->
            assertNull(append(assembly, raw, 0, 100))
            assertNull(append(assembly, raw, 0, 100))
            val result = checkNotNull(append(assembly, raw, 100, raw.size - 100))
            assertArrayEquals(raw, result.rawBytes())
            assertSame(result, append(assembly, raw, 100, raw.size - 100))
            assertFalse(assembly.toString().contains(id))
        }
        assembly(raw).use { assembly -> assertThrows(IOException::class.java) { append(assembly, raw, 0, raw.size, end = false) } }
    }

    @Test fun multipleMaximumChunksReconstructMoreThanOneWirePage() {
        val raw = encode(repeatedBlocks(100))
        assertTrue(raw.size > 65536)
        assembly(raw).use { a ->
            var offset = 0
            var result: AapsV3ComboJournalRead? = null
            while (offset < raw.size) {
                val size = minOf(65536, raw.size - offset)
                result = append(a, raw, offset, size)
                offset += size
                if (offset < raw.size) assertNull(result)
            }
            assertEquals(ComboJournalIssue.NONE, checkNotNull(result).issue)
            assertArrayEquals(raw, result.rawBytes())
        }
    }

    @Test fun chunkGapsOverlapSubstitutionAndDigestFailureLatchRefusal() {
        val raw = encode(records())
        val sha = AapsV3ComboJournalAssembly.digest(raw)
        val invalid: List<(AapsV3ComboJournalAssembly) -> Unit> = listOf(
            { append(it, raw, 101, 10) }, { append(it, raw, 50, 100) },
            { it.append(id, 0, raw.size, sha, raw.copyOfRange(0, 100).also { b -> b[0] = 1 }, false) },
            { it.append("00000000-0000-4000-8000-000000000002", 100, raw.size, sha, raw.copyOfRange(100, raw.size), true) },
            { it.append(id, 100, raw.size + 1, sha, raw.copyOfRange(100, raw.size), true) },
            { it.append(id, 100, raw.size, "0".repeat(64), raw.copyOfRange(100, raw.size), true) },
            { it.append(id, 100, raw.size, sha, raw.copyOfRange(100, raw.size).also { b -> b[0] = 1 }, true) }
        )
        invalid.forEach { attempt -> assembly(raw).use { a ->
            append(a, raw, 0, 100)
            assertEquals("history_chunk_invalid", assertThrows(IOException::class.java) { attempt(a) }.message)
            assertThrows(IOException::class.java) { append(a, raw, 100, raw.size - 100) }
        } }
    }

    @Test fun emptyOversizeExcessChunksInvalidManifestAndClosedAssemblerRefuse() {
        val raw = encode(records())
        listOf(0, AapsV3ComboJournalRead.MAX_READ_BYTES + 1).forEach { size ->
            assertThrows(IOException::class.java) { AapsV3ComboJournalAssembly(id, size, "0".repeat(64), binding) }
        }
        assertThrows(IOException::class.java) { AapsV3ComboJournalAssembly("../foreign", raw.size, "0".repeat(64), binding) }
        assertThrows(IOException::class.java) { AapsV3ComboJournalAssembly(id, raw.size, "0".repeat(63), binding) }
        for (bytes in listOf(byteArrayOf(), ByteArray(65537))) assembly(raw).use { a ->
            assertThrows(IOException::class.java) { a.append(id, 0, raw.size, AapsV3ComboJournalAssembly.digest(raw), bytes, false) }
        }
        assembly(raw).use { a ->
            repeat(256) { append(a, raw, it, 1) }
            assertThrows(IOException::class.java) { append(a, raw, 256, 1) }
        }
        val a = assembly(raw); a.close()
        assertThrows(IOException::class.java) { append(a, raw, 0, raw.size) }
    }
}
