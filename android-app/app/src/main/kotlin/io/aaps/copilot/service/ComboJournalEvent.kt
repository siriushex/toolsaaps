package io.aaps.copilot.service

import java.time.LocalDateTime

internal enum class ComboRecordedBolusKind { QUICK, STANDARD, EXTENDED, MULTIWAVE }
internal enum class ComboRecordedBolusPhase { REQUESTED, INFUSED, STARTED, ENDED }

/** Protocol labels and recorded quantities, not verified physical delivery or inferred meal intent. */
internal sealed class ComboEventDetail {
    class Bolus(
        val kind: ComboRecordedBolusKind,
        val phase: ComboRecordedBolusPhase,
        val recordedAmountTenths: Int,
        val recordedImmediateTenths: Int?,
        val recordedDurationMinutes: Int?,
        val manual: Boolean?
    ) : ComboEventDetail()

    class ClockChange(val replacementLocalTime: LocalDateTime) : ComboEventDetail()
    object Unknown : ComboEventDetail()
    final override fun toString() = "ComboEventDetail(redacted)"
}

/** One CRC/calendar-validated event. Local clocks deliberately have no UTC/delivery-start accessor. */
internal class ComboJournalEvent private constructor(
    val typeId: Int,
    val counter: Long,
    val localTime: LocalDateTime,
    val detail: ComboEventDetail,
    private val original: ByteArray
) {
    fun rawBytes(): ByteArray = original.copyOf()
    override fun toString() = "ComboJournalEvent(redacted)"

    companion object {
        fun decode(raw: ByteArray): ComboJournalEvent {
            require(raw.size == 18)
            val bytes = raw.copyOf()
            require(crc16(bytes, 0, 10) == unsigned16(bytes, 10))
            require(crc16(bytes, 12, 4) == unsigned16(bytes, 16))
            val time = localTime(bytes, 0)
            val type = unsigned16(bytes, 8)
            val counter = (0 until 4).fold(0L) { value, i ->
                value or ((bytes[12 + i].toLong() and 255L) shl (8 * i))
            }
            val detail = when (type) {
                4, 5 -> ComboEventDetail.Bolus(ComboRecordedBolusKind.QUICK,
                    if (type == 4) ComboRecordedBolusPhase.REQUESTED else ComboRecordedBolusPhase.INFUSED,
                    unsigned16(bytes, 4), null, null, null)
                6, 7, 14, 15 -> ComboEventDetail.Bolus(ComboRecordedBolusKind.STANDARD,
                    if (type == 6 || type == 14) ComboRecordedBolusPhase.REQUESTED else ComboRecordedBolusPhase.INFUSED,
                    unsigned16(bytes, 4), null, null, type == 6 || type == 7)
                8, 9, 16, 17 -> ComboEventDetail.Bolus(ComboRecordedBolusKind.EXTENDED,
                    if (type == 8 || type == 16) ComboRecordedBolusPhase.STARTED else ComboRecordedBolusPhase.ENDED,
                    unsigned16(bytes, 4), null, unsigned16(bytes, 6), type == 8 || type == 9)
                10, 11, 18, 19 -> {
                    val a = bytes[4].toInt() and 255
                    val b = bytes[5].toInt() and 255
                    val c = bytes[6].toInt() and 255
                    val d = bytes[7].toInt() and 255
                    ComboEventDetail.Bolus(ComboRecordedBolusKind.MULTIWAVE,
                        if (type == 10 || type == 18) ComboRecordedBolusPhase.STARTED else ComboRecordedBolusPhase.ENDED,
                        a or ((b and 3) shl 8), (b ushr 2) or ((c and 15) shl 6),
                        (c ushr 4) or (d shl 4), type == 10 || type == 11)
                }
                24 -> ComboEventDetail.ClockChange(localTime(bytes, 4))
                else -> ComboEventDetail.Unknown
            }
            return ComboJournalEvent(type, counter, time, detail, bytes)
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

        private fun localTime(bytes: ByteArray, offset: Int): LocalDateTime {
            val a = bytes[offset].toInt() and 255
            val b = bytes[offset + 1].toInt() and 255
            val c = bytes[offset + 2].toInt() and 255
            val d = bytes[offset + 3].toInt() and 255
            return LocalDateTime.of(2000 + (d ushr 2), (c ushr 6) or ((d and 3) shl 2),
                (c ushr 1) and 31, (b ushr 4) or ((c and 1) shl 4),
                (a ushr 6) or ((b and 15) shl 2), a and 63)
        }
    }
}

/** Positions refer to the retained frame/event order. ACK is only the history reader's receipt. */
internal class ComboJournalEventObservation(
    val frameIndex: Int,
    val eventIndex: Int,
    val historyBlockAcknowledged: Boolean,
    val event: ComboJournalEvent
) {
    override fun toString() = "ComboJournalEventObservation(redacted)"
}
