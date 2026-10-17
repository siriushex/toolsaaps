package io.aaps.copilot.domain.alerts

import org.junit.Assert.*
import org.junit.Test

class LocalAlarmFatiguePolicyTest {
    private val timing = LocalAlarmTiming()
    private val environment = LocalAlarmEnvironment(true, true, 1_000, 10_000, 2)
    private val evidence = LocalAlarmEvidence(LocalAlarmKey(LocalAlarmSourceKind.GLUCOSE, "source-a"),
        1, LocalAlarmLevel.WARNING_30, 2, 1_000, 90_000_000, true)
    private fun at(time: Long) = environment.copy(nowElapsedMs = time, nowWallMs = time + 9_000)

    @Test fun noncriticalIntervalsDoubleThenStayAtThirtyMinutesAcrossPersistence() {
        for (level in listOf(LocalAlarmLevel.WARNING_30, LocalAlarmLevel.SOFT_HIGH_RISK,
            LocalAlarmLevel.PUMP_LINK, LocalAlarmLevel.DELIVERY_DIAGNOSTIC)) {
            val kind = when (level) {
                LocalAlarmLevel.PUMP_LINK -> LocalAlarmSourceKind.PUMP_LINK
                LocalAlarmLevel.DELIVERY_DIAGNOSTIC -> LocalAlarmSourceKind.DELIVERY_DIAGNOSTIC
                else -> LocalAlarmSourceKind.GLUCOSE
            }
            val input = evidence.copy(key = evidence.key.copy(kind = kind), level = level)
            var state: LocalAlarmState? = null
            var time = 1_000L
            for (interval in listOf(300_000L, 600_000L, 1_200_000L, 1_800_000L, 1_800_000L)) {
                val next = LocalAlarmPolicy.evaluate(input.copy(observedElapsedMs = time), state, at(time), timing)
                assertEquals(LocalAlarmAdmission.START, next.admission)
                assertEquals(interval, next.state!!.nextDueElapsedMs!! - time)
                state = LocalAlarmPersistenceCodec.decodeState(LocalAlarmPersistenceCodec.encodeState(next.state!!)!!)!!
                    .copy(cycleActive = false)
                time = state.nextDueElapsedMs!!
            }
        }
    }

    @Test fun freshSameLevelEvidenceDoesNotResetBackoffOrSlideDue() {
        val first = LocalAlarmPolicy.evaluate(evidence, null, environment, timing).state!!
        val second = LocalAlarmPolicy.evaluate(evidence, first, at(301_000), timing).state!!
        val refreshed = LocalAlarmPolicy.evaluate(evidence.copy(observedElapsedMs = 400_000), second,
            at(400_000), timing)
        assertEquals(LocalAlarmAdmission.WAITING, refreshed.admission)
        assertEquals(901_000L, refreshed.state!!.nextDueElapsedMs)
        assertEquals(2L, refreshed.state!!.ordinal)
    }

    @Test fun newGenerationResetsBackoffButNeverReusesOrdinal() {
        val first = LocalAlarmPolicy.evaluate(evidence, null, environment, timing).state!!
        val second = LocalAlarmPolicy.evaluate(evidence, first, at(301_000), timing).state!!
        val changed = LocalAlarmPolicy.evaluate(evidence.copy(generation = 2), second, at(302_000), timing)
        assertEquals(3L, changed.startCycle!!.ordinal)
        assertEquals(602_000L, changed.state!!.nextDueElapsedMs)
    }

    @Test fun criticalRepeatsNeverBackOffAndRetainReachedVolume() {
        for (level in listOf(LocalAlarmLevel.CRITICAL_5, LocalAlarmLevel.LOW_NOW)) {
            val input = evidence.copy(level = level)
            var state: LocalAlarmState? = null
            var time = 1_000L
            repeat(8) {
                val next = LocalAlarmPolicy.evaluate(input, state, at(time), timing)
                assertEquals(LocalAlarmAdmission.START, next.admission)
                assertEquals(time + 120_000, next.state!!.nextDueElapsedMs)
                if (it > 0) assertTrue(next.profile!!.steps.all { step -> step.targetPercent == 100 })
                state = next.state!!.copy(cycleActive = false, reachedPercent = 100)
                time = state!!.nextDueElapsedMs!!
            }
        }
    }

    @Test fun acknowledgementUsesBaseIntervalNotBackedOffInterval() {
        var state = LocalAlarmPolicy.evaluate(evidence, null, environment, timing).state!!
        state = LocalAlarmPolicy.evaluate(evidence, state, at(301_000), timing).state!!
        val action = LocalAlarmAcknowledgement(state.key, state.generation, state.ordinal, state.level)
        val paused = LocalAlarmPolicy.acknowledge(evidence, state, at(302_000), timing, action)!!
        assertEquals(611_000L, paused.pause!!.untilWallMs)
        assertEquals(LocalAlarmAdmission.ACKNOWLEDGED,
            LocalAlarmPolicy.evaluate(evidence, paused, at(601_999), timing).admission)
        val resumed = LocalAlarmPolicy.evaluate(evidence, paused, at(602_000), timing)
        assertEquals(902_000L, resumed.state!!.nextDueElapsedMs)
    }

    @Test fun configuredMinimumAndMaximumRemainBoundsForNoncriticalBackoff() {
        val settings = timing.copy(softRepeatMinutes = 20)
        val first = LocalAlarmPolicy.evaluate(evidence, null, environment, settings).state!!
        assertEquals(1_201_000L, first.nextDueElapsedMs)
        val second = LocalAlarmPolicy.evaluate(evidence, first, at(1_201_000), settings)
        assertEquals(3_001_000L, second.state!!.nextDueElapsedMs)
    }

    @Test fun cooldownDoesNotAcknowledgeOrAdvanceSourceAndNeverBlocksCritical() {
        val waiting = environment.copy(noncriticalCooldownUntilElapsedMs = 301_000)
        val deferred = LocalAlarmPolicy.evaluate(evidence, null, waiting, timing)
        assertEquals(LocalAlarmAdmission.COOLDOWN, deferred.admission)
        assertEquals(0L, deferred.state!!.ordinal)
        assertNull(deferred.state!!.lastCycle)
        assertNull(deferred.state!!.pause)
        assertNull(deferred.state!!.nextDueElapsedMs)
        for (deadline in listOf(301_000L, null)) {
            val env = waiting.copy(noncriticalCooldownUntilElapsedMs = deadline)
            assertEquals(LocalAlarmAdmission.START, LocalAlarmPolicy.evaluate(
                evidence.copy(level = LocalAlarmLevel.LOW_NOW), null, env, timing).admission)
        }
    }

    @Test fun activeOwnerRemainsAdmittedDuringItsOwnConfirmedCooldown() {
        val initial = LocalAlarmPolicy.evaluate(evidence, null, environment, timing).state!!
        val env = at(2_000).copy(noncriticalCooldownUntilElapsedMs = 301_000)
        assertEquals(LocalAlarmAdmission.ACTIVE, LocalAlarmPolicy.evaluate(evidence, initial, env, timing).admission)
    }

    @Test fun globalOffStillWinsDuringCriticalAndNoncriticalCooldown() {
        val env = environment.copy(noncriticalCooldownUntilElapsedMs = 301_000, mutedUntilWallMs = 70_000)
        for (level in listOf(LocalAlarmLevel.WARNING_30, LocalAlarmLevel.LOW_NOW)) {
            assertEquals(LocalAlarmAdmission.MUTED, LocalAlarmPolicy.evaluate(evidence.copy(level = level), null, env, timing).admission)
        }
    }
}
