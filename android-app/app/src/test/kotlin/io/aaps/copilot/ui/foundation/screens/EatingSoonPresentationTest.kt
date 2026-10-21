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

    @Test fun mealTargetRefusalReasonSurvivesWithoutStandaloneLocalState() {
        val blocked = refusedReceipt("forecast_30m_ci_low")
        assertEquals("forecast_30m_ci_low", eatingSoonPresentation(blocked, null, now).reason)
    }

    @Test fun localQueueCannotEraseTheStoredRefusalReason() {
        val blocked = eatingSoonPresentation(refusedReceipt("sensor_untrusted"), null, now)
        val queued = EatingSoonUiState(EatingSoonUiStatus.QUEUED, key, now)
        assertEquals("sensor_untrusted", mergeEatingSoonPresentation(blocked, queued, now).reason)
        assertEquals("sensor_untrusted", mergeEatingSoonPresentation(blocked,
            queued.copy(status = EatingSoonUiStatus.BLOCKED, reason = "forecast_stale"), now).reason)
    }

    @Test fun storedReasonUsesTheTransportAllowlistAndNeverExposesArbitraryPayloadText() {
        listOf(null, "not_an_allowed_code", "x".repeat(97), 42, true,
            listOf("sensor_untrusted"), mapOf("reason" to "sensor_untrusted")).forEach { code ->
            assertNull(eatingSoonPresentation(refusedReceipt(code), null, now).reason)
        }
        assertEquals("actions_disarmed", eatingSoonPresentation(
            refusedReceipt("therapy_actions_not_armed", "FAILED"), null, now).reason)
        for (status in listOf("SENT", "PENDING")) {
            assertNull(eatingSoonPresentation(refusedReceipt("sensor_untrusted", status), null, now).reason)
        }
        assertNull(eatingSoonPresentation(refusedReceipt("sensor_untrusted").copy(
            idempotencyKey = "manual:other"), null, now).reason)
    }

    private fun refusedReceipt(code: Any?, status: String = "BLOCKED"): ActionCommandEntity {
        val gson = Gson()
        val payload = gson.fromJson(receipt.payloadJson, com.google.gson.JsonObject::class.java)
        payload.add("eatingSoonFailureCode", gson.toJsonTree(code))
        return receipt.copy(status = status, payloadJson = gson.toJson(payload))
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
