package io.aaps.copilot.domain.alerts

object LocalAlarmProfiles {
    private val ordinaryTargets = listOf(25, 50, 75, 100)
    private val urgentTargets = listOf(70, 85, 100, 100)

    fun create(level: LocalAlarmLevel, timing: LocalAlarmTiming, reachedPercent: Int = 0): LocalAlarmProfile? {
        if (!level.audible || !timing.valid() || reachedPercent !in 0..100) return null
        val duration = if (level.strong) timing.strongClipMs else timing.softClipMs
        val repeatMinutes = if (level.strong) timing.strongRepeatMinutes else timing.softRepeatMinutes
        return LocalAlarmProfile(
            steps = targets(level).mapIndexed { index, target ->
                LocalAlarmStep(
                    offsetMs = if (level.strong) duration.toLong() * index / 4L else index * 15_000L,
                    targetPercent = maxOf(target, reachedPercent),
                    startsClip = !level.strong || index == 0
                )
            },
            clipDurationMs = duration,
            repeatMs = repeatMinutes * 60_000L
        )
    }

    fun targetVolume(percent: Int, maxVolume: Int, currentVolume: Int): Int? {
        if (percent !in 1..100 || maxVolume <= 0 || currentVolume !in 0..maxVolume) return null
        return maxOf(currentVolume, ((maxVolume.toLong() * percent + 99L) / 100L).toInt())
    }

    internal fun targets(level: LocalAlarmLevel): List<Int> =
        if (level == LocalAlarmLevel.LOW_NOW) urgentTargets else ordinaryTargets
}
