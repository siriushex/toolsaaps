package io.aaps.copilot.data.repository

import io.aaps.copilot.domain.alerts.*
import org.junit.Assert.*
import org.junit.Test

class AlertAudioOwnershipTest {
    private val gate = AlertAudioOwnership()
    private val cycle = LocalAlarmCycle(LocalAlarmKey(LocalAlarmSourceKind.GLUCOSE, "source-a"),
        1, 1, LocalAlarmLevel.LOW_NOW, 1, 1_000, 56_000)
    private fun legacy() = gate.begin(AlertAudioOwnerKind.LEGACY)
    private fun preview() = gate.begin(AlertAudioOwnerKind.PREVIEW)

    @Test fun reservationDoesNotActivateAndANewerRequestSupersedesOnlyPendingAuthority() {
        val first = legacy(); assertTrue(gate.isCurrent(first)); assertTrue(gate.activate(first))
        val next = preview(); assertFalse(gate.isCurrent(first)); assertTrue(gate.isCurrent(next))
        assertFalse("Physical owner must be released first", gate.activate(next))
        gate.release(first); assertTrue(gate.activate(next))
    }

    @Test fun staleReleaseAndRevokeCannotCancelTheReplacement() {
        val first = legacy(); gate.activate(first)
        val next = preview(); gate.release(first); gate.activate(next)
        gate.revoke(first); gate.release(first)
        assertTrue(gate.isCurrent(next)); assertFalse(gate.isCurrent(first))
    }

    @Test fun globalStopCapturesPendingAndActiveButNotLaterRequests() {
        val active = legacy(); gate.activate(active)
        val pending = preview()
        val revoked = gate.revokeAll()
        assertEquals(setOf(active, pending), revoked)
        assertFalse(gate.isCurrent(active)); assertFalse(gate.isCurrent(pending))
        val next = legacy()
        assertFalse(revoked.contains(next)); assertFalse(gate.activate(next))
        gate.release(active); assertTrue(gate.activate(next))
        revoked.forEach(gate::release)
        assertTrue(gate.isCurrent(next))
    }

    @Test fun evenAnEmptyOffFencesAnAlreadyDispatchedLocalRequest() {
        val before = gate.stamp(); assertTrue(gate.revokeAll().isEmpty())
        assertNull(gate.beginLocalIfCurrent(before, cycle))
    }

    @Test fun localReservationCannotReplaceAnotherRequestSincePreflight() {
        val before = gate.stamp(); val urgent = legacy()
        assertNull(gate.beginLocalIfCurrent(before, cycle))
        assertTrue(gate.isCurrent(urgent)); assertTrue(gate.activate(urgent))
    }

    @Test fun oneStampCannotReserveTwoLocalRequests() {
        val before = gate.stamp()
        val first = gate.beginLocalIfCurrent(before, cycle)!!
        assertEquals(AlertAudioOwnerKind.LOCAL, first.kind); assertEquals(cycle, first.cycle)
        assertNull(gate.beginLocalIfCurrent(before, cycle.copy(ordinal = 2)))
        assertTrue(gate.isCurrent(first))
    }

    @Test fun previewStopCannotRevokeAnUrgentRequestOrChangeItsStamp() {
        val urgent = legacy(); gate.activate(urgent)
        val before = gate.stamp(); assertTrue(gate.revokePreview().isEmpty())
        assertTrue(gate.isCurrent(urgent))
        assertNotNull(gate.beginLocalIfCurrent(before, cycle))
    }

    @Test fun previewStopRevokesOnlyPreviewNotAnOlderPhysicalLegacyOwner() {
        val active = legacy(); gate.activate(active)
        val pending = preview()
        assertEquals(setOf(pending), gate.revokePreview())
        assertFalse(gate.activate(pending))
        val next = legacy(); assertFalse(gate.activate(next))
        gate.release(active); assertTrue(gate.activate(next))
    }

    @Test fun previewStopFencesItsPendingStampWithoutStoppingANewPreview() {
        val old = preview(); gate.activate(old)
        val stamp = gate.stamp(); val revoked = gate.revokePreview()
        assertNull(gate.beginLocalIfCurrent(stamp, cycle))
        val next = preview(); gate.release(old); assertTrue(gate.activate(next))
        assertFalse(revoked.contains(next)); revoked.forEach(gate::release)
        assertTrue(gate.isCurrent(next))
    }

    @Test fun exactCycleStopCannotRevokeANewerOrdinal() {
        val first = gate.beginLocalIfCurrent(gate.stamp(), cycle)!!; gate.activate(first)
        val next = gate.beginLocalIfCurrent(gate.stamp(), cycle.copy(ordinal = 2))!!
        gate.release(first); gate.activate(next)
        val stamp = gate.stamp()
        assertTrue(gate.revokeCycle(cycle).isEmpty()); assertTrue(gate.isCurrent(next))
        assertNotNull(gate.beginLocalIfCurrent(stamp, cycle.copy(ordinal = 3)))
    }

    @Test fun exactCycleStopRevokesItsKnownPreparationAndKeepsCleanupOwnership() {
        val request = gate.beginLocalIfCurrent(gate.stamp(), cycle)!!; gate.activate(request)
        assertEquals(setOf(request), gate.revokeCycle(cycle))
        assertFalse(gate.isCurrent(request)); assertFalse(gate.activate(request))
        val next = legacy(); assertFalse(gate.activate(next))
        gate.release(request); assertTrue(gate.activate(next))
    }

    @Test fun releasingAnOldPhysicalOwnerCannotInvalidateANewerPendingStamp() {
        val first = legacy(); gate.activate(first)
        val next = preview(); val stamp = gate.stamp()
        gate.release(first)
        assertTrue(gate.isCurrent(next)); assertNotNull(gate.beginLocalIfCurrent(stamp, cycle))
    }

    @Test fun cancelledRequestCannotActivateOrBeResurrectedByRelease() {
        val request = legacy(); gate.revoke(request); gate.release(request)
        assertFalse(gate.activate(request)); assertFalse(gate.isCurrent(request))
    }

    @Test fun revocationBeforeCallbackIsImmediateButPhysicalOwnershipWaitsForRelease() {
        val request = legacy(); gate.activate(request); gate.revokeAll()
        assertFalse(gate.isCurrent(request))
        val next = legacy(); assertFalse(gate.activate(next))
        gate.release(request); assertTrue(gate.activate(next))
        gate.revoke(request); assertTrue(gate.isCurrent(next))
    }

    @Test fun repeatedReleaseAndRevocationCannotCreateAuthority() {
        val request = legacy(); gate.activate(request)
        repeat(3) { gate.revoke(request); gate.release(request) }
        assertFalse(gate.activate(request)); assertTrue(gate.revokeAll().isEmpty())
    }

    @Test fun localKindWithoutPreflightStampCannotReplaceALegacyRequest() {
        val urgent = legacy()
        try { gate.begin(AlertAudioOwnerKind.LOCAL); fail("Expected rejection") } catch (_: IllegalArgumentException) {}
        assertTrue(gate.isCurrent(urgent))
    }
}
