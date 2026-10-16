package io.aaps.copilot.domain.alerts

object LocalAlarmProfiles {
    private val ordinaryTargets = listOf(25, 50, 75, 100)
    private val urgentTargets = listOf(70, 85, 100, 100)
    private val softTargets = listOf(25)

    fun create(level: LocalAlarmLevel, timing: LocalAlarmTiming, reachedPercent: Int = 0): LocalAlarmProfile? {
        if (!level.audible || !timing.valid() || reachedPercent !in 0..100) return null
        val duration = if (level.strong) timing.strongClipMs else timing.softClipMs
        val repeatMinutes = if (level.strong) timing.strongRepeatMinutes else timing.softRepeatMinutes
        return LocalAlarmProfile(
            steps = targets(level).mapIndexed { index, target ->
                LocalAlarmStep(
                    offsetMs = if (level.strong) duration.toLong() * index / 4L else index * 15_000L,
                    targetPercent = if (level.strong) maxOf(target, reachedPercent) else target,
                    startsClip = !level.strong || index == 0
                )
            },
            clipDurationMs = duration,
            repeatMs = repeatMinutes * 60_000L
        )
    }

    internal fun stepDeadlineOffsetMs(profile: LocalAlarmProfile, index: Int): Long =
        profile.steps.getOrNull(index + 1)?.offsetMs
            ?: if (profile.steps.size == 1) 15_000L else profile.cycleTimeoutMs

    fun targetVolume(percent: Int, maxVolume: Int, currentVolume: Int): Int? {
        if (percent !in 1..100 || maxVolume <= 0 || currentVolume !in 0..maxVolume) return null
        return maxOf(currentVolume, ((maxVolume.toLong() * percent + 99L) / 100L).toInt())
    }

    internal fun targets(level: LocalAlarmLevel): List<Int> =
        when {
            !level.strong -> softTargets
            level == LocalAlarmLevel.LOW_NOW -> urgentTargets
            else -> ordinaryTargets
        }
}
