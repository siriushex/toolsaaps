package io.aaps.copilot.service

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.IOException
import java.time.LocalDateTime
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

    private fun packedTime(year: Int = 2026, month: Int = 10, day: Int = 24,
                           hour: Int = 12, minute: Int = 30, second: Int = 15) = byteArrayOf(
        (second or ((minute and 3) shl 6)).toByte(),
        ((minute ushr 2) or ((hour and 15) shl 4)).toByte(),
        ((hour ushr 4) or (day shl 1) or ((month and 3) shl 6)).toByte(),
        ((month ushr 2) or ((year - 2000) shl 2)).toByte()
    )

    // Independent forward-polynomial reference, with reflected input and output.
    private fun referenceCrc(bytes: ByteArray): Int {
        var crc = 0xffff
        bytes.forEach { byte ->
            crc = crc xor ((Integer.reverse(byte.toInt() and 255) ushr 24) shl 8)
            repeat(8) { crc = ((crc shl 1) xor if (crc and 0x8000 != 0) 0x1021 else 0) and 0xffff }
        }
        return Integer.reverse(crc) ushr 16
    }

    private fun event(type: Int = 7, time: ByteArray = packedTime(), detail: ByteArray = byteArrayOf(22, 0, 0, 0),
                      counter: Long = 123): ByteArray = ByteArray(18).also { bytes ->
        time.copyInto(bytes); detail.copyInto(bytes, 4)
        bytes[8] = type.toByte(); bytes[9] = (type ushr 8).toByte()
        repeat(4) { bytes[12 + it] = (counter ushr (8 * it)).toByte() }
        for ((start, end, dest) in listOf(Triple(0, 10, 10), Triple(12, 16, 16))) {
            val crc = referenceCrc(bytes.copyOfRange(start, end))
            bytes[dest] = crc.toByte(); bytes[dest + 1] = (crc ushr 8).toByte()
        }
    }

    private fun eventRecords(events: List<ByteArray>, parsed: Int = events.size, unmapped: Int = 0,
                             clock: Boolean = false, unknown: Boolean = unmapped > 0): List<JsonObject> {
        val payload = byteArrayOf(0, 0, 0, 0, 0xb7.toByte(), 0xb7.toByte(), events.size.toByte()) +
            events.fold(byteArrayOf()) { bytes, event -> bytes + event }
        val hex = payload.joinToString("") { "%02x".format(it.toInt() and 255) }
        return listOf("BEGIN", "BLOCK", "CONFIRMED", "END").map { phase ->
            if (phase == "BEGIN") record(phase) else record(phase, hex = hex, obs = observation(phase, changes = arrayOf(
                "parsedEvents" to JsonPrimitive(parsed), "unmappedEvents" to JsonPrimitive(unmapped),
                "clockChangeObserved" to JsonPrimitive(clock), "unmappedEventObserved" to JsonPrimitive(unknown))))
        }
    }

    private fun assertEventsRejected(records: List<JsonObject>) {
        val raw = encode(records)
        val result = AapsV3ComboJournalRead.read(raw, binding)
        assertEquals(AapsV3ComboHistoryStatus.INVALID, result.status)
        assertFalse(result.closed); assertFalse(result.deliveryCompletenessVerified)
        assertEquals(listOf("BEGIN"), result.frames.map { it.phase })
        assertArrayEquals(raw, result.rawBytes())
        assertTrue(AapsV3ComboHistoryHazard.CORRUPT_READ in result.hazards)
    }

    private fun twoEventBlocks(first: List<ByteArray>, second: List<ByteArray>): List<JsonObject> {
        val start = eventRecords(first).dropLast(1).toMutableList()
        val payload = start[1].getValue("payloadHex").jsonPrimitive.content.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        payload[2] = (first.size + second.size).toByte(); payload[4] = 0x48
        start[1] = JsonObject(start[1] + ("payloadHex" to JsonPrimitive(payload.joinToString("") { "%02x".format(it.toInt() and 255) })))
        return start + eventRecords(second).drop(1).map { frame ->
            val block = frame.getValue("phase").jsonPrimitive.content == "BLOCK"
            val obs = JsonObject(frame.getValue("observation").jsonObject + mapOf(
                "validatedBlocks" to JsonPrimitive(2), "confirmedBlocks" to JsonPrimitive(if (block) 1 else 2),
                "parsedEvents" to JsonPrimitive(first.size + second.size)))
            JsonObject(frame + ("observation" to obs))
        }
    }

    private fun withBlockHeader(records: List<JsonObject>, blockIndex: Int, remaining: Int,
                                more: Boolean): List<JsonObject> {
        var seen = 0
        return records.map { record ->
            if (record["phase"]?.jsonPrimitive?.content != "BLOCK" || seen++ != blockIndex) record else {
                val payload = record.getValue("payloadHex").jsonPrimitive.content.chunked(2)
                    .map { it.toInt(16).toByte() }.toByteArray()
                payload[2] = remaining.toByte(); payload[3] = (remaining ushr 8).toByte()
                payload[4] = if (more) 0x48 else 0xb7.toByte()
                JsonObject(record + ("payloadHex" to JsonPrimitive(payload.joinToString("") { "%02x".format(it.toInt() and 255) })))
            }
        }
    }

    @Test fun closedReaderCannotHideDeclaredUnconsumedTail() {
        val records = withBlockHeader(eventRecords(listOf(event())), 0, 4, more = false)
        assertOrderHazards(records, "DECLARED_HISTORY_TAIL")
    }

    @Test fun emptyTerminalBlockCannotHideDeclaredRemainingEvents() {
        assertOrderHazards(withBlockHeader(records(), 0, 65535, more = false), "DECLARED_HISTORY_TAIL")
    }

    @Test fun unexplainedInterBlockCountDropIsAdverseEvenWithConsecutiveEvents() {
        val records = withBlockHeader(twoEventBlocks(listOf(event(counter = 10)), listOf(event(counter = 11))),
            0, 5, more = true)
        assertOrderHazards(records, "REMAINING_COUNT_DISCONTINUITY")
    }

    @Test fun countReductionByAllRawEventsAllowsUnknownEventsAndNewArrivals() {
        val first = listOf(event(counter = 10))
        val second = listOf(event(counter = 11), event(counter = 12), event(counter = 13))
        val growing = withBlockHeader(withBlockHeader(twoEventBlocks(first, second), 0, 2, more = true),
            1, 3, more = false)
        assertEquals(AapsV3ComboHistoryStatus.RETAINED_UNVERIFIED, read(growing).status)
        val knownAndUnknown = twoEventBlocks(listOf(event(counter = 10), event(counter = 11, type = 99)),
            listOf(event(counter = 12))).map { frame ->
            if (frame["phase"]?.jsonPrimitive?.content == "BEGIN") frame else {
                val old = frame.getValue("observation").jsonObject
                JsonObject(frame + ("observation" to JsonObject(old + mapOf(
                    "parsedEvents" to JsonPrimitive(old.getValue("parsedEvents").jsonPrimitive.int - 1),
                    "unmappedEvents" to JsonPrimitive(1), "unmappedEventObserved" to JsonPrimitive(true)))))
            }
        }
        val result = read(knownAndUnknown)
        assertEquals(ComboJournalIssue.NONE, result.issue)
        assertEquals(setOf(AapsV3ComboHistoryHazard.UNMAPPED_EVENT), result.hazards)
    }

    @Test fun aZeroTerminalRemainingCountMayStillCarryTheLastEvents() {
        val records = twoEventBlocks(listOf(event(counter = 10)), listOf(event(counter = 11)))
        assertEquals(AapsV3ComboHistoryStatus.RETAINED_UNVERIFIED, read(records).status)
        assertFalse(read(records).deliveryCompletenessVerified)
    }

    @Test fun unsignedRemainingBoundariesUseRawCountNotMappedCount() {
        val records = eventRecords(listOf(event(counter = 10), event(counter = 11, type = 99)), parsed = 1, unmapped = 1)
        for (remaining in listOf(0, 1, 2, 3, 255, 256, 32767, 32768, 65535)) {
            val result = read(withBlockHeader(records, 0, remaining, more = false))
            assertEquals(ComboJournalIssue.NONE, result.issue)
            assertEquals(remaining > 2, AapsV3ComboHistoryHazard.DECLARED_HISTORY_TAIL in result.hazards)
            assertTrue(AapsV3ComboHistoryHazard.UNMAPPED_EVENT in result.hazards)
        }
    }

    @Test fun damagedTailPreservesAlreadyObservedCountContradiction() {
        val records = withBlockHeader(eventRecords(listOf(event())), 0, 4, more = false).take(2)
        val raw = encode(records) + byteArrayOf(0, 1)
        val result = AapsV3ComboJournalRead.read(raw, binding)
        assertEquals(ComboJournalIssue.FRAME, result.issue)
        assertTrue(AapsV3ComboHistoryHazard.DECLARED_HISTORY_TAIL in result.hazards)
        assertTrue(AapsV3ComboHistoryHazard.UNCONFIRMED_BLOCK in result.hazards)
        assertFalse(result.closed); assertArrayEquals(raw, result.rawBytes())
    }

    @Test fun countContradictionDoesNotMaskLocalClockOrReaderTimeRewind() {
        val records = withBlockHeader(eventRecords(listOf(event(counter = 10, time = packedTime(hour = 13)),
            event(counter = 11, time = packedTime(hour = 12)))), 0, 4, more = false).map { frame ->
            if (frame["phase"]?.jsonPrimitive?.content != "END") frame else JsonObject(frame +
                ("observation" to JsonObject(frame.getValue("observation").jsonObject +
                    ("readFinishedAt" to JsonPrimitive(900)))))
        }
        val result = assertOrderHazards(records, "DECLARED_HISTORY_TAIL", "EVENT_TIME_REWIND", "CLOCK_CHANGE")
        assertEquals(900L, result.frames.last().observation.finishedAtMs)
        assertEquals(13, result.events.first().event.localTime.hour)
        assertEquals(12, result.events.last().event.localTime.hour)
    }

    private fun assertOrderHazards(records: List<JsonObject>, vararg expected: String): AapsV3ComboJournalRead {
        val result = read(records)
        assertEquals(ComboJournalIssue.NONE, result.issue)
        assertTrue(result.closed)
        assertEquals(AapsV3ComboHistoryStatus.ADVERSE, result.status)
        assertTrue(result.hazards.map { it.name }.containsAll(expected.toList()))
        assertArrayEquals(encode(records), result.rawBytes())
        assertFalse(result.pumpBindingVerified); assertFalse(result.deliveryCompletenessVerified)
        return result
    }

    @Test fun quickAndStandardRecordsKeepRequestedSeparateFromInfused() {
        val types = listOf(4, 5, 6, 7, 14, 15)
        val result = read(eventRecords(types.mapIndexed { i, type -> event(type, counter = i.toLong(),
            detail = byteArrayOf(29, 0, 0x34, 0x12)) }))
        assertEquals(ComboJournalIssue.NONE, result.issue)
        for ((index, observation) in result.events.withIndex()) {
            val event = observation.event
            val bolus = event.detail as ComboEventDetail.Bolus
            assertEquals(types[index], event.typeId)
            assertEquals(index.toLong(), event.counter)
            assertEquals(if (index < 2) ComboRecordedBolusKind.QUICK else ComboRecordedBolusKind.STANDARD, bolus.kind)
            assertEquals(if (index % 2 == 0) ComboRecordedBolusPhase.REQUESTED else ComboRecordedBolusPhase.INFUSED, bolus.phase)
            assertEquals(29, bolus.recordedAmountTenths)
            assertEquals(when (index) { 0, 1 -> null; 2, 3 -> true; else -> false }, bolus.manual)
            assertNull(bolus.recordedImmediateTenths); assertNull(bolus.recordedDurationMinutes)
            assertEquals(1, observation.frameIndex); assertEquals(index, observation.eventIndex)
        }
        assertFalse(result.deliveryCompletenessVerified); assertFalse(result.pumpBindingVerified)
    }

    @Test fun extendedRecordsKeepBothAmountAndDurationWithStartEndAndOrigin() {
        val types = listOf(8, 9, 16, 17)
        val result = read(eventRecords(types.mapIndexed { i, type -> event(type, counter = i.toLong(),
            detail = byteArrayOf(0xff.toByte(), 0x80.toByte(), 0x34, 0x12)) }))
        for ((index, observation) in result.events.withIndex()) {
            val bolus = observation.event.detail as ComboEventDetail.Bolus
            assertEquals(ComboRecordedBolusKind.EXTENDED, bolus.kind)
            assertEquals(if (index % 2 == 0) ComboRecordedBolusPhase.STARTED else ComboRecordedBolusPhase.ENDED, bolus.phase)
            assertEquals(33023, bolus.recordedAmountTenths)
            assertEquals(4660, bolus.recordedDurationMinutes)
            assertEquals(index < 2, bolus.manual); assertNull(bolus.recordedImmediateTenths)
        }
        assertEquals(4, result.events.size)
        assertFalse(result.deliveryCompletenessVerified)
    }

    @Test fun multiwavePackedFieldsArePreservedWithoutClampingOrCombiningRecords() {
        val types = listOf(10, 11, 18, 19)
        val detail = byteArrayOf(0xab.toByte(), 0xcd.toByte(), 0xef.toByte(), 0x98.toByte())
        val result = read(eventRecords(types.mapIndexed { i, type -> event(type, counter = i.toLong(), detail = detail) }))
        for ((index, observation) in result.events.withIndex()) {
            val bolus = observation.event.detail as ComboEventDetail.Bolus
            assertEquals(ComboRecordedBolusKind.MULTIWAVE, bolus.kind)
            assertEquals(if (index % 2 == 0) ComboRecordedBolusPhase.STARTED else ComboRecordedBolusPhase.ENDED, bolus.phase)
            assertEquals(427, bolus.recordedAmountTenths)
            assertEquals(1011, bolus.recordedImmediateTenths)
            assertEquals(2446, bolus.recordedDurationMinutes)
            assertEquals(index < 2, bolus.manual)
        }
        assertEquals(4, result.events.size)
        assertFalse(result.deliveryCompletenessVerified)
    }

    @Test fun everyMultiwaveDetailBitMapsToItsOwnFieldWithoutSignedByteExtension() {
        for (bit in 0 until 32) {
            val detail = ByteArray(4).also { it[bit / 8] = (1 shl (bit % 8)).toByte() }
            val bolus = ComboJournalEvent.decode(event(18, detail = detail)).detail as ComboEventDetail.Bolus
            assertEquals(if (bit < 10) 1 shl bit else 0, bolus.recordedAmountTenths)
            assertEquals(if (bit in 10..19) 1 shl (bit - 10) else 0, bolus.recordedImmediateTenths)
            assertEquals(if (bit >= 20) 1 shl (bit - 20) else 0, bolus.recordedDurationMinutes)
        }
        val max = ComboJournalEvent.decode(event(19, detail = ByteArray(4) { -1 })).detail as ComboEventDetail.Bolus
        assertEquals(1023, max.recordedAmountTenths)
        assertEquals(1023, max.recordedImmediateTenths)
        assertEquals(4095, max.recordedDurationMinutes)
    }

    @Test fun ordinaryAmountAndDurationFieldsRemainUnsignedIncludingZeroAndMaximum() {
        for (value in listOf(0, 1, 255, 256, 32767, 32768, 65535)) {
            val detail = byteArrayOf(value.toByte(), (value ushr 8).toByte(), value.toByte(), (value ushr 8).toByte())
            for (type in listOf(4, 5, 6, 7, 8, 9, 14, 15, 16, 17)) {
                val bolus = ComboJournalEvent.decode(event(type, detail = detail)).detail as ComboEventDetail.Bolus
                assertEquals(value, bolus.recordedAmountTenths)
                assertEquals(if (type in listOf(8, 9, 16, 17)) value else null, bolus.recordedDurationMinutes)
            }
        }
    }

    @Test fun localClockChangeKeepsOldAndReplacementClocksWithoutUtcInference() {
        val old = packedTime(2026, 10, 24, 14, 11, 15)
        val replacement = packedTime(2026, 10, 24, 13, 5, 10)
        val result = read(eventRecords(listOf(event(24, time = old, detail = replacement)), clock = true))
        val event = result.events.single().event
        assertEquals(LocalDateTime.of(2026, 10, 24, 14, 11, 15), event.localTime)
        assertEquals(LocalDateTime.of(2026, 10, 24, 13, 5, 10),
            (event.detail as ComboEventDetail.ClockChange).replacementLocalTime)
        assertTrue(AapsV3ComboHistoryHazard.CLOCK_CHANGE in result.hazards)
        assertFalse(result.deliveryCompletenessVerified)
    }

    @Test fun opaqueTypesNeverBecomeInsulinAndKeepUnsignedCounterAndOriginalBytes() {
        for (type in listOf(0, 12, 25, 0x1234, 0xffff)) {
            val raw = event(type, counter = 0xffffffffL, detail = ByteArray(4) { -1 })
            val result = read(eventRecords(listOf(raw), parsed = 0, unmapped = 1))
            val projected = result.events.single().event
            assertSame(ComboEventDetail.Unknown, projected.detail)
            assertEquals(type, projected.typeId); assertEquals(0xffffffffL, projected.counter)
            assertArrayEquals(raw, projected.rawBytes())
        }
    }

    @Test fun historyAcknowledgementIsPerBlockAndNotInfusionOrClosedReadProof() {
        val records = eventRecords(listOf(event(type = 14)))
        val unconfirmed = read(records.take(2))
        assertFalse(unconfirmed.events.single().historyBlockAcknowledged)
        val confirmed = read(records.take(3))
        assertTrue(confirmed.events.single().historyBlockAcknowledged)
        assertFalse(confirmed.closed); assertFalse(confirmed.deliveryCompletenessVerified)
        assertEquals(ComboRecordedBolusPhase.REQUESTED,
            (confirmed.events.single().event.detail as ComboEventDetail.Bolus).phase)
        val two = twoEventBlocks(listOf(event(counter = 1), event(counter = 2)), listOf(event(counter = 3)))
        val interrupted = read(two.take(4))
        assertEquals(listOf(true, true, false), interrupted.events.map { it.historyBlockAcknowledged })
        assertEquals(listOf(1, 1, 3), interrupted.events.map { it.frameIndex })
        assertEquals(listOf(0, 1, 0), interrupted.events.map { it.eventIndex })
        assertFalse(interrupted.deliveryCompletenessVerified)
    }

    @Test fun corruptConfirmationOrTailCannotManufactureAnAcknowledgementOrErasePrefix() {
        val records = eventRecords(listOf(event()))
        val noAck = AapsV3ComboJournalRead.read(encode(records.take(2)) + byteArrayOf(0, 1), binding)
        assertFalse(noAck.events.single().historyBlockAcknowledged)
        val priorAck = AapsV3ComboJournalRead.read(encode(records.take(3)) + byteArrayOf(0, 1), binding)
        assertTrue(priorAck.events.single().historyBlockAcknowledged)
        assertEquals(AapsV3ComboHistoryStatus.INVALID, priorAck.status)
        assertFalse(priorAck.closed); assertFalse(priorAck.deliveryCompletenessVerified)
        val mixed = read(records.take(2) + record("CONFIRMED", owner = "b".repeat(64)))
        assertTrue(mixed.events.isEmpty())
        assertFalse(mixed.prefixMatchesExpectedBinding)
    }

    @Test fun damagedLastEventPublishesNoPartialEventProjection() {
        val bad = event(counter = 2).also { it[16] = (it[16].toInt() xor 1).toByte() }
        val result = read(eventRecords(listOf(event(counter = 1), bad)))
        assertEquals(ComboJournalIssue.EVENT, result.issue)
        assertTrue(result.events.isEmpty())
        assertTrue(result.frames.all { it.events.isEmpty() })
    }

    @Test fun projectionsAndOriginalBytesAreImmutableAndRedacted() {
        val raw = event()
        val result = read(eventRecords(listOf(raw)))
        val observation = result.events.single()
        val projected = observation.event
        projected.rawBytes().fill(0); result.frames[1].payloadBytes().fill(0)
        assertArrayEquals(raw, projected.rawBytes())
        assertThrows(UnsupportedOperationException::class.java) { (result.events as MutableList).clear() }
        assertThrows(UnsupportedOperationException::class.java) { (result.frames[1].events as MutableList).clear() }
        assertEquals("ComboJournalEvent(redacted)", projected.toString())
        assertEquals("ComboJournalEventObservation(redacted)", observation.toString())
        assertEquals("ComboEventDetail(redacted)", projected.detail.toString())
        val decoded = ComboJournalEvent.decode(raw)
        raw.fill(0)
        assertArrayEquals(event(), decoded.rawBytes())
    }

    @Test fun standaloneEventDecoderRejectsWrongLengthAndChecksumBeforeDetails() {
        for (size in listOf(0, 17, 19)) assertThrows(IllegalArgumentException::class.java) {
            ComboJournalEvent.decode(ByteArray(size))
        }
        for (index in listOf(4, 10, 12, 16)) assertThrows(IllegalArgumentException::class.java) {
            ComboJournalEvent.decode(event().also { it[index] = (it[index].toInt() xor 1).toByte() })
        }
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
        val records = eventRecords(listOf(event(type = 0x1234)), parsed = 0, unmapped = 1)
        val result = read(records)
        assertEquals(ComboJournalIssue.NONE, result.issue)
        assertEquals(0L, result.frames.last().observation.parsedEvents)
        assertEquals(1L, result.frames.last().observation.unmappedEvents)
        assertTrue(AapsV3ComboHistoryHazard.UNMAPPED_EVENT in result.hazards)
        assertFalse(result.deliveryCompletenessVerified)
    }

    @Test fun eventChecksumsMatchPinnedNativeReaderVectors() {
        assertEquals(0x02a2, referenceCrc("0123456789abcdef".toByteArray()))
        // AAPS RC1 PumpIOTest history event, unchanged from the upstream protocol test.
        val native = "540e93541d000000070073490f3b01000807".chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        assertArrayEquals(native, event(time = packedTime(2021, 2, 9, 16, 57, 20),
            detail = byteArrayOf(29, 0, 0, 0), counter = 80655))
        val result = read(eventRecords(listOf(native)))
        assertEquals(ComboJournalIssue.NONE, result.issue)
        val projected = result.events.single().event
        assertEquals(LocalDateTime.of(2021, 2, 9, 16, 57, 20), projected.localTime)
        assertEquals(80655L, projected.counter)
        val bolus = projected.detail as ComboEventDetail.Bolus
        assertEquals(ComboRecordedBolusKind.STANDARD, bolus.kind)
        assertEquals(ComboRecordedBolusPhase.INFUSED, bolus.phase)
        assertEquals(29, bolus.recordedAmountTenths)
        assertEquals(true, bolus.manual)
        assertEquals(AapsV3ComboHistoryStatus.RETAINED_UNVERIFIED, result.status)
        assertFalse(result.pumpBindingVerified); assertFalse(result.deliveryCompletenessVerified)
    }

    @Test fun everySingleBitEventCorruptionIsRejectedEvenWithValidOuterFrameCrc() {
        for (type in listOf(7, 0x1234)) for (index in 0 until 18) for (bit in 0..7) {
            val damaged = event(type).also { it[index] = (it[index].toInt() xor (1 shl bit)).toByte() }
            assertEventsRejected(eventRecords(listOf(damaged), parsed = if (type == 7) 1 else 0,
                unmapped = if (type == 7) 0 else 1))
        }
    }

    @Test fun invalidLocalDatesAreRejectedWithCorrectChecksumsIncludingUnknownEvents() {
        val invalid = listOf(packedTime(month = 0), packedTime(month = 13), packedTime(day = 0),
            packedTime(month = 4, day = 31), packedTime(month = 2, day = 30), packedTime(year = 2025, month = 2, day = 29),
            packedTime(hour = 24), packedTime(hour = 31), packedTime(minute = 60), packedTime(minute = 63),
            packedTime(second = 60), packedTime(second = 63))
        for (type in listOf(7, 0xffff)) for (time in invalid)
            assertEventsRejected(eventRecords(listOf(event(type, time)), parsed = if (type == 7) 1 else 0,
                unmapped = if (type == 7) 0 else 1))
    }

    @Test fun validLocalDatesIncludeLeapDayYearLimitsAndBackwardsOrderWithoutUtcInference() {
        val times = listOf(packedTime(2063, 12, 31, 23, 59, 59), packedTime(2000, 1, 1, 0, 0, 0),
            packedTime(2000, 2, 29), packedTime(2024, 2, 29))
        val records = eventRecords(times.mapIndexed { i, time -> event(time = time, counter = i.toLong()) })
        val result = read(records)
        assertEquals(ComboJournalIssue.NONE, result.issue)
        assertArrayEquals(encode(records), result.rawBytes())
        assertFalse(result.deliveryCompletenessVerified)
    }

    @Test fun everyCalendarDateInWireRangeAndEveryTimeFieldBoundaryIsAccepted() {
        var date = LocalDateTime.of(2000, 1, 1, 0, 0)
        // Exercise the date decoder across month, leap-year and year-bit boundaries in bounded blocks.
        while (date.year <= 2063) {
            val events = buildList {
                repeat(64) {
                    if (date.year <= 2063) {
                        add(event(time = packedTime(date.year, date.monthValue, date.dayOfMonth, 23, 59, 59)))
                        date = date.plusDays(1)
                    }
                }
            }
            assertEquals(ComboJournalIssue.NONE, read(eventRecords(events)).issue)
        }
        for (value in 0..59) {
            assertEquals(ComboJournalIssue.NONE, read(eventRecords(listOf(
                event(time = packedTime(hour = value % 24, minute = value, second = value))))).issue)
        }
    }

    @Test fun knownTypesAndUnmappedTypesMustMatchSeparateReaderCounters() {
        val known = listOf(4, 5, 6, 7, 8, 9, 10, 11, 14, 15, 16, 17, 18, 19, 24)
        val events = known.map { event(it, detail = if (it == 24) packedTime() else byteArrayOf(22, 0, 0, 0)) } +
            listOf(event(0), event(12), event(25), event(0x1234), event(0xffff))
        val result = read(eventRecords(events, parsed = known.size, unmapped = 5, clock = true))
        assertEquals(ComboJournalIssue.NONE, result.issue)
        assertTrue(result.hazards.containsAll(setOf(AapsV3ComboHistoryHazard.CLOCK_CHANGE, AapsV3ComboHistoryHazard.UNMAPPED_EVENT)))
        assertEventsRejected(eventRecords(listOf(event(7)), parsed = 0, unmapped = 1))
        assertEventsRejected(eventRecords(listOf(event(25)), parsed = 1, unmapped = 0))
        assertEventsRejected(eventRecords(listOf(event(25)), parsed = 0, unmapped = 1, unknown = false))
    }

    @Test fun clockChangeDetailRequiresValidDateAndStickyClockFlag() {
        assertEventsRejected(eventRecords(listOf(event(24, detail = packedTime(month = 2, day = 30))), clock = true))
        assertEventsRejected(eventRecords(listOf(event(24, detail = packedTime())), clock = false))
        val records = eventRecords(listOf(event(24, detail = packedTime(2020, 1, 1))), clock = true)
        val result = read(records)
        assertEquals(ComboJournalIssue.NONE, result.issue)
        assertTrue(AapsV3ComboHistoryHazard.CLOCK_CHANGE in result.hazards)
        assertArrayEquals(encode(records), result.rawBytes())
    }

    @Test fun lastDamagedEventRejectsEntireBlockNotOnlyItsTail() {
        val damaged = event().also { it[16] = (it[16].toInt() xor 1).toByte() }
        assertEventsRejected(eventRecords(List(24) { event(counter = it.toLong()) } + damaged))
    }

    @Test fun identicalRepeatedEventIsAdverseButRetainedWithoutDeduplication() {
        val repeated = event(counter = 12)
        val result = assertOrderHazards(eventRecords(listOf(repeated, repeated)), "EVENT_DUPLICATE")
        assertEquals(2L, result.frames.last().observation.parsedEvents)
        assertEquals(7 + 2 * 18, result.frames[1].payloadBytes().size)
        assertEquals(setOf("EVENT_DUPLICATE"), result.hazards.map { it.name }.toSet())
    }

    @Test fun reusedCounterWithDifferentTimestampTypeOrAmountRemainsAmbiguous() {
        val first = event(counter = 12)
        listOf(event(counter = 12, time = packedTime(minute = 31)), event(type = 15, counter = 12),
            event(counter = 12, detail = byteArrayOf(23, 0, 0, 0))).forEach { changed ->
            val result = assertOrderHazards(eventRecords(listOf(first, changed)), "EVENT_COUNTER_REUSED")
            assertFalse(result.hazards.any { it.name == "EVENT_DUPLICATE" })
        }
    }

    @Test fun unsignedCounterBoundariesAndGapsDoNotInventLossOrCompleteness() {
        val records = eventRecords(listOf(0L, 9L, 0x7fffffffL, 0x80000000L, 0xffffffffL).map { event(counter = it) })
        val result = read(records)
        assertEquals(AapsV3ComboHistoryStatus.RETAINED_UNVERIFIED, result.status)
        assertTrue(result.hazards.isEmpty())
        assertFalse(result.deliveryCompletenessVerified)
    }

    @Test fun decreasingCounterIncludingPossibleRolloverIsNeverSilentlyUnwrapped() {
        for (counters in listOf(listOf(100L, 99L), listOf(0xffffffffL, 0L)))
            assertOrderHazards(eventRecords(counters.map { event(counter = it) }), "EVENT_COUNTER_REWIND")
    }

    @Test fun backwardsPumpTimeIsAdverseEvenWhenCountersAdvanceAndReaderClockIsNormal() {
        val result = assertOrderHazards(eventRecords(listOf(
            event(counter = 1, time = packedTime(hour = 13)), event(counter = 2, time = packedTime(hour = 12)))),
            "EVENT_TIME_REWIND")
        assertEquals(setOf("EVENT_TIME_REWIND"), result.hazards.map { it.name }.toSet())
        assertEquals(2000L, result.frames.last().observation.finishedAtMs)
    }

    @Test fun orderAuditContinuesAcrossConfirmedBlockBoundary() {
        assertOrderHazards(twoEventBlocks(listOf(event(counter = 5)), listOf(event(counter = 5))), "EVENT_DUPLICATE")
        assertOrderHazards(twoEventBlocks(listOf(event(counter = 5, time = packedTime(hour = 13))),
            listOf(event(counter = 4, time = packedTime(hour = 12)))), "EVENT_COUNTER_REWIND", "EVENT_TIME_REWIND")
    }

    @Test fun nonAdjacentCounterReuseIsNotHiddenByAnInterveningEvent() {
        val first = event(counter = 5)
        assertOrderHazards(twoEventBlocks(listOf(first, event(counter = 6)), listOf(first)),
            "EVENT_DUPLICATE", "EVENT_COUNTER_REWIND")
        assertOrderHazards(twoEventBlocks(listOf(first, event(counter = 6)), listOf(event(type = 15, counter = 5))),
            "EVENT_COUNTER_REUSED", "EVENT_COUNTER_REWIND")
    }

    @Test fun unknownEventsParticipateInOrderAuditWithoutBecomingKnownDelivery() {
        val unknown = event(type = 0x1234, counter = 12)
        val result = assertOrderHazards(eventRecords(listOf(unknown, unknown), parsed = 0, unmapped = 2),
            "EVENT_DUPLICATE", "UNMAPPED_EVENT")
        assertEquals(0L, result.frames.last().observation.parsedEvents)
        assertEquals(2L, result.frames.last().observation.unmappedEvents)
    }

    @Test fun earlierOrderHazardsSurviveLaterCorruptionButInvalidBlockCannotAddOrderEvidence() {
        val first = event(counter = 5)
        val damaged = event(counter = 6).also { it[17] = (it[17].toInt() xor 1).toByte() }
        val afterDuplicate = read(twoEventBlocks(listOf(first, first), listOf(damaged)))
        assertEquals(ComboJournalIssue.EVENT, afterDuplicate.issue)
        assertTrue(afterDuplicate.hazards.any { it.name == "EVENT_DUPLICATE" })
        assertEquals(3, afterDuplicate.frames.size)
        val partial = read(twoEventBlocks(listOf(first), listOf(first, damaged)))
        assertEquals(ComboJournalIssue.EVENT, partial.issue)
        assertFalse(partial.hazards.any { it.name == "EVENT_DUPLICATE" })
        assertEquals(3, partial.frames.size)
    }

    @Test fun orderStateIsScopedToOneReadAndDoesNotLeakIntoAnother() {
        val one = event(counter = 5)
        assertOrderHazards(eventRecords(listOf(one, one)), "EVENT_DUPLICATE")
        val next = read(eventRecords(listOf(one)))
        assertEquals(AapsV3ComboHistoryStatus.RETAINED_UNVERIFIED, next.status)
        assertTrue(next.hazards.isEmpty())
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
