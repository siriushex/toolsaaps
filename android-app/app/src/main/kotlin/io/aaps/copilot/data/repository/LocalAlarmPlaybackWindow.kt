package io.aaps.copilot.data.repository

import io.aaps.copilot.domain.alerts.LocalAlarmCycle
import io.aaps.copilot.domain.alerts.LocalAlarmPolicy
import io.aaps.copilot.domain.alerts.LocalAlarmProfiles
import io.aaps.copilot.domain.alerts.LocalAlarmState
import io.aaps.copilot.domain.alerts.LocalAlarmTiming

internal class LocalAlarmPlaybackWindow private constructor(
    val cycle: LocalAlarmCycle,
    val stepIndex: Int,
    private val startsElapsedMs: Long,
    private val startDeadlineElapsedMs: Long,
    private val durationMs: Int
) {
    fun remainingStartMs(now: Long): Long? =
        if (now >= startsElapsedMs && now < startDeadlineElapsedMs) startDeadlineElapsedMs - now else null

    fun playDurationMs(now: Long): Int? =
        if (now >= startsElapsedMs && now < cycle.deadlineElapsedMs)
            minOf(durationMs.toLong(), cycle.deadlineElapsedMs - now).toInt() else null

    companion object {
        fun create(cycle: LocalAlarmCycle, stepIndex: Int, durationMs: Int): LocalAlarmPlaybackWindow? {
            val shape = LocalAlarmState(cycle.key, cycle.generation, cycle.level, cycle.bootCount,
                cycle.startedElapsedMs, cycle.deadlineElapsedMs, cycle.ordinal, cycle, cycleActive = true)
            if (!LocalAlarmPolicy.validStateShape(shape)) return null
            val timing = if (cycle.level.strong) LocalAlarmTiming(strongClipMs = durationMs)
                else LocalAlarmTiming(softClipMs = durationMs)
            val profile = LocalAlarmProfiles.create(cycle.level, timing) ?: return null
            val step = profile.steps.getOrNull(stepIndex)?.takeIf { it.startsClip } ?: return null
            val start = cycle.startedElapsedMs + step.offsetMs
            val deadline = profile.steps.getOrNull(stepIndex + 1)?.let { cycle.startedElapsedMs + it.offsetMs }
                ?: cycle.deadlineElapsedMs
            return LocalAlarmPlaybackWindow(cycle, stepIndex, start, deadline, durationMs)
        }
    }
}
