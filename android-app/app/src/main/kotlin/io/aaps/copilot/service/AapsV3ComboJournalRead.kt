package io.aaps.copilot.service

import io.aaps.copilot.data.repository.HistoryJson
import java.io.ByteArrayInputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.IOException
import java.security.MessageDigest
import java.time.LocalDateTime
import java.util.Collections
import java.util.zip.CRC32

internal enum class ComboJournalIssue { NONE, LIMIT, FRAME, RECORD, BINDING, SEQUENCE, COUNTERS, BLOCK, EVENT }

internal class ComboJournalFrame(val phase: String, val observation: AapsV3ComboObservation, payload: ByteArray) {
    private val content = payload.copyOf()
    fun payloadBytes(): ByteArray = content.copyOf()
    override fun toString() = "ComboJournalFrame(redacted)"
}

/** Validates retained event integrity, not clock epochs, pump identity or physical delivery. */
internal class AapsV3ComboJournalRead private constructor(
    val issue: ComboJournalIssue, val closed: Boolean, val prefixMatchesExpectedBinding: Boolean,
    frames: List<ComboJournalFrame>, hazards: Set<AapsV3ComboHistoryHazard>, raw: ByteArray
) {
    val frames: List<ComboJournalFrame> = Collections.unmodifiableList(frames.toList())
    val hazards: Set<AapsV3ComboHistoryHazard> = Collections.unmodifiableSet(hazards.toSet())
    private val original = raw.copyOf()
    fun rawBytes(): ByteArray = original.copyOf()
    val pumpBindingVerified: Boolean get() = false
    val deliveryCompletenessVerified: Boolean get() = false
    val status: AapsV3ComboHistoryStatus get() = when {
        issue == ComboJournalIssue.LIMIT -> AapsV3ComboHistoryStatus.LIMIT_REACHED
        issue != ComboJournalIssue.NONE -> AapsV3ComboHistoryStatus.INVALID
        hazards.isNotEmpty() -> AapsV3ComboHistoryStatus.ADVERSE
        else -> AapsV3ComboHistoryStatus.RETAINED_UNVERIFIED
    }
    override fun toString() = "ComboJournalRead(status=$status, redacted)"

    companion object {
        const val MAX_READ_BYTES = 2 * 1024 * 1024
        private const val MAX_FRAME_BYTES = 16384
        private const val MAX_FRAMES = 256
        private val BINDING = Regex("[0-9a-f]{64}")
        private val HEX = Regex("(?:[0-9a-f]{2}){1,4096}")

        fun read(bytes: ByteArray, expectedBinding: String): AapsV3ComboJournalRead {
            val frames = mutableListOf<ComboJournalFrame>()
            val hazards = mutableSetOf<AapsV3ComboHistoryHazard>()
            var scope = false
            var issue = ComboJournalIssue.BINDING
            // Bound before copying. All subsequent checks consume the same immutable bytes.
            if (bytes.size > MAX_READ_BYTES) return AapsV3ComboJournalRead(
                ComboJournalIssue.LIMIT, false, false, emptyList(), emptySet(), byteArrayOf())
            val raw = bytes.copyOf()
            try {
                require(BINDING.matches(expectedBinding))
                DataInputStream(ByteArrayInputStream(raw)).use { input ->
                    issue = ComboJournalIssue.FRAME
                    require(input.available() > 0)
                    while (input.available() > 0) {
                        issue = ComboJournalIssue.LIMIT
                        require(frames.size < MAX_FRAMES)
                        issue = ComboJournalIssue.FRAME
                        val length = input.readInt()
                        require(length in 1..MAX_FRAME_BYTES && input.available() >= length + 4)
                        val body = ByteArray(length).also(input::readFully)
                        require(input.readInt() == CRC32().apply { update(body) }.value.toInt())
                        issue = ComboJournalIssue.RECORD
                        val record = HistoryJson.parse(body, MAX_FRAME_BYTES)
                        HistoryJson.fields(record, setOf("version", "binding", "phase", "observation"), setOf("payloadHex"))
                        require(HistoryJson.long(record.getValue("version")) == 1L)
                        issue = ComboJournalIssue.BINDING
                        require(HistoryJson.string(record.getValue("binding")) == expectedBinding)
                        issue = ComboJournalIssue.RECORD
                        val phase = HistoryJson.string(record.getValue("phase"))
                        val observation = AapsV3ComboObservation.parse(HistoryJson.obj(record.getValue("observation")))
                        val hex = record["payloadHex"]?.let(HistoryJson::string)
                        require(if (phase == "BLOCK") hex != null && HEX.matches(hex) else hex == null)
                        val payload = hex?.let { value -> ByteArray(value.length / 2) { value.substring(it * 2, it * 2 + 2).toInt(16).toByte() } }
                            ?: byteArrayOf()
                        issue = ComboJournalIssue.SEQUENCE
                        val previous = frames.lastOrNull()
                        require(when (phase) {
                            "BEGIN" -> previous == null
                            "BLOCK" -> previous?.phase in setOf("BEGIN", "CONFIRMED")
                            "CONFIRMED" -> previous?.phase == "BLOCK"
                            "END" -> previous != null && previous.phase != "END"
                            else -> false
                        })
                        require(if (phase == "END") observation.state in setOf("RETURNED", "FAILED") else observation.state == "READING")
                        if (phase == "BLOCK" && previous?.phase == "CONFIRMED") require(!readerReachedEnd(frames))
                        if (phase == "END" && observation.state == "RETURNED")
                            require(previous?.phase == "CONFIRMED" && readerReachedEnd(frames))
                        issue = ComboJournalIssue.COUNTERS
                        validateCounters(phase, previous?.observation, observation)
                        if (phase == "BLOCK") {
                            issue = ComboJournalIssue.BLOCK
                            require(payload.size >= 7)
                            val count = payload[6].toInt() and 255
                            require(payload.size == 7 + 18 * count)
                            require((payload[4].toInt() and 255) in setOf(0x48, 0xb7))
                            require((payload[5].toInt() and 255) in setOf(0x48, 0xb7))
                            require(payload[0] == 0.toByte() && payload[1] == 0.toByte())
                            val before = checkNotNull(previous).observation
                            require(observation.parsedEvents - before.parsedEvents + observation.unmappedEvents - before.unmappedEvents == count.toLong())
                            require(payload[5] != 0x48.toByte() || AapsV3ComboHistoryHazard.FIFO_GAP in observation.flags)
                            issue = ComboJournalIssue.EVENT
                            validateEvents(payload, before, observation)
                        }
                        frames += ComboJournalFrame(phase, observation, payload)
                        hazards += observation.hazards
                        scope = true
                    }
                }
                issue = ComboJournalIssue.NONE
            } catch (_: Exception) {
                hazards += AapsV3ComboHistoryHazard.CORRUPT_READ
            }
            if (issue == ComboJournalIssue.BINDING) {
                // A mixed-owner file cannot be exported under its first validated owner.
                scope = false
                frames.clear()
            }
            val closed = issue == ComboJournalIssue.NONE && frames.lastOrNull()?.phase == "END"
            if (!closed) hazards += AapsV3ComboHistoryHazard.UNCLOSED_READ
            frames.lastOrNull()?.observation?.let {
                if (it.validatedBlocks != it.confirmedBlocks) hazards += AapsV3ComboHistoryHazard.UNCONFIRMED_BLOCK
            }
            // A validated prefix scopes a damaged tail. It is not independent pairing authentication.
            return AapsV3ComboJournalRead(issue, closed, scope, frames, hazards, if (scope) raw else byteArrayOf())
        }

        private fun validateEvents(payload: ByteArray, before: AapsV3ComboObservation, current: AapsV3ComboObservation) {
            var parsed = 0L
            var unmapped = 0L
            for (offset in 7 until payload.size step 18) {
                require(crc16(payload, offset, 10) == unsigned16(payload, offset + 10))
                require(crc16(payload, offset + 12, 4) == unsigned16(payload, offset + 16))
                validateLocalTime(payload, offset)
                when (unsigned16(payload, offset + 8)) {
                    4, 5, 6, 7, 8, 9, 10, 11, 14, 15, 16, 17, 18, 19 -> parsed++
                    24 -> {
                        validateLocalTime(payload, offset + 4)
                        require(AapsV3ComboHistoryHazard.CLOCK_CHANGE in current.flags)
                        parsed++
                    }
                    else -> unmapped++
                }
            }
            require(current.parsedEvents - before.parsedEvents == parsed)
            require(current.unmappedEvents - before.unmappedEvents == unmapped)
            require(unmapped == 0L || AapsV3ComboHistoryHazard.UNMAPPED_EVENT in current.flags)
        }

        private fun unsigned16(bytes: ByteArray, offset: Int) =
            (bytes[offset].toInt() and 255) or ((bytes[offset + 1].toInt() and 255) shl 8)

        private fun crc16(bytes: ByteArray, offset: Int, length: Int): Int {
            // CRC-16/MCRF4XX: reflected 0x1021 polynomial, 0xffff seed, no final xor.
            var crc = 0xffff
            for (i in offset until offset + length) {
                crc = crc xor (bytes[i].toInt() and 255)
                repeat(8) { crc = (crc ushr 1) xor if (crc and 1 != 0) 0x8408 else 0 }
            }
            return crc
        }

        private fun validateLocalTime(bytes: ByteArray, offset: Int) {
            val a = bytes[offset].toInt() and 255
            val b = bytes[offset + 1].toInt() and 255
            val c = bytes[offset + 2].toInt() and 255
            val d = bytes[offset + 3].toInt() and 255
            // Calendar validity only. A pump-local time is not an authenticated UTC instant.
            LocalDateTime.of(2000 + (d ushr 2), (c ushr 6) or ((d and 3) shl 2),
                (c ushr 1) and 31, (b ushr 4) or ((c and 1) shl 4),
                (a ushr 6) or ((b and 15) shl 2), a and 63)
        }

        private fun readerReachedEnd(frames: List<ComboJournalFrame>): Boolean {
            val index = frames.indexOfLast { it.phase == "BLOCK" }
            require(index > 0)
            val block = frames[index]
            val payload = block.payloadBytes()
            val remaining = (payload[2].toInt() and 255) or ((payload[3].toInt() and 255) shl 8)
            val parsed = block.observation.parsedEvents - frames[index - 1].observation.parsedEvents
            // Match the existing native reader's termination rule, not physical coverage.
            return payload[4] != 0x48.toByte() || remaining <= parsed
        }

        private fun validateCounters(phase: String, previous: AapsV3ComboObservation?, current: AapsV3ComboObservation) {
            if (previous == null) {
                require(current.validatedBlocks == 0L && current.confirmedBlocks == 0L && current.parsedEvents == 0L && current.unmappedEvents == 0L)
                return
            }
            require(current.startedAtMs == previous.startedAtMs && current.flags.containsAll(previous.flags))
            require(current.validatedBlocks == previous.validatedBlocks + if (phase == "BLOCK") 1 else 0)
            require(current.confirmedBlocks == previous.confirmedBlocks + if (phase == "CONFIRMED") 1 else 0)
            require(current.parsedEvents >= previous.parsedEvents && current.unmappedEvents >= previous.unmappedEvents)
            if (phase != "BLOCK") require(current.parsedEvents == previous.parsedEvents && current.unmappedEvents == previous.unmappedEvents)
        }
    }
}

/** One bounded retained read. Transport must authenticate the manifest and destination separately. */
internal class AapsV3ComboJournalAssembly(
    private val readId: String, private val expectedSize: Int, private val expectedSha256: String,
    private val expectedBinding: String
) : Closeable {
    private var buffer: ByteArray?
    private var received = 0
    private val chunks = linkedMapOf<Int, Int>()
    private var result: AapsV3ComboJournalRead? = null
    init {
        try {
            HistoryJson.uuid(readId)
            require(expectedSize in 1..AapsV3ComboJournalRead.MAX_READ_BYTES)
            require(expectedSha256.matches(Regex("[0-9a-f]{64}")) && expectedBinding.matches(Regex("[0-9a-f]{64}")))
        } catch (_: Exception) { throw IOException("history_manifest_invalid") }
        buffer = ByteArray(expectedSize)
    }

    @Synchronized
    fun append(id: String, offset: Int, totalSize: Int, sha256: String, bytes: ByteArray, endOfRead: Boolean): AapsV3ComboJournalRead? {
        try {
            val raw = checkNotNull(buffer)
            require(id == readId && totalSize == expectedSize && sha256 == expectedSha256)
            require(bytes.size in 1..65536 && offset in 0 until expectedSize && bytes.size <= expectedSize - offset)
            require(endOfRead == (offset + bytes.size == expectedSize))
            if (offset < received) {
                require(chunks[offset] == bytes.size && bytes.indices.all { raw[offset + it] == bytes[it] })
                return result
            }
            require(offset == received && chunks.size < 256 && result == null)
            bytes.copyInto(raw, offset)
            chunks[offset] = bytes.size
            received += bytes.size
            if (!endOfRead) return null
            require(digest(raw) == expectedSha256)
            return AapsV3ComboJournalRead.read(raw, expectedBinding).also { result = it }
        } catch (_: Exception) {
            close()
            throw IOException("history_chunk_invalid")
        }
    }

    @Synchronized override fun close() {
        buffer?.fill(0); buffer = null; result = null; chunks.clear()
    }
    override fun toString() = "ComboJournalAssembly(redacted)"

    companion object {
        fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}
