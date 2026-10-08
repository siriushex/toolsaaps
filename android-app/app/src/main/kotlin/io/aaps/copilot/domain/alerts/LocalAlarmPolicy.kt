package io.aaps.copilot.domain.alerts

object LocalAlarmPolicy {
    fun evaluate(
        evidence: LocalAlarmEvidence,
        previous: LocalAlarmState?,
        environment: LocalAlarmEnvironment,
        timing: LocalAlarmTiming
    ): LocalAlarmEvaluation {
        val oldActive = previous?.activeCycle
        fun stop(admission: LocalAlarmAdmission, state: LocalAlarmState? = previous) = LocalAlarmEvaluation(
            state = state?.copy(cycleActive = false, nextDueElapsedMs = null),
            admission = admission,
            cancelActive = previous?.cycleActive == true
        )

        if (!validEnvironment(environment)) return stop(LocalAlarmAdmission.INVALID_ENVIRONMENT)
        if (previous != null && (previous.key != evidence.key || !validState(previous, environment))) {
            return stop(LocalAlarmAdmission.INVALID_STATE)
        }
        if (!validEvidence(evidence, environment) || olderEvidence(evidence, previous)) {
            return stop(LocalAlarmAdmission.INVALID_EVIDENCE)
        }
        if (!timing.valid()) return stop(LocalAlarmAdmission.INVALID_TIMING)

        val sameIdentity = previous != null && previous.generation == evidence.generation &&
            previous.bootCount == evidence.bootCount
        var state = if (sameIdentity) {
            previous!!.copy(observedElapsedMs = evidence.observedElapsedMs, validUntilElapsedMs = evidence.validUntilElapsedMs)
        } else {
            LocalAlarmState(evidence.key, evidence.generation, evidence.level, evidence.bootCount,
                evidence.observedElapsedMs, evidence.validUntilElapsedMs, ordinal = previous?.ordinal ?: 0L)
        }
        if (state.level != evidence.level) {
            val keepsPause = state.pause?.let { evidence.level.priority <= it.level.priority } == true
            state = state.copy(level = evidence.level, lastCycle = state.lastCycle.takeIf { keepsPause },
                cycleActive = false, nextDueElapsedMs = null)
        }
        if (state.pause?.let { evidence.level.priority > it.level.priority } == true) {
            state = state.copy(pause = null, nextDueElapsedMs = null)
        }
        if (!evidence.level.audible) {
            return stop(LocalAlarmAdmission.SILENT, state.copy(lastCycle = null, reachedPercent = 0, pause = null))
        }
        if (!environment.enabled) return stop(LocalAlarmAdmission.DISABLED, state)
        if (!environment.armed) return stop(LocalAlarmAdmission.UNAVAILABLE, state)
        if (environment.mutedUntilWallMs > environment.nowWallMs) return stop(LocalAlarmAdmission.MUTED, state)

        state.pause?.let { pause ->
            if (environment.nowWallMs < pause.untilWallMs) return stop(LocalAlarmAdmission.ACKNOWLEDGED, state)
            state = state.copy(pause = null, lastCycle = null, nextDueElapsedMs = null)
        }
        val active = state.activeCycle
        if (active != null && environment.nowElapsedMs < active.deadlineElapsedMs) {
            return LocalAlarmEvaluation(state, LocalAlarmAdmission.ACTIVE)
        }
        if (active != null) state = state.copy(cycleActive = false)
        if (state.nextDueElapsedMs?.let { environment.nowElapsedMs < it } == true) {
            return LocalAlarmEvaluation(state, LocalAlarmAdmission.WAITING, cancelActive = oldActive != state.activeCycle)
        }
        val profile = LocalAlarmProfiles.create(evidence.level, timing, state.reachedPercent)
            ?: return stop(LocalAlarmAdmission.INVALID_TIMING, state)
        val deadline = addTime(environment.nowElapsedMs, profile.cycleTimeoutMs)
            ?: return stop(LocalAlarmAdmission.OVERFLOW, state)
        val nextDue = addTime(environment.nowElapsedMs, profile.repeatMs)
            ?: return stop(LocalAlarmAdmission.OVERFLOW, state)
        if (state.ordinal == Long.MAX_VALUE) return stop(LocalAlarmAdmission.OVERFLOW, state)
        val cycle = LocalAlarmCycle(state.key, state.generation, state.ordinal + 1L, state.level,
            state.bootCount, environment.nowElapsedMs, deadline)
        return LocalAlarmEvaluation(
            state = state.copy(ordinal = cycle.ordinal, lastCycle = cycle, cycleActive = true, nextDueElapsedMs = nextDue),
            admission = LocalAlarmAdmission.START,
            startCycle = cycle,
            profile = profile,
            cancelActive = oldActive != null
        )
    }

    fun acknowledge(
        evidence: LocalAlarmEvidence,
        previous: LocalAlarmState?,
        environment: LocalAlarmEnvironment,
        timing: LocalAlarmTiming,
        action: LocalAlarmAcknowledgement
    ): LocalAlarmState? {
        if (previous == null || !validEnvironment(environment) || !environment.enabled || !environment.armed ||
            !validState(previous, environment) || !validEvidence(evidence, environment) || olderEvidence(evidence, previous) ||
            previous.key != evidence.key || previous.generation != evidence.generation || previous.bootCount != evidence.bootCount ||
            previous.level != evidence.level || previous.lastCycle == null || previous.pause != null ||
            action.key != previous.key || action.generation != previous.generation ||
            action.ordinal != previous.ordinal || action.level != previous.level
        ) return previous
        val profile = LocalAlarmProfiles.create(evidence.level, timing, previous.reachedPercent) ?: return previous
        val until = addTime(environment.nowWallMs, profile.repeatMs) ?: return previous
        return previous.copy(
            observedElapsedMs = evidence.observedElapsedMs,
            validUntilElapsedMs = evidence.validUntilElapsedMs,
            cycleActive = false,
            nextDueElapsedMs = null,
            pause = LocalAlarmPause(environment.nowWallMs, until, evidence.level)
        )
    }

    fun recordReached(
        state: LocalAlarmState,
        cycle: LocalAlarmCycle,
        targetPercent: Int,
        environment: LocalAlarmEnvironment
    ): LocalAlarmState {
        if (!validEnvironment(environment) || !environment.enabled || !environment.armed ||
            environment.mutedUntilWallMs > environment.nowWallMs || !validState(state, environment) ||
            state.bootCount != environment.bootCount || state.activeCycle != cycle ||
            environment.nowElapsedMs >= cycle.deadlineElapsedMs || environment.nowElapsedMs >= state.validUntilElapsedMs ||
            targetPercent !in 1..100 ||
            (targetPercent !in LocalAlarmProfiles.targets(state.level) && targetPercent != state.reachedPercent)
        ) return state
        return state.copy(reachedPercent = maxOf(state.reachedPercent, targetPercent))
    }

    private fun validEnvironment(environment: LocalAlarmEnvironment): Boolean = environment.nowElapsedMs >= 0L &&
        environment.nowWallMs >= 0L && environment.bootCount >= 0 && environment.mutedUntilWallMs >= 0L

    private fun validKey(key: LocalAlarmKey): Boolean = key.id.length in 1..128 &&
        key.id.isNotBlank() && key.id.all { it in ' '..'~' }

    private fun accepts(key: LocalAlarmKey, level: LocalAlarmLevel): Boolean = when (key.kind) {
        LocalAlarmSourceKind.GLUCOSE -> level != LocalAlarmLevel.PUMP_LINK && level != LocalAlarmLevel.DELIVERY_DIAGNOSTIC
        LocalAlarmSourceKind.PUMP_LINK -> level == LocalAlarmLevel.NONE || level == LocalAlarmLevel.PUMP_LINK
        LocalAlarmSourceKind.DELIVERY_DIAGNOSTIC -> level == LocalAlarmLevel.NONE || level == LocalAlarmLevel.DELIVERY_DIAGNOSTIC
    }

    private fun validEvidence(evidence: LocalAlarmEvidence, environment: LocalAlarmEnvironment): Boolean =
        evidence.authorized && validKey(evidence.key) && evidence.generation > 0L && accepts(evidence.key, evidence.level) &&
            evidence.bootCount == environment.bootCount && evidence.observedElapsedMs >= 0L &&
            evidence.observedElapsedMs <= environment.nowElapsedMs && evidence.validUntilElapsedMs > environment.nowElapsedMs

    private fun olderEvidence(evidence: LocalAlarmEvidence, previous: LocalAlarmState?): Boolean =
        previous != null && previous.bootCount == evidence.bootCount &&
            (evidence.generation < previous.generation || evidence.observedElapsedMs < previous.observedElapsedMs)

    private fun validState(state: LocalAlarmState, environment: LocalAlarmEnvironment): Boolean {
        if (!validStateShape(state)) return false
        if (state.bootCount == environment.bootCount && state.observedElapsedMs > environment.nowElapsedMs) return false
        if (state.lastCycle?.let { it.bootCount == environment.bootCount && it.startedElapsedMs > environment.nowElapsedMs } == true) return false
        if (state.pause?.let { it.startedWallMs > environment.nowWallMs } == true) return false
        return true
    }

    internal fun validStateShape(state: LocalAlarmState): Boolean {
        if (!validKey(state.key) || state.generation <= 0L || state.bootCount < 0 || !accepts(state.key, state.level) ||
            state.observedElapsedMs < 0L || state.validUntilElapsedMs <= state.observedElapsedMs ||
            state.ordinal < 0L || state.reachedPercent !in 0..100
        ) return false
        val cycle = state.lastCycle
        if (state.cycleActive && (cycle == null || state.pause != null)) return false
        if (cycle != null) {
            if (cycle.key != state.key || cycle.generation != state.generation || cycle.ordinal != state.ordinal ||
                cycle.bootCount != state.bootCount ||
                (cycle.level != state.level && state.pause?.level != cycle.level) ||
                !cycle.level.audible || cycle.ordinal <= 0L ||
                cycle.startedElapsedMs < 0L || cycle.deadlineElapsedMs < cycle.startedElapsedMs ||
                cycle.deadlineElapsedMs - cycle.startedElapsedMs != LOCAL_ALARM_CYCLE_TIMEOUT_MS
            ) return false
        }
        state.nextDueElapsedMs?.let { due ->
            if (cycle == null || due < cycle.startedElapsedMs || !validInterval(due - cycle.startedElapsedMs, cycle.level)) return false
        }
        state.pause?.let { pause ->
            if (cycle == null || pause.level != cycle.level || pause.level.priority < state.level.priority ||
                !pause.level.audible || !accepts(state.key, pause.level) ||
                pause.startedWallMs < 0L || pause.untilWallMs < pause.startedWallMs ||
                !validInterval(pause.untilWallMs - pause.startedWallMs, pause.level)
            ) return false
        }
        return true
    }

    private fun validInterval(durationMs: Long, level: LocalAlarmLevel): Boolean =
        if (level.strong) durationMs in 60_000L..600_000L else durationMs in 300_000L..1_800_000L

    private fun addTime(now: Long, delay: Long): Long? =
        if (now < 0L || delay < 0L || now > Long.MAX_VALUE - delay) null else now + delay
}
