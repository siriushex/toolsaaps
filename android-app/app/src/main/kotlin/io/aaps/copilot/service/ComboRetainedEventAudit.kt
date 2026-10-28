package io.aaps.copilot.service

import java.util.Collections
import java.util.TreeMap

/** Interior observations only: neither the ends of history nor pump-clock epochs are known. */
internal class ComboRetainedEventAudit(
    val observations: Int,
    val distinctCounters: Int,
    val exactRepeatObservations: Int,
    val conflictingCounters: Int,
    val unacknowledgedObservations: Int,
    val countersWithoutAcknowledgedCopy: Int,
    val interiorCounterGaps: Int,
    val missingInteriorCounterValues: Long,
    val backwardsLocalTimePairs: Int,
    val clockChangeCounters: Int,
    val unknownEventCounters: Int,
    bolusPhaseCounts: Map<ComboRecordedBolusPhase, Int>,
    hazards: Set<AapsV3ComboHistoryHazard>
) {
    val bolusPhaseCounts: Map<ComboRecordedBolusPhase, Int> = Collections.unmodifiableMap(bolusPhaseCounts.toMap())
    val hazards: Set<AapsV3ComboHistoryHazard> = Collections.unmodifiableSet(hazards.toSet())
    val pumpBindingVerified: Boolean get() = false
    val clockMappingVerified: Boolean get() = false
    val deliveryCompletenessVerified: Boolean get() = false
    override fun toString() = "ComboRetainedEventAudit(redacted)"
}

/** Local to one owned retention transaction. Never repairs, sorts or deduplicates stored bytes. */
internal class ComboRetainedEventAuditBuilder(private val maximumCounters: Int = 100_000) {
    private class Seen(val first: ComboJournalEvent, acknowledged: Boolean) {
        var count = 1
        var conflicting = false
        var acknowledged = acknowledged
        var clockChange = first.detail is ComboEventDetail.ClockChange
        var unknown = first.detail is ComboEventDetail.Unknown
    }
    private val counters = TreeMap<Long, Seen>()
    private val hazards = mutableSetOf<AapsV3ComboHistoryHazard>()
    private var observations = 0
    private var unacknowledged = 0
    private var failed = false
    private var finished = false
    init { require(maximumCounters in 1..100_000) }

    fun accept(read: AapsV3ComboJournalRead) {
        check(!failed && !finished)
        hazards += read.hazards
        for (observation in read.events) {
            val event = observation.event
            val previous = counters[event.counter]
            if (observations >= 1_000_000 || previous == null && counters.size >= maximumCounters) {
                failed = true
                throw ComboRetentionFailure(ComboRetentionError.CAPACITY)
            }
            observations++
            if (!observation.historyBlockAcknowledged) unacknowledged++
            if (previous == null) counters[event.counter] = Seen(event, observation.historyBlockAcknowledged)
            else {
                val firstBytes = previous.first.rawBytes(); val nextBytes = event.rawBytes()
                try { previous.conflicting = previous.conflicting || !firstBytes.contentEquals(nextBytes) }
                finally { firstBytes.fill(0); nextBytes.fill(0) }
                previous.count++
                previous.acknowledged = previous.acknowledged || observation.historyBlockAcknowledged
                previous.clockChange = previous.clockChange || event.detail is ComboEventDetail.ClockChange
                previous.unknown = previous.unknown || event.detail is ComboEventDetail.Unknown
            }
        }
    }

    fun finish(): ComboRetainedEventAudit {
        check(!failed && !finished)
        finished = true
        var previousCounter: Long? = null
        var previous: Seen? = null
        var gaps = 0
        var missing = 0L
        var backwards = 0
        val phases = ComboRecordedBolusPhase.entries.associateWith { 0 }.toMutableMap()
        for ((counter, entry) in counters) {
            previousCounter?.let { before ->
                if (counter > before + 1) { gaps++; missing += counter - before - 1 }
            }
            if (!entry.conflicting) {
                if (previous?.let { !it.conflicting && entry.first.localTime.isBefore(it.first.localTime) } == true)
                    backwards++
                (entry.first.detail as? ComboEventDetail.Bolus)?.let { phases[it.phase] = phases.getValue(it.phase) + 1 }
            }
            previousCounter = counter
            previous = entry
        }
        val conflicts = counters.values.count { it.conflicting }
        val clockChanges = counters.values.count { it.clockChange }
        val unknown = counters.values.count { it.unknown }
        if (conflicts > 0) hazards += AapsV3ComboHistoryHazard.EVENT_COUNTER_REUSED
        if (gaps > 0) hazards += AapsV3ComboHistoryHazard.EVENT_COUNTER_GAP
        if (backwards > 0) hazards += AapsV3ComboHistoryHazard.EVENT_TIME_REWIND
        if (clockChanges > 0) hazards += AapsV3ComboHistoryHazard.CLOCK_CHANGE
        if (unknown > 0) hazards += AapsV3ComboHistoryHazard.UNMAPPED_EVENT
        if (unacknowledged > 0) hazards += AapsV3ComboHistoryHazard.UNCONFIRMED_BLOCK
        return ComboRetainedEventAudit(observations, counters.size,
            counters.values.filter { !it.conflicting }.sumOf { it.count - 1 }, conflicts,
            unacknowledged, counters.values.count { !it.acknowledged }, gaps, missing, backwards,
            clockChanges, unknown, phases, hazards)
    }
}
