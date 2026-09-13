package io.aaps.copilot.config

enum class TargetManagerTimingSetting {
    RETARGET, POST_HYPO, PATTERN, SEGMENT
}

internal fun AppSettings.withTargetManagerTiming(
    setting: TargetManagerTimingSetting,
    minutes: Int
): AppSettings {
    require(if (setting == TargetManagerTimingSetting.RETARGET) minutes in listOf(5, 15, 30) else minutes in 0..240)
    return when (setting) {
        TargetManagerTimingSetting.RETARGET -> copy(adaptiveControllerRetargetMinutes = minutes)
        TargetManagerTimingSetting.POST_HYPO -> copy(rulePostHypoCooldownMinutes = minutes)
        TargetManagerTimingSetting.PATTERN -> copy(rulePatternCooldownMinutes = minutes)
        TargetManagerTimingSetting.SEGMENT -> copy(ruleSegmentCooldownMinutes = minutes)
    }
}
