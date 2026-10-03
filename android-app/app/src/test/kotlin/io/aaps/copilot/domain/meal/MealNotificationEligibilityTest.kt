package io.aaps.copilot.domain.meal

import org.junit.Assert.*
import org.junit.Test

class MealNotificationEligibilityTest {
    private val window = 120 * 60_000L
    private val previous = MealNotificationClaim("episode-a", "boot-a", 1_000L, 10_000_000L)
    private fun check(elapsed: Long, boot: String = "boot-a", sameEpisode: Boolean = false,
                      mutedUntil: Long = 0L, wall: Long = 30_000_000L, trusted: Boolean = true) =
        MealNotificationEligibility.evaluate(MealDeliveryClock(boot, elapsed, wall, trusted), previous,
            sameEpisode, mutedUntil)

    @Test fun exactTwoHourBoundary() {
        assertEquals(MealNotificationBlock.COOLDOWN, check(1_000 + window - 1))
        assertNull(check(1_000 + window))
    }
    @Test fun forwardWallClockCannotBypassElapsedCooldown() {
        assertEquals(MealNotificationBlock.COOLDOWN, check(2_000, wall = Long.MAX_VALUE))
    }
    @Test fun rebootRequiresFullWindowOfKnownUptime() {
        assertEquals(MealNotificationBlock.CLOCK_UNCERTAIN, check(window - 1, boot = "boot-b"))
        assertNull(check(window, boot = "boot-b"))
    }
    @Test fun sameEpisodeNeverRepeats() {
        assertEquals(MealNotificationBlock.EPISODE_ALREADY_CLAIMED, check(window * 3, sameEpisode = true))
    }
    @Test fun muteAndInvalidClockBlockEvenWithoutPreviousClaim() {
        assertEquals(MealNotificationBlock.MUTED, check(window * 3, mutedUntil = 30_000_001L))
        assertEquals(MealNotificationBlock.CLOCK_UNCERTAIN, check(window * 3, trusted = false))
        assertEquals(MealNotificationBlock.CLOCK_UNCERTAIN, MealNotificationEligibility.evaluate(
            MealDeliveryClock("", -1, -1, false), null, false, 0))
    }
    @Test fun backwardsClockAndElapsedResetDoNotResetQuota() {
        assertEquals(MealNotificationBlock.CLOCK_UNCERTAIN, check(500))
        assertEquals(MealNotificationBlock.CLOCK_UNCERTAIN, check(window * 3, wall = previous.wallAtMs - 1))
    }
    @Test fun muteExpiryDoesNotRepresentAStoredNotificationToReplay() {
        assertNull(check(window * 3, mutedUntil = 30_000_000L))
        // Eligibility alone has no notification payload or send side effect.
    }
}
