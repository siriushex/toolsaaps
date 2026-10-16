package io.aaps.copilot.data.repository

import io.aaps.copilot.domain.alerts.*
import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test

class LocalAlarmSourceFeedTest {
    private val base = 1_000_000L
    private var clock = LocalAlarmSourceClock(base, 10_000, 3)
    private val feed = LocalAlarmSourceFeed(LocalAlarmSourceKind.GLUCOSE) { clock }
    private fun observation(level: LocalAlarmLevel = LocalAlarmLevel.WARNING_30,
        measurement: Long = base, evaluated: Long = clock.wallMs) =
        LocalAlarmSourceObservation(level, evaluated, measurement, measurement + 360_000, "low")
    private fun advance(ms: Long) { clock = clock.copy(wallMs = clock.wallMs + ms, elapsedMs = clock.elapsedMs + ms) }
    private fun publish(revision: Long = 1, observation: LocalAlarmSourceObservation = observation()): CheckedLocalAlarmSource {
        val ticket = feed.begin(observation)!!
        assertTrue(feed.publish(ticket, "episode-a", revision))
        return feed.current()!!
    }

    @Test fun constructionAndBeginAloneDoNotPublishAuthority() {
        assertNull(feed.current()); assertNull(feed.source.value)
        feed.begin(observation())
        assertNull(feed.current()); assertNull(feed.source.value)
    }

    @Test fun committedSourceHasBoundedCurrentBootEvidence() {
        val source = publish()
        assertEquals(LocalAlarmKey(LocalAlarmSourceKind.GLUCOSE, "episode-a"), source.evidence.key)
        assertEquals(1L, source.evidence.generation)
        assertEquals(10_000L, source.evidence.observedElapsedMs)
        assertEquals(370_000L, source.evidence.validUntilElapsedMs)
        assertTrue(source.evidence.authorized)
        assertSame(source, feed.source.value)
    }

    @Test fun expiryIsExclusiveAndClockReadCannotRenewIt() {
        publish(); advance(359_999)
        assertNotNull(feed.current())
        advance(1); assertNull(feed.current())
        assertNull(feed.begin(observation()))
        assertNull(feed.current())
    }

    @Test fun duplicateMeasurementDoesNotRenewExpiryGenerationOrQueueAge() {
        val first = publish()
        advance(60_000)
        val next = publish(9)
        assertEquals(first.evidence.generation, next.evidence.generation)
        assertEquals(first.evidence.validUntilElapsedMs, next.evidence.validUntilElapsedMs)
        assertEquals(first.evidence.observedElapsedMs, next.evidence.observedElapsedMs)
        assertEquals(first.pendingSinceElapsedMs, next.pendingSinceElapsedMs)
    }

    @Test fun newMeasurementRevokesBeforeWriteButRetainsEpisodeGeneration() {
        val first = publish(); advance(300_000)
        val ticket = feed.begin(observation(measurement = clock.wallMs))!!
        assertNull(feed.current())
        assertTrue(feed.publish(ticket, "episode-a", 20))
        val next = feed.current()!!
        assertEquals(first.evidence.generation, next.evidence.generation)
        assertEquals(first.pendingSinceElapsedMs, next.pendingSinceElapsedMs)
        assertEquals(670_000L, next.evidence.validUntilElapsedMs)
    }

    @Test fun levelChangeRetainsGenerationForScopedAcknowledgementPolicy() {
        val first = publish(); advance(1_000)
        val next = publish(3, observation(LocalAlarmLevel.LOW_NOW))
        assertEquals(first.evidence.generation, next.evidence.generation)
        assertEquals(LocalAlarmLevel.LOW_NOW, next.evidence.level)
        assertEquals(clock.elapsedMs, next.pendingSinceElapsedMs)
    }

    @Test fun safeObservationRevokesAndFreshCohortUsesNewCommittedRevision() {
        val first = publish()
        feed.begin(null); assertNull(feed.current()); assertNull(feed.source.value)
        val resumed = publish(4)
        assertTrue(resumed.evidence.generation > first.evidence.generation)
    }

    @Test fun externalRevocationRejectsInFlightPublication() {
        publish(); val ticket = feed.begin(observation())!!
        feed.invalidate()
        assertFalse(feed.publish(ticket, "episode-a", 3))
        assertNull(feed.current())
    }

    @Test fun staleFailureCannotRevokeNewerPublication() {
        val old = feed.begin(observation())!!
        val current = publish(3)
        feed.fail(old)
        assertSame(current, feed.current())
    }

    @Test fun failureOfPublishedTicketRevokesIt() {
        val ticket = feed.begin(observation())!!
        assertTrue(feed.publish(ticket, "episode-a", 1))
        feed.fail(ticket); assertNull(feed.current())
    }

    @Test fun delayedCommitPastEvidenceExpiryCannotPublish() {
        val ticket = feed.begin(observation())!!
        advance(360_000)
        assertFalse(feed.publish(ticket, "episode-a", 1))
        assertNull(feed.current())
    }

    @Test fun oldBootAndBackwardClocksNeverBecomeCurrent() {
        val original = clock
        for (changed in listOf(original.copy(bootCount = 4), original.copy(elapsedMs = 9_999),
            original.copy(wallMs = base - 1), original.copy(wallMs = base + 6_000))) {
            val independent = LocalAlarmSourceFeed(LocalAlarmSourceKind.GLUCOSE) { clock }
            clock = original
            assertTrue(independent.publish(independent.begin(observation())!!, "episode-a", 1))
            clock = changed; assertNull(independent.current())
        }
    }

    @Test fun futureAndOutOfOrderMeasurementsRevokeWithoutPublishing() {
        publish(); advance(300_000)
        publish(3, observation(measurement = clock.wallMs))
        assertNull(feed.begin(observation(measurement = base)))
        assertNull(feed.current())
        assertNull(feed.begin(observation(measurement = clock.wallMs + 1)))
    }

    @Test fun duplicateCannotReanchorAfterWallClockDisagreement() {
        publish(); clock = clock.copy(elapsedMs = clock.elapsedMs + 60_000)
        assertNull(feed.current())
        assertNull(feed.begin(observation()))
        assertNull(feed.current())
    }

    @Test fun duplicateCannotExtendItsOriginalWallDeadline() {
        val initial = publish(); advance(1_000)
        val changed = observation().copy(validUntilWallMs = base + 600_000)
        val next = publish(2, changed)
        assertEquals(initial.evidence.validUntilElapsedMs, next.evidence.validUntilElapsedMs)
    }

    @Test fun shortenedDeadlineIsRespectedWithoutSlidingOriginalClockWindow() {
        publish(); advance(1_000)
        val next = publish(2, observation().copy(validUntilWallMs = base + 10_000))
        assertEquals(20_000L, next.evidence.validUntilElapsedMs)
        advance(9_000); assertNull(feed.current())
    }

    @Test fun invalidIdentityRevisionMuteAndWrongSourceKindCannotPublish() {
        for (id in listOf("", " ", "bad\n", "x".repeat(129))) {
            val ticket = feed.begin(observation())!!
            assertFalse(feed.publish(ticket, id, 1)); assertNull(feed.current())
        }
        assertFalse(feed.publish(feed.begin(observation())!!, "episode-a", 0))
        assertFalse(feed.publish(feed.begin(observation())!!, "episode-a", 1, clock.wallMs + 1))
        val diagnostic = LocalAlarmSourceFeed(LocalAlarmSourceKind.DELIVERY_DIAGNOSTIC) { clock }
        assertNull(diagnostic.begin(observation()))
    }

    @Test fun unavailableClockIsNotReplacedWithAHealthyDefault() {
        val broken = LocalAlarmSourceFeed(LocalAlarmSourceKind.GLUCOSE) { error("unavailable") }
        assertNull(broken.begin(observation())); assertNull(broken.current())
        val cancelled = LocalAlarmSourceFeed(LocalAlarmSourceKind.GLUCOSE) { throw CancellationException("cancel") }
        try { cancelled.begin(observation()); fail("Expected cancellation") } catch (_: CancellationException) {}
    }

    @Test fun elapsedDeadlineOverflowAndInvalidClockFailClosed() {
        clock = clock.copy(elapsedMs = Long.MAX_VALUE - 1)
        assertNull(feed.begin(observation()))
        clock = clock.copy(elapsedMs = 1, bootCount = -1)
        assertNull(feed.begin(observation()))
    }

    @Test fun cancelledClockDuringNewAcceptanceRevokesThePreviousSource() {
        var cancelled = false
        val feed = LocalAlarmSourceFeed(LocalAlarmSourceKind.GLUCOSE) {
            if (cancelled) throw CancellationException("clock_cancelled") else clock
        }
        assertTrue(feed.publish(feed.begin(observation())!!, "episode-a", 1))
        cancelled = true
        try { feed.begin(observation()); fail("Expected cancellation") } catch (_: CancellationException) {}
        assertNull(feed.source.value)
    }

    @Test fun cancelledClockReadCannotLeaveCachedSourceLookingCurrent() {
        var cancelled = false
        val feed = LocalAlarmSourceFeed(LocalAlarmSourceKind.GLUCOSE) {
            if (cancelled) throw CancellationException("clock_cancelled") else clock
        }
        assertTrue(feed.publish(feed.begin(observation())!!, "episode-a", 1))
        cancelled = true
        try { feed.current(); fail("Expected cancellation") } catch (_: CancellationException) {}
        assertNull(feed.source.value)
    }

    @Test fun cancelledPublicationCannotLeaveTheOldDuplicateSourceOrPendingTicket() {
        var cancelled = false
        val feed = LocalAlarmSourceFeed(LocalAlarmSourceKind.GLUCOSE) {
            if (cancelled) throw CancellationException("clock_cancelled") else clock
        }
        assertTrue(feed.publish(feed.begin(observation())!!, "episode-a", 1))
        val ticket = feed.begin(observation())!!
        cancelled = true
        try { feed.publish(ticket, "episode-a", 2); fail("Expected cancellation") } catch (_: CancellationException) {}
        assertNull(feed.source.value)
        cancelled = false
        assertFalse(feed.publish(ticket, "episode-a", 2))
    }
}
