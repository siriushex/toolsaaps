package io.aaps.copilot.domain.alerts

import org.junit.Assert.*
import org.junit.Test

class LocalAlarmArbitrationPolicyTest {
    private val environment = LocalAlarmEnvironment(true, true, 100_000, 200_000, 2)
    private val timing = LocalAlarmTiming()
    private fun request(id: String = "source-a", level: LocalAlarmLevel = LocalAlarmLevel.WARNING_30,
        pending: Long = 1_000, kind: LocalAlarmSourceKind = LocalAlarmSourceKind.GLUCOSE) =
        LocalAlarmRequest(LocalAlarmEvidence(LocalAlarmKey(kind, id), 1, level, 2, 90_000, 900_000, true), pending)
    private fun active(request: LocalAlarmRequest): LocalAlarmState =
        LocalAlarmPolicy.evaluate(request.evidence, null, environment, timing).state!!
    private fun decide(requests: List<LocalAlarmRequest>, states: List<LocalAlarmState> = emptyList(),
        owner: LocalAlarmCycle? = null, env: LocalAlarmEnvironment = environment) =
        LocalAlarmArbitrationPolicy.evaluate(requests, states.associateBy { it.key }, owner, env, timing)

    @Test fun priorityOrderAndSilentSourcesDoNotClaimSelection() {
        val requests = listOf(request("high", LocalAlarmLevel.SOFT_HIGH_RISK),
            request("pump", LocalAlarmLevel.PUMP_LINK, kind = LocalAlarmSourceKind.PUMP_LINK),
            request("diagnostic", LocalAlarmLevel.DELIVERY_DIAGNOSTIC, kind = LocalAlarmSourceKind.DELIVERY_DIAGNOSTIC),
            request("warning"), request("critical", LocalAlarmLevel.CRITICAL_5), request("low", LocalAlarmLevel.LOW_NOW))
        for (count in requests.size downTo 1) {
            val choice = decide(requests.take(count).reversed() + request("watch", LocalAlarmLevel.WATCH_60))
            assertEquals(requests[count - 1].evidence, choice.selected)
            assertEquals(LocalAlarmArbitrationStatus.READY, choice.status)
        }
        assertEquals(LocalAlarmArbitrationStatus.IDLE, decide(listOf(request(level = LocalAlarmLevel.NONE))).status)
    }

    @Test fun equalPriorityKeepsExactOwnerDespiteOlderQueueEntry() {
        val current = request(pending = 80_000); val state = active(current)
        val decision = decide(listOf(request("older", pending = 1), current), listOf(state), state.activeCycle)
        assertEquals(state.activeCycle, decision.retainedCycle)
        assertNull(decision.selected); assertNull(decision.cancelCycle)
        assertEquals(state.activeCycle!!.deadlineElapsedMs, decision.nextWakeElapsedMs)
    }

    @Test fun higherEligibleSourcePreemptsOnlyAfterExactCleanup() {
        val current = request(); val state = active(current)
        val high = request("low", LocalAlarmLevel.LOW_NOW)
        val decision = decide(listOf(current, high), listOf(state), state.activeCycle)
        assertEquals(state.activeCycle, decision.cancelCycle)
        assertEquals(high.evidence, decision.selected); assertNull(decision.retainedCycle)
    }

    @Test fun pausedHigherSourceDoesNotPreemptAndSchedulesItsDeadline() {
        val current = request(); val state = active(current)
        val high = request("low", LocalAlarmLevel.LOW_NOW); val highState = active(high)
        val paused = LocalAlarmPolicy.acknowledge(high.evidence, highState, environment, timing,
            LocalAlarmAcknowledgement(highState.key, 1, 1, highState.level))!!
        val decision = decide(listOf(current, high), listOf(state, paused), state.activeCycle)
        assertEquals(state.activeCycle, decision.retainedCycle)
        assertNull(decision.cancelCycle)
    }

    @Test fun ownerLossOrAuthorityLossCancelsExactCycleWithoutRetainingIt() {
        val current = request(); val state = active(current)
        val losses = listOf(emptyList(), listOf(current.copy(evidence = current.evidence.copy(authorized = false))),
            listOf(current.copy(evidence = current.evidence.copy(validUntilElapsedMs = environment.nowElapsedMs))),
            listOf(current.copy(evidence = current.evidence.copy(bootCount = 1))),
            listOf(current.copy(evidence = current.evidence.copy(level = LocalAlarmLevel.NONE))))
        losses.forEach { sources ->
            val decision = decide(sources, listOf(state), state.activeCycle)
            assertEquals(state.activeCycle, decision.cancelCycle)
            assertNull(decision.retainedCycle); assertNull(decision.selected)
        }
    }

    @Test fun freshReplacementGenerationAndLevelNeverAdoptOldOwner() {
        val current = request(); val state = active(current)
        for (changed in listOf(current.evidence.copy(generation = 2), current.evidence.copy(level = LocalAlarmLevel.CRITICAL_5))) {
            val decision = decide(listOf(current.copy(evidence = changed)), listOf(state), state.activeCycle)
            assertEquals(changed, decision.selected); assertEquals(state.activeCycle, decision.cancelCycle)
        }
    }

    @Test fun disabledUnavailableAndOffCancelWithoutSelection() {
        val source = request(); val state = active(source)
        for (env in listOf(environment.copy(enabled = false), environment.copy(armed = false),
            environment.copy(mutedUntilWallMs = environment.nowWallMs + 20_000))) {
            val decision = decide(listOf(source), listOf(state), state.activeCycle, env)
            assertEquals(state.activeCycle, decision.cancelCycle); assertNull(decision.selected)
            assertEquals(if (env.enabled && env.armed) 120_000L else null, decision.nextWakeElapsedMs)
        }
    }

    @Test fun oldestDueWinsAfterCycleEndsAndTiesAreStable() {
        val first = request("source-z", pending = 1); val state = active(first).copy(cycleActive = false)
        val second = request("source-b", pending = 200); val third = request("source-a", pending = 200)
        val env = environment.copy(nowElapsedMs = 500_000)
        val decision = decide(listOf(first, second, third), listOf(state), env = env)
        assertEquals(third.evidence, decision.selected)
        assertEquals(third.evidence, decide(listOf(third, second, first), listOf(state), env = env).selected)
    }

    @Test fun duplicateEvidenceRefreshDoesNotMovePendingOrPersistedDue() {
        val first = request(pending = 1); val second = request("source-b", pending = 2)
        val refreshed = first.copy(evidence = first.evidence.copy(observedElapsedMs = 99_000))
        assertEquals(refreshed.evidence, decide(listOf(second, refreshed)).selected)
        val state = active(first).copy(cycleActive = false)
        assertEquals(state.nextDueElapsedMs, decide(listOf(refreshed), listOf(state)).nextWakeElapsedMs)
        assertEquals(1L, state.ordinal)
    }

    @Test fun staleOrMalformedHighSourceCannotSuppressFreshLowerSource() {
        val lower = request(); val high = request("low", LocalAlarmLevel.LOW_NOW)
        for (evidence in listOf(high.evidence.copy(authorized = false), high.evidence.copy(generation = 0),
            high.evidence.copy(observedElapsedMs = 100_001), high.evidence.copy(validUntilElapsedMs = 100_000),
            high.evidence.copy(key = LocalAlarmKey(LocalAlarmSourceKind.PUMP_LINK, "bad")))) {
            assertEquals(lower.evidence, decide(listOf(lower, high.copy(evidence = evidence))).selected)
        }
    }

    @Test fun unownedOrConflictingClaimsRequireRecoveryNeverAdoption() {
        val first = request(); val firstState = active(first)
        val second = request("source-b"); val secondState = active(second)
        val unowned = decide(listOf(first), listOf(firstState))
        assertEquals(LocalAlarmArbitrationStatus.RECOVERY_REQUIRED, unowned.status)
        assertEquals(listOf(firstState.activeCycle), unowned.interruptedCycles); assertNull(unowned.selected)
        val conflict = decide(listOf(first, second), listOf(firstState, secondState), firstState.activeCycle)
        assertEquals(firstState.activeCycle, conflict.cancelCycle)
        assertEquals(listOf(secondState.activeCycle), conflict.interruptedCycles); assertNull(conflict.selected)
    }

    @Test fun staleCancellationIdentityCannotRetainOrAdoptActualClaim() {
        val source = request(); val state = active(source)
        val stale = state.activeCycle!!.copy(ordinal = 99)
        val decision = decide(listOf(source), listOf(state), stale)
        assertEquals(stale, decision.cancelCycle)
        assertEquals(listOf(state.activeCycle), decision.interruptedCycles); assertNull(decision.selected)
    }

    @Test fun expiryAndAcknowledgementUseNearestFutureDeadlineWithoutBusyLoop() {
        val source = request(); val active = active(source)
        val paused = LocalAlarmPolicy.acknowledge(source.evidence, active, environment, timing,
            LocalAlarmAcknowledgement(active.key, 1, 1, active.level))!!
        assertEquals(400_000L, decide(listOf(source), listOf(paused)).nextWakeElapsedMs)
        val expiring = source.copy(evidence = source.evidence.copy(validUntilElapsedMs = 120_000))
        assertEquals(120_000L, decide(listOf(expiring), listOf(paused)).nextWakeElapsedMs)
        val env = environment.copy(nowElapsedMs = 400_000, nowWallMs = 500_000)
        assertEquals(source.evidence, decide(listOf(source), listOf(paused), env = env).selected)
    }

    @Test fun invalidSnapshotAndClockFailClosedWithExactCancellation() {
        val source = request(); val state = active(source)
        for (sources in listOf(listOf(source, source), listOf(source.copy(pendingSinceElapsedMs = -1)),
            listOf(source.copy(pendingSinceElapsedMs = 100_001)), (1..65).map { request("source-$it") })) {
            val decision = decide(sources, listOf(state), state.activeCycle)
            assertEquals(LocalAlarmArbitrationStatus.INVALID_SNAPSHOT, decision.status)
            assertEquals(state.activeCycle, decision.cancelCycle); assertNull(decision.selected)
        }
        assertEquals(LocalAlarmArbitrationStatus.INVALID_SNAPSHOT,
            decide(emptyList(), env = environment.copy(bootCount = -1)).status)
    }

    @Test fun corruptedStateFailsWholeSnapshotRatherThanResettingOrdinal() {
        val source = request(); val state = active(source).copy(ordinal = -1)
        val decision = decide(listOf(source, request("fresh")), listOf(state))
        assertEquals(LocalAlarmArbitrationStatus.INVALID_SNAPSHOT, decision.status); assertNull(decision.selected)
    }

    @Test fun wallToElapsedDeadlineOverflowStillRetainsFiniteSourceExpiry() {
        val env = environment.copy(nowElapsedMs = Long.MAX_VALUE - 10, nowWallMs = 10)
        val source = request().copy(evidence = request().evidence.copy(observedElapsedMs = env.nowElapsedMs,
            validUntilElapsedMs = Long.MAX_VALUE), pendingSinceElapsedMs = env.nowElapsedMs)
        val decision = decide(listOf(source), env = env.copy(mutedUntilWallMs = Long.MAX_VALUE))
        assertNull(decision.selected); assertEquals(Long.MAX_VALUE, decision.nextWakeElapsedMs)
    }
}
