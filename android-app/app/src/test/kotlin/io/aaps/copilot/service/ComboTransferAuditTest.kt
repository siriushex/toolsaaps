package io.aaps.copilot.service

import org.junit.Assert.*
import org.junit.Test

class ComboTransferAuditTest {
    @Test fun summaryPreservesDistinctCounterAndClockFindingsWithoutIdentifiers() {
        val events = ComboRetainedEventAudit(14, 10, 3, 1, 4, 2, 1, 9,
            3, 2, 4, 5, emptyMap(), emptySet())
        val source = ComboInventoryRetentionAudit("private-snapshot", "private-hash", 4, 1, 2,
            listOf("missing-one", "missing-two"), listOf("conflicting"),
            setOf(AapsV3ComboHistoryHazard.READER_TIME_INCONSISTENT), events)
        val result = ComboTransferAudit.from(source, 123456)
        assertEquals(123456L, result.checkedAtMs)
        assertEquals(1, result.retainedReads); assertEquals(2, result.missingReads); assertEquals(1, result.conflictingReads)
        assertEquals(10, result.distinctEvents); assertEquals(2, result.eventsWithoutReadAcknowledgement)
        assertEquals(1, result.conflictingEvents); assertEquals(9L, result.missingInteriorCounterValues)
        assertEquals(3, result.backwardsLocalTimePairs); assertEquals(2, result.postClockChangeRewinds)
        assertEquals(4, result.clockChangeEvents); assertEquals(5, result.unknownEvents)
        assertTrue(result.readerTimeInconsistent); assertEquals(1, result.hazardKinds)
        assertEquals("ComboTransferAudit(redacted)", result.toString())
        assertFalse(result.deliveryCompletenessVerified); assertFalse(result.clockMappingVerified)
    }

    @Test fun emptyMatchingSnapshotDoesNotProveDeliveryOrClockMapping() {
        val events = ComboRetainedEventAuditBuilder().finish()
        val source = ComboInventoryRetentionAudit("empty", "digest", 0, 0, 0,
            emptyList(), emptyList(), emptySet(), events)
        assertTrue(source.declaredInventoryRetained)
        val result = ComboTransferAudit.from(source, 1000)
        assertEquals(0, result.distinctEvents); assertFalse(result.readerTimeInconsistent)
        assertFalse(result.deliveryCompletenessVerified); assertFalse(result.clockMappingVerified)
    }
}
