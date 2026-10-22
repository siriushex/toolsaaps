package io.aaps.copilot.domain.meal

import org.junit.Assert.*
import org.junit.Test

class MealMessageEpisodeTest {
    private val record = MealRecordRevision("meal-12", 3, 1_700_000_000_000L, 35.0, false)
    private fun episode() = MealMessageEpisode("episode-1", record, "bolus-1", record.recordedAtMs - 60_000L, "r1")

    @Test fun postedMessageIsOnlyAnAssumptionAndCannotBecomeConfirmedIntake() {
        val initial = episode()
        assertNull(initial.assumedStartAtMs)
        val posted = initial.recordPosted(record.recordedAtMs + 120_000)
        assertEquals(record.recordedAtMs + 120_000, posted.assumedStartAtMs)
        assertEquals(record, posted.meal)
        assertFalse(posted.intakeConfirmed)
        assertFalse(posted.trainingLabelEligible)
        assertTrue(posted.missedMealScenarioRequired)
        assertSame(posted, posted.recordPosted(record.recordedAtMs + 180_000))
        assertNull(initial.messagePostedAtMs)
    }

    @Test fun revisionChangesNeverInheritAnAssumedStart() {
        val posted = episode().recordPosted(record.recordedAtMs + 120_000)
        val revised = posted.reconcile(record.copy(revision = 4, grams = 40.0))
        assertNull(revised.assumedStartAtMs)
        assertEquals(4L, revised.meal.revision)
        assertSame(revised, revised.reconcile(record))
        assertThrows(IllegalArgumentException::class.java) { posted.reconcile(record.copy(grams = 40.0)) }
        assertThrows(IllegalArgumentException::class.java) { posted.reconcile(record.copy(canonicalId = "other")) }
    }

    @Test fun deletedMealAndNoncausalPostingAreRejected() {
        val initial = episode()
        assertThrows(IllegalArgumentException::class.java) { initial.recordPosted(initial.insulinRecordedAtMs - 1) }
        val deleted = initial.reconcile(record.copy(revision = 4, deleted = true))
        assertThrows(IllegalArgumentException::class.java) { deleted.recordPosted(record.recordedAtMs + 120_000) }
    }
}
