package io.aaps.copilot.domain.alerts

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class LocalAlarmPolicyTest {
    private val now = 1_000_000L
    private val wall = 10_000_000L
    private val timing = LocalAlarmTiming()
    private val key = LocalAlarmKey(LocalAlarmSourceKind.GLUCOSE, "episode-a")
    private val environment = LocalAlarmEnvironment(true, true, now, wall, 7)
    private val evidence = LocalAlarmEvidence(key, 1L, LocalAlarmLevel.WARNING_30, 7,
        now, now + 3_000_000L, authorized = true)

    private fun initial(level: LocalAlarmLevel = evidence.level): LocalAlarmState =
        LocalAlarmPolicy.evaluate(evidence.copy(level = level), null, environment, timing).state!!

    private fun at(elapsed: Long) = environment.copy(nowElapsedMs = elapsed, nowWallMs = wall + elapsed - now)
    private fun action(state: LocalAlarmState) =
        LocalAlarmAcknowledgement(state.key, state.generation, state.ordinal, state.level)

    @Test fun firstAuthorizedRiskClaimsOneBoundedCurrentCycle() {
        val result = LocalAlarmPolicy.evaluate(evidence, null, environment, timing)
        assertThat(result.admission).isEqualTo(LocalAlarmAdmission.START)
        assertThat(result.startCycle!!.ordinal).isEqualTo(1L)
        assertThat(result.startCycle!!.deadlineElapsedMs).isEqualTo(now + 55_000L)
        assertThat(result.state!!.nextDueElapsedMs).isEqualTo(now + 300_000L)
        assertThat(result.state!!.reachedPercent).isEqualTo(0)
        assertThat(result.cancelActive).isFalse()
    }

    @Test fun duplicateEvaluationDoesNotRestartCycleOrSlideNextDue() {
        val state = initial()
        val result = LocalAlarmPolicy.evaluate(evidence, state, at(now + 1_000L), timing)
        assertThat(result.admission).isEqualTo(LocalAlarmAdmission.ACTIVE)
        assertThat(result.startCycle).isNull()
        assertThat(result.state).isEqualTo(state)
    }

    @Test fun cycleDeadlineCancelsActiveClaimButRepeatStillWaits() {
        val result = LocalAlarmPolicy.evaluate(evidence, initial(), at(now + 55_000L), timing)
        assertThat(result.cancelActive).isTrue()
        assertThat(result.startCycle).isNull()
        assertThat(result.state!!.activeCycle).isNull()
        assertThat(result.state!!.nextDueElapsedMs).isEqualTo(now + 300_000L)
    }

    @Test fun repeatAtExactDueCreatesNextOrdinalAndRebasesDeadline() {
        val result = LocalAlarmPolicy.evaluate(evidence, initial(), at(now + 300_000L), timing)
        assertThat(result.startCycle!!.ordinal).isEqualTo(2L)
        assertThat(result.state!!.nextDueElapsedMs).isEqualTo(now + 600_000L)
    }

    @Test fun longPauseDoesNotReplayBacklog() {
        val later = at(now + 1_500_000L)
        val fresh = evidence.copy(observedElapsedMs = later.nowElapsedMs)
        val result = LocalAlarmPolicy.evaluate(fresh, initial(), later, timing)
        assertThat(result.startCycle!!.ordinal).isEqualTo(2L)
        assertThat(result.state!!.nextDueElapsedMs).isEqualTo(later.nowElapsedMs + 300_000L)
    }

    @Test fun unchangedEvidenceBeforeDueDoesNotSlideRepeatEvenWhenWallClockChanges() {
        val env = at(now + 100_000L).copy(nowWallMs = wall + 20_000_000L)
        val result = LocalAlarmPolicy.evaluate(evidence, initial(), env, timing)
        assertThat(result.startCycle).isNull()
        assertThat(result.state!!.nextDueElapsedMs).isEqualTo(now + 300_000L)
    }

    @Test fun offCancelsActiveAndDoesNotTreatClinicalEventAsResolved() {
        val result = LocalAlarmPolicy.evaluate(evidence, initial(), environment.copy(mutedUntilWallMs = wall + 60_000L), timing)
        assertThat(result.admission).isEqualTo(LocalAlarmAdmission.MUTED)
        assertThat(result.cancelActive).isTrue()
        assertThat(result.state!!.activeCycle).isNull()
        assertThat(result.state!!.level).isEqualTo(LocalAlarmLevel.WARNING_30)
    }

    @Test fun continuingSuppressedRiskIsEligibleAtExactOffExpiry() {
        val off = environment.copy(mutedUntilWallMs = wall + 60_000L)
        val suppressed = LocalAlarmPolicy.evaluate(evidence, null, off, timing).state!!
        assertThat(suppressed.ordinal).isEqualTo(0L)
        assertThat(LocalAlarmPolicy.evaluate(evidence, suppressed,
            off.copy(nowWallMs = wall + 59_999L, nowElapsedMs = now + 59_999L), timing).startCycle).isNull()
        val result = LocalAlarmPolicy.evaluate(evidence, suppressed,
            off.copy(nowWallMs = wall + 60_000L, nowElapsedMs = now + 60_000L), timing)
        assertThat(result.startCycle!!.ordinal).isEqualTo(1L)
    }

    @Test fun offResumeDoesNotWaitForPreviouslyScheduledRepeat() {
        val off = environment.copy(mutedUntilWallMs = wall + 20_000L)
        val stopped = LocalAlarmPolicy.evaluate(evidence, initial(), off, timing).state!!
        val result = LocalAlarmPolicy.evaluate(evidence, stopped,
            off.copy(nowWallMs = wall + 20_000L, nowElapsedMs = now + 20_000L), timing)
        assertThat(result.startCycle!!.ordinal).isEqualTo(2L)
    }

    @Test fun urgentLowCannotBypassExplicitGlobalOff() {
        val result = LocalAlarmPolicy.evaluate(evidence.copy(level = LocalAlarmLevel.LOW_NOW), null,
            environment.copy(mutedUntilWallMs = wall + 1L), timing)
        assertThat(result.admission).isEqualTo(LocalAlarmAdmission.MUTED)
        assertThat(result.startCycle).isNull()
    }

    @Test fun disabledAndUnarmedCancelRatherThanClaim() {
        for (env in listOf(environment.copy(enabled = false), environment.copy(armed = false))) {
            val result = LocalAlarmPolicy.evaluate(evidence, initial(), env, timing)
            assertThat(result.startCycle).isNull()
            assertThat(result.cancelActive).isTrue()
        }
    }

    @Test fun staleFutureUnauthorizedAndPreviousBootEvidenceCancel() {
        for (input in listOf(evidence.copy(validUntilElapsedMs = now),
            evidence.copy(observedElapsedMs = now + 1L), evidence.copy(authorized = false),
            evidence.copy(bootCount = 6), evidence.copy(observedElapsedMs = -1L),
            evidence.copy(generation = 0L))) {
            val result = LocalAlarmPolicy.evaluate(input, initial(), environment, timing)
            assertThat(result.admission).isEqualTo(LocalAlarmAdmission.INVALID_EVIDENCE)
            assertThat(result.startCycle).isNull()
            assertThat(result.cancelActive).isTrue()
        }
    }

    @Test fun evidenceExpiryIsExclusiveEvenWhileCycleWasActive() {
        val result = LocalAlarmPolicy.evaluate(evidence.copy(validUntilElapsedMs = now + 10_000L),
            initial(), at(now + 10_000L), timing)
        assertThat(result.startCycle).isNull()
        assertThat(result.cancelActive).isTrue()
    }

    @Test fun silentStagesCancelImmediatelyWithoutEpisodeHysteresis() {
        for (level in listOf(LocalAlarmLevel.NONE, LocalAlarmLevel.WATCH_60)) {
            val result = LocalAlarmPolicy.evaluate(evidence.copy(level = level), initial(), environment, timing)
            assertThat(result.admission).isEqualTo(LocalAlarmAdmission.SILENT)
            assertThat(result.cancelActive).isTrue()
            assertThat(result.startCycle).isNull()
        }
    }

    @Test fun typedSourceCannotClaimAnotherSourcesLevel() {
        for (kind in listOf(LocalAlarmSourceKind.PUMP_LINK, LocalAlarmSourceKind.DELIVERY_DIAGNOSTIC)) {
            val result = LocalAlarmPolicy.evaluate(evidence.copy(key = key.copy(kind = kind)), null, environment, timing)
            assertThat(result.admission).isEqualTo(LocalAlarmAdmission.INVALID_EVIDENCE)
        }
        assertThat(LocalAlarmPolicy.evaluate(evidence.copy(level = LocalAlarmLevel.PUMP_LINK), null,
            environment, timing).startCycle).isNull()
    }

    @Test fun technicalSourcesHaveIndependentValidTypedClaims() {
        for ((kind, level) in listOf(LocalAlarmSourceKind.PUMP_LINK to LocalAlarmLevel.PUMP_LINK,
            LocalAlarmSourceKind.DELIVERY_DIAGNOSTIC to LocalAlarmLevel.DELIVERY_DIAGNOSTIC)) {
            val input = evidence.copy(key = LocalAlarmKey(kind, "source-a"), level = level)
            assertThat(LocalAlarmPolicy.evaluate(input, null, environment, timing).startCycle!!.key).isEqualTo(input.key)
        }
    }

    @Test fun invalidKeysAndClocksCannotAuthorizeStart() {
        for (id in listOf("", " ", "source\n", "x".repeat(129))) {
            assertThat(LocalAlarmPolicy.evaluate(evidence.copy(key = key.copy(id = id)), null,
                environment, timing).startCycle).isNull()
        }
        for (env in listOf(environment.copy(nowElapsedMs = -1L), environment.copy(nowWallMs = -1L),
            environment.copy(bootCount = -1), environment.copy(mutedUntilWallMs = -1L))) {
            assertThat(LocalAlarmPolicy.evaluate(evidence, initial(), env, timing).startCycle).isNull()
        }
    }

    @Test fun acknowledgementPausesOnlyCurrentSourceAndCancelsActiveCycle() {
        val state = initial()
        val paused = LocalAlarmPolicy.acknowledge(evidence, state, environment, timing, action(state))!!
        assertThat(paused.activeCycle).isNull()
        assertThat(paused.pause!!.untilWallMs).isEqualTo(wall + 300_000L)
        val result = LocalAlarmPolicy.evaluate(evidence, paused, at(now + 299_999L), timing)
        assertThat(result.admission).isEqualTo(LocalAlarmAdmission.ACKNOWLEDGED)
        assertThat(result.startCycle).isNull()
        val other = evidence.copy(key = key.copy(id = "episode-b"))
        assertThat(LocalAlarmPolicy.evaluate(other, null, environment, timing).startCycle).isNotNull()
    }

    @Test fun acknowledgementExpiresAtExactWallDeadline() {
        val state = initial()
        val paused = LocalAlarmPolicy.acknowledge(evidence, state, environment, timing, action(state))!!
        val result = LocalAlarmPolicy.evaluate(evidence, paused, at(now + 300_000L), timing)
        assertThat(result.startCycle!!.ordinal).isEqualTo(2L)
        assertThat(result.state!!.pause).isNull()
    }

    @Test fun duplicateAcknowledgementDoesNotExtendPause() {
        val state = initial()
        val paused = LocalAlarmPolicy.acknowledge(evidence, state, environment, timing, action(state))!!
        assertThat(LocalAlarmPolicy.acknowledge(evidence, paused, at(now + 30_000L), timing, action(state)))
            .isEqualTo(paused)
    }

    @Test fun urgentSeverityInvalidatesPauseImmediately() {
        val state = initial()
        val paused = LocalAlarmPolicy.acknowledge(evidence, state, environment, timing, action(state))!!
        val result = LocalAlarmPolicy.evaluate(evidence.copy(level = LocalAlarmLevel.LOW_NOW), paused,
            at(now + 1_000L), timing)
        assertThat(result.startCycle!!.level).isEqualTo(LocalAlarmLevel.LOW_NOW)
        assertThat(result.profile!!.steps.first().targetPercent).isAtLeast(70)
        assertThat(result.state!!.pause).isNull()
    }

    @Test fun staleOrdinalLevelGenerationOrSourceCannotAcknowledgeRisk() {
        val state = initial()
        val invalid = listOf(action(state).copy(ordinal = 0L), action(state).copy(generation = 2L),
            action(state).copy(key = key.copy(id = "episode-b")), action(state).copy(level = LocalAlarmLevel.LOW_NOW))
        invalid.forEach { assertThat(LocalAlarmPolicy.acknowledge(evidence, state, environment, timing, it)).isEqualTo(state) }
        assertThat(LocalAlarmPolicy.acknowledge(evidence.copy(level = LocalAlarmLevel.LOW_NOW), state,
            environment, timing, action(state))).isEqualTo(state)
    }

    @Test fun staleAuthorityCannotCreateAcknowledgementPause() {
        val state = initial()
        assertThat(LocalAlarmPolicy.acknowledge(evidence.copy(authorized = false), state, environment,
            timing, action(state))).isEqualTo(state)
    }

    @Test fun oldActionCannotAcknowledgeLaterRepeat() {
        val state = initial()
        val next = LocalAlarmPolicy.evaluate(evidence, state, at(now + 300_000L), timing).state!!
        assertThat(LocalAlarmPolicy.acknowledge(evidence, next, at(now + 300_000L), timing, action(state))).isEqualTo(next)
    }

    @Test fun freshGenerationClearsPauseButRetainsMonotonicSourceOrdinal() {
        val state = initial()
        val paused = LocalAlarmPolicy.acknowledge(evidence, state, environment, timing, action(state))!!
        val result = LocalAlarmPolicy.evaluate(evidence.copy(generation = 2L), paused, environment, timing)
        assertThat(result.startCycle!!.generation).isEqualTo(2L)
        assertThat(result.startCycle!!.ordinal).isEqualTo(2L)
        assertThat(result.state!!.pause).isNull()
    }

    @Test fun strongerLevelPreemptsActiveLowerCycle() {
        val result = LocalAlarmPolicy.evaluate(evidence.copy(level = LocalAlarmLevel.CRITICAL_5), initial(),
            at(now + 1_000L), timing)
        assertThat(result.cancelActive).isTrue()
        assertThat(result.startCycle!!.level).isEqualTo(LocalAlarmLevel.CRITICAL_5)
        assertThat(result.startCycle!!.ordinal).isEqualTo(2L)
    }

    @Test fun lowerAcceptedLevelStopsOldStrongClip() {
        val result = LocalAlarmPolicy.evaluate(evidence, initial(LocalAlarmLevel.LOW_NOW),
            at(now + 1_000L), timing)
        assertThat(result.cancelActive).isTrue()
        assertThat(result.startCycle!!.level).isEqualTo(LocalAlarmLevel.WARNING_30)
    }

    @Test fun proposedTimelineDoesNotPretendAllVolumeStepsWereReached() {
        val state = initial()
        val next = LocalAlarmPolicy.evaluate(evidence, state, at(now + 300_000L), timing)
        assertThat(next.profile!!.steps.first().targetPercent).isEqualTo(25)
        assertThat(next.state!!.reachedPercent).isEqualTo(0)
    }

    @Test fun confirmedProgressCarriesIntoNextCycleWithoutReturningToGentleStep() {
        val state = initial()
        val progressed = LocalAlarmPolicy.recordReached(state, state.activeCycle!!, 75, environment)
        val result = LocalAlarmPolicy.evaluate(evidence, progressed, at(now + 300_000L), timing)
        assertThat(result.profile!!.steps.first().targetPercent).isEqualTo(75)
        assertThat(result.state!!.reachedPercent).isEqualTo(75)
    }

    @Test fun lateMismatchedCancelledOrInvalidProgressIsIgnored() {
        val state = initial()
        val cycle = state.activeCycle!!
        assertThat(LocalAlarmPolicy.recordReached(state, cycle.copy(ordinal = 2L), 75, environment)).isEqualTo(state)
        assertThat(LocalAlarmPolicy.recordReached(state, cycle, 101, environment)).isEqualTo(state)
        assertThat(LocalAlarmPolicy.recordReached(state, cycle, 40, environment)).isEqualTo(state)
        assertThat(LocalAlarmPolicy.recordReached(state, cycle, 75, at(now + 55_000L))).isEqualTo(state)
        assertThat(LocalAlarmPolicy.recordReached(state, cycle, 75, environment.copy(mutedUntilWallMs = wall + 1L))).isEqualTo(state)
        val stopped = LocalAlarmPolicy.evaluate(evidence, state, environment.copy(enabled = false), timing).state!!
        assertThat(LocalAlarmPolicy.recordReached(stopped, cycle, 75, environment)).isEqualTo(stopped)
    }

    @Test fun corruptStateFailsAdmissionWithoutResettingCounterAndClaiming() {
        val state = initial()
        val invalid = listOf(state.copy(ordinal = -1L), state.copy(reachedPercent = 101),
            state.copy(nextDueElapsedMs = Long.MAX_VALUE),
            state.copy(lastCycle = state.lastCycle!!.copy(ordinal = 9L)),
            state.copy(pause = LocalAlarmPause(wall, wall + 60_000L, LocalAlarmLevel.WARNING_30)),
            state.copy(key = key.copy(id = "episode-b")))
        invalid.forEach {
            val result = LocalAlarmPolicy.evaluate(evidence, it, environment, timing)
            assertThat(result.admission).isEqualTo(LocalAlarmAdmission.INVALID_STATE)
            assertThat(result.startCycle).isNull()
        }
    }

    @Test fun elapsedClockRollbackCannotReuseClaimFromTheFuture() {
        val result = LocalAlarmPolicy.evaluate(evidence.copy(observedElapsedMs = now - 1L), initial(),
            at(now - 1L), timing)
        assertThat(result.admission).isEqualTo(LocalAlarmAdmission.INVALID_STATE)
        assertThat(result.startCycle).isNull()
    }

    @Test fun freshBootDoesNotReuseOldActiveClaimOrProgress() {
        val state = initial()
        val progressed = LocalAlarmPolicy.recordReached(state, state.activeCycle!!, 100, environment)
        val result = LocalAlarmPolicy.evaluate(evidence.copy(bootCount = 8, observedElapsedMs = 1L,
            validUntilElapsedMs = 500_000L), progressed,
            environment.copy(bootCount = 8, nowElapsedMs = 1L), timing)
        assertThat(result.cancelActive).isTrue()
        assertThat(result.startCycle!!.ordinal).isEqualTo(2L)
        assertThat(result.state!!.reachedPercent).isEqualTo(0)
    }

    @Test fun counterAndElapsedDeadlineOverflowCannotClaim() {
        val exhausted = initial().copy(ordinal = Long.MAX_VALUE, lastCycle = null,
            cycleActive = false, nextDueElapsedMs = null)
        assertThat(LocalAlarmPolicy.evaluate(evidence, exhausted, environment, timing).admission)
            .isEqualTo(LocalAlarmAdmission.OVERFLOW)
        val env = environment.copy(nowElapsedMs = Long.MAX_VALUE - 1L)
        val input = evidence.copy(observedElapsedMs = env.nowElapsedMs, validUntilElapsedMs = Long.MAX_VALUE)
        assertThat(LocalAlarmPolicy.evaluate(input, null, env, timing).startCycle).isNull()
    }

    @Test fun acknowledgementWallDeadlineOverflowCannotMutateState() {
        val state = initial()
        val env = environment.copy(nowWallMs = Long.MAX_VALUE - 1L)
        assertThat(LocalAlarmPolicy.acknowledge(evidence, state, env, timing, action(state))).isEqualTo(state)
    }

    @Test fun changedRepeatSettingsApplyToNewClaimWithoutSlidingOldDue() {
        val settings = timing.copy(softRepeatMinutes = 10)
        assertThat(LocalAlarmPolicy.evaluate(evidence, initial(), at(now + 100_000L), settings).state!!.nextDueElapsedMs)
            .isEqualTo(now + 300_000L)
        assertThat(LocalAlarmPolicy.evaluate(evidence, initial(), at(now + 300_000L), settings).state!!.nextDueElapsedMs)
            .isEqualTo(now + 900_000L)
    }

    @Test fun corruptTimingCannotAuthorizeEvenAnExistingActiveCycle() {
        val result = LocalAlarmPolicy.evaluate(evidence, initial(), environment, timing.copy(strongClipMs = -1))
        assertThat(result.startCycle).isNull()
        assertThat(result.cancelActive).isTrue()
        assertThat(result.admission).isEqualTo(LocalAlarmAdmission.INVALID_TIMING)
    }

    @Test fun lowerLevelDuringAcknowledgementKeepsAValidPauseUntilExpiry() {
        val low = evidence.copy(level = LocalAlarmLevel.LOW_NOW)
        val state = initial(LocalAlarmLevel.LOW_NOW)
        val paused = LocalAlarmPolicy.acknowledge(low, state, environment, timing, action(state))!!
        val lower = LocalAlarmPolicy.evaluate(evidence, paused, at(now + 1_000L), timing).state!!
        val repeated = LocalAlarmPolicy.evaluate(evidence, lower, at(now + 2_000L), timing)
        assertThat(repeated.admission).isEqualTo(LocalAlarmAdmission.ACKNOWLEDGED)
        assertThat(repeated.startCycle).isNull()
        val resumed = LocalAlarmPolicy.evaluate(evidence, repeated.state, at(now + 120_000L), timing)
        assertThat(resumed.startCycle!!.level).isEqualTo(LocalAlarmLevel.WARNING_30)
        assertThat(resumed.startCycle!!.ordinal).isEqualTo(2L)
    }

    @Test fun olderGenerationCannotReopenAnActiveSource() {
        val newer = LocalAlarmPolicy.evaluate(evidence.copy(generation = 2L), initial(), environment, timing).state!!
        val result = LocalAlarmPolicy.evaluate(evidence, newer, environment, timing)
        assertThat(result.admission).isEqualTo(LocalAlarmAdmission.INVALID_EVIDENCE)
        assertThat(result.startCycle).isNull()
    }

    @Test fun reorderedObservationCannotPreemptWithAnOldLevel() {
        val env = at(now + 1_000L)
        val newer = LocalAlarmPolicy.evaluate(evidence.copy(observedElapsedMs = env.nowElapsedMs),
            initial(), env, timing).state!!
        val result = LocalAlarmPolicy.evaluate(evidence.copy(level = LocalAlarmLevel.LOW_NOW), newer,
            at(now + 2_000L), timing)
        assertThat(result.admission).isEqualTo(LocalAlarmAdmission.INVALID_EVIDENCE)
        assertThat(result.startCycle).isNull()
    }

    @Test fun acknowledgementAfterClipEndStillPausesTheCurrentOrdinal() {
        val ended = LocalAlarmPolicy.evaluate(evidence, initial(), at(now + 55_000L), timing).state!!
        val paused = LocalAlarmPolicy.acknowledge(evidence, ended, at(now + 60_000L), timing, action(ended))!!
        assertThat(paused.pause!!.untilWallMs).isEqualTo(wall + 360_000L)
        assertThat(paused.ordinal).isEqualTo(1L)
    }

    @Test fun corruptActiveFlagStillRequestsCancellationWhenClaimIsMissing() {
        val corrupt = initial().copy(lastCycle = null, nextDueElapsedMs = null)
        val result = LocalAlarmPolicy.evaluate(evidence, corrupt, environment, timing)
        assertThat(result.admission).isEqualTo(LocalAlarmAdmission.INVALID_STATE)
        assertThat(result.startCycle).isNull()
        assertThat(result.cancelActive).isTrue()
    }
}
