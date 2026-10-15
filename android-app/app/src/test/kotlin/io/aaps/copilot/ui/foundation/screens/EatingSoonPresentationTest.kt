package io.aaps.copilot.ui.foundation.screens

import com.google.gson.Gson
import io.aaps.copilot.data.local.entity.ActionCommandEntity
import io.aaps.copilot.data.repository.withConfirmedEatingSoonContext
import io.aaps.copilot.domain.target.ActiveAapsTarget
import io.aaps.copilot.domain.target.ActiveTargetOwnership
import org.junit.Assert.*
import org.junit.Test

class EatingSoonPresentationTest {
    private val now = 1_800_000_000_000L
    private val key = "manual:meal:meal-a:eating-soon"
    private val receipt = ActionCommandEntity(key, now - 59_000L, "temp_target",
        """{"targetMmol":"4.1","durationMinutes":"30","reason":"Eating Soon"}""", "{}", key, "SENT")
    private val observed = ActiveAapsTarget(4.1, now - 60_000L, now + 1_740_000L,
        "therapy_history", ActiveTargetOwnership.MANUAL_OR_FOREIGN, key)

    @Test fun sentIsNotConfirmedAndRestoredPendingIsNotAnInFlightQueue() {
        assertEquals(EatingSoonUiStatus.AWAITING_AAPS, eatingSoonPresentation(receipt, null, now).status)
        assertEquals(EatingSoonUiStatus.UNKNOWN, eatingSoonPresentation(receipt.copy(status = "PENDING"), null, now).status)
        assertEquals(EatingSoonUiStatus.BLOCKED, eatingSoonPresentation(receipt.copy(status = "BLOCKED"), null, now).status)
    }

    @Test fun onlyExactActiveCanonicalReadbackConfirms() {
        val confirmed = withConfirmedEatingSoonContext(observed, receipt, now, Gson())!!
        assertEquals(EatingSoonUiStatus.CONFIRMED, eatingSoonPresentation(receipt, confirmed, now).status)
        listOf(observed, confirmed.copy(idempotencyKey = "manual:meal:other:eating-soon"),
            confirmed.copy(targetMmol = 4.2), confirmed.copy(evidenceResolved = false),
            confirmed.copy(expiresAt = now), confirmed.copy(startedAt = now + 1)).forEach {
            assertNotEquals(EatingSoonUiStatus.CONFIRMED, eatingSoonPresentation(receipt, it, now).status)
        }
    }

    @Test fun malformedOrFutureReceiptsNeverCreateSuccess() {
        assertEquals(EatingSoonUiStatus.IDLE, eatingSoonPresentation(receipt.copy(payloadJson = "{}"), observed, now).status)
        assertEquals(EatingSoonUiStatus.UNKNOWN, eatingSoonPresentation(receipt.copy(timestamp = now + 1), observed, now).status)
    }

    @Test fun expiredKnownSentIntentAllowsANewConfirmationButPendingDoesNotBecomeIdle() {
        val later = receipt.timestamp + 30 * 60_000L
        assertEquals(EatingSoonUiStatus.IDLE, eatingSoonPresentation(receipt, null, later).status)
        assertEquals(EatingSoonUiStatus.UNKNOWN, eatingSoonPresentation(receipt.copy(status = "PENDING"), null, later).status)
    }

    @Test fun localQueueDoesNotMaskReadbackAndReasonSurvivesDurableBlockedReceipt() {
        val queued = EatingSoonUiState(EatingSoonUiStatus.QUEUED, key, now)
        assertEquals(queued, mergeEatingSoonPresentation(eatingSoonPresentation(receipt.copy(status = "PENDING"), null, now), queued, now))
        val confirmed = withConfirmedEatingSoonContext(observed, receipt, now, Gson())!!
        assertEquals(EatingSoonUiStatus.CONFIRMED,
            mergeEatingSoonPresentation(eatingSoonPresentation(receipt, confirmed, now), queued, now).status)
        val blocked = queued.copy(status = EatingSoonUiStatus.BLOCKED, reason = "low_glucose")
        assertEquals("low_glucose", mergeEatingSoonPresentation(
            eatingSoonPresentation(receipt.copy(status = "BLOCKED"), null, now), blocked, now).reason)
    }
}
