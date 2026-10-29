package io.aaps.copilot.service

import io.aaps.copilot.data.repository.HistoryJson
import io.aaps.copilot.data.repository.LocalNightscoutV3Store
import io.aaps.copilot.data.repository.LocalV3RetainedSnapshot
import java.time.Instant
import java.util.Collections
import kotlinx.serialization.json.*

enum class AapsV3ComboHistoryStatus { UNCHECKED, NOT_OBSERVED, RETAINED_UNVERIFIED, ADVERSE, INVALID, LATE_EVIDENCE, LIMIT_REACHED, ERROR }

internal enum class AapsV3ComboHistoryHazard {
    STORAGE_FAILED, UNCLOSED_READ, CORRUPT_READ, UNSCOPED_CORRUPTION, ADVERSE_READ, FAILED_READ,
    FIFO_GAP, CLOCK_CHANGE, UNMAPPED_EVENT, INTERRUPTED_READ, MALFORMED_BLOCK, UNCONFIRMED_BLOCK,
    EVENT_DUPLICATE, EVENT_COUNTER_REUSED, EVENT_COUNTER_REWIND, EVENT_TIME_REWIND, EVENT_COUNTER_GAP,
    POST_CLOCK_CHANGE_REWIND, READER_TIME_INCONSISTENT,
    DECLARED_HISTORY_TAIL, REMAINING_COUNT_DISCONTINUITY
}

/** These are retained uploader diagnostics, not current-pump identity or delivery receipts. */
internal class AapsV3ComboHistoryRead(
    val status: AapsV3ComboHistoryStatus, val retainedRevision: Long?, val observedSnapshots: Int = 0,
    hazards: Set<AapsV3ComboHistoryHazard> = emptySet()
) {
    val hazards: Set<AapsV3ComboHistoryHazard> = Collections.unmodifiableSet(hazards.toSet())
    val pumpBindingVerified: Boolean get() = false
    val deliveryCompletenessVerified: Boolean get() = false
    override fun toString() = "AapsV3ComboHistoryRead(status=$status, redacted)"
}

/** Keep adverse observations across newer clean snapshots; never sum cumulative journal counters. */
internal class AapsV3ComboHistoryTimeline(snapshot: LocalV3RetainedSnapshot) {
    val revision = snapshot.collectionRevision
    private data class Entry(val atMs: Long, val hazards: Set<AapsV3ComboHistoryHazard>)
    private val entries: List<Entry>
    private val failure: AapsV3ComboHistoryStatus?

    init {
        var invalid = revision !in 0..LocalNightscoutV3Store.MAX_TIME
        val rows = (snapshot as? LocalV3RetainedSnapshot.Complete)?.documents.orEmpty()
        invalid = invalid || rows.map { it.identifier }.distinct().size != rows.size
        entries = rows.mapNotNull { row ->
            try {
                require(row.collection == "devicestatus" && row.source == "local-v3-owner" && !row.deleted)
                require(row.identifier.isNotBlank() && row.createdRevision in 1..row.modifiedRevision && row.modifiedRevision <= revision)
                val at = checkNotNull(row.eventAtMs).also { require(it in 1..LocalNightscoutV3Store.MAX_TIME) }
                val bytes = HistoryJson.encodeUtf8(row.payloadJson)
                require(bytes.size == row.payloadBytes)
                val body = HistoryJson.parse(bytes, LocalNightscoutV3Store.MAX_PAYLOAD_BYTES)
                require(HistoryJson.long(body.getValue("date")) == at)
                body["created_at"]?.let {
                    val instant = Instant.parse(HistoryJson.string(it))
                    require(instant.nano % 1_000_000 == 0 && instant.toEpochMilli() == at)
                }
                require(HistoryJson.string(body.getValue("device")).let { it.isNotBlank() && it.length <= 256 })
                val pump = body["pump"]?.let(HistoryJson::obj) ?: return@mapNotNull null
                val extended = pump["extended"]?.let(HistoryJson::obj) ?: return@mapNotNull null
                val read = extended["ComboHistoryRead"]
                val journal = extended["ComboHistoryJournal"]
                if (read == null && journal == null) return@mapNotNull null
                val hazards = mutableSetOf<AapsV3ComboHistoryHazard>()
                read?.let {
                    val observation = AapsV3ComboObservation.parse(HistoryJson.obj(it))
                    hazards += observation.hazards
                    // Reader and status dates share the producer clock. Never move either date
                    // to hide a rewind or a read that claims to finish after its publication.
                    if (observation.startedAtMs?.let { start -> start > at } == true ||
                        observation.finishedAtMs?.let { end -> end > at } == true)
                        hazards += AapsV3ComboHistoryHazard.READER_TIME_INCONSISTENT
                }
                journal?.let { journalStatus(HistoryJson.obj(it), hazards) }
                Entry(at, hazards.toSet())
            } catch (_: Exception) {
                // An unparsed clock cannot establish that damaged evidence is outside the query.
                invalid = true
                null
            }
        }
        failure = when {
            snapshot is LocalV3RetainedSnapshot.OverLimit -> AapsV3ComboHistoryStatus.LIMIT_REACHED
            invalid -> AapsV3ComboHistoryStatus.INVALID
            else -> null
        }
    }

    fun read(atMs: Long, knownAtMs: Long): AapsV3ComboHistoryRead {
        fun unavailable(status: AapsV3ComboHistoryStatus) = AapsV3ComboHistoryRead(status, revision)
        if (atMs !in 1..knownAtMs || knownAtMs > LocalNightscoutV3Store.MAX_TIME)
            return unavailable(AapsV3ComboHistoryStatus.INVALID)
        failure?.let { return unavailable(it) }
        if (revision > knownAtMs) return unavailable(AapsV3ComboHistoryStatus.LATE_EVIDENCE)
        val retained = entries.filter { it.atMs <= atMs }
        val hazards = retained.flatMap { it.hazards }.toSet()
        return AapsV3ComboHistoryRead(when {
            retained.isEmpty() -> AapsV3ComboHistoryStatus.NOT_OBSERVED
            hazards.isNotEmpty() -> AapsV3ComboHistoryStatus.ADVERSE
            else -> AapsV3ComboHistoryStatus.RETAINED_UNVERIFIED
        }, revision, retained.size, hazards)
    }

    private fun journalStatus(body: JsonObject, hazards: MutableSet<AapsV3ComboHistoryHazard>) {
        require(body.keys.all { it in JOURNAL_FIELDS })
        version(body)
        val state = HistoryJson.string(body.getValue("state"))
        require(state in setOf("STORED", "WRITING", "FAILED"))
        if (state == "FAILED") hazards += AapsV3ComboHistoryHazard.STORAGE_FAILED
        // Plugin storage failures can report only this minimal, scoped failure snapshot.
        if (state == "FAILED" && body.keys == setOf("version", "coverage", "state")) return
        val retained = count(body, "retainedReads", 4096)
        val counts = listOf("unclosedReads", "corruptReads", "adverseReads", "failedReads").associateWith {
            count(body, it, retained)
        }
        val unscoped = count(body, "unscopedCorruptReads", 4096)
        require(retained + unscoped <= 4096)
        require(counts.getValue("corruptReads") <= counts.getValue("adverseReads"))
        if (state == "WRITING") require(counts.getValue("unclosedReads") > 0)
        for ((key, hazard) in listOf(
            "unclosedReads" to AapsV3ComboHistoryHazard.UNCLOSED_READ,
            "corruptReads" to AapsV3ComboHistoryHazard.CORRUPT_READ,
            "adverseReads" to AapsV3ComboHistoryHazard.ADVERSE_READ,
            "failedReads" to AapsV3ComboHistoryHazard.FAILED_READ
        )) if (counts.getValue(key) > (if (key == "unclosedReads" && state == "WRITING") 1 else 0)) hazards += hazard
        if (unscoped > 0) hazards += AapsV3ComboHistoryHazard.UNSCOPED_CORRUPTION
    }

    private fun version(body: JsonObject) {
        require(HistoryJson.long(body.getValue("version")) == 1L)
        require(HistoryJson.string(body.getValue("coverage")) == "unverified")
    }
    private fun count(body: JsonObject, key: String, max: Long = Int.MAX_VALUE.toLong()) =
        HistoryJson.long(body.getValue(key)).also { require(it in 0..max) }

    companion object {
        private val JOURNAL_FIELDS = setOf("version", "coverage", "state", "retainedReads", "unclosedReads", "corruptReads",
            "adverseReads", "failedReads", "unscopedCorruptReads")
    }
}
