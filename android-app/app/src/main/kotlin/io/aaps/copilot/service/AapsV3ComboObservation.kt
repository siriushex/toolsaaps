package io.aaps.copilot.service

import io.aaps.copilot.data.repository.HistoryJson
import io.aaps.copilot.data.repository.LocalNightscoutV3Store
import java.util.Collections
import kotlinx.serialization.json.JsonObject

/** Reader clocks and counters, not pump event times or physical delivery evidence. */
internal class AapsV3ComboObservation private constructor(
    val state: String, val startedAtMs: Long?, val finishedAtMs: Long?,
    val validatedBlocks: Long, val confirmedBlocks: Long, val parsedEvents: Long, val unmappedEvents: Long,
    flags: Set<AapsV3ComboHistoryHazard>, hazards: Set<AapsV3ComboHistoryHazard>
) {
    val flags: Set<AapsV3ComboHistoryHazard> = Collections.unmodifiableSet(flags.toSet())
    val hazards: Set<AapsV3ComboHistoryHazard> = Collections.unmodifiableSet(hazards.toSet())
    override fun toString() = "ComboObservation(redacted)"

    companion object {
        private val FLAGS = mapOf(
            "gapObserved" to AapsV3ComboHistoryHazard.FIFO_GAP,
            "clockChangeObserved" to AapsV3ComboHistoryHazard.CLOCK_CHANGE,
            "unmappedEventObserved" to AapsV3ComboHistoryHazard.UNMAPPED_EVENT,
            "interruptedReadObserved" to AapsV3ComboHistoryHazard.INTERRUPTED_READ,
            "malformedBlockObserved" to AapsV3ComboHistoryHazard.MALFORMED_BLOCK,
            "journalWriteFailed" to AapsV3ComboHistoryHazard.STORAGE_FAILED
        )
        private val FIELDS = FLAGS.keys + setOf("version", "coverage", "scope", "state", "readStartedAt", "readFinishedAt",
            "validatedBlocks", "confirmedBlocks", "parsedEvents", "unmappedEvents")

        fun parse(body: JsonObject): AapsV3ComboObservation {
            require(body.keys.all { it in FIELDS })
            require(HistoryJson.long(body.getValue("version")) == 1L)
            require(HistoryJson.string(body.getValue("coverage")) == "unverified")
            require(HistoryJson.string(body.getValue("scope")) == "reader_session")
            val state = HistoryJson.string(body.getValue("state"))
            require(state in setOf("NOT_READ", "READING", "RETURNED", "FAILED"))
            fun time(key: String) = body[key]?.let(HistoryJson::long)?.also { require(it in 1..LocalNightscoutV3Store.MAX_TIME) }
            val start = time("readStartedAt")
            val end = time("readFinishedAt")
            require(if (state == "NOT_READ") start == null && end == null else start != null)
            require(if (state in setOf("RETURNED", "FAILED")) end != null else end == null)
            fun count(key: String, max: Long = Int.MAX_VALUE.toLong()) = HistoryJson.long(body.getValue(key)).also { require(it in 0..max) }
            val validated = count("validatedBlocks")
            val confirmed = count("confirmedBlocks", validated)
            val parsed = count("parsedEvents")
            val unmapped = count("unmappedEvents")
            if (state == "NOT_READ") require(validated == 0L && confirmed == 0L && parsed == 0L && unmapped == 0L)
            val flags = FLAGS.filter { HistoryJson.bool(body.getValue(it.key)) }.values.toSet()
            val hazards = flags.toMutableSet()
            if (state == "FAILED") hazards += AapsV3ComboHistoryHazard.FAILED_READ
            if (state in setOf("RETURNED", "FAILED") && validated != confirmed) hazards += AapsV3ComboHistoryHazard.UNCONFIRMED_BLOCK
            if (unmapped > 0) hazards += AapsV3ComboHistoryHazard.UNMAPPED_EVENT
            if (start != null && end != null && end < start) hazards += AapsV3ComboHistoryHazard.CLOCK_CHANGE
            return AapsV3ComboObservation(state, start, end, validated, confirmed, parsed, unmapped, flags, hazards)
        }
    }
}
