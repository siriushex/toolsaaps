package io.aaps.copilot.service

/** Last completed snapshot comparison, not live delivery or pump-clock authority. */
data class ComboTransferAudit(
    val checkedAtMs: Long,
    val retainedReads: Int,
    val missingReads: Int,
    val conflictingReads: Int,
    val distinctEvents: Int,
    val eventsWithoutReadAcknowledgement: Int,
    val conflictingEvents: Int,
    val missingInteriorCounterValues: Long,
    val backwardsLocalTimePairs: Int,
    val postClockChangeRewinds: Int,
    val clockChangeEvents: Int,
    val unknownEvents: Int,
    val readerTimeInconsistent: Boolean,
    val hazardKinds: Int
) {
    val deliveryCompletenessVerified: Boolean get() = false
    val clockMappingVerified: Boolean get() = false
    override fun toString() = "ComboTransferAudit(redacted)"

    internal companion object {
        fun from(audit: ComboInventoryRetentionAudit, checkedAtMs: Long) = ComboTransferAudit(
            checkedAtMs, audit.matchedReadCount, audit.missingReadIds.size, audit.conflictingReadIds.size,
            audit.events.distinctCounters, audit.events.countersWithoutAcknowledgedCopy,
            audit.events.conflictingCounters, audit.events.missingInteriorCounterValues,
            audit.events.backwardsLocalTimePairs, audit.events.postClockChangeRewinds,
            audit.events.clockChangeCounters, audit.events.unknownEventCounters,
            AapsV3ComboHistoryHazard.READER_TIME_INCONSISTENT in audit.hazards, audit.hazards.size
        )
    }
}
