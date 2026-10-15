package io.aaps.copilot.domain.meal

import org.junit.Assert.*
import org.junit.Test

class MealEpisodeIdentityTest {
    private val original = MealInput("input-a", 1_000_000, MealCarbRange(15.0, 40.0))
    private val initial = MealEpisodeIdentity(original)
    private val confirmation = MealRecordRevision("aaps-a", 1, 900_000, 25.0, false)

    @Test fun linkingConfirmedAapsRecordDoesNotProveIngestionOrRewriteInput() {
        val linked = initial.reconcile(confirmation)
        assertEquals(original, linked.input)
        assertEquals(900_000L, linked.aapsRecord?.recordedAtMs)
        assertEquals(1_000_000L, linked.input.recordedAtMs)
    }
    @Test fun duplicateAndOlderRevisionAreIdempotent() {
        val linked = initial.reconcile(confirmation)
        assertSame(linked, linked.reconcile(confirmation))
        assertSame(linked, linked.reconcile(confirmation.copy(revision = 0)))
    }
    @Test fun correctionOrTombstoneRetainsStableEpisodeIdentity() {
        val linked = initial.reconcile(confirmation)
        val corrected = linked.reconcile(confirmation.copy(revision = 2, grams = 30.0))
        val removed = corrected.reconcile(confirmation.copy(revision = 3, deleted = true))
        assertEquals(original.id, removed.input.id)
        assertTrue(removed.aapsRecord!!.deleted)
    }
    @Test(expected = IllegalArgumentException::class)
    fun proximityDoesNotAuthorizeMergingDifferentMeals() {
        initial.reconcile(confirmation).reconcile(confirmation.copy(canonicalId = "aaps-b", revision = 2))
    }
    @Test(expected = IllegalArgumentException::class)
    fun conflictingContentAtSameRevisionIsNotSilentlyAccepted() {
        initial.reconcile(confirmation).reconcile(confirmation.copy(grams = 35.0))
    }
}
