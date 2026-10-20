package io.aaps.copilot.ui

import io.aaps.copilot.data.local.entity.TherapyEventEntity
import io.aaps.copilot.data.repository.AiInsulinContext
import org.junit.Assert.*
import org.junit.Test

class InsulinOnsetCountdownTest {
    private val now = 10_000_000L
    private val model = AiInsulinContext("FIASP", 27.5, now, fittedProfileId = "FIASP")
    private fun row(id: String = "meal-1", timestamp: Long = now - 15 * 60_000L,
        type: String = "meal_bolus", payload: String = "{\"insulin\":2.2,\"carbs\":40}") =
        TherapyEventEntity(id, timestamp, type, payload)

    @Test fun fractionalOnsetKeepsSecondsAndDoesNotRestartWithScreenClock() {
        val timer = insulinOnsetCountdown(listOf(row()), model, now)!!
        assertEquals(750L, timer.remainingSeconds(now))
        assertEquals(749L, timer.remainingSeconds(now + 1_000))
        assertEquals(0L, timer.remainingSeconds(now + 750_000))
        assertEquals(0L, timer.remainingSeconds(now + 751_000))
    }
    @Test fun expiredClockRemainsVisibleBrieflyThenDisappears() {
        val timer = insulinOnsetCountdown(listOf(row()), model, now)!!.copy(validUntilMs = Long.MAX_VALUE)
        assertTrue(timer.visibleAt(timer.estimatedAtMs + 15 * 60_000))
        assertFalse(timer.visibleAt(timer.estimatedAtMs + 15 * 60_000 + 1))
        assertFalse(timer.visibleAt(timer.recordedAtMs - 1))
    }
    @Test fun timerHidesWhenItsModelEvidenceExpiresAndCorrectionHasNoMealGesture() {
        val timer = insulinOnsetCountdown(listOf(row(type = "correction_bolus")), model, now)!!
        assertFalse(timer.mealAssociated)
        assertTrue(timer.visibleAt(now + 900_000))
        assertFalse(timer.visibleAt(now + 900_001))
    }
    @Test fun iobWithoutMealInsulinRecordCannotStartAClock() {
        assertNull(insulinOnsetCountdown(emptyList(), model, now))
        assertNull(insulinOnsetCountdown(listOf(row(payload = "{\"carbs\":40}")), model, now))
    }
    @Test fun insulinOnlyRecordCanShowOnsetWithoutClaimingAConfirmedMeal() {
        for (type in listOf("insulin", "bolus", "correction_bolus")) {
            val timer = insulinOnsetCountdown(listOf(row(type = type, payload = "{\"insulin\":2.2}")), model, now)
            assertNotNull(type, timer)
            assertEquals(750L, timer!!.remainingSeconds(now))
        }
    }
    @Test fun automaticInferredAndUnconfirmedInsulinCannotStartATimer() {
        val payloads = listOf(
            "{\"insulin\":0.2,\"isSMB\":true}",
            "{\"insulin\":0.2,\"bolusType\":\"SMB\"}",
            "{\"insulin\":0.2,\"type\":\"SMB\"}",
            "{\"insulin\":2.2,\"classification\":\"INFERRED_IOB\"}",
            "{\"insulin\":2.2,\"classification\":\"RECOVERED_MICROBOLUS\"}",
            "{\"insulin\":2.2,\"classification\":\"LOCAL_ACTION\"}",
            "{\"insulin\":2.2,\"cancelled\":true}",
            "{\"insulin\":2.2,\"inferred\":true}"
        )
        payloads.forEach { assertNull(it, insulinOnsetCountdown(listOf(row(type = "correction_bolus", payload = it)), model, now)) }
    }
    @Test fun staleFutureOrInvalidModelCannotSignalCompletion() {
        listOf(model.copy(onsetEvidenceTs = now - 900_001), model.copy(onsetEvidenceTs = now + 1),
            model.copy(onsetMinutes = Double.NaN), model.copy(onsetMinutes = 0.0),
            model.copy(fittedProfileId = "HUMALOG"), model.copy(configuredProfileId = "UNKNOWN"))
            .forEach { assertNull(insulinOnsetCountdown(listOf(row()), it, now)) }
    }
    @Test fun futureConflictingAndBroadcastRowsDoNotInventAnAnchor() {
        assertNull(insulinOnsetCountdown(listOf(row(timestamp = now + 1)), model, now))
        assertNull(insulinOnsetCountdown(listOf(row(), row(id = "meal-2")), model, now))
        assertNull(insulinOnsetCountdown(listOf(row(id = "br-local_broadcast-1")), model, now))
        assertNull(insulinOnsetCountdown(listOf(row(payload = "{\"insulin\":2.2,\"isValid\":false}")), model, now))
    }
}
