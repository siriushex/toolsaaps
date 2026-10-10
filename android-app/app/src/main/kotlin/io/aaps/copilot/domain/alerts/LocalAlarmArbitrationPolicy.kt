package io.aaps.copilot.domain.alerts

// Pending age is queue metadata, not authority. Keep it stable across duplicate
// updates; reset it when the source's generation/level or eligibility changes.
data class LocalAlarmRequest(val evidence: LocalAlarmEvidence, val pendingSinceElapsedMs: Long)

enum class LocalAlarmArbitrationStatus { READY, IDLE, INVALID_SNAPSHOT, RECOVERY_REQUIRED }

data class LocalAlarmArbitrationDecision(
    val status: LocalAlarmArbitrationStatus,
    val selected: LocalAlarmEvidence? = null,
    val retainedCycle: LocalAlarmCycle? = null,
    val cancelCycle: LocalAlarmCycle? = null,
    val interruptedCycles: List<LocalAlarmCycle> = emptyList(),
    val nextWakeElapsedMs: Long? = null
)

/** Read-only selection. A replacement may start only after exact old cleanup. */
object LocalAlarmArbitrationPolicy {
    private data class Candidate(val request: LocalAlarmRequest, val previous: LocalAlarmState?,
        val evaluation: LocalAlarmEvaluation)

    fun evaluate(requests: List<LocalAlarmRequest>, states: Map<LocalAlarmKey, LocalAlarmState>,
        owner: LocalAlarmCycle?, environment: LocalAlarmEnvironment,
        timing: LocalAlarmTiming): LocalAlarmArbitrationDecision {
        val keys = boundedKeys(requests, owner)
        if (keys == null || !LocalAlarmPolicy.validEnvironment(environment) || !timing.valid() ||
            requests.any { it.pendingSinceElapsedMs < 0 || it.pendingSinceElapsedMs > environment.nowElapsedMs } ||
            states.any { (key, state) -> key !in keys || key != state.key || !LocalAlarmPolicy.validState(state, environment) }
        ) return LocalAlarmArbitrationDecision(LocalAlarmArbitrationStatus.INVALID_SNAPSHOT, cancelCycle = owner)

        val interrupted = states.values.mapNotNull { it.activeCycle }.filter { it != owner }
            .sortedWith(compareBy({ it.key.kind.name }, { it.key.id }))
        if (interrupted.isNotEmpty()) return LocalAlarmArbitrationDecision(
            LocalAlarmArbitrationStatus.RECOVERY_REQUIRED, cancelCycle = owner, interruptedCycles = interrupted)

        val candidates = requests.map { request ->
            val previous = states[request.evidence.key]
            Candidate(request, previous, LocalAlarmPolicy.evaluate(request.evidence, previous, environment, timing))
        }
        val retained = owner?.takeIf { cycle -> candidates.any {
            it.evaluation.admission == LocalAlarmAdmission.ACTIVE && it.evaluation.state?.activeCycle == cycle
        } }
        val ready = candidates.filter { it.evaluation.admission == LocalAlarmAdmission.START }
            .sortedWith(compareByDescending<Candidate> { it.request.evidence.level.priority }
                .thenBy { due(it, environment) }.thenBy { it.request.evidence.key.kind.name }
                .thenBy { it.request.evidence.key.id }).firstOrNull()
        val keep = retained != null && (ready == null || ready.request.evidence.level.priority <= retained.level.priority)
        val selected = ready?.request?.evidence.takeUnless { keep }
        val wake = candidates.flatMap { deadlines(it, environment) }
            .filter { it > environment.nowElapsedMs }.minOrNull()
        return LocalAlarmArbitrationDecision(
            if (keep || selected != null) LocalAlarmArbitrationStatus.READY else LocalAlarmArbitrationStatus.IDLE,
            selected = selected, retainedCycle = retained.takeIf { keep },
            cancelCycle = owner.takeUnless { keep }, nextWakeElapsedMs = wake)
    }

    internal fun boundedKeys(requests: List<LocalAlarmRequest>, owner: LocalAlarmCycle?): Set<LocalAlarmKey>? {
        if (requests.size > 64) return null
        val keys = requests.map { it.evidence.key }.toSet()
        if (keys.size != requests.size) return null
        val all = if (owner == null) keys else keys + owner.key
        return all.takeIf { it.size <= 64 }
    }

    private fun due(candidate: Candidate, environment: LocalAlarmEnvironment): Long {
        val previous = candidate.previous
        val evidence = candidate.request.evidence
        if (previous?.generation == evidence.generation && previous.bootCount == evidence.bootCount &&
            previous.level == evidence.level) {
            previous.pause?.let {
                val overdue = environment.nowWallMs - it.untilWallMs
                return environment.nowElapsedMs - minOf(environment.nowElapsedMs, maxOf(0L, overdue))
            }
            previous.nextDueElapsedMs?.let { return it }
        }
        return candidate.request.pendingSinceElapsedMs
    }

    private fun deadlines(candidate: Candidate, environment: LocalAlarmEnvironment): List<Long> {
        val evaluation = candidate.evaluation
        val expiry = candidate.request.evidence.validUntilElapsedMs
        val deadline = when (evaluation.admission) {
            LocalAlarmAdmission.ACTIVE -> evaluation.state?.activeCycle?.deadlineElapsedMs
            LocalAlarmAdmission.WAITING -> evaluation.state?.nextDueElapsedMs
            LocalAlarmAdmission.ACKNOWLEDGED -> evaluation.state?.pause?.untilWallMs?.let { elapsed(it, environment) }
            LocalAlarmAdmission.MUTED -> elapsed(environment.mutedUntilWallMs, environment)
            LocalAlarmAdmission.START -> null
            else -> return emptyList()
        }
        return listOfNotNull(expiry, deadline)
    }

    private fun elapsed(wallDeadline: Long, environment: LocalAlarmEnvironment): Long? {
        if (wallDeadline <= environment.nowWallMs) return null
        val delay = wallDeadline - environment.nowWallMs
        return if (environment.nowElapsedMs > Long.MAX_VALUE - delay) null else environment.nowElapsedMs + delay
    }
}
